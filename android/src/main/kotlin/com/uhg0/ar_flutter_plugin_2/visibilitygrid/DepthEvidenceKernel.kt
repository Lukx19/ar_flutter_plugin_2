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
    private val hashKeys = LongArray(hashCapacity)
    private val hashRows = IntArray(hashCapacity) { EMPTY_ROW }
    // A retained evidence row is exactly 32 bytes: voxel key (8), evidence
    // counters/directions/flags (7), source id (4), source voxel key (8),
    // packed normal/confidence/lineage (5). The byte columns are unsigned on
    // read; the canonical IDs and voxel keys are bounded to 32/63 bits.
    private val rowVoxelKey = LongArray(tableCapacity)
    private val rowOccupied = ByteArray(tableCapacity)
    private val rowFree = ByteArray(tableCapacity)
    private val rowDirections = IntArray(tableCapacity)
    private val rowFlags = ByteArray(tableCapacity)
    private val rowSourceId = IntArray(tableCapacity)
    private val rowSourceKey = LongArray(tableCapacity)
    private val rowPackedNormal = ShortArray(tableCapacity)
    private val rowNormalConfidence = ByteArray(tableCapacity)
    private val rowLineageCount = ShortArray(tableCapacity)

    // One fixed staging table is reused by every prepare. It is deliberately
    // primitive and bounded by the negotiated surface capacity; no per-prepare
    // hash table or growing collection is allocated.
    private val stageHashKeys = LongArray(hashCapacity)
    private val stageHashRows = IntArray(hashCapacity) { EMPTY_ROW }
    private val stageX = IntArray(tableCapacity)
    private val stageY = IntArray(tableCapacity)
    private val stageZ = IntArray(tableCapacity)
    private val stageOccupied = IntArray(tableCapacity)
    private val stageFree = IntArray(tableCapacity)
    private val stageDirections = IntArray(tableCapacity)
    private val stageContradicted = BooleanArray(tableCapacity)
    private val stageSourceId = LongArray(tableCapacity)
    private val stageSourceKey = LongArray(tableCapacity)
    private val stagePackedNormal = IntArray(tableCapacity)
    private val stageNormalConfidence = IntArray(tableCapacity)
    private val stageLineageCount = IntArray(tableCapacity)
    private val stagePublished = BooleanArray(tableCapacity)
    private val stageHasOccupied = BooleanArray(tableCapacity)
    private val stageOccupiedCameraX = DoubleArray(tableCapacity)
    private val stageOccupiedCameraY = DoubleArray(tableCapacity)
    private val stageOccupiedCameraZ = DoubleArray(tableCapacity)
    private val stageFreeBin = IntArray(tableCapacity) { NO_DIRECTION }
    private val stageOrder = IntArray(tableCapacity)
    private val stageChanges = arrayOfNulls<DepthEvidenceChange>(tableCapacity)
    private var stageCount = 0
    private var stageChangeCount = 0

    private var residentRows = 0
    private var lastSequence = Long.MIN_VALUE
    private var lastTimestampNs = Long.MIN_VALUE
    private var activeFrame: VisibilityGroupFrame? = null
    private var pending: Pending? = null
    private var lastReceipt = DepthEvidenceReceipt()
    private var capacityRefusalCount = 0
    private var closed = false

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
        } catch (_: StageCapacityFailure) {
            return refused(DepthEvidenceRefusal.SURFACE_CAPACITY)
        }
        if (staged is Staged.Refused) return refused(staged.reason)

        val accepted = staged as Staged.Accepted
        pending = Pending(accepted.result, stageCount, accepted.frame)
        return accepted.result
    }

    /**
     * Forms one deterministic canonical transaction from an explicit bounded
     * source/target cut. This is the producer for the complete change algebra;
     * depth admission remains responsible only for evidence qualification.
     */
    fun prepareIntentions(
        sequence: Long,
        sourceTimestampNs: Long,
        groupFrame: VisibilityGroupFrame,
        expectedGeometryRevision: Long,
        expectedLineageRevision: Long,
        sources: List<DepthCanonicalSurface>,
        targets: List<CanonicalTarget>,
    ): DepthEvidenceResult {
        if (closed) return refused(DepthEvidenceRefusal.CLOSED)
        if (pending != null) return refused(DepthEvidenceRefusal.PREPARED_BUSY)
        if (sequence <= lastSequence || sourceTimestampNs <= lastTimestampNs) {
            return refused(DepthEvidenceRefusal.DUPLICATE_TIMESTAMP)
        }
        if (sequence < 0L || sourceTimestampNs <= 0L ||
            groupFrame.modelCapacity < 1 || expectedGeometryRevision < 0L ||
            expectedLineageRevision < 0L || expectedGeometryRevision == Long.MAX_VALUE ||
            expectedLineageRevision == Long.MAX_VALUE ||
            activeFrame?.let { it != groupFrame } == true
        ) return refused(DepthEvidenceRefusal.INVALID_FRAME)
        val operationCapacity = minOf(tableCapacity, groupFrame.modelCapacity)
        if (sources.size > operationCapacity || targets.size > operationCapacity) {
            return refused(DepthEvidenceRefusal.SURFACE_CAPACITY)
        }
        if (sources.isEmpty() && targets.isEmpty()) return refused(DepthEvidenceRefusal.INVALID_SAMPLE)

        resetStage()
        for (source in sources) {
            if (!markIntentionVoxel(source.voxel)) return refused(DepthEvidenceRefusal.SOURCE_OVERLAP)
        }
        sortSourceIntentionOrder(sources)
        for (index in 1 until sources.size) {
            val prior = sources[stageOrder[index - 1]]
            val current = sources[stageOrder[index]]
            if (prior.id == current.id) return refused(DepthEvidenceRefusal.SOURCE_OVERLAP)
        }
        sortTargetIntentionOrder(targets)
        for (index in 1 until targets.size) {
            val prior = targets[stageOrder[index - 1]]
            val current = targets[stageOrder[index]]
            if (prior.voxel == current.voxel) return refused(DepthEvidenceRefusal.DUPLICATE_TARGET)
        }
        for (target in targets) {
            if (!target.voxelInRange() || target.normalOctX !in -127..127 ||
                target.normalOctY !in -127..127 || target.normalConfidence !in 0..255
            ) return refused(DepthEvidenceRefusal.INVALID_SAMPLE)
        }
        for (source in sources) {
            if (!voxelInRange(source.voxel)) return refused(DepthEvidenceRefusal.INVALID_SAMPLE)
        }

        // Copy only the output-facing immutable views; all ordering scratch is
        // the preallocated primitive stageOrder column.
        sortSourceIntentionOrder(sources)
        val orderedSources = List(sources.size) { sources[stageOrder[it]] }
        sortTargetIntentionOrder(targets)
        val orderedTargets = List(targets.size) { targets[stageOrder[it]] }
        val changes: List<DepthEvidenceChange> = when {
            orderedSources.isEmpty() -> orderedTargets.map {
                DepthEvidenceChange.Create(it.copy(id = null))
            }
            orderedTargets.isEmpty() -> orderedSources.map { DepthEvidenceChange.Remove(it.id) }
            orderedSources.size == 1 && orderedTargets.size == 1 -> {
                val source = orderedSources.single()
                val target = orderedTargets.single().copy(id = source.id)
                listOf(
                    if (source.voxel == target.voxel) {
                        DepthEvidenceChange.Refine(source.id, target)
                    } else {
                        DepthEvidenceChange.Relocate(source.id, target)
                    },
                )
            }
            orderedSources.size > 1 && orderedTargets.size == 1 -> listOf(
                DepthEvidenceChange.Merge(orderedSources.map { it.id }, orderedTargets.single().copy(id = null)),
            )
            orderedSources.size == 1 -> listOf(
                DepthEvidenceChange.Split(orderedSources.single().id, orderedTargets.map { it.copy(id = null) }),
            )
            else -> listOf(
                DepthEvidenceChange.Replace(
                    orderedSources.map { it.id },
                    orderedTargets.map { it.copy(id = null) },
                ),
            )
        }
        val immutableChanges = Collections.unmodifiableList(changes)
        val counts = kindCounts(immutableChanges)
        val receipt = DepthEvidenceReceipt(
            sequence = sequence,
            sourceTimestampNs = sourceTimestampNs,
            createCount = counts[0],
            refineCount = counts[1],
            relocateCount = counts[2],
            mergeCount = counts[3],
            splitCount = counts[4],
            replaceCount = counts[5],
            removeCount = counts[6],
            capacityRefusals = capacityRefusalCount,
            preparedResidentBytes = 0,
            p50VirtualWorkUnits = changes.size,
            p95VirtualWorkUnits = changes.size,
        )
        val work = DepthEvidenceWorkReceipt(
            distinctTouchedVoxelCount = orderedTargets.size,
            emittedChangeCount = changes.size,
            rayVisits = 0,
            independentDirectionVotes = 0,
            virtualWorkUnits = changes.size,
        )
        val result = DepthEvidenceResult.Accepted(
            expectedGeometryRevision,
            expectedLineageRevision,
            immutableChanges,
            receipt,
            work,
        )
        pending = Pending(result, 0, groupFrame)
        return result
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
        lastSequence = staged.result.receipt.sequence
        lastTimestampNs = staged.result.receipt.sourceTimestampNs
        activeFrame = staged.frame
        lastReceipt = staged.result.receipt
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

    /** Reports logical resident storage only; observation payloads are never retained. */
    fun resourceReceipt(): DepthEvidenceResourceReceipt {
        val preparedRows = pending?.let { staged ->
            var count = 0
            for (index in 0 until staged.stageCount) {
                if (findRow(stageX[index], stageY[index], stageZ[index]) == EMPTY_ROW) count++
            }
            count
        } ?: 0
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
        )
    }

    override fun close() {
        if (closed) return
        pending = null
        resetStage()
        java.util.Arrays.fill(hashRows, EMPTY_ROW)
        java.util.Arrays.fill(hashKeys, 0L)
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
                ?: run {
                    rejectedSamples = checkedAdd(rejectedSamples, 1)
                    continue
                }
            val endpointSurface = try {
                surfaces.findSurfaceAt(endpointVoxel)
            } catch (_: RuntimeException) {
                throw DepthLookupFailure()
            }
            val priorEndpointIndex = stageFind(endpointVoxel)
            val hadOccupied = priorEndpointIndex != EMPTY_ROW && stageHasOccupied[priorEndpointIndex]
            val endpointIndex = stageIndex(endpointVoxel, endpointSurface)
            stageHasOccupied[endpointIndex] = true
            if (!hadOccupied || comparePoint(cameraPoint, occupiedPoint(endpointIndex)) < 0) {
                stageOccupiedCameraX[endpointIndex] = cameraPoint.x
                stageOccupiedCameraY[endpointIndex] = cameraPoint.y
                stageOccupiedCameraZ[endpointIndex] = cameraPoint.z
            }
            var rayResult: DepthRayVisitResult
            val remaining = configuration.rayVisitCapacity - visits
            if (remaining < 0) return Staged.Refused(DepthEvidenceRefusal.RAY_VISIT_CAPACITY)
            try {
                rayResult = surfaces.visitRayCells(
                    cameraGroup,
                    endpoint,
                    remaining,
                ) { voxel, surface ->
                    if (!voxelInRange(voxel)) return@visitRayCells false
                    val candidateIndex = if (surface != null) stageIndex(voxel, surface) else stageFind(voxel)
                    val cellCenter = center(voxel, batch.groupFrame)
                    if (isFreeEvidence(cameraGroup, endpoint, endpointVoxel, voxel, cellCenter, batch.groupFrame)) {
                        val retained = findRow(voxel) != EMPTY_ROW
                        if (candidateIndex != EMPTY_ROW || retained) {
                            val index = if (candidateIndex != EMPTY_ROW) candidateIndex else stageIndex(voxel, null)
                            val direction = directionBin(cameraGroup, cellCenter)
                            stageFreeBin[index] = if (stageFreeBin[index] == NO_DIRECTION) {
                                direction
                            } else {
                                minOf(stageFreeBin[index], direction)
                            }
                        }
                    }
                    true
                }
            } catch (_: RuntimeException) {
                throw DepthLookupFailure()
            }
            if (rayResult.arithmeticOverflow || rayResult.truncated ||
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
                val updated = increment(stageOccupied[index])
                stageOccupied[index] = updated.first
                if (updated.second) overflowCount = checkedAdd(overflowCount, 1)
            }
            if (stageFreeBin[index] != NO_DIRECTION) {
                val updated = increment(stageFree[index])
                stageFree[index] = updated.first
                if (updated.second) overflowCount = checkedAdd(overflowCount, 1)
                val bit = 1 shl stageFreeBin[index]
                if (stageDirections[index] and bit == 0) {
                    stageDirections[index] = stageDirections[index] or bit
                    directionVotes++
                }
            }
            if (stageHasOccupied[index] && stageFree[index] > 0) conflicts = checkedAdd(conflicts, 1)
        }
        sortStageOrder()
        for (position in 0 until stageCount) {
            val index = stageOrder[position]
            val change = transition(
                index,
                if (stageHasOccupied[index]) occupiedPoint(index) else null,
                cameraGroup,
                batch.groupFrame,
            )
            if (change != null) {
                validateChange(change)?.let { return Staged.Refused(it) }
                stageChanges[stageChangeCount++] = change
            }
        }
        var newRows = 0
        for (index in 0 until stageCount) {
            if (findRow(stageX[index], stageY[index], stageZ[index]) == EMPTY_ROW) newRows++
        }
        if (residentRows + newRows > minOf(configuration.surfaceCapacity, batch.groupFrame.modelCapacity)) {
            return Staged.Refused(DepthEvidenceRefusal.SURFACE_CAPACITY)
        }
        val immutableChanges = Collections.unmodifiableList((0 until stageChangeCount).map { stageChanges[it]!! })
        val kindCounts = kindCounts(stageChanges, stageChangeCount)
        val virtualWork = checkedAdd(acceptedSamples, checkedAdd(visits, stageCount))
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
            overflowCount = overflowCount,
            preparedResidentBytes = checkedBytes(newRows),
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

    private fun transition(
        index: Int,
        cameraPoint: DepthPointMm?,
        cameraGroup: DepthPointMm,
        frame: VisibilityGroupFrame,
    ): DepthEvidenceChange? {
        val sourceId = stageSourceId[index]
        if (stageContradicted[index]) {
            if (stageOccupied[index] < configuration.occupiedEvidenceToShow) return null
            stageContradicted[index] = false
            stageFree[index] = 0
            stageDirections[index] = 0
            stagePublished[index] = true
            return mutationFor(index, cameraPoint, cameraGroup, frame)
        }
        val conflicted = cameraPoint != null && stageFree[index] > 0
        val canCarve = stageFree[index] >= configuration.freeEvidenceToCarve &&
            stageFree[index] - stageOccupied[index] >= configuration.freeEvidenceMargin &&
            hasSeparatedDirections(stageDirections[index], configuration.separatedDirectionBinsRequired)
        if (canCarve && !conflicted && sourceId != 0L) {
            stageContradicted[index] = true
            stageOccupied[index] = 0
            stagePublished[index] = false
            return DepthEvidenceChange.Remove(SurfaceId(sourceId))
        }
        if (conflicted && canCarve) return null
        if (stageOccupied[index] < configuration.occupiedEvidenceToShow || stagePublished[index]) return null
        stagePublished[index] = true
        return mutationFor(index, cameraPoint, cameraGroup, frame)
    }

    private fun mutationFor(
        index: Int,
        cameraPoint: DepthPointMm?,
        cameraGroup: DepthPointMm,
        frame: VisibilityGroupFrame,
    ): DepthEvidenceChange {
        val target = targetFor(
            index,
            cameraPoint,
            cameraGroup,
            frame,
            stageSourceId[index].takeIf { it != 0L }?.let(::SurfaceId),
        )
        val sourceId = stageSourceId[index]
        return when {
            sourceId == 0L -> DepthEvidenceChange.Create(target.copy(id = null))
            stageSourceKey[index] == packVisibilityGridKey(stageX[index], stageY[index], stageZ[index]) ->
                DepthEvidenceChange.Refine(SurfaceId(sourceId), target.copy(id = SurfaceId(sourceId)))
            else -> DepthEvidenceChange.Relocate(SurfaceId(sourceId), target.copy(id = SurfaceId(sourceId)))
        }
    }

    private fun targetFor(
        index: Int,
        cameraPoint: DepthPointMm?,
        cameraGroup: DepthPointMm,
        frame: VisibilityGroupFrame,
        sourceId: SurfaceId?,
    ): CanonicalTarget {
        val packed = if (stagePackedNormal[index] != 0) stagePackedNormal[index] else {
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
            normalConfidence = if (stageNormalConfidence[index] != 0) {
                stageNormalConfidence[index]
            } else {
                (stageOccupied[index] * 64).coerceIn(0, 255)
            },
        )
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
        rowOccupied[row] = stageOccupied[index].toByte()
        rowFree[row] = stageFree[index].toByte()
        rowDirections[row] = stageDirections[index]
        rowFlags[row] = flags(stageContradicted[index], stagePublished[index])
        rowSourceId[row] = stageSourceId[index].toInt()
        rowSourceKey[row] = stageSourceKey[index]
        rowPackedNormal[row] = stagePackedNormal[index].toShort()
        rowNormalConfidence[row] = stageNormalConfidence[index].toByte()
        rowLineageCount[row] = stageLineageCount[index].toShort()
    }

    private fun stageFind(voxel: Voxel): Int = stageFind(voxel.x, voxel.y, voxel.z)

    private fun stageFind(x: Int, y: Int, z: Int): Int {
        val key = packVisibilityGridKey(x, y, z)
        var slot = hash(key)
        while (true) {
            val row = stageHashRows[slot]
            if (row == EMPTY_ROW) return EMPTY_ROW
            if (stageHashKeys[slot] == key) return row
            slot = (slot + 1) and (hashCapacity - 1)
        }
    }

    private fun markIntentionVoxel(voxel: Voxel): Boolean {
        val key = packVisibilityGridKey(voxel.x, voxel.y, voxel.z)
        var slot = hash(key)
        while (true) {
            val row = stageHashRows[slot]
            if (row != EMPTY_ROW) {
                if (stageHashKeys[slot] == key) return false
                slot = (slot + 1) and (hashCapacity - 1)
                continue
            }
            stageHashKeys[slot] = key
            stageHashRows[slot] = 0
            return true
        }
    }

    private fun stageIndex(voxel: Voxel, surface: DepthCanonicalSurface?): Int {
        var index = stageFind(voxel)
        if (index != EMPTY_ROW) {
            if (surface != null) stageAttach(index, surface)
            return index
        }
        if (stageCount >= tableCapacity) throw StageCapacityFailure()
        index = stageCount++
        stageX[index] = voxel.x
        stageY[index] = voxel.y
        stageZ[index] = voxel.z
        stageOccupied[index] = 0
        stageFree[index] = 0
        stageDirections[index] = 0
        stageContradicted[index] = false
        stageSourceId[index] = 0L
        stageSourceKey[index] = packVisibilityGridKey(voxel.x, voxel.y, voxel.z)
        stagePackedNormal[index] = 0
        stageNormalConfidence[index] = 0
        stagePublished[index] = false
        stageHasOccupied[index] = false
        stageOccupiedCameraX[index] = 0.0
        stageOccupiedCameraY[index] = 0.0
        stageOccupiedCameraZ[index] = 0.0
        stageFreeBin[index] = NO_DIRECTION
        val resident = findRow(voxel.x, voxel.y, voxel.z)
        if (resident != EMPTY_ROW) {
            stageOccupied[index] = rowOccupied[resident].toInt() and 0xff
            stageFree[index] = rowFree[resident].toInt() and 0xff
            stageDirections[index] = rowDirections[resident]
            stageContradicted[index] = rowFlags[resident].toInt() and CONTRADICTED_FLAG != 0
            stagePublished[index] = rowFlags[resident].toInt() and PUBLISHED_FLAG != 0
            stageSourceId[index] = rowSourceId[resident].toLong() and 0xffff_ffffL
            stageSourceKey[index] = rowSourceKey[resident]
            stagePackedNormal[index] = rowPackedNormal[resident].toInt() and 0xffff
            stageNormalConfidence[index] = rowNormalConfidence[resident].toInt() and 0xff
            stageLineageCount[index] = rowLineageCount[resident].toInt() and 0xffff
        }
        var slot = hash(packVisibilityGridKey(voxel.x, voxel.y, voxel.z))
        while (stageHashRows[slot] != EMPTY_ROW) slot = (slot + 1) and (hashCapacity - 1)
        stageHashKeys[slot] = packVisibilityGridKey(voxel.x, voxel.y, voxel.z)
        stageHashRows[slot] = index
        if (surface != null) stageAttach(index, surface)
        return index
    }

    private fun stageAttach(index: Int, surface: DepthCanonicalSurface) {
        val prior = stageSourceId[index]
        if (prior == 0L || surface.id.value < prior) {
            stageSourceId[index] = surface.id.value
            stageSourceKey[index] = packVisibilityGridKey(surface.voxel.x, surface.voxel.y, surface.voxel.z)
            stagePackedNormal[index] = surface.packedNormal
            stageNormalConfidence[index] = surface.normalConfidence
            stageLineageCount[index] = surface.lineageCount
            stagePublished[index] = false
        }
    }

    private fun occupiedPoint(index: Int): DepthPointMm = DepthPointMm(
        stageOccupiedCameraX[index], stageOccupiedCameraY[index], stageOccupiedCameraZ[index],
    )

    private fun sortStageOrder() {
        for (index in 0 until stageCount) stageOrder[index] = index
        for (position in 1 until stageCount) {
            val value = stageOrder[position]
            var cursor = position - 1
            while (cursor >= 0 && compareStageIndices(stageOrder[cursor], value) > 0) {
                stageOrder[cursor + 1] = stageOrder[cursor]
                cursor--
            }
            stageOrder[cursor + 1] = value
        }
    }

    private fun sortSourceIntentionOrder(sources: List<DepthCanonicalSurface>) {
        for (position in sources.indices) stageOrder[position] = position
        for (position in 1 until sources.size) {
            val value = stageOrder[position]
            var cursor = position - 1
            while (cursor >= 0 && sources[stageOrder[cursor]].id.value > sources[value].id.value) {
                stageOrder[cursor + 1] = stageOrder[cursor]
                cursor--
            }
            stageOrder[cursor + 1] = value
        }
    }

    private fun sortTargetIntentionOrder(targets: List<CanonicalTarget>) {
        for (position in targets.indices) stageOrder[position] = position
        for (position in 1 until targets.size) {
            val value = stageOrder[position]
            var cursor = position - 1
            while (cursor >= 0 && compareTargets(targets[stageOrder[cursor]], targets[value]) > 0) {
                stageOrder[cursor + 1] = stageOrder[cursor]
                cursor--
            }
            stageOrder[cursor + 1] = value
        }
    }

    private fun compareTargets(left: CanonicalTarget, right: CanonicalTarget): Int {
        val x = left.voxel.x.compareTo(right.voxel.x)
        if (x != 0) return x
        val y = left.voxel.y.compareTo(right.voxel.y)
        if (y != 0) return y
        val z = left.voxel.z.compareTo(right.voxel.z)
        if (z != 0) return z
        val nx = left.normalOctX.compareTo(right.normalOctX)
        if (nx != 0) return nx
        val ny = left.normalOctY.compareTo(right.normalOctY)
        return if (ny != 0) ny else left.normalConfidence.compareTo(right.normalConfidence)
    }

    private fun compareStageIndices(left: Int, right: Int): Int {
        val x = stageX[left].compareTo(stageX[right])
        if (x != 0) return x
        val y = stageY[left].compareTo(stageY[right])
        return if (y != 0) y else stageZ[left].compareTo(stageZ[right])
    }

    private fun resetStage() {
        java.util.Arrays.fill(stageHashRows, EMPTY_ROW)
        java.util.Arrays.fill(stageHashKeys, 0L)
        java.util.Arrays.fill(stageFreeBin, NO_DIRECTION)
        java.util.Arrays.fill(stageHasOccupied, false)
        java.util.Arrays.fill(stageChanges, null)
        stageCount = 0
        stageChangeCount = 0
    }

    private fun insertRow(x: Int, y: Int, z: Int): Int {
        check(residentRows < tableCapacity)
        val row = residentRows++
        val key = packVisibilityGridKey(x, y, z)
        rowVoxelKey[row] = key
        var slot = hash(key)
        while (hashRows[slot] != EMPTY_ROW) slot = (slot + 1) and (hashCapacity - 1)
        hashKeys[slot] = key
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
            if (hashKeys[slot] == key) return row
            slot = (slot + 1) and (hashCapacity - 1)
        }
    }

    private fun hash(voxel: Voxel): Int = hash(packVisibilityGridKey(voxel.x, voxel.y, voxel.z))

    private fun hash(value: Long): Int {
        var mixed = value xor (value ushr 33)
        mixed *= -49064778989728563L
        mixed = mixed xor (mixed ushr 33)
        mixed *= -4265267296055464877L
        mixed = mixed xor (mixed ushr 33)
        return mixed.toInt() and (hashCapacity - 1)
    }

    private fun refused(reason: DepthEvidenceRefusal): DepthEvidenceResult.Refused {
        if (reason == DepthEvidenceRefusal.SAMPLE_CAPACITY ||
            reason == DepthEvidenceRefusal.RAY_VISIT_CAPACITY ||
            reason == DepthEvidenceRefusal.SURFACE_CAPACITY
        ) {
            capacityRefusalCount = checkedAdd(capacityRefusalCount, 1)
            lastReceipt = lastReceipt.copy(capacityRefusals = capacityRefusalCount)
        }
        return DepthEvidenceResult.Refused(reason, lastReceipt)
    }

    private fun kindCounts(changes: Array<out DepthEvidenceChange?>, count: Int): IntArray = IntArray(7).also { counts ->
        for (index in 0 until count) {
            val change = changes[index]!!
            when (change) {
                is DepthEvidenceChange.Create -> counts[0]++
                is DepthEvidenceChange.Refine -> counts[1]++
                is DepthEvidenceChange.Relocate -> counts[2]++
                is DepthEvidenceChange.Merge -> counts[3]++
                is DepthEvidenceChange.Split -> counts[4]++
                is DepthEvidenceChange.Replace -> counts[5]++
                is DepthEvidenceChange.Remove -> counts[6]++
            }
        }
    }

    private fun kindCounts(changes: List<DepthEvidenceChange>): IntArray = IntArray(7).also { counts ->
        for (change in changes) {
            when (change) {
                is DepthEvidenceChange.Create -> counts[0]++
                is DepthEvidenceChange.Refine -> counts[1]++
                is DepthEvidenceChange.Relocate -> counts[2]++
                is DepthEvidenceChange.Merge -> counts[3]++
                is DepthEvidenceChange.Split -> counts[4]++
                is DepthEvidenceChange.Replace -> counts[5]++
                is DepthEvidenceChange.Remove -> counts[6]++
            }
        }
    }

    private fun validateChange(change: DepthEvidenceChange): DepthEvidenceRefusal? {
        fun targetOk(target: CanonicalTarget): Boolean = target.voxelInRange() &&
            target.normalOctX in -127..127 && target.normalOctY in -127..127 &&
            target.normalConfidence in 0..255 && !targetUsed(target.voxel)
        fun sourceOk(source: SurfaceId): Boolean = !sourceUsed(source)
        return when (change) {
            is DepthEvidenceChange.Create -> if (targetOk(change.target)) null else DepthEvidenceRefusal.DUPLICATE_TARGET
            is DepthEvidenceChange.Refine -> when {
                !sourceOk(change.sourceId) -> DepthEvidenceRefusal.SOURCE_OVERLAP
                !targetOk(change.target) -> DepthEvidenceRefusal.DUPLICATE_TARGET
                else -> null
            }
            is DepthEvidenceChange.Relocate -> when {
                !sourceOk(change.sourceId) -> DepthEvidenceRefusal.SOURCE_OVERLAP
                !targetOk(change.target) -> DepthEvidenceRefusal.DUPLICATE_TARGET
                else -> null
            }
            is DepthEvidenceChange.Merge -> {
                when {
                    change.sourceIds.isEmpty() || hasDuplicateSources(change.sourceIds) ||
                        change.sourceIds.any { !sourceOk(it) } -> DepthEvidenceRefusal.SOURCE_OVERLAP
                    !targetOk(change.target) -> DepthEvidenceRefusal.DUPLICATE_TARGET
                    else -> null
                }
            }
            is DepthEvidenceChange.Split -> {
                when {
                    change.targets.isEmpty() || !sourceOk(change.sourceId) -> DepthEvidenceRefusal.SOURCE_OVERLAP
                    !change.targets.all(::targetOk) -> DepthEvidenceRefusal.DUPLICATE_TARGET
                    else -> null
                }
            }
            is DepthEvidenceChange.Replace -> {
                when {
                    change.sourceIds.isEmpty() || hasDuplicateSources(change.sourceIds) ||
                        change.sourceIds.any { !sourceOk(it) } -> DepthEvidenceRefusal.SOURCE_OVERLAP
                    change.targets.isEmpty() || !change.targets.all(::targetOk) -> DepthEvidenceRefusal.DUPLICATE_TARGET
                    else -> null
                }
            }
            is DepthEvidenceChange.Remove -> if (sourceOk(change.sourceId)) null else DepthEvidenceRefusal.SOURCE_OVERLAP
        }
    }

    private fun sourceUsed(source: SurfaceId): Boolean {
        for (index in 0 until stageChangeCount) {
            when (val change = stageChanges[index]!!) {
                is DepthEvidenceChange.Refine -> if (change.sourceId == source) return true
                is DepthEvidenceChange.Relocate -> if (change.sourceId == source) return true
                is DepthEvidenceChange.Merge -> if (change.sourceIds.any { it == source }) return true
                is DepthEvidenceChange.Split -> if (change.sourceId == source) return true
                is DepthEvidenceChange.Replace -> if (change.sourceIds.any { it == source }) return true
                is DepthEvidenceChange.Remove -> if (change.sourceId == source) return true
                is DepthEvidenceChange.Create -> Unit
            }
        }
        return false
    }

    private fun targetUsed(voxel: Voxel): Boolean {
        for (index in 0 until stageChangeCount) {
            when (val change = stageChanges[index]!!) {
                is DepthEvidenceChange.Create -> if (change.target.voxel == voxel) return true
                is DepthEvidenceChange.Refine -> if (change.target.voxel == voxel) return true
                is DepthEvidenceChange.Relocate -> if (change.target.voxel == voxel) return true
                is DepthEvidenceChange.Merge -> if (change.target.voxel == voxel) return true
                is DepthEvidenceChange.Split -> if (change.targets.any { it.voxel == voxel }) return true
                is DepthEvidenceChange.Replace -> if (change.targets.any { it.voxel == voxel }) return true
                is DepthEvidenceChange.Remove -> Unit
            }
        }
        return false
    }

    private fun hasDuplicateSources(sources: List<SurfaceId>): Boolean {
        for (left in sources.indices) for (right in 0 until left) {
            if (sources[left] == sources[right]) return true
        }
        return false
    }

    private fun CanonicalTarget.voxelInRange(): Boolean = voxelInRange(voxel)

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

    private fun fixedPrimitiveBytes(): Int = Math.addExact(
        // Retained rows are exactly 32 bytes. Staging adds its bounded
        // primitive columns, three doubles for the deterministic occupied
        // sample, one free-direction bin, one sort index, one lineage column,
        // and one fixed reference slot per row.
        Math.addExact(
            Math.multiplyExact(tableCapacity, EVIDENCE_BYTES),
            Math.multiplyExact(tableCapacity, 95),
        ),
        Math.multiplyExact(hashCapacity, (8 + 4) * 2),
    )

    private fun flags(contradicted: Boolean, published: Boolean): Byte =
        ((if (contradicted) CONTRADICTED_FLAG else 0) or
            (if (published) PUBLISHED_FLAG else 0)).toByte()

    private data class Pending(
        val result: DepthEvidenceResult.Accepted,
        val stageCount: Int,
        val frame: VisibilityGroupFrame,
    )

    private sealed interface Staged {
        data class Accepted(val result: DepthEvidenceResult.Accepted, val stageCount: Int, val frame: VisibilityGroupFrame) : Staged
        data class Refused(val reason: DepthEvidenceRefusal) : Staged
    }

    private class DepthLookupFailure : RuntimeException()
    private class StageCapacityFailure : RuntimeException()

    private companion object {
        const val EMPTY_ROW = -1
        const val EVIDENCE_BYTES = 32
        const val DIRECTION_BINS = 24
        const val NO_DIRECTION = -1
        const val CONTRADICTED_FLAG = 1
        const val PUBLISHED_FLAG = 2

        fun nextPowerOfTwo(value: Int): Int {
            var result = 1
            while (result < value) result = Math.multiplyExact(result, 2)
            return result
        }
    }
}
