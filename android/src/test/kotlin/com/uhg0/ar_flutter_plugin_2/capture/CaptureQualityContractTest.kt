package com.uhg0.ar_flutter_plugin_2.capture

import java.security.MessageDigest
import org.junit.Assert.*
import org.junit.Test

class CaptureQualityContractTest {
    private fun review() = CaptureQualityAssessmentV1(policyVersion = "jpeg-global-variance-v1",
        disposition = CaptureQualityDispositionV1.REVIEW_REQUIRED, reason = CaptureQualityReasonV1.BELOW_THRESHOLD,
        score = 58.6645, threshold = 110.0, sampleWidth = 256, sampleHeight = 192)

    @Test fun `policy pass cannot be constructed from missing unknown or contradictory quality evidence`() {
        val review = review()
        for (change in listOf<() -> CaptureQualityAssessmentV1>(
            { review.copy(schemaVersion = 2) }, { review.copy(policyVersion = "") },
            { review.copy(policyVersion = "line\nbreak") }, { review.copy(policyVersion = "x".repeat(129)) },
            { review.copy(score = Double.NaN) }, { review.copy(threshold = Double.POSITIVE_INFINITY) },
            { review.copy(sampleWidth = null) }, { review.copy(sampleHeight = 4097) },
            { review.copy(disposition = CaptureQualityDispositionV1.POLICY_PASSED) },
            { review.copy(reason = CaptureQualityReasonV1.POLICY_PASSED) },
            { review.copy(reason = CaptureQualityReasonV1.ANALYSIS_UNAVAILABLE, score = null) },
        )) assertThrows(IllegalArgumentException::class.java) { change() }
    }

    @Test fun `strict new root retains typed assessment and rejects missing unknown duplicate or downgraded schema`() {
        val requestHash = "a".repeat(64)
        val fields = review().rootFields().entries.joinToString("\n", postfix = "\n") { "${it.key}=${it.value}" }
        val root = "schema=6\nrevision=1\nrequest=$requestHash\nprevious=-\nsecondPrevious=-\n" +
            "component=JPEG:3:${"b".repeat(64)}\nexposureTimestampNanoseconds=123\n" + fields +
            "qualityAssessmentHash=${CaptureQualityAssessmentV1.boundHash(requestHash, review())}\n"
        assertEquals(review(), parse(root)!!.qualityAssessment)
        for (bad in listOf(
            root.replace("qualityDisposition=reviewRequired\n", ""), root.replace("qualitySchema=1", "qualitySchema=2"),
            root.replace("schema=6", "schema=7"), root + "schema=5\n", root + "revision=1\n",
            root.replace("schema=6", "schema=5"), root + "path=/tmp/jpeg\n",
            root.replace("qualityScore=58.6645", "qualityScore=NaN"),
            root.replace("qualityScore=58.6645", "qualityScore=60.0"),
            root.replace("qualityDisposition=reviewRequired", "qualityDisposition=policyPassed"),
            root.replace("component=JPEG", "component=UNKNOWN"),
            root.replace("exposureTimestampNanoseconds=123", "exposureTimestampNanoseconds=-1"),
            root.replace("qualityAssessmentHash=", "qualityAssessmentHash=0"),
        )) assertNull(bad, parse(bad))
        val legacy = "schema=5\nrevision=1\nrequest=$requestHash\nprevious=-\nsecondPrevious=-\ncomponent=JPEG:3:${"b".repeat(64)}\n"
        assertEquals("5", parse(legacy)!!.schema)
        assertNull(parse(legacy)!!.qualityAssessment)
    }

    @Test fun `opt in scalar wire carries durable assessment while legacy wire remains unchanged`() {
        val identity = CaptureAttemptIdentity("attempt", "commit", 1,
            CaptureLifecycleCut("session", 1, "group", 1, "ar", "view", 1, "binding", 1, 1))
        val requestHash = "a".repeat(64)
        val terminal = CaptureTerminal(CaptureTerminalKind.COMMITTED_PICTURE, identity, requestHash, "committed",
            "capture", 1, "b".repeat(64), 3, null, review(), CaptureQualityAssessmentV1.boundHash(requestHash, review()))
        val matrix = List(16) { if (it % 5 == 0) 1.0 else 0.0 }
        val context = NativeCaptureRecoveryContextV2("session", "group", 0, 1, "manual", 1, 1, 1,
            listOf(0.0, 0.0, 0.0), listOf(0.0, 0.0, 0.0, 1.0), matrix, matrix, matrix, matrix)
        val event = NativeCaptureEventV2(NativeCaptureEventKindV2.COMMITTED, "attempt", "capture", 1,
            "b".repeat(64), 3, reason = "committed", recoveryContext = context)
        val legacy = NativeCaptureWireV2.event(event)
        assertEquals("native_capture_v2", legacy["wireVersion"])
        assertFalse(legacy.containsKey("qualityDisposition"))
        val assessed = NativeCaptureWireV2.assessedCommittedEvent(event, terminal)
        assertEquals("native_capture_quality_v1", assessed["wireVersion"])
        assertEquals("reviewRequired", assessed["qualityDisposition"])
        assertEquals(terminal.qualityAssessmentHash, assessed["qualityAssessmentHash"])
        assertEquals(58.6645, (assessed["qualityAssessment"] as Map<*, *>)["score"])
        assertThrows(IllegalArgumentException::class.java) {
            NativeCaptureWireV2.assessedCommittedEvent(event.copy(attemptId = "changed"), terminal)
        }
    }

    private fun parse(source: String): CaptureRootMetadataV2? {
        val bytes = source.toByteArray()
        val hash = MessageDigest.getInstance("SHA-256").digest(bytes).toCaptureHashHex()
        return bytes.inputStream().use { CaptureRootMetadataReaderV2().root(hash, it::read) }
    }
}
