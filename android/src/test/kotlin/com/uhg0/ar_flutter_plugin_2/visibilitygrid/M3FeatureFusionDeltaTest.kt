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

class M3FeatureFusionDeltaTest {
    @Test
    fun `accumulated deltas reproduce the locked candidate A canonical bytes`() {
        val root = fixture("m0b_fusion_vector_v1.json")
        val observations = root.getValue("observations").jsonArray.map(::fusionEvidence)
        val kernel = M3FeatureFusionKernel()
        val accumulator = DeltaAccumulator()

        accepted(kernel, batch(1, observations.take(observations.size / 2))).also(accumulator::apply)
        accepted(kernel, batch(2, observations.drop(observations.size / 2))).also(accumulator::apply)

        val expected = canonicalExpected(root.getValue("expected").jsonObject.getValue("A").jsonArray)
        assertArrayEquals(expected, canonical(accumulator.candidates()))
    }

    @Test
    fun `activation refinement and inactive reobservation produce deterministic bounded deltas`() {
        val kernel = M3FeatureFusionKernel()
        val inactive = accepted(kernel, batch(1, listOf(evidence(4, 0, 0, 1, 1))))
        assertTrue(inactive.delta.isEmpty())
        assertEquals(1, inactive.work.distinctTouchedVoxelCount)
        assertEquals(0, inactive.work.returnedCandidateCount)

        val reobservedInactive = accepted(kernel, batch(2, listOf(evidence(4, 0, 0, 0, 2))))
        assertTrue(reobservedInactive.delta.isEmpty())
        assertEquals(1, reobservedInactive.work.distinctTouchedVoxelCount)
        assertEquals(0, reobservedInactive.work.returnedCandidateCount)

        val activated = accepted(kernel, batch(3, listOf(evidence(4, 0, 0, 1, 3))))
        assertEquals(listOf(M3FeatureFusionCandidate(4, 0, 0, 2, 4, 3)), activated.delta)

        val refined = accepted(kernel, batch(4, listOf(evidence(4, 0, 0, 1, 4))))
        assertEquals(listOf(M3FeatureFusionCandidate(4, 0, 0, 3, 4, 4)), refined.delta)

        val secondKernel = M3FeatureFusionKernel()
        val sorted = accepted(
            secondKernel,
            batch(1, listOf(evidence(5, 0, 0, 2, 1), evidence(-1, 0, 0, 2, 2))),
        )
        assertEquals(listOf(-1, 5), sorted.delta.map { it.x })
        assertEquals(2, sorted.work.distinctTouchedVoxelCount)
        assertEquals(2, sorted.work.returnedCandidateCount)
    }

    @Test
    fun `one voxel refinement at one hundred thousand live surfaces returns no full result`() {
        val kernel = M3FeatureFusionKernel()
        val full = accepted(kernel, batch(1, List(100_000) { index -> evidence(index, 0, 0, 2, index) }))
        assertEquals(100_000, full.receipt.surfaceCount)
        assertEquals(100_000, full.receipt.associationCount)

        val refinement = accepted(kernel, batch(2, listOf(evidence(42, 0, 0, 1, 100_000))))
        assertEquals(100_000, refinement.receipt.surfaceCount)
        assertEquals(100_001, refinement.receipt.associationCount)
        assertEquals(1, refinement.work.distinctTouchedVoxelCount)
        assertEquals(1, refinement.work.returnedCandidateCount)
        assertEquals(listOf(M3FeatureFusionCandidate(42, 0, 0, 3, 4, 2)), refinement.delta)

        // The returned graph is bounded by the touched result, not the 100k
        // retained population. The kernel itself is intentionally excluded.
        val resultBytes = GraphLayout.parseInstance(refinement).totalSize()
        println(
            "M3_INCREMENTAL_DELTA_RECEIPT liveSurfaces=${refinement.receipt.surfaceCount} " +
                "associations=${refinement.receipt.associationCount} " +
                "touchedVoxels=${refinement.work.distinctTouchedVoxelCount} " +
                "returnedCandidates=${refinement.work.returnedCandidateCount} resultBytes=$resultBytes",
        )
        assertTrue(resultBytes < 4_096L)
    }

    private fun accepted(kernel: M3FeatureFusionKernel, batch: M3FeatureFusionBatch) =
        kernel.accept(batch) as M3FeatureFusionResult.Accepted

    private fun batch(sequence: Long, observations: List<M3FeatureFusionEvidence>) =
        M3FeatureFusionBatch(sequence, sequence, observations)

    private fun evidence(x: Int, y: Int, z: Int, weight: Int, supportId: Int) =
        M3FeatureFusionEvidence(x * 0.1 + 0.02, y * 0.1 + 0.02, z * 0.1 + 0.02, weight, supportId)

    private fun fusionEvidence(raw: kotlinx.serialization.json.JsonElement): M3FeatureFusionEvidence {
        val row = raw.jsonObject
        return evidence(row.int("x"), row.int("y"), row.int("z"), row.int("signedWeight"), row.int("supportId"))
    }

    private fun canonical(candidates: List<M3FeatureFusionCandidate>): ByteArray = JsonArray(candidates.map { candidate ->
        buildJsonObject {
            put("x", candidate.x); put("y", candidate.y); put("z", candidate.z); put("weight", candidate.weight)
            put("normalOctant", candidate.normalOctant); put("observationCount", candidate.observationCount)
        }
    }).toString().encodeToByteArray()

    private fun canonicalExpected(rows: JsonArray): ByteArray = JsonArray(rows.map { raw ->
        val row = raw.jsonObject
        buildJsonObject {
            put("x", row.int("x")); put("y", row.int("y")); put("z", row.int("z")); put("weight", row.int("weight"))
            put("normalOctant", row.int("normalOctant")); put("observationCount", row.int("observationCount"))
        }
    }).toString().encodeToByteArray()

    private fun fixture(name: String): JsonObject = Json.parseToJsonElement(
        requireNotNull(javaClass.classLoader?.getResourceAsStream(name)).readBytes().decodeToString(),
    ).jsonObject

    private fun JsonObject.int(key: String) = getValue(key).jsonPrimitive.int

    private class DeltaAccumulator {
        private val byKey = TreeMap<String, M3FeatureFusionCandidate>()

        fun apply(result: M3FeatureFusionResult.Accepted) {
            result.delta.forEach { candidate -> byKey["${candidate.x},${candidate.y},${candidate.z}"] = candidate }
        }

        fun candidates(): List<M3FeatureFusionCandidate> = byKey.values.sortedWith(
            compareBy<M3FeatureFusionCandidate> { it.x }.thenBy { it.y }.thenBy { it.z },
        )
    }
}
