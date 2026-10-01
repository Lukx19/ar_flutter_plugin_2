package com.uhg0.ar_flutter_plugin_2.sceneview

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FrameCadenceTrackerTest {
    @Test
    fun `depth intake pauses after frame jitter and resumes after smooth frames`() {
        val tracker = FrameCadenceTracker()
        var timestamp = 0L
        tracker.record(timestamp)
        repeat(15) {
            timestamp += 33_000_000L
            tracker.record(timestamp)
        }
        assertTrue(tracker.healthyForDepthIntake())
        timestamp += 120_000_000L
        tracker.record(timestamp)
        assertFalse(tracker.healthyForDepthIntake())
        repeat(60) {
            timestamp += 33_000_000L
            tracker.record(timestamp)
        }
        assertTrue(tracker.healthyForDepthIntake())
    }

    @Test
    fun `reports median cadence and ignores non-increasing timestamps`() {
        val tracker = FrameCadenceTracker()

        tracker.record(1_000_000_000L)
        tracker.record(1_016_000_000L)
        tracker.record(1_016_000_000L)
        tracker.record(1_010_000_000L)
        tracker.record(1_050_000_000L)
        tracker.record(1_066_000_000L)

        val snapshot = tracker.snapshot()
        assertEquals(3, snapshot["sampleCount"])
        assertEquals(16.0, snapshot["medianFrameIntervalMs"] as Double, 0.001)
        assertEquals(62.5, snapshot["medianFps"] as Double, 0.001)
        assertEquals(34.0, snapshot["p95FrameIntervalMs"] as Double, 0.001)
        assertEquals(34.0, snapshot["p99FrameIntervalMs"] as Double, 0.001)
        assertEquals(34.0, snapshot["maxFrameIntervalMs"] as Double, 0.001)
        assertEquals(0, snapshot["frameGapsOver50Ms"])
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
        assertEquals(40.0, snapshot["maxFrameIntervalMs"] as Double, 0.001)
    }

    @Test
    fun `reports tail jitter and excludes gaps that leave the bounded window`() {
        val tracker = FrameCadenceTracker(maximumSamples = 5)
        var timestamp = 0L
        tracker.record(timestamp)
        for (intervalMs in listOf(120L, 16L, 16L, 40L, 120L, 16L)) {
            timestamp += intervalMs * 1_000_000L
            tracker.record(timestamp)
        }

        val snapshot = tracker.snapshot()
        assertEquals(5, snapshot["sampleCount"])
        assertEquals(120.0, snapshot["p95FrameIntervalMs"] as Double, 0.001)
        assertEquals(120.0, snapshot["p99FrameIntervalMs"] as Double, 0.001)
        assertEquals(120.0, snapshot["maxFrameIntervalMs"] as Double, 0.001)
        assertEquals(1, snapshot["frameGapsOver50Ms"])
        assertEquals(1, snapshot["frameGapsOver100Ms"])
    }
}
