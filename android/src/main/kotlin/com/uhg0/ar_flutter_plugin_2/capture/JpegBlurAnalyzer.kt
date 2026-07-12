package com.uhg0.ar_flutter_plugin_2.capture

import android.graphics.BitmapFactory

internal object JpegBlurAnalyzer {
    private const val MaxAnalysisDimension = 1024

    fun analyzeLaplacianVariance(
        jpegBytes: ByteArray,
    ): PreviewBlurQuality {
        val bounds =
            BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }
        BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            throw CaptureSessionException(
                code = "QUALITY_ANALYSIS_FAILED",
                message = "Unable to decode JPEG bounds for blur analysis",
            )
        }
        val sampledDecodeOptions =
            BitmapFactory.Options().apply {
                inSampleSize = calculateInSampleSize(bounds.outWidth, bounds.outHeight)
            }
        val bitmap =
            BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size, sampledDecodeOptions)
                ?: throw CaptureSessionException(
                    code = "QUALITY_ANALYSIS_FAILED",
                    message = "Unable to decode JPEG bytes for blur analysis",
                )

        val width = bitmap.width
        val height = bitmap.height
        require(width > 2) { "width must be greater than 2" }
        require(height > 2) { "height must be greater than 2" }

        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        val luma =
            ByteArray(width * height) { index ->
                val pixel = pixels[index]
                val red = (pixel shr 16) and 0xFF
                val green = (pixel shr 8) and 0xFF
                val blue = pixel and 0xFF
                ((red * 2126 + green * 7152 + blue * 722) / 10000).toByte()
            }
        bitmap.recycle()

        return LaplacianVarianceAnalyzer.analyze(
            luma = luma,
            width = width,
            height = height,
            maxAnalysisDimension = MaxAnalysisDimension,
            borderFraction = 0.05,
            algorithm = "jpegLaplacianVarianceV1",
        )
    }

    internal fun calculateInSampleSize(
        width: Int,
        height: Int,
    ): Int {
        require(width > 0) { "width must be greater than 0" }
        require(height > 0) { "height must be greater than 0" }

        val longEdge = maxOf(width, height)
        var inSampleSize = 1
        while (longEdge / inSampleSize > MaxAnalysisDimension) {
            inSampleSize *= 2
        }
        return inSampleSize
    }
}
