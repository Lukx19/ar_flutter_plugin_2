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
        val profile = M0aTransactionResponseProfileV1(M0aPacketCodec.responseMinimumBytes)
        val bytes = ByteArray(profile.chunkPayloadBytes + 1) { (it and 0xff).toByte() }
        val frames = M0aStructuralTransactionProducerV1.produce(
            transactionId = 3,
            baseGeometryRevision = 4,
            targetGeometryRevision = 5,
            targetLineageRevision = 6,
            bytes = bytes,
            responseProfile = profile,
        )
        assertEquals(4, frames.size)
        assertTrue(frames[0] is M0aTransactionBeginFrameV1)
        assertEquals(0, (frames[1] as M0aTransactionChunkFrameV1).value.chunkIndex)
        assertEquals(profile.chunkPayloadBytes, (frames[1] as M0aTransactionChunkFrameV1).value.bytes.size)
        assertArrayEquals(byteArrayOf(bytes.last()), (frames[2] as M0aTransactionChunkFrameV1).value.bytes)
        assertTrue(frames[3] is M0aTransactionCommitFrameV1)
    }

    @Test
    fun `fresh binding transaction advances from zero committed geometry`() {
        val frames = M0aStructuralTransactionProducerV1.produce(
            transactionId = 1,
            baseGeometryRevision = 0,
            targetGeometryRevision = 1,
            targetLineageRevision = 1,
            bytes = byteArrayOf(),
        )
        val receiver = M0aStructuralTransactionReceiverV1()

        receiver.begin((frames[0] as M0aTransactionBeginFrameV1).value)
        receiver.acknowledgeBegin(M0aTransactionBeginAcknowledgementV1(1, 1, 1))
        receiver.commit((frames[1] as M0aTransactionCommitFrameV1).value)
        receiver.acknowledgeCommit(M0aTransactionAcknowledgementV1(1, 1, 1))

        assertEquals(M0aStructuralTransactionState.READY, receiver.state)
        assertEquals(1L, receiver.visibleTransactionId)
        assertEquals(1L, receiver.visibleGeometryRevision)
        assertEquals(1L, receiver.visibleLineageRevision)
        assertTrue(receiver.visibleBytes.isEmpty())
    }

    @Test
    fun `structural frames round trip through the packed response envelope`() {
        val frames = M0aStructuralTransactionProducerV1.produce(
            transactionId = 3,
            baseGeometryRevision = 4,
            targetGeometryRevision = 5,
            targetLineageRevision = 6,
            bytes = byteArrayOf(1, 2, 3, 4, 5),
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
    fun `request ceiling remains distinct from one mebibyte structural transaction ceiling`() {
        val exactRequest = M0aPacketCodec.encodeRequest(request(commandBytes = ByteArray(16_304)))
        assertEquals(M0aPacketCodec.requestCeilingBytes, exactRequest.size)
        assertArrayEquals(exactRequest, M0aPacketCodec.encodeRequest(request(commandBytes = ByteArray(16_304))))
        assertThrows(IllegalArgumentException::class.java) {
            M0aPacketCodec.encodeRequest(request(commandBytes = ByteArray(16_305)))
        }

        val maximum = ByteArray(M0aStructuralTransactionLimits.MAX_STRUCTURAL_TRANSACTION_BYTES)
        val frames = M0aStructuralTransactionProducerV1.produce(
            transactionId = 3,
            baseGeometryRevision = 4,
            targetGeometryRevision = 5,
            targetLineageRevision = 6,
            bytes = maximum,
        )
        assertEquals(67, frames.size)
        frames.forEachIndexed { index, frame ->
            val response = M0aTransactionResponseCodecV1.encodeFrame(
                frame,
                streamToken = 9,
                requestSequence = index.toLong() + 1,
                nextExpectedRequestSequence = index.toLong() + 2,
            )
            assertTrue(M0aPacketCodec.encodeResponse(response, M0aPacketCodec.responseMaximumBytes).size <=
                M0aPacketCodec.responseMaximumBytes)
        }
        assertThrows(IllegalArgumentException::class.java) {
            M0aStructuralTransactionProducerV1.produce(
                transactionId = 3,
                baseGeometryRevision = 4,
                targetGeometryRevision = 5,
                targetLineageRevision = 6,
                bytes = ByteArray(M0aStructuralTransactionLimits.MAX_STRUCTURAL_TRANSACTION_BYTES + 1),
            )
        }
    }

    @Test
    fun `catch up ceiling derives bounded stride and frame count from response envelope`() {
        assertEquals(3_972, M0aTransactionResponseProfileV1(M0aPacketCodec.responseMinimumBytes).chunkPayloadBytes)
        assertEquals(16_260, M0aStructuralTransactionLimits.ordinaryChunkPayloadBytes)
        assertEquals(65_412, M0aStructuralTransactionLimits.catchUpChunkPayloadBytes)
        assertEquals(67, M0aStructuralTransactionLimits.frameCount(
            M0aStructuralTransactionLimits.MAX_STRUCTURAL_TRANSACTION_BYTES,
            M0aPacketCodec.responseMaximumBytes,
        ))
        assertEquals(19, M0aStructuralTransactionLimits.frameCount(
            M0aStructuralTransactionLimits.MAX_STRUCTURAL_TRANSACTION_BYTES,
            M0aPacketCodec.catchUpMaximumBytes,
        ))
        val frames = M0aStructuralTransactionProducerV1.produce(
            transactionId = 3,
            baseGeometryRevision = 4,
            targetGeometryRevision = 5,
            targetLineageRevision = 6,
            bytes = ByteArray(M0aStructuralTransactionLimits.MAX_STRUCTURAL_TRANSACTION_BYTES),
            responseProfile = M0aTransactionResponseProfileV1.catchUp,
        )
        assertEquals(19, frames.size)
        frames.forEachIndexed { index, frame ->
            val response = M0aTransactionResponseCodecV1.encodeFrame(
                frame,
                streamToken = 9,
                requestSequence = index.toLong() + 1,
                nextExpectedRequestSequence = index.toLong() + 2,
            )
            assertTrue(M0aPacketCodec.encodeResponse(response, M0aPacketCodec.catchUpMaximumBytes).size <=
                M0aPacketCodec.catchUpMaximumBytes)
        }
        assertThrows(IllegalArgumentException::class.java) {
            M0aTransactionResponseProfileV1.forChunkPayloadBytes(65_413)
        }
        assertThrows(IllegalArgumentException::class.java) {
            M0aTransactionResponseProfileV1.forChunkPayloadBytes(65_535)
        }
    }

    @Test
    fun `duplicate exact chunk is invisible and changed duplicate is rejected`() {
        val receiver = M0aStructuralTransactionReceiverV1()
        val bytes = byteArrayOf(1, 2, 3, 4)
        receiver.begin(begin(bytes))
        receiver.acknowledgeBegin(M0aTransactionBeginAcknowledgementV1(7, 11, 12))
        val first = M0aTransactionChunkV1(7, 0, byteArrayOf(1, 2), 0)
        receiver.chunk(first)
        receiver.chunk(first.copy(bytes = first.bytes.copyOf()))
        assertEquals(2, receiver.stagedBytes)
        assertThrows(IllegalArgumentException::class.java) {
            receiver.chunk(M0aTransactionChunkV1(7, 0, byteArrayOf(9, 2), 0))
        }
        receiver.chunk(M0aTransactionChunkV1(7, 1, byteArrayOf(3, 4), 2))
        receiver.commit(M0aTransactionCommitV1(7, checksum(bytes)))
        receiver.acknowledgeCommit(M0aTransactionAcknowledgementV1(7, 11, 12))
        assertArrayEquals(bytes, receiver.visibleBytes)
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

    private fun request(commandBytes: ByteArray) = M0aPacketCodec.Request(
        requestFlags = 0,
        streamToken = 1,
        acknowledgedTransactionId = 0,
        acknowledgedGeometryRevision = 0,
        acknowledgedLineageRevision = 0,
        nextStyleRevision = 0,
        maximumResponseBytes = M0aPacketCodec.responseMaximumBytes,
        styleRecords = emptyList(),
        commandBytes = commandBytes,
        requestSequence = 1,
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
