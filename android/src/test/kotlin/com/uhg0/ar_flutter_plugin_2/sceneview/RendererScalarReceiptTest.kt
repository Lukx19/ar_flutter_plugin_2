package com.uhg0.ar_flutter_plugin_2.sceneview

import java.io.File
import java.security.MessageDigest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RendererScalarReceiptTest {
    @Test
    fun scalarResourceStateSeparatesFailureAdmissionAndUploadWithoutLegacyWindows() {
        val telemetry = RendererTelemetry()
        telemetry.recordResourceFailure()
        telemetry.recordResourceFailureDetail("allocation", "IllegalStateException")
        val scheduler = RendererPageFrameScheduler { }
        scheduler.request { error("queued frame must remain unexecuted") }
        val pages = scheduler.pressureSnapshot()
        val scalars = telemetry.resourceScalarSnapshot() + mapOf(
            "pageFramesRequested" to pages.requested, "pageFramesExecuted" to pages.executed,
            "pageFramesPending" to pages.pendingTransactions, "resourceTokenEpoch" to 3L)
        val status = CoverageRendererStatus(true, true, CoveragePresentationMode.SEMANTIC_CENTROIDS,
            CoveragePalette.COVERAGE, 1199, 1199, 0, 387, 402, resourceAvailable = false, recoveryPending = true)
        val cadence = FrameCadenceTracker().snapshot()
        val reply = rendererScalarReceipt(status, cadence, cadence, 30, 30, false, true, true,
            1, 2, 3, scalars)
        val renderer = reply["renderer"] as Map<*, *>
        assertEquals(1199, renderer["rowCount"])
        assertEquals(false, renderer["resourceAvailable"])
        assertEquals(1, renderer["resourceFailureCount"])
        assertEquals("allocation", renderer["lastResourceFailureStage"])
        assertEquals("IllegalStateException", renderer["lastResourceFailureClass"])
        assertEquals(1L, renderer["pageFramesRequested"])
        assertEquals(0L, renderer["pageFramesExecuted"])
        assertEquals(1L, renderer["pageFramesPending"])
        assertEquals(3L, renderer["resourceTokenEpoch"])
        assertTrue(renderer.values.all { it == null || it is Number || it is Boolean || it is String })
        assertEquals(16, scalars.size)
        System.getenv("CAPTURE3D_DIAGNOSTIC_COMPOSITION_DIR")?.let { directory ->
            File(directory, "native-renderer-resource-superset.json").apply {
                parentFile?.mkdirs()
                writeText(encodeJson(reply))
            }
        }
    }

    @Test
    fun `scalar projection preserves hidden rows and separates cadence gates without diagnostic arrays`() {
        val cadence = FrameCadenceTracker()
        cadence.record(1_000_000_000L)
        cadence.record(1_033_333_333L)
        val before = cadence.snapshot()
        val input = before + mapOf("diagnosticTiming" to longArrayOf(1, 2), "backingPages" to listOf(3))
        val status = CoverageRendererStatus(false, false, CoveragePresentationMode.SEMANTIC_CENTROIDS,
            CoveragePalette.COVERAGE, 7, 5, 3L, 4L, 6L)
        val reply = rendererScalarReceipt(status, input, input, 30, 30, true, true, false,
            816664864980761L, 816664898314095L, 123456L)
        val renderer = reply["renderer"] as Map<*, *>
        assertEquals(false, renderer["visible"])
        assertEquals(7, renderer["rowCount"])
        assertEquals(5, renderer["selectedRows"])
        val gates = reply["gates"] as Map<*, *>
        assertEquals(true, gates["nativeCapturePending"])
        assertEquals(true, gates["rendererCadenceEligible"])
        assertEquals(false, gates["cameraCadenceEligible"])
        val scalars = reply["cadence"] as Map<*, *>
        assertFalse(scalars.containsKey("diagnosticTiming"))
        assertFalse(scalars.containsKey("backingPages"))
        assertTrue(scalars.values.none { it is List<*> || it is LongArray })
        assertEquals(before, cadence.snapshot())
        val fixture = compositionFixture()
        val native = fixture.getValue("native").jsonObject
        val expectedRenderer = native.getValue("renderer").jsonObject
        val expectedFields = expectedRenderer.getValue("renderer").jsonObject
        val expectedGates = expectedRenderer.getValue("gates").jsonObject
        val expectedSampling = expectedRenderer.getValue("sampling").jsonObject
        assertEquals(expectedFields.boolean("visible"), renderer["visible"])
        assertEquals(expectedFields.string("mode"), renderer["mode"])
        assertEquals(expectedFields.int("rowCount"), renderer["rowCount"])
        assertEquals(expectedFields.int("selectedRows"), renderer["selectedRows"])
        assertEquals(expectedGates.boolean("nativeCapturePending"), gates["nativeCapturePending"])
        assertEquals(expectedGates.boolean("rendererCadenceEligible"), gates["rendererCadenceEligible"])
        assertEquals(expectedGates.boolean("cameraCadenceEligible"), gates["cameraCadenceEligible"])
        assertEquals(expectedSampling.string("rendererCapturedStartNs"), (reply["sampling"] as Map<*, *>)["rendererCapturedStartNs"])
        val hashInput = listOf(
            renderer["visible"], renderer["mode"], renderer["rowCount"], renderer["selectedRows"],
            gates["nativeCapturePending"], gates["rendererCadenceEligible"], gates["cameraCadenceEligible"],
            (reply["sampling"] as Map<*, *>)["rendererCapturedStartNs"],
            (reply["sampling"] as Map<*, *>)["rendererCapturedEndNs"],
        ).joinToString("|")
        assertEquals(expectedRenderer.string("rendererHashInput"), hashInput)
        assertEquals(fixture.getValue("hashes").jsonObject.string("renderer"), sha256(hashInput))
        writeCompositionReceipt("native-renderer.json", reply, "renderer")
        assertEquals("816664864980761", (reply["sampling"] as Map<*, *>)["rendererCapturedStartNs"])
    }

    private fun compositionFixture(): JsonObject = Json.parseToJsonElement(
        File("../../../scripts/performance/fixtures/live_ar_diagnostics_composition.json").readText()
    ).jsonObject

    private fun JsonObject.string(key: String): String = getValue(key).jsonPrimitive.content
    private fun JsonObject.int(key: String): Int = getValue(key).jsonPrimitive.int
    private fun JsonObject.boolean(key: String): Boolean = getValue(key).jsonPrimitive.boolean
    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray())
        .joinToString("") { "%02x".format(it) }

    private fun writeCompositionReceipt(
        fileName: String,
        receipt: Map<String, Any?>,
        fixtureName: String,
    ) {
        val encoded = encodeJson(receipt)
        check(encoded.toByteArray(Charsets.UTF_8).size <= 24 * 1024) { "diagnostic receipt exceeds 24 KiB" }
        System.getenv("CAPTURE3D_DIAGNOSTIC_COMPOSITION_DIR")?.let { directory ->
            File(directory, fileName).apply {
                parentFile?.mkdirs()
                writeText(encoded)
            }
        }
        if (System.getenv("CAPTURE3D_DIAGNOSTIC_COMPOSITION_RECORD") == "1") return
        val nativeComplete = compositionFixture()["nativeComplete"]?.jsonObject
            ?: error("composition fixture lacks nativeComplete in strict mode")
        val expected = nativeComplete[fixtureName]?.jsonObject
            ?: error("composition fixture lacks nativeComplete.$fixtureName")
        assertEquals(expected, Json.parseToJsonElement(encodeJson(receipt)).jsonObject)
    }

    private fun encodeJson(value: Any?): String = when (value) {
        null -> "null"
        is String -> quoteJson(value)
        is Boolean -> value.toString()
        is Number -> value.toString()
        is Map<*, *> -> value.entries.joinToString(",", "{", "}") { (key, nested) ->
            require(key is String) { "diagnostic receipt map keys must be strings" }
            "${quoteJson(key)}:${encodeJson(nested)}"
        }
        is Iterable<*> -> error("diagnostic receipt must contain scalar leaves")
        else -> error("unsupported diagnostic receipt value: ${value::class.java.name}")
    }

    private fun quoteJson(value: String): String = buildString {
        append('"')
        value.forEach { character ->
            when (character) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\b' -> append("\\b")
                '\u000C' -> append("\\f")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (character.code < 0x20) append("\\u%04x".format(character.code)) else append(character)
            }
        }
        append('"')
    }
}
