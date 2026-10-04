package com.uhg0.ar_flutter_plugin_2.capture

import java.security.MessageDigest
import org.junit.Assert.*
import org.junit.Test

class CaptureRootMetadataReaderV2Test {
    @Test
    fun `repeated root bytes reuse parsed metadata but corruption never reuses authority`() {
        val reader = CaptureRootMetadataReaderV2()
        val bytes = root(1)
        val hash = hash(bytes)
        val first = bytes.inputStream().use { reader.root(hash, it::read) }!!
        repeat(10) {
            assertSame(first, bytes.inputStream().use { reader.root(hash, it::read) })
        }
        assertEquals(1L, reader.parsedRootCount)
        assertEquals(1L, first.revision)
        assertEquals(23L, first.components.single().length)
        assertNull(root(2).inputStream().use { reader.root(hash, it::read) })
        assertSame(first, bytes.inputStream().use { reader.root(hash, it::read) })
        assertEquals(1L, reader.parsedRootCount)
    }

    @Test
    fun `cache eviction leaves held metadata stable and mutable pointers are read every time`() {
        val reader = CaptureRootMetadataReaderV2()
        val firstBytes = root(1)
        val first = firstBytes.inputStream().use { reader.root(hash(firstBytes), it::read) }!!
        for (revision in 2L..10L) {
            val bytes = root(revision)
            assertNotNull(bytes.inputStream().use { reader.root(hash(bytes), it::read) })
        }
        assertEquals(1L, first.revision)
        val again = firstBytes.inputStream().use { reader.root(hash(firstBytes), it::read) }
        assertEquals(first, again)
        assertNotSame(first, again)
        for (revision in 1L..2L) {
            val pointer = "$revision\n${hash(root(revision))}\ncommit\n".byteInputStream()
                .use { reader.pointer(it::read) }!!
            assertEquals(revision, pointer.revision)
        }
    }

    @Test
    fun `oversize and failed reads cannot poison the next metadata read`() {
        val reader = CaptureRootMetadataReaderV2()
        val large = ByteArray(CaptureRootMetadataReaderV2.MAX_METADATA_BYTES + 1) { 65 }
        assertNull(large.inputStream().use { reader.root(hash(large), it::read) })
        assertThrows(java.io.IOException::class.java) {
            reader.root(hash(root(1))) { _, _, _ -> throw java.io.IOException("failure") }
        }
        val good = root(1)
        assertNotNull(good.inputStream().use { input ->
            reader.root(hash(good)) { bytes, offset, count -> input.read(bytes, offset, minOf(count, 7)) }
        })
    }

    private fun root(revision: Long) =
        "schema=5\nrevision=$revision\nrequest=${"a".repeat(64)}\nprevious=-\nsecondPrevious=-\ncomponent=JPEG:23:${"b".repeat(64)}\n".toByteArray()
    private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).toCaptureHashHex()
}
