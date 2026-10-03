package com.uhg0.ar_flutter_plugin_2.visibilitygrid

import java.util.Collections
import java.util.AbstractList
import kotlin.math.floor

/** Candidate-A feature fusion behind one state-owning admission interface. */
internal class FeatureFusionKernel(
    private val operations: FeatureFusionOperations = JvmFeatureFusionOperations,
) {
    /**
     * Resolves an exact canonical allocation receipt from the source authority.
     * The caller supplies the cut and binding generation captured when the
     * kernel was attached; an authority that has moved on must return null.
     * Implementations run on the serial canonical lane and must not retain a
     * lease or allocate a source-sized index of their own.
     */
    internal fun interface CanonicalFingerprintResolver {
        fun resolve(
            sourceId: Long,
            expectedGeometryRevision: Long,
            expectedLineageRevision: Long,
            expectedGeneration: Long,
        ): CanonicalReceiptBytes?
    }

    private var fingerprintResolver: CanonicalFingerprintResolver? = null
    private var fingerprintGeometryRevision = 0L
    private var fingerprintLineageRevision = 0L
    private var fingerprintGeneration = 0L
    /**
     * Direct kernels in the pure reference tests have no canonical authority.
     * This sparse fallback is cleared as soon as production binds a resolver;
     * it is deliberately not part of the production steady-state ownership.
     */
    private val fixtureCurrentFingerprintBacking = HashMap<Int, CanonicalReceiptBytes>()
    private val fixtureHistoricalFingerprintBacking = HashMap<Int, CanonicalReceiptBytes>()

    /** Binds a cut-scoped source authority before the first production admission. */
    @Synchronized
    internal fun bindCanonicalFingerprintResolver(
        resolver: CanonicalFingerprintResolver,
        geometryRevision: Long,
        lineageRevision: Long,
        generation: Long,
    ) {
        require(geometryRevision >= 0L && lineageRevision >= 0L && generation >= 0L)
        fingerprintResolver = resolver
        fingerprintGeometryRevision = geometryRevision
        fingerprintLineageRevision = lineageRevision
        fingerprintGeneration = generation
        fixtureCurrentFingerprintBacking.clear()
        fixtureHistoricalFingerprintBacking.clear()
    }

    private val normalEncoder = FeatureNormalOctEncoder()
    /*
     * Packed ingress is consumed through these bounded primitive columns.  The
     * columns are owned by this serial kernel and are only live while
     * preparePacked is staging; no FeatureFusionEvidence or
     * FeatureNormalEvidence object is created for a packed point.
     */
    private val packedVoxelX = IntArray(V2_FEATURE_SAMPLE_CAPACITY)
    private val packedVoxelY = IntArray(V2_FEATURE_SAMPLE_CAPACITY)
    private val packedVoxelZ = IntArray(V2_FEATURE_SAMPLE_CAPACITY)
    private val packedSupportIds = IntArray(V2_FEATURE_SAMPLE_CAPACITY)
    private val packedSignedWeights = IntArray(V2_FEATURE_SAMPLE_CAPACITY)
    private val packedAxisXQ15 = IntArray(V2_FEATURE_SAMPLE_CAPACITY)
    private val packedAxisYQ15 = IntArray(V2_FEATURE_SAMPLE_CAPACITY)
    private val packedAxisZQ15 = IntArray(V2_FEATURE_SAMPLE_CAPACITY)
    private val packedPositiveSide = BooleanArray(V2_FEATURE_SAMPLE_CAPACITY)
    private val packedSupportQ13 = IntArray(V2_FEATURE_SAMPLE_CAPACITY)
    private val packedOrder = IntArray(V2_FEATURE_SAMPLE_CAPACITY)
    private var packedNormalizedCount = 0
    private val packedDirectionCacheKeys = LongArray(PACKED_DIRECTION_CACHE_CAPACITY) { Long.MIN_VALUE }
    private val packedDirectionCacheDirect = IntArray(PACKED_DIRECTION_CACHE_CAPACITY)
    private val packedDirectionCacheOpposite = IntArray(PACKED_DIRECTION_CACHE_CAPACITY)
    private val packedDirectionScratch = IntArray(3)
    private val packedNegatedDirectionScratch = IntArray(3)
    private val packedNormalizedEvidence = PackedNormalizedEvidenceList()

    /** Stages one immutable batch without changing retained state. */
    internal fun prepare(batch: FeatureFusionBatch): FeatureFusionResult {
        check(pending == null) { "a prepared kernel batch is already outstanding" }
        val staged = try {
            when (val normalization = normalize(batch)) {
                is Normalization.Refused -> return refused(normalization.reason)
                is Normalization.Accepted -> stage(normalization.evidence, batch.sequence, batch.timestampNs)
            }
        } catch (_: FeatureFusionAllocationFailure) {
            return refused(FeatureFusionRefusal.ALLOCATION)
        } catch (_: OutOfMemoryError) {
            return refused(FeatureFusionRefusal.ALLOCATION)
        }
        if (staged is Staging.Refused) return refused(staged.reason)
        staged as Staging.Accepted

        pending = PendingApplication(staged, batch.sequence, batch.timestampNs)
        return staged.result
    }

    /** Stages a packed producer lease without materialising per-point model objects. */
    internal fun preparePacked(
        observation: VisibilityFeatureObservation,
        sequence: Long = observation.frame.frameSequence,
    ): FeatureFusionResult {
        val packed = observation.packedSamples ?: return refused(FeatureFusionRefusal.INVALID_NORMAL_EVIDENCE)
        check(pending == null) { "a prepared kernel batch is already outstanding" }
        val normalized = try {
            normalizePacked(observation, sequence)
        } catch (_: FeatureFusionAllocationFailure) {
            return refused(FeatureFusionRefusal.ALLOCATION)
        } catch (_: OutOfMemoryError) {
            return refused(FeatureFusionRefusal.ALLOCATION)
        }
        if (normalized is Normalization.Refused) return refused(normalized.reason)
        val acceptedNormalization = normalized as Normalization.Accepted
        val staged = try {
            stage(acceptedNormalization.evidence, sequence, observation.frame.sourceTimestampNs)
        } catch (_: FeatureFusionAllocationFailure) {
            return refused(FeatureFusionRefusal.ALLOCATION)
        } catch (_: OutOfMemoryError) {
            return refused(FeatureFusionRefusal.ALLOCATION)
        }
        if (staged is Staging.Refused) return refused(staged.reason)
        staged as Staging.Accepted
        pending = PendingApplication(staged, sequence, observation.frame.sourceTimestampNs)
        return staged.result
    }

    /** Compatibility admission: prepare and atomically apply without canonical correlation. */
    fun accept(batch: FeatureFusionBatch): FeatureFusionResult {
        val result = prepare(batch)
        if (result is FeatureFusionResult.Accepted) {
            check(prepareCanonicalApplication(emptyList()))
            applyPrepared()
        }
        return result
    }

    /** Discards an uncommitted batch after any v6 prepare/commit refusal. */
    internal fun discardPrepared() {
        pending = null
    }

    /**
     * Stages identity-only canonical changes independently of the feature batch
     * admission above.  The staged packet owns only exact-size primitive arrays;
     * input model objects are never retained after this call.
     */
    @Synchronized
    internal fun prepareCanonicalRemap(remaps: List<CanonicalFeatureRemap>): FeatureCanonicalRemapPreparation {
        if (pendingCanonicalRemap != null) return FeatureCanonicalRemapPreparation.Refused(FeatureCanonicalRemapRefusal.PREPARED_BUSY)
        if (remaps.size > SURFACE_CAPACITY) return FeatureCanonicalRemapPreparation.Refused(FeatureCanonicalRemapRefusal.CAPACITY)
        val staged = try {
            operations.allocate(FeatureFusionAllocationCut.CANONICAL_REMAP) {
                stageCanonicalRemap(remaps)
            }
        } catch (_: FeatureFusionAllocationFailure) {
            return FeatureCanonicalRemapPreparation.Refused(FeatureCanonicalRemapRefusal.ALLOCATION)
        } catch (_: OutOfMemoryError) {
            return FeatureCanonicalRemapPreparation.Refused(FeatureCanonicalRemapRefusal.ALLOCATION)
        } catch (_: ArithmeticException) {
            return FeatureCanonicalRemapPreparation.Refused(FeatureCanonicalRemapRefusal.CHECKED_ARITHMETIC)
        }
        if (staged is CanonicalRemapStage.Refused) {
            return FeatureCanonicalRemapPreparation.Refused(staged.reason)
        }
        staged as CanonicalRemapStage.Accepted
        pendingCanonicalRemap = staged.packet
        return FeatureCanonicalRemapPreparation.Prepared(staged.packet.slots.size)
    }

    /** Convenience overload for callers changing one canonical identity. */
    @Synchronized
    internal fun prepareCanonicalRemap(remap: CanonicalFeatureRemap): FeatureCanonicalRemapPreparation =
        prepareCanonicalRemap(listOf(remap))

    /** Applies a successful remap using only the preflighted primitive packet. */
    @Synchronized
    internal fun applyPreparedCanonicalRemap() {
        val prepared = checkNotNull(pendingCanonicalRemap) { "no prepared canonical remap" }
        for (index in prepared.slots.indices) {
            val slot = prepared.slots[index]
            canonicalIds[slot] = prepared.targetIds[index].toInt()
            if (prepared.hasCanonicalMetadata[index]) {
                currentFingerprintSourceIds[slot] = prepared.targetIds[index].toInt()
                if (fingerprintResolver == null) {
                    rememberFixtureCurrentFingerprint(
                        slot,
                        CanonicalReceiptBytes(prepared.fingerprints.copyOfRange(
                            index * HASH_BYTES, (index + 1) * HASH_BYTES,
                        )),
                    )
                }
                accumulatedWeights[slot] = encodeWeightAndCanonical(
                    retainedWeight(slot), prepared.packedNormals[index], prepared.normalConfidences[index],
                )
            }
        }
        pendingCanonicalRemap = null
    }

    /** Idempotently drops a canonical remap packet without touching feature state. */
    @Synchronized
    internal fun discardPreparedCanonicalRemap() {
        pendingCanonicalRemap = null
    }

    /** Incremental primitive-array ownership of the staged identity-only remap. */
    @Synchronized
    internal fun pendingCanonicalRemapPrimitiveBytes(): Long {
        val prepared = pendingCanonicalRemap ?: return 0L
        return canonicalRemapPrimitiveBytes(prepared.slots.size)
    }

    private fun stageCanonicalRemap(remaps: List<CanonicalFeatureRemap>): CanonicalRemapStage {
        val count = remaps.size
        val tableCapacity = remapTableCapacity(count)
        val seenSlots = IntArray(tableCapacity)
        val slots = IntArray(count)
        val targetIds = LongArray(count)
        val hasCanonicalMetadata = BooleanArray(count)
        val fingerprints = ByteArray(Math.multiplyExact(count, HASH_BYTES))
        val packedNormals = IntArray(count)
        val normalConfidences = IntArray(count)
        for (index in remaps.indices) {
            val remap = remaps[index]
            val slot = remap.featureSlot
            if (slot !in 0 until surfaceCount) {
                return CanonicalRemapStage.Refused(FeatureCanonicalRemapRefusal.INVALID_SLOT)
            }
            if (!insertRemapSlot(seenSlots, slot)) {
                return CanonicalRemapStage.Refused(FeatureCanonicalRemapRefusal.DUPLICATE_SLOT)
            }
            val previous = remap.previousSurfaceId.value
            if (previous !in 1L..UINT32_MASK ||
                (canonicalIds[slot].toLong() and UINT32_MASK) != previous
            ) {
                return CanonicalRemapStage.Refused(FeatureCanonicalRemapRefusal.STALE_PREVIOUS_ID)
            }
            if (canonicalCorrelation(slot) == null || featureEvidenceAllocationFingerprint(slot) == null) {
                return CanonicalRemapStage.Refused(FeatureCanonicalRemapRefusal.CANONICAL_PROVENANCE)
            }
            val target = remap.nextSurfaceId?.value ?: 0L
            if (target !in 0L..UINT32_MASK || (remap.nextSurfaceId != null && target == 0L)) {
                return CanonicalRemapStage.Refused(FeatureCanonicalRemapRefusal.INVALID_ID)
            }
            slots[index] = slot
            targetIds[index] = target
            remap.nextProvenance?.let { provenance ->
                if (provenance.allocationFingerprint.size != HASH_BYTES ||
                    provenance.packedNormal !in 0..0xffff || provenance.normalConfidence !in 0..255 ||
                    remap.nextSurfaceId == null
                ) return CanonicalRemapStage.Refused(FeatureCanonicalRemapRefusal.INVALID_ID)
                hasCanonicalMetadata[index] = true
                provenance.allocationFingerprint.toByteArray().copyInto(fingerprints, index * HASH_BYTES)
                packedNormals[index] = provenance.packedNormal
                normalConfidences[index] = provenance.normalConfidence
            }
        }
        return CanonicalRemapStage.Accepted(PendingCanonicalRemap(
            slots, targetIds, hasCanonicalMetadata, fingerprints, packedNormals, normalConfidences,
        ))
    }

    private fun remapTableCapacity(count: Int): Int {
        var capacity = 1
        val required = operations.addExact(count, count)
        while (capacity < required) capacity = operations.addExact(capacity, capacity)
        return capacity
    }

    private fun insertRemapSlot(table: IntArray, slot: Int): Boolean {
        var index = remapHash(slot.toLong()) and (table.size - 1)
        while (true) {
            val encoded = table[index]
            if (encoded == 0) {
                table[index] = slot + 1
                return true
            }
            if (encoded == slot + 1) return false
            index = (index + 1) and (table.size - 1)
        }
    }

    private fun remapHash(value: Long): Int {
        var mixed = value xor (value ushr 33)
        mixed *= -49064778989728563L
        mixed = mixed xor (mixed ushr 33)
        mixed *= -4265267296055464877L
        return mixed.toInt()
    }


    /**
     * Validates and copies every post-commit write before durability is attempted.
     * A successful call makes [applyPrepared] allocation-free and infallible under
     * the integration's single mutation lane.
     */
    internal fun prepareCanonicalApplication(assignments: List<CanonicalFeatureAssignment>): Boolean {
        val prepared = pending ?: return false
        if (prepared.assignments != null) return false
        val staged = prepared.staged
        val seenSlots = HashSet<Int>()
        val copied = ArrayList<CanonicalFeatureAssignment>(assignments.size)
        val newBySlot = staged.newSurfaces.associateBy { it.slot }
        for (assignment in assignments) {
            val slot = assignment.kernelSlot
            if (assignment.allocationFingerprint.size != HASH_BYTES ||
                assignment.packedNormal !in 0..0xffff || assignment.normalConfidence !in 0..255 ||
                assignment.x !in VOXEL_COORDINATE_MIN..VOXEL_COORDINATE_MAX ||
                assignment.y !in VOXEL_COORDINATE_MIN..VOXEL_COORDINATE_MAX ||
                assignment.z !in VOXEL_COORDINATE_MIN..VOXEL_COORDINATE_MAX
            ) return false
            val expectedKey = if (slot < surfaceCount) {
                if (slot < 0) return false
                surfaceKeys[slot]
            } else {
                val projected = newBySlot[slot] ?: return false
                packVisibilityGridKey(projected.key.x, projected.key.y, projected.key.z)
            }
            if (slot >= staged.result.receipt.surfaceCount || !seenSlots.add(slot) ||
                expectedKey != packVisibilityGridKey(assignment.x, assignment.y, assignment.z)
            ) return false
            val retained = if (slot < surfaceCount) canonicalIds[slot] else 0
            if (retained != 0 && retained != assignment.id.value.toInt()) return false
            copied += assignment
        }
        prepared.assignments = copied
        prepared.canonicalFingerprints = ByteArray(Math.multiplyExact(copied.size, HASH_BYTES)).also { fingerprints ->
            copied.forEachIndexed { index, assignment ->
                assignment.allocationFingerprint.toByteArray().copyInto(fingerprints, index * HASH_BYTES)
            }
        }
        return true
    }

    /** Applies only primitive writes that were completely preflighted above. */
    internal fun applyPrepared(): FeatureFusionResult.Accepted {
        val prepared = requireNotNull(pending) { "no prepared kernel batch" }
        val assignments = requireNotNull(prepared.assignments) { "kernel application was not preflighted" }
        val staged = prepared.staged

        // Every allocation, checked calculation, result construction and
        // canonical-correlation validation has
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
            accumulatedWeights[update.slot] = withWeight(accumulatedWeights[update.slot], update.weight)
            observationCounts[update.slot] = encodeObservationState(
                update.observationCount,
                update.primarySide,
            )
            active[update.slot] = update.isActive
            axisXQ13[update.slot] = update.axisXQ13
            axisYQ13[update.slot] = update.axisYQ13
            axisZQ13[update.slot] = update.axisZQ13
            positiveSupportQ13[update.slot] = update.positiveSupportQ13
            negativeSupportQ13[update.slot] = update.negativeSupportQ13
        }
        for ((assignmentIndex, assignment) in assignments.withIndex()) {
            if (canonicalIds[assignment.kernelSlot] == 0) {
                canonicalIds[assignment.kernelSlot] = assignment.id.value.toInt()
            }
            if (featureEvidenceSourceIds[assignment.kernelSlot] == 0) {
                featureEvidenceSourceIds[assignment.kernelSlot] = assignment.id.value.toInt()
                if (fingerprintResolver == null) {
                    rememberFixtureHistoricalFingerprint(
                        assignment.kernelSlot,
                        CanonicalReceiptBytes(requireNotNull(prepared.canonicalFingerprints).copyOfRange(
                            assignmentIndex * HASH_BYTES, (assignmentIndex + 1) * HASH_BYTES,
                        )),
                    )
                }
            }
            accumulatedWeights[assignment.kernelSlot] = encodeWeightAndCanonical(
                retainedWeight(assignment.kernelSlot), assignment.packedNormal, assignment.normalConfidence,
            )
            currentFingerprintSourceIds[assignment.kernelSlot] = assignment.id.value.toInt()
            if (fingerprintResolver == null) {
                rememberFixtureCurrentFingerprint(
                    assignment.kernelSlot,
                    CanonicalReceiptBytes(requireNotNull(prepared.canonicalFingerprints).copyOfRange(
                        assignmentIndex * HASH_BYTES, (assignmentIndex + 1) * HASH_BYTES,
                    )),
                )
            }
        }
        lastSequence = prepared.sequence
        lastTimestampNs = prepared.timestampNs
        pending = null
        return staged.result
    }

    private fun normalize(batch: FeatureFusionBatch): Normalization {
        if (batch.sequence <= lastSequence || batch.timestampNs <= lastTimestampNs || batch.timestampNs < 0L) {
            return Normalization.Refused(FeatureFusionRefusal.STALE_BATCH)
        }
        if (batch.observations.size > ASSOCIATION_CAPACITY) {
            return Normalization.Refused(FeatureFusionRefusal.ASSOCIATION_CAPACITY)
        }
        return operations.allocate(FeatureFusionAllocationCut.NORMALIZATION) {
            val normalized = ArrayList<NormalizedEvidence>(batch.observations.size)
            // Batch-local only: exhaustive encoding remains exact while repeated
            // rays do not pay its 65,025-code search repeatedly.
            val octCodes = HashMap<DirectionKey, Pair<Int, Int>>()
            batch.observations.forEach { evidence ->
                if (evidence.signedWeight !in -EVIDENCE_SATURATION..EVIDENCE_SATURATION) {
                    return@allocate Normalization.Refused(FeatureFusionRefusal.INVALID_EVIDENCE_WEIGHT)
                }
                val x = quantize(evidence.xMeters)
                    ?: return@allocate Normalization.Refused(quantizationRefusal(evidence.xMeters))
                val y = quantize(evidence.yMeters)
                    ?: return@allocate Normalization.Refused(quantizationRefusal(evidence.yMeters))
                val z = quantize(evidence.zMeters)
                    ?: return@allocate Normalization.Refused(quantizationRefusal(evidence.zMeters))
                val normal = evidence.normalEvidence
                    ?: return@allocate Normalization.Refused(FeatureFusionRefusal.INVALID_NORMAL_EVIDENCE)
                if (normal.voxelX != x || normal.voxelY != y || normal.voxelZ != z ||
                    normal.confidenceQ15 !in 0..32_767 ||
                    (normal.sampleXmm == normal.cameraXmm && normal.sampleYmm == normal.cameraYmm && normal.sampleZmm == normal.cameraZmm)
                ) return@allocate Normalization.Refused(FeatureFusionRefusal.INVALID_NORMAL_EVIDENCE)
                val direction = FeatureNormalMath.normalizeQ15(
                    normal.cameraXmm.toLong() - normal.sampleXmm,
                    normal.cameraYmm.toLong() - normal.sampleYmm,
                    normal.cameraZmm.toLong() - normal.sampleZmm,
                ) ?: return@allocate Normalization.Refused(FeatureFusionRefusal.INVALID_NORMAL_EVIDENCE)
                val negated = FeatureNormalMath.negated(direction)
                val codes = octCodes.getOrPut(DirectionKey(direction[0], direction[1], direction[2])) {
                    normalEncoder.encode(direction) to normalEncoder.encode(negated)
                }
                val directCode = codes.first
                val oppositeCode = codes.second
                val canonicalDirection = if (directCode <= oppositeCode) direction else negated
                normalized += NormalizedEvidence(
                    VoxelKey(x, y, z), evidence.signedWeight, evidence.supportId, canonicalDirection,
                    directCode <= oppositeCode, FeatureNormalMath.confidenceQ13(normal.confidenceQ15),
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

    /**
     * Packed feature normalization writes directly into the kernel's bounded
     * primitive columns.  The staging list is a scalar view over those columns
     * and is consumed synchronously; it never owns the producer lease.
     */
    private fun normalizePacked(
        observation: VisibilityFeatureObservation,
        sequence: Long,
    ): Normalization {
        val packed = observation.packedSamples ?: return Normalization.Refused(FeatureFusionRefusal.INVALID_NORMAL_EVIDENCE)
        if (sequence <= lastSequence || observation.frame.sourceTimestampNs <= lastTimestampNs || observation.frame.sourceTimestampNs < 0L) {
            return Normalization.Refused(FeatureFusionRefusal.STALE_BATCH)
        }
        if (packed.count > ASSOCIATION_CAPACITY || packed.count > V2_FEATURE_SAMPLE_CAPACITY) {
            return Normalization.Refused(FeatureFusionRefusal.ASSOCIATION_CAPACITY)
        }
        return operations.allocate(FeatureFusionAllocationCut.NORMALIZATION) {
            packedNormalizedCount = packed.count
            packedDirectionCacheKeys.fill(Long.MIN_VALUE)
            val pose = observation.frame.pose.worldFromCameraGl
            val cameraX = FeatureNormalEvidence.intMillimeters(pose[12])
                ?: return@allocate Normalization.Refused(FeatureFusionRefusal.INVALID_NORMAL_EVIDENCE)
            val cameraY = FeatureNormalEvidence.intMillimeters(pose[13])
                ?: return@allocate Normalization.Refused(FeatureFusionRefusal.INVALID_NORMAL_EVIDENCE)
            val cameraZ = FeatureNormalEvidence.intMillimeters(pose[14])
                ?: return@allocate Normalization.Refused(FeatureFusionRefusal.INVALID_NORMAL_EVIDENCE)
            for (index in 0 until packed.count) {
                val sampleXWorld = packed.xWorldAt(index)
                val sampleYWorld = packed.yWorldAt(index)
                val sampleZWorld = packed.zWorldAt(index)
                val x = quantize(sampleXWorld)
                    ?: return@allocate Normalization.Refused(quantizationRefusal(sampleXWorld))
                val y = quantize(sampleYWorld)
                    ?: return@allocate Normalization.Refused(quantizationRefusal(sampleYWorld))
                val z = quantize(sampleZWorld)
                    ?: return@allocate Normalization.Refused(quantizationRefusal(sampleZWorld))
                val sampleX = FeatureNormalEvidence.intMillimeters(sampleXWorld)
                    ?: return@allocate Normalization.Refused(FeatureFusionRefusal.INVALID_NORMAL_EVIDENCE)
                val sampleY = FeatureNormalEvidence.intMillimeters(sampleYWorld)
                    ?: return@allocate Normalization.Refused(FeatureFusionRefusal.INVALID_NORMAL_EVIDENCE)
                val sampleZ = FeatureNormalEvidence.intMillimeters(sampleZWorld)
                    ?: return@allocate Normalization.Refused(FeatureFusionRefusal.INVALID_NORMAL_EVIDENCE)
                val confidence = FeatureNormalEvidence.q15(packed.confidenceAt(index))
                    ?: return@allocate Normalization.Refused(FeatureFusionRefusal.INVALID_NORMAL_EVIDENCE)
                if (sampleX == cameraX && sampleY == cameraY && sampleZ == cameraZ) {
                    return@allocate Normalization.Refused(FeatureFusionRefusal.INVALID_NORMAL_EVIDENCE)
                }
                packedDirectionScratch.fill(0)
                if (!FeatureNormalMath.normalizeQ15Into(
                        cameraX.toLong() - sampleX,
                        cameraY.toLong() - sampleY,
                        cameraZ.toLong() - sampleZ,
                        packedDirectionScratch,
                    )
                ) return@allocate Normalization.Refused(FeatureFusionRefusal.INVALID_NORMAL_EVIDENCE)
                packedNegatedDirectionScratch[0] = -packedDirectionScratch[0]
                packedNegatedDirectionScratch[1] = -packedDirectionScratch[1]
                packedNegatedDirectionScratch[2] = -packedDirectionScratch[2]
                val cacheKey = packedDirectionKey(packedDirectionScratch)
                val cacheSlot = findPackedDirectionCacheSlot(cacheKey)
                val directCode: Int
                val oppositeCode: Int
                if (packedDirectionCacheKeys[cacheSlot] == Long.MIN_VALUE) {
                    directCode = normalEncoder.encode(packedDirectionScratch)
                    oppositeCode = normalEncoder.encode(packedNegatedDirectionScratch)
                    packedDirectionCacheKeys[cacheSlot] = cacheKey
                    packedDirectionCacheDirect[cacheSlot] = directCode
                    packedDirectionCacheOpposite[cacheSlot] = oppositeCode
                } else {
                    directCode = packedDirectionCacheDirect[cacheSlot]
                    oppositeCode = packedDirectionCacheOpposite[cacheSlot]
                }
                val positive = directCode <= oppositeCode
                val direction = if (positive) packedDirectionScratch else packedNegatedDirectionScratch
                packedVoxelX[index] = x
                packedVoxelY[index] = y
                packedVoxelZ[index] = z
                packedSupportIds[index] = packed.idAt(index)
                packedSignedWeights[index] = 2
                packedAxisXQ15[index] = direction[0]
                packedAxisYQ15[index] = direction[1]
                packedAxisZQ15[index] = direction[2]
                packedPositiveSide[index] = positive
                packedSupportQ13[index] = FeatureNormalMath.confidenceQ13(confidence)
                packedOrder[index] = index
            }
            sortPackedOrder()
            Normalization.Accepted(packedNormalizedEvidence)
        }
    }

    private fun sortPackedOrder() {
        for (index in 1 until packedNormalizedCount) {
            val value = packedOrder[index]
            var insertion = index
            while (insertion > 0 && comparePacked(value, packedOrder[insertion - 1]) < 0) {
                packedOrder[insertion] = packedOrder[insertion - 1]
                insertion--
            }
            packedOrder[insertion] = value
        }
    }

    private fun comparePacked(left: Int, right: Int): Int {
        fun compare(a: Int, b: Int): Int = a.compareTo(b)
        compare(packedVoxelX[left], packedVoxelX[right]).takeIf { it != 0 }?.let { return it }
        compare(packedVoxelY[left], packedVoxelY[right]).takeIf { it != 0 }?.let { return it }
        compare(packedVoxelZ[left], packedVoxelZ[right]).takeIf { it != 0 }?.let { return it }
        compare(packedSupportIds[left], packedSupportIds[right]).takeIf { it != 0 }?.let { return it }
        compare(packedSignedWeights[left], packedSignedWeights[right]).takeIf { it != 0 }?.let { return it }
        compare(packedAxisXQ15[left], packedAxisXQ15[right]).takeIf { it != 0 }?.let { return it }
        compare(packedAxisYQ15[left], packedAxisYQ15[right]).takeIf { it != 0 }?.let { return it }
        compare(packedAxisZQ15[left], packedAxisZQ15[right]).takeIf { it != 0 }?.let { return it }
        packedPositiveSide[left].compareTo(packedPositiveSide[right]).takeIf { it != 0 }?.let { return it }
        return compare(packedSupportQ13[left], packedSupportQ13[right])
    }

    private fun packedDirectionKey(vector: IntArray): Long =
        ((vector[0].toLong() and 0xffffL) shl 32) or
            ((vector[1].toLong() and 0xffffL) shl 16) or
            (vector[2].toLong() and 0xffffL)

    private fun findPackedDirectionCacheSlot(key: Long): Int {
        var slot = packedDirectionHash(key) and (PACKED_DIRECTION_CACHE_CAPACITY - 1)
        while (true) {
            val current = packedDirectionCacheKeys[slot]
            if (current == Long.MIN_VALUE || current == key) return slot
            slot = (slot + 1) and (PACKED_DIRECTION_CACHE_CAPACITY - 1)
        }
    }

    private fun packedDirectionHash(value: Long): Int {
        var mixed = value xor (value ushr 33)
        mixed *= -49064778989728563L
        mixed = mixed xor (mixed ushr 33)
        return mixed.toInt()
    }

    private fun stage(evidence: List<NormalizedEvidence>, sequence: Long, timestampNs: Long): Staging = try {
        operations.allocate(FeatureFusionAllocationCut.PREFLIGHT) {
            val nextAssociations = addOrRefuse(associationCount, evidence.size)
                ?: return@allocate Staging.Refused(FeatureFusionRefusal.CHECKED_ARITHMETIC)
            if (nextAssociations > ASSOCIATION_CAPACITY) {
                return@allocate Staging.Refused(FeatureFusionRefusal.ASSOCIATION_CAPACITY)
            }

            val updatesByKey = LinkedHashMap<VoxelKey, ProjectedSurface>()
            val associations = ArrayList<ProjectedAssociation>(evidence.size)
            var projectedSurfaceCount = surfaceCount
            evidence.forEach { item ->
                var projected = updatesByKey[item.key]
                if (projected == null) {
                    val existing = findSlot(item.key)
                    if (existing >= 0) {
                        if (canonicalIds[existing] != 0 &&
                            (canonicalCorrelation(existing) == null ||
                                featureEvidenceAllocationFingerprint(existing) == null)
                        ) return@allocate Staging.Refused(FeatureFusionRefusal.CANONICAL_PROVENANCE)
                        projected = ProjectedSurface(existing, item.key, retainedWeight(existing), observationCount(observationCounts[existing]), active[existing], false,
                            axisXQ13[existing], axisYQ13[existing], axisZQ13[existing], positiveSupportQ13[existing], negativeSupportQ13[existing],
                            primarySide(observationCounts[existing]))
                    } else {
                        val next = addOrRefuse(projectedSurfaceCount, 1)
                            ?: return@allocate Staging.Refused(FeatureFusionRefusal.CHECKED_ARITHMETIC)
                        if (next > SURFACE_CAPACITY) {
                            return@allocate Staging.Refused(FeatureFusionRefusal.SURFACE_CAPACITY)
                        }
                        projected = ProjectedSurface(projectedSurfaceCount, item.key, 0, 0, false, true, 0, 0, 0, 0, 0, FeaturePrimarySide.NONE)
                        projectedSurfaceCount = next
                    }
                }
                val sum = addOrRefuse(projected.weight, item.signedWeight)
                    ?: return@allocate Staging.Refused(FeatureFusionRefusal.CHECKED_ARITHMETIC)
                val count = addOrRefuse(projected.observationCount, 1)
                    ?: return@allocate Staging.Refused(FeatureFusionRefusal.CHECKED_ARITHMETIC)
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

            operations.allocate(FeatureFusionAllocationCut.RESULT) {
                // The result is deliberately derived only from the distinct voxels
                // staged by this batch.  Retained arrays remain the sole complete
                // candidate-A state; scanning them here would make a one-voxel
                // refinement proportional to the live population.
                val delta = ArrayList<FeatureFusionChange>(updatesByKey.size)
                // Batch-local only. Capacity campaigns commonly share an exact
                // accumulated axis; exhaustively encode each distinct Q15 axis
                // once without retaining an estimator cache in kernel state.
                val axisOctCodes = HashMap<DirectionKey, Pair<Int, Int>>()
                updatesByKey.values.forEach { projected ->
                    val wasActive = !projected.isNew && active[projected.slot]
                    when {
                        projected.isActive && (!wasActive || hasMaterialChange(projected, axisOctCodes)) -> {
                            val correlation = canonicalCorrelation(projected.slot)
                            if (canonicalIds[projected.slot] != 0 && correlation == null) {
                                return@allocate Staging.Refused(FeatureFusionRefusal.CANONICAL_PROVENANCE)
                            }
                            delta += FeatureFusionChange.Upsert(
                                candidate(projected, axisOctCodes),
                                projected.slot,
                                correlation,
                            )
                        }
                        wasActive && !projected.isActive ->
                            delta += FeatureFusionChange.Removal(projected.key.x, projected.key.y, projected.key.z)
                    }
                }
                delta.sortWith(compareBy<FeatureFusionChange> { it.x }.thenBy { it.y }.thenBy { it.z })
                val result = FeatureFusionResult.Accepted(
                    Collections.unmodifiableList(delta),
                    receipt(projectedSurfaceCount, nextAssociations),
                    FeatureFusionWorkReceipt(
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
        Staging.Refused(FeatureFusionRefusal.CHECKED_ARITHMETIC)
    }

    private fun candidate(surface: ProjectedSurface, axisOctCodes: MutableMap<DirectionKey, Pair<Int, Int>>) = FeatureFusionCandidate(
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
        if (surface.primarySide != FeaturePrimarySide.NONE || surface.positiveSupportQ13 == surface.negativeSupportQ13) return surface
        val positive = normalConfidence(surface.positiveSupportQ13)
        val negative = normalConfidence(surface.negativeSupportQ13)
        val dominant = if (surface.positiveSupportQ13 > surface.negativeSupportQ13) {
            FeaturePrimarySide.POSITIVE to positive
        } else {
            FeaturePrimarySide.NEGATIVE to negative
        }
        return if (dominant.second >= 64) surface.copy(primarySide = dominant.first) else surface
    }

    private fun observationCount(encoded: Int): Int = encoded and OBSERVATION_COUNT_MASK
    private fun primarySide(encoded: Int): FeaturePrimarySide = when (encoded ushr OBSERVATION_PRIMARY_SHIFT) {
        0 -> FeaturePrimarySide.NONE
        1 -> FeaturePrimarySide.POSITIVE
        2 -> FeaturePrimarySide.NEGATIVE
        else -> error("invalid retained primary-side state")
    }
    private fun encodeObservationState(count: Int, side: FeaturePrimarySide): Int {
        require(count in 0..OBSERVATION_COUNT_MASK)
        return count or (side.code shl OBSERVATION_PRIMARY_SHIFT)
    }

    private fun insertAt(slot: Int, key: VoxelKey) {
        check(slot == surfaceCount && surfaceCount < SURFACE_CAPACITY)
        var bucket = hash(key)
        while (hashSlots[bucket] != 0) bucket = (bucket + 1) and HASH_MASK
        hashSlots[bucket] = slot + 1
        surfaceKeys[slot] = packVisibilityGridKey(key.x, key.y, key.z)
        surfaceCount++
    }

    private fun findSlot(key: VoxelKey): Int {
        val packed = packVisibilityGridKey(key.x, key.y, key.z)
        var bucket = hash(key)
        repeat(HASH_SLOTS) {
            val encoded = hashSlots[bucket]
            if (encoded == 0) return -1
            val slot = encoded - 1
            if (surfaceKeys[slot] == packed) return slot
            bucket = (bucket + 1) and HASH_MASK
        }
        return -1
    }

    /**
     * Installs durable canonical identities only after their adjacent commit
     * has succeeded. Validation is complete before retained state is touched,
     * so a refusal cannot leave a partially correlated kernel.
     */
    @Synchronized
    internal fun assignCanonicalCorrelations(assignments: List<CanonicalFeatureAssignment>): Boolean {
        if (assignments.isEmpty()) return true
        val seenSlots = HashSet<Int>()
        for (assignment in assignments) {
            val slot = assignment.kernelSlot
            if (slot !in 0 until surfaceCount || !seenSlots.add(slot) ||
                surfaceKeys[slot] != packVisibilityGridKey(assignment.x, assignment.y, assignment.z) ||
                assignment.id.value !in 1L..UINT32_MASK || assignment.allocationFingerprint.size != HASH_BYTES ||
                assignment.packedNormal !in 0..0xffff || assignment.normalConfidence !in 0..255
            ) return false
            val encoded = assignment.id.value.toInt()
            val retained = canonicalIds[slot]
            if (retained != 0 && retained != encoded) return false
        }
        for (assignment in assignments) {
            if (canonicalIds[assignment.kernelSlot] == 0) {
                canonicalIds[assignment.kernelSlot] = assignment.id.value.toInt()
            }
            if (featureEvidenceSourceIds[assignment.kernelSlot] == 0) {
                featureEvidenceSourceIds[assignment.kernelSlot] = assignment.id.value.toInt()
                rememberFixtureHistoricalFingerprint(assignment.kernelSlot, assignment.allocationFingerprint)
            }
            accumulatedWeights[assignment.kernelSlot] = encodeWeightAndCanonical(
                retainedWeight(assignment.kernelSlot), assignment.packedNormal, assignment.normalConfidence,
            )
            currentFingerprintSourceIds[assignment.kernelSlot] = assignment.id.value.toInt()
            rememberFixtureCurrentFingerprint(assignment.kernelSlot, assignment.allocationFingerprint)
        }
        return true
    }

    /** Hydrates one cold-recovery row before ordinary admissions resume. */
    internal fun hydrateCanonicalSurface(row: CompactSurface, fingerprint: CanonicalReceiptBytes): Boolean {
        if (row.id.value !in 1..UINT32_MASK || fingerprint.size != HASH_BYTES ||
            row.packedNormal !in 0..0xffff || row.normalConfidence !in 0..255
        ) return false
        fingerprintResolver?.let { resolver ->
            if (resolveCanonicalFingerprint(resolver, row.id.value, fingerprintGeometryRevision,
                    fingerprintLineageRevision, fingerprintGeneration) != fingerprint
            ) return false
        }
        val key = VoxelKey(row.voxel.x, row.voxel.y, row.voxel.z)
        if (findSlot(key) >= 0 || surfaceCount >= SURFACE_CAPACITY) return false
        val octX = (row.packedNormal ushr 8).toByte().toInt()
        val octY = row.packedNormal.toByte().toInt()
        val decoded = FeatureNormalMath.decodeOct(octX, octY) ?: return false
        val slot = surfaceCount
        insertAt(slot, key)
        accumulatedWeights[slot] = encodeWeightAndCanonical(
            OCCUPANCY_THRESHOLD, row.packedNormal, row.normalConfidence,
        )
        observationCounts[slot] = encodeObservationState(0, FeaturePrimarySide.POSITIVE)
        active[slot] = true
        // Only the canonical octant survives restart. Seed a deterministic
        // bounded prior strong enough that replaying one retained sample cannot
        // manufacture a different octant from quantization noise.
        axisXQ13[slot] = Math.multiplyExact(decoded[0], HYDRATED_AXIS_SCALE)
        axisYQ13[slot] = Math.multiplyExact(decoded[1], HYDRATED_AXIS_SCALE)
        axisZQ13[slot] = Math.multiplyExact(decoded[2], HYDRATED_AXIS_SCALE)
        positiveSupportQ13[slot] = FeatureNormalMath.roundTiesEven(
            row.normalConfidence.toLong() * 4L * 8192L, 255L,
        ).toInt()
        negativeSupportQ13[slot] = 0
        return assignCanonicalCorrelations(listOf(
            CanonicalFeatureAssignment(
                slot, row.voxel.x, row.voxel.y, row.voxel.z, row.id, fingerprint,
                row.packedNormal, row.normalConfidence,
            ),
        ))
    }

    internal fun canonicalCorrelation(kernelSlot: Int): CanonicalFeatureCorrelation? {
        if (kernelSlot !in 0 until surfaceCount) return null
        val encodedId = canonicalIds[kernelSlot]
        if (encodedId == 0) return null
        val fingerprint = resolveCurrentFingerprint(kernelSlot, currentFingerprintSourceIds[kernelSlot]) ?: return null
        return CanonicalFeatureCorrelation(
            SurfaceId(encodedId.toLong() and UINT32_MASK),
            fingerprint,
            retainedPackedNormal(kernelSlot),
            retainedConfidence(kernelSlot),
        )
    }

    /** Immutable allocation provenance recorded when this feature slot first gained canonical identity. */
    internal fun featureEvidenceAllocationFingerprint(kernelSlot: Int): CanonicalReceiptBytes? {
        if (kernelSlot !in 0 until surfaceCount || canonicalIds[kernelSlot] == 0) return null
        return resolveHistoricalFingerprint(kernelSlot, featureEvidenceSourceIds[kernelSlot])
    }

    private fun resolveCurrentFingerprint(kernelSlot: Int, encodedSourceId: Int): CanonicalReceiptBytes? {
        if (encodedSourceId == 0) return null
        val sourceId = encodedSourceId.toLong() and UINT32_MASK
        val resolver = fingerprintResolver
        return if (resolver == null) {
            fixtureCurrentFingerprintBacking[kernelSlot]
        } else {
            resolveCanonicalFingerprint(resolver,
                sourceId,
                fingerprintGeometryRevision,
                fingerprintLineageRevision,
                fingerprintGeneration,
            )
        }
    }

    private fun resolveHistoricalFingerprint(kernelSlot: Int, encodedSourceId: Int): CanonicalReceiptBytes? {
        if (encodedSourceId == 0) return null
        val sourceId = encodedSourceId.toLong() and UINT32_MASK
        val resolver = fingerprintResolver
        return if (resolver == null) {
            fixtureHistoricalFingerprintBacking[kernelSlot]
        } else {
            resolveCanonicalFingerprint(resolver,
                sourceId,
                fingerprintGeometryRevision,
                fingerprintLineageRevision,
                fingerprintGeneration,
            )
        }
    }

    private fun resolveCanonicalFingerprint(
        resolver: CanonicalFingerprintResolver,
        sourceId: Long,
        geometryRevision: Long,
        lineageRevision: Long,
        generation: Long,
    ): CanonicalReceiptBytes? = try {
        resolver.resolve(sourceId, geometryRevision, lineageRevision, generation)
            ?.takeIf { it.size == HASH_BYTES }
    } catch (_: Exception) {
        // An unavailable/corrupt authority refuses preparation. No canonical
        // write or retained feature mutation has occurred at this boundary.
        null
    }

    private fun rememberFixtureCurrentFingerprint(kernelSlot: Int, fingerprint: CanonicalReceiptBytes) {
        if (fingerprintResolver == null) {
            require(fingerprint.size == HASH_BYTES)
            fixtureCurrentFingerprintBacking[kernelSlot] = fingerprint
        }
    }

    private fun rememberFixtureHistoricalFingerprint(kernelSlot: Int, fingerprint: CanonicalReceiptBytes) {
        if (fingerprintResolver == null) {
            require(fingerprint.size == HASH_BYTES)
            fixtureHistoricalFingerprintBacking.putIfAbsent(kernelSlot, fingerprint)
        }
    }

    /** Resolves one retained feature slot by its exact voxel and canonical identity. */
    @Synchronized
    internal fun canonicalFeatureSlot(voxel: Voxel, expectedSurfaceId: SurfaceId): Int? {
        val slot = findSlot(VoxelKey(voxel.x, voxel.y, voxel.z))
        if (slot < 0) return null
        val encodedId = canonicalIds[slot].toLong() and UINT32_MASK
        return slot.takeIf { encodedId == expectedSurfaceId.value }
    }

    private fun retainedWeight(slot: Int) = accumulatedWeights[slot].toByte().toInt()
    private fun withWeight(encoded: Int, weight: Int) = (encoded and -0x100) or (weight and 0xff)
    private fun retainedPackedNormal(slot: Int) = (accumulatedWeights[slot] ushr 8) and 0xffff
    private fun retainedConfidence(slot: Int): Int = (accumulatedWeights[slot] ushr 24) and 0xff
    private fun encodeWeightAndCanonical(weight: Int, packedNormal: Int, confidence: Int) =
        (weight and 0xff) or ((packedNormal and 0xffff) shl 8) or
            ((confidence.also { require(it in 0..255) } and 0xff) shl 24)

    private fun quantize(meters: Double): Int? {
        if (!meters.isFinite()) return null
        val voxel = floor(meters / VOXEL_METERS)
        if (!voxel.isFinite() || voxel < VOXEL_MIN || voxel > VOXEL_MAX) return null
        return voxel.toInt()
    }

    private fun quantizationRefusal(meters: Double) =
        if (meters.isFinite()) FeatureFusionRefusal.COORDINATE_OUT_OF_RANGE else FeatureFusionRefusal.NON_FINITE_COORDINATE

    private fun addOrRefuse(left: Int, right: Int): Int? = try {
        operations.addExact(left, right)
    } catch (_: ArithmeticException) {
        null
    }

    private fun refused(reason: FeatureFusionRefusal) = FeatureFusionResult.Refused(reason, receipt())
    internal fun resourceReceipt(): FeatureFusionResourceReceipt = receipt()
    private fun receipt(surfaces: Int = surfaceCount, associations: Int = associationCount) =
        FeatureFusionResourceReceipt(surfaces, associations, retainedOwnerBytes())

    /** Portable retained ownership: concrete array capacities plus the scalar owner/header. */
    internal fun normalEncoderPortableBytes(): Int = normalEncoder.portableBytes()

    // Kernel fields, two empty fixture maps, operation adapter and alignment;
    // the independent constructed-graph regression verifies this envelope.
    private fun retainedOwnerBytes(): Int = 384 + normalEncoder.portableBytes() +
        32 * ARRAY_HEADER_BYTES.toInt() +
        surfaceKeys.size * Long.SIZE_BYTES +
        (fixtureCurrentFingerprintBacking.size + fixtureHistoricalFingerprintBacking.size) *
            256 + // Fixture-only map nodes, keys, receipt bytes and backing slots.
        active.size + Int.SIZE_BYTES * (
            canonicalIds.size + currentFingerprintSourceIds.size + featureEvidenceSourceIds.size +
            accumulatedWeights.size + observationCounts.size +
                axisXQ13.size + axisYQ13.size + axisZQ13.size + positiveSupportQ13.size +
                negativeSupportQ13.size + evidenceWeights.size + evidenceSupportIds.size +
                associationSlots.size + hashSlots.size +
                packedVoxelX.size + packedVoxelY.size + packedVoxelZ.size + packedSupportIds.size +
                packedSignedWeights.size + packedAxisXQ15.size + packedAxisYQ15.size +
                packedAxisZQ15.size + packedSupportQ13.size + packedOrder.size +
                packedDirectionCacheDirect.size + packedDirectionCacheOpposite.size +
                packedDirectionScratch.size + packedNegatedDirectionScratch.size
            ) + packedPositiveSide.size + packedDirectionCacheKeys.size * Long.SIZE_BYTES

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
    ): List<FeatureNormalCandidate> {
        val axis = FeatureNormalMath.normalizeQ15(surface.axisXQ13.toLong(), surface.axisYQ13.toLong(), surface.axisZQ13.toLong())
            ?: return emptyList()
        val codes = axisOctCodes.getOrPut(DirectionKey(axis[0], axis[1], axis[2])) {
            normalEncoder.encode(axis) to normalEncoder.encode(FeatureNormalMath.negated(axis))
        }
        val axisCode = codes.first
        val opposite = codes.second
        val positive = surface.positiveSupportQ13
        val negative = surface.negativeSupportQ13
        if (positive == negative) {
            val chosen = minOf(axisCode, opposite)
            return listOf(FeatureNormalCandidate(surface.key.x, surface.key.y, surface.key.z, FeatureNormalFace.PRIMARY,
                (chosen ushr 8).toByte().toInt(), chosen.toByte().toInt(), 0))
        }
        val positiveConfidence = normalConfidence(positive)
        val negativeConfidence = normalConfidence(negative)
        val bothReliable = positiveConfidence >= 64 && negativeConfidence >= 64
        val primaryPositive = when (surface.primarySide) {
            FeaturePrimarySide.POSITIVE -> true
            FeaturePrimarySide.NEGATIVE -> false
            FeaturePrimarySide.NONE -> positive > negative
        }
        val primaryCode = if (primaryPositive) axisCode else opposite
        val primaryConfidence = if (bothReliable) {
            if (primaryPositive) positiveConfidence else negativeConfidence
        } else {
            normalConfidence(kotlin.math.abs(positive - negative))
        }
        val primary = FeatureNormalCandidate(surface.key.x, surface.key.y, surface.key.z, FeatureNormalFace.PRIMARY,
            (primaryCode ushr 8).toByte().toInt(), primaryCode.toByte().toInt(), primaryConfidence)
        if (!bothReliable) return listOf(primary)
        val opposingCode = if (primaryPositive) opposite else axisCode
        return listOf(primary, FeatureNormalCandidate(surface.key.x, surface.key.y, surface.key.z, FeatureNormalFace.OPPOSING,
            (opposingCode ushr 8).toByte().toInt(), opposingCode.toByte().toInt(),
            if (primaryPositive) negativeConfidence else positiveConfidence))
            .sortedBy { ((it.normalOctX and 0xff) shl 8) or (it.normalOctY and 0xff) }
    }

    private fun normalConfidence(support: Int): Int =
        FeatureNormalMath.roundTiesEven(support.toLong() * 255L, 4L * 8192L).coerceIn(0L, 255L).toInt()

    private data class VoxelKey(val x: Int, val y: Int, val z: Int)
    private data class DirectionKey(val x: Int, val y: Int, val z: Int)
    private data class NormalizedEvidence(val key: VoxelKey, val signedWeight: Int, val supportId: Int, val axisDirectionQ15: IntArray, val positiveSide: Boolean, val supportQ13: Int)
    private inner class PackedNormalizedEvidenceList : AbstractList<NormalizedEvidence>() {
        override val size: Int get() = packedNormalizedCount

        override fun get(index: Int): NormalizedEvidence {
            require(index in 0 until packedNormalizedCount)
            val source = packedOrder[index]
            return NormalizedEvidence(
                VoxelKey(packedVoxelX[source], packedVoxelY[source], packedVoxelZ[source]),
                packedSignedWeights[source],
                packedSupportIds[source],
                intArrayOf(packedAxisXQ15[source], packedAxisYQ15[source], packedAxisZQ15[source]),
                packedPositiveSide[source],
                packedSupportQ13[source],
            )
        }
    }
    private data class ProjectedAssociation(val slot: Int, val weight: Int, val supportId: Int)
    private data class ProjectedSurface(
        val slot: Int, val key: VoxelKey, val weight: Int, val observationCount: Int, val isActive: Boolean, val isNew: Boolean,
        val axisXQ13: Int, val axisYQ13: Int, val axisZQ13: Int, val positiveSupportQ13: Int, val negativeSupportQ13: Int,
        val primarySide: FeaturePrimarySide,
    ) {
        fun addNormal(evidence: NormalizedEvidence): ProjectedSurface {
            fun contribution(component: Int) = Math.toIntExact(FeatureNormalMath.roundTiesEven(component.toLong() * evidence.supportQ13, 32_767L))
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
        data class Refused(val reason: FeatureFusionRefusal) : Normalization
    }
    private sealed interface Staging {
        data class Accepted(
            val updates: List<ProjectedSurface>,
            val newSurfaces: List<ProjectedSurface>,
            val associations: List<ProjectedAssociation>,
            val result: FeatureFusionResult.Accepted,
        ) : Staging
        data class Refused(val reason: FeatureFusionRefusal) : Staging
    }
    private class PendingApplication(
        val staged: Staging.Accepted,
        val sequence: Long,
        val timestampNs: Long,
    ) {
        var assignments: List<CanonicalFeatureAssignment>? = null
        var canonicalFingerprints: ByteArray? = null
    }

    private class PendingCanonicalRemap(
        val slots: IntArray,
        val targetIds: LongArray,
        val hasCanonicalMetadata: BooleanArray,
        val fingerprints: ByteArray,
        val packedNormals: IntArray,
        val normalConfidences: IntArray,
    )

    private sealed interface CanonicalRemapStage {
        data class Accepted(val packet: PendingCanonicalRemap) : CanonicalRemapStage
        data class Refused(val reason: FeatureCanonicalRemapRefusal) : CanonicalRemapStage
    }

    // The normalized voxel domain is exactly three signed 21-bit coordinates.
    // Sharing the renderer's canonical packing frees one primitive column for
    // the exact uint32 canonical identity without growing the tuple payload.
    private val surfaceKeys = LongArray(SURFACE_CAPACITY)
    private val canonicalIds = IntArray(SURFACE_CAPACITY)
    // Canonical association provenance is mutable after replacement, while the
    // per-slot feature-evidence provenance is installed once and stays immutable.
    /** Source whose exact receipt the current canonical identity uses. */
    private val currentFingerprintSourceIds = IntArray(SURFACE_CAPACITY)
    /** Immutable source identity from the first canonical assignment. */
    private val featureEvidenceSourceIds = IntArray(SURFACE_CAPACITY)
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
    private var pending: PendingApplication? = null
    private var pendingCanonicalRemap: PendingCanonicalRemap? = null

    companion object {
        private const val ARRAY_HEADER_BYTES = 16L

        internal fun maximumPendingCanonicalRemapPrimitiveBytes(): Long =
            canonicalRemapPrimitiveBytes(SURFACE_CAPACITY)

        private fun canonicalRemapPrimitiveBytes(count: Int): Long {
            require(count in 0..SURFACE_CAPACITY)
            val slots = Math.addExact(ARRAY_HEADER_BYTES, Math.multiplyExact(count.toLong(), Int.SIZE_BYTES.toLong()))
            val targets = Math.addExact(ARRAY_HEADER_BYTES, Math.multiplyExact(count.toLong(), Long.SIZE_BYTES.toLong()))
            val flags = Math.addExact(ARRAY_HEADER_BYTES, count.toLong())
            val fingerprints = Math.addExact(ARRAY_HEADER_BYTES, Math.multiplyExact(count.toLong(), HASH_BYTES.toLong()))
            val metadata = Math.multiplyExact(
                2L, Math.addExact(ARRAY_HEADER_BYTES, Math.multiplyExact(count.toLong(), Int.SIZE_BYTES.toLong())),
            )
            return listOf(slots, targets, flags, fingerprints, metadata).fold(0L, Math::addExact)
        }

        const val SURFACE_CAPACITY = 100_000
        const val ASSOCIATION_CAPACITY = 200_000
        const val HASH_SLOTS = 262_144
        const val HASH_MASK = HASH_SLOTS - 1
        // Fixed owner receipt after accounting for every array header and the
        // two reusable three-component direction scratch buffers.
        const val CANONICAL_SURFACE_TUPLE_SHARE_BYTES = 9_371_384
        const val HASH_BYTES = 32
        const val UINT32_MASK = 0xffff_ffffL
        const val HYDRATED_AXIS_SCALE = 1_024
        const val OCCUPANCY_THRESHOLD = 2
        const val DEACTIVATION_THRESHOLD = 1
        const val EVIDENCE_SATURATION = 127
        const val OBSERVATION_COUNT_MASK = (1 shl 18) - 1
        const val OBSERVATION_PRIMARY_SHIFT = 30
        private const val PACKED_DIRECTION_CACHE_CAPACITY = 2_048
        const val VOXEL_METERS = 0.1
        const val VOXEL_MIN = -(1 shl 20).toDouble()
        const val VOXEL_MAX = ((1 shl 20) - 1).toDouble()
    }
}

private enum class FeaturePrimarySide(val code: Int) { NONE(0), POSITIVE(1), NEGATIVE(2) }

/** Internal seam: production and fault-injected adapters stage the same work. */
internal interface FeatureFusionOperations {
    fun <T> allocate(cut: FeatureFusionAllocationCut, block: () -> T): T
    fun addExact(left: Int, right: Int): Int
}

internal object JvmFeatureFusionOperations : FeatureFusionOperations {
    override fun <T> allocate(cut: FeatureFusionAllocationCut, block: () -> T): T = block()
    override fun addExact(left: Int, right: Int): Int = Math.addExact(left, right)
}

internal enum class FeatureFusionAllocationCut { NORMALIZATION, PREFLIGHT, RESULT, CANONICAL_REMAP }
internal class FeatureFusionAllocationFailure : RuntimeException()

internal class FeatureFusionBatch(val sequence: Long, val timestampNs: Long, observations: List<FeatureFusionEvidence>) {
    val observations: List<FeatureFusionEvidence> = observations.toList()
}

internal data class FeatureFusionEvidence(
    val xMeters: Double, val yMeters: Double, val zMeters: Double, val signedWeight: Int, val supportId: Int,
    val normalEvidence: FeatureNormalEvidence? = null,
)

internal sealed interface FeatureFusionResult {
    /**
     * A deterministic, immutable delta for this admitted batch.  It contains
     * only touched voxels whose canonical candidate-A state changed: upserts
     * for activation/material refinement and removals for deactivation.
     */
    data class Accepted(
        val delta: List<FeatureFusionChange>,
        val receipt: FeatureFusionResourceReceipt,
        val work: FeatureFusionWorkReceipt,
    ) : FeatureFusionResult
    data class Refused(val reason: FeatureFusionRefusal, val receipt: FeatureFusionResourceReceipt) : FeatureFusionResult
}

internal enum class FeatureNormalFace { PRIMARY, OPPOSING }
internal data class FeatureNormalCandidate(
    val x: Int, val y: Int, val z: Int, val face: FeatureNormalFace,
    val normalOctX: Int, val normalOctY: Int, val normalConfidence: Int,
)
internal data class FeatureFusionCandidate(
    val x: Int, val y: Int, val z: Int, val weight: Int, val observationCount: Int,
    val normalCandidates: List<FeatureNormalCandidate>,
) {
    /** Compatibility-only diagnostic for the immutable Proposal 08 spike candidate-A oracle. */
    val normalOctant: Int get() = ((x.compareTo(0) shl 2) or (y.compareTo(0) shl 1) or z.compareTo(0)) and 7

    @Suppress("unused")
    constructor(x: Int, y: Int, z: Int, weight: Int, normalOctantDiagnostic: Int, observationCount: Int) :
        this(x, y, z, weight, observationCount, emptyList()) {
        require(normalOctantDiagnostic in 0..7)
    }
}
internal sealed interface FeatureFusionChange {
    val x: Int
    val y: Int
    val z: Int

    data class Upsert(
        val candidate: FeatureFusionCandidate,
        internal val kernelSlot: Int = -1,
        internal val canonicalCorrelation: CanonicalFeatureCorrelation? = null,
    ) : FeatureFusionChange {
        override val x: Int get() = candidate.x
        override val y: Int get() = candidate.y
        override val z: Int get() = candidate.z
    }

    data class Removal(override val x: Int, override val y: Int, override val z: Int) : FeatureFusionChange
}
internal data class CanonicalFeatureCorrelation(
    val id: SurfaceId,
    val allocationFingerprint: CanonicalReceiptBytes,
    val packedNormal: Int,
    val normalConfidence: Int,
)
internal data class CanonicalFeatureAssignment(
    val kernelSlot: Int,
    val x: Int,
    val y: Int,
    val z: Int,
    val id: SurfaceId,
    val allocationFingerprint: CanonicalReceiptBytes,
    val packedNormal: Int = 0,
    val normalConfidence: Int = 0,
)
internal data class CanonicalFeatureRemap(
    val featureSlot: Int,
    val previousSurfaceId: SurfaceId,
    val nextSurfaceId: SurfaceId?,
    val nextProvenance: CanonicalFeatureProvenance? = null,
)
internal data class CanonicalFeatureProvenance(
    val allocationFingerprint: CanonicalReceiptBytes,
    val packedNormal: Int,
    val normalConfidence: Int,
)
internal sealed interface FeatureCanonicalRemapPreparation {
    data class Prepared(val count: Int) : FeatureCanonicalRemapPreparation
    data class Refused(val reason: FeatureCanonicalRemapRefusal) : FeatureCanonicalRemapPreparation
}
internal enum class FeatureCanonicalRemapRefusal {
    CANONICAL_PROVENANCE,
    PREPARED_BUSY,
    CAPACITY,
    INVALID_SLOT,
    STALE_PREVIOUS_ID,
    DUPLICATE_SLOT,
    INVALID_ID,
    CHECKED_ARITHMETIC,
    ALLOCATION,
}
internal data class FeatureFusionResourceReceipt(val surfaceCount: Int, val associationCount: Int, val assignedTupleShareBytes: Int)
/** Scalar-only receipt for bounded output work; it never exposes retained rows. */
internal data class FeatureFusionWorkReceipt(val distinctTouchedVoxelCount: Int, val emittedEventCount: Int)

internal enum class FeatureFusionRefusal {
    CANONICAL_PROVENANCE,
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
