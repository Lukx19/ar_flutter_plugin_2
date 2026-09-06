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
        return block(lease.completeView)
    }

    /** Dirty-only view: exact current scalars plus kernel-owned row correlation. */
    @Synchronized fun <T> withCorrelatedCurrent(
        changes: List<FeatureFusionChange>,
        block: (CanonicalStateView) -> T,
    ): T? {
        checkOpen()
        val lease = current ?: return null
        val correlations = HashMap<Voxel, CanonicalFeatureCorrelation>()
        changes.forEach { change ->
            val upsert = change as? FeatureFusionChange.Upsert ?: return@forEach
            upsert.canonicalCorrelation?.let { correlations[Voxel(upsert.x, upsert.y, upsert.z)] = it }
        }
        val view = CorrelatedCanonicalStateView(lease.scalarView, correlations)
        return authenticatedBorrow(lease, view, block)
    }

    private fun <T> authenticatedBorrow(
        lease: CurrentLease,
        view: CanonicalStateView,
        block: (CanonicalStateView) -> T,
    ): T? {
        if (!lease.isCurrent(owner?.activationState()?.cut) || view.cut != lease.scalarView.cut) {
            invalidateCurrent(); return null
        }
        val result = block(view)
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
        val result = try {
            owner().commitAdjacentCanonicalMutation(plan, faults)
        } catch (failure: Throwable) {
            invalidateCurrent()
            throw failure
        }
        if (result is CanonicalAdjacentCommitResult.Committed) {
            val successor = result.successor ?: run { invalidateCurrent(); return result }
            val lease = current ?: run { successor.close(); return result }
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
        current?.completeView?.retainedMemoryReceipt()?.let {
            CanonicalCompleteCurrentLeaseReceipt(it, it.peakWithScratchBytes)
        }

    internal fun portableOwnerBytes(): Long =
        40L + // CanonicalRuntimeResources
            48L + // CurrentLease with scalar and retained complete-current capabilities
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
                    CurrentLease(base.scalarView(directory), selected.view, base, null)
                is CanonicalReopenResult.Selected -> {
                    val scalar = selected.commit.view.scalarView(directory)
                    CurrentLease(scalar, selected.commit.view, base, selected.commit)
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

    private class CurrentLease(
        var scalarView: ScalarCanonicalStateView,
        var completeView: CanonicalStateView,
        private val base: CompactCanonicalStore,
        var commit: CanonicalPublishedCommit?,
    ) : AutoCloseable {
        fun isCurrent(cut: CompactCanonicalCut?): Boolean =
            cut != null && cut == scalarView.cut && cut == completeView.cut
        override fun close() {
            commit?.close(); commit = null; base.close()
        }
    }
}

internal data class CanonicalCompleteCurrentLeaseReceipt(
    val retained: CompactRetainedMemoryReceipt,
    /** Lifecycle-only cold materialization peak; never charged to an ordinary bounded request. */
    val lifecycleOpenPeakBytes: Long,
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

private class CorrelatedCanonicalStateView(
    private val scalar: ScalarCanonicalStateView,
    private val correlations: Map<Voxel, CanonicalFeatureCorrelation>,
) : ScalarCanonicalAuthority by scalar {
    override fun findById(id: SurfaceId): CompactSurface? = correlations.entries.firstOrNull { it.value.id == id }
        ?.let { (voxel, correlation) -> CompactSurface(correlation.id, voxel, correlation.packedNormal, correlation.normalConfidence) }
    override fun findByVoxel(voxel: Voxel): CompactSurface? = correlations[voxel]
        ?.let { CompactSurface(it.id, voxel, it.packedNormal, it.normalConfidence) }
    override fun readSourceById(id: SurfaceId): CanonicalPageRead<PagedSource?> {
        val entry = correlations.entries.firstOrNull { it.value.id == id }
            ?: return CanonicalPageRead.Complete(null, 0, 0)
        val correlation = entry.value
        return CanonicalPageRead.Complete(
            PagedSource(
                correlation.id, entry.key, correlation.packedNormal,
                correlation.normalConfidence, correlation.allocationFingerprint,
            ), 0, 0,
        )
    }
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
}

internal data class CanonicalRendererPage(
    val cut: CompactCanonicalCut,
    val rows: List<CommittedGeometryRow>,
    val nextCursor: Long?,
)
