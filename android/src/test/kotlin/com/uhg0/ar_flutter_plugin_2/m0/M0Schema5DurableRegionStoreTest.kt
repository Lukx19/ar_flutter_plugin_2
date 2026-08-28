package com.uhg0.ar_flutter_plugin_2.m0

import java.nio.file.Files
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class M0Schema5DurableRegionStoreTest {
    @Test
    fun `receipt resumes exact retry after death before root switch`() {
        val directory = Files.createTempDirectory("m0c-receipt-").toFile()
        try {
            val old = listOf(cut(1, -1), cut(1, 0))
            val next = listOf(cut(2, -1), cut(2, 0))
            val store = M0Schema5DurableRegionCutStore(directory, old)
            val interrupted = store.publish(
                next,
                fault = M0DurableCutFaultPoint.afterReceipt,
                operationId = "checkpoint-7",
            )
            assertFalse(interrupted.published)
            assertEquals(0L, interrupted.visibleRootId)

            val restarted = M0Schema5DurableRegionCutStore(directory)
            val resumed = restarted.publish(next, operationId = "checkpoint-7")
            assertTrue(resumed.published)
            assertEquals(1L, resumed.visibleRootId)
            assertTrue(restarted.hasActivation(1))
            assertTrue(restarted.hasEviction(1))
            assertTrue(restarted.hasMigration(1))
            assertTrue(restarted.hasTombstone(1))

            val replay = restarted.publish(next, operationId = "checkpoint-7")
            assertEquals(1L, replay.visibleRootId)
            assertFalse(restarted.hasRoot(2))
            assertThrows(IllegalArgumentException::class.java) {
                restarted.publish(listOf(cut(3, -1), cut(3, 0)), operationId = "checkpoint-7")
            }
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `Kotlin durable root executes the shared twenty case fault matrix`() {
        val corpus = fixture("m0c_fault_corpus_v1.json")
        val faults = corpus.getValue("faults").jsonArray
        val root = Files.createTempDirectory("capture3d-m0c-kotlin-").toFile()
        try {
            faults.forEach { value ->
                val expected = value.jsonObject
                val name = expected.getValue("name").jsonPrimitive.content
                val directory = root.resolve(name)
                val old = listOf(cut(1, 0), cut(1, 1))
                val next = listOf(cut(2, 0), cut(2, 1))
                val store = M0Schema5DurableRegionCutStore(directory, old)
                val result = store.publish(next, M0DurableCutFaultPoint.valueOf(name))
                val restarted = M0Schema5DurableRegionCutStore(directory)

                assertEquals(name, expected.getValue("publishesNewRoot").jsonPrimitive.content.toBoolean(), result.published)
                assertEquals(name, setOf(expected.getValue("visibleGeneration").jsonPrimitive.int.toLong()), restarted.visibleCuts.values.map { it.generation }.toSet())
                assertFalse(restarted.hasStaging)
                fun reached(point: M0DurableCutFaultPoint) =
                    M0DurableCutFaultPoint.valueOf(name).ordinal >= point.ordinal
                assertEquals(name, reached(M0DurableCutFaultPoint.afterReceipt), restarted.hasReceipt(1))
                assertEquals(name, reached(M0DurableCutFaultPoint.afterActivation), restarted.hasActivation(1))
                assertEquals(name, reached(M0DurableCutFaultPoint.afterEviction), restarted.hasEviction(1))
                assertEquals(name, reached(M0DurableCutFaultPoint.afterMigration), restarted.hasMigration(1))
                assertEquals(name, reached(M0DurableCutFaultPoint.afterTombstone), restarted.hasTombstone(1))
                val orphanRoot = expected.getValue("orphanRoot").jsonPrimitive.content.toBoolean()
                if (orphanRoot) {
                    assertTrue(name, restarted.hasRoot(1))
                    assertEquals(0L, restarted.visibleRootId)
                } else if (!result.published) {
                    assertFalse(restarted.hasRoot(1))
                }
            }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `Kotlin pointer fallback keeps the prior root when the current slot is corrupt`() {
        val root = Files.createTempDirectory("capture3d-m0c-pointer-").toFile()
        try {
            val store = M0Schema5DurableRegionCutStore(root, listOf(cut(1, 0)))
            assertTrue(store.publish(listOf(cut(2, 0))).published)
            val current = root.resolve("root-B.ptr")
            val corrupted = current.readBytes()
            corrupted[252] = (corrupted[252].toInt() xor 1).toByte()
            current.writeBytes(corrupted)
            val recovered = M0Schema5DurableRegionCutStore(root)
            assertEquals(0L, recovered.visibleRootId)
            assertEquals(1L, recovered.visibleCuts.values.single().generation)
            assertTrue(recovered.hasRoot(1))
        } finally {
            root.deleteRecursively()
        }
    }

    private fun cut(generation: Long, coordinate: Int) = M0RegionPairCut(
        region = M0RegionCoordinate(coordinate, 0, 0),
        generation = generation,
        geometryRevision = generation,
        coverageRevision = generation,
        captureEvaluatedThrough = 4,
        pendingThrough = 2,
    )

    private fun fixture(name: String) = Json.parseToJsonElement(
        requireNotNull(javaClass.classLoader?.getResourceAsStream(name)).bufferedReader().use { it.readText() },
    ).jsonObject
}
