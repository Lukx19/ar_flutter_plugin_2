package com.uhg0.ar_flutter_plugin_2.visibilitygrid

/**
 * One worker-produced style/target projection qualified by the canonical cut
 * that produced it.  The arrays are intentionally part of the public shape of
 * this package; [VisibilityGridRendererState] copies them before validation so
 * a caller cannot mutate an accepted cut in place.
 */
internal data class QualifiedRendererStyleCut(
    val ownership: VisibilityObservationOwnership,
    val transactionId: Long,
    val geometryRevision: Long,
    val lineageRevision: Long,
    val semanticRevision: Long,
    val coverageRevision: Long,
    val styleRevision: Long,
    val residencyRevision: Long,
    val targetRevision: Long,
    val reset: Boolean,
    val surfaceIds: LongArray,
    val styleRows: ByteArray,
    val targetSurfaceId: Long?,
    val targetDirectionIndex: Int?,
)

internal sealed interface RendererStyleCutResult {
    data class Applied(
        val styleRevision: Long,
        val targetRevision: Long,
        val changedRows: Int,
        val targetSurfaceId: Long?,
    ) : RendererStyleCutResult

    data class Replayed(val styleRevision: Long) : RendererStyleCutResult

    data class Rejected(val reason: RendererStyleCutRejection) : RendererStyleCutResult
}

/** Stable reasons for refusing a qualified renderer cut before mutation. */
internal enum class RendererStyleCutRejection {
    CLOSED,
    STALE_OWNERSHIP,
    GROUP_MISMATCH,
    TRANSACTION_MISMATCH,
    GEOMETRY_REVISION_MISMATCH,
    LINEAGE_REVISION_MISMATCH,
    SEMANTIC_REVISION_STALE,
    COVERAGE_REVISION_STALE,
    STYLE_REVISION_STALE,
    RESIDENCY_REVISION_STALE,
    TARGET_REVISION_STALE,
    REVISION_GAP,
    REVISION_OVERFLOW,
    MALFORMED_LENGTH,
    MALFORMED_STYLE,
    MIXED_STYLE_GENERATION,
    UNSORTED_SURFACE_IDS,
    DUPLICATE_SURFACE_ID,
    UNKNOWN_SURFACE_ID,
    INCOMPLETE_RESET,
    TARGET_NOT_SUPPLIED,
    TARGET_DIRECTION_INVALID,
    REPLAY_MISMATCH,
    CAPACITY,
}

internal const val QUALIFIED_RENDERER_STYLE_CUT_MAX_ROWS = 100_000
