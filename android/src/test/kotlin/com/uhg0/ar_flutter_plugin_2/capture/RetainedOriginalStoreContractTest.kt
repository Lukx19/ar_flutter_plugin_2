package com.uhg0.ar_flutter_plugin_2.capture

import java.io.ByteArrayInputStream
import java.io.File
import java.security.MessageDigest
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

/** Real durable-store retention behavior; no fake store or accepted-quality publication. */
class RetainedOriginalStoreContractTest {
    private val directories = mutableListOf<File>()
    @After fun cleanUp() { directories.forEach { it.deleteRecursively() } }

    @Test fun `low score original and bound quality survive real store close and reopen`() {
        val root = directory()
        val jpeg = byteArrayOf(0xff.toByte(), 0xd8.toByte(), 23, 42, 0xff.toByte(), 0xd9.toByte())
        val request = request(jpeg, quality())
        val budget = budget(root)
        val store = store(root, budget)
        store.acceptCaptureBeforeExposure(request)
        val committed = store.commitStreamedAssessed(request, streams(jpeg))
        val terminal = committed.terminal!!
        assertEquals(CaptureTerminalKind.COMMITTED_PICTURE, terminal.kind)
        assertEquals(CaptureQualityDispositionV1.REVIEW_REQUIRED, terminal.qualityAssessment!!.disposition)
        assertEquals(58.6645, terminal.qualityAssessment!!.score!!, 0.0)
        assertEquals(110.0, terminal.qualityAssessment!!.threshold!!, 0.0)
        val rootFile = File(root, "store").walkTopDown().single { it.name == "${terminal.manifestId}.root" }
        val metadata = rootFile.inputStream().use { CaptureRootMetadataReaderV2().root(terminal.manifestId!!, it::read) }!!
        assertEquals("6", metadata.schema)
        assertEquals(request.qualityAssessment, metadata.qualityAssessment)
        assertEquals(terminal.qualityAssessmentHash, metadata.qualityAssessmentHash)
        assertEquals(sha(jpeg).toCaptureHashHex(), metadata.components.single().hash)
        val original = File(root, "store").walkTopDown().single { it.name == "jpeg.${sha(jpeg).toCaptureHashHex()}.blob" }
        assertArrayEquals(jpeg, original.readBytes())
        store.close(); budget.close()
        val reopenedBudget = budget(root)
        val reopened = store(root, reopenedBudget)
        reopened.recover()
        val recovered = reopened.queryReceipt(request.accepted.identity)!!
        assertEquals(committed, recovered)
        val projected = reopened.recoverAndProject().single()
        assertEquals(request.qualityAssessment, projected.qualityAssessment)
        assertEquals(terminal.qualityAssessmentHash, projected.qualityAssessmentHash)
        assertArrayEquals(jpeg, original.readBytes())
        reopened.close(); reopenedBudget.close()
    }

    @Test fun `same identity assessment component and exposure changes conflict without rewriting original`() {
        val root = directory()
        val jpeg = byteArrayOf(1, 2, 3, 4)
        val original = request(jpeg, quality())
        val budget = budget(root)
        val store = store(root, budget)
        store.acceptCaptureBeforeExposure(original)
        val committed = store.commitStreamedAssessed(original, streams(jpeg))
        assertEquals(committed, store.commitStreamedAssessed(original, emptyList()))
        val repeated = CountedInput(jpeg)
        assertThrows(DurableStoreConflictV2::class.java) {
            store.commitStreamedAssessed(original, listOf(CaptureComponentStreamV2(CaptureComponentKind.JPEG, repeated)))
        }
        assertEquals(1, repeated.closes)
        for (changed in listOf(request(jpeg, quality(61.7403)), request(byteArrayOf(1, 2, 3, 5), quality()),
                request(jpeg, quality(), exposure = 123457L))) {
            assertThrows(DurableStoreConflictV2::class.java) { store.commitStreamedAssessed(changed, emptyList()) }
        }
        assertEquals(committed, store.queryReceipt(original.accepted.identity))
        assertArrayEquals(jpeg, File(root, "store").walkTopDown().single { it.name.endsWith(".blob") }.readBytes())
        store.close(); budget.close()
    }

    @Test fun `post output assessed replay rehashes bytes and conflicts on every bound input`() {
        val root = directory()
        val jpeg = byteArrayOf(7, 8, 9)
        val request = request(jpeg, null)
        val budget = budget(root)
        val store = store(root, budget)
        val template = template(request)
        store.acceptCaptureBeforeExposure(request)
        val originalInput = CountedInput(jpeg)
        val committed = store.commitStreamedFinalizedAssessed(template, 234L,
            listOf(CaptureComponentStreamV2(CaptureComponentKind.JPEG, originalInput)), quality())
        assertEquals(1, originalInput.closes)
        assertEquals(committed, store.commitStreamedFinalizedAssessed(template, 234L, streams(jpeg), quality()))
        assertThrows(DurableStoreConflictV2::class.java) {
            store.commitStreamedFinalizedAssessed(template, 234L, streams(jpeg), quality(61.7403))
        }
        assertThrows(DurableStoreConflictV2::class.java) {
            store.commitStreamedFinalizedAssessed(template, 235L, streams(jpeg), quality())
        }
        val changedInput = CountedInput(byteArrayOf(7, 8, 10))
        assertThrows(DurableStoreConflictV2::class.java) {
            store.commitStreamedFinalizedAssessed(template, 234L,
                listOf(CaptureComponentStreamV2(CaptureComponentKind.JPEG, changedInput)), quality())
        }
        assertEquals(1, changedInput.closes)
        assertThrows(DurableStoreConflictV2::class.java) {
            store.commitStreamedFinalizedAssessed(template.copy(poseRecordHash = sha("changed-pose".toByteArray())),
                234L, streams(jpeg), quality())
        }
        assertThrows(IllegalStateException::class.java) {
            store.commitStreamedFinalizedAssessed(template, 234L, streams(ByteArray(1025)), quality())
        }
        assertThrows(DurableStoreConflictV2::class.java) { store.replayTemplateFenceBeforeExposure(template) }
        assertEquals(committed, store.replayAssessedTemplateFenceBeforeExposure(template))
        assertThrows(DurableStoreConflictV2::class.java) {
            store.replayAssessedTemplateFenceBeforeExposure(template.copy(poseRecordHash = sha("changed-pose".toByteArray())))
        }
        assertThrows(DurableStoreConflictV2::class.java) { store.commitStreamedFinalized(template, 234L, emptyList()) }
        assertEquals(committed, store.queryReceipt(request.accepted.identity))
        assertArrayEquals(jpeg, File(root, "store").walkTopDown().single { it.name.endsWith(".blob") }.readBytes())
        assertFalse(File(root, "store").walkTopDown().any { it.name == "staging" })
        store.close(); budget.close()
    }

    @Test fun `policy passed and explicit analysis unavailable remain different durable dispositions`() {
        for (assessment in listOf(
            CaptureQualityAssessmentV1(policyVersion = "jpeg-global-variance-v1",
                disposition = CaptureQualityDispositionV1.POLICY_PASSED, reason = CaptureQualityReasonV1.POLICY_PASSED,
                score = 120.0, threshold = 110.0, sampleWidth = 256, sampleHeight = 192),
            CaptureQualityAssessmentV1(policyVersion = "jpeg-global-variance-v1",
                disposition = CaptureQualityDispositionV1.REVIEW_REQUIRED, reason = CaptureQualityReasonV1.ANALYSIS_UNAVAILABLE,
                threshold = 110.0, unavailableReason = CaptureQualityUnavailableReasonV1.DECODE_FAILED),
        )) {
            val root = directory()
            val jpeg = byteArrayOf(12, 34)
            val request = request(jpeg, assessment)
            val budget = budget(root)
            val store = store(root, budget)
            store.acceptCaptureBeforeExposure(request)
            val committed = store.commitStreamedAssessed(request, streams(jpeg))
            store.close(); budget.close()
            val reopenedBudget = budget(root)
            val reopened = store(root, reopenedBudget)
            assertEquals(assessment, reopened.queryReceipt(request.accepted.identity)!!.terminal!!.qualityAssessment)
            assertEquals(committed.terminal!!.qualityAssessmentHash, reopened.recoverAndProject().single().qualityAssessmentHash)
            reopened.close(); reopenedBudget.close()
        }
    }

    @Test fun `review retained jpeg dng pair keeps exact hashes lengths and bytes through reopen`() {
        val root = directory()
        val jpeg = byteArrayOf(1, 23, 45)
        val dng = byteArrayOf(67, 89, 10, 11)
        val request = request(jpeg, quality(), dng = dng)
        val budget = budget(root)
        val store = store(root, budget)
        store.acceptCaptureBeforeExposure(request)
        val committed = store.commitStreamedAssessed(request, listOf(
            CaptureComponentStreamV2(CaptureComponentKind.JPEG, ByteArrayInputStream(jpeg)),
            CaptureComponentStreamV2(CaptureComponentKind.DNG, ByteArrayInputStream(dng)),
        ))
        assertEquals(jpeg.size.toLong(), committed.terminal!!.jpegSizeBytes)
        assertEquals(dng.size.toLong(), committed.terminal!!.dngSizeBytes)
        assertThrows(DurableStoreConflictV2::class.java) {
            store.commitStreamedAssessed(request(jpeg, quality(), dng = byteArrayOf(67, 89, 10, 12)), emptyList())
        }
        store.close(); budget.close()
        val reopenedBudget = budget(root)
        val reopened = store(root, reopenedBudget)
        assertEquals(committed, reopened.queryReceipt(request.accepted.identity))
        val originals = File(root, "store").walkTopDown().filter { it.name.endsWith(".blob") }.toList()
        assertEquals(2, originals.size)
        assertArrayEquals(jpeg, originals.single { it.name == "jpeg.${sha(jpeg).toCaptureHashHex()}.blob" }.readBytes())
        assertArrayEquals(dng, originals.single { it.name == "dng.${sha(dng).toCaptureHashHex()}.blob" }.readBytes())
        val rootFile = File(root, "store").walkTopDown().single { it.extension == "root" }
        val metadata = rootFile.inputStream().use { CaptureRootMetadataReaderV2().root(committed.terminal!!.manifestId!!, it::read) }!!
        assertEquals(setOf(sha(jpeg).toCaptureHashHex(), sha(dng).toCaptureHashHex()), metadata.components.map { it.hash }.toSet())
        assertEquals(quality(), reopened.recoverAndProject().single().qualityAssessment)
        reopened.close(); reopenedBudget.close()
    }

    @Test fun `assessed receipt identity revision and jpeg size contradictions refuse query and projection`() {
        for (corruption in listOf("captureId", "revision", "jpegSizeBytes", "missingJpegSizeBytes", "malformedJpegSizeBytes", "unexpectedDngSizeBytes")) {
            val root = directory()
            val jpeg = byteArrayOf(17, 29, 43, 59)
            val request = request(jpeg, quality())
            val budget = budget(root)
            val store = store(root, budget)
            store.acceptCaptureBeforeExposure(request)
            val committed = store.commitStreamedAssessed(request, streams(jpeg))
            val terminal = requireNotNull(committed.terminal)
            val original = File(root, "store").walkTopDown().single { it.name.endsWith(".blob") }.readBytes()
            store.close(); budget.close()
            val receipt = File(root, "store").walkTopDown().single { it.name == "receipt.properties" }
            val text = receipt.readText()
            receipt.writeText(when (corruption) {
                "captureId" -> text.replace("captureId=${terminal.captureId}", "captureId=${"0".repeat(64)}")
                "revision" -> text.replace("revision=${terminal.captureRevision}", "revision=${terminal.captureRevision!! + 1}")
                "jpegSizeBytes" -> text.replace("jpegSizeBytes=${jpeg.size}", "jpegSizeBytes=${jpeg.size + 1}")
                "missingJpegSizeBytes" -> text.lineSequence().filterNot { it.startsWith("jpegSizeBytes=") }.joinToString("\n", postfix = "\n")
                "malformedJpegSizeBytes" -> text.replace("jpegSizeBytes=${jpeg.size}", "jpegSizeBytes=not-a-size")
                else -> text + "dngSizeBytes=9\n"
            })
            val reopenedBudget = budget(root)
            val reopened = store(root, reopenedBudget)
            assertThrows(DurableStoreConflictV2::class.java) { reopened.queryReceipt(request.accepted.identity) }
            assertThrows(DurableStoreConflictV2::class.java) { reopened.recoverAndProject() }
            assertArrayEquals(original, File(root, "store").walkTopDown().single { it.name.endsWith(".blob") }.readBytes())
            reopened.close(); reopenedBudget.close()
        }
    }

    @Test fun `assessed jpeg dng pair rejects missing or malformed dng size after reopen`() {
        for (corruption in listOf("missingDngSizeBytes", "malformedDngSizeBytes")) {
            val root = directory()
            val jpeg = byteArrayOf(3, 5, 7)
            val dng = byteArrayOf(11, 13, 17, 19)
            val request = request(jpeg, quality(), dng = dng)
            val budget = budget(root)
            val store = store(root, budget)
            store.acceptCaptureBeforeExposure(request)
            val committed = store.commitStreamedAssessed(request, listOf(
                CaptureComponentStreamV2(CaptureComponentKind.JPEG, ByteArrayInputStream(jpeg)),
                CaptureComponentStreamV2(CaptureComponentKind.DNG, ByteArrayInputStream(dng)),
            ))
            val originalJpeg = File(root, "store").walkTopDown().single { it.name.startsWith("jpeg.") }.readBytes()
            val originalDng = File(root, "store").walkTopDown().single { it.name.startsWith("dng.") }.readBytes()
            store.close(); budget.close()
            val receipt = File(root, "store").walkTopDown().single { it.name == "receipt.properties" }
            val text = receipt.readText()
            receipt.writeText(if (corruption == "missingDngSizeBytes") {
                text.lineSequence().filterNot { it.startsWith("dngSizeBytes=") }.joinToString("\n", postfix = "\n")
            } else {
                text.replace("dngSizeBytes=${dng.size}", "dngSizeBytes=not-a-size")
            })
            val reopenedBudget = budget(root)
            val reopened = store(root, reopenedBudget)
            assertThrows(DurableStoreConflictV2::class.java) { reopened.queryReceipt(request.accepted.identity) }
            assertThrows(DurableStoreConflictV2::class.java) { reopened.recoverAndProject() }
            assertArrayEquals(originalJpeg, File(root, "store").walkTopDown().single { it.name.startsWith("jpeg.") }.readBytes())
            assertArrayEquals(originalDng, File(root, "store").walkTopDown().single { it.name.startsWith("dng.") }.readBytes())
            assertEquals(committed.terminal!!.dngSizeBytes, dng.size.toLong())
            reopened.close(); reopenedBudget.close()
        }
    }

    @Test fun `new receipt missing quality conflicts and unchanged legacy remains unassessed`() {
        val jpeg = byteArrayOf(11, 22, 33)
        val assessedRoot = directory()
        val assessedBudget = budget(assessedRoot)
        val assessedStore = store(assessedRoot, assessedBudget)
        val assessedRequest = request(jpeg, quality())
        assessedStore.acceptCaptureBeforeExposure(assessedRequest)
        assessedStore.commitStreamedAssessed(assessedRequest, streams(jpeg))
        val receipt = File(assessedRoot, "store").walkTopDown().single { it.name == "receipt.properties" }
        receipt.writeText(receipt.readLines().filterNot { it.startsWith("qualityDisposition=") }.joinToString("\n", postfix = "\n"))
        assertThrows(DurableStoreConflictV2::class.java) { assessedStore.queryReceipt(assessedRequest.accepted.identity) }
        assertThrows(DurableStoreConflictV2::class.java) { assessedStore.recoverAndProject() }
        assessedStore.close(); assessedBudget.close()

        val legacyRoot = directory()
        val legacyBudget = budget(legacyRoot)
        val legacyStore = store(legacyRoot, legacyBudget)
        val legacy = request(jpeg, null)
        legacyStore.acceptCaptureBeforeExposure(legacy)
        val committed = legacyStore.commitStreamed(legacy, streams(jpeg))
        assertNull(committed.terminal!!.qualityAssessment)
        val rootFile = File(legacyRoot, "store").walkTopDown().single { it.extension == "root" }
        assertTrue(rootFile.readText().startsWith("schema=5\n"))
        assertFalse(rootFile.readText().contains("quality"))
        assertThrows(IllegalArgumentException::class.java) { legacyStore.commitStreamed(assessedRequest, emptyList()) }
        assertThrows(IllegalArgumentException::class.java) { legacyStore.commitStreamedAssessed(legacy, emptyList()) }
        legacyStore.close(); legacyBudget.close()
        val reopenedBudget = budget(legacyRoot)
        val reopened = store(legacyRoot, reopenedBudget)
        assertEquals(committed, reopened.queryReceipt(legacy.accepted.identity))
        assertNull(reopened.recoverAndProject().single().qualityAssessment)
        reopened.close(); reopenedBudget.close()
    }

    private fun template(request: CaptureCommitRequest) = CapturePostOutputTemplateV2(request.accepted,
        request.poseRecordHash, request.cameraModelHash, request.validationRecordHash, request.ledgerRecordHash)
    private class CountedInput(bytes: ByteArray) : ByteArrayInputStream(bytes) {
        var closes = 0
        override fun close() { closes++; super.close() }
    }

    private fun quality(score: Double = 58.6645) = CaptureQualityAssessmentV1(
        policyVersion = "jpeg-global-variance-v1",
        disposition = CaptureQualityDispositionV1.REVIEW_REQUIRED,
        reason = CaptureQualityReasonV1.BELOW_THRESHOLD,
        score = score, threshold = 110.0, sampleWidth = 256, sampleHeight = 192,
    )
    private fun request(jpeg: ByteArray, quality: CaptureQualityAssessmentV1?, exposure: Long = 123456L,
        dng: ByteArray? = null): CaptureCommitRequest {
        val identity = CaptureAttemptIdentity("retained-attempt", "retained-commit", 1,
            CaptureLifecycleCut("retained-session", 1, "group", 1, "ar", "view", 1, "binding", 1, 1))
        val kinds = if (dng == null) setOf(CaptureComponentKind.JPEG) else setOf(CaptureComponentKind.JPEG, CaptureComponentKind.DNG)
        val accepted = CaptureAcceptedAttempt(identity, CaptureLane.MANUAL,
            CaptureComponentProfile(if (dng == null) "jpeg" else "raw+jpeg", kinds, 1024, 1024),
            CaptureReservationLiability(0, 65536, kinds.size.toLong(), 1, 0, true), sha("intent".toByteArray()), sha("accepted".toByteArray()))
        return CaptureCommitRequest(accepted,
            listOfNotNull(CaptureComponentDescriptor(CaptureComponentKind.JPEG, jpeg.size.toLong(), sha(jpeg), sha(jpeg).toCaptureHashHex()),
                dng?.let { CaptureComponentDescriptor(CaptureComponentKind.DNG, it.size.toLong(), sha(it), sha(it).toCaptureHashHex()) }),
            exposure, sha("pose".toByteArray()), sha("camera".toByteArray()), sha("validation".toByteArray()), sha("ledger".toByteArray()),
            qualityAssessment = quality)
    }
    private fun streams(jpeg: ByteArray) = listOf(CaptureComponentStreamV2(CaptureComponentKind.JPEG, ByteArrayInputStream(jpeg)))
    private fun store(root: File, budget: StorageBudgetCoordinatorV2) =
        DurableSessionStoreV2(File(root, "store"), budget, filesystemBackend = JvmDescriptorFilesystemV2())
    private fun budget(root: File) = StorageBudgetCoordinatorV2(File(root, "budget"), StorageBudgetPolicyV2(1024 * 1024, 0),
        JvmDescriptorFilesystemV2(authoritativeAllocationUnit = { 4096L })) { 1024 * 1024 }
    private fun directory() = File.createTempFile("retained-original", "").also { it.delete(); assertTrue(it.mkdirs()); directories += it }
    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).map { it.toInt() and 0xff }
}
