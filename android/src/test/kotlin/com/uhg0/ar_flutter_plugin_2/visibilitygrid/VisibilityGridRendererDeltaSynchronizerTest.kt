package com.uhg0.ar_flutter_plugin_2.visibilitygrid

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class VisibilityGridRendererDeltaSynchronizerTest {
    @Test
    fun `stale executor delta after snapshot recovery is ignored`() {
        val renderer = rendererAtRevision(3, longArrayOf(30))
        var snapshotRequested = false

        val result =
            synchronizeRendererGeometry(
                renderer = renderer,
                delta = delta(baseRevision = 1, revision = 2, upserts = listOf(20)),
                fullSnapshot = {
                    snapshotRequested = true
                    snapshot(3, listOf(30))
                },
            )

        assertEquals(RendererGeometrySyncResult.STALE_IGNORED, result)
        assertEquals(false, snapshotRequested)
        assertArrayEquals(longArrayOf(30), renderer.snapshot().keys)
    }

    @Test
    fun `forward revision gap repairs renderer from bounded full snapshot`() {
        val renderer = rendererAtRevision(1, longArrayOf(10))

        val result =
            synchronizeRendererGeometry(
                renderer = renderer,
                delta = delta(baseRevision = 2, revision = 3, upserts = listOf(30)),
                fullSnapshot = { snapshot(3, listOf(10, 20, 30)) },
            )

        assertEquals(RendererGeometrySyncResult.SNAPSHOT_APPLIED, result)
        assertArrayEquals(longArrayOf(10, 20, 30), renderer.snapshot().keys)
    }

    @Test
    fun `next sequential delta applies without requesting a snapshot`() {
        val renderer = rendererAtRevision(1, longArrayOf(10))

        val result =
            synchronizeRendererGeometry(
                renderer = renderer,
                delta = delta(baseRevision = 1, revision = 2, upserts = listOf(20)),
                fullSnapshot = { error("sequential delta must not request a snapshot") },
            )

        assertEquals(RendererGeometrySyncResult.DELTA_APPLIED, result)
        assertArrayEquals(longArrayOf(10, 20), renderer.snapshot().keys)
    }
}

private fun rendererAtRevision(
    revision: Long,
    keys: LongArray,
): VisibilityGridRendererState =
    VisibilityGridRendererState(capacity = 8).also {
        it.startGroup(
            config = syncGroup(8),
            geometryRevision = revision,
            restoredKeys = keys,
        )
    }

private fun delta(
    baseRevision: Long,
    revision: Long,
    upserts: List<Long>,
): VisibilityGridDelta =
    VisibilityGridDelta(
        groupId = "group",
        groupGeneration = 1,
        sessionGeneration = 1,
        baseGeometryRevision = baseRevision,
        geometryRevision = revision,
        reset = false,
        upsertKeys = upserts,
        removalKeys = emptyList(),
        capacity = 8,
        diagnostics = syncDiagnostics(),
    )

private fun snapshot(
    revision: Long,
    keys: List<Long>,
): VisibilityGridSnapshot =
    VisibilityGridSnapshot(
        groupId = "group",
        groupGeneration = 1,
        sessionGeneration = 1,
        geometryRevision = revision,
        stableKeys = keys,
        supportByKey = emptyMap(),
        diagnostics = syncDiagnostics(),
    )

private fun syncGroup(capacity: Int): VisibilityGridGroupConfig =
    VisibilityGridGroupConfig(
        groupId = "group",
        groupGeneration = 1,
        sessionGeneration = 1,
        voxelSizeMeters = 0.1,
        capacity = capacity,
        groupFromWorldGl = identityVisibilityGridTransform(),
        worldFromGroupGl = identityVisibilityGridTransform(),
        restoredGeometryRevision = 0,
        restoredKeys = longArrayOf(),
    )

private fun syncDiagnostics(): VisibilityGridDiagnostics =
    VisibilityGridDiagnostics(
        candidateTracks = 0,
        stableTracks = 0,
        stableVoxels = 0,
        featureTrackCapacity = 16,
        stableVoxelCapacity = 8,
        acceptedSamples = 0,
        rejectedSamples = 0,
        capacityRejectedCandidates = 0,
        featureHealth = "healthy",
        featureTransientUnavailableCount = 0,
        featureFailureCount = 0,
        lastFeatureFusionNs = 0,
        maxFeatureFusionNs = 0,
        estimatedStateBytes = 0,
    )
