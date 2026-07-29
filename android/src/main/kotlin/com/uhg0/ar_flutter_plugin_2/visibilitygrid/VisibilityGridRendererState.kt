package com.uhg0.ar_flutter_plugin_2.visibilitygrid

import com.uhg0.ar_flutter_plugin_2.pointcloud.CoveragePointRenderSnapshot
import com.uhg0.ar_flutter_plugin_2.pointcloud.CoveragePointRenderUpdate
import com.uhg0.ar_flutter_plugin_2.pointcloud.CoveragePointSpan
import com.uhg0.ar_flutter_plugin_2.pointcloud.VoxelRenderMode
import com.uhg0.ar_flutter_plugin_2.pointcloud.identityGridRotation
import java.util.TreeSet

/**
 * Dense renderer-row mirror of authoritative native grid geometry.
 *
 * Removal uses swap-remove, so both point and cube modes share the same
 * contiguous rows and every removed row is immediately reusable.
 */
class VisibilityGridRendererState(
    val capacity: Int,
    private val defaultColor: Int = 0xFFFF0000.toInt(),
) {
    init {
        require(capacity in 1..100_000)
    }

    private val keys = LongArray(capacity)
    private val positions = FloatArray(capacity * 3)
    private val colors = IntArray(capacity)
    private val rowsByKey = HashMap<Long, Int>(capacity)
    private val dirtyRows = TreeSet<Int>()
    private var group: VisibilityGridGroupConfig? = null
    private var count = 0
    private var renderRevision = 0L
    private var geometryRevision = 0L
    private var visibilityRevision = 0L
    private var enabled = true
    private var mode = VoxelRenderMode.CENTROIDS
    private var disposed = false
    private var resetUpload = true
    private var ignoredVisibilityKeyCount = 0L

    val freeRowCount: Int
        @Synchronized get() = capacity - count

    val isDisposed: Boolean
        @Synchronized get() = disposed

    val currentGeometryRevision: Long
        @Synchronized get() = geometryRevision

    val currentVisibilityRevision: Long
        @Synchronized get() = visibilityRevision

    val ignoredDeletedVisibilityKeys: Long
        @Synchronized get() = ignoredVisibilityKeyCount

    @Synchronized
    fun startGroup(
        config: VisibilityGridGroupConfig,
        geometryRevision: Long,
        visibilityRevision: Long = 0,
        restoredKeys: LongArray,
    ) {
        ensureActive()
        require(config.capacity <= capacity)
        require(geometryRevision >= 0)
        require(restoredKeys.size <= config.capacity)
        require(restoredKeys.toSet().size == restoredKeys.size)
        clearRows()
        group = config
        this.geometryRevision = geometryRevision
        require(visibilityRevision >= 0)
        this.visibilityRevision = visibilityRevision
        ignoredVisibilityKeyCount = 0
        restoredKeys.forEach(::append)
        dirtyRows += 0 until count
        resetUpload = true
        renderRevision++
    }

    @Synchronized
    fun applyGeometry(
        revision: Long,
        reset: Boolean,
        upsertKeys: LongArray,
        removalKeys: LongArray,
    ): Boolean {
        ensureActive()
        val active = group ?: return false
        if ((!reset && revision != geometryRevision + 1) ||
            (reset && revision <= geometryRevision) ||
            upsertKeys.toSet().size != upsertKeys.size ||
            removalKeys.toSet().size != removalKeys.size ||
            upsertKeys.any(removalKeys.toSet()::contains)
        ) {
            return false
        }
        val finalKeys =
            if (reset) {
                upsertKeys.toSet()
            } else {
                rowsByKey.keys.toMutableSet().apply {
                    removeAll(removalKeys.toSet())
                    addAll(upsertKeys.toSet())
                }
            }
        if (finalKeys.size > active.capacity) return false

        if (reset) {
            clearRows()
            upsertKeys.forEach(::append)
            dirtyRows += 0 until count
            resetUpload = true
        } else {
            removalKeys.forEach(::remove)
            upsertKeys.forEach { key ->
                if (key !in rowsByKey) append(key)
            }
        }
        geometryRevision = revision
        renderRevision++
        return true
    }

    @Synchronized
    fun applyVisibility(
        namedGeometryRevision: Long,
        nextVisibilityRevision: Long,
        patchKeys: LongArray,
        patchColors: IntArray,
    ): Boolean {
        ensureActive()
        if (namedGeometryRevision != geometryRevision ||
            nextVisibilityRevision <= visibilityRevision ||
            patchKeys.size != patchColors.size ||
            patchKeys.toSet().size != patchKeys.size
        ) {
            return false
        }
        patchKeys.indices.forEach { index ->
            val row = rowsByKey[patchKeys[index]]
            if (row == null) {
                ignoredVisibilityKeyCount++
                return@forEach
            }
            if (colors[row] != patchColors[index]) {
                colors[row] = patchColors[index]
                dirtyRows += row
            }
        }
        visibilityRevision = nextVisibilityRevision
        renderRevision++
        return true
    }

    @Synchronized
    fun setEnabled(value: Boolean) {
        ensureActive()
        if (enabled == value) return
        enabled = value
        renderRevision++
    }

    @Synchronized
    fun setRenderMode(value: VoxelRenderMode) {
        ensureActive()
        if (mode == value) return
        mode = value
        renderRevision++
    }

    @Synchronized
    fun snapshot(): CoveragePointRenderSnapshot {
        ensureActive()
        val snapshotKeys = keys.copyOf(count)
        val snapshotPositions = positions.copyOf(count * 3)
        val snapshotColors = colors.copyOf(count)
        val spans = dirtySpans()
        val update =
            CoveragePointRenderUpdate(
                geometryRevision = geometryRevision,
                visibilityRevision = visibilityRevision,
                enabled = enabled,
                count = count,
                spans = spans,
                reset = resetUpload,
            )
        dirtyRows.clear()
        resetUpload = false
        return CoveragePointRenderSnapshot(
            revision = renderRevision,
            enabled = enabled,
            capacity = capacity,
            count = count,
            keys = snapshotKeys,
            positions = snapshotPositions,
            colors = snapshotColors,
            gridRotationWorld = identityGridRotation(),
            update = update,
        )
    }

    @Synchronized
    fun markUploadFailed() {
        ensureActive()
        resetUpload = true
        dirtyRows += 0 until count
        renderRevision++
    }

    @Synchronized
    fun stopGroup() {
        if (disposed) return
        clearRows()
        group = null
        geometryRevision = 0
        visibilityRevision = 0
        ignoredVisibilityKeyCount = 0
        resetUpload = true
        renderRevision++
    }

    @Synchronized
    fun dispose() {
        if (disposed) return
        stopGroup()
        disposed = true
    }

    private fun append(key: Long) {
        check(count < capacity)
        val row = count++
        keys[row] = key
        colors[row] = defaultColor
        writePosition(row, key)
        rowsByKey[key] = row
        dirtyRows += row
    }

    private fun remove(key: Long) {
        val row = rowsByKey.remove(key) ?: return
        val last = --count
        if (row != last) {
            val movedKey = keys[last]
            keys[row] = movedKey
            colors[row] = colors[last]
            positions[last * 3].let { positions[row * 3] = it }
            positions[last * 3 + 1].let { positions[row * 3 + 1] = it }
            positions[last * 3 + 2].let { positions[row * 3 + 2] = it }
            rowsByKey[movedKey] = row
            dirtyRows += row
        }
        keys[last] = 0
        colors[last] = 0
    }

    private fun writePosition(row: Int, key: Long) {
        val active = checkNotNull(group)
        val coordinates = unpackVisibilityGridKey(key)
        val half = active.voxelSizeMeters / 2.0
        val x = coordinates[0] * active.voxelSizeMeters + half
        val y = coordinates[1] * active.voxelSizeMeters + half
        val z = coordinates[2] * active.voxelSizeMeters + half
        val matrix = active.worldFromGroupGl
        positions[row * 3] =
            (matrix[0] * x + matrix[4] * y + matrix[8] * z + matrix[12]).toFloat()
        positions[row * 3 + 1] =
            (matrix[1] * x + matrix[5] * y + matrix[9] * z + matrix[13]).toFloat()
        positions[row * 3 + 2] =
            (matrix[2] * x + matrix[6] * y + matrix[10] * z + matrix[14]).toFloat()
    }

    private fun dirtySpans(): List<CoveragePointSpan> {
        val activeRows = dirtyRows.filter { it < count }
        if (activeRows.isEmpty()) return emptyList()
        val spans = mutableListOf<CoveragePointSpan>()
        var cursor = 0
        while (cursor < activeRows.size) {
            val start = activeRows[cursor]
            var end = start
            cursor++
            while (cursor < activeRows.size && activeRows[cursor] == end + 1) {
                end = activeRows[cursor++]
            }
            spans +=
                CoveragePointSpan(
                    startSlot = start,
                    positions = positions.copyOfRange(start * 3, (end + 1) * 3),
                    colors = colors.copyOfRange(start, end + 1),
                )
        }
        return spans
    }

    private fun clearRows() {
        keys.fill(0)
        positions.fill(0f)
        colors.fill(0)
        rowsByKey.clear()
        dirtyRows.clear()
        count = 0
    }

    private fun ensureActive() {
        check(!disposed) { "VisibilityGridRendererState is disposed" }
    }
}
