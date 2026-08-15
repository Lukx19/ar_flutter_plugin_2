package com.uhg0.ar_flutter_plugin_2.m0

data class M0Q15Vector(val x: Int, val y: Int, val z: Int) {
    fun dot(other: M0Q15Vector): Long =
        x.toLong() * other.x + y.toLong() * other.y + z.toLong() * other.z
}

object M0PictureViewBins24 {
    const val Q15_MAXIMUM = 32767
    const val FRONT_DOT = 268419072L
    const val NEAR_DOT = 858941031L
    const val DIVERSE_DOT = 759203784L
    const val PACKED_COUNT_MAXIMUM = 65535

    val centers: List<M0Q15Vector> = listOf(
        M0Q15Vector(-9346, -21845, -22564),
        M0Q15Vector(-22564, -21845, -9346),
        M0Q15Vector(-22564, -21845, 9346),
        M0Q15Vector(-9346, -21845, 22564),
        M0Q15Vector(9346, -21845, 22564),
        M0Q15Vector(22564, -21845, 9346),
        M0Q15Vector(22564, -21845, -9346),
        M0Q15Vector(9346, -21845, -22564),
        M0Q15Vector(-12539, 0, -30273),
        M0Q15Vector(-30273, 0, -12539),
        M0Q15Vector(-30273, 0, 12539),
        M0Q15Vector(-12539, 0, 30273),
        M0Q15Vector(12539, 0, 30273),
        M0Q15Vector(30273, 0, 12539),
        M0Q15Vector(30273, 0, -12539),
        M0Q15Vector(12539, 0, -30273),
        M0Q15Vector(-9346, 21845, -22564),
        M0Q15Vector(-22564, 21845, -9346),
        M0Q15Vector(-22564, 21845, 9346),
        M0Q15Vector(-9346, 21845, 22564),
        M0Q15Vector(9346, 21845, 22564),
        M0Q15Vector(22564, 21845, 9346),
        M0Q15Vector(22564, 21845, -9346),
        M0Q15Vector(9346, 21845, -22564),
    )

    fun classify(vector: M0Q15Vector): Int {
        val band = when {
            vector.y * 3 < -32767 -> 0
            vector.y * 3 < 32767 -> 1
            else -> 2
        }
        val absoluteX = kotlin.math.abs(vector.x)
        val absoluteZ = kotlin.math.abs(vector.z)
        val azimuth = when {
            absoluteX == 0 && absoluteZ == 0 -> 0
            absoluteX == absoluteZ -> when {
                vector.x < 0 && vector.z < 0 -> 1
                vector.x < 0 -> 3
                vector.z >= 0 -> 5
                else -> 7
            }
            absoluteX < absoluteZ -> if (vector.z < 0) 0 else 4
            else -> if (vector.x < 0) 2 else 6
        }
        return band * 8 + azimuth
    }

    fun normalize(x: Int, y: Int, z: Int): M0Q15Vector {
        val lengthSquared = x.toLong() * x + y.toLong() * y + z.toLong() * z
        require(lengthSquared > 0) { "Direction vector is degenerate." }
        return M0Q15Vector(
            normalizeComponent(x, lengthSquared),
            normalizeComponent(y, lengthSquared),
            normalizeComponent(z, lengthSquared),
        )
    }

    private fun normalizeComponent(component: Int, lengthSquared: Long): Int {
        val absolute = kotlin.math.abs(component).toLong()
        val maximum = Q15_MAXIMUM.toLong()
        val numerator = absolute * absolute * maximum * maximum
        var result = integerSqrt(numerator / lengthSquared)
        while (4 * numerator > lengthSquared * (2 * result + 1) * (2 * result + 1)) {
            result++
        }
        if (
            4 * numerator == lengthSquared * (2 * result + 1) * (2 * result + 1) &&
            result % 2L == 0L
        ) {
            result++
        }
        return (if (component < 0) -result else result).toInt()
    }

    private fun integerSqrt(value: Long): Long {
        var low = 0L
        var high = 1L shl 31
        var answer = 0L
        while (low <= high) {
            val middle = (low + high) ushr 1
            if (middle == 0L || middle <= value / middle) {
                answer = middle
                low = middle + 1
            } else {
                high = middle - 1
            }
        }
        return answer
    }
}

data class M0VisibilityTriplet(
    val indices: List<Int>,
    val score: Int,
    val complete: Boolean,
    val summedIncidence: Int,
)

class M0VisibilityCoverage24 {
    private val packed = IntArray(24)
    private val overflow = LongArray(24)

    fun packedCounts(): IntArray = packed.copyOf()

    fun overflowCounts(): LongArray = overflow.copyOf()

    fun countAt(bin: Int): Long {
        checkBin(bin)
        return packed[bin].toLong() + overflow[bin]
    }

    fun credit(bin: Int, amount: Long = 1) {
        checkBin(bin)
        require(amount > 0) { "amount must be positive" }
        val exact = countAt(bin) + amount
        packed[bin] = exact.coerceAtMost(M0PictureViewBins24.PACKED_COUNT_MAXIMUM.toLong()).toInt()
        overflow[bin] = exact - packed[bin]
    }

    fun evaluate(
        normal: M0Q15Vector,
        normalConfidence: Int,
        coveragePending: Boolean = false,
        indeterminateHistory: Boolean = false,
        nonresidentPreciseData: Boolean = false,
    ): M0VisibilityTriplet {
        require(normalConfidence in 0..255)
        val candidates = mutableListOf<M0VisibilityTriplet>()
        for (first in 0 until 22) {
            for (second in first + 1 until 23) {
                for (third in second + 1 until 24) {
                    val indices = listOf(first, second, third)
                    if (!isDiverse(indices)) continue
                    val incidence = mutableListOf<Int>()
                    var eligible = true
                    for (index in indices) {
                        val dot = normal.dot(M0PictureViewBins24.centers[index])
                        if (normalConfidence < 64) {
                            incidence += 65535
                        } else if (dot < M0PictureViewBins24.FRONT_DOT) {
                            eligible = false
                            break
                        } else {
                            incidence += roundHalfUp(
                                65535L * (dot - M0PictureViewBins24.FRONT_DOT),
                                M0PictureViewBins24.NEAR_DOT - M0PictureViewBins24.FRONT_DOT,
                            ).coerceIn(0, 65535).toInt()
                        }
                    }
                    if (!eligible) continue
                    val observed = indices.count { countAt(it) > 0 }
                    val capped = indices.sumOf { countAt(it).coerceAtMost(2) }
                    val score = roundHalfUp(
                        255L * (140 * observed + 30 * capped),
                        600,
                    ).toInt()
                    val nearNormal = normalConfidence < 64 ||
                        indices.any {
                            normal.dot(M0PictureViewBins24.centers[it]) >=
                                M0PictureViewBins24.NEAR_DOT
                        }
                    candidates += M0VisibilityTriplet(
                        indices = indices,
                        score = score,
                        complete = indices.all { countAt(it) >= 2 } &&
                            nearNormal &&
                            !coveragePending &&
                            !indeterminateHistory &&
                            !nonresidentPreciseData,
                        summedIncidence = incidence.sum(),
                    )
                }
            }
        }
        return candidates.sortedWith { left, right ->
            compareValuesBy(
                right,
                left,
                M0VisibilityTriplet::score,
                { value -> value.indices.count { countAt(it) >= 2 } },
                { value -> hasNearNormal(value, normal, normalConfidence) },
                M0VisibilityTriplet::summedIncidence,
            ).takeIf { it != 0 } ?: compareLexicographically(left.indices, right.indices)
        }.firstOrNull() ?: M0VisibilityTriplet(emptyList(), 0, false, 0)
    }

    fun directionalNeedCode(bin: Int, complete: Boolean): Int {
        val count = countAt(bin)
        if (complete) return 0
        if (count == 0L) return 20
        if (count == 1L) return 10
        return 1
    }

    private fun isDiverse(indices: List<Int>): Boolean {
        for (left in indices.indices) {
            for (right in left + 1 until indices.size) {
                if (
                    M0PictureViewBins24.centers[indices[left]].dot(
                        M0PictureViewBins24.centers[indices[right]],
                    ) > M0PictureViewBins24.DIVERSE_DOT
                ) return false
            }
        }
        return true
    }

    private fun hasNearNormal(
        triplet: M0VisibilityTriplet,
        normal: M0Q15Vector,
        normalConfidence: Int,
    ): Boolean = normalConfidence < 64 || triplet.indices.any {
        normal.dot(M0PictureViewBins24.centers[it]) >= M0PictureViewBins24.NEAR_DOT
    }

    private fun checkBin(bin: Int) {
        require(bin in 0 until 24) { "bin=$bin" }
    }

    private fun roundHalfUp(numerator: Long, denominator: Long): Long =
        (numerator + denominator / 2) / denominator

    private fun compareLexicographically(left: List<Int>, right: List<Int>): Int {
        for (index in left.indices) {
            val comparison = left[index].compareTo(right[index])
            if (comparison != 0) return comparison
        }
        return 0
    }
}
