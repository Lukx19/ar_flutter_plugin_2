package com.uhg0.ar_flutter_plugin_2.visibilitygrid

import com.uhg0.ar_flutter_plugin_2.capture.StorageBudgetCoordinatorV2
import java.io.File

/**
 * The one group-owned runtime capability for durable M3 work.
 *
 * It normalizes the group-private directory once and owns the matching
 * [StorageBudgetCoordinatorV2] for its entire lifetime.  The legacy snapshot
 * exists only long enough to seed the first CREATE's v6 sibling; callers never
 * reopen it and every later mutation uses the selected v6 root.
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
        return M3SurfaceOwnership.open(
            group,
            directory,
            M3SurfaceOwnershipConfiguration(seededEmptyBaseline = baseline),
        ).also { opened ->
            if (opened is M3SurfaceOwnershipOpenResult.Opened) owner = opened.ownership
        }
    }

    /** Switches the initial durable CREATE to v6 before it is published. */
    fun activateInitialCreate(baseline: M3CommittedEmptyBaseline): M3SurfaceOwnershipOpenResult {
        checkOpen()
        val legacy = requireNotNull(owner)
        legacy.close()
        owner = null
        val configuration = M3SurfaceOwnershipConfiguration(seededEmptyBaseline = baseline)
        if (M3CompactCanonicalStore.prepareV6SiblingMigration(group, directory, budget, configuration)
            !is M3CompactCanonicalMigrationResult.Prepared
        ) return M3SurfaceOwnershipOpenResult.Refused(M3SurfaceOwnershipRestoreRefusal.DURABILITY_FAILURE)
        val plan = (M3CanonicalActivation.prepare(group, directory, budget, configuration)
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

    fun openCurrent(): M3CompactCanonicalOpenResult {
        checkOpen()
        return M3CompactCanonicalStore.openV6(group, directory, budget)
    }

    /** One bounded renderer page from the active v6 root for restart rebuild. */
    fun readRendererPage(cursor: Int, limit: Int = 512): M3CanonicalRendererPage? {
        checkOpen()
        val store = (openCurrent() as? M3CompactCanonicalOpenResult.Opened)?.store ?: return null
        return store.use {
            val page = it.readRendererPage(cursor, limit)
            M3CanonicalRendererPage(it.cut, page.rows.map(M3CompactSurface::voxel), page.nextCursor)
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
            // The existing coordinator constrains candidate trees to its root. M3's durable
            // file names are already group-hashed, so all groups share this one accounting root.
            return M3CanonicalRuntimeResources(group, sharedDirectory, groupDirectory, coordinator)
        }
    }
}

internal data class M3CanonicalRendererPage(
    val cut: M3CompactCanonicalCut,
    val voxels: List<M3Voxel>,
    val nextCursor: Int?,
)
