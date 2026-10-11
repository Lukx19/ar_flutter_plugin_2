package com.uhg0.ar_flutter_plugin_2.visibilityprotocol

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.*
import org.junit.Test

class SparseRendererStyleCommandV1Test {
    @Test fun `distinct sparse ABI matches shared vector and rejects full page interpretation`() {
        val fixture = Json.parseToJsonElement(requireNotNull(javaClass.classLoader
            ?.getResourceAsStream("sparse_renderer_style_command_v1.json")).bufferedReader().readText()).jsonObject
        val bytes = hex(fixture.getValue("commandHex").jsonPrimitive.content)
        val cut = SparseRendererStyleCommandV1.decode(bytes)
        assertEquals(512, cut.sparseContext!!.totalRows)
        assertEquals(1L, cut.sparseContext!!.baseStyleRevision)
        assertEquals(2L, cut.styleRevision)
        assertArrayEquals(longArrayOf(7), cut.surfaceIds)
        assertArrayEquals(hex(fixture.getValue("styleRowsHex").jsonPrimitive.content), cut.styleRows)
        assertArrayEquals(bytes, SparseRendererStyleCommandV1.encode(cut))
        assertThrows(IllegalArgumentException::class.java) { RendererStyleCommandV1.decode(bytes) }
        val surrounded = ByteArray(bytes.size + 14) { 99 }
        bytes.copyInto(surrounded, 7)
        assertArrayEquals(bytes, SparseRendererStyleCommandV1.encode(SparseRendererStyleCommandV1.decode(surrounded, 7, bytes.size)))
    }

    @Test fun `sparse header flags lengths generations and duplicate ids fail before decoded publication`() {
        val valid = SparseRendererStyleCommandV1.encode(sparseFixture())
        for (offset in intArrayOf(2, 6, 141, 176, 223)) {
            val invalid = valid.copyOf().also { it[offset] = 1 }
            assertThrows(IllegalArgumentException::class.java) { SparseRendererStyleCommandV1.decode(invalid) }
        }
        val duplicate = sparseFixture(ids = longArrayOf(1, 1))
        assertThrows(IllegalArgumentException::class.java) { SparseRendererStyleCommandV1.decode(SparseRendererStyleCommandV1.encode(duplicate)) }
        assertThrows(IllegalArgumentException::class.java) { SparseRendererStyleCommandV1.decode(valid.copyOf(valid.size - 1)) }
        val wrongBase = valid.copyOf().also { ByteBuffer.wrap(it).order(ByteOrder.LITTLE_ENDIAN).putLong(120, 9) }
        assertThrows(IllegalArgumentException::class.java) { SparseRendererStyleCommandV1.decode(wrongBase) }
        val zero = SparseRendererStyleCommandV1.decode(SparseRendererStyleCommandV1.encode(sparseFixture(ids = longArrayOf())))
        assertEquals(256, SparseRendererStyleCommandV1.encode(zero).size)
        assertEquals(2L, zero.styleRevision)
    }

    private fun hex(value: String) = ByteArray(value.length / 2) { value.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
}

internal fun sparseFixture(ids: LongArray = longArrayOf(1), total: Int = 512,
    transaction: Long = 0, geometry: Long = 0, lineage: Long = 0,
    base: Long = 1, age: Int = 0, group: ByteArray = ByteArray(16),
    rows: ByteArray = ByteArray(ids.size * 16).also { bytes ->
        for (index in ids.indices) com.uhg0.ar_flutter_plugin_2.pointcloud.CoverageRendererStyleRowV1(
            semanticGeneration = base + 1, styleGeneration = base + 1,
            age = com.uhg0.ar_flutter_plugin_2.pointcloud.CoverageRendererAge.values()[age]
        ).encodeInto(bytes, index * 16)
    }): RendererStyleCutPayloadV1 {
    // Duplicate/malformed fixtures are built after the normal payload boundary.
    val safeIds = LongArray(ids.size) { it + 1L }
    val cut = RendererStyleCutPayloadV1(group, 1, 1, transaction, geometry, lineage,
        base + 1, base + 1, base + 1, base + 1, base + 1, true, safeIds, rows,
        sparseContext = SparseRendererStyleContextV1(base, base, base, base, base, total, age, ByteArray(32)))
    ids.copyInto(cut.surfaceIds)
    return cut
}
