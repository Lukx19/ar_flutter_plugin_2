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
        val start = quantize(camera, voxelSizeMicrometres)
            ?: return DepthRayVisitResult(0, arithmeticOverflow = true)
        val end = quantize(endpoint, voxelSizeMicrometres)
            ?: return DepthRayVisitResult(0, arithmeticOverflow = true)
        val size = voxelSizeMicrometres.toDouble() / 1_000.0
        val delta = doubleArrayOf(endpoint.x - camera.x, endpoint.y - camera.y, endpoint.z - camera.z)
        if (!size.isFinite() || delta.any { !it.isFinite() }) {
            return DepthRayVisitResult(0, arithmeticOverflow = true)
        }
        val current = intArrayOf(start.x, start.y, start.z)
        val target = intArrayOf(end.x, end.y, end.z)
        val step = IntArray(3) { axis -> delta[axis].compareTo(0.0) }
        val startPoint = doubleArrayOf(camera.x, camera.y, camera.z)
        val tDelta = DoubleArray(3) { axis ->
            if (step[axis] == 0) Double.POSITIVE_INFINITY else size / kotlin.math.abs(delta[axis])
        }
        val tMax = DoubleArray(3) { axis ->
            if (step[axis] == 0) {
                Double.POSITIVE_INFINITY
            } else {
                val boundary = (current[axis] + if (step[axis] > 0) 1 else 0) * size
                (boundary - startPoint[axis]) / delta[axis]
            }
        }
        var visited = 0
        fun emit(voxel: Voxel): Boolean {
            if (visited >= maximumVisits) return false
            visited = Math.addExact(visited, 1)
            return visitor(voxel)
        }
        try {
            if (!emit(start)) return DepthRayVisitResult(visited)
            while (!current.contentEquals(target)) {
                val crossing = (0..2)
                    .asSequence()
                    .filter { current[it] != target[it] }
                    .minOfOrNull { tMax[it] }
                    ?: return DepthRayVisitResult(visited, arithmeticOverflow = true)
                if (!crossing.isFinite()) return DepthRayVisitResult(visited, arithmeticOverflow = true)
                var tiedMask = 0
                for (axis in 0..2) {
                    if (current[axis] != target[axis] && tMax[axis] == crossing) {
                        tiedMask = tiedMask or (1 shl axis)
                    }
                }
                for (subset in 1..7) {
                    if (subset and tiedMask != subset) continue
                    val next = current.copyOf()
                    for (axis in 0..2) if (subset and (1 shl axis) != 0) {
                        next[axis] = Math.addExact(next[axis], step[axis])
                    }
                    val voxel = Voxel(next[0], next[1], next[2])
                    if (!inRange(voxel)) return DepthRayVisitResult(visited, arithmeticOverflow = true)
                    if (!emit(voxel)) return DepthRayVisitResult(visited, truncated = true)
                }
                for (axis in 0..2) if (tiedMask and (1 shl axis) != 0) {
                    current[axis] = Math.addExact(current[axis], step[axis])
                    tMax[axis] += tDelta[axis]
                }
            }
        } catch (_: ArithmeticException) {
            return DepthRayVisitResult(visited, arithmeticOverflow = true)
        }
        return DepthRayVisitResult(visited)
    }

    private fun quantize(point: DepthPointMm, voxelSizeMicrometres: Int): Voxel? {
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

    private fun inRange(voxel: Voxel): Boolean =
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
