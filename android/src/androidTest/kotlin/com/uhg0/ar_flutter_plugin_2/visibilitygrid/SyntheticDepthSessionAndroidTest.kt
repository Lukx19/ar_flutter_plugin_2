package com.uhg0.ar_flutter_plugin_2.visibilitygrid

import android.content.Context
import android.os.Debug
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.uhg0.ar_flutter_plugin_2.capture.AndroidDescriptorFilesystemV2
import com.uhg0.ar_flutter_plugin_2.capture.StorageBudgetCoordinatorV2
import com.uhg0.ar_flutter_plugin_2.capture.StorageBudgetPolicyV2
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.max
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Runs the production raw-depth selector, evidence kernel, canonical commit, and ACK on a
 * physical Android runtime without depending on a live camera or AR tracking motion.
 *
 * The same direct 2,000 x 2,000 buffers are rewritten for each map. This keeps the benchmark's
 * memory shape representative of one callback and prevents the fixture from hiding a retention
 * problem by holding three complete depth images at once.
 */
@RunWith(AndroidJUnit4::class)
class SyntheticDepthSessionAndroidTest {
    @Test(timeout = 12 * 60 * 1_000L)
    fun threeMaterialDepthMapsCompleteSelectionFusionCommitAndAckInOneSession() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(context.cacheDir, "synthetic-depth-session-${System.nanoTime()}")
        check(root.mkdirs())
        val depthPixels = ByteBuffer.allocateDirect(IMAGE_WIDTH * IMAGE_HEIGHT * 2)
            .order(ByteOrder.LITTLE_ENDIAN)
        val confidencePixels = ByteBuffer.allocateDirect(IMAGE_WIDTH * IMAGE_HEIGHT)
        val group = SurfaceGroup("d".repeat(32))
        val coordinator = StorageBudgetCoordinatorV2(
            File(root, "visibility-grid-canonical-surface-runtime"),
            StorageBudgetPolicyV2(64L * 1024 * 1024, 0),
            AndroidDescriptorFilesystemV2(),
        )
        val resources = CanonicalRuntimeResources.openLive(root, group, coordinator)
        val kernel = DepthEvidenceKernel(
            DepthEvidenceConfiguration(occupiedEvidenceToShow = 1),
        )
        val identity = identityTransform()
        val groupFrame = VisibilityGroupFrame.copyOf(identity, identity, 100_000, 100_000)
        val selectionMicros = ArrayList<Long>(MAP_COUNT)
        val fusionMicros = ArrayList<Long>(MAP_COUNT)
        val preparationMicros = ArrayList<Long>(MAP_COUNT)
        val commitMicros = ArrayList<Long>(MAP_COUNT)
        val ackMicros = ArrayList<Long>(MAP_COUNT)
        val endToEndMicros = ArrayList<Long>(MAP_COUNT)
        val mapAllocatedBytes = ArrayList<Long?>(MAP_COUNT)
        val mapGcCounts = ArrayList<Long?>(MAP_COUNT)
        val preparationAllocatedBytes = ArrayList<Long?>(MAP_COUNT)
        val commitAllocatedBytes = ArrayList<Long?>(MAP_COUNT)
        val selectedCounts = ArrayList<Int>(MAP_COUNT)
        var committedMaps = 0
        var retainedSamples = 0
        try {
            val opened = resources.openInitial(committedEmptyBaseline("tablet", group.value, 1, 1, 1))
            assertTrue("canonical runtime did not open: $opened", opened is SurfaceOwnershipOpenResult.Opened)
            val allocatedBefore = Debug.getRuntimeStat("art.gc.bytes-allocated")?.toLongOrNull()
            val campaignStartedMs = SystemClock.elapsedRealtime()
            repeat(MAP_COUNT) { mapIndex ->
                val timestampNs = (mapIndex + 1L) * 3_000_000_000L
                writeMaterialMap(depthPixels, confidencePixels, mapIndex)
                val source = materialSource(depthPixels, confidencePixels)
                // Two warm-up frames establish the selector's stable-neighbour state. They are
                // real 2,000 x 2,000 acquisitions but are excluded from the per-map admission
                // timing, just as the live pipeline excludes its previous-frame history.
                source.acquire(metadata(timestampNs - 2_000_000_000L))
                source.acquire(metadata(timestampNs - 1_000_000_000L))
                val mapAllocatedBefore = Debug.getRuntimeStat("art.gc.bytes-allocated")?.toLongOrNull()
                val mapGcBefore = Debug.getRuntimeStat("art.gc.gc-count")?.toLongOrNull()
                val mapStartedNs = System.nanoTime()
                val selectedStartedNs = System.nanoTime()
                val acquisition = source.acquire(metadata(timestampNs))
                val selected = acquisition as? DepthAcquisitionResult.Observation
                    ?: error("synthetic depth map $mapIndex was not selected")
                selectionMicros += elapsedMicros(selectedStartedNs, System.nanoTime())
                selectedCounts += selected.value.samples.size
                assertTrue("map $mapIndex was over sample cap", selected.value.samples.size <= V2_DEPTH_SAMPLE_CAPACITY)
                assertTrue("map $mapIndex lost retained detail", selected.value.samples.size > 1_536)
                assertTrue("map $mapIndex lost 300 mm patch", selected.value.samples.any { it.depthMillimeters == 300 })
                assertTrue("map $mapIndex lost 400 mm patch", selected.value.samples.any { it.depthMillimeters == 400 })
                assertTrue("map $mapIndex lost 600 mm patch", selected.value.samples.any { it.depthMillimeters == 600 })
                retainedSamples += selected.value.samples.size

                val batch = DepthEvidenceBatch(
                    sequence = mapIndex.toLong() + 1L,
                    sourceTimestampNs = timestampNs,
                    groupFrame = groupFrame,
                    groupFromCameraGl = identity.toList(),
                    intrinsics = VisibilityCameraIntrinsics(
                        IMAGE_WIDTH, IMAGE_HEIGHT, 1_000.0, 1_000.0, 1_000.0, 1_000.0,
                    ),
                    samples = selected.value.samples.map {
                        VisibilityDepthSample(it.x, it.y, it.depthMillimeters, it.confidence)
                    },
                    sourceRejectedSamples = selected.value.sourceRejectedPixels,
                )
                val fusionStartedNs = System.nanoTime()
                assertTrue("map $mapIndex spatial window refused", resources.updateSpatialWindow(batch))
                val currentCut = requireNotNull(resources.owner().activationState()).cut
                val lookup = resources.withBoundedCurrent(
                    BoundedCanonicalLookupRequest(
                        expectedGeometryRevision = currentCut.geometryRevision,
                        expectedLineageRevision = currentCut.lineageRevision,
                        maximumDirectLookups = 100_000,
                        maximumRayCellVisits = 65_536,
                        maximumPageReads = 65_536,
                        maximumBytesRead = 64L * 1024L * 1024L,
                    ),
                ) { view -> kernel.prepare(batch, view) }
                val preparedEvidence = ((lookup as? BoundedCanonicalLookupResult.Completed)?.value
                    as? DepthEvidenceResult.Accepted)
                    ?: error("synthetic depth map $mapIndex was refused by evidence kernel: $lookup")
                fusionMicros += elapsedMicros(fusionStartedNs, System.nanoTime())
                assertTrue("map $mapIndex produced no canonical changes", preparedEvidence.changes.isNotEmpty())

                val preparationStartedNs = System.nanoTime()
                val preparationAllocatedBefore = Debug.getRuntimeStat("art.gc.bytes-allocated")?.toLongOrNull()
                val mutation = resources.prepareEvidenceBatch(
                    CanonicalEvidenceBatchCommand(
                        commandId = "tablet-synthetic-depth-$mapIndex",
                        expectedGeometryRevision = preparedEvidence.expectedGeometryRevision,
                        expectedLineageRevision = preparedEvidence.expectedLineageRevision,
                        changes = preparedEvidence.changes,
                    ),
                ) as? CanonicalMutationPreparation.Prepared
                    ?: error("synthetic depth map $mapIndex canonical preparation was refused")
                preparationMicros += elapsedMicros(preparationStartedNs, System.nanoTime())
                preparationAllocatedBytes += preparationAllocatedBefore?.let { before ->
                    Debug.getRuntimeStat("art.gc.bytes-allocated")?.toLongOrNull()?.minus(before)
                }
                val commitStartedNs = System.nanoTime()
                val commitAllocatedBefore = Debug.getRuntimeStat("art.gc.bytes-allocated")?.toLongOrNull()
                val committed = resources.commitAdjacent(mutation.mutation)
                commitMicros += elapsedMicros(commitStartedNs, System.nanoTime())
                commitAllocatedBytes += commitAllocatedBefore?.let { before ->
                    Debug.getRuntimeStat("art.gc.bytes-allocated")?.toLongOrNull()?.minus(before)
                }
                assertTrue("synthetic depth map $mapIndex canonical commit refused: $committed",
                    committed is CanonicalAdjacentCommitResult.Committed)
                assertTrue(kernel.applyPrepared() is DepthEvidenceApplyResult.Applied)

                val state = requireNotNull(resources.owner().activationState())
                val current = state.current as? CanonicalActivationCurrent.Receipt
                    ?: error("synthetic depth map $mapIndex did not publish a canonical receipt")
                val ackStartedNs = System.nanoTime()
                val acknowledged = resources.owner().acknowledgeCanonicalCurrent(
                    CanonicalAcknowledgement(
                        current.identity.commandHash,
                        state.cut.geometryRevision,
                        state.cut.lineageRevision,
                    ),
                )
                ackMicros += elapsedMicros(ackStartedNs, System.nanoTime())
                assertTrue(
                    "synthetic depth map $mapIndex canonical ACK failed: $acknowledged",
                    acknowledged is CanonicalAcknowledgementResult.Acknowledged ||
                        acknowledged is CanonicalAcknowledgementResult.Idempotent,
                )
                committedMaps++
                endToEndMicros += elapsedMicros(mapStartedNs, System.nanoTime())
                mapAllocatedBytes += mapAllocatedBefore?.let { before ->
                    Debug.getRuntimeStat("art.gc.bytes-allocated")?.toLongOrNull()?.minus(before)
                }
                mapGcCounts += mapGcBefore?.let { before ->
                    Debug.getRuntimeStat("art.gc.gc-count")?.toLongOrNull()?.minus(before)
                }
                Log.i(
                    TAG,
                    "map=$mapIndex samples=${selected.value.samples.size} " +
                        "selection_us=${selectionMicros.last()} fusion_us=${fusionMicros.last()} " +
                        "preparation_us=${preparationMicros.last()} commit_us=${commitMicros.last()} " +
                        "preparation_allocated_bytes=${preparationAllocatedBytes.last()} " +
                        "commit_allocated_bytes=${commitAllocatedBytes.last()} ack_us=${ackMicros.last()} " +
                        "end_to_end_us=${endToEndMicros.last()} " +
                        "allocated_bytes=${mapAllocatedBytes.last()} gc_count=${mapGcCounts.last()} " +
                        "geometry=${state.cut.geometryRevision} lineage=${state.cut.lineageRevision}",
                )
            }
            val campaignDurationMs = SystemClock.elapsedRealtime() - campaignStartedMs
            val allocatedCampaignBytes = allocatedBefore?.let { before ->
                Debug.getRuntimeStat("art.gc.bytes-allocated")?.toLongOrNull()?.minus(before)
            }
            val maxEndToEndMicros = endToEndMicros.maxOrNull() ?: 0L
            val targetMet = endToEndMicros.all { it <= DEPTH_FRESHNESS_TARGET_MICROS }
            Log.i(
                TAG,
                "maps=$committedMaps selected_counts=$selectedCounts retained_samples=$retainedSamples " +
                    "selection_us=$selectionMicros fusion_us=$fusionMicros " +
                    "preparation_us=$preparationMicros commit_us=$commitMicros " +
                    "preparation_allocated_bytes=$preparationAllocatedBytes " +
                    "commit_allocated_bytes=$commitAllocatedBytes " +
                    "ack_us=$ackMicros end_to_end_us=$endToEndMicros " +
                    "max_end_to_end_us=$maxEndToEndMicros three_second_target_met=$targetMet " +
                    "campaign_duration_ms=$campaignDurationMs " +
                    "art_allocated_campaign_bytes=$allocatedCampaignBytes " +
                    "map_allocated_bytes=$mapAllocatedBytes map_gc_counts=$mapGcCounts",
            )
            assertEquals(MAP_COUNT, committedMaps)
            assertEquals(MAP_COUNT, selectionMicros.size)
            assertEquals(MAP_COUNT, fusionMicros.size)
            assertEquals(MAP_COUNT, preparationMicros.size)
            assertEquals(MAP_COUNT, commitMicros.size)
            assertEquals(MAP_COUNT, ackMicros.size)
            assertTrue("depth integration exceeded three seconds: $endToEndMicros", targetMet)
            assertTrue("canonical runtime retained payload", resources.retainedCompleteCurrentMemoryReceipt() != null)
        } finally {
            kernel.close()
            resources.close()
            coordinator.close()
            root.deleteRecursively()
        }
    }

    private fun materialSource(depth: ByteBuffer, confidence: ByteBuffer) = RawDepthCopySource(
        acquirer = object : PairedRawDepthAcquirer {
            override fun acquireDepth() = bufferImage(depth, isConfidence = false)
            override fun acquireConfidence() = bufferImage(confidence, isConfidence = true)
        },
        maxCopiedPixels = V2_DEPTH_SAMPLE_CAPACITY,
    )

    private fun bufferImage(buffer: ByteBuffer, isConfidence: Boolean) = object : RawDepthImage {
        override val width = IMAGE_WIDTH
        override val height = IMAGE_HEIGHT
        override fun unsignedValue(x: Int, y: Int): Int = if (isConfidence) {
            buffer.get(y * IMAGE_WIDTH + x).toInt() and 0xff
        } else {
            buffer.getShort((y * IMAGE_WIDTH + x) * 2).toInt() and 0xffff
        }
        override fun close() = Unit
    }

    private fun writeMaterialMap(depth: ByteBuffer, confidence: ByteBuffer, mapIndex: Int) {
        var index = 0
        while (index < IMAGE_WIDTH * IMAGE_HEIGHT) {
            val x = index % IMAGE_WIDTH
            val y = index / IMAGE_WIDTH
            val millimetres = if (x in 700..1_300 && y in 700..1_300) 900 else 1_200
            depth.putShort(index * 2, millimetres.toShort())
            confidence.put(index, 255.toByte())
            index++
        }
        val offset = mapIndex * 250
        patch(depth, 967 + offset, 968 + offset, 400)
        patch(depth, 993 + offset, 968 + offset, 600)
        patch(depth, 1_018 + offset, 968 + offset, 300)
    }

    private fun patch(depth: ByteBuffer, left: Int, top: Int, millimetres: Int) {
        for (y in top..top + 8) for (x in left..left + 8) {
            depth.putShort((y * IMAGE_WIDTH + x) * 2, millimetres.toShort())
        }
    }

    private fun metadata(timestampNs: Long) = RawDepthFrameMetadata(
        timestampNs = timestampNs,
        groupGeneration = 1,
        sessionGeneration = 1,
        tracking = true,
        width = IMAGE_WIDTH,
        height = IMAGE_HEIGHT,
        intrinsics = DepthIntrinsics(1_000.0, 1_000.0, 1_000.0, 1_000.0),
        worldFromCameraGl = identityTransform(),
    )

    private fun identityTransform() = DoubleArray(16) { if (it % 5 == 0) 1.0 else 0.0 }

    private fun elapsedMicros(startNs: Long, endNs: Long) =
        max(0L, (endNs - startNs) / 1_000L)

    private companion object {
        const val IMAGE_WIDTH = 2_000
        const val IMAGE_HEIGHT = 2_000
        const val MAP_COUNT = 3
        const val DEPTH_FRESHNESS_TARGET_MICROS = 3_000_000L
        const val TAG = "SyntheticDepthSession"
    }
}
