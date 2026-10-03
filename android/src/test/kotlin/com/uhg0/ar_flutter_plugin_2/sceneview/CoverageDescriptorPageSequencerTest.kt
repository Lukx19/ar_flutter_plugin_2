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
import org.junit.Assert.assertThrows
import org.junit.Test
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class CoverageDescriptorPageSequencerTest {
    @Test
    fun `worker publication cannot mutate renderer owned pages and hands off the latest cut`() {
        val mailbox = CoverageRendererPublicationMailbox<BoundedCoveragePresentation>()
        val sequencer = CoverageDescriptorPageSequencer()
        sequencer.replace(descriptor(count = 600))
        val active = checkNotNull(sequencer.nextPage())
        val successor = descriptor(count = 2)
        val producer = Executors.newSingleThreadExecutor()
        try {
            producer.submit {
                assertThrows(IllegalStateException::class.java) { sequencer.replace(successor) }
                assertThrows(IllegalStateException::class.java) { sequencer.clear() }
                assertThrows(IllegalStateException::class.java) { sequencer.nextPage() }
                assertThrows(IllegalStateException::class.java) { sequencer.release(active.ticket) }
                assertThrows(IllegalStateException::class.java) { sequencer.hasInFlightPage }
                assertTrue(mailbox.offer(successor))
            }.get(2, TimeUnit.SECONDS)

            // Taking the immutable cut never transfers the active upload's
            // ownership to its producer, and does not release that page.
            sequencer.replace(checkNotNull(mailbox.take()).value)
            assertNull(sequencer.nextPage())
            sequencer.release(active.ticket)
            val replacement = checkNotNull(sequencer.nextPage())
            assertEquals(2, replacement.page.count)
            assertTrue(replacement.reset)
        } finally {
            producer.shutdownNow()
        }
    }

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
        assertEquals(0, replacement.page.startSlot)
        assertTrue(replacement.reset)
        assertNotSame(inFlight.ticket, replacement.ticket)

        sequencer.release(inFlight.ticket)
        assertNull(sequencer.nextPage())
    }

    @Test
    fun `superseding an incomplete baseline starts the newest descriptor from a full reset`() {
        val baseline = descriptor(count = 1_025)
        val ordinary = descriptor(
            count = 1_025,
            update = CoveragePointRenderUpdate(
                geometryRevision = 2,
                visibilityRevision = 2,
                enabled = true,
                count = 1_025,
                spans = listOf(CoveragePointSpan(900, FloatArray(0), IntArray(0), endSlotExclusive = 901)),
                reset = false,
            ),
        )
        val sequencer = CoverageDescriptorPageSequencer()
        sequencer.replace(baseline)
        val first = checkNotNull(sequencer.nextPage())

        sequencer.replace(ordinary)
        assertNull(sequencer.nextPage())

        sequencer.release(first.ticket)
        val replacement = checkNotNull(sequencer.nextPage())
        assertEquals(0, replacement.page.startSlot)
        assertTrue(replacement.reset)
    }

    @Test
    fun `page borrow failure makes that descriptor incomplete and blocks sparse successor`() {
        val failed = descriptor(count = 4, pageAvailable = false)
        val successor = descriptor(
            count = 4,
            update = CoveragePointRenderUpdate(
                geometryRevision = 2,
                visibilityRevision = 2,
                enabled = true,
                count = 4,
                spans = listOf(CoveragePointSpan(2, FloatArray(0), IntArray(0), endSlotExclusive = 3)),
                reset = false,
            ),
        )
        val sequencer = CoverageDescriptorPageSequencer()
        sequencer.replace(failed)
        assertNull(sequencer.nextPage())

        sequencer.replace(successor)
        val reset = checkNotNull(sequencer.nextPage())
        assertEquals(0, reset.page.startSlot)
        assertTrue(reset.reset)
    }

    @Test
    fun `clear forgets completed predecessor and preserves only an already in-flight release`() {
        val baseline = descriptor(count = 4)
        val successor = descriptor(
            count = 4,
            update = CoveragePointRenderUpdate(
                geometryRevision = 2,
                visibilityRevision = 2,
                enabled = true,
                count = 4,
                spans = listOf(CoveragePointSpan(1, FloatArray(0), IntArray(0), endSlotExclusive = 2)),
                reset = false,
            ),
        )
        val sequencer = CoverageDescriptorPageSequencer()
        sequencer.replace(baseline)
        val page = checkNotNull(sequencer.nextPage())
        sequencer.release(page.ticket)
        sequencer.clear()
        sequencer.replace(successor)

        val reset = checkNotNull(sequencer.nextPage())
        assertEquals(0, reset.page.startSlot)
        assertTrue(reset.reset)
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

    @Test
    fun `page submission carries descriptor visibility`() {
        val sequencer = CoverageDescriptorPageSequencer()
        sequencer.replace(descriptor(count = 1, enabled = false))

        val submission = checkNotNull(sequencer.nextPage())
        assertFalse(submission.enabled)
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
        pageAvailable: Boolean = true,
        enabled: Boolean = true,
    ): BoundedCoveragePresentation {
        val qualifier = CoverageRowsQualifier(1, 1, 1, 1, update.geometryRevision, 1)
        val style = CoverageRendererStyleRowV1().encode()
        val styles = ByteArray(count * COVERAGE_RENDERER_STYLE_ROW_BYTES) { style[it % style.size] }
        return PresentationDescriptor.create(
            qualifier = qualifier,
            mode = mode,
            enabled = enabled,
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
                if (!pageAvailable || expected != qualifier || start >= count) {
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
