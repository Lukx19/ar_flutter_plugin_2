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
            observationCounts[update.slot] = encodeObservationState(update.observationCount, update.primarySide)
            active[update.slot] = update.isActive
            axisXQ13[update.slot] = update.axisXQ13
            axisYQ13[update.slot] = update.axisYQ13
            axisZQ13[update.slot] = update.axisZQ13
            positiveSupportQ13[update.slot] = update.positiveSupportQ13
            negativeSupportQ13[update.slot] = update.negativeSupportQ13
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
            // Batch-local only: exhaustive encoding remains exact while repeated
            // rays do not pay its 65,025-code search repeatedly.
            val octCodes = HashMap<DirectionKey, Pair<Int, Int>>()
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
                val normal = evidence.normalEvidence
                    ?: return@allocate Normalization.Refused(M3FeatureFusionRefusal.INVALID_NORMAL_EVIDENCE)
                if (normal.voxelX != x || normal.voxelY != y || normal.voxelZ != z ||
                    normal.confidenceQ15 !in 0..32_767 ||
                    (normal.sampleXmm == normal.cameraXmm && normal.sampleYmm == normal.cameraYmm && normal.sampleZmm == normal.cameraZmm)
                ) return@allocate Normalization.Refused(M3FeatureFusionRefusal.INVALID_NORMAL_EVIDENCE)
                val direction = M3NormalMath.normalizeQ15(
                    normal.cameraXmm.toLong() - normal.sampleXmm,
                    normal.cameraYmm.toLong() - normal.sampleYmm,
                    normal.cameraZmm.toLong() - normal.sampleZmm,
                ) ?: return@allocate Normalization.Refused(M3FeatureFusionRefusal.INVALID_NORMAL_EVIDENCE)
                val negated = M3NormalMath.negated(direction)
                val codes = octCodes.getOrPut(DirectionKey(direction[0], direction[1], direction[2])) {
                    M3NormalMath.encodeOct(direction) to M3NormalMath.encodeOct(negated)
                }
                val directCode = codes.first
                val oppositeCode = codes.second
                val canonicalDirection = if (directCode <= oppositeCode) direction else negated
                normalized += NormalizedEvidence(
                    VoxelKey(x, y, z), evidence.signedWeight, evidence.supportId, canonicalDirection,
                    directCode <= oppositeCode, M3NormalMath.confidenceQ13(normal.confidenceQ15),
                )
            }
            normalized.sortWith(
                compareBy<NormalizedEvidence> { it.key.x }
                    .thenBy { it.key.y }
                    .thenBy { it.key.z }
                    .thenBy { it.supportId }
                    .thenBy { it.signedWeight }
                    .thenBy { it.axisDirectionQ15[0] }
                    .thenBy { it.axisDirectionQ15[1] }
                    .thenBy { it.axisDirectionQ15[2] }
                    .thenBy { it.positiveSide }
                    .thenBy { it.supportQ13 },
            )
            Normalization.Accepted(normalized)
        }
    }

    private fun stage(evidence: List<NormalizedEvidence>, batch: M3FeatureFusionBatch): Staging = try {
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
                        projected = ProjectedSurface(existing, item.key, accumulatedWeights[existing], observationCount(observationCounts[existing]), active[existing], false,
                            axisXQ13[existing], axisYQ13[existing], axisZQ13[existing], positiveSupportQ13[existing], negativeSupportQ13[existing],
                            primarySide(observationCounts[existing]))
                    } else {
                        val next = addOrRefuse(projectedSurfaceCount, 1)
                            ?: return@allocate Staging.Refused(M3FeatureFusionRefusal.CHECKED_ARITHMETIC)
                        if (next > SURFACE_CAPACITY) {
                            return@allocate Staging.Refused(M3FeatureFusionRefusal.SURFACE_CAPACITY)
                        }
                        projected = ProjectedSurface(projectedSurfaceCount, item.key, 0, 0, false, true, 0, 0, 0, 0, 0, M3FeaturePrimarySide.NONE)
                        projectedSurfaceCount = next
                    }
                }
                val sum = addOrRefuse(projected.weight, item.signedWeight)
                    ?: return@allocate Staging.Refused(M3FeatureFusionRefusal.CHECKED_ARITHMETIC)
                val count = addOrRefuse(projected.observationCount, 1)
                    ?: return@allocate Staging.Refused(M3FeatureFusionRefusal.CHECKED_ARITHMETIC)
                val weight = sum.coerceIn(-EVIDENCE_SATURATION, EVIDENCE_SATURATION)
                val threshold = if (projected.isActive) DEACTIVATION_THRESHOLD else OCCUPANCY_THRESHOLD
                val normal = if (item.signedWeight > 0 && item.supportQ13 > 0) projected.addNormal(item) else projected
                val updated = normal.copy(weight = weight, observationCount = count, isActive = weight >= threshold)
                updatesByKey[item.key] = updated
                associations += ProjectedAssociation(updated.slot, item.signedWeight, item.supportId)
            }
            // Pin only from the complete cumulative state for this admitted
            // batch. Raw input permutation cannot choose a transient winner.
            updatesByKey.entries.forEach { entry -> entry.setValue(pinReliablePrimary(entry.value)) }

            operations.allocate(M3AllocationCut.RESULT) {
                // The result is deliberately derived only from the distinct voxels
                // staged by this batch.  Retained arrays remain the sole complete
                // candidate-A state; scanning them here would make a one-voxel
                // refinement proportional to the live population.
                val delta = ArrayList<M3FeatureFusionChange>(updatesByKey.size)
                // Batch-local only. Capacity campaigns commonly share an exact
                // accumulated axis; exhaustively encode each distinct Q15 axis
                // once without retaining an estimator cache in kernel state.
                val axisOctCodes = HashMap<DirectionKey, Pair<Int, Int>>()
                updatesByKey.values.forEach { projected ->
                    val wasActive = !projected.isNew && active[projected.slot]
                    when {
                        projected.isActive && (!wasActive || hasMaterialChange(projected, axisOctCodes)) ->
                            delta += M3FeatureFusionChange.Upsert(candidate(projected, axisOctCodes))
                        wasActive && !projected.isActive ->
                            delta += M3FeatureFusionChange.Removal(projected.key.x, projected.key.y, projected.key.z)
                    }
                }
                delta.sortWith(compareBy<M3FeatureFusionChange> { it.x }.thenBy { it.y }.thenBy { it.z })
                val result = M3FeatureFusionResult.Accepted(
                    Collections.unmodifiableList(delta),
                    receipt(projectedSurfaceCount, nextAssociations),
                    M3FeatureFusionWorkReceipt(
                        distinctTouchedVoxelCount = updatesByKey.size,
                        emittedEventCount = delta.size,
                    ),
                )
                Staging.Accepted(
                    updatesByKey.values.toList(),
                    updatesByKey.values.filter { it.isNew },
                    associations,
                    result,
                )
            }
        }
    } catch (_: ArithmeticException) {
        Staging.Refused(M3FeatureFusionRefusal.CHECKED_ARITHMETIC)
    }

    private fun candidate(surface: ProjectedSurface, axisOctCodes: MutableMap<DirectionKey, Pair<Int, Int>>) = M3FeatureFusionCandidate(
        surface.key.x,
        surface.key.y,
        surface.key.z,
        surface.weight,
        surface.observationCount,
        hypotheses(surface, axisOctCodes),
    )

    /** Within-band confidence is retained evidence, not canonical material state. */
    private fun hasMaterialChange(surface: ProjectedSurface, axisOctCodes: MutableMap<DirectionKey, Pair<Int, Int>>): Boolean {
        val previous = ProjectedSurface(surface.slot, surface.key, accumulatedWeights[surface.slot], observationCount(observationCounts[surface.slot]), active[surface.slot], false,
            axisXQ13[surface.slot], axisYQ13[surface.slot], axisZQ13[surface.slot], positiveSupportQ13[surface.slot], negativeSupportQ13[surface.slot],
            primarySide(observationCounts[surface.slot]))
        val before = hypotheses(previous, axisOctCodes)
        val after = hypotheses(surface, axisOctCodes)
        return before.size != after.size || before.zip(after).any { (old, new) ->
            old.face != new.face || old.normalOctX != new.normalOctX || old.normalOctY != new.normalOctY ||
                confidenceBand(old.normalConfidence) != confidenceBand(new.normalConfidence)
        }
    }

    private fun confidenceBand(confidence: Int): Int = when (confidence.coerceIn(0, 255)) {
        0 -> 0
        in 1 until 64 -> 1
        in 64 until 192 -> 64
        else -> 192
    }

    private fun pinReliablePrimary(surface: ProjectedSurface): ProjectedSurface {
        if (surface.primarySide != M3FeaturePrimarySide.NONE || surface.positiveSupportQ13 == surface.negativeSupportQ13) return surface
        val positive = normalConfidence(surface.positiveSupportQ13)
        val negative = normalConfidence(surface.negativeSupportQ13)
        val dominant = if (surface.positiveSupportQ13 > surface.negativeSupportQ13) {
            M3FeaturePrimarySide.POSITIVE to positive
        } else {
            M3FeaturePrimarySide.NEGATIVE to negative
        }
        return if (dominant.second >= 64) surface.copy(primarySide = dominant.first) else surface
    }

    private fun observationCount(encoded: Int): Int = encoded and OBSERVATION_COUNT_MASK
    private fun primarySide(encoded: Int): M3FeaturePrimarySide = when (encoded ushr OBSERVATION_PRIMARY_SHIFT) {
        0 -> M3FeaturePrimarySide.NONE
        1 -> M3FeaturePrimarySide.POSITIVE
        2 -> M3FeaturePrimarySide.NEGATIVE
        else -> error("invalid retained primary-side state")
    }
    private fun encodeObservationState(count: Int, side: M3FeaturePrimarySide): Int {
        require(count in 0..OBSERVATION_COUNT_MASK)
        return count or (side.code shl OBSERVATION_PRIMARY_SHIFT)
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

    /**
     * C12's exact equal-side rule is intentionally stronger than the ticket's
     * later two-face shorthand: a tie publishes one lexicographically-minimum
     * unknown normal (confidence 0), rather than two falsely oriented faces.
     */
    private fun hypotheses(
        surface: ProjectedSurface,
        axisOctCodes: MutableMap<DirectionKey, Pair<Int, Int>>,
    ): List<M3FeatureNormalCandidate> {
        val axis = M3NormalMath.normalizeQ15(surface.axisXQ13.toLong(), surface.axisYQ13.toLong(), surface.axisZQ13.toLong())
            ?: return emptyList()
        val codes = axisOctCodes.getOrPut(DirectionKey(axis[0], axis[1], axis[2])) {
            M3NormalMath.encodeOct(axis) to M3NormalMath.encodeOct(M3NormalMath.negated(axis))
        }
        val axisCode = codes.first
        val opposite = codes.second
        val positive = surface.positiveSupportQ13
        val negative = surface.negativeSupportQ13
        if (positive == negative) {
            val chosen = minOf(axisCode, opposite)
            return listOf(M3FeatureNormalCandidate(surface.key.x, surface.key.y, surface.key.z, M3FeatureNormalFace.PRIMARY,
                (chosen ushr 8).toByte().toInt(), chosen.toByte().toInt(), 0))
        }
        val positiveConfidence = normalConfidence(positive)
        val negativeConfidence = normalConfidence(negative)
        val bothReliable = positiveConfidence >= 64 && negativeConfidence >= 64
        val primaryPositive = when (surface.primarySide) {
            M3FeaturePrimarySide.POSITIVE -> true
            M3FeaturePrimarySide.NEGATIVE -> false
            M3FeaturePrimarySide.NONE -> positive > negative
        }
        val primaryCode = if (primaryPositive) axisCode else opposite
        val primaryConfidence = if (bothReliable) {
            if (primaryPositive) positiveConfidence else negativeConfidence
        } else {
            normalConfidence(kotlin.math.abs(positive - negative))
        }
        val primary = M3FeatureNormalCandidate(surface.key.x, surface.key.y, surface.key.z, M3FeatureNormalFace.PRIMARY,
            (primaryCode ushr 8).toByte().toInt(), primaryCode.toByte().toInt(), primaryConfidence)
        if (!bothReliable) return listOf(primary)
        val opposingCode = if (primaryPositive) opposite else axisCode
        return listOf(primary, M3FeatureNormalCandidate(surface.key.x, surface.key.y, surface.key.z, M3FeatureNormalFace.OPPOSING,
            (opposingCode ushr 8).toByte().toInt(), opposingCode.toByte().toInt(),
            if (primaryPositive) negativeConfidence else positiveConfidence))
            .sortedBy { ((it.normalOctX and 0xff) shl 8) or (it.normalOctY and 0xff) }
    }

    private fun normalConfidence(support: Int): Int =
        M3NormalMath.roundTiesEven(support.toLong() * 255L, 4L * 8192L).coerceIn(0L, 255L).toInt()

    private data class VoxelKey(val x: Int, val y: Int, val z: Int)
    private data class DirectionKey(val x: Int, val y: Int, val z: Int)
    private data class NormalizedEvidence(val key: VoxelKey, val signedWeight: Int, val supportId: Int, val axisDirectionQ15: IntArray, val positiveSide: Boolean, val supportQ13: Int)
    private data class ProjectedAssociation(val slot: Int, val weight: Int, val supportId: Int)
    private data class ProjectedSurface(
        val slot: Int, val key: VoxelKey, val weight: Int, val observationCount: Int, val isActive: Boolean, val isNew: Boolean,
        val axisXQ13: Int, val axisYQ13: Int, val axisZQ13: Int, val positiveSupportQ13: Int, val negativeSupportQ13: Int,
        val primarySide: M3FeaturePrimarySide,
    ) {
        fun addNormal(evidence: NormalizedEvidence): ProjectedSurface {
            fun contribution(component: Int) = Math.toIntExact(M3NormalMath.roundTiesEven(component.toLong() * evidence.supportQ13, 32_767L))
            return copy(
                axisXQ13 = Math.addExact(axisXQ13, contribution(evidence.axisDirectionQ15[0])),
                axisYQ13 = Math.addExact(axisYQ13, contribution(evidence.axisDirectionQ15[1])),
                axisZQ13 = Math.addExact(axisZQ13, contribution(evidence.axisDirectionQ15[2])),
                positiveSupportQ13 = if (evidence.positiveSide) Math.addExact(positiveSupportQ13, evidence.supportQ13) else positiveSupportQ13,
                negativeSupportQ13 = if (evidence.positiveSide) negativeSupportQ13 else Math.addExact(negativeSupportQ13, evidence.supportQ13),
            )
        }
    }
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
    // #113's only retained directional state: five fixed primitive arrays.
    private val axisXQ13 = IntArray(SURFACE_CAPACITY)
    private val axisYQ13 = IntArray(SURFACE_CAPACITY)
    private val axisZQ13 = IntArray(SURFACE_CAPACITY)
    private val positiveSupportQ13 = IntArray(SURFACE_CAPACITY)
    private val negativeSupportQ13 = IntArray(SURFACE_CAPACITY)
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
        const val M3_TUPLE_SHARE_BYTES = 7_549_000
        const val OCCUPANCY_THRESHOLD = 2
        const val DEACTIVATION_THRESHOLD = 1
        const val EVIDENCE_SATURATION = 127
        const val OBSERVATION_PRIMARY_SHIFT = 30
        const val OBSERVATION_COUNT_MASK = (1 shl OBSERVATION_PRIMARY_SHIFT) - 1
        const val VOXEL_METERS = 0.1
        const val VOXEL_MIN = -(1 shl 20).toDouble()
        const val VOXEL_MAX = ((1 shl 20) - 1).toDouble()
    }
}

private enum class M3FeaturePrimarySide(val code: Int) { NONE(0), POSITIVE(1), NEGATIVE(2) }

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

internal data class M3FeatureFusionEvidence(
    val xMeters: Double, val yMeters: Double, val zMeters: Double, val signedWeight: Int, val supportId: Int,
    val normalEvidence: M3FeatureNormalEvidence? = null,
)

internal sealed interface M3FeatureFusionResult {
    /**
     * A deterministic, immutable delta for this admitted batch.  It contains
     * only touched voxels whose canonical candidate-A state changed: upserts
     * for activation/material refinement and removals for deactivation.
     */
    data class Accepted(
        val delta: List<M3FeatureFusionChange>,
        val receipt: M3FeatureFusionResourceReceipt,
        val work: M3FeatureFusionWorkReceipt,
    ) : M3FeatureFusionResult
    data class Refused(val reason: M3FeatureFusionRefusal, val receipt: M3FeatureFusionResourceReceipt) : M3FeatureFusionResult
}

internal enum class M3FeatureNormalFace { PRIMARY, OPPOSING }
internal data class M3FeatureNormalCandidate(
    val x: Int, val y: Int, val z: Int, val face: M3FeatureNormalFace,
    val normalOctX: Int, val normalOctY: Int, val normalConfidence: Int,
)
internal data class M3FeatureFusionCandidate(
    val x: Int, val y: Int, val z: Int, val weight: Int, val observationCount: Int,
    val normalCandidates: List<M3FeatureNormalCandidate>,
) {
    /** Compatibility-only diagnostic for the immutable M0 candidate-A oracle. */
    val normalOctant: Int get() = ((x.compareTo(0) shl 2) or (y.compareTo(0) shl 1) or z.compareTo(0)) and 7

    @Suppress("unused")
    constructor(x: Int, y: Int, z: Int, weight: Int, normalOctantDiagnostic: Int, observationCount: Int) :
        this(x, y, z, weight, observationCount, emptyList()) {
        require(normalOctantDiagnostic in 0..7)
    }
}
internal sealed interface M3FeatureFusionChange {
    val x: Int
    val y: Int
    val z: Int

    data class Upsert(val candidate: M3FeatureFusionCandidate) : M3FeatureFusionChange {
        override val x: Int get() = candidate.x
        override val y: Int get() = candidate.y
        override val z: Int get() = candidate.z
    }

    data class Removal(override val x: Int, override val y: Int, override val z: Int) : M3FeatureFusionChange
}
internal data class M3FeatureFusionResourceReceipt(val surfaceCount: Int, val associationCount: Int, val assignedTupleShareBytes: Int)
/** Scalar-only receipt for bounded output work; it never exposes retained rows. */
internal data class M3FeatureFusionWorkReceipt(val distinctTouchedVoxelCount: Int, val emittedEventCount: Int)

internal enum class M3FeatureFusionRefusal {
    STALE_BATCH,
    NON_FINITE_COORDINATE,
    COORDINATE_OUT_OF_RANGE,
    INVALID_EVIDENCE_WEIGHT,
    INVALID_NORMAL_EVIDENCE,
    CHECKED_ARITHMETIC,
    SURFACE_CAPACITY,
    ASSOCIATION_CAPACITY,
    ALLOCATION,
}
