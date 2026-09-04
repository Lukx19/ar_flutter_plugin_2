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

/** Immutable input copied by the admission owner before this kernel is called. */
internal class DepthEvidenceBatch(
    val sequence: Long,
    val sourceTimestampNs: Long,
    val groupFrame: VisibilityGroupFrame,
    groupFromCameraGl: List<Double>,
    val intrinsics: VisibilityCameraIntrinsics,
    samples: List<VisibilityDepthSample>,
    val sourceRejectedSamples: Int,
    val tracking: Boolean = true,
) {
    val groupFromCameraGl: List<Double> = immutableDoubles(groupFromCameraGl)
    val samples: List<VisibilityDepthSample> =
        Collections.unmodifiableList(ArrayList(samples))

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

private fun immutableDoubles(values: List<Double>): List<Double> =
    Collections.unmodifiableList(ArrayList(values))

/** A point in group coordinates, expressed in millimetres. */
internal data class DepthPointMm(val x: Double, val y: Double, val z: Double) {
    fun isFinite(): Boolean = x.isFinite() && y.isFinite() && z.isFinite()
}

/** Result of bounded ray traversal supplied by the canonical surface view. */
internal data class DepthRayVisitResult(
    val visitedCells: Int,
    val truncated: Boolean = false,
    val arithmeticOverflow: Boolean = false,
) {
    val visitCount: Int get() = visitedCells
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

internal interface BoundedCanonicalSurfaceView {
    val geometryRevision: Long
    val lineageRevision: Long
    val surfaceCount: Int

    /** Returns only a directly addressed row; implementations must not enumerate rows. */
    fun findSurfaceById(id: SurfaceId): DepthCanonicalSurface?

    fun findSurfaceAt(voxel: Voxel): DepthCanonicalSurface?

    fun visitRayCells(
        startGroupMm: DepthPointMm,
        endpointGroupMm: DepthPointMm,
        maximumVisits: Int,
        visitor: (Voxel, DepthCanonicalSurface?) -> Boolean,
    ): DepthRayVisitResult
}

internal sealed interface DepthEvidenceChange {
    data class Create(val target: CanonicalTarget) : DepthEvidenceChange
    data class Refine(val sourceId: SurfaceId, val target: CanonicalTarget) : DepthEvidenceChange
    data class Relocate(val sourceId: SurfaceId, val target: CanonicalTarget) : DepthEvidenceChange
    data class Merge(val sourceIds: List<SurfaceId>, val target: CanonicalTarget) : DepthEvidenceChange
    data class Split(val sourceId: SurfaceId, val targets: List<CanonicalTarget>) : DepthEvidenceChange
    data class Replace(val sourceIds: List<SurfaceId>, val targets: List<CanonicalTarget>) : DepthEvidenceChange
    data class Remove(val sourceId: SurfaceId) : DepthEvidenceChange
}

/** Scalar receipt for one accepted or previously committed batch. */
internal data class DepthEvidenceReceipt(
    val sequence: Long = 0,
    val sourceTimestampNs: Long = 0,
    val acceptedSamples: Int = 0,
    val rejectedSamples: Int = 0,
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
) {
    val acceptedPixelCount: Int get() = acceptedSamples
    val rejectedPixelCount: Int get() = rejectedSamples
    val rayVisitCount: Int get() = rayVisits
    val touchedRows: Int get() = touchedEvidenceRows
}

internal data class DepthEvidenceWorkReceipt(
    val distinctTouchedVoxelCount: Int,
    val emittedChangeCount: Int,
    val rayVisits: Int,
    val independentDirectionVotes: Int,
    val virtualWorkUnits: Int,
) {
    val touchedEvidenceRows: Int get() = distinctTouchedVoxelCount
}

internal data class DepthEvidenceResourceReceipt(
    val residentEvidenceRows: Int,
    val residentBytes: Int,
    val preparedEvidenceRows: Int,
    val preparedResidentBytes: Int,
    val evidenceRowCapacity: Int,
    val fixedPrimitiveBytes: Int,
    val closed: Boolean,
) {
    val rowCount: Int get() = residentEvidenceRows
}

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
