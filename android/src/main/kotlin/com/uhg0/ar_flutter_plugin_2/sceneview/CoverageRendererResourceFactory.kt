package com.uhg0.ar_flutter_plugin_2.sceneview

import com.uhg0.ar_flutter_plugin_2.pointcloud.VoxelRenderMode

internal enum class CoverageRendererTransitionStrategy {
    COEXIST,
    CLEAR_FIRST,
}

internal data class CoverageRendererResourceAdmission(
    val strategy: CoverageRendererTransitionStrategy,
    val currentBytes: Int,
    val candidateBytes: Int,
    val combinedBytes: Int,
) {
    init {
        require(currentBytes >= 0)
        require(candidateBytes >= 0)
        require(combinedBytes >= 0)
    }
}

internal data class CoverageRendererResourceTransition(
    val admission: CoverageRendererResourceAdmission,
    val hadActiveResource: Boolean,
    val mode: VoxelRenderMode,
    val capacity: Int,
    val rendererGeneration: Long,
)

/**
 * Owns the one active mesh resource generation. Its small interface makes the
 * clear-before-replace rule identical for Compose and the JVM fake backend.
 */
internal class CoverageRendererResourceFactory(
    private val onReplacement: () -> Unit = {},
    private val onDisposal: () -> Unit = {},
    private val admit: (VoxelRenderMode, Int) -> CoverageRendererResourceAdmission =
        { _, _ ->
            CoverageRendererResourceAdmission(
                strategy = CoverageRendererTransitionStrategy.COEXIST,
                currentBytes = 0,
                candidateBytes = 0,
                combinedBytes = 0,
            )
        },
    private val onClearFirst: (CoverageRendererResourceTransition) -> Unit = {},
    private val onCreated: (CoverageRendererResourceTransition) -> Unit = {},
    private val onCreationFailure: (CoverageRendererResourceTransition) -> Unit = {},
) {
    private var active: Any? = null
    private var releaseActive: ((Any) -> Unit)? = null

    @Suppress("UNCHECKED_CAST")
    fun <T : Any> replacePoint(
        mode: VoxelRenderMode,
        capacity: Int,
        owner: String,
        rendererGeneration: Long = 0L,
        create: (VoxelRenderMode, Int, String) -> T,
        release: (T) -> Unit,
    ): T? = replace(mode, capacity, owner, rendererGeneration, create, release)

    fun <T : Any> replaceCube(
        capacity: Int,
        owner: String,
        rendererGeneration: Long = 0L,
        create: (VoxelRenderMode, Int, String) -> T,
        release: (T) -> Unit,
    ): T? = replace(VoxelRenderMode.CUBES, capacity, owner, rendererGeneration, create, release)

    @Suppress("UNCHECKED_CAST")
    private fun <T : Any> replace(
        mode: VoxelRenderMode,
        capacity: Int,
        owner: String,
        rendererGeneration: Long,
        create: (VoxelRenderMode, Int, String) -> T,
        release: (T) -> Unit,
    ): T? {
        val prior = active
        val priorRelease = releaseActive
        val admission = admit(mode, capacity)
        val transition = CoverageRendererResourceTransition(
            admission = admission,
            hadActiveResource = prior != null,
            mode = mode,
            capacity = capacity,
            rendererGeneration = rendererGeneration,
        )
        if (admission.strategy == CoverageRendererTransitionStrategy.CLEAR_FIRST && prior != null) {
            onClearFirst(transition)
            active = null
            releaseActive = null
            priorRelease?.invoke(prior)
            onDisposal()
        }
        // Coexistence is transactional: retain the current owner until the
        // new resource exists and can be installed. A failed allocation then
        // leaves the old renderer valid and mounted. Clear-first has already
        // made the old resource unavailable, so its failure remains fenced.
        val next = try {
            create(mode, capacity, owner)
        } catch (error: Throwable) {
            if (admission.strategy == CoverageRendererTransitionStrategy.CLEAR_FIRST || prior == null) {
                onCreationFailure(transition)
            }
            return null
        }
        try {
            onCreated(transition)
        } catch (error: Throwable) {
            runCatching { release(next) }
            if (admission.strategy == CoverageRendererTransitionStrategy.CLEAR_FIRST ||
                !transition.hadActiveResource
            ) {
                onCreationFailure(transition)
            }
            return null
        }
        active = next
        releaseActive = { value -> release(value as T) }
        if (prior != null && priorRelease != null &&
            admission.strategy == CoverageRendererTransitionStrategy.COEXIST
        ) {
            onReplacement()
            priorRelease(prior)
            onDisposal()
        }
        return next
    }

    fun clear() {
        val prior = active
        val priorRelease = releaseActive
        active = null
        releaseActive = null
        if (prior != null && priorRelease != null) {
            priorRelease(prior)
            onDisposal()
        }
    }
}
