package com.uhg0.ar_flutter_plugin_2.capture

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AssessedRecoveryIdentityTest {
    @get:Rule val directory = TemporaryFolder()

    @Test fun exactFullIdentityAndBoundQualitySurviveNewAdmissionReceiptAndReopen() {
        AdmittedJpegStoreFixture(directory.newFolder(), CaptureLane.MANUAL).use { fixture ->
            val old = fixture.request.accepted
            val cut = CaptureLifecycleCut("exact-session", 4, "exact-group", 8, "exact-ar-session", "exact-view", 12,
                "exact-binding", 16, 20)
            val identity = CaptureAttemptIdentity("exact-attempt", "exact-commit", 42, cut)
            val accepted = CaptureAcceptedAttempt(identity, old.lane, old.profile, old.reservation,
                old.canonicalIntentHash, old.acceptedReceiptHash)
            val template = CapturePostOutputTemplateV2(accepted, fixture.request.poseRecordHash, fixture.request.cameraModelHash,
                fixture.request.validationRecordHash, fixture.request.ledgerRecordHash)
            val request = CaptureCommitRequest(accepted, emptyList(), 0, template.poseRecordHash, template.cameraModelHash,
                template.validationRecordHash, template.ledgerRecordHash)
            fixture.store.acceptCaptureBeforeExposure(request, requireRecoveryIdentity = true)
            val acceptedFile = fixture.storeRoot.walkTopDown().single { it.name == "accepted.properties" }
            assertTrue(acceptedFile.length() <= NativeCaptureReservationBoundsV2.ACCEPTED_RECORD_BYTES)
            assertTrue(acceptedFile.length() <= fixture.budget.reservedBytes())
            val committed = fixture.store.commitStreamedFinalizedAssessed(template, 1234, fixture.components().streams, quality())
            fixture.assertOriginal(committed)
            fixture.reopen()
            val projection = fixture.store.recoverAndProject().single()
            assertEquals(identity, projection.verifiedTerminal?.identity)
            assertEquals(committed.terminal, projection.verifiedTerminal)
            assertEquals(committed.terminal?.canonicalTerminalHash, projection.verifiedTerminal?.canonicalTerminalHash)
            assertEquals(committed.terminal?.qualityAssessment, projection.qualityAssessment)
            assertEquals(committed.terminal?.qualityAssessmentHash, projection.qualityAssessmentHash)
            assertEquals(committed, fixture.store.queryReceipt(identity))
            val changedCut = accepted.identity.copy(lifecycleCut = cut.copy(bindingToken = "changed-binding"))
            val changedAccepted = CaptureAcceptedAttempt(changedCut, old.lane, old.profile, old.reservation,
                old.canonicalIntentHash, old.acceptedReceiptHash)
            assertThrows(DurableStoreConflictV2::class.java) {
                fixture.store.replayAssessedTemplateFenceBeforeExposure(template.copy(accepted = changedAccepted))
            }
        }
    }

    @Test fun malformedMissingOrUnknownExactIdentityNeverBecomesRecoveredAssessedAuthority() {
        for (mode in listOf("missing", "unknownVersion", "malformed")) {
            AdmittedJpegStoreFixture(directory.newFolder(mode), CaptureLane.MANUAL).use { fixture ->
                fixture.store.acceptCaptureBeforeExposure(fixture.request, requireRecoveryIdentity = true)
                fixture.store.commitStreamedFinalizedAssessed(fixture.template, 1234, fixture.components().streams, quality())
                val file = fixture.storeRoot.walkTopDown().single { it.name == "accepted.properties" }
                val text = file.readText()
                file.writeText(when (mode) {
                    "missing" -> text.lineSequence().filterNot { it.startsWith("acceptedIdentity") }.joinToString("\n", postfix = "\n")
                    "unknownVersion" -> text.replace("acceptedIdentityVersion=1", "acceptedIdentityVersion=2")
                    else -> text.replace(Regex("acceptedIdentity=[^\n]+"), "acceptedIdentity=invalid")
                })
                fixture.reopen()
                assertThrows(DurableStoreConflictV2::class.java) { fixture.store.recoverAndProject() }
            }
        }
    }

    @Test fun oldLegacyAcceptanceRemainsReadableWithNoInventedRecoveredCut() {
        AdmittedJpegStoreFixture(directory.newFolder(), CaptureLane.MANUAL).use { fixture ->
            fixture.store.acceptCaptureBeforeExposure(fixture.request)
            val committed = fixture.store.commitStreamedFinalized(fixture.template, 1234, fixture.components().streams)
            val file = fixture.storeRoot.walkTopDown().single { it.name == "accepted.properties" }
            file.writeText(file.readLines().filterNot { it.startsWith("acceptedIdentity") }.joinToString("\n", postfix = "\n"))
            val oldBytes = file.readBytes()
            fixture.reopen()
            val projection = fixture.store.recoverAndProject().single()
            assertNull(projection.verifiedTerminal)
            assertNull(projection.qualityAssessment)
            assertArrayEquals(oldBytes, file.readBytes())
            assertEquals(committed, fixture.store.queryReceipt(committed.identity))
        }
    }

    @Test fun oversizedExactIdentityRefusesAcceptanceAndReleasesReservation() {
        AdmittedJpegStoreFixture(directory.newFolder(), CaptureLane.MANUAL).use { fixture ->
            val old = fixture.request.accepted
            val identity = old.identity.copy(lifecycleCut = old.identity.lifecycleCut.copy(bindingToken = "b".repeat(50_000)))
            val accepted = CaptureAcceptedAttempt(identity, old.lane, old.profile, old.reservation,
                old.canonicalIntentHash, old.acceptedReceiptHash)
            val request = CaptureCommitRequest(accepted, emptyList(), 0, fixture.request.poseRecordHash,
                fixture.request.cameraModelHash, fixture.request.validationRecordHash, fixture.request.ledgerRecordHash)
            assertThrows(IllegalStateException::class.java) { fixture.store.acceptCaptureBeforeExposure(request, requireRecoveryIdentity = true) }
            assertEquals(0L, fixture.budget.reservedBytes())
            assertFalse(fixture.storeRoot.walkTopDown().any { it.name == "accepted.properties" })
        }
    }

    private fun quality() = CaptureQualityAssessmentV1(policyVersion = "jpegLaplacianVarianceV1:threshold110",
        disposition = CaptureQualityDispositionV1.REVIEW_REQUIRED, reason = CaptureQualityReasonV1.BELOW_THRESHOLD,
        score = 58.6645, threshold = 110.0, sampleWidth = 516, sampleHeight = 387)
}
