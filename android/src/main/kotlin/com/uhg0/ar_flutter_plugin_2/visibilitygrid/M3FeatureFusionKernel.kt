package com.uhg0.ar_flutter_plugin_2.visibilitygrid

import kotlin.math.floor

/**
 * The feature-only M3 candidate-A kernel.
 *
 * This module owns its compact evidence, association, and surface rows.  Its
 * result is intentionally internal: later milestones own durable surface
 * identities, lineage, paging, and publication.
 */
internal class M3FeatureFusionKernel {
    /** Applies one immutable, ordered feature batch without exposing storage. */
    fun accept(batch: M3FeatureFusionBatch): M3FeatureFusionResult {
        val normalized = when (val result = normalize(batch)) {
            is Normalization.Refused -> return M3FeatureFusionResult.Refused(result.reason, receipt())
            is Normalization.Accepted -> result.evidence
        }

        val capacityRefusal = preflight(normalized)
        if (capacityRefusal != null) {
            return M3FeatureFusionResult.Refused(capacityRefusal, receipt())
        }

        normalized.forEach { evidence ->
            val slot = findSlot(evidence.x, evidence.y, evidence.z)
            val surfaceSlot = if (slot >= 0) slot else insert(evidence.x, evidence.y, evidence.z)
            associationSlots[associationCount] = surfaceSlot
            evidenceWeights[associationCount] = evidence.signedWeight
            evidenceSupportIds[associationCount] = evidence.supportId
            associationCount++

            val nextWeight = checkedAdd(accumulatedWeights[surfaceSlot], evidence.signedWeight)
            accumulatedWeights[surfaceSlot] = nextWeight.coerceIn(-EVIDENCE_SATURATION, EVIDENCE_SATURATION)
            observationCounts[surfaceSlot] = checkedAdd(observationCounts[surfaceSlot], 1)
        }

        // Normalization sorts by voxel then support.  Updating in that exact
        // order makes the threshold transition independent of callback order.
        normalized.forEachIndexed { index, evidence ->
            if (index == 0 || !evidence.sameKey(normalized[index - 1])) {
                updateActivity(findSlot(evidence.x, evidence.y, evidence.z))
            }
        }
        lastSequence = batch.sequence
        lastTimestampNs = batch.timestampNs
        return M3FeatureFusionResult.Accepted(candidates(), receipt())
    }

    private fun normalize(batch: M3FeatureFusionBatch): Normalization {
        if (batch.sequence <= lastSequence || batch.timestampNs <= lastTimestampNs || batch.timestampNs < 0L) {
            return Normalization.Refused(M3FeatureFusionRefusal.STALE_BATCH)
        }
        if (batch.observations.size > ASSOCIATION_CAPACITY) {
            return Normalization.Refused(M3FeatureFusionRefusal.ASSOCIATION_CAPACITY)
        }
        val normalized = ArrayList<NormalizedEvidence>(batch.observations.size)
        batch.observations.forEach { evidence ->
            if (evidence.signedWeight !in -EVIDENCE_SATURATION..EVIDENCE_SATURATION) {
                return Normalization.Refused(M3FeatureFusionRefusal.INVALID_EVIDENCE_WEIGHT)
            }
            val x = quantize(evidence.xMeters) ?: return Normalization.Refused(quantizationRefusal(evidence.xMeters))
            val y = quantize(evidence.yMeters) ?: return Normalization.Refused(quantizationRefusal(evidence.yMeters))
            val z = quantize(evidence.zMeters) ?: return Normalization.Refused(quantizationRefusal(evidence.zMeters))
            normalized += NormalizedEvidence(x, y, z, evidence.signedWeight, evidence.supportId)
        }
        return Normalization.Accepted(
            normalized.sortedWith(
                compareBy<NormalizedEvidence> { it.x }
                    .thenBy { it.y }
                    .thenBy { it.z }
                    .thenBy { it.supportId }
                    .thenBy { it.signedWeight },
            ),
        )
    }

    private fun quantize(meters: Double): Int? {
        if (!meters.isFinite()) return null
        val voxel = floor(meters / VOXEL_METERS)
        if (!voxel.isFinite() || voxel < VOXEL_MIN || voxel > VOXEL_MAX) return null
        return voxel.toInt()
    }

    private fun quantizationRefusal(meters: Double): M3FeatureFusionRefusal =
        if (meters.isFinite()) M3FeatureFusionRefusal.COORDINATE_OUT_OF_RANGE
        else M3FeatureFusionRefusal.NON_FINITE_COORDINATE

    private fun preflight(batch: List<NormalizedEvidence>): M3FeatureFusionRefusal? {
        val nextAssociations = checkedAddOrNull(associationCount, batch.size)
            ?: return M3FeatureFusionRefusal.CHECKED_ARITHMETIC
        if (nextAssociations > ASSOCIATION_CAPACITY) return M3FeatureFusionRefusal.ASSOCIATION_CAPACITY

        val newKeys = HashSet<NormalizedEvidence>()
        batch.forEach { evidence ->
            if (findSlot(evidence.x, evidence.y, evidence.z) < 0) newKeys += evidence.keyOnly()
        }
        val nextSurfaces = checkedAddOrNull(surfaceCount, newKeys.size)
            ?: return M3FeatureFusionRefusal.CHECKED_ARITHMETIC
        return if (nextSurfaces > SURFACE_CAPACITY) M3FeatureFusionRefusal.SURFACE_CAPACITY else null
    }

    private fun insert(x: Int, y: Int, z: Int): Int {
        check(surfaceCount < SURFACE_CAPACITY)
        var bucket = hash(x, y, z)
        while (hashSlots[bucket] != 0) bucket = (bucket + 1) and HASH_MASK
        val slot = surfaceCount++
        hashSlots[bucket] = slot + 1
        surfaceX[slot] = x
        surfaceY[slot] = y
        surfaceZ[slot] = z
        return slot
    }

    private fun findSlot(x: Int, y: Int, z: Int): Int {
        var bucket = hash(x, y, z)
        repeat(HASH_SLOTS) {
            val encoded = hashSlots[bucket]
            if (encoded == 0) return -1
            val slot = encoded - 1
            if (surfaceX[slot] == x && surfaceY[slot] == y && surfaceZ[slot] == z) return slot
            bucket = (bucket + 1) and HASH_MASK
        }
        return -1
    }

    private fun updateActivity(slot: Int) {
        val threshold = if (active[slot]) DEACTIVATION_THRESHOLD else OCCUPANCY_THRESHOLD
        active[slot] = accumulatedWeights[slot] >= threshold
    }

    private fun candidates(): List<M3FeatureFusionCandidate> =
        (0 until surfaceCount)
            .filter { active[it] }
            .map { slot ->
                M3FeatureFusionCandidate(
                    x = surfaceX[slot],
                    y = surfaceY[slot],
                    z = surfaceZ[slot],
                    weight = accumulatedWeights[slot],
                    normalOctant = normalOctant(surfaceX[slot], surfaceY[slot], surfaceZ[slot]),
                    observationCount = observationCounts[slot],
                )
            }
            .sortedWith(compareBy<M3FeatureFusionCandidate> { it.x }.thenBy { it.y }.thenBy { it.z })

    private fun receipt(): M3FeatureFusionResourceReceipt =
        M3FeatureFusionResourceReceipt(
            surfaceCount = surfaceCount,
            associationCount = associationCount,
            retainedPrimitiveBytes = retainedPrimitiveBytes,
            assignedTupleShareBytes = M3_TUPLE_SHARE_BYTES,
        )

    private fun checkedAdd(left: Int, right: Int): Int = Math.addExact(left, right)

    private fun checkedAddOrNull(left: Int, right: Int): Int? = try {
        Math.addExact(left, right)
    } catch (_: ArithmeticException) {
        null
    }

    private fun hash(x: Int, y: Int, z: Int): Int {
        var value = x * 73856093 xor y * 19349663 xor z * 83492791
        value = value xor (value ushr 16)
        return value and HASH_MASK
    }

    private fun normalOctant(x: Int, y: Int, z: Int): Int =
        ((x.compareTo(0) shl 2) or (y.compareTo(0) shl 1) or z.compareTo(0)) and 7

    private data class NormalizedEvidence(
        val x: Int,
        val y: Int,
        val z: Int,
        val signedWeight: Int,
        val supportId: Int,
    ) {
        fun sameKey(other: NormalizedEvidence): Boolean = x == other.x && y == other.y && z == other.z
        fun keyOnly(): NormalizedEvidence = copy(signedWeight = 0, supportId = 0)
    }

    private sealed interface Normalization {
        data class Accepted(val evidence: List<NormalizedEvidence>) : Normalization
        data class Refused(val reason: M3FeatureFusionRefusal) : Normalization
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
    private val retainedPrimitiveBytes =
        (surfaceX.size + surfaceY.size + surfaceZ.size + accumulatedWeights.size + observationCounts.size +
            evidenceWeights.size + evidenceSupportIds.size + associationSlots.size + hashSlots.size) * Int.SIZE_BYTES +
            active.size
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

internal class M3FeatureFusionBatch(
    val sequence: Long,
    val timestampNs: Long,
    observations: List<M3FeatureFusionEvidence>,
) {
    val observations: List<M3FeatureFusionEvidence> = observations.toList()
}

internal data class M3FeatureFusionEvidence(
    val xMeters: Double,
    val yMeters: Double,
    val zMeters: Double,
    val signedWeight: Int,
    val supportId: Int,
)

internal sealed interface M3FeatureFusionResult {
    data class Accepted(
        val candidates: List<M3FeatureFusionCandidate>,
        val receipt: M3FeatureFusionResourceReceipt,
    ) : M3FeatureFusionResult

    data class Refused(
        val reason: M3FeatureFusionRefusal,
        val receipt: M3FeatureFusionResourceReceipt,
    ) : M3FeatureFusionResult
}

internal data class M3FeatureFusionCandidate(
    val x: Int,
    val y: Int,
    val z: Int,
    val weight: Int,
    val normalOctant: Int,
    val observationCount: Int,
)

internal data class M3FeatureFusionResourceReceipt(
    val surfaceCount: Int,
    val associationCount: Int,
    val retainedPrimitiveBytes: Int,
    val assignedTupleShareBytes: Int,
)

internal enum class M3FeatureFusionRefusal {
    INVALID_BATCH,
    STALE_BATCH,
    NON_FINITE_COORDINATE,
    COORDINATE_OUT_OF_RANGE,
    INVALID_EVIDENCE_WEIGHT,
    CHECKED_ARITHMETIC,
    SURFACE_CAPACITY,
    ASSOCIATION_CAPACITY,
}
