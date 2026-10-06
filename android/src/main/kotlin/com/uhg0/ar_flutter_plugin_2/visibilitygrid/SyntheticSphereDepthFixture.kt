package com.uhg0.ar_flutter_plugin_2.visibilitygrid

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/** Cached visible depth patches of a two-metre shell surrounding the camera origin. */
internal class SyntheticSphereDepthFixture(worldFromGroup: List<Double>) {
    data class View(
        val worldPose: DoubleArray,
        val groupPose: DoubleArray,
        val rotationQuaternion: FloatArray,
        val depths: IntArray,
        val featureCoordinates: DoubleArray,
    )

    val views: Array<View> = Array(VIEW_COUNT) { index ->
        val ring = when (index) { 0 -> -2; 19 -> 2; else -> (index - 1) / 6 - 1 }
        val heading = when {
            index == 0 -> 0
            index == 19 -> 5
            ring == 0 -> 5 - (index - 1) % 6
            else -> (index - 1) % 6
        }
        val yaw = heading * PI / 3.0
        val pitch = ring * PI / 4.0
        val orbit = index * 2.0 * PI / VIEW_COUNT
        val cy = cos(yaw); val sy = sin(yaw)
        val cp = cos(pitch); val sp = sin(pitch)
        val groupPose = doubleArrayOf(
            cy, 0.0, sy, 0.0,
            -sy * sp, cp, cy * sp, 0.0,
            -sy * cp, -sp, cy * cp, 0.0,
            .25 * sin(orbit), .15 * (cos(orbit) - 1.0), .10 * sin(2.0 * orbit), 1.0,
        )
        val worldPose = DoubleArray(16) { slot ->
            val row = slot % 4; val column = slot / 4
            (0..3).sumOf { worldFromGroup[it * 4 + row] * groupPose[column * 4 + it] }
        }
        val features = DoubleArray(FEATURE_SAMPLES * 3)
        val depths = IntArray(SAMPLES) { sample ->
            val rx = (pixelX(sample) - PRINCIPAL_X) / FOCAL_LENGTH
            val ry = (PRINCIPAL_Y - pixelY(sample)) / FOCAL_LENGTH
            val norm = sqrt(rx * rx + ry * ry + 1.0)
            val dx = (groupPose[0] * rx + groupPose[4] * ry - groupPose[8]) / norm
            val dy = (groupPose[1] * rx + groupPose[5] * ry - groupPose[9]) / norm
            val dz = (groupPose[2] * rx + groupPose[6] * ry - groupPose[10]) / norm
            val ox = groupPose[12]; val oy = groupPose[13]; val oz = groupPose[14]
            val dot = ox * dx + oy * dy + oz * dz
            val distance = -dot + sqrt(dot * dot + RADIUS * RADIUS - ox * ox - oy * oy - oz * oz)
            if (sample / GRID_EDGE == 32) {
                val feature = (sample % GRID_EDGE - 14) / 9
                if (feature in 0 until FEATURE_SAMPLES && sample % GRID_EDGE == 14 + feature * 9) {
                    features[feature * 3] = ox + distance * dx
                    features[feature * 3 + 1] = oy + distance * dy
                    features[feature * 3 + 2] = oz + distance * dz
                }
            }
            (distance / norm * 1000.0).roundToInt()
        }
        View(worldPose, groupPose, quaternion(worldPose), depths, features)
    }

    companion object {
        const val VIEW_COUNT = 20
        const val FEATURE_SAMPLES = 5
        const val GRID_EDGE = 64
        const val SAMPLES = GRID_EDGE * GRID_EDGE
        const val RADIUS = 2.0
        const val FOCAL_LENGTH = 1000.0
        const val PRINCIPAL_X = 640
        const val PRINCIPAL_Y = 480
        fun pixelX(index: Int): Int = 10 + index % GRID_EDGE * 20
        fun pixelY(index: Int): Int = 7 + index / GRID_EDGE * 15

        private fun quaternion(m: DoubleArray): FloatArray {
            val trace = m[0] + m[5] + m[10]
            val q = when {
                trace > 0 -> {
                    val s = sqrt(trace + 1.0) * 2
                    doubleArrayOf((m[6] - m[9]) / s, (m[8] - m[2]) / s, (m[1] - m[4]) / s, s / 4)
                }
                m[0] > m[5] && m[0] > m[10] -> {
                    val s = sqrt(1.0 + m[0] - m[5] - m[10]) * 2
                    doubleArrayOf(s / 4, (m[4] + m[1]) / s, (m[8] + m[2]) / s, (m[6] - m[9]) / s)
                }
                m[5] > m[10] -> {
                    val s = sqrt(1.0 + m[5] - m[0] - m[10]) * 2
                    doubleArrayOf((m[4] + m[1]) / s, s / 4, (m[9] + m[6]) / s, (m[8] - m[2]) / s)
                }
                else -> {
                    val s = sqrt(1.0 + m[10] - m[0] - m[5]) * 2
                    doubleArrayOf((m[8] + m[2]) / s, (m[9] + m[6]) / s, s / 4, (m[1] - m[4]) / s)
                }
            }
            return FloatArray(4) { q[it].toFloat() }
        }
    }
}
