package com.uhg0.ar_flutter_plugin_2.capture

import java.nio.ByteBuffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class YuvPlaneBlurAnalyzerTest {
    @Test
    fun `reads crop row stride and pixel stride before encoding`() {
        val rowStride = 20
        val pixelStride = 2
        val bytes = ByteArray(rowStride * 8)
        for (row in 0 until 8) {
            for (column in 0 until 8) {
                bytes[row * rowStride + column * pixelStride] =
                    if ((row + column) % 2 == 0) 0 else 255.toByte()
            }
        }

        val quality =
            YuvPlaneBlurAnalyzer.analyze(
                buffer = ByteBuffer.wrap(bytes),
                rowStride = rowStride,
                pixelStride = pixelStride,
                cropLeft = 1,
                cropTop = 1,
                cropWidth = 6,
                cropHeight = 6,
            )

        assertEquals("yuvLaplacianVarianceV1", quality.algorithm)
        assertEquals(6, quality.analyzedWidth)
        assertEquals(6, quality.analyzedHeight)
        assertTrue(quality.blurScore > 0.0)
    }
}
