package com.uhg0.ar_flutter_plugin_2.sceneview

import com.google.android.filament.IndexBuffer
import com.google.android.filament.MaterialInstance
import com.google.android.filament.RenderableManager
import com.google.android.filament.VertexBuffer
import com.uhg0.ar_flutter_plugin_2.pointcloud.CoveragePointRenderSnapshot
import io.github.sceneview.node.Node
import org.junit.Assert.assertEquals
import org.junit.Test

class CoverageMeshReplacementDisposalTest {
    @Test
    fun `detached outgoing node still releases its resource before replacement`() {
        val resources = DetachedCoverageResources()

        disposeCoverageResourcesForReplacement(node = null, resources = resources)

        assertEquals(1, resources.destroyCalls)
    }
}

private class DetachedCoverageResources : CoverageVoxelMeshResources {
    var destroyCalls = 0

    override val capacity: Int get() = 1
    override val vertexBuffer: VertexBuffer get() = error("not used by disposal test")
    override val indexBuffer: IndexBuffer get() = error("not used by disposal test")
    override val primitiveType: RenderableManager.PrimitiveType
        get() = error("not used by disposal test")

    override fun update(
        node: Node,
        snapshot: CoveragePointRenderSnapshot,
        materialInstance: MaterialInstance,
        pointSizePx: Float,
    ) = error("not used by disposal test")

    override fun hide(node: Node) = error("not used by disposal test")

    override fun requireRetainedSnapshotUpload() = error("not used by disposal test")

    override fun onRendererFrame() = error("not used by disposal test")

    override fun setOnUploadPageReleased(listener: () -> Unit) = error("not used by disposal test")

    override fun destroy() {
        destroyCalls++
    }
}
