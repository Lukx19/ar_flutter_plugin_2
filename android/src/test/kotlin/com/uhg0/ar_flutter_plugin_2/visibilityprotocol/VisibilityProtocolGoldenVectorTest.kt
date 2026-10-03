package com.uhg0.ar_flutter_plugin_2.visibilityprotocol


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
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class VisibilityProtocolGoldenVectorTest {
    @Test
    fun `pinned Dart vector has identical Kotlin bytes and values`() {
        val root = fixture()
        val requestSpec = root.getValue("request").jsonObject
        val request = PacketCodec.Request(
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
        val requestBytes = PacketCodec.encodeRequest(request)
        assertEquals(requestSpec.int("length"), requestBytes.size)
        assertEquals(requestSpec.getValue("sha256").jsonPrimitive.content, sha256(requestBytes))
        val decodedRequest = PacketCodec.decodeRequest(requestBytes)
        assertEquals(request.streamToken, decodedRequest.streamToken)
        assertEquals(request.maximumResponseBytes, decodedRequest.maximumResponseBytes)
        assertEquals(request.requestSequence, decodedRequest.requestSequence)
        assertArrayEquals(request.styleRecords.single(), decodedRequest.styleRecords.single())
        assertArrayEquals(request.commandBytes, decodedRequest.commandBytes)
        val guardedRequest = ByteArray(requestBytes.size + 4) { 0x5a }
        assertEquals(requestBytes.size, PacketCodec.encodeRequestInto(request, guardedRequest, 2))
        assertArrayEquals(requestBytes, guardedRequest.copyOfRange(2, guardedRequest.size - 2))
        assertTrue(guardedRequest.take(2).all { it == 0x5a.toByte() })
        assertTrue(guardedRequest.takeLast(2).all { it == 0x5a.toByte() })
        try {
            PacketCodec.encodeRequestInto(request, ByteArray(requestBytes.size - 1))
            fail("Short request destination accepted")
        } catch (_: IllegalArgumentException) { }

        val responseSpec = root.getValue("response").jsonObject
        val response = PacketCodec.noChanges(
            streamToken = responseSpec.long("streamToken"),
            requestSequence = responseSpec.long("requestSequence"),
            nextExpectedRequestSequence = responseSpec.long("nextExpectedRequestSequence"),
        )
        val responseBytes = PacketCodec.encodeResponse(
            response,
            requestSpec.int("maximumResponseBytes"),
        )
        assertEquals(responseSpec.int("length"), responseBytes.size)
        assertEquals(responseSpec.getValue("sha256").jsonPrimitive.content, sha256(responseBytes))
        val decodedResponse = PacketCodec.decodeResponse(responseBytes)
        assertEquals(response.messageKind, decodedResponse.messageKind)
        assertEquals(response.streamToken, decodedResponse.streamToken)
        assertEquals(response.requestSequence, decodedResponse.requestSequence)
        assertEquals(response.nextExpectedRequestSequence, decodedResponse.nextExpectedRequestSequence)
        assertArrayEquals(responseBytes, PacketCodec.encodeResponse(decodedResponse, 4096))
        val guardedResponse = ByteArray(responseBytes.size + 4) { 0x5a }
        assertEquals(responseBytes.size, PacketCodec.encodeResponseInto(response, 4096, guardedResponse, 2))
        assertArrayEquals(responseBytes, guardedResponse.copyOfRange(2, guardedResponse.size - 2))
        assertTrue(guardedResponse.take(2).all { it == 0x5a.toByte() })
        assertTrue(guardedResponse.takeLast(2).all { it == 0x5a.toByte() })
    }

    private fun fixture(): JsonObject =
        Json.parseToJsonElement(
            requireNotNull(javaClass.classLoader?.getResourceAsStream("visibility_protocol_golden_vector_v1.json"))
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
