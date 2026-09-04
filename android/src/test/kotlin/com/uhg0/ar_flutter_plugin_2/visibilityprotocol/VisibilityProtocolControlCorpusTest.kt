package com.uhg0.ar_flutter_plugin_2.visibilityprotocol


import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test

class VisibilityProtocolControlCorpusTest {
    @Test
    fun `START default encodes the explicit maximum accepted model capacity`() {
        val defaultPayload = StartRequestCodecV2.defaultPayload()
        assertEquals(100_000, StartRequestCodecV2.decode(defaultPayload).requestedModelCapacity)

        val historicalZero = defaultPayload.copyOf().also {
            ByteBuffer.wrap(it).order(ByteOrder.LITTLE_ENDIAN).putInt(40, 0)
        }
        assertEquals(
            ControlValidationFailure(36, 4, 20, 100_000, 0),
            (StartRequestCodecV2.decodeDetailed(historicalZero) as
                StartRequestCodecV2.DetailedDecode.Invalid).failure,
        )
    }

    @Test
    fun `START rejects a one direction only ill conditioned inverse`() {
        val groupFromWorld = doubleArrayOf(
            1e8, 0.0, 0.0, 0.0,
            0.0, 1.0, 0.0, 0.0,
            0.0, 0.0, 1.0, 0.0,
            0.0, 0.0, 0.0, 1.0,
        )
        val worldFromGroup = doubleArrayOf(
            1e-8, 1e-7, 0.0, 0.0,
            0.0, 1.0, 0.0, 0.0,
            0.0, 0.0, 1.0, 0.0,
            0.0, 0.0, 0.0, 1.0,
        )
        assertEquals(1e-7, groupFromWorld[5] * worldFromGroup[1], 0.0)
        assertEquals(10.0, worldFromGroup[1] * groupFromWorld[0], 0.0)
        assertFalse(CoordinateFrameTransforms.areFiniteAffineInverses(groupFromWorld, worldFromGroup))

        val payload = StartRequestCodecV2.defaultPayload()
        val data = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN)
        groupFromWorld.forEachIndexed { index, value -> data.putDouble(136 + index * 8, value) }
        worldFromGroup.forEachIndexed { index, value -> data.putDouble(264 + index * 8, value) }
        assertEquals(
            ControlValidationFailure(36, 5, 21, 1, 0),
            (StartRequestCodecV2.decodeDetailed(payload) as StartRequestCodecV2.DetailedDecode.Invalid).failure,
        )
    }

    @Test
    fun `START retains exact non identity inverse matrix values and bit identities`() {
        val payload = StartRequestCodecV2.defaultPayload()
        val groupFromWorld = doubleArrayOf(
            1.0, 0.0, 0.0, 0.0,
            0.0, 1.0, 0.0, 0.0,
            0.0, 0.0, 1.0, 0.0,
            1.25, -2.5, 3.75, 1.0,
        )
        val worldFromGroup = groupFromWorld.copyOf().also {
            it[12] = -1.25; it[13] = 2.5; it[14] = -3.75
        }
        val data = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN)
        groupFromWorld.forEachIndexed { index, value -> data.putDouble(136 + index * 8, value) }
        worldFromGroup.forEachIndexed { index, value -> data.putDouble(264 + index * 8, value) }

        val configuration = StartRequestCodecV2.decode(payload)

        assertEquals(groupFromWorld.toList(), configuration.groupFromWorldGl)
        assertEquals(worldFromGroup.toList(), configuration.worldFromGroupGl)
        assertEquals(groupFromWorld.joinToString(",") { it.toBits().toString(16) }, configuration.groupFromWorldIdentity)
        assertEquals(worldFromGroup.joinToString(",") { it.toBits().toString(16) }, configuration.worldFromGroupIdentity)
        payload[136] = 0
        assertEquals(groupFromWorld.toList(), configuration.groupFromWorldGl)
    }

    @Test
    fun `START payload failures retain stable phase field and evidence identity`() {
        val valid = StartRequestCodecV2.defaultPayload()
        val cases = listOf(
            valid.copyOf().also { ByteBuffer.wrap(it).order(ByteOrder.LITTLE_ENDIAN).putShort(4, 6.toShort()) } to
                ControlValidationFailure(1, 4, 2, 5, 6),
            valid.copyOf().also {
                it[7] = 1
                ByteBuffer.wrap(it).order(ByteOrder.LITTLE_ENDIAN).putShort(4, 6.toShort())
            } to ControlValidationFailure(2, 4, 2, 5, 6),
            valid.copyOf().also { it[7] = 2 } to
                ControlValidationFailure(6, 3, 5, 1, 2),
            valid.copyOf().also { ByteBuffer.wrap(it).order(ByteOrder.LITTLE_ENDIAN).putInt(24, 0) } to
                ControlValidationFailure(36, 4, 20, 4096, 0),
            valid.copyOf().also { ByteBuffer.wrap(it).order(ByteOrder.LITTLE_ENDIAN).putShort(48, 2.toShort()) } to
                ControlValidationFailure(6, 5, 21, 1, 2),
            valid.copyOf().also {
                ByteBuffer.wrap(it).order(ByteOrder.LITTLE_ENDIAN).putDouble(136, Double.NaN)
            } to ControlValidationFailure(36, 5, 21, 0, Double.NaN.toRawBits()),
            valid.copyOf().also { it[392] = 1 } to
                ControlValidationFailure(6, 6, 22, 0, 2),
            valid.copyOf().also { it[456] = 1 } to
                ControlValidationFailure(6, 7, 5, 0, 1),
        )
        cases.forEach { (payload, expected) ->
            assertEquals(expected, ControlCodec.validateControlPayload(startRequest(payload)))
            val detailed = StartRequestCodecV2.decodeDetailed(payload)
            assertEquals(
                expected,
                (detailed as StartRequestCodecV2.DetailedDecode.Invalid).failure,
            )
            val strict = assertThrows(StartRequestCodecV2.ValidationException::class.java) {
                StartRequestCodecV2.decode(payload)
            }
            assertEquals(expected, strict.failure)
        }
        assertEquals(null, ControlCodec.validateControlPayload(startRequest(valid)))
        val detailedValid = (StartRequestCodecV2.decodeDetailed(valid) as
            StartRequestCodecV2.DetailedDecode.Valid).configuration
        assertEquals(StartRequestCodecV2.decode(valid).persistenceSchema, detailedValid.persistenceSchema)
    }

    @Test
    fun `Kotlin control envelopes match the locked Dart corpus`() {
        val root = fixture()
        val common = root.getValue("common").jsonObject
        val validation = root.getValue("validationRules").jsonObject
        assertEquals(listOf(1, 2, 3), validation.getValue("stopReasons").jsonArray.map { it.jsonPrimitive.int })
        assertEquals(0, validation.int("stopFlagsMask"))
        assertEquals(1, validation.int("startNewSchemaErrorId"))
        assertEquals(2, validation.int("startRestoreSchemaErrorId"))
        assertEquals(36, validation.int("nonFiniteMatrixErrorId"))
        assertEquals(ControlCodec.errorDetailBytes, validation.int("errorDetailBytes"))
        assertEquals(true, validation.getValue("everyErrorRequiresDetail").jsonPrimitive.boolean)
        val lifecyclePolicies = validation.getValue("lifecycleErrorPolicies").jsonObject
        assertEquals(listOf(0, 6, 5, 0), lifecyclePolicies.policy(1))
        assertEquals(listOf(2, 4, 4, 4), lifecyclePolicies.policy(4))
        assertEquals(listOf(0, 6, 5, 0), lifecyclePolicies.policy(46))
        assertEquals(listOf(0, 7, 5, 0), lifecyclePolicies.policy(48))
        root.getValue("requests").jsonArray.forEach { raw ->
            val spec = raw.jsonObject
            val request = ControlRequest(
                operation = ControlOperation.fromWire(spec.int("operation")),
                flags = 0,
                controlRequestId = Uuid(hex(spec.string("controlRequestId"))),
                sessionId = Uuid(hex(common.string("sessionId"))),
                captureGroupId = Uuid(hex(common.string("captureGroupId"))),
                sessionGeneration = common.long("sessionGeneration"),
                groupGeneration = common.long("groupGeneration"),
                coverageEpoch = common.long("coverageEpoch"),
                streamToken = spec.long("streamToken"),
                payload = hex(spec.string("payloadHex")),
            )
            val bytes = ControlCodec.encodeRequest(request)
            assertEquals(spec.int("length"), bytes.size)
            assertEquals(spec.string("sha256"), sha256(bytes))
            assertArrayEquals(bytes, ControlCodec.encodeRequest(ControlCodec.decodeRequest(bytes)))
            val validationFailure = ControlCodec.validateControlPayload(request)
            if (request.operation == ControlOperation.START &&
                ByteBuffer.wrap(request.payload).order(ByteOrder.LITTLE_ENDIAN).getInt(40) == 0
            ) {
                assertEquals(ControlValidationFailure(36, 4, 20, 100_000, 0), validationFailure)
            } else {
                assertEquals(null, validationFailure)
            }
            val truncated = ControlCodec.validateControlPayload(
                request.copy(payload = request.payload.copyOf(request.payload.size - 1)),
            )
            assertEquals(2, truncated?.validationPhase)
            assertEquals(3, truncated?.fieldId)
        }

        val response = root.getValue("startResponse").jsonObject
        val responseBytes = ControlCodec.encodeResponse(
            ControlResponse(
                operation = ControlOperation.START,
                outcome = response.int("outcome"),
                resultFlags = response.int("resultFlags"),
                errorId = response.int("errorId"),
                controlRequestId = Uuid(hex(response.string("controlRequestId"))),
                sessionId = Uuid(hex(common.string("sessionId"))),
                captureGroupId = Uuid(hex(common.string("captureGroupId"))),
                sessionGeneration = common.long("sessionGeneration"),
                groupGeneration = common.long("groupGeneration"),
                coverageEpoch = common.long("coverageEpoch"),
                streamToken = response.long("streamToken"),
                nextExchangeRequestSequence = response.long("nextExchangeRequestSequence"),
                nativeTransactionId = response.long("nativeTransactionId"),
                payload = hex(response.string("payloadHex")),
            ),
            ControlCodec.hardCeilingBytes,
        )
        assertEquals(response.int("length"), responseBytes.size)
        assertEquals(response.string("sha256"), sha256(responseBytes))

        val malformed = root.getValue("malformedControlErrorResponse").jsonObject
        val detail = malformed.getValue("detail").jsonObject
        val errorDetail = ControlCodec.encodeErrorDetail(
            ErrorDetail(
                errorId = malformed.int("errorId"),
                scope = detail.int("scope"),
                disposition = detail.int("disposition"),
                validationPhase = detail.int("validationPhase"),
                recoveryAction = detail.int("recoveryAction"),
                fieldId = detail.int("fieldId"),
                authorityKind = detail.int("authorityKind"),
                diagnosticBytes = 0,
                geometryRevision = detail.long("geometryRevision"),
                lineageRevision = detail.long("lineageRevision"),
                captureRevision = detail.long("captureRevision"),
                coverageRevision = detail.long("coverageRevision"),
                acceptedStyleRevision = detail.long("acceptedStyleRevision"),
                regionManifestRevision = detail.long("regionManifestRevision"),
                nextSurfaceIdHighWater = detail.long("nextSurfaceIdHighWater"),
                expectedValue = detail.long("expectedValue"),
                observedValue = detail.long("observedValue"),
                schemaRootRevision = detail.long("schemaRootRevision"),
            ),
        )
        val errorBytes = ControlCodec.encodeResponse(
            ControlResponse(
                operation = ControlOperation.START,
                outcome = malformed.int("outcome"),
                resultFlags = malformed.int("resultFlags"),
                errorId = malformed.int("errorId"),
                controlRequestId = Uuid(hex(malformed.string("controlRequestId"))),
                sessionId = Uuid(hex(common.string("sessionId"))),
                captureGroupId = Uuid(hex(common.string("captureGroupId"))),
                sessionGeneration = common.long("sessionGeneration"),
                groupGeneration = common.long("groupGeneration"),
                coverageEpoch = common.long("coverageEpoch"),
                streamToken = malformed.long("streamToken"),
                nextExchangeRequestSequence = malformed.long("nextExchangeRequestSequence"),
                nativeTransactionId = malformed.long("nativeTransactionId"),
                payload = errorDetail,
            ),
            ControlCodec.hardCeilingBytes,
        )
        assertEquals(malformed.int("length"), errorBytes.size)
        assertEquals(malformed.string("sha256"), sha256(errorBytes))
        val decodedError = ControlCodec.decodeResponse(errorBytes)
        assertEquals(6, decodedError.errorId)
        assertEquals(0, ControlCodec.decodeErrorDetail(decodedError.payload).disposition)

        val mismatchedErrorId = errorBytes.copyOf().also { bytes ->
            bytes[ControlCodec.responseHeaderBytes] = 5
            ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).putInt(
                120,
                ControlCodec.crc32(bytes, 120),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            ControlCodec.decodeResponse(mismatchedErrorId)
        }
        val mismatchedDiagnosticLength = errorBytes.copyOf().also { bytes ->
            ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).putShort(
                ControlCodec.responseHeaderBytes + 10,
                1.toShort(),
            )
            ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).putInt(
                120,
                ControlCodec.crc32(bytes, 120),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            ControlCodec.decodeResponse(mismatchedDiagnosticLength)
        }
        val emptyError = ControlCodec.encodeResponse(
            decodedError.copy(payload = byteArrayOf()),
            ControlCodec.hardCeilingBytes,
        )
        assertThrows(IllegalArgumentException::class.java) {
            ControlCodec.decodeResponse(emptyError)
        }
    }

    private fun fixture(): JsonObject = Json.parseToJsonElement(
        requireNotNull(javaClass.classLoader?.getResourceAsStream("visibility_protocol_control_corpus_v1.json"))
            .bufferedReader().use { it.readText() },
    ).jsonObject

    private fun startRequest(payload: ByteArray) = ControlRequest(
        operation = ControlOperation.START,
        flags = 0,
        controlRequestId = Uuid(hex("102132435465467798a9bacbdcedfe0f")),
        sessionId = Uuid(hex("2031425364754677a8b9cadbdcedfe1f")),
        captureGroupId = Uuid(hex("3041526374854677a8b9cadbdcedfe2f")),
        sessionGeneration = 1,
        groupGeneration = 1,
        coverageEpoch = 1,
        streamToken = 0,
        payload = payload,
    )

    private fun JsonObject.int(key: String): Int = getValue(key).jsonPrimitive.int
    private fun JsonObject.long(key: String): Long = getValue(key).jsonPrimitive.long
    private fun JsonObject.string(key: String): String = getValue(key).jsonPrimitive.content
    private fun JsonObject.policy(errorId: Int): List<Int> = getValue(errorId.toString()).jsonObject.let {
        listOf(
            it.int("disposition"),
            it.int("validationPhase"),
            it.int("recoveryAction"),
            it.int("resultFlags"),
        )
    }

    private fun hex(value: String): ByteArray = ByteArray(value.length / 2) { index ->
        value.substring(index * 2, index * 2 + 2).toInt(16).toByte()
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { byte -> "%02x".format(byte) }
}
