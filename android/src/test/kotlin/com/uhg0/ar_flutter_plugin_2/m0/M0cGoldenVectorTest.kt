package com.uhg0.ar_flutter_plugin_2.m0

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.int
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class M0cGoldenVectorTest {
    @Test
    fun `staged M0c fault corpus is hash bound and complete`() {
        val manifest = fixture("m0c_reference_corpus_v1.json")
        val faultCorpus = manifest.getValue("faultCorpus").jsonObject
        val base = fixture(faultCorpus.getValue("baseCorpus").jsonPrimitive.content)
        val baseCases = base.getValue("faults").jsonArray.associateBy {
            it.jsonObject.getValue("name").jsonPrimitive.content
        }
        val stages = faultCorpus.getValue("stageCorpora").jsonObject
        val namesByStage = mutableMapOf<String, Set<String>>()
        var caseCount = 0
        stages.forEach { (stage, descriptorElement) ->
            val descriptor = descriptorElement.jsonObject
            val bytes = resourceBytes(descriptor.getValue("file").jsonPrimitive.content)
            assertEquals(descriptor.getValue("sha256").jsonPrimitive.content, sha256(bytes))
            val root = Json.parseToJsonElement(bytes.decodeToString()).jsonObject
            assertEquals(stage, root.getValue("stage").jsonPrimitive.content)
            assertEquals(faultCorpus.getValue("status"), root.getValue("status"))
            val names = root.getValue("cases").jsonArray.map { value ->
                val fault = value.jsonObject
                val name = fault.getValue("name").jsonPrimitive.content
                assertEquals(baseCases.getValue(name), fault)
                caseCount++
                name
            }.toSet()
            namesByStage[stage] = names
        }
        assertEquals(faultCorpus.int("expectedCaseCount"), caseCount)
        assertEquals(baseCases.keys, namesByStage.values.flatten().toSet())
    }

    @Test
    fun `pinned Dart schema five shards have identical Kotlin bytes and values`() {
        val root = fixture()
        val regionValues = root.getValue("region").toString()
            .removePrefix("[")
            .removeSuffix("]")
            .split(",")
            .map { it.trim().toInt() }
        val region = M0RegionCoordinate(regionValues[0], regionValues[1], regionValues[2])
        val canonical = M0RegionShardV5.encodeCanonical(
            region = region,
            captureEvaluatedThrough = root.int("captureEvaluatedThrough").toLong(),
            pendingThrough = root.int("pendingThrough").toLong(),
            surfaceRows = listOf(ByteArray(19) { it.toByte() }),
            lineageRows = listOf(ByteArray(9) { (it + 19).toByte() }),
        )
        assertVector(canonical, root.getValue("canonical").jsonObject)
        val decodedCanonical = M0RegionShardV5.decode(canonical)
        assertEquals(region, decodedCanonical.region)
        assertEquals(19, decodedCanonical.surfaceRows.single().size)
        assertEquals(9, decodedCanonical.secondaryRows.single().size)

        val coverage = M0RegionShardV5.encodeCoverage(
            region = region,
            captureEvaluatedThrough = root.int("captureEvaluatedThrough").toLong(),
            pendingThrough = root.int("pendingThrough").toLong(),
            surfaceRows = listOf(ByteArray(56) { (it + 28).toByte() }),
            overflowRows = listOf(ByteArray(13) { (it + 84).toByte() }),
            compression = M0RegionShardV5.Compression.ZLIB,
        )
        assertVector(coverage, root.getValue("coverage").jsonObject)
        val decodedCoverage = M0RegionShardV5.decode(coverage)
        assertEquals(M0RegionShardV5.Compression.ZLIB, decodedCoverage.compression)
        assertEquals(56, decodedCoverage.surfaceRows.single().size)
        assertEquals(13, decodedCoverage.secondaryRows.single().size)
    }

    @Test
    fun `schema five decoder rejects declared row bytes that do not match payload`() {
        val packet = M0RegionShardV5.encodeCanonical(
            region = M0RegionCoordinate(1, 2, 3),
            captureEvaluatedThrough = 2,
            pendingThrough = 1,
            surfaceRows = listOf(ByteArray(19) { it.toByte() }),
            lineageRows = listOf(ByteArray(9) { (it + 19).toByte() }),
        )
        ByteBuffer.wrap(packet).order(ByteOrder.LITTLE_ENDIAN).putInt(24, 0)

        assertThrows(IllegalArgumentException::class.java) {
            M0RegionShardV5.decode(packet)
        }
    }

    @Test
    fun `schema five decoder rejects zlib payload trailing bytes`() {
        val packet = M0RegionShardV5.encodeCoverage(
            region = M0RegionCoordinate(1, 2, 3),
            captureEvaluatedThrough = 2,
            pendingThrough = 1,
            surfaceRows = listOf(ByteArray(56)),
            overflowRows = listOf(ByteArray(13)),
            compression = M0RegionShardV5.Compression.ZLIB,
        )
        val malformed = packet.copyOf(packet.size + 1)
        malformed[malformed.lastIndex] = 0x7f
        ByteBuffer.wrap(malformed).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(52, malformed.size - M0RegionShardV5.headerBytes)
        MessageDigest.getInstance("SHA-256")
            .digest(malformed.copyOfRange(M0RegionShardV5.headerBytes, malformed.size))
            .copyInto(malformed, 64)

        assertThrows(IllegalArgumentException::class.java) {
            M0RegionShardV5.decode(malformed)
        }
    }

    @Test
    fun `schema five decoder rejects a duplicated zlib trailer`() {
        val packet = M0RegionShardV5.encodeCoverage(
            region = M0RegionCoordinate(1, 2, 3),
            captureEvaluatedThrough = 2,
            pendingThrough = 1,
            surfaceRows = listOf(ByteArray(56)),
            overflowRows = listOf(ByteArray(13)),
            compression = M0RegionShardV5.Compression.ZLIB,
        )
        val originalPayload = packet.copyOfRange(
            M0RegionShardV5.headerBytes,
            packet.size,
        )
        val payload = originalPayload +
            originalPayload.copyOfRange(originalPayload.size - 4, originalPayload.size)
        val malformed = packet.copyOf(packet.size + 4)
        payload.copyInto(malformed, M0RegionShardV5.headerBytes)
        ByteBuffer.wrap(malformed).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(52, payload.size)
        MessageDigest.getInstance("SHA-256")
            .digest(payload)
            .copyInto(malformed, 64)

        assertThrows(IllegalArgumentException::class.java) {
            M0RegionShardV5.decode(malformed)
        }
    }

    @Test
    fun `schema five decoder accepts an empty zlib shard`() {
        val packet = M0RegionShardV5.encodeCoverage(
            region = M0RegionCoordinate(1, 2, 3),
            captureEvaluatedThrough = 2,
            pendingThrough = 1,
            surfaceRows = emptyList(),
            overflowRows = emptyList(),
            compression = M0RegionShardV5.Compression.ZLIB,
        )

        val decoded = M0RegionShardV5.decode(packet)

        assertTrue(decoded.surfaceRows.isEmpty())
        assertTrue(decoded.secondaryRows.isEmpty())
    }

    @Test
    fun `schema five decoder rejects a zlib checksum mismatch`() {
        val packet = M0RegionShardV5.encodeCoverage(
            region = M0RegionCoordinate(1, 2, 3),
            captureEvaluatedThrough = 2,
            pendingThrough = 1,
            surfaceRows = listOf(ByteArray(56)),
            overflowRows = listOf(ByteArray(13)),
            compression = M0RegionShardV5.Compression.ZLIB,
        )
        val payload = packet.copyOfRange(M0RegionShardV5.headerBytes, packet.size)
        payload[payload.lastIndex] = (payload[payload.lastIndex].toInt() xor 1).toByte()
        val malformed = packet.copyOf()
        payload.copyInto(malformed, M0RegionShardV5.headerBytes)
        MessageDigest.getInstance("SHA-256")
            .digest(payload)
            .copyInto(malformed, 64)

        assertThrows(IllegalArgumentException::class.java) {
            M0RegionShardV5.decode(malformed)
        }
    }

    @Test
    fun `schema five decoder rejects non portable unsigned watermarks`() {
        val packet = M0RegionShardV5.encodeCoverage(
            region = M0RegionCoordinate(1, 2, 3),
            captureEvaluatedThrough = 2,
            pendingThrough = 1,
            surfaceRows = listOf(ByteArray(56)),
            overflowRows = listOf(ByteArray(13)),
        )
        ByteBuffer.wrap(packet).order(ByteOrder.LITTLE_ENDIAN)
            .putLong(32, Long.MIN_VALUE)

        assertThrows(IllegalArgumentException::class.java) {
            M0RegionShardV5.decode(packet)
        }
    }

    @Test
    fun `schema five decoder rejects a packet larger than the total shard limit`() {
        val packet = M0RegionShardV5.encodeCoverage(
            region = M0RegionCoordinate(1, 2, 3),
            captureEvaluatedThrough = 2,
            pendingThrough = 1,
            surfaceRows = listOf(ByteArray(56)),
            overflowRows = listOf(ByteArray(13)),
        )
        val oversized = ByteArray(M0RegionShardV5.maximumBytes + 1)
        packet.copyInto(oversized)

        assertThrows(IllegalArgumentException::class.java) {
            M0RegionShardV5.decode(oversized)
        }
    }

    @Test
    fun `hash bound schema five resource corpus preserves bounds precedence`() {
        val corpusBytes = resourceBytes("m0c_shard_resource_corpus_v1.json")
        assertEquals(
            "db975c3001f3e682958399fd9a25deb29c3fbe4333836d5fe0f3443e77fe1875",
            sha256(corpusBytes),
        )
        val manifest = fixture("m0c_shard_resource_corpus_v1.json")
        val baseVector = fixture(manifest.getValue("baseVector").jsonPrimitive.content)
        val cases = manifest.getValue("cases").jsonArray
        assertEquals(manifest.int("expectedCaseCount"), cases.size)
        assertEquals(
            manifest.getValue("expectedCaseNames").jsonArray.map { it.jsonPrimitive.content },
            cases.map { it.jsonObject.getValue("name").jsonPrimitive.content },
        )
        val base = resourceBasePacket(baseVector)
        cases.forEach { raw ->
            val fault = raw.jsonObject
            val malformed = applyResourceCase(
                base,
                fault.getValue("operation").jsonPrimitive.content,
            )
            assertThrows(IllegalArgumentException::class.java) {
                M0RegionShardV5.decode(malformed)
            }
        }
    }

    @Test
    fun `hash bound schema five shard error corpus preserves Kotlin outcomes`() {
        val corpusBytes = resourceBytes("m0c_shard_error_corpus_v1.json")
        assertEquals(
            "257338f80bd2dc797c3c12e645bcd4746b1b54266a181c058e78f2263613e83f",
            sha256(corpusBytes),
        )
        val manifest = fixture("m0c_shard_error_corpus_v1.json")
        val vectorBytes = resourceBytes(manifest.getValue("baseVector").jsonPrimitive.content)
        assertEquals(
            manifest.getValue("baseVectorSha256").jsonPrimitive.content,
            sha256(vectorBytes),
        )
        val vector = Json.parseToJsonElement(vectorBytes.decodeToString()).jsonObject
        val cases = manifest.getValue("cases").jsonArray
        val expectedNames = manifest.getValue("expectedCaseNames").jsonArray
            .map { it.jsonPrimitive.content }
        assertEquals(manifest.int("expectedCaseCount"), cases.size)
        assertEquals(
            expectedNames,
            cases.map { it.jsonObject.getValue("name").jsonPrimitive.content },
        )
        cases.forEach { raw ->
            val fault = raw.jsonObject
            val packet = applyShardErrorCase(vector, fault)
            val packetSha256 = sha256(packet)
            if (fault.getValue("packetSha256").jsonPrimitive.content != "pending") {
                assertEquals(fault.getValue("packetSha256").jsonPrimitive.content, packetSha256)
            }
            if (fault.getValue("outcome").jsonPrimitive.content == "accept") {
                assertTrue(
                    fault.getValue("name").jsonPrimitive.content,
                    runCatching { M0RegionShardV5.decode(packet) }.isSuccess,
                )
            } else {
                assertThrows(IllegalArgumentException::class.java) {
                    M0RegionShardV5.decode(packet)
                }
            }
        }
    }

    private fun fixture(fileName: String = "m0c_golden_vector_v1.json"): JsonObject =
        Json.parseToJsonElement(
            requireNotNull(javaClass.classLoader?.getResourceAsStream(fileName))
                .bufferedReader()
                .use { it.readText() },
        ).jsonObject

    private fun resourceBytes(fileName: String): ByteArray =
        requireNotNull(javaClass.classLoader?.getResourceAsStream(fileName)).readBytes()

    private fun resourceBasePacket(vector: JsonObject): ByteArray {
        val regionValues = vector.getValue("region").jsonArray.map { it.jsonPrimitive.int }
        return M0RegionShardV5.encodeCoverage(
            region = M0RegionCoordinate(regionValues[0], regionValues[1], regionValues[2]),
            captureEvaluatedThrough = vector.int("captureEvaluatedThrough").toLong(),
            pendingThrough = vector.int("pendingThrough").toLong(),
            compression = M0RegionShardV5.Compression.ZLIB,
            surfaceRows = listOf(ByteArray(56)),
            overflowRows = listOf(ByteArray(13)),
        )
    }

    private fun applyResourceCase(base: ByteArray, operation: String): ByteArray {
        if (operation == "oversize-packet") {
            return ByteArray(M0RegionShardV5.maximumBytes + 1).also { oversized ->
                base.copyInto(oversized)
            }
        }
        val malformed = base.copyOf()
        val data = ByteBuffer.wrap(malformed).order(ByteOrder.LITTLE_ENDIAN)
        when (operation) {
            "set-capture-high-bit" -> data.putLong(32, Long.MIN_VALUE)
            "set-pending-high-bit" -> data.putLong(40, Long.MIN_VALUE)
            "set-stored-bytes-zero" -> data.putInt(52, 0)
            else -> error("Unknown M0c resource operation: $operation")
        }
        return malformed
    }

    private fun assertVector(bytes: ByteArray, spec: JsonObject) {
        assertEquals(spec.int("length"), bytes.size)
        assertEquals(spec.getValue("sha256").jsonPrimitive.content, sha256(bytes))
        assertEquals(spec.getValue("hex").jsonPrimitive.content, hex(bytes))
        assertTrue(bytes.isNotEmpty())
    }

    private fun applyShardErrorCase(vector: JsonObject, fault: JsonObject): ByteArray {
        val regionValues = vector.getValue("region").jsonArray.map { it.jsonPrimitive.int }
        val region = M0RegionCoordinate(regionValues[0], regionValues[1], regionValues[2])
        val capture = vector.int("captureEvaluatedThrough").toLong()
        val pending = vector.int("pendingThrough").toLong()
        return when (fault.getValue("operation").jsonPrimitive.content) {
            "zero-canonical-surface-count" -> M0RegionShardV5.encodeCanonical(
                region = region,
                captureEvaluatedThrough = capture,
                pendingThrough = pending,
                surfaceRows = listOf(ByteArray(19) { it.toByte() }),
                lineageRows = listOf(ByteArray(9) { (it + 19).toByte() }),
            ).also { packet ->
                ByteBuffer.wrap(packet).order(ByteOrder.LITTLE_ENDIAN).putInt(24, 0)
            }
            "encode-empty-coverage" -> M0RegionShardV5.encodeCoverage(
                region = region,
                captureEvaluatedThrough = capture,
                pendingThrough = pending,
                surfaceRows = emptyList(),
                overflowRows = emptyList(),
                compression = M0RegionShardV5.Compression.ZLIB,
            )
            else -> {
                val base = M0RegionShardV5.encodeCoverage(
                    region = region,
                    captureEvaluatedThrough = capture,
                    pendingThrough = pending,
                    surfaceRows = listOf(ByteArray(56)),
                    overflowRows = listOf(ByteArray(13)),
                    compression = M0RegionShardV5.Compression.ZLIB,
                )
                val originalPayload = base.copyOfRange(M0RegionShardV5.headerBytes, base.size)
                val payload = when (fault.getValue("operation").jsonPrimitive.content) {
                    "append-byte" -> originalPayload +
                        byteArrayOf(fault.getValue("byte").jsonPrimitive.int.toByte())
              "append-last-bytes" -> originalPayload + originalPayload.copyOfRange(
                  originalPayload.size - fault.int("count"),
                  originalPayload.size,
              )
              "append-base-payload" -> originalPayload + originalPayload
              "set-dictionary-flag" -> withZlibDictionaryFlag(originalPayload)
              "inflate-ratio-over-64" -> originalPayload.copyOf()
                    "xor-last-byte" -> originalPayload.copyOf().also { bytes ->
                        bytes[bytes.lastIndex] = (
                            bytes[bytes.lastIndex].toInt() xor fault.int("mask")
                        ).toByte()
                    }
                    else -> error("Unknown M0c shard error operation")
                }
                val malformed = base.copyOf(M0RegionShardV5.headerBytes + payload.size)
                payload.copyInto(malformed, M0RegionShardV5.headerBytes)
                ByteBuffer.wrap(malformed).order(ByteOrder.LITTLE_ENDIAN)
                .putInt(52, payload.size)
                .also {
                    if (fault.getValue("operation").jsonPrimitive.content == "inflate-ratio-over-64") {
                        it.putInt(24, fault.int("surfaceCount"))
                        it.putInt(28, 0)
                        it.putInt(56, fault.int("decodedBytes"))
                    }
                }
                MessageDigest.getInstance("SHA-256")
                    .digest(payload)
                    .copyInto(malformed, 64)
                malformed
            }
        }
    }

    private fun withZlibDictionaryFlag(payload: ByteArray): ByteArray {
        val result = payload.copyOf()
        val cmf = result[0].toInt() and 0xff
        var flg = (result[1].toInt() or 0x20) and 0xe0
        while (((cmf shl 8) or flg) % 31 != 0) {
            flg++
        }
        result[1] = flg.toByte()
        return result
    }

    private fun JsonObject.int(key: String): Int = getValue(key).jsonPrimitive.int

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { byte ->
            "%02x".format(byte)
        }

    private fun hex(bytes: ByteArray): String =
        bytes.joinToString("") { byte -> "%02x".format(byte) }
}
