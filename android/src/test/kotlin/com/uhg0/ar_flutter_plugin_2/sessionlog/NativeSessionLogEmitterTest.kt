package com.uhg0.ar_flutter_plugin_2.sessionlog

import com.uhg0.ar_flutter_plugin_2.capture.PoseDataExtractor
import com.uhg0.ar_flutter_plugin_2.capture.PoseDataExtractor.ExposureMotionGate
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

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

    private fun diagnostic(gate: ExposureMotionGate) = PoseDataExtractor.ExposureMotionDiagnostic(
        gate = gate,
        decisionNs = 1L, ageMs = 5.0, historySpanMs = 300.0, stableDwellMs = 120.0, pairCount = 9,
        latestTranslationSpeedMps = 0.01, latestAngularSpeedDegreesPerSecond = 1.0,
        medianTranslationSpeedMps = 0.01, medianAngularSpeedDegreesPerSecond = 1.0,
        maximumTranslationSpeedMps = 0.02, maximumAngularSpeedDegreesPerSecond = 2.0,
        firstRefusingPair = null, maximumAngularPair = null, minimumPositiveCameraGapNs = 33_000_000L,
        duplicatePairCount = 0, timestampReadMismatchCount = 0L,
        breakCounts = emptyMap(), sameImageRevisionCount = 0L,
    )

    @Test
    fun `every motion gate refusal is logged under a catalogue code with the gate kept`() {
        for (gate in ExposureMotionGate.values()) {
            if (gate == ExposureMotionGate.APPROVED) continue
            val records = withNativeSessionLog { NativeSessionLogEmitter.recordMotionRefusal("a1", diagnostic(gate)) }
            val full = records.single()
            val code = full[1] as String
            assertTrue("$gate -> $code is not in the catalogue", code in CATALOGUE_CAPTURE_REFUSED_CODES)
            EXPECTED_CODES[gate]?.let { assertEquals(it, code) }
            assertEquals(gate.name, full.data["gate"])
            assertEquals("CAPTURE_REFUSED_${gate.name}", full.data["originalCode"])
        }
    }

    @Test
    fun `the mirrored refusal codes are in the app catalogue`() {
        // Present when the plugin is built inside the app repository.
        val catalogue = File("../../../capture3d/lib/capture/session_log/session_log_codes.dart")
        assumeTrue(catalogue.isFile)
        val ids = Regex("'(CAPTURE_REFUSED_[A-Z_]+)'").findAll(catalogue.readText()).map { it.groupValues[1] }.toSet()
        assertEquals(CATALOGUE_CAPTURE_REFUSED_CODES, ids)
    }

    @Test
    fun `invalid sample gates folded into one code are each written once in full`() {
        val records = withNativeSessionLog {
            NativeSessionLogEmitter.recordMotionRefusal("a1", diagnostic(ExposureMotionGate.INVALID_GAP))
            NativeSessionLogEmitter.recordMotionRefusal("a2", diagnostic(ExposureMotionGate.INVALID_POSE))
        }
        assertEquals(
            listOf("INVALID_GAP", "INVALID_POSE"),
            records.full("CAPTURE_REFUSED_OTHER").map { it.data["gate"] },
        )
    }

    @Test
    fun `a failing handler replies with a status map, not an error`() {
        val replies = mutableListOf<Any?>()
        var errors = 0
        val result = object : MethodChannel.Result {
            override fun success(result: Any?) {
                replies.add(result)
            }

            override fun error(errorCode: String, errorMessage: String?, errorDetails: Any?) {
                errors++
            }

            override fun notImplemented() {
                errors++
            }
        }
        val records = withNativeSessionLog {
            NativeSessionLogEmitter.guard("ch", MethodCall("boom", null), result) { throw IllegalStateException("x") }
        }
        assertEquals(0, errors)
        val reply = replies.single() as Map<*, *>
        assertEquals("error", reply["status"])
        assertEquals("NATIVE_HANDLER_ERROR", reply["code"])
        assertEquals(1, records.full("NATIVE_HANDLER_ERROR").size)
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

    private companion object {
        /** Gates whose name differs from their catalogue reason. */
        val EXPECTED_CODES = mapOf(
            ExposureMotionGate.STALE to "CAPTURE_REFUSED_STALE_POSE",
            ExposureMotionGate.OLDER_NOT_TRACKING to "CAPTURE_REFUSED_NOT_TRACKING",
            ExposureMotionGate.INVALID_GAP to "CAPTURE_REFUSED_OTHER",
            ExposureMotionGate.INVALID_POSE to "CAPTURE_REFUSED_OTHER",
            ExposureMotionGate.INVALID_QUATERNION to "CAPTURE_REFUSED_OTHER",
        )

        /** `CAPTURE_REFUSED_*` ids of `session_log_codes.dart`, mirrored (compared with the file when present). */
        val CATALOGUE_CAPTURE_REFUSED_CODES = setOf(
            "CAPTURE_REFUSED_BLUR",
            "CAPTURE_REFUSED_MOTION",
            "CAPTURE_REFUSED_INSUFFICIENT_DWELL",
            "CAPTURE_REFUSED_MOVING_TOO_FAST",
            "CAPTURE_REFUSED_NOT_TRACKING",
            "CAPTURE_REFUSED_STALE_POSE",
            "CAPTURE_REFUSED_NO_POSE",
            "CAPTURE_REFUSED_QUALITY_UNAVAILABLE",
            "CAPTURE_REFUSED_OTHER",
        )
    }
}
