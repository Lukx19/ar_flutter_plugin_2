package com.uhg0.ar_flutter_plugin_2.m0

/** Creates a production candidate kernel with explicit testable bounds. */
fun interface M0bKernelFactory {
    fun create(capacity: Int, maxObservations: Int, maxLineageIds: Int): M0FusionKernel
}

/**
 * Fixed-capacity sink for canonical surfaces and their admitted associations.
 * Candidate semantics never live here; only production-kernel outputs are
 * copied into these primitive lanes.
 */
class M0bCompactFusionLedger(
    val surfaceCapacity: Int = MAX_SURFACES,
    val associationCapacity: Int = MAX_ASSOCIATIONS,
) {
    init {
        require(surfaceCapacity in 1..MAX_SURFACES) {
            "surfaceCapacity must be in 1..$MAX_SURFACES"
        }
        require(associationCapacity in 1..MAX_ASSOCIATIONS) {
            "associationCapacity must be in 1..$MAX_ASSOCIATIONS"
        }
    }

    private val surfaces = IntArray(Math.multiplyExact(surfaceCapacity, SURFACE_LANES))
    private val associations = IntArray(Math.multiplyExact(associationCapacity, ASSOCIATION_LANES))
    private val lineage = IntArray(Math.multiplyExact(surfaceCapacity, LINEAGE_LANES))

    val semanticBytes: Int = Math.addExact(
        Math.addExact(surfaces.size * Int.SIZE_BYTES, associations.size * Int.SIZE_BYTES),
        lineage.size * Int.SIZE_BYTES,
    )
    val serializedBytes: Int get() = semanticBytes

    fun recordSurface(index: Int, surface: M0CanonicalSurface) {
        require(index in 0 until surfaceCapacity)
        val base = index * SURFACE_LANES
        surfaces[base] = surface.key.x
        surfaces[base + 1] = surface.key.y
        surfaces[base + 2] = surface.key.z
        surfaces[base + 3] = surface.weight
        surfaces[base + 4] = surface.extentU
        surfaces[base + 5] = surface.extentV
        surfaces[base + 6] = surface.planeAxis
        surfaces[base + 7] = surface.normalOctant
        surfaces[base + 8] = surface.observationCount
        surfaces[base + 9] = surface.lineageIds.size
        val lineageBase = index * LINEAGE_LANES
        surface.lineageIds.take(LINEAGE_LANES).forEachIndexed { lane, id ->
            lineage[lineageBase + lane] = id
        }
    }

    fun recordAssociation(index: Int, observation: M0VoxelObservation) {
        require(index in 0 until associationCapacity)
        val base = index * ASSOCIATION_LANES
        associations[base] = observation.x
        associations[base + 1] = observation.y
        associations[base + 2] = observation.z
        associations[base + 3] = observation.signedWeight
        associations[base + 4] = observation.supportId
        associations[base + 5] = index
    }

    companion object {
        const val MAX_SURFACES = 100_000
        const val MAX_ASSOCIATIONS = 200_000
        private const val SURFACE_LANES = 16
        private const val ASSOCIATION_LANES = 6
        private const val LINEAGE_LANES = 4
    }
}

/** Runs every declared boundary item through one production candidate kernel. */
class M0bKernelBoundaryAdapter(
    private val candidateId: String,
    private val factory: M0bKernelFactory,
    private val allocatedBytes: () -> Long,
    private val cpuNanos: () -> Long,
) {
    fun executeBoundary(): M0bBoundaryResult {
        val ledger = M0bCompactFusionLedger()
        var associationIndex = 0
        var outputIndex = 0
        var invocationCount = 0
        var logicalSurfaceCount = 0
        var outputSurfaceCount = 0
        var checksum = 17L
        var peakWorkingAllocationBytes = 0L
        var peakKernelCpuMicros = 0L
        var overflow = 0
        for (start in 0 until M0bCompactFusionLedger.MAX_SURFACES step BATCH_SURFACES) {
            val count = minOf(BATCH_SURFACES, M0bCompactFusionLedger.MAX_SURFACES - start)
            val observations = ArrayList<M0VoxelObservation>(count * 2)
            repeat(count) { offset ->
                val key = keyFor(start + offset)
                repeat(2) { duplicate ->
                    observations += M0VoxelObservation(
                        key.x,
                        key.y,
                        key.z,
                        signedWeight = 1,
                        supportId = associationIndex + duplicate,
                    )
                }
                associationIndex += 2
            }
            val before = allocatedBytes()
            val cpuBefore = cpuNanos()
            val result = factory.create(count, observations.size, LINEAGE_LIMIT).fuse(observations)
            peakKernelCpuMicros = maxOf(
                peakKernelCpuMicros,
                ((cpuNanos() - cpuBefore) / 1_000).coerceAtLeast(1L),
            )
            val batchAllocation = allocatedBytes() - before
            peakWorkingAllocationBytes = maxOf(peakWorkingAllocationBytes, batchAllocation)
            invocationCount++
            logicalSurfaceCount += count
            overflow += result.overflowObservationCount
            val represented = result.surfaces.sumOf { it.extentU * it.extentV }
            check(represented == count) { "$candidateId represented $represented of $count surfaces" }
            observations.forEachIndexed { index, observation ->
                ledger.recordAssociation(associationIndex - observations.size + index, observation)
            }
            result.surfaces.forEach { surface ->
                ledger.recordSurface(outputIndex++, surface)
                outputSurfaceCount++
                checksum = checksum * 31 + canonicalChecksum(surface)
            }
        }
        check(associationIndex == M0bCompactFusionLedger.MAX_ASSOCIATIONS)
        check(logicalSurfaceCount == M0bCompactFusionLedger.MAX_SURFACES)
        val capacityOverflow = capacityOverflow()
        val lineageOverflow = lineageOverflow()
        val checkedOverflow = checkedOverflowFailures()
        return M0bBoundaryResult(
            candidateId = candidateId,
            invocationCount = invocationCount,
            surfaceCount = logicalSurfaceCount,
            outputSurfaceCount = outputSurfaceCount,
            associationCount = associationIndex,
            semanticBytes = ledger.semanticBytes,
            peakAllocationBytes = ledger.semanticBytes + peakWorkingAllocationBytes,
            peakKernelCpuMicros = peakKernelCpuMicros,
            fusionChecksum = checksum,
            checkedOverflowFailures = checkedOverflow,
            capacityOverflowCount = capacityOverflow,
            lineageOverflowCount = lineageOverflow,
        )
    }

    fun executeReplay(): M0bReplayBoundaryResult {
        var checksum = 23L
        var invocationCount = 0
        var associationCount = 0
        repeat(REPLAY_PICTURES) { picture ->
            val observations = List(REPLAY_SURFACES) { offset ->
                val key = keyFor(offset)
                M0VoxelObservation(
                    key.x,
                    key.y,
                    key.z,
                    signedWeight = if (candidateId == "C") 1 else 2,
                    supportId = picture * REPLAY_SURFACES + offset,
                )
            }
            val result = factory.create(REPLAY_SURFACES, observations.size, LINEAGE_LIMIT)
                .fuse(observations)
            invocationCount++
            associationCount += observations.size
            result.surfaces.forEach { surface ->
                checksum = checksum * 31 + canonicalChecksum(surface) + picture
            }
        }
        check(invocationCount == REPLAY_PICTURES)
        check(associationCount == REPLAY_PICTURES * REPLAY_SURFACES)
        return M0bReplayBoundaryResult(invocationCount, associationCount, checksum)
    }

    private fun capacityOverflow(): Int {
        val observations = listOf(
            M0VoxelObservation(0, 0, 0, 2, 1),
            M0VoxelObservation(10, 0, 0, 2, 2),
        )
        return factory.create(1, observations.size, LINEAGE_LIMIT).fuse(observations)
            .overflowObservationCount
    }

    private fun lineageOverflow(): Int {
        val observations = List(LINEAGE_LIMIT + 1) { support ->
            M0VoxelObservation(0, 0, 0, 1, support)
        }
        return factory.create(1, observations.size, LINEAGE_LIMIT).fuse(observations)
            .overflowObservationCount
    }

    private fun checkedOverflowFailures(): Int {
        val result = factory.create(1, 1, LINEAGE_LIMIT).fuse(
            listOf(M0VoxelObservation(0, 0, 0, Int.MAX_VALUE, 1)),
        )
        return if (result.overflowObservationCount == 1) 0 else 1
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
        private const val BATCH_SURFACES = 256
        private const val LINEAGE_LIMIT = 4
        private const val REPLAY_PICTURES = 300
        private const val REPLAY_SURFACES = 200
    }
}

data class M0bBoundaryResult(
    val candidateId: String,
    val invocationCount: Int,
    val surfaceCount: Int,
    val outputSurfaceCount: Int,
    val associationCount: Int,
    val semanticBytes: Int,
    val peakAllocationBytes: Long,
    val peakKernelCpuMicros: Long,
    val fusionChecksum: Long,
    val checkedOverflowFailures: Int,
    val capacityOverflowCount: Int,
    val lineageOverflowCount: Int,
)

data class M0bReplayBoundaryResult(
    val invocationCount: Int,
    val associationCount: Int,
    val checksum: Long,
)
