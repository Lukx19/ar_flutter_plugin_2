package com.uhg0.ar_flutter_plugin_2.visibilitygrid

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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.openjdk.jol.info.GraphLayout

class FeatureFusionKernelTest {
    @Test
    fun `candidate A bytes match the immutable fusion lock and reject B and C`() {
        val root = fixture("m0b_fusion_vector_v1.json")
        val observations = root.getValue("observations").jsonArray.map(::fusionEvidence)
        val actual = canonical(upserts(accepted(kernel(), batch(1, observations))))
        val expected = root.getValue("expected").jsonObject
        val candidateA = canonicalExpected(expected.getValue("A").jsonArray)
        val candidateB = canonicalExpected(expected.getValue("B").jsonArray)
        val candidateC = canonicalExpected(expected.getValue("C").jsonArray)

        assertArrayEquals(candidateA, actual)
        assertFalse("Candidate B consolidation must remain rejected", candidateB.contentEquals(actual))
        assertFalse("Candidate C averaging must remain rejected", candidateC.contentEquals(actual))
        assertEquals("957ad2856b197eaad44e42db72a444cc9b6c516fa746a225329aaada0fb8ac85", testSha256Hex(resourceBytes("m0b_fusion_vector_v1.json")))
    }

    @Test
    fun `locked clean wall jitter signed boundary relocation and independent runs preserve oracle keys`() {
        val oracle = fixture("m0b_reference_oracle_v1.json")
        val selected = linkedSetOf("clean_wall", "jitter", "relocation", "negative_boundary")
        val scenes = listOf(
            "m0b_feature_depth_train_v1.json",
            "m0b_feature_depth_locked_v1.json",
            "m0b_feature_depth_held_out_v1.json",
        ).flatMap { fixture(it).getValue("scenes").jsonArray }.map { it.jsonObject }.filter { it.string("name") in selected }
        assertEquals(selected, scenes.mapTo(linkedSetOf()) { it.string("name") })

        scenes.forEach { scene ->
            val observations = scene.getValue("observations").jsonArray.mapNotNull { raw ->
                val row = raw.jsonObject
                if (row.int("confidenceQ15") < scene.int("minimumConfidenceQ15")) null else {
                    val key = row.getValue("key").jsonArray.map { it.jsonPrimitive.int }
                    evidence(key[0], key[1], key[2], row.int("signedWeight"), row.int("supportId"))
                }
            }
            val expectedKeys = oracle.getValue("scenes").jsonArray
                .first { it.jsonObject.string("name") == scene.string("name") }.jsonObject
                .getValue("expectedSurfaceKeys").jsonArray.map { key -> key.jsonArray.joinToString(",") { it.jsonPrimitive.content } }.sorted()
            val first = upserts(accepted(kernel(), batch(1, observations)))
            val second = upserts(accepted(kernel(), batch(1, observations.reversed())))
            assertArrayEquals(scene.string("name"), canonical(first), canonical(second))
            assertEquals(scene.string("name"), expectedKeys, first.map { "${it.x},${it.y},${it.z}" }.sorted())
        }
    }

    @Test
    fun `allocation failures before and after preflight preserve prior receipt and next delta`() {
        FeatureFusionAllocationCut.entries.forEach { cut ->
            val operations = FaultOperations(allocationCut = cut)
            val kernel = FeatureFusionKernel(operations)
            val prior = accepted(kernel, batch(1, listOf(evidence(0, 0, 0, 2, 1))))
            operations.armed = true
            val refusal = kernel.accept(batch(2, listOf(evidence(1, 0, 0, 2, 2)))) as FeatureFusionResult.Refused
            assertEquals(cut.name, FeatureFusionRefusal.ALLOCATION, refusal.reason)
            assertEquals(cut.name, prior.receipt, refusal.receipt)

            val later = accepted(kernel, batch(2, emptyList()))
            assertTrue(cut.name, later.delta.isEmpty())
            assertEquals(cut.name, prior.receipt, later.receipt)
        }
    }

    @Test
    fun `checked arithmetic refusal preserves state and a later batch is accepted`() {
        val operations = FaultOperations(arithmeticFailure = true)
        val kernel = FeatureFusionKernel(operations)
        val prior = accepted(kernel, batch(1, listOf(evidence(0, 0, 0, 2, 1))))
        operations.armed = true
        val refusal = kernel.accept(batch(2, listOf(evidence(1, 0, 0, 2, 2)))) as FeatureFusionResult.Refused
        assertEquals(FeatureFusionRefusal.CHECKED_ARITHMETIC, refusal.reason)
        assertEquals(prior.receipt, refusal.receipt)
        val later = accepted(kernel, batch(2, emptyList()))
        assertTrue(later.delta.isEmpty())
        assertEquals(prior.receipt, later.receipt)
    }

    @Test
    fun `surface capacity at one hundred thousand refuses while association capacity remains`() {
        val kernel = kernel()
        val surfaces = List(100_000) { index -> evidence(index, 0, 0, 2, index) }
        val full = accepted(kernel, batch(1, surfaces))
        assertEquals(100_000, full.receipt.surfaceCount)
        assertEquals(100_000, full.receipt.associationCount)

        val refusal = kernel.accept(batch(2, listOf(evidence(100_000, 0, 0, 2, 100_000)))) as FeatureFusionResult.Refused
        assertEquals(FeatureFusionRefusal.SURFACE_CAPACITY, refusal.reason)
        assertEquals(full.receipt, refusal.receipt)

        val retryEvidence = listOf(evidence(0, 0, 0, 1, 100_001)) +
            List(62) { index -> evidence(0, 0, 0, 0, 100_002 + index) }
        val later = accepted(kernel, batch(2, retryEvidence))
        assertEquals(100_000, later.receipt.surfaceCount)
        assertEquals(100_063, later.receipt.associationCount)
        assertTrue(upserts(later).isEmpty()) // 64 -> 128 stays in C12's reliable band.
    }

    @Test
    fun `association capacity at two hundred thousand refuses without truncation and permits retry`() {
        val kernel = kernel()
        val surfaces = List(100_000) { index -> evidence(index, 0, 0, 2, index) }
        accepted(kernel, batch(1, surfaces))
        val full = accepted(
            kernel,
            batch(2, surfaces.mapIndexed { index, value -> value.copy(supportId = index + 100_000) }),
        )
        assertEquals(100_000, full.receipt.surfaceCount)
        assertEquals(200_000, full.receipt.associationCount)

        val refusal = kernel.accept(batch(3, listOf(evidence(0, 0, 0, 1, 200_000)))) as FeatureFusionResult.Refused
        assertEquals(FeatureFusionRefusal.ASSOCIATION_CAPACITY, refusal.reason)
        assertEquals(full.receipt, refusal.receipt)

        val retry = accepted(kernel, batch(3, emptyList()))
        assertEquals(full.receipt, retry.receipt)
        assertTrue(retry.delta.isEmpty())
    }

    @Test
    fun `nonfinite invalid weight and stale refusals preserve state and sequence for retry`() {
        val kernel = kernel()
        var prior = accepted(kernel, batch(1, listOf(evidence(0, 0, 0, 2, 1))))

        val nonFiniteBatch = FeatureFusionBatch(
            2,
            2,
            listOf(FeatureFusionEvidence(Double.NaN, 0.0, 0.0, 1, 2)),
        )
        val nonFinite = kernel.accept(nonFiniteBatch) as FeatureFusionResult.Refused
        assertAtomicRefusal(FeatureFusionRefusal.NON_FINITE_COORDINATE, prior, nonFinite)
        var retry = accepted(kernel, batch(2, emptyList()))
        assertEquals(prior.receipt, retry.receipt)
        assertTrue(retry.delta.isEmpty())
        prior = retry

        val invalidWeight = kernel.accept(batch(3, listOf(evidence(1, 0, 0, 128, 3)))) as FeatureFusionResult.Refused
        assertAtomicRefusal(FeatureFusionRefusal.INVALID_EVIDENCE_WEIGHT, prior, invalidWeight)
        retry = accepted(kernel, batch(3, emptyList()))
        assertEquals(prior.receipt, retry.receipt)
        assertTrue(retry.delta.isEmpty())
        prior = retry

        val stale = kernel.accept(FeatureFusionBatch(4, 2, listOf(evidence(1, 0, 0, 2, 4)))) as FeatureFusionResult.Refused
        assertAtomicRefusal(FeatureFusionRefusal.STALE_BATCH, prior, stale)
        retry = accepted(kernel, FeatureFusionBatch(4, 4, emptyList()))
        assertEquals(prior.receipt, retry.receipt)
        assertTrue(retry.delta.isEmpty())
    }

    @Test
    fun `packed signed voxel boundaries and uint32 canonical ranges round trip exactly`() {
        val boundaries = listOf(
            intArrayOf(VOXEL_COORDINATE_MIN, VOXEL_COORDINATE_MIN, VOXEL_COORDINATE_MIN),
            intArrayOf(VOXEL_COORDINATE_MAX, VOXEL_COORDINATE_MAX, VOXEL_COORDINATE_MAX),
            intArrayOf(VOXEL_COORDINATE_MIN, 0, VOXEL_COORDINATE_MAX),
        )
        boundaries.forEach { expected ->
            assertArrayEquals(expected, unpackVisibilityGridKey(packVisibilityGridKey(expected[0], expected[1], expected[2])))
        }

        val kernel = kernel()
        val accepted = accepted(kernel, batch(1, listOf(evidence(0, 0, 0, 2, 1), evidence(1, 0, 0, 2, 2))))
        val changes = accepted.delta.map { it as FeatureFusionChange.Upsert }
        val firstFingerprint = M3CanonicalReceiptBytes(ByteArray(32) { 0x11 })
        val lastFingerprint = M3CanonicalReceiptBytes(ByteArray(32) { 0x7f })
        assertTrue(kernel.assignCanonicalCorrelations(listOf(changes[0].assignment(M3SurfaceId(1), firstFingerprint))))
        assertTrue(kernel.assignCanonicalCorrelations(listOf(changes[1].assignment(M3SurfaceId(0xffff_ffffL), lastFingerprint))))
        assertEquals(M3SurfaceId(1), kernel.canonicalCorrelation(changes[0].kernelSlot)?.id)
        assertEquals(firstFingerprint, kernel.canonicalCorrelation(changes[0].kernelSlot)?.allocationFingerprint)
        assertEquals(M3SurfaceId(0xffff_ffffL), kernel.canonicalCorrelation(changes[1].kernelSlot)?.id)
        assertEquals(lastFingerprint, kernel.canonicalCorrelation(changes[1].kernelSlot)?.allocationFingerprint)

        val before = kernel.canonicalCorrelation(changes[0].kernelSlot)
        assertFalse(kernel.assignCanonicalCorrelations(listOf(changes[0].assignment(M3SurfaceId(2), firstFingerprint))))
        assertEquals(before, kernel.canonicalCorrelation(changes[0].kernelSlot))
    }

    @Test
    fun `hydrated canonical row retains an identical observation without a material delta`() {
        val sample = evidence(7, 0, 0, 2, 1)
        val candidate = upserts(accepted(kernel(), batch(1, listOf(sample)))).single()
        val target = requireNotNull(candidate.primaryCanonicalTarget())
        val packed = ((target.normalOctX and 0xff) shl 8) or (target.normalOctY and 0xff)
        val recovered = kernel()
        assertTrue(recovered.hydrateCanonicalSurface(
            M3CompactSurface(M3SurfaceId(1), target.voxel, packed, target.normalConfidence),
            M3CanonicalReceiptBytes(ByteArray(32) { 3 }),
        ))
        assertTrue(accepted(recovered, batch(1, listOf(sample))).delta.isEmpty())
    }

    @Test
    fun `discarded prepared new and existing refinements retry without retained mutation`() {
        val kernel = kernel()
        val newBatch = batch(1, listOf(evidence(0, 0, 0, 2, 1)))
        val stagedNew = kernel.prepare(newBatch) as FeatureFusionResult.Accepted
        assertEquals(0, kernel.resourceReceipt().surfaceCount)
        assertEquals(0, kernel.resourceReceipt().associationCount)
        kernel.discardPrepared()

        val retriedNew = kernel.prepare(newBatch) as FeatureFusionResult.Accepted
        assertEquals(stagedNew.delta, retriedNew.delta)
        assertTrue(kernel.prepareCanonicalApplication(emptyList()))
        kernel.applyPrepared()
        assertEquals(1, kernel.resourceReceipt().surfaceCount)
        assertEquals(1, kernel.resourceReceipt().associationCount)

        val refinement = batch(2, listOf(evidence(0, 0, 0, 1, 2)))
        val stagedRefinement = kernel.prepare(refinement) as FeatureFusionResult.Accepted
        kernel.discardPrepared()
        assertEquals(1, kernel.resourceReceipt().associationCount)
        val retriedRefinement = kernel.prepare(refinement) as FeatureFusionResult.Accepted
        assertEquals(stagedRefinement.delta, retriedRefinement.delta)
        assertTrue(kernel.prepareCanonicalApplication(emptyList()))
        kernel.applyPrepared()
        assertEquals(2, kernel.resourceReceipt().associationCount)
    }

    @Test
    fun `hydrated canonical confidence retains every uint8 boundary exactly`() {
        val fingerprint = M3CanonicalReceiptBytes(ByteArray(32) { 0x4d })
        val target = requireNotNull(
            upserts(accepted(kernel(), batch(1, listOf(evidence(0, 0, 0, 2, 1))))).single().primaryCanonicalTarget(),
        )
        val packedNormal = ((target.normalOctX and 0xff) shl 8) or (target.normalOctY and 0xff)
        listOf(0, 1, 63, 64, 191, 192, 255).forEachIndexed { index, confidence ->
            val kernel = kernel()
            assertTrue(kernel.hydrateCanonicalSurface(
                M3CompactSurface(M3SurfaceId(1), M3Voxel(index, 0, 0), packedNormal, confidence),
                fingerprint,
            ))
            assertEquals(confidence, kernel.canonicalCorrelation(0)?.normalConfidence)
        }
    }

    @Test
    fun `constructed retained kernel graph fits its assigned feature-fusion memory share`() {
        val kernel = kernel()
        val outcome = accepted(kernel, batch(1, emptyList()))
        // Boundary: every object strongly reachable from the constructed kernel,
        // including the kernel/adapter/array headers and alignment. The accepted
        // result and batch are excluded because they are returned/transient, not
        // retained kernel state. GraphLayout measures the active JVM object model.
        val layout = GraphLayout.parseInstance(kernel)
        val retainedBytes = layout.totalSize()
        val primitivePayloadBytes = 7_589_536L
        val overheadBytes = retainedBytes - primitivePayloadBytes
        val implementationBytes = requireNotNull(
            javaClass.classLoader?.getResourceAsStream(
                "com/uhg0/ar_flutter_plugin_2/visibilitygrid/FeatureFusionKernel.class",
            ),
        ).readBytes()
        println("FEATURE_FUSION_RETAINED_ALLOCATION_RECEIPT implementationClassSha256=${testSha256Hex(implementationBytes)} retainedBytes=$retainedBytes primitivePayloadBytes=$primitivePayloadBytes objectAndArrayOverheadBytes=$overheadBytes assignedTupleShareBytes=${outcome.receipt.assignedTupleShareBytes}")
        assertTrue("JVM graph measurement must include headers/alignment", overheadBytes > 0)
        assertTrue(retainedBytes <= outcome.receipt.assignedTupleShareBytes)
        assertEquals(7_589_960, outcome.receipt.assignedTupleShareBytes)
    }

    private fun kernel() = FeatureFusionKernel()
    private fun accepted(kernel: FeatureFusionKernel, batch: FeatureFusionBatch) =
        kernel.accept(batch) as FeatureFusionResult.Accepted
    private fun upserts(result: FeatureFusionResult.Accepted) =
        result.delta.mapNotNull { (it as? FeatureFusionChange.Upsert)?.candidate }

    private fun FeatureFusionChange.Upsert.assignment(id: M3SurfaceId, fingerprint: M3CanonicalReceiptBytes) =
        CanonicalFeatureAssignment(kernelSlot, x, y, z, id, fingerprint)

    private fun assertAtomicRefusal(
        reason: FeatureFusionRefusal,
        prior: FeatureFusionResult.Accepted,
        refusal: FeatureFusionResult.Refused,
    ) {
        assertEquals(reason, refusal.reason)
        assertEquals(prior.receipt, refusal.receipt)
    }
    private fun batch(sequence: Long, observations: List<FeatureFusionEvidence>) = FeatureFusionBatch(sequence, sequence, observations)
    private fun evidence(x: Int, y: Int, z: Int, weight: Int, supportId: Int) =
        FeatureFusionEvidence(x * 0.1 + 0.02, y * 0.1 + 0.02, z * 0.1 + 0.02, weight, supportId,
            FeatureNormalEvidence(x, y, z, x * 100 + 20, y * 100 + 20, z * 100 + 20, x * 100 + 1_020, y * 100 + 20, z * 100 + 20, 32_767))

    private fun fusionEvidence(raw: kotlinx.serialization.json.JsonElement): FeatureFusionEvidence {
        val row = raw.jsonObject
        return evidence(row.int("x"), row.int("y"), row.int("z"), row.int("signedWeight"), row.int("supportId"))
    }

    private fun canonical(candidates: List<FeatureFusionCandidate>): ByteArray = JsonArray(candidates.map { candidate ->
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

    private fun fixture(name: String): JsonObject = Json.parseToJsonElement(resourceBytes(name).decodeToString()).jsonObject
    private fun resourceBytes(name: String): ByteArray = requireNotNull(javaClass.classLoader?.getResourceAsStream(name)).readBytes()
    private fun JsonObject.int(key: String) = getValue(key).jsonPrimitive.int
    private fun JsonObject.string(key: String) = getValue(key).jsonPrimitive.content

    private class FaultOperations(
        private val allocationCut: FeatureFusionAllocationCut? = null,
        private val arithmeticFailure: Boolean = false,
    ) : FeatureFusionOperations {
        var armed = false
        override fun <T> allocate(cut: FeatureFusionAllocationCut, block: () -> T): T {
            if (armed && cut == allocationCut) { armed = false; throw FeatureFusionAllocationFailure() }
            return block()
        }
        override fun addExact(left: Int, right: Int): Int {
            if (armed && arithmeticFailure) { armed = false; throw ArithmeticException("injected") }
            return Math.addExact(left, right)
        }
    }
}
