package com.uhg0.ar_flutter_plugin_2.m0

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertEquals
import org.junit.Test

class M0bFusionVectorTest {
    @Test
    fun `Kotlin fusion candidates match the shared surface and lineage vector`() {
        val root = fixture()
        val observations = root.getValue("observations").jsonArray.map { value ->
            val row = value.jsonObject
            M0VoxelObservation(
                x = row.int("x"),
                y = row.int("y"),
                z = row.int("z"),
                signedWeight = row.int("signedWeight"),
                supportId = row.int("supportId"),
            )
        }
        val expected = root.getValue("expected").jsonObject

        listOf(
            "A" to M0SignedOccupancyKernel(),
            "B" to M0PlanarConsolidationKernel(),
            "C" to M0BoundedTsdfKernel(),
        ).forEach { (candidate, kernel) ->
            val actual = kernel.fuse(observations)
            val rows = expected.getValue(candidate).jsonArray
            assertEquals(candidate, 0, actual.overflowObservationCount)
            assertEquals(candidate, rows.size, actual.surfaces.size)
            rows.forEachIndexed { index, value ->
                val row = value.jsonObject
                val surface = actual.surfaces[index]
                assertEquals(candidate, row.long("surfaceId"), surface.surfaceId)
                assertEquals(candidate, M0VoxelKey(row.int("x"), row.int("y"), row.int("z")), surface.key)
                assertEquals(candidate, row.int("weight"), surface.weight)
                assertEquals(candidate, row.int("normalOctant"), surface.normalOctant)
                assertEquals(candidate, row.int("extentU"), surface.extentU)
                assertEquals(candidate, row.int("extentV"), surface.extentV)
                assertEquals(candidate, row.int("planeAxis"), surface.planeAxis)
                assertEquals(
                    candidate,
                    row.getValue("lineageIds").jsonArray.map { it.jsonPrimitive.int },
                    surface.lineageIds,
                )
                assertEquals(candidate, row.int("observationCount"), surface.observationCount)
            }
        }
    }

    private fun fixture(): JsonObject =
        Json.parseToJsonElement(
            requireNotNull(javaClass.classLoader?.getResourceAsStream("m0b_fusion_vector_v1.json"))
                .bufferedReader()
                .use { it.readText() },
        ).jsonObject

    private fun JsonObject.int(key: String): Int = getValue(key).jsonPrimitive.int

    private fun JsonObject.long(key: String): Long = getValue(key).jsonPrimitive.long
}
