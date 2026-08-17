package com.uhg0.ar_flutter_plugin_2.m0

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Exact fixed framing shared by the M0a Dart and Android reference codecs. */
object M0aPacketCodec {
    const val requestHeaderBytes = 80
    const val responseHeaderBytes = 112
    const val requestCeilingBytes = 16 * 1024
    const val responseMinimumBytes = 4 * 1024
    const val responseMaximumBytes = 16 * 1024
    const val catchUpMaximumBytes = 64 * 1024
    const val styleRecordBytes = 8
    const val ordinaryMessageKind = 1

    data class Request(
        val requestFlags: Int,
        val streamToken: Long,
        val acknowledgedTransactionId: Long,
        val acknowledgedGeometryRevision: Long,
        val acknowledgedLineageRevision: Long,
        val nextStyleRevision: Long,
        val maximumResponseBytes: Int,
        val styleRecords: List<ByteArray>,
        val commandBytes: ByteArray,
        val requestSequence: Long,
    )

    data class Response(
        val messageKind: Int,
        val responseFlags: Int,
        val resultFlags: Int,
        val errorId: Int,
        val requestSequence: Long,
        val streamToken: Long = 0,
        val nextExpectedRequestSequence: Long = 0,
        val transactionId: Long = 0,
        val baseGeometryRevision: Long = 0,
        val targetGeometryRevision: Long = 0,
        val targetLineageRevision: Long = 0,
        val acceptedStyleRevision: Long = 0,
        val chunkIndex: Int = 0,
        val chunkCount: Int = 0,
        val upsertCount: Int = 0,
        val removalCount: Int = 0,
        val lineageCount: Int = 0,
        val regionResultCount: Int = 0,
        val payload: ByteArray = byteArrayOf(),
        val diagnostic: ByteArray = byteArrayOf(),
    )

    fun encodeRequest(request: Request): ByteArray {
        validateOrdinal(request.streamToken, "streamToken")
        validateOrdinal(request.requestSequence, "requestSequence")
        validateOrdinal(request.acknowledgedTransactionId, "acknowledgedTransactionId", true)
        validateOrdinal(request.acknowledgedGeometryRevision, "acknowledgedGeometryRevision", true)
        validateOrdinal(request.acknowledgedLineageRevision, "acknowledgedLineageRevision", true)
        validateOrdinal(request.nextStyleRevision, "nextStyleRevision", true)
        require(request.requestFlags in 0..0x3f) { "Request flags contain reserved bits" }
        require(request.maximumResponseBytes in responseMinimumBytes..responseMaximumBytes) {
            "maximumResponseBytes is outside the negotiated range"
        }
        require(request.styleRecords.all { it.size == styleRecordBytes }) {
            "Style records must be exactly 8 bytes"
        }
        val commandBytes = request.commandBytes.size
        val payloadBytes = request.styleRecords.size * styleRecordBytes + commandBytes
        val packetBytes = requestHeaderBytes + payloadBytes
        require(packetBytes <= requestCeilingBytes) { "Request exceeds the 16 KiB ceiling" }
        require(commandBytes <= 0xffff) { "Command section is too large" }
        val packet = ByteArray(packetBytes)
        val data = ByteBuffer.wrap(packet).order(ByteOrder.LITTLE_ENDIAN)
        packet.writeMagic("VGR2")
        data.putShort(4, 2)
        data.putShort(6, requestHeaderBytes.toShort())
        data.putShort(8, request.requestFlags.toShort())
        data.putShort(10, 0)
        data.putInt(12, packetBytes)
        data.putLong(16, request.streamToken)
        data.putLong(24, request.acknowledgedTransactionId)
        data.putLong(32, request.acknowledgedGeometryRevision)
        data.putLong(40, request.acknowledgedLineageRevision)
        data.putLong(48, request.nextStyleRevision)
        data.putInt(56, request.maximumResponseBytes)
        data.putShort(60, request.styleRecords.size.toShort())
        data.putShort(62, commandBytes.toShort())
        data.putLong(64, request.requestSequence)
        data.putInt(72, 0)
        data.putInt(76, 0)
        var offset = requestHeaderBytes
        request.styleRecords.forEach { record ->
            record.copyInto(packet, offset)
            offset += record.size
        }
        request.commandBytes.copyInto(packet, offset)
        data.putInt(72, crc32(packet, 72))
        return packet
    }

    fun decodeRequest(packet: ByteArray): Request {
        require(packet.size >= requestHeaderBytes) { "Request is shorter than its header" }
        val data = ByteBuffer.wrap(packet).order(ByteOrder.LITTLE_ENDIAN)
        require(packet.magicIs("VGR2")) { "Packet magic is invalid" }
        require(data.getShort(4).toInt() and 0xffff == 2) { "Unsupported request schema" }
        require(data.getShort(6).toInt() and 0xffff == requestHeaderBytes) {
            "Unsupported request header"
        }
        require(data.getShort(10).toInt() == 0 && data.getInt(76) == 0) {
            "Request reserved fields are non-zero"
        }
        require(data.getInt(12).toLong() and 0xffffffffL == packet.size.toLong()) {
            "Request length is invalid"
        }
        require(packet.size <= requestCeilingBytes) { "Request exceeds the 16 KiB ceiling" }
        require(data.getInt(72) == crc32(packet, 72)) { "Request CRC is invalid" }
        val styleCount = data.getShort(60).toInt() and 0xffff
        val commandBytes = data.getShort(62).toInt() and 0xffff
        require(requestHeaderBytes + styleCount * styleRecordBytes + commandBytes == packet.size) {
            "Request payload lengths are invalid"
        }
        var offset = requestHeaderBytes
        val styles = buildList {
            repeat(styleCount) {
                add(packet.copyOfRange(offset, offset + styleRecordBytes))
                offset += styleRecordBytes
            }
        }
        val request = Request(
            requestFlags = data.getShort(8).toInt() and 0xffff,
            streamToken = data.getLong(16),
            acknowledgedTransactionId = data.getLong(24),
            acknowledgedGeometryRevision = data.getLong(32),
            acknowledgedLineageRevision = data.getLong(40),
            nextStyleRevision = data.getLong(48),
            maximumResponseBytes = data.getInt(56),
            styleRecords = styles,
            commandBytes = packet.copyOfRange(offset, packet.size),
            requestSequence = data.getLong(64),
        )
        validateOrdinal(request.streamToken, "streamToken")
        validateOrdinal(request.requestSequence, "requestSequence")
        validateOrdinal(request.acknowledgedTransactionId, "acknowledgedTransactionId", true)
        validateOrdinal(request.acknowledgedGeometryRevision, "acknowledgedGeometryRevision", true)
        validateOrdinal(request.acknowledgedLineageRevision, "acknowledgedLineageRevision", true)
        validateOrdinal(request.nextStyleRevision, "nextStyleRevision", true)
        require(request.requestFlags <= 0x3f) { "Request flags contain reserved bits" }
        require(request.maximumResponseBytes in responseMinimumBytes..responseMaximumBytes) {
            "maximumResponseBytes is outside the negotiated range"
        }
        return request
    }

    fun encodeResponse(response: Response, maximumBytes: Int): ByteArray {
        validateResponse(response)
        require(response.resultFlags in 0..0x1f)
        require(response.errorId in 0..0xffff)
        require(response.requestSequence in 0..Long.MAX_VALUE)
        require(response.streamToken in 0..Long.MAX_VALUE)
        require(response.nextExpectedRequestSequence in 0..Long.MAX_VALUE)
        require(response.transactionId in 0..Long.MAX_VALUE)
        require(response.baseGeometryRevision in 0..Long.MAX_VALUE)
        require(response.targetGeometryRevision in 0..Long.MAX_VALUE)
        require(response.targetLineageRevision in 0..Long.MAX_VALUE)
        require(response.acceptedStyleRevision in 0..Long.MAX_VALUE)
        require(response.chunkIndex in 0..0xffff)
        require(response.chunkCount in 0..0xffff)
        require(response.upsertCount in 0..0xffff)
        require(response.removalCount in 0..0xffff)
        require(response.lineageCount in 0..0xffff)
        require(response.regionResultCount in 0..0xffff)
        require(response.diagnostic.size <= 1024) { "Response diagnostic exceeds 1 KiB" }
        val body = response.payload + response.diagnostic
        val packetBytes = responseHeaderBytes + body.size
        require(packetBytes <= maximumBytes && packetBytes <= catchUpMaximumBytes) {
            "Response exceeds negotiated or hard ceiling"
        }
        val packet = ByteArray(packetBytes)
        val data = ByteBuffer.wrap(packet).order(ByteOrder.LITTLE_ENDIAN)
        packet.writeMagic("VGS2")
        data.putShort(4, 2)
        data.putShort(6, responseHeaderBytes.toShort())
        data.put(8, response.messageKind.toByte())
        data.put(9, response.responseFlags.toByte())
        data.putShort(10, response.resultFlags.toShort())
        data.putShort(12, response.errorId.toShort())
        data.putShort(14, 0)
        data.putInt(16, packetBytes)
        data.putShort(20, response.diagnostic.size.toShort())
        data.putShort(22, 0)
        data.putLong(24, response.streamToken)
        data.putLong(32, response.requestSequence)
        data.putLong(40, response.nextExpectedRequestSequence)
        data.putLong(48, response.transactionId)
        data.putLong(56, response.baseGeometryRevision)
        data.putLong(64, response.targetGeometryRevision)
        data.putLong(72, response.targetLineageRevision)
        data.putLong(80, response.acceptedStyleRevision)
        data.putShort(88, response.chunkIndex.toShort())
        data.putShort(90, response.chunkCount.toShort())
        data.putShort(92, response.upsertCount.toShort())
        data.putShort(94, response.removalCount.toShort())
        data.putShort(96, response.lineageCount.toShort())
        data.putShort(98, response.regionResultCount.toShort())
        data.putInt(100, body.size)
        data.putInt(104, 0)
        data.putInt(108, 0)
        body.copyInto(packet, responseHeaderBytes)
        data.putInt(104, crc32(packet, 104))
        return packet
    }

    fun decodeResponse(packet: ByteArray): Response {
        require(packet.size >= responseHeaderBytes) { "Response is shorter than its header" }
        require(packet.size <= catchUpMaximumBytes) { "Response exceeds the 64 KiB catch-up ceiling" }
        val data = ByteBuffer.wrap(packet).order(ByteOrder.LITTLE_ENDIAN)
        require(packet.magicIs("VGS2")) { "Packet magic is invalid" }
        require(data.getShort(4).toInt() and 0xffff == 2)
        require(data.getShort(6).toInt() and 0xffff == responseHeaderBytes)
        require(data.getShort(14).toInt() == 0 && data.getShort(22).toInt() == 0)
        require(data.getInt(108) == 0)
        require(data.getShort(10).toInt() and 0xffff <= 0x1f)
        require(data.getInt(16).toLong() and 0xffffffffL == packet.size.toLong())
        val payloadBytes = data.getInt(100)
        val diagnosticBytes = data.getShort(20).toInt() and 0xffff
        require(payloadBytes == packet.size - responseHeaderBytes)
        require(diagnosticBytes <= payloadBytes)
        require(data.getInt(104) == crc32(packet, 104)) { "Response CRC is invalid" }
        val split = packet.size - diagnosticBytes
        return Response(
            messageKind = data.get(8).toInt() and 0xff,
            responseFlags = data.get(9).toInt() and 0xff,
            resultFlags = data.getShort(10).toInt() and 0xffff,
            errorId = data.getShort(12).toInt() and 0xffff,
            requestSequence = data.getLong(32),
            streamToken = data.getLong(24),
            nextExpectedRequestSequence = data.getLong(40),
            transactionId = data.getLong(48),
            baseGeometryRevision = data.getLong(56),
            targetGeometryRevision = data.getLong(64),
            targetLineageRevision = data.getLong(72),
            acceptedStyleRevision = data.getLong(80),
            chunkIndex = data.getShort(88).toInt() and 0xffff,
            chunkCount = data.getShort(90).toInt() and 0xffff,
            upsertCount = data.getShort(92).toInt() and 0xffff,
            removalCount = data.getShort(94).toInt() and 0xffff,
            lineageCount = data.getShort(96).toInt() and 0xffff,
            payload = packet.copyOfRange(responseHeaderBytes, split),
            diagnostic = packet.copyOfRange(split, packet.size),
            regionResultCount = data.getShort(98).toInt() and 0xffff,
        ).also(::validateResponse)
    }

    fun noChanges(
        streamToken: Long,
        requestSequence: Long,
        nextExpectedRequestSequence: Long,
    ): Response = Response(
        messageKind = ordinaryMessageKind,
        responseFlags = 0,
        resultFlags = 0,
        errorId = 0,
        requestSequence = requestSequence,
        streamToken = streamToken,
        nextExpectedRequestSequence = nextExpectedRequestSequence,
    )

    /** A bounded response instructing the worker to rebuild its acknowledgement baseline. */
    fun resyncRequired(
        streamToken: Long,
        requestSequence: Long,
        nextExpectedRequestSequence: Long,
    ): Response = Response(
        messageKind = 5,
        responseFlags = 0,
        resultFlags = 8,
        errorId = 0,
        requestSequence = requestSequence,
        streamToken = streamToken,
        nextExpectedRequestSequence = nextExpectedRequestSequence,
    )

    fun error(
        streamToken: Long,
        requestSequence: Long,
        nextExpectedRequestSequence: Long,
        errorId: Int = 1,
    ): Response = Response(
        messageKind = 255,
        responseFlags = 0,
        resultFlags = 0,
        errorId = errorId,
        requestSequence = requestSequence,
        streamToken = streamToken,
        nextExpectedRequestSequence = nextExpectedRequestSequence,
    )

    /** CRC-32 for transaction payloads, shared by all VGS2 body codecs. */
    internal fun crc32Payload(bytes: ByteArray): Long {
        var crc = -1
        bytes.forEach { original ->
            crc = crc xor (original.toInt() and 0xff)
            repeat(8) {
                crc = if ((crc and 1) == 1) {
                    (crc ushr 1) xor 0xedb88320.toInt()
                } else {
                    crc ushr 1
                }
            }
        }
        return (crc xor -1).toLong() and 0xffff_ffffL
    }

    private fun validateOrdinal(value: Long, name: String, allowZero: Boolean = false) {
        require(value >= (if (allowZero) 0 else 1) && value <= Long.MAX_VALUE) {
            "$name is outside PortableOrdinal"
        }
    }

    private fun validateResponse(response: Response) {
        require(response.messageKind in setOf(1, 2, 3, 4, 5, 255)) {
            "Response message kind is invalid"
        }
        require(response.responseFlags in 0..0x1f) {
            "Response flags contain reserved bits"
        }
        if (response.messageKind == 255) {
            require(response.errorId in 1..150) { "Error response has no stable error ID" }
        } else {
            require(response.errorId == 0) { "Non-error response has a non-zero error ID" }
        }
        require(response.requestSequence in 0..Long.MAX_VALUE)
        validateOrdinal(response.streamToken, "streamToken", true)
        validateOrdinal(response.nextExpectedRequestSequence, "nextExpectedRequestSequence", true)
        validateOrdinal(response.transactionId, "transactionId", true)
        validateOrdinal(response.baseGeometryRevision, "baseGeometryRevision", true)
        validateOrdinal(response.targetGeometryRevision, "targetGeometryRevision", true)
        validateOrdinal(response.targetLineageRevision, "targetLineageRevision", true)
        validateOrdinal(response.acceptedStyleRevision, "acceptedStyleRevision", true)
        require(response.chunkIndex in 0..0xffff)
        require(response.chunkCount in 0..0xffff)
        require(response.upsertCount in 0..0xffff)
        require(response.removalCount in 0..0xffff)
        require(response.lineageCount in 0..0xffff)
        require(response.regionResultCount in 0..0xffff)
        require(response.diagnostic.size <= 1024) { "Response diagnostic exceeds 1 KiB" }
    }

    private fun ByteArray.writeMagic(value: String) {
        value.toByteArray(Charsets.US_ASCII).copyInto(this)
    }

    private fun ByteArray.magicIs(value: String): Boolean =
        value.toByteArray(Charsets.US_ASCII).contentEquals(copyOfRange(0, 4))

    private fun crc32(bytes: ByteArray, zeroOffset: Int): Int {
        var crc = -1
        bytes.forEachIndexed { index, original ->
            val byte = if (index in zeroOffset until zeroOffset + 4) 0 else original.toInt() and 0xff
            crc = crc xor byte
            repeat(8) {
                crc = if ((crc and 1) == 1) (crc ushr 1) xor 0xedb88320.toInt() else crc ushr 1
            }
        }
        return crc xor -1
    }
}
