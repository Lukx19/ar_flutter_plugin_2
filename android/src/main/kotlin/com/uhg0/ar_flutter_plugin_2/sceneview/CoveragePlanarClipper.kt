package com.uhg0.ar_flutter_plugin_2.sceneview

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

/** Reusable plane/cube intersection. Plane origin is observed geometry, never inferred from the voxel center. */
internal class CoveragePlanarClipper {
    private val vertices = DoubleArray(18)
    private val distances = DoubleArray(8)
    private var centerX = 0.0
    private var centerY = 0.0
    private var centerZ = 0.0
    var vertexCount = 0
        private set

    fun clip(
        minX: Double, minY: Double, minZ: Double, size: Double,
        originX: Double, originY: Double, originZ: Double,
        normalX: Double, normalY: Double, normalZ: Double,
    ): Boolean {
        vertexCount = 0
        if (!minX.isFinite() || !minY.isFinite() || !minZ.isFinite() ||
            !size.isFinite() || size <= 0.0 || !originX.isFinite() ||
            !originY.isFinite() || !originZ.isFinite() || !normalX.isFinite() ||
            !normalY.isFinite() || !normalZ.isFinite()) return false
        val length = sqrt(normalX * normalX + normalY * normalY + normalZ * normalZ)
        if (!length.isFinite() || length <= 1e-12) return false
        val nx = normalX / length
        val ny = normalY / length
        val nz = normalZ / length
        val half = size * 0.5
        centerX = minX + half
        centerY = minY + half
        centerZ = minZ + half
        if (!centerX.isFinite() || !centerY.isFinite() || !centerZ.isFinite()) return false
        // Local coordinates avoid subtracting large world-plane constants.
        val offset = nx * (originX - centerX) + ny * (originY - centerY) + nz * (originZ - centerZ)
        if (!offset.isFinite()) return false
        val epsilon = max(size * 1e-10, 1e-13)
        for (corner in 0..7) {
            distances[corner] = nx * signedHalf(corner, 0, half) +
                ny * signedHalf(corner, 1, half) + nz * signedHalf(corner, 2, half) - offset
        }
        for (corner in 0..7) {
            for (axis in 0..2) {
                val bit = 1 shl axis
                if ((corner and bit) != 0) continue
                val other = corner or bit
                val a = distances[corner]
                val b = distances[other]
                val x = signedHalf(corner, 0, half)
                val y = signedHalf(corner, 1, half)
                val z = signedHalf(corner, 2, half)
                if (abs(a) <= epsilon && !add(x, y, z, epsilon)) return false
                if (abs(b) <= epsilon && !add(
                        signedHalf(other, 0, half), signedHalf(other, 1, half),
                        signedHalf(other, 2, half), epsilon,
                    )) return false
                if ((a < -epsilon && b > epsilon) || (a > epsilon && b < -epsilon)) {
                    val t = a / (a - b)
                    if (!add(x + if (axis == 0) size * t else 0.0,
                            y + if (axis == 1) size * t else 0.0,
                            z + if (axis == 2) size * t else 0.0, epsilon)) return false
                }
            }
        }
        if (vertexCount < 3) { vertexCount = 0; return false }
        var cx = 0.0
        var cy = 0.0
        var cz = 0.0
        for (index in 0 until vertexCount) {
            cx += vertices[index * 3]
            cy += vertices[index * 3 + 1]
            cz += vertices[index * 3 + 2]
        }
        cx /= vertexCount
        cy /= vertexCount
        cz /= vertexCount
        val drop = if (abs(nx) >= abs(ny) && abs(nx) >= abs(nz)) 0 else if (abs(ny) >= abs(nz)) 1 else 2
        val u = (drop + 1) % 3
        val v = (drop + 2) % 3
        val normal = if (drop == 0) nx else if (drop == 1) ny else nz
        val direction = if (normal < 0.0) -1.0 else 1.0
        val meanU = if (u == 0) cx else if (u == 1) cy else cz
        val meanV = if (v == 0) cx else if (v == 1) cy else cz
        for (index in 1 until vertexCount) {
            val x = vertices[index * 3]
            val y = vertices[index * 3 + 1]
            val z = vertices[index * 3 + 2]
            val px = vertices[index * 3 + u] - meanU
            val py = (vertices[index * 3 + v] - meanV) * direction
            var destination = index
            while (destination > 0 && precedes(px, py,
                    vertices[(destination - 1) * 3 + u] - meanU,
                    (vertices[(destination - 1) * 3 + v] - meanV) * direction)) {
                vertices[destination * 3] = vertices[(destination - 1) * 3]
                vertices[destination * 3 + 1] = vertices[(destination - 1) * 3 + 1]
                vertices[destination * 3 + 2] = vertices[(destination - 1) * 3 + 2]
                destination--
            }
            vertices[destination * 3] = x
            vertices[destination * 3 + 1] = y
            vertices[destination * 3 + 2] = z
        }
        return true
    }

    fun componentAt(vertex: Int, component: Int): Double {
        require(vertex >= 0 && vertex < vertexCount && component >= 0 && component < 3)
        return vertices[vertex * 3 + component] + when (component) { 0 -> centerX; 1 -> centerY; else -> centerZ }
    }

    private fun signedHalf(corner: Int, axis: Int, half: Double) = if ((corner and (1 shl axis)) == 0) -half else half

    private fun add(x: Double, y: Double, z: Double, epsilon: Double): Boolean {
        for (index in 0 until vertexCount) {
            if (abs(vertices[index * 3] - x) <= epsilon && abs(vertices[index * 3 + 1] - y) <= epsilon &&
                abs(vertices[index * 3 + 2] - z) <= epsilon) return true
        }
        if (vertexCount == 6) { vertexCount = 0; return false }
        vertices[vertexCount * 3] = x
        vertices[vertexCount * 3 + 1] = y
        vertices[vertexCount * 3 + 2] = z
        vertexCount++
        return true
    }

    private fun precedes(ax: Double, ay: Double, bx: Double, by: Double): Boolean {
        val upperA = ay > 0.0 || (ay == 0.0 && ax >= 0.0)
        val upperB = by > 0.0 || (by == 0.0 && bx >= 0.0)
        if (upperA != upperB) return upperA
        val cross = ax * by - ay * bx
        return if (cross != 0.0) cross > 0.0 else ax * ax + ay * ay < bx * bx + by * by
    }
}
