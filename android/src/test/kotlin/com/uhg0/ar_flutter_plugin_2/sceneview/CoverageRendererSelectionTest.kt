package com.uhg0.ar_flutter_plugin_2.sceneview

import com.uhg0.ar_flutter_plugin_2.pointcloud.CoveragePointRenderSnapshot
import com.uhg0.ar_flutter_plugin_2.pointcloud.CoverageRendererCoverage
import com.uhg0.ar_flutter_plugin_2.pointcloud.CoverageRendererStyleRowV1
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class CoverageRendererSelectionTest {
    @Test
    fun `M0d lazy mode resource peaks stay within the shared eight MiB cap`() {
        assertEquals(2_000, CoverageRendererLimits.RAW_POINT_CAPACITY)
        assertEquals(20_000, CoverageRendererLimits.CENTROID_CAPACITY)
        assertEquals(8_000, CoverageRendererLimits.CUBE_CAPACITY)
        assertEquals(7_963_200, CoverageRendererLimits.maximumActiveRendererBytes)
        com.uhg0.ar_flutter_plugin_2.pointcloud.VoxelRenderMode.entries.forEach { mode ->
            assertTrue(
                "$mode startup peak must fit the shared renderer cap",
                CoverageRendererLimits.activeRendererPeakBytes(mode) <=
                    CoverageRendererLimits.SHARED_OWNED_BUFFER_LIMIT_BYTES,
            )
        }
    }

    @Test
    fun `production maximum cube resources reserve every owner and reject exact limit plus one`() {
        val telemetry = RendererTelemetry()
        val ledger = CoverageRendererAllocationLedger(telemetry)
        ledger.installPersistentCoverageState(com.uhg0.ar_flutter_plugin_2.pointcloud.VoxelRenderMode.CUBES)
        ledger.updateSnapshotHandoff(com.uhg0.ar_flutter_plugin_2.pointcloud.VoxelRenderMode.CUBES)
        ledger.installCubeResources("cubes", CoverageRendererLimits.CUBE_CAPACITY)
        assertEquals(
            CoverageRendererLimits.maximumActiveRendererBytes,
            telemetry.snapshot().getValue("ownedBufferBytes"),
        )

        telemetry.setOwnedBufferBytes(
            "exact-cap-reservation",
            CoverageRendererLimits.SHARED_OWNED_BUFFER_LIMIT_BYTES -
                CoverageRendererLimits.maximumActiveRendererBytes,
        )
        assertThrows(IllegalStateException::class.java) {
            telemetry.setOwnedBufferBytes("limit-plus-one", 1)
        }
    }

    @Test
    fun `replacement fence releases actual cube startup owners before installing centroid startup`() {
        val telemetry = RendererTelemetry()
        val ledger = CoverageRendererAllocationLedger(telemetry)
        ledger.installPersistentCoverageState(
            com.uhg0.ar_flutter_plugin_2.pointcloud.VoxelRenderMode.CUBES,
        )
        ledger.updateSnapshotHandoff(com.uhg0.ar_flutter_plugin_2.pointcloud.VoxelRenderMode.CUBES)
        ledger.installCubeResources("active", CoverageRendererLimits.CUBE_CAPACITY)
        ledger.releaseCubeResources("active")
        ledger.installPersistentCoverageState(
            com.uhg0.ar_flutter_plugin_2.pointcloud.VoxelRenderMode.CENTROIDS,
        )
        ledger.updateSnapshotHandoff(com.uhg0.ar_flutter_plugin_2.pointcloud.VoxelRenderMode.CENTROIDS)
        ledger.installPointResources("active", CoverageRendererLimits.CENTROID_CAPACITY)

        assertEquals(
            CoverageRendererLimits.activeRendererPeakBytes(
                com.uhg0.ar_flutter_plugin_2.pointcloud.VoxelRenderMode.CENTROIDS,
            ),
            telemetry.snapshot().getValue("ownedBufferBytes"),
        )
        assertEquals(
            CoverageRendererLimits.maximumActiveRendererBytes,
            telemetry.snapshot().getValue("peakOwnedBufferBytes"),
        )
    }

    @Test
    fun `point startup index staging is retained only until its production completion owner is released`() {
        val telemetry = RendererTelemetry()
        val ledger = CoverageRendererAllocationLedger(telemetry)
        ledger.installPersistentCoverageState(
            com.uhg0.ar_flutter_plugin_2.pointcloud.VoxelRenderMode.CENTROIDS,
        )
        ledger.updateSnapshotHandoff(com.uhg0.ar_flutter_plugin_2.pointcloud.VoxelRenderMode.CENTROIDS)
        ledger.installPointResources("centroids", CoverageRendererLimits.CENTROID_CAPACITY)

        assertEquals(
            CoverageRendererLimits.activeRendererPeakBytes(
                com.uhg0.ar_flutter_plugin_2.pointcloud.VoxelRenderMode.CENTROIDS,
            ),
            telemetry.snapshot().getValue("ownedBufferBytes"),
        )

        ledger.completePointStartup("centroids")

        assertEquals(
            CoverageRendererLimits.activeRendererPeakBytes(
                com.uhg0.ar_flutter_plugin_2.pointcloud.VoxelRenderMode.CENTROIDS,
            ) -
                CoverageRendererLimits.CENTROID_CAPACITY *
                    CoveragePointMeshResources.STARTUP_INDEX_STAGING_BYTES_PER_ROW,
            telemetry.snapshot().getValue("ownedBufferBytes"),
        )
    }

    @Test
    fun `over-cap cube presentation selects stable identities and forces a reset`() {
        val snapshot = CoveragePointRenderSnapshot(
            revision = 7,
            enabled = true,
            capacity = 20_000,
            count = 3,
            keys = longArrayOf(30, 10, 20),
            positions = floatArrayOf(30f, 0f, 0f, 10f, 0f, 0f, 20f, 0f, 0f),
            colors = intArrayOf(30, 10, 20),
        )

        val bounded = snapshot.boundedForPresentation(2)

        assertEquals(2, bounded.capacity)
        assertEquals(2, bounded.count)
        assertArrayEquals(longArrayOf(10, 20), bounded.keys)
        assertArrayEquals(floatArrayOf(10f, 0f, 0f, 20f, 0f, 0f), bounded.positions, 0f)
        val update = checkNotNull(bounded.update)
        assertTrue(update.reset)
        assertEquals(2, update.spans.single().colors.size)
    }

    @Test
    fun `ordinary retained-row change preserves selected identities and emits one dirty span`() {
        val selector = CoveragePresentationSelector(2)
        selector.select(
            CoveragePointRenderSnapshot(
                revision = 1,
                enabled = true,
                capacity = 4,
                count = 4,
                keys = longArrayOf(30, 10, 20, 40),
                positions = FloatArray(12),
                colors = IntArray(4),
            ),
        )

        val bounded = selector.select(
            CoveragePointRenderSnapshot(
                revision = 2,
                enabled = true,
                capacity = 4,
                count = 4,
                keys = longArrayOf(30, 10, 20, 40),
                positions = floatArrayOf(0f, 0f, 0f, 11f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f),
                colors = IntArray(4),
                update = com.uhg0.ar_flutter_plugin_2.pointcloud.CoveragePointRenderUpdate(
                    geometryRevision = 2,
                    visibilityRevision = 0,
                    enabled = true,
                    count = 4,
                    spans = listOf(
                        com.uhg0.ar_flutter_plugin_2.pointcloud.CoveragePointSpan(
                            startSlot = 1,
                            positions = floatArrayOf(11f, 0f, 0f),
                            colors = intArrayOf(0),
                        ),
                    ),
                    reset = false,
                ),
            ),
        )

        assertArrayEquals(longArrayOf(10, 20), bounded.keys)
        assertEquals(false, checkNotNull(bounded.update).reset)
        assertEquals(0, bounded.update.spans.single().startSlot)
        assertArrayEquals(floatArrayOf(11f, 0f, 0f), bounded.update.spans.single().positions, 0f)
    }

    @Test
    fun `presentation selection preserves style rows with their stable identities`() {
        val uncovered = CoverageRendererStyleRowV1().encode()
        val complete = CoverageRendererStyleRowV1(
            semanticGeneration = 3,
            styleGeneration = 4,
            coverage = CoverageRendererCoverage.COMPLETE,
        ).encode()
        val snapshot = CoveragePointRenderSnapshot(
            revision = 7,
            enabled = true,
            capacity = 3,
            count = 3,
            keys = longArrayOf(30, 10, 20),
            positions = FloatArray(9),
            colors = intArrayOf(30, 10, 20),
            styleRows = uncovered + complete + uncovered,
        )

        val bounded = snapshot.boundedForPresentation(2)

        assertArrayEquals(longArrayOf(10, 20), bounded.keys)
        assertEquals(
            CoverageRendererCoverage.COMPLETE,
            CoverageRendererStyleRowV1.decode(bounded.styleRows).coverage,
        )
        assertEquals(
            CoverageRendererCoverage.UNCOVERED,
            CoverageRendererStyleRowV1.decode(bounded.styleRows, 16).coverage,
        )
        assertArrayEquals(bounded.styleRows, bounded.update!!.spans.single().styleRows)
    }

    @Test
    fun `new lower identity replaces one full selector slot without moving retained slots`() {
        val selector = CoveragePresentationSelector(3)
        selector.select(
            CoveragePointRenderSnapshot(
                revision = 1,
                enabled = true,
                capacity = 4,
                count = 3,
                keys = longArrayOf(30, 20, 40),
                positions = FloatArray(9),
                colors = IntArray(3),
            ),
        )

        val bounded = selector.select(
            CoveragePointRenderSnapshot(
                revision = 2,
                enabled = true,
                capacity = 4,
                count = 4,
                keys = longArrayOf(30, 20, 40, 10),
                positions = FloatArray(12),
                colors = IntArray(4),
                update = com.uhg0.ar_flutter_plugin_2.pointcloud.CoveragePointRenderUpdate(
                    geometryRevision = 2,
                    visibilityRevision = 0,
                    enabled = true,
                    count = 4,
                    spans = listOf(
                        com.uhg0.ar_flutter_plugin_2.pointcloud.CoveragePointSpan(
                            startSlot = 3,
                            positions = floatArrayOf(0f, 0f, 0f),
                            colors = intArrayOf(0),
                        ),
                    ),
                    reset = false,
                ),
            ),
        )

        // 20 and 30 retain their presentation destinations. The largest
        // selected key (40) is evicted and its slot is deterministically
        // reused by the newly admitted lower key (10).
        assertArrayEquals(longArrayOf(20, 30, 10), bounded.keys)
        val update = checkNotNull(bounded.update)
        assertEquals(false, update.reset)
        assertEquals(1, update.spans.size)
        assertEquals(2, update.spans.single().startSlot)
        assertEquals(1, update.spans.single().colors.size)
    }
}
