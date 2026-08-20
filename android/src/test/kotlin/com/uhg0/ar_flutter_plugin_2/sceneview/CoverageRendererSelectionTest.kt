package com.uhg0.ar_flutter_plugin_2.sceneview

import com.uhg0.ar_flutter_plugin_2.pointcloud.CoveragePointRenderSnapshot
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class CoverageRendererSelectionTest {
    @Test
    fun `M0d mode resource ledger stays within the shared eight MiB cap`() {
        assertEquals(6_680_000, CoverageRendererLimits.allModeOwnedBufferBytes)
        assertTrue(
            CoverageRendererLimits.allModeOwnedBufferBytes <=
                CoverageRendererLimits.SHARED_OWNED_BUFFER_LIMIT_BYTES,
        )
        assertEquals(2_000, CoverageRendererLimits.RAW_POINT_CAPACITY)
        assertEquals(20_000, CoverageRendererLimits.CENTROID_CAPACITY)
        assertEquals(8_000, CoverageRendererLimits.CUBE_CAPACITY)
        assertEquals(7_486_208, CoverageRendererLimits.maximumActiveRendererBytes)
        assertTrue(
            CoverageRendererLimits.maximumActiveRendererBytes <=
                CoverageRendererLimits.SHARED_OWNED_BUFFER_LIMIT_BYTES,
        )
    }

    @Test
    fun `maximum active cube ledger fits but one extra byte is rejected`() {
        val telemetry = RendererTelemetry()
        telemetry.setOwnedBufferBytes(
            "selection",
            CoverageRendererLimits.NATIVE_SELECTION_BYTES,
        )
        telemetry.setOwnedBufferBytes("auxiliary", CoverageRendererLimits.AUXILIARY_BYTES)
        telemetry.setOwnedBufferBytes(
            "snapshot-handoff",
            CoverageRendererLimits.CUBE_SNAPSHOT_HANDOFF_BYTES,
        )
        telemetry.setOwnedBufferBytes(
            "cubes",
            CoverageRendererLimits.CUBE_CAPACITY * CoverageCubeMeshResources.OWNED_BYTES_PER_VOXEL,
        )
        assertEquals(
            CoverageRendererLimits.maximumActiveRendererBytes,
            telemetry.snapshot().getValue("ownedBufferBytes"),
        )

        assertThrows(IllegalStateException::class.java) {
            telemetry.setOwnedBufferBytes(
                "overflow",
                CoverageRendererLimits.SHARED_OWNED_BUFFER_LIMIT_BYTES -
                    CoverageRendererLimits.maximumActiveRendererBytes + 1,
            )
        }
    }

    @Test
    fun `replacement fence releases cube bytes before installing centroid bytes`() {
        val telemetry = RendererTelemetry()
        telemetry.setOwnedBufferBytes("selection", CoverageRendererLimits.NATIVE_SELECTION_BYTES)
        telemetry.setOwnedBufferBytes("auxiliary", CoverageRendererLimits.AUXILIARY_BYTES)
        telemetry.setOwnedBufferBytes(
            "snapshot-handoff",
            CoverageRendererLimits.CUBE_SNAPSHOT_HANDOFF_BYTES,
        )
        telemetry.setOwnedBufferBytes(
            "active",
            CoverageRendererLimits.CUBE_CAPACITY * CoverageCubeMeshResources.OWNED_BYTES_PER_VOXEL,
        )
        telemetry.removeOwner("active")
        telemetry.setOwnedBufferBytes(
            "active",
            CoverageRendererLimits.CENTROID_CAPACITY * CoveragePointMeshResources.OWNED_BYTES_PER_ROW,
        )

        assertEquals(
            CoverageRendererLimits.NATIVE_SELECTION_BYTES +
                CoverageRendererLimits.AUXILIARY_BYTES +
                CoverageRendererLimits.CUBE_SNAPSHOT_HANDOFF_BYTES +
                CoverageRendererLimits.CENTROID_CAPACITY * CoveragePointMeshResources.OWNED_BYTES_PER_ROW,
            telemetry.snapshot().getValue("ownedBufferBytes"),
        )
        assertEquals(
            CoverageRendererLimits.maximumActiveRendererBytes,
            telemetry.snapshot().getValue("peakOwnedBufferBytes"),
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
    fun `new lower identity replaces only by an explicit reset`() {
        val selector = CoveragePresentationSelector(2)
        selector.select(
            CoveragePointRenderSnapshot(
                revision = 1,
                enabled = true,
                capacity = 3,
                count = 3,
                keys = longArrayOf(30, 10, 20),
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
                keys = longArrayOf(30, 10, 20, 5),
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

        assertArrayEquals(longArrayOf(5, 10), bounded.keys)
        assertTrue(checkNotNull(bounded.update).reset)
    }
}
