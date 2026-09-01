package com.uhg0.ar_flutter_plugin_2.visibilitygrid

import java.util.Collections
import kotlin.math.floor

/** Candidate-A feature fusion behind one state-owning admission interface. */
internal class FeatureFusionKernel(
    private val operations: FeatureFusionOperations = JvmFeatureFusionOperations,
) {
    /** Stages one immutable batch without changing retained state. */
    internal fun prepare(batch: FeatureFusionBatch): FeatureFusionResult {
        check(pending == null) { "a prepared kernel batch is already outstanding" }
        val staged = try {
            when (val normalization = normalize(batch)) {
                is Normalization.Refused -> return refused(normalization.reason)
                is Normalization.Accepted -> stage(normalization.evidence, batch)
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
            if (retained != 0 && (retained != assignment.id.value.toInt() ||
                    canonicalCorrelation(slot)?.allocationFingerprint != assignment.allocationFingerprint)
            ) return false
            copied += assignment
        }
        val sortedNew = copied.filter { (if (it.kernelSlot < surfaceCount) canonicalIds[it.kernelSlot] else 0) == 0 }
            .sortedWith { left, right -> java.lang.Integer.compareUnsigned(left.id.value.toInt(), right.id.value.toInt()) }
        var extendLast = false
        if (sortedNew.isNotEmpty()) {
            val firstId = sortedNew.first().id.value
            val lastId = sortedNew.last().id.value
            val priorFingerprint = if (allocationRangeCount == 0) null else {
                val offset = (allocationRangeCount - 1) * HASH_BYTES
                CanonicalReceiptBytes(allocationFingerprints.copyOfRange(offset, offset + HASH_BYTES))
            }
            extendLast = allocationRangeCount > 0 &&
                (allocationRangeEnds[allocationRangeCount - 1].toLong() and UINT32_MASK) + 1L == firstId &&
                priorFingerprint == sortedNew.first().allocationFingerprint
            if (lastId - firstId + 1L != sortedNew.size.toLong() ||
                sortedNew.any { it.allocationFingerprint != sortedNew.first().allocationFingerprint } ||
                (!extendLast && allocationRangeCount >= MAX_ALLOCATION_RANGES) ||
                (allocationRangeCount > 0 && java.lang.Integer.compareUnsigned(
                    allocationRangeEnds[allocationRangeCount - 1], firstId.toInt(),
                ) >= 0)
            ) return false
        }
        prepared.assignments = copied
        prepared.sortedNewAssignments = sortedNew
        prepared.extendLastRange = extendLast
        prepared.newRangeFingerprint = sortedNew.firstOrNull()?.allocationFingerprint?.toByteArray()
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
            observationCounts[update.slot] = encodeObservationState(update.observationCount, update.primarySide)
            active[update.slot] = update.isActive
            axisXQ13[update.slot] = update.axisXQ13
            axisYQ13[update.slot] = update.axisYQ13
            axisZQ13[update.slot] = update.axisZQ13
            positiveSupportQ13[update.slot] = update.positiveSupportQ13
            negativeSupportQ13[update.slot] = update.negativeSupportQ13
        }
        for (assignment in prepared.sortedNewAssignments) {
            canonicalIds[assignment.kernelSlot] = assignment.id.value.toInt()
        }
        for (assignment in assignments) {
            accumulatedWeights[assignment.kernelSlot] = encodeWeightAndCanonical(
                retainedWeight(assignment.kernelSlot), assignment.packedNormal, assignment.normalConfidence,
            )
        }
        if (prepared.sortedNewAssignments.isNotEmpty()) {
            if (prepared.extendLastRange) {
                allocationRangeEnds[allocationRangeCount - 1] = prepared.sortedNewAssignments.last().id.value.toInt()
            } else {
                allocationRangeStarts[allocationRangeCount] = prepared.sortedNewAssignments.first().id.value.toInt()
                allocationRangeEnds[allocationRangeCount] = prepared.sortedNewAssignments.last().id.value.toInt()
                requireNotNull(prepared.newRangeFingerprint).copyInto(
                    allocationFingerprints, allocationRangeCount * HASH_BYTES,
                )
                allocationRangeCount++
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
                    FeatureNormalMath.encodeOct(direction) to FeatureNormalMath.encodeOct(negated)
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

    private fun stage(evidence: List<NormalizedEvidence>, batch: FeatureFusionBatch): Staging = try {
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
                        projected.isActive && (!wasActive || hasMaterialChange(projected, axisOctCodes)) ->
                            delta += FeatureFusionChange.Upsert(
                                candidate(projected, axisOctCodes),
                                projected.slot,
                                canonicalCorrelation(projected.slot),
                            )
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
        val sortedNew = ArrayList<CanonicalFeatureAssignment>()
        val seenSlots = HashSet<Int>()
        for (assignment in assignments) {
            val slot = assignment.kernelSlot
            if (slot !in 0 until surfaceCount || !seenSlots.add(slot) ||
                surfaceKeys[slot] != packVisibilityGridKey(assignment.x, assignment.y, assignment.z)
            ) return false
            val encoded = assignment.id.value.toInt()
            val retained = canonicalIds[slot]
            if (retained == 0) {
                sortedNew += assignment
            } else if (retained != encoded ||
                canonicalCorrelation(slot)?.allocationFingerprint != assignment.allocationFingerprint
            ) return false
        }
        sortedNew.sortWith { left, right ->
            java.lang.Integer.compareUnsigned(left.id.value.toInt(), right.id.value.toInt())
        }
        var extendLast = false
        if (sortedNew.isNotEmpty()) {
            val firstId = sortedNew.first().id.value
            val lastId = sortedNew.last().id.value
            val priorFingerprint = if (allocationRangeCount == 0) null else {
                val offset = (allocationRangeCount - 1) * HASH_BYTES
                CanonicalReceiptBytes(allocationFingerprints.copyOfRange(offset, offset + HASH_BYTES))
            }
            extendLast = allocationRangeCount > 0 &&
                (allocationRangeEnds[allocationRangeCount - 1].toLong() and UINT32_MASK) + 1L == firstId &&
                priorFingerprint == sortedNew.first().allocationFingerprint
            if (lastId - firstId + 1L != sortedNew.size.toLong() ||
                sortedNew.any { it.allocationFingerprint != sortedNew.first().allocationFingerprint } ||
                (!extendLast && allocationRangeCount >= MAX_ALLOCATION_RANGES) ||
                (allocationRangeCount > 0 &&
                    java.lang.Integer.compareUnsigned(
                        allocationRangeEnds[allocationRangeCount - 1],
                        firstId.toInt(),
                    ) >= 0)
            ) return false
        }
        for (assignment in sortedNew) {
            canonicalIds[assignment.kernelSlot] = assignment.id.value.toInt()
        }
        for (assignment in assignments) {
            accumulatedWeights[assignment.kernelSlot] = encodeWeightAndCanonical(
                retainedWeight(assignment.kernelSlot), assignment.packedNormal, assignment.normalConfidence,
            )
        }
        if (sortedNew.isNotEmpty()) {
            if (extendLast) allocationRangeEnds[allocationRangeCount - 1] = sortedNew.last().id.value.toInt()
            else {
                allocationRangeStarts[allocationRangeCount] = sortedNew.first().id.value.toInt()
                allocationRangeEnds[allocationRangeCount] = sortedNew.last().id.value.toInt()
                sortedNew.first().allocationFingerprint.toByteArray().copyInto(
                    allocationFingerprints,
                    allocationRangeCount * HASH_BYTES,
                )
                allocationRangeCount++
            }
        }
        return true
    }

    /** Hydrates one cold-recovery row before ordinary admissions resume. */
    internal fun hydrateCanonicalSurface(row: CompactSurface, fingerprint: CanonicalReceiptBytes): Boolean {
        if (row.id.value !in 1..UINT32_MASK || fingerprint.size != HASH_BYTES) return false
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
        var low = 0
        var high = allocationRangeCount - 1
        while (low <= high) {
            val mid = (low + high) ushr 1
            when {
                java.lang.Integer.compareUnsigned(encodedId, allocationRangeStarts[mid]) < 0 -> high = mid - 1
                java.lang.Integer.compareUnsigned(encodedId, allocationRangeEnds[mid]) > 0 -> low = mid + 1
                else -> {
                    val offset = mid * HASH_BYTES
                    return CanonicalFeatureCorrelation(
                        SurfaceId(encodedId.toLong() and UINT32_MASK),
                        CanonicalReceiptBytes(allocationFingerprints.copyOfRange(offset, offset + HASH_BYTES)),
                        retainedPackedNormal(kernelSlot),
                        retainedConfidence(kernelSlot),
                    )
                }
            }
        }
        return null
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
        FeatureFusionResourceReceipt(surfaces, associations, CANONICAL_SURFACE_TUPLE_SHARE_BYTES)

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
            FeatureNormalMath.encodeOct(axis) to FeatureNormalMath.encodeOct(FeatureNormalMath.negated(axis))
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
        var sortedNewAssignments: List<CanonicalFeatureAssignment> = emptyList()
        var extendLastRange: Boolean = false
        var newRangeFingerprint: ByteArray? = null
    }

    // The normalized voxel domain is exactly three signed 21-bit coordinates.
    // Sharing the renderer's canonical packing frees one primitive column for
    // the exact uint32 canonical identity without growing the tuple payload.
    private val surfaceKeys = LongArray(SURFACE_CAPACITY)
    private val canonicalIds = IntArray(SURFACE_CAPACITY)
    private val allocationRangeStarts = IntArray(MAX_ALLOCATION_RANGES)
    private val allocationRangeEnds = IntArray(MAX_ALLOCATION_RANGES)
    private val allocationFingerprints = ByteArray(MAX_ALLOCATION_RANGES * HASH_BYTES)
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
    private var allocationRangeCount = 0
    private var lastSequence = Long.MIN_VALUE
    private var lastTimestampNs = Long.MIN_VALUE
    private var pending: PendingApplication? = null

    private companion object {
        const val SURFACE_CAPACITY = 100_000
        const val ASSOCIATION_CAPACITY = 200_000
        const val HASH_SLOTS = 262_144
        const val HASH_MASK = HASH_SLOTS - 1
        const val CANONICAL_SURFACE_TUPLE_SHARE_BYTES = 7_589_960
        const val MAX_ALLOCATION_RANGES = 1_024
        const val HASH_BYTES = 32
        const val UINT32_MASK = 0xffff_ffffL
        const val HYDRATED_AXIS_SCALE = 1_024
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

internal enum class FeatureFusionAllocationCut { NORMALIZATION, PREFLIGHT, RESULT }
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
internal data class FeatureFusionResourceReceipt(val surfaceCount: Int, val associationCount: Int, val assignedTupleShareBytes: Int)
/** Scalar-only receipt for bounded output work; it never exposes retained rows. */
internal data class FeatureFusionWorkReceipt(val distinctTouchedVoxelCount: Int, val emittedEventCount: Int)

internal enum class FeatureFusionRefusal {
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
