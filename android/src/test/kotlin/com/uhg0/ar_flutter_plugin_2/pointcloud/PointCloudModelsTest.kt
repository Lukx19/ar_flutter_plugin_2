package com.uhg0.ar_flutter_plugin_2.pointcloud

import org.junit.Assert.assertEquals
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class PointCloudModelsTest {
    @Test
    fun `palette rewrite centralizes top-level and populated span buffers`() {
        val first = CoverageRendererStyleRowV1(
            coverage = CoverageRendererCoverage.UNCOVERED,
            palette = CoverageRendererPalette.COVERAGE,
        )
        val second = CoverageRendererStyleRowV1(
            coverage = CoverageRendererCoverage.COMPLETE,
            palette = CoverageRendererPalette.COVERAGE,
        )
        val firstBytes = first.encode()
        val secondBytes = second.encode()
        val source = CoveragePointRenderSnapshot(
            revision = 1L,
            enabled = true,
            capacity = 2,
            count = 2,
            keys = longArrayOf(1L, 2L),
            positions = FloatArray(6),
            colors = intArrayOf(first.packedColor(), second.packedColor()),
            styleRows = firstBytes + secondBytes,
            update = CoveragePointRenderUpdate(
                geometryRevision = 1L,
                visibilityRevision = 1L,
                enabled = true,
                count = 2,
                spans = listOf(
                    CoveragePointSpan(
                        startSlot = 1,
                        positions = FloatArray(3),
                        colors = intArrayOf(second.packedColor()),
                        styleRows = secondBytes,
                    ),
                ),
                reset = false,
            ),
        )

        val recolored = source.rewritePaletteBuffers(CoverageRendererPalette.NORMAL)

        assertEquals(
            CoverageRendererPalette.COVERAGE,
            CoverageRendererStyleRowV1.decode(source.styleRows).palette,
        )
        assertEquals(
            CoverageRendererPalette.NORMAL,
            CoverageRendererStyleRowV1.decode(recolored.styleRows).palette,
        )
        assertEquals(
            CoverageRendererStyleRowV1(coverage = CoverageRendererCoverage.UNCOVERED, palette = CoverageRendererPalette.NORMAL)
                .packedColor(),
            recolored.colors[0],
        )
        val span = recolored.update!!.spans.single()
        assertEquals(
            CoverageRendererPalette.NORMAL,
            CoverageRendererStyleRowV1.decode(span.styleRows).palette,
        )
        assertEquals(
            CoverageRendererStyleRowV1(coverage = CoverageRendererCoverage.COMPLETE, palette = CoverageRendererPalette.NORMAL)
                .packedColor(),
            span.colors[0],
        )
        assertNotSame(source.styleRows, recolored.styleRows)
        assertNotSame(source.update!!.spans.single().styleRows, span.styleRows)
    }

    @Test
    fun `palette rewrite preserves range-only spans`() {
        val source = CoveragePointRenderSnapshot(
            revision = 1L,
            enabled = true,
            capacity = 1,
            count = 1,
            keys = longArrayOf(1L),
            positions = FloatArray(3),
            colors = intArrayOf(0xff00ff00.toInt()),
            styleRows = CoverageRendererStyleRowV1().encode(),
            update = CoveragePointRenderUpdate(
                geometryRevision = 1L,
                visibilityRevision = 1L,
                enabled = true,
                count = 1,
                spans = listOf(
                    CoveragePointSpan(
                        startSlot = 0,
                        positions = FloatArray(0),
                        colors = IntArray(0),
                        endSlotExclusive = 1,
                    ),
                ),
                reset = false,
            ),
        )

        val recolored = source.rewritePaletteBuffers(CoverageRendererPalette.NORMAL)
        val recoloredUpdate = checkNotNull(recolored.update)
        val span = recoloredUpdate.spans.single()
        assertTrue(span.positions.isEmpty())
        assertTrue(span.colors.isEmpty())
        assertTrue(span.styleRows.isEmpty())
        assertFalse(recoloredUpdate.reset)
    }

    @Test
    fun `config validates renderer resource bounds`() {
        assertFails { PointCloudNativeConfig(renderCapacity = 0) }
        assertFails { PointCloudNativeConfig(voxelSizeMeters = 0f) }
        assertFails { PointCloudNativeConfig(cubeSizeFactor = 0f) }
        assertFails { PointCloudNativeConfig(cubeSizeFactor = 1.1f) }
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
    fun `renderer style row matches the independent little endian wire vector`() {
        val wire = byteArrayOf(
            1,
            0x95.toByte(),
            0x48.toByte(),
            0x1f.toByte(),
            23,
            0,
            0xff.toByte(),
            0xff.toByte(),
            0xff.toByte(),
            0xff.toByte(),
            0xff.toByte(),
            0xff.toByte(),
            17,
            0,
            0,
            0,
        )

        assertEquals(
            CoverageRendererStyleRowV1(
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
            ),
            CoverageRendererStyleRowV1.decode(wire),
        )
    }

    @Test
    fun `renderer style row writes into a caller owned buffer`() {
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
        val destination = ByteArray(COVERAGE_RENDERER_STYLE_ROW_BYTES + 2) { 0x5a.toByte() }

        row.encodeInto(destination, 1)

        assertArrayEquals(row.encode(), destination.copyOfRange(1, destination.size - 1))
        assertEquals(0x5a.toByte(), destination.first())
        assertEquals(0x5a.toByte(), destination.last())
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
