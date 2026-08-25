package com.uhg0.ar_flutter_plugin_2.m0

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Portable UUID bytes used by the VGC2/VGD2 envelopes. */
class M0aUuid(val bytes: ByteArray) {
    init {
        require(bytes.size == 16 && bytes.any { it.toInt() != 0 }) { "UUID must be non-zero and 16 bytes" }
        require((bytes[6].toInt() and 0xf0) != 0 && (bytes[8].toInt() and 0xc0) == 0x80) {
            "UUID version or variant is invalid"
        }
    }

    override fun equals(other: Any?): Boolean = other is M0aUuid && bytes.contentEquals(other.bytes)
    override fun hashCode(): Int = bytes.contentHashCode()
}

enum class M0aControlOperation(val wireValue: Int) {
    START(1), BEGIN_CHECKPOINT(2), RELEASE_CHECKPOINT(3), STOP(4);

    companion object {
        fun fromWire(value: Int): M0aControlOperation = entries.firstOrNull { it.wireValue == value }
            ?: error("Unknown control operation")
    }
}

data class M0aControlRequest(
    val operation: M0aControlOperation,
    val flags: Int,
    val controlRequestId: M0aUuid,
    val sessionId: M0aUuid,
    val captureGroupId: M0aUuid,
    val sessionGeneration: Long,
    val groupGeneration: Long,
    val coverageEpoch: Long,
    val streamToken: Long,
    val payload: ByteArray = byteArrayOf(),
) {
    override fun equals(other: Any?): Boolean = other is M0aControlRequest &&
        operation == other.operation && flags == other.flags &&
        controlRequestId == other.controlRequestId && sessionId == other.sessionId &&
        captureGroupId == other.captureGroupId && sessionGeneration == other.sessionGeneration &&
        groupGeneration == other.groupGeneration && coverageEpoch == other.coverageEpoch &&
        streamToken == other.streamToken && payload.contentEquals(other.payload)

    override fun hashCode(): Int = listOf(
        operation, flags, controlRequestId, sessionId, captureGroupId,
        sessionGeneration, groupGeneration, coverageEpoch, streamToken,
    ).hashCode() * 31 + payload.contentHashCode()
}

data class M0aControlResponse(
    val operation: M0aControlOperation,
    val outcome: Int,
    val resultFlags: Int,
    val errorId: Int,
    val controlRequestId: M0aUuid,
    val sessionId: M0aUuid,
    val captureGroupId: M0aUuid,
    val sessionGeneration: Long,
    val groupGeneration: Long,
    val coverageEpoch: Long,
    val streamToken: Long,
    val nextExchangeRequestSequence: Long,
    val nativeTransactionId: Long,
    val payload: ByteArray = byteArrayOf(),
    val diagnostic: ByteArray = byteArrayOf(),
) {
    override fun equals(other: Any?): Boolean = other is M0aControlResponse &&
        operation == other.operation && outcome == other.outcome && resultFlags == other.resultFlags &&
        errorId == other.errorId && controlRequestId == other.controlRequestId &&
        sessionId == other.sessionId && captureGroupId == other.captureGroupId &&
        sessionGeneration == other.sessionGeneration && groupGeneration == other.groupGeneration &&
        coverageEpoch == other.coverageEpoch && streamToken == other.streamToken &&
        nextExchangeRequestSequence == other.nextExchangeRequestSequence &&
        nativeTransactionId == other.nativeTransactionId && payload.contentEquals(other.payload) &&
        diagnostic.contentEquals(other.diagnostic)

    override fun hashCode(): Int = listOf(
        operation, outcome, resultFlags, errorId, controlRequestId, sessionId,
        captureGroupId, sessionGeneration, groupGeneration, coverageEpoch,
        streamToken, nextExchangeRequestSequence, nativeTransactionId,
    ).hashCode() * 31 + 31 * payload.contentHashCode() + diagnostic.contentHashCode()
}

data class M0aErrorDetail(
    val errorId: Int,
    val scope: Int,
    val disposition: Int,
    val validationPhase: Int,
    val recoveryAction: Int,
    val fieldId: Int,
    val authorityKind: Int,
    val diagnosticBytes: Int,
    val geometryRevision: Long,
    val lineageRevision: Long,
    val captureRevision: Long,
    val coverageRevision: Long,
    val acceptedStyleRevision: Long,
    val regionManifestRevision: Long,
    val nextSurfaceIdHighWater: Long,
    val expectedValue: Long,
    val observedValue: Long,
    val schemaRootRevision: Long,
)

/** A fully readable control identity plus its first canonical pre-accept failure. */
data class M0aCorrelatedControlDecode(
    val request: M0aControlRequest,
    val failure: M0aControlValidationFailure? = null,
)

/** Stable ErrorDetailV2 fields for a control failure before receipt acceptance. */
data class M0aControlValidationFailure(
    val errorId: Int,
    val validationPhase: Int,
    val fieldId: Int,
    val expectedValue: Long,
    val observedValue: Long,
)

object M0aControlCodec {
    const val requestHeaderBytes = 104
    const val responseHeaderBytes = 128
    const val hardCeilingBytes = 16 * 1024
    const val errorDetailBytes = 96

    fun encodeRequest(request: M0aControlRequest): ByteArray {
        validateRequest(request)
        val packetBytes = requestHeaderBytes + request.payload.size
        require(packetBytes <= hardCeilingBytes && request.payload.size <= 0xffff)
        val packet = ByteArray(packetBytes)
        val data = ByteBuffer.wrap(packet).order(ByteOrder.LITTLE_ENDIAN)
        packet.magic("VGC2")
        data.putShort(4, 2)
        data.put(6, request.operation.wireValue.toByte())
        data.put(7, request.flags.toByte())
        data.putShort(8, requestHeaderBytes.toShort())
        data.putShort(10, request.payload.size.toShort())
        data.putInt(12, packetBytes)
        request.controlRequestId.bytes.copyInto(packet, 16)
        request.sessionId.bytes.copyInto(packet, 32)
        request.captureGroupId.bytes.copyInto(packet, 48)
        data.putLong(64, request.sessionGeneration)
        data.putLong(72, request.groupGeneration)
        data.putLong(80, request.coverageEpoch)
        data.putLong(88, request.streamToken)
        data.putInt(96, 0)
        data.putInt(100, 0)
        request.payload.copyInto(packet, requestHeaderBytes)
        data.putInt(96, crc32(packet, 96))
        return packet
    }

    fun decodeRequest(packet: ByteArray): M0aControlRequest {
        require(packet.size >= requestHeaderBytes && packet.size <= hardCeilingBytes)
        val data = ByteBuffer.wrap(packet).order(ByteOrder.LITTLE_ENDIAN)
        require(packet.magicIs("VGC2"))
        require(data.getShort(4).toInt() and 0xffff == 2)
        require(data.getShort(8).toInt() and 0xffff == requestHeaderBytes)
        require(data.getInt(12) == packet.size)
        require(data.getShort(10).toInt() and 0xffff == packet.size - requestHeaderBytes)
        require(data.getInt(100) == 0)
        require(data.getInt(96) == crc32(packet, 96))
        val request = M0aControlRequest(
            operation = M0aControlOperation.fromWire(data.get(6).toInt() and 0xff),
            flags = data.get(7).toInt() and 0xff,
            controlRequestId = M0aUuid(packet.copyOfRange(16, 32)),
            sessionId = M0aUuid(packet.copyOfRange(32, 48)),
            captureGroupId = M0aUuid(packet.copyOfRange(48, 64)),
            sessionGeneration = data.getLong(64),
            groupGeneration = data.getLong(72),
            coverageEpoch = data.getLong(80),
            streamToken = data.getLong(88),
            payload = packet.copyOfRange(requestHeaderBytes, packet.size),
        )
        validateRequest(request)
        return request
    }

    /**
     * Decodes a request for the authenticated Android binding seam.
     *
     * A canonical failure is returned only after the complete correlation
     * identity is trustworthy. Short headers, malformed UUID identities, and
     * non-portable correlation ordinals still throw so the transport cannot
     * invent a peer receipt. No receipt or lifecycle state is touched here.
     */
    fun decodeCorrelatedRequest(
        packet: ByteArray,
        methodOperation: M0aControlOperation,
    ): M0aCorrelatedControlDecode {
        require(packet.size >= requestHeaderBytes) { "Control correlation header is unreadable" }
        val data = ByteBuffer.wrap(packet).order(ByteOrder.LITTLE_ENDIAN)
        val request = M0aControlRequest(
            operation = methodOperation,
            flags = data.get(7).toInt() and 0xff,
            controlRequestId = M0aUuid(packet.copyOfRange(16, 32)),
            sessionId = M0aUuid(packet.copyOfRange(32, 48)),
            captureGroupId = M0aUuid(packet.copyOfRange(48, 64)),
            sessionGeneration = data.getLong(64),
            groupGeneration = data.getLong(72),
            coverageEpoch = data.getLong(80),
            streamToken = data.getLong(88),
            // Correlation is extracted before framing proof. Do not copy an
            // untrusted payload until the hard ceiling and exact lengths pass.
            payload = byteArrayOf(),
        )
        validateCorrelation(request)

        fun failure(
            errorId: Int,
            phase: Int,
            fieldId: Int,
            expected: Long,
            observed: Long,
        ) = M0aCorrelatedControlDecode(
            request,
            M0aControlValidationFailure(errorId, phase, fieldId, expected, observed),
        )

        val expectedMagic = 0x32434756L
        val observedMagic = data.getInt(0).toLong() and 0xffff_ffffL
        if (!packet.magicIs("VGC2")) {
            return failure(6, 1, 1, expectedMagic, observedMagic)
        }
        val major = data.getShort(4).toInt() and 0xffff
        if (major != 2) return failure(1, 1, 2, 2, major.toLong())
        val headerBytes = data.getShort(8).toInt() and 0xffff
        if (headerBytes != requestHeaderBytes) {
            return failure(6, 1, 2, requestHeaderBytes.toLong(), headerBytes.toLong())
        }

        val declaredPacketBytes = data.getInt(12).toLong() and 0xffff_ffffL
        if (packet.size > hardCeilingBytes) {
            return failure(5, 2, 3, hardCeilingBytes.toLong(), packet.size.toLong())
        }
        if (declaredPacketBytes != packet.size.toLong()) {
            return failure(6, 2, 3, declaredPacketBytes, packet.size.toLong())
        }
        val declaredPayloadBytes = data.getShort(10).toInt() and 0xffff
        val actualPayloadBytes = packet.size - requestHeaderBytes
        if (declaredPayloadBytes != actualPayloadBytes) {
            return failure(6, 2, 3, declaredPayloadBytes.toLong(), actualPayloadBytes.toLong())
        }
        val expectedCrc = crc32(packet, 96).toLong() and 0xffff_ffffL
        val observedCrc = data.getInt(96).toLong() and 0xffff_ffffL
        if (observedCrc != expectedCrc) {
            return failure(6, 2, 4, expectedCrc, observedCrc)
        }
        val reserved = data.getInt(100).toLong() and 0xffff_ffffL
        if (reserved != 0L) return failure(6, 2, 5, 0, reserved)

        val wireOperation = data.get(6).toInt() and 0xff
        if (wireOperation !in 1..4 || wireOperation != methodOperation.wireValue) {
            return failure(6, 3, 16, methodOperation.wireValue.toLong(), wireOperation.toLong())
        }
        if (request.flags and 0xfe != 0) {
            return failure(6, 3, 5, 1, request.flags.toLong())
        }
        val streamTokenValid = request.streamToken >= 0 &&
            ((methodOperation == M0aControlOperation.START) == (request.streamToken == 0L))
        if (!streamTokenValid) {
            val expected = if (methodOperation == M0aControlOperation.START) 0L else 1L
            return failure(36, 3, 7, expected, request.streamToken)
        }

        return M0aCorrelatedControlDecode(decodeRequest(packet))
    }

    fun encodeResponse(response: M0aControlResponse, maximumBytes: Int): ByteArray {
        validateResponse(response)
        val body = response.payload + response.diagnostic
        val packetBytes = responseHeaderBytes + body.size
        require(maximumBytes >= responseHeaderBytes && packetBytes <= maximumBytes && packetBytes <= hardCeilingBytes)
        val packet = ByteArray(packetBytes)
        val data = ByteBuffer.wrap(packet).order(ByteOrder.LITTLE_ENDIAN)
        packet.magic("VGD2")
        data.putShort(4, 2)
        data.put(6, response.operation.wireValue.toByte())
        data.put(7, response.outcome.toByte())
        data.putShort(8, responseHeaderBytes.toShort())
        data.putShort(10, response.resultFlags.toShort())
        data.putShort(12, response.errorId.toShort())
        data.putShort(14, response.diagnostic.size.toShort())
        data.putInt(16, packetBytes)
        data.putInt(20, body.size)
        response.controlRequestId.bytes.copyInto(packet, 24)
        response.sessionId.bytes.copyInto(packet, 40)
        response.captureGroupId.bytes.copyInto(packet, 56)
        data.putLong(72, response.sessionGeneration)
        data.putLong(80, response.groupGeneration)
        data.putLong(88, response.coverageEpoch)
        data.putLong(96, response.streamToken)
        data.putLong(104, response.nextExchangeRequestSequence)
        data.putLong(112, response.nativeTransactionId)
        data.putInt(120, 0)
        data.putInt(124, 0)
        body.copyInto(packet, responseHeaderBytes)
        data.putInt(120, crc32(packet, 120))
        return packet
    }

    fun decodeResponse(packet: ByteArray): M0aControlResponse {
        require(packet.size >= responseHeaderBytes && packet.size <= hardCeilingBytes)
        val data = ByteBuffer.wrap(packet).order(ByteOrder.LITTLE_ENDIAN)
        require(packet.magicIs("VGD2"))
        require(data.getShort(4).toInt() and 0xffff == 2)
        require(data.getShort(8).toInt() and 0xffff == responseHeaderBytes)
        require(data.getInt(16) == packet.size)
        require(data.getInt(20) == packet.size - responseHeaderBytes)
        require(data.getShort(14).toInt() and 0xffff <= packet.size - responseHeaderBytes)
        require(data.getShort(10).toInt() and 0xffff <= 0x1f)
        require(data.getInt(124) == 0)
        require(data.getInt(120) == crc32(packet, 120))
        val diagnosticBytes = data.getShort(14).toInt() and 0xffff
        val split = packet.size - diagnosticBytes
        return M0aControlResponse(
            operation = M0aControlOperation.fromWire(data.get(6).toInt() and 0xff),
            outcome = data.get(7).toInt() and 0xff,
            resultFlags = data.getShort(10).toInt() and 0xffff,
            errorId = data.getShort(12).toInt() and 0xffff,
            controlRequestId = M0aUuid(packet.copyOfRange(24, 40)),
            sessionId = M0aUuid(packet.copyOfRange(40, 56)),
            captureGroupId = M0aUuid(packet.copyOfRange(56, 72)),
            sessionGeneration = data.getLong(72),
            groupGeneration = data.getLong(80),
            coverageEpoch = data.getLong(88),
            streamToken = data.getLong(96),
            nextExchangeRequestSequence = data.getLong(104),
            nativeTransactionId = data.getLong(112),
            payload = packet.copyOfRange(responseHeaderBytes, split),
            diagnostic = packet.copyOfRange(split, packet.size),
        ).also { response ->
            validateResponse(response)
            if (response.outcome == 1 && response.payload.isNotEmpty()) {
                require(response.payload.size == errorDetailBytes) {
                    "Error response payload must be one ErrorDetailV2"
                }
                val detail = decodeErrorDetail(response.payload)
                require(detail.errorId == response.errorId) {
                    "Error envelope and ErrorDetailV2 IDs disagree"
                }
                require(detail.diagnosticBytes == response.diagnostic.size) {
                    "ErrorDetailV2 diagnostic length disagrees with the envelope"
                }
            }
        }
    }

    /** Validates a fully framed payload before lifecycle receipt admission. */
    fun validateControlPayload(request: M0aControlRequest): M0aControlValidationFailure? =
        when (request.operation) {
            M0aControlOperation.START -> validateStartPayload(request.payload)
            M0aControlOperation.BEGIN_CHECKPOINT -> validateBeginCheckpointPayload(request.payload)
            M0aControlOperation.RELEASE_CHECKPOINT -> validateReleaseCheckpointPayload(request.payload)
            M0aControlOperation.STOP -> validateStopPayload(request.payload)
        }

    private fun validateStartPayload(bytes: ByteArray): M0aControlValidationFailure? {
        fun invalid(error: Int, phase: Int, field: Int, expected: Long, observed: Long) =
            M0aControlValidationFailure(error, phase, field, expected, observed)
        if (bytes.size != M0aStartRequestCodecV2.byteLength) {
            return invalid(6, 2, 3, M0aStartRequestCodecV2.byteLength.toLong(), bytes.size.toLong())
        }
        val data = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val schema = data.getShort(4).toInt() and 0xffff
        if (schema != 5) return invalid(1, 4, 2, 5, schema.toLong())
        val minimumMinor = data.getShort(0).toInt() and 0xffff
        val maximumMinor = data.getShort(2).toInt() and 0xffff
        if (maximumMinor < minimumMinor) return invalid(36, 4, 20, minimumMinor.toLong(), maximumMinor.toLong())
        val profile = data.get(6).toInt() and 0xff
        if (profile > 2) return invalid(6, 3, 5, 2, profile.toLong())
        val flags = data.get(7).toInt() and 0xff
        if (flags and 0xfe != 0) return invalid(6, 3, 5, 1, flags.toLong())
        for (offset in intArrayOf(8, 16)) {
            val value = data.getLong(offset)
            if (value < 0) return invalid(36, 4, 17, Long.MAX_VALUE, value)
        }
        val ordinary = data.getInt(24).toLong() and 0xffff_ffffL
        val catchUp = data.getInt(28).toLong() and 0xffff_ffffL
        val diagnostic = data.getShort(32).toInt() and 0xffff
        val regionLimit = data.getShort(34).toInt() and 0xffff
        val voxel = data.getInt(36).toLong() and 0xffff_ffffL
        val modelCapacity = data.getInt(40).toLong() and 0xffff_ffffL
        val pendingCapacity = data.getInt(44).toLong() and 0xffff_ffffL
        if (ordinary !in 4096..16384) return invalid(36, 4, 20, 4096, ordinary)
        if (catchUp !in ordinary..65536) return invalid(36, 4, 20, ordinary, catchUp)
        if (diagnostic > 1024) return invalid(36, 4, 20, 1024, diagnostic.toLong())
        if (regionLimit !in 1..8) return invalid(36, 4, 20, 8, regionLimit.toLong())
        if (voxel == 0L || 1_000_000L % voxel != 0L || 3_000_000L % voxel != 0L) {
            return invalid(36, 4, 20, 1_000_000, voxel)
        }
        if (modelCapacity > 100_000) return invalid(36, 4, 20, 100_000, modelCapacity)
        if (pendingCapacity > 200_000) return invalid(36, 4, 20, 200_000, pendingCapacity)
        for (offset in intArrayOf(48, 50, 52, 54)) {
            val convention = data.getShort(offset).toInt() and 0xffff
            if (convention != 1) return invalid(6, 5, 21, 1, convention.toLong())
        }
        repeat(10) { index ->
            val revision = data.getLong(56 + index * 8)
            if (revision < 0) return invalid(36, 4, 20, Long.MAX_VALUE, revision)
        }
        for (matrixOffset in intArrayOf(136, 264)) {
            repeat(16) { index ->
                val value = data.getDouble(matrixOffset + index * 8)
                if (!value.isFinite()) return invalid(6, 5, 21, 0, value.toRawBits())
            }
        }
        val restoreRequested = flags and 1 != 0
        val revisionsEmpty = (0 until 10).all { data.getLong(56 + it * 8) == 0L }
        val schemaEmpty = bytes.copyOfRange(392, 424).all { it.toInt() == 0 }
        val manifestEmpty = bytes.copyOfRange(424, 456).all { it.toInt() == 0 }
        val hashState = if (schemaEmpty && manifestEmpty) 0L else if (!schemaEmpty && !manifestEmpty) 1L else 2L
        if (restoreRequested && hashState == 2L) return invalid(6, 6, 22, 1, hashState)
        if (!restoreRequested && (!revisionsEmpty || hashState != 0L)) {
            return invalid(6, 6, 22, 0, if (!revisionsEmpty) 3 else hashState)
        }
        val reservedIndex = (456 until M0aStartRequestCodecV2.byteLength)
            .firstOrNull { bytes[it].toInt() != 0 }
        if (reservedIndex != null) {
            return invalid(6, 7, 5, 0, bytes[reservedIndex].toLong() and 0xff)
        }
        // Keep the strict decoder authoritative; this call must now be infallible.
        M0aStartRequestCodecV2.decode(bytes)
        return null
    }

    private fun validateBeginCheckpointPayload(bytes: ByteArray): M0aControlValidationFailure? {
        if (bytes.size != 112) return payloadLengthFailure(112, bytes.size)
        val data = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        repeat(12) { index ->
            val value = data.getLong(index * 8)
            if (value < 0) return numericFailure(Long.MAX_VALUE, value)
        }
        val maximumCatchUp = data.getInt(96).toLong() and 0xffff_ffffL
        if (maximumCatchUp !in 4096..65536) return numericFailure(65536, maximumCatchUp)
        val flags = data.getInt(100).toLong() and 0xffff_ffffL
        if (flags and 1.inv().toLong() != 0L) return reservedFailure(flags)
        val pinLease = data.getLong(104)
        if (pinLease <= 0) return numericFailure(1, pinLease)
        return null
    }

    private fun validateReleaseCheckpointPayload(bytes: ByteArray): M0aControlValidationFailure? {
        if (bytes.size != 72) return payloadLengthFailure(72, bytes.size)
        val data = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val token = data.getLong(0)
        if (token <= 0) return numericFailure(1, token)
        val committed = data.get(8).toInt() and 0xff
        if (committed !in 0..1) return reservedFailure(committed.toLong())
        val reserved = (10 until 16).firstOrNull { bytes[it].toInt() != 0 }
        if (reserved != null) return reservedFailure(bytes[reserved].toLong() and 0xff)
        val revision = data.getLong(16)
        if (revision < 0) return numericFailure(Long.MAX_VALUE, revision)
        val commitEmpty = bytes.copyOfRange(24, 40).all { it.toInt() == 0 }
        val hashEmpty = bytes.copyOfRange(40, 72).all { it.toInt() == 0 }
        val legal = if (committed == 0) revision == 0L && commitEmpty && hashEmpty else !commitEmpty && !hashEmpty
        return if (legal) null else M0aControlValidationFailure(6, 6, 22, committed.toLong(), 2)
    }

    private fun validateStopPayload(bytes: ByteArray): M0aControlValidationFailure? {
        if (bytes.size != 88) return payloadLengthFailure(88, bytes.size)
        val data = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val mode = data.get(1).toInt() and 0xff
        if (mode > 2) return reservedFailure(mode.toLong())
        val reserved = data.getInt(4).toLong() and 0xffff_ffffL
        if (reserved != 0L) return reservedFailure(reserved)
        val drainBudget = data.getLong(8)
        if (drainBudget <= 0) return numericFailure(1, drainBudget)
        repeat(7) { index ->
            val value = data.getLong(16 + index * 8)
            if (value < 0) return numericFailure(Long.MAX_VALUE, value)
        }
        val checkpointEmpty = bytes.copyOfRange(72, 88).all { it.toInt() == 0 }
        if ((mode == 0) == checkpointEmpty) {
            return M0aControlValidationFailure(6, 6, 22, if (mode == 0) 1 else 0, if (checkpointEmpty) 0 else 1)
        }
        return null
    }

    private fun payloadLengthFailure(expected: Int, observed: Int) =
        M0aControlValidationFailure(6, 2, 3, expected.toLong(), observed.toLong())

    private fun numericFailure(expected: Long, observed: Long) =
        M0aControlValidationFailure(36, 4, 20, expected, observed)

    private fun reservedFailure(observed: Long) =
        M0aControlValidationFailure(6, 7, 5, 0, observed)

    fun encodeErrorDetail(detail: M0aErrorDetail): ByteArray {
        require(detail.errorId in 1..150)
        require(detail.scope in 0..7 && detail.disposition in 0..2)
        require(detail.validationPhase in 1..10 && detail.recoveryAction in 0..9)
        require(detail.fieldId in 0..0xffff && detail.authorityKind in 0..0xffff)
        require(detail.diagnosticBytes in 0..256)
        val packet = ByteArray(errorDetailBytes)
        val data = ByteBuffer.wrap(packet).order(ByteOrder.LITTLE_ENDIAN)
        data.putShort(0, detail.errorId.toShort())
        data.put(2, detail.scope.toByte())
        data.put(3, detail.disposition.toByte())
        data.put(4, detail.validationPhase.toByte())
        data.put(5, detail.recoveryAction.toByte())
        data.putShort(6, detail.fieldId.toShort())
        data.putShort(8, detail.authorityKind.toShort())
        data.putShort(10, detail.diagnosticBytes.toShort())
        data.putLong(12, detail.geometryRevision)
        data.putLong(20, detail.lineageRevision)
        data.putLong(28, detail.captureRevision)
        data.putLong(36, detail.coverageRevision)
        data.putLong(44, detail.acceptedStyleRevision)
        data.putLong(52, detail.regionManifestRevision)
        data.putLong(60, detail.nextSurfaceIdHighWater)
        data.putLong(68, detail.expectedValue)
        data.putLong(76, detail.observedValue)
        data.putLong(84, detail.schemaRootRevision)
        return packet
    }

    fun decodeErrorDetail(bytes: ByteArray): M0aErrorDetail {
        require(bytes.size == errorDetailBytes)
        val data = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        require(data.getInt(92) == 0)
        return M0aErrorDetail(
            data.getShort(0).toInt() and 0xffff,
            data.get(2).toInt() and 0xff,
            data.get(3).toInt() and 0xff,
            data.get(4).toInt() and 0xff,
            data.get(5).toInt() and 0xff,
            data.getShort(6).toInt() and 0xffff,
            data.getShort(8).toInt() and 0xffff,
            data.getShort(10).toInt() and 0xffff,
            data.getLong(12), data.getLong(20), data.getLong(28), data.getLong(36),
            data.getLong(44), data.getLong(52), data.getLong(60), data.getLong(68),
            data.getLong(76), data.getLong(84),
        )
    }

    private fun validateRequest(request: M0aControlRequest) {
        require(request.flags and 0xfe == 0)
        validateOrdinal(request.sessionGeneration, "sessionGeneration")
        validateOrdinal(request.groupGeneration, "groupGeneration")
        validateOrdinal(request.coverageEpoch, "coverageEpoch")
        require(request.streamToken in 0..Long.MAX_VALUE)
        require((request.operation == M0aControlOperation.START) == (request.streamToken == 0L))
    }

    private fun validateCorrelation(request: M0aControlRequest) {
        validateOrdinal(request.sessionGeneration, "sessionGeneration")
        validateOrdinal(request.groupGeneration, "groupGeneration")
        validateOrdinal(request.coverageEpoch, "coverageEpoch")
        require(request.streamToken >= 0) { "streamToken is outside PortableOrdinal" }
    }

    private fun validateResponse(response: M0aControlResponse) {
        require(response.outcome in 0..1 && response.resultFlags in 0..0x1f)
        require(
            if (response.outcome == 0) response.errorId == 0
            else response.errorId in 1..150,
        )
        require(response.errorId in 0..0xffff && response.diagnostic.size <= 256)
        validateOrdinal(response.sessionGeneration, "sessionGeneration")
        validateOrdinal(response.groupGeneration, "groupGeneration")
        validateOrdinal(response.coverageEpoch, "coverageEpoch")
        require(response.streamToken >= 0 && response.nextExchangeRequestSequence >= 0 && response.nativeTransactionId >= 0)
    }

    private fun validateOrdinal(value: Long, name: String) = require(value in 1..Long.MAX_VALUE) { "$name is outside PortableOrdinal" }
    private fun ByteArray.magic(value: String) = value.toByteArray(Charsets.US_ASCII).copyInto(this)
    private fun ByteArray.magicIs(value: String): Boolean = value.toByteArray(Charsets.US_ASCII).contentEquals(copyOfRange(0, 4))
    internal fun crc32(bytes: ByteArray, zeroOffset: Int): Int {
        var crc = -1
        bytes.forEachIndexed { index, original ->
            val byte = if (index in zeroOffset until zeroOffset + 4) 0 else original.toInt() and 0xff
            crc = crc xor byte
            repeat(8) { crc = if ((crc and 1) == 1) (crc ushr 1) xor 0xedb88320.toInt() else crc ushr 1 }
        }
        return crc xor -1
    }
}
