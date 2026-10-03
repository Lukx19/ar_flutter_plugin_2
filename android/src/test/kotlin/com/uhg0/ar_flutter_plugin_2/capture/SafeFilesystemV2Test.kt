package com.uhg0.ar_flutter_plugin_2.capture

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.security.MessageDigest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test

class SafeFilesystemV2Test {
    @Test
    fun `metadata reads reuse component scratch while returning independent bytes`() {
        val root = Files.createTempDirectory("safe-filesystem-metadata").toFile()
        val reads = ReadTrackingState()
        try {
            SafeFilesystemV2(root, DurableStoreFaultInjectorV2 { }, ReadTrackingFilesystem(reads)).use { files ->
                val lengths = listOf(0, 1, 65_535, 65_536, 65_537, 131_089)
                val expected = lengths.map { length -> ByteArray(length) { (it * 31 + 17).toByte() } }
                val paths = expected.mapIndexed { index, bytes ->
                    files.child("metadata-$index").also { it.writeBytes(bytes) }
                }
                val actual = paths.map(files::readBytes)
                paths.forEachIndexed { index, path ->
                    val measured = files.digestAndLength(path)
                    assertEquals(expected[index].size.toLong(), measured.first)
                    assertArrayEquals(sha256(expected[index]), measured.second)
                    assertArrayEquals(expected[index], actual[index])
                }
                // Later reads and digest passes must not rewrite any earlier result.
                actual[1][0] = 42
                assertArrayEquals(expected[1], files.readBytes(paths[1]))
                assertEquals(13, reads.closedDescriptors)
                assertEquals(CaptureCopyWorkspace.COPY_BUFFER_BYTES, reads.buffer?.size)
            }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `failed metadata reads close their descriptor and leave digest state reusable`() {
        val root = Files.createTempDirectory("safe-filesystem-read-failure").toFile()
        val reads = ReadTrackingState().apply { failAfterChunk = true }
        try {
            SafeFilesystemV2(root, DurableStoreFaultInjectorV2 { }, ReadTrackingFilesystem(reads)).use { files ->
                val expected = ByteArray(12_000) { (it * 13).toByte() }
                val path = files.child("metadata").also { it.writeBytes(expected) }
                assertThrows(IOException::class.java) { files.readBytes(path) }
                assertEquals(1, reads.closedDescriptors)
                reads.failAfterChunk = false
                assertArrayEquals(expected, files.readBytes(path))
                val measured = files.digestAndLength(path)
                assertEquals(expected.size.toLong(), measured.first)
                assertArrayEquals(sha256(expected), measured.second)
                assertEquals(3, reads.closedDescriptors)
            }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `normalized contained paths succeed while escapes and invalid names reject before descriptor read`() {
        val root = Files.createTempDirectory("safe-filesystem-containment").toFile()
        val reads = ReadTrackingState()
        try {
            SafeFilesystemV2(root, DurableStoreFaultInjectorV2 { }, ReadTrackingFilesystem(reads)).use { files ->
                val expected = byteArrayOf(7, 9, 13)
                files.child("metadata").writeBytes(expected)
                assertArrayEquals(expected, files.readBytes(File(root, "nested/../metadata")))
                assertEquals(1, reads.openCalls)
                val sibling = File(root.parentFile, root.name + "-sibling")
                for (rejected in listOf(File(sibling, "metadata"), File(root, "../" + sibling.name + "/metadata"), File(root, "unsafe name"))) {
                    assertThrows(IllegalArgumentException::class.java) { files.readBytes(rejected) }
                }
                assertThrows(IllegalArgumentException::class.java) { files.child("..", "metadata") }
                assertEquals("rejected paths must never reach descriptor traversal", 1, reads.openCalls)
            }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `segment validation matches legacy ASCII regex for all character units and length boundaries`() {
        val root = Files.createTempDirectory("safe-filesystem-segments").toFile()
        val reads = ReadTrackingState()
        val legacy = Regex("[A-Za-z0-9._-]{1,240}")
        try {
            SafeFilesystemV2(root, DurableStoreFaultInjectorV2 { }, ReadTrackingFilesystem(reads)).use { files ->
                fun verify(name: String) {
                    val expected = legacy.matches(name) && name != "." && name != ".."
                    val actual = try {
                        files.child(name)
                        true
                    } catch (_: IllegalArgumentException) {
                        false
                    }
                    if (expected != actual) assertEquals("segment length=${name.length}, units=${name.map { it.code }}", expected, actual)
                }
                // Every UTF-16 unit includes control characters, non-ASCII letters,
                // Unicode lookalikes and isolated surrogates; the whitelist remains ASCII.
                for (unit in Char.MIN_VALUE.code..Char.MAX_VALUE.code) verify("a${unit.toChar()}z")
                for (unit in 0..127) {
                    verify("${unit.toChar()}az")
                    verify("az${unit.toChar()}")
                }
                for (name in listOf("", ".", "..", "...", "._-", "../x", "x/y", "x\\y", "\uD83D\uDE00")) verify(name)
                for (length in listOf(1, 2, 173, 174, 239, 240, 241, 255)) {
                    verify("a".repeat(length))
                    verify(".".repeat(length))
                    verify("a".repeat(length - 1) + "\u00E9")
                }
                assertEquals("child validation never opens a descriptor", 0, reads.openCalls)
            }
        } finally {
            root.deleteRecursively()
        }
    }

    private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)

    private class ReadTrackingState {
        var buffer: ByteArray? = null
        var closedDescriptors = 0
        var openCalls = 0
        var failAfterChunk = false
    }

    private class ReadTrackingFilesystem(
        private val reads: ReadTrackingState,
        private val delegate: DescriptorFilesystemV2 = JvmDescriptorFilesystemV2(),
    ) : DescriptorFilesystemV2 by delegate {
        override fun bind(root: File): DescriptorFilesystemV2 = ReadTrackingFilesystem(reads, delegate.bind(root))

        override fun openRead(segments: List<String>): DescriptorFileV2 {
            reads.openCalls++
            val descriptor = delegate.openRead(segments)
            return object : DescriptorFileV2 by descriptor {
                private var returnedZero = false
                private var returnedChunk = false

                override fun read(bytes: ByteArray, offset: Int, count: Int): Int {
                    if (reads.buffer == null) reads.buffer = bytes
                    assertSame("metadata and digest reads share the existing buffer", reads.buffer, bytes)
                    assertEquals(CaptureCopyWorkspace.COPY_BUFFER_BYTES, count)
                    if (!returnedZero) {
                        returnedZero = true
                        return 0
                    }
                    if (returnedChunk && reads.failAfterChunk) throw IOException("metadata read failure")
                    return descriptor.read(bytes, offset, minOf(count, 8_191)).also {
                        if (it > 0) returnedChunk = true
                    }
                }

                override fun close() {
                    reads.closedDescriptors++
                    descriptor.close()
                }
            }
        }
    }
}
