package com.uhg0.ar_flutter_plugin_2.visibilitygrid

import java.util.Collections

/** One immutable stable-identity row handed from canonical storage to a renderer. */
internal data class CommittedGeometryRow(
    val surfaceId: Long,
    val voxel: Voxel,
    val packedNormal: Int,
    val normalConfidence: Int,
    val lineageCount: Int,
) {
    init {
        require(surfaceId in 1 until 0x1_0000_0000L)
        require(normalConfidence in 0..255)
        require(lineageCount in 0..0xffff)
    }
}

/**
 * Complete, post-commit canonical geometry delta. The arrays and list are
 * copied at the boundary so a renderer cannot retain mutable canonical state.
 */
internal class CommittedGeometryCut(
    val ownership: VisibilityObservationOwnership,
    val transactionId: Long,
    val baseGeometryRevision: Long,
    val geometryRevision: Long,
    val lineageRevision: Long,
    val reset: Boolean,
    upserts: List<CommittedGeometryRow>,
    removedSurfaceIds: LongArray,
) {
    val upserts: List<CommittedGeometryRow> =
        Collections.unmodifiableList(ArrayList(upserts))
    private val removedSurfaceIdsValue: LongArray = removedSurfaceIds.copyOf()

    /** Returns a defensive copy of stable identities removed by this cut. */
    val removedSurfaceIds: LongArray
        get() = removedSurfaceIdsValue.copyOf()

    init {
        require(transactionId >= 0)
        require(baseGeometryRevision >= 0)
        require(geometryRevision >= 0)
        require(lineageRevision >= 0)
        if (reset) {
            require(baseGeometryRevision == 0L)
        } else {
            require(baseGeometryRevision < Long.MAX_VALUE)
            require(geometryRevision == baseGeometryRevision + 1L)
        }
        require(upserts.map { it.surfaceId }.toSet().size == upserts.size)
        require(removedSurfaceIds.all { it in 1 until 0x1_0000_0000L })
        require(removedSurfaceIds.distinct().size == removedSurfaceIds.size)
        // REPLACEMENT may retire and recreate the same stable identity atomically.
        // Consumers apply removals before upserts from this one committed cut.
    }

    /** Creates one rebuild page while preserving the exact cut identity. */
    internal fun withUpserts(rows: List<CommittedGeometryRow>): CommittedGeometryCut =
        CommittedGeometryCut(
            ownership = ownership,
            transactionId = transactionId,
            baseGeometryRevision = baseGeometryRevision,
            geometryRevision = geometryRevision,
            lineageRevision = lineageRevision,
            reset = reset,
            upserts = rows,
            removedSurfaceIds = removedSurfaceIdsValue,
        )

    override fun equals(other: Any?): Boolean = other is CommittedGeometryCut &&
        ownership == other.ownership &&
        transactionId == other.transactionId &&
        baseGeometryRevision == other.baseGeometryRevision &&
        geometryRevision == other.geometryRevision &&
        lineageRevision == other.lineageRevision &&
        reset == other.reset &&
        upserts == other.upserts &&
        removedSurfaceIdsValue.contentEquals(other.removedSurfaceIdsValue)

    override fun hashCode(): Int = listOf(
        ownership,
        transactionId,
        baseGeometryRevision,
        geometryRevision,
        lineageRevision,
        reset,
        upserts,
    ).hashCode() * 31 + removedSurfaceIdsValue.contentHashCode()

    override fun toString(): String =
        "CommittedGeometryCut(ownership=$ownership, transactionId=$transactionId, " +
            "baseGeometryRevision=$baseGeometryRevision, geometryRevision=$geometryRevision, " +
            "lineageRevision=$lineageRevision, reset=$reset, upserts=$upserts, " +
            "removedSurfaceIds=${removedSurfaceIdsValue.contentToString()})"
}

/** Copies only the bounded dirty rows and removed identities from a prepared mutation. */
internal fun PreparedCanonicalMutation.toCommittedGeometryCut(
    ownership: VisibilityObservationOwnership,
    transactionId: Long,
): CommittedGeometryCut {
    val rows = ArrayList<CommittedGeometryRow>(dirtyRowCount)
    check(visitDirtyRows { row ->
        rows += CommittedGeometryRow(
            surfaceId = row.id.value,
            voxel = row.voxel.copy(),
            packedNormal = row.packedNormal,
            normalConfidence = row.normalConfidence,
            lineageCount = targetLineageCount,
        )
        true
    })
    val removed = LongArray(removedSurfaceCount)
    var index = 0
    visitRemovedSurfaceIds { id ->
        removed[index++] = id.value
        true
    }
    check(index == removed.size)
    return CommittedGeometryCut(
        ownership = ownership,
        transactionId = transactionId,
        baseGeometryRevision = sourceCut.geometryRevision,
        geometryRevision = targetGeometryRevision,
        lineageRevision = targetLineageRevision,
        reset = false,
        upserts = rows,
        removedSurfaceIds = removed,
    )
}
