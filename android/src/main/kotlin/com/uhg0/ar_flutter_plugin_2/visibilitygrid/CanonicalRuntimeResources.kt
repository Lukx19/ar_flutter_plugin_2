package com.uhg0.ar_flutter_plugin_2.visibilitygrid

import com.uhg0.ar_flutter_plugin_2.capture.StorageBudgetCoordinatorV2
import java.io.File

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
) : AutoCloseable {
    private val budget = CoordinatorStorageBudget(coordinator)
    private var owner: SurfaceOwnership? = null
    private var current: CurrentLease? = null
    private var closed = false

    fun openInitial(baseline: committedEmptyBaseline): SurfaceOwnershipOpenResult {
        checkOpen()
        check(owner == null)
        check(!CanonicalActivationSelector.hasDurableSelector(group, directory))
        val configuration = this.configuration.copy(seededEmptyBaseline = baseline)
        if (CompactCanonicalStore.prepareEmptyV6Bootstrap(
                group, directory, budget, baseline, configuration,
            )
            !is CompactCanonicalMigrationResult.Prepared
        ) return SurfaceOwnershipOpenResult.Refused(SurfaceOwnershipRestoreRefusal.DURABILITY_FAILURE)
        val plan = (CanonicalActivation.prepareEmptyV6(
            group, directory, budget, baseline, configuration,
        )
            as? CanonicalActivationPreparation.Prepared)?.plan
            ?: return SurfaceOwnershipOpenResult.Refused(SurfaceOwnershipRestoreRefusal.DURABILITY_FAILURE)
        val opened = SurfaceOwnership.open(group, directory, budget, plan, configuration)
        if (opened !is SurfaceOwnershipOpenResult.Opened) return opened
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
        if (!CanonicalActivationSelector.hasDurableSelector(group, directory)) {
            return SurfaceOwnershipOpenResult.Refused(SurfaceOwnershipRestoreRefusal.CORRUPT)
        }
        val opened = SurfaceOwnership.open(group, directory, budget, configuration)
        if (opened !is SurfaceOwnershipOpenResult.Opened) return opened
        owner = opened.ownership
        if (!warmCurrent()) {
            opened.ownership.close(); owner = null
            return SurfaceOwnershipOpenResult.Refused(SurfaceOwnershipRestoreRefusal.CORRUPT)
        }
        return opened
    }

    fun owner(): SurfaceOwnership = requireNotNull(owner) { "canonical surface runtime authority is unavailable" }

    private fun openGenerationZero(): CompactCanonicalOpenResult {
        checkOpen()
        return CompactCanonicalStore.openV6(group, directory, budget, configuration)
    }

    /** Borrows exactly the selector-named v6 cut for one bounded operation. */
    @Synchronized fun <T> withCurrent(block: (CanonicalStateView) -> T): T? {
        checkOpen()
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
        return withAuthenticatedCompleteCurrent(
            CanonicalRevisionPair(request.expectedGeometryRevision, request.expectedLineageRevision),
            onFailure = { failure ->
                BoundedCanonicalLookupResult.Refused(
                    failure.lookupReason,
                    BoundedCanonicalLookupReceipt(0, 0, 0, 0, false),
                )
            },
        ) { view ->
            val bounded = BoundedCanonicalCurrentView(view, request, configuration.voxelMicrometers)
            bounded.result(block(bounded))
        }
    }

    /** Prepares one depth batch from the same authenticated complete current cut. */
    @Synchronized
    internal fun prepareEvidenceBatch(
        command: CanonicalEvidenceBatchCommand,
    ): CanonicalMutationPreparation {
        checkOpen()
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
        if (!lease.isCurrent(owner?.activationState()?.cut)) {
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
        val lease = current ?: return null
        // Feature-local correlation identifies associations only. Canonical
        // occupancy, normals, identity and allocation provenance always come
        // from the lifecycle-owned complete authority.
        if (!lease.isCurrent(owner?.activationState()?.cut)) {
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
        if (!lease.isCurrent(owner?.activationState()?.cut) || view.cut != lease.scalarView.cut) {
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
        current?.scalarView?.scalarMemoryReceipt
    @Synchronized internal fun retainedCompleteCurrentMemoryReceipt(): CompactRetainedMemoryReceipt? =
        current?.completeView?.retainedMemoryReceipt()
    @Synchronized internal fun completeCurrentLeaseReceipt(): CanonicalCompleteCurrentLeaseReceipt? =
        current?.resourceReceipt()
    @Synchronized internal fun featurePlanningMemoryReceipt(maximumTouches: Int): CanonicalFeaturePlanningMemoryReceipt? =
        current?.featurePlanningMemoryReceipt(maximumTouches)

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
        val base = (openGenerationZero() as? CompactCanonicalOpenResult.Opened)?.store ?: return null
        val store = CanonicalCommitStore.open(directory, budget) ?: run { base.close(); return null }
        val retained = retainedCurrentReceipt()
        return try {
            when (val selected = store.reopen(base, retained)) {
                is CanonicalReopenResult.GenerationZero ->
                    CurrentLease.create(base.scalarView(directory), selected.view, base, null, configuration.surfaceCapacity)
                is CanonicalReopenResult.Selected -> {
                    val scalar = selected.commit.view.scalarView(directory)
                    CurrentLease.create(scalar, selected.commit.view, base, selected.commit, configuration.surfaceCapacity)
                }
                is CanonicalReopenResult.Refused -> { base.close(); null }
            }
        } catch (_: Throwable) {
            base.close(); null
        } finally { store.close() }
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
        val lease = current ?: return null
        val view = lease.completeView
        var id = 1L
        var rendered = 0
        val page = ArrayList<CommittedGeometryRow>(512)
        while (id < view.cut.nextSurfaceIdHighWater) {
            val row = view.findById(SurfaceId(id++)) ?: continue
            val source = (view.readSourceById(row.id) as? CanonicalPageRead.Complete)?.value ?: return null
            if (hydrateKernel && !kernel.hydrateCanonicalSurface(row, source.allocationFingerprint)) return null
            if (rendered < rendererLimit) {
                page += CommittedGeometryRow(
                    surfaceId = row.id.value,
                    voxel = row.voxel,
                    packedNormal = row.packedNormal,
                    normalConfidence = row.normalConfidence,
                    lineageCount = view.cut.lineageCount,
                )
                rendered++
                if (page.size == 512) {
                    sink(CanonicalRendererPage(view.cut, page.toList(), null)); page.clear()
                }
            }
        }
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
        val lease = current ?: return null
        val view = lease.completeView
        return run {
            require(cursor in 0 until view.cut.nextSurfaceIdHighWater && limit in 1..512)
            val rows = ArrayList<CommittedGeometryRow>(limit)
            var id = cursor + 1
            while (id < view.cut.nextSurfaceIdHighWater && rows.size < limit) {
                view.findById(SurfaceId(id))?.let { row ->
                    rows += CommittedGeometryRow(
                        surfaceId = row.id.value,
                        voxel = row.voxel,
                        packedNormal = row.packedNormal,
                        normalConfidence = row.normalConfidence,
                        lineageCount = view.cut.lineageCount,
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

    /** Test/diagnostic recovery projection in one cold verified scope. */
    internal fun readAllRendererKeys(): LongArray? {
        checkOpen()
        val lease = current ?: return null
        val view = lease.completeView
        val keys = LongArray(view.cut.liveSurfaceCount)
        var size = 0
        var id = 1L
        while (id < view.cut.nextSurfaceIdHighWater) {
            view.findById(SurfaceId(id++))?.let { row ->
                keys[size++] = packVisibilityGridKey(row.voxel.x, row.voxel.y, row.voxel.z)
            }
        }
        return if (size == keys.size) keys else keys.copyOf(size)
    }

    override fun close() {
        if (closed) return
        closed = true
        try { owner?.close() } finally { invalidateCurrent() }
        owner = null
    }

    private fun checkOpen() = check(!closed) { "canonical surface runtime resources are closed" }

    companion object {
        private const val RUNTIME_DIRECTORY = "visibility-grid-canonical-surface-runtime"

        fun open(
            root: File,
            group: SurfaceGroup,
            coordinator: StorageBudgetCoordinatorV2,
            configuration: SurfaceOwnershipConfiguration = SurfaceOwnershipConfiguration(),
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
            return CanonicalRuntimeResources(group, groupDirectory, groupDirectory, coordinator, configuration)
        }
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
        fun featurePlanningView(budget: FeaturePlanningReadBudget): RoutedFeaturePlanningView =
            featureRoutes.view(scalarView.cut, base, commit, budget)
        fun featurePlanningMemoryReceipt(maximumTouches: Int) = featureRoutes.memoryReceipt(maximumTouches)

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
            return CanonicalCompleteCurrentLeaseReceipt(
                baseReceipt,
                cowRetained,
                routing.routeRetainedBytes,
                Math.addExact(retainedTotal, routing.routeRetainedBytes),
                maxOf(
                    Math.addExact(baseReceipt.peakWithScratchBytes, routing.routeRetainedBytes),
                    cowConstruction,
                    Math.addExact(
                        retainedTotal,
                        Math.addExact(routing.routeRetainedBytes, routing.lifecycleConstructionScratchBytes),
                    ),
                ),
            )
        }
        fun isCurrent(cut: CompactCanonicalCut?): Boolean =
            cut != null && cut == scalarView.cut && cut == completeView.cut
        override fun close() {
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
 * primitive payload at 100k is 2,372,864 bytes: one 131,072-entry long/int
 * table plus two 100k Int provider columns. No canonical row is duplicated.
 */
private class CanonicalFeaturePlanningRoutes private constructor(private val capacity: Int) : AutoCloseable {
    private val tableSize = run { var value = 1; while (value <= capacity) value = value shl 1; value }
    private val keys = LongArray(tableSize)
    private val tokens = IntArray(tableSize)
    private val rowProviders = IntArray(capacity)
    private val sourceProviders = IntArray(capacity)
    private var freeHead = -1
    private var nextDescriptor = 0
    private var closed = false

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
        var dirtyCount = 0
        if (!plan.visitDirtyRows { row ->
            val key = packVisibilityGridKey(row.voxel.x, row.voxel.y, row.voxel.z)
            val retained = descriptor(key)
            val sourceProvider = if (row.id.value >= plan.sourceCut.nextSurfaceIdHighWater) providerToken else {
                retained?.let { sourceProviders[it] }
                    ?: plan.removedRouteKey(row.id)?.let(::descriptor)?.let { sourceProviders[it] }
                    ?: return@visitDirtyRows false
            }
            keys[dirtyCount] = key; sources[dirtyCount] = sourceProvider; dirtyCount++
            true
        } || dirtyCount != plan.dirtyRowCount) return null
        return CanonicalFeatureRouteDelta(providerToken, removed, keys, sources).also {
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
        val row = providerSurface(rowProviders[descriptor], voxel, base, commit)
            ?: return RoutedFeatureRead.Failed()
        val source = providerSource(sourceProviders[descriptor], row.value.id, base, commit)
            ?: return RoutedFeatureRead.Failed(row.work)
        return RoutedFeatureRead.Found(row.value, source.value, row.work + source.work)
    }

    private fun providerSurface(token: Int, voxel: Voxel, base: CanonicalStateView, commit: CanonicalPublishedCommit?): RoutedProviderRead<CompactSurface>? {
        if (CanonicalRuntimeCurrentTestHooks.failFeatureRouteRead?.invoke("row") == true) return null
        return if (FeatureRouteProviderToken.isBase(token)) when (val read = base.findByVoxelBounded(voxel, 1L, CanonicalPageCache.PAGE_BYTES.toLong())) {
            is CanonicalBoundedReadResult.Complete -> read.value?.let { RoutedProviderRead(it, read.work) }
            is CanonicalBoundedReadResult.Refused -> null
        } else when (val read = commit?.routingGenerationAt(FeatureRouteProviderToken.generationOrdinal(token))?.routedSurface(voxel)) {
            is RoutedGenerationRead.Complete -> RoutedProviderRead(read.value, read.work)
            is RoutedGenerationRead.Refused, null -> null
        }
    }
    private fun providerSource(token: Int, id: SurfaceId, base: CanonicalStateView, commit: CanonicalPublishedCommit?): RoutedProviderRead<PagedSource>? {
        if (CanonicalRuntimeCurrentTestHooks.failFeatureRouteRead?.invoke("source") == true) return null
        return if (FeatureRouteProviderToken.isBase(token)) when (val read = base.readSourceByIdBounded(id, 1L, CanonicalPageCache.PAGE_BYTES.toLong())) {
            is CanonicalBoundedReadResult.Complete -> read.value?.let { RoutedProviderRead(it, read.work) }
            is CanonicalBoundedReadResult.Refused -> null
        } else when (val read = commit?.routingGenerationAt(FeatureRouteProviderToken.generationOrdinal(token))?.routedSource(id)) {
            is RoutedGenerationRead.Complete -> RoutedProviderRead(read.value, read.work)
            is RoutedGenerationRead.Refused, null -> null
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
            Math.multiplyExact(capacity.toLong(), 2L * Int.SIZE_BYTES),
        )
        val retained = Math.addExact(128L, Math.addExact(routeArrays, 4L * 16L))
        val scratch = Math.addExact(64L, Math.addExact(2L * 16L,
            Math.multiplyExact(tableSize.toLong(), (Long.SIZE_BYTES + Int.SIZE_BYTES).toLong())))
        val borrow = Math.addExact(128L + 3L * 16L, Math.multiplyExact(maximumTouches.toLong(), 24L))
        val routeDelta = Math.addExact(88L, Math.multiplyExact(maximumTouches.toLong(), 20L))
        return CanonicalFeaturePlanningMemoryReceipt(retained, scratch, borrow, routeDelta)
    }
    override fun close() { if (!closed) { closed = true; tokens.fill(0); rowProviders.fill(0); sourceProviders.fill(0) } }

    companion object {
        fun build(base: CompactCanonicalStore, commit: CanonicalPublishedCommit?, capacity: Int): CanonicalFeaturePlanningRoutes? {
            val routes = CanonicalFeaturePlanningRoutes(capacity)
            var id = 1L
            while (id < base.cut.nextSurfaceIdHighWater) {
                val row = base.findById(SurfaceId(id++)) ?: continue
                val source = (base.readSourceById(row.id) as? CanonicalPageRead.Complete)?.value ?: run {
                    routes.close(); return null
                }
                val descriptor = routes.acquire() ?: run { routes.close(); return null }
                routes.rowProviders[descriptor] = FeatureRouteProviderToken.BASE
                routes.sourceProviders[descriptor] = FeatureRouteProviderToken.BASE
                routes.put(packVisibilityGridKey(row.voxel.x, row.voxel.y, row.voxel.z), descriptor)
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
) {
    val memoryReceipt = CanonicalFeatureRouteDeltaMemoryReceipt(
        removedKeys.size,
        dirtyKeys.size,
        Math.addExact(
            88L,
            Math.addExact(
                Math.multiplyExact(removedKeys.size.toLong(), 8L),
                Math.multiplyExact(dirtyKeys.size.toLong(), 12L),
            ),
        ),
    )
}

internal data class CanonicalFeatureRouteDeltaMemoryReceipt(
    val removedRoutes: Int,
    val dirtyRoutes: Int,
    val retainedBytes: Long,
)

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

private data class RoutedProviderRead<T>(val value: T, val work: CanonicalReadWork)

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
}

internal data class CanonicalRendererPage(
    val cut: CompactCanonicalCut,
    val rows: List<CommittedGeometryRow>,
    val nextCursor: Long?,
)
