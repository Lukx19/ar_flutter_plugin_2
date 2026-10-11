package com.uhg0.ar_flutter_plugin_2.gridrenderer

import com.uhg0.ar_flutter_plugin_2.visibilitycontract.RendererDeltaLayout
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Result of offering one renderer delta to the scheduler. */
internal enum class DeltaIntake(val code: Int) {
    /** Queued for upload. */
    ACCEPTED(0),

    /** Dropped; the sender must request a full resynchronisation (reset first). */
    RESYNC_REQUIRED(1),

    /** Dropped because the message is malformed. */
    MALFORMED(2),

    /** Dropped because the render thread failed; nothing is drawn until a new surface starts a working thread. */
    RENDERER_FAILED(4),
}

/** Upload statistics of the scheduler. */
internal class UploadSchedulerCounters {
    var offered = 0L
    var accepted = 0L
    var droppedAwaitingReset = 0L
    var overflows = 0L
    var malformed = 0L
    var frames = 0L
    var lastFrameBytes = 0
    var maxFrameBytes = 0
    var lastFrameMessages = 0
    var deferredFrames = 0L
    var maxBacklogBytes = 0
}

/**
 * Moves renderer deltas from the channel thread to the render thread with a
 * per-frame upload byte budget.
 *
 * [offer] copies each message into a preallocated direct ring (no allocation
 * per message) and returns at once. [drainFrame] runs on the render thread:
 * it applies queued messages in order until the next one would exceed the
 * frame's budget or the target's staging capacity, always at least one
 * uploading message per frame so the queue makes progress. Messages are never dropped
 * silently: when the ring is full, intake switches to awaiting a reset and
 * replies [DeltaIntake.RESYNC_REQUIRED] until the sender's reset arrives.
 * A new scheduler starts awaiting a reset, so a renderer recreated after a
 * view replacement resynchronises with the grid.
 */
internal class DeltaUploadScheduler(
    ringBytes: Int = DEFAULT_RING_BYTES,
    private val maxQueued: Int = DEFAULT_MAX_QUEUED,
) {
    private val ring: ByteBuffer = ByteBuffer.allocateDirect(ringBytes).order(ByteOrder.LITTLE_ENDIAN)
    private val capacity = ringBytes
    private val starts = IntArray(maxQueued)
    private val lengths = IntArray(maxQueued)
    private val consumed = IntArray(maxQueued)
    private val lock = Any()
    private var head = 0
    private var queued = 0
    private var readPos = 0
    private var writePos = 0
    private var usedBytes = 0
    private var awaitingReset = true
    private var epoch = 0
    private var applying = false
    private val readView: ByteBuffer = ring.duplicate().order(ByteOrder.LITTLE_ENDIAN)

    // Write view of the ring for bulk copies (channel thread, under [lock]).
    private val ringWriter: ByteBuffer = ring.duplicate()

    val counters = UploadSchedulerCounters()

    /** Bytes queued and not yet applied. */
    val backlogBytes: Int get() = synchronized(lock) { usedBytes }

    val queuedMessages: Int get() = synchronized(lock) { queued }

    val isAwaitingReset: Boolean get() = synchronized(lock) { awaitingReset }

    /** Copies the message `src[offset, offset + length)` into the ring. Channel thread. */
    fun offer(src: ByteBuffer, offset: Int, length: Int): DeltaIntake {
        synchronized(lock) {
            counters.offered++
            if (length < RendererDeltaLayout.HEADER_BYTES || length > RendererDeltaLayout.MAX_MESSAGE_BYTES ||
                offset < 0 || offset + length > src.limit()
            ) {
                counters.malformed++
                return DeltaIntake.MALFORMED
            }
            val kind = src.get(offset + RendererDeltaLayout.KIND).toInt() and 0xff
            if (awaitingReset) {
                // The render thread may still read the discarded ring; the
                // sender retries its reset with the next export call.
                if (kind != RendererDeltaLayout.KIND_RESET || applying) {
                    counters.droppedAwaitingReset++
                    return DeltaIntake.RESYNC_REQUIRED
                }
                awaitingReset = false
            }
            val at = reserve(length)
            if (at < 0) {
                counters.overflows++
                awaitingReset = true
                discardAll()
                return DeltaIntake.RESYNC_REQUIRED
            }
            copyIntoRing(src, offset, length, at)
            counters.accepted++
            if (usedBytes > counters.maxBacklogBytes) counters.maxBacklogBytes = usedBytes
            return DeltaIntake.ACCEPTED
        }
    }

    /** Bulk copy of [length] bytes of [src] at [offset] into the ring at [at]; [src]'s position and limit are restored. */
    private fun copyIntoRing(src: ByteBuffer, offset: Int, length: Int, at: Int) {
        val position = src.position()
        val limit = src.limit()
        try {
            src.limit(offset + length).position(offset)
            ringWriter.limit(at + length).position(at)
            ringWriter.put(src)
        } finally {
            src.limit(limit).position(position)
        }
    }

    /** Drops the queue and waits for the sender's reset, for example after the GL context was lost. */
    fun requireReset() {
        synchronized(lock) {
            awaitingReset = true
            discardAll()
        }
    }

    /** Convenience for a heap or direct array. */
    fun offer(bytes: ByteArray, length: Int = bytes.size): DeltaIntake =
        offer(ByteBuffer.wrap(bytes, 0, length).order(ByteOrder.LITTLE_ENDIAN), 0, length)

    /**
     * Applies queued messages to [store] within [budgetBytes] of uploads (and
     * the target's staging capacity). Render thread. Returns uploaded bytes.
     */
    fun drainFrame(store: InstanceBufferStore, target: GpuBufferTarget, budgetBytes: Int): Int {
        var uploaded = 0
        var messages = 0
        while (true) {
            val start: Int
            val length: Int
            val peeked: Int
            synchronized(lock) {
                if (queued == 0) {
                    finishFrame(uploaded, messages, deferred = false)
                    return uploaded
                }
                start = starts[head]
                length = lengths[head]
                peeked = epoch
                applying = true
            }
            try {
                val cost = store.uploadBytes(readView, start, length)
                val staging = target.frameUploadCapacity()
                if ((uploaded > 0 && uploaded + cost > minOf(budgetBytes, staging)) || cost > staging) {
                    finishFrame(uploaded, messages, deferred = true)
                    return uploaded
                }
                store.apply(readView, start, length)
                uploaded += cost
                messages++
            } finally {
                synchronized(lock) { applying = false }
            }
            synchronized(lock) { if (peeked == epoch && queued > 0) pop() }
        }
    }

    private fun finishFrame(bytes: Int, messages: Int, deferred: Boolean) {
        counters.frames++
        counters.lastFrameBytes = bytes
        counters.lastFrameMessages = messages
        if (bytes > counters.maxFrameBytes) counters.maxFrameBytes = bytes
        if (deferred) counters.deferredFrames++
    }

    /** Ring offset for [length] contiguous bytes, or -1 when full. Holds [lock]. */
    private fun reserve(length: Int): Int {
        if (queued == maxQueued) return -1
        if (queued == 0) {
            readPos = 0
            writePos = 0
        }
        val at: Int
        var pad = 0
        if (queued == 0 || writePos > readPos || (writePos == readPos && usedBytes == 0)) {
            if (capacity - writePos >= length) {
                at = writePos
            } else if (readPos >= length) {
                pad = capacity - writePos
                at = 0
            } else {
                return -1
            }
        } else {
            if (readPos - writePos >= length) at = writePos else return -1
        }
        val tail = (head + queued) % maxQueued
        starts[tail] = at
        lengths[tail] = length
        consumed[tail] = length + pad
        queued++
        usedBytes += length + pad
        writePos = at + length
        if (writePos == capacity) writePos = 0
        return at
    }

    private fun pop() {
        usedBytes -= consumed[head]
        head = (head + 1) % maxQueued
        queued--
        if (queued == 0) {
            readPos = 0
            writePos = 0
            usedBytes = 0
        } else {
            readPos = starts[head]
        }
    }

    private fun discardAll() {
        epoch++
        head = 0
        queued = 0
        readPos = 0
        writePos = 0
        usedBytes = 0
    }

    companion object {
        /** About 32 full messages; a backlog beyond it means the renderer stalled. */
        const val DEFAULT_RING_BYTES = 2 * 1024 * 1024
        const val DEFAULT_MAX_QUEUED = 4096

        /**
         * Upload bytes per frame. The measured prototype uploaded dirty bricks
         * up to 64 KiB per frame; larger single uploads stalled the tablet CPU
         * up to 17 ms when written straight into buffers in use.
         */
        const val DEFAULT_FRAME_BUDGET_BYTES = 64 * 1024
    }
}
