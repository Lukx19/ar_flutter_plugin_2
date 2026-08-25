package com.uhg0.ar_flutter_plugin_2.m0

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class M0aControlCorpusTest {
    @Test
    fun `START payload failures retain stable phase field and evidence identity`() {
        val valid = M0aStartRequestCodecV2.defaultPayload()
        val cases = listOf(
            valid.copyOf().also { ByteBuffer.wrap(it).order(ByteOrder.LITTLE_ENDIAN).putShort(4, 6.toShort()) } to
                M0aControlValidationFailure(1, 4, 2, 5, 6),
            valid.copyOf().also {
                it[7] = 1
                ByteBuffer.wrap(it).order(ByteOrder.LITTLE_ENDIAN).putShort(4, 6.toShort())
            } to M0aControlValidationFailure(2, 4, 2, 5, 6),
            valid.copyOf().also { it[7] = 2 } to
                M0aControlValidationFailure(6, 3, 5, 1, 2),
            valid.copyOf().also { ByteBuffer.wrap(it).order(ByteOrder.LITTLE_ENDIAN).putInt(24, 0) } to
                M0aControlValidationFailure(36, 4, 20, 4096, 0),
            valid.copyOf().also { ByteBuffer.wrap(it).order(ByteOrder.LITTLE_ENDIAN).putShort(48, 2.toShort()) } to
                M0aControlValidationFailure(6, 5, 21, 1, 2),
            valid.copyOf().also {
                ByteBuffer.wrap(it).order(ByteOrder.LITTLE_ENDIAN).putDouble(136, Double.NaN)
            } to M0aControlValidationFailure(36, 5, 21, 0, Double.NaN.toRawBits()),
            valid.copyOf().also { it[392] = 1 } to
                M0aControlValidationFailure(6, 6, 22, 0, 2),
            valid.copyOf().also { it[456] = 1 } to
                M0aControlValidationFailure(6, 7, 5, 0, 1),
        )
        cases.forEach { (payload, expected) ->
            assertEquals(expected, M0aControlCodec.validateControlPayload(startRequest(payload)))
            val detailed = M0aStartRequestCodecV2.decodeDetailed(payload)
            assertEquals(
                expected,
                (detailed as M0aStartRequestCodecV2.DetailedDecode.Invalid).failure,
            )
            val strict = assertThrows(M0aStartRequestCodecV2.ValidationException::class.java) {
                M0aStartRequestCodecV2.decode(payload)
            }
            assertEquals(expected, strict.failure)
        }
        assertEquals(null, M0aControlCodec.validateControlPayload(startRequest(valid)))
        val detailedValid = (M0aStartRequestCodecV2.decodeDetailed(valid) as
            M0aStartRequestCodecV2.DetailedDecode.Valid).configuration
        assertEquals(M0aStartRequestCodecV2.decode(valid).persistenceSchema, detailedValid.persistenceSchema)
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
        assertEquals(M0aControlCodec.errorDetailBytes, validation.int("errorDetailBytes"))
        root.getValue("requests").jsonArray.forEach { raw ->
            val spec = raw.jsonObject
            val request = M0aControlRequest(
                operation = M0aControlOperation.fromWire(spec.int("operation")),
                flags = 0,
                controlRequestId = M0aUuid(hex(spec.string("controlRequestId"))),
                sessionId = M0aUuid(hex(common.string("sessionId"))),
                captureGroupId = M0aUuid(hex(common.string("captureGroupId"))),
                sessionGeneration = common.long("sessionGeneration"),
                groupGeneration = common.long("groupGeneration"),
                coverageEpoch = common.long("coverageEpoch"),
                streamToken = spec.long("streamToken"),
                payload = hex(spec.string("payloadHex")),
            )
            val bytes = M0aControlCodec.encodeRequest(request)
            assertEquals(spec.int("length"), bytes.size)
            assertEquals(spec.string("sha256"), sha256(bytes))
            assertArrayEquals(bytes, M0aControlCodec.encodeRequest(M0aControlCodec.decodeRequest(bytes)))
            assertEquals(null, M0aControlCodec.validateControlPayload(request))
            val truncated = M0aControlCodec.validateControlPayload(
                request.copy(payload = request.payload.copyOf(request.payload.size - 1)),
            )
            assertEquals(2, truncated?.validationPhase)
            assertEquals(3, truncated?.fieldId)
        }

        val response = root.getValue("startResponse").jsonObject
        val responseBytes = M0aControlCodec.encodeResponse(
            M0aControlResponse(
                operation = M0aControlOperation.START,
                outcome = response.int("outcome"),
                resultFlags = response.int("resultFlags"),
                errorId = response.int("errorId"),
                controlRequestId = M0aUuid(hex(response.string("controlRequestId"))),
                sessionId = M0aUuid(hex(common.string("sessionId"))),
                captureGroupId = M0aUuid(hex(common.string("captureGroupId"))),
                sessionGeneration = common.long("sessionGeneration"),
                groupGeneration = common.long("groupGeneration"),
                coverageEpoch = common.long("coverageEpoch"),
                streamToken = response.long("streamToken"),
                nextExchangeRequestSequence = response.long("nextExchangeRequestSequence"),
                nativeTransactionId = response.long("nativeTransactionId"),
                payload = hex(response.string("payloadHex")),
            ),
            M0aControlCodec.hardCeilingBytes,
        )
        assertEquals(response.int("length"), responseBytes.size)
        assertEquals(response.string("sha256"), sha256(responseBytes))

        val malformed = root.getValue("malformedControlErrorResponse").jsonObject
        val detail = malformed.getValue("detail").jsonObject
        val errorDetail = M0aControlCodec.encodeErrorDetail(
            M0aErrorDetail(
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
        val errorBytes = M0aControlCodec.encodeResponse(
            M0aControlResponse(
                operation = M0aControlOperation.START,
                outcome = malformed.int("outcome"),
                resultFlags = malformed.int("resultFlags"),
                errorId = malformed.int("errorId"),
                controlRequestId = M0aUuid(hex(malformed.string("controlRequestId"))),
                sessionId = M0aUuid(hex(common.string("sessionId"))),
                captureGroupId = M0aUuid(hex(common.string("captureGroupId"))),
                sessionGeneration = common.long("sessionGeneration"),
                groupGeneration = common.long("groupGeneration"),
                coverageEpoch = common.long("coverageEpoch"),
                streamToken = malformed.long("streamToken"),
                nextExchangeRequestSequence = malformed.long("nextExchangeRequestSequence"),
                nativeTransactionId = malformed.long("nativeTransactionId"),
                payload = errorDetail,
            ),
            M0aControlCodec.hardCeilingBytes,
        )
        assertEquals(malformed.int("length"), errorBytes.size)
        assertEquals(malformed.string("sha256"), sha256(errorBytes))
        val decodedError = M0aControlCodec.decodeResponse(errorBytes)
        assertEquals(6, decodedError.errorId)
        assertEquals(0, M0aControlCodec.decodeErrorDetail(decodedError.payload).disposition)

        val mismatchedErrorId = errorBytes.copyOf().also { bytes ->
            bytes[M0aControlCodec.responseHeaderBytes] = 5
            ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).putInt(
                120,
                M0aControlCodec.crc32(bytes, 120),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            M0aControlCodec.decodeResponse(mismatchedErrorId)
        }
        val mismatchedDiagnosticLength = errorBytes.copyOf().also { bytes ->
            ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).putShort(
                M0aControlCodec.responseHeaderBytes + 10,
                1.toShort(),
            )
            ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).putInt(
                120,
                M0aControlCodec.crc32(bytes, 120),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            M0aControlCodec.decodeResponse(mismatchedDiagnosticLength)
        }
        val emptyError = M0aControlCodec.encodeResponse(
            decodedError.copy(payload = byteArrayOf()),
            M0aControlCodec.hardCeilingBytes,
        )
        assertThrows(IllegalArgumentException::class.java) {
            M0aControlCodec.decodeResponse(emptyError)
        }
    }

    private fun fixture(): JsonObject = Json.parseToJsonElement(
        requireNotNull(javaClass.classLoader?.getResourceAsStream("m0a_control_corpus_v1.json"))
            .bufferedReader().use { it.readText() },
    ).jsonObject

    private fun startRequest(payload: ByteArray) = M0aControlRequest(
        operation = M0aControlOperation.START,
        flags = 0,
        controlRequestId = M0aUuid(hex("102132435465467798a9bacbdcedfe0f")),
        sessionId = M0aUuid(hex("2031425364754677a8b9cadbdcedfe1f")),
        captureGroupId = M0aUuid(hex("3041526374854677a8b9cadbdcedfe2f")),
        sessionGeneration = 1,
        groupGeneration = 1,
        coverageEpoch = 1,
        streamToken = 0,
        payload = payload,
    )

    private fun JsonObject.int(key: String): Int = getValue(key).jsonPrimitive.int
    private fun JsonObject.long(key: String): Long = getValue(key).jsonPrimitive.long
    private fun JsonObject.string(key: String): String = getValue(key).jsonPrimitive.content

    private fun hex(value: String): ByteArray = ByteArray(value.length / 2) { index ->
        value.substring(index * 2, index * 2 + 2).toInt(16).toByte()
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { byte -> "%02x".format(byte) }
}
