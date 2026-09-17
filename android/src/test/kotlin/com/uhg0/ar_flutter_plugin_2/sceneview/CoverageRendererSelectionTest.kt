package com.uhg0.ar_flutter_plugin_2.sceneview

import com.uhg0.ar_flutter_plugin_2.pointcloud.CoveragePointRenderSnapshot
import com.uhg0.ar_flutter_plugin_2.pointcloud.CoverageRendererCoverage
import com.uhg0.ar_flutter_plugin_2.pointcloud.CoverageRendererStyleRowV1
import com.uhg0.ar_flutter_plugin_2.visibilitygrid.VisibilityGridRendererState
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CoverageRendererSelectionTest {
    @Test
    fun `production resource factory installs replacement before releasing old generation`() {
        val events = mutableListOf<String>()
        val modes = mutableListOf<com.uhg0.ar_flutter_plugin_2.pointcloud.VoxelRenderMode>()
        val factory = CoverageRendererResourceFactory()
        factory.replaceCube(
            8_000,
            "cubes",
            create = { mode, capacity, owner -> "cube".also { events += "create:$it:$mode:$capacity:$owner"; modes += mode } },
            release = { value: String -> events += "release:$value" },
        )
        factory.replacePoint(
            com.uhg0.ar_flutter_plugin_2.pointcloud.VoxelRenderMode.CENTROIDS,
            20_000,
            "centroids",
            create = { mode, capacity, owner -> "centroid".also { events += "create:$it:$mode:$capacity:$owner"; modes += mode } },
            release = { value: String -> events += "release:$value" },
        )
        factory.clear()
        assertEquals(
            listOf("create:cube:CUBES:8000:cubes", "create:centroid:CENTROIDS:20000:centroids", "release:cube", "release:centroid"),
            events,
        )
        assertEquals(
            listOf(
                com.uhg0.ar_flutter_plugin_2.pointcloud.VoxelRenderMode.CUBES,
                com.uhg0.ar_flutter_plugin_2.pointcloud.VoxelRenderMode.CENTROIDS,
            ),
            modes,
        )
    }

    @Test
    fun `failed replacement leaves the current renderer owner mounted`() {
        val released = mutableListOf<String>()
        val factory = CoverageRendererResourceFactory()
        factory.replaceCube(
            8_000,
            "current",
            create = { _, _, _ -> "current" },
            release = { value: String -> released += value },
        )

        val failed =
            factory.replacePoint(
                com.uhg0.ar_flutter_plugin_2.pointcloud.VoxelRenderMode.CENTROIDS,
                20_000,
                "replacement",
                create = { _, _, _ -> throw IllegalStateException("allocation failed") },
                release = { value: String -> released += value },
            )
        assertNull(failed)
        assertEquals(emptyList<String>(), released)
        factory.clear()
        assertEquals(listOf("current"), released)
    }

    @Test
    fun `cube to centroid replacement clears first and restores ledger after one release each`() {
        val telemetry = RendererTelemetry()
        val ledger = CoverageRendererAllocationLedger(telemetry)
        val events = mutableListOf<String>()
        val factory = CoverageRendererResourceFactory(
            admit = { mode, _ -> ledger.admitResourceReplacement(mode) },
            onClearFirst = { transition ->
                assertEquals(CoverageRendererTransitionStrategy.CLEAR_FIRST, transition.admission.strategy)
                events += "clear-first"
            },
        )

        fun construct(mode: com.uhg0.ar_flutter_plugin_2.pointcloud.VoxelRenderMode): String {
            ledger.installPersistentCoverageState(mode)
            ledger.updateSnapshotHandoff(mode)
            when (mode) {
                com.uhg0.ar_flutter_plugin_2.pointcloud.VoxelRenderMode.CUBES ->
                    ledger.installCubeResources("active", CoverageRendererLimits.CUBE_CAPACITY)
                else ->
                    ledger.installPointResources("active", CoverageRendererLimits.CENTROID_CAPACITY)
            }
            events += "create:$mode"
            return mode.name
        }
        fun release(mode: com.uhg0.ar_flutter_plugin_2.pointcloud.VoxelRenderMode) {
            if (mode == com.uhg0.ar_flutter_plugin_2.pointcloud.VoxelRenderMode.CUBES) {
                ledger.releaseCubeResources("active")
            } else {
                ledger.releasePointResources("active")
            }
            ledger.clearCoverageState()
            events += "release:$mode"
        }

        factory.replaceCube(
            CoverageRendererLimits.CUBE_CAPACITY,
            "active",
            create = { mode, _, _ -> construct(mode) },
            release = { release(com.uhg0.ar_flutter_plugin_2.pointcloud.VoxelRenderMode.CUBES) },
        )
        factory.replacePoint(
            com.uhg0.ar_flutter_plugin_2.pointcloud.VoxelRenderMode.CENTROIDS,
            CoverageRendererLimits.CENTROID_CAPACITY,
            "active",
            create = { mode, _, _ -> construct(mode) },
            release = { release(com.uhg0.ar_flutter_plugin_2.pointcloud.VoxelRenderMode.CENTROIDS) },
        )
        factory.clear()

        assertEquals(
            listOf("create:CUBES", "clear-first", "release:CUBES", "create:CENTROIDS", "release:CENTROIDS"),
            events,
        )
        assertTrue((telemetry.snapshot().getValue("peakOwnedBufferBytes") as Int) <= 8 * 1024 * 1024)
        assertEquals(0, telemetry.snapshot().getValue("ownedBufferBytes"))
    }

    @Test
    fun `within-budget coexistence retains old resource when replacement creation fails`() {
        val telemetry = RendererTelemetry()
        telemetry.setOwnedBufferBytes("unrelated", 1)
        val ledger = CoverageRendererAllocationLedger(telemetry)
        val released = mutableListOf<String>()
        val factory = CoverageRendererResourceFactory(
            admit = { mode, _ -> ledger.admitResourceReplacement(mode) },
        )
        factory.replacePoint(
            com.uhg0.ar_flutter_plugin_2.pointcloud.VoxelRenderMode.POINTS,
            1,
            "old",
            create = { _, _, _ -> "old" },
            release = { value: String -> released += value },
        )

        val failed =
            factory.replacePoint(
                com.uhg0.ar_flutter_plugin_2.pointcloud.VoxelRenderMode.POINTS,
                1,
                "new",
                create = { _, _, _ -> throw IllegalStateException("allocation failed") },
                release = { value: String -> released += value },
            )
        assertNull(failed)
        assertEquals(emptyList<String>(), released)
        factory.clear()
        assertEquals(listOf("old"), released)
    }

    @Test
    fun `clear-first resource failure is typed and does not throw through composition`() {
        val events = mutableListOf<String>()
        val factory = CoverageRendererResourceFactory(
            admit = { _, _ ->
                CoverageRendererResourceAdmission(
                    strategy = CoverageRendererTransitionStrategy.CLEAR_FIRST,
                    currentBytes = 8,
                    candidateBytes = 9,
                    combinedBytes = 17,
                )
            },
            onClearFirst = { transition -> events += "clear:${transition.rendererGeneration}" },
            onCreationFailure = { transition -> events += "failed:${transition.rendererGeneration}" },
        )
        factory.replacePoint(
            com.uhg0.ar_flutter_plugin_2.pointcloud.VoxelRenderMode.POINTS,
            1,
            "current",
            rendererGeneration = 4L,
            create = { _, _, _ -> "current" },
            release = { events += "release" },
        )

        val result = factory.replacePoint(
            com.uhg0.ar_flutter_plugin_2.pointcloud.VoxelRenderMode.CENTROIDS,
            1,
            "replacement",
            rendererGeneration = 5L,
            create = { _, _, _ -> throw IllegalStateException("allocation failed") },
            release = { events += "replacement-release" },
        )

        assertNull(result)
        assertEquals(listOf("clear:5", "release", "failed:5"), events)
        factory.clear()
        assertEquals(listOf("clear:5", "release", "failed:5"), events)
    }

    @Test
    fun `renderer lazy mode resource peaks stay within the shared eight MiB cap`() {
        assertEquals(2_000, CoverageRendererLimits.RAW_POINT_CAPACITY)
        assertEquals(20_000, CoverageRendererLimits.CENTROID_CAPACITY)
        assertEquals(8_000, CoverageRendererLimits.CUBE_CAPACITY)
        assertEquals(8_016_704, CoverageRendererLimits.maximumActiveRendererBytes)
        com.uhg0.ar_flutter_plugin_2.pointcloud.VoxelRenderMode.entries.forEach { mode ->
            assertTrue(
                "$mode startup peak must fit the shared renderer cap",
                CoverageRendererLimits.activeRendererPeakBytes(mode) <=
                    CoverageRendererLimits.SHARED_OWNED_BUFFER_LIMIT_BYTES,
            )
        }
    }

    @Test
    fun `lazy ledger charges selector and handoff only after an actual snapshot exists`() {
        val telemetry = RendererTelemetry()
        val ledger = CoverageRendererAllocationLedger(telemetry)
        ledger.installPersistentCoverageState(
            com.uhg0.ar_flutter_plugin_2.pointcloud.VoxelRenderMode.CENTROIDS,
            snapshot = null,
        )
        ledger.updateSnapshotHandoff(
            com.uhg0.ar_flutter_plugin_2.pointcloud.VoxelRenderMode.CENTROIDS,
            snapshot = null,
        )
        assertEquals(0, telemetry.snapshot().getValue("ownedBufferBytes"))

        val snapshot = CoveragePointRenderSnapshot(
            revision = 1L,
            enabled = true,
            capacity = 1,
            count = 1,
            keys = longArrayOf(1L),
            positions = FloatArray(3),
            colors = IntArray(1),
        )
        ledger.installPersistentCoverageState(
            com.uhg0.ar_flutter_plugin_2.pointcloud.VoxelRenderMode.CENTROIDS,
            snapshot,
        )
        ledger.updateSnapshotHandoff(
            com.uhg0.ar_flutter_plugin_2.pointcloud.VoxelRenderMode.CENTROIDS,
            snapshot,
        )
        assertEquals(
            VisibilityGridRendererState.ownedStorageBytes(CoverageRendererLimits.CENTROID_CAPACITY) +
                CoverageRendererLimits.AUXILIARY_BYTES +
                CoverageRendererLimits.SNAPSHOT_ROW_BYTES * 4,
            telemetry.snapshot().getValue("ownedBufferBytes"),
        )
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
            styleRows = complete + complete + uncovered,
        )

        val bounded = snapshot.boundedForPresentation(2)

        assertArrayEquals(longArrayOf(20, 10), bounded.keys)
        assertEquals(
            CoverageRendererCoverage.UNCOVERED,
            CoverageRendererStyleRowV1.decode(bounded.styleRows).coverage,
        )
        assertEquals(
            CoverageRendererCoverage.COMPLETE,
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

    @Test
    fun `retained selector shrinks safely and clears removed source mappings`() {
        val selector = CoveragePresentationSelector(3)
        selector.select(
            CoveragePointRenderSnapshot(
                revision = 1,
                enabled = true,
                capacity = 3,
                count = 3,
                keys = longArrayOf(10, 20, 30),
                positions = FloatArray(9),
                colors = IntArray(3),
            ),
        )

        val bounded = selector.select(
            CoveragePointRenderSnapshot(
                revision = 2,
                enabled = true,
                capacity = 2,
                count = 2,
                keys = longArrayOf(30, 10),
                positions = FloatArray(6),
                colors = IntArray(2),
            ),
        )

        assertEquals(2, bounded.count)
        assertArrayEquals(longArrayOf(10, 30), bounded.keys)
        assertTrue(checkNotNull(bounded.update).reset)
        assertEquals(2, bounded.update.spans.single().colors.size)
    }

    @Test
    fun `retained selector recomputes when an unselected row becomes higher priority`() {
        val selector = CoveragePresentationSelector(2)
        selector.select(
            CoveragePointRenderSnapshot(
                revision = 1,
                enabled = true,
                capacity = 3,
                count = 3,
                keys = longArrayOf(10, 20, 30),
                positions = FloatArray(9),
                colors = IntArray(3),
                styleRows = CoverageRendererStyleRowV1().encode().let { it + it + it },
            ),
        )
        val promoted = CoverageRendererStyleRowV1(
            target = com.uhg0.ar_flutter_plugin_2.pointcloud.CoverageRendererTarget.PRIMARY,
            directionBin = 1,
            glyph = com.uhg0.ar_flutter_plugin_2.pointcloud.CoverageRendererGlyph.DESIRED_DIRECTION,
        ).encode()
        val styles = CoverageRendererStyleRowV1().encode().let { it + it + it }
        promoted.copyInto(styles, 16 * 2)

        val bounded = selector.select(
            CoveragePointRenderSnapshot(
                revision = 2,
                enabled = true,
                capacity = 3,
                count = 3,
                keys = longArrayOf(10, 20, 30),
                positions = FloatArray(9),
                colors = IntArray(3),
                styleRows = styles,
                update = com.uhg0.ar_flutter_plugin_2.pointcloud.CoveragePointRenderUpdate(
                    geometryRevision = 2,
                    visibilityRevision = 2,
                    enabled = true,
                    count = 3,
                    spans = listOf(
                        com.uhg0.ar_flutter_plugin_2.pointcloud.CoveragePointSpan(
                            startSlot = 2,
                            positions = FloatArray(3),
                            colors = intArrayOf(0),
                            styleRows = promoted,
                        ),
                    ),
                    reset = false,
                ),
            ),
        )

        assertArrayEquals(longArrayOf(30, 10), bounded.keys)
        assertTrue(checkNotNull(bounded.update).reset)
    }
}
