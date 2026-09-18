package com.uhg0.ar_flutter_plugin_2.sceneview

import com.google.android.filament.IndexBuffer
import com.google.android.filament.MaterialInstance
import com.google.android.filament.RenderableManager
import com.google.android.filament.VertexBuffer
import com.uhg0.ar_flutter_plugin_2.pointcloud.CoveragePointRenderSnapshot
import io.github.sceneview.node.Node

/** Filament resources for one of the coverage voxel visualization modes. */
internal interface CoverageVoxelMeshResources {
    val capacity: Int
    val vertexBuffer: VertexBuffer
    val indexBuffer: IndexBuffer
    val primitiveType: RenderableManager.PrimitiveType

    fun update(
        node: Node,
        snapshot: CoveragePointRenderSnapshot,
        materialInstance: MaterialInstance,
        pointSizePx: Float,
    )

    /** Consumes one descriptor page; implementations keep only one page staged. */
    fun updatePage(
        node: Node,
        page: CoveragePresentationPage,
        materialInstance: MaterialInstance,
        pointSizePx: Float,
        reset: Boolean,
    ) {
        require(page.startSlot + page.count <= capacity)
        val snapshot = CoveragePointRenderSnapshot(
            revision = page.startSlot.toLong(),
            enabled = true,
            capacity = capacity,
            count = page.count,
            keys = page.surfaceIds.copyOf(),
            surfaceIds = page.surfaceIds.copyOf(),
            positions = page.positions.copyOf(),
            colors = page.colors.copyOf(),
            styleRows = page.styleRows.copyOf(),
        )
        update(node, snapshot, materialInstance, pointSizePx)
    }

    /** Removes this mesh from the next draw without destroying retained buffers. */
    fun hide(node: Node)

    /** Queues a full retained-snapshot upload for this resource generation. */
    fun requireRetainedSnapshotUpload()

    fun onRendererFrame()

    /** Invoked only after both native buffer-consumption callbacks release a page. */
    fun setOnUploadPageReleased(listener: () -> Unit)

    fun destroy()
}

/**
 * Releases the outgoing resource before a mutually-exclusive mode can create
 * its replacement. Compose may have detached the Node before the binding's
 * disposal callback runs; that must not defer a ledger-charged resource.
 */
internal fun disposeCoverageResourcesForReplacement(
    node: Node?,
    resources: CoverageVoxelMeshResources,
) {
    if (node != null) {
        node.destroy()
    } else {
        resources.destroy()
    }
}
