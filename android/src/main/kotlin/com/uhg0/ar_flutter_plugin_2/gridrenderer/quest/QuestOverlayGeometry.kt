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
 * Turns a [QuestOverlayFrame] into line segments, following the guided
 * capture design (visibility-grid page, "Donuts, reward and compass"):
 *
 * - A donut is a thin ring (three concentric loops forming a torus band) of
 *   the message's world radius (16 cm across by default) at the standpoint,
 *   facing the aim point. Its on-screen diameter is clamped to the
 *   message's dp range (24-96 dp by default) using the focal length and
 *   density from [setView], so a far donut stays a small readable circle
 *   and a near one never fills the view.
 * - Within twice the position tolerance the user is at the standpoint: the
 *   donut frames the aim point instead, around a dashed aim reticle whose
 *   radius is the inner circle as seen from the camera, so the screen cross
 *   inside the reticle means aimed.
 * - Shown donuts are amber, aimed green, the next trail start cyan;
 *   captured donuts flash white three times, timed-out donuts fade out.
 * - Trails are solid where captured and dashed elsewhere; footprints are
 *   two foot-sized soles on the floor at the standpoint.
 *
 * Uses only fields, precomputed tables and the caller's sink; nothing is
 * allocated per frame.
 */
internal class QuestOverlayGeometry {
    companion object {
        const val RING_SEGMENTS = 32
        const val FAR_SEGMENTS = 16

        /** Beyond this camera distance a donut is drawn with [FAR_SEGMENTS]. */
        const val FAR_METRES = 3f

        /** Defaults when a message leaves the ring fields zero. */
        const val DEFAULT_RING_RADIUS_METRES = 0.08f
        const val DEFAULT_RING_MIN_DIAMETER_DP = 24
        const val DEFAULT_RING_MAX_DIAMETER_DP = 96
        const val DEFAULT_INNER_CIRCLE_DEGREES = 4f

        /** Loop radii of the ring band as fractions of the outer radius. */
        val RING_LOOPS = floatArrayOf(0.8f, 0.9f, 1f)

        /** Near: the aim ring's outer radius in reticle radii. */
        const val AIM_RING_RETICLE_RATIO = 1.5f

        /** Trail parts still to capture: one dash per this length, at most [MAX_DASHES] per segment. */
        const val TRAIL_DASH_METRES = 0.1f
        const val MAX_DASHES = 8

        const val COLOUR_SHOWN = 0xFFB300FF.toInt()
        const val COLOUR_AIMED = 0x43A047FF
        const val COLOUR_FLASH = 0xFFFFFFFF.toInt()
        const val COLOUR_TIMED_OUT = 0xFFB300FF.toInt()
        const val COLOUR_TRAIL_START = 0x26C6DAFF
        const val COLOUR_TRAIL_PENDING = 0xFFFFFF99.toInt()
        const val COLOUR_TRAIL_ACTIVE = 0xFFFFFFFF.toInt()
        const val COLOUR_TRAIL_CAPTURED = 0x43A047FF
        const val COLOUR_FOOTPRINT = 0xFFB300CC.toInt()
        const val COLOUR_ZONE = 0x90A4AEFF.toInt()

        /** Half sole length and width; sole centres [FOOT_SPACING] either side of the standpoint. */
        const val FOOT_HALF_LENGTH = 0.13f
        const val FOOT_HALF_WIDTH = 0.05f
        const val FOOT_SPACING = 0.1f

        /** Sole outline as (forward, side) pairs in half-extent units, a closed loop. */
        val FOOT_OUTLINE = floatArrayOf(
            1f, 0f, 0.8f, 0.75f, 0.3f, 1f, -0.4f, 0.8f, -0.9f, 0.6f,
            -1f, 0f, -0.9f, -0.6f, -0.4f, -0.8f, 0.3f, -1f, 0.8f, -0.75f,
        )

        /** Alpha of a timed-out donut in fade phase 0-5. */
        fun fadeAlpha(phase: Int): Int = 255 * (6 - phase.coerceIn(0, 5)) / 7

        private val COS = FloatArray(RING_SEGMENTS + 1) { cos(2.0 * Math.PI * it / RING_SEGMENTS).toFloat() }
        private val SIN = FloatArray(RING_SEGMENTS + 1) { sin(2.0 * Math.PI * it / RING_SEGMENTS).toFloat() }
    }

    private val u = FloatArray(3)
    private val v = FloatArray(3)

    /** Projection focal length in pixels; 0 disables the screen clamp. */
    private var focalPx = 0f
    private var pxPerDp = 1f

    var lines = 0
        private set
    private var target: OverlayLineSink = OverlayLineSink { _, _, _, _, _, _, _ -> }
    private val counting = OverlayLineSink { x0, y0, z0, x1, y1, z1, rgba ->
        lines++
        target.line(x0, y0, z0, x1, y1, z1, rgba)
    }

    /** Render thread: this frame's projection focal length in pixels and the display density. */
    fun setView(focalPx: Float, pxPerDp: Float) {
        this.focalPx = focalPx
        this.pxPerDp = pxPerDp
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

    /** Colour of donut [i] this frame, or 0 when it is not drawn. */
    fun donutColour(f: QuestOverlayFrame, i: Int): Int {
        val flash = f.donutFlash[i]
        return when (f.donutState[i]) {
            // Three flashes: even phases lit, odd phases dark, then gone.
            QuestOverlayLayout.STATE_CAPTURED ->
                if (flash == QuestOverlayLayout.FLASH_NONE || flash % 2 == 1) 0 else COLOUR_FLASH
            QuestOverlayLayout.STATE_TIMED_OUT ->
                if (flash == QuestOverlayLayout.FLASH_NONE) {
                    0
                } else {
                    (COLOUR_TIMED_OUT and 0xFFFFFF00.toInt()) or fadeAlpha(flash)
                }
            else -> when {
                (f.donutFlags[i] and QuestOverlayLayout.DONUT_FLAG_TRAIL_START) != 0 -> COLOUR_TRAIL_START
                (f.donutFlags[i] and QuestOverlayLayout.DONUT_FLAG_AIMED) != 0 -> COLOUR_AIMED
                else -> COLOUR_SHOWN
            }
        }
    }

    /**
     * World radius at [distance] from the camera whose projection is
     * [radius]'s, clamped to [[minPx], [maxPx]] pixels; unclamped until
     * [setView] gave a focal length.
     */
    fun clampRadius(radius: Float, distance: Float, minPx: Float, maxPx: Float): Float {
        if (focalPx <= 0f || distance <= 1e-4f) return radius
        val px = (radius * focalPx / distance).coerceIn(minPx, maxPx)
        return px * distance / focalPx
    }

    private fun minRingPx(f: QuestOverlayFrame): Float =
        0.5f * pxPerDp * (if (f.ringMinDiameterDp > 0) f.ringMinDiameterDp else DEFAULT_RING_MIN_DIAMETER_DP)

    private fun maxRingPx(f: QuestOverlayFrame): Float =
        0.5f * pxPerDp * (if (f.ringMaxDiameterDp > 0) f.ringMaxDiameterDp else DEFAULT_RING_MAX_DIAMETER_DP)

    private fun donut(f: QuestOverlayFrame, i: Int, camX: Float, camY: Float, camZ: Float, sink: OverlayLineSink) {
        val colour = donutColour(f, i)
        if (colour == 0) return
        val sx = f.donutStandpoint[3 * i]
        val sy = f.donutStandpoint[3 * i + 1]
        val sz = f.donutStandpoint[3 * i + 2]
        val ax = f.donutAim[3 * i]
        val ay = f.donutAim[3 * i + 1]
        val az = f.donutAim[3 * i + 2]
        if (!basis(ax - sx, ay - sy, az - sz)) return
        val camDistance = distance(camX, camY, camZ, sx, sy, sz)
        if (camDistance <= 2f * f.donutPositionTolerance[i]) {
            // At the standpoint: the donut frames the aim point around the reticle.
            val aimDistance = distance(camX, camY, camZ, ax, ay, az)
            if (aimDistance < 1e-3f) return
            val degrees = f.donutInnerCircleDegrees[i].let { if (it > 0f) it else DEFAULT_INNER_CIRCLE_DEGREES }
            val reticle = aimDistance * tan(Math.toRadians(degrees.toDouble())).toFloat()
            val outer = clampRadius(reticle * AIM_RING_RETICLE_RATIO, aimDistance, minRingPx(f), Float.MAX_VALUE)
            ring(ax, ay, az, outer, RING_SEGMENTS, colour, sink)
            val state = f.donutState[i]
            if (state != QuestOverlayLayout.STATE_CAPTURED && state != QuestOverlayLayout.STATE_TIMED_OUT) {
                dashedCircle(ax, ay, az, reticle, colour, sink)
            }
            return
        }
        val radius = f.donutRingRadius[i].let { if (it > 0f) it else DEFAULT_RING_RADIUS_METRES }
        val outer = clampRadius(radius, camDistance, minRingPx(f), maxRingPx(f))
        ring(sx, sy, sz, outer, if (camDistance > FAR_METRES) FAR_SEGMENTS else RING_SEGMENTS, colour, sink)
    }

    private fun ring(cx: Float, cy: Float, cz: Float, outer: Float, segments: Int, colour: Int, sink: OverlayLineSink) {
        for (loop in RING_LOOPS) circle(cx, cy, cz, outer * loop, segments, colour, sink)
    }

    /** Aim reticle: a circle of [RING_SEGMENTS] / 2 dashes. */
    private fun dashedCircle(cx: Float, cy: Float, cz: Float, r: Float, colour: Int, sink: OverlayLineSink) {
        var k = 0
        while (k < RING_SEGMENTS) {
            sink.line(
                cx + (u[0] * COS[k] + v[0] * SIN[k]) * r,
                cy + (u[1] * COS[k] + v[1] * SIN[k]) * r,
                cz + (u[2] * COS[k] + v[2] * SIN[k]) * r,
                cx + (u[0] * COS[k + 1] + v[0] * SIN[k + 1]) * r,
                cy + (u[1] * COS[k + 1] + v[1] * SIN[k + 1]) * r,
                cz + (u[2] * COS[k + 1] + v[2] * SIN[k + 1]) * r,
                colour,
            )
            k += 2
        }
    }

    private fun trails(f: QuestOverlayFrame, sink: OverlayLineSink) {
        for (i in 0 until f.trailVertexCount - 1) {
            if (f.trailIndex[i] != f.trailIndex[i + 1]) continue
            val flags = f.trailFlags[i]
            val captured = f.trailStationState[i] == QuestOverlayLayout.STATION_CAPTURED &&
                f.trailStationState[i + 1] == QuestOverlayLayout.STATION_CAPTURED
            val solid = captured || (flags and QuestOverlayLayout.TRAIL_FLAG_COMPLETED) != 0
            val colour = when {
                solid -> COLOUR_TRAIL_CAPTURED
                (flags and QuestOverlayLayout.TRAIL_FLAG_ACTIVE) != 0 -> COLOUR_TRAIL_ACTIVE
                else -> COLOUR_TRAIL_PENDING
            }
            val x0 = f.trailPoint[3 * i]
            val y0 = f.trailPoint[3 * i + 1]
            val z0 = f.trailPoint[3 * i + 2]
            val dx = f.trailPoint[3 * i + 3] - x0
            val dy = f.trailPoint[3 * i + 4] - y0
            val dz = f.trailPoint[3 * i + 5] - z0
            if (solid) {
                sink.line(x0, y0, z0, x0 + dx, y0 + dy, z0 + dz, colour)
                continue
            }
            // Still to capture: dashed, the first half of each dash period drawn.
            val length = sqrt(dx * dx + dy * dy + dz * dz)
            val dashes = (length / TRAIL_DASH_METRES).toInt().coerceIn(1, MAX_DASHES)
            for (k in 0 until dashes) {
                val t0 = k.toFloat() / dashes
                val t1 = (k + 0.5f) / dashes
                sink.line(x0 + dx * t0, y0 + dy * t0, z0 + dz * t0, x0 + dx * t1, y0 + dy * t1, z0 + dz * t1, colour)
            }
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
            sole(x + rx * s, y, z + rz * s, fx, fz, rx, rz, sink)
        }
    }

    /** One sole outline centred at ([cx], [cz]) facing ([fx], [fz]) with side axis ([rx], [rz]). */
    private fun sole(cx: Float, y: Float, cz: Float, fx: Float, fz: Float, rx: Float, rz: Float, sink: OverlayLineSink) {
        val n = FOOT_OUTLINE.size / 2
        for (k in 0 until n) {
            val j = (k + 1) % n
            val a = FOOT_OUTLINE[2 * k] * FOOT_HALF_LENGTH
            val b = FOOT_OUTLINE[2 * k + 1] * FOOT_HALF_WIDTH
            val c = FOOT_OUTLINE[2 * j] * FOOT_HALF_LENGTH
            val d = FOOT_OUTLINE[2 * j + 1] * FOOT_HALF_WIDTH
            sink.line(
                cx + fx * a + rx * b, y, cz + fz * a + rz * b,
                cx + fx * c + rx * d, y, cz + fz * c + rz * d,
                COLOUR_FOOTPRINT,
            )
        }
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
        val hz = 0f
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
        val step = RING_SEGMENTS / segments
        var px = cx + u[0] * r
        var py = cy + u[1] * r
        var pz = cz + u[2] * r
        var k = step
        while (k <= RING_SEGMENTS) {
            val c = COS[k] * r
            val s = SIN[k] * r
            val qx = cx + u[0] * c + v[0] * s
            val qy = cy + u[1] * c + v[1] * s
            val qz = cz + u[2] * c + v[2] * s
            sink.line(px, py, pz, qx, qy, qz, colour)
            px = qx
            py = qy
            pz = qz
            k += step
        }
    }

    private fun distance(ax: Float, ay: Float, az: Float, bx: Float, by: Float, bz: Float): Float {
        val dx = ax - bx
        val dy = ay - by
        val dz = az - bz
        return sqrt(dx * dx + dy * dy + dz * dz)
    }
}
