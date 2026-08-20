package com.uhg0.ar_flutter_plugin_2.sceneview

import org.junit.Assert.assertEquals
import org.junit.Test

class RendererTelemetryTest {
    @Test
    fun `telemetry retains owned buffer peak after a renderer replacement`() {
        val telemetry = RendererTelemetry()
        telemetry.setOwnedBufferBytes("points", 2_304)
        telemetry.setOwnedBufferBytes("cubes", 47_104)
        telemetry.beginRendererUpdate()
        telemetry.recordUpload(64)
        telemetry.recordUploadCallback()
        telemetry.removeOwner("points")

        val snapshot = telemetry.snapshot()

        assertEquals(47_104, snapshot.getValue("ownedBufferBytes"))
        assertEquals(49_408, snapshot.getValue("peakOwnedBufferBytes"))
        assertEquals(64, snapshot.getValue("currentUpdateUploadBytes"))
        assertEquals(1, snapshot.getValue("uploadCallbackCount"))
        assertEquals(64 * 1024, snapshot.getValue("ordinaryUploadLimitBytes"))
    }
}
