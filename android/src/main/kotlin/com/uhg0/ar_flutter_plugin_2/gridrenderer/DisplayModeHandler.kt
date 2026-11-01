package com.uhg0.ar_flutter_plugin_2.gridrenderer

import com.uhg0.ar_flutter_plugin_2.visibilitycontract.ColourTableLayout
import com.uhg0.ar_flutter_plugin_2.visibilitycontract.DisplayModeLayout
import com.uhg0.ar_flutter_plugin_2.visibilitycontract.contractMagic
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Codes of the display mode message, mirroring the Dart contract. */
internal object GridDisplayCodes {
    const val BASE_POINTS = 0
    const val BASE_SPLATS = 1
    const val BASE_VOXELS = 2
    const val BASE_NONE = 3

    const val OVERLAY_SPIKE_BALLS = 1
    const val OVERLAY_BRICKS = 2
    const val OVERLAY_REGIONS = 4
    const val OVERLAY_TARGETS = 8
    const val OVERLAY_MARKERS = 16

    /** Raw feature points of each frame (app-driven, points-only grid). */
    const val OVERLAY_RAW_FEATURE_POINTS = 32

    const val COLOUR_COVERAGE = 0
    const val COLOUR_CONFIDENCE = 1
    const val COLOUR_AGE = 2
    const val COLOUR_SOURCE = 3
    const val COLOUR_COMPLETION = 4
    const val COLOUR_DIRECTIONAL_COVERAGE = 5
    const val COLOUR_ATTRIBUTES = 6
    const val OVERLAY_HALF_TRANSPARENCY = 64
    const val OVERLAY_FOCUS_PRIORITY = 128

    const val SIZE_FIXED = 0
    const val SIZE_BY_COVERAGE = 1
    const val SIZE_BY_CONFIDENCE = 2
}

/**
 * Provisional planner target message on the display channel (magic `C3TG`):
 * u16 version at 4, u8 count at 6, then 16 targets x 8 f32. Requested as a
 * contract addition; mirrors `planner_target_message.dart`.
 */
internal object PlannerTargetMessageLayout {
    val MAGIC = contractMagic("C3TG")
    const val VERSION = 1
    const val HEADER_BYTES = 8
    const val MAX_TARGETS = 16
    const val FLOATS_PER_TARGET = 8
    const val BYTES = HEADER_BYTES + MAX_TARGETS * FLOATS_PER_TARGET * 4
    const val COUNT = 6
}

/** One display state; the render thread keeps its own copy. */
internal class GridDisplayState {
    var baseLayer = GridDisplayCodes.BASE_POINTS
    var overlayBits = 0
    var colourAttribute = GridDisplayCodes.COLOUR_COVERAGE
    var sizeRule = GridDisplayCodes.SIZE_BY_COVERAGE
    var spikeNearestK = 512
    var pointSizePx = 6f
    var colourTableGeneration = 0L

    /** Surfaces with a confidence byte below this (0-255) are not drawn; 0 draws all. */
    var minQuality = 0

    /** Increments with every accepted display mode message. */
    var modeVersion = 0L

    fun copyFrom(other: GridDisplayState) {
        baseLayer = other.baseLayer
        overlayBits = other.overlayBits
        colourAttribute = other.colourAttribute
        sizeRule = other.sizeRule
        spikeNearestK = other.spikeNearestK
        pointSizePx = other.pointSizePx
        colourTableGeneration = other.colourTableGeneration
        minQuality = other.minQuality
        modeVersion = other.modeVersion
    }

    fun has(overlay: Int): Boolean = overlayBits and overlay != 0
}

/** Result of one display channel message. */
internal enum class DisplayIntake { MODE, COLOUR_TABLE, TARGETS, OVERLAY, REJECTED }

/**
 * Parses display channel messages (display mode, colour tables, provisional
 * planner targets) on the channel thread and hands a consistent snapshot to
 * the render thread. A mode switch changes only this state; draw calls and
 * uniforms follow it, instance buffers are never touched.
 */
internal class DisplayModeHandler {
    private val lock = Any()
    private val pending = GridDisplayState()
    private val tables = ByteArray(GridDisplayCodes.COLOUR_ATTRIBUTES * ColourTableLayout.ENTRIES * 4)
    private var tablesVersion = 0L
    private val targets = FloatArray(PlannerTargetMessageLayout.MAX_TARGETS * PlannerTargetMessageLayout.FLOATS_PER_TARGET)
    private val focus = FloatArray(64 * 4)
    private var focusCount = 0
    private var focusVersion = 0L
    private var targetCount = 0
    private var targetsVersion = 0L
    var rejected = 0L
        private set

    init {
        // Neutral grey ramp until the first colour tables arrive.
        for (t in 0 until GridDisplayCodes.COLOUR_ATTRIBUTES) {
            for (i in 0 until ColourTableLayout.ENTRIES) {
                val o = (t * ColourTableLayout.ENTRIES + i) * 4
                tables[o] = i.toByte(); tables[o + 1] = i.toByte(); tables[o + 2] = i.toByte(); tables[o + 3] = -1
            }
        }
    }

    fun onMessage(bytes: ByteArray, length: Int = bytes.size): DisplayIntake =
        onMessage(ByteBuffer.wrap(bytes, 0, length).order(ByteOrder.LITTLE_ENDIAN), 0, length)

    fun onMessage(src: ByteBuffer, offset: Int, length: Int): DisplayIntake {
        val b = src.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        if (length < 8 || offset + length > b.limit()) return reject()
        val magic = b.getInt(offset)
        val version = b.getShort(offset + 4).toInt() and 0xffff
        return when (magic) {
            DisplayModeLayout.MAGIC -> {
                if (version != DisplayModeLayout.VERSION || length < DisplayModeLayout.BYTES) return reject()
                val base = b.get(offset + DisplayModeLayout.BASE_LAYER).toInt() and 0xff
                val colour = b.get(offset + DisplayModeLayout.COLOUR_ATTRIBUTE).toInt() and 0xff
                val size = b.get(offset + DisplayModeLayout.SIZE_RULE).toInt() and 0xff
                if (base > GridDisplayCodes.BASE_NONE || colour >= GridDisplayCodes.COLOUR_ATTRIBUTES ||
                    size > GridDisplayCodes.SIZE_BY_CONFIDENCE
                ) return reject()
                synchronized(lock) {
                    pending.baseLayer = base
                    pending.overlayBits = b.get(offset + DisplayModeLayout.OVERLAY_BITS).toInt() and 0xff
                    pending.colourAttribute = colour
                    pending.sizeRule = size
                    pending.spikeNearestK = b.getShort(offset + DisplayModeLayout.SPIKE_NEAREST_K).toInt() and 0xffff
                    val px = b.getFloat(offset + DisplayModeLayout.POINT_SIZE)
                    pending.pointSizePx = if (px.isFinite()) px.coerceIn(1f, 128f) else 6f
                    pending.colourTableGeneration =
                        b.getInt(offset + DisplayModeLayout.COLOUR_TABLE_GENERATION).toLong() and 0xffffffffL
                    pending.minQuality = b.get(offset + DisplayModeLayout.MIN_QUALITY).toInt() and 0xff
                    pending.modeVersion++
                }
                DisplayIntake.MODE
            }
            ColourTableLayout.MAGIC -> {
                if (version != ColourTableLayout.VERSION || length < ColourTableLayout.BYTES) return reject()
                val attribute = b.get(offset + ColourTableLayout.ATTRIBUTE).toInt() and 0xff
                if (attribute >= GridDisplayCodes.COLOUR_ATTRIBUTES) return reject()
                synchronized(lock) {
                    val dst = attribute * ColourTableLayout.ENTRIES * 4
                    for (i in 0 until ColourTableLayout.ENTRIES * 4) {
                        tables[dst + i] = b.get(offset + ColourTableLayout.HEADER_BYTES + i)
                    }
                    tablesVersion++
                }
                DisplayIntake.COLOUR_TABLE
            }
            contractMagic("C3FP") -> {
                val count = b.get(offset + 6).toInt() and 0xff
                if (version != 1 || count > 64 || length != 8 + count * 16) return reject()
                // Validate the whole update before changing the visible frame.
                for (i in 0 until count * 4) {
                    val value = b.getFloat(offset + 8 + i * 4)
                    if (!value.isFinite() || (i % 4 == 3 && value !in 0f..1f)) return reject()
                }
                synchronized(lock) {
                    focus.fill(0f)
                    for (i in 0 until count * 4) focus[i] = b.getFloat(offset + 8 + i * 4)
                    focusCount = count
                    focusVersion++
                }
                DisplayIntake.OVERLAY
            }
            PlannerTargetMessageLayout.MAGIC -> {
                if (version != PlannerTargetMessageLayout.VERSION || length < PlannerTargetMessageLayout.BYTES) return reject()
                val count = (b.get(offset + PlannerTargetMessageLayout.COUNT).toInt() and 0xff)
                    .coerceAtMost(PlannerTargetMessageLayout.MAX_TARGETS)
                synchronized(lock) {
                    for (i in targets.indices) {
                        targets[i] = b.getFloat(offset + PlannerTargetMessageLayout.HEADER_BYTES + 4 * i)
                    }
                    targetCount = count
                    targetsVersion++
                }
                DisplayIntake.TARGETS
            }
            else -> reject()
        }
    }

    private fun reject(): DisplayIntake {
        rejected++
        return DisplayIntake.REJECTED
    }

    /** Copies the current mode into [out]; returns whether it changed since [knownVersion]. */
    fun snapshotMode(out: GridDisplayState, knownVersion: Long): Boolean = synchronized(lock) {
        if (pending.modeVersion == knownVersion) return false
        out.copyFrom(pending)
        true
    }

    /** Copies the colour tables (6 x 256 RGBA8) into [out] when newer than [knownVersion]; returns the version or -1. */
    fun snapshotTables(out: ByteBuffer, knownVersion: Long): Long = synchronized(lock) {
        if (tablesVersion == knownVersion) return -1
        out.clear()
        out.put(tables)
        out.flip()
        tablesVersion
    }

    /** Copies planner targets into [out]; returns the count, or -1 when unchanged since [knownVersion]. */
    fun snapshotTargets(out: FloatArray, knownVersion: Long): Int = synchronized(lock) {
        if (targetsVersion == knownVersion) return -1
        System.arraycopy(targets, 0, out, 0, targets.size)
        targetCount
    }

    fun clearFocus() = synchronized(lock) {
        focus.fill(0f)
        focusCount = 0
        focusVersion++
    }

    fun snapshotFocus(out: FloatArray, knownVersion: Long): Int = synchronized(lock) {
        if (focusVersion == knownVersion) return -1
        System.arraycopy(focus, 0, out, 0, focus.size)
        focusCount
    }

    val currentFocusVersion: Long get() = synchronized(lock) { focusVersion }
    val currentTargetsVersion: Long get() = synchronized(lock) { targetsVersion }
    val currentTablesVersion: Long get() = synchronized(lock) { tablesVersion }

    /** Colour of [attribute] entry [index] as 0xRRGGBBAA (diagnostics and tests). */
    fun rgba(attribute: Int, index: Int): Int = synchronized(lock) {
        val o = (attribute * ColourTableLayout.ENTRIES + index) * 4
        ((tables[o].toInt() and 0xff) shl 24) or ((tables[o + 1].toInt() and 0xff) shl 16) or
            ((tables[o + 2].toInt() and 0xff) shl 8) or (tables[o + 3].toInt() and 0xff)
    }
}
