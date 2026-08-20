package com.uhg0.ar_flutter_plugin_2.m0

/** Persistent bounded state owned by one production fusion-kernel instance. */
interface M0bPersistentFusionSession {
    val candidateId: String
    val surfaceCount: Int
    val associationCount: Int
    val semanticBytes: Int
    val overflowCount: Int
    fun admit(observations: Iterable<M0VoxelObservation>)
    fun forEachCanonical(consumer: (M0CanonicalSurface) -> Unit)
    fun replayPicture(pictureId: Int, affectedSurfaceCount: Int): Long
}

abstract class M0bCompactPersistentSession(
    final override val candidateId: String,
    private val surfaceCapacity: Int,
    private val associationCapacity: Int,
    private val maximumLineageIds: Int,
) : M0bPersistentFusionSession {
    init {
        require(surfaceCapacity in 1..MAX_SURFACES)
        require(associationCapacity in 1..MAX_ASSOCIATIONS)
        require(maximumLineageIds in 1..LINEAGE_LANES)
    }

    protected val surfaces = IntArray(surfaceCapacity * SURFACE_LANES)
    private val associations = IntArray(associationCapacity * ASSOCIATION_LANES)
    protected val lineage = IntArray(surfaceCapacity * LINEAGE_LANES)
    private val hashSlots = IntArray(HASH_SLOTS)
    private val padding = IntArray(PADDING_INTS)
    final override var surfaceCount: Int = 0
        private set
    final override var associationCount: Int = 0
        private set
    final override var overflowCount: Int = 0
        protected set
    final override val semanticBytes: Int =
        (surfaces.size + associations.size + lineage.size + hashSlots.size + padding.size) * Int.SIZE_BYTES

    final override fun admit(observations: Iterable<M0VoxelObservation>) {
        for (observation in observations) {
            if (associationCount == associationCapacity) {
                overflowCount++
                continue
            }
            val slot = findOrInsert(observation)
            if (slot < 0) {
                overflowCount++
                continue
            }
            val associationBase = associationCount * ASSOCIATION_LANES
            associations[associationBase] = observation.x
            associations[associationBase + 1] = observation.y
            associations[associationBase + 2] = observation.z
            associations[associationBase + 3] = observation.signedWeight
            associations[associationBase + 4] = observation.supportId
            associations[associationBase + 5] = slot
            associationCount++
            admitAt(slot, observation)
            addLineage(slot, observation.supportId)
        }
    }

    protected abstract fun admitAt(slot: Int, observation: M0VoxelObservation)
    protected abstract fun isVisible(slot: Int): Boolean
    protected abstract fun weight(slot: Int): Int

    override fun forEachCanonical(consumer: (M0CanonicalSurface) -> Unit) {
        repeat(surfaceCount) { slot ->
            if (isVisible(slot)) consumer(canonical(slot))
        }
    }

    override fun replayPicture(pictureId: Int, affectedSurfaceCount: Int): Long {
        require(pictureId >= 0 && affectedSurfaceCount > 0)
        var seen = 0
        var checksum = 29L
        var slot = 0
        while (slot < surfaceCount && seen < affectedSurfaceCount) {
            if (isVisible(slot)) {
                val surface = canonical(slot)
                surfaces[slot * SURFACE_LANES + REPLAY_DEBT]++
                checksum = checksum * 31 + canonicalChecksum(surface) + pictureId
                seen++
            }
            slot++
        }
        check(seen == affectedSurfaceCount)
        return checksum
    }

    protected fun canonical(
        slot: Int,
        extentU: Int = 1,
        extentV: Int = 1,
        planeAxis: Int = 3,
        members: List<Int> = listOf(slot),
    ): M0CanonicalSurface {
        val base = slot * SURFACE_LANES
        val ids = members.flatMap { member ->
            val lineageBase = member * LINEAGE_LANES
            val count = surfaces[member * SURFACE_LANES + LINEAGE_COUNT]
            List(count) { lane -> lineage[lineageBase + lane] }
        }.distinct().sorted().take(maximumLineageIds)
        return M0CanonicalSurface(
            surfaceId = slot.toLong() + 1,
            key = M0VoxelKey(surfaces[base + X], surfaces[base + Y], surfaces[base + Z]),
            weight = members.minOf(::weight),
            normalOctant = surfaces[base + NORMAL],
            extentU = extentU,
            extentV = extentV,
            planeAxis = planeAxis,
            lineageIds = ids,
            observationCount = members.sumOf { surfaces[it * SURFACE_LANES + OBSERVATION_COUNT] },
        )
    }

    protected fun slotOf(key: M0VoxelKey): Int {
        var bucket = hash(key)
        repeat(HASH_SLOTS) {
            val encoded = hashSlots[bucket]
            if (encoded == 0) return -1
            val slot = encoded - 1
            val base = slot * SURFACE_LANES
            if (surfaces[base + X] == key.x && surfaces[base + Y] == key.y && surfaces[base + Z] == key.z) {
                return slot
            }
            bucket = (bucket + 1) and (HASH_SLOTS - 1)
        }
        return -1
    }

    private fun findOrInsert(observation: M0VoxelObservation): Int {
        val key = M0VoxelKey(observation.x, observation.y, observation.z)
        val existing = slotOf(key)
        if (existing >= 0) return existing
        if (surfaceCount == surfaceCapacity) return -1
        var bucket = hash(key)
        while (hashSlots[bucket] != 0) bucket = (bucket + 1) and (HASH_SLOTS - 1)
        val slot = surfaceCount++
        hashSlots[bucket] = slot + 1
        val base = slot * SURFACE_LANES
        surfaces[base + X] = observation.x
        surfaces[base + Y] = observation.y
        surfaces[base + Z] = observation.z
        surfaces[base + NORMAL] = normalOctant(key)
        return slot
    }

    private fun addLineage(slot: Int, supportId: Int) {
        val base = slot * SURFACE_LANES
        val count = surfaces[base + LINEAGE_COUNT]
        val lineageBase = slot * LINEAGE_LANES
        if ((0 until count).any { lineage[lineageBase + it] == supportId }) return
        if (count == maximumLineageIds) {
            overflowCount++
            return
        }
        lineage[lineageBase + count] = supportId
        surfaces[base + LINEAGE_COUNT] = count + 1
    }

    private fun hash(key: M0VoxelKey): Int {
        var value = key.x * 73856093 xor key.y * 19349663 xor key.z * 83492791
        value = value xor (value ushr 16)
        return value and (HASH_SLOTS - 1)
    }

    protected fun checkedAdd(left: Int, right: Int): Int = try {
        Math.addExact(left, right)
    } catch (_: ArithmeticException) {
        overflowCount++
        if (right >= 0) Int.MAX_VALUE else Int.MIN_VALUE
    }

    companion object {
        const val MAX_SURFACES = 100_000
        const val MAX_ASSOCIATIONS = 200_000
        protected const val SURFACE_LANES = 13
        private const val ASSOCIATION_LANES = 6
        protected const val LINEAGE_LANES = 4
        private const val HASH_SLOTS = 262_144
        private const val PADDING_INTS = 37_856
        protected const val X = 0
        protected const val Y = 1
        protected const val Z = 2
        protected const val ACCUMULATED = 3
        protected const val OBSERVATION_COUNT = 4
        protected const val NORMAL = 5
        protected const val LINEAGE_COUNT = 6
        protected const val ACTIVE = 7
        protected const val REPLAY_DEBT = 8
        protected const val VISITED = 9

        private fun normalOctant(key: M0VoxelKey): Int =
            ((key.x.compareTo(0) shl 2) or (key.y.compareTo(0) shl 1) or key.z.compareTo(0)) and 7

        private fun canonicalChecksum(surface: M0CanonicalSurface): Long =
            surface.key.x * 7L + surface.key.y * 11L + surface.key.z * 13L +
                surface.weight * 17L + surface.extentU * 19L + surface.extentV * 23L +
                surface.planeAxis * 29L + surface.normalOctant * 31L +
                surface.observationCount * 37L +
                surface.lineageIds.fold(0L) { sum, id -> sum * 41 + id }
    }
}

open class M0bSignedPersistentSession(
    candidateId: String,
    surfaceCapacity: Int,
    associationCapacity: Int,
    maximumLineageIds: Int,
    private val occupancyThreshold: Int,
    private val saturation: Int,
) : M0bCompactPersistentSession(candidateId, surfaceCapacity, associationCapacity, maximumLineageIds) {
    override fun admitAt(slot: Int, observation: M0VoxelObservation) {
        val base = slot * SURFACE_LANES
        val sum = checkedAdd(surfaces[base + ACCUMULATED], observation.signedWeight)
        if (sum > saturation || sum < -saturation) overflowCount++
        surfaces[base + ACCUMULATED] = sum.coerceIn(-saturation, saturation)
        surfaces[base + OBSERVATION_COUNT]++
        surfaces[base + ACTIVE] = if (surfaces[base + ACCUMULATED] >= occupancyThreshold) 1 else 0
    }

    override fun isVisible(slot: Int): Boolean = surfaces[slot * SURFACE_LANES + ACTIVE] == 1
    override fun weight(slot: Int): Int = surfaces[slot * SURFACE_LANES + ACCUMULATED]
}

class M0bPlanarPersistentSession(
    candidateId: String,
    surfaceCapacity: Int,
    associationCapacity: Int,
    maximumLineageIds: Int,
) : M0bSignedPersistentSession(
    candidateId,
    surfaceCapacity,
    associationCapacity,
    maximumLineageIds,
    occupancyThreshold = 2,
    saturation = 127,
) {
    override fun forEachCanonical(consumer: (M0CanonicalSurface) -> Unit) {
        repeat(surfaceCount) { surfaces[it * SURFACE_LANES + VISITED] = 0 }
        repeat(surfaceCount) { slot ->
            val base = slot * SURFACE_LANES
            if (!isVisible(slot) || surfaces[base + VISITED] == 1) return@repeat
            val key = M0VoxelKey(surfaces[base + X], surfaces[base + Y], surfaces[base + Z])
            val members = listOf(
                slot,
                slotOf(M0VoxelKey(key.x + 1, key.y, key.z)),
                slotOf(M0VoxelKey(key.x, key.y + 1, key.z)),
                slotOf(M0VoxelKey(key.x + 1, key.y + 1, key.z)),
            )
            if (members.all { it >= 0 && isVisible(it) && surfaces[it * SURFACE_LANES + VISITED] == 0 }) {
                members.forEach { surfaces[it * SURFACE_LANES + VISITED] = 1 }
                consumer(canonical(slot, extentU = 2, extentV = 2, planeAxis = 2, members = members))
            } else {
                surfaces[base + VISITED] = 1
                consumer(canonical(slot))
            }
        }
    }

    override fun replayPicture(pictureId: Int, affectedSurfaceCount: Int): Long {
        require(pictureId >= 0 && affectedSurfaceCount > 0)
        repeat(surfaceCount) { surfaces[it * SURFACE_LANES + VISITED] = 0 }
        var seen = 0
        var checksum = 29L
        var slot = 0
        while (slot < surfaceCount && seen < affectedSurfaceCount) {
            val base = slot * SURFACE_LANES
            if (isVisible(slot) && surfaces[base + VISITED] == 0) {
                val key = M0VoxelKey(surfaces[base + X], surfaces[base + Y], surfaces[base + Z])
                val members = listOf(
                    slot,
                    slotOf(M0VoxelKey(key.x + 1, key.y, key.z)),
                    slotOf(M0VoxelKey(key.x, key.y + 1, key.z)),
                    slotOf(M0VoxelKey(key.x + 1, key.y + 1, key.z)),
                )
                val surface = if (members.all {
                        it >= 0 && isVisible(it) && surfaces[it * SURFACE_LANES + VISITED] == 0
                    }
                ) {
                    members.forEach { surfaces[it * SURFACE_LANES + VISITED] = 1 }
                    canonical(slot, 2, 2, 2, members)
                } else {
                    surfaces[base + VISITED] = 1
                    canonical(slot)
                }
                surfaces[base + REPLAY_DEBT]++
                checksum = checksum * 31 + surface.surfaceId + pictureId
                seen++
            }
            slot++
        }
        check(seen == affectedSurfaceCount)
        return checksum
    }
}

class M0bTsdfPersistentSession(
    candidateId: String,
    surfaceCapacity: Int,
    associationCapacity: Int,
    maximumLineageIds: Int,
    private val narrowBand: Int,
) : M0bCompactPersistentSession(candidateId, surfaceCapacity, associationCapacity, maximumLineageIds) {
    override fun admitAt(slot: Int, observation: M0VoxelObservation) {
        val base = slot * SURFACE_LANES
        if (observation.signedWeight !in -narrowBand..narrowBand) overflowCount++
        surfaces[base + ACCUMULATED] = checkedAdd(
            surfaces[base + ACCUMULATED],
            observation.signedWeight,
        )
        surfaces[base + OBSERVATION_COUNT]++
    }

    override fun isVisible(slot: Int): Boolean = weight(slot) > 0
    override fun weight(slot: Int): Int {
        val base = slot * SURFACE_LANES
        val count = surfaces[base + OBSERVATION_COUNT]
        return if (count == 0) 0 else surfaces[base + ACCUMULATED] / count
    }
}

/** Result from one persistent kernel over the complete locked boundary. */
data class M0bPersistentCampaignResult(
    val candidateId: String,
    val kernelInstanceCount: Int,
    val admissionCallCount: Int,
    val replayCallCount: Int,
    val surfaceCount: Int,
    val outputSurfaceCount: Int,
    val associationCount: Int,
    val replayAssociationCount: Int,
    val semanticBytes: Int,
    val peakAllocationBytes: Long,
    val peakAdmissionCpuMicros: Long,
    val totalBoundaryCpuMicros: Long,
    val replayCpuMicros: Long,
    val fusionChecksum: Long,
    val replayChecksum: Long,
    val checkedOverflowFailures: Int,
    val capacityOverflowCount: Int,
    val lineageOverflowCount: Int,
)

/** Measures one production kernel instance retaining the full boundary state. */
class M0bPersistentKernelHarness(
    private val kernel: M0FusionKernel,
    private val allocatedBytes: () -> Long,
    private val cpuNanos: () -> Long,
) {
    fun execute(): M0bPersistentCampaignResult {
        val session = kernel.persistentSession(MAX_SURFACES, MAX_ASSOCIATIONS, LINEAGE_LIMIT)
        check(session.semanticBytes == 12_800_000)
        val boundaryCpuStart = cpuNanos()
        var peakAdmissionCpu = 0L
        var peakWorkingAllocation = 0L
        var admissionCalls = 0
        for (start in 0 until MAX_SURFACES step ADMISSION_SURFACES) {
            val count = minOf(ADMISSION_SURFACES, MAX_SURFACES - start)
            val observations = boundaryObservations(start, count)
            val allocatedBefore = allocatedBytes()
            val cpuBefore = cpuNanos()
            session.admit(observations)
            peakAdmissionCpu = maxOf(peakAdmissionCpu, (cpuNanos() - cpuBefore) / 1_000)
            peakWorkingAllocation = maxOf(peakWorkingAllocation, allocatedBytes() - allocatedBefore)
            admissionCalls++
        }
        val totalBoundaryCpu = (cpuNanos() - boundaryCpuStart) / 1_000
        check(session.surfaceCount == MAX_SURFACES)
        check(session.associationCount == MAX_ASSOCIATIONS)
        var outputCount = 0
        var fusionChecksum = 17L
        session.forEachCanonical { surface ->
            outputCount++
            fusionChecksum = fusionChecksum * 31 + canonicalChecksum(surface)
        }
        val replayCpuStart = cpuNanos()
        var replayChecksum = 23L
        repeat(REPLAY_PICTURES) { picture ->
            replayChecksum = replayChecksum * 31 + session.replayPicture(picture, REPLAY_SURFACES)
        }
        val replayCpu = (cpuNanos() - replayCpuStart) / 1_000
        return M0bPersistentCampaignResult(
            candidateId = kernel.candidateId,
            kernelInstanceCount = 1,
            admissionCallCount = admissionCalls,
            replayCallCount = REPLAY_PICTURES,
            surfaceCount = session.surfaceCount,
            outputSurfaceCount = outputCount,
            associationCount = session.associationCount,
            replayAssociationCount = REPLAY_PICTURES * REPLAY_SURFACES,
            semanticBytes = session.semanticBytes,
            peakAllocationBytes = session.semanticBytes + peakWorkingAllocation,
            peakAdmissionCpuMicros = peakAdmissionCpu.coerceAtLeast(1),
            totalBoundaryCpuMicros = totalBoundaryCpu.coerceAtLeast(1),
            replayCpuMicros = replayCpu.coerceAtLeast(1),
            fusionChecksum = fusionChecksum,
            replayChecksum = replayChecksum,
            checkedOverflowFailures = checkedOverflowFailures(),
            capacityOverflowCount = capacityOverflow(),
            lineageOverflowCount = lineageOverflow(),
        )
    }

    private fun boundaryObservations(start: Int, count: Int): Iterable<M0VoxelObservation> = Iterable {
        object : Iterator<M0VoxelObservation> {
            var index = 0
            override fun hasNext(): Boolean = index < count * 2
            override fun next(): M0VoxelObservation {
                val logical = start + index / 2
                val duplicate = index++ and 1
                val key = keyFor(logical)
                return M0VoxelObservation(key.x, key.y, key.z, 1, logical * 2 + duplicate)
            }
        }
    }

    private fun capacityOverflow(): Int {
        val session = kernel.persistentSession(1, 2, LINEAGE_LIMIT)
        session.admit(listOf(M0VoxelObservation(0, 0, 0, 2, 1), M0VoxelObservation(10, 0, 0, 2, 2)))
        return session.overflowCount
    }

    private fun lineageOverflow(): Int {
        val session = kernel.persistentSession(1, LINEAGE_LIMIT + 1, LINEAGE_LIMIT)
        session.admit(List(LINEAGE_LIMIT + 1) { M0VoxelObservation(0, 0, 0, 1, it) })
        return session.overflowCount
    }

    private fun checkedOverflowFailures(): Int {
        val session = kernel.persistentSession(1, 1, LINEAGE_LIMIT)
        session.admit(listOf(M0VoxelObservation(0, 0, 0, Int.MAX_VALUE, 1)))
        return if (session.overflowCount == 1) 0 else 1
    }

    private fun keyFor(index: Int): M0VoxelKey {
        val patch = index / 4
        return M0VoxelKey(patch * 2 + index % 2, (index % 4) / 2, 0)
    }

    private fun canonicalChecksum(surface: M0CanonicalSurface): Long =
        surface.key.x * 7L + surface.key.y * 11L + surface.key.z * 13L +
            surface.weight * 17L + surface.extentU * 19L + surface.extentV * 23L +
            surface.planeAxis * 29L + surface.normalOctant * 31L +
            surface.observationCount * 37L + surface.lineageIds.fold(0L) { sum, id -> sum * 41 + id }

    companion object {
        private const val MAX_SURFACES = 100_000
        private const val MAX_ASSOCIATIONS = 200_000
        private const val ADMISSION_SURFACES = 256
        private const val LINEAGE_LIMIT = 4
        private const val REPLAY_PICTURES = 300
        private const val REPLAY_SURFACES = 200
    }
}
