package com.uhg0.ar_flutter_plugin_2.visibilitygrid

import com.uhg0.ar_flutter_plugin_2.m0.M0SignedOccupancyKernel
import com.uhg0.ar_flutter_plugin_2.m0.M0VoxelObservation
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class M3FeatureFusionKernelTest {
    @Test
    fun `selected candidate A matches the locked feature vector without exposing durable identity`() {
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
        val expected = M0SignedOccupancyKernel().fuse(observations).surfaces
        val outcome = kernel().accept(batch(1, 1, observations)) as M3FeatureFusionResult.Accepted

        assertEquals(
            expected.map { listOf(it.key.x, it.key.y, it.key.z, it.weight, it.normalOctant, it.observationCount) },
            outcome.candidates.map { listOf(it.x, it.y, it.z, it.weight, it.normalOctant, it.observationCount) },
        )
        assertTrue(outcome.candidates.none { it.toString().contains("surfaceId") })
    }

    @Test
    fun `hysteresis uses the locked activation and deactivation thresholds`() {
        val kernel = kernel()
        assertEquals(1, accepted(kernel, batch(1, 1, listOf(voxel(0, 0, 0, 2, 1)))).candidates.size)
        assertEquals(1, accepted(kernel, batch(2, 2, listOf(voxel(0, 0, 0, -1, 2)))).candidates.size)
        assertTrue(accepted(kernel, batch(3, 3, listOf(voxel(0, 0, 0, -1, 3)))).candidates.isEmpty())
    }

    @Test
    fun `invalid nonfinite and stale batches refuse without mutating accepted state`() {
        val kernel = kernel()
        val accepted = accepted(kernel, batch(1, 1, listOf(voxel(0, 0, 0, 2, 1))))

        val nonFinite = kernel.accept(
            M3FeatureFusionBatch(2, 2, listOf(M3FeatureFusionEvidence(Double.NaN, 0.0, 0.0, 1, 2))),
        ) as M3FeatureFusionResult.Refused
        assertEquals(M3FeatureFusionRefusal.NON_FINITE_COORDINATE, nonFinite.reason)
        assertEquals(accepted.receipt, nonFinite.receipt)

        val invalidWeight = kernel.accept(batch(2, 2, listOf(voxel(0, 0, 0, 128, 2)))) as M3FeatureFusionResult.Refused
        assertEquals(M3FeatureFusionRefusal.INVALID_EVIDENCE_WEIGHT, invalidWeight.reason)
        assertEquals(accepted.receipt, invalidWeight.receipt)

        val stale = kernel.accept(batch(1, 3, listOf(voxel(0, 0, 0, 1, 3)))) as M3FeatureFusionResult.Refused
        assertEquals(M3FeatureFusionRefusal.STALE_BATCH, stale.reason)
        assertEquals(accepted.receipt, stale.receipt)
    }

    @Test
    fun `exact one hundred thousand surfaces and two hundred thousand associations preflight atomically`() {
        val kernel = kernel()
        val surfaces = List(100_000) { index -> voxel(index, 0, 0, 2, index) }
        val first = accepted(kernel, batch(1, 1, surfaces))
        assertEquals(100_000, first.receipt.surfaceCount)
        assertEquals(100_000, first.receipt.associationCount)

        val second = accepted(kernel, batch(2, 2, surfaces.mapIndexed { index, value -> value.copy(supportId = index + 100_000) }))
        assertEquals(100_000, second.receipt.surfaceCount)
        assertEquals(200_000, second.receipt.associationCount)

        val refused = kernel.accept(batch(3, 3, listOf(voxel(100_000, 0, 0, 2, 300_000)))) as M3FeatureFusionResult.Refused
        assertEquals(M3FeatureFusionRefusal.ASSOCIATION_CAPACITY, refused.reason)
        assertEquals(second.receipt, refused.receipt)
    }

    @Test
    fun `retained primitive allocation is counted against the assigned M3 tuple share`() {
        val receipt = accepted(kernel(), batch(1, 1, emptyList())).receipt
        assertEquals(5_548_576, receipt.retainedPrimitiveBytes)
        assertTrue(receipt.retainedPrimitiveBytes <= receipt.assignedTupleShareBytes)
        assertEquals(16 * 1024 * 1024, receipt.assignedTupleShareBytes)
    }

    private fun kernel() = M3FeatureFusionKernel()

    private fun accepted(
        kernel: M3FeatureFusionKernel,
        batch: M3FeatureFusionBatch,
    ): M3FeatureFusionResult.Accepted = kernel.accept(batch) as M3FeatureFusionResult.Accepted

    private fun batch(
        sequence: Long,
        timestampNs: Long,
        observations: List<M0VoxelObservation>,
    ) = M3FeatureFusionBatch(
        sequence,
        timestampNs,
        observations.map { observation ->
            M3FeatureFusionEvidence(
                xMeters = observation.x * 0.1 + 0.02,
                yMeters = observation.y * 0.1 + 0.02,
                zMeters = observation.z * 0.1 + 0.02,
                signedWeight = observation.signedWeight,
                supportId = observation.supportId,
            )
        },
    )

    private fun voxel(x: Int, y: Int, z: Int, weight: Int, supportId: Int) =
        M0VoxelObservation(x, y, z, weight, supportId)

    private fun fixture() = Json.parseToJsonElement(
        requireNotNull(javaClass.classLoader?.getResourceAsStream("m0b_fusion_vector_v1.json"))
            .bufferedReader()
            .use { it.readText() },
    ).jsonObject

    private fun kotlinx.serialization.json.JsonObject.int(key: String): Int =
        getValue(key).jsonPrimitive.int
}
