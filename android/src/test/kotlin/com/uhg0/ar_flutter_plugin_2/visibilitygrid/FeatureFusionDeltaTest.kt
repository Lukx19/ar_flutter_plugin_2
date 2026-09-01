package com.uhg0.ar_flutter_plugin_2.visibilitygrid

import java.util.TreeMap
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.openjdk.jol.info.GraphLayout

class FeatureFusionDeltaTest {
    @Test
    fun `accumulated deltas reproduce the locked candidate A material projection`() {
        val root = fixture("m0b_fusion_vector_v1.json")
        val observations = root.getValue("observations").jsonArray.map(::fusionEvidence)
        val kernel = FeatureFusionKernel()
        val accumulator = DeltaAccumulator()

        accepted(kernel, batch(1, observations.take(observations.size / 2))).also(accumulator::apply)
        accepted(kernel, batch(2, observations.drop(observations.size / 2))).also(accumulator::apply)

        val expected = canonicalMaterialExpected(root.getValue("expected").jsonObject.getValue("A").jsonArray)
        assertArrayEquals(expected, canonicalMaterial(accumulator.candidates()))
    }

    @Test
    fun `activation refinement and inactive reobservation produce deterministic bounded deltas`() {
        val kernel = FeatureFusionKernel()
        val inactive = accepted(kernel, batch(1, listOf(evidence(4, 0, 0, 1, 1))))
        assertTrue(inactive.delta.isEmpty())
        assertEquals(1, inactive.work.distinctTouchedVoxelCount)
        assertEquals(0, inactive.work.emittedEventCount)

        val reobservedInactive = accepted(kernel, batch(2, listOf(evidence(4, 0, 0, 0, 2))))
        assertTrue(reobservedInactive.delta.isEmpty())
        assertEquals(1, reobservedInactive.work.distinctTouchedVoxelCount)
        assertEquals(0, reobservedInactive.work.emittedEventCount)

        val activated = accepted(kernel, batch(3, listOf(evidence(4, 0, 0, 1, 3))))
        assertEquals(listOf(4 to 2), upserts(activated).map { it.x to it.weight })

        val refinedWithinBand = accepted(kernel, batch(4, listOf(evidence(4, 0, 0, 1, 4))))
        assertTrue(refinedWithinBand.delta.isEmpty())

        val secondKernel = FeatureFusionKernel()
        val sorted = accepted(
            secondKernel,
            batch(1, listOf(evidence(5, 0, 0, 2, 1), evidence(-1, 0, 0, 2, 2))),
        )
        assertEquals(listOf(-1, 5), sorted.delta.map { it.x })
        assertEquals(2, sorted.work.distinctTouchedVoxelCount)
        assertEquals(2, sorted.work.emittedEventCount)
    }

    @Test
    fun `deactivation emits removal and accumulated delta matches exact active state`() {
        val kernel = FeatureFusionKernel()
        val accumulator = DeltaAccumulator()

        accepted(kernel, batch(1, listOf(evidence(7, 0, 0, 2, 1)))).also(accumulator::apply)
        assertEquals(listOf(7 to 2), accumulator.candidates().map { it.x to it.weight })

        val deactivated = accepted(kernel, batch(2, listOf(evidence(7, 0, 0, -2, 2))))
        assertEquals(listOf(FeatureFusionChange.Removal(7, 0, 0)), deactivated.delta)
        assertEquals(1, deactivated.work.distinctTouchedVoxelCount)
        assertEquals(1, deactivated.work.emittedEventCount)
        accumulator.apply(deactivated)
        assertTrue(accumulator.candidates().isEmpty())
    }

    @Test
    fun `active zero weight updates evidence without emitting a canonical change`() {
        val kernel = FeatureFusionKernel()
        accepted(kernel, batch(1, listOf(evidence(8, 0, 0, 2, 1))))

        val evidenceOnly = accepted(kernel, batch(2, listOf(evidence(8, 0, 0, 0, 2))))

        assertTrue(evidenceOnly.delta.isEmpty())
        assertEquals(1, evidenceOnly.work.distinctTouchedVoxelCount)
        assertEquals(0, evidenceOnly.work.emittedEventCount)
    }

    @Test
    fun `normal confidence threshold transitions publish only on C12 material bands`() {
        val kernel = FeatureFusionKernel()

        val zeroToOne = accepted(kernel, batch(1, listOf(evidence(9, 0, 0, 2, 1, confidenceQ15 = 512))))
        assertEquals(listOf(1), upserts(zeroToOne).flatMap { it.normalCandidates }.map { it.normalConfidence })

        val through63 = accepted(kernel, batch(2, List(62) { evidence(9, 0, 0, 2, it + 2, confidenceQ15 = 512) }))
        assertTrue(through63.delta.isEmpty())
        val sixtyFour = accepted(kernel, batch(3, listOf(evidence(9, 0, 0, 2, 64, confidenceQ15 = 512))))
        assertEquals(listOf(64), upserts(sixtyFour).flatMap { it.normalCandidates }.map { it.normalConfidence })

        val through191 = accepted(kernel, batch(4, List(127) { evidence(9, 0, 0, 2, it + 65, confidenceQ15 = 512) }))
        assertTrue(through191.delta.isEmpty())
        // Exact estimator formula: roundTiesEven(n * 128 * 255 / 32768).
        // Therefore n=192 remains confidence 191 and n=193 crosses to 192.
        val observation192 = accepted(kernel, batch(5, listOf(evidence(9, 0, 0, 2, 192, confidenceQ15 = 512))))
        assertTrue(observation192.delta.isEmpty())
        val observation193 = accepted(kernel, batch(6, listOf(evidence(9, 0, 0, 2, 193, confidenceQ15 = 512))))
        assertEquals(listOf(192), upserts(observation193).flatMap { it.normalCandidates }.map { it.normalConfidence })

        val throughAndPastSaturation = accepted(kernel, batch(7, List(63) { evidence(9, 0, 0, 2, it + 194, confidenceQ15 = 512) }))
        assertTrue(throughAndPastSaturation.delta.isEmpty())
    }

    @Test
    fun `one voxel refinement at one hundred thousand live surfaces returns no full result`() {
        val kernel = FeatureFusionKernel()
        val full = accepted(kernel, batch(1, List(100_000) { index -> evidence(index, 0, 0, 2, index) }))
        assertEquals(100_000, full.receipt.surfaceCount)
        assertEquals(100_000, full.receipt.associationCount)

        val withinBand = accepted(kernel, batch(2, List(62) { evidence(42, 0, 0, 0, 100_000 + it) }))
        assertTrue(withinBand.delta.isEmpty())
        val refinement = accepted(kernel, batch(3, listOf(evidence(42, 0, 0, 1, 100_062))))
        assertEquals(100_000, refinement.receipt.surfaceCount)
        assertEquals(100_063, refinement.receipt.associationCount)
        assertEquals(1, refinement.work.distinctTouchedVoxelCount)
        assertEquals(0, refinement.work.emittedEventCount)
        assertTrue(upserts(refinement).isEmpty())

        // The returned graph is bounded by the touched result, not the 100k
        // retained population. The kernel itself is intentionally excluded.
        val resultBytes = GraphLayout.parseInstance(refinement).totalSize()
        println(
            "M3_INCREMENTAL_DELTA_RECEIPT liveSurfaces=${refinement.receipt.surfaceCount} " +
                "associations=${refinement.receipt.associationCount} " +
                "touchedVoxels=${refinement.work.distinctTouchedVoxelCount} " +
                "emittedEvents=${refinement.work.emittedEventCount} resultBytes=$resultBytes",
        )
        assertTrue(resultBytes < 4_096L)
    }

    private fun accepted(kernel: FeatureFusionKernel, batch: FeatureFusionBatch) =
        kernel.accept(batch) as FeatureFusionResult.Accepted

    private fun upserts(result: FeatureFusionResult.Accepted) =
        result.delta.mapNotNull { (it as? FeatureFusionChange.Upsert)?.candidate }

    private fun batch(sequence: Long, observations: List<FeatureFusionEvidence>) =
        FeatureFusionBatch(sequence, sequence, observations)

    private fun evidence(x: Int, y: Int, z: Int, weight: Int, supportId: Int, confidenceQ15: Int = 32_767) =
        FeatureFusionEvidence(x * 0.1 + 0.02, y * 0.1 + 0.02, z * 0.1 + 0.02, weight, supportId,
            FeatureNormalEvidence(x, y, z, x * 100 + 20, y * 100 + 20, z * 100 + 20, x * 100 + 1_020, y * 100 + 20, z * 100 + 20, confidenceQ15))

    private fun fusionEvidence(raw: kotlinx.serialization.json.JsonElement): FeatureFusionEvidence {
        val row = raw.jsonObject
        return evidence(row.int("x"), row.int("y"), row.int("z"), row.int("signedWeight"), row.int("supportId"))
    }

    private fun canonicalMaterial(candidates: List<FeatureFusionCandidate>): ByteArray = JsonArray(candidates.map { candidate ->
        buildJsonObject {
            put("x", candidate.x); put("y", candidate.y); put("z", candidate.z)
            put("normalOctant", candidate.normalOctant); put("confidenceBand", confidenceBand(candidate.observationCount))
        }
    }).toString().encodeToByteArray()

    private fun canonicalMaterialExpected(rows: JsonArray): ByteArray = JsonArray(rows.map { raw ->
        val row = raw.jsonObject
        buildJsonObject {
            put("x", row.int("x")); put("y", row.int("y")); put("z", row.int("z"))
            put("normalOctant", row.int("normalOctant")); put("confidenceBand", confidenceBand(row.int("observationCount")))
        }
    }).toString().encodeToByteArray()

    private fun confidenceBand(observationCount: Int) = when (observationCount.coerceIn(0, 255)) {
        0 -> 0
        in 1 until 64 -> 1
        in 64 until 192 -> 64
        else -> 192
    }

    private fun fixture(name: String): JsonObject = Json.parseToJsonElement(
        requireNotNull(javaClass.classLoader?.getResourceAsStream(name)).readBytes().decodeToString(),
    ).jsonObject

    private fun JsonObject.int(key: String) = getValue(key).jsonPrimitive.int

    private class DeltaAccumulator {
        private val byKey = TreeMap<String, FeatureFusionCandidate>()

        fun apply(result: FeatureFusionResult.Accepted) {
            result.delta.forEach { change ->
                val key = "${change.x},${change.y},${change.z}"
                when (change) {
                    is FeatureFusionChange.Upsert -> byKey[key] = change.candidate
                    is FeatureFusionChange.Removal -> byKey.remove(key)
                }
            }
        }

        fun candidates(): List<FeatureFusionCandidate> = byKey.values.sortedWith(
            compareBy<FeatureFusionCandidate> { it.x }.thenBy { it.y }.thenBy { it.z },
        )
    }
}
