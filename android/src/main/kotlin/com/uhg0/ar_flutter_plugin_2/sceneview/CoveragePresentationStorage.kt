package com.uhg0.ar_flutter_plugin_2.sceneview

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
    lateinit var sortedSurfaceIds: LongArray
        private set
    lateinit var sortedDestinations: IntArray
        private set
    lateinit var freeDestinations: IntArray
        private set
    lateinit var dirtyDestinations: IntArray
        private set
    var dirtyDestinationCount: Int = 0
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
            sortedSurfaceIds = LongArray(requestedCapacity)
            sortedDestinations = IntArray(requestedCapacity)
            freeDestinations = IntArray(requestedCapacity)
            dirtyDestinations = IntArray(requestedCapacity)
            dirtyDestinationCount = 0
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
            return estimatedOwnedStorageBytes(capacity, sourceSlotToDestination.size)
        }

    val sourceCapacity: Int
        get() = sourceSlotToDestination.size

    fun markDirty(destination: Int) {
        require(destination in 0 until capacity)
        if (dirtyDestinationCount < dirtyDestinations.size) {
            dirtyDestinations[dirtyDestinationCount++] = destination
        }
    }

    fun clearDirty() {
        dirtyDestinationCount = 0
    }

    fun drainDirty(maxCount: Int = dirtyDestinationCount): IntArray {
        val count = minOf(maxCount, dirtyDestinationCount)
        val values = dirtyDestinations.copyOf(count)
        values.sort()
        var unique = 0
        values.forEach { value ->
            if (unique == 0 || values[unique - 1] != value) {
                values[unique++] = value
            }
        }
        dirtyDestinationCount = 0
        return values.copyOf(unique)
    }

    companion object {
        fun estimatedOwnedStorageBytes(
            capacity: Int,
            sourceCapacity: Int = capacity,
            // Kept for source compatibility with earlier accounting callers;
            // style values are borrowed from canonical state.
            withStyleRows: Boolean = true,
        ): Int {
            require(capacity > 0)
            require(sourceCapacity >= 0)
            return capacity * (
                Int.SIZE_BYTES +
                    Long.SIZE_BYTES * 2 +
                    Long.SIZE_BYTES +
                    Int.SIZE_BYTES * 3
            ) +
                sourceCapacity * (Int.SIZE_BYTES + Long.SIZE_BYTES + Long.SIZE_BYTES + Int.SIZE_BYTES)
        }
    }
}
