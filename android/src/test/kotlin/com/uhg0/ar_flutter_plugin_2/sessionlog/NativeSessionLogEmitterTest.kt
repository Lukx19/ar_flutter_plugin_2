package com.uhg0.ar_flutter_plugin_2.sessionlog

import com.uhg0.ar_flutter_plugin_2.capture.PoseDataExtractor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeSessionLogEmitterTest {
    @Test
    fun `a failure is recorded only while a log is open and builds its data only when admitted`() {
        var built = 0
        NativeSessionLogEmitter.setActive(false)
        NativeSessionLogEmitter.failure("NATIVE_HANDLER_ERROR", "x", "m") { built++; emptyMap() }
        assertEquals(0, built)
        val records = withNativeSessionLog {
            repeat(3) {
                NativeSessionLogEmitter.failure("NATIVE_HANDLER_ERROR", "x", "m") { built++; mapOf("n" to it) }
            }
        }
        assertEquals(1, built)
        assertEquals(1, records.full("NATIVE_HANDLER_ERROR").size)
        assertEquals(listOf("C", "NATIVE_HANDLER_ERROR", 2, "m"), records.single { it[0] == "C" })
    }

    @Test
    fun `counter growth is reported with the new and total counts and advances the baseline while closed`() {
        NativeSessionLogEmitter.setActive(false)
        var baseline = NativeSessionLogEmitter.reportGrowth("DEPTH_STALE", null, 5, 0, "stale")
        assertEquals(5L, baseline)
        val records = withNativeSessionLog {
            baseline = NativeSessionLogEmitter.reportGrowth("DEPTH_STALE", null, 8, baseline, "stale", mapOf("lastImageAgeMs" to 40L))
        }
        val stale = records.full("DEPTH_STALE").single()
        assertEquals(3L, stale.data["new"])
        assertEquals(8L, stale.data["total"])
        assertEquals(40L, stale.data["lastImageAgeMs"])
    }

    @Test
    fun `a motion refusal names why the motion history broke`() {
        val diagnostic = PoseDataExtractor.ExposureMotionDiagnostic(
            gate = PoseDataExtractor.ExposureMotionGate.INSUFFICIENT_DWELL,
            decisionNs = 1L, ageMs = 5.0, historySpanMs = 300.0, stableDwellMs = 120.0, pairCount = 9,
            latestTranslationSpeedMps = 0.01, latestAngularSpeedDegreesPerSecond = 1.0,
            medianTranslationSpeedMps = 0.01, medianAngularSpeedDegreesPerSecond = 1.0,
            maximumTranslationSpeedMps = 0.02, maximumAngularSpeedDegreesPerSecond = 2.0,
            firstRefusingPair = null, maximumAngularPair = null, minimumPositiveCameraGapNs = 33_000_000L,
            duplicatePairCount = 0, timestampReadMismatchCount = 0L,
            breakCounts = mapOf(PoseDataExtractor.ExposureMotionGate.MOVING_TOO_FAST to 2L),
            sameImageRevisionCount = 7L,
        )
        val records = withNativeSessionLog { NativeSessionLogEmitter.recordMotionRefusal("a1", diagnostic) }
        val refusal = records.full("CAPTURE_REFUSED_INSUFFICIENT_DWELL").single()
        assertEquals("MOVING_TOO_FAST:2", refusal.data["historyBreak"])
        assertEquals(7L, refusal.data["sameImageRevisions"])
        assertTrue(refusal.data.containsKey("dwellMs"))
    }
}
