package com.uhg0.ar_flutter_plugin_2.visibilitygrid

import android.os.Debug
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.uhg0.ar_flutter_plugin_2.pointcloud.CoverageCommittedRow
import com.uhg0.ar_flutter_plugin_2.pointcloud.CoverageCommittedRows
import com.uhg0.ar_flutter_plugin_2.pointcloud.COVERAGE_RENDERER_NO_DIRECTION
import com.uhg0.ar_flutter_plugin_2.pointcloud.COVERAGE_RENDERER_STYLE_ROW_BYTES
import com.uhg0.ar_flutter_plugin_2.pointcloud.CoverageRendererCoverage
import com.uhg0.ar_flutter_plugin_2.pointcloud.CoverageRendererStyleRowV1
import com.uhg0.ar_flutter_plugin_2.pointcloud.CoverageRowsQualifier
import com.uhg0.ar_flutter_plugin_2.sceneview.CoveragePresentationSelector
import java.util.Arrays
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Isolated renderer style-update allocation measurement.
 *
 * The immutable cuts are prepared before measurement. Warm updates drain their
 * range receipts before the measured window. The receipt separates the style
 * cut stage from the bounded selection/presentation pipeline; held input
 * assertions protect the immutable producer/consumer boundary.
 */
@RunWith(AndroidJUnit4::class)
class RendererAllocationRateAndroidTest {
    @Test(timeout = 90_000)
    fun warmRendererUpdatesReportBoundedCapacityAndAllocation() {
        val rowCount = 1_200
        val warmUpdates = 10
        val measuredUpdates = 120
        val state = VisibilityGridRendererState(rowCount)
        val owner = ownership(rowCount)
        val rows = (0 until rowCount).map { index ->
            CanonicalRenderRow(
                surfaceId = index + 1L,
                voxelKey = packVisibilityGridKey(index - rowCount / 2, 0, 0),
                packedNormal = 0,
                normalConfidence = 0,
                lineageCount = 0,
            )
        }
        val config = VisibilityGridGroupConfig(
            groupId = "02".repeat(16),
            groupGeneration = 1,
            sessionGeneration = 1,
            voxelSizeMeters = 0.1,
            capacity = rowCount,
            groupFromWorldGl = identityVisibilityGridTransform(),
            worldFromGroupGl = identityVisibilityGridTransform(),
        )
        val cuts = (1..(warmUpdates + measuredUpdates)).map { revision ->
            styleCut(rowCount, owner, revision.toLong())
        }

        try {
            state.startCanonicalGroup(
                config = config,
                geometryRevision = 1,
                rows = rows,
                ownership = owner,
                transactionId = 9,
                lineageRevision = 3,
            )
            state.takePresentationUpdate()
            repeat(warmUpdates) { index ->
                assertTrue(state.applyStyleCut(cuts[index]) is RendererStyleCutResult.Applied)
                state.takePresentationUpdate()
            }

            val warmCapacityBytes = state.ownedStorageBytes
            val allocationBefore = runtimeStat("art.gc.bytes-allocated")
            val nativeBefore = Debug.getNativeHeapAllocatedSize()
            val updateAllocations = LongArray(measuredUpdates)
            val updateElapsedMicros = LongArray(measuredUpdates)
            val heldCut = cuts.last()
            val heldStyleRows = heldCut.styleRows.copyOf()

            repeat(measuredUpdates) { offset ->
                val before = runtimeStat("art.gc.bytes-allocated")
                val startedNs = System.nanoTime()
                val cut = cuts[warmUpdates + offset]
                assertTrue(state.applyStyleCut(cut) is RendererStyleCutResult.Applied)
                state.takePresentationUpdate()
                updateElapsedMicros[offset] = elapsedMicros(startedNs)
                val after = runtimeStat("art.gc.bytes-allocated")
                updateAllocations[offset] = allocationDelta(after, before, "style cut")
            }

            val allocationAfter = runtimeStat("art.gc.bytes-allocated")
            val nativeAfter = Debug.getNativeHeapAllocatedSize()
            val totalAllocated = allocationDelta(allocationAfter, allocationBefore, "style cut total")
            val maxPerUpdate = updateAllocations.maxOrNull() ?: 0L
            val sorted = updateAllocations.clone().also { Arrays.sort(it) }
            val p95 = sorted[((measuredUpdates * 95) + 99) / 100 - 1]
            val sortedElapsed = updateElapsedMicros.clone().also { Arrays.sort(it) }
            val elapsedP50 = sortedElapsed[((measuredUpdates * 50) + 99) / 100 - 1]
            val elapsedP95 = sortedElapsed[((measuredUpdates * 95) + 99) / 100 - 1]
            val elapsedP99 = sortedElapsed[((measuredUpdates * 99) + 99) / 100 - 1]
            val elapsedMax = sortedElapsed.lastOrNull() ?: 0L
            val retainedCapacityGrowth =
                state.ownedStorageBytes.toLong() - warmCapacityBytes.toLong()
            val budgetBytes = 2L * 1024L * 1024L

            assertEquals("renderer capacity changed", rowCount, state.capacity)
            assertEquals("renderer retained capacity grew", 0L, retainedCapacityGrowth)
            assertTrue("renderer per-update allocation $maxPerUpdate", maxPerUpdate <= budgetBytes)
            assertTrue("renderer total allocation $totalAllocated", totalAllocated >= 0L)

            assertTrue(
                "exact replay was not idempotent",
                state.applyStyleCut(heldCut) is RendererStyleCutResult.Replayed,
            )
            assertArrayEquals(heldStyleRows, heldCut.styleRows)
            state.takePresentationUpdate()

            val sourceRows = StaticRows(rowCount)
            val selector = CoveragePresentationSelector(rowCount)
            val firstPresentation = selector.select(
                sourceRows,
                requestedCapacity = rowCount,
                forceReset = true,
            )
            assertEquals(rowCount, firstPresentation.count)
            repeat(warmUpdates) {
                sourceRows.styleRevision++
                selector.select(sourceRows, requestedCapacity = rowCount)
            }
            val selectorWarmCapacityBytes = selector.ownedStorageBytes
            val selectorAllocationBefore = runtimeStat("art.gc.bytes-allocated")
            val selectorElapsedMicros = LongArray(measuredUpdates)
            val selectorAllocations = LongArray(measuredUpdates)
            val heldSurfaceIds = firstPresentation.surfaceIds.copyOf()
            repeat(measuredUpdates) { index ->
                val before = runtimeStat("art.gc.bytes-allocated")
                val startedNs = System.nanoTime()
                sourceRows.styleRevision++
                val presentation = selector.select(sourceRows, requestedCapacity = rowCount)
                selectorElapsedMicros[index] = elapsedMicros(startedNs)
                val after = runtimeStat("art.gc.bytes-allocated")
                selectorAllocations[index] = allocationDelta(after, before, "selection")
                assertEquals(rowCount, presentation.count)
            }
            val selectorAllocationAfter = runtimeStat("art.gc.bytes-allocated")
            val selectorTotalAllocated = allocationDelta(
                selectorAllocationAfter,
                selectorAllocationBefore,
                "selection total",
            )
            val selectorSortedElapsed = selectorElapsedMicros.clone().also { Arrays.sort(it) }
            val selectorSortedAllocations = selectorAllocations.clone().also { Arrays.sort(it) }
            val selectorRetainedGrowth =
                selector.ownedStorageBytes.toLong() - selectorWarmCapacityBytes.toLong()
            assertArrayEquals(heldSurfaceIds, firstPresentation.surfaceIds)
            assertEquals("selector retained capacity grew", 0L, selectorRetainedGrowth)

            Log.i(
                "RendererAllocationReceipt",
                "format=capture3d-renderer-allocation-v1 rows=$rowCount " +
                    "warm_updates=$warmUpdates measured_updates=$measuredUpdates " +
                    "warm_capacity_bytes=$warmCapacityBytes " +
                    "measured_capacity_bytes=${state.ownedStorageBytes} " +
                    "retained_capacity_growth_bytes=$retainedCapacityGrowth " +
                    "art_allocated_bytes=$totalAllocated " +
                    "art_allocated_bytes_per_update=${totalAllocated / measuredUpdates} " +
                    "art_allocated_bytes_p95=$p95 " +
                    "art_allocated_bytes_max=$maxPerUpdate " +
                    "elapsed_us_p50=$elapsedP50 elapsed_us_p95=$elapsedP95 " +
                    "elapsed_us_p99=$elapsedP99 elapsed_us_max=$elapsedMax " +
                    "allocation_budget_bytes=$budgetBytes " +
                    "native_heap_before_bytes=$nativeBefore " +
                    "native_heap_after_bytes=$nativeAfter " +
                    "selection_warm_capacity_bytes=$selectorWarmCapacityBytes " +
                    "selection_retained_capacity_growth_bytes=$selectorRetainedGrowth " +
                    "selection_art_allocated_bytes=$selectorTotalAllocated " +
                    "selection_art_allocated_bytes_p50=${selectorSortedAllocations[((measuredUpdates * 50) + 99) / 100 - 1]} " +
                    "selection_art_allocated_bytes_p95=${selectorSortedAllocations[((measuredUpdates * 95) + 99) / 100 - 1]} " +
                    "selection_art_allocated_bytes_p99=${selectorSortedAllocations[((measuredUpdates * 99) + 99) / 100 - 1]} " +
                    "selection_art_allocated_bytes_max=${selectorSortedAllocations.lastOrNull() ?: 0L} " +
                    "selection_elapsed_us_p50=${selectorSortedElapsed[((measuredUpdates * 50) + 99) / 100 - 1]} " +
                    "selection_elapsed_us_p95=${selectorSortedElapsed[((measuredUpdates * 95) + 99) / 100 - 1]} " +
                    "selection_elapsed_us_p99=${selectorSortedElapsed[((measuredUpdates * 99) + 99) / 100 - 1]} " +
                    "selection_elapsed_us_max=${selectorSortedElapsed.lastOrNull() ?: 0L}",
            )
        } finally {
            state.dispose()
        }
    }

    private fun styleCut(
        rowCount: Int,
        owner: VisibilityObservationOwnership,
        revision: Long,
    ) = QualifiedRendererStyleCut(
        ownership = owner,
        transactionId = 9,
        geometryRevision = 1,
        lineageRevision = 3,
        semanticRevision = revision,
        coverageRevision = revision,
        styleRevision = revision,
        residencyRevision = revision,
        targetRevision = revision,
        reset = false,
        surfaceIds = LongArray(rowCount) { it + 1L },
        styleRows = ByteArray(rowCount * COVERAGE_RENDERER_STYLE_ROW_BYTES).also { bytes ->
            repeat(rowCount) { index ->
                CoverageRendererStyleRowV1(
                    semanticGeneration = revision,
                    styleGeneration = revision,
                    coverage = if (index % 3 == 0) {
                        CoverageRendererCoverage.COMPLETE
                    } else {
                        CoverageRendererCoverage.PARTIAL
                    },
                    directionBin = COVERAGE_RENDERER_NO_DIRECTION,
                ).encodeInto(bytes, index * COVERAGE_RENDERER_STYLE_ROW_BYTES)
            }
        },
        targetSurfaceId = null,
        targetDirectionIndex = null,
    )

    private fun ownership(capacity: Int) = VisibilityObservationOwnership(
        sessionId = "01".repeat(16),
        sessionGeneration = 1,
        captureGroupId = "02".repeat(16),
        groupGeneration = 1,
        coverageEpoch = 1,
        arSessionIdentity = "03".repeat(16),
        viewInstanceId = "04".repeat(16),
        viewGeneration = 1,
        nativeStreamToken = "05".repeat(16),
        workerBindingToken = "06".repeat(16),
        bindingGeneration = 1,
        lifecycleSequence = 1,
        operationGeneration = 1,
        groupFrame = VisibilityGroupFrame.copyOf(
            identityVisibilityGridTransform(),
            identityVisibilityGridTransform(),
            100_000,
            capacity,
        ),
    )

    private fun runtimeStat(name: String): Long =
        requireNotNull(Debug.getRuntimeStat(name)?.toLongOrNull()) { "missing ART stat $name" }

    private fun allocationDelta(after: Long, before: Long, stage: String): Long {
        require(after >= before) {
            "ART allocation counter reset during $stage: $before -> $after"
        }
        return after - before
    }

    private fun elapsedMicros(startedNs: Long): Long =
        (System.nanoTime() - startedNs) / 1_000L

    private class StaticRows(override val count: Int) : CoverageCommittedRows {
        private val values = Array(count) { index ->
            CoverageCommittedRow(
                surfaceId = index + 1L,
                key = index + 1L,
                x = index.toFloat(),
                y = 0f,
                z = 0f,
                color = 0,
                style = CoverageRendererStyleRowV1(
                    semanticGeneration = 1L,
                    styleGeneration = 1L,
                    coverage = CoverageRendererCoverage.PARTIAL,
                    directionBin = COVERAGE_RENDERER_NO_DIRECTION,
                ),
            )
        }

        override val capacity: Int = count
        var styleRevision: Long = 1L

        override val qualifier: CoverageRowsQualifier
            get() = CoverageRowsQualifier(
                bindingGeneration = 1L,
                groupGeneration = 1L,
                rendererGeneration = 1L,
                transactionId = 1L,
                geometryRevision = 1L,
                styleRevision = styleRevision,
            )

        override fun rowAt(index: Int): CoverageCommittedRow = values[index]
    }
}
