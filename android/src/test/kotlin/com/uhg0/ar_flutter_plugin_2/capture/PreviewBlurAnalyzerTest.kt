package com.uhg0.ar_flutter_plugin_2.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PreviewBlurAnalyzerTest {
    @Test
    fun `laplacian variance is higher for a detailed pattern than flat image`() {
        val width = 32
        val height = 32
        val flat = ByteArray(width * height) { 127.toByte() }
        val checker = ByteArray(width * height) { index ->
            val x = index % width
            val y = index / width
            if ((x + y) % 2 == 0) 0.toByte() else 255.toByte()
        }

        val flatQuality = PreviewBlurAnalyzer.analyzeLaplacianVariance(
            luma = flat,
            width = width,
            height = height,
        )
        val checkerQuality = PreviewBlurAnalyzer.analyzeLaplacianVariance(
            luma = checker,
            width = width,
            height = height,
        )

        assertEquals("previewLaplacianVarianceV1", flatQuality.algorithm)
        assertEquals(width, flatQuality.analyzedWidth)
        assertEquals(height, flatQuality.analyzedHeight)
        assertEquals(0.0, flatQuality.blurScore, 0.0001)
        assertTrue(checkerQuality.blurScore > flatQuality.blurScore)
    }
}
