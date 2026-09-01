package com.uhg0.ar_flutter_plugin_2.proposal08

import com.uhg0.ar_flutter_plugin_2.proposal08.*

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

class VisibilityProtocolTransactionReferenceTest {
    @Test
    fun `begin chunks commit and acknowledgement publish atomically`() {
        val receiver = StructuralTransactionReceiverV1(maximumStagedBytes = 8)
        val bytes = byteArrayOf(1, 2, 3, 4, 5)
        receiver.begin(begin(bytes))
        assertEquals(StructuralTransactionState.SENDING_BEGIN, receiver.state)
        assertEquals(0, receiver.visibleBytes.size)
        receiver.acknowledgeBegin(TransactionBeginAcknowledgementV1(7, 11, 12))
        receiver.chunk(TransactionChunkV1(7, 0, byteArrayOf(1, 2)))
        receiver.chunk(TransactionChunkV1(7, 1, byteArrayOf(3, 4, 5)))
        receiver.commit(TransactionCommitV1(7, checksum(bytes)))
        assertEquals(StructuralTransactionState.COMMIT_AWAITING_ACK, receiver.state)
        assertEquals(0, receiver.visibleBytes.size)
        receiver.acknowledgeCommit(TransactionAcknowledgementV1(7, 11, 12))
        assertEquals(StructuralTransactionState.READY, receiver.state)
        assertArrayEquals(bytes, receiver.visibleBytes)
        receiver.acknowledgeCommit(TransactionAcknowledgementV1(7, 11, 12))
        assertArrayEquals(bytes, receiver.visibleBytes)
    }

    @Test
    fun `order checksum and acknowledgement faults never publish partial data`() {
        val receiver = StructuralTransactionReceiverV1(maximumStagedBytes = 8)
        receiver.begin(begin(byteArrayOf(1, 2, 3, 4)))
        receiver.acknowledgeBegin(TransactionBeginAcknowledgementV1(7, 11, 12))
        assertThrows(IllegalArgumentException::class.java) {
            receiver.chunk(TransactionChunkV1(7, 1, byteArrayOf(1)))
        }
        assertThrows(IllegalArgumentException::class.java) {
            receiver.chunk(TransactionChunkV1(7, 0, byteArrayOf(1), 1))
        }
        receiver.chunk(TransactionChunkV1(7, 0, byteArrayOf(1, 2)))
        receiver.chunk(TransactionChunkV1(7, 1, byteArrayOf(3, 4)))
        assertThrows(IllegalArgumentException::class.java) {
            receiver.commit(TransactionCommitV1(7, 99))
        }
        receiver.commit(TransactionCommitV1(7, checksum(byteArrayOf(1, 2, 3, 4))))
        assertThrows(IllegalArgumentException::class.java) {
            receiver.acknowledgeCommit(TransactionAcknowledgementV1(7, 99, 12))
        }
        assertEquals(0, receiver.visibleBytes.size)
    }

    @Test
    fun `abandonment requires matching canonical resync`() {
        val receiver = StructuralTransactionReceiverV1()
        receiver.begin(begin(byteArrayOf(1, 2)))
        receiver.abandon()
        assertEquals(StructuralTransactionState.RESYNC_PENDING, receiver.state)
        receiver.resync(
            ResyncCommandV1(
                ResyncPayloadV1(
                    lastCommittedTransactionId = 0,
                    lastCommittedGeometryRevision = 0,
                    lastCommittedLineageRevision = 0,
                    failedTransactionId = 7,
                    reason = ResyncReason.ABANDONED_STAGING,
                ),
            ),
        )
        assertEquals(StructuralTransactionState.READY, receiver.state)
        assertTrue(receiver.visibleBytes.isEmpty())
    }

    @Test
    fun `producer emits begin ordered chunks and commit frames`() {
        val profile = TransactionResponseProfileV1(PacketCodec.responseMinimumBytes)
        val bytes = ByteArray(profile.chunkPayloadBytes + 1) { (it and 0xff).toByte() }
        val frames = StructuralTransactionProducerV1.produce(
            transactionId = 3,
            baseGeometryRevision = 4,
            targetGeometryRevision = 5,
            targetLineageRevision = 6,
            bytes = bytes,
            responseProfile = profile,
        )
        assertEquals(4, frames.size)
        assertTrue(frames[0] is TransactionBeginFrameV1)
        assertEquals(0, (frames[1] as TransactionChunkFrameV1).value.chunkIndex)
        assertEquals(profile.chunkPayloadBytes, (frames[1] as TransactionChunkFrameV1).value.bytes.size)
        assertArrayEquals(byteArrayOf(bytes.last()), (frames[2] as TransactionChunkFrameV1).value.bytes)
        assertTrue(frames[3] is TransactionCommitFrameV1)
    }

    @Test
    fun `fresh binding transaction advances from zero committed geometry`() {
        val frames = StructuralTransactionProducerV1.produce(
            transactionId = 1,
            baseGeometryRevision = 0,
            targetGeometryRevision = 1,
            targetLineageRevision = 1,
            bytes = byteArrayOf(),
        )
        val receiver = StructuralTransactionReceiverV1()

        receiver.begin((frames[0] as TransactionBeginFrameV1).value)
        receiver.acknowledgeBegin(TransactionBeginAcknowledgementV1(1, 1, 1))
        receiver.commit((frames[1] as TransactionCommitFrameV1).value)
        receiver.acknowledgeCommit(TransactionAcknowledgementV1(1, 1, 1))

        assertEquals(StructuralTransactionState.READY, receiver.state)
        assertEquals(1L, receiver.visibleTransactionId)
        assertEquals(1L, receiver.visibleGeometryRevision)
        assertEquals(1L, receiver.visibleLineageRevision)
        assertTrue(receiver.visibleBytes.isEmpty())
    }

    @Test
    fun `structural frames round trip through the packed response envelope`() {
        val frames = StructuralTransactionProducerV1.produce(
            transactionId = 3,
            baseGeometryRevision = 4,
            targetGeometryRevision = 5,
            targetLineageRevision = 6,
            bytes = byteArrayOf(1, 2, 3, 4, 5),
        )
        frames.forEachIndexed { index, frame ->
            val encoded = PacketCodec.encodeResponse(
                TransactionResponseCodecV1.encodeFrame(
                    frame = frame,
                    streamToken = 91,
                    requestSequence = index.toLong() + 1,
                    nextExpectedRequestSequence = index.toLong() + 2,
                ),
                PacketCodec.responseMaximumBytes,
            )
            val decoded = TransactionResponseCodecV1.decodeFrame(
                PacketCodec.decodeResponse(encoded),
            )
            when (frame) {
                is TransactionBeginFrameV1 -> {
                    val actual = decoded as TransactionBeginFrameV1
                    assertEquals(frame.value, actual.value)
                }
                is TransactionChunkFrameV1 -> {
                    val actual = decoded as TransactionChunkFrameV1
                    assertEquals(frame.value.transactionId, actual.value.transactionId)
                    assertEquals(frame.value.chunkIndex, actual.value.chunkIndex)
                    assertEquals(frame.value.offset, actual.value.offset)
                    assertArrayEquals(frame.value.bytes, actual.value.bytes)
                }
                is TransactionCommitFrameV1 -> {
                    val actual = decoded as TransactionCommitFrameV1
                    assertEquals(frame.value, actual.value)
                }
            }
        }
    }

    @Test
    fun `structural response kinds match canonical Ch13 IDs`() {
        val frames = StructuralTransactionProducerV1.produce(
            transactionId = 3,
            baseGeometryRevision = 4,
            targetGeometryRevision = 5,
            targetLineageRevision = 6,
            bytes = byteArrayOf(1),
        )
        val kinds = frames.map { frame ->
            TransactionResponseCodecV1.encodeFrame(
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
        val exactRequest = PacketCodec.encodeRequest(request(commandBytes = ByteArray(16_304)))
        assertEquals(PacketCodec.requestCeilingBytes, exactRequest.size)
        assertArrayEquals(exactRequest, PacketCodec.encodeRequest(request(commandBytes = ByteArray(16_304))))
        assertThrows(IllegalArgumentException::class.java) {
            PacketCodec.encodeRequest(request(commandBytes = ByteArray(16_305)))
        }

        val maximum = ByteArray(StructuralTransactionLimits.MAX_STRUCTURAL_TRANSACTION_BYTES)
        val frames = StructuralTransactionProducerV1.produce(
            transactionId = 3,
            baseGeometryRevision = 4,
            targetGeometryRevision = 5,
            targetLineageRevision = 6,
            bytes = maximum,
        )
        assertEquals(67, frames.size)
        frames.forEachIndexed { index, frame ->
            val response = TransactionResponseCodecV1.encodeFrame(
                frame,
                streamToken = 9,
                requestSequence = index.toLong() + 1,
                nextExpectedRequestSequence = index.toLong() + 2,
            )
            assertTrue(PacketCodec.encodeResponse(response, PacketCodec.responseMaximumBytes).size <=
                PacketCodec.responseMaximumBytes)
        }
        assertThrows(IllegalArgumentException::class.java) {
            StructuralTransactionProducerV1.produce(
                transactionId = 3,
                baseGeometryRevision = 4,
                targetGeometryRevision = 5,
                targetLineageRevision = 6,
                bytes = ByteArray(StructuralTransactionLimits.MAX_STRUCTURAL_TRANSACTION_BYTES + 1),
            )
        }
    }

    @Test
    fun `catch up ceiling derives bounded stride and frame count from response envelope`() {
        assertEquals(3_972, TransactionResponseProfileV1(PacketCodec.responseMinimumBytes).chunkPayloadBytes)
        assertEquals(16_260, StructuralTransactionLimits.ordinaryChunkPayloadBytes)
        assertEquals(65_412, StructuralTransactionLimits.catchUpChunkPayloadBytes)
        assertEquals(67, StructuralTransactionLimits.frameCount(
            StructuralTransactionLimits.MAX_STRUCTURAL_TRANSACTION_BYTES,
            PacketCodec.responseMaximumBytes,
        ))
        assertEquals(19, StructuralTransactionLimits.frameCount(
            StructuralTransactionLimits.MAX_STRUCTURAL_TRANSACTION_BYTES,
            PacketCodec.catchUpMaximumBytes,
        ))
        val frames = StructuralTransactionProducerV1.produce(
            transactionId = 3,
            baseGeometryRevision = 4,
            targetGeometryRevision = 5,
            targetLineageRevision = 6,
            bytes = ByteArray(StructuralTransactionLimits.MAX_STRUCTURAL_TRANSACTION_BYTES),
            responseProfile = TransactionResponseProfileV1.catchUp,
        )
        assertEquals(19, frames.size)
        frames.forEachIndexed { index, frame ->
            val response = TransactionResponseCodecV1.encodeFrame(
                frame,
                streamToken = 9,
                requestSequence = index.toLong() + 1,
                nextExpectedRequestSequence = index.toLong() + 2,
            )
            assertTrue(PacketCodec.encodeResponse(response, PacketCodec.catchUpMaximumBytes).size <=
                PacketCodec.catchUpMaximumBytes)
        }
        assertThrows(IllegalArgumentException::class.java) {
            TransactionResponseProfileV1.forChunkPayloadBytes(65_413)
        }
        assertThrows(IllegalArgumentException::class.java) {
            TransactionResponseProfileV1.forChunkPayloadBytes(65_535)
        }
    }

    @Test
    fun `duplicate exact chunk is invisible and changed duplicate is rejected`() {
        val receiver = StructuralTransactionReceiverV1()
        val bytes = byteArrayOf(1, 2, 3, 4)
        receiver.begin(begin(bytes))
        receiver.acknowledgeBegin(TransactionBeginAcknowledgementV1(7, 11, 12))
        val first = TransactionChunkV1(7, 0, byteArrayOf(1, 2), 0)
        receiver.chunk(first)
        receiver.chunk(first.copy(bytes = first.bytes.copyOf()))
        assertEquals(2, receiver.stagedBytes)
        assertThrows(IllegalArgumentException::class.java) {
            receiver.chunk(TransactionChunkV1(7, 0, byteArrayOf(9, 2), 0))
        }
        receiver.chunk(TransactionChunkV1(7, 1, byteArrayOf(3, 4), 2))
        receiver.commit(TransactionCommitV1(7, checksum(bytes)))
        receiver.acknowledgeCommit(TransactionAcknowledgementV1(7, 11, 12))
        assertArrayEquals(bytes, receiver.visibleBytes)
    }

    private fun begin(bytes: ByteArray) = TransactionBeginV1(
        transactionId = 7,
        baseGeometryRevision = 10,
        targetGeometryRevision = 11,
        targetLineageRevision = 12,
        chunkCount = if (bytes.isEmpty()) 0 else 2,
        totalBytes = bytes.size,
        payloadChecksum = checksum(bytes),
    )

    private fun request(commandBytes: ByteArray) = PacketCodec.Request(
        requestFlags = 0,
        streamToken = 1,
        acknowledgedTransactionId = 0,
        acknowledgedGeometryRevision = 0,
        acknowledgedLineageRevision = 0,
        nextStyleRevision = 0,
        maximumResponseBytes = PacketCodec.responseMaximumBytes,
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
