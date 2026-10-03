package com.uhg0.ar_flutter_plugin_2.visibilityprotocol

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Exact fixed framing shared by the visibility protocol Dart and Android reference codecs. */
object PacketCodec {
    const val requestHeaderBytes = 80
    const val responseHeaderBytes = 112
    const val requestCeilingBytes = 16 * 1024
    const val responseMinimumBytes = 4 * 1024
    const val responseMaximumBytes = 16 * 1024
    const val catchUpMaximumBytes = 64 * 1024
    const val styleRecordBytes = 8
    const val noChangesMessageKind = 0

    data class ErrorAuthority(
        val geometryRevision: Long = 0,
        val lineageRevision: Long = 0,
        val captureRevision: Long = 0,
        val coverageRevision: Long = 0,
        val acceptedStyleRevision: Long = 0,
        val regionManifestRevision: Long = 0,
        val nextSurfaceIdHighWater: Long = 0,
        val schemaRootRevision: Long = 0,
    )

    data class ErrorPolicy(
        val resultFlags: Int,
        val disposition: Int,
        val validationPhase: Int,
        val recoveryAction: Int,
        val fieldId: Int,
        val sequenceDisposition: SequenceDisposition,
    )

    enum class SequenceDisposition { UNCHANGED, ADJACENT, BINDING_INVALID }

    fun errorPolicy(errorId: Int): ErrorPolicy = when (errorId) {
        4 -> ErrorPolicy(2, 2, 4, 4, 4, SequenceDisposition.BINDING_INVALID)
        6 -> ErrorPolicy(0, 0, 2, 0, 0, SequenceDisposition.UNCHANGED)
        8 -> ErrorPolicy(9, 1, 8, 3, 9, SequenceDisposition.ADJACENT)
        30 -> ErrorPolicy(0, 0, 5, 4, 9, SequenceDisposition.UNCHANGED)
        31 -> ErrorPolicy(0, 0, 5, 1, 9, SequenceDisposition.UNCHANGED)
        32 -> ErrorPolicy(0, 0, 5, 0, 9, SequenceDisposition.UNCHANGED)
        34 -> ErrorPolicy(0, 0, 7, 3, 9, SequenceDisposition.UNCHANGED)
        35 -> ErrorPolicy(0, 0, 5, 4, 9, SequenceDisposition.UNCHANGED)
        48 -> ErrorPolicy(0, 0, 7, 0, 0, SequenceDisposition.UNCHANGED)
        142 -> ErrorPolicy(2, 2, 8, 4, 0, SequenceDisposition.BINDING_INVALID)
        144 -> ErrorPolicy(2, 2, 9, 4, 0, SequenceDisposition.BINDING_INVALID)
        else -> ErrorPolicy(0, 0, 5, 0, 0, SequenceDisposition.UNCHANGED)
    }

    fun nextSequenceForPolicy(
        errorId: Int,
        requestSequence: Long,
        currentExpectedSequence: Long = requestSequence,
    ): Long =
        when (errorPolicy(errorId).sequenceDisposition) {
            SequenceDisposition.UNCHANGED -> currentExpectedSequence
            SequenceDisposition.ADJACENT -> {
                require(requestSequence < Long.MAX_VALUE) { "Consumed error cannot advance MAX" }
                requestSequence + 1
            }
            SequenceDisposition.BINDING_INVALID -> 0
        }

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
        private var commandRangeOwner: ByteArray = commandBytes,
        private var commandRangeOffset: Int = 0,
        private var commandRangeLength: Int = commandBytes.size,
    ) {
        init {
            // Data-class copy carries its old range fields. A replacement array
            // starts a new full command; unchanged storage preserves its view.
            // These private fields are normalized only during construction.
            if (commandRangeOwner !== commandBytes) {
                commandRangeOwner = commandBytes
                commandRangeOffset = 0
                commandRangeLength = commandBytes.size
            }
        }
        val commandOffset: Int get() = commandRangeOffset
        val commandLength: Int get() = commandRangeLength
        val styleCount: Int get() = styleRecords.size
    }

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
        val size = requestHeaderBytes.toLong() + request.styleRecords.size.toLong() * styleRecordBytes + request.commandLength
        require(size <= requestCeilingBytes) { "Request exceeds the 16 KiB ceiling" }
        return ByteArray(size.toInt()).also { encodeRequestInto(request, it) }
    }

    /** Caller-private destination is borrowed only for this synchronous call. */
    fun encodeRequestInto(request: Request, destination: ByteArray, destinationOffset: Int = 0): Int {
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
        require(request.commandOffset >= 0 && request.commandLength >= 0 &&
            request.commandOffset <= request.commandBytes.size - request.commandLength)
        val commandBytes = request.commandLength
        val payloadBytes = request.styleRecords.size * styleRecordBytes + commandBytes
        val packetBytes = requestHeaderBytes + payloadBytes
        require(packetBytes <= requestCeilingBytes) { "Request exceeds the 16 KiB ceiling" }
        require(commandBytes <= 0xffff) { "Command section is too large" }
        require(destinationOffset >= 0 && destinationOffset <= destination.size - packetBytes) { "Request destination is too small" }
        val data = ByteBuffer.wrap(destination, destinationOffset, packetBytes).slice().order(ByteOrder.LITTLE_ENDIAN)
        data.put(0, 0x56.toByte()); data.put(1, 0x47.toByte()); data.put(2, 0x52.toByte()); data.put(3, 0x32.toByte())
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
        var offset = destinationOffset + requestHeaderBytes
        request.styleRecords.forEach { record ->
            record.copyInto(destination, offset)
            offset += record.size
        }
        request.commandBytes.copyInto(destination, offset, request.commandOffset, request.commandOffset + request.commandLength)
        data.putInt(72, crc32(destination, 72, destinationOffset, packetBytes))
        return packetBytes
    }

    fun decodeRequest(packet: ByteArray): Request = decodeRequestView(packet).let { request ->
        request.copy(
            styleRecords = request.styleRecords.toList(),
            commandBytes = request.commandBytes.copyOfRange(
                request.commandOffset, request.commandOffset + request.commandLength,
            ),
        )
    }

    /** Packet is privately owned by ingress and remains immutable throughout queued/replay ownership. */
    internal fun decodeRequestView(packet: ByteArray): Request {
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
        val commandOffset = requestHeaderBytes + styleCount * styleRecordBytes
        // Compatibility accessor materializes a record only when explicitly requested.
        // Production admission uses styleCount and never creates per-record byte arrays.
        val styles = object : AbstractList<ByteArray>() {
            override val size: Int get() = styleCount
            override fun get(index: Int): ByteArray {
                require(index in 0 until size)
                val offset = requestHeaderBytes + index * styleRecordBytes
                return packet.copyOfRange(offset, offset + styleRecordBytes)
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
            commandBytes = packet,
            commandRangeOffset = commandOffset,
            commandRangeLength = commandBytes,
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
        return ByteArray(validatedResponseSize(response, maximumBytes)).also {
            encodeResponseInto(response, maximumBytes, it)
        }
    }

    /** Complete validation without allocating a throwaway encoded response. */
    fun validatedResponseSize(response: Response, maximumBytes: Int): Int {
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
        val packetBytes = responseHeaderBytes.toLong() + response.payload.size + response.diagnostic.size
        require(packetBytes <= maximumBytes && packetBytes <= catchUpMaximumBytes) {
            "Response exceeds negotiated or hard ceiling"
        }
        return packetBytes.toInt()
    }

    fun encodeResponseInto(response: Response, maximumBytes: Int, destination: ByteArray, destinationOffset: Int = 0): Int =
        encodeResponseInto(response, maximumBytes, ByteBuffer.wrap(destination), destinationOffset)

    /** Writes directly into caller storage and advances its position to the exact packet end.
     * The ceiling and CRC cover only the packet; prefix/suffix bytes remain untouched.
     */
    fun encodeResponseInto(response: Response, maximumBytes: Int, destination: ByteBuffer, destinationOffset: Int = destination.position()): Int {
        val packetBytes = validatedResponseSize(response, maximumBytes)
        require(!destination.isReadOnly) { "Response destination is read-only" }
        require(destinationOffset >= 0 && destinationOffset <= destination.limit() - packetBytes) { "Response destination is too small" }
        val bodyBytes = response.payload.size + response.diagnostic.size
        val data = destination.duplicate().apply {
            position(destinationOffset)
            limit(destinationOffset + packetBytes)
        }.slice().order(ByteOrder.LITTLE_ENDIAN)
        data.put(0, 0x56.toByte()); data.put(1, 0x47.toByte()); data.put(2, 0x53.toByte()); data.put(3, 0x32.toByte())
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
        data.putInt(100, bodyBytes)
        data.putInt(104, 0)
        data.putInt(108, 0)
        data.position(responseHeaderBytes)
        data.put(response.payload)
        data.put(response.diagnostic)
        data.putInt(104, crc32(data, 104, packetBytes))
        destination.position(destinationOffset + packetBytes)
        return packetBytes
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
        transactionId: Long = 0,
        targetGeometryRevision: Long = 0,
        targetLineageRevision: Long = 0,
        acceptedStyleRevision: Long = 0,
        resultFlags: Int = 0,
    ): Response = Response(
        messageKind = noChangesMessageKind,
        responseFlags = 0,
        resultFlags = resultFlags,
        errorId = 0,
        requestSequence = requestSequence,
        streamToken = streamToken,
        nextExpectedRequestSequence = nextExpectedRequestSequence,
        transactionId = transactionId,
        targetGeometryRevision = targetGeometryRevision,
        targetLineageRevision = targetLineageRevision,
        acceptedStyleRevision = acceptedStyleRevision,
    )

    /** Consumed terminal drain. The maximum sequence remains replayable. */
    fun rolloverRequired(
        streamToken: Long,
        requestSequence: Long,
        transactionId: Long,
        targetGeometryRevision: Long,
        targetLineageRevision: Long,
        acceptedStyleRevision: Long,
    ): Response = noChanges(
        streamToken = streamToken,
        requestSequence = requestSequence,
        nextExpectedRequestSequence = requestSequence,
        transactionId = transactionId,
        targetGeometryRevision = targetGeometryRevision,
        targetLineageRevision = targetLineageRevision,
        acceptedStyleRevision = acceptedStyleRevision,
        resultFlags = REQUEST_CONSUMED_RESULT_FLAG or ROLLOVER_REQUIRED_RESULT_FLAG,
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
        authority: ErrorAuthority = ErrorAuthority(),
        diagnostic: ByteArray = byteArrayOf(),
        expectedValue: Long = nextExpectedRequestSequence,
        observedValue: Long = requestSequence,
    ): Response {
        val policy = errorPolicy(errorId)
        val canonicalNextSequence = nextSequenceForPolicy(
            errorId, requestSequence, nextExpectedRequestSequence,
        )
        val detail = ControlCodec.encodeErrorDetail(ErrorDetail(
            errorId, 1, policy.disposition, policy.validationPhase,
            policy.recoveryAction, policy.fieldId, 1, diagnostic.size,
            authority.geometryRevision, authority.lineageRevision,
            authority.captureRevision, authority.coverageRevision,
            authority.acceptedStyleRevision, authority.regionManifestRevision,
            authority.nextSurfaceIdHighWater, expectedValue,
            observedValue, authority.schemaRootRevision,
        ))
        return Response(
        messageKind = 255,
        responseFlags = 0,
        resultFlags = policy.resultFlags,
        errorId = errorId,
        requestSequence = requestSequence,
        streamToken = streamToken,
        nextExpectedRequestSequence = canonicalNextSequence,
        targetGeometryRevision = authority.geometryRevision,
        targetLineageRevision = authority.lineageRevision,
        acceptedStyleRevision = authority.acceptedStyleRevision,
        payload = detail,
        diagnostic = diagnostic,
        )
    }

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
        require(response.messageKind in 0..5 || response.messageKind == 255) {
            "Response message kind is invalid"
        }
        require(response.responseFlags in 0..0x1f) {
            "Response flags contain reserved bits"
        }
        if (response.messageKind == 255) {
            require(response.errorId in 1..150) { "Error response has no stable error ID" }
            require(response.payload.size == ControlCodec.errorDetailBytes) {
                "VGS2 error omitted its exact 96-byte ErrorDetailV2"
            }
            val detail = ControlCodec.decodeErrorDetail(response.payload)
            val policy = errorPolicy(response.errorId)
            require(detail.errorId == response.errorId &&
                detail.diagnosticBytes == response.diagnostic.size && detail.scope == 1 &&
                detail.disposition == policy.disposition &&
                detail.validationPhase == policy.validationPhase &&
                detail.recoveryAction == policy.recoveryAction &&
                detail.fieldId == policy.fieldId &&
                response.resultFlags == policy.resultFlags &&
                detail.geometryRevision == response.targetGeometryRevision &&
                detail.lineageRevision == response.targetLineageRevision &&
                detail.acceptedStyleRevision == response.acceptedStyleRevision) {
                "VGS2 error evidence disagrees with canonical policy or authority"
            }
            require(response.nextExpectedRequestSequence == when (policy.sequenceDisposition) {
                SequenceDisposition.UNCHANGED -> detail.expectedValue
                SequenceDisposition.ADJACENT -> response.requestSequence + 1
                SequenceDisposition.BINDING_INVALID -> 0
            }) {
                "VGS2 error sequence disagrees with canonical policy"
            }
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

    private const val REQUEST_CONSUMED_RESULT_FLAG = 1
    private const val ROLLOVER_REQUIRED_RESULT_FLAG = 1 shl 2

    private fun ByteArray.writeMagic(value: String) {
        value.toByteArray(Charsets.US_ASCII).copyInto(this)
    }

    private fun ByteArray.magicIs(value: String): Boolean =
        value.toByteArray(Charsets.US_ASCII).contentEquals(copyOfRange(0, 4))

    private fun crc32(bytes: ByteArray, zeroOffset: Int, start: Int = 0, length: Int = bytes.size): Int {
        var crc = -1
        for (index in 0 until length) {
            val original = bytes[start + index]
            val byte = if (index in zeroOffset until zeroOffset + 4) 0 else original.toInt() and 0xff
            crc = crc xor byte
            repeat(8) {
                crc = if ((crc and 1) == 1) (crc ushr 1) xor 0xedb88320.toInt() else crc ushr 1
            }
        }
        return crc xor -1
    }

    private fun crc32(bytes: ByteBuffer, zeroOffset: Int, length: Int): Int {
        var crc = -1
        for (index in 0 until length) {
            val byte = if (index in zeroOffset until zeroOffset + 4) 0 else bytes.get(index).toInt() and 0xff
            crc = crc xor byte
            repeat(8) {
                crc = if ((crc and 1) == 1) (crc ushr 1) xor 0xedb88320.toInt() else crc ushr 1
            }
        }
        return crc xor -1
    }
}
