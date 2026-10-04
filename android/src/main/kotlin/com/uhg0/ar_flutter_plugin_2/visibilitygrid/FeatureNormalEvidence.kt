package com.uhg0.ar_flutter_plugin_2.visibilitygrid

import java.math.BigInteger
import kotlin.math.floor

/**
 * Immutable, fixed-point evidence passed from capture ingress to the canonical surface normal kernel.
 * It deliberately has no identity, lineage, or publication fields.
 */
internal data class FeatureNormalEvidence(
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
            val converted = ArrayList<FeatureNormalEvidence>(observation.samples.size)
            observation.samples.forEach { sample ->
                val x = voxel(sample.xWorld) ?: return Conversion.Refused
                val y = voxel(sample.yWorld) ?: return Conversion.Refused
                val z = voxel(sample.zWorld) ?: return Conversion.Refused
                val sampleX = intMillimeters(sample.xWorld) ?: return Conversion.Refused
                val sampleY = intMillimeters(sample.yWorld) ?: return Conversion.Refused
                val sampleZ = intMillimeters(sample.zWorld) ?: return Conversion.Refused
                val confidence = q15(sample.confidence) ?: return Conversion.Refused
                if (sampleX == cameraX && sampleY == cameraY && sampleZ == cameraZ) return Conversion.Refused
                converted += FeatureNormalEvidence(x, y, z, sampleX, sampleY, sampleZ, cameraX, cameraY, cameraZ, confidence)
            }
            return Conversion.Accepted(converted)
        }

        /** Scalar packed handoff adapter used before the fusion workspace takes over. */
        fun fromPacked(observation: VisibilityFeatureObservation): Conversion {
            val packed = observation.packedSamples ?: return from(observation)
            val pose = observation.frame.pose.worldFromCameraGl
            val cameraX = intMillimeters(pose[12]) ?: return Conversion.Refused
            val cameraY = intMillimeters(pose[13]) ?: return Conversion.Refused
            val cameraZ = intMillimeters(pose[14]) ?: return Conversion.Refused
            val converted = ArrayList<FeatureNormalEvidence>(packed.count)
            for (index in 0 until packed.count) {
                val xWorld = packed.xWorldAt(index)
                val yWorld = packed.yWorldAt(index)
                val zWorld = packed.zWorldAt(index)
                val x = voxel(xWorld) ?: return Conversion.Refused
                val y = voxel(yWorld) ?: return Conversion.Refused
                val z = voxel(zWorld) ?: return Conversion.Refused
                val sampleX = intMillimeters(xWorld) ?: return Conversion.Refused
                val sampleY = intMillimeters(yWorld) ?: return Conversion.Refused
                val sampleZ = intMillimeters(zWorld) ?: return Conversion.Refused
                val confidence = q15(packed.confidenceAt(index)) ?: return Conversion.Refused
                if (sampleX == cameraX && sampleY == cameraY && sampleZ == cameraZ) {
                    return Conversion.Refused
                }
                converted += FeatureNormalEvidence(
                    x, y, z, sampleX, sampleY, sampleZ,
                    cameraX, cameraY, cameraZ, confidence,
                )
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
        data class Accepted(val evidence: List<FeatureNormalEvidence>) : Conversion
        data object Refused : Conversion
    }
}

/** Exact C12 Q1.15 and signed-oct helpers. Kept free of floating normalization. */
internal object FeatureNormalMath {
    fun normalizeQ15(x: Long, y: Long, z: Long): IntArray? {
        val result = IntArray(3)
        return result.takeIf { normalizeQ15Into(x, y, z, it) }
    }

    /** Exact scalar destination form used by the packed fusion workspace. */
    internal fun normalizeQ15Into(x: Long, y: Long, z: Long, destination: IntArray): Boolean {
        require(destination.size >= 3)
        val squaredX = BigInteger.valueOf(x).pow(2)
        val squaredY = BigInteger.valueOf(y).pow(2)
        val squaredZ = BigInteger.valueOf(z).pow(2)
        val length2 = squaredX.add(squaredY).add(squaredZ)
        if (length2 == BigInteger.ZERO) return false
        val q15Squared = BigInteger.valueOf(32_767L * 32_767L)
        destination[0] = normalizeComponent(x, squaredX, length2, q15Squared)
        destination[1] = normalizeComponent(y, squaredY, length2, q15Squared)
        destination[2] = normalizeComponent(z, squaredZ, length2, q15Squared)
        return true
    }

    private fun normalizeComponent(
        component: Long,
        squared: BigInteger,
        length2: BigInteger,
        q15Squared: BigInteger,
    ): Int {
        val numerator = squared.multiply(q15Squared)
        // squared <= length2, so this quotient is always in 0..32_767^2
        // and is exactly representable by Long on supported API levels.
        val scaledSquared = numerator.divide(length2)
        check(scaledSquared.signum() >= 0 && scaledSquared.bitLength() <= 30)
        val q = integerSqrt(scaledSquared.toLong())
        val next = BigInteger.valueOf(2L * q + 1L)
        val comparison = numerator.shiftLeft(2).compareTo(length2.multiply(next.pow(2)))
        val rounded = if (comparison > 0 || (comparison == 0 && (q and 1L) == 1L)) q + 1L else q
        return (if (component < 0L) -rounded else rounded).toInt()
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
        return normalizeDecodedOctQ15(unfolded[0], unfolded[1], unfolded[2])
    }

    /**
     * Exact [normalizeQ15] specialization for signed-oct decode coordinates.
     *
     * Every input component is in `-32_767..32_767`, so the squared length,
     * Q15 numerator and midpoint comparison are all bounded by `Long`. Keeping
     * this hot exhaustive-search path out of [BigInteger] preserves the same
     * ties-to-even result without allocating big integers for every candidate.
     */
    internal fun normalizeDecodedOctQ15(x: Int, y: Int, z: Int): IntArray? {
        require(x in -32_767..32_767 && y in -32_767..32_767 && z in -32_767..32_767)
        val components = intArrayOf(x, y, z)
        val squared = LongArray(components.size) { index ->
            components[index].toLong() * components[index].toLong()
        }
        val length2 = squared.fold(0L, Math::addExact)
        if (length2 == 0L) return null
        val q15Squared = 32_767L * 32_767L
        return IntArray(components.size) { index ->
            val numerator = Math.multiplyExact(squared[index], q15Squared)
            val q = integerSqrt(numerator / length2)
            val next = 2L * q + 1L
            val comparison = Math.multiplyExact(numerator, 4L).compareTo(
                Math.multiplyExact(length2, Math.multiplyExact(next, next)),
            )
            val rounded = if (comparison > 0 || (comparison == 0 && (q and 1L) == 1L)) q + 1L else q
            (if (components[index] < 0) -rounded else rounded).toInt()
        }
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
