package com.uhg0.ar_flutter_plugin_2.sceneview

import com.google.android.filament.IndexBuffer
import com.google.android.filament.MaterialInstance
import com.google.android.filament.RenderableManager
import com.google.android.filament.VertexBuffer
import com.uhg0.ar_flutter_plugin_2.pointcloud.CoveragePointRenderSnapshot
import io.github.sceneview.node.MeshNode

/** Filament resources for one of the coverage voxel visualization modes. */
internal interface CoverageVoxelMeshResources {
    val capacity: Int
    val vertexBuffer: VertexBuffer
    val indexBuffer: IndexBuffer
    val primitiveType: RenderableManager.PrimitiveType

    fun update(
        node: MeshNode,
        snapshot: CoveragePointRenderSnapshot,
        materialInstance: MaterialInstance,
        pointSizePx: Float,
    )

    fun destroy()
}
