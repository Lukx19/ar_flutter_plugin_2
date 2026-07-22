package com.uhg0.ar_flutter_plugin_2.capture

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RawPlaneBlurAnalyzerTest {
    @Test
    fun `same Bayer phase detail scores above a flat raw plane`() {
        val width = 64
        val height = 48
        val flat = raw16(width, height) { _, _ -> 2048 }
        val detailed = raw16(width, height) { x, y ->
            if ((x / 2 + y / 2) % 2 == 0) 512 else 3584
        }

        val flatResult = analyze(flat, width, height)
        val detailedResult = analyze(detailed, width, height)

        assertEquals("rawBayerPhaseLaplacianVarianceV1", detailedResult.algorithm)
        assertTrue(detailedResult.blurScore > flatResult.blurScore)
        assertTrue(detailedResult.analyzedWidth > 2)
        assertTrue(detailedResult.analyzedHeight > 2)
    }

    private fun analyze(buffer: ByteBuffer, width: Int, height: Int) =
        RawPlaneBlurAnalyzer.analyze(
            buffer = buffer,
            rowStride = width * 2,
            pixelStride = 2,
            width = width,
            height = height,
        )

    private fun raw16(
        width: Int,
        height: Int,
        value: (Int, Int) -> Int,
    ): ByteBuffer =
        ByteBuffer.allocate(width * height * 2).order(ByteOrder.nativeOrder()).apply {
            for (y in 0 until height) {
                for (x in 0 until width) {
                    putShort(value(x, y).toShort())
                }
            }
            flip()
        }
}
