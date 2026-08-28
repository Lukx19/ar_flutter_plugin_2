package com.uhg0.ar_flutter_plugin_2.visibilitygrid

import java.util.Collections
import kotlin.math.floor

/** Candidate-A feature fusion behind one state-owning admission interface. */
internal class M3FeatureFusionKernel(
    private val operations: M3KernelOperations = JvmM3KernelOperations,
) {
    /** Applies one immutable batch, or refuses it without changing retained state. */
    fun accept(batch: M3FeatureFusionBatch): M3FeatureFusionResult {
        val staged = try {
            when (val normalization = normalize(batch)) {
                is Normalization.Refused -> return refused(normalization.reason)
                is Normalization.Accepted -> stage(normalization.evidence, batch)
            }
        } catch (_: M3AllocationFailure) {
            return refused(M3FeatureFusionRefusal.ALLOCATION)
        } catch (_: OutOfMemoryError) {
            return refused(M3FeatureFusionRefusal.ALLOCATION)
        }
        if (staged is Staging.Refused) return refused(staged.reason)
        staged as Staging.Accepted

        // Every allocation, checked calculation, and result construction has
        // succeeded. The remainder writes only preallocated primitive storage.
        var index = 0
        while (index < staged.newSurfaces.size) {
            val surface = staged.newSurfaces[index++]
            insertAt(surface.slot, surface.key)
        }
        index = 0
        while (index < staged.associations.size) {
            val association = staged.associations[index++]
            associationSlots[associationCount] = association.slot
            evidenceWeights[associationCount] = association.weight
            evidenceSupportIds[associationCount] = association.supportId
            associationCount++
        }
        index = 0
        while (index < staged.updates.size) {
            val update = staged.updates[index++]
            accumulatedWeights[update.slot] = update.weight
            observationCounts[update.slot] = update.observationCount
            active[update.slot] = update.isActive
        }
        lastSequence = batch.sequence
        lastTimestampNs = batch.timestampNs
        return staged.result
    }

    private fun normalize(batch: M3FeatureFusionBatch): Normalization {
        if (batch.sequence <= lastSequence || batch.timestampNs <= lastTimestampNs || batch.timestampNs < 0L) {
            return Normalization.Refused(M3FeatureFusionRefusal.STALE_BATCH)
        }
        if (batch.observations.size > ASSOCIATION_CAPACITY) {
            return Normalization.Refused(M3FeatureFusionRefusal.ASSOCIATION_CAPACITY)
        }
        return operations.allocate(M3AllocationCut.NORMALIZATION) {
            val normalized = ArrayList<NormalizedEvidence>(batch.observations.size)
            batch.observations.forEach { evidence ->
                if (evidence.signedWeight !in -EVIDENCE_SATURATION..EVIDENCE_SATURATION) {
                    return@allocate Normalization.Refused(M3FeatureFusionRefusal.INVALID_EVIDENCE_WEIGHT)
                }
                val x = quantize(evidence.xMeters)
                    ?: return@allocate Normalization.Refused(quantizationRefusal(evidence.xMeters))
                val y = quantize(evidence.yMeters)
                    ?: return@allocate Normalization.Refused(quantizationRefusal(evidence.yMeters))
                val z = quantize(evidence.zMeters)
                    ?: return@allocate Normalization.Refused(quantizationRefusal(evidence.zMeters))
                normalized += NormalizedEvidence(VoxelKey(x, y, z), evidence.signedWeight, evidence.supportId)
            }
            normalized.sortWith(
                compareBy<NormalizedEvidence> { it.key.x }
                    .thenBy { it.key.y }
                    .thenBy { it.key.z }
                    .thenBy { it.supportId }
                    .thenBy { it.signedWeight },
            )
            Normalization.Accepted(normalized)
        }
    }

    private fun stage(evidence: List<NormalizedEvidence>, batch: M3FeatureFusionBatch): Staging =
        operations.allocate(M3AllocationCut.PREFLIGHT) {
            val nextAssociations = addOrRefuse(associationCount, evidence.size)
                ?: return@allocate Staging.Refused(M3FeatureFusionRefusal.CHECKED_ARITHMETIC)
            if (nextAssociations > ASSOCIATION_CAPACITY) {
                return@allocate Staging.Refused(M3FeatureFusionRefusal.ASSOCIATION_CAPACITY)
            }

            val updatesByKey = LinkedHashMap<VoxelKey, ProjectedSurface>()
            val associations = ArrayList<ProjectedAssociation>(evidence.size)
            var projectedSurfaceCount = surfaceCount
            evidence.forEach { item ->
                var projected = updatesByKey[item.key]
                if (projected == null) {
                    val existing = findSlot(item.key)
                    if (existing >= 0) {
                        projected = ProjectedSurface(existing, item.key, accumulatedWeights[existing], observationCounts[existing], active[existing], false)
                    } else {
                        val next = addOrRefuse(projectedSurfaceCount, 1)
                            ?: return@allocate Staging.Refused(M3FeatureFusionRefusal.CHECKED_ARITHMETIC)
                        if (next > SURFACE_CAPACITY) {
                            return@allocate Staging.Refused(M3FeatureFusionRefusal.SURFACE_CAPACITY)
                        }
                        projected = ProjectedSurface(projectedSurfaceCount, item.key, 0, 0, false, true)
                        projectedSurfaceCount = next
                    }
                }
                val sum = addOrRefuse(projected.weight, item.signedWeight)
                    ?: return@allocate Staging.Refused(M3FeatureFusionRefusal.CHECKED_ARITHMETIC)
                val count = addOrRefuse(projected.observationCount, 1)
                    ?: return@allocate Staging.Refused(M3FeatureFusionRefusal.CHECKED_ARITHMETIC)
                val weight = sum.coerceIn(-EVIDENCE_SATURATION, EVIDENCE_SATURATION)
                val threshold = if (projected.isActive) DEACTIVATION_THRESHOLD else OCCUPANCY_THRESHOLD
                val updated = projected.copy(weight = weight, observationCount = count, isActive = weight >= threshold)
                updatesByKey[item.key] = updated
                associations += ProjectedAssociation(updated.slot, item.signedWeight, item.supportId)
            }

            operations.allocate(M3AllocationCut.RESULT) {
                val newBySlot = updatesByKey.values.filter { it.isNew }.associateBy { it.slot }
                val candidates = ArrayList<M3FeatureFusionCandidate>()
                repeat(projectedSurfaceCount) { slot ->
                    val key = if (slot < surfaceCount) VoxelKey(surfaceX[slot], surfaceY[slot], surfaceZ[slot]) else newBySlot.getValue(slot).key
                    val projected = updatesByKey[key]
                    if (projected?.isActive ?: active[slot]) {
                        candidates += M3FeatureFusionCandidate(
                            key.x, key.y, key.z,
                            projected?.weight ?: accumulatedWeights[slot],
                            normalOctant(key),
                            projected?.observationCount ?: observationCounts[slot],
                        )
                    }
                }
                candidates.sortWith(compareBy<M3FeatureFusionCandidate> { it.x }.thenBy { it.y }.thenBy { it.z })
                val result = M3FeatureFusionResult.Accepted(
                    Collections.unmodifiableList(candidates),
                    receipt(projectedSurfaceCount, nextAssociations),
                )
                Staging.Accepted(updatesByKey.values.toList(), newBySlot.values.toList(), associations, result)
            }
        }

    private fun insertAt(slot: Int, key: VoxelKey) {
        check(slot == surfaceCount && surfaceCount < SURFACE_CAPACITY)
        var bucket = hash(key)
        while (hashSlots[bucket] != 0) bucket = (bucket + 1) and HASH_MASK
        hashSlots[bucket] = slot + 1
        surfaceX[slot] = key.x
        surfaceY[slot] = key.y
        surfaceZ[slot] = key.z
        surfaceCount++
    }

    private fun findSlot(key: VoxelKey): Int {
        var bucket = hash(key)
        repeat(HASH_SLOTS) {
            val encoded = hashSlots[bucket]
            if (encoded == 0) return -1
            val slot = encoded - 1
            if (surfaceX[slot] == key.x && surfaceY[slot] == key.y && surfaceZ[slot] == key.z) return slot
            bucket = (bucket + 1) and HASH_MASK
        }
        return -1
    }

    private fun quantize(meters: Double): Int? {
        if (!meters.isFinite()) return null
        val voxel = floor(meters / VOXEL_METERS)
        if (!voxel.isFinite() || voxel < VOXEL_MIN || voxel > VOXEL_MAX) return null
        return voxel.toInt()
    }

    private fun quantizationRefusal(meters: Double) =
        if (meters.isFinite()) M3FeatureFusionRefusal.COORDINATE_OUT_OF_RANGE else M3FeatureFusionRefusal.NON_FINITE_COORDINATE

    private fun addOrRefuse(left: Int, right: Int): Int? = try {
        operations.addExact(left, right)
    } catch (_: ArithmeticException) {
        null
    }

    private fun refused(reason: M3FeatureFusionRefusal) = M3FeatureFusionResult.Refused(reason, receipt())
    private fun receipt(surfaces: Int = surfaceCount, associations: Int = associationCount) =
        M3FeatureFusionResourceReceipt(surfaces, associations, M3_TUPLE_SHARE_BYTES)

    private fun hash(key: VoxelKey): Int {
        var value = key.x * 73856093 xor key.y * 19349663 xor key.z * 83492791
        value = value xor (value ushr 16)
        return value and HASH_MASK
    }

    private fun normalOctant(key: VoxelKey): Int =
        ((key.x.compareTo(0) shl 2) or (key.y.compareTo(0) shl 1) or key.z.compareTo(0)) and 7

    private data class VoxelKey(val x: Int, val y: Int, val z: Int)
    private data class NormalizedEvidence(val key: VoxelKey, val signedWeight: Int, val supportId: Int)
    private data class ProjectedAssociation(val slot: Int, val weight: Int, val supportId: Int)
    private data class ProjectedSurface(val slot: Int, val key: VoxelKey, val weight: Int, val observationCount: Int, val isActive: Boolean, val isNew: Boolean)
    private sealed interface Normalization {
        data class Accepted(val evidence: List<NormalizedEvidence>) : Normalization
        data class Refused(val reason: M3FeatureFusionRefusal) : Normalization
    }
    private sealed interface Staging {
        data class Accepted(
            val updates: List<ProjectedSurface>,
            val newSurfaces: List<ProjectedSurface>,
            val associations: List<ProjectedAssociation>,
            val result: M3FeatureFusionResult.Accepted,
        ) : Staging
        data class Refused(val reason: M3FeatureFusionRefusal) : Staging
    }

    private val surfaceX = IntArray(SURFACE_CAPACITY)
    private val surfaceY = IntArray(SURFACE_CAPACITY)
    private val surfaceZ = IntArray(SURFACE_CAPACITY)
    private val accumulatedWeights = IntArray(SURFACE_CAPACITY)
    private val observationCounts = IntArray(SURFACE_CAPACITY)
    private val active = BooleanArray(SURFACE_CAPACITY)
    private val evidenceWeights = IntArray(ASSOCIATION_CAPACITY)
    private val evidenceSupportIds = IntArray(ASSOCIATION_CAPACITY)
    private val associationSlots = IntArray(ASSOCIATION_CAPACITY)
    private val hashSlots = IntArray(HASH_SLOTS)
    private var surfaceCount = 0
    private var associationCount = 0
    private var lastSequence = Long.MIN_VALUE
    private var lastTimestampNs = Long.MIN_VALUE

    private companion object {
        const val SURFACE_CAPACITY = 100_000
        const val ASSOCIATION_CAPACITY = 200_000
        const val HASH_SLOTS = 262_144
        const val HASH_MASK = HASH_SLOTS - 1
        const val M3_TUPLE_SHARE_BYTES = 16 * 1024 * 1024
        const val OCCUPANCY_THRESHOLD = 2
        const val DEACTIVATION_THRESHOLD = 1
        const val EVIDENCE_SATURATION = 127
        const val VOXEL_METERS = 0.1
        const val VOXEL_MIN = -(1 shl 20).toDouble()
        const val VOXEL_MAX = ((1 shl 20) - 1).toDouble()
    }
}

/** Internal seam: production and fault-injected adapters stage the same work. */
internal interface M3KernelOperations {
    fun <T> allocate(cut: M3AllocationCut, block: () -> T): T
    fun addExact(left: Int, right: Int): Int
}

internal object JvmM3KernelOperations : M3KernelOperations {
    override fun <T> allocate(cut: M3AllocationCut, block: () -> T): T = block()
    override fun addExact(left: Int, right: Int): Int = Math.addExact(left, right)
}

internal enum class M3AllocationCut { NORMALIZATION, PREFLIGHT, RESULT }
internal class M3AllocationFailure : RuntimeException()

internal class M3FeatureFusionBatch(val sequence: Long, val timestampNs: Long, observations: List<M3FeatureFusionEvidence>) {
    val observations: List<M3FeatureFusionEvidence> = observations.toList()
}

internal data class M3FeatureFusionEvidence(val xMeters: Double, val yMeters: Double, val zMeters: Double, val signedWeight: Int, val supportId: Int)

internal sealed interface M3FeatureFusionResult {
    data class Accepted(val candidates: List<M3FeatureFusionCandidate>, val receipt: M3FeatureFusionResourceReceipt) : M3FeatureFusionResult
    data class Refused(val reason: M3FeatureFusionRefusal, val receipt: M3FeatureFusionResourceReceipt) : M3FeatureFusionResult
}

internal data class M3FeatureFusionCandidate(val x: Int, val y: Int, val z: Int, val weight: Int, val normalOctant: Int, val observationCount: Int)
internal data class M3FeatureFusionResourceReceipt(val surfaceCount: Int, val associationCount: Int, val assignedTupleShareBytes: Int)

internal enum class M3FeatureFusionRefusal {
    STALE_BATCH,
    NON_FINITE_COORDINATE,
    COORDINATE_OUT_OF_RANGE,
    INVALID_EVIDENCE_WEIGHT,
    CHECKED_ARITHMETIC,
    SURFACE_CAPACITY,
    ASSOCIATION_CAPACITY,
    ALLOCATION,
}
