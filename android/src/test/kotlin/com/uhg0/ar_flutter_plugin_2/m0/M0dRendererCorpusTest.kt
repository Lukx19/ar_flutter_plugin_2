package com.uhg0.ar_flutter_plugin_2.m0

import java.security.MessageDigest
import com.uhg0.ar_flutter_plugin_2.pointcloud.COVERAGE_RENDERER_MAX_STYLE_PATCH_ROWS
import com.uhg0.ar_flutter_plugin_2.pointcloud.COVERAGE_RENDERER_STYLE_ROW_BYTES
import com.uhg0.ar_flutter_plugin_2.pointcloud.CoverageRendererAge
import com.uhg0.ar_flutter_plugin_2.pointcloud.CoverageRendererCoverage
import com.uhg0.ar_flutter_plugin_2.pointcloud.CoverageRendererCut
import com.uhg0.ar_flutter_plugin_2.pointcloud.CoverageRendererGlyph
import com.uhg0.ar_flutter_plugin_2.pointcloud.CoverageRendererPalette
import com.uhg0.ar_flutter_plugin_2.pointcloud.CoverageRendererResidency
import com.uhg0.ar_flutter_plugin_2.pointcloud.CoverageRendererSemantic
import com.uhg0.ar_flutter_plugin_2.pointcloud.CoverageRendererSourceHealth
import com.uhg0.ar_flutter_plugin_2.pointcloud.CoverageRendererStyleRowV1
import com.uhg0.ar_flutter_plugin_2.pointcloud.CoverageRendererTarget
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
import org.junit.Assert.assertThrows
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
        when (kind) {
            "style-domain" -> return runStyleDomain(testCase)
            "style-vectors" -> return runStyleVectors(testCase)
            "malformed-styles" -> return runMalformedStyles(testCase)
            "packet-boundary" -> {
                assertEquals(COVERAGE_RENDERER_STYLE_ROW_BYTES, testCase.int("rowBytes"))
                assertEquals(COVERAGE_RENDERER_MAX_STYLE_PATCH_ROWS, testCase.int("maximumRows"))
                assertEquals(
                    testCase.int("keyAndStyleBytes"),
                    testCase.int("maximumRows") * (Long.SIZE_BYTES + COVERAGE_RENDERER_STYLE_ROW_BYTES),
                )
                assertEquals(COVERAGE_RENDERER_MAX_STYLE_PATCH_ROWS + 1, testCase.int("overflowRows"))
                return
            }
        }
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
                val plan = renderer.flush()
                val changedRows = plan.dirtySpans.sumOf { it.endExclusive - it.start }
                assertTrue(changedRows <= expected.int("maximumChangedRows"))
                assertTrue(plan.uploadBytes <= expected.int("maximumUploadBytes"))
            }
            else -> error("Unknown M0d case $kind")
        }
    }

    private fun runStyleDomain(testCase: JsonObject) {
        val field = testCase.getValue("field").jsonPrimitive.content
        testCase.getValue("values").jsonArray.forEach { element ->
            val value = element.jsonObject
            val row = styleRow(field, value)
            assertEquals(row, CoverageRendererStyleRowV1.decode(row.encode()))
            if ("code" in value) assertEquals(value.int("code"), styleCode(field, row))
            value["argb"]?.jsonPrimitive?.content?.let { expectedArgb ->
                assertEquals(expectedArgb.toLong(16).toInt(), row.packedColor())
            }
        }
    }

    private fun styleRow(field: String, value: JsonObject): CoverageRendererStyleRowV1 {
        val name = value["name"]?.jsonPrimitive?.content
        return when (field) {
            "semantic" -> CoverageRendererStyleRowV1(
                semantic = CoverageRendererSemantic.entries.first { it.name.toWire() == name },
                palette = CoverageRendererPalette.OCCUPANCY,
            )
            "coverage" -> CoverageRendererStyleRowV1(
                coverage = CoverageRendererCoverage.entries.first { it.name.toWire() == name },
            )
            "palette" -> {
                val palette = CoverageRendererPalette.entries.first { it.name.toWire() == name }
                CoverageRendererStyleRowV1(
                    palette = palette,
                    directionBin = if (palette == CoverageRendererPalette.DIRECTION) 0 else 0xff,
                    glyph = if (palette == CoverageRendererPalette.DIRECTION) {
                        CoverageRendererGlyph.VIEW_ROSE
                    } else CoverageRendererGlyph.NONE,
                )
            }
            "cut" -> CoverageRendererStyleRowV1(
                cut = CoverageRendererCut.entries.first { it.name.toWire() == name },
            )
            "residency" -> CoverageRendererStyleRowV1(
                residency = CoverageRendererResidency.entries.first { it.name.toWire() == name },
                palette = CoverageRendererPalette.RESIDENCY,
            )
            "target" -> CoverageRendererStyleRowV1(
                target = CoverageRendererTarget.entries.first { it.name.toWire() == name },
            )
            "glyph" -> CoverageRendererStyleRowV1(
                glyph = CoverageRendererGlyph.entries.first { it.name.toWire() == name },
                directionBin = value.int("directionBin"),
                palette = CoverageRendererPalette.DIRECTION,
            )
            "lineage" -> CoverageRendererStyleRowV1(
                lineageCount = value.int("value"),
                palette = CoverageRendererPalette.LINEAGE,
            )
            "age" -> CoverageRendererStyleRowV1(
                age = CoverageRendererAge.entries.first { it.name.toWire() == name },
                palette = CoverageRendererPalette.AGE,
            )
            "sourceHealth" -> CoverageRendererStyleRowV1(
                sourceHealth = CoverageRendererSourceHealth.entries.first { it.name.toWire() == name },
                palette = CoverageRendererPalette.SOURCE_HEALTH,
            )
            else -> error("Unknown renderer style field $field")
        }
    }

    private fun styleCode(field: String, row: CoverageRendererStyleRowV1): Int = when (field) {
        "semantic" -> row.semantic.code
        "coverage" -> row.coverage.code
        "palette" -> row.palette.code
        "cut" -> row.cut.code
        "residency" -> row.residency.code
        "target" -> row.target.code
        "glyph" -> row.glyph.code
        "age" -> row.age.code
        "sourceHealth" -> row.sourceHealth.code
        else -> error("Style field $field has no enum code")
    }

    private fun runStyleVectors(testCase: JsonObject) {
        testCase.getValue("vectors").jsonArray.forEach { element ->
            val vector = element.jsonObject
            val row = when (vector.getValue("name").jsonPrimitive.content) {
                "zero" -> CoverageRendererStyleRowV1()
                "uint32-max" -> CoverageRendererStyleRowV1(
                    semanticGeneration = 0xffff_ffffL,
                    styleGeneration = 0xffff_ffffL,
                )
                "all-fields" -> CoverageRendererStyleRowV1(
                    semanticGeneration = 0xffff_ffffL,
                    styleGeneration = 17,
                    semantic = CoverageRendererSemantic.AMBIGUOUS,
                    coverage = CoverageRendererCoverage.PARTIAL,
                    palette = CoverageRendererPalette.DIRECTION,
                    cut = CoverageRendererCut.INDETERMINATE_HISTORY,
                    residency = CoverageRendererResidency.WARM_L1,
                    target = CoverageRendererTarget.HALO,
                    directionBin = 23,
                    glyph = CoverageRendererGlyph.VIEW_ROSE,
                    lineageCount = 0xffff,
                    age = CoverageRendererAge.OLD,
                    sourceHealth = CoverageRendererSourceHealth.FEATURE_ONLY,
                )
                else -> error("Unknown renderer style vector")
            }
            assertEquals(vector.getValue("hex").jsonPrimitive.content, row.encode().toHex())
            assertEquals(row, CoverageRendererStyleRowV1.decode(row.encode()))
        }
    }

    private fun runMalformedStyles(testCase: JsonObject) {
        testCase.getValue("hex").jsonArray.forEach { element ->
            val bytes = element.jsonPrimitive.content.hexBytes()
            assertThrows(IllegalArgumentException::class.java) {
                CoverageRendererStyleRowV1.decode(bytes)
            }
        }
        testCase.getValue("invalidBuilds").jsonArray.forEach { element ->
            assertThrows(IllegalArgumentException::class.java) {
                when (element.jsonPrimitive.content) {
                    "semanticGenerationOverflow" -> CoverageRendererStyleRowV1(semanticGeneration = 0x1_0000_0000L)
                    "styleGenerationOverflow" -> CoverageRendererStyleRowV1(styleGeneration = 0x1_0000_0000L)
                    "lineageOverflow" -> CoverageRendererStyleRowV1(lineageCount = 0x1_0000)
                    "directionReserved" -> CoverageRendererStyleRowV1(
                        directionBin = 24,
                        glyph = CoverageRendererGlyph.DESIRED_DIRECTION,
                    )
                    "glyphDirectionMismatch" -> CoverageRendererStyleRowV1(
                        directionBin = 0,
                        glyph = CoverageRendererGlyph.NONE,
                    )
                    else -> error("Unknown malformed style build")
                }
            }
        }
    }

    private fun mode(value: String): M0RendererMode = when (value) {
        "centroids" -> M0RendererMode.CENTROIDS
        "cubes" -> M0RendererMode.CUBES
        "rawPoints" -> M0RendererMode.RAW_POINTS
        "warmProxies" -> M0RendererMode.WARM_PROXIES
        "overview" -> M0RendererMode.OVERVIEW
        "glyphs" -> M0RendererMode.GLYPHS
        "suppressedDebug" -> M0RendererMode.SUPPRESSED_DEBUG
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

    private fun String.toWire(): String = lowercase().split('_').let { words ->
        words.first() + words.drop(1).joinToString("") { it.replaceFirstChar(Char::uppercase) }
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    private fun String.hexBytes(): ByteArray {
        require(length % 2 == 0)
        return ByteArray(length / 2) { index -> substring(index * 2, index * 2 + 2).toInt(16).toByte() }
    }
}
