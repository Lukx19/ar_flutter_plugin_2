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
            ),
            M0aControlCodec.hardCeilingBytes,
        )
        assertEquals(response.int("length"), responseBytes.size)
        assertEquals(response.string("sha256"), sha256(responseBytes))
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
