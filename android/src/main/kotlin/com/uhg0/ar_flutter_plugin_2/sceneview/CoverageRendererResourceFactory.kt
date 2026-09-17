package com.uhg0.ar_flutter_plugin_2.sceneview

import com.uhg0.ar_flutter_plugin_2.pointcloud.VoxelRenderMode

internal enum class CoverageRendererTransitionStrategy {
    COEXIST,
    CLEAR_FIRST,
    REJECT,
}

internal val CoverageRendererTransitionStrategy.wireName: String
    get() = when (this) {
        CoverageRendererTransitionStrategy.COEXIST -> "coexist"
        CoverageRendererTransitionStrategy.CLEAR_FIRST -> "clearFirst"
        CoverageRendererTransitionStrategy.REJECT -> "reject"
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
    val token: CoverageResourceToken? = null,
    val previousToken: CoverageResourceToken? = null,
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
    private val onDisposed: (CoverageResourceToken?) -> Unit = {},
) {
    private var active: Any? = null
    private var releaseActive: ((Any) -> Unit)? = null
    private var activeToken: CoverageResourceToken? = null

    @Suppress("UNCHECKED_CAST")
    fun <T : Any> replacePoint(
        mode: VoxelRenderMode,
        capacity: Int,
        owner: String,
        rendererGeneration: Long = 0L,
        token: CoverageResourceToken? = null,
        create: (VoxelRenderMode, Int, String) -> T,
        release: (T) -> Unit,
    ): T? = replace(mode, capacity, owner, rendererGeneration, token, create, release)

    fun <T : Any> replaceCube(
        capacity: Int,
        owner: String,
        rendererGeneration: Long = 0L,
        token: CoverageResourceToken? = null,
        create: (VoxelRenderMode, Int, String) -> T,
        release: (T) -> Unit,
    ): T? = replace(VoxelRenderMode.CUBES, capacity, owner, rendererGeneration, token, create, release)

    @Suppress("UNCHECKED_CAST")
    private fun <T : Any> replace(
        mode: VoxelRenderMode,
        capacity: Int,
        owner: String,
        rendererGeneration: Long,
        token: CoverageResourceToken?,
        create: (VoxelRenderMode, Int, String) -> T,
        release: (T) -> Unit,
    ): T? {
        val prior = active
        val priorRelease = releaseActive
        val priorToken = activeToken
        val admission = admit(mode, capacity)
        val transition = CoverageRendererResourceTransition(
            admission = admission,
            hadActiveResource = prior != null,
            mode = mode,
            capacity = capacity,
            rendererGeneration = token?.sourceRendererGeneration ?: rendererGeneration,
            token = token,
            previousToken = priorToken,
        )
        if (admission.strategy == CoverageRendererTransitionStrategy.REJECT) {
            // Admission is non-mutating: reject before clearing or invoking
            // the factory, while the current generation remains mounted.
            onCreationFailure(transition)
            return null
        }
        if (admission.strategy == CoverageRendererTransitionStrategy.CLEAR_FIRST && prior != null) {
            onClearFirst(transition)
            active = null
            releaseActive = null
            activeToken = null
            priorRelease?.invoke(prior)
            onDisposal()
            onDisposed(priorToken)
        }
        // Coexistence is transactional: retain the current owner until the
        // new resource exists and can be installed. A failed allocation then
        // leaves the old renderer valid and mounted. Clear-first has already
        // made the old resource unavailable, so its failure remains fenced.
        val resourceOwner = token?.let { "$owner-epoch-${it.epoch}" } ?: owner
        val next = try {
            // Token-qualified names keep both generations visible to the
            // allocation ledger during a coexistence transaction. Legacy
            // callers without a token retain their existing owner names.
            create(mode, capacity, resourceOwner)
        } catch (error: Throwable) {
            onCreationFailure(transition)
            return null
        }
        try {
            onCreated(transition)
        } catch (error: Throwable) {
            runCatching { release(next) }
            onCreationFailure(transition)
            return null
        }
        active = next
        releaseActive = { value -> release(value as T) }
        activeToken = transition.token
        if (prior != null && priorRelease != null &&
            admission.strategy == CoverageRendererTransitionStrategy.COEXIST
        ) {
            onReplacement()
            priorRelease(prior)
            onDisposal()
            onDisposed(priorToken)
        }
        return next
    }

    fun clear() {
        val prior = active
        val priorRelease = releaseActive
        val priorToken = activeToken
        active = null
        releaseActive = null
        activeToken = null
        if (prior != null && priorRelease != null) {
            priorRelease(prior)
            onDisposal()
            onDisposed(priorToken)
        }
    }
}
