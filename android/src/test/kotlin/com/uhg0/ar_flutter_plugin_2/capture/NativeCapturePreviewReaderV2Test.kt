package com.uhg0.ar_flutter_plugin_2.capture

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.security.MessageDigest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeCapturePreviewReaderV2Test {
    @Test
    fun `materializes an immutable preview through reusable copy and digest workspace`() {
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
            val reader = NativeCapturePreviewReaderV2(root)
            assertTrue(reader.materializeJpegPreview(manifestId, captureId, target))
            assertArrayEquals(image, target.readBytes())

            // A damaged display copy is replaced only after its durable hash
            // fails; the source bytes remain the immutable authority.
            target.writeBytes(byteArrayOf(1, 2, 3))
            assertTrue(reader.materializeJpegPreview(manifestId, captureId, target))
            assertArrayEquals(image, target.readBytes())
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
