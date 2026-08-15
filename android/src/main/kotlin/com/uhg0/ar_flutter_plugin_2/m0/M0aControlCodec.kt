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
        ).also(::validateResponse)
    }

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

    private fun validateResponse(response: M0aControlResponse) {
        require(response.outcome in 0..1 && response.resultFlags in 0..0x1f)
        require(response.errorId in 0..0xffff && response.diagnostic.size <= 256)
        validateOrdinal(response.sessionGeneration, "sessionGeneration")
        validateOrdinal(response.groupGeneration, "groupGeneration")
        validateOrdinal(response.coverageEpoch, "coverageEpoch")
        require(response.streamToken >= 0 && response.nextExchangeRequestSequence >= 0 && response.nativeTransactionId >= 0)
    }

    private fun validateOrdinal(value: Long, name: String) = require(value in 1..Long.MAX_VALUE) { "$name is outside PortableOrdinal" }
    private fun ByteArray.magic(value: String) = value.toByteArray(Charsets.US_ASCII).copyInto(this)
    private fun ByteArray.magicIs(value: String): Boolean = value.toByteArray(Charsets.US_ASCII).contentEquals(copyOfRange(0, 4))
    private fun crc32(bytes: ByteArray, zeroOffset: Int): Int {
        var crc = -1
        bytes.forEachIndexed { index, original ->
            val byte = if (index in zeroOffset until zeroOffset + 4) 0 else original.toInt() and 0xff
            crc = crc xor byte
            repeat(8) { crc = if ((crc and 1) == 1) (crc ushr 1) xor 0xedb88320.toInt() else crc ushr 1 }
        }
        return crc xor -1
    }
}
