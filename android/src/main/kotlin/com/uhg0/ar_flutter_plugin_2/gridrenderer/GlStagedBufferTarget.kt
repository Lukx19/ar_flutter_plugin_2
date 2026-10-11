package com.uhg0.ar_flutter_plugin_2.gridrenderer

import android.opengl.GLES30
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * GLES 3.0 instance buffers fed through a staged upload ring.
 *
 * Writing delta bytes straight into a buffer the GPU is still drawing from
 * makes the driver synchronise; the tablet measured CPU upload stalls up to
 * 17 ms that way. Instead each frame takes one of [slots] staging buffers
 * whose previous copies have completed (checked with a fence, never waited
 * on), maps a range of it unsynchronised, writes the bytes there and records
 * a GPU-side `glCopyBufferSubData` into the persistent buffer. When the next
 * staging buffer is still busy, [frameUploadCapacity] is zero and uploads
 * wait for a later frame instead of stalling this one.
 *
 * Persistent buffers grow by allocating a larger buffer and copying on the
 * GPU; the growth is announced by the exporter's `buffer capacity` field and
 * counted by [InstanceBufferStore].
 */
internal class GlStagedBufferTarget(
    private val stagingBytes: Int = DEFAULT_STAGING_BYTES,
    private val slots: Int = DEFAULT_SLOTS,
) : GpuBufferTarget {
    private val names = IntArray(GridBuffer.values().size)
    private val capacity = IntArray(GridBuffer.values().size)
    private val staging = IntArray(slots)
    private val fences = LongArray(slots)
    private var zeroBuffer = 0
    private var slot = 0
    private var cursor = 0
    private var slotUsable = false
    private val copyName = IntArray(1)

    var stagedBytes = 0L
        private set
    var busyFrames = 0L
        private set
    var growths = 0L
        private set

    /** GL buffer name of [buffer], 0 before its first allocation. */
    fun bufferName(buffer: GridBuffer): Int = names[buffer.ordinal]

    fun create() {
        GLES30.glGenBuffers(slots, staging, 0)
        for (s in 0 until slots) {
            GLES30.glBindBuffer(GLES30.GL_COPY_READ_BUFFER, staging[s])
            GLES30.glBufferData(GLES30.GL_COPY_READ_BUFFER, stagingBytes, null, GLES30.GL_STREAM_DRAW)
        }
        val zeros = ByteBuffer.allocateDirect(ZERO_BYTES).order(ByteOrder.nativeOrder())
        GLES30.glGenBuffers(1, copyName, 0)
        zeroBuffer = copyName[0]
        GLES30.glBindBuffer(GLES30.GL_COPY_READ_BUFFER, zeroBuffer)
        GLES30.glBufferData(GLES30.GL_COPY_READ_BUFFER, ZERO_BYTES, zeros, GLES30.GL_STATIC_DRAW)
        GLES30.glBindBuffer(GLES30.GL_COPY_READ_BUFFER, 0)
    }

    /** Picks this frame's staging buffer without waiting for the GPU. */
    fun beginFrame() {
        slot = (slot + 1) % slots
        cursor = 0
        val fence = fences[slot]
        slotUsable = if (fence == 0L) {
            true
        } else {
            val status = GLES30.glClientWaitSync(fence, 0, 0)
            if (status == GLES30.GL_ALREADY_SIGNALED || status == GLES30.GL_CONDITION_SATISFIED) {
                GLES30.glDeleteSync(fence)
                fences[slot] = 0L
                true
            } else {
                busyFrames++
                false
            }
        }
    }

    /** Fences this frame's copies so the staging buffer is reused only after they ran. */
    fun endFrame() {
        if (cursor > 0 && fences[slot] == 0L) {
            fences[slot] = GLES30.glFenceSync(GLES30.GL_SYNC_GPU_COMMANDS_COMPLETE, 0)
        }
    }

    override fun frameUploadCapacity(): Int = if (slotUsable) stagingBytes - cursor else 0

    override fun ensureCapacity(buffer: GridBuffer, bytes: Int) {
        val i = buffer.ordinal
        if (bytes <= capacity[i]) return
        GLES30.glGenBuffers(1, copyName, 0)
        val grown = copyName[0]
        GLES30.glBindBuffer(GLES30.GL_COPY_WRITE_BUFFER, grown)
        GLES30.glBufferData(GLES30.GL_COPY_WRITE_BUFFER, bytes, null, GLES30.GL_DYNAMIC_DRAW)
        GlFailureCheck.allocationFailure(GLES30.glGetError(), bytes)?.let { failure ->
            GLES30.glBindBuffer(GLES30.GL_COPY_WRITE_BUFFER, 0)
            GLES30.glDeleteBuffers(1, copyName, 0)
            throw IllegalStateException(failure)
        }
        val old = names[i]
        if (old != 0) {
            GLES30.glBindBuffer(GLES30.GL_COPY_READ_BUFFER, old)
            GLES30.glCopyBufferSubData(GLES30.GL_COPY_READ_BUFFER, GLES30.GL_COPY_WRITE_BUFFER, 0, 0, capacity[i])
            copyName[0] = old
            GLES30.glDeleteBuffers(1, copyName, 0)
            growths++
        }
        // New space must read as padding: zero surface/summary records are
        // unpublished, and compact voxels get explicit 0xFF padding by delta.
        clearRange(grown, capacity[i], bytes - capacity[i])
        names[i] = grown
        capacity[i] = bytes
        GLES30.glBindBuffer(GLES30.GL_COPY_WRITE_BUFFER, 0)
        GLES30.glBindBuffer(GLES30.GL_COPY_READ_BUFFER, 0)
    }

    override fun write(buffer: GridBuffer, dstOffset: Int, src: ByteBuffer, srcOffset: Int, length: Int) {
        if (length <= 0) return
        check(slotUsable && cursor + length <= stagingBytes) { "staging ring overrun" }
        GLES30.glBindBuffer(GLES30.GL_COPY_READ_BUFFER, staging[slot])
        val mapped = GLES30.glMapBufferRange(
            GLES30.GL_COPY_READ_BUFFER,
            cursor,
            length,
            GLES30.GL_MAP_WRITE_BIT or GLES30.GL_MAP_UNSYNCHRONIZED_BIT or GLES30.GL_MAP_INVALIDATE_RANGE_BIT,
        ) as ByteBuffer
        val view = src.duplicate()
        view.limit(srcOffset + length).position(srcOffset)
        mapped.put(view)
        GLES30.glUnmapBuffer(GLES30.GL_COPY_READ_BUFFER)
        GLES30.glBindBuffer(GLES30.GL_COPY_WRITE_BUFFER, names[buffer.ordinal])
        GLES30.glCopyBufferSubData(GLES30.GL_COPY_READ_BUFFER, GLES30.GL_COPY_WRITE_BUFFER, cursor, dstOffset, length)
        GLES30.glBindBuffer(GLES30.GL_COPY_WRITE_BUFFER, 0)
        GLES30.glBindBuffer(GLES30.GL_COPY_READ_BUFFER, 0)
        // Keep copy sources 4-byte aligned.
        cursor += (length + 3) and 3.inv()
        stagedBytes += length
    }

    override fun clear(buffer: GridBuffer, dstOffset: Int, length: Int) {
        clearRange(names[buffer.ordinal], dstOffset, length)
    }

    private fun clearRange(name: Int, dstOffset: Int, length: Int) {
        if (name == 0 || length <= 0) return
        GLES30.glBindBuffer(GLES30.GL_COPY_READ_BUFFER, zeroBuffer)
        GLES30.glBindBuffer(GLES30.GL_COPY_WRITE_BUFFER, name)
        var done = 0
        while (done < length) {
            val n = minOf(ZERO_BYTES, length - done)
            GLES30.glCopyBufferSubData(GLES30.GL_COPY_READ_BUFFER, GLES30.GL_COPY_WRITE_BUFFER, 0, dstOffset + done, n)
            done += n
        }
        GLES30.glBindBuffer(GLES30.GL_COPY_WRITE_BUFFER, 0)
        GLES30.glBindBuffer(GLES30.GL_COPY_READ_BUFFER, 0)
    }

    /** Deletes every GL object; the context must be current. */
    fun destroy() {
        for (s in 0 until slots) if (fences[s] != 0L) GLES30.glDeleteSync(fences[s])
        fences.fill(0L)
        GLES30.glDeleteBuffers(slots, staging, 0)
        copyName[0] = zeroBuffer
        GLES30.glDeleteBuffers(1, copyName, 0)
        for (i in names.indices) {
            if (names[i] != 0) {
                copyName[0] = names[i]
                GLES30.glDeleteBuffers(1, copyName, 0)
            }
        }
        names.fill(0)
        capacity.fill(0)
    }

    companion object {
        /** Staging bytes per frame slot; above the per-frame budget so one full message always fits. */
        const val DEFAULT_STAGING_BYTES = 256 * 1024
        const val DEFAULT_SLOTS = 3
        private const val ZERO_BYTES = 64 * 1024
    }
}
