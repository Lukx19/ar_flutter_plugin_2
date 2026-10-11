package com.uhg0.ar_flutter_plugin_2.gridrenderer.quest

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/** Receives overlay line segments in world coordinates with an RGBA8 colour. */
internal fun interface OverlayLineSink {
    fun line(x0: Float, y0: Float, z0: Float, x1: Float, y1: Float, z1: Float, rgba: Int)
}

/**
 * Turns a [QuestOverlayFrame] into line segments: donut rings at the
 * standpoints facing their aim points (a small circle far away, a ring with
 * an aiming reticle up close), flashes of captured donuts, trail polylines
 * (solid where captured), footprints and the scan zone outline. Uses only
 * fields and the caller's sink; nothing is allocated per frame.
 */
internal class QuestOverlayGeometry {
    companion object {
        const val RING_SEGMENTS = 32
        const val FAR_SEGMENTS = 16

        /** Beyond this camera distance a donut is drawn as a small circle. */
        const val FAR_METRES = 3f

        const val COLOUR_SHOWN = 0xFFB300FF.toInt()
        const val COLOUR_AIMED = 0x43A047FF
        const val COLOUR_FLASH = 0xFFFFFFFF.toInt()
        const val COLOUR_TRAIL_START = 0x26C6DAFF
        const val COLOUR_TRAIL_PENDING = 0xFFFFFF99.toInt()
        const val COLOUR_TRAIL_ACTIVE = 0xFFFFFFFF.toInt()
        const val COLOUR_TRAIL_CAPTURED = 0x43A047FF
        const val COLOUR_FOOTPRINT = 0xFFB300CC.toInt()
        const val COLOUR_ZONE = 0x90A4AEFF.toInt()

        const val FOOT_HALF_WIDTH = 0.05f
        const val FOOT_HALF_LENGTH = 0.13f
        const val FOOT_SPACING = 0.12f
    }

    private val u = FloatArray(3)
    private val v = FloatArray(3)
    var lines = 0
        private set
    private var target: OverlayLineSink = OverlayLineSink { _, _, _, _, _, _, _ -> }
    private val counting = OverlayLineSink { x0, y0, z0, x1, y1, z1, rgba ->
        lines++
        target.line(x0, y0, z0, x1, y1, z1, rgba)
    }

    /** Emits every visible element of [frame]; returns the segment count. */
    fun build(frame: QuestOverlayFrame, camX: Float, camY: Float, camZ: Float, sink: OverlayLineSink): Int {
        lines = 0
        target = sink
        if (frame.hasLayer(QuestOverlayLayout.LAYER_DONUTS) ||
            frame.hasLayer(QuestOverlayLayout.LAYER_TRAILS)
        ) {
            for (i in 0 until frame.donutCount) donut(frame, i, camX, camY, camZ, counting)
        }
        if (frame.hasLayer(QuestOverlayLayout.LAYER_TRAILS)) trails(frame, counting)
        if (frame.hasLayer(QuestOverlayLayout.LAYER_FOOTPRINTS)) {
            for (i in 0 until frame.footprintCount) footprint(frame, i, counting)
        }
        if (frame.hasLayer(QuestOverlayLayout.LAYER_ZONE_OUTLINE)) zone(frame, counting)
        return lines
    }

    private fun donut(f: QuestOverlayFrame, i: Int, camX: Float, camY: Float, camZ: Float, sink: OverlayLineSink) {
        val flash = f.donutFlash[i]
        // Captured donuts flash three times: even phases lit, odd phases dark.
        if (flash != QuestOverlayLayout.FLASH_NONE && flash % 2 == 1) return
        if (flash == QuestOverlayLayout.FLASH_NONE && f.donutState[i] == QuestOverlayLayout.STATE_CAPTURED) return
        val sx = f.donutStandpoint[3 * i]
        val sy = f.donutStandpoint[3 * i + 1]
        val sz = f.donutStandpoint[3 * i + 2]
        val ax = f.donutAim[3 * i]
        val ay = f.donutAim[3 * i + 1]
        val az = f.donutAim[3 * i + 2]
        if (!basis(ax - sx, ay - sy, az - sz)) return
        val colour = when {
            flash != QuestOverlayLayout.FLASH_NONE -> COLOUR_FLASH
            (f.donutFlags[i] and QuestOverlayLayout.DONUT_FLAG_TRAIL_START) != 0 -> COLOUR_TRAIL_START
            (f.donutFlags[i] and QuestOverlayLayout.DONUT_FLAG_AIMED) != 0 -> COLOUR_AIMED
            else -> COLOUR_SHOWN
        }
        val radius = f.donutPositionTolerance[i]
        val camDistance = distance(camX, camY, camZ, sx, sy, sz)
        if (camDistance > FAR_METRES) {
            circle(sx, sy, sz, radius * 0.6f, FAR_SEGMENTS, colour, sink)
            return
        }
        // Donut: outer and inner ring around the standpoint.
        circle(sx, sy, sz, radius, RING_SEGMENTS, colour, sink)
        circle(sx, sy, sz, radius * 0.7f, RING_SEGMENTS, colour, sink)
        // Up close: aiming reticle at the aim point, sized to the inner circle.
        if (camDistance <= 2f * radius) {
            val d = distance(sx, sy, sz, ax, ay, az)
            val r = d * tan(Math.toRadians(f.donutInnerCircleDegrees[i].toDouble())).toFloat()
            circle(ax, ay, az, r, RING_SEGMENTS, colour, sink)
            val c = 2f * r
            sink.line(ax - u[0] * c, ay - u[1] * c, az - u[2] * c, ax + u[0] * c, ay + u[1] * c, az + u[2] * c, colour)
            sink.line(ax - v[0] * c, ay - v[1] * c, az - v[2] * c, ax + v[0] * c, ay + v[1] * c, az + v[2] * c, colour)
        }
    }

    private fun trails(f: QuestOverlayFrame, sink: OverlayLineSink) {
        for (i in 0 until f.trailVertexCount - 1) {
            if (f.trailIndex[i] != f.trailIndex[i + 1]) continue
            val flags = f.trailFlags[i]
            val captured = f.trailStationState[i] == QuestOverlayLayout.STATION_CAPTURED &&
                f.trailStationState[i + 1] == QuestOverlayLayout.STATION_CAPTURED
            val colour = when {
                captured || (flags and QuestOverlayLayout.TRAIL_FLAG_COMPLETED) != 0 -> COLOUR_TRAIL_CAPTURED
                (flags and QuestOverlayLayout.TRAIL_FLAG_ACTIVE) != 0 -> COLOUR_TRAIL_ACTIVE
                else -> COLOUR_TRAIL_PENDING
            }
            sink.line(
                f.trailPoint[3 * i], f.trailPoint[3 * i + 1], f.trailPoint[3 * i + 2],
                f.trailPoint[3 * i + 3], f.trailPoint[3 * i + 4], f.trailPoint[3 * i + 5],
                colour,
            )
        }
    }

    private fun footprint(f: QuestOverlayFrame, i: Int, sink: OverlayLineSink) {
        val x = f.footprint[3 * i]
        val y = f.footprint[3 * i + 1]
        val z = f.footprint[3 * i + 2]
        val yaw = f.footprintYaw[i]
        val fx = cos(yaw)
        val fz = sin(yaw)
        // Side axis, perpendicular to the facing direction on the floor.
        val rx = -fz
        val rz = fx
        for (side in 0 until 2) {
            val s = if (side == 0) -FOOT_SPACING else FOOT_SPACING
            val cx = x + rx * s
            val cz = z + rz * s
            rect(cx, y, cz, fx, fz, rx, rz, sink)
        }
    }

    private fun rect(cx: Float, y: Float, cz: Float, fx: Float, fz: Float, rx: Float, rz: Float, sink: OverlayLineSink) {
        val ax = fx * FOOT_HALF_LENGTH
        val az = fz * FOOT_HALF_LENGTH
        val bx = rx * FOOT_HALF_WIDTH
        val bz = rz * FOOT_HALF_WIDTH
        val x0 = cx - ax - bx
        val z0 = cz - az - bz
        val x1 = cx + ax - bx
        val z1 = cz + az - bz
        val x2 = cx + ax + bx
        val z2 = cz + az + bz
        val x3 = cx - ax + bx
        val z3 = cz - az + bz
        sink.line(x0, y, z0, x1, y, z1, COLOUR_FOOTPRINT)
        sink.line(x1, y, z1, x2, y, z2, COLOUR_FOOTPRINT)
        sink.line(x2, y, z2, x3, y, z3, COLOUR_FOOTPRINT)
        sink.line(x3, y, z3, x0, y, z0, COLOUR_FOOTPRINT)
    }

    private fun zone(f: QuestOverlayFrame, sink: OverlayLineSink) {
        when (f.zoneKind) {
            QuestOverlayLayout.ZONE_SPACE -> {
                val y = f.zoneParams[0]
                val n = f.zoneVertexCount
                for (i in 0 until n) {
                    val j = (i + 1) % n
                    sink.line(
                        f.zoneVertexXz[2 * i], y, f.zoneVertexXz[2 * i + 1],
                        f.zoneVertexXz[2 * j], y, f.zoneVertexXz[2 * j + 1],
                        COLOUR_ZONE,
                    )
                }
            }
            QuestOverlayLayout.ZONE_OBJECT -> {
                val p = f.zoneParams
                for (e in 0 until 12) {
                    // Box edges: 4 along each axis.
                    val axis = e / 4
                    val s1 = if (e % 2 == 0) -1f else 1f
                    val s2 = if ((e / 2) % 2 == 0) -1f else 1f
                    val a1 = (axis + 1) % 3
                    val a2 = (axis + 2) % 3
                    u[axis] = p[axis] - p[3 + axis]
                    v[axis] = p[axis] + p[3 + axis]
                    u[a1] = p[a1] + s1 * p[3 + a1]
                    v[a1] = u[a1]
                    u[a2] = p[a2] + s2 * p[3 + a2]
                    v[a2] = u[a2]
                    sink.line(u[0], u[1], u[2], v[0], v[1], v[2], COLOUR_ZONE)
                }
            }
        }
    }

    /** Orthonormal u, v perpendicular to the direction (dx, dy, dz). */
    private fun basis(dx: Float, dy: Float, dz: Float): Boolean {
        val l = sqrt(dx * dx + dy * dy + dz * dz)
        if (l < 1e-6f) return false
        val nx = dx / l
        val ny = dy / l
        val nz = dz / l
        // Pick the world axis least parallel to n.
        var hx = 0f
        var hy = 1f
        var hz = 0f
        if (abs(ny) > 0.9f) {
            hx = 1f
            hy = 0f
        }
        // u = normalize(h x n), v = n x u.
        var ux = hy * nz - hz * ny
        var uy = hz * nx - hx * nz
        var uz = hx * ny - hy * nx
        val ul = sqrt(ux * ux + uy * uy + uz * uz)
        ux /= ul
        uy /= ul
        uz /= ul
        u[0] = ux
        u[1] = uy
        u[2] = uz
        v[0] = ny * uz - nz * uy
        v[1] = nz * ux - nx * uz
        v[2] = nx * uy - ny * ux
        return true
    }

    private fun circle(cx: Float, cy: Float, cz: Float, r: Float, segments: Int, colour: Int, sink: OverlayLineSink) {
        var px = cx + u[0] * r
        var py = cy + u[1] * r
        var pz = cz + u[2] * r
        for (k in 1..segments) {
            val a = (2.0 * Math.PI * k / segments)
            val c = cos(a).toFloat() * r
            val s = sin(a).toFloat() * r
            val qx = cx + u[0] * c + v[0] * s
            val qy = cy + u[1] * c + v[1] * s
            val qz = cz + u[2] * c + v[2] * s
            sink.line(px, py, pz, qx, qy, qz, colour)
            px = qx
            py = qy
            pz = qz
        }
    }

    private fun distance(ax: Float, ay: Float, az: Float, bx: Float, by: Float, bz: Float): Float {
        val dx = ax - bx
        val dy = ay - by
        val dz = az - bz
        return sqrt(dx * dx + dy * dy + dz * dz)
    }
}
