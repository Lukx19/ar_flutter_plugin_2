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
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Sustained tablet campaign for the camera-independent depth path.
 *
 * One live session receives many 2,000 x 2,000 maps through the production
 * RawDepthCopySource and DepthEvidenceKernel. The two direct image buffers are
 * reused for every map, so the reported allocation counters describe the
 * processing path rather than retaining a campaign-sized image history. The
 * final control phase performs real canonical MERGE commands through the same
 * live owner and records their preparation, commit, and ACK timings.
 *
 * This is diagnostic evidence. It deliberately reports real tablet timings and
 * ART/native heap counters without turning a particular device speed into a
 * production correctness gate.
 */
@RunWith(AndroidJUnit4::class)
class ManyDepthMapsSessionAndroidTest {
    @Test(timeout = 12 * 60 * 1_000L)
    fun manyReusedDepthMapsFuseAndCanonicalMergesCommitInOneLiveSession() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(context.cacheDir, "many-depth-maps-session-${System.nanoTime()}")
        check(root.mkdirs())
        val depthPixels = ByteBuffer.allocateDirect(IMAGE_WIDTH * IMAGE_HEIGHT * 2)
            .order(ByteOrder.LITTLE_ENDIAN)
        val confidencePixels = ByteBuffer.allocateDirect(IMAGE_WIDTH * IMAGE_HEIGHT)
        val group = SurfaceGroup("e".repeat(32))
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
        val intrinsics = VisibilityCameraIntrinsics(
            IMAGE_WIDTH, IMAGE_HEIGHT, 1_000.0, 1_000.0, 1_000.0, 1_000.0,
        )
        val source = reusableDepthSource(depthPixels, confidencePixels)
        val packedPool = DepthSamplesLeasePool()
        val mapTimings = ArrayList<StageTiming>(MAP_COUNT)
        val controlTimings = ArrayList<StageTiming>(MERGE_COUNT)
        val selectedCounts = ArrayList<Int>(MAP_COUNT)
        var committedMaps = 0
        var campaignStartedMs = 0L
        val campaignAllocatedBefore = runtimeStat("art.gc.bytes-allocated")
        val campaignGcBefore = gcSnapshot()
        val nativeHeapBefore = Debug.getNativeHeapAllocatedSize()
        try {
            assertTrue(
                "canonical runtime did not open",
                resources.openInitial(committedEmptyBaseline("tablet-many-depth", group.value, 1, 1, 1))
                    is SurfaceOwnershipOpenResult.Opened,
            )
            campaignStartedMs = SystemClock.elapsedRealtime()
            repeat(MAP_COUNT) { mapIndex ->
                writeRotatingMap(depthPixels, confidencePixels, mapIndex)
                val timestampNs = (mapIndex + 1L) * 3_000_000_000L
                val cameraPose = cameraPose(mapIndex)

                // The selector has no image retention requirement. Warm its source-local
                // neighborhood state before timing the admitted map, while reusing the same
                // direct buffers and RawDepthCopySource instance.
                val warmMetadata = metadata(timestampNs - 2_000_000_000L, cameraPose)
                val warmGeneration = SampleLeaseGeneration(1, warmMetadata.sessionGeneration, warmMetadata.groupGeneration, warmMetadata.timestampNs)
                (source.acquirePacked({ _, _ -> warmMetadata }, packedPool, warmGeneration) as? PackedDepthAcquisitionResult.Observation)
                    ?.lease?.close()
                val secondWarmMetadata = metadata(timestampNs - 1_000_000_000L, cameraPose)
                val secondWarmGeneration = SampleLeaseGeneration(1, secondWarmMetadata.sessionGeneration, secondWarmMetadata.groupGeneration, secondWarmMetadata.timestampNs)
                (source.acquirePacked({ _, _ -> secondWarmMetadata }, packedPool, secondWarmGeneration) as? PackedDepthAcquisitionResult.Observation)
                    ?.lease?.close()

                val allocatedBefore = runtimeStat("art.gc.bytes-allocated")
                val gcBefore = gcSnapshot()
                val nativeBefore = Debug.getNativeHeapAllocatedSize()
                val startedNs = System.nanoTime()
                val selectionStartedNs = System.nanoTime()
                val selectionAllocatedBefore = runtimeStat("art.gc.bytes-allocated")
                val selectedMetadata = metadata(timestampNs, cameraPose)
                val selectedGeneration = SampleLeaseGeneration(1, selectedMetadata.sessionGeneration, selectedMetadata.groupGeneration, selectedMetadata.timestampNs)
                val selected = source.acquirePacked({ _, _ -> selectedMetadata }, packedPool, selectedGeneration)
                    as? PackedDepthAcquisitionResult.Observation
                    ?: error("depth map $mapIndex was not selected")
                val selectionMicros = elapsedMicros(selectionStartedNs, System.nanoTime())
                val selectionAllocatedBytes = delta(selectionAllocatedBefore, runtimeStat("art.gc.bytes-allocated"))
                selectedCounts += selected.lease.samples.count
                assertTrue("map $mapIndex was over sample cap", selected.lease.samples.count <= V2_DEPTH_SAMPLE_CAPACITY)
                assertTrue("map $mapIndex lost retained detail", selected.lease.samples.count > 1_536)
                assertTrue(
                    "map $mapIndex lost a foreground layer",
                    (0 until selected.lease.samples.count).any { index ->
                        selected.lease.samples.depthMillimetresAt(index) in 300..350 ||
                            selected.lease.samples.depthMillimetresAt(index) in 400..450 ||
                            selected.lease.samples.depthMillimetresAt(index) in 600..650
                    },
                )
                val batch = DepthEvidenceMetadata(
                    sequence = mapIndex.toLong() + 1L,
                    sourceTimestampNs = timestampNs,
                    groupFrame = groupFrame,
                    groupFromCameraGl = selected.metadata.worldFromCameraGl.toList(),
                    intrinsics = intrinsics,
                    sourceRejectedSamples = selected.lease.samples.rejectedCount,
                    tracking = selected.metadata.tracking,
                )

                val fusionStartedNs = System.nanoTime()
                val fusionAllocatedBefore = runtimeStat("art.gc.bytes-allocated")
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
                ) { view ->
                    try {
                        kernel.prepare(selected.lease.samples, batch, view)
                    } finally {
                        selected.lease.close()
                    }
                }
                val preparedEvidence = ((lookup as? BoundedCanonicalLookupResult.Completed)?.value
                    as? DepthEvidenceResult.Accepted)
                    ?: error("depth map $mapIndex was refused by the evidence kernel: $lookup")
                val fusionMicros = elapsedMicros(fusionStartedNs, System.nanoTime())
                val fusionAllocatedBytes = delta(fusionAllocatedBefore, runtimeStat("art.gc.bytes-allocated"))
                assertTrue("map $mapIndex produced no canonical changes", preparedEvidence.changes.isNotEmpty())

                val preparationStartedNs = System.nanoTime()
                val preparationAllocatedBefore = runtimeStat("art.gc.bytes-allocated")
                val prepared = resources.prepareEvidenceBatch(
                    CanonicalEvidenceBatchCommand(
                        commandId = "tablet-many-depth-$mapIndex",
                        expectedGeometryRevision = preparedEvidence.expectedGeometryRevision,
                        expectedLineageRevision = preparedEvidence.expectedLineageRevision,
                        changes = preparedEvidence.changes,
                    ),
                ) as? CanonicalMutationPreparation.Prepared
                    ?: error("depth map $mapIndex canonical preparation was refused")
                val preparationMicros = elapsedMicros(preparationStartedNs, System.nanoTime())
                val preparationAllocatedBytes = delta(preparationAllocatedBefore, runtimeStat("art.gc.bytes-allocated"))

                val commitStartedNs = System.nanoTime()
                val commitAllocatedBefore = runtimeStat("art.gc.bytes-allocated")
                val committed = resources.commitAdjacent(prepared.mutation)
                assertTrue("depth map $mapIndex canonical commit refused: $committed",
                    committed is CanonicalAdjacentCommitResult.Committed)
                assertTrue(kernel.applyPrepared(prepared.mutation) is DepthEvidenceApplyResult.Applied)
                val commitMicros = elapsedMicros(commitStartedNs, System.nanoTime())
                val commitAllocatedBytes = delta(commitAllocatedBefore, runtimeStat("art.gc.bytes-allocated"))

                val state = requireNotNull(resources.owner().activationState())
                val current = state.current as? CanonicalActivationCurrent.Receipt
                    ?: error("depth map $mapIndex did not publish a canonical receipt")
                val ackStartedNs = System.nanoTime()
                val acknowledgementAllocatedBefore = runtimeStat("art.gc.bytes-allocated")
                val acknowledged = resources.owner().acknowledgeCanonicalCurrent(
                    CanonicalAcknowledgement(
                        current.identity.commandHash,
                        state.cut.geometryRevision,
                        state.cut.lineageRevision,
                    ),
                )
                val ackMicros = elapsedMicros(ackStartedNs, System.nanoTime())
                val acknowledgementAllocatedBytes = delta(
                    acknowledgementAllocatedBefore,
                    runtimeStat("art.gc.bytes-allocated"),
                )
                assertTrue(
                    "depth map $mapIndex canonical ACK failed: $acknowledged",
                    acknowledged is CanonicalAcknowledgementResult.Acknowledged ||
                        acknowledged is CanonicalAcknowledgementResult.Idempotent,
                )
                committedMaps++
                val timing = StageTiming(
                    operation = "depth-$mapIndex",
                    selectionMicros = selectionMicros,
                    fusionMicros = fusionMicros,
                    preparationMicros = preparationMicros,
                    commitMicros = commitMicros,
                    acknowledgementMicros = ackMicros,
                    endToEndMicros = elapsedMicros(startedNs, System.nanoTime()),
                    allocatedBytes = delta(allocatedBefore, runtimeStat("art.gc.bytes-allocated")),
                    gcCount = delta(gcBefore.count, gcSnapshot().count),
                    gcTime = delta(gcBefore.time, gcSnapshot().time),
                    nativeHeapBefore = nativeBefore,
                    nativeHeapAfter = Debug.getNativeHeapAllocatedSize(),
                    selectionAllocatedBytes = selectionAllocatedBytes,
                    fusionAllocatedBytes = fusionAllocatedBytes,
                    preparationAllocatedBytes = preparationAllocatedBytes,
                    commitAllocatedBytes = commitAllocatedBytes,
                    acknowledgementAllocatedBytes = acknowledgementAllocatedBytes,
                )
                mapTimings += timing
                Log.i(TAG, timing.toLogLine("depth"))
            }

            // Ensure the timing campaign also covers real canonical merge commands. These
            // source rows come from the depth-derived cut, while their target voxels are
            // deliberately separate so a merge count cannot be mistaken for fusion output.
            val beforeControl = requireNotNull(resources.owner().activationState()).cut
            val controlSourceIds = ArrayList<SurfaceId>(CONTROL_SURFACE_COUNT)
            resources.withCurrent { view ->
                var candidate = 1L
                while (candidate < beforeControl.nextSurfaceIdHighWater &&
                    controlSourceIds.size < CONTROL_SURFACE_COUNT
                ) {
                    val id = SurfaceId(candidate)
                    if (view.findById(id) != null) controlSourceIds += id
                    candidate++
                }
            }
            assertEquals(CONTROL_SURFACE_COUNT, controlSourceIds.size)
            repeat(MERGE_COUNT) { mergeIndex ->
                val currentCut = requireNotNull(resources.owner().activationState()).cut
                val first = controlSourceIds[mergeIndex * 2]
                val second = controlSourceIds[mergeIndex * 2 + 1]
                commitCanonicalCommand(
                    resources,
                    CanonicalTransactionCommand(
                        commandId = "tablet-many-depth-merge-$mergeIndex",
                        kind = CanonicalOperation.MERGE,
                        expectedGeometryRevision = currentCut.geometryRevision,
                        expectedLineageRevision = currentCut.lineageRevision,
                        sourceIds = listOf(first, second),
                        targets = listOf(target(null, Voxel(CONTROL_MERGE_X + mergeIndex % CONTROL_GRID_WIDTH,
                            CONTROL_MERGE_Y + mergeIndex / CONTROL_GRID_WIDTH, CONTROL_MERGE_Z))),
                    ),
                    controlTimings,
                )
            }
            assertEquals(MERGE_COUNT, controlTimings.size)
            // The 128 source rows are already part of the depth-derived cut. Each
            // two-source merge replaces them with one target, reducing the live
            // count by one; no additional source rows are created here.
            assertEquals(
                beforeControl.liveSurfaceCount - MERGE_COUNT,
                requireNotNull(resources.owner().activationState()).cut.liveSurfaceCount,
            )
            val actualMergeTimings = controlTimings
            Log.i(
                TAG,
                "merge_summary ${summary("merge", actualMergeTimings.map { it.endToEndMicros })} " +
                    "preparation_allocations=${allocationSummary(actualMergeTimings.map { it.preparationAllocatedBytes })} " +
                    "commit_allocations=${allocationSummary(actualMergeTimings.map { it.commitAllocatedBytes })} " +
                    "ack_allocations=${allocationSummary(actualMergeTimings.map { it.acknowledgementAllocatedBytes })}",
            )

            val finalCut = requireNotNull(resources.owner().activationState()).cut
            val campaignDurationMs = SystemClock.elapsedRealtime() - campaignStartedMs
            val campaignAllocated = delta(campaignAllocatedBefore, runtimeStat("art.gc.bytes-allocated"))
            val campaignGc = gcSnapshot()
            val nativeHeapAfter = Debug.getNativeHeapAllocatedSize()
            Log.i(
                TAG,
                "campaign maps=$committedMaps yaw_views=$YAW_VIEW_COUNT yaw_sweeps=2 " +
                    "merges=$MERGE_COUNT control_surfaces=$CONTROL_SURFACE_COUNT selected_counts=$selectedCounts " +
                    "duration_ms=$campaignDurationMs art_allocated_bytes=$campaignAllocated " +
                    "gc_count=${delta(campaignGcBefore.count, campaignGc.count)} " +
                    "gc_time=${delta(campaignGcBefore.time, campaignGc.time)} " +
                    "native_heap_before=$nativeHeapBefore native_heap_after=$nativeHeapAfter " +
                    "final_live_surfaces=${finalCut.liveSurfaceCount} " +
                    "final_geometry=${finalCut.geometryRevision} final_lineage=${finalCut.lineageRevision} " +
                    "depth_end_to_end=${summary("depth", mapTimings.map { it.endToEndMicros })} " +
                    "depth_selection=${summary("selection", mapTimings.map { it.selectionMicros })} " +
                    "depth_fusion=${summary("fusion", mapTimings.map { it.fusionMicros })} " +
                    "depth_preparation=${summary("preparation", mapTimings.map { it.preparationMicros })} " +
                    "depth_commit=${summary("commit", mapTimings.map { it.commitMicros })} " +
                    "depth_ack=${summary("ack", mapTimings.map { it.acknowledgementMicros })} " +
                    "depth_stage_allocations=" +
                    "selection=${allocationSummary(mapTimings.map { it.selectionAllocatedBytes })} " +
                    "fusion=${allocationSummary(mapTimings.map { it.fusionAllocatedBytes })} " +
                    "preparation=${allocationSummary(mapTimings.map { it.preparationAllocatedBytes })} " +
                    "commit=${allocationSummary(mapTimings.map { it.commitAllocatedBytes })} " +
                    "ack=${allocationSummary(mapTimings.map { it.acknowledgementAllocatedBytes })} " +
                    "merge_end_to_end=${summary("merge", actualMergeTimings.map { it.endToEndMicros })} " +
                    "merge_stage_allocations=" +
                    "preparation=${allocationSummary(actualMergeTimings.map { it.preparationAllocatedBytes })} " +
                    "commit=${allocationSummary(actualMergeTimings.map { it.commitAllocatedBytes })} " +
                    "ack=${allocationSummary(actualMergeTimings.map { it.acknowledgementAllocatedBytes })} " +
                    "retained_bytes=${resources.retainedCompleteCurrentMemoryReceipt()?.residentTotalBytes}",
            )
            assertEquals(MAP_COUNT, committedMaps)
            assertEquals(MAP_COUNT, mapTimings.size)
            assertEquals(MERGE_COUNT, controlTimings.size)
            assertTrue(resources.retainedCompleteCurrentMemoryReceipt() != null)
        } finally {
            kernel.close()
            packedPool.close()
            resources.close()
            coordinator.close()
            root.deleteRecursively()
        }
    }

    @Test(timeout = 12 * 60 * 1_000L)
    fun overlapScenariosMeasureEndpointReuseAndRefinementEvidenceInOneSession() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(context.cacheDir, "depth-overlap-scenarios-${System.nanoTime()}")
        check(root.mkdirs())
        val depthPixels = ByteBuffer.allocateDirect(IMAGE_WIDTH * IMAGE_HEIGHT * 2)
            .order(ByteOrder.LITTLE_ENDIAN)
        val confidencePixels = ByteBuffer.allocateDirect(IMAGE_WIDTH * IMAGE_HEIGHT)
        val group = SurfaceGroup("f".repeat(32))
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
        val intrinsics = VisibilityCameraIntrinsics(
            IMAGE_WIDTH, IMAGE_HEIGHT, 1_000.0, 1_000.0, 1_000.0, 1_000.0,
        )
        val source = reusableDepthSource(depthPixels, confidencePixels)
        val writer = ReusableDepthFixture(depthPixels, confidencePixels)
        val maps = overlapMaps()
        val timings = ArrayList<OverlapTiming>(maps.size)
        val campaignAllocatedBefore = runtimeStat("art.gc.bytes-allocated")
        val campaignGcBefore = gcSnapshot()
        val nativeHeapBefore = Debug.getNativeHeapAllocatedSize()
        var campaignStartedMs = 0L
        try {
            assertTrue(
                "canonical runtime did not open",
                resources.openInitial(committedEmptyBaseline("tablet-depth-overlap", group.value, 1, 1, 1))
                    is SurfaceOwnershipOpenResult.Opened,
            )
            campaignStartedMs = SystemClock.elapsedRealtime()
            maps.forEachIndexed { mapIndex, map ->
                val writeSummary = writer.write(map, intrinsics)
                assertTrue(
                    "${map.caseName} wrote no projected depth samples",
                    writeSummary.writtenPoints > 32,
                )
                assertTrue(
                    "${map.caseName} projected no valid pixels: " +
                        "written=${writeSummary.writtenPoints}",
                    writeSummary.projectedValidPixels > 0,
                )
                val timestampNs = (mapIndex + 1L) * 3_000_000_000L
                val currentMetadata = metadata(timestampNs, map.cameraPose)
                source.acquire(metadata(timestampNs - 2_000_000_000L, map.cameraPose))
                source.acquire(metadata(timestampNs - 1_000_000_000L, map.cameraPose))

                val allocatedBefore = runtimeStat("art.gc.bytes-allocated")
                val gcBefore = gcSnapshot()
                val nativeBefore = Debug.getNativeHeapAllocatedSize()
                val startedNs = System.nanoTime()
                val selectionStartedNs = System.nanoTime()
                val selectionAllocatedBefore = runtimeStat("art.gc.bytes-allocated")
                val selected = source.acquire(currentMetadata) as? DepthAcquisitionResult.Observation
                    ?: error("${map.caseName} map $mapIndex was not selected")
                val selectionMicros = elapsedMicros(selectionStartedNs, System.nanoTime())
                val selectionAllocatedBytes = delta(
                    selectionAllocatedBefore,
                    runtimeStat("art.gc.bytes-allocated"),
                )
                assertTrue(
                    "${map.caseName} selected no valid samples: " +
                        "written=${writeSummary.writtenPoints} " +
                        "projected_valid=${writeSummary.projectedValidPixels}",
                    selected.value.samples.isNotEmpty(),
                )
                assertTrue(
                    "${map.caseName} selected more samples than projected-valid pixels: " +
                        "selected=${selected.value.samples.size} " +
                        "projected_valid=${writeSummary.projectedValidPixels}",
                    selected.value.samples.size <= writeSummary.projectedValidPixels,
                )
                val samples = ArrayList<VisibilityDepthSample>(selected.value.samples.size)
                selected.value.samples.forEach { sample ->
                    samples += VisibilityDepthSample(sample.x, sample.y, sample.depthMillimeters, sample.confidence)
                }
                val batch = DepthEvidenceBatch(
                    sequence = mapIndex.toLong() + 1L,
                    sourceTimestampNs = timestampNs,
                    groupFrame = groupFrame,
                    groupFromCameraGl = selected.value.worldFromCameraGl.toList(),
                    intrinsics = intrinsics,
                    samples = samples,
                    sourceRejectedSamples = selected.value.sourceRejectedPixels,
                )

                assertTrue("${map.caseName} spatial window refused", resources.updateSpatialWindow(batch))
                val currentCut = requireNotNull(resources.owner().activationState()).cut
                val overlap = measureEndpointOverlap(
                    resources,
                    CanonicalRevisionPair(currentCut.geometryRevision, currentCut.lineageRevision),
                    batch,
                )
                assertTrue("${map.caseName} produced no measurable endpoints", overlap.total > 0)
                if (map.caseName == EXACT_REVISIT_CASE) {
                    assertEquals("exact revisit lost endpoint occupancy", overlap.total, overlap.existing)
                }
                if (map.caseName == NEARER_OCCLUDER_CASE ||
                    map.caseName == FARTHER_DEPTH_CONFLICT_CASE
                ) {
                    assertTrue(
                        "${map.caseName} did not move any endpoint voxels",
                        overlap.existing < overlap.total,
                    )
                }
                val fusionStartedNs = System.nanoTime()
                val fusionAllocatedBefore = runtimeStat("art.gc.bytes-allocated")
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
                    ?: error("${map.caseName} map $mapIndex was refused: $lookup")
                Log.i(TAG, "${map.caseName} canonical_batch ${canonicalBatchSummary(preparedEvidence.changes)}")
                val fusionMicros = elapsedMicros(fusionStartedNs, System.nanoTime())
                val fusionAllocatedBytes = delta(
                    fusionAllocatedBefore,
                    runtimeStat("art.gc.bytes-allocated"),
                )

                var preparationMicros: Long? = null
                var commitMicros: Long? = null
                var acknowledgementMicros: Long? = null
                var preparationAllocatedBytes: Long? = null
                var commitAllocatedBytes: Long? = null
                var acknowledgementAllocatedBytes: Long? = null
                val applyStartedNs = System.nanoTime()
                val applyAllocatedBefore = runtimeStat("art.gc.bytes-allocated")
                if (preparedEvidence.changes.isEmpty()) {
                    assertTrue(
                        "${map.caseName} accepted evidence was not retained",
                        kernel.applyPrepared() is DepthEvidenceApplyResult.Applied,
                    )
                } else {
                    val preparationStartedNs = System.nanoTime()
                    val preparationAllocationStart = runtimeStat("art.gc.bytes-allocated")
                    val prepared = resources.prepareEvidenceBatch(
                        CanonicalEvidenceBatchCommand(
                            commandId = "tablet-depth-overlap-$mapIndex",
                            expectedGeometryRevision = preparedEvidence.expectedGeometryRevision,
                            expectedLineageRevision = preparedEvidence.expectedLineageRevision,
                            changes = preparedEvidence.changes,
                        ),
                    )
                    val canonicalPrepared = prepared as? CanonicalMutationPreparation.Prepared
                        ?: error("${map.caseName} canonical preparation was refused: $prepared")
                    preparationMicros = elapsedMicros(preparationStartedNs, System.nanoTime())
                    preparationAllocatedBytes = delta(
                        preparationAllocationStart,
                        runtimeStat("art.gc.bytes-allocated"),
                    )
                    val commitStartedNs = System.nanoTime()
                    val commitAllocationStart = runtimeStat("art.gc.bytes-allocated")
                    val committed = resources.commitAdjacent(canonicalPrepared.mutation)
                    assertTrue(
                        "${map.caseName} canonical commit refused: $committed",
                        committed is CanonicalAdjacentCommitResult.Committed,
                    )
                    assertTrue(
                        "${map.caseName} accepted evidence was not retained",
                        kernel.applyPrepared(canonicalPrepared.mutation) is DepthEvidenceApplyResult.Applied,
                    )
                    commitMicros = elapsedMicros(commitStartedNs, System.nanoTime())
                    commitAllocatedBytes = delta(
                        commitAllocationStart,
                        runtimeStat("art.gc.bytes-allocated"),
                    )
                    val current = requireNotNull(resources.owner().activationState())
                    val receipt = current.current as CanonicalActivationCurrent.Receipt
                    val acknowledgementStartedNs = System.nanoTime()
                    val acknowledgementAllocationStart = runtimeStat("art.gc.bytes-allocated")
                    val acknowledged = resources.owner().acknowledgeCanonicalCurrent(
                        CanonicalAcknowledgement(
                            receipt.identity.commandHash,
                            current.cut.geometryRevision,
                            current.cut.lineageRevision,
                        ),
                    )
                    assertTrue(
                        "${map.caseName} canonical ACK failed: $acknowledged",
                        acknowledged is CanonicalAcknowledgementResult.Acknowledged ||
                            acknowledged is CanonicalAcknowledgementResult.Idempotent,
                    )
                    acknowledgementMicros = elapsedMicros(acknowledgementStartedNs, System.nanoTime())
                    acknowledgementAllocatedBytes = delta(
                        acknowledgementAllocationStart,
                        runtimeStat("art.gc.bytes-allocated"),
                    )
                }
                val applyMicros = elapsedMicros(applyStartedNs, System.nanoTime())
                val applyAllocatedBytes = delta(applyAllocatedBefore, runtimeStat("art.gc.bytes-allocated"))
                val timing = OverlapTiming(
                    caseName = map.caseName,
                    mapIndex = mapIndex,
                    writtenPoints = writeSummary.writtenPoints,
                    projectedValidPixels = writeSummary.projectedValidPixels,
                    selectedSamples = batch.samples.size,
                    overlap = overlap,
                    receipt = preparedEvidence.receipt,
                    material = preparedEvidence.changes.isNotEmpty(),
                    selectionMicros = selectionMicros,
                    fusionMicros = fusionMicros,
                    preparationMicros = preparationMicros,
                    commitMicros = commitMicros,
                    acknowledgementMicros = acknowledgementMicros,
                    applyMicros = applyMicros,
                    endToEndMicros = elapsedMicros(startedNs, System.nanoTime()),
                    allocatedBytes = delta(allocatedBefore, runtimeStat("art.gc.bytes-allocated")),
                    gcCount = delta(gcBefore.count, gcSnapshot().count),
                    gcTime = delta(gcBefore.time, gcSnapshot().time),
                    nativeHeapBefore = nativeBefore,
                    nativeHeapAfter = Debug.getNativeHeapAllocatedSize(),
                    selectionAllocatedBytes = selectionAllocatedBytes,
                    fusionAllocatedBytes = fusionAllocatedBytes,
                    preparationAllocatedBytes = preparationAllocatedBytes,
                    commitAllocatedBytes = commitAllocatedBytes,
                    acknowledgementAllocatedBytes = acknowledgementAllocatedBytes,
                    applyAllocatedBytes = applyAllocatedBytes,
                )
                timings += timing
                Log.i(TAG, timing.toLogLine())
            }

            val campaignDurationMs = SystemClock.elapsedRealtime() - campaignStartedMs
            val campaignGc = gcSnapshot()
            val finalCut = requireNotNull(resources.owner().activationState()).cut
            val campaignAllocated = delta(campaignAllocatedBefore, runtimeStat("art.gc.bytes-allocated"))
            val campaignAllocatedPerMinute = if (campaignAllocated != null && campaignDurationMs > 0L) {
                campaignAllocated * 60_000L / campaignDurationMs
            } else null
            val caseGroups = timings.groupBy { it.caseName }
            listOf(LOW_OVERLAP_CASE, MEDIUM_OVERLAP_CASE, HIGH_OVERLAP_CASE).forEach { caseName ->
                val values = caseGroups[caseName].orEmpty()
                assertEquals("$caseName band count changed", 2, values.size)
                assertTrue(
                    "$caseName band has no reused endpoint",
                    values.any { it.overlap.existing > 0 },
                )
                assertTrue(
                    "$caseName band discovered no canonical changes",
                    values.any { it.receipt.createCount > 0 || it.receipt.refineCount > 0 },
                )
            }
            listOf(NEARER_OCCLUDER_CASE, FARTHER_DEPTH_CONFLICT_CASE).forEach { caseName ->
                assertTrue(
                    "$caseName retained no geometric conflict",
                    caseGroups[caseName].orEmpty().any { it.receipt.conflictsRetained > 0 },
                )
            }
            caseGroups.forEach { (caseName, values) ->
                Log.i(
                    TAG,
                    "overlap_case_summary case=$caseName maps=${values.size} " +
                        "written_points=${values.sumOf { it.writtenPoints }} " +
                        "projected_valid_pixels=${values.sumOf { it.projectedValidPixels }} " +
                        "selected_samples=${values.sumOf { it.selectedSamples }} " +
                        "endpoint_existing=${values.sumOf { it.overlap.existing }} " +
                        "endpoint_total=${values.sumOf { it.overlap.total }} " +
                        "endpoint_overlap_bp=${overlapBasisPoints(values.map { it.overlap })} " +
                        "receipt_create=${values.sumOf { it.receipt.createCount }} " +
                        "receipt_refine=${values.sumOf { it.receipt.refineCount }} " +
                        "receipt_relocate=${values.sumOf { it.receipt.relocateCount }} " +
                        "receipt_merge=${values.sumOf { it.receipt.mergeCount }} " +
                        "receipt_split=${values.sumOf { it.receipt.splitCount }} " +
                        "receipt_replace=${values.sumOf { it.receipt.replaceCount }} " +
                        "receipt_remove=${values.sumOf { it.receipt.removeCount }} " +
                        "receipt_conflicts=${values.sumOf { it.receipt.conflictsRetained }} " +
                        "material_maps=${values.count { it.material }} " +
                        "gc_count=${values.mapNotNull { it.gcCount }.sum()} " +
                        "gc_time=${values.mapNotNull { it.gcTime }.sum()} " +
                        "end_to_end=${summary("end_to_end", values.map { it.endToEndMicros })} " +
                        "fusion=${summary("fusion", values.map { it.fusionMicros })} " +
                        "apply=${summary("apply", values.map { it.applyMicros })} " +
                        "preparation=${nullableSummary(values.map { it.preparationMicros })} " +
                        "commit=${nullableSummary(values.map { it.commitMicros })} " +
                        "ack=${nullableSummary(values.map { it.acknowledgementMicros })} " +
                        "stage_allocations=" +
                        "selection=${allocationSummary(values.map { it.selectionAllocatedBytes })} " +
                        "fusion=${allocationSummary(values.map { it.fusionAllocatedBytes })} " +
                        "preparation=${allocationSummary(values.map { it.preparationAllocatedBytes })} " +
                        "commit=${allocationSummary(values.map { it.commitAllocatedBytes })} " +
                        "ack=${allocationSummary(values.map { it.acknowledgementAllocatedBytes })} " +
                        "apply=${allocationSummary(values.map { it.applyAllocatedBytes })}",
                )
            }
            Log.i(
                TAG,
                "overlap_campaign maps=${timings.size} duration_ms=$campaignDurationMs " +
                    "art_allocated_bytes=$campaignAllocated " +
                    "art_allocated_bytes_per_minute=$campaignAllocatedPerMinute " +
                    "gc_count=${delta(campaignGcBefore.count, campaignGc.count)} " +
                    "gc_time=${delta(campaignGcBefore.time, campaignGc.time)} " +
                    "native_heap_before=$nativeHeapBefore " +
                    "native_heap_after=${Debug.getNativeHeapAllocatedSize()} " +
                    "final_live_surfaces=${finalCut.liveSurfaceCount}",
            )
            assertEquals(maps.size, timings.size)
            val exactRevisit = timings.filter { it.caseName == EXACT_REVISIT_CASE }
            assertEquals("exact revisit map count changed", 1, exactRevisit.size)
            assertTrue(
                "exact revisit lost endpoint occupancy",
                exactRevisit.all { it.overlap.existing == it.overlap.total },
            )
            assertTrue(
                "exact revisit changed canonical structure",
                exactRevisit.all { timing ->
                    timing.receipt.createCount == 0 &&
                        timing.receipt.relocateCount == 0 &&
                        timing.receipt.mergeCount == 0 &&
                        timing.receipt.splitCount == 0 &&
                        timing.receipt.replaceCount == 0 &&
                        timing.receipt.removeCount == 0
                },
            )
        } finally {
            kernel.close()
            resources.close()
            coordinator.close()
            root.deleteRecursively()
        }
    }

    private fun commitCanonicalCommand(
        resources: CanonicalRuntimeResources,
        command: CanonicalTransactionCommand,
        timings: MutableList<StageTiming>,
    ) {
        val allocatedBefore = runtimeStat("art.gc.bytes-allocated")
        val gcBefore = gcSnapshot()
        val nativeBefore = Debug.getNativeHeapAllocatedSize()
        val startedNs = System.nanoTime()
        val preparationStartedNs = System.nanoTime()
        val preparationAllocatedBefore = runtimeStat("art.gc.bytes-allocated")
        val preparation = resources.withCurrent {
            resources.owner().prepareAdjacentMutation(it, command)
        }
        val prepared = preparation as? CanonicalMutationPreparation.Prepared
            ?: error("canonical ${command.kind} preparation was refused: $preparation")
        val preparationMicros = elapsedMicros(preparationStartedNs, System.nanoTime())
        val preparationAllocatedBytes = delta(preparationAllocatedBefore, runtimeStat("art.gc.bytes-allocated"))
        val commitStartedNs = System.nanoTime()
        val commitAllocatedBefore = runtimeStat("art.gc.bytes-allocated")
        val committed = resources.commitAdjacent(prepared.mutation)
        assertTrue("canonical ${command.kind} commit refused: $committed",
            committed is CanonicalAdjacentCommitResult.Committed)
        val commitMicros = elapsedMicros(commitStartedNs, System.nanoTime())
        val commitAllocatedBytes = delta(commitAllocatedBefore, runtimeStat("art.gc.bytes-allocated"))
        val current = requireNotNull(resources.owner().activationState())
        val receipt = current.current as CanonicalActivationCurrent.Receipt
        val ackStartedNs = System.nanoTime()
        val acknowledgementAllocatedBefore = runtimeStat("art.gc.bytes-allocated")
        val acknowledged = resources.owner().acknowledgeCanonicalCurrent(
            CanonicalAcknowledgement(receipt.identity.commandHash, current.cut.geometryRevision, current.cut.lineageRevision),
        )
        val acknowledgementMicros = elapsedMicros(ackStartedNs, System.nanoTime())
        val acknowledgementAllocatedBytes = delta(
            acknowledgementAllocatedBefore,
            runtimeStat("art.gc.bytes-allocated"),
        )
        assertTrue(
            "canonical ${command.kind} ACK failed: $acknowledged",
            acknowledged is CanonicalAcknowledgementResult.Acknowledged ||
                acknowledged is CanonicalAcknowledgementResult.Idempotent,
        )
        timings += StageTiming(
            operation = command.commandId,
            selectionMicros = 0,
            fusionMicros = 0,
            preparationMicros = preparationMicros,
            commitMicros = commitMicros,
            acknowledgementMicros = acknowledgementMicros,
            endToEndMicros = elapsedMicros(startedNs, System.nanoTime()),
            allocatedBytes = delta(allocatedBefore, runtimeStat("art.gc.bytes-allocated")),
            gcCount = delta(gcBefore.count, gcSnapshot().count),
            gcTime = delta(gcBefore.time, gcSnapshot().time),
            nativeHeapBefore = nativeBefore,
            nativeHeapAfter = Debug.getNativeHeapAllocatedSize(),
            selectionAllocatedBytes = null,
            fusionAllocatedBytes = null,
            preparationAllocatedBytes = preparationAllocatedBytes,
            commitAllocatedBytes = commitAllocatedBytes,
            acknowledgementAllocatedBytes = acknowledgementAllocatedBytes,
        )
        Log.i(TAG, timings.last().toLogLine("canonical"))
    }

    private fun measureEndpointOverlap(
        resources: CanonicalRuntimeResources,
        revisionPair: CanonicalRevisionPair,
        batch: DepthEvidenceBatch,
    ): EndpointOverlap {
        var total = 0
        var existing = 0
        val lookup = resources.withBoundedCurrent(
            BoundedCanonicalLookupRequest(
                expectedGeometryRevision = revisionPair.geometryRevision,
                expectedLineageRevision = revisionPair.lineageRevision,
                maximumDirectLookups = batch.samples.size + 1,
                maximumRayCellVisits = 0,
                maximumPageReads = 65_536,
                maximumBytesRead = 64L * 1024L * 1024L,
            ),
        ) { view ->
            batch.samples.forEach { sample ->
                val voxel = endpointVoxel(sample, batch)
                if (voxel != null) {
                    total++
                    if (view.findSurfaceAt(voxel) != null) existing++
                }
            }
            Unit
        }
        check(lookup is BoundedCanonicalLookupResult.Completed) {
            "endpoint occupancy probe was refused: $lookup"
        }
        return EndpointOverlap(existing, total)
    }

    /** Mirrors the kernel's column-major depth projection for diagnostic overlap only. */
    private fun endpointVoxel(sample: VisibilityDepthSample, batch: DepthEvidenceBatch): Voxel? {
        val cameraPoint = DepthPointMm(
            (sample.x - batch.intrinsics.cx) * sample.depthMillimeters / batch.intrinsics.fx,
            -(sample.y - batch.intrinsics.cy) * sample.depthMillimeters / batch.intrinsics.fy,
            -sample.depthMillimeters.toDouble(),
        )
        val matrix = batch.groupFromCameraGl
        val x = cameraPoint.x / 1_000.0
        val y = cameraPoint.y / 1_000.0
        val z = cameraPoint.z / 1_000.0
        val tx = matrix[0] * x + matrix[4] * y + matrix[8] * z + matrix[12]
        val ty = matrix[1] * x + matrix[5] * y + matrix[9] * z + matrix[13]
        val tz = matrix[2] * x + matrix[6] * y + matrix[10] * z + matrix[14]
        val tw = matrix[3] * x + matrix[7] * y + matrix[11] * z + matrix[15]
        if (!tx.isFinite() || !ty.isFinite() || !tz.isFinite() || !tw.isFinite() || tw == 0.0) return null
        return DepthVoxelAddressing.quantize(
            DepthPointMm(tx * 1_000.0 / tw, ty * 1_000.0 / tw, tz * 1_000.0 / tw),
            batch.groupFrame.voxelSizeMicrometres,
        )
    }

    private fun overlapMaps(): List<OverlapMap> {
        val scene = ArrayList<ScenePoint>(SCENE_POINT_COUNT)
        for (layer in 0 until SCENE_DEPTH_LAYERS) {
            // Half-voxel offsets keep pixel-rounding diagnostics away from a
            // quantization boundary while preserving a static layered scene.
            val depth = 1_550.0 + layer * 200.0
            for (yIndex in 0 until SCENE_ROWS) {
                for (xIndex in 0 until SCENE_COLUMNS) {
                    scene += ScenePoint(
                        xMm = -1_600.0 + xIndex * 40.0 + layer * 30.0,
                        yMm = -1_200.0 + yIndex * 40.0 + layer * 30.0,
                        zMm = -depth,
                    )
                }
            }
        }
        val planeSize = SCENE_COLUMNS * SCENE_ROWS
        val bootstrap = scene.subList(0, planeSize).toList()
        val lowOne = scene.subList(planeSize, planeSize * 2).toList() + bootstrap.take(512)
        val lowTwo = scene.subList(planeSize * 2, planeSize * 3).toList() + bootstrap.take(1_024)
        val knownAfterLow = bootstrap + lowOne + lowTwo
        val thirdPlane = scene.subList(planeSize * 3, planeSize * 4)
        val mediumOne = thirdPlane.take(2_560) + knownAfterLow.take(2_560)
        val mediumTwo = thirdPlane.drop(2_560) + lowOne.take(2_560)
        val knownAfterMedium = knownAfterLow + mediumOne + mediumTwo
        val fourthPlane = scene.subList(planeSize * 4, planeSize * 5)
        val highOne = fourthPlane.take(512) + knownAfterMedium.take(3_584)
        val highTwo = fourthPlane.drop(512).take(512) + knownAfterMedium.take(3_584)
        val exactPose = scenarioPose(0.12)
        val exactMap = highTwo
        val conflictMap = exactMap.mapIndexed { index, point ->
            if (index < 512) nearerAlongCameraRay(point, exactPose) else point
        }
        val fartherConflictMap = exactMap.mapIndexed { index, point ->
            if (index < 512) fartherAlongCameraRay(point, exactPose) else point
        }
        return listOf(
            OverlapMap("bootstrap-empty", scenarioPose(-0.16), bootstrap),
            OverlapMap(LOW_OVERLAP_CASE, scenarioPose(-0.12), lowOne),
            OverlapMap(LOW_OVERLAP_CASE, scenarioPose(-0.08), lowTwo),
            OverlapMap(MEDIUM_OVERLAP_CASE, scenarioPose(-0.04), mediumOne),
            OverlapMap(MEDIUM_OVERLAP_CASE, scenarioPose(0.0), mediumTwo),
            OverlapMap(HIGH_OVERLAP_CASE, scenarioPose(0.06), highOne),
            OverlapMap(HIGH_OVERLAP_CASE, exactPose, highTwo),
            OverlapMap(EXACT_REVISIT_CASE, exactPose, exactMap),
            OverlapMap(NEARER_OCCLUDER_CASE, exactPose, conflictMap),
            OverlapMap(FARTHER_DEPTH_CONFLICT_CASE, exactPose, fartherConflictMap),
        )
    }

    private fun nearerAlongCameraRay(point: ScenePoint, cameraPose: DoubleArray): ScenePoint {
        return scaleAlongCameraRay(point, cameraPose, 0.72)
    }

    private fun fartherAlongCameraRay(point: ScenePoint, cameraPose: DoubleArray): ScenePoint {
        return scaleAlongCameraRay(point, cameraPose, 1.28)
    }

    private fun scaleAlongCameraRay(point: ScenePoint, cameraPose: DoubleArray, scale: Double): ScenePoint {
        val cameraX = cameraPose[12] * 1_000.0
        val cameraY = cameraPose[13] * 1_000.0
        val cameraZ = cameraPose[14] * 1_000.0
        return ScenePoint(
            cameraX + (point.xMm - cameraX) * scale,
            cameraY + (point.yMm - cameraY) * scale,
            cameraZ + (point.zMm - cameraZ) * scale,
        )
    }

    private fun scenarioPose(angle: Double): DoubleArray {
        val radius = 0.12
        val cosine = cos(angle)
        val sine = sin(angle)
        return doubleArrayOf(
            cosine, 0.0, -sine, 0.0,
            0.0, 1.0, 0.0, 0.0,
            sine, 0.0, cosine, 0.0,
            sine * radius, 0.0, cosine * radius, 1.0,
        )
    }

    private fun nullableSummary(values: List<Long?>): String {
        val known = values.filterNotNull()
        return if (known.isEmpty()) "null" else summary("known", known) + ",known=${known.size}/${values.size}"
    }

    private fun overlapBasisPoints(values: List<EndpointOverlap>): Int {
        val total = values.sumOf { it.total }
        return if (total == 0) 0 else values.sumOf { it.existing } * 10_000 / total
    }

    private fun canonicalBatchSummary(changes: List<DepthEvidenceChange>): String {
        val operationCounts = changes.groupingBy { it.operationKind.name }.eachCount()
        val sourceTotal = changes.sumOf { it.sourceCount.toLong() }
        val targetTotal = changes.sumOf { it.targetCount.toLong() }
        val largestSourceComponent = changes.maxOfOrNull { it.sourceCount } ?: 0
        val supportCrossProduct = changes.sumOf { change ->
            when (change.operationKind.supportMode) {
                DepthEvidenceSupportMode.NONE -> 0L
                DepthEvidenceSupportMode.SELF -> change.targetCount.toLong()
                DepthEvidenceSupportMode.SOURCE_TO_TARGETS ->
                    change.sourceCount.toLong() * change.targetCount.toLong()
            }
        }
        return "changes=${changes.size} operations=$operationCounts source_total=$sourceTotal " +
            "target_total=$targetTotal largest_source_component=$largestSourceComponent " +
            "support_cross_product=$supportCrossProduct"
    }

    private class ReusableDepthFixture(
        private val depth: ByteBuffer,
        private val confidence: ByteBuffer,
    ) {
        private val touchedPixels = IntArray(SCENE_POINT_COUNT)
        private var touchedCount = 0

        fun write(map: OverlapMap, intrinsics: VisibilityCameraIntrinsics): DepthWriteSummary {
            for (index in 0 until touchedCount) {
                val pixel = touchedPixels[index]
                depth.putShort(pixel * 2, 0.toShort())
                confidence.put(pixel, 0.toByte())
            }
            touchedCount = 0
            val projectedPixels = HashSet<Int>(map.points.size)
            map.points.forEach { point ->
                val projected = project(point, map.cameraPose, intrinsics) ?: return@forEach
                val pixel = projected.y * IMAGE_WIDTH + projected.x
                if (touchedCount >= touchedPixels.size) error("synthetic depth fixture capacity exhausted")
                touchedPixels[touchedCount++] = pixel
                projectedPixels += pixel
                depth.putShort(pixel * 2, projected.depthMillimetres.toShort())
                confidence.put(pixel, 255.toByte())
            }
            return DepthWriteSummary(touchedCount, projectedPixels.size)
        }

        private fun project(
            point: ScenePoint,
            pose: DoubleArray,
            intrinsics: VisibilityCameraIntrinsics,
        ): ProjectedDepthPixel? {
            val dx = point.xMm / 1_000.0 - pose[12]
            val dy = point.yMm / 1_000.0 - pose[13]
            val dz = point.zMm / 1_000.0 - pose[14]
            val cameraX = pose[0] * dx + pose[1] * dy + pose[2] * dz
            val cameraY = pose[4] * dx + pose[5] * dy + pose[6] * dz
            val cameraZ = pose[8] * dx + pose[9] * dy + pose[10] * dz
            val depth = -cameraZ * 1_000.0
            if (!depth.isFinite() || depth < 200.0 || depth > 8_000.0) return null
            val x = (intrinsics.fx * cameraX / -cameraZ + intrinsics.cx).roundToInt()
            val y = (intrinsics.cy - intrinsics.fy * cameraY / -cameraZ).roundToInt()
            if (x !in 0 until intrinsics.imageWidth || y !in 0 until intrinsics.imageHeight) return null
            return ProjectedDepthPixel(x, y, depth.roundToInt())
        }
    }

    private fun reusableDepthSource(depth: ByteBuffer, confidence: ByteBuffer) = RawDepthCopySource(
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

    private fun writeRotatingMap(depth: ByteBuffer, confidence: ByteBuffer, mapIndex: Int) {
        depth.clear()
        confidence.clear()
        var index = 0
        while (index < IMAGE_WIDTH * IMAGE_HEIGHT) {
            depth.putShort(index * 2, 1_200.toShort())
            confidence.put(index, 255.toByte())
            index++
        }
        val angle = (mapIndex % YAW_VIEW_COUNT).toDouble() * (2.0 * Math.PI / YAW_VIEW_COUNT.toDouble())
        val centerX = (1_000.0 + cos(angle) * 350.0).roundToInt()
        val centerY = (1_000.0 + sin(angle) * 350.0).roundToInt()
        val sweepDepthOffset = (mapIndex / YAW_VIEW_COUNT) * 50
        patch(depth, centerX - 22, centerY, 300 + sweepDepthOffset)
        patch(depth, centerX, centerY, 400 + sweepDepthOffset)
        patch(depth, centerX + 22, centerY, 600 + sweepDepthOffset)
    }

    private fun patch(depth: ByteBuffer, centerX: Int, centerY: Int, millimetres: Int) {
        for (y in centerY - 4..centerY + 4) for (x in centerX - 4..centerX + 4) {
            depth.putShort((y * IMAGE_WIDTH + x) * 2, millimetres.toShort())
        }
    }

    private fun metadata(timestampNs: Long, cameraPose: DoubleArray) = RawDepthFrameMetadata(
        timestampNs = timestampNs,
        groupGeneration = 1,
        sessionGeneration = 1,
        tracking = true,
        width = IMAGE_WIDTH,
        height = IMAGE_HEIGHT,
        intrinsics = DepthIntrinsics(1_000.0, 1_000.0, 1_000.0, 1_000.0),
        worldFromCameraGl = cameraPose,
    )

    /** Camera yaw plus a bounded translation keeps the synthetic observer inside a 1 m volume. */
    private fun cameraPose(mapIndex: Int): DoubleArray {
        val angle = (mapIndex % YAW_VIEW_COUNT).toDouble() * (2.0 * Math.PI / YAW_VIEW_COUNT.toDouble())
        val cosine = cos(angle)
        val sine = sin(angle)
        return doubleArrayOf(
            cosine, 0.0, -sine, 0.0,
            0.0, 1.0, 0.0, 0.0,
            sine, 0.0, cosine, 0.0,
            sin(angle) * 0.25, 0.0, cos(angle) * 0.25, 1.0,
        )
    }

    private fun target(id: SurfaceId?, voxel: Voxel) = CanonicalTarget(id, voxel, 0, 0, 200)

    private fun identityTransform() = DoubleArray(16) { if (it % 5 == 0) 1.0 else 0.0 }

    private fun elapsedMicros(startNs: Long, endNs: Long) = max(0L, (endNs - startNs) / 1_000L)

    private fun runtimeStat(name: String): Long? = Debug.getRuntimeStat(name)?.toLongOrNull()

    private fun delta(before: Long?, after: Long?): Long? = if (before != null && after != null) {
        require(after >= before) { "runtime allocation/GC counter reset invalidates this campaign" }
        after - before
    } else null

    private fun gcSnapshot() = GcSnapshot(
        runtimeStat("art.gc.gc-count"),
        runtimeStat("art.gc.gc-time"),
    )

    private fun summary(label: String, values: List<Long>): String {
        require(values.isNotEmpty())
        val sorted = values.sorted()
        fun percentile(fraction: Double): Long {
            val index = (ceil(sorted.size * fraction).toInt() - 1).coerceIn(0, sorted.lastIndex)
            return sorted[index]
        }
        return "$label:p50=${percentile(0.50)}us,p95=${percentile(0.95)}us," +
            "p99=${percentile(0.99)}us,max=${sorted.last()}us"
    }

    private fun allocationSummary(values: List<Long?>): String {
        val known = values.filterNotNull()
        if (known.isEmpty()) return "unknown"
        val sorted = known.sorted()
        fun percentile(fraction: Double): Long {
            val index = (ceil(sorted.size * fraction).toInt() - 1).coerceIn(0, sorted.lastIndex)
            return sorted[index]
        }
        return "p50=${percentile(0.50)}B,p95=${percentile(0.95)}B," +
            "p99=${percentile(0.99)}B,max=${sorted.last()}B,total=${known.sum()}B," +
            "known=${known.size}/${values.size}"
    }

    private data class GcSnapshot(val count: Long?, val time: Long?)

    private data class ScenePoint(val xMm: Double, val yMm: Double, val zMm: Double)

    private data class ProjectedDepthPixel(val x: Int, val y: Int, val depthMillimetres: Int)

    private data class OverlapMap(
        val caseName: String,
        val cameraPose: DoubleArray,
        val points: List<ScenePoint>,
    )

    private data class DepthWriteSummary(
        val writtenPoints: Int,
        val projectedValidPixels: Int,
    )

    private data class EndpointOverlap(val existing: Int, val total: Int)

    private data class OverlapTiming(
        val caseName: String,
        val mapIndex: Int,
        val writtenPoints: Int,
        val projectedValidPixels: Int,
        val selectedSamples: Int,
        val overlap: EndpointOverlap,
        val receipt: DepthEvidenceReceipt,
        val material: Boolean,
        val selectionMicros: Long,
        val fusionMicros: Long,
        val preparationMicros: Long?,
        val commitMicros: Long?,
        val acknowledgementMicros: Long?,
        val applyMicros: Long,
        val endToEndMicros: Long,
        val allocatedBytes: Long?,
        val gcCount: Long?,
        val gcTime: Long?,
        val nativeHeapBefore: Long,
        val nativeHeapAfter: Long,
        val selectionAllocatedBytes: Long?,
        val fusionAllocatedBytes: Long?,
        val preparationAllocatedBytes: Long?,
        val commitAllocatedBytes: Long?,
        val acknowledgementAllocatedBytes: Long?,
        val applyAllocatedBytes: Long?,
    ) {
        fun toLogLine(): String =
            "overlap case=$caseName map=$mapIndex endpoint_existing=${overlap.existing} " +
                "endpoint_total=${overlap.total} endpoint_overlap_bp=" +
                "${if (overlap.total == 0) 0 else overlap.existing * 10_000 / overlap.total} " +
                "written_points=$writtenPoints projected_valid_pixels=$projectedValidPixels " +
                "selected_samples=$selectedSamples material=$material " +
                "receipt_create=${receipt.createCount} receipt_refine=${receipt.refineCount} " +
                "receipt_relocate=${receipt.relocateCount} receipt_merge=${receipt.mergeCount} " +
                "receipt_split=${receipt.splitCount} receipt_replace=${receipt.replaceCount} " +
                "receipt_remove=${receipt.removeCount} receipt_conflicts=${receipt.conflictsRetained} " +
                "selection_us=$selectionMicros fusion_us=$fusionMicros " +
                "preparation_us=$preparationMicros commit_us=$commitMicros ack_us=$acknowledgementMicros " +
                "apply_us=$applyMicros end_to_end_us=$endToEndMicros allocated_bytes=$allocatedBytes " +
                "gc_count=$gcCount gc_time=$gcTime native_heap_before=$nativeHeapBefore " +
                "native_heap_after=$nativeHeapAfter selection_allocated_bytes=$selectionAllocatedBytes " +
                "fusion_allocated_bytes=$fusionAllocatedBytes preparation_allocated_bytes=$preparationAllocatedBytes " +
                "commit_allocated_bytes=$commitAllocatedBytes ack_allocated_bytes=$acknowledgementAllocatedBytes " +
                "apply_allocated_bytes=$applyAllocatedBytes"
    }

    private data class StageTiming(
        val operation: String,
        val selectionMicros: Long,
        val fusionMicros: Long,
        val preparationMicros: Long,
        val commitMicros: Long,
        val acknowledgementMicros: Long,
        val endToEndMicros: Long,
        val allocatedBytes: Long?,
        val gcCount: Long?,
        val gcTime: Long?,
        val nativeHeapBefore: Long,
        val nativeHeapAfter: Long,
        val selectionAllocatedBytes: Long?,
        val fusionAllocatedBytes: Long?,
        val preparationAllocatedBytes: Long?,
        val commitAllocatedBytes: Long?,
        val acknowledgementAllocatedBytes: Long?,
    ) {
        fun toLogLine(phase: String): String =
            "$phase operation=$operation selection_us=$selectionMicros fusion_us=$fusionMicros " +
                "preparation_us=$preparationMicros commit_us=$commitMicros ack_us=$acknowledgementMicros " +
                "end_to_end_us=$endToEndMicros allocated_bytes=$allocatedBytes gc_count=$gcCount " +
                "gc_time=$gcTime native_heap_before=$nativeHeapBefore native_heap_after=$nativeHeapAfter " +
                "selection_allocated_bytes=$selectionAllocatedBytes fusion_allocated_bytes=$fusionAllocatedBytes " +
                "preparation_allocated_bytes=$preparationAllocatedBytes commit_allocated_bytes=$commitAllocatedBytes " +
                "ack_allocated_bytes=$acknowledgementAllocatedBytes"
    }

    private companion object {
        const val IMAGE_WIDTH = 2_000
        const val IMAGE_HEIGHT = 2_000
        const val MAP_COUNT = 60
        const val YAW_VIEW_COUNT = 30
        const val CONTROL_SURFACE_COUNT = 128
        const val MERGE_COUNT = CONTROL_SURFACE_COUNT / 2
        const val CONTROL_GRID_WIDTH = 8
        const val CONTROL_MERGE_X = 50
        const val CONTROL_MERGE_Y = 40
        const val CONTROL_MERGE_Z = 40
        const val SCENE_COLUMNS = 80
        const val SCENE_ROWS = 64
        const val SCENE_DEPTH_LAYERS = 5
        const val SCENE_POINT_COUNT = SCENE_COLUMNS * SCENE_ROWS * SCENE_DEPTH_LAYERS
        const val EXACT_REVISIT_CASE = "exact-revisit"
        const val LOW_OVERLAP_CASE = "low-overlap"
        const val MEDIUM_OVERLAP_CASE = "medium-overlap"
        const val HIGH_OVERLAP_CASE = "high-overlap"
        const val NEARER_OCCLUDER_CASE = "nearer-occluder"
        const val FARTHER_DEPTH_CONFLICT_CASE = "farther-depth-conflict"
        const val TAG = "ManyDepthMapsSession"
    }
}
