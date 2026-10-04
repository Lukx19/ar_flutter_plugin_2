package com.uhg0.ar_flutter_plugin_2.sceneview

import com.uhg0.ar_flutter_plugin_2.pointcloud.CoveragePointRenderUpdate
import com.uhg0.ar_flutter_plugin_2.pointcloud.CoverageRendererPalette
import com.uhg0.ar_flutter_plugin_2.pointcloud.CoverageRendererGlyph
import com.uhg0.ar_flutter_plugin_2.pointcloud.CoverageRendererCoverage
import com.uhg0.ar_flutter_plugin_2.pointcloud.CoverageRendererStyleRowV1
import com.uhg0.ar_flutter_plugin_2.pointcloud.CoverageRendererTarget
import com.uhg0.ar_flutter_plugin_2.pointcloud.CoverageRowsQualifier
import com.uhg0.ar_flutter_plugin_2.pointcloud.COVERAGE_RENDERER_STYLE_ROW_BYTES
import com.uhg0.ar_flutter_plugin_2.pointcloud.COVERAGE_RENDERER_NO_DIRECTION
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CoveragePresentationDescriptorTest {
    @Test
    fun `packed glyph ranking and palette views match scalar ordering with stable old owners`() {
        val size = 800
        val ids = LongArray(size) { size - it.toLong() }
        val scalar = Array(size) { index -> CoverageRendererStyleRowV1(
            target = com.uhg0.ar_flutter_plugin_2.pointcloud.CoverageRendererTarget.entries[index % 3],
            coverage = CoverageRendererCoverage.entries[index / 3 % 3],
            residency = com.uhg0.ar_flutter_plugin_2.pointcloud.CoverageRendererResidency.entries[index / 9 % 3],
            glyph = CoverageRendererGlyph.VIEW_ROSE,
            directionBin = index % 24,
        ) }
        val ranked = (0 until size).sortedWith { a, b ->
            compareCoverageStylePriority(scalar[a], ids[a], scalar[b], ids[b])
        }.take(CoverageRendererLimits.GLYPH_CAPACITY).toSet()
        val workspace = PresentationWorkspace(size)
        fun descriptor(generation: Long): PresentationDescriptor = PresentationDescriptor.createOwned(
            qualifier = CoverageRowsQualifier(1, 1, 0, generation, generation, generation),
            mode = CoveragePresentationMode.SEMANTIC_CENTROIDS, enabled = true,
            capacity = CoverageRendererLimits.CENTROID_CAPACITY, sourceCapacity = size, sourceCount = size,
            palette = CoverageRendererPalette.COVERAGE, paletteEpoch = 0,
            selectedSurfaceIds = ids.copyOf(), selectedSourceSlots = IntArray(size) { it },
            styleRows = ByteArray(size * COVERAGE_RENDERER_STYLE_ROW_BYTES).also { rows ->
                scalar.forEachIndexed { index, row -> row.encodeInto(rows, index * COVERAGE_RENDERER_STYLE_ROW_BYTES) }
            }, update = null, workspace = workspace,
            pageReader = PresentationPageReader { _, start, count, mapping, page ->
                repeat(count) { index -> page.surfaceIds[index] = mapping.surfaceIdAt(start + index) }
                true
            },
        )
        val first = descriptor(1)
        val oldStyles = first.styleRows
        val scratch = listOf("ids", "slots", "heap").map { name ->
            PresentationWorkspace::class.java.getDeclaredField(name).apply { isAccessible = true }.get(workspace)
        }
        repeat(4) { descriptor(it + 2L) }
        listOf("ids", "slots", "heap").forEachIndexed { index, name ->
            org.junit.Assert.assertSame(scratch[index], PresentationWorkspace::class.java.getDeclaredField(name)
                .apply { isAccessible = true }.get(workspace))
        }
        org.junit.Assert.assertArrayEquals(oldStyles, first.styleRows)
        val paletteRows = CoverageRendererPalette.entries.associateWith { palette ->
            first.withControls(first.mode, palette = palette).styleRows
        }
        repeat(size) { index ->
            val row = CoverageRendererStyleRowV1.decode(oldStyles, index * COVERAGE_RENDERER_STYLE_ROW_BYTES)
            assertEquals(index in ranked, row.glyph != CoverageRendererGlyph.NONE)
            CoverageRendererPalette.entries.forEach { palette ->
                val recolored = checkNotNull(paletteRows[palette])
                org.junit.Assert.assertArrayEquals(row.copy(palette = palette).encode(),
                    recolored.copyOfRange(index * COVERAGE_RENDERER_STYLE_ROW_BYTES,
                        (index + 1) * COVERAGE_RENDERER_STYLE_ROW_BYTES))
            }
        }
        val destination = CoveragePresentationPage.allocate(512)
        assertTrue(first.withPageInto(first.qualifier, 799, 512, destination))
        assertEquals(1, destination.count)
        assertEquals(ids[799], destination.surfaceIds[0])
        assertFalse(first.withPageInto(first.qualifier.copy(styleRevision = 99), 0, 512, destination))
        assertEquals(0, destination.count)
    }

    @Test
    fun `scalar glyph and color reads preserve all decoder validation including malformed none rows`() {
        val original = CoverageRendererStyleRowV1().encode()
        for (palette in CoverageRendererPalette.entries) {
            val baseline = CoverageRendererStyleRowV1(palette = palette).encode()
            for (offset in baseline.indices) for (value in 0..255) {
                val bytes = baseline.copyOf().also { it[offset] = value.toByte() }
                val decoded = runCatching { CoverageRendererStyleRowV1.decode(bytes) }
                val scalar = runCatching { CoverageRendererStyleRowV1.validatedGlyph(bytes) }
                val color = runCatching { CoverageRendererStyleRowV1.validatedPackedColor(bytes) }
                assertEquals("palette=$palette offset=$offset value=$value", decoded.isSuccess, scalar.isSuccess)
                assertEquals(decoded.isSuccess, color.isSuccess)
                if (decoded.isSuccess) {
                    assertEquals(decoded.getOrThrow().glyph, scalar.getOrThrow())
                    assertEquals(decoded.getOrThrow().packedColor(), color.getOrThrow())
                }
            }
        }
        val malformedNone = original.copyOf().also { it[5] = 1 }
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
            PresentationDescriptor.create(
                qualifier = CoverageRowsQualifier(1, 1, 1, 1, 1, 1),
                mode = CoveragePresentationMode.SEMANTIC_CENTROIDS,
                enabled = true, capacity = 1, sourceCapacity = 1, sourceCount = 1,
                palette = CoverageRendererPalette.COVERAGE, paletteEpoch = 0,
                selectedSurfaceIds = longArrayOf(1), selectedSourceSlots = intArrayOf(0),
                styleRows = malformedNone, update = null, pageReader = { _, _, _ -> null },
            )
        }
    }

    @Test
    fun `glyph presentation keeps the highest priority 256 committed candidates`() {
        val qualifier = CoverageRowsQualifier(2, 3, 0, 4, 5, 6)
        val ids = LongArray(300) { it + 1L }
        val slots = IntArray(ids.size) { it }
        val committedStyles = ByteArray(ids.size * COVERAGE_RENDERER_STYLE_ROW_BYTES)
        repeat(ids.size) { index ->
            CoverageRendererStyleRowV1(
                glyph = if (index == ids.lastIndex) CoverageRendererGlyph.DESIRED_DIRECTION else CoverageRendererGlyph.VIEW_ROSE,
                directionBin = index % 24,
                target = if (index == ids.lastIndex) CoverageRendererTarget.PRIMARY else CoverageRendererTarget.NONE,
                coverage = if (index == ids.lastIndex - 1) CoverageRendererCoverage.UNCOVERED else CoverageRendererCoverage.COMPLETE,
            ).encode().copyInto(committedStyles, index * COVERAGE_RENDERER_STYLE_ROW_BYTES)
        }
        val descriptor = PresentationDescriptor.create(
            qualifier = qualifier,
            mode = CoveragePresentationMode.SEMANTIC_CENTROIDS,
            enabled = true,
            capacity = CoverageRendererLimits.CENTROID_CAPACITY,
            sourceCapacity = ids.size,
            sourceCount = ids.size,
            palette = CoverageRendererPalette.COVERAGE,
            paletteEpoch = 0L,
            selectedSurfaceIds = ids,
            selectedSourceSlots = slots,
            styleRows = committedStyles,
            update = null,
            pageReader = { expected, start, maximum ->
                if (expected != qualifier) null else {
                    val count = minOf(maximum, ids.size - start)
                    CoveragePresentationPage(
                        start, ids.size, ids.copyOfRange(start, start + count),
                        FloatArray(count * 3), IntArray(count),
                        committedStyles.copyOfRange(
                            start * COVERAGE_RENDERER_STYLE_ROW_BYTES,
                            (start + count) * COVERAGE_RENDERER_STYLE_ROW_BYTES,
                        ),
                    )
                }
            },
        )

        assertEquals(ids.toList(), descriptor.selectedSurfaceIds.toList())
        assertEquals(CoverageRendererLimits.GLYPH_CAPACITY, descriptor.glyphCount)
        assertEquals(CoverageRendererGlyph.NONE, CoverageRendererStyleRowV1.decode(descriptor.styleRows, 255 * COVERAGE_RENDERER_STYLE_ROW_BYTES).glyph)
        assertEquals(COVERAGE_RENDERER_NO_DIRECTION, CoverageRendererStyleRowV1.decode(descriptor.styleRows, 255 * COVERAGE_RENDERER_STYLE_ROW_BYTES).directionBin)
        assertEquals(CoverageRendererGlyph.VIEW_ROSE, CoverageRendererStyleRowV1.decode(descriptor.styleRows, 298 * COVERAGE_RENDERER_STYLE_ROW_BYTES).glyph)
        assertEquals(CoverageRendererGlyph.DESIRED_DIRECTION, CoverageRendererStyleRowV1.decode(descriptor.styleRows, 299 * COVERAGE_RENDERER_STYLE_ROW_BYTES).glyph)
        assertEquals(CoverageRendererGlyph.VIEW_ROSE, CoverageRendererStyleRowV1.decode(committedStyles, 255 * COVERAGE_RENDERER_STYLE_ROW_BYTES).glyph)
        assertEquals(255 % 24, CoverageRendererStyleRowV1.decode(committedStyles, 255 * COVERAGE_RENDERER_STYLE_ROW_BYTES).directionBin)
        assertTrue(descriptor.withPage(qualifier, 0, 300) { page ->
            val visibleGlyphs = (0 until page.count).count { index ->
                CoverageRendererStyleRowV1.decode(page.styleRows, index * COVERAGE_RENDERER_STYLE_ROW_BYTES).glyph != CoverageRendererGlyph.NONE
            }
            assertEquals(CoverageRendererLimits.GLYPH_CAPACITY, visibleGlyphs)
        })
        val raw = descriptor.withControls(CoveragePresentationMode.RAW_FEATURES)
        val cube = raw.withControls(CoveragePresentationMode.SEMANTIC_CUBES)
        assertEquals(ids.size, raw.count)
        assertEquals(CoverageRendererLimits.GLYPH_CAPACITY, raw.glyphCount)
        assertEquals(CoverageRendererLimits.GLYPH_CAPACITY, cube.glyphCount)
    }

    @Test
    fun descriptorDefensivelyOwnsBoundedIdentityAndStyleMetadataAndBorrowsPages() {
        val qualifier = CoverageRowsQualifier(2, 3, 0, 4, 5, 6)
        val ids = LongArray(10_001) { it + 1L }
        val slots = IntArray(10_001) { it }
        val encodedStyle = CoverageRendererStyleRowV1().encode()
        val styles = ByteArray(ids.size * COVERAGE_RENDERER_STYLE_ROW_BYTES) {
            encodedStyle[it % COVERAGE_RENDERER_STYLE_ROW_BYTES]
        }
        val descriptor = PresentationDescriptor.create(
            qualifier = qualifier,
            mode = CoveragePresentationMode.SEMANTIC_CENTROIDS,
            enabled = true,
            capacity = CoveragePresentationMode.SEMANTIC_CENTROIDS.presentationCapacity,
            sourceCapacity = 100_000,
            sourceCount = ids.size,
            palette = CoverageRendererPalette.COVERAGE,
            paletteEpoch = 1L,
            selectedSurfaceIds = ids,
            selectedSourceSlots = slots,
            styleRows = styles,
            update = CoveragePointRenderUpdate(5, 6, true, ids.size, emptyList(), true),
            pageReader = { expected, start, maximum ->
                if (expected != qualifier) {
                    null
                } else {
                    val count = minOf(maximum, ids.size - start)
                    if (count <= 0) null else CoveragePresentationPage(
                        startSlot = start,
                        totalCount = ids.size,
                        surfaceIds = ids.copyOfRange(start, start + count),
                        positions = FloatArray(count * 3) { index -> (start + index / 3).toFloat() },
                        colors = IntArray(count) { 0xff00ff00.toInt() },
                        styleRows = styles.copyOfRange(
                            start * COVERAGE_RENDERER_STYLE_ROW_BYTES,
                            (start + count) * COVERAGE_RENDERER_STYLE_ROW_BYTES,
                        ),
                    )
                }
            },
        )
        val exposedIds = descriptor.selectedSurfaceIds
        exposedIds[0] = 99_999L
        assertNotEquals(99_999L, descriptor.selectedSurfaceIds[0])
        var pageCount = 0
        assertTrue(descriptor.withPage(qualifier, 0, 512) { page -> pageCount = page.count })
        assertEquals(512, pageCount)
        assertFalse(descriptor.withPage(qualifier.copy(styleRevision = 7), 0, 512) { })

        val cube = descriptor.withControls(CoveragePresentationMode.SEMANTIC_CUBES)
        assertEquals(8_000, cube.count)

        val restored = cube.withControls(CoveragePresentationMode.SEMANTIC_CENTROIDS)
        assertEquals(10_001, restored.count)
        var restoredPageId = -1L
        assertTrue(restored.withPage(qualifier, 9_999, 2) { page ->
            restoredPageId = page.surfaceIds.first()
        })
        assertEquals(10_000L, restoredPageId)

        assertEquals(descriptor.glyphCount, cube.glyphCount)

        val hiddenNormal = restored.withControls(
            mode = CoveragePresentationMode.SEMANTIC_CENTROIDS,
            enabled = false,
            palette = CoverageRendererPalette.NORMAL,
            paletteEpoch = 2L,
        )
        assertFalse(hiddenNormal.enabled)
        assertEquals(
            CoverageRendererPalette.NORMAL,
            CoverageRendererStyleRowV1.decode(hiddenNormal.styleRows, 0).palette,
        )
        assertTrue(hiddenNormal.withPage(qualifier, 0, 1) { page ->
            assertEquals(
                CoverageRendererPalette.NORMAL,
                CoverageRendererStyleRowV1.decode(page.styleRows, 0).palette,
            )
        })
    }
}
