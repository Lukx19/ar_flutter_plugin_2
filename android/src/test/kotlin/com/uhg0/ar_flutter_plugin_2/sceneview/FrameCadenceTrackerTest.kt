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

    @Test
    fun `measurement window skips boundary interval while health keeps older stalls`() {
        val tracker = FrameCadenceTracker(maximumSamples = 20)
        var timestamp = 0L
        tracker.record(timestamp)
        repeat(4) {
            timestamp += 120_000_000L
            tracker.record(timestamp)
        }
        repeat(11) {
            timestamp += 33_000_000L
            tracker.record(timestamp)
        }
        val rollingBeforeWindow = tracker.snapshot()
        tracker.beginMeasurementWindow()

        // The first interval crosses the mark and must not enter the campaign.
        timestamp += 120_000_000L
        tracker.record(timestamp)
        repeat(3) {
            timestamp += 33_000_000L
            tracker.record(timestamp)
        }

        val campaign = tracker.snapshot()
        assertEquals(15, rollingBeforeWindow["sampleCount"])
        assertEquals(3, campaign["sampleCount"])
        assertEquals(33.0, campaign["maxFrameIntervalMs"] as Double, 0.001)
        assertEquals(0, campaign["frameGapsOver100Ms"])
        assertFalse(tracker.healthyForDepthIntake())
        // Maps returned before the mark remain unchanged while the live health
        // guard continues to see the complete rolling history.
        assertEquals(4, rollingBeforeWindow["frameGapsOver100Ms"])
    }

    @Test
    fun `ring wrap preserves equal interval ordering for percentile statistics`() {
        val tracker = FrameCadenceTracker(maximumSamples = 3)

        tracker.record(0L)
        tracker.record(10_000_000L)
        tracker.record(30_000_000L)
        tracker.record(50_000_000L)
        tracker.record(80_000_000L)

        val snapshot = tracker.snapshot()
        assertEquals(3, snapshot["sampleCount"])
        assertEquals(20.0, snapshot["medianFrameIntervalMs"] as Double, 0.001)
        assertEquals(30.0, snapshot["maxFrameIntervalMs"] as Double, 0.001)
        assertEquals(0, snapshot["frameGapsOver50Ms"])
    }

    @Test
    fun `window started before the first frame includes its first complete interval`() {
        val tracker = FrameCadenceTracker()

        tracker.beginMeasurementWindow()
        tracker.record(0L)
        tracker.record(16_000_000L)

        val snapshot = tracker.snapshot()
        assertEquals(1, snapshot["sampleCount"])
        assertEquals(16.0, snapshot["maxFrameIntervalMs"] as Double, 0.001)
    }

    @Test
    fun `measurement window reports exact p99 tail and full gap count`() {
        val tracker = FrameCadenceTracker()
        var timestamp = 0L
        tracker.record(timestamp)
        repeat(15) {
            timestamp += 16_000_000L
            tracker.record(timestamp)
        }
        tracker.beginMeasurementWindow()

        // The first interval crosses the mark and is excluded from the full
        // window tail. Every following interval is retained by the test tail.
        timestamp += 120_000_000L
        tracker.record(timestamp)
        repeat(4) {
            timestamp += 16_000_000L
            tracker.record(timestamp)
        }

        val snapshot = tracker.snapshot()
        assertEquals(4, snapshot["measurementWindowSampleCount"])
        assertEquals(16.0, snapshot["measurementWindowP99FrameIntervalMs"] as Double, 0.001)
        assertEquals(true, snapshot["measurementWindowP99Complete"])
        assertEquals(16.0, snapshot["measurementWindowMaxFrameIntervalMs"] as Double, 0.001)
        assertEquals(0, snapshot["measurementWindowFrameGapsOver100Ms"])
    }

    @Test
    fun `measurement window reports stale local arrival after the last advancing frame`() {
        var arrivalNow = 32_000_000L
        val tracker = FrameCadenceTracker(clockNs = { arrivalNow })

        tracker.record(0L, 0L)
        tracker.beginMeasurementWindow()
        tracker.record(16_000_000L, 16_000_000L) // boundary interval
        tracker.record(32_000_000L, 32_000_000L)

        val fresh = tracker.snapshot()
        assertEquals(true, fresh["measurementWindowHasAdvancingFrame"])
        assertEquals(0.0, fresh["measurementWindowLastArrivalAgeMs"] as Double, 0.001)

        arrivalNow += 100_000_000L
        val stale = tracker.snapshot()
        assertEquals(100.0, stale["measurementWindowLastArrivalAgeMs"] as Double, 0.001)
    }

    private fun gapRecords(snapshot: Map<String, Any?>) = snapshot["records"] as List<*>

    @Test fun `diagnostic capacity retains first gaps despite cadence ring eviction`() {
        val tracker = FrameCadenceTracker(maximumSamples = 2, debugGapTimingEnabled = true)
        tracker.beginMeasurementWindow(captureDiagnosticTiming = true)
        tracker.record(0L)
        repeat(20) { tracker.record((it + 1L) * 120_000_000L) }
        val receipt = tracker.diagnosticTimingSnapshot()
        assertEquals(16, gapRecords(receipt).size)
        assertEquals(20L, receipt["observedGaps"])
        assertEquals(4L, receipt["droppedGaps"])
        assertEquals(512, receipt["scalarBytes"])
        assertEquals(1L, (gapRecords(receipt).first() as Map<*, *>)["intervalOrdinal"])
        assertEquals(16L, (gapRecords(receipt).last() as Map<*, *>)["intervalOrdinal"])
        assertEquals(20, tracker.snapshot()["measurementWindowFrameGapsOver100Ms"])
    }

    @Test fun `camera sensor gap and callback arrival span remain distinct`() {
        val tracker = FrameCadenceTracker(debugGapTimingEnabled = true)
        tracker.beginMeasurementWindow(captureDiagnosticTiming = true)
        tracker.record(8_000_000_000L, 1_000_000_000L)
        tracker.record(8_200_000_000L, 1_033_000_000L)
        val gap = gapRecords(tracker.diagnosticTimingSnapshot()).single() as Map<*, *>
        assertEquals(200_000_000L, gap["sourceFrameIntervalNs"])
        assertEquals(33_000_000L, gap["callbackArrivalIntervalNs"])
        assertEquals(true, gap["arrivalClockQualified"])
        tracker.record(8_400_000_000L, 1_020_000_000L)
        val reversed = gapRecords(tracker.diagnosticTimingSnapshot()).last() as Map<*, *>
        assertEquals(false, reversed["arrivalClockQualified"])
    }

    @Test fun `reset and freeze isolate held receipts without changing cadence gates`() {
        var now = 0L
        val tracker = FrameCadenceTracker(clockNs = { now }, debugGapTimingEnabled = true)
        tracker.beginMeasurementWindow(captureDiagnosticTiming = true)
        tracker.record(0L)
        tracker.record(120_000_000L)
        val held = tracker.diagnosticTimingSnapshot()
        now = 130_000_000L
        tracker.beginMeasurementWindow(captureDiagnosticTiming = true)
        tracker.record(240_000_000L) // Crosses reset boundary, excluded.
        tracker.record(360_000_000L)
        now = 370_000_000L
        tracker.freezeDiagnosticTiming()
        tracker.record(480_000_000L) // Gate still sees this; frozen diagnostics do not.
        val frozen = tracker.diagnosticTimingSnapshot()
        assertEquals(1L, held["epoch"])
        assertEquals(1L, held["observedGaps"])
        assertEquals(120_000_000L, (gapRecords(held).single() as Map<*, *>)["arrivalEndNs"])
        assertEquals(2L, frozen["epoch"])
        assertEquals(130_000_000L, frozen["windowStartNs"])
        assertEquals(370_000_000L, frozen["windowEndNs"])
        assertEquals(true, frozen["frozen"])
        assertEquals(1L, frozen["observedGaps"])
        assertEquals(2, tracker.snapshot()["measurementWindowFrameGapsOver100Ms"])
    }

    @Test fun `ordinary and release trackers never collect diagnostic intervals`() {
        for (debugEnabled in listOf(false, true)) {
            val tracker = FrameCadenceTracker(debugGapTimingEnabled = debugEnabled)
            tracker.beginMeasurementWindow(captureDiagnosticTiming = !debugEnabled)
            tracker.record(0L)
            tracker.record(120_000_000L)
            val receipt = tracker.diagnosticTimingSnapshot()
            assertEquals(false, receipt["enabled"])
            assertEquals(if (debugEnabled) 512 else 0, receipt["scalarBytes"])
            assertTrue(gapRecords(receipt).isEmpty())
            assertEquals(1, tracker.snapshot()["measurementWindowFrameGapsOver100Ms"])
        }
    }
}
