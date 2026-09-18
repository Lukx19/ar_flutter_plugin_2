package com.uhg0.ar_flutter_plugin_2.sceneview

import com.uhg0.ar_flutter_plugin_2.pointcloud.CoveragePointRenderUpdate
import com.uhg0.ar_flutter_plugin_2.pointcloud.CoveragePointSpan
import com.uhg0.ar_flutter_plugin_2.pointcloud.CoverageRendererPalette
import com.uhg0.ar_flutter_plugin_2.pointcloud.CoverageRendererStyleRowV1
import com.uhg0.ar_flutter_plugin_2.pointcloud.CoverageRowsQualifier
import com.uhg0.ar_flutter_plugin_2.pointcloud.COVERAGE_RENDERER_STYLE_ROW_BYTES
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CoverageDescriptorPageSequencerTest {
    @Test
    fun `baseline is a full reset and every page is bounded`() {
        val descriptor = descriptor(count = 1_025)
        val sequencer = CoverageDescriptorPageSequencer()

        sequencer.replace(descriptor)

        val first = checkNotNull(sequencer.nextPage())
        assertEquals(0, first.page.startSlot)
        assertEquals(512, first.page.count)
        assertEquals(true, first.reset)
        assertTrue(first.page.count <= CoverageDescriptorPageSequencer.MAX_ROWS_PER_PAGE)
        sequencer.release(first.ticket)

        val second = checkNotNull(sequencer.nextPage())
        assertEquals(512, second.page.startSlot)
        assertEquals(512, second.page.count)
        assertFalse(second.reset)
        sequencer.release(second.ticket)

        val third = checkNotNull(sequencer.nextPage())
        assertEquals(1_024, third.page.startSlot)
        assertEquals(1, third.page.count)
        assertFalse(third.reset)
    }

    @Test
    fun `ordinary updates sort merge and clip range-only spans`() {
        val initial = descriptor(count = 100)
        val ordinary = descriptor(
            count = 100,
            update = CoveragePointRenderUpdate(
                geometryRevision = 2,
                visibilityRevision = 2,
                enabled = true,
                count = 100,
                spans = listOf(
                    CoveragePointSpan(80, FloatArray(0), IntArray(0), endSlotExclusive = 130),
                    CoveragePointSpan(25, FloatArray(0), IntArray(0), endSlotExclusive = 70),
                    CoveragePointSpan(5, FloatArray(0), IntArray(0), endSlotExclusive = 20),
                    CoveragePointSpan(18, FloatArray(0), IntArray(0), endSlotExclusive = 30),
                ),
                reset = false,
            ),
        )
        val sequencer = CoverageDescriptorPageSequencer()
        sequencer.replace(initial)
        val baseline = checkNotNull(sequencer.nextPage())
        sequencer.release(baseline.ticket)

        sequencer.replace(ordinary)

        val page = checkNotNull(sequencer.nextPage())
        assertEquals(5, page.page.startSlot)
        assertEquals(65, page.page.count)
        assertFalse(page.reset)
        sequencer.release(page.ticket)
        val clipped = checkNotNull(sequencer.nextPage())
        assertEquals(80, clipped.page.startSlot)
        assertEquals(20, clipped.page.count)
        sequencer.release(clipped.ticket)
        assertNull(sequencer.nextPage())
    }

    @Test
    fun `supersession preserves active page and stale ticket cannot release replacement`() {
        val firstDescriptor = descriptor(count = 600)
        val secondDescriptor = descriptor(
            count = 600,
            update = CoveragePointRenderUpdate(
                geometryRevision = 2,
                visibilityRevision = 2,
                enabled = true,
                count = 600,
                spans = listOf(
                    CoveragePointSpan(300, FloatArray(0), IntArray(0), endSlotExclusive = 310),
                ),
                reset = false,
            ),
        )
        val sequencer = CoverageDescriptorPageSequencer()
        sequencer.replace(firstDescriptor)
        val inFlight = checkNotNull(sequencer.nextPage())

        sequencer.replace(secondDescriptor)
        assertNull(sequencer.nextPage())

        sequencer.release(inFlight.ticket)
        val replacement = checkNotNull(sequencer.nextPage())
        assertEquals(300, replacement.page.startSlot)
        assertNotSame(inFlight.ticket, replacement.ticket)

        sequencer.release(inFlight.ticket)
        assertNull(sequencer.nextPage())
    }

    @Test
    fun `mode palette reset and explicit rehydration are the only ordinary full transitions`() {
        val baseline = descriptor(count = 16)
        val ordinary = descriptor(
            count = 16,
            update = CoveragePointRenderUpdate(
                geometryRevision = 2,
                visibilityRevision = 2,
                enabled = true,
                count = 16,
                spans = listOf(CoveragePointSpan(2, FloatArray(0), IntArray(0), endSlotExclusive = 4)),
                reset = false,
            ),
        )
        val reset = descriptor(
            count = 16,
            update = ordinary.update!!.copy(reset = true),
        )
        val mode = descriptor(
            count = 16,
            update = ordinary.update,
            mode = CoveragePresentationMode.SEMANTIC_CUBES,
        )
        val palette = descriptor(
            count = 16,
            update = ordinary.update,
            palette = CoverageRendererPalette.NORMAL,
            paletteEpoch = ordinary.paletteEpoch + 1,
        )
        val sequencer = CoverageDescriptorPageSequencer()

        fun nextResetFor(descriptor: BoundedCoveragePresentation, rehydrate: Boolean = false): Boolean {
            sequencer.replace(descriptor, rehydrate = rehydrate)
            val page = checkNotNull(sequencer.nextPage())
            sequencer.release(page.ticket)
            return page.reset
        }

        assertTrue(nextResetFor(baseline))
        assertFalse(nextResetFor(ordinary))
        assertTrue(nextResetFor(reset))
        assertTrue(nextResetFor(mode))
        assertTrue(nextResetFor(palette))
        assertTrue(nextResetFor(ordinary, rehydrate = true))
    }

    private fun descriptor(
        count: Int,
        update: CoveragePointRenderUpdate = CoveragePointRenderUpdate(
            geometryRevision = 1,
            visibilityRevision = 1,
            enabled = true,
            count = count,
            spans = emptyList(),
            reset = false,
        ),
        mode: CoveragePresentationMode = CoveragePresentationMode.SEMANTIC_CENTROIDS,
        palette: CoverageRendererPalette = CoverageRendererPalette.COVERAGE,
        paletteEpoch: Long = 1,
    ): BoundedCoveragePresentation {
        val qualifier = CoverageRowsQualifier(1, 1, 1, 1, update.geometryRevision, 1)
        val style = CoverageRendererStyleRowV1().encode()
        val styles = ByteArray(count * COVERAGE_RENDERER_STYLE_ROW_BYTES) { style[it % style.size] }
        return PresentationDescriptor.create(
            qualifier = qualifier,
            mode = mode,
            enabled = true,
            capacity = mode.presentationCapacity,
            sourceCapacity = count,
            sourceCount = count,
            palette = palette,
            paletteEpoch = paletteEpoch,
            selectedSurfaceIds = LongArray(count) { it.toLong() },
            selectedSourceSlots = IntArray(count) { it },
            styleRows = styles,
            update = update,
            pageReader = { expected, start, maximum ->
                if (expected != qualifier || start >= count) {
                    null
                } else {
                    val pageCount = minOf(maximum, count - start)
                    CoveragePresentationPage(
                        startSlot = start,
                        totalCount = count,
                        surfaceIds = LongArray(pageCount) { start + it.toLong() },
                        positions = FloatArray(pageCount * 3),
                        colors = IntArray(pageCount),
                        styleRows = styles.copyOfRange(
                            start * COVERAGE_RENDERER_STYLE_ROW_BYTES,
                            (start + pageCount) * COVERAGE_RENDERER_STYLE_ROW_BYTES,
                        ),
                    )
                }
            },
        )
    }
}
