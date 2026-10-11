package com.uhg0.ar_flutter_plugin_2.visibilitygrid

import kotlin.math.abs
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sqrt

/**
 * Pure, bounded depth evidence admission.
 *
 * The kernel stages primitive evidence and canonical intentions without owning
 * canonical IDs, storage, rendering, or a platform callback. A caller may
 * apply the staged packet only after its canonical transaction succeeds.
 */
internal class DepthEvidenceKernel(
    internal val configuration: DepthEvidenceConfiguration = DepthEvidenceConfiguration(),
) : AutoCloseable, DepthCellPlaneScratch, CurrentEstimatePlaneSupportWords {
    private val estimatesEnabled = configuration.surfaceEstimationMode != DepthSurfaceEstimationMode.DISABLED
    private val localPlane = if (estimatesEnabled) DepthLocalPlaneEstimator(configuration.sampleCapacity) else null
    private val tableCapacity = configuration.surfaceCapacity
    private val hashCapacity = nextPowerOfTwo((tableCapacity * 2).coerceAtLeast(2))
    private val stageCapacity = minOf(
        tableCapacity,
        Math.addExact(configuration.rayVisitCapacity, configuration.sampleCapacity),
    )
    private val stageHashCapacity = nextPowerOfTwo((stageCapacity * 2).coerceAtLeast(2))
    private val supportCacheCapacity = supportCacheCapacityFor(stageCapacity)
    private var supportCacheCount = 0
    private var supportCacheOpen = false
    private var reuseFullFitAttempts = 0
    private var reuseFullFitSuccesses = 0
    private var reuseAttempts = 0
    private var reuseQualifiedPoints = 0
    private var reuseRefusals = 0
    private var reuseWitnessWork = 0
    private var reuseScratchWork = 0
    private var hashRows = IntArray(hashCapacity) { EMPTY_ROW }
    // A retained evidence row is exactly 32 bytes: voxel key (8), evidence
    // counters/directions/flags (7), source id (4), source voxel key (8),
    // packed normal/confidence/lineage (5). The byte columns are unsigned on
    // read; the canonical IDs and voxel keys are bounded to 32/63 bits.
    private var rowVoxelKey = LongArray(tableCapacity)
    private var rowOccupied = ByteArray(tableCapacity)
    private var rowFree = ByteArray(tableCapacity)
    private var rowDirections = IntArray(tableCapacity)
    private var rowFlags = ByteArray(tableCapacity)
    private var rowSourceId = IntArray(tableCapacity)
    private var rowSourceKey = LongArray(tableCapacity)
    private var rowPackedNormal = ShortArray(tableCapacity)
    private var rowNormalConfidence = ByteArray(tableCapacity)
    private var rowLineageCount = ShortArray(tableCapacity)
    // Optional millimetre offsets from addressed cell centre: six bytes per row.
    // Validity uses the existing flags byte; independent support uses occupancy.
    private var rowEstimateOffsetX = if (estimatesEnabled) ShortArray(tableCapacity) else null
    private var rowEstimateOffsetY = if (estimatesEnabled) ShortArray(tableCapacity) else null
    private var rowEstimateOffsetZ = if (estimatesEnabled) ShortArray(tableCapacity) else null

    // One fixed staging table is reused by every prepare. A batch can address
    // at most one endpoint per sample plus one row per ray visit, so staging is
    // bounded by that locked work budget rather than the larger model capacity.
    private var stageHashRows = IntArray(stageHashCapacity) { EMPTY_ROW }
    private var stageX = IntArray(stageCapacity)
    private var stageY = IntArray(stageCapacity)
    private var stageZ = IntArray(stageCapacity)
    private var stageOccupied = ByteArray(stageCapacity)
    private var stageFree = ByteArray(stageCapacity)
    private var stageDirections = IntArray(stageCapacity)
    private var stageContradicted = BooleanArray(stageCapacity)
    private var stageSourceId = IntArray(stageCapacity)
    private var stageSourceKey = LongArray(stageCapacity)
    private var stagePackedNormal = ShortArray(stageCapacity)
    private var stageNormalConfidence = ByteArray(stageCapacity)
    private var stageLineageCount = ShortArray(stageCapacity)
    private var stagePublished = BooleanArray(stageCapacity)
    private var stageHasOccupied = BooleanArray(stageCapacity)
    private var stageOccupiedPointX = DoubleArray(stageCapacity)
    private var stageOccupiedPointY = DoubleArray(stageCapacity)
    private var stageOccupiedPointZ = DoubleArray(stageCapacity)
    // Bit 0: retained fit; bit 1: current-frame fit already incorporated.
    // Existing representative columns hold the staged temporal position.
    private var stageEstimateState = if (estimatesEnabled) ByteArray(stageCapacity) else null
    private var stageFreeBin = ByteArray(stageCapacity) { NO_DIRECTION.toByte() }
    private var stageOrder = IntArray(stageCapacity)
    private var stageSortScratch = IntArray(stageCapacity)
    private var stageSortCounts = IntArray(RADIX_BUCKETS)
    // Coordinate lookup is dead before planning. Its 2x hash table becomes
    // the primary implicit-relation order, while these two stage-sized arrays
    // form one segmented radix destination for exactly two IDs per row.
    private var stageRelationTarget = IntArray(stageCapacity)
    private var stageRelationSource = IntArray(stageCapacity)
    private var stageRelationOrder = IntArray(stageCapacity)
    private var stageComponentOrder = IntArray(stageCapacity)
    private var stageComponentRank = IntArray(stageCapacity)
    private var stageComponent = IntArray(stageCapacity)
    private var stagePositive = BooleanArray(stageCapacity)
    private var stageRemoval = BooleanArray(stageCapacity)
    private var stageChangeKind = ByteArray(stageCapacity)
    private var stageChangeSource = IntArray(stageCapacity)
    private var stageChangeTarget = IntArray(stageCapacity)
    private var stageChangeComponent = IntArray(stageCapacity)
    private var stageKindCounts = IntArray(7)
    // Reused scratch for source IDs retired by the canonical mutation that
    // consumed the pending evidence packet.
    private var stageRetiredSourceIds = IntArray(tableCapacity)
    // Existing reserve: eight Int fields, two Boolean fields, one config reference and fieldless helper header.
    private val bulkCellCapacity = if (estimatesEnabled && configuration.planeFitStrategy == DepthPlaneFitStrategy.BULK_CELL_MOMENTS)
        Integer.highestOneBit(minOf(configuration.sampleCapacity, stageCapacity / 3, (3 * stageCapacity) / 34)) else 0
    private var bulkCellCount = 0
    private var bulkQualifiedCells = 0
    private var bulkCovarianceSolves = 0
    private var bulkCertificateAttempts = 0
    private var bulkResidualWork = 0
    private var bulkScratchWork = 0
    private var bulkProjectedSamples = 0
    private var bulkScratchOpen = false
    private var bulkWholeEstimateUnrefined = false
    private var stageAssociationWork = 0
    private var stageAssociatedSamples = 0
    private var stageIndependentPlaneFits = 0
    private var stageCount = 0
    private var stageRelationCount = 0
    private var stageRelationAdmissionWork = 0
    private var stageOrderedSourceCount = 0
    private var stageOrderedTargetCount = 0
    private var stageChangeCount = 0
    private val canonicalScratch = CanonicalSurfaceScratch()
    private val canonicalSourceScratch = CanonicalSurfaceScratch()

    private var residentRows = 0
    private var lastSequence = Long.MIN_VALUE
    private var lastTimestampNs = Long.MIN_VALUE
    private var activeFrame: VisibilityGroupFrame? = null
    private var pending: Pending? = null
    private var lastReceipt = DepthEvidenceReceipt()
    private var overflowEvidenceCount = 0
    private var closed = false

    init {
        require(modeledMaximumSemanticStateBytes() <= SEMANTIC_STATE_BUDGET_BYTES) {
            "modeled semantic-state ownership exceeds the native budget"
        }
    }

    /** Stages one batch without changing retained evidence. */
    @Synchronized
    fun prepare(batch: DepthEvidenceBatch, surfaces: BoundedCanonicalSurfaceView): DepthEvidenceResult = try {
        prepareLocked(batch, batch, surfaces)
    } catch (_: DepthLookupFailure) {
        resetStage()
        refused(DepthEvidenceRefusal.CANONICAL_LOOKUP_FAILED, batch)
    } finally { endBulkScratch(); endPlaneSupportReusePhase() }

    /**
     * Stages a borrowed depth lease without materializing one object per sample.
     * The metadata is copied by the admission owner. Preparation copies only
     * the bounded primitive result into the kernel workspace, so the caller
     * may close the lease as soon as this method returns.
     */
    @Synchronized
    fun prepare(
        samples: BorrowedDepthSamples,
        metadata: DepthEvidenceMetadata,
        surfaces: BoundedCanonicalSurfaceView,
    ): DepthEvidenceResult = try {
        prepareLocked(metadata, samples, surfaces)
    } catch (_: DepthLookupFailure) {
        resetStage()
        refused(DepthEvidenceRefusal.CANONICAL_LOOKUP_FAILED, metadata)
    } finally { endBulkScratch(); endPlaneSupportReusePhase() }

    private fun prepareLocked(
        metadata: DepthEvidenceMetadataView,
        samples: DepthEvidenceSampleAccess,
        surfaces: BoundedCanonicalSurfaceView,
    ): DepthEvidenceResult {
        if (closed) return refused(DepthEvidenceRefusal.CLOSED, metadata)
        if (pending != null) return refused(DepthEvidenceRefusal.PREPARED_BUSY, metadata)
        if (!metadata.tracking) return refused(DepthEvidenceRefusal.NOT_TRACKING, metadata)
        if (metadata.sequence <= lastSequence || metadata.sourceTimestampNs <= lastTimestampNs) {
            return refused(DepthEvidenceRefusal.DUPLICATE_TIMESTAMP, metadata)
        }
        if (metadata.sequence < 0L || metadata.sourceTimestampNs <= 0L ||
            !isAffine(metadata.groupFromCameraGl) ||
            activeFrame?.let { it != metadata.groupFrame } == true
        ) return refused(DepthEvidenceRefusal.INVALID_FRAME, metadata)
        val revisionPair = canonicalAccess { surfaces.revisionPair }
        if (revisionPair.geometryRevision < 0L || revisionPair.lineageRevision < 0L ||
            revisionPair.geometryRevision == Long.MAX_VALUE || revisionPair.lineageRevision == Long.MAX_VALUE
        ) return refused(DepthEvidenceRefusal.STALE_CANONICAL_CUT, metadata)
        if (samples.count > configuration.sampleCapacity) {
            return refused(DepthEvidenceRefusal.SAMPLE_CAPACITY, metadata)
        }
        if (samples.rejectedCount != metadata.sourceRejectedSamples) {
            return refused(DepthEvidenceRefusal.INVALID_SAMPLE, metadata)
        }
        val surfaceCapacity = minOf(configuration.surfaceCapacity, metadata.groupFrame.modelCapacity)
        if (metadata.sourceRejectedSamples < 0 || canonicalAccess { surfaces.surfaceCount } !in 0..surfaceCapacity) {
            return refused(if (metadata.sourceRejectedSamples < 0) {
                DepthEvidenceRefusal.INVALID_SAMPLE
            } else {
                DepthEvidenceRefusal.SURFACE_CAPACITY
            }, metadata)
        }

        val cameraGroup = transform(metadata.groupFromCameraGl, DepthPointMm(0.0, 0.0, 0.0))
            ?: return refused(DepthEvidenceRefusal.INVALID_FRAME, metadata)
        val staged = try {
            stage(metadata, samples, surfaces, cameraGroup, revisionPair)
        } catch (_: ArithmeticException) {
            return refused(DepthEvidenceRefusal.ARITHMETIC_OVERFLOW, metadata)
        } catch (_: DuplicateTargetFailure) {
            return refused(DepthEvidenceRefusal.DUPLICATE_TARGET, metadata)
        } catch (_: SourceOverlapFailure) {
            return refused(DepthEvidenceRefusal.SOURCE_OVERLAP, metadata)
        } catch (_: StageCapacityFailure) {
            return refused(DepthEvidenceRefusal.SURFACE_CAPACITY, metadata)
        }
        if (staged is Staged.Refused) {
            resetStage()
            return refused(staged.reason, metadata, staged.receipt)
        }

        val accepted = staged as Staged.Accepted
        val revisionAfterPlanning = canonicalAccess { surfaces.revisionPair }
        if (revisionAfterPlanning != revisionPair) {
            resetStage()
            return refused(DepthEvidenceRefusal.STALE_CANONICAL_CUT, metadata)
        }
        pending = Pending(accepted.result.receipt, stageCount, accepted.frame)
        return accepted.result
    }

    /** Installs the previously staged primitive evidence exactly once. */
    @Synchronized
    fun applyPrepared(canonicalMutation: PreparedCanonicalMutation? = null): DepthEvidenceApplyResult {
        if (closed) return DepthEvidenceApplyResult.NoPrepared(lastReceipt)
        val staged = pending ?: return DepthEvidenceApplyResult.NoPrepared(lastReceipt)
        val retiredSourceCount = if (canonicalMutation == null) {
            0
        } else {
            var count = 0
            canonicalMutation.visitRemovedSurfaceIds { id ->
                check(count < stageRetiredSourceIds.size)
                stageRetiredSourceIds[count++] = id.value.toInt()
                true
            }
            count
        }
        return applyPreparedInternal(staged, retiredSourceCount)
    }

    /** Test and adapter seam for an already known retired-ID set. */
    @Synchronized
    internal fun applyPrepared(retiredSourceIds: LongArray): DepthEvidenceApplyResult {
        if (closed) return DepthEvidenceApplyResult.NoPrepared(lastReceipt)
        val staged = pending ?: return DepthEvidenceApplyResult.NoPrepared(lastReceipt)
        check(retiredSourceIds.size <= stageRetiredSourceIds.size)
        retiredSourceIds.forEachIndexed { index, id ->
            require(id in 1L..0xffff_ffffL)
            stageRetiredSourceIds[index] = id.toInt()
        }
        return applyPreparedInternal(staged, retiredSourceIds.size)
    }

    private fun applyPreparedInternal(staged: Pending, retiredSourceCount: Int): DepthEvidenceApplyResult {
        for (index in 0 until staged.stageCount) {
            val row = findRow(stageX[index], stageY[index], stageZ[index])
            val resident = if (row == EMPTY_ROW) {
                insertRow(stageX[index], stageY[index], stageZ[index])
            } else {
                row
            }
            writeStage(resident, index)
        }
        if (retiredSourceCount > 0) clearRetiredEvidenceSources(retiredSourceCount)
        lastSequence = staged.receipt.sequence
        lastTimestampNs = staged.receipt.sourceTimestampNs
        activeFrame = staged.frame
        lastReceipt = staged.receipt
        overflowEvidenceCount = staged.receipt.overflowCount
        pending = null
        resetStage()
        return DepthEvidenceApplyResult.Applied(lastReceipt)
    }

    /** Removes canonical source identities retired by the just-committed cut. */
    private fun clearRetiredEvidenceSources(retiredSourceCount: Int) {
        java.util.Arrays.sort(stageRetiredSourceIds, 0, retiredSourceCount)
        for (row in hashRows.indices) {
            if (hashRows[row] == EMPTY_ROW) continue
            val resident = hashRows[row]
            val sourceId = rowSourceId[resident]
            if (sourceId == 0 ||
                binarySearch(stageRetiredSourceIds, retiredSourceCount, sourceId) < 0
            ) continue
            rowSourceId[resident] = 0
            rowSourceKey[resident] = rowVoxelKey[resident]
            rowFlags[resident] = (rowFlags[resident].toInt() and ESTIMATED_FLAG.inv()).toByte()
            rowPackedNormal[resident] = 0
            rowNormalConfidence[resident] = 0
            rowLineageCount[resident] = 0
        }
    }

    private fun binarySearch(values: IntArray, count: Int, value: Int): Int {
        var low = 0
        var high = count - 1
        while (low <= high) {
            val middle = (low + high) ushr 1
            when (Integer.compare(values[middle], value)) {
                0 -> return middle
                -1 -> low = middle + 1
                else -> high = middle - 1
            }
        }
        return -1
    }

    /** Drops the staged packet and leaves all retained primitive state untouched. */
    @Synchronized
    fun discardPrepared(): DepthEvidenceDiscardResult {
        if (closed) return DepthEvidenceDiscardResult.AlreadyDiscarded(lastReceipt)
        if (pending == null) return DepthEvidenceDiscardResult.AlreadyDiscarded(lastReceipt)
        pending = null
        resetStage()
        return DepthEvidenceDiscardResult.Discarded(lastReceipt)
    }

    /** Reports exact resident/staged row ownership; observation payloads are never retained. */
    @Synchronized
    fun resourceReceipt(): DepthEvidenceResourceReceipt {
        val preparedRows = pending?.stageCount ?: 0
        val residentBytes = checkedBytes(residentRows)
        val preparedBytes = checkedBytes(preparedRows)
        return DepthEvidenceResourceReceipt(
            residentEvidenceRows = if (closed) 0 else residentRows,
            residentBytes = if (closed) 0 else residentBytes,
            preparedEvidenceRows = if (closed) 0 else preparedRows,
            preparedResidentBytes = if (closed) 0 else preparedBytes,
            evidenceRowCapacity = tableCapacity,
            fixedPrimitiveBytes = if (closed) 0 else fixedPrimitiveBytes(),
            closed = closed,
            maximumAcceptedOutputReserveBytes = if (closed) 0 else MAXIMUM_ACCEPTED_OUTPUT_RESERVE_BYTES,
            modeledMaximumSemanticStateBytes = if (closed) 0 else modeledMaximumSemanticStateBytes(),
            semanticStateBudgetBytes = if (closed) 0 else SEMANTIC_STATE_BUDGET_BYTES,
        )
    }

    @Synchronized
    override fun close() {
        if (closed) return
        pending = null
        resetStage()
        hashRows = IntArray(0)
        rowVoxelKey = LongArray(0)
        rowOccupied = ByteArray(0)
        rowFree = ByteArray(0)
        rowDirections = IntArray(0)
        rowFlags = ByteArray(0)
        rowSourceId = IntArray(0)
        rowSourceKey = LongArray(0)
        rowPackedNormal = ShortArray(0)
        rowNormalConfidence = ByteArray(0)
        rowLineageCount = ShortArray(0)
        rowEstimateOffsetX = null
        rowEstimateOffsetY = null
        rowEstimateOffsetZ = null
        stageEstimateState = null
        localPlane?.close()
        stageHashRows = IntArray(0)
        stageX = IntArray(0)
        stageY = IntArray(0)
        stageZ = IntArray(0)
        stageOccupied = ByteArray(0)
        stageFree = ByteArray(0)
        stageDirections = IntArray(0)
        stageContradicted = BooleanArray(0)
        stageSourceId = IntArray(0)
        stageSourceKey = LongArray(0)
        stagePackedNormal = ShortArray(0)
        stageNormalConfidence = ByteArray(0)
        stageLineageCount = ShortArray(0)
        stagePublished = BooleanArray(0)
        stageHasOccupied = BooleanArray(0)
        stageOccupiedPointX = DoubleArray(0)
        stageOccupiedPointY = DoubleArray(0)
        stageOccupiedPointZ = DoubleArray(0)
        stageFreeBin = ByteArray(0)
        stageOrder = IntArray(0)
        stageSortScratch = IntArray(0)
        stageSortCounts = IntArray(0)
        stageRelationTarget = IntArray(0)
        stageRelationSource = IntArray(0)
        stageRelationOrder = IntArray(0)
        stageComponentOrder = IntArray(0)
        stageComponentRank = IntArray(0)
        stageComponent = IntArray(0)
        stagePositive = BooleanArray(0)
        stageRemoval = BooleanArray(0)
        stageChangeKind = ByteArray(0)
        stageChangeSource = IntArray(0)
        stageChangeTarget = IntArray(0)
        stageChangeComponent = IntArray(0)
        stageKindCounts = IntArray(0)
        stageRetiredSourceIds = IntArray(0)
        residentRows = 0
        activeFrame = null
        closed = true
    }

    private fun stage(
        metadata: DepthEvidenceMetadataView,
        samples: DepthEvidenceSampleAccess,
        surfaces: BoundedCanonicalSurfaceView,
        cameraGroup: DepthPointMm,
        revisionPair: CanonicalRevisionPair,
    ): Staged {
        resetStage()
        if (estimatesEnabled && configuration.planeFitStrategy == DepthPlaneFitStrategy.BULK_CELL_MOMENTS)
            prepareBulkPlanes(metadata, samples, cameraGroup)
        else {
            localPlane?.index(metadata, samples, configuration)
            beginPlaneSupportReusePhase()
        }
        stageAssociationWork = 0
        stageAssociatedSamples = 0
        stageIndependentPlaneFits = 0
        val evidenceIndexWork = if (surfaces.supportsEmptyBlockSkipping) rebuildEvidenceBlocks() else 0
        var acceptedSamples = 0
        var rejectedSamples = metadata.sourceRejectedSamples
        var visits = 0
        var overflowCount = 0
        fun refuseAttempt(reason: DepthEvidenceRefusal, attemptedVisits: Int = visits): Staged.Refused {
            val work = checkedAdd(evidenceIndexWork, checkedAdd(acceptedSamples, checkedAdd(attemptedVisits, stageCount)))
            return Staged.Refused(
                reason,
                DepthEvidenceReceipt(
                    sequence = metadata.sequence,
                    sourceTimestampNs = metadata.sourceTimestampNs,
                    acceptedSamples = acceptedSamples,
                    rejectedSamples = rejectedSamples,
                    rayVisits = attemptedVisits,
                    touchedEvidenceRows = stageCount,
                    overflowCount = overflowCount,
                    p50VirtualWorkUnits = work,
                    p95VirtualWorkUnits = work,
                ),
            )
        }

        for (sampleIndex in 0 until samples.count) {
            val x = samples.xAt(sampleIndex)
            val y = samples.yAt(sampleIndex)
            val depthMillimetres = samples.depthMillimetresAt(sampleIndex)
            val confidence = samples.confidenceAt(sampleIndex)
            if (x !in 0 until metadata.intrinsics.imageWidth ||
                y !in 0 until metadata.intrinsics.imageHeight ||
                confidence < configuration.confidenceMinimum ||
                depthMillimetres !in configuration.minimumDepthMillimetres..configuration.maximumDepthMillimetres
            ) {
                rejectedSamples = checkedAdd(rejectedSamples, 1)
                continue
            }
            val cameraPoint = DepthPointMm(
                (x - metadata.intrinsics.cx) * depthMillimetres / metadata.intrinsics.fx,
                -(y - metadata.intrinsics.cy) * depthMillimetres / metadata.intrinsics.fy,
                -depthMillimetres.toDouble(),
            )
            if (!cameraPoint.isFinite()) {
                rejectedSamples = checkedAdd(rejectedSamples, 1)
                continue
            }
            val endpoint = transform(metadata.groupFromCameraGl, cameraPoint)
                ?: throw ArithmeticException("non-finite depth transform")
            val measuredVoxel = DepthVoxelAddressing.quantize(endpoint, metadata.groupFrame.voxelSizeMicrometres)
                ?: return refuseAttempt(DepthEvidenceRefusal.ARITHMETIC_OVERFLOW)
            val hasLocalPlane = if (estimatesEnabled && configuration.planeFitStrategy == DepthPlaneFitStrategy.BULK_CELL_MOMENTS)
                projectBulkPoint(measuredVoxel, endpoint)
            else fitWithCurrentEstimateSupport(metadata, samples, sampleIndex, measuredVoxel)
            val endpointVoxel = if (hasLocalPlane &&
                configuration.surfaceEstimationMode == DepthSurfaceEstimationMode.COMPATIBLE_LOCAL_PLANES
            ) associatedEndpointVoxel(measuredVoxel, metadata.groupFrame) else measuredVoxel
            if (endpointVoxel != measuredVoxel) stageAssociatedSamples++
            val endpointFound = canonicalAccess {
                surfaces.findSurfaceAtInto(endpointVoxel.x, endpointVoxel.y, endpointVoxel.z, canonicalScratch)
            }
            val endpointId = if (endpointFound) canonicalScratch.id else 0L
            val endpointVoxelX = if (endpointFound) canonicalScratch.addressedVoxelX else endpointVoxel.x
            val endpointVoxelY = if (endpointFound) canonicalScratch.addressedVoxelY else endpointVoxel.y
            val endpointVoxelZ = if (endpointFound) canonicalScratch.addressedVoxelZ else endpointVoxel.z
            val endpointSourceVoxelX = if (endpointFound) canonicalScratch.voxelX else endpointVoxel.x
            val endpointSourceVoxelY = if (endpointFound) canonicalScratch.voxelY else endpointVoxel.y
            val endpointSourceVoxelZ = if (endpointFound) canonicalScratch.voxelZ else endpointVoxel.z
            val endpointPackedNormal = if (endpointFound) canonicalScratch.packedNormal else 0
            val endpointNormalConfidence = if (endpointFound) canonicalScratch.normalConfidence else 0
            val endpointLineageCount = if (endpointFound) canonicalScratch.lineageCount else 0
            if (endpointFound) validateCanonicalSurface(
                surfaces, endpointVoxel.x, endpointVoxel.y, endpointVoxel.z, canonicalScratch,
            )
            val priorEndpointIndex = stageFind(endpointVoxelX, endpointVoxelY, endpointVoxelZ)
            val hadOccupied = priorEndpointIndex != EMPTY_ROW && stageHasOccupied[priorEndpointIndex]
            val endpointIndex = stageIndex(
                endpointVoxelX, endpointVoxelY, endpointVoxelZ,
                endpointId, endpointSourceVoxelX, endpointSourceVoxelY, endpointSourceVoxelZ,
                endpointPackedNormal, endpointNormalConfidence, endpointLineageCount,
            )
            stageHasOccupied[endpointIndex] = true
            if (hasLocalPlane) updateSupportDestinationHint(measuredVoxel, endpointIndex)
            if (hasLocalPlane) updateStagedEstimate(endpointIndex, metadata.groupFrame)
            if (estimateState(endpointIndex) and ESTIMATE_VALID == 0 &&
                (!hadOccupied || comparePoint(endpoint, occupiedPoint(endpointIndex)) < 0)) {
                stageOccupiedPointX[endpointIndex] = endpoint.x
                stageOccupiedPointY[endpointIndex] = endpoint.y
                stageOccupiedPointZ[endpointIndex] = endpoint.z
            }
            // The map stores evidence by voxel. Repeated endpoints in one
            // camera frame share a voxel-scale ray; retain the representative
            // endpoint and count the sample without walking that ray again.
            if (hadOccupied) {
                acceptedSamples = checkedAdd(acceptedSamples, 1)
                continue
            }
            var rayResult: DepthRayVisitResult
            val remaining = configuration.rayVisitCapacity - visits
            if (remaining < 0) return refuseAttempt(DepthEvidenceRefusal.RAY_VISIT_CAPACITY)
            try {
                rayResult = visitRayCellsInto(
                    surfaces,
                    cameraGroup,
                    endpoint,
                    metadata.groupFrame,
                    remaining,
                    measuredVoxel.x,
                    measuredVoxel.y,
                    measuredVoxel.z,
                    endpointFound && measuredVoxel == endpointVoxel,
                    endpointId,
                    endpointSourceVoxelX,
                    endpointSourceVoxelY,
                    endpointSourceVoxelZ,
                    endpointPackedNormal,
                    endpointNormalConfidence,
                    endpointLineageCount,
                ) { xCell, yCell, zCell, lookup ->
                    if (!voxelInRange(xCell, yCell, zCell)) return@visitRayCellsInto false
                    val found = lookup.present
                    if (found) validateCanonicalSurface(surfaces, xCell, yCell, zCell, lookup)
                    val candidateIndex = if (found) stageIndex(
                        xCell, yCell, zCell, lookup.id, lookup.voxelX, lookup.voxelY, lookup.voxelZ,
                        lookup.packedNormal, lookup.normalConfidence, lookup.lineageCount,
                    ) else stageFind(xCell, yCell, zCell)
                    val centerX = centerCoordinate(xCell, metadata.groupFrame)
                    val centerY = centerCoordinate(yCell, metadata.groupFrame)
                    val centerZ = centerCoordinate(zCell, metadata.groupFrame)
                    if (isFreeEvidence(
                            cameraGroup, endpoint, endpointVoxelX, endpointVoxelY, endpointVoxelZ,
                            xCell, yCell, zCell, centerX, centerY, centerZ, metadata.groupFrame,
                        )
                    ) {
                        val retained = findRow(xCell, yCell, zCell) != EMPTY_ROW
                        if (candidateIndex != EMPTY_ROW || retained) {
                            val index = if (candidateIndex != EMPTY_ROW) candidateIndex else stageIndex(xCell, yCell, zCell, 0L, 0, 0, 0, 0, 0, 0)
                            val direction = directionBin(
                                cameraGroup.x, cameraGroup.y, cameraGroup.z,
                                centerX, centerY, centerZ,
                            )
                            stageFreeBin[index] = if (stageFreeBinValue(index) == NO_DIRECTION) {
                                direction.toByte()
                            } else {
                                minOf(stageFreeBinValue(index), direction).toByte()
                            }
                        }
                    }
                    true
                }
            } catch (failure: DuplicateTargetFailure) {
                throw failure
            } catch (_: RuntimeException) {
                throw DepthLookupFailure()
            }
            if (rayResult.arithmeticOverflow) {
                return refuseAttempt(
                    DepthEvidenceRefusal.ARITHMETIC_OVERFLOW,
                    checkedAdd(visits, rayResult.workUnits),
                )
            }
            if (rayResult.truncated ||
                rayResult.workUnits < 0 || rayResult.workUnits > remaining
            ) return refuseAttempt(
                DepthEvidenceRefusal.RAY_VISIT_CAPACITY,
                checkedAdd(visits, rayResult.workUnits),
            )
            visits = checkedAdd(visits, rayResult.workUnits)
            acceptedSamples = checkedAdd(acceptedSamples, 1)
        }
        if (acceptedSamples == 0 && samples.count > 0) {
            return refuseAttempt(DepthEvidenceRefusal.INVALID_SAMPLE)
        }
        var directionVotes = 0
        var conflicts = 0
        for (index in 0 until stageCount) {
            finalizeStagedPosition(index, metadata.groupFrame)
            if (stageHasOccupied[index]) {
                val updated = increment(stageOccupiedValue(index))
                stageOccupied[index] = (updated and 0xff).toByte()
                if (updated ushr 8 != 0) overflowCount = checkedAdd(overflowCount, 1)
            }
            if (stageFreeBinValue(index) != NO_DIRECTION) {
                val updated = increment(stageFreeValue(index))
                stageFree[index] = (updated and 0xff).toByte()
                if (updated ushr 8 != 0) overflowCount = checkedAdd(overflowCount, 1)
                val bit = 1 shl stageFreeBinValue(index)
                if (stageDirections[index] and bit == 0) {
                    stageDirections[index] = stageDirections[index] or bit
                    directionVotes++
                }
            }
            if (stageHasOccupied[index] && stageFreeValue(index) > 0) conflicts = checkedAdd(conflicts, 1)
        }
        endBulkScratch()
        endPlaneSupportReusePhase()
        val orderingWork = sortStageOrder()
        val planningWork = planChanges(surfaces)
        val validation = validatePlannedChanges()
        validation.refusal?.let { return refuseAttempt(it) }
        var newRows = 0
        for (index in 0 until stageCount) {
            if (findRow(stageX[index], stageY[index], stageZ[index]) == EMPTY_ROW) newRows++
        }
        val projection = projectedSurfaceCount(canonicalAccess { surfaces.surfaceCount })
        if (projection.count < 0) throw DepthLookupFailure()
        if (residentRows + newRows > minOf(configuration.surfaceCapacity, metadata.groupFrame.modelCapacity) ||
            projection.count > minOf(configuration.surfaceCapacity, metadata.groupFrame.modelCapacity)
        ) {
            return refuseAttempt(DepthEvidenceRefusal.SURFACE_CAPACITY)
        }
        val immutableChanges = materializeChanges(cameraGroup, metadata.groupFrame)
        val kindCounts = kindCounts(stageChangeKind, stageChangeCount)
        val virtualWork = checkedAdd(
            checkedAdd(orderingWork, checkedAdd(planningWork, checkedAdd(validation.work, projection.work))),
            checkedAdd(checkedAdd(bulkScratchWork, checkedAdd(checkedAdd(stageAssociationWork, localPlane?.workUnits ?: 0), reuseScratchWork)), checkedAdd(evidenceIndexWork,
                checkedAdd(stageRelationAdmissionWork, checkedAdd(acceptedSamples, checkedAdd(visits, stageCount))))),
        )
        val receipt = DepthEvidenceReceipt(
            sequence = metadata.sequence,
            sourceTimestampNs = metadata.sourceTimestampNs,
            acceptedSamples = acceptedSamples,
            rejectedSamples = rejectedSamples,
            rayVisits = visits,
            touchedEvidenceRows = stageCount,
            independentDirectionVotes = directionVotes,
            createCount = kindCounts[0],
            refineCount = kindCounts[1],
            relocateCount = kindCounts[2],
            mergeCount = kindCounts[3],
            splitCount = kindCounts[4],
            replaceCount = kindCounts[5],
            removeCount = kindCounts[6],
            conflictsRetained = conflicts,
            capacityRefusals = 0,
            overflowCount = checkedAdd(overflowEvidenceCount, overflowCount),
            preparedResidentBytes = checkedBytes(stageCount),
            p50VirtualWorkUnits = virtualWork,
            p95VirtualWorkUnits = virtualWork,
        )
        val work = DepthEvidenceWorkReceipt(
            distinctTouchedVoxelCount = stageCount,
            emittedChangeCount = immutableChanges.size,
            rayVisits = visits,
            independentDirectionVotes = directionVotes,
            virtualWorkUnits = virtualWork,
        )
        return Staged.Accepted(
            DepthEvidenceResult.Accepted(
                revisionPair.geometryRevision,
                revisionPair.lineageRevision,
                immutableChanges,
                receipt,
                work,
            ),
            stageCount,
            metadata.groupFrame,
        )
    }

    private fun planChanges(surfaces: BoundedCanonicalSurfaceView): Int {
        var work = stageCount
        for (index in 0 until stageCount) {
            stagePositive[index] = false
            stageRemoval[index] = false
            stageComponent[index] = index
            val sourceId = stageSourceIdValue(index)
            if (stageContradicted[index]) {
                if (stageOccupiedValue(index) >= configuration.occupiedEvidenceToShow && hasPublicationPlaneSupport(index)) {
                    stageContradicted[index] = false
                    stageFree[index] = 0
                    stageDirections[index] = 0
                    stagePublished[index] = true
                    stagePositive[index] = true
                }
                continue
            }
            val conflicted = stageHasOccupied[index] && stageFreeValue(index) > 0
            val canCarve = stageFreeValue(index) >= configuration.freeEvidenceToCarve &&
                stageFreeValue(index) - stageOccupiedValue(index) >= configuration.freeEvidenceMargin &&
                hasSeparatedDirections(stageDirections[index], configuration.separatedDirectionBinsRequired)
            if (canCarve && !conflicted && sourceId != 0L) {
                stageContradicted[index] = true
                stageOccupied[index] = 0
                stagePublished[index] = false
                stageRemoval[index] = true
            } else if (!conflicted && stageOccupiedValue(index) >= configuration.occupiedEvidenceToShow &&
                !stagePublished[index] && hasPublicationPlaneSupport(index)
            ) {
                stagePublished[index] = true
                stagePositive[index] = true
            }
        }

        stageRelationCount = buildImplicitRelations()
        work = checkedAdd(work, stageRelationCount)
        work = checkedAdd(work, radixSortImplicitRelations(0, stageRelationCount) { relation ->
            implicitRelationSourceValue(relation).toInt()
        })
        var relationPosition = 0
        while (relationPosition < stageRelationCount) {
            val source = implicitRelationSourceValue(stageHashRows[relationPosition])
            var end = relationPosition + 1
            while (end < stageRelationCount && implicitRelationSourceValue(stageHashRows[end]) == source) end++
            var firstPositive = EMPTY_ROW
            for (position in relationPosition until end) {
                val target = implicitRelationTarget(stageHashRows[position])
                if (stagePositive[target]) {
                    if (firstPositive == EMPTY_ROW) firstPositive = target else unionComponents(firstPositive, target)
                }
            }
            work = checkedAdd(work, end - relationPosition)
            relationPosition = end
        }

        stageOrderedTargetCount = 0
        for (position in 0 until stageCount) {
            val index = stageOrder[position]
            stageComponentRank[index] = position
            if (stagePositive[index]) stageComponentOrder[stageOrderedTargetCount++] = index
        }
        work = checkedAdd(work, stageCount)
        work = checkedAdd(work, radixSortComponentOrderByRoot(stageOrderedTargetCount))

        var sourceWrite = 0
        relationPosition = 0
        while (relationPosition < stageRelationCount) {
            val source = implicitRelationSourceValue(stageHashRows[relationPosition])
            var end = relationPosition + 1
            while (end < stageRelationCount && implicitRelationSourceValue(stageHashRows[end]) == source) end++
            var representative = EMPTY_ROW
            for (position in relationPosition until end) {
                val relation = stageHashRows[position]
                if (stagePositive[implicitRelationTarget(relation)]) {
                    representative = relation
                    break
                }
            }
            if (representative != EMPTY_ROW) {
                validateEmittedSource(surfaces, source)
                work = checkedAdd(work, 1)
                stageHashRows[sourceWrite++] = representative
            }
            work = checkedAdd(work, end - relationPosition)
            relationPosition = end
        }
        stageOrderedSourceCount = sourceWrite
        work = checkedAdd(work, validateRemovalSourcesAgainstPositiveSources(canonicalAccess { surfaces.surfaceCount }))
        work = checkedAdd(work, radixSortImplicitRelations(0, stageOrderedSourceCount) { relation ->
            stageComponentRank[findComponent(implicitRelationTarget(relation))]
        })
        var componentStart = 0
        while (componentStart < stageOrderedSourceCount) {
            val root = sourceRootAt(componentStart)
            var componentEnd = componentStart + 1
            while (componentEnd < stageOrderedSourceCount && sourceRootAt(componentEnd) == root) componentEnd++
            work = checkedAdd(work, radixSortImplicitRelations(componentStart, componentEnd - componentStart) { relation ->
                implicitRelationSourceValue(relation).toInt()
            })
            componentStart = componentEnd
        }

        stageChangeCount = 0
        var targetPosition = 0
        while (targetPosition < stageOrderedTargetCount) {
            val index = stageComponentOrder[targetPosition]
            val root = findComponent(index)
            val sourceCount = componentSourceCount(root)
            val targetCount = componentTargetCount(root)
            val kind = when {
                sourceCount == 0 -> CHANGE_CREATE
                sourceCount == 1 && targetCount == 1 -> {
                    if (stageSourceKey[index] == packVisibilityGridKey(stageX[index], stageY[index], stageZ[index])) {
                        CHANGE_REFINE
                    } else {
                        CHANGE_RELOCATE
                    }
                }
                sourceCount > 1 && targetCount == 1 -> CHANGE_MERGE
                sourceCount == 1 -> CHANGE_SPLIT
                else -> CHANGE_REPLACE
            }
            addPlannedChange(kind, index, root, sourceCount == 1)
            targetPosition += targetCount
        }
        work = checkedAdd(work, stageOrderedTargetCount)
        for (position in 0 until stageCount) {
            val index = stageOrder[position]
            if (stageRemoval[index]) addPlannedChange(CHANGE_REMOVE, index, index, false)
        }
        return checkedAdd(work, stageCount)
    }

    private fun addPlannedChange(kind: Int, index: Int, component: Int, hasSingleSource: Boolean) {
        check(stageChangeCount < stageCapacity)
        val slot = stageChangeCount++
        stageChangeKind[slot] = kind.toByte()
        stageChangeTarget[slot] = index
        stageChangeComponent[slot] = component
        stageRelationSource[slot] = (if (hasSingleSource) {
            sourceAtRank(component, 0)
        } else {
            stageSourceIdValue(index)
        }).toInt()
    }

    private fun findComponent(index: Int): Int {
        var root = index
        while (stageComponent[root] != root) root = stageComponent[root]
        var cursor = index
        while (stageComponent[cursor] != cursor) {
            val next = stageComponent[cursor]
            stageComponent[cursor] = root
            cursor = next
        }
        return root
    }

    private fun unionComponents(left: Int, right: Int) {
        val leftRoot = findComponent(left)
        val rightRoot = findComponent(right)
        if (leftRoot == rightRoot) return
        if (leftRoot < rightRoot) stageComponent[rightRoot] = leftRoot else stageComponent[leftRoot] = rightRoot
    }

    private fun componentTargetCount(root: Int): Int {
        val start = lowerBoundTargetRoot(root)
        var end = start
        while (end < stageOrderedTargetCount && targetRootAt(end) == root) end++
        return end - start
    }

    private fun componentSourceCount(root: Int): Int {
        val start = lowerBoundSourceRoot(root)
        var end = start
        while (end < stageOrderedSourceCount && sourceRootAt(end) == root) end++
        return end - start
    }

    private fun sourceAtRank(root: Int, rank: Int): Long {
        val position = lowerBoundSourceRoot(root) + rank
        check(position < stageOrderedSourceCount && sourceRootAt(position) == root)
        return implicitRelationSourceValue(stageHashRows[position])
    }

    private fun targetAtRank(root: Int, rank: Int): Int {
        val position = lowerBoundTargetRoot(root) + rank
        check(position < stageOrderedTargetCount && targetRootAt(position) == root)
        return stageComponentOrder[position]
    }

    private fun lowerBoundTargetRoot(root: Int): Int {
        var low = 0
        var high = stageOrderedTargetCount
        while (low < high) {
            val middle = (low + high) ushr 1
            if (stageComponentRank[targetRootAt(middle)] < stageComponentRank[root]) low = middle + 1 else high = middle
        }
        return low
    }

    private fun lowerBoundSourceRoot(root: Int): Int {
        var low = 0
        var high = stageOrderedSourceCount
        while (low < high) {
            val middle = (low + high) ushr 1
            if (stageComponentRank[sourceRootAt(middle)] < stageComponentRank[root]) low = middle + 1 else high = middle
        }
        return low
    }

    private fun targetRootAt(position: Int): Int = findComponent(stageComponentOrder[position])

    private fun sourceRootAt(position: Int): Int =
        findComponent(implicitRelationTarget(stageHashRows[position]))

    private fun materializeChanges(cameraGroup: DepthPointMm, frame: VisibilityGroupFrame): List<DepthEvidenceChange> {
        var sourceTotal = 0
        var targetTotal = 0
        for (slot in 0 until stageChangeCount) {
            val component = stageChangeComponent[slot]
            sourceTotal = checkedAdd(sourceTotal, when (stageChangeKind[slot].toInt()) {
                CHANGE_CREATE -> 0
                CHANGE_MERGE, CHANGE_REPLACE -> componentSourceCount(component)
                else -> 1
            })
            targetTotal = checkedAdd(targetTotal, when (stageChangeKind[slot].toInt()) {
                CHANGE_REMOVE -> 0
                CHANGE_SPLIT, CHANGE_REPLACE -> componentTargetCount(component)
                else -> 1
            })
        }
        check(sourceTotal <= tableCapacity && targetTotal <= stageCapacity)
        val kinds = ByteArray(stageChangeCount)
        val sourceStarts = IntArray(stageChangeCount)
        val sourceCounts = IntArray(stageChangeCount)
        val targetStarts = IntArray(stageChangeCount)
        val targetCounts = IntArray(stageChangeCount)
        val sources = IntArray(sourceTotal)
        val targetKeys = LongArray(targetTotal)
        val targetPackedNormals = ShortArray(targetTotal)
        val targetConfidences = ByteArray(targetTotal)
        val targetIds = IntArray(targetTotal)
        var sourceWrite = 0
        var targetWrite = 0
        fun appendSource(value: Long) { sources[sourceWrite++] = value.toInt() }
        fun appendTarget(index: Int, id: SurfaceId?) {
            val target = targetFor(index, if (stageHasOccupied[index]) occupiedPoint(index) else null, cameraGroup, frame, id)
            targetKeys[targetWrite] = packVisibilityGridKey(target.voxel.x, target.voxel.y, target.voxel.z)
            targetPackedNormals[targetWrite] = (((target.normalOctX and 0xff) shl 8) or
                (target.normalOctY and 0xff)).toShort()
            targetConfidences[targetWrite] = target.normalConfidence.toByte()
            targetIds[targetWrite] = target.id?.value?.toInt() ?: 0
            targetWrite++
        }
        for (slot in 0 until stageChangeCount) {
            val kind = stageChangeKind[slot].toInt()
            val index = stageChangeTarget[slot]
            val component = stageChangeComponent[slot]
            kinds[slot] = kind.toByte()
            sourceStarts[slot] = sourceWrite
            targetStarts[slot] = targetWrite
            when (kind) {
                CHANGE_CREATE -> appendTarget(index, null)
                CHANGE_REFINE, CHANGE_RELOCATE -> {
                    val source = plannedChangeSourceValue(slot)
                    appendSource(source)
                    appendTarget(index, SurfaceId(source))
                }
                CHANGE_MERGE -> {
                    repeat(componentSourceCount(component)) { appendSource(sourceAtRank(component, it)) }
                    appendTarget(index, null)
                }
                CHANGE_SPLIT -> {
                    appendSource(plannedChangeSourceValue(slot))
                    repeat(componentTargetCount(component)) { appendTarget(targetAtRank(component, it), null) }
                }
                CHANGE_REPLACE -> {
                    repeat(componentSourceCount(component)) { appendSource(sourceAtRank(component, it)) }
                    repeat(componentTargetCount(component)) { appendTarget(targetAtRank(component, it), null) }
                }
                CHANGE_REMOVE -> appendSource(plannedChangeSourceValue(slot))
            }
            sourceCounts[slot] = sourceWrite - sourceStarts[slot]
            targetCounts[slot] = targetWrite - targetStarts[slot]
        }
        check(sourceWrite == sourceTotal && targetWrite == targetTotal)
        return PrimitiveDepthEvidenceChanges(
            kinds, sourceStarts, sourceCounts, targetStarts, targetCounts,
            sources, targetKeys, targetPackedNormals, targetConfidences, targetIds,
        )
    }

    private fun projectedSurfaceCount(initialCount: Int): SurfaceProjection {
        var projected = initialCount
        for (slot in 0 until stageChangeCount) {
            val component = stageChangeComponent[slot]
            val delta = when (stageChangeKind[slot].toInt()) {
                CHANGE_CREATE -> 1
                CHANGE_REFINE, CHANGE_RELOCATE -> 0
                CHANGE_MERGE -> 1 - componentSourceCount(component)
                CHANGE_SPLIT -> componentTargetCount(component) - 1
                CHANGE_REPLACE -> componentTargetCount(component) - componentSourceCount(component)
                CHANGE_REMOVE -> -1
                else -> throw ArithmeticException("unknown projected change kind")
            }
            projected = Math.addExact(projected, delta)
        }
        return SurfaceProjection(projected, stageChangeCount)
    }

    private fun validatePlannedChanges(): PlannedValidation {
        // Each staged target has one coordinate-hash row, and each positive row
        // appears in exactly one component order, so a linear range check proves
        // target uniqueness without comparing planned changes pairwise.
        for (position in 0 until stageOrderedTargetCount) {
            val target = stageComponentOrder[position]
            if (!voxelInRange(Voxel(stageX[target], stageY[target], stageZ[target]))) {
                return PlannedValidation(DepthEvidenceRefusal.DUPLICATE_TARGET, position + 1)
            }
        }
        return PlannedValidation(null, checkedAdd(stageOrderedTargetCount, stageChangeCount))
    }

    private fun validateRemovalSourcesAgainstPositiveSources(surfaceCount: Int): Int {
        var removalCount = 0
        for (index in 0 until stageCount) {
            if (stageRemoval[index]) stageSortScratch[removalCount++] = stageSourceId[index]
        }
        if (stageOrderedSourceCount + removalCount > surfaceCount) throw DepthLookupFailure()
        var work = radixSortUnsignedValues(stageSortScratch, stageRelationTarget, removalCount)
        var positive = 0
        var removal = 0
        while (positive < stageOrderedSourceCount || removal < removalCount) {
            if (removal > 0 && removal < removalCount &&
                stageSortScratch[removal] == stageSortScratch[removal - 1]
            ) throw SourceOverlapFailure()
            when {
                positive == stageOrderedSourceCount -> removal++
                removal == removalCount -> positive++
                else -> {
                    val positiveId = implicitRelationSourceValue(stageHashRows[positive]).toInt()
                    val comparison = Integer.compareUnsigned(positiveId, stageSortScratch[removal])
                    when {
                        comparison < 0 -> positive++
                        comparison > 0 -> removal++
                        else -> throw SourceOverlapFailure()
                    }
                }
            }
            work = checkedAdd(work, 1)
        }
        return work
    }

    private fun targetFor(
        index: Int,
        cameraPoint: DepthPointMm?,
        cameraGroup: DepthPointMm,
        frame: VisibilityGroupFrame,
        sourceId: SurfaceId?,
    ): CanonicalTarget {
        val packed = if (estimateState(index) and ESTIMATE_VALID != 0 || stagePackedNormalValue(index) != 0) stagePackedNormalValue(index) else {
            val center = center(Voxel(stageX[index], stageY[index], stageZ[index]), frame)
            val direction = cameraPoint?.let {
                DepthPointMm(cameraGroup.x - it.x, cameraGroup.y - it.y, cameraGroup.z - it.z)
            } ?: center
            encodeNormal(direction)
        }
        return CanonicalTarget(
            id = sourceId,
            voxel = Voxel(stageX[index], stageY[index], stageZ[index]),
            normalOctX = (packed ushr 8).toByte().toInt(),
            normalOctY = packed.toByte().toInt(),
            normalConfidence = if (stageNormalConfidenceValue(index) != 0) {
                stageNormalConfidenceValue(index)
            } else {
                (stageOccupiedValue(index) * 64).coerceIn(0, 255)
            },
        )
    }

    /** Deterministic 3-D supercover in increasing segment parameter order. */
    internal fun visitRayCells(
        camera: DepthPointMm,
        endpoint: DepthPointMm,
        frame: VisibilityGroupFrame,
        maximumVisits: Int,
        visitor: (Voxel) -> Boolean,
    ): DepthRayVisitResult = DepthRaySupercover.visit(
        camera, endpoint, frame.voxelSizeMicrometres, maximumVisits, visitor,
    )

    private fun visitRayCellsInto(
        surfaces: BoundedCanonicalSurfaceView,
        camera: DepthPointMm,
        endpoint: DepthPointMm,
        frame: VisibilityGroupFrame,
        maximumVisits: Int,
        endpointVoxelX: Int,
        endpointVoxelY: Int,
        endpointVoxelZ: Int,
        endpointFound: Boolean,
        endpointId: Long,
        endpointSourceVoxelX: Int,
        endpointSourceVoxelY: Int,
        endpointSourceVoxelZ: Int,
        endpointPackedNormal: Int,
        endpointNormalConfidence: Int,
        endpointLineageCount: Int,
        visitor: (Int, Int, Int, CanonicalSurfaceScratch) -> Boolean,
    ): DepthRayVisitResult = DepthRaySupercover.visitCoordinates(
        camera, endpoint, frame.voxelSizeMicrometres, maximumVisits,
        emptyBlock = if (surfaces.supportsEmptyBlockSkipping) { x, y, z, size ->
            !hasEvidenceBlock(x, y, z, size) && surfaces.isKnownEmptyBlock(x, y, z, size)
        } else null,
    ) { x, y, z ->
        if (x == endpointVoxelX && y == endpointVoxelY && z == endpointVoxelZ) {
            if (endpointFound) {
                canonicalScratch.setAddressed(
                    endpointId,
                    endpointVoxelX, endpointVoxelY, endpointVoxelZ,
                    endpointSourceVoxelX, endpointSourceVoxelY, endpointSourceVoxelZ,
                    endpointPackedNormal, endpointNormalConfidence, endpointLineageCount,
                )
            } else {
                canonicalScratch.clear()
            }
        } else if (!surfaces.findSurfaceAtInto(x, y, z, canonicalScratch)) {
            canonicalScratch.clear()
        }
        visitor(x, y, z, canonicalScratch)
    }

    private fun isFreeEvidence(
        camera: DepthPointMm,
        endpoint: DepthPointMm,
        endpointVoxelX: Int,
        endpointVoxelY: Int,
        endpointVoxelZ: Int,
        voxelX: Int,
        voxelY: Int,
        voxelZ: Int,
        cellCenterX: Double,
        cellCenterY: Double,
        cellCenterZ: Double,
        frame: VisibilityGroupFrame,
    ): Boolean {
        if (voxelX == endpointVoxelX && voxelY == endpointVoxelY && voxelZ == endpointVoxelZ) return false
        if (frame.voxelSizeMicrometres <= 0 || !voxelInRange(voxelX, voxelY, voxelZ)) return false
        val dx = endpoint.x - camera.x
        val dy = endpoint.y - camera.y
        val dz = endpoint.z - camera.z
        val length2 = dx * dx + dy * dy + dz * dz
        if (!length2.isFinite() || length2 <= 0.0) return false
        val toCellX = cellCenterX - camera.x
        val toCellY = cellCenterY - camera.y
        val toCellZ = cellCenterZ - camera.z
        val projection = (toCellX * dx + toCellY * dy + toCellZ * dz) / length2
        if (!projection.isFinite() || projection < 0.0 || projection > 1.0) return false
        val closestX = camera.x + projection * dx
        val closestY = camera.y + projection * dy
        val closestZ = camera.z + projection * dz
        val offX = cellCenterX - closestX
        val offY = cellCenterY - closestY
        val offZ = cellCenterZ - closestZ
        val size = frame.voxelSizeMicrometres.toDouble() / 1_000.0
        val halfDiagonal = size * sqrt(3.0) * 0.5
        val projectionExtent = halfDiagonal / sqrt(length2)
        if (projection + projectionExtent < 0.0 || projection - projectionExtent > 1.0) return false
        val voxelMinX = voxelX * size
        val voxelMinY = voxelY * size
        val voxelMinZ = voxelZ * size
        val voxelMaxX = voxelMinX + size
        val voxelMaxY = voxelMinY + size
        val voxelMaxZ = voxelMinZ + size
        val safety = configuration.safetyBandMillimetres.toDouble()
        val endpointBoxDistance2 = pointBoxDistance2(
            endpoint.x,
            endpoint.y,
            endpoint.z,
            voxelMinX,
            voxelMinY,
            voxelMinZ,
            voxelMaxX,
            voxelMaxY,
            voxelMaxZ,
        )
        // A free voxel is unsafe when any part of its volume intersects the
        // endpoint exclusion ball. Equality is unsafe as well.
        if (!endpointBoxDistance2.isFinite() || endpointBoxDistance2 <= safety * safety) return false
        val voxelRadius = halfDiagonal
        if (offX * offX + offY * offY + offZ * offZ > voxelRadius * voxelRadius) return false
        return true
    }

    private fun validateCanonicalSurface(
        surfaces: BoundedCanonicalSurfaceView,
        addressedX: Int,
        addressedY: Int,
        addressedZ: Int,
        lookup: CanonicalSurfaceScratch,
    ) {
        if (!lookup.present || lookup.addressedVoxelX != addressedX ||
            lookup.addressedVoxelY != addressedY || lookup.addressedVoxelZ != addressedZ
        ) throw DepthLookupFailure()
        if (canonicalAccess { surfaces.surfaceCount } == 0) throw DepthLookupFailure()
        val surfaceId = lookup.id
        val surfaceX = lookup.voxelX
        val surfaceY = lookup.voxelY
        val surfaceZ = lookup.voxelZ
        if (!voxelInRange(surfaceX, surfaceY, surfaceZ)) throw DepthLookupFailure()
        val canonicalFound = canonicalAccess { surfaces.findSurfaceByIdInto(surfaceId, canonicalSourceScratch) }
        if (!canonicalFound || canonicalSourceScratch.id != surfaceId) throw DepthLookupFailure()
        if (canonicalSourceScratch.voxelX != surfaceX || canonicalSourceScratch.voxelY != surfaceY ||
            canonicalSourceScratch.voxelZ != surfaceZ
        ) throw DuplicateTargetFailure()
    }

    private fun validateEmittedSource(surfaces: BoundedCanonicalSurfaceView, sourceId: Long) {
        val canonical = canonicalAccess { surfaces.findSurfaceById(SurfaceId(sourceId)) } ?: throw DepthLookupFailure()
        if (canonical.id.value != sourceId || !voxelInRange(canonical.voxel)) throw DepthLookupFailure()
    }

    private fun pointBoxDistance2(
        x: Double,
        y: Double,
        z: Double,
        minX: Double,
        minY: Double,
        minZ: Double,
        maxX: Double,
        maxY: Double,
        maxZ: Double,
    ): Double {
        fun axisDistance(value: Double, minimum: Double, maximum: Double): Double = when {
            value < minimum -> minimum - value
            value > maximum -> value - maximum
            else -> 0.0
        }
        val dx = axisDistance(x, minX, maxX)
        val dy = axisDistance(y, minY, maxY)
        val dz = axisDistance(z, minZ, maxZ)
        return dx * dx + dy * dy + dz * dz
    }

    private fun writeStage(row: Int, index: Int) {
        rowVoxelKey[row] = packVisibilityGridKey(stageX[index], stageY[index], stageZ[index])
        rowOccupied[row] = stageOccupied[index]
        rowFree[row] = stageFree[index]
        rowDirections[row] = stageDirections[index]
        rowFlags[row] = (flags(stageContradicted[index], stagePublished[index]).toInt() or
            (if (estimateState(index) and ESTIMATE_VALID != 0) ESTIMATED_FLAG else 0)).toByte()
        if (estimateState(index) and ESTIMATE_VALID != 0) {
            val frame = requireNotNull(pending).frame
            rowEstimateOffsetX!![row] = estimateOffset(stageOccupiedPointX[index], stageX[index], frame)
            rowEstimateOffsetY!![row] = estimateOffset(stageOccupiedPointY[index], stageY[index], frame)
            rowEstimateOffsetZ!![row] = estimateOffset(stageOccupiedPointZ[index], stageZ[index], frame)
        }
        rowSourceId[row] = stageSourceId[index]
        rowSourceKey[row] = stageSourceKey[index]
        rowPackedNormal[row] = stagePackedNormal[index]
        rowNormalConfidence[row] = stageNormalConfidence[index]
        rowLineageCount[row] = stageLineageCount[index]
    }

    private fun stageFind(voxel: Voxel): Int = stageFind(voxel.x, voxel.y, voxel.z)

    private fun stageFind(x: Int, y: Int, z: Int): Int {
        val key = packVisibilityGridKey(x, y, z)
        var slot = stageHash(key)
        while (true) {
            val row = stageHashRows[slot]
            if (row == EMPTY_ROW) return EMPTY_ROW
            if (packVisibilityGridKey(stageX[row], stageY[row], stageZ[row]) == key) return row
            slot = (slot + 1) and (stageHashCapacity - 1)
        }
    }

    private fun stageIndex(
        x: Int,
        y: Int,
        z: Int,
        surfaceId: Long,
        surfaceX: Int,
        surfaceY: Int,
        surfaceZ: Int,
        surfacePackedNormal: Int,
        surfaceNormalConfidence: Int,
        surfaceLineageCount: Int,
    ): Int {
        var index = stageFind(x, y, z)
        if (index != EMPTY_ROW) {
            if (surfaceId != 0L) stageAttach(
                index, surfaceId, surfaceX, surfaceY, surfaceZ,
                surfacePackedNormal, surfaceNormalConfidence, surfaceLineageCount,
            )
            return index
        }
        if (stageCount >= stageCapacity) throw StageCapacityFailure()
        index = stageCount++
        stageX[index] = x
        stageY[index] = y
        stageZ[index] = z
        markEvidenceBlock(x, y, z)
        stageOccupied[index] = 0
        stageFree[index] = 0
        stageDirections[index] = 0
        stageContradicted[index] = false
        stageSourceId[index] = 0
        stageSourceKey[index] = packVisibilityGridKey(x, y, z)
        stagePackedNormal[index] = 0
        stageNormalConfidence[index] = 0
        stagePublished[index] = false
        // Reuse the later planned-change source column as the row-local
        // canonical attachment marker. Planning overwrites it only after all
        // lookups and relation admission have completed.
        stageChangeSource[index] = 0
        stageHasOccupied[index] = false
        stageOccupiedPointX[index] = 0.0
        stageOccupiedPointY[index] = 0.0
        stageOccupiedPointZ[index] = 0.0
        stageEstimateState?.set(index, 0)
        stageOrder[index] = 0
        stageFreeBin[index] = NO_DIRECTION.toByte()
        val resident = findRow(x, y, z)
        if (resident != EMPTY_ROW) {
            stageOccupied[index] = rowOccupied[resident]
            stageFree[index] = rowFree[resident]
            stageDirections[index] = rowDirections[resident]
            stageContradicted[index] = rowFlags[resident].toInt() and CONTRADICTED_FLAG != 0
            stagePublished[index] = rowFlags[resident].toInt() and PUBLISHED_FLAG != 0
            stageSourceId[index] = rowSourceId[resident]
            stageSourceKey[index] = rowSourceKey[resident]
            stagePackedNormal[index] = rowPackedNormal[resident]
            stageNormalConfidence[index] = rowNormalConfidence[resident]
            stageLineageCount[index] = rowLineageCount[resident]
            if (rowFlags[resident].toInt() and ESTIMATED_FLAG != 0) {
                stageEstimateState!![index] = ESTIMATE_VALID.toByte()
                val frame = requireNotNull(activeFrame)
                stageOccupiedPointX[index] = centerCoordinate(x, frame) + rowEstimateOffsetX!![resident]
                stageOccupiedPointY[index] = centerCoordinate(y, frame) + rowEstimateOffsetY!![resident]
                stageOccupiedPointZ[index] = centerCoordinate(z, frame) + rowEstimateOffsetZ!![resident]
            }
        }
        var slot = stageHash(packVisibilityGridKey(x, y, z))
        while (stageHashRows[slot] != EMPTY_ROW) slot = (slot + 1) and (stageHashCapacity - 1)
        stageHashRows[slot] = index
        if (surfaceId != 0L) stageAttach(
            index, surfaceId, surfaceX, surfaceY, surfaceZ,
            surfacePackedNormal, surfaceNormalConfidence, surfaceLineageCount,
        )
        return index
    }

    private fun stageAttach(
        index: Int,
        surfaceId: Long,
        surfaceX: Int,
        surfaceY: Int,
        surfaceZ: Int,
        surfacePackedNormal: Int,
        surfaceNormalConfidence: Int,
        surfaceLineageCount: Int,
    ) {
        val prior = stageSourceIdValue(index)
        stageAddRelation(index, surfaceId)
        if (prior == 0L || surfaceId < prior) {
            if (prior != 0L && surfaceId != prior) stageChangeSource[index] = prior.toInt()
            stageSourceId[index] = surfaceId.toInt()
            stageSourceKey[index] = packVisibilityGridKey(surfaceX, surfaceY, surfaceZ)
            stagePackedNormal[index] = surfacePackedNormal.toShort()
            stageNormalConfidence[index] = surfaceNormalConfidence.toByte()
            stageLineageCount[index] = surfaceLineageCount.toShort()
            stagePublished[index] = false
        }
    }

    private fun stageAddRelation(index: Int, sourceId: Long) {
        stageRelationAdmissionWork = checkedAdd(stageRelationAdmissionWork, 1)
        val primary = stageSourceIdValue(index)
        if (primary == sourceId && primary != 0L) return
        val attached = stageChangeSource[index].toLong() and 0xffff_ffffL
        if (attached == sourceId) return
        if (attached != 0L) throw DepthLookupFailure()
        if (primary == 0L) return
        stageChangeSource[index] = sourceId.toInt()
    }

    /** Constant-size diagnostic read of one retained estimate; never enumerates the map. */
    @Synchronized
    internal fun surfacePositionEstimate(voxel: Voxel): DepthSurfacePositionEstimate? {
        if (closed || !estimatesEnabled || !voxelInRange(voxel)) return null
        val row = findRow(voxel)
        if (row == EMPTY_ROW || rowFlags[row].toInt() and ESTIMATED_FLAG == 0) return null
        val frame = activeFrame ?: return null
        val confidence = rowNormalConfidence[row].toInt() and 0xff
        return DepthSurfacePositionEstimate(
            centerCoordinate(voxel.x, frame) + rowEstimateOffsetX!![row],
            centerCoordinate(voxel.y, frame) + rowEstimateOffsetY!![row],
            centerCoordinate(voxel.z, frame) + rowEstimateOffsetZ!![row],
            rowPackedNormal[row].toInt() and 0xffff,
            if (confidence == 255) 4 else confidence / 64,
        )
    }

    @Synchronized
    internal fun surfaceEstimationReceipt(): DepthSurfaceEstimationReceipt = DepthSurfaceEstimationReceipt(
        configuration.surfaceEstimationMode, localPlane?.fittedSamples ?: 0,
        stageIndependentPlaneFits, stageAssociatedSamples, localPlane?.workUnits ?: 0, stageAssociationWork,
        reuseFullFitAttempts, reuseFullFitSuccesses, reuseAttempts, reuseQualifiedPoints, reuseRefusals,
        reuseWitnessWork, reuseScratchWork,
    )

    /** Relation scratch ownership ends before sorting; no input is retained. */
    private fun endBulkScratch() { bulkScratchOpen = false }

    override fun cellWord(index: Int): Int {
        check(bulkScratchOpen && index in 0 until 3 * stageCapacity)
        bulkScratchWork++
        return when { index < stageCapacity -> stageRelationTarget[index]
            index < 2 * stageCapacity -> stageRelationSource[index - stageCapacity]
            else -> stageRelationOrder[index - 2 * stageCapacity] }
    }

    override fun cellWord(index: Int, value: Int) {
        check(bulkScratchOpen && index in 0 until 3 * stageCapacity)
        bulkScratchWork++
        when { index < stageCapacity -> stageRelationTarget[index] = value
            index < 2 * stageCapacity -> stageRelationSource[index - stageCapacity] = value
            else -> stageRelationOrder[index - 2 * stageCapacity] = value }
    }

    override fun cellMoment(cell: Int, component: Int): Double {
        check(bulkScratchOpen && cell in 0 until bulkCellCount && component in 0..8)
        bulkScratchWork++
        val index = cell * 3 + component % 3
        return when (component / 3) { 0 -> stageOccupiedPointX[index]
            1 -> stageOccupiedPointY[index]; else -> stageOccupiedPointZ[index] }
    }

    override fun cellMoment(cell: Int, component: Int, value: Double) {
        check(bulkScratchOpen && cell in 0 until bulkCellCount && component in 0..8)
        bulkScratchWork++
        val index = cell * 3 + component % 3
        when (component / 3) { 0 -> stageOccupiedPointX[index] = value
            1 -> stageOccupiedPointY[index] = value; else -> stageOccupiedPointZ[index] = value }
    }

    private fun bulkBase(cell: Int): Int = 2 * bulkCellCapacity + DepthCellPlaneEstimator.WORDS * cell
    private fun bulkKey(base: Int): Long = (cellWord(base).toLong() and 0xffffffffL) or (cellWord(base + 1).toLong() shl 32)
    private fun bulkHash(key: Long): Int {
        var mixed = key xor (key ushr 33)
        mixed *= -49064778989728563L; mixed = mixed xor (mixed ushr 33)
        return mixed.toInt() and (2 * bulkCellCapacity - 1)
    }

    /** Exact voxel hash, with exhaustion selecting raw evidence for the entire estimate. */
    private fun bulkFind(key: Long, insert: Boolean): Int {
        if (!bulkScratchOpen || bulkCellCapacity == 0) return EMPTY_ROW
        var slot = bulkHash(key)
        for (probe in 0 until 16) {
            val cell = cellWord(slot)
            if (cell == EMPTY_ROW) {
                if (!insert || bulkCellCount == bulkCellCapacity) return EMPTY_ROW
                val created = bulkCellCount++
                val base = bulkBase(created)
                for (word in 0 until DepthCellPlaneEstimator.WORDS) cellWord(base + word, 0)
                cellWord(base, key.toInt()); cellWord(base + 1, (key ushr 32).toInt())
                cellWord(base + DepthCellPlaneEstimator.MIN_DEPTH, Int.MAX_VALUE)
                cellWord(base + DepthCellPlaneEstimator.MIN_X, Int.MAX_VALUE)
                cellWord(base + DepthCellPlaneEstimator.MIN_Y, Int.MAX_VALUE)
                cellWord(base + DepthCellPlaneEstimator.MAX_DEPTH, Int.MIN_VALUE)
                cellWord(base + DepthCellPlaneEstimator.MAX_X, Int.MIN_VALUE)
                cellWord(base + DepthCellPlaneEstimator.MAX_Y, Int.MIN_VALUE)
                for (component in 0..8) cellMoment(created, component, 0.0)
                cellWord(slot, created)
                return created
            }
            if (bulkKey(bulkBase(cell)) == key) return cell
            slot = (slot + 1) and (2 * bulkCellCapacity - 1)
        }
        return EMPTY_ROW
    }

    private fun bulkEligible(metadata: DepthEvidenceMetadataView, samples: DepthEvidenceSampleAccess, index: Int): Boolean =
        samples.xAt(index) in 0 until metadata.intrinsics.imageWidth && samples.yAt(index) in 0 until metadata.intrinsics.imageHeight &&
            samples.confidenceAt(index) >= configuration.confidenceMinimum && samples.depthMillimetresAt(index) in
            configuration.minimumDepthMillimetres..configuration.maximumDepthMillimetres

    /** Same scalar transform and floor quantization as the ordinary measured endpoint. */
    private fun bulkMeasuredKey(metadata: DepthEvidenceMetadataView, samples: DepthEvidenceSampleAccess, index: Int): Long {
        val plane = requireNotNull(localPlane)
        if (!plane.loadMeasuredPoint(metadata, samples, index)) throw ArithmeticException("non-finite depth transform")
        val size = metadata.groupFrame.voxelSizeMicrometres
        val x = floor(plane.pointX * 1_000.0 / size); val y = floor(plane.pointY * 1_000.0 / size)
        val z = floor(plane.pointZ * 1_000.0 / size)
        if (!x.isFinite() || !y.isFinite() || !z.isFinite() || x < VOXEL_COORDINATE_MIN || x > VOXEL_COORDINATE_MAX ||
            y < VOXEL_COORDINATE_MIN || y > VOXEL_COORDINATE_MAX || z < VOXEL_COORDINATE_MIN || z > VOXEL_COORDINATE_MAX)
            throw ArithmeticException("depth quantization overflow")
        return packVisibilityGridKey(x.toInt(), y.toInt(), z.toInt())
    }

    private fun rejectBulkCell(base: Int) { cellWord(base + DepthCellPlaneEstimator.STATUS, DepthCellPlaneEstimator.REJECTED) }
    private fun useUnrefinedBulkEstimate() { bulkWholeEstimateUnrefined = true; bulkQualifiedCells = 0; endBulkScratch() }

    /** Whole population first; no evidence row or per-point fit exists in this phase. */
    private fun prepareBulkPlanes(metadata: DepthEvidenceMetadataView, samples: DepthEvidenceSampleAccess, camera: DepthPointMm) {
        bulkCellCount = 0; bulkQualifiedCells = 0; bulkCovarianceSolves = 0; bulkCertificateAttempts = 0
        bulkResidualWork = 0; bulkScratchWork = 0; bulkProjectedSamples = 0
        bulkWholeEstimateUnrefined = false
        bulkScratchOpen = bulkCellCapacity > 0
        val plane = requireNotNull(localPlane)
        if (!bulkScratchOpen) { useUnrefinedBulkEstimate(); plane.index(metadata, samples, configuration); return }
        for (slot in 0 until 2 * bulkCellCapacity) cellWord(slot, EMPTY_ROW)
        if (!plane.indexPixelConflicts(metadata, samples, configuration)) {
            bulkScratchWork += plane.workUnits; useUnrefinedBulkEstimate(); plane.index(metadata, samples, configuration); return
        }
        for (index in 0 until samples.count) {
            if (!bulkEligible(metadata, samples, index)) continue
            val cell = bulkFind(bulkMeasuredKey(metadata, samples, index), true)
            if (cell == EMPTY_ROW) {
                bulkScratchWork += plane.workUnits; useUnrefinedBulkEstimate(); plane.index(metadata, samples, configuration); return
            }
            val base = bulkBase(cell)
            DepthCellPlaneEstimator.accumulate(this, cell, base, plane.pointX, plane.pointY, plane.pointZ)
            val depth = samples.depthMillimetresAt(index); val x = samples.xAt(index); val y = samples.yAt(index)
            cellWord(base + 4, minOf(cellWord(base + 4), depth)); cellWord(base + 5, maxOf(cellWord(base + 5), depth))
            cellWord(base + 6, minOf(cellWord(base + 6), x)); cellWord(base + 7, maxOf(cellWord(base + 7), x))
            cellWord(base + 8, minOf(cellWord(base + 8), y)); cellWord(base + 9, maxOf(cellWord(base + 9), y))
            if (plane.pixelConflicted(samples, index)) rejectBulkCell(base)
        }
        bulkScratchWork += plane.workUnits
        for (cell in 0 until bulkCellCount) {
            val base = bulkBase(cell)
            if (cellWord(base + DepthCellPlaneEstimator.STATUS) == DepthCellPlaneEstimator.REJECTED) continue
            if (cellWord(base + 5) - cellWord(base + 4) > 2 * minOf(30.0, metadata.groupFrame.voxelSizeMicrometres / 1_000.0 * 0.3)) {
                rejectBulkCell(base); continue
            }
            bulkCovarianceSolves++
            if (!DepthCellPlaneEstimator.solve(this, cell, base, camera.x, camera.y, camera.z)) rejectBulkCell(base)
        }
        val radius = minOf(50.0, metadata.groupFrame.voxelSizeMicrometres / 1_000.0)
        val residualLimit = minOf(12.0, metadata.groupFrame.voxelSizeMicrometres / 1_000.0 * 0.12)
        for (index in 0 until samples.count) {
            if (!bulkEligible(metadata, samples, index)) continue
            val cell = bulkFind(bulkMeasuredKey(metadata, samples, index), false)
            check(cell != EMPTY_ROW)
            val base = bulkBase(cell)
            if (cellWord(base + 2) != DepthCellPlaneEstimator.SOLVED) continue
            bulkResidualWork++
            val dx = plane.pointX - DepthCellPlaneEstimator.double(this, base + 16)
            val dy = plane.pointY - DepthCellPlaneEstimator.double(this, base + 18)
            val dz = plane.pointZ - DepthCellPlaneEstimator.double(this, base + 20)
            val residual = abs(dx * DepthCellPlaneEstimator.double(this, base + 22) + dy * DepthCellPlaneEstimator.double(this, base + 24) +
                dz * DepthCellPlaneEstimator.double(this, base + 26))
            val distance = dx * dx + dy * dy + dz * dz
            if (!distance.isFinite() || !residual.isFinite() || residual > residualLimit || distance > radius * radius) {
                rejectBulkCell(base); continue
            }
            DepthCellPlaneEstimator.double(this, base + 14, maxOf(DepthCellPlaneEstimator.double(this, base + 14), residual))
            val old = cellWord(base + 10)
            val best = DepthCellPlaneEstimator.double(this, base + 12)
            if (distance < best || (distance == best && (old < 0 || xBefore(samples, index, old)))) {
                DepthCellPlaneEstimator.double(this, base + 12, distance); cellWord(base + 10, index)
            }
        }
        plane.index(metadata, samples, configuration)
        for (cell in 0 until bulkCellCount) {
            val base = bulkBase(cell)
            if (cellWord(base + 2) != DepthCellPlaneEstimator.SOLVED) continue
            val centre = cellWord(base + 10)
            if (centre < 0) { rejectBulkCell(base); continue }
            bulkCertificateAttempts++
            if (!plane.estimate(metadata, samples, centre, configuration, populationResidualFloor = DepthCellPlaneEstimator.double(this, base + 14)) ||
                plane.normalX * DepthCellPlaneEstimator.double(this, base + 22) + plane.normalY * DepthCellPlaneEstimator.double(this, base + 24) +
                plane.normalZ * DepthCellPlaneEstimator.double(this, base + 26) < DepthCellPlaneEstimator.ANGULAR_COSINE) {
                rejectBulkCell(base); continue
            }
            // Moments are dead after solving; three slots now hold the raw certificate centre.
            check(plane.loadMeasuredPoint(metadata, samples, centre))
            cellMoment(cell, 0, plane.pointX); cellMoment(cell, 1, plane.pointY); cellMoment(cell, 2, plane.pointZ)
            cellWord(base + 2, DepthCellPlaneEstimator.QUALIFIED)
        }
        // Every member must also stay in the original certificate's physical neighborhood.
        for (index in 0 until samples.count) {
            if (!bulkEligible(metadata, samples, index)) continue
            val cell = bulkFind(bulkMeasuredKey(metadata, samples, index), false)
            check(cell != EMPTY_ROW)
            val base = bulkBase(cell)
            if (cellWord(base + 2) != DepthCellPlaneEstimator.QUALIFIED) continue
            bulkResidualWork++
            val dx = plane.pointX - cellMoment(cell, 0); val dy = plane.pointY - cellMoment(cell, 1); val dz = plane.pointZ - cellMoment(cell, 2)
            if (dx * dx + dy * dy + dz * dz > radius * radius ||
                abs(samples.depthMillimetresAt(index) - samples.depthMillimetresAt(cellWord(base + 10))) >
                minOf(30.0, metadata.groupFrame.voxelSizeMicrometres / 1_000.0 * 0.3)) rejectBulkCell(base)
        }
        for (cell in 0 until bulkCellCount) if (cellWord(bulkBase(cell) + 2) == DepthCellPlaneEstimator.QUALIFIED) bulkQualifiedCells++
    }

    private fun xBefore(samples: DepthEvidenceSampleAccess, a: Int, b: Int): Boolean = samples.xAt(a) < samples.xAt(b) ||
        (samples.xAt(a) == samples.xAt(b) && (samples.yAt(a) < samples.yAt(b) ||
            (samples.yAt(a) == samples.yAt(b) && samples.depthMillimetresAt(a) < samples.depthMillimetresAt(b))))

    private fun projectBulkPoint(measured: Voxel, endpoint: DepthPointMm): Boolean {
        if (!bulkScratchOpen || bulkWholeEstimateUnrefined) return false
        val cell = bulkFind(packVisibilityGridKey(measured.x, measured.y, measured.z), false)
        check(cell != EMPTY_ROW)
        val base = bulkBase(cell)
        if (cellWord(base + 2) != DepthCellPlaneEstimator.QUALIFIED) return false
        val nx = DepthCellPlaneEstimator.double(this, base + 22); val ny = DepthCellPlaneEstimator.double(this, base + 24)
        val nz = DepthCellPlaneEstimator.double(this, base + 26)
        val residual = (endpoint.x - DepthCellPlaneEstimator.double(this, base + 16)) * nx +
            (endpoint.y - DepthCellPlaneEstimator.double(this, base + 18)) * ny + (endpoint.z - DepthCellPlaneEstimator.double(this, base + 20)) * nz
        requireNotNull(localPlane).setProjection(endpoint.x - residual * nx, endpoint.y - residual * ny, endpoint.z - residual * nz, nx, ny, nz)
        bulkProjectedSamples++
        return true
    }

    @Synchronized internal fun bulkPlaneReceipt(): DepthBulkPlaneReceipt = DepthBulkPlaneReceipt(
        configuration.planeFitStrategy, bulkCellCapacity, bulkCellCount, bulkQualifiedCells, bulkCellCount - bulkQualifiedCells,
        bulkCovarianceSolves, bulkCertificateAttempts, bulkResidualWork, bulkScratchWork, bulkProjectedSamples, bulkWholeEstimateUnrefined)

    internal fun bulkScratchWordForTest(index: Int): Int = cellWord(index)
    internal fun bulkHashForTest(key: Long): Int = bulkHash(key)
    @Synchronized internal fun currentProjectedPointForTest(): DepthPointMm = requireNotNull(localPlane).let {
        DepthPointMm(it.pointX, it.pointY, it.pointZ)
    }

    private fun beginPlaneSupportReusePhase() {
        supportCacheCount = 0
        reuseFullFitAttempts = 0; reuseFullFitSuccesses = 0
        reuseAttempts = 0; reuseQualifiedPoints = 0; reuseRefusals = 0
        reuseWitnessWork = 0; reuseScratchWork = 0
        supportCacheOpen = supportCacheCapacity > 0
        if (supportCacheOpen) for (index in 0 until supportCacheCapacity * 2) writePlaneSupportWord(index, EMPTY_ROW)
    }

    private fun endPlaneSupportReusePhase() {
        supportCacheOpen = false
        supportCacheCount = 0
    }

    override fun readPlaneSupportWord(index: Int): Int {
        check(supportCacheOpen) { "current-estimate support scratch is no longer owned by ingestion" }
        require(index in 0 until supportCacheCapacity * 66)
        reuseScratchWork++
        return when {
            index < stageCapacity -> stageRelationTarget[index]
            index < stageCapacity * 2 -> stageRelationSource[index - stageCapacity]
            else -> stageRelationOrder[index - stageCapacity * 2]
        }
    }

    override fun writePlaneSupportWord(index: Int, value: Int) {
        check(supportCacheOpen) { "current-estimate support scratch is no longer owned by ingestion" }
        require(index in 0 until supportCacheCapacity * 66)
        reuseScratchWork++
        when {
            index < stageCapacity -> stageRelationTarget[index] = value
            index < stageCapacity * 2 -> stageRelationSource[index - stageCapacity] = value
            else -> stageRelationOrder[index - stageCapacity * 2] = value
        }
    }

    private fun supportCertificateBase(entry: Int): Int = supportCacheCapacity * 2 + entry * 64

    private fun findSupportCertificate(key: Long): Int {
        check(supportCacheOpen)
        val mask = supportCacheCapacity * 2 - 1
        var slot = mixedHash(key) and mask
        repeat(minOf(16, supportCacheCapacity * 2)) {
            val entry = readPlaneSupportWord(slot)
            if (entry == EMPTY_ROW) return EMPTY_ROW
            check(entry in 0 until supportCacheCount)
            val base = supportCertificateBase(entry)
            val cached = (readPlaneSupportWord(base).toLong() and 0xffff_ffffL) or
                (readPlaneSupportWord(base + 1).toLong() shl 32)
            if (cached == key) return entry
            slot = (slot + 1) and mask
        }
        return EMPTY_ROW
    }

    private fun canInsertSupportCertificate(key: Long): Boolean {
        if (supportCacheCount >= supportCacheCapacity) return false
        val mask = supportCacheCapacity * 2 - 1
        var slot = mixedHash(key) and mask
        repeat(minOf(16, supportCacheCapacity * 2)) {
            if (readPlaneSupportWord(slot) == EMPTY_ROW) return true
            slot = (slot + 1) and mask
        }
        return false
    }

    private fun publishSupportCertificate(key: Long, entry: Int) {
        val base = supportCertificateBase(entry)
        writePlaneSupportWord(base, key.toInt()); writePlaneSupportWord(base + 1, (key ushr 32).toInt())
        writePlaneSupportWord(base + 2, EMPTY_ROW)
        if (entry < supportCacheCount) return
        check(entry == supportCacheCount)
        val mask = supportCacheCapacity * 2 - 1
        var slot = mixedHash(key) and mask
        repeat(minOf(16, supportCacheCapacity * 2)) {
            if (readPlaneSupportWord(slot) == EMPTY_ROW) {
                writePlaneSupportWord(slot, entry)
                supportCacheCount++
                return
            }
            slot = (slot + 1) and mask
        }
        error("admitted support-certificate slot disappeared during synchronous ingestion")
    }

    internal fun currentEstimateSupportCapacityForTest(): Int = supportCacheCapacity

    internal fun currentEstimateSupportHashForTest(measured: Voxel): Int {
        require(supportCacheCapacity > 0)
        return mixedHash(packVisibilityGridKey(measured.x, measured.y, measured.z)) and (supportCacheCapacity * 2 - 1)
    }

    internal fun currentEstimateDestinationHintForTest(measured: Voxel): Int {
        check(supportCacheOpen)
        val entry = findSupportCertificate(packVisibilityGridKey(measured.x, measured.y, measured.z))
        return if (entry == EMPTY_ROW) EMPTY_ROW else readPlaneSupportWord(supportCertificateBase(entry) + 2)
    }

    private fun fitWithCurrentEstimateSupport(metadata: DepthEvidenceMetadataView, samples: DepthEvidenceSampleAccess,
        index: Int, measured: Voxel): Boolean {
        val plane = localPlane ?: return false
        val key = packVisibilityGridKey(measured.x, measured.y, measured.z)
        val entry = if (supportCacheOpen) findSupportCertificate(key) else EMPTY_ROW
        if (entry != EMPTY_ROW) {
            reuseAttempts++
            val witnessBefore = plane.workUnits
            val qualified = plane.tryReuse(metadata, samples, index, configuration, this, supportCertificateBase(entry))
            reuseWitnessWork += plane.workUnits - witnessBefore
            if (qualified) { reuseQualifiedPoints++; return true }
            reuseRefusals++
        }
        val writeEntry = if (!supportCacheOpen) EMPTY_ROW else if (entry != EMPTY_ROW) entry
            else if (canInsertSupportCertificate(key)) supportCacheCount else EMPTY_ROW
        reuseFullFitAttempts++
        val qualified = plane.estimate(metadata, samples, index, configuration,
            if (writeEntry != EMPTY_ROW) this else null,
            if (writeEntry != EMPTY_ROW) supportCertificateBase(writeEntry) else 0)
        if (qualified) {
            reuseFullFitSuccesses++
            if (writeEntry != EMPTY_ROW) publishSupportCertificate(key, writeEntry)
        }
        return qualified
    }

    private fun updateSupportDestinationHint(measured: Voxel, destination: Int) {
        if (!supportCacheOpen) return
        val entry = findSupportCertificate(packVisibilityGridKey(measured.x, measured.y, measured.z))
        if (entry != EMPTY_ROW) writePlaneSupportWord(supportCertificateBase(entry) + 2, destination)
    }

    private fun supportCacheCapacityFor(availableStageRows: Int): Int {
        if (!estimatesEnabled || (!configuration.reuseCurrentEstimatePlaneSupport &&
            configuration.planeFitStrategy != DepthPlaneFitStrategy.REUSABLE_SAMPLE_SUPPORT)) return 0
        var capacity = 0
        var candidate = 1
        while (candidate <= 2048 && candidate * 66 <= availableStageRows * 3) {
            capacity = candidate; candidate *= 2
        }
        return capacity
    }

    private fun hasPublicationPlaneSupport(index: Int): Boolean =
        estimateState(index) and ESTIMATE_VALID == 0 || stageNormalConfidenceValue(index) >=
            minOf(255, configuration.occupiedEvidenceToShow * 64)

    private fun estimateState(index: Int): Int = stageEstimateState?.get(index)?.toInt() ?: 0

    /** Bounded 27-cell association; never enumerates canonical surfaces or erases a neighbor. */
    private fun associatedEndpointVoxel(measured: Voxel, frame: VisibilityGroupFrame): Voxel {
        val plane = requireNotNull(localPlane)
        val size = frame.voxelSizeMicrometres / 1_000.0
        val residualGate = minOf(30.0, size * 0.3)
        val tangentGate = minOf(35.0, size * 0.35)
        var selected = measured
        var bestDistance = Double.POSITIVE_INFINITY
        for (dx in -1..1) for (dy in -1..1) for (dz in -1..1) {
            stageAssociationWork++
            val x = measured.x + dx; val y = measured.y + dy; val z = measured.z + dz
            if (!voxelInRange(x, y, z)) continue
            val cellDistance = sqrt((dx * dx + dy * dy + dz * dz).toDouble())
            if (cellDistance > 0.0 && kotlin.math.abs(dx * plane.normalX + dy * plane.normalY +
                    dz * plane.normalZ) < cellDistance * 0.5) continue
            val row = findRow(x, y, z)
            if (row == EMPTY_ROW || (rowOccupied[row].toInt() and 0xff) == 0 ||
                rowFlags[row].toInt() and ESTIMATED_FLAG == 0 ||
                rowFlags[row].toInt() and CONTRADICTED_FLAG != 0 ||
                normalAgreement(rowPackedNormal[row].toInt() and 0xffff, plane) < NORMAL_AGREEMENT_COSINE) continue
            val px = plane.pointX - (centerCoordinate(x, frame) + rowEstimateOffsetX!![row])
            val py = plane.pointY - (centerCoordinate(y, frame) + rowEstimateOffsetY!![row])
            val pz = plane.pointZ - (centerCoordinate(z, frame) + rowEstimateOffsetZ!![row])
            val residual = px * plane.normalX + py * plane.normalY + pz * plane.normalZ
            val distance = px * px + py * py + pz * pz
            if (kotlin.math.abs(residual) > residualGate || distance - residual * residual > tangentGate * tangentGate) continue
            if (distance < bestDistance) { selected = Voxel(x, y, z); bestDistance = distance }
        }
        return selected
    }

    /** One local fit per independent estimate updates the bounded temporal mean. */
    private fun updateStagedEstimate(index: Int, frame: VisibilityGroupFrame) {
        val alreadyObserved = estimateState(index) and ESTIMATE_OBSERVED != 0
        val plane = requireNotNull(localPlane)
        val valid = estimateState(index) and ESTIMATE_VALID != 0
        val size = frame.voxelSizeMicrometres / 1_000.0
        if (valid) {
            val dx = plane.pointX - stageOccupiedPointX[index]
            val dy = plane.pointY - stageOccupiedPointY[index]
            val dz = plane.pointZ - stageOccupiedPointZ[index]
            if (normalAgreement(stagePackedNormalValue(index), plane) < NORMAL_AGREEMENT_COSINE ||
                kotlin.math.abs(dx * plane.normalX + dy * plane.normalY + dz * plane.normalZ) > minOf(30.0, size * 0.3)) return
        }
        // stageOrder is unused until sorting; it counts coherent samples in
        // this acquired estimate without reserving another stage-sized array.
        val weight = if (alreadyObserved) stageOrder[index] else 0
        val x = (stageOccupiedPointX[index] * weight + plane.pointX) / (weight + 1)
        val y = (stageOccupiedPointY[index] * weight + plane.pointY) / (weight + 1)
        val z = (stageOccupiedPointZ[index] * weight + plane.pointZ) / (weight + 1)
        if (kotlin.math.abs(x - centerCoordinate(stageX[index], frame)) > Short.MAX_VALUE ||
            kotlin.math.abs(y - centerCoordinate(stageY[index], frame)) > Short.MAX_VALUE ||
            kotlin.math.abs(z - centerCoordinate(stageZ[index], frame)) > Short.MAX_VALUE) return
        stageOccupiedPointX[index] = x; stageOccupiedPointY[index] = y; stageOccupiedPointZ[index] = z
        stageOrder[index] = weight + 1
        if (!alreadyObserved) {
            stagePackedNormal[index] = encodeNormal(DepthPointMm(plane.normalX, plane.normalY, plane.normalZ)).toShort()
            stageNormalConfidence[index] = (if (valid) stageNormalConfidenceValue(index) + 64 else 64).coerceAtMost(255).toByte()
            stageIndependentPlaneFits++
        }
        stageEstimateState!![index] = (ESTIMATE_VALID or ESTIMATE_OBSERVED).toByte()
    }

    private fun finalizeStagedPosition(index: Int, frame: VisibilityGroupFrame) {
        if (estimateState(index) and ESTIMATE_OBSERVED == 0) return
        val row = findRow(stageX[index], stageY[index], stageZ[index])
        if (row == EMPTY_ROW || rowFlags[row].toInt() and ESTIMATED_FLAG == 0) return
        val weight = minOf(3, (rowNormalConfidence[row].toInt() and 0xff) / 64)
        val oldX = centerCoordinate(stageX[index], frame) + rowEstimateOffsetX!![row]
        val oldY = centerCoordinate(stageY[index], frame) + rowEstimateOffsetY!![row]
        val oldZ = centerCoordinate(stageZ[index], frame) + rowEstimateOffsetZ!![row]
        stageOccupiedPointX[index] = (oldX * weight + stageOccupiedPointX[index]) / (weight + 1)
        stageOccupiedPointY[index] = (oldY * weight + stageOccupiedPointY[index]) / (weight + 1)
        stageOccupiedPointZ[index] = (oldZ * weight + stageOccupiedPointZ[index]) / (weight + 1)
    }

    private fun normalAgreement(packed: Int, plane: DepthLocalPlaneEstimator): Double {
        var x = (packed ushr 8).toByte().toInt() / 127.0
        var y = packed.toByte().toInt() / 127.0
        val z = 1.0 - kotlin.math.abs(x) - kotlin.math.abs(y)
        if (z < 0.0) {
            val oldX = x
            x = (1.0 - kotlin.math.abs(y)) * if (oldX < 0.0) -1.0 else 1.0
            y = (1.0 - kotlin.math.abs(oldX)) * if (y < 0.0) -1.0 else 1.0
        }
        val length = sqrt(x * x + y * y + z * z)
        return (x * plane.normalX + y * plane.normalY + z * plane.normalZ) / length
    }

    private fun estimateOffset(point: Double, cell: Int, frame: VisibilityGroupFrame): Short =
        kotlin.math.round(point - centerCoordinate(cell, frame)).toInt().toShort()

    private fun occupiedPoint(index: Int): DepthPointMm = DepthPointMm(
        stageOccupiedPointX[index], stageOccupiedPointY[index], stageOccupiedPointZ[index],
    )

    private fun sortStageOrder(): Int {
        for (index in 0 until stageCount) stageOrder[index] = index
        var source = stageOrder
        var destination = stageSortScratch
        for (axis in 2 downTo 0) {
            for (shift in 0 until Int.SIZE_BITS step RADIX_BITS) {
                java.util.Arrays.fill(stageSortCounts, 0)
                for (position in 0 until stageCount) {
                    stageSortCounts[radixByte(source[position], axis, shift)]++
                }
                var prefix = 0
                for (bucket in stageSortCounts.indices) {
                    val count = stageSortCounts[bucket]
                    stageSortCounts[bucket] = prefix
                    prefix += count
                }
                for (position in 0 until stageCount) {
                    val value = source[position]
                    val bucket = radixByte(value, axis, shift)
                    destination[stageSortCounts[bucket]++] = value
                }
                val previousSource = source
                source = destination
                destination = previousSource
            }
        }
        check(source === stageOrder)
        return Math.multiplyExact(stageCount, RADIX_PASSES * 2)
    }

    private fun radixByte(index: Int, axis: Int, shift: Int): Int {
        val coordinate = when (axis) {
            0 -> stageX[index]
            1 -> stageY[index]
            else -> stageZ[index]
        }
        return ((coordinate xor Int.MIN_VALUE) ushr shift) and (RADIX_BUCKETS - 1)
    }

    private fun buildImplicitRelations(): Int {
        val maximum = Math.multiplyExact(stageCount, MAX_RELATIONS_PER_ROW)
        check(maximum <= stageHashRows.size)
        check(maximum <= stageRelationTarget.size + stageRelationOrder.size)
        var count = 0
        for (index in 0 until stageCount) {
            if (stageSourceIdValue(index) != 0L) stageHashRows[count++] = index shl 1
            val secondary = stageChangeSourceValue(index)
            if (secondary != 0L && secondary != stageSourceIdValue(index)) {
                stageHashRows[count++] = (index shl 1) or 1
            }
        }
        check(count <= maximum)
        return count
    }

    private fun implicitRelationTarget(relation: Int): Int = relation ushr 1

    private fun implicitRelationSourceValue(relation: Int): Long =
        if (relation and 1 == 0) {
            stageSourceIdValue(implicitRelationTarget(relation))
        } else {
            stageChangeSourceValue(implicitRelationTarget(relation))
        }

    private inline fun radixSortImplicitRelations(
        offset: Int,
        count: Int,
        crossinline key: (Int) -> Int,
    ): Int {
        check(offset >= 0 && count >= 0 && offset + count <= stageHashRows.size)
        check(offset + count <= stageRelationTarget.size + stageRelationOrder.size)
        var primaryIsSource = true
        for (shift in 0 until Int.SIZE_BITS step RADIX_BITS) {
            java.util.Arrays.fill(stageSortCounts, 0)
            for (position in offset until offset + count) {
                val relation = if (primaryIsSource) stageHashRows[position] else relationScratchAt(position)
                stageSortCounts[(key(relation) ushr shift) and (RADIX_BUCKETS - 1)]++
            }
            var prefix = offset
            for (bucket in stageSortCounts.indices) {
                val bucketCount = stageSortCounts[bucket]
                stageSortCounts[bucket] = prefix
                prefix += bucketCount
            }
            for (position in offset until offset + count) {
                val relation = if (primaryIsSource) stageHashRows[position] else relationScratchAt(position)
                val bucket = (key(relation) ushr shift) and (RADIX_BUCKETS - 1)
                val destination = stageSortCounts[bucket]++
                if (primaryIsSource) setRelationScratch(destination, relation) else stageHashRows[destination] = relation
            }
            primaryIsSource = !primaryIsSource
        }
        check(primaryIsSource)
        return Math.multiplyExact(count, Int.SIZE_BYTES * 2)
    }

    private fun relationScratchAt(position: Int): Int = if (position < stageCapacity) {
        stageRelationTarget[position]
    } else {
        stageRelationOrder[position - stageCapacity]
    }

    private fun setRelationScratch(position: Int, relation: Int) {
        if (position < stageCapacity) {
            stageRelationTarget[position] = relation
        } else {
            stageRelationOrder[position - stageCapacity] = relation
        }
    }

    private fun radixSortComponentOrderByRoot(count: Int): Int = radixSortIndices(
        stageComponentOrder,
        count,
    ) { index -> stageComponentRank[findComponent(index)] }

    private inline fun radixSortIndices(
        values: IntArray,
        count: Int,
        crossinline key: (Int) -> Int,
    ): Int {
        var source = values
        var destination = stageSortScratch
        for (shift in 0 until Int.SIZE_BITS step RADIX_BITS) {
            java.util.Arrays.fill(stageSortCounts, 0)
            for (position in 0 until count) {
                stageSortCounts[(key(source[position]) ushr shift) and (RADIX_BUCKETS - 1)]++
            }
            var prefix = 0
            for (bucket in stageSortCounts.indices) {
                val bucketCount = stageSortCounts[bucket]
                stageSortCounts[bucket] = prefix
                prefix += bucketCount
            }
            for (position in 0 until count) {
                val value = source[position]
                val bucket = (key(value) ushr shift) and (RADIX_BUCKETS - 1)
                destination[stageSortCounts[bucket]++] = value
            }
            val previousSource = source
            source = destination
            destination = previousSource
        }
        check(source === values)
        return Math.multiplyExact(count, Int.SIZE_BYTES * 2)
    }

    private fun radixSortUnsignedValues(values: IntArray, destinationValues: IntArray, count: Int): Int {
        var source = values
        var destination = destinationValues
        for (shift in 0 until Int.SIZE_BITS step RADIX_BITS) {
            java.util.Arrays.fill(stageSortCounts, 0)
            for (position in 0 until count) {
                stageSortCounts[(source[position] ushr shift) and (RADIX_BUCKETS - 1)]++
            }
            var prefix = 0
            for (bucket in stageSortCounts.indices) {
                val bucketCount = stageSortCounts[bucket]
                stageSortCounts[bucket] = prefix
                prefix += bucketCount
            }
            for (position in 0 until count) {
                val value = source[position]
                val bucket = (value ushr shift) and (RADIX_BUCKETS - 1)
                destination[stageSortCounts[bucket]++] = value
            }
            val previousSource = source
            source = destination
            destination = previousSource
        }
        check(source === values)
        return Math.multiplyExact(count, Int.SIZE_BYTES * 2)
    }

    /** Planning has not begun: its order array temporarily owns a conservative block bitset. */
    private fun rebuildEvidenceBlocks(): Int {
        java.util.Arrays.fill(stageComponentOrder, 0, Integer.highestOneBit(stageCapacity), 0)
        val mask = (1L shl 21) - 1L
        for (row in 0 until residentRows) {
            val key = rowVoxelKey[row]
            markEvidenceBlock(
                ((key ushr 42) and mask).toInt() + VOXEL_COORDINATE_MIN,
                ((key ushr 21) and mask).toInt() + VOXEL_COORDINATE_MIN,
                (key and mask).toInt() + VOXEL_COORDINATE_MIN,
            )
        }
        return residentRows
    }

    private fun evidenceBlockWord(x: Int, y: Int, z: Int, size: Int): Int =
        (depthBlockHash(depthBlockParentKey(x, y, z, size)) and (Integer.highestOneBit(stageCapacity) / 2 - 1)) * 2

    private fun markEvidenceBlock(x: Int, y: Int, z: Int) {
        if (stageCapacity < 2) return
        val word = evidenceBlockWord(x, y, z, 1)
        val bits = depthBlockQueryMask(x, y, z, 1)
        stageComponentOrder[word] = stageComponentOrder[word] or bits.toInt()
        stageComponentOrder[word + 1] = stageComponentOrder[word + 1] or (bits ushr 32).toInt()
    }

    private fun hasEvidenceBlock(x: Int, y: Int, z: Int, size: Int): Boolean {
        if (stageCapacity < 2) return true
        val word = evidenceBlockWord(x, y, z, size)
        val bits = depthBlockQueryMask(x, y, z, size)
        return stageComponentOrder[word] and bits.toInt() != 0 ||
            stageComponentOrder[word + 1] and (bits ushr 32).toInt() != 0
    }

    private fun resetStage() {
        endBulkScratch()
        endPlaneSupportReusePhase()
        java.util.Arrays.fill(stageHashRows, EMPTY_ROW)
        java.util.Arrays.fill(stageFreeBin, NO_DIRECTION.toByte())
        java.util.Arrays.fill(stageHasOccupied, false)
        java.util.Arrays.fill(stagePositive, false)
        java.util.Arrays.fill(stageRemoval, false)
        stageCount = 0
        stageRelationCount = 0
        stageRelationAdmissionWork = 0
        stageOrderedSourceCount = 0
        stageOrderedTargetCount = 0
        stageChangeCount = 0
    }

    private fun insertRow(x: Int, y: Int, z: Int): Int {
        check(residentRows < tableCapacity)
        val row = residentRows++
        val key = packVisibilityGridKey(x, y, z)
        rowVoxelKey[row] = key
        var slot = hash(key)
        while (hashRows[slot] != EMPTY_ROW) slot = (slot + 1) and (hashCapacity - 1)
        hashRows[slot] = row
        return row
    }

    private fun findRow(voxel: Voxel): Int = findRow(voxel.x, voxel.y, voxel.z)

    private fun findRow(x: Int, y: Int, z: Int): Int {
        val key = packVisibilityGridKey(x, y, z)
        var slot = hash(key)
        while (true) {
            val row = hashRows[slot]
            if (row == EMPTY_ROW) return EMPTY_ROW
            if (rowVoxelKey[row] == key) return row
            slot = (slot + 1) and (hashCapacity - 1)
        }
    }

    private fun hash(voxel: Voxel): Int = hash(packVisibilityGridKey(voxel.x, voxel.y, voxel.z))

    private fun hash(value: Long): Int {
        return mixedHash(value) and (hashCapacity - 1)
    }

    private fun stageHash(value: Long): Int {
        return mixedHash(value) and (stageHashCapacity - 1)
    }

    private fun mixedHash(value: Long): Int {
        var mixed = value xor (value ushr 33)
        mixed *= -49064778989728563L
        mixed = mixed xor (mixed ushr 33)
        mixed *= -4265267296055464877L
        mixed = mixed xor (mixed ushr 33)
        return mixed.toInt()
    }

    private fun refused(
        reason: DepthEvidenceRefusal,
        metadata: DepthEvidenceMetadataView,
        attempt: DepthEvidenceReceipt? = null,
    ): DepthEvidenceResult.Refused {
        val base = attempt ?: DepthEvidenceReceipt(
            sequence = metadata.sequence,
            sourceTimestampNs = metadata.sourceTimestampNs,
        )
        val receipt = if (reason == DepthEvidenceRefusal.SAMPLE_CAPACITY ||
            reason == DepthEvidenceRefusal.RAY_VISIT_CAPACITY ||
            reason == DepthEvidenceRefusal.SURFACE_CAPACITY
        ) {
            base.copy(capacityRefusals = checkedAdd(base.capacityRefusals, 1))
        } else if (reason == DepthEvidenceRefusal.ARITHMETIC_OVERFLOW) {
            base.copy(overflowCount = checkedAdd(base.overflowCount, 1))
        } else {
            base
        }
        return DepthEvidenceResult.Refused(reason, receipt)
    }

    private fun kindCounts(kinds: ByteArray, count: Int): IntArray = stageKindCounts.also { counts ->
        counts.fill(0)
        for (index in 0 until count) when (kinds[index].toInt()) {
            CHANGE_CREATE -> counts[0]++
            CHANGE_REFINE -> counts[1]++
            CHANGE_RELOCATE -> counts[2]++
            CHANGE_MERGE -> counts[3]++
            CHANGE_SPLIT -> counts[4]++
            CHANGE_REPLACE -> counts[5]++
            CHANGE_REMOVE -> counts[6]++
        }
    }

    private fun hasSeparatedDirections(mask: Int, required: Int): Boolean {
        var count = 0
        for (bin in 0 until DIRECTION_BINS) if (mask and (1 shl bin) != 0) count++
        if (count < required) return false
        for (first in 0 until DIRECTION_BINS) {
            if (mask and (1 shl first) == 0) continue
            for (second in first + 1 until DIRECTION_BINS) {
                if (mask and (1 shl second) != 0 && directionDot(first, second) <= cos(PI / 6.0)) return true
            }
        }
        return false
    }

    private fun directionDot(first: Int, second: Int): Double {
        val leftElevation = when (first / 8) { 0 -> -PI / 4.0; 1 -> 0.0; else -> PI / 4.0 }
        val rightElevation = when (second / 8) { 0 -> -PI / 4.0; 1 -> 0.0; else -> PI / 4.0 }
        val leftAzimuth = -PI + (first % 8 + 0.5) * PI / 4.0
        val rightAzimuth = -PI + (second % 8 + 0.5) * PI / 4.0
        return kotlin.math.sin(leftElevation) * kotlin.math.sin(rightElevation) +
            kotlin.math.cos(leftElevation) * kotlin.math.cos(rightElevation) *
            kotlin.math.cos(leftAzimuth - rightAzimuth)
    }

    private fun directionBin(camera: DepthPointMm, endpoint: DepthPointMm): Int {
        return directionBin(camera.x, camera.y, camera.z, endpoint.x, endpoint.y, endpoint.z)
    }

    private fun directionBin(
        cameraX: Double,
        cameraY: Double,
        cameraZ: Double,
        endpointX: Double,
        endpointY: Double,
        endpointZ: Double,
    ): Int {
        val x = endpointX - cameraX; val y = endpointY - cameraY; val z = endpointZ - cameraZ
        val length = sqrt(x * x + y * y + z * z)
        if (!length.isFinite() || length <= 0.0) return 0
        val azimuth = atan2(x, -z)
        val azimuthBin = floor((azimuth + PI) / (2.0 * PI) * 8.0).toInt().coerceIn(0, 7)
        val elevation = kotlin.math.asin((y / length).coerceIn(-1.0, 1.0))
        val elevationBin = when { elevation < -PI / 8.0 -> 0; elevation > PI / 8.0 -> 2; else -> 1 }
        return elevationBin * 8 + azimuthBin
    }

    private fun center(voxel: Voxel, frame: VisibilityGroupFrame): DepthPointMm {
        return DepthPointMm(
            centerCoordinate(voxel.x, frame), centerCoordinate(voxel.y, frame), centerCoordinate(voxel.z, frame),
        )
    }

    private fun centerCoordinate(coordinate: Int, frame: VisibilityGroupFrame): Double =
        (coordinate + 0.5) * frame.voxelSizeMicrometres.toDouble() / 1_000.0

    private fun transform(matrix: List<Double>, pointMm: DepthPointMm): DepthPointMm? {
        val x = pointMm.x / 1_000.0; val y = pointMm.y / 1_000.0; val z = pointMm.z / 1_000.0
        val tx = matrix[0] * x + matrix[4] * y + matrix[8] * z + matrix[12]
        val ty = matrix[1] * x + matrix[5] * y + matrix[9] * z + matrix[13]
        val tz = matrix[2] * x + matrix[6] * y + matrix[10] * z + matrix[14]
        val tw = matrix[3] * x + matrix[7] * y + matrix[11] * z + matrix[15]
        if (!tx.isFinite() || !ty.isFinite() || !tz.isFinite() || !tw.isFinite() || kotlin.math.abs(tw) <= 1e-12) return null
        return DepthPointMm(tx * 1_000.0 / tw, ty * 1_000.0 / tw, tz * 1_000.0 / tw)
    }

    private fun encodeNormal(vector: DepthPointMm): Int {
        val length = sqrt(vector.x * vector.x + vector.y * vector.y + vector.z * vector.z)
        if (!length.isFinite() || length <= 0.0) return 0
        val nx = vector.x / length; val ny = vector.y / length; val nz = vector.z / length
        val denominator = kotlin.math.abs(nx) + kotlin.math.abs(ny) + kotlin.math.abs(nz)
        var x = nx / denominator; var y = ny / denominator
        if (nz < 0.0) {
            x = (1.0 - kotlin.math.abs(y)) * if (x < 0.0) -1.0 else 1.0
            y = (1.0 - kotlin.math.abs(nx / denominator)) * if (y < 0.0) -1.0 else 1.0
        }
        val octX = (x * 127.0).toInt().coerceIn(-127, 127)
        val octY = (y * 127.0).toInt().coerceIn(-127, 127)
        return ((octX and 0xff) shl 8) or (octY and 0xff)
    }

    private fun comparePoint(left: DepthPointMm, right: DepthPointMm): Int =
        compareValuesBy(left, right, { it.x }, { it.y }, { it.z })

    private fun isAffine(matrix: List<Double>): Boolean = matrix.size == 16 && matrix.all(Double::isFinite) &&
        kotlin.math.abs(matrix[3]) <= 1e-6 && kotlin.math.abs(matrix[7]) <= 1e-6 &&
        kotlin.math.abs(matrix[11]) <= 1e-6 && kotlin.math.abs(matrix[15] - 1.0) <= 1e-6

    private fun voxelInRange(voxel: Voxel): Boolean = voxelInRange(voxel.x, voxel.y, voxel.z)

    private fun voxelInRange(x: Int, y: Int, z: Int): Boolean =
        DepthVoxelAddressing.contains(x, y, z)

    /** Packs the saturated byte value and overflow bit into one scalar. */
    private fun increment(value: Int): Int = if (value >= 255) 255 or (1 shl 8) else value + 1

    private fun checkedAdd(left: Int, right: Int): Int = Math.addExact(left, right)

    private fun checkedBytes(rows: Int): Int = Math.multiplyExact(rows, EVIDENCE_BYTES + if (estimatesEnabled) 6 else 0)

    private inline fun <T> canonicalAccess(access: () -> T): T = try {
        access()
    } catch (_: Exception) {
        throw DepthLookupFailure()
    }

    private fun stageOccupiedValue(index: Int): Int = stageOccupied[index].toInt() and 0xff

    private fun stageFreeValue(index: Int): Int = stageFree[index].toInt() and 0xff

    private fun stageSourceIdValue(index: Int): Long = stageSourceId[index].toLong() and 0xffff_ffffL

    private fun stagePackedNormalValue(index: Int): Int = stagePackedNormal[index].toInt() and 0xffff

    private fun stageNormalConfidenceValue(index: Int): Int = stageNormalConfidence[index].toInt() and 0xff

    internal fun primitiveArraysForAccounting(): List<Any> = listOf(
        hashRows,
        rowVoxelKey, rowOccupied, rowFree, rowDirections, rowFlags, rowSourceId,
        rowSourceKey, rowPackedNormal, rowNormalConfidence, rowLineageCount,
        stageHashRows,
        stageX, stageY, stageZ, stageOccupied, stageFree, stageDirections,
        stageContradicted, stageSourceId, stageSourceKey, stagePackedNormal,
        stageNormalConfidence, stageLineageCount, stagePublished, stageHasOccupied,
        stageOccupiedPointX, stageOccupiedPointY, stageOccupiedPointZ,
        stageFreeBin, stageOrder, stageSortScratch, stageSortCounts,
        stageRelationTarget, stageRelationSource, stageRelationOrder, stageComponentOrder,
        stageComponentRank,
        stageComponent, stagePositive, stageRemoval, stageChangeKind,
        stageChangeSource, stageChangeTarget, stageChangeComponent,
        stageKindCounts,
        stageRetiredSourceIds,
    ) + listOfNotNull(rowEstimateOffsetX, rowEstimateOffsetY, rowEstimateOffsetZ, stageEstimateState) +
        (localPlane?.primitiveArraysForAccounting() ?: emptyList())

    private fun fixedPrimitiveBytes(): Int = Math.toIntExact(
        primitiveArraysForAccounting().sumOf(::modeledArrayBytes),
    )

    private fun modeledArrayBytes(array: Any): Long {
        val elementBytes = when (array) {
            is ByteArray, is BooleanArray -> Byte.SIZE_BYTES
            is ShortArray -> Short.SIZE_BYTES
            is IntArray, is FloatArray -> Int.SIZE_BYTES
            is LongArray, is DoubleArray -> Long.SIZE_BYTES
            else -> error("non-primitive array in depth evidence ledger")
        }
        val payload = java.lang.reflect.Array.getLength(array).toLong() * elementBytes
        val alignedPayload = (payload + OBJECT_ALIGNMENT_BYTES - 1) / OBJECT_ALIGNMENT_BYTES * OBJECT_ALIGNMENT_BYTES
        return ARRAY_HEADER_BYTES + alignedPayload
    }

    private fun modeledMaximumSemanticStateBytes(): Int = Math.addExact(
        fixedPrimitiveBytes(),
        MAXIMUM_ACCEPTED_OUTPUT_RESERVE_BYTES + if (estimatesEnabled) LOCAL_ESTIMATOR_SCALAR_RESERVE_BYTES else 0,
    )

    private fun stageFreeBinValue(index: Int): Int = stageFreeBin[index].toInt()

    private fun stageChangeSourceValue(index: Int): Long = stageChangeSource[index].toLong() and 0xffff_ffffL

    private fun plannedChangeSourceValue(index: Int): Long = stageRelationSource[index].toLong() and 0xffff_ffffL

    private fun flags(contradicted: Boolean, published: Boolean): Byte =
        ((if (contradicted) CONTRADICTED_FLAG else 0) or
            (if (published) PUBLISHED_FLAG else 0)).toByte()

    /**
     * Immutable packet ownership is primitive-backed. Element access creates
     * only the requested public value (and a bounded list view for a nested
     * source/target list); it never caches or accumulates materialized graphs.
     */
    private class PrimitiveDepthEvidenceChanges(
        private val kinds: ByteArray,
        private val sourceStarts: IntArray,
        private val sourceCounts: IntArray,
        private val targetStarts: IntArray,
        private val targetCounts: IntArray,
        private val sources: IntArray,
        private val targetKeys: LongArray,
        private val targetPackedNormals: ShortArray,
        private val targetConfidences: ByteArray,
        private val targetIds: IntArray,
    ) : AbstractList<DepthEvidenceChange>() {
        override val size: Int get() = kinds.size

        override fun get(index: Int): DepthEvidenceChange {
            if (index !in indices) throw IndexOutOfBoundsException("change index $index")
            val sourceIds = PrimitiveSurfaceIds(sources, sourceStarts[index], sourceCounts[index])
            val targets = PrimitiveCanonicalTargets(
                targetKeys, targetPackedNormals, targetConfidences, targetIds,
                targetStarts[index], targetCounts[index],
            )
            return when (kinds[index].toInt()) {
                CHANGE_CREATE -> DepthEvidenceChange.Create(targets.single())
                CHANGE_REFINE -> DepthEvidenceChange.Refine(sourceIds.single(), targets.single())
                CHANGE_RELOCATE -> DepthEvidenceChange.Relocate(sourceIds.single(), targets.single())
                CHANGE_MERGE -> DepthEvidenceChange.Merge(sourceIds, targets.single())
                CHANGE_SPLIT -> DepthEvidenceChange.Split(sourceIds.single(), targets)
                CHANGE_REPLACE -> DepthEvidenceChange.Replace(sourceIds, targets)
                CHANGE_REMOVE -> DepthEvidenceChange.Remove(sourceIds.single())
                else -> error("unknown primitive change kind")
            }
        }
    }

    private class PrimitiveSurfaceIds(
        private val values: IntArray,
        private val offset: Int,
        override val size: Int,
    ) : AbstractList<SurfaceId>() {
        override fun get(index: Int): SurfaceId {
            if (index !in indices) throw IndexOutOfBoundsException("source index $index")
            return SurfaceId(values[offset + index].toLong() and 0xffff_ffffL)
        }
    }

    private class PrimitiveCanonicalTargets(
        private val keys: LongArray,
        private val packedNormals: ShortArray,
        private val confidences: ByteArray,
        private val ids: IntArray,
        private val offset: Int,
        override val size: Int,
    ) : AbstractList<CanonicalTarget>() {
        override fun get(index: Int): CanonicalTarget {
            if (index !in indices) throw IndexOutOfBoundsException("target index $index")
            val position = offset + index
            val key = keys[position]
            val mask = (1L shl 21) - 1
            val bias = 1 shl 20
            val packed = packedNormals[position].toInt() and 0xffff
            val id = ids[position].toLong() and 0xffff_ffffL
            return CanonicalTarget(
                id = id.takeIf { it != 0L }?.let(::SurfaceId),
                voxel = Voxel(
                    ((key ushr 42) and mask).toInt() - bias,
                    ((key ushr 21) and mask).toInt() - bias,
                    (key and mask).toInt() - bias,
                ),
                normalOctX = (packed ushr 8).toByte().toInt(),
                normalOctY = packed.toByte().toInt(),
                normalConfidence = confidences[position].toInt() and 0xff,
            )
        }
    }

    private data class Pending(
        val receipt: DepthEvidenceReceipt,
        val stageCount: Int,
        val frame: VisibilityGroupFrame,
    )

    private data class PlannedValidation(val refusal: DepthEvidenceRefusal?, val work: Int)

    private data class SurfaceProjection(val count: Int, val work: Int)

    private sealed interface Staged {
        data class Accepted(val result: DepthEvidenceResult.Accepted, val stageCount: Int, val frame: VisibilityGroupFrame) : Staged
        data class Refused(val reason: DepthEvidenceRefusal, val receipt: DepthEvidenceReceipt) : Staged
    }

    private class DepthLookupFailure : RuntimeException()
    private class DuplicateTargetFailure : RuntimeException()
    private class SourceOverlapFailure : RuntimeException()
    private class StageCapacityFailure : RuntimeException()

    private companion object {
        const val EMPTY_ROW = -1
        const val EVIDENCE_BYTES = 32
        // Combined local helper, configuration and mutually exclusive reuse/bulk scalar fields.
        // Conservative portable 16-byte header/8-byte alignment model; array scratch is shared.
        const val LOCAL_ESTIMATOR_SCALAR_RESERVE_BYTES = 384
        const val SEMANTIC_STATE_BUDGET_BYTES = 16 * 1024 * 1024
        // Conservative modeled ownership under the same 16-byte header and
        // 8-byte alignment convention as the fixed-array ledger. The packet
        // owns ten immutable primitive columns: five change descriptors, one
        // bounded canonical-source column, and four target columns. Public
        // values/list views are materialized one element at a time and never
        // cached, so they are transient rather than retained packet state.
        // This is a semantic ownership model, not a JVM heap measurement.
        const val MAXIMUM_CHANGE_ROWS = 65_536 + V2_DEPTH_SAMPLE_CAPACITY
        const val MAXIMUM_SOURCE_ROWS = 100_000
        const val CHANGE_DESCRIPTOR_ARRAY_BYTES = (16 + MAXIMUM_CHANGE_ROWS) +
            4 * (16 + MAXIMUM_CHANGE_ROWS * 4)
        const val SOURCE_ARRAY_BYTES = 16 + MAXIMUM_SOURCE_ROWS * 4
        const val TARGET_ARRAY_BYTES = (16 + MAXIMUM_CHANGE_ROWS * 8) +
            (16 + MAXIMUM_CHANGE_ROWS * 2) + (16 + MAXIMUM_CHANGE_ROWS) +
            (16 + MAXIMUM_CHANGE_ROWS * 4)
        const val PRIMITIVE_CHANGE_LIST_BYTES = 96
        const val ACCEPTED_PACKET_CONTAINER_BYTES = 56 + 104 + 40
        const val MAXIMUM_ACCEPTED_OUTPUT_RESERVE_BYTES = CHANGE_DESCRIPTOR_ARRAY_BYTES +
            SOURCE_ARRAY_BYTES + TARGET_ARRAY_BYTES + PRIMITIVE_CHANGE_LIST_BYTES +
            ACCEPTED_PACKET_CONTAINER_BYTES
        const val ARRAY_HEADER_BYTES = 16L
        const val OBJECT_ALIGNMENT_BYTES = 8L
        const val RADIX_BITS = 8
        const val RADIX_BUCKETS = 1 shl RADIX_BITS
        const val RADIX_PASSES = 3 * Int.SIZE_BYTES
        const val DIRECTION_BINS = 24
        const val NO_DIRECTION = -1
        const val CONTRADICTED_FLAG = 1
        const val PUBLISHED_FLAG = 2
        const val ESTIMATED_FLAG = 4
        const val ESTIMATE_VALID = 1
        const val ESTIMATE_OBSERVED = 2
        const val NORMAL_AGREEMENT_COSINE = 0.984807753012208
        const val CHANGE_CREATE = 1
        const val CHANGE_REFINE = 2
        const val CHANGE_RELOCATE = 3
        const val CHANGE_MERGE = 4
        const val CHANGE_SPLIT = 5
        const val CHANGE_REPLACE = 6
        const val CHANGE_REMOVE = 7
        const val MAX_RELATIONS_PER_ROW = 2

        fun nextPowerOfTwo(value: Int): Int {
            var result = 1
            while (result < value) result = Math.multiplyExact(result, 2)
            return result
        }
    }
}
