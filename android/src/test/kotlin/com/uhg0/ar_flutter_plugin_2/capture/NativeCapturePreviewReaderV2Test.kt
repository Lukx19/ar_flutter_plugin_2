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
        val root = Files.createTempDirectory("preview-reader").toFile()
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

            val target = File(root, "preview.jpg")
            val thumbnail = image.copyOf(16)
            val reader = NativeCapturePreviewReaderV2(root,
                { _, destination, _, _ -> destination.writeBytes(thumbnail); true },
                { file, _ -> file.isFile && file.readBytes().contentEquals(thumbnail) },
            )
            assertTrue(reader.materializeJpegPreview(manifestId, captureId, target))
            assertArrayEquals(thumbnail, target.readBytes())
            assertArrayEquals(image, File(session, "assets/$captureId/jpeg.$componentHash.blob").readBytes())

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
        val root = Files.createTempDirectory("older-preview").toFile()
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

    @Test fun `off chain immutable object cannot authorize preview and cancelled traversal writes nothing`() {
        val root = Files.createTempDirectory("cancel-preview").toFile()
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
        val root = Files.createTempDirectory("tampered-preview").toFile()
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

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private fun digest(bytes: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(bytes)
}
