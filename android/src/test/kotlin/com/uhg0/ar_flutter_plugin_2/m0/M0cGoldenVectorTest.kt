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

    private fun fixture(fileName: String = "m0c_golden_vector_v1.json"): JsonObject =
        Json.parseToJsonElement(
            requireNotNull(javaClass.classLoader?.getResourceAsStream(fileName))
                .bufferedReader()
                .use { it.readText() },
        ).jsonObject

    private fun resourceBytes(fileName: String): ByteArray =
        requireNotNull(javaClass.classLoader?.getResourceAsStream(fileName)).readBytes()

    private fun assertVector(bytes: ByteArray, spec: JsonObject) {
        assertEquals(spec.int("length"), bytes.size)
        assertEquals(spec.getValue("sha256").jsonPrimitive.content, sha256(bytes))
        assertEquals(spec.getValue("hex").jsonPrimitive.content, hex(bytes))
        assertTrue(bytes.isNotEmpty())
    }

    private fun JsonObject.int(key: String): Int = getValue(key).jsonPrimitive.int

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { byte ->
            "%02x".format(byte)
        }

    private fun hex(bytes: ByteArray): String =
        bytes.joinToString("") { byte -> "%02x".format(byte) }
}
