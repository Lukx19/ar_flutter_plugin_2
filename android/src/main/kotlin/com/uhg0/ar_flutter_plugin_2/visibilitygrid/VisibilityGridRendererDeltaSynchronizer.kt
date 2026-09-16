package com.uhg0.ar_flutter_plugin_2.visibilitygrid

/**
 * Applies a grid delta to the renderer mirror without treating an ordinary
 * revision race as a renderer failure.
 *
 * Dart can request a full snapshot while an older native delta is already on
 * the fusion executor. Stale work is ignored; a genuine forward gap is
 * repaired from the grid's bounded full snapshot.
 */
internal fun synchronizeRendererGeometry(
    renderer: VisibilityGridRendererState,
    delta: VisibilityGridDelta,
    fullSnapshot: () -> VisibilityGridSnapshot,
    selectedRenderKeys: () -> LongArray = { fullSnapshot().stableKeys.take(renderer.capacity).toLongArray() },
): RendererGeometrySyncResult {
    if (delta.geometryRevision <= renderer.currentGeometryRevision) {
        return RendererGeometrySyncResult.STALE_IGNORED
    }
    if (
        renderer.applyGeometry(
            revision = delta.geometryRevision,
            reset = delta.reset,
            upsertKeys = delta.upsertKeys.toLongArray(),
            removalKeys = delta.removalKeys.toLongArray(),
            selectedKeysForResetOrReplacement = selectedRenderKeys,
        )
    ) {
        return RendererGeometrySyncResult.DELTA_APPLIED
    }

    val snapshot = fullSnapshot()
    if (snapshot.geometryRevision <= renderer.currentGeometryRevision) {
        return RendererGeometrySyncResult.STALE_IGNORED
    }
    return if (
        renderer.applyGeometry(
            revision = snapshot.geometryRevision,
            reset = true,
            upsertKeys = snapshot.stableKeys.toLongArray(),
            removalKeys = longArrayOf(),
            selectedKeysForResetOrReplacement = selectedRenderKeys,
        )
    ) {
        RendererGeometrySyncResult.SNAPSHOT_APPLIED
    } else {
        RendererGeometrySyncResult.REJECTED
    }
}

internal enum class RendererGeometrySyncResult {
    DELTA_APPLIED,
    SNAPSHOT_APPLIED,
    STALE_IGNORED,
    REJECTED,
}
