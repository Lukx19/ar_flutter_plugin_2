package com.uhg0.ar_flutter_plugin_2.m0

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** VGS2 structural BEGIN/CHUNK/COMMIT body codec shared with Dart. */
object M0aTransactionResponseCodecV1 {
    const val beginMessageKind = 2
    const val chunkMessageKind = 3
    const val commitMessageKind = 4

    fun payloadChecksum(bytes: ByteArray): Long = M0aPacketCodec.crc32Payload(bytes)

    /** Maximum CHUNK data bytes that still leave room for the VGS2 envelope. */
    fun chunkPayloadBytesForResponseCeiling(responseCeilingBytes: Int): Int =
        M0aStructuralTransactionLimits.chunkPayloadBytesForResponseCeiling(responseCeilingBytes)

    fun encodeFrame(
        frame: M0aTransactionFrameV1,
        streamToken: Long,
        requestSequence: Long,
        nextExpectedRequestSequence: Long,
    ): M0aPacketCodec.Response {
        return when (frame) {
            is M0aTransactionBeginFrameV1 -> {
                val value = frame.value
                val body = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
                    .putInt(value.totalBytes)
                    .putInt(value.payloadChecksum.toInt())
                    .array()
                M0aPacketCodec.Response(
                    messageKind = beginMessageKind,
                    responseFlags = 0,
                    resultFlags = 0,
                    errorId = 0,
                    requestSequence = requestSequence,
                    streamToken = streamToken,
                    nextExpectedRequestSequence = nextExpectedRequestSequence,
                    transactionId = value.transactionId,
                    baseGeometryRevision = value.baseGeometryRevision,
                    targetGeometryRevision = value.targetGeometryRevision,
                    targetLineageRevision = value.targetLineageRevision,
                    chunkCount = value.chunkCount,
                    payload = body,
                )
            }
            is M0aTransactionChunkFrameV1 -> {
                val value = frame.value
                require(value.bytes.isNotEmpty()) { "CHUNK must not be empty" }
                val offset = value.offset ?: value.chunkIndex * chunkStride
                require(offset in 0..M0aStructuralTransactionLimits.MAX_STRUCTURAL_TRANSACTION_BYTES)
                require(value.bytes.size <= M0aStructuralTransactionLimits.MAX_STRUCTURAL_TRANSACTION_BYTES)
                require(offset <= Int.MAX_VALUE - value.bytes.size)
                val body = ByteBuffer.allocate(12 + value.bytes.size)
                    .order(ByteOrder.LITTLE_ENDIAN)
                    .putInt(offset)
                    .putInt(value.bytes.size)
                    .putInt(M0aPacketCodec.crc32Payload(value.bytes).toInt())
                    .put(value.bytes)
                    .array()
                M0aPacketCodec.Response(
                    messageKind = chunkMessageKind,
                    responseFlags = 0,
                    resultFlags = 0,
                    errorId = 0,
                    requestSequence = requestSequence,
                    streamToken = streamToken,
                    nextExpectedRequestSequence = nextExpectedRequestSequence,
                    transactionId = value.transactionId,
                    chunkIndex = value.chunkIndex,
                    payload = body,
                )
            }
            is M0aTransactionCommitFrameV1 -> {
                val value = frame.value
                val body = ByteBuffer.allocate(4)
                    .order(ByteOrder.LITTLE_ENDIAN)
                    .putInt(value.payloadChecksum.toInt())
                    .array()
                M0aPacketCodec.Response(
                    messageKind = commitMessageKind,
                    responseFlags = 0,
                    resultFlags = 0,
                    errorId = 0,
                    requestSequence = requestSequence,
                    streamToken = streamToken,
                    nextExpectedRequestSequence = nextExpectedRequestSequence,
                    transactionId = value.transactionId,
                    payload = body,
                )
            }
        }
    }

    fun decodeFrame(response: M0aPacketCodec.Response): M0aTransactionFrameV1 {
        require(response.responseFlags == 0 && response.resultFlags == 0 && response.errorId == 0) {
            "Transaction response has non-transaction flags"
        }
        require(response.diagnostic.isEmpty()) { "Transaction response has diagnostics" }
        val data = ByteBuffer.wrap(response.payload).order(ByteOrder.LITTLE_ENDIAN)
        return when (response.messageKind) {
            beginMessageKind -> {
                require(response.payload.size == 8 && response.transactionId > 0)
                require(response.baseGeometryRevision >= 0)
                require(response.targetGeometryRevision > 0 && response.targetLineageRevision > 0)
                M0aTransactionBeginFrameV1(
                    M0aTransactionBeginV1(
                        transactionId = response.transactionId,
                        baseGeometryRevision = response.baseGeometryRevision,
                        targetGeometryRevision = response.targetGeometryRevision,
                        targetLineageRevision = response.targetLineageRevision,
                        chunkCount = response.chunkCount,
                        totalBytes = data.getInt(0),
                        payloadChecksum = data.getInt(4).toLong() and 0xffff_ffffL,
                    ),
                )
            }
            chunkMessageKind -> {
                require(response.payload.size >= 12 && response.transactionId > 0)
                val offset = data.getInt(0).toLong() and 0xffff_ffffL
                val length = data.getInt(4)
                val checksum = data.getInt(8).toLong() and 0xffff_ffffL
                require(length > 0 && length == response.payload.size - 12)
                require(offset <= M0aStructuralTransactionLimits.MAX_STRUCTURAL_TRANSACTION_BYTES &&
                    offset <= 0xffff_ffffL - length &&
                    offset + length <= M0aStructuralTransactionLimits.MAX_STRUCTURAL_TRANSACTION_BYTES.toLong())
                val bytes = response.payload.copyOfRange(12, response.payload.size)
                require(M0aPacketCodec.crc32Payload(bytes) == checksum) {
                    "CHUNK checksum is invalid"
                }
                M0aTransactionChunkFrameV1(
                    M0aTransactionChunkV1(response.transactionId, response.chunkIndex, bytes, offset.toInt()),
                )
            }
            commitMessageKind -> {
                require(response.payload.size == 4 && response.transactionId > 0)
                M0aTransactionCommitFrameV1(
                    M0aTransactionCommitV1(
                        response.transactionId,
                        data.getInt(0).toLong() and 0xffff_ffffL,
                    ),
                )
            }
            else -> error("Response is not a structural transaction frame")
        }
    }

    private val chunkStride: Int
        get() = M0aStructuralTransactionLimits.ordinaryChunkPayloadBytes

}
