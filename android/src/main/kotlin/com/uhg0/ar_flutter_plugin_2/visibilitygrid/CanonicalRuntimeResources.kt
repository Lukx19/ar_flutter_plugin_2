package com.uhg0.ar_flutter_plugin_2.visibilitygrid

import com.uhg0.ar_flutter_plugin_2.capture.StorageBudgetCoordinatorV2
import java.io.File

// Canonical lineage can reach 200k associations; the renderer style row has
// a 16-bit lineage field. Saturate only the display value, not the authority.
private fun rendererLineageCount(canonicalCount: Int): Int = minOf(canonicalCount, 0xffff)

/**
 * The one group-owned runtime capability for durable canonical surface work.
 *
 * It normalizes the group-private directory once and owns the matching
 * [StorageBudgetCoordinatorV2] for its entire lifetime. The acknowledged binding lifecycle
 * baseline is created directly as an empty budget-owned v6 root; this runtime
 * has no writable legacy fallback.
 */
internal class CanonicalRuntimeResources private constructor(
    val group: SurfaceGroup,
    val directory: File,
    val groupDirectory: File,
    coordinator: StorageBudgetCoordinatorV2,
    private val configuration: SurfaceOwnershipConfiguration = SurfaceOwnershipConfiguration(),
    internal val usesSessionMemory: Boolean = false,
) : AutoCloseable {
    private val budget = CoordinatorStorageBudget(coordinator)
    private var owner: SurfaceOwnership? = null
    private var current: CurrentLease? = null
    private var lastOpenFailureStage: CanonicalRuntimeOpenFailureStage? = null
    private var closed = false
    private var session: SessionCanonicalMemoryState? = null
    private var spatial: LiveSurfaceSpatialCache? = null

    fun openInitial(baseline: committedEmptyBaseline): SurfaceOwnershipOpenResult {
        checkOpen()
        check(owner == null)
        if (usesSessionMemory) {
            if (!configuration.isValid) return SurfaceOwnershipOpenResult.Refused(SurfaceOwnershipRestoreRefusal.INVALID_CONFIGURATION)
            if (baseline.groupIdentity != group.value) return SurfaceOwnershipOpenResult.Refused(SurfaceOwnershipRestoreRefusal.FORK)
            val seeded = configuration.copy(seededEmptyBaseline = baseline)
            val state = SessionCanonicalMemoryState(group, seeded, directory.absoluteFile.toPath().normalize().toString())
            val opened = SurfaceOwnership.session(group, directory, seeded, state)
            session = state
            spatial = LiveSurfaceSpatialCache(configuration.surfaceCapacity, configuration.voxelMicrometers)
            owner = (opened as SurfaceOwnershipOpenResult.Opened).ownership
            return opened
        }
        check(!CanonicalActivationSelector.hasDurableSelector(group, directory))
        lastOpenFailureStage = null
        val configuration = this.configuration.copy(seededEmptyBaseline = baseline)
        if (CompactCanonicalStore.prepareEmptyV6Bootstrap(
                group, directory, budget, baseline, configuration,
            )
            !is CompactCanonicalMigrationResult.Prepared
        ) return openFailure(CanonicalRuntimeOpenFailureStage.BOOTSTRAP_CANDIDATE)
        val plan = (CanonicalActivation.prepareEmptyV6(
            group, directory, budget, baseline, configuration,
        )
            as? CanonicalActivationPreparation.Prepared)?.plan
            ?: return openFailure(CanonicalRuntimeOpenFailureStage.ACTIVATION_PREPARATION)
        val opened = SurfaceOwnership.open(group, directory, budget, plan, configuration)
        if (opened !is SurfaceOwnershipOpenResult.Opened) {
            lastOpenFailureStage = CanonicalRuntimeOpenFailureStage.ACTIVATION_AUTHORITY_OPEN
            return opened
        }
        owner = opened.ownership
        if (!warmCurrent()) {
            opened.ownership.close(); owner = null
            return SurfaceOwnershipOpenResult.Refused(SurfaceOwnershipRestoreRefusal.DURABILITY_FAILURE)
        }
        return opened
    }

    /** Opens only the selected v6 authority; an absent selector is never a legacy fallback. */
    fun reopen(): SurfaceOwnershipOpenResult {
        checkOpen()
        check(owner == null)
        if (usesSessionMemory) return SurfaceOwnershipOpenResult.Refused(SurfaceOwnershipRestoreRefusal.CORRUPT)
        lastOpenFailureStage = null
        if (!CanonicalActivationSelector.hasDurableSelector(group, directory)) {
            return SurfaceOwnershipOpenResult.Refused(SurfaceOwnershipRestoreRefusal.CORRUPT)
        }
        val opened = SurfaceOwnership.open(group, directory, budget, configuration)
        if (opened !is SurfaceOwnershipOpenResult.Opened) {
            lastOpenFailureStage = CanonicalRuntimeOpenFailureStage.ACTIVATION_AUTHORITY_OPEN
            return opened
        }
        owner = opened.ownership
        if (!warmCurrent()) {
            opened.ownership.close(); owner = null
            return SurfaceOwnershipOpenResult.Refused(SurfaceOwnershipRestoreRefusal.CORRUPT)
        }
        return opened
    }

    /** Fixed, bounded integration telemetry for the most recent failed open attempt. */
    internal fun integrationOpenFailureStatus(reason: SurfaceOwnershipRestoreRefusal): String =
        canonicalRuntimeOpenFailureStatus(reason, lastOpenFailureStage)

    fun owner(): SurfaceOwnership = requireNotNull(owner) { "canonical surface runtime authority is unavailable" }

    private fun isDurablyCurrent(lease: CurrentLease): Boolean {
        val held = owner?.activationState() ?: return false
        if (!lease.isCurrent(held.cut)) return false
        val selected = CanonicalActivationSelector.reopenAuthenticatedCurrent(
            group, directory, budget, held.cut,
        ) as? CanonicalActivationResult.Active ?: return false
        return selected.state.cut == held.cut &&
            selected.state.identity == held.identity &&
            selected.state.currentState == held.currentState
    }
    private fun openGenerationZero(): CompactCanonicalOpenResult {
        checkOpen()
        return CompactCanonicalStore.openV6(group, directory, budget, configuration)
    }

    /** Borrows exactly the selector-named v6 cut for one bounded operation. */
    @Synchronized fun <T> withCurrent(block: (CanonicalStateView) -> T): T? {
        checkOpen()
        session?.let { return block(it) }
        val lease = current ?: return null
        return authenticatedBorrow(lease, lease.completeView, block)
    }

    /** Borrows one exact complete current cut behind explicit lookup limits. */
    @Synchronized
    internal fun <T> withBoundedCurrent(
        request: BoundedCanonicalLookupRequest,
        block: (BoundedCanonicalSurfaceView) -> T,
    ): BoundedCanonicalLookupResult<T> {
        if (request.maximumDirectLookups < 0 || request.maximumRayCellVisits < 0 ||
            request.maximumPageReads < 0 || request.maximumBytesRead < 0L
        ) {
            return BoundedCanonicalLookupResult.Refused(
                BoundedCanonicalLookupReason.INVALID_REQUEST,
                BoundedCanonicalLookupReceipt(0, 0, 0, 0, false),
            )
        }
        if (closed) {
            return BoundedCanonicalLookupResult.Refused(
                BoundedCanonicalLookupReason.CURRENT_UNAVAILABLE,
                BoundedCanonicalLookupReceipt(0, 0, 0, 0, false),
            )
        }
        session?.let { state ->
            if (state.cut.geometryRevision != request.expectedGeometryRevision ||
                state.cut.lineageRevision != request.expectedLineageRevision) return BoundedCanonicalLookupResult.Refused(
                BoundedCanonicalLookupReason.REVISION_CONFLICT, BoundedCanonicalLookupReceipt(0, 0, 0, 0, false),
            )
            val cache = spatial
            val view = if (cache == null) state else SpatialCanonicalLookupView(state, cache)
            val bounded = BoundedCanonicalCurrentView(view, request, configuration.voxelMicrometers)
            return bounded.result(block(bounded))
        }
        return withAuthenticatedCompleteCurrent(
            CanonicalRevisionPair(request.expectedGeometryRevision, request.expectedLineageRevision),
            onFailure = { failure ->
                BoundedCanonicalLookupResult.Refused(
                    failure.lookupReason,
                    BoundedCanonicalLookupReceipt(0, 0, 0, 0, false),
                )
            },
        ) { view ->
            val routed = requireNotNull(current).depthLookupView(view)
            val bounded = BoundedCanonicalCurrentView(
                routed, request, configuration.voxelMicrometers,
                reuseAuthenticatedSurfaceReads = true,
            )
            bounded.result(block(bounded))
        }
    }

    /** Prepares one depth batch from the same authenticated complete current cut. */
    @Synchronized
    internal fun prepareEvidenceBatch(
        command: CanonicalEvidenceBatchCommand,
    ): CanonicalMutationPreparation {
        checkOpen()
        session?.let { return owner().prepareAdjacentMutation(it, command) }
        return withAuthenticatedCompleteCurrent(
            CanonicalRevisionPair(command.expectedGeometryRevision, command.expectedLineageRevision),
            onFailure = { failure ->
                when (failure) {
                    CompleteCurrentBorrowFailure.REVISION_CONFLICT ->
                        CanonicalMutationPreparation.Refused(
                            CanonicalMutationRefusal.REVISION_CONFLICT,
                            currentStateReceipt(),
                        )
                    CompleteCurrentBorrowFailure.CURRENT_UNAVAILABLE ->
                        CanonicalMutationPreparation.Refused(
                            CanonicalMutationRefusal.INVALID_OWNERSHIP,
                            CanonicalStateReceipt(0, 0, 1, 0),
                        )
                }
            },
        ) { view -> owner().prepareAdjacentMutation(view, command) }
    }

    private fun currentStateReceipt() = current?.scalarView?.cut?.let { cut ->
        CanonicalStateReceipt(
            cut.geometryRevision, cut.lineageRevision,
            cut.nextSurfaceIdHighWater, cut.liveSurfaceCount,
        )
    } ?: CanonicalStateReceipt(0, 0, 1, 0)

    private fun <T> withAuthenticatedCompleteCurrent(
        expected: CanonicalRevisionPair,
        onFailure: (CompleteCurrentBorrowFailure) -> T,
        block: (CanonicalStateView) -> T,
    ): T {
        val lease = current ?: return onFailure(CompleteCurrentBorrowFailure.CURRENT_UNAVAILABLE)
        if (expected.geometryRevision != lease.scalarView.cut.geometryRevision ||
            expected.lineageRevision != lease.scalarView.cut.lineageRevision
        ) return onFailure(CompleteCurrentBorrowFailure.REVISION_CONFLICT)
        if (!isDurablyCurrent(lease)) {
            invalidateCurrent()
            return onFailure(CompleteCurrentBorrowFailure.CURRENT_UNAVAILABLE)
        }
        return attachRetainedPublishedAuthority(lease, block(lease.completeView))
    }

    /** Borrows complete canonical authority for bounded feature planning. */
    @Synchronized fun <T> withFeaturePlanningCurrent(
        maximumTouches: Int,
        block: (CanonicalFeaturePlanningView) -> T,
    ): T? = withFeaturePlanningCurrent(
        FeaturePlanningReadBudget.derived(maximumTouches, configuration.surfaceCapacity), block,
    )

    @Synchronized fun <T> withFeaturePlanningCurrent(
        maximumTouches: Int,
        maximumPageReads: Long,
        maximumBytesRead: Long,
        block: (CanonicalFeaturePlanningView) -> T,
    ): T? = withFeaturePlanningCurrent(
        FeaturePlanningReadBudget.explicit(
            maximumTouches, maximumPageReads, maximumBytesRead, configuration.surfaceCapacity,
        ),
        block,
    )

    private fun <T> withFeaturePlanningCurrent(
        budget: FeaturePlanningReadBudget,
        block: (CanonicalFeaturePlanningView) -> T,
    ): T? {
        checkOpen()
        session?.let { return block(it) }
        val lease = current ?: return null
        // Feature-local correlation identifies associations only. Canonical
        // occupancy, normals, identity and allocation provenance always come
        // from the lifecycle-owned complete authority.
        if (!isDurablyCurrent(lease)) {
            invalidateCurrent(); return null
        }
        val view = lease.featurePlanningView(budget)
        val result = block(view)
        if (view.routingFailed) {
            if (result is CanonicalMutationPreparation.Prepared) result.mutation.discard()
            return null
        }
        return attachRetainedPublishedAuthority(lease, result)
    }

    private fun <T> authenticatedBorrow(
        lease: CurrentLease,
        view: CanonicalStateView,
        block: (CanonicalStateView) -> T,
    ): T? {
        if (!isDurablyCurrent(lease) || view.cut != lease.scalarView.cut) {
            invalidateCurrent(); return null
        }
        return attachRetainedPublishedAuthority(lease, block(view))
    }

    private fun <T> attachRetainedPublishedAuthority(lease: CurrentLease, result: T): T {
        if (result is CanonicalMutationPreparation.Prepared && lease.commit != null) {
            check(CanonicalAuthorityLeaseRegistry.attachPublished(
                result.mutation.authorityLease, requireNotNull(lease.commit),
            ))
        }
        return result
    }

    /** Commits and atomically installs the transferred adjacent composed view. */
    @Synchronized fun commitAdjacent(
        plan: PreparedCanonicalMutation,
        faults: CanonicalCommitFaults = CanonicalCommitFaults(),
    ): CanonicalAdjacentCommitResult {
        checkOpen()
        session?.let {
            val result = owner().commitAdjacentCanonicalMutation(plan, faults)
            if (result is CanonicalAdjacentCommitResult.Committed) {
                plan.visitRemovedSurfaceIds { id -> spatial?.remove(id); true }
                plan.visitDirtyRows { row -> spatial?.upsert(row.id, row.voxel); true }
            }
            return result
        }
        val lease = current ?: return CanonicalAdjacentCommitResult.Refused(
            CanonicalAdjacentCommitRefusal.NO_ACTIVE_AUTHORITY,
        )
        val routeDelta = lease.prepareRouting(plan) ?: run {
            plan.discard()
            return CanonicalAdjacentCommitResult.Refused(CanonicalAdjacentCommitRefusal.PLAN_DISCARDED)
        }
        val result = try {
            owner().commitAdjacentCanonicalMutation(plan, faults)
        } catch (failure: Throwable) {
            invalidateCurrent()
            throw failure
        }
        if (result is CanonicalAdjacentCommitResult.Committed) {
            val successor = result.successor ?: run { invalidateCurrent(); return result }
            lease.applyRouting(routeDelta, successor)
            val scalar = successor.view.scalarView(directory)
            lease.commit?.close()
            lease.commit = successor
            lease.completeView = successor.view
            lease.scalarView = scalar
        }
        return result
    }

    @Synchronized internal fun retainedCurrentProofBytes(): Long = current?.commit?.retainedProofBytes() ?: 0L
    @Synchronized internal fun retainedScalarMemoryReceipt(): ScalarCanonicalMemoryReceipt? =
        session?.let { ScalarCanonicalMemoryReceipt.measure(it.authorityParentKey, it.cut) } ?: current?.scalarView?.scalarMemoryReceipt
    @Synchronized internal fun retainedCompleteCurrentMemoryReceipt(): CompactRetainedMemoryReceipt? =
        session?.let { sessionMemoryReceipt(it) } ?: current?.completeView?.retainedMemoryReceipt()
    @Synchronized internal fun completeCurrentLeaseReceipt(): CanonicalCompleteCurrentLeaseReceipt? =
        session?.let {
            val memory = sessionMemoryReceipt(it)
            CanonicalCompleteCurrentLeaseReceipt(memory, 0, 0, memory.residentTotalBytes, memory.peakWithScratchBytes)
        } ?: current?.resourceReceipt()
    @Synchronized internal fun featurePlanningMemoryReceipt(maximumTouches: Int): CanonicalFeaturePlanningMemoryReceipt? =
        session?.let { CanonicalFeaturePlanningMemoryReceipt(0, 0, 0, 0) } ?: current?.featurePlanningMemoryReceipt(maximumTouches)
    @Synchronized internal fun currentRowFoldScratchBytes(): Long? =
        if (session != null) 0L else current?.let { CurrentRowFoldScratch.memoryBytes() }

    private fun sessionMemoryReceipt(state: SessionCanonicalMemoryState): CompactRetainedMemoryReceipt =
        state.retainedMemoryReceipt().let { it.copy(cacheMetadataBytes = it.cacheMetadataBytes + (spatial?.retainedPrimitiveBytes ?: 0)) }

    /** Frustum refresh is worker-only and reuses one spatial candidate buffer. */
    @Synchronized internal fun updateSpatialWindow(batch: DepthEvidenceBatch): Boolean {
        checkOpen()
        val cache = spatial ?: return true
        if (!cache.updateWindow(batch.groupFromCameraGl, batch.intrinsics, 8_000.0)) return false
        // Dirty rows have already been written to packed session authority at
        // commit. Movement can release their cold working-set markers without
        // another geometry copy, file write, or loss of canonical history.
        cache.flushCold { _, _ -> true }
        return true
    }

    internal fun portableOwnerBytes(): Long =
        40L + // CanonicalRuntimeResources
            56L + // CurrentLease with scalar, complete-current, and feature-route capabilities
            104L + 56L + // SurfaceOwnership + configuration
            40L + 120L + 32L + 32L + // published/current activation scalars
            16L + 16L + // coordinator budget adapter + surface group
            2L * 32L + // normalized directory File owners
            group.value.encodeToByteArray().size +
            directory.path.encodeToByteArray().size + groupDirectory.path.encodeToByteArray().size

    internal fun portableCoordinatorOwnerBytes(): Long =
        56L + 48L + 32L + 32L + // coordinator, descriptor filesystem, safe filesystem, policy
            8L * 16L + // bounded atomic/counter owners
            2L * 32L // reservations/reclaims directory owners

    private fun warmCurrent(): Boolean {
        check(current == null)
        current = materializeCurrent()
        current?.let {
            val authenticatedCurrentBytes = when (val state = owner?.activationState()?.currentState) {
                is CanonicalCurrentState.Unacknowledged -> state.identity.canonicalLength
                else -> 0L
            }
            CanonicalRuntimeCurrentTestHooks.onAuthenticatedBorrow?.invoke(authenticatedCurrentBytes)
        }
        return current != null
    }

    private fun materializeCurrent(): CurrentLease? {
        val base = (openGenerationZero() as? CompactCanonicalOpenResult.Opened)?.store
            ?: return materializationFailure(CanonicalRuntimeOpenFailureStage.BASE_AUTHORITY_OPEN)
        val store = CanonicalCommitStore.open(directory, budget) ?: run {
            base.close()
            return materializationFailure(CanonicalRuntimeOpenFailureStage.COMMIT_STORE_OPEN)
        }
        val retained = retainedCurrentReceipt()
        return try {
            when (val selected = store.reopen(base, retained)) {
                is CanonicalReopenResult.GenerationZero ->
                    CurrentLease.create(
                        base.scalarView(directory), selected.view, base, null, configuration.surfaceCapacity,
                    ) ?: materializationFailure(CanonicalRuntimeOpenFailureStage.FEATURE_ROUTE_HYDRATION)
                is CanonicalReopenResult.Selected -> {
                    val scalar = selected.commit.view.scalarView(directory)
                    CurrentLease.create(
                        scalar, selected.commit.view, base, selected.commit, configuration.surfaceCapacity,
                    ) ?: materializationFailure(CanonicalRuntimeOpenFailureStage.FEATURE_ROUTE_HYDRATION)
                }
                is CanonicalReopenResult.Refused -> {
                    base.close()
                    materializationFailure(CanonicalRuntimeOpenFailureStage.CURRENT_REOPEN)
                }
            }
        } catch (_: Throwable) {
            base.close()
            materializationFailure(CanonicalRuntimeOpenFailureStage.CURRENT_REOPEN)
        } finally { store.close() }
    }

    private fun materializationFailure(stage: CanonicalRuntimeOpenFailureStage): CurrentLease? {
        lastOpenFailureStage = stage
        return null
    }

    private fun openFailure(stage: CanonicalRuntimeOpenFailureStage): SurfaceOwnershipOpenResult.Refused {
        lastOpenFailureStage = stage
        return SurfaceOwnershipOpenResult.Refused(SurfaceOwnershipRestoreRefusal.DURABILITY_FAILURE)
    }

    /** Rebuilds from the lifecycle-owned authenticated complete current. */
    @Synchronized fun rebuildAndHydrate(
        kernel: FeatureFusionKernel,
        rendererLimit: Int,
        hydrateKernel: Boolean = true,
        sink: (CanonicalRendererPage) -> Unit,
    ): CompactCanonicalCut? {
        checkOpen()
        require(rendererLimit >= 0)
        session?.let { state ->
            val page = ArrayList<CommittedGeometryRow>(512)
            var rendered = 0
            val complete = state.visitRows { row, fingerprint ->
                if (hydrateKernel && !kernel.hydrateCanonicalSurface(row, fingerprint)) return@visitRows false
                if (rendered < rendererLimit) {
                    page += CommittedGeometryRow(row.id.value, row.voxel, row.packedNormal, row.normalConfidence,
                        rendererLineageCount(state.cut.lineageCount))
                    rendered++
                    if (page.size == 512) { sink(CanonicalRendererPage(state.cut, page.toList(), null)); page.clear() }
                }
                true
            }
            if (!complete) return null
            if (page.isNotEmpty()) sink(CanonicalRendererPage(state.cut, page.toList(), null))
            return state.cut
        }
        val lease = current ?: return null
        val view = lease.completeView
        var rendered = 0
        val page = ArrayList<CommittedGeometryRow>(512)
        val folded = lease.foldCurrentRows { row, allocationFingerprint ->
            if (hydrateKernel && !kernel.hydrateCanonicalSurface(row, allocationFingerprint)) return@foldCurrentRows false
            if (rendered < rendererLimit) {
                page += CommittedGeometryRow(
                    surfaceId = row.id.value,
                    voxel = row.voxel,
                    packedNormal = row.packedNormal,
                    normalConfidence = row.normalConfidence,
                    lineageCount = rendererLineageCount(view.cut.lineageCount),
                )
                rendered++
                if (page.size == 512) {
                    sink(CanonicalRendererPage(view.cut, page.toList(), null)); page.clear()
                }
            }
            true
        }
        if (!folded) return null
        if (page.isNotEmpty()) sink(CanonicalRendererPage(view.cut, page.toList(), null))
        return view.cut
    }

    private fun retainedCurrentReceipt(): PreparedIntentCurrentReceipt? = owner?.activationState()?.currentState.let { state ->
        val identity = when (state) {
            is CanonicalCurrentState.Unacknowledged -> state.identity
            is CanonicalCurrentState.Acknowledged -> state.identity
            else -> null
        }
        identity?.let { PreparedIntentCurrentReceipt(it.canonicalLength, it.canonicalHash) }
    }

    private fun invalidateCurrent() { current?.close(); current = null }

    /** One bounded renderer page from the active v6 root for restart rebuild. */
    fun readRendererPage(cursor: Long, limit: Int = 512): CanonicalRendererPage? {
        checkOpen()
        session?.let { return it.rendererPage(cursor, limit) }
        val lease = current ?: return null
        val view = lease.completeView
        return run {
            require(cursor in 0 until view.cut.nextSurfaceIdHighWater && limit in 1..512)
            if (view.cut.nextSurfaceIdHighWater > Int.MAX_VALUE.toLong()) {
                val ids = lease.sparseIds() ?: return null
                var index = ids.binarySearch(cursor + 1).let { if (it < 0) -it - 1 else it }
                val rows = ArrayList<CommittedGeometryRow>(limit)
                while (index < ids.size && rows.size < limit) {
                    val row = view.findById(SurfaceId(ids[index++])) ?: return null
                    rows += CommittedGeometryRow(
                        surfaceId = row.id.value,
                        voxel = row.voxel,
                        packedNormal = row.packedNormal,
                        normalConfidence = row.normalConfidence,
                        lineageCount = rendererLineageCount(view.cut.lineageCount),
                    )
                }
                return CanonicalRendererPage(
                    view.cut, rows, ids.getOrNull(index - 1)?.takeIf { index < ids.size },
                )
            }
            val rows = ArrayList<CommittedGeometryRow>(limit)
            var id = cursor + 1
            while (id < view.cut.nextSurfaceIdHighWater && rows.size < limit) {
                view.findById(SurfaceId(id))?.let { row ->
                    rows += CommittedGeometryRow(
                        surfaceId = row.id.value,
                        voxel = row.voxel,
                        packedNormal = row.packedNormal,
                        normalConfidence = row.normalConfidence,
                        lineageCount = rendererLineageCount(view.cut.lineageCount),
                    )
                }
                id++
            }
            CanonicalRendererPage(
                view.cut,
                rows,
                id.takeIf { it < view.cut.nextSurfaceIdHighWater }?.minus(1),
            )
        }
    }

    /**
     * Borrows one qualifier-fenced canonical renderer page.  The callback is
     * bounded to 512 rows and must not retain the page after it returns.
     */
    @Synchronized
    internal fun withRendererPage(
        expectedGeometryRevision: Long,
        expectedLineageRevision: Long,
        cursor: Long,
        limit: Int = 512,
        block: (CanonicalRendererPage) -> Unit,
    ): Boolean {
        if (limit !in 1..512 || closed) return false
        session?.let { state ->
            if (state.cut.geometryRevision != expectedGeometryRevision || state.cut.lineageRevision != expectedLineageRevision) return false
            val page = state.rendererPage(cursor, limit) ?: return false
            block(page)
            return session === state && state.cut.geometryRevision == expectedGeometryRevision && state.cut.lineageRevision == expectedLineageRevision
        }
        val lease = current ?: return false
        val expected = CanonicalRevisionPair(expectedGeometryRevision, expectedLineageRevision)
        if (lease.scalarView.cut.let {
                it.geometryRevision != expected.geometryRevision ||
                    it.lineageRevision != expected.lineageRevision
            } || !isDurablyCurrent(lease)
        ) {
            invalidateCurrent()
            return false
        }
        val page = runCatching { readRendererPage(cursor, limit) }.getOrNull() ?: return false
        block(page)
        val finalCut = lease.scalarView.cut
        return current === lease && isDurablyCurrent(lease) &&
            finalCut.geometryRevision == expected.geometryRevision &&
            finalCut.lineageRevision == expected.lineageRevision
    }

    /** Test/diagnostic recovery projection in one cold verified scope. */
    internal fun readAllRendererKeys(): LongArray? {
        checkOpen()
        session?.let { return it.occupiedKeys() }
        val lease = current ?: return null
        return lease.copyOccupiedKeys()
    }

    override fun close() {
        if (closed) return
        closed = true
        try { owner?.close() } finally { invalidateCurrent() }
        owner = null
        session = null
        spatial = null
    }

    private fun checkOpen() = check(!closed) { "canonical surface runtime resources are closed" }

    companion object {
        private const val RUNTIME_DIRECTORY = "visibility-grid-canonical-surface-runtime"

        fun open(
            root: File,
            group: SurfaceGroup,
            coordinator: StorageBudgetCoordinatorV2,
            configuration: SurfaceOwnershipConfiguration = SurfaceOwnershipConfiguration(),
            sessionMemory: Boolean = false,
        ): CanonicalRuntimeResources {
            val normalizedRoot = root.absoluteFile.toPath().normalize().toFile()
            val groupName = group.value.lowercase()
            require(groupName.matches(Regex("[0-9a-f]{32}"))) { "canonical surface group directory must be canonical" }
            val sharedDirectory = File(normalizedRoot, RUNTIME_DIRECTORY)
                .absoluteFile.toPath().normalize().toFile()
            val groupDirectory = File(sharedDirectory, groupName)
                .absoluteFile.toPath().normalize().toFile()
            require(sharedDirectory.toPath().startsWith(normalizedRoot.toPath()))
            require(groupDirectory.toPath().startsWith(sharedDirectory.toPath()))
            require(sharedDirectory.mkdirs() || sharedDirectory.isDirectory)
            require(groupDirectory.mkdirs() || groupDirectory.isDirectory)
            // Every canonical selector, root, current and candidate lives under the normalized
            // group root while the borrowed coordinator accounts them from its shared ancestor.
            return CanonicalRuntimeResources(group, groupDirectory, groupDirectory, coordinator, configuration, sessionMemory)
        }

        fun openLive(root: File, group: SurfaceGroup, coordinator: StorageBudgetCoordinatorV2,
            configuration: SurfaceOwnershipConfiguration = SurfaceOwnershipConfiguration()) =
            open(root, group, coordinator, configuration, sessionMemory = true)
    }

    private enum class CompleteCurrentBorrowFailure {
        CURRENT_UNAVAILABLE,
        REVISION_CONFLICT;

        val lookupReason: BoundedCanonicalLookupReason
            get() = when (this) {
                CURRENT_UNAVAILABLE -> BoundedCanonicalLookupReason.CURRENT_UNAVAILABLE
                REVISION_CONFLICT -> BoundedCanonicalLookupReason.REVISION_CONFLICT
            }
    }

    private class CurrentLease private constructor(
        var scalarView: ScalarCanonicalStateView,
        var completeView: CanonicalStateView,
        private val base: CompactCanonicalStore,
        var commit: CanonicalPublishedCommit?,
        private val featureRoutes: CanonicalFeaturePlanningRoutes,
    ) : AutoCloseable {
        private var sortedSparseIds: LongArray? = null

        @Synchronized fun sparseIds(): LongArray? {
            if (scalarView.cut.nextSurfaceIdHighWater <= Int.MAX_VALUE.toLong()) return null
            sortedSparseIds?.let { return it }
            return featureRoutes.copySortedSurfaceIds(scalarView.cut.liveSurfaceCount)?.also {
                sortedSparseIds = it
            }
        }

        fun featurePlanningView(budget: FeaturePlanningReadBudget): RoutedFeaturePlanningView =
            featureRoutes.view(scalarView.cut, base, commit, budget)
        fun depthLookupView(view: CanonicalStateView): CanonicalStateView =
            RoutedCanonicalDepthView(view, featureRoutes, base, commit)
        fun featurePlanningMemoryReceipt(maximumTouches: Int) = featureRoutes.memoryReceipt(maximumTouches)
        fun copyOccupiedKeys(): LongArray? = featureRoutes.copyOccupiedKeys(scalarView.cut.liveSurfaceCount)
        fun foldCurrentRows(sink: (CompactSurface, CanonicalReceiptBytes) -> Boolean): Boolean {
            val highWater = scalarView.cut.nextSurfaceIdHighWater
            if (highWater < 1L) return false
            // Canonical IDs can reach UINT32_MAX while only 100k surfaces are live.
            // Walk retained route identities instead of billions of empty ID windows.
            if (highWater > Int.MAX_VALUE.toLong()) {
                val ids = sparseIds() ?: return false
                for (idValue in ids) {
                    val id = SurfaceId(idValue)
                    val row = completeView.findById(id) ?: return false
                    if (!featureRoutes.contains(row.voxel, row.id)) return false
                    val source = (completeView.readSourceById(id) as? CanonicalPageRead.Complete)?.value
                        ?: return false
                    if (!sink(row, source.allocationFingerprint)) return false
                }
                return true
            }
            val scratch = CurrentRowFoldScratch()
            val published = commit
            var emitted = 0
            var first = 1L
            while (first < highWater) {
                val last = minOf(highWater - 1L, first + CurrentRowFoldScratch.WINDOW_SIZE - 1L)
                scratch.reset(first, last)
                if (published != null) for (ordinal in published.routingGenerationCount() - 1 downTo 0) {
                    if (!scratch.apply(published.routingGenerationAt(ordinal))) return false
                }
                for (idValue in first..last) {
                    val folded = scratch.row(idValue)
                    val row = if (folded == CurrentRowFoldScratch.PRESENT) {
                        scratch.surface(idValue)
                    } else base.findById(SurfaceId(idValue))
                    row ?: continue
                    if (!featureRoutes.contains(row.voxel, row.id)) continue
                    val fingerprint = scratch.sourceFingerprint(idValue) ?: run {
                        val source = (base.readSourceById(row.id) as? CanonicalPageRead.Complete)?.value ?: return false
                        source.allocationFingerprint
                    }
                    if (!sink(row, fingerprint)) return false
                    emitted++
                }
                first = last + 1L
            }
            return emitted == scalarView.cut.liveSurfaceCount
        }

        fun prepareRouting(plan: PreparedCanonicalMutation): CanonicalFeatureRouteDelta? =
            featureRoutes.prepare(
                plan, FeatureRouteProviderToken.generation(commit?.routingGenerationCount() ?: 0),
            )

        fun applyRouting(delta: CanonicalFeatureRouteDelta, successor: CanonicalPublishedCommit) {
            check(FeatureRouteProviderToken.generation(successor.routingGenerationCount() - 1) == delta.providerToken)
            featureRoutes.apply(delta)
        }
        fun resourceReceipt(): CanonicalCompleteCurrentLeaseReceipt {
            val baseReceipt = base.retainedMemoryReceipt()
            val cow = commit?.leaseMemoryReceipt()
            val cowRetained = cow?.retainedProofBytes ?: 0L
            val retainedTotal = Math.addExact(baseReceipt.residentTotalBytes, cowRetained)
            val cowConstruction = cow?.let {
                Math.addExact(baseReceipt.residentTotalBytes, it.lifecycleConstructionPeakBytes)
            } ?: 0L
            val routing = featureRoutes.memoryReceipt(0)
            val sparseIdBytes = sortedSparseIds?.let {
                Math.addExact(16L, Math.multiplyExact(it.size.toLong(), Long.SIZE_BYTES.toLong()))
            } ?: 0L
            val routingRetained = Math.addExact(routing.routeRetainedBytes, sparseIdBytes)
            return CanonicalCompleteCurrentLeaseReceipt(
                baseReceipt,
                cowRetained,
                routingRetained,
                Math.addExact(retainedTotal, routingRetained),
                maxOf(
                    Math.addExact(baseReceipt.peakWithScratchBytes, routingRetained),
                    cowConstruction,
                    Math.addExact(
                        retainedTotal,
                        Math.addExact(routingRetained, routing.lifecycleConstructionScratchBytes),
                    ),
                ),
            )
        }
        fun isCurrent(cut: CompactCanonicalCut?): Boolean =
            cut != null && cut == scalarView.cut && cut == completeView.cut
        override fun close() {
            sortedSparseIds = null
            featureRoutes.close()
            commit?.close(); commit = null; base.close()
        }

        companion object {
            fun create(
                scalarView: ScalarCanonicalStateView,
                completeView: CanonicalStateView,
                base: CompactCanonicalStore,
                commit: CanonicalPublishedCommit?,
                capacity: Int,
            ): CurrentLease? {
                val routes = CanonicalFeaturePlanningRoutes.build(base, commit, capacity) ?: run {
                    commit?.close(); base.close(); return null
                }
                return CurrentLease(scalarView, completeView, base, commit, routes)
            }
        }
    }
}

/**
 * Lifecycle-owned voxel routing for ordinary feature planning. The retained
 * primitive payload at 100k is 3,172,864 bytes: one 131,072-entry long/int
 * table plus two 100k Int provider columns and one Long identity column.
 * No canonical row metadata or source payload is duplicated.
 */
private class CanonicalFeaturePlanningRoutes private constructor(private val capacity: Int) : AutoCloseable {
    private val tableSize = run { var value = 1; while (value <= capacity) value = value shl 1; value }
    private val keys = LongArray(tableSize)
    private val tokens = IntArray(tableSize)
    private val rowProviders = IntArray(capacity)
    private val sourceProviders = IntArray(capacity)
    private val surfaceIds = LongArray(capacity)
    private var freeHead = -1
    private var nextDescriptor = 0
    private var closed = false

    fun copyOccupiedKeys(expectedLiveCount: Int): LongArray? {
        if (closed || expectedLiveCount !in 0..capacity) return null
        val occupied = LongArray(expectedLiveCount)
        var size = 0
        for (slot in tokens.indices) if (tokens[slot] != 0) {
            if (size >= occupied.size) return null
            occupied[size++] = keys[slot]
        }
        return occupied.takeIf { size == expectedLiveCount }
    }
    fun copySortedSurfaceIds(expectedLiveCount: Int): LongArray? {
        if (closed || expectedLiveCount !in 0..capacity) return null
        val ids = LongArray(expectedLiveCount)
        var size = 0
        for (slot in tokens.indices) if (tokens[slot] != 0) {
            if (size >= ids.size) return null
            ids[size++] = surfaceIds[tokens[slot] - 1]
        }
        if (size != expectedLiveCount) return null
        ids.sort()
        if (ids.any { it <= 0L }) return null
        for (index in 1 until ids.size) if (ids[index] == ids[index - 1]) return null
        return ids
    }


    fun contains(voxel: Voxel, id: SurfaceId): Boolean {
        if (closed) return false
        val descriptor = descriptor(packVisibilityGridKey(voxel.x, voxel.y, voxel.z)) ?: return false
        return surfaceIds[descriptor] == id.value
    }

    fun view(
        cut: CompactCanonicalCut,
        base: CanonicalStateView,
        commit: CanonicalPublishedCommit?,
        budget: FeaturePlanningReadBudget,
    ) = RoutedFeaturePlanningView(cut, this, base, commit, budget)

    fun prepare(plan: PreparedCanonicalMutation, providerToken: Int): CanonicalFeatureRouteDelta? {
        if (closed || plan.targetLiveSurfaceCount !in 0..capacity) return null
        val removed = LongArray(plan.removedSurfaceCount)
        var removedCount = 0
        plan.visitRemovedSurfaceIds { id ->
            val key = plan.removedRouteKey(id) ?: return@visitRemovedSurfaceIds false
            val descriptor = descriptor(key)
                ?: return@visitRemovedSurfaceIds false
            removed[removedCount++] = key
            true
        }
        if (removedCount != plan.removedSurfaceCount) return null
        val keys = LongArray(plan.dirtyRowCount)
        val sources = IntArray(plan.dirtyRowCount)
        val ids = LongArray(plan.dirtyRowCount)
        var dirtyCount = 0
        if (!plan.visitDirtyRows { row ->
            val key = packVisibilityGridKey(row.voxel.x, row.voxel.y, row.voxel.z)
            val retained = descriptor(key)
            val sourceProvider = if (row.id.value >= plan.sourceCut.nextSurfaceIdHighWater) providerToken else {
                plan.removedRouteKey(row.id)?.let(::descriptor)?.let { sourceProviders[it] }
                    ?: retained?.takeIf { surfaceIds[it] == row.id.value }?.let { sourceProviders[it] }
                    ?: return@visitDirtyRows false
            }
            keys[dirtyCount] = key; sources[dirtyCount] = sourceProvider; ids[dirtyCount] = row.id.value; dirtyCount++
            true
        } || dirtyCount != plan.dirtyRowCount) return null
        return CanonicalFeatureRouteDelta(providerToken, removed, keys, sources, ids).also {
            CanonicalRuntimeCurrentTestHooks.onFeatureRouteDeltaPrepared?.invoke(it.memoryReceipt)
        }
    }

    fun apply(delta: CanonicalFeatureRouteDelta) {
        check(!closed)
        delta.removedKeys.forEach { key -> remove(key)?.let(::release) }
        delta.dirtyKeys.indices.forEach { index ->
            val key = delta.dirtyKeys[index]
            val descriptor = descriptor(key) ?: requireNotNull(acquire())
            rowProviders[descriptor] = delta.providerToken
            sourceProviders[descriptor] = delta.sourceProviders[index]
            surfaceIds[descriptor] = delta.surfaceIds[index]
            put(key, descriptor)
        }
    }

    private fun applyGeneration(generation: CanonicalCowGeneration, ordinal: Int): Boolean {
        val sourceRoutes = SourceProviderRouteScratch(tableSize)
        return generation.visitRoutingRecords { kind, record ->
            when {
                kind == CowFragmentKind.SOURCE && record is CowRecord.Source ->
                    sourceRoutes.put(record.value.id, FeatureRouteProviderToken.generation(ordinal))
                kind == CowFragmentKind.VOXEL_TOMBSTONE && record is CowRecord.Tombstone -> {
                    remove(packVisibilityGridKey(record.x, record.y, record.z))?.let { descriptor ->
                        sourceRoutes.put(record.id, sourceProviders[descriptor])
                        release(descriptor)
                    }
                }
                kind == CowFragmentKind.VOXEL_INDEX && record is CowRecord.Index -> {
                    val key = packVisibilityGridKey(record.x, record.y, record.z)
                    val retained = descriptor(key)
                    val source = sourceRoutes[record.id]
                        ?: retained?.let { sourceProviders[it] }
                        ?: return@visitRoutingRecords false
                    val descriptor = retained ?: acquire() ?: return@visitRoutingRecords false
                    rowProviders[descriptor] = FeatureRouteProviderToken.generation(ordinal)
                    sourceProviders[descriptor] = source
                    surfaceIds[descriptor] = record.id
                    put(key, descriptor)
                }
            }
            true
        }
    }

    fun resolve(
        voxel: Voxel,
        base: CanonicalStateView,
        commit: CanonicalPublishedCommit?,
        maximumPageReads: Long,
        maximumBytesRead: Long,
    ): RoutedFeatureRead {
        val descriptor = descriptor(packVisibilityGridKey(voxel.x, voxel.y, voxel.z))
            ?: return RoutedFeatureRead.Missing
        val rowPages = if (FeatureRouteProviderToken.isBase(rowProviders[descriptor])) 0L else 2L
        val requiredPages = Math.addExact(rowPages, 1L)
        val requiredBytes = Math.multiplyExact(requiredPages, CanonicalPageCache.PAGE_BYTES.toLong())
        if (maximumPageReads < requiredPages || maximumBytesRead < requiredBytes) return RoutedFeatureRead.Failed(CanonicalReadWork.ZERO)
        val row = when (val read = providerSurface(rowProviders[descriptor], voxel, base, commit)) {
            is RoutedProviderResult.Complete -> read
            is RoutedProviderResult.Refused -> return RoutedFeatureRead.Failed(read.work)
        }
        val source = when (val read = providerSource(sourceProviders[descriptor], row.value.id, base, commit)) {
            is RoutedProviderResult.Complete -> read
            is RoutedProviderResult.Refused -> return RoutedFeatureRead.Failed(row.work + read.work)
        }
        return RoutedFeatureRead.Found(row.value, source.value, row.work + source.work)
    }

    fun resolveSurface(
        voxel: Voxel,
        base: CanonicalStateView,
        commit: CanonicalPublishedCommit?,
        maximumPageReads: Long,
        maximumBytesRead: Long,
    ): CanonicalBoundedReadResult<CompactSurface?> {
        val descriptor = descriptor(packVisibilityGridKey(voxel.x, voxel.y, voxel.z))
            ?: return CanonicalBoundedReadResult.Complete(null, CanonicalReadWork.ZERO)
        val requiredPages = if (FeatureRouteProviderToken.isBase(rowProviders[descriptor])) 1L else 2L
        if (maximumPageReads < requiredPages ||
            maximumBytesRead < requiredPages * CanonicalPageCache.PAGE_BYTES
        ) return CanonicalBoundedReadResult.Refused(
            CanonicalBoundedReadRefusal.LIMIT_EXHAUSTED, CanonicalReadWork.ZERO,
        )
        return when (val read = providerSurface(rowProviders[descriptor], voxel, base, commit)) {
            is RoutedProviderResult.Complete -> CanonicalBoundedReadResult.Complete(read.value, read.work)
            is RoutedProviderResult.Refused -> CanonicalBoundedReadResult.Refused(
                CanonicalBoundedReadRefusal.CANONICAL_READ_FAILURE, read.work,
            )
        }
    }

    private fun providerSurface(token: Int, voxel: Voxel, base: CanonicalStateView, commit: CanonicalPublishedCommit?): RoutedProviderResult<CompactSurface> {
        if (CanonicalRuntimeCurrentTestHooks.failFeatureRouteRead?.invoke("row") == true) return RoutedProviderResult.Refused(CanonicalReadWork.ZERO)
        return if (FeatureRouteProviderToken.isBase(token)) when (val read = base.findByVoxelBounded(voxel, 1L, CanonicalPageCache.PAGE_BYTES.toLong())) {
            is CanonicalBoundedReadResult.Complete -> read.value?.let { RoutedProviderResult.Complete(it, read.work) } ?: RoutedProviderResult.Refused(read.work)
            is CanonicalBoundedReadResult.Refused -> RoutedProviderResult.Refused(read.work)
        } else when (val read = commit?.routingGenerationAt(FeatureRouteProviderToken.generationOrdinal(token))?.routedSurface(voxel)) {
            is RoutedGenerationRead.Complete -> RoutedProviderResult.Complete(read.value, read.work)
            is RoutedGenerationRead.Refused -> RoutedProviderResult.Refused(read.work)
            null -> RoutedProviderResult.Refused(CanonicalReadWork.ZERO)
        }
    }
    private fun providerSource(token: Int, id: SurfaceId, base: CanonicalStateView, commit: CanonicalPublishedCommit?): RoutedProviderResult<PagedSource> {
        if (CanonicalRuntimeCurrentTestHooks.failFeatureRouteRead?.invoke("source") == true) return RoutedProviderResult.Refused(CanonicalReadWork.ZERO)
        return if (FeatureRouteProviderToken.isBase(token)) when (val read = base.readSourceByIdBounded(id, 1L, CanonicalPageCache.PAGE_BYTES.toLong())) {
            is CanonicalBoundedReadResult.Complete -> read.value?.let { RoutedProviderResult.Complete(it, read.work) } ?: RoutedProviderResult.Refused(read.work)
            is CanonicalBoundedReadResult.Refused -> RoutedProviderResult.Refused(read.work)
        } else when (val read = commit?.routingGenerationAt(FeatureRouteProviderToken.generationOrdinal(token))?.routedSource(id)) {
            is RoutedGenerationRead.Complete -> RoutedProviderResult.Complete(read.value, read.work)
            is RoutedGenerationRead.Refused -> RoutedProviderResult.Refused(read.work)
            null -> RoutedProviderResult.Refused(CanonicalReadWork.ZERO)
        }
    }

    private fun acquire(): Int? = when {
        freeHead >= 0 -> freeHead.also { descriptor ->
            freeHead = -rowProviders[descriptor] - 1
            rowProviders[descriptor] = 0
        }
        nextDescriptor < capacity -> nextDescriptor++
        else -> null
    }
    private fun release(value: Int) {
        rowProviders[value] = -(freeHead + 1)
        sourceProviders[value] = 0
        surfaceIds[value] = 0L
        freeHead = value
    }
    private fun descriptor(key: Long): Int? {
        var index = slot(key)
        while (tokens[index] != 0) {
            if (keys[index] == key) return tokens[index] - 1
            index = (index + 1) and (tableSize - 1)
        }
        return null
    }
    private fun put(key: Long, descriptor: Int) {
        var index = slot(key)
        while (tokens[index] != 0 && keys[index] != key) index = (index + 1) and (tableSize - 1)
        keys[index] = key; tokens[index] = descriptor + 1
    }
    private fun remove(key: Long): Int? {
        var index = slot(key)
        while (tokens[index] != 0) {
            if (keys[index] == key) {
                val removed = tokens[index] - 1
                tokens[index] = 0
                var next = (index + 1) and (tableSize - 1)
                while (tokens[next] != 0) {
                    val movedKey = keys[next]; val moved = tokens[next] - 1
                    tokens[next] = 0; put(movedKey, moved); next = (next + 1) and (tableSize - 1)
                }
                return removed
            }
            index = (index + 1) and (tableSize - 1)
        }
        return null
    }
    private fun slot(key: Long): Int {
        return featureRouteSlot(key, tableSize - 1)
    }
    fun memoryReceipt(maximumTouches: Int): CanonicalFeaturePlanningMemoryReceipt {
        require(maximumTouches in 0..capacity)
        val routeArrays = Math.addExact(
            Math.multiplyExact(tableSize.toLong(), (Long.SIZE_BYTES + Int.SIZE_BYTES).toLong()),
            Math.multiplyExact(capacity.toLong(), 2L * Int.SIZE_BYTES + Long.SIZE_BYTES),
        )
        val retained = Math.addExact(136L, Math.addExact(routeArrays, 5L * 16L))
        val scratch = Math.addExact(64L, Math.addExact(2L * 16L,
            Math.multiplyExact(tableSize.toLong(), (Long.SIZE_BYTES + Int.SIZE_BYTES).toLong())))
        val borrow = Math.addExact(128L + 3L * 16L, Math.multiplyExact(maximumTouches.toLong(), 24L))
        val routeDelta = Math.addExact(112L, Math.multiplyExact(maximumTouches.toLong(), 28L))
        return CanonicalFeaturePlanningMemoryReceipt(retained, scratch, borrow, routeDelta)
    }
    override fun close() { if (!closed) { closed = true; tokens.fill(0); rowProviders.fill(0); sourceProviders.fill(0); surfaceIds.fill(0) } }

    companion object {
        fun build(base: CompactCanonicalStore, commit: CanonicalPublishedCommit?, capacity: Int): CanonicalFeaturePlanningRoutes? {
            val routes = CanonicalFeaturePlanningRoutes(capacity)
            var cursor = 0
            while (cursor < base.cut.liveSurfaceCount) {
                val page = base.readRendererPage(
                    cursor, minOf(512, base.cut.liveSurfaceCount - cursor),
                )
                if (page.rows.isEmpty()) { routes.close(); return null }
                for (row in page.rows) {
                    val source = (base.readSourceById(row.id) as? CanonicalPageRead.Complete)?.value ?: run {
                        routes.close(); return null
                    }
                    val descriptor = routes.acquire() ?: run { routes.close(); return null }
                    routes.rowProviders[descriptor] = FeatureRouteProviderToken.BASE
                    routes.sourceProviders[descriptor] = FeatureRouteProviderToken.BASE
                    routes.surfaceIds[descriptor] = row.id.value
                    routes.put(packVisibilityGridKey(row.voxel.x, row.voxel.y, row.voxel.z), descriptor)
                }
                cursor += page.rows.size
            }
            commit?.let { published ->
                for (ordinal in 0 until published.routingGenerationCount()) {
                    if (!routes.applyGeneration(published.routingGenerationAt(ordinal), ordinal)) {
                        routes.close(); return null
                    }
                }
            }
            return routes
        }
    }
}

internal class FeaturePlanningReadBudget private constructor(
    val maximumTouches: Int,
    val maximumPageReads: Long,
    val maximumBytesRead: Long,
) {
    companion object {
        fun derived(maximumTouches: Int, surfaceCapacity: Int): FeaturePlanningReadBudget {
            val pages = Math.multiplyExact(maximumTouches.toLong(), 3L)
            return explicit(
                maximumTouches,
                pages,
                Math.multiplyExact(pages, CanonicalPageCache.PAGE_BYTES.toLong()),
                surfaceCapacity,
            )
        }

        fun explicit(
            maximumTouches: Int,
            maximumPageReads: Long,
            maximumBytesRead: Long,
            surfaceCapacity: Int,
        ): FeaturePlanningReadBudget {
            require(maximumTouches in 0..surfaceCapacity && maximumPageReads >= 0L && maximumBytesRead >= 0L)
            return FeaturePlanningReadBudget(maximumTouches, maximumPageReads, maximumBytesRead)
        }
    }
}

private object FeatureRouteProviderToken {
    const val BASE = 1
    private const val FIRST_GENERATION = 2
    fun isBase(token: Int) = token == BASE
    fun generation(ordinal: Int): Int {
        require(ordinal >= 0)
        return Math.addExact(FIRST_GENERATION, ordinal)
    }
    fun generationOrdinal(token: Int): Int {
        require(token >= FIRST_GENERATION)
        return token - FIRST_GENERATION
    }
}

private class CurrentRowFoldScratch {
    private val states = ByteArray(WINDOW_SIZE)
    private val sourceStates = ByteArray(WINDOW_SIZE)
    private val xs = IntArray(WINDOW_SIZE)
    private val ys = IntArray(WINDOW_SIZE)
    private val zs = IntArray(WINDOW_SIZE)
    private val normals = IntArray(WINDOW_SIZE)
    private val confidences = IntArray(WINDOW_SIZE)
    private val fingerprints = ByteArray(Math.multiplyExact(WINDOW_SIZE, 32))
    private var firstId = 1L
    private var lastId = 0L

    fun reset(first: Long, last: Long) {
        require(first >= 1L && last >= first && last - first < WINDOW_SIZE)
        firstId = first; lastId = last
        states.fill(UNKNOWN); sourceStates.fill(UNKNOWN)
    }

    fun apply(generation: CanonicalCowGeneration): Boolean = generation.visitCurrentFoldRecords(firstId, lastId) { kind, record ->
        when {
            kind == CowFragmentKind.ROW && record is CowRecord.Row -> {
                val value = record.value
                val id = value.id.toWindowIndex() ?: return@visitCurrentFoldRecords true
                if (states[id] == UNKNOWN) {
                    states[id] = PRESENT
                    xs[id] = value.x; ys[id] = value.y; zs[id] = value.z
                    normals[id] = value.normal; confidences[id] = value.confidence
                }
            }
            kind == CowFragmentKind.SOURCE && record is CowRecord.Source -> {
                val value = record.value
                val id = value.id.toWindowIndex() ?: return@visitCurrentFoldRecords true
                if (sourceStates[id] == UNKNOWN) {
                    sourceStates[id] = PRESENT
                    value.words().copyInto(fingerprints, id * 32)
                }
            }
        }
        true
    }

    fun row(id: Long) = states[requireNotNull(id.toWindowIndex())]
    fun surface(id: Long): CompactSurface {
        val index = requireNotNull(id.toWindowIndex())
        return CompactSurface(
            SurfaceId(id),
            Voxel(xs[index], ys[index], zs[index]),
            normals[index],
            confidences[index],
        )
    }

    fun sourceFingerprint(id: Long): CanonicalReceiptBytes? {
        val index = requireNotNull(id.toWindowIndex())
        return if (sourceStates[index] == PRESENT) {
            CanonicalReceiptBytes(fingerprints.copyOfRange(index * 32, index * 32 + 32))
        } else {
            null
        }
    }

    private fun Long.toWindowIndex(): Int? = if (this in firstId..lastId) (this - firstId).toInt() else null

    companion object {
        const val UNKNOWN: Byte = 0
        const val PRESENT: Byte = 1
        const val WINDOW_SIZE = 272
        fun memoryBytes(): Long = Math.addExact(176L, Math.multiplyExact(WINDOW_SIZE.toLong(), 54L))
    }
}

private class SourceProviderRouteScratch(tableSize: Int) {
    private val mask = tableSize - 1
    private val keys = LongArray(tableSize)
    private val values = IntArray(tableSize)
    operator fun get(key: Long): Int? {
        var slot = slot(key)
        while (values[slot] != 0) {
            if (keys[slot] == key) return values[slot]
            slot = (slot + 1) and mask
        }
        return null
    }
    fun put(key: Long, value: Int) {
        require(value > 0)
        var slot = slot(key)
        while (values[slot] != 0 && keys[slot] != key) slot = (slot + 1) and mask
        keys[slot] = key; values[slot] = value
    }
    private fun slot(key: Long): Int {
        return featureRouteSlot(key, mask)
    }
}

private fun featureRouteSlot(key: Long, mask: Int): Int {
    var mixed = key xor (key ushr 33)
    mixed *= -49064778989728563L
    mixed = mixed xor (mixed ushr 33)
    return mixed.toInt() and mask
}

private class CanonicalFeatureRouteDelta(
    val providerToken: Int,
    val removedKeys: LongArray,
    val dirtyKeys: LongArray,
    val sourceProviders: IntArray,
    val surfaceIds: LongArray,
) {
    val memoryReceipt = CanonicalFeatureRouteDeltaMemoryReceipt(
        removedKeys.size,
        dirtyKeys.size,
        Math.addExact(
            112L,
            Math.addExact(
                Math.multiplyExact(removedKeys.size.toLong(), 8L),
                Math.multiplyExact(dirtyKeys.size.toLong(), 20L),
            ),
        ),
    )
}

internal data class CanonicalFeatureRouteDeltaMemoryReceipt(
    val removedRoutes: Int,
    val dirtyRoutes: Int,
    val retainedBytes: Long,
)

/** Reuses the authenticated current route index for sparse depth rays. */
private class RoutedCanonicalDepthView(
    private val delegate: CanonicalStateView,
    private val routes: CanonicalFeaturePlanningRoutes,
    private val base: CanonicalStateView,
    private val commit: CanonicalPublishedCommit?,
) : CanonicalStateView by delegate {
    override fun findByVoxelBounded(
        voxel: Voxel,
        maximumPageReads: Long,
        maximumBytesRead: Long,
    ): CanonicalBoundedReadResult<CompactSurface?> =
        routes.resolveSurface(voxel, base, commit, maximumPageReads, maximumBytesRead)
}

private class RoutedFeaturePlanningView(
    override val cut: CompactCanonicalCut,
    private val routes: CanonicalFeaturePlanningRoutes,
    private val base: CanonicalStateView,
    private val commit: CanonicalPublishedCommit?,
    budget: FeaturePlanningReadBudget,
) : CanonicalFeaturePlanningView {
    private val touchedIds = LongArray(budget.maximumTouches)
    private val touchedRows = arrayOfNulls<CompactSurface>(budget.maximumTouches)
    private val touchedSources = arrayOfNulls<PagedSource>(budget.maximumTouches)
    private var touchedCount = 0
    private var remainingPageReads = budget.maximumPageReads
    private var remainingBytesRead = budget.maximumBytesRead
    private var work = CanonicalReadWork.ZERO
    var routingFailed = false
        private set
    override val generationZeroAuthority: CanonicalStateView get() = base.generationZeroAuthority
    override fun findByVoxel(voxel: Voxel): CompactSurface? {
        if (routingFailed) return null
        (0 until touchedCount).firstOrNull { touchedRows[it]?.voxel == voxel }
            ?.let { return touchedRows[it] }
        return when (
        val read = routes.resolve(
            voxel, base, commit,
            remainingPageReads,
            remainingBytesRead,
        )
        ) {
        RoutedFeatureRead.Missing -> null
        is RoutedFeatureRead.Failed -> null.also {
            work += read.work
            remainingPageReads -= read.work.pageReads
            remainingBytesRead -= read.work.bytesRead
            routingFailed = true
        }
        is RoutedFeatureRead.Found -> read.row.also {
            work += read.work
            remainingPageReads -= read.work.pageReads
            remainingBytesRead -= read.work.bytesRead
            val existing = (0 until touchedCount).firstOrNull { index -> touchedIds[index] == it.id.value }
            val index = existing ?: touchedCount.also { next ->
                if (next >= touchedIds.size) { routingFailed = true; return@also }
                touchedCount++
            }
            touchedIds[index] = it.id.value; touchedRows[index] = it; touchedSources[index] = read.source
        }
        }
    }
    override fun findById(id: SurfaceId): CompactSurface? =
        (0 until touchedCount).firstOrNull { touchedIds[it] == id.value }?.let { touchedRows[it] }
    override fun readSourceById(id: SurfaceId): CanonicalPageRead<PagedSource?> =
        CanonicalPageRead.Complete(
            (0 until touchedCount).firstOrNull { touchedIds[it] == id.value }?.let { touchedSources[it] }, 0, 0,
        )
    override fun readSourceByIdBounded(
        id: SurfaceId,
        maximumPageReads: Long,
        maximumBytesRead: Long,
    ): CanonicalBoundedReadResult<PagedSource?> = if (maximumPageReads < 0L || maximumBytesRead < 0L) {
        CanonicalBoundedReadResult.Refused(CanonicalBoundedReadRefusal.LIMIT_EXHAUSTED)
    } else CanonicalBoundedReadResult.Complete(
        (0 until touchedCount).firstOrNull { touchedIds[it] == id.value }?.let { touchedSources[it] },
        CanonicalReadWork.ZERO,
    )
    override fun readWorkReceipt() = work
}

private sealed interface RoutedFeatureRead {
    data object Missing : RoutedFeatureRead
    data class Failed(val work: CanonicalReadWork = CanonicalReadWork.ZERO) : RoutedFeatureRead
    data class Found(val row: CompactSurface, val source: PagedSource, val work: CanonicalReadWork) : RoutedFeatureRead
}

private sealed interface RoutedProviderResult<out T> {
    data class Complete<T>(val value: T, val work: CanonicalReadWork) : RoutedProviderResult<T>
    data class Refused(val work: CanonicalReadWork) : RoutedProviderResult<Nothing>
}

private operator fun CanonicalReadWork.plus(other: CanonicalReadWork) = CanonicalReadWork(
    Math.addExact(directLookups, other.directLookups),
    Math.addExact(pageReads, other.pageReads),
    Math.addExact(inspectedRows, other.inspectedRows),
    Math.addExact(bytesRead, other.bytesRead),
)

internal data class CanonicalCompleteCurrentLeaseReceipt(
    val baseRetained: CompactRetainedMemoryReceipt,
    val cowProofAndIndexBytes: Long,
    val featurePlanningRouteBytes: Long,
    val retainedTotalBytes: Long,
    /** Lifecycle-only cold materialization peak; never charged to an ordinary bounded request. */
    val lifecycleOpenPeakBytes: Long,
)

internal data class CanonicalFeaturePlanningMemoryReceipt(
    val routeRetainedBytes: Long,
    val lifecycleConstructionScratchBytes: Long,
    val borrowCacheBytes: Long,
    val maximumRouteDeltaBytes: Long,
)

private open class ScalarCanonicalStateView(
    override val authorityParentKey: String,
    override val cut: CompactCanonicalCut,
    private val storage: CompactStorageReceipt,
) : ScalarCanonicalAuthority {
    val scalarMemoryReceipt = ScalarCanonicalMemoryReceipt.measure(authorityParentKey, cut)
    override fun findById(id: SurfaceId): CompactSurface? = null
    override fun findByVoxel(voxel: Voxel): CompactSurface? = null
    override fun readPage(region: StorageRegion, page: Int, cursor: Int, limit: Int) = CompactPage(emptyList(), null, 0)
    override fun readSourceById(id: SurfaceId): CanonicalPageRead<PagedSource?> = CanonicalPageRead.Complete(null, 0, 0)
    override fun visitSourceSupport(target: SurfaceId, cursor: SourceSupportCursor?, sink: (PagedSupport) -> Boolean) =
        SourceSupportRead.Complete(0, null, 0, 0)
    override fun retainedMemoryReceipt() = CompactRetainedMemoryReceipt(
        0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
        scalarMemoryReceipt.portableBytes, 0,
    )
    override fun allocatedStorageReceipt() = storage
    override fun close() = Unit
}

private fun CanonicalStateView.scalarView(directory: File) = ScalarCanonicalStateView(
    directory.absoluteFile.toPath().normalize().toString(), cut, allocatedStorageReceipt(),
)

/** Exact portable layout of the strongly-reachable steady scalar authority. */
internal data class ScalarCanonicalMemoryReceipt(
    val objectAndReferenceBytes: Long,
    val authorityParentUtf8Bytes: Long,
    val cutScalarBytes: Long,
    val cutIdentityUtf8Bytes: Long,
    val cutHashBytes: Long,
    val baselineIdentityUtf8Bytes: Long,
) {
    val portableBytes: Long get() = objectAndReferenceBytes + authorityParentUtf8Bytes + cutScalarBytes +
        cutIdentityUtf8Bytes + cutHashBytes + baselineIdentityUtf8Bytes

    companion object {
        // Portable 64-bit assigned layout: scalar view/receipt/storage, cut/group,
        // two hash wrappers+array envelopes, and three UTF-8 identity envelopes.
        // A retained bootstrap baseline adds its envelope and two identity envelopes.
        private const val OBJECT_AND_REFERENCE_BYTES = 384L
        private const val BASELINE_OBJECT_AND_REFERENCE_BYTES = 104L
        private const val CUT_SCALAR_BYTES = 3L * Long.SIZE_BYTES + 4L * Int.SIZE_BYTES
        fun measure(parent: String, cut: CompactCanonicalCut): ScalarCanonicalMemoryReceipt {
            val baseline = cut.seededEmptyBaseline
            val baselineBytes = if (baseline == null) 0L else
                baseline.bindingIdentity.encodeToByteArray().size.toLong() +
                    baseline.groupIdentity.encodeToByteArray().size.toLong() + 3L * Long.SIZE_BYTES
            return ScalarCanonicalMemoryReceipt(
                OBJECT_AND_REFERENCE_BYTES + if (baseline == null) 0 else BASELINE_OBJECT_AND_REFERENCE_BYTES,
                parent.encodeToByteArray().size.toLong(),
                CUT_SCALAR_BYTES,
                cut.group.value.encodeToByteArray().size.toLong() + cut.profile.encodeToByteArray().size.toLong(),
                cut.rootHash.size.toLong() + cut.sourceHash.size.toLong(),
                baselineBytes,
            )
        }
    }
}

/** Disabled-by-default scalar observation; it retains no current view or payload. */
internal object CanonicalRuntimeCurrentTestHooks {
    @Volatile var onAuthenticatedBorrow: ((Long) -> Unit)? = null
    @Volatile var onFeatureRouteRead: ((String, CowReadWork) -> Unit)? = null
    @Volatile var failFeatureRouteRead: ((String) -> Boolean)? = null
    @Volatile var onFeatureRouteDeltaPrepared: ((CanonicalFeatureRouteDeltaMemoryReceipt) -> Unit)? = null
    @Volatile var onCurrentFoldRead: ((CowReadWork) -> Unit)? = null
}

internal data class CanonicalRendererPage(
    val cut: CompactCanonicalCut,
    val rows: List<CommittedGeometryRow>,
    val nextCursor: Long?,
)

/** Bounded semantic phases for a canonical runtime open; never contains exception or path data. */
internal enum class CanonicalRuntimeOpenFailureStage(val statusSuffix: String) {
    BOOTSTRAP_CANDIDATE("BootstrapCandidate"),
    ACTIVATION_PREPARATION("ActivationPreparation"),
    ACTIVATION_AUTHORITY_OPEN("ActivationAuthorityOpen"),
    BASE_AUTHORITY_OPEN("BaseAuthorityOpen"),
    COMMIT_STORE_OPEN("CommitStoreOpen"),
    CURRENT_REOPEN("CurrentReopen"),
    FEATURE_ROUTE_HYDRATION("FeatureRouteHydration"),
}

internal fun canonicalRuntimeOpenFailureStatus(
    reason: SurfaceOwnershipRestoreRefusal,
    stage: CanonicalRuntimeOpenFailureStage?,
): String {
    val reasonStatus = when (reason) {
        SurfaceOwnershipRestoreRefusal.INVALID_CONFIGURATION -> "openRefusedInvalidConfiguration"
        SurfaceOwnershipRestoreRefusal.CORRUPT -> "openRefusedCorrupt"
        SurfaceOwnershipRestoreRefusal.FORK -> "openRefusedFork"
        SurfaceOwnershipRestoreRefusal.DURABILITY_FAILURE -> "openRefusedDurabilityFailure"
    }
    return reasonStatus + (stage?.statusSuffix ?: "")
}
