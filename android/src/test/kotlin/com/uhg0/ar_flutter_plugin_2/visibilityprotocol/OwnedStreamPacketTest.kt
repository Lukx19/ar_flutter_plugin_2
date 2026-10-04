package com.uhg0.ar_flutter_plugin_2.visibilityprotocol

import java.nio.ByteBuffer
import org.junit.Assert.*
import org.junit.Test

class OwnedStreamPacketTest {
    private fun request() = PacketCodec.Request(0, 1, 0, 0, 0, 0, 4096,
        listOf(byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)), byteArrayOf(9, 10), 1)

    @Test fun `qualifier is checked before one owned payload copy without changing framework cursor`() {
        val qualifier = byteArrayOf(7, 8)
        val framework = ByteBuffer.wrap(byteArrayOf(99, 7, 8, 1, 2, 88)).apply { position(1); limit(5) }
        val owned = checkNotNull(AuthenticatedStreamPacket.copyPayload(framework, qualifier))
        assertArrayEquals(byteArrayOf(1, 2), owned)
        assertEquals(1, framework.position())
        assertEquals(5, framework.limit())
        framework.put(3, 42)
        assertArrayEquals(byteArrayOf(1, 2), owned)
        assertNull(AuthenticatedStreamPacket.copyPayload(framework, byteArrayOf(7, 9)))
        assertNull(AuthenticatedStreamPacket.copyPayload(framework, ByteArray(5)))
    }

    @Test fun `owned request view retains packet range while legacy decode returns independent values`() {
        val bytes = PacketCodec.encodeRequest(request())
        val view = PacketCodec.decodeRequestView(bytes)
        assertSame(bytes, view.commandBytes)
        assertEquals(88, view.commandOffset)
        assertEquals(2, view.commandLength)
        assertEquals(1, view.styleCount)
        assertArrayEquals(bytes, PacketCodec.encodeRequest(view))
        val legacy = PacketCodec.decodeRequest(bytes)
        bytes[88] = 42
        assertArrayEquals(byteArrayOf(9, 10), legacy.commandBytes)
        assertArrayEquals(request().styleRecords[0], legacy.styleRecords[0])
        assertThrows(IllegalArgumentException::class.java) { PacketCodec.decodeRequestView(bytes) }
    }

    @Test fun `copying ordinary or viewed requests rebinds replacement command storage`() {
        val ordinary = request()
        val packet = PacketCodec.encodeRequest(ordinary)
        val view = PacketCodec.decodeRequestView(packet)
        val copiedView = view.copy(requestSequence = 2)
        assertSame(packet, copiedView.commandBytes)
        assertEquals(view.commandOffset, copiedView.commandOffset)
        assertEquals(view.commandLength, copiedView.commandLength)
        for (source in listOf(ordinary, view, PacketCodec.decodeRequest(packet))) {
            for (replacement in listOf(byteArrayOf(), byteArrayOf(3), ByteArray(17) { it.toByte() },
                ByteArray(PacketCodec.requestCeilingBytes - PacketCodec.requestHeaderBytes - PacketCodec.styleRecordBytes))) {
                val copied = source.copy(commandBytes = replacement)
                assertSame(replacement, copied.commandBytes)
                assertEquals(0, copied.commandOffset)
                assertEquals(replacement.size, copied.commandLength)
                val encoded = PacketCodec.encodeRequest(copied)
                assertEquals(PacketCodec.requestHeaderBytes + PacketCodec.styleRecordBytes + replacement.size, encoded.size)
                assertArrayEquals(replacement, PacketCodec.decodeRequest(encoded).commandBytes)
                val nextReplacement = byteArrayOf(99, 100, 101)
                val copiedAgain = copied.copy(commandBytes = nextReplacement)
                assertEquals(0, copiedAgain.commandOffset)
                assertEquals(nextReplacement.size, copiedAgain.commandLength)
                assertArrayEquals(nextReplacement, PacketCodec.decodeRequest(PacketCodec.encodeRequest(copiedAgain)).commandBytes)
            }
        }
        assertArrayEquals(packet, PacketCodec.encodeRequest(view))
    }
    @Test fun `active prefix upload ignores stale capacity tail`() {
        val buffers = com.uhg0.ar_flutter_plugin_2.sceneview.CoveragePointUploadBuffers(2)
        buffers.writePage(floatArrayOf(1f, 2f, 3f, 99f, 99f, 99f), intArrayOf(-1, 0), 0, 1)
        assertEquals(3, buffers.positionBuffer.remaining())
        assertEquals(4, buffers.colorBuffer.remaining())
    }
}
