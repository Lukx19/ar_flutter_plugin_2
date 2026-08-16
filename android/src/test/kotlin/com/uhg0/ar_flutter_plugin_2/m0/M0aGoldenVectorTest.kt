package com.uhg0.ar_flutter_plugin_2.m0

import java.security.MessageDigest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.long
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class M0aGoldenVectorTest {
    @Test
    fun `pinned Dart vector has identical Kotlin bytes and values`() {
        val root = fixture()
        val requestSpec = root.getValue("request").jsonObject
        val request = M0aPacketCodec.Request(
            requestFlags = 0,
            streamToken = requestSpec.long("streamToken"),
            acknowledgedTransactionId = 0,
            acknowledgedGeometryRevision = 0,
            acknowledgedLineageRevision = 0,
            nextStyleRevision = 0,
            maximumResponseBytes = requestSpec.int("maximumResponseBytes"),
            styleRecords = requestSpec.getValue("styleRecordsHex").jsonArray.map { hex(it.jsonPrimitive.content) },
            commandBytes = hex(requestSpec.getValue("commandHex").jsonPrimitive.content),
            requestSequence = requestSpec.long("requestSequence"),
        )
        val requestBytes = M0aPacketCodec.encodeRequest(request)
        assertEquals(requestSpec.int("length"), requestBytes.size)
        assertEquals(requestSpec.getValue("sha256").jsonPrimitive.content, sha256(requestBytes))
        val decodedRequest = M0aPacketCodec.decodeRequest(requestBytes)
        assertEquals(request.streamToken, decodedRequest.streamToken)
        assertEquals(request.maximumResponseBytes, decodedRequest.maximumResponseBytes)
        assertEquals(request.requestSequence, decodedRequest.requestSequence)
        assertArrayEquals(request.styleRecords.single(), decodedRequest.styleRecords.single())
        assertArrayEquals(request.commandBytes, decodedRequest.commandBytes)

        val responseSpec = root.getValue("response").jsonObject
        val response = M0aPacketCodec.noChanges(
            streamToken = responseSpec.long("streamToken"),
            requestSequence = responseSpec.long("requestSequence"),
            nextExpectedRequestSequence = responseSpec.long("nextExpectedRequestSequence"),
        )
        val responseBytes = M0aPacketCodec.encodeResponse(
            response,
            requestSpec.int("maximumResponseBytes"),
        )
        assertEquals(responseSpec.int("length"), responseBytes.size)
        assertEquals(responseSpec.getValue("sha256").jsonPrimitive.content, sha256(responseBytes))
        val decodedResponse = M0aPacketCodec.decodeResponse(responseBytes)
        assertEquals(response.messageKind, decodedResponse.messageKind)
        assertEquals(response.streamToken, decodedResponse.streamToken)
        assertEquals(response.requestSequence, decodedResponse.requestSequence)
        assertEquals(response.nextExpectedRequestSequence, decodedResponse.nextExpectedRequestSequence)
        assertArrayEquals(responseBytes, M0aPacketCodec.encodeResponse(decodedResponse, 4096))
    }

    private fun fixture(): JsonObject =
        Json.parseToJsonElement(
            requireNotNull(javaClass.classLoader?.getResourceAsStream("m0a_golden_vector_v1.json"))
                .bufferedReader()
                .use { it.readText() },
        ).jsonObject

    private fun JsonObject.int(key: String): Int = getValue(key).jsonPrimitive.int

    private fun JsonObject.long(key: String): Long = getValue(key).jsonPrimitive.long

    private fun hex(value: String): ByteArray {
        require(value.length % 2 == 0)
        return ByteArray(value.length / 2) { index ->
            value.substring(index * 2, index * 2 + 2).toInt(16).toByte()
        }
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { byte ->
            "%02x".format(byte)
        }
}
