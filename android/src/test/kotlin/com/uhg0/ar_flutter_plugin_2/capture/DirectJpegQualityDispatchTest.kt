package com.uhg0.ar_flutter_plugin_2.capture
import org.junit.Assert.*
import org.junit.Test
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class DirectJpegQualityDispatchTest {
    @get:Rule val retainedOriginalDirectory = TemporaryFolder()

    @Test fun lowScoreAcquiredOriginalReachesDurableStoreWithoutQualityAcceptance() {
        for (lane in CaptureLane.entries) {
            AdmittedJpegStoreFixture(retainedOriginalDirectory.newFolder(lane.name.lowercase()), lane).use { fixture ->
                val admitted = fixture.store.acceptCaptureBeforeExposure(fixture.request)
                assertEquals(CaptureAttemptPhase.RESERVED_ACCEPTED, admitted.phase)
                assertTrue(admitted.durable)
                assertEquals(fixture.request.accepted.reservation.totalStoreLiability,
                    fixture.budget.reservedBytes())
                val components = fixture.components()
                val failures = mutableListOf<String>()
                var handoffs = 0
                var quality: PreviewBlurQuality? = null
                var durable: CaptureReceipt? = null
                val task = DirectStoreDispatch(components,
                    { acquired ->
                        handoffs++
                        assertEquals(fixture.request.accepted.identity, acquired.qualifier.identity)
                        assertEquals(setOf(CaptureComponentKind.JPEG), acquired.streams.map { it.kind }.toSet())
                        assertEquals(CaptureQualityDispositionV1.REVIEW_REQUIRED, acquired.qualityAssessment?.disposition)
                        durable = fixture.store.commitStreamedFinalizedAssessed(fixture.template,
                            acquired.exposureTimestampNanoseconds, acquired.streams, requireNotNull(acquired.qualityAssessment))
                    },
                    { failures.add(it) },
                    { PreviewBlurQuality(58.6645, 516, 387, "jpegLaplacianVarianceV1") },
                    { quality = it }, retainAssessedOriginals = true)
                task.run()
                task.run()
                assertEquals("Original component stream must close exactly once", 1, fixture.closed.get())
                val measured = requireNotNull(quality)
                assertEquals(58.6645, measured.blurScore, 0.0)
                assertTrue("Retention cannot declare a below-threshold image policy-passed",
                    measured.blurScore < JpegBlurAnalyzer.ProductionThreshold)
                // The frozen baseline failed here before the explicit retained-review opt-in.
                assertEquals("Low-score original never reached durable handoff ($lane); failures=$failures",
                    1, handoffs)
                assertTrue("Complete acquired original must not become acquisition failure: $failures",
                    failures.isEmpty())
                assertNotNull("Low-score acquired original has no durable retained receipt", durable)
                val receipt = requireNotNull(durable)
                assertTrue(receipt.durable)
                assertEquals(fixture.request.accepted.identity, receipt.identity)
                assertEquals(CaptureAttemptPhase.COMMITTED_PICTURE, receipt.phase)
                assertEquals(receipt, fixture.store.queryReceipt(receipt.identity))
                assertEquals(CaptureQualityDispositionV1.REVIEW_REQUIRED, receipt.terminal?.qualityAssessment?.disposition)
                assertNotNull(receipt.terminal?.qualityAssessmentHash)
                fixture.assertOriginal(receipt)
                fixture.reopen()
                val recovered = fixture.store.queryReceipt(receipt.identity)
                assertEquals("Receipt/hash must survive close and reopen unchanged", receipt, recovered)
                fixture.assertOriginal(requireNotNull(recovered))
                val projection = fixture.store.recoverAndProject().single()
                assertEquals(receipt.terminal, projection.verifiedTerminal)
                assertEquals(receipt.identity, projection.verifiedTerminal?.identity)
                assertEquals(receipt.terminal?.qualityAssessmentHash, projection.qualityAssessmentHash)
                assertEquals(1, fixture.closed.get())
            }
        }
    }

    @Test fun unavailableAnalysisRetainsOriginalWithExplicitEvidenceAndNoQualityAcceptance() {
        for (mode in listOf("nonfinite", "analysisException")) for (lane in CaptureLane.entries) {
            AdmittedJpegStoreFixture(retainedOriginalDirectory.newFolder("$mode-${lane.name}"), lane).use { fixture ->
                fixture.store.acceptCaptureBeforeExposure(fixture.request, requireRecoveryIdentity = true)
                var receipt: CaptureReceipt? = null
                val task = DirectStoreDispatch(fixture.components(), { acquired ->
                    val assessment = requireNotNull(acquired.qualityAssessment)
                    assertEquals(CaptureQualityDispositionV1.REVIEW_REQUIRED, assessment.disposition)
                    assertEquals(CaptureQualityReasonV1.ANALYSIS_UNAVAILABLE, assessment.reason)
                    assertEquals(if (mode == "nonfinite") CaptureQualityUnavailableReasonV1.EVIDENCE_MISSING else
                        CaptureQualityUnavailableReasonV1.ANALYSIS_FAILED, assessment.unavailableReason)
                    assertNull(assessment.score)
                    receipt = fixture.store.commitStreamedFinalizedAssessed(fixture.template,
                        acquired.exposureTimestampNanoseconds, acquired.streams, assessment)
                }, { fail("Analysis unavailability became acquisition failure: $it") }, {
                    if (mode == "analysisException") throw IllegalStateException("analysis unavailable")
                    PreviewBlurQuality(Double.NaN, 516, 387)
                }, retainAssessedOriginals = true)
                task.run(); task.run()
                fixture.assertOriginal(requireNotNull(receipt))
                assertEquals(1, fixture.closed.get())
            }
        }
    }

    @Test fun retainedHandoffCallbackFailureClosesOriginalExactlyOnce() {
        val closes = AtomicInteger()
        val failures = mutableListOf<String>()
        val task = DirectStoreDispatch(components(listOf(CaptureComponentKind.JPEG), closes), { acquired ->
            acquired.streams.single().input.close()
            error("callback failure after close")
        }, failures::add, { PreviewBlurQuality(58.6645, 516, 387) }, retainAssessedOriginals = true)
        task.run(); task.run(); task.cancel("late-cancel")
        assertEquals(listOf("component-dispatch"), failures)
        assertEquals(1, closes.get())
    }

    private fun components(kinds: List<CaptureComponentKind>, closed: AtomicInteger): SharedCameraComponentSetV2 {
        val cut = CaptureLifecycleCut("session", 1, "group", 1, "ar", "view", 1, "binding", 1, 1)
        val identity = CaptureAttemptIdentity("attempt", "commit", 1, cut)
        val qualifier = CaptureAttemptQualifierV2(identity, cut, 1)
        return SharedCameraComponentSetV2(qualifier, kinds.map { kind ->
            CaptureComponentStreamV2(kind,
                CameraPlaneInputStreamV2(ByteBuffer.wrap(byteArrayOf(1, 2, 3, 4))) { closed.incrementAndGet() })
        }, 1)
    }

    @Test fun qualifiedJpegThresholdHandsOriginalComponentsToStoreUnchanged() {
        val closed = AtomicInteger()
        var commits = 0
        val task = DirectStoreDispatch(
            components(listOf(CaptureComponentKind.JPEG, CaptureComponentKind.DNG), closed),
            { set ->
                commits++
                set.streams.forEach { stream ->
                    assertArrayEquals(byteArrayOf(1, 2, 3, 4), stream.input.readBytes())
                    stream.input.close()
                }
            },
            { fail("Unexpected failure: $it") },
            { PreviewBlurQuality(110.0, 516, 387, "jpegLaplacianVarianceV1") })
        task.run()
        task.run()
        assertEquals(1, commits)
        assertEquals(2, closed.get())
    }

    @Test fun blurAndUnavailableEvaluationCloseBothComponentsWithoutStoreHandoff() {
        for (mode in listOf("blur", "nonfinite", "decoder")) {
            val closed = AtomicInteger()
            val failures = mutableListOf<String>()
            val task = DirectStoreDispatch(
                components(listOf(CaptureComponentKind.JPEG, CaptureComponentKind.DNG), closed),
                { fail("Rejected JPEG reached store") }, { failures.add(it) },
                { when (mode) {
                    "decoder" -> throw IllegalStateException("decoder unavailable")
                    "nonfinite" -> PreviewBlurQuality(Double.NaN, 516, 387)
                    else -> PreviewBlurQuality(109.0, 516, 387)
                } })
            task.run()
            task.run()
            assertEquals(listOf(if (mode == "blur") "REJECTED_BLUR" else "QUALITY_ANALYSIS_FAILED"), failures)
            assertEquals(2, closed.get())
        }
    }

    @Test fun queuedCancellationClosesComponentsOnceWithoutQualityOrStoreWork() {
        val closed = AtomicInteger()
        val failures = mutableListOf<String>()
        val task = DirectStoreDispatch(components(listOf(CaptureComponentKind.JPEG), closed),
            { fail("Cancelled work reached store") }, { failures.add(it) },
            { error("Cancelled work evaluated JPEG") })
        task.cancel("capture-disposed")
        task.cancel("capture-disposed")
        task.run()
        assertEquals(listOf("capture-disposed"), failures)
        assertEquals(1, closed.get())
    }

    @Test fun cancellationAtQualityPublicationPreventsStoreOwnershipClaim() {
        val closed = AtomicInteger()
        val failures = mutableListOf<String>()
        lateinit var task: DirectStoreDispatch
        task = DirectStoreDispatch(components(listOf(CaptureComponentKind.JPEG), closed),
            { fail("Cancelled quality result reached store") }, { failures.add(it) },
            { PreviewBlurQuality(200.0, 516, 387) },
            { task.cancel("capture-disposed") })
        task.run()
        assertEquals(listOf("capture-disposed"), failures)
        assertEquals(1, closed.get())
    }

    @Test fun fundedExposurePortForwardsExactWorkspaceWithoutChangingLegacyHook() {
        val closed = AtomicInteger()
        val qualifier = components(listOf(CaptureComponentKind.JPEG), closed).qualifier
        val required = setOf(CaptureComponentKind.JPEG)
        var legacyRequests = 0
        var fundedBytes = 0L
        val callback = object : SharedCameraExposureCallbackV2 {
            override fun onComponents(components: SharedCameraComponentSetV2) = Unit
            override fun onFailure(qualifier: CaptureAttemptQualifierV2, reason: String) = Unit
        }
        val port = SharedCameraManagerExposurePortV2(
            request = { _, _, _ -> legacyRequests++; true },
            cancel = {},
            fundedRequest = { actual, kinds, sink, bytes ->
                assertEquals(qualifier, actual)
                assertEquals(required, kinds)
                assertSame(callback, sink)
                fundedBytes = bytes
                true
            })
        assertTrue(port.requestExposure(qualifier, required, callback, JpegBlurAnalyzer.WorkingMemoryBytes))
        assertEquals(8L * 1024L * 1024L, fundedBytes)
        assertEquals(0, legacyRequests)
        assertTrue(port.requestExposure(qualifier, required, callback))
        assertEquals(1, legacyRequests)
    }

    @Test fun runningCancellationRetainsSourceUntilQualityExitsThenRefusesHandoffOnce() {
        val closed = AtomicInteger()
        val failures = mutableListOf<String>()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val worker = Executors.newSingleThreadExecutor()
        try {
            val task = DirectStoreDispatch(components(listOf(CaptureComponentKind.JPEG), closed),
                { fail("Cancelled work reached store") }, { failures.add(it) },
                { cancelled ->
                    entered.countDown()
                    assertTrue(release.await(2, TimeUnit.SECONDS))
                    assertTrue(cancelled())
                    PreviewBlurQuality(200.0, 516, 387)
                })
            val pending = worker.submit(task)
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            task.cancel("capture-disposed")
            assertEquals(0, closed.get())
            release.countDown()
            pending.get(2, TimeUnit.SECONDS)
            task.cancel("capture-disposed")
            assertEquals(listOf("capture-disposed"), failures)
            assertEquals(1, closed.get())
        } finally {
            release.countDown()
            worker.shutdownNow()
            assertTrue(worker.awaitTermination(2, TimeUnit.SECONDS))
        }
    }
}
