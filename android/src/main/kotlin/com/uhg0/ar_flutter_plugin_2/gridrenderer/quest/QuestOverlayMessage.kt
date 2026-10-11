package com.uhg0.ar_flutter_plugin_2.gridrenderer.quest

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Layout of the quest overlay message written by the Dart guided capture
 * controller (`QuestOverlayLayout` in
 * `capture3d/lib/capture/guided_capture/quest_overlay_message.dart`).
 * Little-endian, at most 64 KiB; header 48 B, then donuts (48 B), trail
 * vertices (20 B), footprints (16 B) and zone vertices (8 B).
 */
internal object QuestOverlayLayout {
    /** `C3QO` read as a little-endian u32. */
    const val MAGIC = 0x4F513343
    const val VERSION = 1
    const val HEADER_BYTES = 48
    const val DONUT_BYTES = 48
    const val TRAIL_VERTEX_BYTES = 20
    const val FOOTPRINT_BYTES = 16
    const val ZONE_VERTEX_BYTES = 8
    const val MAX_MESSAGE_BYTES = 65536

    const val LAYER_DONUTS = 1
    const val LAYER_TRAILS = 2
    const val LAYER_FOOTPRINTS = 4
    const val LAYER_ZONE_OUTLINE = 8
    const val LAYER_GREY_OUTSIDE = 16

    const val ZONE_NONE = 0
    const val ZONE_SPACE = 1
    const val ZONE_OBJECT = 2

    const val FLASH_NONE = 255
    const val DONUT_FLAG_AIMED = 1
    const val DONUT_FLAG_TRAIL_START = 2
    const val TRAIL_FLAG_ACTIVE = 1
    const val TRAIL_FLAG_COMPLETED = 2

    const val STATE_CAPTURED = 2
    const val STATE_TIMED_OUT = 3
    const val STATION_CAPTURED = 2

    const val MAX_DONUTS = 72
    const val MAX_TRAIL_VERTICES = 2048
    const val MAX_FOOTPRINTS = 72
    const val MAX_ZONE_VERTICES = 256
}

/** Why a message was rejected. */
internal enum class QuestOverlayStatus { OK, TOO_SHORT, BAD_MAGIC, BAD_VERSION, BAD_LENGTH }

/**
 * Decoded quest overlay in preallocated columns. [decode] copies a message
 * into the columns without allocating; the renderer reads them on its
 * thread after the hand-over.
 */
internal class QuestOverlayFrame {
    var sequence = 0L
    var layers = 0
    var donutCount = 0
    var trailVertexCount = 0
    var footprintCount = 0
    var zoneVertexCount = 0
    var zoneKind = QuestOverlayLayout.ZONE_NONE

    /** On-screen diameter range of donut rings in dp; 0 selects the platform default. */
    var ringMinDiameterDp = 0
    var ringMaxDiameterDp = 0

    /** Space: floor y, ceiling y; object: centre xyz, half extents xyz. */
    val zoneParams = FloatArray(6)

    val donutQuestId = IntArray(QuestOverlayLayout.MAX_DONUTS)
    val donutState = IntArray(QuestOverlayLayout.MAX_DONUTS)
    val donutKind = IntArray(QuestOverlayLayout.MAX_DONUTS)
    val donutFlash = IntArray(QuestOverlayLayout.MAX_DONUTS)
    val donutFlags = IntArray(QuestOverlayLayout.MAX_DONUTS)
    val donutStandpoint = FloatArray(QuestOverlayLayout.MAX_DONUTS * 3)
    val donutAim = FloatArray(QuestOverlayLayout.MAX_DONUTS * 3)
    val donutPositionTolerance = FloatArray(QuestOverlayLayout.MAX_DONUTS)
    val donutInnerCircleDegrees = FloatArray(QuestOverlayLayout.MAX_DONUTS)
    val donutAngleToleranceDegrees = FloatArray(QuestOverlayLayout.MAX_DONUTS)

    /** Ring outer radius in metres; 0 selects the platform default. */
    val donutRingRadius = FloatArray(QuestOverlayLayout.MAX_DONUTS)

    val trailPoint = FloatArray(QuestOverlayLayout.MAX_TRAIL_VERTICES * 3)
    val trailIndex = IntArray(QuestOverlayLayout.MAX_TRAIL_VERTICES)
    val trailStationState = IntArray(QuestOverlayLayout.MAX_TRAIL_VERTICES)
    val trailFlags = IntArray(QuestOverlayLayout.MAX_TRAIL_VERTICES)

    val footprint = FloatArray(QuestOverlayLayout.MAX_FOOTPRINTS * 3)
    val footprintYaw = FloatArray(QuestOverlayLayout.MAX_FOOTPRINTS)

    val zoneVertexXz = FloatArray(QuestOverlayLayout.MAX_ZONE_VERTICES * 2)

    fun hasLayer(bit: Int): Boolean = (layers and bit) != 0

    /** Decodes `buffer[0, length)`; on failure the previous frame stays. */
    fun decode(buffer: ByteBuffer, length: Int): QuestOverlayStatus {
        val b = buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        if (length < QuestOverlayLayout.HEADER_BYTES || length > b.capacity()) {
            return QuestOverlayStatus.TOO_SHORT
        }
        if (b.getInt(0) != QuestOverlayLayout.MAGIC) return QuestOverlayStatus.BAD_MAGIC
        if (b.getShort(4).toInt() and 0xffff != QuestOverlayLayout.VERSION) {
            return QuestOverlayStatus.BAD_VERSION
        }
        val donuts = b.getShort(12).toInt() and 0xffff
        val vertices = b.getShort(14).toInt() and 0xffff
        val footprints = b.getShort(16).toInt() and 0xffff
        val zoneVertices = b.getShort(18).toInt() and 0xffff
        val expected = QuestOverlayLayout.HEADER_BYTES +
            donuts * QuestOverlayLayout.DONUT_BYTES +
            vertices * QuestOverlayLayout.TRAIL_VERTEX_BYTES +
            footprints * QuestOverlayLayout.FOOTPRINT_BYTES +
            zoneVertices * QuestOverlayLayout.ZONE_VERTEX_BYTES
        if (expected != length ||
            donuts > QuestOverlayLayout.MAX_DONUTS ||
            vertices > QuestOverlayLayout.MAX_TRAIL_VERTICES ||
            footprints > QuestOverlayLayout.MAX_FOOTPRINTS ||
            zoneVertices > QuestOverlayLayout.MAX_ZONE_VERTICES
        ) {
            return QuestOverlayStatus.BAD_LENGTH
        }
        layers = b.getShort(6).toInt() and 0xffff
        sequence = b.getInt(8).toLong() and 0xffffffffL
        zoneKind = b.get(20).toInt() and 0xff
        ringMinDiameterDp = b.get(21).toInt() and 0xff
        ringMaxDiameterDp = b.get(22).toInt() and 0xff
        for (i in 0 until 6) zoneParams[i] = b.getFloat(24 + 4 * i)
        var o = QuestOverlayLayout.HEADER_BYTES
        for (i in 0 until donuts) {
            donutQuestId[i] = b.getInt(o)
            donutState[i] = b.get(o + 4).toInt() and 0xff
            donutKind[i] = b.get(o + 5).toInt() and 0xff
            donutFlash[i] = b.get(o + 6).toInt() and 0xff
            donutFlags[i] = b.get(o + 7).toInt() and 0xff
            for (a in 0 until 3) {
                donutStandpoint[3 * i + a] = b.getFloat(o + 8 + 4 * a)
                donutAim[3 * i + a] = b.getFloat(o + 20 + 4 * a)
            }
            donutPositionTolerance[i] = b.getFloat(o + 32)
            donutInnerCircleDegrees[i] = b.getFloat(o + 36)
            donutAngleToleranceDegrees[i] = b.getFloat(o + 40)
            donutRingRadius[i] = b.getFloat(o + 44)
            o += QuestOverlayLayout.DONUT_BYTES
        }
        for (i in 0 until vertices) {
            for (a in 0 until 3) trailPoint[3 * i + a] = b.getFloat(o + 4 * a)
            trailIndex[i] = b.getShort(o + 12).toInt() and 0xffff
            trailStationState[i] = b.get(o + 14).toInt() and 0xff
            trailFlags[i] = b.get(o + 15).toInt() and 0xff
            o += QuestOverlayLayout.TRAIL_VERTEX_BYTES
        }
        for (i in 0 until footprints) {
            for (a in 0 until 3) footprint[3 * i + a] = b.getFloat(o + 4 * a)
            footprintYaw[i] = b.getFloat(o + 12)
            o += QuestOverlayLayout.FOOTPRINT_BYTES
        }
        for (i in 0 until zoneVertices) {
            zoneVertexXz[2 * i] = b.getFloat(o)
            zoneVertexXz[2 * i + 1] = b.getFloat(o + 4)
            o += QuestOverlayLayout.ZONE_VERTEX_BYTES
        }
        donutCount = donuts
        trailVertexCount = vertices
        footprintCount = footprints
        zoneVertexCount = zoneVertices
        return QuestOverlayStatus.OK
    }

    /**
     * Whether a point is inside the scan zone (or no zone is set); the
     * renderer greys surfaces outside when [QuestOverlayLayout.LAYER_GREY_OUTSIDE]
     * is set.
     */
    fun isInScope(x: Float, y: Float, z: Float): Boolean {
        when (zoneKind) {
            QuestOverlayLayout.ZONE_SPACE -> {
                if (y < zoneParams[0] || y > zoneParams[1]) return false
                var inside = false
                val n = zoneVertexCount
                var j = n - 1
                for (i in 0 until n) {
                    val xi = zoneVertexXz[2 * i]
                    val zi = zoneVertexXz[2 * i + 1]
                    val xj = zoneVertexXz[2 * j]
                    val zj = zoneVertexXz[2 * j + 1]
                    if ((zi > z) != (zj > z) && x < (xj - xi) * (z - zi) / (zj - zi) + xi) {
                        inside = !inside
                    }
                    j = i
                }
                return inside
            }
            QuestOverlayLayout.ZONE_OBJECT -> {
                return Math.abs(x - zoneParams[0]) <= zoneParams[3] &&
                    Math.abs(y - zoneParams[1]) <= zoneParams[4] &&
                    Math.abs(z - zoneParams[2]) <= zoneParams[5]
            }
            else -> return true
        }
    }
}
