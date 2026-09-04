package com.uhg0.ar_flutter_plugin_2.featurefusion

data class VoxelObservation(
    val x: Int,
    val y: Int,
    val z: Int,
    val signedWeight: Int,
    val supportId: Int = 0,
)

data class VoxelKey(val x: Int, val y: Int, val z: Int) : Comparable<VoxelKey> {
    override fun compareTo(other: VoxelKey): Int =
        compareValuesBy(this, other, VoxelKey::x, VoxelKey::y, VoxelKey::z)
}

data class CanonicalSurface(
    val surfaceId: Long,
    val key: VoxelKey,
    val weight: Int,
    val normalOctant: Int,
    val extentU: Int = 1,
    val extentV: Int = 1,
    val planeAxis: Int = 3,
    val lineageIds: List<Int> = emptyList(),
    val observationCount: Int = 0,
)

data class FusionResult(
    val surfaces: List<CanonicalSurface>,
    val overflowObservationCount: Int,
)

class StableSurfaceIdAllocator {
    private val ids = mutableMapOf<VoxelKey, Long>()
    private var next = 1L

    fun idFor(key: VoxelKey): Long = ids.getOrPut(key) { next++ }
}

interface FusionKernel {
    val candidateId: String
    fun fuse(observations: Iterable<VoxelObservation>): FusionResult
    fun persistentSession(
        surfaceCapacity: Int,
        associationCapacity: Int,
        maxLineageIds: Int,
    ): PersistentFusionSession
}

private const val DEFAULT_MAX_OBSERVATIONS = 200_000
private const val DEFAULT_MAX_LINEAGE_IDS = 16

/** Candidate A: fixed signed occupancy and deterministic thresholding. */
open class SignedOccupancyKernel(
    private val capacity: Int = 100_000,
    private val occupancyThreshold: Int = 2,
    private val saturation: Int = 127,
    private val hysteresis: Int = 1,
    private val maxObservations: Int = DEFAULT_MAX_OBSERVATIONS,
    protected val maxLineageIds: Int = DEFAULT_MAX_LINEAGE_IDS,
) : FusionKernel {
    protected val ids = StableSurfaceIdAllocator()
    private val activeKeys = mutableSetOf<VoxelKey>()
    init {
        require(
            capacity > 0 && occupancyThreshold > 0 && saturation > occupancyThreshold &&
                hysteresis in 0 until occupancyThreshold && maxObservations > 0 &&
                maxLineageIds > 0,
        )
    }

    override val candidateId: String = "A"

    override fun persistentSession(
        surfaceCapacity: Int,
        associationCapacity: Int,
        maxLineageIds: Int,
    ): PersistentFusionSession = SignedPersistentSession(
        candidateId = candidateId,
        surfaceCapacity = surfaceCapacity,
        associationCapacity = associationCapacity,
        maximumLineageIds = maxLineageIds,
        occupancyThreshold = occupancyThreshold,
        saturation = saturation,
    )

    override fun fuse(observations: Iterable<VoxelObservation>): FusionResult {
        val weights = sortedMapOf<VoxelKey, Int>()
        val lineage = sortedMapOf<VoxelKey, MutableSet<Int>>()
        val observationCounts = sortedMapOf<VoxelKey, Int>()
        var overflow = 0
        var acceptedObservations = 0
        for (observation in observations) {
            if (acceptedObservations == maxObservations) {
                overflow++
                break
            }
            acceptedObservations++
            val key = VoxelKey(observation.x, observation.y, observation.z)
            val previous = weights[key] ?: 0
            val next = previous + observation.signedWeight
            if (next > saturation || next < -saturation) overflow++
            weights[key] = next.coerceIn(-saturation, saturation)
            if (addBoundedLineage(lineage, key, observation.supportId, maxLineageIds)) {
                overflow++
            }
            observationCounts[key] = (observationCounts[key] ?: 0) + 1
        }
        val visible = buildList {
            val deactivationThreshold = occupancyThreshold - hysteresis
            for ((key, weight) in weights) {
                val threshold = if (activeKeys.contains(key)) deactivationThreshold else occupancyThreshold
                if (weight >= threshold) {
                    if (size == capacity) {
                        overflow++
                        continue
                    }
                    add(key to weight)
                    activeKeys += key
                } else {
                    activeKeys -= key
                }
            }
        }
        return FusionResult(
            surfaces = visible.map { (key, weight) ->
                CanonicalSurface(
                    surfaceId = ids.idFor(key),
                    key = key,
                    weight = weight,
                    normalOctant = normalOctant(key),
                    lineageIds = lineage.getValue(key).sorted(),
                    observationCount = observationCounts.getValue(key),
                )
            },
            overflowObservationCount = overflow,
        )
    }
}

class PlanarConsolidationKernel(
    capacity: Int = 100_000,
    occupancyThreshold: Int = 2,
    saturation: Int = 127,
    hysteresis: Int = 1,
    maxObservations: Int = DEFAULT_MAX_OBSERVATIONS,
    maxLineageIds: Int = DEFAULT_MAX_LINEAGE_IDS,
) : SignedOccupancyKernel(
    capacity,
    occupancyThreshold,
    saturation,
    hysteresis,
    maxObservations,
    maxLineageIds,
) {
    override val candidateId: String = "B"

    override fun persistentSession(
        surfaceCapacity: Int,
        associationCapacity: Int,
        maxLineageIds: Int,
    ): PersistentFusionSession = PlanarPersistentSession(
        candidateId = candidateId,
        surfaceCapacity = surfaceCapacity,
        associationCapacity = associationCapacity,
        maximumLineageIds = maxLineageIds,
    )

    override fun fuse(observations: Iterable<VoxelObservation>): FusionResult {
        val base = super.fuse(observations)
        val byKey = base.surfaces.associateBy { it.key }
        val remaining = byKey.keys.toMutableSet()
        val consolidated = mutableListOf<CanonicalSurface>()
        var mergeOverflow = 0
        for (anchor in byKey.keys.sorted()) {
            if (!remaining.contains(anchor)) continue
            val patch = findPatch(anchor, byKey, remaining)
            if (patch == null) {
                consolidated += byKey.getValue(anchor)
                remaining -= anchor
                continue
            }
            remaining.removeAll(patch.keys.toSet())
            val members = patch.keys.map { byKey.getValue(it) }
            val mergedLineage = mergeBoundedLineage(members, maxLineageIds)
            mergeOverflow += mergedLineage.overflowCount
            consolidated += CanonicalSurface(
                surfaceId = ids.idFor(anchor),
                key = anchor,
                weight = members.minOf { it.weight },
                normalOctant = members.first().normalOctant,
                extentU = 2,
                extentV = 2,
                planeAxis = patch.planeAxis,
                lineageIds = mergedLineage.ids,
                observationCount = members.sumOf { it.observationCount },
            )
        }
        return FusionResult(
            surfaces = consolidated.sortedBy { it.key },
            overflowObservationCount = base.overflowObservationCount + mergeOverflow,
        )
    }
}

class BoundedTsdfKernel(
    private val capacity: Int = 100_000,
    private val narrowBand: Int = 4,
    private val maxObservations: Int = DEFAULT_MAX_OBSERVATIONS,
    private val maxLineageIds: Int = DEFAULT_MAX_LINEAGE_IDS,
) : FusionKernel {
    private val ids = StableSurfaceIdAllocator()
    init {
        require(capacity > 0 && narrowBand > 0 && maxObservations > 0 && maxLineageIds > 0)
    }

    override val candidateId: String = "C"

    override fun persistentSession(
        surfaceCapacity: Int,
        associationCapacity: Int,
        maxLineageIds: Int,
    ): PersistentFusionSession = TsdfPersistentSession(
        candidateId = candidateId,
        surfaceCapacity = surfaceCapacity,
        associationCapacity = associationCapacity,
        maximumLineageIds = maxLineageIds,
        narrowBand = narrowBand,
    )

    override fun fuse(observations: Iterable<VoxelObservation>): FusionResult {
        val sums = sortedMapOf<VoxelKey, Int>()
        val counts = sortedMapOf<VoxelKey, Int>()
        val lineage = sortedMapOf<VoxelKey, MutableSet<Int>>()
        var overflow = 0
        var acceptedObservations = 0
        for (observation in observations) {
            if (acceptedObservations == maxObservations) {
                overflow++
                break
            }
            acceptedObservations++
            val key = VoxelKey(observation.x, observation.y, observation.z)
            if (observation.signedWeight > narrowBand || observation.signedWeight < -narrowBand) overflow++
            sums[key] = (sums[key] ?: 0) + observation.signedWeight
            counts[key] = (counts[key] ?: 0) + 1
            if (addBoundedLineage(lineage, key, observation.supportId, maxLineageIds)) {
                overflow++
            }
        }
        val signedDistance = sums.mapValues { (key, sum) ->
            roundTiesEven(sum.toLong(), counts.getValue(key).toLong())
                .toInt()
                .coerceIn(-narrowBand, narrowBand)
        }
        val visible = signedDistance.entries
            .filter { it.value > 0 && hasNonPositiveNeighbor(it.key, signedDistance) }
            .take(capacity)
        return FusionResult(
            surfaces = visible.mapIndexed { index, entry ->
                CanonicalSurface(
                    surfaceId = ids.idFor(entry.key),
                    key = entry.key,
                    weight = entry.value,
                    normalOctant = normalOctant(entry.key),
                    lineageIds = lineage.getValue(entry.key).sorted(),
                    observationCount = counts.getValue(entry.key),
                )
            },
            overflowObservationCount = overflow +
                (signedDistance.size - capacity).coerceAtLeast(0),
        )
    }
}

private fun addBoundedLineage(
    lineage: MutableMap<VoxelKey, MutableSet<Int>>,
    key: VoxelKey,
    supportId: Int,
    maximum: Int,
): Boolean {
    val supportIds = lineage.getOrPut(key) { mutableSetOf() }
    if (!supportIds.add(supportId) || supportIds.size <= maximum) return false
    supportIds.remove(supportIds.maxOrNull())
    return true
}

private data class BoundedLineage(val ids: List<Int>, val overflowCount: Int)

private fun mergeBoundedLineage(
    surfaces: Iterable<CanonicalSurface>,
    maximum: Int,
): BoundedLineage {
    val ids = surfaces.flatMap { it.lineageIds }.distinct().sorted()
    val overflowCount = (ids.size - maximum).coerceAtLeast(0)
    return BoundedLineage(ids.take(maximum), overflowCount)
}

private data class PlanarPatch(val keys: List<VoxelKey>, val planeAxis: Int)

private fun findPatch(
    anchor: VoxelKey,
    surfaces: Map<VoxelKey, CanonicalSurface>,
    remaining: Set<VoxelKey>,
): PlanarPatch? {
    val candidates = listOf(
        PlanarPatch(
            listOf(
                anchor,
                VoxelKey(anchor.x + 1, anchor.y, anchor.z),
                VoxelKey(anchor.x, anchor.y + 1, anchor.z),
                VoxelKey(anchor.x + 1, anchor.y + 1, anchor.z),
            ),
            2,
        ),
        PlanarPatch(
            listOf(
                anchor,
                VoxelKey(anchor.x + 1, anchor.y, anchor.z),
                VoxelKey(anchor.x, anchor.y, anchor.z + 1),
                VoxelKey(anchor.x + 1, anchor.y, anchor.z + 1),
            ),
            1,
        ),
        PlanarPatch(
            listOf(
                anchor,
                VoxelKey(anchor.x, anchor.y + 1, anchor.z),
                VoxelKey(anchor.x, anchor.y + 1, anchor.z + 1),
                VoxelKey(anchor.x, anchor.y, anchor.z + 1),
            ),
            0,
        ),
    )
    return candidates.firstOrNull { candidate ->
        candidate.keys.all { remaining.contains(it) && surfaces.getValue(it).weight > 0 }
    }
}

private fun hasNonPositiveNeighbor(key: VoxelKey, values: Map<VoxelKey, Int>): Boolean {
    val directions = listOf(
        VoxelKey(1, 0, 0), VoxelKey(-1, 0, 0),
        VoxelKey(0, 1, 0), VoxelKey(0, -1, 0),
        VoxelKey(0, 0, 1), VoxelKey(0, 0, -1),
    )
    return directions.any { direction ->
        (values[VoxelKey(key.x + direction.x, key.y + direction.y, key.z + direction.z)] ?: 0) <= 0
    }
}

private fun normalOctant(key: VoxelKey): Int =
    ((key.x.compareTo(0) shl 2) or (key.y.compareTo(0) shl 1) or key.z.compareTo(0)) and 7

private fun roundTiesEven(numerator: Long, denominator: Long): Long {
    require(denominator > 0)
    val negative = numerator < 0
    val absolute = kotlin.math.abs(numerator)
    var quotient = absolute / denominator
    val remainder = absolute % denominator
    if (2 * remainder > denominator ||
        (2 * remainder == denominator && quotient % 2L != 0L)
    ) {
        quotient++
    }
    return if (negative) -quotient else quotient
}
