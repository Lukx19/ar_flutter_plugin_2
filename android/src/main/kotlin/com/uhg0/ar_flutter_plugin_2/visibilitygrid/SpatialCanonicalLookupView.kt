package com.uhg0.ar_flutter_plugin_2.visibilitygrid

/** Spatial filtering only narrows sensor lookups, never canonical support or lineage authority. */
internal class SpatialCanonicalLookupView(
    private val state: CanonicalStateView,
    private val cache: LiveSurfaceSpatialCache,
) : CanonicalStateView by state {
    override fun findByVoxelBounded(voxel: Voxel, maximumPageReads: Long, maximumBytesRead: Long): CanonicalBoundedReadResult<CompactSurface?> {
        if (cache.hasWindow && !cache.contains(voxel)) return CanonicalBoundedReadResult.Complete(null, CanonicalReadWork.ZERO)
        return state.findByVoxelBounded(voxel, maximumPageReads, maximumBytesRead)
    }

    override fun findByIdBounded(id: SurfaceId, maximumPageReads: Long, maximumBytesRead: Long): CanonicalBoundedReadResult<CompactSurface?> =
        when (val result = state.findByIdBounded(id, maximumPageReads, maximumBytesRead)) {
            is CanonicalBoundedReadResult.Complete -> if (result.value != null && cache.hasWindow && !cache.contains(result.value.voxel))
                CanonicalBoundedReadResult.Complete(null, result.work) else result
            is CanonicalBoundedReadResult.Refused -> result
        }

    // The delegate is borrowed, never owned by a sensor operation.
    override fun close() = Unit
}
