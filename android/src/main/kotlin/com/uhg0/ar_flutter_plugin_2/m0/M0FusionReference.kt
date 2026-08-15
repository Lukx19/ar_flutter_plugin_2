package com.uhg0.ar_flutter_plugin_2.m0

data class M0VoxelObservation(
    val x: Int,
    val y: Int,
    val z: Int,
    val signedWeight: Int,
    val supportId: Int = 0,
)

data class M0VoxelKey(val x: Int, val y: Int, val z: Int) : Comparable<M0VoxelKey> {
    override fun compareTo(other: M0VoxelKey): Int =
        compareValuesBy(this, other, M0VoxelKey::x, M0VoxelKey::y, M0VoxelKey::z)
}

data class M0CanonicalSurface(
    val surfaceId: Long,
    val key: M0VoxelKey,
    val weight: Int,
    val normalOctant: Int,
)

data class M0FusionResult(
    val surfaces: List<M0CanonicalSurface>,
    val overflowObservationCount: Int,
)

interface M0FusionKernel {
    val candidateId: String
    fun fuse(observations: Iterable<M0VoxelObservation>): M0FusionResult
}

/** Candidate A: fixed signed occupancy and deterministic thresholding. */
open class M0SignedOccupancyKernel(
    private val capacity: Int = 100_000,
    private val occupancyThreshold: Int = 2,
    private val saturation: Int = 127,
) : M0FusionKernel {
    init {
        require(capacity > 0 && occupancyThreshold > 0 && saturation > occupancyThreshold)
    }

    override val candidateId: String = "A"

    override fun fuse(observations: Iterable<M0VoxelObservation>): M0FusionResult {
        val weights = sortedMapOf<M0VoxelKey, Int>()
        var overflow = 0
        observations.forEach { observation ->
            val key = M0VoxelKey(observation.x, observation.y, observation.z)
            val previous = weights[key] ?: 0
            val next = previous + observation.signedWeight
            if (next > saturation || next < -saturation) overflow++
            weights[key] = next.coerceIn(-saturation, saturation)
        }
        val visible = weights.entries
            .filter { it.value >= occupancyThreshold }
            .take(capacity)
        return M0FusionResult(
            surfaces = visible.mapIndexed { index, entry ->
                M0CanonicalSurface(index + 1L, entry.key, entry.value, normalOctant(entry.key))
            },
            overflowObservationCount = overflow + (weights.size - visible.size).coerceAtLeast(0),
        )
    }
}

class M0PlanarConsolidationKernel(
    capacity: Int = 100_000,
    occupancyThreshold: Int = 2,
    saturation: Int = 127,
) : M0SignedOccupancyKernel(capacity, occupancyThreshold, saturation) {
    override val candidateId: String = "B"

    override fun fuse(observations: Iterable<M0VoxelObservation>): M0FusionResult {
        val base = super.fuse(observations)
        return M0FusionResult(
            // M0's conservative reference must not collapse distinct z layers;
            // the locked corpus decides whether a richer planar merge is safe.
            surfaces = base.surfaces.mapIndexed { index, surface ->
                surface.copy(surfaceId = index + 1L)
            },
            overflowObservationCount = base.overflowObservationCount,
        )
    }
}

class M0BoundedTsdfKernel(
    private val capacity: Int = 100_000,
    private val narrowBand: Int = 4,
) : M0FusionKernel {
    init {
        require(capacity > 0 && narrowBand > 0)
    }

    override val candidateId: String = "C"

    override fun fuse(observations: Iterable<M0VoxelObservation>): M0FusionResult {
        val signedDistance = sortedMapOf<M0VoxelKey, Int>()
        var overflow = 0
        observations.forEach { observation ->
            val key = M0VoxelKey(observation.x, observation.y, observation.z)
            val previous = signedDistance[key] ?: 0
            val next = previous + observation.signedWeight
            if (next > narrowBand || next < -narrowBand) overflow++
            signedDistance[key] = next.coerceIn(-narrowBand, narrowBand)
        }
        val visible = signedDistance.entries.filter { it.value > 0 }.take(capacity)
        return M0FusionResult(
            surfaces = visible.mapIndexed { index, entry ->
                M0CanonicalSurface(index + 1L, entry.key, entry.value, normalOctant(entry.key))
            },
            overflowObservationCount = overflow + (signedDistance.size - visible.size).coerceAtLeast(0),
        )
    }
}

private fun normalOctant(key: M0VoxelKey): Int =
    ((key.x.compareTo(0) shl 2) or (key.y.compareTo(0) shl 1) or key.z.compareTo(0)) and 7
