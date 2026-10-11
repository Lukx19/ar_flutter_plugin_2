package com.uhg0.ar_flutter_plugin_2.gridrenderer

import java.nio.ByteBuffer

/** Byte-array mirror of the GPU buffers, counting every operation. */
internal class ByteArrayGpuTarget(var uploadCapacity: Int = Int.MAX_VALUE) : GpuBufferTarget {
    val bytes = Array(GridBuffer.values().size) { ByteArray(0) }
    var writes = 0
    var clears = 0
    var grows = 0
    var writtenBytes = 0L
    var frameWritten = 0

    val operations: Int get() = writes + clears + grows

    fun buffer(buffer: GridBuffer): ByteArray = bytes[buffer.ordinal]

    fun beginFrame() {
        frameWritten = 0
    }

    override fun ensureCapacity(buffer: GridBuffer, bytes: Int) {
        val old = this.bytes[buffer.ordinal]
        if (bytes <= old.size) return
        this.bytes[buffer.ordinal] = old.copyOf(bytes)
        grows++
    }

    override fun write(buffer: GridBuffer, dstOffset: Int, src: ByteBuffer, srcOffset: Int, length: Int) {
        val dst = bytes[buffer.ordinal]
        for (i in 0 until length) dst[dstOffset + i] = src.get(srcOffset + i)
        writes++
        writtenBytes += length
        frameWritten += length
    }

    override fun clear(buffer: GridBuffer, dstOffset: Int, length: Int) {
        bytes[buffer.ordinal].fill(0, dstOffset, dstOffset + length)
        clears++
        frameWritten += length
    }

    override fun frameUploadCapacity(): Int = (uploadCapacity - frameWritten).coerceAtLeast(0)
}
