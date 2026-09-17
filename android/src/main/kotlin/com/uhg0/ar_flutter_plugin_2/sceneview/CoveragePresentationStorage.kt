package com.uhg0.ar_flutter_plugin_2.sceneview

import com.uhg0.ar_flutter_plugin_2.pointcloud.COVERAGE_RENDERER_STYLE_ROW_BYTES
import com.uhg0.ar_flutter_plugin_2.visibilitygrid.DirtyRowQueue
import com.uhg0.ar_flutter_plugin_2.visibilitygrid.LongRowIndex
import com.uhg0.ar_flutter_plugin_2.visibilitygrid.SelectedKeyMaxHeap

/**
 * Primitive backing storage retained by one presentation selector.
 *
 * The arrays are allocated only after a mode is selected and are resized to
 * that mode's capacity. This keeps the retained selector bounded by the
 * active mode rather than silently retaining centroid-sized storage for every
 * presentation.
 */
internal class CoveragePresentationStorage(
    private val maximumCapacity: Int,
) {
    var capacity: Int = 0
        private set
    var styleRowsPresent: Boolean = false
        private set

    lateinit var selectedSourceSlots: IntArray
        private set
    lateinit var selectedKeys: LongArray
        private set
    lateinit var selectedSurfaceIds: LongArray
        private set
    lateinit var selectedPositions: FloatArray
        private set
    lateinit var selectedColors: IntArray
        private set
    lateinit var selectedStyleRows: ByteArray
        private set
    lateinit var selectedKeyToDestination: LongRowIndex
        private set
    lateinit var selectedKeyMaxHeap: SelectedKeyMaxHeap
        private set
    lateinit var freeDestinations: IntArray
        private set
    lateinit var dirtyDestinations: DirtyRowQueue
        private set

    var sourceSlotToDestination = IntArray(0)
        private set
    var sourceRankingSurfaceIds = LongArray(0)
        private set
    var sourceRankingKeys = LongArray(0)
        private set
    var sourceRankingStyles = IntArray(0)
        private set

    init {
        require(maximumCapacity > 0)
    }

    fun ensurePresentationCapacity(requestedCapacity: Int, withStyleRows: Boolean) {
        require(requestedCapacity in 1..maximumCapacity)
        val capacityChanged = capacity != requestedCapacity
        val styleChanged = styleRowsPresent != withStyleRows
        if (!capacityChanged && !styleChanged) return
        if (capacityChanged) {
            capacity = requestedCapacity
            selectedSourceSlots = IntArray(requestedCapacity)
            selectedKeys = LongArray(requestedCapacity)
            selectedSurfaceIds = LongArray(requestedCapacity)
            selectedPositions = FloatArray(requestedCapacity * CoveragePointMeshResources.POSITION_COMPONENTS)
            selectedColors = IntArray(requestedCapacity)
            selectedKeyToDestination = LongRowIndex(requestedCapacity)
            selectedKeyMaxHeap = SelectedKeyMaxHeap(requestedCapacity)
            freeDestinations = IntArray(requestedCapacity)
            dirtyDestinations = DirtyRowQueue(requestedCapacity)
        }
        if (capacityChanged || styleChanged) {
            selectedStyleRows = if (withStyleRows) {
                ByteArray(requestedCapacity * COVERAGE_RENDERER_STYLE_ROW_BYTES)
            } else {
                ByteArray(0)
            }
        }
        styleRowsPresent = withStyleRows
    }

    fun ensureSourceCapacity(sourceCapacity: Int) {
        if (sourceSlotToDestination.size >= sourceCapacity) return
        val previousSourceCapacity = sourceSlotToDestination.size
        sourceSlotToDestination = sourceSlotToDestination.copyOf(sourceCapacity).also { values ->
            for (index in previousSourceCapacity until values.size) values[index] = -1
        }
        sourceRankingSurfaceIds = sourceRankingSurfaceIds.copyOf(sourceCapacity)
        sourceRankingKeys = sourceRankingKeys.copyOf(sourceCapacity)
        sourceRankingStyles = sourceRankingStyles.copyOf(sourceCapacity)
    }

    val ownedStorageBytes: Int
        get() {
            if (capacity == 0) return 0
            return estimatedOwnedStorageBytes(capacity, sourceSlotToDestination.size, styleRowsPresent)
        }

    companion object {
        fun estimatedOwnedStorageBytes(
            capacity: Int,
            sourceCapacity: Int = capacity,
            withStyleRows: Boolean = true,
        ): Int {
            require(capacity > 0)
            require(sourceCapacity >= 0)
            return capacity * (
                Int.SIZE_BYTES +
                    Long.SIZE_BYTES * 2 +
                    CoveragePointMeshResources.POSITION_COMPONENTS * Float.SIZE_BYTES +
                    Int.SIZE_BYTES +
                    if (withStyleRows) COVERAGE_RENDERER_STYLE_ROW_BYTES else 0
            ) +
                LongRowIndex.ownedStorageBytes(capacity) +
                SelectedKeyMaxHeap.ownedStorageBytes(capacity) +
                DirtyRowQueue.ownedStorageBytes(capacity) +
                capacity * Int.SIZE_BYTES +
                sourceCapacity * (Int.SIZE_BYTES + Long.SIZE_BYTES + Long.SIZE_BYTES + Int.SIZE_BYTES)
        }
    }
}
