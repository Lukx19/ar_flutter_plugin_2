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
 * Bounds a semantic snapshot for one presentation mode. For an over-cap
 * snapshot the chosen keys are stable (ascending identity), and the complete
 * selected range becomes a reset so stale dirty spans cannot address a
 * different slot after selection changes.
 */
internal fun CoveragePointRenderSnapshot.boundedForPresentation(
    presentationCapacity: Int,
): CoveragePointRenderSnapshot {
    require(presentationCapacity > 0)
    require(count in 0..capacity)
    require(keys.size == count)
    require(positions.size == count * CoveragePointMeshResources.POSITION_COMPONENTS)
    require(colors.size == count)
    if (count <= presentationCapacity) return copy(capacity = presentationCapacity)

    val selected = keys.indices.sortedBy { keys[it] }.take(presentationCapacity)
    val selectedKeys = LongArray(selected.size)
    val selectedPositions = FloatArray(selected.size * CoveragePointMeshResources.POSITION_COMPONENTS)
    val selectedColors = IntArray(selected.size)
    selected.forEachIndexed { destination, source ->
        selectedKeys[destination] = keys[source]
        selectedColors[destination] = colors[source]
        positions.copyInto(
            selectedPositions,
            destinationOffset = destination * CoveragePointMeshResources.POSITION_COMPONENTS,
            startIndex = source * CoveragePointMeshResources.POSITION_COMPONENTS,
            endIndex = (source + 1) * CoveragePointMeshResources.POSITION_COMPONENTS,
        )
    }
    return copy(
        capacity = presentationCapacity,
        count = selected.size,
        keys = selectedKeys,
        positions = selectedPositions,
        colors = selectedColors,
        update = CoveragePointRenderUpdate(
            geometryRevision = update?.geometryRevision ?: revision,
            visibilityRevision = update?.visibilityRevision ?: revision,
            enabled = enabled,
            count = selected.size,
            spans = listOf(CoveragePointSpan(0, selectedPositions, selectedColors)),
            reset = true,
        ),
    )
}
