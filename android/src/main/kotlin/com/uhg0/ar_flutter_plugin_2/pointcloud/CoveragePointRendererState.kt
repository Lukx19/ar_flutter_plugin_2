package com.uhg0.ar_flutter_plugin_2.pointcloud

/** Accumulated renderer state for Dart-owned occupied voxel centroids. */
class CoveragePointRendererState(
    private val config: PointCloudNativeConfig,
) {
    private val keys = LongArray(config.renderCapacity)
    private val positions = FloatArray(config.renderCapacity * 3)
    private val colors = IntArray(config.renderCapacity)
    private val slotsByKey = HashMap<Long, Int>(config.renderCapacity)
    private var count = 0
    private var enabled = config.enabled
    private var renderMode = config.voxelRenderMode
    private var revision = 0L
    private var geometryRevision = 0L
    private var visibilityRevision = 0L
    private val pendingDirtySlots = java.util.TreeSet<Int>()
    private var disposed = false
    private var lastAppliedColorEpoch = 0L
    private var emittedFrames = 0L
    private var coalescedFrames = 0L
    private var droppedVoxelRows = 0L
    private var unchangedVoxelRows = 0L
    private var fullResyncUploads = 0L
    private var partialUploads = 0L
    private var uploadedBytes = 0L
    private var coalescedRendererUpdates = 0L

    @Synchronized
    fun updateVoxels(
        epoch: Long,
        patchKeys: LongArray,
        patchPositions: FloatArray,
        patchColors: IntArray,
    ): Boolean {
        ensureActive()
        require(epoch >= 0)
        require(patchPositions.size == patchKeys.size * 3)
        require(patchColors.size == patchKeys.size)
        require(patchKeys.size <= config.renderCapacity)
        require(patchPositions.all { it.isFinite() })
        if (epoch < lastAppliedColorEpoch) return false
        val hadPendingDirtySlots = pendingDirtySlots.isNotEmpty()
        var changed = false
        for (index in patchKeys.indices) {
            val key = patchKeys[index]
            val existing = slotsByKey[key]
            val slot = existing ?: allocateSlot(key)
            if (slot == null) {
                droppedVoxelRows++
                continue
            }
            val sourceOffset = index * 3
            val targetOffset = slot * 3
            val nextX = patchPositions[sourceOffset]
            val nextY = patchPositions[sourceOffset + 1]
            val nextZ = patchPositions[sourceOffset + 2]
            val nextColor = patchColors[index]
            if (existing == null ||
                positions[targetOffset] != nextX ||
                positions[targetOffset + 1] != nextY ||
                positions[targetOffset + 2] != nextZ ||
                colors[slot] != nextColor
            ) {
                positions[targetOffset] = nextX
                positions[targetOffset + 1] = nextY
                positions[targetOffset + 2] = nextZ
                colors[slot] = nextColor
                // Existing voxels can change color after a saved capture.
                // Keep the slot in the dirty set so the retained Filament
                // mesh uploads the recolor, not just the Dart-side state.
                pendingDirtySlots += slot
                changed = true
            } else {
                unchangedVoxelRows++
            }
        }
        lastAppliedColorEpoch = epoch
        emittedFrames++
        if (changed) revision++
        if (changed) geometryRevision++
        if (changed && hadPendingDirtySlots) coalescedRendererUpdates++
        return true
    }

    @Synchronized
    fun setEnabled(value: Boolean) {
        ensureActive()
        if (enabled == value) return
        enabled = value
        visibilityRevision++
        revision++
    }

    @Synchronized
    fun setRenderMode(value: VoxelRenderMode) {
        ensureActive()
        if (renderMode == value) return
        renderMode = value
        revision++
    }

    @Synchronized
    fun snapshot(): CoveragePointRenderSnapshot {
        ensureActive()
        return snapshotLocked(includeUpdate = false)
    }

    @Synchronized
    fun snapshotIfChanged(
        previousRevision: Long,
        force: Boolean = false,
    ): CoveragePointRenderSnapshot? {
        ensureActive()
        if (!force && revision == previousRevision) return null
        return snapshotLocked(includeUpdate = true, reset = force)
    }

    private fun snapshotLocked(
        includeUpdate: Boolean,
        reset: Boolean = false,
    ): CoveragePointRenderSnapshot {
        val snapshotKeys = LongArray(count)
        val snapshotPositions = FloatArray(count * 3)
        val snapshotColors = IntArray(count)
        var target = 0
        for (slot in 0 until count) {
            snapshotKeys[target] = keys[slot]
            snapshotColors[target] = colors[slot]
            positions.copyInto(snapshotPositions, target * 3, slot * 3, slot * 3 + 3)
            target++
        }
        val update = if (includeUpdate) {
            val slots = pendingDirtySlots.toList()
            val spans = mutableListOf<CoveragePointSpan>()
            var cursor = 0
            while (cursor < slots.size) {
                val start = slots[cursor]
                var end = start
                cursor++
                while (cursor < slots.size && slots[cursor] == end + 1) {
                    end = slots[cursor]
                    cursor++
                }
                spans += CoveragePointSpan(
                    startSlot = start,
                    positions = positions.copyOfRange(start * 3, (end + 1) * 3),
                    colors = colors.copyOfRange(start, end + 1),
                )
            }
            pendingDirtySlots.clear()
            if (reset) {
                fullResyncUploads++
            } else if (spans.isNotEmpty()) {
                partialUploads++
            }
            uploadedBytes += spans.sumOf { span ->
                span.positions.size.toLong() * Float.SIZE_BYTES +
                    span.colors.size.toLong() * 4L
            }
            CoveragePointRenderUpdate(
                geometryRevision = geometryRevision,
                visibilityRevision = visibilityRevision,
                enabled = enabled,
                count = target,
                spans = spans,
                reset = reset,
            )
        } else null
        return CoveragePointRenderSnapshot(
            revision = revision,
            enabled = enabled,
            capacity = config.renderCapacity,
            count = target,
            keys = snapshotKeys,
            positions = snapshotPositions,
            colors = snapshotColors,
            update = update,
        )
    }

    @Synchronized
    fun stats(fps: Double = 0.0): PointCloudRenderStats = PointCloudRenderStats(
        fps = fps,
        livePointCount = count,
        bufferBytes = config.renderCapacity * BYTES_PER_ROW,
        emittedFrames = emittedFrames,
        coalescedFrames = coalescedFrames,
        lastAppliedColorEpoch = lastAppliedColorEpoch,
        fixedStateArrayBytes = config.renderCapacity.toLong() * FIXED_STATE_BYTES_PER_ROW,
        keyIndexEntries = count.toLong(),
        rendererDesiredBytes = count.toLong() * rendererVertexBytes() +
            count.toLong() * rendererIndexBytes(),
        uploadStagingBytes = config.renderCapacity.toLong() * rendererVertexBytes(),
        gpuVertexBytes = config.renderCapacity.toLong() * rendererVertexBytes(),
        gpuIndexBytes = config.renderCapacity.toLong() * rendererIndexBytes(),
        droppedVoxelRows = droppedVoxelRows,
        unchangedVoxelRows = unchangedVoxelRows,
        fullResyncUploads = fullResyncUploads,
        partialUploads = partialUploads,
        uploadedBytes = uploadedBytes,
        coalescedRendererUpdates = coalescedRendererUpdates,
    )

    @Synchronized
    fun recordCoalescedFrame() {
        ensureActive()
        coalescedFrames++
    }

    @Synchronized
    fun clear() {
        if (disposed) return
        keys.fill(0L)
        slotsByKey.clear()
        count = 0
        lastAppliedColorEpoch = 0L
        emittedFrames = 0L
        coalescedFrames = 0L
        geometryRevision = 0L
        visibilityRevision = 0L
        pendingDirtySlots.clear()
        droppedVoxelRows = 0L
        unchangedVoxelRows = 0L
        fullResyncUploads = 0L
        partialUploads = 0L
        uploadedBytes = 0L
        coalescedRendererUpdates = 0L
        revision++
    }

    @Synchronized
    fun dispose() {
        if (disposed) return
        clear()
        disposed = true
    }

    @Synchronized
    fun isDisposed(): Boolean = disposed

    private fun allocateSlot(key: Long): Int? {
        if (count >= config.renderCapacity) return null
        val slot = count
        keys[slot] = key
        slotsByKey[key] = slot
        pendingDirtySlots += slot
        count++
        return slot
    }

    private fun ensureActive() {
        check(!disposed) { "CoveragePointRendererState is disposed" }
    }

    private fun rendererVertexBytes(): Long =
        RENDERER_BYTES_PER_ROW * if (renderMode == VoxelRenderMode.CUBES) 8L else 1L

    private fun rendererIndexBytes(): Long =
        INDEX_BYTES_PER_ROW * if (renderMode == VoxelRenderMode.CUBES) 36L else 1L

    private companion object {
        const val BYTES_PER_ROW = 28
        // 64-bit key + float3 position + packed ARGB color. There is no
        // active bitmap: slots are dense from zero through count.
        const val FIXED_STATE_BYTES_PER_ROW = 24L
        const val RENDERER_BYTES_PER_ROW = 16L
        const val INDEX_BYTES_PER_ROW = 4L
    }
}
