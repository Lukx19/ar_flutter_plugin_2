package com.uhg0.ar_flutter_plugin_2.sceneview

import com.uhg0.ar_flutter_plugin_2.pointcloud.CoveragePointRenderUpdate
import com.uhg0.ar_flutter_plugin_2.pointcloud.CoverageRendererPalette
import com.uhg0.ar_flutter_plugin_2.pointcloud.CoverageRendererStyleRowV1
import com.uhg0.ar_flutter_plugin_2.pointcloud.CoverageRowsQualifier
import com.uhg0.ar_flutter_plugin_2.pointcloud.COVERAGE_RENDERER_STYLE_ROW_BYTES
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CoveragePresentationDescriptorTest {
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

        val cube = descriptor.forMode(CoveragePresentationMode.SEMANTIC_CUBES)
        assertEquals(8_000, cube.count)
    }
}
