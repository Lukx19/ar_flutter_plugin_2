package com.uhg0.ar_flutter_plugin_2.visibilityprotocol

/** Shared finite affine inverse validation for protocol and renderer group frames. */
internal object CoordinateFrameTransforms {
    fun areFiniteAffineInverses(first: List<Double>, second: List<Double>): Boolean =
        areFiniteAffineInverses(first.size, first::get, second.size, second::get)

    fun areFiniteAffineInverses(first: DoubleArray, second: DoubleArray): Boolean =
        areFiniteAffineInverses(first.size, first::get, second.size, second::get)

    private fun areFiniteAffineInverses(
        firstSize: Int,
        first: (Int) -> Double,
        secondSize: Int,
        second: (Int) -> Double,
    ): Boolean {
        if (firstSize != 16 || secondSize != 16) return false
        if ((0 until 16).any { !first(it).isFinite() || !second(it).isFinite() }) return false
        val affine = listOf(first, second).all { matrix ->
            kotlin.math.abs(matrix(3)) <= AFFINE_TOLERANCE &&
                kotlin.math.abs(matrix(7)) <= AFFINE_TOLERANCE &&
                kotlin.math.abs(matrix(11)) <= AFFINE_TOLERANCE &&
                kotlin.math.abs(matrix(15) - 1.0) <= AFFINE_TOLERANCE
        }
        if (!affine) return false
        return (0 until 4).all { row ->
            (0 until 4).all { column ->
                val actual = (0 until 4).sumOf { index ->
                    first(index * 4 + row) * second(column * 4 + index)
                }
                val expected = if (row == column) 1.0 else 0.0
                kotlin.math.abs(actual - expected) <= INVERSE_TOLERANCE
            }
        }
    }

    private const val AFFINE_TOLERANCE = 1e-9
    private const val INVERSE_TOLERANCE = 1e-6
}
