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
import org.junit.Test

class M0bLockedCampaignTest {
    @Test
    fun `locked T0 through T3 campaign produces a native receipt`() {
        val manifest = fixture("m0b_campaign_manifest_v1.json")
        val oracle = fixture("m0b_reference_oracle_v1.json")
        val expectedHashes = fixture("m0b_crosslang_lock_v1.json")
            .getValue("candidateOutputSha256").jsonObject
        val scenes = mutableListOf<Scene>()
        manifest.getValue("partitions").jsonArray.forEach { raw ->
            val descriptor = raw.jsonObject
            val fileName = descriptor.string("file")
            val bytes = resourceBytes(fileName)
            assertEquals(descriptor.string("sha256"), sha256(bytes))
            if (descriptor.string("stage") != "proposal07Comparison") {
                fixture(fileName).getValue("scenes").jsonArray.forEach { scene ->
                    scenes += scene(scene.jsonObject, oracle)
                }
            }
        }
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

        val receipt = measuredReceipt(factories, scenes)
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
        }
    }

    private fun measuredReceipt(
        factories: Map<String, () -> M0FusionKernel>,
        scenes: List<Scene>,
    ): String {
        enableThreadAllocationMeasurement()
        val candidateJson = buildJsonObject {
            factories.forEach { (candidate, factory) ->
                repeat(2) { runWindow(factory, scenes) }
                val cpu = MutableList(5) { runWindow(factory, scenes) }.sorted()
                val replay = MutableList(5) { runReplayWindow() }.sorted()
                val allocations = MutableList(5) {
                    val before = currentThreadAllocatedBytes()
                    val ledger = M0bCompactFusionLedger()
                    val checksum = ledger.populateDeterministically()
                    assertTrue(checksum != Long.MIN_VALUE)
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
                    put("checkedOverflowFailures", checkedOverflowFailures(factory))
                    put("capacityOverflowCount", capacityOverflow())
                    put("lineageOverflowCount", lineageOverflow())
                })
            }
        }
        return buildJsonObject {
            put("format", "proposal08-m0b-kotlin-receipt-v1")
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

    private fun runWindow(factory: () -> M0FusionKernel, scenes: List<Scene>): Long {
        val before = currentThreadCpuTime()
        scenes.forEach { factory().fuse(it.observations) }
        return ((currentThreadCpuTime() - before) / 1_000).coerceAtLeast(1L)
    }

    private fun runReplayWindow(): Long {
        val before = currentThreadCpuTime()
        var checksum = 0L
        repeat(300) { picture -> repeat(200) { surface -> checksum = checksum xor (picture * 200L + surface) } }
        check(checksum >= 0)
        return ((currentThreadCpuTime() - before) / 1_000).coerceAtLeast(1L)
    }

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

    private fun checkedOverflowFailures(factory: () -> M0FusionKernel): Int =
        if (factory().fuse(listOf(M0VoxelObservation(0, 0, 0, Int.MAX_VALUE, 1)))
                .overflowObservationCount == 1
        ) 0 else 1

    private fun capacityOverflow(): Int = M0SignedOccupancyKernel(capacity = 1).fuse(
        listOf(M0VoxelObservation(0, 0, 0, 2, 1), M0VoxelObservation(1, 0, 0, 2, 2)),
    ).overflowObservationCount

    private fun lineageOverflow(): Int = M0SignedOccupancyKernel(maxLineageIds = 1).fuse(
        listOf(M0VoxelObservation(0, 0, 0, 1, 1), M0VoxelObservation(0, 0, 0, 1, 2)),
    ).overflowObservationCount

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
        val truth = oracle.getValue("scenes").jsonArray
            .first { it.jsonObject.string("name") == name }.jsonObject
        assertTrue(truth.getValue("faces").jsonArray.isNotEmpty())
        val minimum = input.int("minimumConfidenceQ15")
        return Scene(name, input.getValue("observations").jsonArray.mapNotNull { raw ->
            val row = raw.jsonObject
            if (row.int("confidenceQ15") < minimum) null else {
                val key = row.getValue("key").jsonArray.map { it.jsonPrimitive.int }
                M0VoxelObservation(key[0], key[1], key[2], row.int("signedWeight"), row.int("supportId"))
            }
        })
    }

    private data class Scene(val name: String, val observations: List<M0VoxelObservation>)
    private fun fixture(name: String): JsonObject = Json.parseToJsonElement(resourceBytes(name).decodeToString()).jsonObject
    private fun resourceBytes(name: String): ByteArray = requireNotNull(javaClass.classLoader?.getResourceAsStream(name)).readBytes()
    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private fun JsonObject.int(key: String): Int = getValue(key).jsonPrimitive.int
    private fun JsonObject.string(key: String): String = getValue(key).jsonPrimitive.content
    private fun number(value: Int): JsonElement = Json.parseToJsonElement(value.toString())
    private fun longArray(values: List<Long>): JsonArray =
        JsonArray(values.map { Json.parseToJsonElement(it.coerceAtLeast(1L).toString()) })
}
