package com.uhg0.ar_flutter_plugin_2.visibilitygrid

import java.util.Collections
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
    private val configuration: DepthEvidenceConfiguration = DepthEvidenceConfiguration(),
) : AutoCloseable {
    private val tableCapacity = configuration.surfaceCapacity
    private val hashCapacity = nextPowerOfTwo((tableCapacity * 2).coerceAtLeast(2))
    private val stageCapacity = minOf(
        tableCapacity,
        Math.addExact(configuration.rayVisitCapacity, configuration.sampleCapacity),
    )
    private val stageHashCapacity = nextPowerOfTwo((stageCapacity * 2).coerceAtLeast(2))
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
    private var stageFreeBin = ByteArray(stageCapacity) { NO_DIRECTION.toByte() }
    private var stageOrder = IntArray(stageCapacity)
    private var stageSortScratch = IntArray(stageCapacity)
    private var stageSortCounts = IntArray(RADIX_BUCKETS)
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
    private var stageCount = 0
    private var stageRelationCount = 0
    private var stageOrderedSourceCount = 0
    private var stageOrderedTargetCount = 0
    private var stageChangeCount = 0

    private var residentRows = 0
    private var lastSequence = Long.MIN_VALUE
    private var lastTimestampNs = Long.MIN_VALUE
    private var activeFrame: VisibilityGroupFrame? = null
    private var pending: Pending? = null
    private var lastReceipt = DepthEvidenceReceipt()
    private var capacityRefusalCount = 0
    private var overflowEvidenceCount = 0
    private var closed = false

    init {
        require(modeledMaximumSemanticStateBytes() <= SEMANTIC_STATE_BUDGET_BYTES) {
            "modeled semantic-state ownership exceeds the native budget"
        }
    }

    /** Stages one batch without changing retained evidence. */
    fun prepare(batch: DepthEvidenceBatch, surfaces: BoundedCanonicalSurfaceView): DepthEvidenceResult {
        if (closed) return refused(DepthEvidenceRefusal.CLOSED)
        if (pending != null) return refused(DepthEvidenceRefusal.PREPARED_BUSY)
        if (!batch.tracking) return refused(DepthEvidenceRefusal.NOT_TRACKING)
        if (batch.sequence <= lastSequence || batch.sourceTimestampNs <= lastTimestampNs) {
            return refused(DepthEvidenceRefusal.DUPLICATE_TIMESTAMP)
        }
        if (batch.sequence < 0L || batch.sourceTimestampNs <= 0L ||
            !isAffine(batch.groupFromCameraGl) ||
            activeFrame?.let { it != batch.groupFrame } == true
        ) return refused(DepthEvidenceRefusal.INVALID_FRAME)
        if (surfaces.geometryRevision < 0L || surfaces.lineageRevision < 0L ||
            surfaces.geometryRevision == Long.MAX_VALUE || surfaces.lineageRevision == Long.MAX_VALUE
        ) return refused(DepthEvidenceRefusal.STALE_CANONICAL_CUT)
        if (batch.samples.size > configuration.sampleCapacity) {
            return refused(DepthEvidenceRefusal.SAMPLE_CAPACITY)
        }
        val surfaceCapacity = minOf(configuration.surfaceCapacity, batch.groupFrame.modelCapacity)
        if (batch.sourceRejectedSamples < 0 || surfaces.surfaceCount !in 0..surfaceCapacity) {
            return refused(if (batch.sourceRejectedSamples < 0) {
                DepthEvidenceRefusal.INVALID_SAMPLE
            } else {
                DepthEvidenceRefusal.SURFACE_CAPACITY
            })
        }

        val cameraGroup = transform(batch.groupFromCameraGl, DepthPointMm(0.0, 0.0, 0.0))
            ?: return refused(DepthEvidenceRefusal.INVALID_FRAME)
        val staged = try {
            stage(batch, surfaces, cameraGroup)
        } catch (_: ArithmeticException) {
            return refused(DepthEvidenceRefusal.ARITHMETIC_OVERFLOW)
        } catch (_: DepthLookupFailure) {
            return refused(DepthEvidenceRefusal.CANONICAL_LOOKUP_FAILED)
        } catch (_: DuplicateTargetFailure) {
            return refused(DepthEvidenceRefusal.DUPLICATE_TARGET)
        } catch (_: StageCapacityFailure) {
            return refused(DepthEvidenceRefusal.SURFACE_CAPACITY)
        }
        if (staged is Staged.Refused) return refused(staged.reason)

        val accepted = staged as Staged.Accepted
        pending = Pending(accepted.result.receipt, stageCount, accepted.frame)
        return accepted.result
    }

    /** Installs the previously staged primitive evidence exactly once. */
    fun applyPrepared(): DepthEvidenceApplyResult {
        if (closed) return DepthEvidenceApplyResult.NoPrepared(lastReceipt)
        val staged = pending ?: return DepthEvidenceApplyResult.NoPrepared(lastReceipt)
        for (index in 0 until staged.stageCount) {
            val row = findRow(stageX[index], stageY[index], stageZ[index])
            val resident = if (row == EMPTY_ROW) {
                insertRow(stageX[index], stageY[index], stageZ[index])
            } else {
                row
            }
            writeStage(resident, index)
        }
        lastSequence = staged.receipt.sequence
        lastTimestampNs = staged.receipt.sourceTimestampNs
        activeFrame = staged.frame
        lastReceipt = staged.receipt
        overflowEvidenceCount = staged.receipt.overflowCount
        pending = null
        resetStage()
        return DepthEvidenceApplyResult.Applied(lastReceipt)
    }

    /** Drops the staged packet and leaves all retained primitive state untouched. */
    fun discardPrepared(): DepthEvidenceDiscardResult {
        if (closed) return DepthEvidenceDiscardResult.AlreadyDiscarded(lastReceipt)
        if (pending == null) return DepthEvidenceDiscardResult.AlreadyDiscarded(lastReceipt)
        pending = null
        resetStage()
        return DepthEvidenceDiscardResult.Discarded(lastReceipt)
    }

    /** Reports exact resident/staged row ownership; observation payloads are never retained. */
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
        )
    }

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
        residentRows = 0
        activeFrame = null
        closed = true
    }

    private fun stage(
        batch: DepthEvidenceBatch,
        surfaces: BoundedCanonicalSurfaceView,
        cameraGroup: DepthPointMm,
    ): Staged {
        resetStage()
        var acceptedSamples = 0
        var rejectedSamples = batch.sourceRejectedSamples
        var visits = 0
        var overflowCount = 0

        for (sample in batch.samples) {
            if (sample.x !in 0 until batch.intrinsics.imageWidth ||
                sample.y !in 0 until batch.intrinsics.imageHeight ||
                sample.confidence < configuration.confidenceMinimum ||
                sample.depthMillimeters !in configuration.minimumDepthMillimetres..configuration.maximumDepthMillimetres
            ) {
                rejectedSamples = checkedAdd(rejectedSamples, 1)
                continue
            }
            val cameraPoint = DepthPointMm(
                (sample.x - batch.intrinsics.cx) * sample.depthMillimeters / batch.intrinsics.fx,
                -(sample.y - batch.intrinsics.cy) * sample.depthMillimeters / batch.intrinsics.fy,
                -sample.depthMillimeters.toDouble(),
            )
            if (!cameraPoint.isFinite()) {
                rejectedSamples = checkedAdd(rejectedSamples, 1)
                continue
            }
            val endpoint = transform(batch.groupFromCameraGl, cameraPoint)
                ?: throw ArithmeticException("non-finite depth transform")
            val endpointVoxel = quantize(endpoint, batch.groupFrame)
                ?: return Staged.Refused(DepthEvidenceRefusal.ARITHMETIC_OVERFLOW)
            val endpointLookup = try {
                surfaces.findSurfaceAt(endpointVoxel)
            } catch (failure: DuplicateTargetFailure) {
                throw failure
            } catch (_: RuntimeException) {
                throw DepthLookupFailure()
            }
            if (endpointLookup != null) validateCanonicalSurface(surfaces, endpointVoxel, endpointLookup)
            val endpointSurface = endpointLookup?.surface
            val priorEndpointIndex = stageFind(endpointVoxel)
            val hadOccupied = priorEndpointIndex != EMPTY_ROW && stageHasOccupied[priorEndpointIndex]
            val endpointIndex = stageIndex(endpointVoxel, endpointSurface)
            stageHasOccupied[endpointIndex] = true
            if (!hadOccupied || comparePoint(endpoint, occupiedPoint(endpointIndex)) < 0) {
                stageOccupiedPointX[endpointIndex] = endpoint.x
                stageOccupiedPointY[endpointIndex] = endpoint.y
                stageOccupiedPointZ[endpointIndex] = endpoint.z
            }
            var rayResult: DepthRayVisitResult
            val remaining = configuration.rayVisitCapacity - visits
            if (remaining < 0) return Staged.Refused(DepthEvidenceRefusal.RAY_VISIT_CAPACITY)
            try {
                rayResult = visitRayCells(cameraGroup, endpoint, batch.groupFrame, remaining) { voxel ->
                    val lookup = if (voxel == endpointVoxel) endpointLookup else surfaces.findSurfaceAt(voxel)
                    if (!voxelInRange(voxel)) return@visitRayCells false
                    if (lookup != null) validateCanonicalSurface(surfaces, voxel, lookup)
                    val surface = lookup?.surface
                    val candidateIndex = if (surface != null) stageIndex(voxel, surface) else stageFind(voxel)
                    val cellCenter = center(voxel, batch.groupFrame)
                    if (isFreeEvidence(cameraGroup, endpoint, endpointVoxel, voxel, cellCenter, batch.groupFrame)) {
                        val retained = findRow(voxel) != EMPTY_ROW
                        if (candidateIndex != EMPTY_ROW || retained) {
                            val index = if (candidateIndex != EMPTY_ROW) candidateIndex else stageIndex(voxel, null)
                            val direction = directionBin(cameraGroup, cellCenter)
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
                return Staged.Refused(DepthEvidenceRefusal.ARITHMETIC_OVERFLOW)
            }
            if (rayResult.truncated ||
                rayResult.visitedCells < 0 || rayResult.visitedCells > remaining
            ) return Staged.Refused(DepthEvidenceRefusal.RAY_VISIT_CAPACITY)
            visits = checkedAdd(visits, rayResult.visitedCells)
            acceptedSamples = checkedAdd(acceptedSamples, 1)
        }
        if (acceptedSamples == 0 && batch.samples.isNotEmpty()) {
            return Staged.Refused(DepthEvidenceRefusal.INVALID_SAMPLE)
        }
        var directionVotes = 0
        var conflicts = 0
        for (index in 0 until stageCount) {
            if (stageHasOccupied[index]) {
                val updated = increment(stageOccupiedValue(index))
                stageOccupied[index] = updated.first.toByte()
                if (updated.second) overflowCount = checkedAdd(overflowCount, 1)
            }
            if (stageFreeBinValue(index) != NO_DIRECTION) {
                val updated = increment(stageFreeValue(index))
                stageFree[index] = updated.first.toByte()
                if (updated.second) overflowCount = checkedAdd(overflowCount, 1)
                val bit = 1 shl stageFreeBinValue(index)
                if (stageDirections[index] and bit == 0) {
                    stageDirections[index] = stageDirections[index] or bit
                    directionVotes++
                }
            }
            if (stageHasOccupied[index] && stageFreeValue(index) > 0) conflicts = checkedAdd(conflicts, 1)
        }
        val orderingWork = sortStageOrder()
        val planningWork = planChanges()
        val validation = validatePlannedChanges()
        validation.refusal?.let { return Staged.Refused(it) }
        var newRows = 0
        for (index in 0 until stageCount) {
            if (findRow(stageX[index], stageY[index], stageZ[index]) == EMPTY_ROW) newRows++
        }
        val projection = projectedSurfaceCount(surfaces.surfaceCount)
        if (projection.count < 0) throw DepthLookupFailure()
        if (residentRows + newRows > minOf(configuration.surfaceCapacity, batch.groupFrame.modelCapacity) ||
            projection.count > minOf(configuration.surfaceCapacity, batch.groupFrame.modelCapacity)
        ) {
            return Staged.Refused(DepthEvidenceRefusal.SURFACE_CAPACITY)
        }
        val immutableChanges = Collections.unmodifiableList(
            (0 until stageChangeCount).map { materializeChange(it, cameraGroup, batch.groupFrame) },
        )
        val kindCounts = kindCounts(stageChangeKind, stageChangeCount)
        val virtualWork = checkedAdd(
            checkedAdd(orderingWork, checkedAdd(planningWork, checkedAdd(validation.work, projection.work))),
            checkedAdd(acceptedSamples, checkedAdd(visits, stageCount)),
        )
        val receipt = DepthEvidenceReceipt(
            sequence = batch.sequence,
            sourceTimestampNs = batch.sourceTimestampNs,
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
            capacityRefusals = capacityRefusalCount,
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
                surfaces.geometryRevision,
                surfaces.lineageRevision,
                immutableChanges,
                receipt,
                work,
            ),
            stageCount,
            batch.groupFrame,
        )
    }

    private fun planChanges(): Int {
        var work = stageCount
        for (index in 0 until stageCount) {
            stagePositive[index] = false
            stageRemoval[index] = false
            stageComponent[index] = index
            val sourceId = stageSourceIdValue(index)
            if (stageContradicted[index]) {
                if (stageOccupiedValue(index) >= configuration.occupiedEvidenceToShow) {
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
                !stagePublished[index]
            ) {
                stagePublished[index] = true
                stagePositive[index] = true
            }
        }

        for (relation in 0 until stageRelationCount) stageRelationOrder[relation] = relation
        work = checkedAdd(work, stageRelationCount)
        work = checkedAdd(work, radixSortRelationOrderBySource(stageRelationCount))
        var relationPosition = 0
        while (relationPosition < stageRelationCount) {
            val source = relationSourceValue(stageRelationOrder[relationPosition])
            var end = relationPosition + 1
            while (end < stageRelationCount && relationSourceValue(stageRelationOrder[end]) == source) end++
            var firstPositive = EMPTY_ROW
            for (position in relationPosition until end) {
                val target = stageRelationTarget[stageRelationOrder[position]]
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
            val source = relationSourceValue(stageRelationOrder[relationPosition])
            var end = relationPosition + 1
            while (end < stageRelationCount && relationSourceValue(stageRelationOrder[end]) == source) end++
            var representative = EMPTY_ROW
            for (position in relationPosition until end) {
                val relation = stageRelationOrder[position]
                if (stagePositive[stageRelationTarget[relation]]) {
                    representative = relation
                    break
                }
            }
            if (representative != EMPTY_ROW) stageRelationOrder[sourceWrite++] = representative
            work = checkedAdd(work, end - relationPosition)
            relationPosition = end
        }
        stageOrderedSourceCount = sourceWrite
        work = checkedAdd(work, radixSortRelationOrderByRoot(stageOrderedSourceCount))

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
        stageChangeSource[slot] = (if (hasSingleSource) {
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
        return relationSourceValue(stageRelationOrder[position])
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
        findComponent(stageRelationTarget[stageRelationOrder[position]])

    private fun relationSourceValue(relation: Int): Long =
        stageRelationSource[relation].toLong() and 0xffff_ffffL

    private fun materializeChange(
        slot: Int,
        cameraGroup: DepthPointMm,
        frame: VisibilityGroupFrame,
    ): DepthEvidenceChange {
        val index = stageChangeTarget[slot]
        val root = stageChangeComponent[slot]
        val source = stageChangeSourceValue(slot).takeIf { it != 0L }?.let(::SurfaceId)
        fun target(targetIndex: Int, id: SurfaceId? = null): CanonicalTarget = targetFor(
            targetIndex,
            if (stageHasOccupied[targetIndex]) occupiedPoint(targetIndex) else null,
            cameraGroup,
            frame,
            id,
        )
        return when (stageChangeKind[slot].toInt()) {
            CHANGE_CREATE -> DepthEvidenceChange.Create(target(index))
            CHANGE_REFINE -> DepthEvidenceChange.Refine(requireNotNull(source), target(index, source))
            CHANGE_RELOCATE -> DepthEvidenceChange.Relocate(requireNotNull(source), target(index, source))
            CHANGE_MERGE -> DepthEvidenceChange.Merge(
                immutableCopy(sourceIdsForComponent(root)), target(index),
            )
            CHANGE_SPLIT -> DepthEvidenceChange.Split(
                requireNotNull(source), immutableCopy(targetsForComponent(root, cameraGroup, frame)),
            )
            CHANGE_REPLACE -> DepthEvidenceChange.Replace(
                immutableCopy(sourceIdsForComponent(root)),
                immutableCopy(targetsForComponent(root, cameraGroup, frame)),
            )
            CHANGE_REMOVE -> DepthEvidenceChange.Remove(requireNotNull(source))
            else -> error("unknown staged change kind")
        }
    }

    private fun sourceIdsForComponent(root: Int): List<SurfaceId> = List(componentSourceCount(root)) { rank ->
        SurfaceId(sourceAtRank(root, rank))
    }

    private fun <T> immutableCopy(values: Collection<T>): List<T> =
        Collections.unmodifiableList(ArrayList(values))

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

    private fun targetsForComponent(
        root: Int,
        cameraGroup: DepthPointMm,
        frame: VisibilityGroupFrame,
    ): List<CanonicalTarget> = List(componentTargetCount(root)) { rank ->
        targetFor(
            targetAtRank(root, rank),
            occupiedPoint(targetAtRank(root, rank)),
            cameraGroup,
            frame,
            null,
        )
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
        var sourceConsumers = 0
        for (position in 0 until stageOrderedSourceCount) {
            stageSortScratch[sourceConsumers++] = stageRelationSource[stageRelationOrder[position]]
        }
        for (slot in 0 until stageChangeCount) {
            if (stageChangeKind[slot].toInt() == CHANGE_REMOVE) {
                stageSortScratch[sourceConsumers++] = stageChangeSource[slot]
            }
        }
        check(sourceConsumers <= stageCapacity)
        // Coordinate lookup is dead after staging/planning. Reuse its larger
        // primitive table as radix destination; resetStage restores it before
        // any later prepare, and applyPrepared never reads it.
        var work = checkedAdd(stageOrderedTargetCount, checkedAdd(stageOrderedSourceCount, stageChangeCount))
        work = checkedAdd(work, radixSortUnsignedValues(stageSortScratch, stageHashRows, sourceConsumers))
        for (position in 1 until sourceConsumers) {
            if (stageSortScratch[position] == stageSortScratch[position - 1]) {
                return PlannedValidation(DepthEvidenceRefusal.SOURCE_OVERLAP, checkedAdd(work, position))
            }
        }
        return PlannedValidation(null, checkedAdd(work, sourceConsumers))
    }

    private fun targetFor(
        index: Int,
        cameraPoint: DepthPointMm?,
        cameraGroup: DepthPointMm,
        frame: VisibilityGroupFrame,
        sourceId: SurfaceId?,
    ): CanonicalTarget {
        val packed = if (stagePackedNormalValue(index) != 0) stagePackedNormalValue(index) else {
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
    ): DepthRayVisitResult {
        if (maximumVisits !in 0..65_536 || !camera.isFinite() || !endpoint.isFinite()) {
            return DepthRayVisitResult(0, arithmeticOverflow = true)
        }
        val start = quantize(camera, frame)
            ?: return DepthRayVisitResult(0, arithmeticOverflow = true)
        val end = quantize(endpoint, frame)
            ?: return DepthRayVisitResult(0, arithmeticOverflow = true)
        val size = frame.voxelSizeMicrometres.toDouble() / 1_000.0
        val delta = doubleArrayOf(endpoint.x - camera.x, endpoint.y - camera.y, endpoint.z - camera.z)
        if (!size.isFinite() || size <= 0.0 || delta.any { !it.isFinite() }) {
            return DepthRayVisitResult(0, arithmeticOverflow = true)
        }
        val current = intArrayOf(start.x, start.y, start.z)
        val target = intArrayOf(end.x, end.y, end.z)
        val step = IntArray(3) { axis -> delta[axis].compareTo(0.0) }
        val startPoint = doubleArrayOf(camera.x, camera.y, camera.z)
        val tDelta = DoubleArray(3) { axis ->
            if (step[axis] == 0) Double.POSITIVE_INFINITY else size / kotlin.math.abs(delta[axis])
        }
        val tMax = DoubleArray(3) { axis ->
            if (step[axis] == 0) {
                Double.POSITIVE_INFINITY
            } else {
                val boundary = (current[axis] + if (step[axis] > 0) 1 else 0) * size
                (boundary - startPoint[axis]) / delta[axis]
            }
        }
        var visited = 0
        fun emit(voxel: Voxel): Boolean {
            if (visited >= maximumVisits) return false
            visited = Math.addExact(visited, 1)
            return visitor(voxel)
        }
        try {
            if (!emit(start)) return DepthRayVisitResult(visited)
            while (!current.contentEquals(target)) {
                val crossing = (0..2)
                    .asSequence()
                    .filter { current[it] != target[it] }
                    .minOfOrNull { tMax[it] }
                    ?: return DepthRayVisitResult(visited, arithmeticOverflow = true)
                if (!crossing.isFinite()) return DepthRayVisitResult(visited, arithmeticOverflow = true)
                var tiedMask = 0
                for (axis in 0..2) {
                    if (current[axis] != target[axis] && tMax[axis] == crossing) {
                        tiedMask = tiedMask or (1 shl axis)
                    }
                }
                for (subset in 1..7) {
                    if (subset and tiedMask != subset) continue
                    val next = current.copyOf()
                    for (axis in 0..2) if (subset and (1 shl axis) != 0) {
                        next[axis] = Math.addExact(next[axis], step[axis])
                    }
                    val voxel = Voxel(next[0], next[1], next[2])
                    if (!voxelInRange(voxel)) return DepthRayVisitResult(visited, arithmeticOverflow = true)
                    if (!emit(voxel)) return DepthRayVisitResult(visited, truncated = true)
                }
                for (axis in 0..2) if (tiedMask and (1 shl axis) != 0) {
                    current[axis] = Math.addExact(current[axis], step[axis])
                    tMax[axis] += tDelta[axis]
                }
            }
        } catch (_: ArithmeticException) {
            return DepthRayVisitResult(visited, arithmeticOverflow = true)
        }
        return DepthRayVisitResult(visited)
    }

    private fun isFreeEvidence(
        camera: DepthPointMm,
        endpoint: DepthPointMm,
        endpointVoxel: Voxel,
        voxel: Voxel,
        cellCenter: DepthPointMm,
        frame: VisibilityGroupFrame,
    ): Boolean {
        if (voxel == endpointVoxel) return false
        if (frame.voxelSizeMicrometres <= 0 || !voxelInRange(voxel)) return false
        val dx = endpoint.x - camera.x
        val dy = endpoint.y - camera.y
        val dz = endpoint.z - camera.z
        val length2 = dx * dx + dy * dy + dz * dz
        if (!length2.isFinite() || length2 <= 0.0) return false
        val toCellX = cellCenter.x - camera.x
        val toCellY = cellCenter.y - camera.y
        val toCellZ = cellCenter.z - camera.z
        val projection = (toCellX * dx + toCellY * dy + toCellZ * dz) / length2
        if (!projection.isFinite() || projection < 0.0 || projection > 1.0) return false
        val closestX = camera.x + projection * dx
        val closestY = camera.y + projection * dy
        val closestZ = camera.z + projection * dz
        val offX = cellCenter.x - closestX
        val offY = cellCenter.y - closestY
        val offZ = cellCenter.z - closestZ
        val size = frame.voxelSizeMicrometres.toDouble() / 1_000.0
        val halfDiagonal = size * sqrt(3.0) * 0.5
        val projectionExtent = halfDiagonal / sqrt(length2)
        if (projection + projectionExtent < 0.0 || projection - projectionExtent > 1.0) return false
        val voxelMinX = voxel.x * size
        val voxelMinY = voxel.y * size
        val voxelMinZ = voxel.z * size
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
        addressedVoxel: Voxel,
        lookup: AddressedCanonicalSurface,
    ) {
        if (lookup.addressedVoxel != addressedVoxel) throw DepthLookupFailure()
        if (surfaces.surfaceCount == 0) throw DepthLookupFailure()
        val surface = lookup.surface
        val canonical = surfaces.findSurfaceById(surface.id) ?: throw DepthLookupFailure()
        if (canonical.id != surface.id || !voxelInRange(surface.voxel)) throw DepthLookupFailure()
        if (canonical.voxel != surface.voxel) throw DuplicateTargetFailure()
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
        rowFlags[row] = flags(stageContradicted[index], stagePublished[index])
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

    private fun stageIndex(voxel: Voxel, surface: DepthCanonicalSurface?): Int {
        var index = stageFind(voxel)
        if (index != EMPTY_ROW) {
            if (surface != null) stageAttach(index, surface)
            return index
        }
        if (stageCount >= stageCapacity) throw StageCapacityFailure()
        index = stageCount++
        stageX[index] = voxel.x
        stageY[index] = voxel.y
        stageZ[index] = voxel.z
        stageOccupied[index] = 0
        stageFree[index] = 0
        stageDirections[index] = 0
        stageContradicted[index] = false
        stageSourceId[index] = 0
        stageSourceKey[index] = packVisibilityGridKey(voxel.x, voxel.y, voxel.z)
        stagePackedNormal[index] = 0
        stageNormalConfidence[index] = 0
        stagePublished[index] = false
        stageHasOccupied[index] = false
        stageOccupiedPointX[index] = 0.0
        stageOccupiedPointY[index] = 0.0
        stageOccupiedPointZ[index] = 0.0
        stageFreeBin[index] = NO_DIRECTION.toByte()
        val resident = findRow(voxel.x, voxel.y, voxel.z)
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
            if (stageSourceIdValue(index) != 0L) appendStageRelation(index, stageSourceIdValue(index))
        }
        var slot = stageHash(packVisibilityGridKey(voxel.x, voxel.y, voxel.z))
        while (stageHashRows[slot] != EMPTY_ROW) slot = (slot + 1) and (stageHashCapacity - 1)
        stageHashRows[slot] = index
        if (surface != null) stageAttach(index, surface)
        return index
    }

    private fun stageAttach(index: Int, surface: DepthCanonicalSurface) {
        stageAddRelation(index, surface.id.value)
        val prior = stageSourceIdValue(index)
        if (prior == 0L || surface.id.value < prior) {
            stageSourceId[index] = surface.id.value.toInt()
            stageSourceKey[index] = packVisibilityGridKey(surface.voxel.x, surface.voxel.y, surface.voxel.z)
            stagePackedNormal[index] = surface.packedNormal.toShort()
            stageNormalConfidence[index] = surface.normalConfidence.toByte()
            stageLineageCount[index] = surface.lineageCount.toShort()
            stagePublished[index] = false
        }
    }

    private fun stageAddRelation(index: Int, sourceId: Long) {
        val primary = stageSourceIdValue(index)
        if (primary == sourceId && primary != 0L) return
        if (primary == 0L) {
            appendStageRelation(index, sourceId)
            return
        }
        for (relation in 0 until stageRelationCount) {
            if (stageRelationTarget[relation] == index &&
                (stageRelationSource[relation].toLong() and 0xffff_ffffL) == sourceId
            ) return
        }
        appendStageRelation(index, sourceId)
    }

    private fun appendStageRelation(index: Int, sourceId: Long) {
        if (stageRelationCount >= stageCapacity) throw StageCapacityFailure()
        stageRelationTarget[stageRelationCount] = index
        stageRelationSource[stageRelationCount] = sourceId.toInt()
        stageRelationCount++
    }

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

    private fun radixSortRelationOrderBySource(count: Int): Int = radixSortIndices(
        stageRelationOrder,
        count,
    ) { relation -> stageRelationSource[relation] }

    private fun radixSortRelationOrderByRoot(count: Int): Int = radixSortIndices(
        stageRelationOrder,
        count,
    ) { relation -> stageComponentRank[findComponent(stageRelationTarget[relation])] }

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

    private fun resetStage() {
        java.util.Arrays.fill(stageHashRows, EMPTY_ROW)
        java.util.Arrays.fill(stageFreeBin, NO_DIRECTION.toByte())
        java.util.Arrays.fill(stageHasOccupied, false)
        java.util.Arrays.fill(stagePositive, false)
        java.util.Arrays.fill(stageRemoval, false)
        stageCount = 0
        stageRelationCount = 0
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

    private fun refused(reason: DepthEvidenceRefusal): DepthEvidenceResult.Refused {
        if (reason == DepthEvidenceRefusal.SAMPLE_CAPACITY ||
            reason == DepthEvidenceRefusal.RAY_VISIT_CAPACITY ||
            reason == DepthEvidenceRefusal.SURFACE_CAPACITY
        ) {
            capacityRefusalCount = checkedAdd(capacityRefusalCount, 1)
            lastReceipt = lastReceipt.copy(capacityRefusals = capacityRefusalCount)
        }
        if (reason == DepthEvidenceRefusal.ARITHMETIC_OVERFLOW) {
            overflowEvidenceCount = checkedAdd(overflowEvidenceCount, 1)
            lastReceipt = lastReceipt.copy(overflowCount = overflowEvidenceCount)
        }
        return DepthEvidenceResult.Refused(reason, lastReceipt)
    }

    private fun kindCounts(kinds: ByteArray, count: Int): IntArray = IntArray(7).also { counts ->
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
        fun vector(bin: Int): DoubleArray {
            val elevation = when (bin / 8) { 0 -> -PI / 4.0; 1 -> 0.0; else -> PI / 4.0 }
            val azimuth = -PI + (bin % 8 + 0.5) * PI / 4.0
            val horizontal = kotlin.math.cos(elevation)
            return doubleArrayOf(kotlin.math.sin(azimuth) * horizontal, kotlin.math.sin(elevation), -kotlin.math.cos(azimuth) * horizontal)
        }
        val left = vector(first); val right = vector(second)
        return left[0] * right[0] + left[1] * right[1] + left[2] * right[2]
    }

    private fun directionBin(camera: DepthPointMm, endpoint: DepthPointMm): Int {
        val x = endpoint.x - camera.x; val y = endpoint.y - camera.y; val z = endpoint.z - camera.z
        val length = sqrt(x * x + y * y + z * z)
        if (!length.isFinite() || length <= 0.0) return 0
        val azimuth = atan2(x, -z)
        val azimuthBin = floor((azimuth + PI) / (2.0 * PI) * 8.0).toInt().coerceIn(0, 7)
        val elevation = kotlin.math.asin((y / length).coerceIn(-1.0, 1.0))
        val elevationBin = when { elevation < -PI / 8.0 -> 0; elevation > PI / 8.0 -> 2; else -> 1 }
        return elevationBin * 8 + azimuthBin
    }

    private fun quantize(point: DepthPointMm, frame: VisibilityGroupFrame): Voxel? {
        if (!point.isFinite()) return null
        fun coordinate(value: Double): Int? {
            val quantized = floor(value * 1_000.0 / frame.voxelSizeMicrometres)
            return if (quantized.isFinite() && quantized >= VOXEL_COORDINATE_MIN && quantized <= VOXEL_COORDINATE_MAX) quantized.toInt() else null
        }
        return Voxel(coordinate(point.x) ?: return null, coordinate(point.y) ?: return null, coordinate(point.z) ?: return null)
    }

    private fun center(voxel: Voxel, frame: VisibilityGroupFrame): DepthPointMm {
        val size = frame.voxelSizeMicrometres.toDouble() / 1_000.0
        return DepthPointMm((voxel.x + 0.5) * size, (voxel.y + 0.5) * size, (voxel.z + 0.5) * size)
    }

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

    private fun voxelInRange(voxel: Voxel): Boolean =
        voxel.x in VOXEL_COORDINATE_MIN..VOXEL_COORDINATE_MAX && voxel.y in VOXEL_COORDINATE_MIN..VOXEL_COORDINATE_MAX && voxel.z in VOXEL_COORDINATE_MIN..VOXEL_COORDINATE_MAX

    private fun increment(value: Int): Pair<Int, Boolean> = if (value >= 255) 255 to true else value + 1 to false

    private fun checkedAdd(left: Int, right: Int): Int = Math.addExact(left, right)

    private fun checkedBytes(rows: Int): Int = Math.multiplyExact(rows, EVIDENCE_BYTES)

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
    )

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
        MAXIMUM_ACCEPTED_OUTPUT_RESERVE_BYTES,
    )

    private fun stageFreeBinValue(index: Int): Int = stageFreeBin[index].toInt()

    private fun stageChangeSourceValue(index: Int): Long = stageChangeSource[index].toLong() and 0xffff_ffffL

    private fun flags(contradicted: Boolean, published: Boolean): Byte =
        ((if (contradicted) CONTRADICTED_FLAG else 0) or
            (if (published) PUBLISHED_FLAG else 0)).toByte()

    private data class Pending(
        val receipt: DepthEvidenceReceipt,
        val stageCount: Int,
        val frame: VisibilityGroupFrame,
    )

    private data class PlannedValidation(val refusal: DepthEvidenceRefusal?, val work: Int)

    private data class SurfaceProjection(val count: Int, val work: Int)

    private sealed interface Staged {
        data class Accepted(val result: DepthEvidenceResult.Accepted, val stageCount: Int, val frame: VisibilityGroupFrame) : Staged
        data class Refused(val reason: DepthEvidenceRefusal) : Staged
    }

    private class DepthLookupFailure : RuntimeException()
    private class DuplicateTargetFailure : RuntimeException()
    private class StageCapacityFailure : RuntimeException()

    private companion object {
        const val EMPTY_ROW = -1
        const val EVIDENCE_BYTES = 32
        const val SEMANTIC_STATE_BUDGET_BYTES = 16 * 1024 * 1024
        // Conservative modeled ownership under the same 16-byte header,
        // 8-byte reference and 8-byte alignment convention as the array
        // ledger. The largest shape reserves 65,536 Removes (a 24-byte change
        // plus a 24-byte SurfaceId) and 1,536 Creates (a 24-byte change plus
        // an 80-byte target/voxel graph), as well as 67,072 outer references,
        // the exact list graph, and the Accepted/receipt/work containers. This
        // is a semantic model, not a particular JVM heap measurement.
        const val MAXIMUM_CHANGE_ROWS = 65_536 + V2_DEPTH_SAMPLE_CAPACITY
        const val MAXIMUM_REMOVE_ROWS = 65_536
        const val REMOVE_AND_ID_BYTES = MAXIMUM_REMOVE_ROWS * (24 + 24)
        const val CREATE_AND_TARGET_BYTES = V2_DEPTH_SAMPLE_CAPACITY * (24 + 80)
        const val CHANGE_REFERENCE_ARRAY_BYTES = 16 + MAXIMUM_CHANGE_ROWS * 8
        const val CHANGE_LIST_GRAPH_BYTES = 32 + 24
        const val ACCEPTED_PACKET_CONTAINER_BYTES = 56 + 104 + 40
        const val MAXIMUM_ACCEPTED_OUTPUT_RESERVE_BYTES = REMOVE_AND_ID_BYTES +
            CREATE_AND_TARGET_BYTES + CHANGE_REFERENCE_ARRAY_BYTES +
            CHANGE_LIST_GRAPH_BYTES + ACCEPTED_PACKET_CONTAINER_BYTES
        const val ARRAY_HEADER_BYTES = 16L
        const val OBJECT_ALIGNMENT_BYTES = 8L
        const val RADIX_BITS = 8
        const val RADIX_BUCKETS = 1 shl RADIX_BITS
        const val RADIX_PASSES = 3 * Int.SIZE_BYTES
        const val DIRECTION_BINS = 24
        const val NO_DIRECTION = -1
        const val CONTRADICTED_FLAG = 1
        const val PUBLISHED_FLAG = 2
        const val CHANGE_CREATE = 1
        const val CHANGE_REFINE = 2
        const val CHANGE_RELOCATE = 3
        const val CHANGE_MERGE = 4
        const val CHANGE_SPLIT = 5
        const val CHANGE_REPLACE = 6
        const val CHANGE_REMOVE = 7

        fun nextPowerOfTwo(value: Int): Int {
            var result = 1
            while (result < value) result = Math.multiplyExact(result, 2)
            return result
        }
    }
}
