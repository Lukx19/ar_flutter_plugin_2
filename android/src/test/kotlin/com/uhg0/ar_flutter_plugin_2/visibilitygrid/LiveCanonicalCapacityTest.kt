package com.uhg0.ar_flutter_plugin_2.visibilitygrid

import com.uhg0.ar_flutter_plugin_2.capture.JvmDescriptorFilesystemV2
import com.uhg0.ar_flutter_plugin_2.capture.StorageBudgetCoordinatorV2
import com.uhg0.ar_flutter_plugin_2.capture.StorageBudgetPolicyV2
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Capacity gate for the process-local packed canonical authority.
 *
 * The fixture stays in one live session.  It deliberately reaches the maximum
 * source/support counts through valid depth operations instead of restoring a
 * prebuilt map, so the receipt covers the authority that actually serves the
 * renderer and lookup paths.
 */
class LiveCanonicalCapacityTest {
    @Test(timeout = 240_000)
    fun `live packed authority reaches maximum cut within native aggregate`() {
        val root = Files.createTempDirectory("live-canonical-capacity").toFile()
        val group = SurfaceGroup("b".repeat(32))
        val coordinator = StorageBudgetCoordinatorV2(
            File(root, "visibility-grid-canonical-surface-runtime"),
            StorageBudgetPolicyV2(NATIVE_AGGREGATE_LIMIT_BYTES, 0),
            JvmDescriptorFilesystemV2(),
        )
        val runtime = CanonicalRuntimeResources.openLive(
            root,
            group,
            coordinator,
            SurfaceOwnershipConfiguration(surfaceCapacity = LIVE_ROWS, lineageCapacity = MAXIMUM_LINEAGE),
        )
        val transientPeaks = ArrayList<Long>()
        try {
            assertTrue(
                runtime.openInitial(committedEmptyBaseline("live-capacity", group.value, 1, 1, 1))
                    is SurfaceOwnershipOpenResult.Opened,
            )

            createInitialRows(runtime, transientPeaks)
            val mergeTargets = mergeInitialRows(runtime, transientPeaks)
            val splitOutputs = splitMergeTargets(runtime, mergeTargets, transientPeaks)
            assertEquals(TRANSFORMED_ROWS, splitOutputs.size)
            replaceSupportOneRows(runtime, transientPeaks)
            swapSupportOneRows(runtime, transientPeaks)

            val state = requireNotNull(runtime.owner().activationState())
            assertEquals(LIVE_ROWS, state.cut.liveSurfaceCount)
            assertEquals(MAXIMUM_SOURCES, state.cut.sourceCount)
            assertEquals(MAXIMUM_SUPPORTS, state.cut.supportCount)
            assertEquals(MAXIMUM_LINEAGE, state.cut.lineageCount)
            assertEquals(MAXIMUM_SOURCES + 1L, state.cut.nextSurfaceIdHighWater)
            val overflowCut = state.cut
            val overflowPreparation = runtime.prepareEvidenceBatch(
                CanonicalEvidenceBatchCommand(
                    "live-capacity-overflow",
                    overflowCut.geometryRevision,
                    overflowCut.lineageRevision,
                    listOf(
                        DepthEvidenceChange.Create(target(capacityProbeVoxel(0))),
                        DepthEvidenceChange.Create(target(capacityProbeVoxel(1))),
                    ),
                ),
            )
            assertTrue(overflowPreparation is CanonicalMutationPreparation.Refused)
            assertEquals(
                CanonicalMutationRefusal.CAPACITY,
                (overflowPreparation as CanonicalMutationPreparation.Refused).reason,
            )
            assertEquals(overflowCut, requireNotNull(runtime.owner().activationState()).cut)

            val memory = requireNotNull(runtime.retainedCompleteCurrentMemoryReceipt())
            assertTrue("packed authority must retain rows", memory.rowColumnsBytes > 0L)
            assertTrue("spatial primitive receipt must be charged", memory.cacheMetadataBytes >= SPATIAL_PRIMITIVE_BYTES)
            assertTrue("current buffers are retained exactly once", memory.residentTotalBytes >= CURRENT_BUFFER_BYTES * 2L)

            val pageReceipt = readAndVerifyRendererPages(runtime, state.cut)
            assertEquals((LIVE_ROWS + PAGE_LIMIT - 1) / PAGE_LIMIT, pageReceipt.pageCount)
            assertEquals(PAGE_LIMIT, pageReceipt.maximumPageRows)
            assertEquals(LIVE_ROWS, pageReceipt.rowCount)
            assertEquals(65535, pageReceipt.lineageDisplayValue)

            assertTrue("every staged current delta must fit the journal reserve", transientPeaks.maxOrNull()!! <= JOURNAL_LIMIT_BYTES)
            val current = state.current as CanonicalActivationCurrent.Receipt
            assertTrue(current.identity.canonicalLength <= JOURNAL_LIMIT_BYTES)

            // The packed authority receipt includes its two reusable current
            // buffers and the spatial primitive index.  The transient plan
            // peak is added once; its currentBytes field is already backed by
            // those preallocated buffers and is therefore not added again.
            val packedAuthorityAndSpatial = memory.residentTotalBytes
            val pendingPlanPeak = transientPeaks.maxOrNull()!!
            val ownerBytes = runtime.portableOwnerBytes() + runtime.portableCoordinatorOwnerBytes()
            val projectedPeak = Math.addExact(
                Math.addExact(packedAuthorityAndSpatial, DEPTH_MAXIMUM_SEMANTIC_BYTES),
                Math.addExact(
                    Math.addExact(CompactCanonicalStore.KERNEL_RETAINED_BYTES, pendingPlanPeak),
                    ownerBytes,
                ),
            )
            assertTrue(
                "live maximum aggregate=$projectedPeak packed=$packedAuthorityAndSpatial " +
                    "depth=$DEPTH_MAXIMUM_SEMANTIC_BYTES feature=${CompactCanonicalStore.KERNEL_RETAINED_BYTES} " +
                    "pending=$pendingPlanPeak owners=$ownerBytes",
                projectedPeak <= NATIVE_AGGREGATE_LIMIT_BYTES,
            )
            println(
                "LIVE_CANONICAL_MAXIMUM=live=${state.cut.liveSurfaceCount} sources=${state.cut.sourceCount} " +
                    "supports=${state.cut.supportCount} lineage=${state.cut.lineageCount} " +
                    "highWater=${state.cut.nextSurfaceIdHighWater} pages=${pageReceipt.pageCount} " +
                    "maxPageRows=${pageReceipt.maximumPageRows} packedSpatial=$packedAuthorityAndSpatial " +
                    "pendingPlan=$pendingPlanPeak owners=$ownerBytes projectedPeak=$projectedPeak",
            )
        } finally {
            runtime.close()
            coordinator.close()
            root.deleteRecursively()
        }
    }

    private fun createInitialRows(runtime: CanonicalRuntimeResources, transientPeaks: MutableList<Long>) {
        var start = 0
        while (start < INITIAL_ROWS) {
            val end = minOf(INITIAL_ROWS, start + CREATE_BATCH_ROWS)
            val changes = (start until end).map { index ->
                DepthEvidenceChange.Create(target(initialVoxel(index)))
            }
            apply(runtime, "live-capacity-create-$start", changes, transientPeaks)
            start = end
        }
    }

    private fun mergeInitialRows(
        runtime: CanonicalRuntimeResources,
        transientPeaks: MutableList<Long>,
    ): List<Long> {
        val mergeTargets = ArrayList<Long>(MERGE_GROUPS)
        var groupIndex = 0
        var nextTargetId = INITIAL_ROWS + 1L
        var nextSourceId = UNTOUCHED_ROWS + 1L
        while (groupIndex < MERGE_GROUPS) {
            val end = minOf(MERGE_GROUPS, groupIndex + MERGE_BATCH_GROUPS)
            val changes = ArrayList<DepthEvidenceChange>(end - groupIndex)
            while (groupIndex < end) {
                val size = mergeGroupSize(groupIndex)
                changes += DepthEvidenceChange.Merge(
                    (nextSourceId until nextSourceId + size).map(::SurfaceId),
                    target(mergeVoxel(groupIndex)),
                )
                mergeTargets += nextTargetId++
                nextSourceId += size
                groupIndex++
            }
            apply(runtime, "live-capacity-merge-${mergeTargets.size}", changes, transientPeaks)
        }
        assertEquals(MERGE_GROUPS, mergeTargets.size)
        return mergeTargets
    }

    private fun splitMergeTargets(
        runtime: CanonicalRuntimeResources,
        mergeTargets: List<Long>,
        transientPeaks: MutableList<Long>,
    ): List<Long> {
        val outputs = ArrayList<Long>(TRANSFORMED_ROWS)
        var groupIndex = 0
        var nextTargetId = INITIAL_ROWS + MERGE_GROUPS + 1L
        var outputOrdinal = 0
        while (groupIndex < mergeTargets.size) {
            val end = minOf(mergeTargets.size, groupIndex + SPLIT_BATCH_GROUPS)
            val changes = ArrayList<DepthEvidenceChange>(end - groupIndex)
            while (groupIndex < end) {
                val outputCount = splitGroupSize(groupIndex)
                val targets = (0 until outputCount).map {
                    target(splitVoxel(outputOrdinal++))
                }
                changes += DepthEvidenceChange.Split(
                    SurfaceId(mergeTargets[groupIndex]),
                    targets,
                )
                repeat(outputCount) {
                    outputs += nextTargetId++
                }
                groupIndex++
            }
            apply(runtime, "live-capacity-split-${outputs.size}", changes, transientPeaks)
        }
        assertEquals(TRANSFORMED_ROWS, outputs.size)
        assertEquals(SPLIT_OUTPUT_ROWS, outputOrdinal)
        return outputs
    }

    private fun replaceSupportOneRows(
        runtime: CanonicalRuntimeResources,
        transientPeaks: MutableList<Long>,
    ) {
        var sourceStart = 1L
        val firstReplacementStart = INITIAL_ROWS + MERGE_GROUPS + TRANSFORMED_ROWS + 1L
        repeat(REPLACEMENT_ROUNDS) { round ->
            var offset = 0
            while (offset < UNTOUCHED_ROWS) {
                val end = minOf(UNTOUCHED_ROWS, offset + REPLACE_BATCH_ROWS)
                val changes = (offset until end).map { index ->
                    val source = sourceStart + index
                    DepthEvidenceChange.Replace(
                        listOf(SurfaceId(source)),
                        listOf(target(replaceVoxel(round * UNTOUCHED_ROWS + index))),
                    )
                }
                apply(runtime, "live-capacity-replace-$round-$offset", changes, transientPeaks)
                offset = end
            }
            sourceStart = if (round == 0) firstReplacementStart else sourceStart + UNTOUCHED_ROWS
        }
    }

    private fun swapSupportOneRows(
        runtime: CanonicalRuntimeResources,
        transientPeaks: MutableList<Long>,
    ) {
        val secondReplacementStart = INITIAL_ROWS + MERGE_GROUPS + TRANSFORMED_ROWS + UNTOUCHED_ROWS + 1L
        val changes = ArrayList<DepthEvidenceChange>(SWAP_ROWS * 2)
        for (index in 0 until SWAP_ROWS) {
            changes += DepthEvidenceChange.Remove(SurfaceId(secondReplacementStart + index))
        }
        for (index in 0 until SWAP_ROWS) {
            changes += DepthEvidenceChange.Create(target(swapVoxel(index)))
        }
        var offset = 0
        while (offset < changes.size) {
            val end = minOf(changes.size, offset + SWAP_BATCH_CHANGES)
            apply(runtime, "live-capacity-swap-$offset", changes.subList(offset, end), transientPeaks)
            offset = end
        }
    }

    private fun apply(
        runtime: CanonicalRuntimeResources,
        commandId: String,
        changes: List<DepthEvidenceChange>,
        transientPeaks: MutableList<Long>,
    ) {
        val cut = requireNotNull(runtime.owner().activationState()).cut
        val preparation = runtime.prepareEvidenceBatch(
            CanonicalEvidenceBatchCommand(commandId, cut.geometryRevision, cut.lineageRevision, changes),
        )
        val prepared = when (preparation) {
            is CanonicalMutationPreparation.Prepared -> preparation
            is CanonicalMutationPreparation.Refused -> error(
                "live capacity preparation refused: $commandId reason=${preparation.reason} " +
                    "preflight=${preparation.preflightWork} receipt=${preparation.receipt}",
            )
            is CanonicalMutationPreparation.NoOp -> error(
                "live capacity preparation returned no-op: $commandId receipt=${preparation.receipt}",
            )
        }
        transientPeaks += maxOf(
            prepared.mutation.work.constructionPeakBytes,
            prepared.mutation.work.stagingBytes,
        )
        assertTrue(prepared.mutation.work.currentBytes.toLong() <= JOURNAL_LIMIT_BYTES)
        val result = runtime.commitAdjacent(prepared.mutation)
        assertTrue("live capacity commit refused: $commandId result=$result", result is CanonicalAdjacentCommitResult.Committed)
        val committed = requireNotNull(runtime.owner().activationState())
        val current = committed.current as CanonicalActivationCurrent.Receipt
        assertTrue(
            runtime.owner().acknowledgeCanonicalCurrent(
                CanonicalAcknowledgement(current.identity.commandHash, committed.cut.geometryRevision, committed.cut.lineageRevision),
            ) is CanonicalAcknowledgementResult.Acknowledged,
        )
    }

    private fun readAndVerifyRendererPages(
        runtime: CanonicalRuntimeResources,
        cut: CompactCanonicalCut,
    ): PageReceipt {
        var cursor = 0L
        var pages = 0
        var rows = 0
        var maximumRows = 0
        var lineageValue = -1
        var lastId = 0L
        while (true) {
            val page = requireNotNull(runtime.readRendererPage(cursor, PAGE_LIMIT))
            assertEquals(cut, page.cut)
            assertTrue(page.rows.size in 1..PAGE_LIMIT)
            val counters = requireNotNull(runtime.withCurrent { view ->
                val state = view as SessionCanonicalMemoryState
                state.rendererPageSearchComparisons to state.rendererPageVisitedRows
            })
            assertTrue("renderer page search comparisons=${counters.first}", counters.first <= 17)
            assertEquals(page.rows.size, counters.second)
            assertTrue(counters.second <= PAGE_LIMIT)
            page.rows.zipWithNext().forEach { (left, right) -> assertTrue(left.surfaceId < right.surfaceId) }
            page.rows.forEach { row ->
                assertTrue(row.surfaceId > lastId)
                lastId = row.surfaceId
                rows++
                lineageValue = row.lineageCount
            }
            pages++
            maximumRows = maxOf(maximumRows, page.rows.size)
            val next = page.nextCursor ?: break
            assertTrue(next > cursor)
            cursor = next
        }
        assertEquals(MAXIMUM_SOURCES.toLong(), lastId)
        return PageReceipt(pages, rows, maximumRows, lineageValue)
    }

    private fun target(voxel: Voxel) = CanonicalTarget(null, voxel, 1, 1, 200)

    private fun initialVoxel(index: Int) = Voxel(index % 1_000, index / 1_000, 0)

    private fun mergeVoxel(index: Int) = Voxel(2_000 + index % 1_000, index / 1_000, 1)

    private fun splitVoxel(index: Int) = Voxel(4_000 + index % 1_000, index / 1_000, 2)

    private fun replaceVoxel(index: Int) = Voxel(6_000 + index % 1_000, index / 1_000, 3)

    private fun swapVoxel(index: Int) = Voxel(8_000 + index % 1_000, index / 1_000, 4)

    private fun capacityProbeVoxel(index: Int) = Voxel(10_000 + index, 1, 5)

    private fun mergeGroupSize(index: Int) = when {
        index == 0 -> 9
        index == 1 -> 5
        index == 2 -> 6
        else -> 7
    }

    private fun splitGroupSize(index: Int) = mergeGroupSize(index)

    private data class PageReceipt(
        val pageCount: Int,
        val rowCount: Int,
        val maximumPageRows: Int,
        val lineageDisplayValue: Int,
    )

    private companion object {
        const val INITIAL_ROWS = 100_000
        const val LIVE_ROWS = 100_000
        const val UNTOUCHED_ROWS = 66_667
        const val TRANSFORMED_ROWS = 33_333
        const val MERGE_GROUPS = 4_762
        const val SPLIT_OUTPUT_ROWS = TRANSFORMED_ROWS
        const val REPLACEMENT_ROUNDS = 2
        const val SWAP_ROWS = 28_571
        const val SWAP_BATCH_CHANGES = 128
        const val MAXIMUM_SOURCES = 300_000
        const val MAXIMUM_SUPPORTS = 300_000
        const val MAXIMUM_LINEAGE = 200_000
        const val CREATE_BATCH_ROWS = 512
        const val MERGE_BATCH_GROUPS = 64
        const val SPLIT_BATCH_GROUPS = 32
        const val REPLACE_BATCH_ROWS = 256
        const val PAGE_LIMIT = 512
        const val JOURNAL_LIMIT_BYTES = 1_048_576L
        const val NATIVE_AGGREGATE_LIMIT_BYTES = 64L * 1024L * 1024L
        const val DEPTH_MAXIMUM_SEMANTIC_BYTES = 15_587_096L
        const val SPATIAL_PRIMITIVE_BYTES = 7_222_848L
        const val CURRENT_BUFFER_BYTES = 1_048_576L
    }
}
