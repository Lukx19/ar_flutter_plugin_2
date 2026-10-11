package com.uhg0.ar_flutter_plugin_2.visibilityingest

import com.uhg0.ar_flutter_plugin_2.visibilitycontract.IngestMessageLayout
import io.flutter.plugin.common.BasicMessageChannel
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Monotonic clock in nanoseconds (`SystemClock.elapsedRealtimeNanos` in production). */
internal fun interface IngestClock {
    fun nowNs(): Long
}

/**
 * Accepts complete ingest messages (header plus payload, contract 2.4) from
 * any producer: the shell's own depth, point, photo and session producers and
 * later producers such as marker detections (kind 5). The message bytes are
 * copied before the call returns; the sink stamps sequence, send time and
 * the dropped count.
 */
internal interface IngestMessageSink {
    /**
     * Offers `message[0, length)` (little-endian; the copy resets its position
     * and limit). Kinds 1, 2, 3 and 5 keep only the freshest
     * unsent message (a replaced one counts as dropped); kinds 4, 6 and 8
     * are queued in order. Returns false when the message was refused (malformed,
     * oversized or a full queue); the refusal is counted.
     */
    fun offer(message: ByteBuffer, length: Int): Boolean

    /** Counts a message of [kind] dropped before it was offered (stale, refused, replaced while staged). */
    fun countSourceDrop(kind: Int) {}
}

/**
 * Pull side of the ingest channel `capture3d/visibility_grid/ingest`.
 *
 * Background isolates cannot receive platform-initiated messages, so the grid
 * isolate pulls: each 8 B pull request is answered with the freshest unsent
 * accepted message, or parked until one is offered; a parked pull is answered
 * with an idle message after [IDLE_AFTER_NS]. Only one pull is in flight.
 *
 * Buffers are allocated once. Offered messages are copied into per-kind slots;
 * delivery copies the slot into one of two alternating reply buffers
 * (copy-on-reply). Offers never touch a reply buffer, and a replied buffer is
 * rewritten only by the reply after next, which needs another pull (the grid
 * isolate pulls again only after it handled the previous message).
 */
internal class IngestPullHandler(
    private val clock: IngestClock,
    private val recorder: IngestRecorder? = null,
) : IngestMessageSink {
    companion object {
        const val IDLE_AFTER_NS = 500_000_000L
        const val PHOTO_QUEUE = 32
        const val SESSION_QUEUE = 64
        const val PHOTO_MESSAGE_BYTES = IngestMessageLayout.HEADER_BYTES + 128
        const val SESSION_MESSAGE_BYTES = IngestMessageLayout.HEADER_BYTES + IngestMessageLayout.SESSION_EVENT_BYTES

        /** Photo tie point batches waiting (one per saved photo; detection takes about 80 ms each). */
        const val TIE_POINT_QUEUE = 8

        /** Header, `C3TB` batch header and up to 128 native tie point records of 64 B. */
        const val TIE_POINT_MESSAGE_BYTES = IngestMessageLayout.HEADER_BYTES + 64 + 128 * 64
        private const val KINDS = 9

        /** Delivery order of the freshest-only kinds. */
        private val LATEST_ORDER = intArrayOf(
            IngestMessageLayout.KIND_DEPTH_IMAGE,
            IngestMessageLayout.KIND_MARKER_DETECTIONS,
            IngestMessageLayout.KIND_POINT_CLOUD,
            IngestMessageLayout.KIND_AGGREGATED_VOXELS,
        )

        private fun direct(bytes: Int): ByteBuffer = ByteBuffer.allocateDirect(bytes).order(ByteOrder.LITTLE_ENDIAN)
    }

    private val lock = Object()

    // Freshest-only slots (kinds 1, 2, 3, 5), index by kind.
    private val latest = arrayOfNulls<ByteBuffer>(KINDS)
    private val latestLength = IntArray(KINDS)
    private val latestFresh = BooleanArray(KINDS)

    // In-order queues (kinds 4, 6, 8).
    private val photoRing = Array(PHOTO_QUEUE) { direct(PHOTO_MESSAGE_BYTES) }
    private var photoHead = 0
    private var photoCount = 0
    private val sessionRing = Array(SESSION_QUEUE) { direct(SESSION_MESSAGE_BYTES) }
    private var sessionHead = 0
    private var sessionCount = 0
    private val tieRing = Array(TIE_POINT_QUEUE) { direct(TIE_POINT_MESSAGE_BYTES) }
    private var tieHead = 0
    private var tieCount = 0

    private val replies = arrayOf(direct(IngestMessageLayout.MAX_MESSAGE_BYTES), direct(IngestMessageLayout.MAX_MESSAGE_BYTES))
    private var lastReply = 1

    private val sequence = IntArray(KINDS)
    private val droppedSinceSent = IntArray(KINDS)
    private var idleSequence = 0

    private var pending: BasicMessageChannel.Reply<ByteBuffer>? = null
    private var pendingMask = 0
    private var pendingSinceNs = 0L
    private var closed = false

    // Counters (read under the lock by [snapshot]).
    private val sent = LongArray(KINDS)
    private val replaced = LongArray(KINDS)
    private var refused = 0L
    private var queueOverflow = 0L
    private var malformedPulls = 0L
    private var idleReplies = 0L

    init {
        for (kind in LATEST_ORDER) latest[kind] = direct(IngestMessageLayout.MAX_MESSAGE_BYTES)
    }

    override fun offer(message: ByteBuffer, length: Int): Boolean {
        val valid = length >= IngestMessageLayout.HEADER_BYTES && length <= IngestMessageLayout.MAX_MESSAGE_BYTES &&
            message.capacity() >= length && message.order() == ByteOrder.LITTLE_ENDIAN &&
            message.getInt(0) == IngestMessageLayout.MAGIC &&
            IngestMessageWriter.messageBytes(message) == length
        synchronized(lock) {
            if (!valid || closed) {
                refused++
                return false
            }
            val kind = IngestMessageWriter.kind(message)
            when (kind) {
                IngestMessageLayout.KIND_PHOTO_SAVED -> {
                    if (length > PHOTO_MESSAGE_BYTES || photoCount == PHOTO_QUEUE) return overflowLocked(kind)
                    val slot = photoRing[(photoHead + photoCount) % PHOTO_QUEUE]
                    copy(message, length, slot)
                    stampSequenceLocked(slot, kind)
                    photoCount++
                }
                IngestMessageLayout.KIND_SESSION_EVENT -> {
                    if (length > SESSION_MESSAGE_BYTES || sessionCount == SESSION_QUEUE) return overflowLocked(kind)
                    val slot = sessionRing[(sessionHead + sessionCount) % SESSION_QUEUE]
                    copy(message, length, slot)
                    stampSequenceLocked(slot, kind)
                    sessionCount++
                }
                IngestMessageLayout.KIND_PHOTO_TIE_POINTS -> {
                    if (length > TIE_POINT_MESSAGE_BYTES || tieCount == TIE_POINT_QUEUE) return overflowLocked(kind)
                    val slot = tieRing[(tieHead + tieCount) % TIE_POINT_QUEUE]
                    copy(message, length, slot)
                    stampSequenceLocked(slot, kind)
                    tieCount++
                }
                IngestMessageLayout.KIND_DEPTH_IMAGE, IngestMessageLayout.KIND_POINT_CLOUD,
                IngestMessageLayout.KIND_AGGREGATED_VOXELS, IngestMessageLayout.KIND_MARKER_DETECTIONS -> {
                    val slot = latest[kind]!!
                    if (latestFresh[kind]) {
                        replaced[kind]++
                        droppedSinceSent[kind]++
                    }
                    copy(message, length, slot)
                    stampSequenceLocked(slot, kind)
                    latestLength[kind] = length
                    latestFresh[kind] = true
                }
                else -> {
                    refused++
                    return false
                }
            }
            deliverPendingLocked()
            return true
        }
    }

    override fun countSourceDrop(kind: Int) {
        synchronized(lock) { droppedSinceSent[kind]++ }
    }

    private fun overflowLocked(kind: Int): Boolean {
        queueOverflow++
        droppedSinceSent[kind]++
        return false
    }

    private fun stampSequenceLocked(slot: ByteBuffer, kind: Int) {
        IngestMessageWriter.sequence(slot, sequence[kind]++)
    }

    /** Channel handler; runs on the background task queue. */
    fun onPull(message: ByteBuffer?, reply: BasicMessageChannel.Reply<ByteBuffer>) {
        val mask = pullMask(message)
        synchronized(lock) {
            pending?.let { previous ->
                pending = null
                replyIdleLocked(previous)
            }
            if (mask < 0) malformedPulls++
            if (closed || mask < 0) {
                replyIdleLocked(reply)
                return
            }
            if (!deliverLocked(reply, mask)) {
                pending = reply
                pendingMask = mask
                pendingSinceNs = clock.nowNs()
                lock.notifyAll()
            }
        }
    }

    /** Answers a parked pull with idle once it waited [IDLE_AFTER_NS]; returns ns until the next deadline or -1. */
    fun tick(): Long {
        synchronized(lock) {
            val p = pending ?: return -1L
            val waited = clock.nowNs() - pendingSinceNs
            if (waited >= IDLE_AFTER_NS) {
                pending = null
                replyIdleLocked(p)
                return -1L
            }
            return IDLE_AFTER_NS - waited
        }
    }

    /** Blocks the idle timer thread until a parked pull may time out; returns false once closed. */
    fun awaitIdleDeadline(): Boolean {
        synchronized(lock) {
            if (closed) return false
            val remaining = if (pending == null) -1L else IDLE_AFTER_NS - (clock.nowNs() - pendingSinceNs)
            if (remaining < 0L && pending == null) {
                lock.wait()
            } else if (remaining > 0L) {
                lock.wait(remaining / 1_000_000L + 1L)
            }
            return !closed
        }
    }

    /** Drops queued data (session end); a parked pull stays parked. */
    fun clearQueued() {
        synchronized(lock) {
            for (kind in LATEST_ORDER) latestFresh[kind] = false
            photoCount = 0
            sessionCount = 0
            tieCount = 0
        }
    }

    fun close() {
        synchronized(lock) {
            closed = true
            pending?.let {
                pending = null
                replyIdleLocked(it)
            }
            lock.notifyAll()
        }
    }

    private fun pullMask(message: ByteBuffer?): Int {
        if (message == null || message.remaining() < IngestMessageLayout.PULL_REQUEST_BYTES) return -1
        val b = message.order(ByteOrder.LITTLE_ENDIAN)
        val at = b.position()
        if (b.getInt(at) != IngestMessageLayout.PULL_REQUEST_MAGIC || b.getShort(at + 4).toInt() != 1) return -1
        return b.getShort(at + IngestMessageLayout.PULL_REQUEST_MASK).toInt() and 0xffff
    }

    private fun deliverPendingLocked() {
        val p = pending ?: return
        if (deliverLocked(p, pendingMask)) pending = null
    }

    private fun accepts(mask: Int, kind: Int): Boolean = (mask shr kind) and 1 != 0

    private fun deliverLocked(reply: BasicMessageChannel.Reply<ByteBuffer>, mask: Int): Boolean {
        if (photoCount > 0 && accepts(mask, IngestMessageLayout.KIND_PHOTO_SAVED)) {
            val slot = photoRing[photoHead]
            photoHead = (photoHead + 1) % PHOTO_QUEUE
            photoCount--
            replyLocked(reply, slot, IngestMessageWriter.messageBytes(slot), IngestMessageLayout.KIND_PHOTO_SAVED)
            return true
        }
        if (tieCount > 0 && accepts(mask, IngestMessageLayout.KIND_PHOTO_TIE_POINTS)) {
            val slot = tieRing[tieHead]
            tieHead = (tieHead + 1) % TIE_POINT_QUEUE
            tieCount--
            replyLocked(reply, slot, IngestMessageWriter.messageBytes(slot), IngestMessageLayout.KIND_PHOTO_TIE_POINTS)
            return true
        }
        if (sessionCount > 0 && accepts(mask, IngestMessageLayout.KIND_SESSION_EVENT)) {
            val slot = sessionRing[sessionHead]
            sessionHead = (sessionHead + 1) % SESSION_QUEUE
            sessionCount--
            replyLocked(reply, slot, IngestMessageWriter.messageBytes(slot), IngestMessageLayout.KIND_SESSION_EVENT)
            return true
        }
        for (kind in LATEST_ORDER) {
            if (latestFresh[kind] && accepts(mask, kind)) {
                latestFresh[kind] = false
                replyLocked(reply, latest[kind]!!, latestLength[kind], kind)
                return true
            }
        }
        return false
    }

    /**
     * Alternates the two reply buffers: the previous reply stays untouched
     * while the next one is written, and a buffer is rewritten only after the
     * grid isolate pulled again (it pulls only after handling a reply).
     */
    private fun freeReplyLocked(): ByteBuffer {
        lastReply = 1 - lastReply
        return replies[lastReply]
    }

    private fun replyLocked(reply: BasicMessageChannel.Reply<ByteBuffer>, source: ByteBuffer, length: Int, kind: Int) {
        val b = freeReplyLocked()
        copy(source, length, b)
        IngestMessageWriter.sendTime(b, clock.nowNs())
        IngestMessageWriter.dropped(b, droppedSinceSent[kind])
        droppedSinceSent[kind] = 0
        sent[kind]++
        recorder?.record(b, length)
        b.limit(b.capacity())
        b.position(length) // the engine sends position() bytes from index 0
        reply.reply(b)
    }

    private fun replyIdleLocked(reply: BasicMessageChannel.Reply<ByteBuffer>) {
        val b = freeReplyLocked()
        IngestMessageWriter.begin(b, IngestMessageLayout.KIND_IDLE)
        IngestMessageWriter.sequence(b, idleSequence++)
        IngestMessageWriter.sendTime(b, clock.nowNs())
        b.put(IngestMessageLayout.POSE_MATCH, IngestMessageLayout.POSE_MATCH_MISSING.toByte())
        idleReplies++
        b.limit(b.capacity())
        b.position(IngestMessageLayout.HEADER_BYTES)
        reply.reply(b)
    }

    /** Copies `source[0, length)` to the start of [target]; both are left cleared. Allocation-free. */
    private fun copy(source: ByteBuffer, length: Int, target: ByteBuffer) {
        source.limit(length)
        source.position(0)
        target.clear()
        target.put(source)
        source.clear()
        target.clear()
    }

    /** Diagnostic counters (allocates; not for the frame path). */
    fun snapshot(): Map<String, Any> = synchronized(lock) {
        mapOf(
            "sent" to sent.toList(),
            "replaced" to replaced.toList(),
            "refused" to refused,
            "queueOverflow" to queueOverflow,
            "malformedPulls" to malformedPulls,
            "idleReplies" to idleReplies,
            "parked" to (pending != null),
        )
    }

    internal val idleReplyCount: Long get() = synchronized(lock) { idleReplies }
    internal val queueOverflowCount: Long get() = synchronized(lock) { queueOverflow }
    internal fun sentCount(kind: Int): Long = synchronized(lock) { sent[kind] }
    internal fun replacedCount(kind: Int): Long = synchronized(lock) { replaced[kind] }
    internal val isParked: Boolean get() = synchronized(lock) { pending != null }
}
