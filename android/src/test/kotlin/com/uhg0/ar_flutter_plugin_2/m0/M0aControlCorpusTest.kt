package com.uhg0.ar_flutter_plugin_2.m0

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
import org.junit.Test

class M0aControlCorpusTest {
    @Test
    fun `Kotlin control envelopes match the locked Dart corpus`() {
        val root = fixture()
        val common = root.getValue("common").jsonObject
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
    }

    private fun fixture(): JsonObject = Json.parseToJsonElement(
        requireNotNull(javaClass.classLoader?.getResourceAsStream("m0a_control_corpus_v1.json"))
            .bufferedReader().use { it.readText() },
    ).jsonObject

    private fun JsonObject.int(key: String): Int = getValue(key).jsonPrimitive.int
    private fun JsonObject.long(key: String): Long = getValue(key).jsonPrimitive.long
    private fun JsonObject.string(key: String): String = getValue(key).jsonPrimitive.content

    private fun hex(value: String): ByteArray = ByteArray(value.length / 2) { index ->
        value.substring(index * 2, index * 2 + 2).toInt(16).toByte()
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { byte -> "%02x".format(byte) }
}
