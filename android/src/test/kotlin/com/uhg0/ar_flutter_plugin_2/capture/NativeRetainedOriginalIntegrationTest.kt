package com.uhg0.ar_flutter_plugin_2.capture

import java.io.ByteArrayInputStream
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class NativeRetainedOriginalIntegrationTest {
    @get:Rule val directory = TemporaryFolder()

    @Test fun retainedReviewSettlesBothLanesOnceAndReplaysExactDurableAssessment() {
        for (lane in CaptureLane.entries) AdmittedJpegStoreFixture(directory.newFolder(lane.name), lane).use { fixture ->
            val request = withContext(fixture.request)
            val camera = Exposure()
            val events = mutableListOf<NativeCaptureEventV2>()
            val adapter = NativeCaptureAdapterV2(DurableNativeCaptureStorePortV2(fixture.store, true), camera,
                events = events::add, retainAssessedOriginals = true)
            try {
                adapter.admit(request)
                assertTrue(camera.callback.retainAssessedOriginals)
                DirectStoreDispatch(fixture.components(), camera.callback::onComponents,
                    { fail("Original became failure: $it") }, { PreviewBlurQuality(58.6645, 516, 387) },
                    retainAssessedOriginals = true).run()
                val live = events.single { it.kind == NativeCaptureEventKindV2.COMMITTED }
                val receipt = requireNotNull(fixture.store.queryReceipt(request.accepted.identity))
                fixture.assertOriginal(receipt)
                assertEquals(CaptureQualityDispositionV1.REVIEW_REQUIRED, live.qualityAssessment?.disposition)
                assertEquals(receipt.terminal, live.verifiedTerminal)
                val wire = encodeNativeCaptureEventV2(live)
                assertEquals("native_capture_quality_v1", wire["wireVersion"])
                assertEquals("reviewRequired", wire["qualityDisposition"])
                assertEquals(receipt.terminal?.qualityAssessmentHash, wire["qualityAssessmentHash"])
                assertEquals(0, adapter.snapshot().running)
                assertEquals(0, adapter.snapshot().fundedWaiting)
                assertEquals(1, fixture.closed.get())
                assertEquals(receipt, adapter.admit(request))
                assertEquals(1, camera.requests)
                assertEquals(1, events.count { it.kind == NativeCaptureEventKindV2.COMMITTED })
                val late = CountedInput(fixture.original)
                camera.callback.onComponents(SharedCameraComponentSetV2(camera.qualifier,
                    listOf(CaptureComponentStreamV2(CaptureComponentKind.JPEG, late)), 1_000_000L, live.qualityAssessment))
                assertEquals(1, late.closes)
                assertEquals(1L, adapter.snapshot().lateCallbacks)
                adapter.close()
                fixture.reopen()
                val projection = fixture.store.recoverAndProject().single()
                assertEquals(receipt.terminal, projection.verifiedTerminal)
                assertEquals(request.accepted.identity, projection.verifiedTerminal?.identity)
                val ready = CountDownLatch(1)
                val recoveredEvents = mutableListOf<NativeCaptureEventV2>()
                val dispatcher = NativeCaptureRecoveryDispatcherV2({ listOf(projection) }, { recovered ->
                    synchronized(recoveredEvents) { recoveredEvents.add(recovered) }
                    ready.countDown()
                })
                try {
                    assertTrue(ready.await(2, TimeUnit.SECONDS))
                    val recovered = synchronized(recoveredEvents) { recoveredEvents.single() }
                    assertEquals(live.qualityAssessment, recovered.qualityAssessment)
                    assertEquals(live.qualityAssessmentHash, recovered.qualityAssessmentHash)
                    val replayWire = encodeNativeCaptureEventV2(recovered)
                    assertEquals(wire["qualityAssessment"], replayWire["qualityAssessment"])
                    assertEquals(wire["qualityAssessmentHash"], replayWire["qualityAssessmentHash"])
                    assertThrows(IllegalArgumentException::class.java) { encodeNativeCaptureEventV2(recovered.copy(verifiedTerminal = null)) }
                    assertThrows(IllegalArgumentException::class.java) { encodeNativeCaptureEventV2(recovered.copy(qualityAssessmentHash = "0".repeat(64))) }
                    assertThrows(IllegalArgumentException::class.java) { encodeNativeCaptureEventV2(recovered.copy(captureId = "1".repeat(64))) }
                    assertThrows(DurableStoreConflictV2::class.java) {
                        val changedAssessment = requireNotNull(recovered.qualityAssessment).copy(score = 61.7403)
                        val originalTerminal = requireNotNull(recovered.verifiedTerminal)
                        val changedHash = CaptureQualityAssessmentV1.boundHash(originalTerminal.canonicalTerminalHash, changedAssessment)
                        dispatcher.emitLive(recovered.copy(qualityAssessment = changedAssessment, qualityAssessmentHash = changedHash,
                            verifiedTerminal = originalTerminal.copy(qualityAssessment = changedAssessment, qualityAssessmentHash = changedHash)))
                    }
                    assertEquals(recovered, dispatcher.replaySnapshot().single())
                } finally { dispatcher.close(); assertTrue(dispatcher.awaitTerminationForTest(2, TimeUnit.SECONDS)) }
            } finally { adapter.close() }
        }
    }

    @Test fun shutdownFencesRetainedReviewHandoffAndClosesLateOriginalOnce() {
        AdmittedJpegStoreFixture(directory.newFolder(), CaptureLane.MANUAL).use { fixture ->
            val camera = Exposure(); val events = mutableListOf<NativeCaptureEventV2>()
            val adapter = NativeCaptureAdapterV2(DurableNativeCaptureStorePortV2(fixture.store, true), camera,
                events = events::add, retainAssessedOriginals = true)
            adapter.admit(withContext(fixture.request)); adapter.close()
            DirectStoreDispatch(fixture.components(), camera.callback::onComponents, { fail(it) },
                { PreviewBlurQuality(58.6645, 516, 387) }, retainAssessedOriginals = true).run()
            assertEquals(1, fixture.closed.get())
            assertEquals(0, events.count { it.kind == NativeCaptureEventKindV2.COMMITTED })
            assertEquals(1L, adapter.snapshot().lateCallbacks)
            assertFalse(fixture.storeRoot.walkTopDown().any { it.extension == "blob" })
        }
    }

    @Test fun assessedTerminalReplaysAfterReopenWithProducerOffWithoutExposure() {
        AdmittedJpegStoreFixture(directory.newFolder(), CaptureLane.MANUAL).use { fixture ->
            val request = withContext(fixture.request)
            val camera = Exposure()
            val events = mutableListOf<NativeCaptureEventV2>()
            val producer = NativeCaptureAdapterV2(DurableNativeCaptureStorePortV2(fixture.store, true), camera,
                events = events::add, retainAssessedOriginals = true)
            producer.admit(request)
            DirectStoreDispatch(fixture.components(), camera.callback::onComponents,
                { fail("Original became failure: $it") }, { PreviewBlurQuality(58.6645, 516, 387) },
                retainAssessedOriginals = true).run()
            val committed = events.single { it.kind == NativeCaptureEventKindV2.COMMITTED }
            producer.close()
            fixture.reopen()

            val replayCamera = Exposure()
            val replay = NativeCaptureAdapterV2(DurableNativeCaptureStorePortV2(fixture.store, false), replayCamera)
            try {
                assertEquals(committed.verifiedTerminal, replay.admit(request).terminal)
                assertEquals(0, replayCamera.requests)
            } finally {
                replay.close()
            }
        }
    }

    @Test fun defaultDisabledAdapterRefusesAssessmentRatherThanPublishingLegacyCommit() {
        AdmittedJpegStoreFixture(directory.newFolder(), CaptureLane.MANUAL).use { fixture ->
            val camera = Exposure(); val events = mutableListOf<NativeCaptureEventV2>()
            val adapter = NativeCaptureAdapterV2(DurableNativeCaptureStorePortV2(fixture.store), camera, events = events::add)
            try {
                adapter.admit(withContext(fixture.request))
                assertFalse(camera.callback.retainAssessedOriginals)
                DirectStoreDispatch(fixture.components(), camera.callback::onComponents, { fail(it) },
                    { PreviewBlurQuality(58.6645, 516, 387) }, retainAssessedOriginals = true).run()
                assertEquals(1, fixture.closed.get())
                assertEquals(NativeCaptureEventKindV2.ABANDONED, events.single { it.kind == NativeCaptureEventKindV2.ABANDONED }.kind)
                assertFalse(events.any { it.kind == NativeCaptureEventKindV2.COMMITTED })
            } finally { adapter.close() }
        }
    }

    @Test fun assessedRecoveryWithoutVerifiedTerminalFailsClosedBeforePublication() {
        val assessment = CaptureQualityAssessmentV1(policyVersion = "jpegLaplacianVarianceV1:threshold110",
            disposition = CaptureQualityDispositionV1.REVIEW_REQUIRED, reason = CaptureQualityReasonV1.BELOW_THRESHOLD,
            score = 58.6645, threshold = 110.0, sampleWidth = 516, sampleHeight = 387)
        val events = mutableListOf<NativeCaptureEventV2>(); val emitted = CountDownLatch(1)
        val projection = DurableSessionStoreV2.RecoveryProjectionV2("attempt", CaptureTerminalKind.COMMITTED_PICTURE,
            "a".repeat(64), 1, "b".repeat(64), "recovered-committed", jpegSizeBytes = 42,
            qualityAssessment = assessment, qualityAssessmentHash = "c".repeat(64))
        val dispatcher = NativeCaptureRecoveryDispatcherV2({ listOf(projection) }, {
            synchronized(events) { events.add(it) }; emitted.countDown()
        })
        try {
            assertTrue(emitted.await(2, TimeUnit.SECONDS))
            assertEquals(NativeCaptureEventKindV2.RECOVERY_FAILED, synchronized(events) { events.single().kind })
            assertThrows(NativeCaptureRecoveryAdmissionExceptionV2::class.java) { dispatcher.requireAdmissionReady() }
        } finally { dispatcher.close(); assertTrue(dispatcher.awaitTerminationForTest(2, TimeUnit.SECONDS)) }
    }

    private fun withContext(original: CaptureCommitRequest): CaptureCommitRequest {
        val cut = original.accepted.identity.lifecycleCut
        val matrix = List(16) { if (it % 5 == 0) 1.0 else 0.0 }
        val context = NativeCaptureRecoveryContextV2(cut.sessionId, cut.groupId, 0, cut.groupGeneration,
            original.accepted.lane.name.lowercase(), 1, 0, 1, listOf(0.0, 0.0, 0.0), listOf(0.0, 0.0, 0.0, 1.0),
            matrix, matrix, matrix, matrix)
        return CaptureCommitRequest(original.accepted, emptyList(), 0, original.poseRecordHash,
            original.cameraModelHash, original.validationRecordHash, original.ledgerRecordHash, context)
    }
    private class CountedInput(bytes: ByteArray) : ByteArrayInputStream(bytes) {
        var closes = 0
        override fun close() { closes++; super.close() }
    }
    private class Exposure : SharedCameraExposurePortV2 {
        lateinit var callback: SharedCameraExposureCallbackV2
        lateinit var qualifier: CaptureAttemptQualifierV2
        var requests = 0
        override fun requestExposure(qualifier: CaptureAttemptQualifierV2, required: Set<CaptureComponentKind>, callback: SharedCameraExposureCallbackV2): Boolean {
            this.qualifier = qualifier; this.callback = callback; requests++; return true
        }
        override fun cancelExposure(qualifier: CaptureAttemptQualifierV2) = Unit
    }
}
