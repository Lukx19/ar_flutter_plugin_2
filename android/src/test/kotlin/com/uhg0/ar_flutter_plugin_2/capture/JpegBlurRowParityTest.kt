package com.uhg0.ar_flutter_plugin_2.capture
import org.junit.Assert.*
import org.junit.Test
import java.util.Collections
import java.util.IdentityHashMap

class JpegBlurRowParityTest {
    @Test fun rowWorkspacePreservesExactLegacyVarianceAndSecondarySampling() {
        for ((width, height) in listOf(32 to 24, 516 to 387, 1025 to 769)) {
            val luma = ByteArray(width * height) { offset ->
                val x = offset % width
                val y = offset / width
                ((x * 17 + y * 13) xor ((x / 7) * 91)).toByte()
            }
            val rows = Collections.newSetFromMap(IdentityHashMap<ByteArray, Boolean>())
            val actual = JpegBlurAnalyzer.analyzeSampledRows(width, height) { y, row ->
                rows.add(row)
                luma.copyInto(row, 0, y * width, (y + 1) * width)
            }
            val expected = LaplacianVarianceAnalyzer.analyze(luma, width, height, 1024, 0.05,
                "jpegLaplacianVarianceV1")
            assertEquals(expected, actual)
            assertTrue(rows.size <= 3)
        }
        assertEquals(8, JpegBlurAnalyzer.calculateInSampleSize(4128, 3096))
        assertEquals(8L * 1024L * 1024L, JpegBlurAnalyzer.WorkingMemoryBytes)
        assertEquals(110.0, JpegBlurAnalyzer.ProductionThreshold, 0.0)
    }
}
