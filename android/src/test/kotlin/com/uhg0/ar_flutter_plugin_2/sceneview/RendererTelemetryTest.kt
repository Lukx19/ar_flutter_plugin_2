package com.uhg0.ar_flutter_plugin_2.sceneview

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class RendererTelemetryTest {
    @Test
    fun `telemetry retains owned buffer peak after a renderer replacement`() {
        val telemetry = RendererTelemetry()
        telemetry.setOwnedBufferBytes("points", 2_304)
        telemetry.setOwnedBufferBytes("cubes", 47_104)
        telemetry.beginRendererFrame()
        telemetry.recordResourceResetScheduled()
        telemetry.recordUpload(64, RendererUploadPageOrigin.RESOURCE_GENERATION_RESET)
        telemetry.recordUploadCallback(RendererUploadPageOrigin.RESOURCE_GENERATION_RESET)
        telemetry.recordUploadCompletion(24_000, RendererUploadPageOrigin.RESOURCE_GENERATION_RESET)
        telemetry.removeOwner("points")

        val snapshot = telemetry.snapshot()

        assertEquals(47_104, snapshot.getValue("ownedBufferBytes"))
        assertEquals(49_408, snapshot.getValue("peakOwnedBufferBytes"))
        assertEquals(64, snapshot.getValue("currentUpdateUploadBytes"))
        assertEquals(1, snapshot.getValue("resourceResetScheduledCount"))
        assertEquals(1, snapshot.getValue("uploadPageSubmissionCount"))
        assertEquals(1, snapshot.getValue("resourceGenerationResetPageSubmissionCount"))
        assertEquals(0, snapshot.getValue("ordinaryPageSubmissionCount"))
        assertEquals(1, snapshot.getValue("uploadCallbackCount"))
        assertEquals(1, snapshot.getValue("completedUploadCount"))
        assertEquals(1, snapshot.getValue("resourceGenerationResetCallbackCount"))
        assertEquals(1, snapshot.getValue("resourceGenerationResetCompletionCount"))
        assertEquals("resource-generation-reset", snapshot.getValue("lastUploadPageReason"))
        assertEquals("resource-generation-reset", snapshot.getValue("lastUploadCompletionReason"))
        assertEquals(24_000L, snapshot.getValue("meanUploadCompletionNanos"))
        assertEquals(false, snapshot.getValue("gpuTimingAvailable"))
        assertEquals(false, snapshot.getValue("gpuAllocationAvailable"))
        assertEquals(64 * 1024, snapshot.getValue("ordinaryUploadLimitBytes"))
    }

    @Test
    fun `telemetry rejects a renderer-owned allocation above the shared cap`() {
        val telemetry = RendererTelemetry()
        telemetry.setOwnedBufferBytes("centroids", 20_000 * 36)

        assertThrows(IllegalStateException::class.java) {
            telemetry.setOwnedBufferBytes(
                "cubes",
                RendererTelemetry.RENDERER_ALLOCATION_LIMIT_BYTES,
            )
        }

        assertEquals(20_000 * 36, telemetry.snapshot().getValue("ownedBufferBytes"))
    }

    @Test
    fun `telemetry accumulates uploads within a renderer frame and rejects overflow`() {
        val telemetry = RendererTelemetry()
        telemetry.beginRendererFrame()
        telemetry.recordUpload(32 * 1024)
        telemetry.recordUpload(32 * 1024)

        assertEquals(64 * 1024, telemetry.snapshot().getValue("currentUpdateUploadBytes"))
        assertThrows(IllegalArgumentException::class.java) {
            telemetry.recordUpload(1)
        }

        telemetry.beginRendererFrame()
        telemetry.recordUpload(1)
        assertEquals(1, telemetry.snapshot().getValue("currentUpdateUploadBytes"))
        assertEquals(64 * 1024, telemetry.snapshot().getValue("peakUpdateUploadBytes"))
    }
}
