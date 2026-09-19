package com.uhg0.ar_flutter_plugin_2.sceneview

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CombinedRendererPressureTest {
    @Test
    fun `renderer transition upload churn and page queue stay within exact bounds`() {
        val telemetry = RendererTelemetry()
        telemetry.setOwnedBufferBytes(
            "active-generation",
            CoverageRendererLimits.ACTIVE_RENDERER_OWNED_LIMIT_BYTES,
        )
        telemetry.setOwnedBufferBytes(
            "transition-generation",
            CoverageRendererLimits.TRANSITION_HEADROOM_BYTES,
        )
        telemetry.beginRendererFrame()
        telemetry.recordUpload(RendererTelemetry.ORDINARY_UPLOAD_LIMIT_BYTES)
        telemetry.recordUploadCallback()
        telemetry.recordUploadCompletion(1)
        telemetry.recordSelectionChurn(changedRows = 19, residentRows = 1_000)
        repeat(2) {
            telemetry.recordResourceReplacement()
            telemetry.recordResourceDisposal()
        }

        val scheduled = mutableListOf<() -> Unit>()
        val scheduler = RendererPageFrameScheduler(scheduled::add)
        repeat(128) { scheduler.request {} }
        assertEquals(1, scheduled.size)
        scheduled.removeAt(0).invoke()
        scheduler.request {}
        scheduler.cancel()

        val renderer = telemetry.pressureSnapshot()
        val pages = scheduler.pressureSnapshot()
        assertEquals(14 * 1024 * 1024, CoverageRendererLimits.ACTIVE_RENDERER_OWNED_LIMIT_BYTES)
        assertEquals(32 * 1024 * 1024, CoverageRendererLimits.COMBINED_RENDERER_OWNED_LIMIT_BYTES)
        assertEquals(18 * 1024 * 1024, CoverageRendererLimits.TRANSITION_HEADROOM_BYTES)
        assertEquals(32L * 1024L * 1024L, renderer.rendererOwnedBytes)
        assertEquals(64L * 1024L, renderer.maxUploadBytesPerFrame)
        assertTrue(renderer.selectionChurnPermille < 20)
        assertEquals(renderer.buffersAcquired, renderer.buffersReleased)
        assertEquals(renderer.callbacksAcquired, renderer.callbacksReleased)
        assertEquals(renderer.rendererResourcesAcquired, renderer.rendererResourcesReleased)
        assertEquals(127, pages.coalesced)
        assertEquals(1, pages.maxPendingTransactions)
        assertEquals(0, pages.pendingTransactions)
        assertEquals(0, pages.catchUpBursts)
        assertEquals(pages.pagesAcquired, pages.pagesReleased)
    }
}
