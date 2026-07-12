package com.uhg0.ar_flutter_plugin_2.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LaplacianVarianceAnalyzerTest {
    @Test
    fun `border exclusion ignores edge detail outside the central analysis window`() {
        val width = 100
        val height = 100
        val luma = ByteArray(width * height) { 127.toByte() }

        for (y in 0 until height) {
            for (x in 0 until width) {
                val isInIgnoredBorder =
                    x < 5 || x >= width - 5 || y < 5 || y >= height - 5
                if (isInIgnoredBorder) {
                    luma[(y * width) + x] = if ((x + y) % 2 == 0) 0.toByte() else 255.toByte()
                }
            }
        }

        val noBorderQuality =
            LaplacianVarianceAnalyzer.analyze(
                luma = luma,
                width = width,
                height = height,
                maxAnalysisDimension = 1024,
                borderFraction = 0.0,
                algorithm = "noBorder",
            )
        val borderExcludedQuality =
            LaplacianVarianceAnalyzer.analyze(
                luma = luma,
                width = width,
                height = height,
                maxAnalysisDimension = 1024,
                borderFraction = 0.05,
                algorithm = "withBorder",
            )

        assertTrue(noBorderQuality.blurScore > 0.0)
        assertEquals(0.0, borderExcludedQuality.blurScore, 0.0001)
    }

    @Test
    fun `analysis budget clamps the reported sampled dimensions`() {
        val width = 4000
        val height = 2000
        val luma = ByteArray(width * height) { 127.toByte() }

        val quality =
            LaplacianVarianceAnalyzer.analyze(
                luma = luma,
                width = width,
                height = height,
                maxAnalysisDimension = 1024,
                borderFraction = 0.05,
                algorithm = "budgeted",
            )

        assertTrue(quality.analyzedWidth <= 1024)
        assertTrue(quality.analyzedHeight <= 1024)
        assertEquals("budgeted", quality.algorithm)
    }
}
