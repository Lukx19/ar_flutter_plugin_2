package com.uhg0.ar_flutter_plugin_2.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class JpegBlurAnalyzerTest {
    @Test
    fun `power-of-two jpeg sampling keeps long edge within analysis budget`() {
        assertEquals(1, JpegBlurAnalyzer.calculateInSampleSize(width = 1024, height = 768))
        assertEquals(2, JpegBlurAnalyzer.calculateInSampleSize(width = 1600, height = 1200))
        assertEquals(4, JpegBlurAnalyzer.calculateInSampleSize(width = 4000, height = 3000))
        assertEquals(4, JpegBlurAnalyzer.calculateInSampleSize(width = 2050, height = 1538))
    }

    @Test
    fun `invalid jpeg bytes fail with quality analysis error`() {
        try {
            JpegBlurAnalyzer.analyzeLaplacianVariance(byteArrayOf(1, 2, 3, 4))
        } catch (error: CaptureSessionException) {
            assertEquals("QUALITY_ANALYSIS_FAILED", error.code)
            assertTrue(error.message!!.contains("decode JPEG"))
            return
        }

        throw AssertionError("Expected QUALITY_ANALYSIS_FAILED for invalid JPEG bytes")
    }
}
