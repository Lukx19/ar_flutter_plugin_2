package com.uhg0.ar_flutter_plugin_2.visibilitygrid

import kotlin.math.floor

/** The single checked, deterministic 3-D supercover traversal authority. */
internal object DepthRaySupercover {
    const val EMPTY_BLOCK_VOXELS = 4
    fun visit(
        camera: DepthPointMm,
        endpoint: DepthPointMm,
        voxelSizeMicrometres: Int,
        maximumVisits: Int,
        visitor: (Voxel) -> Boolean,
    ): DepthRayVisitResult = visitCoordinates(
        camera, endpoint, voxelSizeMicrometres, maximumVisits,
    ) { x, y, z -> visitor(Voxel(x, y, z)) }

    /** Scalar traversal used by the fusion workspace; it creates no cell objects. */
    internal fun visitCoordinates(
        camera: DepthPointMm,
        endpoint: DepthPointMm,
        voxelSizeMicrometres: Int,
        maximumVisits: Int,
        emptyBlock: ((Int, Int, Int, Int) -> Boolean)? = null,
        visitor: (Int, Int, Int) -> Boolean,
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
        var emptyBlocks = 0
        var skippedBlock = false
        var skippedMinX = 0
        var skippedMinY = 0
        var skippedMinZ = 0
        var skippedSize = 0
        var checkedBlockX = Int.MIN_VALUE
        var checkedBlockY = Int.MIN_VALUE
        var checkedBlockZ = Int.MIN_VALUE
        var checkedSize = 0
        var checkedEmpty = false
        fun result(truncated: Boolean = false, arithmeticOverflow: Boolean = false) =
            DepthRayVisitResult(visited, truncated, arithmeticOverflow, emptyBlocks)
        fun checkBlock(x: Int, y: Int, z: Int): Int {
            if (emptyBlock == null) return CONTINUE
            if (checkedSize > 0 && x in checkedBlockX until checkedBlockX + checkedSize &&
                y in checkedBlockY until checkedBlockY + checkedSize &&
                z in checkedBlockZ until checkedBlockZ + checkedSize
            ) return CONTINUE
            var size = EMPTY_BLOCK_VOXELS
            while (true) {
                val bx = Math.floorDiv(x, size)
                val by = Math.floorDiv(y, size)
                val bz = Math.floorDiv(z, size)
                val empty = emptyBlock(bx, by, bz, size)
                if (empty || size == 1) {
                    checkedBlockX = bx * size; checkedBlockY = by * size; checkedBlockZ = bz * size
                    checkedSize = size
                    checkedEmpty = empty
                    if (empty) {
                        if (visited + emptyBlocks >= maximumVisits) return CAPACITY_STOP
                        emptyBlocks++
                    }
                    return CONTINUE
                }
                size = size shr 1
            }
        }
        fun emit(x: Int, y: Int, z: Int): Int {
            if (skippedBlock && x in skippedMinX until skippedMinX + skippedSize &&
                y in skippedMinY until skippedMinY + skippedSize &&
                z in skippedMinZ until skippedMinZ + skippedSize
            ) return CONTINUE
            if (checkBlock(x, y, z) == CAPACITY_STOP) return CAPACITY_STOP
            if (emptyBlock != null && checkedEmpty) return CONTINUE
            if (visited + emptyBlocks >= maximumVisits) return CAPACITY_STOP
            visited = Math.addExact(visited, 1)
            return if (visitor(x, y, z)) CONTINUE else VISITOR_STOP
        }
        try {
            when (emit(start.x, start.y, start.z)) {
                CAPACITY_STOP -> return result(truncated = true)
                VISITOR_STOP -> return result()
            }
            while (currentX != targetX || currentY != targetY || currentZ != targetZ) {
                if (emptyBlock != null) {
                    if (checkBlock(currentX, currentY, currentZ) == CAPACITY_STOP) return result(truncated = true)
                    if (checkedEmpty) {
                        skippedBlock = true
                        skippedMinX = checkedBlockX
                        skippedMinY = checkedBlockY
                        skippedMinZ = checkedBlockZ
                        skippedSize = checkedSize
                        val exit = minOf(
                            blockExit(currentX, targetX, stepX, tMaxX, tDeltaX, skippedMinX, skippedSize),
                            blockExit(currentY, targetY, stepY, tMaxY, tDeltaY, skippedMinY, skippedSize),
                            blockExit(currentZ, targetZ, stepZ, tMaxZ, tDeltaZ, skippedMinZ, skippedSize),
                        )
                        // Repeat each axis's original additions, rather than multiplying
                        // tDelta: exact floating-point ties and boundary subset order survive.
                        while (currentX != targetX && tMaxX < exit) { currentX += stepX; tMaxX += tDeltaX }
                        while (currentY != targetY && tMaxY < exit) { currentY += stepY; tMaxY += tDeltaY }
                        while (currentZ != targetZ && tMaxZ < exit) { currentZ += stepZ; tMaxZ += tDeltaZ }
                        if (currentX == targetX && currentY == targetY && currentZ == targetZ) break
                    }
                }
                var crossing = Double.POSITIVE_INFINITY
                if (currentX != targetX && tMaxX < crossing) crossing = tMaxX
                if (currentY != targetY && tMaxY < crossing) crossing = tMaxY
                if (currentZ != targetZ && tMaxZ < crossing) crossing = tMaxZ
                if (!crossing.isFinite()) return result(arithmeticOverflow = true)
                var tiedMask = 0
                if (currentX != targetX && tMaxX == crossing) tiedMask = tiedMask or 1
                if (currentY != targetY && tMaxY == crossing) tiedMask = tiedMask or 2
                if (currentZ != targetZ && tMaxZ == crossing) tiedMask = tiedMask or 4
                for (subset in 1..7) {
                    if (subset and tiedMask != subset) continue
                    val nextX = if (subset and 1 != 0) Math.addExact(currentX, stepX) else currentX
                    val nextY = if (subset and 2 != 0) Math.addExact(currentY, stepY) else currentY
                    val nextZ = if (subset and 4 != 0) Math.addExact(currentZ, stepZ) else currentZ
                    if (!DepthVoxelAddressing.contains(nextX, nextY, nextZ)) {
                        return result(arithmeticOverflow = true)
                    }
                    when (emit(nextX, nextY, nextZ)) {
                        CAPACITY_STOP -> return result(truncated = true)
                        VISITOR_STOP -> return result()
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
            return result(arithmeticOverflow = true)
        }
        return result()
    }

    private fun blockExit(current: Int, target: Int, step: Int, first: Double, delta: Double, minimum: Int, size: Int): Double {
        var coordinate = current
        var crossing = first
        while (coordinate != target) {
            val next = coordinate + step
            if (next < minimum || next >= minimum + size) return crossing
            coordinate = next
            crossing += delta
        }
        return Double.POSITIVE_INFINITY
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
        contains(voxel.x, voxel.y, voxel.z)

    fun contains(x: Int, y: Int, z: Int): Boolean =
        x in VOXEL_COORDINATE_MIN..VOXEL_COORDINATE_MAX &&
            y in VOXEL_COORDINATE_MIN..VOXEL_COORDINATE_MAX &&
            z in VOXEL_COORDINATE_MIN..VOXEL_COORDINATE_MAX
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

    override fun findSurfaceAtInto(x: Int, y: Int, z: Int, scratch: CanonicalSurfaceScratch): Boolean =
        delegate.findSurfaceAtInto(x, y, z, scratch)

    override fun findSurfaceByIdInto(id: Long, scratch: CanonicalSurfaceScratch): Boolean =
        delegate.findSurfaceByIdInto(id, scratch)

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

    override fun visitRayCellsInto(
        startGroupMm: DepthPointMm,
        endpointGroupMm: DepthPointMm,
        maximumVisits: Int,
        scratch: CanonicalSurfaceScratch,
        visitor: (Int, Int, Int, CanonicalSurfaceScratch) -> Boolean,
    ): DepthRayVisitResult = DepthRaySupercover.visitCoordinates(
        startGroupMm, endpointGroupMm, groupFrame.voxelSizeMicrometres, maximumVisits,
    ) { x, y, z ->
        if (!delegate.findSurfaceAtInto(x, y, z, scratch)) scratch.clear()
        visitor(x, y, z, scratch)
    }
}
