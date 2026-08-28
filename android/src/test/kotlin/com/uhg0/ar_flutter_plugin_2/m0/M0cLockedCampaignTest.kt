package com.uhg0.ar_flutter_plugin_2.m0

import java.io.File
import java.nio.file.Files
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class M0cLockedCampaignTest {
    @Test
    fun `locked T0 through T3 campaign produces a native receipt`() {
        val source = fixture("m0c_source_pins_v1.json")
        val policy = M0cResidencyPolicyCampaignV1.run()
        assertEquals("A", policy.selectedCandidateOrNone)

        val canonical = M0RegionShardV5.encodeCanonical(
            M0RegionCoordinate(-1, 2, -3),
            2,
            1,
            List(100_000) { row(19, it) },
            emptyList(),
        )
        val coverage = M0RegionShardV5.encodeCoverage(
            M0RegionCoordinate(-1, 2, -3),
            2,
            1,
            List(100_000) { row(56, it) },
            emptyList(),
        )
        assertEquals(100_000, M0RegionShardV5.decode(canonical).surfaceRows.size)
        assertEquals(100_000, M0RegionShardV5.decode(coverage).surfaceRows.size)

        val mutationBase = M0RegionShardV5.encodeCoverage(
            M0RegionCoordinate(1, 2, 3),
            2,
            1,
            listOf(row(56, 7)),
            listOf(row(13, 11)),
            M0RegionShardV5.Compression.ZLIB,
        )
        var mutationRejections = 0
        repeat(256) { seed ->
            val malformed = mutationBase.copyOf()
            val index = M0RegionShardV5.headerBytes + seed % (malformed.size - M0RegionShardV5.headerBytes)
            malformed[index] = (malformed[index].toInt() xor (1 shl (seed % 8))).toByte()
            if (runCatching { M0RegionShardV5.decode(malformed) }.isFailure) mutationRejections++
        }
        assertEquals(256, mutationRejections)

        var faultPasses = 0
        M0DurableCutFaultPoint.entries.forEach { fault ->
            val directory = Files.createTempDirectory("m0c-locked-fault-").toFile()
            try {
                val store = M0Schema5DurableRegionCutStore(directory, cuts(1))
                val result = store.publish(cuts(2), fault)
                assertEquals(fault.publishesNewRoot, result.published)
                assertEquals(if (fault.publishesNewRoot) 1L else 0L, result.visibleRootId)
                assertFalse(store.hasStaging)
                faultPasses++
            } finally {
                directory.deleteRecursively()
            }
        }

        val replayDirectory = Files.createTempDirectory("m0c-locked-replay-").toFile()
        val idempotentReplay = try {
            val store = M0Schema5DurableRegionCutStore(replayDirectory, cuts(1))
            store.publish(cuts(2), M0DurableCutFaultPoint.afterReceipt, "checkpoint-7")
            val restarted = M0Schema5DurableRegionCutStore(replayDirectory)
            restarted.publish(cuts(2), operationId = "checkpoint-7")
            restarted.publish(cuts(2), operationId = "checkpoint-7")
            restarted.visibleRootId == 1L && !restarted.hasRoot(2)
        } finally {
            replayDirectory.deleteRecursively()
        }
        assertTrue(idempotentReplay)

        val gb = 1_000_000_000L
        val quotaPolicy = M0StorageQuotaPolicy.forVolume(64 * gb)
        val quota = M0StorageBudgetCoordinator(quotaPolicy, 31 * gb, 33 * gb)
        val first = quota.tryReserve(600_000_000, "session-a/checkpoint")!!
        val second = quota.tryReserve(400_000_000, "session-b/picture")!!
        val crossSessionOvercommitRejected = quota.tryReserve(1, "session-c/trace") == null
        quota.commit(first, 500_000_000)
        quota.release(second)
        assertTrue(crossSessionOvercommitRejected)

        val receipt = buildJsonObject {
            put("format", "proposal08-m0c-kotlin-receipt-v1")
            put("source", source)
            put("lockedTiers", buildJsonArray {
                listOf("T0", "T1", "T2", "T3").forEach { add(JsonPrimitive(it)) }
            })
            put("pathLength", policy.pathLength)
            put("cumulativeOwnerCount", policy.cumulativeOwnerCount)
            put("cumulativePageCount", policy.cumulativePageCount)
            put("cumulativeSurfaceCount", policy.cumulativeSurfaceCount)
            put("reverseRevisitIdentity", policy.reverseRevisitIdentity)
            put("selectedCandidateOrNone", policy.selectedCandidateOrNone)
            put("candidates", buildJsonObject {
                policy.candidates.forEach { (id, value) ->
                    put(id, buildJsonObject {
                        put("maximumResidentRegions", value.maximumResidentRegions)
                        put("maximumQueueDepth", value.maximumQueueDepth)
                        put("maximumPrefetchRegions", value.maximumPrefetchRegions)
                        put("maximumOpenFiles", value.maximumOpenFiles)
                        put("maximumDirectoryBytes", value.maximumDirectoryBytes)
                        put("measuredOwnerCount", value.measuredOwnerCount)
                        put("measuredPageCount", value.measuredPageCount)
                        put("measuredSurfaceCount", value.measuredSurfaceCount)
                        put("stableRevisitIdentity", value.stableRevisitIdentity)
                        put("noPartialDemand", value.noPartialDemand)
                        put("noStarvation", value.noStarvation)
                        put("complexityScore", value.complexityScore)
                        put("gateFailures", buildJsonArray {
                            value.gateFailures.forEach { add(JsonPrimitive(it)) }
                        })
                    })
                }
            })
            put("shards", buildJsonObject {
                put("canonicalRows", 100_000)
                put("canonicalBytes", canonical.size)
                put("coverageRows", 100_000)
                put("coverageBytes", coverage.size)
                put("mutationCases", 256)
                put("mutationRejections", mutationRejections)
            })
            put("durability", buildJsonObject {
                put("faultCases", M0DurableCutFaultPoint.entries.size)
                put("faultPasses", faultPasses)
                put("idempotentReceiptReplay", idempotentReplay)
            })
            put("quota", buildJsonObject {
                put("volumeBytes", 64 * gb)
                put("globalQuotaBytes", quotaPolicy.globalQuotaBytes)
                put("freeSpaceFloorBytes", quotaPolicy.freeSpaceFloorBytes)
                put("crossSessionOvercommitRejected", crossSessionOvercommitRejected)
            })
        }.toString()
        val report = File("build/reports/tests/m0c_kotlin_receipt_v1.json")
        report.parentFile?.mkdirs()
        report.writeText(receipt)
        assertEquals(
            requireNotNull(javaClass.classLoader?.getResourceAsStream("m0c_kotlin_receipt_v1.json"))
                .bufferedReader().use { it.readText() }.trim(),
            receipt,
        )
    }

    private fun fixture(name: String) = Json.parseToJsonElement(
        requireNotNull(javaClass.classLoader?.getResourceAsStream(name)).bufferedReader().use { it.readText() },
    ).jsonObject

    private fun cuts(generation: Long) = listOf(
        cut(generation, -1),
        cut(generation, 0),
    )

    private fun cut(generation: Long, coordinate: Int) = M0RegionPairCut(
        M0RegionCoordinate(coordinate, 0, 0),
        generation,
        generation,
        generation,
        4,
        2,
    )

    private fun row(width: Int, seed: Int): ByteArray =
        ByteArray(width) { index -> (seed * 31 + index * 17).toByte() }
}
