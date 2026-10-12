package com.uhg0.ar_flutter_plugin_2.gridrenderer

import com.uhg0.ar_flutter_plugin_2.visibilitycontract.ColourTableLayout
import com.uhg0.ar_flutter_plugin_2.visibilitycontract.DisplayModeLayout
import com.uhg0.ar_flutter_plugin_2.visibilitycontract.RendererDeltaLayout
import com.uhg0.ar_flutter_plugin_2.visibilitycontract.SurfaceInstanceLayout
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Writes renderer delta, display mode and colour table messages in the
 * contract format into one reusable buffer. Used by synthetic renderer scenes
 * (instrumented style tests, diagnostics); production deltas come from the
 * Dart exporter.
 */
internal class RendererDeltaWriter(capacity: Int = RendererDeltaLayout.MAX_MESSAGE_BYTES) {
    val buffer: ByteBuffer = ByteBuffer.allocate(capacity).order(ByteOrder.LITTLE_ENDIAN)
    var length = 0
        private set

    fun begin(kind: Int, firstSlot: Int, records: Int, capacity: Int, brickKey: Long, gridVersion: Int = 1): RendererDeltaWriter {
        val bytes = RendererDeltaLayout.recordBytes(kind)
        length = RendererDeltaLayout.HEADER_BYTES + records * maxOf(bytes, 0)
        for (i in 0 until length) buffer.put(i, 0)
        buffer.putInt(0, RendererDeltaLayout.MAGIC)
        buffer.putShort(4, RendererDeltaLayout.VERSION.toShort())
        buffer.put(RendererDeltaLayout.KIND, kind.toByte())
        buffer.putInt(RendererDeltaLayout.GRID_VERSION, gridVersion)
        buffer.putInt(RendererDeltaLayout.FIRST_SLOT, firstSlot)
        buffer.putInt(RendererDeltaLayout.RECORD_COUNT, records)
        buffer.putInt(RendererDeltaLayout.BUFFER_CAPACITY, capacity)
        buffer.putLong(RendererDeltaLayout.BRICK_KEY, brickKey)
        return this
    }

    /** Writes surface record [index] of the current kind 1 message. */
    fun surface(
        index: Int, x: Float, y: Float, z: Float, nx: Float, ny: Float, nz: Float,
        size: Float = 0.1f, coverage: Int = 128, completion: Int = 1, confidence: Int = 200, age: Int = 0,
        source: Int = 1, flags: Int = SurfaceInstanceLayout.FLAG_PUBLISHED, binQuality: Int = 8, stableId: Int = index + 1,
    ) {
        val o = RendererDeltaLayout.HEADER_BYTES + index * SurfaceInstanceLayout.BYTES
        buffer.putFloat(o, x)
        buffer.putFloat(o + 4, y)
        buffer.putFloat(o + 8, z)
        val l1 = abs(nx) + abs(ny) + abs(nz)
        var ox = nx / l1
        var oy = ny / l1
        if (nz < 0) {
            val fx = (1 - abs(oy)) * (if (ox >= 0) 1f else -1f)
            val fy = (1 - abs(ox)) * (if (oy >= 0) 1f else -1f)
            ox = fx
            oy = fy
        }
        buffer.putShort(o + SurfaceInstanceLayout.NORMAL, (ox.coerceIn(-1f, 1f) * 32767f).roundToInt().toShort())
        buffer.putShort(o + SurfaceInstanceLayout.NORMAL + 2, (oy.coerceIn(-1f, 1f) * 32767f).roundToInt().toShort())
        buffer.putShort(o + SurfaceInstanceLayout.SIZE_F16, halfBits(size).toShort())
        buffer.put(o + SurfaceInstanceLayout.SOURCE, source.toByte())
        buffer.put(o + SurfaceInstanceLayout.FLAGS, flags.toByte())
        buffer.put(o + SurfaceInstanceLayout.COVERAGE, coverage.toByte())
        buffer.put(o + SurfaceInstanceLayout.COMPLETION, completion.toByte())
        buffer.put(o + SurfaceInstanceLayout.CONFIDENCE, confidence.toByte())
        buffer.put(o + SurfaceInstanceLayout.AGE, age.toByte())
        var word = 0
        for (i in 0 until 8) word = word or ((binQuality and 15) shl (4 * i))
        for (w in 0 until 3) buffer.putInt(o + SurfaceInstanceLayout.BINS + 4 * w, word)
        buffer.putInt(o + SurfaceInstanceLayout.STABLE_ID, stableId)
    }

    /** Writes compact voxel entry [index] (padding when [colour] is -1). */
    fun voxel(index: Int, vx: Int, vy: Int, vz: Int, colour: Int) {
        val o = RendererDeltaLayout.HEADER_BYTES + index * RendererDeltaLayout.COMPACT_VOXEL_BYTES
        if (colour < 0) {
            buffer.putLong(o, -1L)
            return
        }
        buffer.putShort(o, vx.toShort())
        buffer.putShort(o + 2, vy.toShort())
        buffer.putShort(o + 4, vz.toShort())
        buffer.putShort(o + 6, colour.toShort())
    }

    /** Writes brick summary [index] of the current kind 2 message. */
    fun brickSummary(index: Int, bx: Int, by: Int, bz: Int, surfaces: Int, complete: Int, meanCoverage: Int, residency: Int) {
        val o = RendererDeltaLayout.HEADER_BYTES + index * 16
        buffer.putShort(o, bx.toShort())
        buffer.putShort(o + 2, by.toShort())
        buffer.putShort(o + 4, bz.toShort())
        buffer.putShort(o + 6, surfaces.toShort())
        buffer.putShort(o + 8, complete.toShort())
        buffer.put(o + 10, meanCoverage.toByte())
        buffer.put(o + 11, residency.toByte())
        buffer.putInt(o + 12, 1)
    }

    /** Writes region summary [index] of the current kind 4 message. */
    fun regionSummary(index: Int, rx: Int, ry: Int, rz: Int, bricks: Int, surfaces: Int, completion: Int, residency: Int) {
        val o = RendererDeltaLayout.HEADER_BYTES + index * 16
        buffer.putShort(o, rx.toShort())
        buffer.putShort(o + 2, ry.toShort())
        buffer.putShort(o + 4, rz.toShort())
        buffer.putShort(o + 6, bricks.toShort())
        buffer.putInt(o + 8, surfaces)
        buffer.put(o + 12, completion.toByte())
        buffer.put(o + 13, residency.toByte())
    }

    /** Writes free range [index] of the current kind 5 message. */
    fun freeRange(index: Int, first: Int, count: Int) {
        val o = RendererDeltaLayout.HEADER_BYTES + index * RendererDeltaLayout.FREE_SLOT_RANGE_BYTES
        buffer.putInt(o, first)
        buffer.putInt(o + 4, count)
    }

    /** Writes a 32 B display mode message; returns its length. */
    fun displayMode(
        baseLayer: Int, overlayBits: Int, colourAttribute: Int, sizeRule: Int,
        spikeNearestK: Int = 512, pointSizePx: Float = 6f, generation: Int = 1, minQuality: Int = 0,
    ): Int {
        for (i in 0 until DisplayModeLayout.BYTES) buffer.put(i, 0)
        buffer.putInt(0, DisplayModeLayout.MAGIC)
        buffer.putShort(4, DisplayModeLayout.VERSION.toShort())
        buffer.put(DisplayModeLayout.BASE_LAYER, baseLayer.toByte())
        buffer.put(DisplayModeLayout.OVERLAY_BITS, overlayBits.toByte())
        buffer.put(DisplayModeLayout.COLOUR_ATTRIBUTE, colourAttribute.toByte())
        buffer.put(DisplayModeLayout.SIZE_RULE, sizeRule.toByte())
        buffer.putShort(DisplayModeLayout.SPIKE_NEAREST_K, spikeNearestK.toShort())
        buffer.putFloat(DisplayModeLayout.POINT_SIZE, pointSizePx)
        buffer.putInt(DisplayModeLayout.COLOUR_TABLE_GENERATION, generation)
        buffer.put(DisplayModeLayout.MIN_QUALITY, minQuality.toByte())
        length = DisplayModeLayout.BYTES
        return length
    }

    /** Writes a colour table message filling every entry with [rgba] (0xRRGGBBAA); returns its length. */
    fun colourTable(attribute: Int, rgba: Int, generation: Int = 1): Int {
        buffer.putInt(0, ColourTableLayout.MAGIC)
        buffer.putShort(4, ColourTableLayout.VERSION.toShort())
        buffer.put(ColourTableLayout.ATTRIBUTE, attribute.toByte())
        buffer.put(7, 0)
        buffer.putInt(ColourTableLayout.GENERATION, generation)
        for (i in 0 until ColourTableLayout.ENTRIES) {
            val o = ColourTableLayout.HEADER_BYTES + 4 * i
            buffer.put(o, (rgba ushr 24).toByte())
            buffer.put(o + 1, (rgba ushr 16).toByte())
            buffer.put(o + 2, (rgba ushr 8).toByte())
            buffer.put(o + 3, rgba.toByte())
        }
        length = ColourTableLayout.BYTES
        return length
    }

    companion object {
        /** IEEE binary16 bits, same rounding as the Dart contract codec. */
        fun halfBits(v: Float): Int {
            val bits = java.lang.Float.floatToRawIntBits(v)
            val sign = (bits ushr 16) and 0x8000
            val exp = ((bits ushr 23) and 0xff) - 127 + 15
            val mant = bits and 0x7fffff
            if (exp <= 0) return sign
            if (exp >= 31) return sign or 0x7c00
            val rounded = ((exp shl 10) or (mant ushr 13)) + ((mant ushr 12) and 1)
            return sign or minOf(rounded, 0x7c00)
        }
    }
}
