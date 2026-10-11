package com.uhg0.ar_flutter_plugin_2.sceneview

import com.uhg0.ar_flutter_plugin_2.pointcloud.VoxelRenderMode
import java.util.concurrent.atomic.AtomicBoolean

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
    val ownershipReceipt: CoverageRendererOwnershipReceipt =
        CoverageRendererOwnershipReceipt.unattributed(candidateBytes),
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
    private val onAcquisition: () -> Unit = {},
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
    private val onFailureDetail: (String, String) -> Unit = { _, _ -> },
) {
    private fun creationFailure(transition: CoverageRendererResourceTransition,
        stage: String, error: Throwable? = null) {
        onFailureDetail(stage, error?.javaClass?.simpleName?.take(96)?.ifBlank { "UnknownThrowable" } ?: "none")
        onCreationFailure(transition)
    }

    private data class RetiredResource(
        val resource: Any,
        val release: (Any) -> Unit,
        val token: CoverageResourceToken,
        var mounted: Boolean,
        var releaseRequested: Boolean = false,
    )

    private fun releaseOwned(
        resource: Any,
        release: (Any) -> Unit,
        onComplete: () -> Unit,
    ) {
        val mesh = resource as? CoverageVoxelMeshResources
        if (mesh == null) {
            release(resource)
            onComplete()
            return
        }
        val completed = AtomicBoolean(false)
        val complete = {
            if (completed.compareAndSet(false, true)) onComplete()
        }
        mesh.setOnResourceDrained(complete)
        release(resource)
        if (mesh.resourceDrainComplete) complete()
    }

    private val debugFailNextAllocation = AtomicBoolean(false)
    private var active: Any? = null
    private var releaseActive: ((Any) -> Unit)? = null
    private var activeToken: CoverageResourceToken? = null
    private var activeMounted = false
    /** A prepared generation is charged and owned, but remains hidden until promotion. */
    private var prepared: Any? = null
    private var releasePrepared: ((Any) -> Unit)? = null
    private var preparedToken: CoverageResourceToken? = null
    private var preparedMounted = false
    /**
     * A replacement can be installed while Compose still owns the outgoing
     * node. Keep that generation charged until its node has detached and
     * explicitly releases its token. Tokenless callers retain the historical
     * immediate-release behavior because they have no lifecycle identity.
     */
    private val retiredResources = LinkedHashMap<CoverageResourceToken, RetiredResource>()
    private val tokenlessDrainingResources = java.util.IdentityHashMap<Any, Boolean>()

    /** Includes cancellation and unknown callback owners, until actual drain. */
    internal fun occupiedGenerationCount(): Int =
        (if (active != null) 1 else 0) + (if (prepared != null) 1 else 0) +
            retiredResources.size + tokenlessDrainingResources.size

    internal fun ownsToken(token: CoverageResourceToken): Boolean =
        activeToken == token || preparedToken == token || retiredResources.containsKey(token)

    /** Borrow only the exact mounted active lease; prepared/retired generations stay fenced. */
    internal fun mountedResource(token: CoverageResourceToken): Any? =
        active?.takeIf { activeToken == token && activeMounted }

    private fun beginRetiredDrain(retired: RetiredResource) {
        if (retired.releaseRequested) return
        retired.releaseRequested = true
        releaseOwned(retired.resource, retired.release) {
            // A duplicate/stale callback must never free a replacement token.
            if (retiredResources[retired.token] !== retired) return@releaseOwned
            retiredResources.remove(retired.token)
            onDisposal()
            onDisposed(retired.token)
        }
    }

    private fun retireAndRelease(resource: Any, release: (Any) -> Unit, token: CoverageResourceToken?) {
        if (token != null) {
            val retiring = RetiredResource(resource, release, token, false)
            retiredResources[token] = retiring
            beginRetiredDrain(retiring)
        } else {
            if (tokenlessDrainingResources.put(resource, true) != null) return
            releaseOwned(resource, release) {
                if (tokenlessDrainingResources.remove(resource) == null) return@releaseOwned
                onDisposal(); onDisposed(null)
            }
        }
    }

    /** One-shot debug fixture at the real resource creation seam. */
    fun armDebugAllocationFailure() {
        check(debugFailNextAllocation.compareAndSet(false, true)) {
            "renderer allocation fault is already armed"
        }
    }

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

    /**
     * Creates the bounded successor beside the active generation.  The active
     * resource is deliberately untouched until [activatePrepared] is called.
     * CLEAR_FIRST is not a valid admission for a coherent successor.
     */
    @Suppress("UNCHECKED_CAST")
    fun <T : Any> preparePoint(
        mode: VoxelRenderMode,
        capacity: Int,
        owner: String,
        rendererGeneration: Long = 0L,
        token: CoverageResourceToken,
        create: (VoxelRenderMode, Int, String) -> T,
        release: (T) -> Unit,
    ): T? = prepare(mode, capacity, owner, rendererGeneration, token, create, release)

    @Suppress("UNCHECKED_CAST")
    fun <T : Any> prepareCube(
        capacity: Int,
        owner: String,
        rendererGeneration: Long = 0L,
        token: CoverageResourceToken,
        create: (VoxelRenderMode, Int, String) -> T,
        release: (T) -> Unit,
    ): T? = prepare(VoxelRenderMode.CUBES, capacity, owner, rendererGeneration, token, create, release)

    @Suppress("UNCHECKED_CAST")
    private fun <T : Any> prepare(
        mode: VoxelRenderMode,
        capacity: Int,
        owner: String,
        rendererGeneration: Long,
        token: CoverageResourceToken,
        create: (VoxelRenderMode, Int, String) -> T,
        release: (T) -> Unit,
    ): T? {
        if (prepared != null) return null
        val admission = admit(mode, capacity)
        val transition = CoverageRendererResourceTransition(
            admission = admission,
            hadActiveResource = active != null,
            mode = mode,
            capacity = capacity,
            rendererGeneration = token.sourceRendererGeneration.takeIf { it >= 0L } ?: rendererGeneration,
            token = token,
            previousToken = activeToken,
        )
        // Active + retired/unknown already consumes both physical generations.
        // Metadata-only preparation must not create a third charged body.
        if (occupiedGenerationCount() >= 2) {
            creationFailure(transition.copy(admission = admission.copy(
                strategy = CoverageRendererTransitionStrategy.REJECT,
            )), "generationCapacity")
            return null
        }
        if (admission.strategy != CoverageRendererTransitionStrategy.COEXIST) {
            creationFailure(transition, "admission")
            return null
        }
        val resourceOwner = "$owner-epoch-${token.epoch}"
        val next = try {
            if (debugFailNextAllocation.compareAndSet(true, false)) error("debug renderer allocation failure")
            create(mode, capacity, resourceOwner)
        } catch (error: Throwable) {
            creationFailure(transition, "allocation", error)
            return null
        }
        onAcquisition()
        try {
            onCreated(transition)
        } catch (error: Throwable) {
            runCatching { retireAndRelease(next, { value -> release(value as T) }, token) }
            creationFailure(transition, "mountNotification", error)
            return null
        }
        prepared = next
        releasePrepared = { value -> release(value as T) }
        preparedToken = token
        preparedMounted = false
        return next
    }

    /** Makes the fully uploaded prepared generation the active generation. */
    fun activatePrepared(token: CoverageResourceToken): Boolean {
        if (preparedToken != token || prepared == null || releasePrepared == null) return false
        val prior = active
        val priorRelease = releaseActive
        val priorToken = activeToken
        val priorMounted = activeMounted
        val next = prepared
        val nextRelease = releasePrepared
        val nextMounted = preparedMounted
        prepared = null; releasePrepared = null; preparedToken = null; preparedMounted = false
        active = next; releaseActive = nextRelease; activeToken = token; activeMounted = nextMounted
        if (prior != null && priorRelease != null) {
            onReplacement()
            if (priorToken != null) {
                check(retiredResources[priorToken] == null) {
                    "resource token was replaced before its node detached: $priorToken"
                }
                retiredResources[priorToken] = RetiredResource(prior, priorRelease, priorToken, priorMounted)
            } else {
                retireAndRelease(prior, priorRelease, null)
            }
        }
        return true
    }

    fun preparedToken(): CoverageResourceToken? = preparedToken

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
        val priorMounted = activeMounted
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
        if (admission.strategy == CoverageRendererTransitionStrategy.COEXIST &&
            occupiedGenerationCount() >= 2
        ) {
            creationFailure(transition.copy(admission = admission.copy(
                strategy = CoverageRendererTransitionStrategy.REJECT,
            )), "generationCapacity")
            return null
        }
        if (admission.strategy == CoverageRendererTransitionStrategy.REJECT) {
            // Admission is non-mutating: reject before clearing or invoking
            // the factory, while the current generation remains mounted.
            creationFailure(transition, "admission")
            return null
        }
        if (admission.strategy == CoverageRendererTransitionStrategy.CLEAR_FIRST && prior != null) {
            onClearFirst(transition)
            val stillActive = active === prior && releaseActive === priorRelease
            active = null
            releaseActive = null
            activeToken = null
            activeMounted = false
            if (stillActive) {
                if (priorToken != null) {
                    val retiring = RetiredResource(prior, checkNotNull(priorRelease), priorToken, false)
                    retiredResources[priorToken] = retiring
                    beginRetiredDrain(retiring)
                } else retireAndRelease(prior, checkNotNull(priorRelease), null)
            }
        }
        if (occupiedGenerationCount() >= 2) {
            creationFailure(transition.copy(admission = admission.copy(strategy = CoverageRendererTransitionStrategy.REJECT)), "generationCapacity")
            return null
        }
        // Coexistence is transactional: retain the current owner until the
        // new resource exists and can be installed. A failed allocation then
        // leaves the old renderer valid and mounted. Clear-first has already
        // made the old resource unavailable, so its failure remains fenced.
        val resourceOwner = token?.let { "$owner-epoch-${it.epoch}" } ?: owner
        val next = try {
            if (debugFailNextAllocation.compareAndSet(true, false)) {
                error("debug renderer allocation failure")
            }
            // Token-qualified names keep both generations visible to the
            // allocation ledger during a coexistence transaction. Legacy
            // callers without a token retain their existing owner names.
            create(mode, capacity, resourceOwner)
        } catch (error: Throwable) {
            creationFailure(transition, "allocation", error)
            return null
        }
        onAcquisition()
        try {
            onCreated(transition)
        } catch (error: Throwable) {
            runCatching { retireAndRelease(next, { value -> release(value as T) }, token) }
            creationFailure(transition, "mountNotification", error)
            return null
        }
        active = next
        releaseActive = { value -> release(value as T) }
        activeToken = transition.token
        activeMounted = false
        if (prior != null && priorRelease != null &&
            admission.strategy == CoverageRendererTransitionStrategy.COEXIST
        ) {
            onReplacement()
            if (priorToken != null) {
                check(retiredResources[priorToken] == null) {
                    "resource token was replaced before its node detached: $priorToken"
                }
                retiredResources[priorToken] = RetiredResource(
                    resource = prior,
                    release = priorRelease,
                    token = priorToken,
                    mounted = priorMounted,
                )
            } else {
                retireAndRelease(prior, priorRelease, null)
            }
        }
        return next
    }

    /**
     * Releases a token after its SceneView node has detached and destroyed its
     * renderable. The method is idempotent so late Compose disposal callbacks
     * cannot release a newer generation.
     */
    fun releaseResource(token: CoverageResourceToken): Boolean {
        if (activeToken == token && active != null && releaseActive != null) {
            val retiring = RetiredResource(checkNotNull(active), checkNotNull(releaseActive), token, false)
            retiredResources[token] = retiring
            active = null
            releaseActive = null
            activeToken = null
            activeMounted = false
            beginRetiredDrain(retiring)
            return true
        }

        if (preparedToken == token && prepared != null && releasePrepared != null) {
            val retiring = RetiredResource(checkNotNull(prepared), checkNotNull(releasePrepared), token, false)
            retiredResources[token] = retiring
            prepared = null; releasePrepared = null; preparedToken = null; preparedMounted = false
            beginRetiredDrain(retiring)
            return true
        }

        val retired = retiredResources[token] ?: return false
        retired.mounted = false
        beginRetiredDrain(retired)
        return true
    }

    /** Records the attach point so shutdown can retain mounted retired nodes. */
    fun markResourceMounted(token: CoverageResourceToken): Boolean {
        if (activeToken == token && active != null) {
            activeMounted = true
            return true
        }
        if (preparedToken == token && prepared != null) {
            preparedMounted = true
            return true
        }
        val retired = retiredResources[token] ?: return false
        retired.mounted = true
        return true
    }

    fun clear() {
        val preexistingRetired = retiredResources.values.toList()
        val pending = prepared
        val pendingRelease = releasePrepared
        val pendingToken = preparedToken
        val pendingMounted = preparedMounted
        val prior = active
        val priorRelease = releaseActive
        val priorToken = activeToken
        val priorMounted = activeMounted
        // Install every cancellation owner before any synchronous/reentrant
        // release can observe capacity. Completion still follows actual drain.
        val pendingRetired = if (pending != null && pendingRelease != null && pendingToken != null) {
            RetiredResource(pending, pendingRelease, pendingToken, pendingMounted).also {
                retiredResources[pendingToken] = it
            }
        } else null
        val priorRetired = if (prior != null && priorRelease != null && priorToken != null) {
            RetiredResource(prior, priorRelease, priorToken, priorMounted).also {
                retiredResources[priorToken] = it
            }
        } else null
        val pendingTokenless = pending != null && pendingRelease != null && pendingToken == null &&
            tokenlessDrainingResources.put(pending, true) == null
        val priorTokenless = prior != null && priorRelease != null && priorToken == null &&
            tokenlessDrainingResources.put(prior, true) == null
        prepared = null; releasePrepared = null; preparedToken = null; preparedMounted = false
        active = null
        releaseActive = null
        activeToken = null
        activeMounted = false

        fun drainTokenless(resource: Any, release: (Any) -> Unit) {
            releaseOwned(resource, release) {
                if (tokenlessDrainingResources.remove(resource) == null) return@releaseOwned
                onDisposal(); onDisposed(null)
            }
        }

        // Preserve active-new before pre-existing-old request ordering. A
        // mounted node still waits for its exact detach/releaseResource token.
        priorRetired?.takeUnless { it.mounted }?.let(::beginRetiredDrain)
        if (priorTokenless) drainTokenless(checkNotNull(prior), checkNotNull(priorRelease))
        pendingRetired?.takeUnless { it.mounted }?.let(::beginRetiredDrain)
        if (pendingTokenless) drainTokenless(checkNotNull(pending), checkNotNull(pendingRelease))
        preexistingRetired.forEach { resource ->
            if (!resource.mounted) beginRetiredDrain(resource)
        }
    }
}
