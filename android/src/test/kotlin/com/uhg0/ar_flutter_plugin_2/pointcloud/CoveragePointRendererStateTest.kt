package com.uhg0.ar_flutter_plugin_2.pointcloud

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class CoveragePointRendererStateTest {
    @Test
    fun `raw ARCore samples become bounded red point snapshots`() {
        val snapshot = PointCloudSample(
            sequence = 7,
            timestampNs = 9,
            ids = intArrayOf(41, 42, 43),
            points = floatArrayOf(
                1f, 2f, 3f, 0.9f,
                4f, 5f, 6f, 0.8f,
                7f, 8f, 9f, 0.7f,
            ),
        ).toRawPointRenderSnapshot(
            capacity = 2,
            color = 0xFFFF0000.toInt(),
            enabled = true,
        )

        assertEquals(7, snapshot.revision)
        assertEquals(2, snapshot.count)
        assertArrayEquals(longArrayOf(41, 42), snapshot.keys)
        assertArrayEquals(floatArrayOf(1f, 2f, 3f, 4f, 5f, 6f), snapshot.positions, 0f)
        assertArrayEquals(
            intArrayOf(0xFFFF0000.toInt(), 0xFFFF0000.toInt()),
            snapshot.colors,
        )
    }

    @Test
    fun `raw points and both visibility grid modes select exclusive layers`() {
        assertEquals(
            CoverageVisualizationLayers(
                rawPointCloud = true,
                visibilityGridCentroids = false,
                visibilityGridCubes = false,
            ),
            config(capacity = 2).copy(voxelRenderMode = VoxelRenderMode.POINTS)
                .visualizationLayers(),
        )
        assertEquals(
            CoverageVisualizationLayers(
                rawPointCloud = false,
                visibilityGridCentroids = true,
                visibilityGridCubes = false,
            ),
            config(capacity = 2).copy(voxelRenderMode = VoxelRenderMode.CENTROIDS)
                .visualizationLayers(),
        )
        assertEquals(
            CoverageVisualizationLayers(
                rawPointCloud = false,
                visibilityGridCentroids = false,
                visibilityGridCubes = true,
            ),
            config(capacity = 2).copy(voxelRenderMode = VoxelRenderMode.CUBES)
                .visualizationLayers(),
        )
        assertEquals(
            CoverageVisualizationLayers(
                rawPointCloud = false,
                visibilityGridCentroids = false,
                visibilityGridCubes = false,
            ),
            config(capacity = 2).copy(enabled = false).visualizationLayers(),
        )
    }

    @Test
    fun `voxel keys upsert stable centroid rows without expiry`() {
        val state = CoveragePointRendererState(config(capacity = 2))
        assertTrue(state.updateVoxels(1, longArrayOf(11), positions(1f), colors(1)))
        assertTrue(state.updateVoxels(2, longArrayOf(11, 22), positions(2f, 3f), colors(2)))

        val snapshot = state.snapshot()
        assertEquals(2, snapshot.count)
        assertArrayEquals(longArrayOf(11, 22), snapshot.keys)
        assertArrayEquals(floatArrayOf(2f, 1f, -1f, 3f, 1f, -1f), snapshot.positions, 0f)
        assertEquals(2, state.stats().emittedFrames)
    }

    @Test
    fun `visibility grid rotation is retained with voxel snapshots`() {
        val state = CoveragePointRendererState(config(capacity = 1))
        val rotation = floatArrayOf(
            0f, 1f, 0f,
            -1f, 0f, 0f,
            0f, 0f, 1f,
        )

        state.updateVoxels(
            1,
            longArrayOf(11),
            positions(1f),
            colors(1),
            rotation,
        )

        assertArrayEquals(rotation, state.snapshot().gridRotationWorld, 0f)
    }

    @Test
    fun `capacity never overwrites accumulated voxels`() {
        val state = CoveragePointRendererState(config(capacity = 1))
        state.updateVoxels(1, longArrayOf(11), positions(1f), colors(1))
        state.updateVoxels(2, longArrayOf(22), positions(2f), colors(1))
        assertArrayEquals(longArrayOf(11), state.snapshot().keys)
    }

    @Test
    fun `newer patch recolors by voxel key and stale epoch is ignored`() {
        val state = CoveragePointRendererState(config(capacity = 3))
        state.updateVoxels(4, longArrayOf(2), positions(1f), intArrayOf(0xFFFF0000.toInt()))
        assertFalse(state.updateVoxels(3, longArrayOf(2), positions(9f), intArrayOf(0)))
        state.updateVoxels(5, longArrayOf(2), positions(1f), intArrayOf(0xFF00FF00.toInt()))
        assertEquals(0xFF00FF00.toInt(), state.snapshot().colors.single())
        assertEquals(5, state.stats().lastAppliedColorEpoch)
    }

    @Test
    fun `existing voxel recolor is included in the next dirty upload`() {
        val state = CoveragePointRendererState(config(capacity = 2))
        state.updateVoxels(1, longArrayOf(11), positions(1f), intArrayOf(0xFFFF0000.toInt()))
        val first = state.snapshotIfChanged(-1, force = true)!!
        assertEquals(0, first.update!!.spans.single().startSlot)

        state.updateVoxels(2, longArrayOf(11), positions(1f), intArrayOf(0xFF00FF00.toInt()))
        val recolor = state.snapshotIfChanged(first.revision)!!
        val update = recolor.update!!
        assertEquals(1, update.spans.size)
        assertEquals(0, update.spans.single().startSlot)
        assertEquals(0xFF00FF00.toInt(), update.spans.single().colors.single())
    }

    @Test
    fun `snapshot is immutable and clear dispose are idempotent`() {
        val state = CoveragePointRendererState(config(capacity = 2))
        state.updateVoxels(9, longArrayOf(1), positions(1f), colors(1))
        state.recordCoalescedFrame()
        val snapshot = state.snapshot()
        snapshot.keys[0] = 99
        assertArrayEquals(longArrayOf(1), state.snapshot().keys)
        state.clear()
        state.clear()
        assertEquals(0, state.snapshot().count)
        assertEquals(0, state.stats().lastAppliedColorEpoch)
        assertEquals(0, state.stats().emittedFrames)
        assertEquals(0, state.stats().coalescedFrames)
        state.dispose()
        state.dispose()
        assertTrue(state.isDisposed())
        try {
            state.snapshot()
            fail("snapshot after dispose must fail")
        } catch (_: IllegalStateException) {
            // Expected.
        }
    }

    @Test
    fun `unchanged render frames do not allocate a new snapshot`() {
        val state = CoveragePointRendererState(config(capacity = 4))
        state.updateVoxels(1, longArrayOf(1), positions(1f), colors(1))
        val first = state.snapshot()
        assertNull(state.snapshotIfChanged(first.revision))
        state.setEnabled(false)
        assertEquals(false, state.snapshotIfChanged(first.revision)!!.enabled)
    }

    @Test
    fun `render mode changes invalidate the snapshot`() {
        val state = CoveragePointRendererState(config(capacity = 2))
        val first = state.snapshot()
        state.setRenderMode(VoxelRenderMode.CUBES)
        assertTrue(state.snapshotIfChanged(first.revision) != null)
    }

    @Test
    fun `stats pin native buffer budget`() {
        val state = CoveragePointRendererState(config(capacity = 4096))
        val stats = state.stats(fps = 60.0)
        assertEquals(4096 * 28, stats.bufferBytes)
        assertEquals(60.0, stats.fps, 0.0)
    }

    private fun config(capacity: Int) = PointCloudNativeConfig(renderCapacity = capacity)

    private fun positions(vararg x: Float): FloatArray = FloatArray(x.size * 3).also { values ->
        x.indices.forEach { index ->
            values[index * 3] = x[index]
            values[index * 3 + 1] = 1f
            values[index * 3 + 2] = -1f
        }
    }

    private fun colors(count: Int) = IntArray(count) { 0xFFFF0000.toInt() }
}
