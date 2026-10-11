package com.uhg0.ar_flutter_plugin_2.capture

import android.graphics.BitmapFactory
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.util.concurrent.CancellationException

/** Production hardware JPEG policy; never applied to preview YUV or re-encoded output. */
internal object JpegBlurAnalyzer {
    private const val MaxAnalysisDimension = 1024
    const val ProductionThreshold = 110.0
    const val WorkingMemoryBytes = 8L * 1024L * 1024L

    fun analyzeLaplacianVariance(jpegBytes: ByteArray): PreviewBlurQuality =
        analyzeLaplacianVariance({ ByteArrayInputStream(jpegBytes) })

    /** The factory returns independent views of the same original JPEG for both decodes. */
    fun analyzeLaplacianVariance(
        openJpeg: () -> InputStream,
        cancelled: () -> Boolean = { false },
    ): PreviewBlurQuality {
        fun checkActive() {
            if (cancelled() || Thread.currentThread().isInterrupted) throw CancellationException("JPEG analysis cancelled")
        }
        checkActive()
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        openJpeg().use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            throw CaptureSessionException("QUALITY_ANALYSIS_FAILED", "Unable to decode JPEG bounds for blur analysis")
        }
        checkActive()
        val options = BitmapFactory.Options().apply {
            inSampleSize = calculateInSampleSize(bounds.outWidth, bounds.outHeight)
        }
        val bitmap = openJpeg().use { BitmapFactory.decodeStream(it, null, options) }
            ?: throw CaptureSessionException("QUALITY_ANALYSIS_FAILED", "Unable to decode JPEG bytes for blur analysis")
        try {
            checkActive()
            val width = bitmap.width
            val height = bitmap.height
            // JPEG sampled decode may round an odd bound up by one pixel.
            require(width in 3..MaxAnalysisDimension + 1 && height in 3..MaxAnalysisDimension + 1)
            val pixels = IntArray(width)
            return analyzeSampledRows(width, height, cancelled) { y, row ->
                bitmap.getPixels(pixels, 0, width, 0, y, width, 1)
                for (x in 0 until width) {
                    val pixel = pixels[x]
                    val red = (pixel shr 16) and 0xff
                    val green = (pixel shr 8) and 0xff
                    val blue = pixel and 0xff
                    row[x] = ((red * 2126 + green * 7152 + blue * 722) / 10000).toByte()
                }
            }
        } finally {
            bitmap.recycle()
        }
    }

    /** Exact legacy integer luminance, sampling, border and accumulation order with three rows. */
    internal fun analyzeSampledRows(
        width: Int,
        height: Int,
        cancelled: () -> Boolean = { false },
        loadRow: (Int, ByteArray) -> Unit,
    ): PreviewBlurQuality {
        require(width > 2 && height > 2)
        val step = if (maxOf(width, height) <= MaxAnalysisDimension) 1
            else (maxOf(width, height) + MaxAnalysisDimension - 1) / MaxAnalysisDimension
        val sampledWidth = width / step
        val sampledHeight = height / step
        require(sampledWidth > 2 && sampledHeight > 2)
        val borderX = (sampledWidth * 0.05).toInt().coerceAtMost((sampledWidth - 3) / 2)
        val borderY = (sampledHeight * 0.05).toInt().coerceAtMost((sampledHeight - 3) / 2)
        val startX = borderX + 1
        val endX = sampledWidth - borderX - 1
        val startY = borderY + 1
        val endY = sampledHeight - borderY - 1
        require(startX < endX && startY < endY)
        var top = ByteArray(width)
        var current = ByteArray(width)
        var bottom = ByteArray(width)
        loadRow((startY - 1) * step, top)
        loadRow(startY * step, current)
        var sum = 0.0
        var sumSquares = 0.0
        var count = 0
        for (y in startY until endY) {
            if (cancelled() || Thread.currentThread().isInterrupted) throw CancellationException("JPEG analysis cancelled")
            loadRow((y + 1) * step, bottom)
            for (x in startX until endX) {
                val sx = x * step
                val laplacian = (-4 * (current[sx].toInt() and 0xff) +
                    (current[sx - step].toInt() and 0xff) + (current[sx + step].toInt() and 0xff) +
                    (top[sx].toInt() and 0xff) + (bottom[sx].toInt() and 0xff)).toDouble()
                sum += laplacian
                sumSquares += laplacian * laplacian
                count++
            }
            val reusable = top
            top = current
            current = bottom
            bottom = reusable
        }
        val mean = sum / count
        return PreviewBlurQuality((sumSquares / count - mean * mean).coerceAtLeast(0.0),
            sampledWidth, sampledHeight, "jpegLaplacianVarianceV1")
    }

    internal fun calculateInSampleSize(width: Int, height: Int): Int {
        require(width > 0 && height > 0)
        val longEdge = maxOf(width, height)
        var sample = 1
        while (longEdge / sample > MaxAnalysisDimension) sample *= 2
        return sample
    }
}
