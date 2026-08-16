package com.uhg0.ar_flutter_plugin_2.m0

import java.security.MessageDigest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class M0dRendererCorpusTest {
    @Test
    fun `staged M0d renderer corpus is hash bound and complete`() {
        val manifest = fixture()
        val rendererCorpus = manifest.getValue("rendererCorpus").jsonObject
        val base = fixture(rendererCorpus.getValue("baseCorpus").jsonPrimitive.content)
        val baseCases = base.getValue("cases").jsonArray.associateBy {
            it.jsonObject.getValue("name").jsonPrimitive.content
        }
        val stageEntries = rendererCorpus.getValue("stageCorpora").jsonObject
        val names = mutableSetOf<String>()
        var caseCount = 0
        stageEntries.forEach { (stage, descriptorElement) ->
            val descriptor = descriptorElement.jsonObject
            val fileName = descriptor.getValue("file").jsonPrimitive.content
            val bytes = resourceBytes(fileName)
            assertEquals(descriptor.getValue("sha256").jsonPrimitive.content, sha256(bytes))
            val stageRoot = Json.parseToJsonElement(bytes.decodeToString()).jsonObject
            assertEquals(stage, stageRoot.getValue("stage").jsonPrimitive.content)
            assertEquals(
                rendererCorpus.getValue("status"),
                stageRoot.getValue("status"),
            )
            stageRoot.getValue("cases").jsonArray.forEach { value ->
                val name = value.jsonObject.getValue("name").jsonPrimitive.content
                assertTrue(names.add(name))
                assertEquals(baseCases.getValue(name), value)
                caseCount++
            }
        }
        assertEquals(rendererCorpus.int("expectedCaseCount"), caseCount)
        assertEquals(baseCases.keys, names)
    }

    @Test
    fun `staged M0d renderer cases pass the Android JVM reference gates`() {
        val manifest = fixture()
        val stages = manifest.getValue("rendererCorpus").jsonObject
            .getValue("stageCorpora").jsonObject
        stages.values.forEach { descriptorElement ->
            val fileName = descriptorElement.jsonObject.getValue("file").jsonPrimitive.content
            val stage = fixture(fileName)
            stage.getValue("cases").jsonArray.forEach { value -> runCase(value.jsonObject) }
        }
    }

    private fun runCase(testCase: JsonObject) {
        val kind = testCase.getValue("kind").jsonPrimitive.content
        val expected = testCase.getValue("expected").jsonObject
        when (kind) {
            "slot-reuse" -> {
                val renderer = M0CentroidRendererState(testCase.int("capacity"))
                assertTrue(renderer.upsert(row("g/s1", M0SemanticState.COVERED)))
                assertTrue(renderer.upsert(row("g/s2", M0SemanticState.PENDING)))
                renderer.flush()
                assertTrue(renderer.remove("g/s1"))
                assertTrue(renderer.upsert(row("g/s3", M0SemanticState.UNCOVERED)))
                assertEquals(expected.int("rowCount"), renderer.rowCount)
                assertEquals(expected.int("reusedSlot"), renderer.slotFor("g/s3"))
            }
            "accessibility" -> {
                val renderer = M0CentroidRendererState(testCase.int("capacity"))
                assertTrue(renderer.upsert(row("g/pending", M0SemanticState.PENDING)))
                assertEquals(expected.getValue("label").jsonPrimitive.content, renderer.accessibilityLabel("g/pending"))
                assertTrue(expected.getValue("available").jsonPrimitive.boolean)
            }
            "context" -> {
                val renderer = M0CentroidRendererState(testCase.int("capacity"))
                assertTrue(renderer.upsert(row("g/live", M0SemanticState.COVERED)))
                renderer.flush()
                renderer.loseContext()
                val lost = renderer.flush()
                renderer.restoreContext()
                val restored = renderer.flush()
                assertEquals(expected.getValue("lostRebuild").jsonPrimitive.boolean, lost.rebuild)
                assertEquals(expected.getValue("restoredRebuild").jsonPrimitive.boolean, restored.rebuild)
                assertEquals(expected.int("rowCount"), renderer.rowCount)
            }
            "stale" -> {
                val renderer = M0CentroidRendererState(testCase.int("capacity"))
                assertTrue(renderer.upsert(row("g/old", M0SemanticState.COVERED)))
                renderer.flush()
                assertTrue(renderer.remove("g/old"))
                assertTrue(renderer.upsert(row("g/live", M0SemanticState.PENDING, x = 1f)))
                renderer.flush()
                renderer.loseContext()
                while (renderer.flush().dirtySpans.isNotEmpty()) { }
                renderer.restoreContext()
                while (renderer.flush().dirtySpans.isNotEmpty()) { }
                assertEquals(expected.int("rowCount"), renderer.rowCount)
                assertEquals("Surface unavailable", renderer.accessibilityLabel("g/old"))
                assertFalse(renderer.hitTest(0f, 0f, 0f) == "g/old")
                assertEquals(expected.getValue("liveHit").jsonPrimitive.content, renderer.hitTest(1f, 0f, 0f))
            }
            "mode-switch" -> {
                val renderer = M0CentroidRendererState(testCase.int("capacity"))
                assertTrue(renderer.upsert(row("g/live", M0SemanticState.COVERED)))
                renderer.flush()
                renderer.setMode(M0RendererMode.CUBES)
                val plan = renderer.flush()
                assertEquals(expected.getValue("mode").jsonPrimitive.content.uppercase(), plan.mode.name)
                assertEquals(expected.int("rowCount"), plan.rowCount)
                assertEquals(expected.int("uploadBytes"), plan.uploadBytes)
            }
            "population" -> {
                val mode = mode(testCase.getValue("mode").jsonPrimitive.content)
                val rows = testCase.int("rows")
                val renderer = M0CentroidRendererState(rows)
                renderer.setMode(mode)
                repeat(rows) { index ->
                    assertTrue(renderer.upsert(row("$mode/$index", M0SemanticState.COVERED, x = index.toFloat())))
                }
                assertEquals(expected.int("rowCap"), renderer.rowCount)
                assertTrue(renderer.flush().uploadBytes <= 64 * 1024)
            }
            "upload" -> {
                val rows = testCase.int("rows")
                val renderer = M0CentroidRendererState(rows)
                repeat(rows) { index -> assertTrue(renderer.upsert(row("g/$index", M0SemanticState.COVERED))) }
                assertEquals(expected.int("uploadBytes"), renderer.flush().uploadBytes)
                assertEquals(rows, renderer.rowCount)
                renderer.flush()
                assertTrue(renderer.flush().dirtySpans.isEmpty())
            }
            "allocation" -> {
                val mode = mode(testCase.getValue("mode").jsonPrimitive.content)
                val rows = testCase.int("rows")
                val renderer = M0CentroidRendererState(rows)
                renderer.setMode(mode)
                repeat(rows) { index -> assertTrue(renderer.upsert(row("$mode/$index", M0SemanticState.COVERED))) }
                assertTrue(renderer.allocatedBytes <= 8 * 1024 * 1024)
            }
            "churn" -> {
                val rows = testCase.int("rows")
                val renderer = M0CentroidRendererState(rows)
                repeat(rows) { index -> assertTrue(renderer.upsert(row("g/$index", M0SemanticState.COVERED))) }
                renderer.flush()
                assertTrue(renderer.remove("g/0"))
                assertTrue(renderer.upsert(row("g/replacement", M0SemanticState.COVERED)))
                assertTrue(renderer.flush().uploadBytes <= 64 * 1024)
                assertTrue(expected.getValue("withinBudget").jsonPrimitive.boolean)
            }
            else -> error("Unknown M0d case $kind")
        }
    }

    private fun mode(value: String): M0RendererMode = when (value) {
        "centroids" -> M0RendererMode.CENTROIDS
        "cubes" -> M0RendererMode.CUBES
        "rawPoints" -> M0RendererMode.RAW_POINTS
        "overview" -> M0RendererMode.OVERVIEW
        else -> error("Unknown renderer mode $value")
    }

    private fun row(key: String, state: M0SemanticState, x: Float = 0f) =
        M0CentroidRow(key, x, 0f, 0f, state)

    private fun fixture(fileName: String = "m0d_reference_corpus_v1.json"): JsonObject =
        Json.parseToJsonElement(
            requireNotNull(javaClass.classLoader?.getResourceAsStream(fileName))
                .bufferedReader()
                .use { it.readText() },
        ).jsonObject

    private fun resourceBytes(fileName: String): ByteArray =
        requireNotNull(javaClass.classLoader?.getResourceAsStream(fileName)).readBytes()

    private fun sha256(bytes: ByteArray): String = MessageDigest
        .getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { byte -> "%02x".format(byte) }

    private fun JsonObject.int(key: String): Int = getValue(key).jsonPrimitive.int
}
