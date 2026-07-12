package com.uhg0.ar_flutter_plugin_2.capture

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Sharpness estimate from one Bayer color phase of a RAW_SENSOR plane.
 * Sampling every second pixel avoids treating the Bayer color pattern itself
 * as scene detail. Values are normalized to 8-bit before Laplacian analysis.
 */
internal object RawPlaneBlurAnalyzer {
    private const val MaxAnalysisDimension = 1024

    fun analyze(
        buffer: ByteBuffer,
        rowStride: Int,
        pixelStride: Int,
        width: Int,
        height: Int,
    ): PreviewBlurQuality {
        require(width > 4 && height > 4)
        require(pixelStride >= 2) { "RAW_SENSOR pixels must expose at least 16 bits" }
        val dimensionStep =
            ((maxOf(width, height) + MaxAnalysisDimension - 1) /
                MaxAnalysisDimension).coerceAtLeast(1)
        val sampleStep = if (dimensionStep % 2 == 0) dimensionStep else dimensionStep + 1
        val sampledWidth = width / sampleStep
        val sampledHeight = height / sampleStep
        require(sampledWidth > 2 && sampledHeight > 2)

        val source = buffer.duplicate().order(ByteOrder.nativeOrder())
        val base = source.position()
        val samples = IntArray(sampledWidth * sampledHeight)
        var min = Int.MAX_VALUE
        var max = Int.MIN_VALUE
        for (row in 0 until sampledHeight) {
            val sourceRow = row * sampleStep
            for (column in 0 until sampledWidth) {
                val sourceColumn = column * sampleStep
                val offset = base + sourceRow * rowStride + sourceColumn * pixelStride
                val value = source.getShort(offset).toInt() and 0xffff
                samples[row * sampledWidth + column] = value
                min = minOf(min, value)
                max = maxOf(max, value)
            }
        }
        val range = (max - min).coerceAtLeast(1)
        val normalized = ByteArray(samples.size) { index ->
            (((samples[index] - min) * 255L / range).toInt() and 0xff).toByte()
        }
        return LaplacianVarianceAnalyzer.analyze(
            luma = normalized,
            width = sampledWidth,
            height = sampledHeight,
            maxAnalysisDimension = MaxAnalysisDimension,
            borderFraction = 0.05,
            algorithm = "rawBayerPhaseLaplacianVarianceV1",
        )
    }
}
