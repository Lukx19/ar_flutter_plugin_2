package com.uhg0.ar_flutter_plugin_2.visibilitygrid

import java.math.BigInteger
import kotlin.math.floor

/**
 * Immutable, fixed-point evidence passed from M2 to the M3 normal kernel.
 * It deliberately has no identity, lineage, or publication fields.
 */
internal data class M3FeatureNormalEvidence(
    val voxelX: Int,
    val voxelY: Int,
    val voxelZ: Int,
    val sampleXmm: Int,
    val sampleYmm: Int,
    val sampleZmm: Int,
    val cameraXmm: Int,
    val cameraYmm: Int,
    val cameraZmm: Int,
    val confidenceQ15: Int,
) {
    companion object {
        fun from(observation: VisibilityFeatureObservation): Conversion {
            val pose = observation.frame.pose.worldFromCameraGl
            val cameraX = intMillimeters(pose[12]) ?: return Conversion.Refused
            val cameraY = intMillimeters(pose[13]) ?: return Conversion.Refused
            val cameraZ = intMillimeters(pose[14]) ?: return Conversion.Refused
            val converted = ArrayList<M3FeatureNormalEvidence>(observation.samples.size)
            observation.samples.forEach { sample ->
                val x = voxel(sample.xWorld) ?: return Conversion.Refused
                val y = voxel(sample.yWorld) ?: return Conversion.Refused
                val z = voxel(sample.zWorld) ?: return Conversion.Refused
                val sampleX = intMillimeters(sample.xWorld) ?: return Conversion.Refused
                val sampleY = intMillimeters(sample.yWorld) ?: return Conversion.Refused
                val sampleZ = intMillimeters(sample.zWorld) ?: return Conversion.Refused
                val confidence = q15(sample.confidence) ?: return Conversion.Refused
                if (sampleX == cameraX && sampleY == cameraY && sampleZ == cameraZ) return Conversion.Refused
                converted += M3FeatureNormalEvidence(x, y, z, sampleX, sampleY, sampleZ, cameraX, cameraY, cameraZ, confidence)
            }
            return Conversion.Accepted(converted)
        }

        /** Exact round-to-nearest, ties-to-even conversion with a checked Int result. */
        internal fun intMillimeters(meters: Double): Int? {
            if (!meters.isFinite()) return null
            val rounded = Math.rint(meters * 1_000.0)
            return if (rounded.isFinite() && rounded >= Int.MIN_VALUE.toDouble() && rounded <= Int.MAX_VALUE.toDouble()) rounded.toInt() else null
        }

        internal fun q15(confidence: Double): Int? {
            if (!confidence.isFinite() || confidence !in 0.0..1.0) return null
            return Math.rint(confidence * 32_767.0).toInt().takeIf { it in 0..32_767 }
        }

        private fun voxel(meters: Double): Int? {
            if (!meters.isFinite()) return null
            val value = floor(meters / 0.1)
            return if (value >= -(1 shl 20).toDouble() && value <= ((1 shl 20) - 1).toDouble()) value.toInt() else null
        }
    }

    internal sealed interface Conversion {
        data class Accepted(val evidence: List<M3FeatureNormalEvidence>) : Conversion
        data object Refused : Conversion
    }
}

/** Exact C12 Q1.15 and signed-oct helpers. Kept free of floating normalization. */
internal object M3NormalMath {
    fun normalizeQ15(x: Long, y: Long, z: Long): IntArray? {
        val components = longArrayOf(x, y, z)
        val squared = components.map { BigInteger.valueOf(it).pow(2) }
        val length2 = squared.fold(BigInteger.ZERO, BigInteger::add)
        if (length2 == BigInteger.ZERO) return null
        val q15Squared = BigInteger.valueOf(32_767L * 32_767L)
        return components.mapIndexed { index, component ->
            val numerator = squared[index].multiply(q15Squared)
            val q = integerSqrt(numerator.divide(length2).longValueExact())
            val next = BigInteger.valueOf(2L * q + 1L)
            val comparison = numerator.shiftLeft(2).compareTo(length2.multiply(next.pow(2)))
            val rounded = if (comparison > 0 || (comparison == 0 && (q and 1L) == 1L)) q + 1L else q
            (if (component < 0L) -rounded else rounded).toInt()
        }.toIntArray()
    }

    fun encodeOct(vector: IntArray): Int {
        var winner = 0
        var best = Long.MIN_VALUE
        for (packed in 0..0xffff) {
            val x = (packed ushr 8).toByte().toInt()
            val y = (packed and 0xff).toByte().toInt()
            if (x == -128 || y == -128) continue
            val decoded = decodeOct(x, y) ?: continue
            val dot = decoded[0].toLong() * vector[0] + decoded[1].toLong() * vector[1] + decoded[2].toLong() * vector[2]
            if (dot > best || (dot == best && packed < winner)) { best = dot; winner = packed }
        }
        return winner
    }

    fun decodeOct(xByte: Int, yByte: Int): IntArray? {
        if (xByte !in -127..127 || yByte !in -127..127) return null
        fun scale(value: Int) = roundTiesEven(value.toLong() * 32_767L, 127L).toInt()
        val x = scale(xByte); val y = scale(yByte)
        val z = 32_767 - kotlin.math.abs(x) - kotlin.math.abs(y)
        val unfolded = if (z < 0) intArrayOf(signNonZero(x) * (32_767 - kotlin.math.abs(y)), signNonZero(y) * (32_767 - kotlin.math.abs(x)), z) else intArrayOf(x, y, z)
        return normalizeQ15(unfolded[0].toLong(), unfolded[1].toLong(), unfolded[2].toLong())
    }

    fun negated(vector: IntArray) = intArrayOf(-vector[0], -vector[1], -vector[2])
    fun confidenceQ13(confidenceQ15: Int): Int = roundTiesEven(confidenceQ15.toLong() * 8192L, 32_767L).toInt()
    fun roundTiesEven(numerator: Long, denominator: Long): Long {
        require(denominator > 0)
        val magnitude = kotlin.math.abs(numerator)
        val quotient = magnitude / denominator
        val remainder = magnitude % denominator
        val twice = remainder * 2L
        val rounded = if (twice > denominator || (twice == denominator && (quotient and 1L) == 1L)) quotient + 1 else quotient
        return if (numerator < 0L) -rounded else rounded
    }

    private fun signNonZero(value: Int) = if (value < 0) -1 else 1
    private fun integerSqrt(value: Long): Long {
        var low = 0L; var high = 1L shl 31
        while (low < high) { val middle = (low + high + 1L) ushr 1; if (middle <= value / middle) low = middle else high = middle - 1L }
        return low
    }
}
