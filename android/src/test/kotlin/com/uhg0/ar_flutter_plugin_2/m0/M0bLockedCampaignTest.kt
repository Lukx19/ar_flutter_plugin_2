package com.uhg0.ar_flutter_plugin_2.m0

import java.io.File
import java.security.MessageDigest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

class M0bLockedCampaignTest {
    @Test
    fun `compact candidate boundary rejects invalid populations`() {
        assertThrows(IllegalArgumentException::class.java) { M0bCompactFusionLedger(0, 1) }
        assertThrows(IllegalArgumentException::class.java) { M0bCompactFusionLedger(100_001, 1) }
        assertThrows(IllegalArgumentException::class.java) { M0bCompactFusionLedger(1, 0) }
        assertThrows(IllegalArgumentException::class.java) { M0bCompactFusionLedger(1, 200_001) }
        assertThrows(IllegalArgumentException::class.java) {
            M0bCompactFusionLedger(1, 1).executeCandidate("D")
        }
    }

    @Test
    fun `locked T0 through T3 campaign produces a native receipt`() {
        val manifest = fixture("m0b_campaign_manifest_v1.json")
        val oracle = fixture("m0b_reference_oracle_v1.json")
        val expectedHashes = fixture("m0b_crosslang_lock_v1.json")
            .getValue("candidateOutputSha256").jsonObject
        val scenes = mutableListOf<Scene>()
        val comparisonScenes = mutableListOf<Scene>()
        var comparisonSceneCount = 0
        manifest.getValue("partitions").jsonArray.forEach { raw ->
            val descriptor = raw.jsonObject
            val fileName = descriptor.string("file")
            val bytes = resourceBytes(fileName)
            assertEquals(descriptor.string("sha256"), sha256(bytes))
            if (descriptor.string("stage") == "proposal07Comparison") {
                fixture(fileName).getValue("scenes").jsonArray.forEach { rawScene ->
                    val input = rawScene.jsonObject
                    val parsedScene = scene(input, input.getValue("reference").jsonObject)
                    scenes += parsedScene
                    comparisonScenes += parsedScene
                    comparisonSceneCount++
                }
            } else {
                fixture(fileName).getValue("scenes").jsonArray.forEach { scene ->
                    scenes += scene(scene.jsonObject, oracle)
                }
            }
        }
        assertTrue("Proposal-07 comparison partition must execute", comparisonSceneCount > 0)
        val factories = linkedMapOf<String, () -> M0FusionKernel>(
            "A" to { M0SignedOccupancyKernel() },
            "B" to { M0PlanarConsolidationKernel() },
            "C" to { M0BoundedTsdfKernel() },
        )
        factories.forEach { (candidate, factory) ->
            val canonical = JsonArray(scenes.map { scene ->
                val first = factory().fuse(scene.observations)
                val reverse = factory().fuse(scene.observations.reversed())
                assertEquals(first, reverse)
                buildJsonObject {
                    put("scene", scene.name)
                    put("candidate", candidate)
                    put("result", canonical(first).toString())
                }
            }).toString()
            assertEquals(
                expectedHashes.getValue(candidate).jsonPrimitive.content,
                sha256(canonical.encodeToByteArray()),
            )
        }

        val baselineRecall = proposal07Recall(comparisonScenes)
        assertEquals(0.8, baselineRecall, 0.0)
        val receipt = measuredReceipt(factories, manifest, comparisonScenes, baselineRecall)
        val report = File("build/reports/tests/m0b_kotlin_receipt_v1.json")
        requireNotNull(report.parentFile).mkdirs()
        report.writeText(receipt)
        val parsed = Json.parseToJsonElement(receipt).jsonObject
        assertEquals(12_800_000, parsed.getValue("resource").jsonObject.int("semanticBytes"))
        parsed.getValue("candidates").jsonObject.values.forEach { raw ->
            val candidate = raw.jsonObject
            assertTrue(candidate.int("replayP95Micros") < 500_000)
            assertTrue(candidate.int("replayMaximumMicros") <= 5_000_000)
            assertTrue(candidate.int("cpuWindowP95Micros") <= 250_000)
            assertTrue(candidate.int("allocationBytes") <= 16_777_216)
            assertEquals(0, candidate.int("checkedOverflowFailures"))
            assertEquals(1, candidate.int("capacityOverflowCount"))
            assertEquals(1, candidate.int("lineageOverflowCount"))
            assertEquals(100_000, candidate.int("surfaceCount"))
            assertEquals(200_000, candidate.int("associationCount"))
            assertEquals(12_800_000, candidate.int("semanticBytes"))
        }
        assertEquals(
            3,
            parsed.getValue("candidates").jsonObject.values
                .map { it.jsonObject.getValue("fusionChecksum").jsonPrimitive.content }
                .toSet().size,
        )
    }

    private fun measuredReceipt(
        factories: Map<String, () -> M0FusionKernel>,
        manifest: JsonObject,
        comparisonScenes: List<Scene>,
        baselineRecall: Double,
    ): String {
        enableThreadAllocationMeasurement()
        val candidateJson = buildJsonObject {
            factories.forEach { (candidate, _) ->
                repeat(2) { executeBoundary(candidate) }
                val cpuRuns = MutableList(5) { executeBoundary(candidate) }
                val cpu = cpuRuns.map { it.first }.sorted()
                val replayRuns = MutableList(5) { executeBoundary(candidate) }
                val replay = replayRuns.map { it.first }.sorted()
                val boundary = replayRuns.first().second
                val candidateRecall = candidateRecall(factories.getValue(candidate), comparisonScenes)
                assertTrue(replayRuns.all { it.second.fusionChecksum == boundary.fusionChecksum })
                assertTrue(replayRuns.all { it.second.replayChecksum == boundary.replayChecksum })
                val allocations = MutableList(5) {
                    val before = currentThreadAllocatedBytes()
                    val ledger = M0bCompactFusionLedger()
                    val result = ledger.executeCandidate(candidate)
                    assertEquals(candidate, result.candidateId)
                    currentThreadAllocatedBytes() - before
                }.sorted()
                put(candidate, buildJsonObject {
                    put("replaySamplesMicros", longArray(replay))
                    put("replayP95Micros", replay[4].coerceAtLeast(1L))
                    put("replayMaximumMicros", replay.max().coerceAtLeast(1L))
                    put("replayImageBytes", 0)
                    put("cpuWindowSamplesMicros", longArray(cpu))
                    put("cpuWindowP95Micros", cpu[4].coerceAtLeast(1L))
                    put("allocationSamplesBytes", longArray(allocations))
                    put("allocationBytes", allocations[4])
                    put("surfaceCount", boundary.surfaceCount)
                    put("associationCount", boundary.associationCount)
                    put("semanticBytes", boundary.semanticBytes)
                    put("fusionChecksum", boundary.fusionChecksum)
                    put("replayChecksum", boundary.replayChecksum)
                    put("checkedOverflowFailures", boundary.checkedOverflowFailures)
                    put("capacityOverflowCount", boundary.capacityOverflowCount)
                    put("lineageOverflowCount", boundary.lineageOverflowCount)
                    put("proposal07BaselineRecall", baselineRecall)
                    put("proposal07CandidateRecall", candidateRecall)
                })
            }
        }
        return buildJsonObject {
            put("format", "proposal08-m0b-kotlin-receipt-v1")
            val pins = fixture("m0b_source_pins_v1.json")
            put("source", pins)
            put("inputs", inputHashes(manifest))
            put("runner", buildJsonObject {
                put("name", "kotlin-jvm-native-kernel")
                put("jvm", System.getProperty("java.version"))
                put("osName", System.getProperty("os.name"))
                put("osVersion", System.getProperty("os.version"))
                put("osArch", System.getProperty("os.arch"))
                put("warmupWindows", 2)
                put("measuredWindows", 5)
                put("clock", "ThreadMXBean.currentThreadCpuTime")
                put("allocation", "ThreadMXBean.currentThreadAllocatedBytes")
            })
            put("resource", buildJsonObject {
                put("surfaceCount", 100_000)
                put("associationCount", 200_000)
                put("semanticBytes", 12_800_000)
                put("serializedBytes", 12_800_000)
                put("fixedCapacity", true)
            })
            put("replay", buildJsonObject {
                put("pictureCount", 300)
                put("affectedSurfaceCount", 200)
                put("imageBytes", 0)
                put("semanticComparisons", 60_000)
            })
            put("candidates", candidateJson)
        }.toString()
    }

    private fun executeBoundary(candidate: String): Pair<Long, M0bBoundaryResult> {
        val before = currentThreadCpuTime()
        val result = M0bCompactFusionLedger().executeCandidate(candidate)
        return ((currentThreadCpuTime() - before) / 1_000).coerceAtLeast(1L) to result
    }

    private fun inputHashes(manifest: JsonObject): JsonObject = buildJsonObject {
        manifest.getValue("partitions").jsonArray.forEach { raw ->
            val descriptor = raw.jsonObject
            put(descriptor.string("stage"), descriptor.string("sha256"))
        }
        put("oracle", manifest.getValue("oracle").jsonObject.string("sha256"))
        listOf("m0b_crosslang_lock_v1.json", "m0b_guidance_vector_v1.json", "m0b_fusion_vector_v1.json")
            .forEach { name -> put(name, sha256(resourceBytes(name))) }
    }

    private fun proposal07Recall(scenes: List<Scene>): Double {
        val expected = scenes.sumOf { it.expected.size }
        val retained = scenes.sumOf { scene ->
            scene.observations.filter { it.signedWeight > 0 }.map { it.key() }.toSet()
                .intersect(scene.expected).size
        }
        return if (expected == 0) 1.0 else retained.toDouble() / expected
    }

    private fun candidateRecall(factory: () -> M0FusionKernel, scenes: List<Scene>): Double {
        val expected = scenes.sumOf { it.expected.size }
        val retained = scenes.sumOf { scene ->
            factory().fuse(scene.observations).surfaces.map { it.key }.toSet()
                .intersect(scene.expected).size
        }
        return if (expected == 0) 1.0 else retained.toDouble() / expected
    }

    private fun M0VoxelObservation.key(): M0VoxelKey = M0VoxelKey(x, y, z)

    private fun managementBean(): Any {
        val factory = Class.forName("java.lang.management.ManagementFactory")
        return requireNotNull(factory.getMethod("getThreadMXBean").invoke(null))
    }

    private fun enableThreadAllocationMeasurement() {
        val contract = Class.forName("com.sun.management.ThreadMXBean")
        contract.getMethod("setThreadAllocatedMemoryEnabled", Boolean::class.javaPrimitiveType)
            .invoke(managementBean(), true)
    }

    private fun currentThreadAllocatedBytes(): Long {
        val contract = Class.forName("com.sun.management.ThreadMXBean")
        return contract.getMethod("getThreadAllocatedBytes", Long::class.javaPrimitiveType)
            .invoke(managementBean(), Thread.currentThread().id) as Long
    }

    private fun currentThreadCpuTime(): Long {
        val contract = Class.forName("java.lang.management.ThreadMXBean")
        return contract.getMethod("getCurrentThreadCpuTime").invoke(managementBean()) as Long
    }

    private fun canonical(result: M0FusionResult): JsonObject = buildJsonObject {
        put("overflowObservationCount", result.overflowObservationCount)
        put("surfaces", JsonArray(result.surfaces.map { surface -> buildJsonObject {
            put("surfaceId", surface.surfaceId)
            put("key", JsonArray(listOf(surface.key.x, surface.key.y, surface.key.z).map(::number)))
            put("weight", surface.weight)
            put("normalOctant", surface.normalOctant)
            put("extentU", surface.extentU)
            put("extentV", surface.extentV)
            put("planeAxis", surface.planeAxis)
            put("lineageIds", JsonArray(surface.lineageIds.map(::number)))
            put("observationCount", surface.observationCount)
        }}))
    }

    private fun scene(input: JsonObject, oracle: JsonObject): Scene {
        val name = input.string("name")
        val truth = if (oracle.containsKey("scenes")) {
            oracle.getValue("scenes").jsonArray.first { it.jsonObject.string("name") == name }.jsonObject
        } else {
            oracle
        }
        assertTrue(truth.getValue("faces").jsonArray.isNotEmpty())
        val minimum = input.int("minimumConfidenceQ15")
        val expected = truth.getValue("expectedSurfaceKeys").jsonArray.map { raw ->
            val key = raw.jsonArray.map { it.jsonPrimitive.int }
            M0VoxelKey(key[0], key[1], key[2])
        }.toSet()
        return Scene(name, input.getValue("observations").jsonArray.mapNotNull { raw ->
            val row = raw.jsonObject
            if (row.int("confidenceQ15") < minimum) null else {
                val key = row.getValue("key").jsonArray.map { it.jsonPrimitive.int }
                M0VoxelObservation(key[0], key[1], key[2], row.int("signedWeight"), row.int("supportId"))
            }
        }, expected)
    }

    private data class Scene(
        val name: String,
        val observations: List<M0VoxelObservation>,
        val expected: Set<M0VoxelKey>,
    )
    private fun fixture(name: String): JsonObject = Json.parseToJsonElement(resourceBytes(name).decodeToString()).jsonObject
    private fun resourceBytes(name: String): ByteArray = requireNotNull(javaClass.classLoader?.getResourceAsStream(name)).readBytes()
    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private fun JsonObject.int(key: String): Int = getValue(key).jsonPrimitive.int
    private fun JsonObject.string(key: String): String = getValue(key).jsonPrimitive.content
    private fun number(value: Int): JsonElement = Json.parseToJsonElement(value.toString())
    private fun longArray(values: List<Long>): JsonArray =
        JsonArray(values.map { Json.parseToJsonElement(it.coerceAtLeast(1L).toString()) })
}
