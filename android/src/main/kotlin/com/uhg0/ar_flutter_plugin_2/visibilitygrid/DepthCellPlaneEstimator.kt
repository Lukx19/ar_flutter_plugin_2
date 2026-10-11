package com.uhg0.ar_flutter_plugin_2.visibilitygrid

import kotlin.math.abs
import kotlin.math.sqrt

/** Borrowed primitive scratch, available only before relation planning. */
internal interface DepthCellPlaneScratch {
    fun cellWord(index: Int): Int
    fun cellWord(index: Int, value: Int)
    fun cellMoment(cell: Int, component: Int): Double
    fun cellMoment(cell: Int, component: Int, value: Double)
}

/** Fieldless, bounded symmetric covariance solver; owns no matrices or population. */
internal object DepthCellPlaneEstimator {
    const val WORDS = 32
    const val STATUS = 2
    const val COUNT = 3
    const val MIN_DEPTH = 4
    const val MAX_DEPTH = 5
    const val MIN_X = 6
    const val MAX_X = 7
    const val MIN_Y = 8
    const val MAX_Y = 9
    const val CENTRE_SAMPLE = 10
    const val NEAREST_DISTANCE = 12
    const val MAXIMUM_RESIDUAL = 14
    const val MEAN_X = 16
    const val MEAN_Y = 18
    const val MEAN_Z = 20
    const val NORMAL_X = 22
    const val NORMAL_Y = 24
    const val NORMAL_Z = 26
    const val ACCUMULATING = 0
    const val REJECTED = 1
    const val SOLVED = 2
    const val QUALIFIED = 3
    const val ANGULAR_COSINE = 0.984807753012208
    const val ANGULAR_SINE = 0.17364817766693033
    const val MAXIMUM_SWEEPS = 12

    fun double(scratch: DepthCellPlaneScratch, index: Int): Double = java.lang.Double.longBitsToDouble(
        (scratch.cellWord(index).toLong() and 0xffffffffL) or (scratch.cellWord(index + 1).toLong() shl 32))
    fun double(scratch: DepthCellPlaneScratch, index: Int, value: Double) {
        val bits = java.lang.Double.doubleToRawLongBits(value)
        scratch.cellWord(index, bits.toInt()); scratch.cellWord(index + 1, (bits ushr 32).toInt())
    }

    /** Stable full-population centred moments, including each duplicate's point weight. */
    fun accumulate(scratch: DepthCellPlaneScratch, cell: Int, base: Int, x: Double, y: Double, z: Double) {
        val count = scratch.cellWord(base + COUNT) + 1
        val mx = scratch.cellMoment(cell, 0); val my = scratch.cellMoment(cell, 1); val mz = scratch.cellMoment(cell, 2)
        val dx = x - mx; val dy = y - my; val dz = z - mz
        val nx = mx + dx / count; val ny = my + dy / count; val nz = mz + dz / count
        scratch.cellMoment(cell, 3, scratch.cellMoment(cell, 3) + dx * (x - nx))
        scratch.cellMoment(cell, 4, scratch.cellMoment(cell, 4) + dx * (y - ny))
        scratch.cellMoment(cell, 5, scratch.cellMoment(cell, 5) + dx * (z - nz))
        scratch.cellMoment(cell, 6, scratch.cellMoment(cell, 6) + dy * (y - ny))
        scratch.cellMoment(cell, 7, scratch.cellMoment(cell, 7) + dy * (z - nz))
        scratch.cellMoment(cell, 8, scratch.cellMoment(cell, 8) + dz * (z - nz))
        scratch.cellMoment(cell, 0, nx); scratch.cellMoment(cell, 1, ny); scratch.cellMoment(cell, 2, nz)
        scratch.cellWord(base + COUNT, count)
    }

    /** At most twelve largest-off-diagonal rotations, followed by an explicit eigen residual. */
    fun solve(scratch: DepthCellPlaneScratch, cell: Int, base: Int, cameraX: Double, cameraY: Double, cameraZ: Double): Boolean {
        if (scratch.cellWord(base + COUNT) < 3 || scratch.cellWord(base + STATUS) == REJECTED) return false
        val originalXX = scratch.cellMoment(cell, 3); val originalXY = scratch.cellMoment(cell, 4)
        val originalXZ = scratch.cellMoment(cell, 5); val originalYY = scratch.cellMoment(cell, 6)
        val originalYZ = scratch.cellMoment(cell, 7); val originalZZ = scratch.cellMoment(cell, 8)
        val trace = originalXX + originalYY + originalZZ
        if (!trace.isFinite() || trace <= 0.0 || !originalXY.isFinite() || !originalXZ.isFinite() || !originalYZ.isFinite()) return false
        var xx = originalXX; var xy = originalXY; var xz = originalXZ
        var yy = originalYY; var yz = originalYZ; var zz = originalZZ
        var ax = 1.0; var ay = 0.0; var az = 0.0
        var bx = 0.0; var by = 1.0; var bz = 0.0
        var cx = 0.0; var cy = 0.0; var cz = 1.0
        for (sweep in 0 until MAXIMUM_SWEEPS) {
            if (maxOf(abs(xy), abs(xz), abs(yz)) <= trace * 1e-12) break
            val pair = if (abs(xy) >= abs(xz) && abs(xy) >= abs(yz)) 0 else if (abs(xz) >= abs(yz)) 1 else 2
            val a = if (pair == 2) yy else xx
            val b = if (pair == 0) yy else zz
            val off = if (pair == 0) xy else if (pair == 1) xz else yz
            val tau = (b - a) / (2.0 * off)
            val t = (if (tau < 0.0) -1.0 else 1.0) / (abs(tau) + sqrt(1.0 + tau * tau))
            val c = 1.0 / sqrt(1.0 + t * t); val s = t * c
            when (pair) {
                0 -> {
                    xx = a - t * off; yy = b + t * off; xy = 0.0
                    val old = xz; xz = c * old - s * yz; yz = s * old + c * yz
                    val ox = ax; val oy = ay; val oz = az
                    ax = c * ox - s * bx; ay = c * oy - s * by; az = c * oz - s * bz
                    bx = s * ox + c * bx; by = s * oy + c * by; bz = s * oz + c * bz
                }
                1 -> {
                    xx = a - t * off; zz = b + t * off; xz = 0.0
                    val old = xy; xy = c * old - s * yz; yz = s * old + c * yz
                    val ox = ax; val oy = ay; val oz = az
                    ax = c * ox - s * cx; ay = c * oy - s * cy; az = c * oz - s * cz
                    cx = s * ox + c * cx; cy = s * oy + c * cy; cz = s * oz + c * cz
                }
                else -> {
                    yy = a - t * off; zz = b + t * off; yz = 0.0
                    val old = xy; xy = c * old - s * xz; xz = s * old + c * xz
                    val ox = bx; val oy = by; val oz = bz
                    bx = c * ox - s * cx; by = c * oy - s * cy; bz = c * oz - s * cz
                    cx = s * ox + c * cx; cy = s * oy + c * cy; cz = s * oz + c * cz
                }
            }
        }
        if (!xx.isFinite() || !yy.isFinite() || !zz.isFinite() ||
            maxOf(abs(xy), abs(xz), abs(yz)) > trace * 1e-10) return false
        val which = if (xx <= yy && xx <= zz) 0 else if (yy <= zz) 1 else 2
        val smallest = if (which == 0) xx else if (which == 1) yy else zz
        val second = if (which == 0) minOf(yy, zz) else if (which == 1) minOf(xx, zz) else minOf(xx, yy)
        if (smallest < -trace * 1e-10 || second <= trace * 1e-6 ||
            maxOf(0.0, smallest) > second * ANGULAR_SINE * ANGULAR_SINE) return false
        var nx = if (which == 0) ax else if (which == 1) bx else cx
        var ny = if (which == 0) ay else if (which == 1) by else cy
        var nz = if (which == 0) az else if (which == 1) bz else cz
        val length = sqrt(nx * nx + ny * ny + nz * nz)
        if (!length.isFinite() || length <= 0.0) return false
        nx /= length; ny /= length; nz /= length
        val ex = originalXX * nx + originalXY * ny + originalXZ * nz - smallest * nx
        val ey = originalXY * nx + originalYY * ny + originalYZ * nz - smallest * ny
        val ez = originalXZ * nx + originalYZ * ny + originalZZ * nz - smallest * nz
        if (sqrt(ex * ex + ey * ey + ez * ez) > maxOf(1e-10, trace * 1e-8)) return false
        val mx = scratch.cellMoment(cell, 0); val my = scratch.cellMoment(cell, 1); val mz = scratch.cellMoment(cell, 2)
        val facing = (cameraX - mx) * nx + (cameraY - my) * ny + (cameraZ - mz) * nz
        if (!facing.isFinite() || abs(facing) <= 1e-8) return false
        if (facing < 0.0) { nx = -nx; ny = -ny; nz = -nz }
        double(scratch, base + MEAN_X, mx); double(scratch, base + MEAN_Y, my); double(scratch, base + MEAN_Z, mz)
        double(scratch, base + NORMAL_X, nx); double(scratch, base + NORMAL_Y, ny); double(scratch, base + NORMAL_Z, nz)
        double(scratch, base + NEAREST_DISTANCE, Double.POSITIVE_INFINITY)
        double(scratch, base + MAXIMUM_RESIDUAL, 0.0)
        scratch.cellWord(base + CENTRE_SAMPLE, -1)
        scratch.cellWord(base + STATUS, SOLVED)
        return true
    }
}
