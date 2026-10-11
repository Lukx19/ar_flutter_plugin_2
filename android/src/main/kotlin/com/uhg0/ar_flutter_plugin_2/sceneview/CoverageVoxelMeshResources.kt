package com.uhg0.ar_flutter_plugin_2.sceneview

import com.uhg0.ar_flutter_plugin_2.pointcloud.COVERAGE_RENDERER_STYLE_ROW_BYTES

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
        ticket: CoverageDescriptorPageTicket? = null,
        enabled: Boolean = true,
    ) {
        require(page.startSlot + page.count <= capacity)
        val snapshot = CoveragePointRenderSnapshot(
            revision = page.startSlot.toLong(),
            enabled = enabled,
            capacity = capacity,
            count = page.count,
            keys = page.surfaceIds.copyOf(page.count),
            surfaceIds = page.surfaceIds.copyOf(page.count),
            positions = page.positions.copyOf(page.count * 3),
            colors = page.colors.copyOf(page.count),
            styleRows = page.styleRows.copyOf(page.count * COVERAGE_RENDERER_STYLE_ROW_BYTES),
        )
        update(node, snapshot, materialInstance, pointSizePx)
    }

    /** Removes this mesh from the next draw without destroying retained buffers. */
    fun hide(node: Node)

    /** Queues a full retained-snapshot upload for this resource generation. */
    fun requireRetainedSnapshotUpload()

    fun onRendererFrame()

    /** Controls the node/layer gate without discarding candidate upload state. */
    fun setDisplayVisible(visible: Boolean) = Unit

    /** True only after every descriptor page and required index upload drained successfully. */
    fun isWholeDescriptorUploadComplete(descriptor: BoundedCoveragePresentation): Boolean = false

    /** Drops completed source aliases after the compact GPU receipt is latched. */
    fun releaseCompletedDescriptorSource() = Unit

    /** Invoked only after both native buffer-consumption callbacks release a page. */
    fun setOnUploadPageReleased(listener: () -> Unit)

    /** Invoked once every submitted GPU/page/index callback has actually drained. */
    fun setOnResourceDrained(listener: () -> Unit) = Unit

    /** True only after [destroy] has completed all asynchronous resource work. */
    val resourceDrainComplete: Boolean
        get() = true

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
