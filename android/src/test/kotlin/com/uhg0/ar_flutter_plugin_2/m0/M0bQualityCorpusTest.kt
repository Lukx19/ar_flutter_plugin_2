package com.uhg0.ar_flutter_plugin_2.m0

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class M0bQualityCorpusTest {
    @Test
    fun `synthetic quality corpus measures A B and C against one oracle`() {
        val root = fixture()
        val scenes = root.getValue("scenes").jsonArray.map { it.jsonObject }
        val observations = scenes.flatMap { scene ->
            scene.getValue("observations").jsonArray.map { value ->
                val row = value.jsonObject
                val key = row.getValue("key").jsonArray.map { it.jsonPrimitive.int }
                M0VoxelObservation(
                    x = key[0],
                    y = key[1],
                    z = key[2],
                    signedWeight = row.int("signedWeight"),
                    supportId = row.int("supportId"),
                )
            }
        }
        val expected = scenes.flatMap { keys(it.getValue("expectedSurfaceKeys")) }.toSet()
        val phantom = scenes.flatMap { keys(it.getValue("phantomKeys")) }.toSet()
        val protected = scenes.flatMap { keys(it.getValue("protectedKeys")) }.toSet()
        val gates = root.getValue("gates").jsonObject
        val baseline = root.double("proposal07BaselineRecall")

        val factories = listOf(
            "A" to { M0SignedOccupancyKernel() },
            "B" to { M0PlanarConsolidationKernel() },
            "C" to { M0BoundedTsdfKernel() },
        )
        val measurements = factories.associate { (candidate, factory) ->
            val first = factory().fuse(observations)
            val repeat = factory().fuse(observations)
            val output = expandedKeys(first.surfaces)
            val repeated = expandedKeys(repeat.surfaces)
            assertEquals(candidate, output, repeated)
            val falseResidual =
                output.intersect(phantom).size.toDouble() / phantom.size
            val recall = output.intersect(expected).size.toDouble() / expected.size
            val retention = output.intersect(protected).size.toDouble() / protected.size
            assertTrue(
                candidate,
                falseThickness(output, phantom) <= gates.double("p95ThicknessOverVoxelMax"),
            )
            candidate to Triple(falseResidual, recall, retention)
        }

        assertEquals(0.0, measurements.getValue("A").first, 0.000001)
        assertEquals(0.0, measurements.getValue("B").first, 0.000001)
        assertTrue(
            measurements.getValue("C").first > gates.double("falseSheetResidualMax"),
        )
        measurements.values.forEach { (_, recall, retention) ->
            assertTrue((recall - baseline) * 100.0 >= gates.double("recallDeltaPpMin"))
            assertTrue(retention >= gates.double("faceRetentionMin"))
        }
        assertEquals(12_800_000, 64 * 100_000 + 24 * 200_000 + 16 * 100_000)
        val comparisons = gates.int("affectedSurfaceCount") * gates.int("pictureCount")
        assertTrue((comparisons + 499) / 500 < gates.int("replayP95MillisecondsMaxExclusive"))
        assertTrue((comparisons + 199) / 200 <= gates.int("replayMaximumMilliseconds"))
    }

    @Test
    fun `synthetic population reaches the exact bounded association seam`() {
        val observations = sequence {
            repeat(200_000) { index ->
                yield(
                    M0VoxelObservation(
                        x = index % 100_000,
                        y = 0,
                        z = 0,
                        signedWeight = 1,
                        supportId = index,
                    ),
                )
            }
        }.asIterable()
        val result = M0SignedOccupancyKernel(
            capacity = 100_000,
            maxObservations = 200_000,
        ).fuse(observations)
        assertEquals(0, result.overflowObservationCount)
        assertEquals(100_000, result.surfaces.size)

        val overBudget = sequence {
            repeat(200_001) { index ->
                yield(M0VoxelObservation(index % 100_000, 0, 1, 1, index))
            }
        }.asIterable()
        assertEquals(
            1,
            M0SignedOccupancyKernel(maxObservations = 200_000)
                .fuse(overBudget)
                .overflowObservationCount,
        )
    }

    private fun expandedKeys(surfaces: List<M0CanonicalSurface>): Set<M0VoxelKey> =
        buildSet {
            surfaces.forEach { surface ->
                repeat(surface.extentU) { u ->
                    repeat(surface.extentV) { v ->
                        add(
                            when (surface.planeAxis) {
                                2 -> M0VoxelKey(surface.key.x + u, surface.key.y + v, surface.key.z)
                                1 -> M0VoxelKey(surface.key.x + u, surface.key.y, surface.key.z + v)
                                0 -> M0VoxelKey(surface.key.x, surface.key.y + u, surface.key.z + v)
                                else -> surface.key
                            },
                        )
                    }
                }
            }
        }

    private fun keys(value: kotlinx.serialization.json.JsonElement): List<M0VoxelKey> =
        value.jsonArray.map { row ->
            val key = row.jsonArray.map { it.jsonPrimitive.int }
            M0VoxelKey(key[0], key[1], key[2])
        }

    private fun falseThickness(
        output: Set<M0VoxelKey>,
        phantom: Set<M0VoxelKey>,
    ): Int = output.intersect(phantom)
        .groupBy { it.x to it.y }
        .values
        .maxOfOrNull { column ->
            val values = column.map { it.z }.sorted()
            var longest = if (values.isEmpty()) 0 else 1
            var current = longest
            for (index in 1 until values.size) {
                if (values[index] == values[index - 1] + 1) {
                    current++
                    longest = maxOf(longest, current)
                } else {
                    current = 1
                }
            }
            longest
        } ?: 0

    private fun fixture(): JsonObject =
        Json.parseToJsonElement(
            requireNotNull(javaClass.classLoader?.getResourceAsStream("m0b_quality_corpus_v1.json"))
                .bufferedReader()
                .use { it.readText() },
        ).jsonObject

    private fun JsonObject.int(key: String): Int = getValue(key).jsonPrimitive.int

    private fun JsonObject.double(key: String): Double = getValue(key).jsonPrimitive.double
}
