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
    fun <T> withCurrent(block: (M3CanonicalStateView) -> T): T? {
        checkOpen()
        val base = (openGenerationZero() as? M3CompactCanonicalOpenResult.Opened)?.store ?: return null
        val commitStore = M3CanonicalCommitStore.open(directory, budget) ?: run { base.close(); return null }
        val retained = owner?.activationState()?.currentState.let { current ->
            val identity = when (current) {
                is M3CanonicalCurrentState.Unacknowledged -> current.identity
                is M3CanonicalCurrentState.Acknowledged -> current.identity
                else -> null
            }
            identity?.let { M3PreparedIntentCurrentReceipt(it.canonicalLength, it.canonicalHash) }
        }
        return try {
            when (val selected = commitStore.reopen(base, retained)) {
                is M3CanonicalReopenResult.GenerationZero -> block(selected.view)
                is M3CanonicalReopenResult.Selected -> selected.commit.use { block(it.view) }
                is M3CanonicalReopenResult.Refused -> null
            }
        } finally {
            commitStore.close()
            base.close()
        }
    }

    /** One bounded renderer page from the active v6 root for restart rebuild. */
    fun readRendererPage(cursor: Long, limit: Int = 512): M3CanonicalRendererPage? {
        checkOpen()
        return withCurrent { store ->
            require(cursor in 0 until store.cut.nextSurfaceIdHighWater && limit in 1..512)
            val rows = ArrayList<M3Voxel>(limit)
            var id = cursor + 1
            while (id < store.cut.nextSurfaceIdHighWater && rows.size < limit) {
                store.findById(M3SurfaceId(id))?.let { rows += it.voxel }
                id++
            }
            M3CanonicalRendererPage(
                store.cut,
                rows,
                id.takeIf { it < store.cut.nextSurfaceIdHighWater }?.minus(1),
            )
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        owner?.close()
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
}

internal data class M3CanonicalRendererPage(
    val cut: M3CompactCanonicalCut,
    val voxels: List<M3Voxel>,
    val nextCursor: Long?,
)
