package com.uhg0.ar_flutter_plugin_2.visibilitygrid

import kotlin.math.abs
import kotlin.math.sqrt

/** Worker-owned, reusable group-space frustum. Camera matrices are GL, column-major, metres. */
internal class ExpandedSurfaceFrustum(
    private val translationMarginMm: Double = 500.0,
    private val angularMarginRadians: Double = Math.toRadians(15.0),
) {
    private val planes = DoubleArray(24)
    private val pose = DoubleArray(16)
    private var intrinsics: VisibilityCameraIntrinsics? = null
    private var depthMm = 0.0
    var valid = false; private set
    var generation = 0L; private set
    var minimumX = 0.0; private set
    var minimumY = 0.0; private set
    var minimumZ = 0.0; private set
    var maximumX = 0.0; private set
    var maximumY = 0.0; private set
    var maximumZ = 0.0; private set

    init { require(translationMarginMm > 0 && angularMarginRadians in 0.0..0.5) }

    /** A false return means invalid input; [generation] changes only when a window is rebuilt. */
    fun update(matrix: List<Double>, camera: VisibilityCameraIntrinsics, maximumDepthMm: Double): Boolean {
        if (!rigid(matrix) || !maximumDepthMm.isFinite() || maximumDepthMm <= 0) {
            valid = false
            return false
        }
        if (valid && intrinsics == camera && depthMm == maximumDepthMm && withinWindow(matrix)) return true
        matrix.forEachIndexed { i, value -> pose[i] = value }
        intrinsics = camera
        depthMm = maximumDepthMm
        // Expand each angular edge independently, preserving an off-centre principal point.
        val left = expanded(camera.cx / camera.fx)
        val right = expanded((camera.imageWidth - camera.cx) / camera.fx)
        val top = expanded(camera.cy / camera.fy)
        val bottom = expanded((camera.imageHeight - camera.cy) / camera.fy)
        setPlane(0, 1.0, 0.0, -left, 0.0)
        setPlane(1, -1.0, 0.0, -right, 0.0)
        setPlane(2, 0.0, 1.0, -bottom, 0.0)
        setPlane(3, 0.0, -1.0, -top, 0.0)
        setPlane(4, 0.0, 0.0, -1.0, 0.0)
        setPlane(5, 0.0, 0.0, 1.0, maximumDepthMm)
        minimumX = Double.POSITIVE_INFINITY; minimumY = minimumX; minimumZ = minimumX
        maximumX = Double.NEGATIVE_INFINITY; maximumY = maximumX; maximumZ = maximumX
        // Bounds include the margin-expanded side planes at both near and far ends.
        val near = -translationMarginMm
        val far = maximumDepthMm + translationMarginMm
        for (depth in 0..1) for (x in 0..1) for (y in 0..1) {
            val d = if (depth == 0) near else far
            val px = if (x == 0) -left * d - translationMarginMm * sqrt(1 + left * left)
                else right * d + translationMarginMm * sqrt(1 + right * right)
            val py = if (y == 0) -bottom * d - translationMarginMm * sqrt(1 + bottom * bottom)
                else top * d + translationMarginMm * sqrt(1 + top * top)
            include(px, py, -d)
        }
        valid = true
        generation++
        return true
    }

    /** Conservative AABB intersection; touching a side/near/far plane remains selected. */
    fun intersects(x: Double, y: Double, z: Double, sizeMm: Double): Boolean {
        if (!valid) return false
        for (p in 0 until 6) {
            val i = p * 4
            val nx = planes[i]; val ny = planes[i + 1]; val nz = planes[i + 2]
            val distance = nx * (if (nx >= 0) x + sizeMm else x) +
                ny * (if (ny >= 0) y + sizeMm else y) +
                nz * (if (nz >= 0) z + sizeMm else z) + planes[i + 3]
            if (distance < -1e-6) return false
        }
        return true
    }

    private fun withinWindow(matrix: List<Double>): Boolean {
        var distanceSquared = 0.0
        for (i in 12..14) { val d = (matrix[i] - pose[i]) * 1_000; distanceSquared += d * d }
        if (distanceSquared > translationMarginMm * translationMarginMm / 16) return false
        // Angle between rotation matrices, without allocating a quaternion/temporary matrix.
        var trace = 0.0
        for (column in 0..2) for (row in 0..2) trace += matrix[column * 4 + row] * pose[column * 4 + row]
        return (trace - 1) / 2 >= kotlin.math.cos(angularMarginRadians / 4)
    }

    private fun expanded(slope: Double): Double = kotlin.math.tan(
        minOf(kotlin.math.atan(slope) + angularMarginRadians, Math.toRadians(85.0)),
    )

    private fun setPlane(index: Int, x: Double, y: Double, z: Double, constant: Double) {
        val i = index * 4
        val nx = pose[0] * x + pose[4] * y + pose[8] * z
        val ny = pose[1] * x + pose[5] * y + pose[9] * z
        val nz = pose[2] * x + pose[6] * y + pose[10] * z
        planes[i] = nx; planes[i + 1] = ny; planes[i + 2] = nz
        planes[i + 3] = constant - (nx * pose[12] + ny * pose[13] + nz * pose[14]) * 1_000 +
            translationMarginMm * sqrt(nx * nx + ny * ny + nz * nz)
    }

    private fun include(x: Double, y: Double, z: Double) {
        val gx = pose[0] * x + pose[4] * y + pose[8] * z + pose[12] * 1_000
        val gy = pose[1] * x + pose[5] * y + pose[9] * z + pose[13] * 1_000
        val gz = pose[2] * x + pose[6] * y + pose[10] * z + pose[14] * 1_000
        minimumX = minOf(minimumX, gx); maximumX = maxOf(maximumX, gx)
        minimumY = minOf(minimumY, gy); maximumY = maxOf(maximumY, gy)
        minimumZ = minOf(minimumZ, gz); maximumZ = maxOf(maximumZ, gz)
    }

    private fun rigid(matrix: List<Double>): Boolean {
        if (matrix.size != 16 || matrix.any { !it.isFinite() } || abs(matrix[15] - 1) > 1e-5 ||
            abs(matrix[3]) + abs(matrix[7]) + abs(matrix[11]) > 1e-5) return false
        for (a in 0..2) for (b in a..2) {
            var dot = 0.0
            for (row in 0..2) dot += matrix[a * 4 + row] * matrix[b * 4 + row]
            if (abs(dot - if (a == b) 1.0 else 0.0) > 1e-4) return false
        }
        val determinant = matrix[0] * (matrix[5] * matrix[10] - matrix[9] * matrix[6]) -
            matrix[4] * (matrix[1] * matrix[10] - matrix[9] * matrix[2]) +
            matrix[8] * (matrix[1] * matrix[6] - matrix[5] * matrix[2])
        return abs(determinant - 1.0) <= 1e-4
    }
}
