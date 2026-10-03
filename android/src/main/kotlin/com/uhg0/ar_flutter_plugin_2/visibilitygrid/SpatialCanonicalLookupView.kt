package com.uhg0.ar_flutter_plugin_2.visibilitygrid

/** Deliberately separate from CanonicalStateView: overlay delegation must not inherit a base certificate. */
internal interface CanonicalEmptyBlockView {
    fun isKnownEmptyBlock(blockX: Int, blockY: Int, blockZ: Int, geometryRevision: Long, lineageRevision: Long, blockVoxels: Int): Boolean
}

/** Spatial filtering only narrows sensor lookups, never canonical support or lineage authority. */
internal class SpatialCanonicalLookupView(
    private val state: CanonicalStateView,
    private val cache: LiveSurfaceSpatialCache,
) : CanonicalStateView by state, CanonicalEmptyBlockView {
    override fun isKnownEmptyBlock(blockX: Int, blockY: Int, blockZ: Int, geometryRevision: Long, lineageRevision: Long, blockVoxels: Int): Boolean =
        state.cut.geometryRevision == geometryRevision && state.cut.lineageRevision == lineageRevision &&
            cache.isKnownEmptyBlock(blockX, blockY, blockZ, geometryRevision, lineageRevision, blockVoxels)

    override fun findByVoxelBounded(voxel: Voxel, maximumPageReads: Long, maximumBytesRead: Long): CanonicalBoundedReadResult<CompactSurface?> {
        if (cache.hasWindow && !cache.contains(voxel)) return CanonicalBoundedReadResult.Complete(null, CanonicalReadWork.ZERO)
        return state.findByVoxelBounded(voxel, maximumPageReads, maximumBytesRead)
    }

    override fun findByVoxelBoundedInto(
        x: Int,
        y: Int,
        z: Int,
        maximumPageReads: Long,
        maximumBytesRead: Long,
        scratch: CanonicalSurfaceScratch,
    ): CanonicalBoundedReadResult<Boolean> {
        if (cache.hasWindow && !cache.contains(x, y, z)) {
            scratch.clear()
            return CanonicalBoundedReadResult.Complete(false, CanonicalReadWork.ZERO)
        }
        return state.findByVoxelBoundedInto(x, y, z, maximumPageReads, maximumBytesRead, scratch)
    }

    /**
     * Identity reads are canonical validation, not sensor occupancy probes.
     *
     * The depth kernel first addresses a row through the spatially filtered
     * voxel path and then validates that row by id. Filtering this second read
     * by the current frustum can reject a valid addressed row at an expanded
     * frustum edge after a camera rotation, producing CANONICAL_LOOKUP_FAILED.
     */
    override fun findByIdBounded(id: SurfaceId, maximumPageReads: Long, maximumBytesRead: Long): CanonicalBoundedReadResult<CompactSurface?> =
        state.findByIdBounded(id, maximumPageReads, maximumBytesRead)

    // The delegate is borrowed, never owned by a sensor operation.
    override fun close() = Unit
}
