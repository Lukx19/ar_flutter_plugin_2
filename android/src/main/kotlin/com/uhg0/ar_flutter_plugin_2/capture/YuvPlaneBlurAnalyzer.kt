package com.uhg0.ar_flutter_plugin_2.capture

import java.nio.ByteBuffer

/** Blur analysis directly from the Y plane, before RGB conversion/encoding. */
internal object YuvPlaneBlurAnalyzer {
    private const val MaxAnalysisDimension = 1024

    fun analyze(
        buffer: ByteBuffer,
        rowStride: Int,
        pixelStride: Int,
        cropLeft: Int,
        cropTop: Int,
        cropWidth: Int,
        cropHeight: Int,
    ): PreviewBlurQuality {
        require(cropWidth > 2 && cropHeight > 2)
        val sampleStep =
            ((maxOf(cropWidth, cropHeight) + MaxAnalysisDimension - 1) /
                MaxAnalysisDimension).coerceAtLeast(1)
        val width = cropWidth / sampleStep
        val height = cropHeight / sampleStep
        require(width > 2 && height > 2)
        val source = buffer.duplicate()
        val base = source.position()
        val packed = ByteArray(width * height)
        for (row in 0 until height) {
            val sourceRow = cropTop + row * sampleStep
            val rowOffset = base + sourceRow * rowStride
            for (column in 0 until width) {
                val sourceColumn = cropLeft + column * sampleStep
                packed[row * width + column] =
                    source.get(rowOffset + sourceColumn * pixelStride)
            }
        }
        return LaplacianVarianceAnalyzer.analyze(
            luma = packed,
            width = width,
            height = height,
            maxAnalysisDimension = MaxAnalysisDimension,
            borderFraction = 0.05,
            algorithm = "yuvLaplacianVarianceV1",
        )
    }
}
