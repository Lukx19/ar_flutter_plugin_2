package com.uhg0.ar_flutter_plugin_2.pointcloud

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertThrows
import org.junit.Assert.fail
import org.junit.Test

class PointCloudModelsTest {
    @Test
    fun `config validates protocol and resource bounds`() {
        assertFails { PointCloudNativeConfig(wireVersion = "v2") }
        assertFails { PointCloudNativeConfig(renderCapacity = 0) }
        assertFails { PointCloudNativeConfig(minConfidence = 1.1f) }
        assertFails { PointCloudNativeConfig(frameRateHz = 0) }
        assertFails { PointCloudNativeConfig(voxelSizeMeters = 0f) }
        assertFails { PointCloudNativeConfig(cubeSizeFactor = 0f) }
        assertFails { PointCloudNativeConfig(cubeSizeFactor = 1.1f) }
    }

    @Test
    fun `synthetic source is deterministic and returns independent arrays`() {
        val fixture = SyntheticPointCloudFixture(
            ids = intArrayOf(7),
            points = floatArrayOf(1f, 2f, 3f, 0.9f),
            timestampStepNs = 25,
        )
        val source = SyntheticPointCloudSource(fixture)
        val first = source.acquire(null)
        val second = source.acquire(null)
        assertEquals(0, first.sequence)
        assertEquals(0, first.timestampNs)
        assertEquals(1, second.sequence)
        assertEquals(25, second.timestampNs)
        assertArrayEquals(first.ids, second.ids)
        assertArrayEquals(first.points, second.points, 0f)
        assertNotSame(first.ids, second.ids)
        assertNotSame(first.points, second.points)
    }

    @Test
    fun `renderer style row round trips every bounded semantic field`() {
        val row = CoverageRendererStyleRowV1(
            semanticGeneration = 0xffff_ffffL,
            styleGeneration = 17,
            semantic = CoverageRendererSemantic.AMBIGUOUS,
            coverage = CoverageRendererCoverage.PARTIAL,
            palette = CoverageRendererPalette.DIRECTION,
            cut = CoverageRendererCut.INDETERMINATE_HISTORY,
            residency = CoverageRendererResidency.WARM_L1,
            target = CoverageRendererTarget.HALO,
            directionBin = 23,
            glyph = CoverageRendererGlyph.VIEW_ROSE,
            lineageCount = 0xffff,
            age = CoverageRendererAge.OLD,
            sourceHealth = CoverageRendererSourceHealth.FEATURE_ONLY,
        )

        assertEquals(row, CoverageRendererStyleRowV1.decode(row.encode()))
        assertEquals(COVERAGE_RENDERER_STYLE_ROW_BYTES, row.encode().size)
    }

    @Test
    fun `renderer style row rejects malformed and reserved inputs`() {
        assertThrows(IllegalArgumentException::class.java) {
            CoverageRendererStyleRowV1(semanticGeneration = 0x1_0000_0000L)
        }
        assertThrows(IllegalArgumentException::class.java) {
            CoverageRendererStyleRowV1(
                directionBin = 24,
                glyph = CoverageRendererGlyph.DESIRED_DIRECTION,
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            CoverageRendererStyleRowV1(
                directionBin = 0,
                glyph = CoverageRendererGlyph.NONE,
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            CoverageRendererStyleRowV1.decode(ByteArray(COVERAGE_RENDERER_STYLE_ROW_BYTES))
        }
        val reserved = CoverageRendererStyleRowV1().encode().also { it[5] = 1 }
        assertThrows(IllegalArgumentException::class.java) {
            CoverageRendererStyleRowV1.decode(reserved)
        }
    }

    private fun assertFails(block: () -> Unit) {
        try {
            block()
            fail("Expected IllegalArgumentException")
        } catch (_: IllegalArgumentException) {
            // Expected.
        }
    }
}
