package com.uhg0.ar_flutter_plugin_2.visibilitygrid

import java.util.Collections

/** Locked, primitive-only calibration for one depth-evidence kernel. */
internal data class DepthEvidenceConfiguration(
    val confidenceMinimum: Int = 128,
    val safetyBandMillimetres: Int = 150,
    val minimumDepthMillimetres: Int = 200,
    val maximumDepthMillimetres: Int = 8_000,
    val occupiedEvidenceToShow: Int = 4,
    val freeEvidenceToCarve: Int = 8,
    val freeEvidenceMargin: Int = 4,
    val separatedDirectionBinsRequired: Int = 2,
    val sampleCapacity: Int = V2_DEPTH_SAMPLE_CAPACITY,
    val rayVisitCapacity: Int = 65_536,
    val surfaceCapacity: Int = 100_000,
) {
    init {
        require(confidenceMinimum in 0..255)
        require(safetyBandMillimetres >= 0)
        require(minimumDepthMillimetres > 0)
        require(maximumDepthMillimetres > minimumDepthMillimetres)
        require(occupiedEvidenceToShow in 1..255)
        require(freeEvidenceToCarve in 1..255)
        require(freeEvidenceMargin in 0..255)
        require(separatedDirectionBinsRequired in 1..24)
        require(sampleCapacity in 1..V2_DEPTH_SAMPLE_CAPACITY)
        require(rayVisitCapacity in 1..65_536)
        require(surfaceCapacity in 1..100_000)
    }
}

/** Scalar-only sample access used by immutable fixtures and leased input. */
internal interface DepthEvidenceSampleAccess {
    val count: Int
    val rejectedCount: Int

    fun xAt(index: Int): Int
    fun yAt(index: Int): Int
    fun depthMillimetresAt(index: Int): Int
    fun confidenceAt(index: Int): Int
}

/** Metadata required by the depth kernel without retaining input samples. */
internal interface DepthEvidenceMetadataView {
    val sequence: Long
    val sourceTimestampNs: Long
    val groupFrame: VisibilityGroupFrame
    val groupFromCameraGl: List<Double>
    val intrinsics: VisibilityCameraIntrinsics
    val sourceRejectedSamples: Int
    val tracking: Boolean
}

/** Immutable metadata companion for a borrowed depth sample lease. */
internal class DepthEvidenceMetadata(
    override val sequence: Long,
    override val sourceTimestampNs: Long,
    override val groupFrame: VisibilityGroupFrame,
    groupFromCameraGl: List<Double>,
    override val intrinsics: VisibilityCameraIntrinsics,
    override val sourceRejectedSamples: Int,
    override val tracking: Boolean = true,
) : DepthEvidenceMetadataView {
    override val groupFromCameraGl: List<Double> = immutableMatrix(groupFromCameraGl)
}

/** Immutable input copied by the admission owner before this kernel is called. */
internal class DepthEvidenceBatch(
    override val sequence: Long,
    override val sourceTimestampNs: Long,
    override val groupFrame: VisibilityGroupFrame,
    groupFromCameraGl: List<Double>,
    override val intrinsics: VisibilityCameraIntrinsics,
    samples: List<VisibilityDepthSample>,
    override val sourceRejectedSamples: Int,
    override val tracking: Boolean = true,
) : DepthEvidenceMetadataView, DepthEvidenceSampleAccess {
    override val groupFromCameraGl: List<Double> = immutableMatrix(groupFromCameraGl)
    val samples: List<VisibilityDepthSample> = immutableDepthSamples(samples)

    override val count: Int get() = samples.size
    override val rejectedCount: Int get() = sourceRejectedSamples
    override fun xAt(index: Int): Int = samples[index].x
    override fun yAt(index: Int): Int = samples[index].y
    override fun depthMillimetresAt(index: Int): Int = samples[index].depthMillimeters
    override fun confidenceAt(index: Int): Int = samples[index].confidence

    fun copy(
        sequence: Long = this.sequence,
        sourceTimestampNs: Long = this.sourceTimestampNs,
        groupFrame: VisibilityGroupFrame = this.groupFrame,
        groupFromCameraGl: List<Double> = this.groupFromCameraGl,
        intrinsics: VisibilityCameraIntrinsics = this.intrinsics,
        samples: List<VisibilityDepthSample> = this.samples,
        sourceRejectedSamples: Int = this.sourceRejectedSamples,
        tracking: Boolean = this.tracking,
    ): DepthEvidenceBatch = DepthEvidenceBatch(
        sequence,
        sourceTimestampNs,
        groupFrame,
        groupFromCameraGl,
        intrinsics,
        samples,
        sourceRejectedSamples,
        tracking,
    )

    override fun equals(other: Any?): Boolean = other is DepthEvidenceBatch &&
        sequence == other.sequence && sourceTimestampNs == other.sourceTimestampNs &&
        groupFrame == other.groupFrame && groupFromCameraGl == other.groupFromCameraGl &&
        intrinsics == other.intrinsics && samples == other.samples &&
        sourceRejectedSamples == other.sourceRejectedSamples && tracking == other.tracking

    override fun hashCode(): Int = listOf(
        sequence, sourceTimestampNs, groupFrame, groupFromCameraGl, intrinsics, samples,
        sourceRejectedSamples, tracking,
    ).hashCode()

    override fun toString(): String =
        "DepthEvidenceBatch(sequence=$sequence, sourceTimestampNs=$sourceTimestampNs, " +
            "groupFrame=$groupFrame, sampleCount=${samples.size}, " +
            "sourceRejectedSamples=$sourceRejectedSamples, tracking=$tracking)"
}

private fun immutableMatrix(values: List<Double>): List<Double> {
    require(values.size == VISIBILITY_TRANSFORM_ELEMENT_COUNT)
    return Collections.unmodifiableList(ArrayList(values))
}

private fun immutableDepthSamples(values: List<VisibilityDepthSample>): List<VisibilityDepthSample> {
    require(values.size <= V2_DEPTH_SAMPLE_CAPACITY)
    return Collections.unmodifiableList(ArrayList(values))
}

private const val VISIBILITY_TRANSFORM_ELEMENT_COUNT = 16

/** A point in group coordinates, expressed in millimetres. */
internal data class DepthPointMm(val x: Double, val y: Double, val z: Double) {
    fun isFinite(): Boolean = x.isFinite() && y.isFinite() && z.isFinite()
}

/** Result of the kernel-owned bounded ray traversal. */
internal data class DepthRayVisitResult(
    val visitedCells: Int,
    val truncated: Boolean = false,
    val arithmeticOverflow: Boolean = false,
    val emptyBlockVisits: Int = 0,
) {
    /** Each certified empty segment costs one bounded traversal unit. */
    val workUnits: Int get() = visitedCells + emptyBlockVisits
}

internal data class DepthCanonicalSurface(
    val id: SurfaceId,
    val voxel: Voxel,
    val packedNormal: Int,
    val normalConfidence: Int,
    val lineageCount: Int,
) {
    init {
        require(packedNormal in 0..0xffff)
        require(normalConfidence in 0..255)
        require(lineageCount in 0..0xffff)
    }
}

/** Immutable proof binding a canonical surface to the exact queried address. */
internal data class AddressedCanonicalSurface(
    val addressedVoxel: Voxel,
    val surface: DepthCanonicalSurface,
)

/** Reusable scalar destination for one canonical lookup. */
internal class CanonicalSurfaceScratch {
    var present: Boolean = false
        private set
    var id: Long = 0L
        private set
    var voxelX: Int = 0
        private set
    var voxelY: Int = 0
        private set
    var voxelZ: Int = 0
        private set
    /** Address queried by a voxel lookup; may differ from the source voxel. */
    var addressedVoxelX: Int = 0
        private set
    var addressedVoxelY: Int = 0
        private set
    var addressedVoxelZ: Int = 0
        private set
    var packedNormal: Int = 0
        private set
    var normalConfidence: Int = 0
        private set
    var lineageCount: Int = 0
        private set

    internal fun clear() {
        present = false
        id = 0L
        voxelX = 0
        voxelY = 0
        voxelZ = 0
        addressedVoxelX = 0
        addressedVoxelY = 0
        addressedVoxelZ = 0
        packedNormal = 0
        normalConfidence = 0
        lineageCount = 0
    }

    internal fun set(
        id: Long,
        voxelX: Int,
        voxelY: Int,
        voxelZ: Int,
        packedNormal: Int,
        normalConfidence: Int,
        lineageCount: Int,
    ) {
        require(id in 1L..0xffff_ffffL)
        require(packedNormal in 0..0xffff)
        require(normalConfidence in 0..255)
        require(lineageCount in 0..0xffff)
        this.present = true
        this.id = id
        this.voxelX = voxelX
        this.voxelY = voxelY
        this.voxelZ = voxelZ
        this.addressedVoxelX = voxelX
        this.addressedVoxelY = voxelY
        this.addressedVoxelZ = voxelZ
        this.packedNormal = packedNormal
        this.normalConfidence = normalConfidence
        this.lineageCount = lineageCount
    }

    /** Writes a row with distinct queried address and canonical source voxel. */
    internal fun setAddressed(
        id: Long,
        addressedVoxelX: Int,
        addressedVoxelY: Int,
        addressedVoxelZ: Int,
        sourceVoxelX: Int,
        sourceVoxelY: Int,
        sourceVoxelZ: Int,
        packedNormal: Int,
        normalConfidence: Int,
        lineageCount: Int,
    ) {
        set(
            id, sourceVoxelX, sourceVoxelY, sourceVoxelZ,
            packedNormal, normalConfidence, lineageCount,
        )
        this.addressedVoxelX = addressedVoxelX
        this.addressedVoxelY = addressedVoxelY
        this.addressedVoxelZ = addressedVoxelZ
    }
}

/**
 * One immutable canonical-cut version. Implementations publish and read this pair atomically.
 * Both coordinates are monotonic, and every visible canonical mutation advances at least one.
 */
internal data class CanonicalRevisionPair(
    val geometryRevision: Long,
    val lineageRevision: Long,
)

internal interface BoundedCanonicalSurfaceView {
    val revisionPair: CanonicalRevisionPair
    val surfaceCount: Int

    /** Optional, cut-qualified certificate for aligned 1/2/4-voxel blocks. Unknown means occupied. */
    val supportsEmptyBlockSkipping: Boolean get() = false
    fun isKnownEmptyBlock(blockX: Int, blockY: Int, blockZ: Int, blockVoxels: Int = 4): Boolean = false

    /** Returns only a directly addressed row; implementations must not enumerate rows. */
    fun findSurfaceById(id: SurfaceId): DepthCanonicalSurface?

    fun findSurfaceAt(voxel: Voxel): AddressedCanonicalSurface?

    /** Scalar lookup used by the depth workspace; implementations may reuse [scratch]. */
    fun findSurfaceAtInto(x: Int, y: Int, z: Int, scratch: CanonicalSurfaceScratch): Boolean {
        val addressed = findSurfaceAt(Voxel(x, y, z))
        if (addressed == null) {
            scratch.clear()
            return false
        }
        val surface = addressed.surface
        require(addressed.addressedVoxel == Voxel(x, y, z))
        scratch.setAddressed(
            surface.id.value,
            addressed.addressedVoxel.x, addressed.addressedVoxel.y, addressed.addressedVoxel.z,
            surface.voxel.x, surface.voxel.y, surface.voxel.z,
            surface.packedNormal, surface.normalConfidence, surface.lineageCount,
        )
        return true
    }

    /** Scalar identity validation used by the depth workspace. */
    fun findSurfaceByIdInto(id: Long, scratch: CanonicalSurfaceScratch): Boolean {
        val surface = findSurfaceById(SurfaceId(id))
        if (surface == null) {
            scratch.clear()
            return false
        }
        scratch.set(
            surface.id.value, surface.voxel.x, surface.voxel.y, surface.voxel.z,
            surface.packedNormal, surface.normalConfidence, surface.lineageCount,
        )
        return true
    }

    fun visitRayCells(
        startGroupMm: DepthPointMm,
        endpointGroupMm: DepthPointMm,
        maximumVisits: Int,
        visitor: (Voxel, DepthCanonicalSurface?) -> Boolean,
    ): DepthRayVisitResult

    /** Scalar supercover seam used by the reusable fusion workspace. */
    fun visitRayCellsInto(
        startGroupMm: DepthPointMm,
        endpointGroupMm: DepthPointMm,
        maximumVisits: Int,
        scratch: CanonicalSurfaceScratch,
        visitor: (Int, Int, Int, CanonicalSurfaceScratch) -> Boolean,
    ): DepthRayVisitResult = visitRayCells(
        startGroupMm, endpointGroupMm, maximumVisits,
    ) { voxel, surface ->
        if (surface == null) scratch.clear() else scratch.setAddressed(
            surface.id.value, voxel.x, voxel.y, voxel.z,
            surface.voxel.x, surface.voxel.y, surface.voxel.z,
            surface.packedNormal, surface.normalConfidence, surface.lineageCount,
        )
        visitor(voxel.x, voxel.y, voxel.z, scratch)
    }
}

internal sealed interface DepthEvidenceChange {
    val operationKind: DepthEvidenceOperationKind
    val sourceCount: Int
    fun sourceAt(index: Int): SurfaceId
    val targetCount: Int
    fun targetAt(index: Int): CanonicalTarget
    val hasCanonicalShape: Boolean
    fun canonicalTargetAt(index: Int): CanonicalTarget
    val liveDelta: Int

    data class Create(val target: CanonicalTarget) : DepthEvidenceChange {
        override val operationKind = DepthEvidenceOperationKind.CREATE
        override val sourceCount = 0
        override fun sourceAt(index: Int): SurfaceId = error("create has no source")
        override val targetCount = 1
        override fun targetAt(index: Int): CanonicalTarget = target
        override val hasCanonicalShape get() = target.id == null
        override fun canonicalTargetAt(index: Int): CanonicalTarget = target
        override val liveDelta = 1
    }

    data class Refine(val sourceId: SurfaceId, val target: CanonicalTarget) : DepthEvidenceChange {
        override val operationKind = DepthEvidenceOperationKind.REFINE
        override val sourceCount = 1
        override fun sourceAt(index: Int): SurfaceId = sourceId
        override val targetCount = 1
        override fun targetAt(index: Int): CanonicalTarget = target
        override val hasCanonicalShape get() = target.id == sourceId
        override fun canonicalTargetAt(index: Int): CanonicalTarget = target
        override val liveDelta = 0
    }

    data class Relocate(val sourceId: SurfaceId, val target: CanonicalTarget) : DepthEvidenceChange {
        override val operationKind = DepthEvidenceOperationKind.RELOCATE
        override val sourceCount = 1
        override fun sourceAt(index: Int): SurfaceId = sourceId
        override val targetCount = 1
        override fun targetAt(index: Int): CanonicalTarget = target
        override val hasCanonicalShape get() = target.id == sourceId
        override fun canonicalTargetAt(index: Int): CanonicalTarget = target
        override val liveDelta = 0
    }

    data class Merge(val sourceIds: List<SurfaceId>, val target: CanonicalTarget) : DepthEvidenceChange {
        override val operationKind = DepthEvidenceOperationKind.MERGE
        override val sourceCount get() = sourceIds.size
        override fun sourceAt(index: Int): SurfaceId = sourceIds[index]
        override val targetCount = 1
        override fun targetAt(index: Int): CanonicalTarget = target
        override val hasCanonicalShape get() = sourceIds.size >= 2 && target.id == null
        override fun canonicalTargetAt(index: Int): CanonicalTarget = target
        override val liveDelta get() = 1 - sourceIds.size
    }

    data class Split(val sourceId: SurfaceId, val targets: List<CanonicalTarget>) : DepthEvidenceChange {
        override val operationKind = DepthEvidenceOperationKind.SPLIT
        override val sourceCount = 1
        override fun sourceAt(index: Int): SurfaceId = sourceId
        override val targetCount get() = targets.size
        override fun targetAt(index: Int): CanonicalTarget = targets[index]
        override val hasCanonicalShape get() = sourceId.value > 0L && targets.size >= 2 && targets.all { it.id == null }
        override fun canonicalTargetAt(index: Int): CanonicalTarget = targets[index]
        override val liveDelta get() = targets.size - 1
    }

    data class Replace(val sourceIds: List<SurfaceId>, val targets: List<CanonicalTarget>) : DepthEvidenceChange {
        override val operationKind = DepthEvidenceOperationKind.REPLACE
        override val sourceCount get() = sourceIds.size
        override fun sourceAt(index: Int): SurfaceId = sourceIds[index]
        override val targetCount get() = targets.size
        override fun targetAt(index: Int): CanonicalTarget = targets[index]
        override val hasCanonicalShape get() = sourceIds.isNotEmpty() && targets.isNotEmpty() && targets.all { it.id == null }
        override fun canonicalTargetAt(index: Int): CanonicalTarget = targets[index]
        override val liveDelta get() = targets.size - sourceIds.size
    }

    data class Remove(val sourceId: SurfaceId) : DepthEvidenceChange {
        override val operationKind = DepthEvidenceOperationKind.REMOVE
        override val sourceCount = 1
        override fun sourceAt(index: Int): SurfaceId = sourceId
        override val targetCount = 0
        override fun targetAt(index: Int): CanonicalTarget = error("remove has no target")
        override val hasCanonicalShape = true
        override fun canonicalTargetAt(index: Int): CanonicalTarget = error("remove has no target")
        override val liveDelta = -1
    }
}

/** Allocation-free semantic description shared by the canonical planner. */
internal enum class DepthEvidenceSupportMode { NONE, SELF, SOURCE_TO_TARGETS }

internal enum class DepthEvidenceOperationKind(
    val structural: Boolean,
    val lineageChanged: Boolean,
    val supportMode: DepthEvidenceSupportMode,
) {
    CREATE(false, false, DepthEvidenceSupportMode.SELF),
    REFINE(false, false, DepthEvidenceSupportMode.NONE),
    RELOCATE(true, true, DepthEvidenceSupportMode.SOURCE_TO_TARGETS),
    MERGE(true, true, DepthEvidenceSupportMode.SOURCE_TO_TARGETS),
    SPLIT(true, true, DepthEvidenceSupportMode.SOURCE_TO_TARGETS),
    REPLACE(true, true, DepthEvidenceSupportMode.SOURCE_TO_TARGETS),
    REMOVE(true, true, DepthEvidenceSupportMode.NONE);
}

/**
 * Scalar receipt for one batch attempt, whether accepted or refused. An applied
 * accepted receipt is also retained as the kernel's last committed receipt.
 */
internal data class DepthEvidenceReceipt(
    val sequence: Long = 0,
    val sourceTimestampNs: Long = 0,
    val acceptedSamples: Int = 0,
    val rejectedSamples: Int = 0,
    /** Bounded traversal units: fine cells plus certified empty block segments. */
    val rayVisits: Int = 0,
    val touchedEvidenceRows: Int = 0,
    val independentDirectionVotes: Int = 0,
    val createCount: Int = 0,
    val refineCount: Int = 0,
    val relocateCount: Int = 0,
    val mergeCount: Int = 0,
    val splitCount: Int = 0,
    val replaceCount: Int = 0,
    val removeCount: Int = 0,
    val conflictsRetained: Int = 0,
    val capacityRefusals: Int = 0,
    val overflowCount: Int = 0,
    val preparedResidentBytes: Int = 0,
    val p50VirtualWorkUnits: Int = 0,
    val p95VirtualWorkUnits: Int = 0,
)

internal data class DepthEvidenceWorkReceipt(
    val distinctTouchedVoxelCount: Int,
    val emittedChangeCount: Int,
    val rayVisits: Int,
    val independentDirectionVotes: Int,
    val virtualWorkUnits: Int,
)

internal data class DepthEvidenceResourceReceipt(
    val residentEvidenceRows: Int,
    val residentBytes: Int,
    val preparedEvidenceRows: Int,
    val preparedResidentBytes: Int,
    val evidenceRowCapacity: Int,
    val fixedPrimitiveBytes: Int,
    val closed: Boolean,
    val maximumAcceptedOutputReserveBytes: Int = 0,
    val modeledMaximumSemanticStateBytes: Int = 0,
    val semanticStateBudgetBytes: Int = 0,
)

internal sealed interface DepthEvidenceResult {
    data class Accepted(
        val expectedGeometryRevision: Long,
        val expectedLineageRevision: Long,
        val changes: List<DepthEvidenceChange>,
        val receipt: DepthEvidenceReceipt,
        val work: DepthEvidenceWorkReceipt,
    ) : DepthEvidenceResult

    data class Refused(
        val reason: DepthEvidenceRefusal,
        val receipt: DepthEvidenceReceipt,
    ) : DepthEvidenceResult
}

internal sealed interface DepthEvidenceApplyResult {
    data class Applied(val receipt: DepthEvidenceReceipt) : DepthEvidenceApplyResult
    data class NoPrepared(val receipt: DepthEvidenceReceipt) : DepthEvidenceApplyResult
}

internal sealed interface DepthEvidenceDiscardResult {
    data class Discarded(val receipt: DepthEvidenceReceipt) : DepthEvidenceDiscardResult
    data class AlreadyDiscarded(val receipt: DepthEvidenceReceipt) : DepthEvidenceDiscardResult
}

internal enum class DepthEvidenceRefusal {
    CLOSED,
    PREPARED_BUSY,
    NOT_TRACKING,
    DUPLICATE_TIMESTAMP,
    INVALID_FRAME,
    INVALID_SAMPLE,
    STALE_CANONICAL_CUT,
    SAMPLE_CAPACITY,
    RAY_VISIT_CAPACITY,
    SURFACE_CAPACITY,
    SOURCE_OVERLAP,
    DUPLICATE_TARGET,
    ARITHMETIC_OVERFLOW,
    CANONICAL_LOOKUP_FAILED,
}
