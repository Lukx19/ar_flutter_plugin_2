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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class M0aFaultCorpusTest {
    @Test
    fun `shared M0a fault corpus rejects malformed request and response packets`() {
        val corpus = fixture("m0a_fault_corpus_v1.json")
        val vectors = baseVectors()
        corpus.getValue("cases").jsonArray.forEach { value ->
            val fault = value.jsonObject
            val packet = if (fault.getValue("packet").jsonPrimitive.content == "request") {
                vectors.request
            } else {
                vectors.response
            }
            val mutated = applyFault(packet, fault)
            assertThrows(IllegalArgumentException::class.java) {
                if (fault.getValue("packet").jsonPrimitive.content == "request") {
                    M0aPacketCodec.decodeRequest(mutated)
                } else {
                    M0aPacketCodec.decodeResponse(mutated)
                }
            }
        }
    }

    @Test
    fun `M0a codecs accept exact packet ceilings and reject one byte over`() {
        val bounds = fixture("m0a_fault_corpus_v1.json").getValue("bounds").jsonObject
        val requestCeiling = bounds.int("requestCeilingBytes")
        val responseMaximum = bounds.int("responseMaximumBytes")
        val request = M0aPacketCodec.Request(
            requestFlags = 0,
            streamToken = 7,
            acknowledgedTransactionId = 0,
            acknowledgedGeometryRevision = 0,
            acknowledgedLineageRevision = 0,
            nextStyleRevision = 0,
            maximumResponseBytes = responseMaximum,
            styleRecords = listOf(ByteArray(M0aPacketCodec.styleRecordBytes)),
            commandBytes = ByteArray(
                requestCeiling - M0aPacketCodec.requestHeaderBytes -
                    M0aPacketCodec.styleRecordBytes,
            ),
            requestSequence = 1,
        )
        assertEquals(requestCeiling, M0aPacketCodec.encodeRequest(request).size)
        M0aPacketCodec.decodeRequest(M0aPacketCodec.encodeRequest(request))
        assertThrows(IllegalArgumentException::class.java) {
            M0aPacketCodec.encodeRequest(
                request.copy(commandBytes = ByteArray(request.commandBytes.size + 1)),
            )
        }

        val response = M0aPacketCodec.Response(
            messageKind = 0,
            responseFlags = 0,
            resultFlags = 0,
            errorId = 0,
            requestSequence = 1,
            streamToken = 7,
            nextExpectedRequestSequence = 2,
            payload = ByteArray(responseMaximum - M0aPacketCodec.responseHeaderBytes),
        )
        assertEquals(
            responseMaximum,
            M0aPacketCodec.encodeResponse(response, responseMaximum).size,
        )
        assertThrows(IllegalArgumentException::class.java) {
            M0aPacketCodec.encodeResponse(
                response.copy(payload = ByteArray(response.payload.size + 1)),
                responseMaximum,
            )
        }
    }

    @Test
    fun `seeded M0a request mutations never cross the CRC boundary`() {
        val corpus = fixture("m0a_fault_corpus_v1.json")
        val count = corpus.getValue("bounds").jsonObject.int("seededRequestMutations")
        val request = baseVectors().request
        repeat(count) { seed ->
            val mutated = request.copyOf()
            val offset = (seed * 37 + 11) % mutated.size
            mutated[offset] = (mutated[offset].toInt() xor ((seed % 255) + 1)).toByte()
            assertThrows(IllegalArgumentException::class.java) {
                M0aPacketCodec.decodeRequest(mutated)
            }
        }
    }

    private fun baseVectors(): Vectors {
        val root = fixture("m0a_golden_vector_v1.json")
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
        val responseSpec = root.getValue("response").jsonObject
        val response = M0aPacketCodec.noChanges(
            streamToken = responseSpec.long("streamToken"),
            requestSequence = responseSpec.long("requestSequence"),
            nextExpectedRequestSequence = responseSpec.long("nextExpectedRequestSequence"),
        )
        return Vectors(
            request = M0aPacketCodec.encodeRequest(request),
            response = M0aPacketCodec.encodeResponse(
                response,
                requestSpec.int("maximumResponseBytes"),
            ),
        )
    }

    private fun applyFault(base: ByteArray, fault: JsonObject): ByteArray {
        if (fault.getValue("operation").jsonPrimitive.content == "truncate") {
            return base.copyOf(fault.int("length"))
        }
        val mutated = base.copyOf()
        val offset = fault.int("offset")
        val width = fault.int("width")
        val value = fault.getValue("value").jsonPrimitive.long
        val data = ByteBuffer.wrap(mutated).order(ByteOrder.LITTLE_ENDIAN)
        when (width) {
            8 -> data.put(offset, value.toByte())
            16 -> data.putShort(offset, value.toShort())
            32 -> data.putInt(offset, value.toInt())
            64 -> data.putLong(offset, value)
            else -> error("Unsupported M0a fault width $width")
        }
        if (fault["recomputeCrc"]?.jsonPrimitive?.content == "true") {
            val crcOffset = if (fault.getValue("packet").jsonPrimitive.content == "request") 72 else 104
            data.putInt(crcOffset, 0)
            data.putInt(crcOffset, crc32(mutated, crcOffset))
        }
        return mutated
    }

    private fun fixture(name: String): JsonObject = Json.parseToJsonElement(
        requireNotNull(javaClass.classLoader?.getResourceAsStream(name))
            .bufferedReader()
            .use { it.readText() },
    ).jsonObject

    private fun hex(value: String): ByteArray = ByteArray(value.length / 2) { index ->
        value.substring(index * 2, index * 2 + 2).toInt(16).toByte()
    }

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

    private data class Vectors(val request: ByteArray, val response: ByteArray)

    private fun JsonObject.int(key: String): Int = getValue(key).jsonPrimitive.int

    private fun JsonObject.long(key: String): Long = getValue(key).jsonPrimitive.long
}
