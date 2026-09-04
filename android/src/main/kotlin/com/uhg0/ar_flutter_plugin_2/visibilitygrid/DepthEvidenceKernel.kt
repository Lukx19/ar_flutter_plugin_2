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
    private val rowUsed = BooleanArray(tableCapacity)
    private val rowX = IntArray(tableCapacity)
    private val rowY = IntArray(tableCapacity)
    private val rowZ = IntArray(tableCapacity)
    private val rowOccupied = IntArray(tableCapacity)
    private val rowFree = IntArray(tableCapacity)
    private val rowDirections = IntArray(tableCapacity)
    private val rowContradicted = BooleanArray(tableCapacity)
    private val rowSourceId = LongArray(tableCapacity)
    private val rowSourceX = IntArray(tableCapacity)
    private val rowSourceY = IntArray(tableCapacity)
    private val rowSourceZ = IntArray(tableCapacity)
    private val rowPackedNormal = IntArray(tableCapacity)
    private val rowNormalConfidence = IntArray(tableCapacity)
    private val rowPublished = BooleanArray(tableCapacity)

    private var residentRows = 0
    private var lastSequence = Long.MIN_VALUE
    private var lastTimestampNs = Long.MIN_VALUE
    private var activeFrame: VisibilityGroupFrame? = null
    private var pending: Pending? = null
    private var lastReceipt = DepthEvidenceReceipt()
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
        }
        if (staged is Staged.Refused) return refused(staged.reason)

        val accepted = staged as Staged.Accepted
        pending = Pending(accepted.result, accepted.states, accepted.frame)
        return accepted.result
    }

    /** Installs the previously staged primitive evidence exactly once. */
    fun applyPrepared(): DepthEvidenceApplyResult {
        if (closed) return DepthEvidenceApplyResult.NoPrepared(lastReceipt)
        val staged = pending ?: return DepthEvidenceApplyResult.NoPrepared(lastReceipt)
        for (state in staged.states) {
            val row = findRow(state.voxel)
            val index = if (row == EMPTY_ROW) insertRow(state.voxel) else row
            writeState(index, state)
        }
        lastSequence = staged.result.receipt.sequence
        lastTimestampNs = staged.result.receipt.sourceTimestampNs
        activeFrame = staged.frame
        lastReceipt = staged.result.receipt
        pending = null
        return DepthEvidenceApplyResult.Applied(lastReceipt)
    }

    /** Drops the staged packet and leaves all retained primitive state untouched. */
    fun discardPrepared(): DepthEvidenceDiscardResult {
        if (closed) return DepthEvidenceDiscardResult.AlreadyDiscarded(lastReceipt)
        if (pending == null) return DepthEvidenceDiscardResult.AlreadyDiscarded(lastReceipt)
        pending = null
        return DepthEvidenceDiscardResult.Discarded(lastReceipt)
    }

    /** Reports logical resident storage only; observation payloads are never retained. */
    fun resourceReceipt(): DepthEvidenceResourceReceipt {
        val preparedRows = pending?.states?.count { findRow(it.voxel) == EMPTY_ROW } ?: 0
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
        java.util.Arrays.fill(rowUsed, false)
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
        val local = HashMap<Voxel, EvidenceState>()
        val canonical = HashMap<Voxel, DepthCanonicalSurface>()
        val occupiedDirection = HashMap<Voxel, DepthPointMm>()
        val freeDirection = HashMap<Voxel, Int>()
        var acceptedSamples = 0
        var rejectedSamples = batch.sourceRejectedSamples
        var visits = 0
        var overflowCount = 0
        var conflictCount = 0

        val orderedSamples = batch.samples.sortedWith(
            compareBy<VisibilityDepthSample> { it.x }
                .thenBy { it.y }
                .thenBy { it.depthMillimeters }
                .thenBy { it.confidence },
        )
        for (sample in orderedSamples) {
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
            if (endpointSurface != null) retainCanonical(canonical, endpointVoxel, endpointSurface)
            val endpointState = stateFor(endpointVoxel, local, endpointSurface)
            endpointState.occupied = increment(endpointState.occupied).also {
                if (it.second) overflowCount = checkedAdd(overflowCount, 1)
            }.first
            val priorDirection = occupiedDirection[endpointVoxel]
            if (priorDirection == null || comparePoint(cameraPoint, priorDirection) < 0) {
                occupiedDirection[endpointVoxel] = cameraPoint
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
                    if (surface != null) retainCanonical(canonical, voxel, surface)
                    val cellCenter = center(voxel, batch.groupFrame)
                    if (isFreeEvidence(cameraGroup, endpoint, endpointVoxel, voxel, cellCenter, batch.groupFrame)) {
                        val retained = findRow(voxel) != EMPTY_ROW
                        if (surface != null || retained || local.containsKey(voxel)) {
                            stateFor(voxel, local, surface)
                            val direction = directionBin(cameraGroup, cellCenter)
                            freeDirection[voxel] = minOf(freeDirection[voxel] ?: direction, direction)
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
        if (acceptedSamples == 0 && orderedSamples.isNotEmpty()) {
            return Staged.Refused(DepthEvidenceRefusal.INVALID_SAMPLE)
        }
        val extraSamples = batch.samples.size - orderedSamples.size
        if (extraSamples > 0) rejectedSamples = checkedAdd(rejectedSamples, extraSamples)

        val directionVotes = HashMap<Voxel, Int>()
        val changes = ArrayList<DepthEvidenceChange>()
        val states = ArrayList<EvidenceState>(local.size)
        val sourceIds = HashSet<SurfaceId>()
        val targetVoxels = HashSet<Voxel>()
        var conflicts = conflictCount
        val orderedVoxels = local.keys.sortedWith(compareBy<Voxel> { it.x }.thenBy { it.y }.thenBy { it.z })
        for (voxel in orderedVoxels) {
            val state = local.getValue(voxel)
            val surface = canonical[voxel]
            if (surface != null) state.attach(surface)
            val freeBin = freeDirection[voxel]
            if (freeBin != null) {
                val updated = increment(state.free)
                state.free = updated.first
                if (updated.second) overflowCount = checkedAdd(overflowCount, 1)
                val bit = 1 shl freeBin
                if (state.directionMask and bit == 0) {
                    state.directionMask = state.directionMask or bit
                    directionVotes[voxel] = 1
                }
            }
            if (occupiedDirection.containsKey(voxel) && state.free > 0) conflicts = checkedAdd(conflicts, 1)
            val change = transition(
                state,
                surface,
                occupiedDirection[voxel],
                cameraGroup,
                batch.groupFrame,
            )
            if (change != null) {
                validateChange(change, sourceIds, targetVoxels)?.let { return Staged.Refused(it) }
                changes += change
            }
            states += state
        }
        val newRows = states.count { findRow(it.voxel) == EMPTY_ROW }
        if (residentRows + newRows > minOf(configuration.surfaceCapacity, batch.groupFrame.modelCapacity)) {
            return Staged.Refused(DepthEvidenceRefusal.SURFACE_CAPACITY)
        }
        val immutableChanges = Collections.unmodifiableList(changes)
        val immutableStates = Collections.unmodifiableList(states)
        val kindCounts = kindCounts(immutableChanges)
        val virtualWork = checkedAdd(acceptedSamples, checkedAdd(visits, local.size))
        val receipt = DepthEvidenceReceipt(
            sequence = batch.sequence,
            sourceTimestampNs = batch.sourceTimestampNs,
            acceptedSamples = acceptedSamples,
            rejectedSamples = rejectedSamples,
            rayVisits = visits,
            touchedEvidenceRows = local.size,
            independentDirectionVotes = directionVotes.values.sum(),
            createCount = kindCounts[0],
            refineCount = kindCounts[1],
            relocateCount = kindCounts[2],
            mergeCount = kindCounts[3],
            splitCount = kindCounts[4],
            replaceCount = kindCounts[5],
            removeCount = kindCounts[6],
            conflictsRetained = conflicts,
            overflowCount = overflowCount,
            preparedResidentBytes = checkedBytes(newRows),
            p50VirtualWorkUnits = virtualWork,
            p95VirtualWorkUnits = virtualWork,
        )
        val work = DepthEvidenceWorkReceipt(
            distinctTouchedVoxelCount = local.size,
            emittedChangeCount = immutableChanges.size,
            rayVisits = visits,
            independentDirectionVotes = directionVotes.values.sum(),
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
            immutableStates,
            batch.groupFrame,
        )
    }

    private fun transition(
        state: EvidenceState,
        surface: DepthCanonicalSurface?,
        cameraPoint: DepthPointMm?,
        cameraGroup: DepthPointMm,
        frame: VisibilityGroupFrame,
    ): DepthEvidenceChange? {
        val sourceId = if (state.sourceId == 0L) surface?.id?.value ?: 0L else state.sourceId
        if (surface != null) state.attach(surface)
        if (state.contradicted) {
            if (state.occupied < configuration.occupiedEvidenceToShow) return null
            state.contradicted = false
            state.free = 0
            state.directionMask = 0
            state.published = true
            return mutationFor(state, sourceId, cameraPoint, cameraGroup, frame)
        }
        val conflicted = cameraPoint != null && state.free > 0
        if (conflicted && !state.contradicted) return null
        val canCarve = state.free >= configuration.freeEvidenceToCarve &&
            state.free - state.occupied >= configuration.freeEvidenceMargin &&
            hasSeparatedDirections(state.directionMask, configuration.separatedDirectionBinsRequired)
        if (canCarve && !conflicted && sourceId != 0L) {
            state.contradicted = true
            state.occupied = 0
            state.published = false
            return DepthEvidenceChange.Remove(SurfaceId(sourceId))
        }
        if (conflicted && canCarve) return null
        if (state.occupied < configuration.occupiedEvidenceToShow || state.published) return null
        state.published = true
        return mutationFor(state, sourceId, cameraPoint, cameraGroup, frame)
    }

    private fun mutationFor(
        state: EvidenceState,
        sourceId: Long,
        cameraPoint: DepthPointMm?,
        cameraGroup: DepthPointMm,
        frame: VisibilityGroupFrame,
    ): DepthEvidenceChange {
        val target = targetFor(
            state,
            cameraPoint,
            cameraGroup,
            frame,
            sourceId.takeIf { it != 0L }?.let(::SurfaceId),
        )
        return when {
            sourceId == 0L -> DepthEvidenceChange.Create(target.copy(id = null))
            state.sourceX == state.voxel.x && state.sourceY == state.voxel.y && state.sourceZ == state.voxel.z ->
                DepthEvidenceChange.Refine(SurfaceId(sourceId), target.copy(id = SurfaceId(sourceId)))
            else -> DepthEvidenceChange.Relocate(SurfaceId(sourceId), target.copy(id = SurfaceId(sourceId)))
        }
    }

    private fun targetFor(
        state: EvidenceState,
        cameraPoint: DepthPointMm?,
        cameraGroup: DepthPointMm,
        frame: VisibilityGroupFrame,
        sourceId: SurfaceId?,
    ): CanonicalTarget {
        val packed = if (state.packedNormal != 0) state.packedNormal else {
            val center = center(state.voxel, frame)
            val direction = cameraPoint?.let {
                DepthPointMm(cameraGroup.x - it.x, cameraGroup.y - it.y, cameraGroup.z - it.z)
            } ?: center
            encodeNormal(direction)
        }
        return CanonicalTarget(
            id = sourceId,
            voxel = state.voxel,
            normalOctX = (packed ushr 8).toByte().toInt(),
            normalOctY = packed.toByte().toInt(),
            normalConfidence = if (state.normalConfidence != 0) {
                state.normalConfidence
            } else {
                (state.occupied * 64).coerceIn(0, 255)
            },
        )
    }

    private fun stateFor(
        voxel: Voxel,
        local: MutableMap<Voxel, EvidenceState>,
        surface: DepthCanonicalSurface?,
    ): EvidenceState {
        val current = local[voxel]
        if (current != null) return current
        val row = findRow(voxel)
        val state = if (row == EMPTY_ROW) {
            EvidenceState(voxel)
        } else {
            EvidenceState(
                voxel,
                rowOccupied[row], rowFree[row], rowDirections[row], rowContradicted[row],
                rowSourceId[row], rowSourceX[row], rowSourceY[row], rowSourceZ[row],
                rowPackedNormal[row], rowNormalConfidence[row], rowPublished[row],
            )
        }
        if (surface != null) state.attach(surface)
        local[voxel] = state
        return state
    }

    private fun retainCanonical(
        canonical: MutableMap<Voxel, DepthCanonicalSurface>,
        voxel: Voxel,
        surface: DepthCanonicalSurface,
    ) {
        val prior = canonical[voxel]
        if (prior == null || surface.id.value < prior.id.value) canonical[voxel] = surface
    }

    private fun EvidenceState.attach(surface: DepthCanonicalSurface) {
        if (sourceId == 0L) {
            sourceId = surface.id.value
            sourceX = surface.voxel.x
            sourceY = surface.voxel.y
            sourceZ = surface.voxel.z
            packedNormal = surface.packedNormal
            normalConfidence = surface.normalConfidence
            // The row is canonical already, but this batch may still qualify
            // a bounded refinement. The transition below owns publication of
            // that intent after its evidence threshold is reached.
            published = false
        }
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
        val voxelRadius = frame.voxelSizeMicrometres.toDouble() / 1_000.0 * 0.9
        if (offX * offX + offY * offY + offZ * offZ > voxelRadius * voxelRadius) return false
        val fromEndpointX = cellCenter.x - endpoint.x
        val fromEndpointY = cellCenter.y - endpoint.y
        val fromEndpointZ = cellCenter.z - endpoint.z
        val distance2 = fromEndpointX * fromEndpointX + fromEndpointY * fromEndpointY + fromEndpointZ * fromEndpointZ
        val band2 = configuration.safetyBandMillimetres.toDouble() * configuration.safetyBandMillimetres
        return distance2.isFinite() && distance2 > band2 &&
            voxelInRange(voxel) && frame.voxelSizeMicrometres > 0
    }

    private fun writeState(row: Int, state: EvidenceState) {
        rowOccupied[row] = state.occupied
        rowFree[row] = state.free
        rowDirections[row] = state.directionMask
        rowContradicted[row] = state.contradicted
        rowSourceId[row] = state.sourceId
        rowSourceX[row] = state.sourceX
        rowSourceY[row] = state.sourceY
        rowSourceZ[row] = state.sourceZ
        rowPackedNormal[row] = state.packedNormal
        rowNormalConfidence[row] = state.normalConfidence
        rowPublished[row] = state.published
    }

    private fun insertRow(voxel: Voxel): Int {
        check(residentRows < tableCapacity)
        val row = residentRows++
        rowUsed[row] = true
        rowX[row] = voxel.x
        rowY[row] = voxel.y
        rowZ[row] = voxel.z
        var slot = hash(voxel)
        while (hashRows[slot] != EMPTY_ROW) slot = (slot + 1) and (hashCapacity - 1)
        hashKeys[slot] = packVisibilityGridKey(voxel.x, voxel.y, voxel.z)
        hashRows[slot] = row
        return row
    }

    private fun findRow(voxel: Voxel): Int {
        val key = packVisibilityGridKey(voxel.x, voxel.y, voxel.z)
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

    private fun refused(reason: DepthEvidenceRefusal) = DepthEvidenceResult.Refused(reason, lastReceipt)

    private fun kindCounts(changes: List<DepthEvidenceChange>): IntArray = IntArray(7).also { counts ->
        changes.forEach {
            when (it) {
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

    private fun validateChange(
        change: DepthEvidenceChange,
        sourceIds: MutableSet<SurfaceId>,
        targets: MutableSet<Voxel>,
    ): DepthEvidenceRefusal? {
        fun targetOk(target: CanonicalTarget): Boolean =
            target.voxelInRange() && target.normalOctX in -127..127 && target.normalOctY in -127..127 &&
                target.normalConfidence in 0..255 && targets.add(target.voxel)
        return when (change) {
            is DepthEvidenceChange.Create -> if (targetOk(change.target)) null else DepthEvidenceRefusal.DUPLICATE_TARGET
            is DepthEvidenceChange.Refine -> when {
                !sourceIds.add(change.sourceId) -> DepthEvidenceRefusal.SOURCE_OVERLAP
                !targetOk(change.target) -> DepthEvidenceRefusal.DUPLICATE_TARGET
                else -> null
            }
            is DepthEvidenceChange.Relocate -> when {
                !sourceIds.add(change.sourceId) -> DepthEvidenceRefusal.SOURCE_OVERLAP
                !targetOk(change.target) -> DepthEvidenceRefusal.DUPLICATE_TARGET
                else -> null
            }
            is DepthEvidenceChange.Merge -> {
                when {
                    change.sourceIds.isEmpty() || change.sourceIds.distinct().size != change.sourceIds.size ||
                        !sourceIds.addAll(change.sourceIds) -> DepthEvidenceRefusal.SOURCE_OVERLAP
                    !targetOk(change.target) -> DepthEvidenceRefusal.DUPLICATE_TARGET
                    else -> null
                }
            }
            is DepthEvidenceChange.Split -> {
                when {
                    change.targets.isEmpty() || !sourceIds.add(change.sourceId) -> DepthEvidenceRefusal.SOURCE_OVERLAP
                    !change.targets.all(::targetOk) -> DepthEvidenceRefusal.DUPLICATE_TARGET
                    else -> null
                }
            }
            is DepthEvidenceChange.Replace -> {
                when {
                    change.sourceIds.isEmpty() || change.sourceIds.distinct().size != change.sourceIds.size ||
                        !sourceIds.addAll(change.sourceIds) -> DepthEvidenceRefusal.SOURCE_OVERLAP
                    change.targets.isEmpty() || !change.targets.all(::targetOk) -> DepthEvidenceRefusal.DUPLICATE_TARGET
                    else -> null
                }
            }
            is DepthEvidenceChange.Remove -> if (sourceIds.add(change.sourceId)) null else DepthEvidenceRefusal.SOURCE_OVERLAP
        }
    }

    private fun CanonicalTarget.voxelInRange(): Boolean = voxelInRange(voxel)

    private fun hasSeparatedDirections(mask: Int, required: Int): Boolean {
        val bins = (0 until DIRECTION_BINS).filter { mask and (1 shl it) != 0 }
        if (bins.size < required) return false
        return bins.any { first -> bins.any { second -> first != second && directionDot(first, second) <= cos(PI / 6.0) } }
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
        // Eleven IntArray columns, one LongArray column, and three BooleanArray
        // columns are retained for each bounded row. Hash columns are fixed too.
        Math.multiplyExact(tableCapacity, 11 * 4 + 8 + 3),
        Math.multiplyExact(hashCapacity, 8 + 4),
    )

    private data class EvidenceState(
        val voxel: Voxel,
        var occupied: Int = 0,
        var free: Int = 0,
        var directionMask: Int = 0,
        var contradicted: Boolean = false,
        var sourceId: Long = 0,
        var sourceX: Int = voxel.x,
        var sourceY: Int = voxel.y,
        var sourceZ: Int = voxel.z,
        var packedNormal: Int = 0,
        var normalConfidence: Int = 0,
        var published: Boolean = false,
    )

    private data class Pending(val result: DepthEvidenceResult.Accepted, val states: List<EvidenceState>, val frame: VisibilityGroupFrame)

    private sealed interface Staged {
        data class Accepted(val result: DepthEvidenceResult.Accepted, val states: List<EvidenceState>, val frame: VisibilityGroupFrame) : Staged
        data class Refused(val reason: DepthEvidenceRefusal) : Staged
    }

    private class DepthLookupFailure : RuntimeException()

    private companion object {
        const val EMPTY_ROW = -1
        const val EVIDENCE_BYTES = 32
        const val DIRECTION_BINS = 24

        fun nextPowerOfTwo(value: Int): Int {
            var result = 1
            while (result < value) result = Math.multiplyExact(result, 2)
            return result
        }
    }
}
