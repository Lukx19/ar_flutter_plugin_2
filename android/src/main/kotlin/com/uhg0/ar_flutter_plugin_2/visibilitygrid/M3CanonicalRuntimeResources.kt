package com.uhg0.ar_flutter_plugin_2.visibilitygrid

import com.uhg0.ar_flutter_plugin_2.capture.StorageBudgetCoordinatorV2
import java.io.File

/**
 * The one group-owned runtime capability for durable M3 work.
 *
 * It normalizes the group-private directory once and owns the matching
 * [StorageBudgetCoordinatorV2] for its entire lifetime. The acknowledged M1
 * baseline is created directly as an empty budget-owned v6 root; this runtime
 * has no writable legacy fallback.
 */
internal class M3CanonicalRuntimeResources private constructor(
    val group: M3SurfaceGroup,
    val directory: File,
    val groupDirectory: File,
    coordinator: StorageBudgetCoordinatorV2,
) : AutoCloseable {
    private val budget = M3CoordinatorStorageBudget(coordinator)
    private var owner: M3SurfaceOwnership? = null
    private var current: CurrentLease? = null
    private var closed = false

    fun openInitial(baseline: M3CommittedEmptyBaseline): M3SurfaceOwnershipOpenResult {
        checkOpen()
        check(owner == null)
        check(!M3CanonicalActivationSelector.hasDurableSelector(group, directory))
        val configuration = M3SurfaceOwnershipConfiguration(seededEmptyBaseline = baseline)
        if (M3CompactCanonicalStore.prepareEmptyV6Bootstrap(
                group, directory, budget, baseline, configuration,
            )
            !is M3CompactCanonicalMigrationResult.Prepared
        ) return M3SurfaceOwnershipOpenResult.Refused(M3SurfaceOwnershipRestoreRefusal.DURABILITY_FAILURE)
        val plan = (M3CanonicalActivation.prepareEmptyV6(
            group, directory, budget, baseline, configuration,
        )
            as? M3CanonicalActivationPreparation.Prepared)?.plan
            ?: return M3SurfaceOwnershipOpenResult.Refused(M3SurfaceOwnershipRestoreRefusal.DURABILITY_FAILURE)
        return M3SurfaceOwnership.open(group, directory, budget, plan, configuration).also { opened ->
            if (opened is M3SurfaceOwnershipOpenResult.Opened) owner = opened.ownership
        }
    }

    /** Opens only the selected v6 authority; an absent selector is never a legacy fallback. */
    fun reopen(): M3SurfaceOwnershipOpenResult {
        checkOpen()
        check(owner == null)
        if (!M3CanonicalActivationSelector.hasDurableSelector(group, directory)) {
            return M3SurfaceOwnershipOpenResult.Refused(M3SurfaceOwnershipRestoreRefusal.CORRUPT)
        }
        return M3SurfaceOwnership.open(group, directory, budget).also { opened ->
            if (opened is M3SurfaceOwnershipOpenResult.Opened) owner = opened.ownership
        }
    }

    fun owner(): M3SurfaceOwnership = requireNotNull(owner) { "M3 runtime authority is unavailable" }

    private fun openGenerationZero(): M3CompactCanonicalOpenResult {
        checkOpen()
        return M3CompactCanonicalStore.openV6(group, directory, budget)
    }

    /** Borrows exactly the selector-named v6 cut for one bounded operation. */
    @Synchronized fun <T> withCurrent(block: (M3CanonicalStateView) -> T): T? {
        checkOpen()
        val lease = current ?: coldCurrent()?.also { current = it } ?: return null
        return authenticatedBorrow(lease, lease.view, block)
    }

    /** Dirty-only view: exact current scalars plus kernel-owned row correlation. */
    @Synchronized fun <T> withCorrelatedCurrent(
        changes: List<FeatureFusionChange>,
        block: (M3CanonicalStateView) -> T,
    ): T? {
        checkOpen()
        val lease = current ?: coldCurrent()?.also { current = it } ?: return null
        val correlations = HashMap<M3Voxel, CanonicalFeatureCorrelation>()
        changes.forEach { change ->
            val upsert = change as? FeatureFusionChange.Upsert ?: return@forEach
            upsert.canonicalCorrelation?.let { correlations[M3Voxel(upsert.x, upsert.y, upsert.z)] = it }
        }
        val view = M3CorrelatedCanonicalStateView(lease.view, correlations)
        return authenticatedBorrow(lease, view, block)
    }

    private fun <T> authenticatedBorrow(
        lease: CurrentLease,
        view: M3CanonicalStateView,
        block: (M3CanonicalStateView) -> T,
    ): T? {
        val authenticated = M3CanonicalActivationSelector.reopenAuthenticatedCurrent(
            group, directory, budget, view.cut,
        ) as? M3CanonicalActivationResult.Active ?: run { invalidateCurrent(); return null }
        if (authenticated.state.cut != view.cut) { invalidateCurrent(); return null }
        val authenticatedCurrentBytes = when (val state = authenticated.state.currentState) {
            is M3CanonicalCurrentState.Unacknowledged -> state.identity.canonicalLength
            else -> 0L
        }
        M3CanonicalRuntimeCurrentTestHooks.onAuthenticatedBorrow?.invoke(authenticatedCurrentBytes)
        val result = block(view)
        if (result is M3CanonicalMutationPreparation.Prepared && lease.commit != null) {
            check(M3CanonicalAuthorityLeaseRegistry.attachPublished(
                result.mutation.authorityLease, requireNotNull(lease.commit),
            ))
        }
        return result
    }

    /** Commits and atomically installs the transferred adjacent composed view. */
    @Synchronized fun commitAdjacent(
        plan: M3PreparedCanonicalMutation,
        faults: M3CanonicalCommitFaults = M3CanonicalCommitFaults(),
    ): M3CanonicalAdjacentCommitResult {
        checkOpen()
        val result = try {
            owner().commitAdjacentCanonicalMutation(plan, faults)
        } catch (failure: Throwable) {
            invalidateCurrent()
            throw failure
        }
        if (result is M3CanonicalAdjacentCommitResult.Committed) {
            val successor = result.successor ?: run { invalidateCurrent(); return result }
            val lease = current ?: run { successor.close(); return result }
            val scalar = successor.view.scalarView(directory)
            lease.commit?.close()
            lease.commit = successor.detachScalar(scalar)
            lease.view = scalar
        }
        return result
    }

    @Synchronized internal fun retainedCurrentProofBytes(): Long = current?.commit?.retainedProofBytes() ?: 0L
    @Synchronized internal fun retainedScalarMemoryReceipt(): M3ScalarCanonicalMemoryReceipt? =
        current?.view?.scalarMemoryReceipt

    internal fun portableOwnerBytes(): Long =
        40L + // M3CanonicalRuntimeResources
            24L + // CurrentLease
            104L + 56L + // M3SurfaceOwnership + configuration
            40L + 120L + 32L + 32L + // published/current activation scalars
            16L + 16L + // coordinator budget adapter + surface group
            2L * 32L + // normalized directory File owners
            group.value.encodeToByteArray().size +
            directory.path.encodeToByteArray().size + groupDirectory.path.encodeToByteArray().size

    internal fun portableCoordinatorOwnerBytes(): Long =
        56L + 48L + 32L + 32L + // coordinator, descriptor filesystem, safe filesystem, policy
            8L * 16L + // bounded atomic/counter owners
            2L * 32L // reservations/reclaims directory owners

    private fun coldCurrent(): CurrentLease? {
        val base = (openGenerationZero() as? M3CompactCanonicalOpenResult.Opened)?.store ?: return null
        val store = M3CanonicalCommitStore.open(directory, budget) ?: run { base.close(); return null }
        val retained = retainedCurrentReceipt()
        return try {
            when (val selected = store.reopen(base, retained)) {
                is M3CanonicalReopenResult.GenerationZero -> CurrentLease(base.scalarView(directory), null)
                is M3CanonicalReopenResult.Selected -> {
                    val scalar = selected.commit.view.scalarView(directory)
                    CurrentLease(scalar, selected.commit.detachScalar(scalar))
                }
                is M3CanonicalReopenResult.Refused -> null
            }
        } finally { store.close(); base.close() }
    }

    /** One named cold recovery scope; the complete view is closed before return. */
    @Synchronized fun rebuildAndHydrate(
        kernel: FeatureFusionKernel,
        rendererLimit: Int,
        sink: (M3CanonicalRendererPage) -> Unit,
    ): M3CompactCanonicalCut? {
        checkOpen()
        require(rendererLimit >= 0)
        val base = (openGenerationZero() as? M3CompactCanonicalOpenResult.Opened)?.store ?: return null
        val store = M3CanonicalCommitStore.open(directory, budget) ?: run { base.close(); return null }
        try {
            val reopened = store.reopen(base, retainedCurrentReceipt())
            val commit = (reopened as? M3CanonicalReopenResult.Selected)?.commit
            val view = commit?.view ?: (reopened as? M3CanonicalReopenResult.GenerationZero)?.view ?: return null
            try {
                val expected = current?.view?.cut
                if (expected != null && expected != view.cut) return null
                val installScalar = current == null
                var id = 1L
                var rendered = 0
                val page = ArrayList<M3Voxel>(512)
                while (id < view.cut.nextSurfaceIdHighWater) {
                    val row = view.findById(M3SurfaceId(id++)) ?: continue
                    val source = (view.readSourceById(row.id) as? M3CanonicalPageRead.Complete)?.value ?: return null
                    if (!kernel.hydrateCanonicalSurface(row, source.allocationFingerprint)) return null
                    if (rendered < rendererLimit) {
                        page += row.voxel
                        rendered++
                        if (page.size == 512) {
                            sink(M3CanonicalRendererPage(view.cut, page.toList(), null)); page.clear()
                        }
                    }
                }
                if (page.isNotEmpty()) sink(M3CanonicalRendererPage(view.cut, page.toList(), null))
                if (installScalar) {
                    val scalar = view.scalarView(directory)
                    current = CurrentLease(scalar, commit?.detachScalar(scalar))
                }
                return view.cut
            } finally {
                if (commit != null && !commit.scalar) commit.close()
            }
        } finally { store.close(); base.close() }
    }

    private fun retainedCurrentReceipt(): M3PreparedIntentCurrentReceipt? = owner?.activationState()?.currentState.let { state ->
        val identity = when (state) {
            is M3CanonicalCurrentState.Unacknowledged -> state.identity
            is M3CanonicalCurrentState.Acknowledged -> state.identity
            else -> null
        }
        identity?.let { M3PreparedIntentCurrentReceipt(it.canonicalLength, it.canonicalHash) }
    }

    private fun invalidateCurrent() { current?.close(); current = null }

    /** One bounded renderer page from the active v6 root for restart rebuild. */
    fun readRendererPage(cursor: Long, limit: Int = 512): M3CanonicalRendererPage? {
        checkOpen()
        val lease = current ?: coldCurrent()?.also { current = it } ?: return null
        val base = (openGenerationZero() as? M3CompactCanonicalOpenResult.Opened)?.store ?: return null
        val store = M3CanonicalCommitStore.open(directory, budget) ?: run { base.close(); return null }
        return try {
            val reopened = store.reopen(base, retainedCurrentReceipt())
            val view = (reopened as? M3CanonicalReopenResult.Selected)?.commit?.view
                ?: (reopened as? M3CanonicalReopenResult.GenerationZero)?.view ?: return null
            try {
            if (view.cut != lease.view.cut) return null
            require(cursor in 0 until view.cut.nextSurfaceIdHighWater && limit in 1..512)
            val rows = ArrayList<M3Voxel>(limit)
            var id = cursor + 1
            while (id < view.cut.nextSurfaceIdHighWater && rows.size < limit) {
                view.findById(M3SurfaceId(id))?.let { rows += it.voxel }
                id++
            }
            M3CanonicalRendererPage(
                view.cut,
                rows,
                id.takeIf { it < view.cut.nextSurfaceIdHighWater }?.minus(1),
            )
            } finally { (reopened as? M3CanonicalReopenResult.Selected)?.commit?.close() }
        } finally { store.close(); base.close() }
    }

    /** Test/diagnostic recovery projection in one cold verified scope. */
    internal fun readAllRendererKeys(): LongArray? {
        checkOpen()
        val lease = current ?: coldCurrent()?.also { current = it } ?: return null
        val base = (openGenerationZero() as? M3CompactCanonicalOpenResult.Opened)?.store ?: return null
        val store = M3CanonicalCommitStore.open(directory, budget) ?: run { base.close(); return null }
        return try {
            val reopened = store.reopen(base, retainedCurrentReceipt())
            val commit = (reopened as? M3CanonicalReopenResult.Selected)?.commit
            val view = commit?.view ?: (reopened as? M3CanonicalReopenResult.GenerationZero)?.view ?: return null
            try {
                if (view.cut != lease.view.cut) return null
                val keys = LongArray(view.cut.liveSurfaceCount)
                var size = 0
                var id = 1L
                while (id < view.cut.nextSurfaceIdHighWater) {
                    view.findById(M3SurfaceId(id++))?.let { row ->
                        keys[size++] = packVisibilityGridKey(row.voxel.x, row.voxel.y, row.voxel.z)
                    }
                }
                if (size == keys.size) keys else keys.copyOf(size)
            } finally { commit?.close() }
        } finally { store.close(); base.close() }
    }

    override fun close() {
        if (closed) return
        closed = true
        try { owner?.close() } finally { invalidateCurrent() }
        owner = null
    }

    private fun checkOpen() = check(!closed) { "M3 runtime resources are closed" }

    companion object {
        private const val RUNTIME_DIRECTORY = "visibility-grid-m3-runtime"

        fun open(
            root: File,
            group: M3SurfaceGroup,
            coordinator: StorageBudgetCoordinatorV2,
        ): M3CanonicalRuntimeResources {
            val normalizedRoot = root.absoluteFile.toPath().normalize().toFile()
            val groupName = group.value.lowercase()
            require(groupName.matches(Regex("[0-9a-f]{32}"))) { "M3 group directory must be canonical" }
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
            return M3CanonicalRuntimeResources(group, groupDirectory, groupDirectory, coordinator)
        }
    }

    private class CurrentLease(
        var view: M3ScalarCanonicalStateView,
        var commit: M3CanonicalPublishedCommit?,
    ) : AutoCloseable {
        override fun close() {
            commit?.close(); commit = null
        }
    }
}

private open class M3ScalarCanonicalStateView(
    override val authorityParentKey: String,
    override val cut: M3CompactCanonicalCut,
    private val storage: M3CompactStorageReceipt,
) : M3ScalarCanonicalAuthority {
    val scalarMemoryReceipt = M3ScalarCanonicalMemoryReceipt.measure(authorityParentKey, cut)
    override fun findById(id: M3SurfaceId): M3CompactSurface? = null
    override fun findByVoxel(voxel: M3Voxel): M3CompactSurface? = null
    override fun readPage(region: M3StorageRegion, page: Int, cursor: Int, limit: Int) = M3CompactPage(emptyList(), null, 0)
    override fun readSourceById(id: M3SurfaceId): M3CanonicalPageRead<M3PagedSource?> = M3CanonicalPageRead.Complete(null, 0, 0)
    override fun visitSourceSupport(target: M3SurfaceId, cursor: M3SourceSupportCursor?, sink: (M3PagedSupport) -> Boolean) =
        M3SourceSupportRead.Complete(0, null, 0, 0)
    override fun retainedMemoryReceipt() = M3CompactRetainedMemoryReceipt(
        0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
        scalarMemoryReceipt.portableBytes, 0,
    )
    override fun allocatedStorageReceipt() = storage
    override fun close() = Unit
}

private class M3CorrelatedCanonicalStateView(
    private val scalar: M3ScalarCanonicalStateView,
    private val correlations: Map<M3Voxel, CanonicalFeatureCorrelation>,
) : M3ScalarCanonicalAuthority by scalar {
    override fun findById(id: M3SurfaceId): M3CompactSurface? = correlations.entries.firstOrNull { it.value.id == id }
        ?.let { (voxel, correlation) -> M3CompactSurface(correlation.id, voxel, correlation.packedNormal, correlation.normalConfidence) }
    override fun findByVoxel(voxel: M3Voxel): M3CompactSurface? = correlations[voxel]
        ?.let { M3CompactSurface(it.id, voxel, it.packedNormal, it.normalConfidence) }
    override fun readSourceById(id: M3SurfaceId): M3CanonicalPageRead<M3PagedSource?> {
        val entry = correlations.entries.firstOrNull { it.value.id == id }
            ?: return M3CanonicalPageRead.Complete(null, 0, 0)
        val correlation = entry.value
        return M3CanonicalPageRead.Complete(
            M3PagedSource(
                correlation.id, entry.key, correlation.packedNormal,
                correlation.normalConfidence, correlation.allocationFingerprint,
            ), 0, 0,
        )
    }
}

private fun M3CanonicalStateView.scalarView(directory: File) = M3ScalarCanonicalStateView(
    directory.absoluteFile.toPath().normalize().toString(), cut, allocatedStorageReceipt(),
)

/** Exact portable layout of the strongly-reachable steady scalar authority. */
internal data class M3ScalarCanonicalMemoryReceipt(
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
        fun measure(parent: String, cut: M3CompactCanonicalCut): M3ScalarCanonicalMemoryReceipt {
            val baseline = cut.seededEmptyBaseline
            val baselineBytes = if (baseline == null) 0L else
                baseline.bindingIdentity.encodeToByteArray().size.toLong() +
                    baseline.groupIdentity.encodeToByteArray().size.toLong() + 3L * Long.SIZE_BYTES
            return M3ScalarCanonicalMemoryReceipt(
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
internal object M3CanonicalRuntimeCurrentTestHooks {
    @Volatile var onAuthenticatedBorrow: ((Long) -> Unit)? = null
}

internal data class M3CanonicalRendererPage(
    val cut: M3CompactCanonicalCut,
    val voxels: List<M3Voxel>,
    val nextCursor: Long?,
)
