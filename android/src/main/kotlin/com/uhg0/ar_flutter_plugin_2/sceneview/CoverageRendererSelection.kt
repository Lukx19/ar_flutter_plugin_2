package com.uhg0.ar_flutter_plugin_2.sceneview

import com.uhg0.ar_flutter_plugin_2.pointcloud.CoveragePointRenderSnapshot
import com.uhg0.ar_flutter_plugin_2.pointcloud.CoveragePointRenderUpdate
import com.uhg0.ar_flutter_plugin_2.pointcloud.CoveragePointSpan
import com.uhg0.ar_flutter_plugin_2.pointcloud.COVERAGE_RENDERER_STYLE_ROW_BYTES
import com.uhg0.ar_flutter_plugin_2.visibilitygrid.DirtyRowQueue
import com.uhg0.ar_flutter_plugin_2.visibilitygrid.LongRowIndex
import com.uhg0.ar_flutter_plugin_2.visibilitygrid.SelectedKeyMaxHeap

/** Chapter 17 fixed presentation maxima; semantic-grid capacity is separate. */
internal object CoverageRendererLimits {
    const val RAW_POINT_CAPACITY = 2_000
    const val CENTROID_CAPACITY = 20_000
    const val CUBE_CAPACITY = 8_000
    const val WARM_PROXY_CAPACITY = 4_096
    const val COLD_OVERVIEW_CAPACITY = 512
    const val GLYPH_CAPACITY = 256
    const val DEBUG_ROW_CAPACITY = 1_024
    const val SHARED_OWNED_BUFFER_LIMIT_BYTES = 8 * 1024 * 1024

    // Fixed, renderer-owned native selection rows: key, world position,
    // colour, 16-byte semantic/style cut, and slot/revision bookkeeping. The semantic grid's 100k state
    // is deliberately not charged here; it belongs to M0b's 16 MiB ledger.
    const val NATIVE_SELECTION_BYTES_PER_ROW = 48
    const val NATIVE_SELECTION_BYTES = CENTROID_CAPACITY * NATIVE_SELECTION_BYTES_PER_ROW
    const val AUXILIARY_ROW_BYTES = 16
    const val AUXILIARY_BYTES =
        (WARM_PROXY_CAPACITY + COLD_OVERVIEW_CAPACITY + GLYPH_CAPACITY + DEBUG_ROW_CAPACITY) *
            AUXILIARY_ROW_BYTES

    // A semantic 20k snapshot is retained at the host seam while the active
    // coordinator may retain both its in-flight and coalesced-next cube
    // presentation snapshots. These copies are deliberately charged rather
    // than treated as invisible JVM transients.
    const val SEMANTIC_SNAPSHOT_BYTES = CENTROID_CAPACITY * 40
    const val CUBE_PRESENTATION_SNAPSHOT_BYTES = CUBE_CAPACITY * 40
    const val CUBE_SNAPSHOT_HANDOFF_BYTES =
        SEMANTIC_SNAPSHOT_BYTES + CUBE_PRESENTATION_SNAPSHOT_BYTES * 2
    const val CENTROID_SNAPSHOT_HANDOFF_BYTES =
        SEMANTIC_SNAPSHOT_BYTES + SEMANTIC_SNAPSHOT_BYTES * 2

    // Point resources own 36 bytes per row; cube resources own 736 bytes per
    // row. Keeping all three dormant mode resources under this bound makes a
    // replacement safe even while Compose retires the previous node.
    val allModeOwnedBufferBytes: Int =
        RAW_POINT_CAPACITY * CoveragePointMeshResources.OWNED_BYTES_PER_ROW +
            CENTROID_CAPACITY * CoveragePointMeshResources.OWNED_BYTES_PER_ROW +
            CUBE_CAPACITY * CoverageCubeMeshResources.OWNED_BYTES_PER_VOXEL

    val maximumActiveRendererBytes: Int =
        CUBE_CAPACITY * CoverageCubeMeshResources.OWNED_BYTES_PER_VOXEL +
            NATIVE_SELECTION_BYTES + AUXILIARY_BYTES + CUBE_SNAPSHOT_HANDOFF_BYTES

    init {
        check(allModeOwnedBufferBytes <= SHARED_OWNED_BUFFER_LIMIT_BYTES)
        check(maximumActiveRendererBytes <= SHARED_OWNED_BUFFER_LIMIT_BYTES)
    }

    fun snapshotHandoffBytes(mode: com.uhg0.ar_flutter_plugin_2.pointcloud.VoxelRenderMode): Int =
        when (mode) {
            com.uhg0.ar_flutter_plugin_2.pointcloud.VoxelRenderMode.CUBES ->
                CUBE_SNAPSHOT_HANDOFF_BYTES
            com.uhg0.ar_flutter_plugin_2.pointcloud.VoxelRenderMode.POINTS,
            com.uhg0.ar_flutter_plugin_2.pointcloud.VoxelRenderMode.CENTROIDS ->
                CENTROID_SNAPSHOT_HANDOFF_BYTES
        }
}

/**
 * Persistent, bounded presentation selector. It performs a complete source
 * pass only when its identity basis changes (initial mount, a resync, or a
 * source-slot rewrite). Ordinary changes are translated through retained
 * primitive source/key-to-presentation-slot tables, avoiding a 20k sort and
 * an 8k reset for every revision.
 *
 * The policy is deterministic: keep the lowest identities, with source-slot
 * order as the tie-breaker. New candidates can replace the current largest
 * identity. A replacement reuses the evicted row's destination, so every
 * retained row preserves its GPU destination and only the replacement row is
 * dirty.
 */
internal class CoveragePresentationSelector(
    private val presentationCapacity: Int,
) {
    private val selectedSourceSlots = IntArray(presentationCapacity) { -1 }
    private val selectedKeys = LongArray(presentationCapacity)
    private val selectedPositions =
        FloatArray(presentationCapacity * CoveragePointMeshResources.POSITION_COMPONENTS)
    private val selectedColors = IntArray(presentationCapacity)
    private val selectedStyleRows =
        ByteArray(presentationCapacity * COVERAGE_RENDERER_STYLE_ROW_BYTES)
    private val selectedKeyToDestination = LongRowIndex(presentationCapacity)
    private val selectedKeyMaxHeap = SelectedKeyMaxHeap(presentationCapacity)
    private val freeDestinations = IntArray(presentationCapacity) { presentationCapacity - it - 1 }
    private var freeDestinationCount = presentationCapacity
    private val dirtyDestinations = DirtyRowQueue(presentationCapacity)
    private var selectedCount = 0
    private var sourceCount = 0
    private var sourceSlotToDestination = IntArray(0)
    private var initialized = false
    private var styleRowsPresent = false

    init {
        require(presentationCapacity > 0)
    }

    fun select(snapshot: CoveragePointRenderSnapshot): CoveragePointRenderSnapshot {
        validate(snapshot)
        styleRowsPresent = snapshot.styleRows.isNotEmpty()
        ensureSourceCapacity(snapshot.capacity)
        val sourceRewritten = initialized && selectedIdentityChanged(snapshot)
        if (!initialized || sourceRewritten || snapshot.count < sourceCount) {
            initialize(snapshot)
            return presentation(snapshot, reset = true, spans = fullSpan())
        }

        val membershipDirtyDestinations = acceptNewCandidates(snapshot)
        if (membershipDirtyDestinations.isNotEmpty()) {
            sourceCount = snapshot.count
            return presentation(
                snapshot,
                reset = false,
                spans = dirtySpans(membershipDirtyDestinations),
            )
        }

        sourceCount = snapshot.count
        val update = snapshot.update
        if (update == null) {
            // A revision without dirty metadata is an explicit resync request,
            // not permission to reuse stale presentation rows.
            rebuildSelectedRows(snapshot)
            return presentation(snapshot, reset = true, spans = fullSpan())
        }
        if (update.reset) {
            rebuildSelectedRows(snapshot)
            return presentation(snapshot, reset = true, spans = fullSpan())
        }

        val spans = applyDirtySpans(snapshot, update)
        return presentation(snapshot, reset = false, spans = spans)
    }

    private fun initialize(snapshot: CoveragePointRenderSnapshot) {
        selectedKeyToDestination.clear()
        selectedKeyMaxHeap.clear()
        sourceSlotToDestination.fill(-1)
        freeDestinationCount = presentationCapacity
        for (destination in freeDestinations.indices) {
            freeDestinations[destination] = presentationCapacity - destination - 1
            selectedSourceSlots[destination] = -1
        }
        selectedCount = minOf(snapshot.count, presentationCapacity)
        val heap = IntArray(selectedCount)
        var heapSize = 0
        for (source in 0 until snapshot.count) {
            if (heapSize < selectedCount) {
                heap[heapSize] = source
                siftUp(heap, heapSize, snapshot.keys)
                heapSize++
            } else if (selectedCount > 0 && compareSource(source, heap[0], snapshot.keys) < 0) {
                heap[0] = source
                siftDown(heap, 0, heapSize, snapshot.keys)
            }
        }
        val selectedSources = IntArray(selectedCount)
        for (destination in selectedCount - 1 downTo 0) {
            selectedSources[destination] = heap[0]
            heap[0] = heap[--heapSize]
            if (heapSize > 0) siftDown(heap, 0, heapSize, snapshot.keys)
        }
        selectedCount = 0
        selectedSources.forEach { source -> assignSourceToFreeDestination(snapshot, source) }
        sourceCount = snapshot.count
        initialized = true
    }

    private fun acceptNewCandidates(snapshot: CoveragePointRenderSnapshot): IntArray {
        dirtyDestinations.clear()
        for (source in sourceCount until snapshot.count) {
            if (selectedCount < presentationCapacity) {
                val destination = assignSourceToFreeDestination(snapshot, source)
                dirtyDestinations.add(destination)
            } else if (selectedCount > 0) {
                val largestKey = selectedKeyMaxHeap.largest(selectedKeyToDestination::containsKey)
                if (largestKey != null && snapshot.keys[source] < largestKey) {
                    val destination = checkNotNull(selectedKeyToDestination.remove(largestKey))
                    val evictedSource = selectedSourceSlots[destination]
                    sourceSlotToDestination[evictedSource] = -1
                    selectedSourceSlots[destination] = source
                    selectedKeys[destination] = snapshot.keys[source]
                    selectedKeyToDestination[snapshot.keys[source]] = destination
                    selectedKeyMaxHeap.add(snapshot.keys[source], selectedKeyToDestination::containsKey)
                    sourceSlotToDestination[source] = destination
                    copySourceRow(snapshot, source, destination)
                    dirtyDestinations.add(destination)
                }
            }
        }
        return dirtyDestinations.drainActive(selectedCount)
    }

    private fun assignSourceToFreeDestination(
        snapshot: CoveragePointRenderSnapshot,
        source: Int,
    ): Int {
        check(freeDestinationCount > 0)
        val destination = freeDestinations[--freeDestinationCount]
        selectedSourceSlots[destination] = source
        selectedKeys[destination] = snapshot.keys[source]
        selectedKeyToDestination[snapshot.keys[source]] = destination
        selectedKeyMaxHeap.add(snapshot.keys[source], selectedKeyToDestination::containsKey)
        sourceSlotToDestination[source] = destination
        copySourceRow(snapshot, source, destination)
        selectedCount++
        return destination
    }

    private fun applyDirtySpans(
        snapshot: CoveragePointRenderSnapshot,
        update: CoveragePointRenderUpdate,
    ): List<CoveragePointSpan> {
        val spans = ArrayList<CoveragePointSpan>()
        update.spans.forEach { span ->
            var source = span.startSlot
            val end = span.startSlot + span.colors.size
            while (source < end) {
                val destination = sourceSlotToDestination.getOrElse(source) { -1 }
                if (destination < 0) {
                    source++
                    continue
                }
                val firstDestination = destination
                var run = 1
                copySourceRow(snapshot, source, destination)
                source++
                while (source < end &&
                    sourceSlotToDestination.getOrElse(source) { -1 } == firstDestination + run
                ) {
                    copySourceRow(snapshot, source, firstDestination + run)
                    source++
                    run++
                }
                spans += CoveragePointSpan(
                    startSlot = firstDestination,
                    positions = selectedPositions.copyOfRange(
                        firstDestination * CoveragePointMeshResources.POSITION_COMPONENTS,
                        (firstDestination + run) * CoveragePointMeshResources.POSITION_COMPONENTS,
                    ),
                    colors = selectedColors.copyOfRange(firstDestination, firstDestination + run),
                    styleRows = selectedStyleRange(firstDestination, firstDestination + run),
                )
            }
        }
        return spans
    }

    private fun selectedIdentityChanged(snapshot: CoveragePointRenderSnapshot): Boolean =
        (0 until selectedCount).any { destination ->
            snapshot.keys[selectedSourceSlots[destination]] != selectedKeys[destination]
        }

    private fun rebuildSelectedRows(snapshot: CoveragePointRenderSnapshot) {
        for (destination in 0 until selectedCount) {
            val source = selectedSourceSlots[destination]
            selectedKeys[destination] = snapshot.keys[source]
            copySourceRow(snapshot, source, destination)
        }
    }

    private fun copySourceRow(snapshot: CoveragePointRenderSnapshot, source: Int, destination: Int) {
        snapshot.positions.copyInto(
            selectedPositions,
            destinationOffset = destination * CoveragePointMeshResources.POSITION_COMPONENTS,
            startIndex = source * CoveragePointMeshResources.POSITION_COMPONENTS,
            endIndex = (source + 1) * CoveragePointMeshResources.POSITION_COMPONENTS,
        )
        selectedColors[destination] = snapshot.colors[source]
        if (snapshot.styleRows.isNotEmpty()) {
            snapshot.styleRows.copyInto(
                selectedStyleRows,
                destination * COVERAGE_RENDERER_STYLE_ROW_BYTES,
                source * COVERAGE_RENDERER_STYLE_ROW_BYTES,
                (source + 1) * COVERAGE_RENDERER_STYLE_ROW_BYTES,
            )
        }
    }

    private fun presentation(
        source: CoveragePointRenderSnapshot,
        reset: Boolean,
        spans: List<CoveragePointSpan>,
    ): CoveragePointRenderSnapshot = source.copy(
        capacity = presentationCapacity,
        count = selectedCount,
        keys = selectedKeys.copyOf(selectedCount),
        positions = selectedPositions.copyOf(
            selectedCount * CoveragePointMeshResources.POSITION_COMPONENTS,
        ),
        colors = selectedColors.copyOf(selectedCount),
        styleRows = selectedStyleRange(0, selectedCount),
        update = CoveragePointRenderUpdate(
            geometryRevision = source.update?.geometryRevision ?: source.revision,
            visibilityRevision = source.update?.visibilityRevision ?: source.revision,
            enabled = source.enabled,
            count = selectedCount,
            spans = spans,
            reset = reset,
        ),
    )

    private fun fullSpan(): List<CoveragePointSpan> =
        if (selectedCount == 0) emptyList() else {
            listOf(
                CoveragePointSpan(
                    0,
                    selectedPositions.copyOf(
                        selectedCount * CoveragePointMeshResources.POSITION_COMPONENTS,
                    ),
                    selectedColors.copyOf(selectedCount),
                    selectedStyleRange(0, selectedCount),
                ),
            )
        }

    private fun dirtySpans(destinations: IntArray): List<CoveragePointSpan> {
        if (destinations.isEmpty()) return emptyList()
        val spans = ArrayList<CoveragePointSpan>()
        var first = destinations[0]
        var previous = first
        fun appendSpan(start: Int, endInclusive: Int) {
            val endExclusive = endInclusive + 1
            spans += CoveragePointSpan(
                startSlot = start,
                positions = selectedPositions.copyOfRange(
                    start * CoveragePointMeshResources.POSITION_COMPONENTS,
                    endExclusive * CoveragePointMeshResources.POSITION_COMPONENTS,
                ),
                colors = selectedColors.copyOfRange(start, endExclusive),
                styleRows = selectedStyleRange(start, endExclusive),
            )
        }
        for (index in 1 until destinations.size) {
            val destination = destinations[index]
            if (destination != previous + 1) {
                appendSpan(first, previous)
                first = destination
            }
            previous = destination
        }
        appendSpan(first, previous)
        return spans
    }

    private fun ensureSourceCapacity(sourceCapacity: Int) {
        if (sourceSlotToDestination.size >= sourceCapacity) return
        sourceSlotToDestination = IntArray(sourceCapacity) { -1 }
    }

    private fun validate(snapshot: CoveragePointRenderSnapshot) {
        require(snapshot.count in 0..snapshot.capacity)
        require(snapshot.keys.size == snapshot.count)
        require(snapshot.positions.size == snapshot.count * CoveragePointMeshResources.POSITION_COMPONENTS)
        require(snapshot.colors.size == snapshot.count)
        require(
            snapshot.styleRows.isEmpty() ||
                snapshot.styleRows.size == snapshot.count * COVERAGE_RENDERER_STYLE_ROW_BYTES,
        )
    }

    private fun selectedStyleRange(start: Int, endExclusive: Int): ByteArray =
        if (!styleRowsPresent) ByteArray(0) else selectedStyleRows.copyOfRange(
            start * COVERAGE_RENDERER_STYLE_ROW_BYTES,
            endExclusive * COVERAGE_RENDERER_STYLE_ROW_BYTES,
        )

    private fun siftUp(heap: IntArray, start: Int, keys: LongArray) {
        var child = start
        while (child > 0) {
            val parent = (child - 1) / 2
            if (compareSource(heap[child], heap[parent], keys) <= 0) return
            val value = heap[parent]
            heap[parent] = heap[child]
            heap[child] = value
            child = parent
        }
    }

    private fun siftDown(heap: IntArray, start: Int, size: Int, keys: LongArray) {
        var parent = start
        while (true) {
            val left = parent * 2 + 1
            if (left >= size) return
            val right = left + 1
            val child = if (right < size && compareSource(heap[right], heap[left], keys) > 0) right else left
            if (compareSource(heap[child], heap[parent], keys) <= 0) return
            val value = heap[parent]
            heap[parent] = heap[child]
            heap[child] = value
            parent = child
        }
    }

    private fun compareSource(first: Int, second: Int, keys: LongArray): Int {
        val keyOrder = keys[first].compareTo(keys[second])
        return if (keyOrder != 0) keyOrder else first.compareTo(second)
    }
}

/** Stateless compatibility helper for a one-off presentation request. */
internal fun CoveragePointRenderSnapshot.boundedForPresentation(
    presentationCapacity: Int,
): CoveragePointRenderSnapshot = CoveragePresentationSelector(presentationCapacity).select(this)
