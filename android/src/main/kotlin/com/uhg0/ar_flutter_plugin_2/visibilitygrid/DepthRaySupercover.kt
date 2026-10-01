package com.uhg0.ar_flutter_plugin_2.visibilitygrid

import kotlin.math.floor

/** The single checked, deterministic 3-D supercover traversal authority. */
internal object DepthRaySupercover {
    fun visit(
        camera: DepthPointMm,
        endpoint: DepthPointMm,
        voxelSizeMicrometres: Int,
        maximumVisits: Int,
        visitor: (Voxel) -> Boolean,
    ): DepthRayVisitResult {
        if (maximumVisits !in 0..65_536 || voxelSizeMicrometres <= 0 ||
            !camera.isFinite() || !endpoint.isFinite()
        ) return DepthRayVisitResult(0, arithmeticOverflow = true)
        val start = DepthVoxelAddressing.quantize(camera, voxelSizeMicrometres)
            ?: return DepthRayVisitResult(0, arithmeticOverflow = true)
        val end = DepthVoxelAddressing.quantize(endpoint, voxelSizeMicrometres)
            ?: return DepthRayVisitResult(0, arithmeticOverflow = true)
        val size = voxelSizeMicrometres.toDouble() / 1_000.0
        val deltaX = endpoint.x - camera.x
        val deltaY = endpoint.y - camera.y
        val deltaZ = endpoint.z - camera.z
        if (!size.isFinite() || !deltaX.isFinite() || !deltaY.isFinite() || !deltaZ.isFinite()) {
            return DepthRayVisitResult(0, arithmeticOverflow = true)
        }
        // Keep the traversal state in scalars. This method is called once per
        // selected depth endpoint, so per-ray coordinate arrays become a
        // substantial allocation source on a populated 2,000 x 2,000 map.
        // The explicit axis order below preserves the previous array-based
        // implementation's tie breaking (x, then y, then z).
        var currentX = start.x
        var currentY = start.y
        var currentZ = start.z
        val targetX = end.x
        val targetY = end.y
        val targetZ = end.z
        val stepX = deltaX.compareTo(0.0)
        val stepY = deltaY.compareTo(0.0)
        val stepZ = deltaZ.compareTo(0.0)
        val tDeltaX = if (stepX == 0) Double.POSITIVE_INFINITY else size / kotlin.math.abs(deltaX)
        val tDeltaY = if (stepY == 0) Double.POSITIVE_INFINITY else size / kotlin.math.abs(deltaY)
        val tDeltaZ = if (stepZ == 0) Double.POSITIVE_INFINITY else size / kotlin.math.abs(deltaZ)
        var tMaxX = if (stepX == 0) Double.POSITIVE_INFINITY else {
            val boundary = (currentX + if (stepX > 0) 1 else 0) * size
            (boundary - camera.x) / deltaX
        }
        var tMaxY = if (stepY == 0) Double.POSITIVE_INFINITY else {
            val boundary = (currentY + if (stepY > 0) 1 else 0) * size
            (boundary - camera.y) / deltaY
        }
        var tMaxZ = if (stepZ == 0) Double.POSITIVE_INFINITY else {
            val boundary = (currentZ + if (stepZ > 0) 1 else 0) * size
            (boundary - camera.z) / deltaZ
        }
        var visited = 0
        fun emit(voxel: Voxel): Int {
            if (visited >= maximumVisits) return CAPACITY_STOP
            visited = Math.addExact(visited, 1)
            return if (visitor(voxel)) CONTINUE else VISITOR_STOP
        }
        try {
            when (emit(start)) {
                CAPACITY_STOP -> return DepthRayVisitResult(visited, truncated = true)
                VISITOR_STOP -> return DepthRayVisitResult(visited)
            }
            while (currentX != targetX || currentY != targetY || currentZ != targetZ) {
                var crossing = Double.POSITIVE_INFINITY
                if (currentX != targetX && tMaxX < crossing) crossing = tMaxX
                if (currentY != targetY && tMaxY < crossing) crossing = tMaxY
                if (currentZ != targetZ && tMaxZ < crossing) crossing = tMaxZ
                if (!crossing.isFinite()) return DepthRayVisitResult(visited, arithmeticOverflow = true)
                var tiedMask = 0
                if (currentX != targetX && tMaxX == crossing) tiedMask = tiedMask or 1
                if (currentY != targetY && tMaxY == crossing) tiedMask = tiedMask or 2
                if (currentZ != targetZ && tMaxZ == crossing) tiedMask = tiedMask or 4
                for (subset in 1..7) {
                    if (subset and tiedMask != subset) continue
                    val nextX = if (subset and 1 != 0) Math.addExact(currentX, stepX) else currentX
                    val nextY = if (subset and 2 != 0) Math.addExact(currentY, stepY) else currentY
                    val nextZ = if (subset and 4 != 0) Math.addExact(currentZ, stepZ) else currentZ
                    val voxel = Voxel(nextX, nextY, nextZ)
                    if (!DepthVoxelAddressing.contains(voxel)) {
                        return DepthRayVisitResult(visited, arithmeticOverflow = true)
                    }
                    when (emit(voxel)) {
                        CAPACITY_STOP -> return DepthRayVisitResult(visited, truncated = true)
                        VISITOR_STOP -> return DepthRayVisitResult(visited)
                    }
                }
                if (tiedMask and 1 != 0) {
                    currentX = Math.addExact(currentX, stepX)
                    tMaxX += tDeltaX
                }
                if (tiedMask and 2 != 0) {
                    currentY = Math.addExact(currentY, stepY)
                    tMaxY += tDeltaY
                }
                if (tiedMask and 4 != 0) {
                    currentZ = Math.addExact(currentZ, stepZ)
                    tMaxZ += tDeltaZ
                }
            }
        } catch (_: ArithmeticException) {
            return DepthRayVisitResult(visited, arithmeticOverflow = true)
        }
        return DepthRayVisitResult(visited)
    }

    private const val CONTINUE = 0
    private const val VISITOR_STOP = 1
    private const val CAPACITY_STOP = 2
}

/** Shared allocation-free validation for every depth voxel address. */
internal object DepthVoxelAddressing {
    fun quantize(point: DepthPointMm, voxelSizeMicrometres: Int): Voxel? {
        if (!point.isFinite() || voxelSizeMicrometres <= 0) return null
        fun coordinate(value: Double): Int? {
            val quantized = floor(value * 1_000.0 / voxelSizeMicrometres)
            return if (quantized.isFinite() &&
                quantized >= VOXEL_COORDINATE_MIN && quantized <= VOXEL_COORDINATE_MAX
            ) quantized.toInt() else null
        }
        return Voxel(
            coordinate(point.x) ?: return null,
            coordinate(point.y) ?: return null,
            coordinate(point.z) ?: return null,
        )
    }

    fun contains(voxel: Voxel): Boolean =
        voxel.x in VOXEL_COORDINATE_MIN..VOXEL_COORDINATE_MAX &&
            voxel.y in VOXEL_COORDINATE_MIN..VOXEL_COORDINATE_MAX &&
            voxel.z in VOXEL_COORDINATE_MIN..VOXEL_COORDINATE_MAX
}

/** Compatibility adapter for callers of the bounded canonical-view traversal seam. */
internal class CanonicalSurfaceRayViewAdapter(
    private val delegate: BoundedCanonicalSurfaceView,
    private val groupFrame: VisibilityGroupFrame,
) : BoundedCanonicalSurfaceView {
    override val revisionPair: CanonicalRevisionPair get() = delegate.revisionPair
    override val surfaceCount: Int get() = delegate.surfaceCount

    override fun findSurfaceById(id: SurfaceId): DepthCanonicalSurface? = delegate.findSurfaceById(id)

    override fun findSurfaceAt(voxel: Voxel): AddressedCanonicalSurface? = delegate.findSurfaceAt(voxel)

    override fun visitRayCells(
        startGroupMm: DepthPointMm,
        endpointGroupMm: DepthPointMm,
        maximumVisits: Int,
        visitor: (Voxel, DepthCanonicalSurface?) -> Boolean,
    ): DepthRayVisitResult = DepthRaySupercover.visit(
        startGroupMm, endpointGroupMm, groupFrame.voxelSizeMicrometres, maximumVisits,
    ) { voxel ->
        val addressed = delegate.findSurfaceAt(voxel)
        require(addressed == null || addressed.addressedVoxel == voxel) {
            "canonical lookup returned a different addressed voxel"
        }
        visitor(voxel, addressed?.surface)
    }
}
