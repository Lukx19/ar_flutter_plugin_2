package com.uhg0.ar_flutter_plugin_2.gridrenderer

import com.uhg0.ar_flutter_plugin_2.visibilitycontract.RendererDeltaLayout
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Outcome of validating one renderer delta message. */
internal enum class DeltaStatus { OK, TOO_SHORT, BAD_MAGIC, BAD_VERSION, BAD_KIND, BAD_LENGTH, TOO_LARGE }

/**
 * Reusable cursor over one renderer delta message (contract section 2.7) in a
 * little-endian [ByteBuffer]. [attach] moves it without allocating; all reads
 * are absolute, so the buffer position is never touched.
 */
internal class RendererDeltaReader {
    private var buffer: ByteBuffer = EMPTY
    private var base = 0
    private var available = 0

    /** Points the reader at the message `[offset, offset + length)` of [source]. */
    fun attach(source: ByteBuffer, offset: Int, length: Int): RendererDeltaReader {
        require(source.order() == ByteOrder.LITTLE_ENDIAN) { "renderer deltas are little-endian" }
        buffer = source
        base = offset
        available = length
        return this
    }

    fun validate(): DeltaStatus {
        if (available < RendererDeltaLayout.HEADER_BYTES || base + RendererDeltaLayout.HEADER_BYTES > buffer.limit()) {
            return DeltaStatus.TOO_SHORT
        }
        if (buffer.getInt(base) != RendererDeltaLayout.MAGIC) return DeltaStatus.BAD_MAGIC
        if ((buffer.getShort(base + 4).toInt() and 0xffff) != RendererDeltaLayout.VERSION) return DeltaStatus.BAD_VERSION
        val size = RendererDeltaLayout.recordBytes(kind)
        if (size < 0) return DeltaStatus.BAD_KIND
        val count = recordCountUnsigned
        if (count < 0 || count > RendererDeltaLayout.MAX_MESSAGE_BYTES) return DeltaStatus.TOO_LARGE
        val total = RendererDeltaLayout.HEADER_BYTES.toLong() + count.toLong() * size
        if (total > RendererDeltaLayout.MAX_MESSAGE_BYTES) return DeltaStatus.TOO_LARGE
        if (total != available.toLong()) return DeltaStatus.BAD_LENGTH
        if (base + total > buffer.limit()) return DeltaStatus.BAD_LENGTH
        return DeltaStatus.OK
    }

    val kind: Int get() = buffer.get(base + RendererDeltaLayout.KIND).toInt() and 0xff
    val gridVersion: Long get() = buffer.getInt(base + RendererDeltaLayout.GRID_VERSION).toLong() and 0xffffffffL
    val firstSlot: Int get() = buffer.getInt(base + RendererDeltaLayout.FIRST_SLOT)
    val recordCount: Int get() = buffer.getInt(base + RendererDeltaLayout.RECORD_COUNT)
    val bufferCapacity: Int get() = buffer.getInt(base + RendererDeltaLayout.BUFFER_CAPACITY)
    val brickKey: Long get() = buffer.getLong(base + RendererDeltaLayout.BRICK_KEY)

    /** Record count, or -1 when it does not fit an Int. */
    private val recordCountUnsigned: Int
        get() = recordCount.let { if (it < 0) -1 else it }

    /** Byte size of one record of this message's kind. */
    val recordBytes: Int get() = RendererDeltaLayout.recordBytes(kind)

    /** Absolute offset in the attached buffer of the first record. */
    val recordsOffset: Int get() = base + RendererDeltaLayout.HEADER_BYTES

    /** Bytes of all records of this message. */
    val recordsLength: Int get() = recordCount * recordBytes

    val source: ByteBuffer get() = buffer

    companion object {
        private val EMPTY: ByteBuffer = ByteBuffer.allocate(0).order(ByteOrder.LITTLE_ENDIAN)
    }
}

/**
 * Brick keys of the grid: signed 21-bit brick coordinates packed x highest,
 * mirroring `BrickKey` in the Dart grid contract.
 */
internal object GridBrickKey {
    private const val BIAS = 0x100000
    private const val MASK = 0x1fffffL
    const val BRICK_METRES = 0.8f
    const val VOXEL_METRES = 0.1f

    fun x(key: Long): Int = ((key shr 42) and MASK).toInt() - BIAS
    fun y(key: Long): Int = ((key shr 21) and MASK).toInt() - BIAS
    fun z(key: Long): Int = (key and MASK).toInt() - BIAS

    fun pack(x: Int, y: Int, z: Int): Long =
        ((x + BIAS).toLong() shl 42) or ((y + BIAS).toLong() shl 21) or (z + BIAS).toLong()
}
