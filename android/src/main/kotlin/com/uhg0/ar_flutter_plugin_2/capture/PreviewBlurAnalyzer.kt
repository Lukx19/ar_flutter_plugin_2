package com.uhg0.ar_flutter_plugin_2.capture

internal data class PreviewBlurQuality(
    val blurScore: Double,
    val analyzedWidth: Int,
    val analyzedHeight: Int,
    val algorithm: String = "previewLaplacianVarianceV1",
)

internal object LaplacianVarianceAnalyzer {
    fun analyze(
        luma: ByteArray,
        width: Int,
        height: Int,
        maxAnalysisDimension: Int,
        borderFraction: Double,
        algorithm: String,
    ): PreviewBlurQuality {
        require(width > 2) { "width must be greater than 2" }
        require(height > 2) { "height must be greater than 2" }
        require(luma.size >= width * height) { "luma buffer is smaller than width*height" }
        require(maxAnalysisDimension > 2) { "maxAnalysisDimension must be greater than 2" }
        require(borderFraction >= 0.0 && borderFraction < 0.5) {
            "borderFraction must be in [0.0, 0.5)"
        }

        val sampleStep = computeSampleStep(width, height, maxAnalysisDimension)
        val sampledWidth = width / sampleStep
        val sampledHeight = height / sampleStep
        require(sampledWidth > 2 && sampledHeight > 2) {
            "sampled image must remain larger than 2x2"
        }

        val borderX = ((sampledWidth * borderFraction).toInt()).coerceAtMost((sampledWidth - 3) / 2)
        val borderY = ((sampledHeight * borderFraction).toInt()).coerceAtMost((sampledHeight - 3) / 2)
        val startX = borderX + 1
        val endXExclusive = sampledWidth - borderX - 1
        val startY = borderY + 1
        val endYExclusive = sampledHeight - borderY - 1
        require(startX < endXExclusive && startY < endYExclusive) {
            "effective sampled image must remain larger than 2x2 after border exclusion"
        }

        var sum = 0.0
        var sumSquares = 0.0
        var sampleCount = 0

        for (y in startY until endYExclusive) {
            val sourceY = y * sampleStep
            for (x in startX until endXExclusive) {
                val sourceX = x * sampleStep
                val center = luma[sourceY * width + sourceX].toUnsignedInt()
                val left = luma[sourceY * width + (sourceX - sampleStep)].toUnsignedInt()
                val right = luma[sourceY * width + (sourceX + sampleStep)].toUnsignedInt()
                val top = luma[(sourceY - sampleStep) * width + sourceX].toUnsignedInt()
                val bottom = luma[(sourceY + sampleStep) * width + sourceX].toUnsignedInt()

                val laplacian = (-4 * center + left + right + top + bottom).toDouble()
                sum += laplacian
                sumSquares += laplacian * laplacian
                sampleCount += 1
            }
        }

        val mean = sum / sampleCount
        val variance = (sumSquares / sampleCount) - (mean * mean)

        return PreviewBlurQuality(
            blurScore = variance.coerceAtLeast(0.0),
            analyzedWidth = sampledWidth,
            analyzedHeight = sampledHeight,
            algorithm = algorithm,
        )
    }

    private fun computeSampleStep(
        width: Int,
        height: Int,
        maxAnalysisDimension: Int,
    ): Int {
        val longEdge = maxOf(width, height)
        if (longEdge <= maxAnalysisDimension) {
            return 1
        }
        return (longEdge + maxAnalysisDimension - 1) / maxAnalysisDimension
    }

    private fun Byte.toUnsignedInt(): Int = toInt() and 0xFF
}

internal object PreviewBlurAnalyzer {
    private const val MaxAnalysisDimension = 256

    fun analyzeLaplacianVariance(
        luma: ByteArray,
        width: Int,
        height: Int,
        algorithm: String = "previewLaplacianVarianceV1",
    ): PreviewBlurQuality =
        LaplacianVarianceAnalyzer.analyze(
            luma = luma,
            width = width,
            height = height,
            maxAnalysisDimension = MaxAnalysisDimension,
            borderFraction = 0.0,
            algorithm = algorithm,
        )
}
