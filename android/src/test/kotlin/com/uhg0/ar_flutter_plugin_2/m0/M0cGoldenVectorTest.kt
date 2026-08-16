package com.uhg0.ar_flutter_plugin_2.m0

import java.security.MessageDigest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.int
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class M0cGoldenVectorTest {
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

    private fun fixture(): JsonObject =
        Json.parseToJsonElement(
            requireNotNull(javaClass.classLoader?.getResourceAsStream("m0c_golden_vector_v1.json"))
                .bufferedReader()
                .use { it.readText() },
        ).jsonObject

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
