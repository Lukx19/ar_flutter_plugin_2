package com.uhg0.ar_flutter_plugin_2.m0

import com.uhg0.ar_flutter_plugin_2.pointcloud.CoveragePointRenderSnapshot
import com.uhg0.ar_flutter_plugin_2.pointcloud.CoveragePointRenderUpdate
import com.uhg0.ar_flutter_plugin_2.pointcloud.CoveragePointSpan
import com.uhg0.ar_flutter_plugin_2.sceneview.CoverageCubeMeshResources
import com.uhg0.ar_flutter_plugin_2.sceneview.CoveragePointMeshResources
import com.uhg0.ar_flutter_plugin_2.sceneview.CoveragePointUploadCoordinator
import com.uhg0.ar_flutter_plugin_2.sceneview.CoveragePointVertexUploader
import com.uhg0.ar_flutter_plugin_2.sceneview.CoveragePresentationSelector
import com.uhg0.ar_flutter_plugin_2.sceneview.CoverageRendererLimits
import com.uhg0.ar_flutter_plugin_2.sceneview.RendererTelemetry
import com.uhg0.ar_flutter_plugin_2.visibilitygrid.LongRowIndex
import java.io.File
import java.nio.ByteBuffer
import java.nio.FloatBuffer
import java.security.MessageDigest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue

internal object M0dT5ReceiptCampaign {
    private const val SELECTOR =
        "M0dExecutableReceiptTest.T5 native renderer campaign emits an immutable executable receipt"

    fun execute() {
        val sourceLock = fixture("m0d_source_pins_v1.json")
        val source = sourceLock["source"]?.jsonObject ?: sourceLock
        val corpus = corpusDescriptor()
        val canonical = canonicalPopulations()
        val selection = exerciseCanonicalSelection()
        val churn = exerciseIncrementalSelection()
        val uploads = exercisePagedUploads()
        val ledger = exerciseOwnedBufferLedger()
        val callbacks = exerciseCallbackLifecycle()

        val measurements = buildJsonObject {
            put("canonicalPopulations", canonical)
            put("selection", selection)
            put("churn", churn)
            put("uploads", uploads)
            put("ledger", ledger)
            put("callbacks", callbacks)
        }
        val observedSha256 = sha256(canonicalJson(measurements).encodeToByteArray())
        val hostFingerprint = sha256(
            listOf(
                System.getProperty("os.name"),
                System.getProperty("os.version"),
                System.getProperty("os.arch"),
                System.getProperty("java.vendor"),
                System.getProperty("java.version"),
            ).joinToString("\u0000").encodeToByteArray(),
        )
        val executionId = sha256(
            canonicalJson(
                buildJsonArray {
                    add(JsonPrimitive("T5"))
                    add(JsonPrimitive(SELECTOR))
                    add(JsonPrimitive(source.getValue("parentCommit").jsonPrimitive.content))
                    add(JsonPrimitive(source.getValue("pluginCommit").jsonPrimitive.content))
                    add(JsonPrimitive(observedSha256))
                },
            ).encodeToByteArray(),
        )
        val receipt = buildJsonObject {
            put("format", "proposal08-m0d-executable-receipt-v1")
            put("tier", "T5")
            put("executionId", executionId)
            put("runner", buildJsonObject {
                put("name", "android-gradle-jvm")
                put("version", System.getProperty("java.version"))
                put("hostFingerprint", hostFingerprint)
            })
            put("testSelector", SELECTOR)
            put("source", source)
            put("inputs", corpus)
            put("execution", buildJsonObject {
                put("observedSha256", observedSha256)
                put("semantic", "native-renderer-selection-upload-ledger-lifecycle")
            })
            put("coveredSelectors", buildJsonArray {
                add(JsonPrimitive("renderer_reference_v1"))
                add(JsonPrimitive("resource_budget_v1"))
                add(JsonPrimitive("P08-BUD-M0d-64KiB-ordinary-upload"))
            })
            put("assertions", assertions(measurements))
            put("measurements", measurements)
        }

        val report = File("build/reports/tests/m0d_t5_receipt_v1.json")
        checkNotNull(report.parentFile).mkdirs()
        report.writeText(receipt.toString() + "\n")
    }

    private fun exerciseCanonicalSelection(): JsonObject {
        val count = 100_000
        val keys = LongArray(count) { (count - 1 - it).toLong() }
        val positions = FloatArray(count * 3) { it.toFloat() }
        val colors = IntArray(count) { 0xff112233.toInt() }
        val selector = CoveragePresentationSelector(CoverageRendererLimits.CENTROID_CAPACITY)
        val initial = selector.select(snapshot(1, count, count, keys, positions, colors))
        assertEquals(CoverageRendererLimits.CENTROID_CAPACITY, initial.count)
        assertEquals(0L, initial.keys.first())
        assertEquals(19_999L, initial.keys.last())
        assertTrue(checkNotNull(initial.update).reset)

        val selectedSource = count - 1
        val changedPositions = positions.copyOf().also { it[selectedSource * 3] = -7f }
        val changed = selector.select(
            snapshot(
                revision = 2,
                capacity = count,
                count = count,
                keys = keys,
                positions = changedPositions,
                colors = colors,
                update = CoveragePointRenderUpdate(
                    geometryRevision = 2,
                    visibilityRevision = 2,
                    enabled = true,
                    count = count,
                    spans = listOf(
                        CoveragePointSpan(
                            startSlot = selectedSource,
                            positions = changedPositions.copyOfRange(selectedSource * 3, selectedSource * 3 + 3),
                            colors = intArrayOf(colors[selectedSource]),
                        ),
                    ),
                    reset = false,
                ),
            ),
        )
        val update = checkNotNull(changed.update)
        assertFalse(update.reset)
        assertEquals(1, update.spans.size)
        assertEquals(0, update.spans.single().startSlot)
        assertEquals(-7f, changed.positions.first(), 0f)
        assertArrayEquals(initial.keys, changed.keys)
        return buildJsonObject {
            put("semanticRows", count)
            put("selectedRows", initial.count)
            put("firstSelectedKey", initial.keys.first())
            put("lastSelectedKey", initial.keys.last())
            put("ordinaryReset", update.reset)
            put("ordinaryDirtySpanCount", update.spans.size)
            put("ordinaryDirtyStartSlot", update.spans.single().startSlot)
        }
    }

    private fun exerciseIncrementalSelection(): JsonObject {
        val capacity = 100_000
        val initialCount = capacity - 1
        val selector = CoveragePresentationSelector(CoverageRendererLimits.CENTROID_CAPACITY)
        val initialKeys = LongArray(initialCount) { (it + 1).toLong() }
        val initial = selector.select(
            snapshot(
                revision = 1,
                capacity = capacity,
                count = initialCount,
                keys = initialKeys,
                positions = FloatArray(initialCount * 3),
                colors = IntArray(initialCount),
            ),
        )
        val finalKeys = LongArray(capacity) { if (it < initialCount) initialKeys[it] else 0L }
        val final = selector.select(
            snapshot(
                revision = 2,
                capacity = capacity,
                count = capacity,
                keys = finalKeys,
                positions = FloatArray(capacity * 3),
                colors = IntArray(capacity),
                update = CoveragePointRenderUpdate(
                    geometryRevision = 2,
                    visibilityRevision = 2,
                    enabled = true,
                    count = capacity,
                    spans = listOf(CoveragePointSpan(initialCount, FloatArray(3), IntArray(1))),
                    reset = false,
                ),
            ),
        )
        val initialDestinations = LongRowIndex(initial.count)
        initial.keys.forEachIndexed { destination, key -> initialDestinations[key] = destination }
        var retainedSlotMoves = 0
        var changedDestinations = 0
        final.keys.forEachIndexed { destination, key ->
            if (initial.keys[destination] != key) changedDestinations++
            val priorDestination = initialDestinations[key]
            if (priorDestination != null && priorDestination != destination) retainedSlotMoves++
        }
        val churnFraction = changedDestinations.toDouble() / initial.count
        assertEquals(1, changedDestinations)
        assertEquals(0, retainedSlotMoves)
        assertTrue(churnFraction < 0.02)
        assertEquals(1L, final.keys.first())
        assertEquals(0L, final.keys.last())
        assertFalse(checkNotNull(final.update).reset)
        assertEquals(19_999, final.update.spans.single().startSlot)
        return buildJsonObject {
            put("finalSemanticRows", finalKeys.size)
            put("changedDestinationRows", changedDestinations)
            put("retainedSlotMoves", retainedSlotMoves)
            put("churnPartsPerMillion", (churnFraction * 1_000_000).toInt())
            put("churnLimitPartsPerMillion", 20_000)
            put("replacementReset", checkNotNull(final.update).reset)
        }
    }

    private fun exercisePagedUploads(): JsonObject {
        val pointUploader = ReceiptPointUploader()
        val pointBytes = mutableListOf<Int>()
        val pointCompletions = mutableListOf<Long>()
        val point = CoveragePointUploadCoordinator(
            capacity = 5_000,
            uploader = pointUploader,
            onUploadSubmitted = pointBytes::add,
            onUploadCompleted = pointCompletions::add,
            clockNanos = { 10L },
        )
        point.submit(
            snapshot(
                1,
                5_000,
                5_000,
                LongArray(5_000) { it.toLong() },
                FloatArray(15_000),
                IntArray(5_000),
            ),
        )
        repeat(2) {
            point.onRendererFrame()
            pointUploader.completeAll()
        }
        assertEquals(listOf(65_536, 14_464), pointBytes)
        assertEquals(listOf(0, 49_152), pointUploader.positionOffsets)
        assertEquals(listOf(0, 16_384), pointUploader.colorOffsets)
        assertEquals(2, pointCompletions.size)

        val cubeUploader = ReceiptCubeUploader()
        val cubeBytes = mutableListOf<Int>()
        val cubeCompletions = mutableListOf<Long>()
        val cube = CoverageCubeMeshResources.CoverageCubeUploadCoordinator(
            capacity = 513,
            halfSize = 0.5f,
            uploader = cubeUploader,
            onUploadSubmitted = cubeBytes::add,
            onUploadCompleted = cubeCompletions::add,
            clockNanos = { 20L },
        )
        cube.submit(
            snapshot(
                1,
                513,
                513,
                LongArray(513) { it.toLong() },
                FloatArray(1_539),
                IntArray(513),
            ),
        )
        repeat(2) {
            cube.onRendererFrame()
            cubeUploader.completeAll()
        }
        assertEquals(listOf(65_536, 128), cubeBytes)
        assertEquals(listOf(0, 49_152), cubeUploader.positionOffsets)
        assertEquals(listOf(0, 16_384), cubeUploader.colorOffsets)
        assertEquals(2, cubeCompletions.size)
        return buildJsonObject {
            put("pointBytesPerFrame", pointBytes.toJson())
            put("pointPositionOffsets", pointUploader.positionOffsets.toJson())
            put("pointColorOffsets", pointUploader.colorOffsets.toJson())
            put("pointCompletedUploads", pointCompletions.size)
            put("cubeBytesPerFrame", cubeBytes.toJson())
            put("cubePositionOffsets", cubeUploader.positionOffsets.toJson())
            put("cubeColorOffsets", cubeUploader.colorOffsets.toJson())
            put("cubeCompletedUploads", cubeCompletions.size)
            put("maximumFrameUploadBytes", maxOf(pointBytes.max(), cubeBytes.max()))
        }
    }

    private fun exerciseOwnedBufferLedger(): JsonObject {
        val telemetry = RendererTelemetry()
        telemetry.setOwnedBufferBytes("selection", CoverageRendererLimits.NATIVE_SELECTION_BYTES)
        telemetry.setOwnedBufferBytes("auxiliary", CoverageRendererLimits.AUXILIARY_BYTES)
        telemetry.setOwnedBufferBytes("snapshot-handoff", CoverageRendererLimits.CUBE_SNAPSHOT_HANDOFF_BYTES)
        telemetry.setOwnedBufferBytes(
            "active",
            CoverageRendererLimits.CUBE_CAPACITY * CoverageCubeMeshResources.OWNED_BYTES_PER_VOXEL,
        )
        val maximum = telemetry.snapshot().getValue("ownedBufferBytes") as Int
        assertEquals(7_486_208, maximum)
        val limitPlusOneRejected = runCatching {
            RendererTelemetry().setOwnedBufferBytes(
                "limit-plus-one",
                RendererTelemetry.RENDERER_ALLOCATION_LIMIT_BYTES + 1,
            )
        }.isFailure
        assertTrue(limitPlusOneRejected)
        telemetry.removeOwner("active")
        telemetry.setOwnedBufferBytes(
            "active",
            CoverageRendererLimits.CENTROID_CAPACITY * CoveragePointMeshResources.OWNED_BYTES_PER_ROW,
        )
        assertEquals(maximum, telemetry.snapshot().getValue("peakOwnedBufferBytes"))
        return buildJsonObject {
            put("maximumActiveRendererBytes", maximum)
            put("rendererOwnedBufferLimitBytes", RendererTelemetry.RENDERER_ALLOCATION_LIMIT_BYTES)
            put("limitPlusOneBytes", RendererTelemetry.RENDERER_ALLOCATION_LIMIT_BYTES + 1)
            put("limitPlusOneRejected", limitPlusOneRejected)
            put("peakAfterReplacementBytes", telemetry.snapshot().getValue("peakOwnedBufferBytes") as Int)
        }
    }

    private fun exerciseCallbackLifecycle(): JsonObject {
        val uploader = ReceiptPointUploader()
        val completions = mutableListOf<Long>()
        var now = 100L
        val coordinator = CoveragePointUploadCoordinator(
            capacity = 1,
            uploader = uploader,
            onUploadCompleted = completions::add,
            clockNanos = { now },
        )
        coordinator.submit(snapshot(1, 1, 1, longArrayOf(1), FloatArray(3), IntArray(1)))
        coordinator.onRendererFrame()
        val callbacks = uploader.pendingCallbacks.toList()
        now = 175L
        uploader.completeAll()
        assertEquals(listOf(75L), completions)
        coordinator.destroy()
        callbacks.forEach { it.invoke() }
        assertEquals(1, completions.size)
        assertEquals(1, uploader.positionSubmissions)
        return buildJsonObject {
            put("completedUploadCount", completions.size)
            put("completionNanos", completions.single())
            put("completionCountAfterDestroyAndLateCallbacks", completions.size)
            put("positionSubmissionsAfterDestroy", uploader.positionSubmissions)
        }
    }

    private fun assertions(measurements: JsonObject): JsonArray {
        val selection = measurements.getValue("selection").jsonObject
        val churn = measurements.getValue("churn").jsonObject
        val uploads = measurements.getValue("uploads").jsonObject
        val ledger = measurements.getValue("ledger").jsonObject
        val callbacks = measurements.getValue("callbacks").jsonObject
        return buildJsonArray {
            add(assertion("semantic-population", selection.getValue("semanticRows"), JsonPrimitive(100_000)))
            add(assertion("centroid-selection", selection.getValue("selectedRows"), JsonPrimitive(20_000)))
            add(assertion("ordinary-incremental-dirty-span", selection.getValue("ordinaryDirtySpanCount"), JsonPrimitive(1)))
            add(assertion("incremental-churn-under-limit", churn.getValue("churnPartsPerMillion"), JsonPrimitive(20_000), "lessThan"))
            add(assertion("ordinary-frame-upload-ceiling", uploads.getValue("maximumFrameUploadBytes"), JsonPrimitive(65_536), "atMost"))
            add(assertion("point-upload-offsets", uploads.getValue("pointPositionOffsets"), listOf(0, 49_152).toJson()))
            add(assertion("cube-upload-offsets", uploads.getValue("cubePositionOffsets"), listOf(0, 49_152).toJson()))
            add(assertion("maximum-active-ledger", ledger.getValue("maximumActiveRendererBytes"), JsonPrimitive(7_486_208)))
            add(assertion("limit-plus-one-rejected", ledger.getValue("limitPlusOneRejected"), JsonPrimitive(true)))
            add(assertion("upload-completion-nonzero", callbacks.getValue("completedUploadCount"), JsonPrimitive(0), "greaterThan"))
            add(assertion("no-late-callbacks", callbacks.getValue("completionCountAfterDestroyAndLateCallbacks"), JsonPrimitive(1)))
        }
    }

    private fun assertion(id: String, actual: JsonElement, expected: JsonElement, predicate: String = "equals") =
        buildJsonObject {
            put("id", id)
            put("predicate", predicate)
            put("actual", actual)
            put("expected", expected)
        }

    private fun corpusDescriptor(): JsonObject {
        val files = listOf(
            "m0d_reference_corpus_v1.json",
            "m0d_renderer_corpus_v1.json",
            "m0d_renderer_train_v1.json",
            "m0d_renderer_validation_v1.json",
            "m0d_renderer_locked_v1.json",
        )
        val descriptor = buildJsonObject {
            put("fixtureFamily", "renderer_reference_v1")
            put("files", buildJsonObject {
                files.forEach { file -> put("docs/m0/$file", sha256(resourceBytes(file))) }
            })
        }
        val reference = fixture("m0d_reference_corpus_v1.json")
        val stageDescriptors = reference.getValue("rendererCorpus").jsonObject
            .getValue("stageCorpora").jsonObject
        stageDescriptors.values.forEach { value ->
            val stage = value.jsonObject
            assertEquals(
                stage.getValue("sha256").jsonPrimitive.content,
                descriptor.getValue("files").jsonObject
                    .getValue("docs/m0/${stage.getValue("file").jsonPrimitive.content}")
                    .jsonPrimitive.content,
            )
        }
        return descriptor
    }

    private fun canonicalPopulations(): JsonObject = buildJsonObject {
        put("semanticRows", 100_000)
        put("centroidRows", CoverageRendererLimits.CENTROID_CAPACITY)
        put("cubeRows", CoverageRendererLimits.CUBE_CAPACITY)
        put("rawPointRows", CoverageRendererLimits.RAW_POINT_CAPACITY)
        put("warmProxyRows", CoverageRendererLimits.WARM_PROXY_CAPACITY)
        put("coldOverviewRows", CoverageRendererLimits.COLD_OVERVIEW_CAPACITY)
        put("glyphRows", CoverageRendererLimits.GLYPH_CAPACITY)
        put("suppressedDebugRows", CoverageRendererLimits.DEBUG_ROW_CAPACITY)
    }

    private fun snapshot(
        revision: Long,
        capacity: Int,
        count: Int,
        keys: LongArray,
        positions: FloatArray,
        colors: IntArray,
        update: CoveragePointRenderUpdate? = null,
    ) = CoveragePointRenderSnapshot(
        revision = revision,
        enabled = true,
        capacity = capacity,
        count = count,
        keys = keys,
        positions = positions,
        colors = colors,
        update = update,
    )

    private fun fixture(fileName: String): JsonObject =
        Json.parseToJsonElement(resourceBytes(fileName).decodeToString()).jsonObject

    private fun resourceBytes(fileName: String): ByteArray =
        requireNotNull(javaClass.classLoader?.getResourceAsStream(fileName)).readBytes()

    private fun sha256(bytes: ByteArray): String = MessageDigest
        .getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { byte -> "%02x".format(byte) }

    private fun canonicalJson(value: JsonElement): String = when (value) {
        is JsonObject -> value.entries
            .sortedBy { it.key }
            .joinToString(separator = ",", prefix = "{", postfix = "}") { (key, item) ->
                "${JsonPrimitive(key)}:${canonicalJson(item)}"
            }
        is JsonArray -> value.joinToString(separator = ",", prefix = "[", postfix = "]") {
            canonicalJson(it)
        }
        else -> value.toString()
    }

    private fun List<Int>.toJson(): JsonArray = buildJsonArray { forEach { add(JsonPrimitive(it)) } }
}

private class ReceiptPointUploader : CoveragePointVertexUploader {
    var positionSubmissions = 0
    val positionOffsets = mutableListOf<Int>()
    val colorOffsets = mutableListOf<Int>()
    val pendingCallbacks = mutableListOf<() -> Unit>()

    override fun uploadPositions(
        buffer: FloatBuffer,
        destOffsetBytes: Int,
        elementCount: Int,
        onConsumed: () -> Unit,
    ) {
        positionSubmissions++
        positionOffsets += destOffsetBytes
        pendingCallbacks += onConsumed
    }

    override fun uploadColors(
        buffer: ByteBuffer,
        destOffsetBytes: Int,
        byteCount: Int,
        onConsumed: () -> Unit,
    ) {
        colorOffsets += destOffsetBytes
        pendingCallbacks += onConsumed
    }

    fun completeAll() {
        while (pendingCallbacks.isNotEmpty()) pendingCallbacks.removeAt(0).invoke()
    }
}

private class ReceiptCubeUploader : CoverageCubeMeshResources.CoverageCubeVertexUploader {
    val positionOffsets = mutableListOf<Int>()
    val colorOffsets = mutableListOf<Int>()
    private val pendingCallbacks = mutableListOf<() -> Unit>()

    override fun uploadPositions(
        buffer: FloatBuffer,
        destOffsetBytes: Int,
        elementCount: Int,
        onConsumed: () -> Unit,
    ) {
        positionOffsets += destOffsetBytes
        pendingCallbacks += onConsumed
    }

    override fun uploadColors(
        buffer: ByteBuffer,
        destOffsetBytes: Int,
        byteCount: Int,
        onConsumed: () -> Unit,
    ) {
        colorOffsets += destOffsetBytes
        pendingCallbacks += onConsumed
    }

    fun completeAll() {
        while (pendingCallbacks.isNotEmpty()) pendingCallbacks.removeAt(0).invoke()
    }
}
