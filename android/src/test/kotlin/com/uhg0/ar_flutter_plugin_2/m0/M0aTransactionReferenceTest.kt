package com.uhg0.ar_flutter_plugin_2.m0

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

class M0aTransactionReferenceTest {
    @Test
    fun `begin chunks commit and acknowledgement publish atomically`() {
        val receiver = M0aStructuralTransactionReceiverV1(maximumStagedBytes = 8)
        val bytes = byteArrayOf(1, 2, 3, 4, 5)
        receiver.begin(begin(bytes))
        assertEquals(M0aStructuralTransactionState.SENDING_BEGIN, receiver.state)
        assertEquals(0, receiver.visibleBytes.size)
        receiver.acknowledgeBegin(M0aTransactionBeginAcknowledgementV1(7, 11, 12))
        receiver.chunk(M0aTransactionChunkV1(7, 0, byteArrayOf(1, 2)))
        receiver.chunk(M0aTransactionChunkV1(7, 1, byteArrayOf(3, 4, 5)))
        receiver.commit(M0aTransactionCommitV1(7, checksum(bytes)))
        assertEquals(M0aStructuralTransactionState.COMMIT_AWAITING_ACK, receiver.state)
        assertEquals(0, receiver.visibleBytes.size)
        receiver.acknowledgeCommit(M0aTransactionAcknowledgementV1(7, 11, 12))
        assertEquals(M0aStructuralTransactionState.READY, receiver.state)
        assertArrayEquals(bytes, receiver.visibleBytes)
        receiver.acknowledgeCommit(M0aTransactionAcknowledgementV1(7, 11, 12))
        assertArrayEquals(bytes, receiver.visibleBytes)
    }

    @Test
    fun `order checksum and acknowledgement faults never publish partial data`() {
        val receiver = M0aStructuralTransactionReceiverV1(maximumStagedBytes = 8)
        receiver.begin(begin(byteArrayOf(1, 2, 3, 4)))
        receiver.acknowledgeBegin(M0aTransactionBeginAcknowledgementV1(7, 11, 12))
        assertThrows(IllegalArgumentException::class.java) {
            receiver.chunk(M0aTransactionChunkV1(7, 1, byteArrayOf(1)))
        }
        assertThrows(IllegalArgumentException::class.java) {
            receiver.chunk(M0aTransactionChunkV1(7, 0, byteArrayOf(1), 1))
        }
        receiver.chunk(M0aTransactionChunkV1(7, 0, byteArrayOf(1, 2)))
        receiver.chunk(M0aTransactionChunkV1(7, 1, byteArrayOf(3, 4)))
        assertThrows(IllegalArgumentException::class.java) {
            receiver.commit(M0aTransactionCommitV1(7, 99))
        }
        receiver.commit(M0aTransactionCommitV1(7, checksum(byteArrayOf(1, 2, 3, 4))))
        assertThrows(IllegalArgumentException::class.java) {
            receiver.acknowledgeCommit(M0aTransactionAcknowledgementV1(7, 99, 12))
        }
        assertEquals(0, receiver.visibleBytes.size)
    }

    @Test
    fun `abandonment requires matching canonical resync`() {
        val receiver = M0aStructuralTransactionReceiverV1()
        receiver.begin(begin(byteArrayOf(1, 2)))
        receiver.abandon()
        assertEquals(M0aStructuralTransactionState.RESYNC_PENDING, receiver.state)
        receiver.resync(
            M0aResyncCommandV1(
                M0aResyncPayloadV1(
                    lastCommittedTransactionId = 0,
                    lastCommittedGeometryRevision = 0,
                    lastCommittedLineageRevision = 0,
                    failedTransactionId = 7,
                    reason = M0aResyncReason.ABANDONED_STAGING,
                ),
            ),
        )
        assertEquals(M0aStructuralTransactionState.READY, receiver.state)
        assertTrue(receiver.visibleBytes.isEmpty())
    }

    @Test
    fun `producer emits begin ordered chunks and commit frames`() {
        val frames = M0aStructuralTransactionProducerV1.produce(
            transactionId = 3,
            baseGeometryRevision = 4,
            targetGeometryRevision = 5,
            targetLineageRevision = 6,
            bytes = byteArrayOf(1, 2, 3, 4, 5),
            maximumChunkBytes = 2,
        )
        assertEquals(5, frames.size)
        assertTrue(frames[0] is M0aTransactionBeginFrameV1)
        assertEquals(0, (frames[1] as M0aTransactionChunkFrameV1).value.chunkIndex)
        assertArrayEquals(byteArrayOf(3, 4), (frames[2] as M0aTransactionChunkFrameV1).value.bytes)
        assertTrue(frames[4] is M0aTransactionCommitFrameV1)
    }

    @Test
    fun `structural frames round trip through the packed response envelope`() {
        val frames = M0aStructuralTransactionProducerV1.produce(
            transactionId = 3,
            baseGeometryRevision = 4,
            targetGeometryRevision = 5,
            targetLineageRevision = 6,
            bytes = byteArrayOf(1, 2, 3, 4, 5),
            maximumChunkBytes = 1024,
        )
        frames.forEachIndexed { index, frame ->
            val encoded = M0aPacketCodec.encodeResponse(
                M0aTransactionResponseCodecV1.encodeFrame(
                    frame = frame,
                    streamToken = 91,
                    requestSequence = index.toLong() + 1,
                    nextExpectedRequestSequence = index.toLong() + 2,
                ),
                M0aPacketCodec.responseMaximumBytes,
            )
            val decoded = M0aTransactionResponseCodecV1.decodeFrame(
                M0aPacketCodec.decodeResponse(encoded),
            )
            when (frame) {
                is M0aTransactionBeginFrameV1 -> {
                    val actual = decoded as M0aTransactionBeginFrameV1
                    assertEquals(frame.value, actual.value)
                }
                is M0aTransactionChunkFrameV1 -> {
                    val actual = decoded as M0aTransactionChunkFrameV1
                    assertEquals(frame.value.transactionId, actual.value.transactionId)
                    assertEquals(frame.value.chunkIndex, actual.value.chunkIndex)
                    assertEquals(frame.value.offset, actual.value.offset)
                    assertArrayEquals(frame.value.bytes, actual.value.bytes)
                }
                is M0aTransactionCommitFrameV1 -> {
                    val actual = decoded as M0aTransactionCommitFrameV1
                    assertEquals(frame.value, actual.value)
                }
            }
        }
    }

    @Test
    fun `structural response kinds match canonical Ch13 IDs`() {
        val frames = M0aStructuralTransactionProducerV1.produce(
            transactionId = 3,
            baseGeometryRevision = 4,
            targetGeometryRevision = 5,
            targetLineageRevision = 6,
            bytes = byteArrayOf(1),
        )
        val kinds = frames.map { frame ->
            M0aTransactionResponseCodecV1.encodeFrame(
                frame = frame,
                streamToken = 1,
                requestSequence = 1,
                nextExpectedRequestSequence = 2,
            ).messageKind
        }
        assertEquals(listOf(2, 3, 4), kinds)
    }

    @Test
    fun `producer accepts exact request ceiling and rejects one byte over`() {
        val maximum = ByteArray(M0aPacketCodec.requestCeilingBytes)
        val frames = M0aStructuralTransactionProducerV1.produce(
            transactionId = 3,
            baseGeometryRevision = 4,
            targetGeometryRevision = 5,
            targetLineageRevision = 6,
            bytes = maximum,
        )
        assertEquals(18, frames.size)
        assertThrows(IllegalArgumentException::class.java) {
            M0aStructuralTransactionProducerV1.produce(
                transactionId = 3,
                baseGeometryRevision = 4,
                targetGeometryRevision = 5,
                targetLineageRevision = 6,
                bytes = ByteArray(M0aPacketCodec.requestCeilingBytes + 1),
            )
        }
    }

    private fun begin(bytes: ByteArray) = M0aTransactionBeginV1(
        transactionId = 7,
        baseGeometryRevision = 10,
        targetGeometryRevision = 11,
        targetLineageRevision = 12,
        chunkCount = if (bytes.isEmpty()) 0 else 2,
        totalBytes = bytes.size,
        payloadChecksum = checksum(bytes),
    )

    private fun checksum(bytes: ByteArray): Long {
        var crc = -1
        bytes.forEach { original ->
            crc = crc xor (original.toInt() and 0xff)
            repeat(8) { crc = if (crc and 1 == 1) (crc ushr 1) xor 0xedb88320.toInt() else crc ushr 1 }
        }
        return (crc xor -1).toLong() and 0xffff_ffffL
    }
}
