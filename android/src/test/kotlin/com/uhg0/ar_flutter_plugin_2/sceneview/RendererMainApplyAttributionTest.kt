package com.uhg0.ar_flutter_plugin_2.sceneview

import org.junit.Assert.assertEquals
import org.junit.Test

class RendererMainApplyAttributionTest {
    @Test
    fun `disabled and reset windows report unavailable allocation instead of zero work`() {
        val disabled = RendererMainApplyAttribution.disabledSnapshot()
        assertEquals(false, disabled.getValue("rendererMainApplyDiagnosticsEnabled"))
        assertEquals(0, disabled.getValue("rendererMainApplyDiagnosticsScalarBytes"))
        assertEquals(-1L, disabled.getValue("rendererMainApplyProcessAllocatedBytesTotal"))

        val attribution = RendererMainApplyAttribution()
        attribution.record(100L, 10L, 30L)
        attribution.reset()
        val reset = attribution.snapshot()
        assertEquals(true, reset.getValue("rendererMainApplyDiagnosticsEnabled"))
        assertEquals(48, reset.getValue("rendererMainApplyDiagnosticsScalarBytes"))
        assertEquals(0L, reset.getValue("rendererMainApplySampleCount"))
        assertEquals(0L, reset.getValue("rendererMainApplyTotalNanos"))
        assertEquals(0L, reset.getValue("rendererMainApplyMaxNanos"))
        assertEquals(0L, reset.getValue("rendererMainApplyAllocationSampleCount"))
        assertEquals(-1L, reset.getValue("rendererMainApplyProcessAllocatedBytesTotal"))
        assertEquals(-1L, reset.getValue("rendererMainApplyProcessAllocatedBytesMax"))
    }

    @Test
    fun `unavailable and rolled back process counters never become allocation samples`() {
        val attribution = RendererMainApplyAttribution()
        attribution.record(10L, -1L, 20L)
        attribution.record(30L, 20L, -1L)
        attribution.record(20L, 100L, 99L)
        val snapshot = attribution.snapshot()

        assertEquals(3L, snapshot.getValue("rendererMainApplySampleCount"))
        assertEquals(60L, snapshot.getValue("rendererMainApplyTotalNanos"))
        assertEquals(30L, snapshot.getValue("rendererMainApplyMaxNanos"))
        assertEquals(0L, snapshot.getValue("rendererMainApplyAllocationSampleCount"))
        assertEquals(-1L, snapshot.getValue("rendererMainApplyProcessAllocatedBytesTotal"))
        assertEquals(-1L, snapshot.getValue("rendererMainApplyProcessAllocatedBytesMax"))
    }

    @Test
    fun `zero allocation is valid and valid inclusive windows retain count total and maximum`() {
        val attribution = RendererMainApplyAttribution()
        attribution.record(0L, 0L, 0L)
        val zero = attribution.snapshot()
        assertEquals(1L, zero.getValue("rendererMainApplyAllocationSampleCount"))
        assertEquals(0L, zero.getValue("rendererMainApplyProcessAllocatedBytesTotal"))
        attribution.record(8L, 100L, 130L)
        attribution.record(5L, 130L, 140L)
        attribution.record(2L, 140L, 139L)
        val snapshot = attribution.snapshot()

        assertEquals(4L, snapshot.getValue("rendererMainApplySampleCount"))
        assertEquals(15L, snapshot.getValue("rendererMainApplyTotalNanos"))
        assertEquals(8L, snapshot.getValue("rendererMainApplyMaxNanos"))
        assertEquals(3L, snapshot.getValue("rendererMainApplyAllocationSampleCount"))
        assertEquals(40L, snapshot.getValue("rendererMainApplyProcessAllocatedBytesTotal"))
        assertEquals(30L, snapshot.getValue("rendererMainApplyProcessAllocatedBytesMax"))
        assertEquals(
            "inclusive-process-counter-window-may-overlap-worker",
            snapshot.getValue("rendererMainApplyAllocationScope"),
        )
    }
}
