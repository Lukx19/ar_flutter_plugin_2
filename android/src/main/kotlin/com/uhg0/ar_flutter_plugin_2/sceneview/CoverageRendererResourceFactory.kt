package com.uhg0.ar_flutter_plugin_2.sceneview

import com.uhg0.ar_flutter_plugin_2.pointcloud.VoxelRenderMode

/**
 * Owns the one active mesh resource generation. Its small interface makes the
 * clear-before-replace rule identical for Compose and the JVM fake backend.
 */
internal class CoverageRendererResourceFactory {
    private var active: Any? = null
    private var releaseActive: ((Any) -> Unit)? = null

    @Suppress("UNCHECKED_CAST")
    fun <T : Any> replacePoint(
        mode: VoxelRenderMode,
        capacity: Int,
        owner: String,
        create: (VoxelRenderMode, Int, String) -> T,
        release: (T) -> Unit,
    ): T = replace(mode, capacity, owner, create, release)

    fun <T : Any> replaceCube(
        capacity: Int,
        owner: String,
        create: (VoxelRenderMode, Int, String) -> T,
        release: (T) -> Unit,
    ): T = replace(VoxelRenderMode.CUBES, capacity, owner, create, release)

    private fun <T : Any> replace(
        mode: VoxelRenderMode,
        capacity: Int,
        owner: String,
        create: (VoxelRenderMode, Int, String) -> T,
        release: (T) -> Unit,
    ): T {
        val prior = active
        val priorRelease = releaseActive
        active = null
        releaseActive = null
        if (prior != null && priorRelease != null) priorRelease(prior)
        return create(mode, capacity, owner).also { next ->
            active = next
            releaseActive = { value -> release(value as T) }
        }
    }

    fun clear() {
        val prior = active
        val priorRelease = releaseActive
        active = null
        releaseActive = null
        if (prior != null && priorRelease != null) priorRelease(prior)
    }
}
