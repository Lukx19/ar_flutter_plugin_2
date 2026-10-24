package com.uhg0.ar_flutter_plugin_2.capture

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.security.MessageDigest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeCapturePreviewReaderV2Test {
    @Test
    fun `materializes a smaller preview after original digest validation without rewriting source`() {
        val root = canonicalTempDirectory("preview-reader")
        try {
            val image = ByteArray(96 * 1024 + 17) { index -> (index * 31).toByte() }
            val componentHash = sha256(image)
            val requestHash = "d".repeat(64)
            val captureId = "b".repeat(64)
            val rootText = """
                schema=5
                revision=1
                request=$requestHash
                previous=-
                secondPrevious=-
                component=JPEG:${image.size}:$componentHash
            """.trimIndent() + "\n"
            val manifestId = sha256(rootText.toByteArray())
            val session = File(root, "sessions/session").apply { mkdirs() }
            File(session, "root-A.ptr").writeText("1\n$manifestId\n${"e".repeat(64)}\n")
            File(session, "objects/$manifestId.root").apply {
                parentFile!!.mkdirs()
                writeText(rootText)
            }
            File(session, "assets/$captureId/jpeg.$componentHash.blob").apply {
                parentFile!!.mkdirs()
                writeBytes(image)
            }

            writeReceipt(session, manifestId, captureId, requestHash, 1, image.size)
            val target = File(root, "preview.jpg")
            val thumbnail = image.copyOf(16)
            val reader = NativeCapturePreviewReaderV2(root,
                { _, destination, _, _ -> destination.writeBytes(thumbnail); true },
                { file, _ -> file.isFile && file.readBytes().contentEquals(thumbnail) },
            )
            assertTrue(reader.materializeJpegPreview(manifestId, captureId, target))
            assertArrayEquals(thumbnail, target.readBytes())
            assertArrayEquals(image, File(session, "assets/$captureId/jpeg.$componentHash.blob").readBytes())

            // Original inspection must bypass thumbnail recompression and repair
            // corrupt cache only from this identity-qualified durable JPEG.
            val original = File(root, "original.jpg")
            assertTrue(reader.materializeJpegPreview(manifestId, captureId, original, edge = 0))
            assertArrayEquals(image, original.readBytes())
            original.writeBytes(ByteArray(image.size) { 7 })
            assertTrue(reader.materializeJpegPreview(manifestId, captureId, original, edge = 0))
            assertArrayEquals(image, original.readBytes())
            assertFalse(reader.materializeJpegPreview(manifestId, "c".repeat(64), original, edge = 0))
            assertFalse(reader.materializeJpegPreview(manifestId, captureId, original, { true }, edge = 0))

            val retainedRoot = File(session, "objects/$manifestId.root")
            retainedRoot.writeText(rootText.replace("revision=1", "revision=2"))
            assertFalse(reader.materializeJpegPreview(manifestId, captureId, target))
            retainedRoot.writeText(rootText)
            assertTrue(reader.materializeJpegPreview(manifestId, captureId, target))
            File(session, "root-A.ptr").writeText("2\n$manifestId\n${"e".repeat(64)}\n")
            assertFalse(reader.materializeJpegPreview(manifestId, captureId, target))
            File(session, "root-A.ptr").writeText("1\n$manifestId\n${"e".repeat(64)}\n")

            // A damaged display copy is replaced only after its durable hash
            // fails; the source bytes remain the immutable authority.
            target.writeBytes(byteArrayOf(1, 2, 3))
            assertTrue(reader.materializeJpegPreview(manifestId, captureId, target))
            assertArrayEquals(thumbnail, target.readBytes())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `copy workspace resets digest state and keeps a 64 kib transfer buffer`() {
        val workspace = CaptureCopyWorkspace()
        assertEquals(64 * 1024, workspace.capacityBytes)
        assertThrows(IllegalArgumentException::class.java) {
            CaptureCopyWorkspace(64 * 1024 + 1)
        }

        val first = copy(workspace, "first-component".toByteArray())
        val second = copy(workspace, "second-component".toByteArray())

        assertEquals(15L, first.length)
        assertEquals(16L, second.length)
        assertArrayEquals(digest("first-component".toByteArray()), first.digest)
        assertArrayEquals(digest("second-component".toByteArray()), second.digest)
    }

    @Test fun `older than three roots repairs once from committed ancestor with bounded preview bytes`() {
        val root = canonicalTempDirectory("older-preview")
        try {
            val fixture = committedChain(root)
            var writes = 0
            val thumbnail = byteArrayOf(9, 8, 7, 6)
            val reader = NativeCapturePreviewReaderV2(root,
                { _, target, _, _ -> writes++; target.writeBytes(thumbnail); true },
                { target, _ -> target.isFile && target.readBytes().contentEquals(thumbnail) },
            )
            val target = File(root, "display.jpg")
            repeat(2) { assertTrue(reader.materializeJpegPreview(fixture.first, fixture.second, target)) }
            assertEquals(1, writes)
            assertArrayEquals(thumbnail, target.readBytes())
            assertArrayEquals(fixture.third, File(root, "sessions/session/assets/${fixture.second}/jpeg.${sha256(fixture.third)}.blob").readBytes())
        } finally { root.deleteRecursively() }
    }

    @Test fun `a preview target under a symlinked folder is refused`() {
        val root = canonicalTempDirectory("symlinked-preview")
        try {
            val fixture = committedChain(root)
            var writes = 0
            val reader = NativeCapturePreviewReaderV2(root, { _, target, _, _ -> writes++; target.writeBytes(byteArrayOf(1)); true },
                { _, _ -> false })
            val link = Files.createSymbolicLink(File(root, "linked").toPath(), File(root, "real").apply { mkdirs() }.toPath()).toFile()
            assertFalse(reader.materializeJpegPreview(fixture.first, fixture.second, File(link, "display.jpg")))
            assertEquals(0, writes)
            assertFalse(File(root, "real/display.jpg").exists())
            assertTrue(reader.materializeJpegPreview(fixture.first, fixture.second, File(root, "real/display.jpg")))
        } finally { root.deleteRecursively() }
    }

    @Test fun `off chain immutable object cannot authorize preview and cancelled traversal writes nothing`() {
        val root = canonicalTempDirectory("cancel-preview")
        try {
            val fixture = committedChain(root)
            var writes = 0
            val reader = NativeCapturePreviewReaderV2(root, { _, _, _, _ -> writes++; true }, { _, _ -> false })
            val target = File(root, "display.jpg")
            var checks = 0
            assertFalse(reader.materializeJpegPreview(fixture.first, fixture.second, target, { ++checks > 3 }))
            val offChain = "schema=5\nrevision=1\nrequest=${"a".repeat(64)}\nprevious=-\nsecondPrevious=-\ncomponent=JPEG:${fixture.third.size}:${sha256(fixture.third)}\n"
            val hash = sha256(offChain.toByteArray())
            File(root, "sessions/session/objects/$hash.root").writeText(offChain)
            assertFalse(reader.materializeJpegPreview(hash, fixture.second, target))
            assertEquals(0, writes)
            assertFalse(target.exists())
        } finally { root.deleteRecursively() }
    }

    @Test fun `tampered original cannot create thumbnail or change authoritative bytes`() {
        val root = canonicalTempDirectory("tampered-preview")
        try {
            val fixture = committedChain(root)
            val source = File(root, "sessions/session/assets/${fixture.second}/jpeg.${sha256(fixture.third)}.blob")
            val damaged = fixture.third.copyOf().also { it[0] = (it[0] + 1).toByte() }
            source.writeBytes(damaged)
            var writes = 0
            val reader = NativeCapturePreviewReaderV2(root, { _, _, _, _ -> writes++; true }, { _, _ -> false })
            val target = File(root, "display.jpg")
            assertFalse(reader.materializeJpegPreview(fixture.first, fixture.second, target))
            assertEquals(0, writes); assertFalse(target.exists())
            assertArrayEquals(damaged, source.readBytes())
        } finally { root.deleteRecursively() }
    }

    private fun committedChain(root: File): Triple<String, String, ByteArray> {
        val session = File(root, "sessions/session").apply { mkdirs() }
        val bytes = ByteArray(4096) { (it * 13).toByte() }
        val component = sha256(bytes)
        val capture = "b".repeat(64)
        File(session, "assets/$capture/jpeg.$component.blob").apply { parentFile!!.mkdirs(); writeBytes(bytes) }
        var previous = "-"; var secondPrevious = "-"; var first = ""
        repeat(6) { index ->
            val content = "schema=5\nrevision=${index + 1}\nrequest=${"d".repeat(64)}\nprevious=$previous\nsecondPrevious=$secondPrevious\ncomponent=JPEG:${bytes.size}:$component\n"
            val hash = sha256(content.toByteArray())
            writeReceipt(session, hash, capture, "d".repeat(64), index + 1L, bytes.size)
            File(session, "objects/$hash.root").apply { parentFile!!.mkdirs(); writeText(content) }
            if (index == 0) first = hash
            secondPrevious = previous; previous = hash
        }
        File(session, "root-A.ptr").writeText("6\n$previous\n${"e".repeat(64)}\n")
        return Triple(first, capture, bytes)
    }

    @Test
    fun `failed component copy resets workspace before the next component`() {
        val workspace = CaptureCopyWorkspace()
        var calls = 0
        assertThrows(IOException::class.java) {
            workspace.digest { buffer, offset, _ ->
                if (calls++ == 0) {
                    "failed-prefix".toByteArray().copyInto(buffer, offset)
                    "failed-prefix".length
                } else {
                    throw IOException("synthetic component failure")
                }
            }
        }

        val recovered = copy(workspace, "recovered-component".toByteArray())
        assertEquals(19L, recovered.length)
        assertArrayEquals(
            digest("recovered-component".toByteArray()),
            recovered.digest,
        )
    }

    private fun copy(workspace: CaptureCopyWorkspace, bytes: ByteArray): CaptureCopyReceipt {
        var consumed = false
        val output = ByteArrayOutputStream()
        return workspace.copy(
            read = { buffer, offset, count ->
                if (consumed) {
                    -1
                } else {
                    check(bytes.size <= count)
                    bytes.copyInto(buffer, offset)
                    consumed = true
                    bytes.size
                }
            },
            write = { buffer, offset, count -> output.write(buffer, offset, count) },
        ).also { assertArrayEquals(bytes, output.toByteArray()) }
    }

    @Test fun retainedReviewPreviewRequiresMatchingReceiptAndNeverPromotesQuality() {
        val folder = canonicalTempDirectory("retained-preview")
        try {
            AdmittedJpegStoreFixture(folder, CaptureLane.MANUAL).use { fixture ->
                fixture.store.acceptCaptureBeforeExposure(fixture.request, requireRecoveryIdentity = true)
                var receipt: CaptureReceipt? = null
                DirectStoreDispatch(fixture.components(), { acquired ->
                    receipt = fixture.store.commitStreamedFinalizedAssessed(fixture.template,
                        acquired.exposureTimestampNanoseconds, acquired.streams, requireNotNull(acquired.qualityAssessment))
                }, { throw AssertionError(it) }, { PreviewBlurQuality(58.6645, 516, 387) }, retainAssessedOriginals = true).run()
                val committed = requireNotNull(receipt); val terminal = requireNotNull(committed.terminal)
                val manifest = requireNotNull(terminal.manifestId); val capture = requireNotNull(terminal.captureId)
                var writes = 0
                val reader = NativeCapturePreviewReaderV2(fixture.storeRoot, { _, target, _, _ ->
                    writes++; target.writeBytes(byteArrayOf(1, 2, 3)); true
                }, { _, _ -> false })
                val target = File(folder, "preview.jpg")
                assertTrue(reader.materializeJpegPreview(manifest, capture, target))
                assertEquals(1, writes)
                assertEquals(CaptureQualityDispositionV1.REVIEW_REQUIRED, fixture.store.queryReceipt(committed.identity)?.terminal?.qualityAssessment?.disposition)
                fixture.assertOriginal(committed)
                val receiptFile = fixture.storeRoot.walkTopDown().single { it.name == "receipt.properties" }
                val originalReceipt = receiptFile.readText()
                receiptFile.writeText(originalReceipt.replace("captureId=${terminal.captureId}", "captureId=${"a".repeat(64)}"))
                assertFalse(reader.materializeJpegPreview(manifest, capture, target))
                receiptFile.writeText(originalReceipt.replace("qualityDisposition=reviewRequired", "qualityDisposition=policyPassed"))
                assertFalse(reader.materializeJpegPreview(manifest, capture, target))
                receiptFile.writeText(originalReceipt)
                assertTrue(reader.materializeJpegPreview(manifest, capture, target))
            }
        } finally { folder.deleteRecursively() }
    }

    @Test fun assessedSelectedRootKeepsOlderLegacyPreviewReadable() {
        val folder = canonicalTempDirectory("mixed-preview")
        try {
            val fixture = committedChain(folder)
            val session = File(folder, "sessions/session")
            val previous = File(session, "root-A.ptr").readLines()[1]
            val previousRoot = File(session, "objects/$previous.root").readLines()
            val secondPrevious = previousRoot.single { it.startsWith("previous=") }.substringAfter('=')
            val request = "d".repeat(64)
            val assessment = CaptureQualityAssessmentV1(policyVersion = "jpegLaplacianVarianceV1:threshold110",
                disposition = CaptureQualityDispositionV1.REVIEW_REQUIRED, reason = CaptureQualityReasonV1.BELOW_THRESHOLD,
                score = 58.6645, threshold = 110.0, sampleWidth = 516, sampleHeight = 387)
            val qualityFields = assessment.rootFields().entries.joinToString("\n", postfix = "\n") { "${it.key}=${it.value}" }
            val assessmentHash = CaptureQualityAssessmentV1.boundHash(request, assessment)
            val content = "schema=6\nrevision=7\nrequest=$request\nprevious=$previous\nsecondPrevious=$secondPrevious\n" +
                "component=JPEG:${fixture.third.size}:${sha256(fixture.third)}\nexposureTimestampNanoseconds=1\n" +
                "qualityAssessmentHash=$assessmentHash\n$qualityFields"
            val manifest = sha256(content.toByteArray())
            File(session, "objects/$manifest.root").writeText(content)
            writeReceipt(session, manifest, fixture.second, request, 7, fixture.third.size)
            File(session, "attempts/$manifest/receipt.properties").appendText("qualityAssessmentHash=$assessmentHash\n$qualityFields")
            File(session, "root-A.ptr").writeText("7\n$manifest\n${"e".repeat(64)}\n")
            val reader = NativeCapturePreviewReaderV2(folder, { _, target, _, _ -> target.writeBytes(byteArrayOf(1)); true }, { _, _ -> false })
            assertTrue(reader.materializeJpegPreview(fixture.first, fixture.second, File(folder, "old-preview.jpg")))
            assertTrue(reader.materializeJpegPreview(manifest, fixture.second, File(folder, "review-preview.jpg")))
        } finally { folder.deleteRecursively() }
    }

    private fun writeReceipt(session: File, manifest: String, capture: String, request: String, revision: Long, size: Int) {
        File(session, "attempts/$manifest/receipt.properties").apply {
            parentFile!!.mkdirs()
            writeText("kind=COMMITTED_PICTURE\nrootHash=$manifest\ncaptureId=$capture\nrequestHash=$request\nrevision=$revision\n" +
                "jpegSizeBytes=$size\nreceiptHash=${sha256("$request|$manifest".toByteArray())}\n")
        }
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private fun digest(bytes: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(bytes)

    /**
     * Production preview targets live under the canonical app files folder
     * (`NativeCapturePreviewOwnerV2`), and the reader refuses a target whose
     * path is not canonical (a symlinked ancestor). Host temporary folders
     * can have one (macOS `/var` -> `/private/var`), so fixtures use the
     * canonical folder as production does.
     */
    private fun canonicalTempDirectory(prefix: String): File = Files.createTempDirectory(prefix).toFile().canonicalFile
}
