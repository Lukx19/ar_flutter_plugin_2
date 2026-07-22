package com.uhg0.ar_flutter_plugin_2.sceneview

import org.junit.Assert.assertEquals
import org.junit.Test

class FrameCadenceTrackerTest {
    @Test
    fun `reports median cadence and ignores non-increasing timestamps`() {
        val tracker = FrameCadenceTracker()

        tracker.record(1_000_000_000L)
        tracker.record(1_016_000_000L)
        tracker.record(1_016_000_000L)
        tracker.record(1_050_000_000L)
        tracker.record(1_066_000_000L)

        val snapshot = tracker.snapshot()
        assertEquals(3, snapshot["sampleCount"])
        assertEquals(16.0, snapshot["medianFrameIntervalMs"] as Double, 0.001)
        assertEquals(62.5, snapshot["medianFps"] as Double, 0.001)
    }

    @Test
    fun `retains only the configured sample window`() {
        val tracker = FrameCadenceTracker(maximumSamples = 2)

        tracker.record(0L)
        tracker.record(10_000_000L)
        tracker.record(30_000_000L)
        tracker.record(70_000_000L)

        val snapshot = tracker.snapshot()
        assertEquals(2, snapshot["sampleCount"])
        assertEquals(30.0, snapshot["medianFrameIntervalMs"] as Double, 0.001)
    }
}
