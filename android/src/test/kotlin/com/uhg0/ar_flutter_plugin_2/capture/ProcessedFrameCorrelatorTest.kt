package com.uhg0.ar_flutter_plugin_2.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ProcessedFrameCorrelatorTest {
    @Test
    fun `pairs frame and result in either order`() {
        listOf(true, false).forEach { frameFirst ->
            val correlator = ProcessedFrameCorrelator<String>()
            val result = metadata()
            val first =
                if (frameFirst) correlator.onFrame(42L, "yuv")
                else correlator.onResult(result)
            assertNull(first)
            val paired =
                if (frameFirst) correlator.onResult(result)
                else correlator.onFrame(42L, "yuv")
            assertEquals("yuv", paired!!.frame)
            assertEquals(42L, paired.result.sensorTimestampNs)
        }
    }

    @Test
    fun `expires unmatched entries and remains bounded`() {
        var now = 0L
        val correlator =
            ProcessedFrameCorrelator<String>(clockMs = { now }, timeoutMs = 10, maxPendingEntries = 2)
        correlator.onFrame(1, "one")
        correlator.onFrame(2, "two")
        correlator.onFrame(3, "three")
        assertEquals(2 to 0, correlator.snapshot())
        now = 20
        correlator.onResult(metadata(4))
        assertEquals(0 to 1, correlator.snapshot())
    }

    private fun metadata(timestamp: Long = 42L) =
        PendingStillResultMetadata(
            frameNumber = timestamp,
            sensorTimestampNs = timestamp,
            exposureTimeNs = 10,
            rollingShutterSkewNs = 2,
            receivedAtMs = 0,
        )
}
