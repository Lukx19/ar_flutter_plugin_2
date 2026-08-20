package com.uhg0.ar_flutter_plugin_2.sceneview

import com.uhg0.ar_flutter_plugin_2.pointcloud.CoveragePointRenderSnapshot
import com.uhg0.ar_flutter_plugin_2.pointcloud.CoveragePointRenderUpdate
import com.uhg0.ar_flutter_plugin_2.pointcloud.CoveragePointSpan

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
    // colour, and slot/revision bookkeeping. The semantic grid's 100k state
    // is deliberately not charged here; it belongs to M0b's 16 MiB ledger.
    const val NATIVE_SELECTION_BYTES_PER_ROW = 32
    const val NATIVE_SELECTION_BYTES = CENTROID_CAPACITY * NATIVE_SELECTION_BYTES_PER_ROW
    const val AUXILIARY_ROW_BYTES = 16
    const val AUXILIARY_BYTES =
        (WARM_PROXY_CAPACITY + COLD_OVERVIEW_CAPACITY + GLYPH_CAPACITY + DEBUG_ROW_CAPACITY) *
            AUXILIARY_ROW_BYTES

    // Point resources own 36 bytes per row; cube resources own 736 bytes per
    // row. Keeping all three dormant mode resources under this bound makes a
    // replacement safe even while Compose retires the previous node.
    val allModeOwnedBufferBytes: Int =
        RAW_POINT_CAPACITY * CoveragePointMeshResources.OWNED_BYTES_PER_ROW +
            CENTROID_CAPACITY * CoveragePointMeshResources.OWNED_BYTES_PER_ROW +
            CUBE_CAPACITY * CoverageCubeMeshResources.OWNED_BYTES_PER_VOXEL

    val maximumActiveRendererBytes: Int =
        CUBE_CAPACITY * CoverageCubeMeshResources.OWNED_BYTES_PER_VOXEL +
            NATIVE_SELECTION_BYTES + AUXILIARY_BYTES

    init {
        check(allModeOwnedBufferBytes <= SHARED_OWNED_BUFFER_LIMIT_BYTES)
        check(maximumActiveRendererBytes <= SHARED_OWNED_BUFFER_LIMIT_BYTES)
    }
}

/**
 * Persistent, bounded presentation selector. It performs a complete source
 * pass only when its identity basis changes (initial mount, a resync, or a
 * source-slot rewrite). Ordinary changes are translated through the retained
 * source-slot-to-presentation-slot table, avoiding a 20k sort and an 8k reset
 * for every revision.
 *
 * The policy is deterministic: keep the lowest identities, with source-slot
 * order as the tie-breaker. New candidates can replace the current largest
 * identity; an identity replacement resets only because presentation slots
 * deliberately changed. Otherwise identities and slots remain stable.
 */
internal class CoveragePresentationSelector(
    private val presentationCapacity: Int,
) {
    private val selectedSourceSlots = IntArray(presentationCapacity)
    private var selectedCount = 0
    private var sourceCount = 0
    private var sourceSlotToDestination = IntArray(0)
    private var selectedKeys = LongArray(0)
    private var selectedPositions = FloatArray(0)
    private var selectedColors = IntArray(0)
    private var initialized = false

    init {
        require(presentationCapacity > 0)
    }

    fun select(snapshot: CoveragePointRenderSnapshot): CoveragePointRenderSnapshot {
        validate(snapshot)
        ensureSourceCapacity(snapshot.capacity)
        val sourceRewritten = initialized && selectedIdentityChanged(snapshot)
        if (!initialized || sourceRewritten || snapshot.count < sourceCount) {
            initialize(snapshot)
            return presentation(snapshot, reset = true, spans = fullSpan())
        }

        val membershipChanged = acceptNewCandidates(snapshot)
        if (membershipChanged) {
            rebuildSelectedRows(snapshot)
            sourceCount = snapshot.count
            return presentation(snapshot, reset = true, spans = fullSpan())
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
        for (destination in selectedCount - 1 downTo 0) {
            selectedSourceSlots[destination] = heap[0]
            heap[0] = heap[--heapSize]
            if (heapSize > 0) siftDown(heap, 0, heapSize, snapshot.keys)
        }
        rebuildSlotMap()
        rebuildSelectedRows(snapshot)
        sourceCount = snapshot.count
        initialized = true
    }

    private fun acceptNewCandidates(snapshot: CoveragePointRenderSnapshot): Boolean {
        var changed = false
        for (source in sourceCount until snapshot.count) {
            if (selectedCount < presentationCapacity) {
                insertSelected(source, snapshot.keys)
                changed = true
            } else if (selectedCount > 0 &&
                compareSource(source, selectedSourceSlots[selectedCount - 1], snapshot.keys) < 0
            ) {
                selectedSourceSlots[selectedCount - 1] = source
                siftSelectedLeft(selectedCount - 1, snapshot.keys)
                changed = true
            }
        }
        if (changed) rebuildSlotMap()
        return changed
    }

    private fun insertSelected(source: Int, keys: LongArray) {
        var destination = selectedCount++
        selectedSourceSlots[destination] = source
        siftSelectedLeft(destination, keys)
    }

    private fun siftSelectedLeft(start: Int, keys: LongArray) {
        var destination = start
        while (destination > 0 &&
            compareSource(selectedSourceSlots[destination], selectedSourceSlots[destination - 1], keys) < 0
        ) {
            val previous = selectedSourceSlots[destination - 1]
            selectedSourceSlots[destination - 1] = selectedSourceSlots[destination]
            selectedSourceSlots[destination] = previous
            destination--
        }
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
        selectedKeys = LongArray(selectedCount)
        selectedPositions = FloatArray(selectedCount * CoveragePointMeshResources.POSITION_COMPONENTS)
        selectedColors = IntArray(selectedCount)
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
    }

    private fun rebuildSlotMap() {
        sourceSlotToDestination.fill(-1)
        for (destination in 0 until selectedCount) {
            sourceSlotToDestination[selectedSourceSlots[destination]] = destination
        }
    }

    private fun presentation(
        source: CoveragePointRenderSnapshot,
        reset: Boolean,
        spans: List<CoveragePointSpan>,
    ): CoveragePointRenderSnapshot = source.copy(
        capacity = presentationCapacity,
        count = selectedCount,
        keys = selectedKeys.copyOf(),
        positions = selectedPositions.copyOf(),
        colors = selectedColors.copyOf(),
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
            listOf(CoveragePointSpan(0, selectedPositions.copyOf(), selectedColors.copyOf()))
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
    }

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
