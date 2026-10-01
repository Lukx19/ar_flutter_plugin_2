package com.uhg0.ar_flutter_plugin_2.visibilitygrid

import kotlin.math.floor

/** Session-owned primitive block index. Refresh visits spatial addresses, never the whole row set. */
internal class LiveSurfaceSpatialCache(
    capacity: Int,
    private val voxelMicrometers: Int,
    private val blockVoxels: Int = 16,
) {
    private val tableSize = hashTableCapacity(capacity)
    private val mask = tableSize - 1
    private val ids = LongArray(tableSize)
    private val rowBlocks = IntArray(tableSize) { -1 }
    private val next = IntArray(tableSize) { -1 }
    private val previous = IntArray(tableSize) { -1 }
    private val blockKeys = LongArray(tableSize)
    private val blockStates = ByteArray(tableSize)
    private val heads = IntArray(tableSize) { -1 }
    private val dirtyPositions = IntArray(tableSize) { -1 }
    private val dirtyBlocks = IntArray(tableSize)
    private var dirtyCount = 0
    private val selected = LongArray(capacity)
    private val selectedBlocks = LongArray(tableSize)
    private var selectionEpoch = 0L
    private var indexedRevision = 0L
    private var selectedRevision = -1L
    private var selectedFrustum = -1L
    private val frustum = ExpandedSurfaceFrustum()
    var selectedCount = 0; private set
    var lastBlockProbes = 0; private set
    var refreshCount = 0L; private set
    var flushedBlocks = 0L; private set
    val hasWindow: Boolean get() = frustum.valid && selectedFrustum >= 0
    private val blockMm = voxelMicrometers / 1_000.0 * blockVoxels
    val retainedPrimitiveBytes: Long = tableSize.toLong() * (8 + 4 + 4 + 4 + 8 + 1 + 4 + 4 + 4 + 8) + capacity.toLong() * 8 + 320

    init { require(capacity in 1..100_000 && voxelMicrometers > 0 && blockVoxels > 0) }

    fun upsert(id: SurfaceId, voxel: Voxel) {
        val slot = idSlot(id.value, true)
        val key = blockKey(Math.floorDiv(voxel.x, blockVoxels), Math.floorDiv(voxel.y, blockVoxels), Math.floorDiv(voxel.z, blockVoxels))
        val existingBlock = blockSlot(key, false)
        if (ids[slot] == id.value && rowBlocks[slot] == existingBlock) { markDirty(existingBlock); return }
        if (ids[slot] == id.value) unlink(slot)
        val block = blockSlot(key, true)
        ids[slot] = id.value
        rowBlocks[slot] = block
        next[slot] = heads[block]
        previous[slot] = -1
        if (heads[block] >= 0) previous[heads[block]] = slot
        heads[block] = slot
        markDirty(block)
        indexedRevision++
    }

    fun remove(id: SurfaceId) {
        val slot = idSlot(id.value, false)
        if (slot < 0) return
        unlink(slot)
        eraseRowBucket(slot)
        indexedRevision++
    }

    fun updateWindow(matrix: List<Double>, intrinsics: VisibilityCameraIntrinsics, maximumDepthMm: Double): Boolean {
        if (!frustum.update(matrix, intrinsics, maximumDepthMm)) return false
        if (selectedRevision == indexedRevision && selectedFrustum == frustum.generation) return true
        val minX = floor(frustum.minimumX / blockMm).toInt()
        val minY = floor(frustum.minimumY / blockMm).toInt()
        val minZ = floor(frustum.minimumZ / blockMm).toInt()
        val maxX = floor(frustum.maximumX / blockMm).toInt()
        val maxY = floor(frustum.maximumY / blockMm).toInt()
        val maxZ = floor(frustum.maximumZ / blockMm).toInt()
        val addresses = (maxX.toLong() - minX + 1) * (maxY.toLong() - minY + 1) * (maxZ.toLong() - minZ + 1)
        if (addresses !in 1..MAXIMUM_BLOCK_PROBES || minOf(minX, minY, minZ) < MIN_BLOCK || maxOf(maxX, maxY, maxZ) > MAX_BLOCK) return false
        selectedCount = 0
        lastBlockProbes = 0
        selectionEpoch++
        for (x in minX..maxX) for (y in minY..maxY) for (z in minZ..maxZ) {
            lastBlockProbes++
            if (!frustum.intersects(x * blockMm, y * blockMm, z * blockMm, blockMm)) continue
            val block = blockSlot(blockKey(x, y, z), false)
            if (block < 0) continue
            selectedBlocks[block] = selectionEpoch
            var slot = heads[block]
            while (slot >= 0) {
                check(selectedCount < selected.size)
                selected[selectedCount++] = ids[slot]
                slot = next[slot]
            }
        }
        selectedRevision = indexedRevision
        selectedFrustum = frustum.generation
        refreshCount++
        return true
    }

    fun contains(voxel: Voxel): Boolean {
        if (!hasWindow || selectedRevision != indexedRevision) return false
        val block = blockSlot(blockKey(Math.floorDiv(voxel.x, blockVoxels), Math.floorDiv(voxel.y, blockVoxels),
            Math.floorDiv(voxel.z, blockVoxels)), false)
        if (block < 0 || selectedBlocks[block] != selectionEpoch) return false
        val mm = voxelMicrometers / 1_000.0
        return frustum.intersects(voxel.x * mm, voxel.y * mm, voxel.z * mm, mm)
    }

    /** Borrowed IDs are valid only until the next index/window change on this worker. */
    fun visitSelected(sink: (Long) -> Unit) { for (i in 0 until selectedCount) sink(selected[i]) }

    /** Flush only dirty cold blocks. A failed write leaves its dirty marker for retry. */
    fun flushCold(write: (Long, (sink: (Long) -> Unit) -> Unit) -> Boolean) {
        if (!frustum.valid || selectedRevision != indexedRevision || selectedFrustum != frustum.generation) return
        var retained = 0
        for (i in 0 until dirtyCount) {
            val block = dirtyBlocks[i]
            if (selectedBlocks[block] == selectionEpoch || !write(blockKeys[block]) { sink ->
                    var slot = heads[block]
                    while (slot >= 0) { sink(ids[slot]); slot = next[slot] }
                }) {
                dirtyBlocks[retained++] = block
                dirtyPositions[block] = retained - 1
            } else {
                dirtyPositions[block] = -1
                flushedBlocks++
            }
        }
        dirtyCount = retained
    }

    private fun markDirty(block: Int) {
        if (dirtyPositions[block] < 0) {
            check(dirtyCount < dirtyBlocks.size)
            dirtyPositions[block] = dirtyCount
            dirtyBlocks[dirtyCount++] = block
        }
    }

    private fun unlink(slot: Int) {
        val block = rowBlocks[slot]
        if (previous[slot] < 0) heads[block] = next[slot] else next[previous[slot]] = next[slot]
        if (next[slot] >= 0) previous[next[slot]] = previous[slot]
        next[slot] = -1; previous[slot] = -1; rowBlocks[slot] = -1
        if (heads[block] < 0) eraseBlockBucket(block) else markDirty(block)
    }

    // Backshift deletion retains an empty termination slot after arbitrary churn.
    // Tombstones would eventually make each insertion scan the entire table.
    private fun eraseRowBucket(removed: Int) {
        var hole = removed
        var scan = (hole + 1) and mask
        while (ids[scan] != 0L) {
            if (((scan - hash(ids[scan])) and mask) >= ((scan - hole) and mask)) {
                ids[hole] = ids[scan]; rowBlocks[hole] = rowBlocks[scan]
                next[hole] = next[scan]; previous[hole] = previous[scan]
                if (previous[hole] >= 0) next[previous[hole]] = hole else heads[rowBlocks[hole]] = hole
                if (next[hole] >= 0) previous[next[hole]] = hole
                hole = scan
            }
            scan = (scan + 1) and mask
        }
        ids[hole] = 0; rowBlocks[hole] = -1; next[hole] = -1; previous[hole] = -1
    }

    private fun eraseBlockBucket(removed: Int) {
        // Empty blocks have no cache payload to flush: canonical removals are
        // already committed. Drop the marker without allocating a replacement.
        val position = dirtyPositions[removed]
        if (position >= 0) {
            val last = dirtyBlocks[--dirtyCount]
            if (position < dirtyCount) { dirtyBlocks[position] = last; dirtyPositions[last] = position }
            dirtyPositions[removed] = -1
        }
        var hole = removed
        var scan = (hole + 1) and mask
        while (blockStates[scan].toInt() != 0) {
            if (((scan - hash(blockKeys[scan])) and mask) >= ((scan - hole) and mask)) {
                blockKeys[hole] = blockKeys[scan]; blockStates[hole] = 1
                heads[hole] = heads[scan]; selectedBlocks[hole] = selectedBlocks[scan]
                dirtyPositions[hole] = dirtyPositions[scan]
                if (dirtyPositions[hole] >= 0) dirtyBlocks[dirtyPositions[hole]] = hole
                var row = heads[hole]
                while (row >= 0) { rowBlocks[row] = hole; row = next[row] }
                hole = scan
            }
            scan = (scan + 1) and mask
        }
        blockStates[hole] = 0; heads[hole] = -1; selectedBlocks[hole] = 0; dirtyPositions[hole] = -1
    }

    private fun idSlot(id: Long, insert: Boolean): Int {
        var slot = hash(id)
        repeat(tableSize) {
            when (ids[slot]) {
                id -> return slot
                0L -> return if (insert) slot else -1
            }
            slot = (slot + 1) and mask
        }
        check(!insert) { "surface cache ID capacity exhausted" }
        return -1
    }

    private fun blockSlot(key: Long, insert: Boolean): Int {
        var slot = hash(key)
        repeat(tableSize) {
            if (blockStates[slot].toInt() == 1 && blockKeys[slot] == key) return slot
            if (blockStates[slot].toInt() == 0) {
                if (!insert) return -1
                val chosen = slot
                blockKeys[chosen] = key; blockStates[chosen] = 1
                selectedBlocks[chosen] = 0
                return chosen
            }
            slot = (slot + 1) and mask
        }
        check(!insert) { "surface cache block capacity exhausted" }
        return -1
    }

    private fun hash(key: Long): Int { val mixed = key * -7046029254386353131L; return ((mixed xor (mixed ushr 32)).toInt()) and mask }
    private fun blockKey(x: Int, y: Int, z: Int): Long {
        require(x in MIN_BLOCK..MAX_BLOCK && y in MIN_BLOCK..MAX_BLOCK && z in MIN_BLOCK..MAX_BLOCK)
        return ((x.toLong() and COORDINATE_MASK) shl 42) or ((y.toLong() and COORDINATE_MASK) shl 21) or (z.toLong() and COORDINATE_MASK)
    }

    companion object {
        // Keep at least 20% empty slots at the configured live-row maximum.
        // This uses 131,072 buckets for 100,000 rows instead of 262,144.
        private fun hashTableCapacity(capacity: Int): Int {
            require(capacity in 1..100_000)
            val required = maxOf(2, (capacity * 5 + 3) / 4)
            var size = 2
            while (size < required) size = size shl 1
            return size
        }

        private const val MIN_BLOCK = -1_048_576
        private const val MAX_BLOCK = 1_048_575
        private const val COORDINATE_MASK = 0x1fffffL
        private const val MAXIMUM_BLOCK_PROBES = 1_000_000L
    }
}
