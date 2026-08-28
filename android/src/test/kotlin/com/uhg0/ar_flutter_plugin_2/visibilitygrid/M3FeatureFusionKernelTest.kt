package com.uhg0.ar_flutter_plugin_2.visibilitygrid

import java.security.MessageDigest
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

class M3FeatureFusionKernelTest {
    @Test
    fun `candidate A bytes match the immutable fusion lock and reject B and C`() {
        val root = fixture("m0b_fusion_vector_v1.json")
        val observations = root.getValue("observations").jsonArray.map(::fusionEvidence)
        val actual = canonical(accepted(kernel(), batch(1, observations)).candidates)
        val expected = root.getValue("expected").jsonObject
        val candidateA = canonicalExpected(expected.getValue("A").jsonArray)
        val candidateB = canonicalExpected(expected.getValue("B").jsonArray)
        val candidateC = canonicalExpected(expected.getValue("C").jsonArray)

        assertArrayEquals(candidateA, actual)
        assertFalse("Candidate B consolidation must remain rejected", candidateB.contentEquals(actual))
        assertFalse("Candidate C averaging must remain rejected", candidateC.contentEquals(actual))
        assertEquals("957ad2856b197eaad44e42db72a444cc9b6c516fa746a225329aaada0fb8ac85", sha256(resourceBytes("m0b_fusion_vector_v1.json")))
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
            val first = accepted(kernel(), batch(1, observations)).candidates
            val second = accepted(kernel(), batch(1, observations.reversed())).candidates
            assertArrayEquals(scene.string("name"), canonical(first), canonical(second))
            assertEquals(scene.string("name"), expectedKeys, first.map { "${it.x},${it.y},${it.z}" }.sorted())
        }
    }

    @Test
    fun `allocation failures before and after preflight preserve prior receipt and candidates`() {
        M3AllocationCut.entries.forEach { cut ->
            val operations = FaultOperations(allocationCut = cut)
            val kernel = M3FeatureFusionKernel(operations)
            val prior = accepted(kernel, batch(1, listOf(evidence(0, 0, 0, 2, 1))))
            operations.armed = true
            val refusal = kernel.accept(batch(2, listOf(evidence(1, 0, 0, 2, 2)))) as M3FeatureFusionResult.Refused
            assertEquals(cut.name, M3FeatureFusionRefusal.ALLOCATION, refusal.reason)
            assertEquals(cut.name, prior.receipt, refusal.receipt)

            val later = accepted(kernel, batch(2, emptyList()))
            assertEquals(cut.name, prior.candidates, later.candidates)
            assertEquals(cut.name, prior.receipt, later.receipt)
        }
    }

    @Test
    fun `checked arithmetic refusal preserves state and a later batch is accepted`() {
        val operations = FaultOperations(arithmeticFailure = true)
        val kernel = M3FeatureFusionKernel(operations)
        val prior = accepted(kernel, batch(1, listOf(evidence(0, 0, 0, 2, 1))))
        operations.armed = true
        val refusal = kernel.accept(batch(2, listOf(evidence(1, 0, 0, 2, 2)))) as M3FeatureFusionResult.Refused
        assertEquals(M3FeatureFusionRefusal.CHECKED_ARITHMETIC, refusal.reason)
        assertEquals(prior.receipt, refusal.receipt)
        val later = accepted(kernel, batch(2, emptyList()))
        assertEquals(prior.candidates, later.candidates)
        assertEquals(prior.receipt, later.receipt)
    }

    @Test
    fun `surface capacity at one hundred thousand refuses while association capacity remains`() {
        val kernel = kernel()
        val surfaces = List(100_000) { index -> evidence(index, 0, 0, 2, index) }
        val full = accepted(kernel, batch(1, surfaces))
        assertEquals(100_000, full.receipt.surfaceCount)
        assertEquals(100_000, full.receipt.associationCount)

        val refusal = kernel.accept(batch(2, listOf(evidence(100_000, 0, 0, 2, 100_000)))) as M3FeatureFusionResult.Refused
        assertEquals(M3FeatureFusionRefusal.SURFACE_CAPACITY, refusal.reason)
        assertEquals(full.receipt, refusal.receipt)

        val later = accepted(kernel, batch(2, listOf(evidence(0, 0, 0, 1, 100_001))))
        assertEquals(100_000, later.receipt.surfaceCount)
        assertEquals(100_001, later.receipt.associationCount)
        assertEquals(3, later.candidates.first { it.x == 0 }.weight)
    }

    @Test
    fun `constructed retained kernel graph fits its assigned M3 tuple share`() {
        val kernel = kernel()
        val outcome = accepted(kernel, batch(1, emptyList()))
        // Boundary: every object strongly reachable from the constructed kernel,
        // including the kernel/adapter/array headers and alignment. The accepted
        // result and batch are excluded because they are returned/transient, not
        // retained kernel state. GraphLayout measures the active JVM object model.
        val layout = GraphLayout.parseInstance(kernel)
        val retainedBytes = layout.totalSize()
        val primitivePayloadBytes = 5_548_576L
        val overheadBytes = retainedBytes - primitivePayloadBytes
        println("M3_RETAINED_ALLOCATION_RECEIPT retainedBytes=$retainedBytes primitivePayloadBytes=$primitivePayloadBytes objectAndArrayOverheadBytes=$overheadBytes assignedTupleShareBytes=${outcome.receipt.assignedTupleShareBytes}")
        assertTrue("JVM graph measurement must include headers/alignment", overheadBytes > 0)
        assertTrue(retainedBytes <= outcome.receipt.assignedTupleShareBytes)
        assertEquals(16 * 1024 * 1024, outcome.receipt.assignedTupleShareBytes)
    }

    private fun kernel() = M3FeatureFusionKernel()
    private fun accepted(kernel: M3FeatureFusionKernel, batch: M3FeatureFusionBatch) =
        kernel.accept(batch) as M3FeatureFusionResult.Accepted
    private fun batch(sequence: Long, observations: List<M3FeatureFusionEvidence>) = M3FeatureFusionBatch(sequence, sequence, observations)
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

    private fun fixture(name: String): JsonObject = Json.parseToJsonElement(resourceBytes(name).decodeToString()).jsonObject
    private fun resourceBytes(name: String): ByteArray = requireNotNull(javaClass.classLoader?.getResourceAsStream(name)).readBytes()
    private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private fun JsonObject.int(key: String) = getValue(key).jsonPrimitive.int
    private fun JsonObject.string(key: String) = getValue(key).jsonPrimitive.content

    private class FaultOperations(
        private val allocationCut: M3AllocationCut? = null,
        private val arithmeticFailure: Boolean = false,
    ) : M3KernelOperations {
        var armed = false
        override fun <T> allocate(cut: M3AllocationCut, block: () -> T): T {
            if (armed && cut == allocationCut) { armed = false; throw M3AllocationFailure() }
            return block()
        }
        override fun addExact(left: Int, right: Int): Int {
            if (armed && arithmeticFailure) { armed = false; throw ArithmeticException("injected") }
            return Math.addExact(left, right)
        }
    }
}
