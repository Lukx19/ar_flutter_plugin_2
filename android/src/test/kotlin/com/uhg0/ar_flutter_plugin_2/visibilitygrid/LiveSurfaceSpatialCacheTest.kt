package com.uhg0.ar_flutter_plugin_2.visibilitygrid

import org.junit.Assert.*
import org.junit.Test

class LiveSurfaceSpatialCacheTest {
    private val camera = VisibilityCameraIntrinsics(640, 480, 500.0, 500.0, 320.0, 240.0)
    private fun pose(x: Double = 0.0, reverse: Boolean = false): List<Double> = listOf(
        if (reverse) -1.0 else 1.0, 0.0, 0.0, 0.0,
        0.0, 1.0, 0.0, 0.0,
        0.0, 0.0, if (reverse) -1.0 else 1.0, 0.0,
        x, 0.0, 0.0, 1.0,
    )

    @Test fun `rotation flushes cold dirty blocks and loads the opposite hemisphere`() {
        val cache = LiveSurfaceSpatialCache(20, 100_000)
        cache.upsert(SurfaceId(1), Voxel(0, 0, -30))
        cache.upsert(SurfaceId(2), Voxel(0, 0, 30))
        assertTrue(cache.updateWindow(pose(), camera, 8_000.0))
        val initial = mutableListOf<Long>()
        cache.visitSelected(initial::add)
        assertEquals(listOf(1L), initial)
        val flushed = mutableListOf<Long>()
        cache.flushCold { _, rows -> rows(flushed::add); true }
        assertEquals(listOf(2L), flushed)
        assertTrue(cache.updateWindow(pose(reverse = true), camera, 8_000.0))
        val rotated = mutableListOf<Long>()
        cache.visitSelected(rotated::add)
        assertEquals(listOf(2L), rotated)
        cache.flushCold { _, rows -> rows(flushed::add); true }
        assertEquals(listOf(2L, 1L), flushed)
    }

    @Test fun `small movements reuse candidates and failed flush remains retryable`() {
        val cache = LiveSurfaceSpatialCache(20, 100_000)
        cache.upsert(SurfaceId(1), Voxel(0, 0, -30))
        cache.upsert(SurfaceId(2), Voxel(0, 0, 30))
        assertTrue(cache.updateWindow(pose(), camera, 8_000.0))
        val refreshes = cache.refreshCount
        repeat(100) { assertTrue(cache.updateWindow(pose(0.05), camera, 8_000.0)) }
        assertEquals(refreshes, cache.refreshCount)
        cache.flushCold { _, _ -> false }
        assertEquals(0L, cache.flushedBlocks)
        cache.flushCold { _, _ -> true }
        assertEquals(1L, cache.flushedBlocks)
        assertTrue(cache.updateWindow(pose(0.3), camera, 8_000.0))
        assertEquals(refreshes + 1, cache.refreshCount)
    }

    @Test fun `refinement and removal update block chains without leaving duplicate identities`() {
        val cache = LiveSurfaceSpatialCache(20, 100_000)
        cache.upsert(SurfaceId(1), Voxel(0, 0, -30))
        cache.upsert(SurfaceId(2), Voxel(1, 0, -30))
        cache.upsert(SurfaceId(1), Voxel(-40, 0, -30))
        cache.remove(SurfaceId(2))
        assertTrue(cache.updateWindow(pose(), camera, 8_000.0))
        val selected = mutableListOf<Long>()
        cache.visitSelected(selected::add)
        assertEquals(listOf(1L), selected)
        cache.remove(SurfaceId(1))
        assertTrue(cache.updateWindow(pose(), camera, 8_000.0))
        assertEquals(0, cache.selectedCount)
    }

    @Test fun `spatial probe count is independent of surfaces beyond the camera range`() {
        val cache = LiveSurfaceSpatialCache(10_000, 100_000)
        cache.upsert(SurfaceId(1), Voxel(-1, -1, -30))
        assertTrue(cache.updateWindow(pose(), camera, 8_000.0))
        val probes = cache.lastBlockProbes
        for (i in 2..10_000) cache.upsert(SurfaceId(i.toLong()), Voxel(i * 16, 0, 0))
        assertTrue(cache.updateWindow(pose(), camera, 8_000.0))
        assertEquals(probes, cache.lastBlockProbes)
        assertEquals(1, cache.selectedCount)
        assertTrue(cache.contains(Voxel(-1, -1, -30)))
        assertFalse(cache.contains(Voxel(0, 0, 100)))
    }

    @Test fun `invalid camera matrices fail closed and asymmetric frustum retains edge voxels`() {
        val frustum = ExpandedSurfaceFrustum()
        val asymmetric = VisibilityCameraIntrinsics(640, 480, 500.0, 500.0, 100.0, 100.0)
        assertTrue(frustum.update(pose(), asymmetric, 8_000.0))
        // Right optical edge at x = 540 / 500 * 4m, with off-centre y principal point.
        assertTrue(frustum.intersects(4_300.0, 0.0, -4_000.0, 100.0))
        assertFalse(frustum.intersects(-10_000.0, 0.0, -4_000.0, 100.0))
        assertFalse(frustum.update(pose().map { it * 2 }, camera, 8_000.0))
        assertFalse(frustum.intersects(0.0, 0.0, -1_000.0, 100.0))
    }

    @Test fun `full cache reuses vacated block buckets during repeated row movement`() {
        val cache = LiveSurfaceSpatialCache(100_000, 100_000)
        assertTrue(cache.retainedPrimitiveBytes < 7L * 1024 * 1024)
        repeat(100_000) { i -> cache.upsert(SurfaceId(i + 1L), Voxel(i * 16, 0, 0)) }
        // Deliberately move every row before refreshing the window. Old empty
        // buckets must not accumulate and exhaust the smaller primitive table.
        repeat(3) { pass ->
            repeat(100_000) { i ->
                cache.upsert(SurfaceId(i + 1L), Voxel(i * 16, pass + 1, -16 * (pass + 1)))
            }
        }
        for (i in 0 until 100_000 step 3) {
            cache.remove(SurfaceId(i + 1L))
            cache.upsert(SurfaceId(0xffffffffL - i), Voxel(i * 16, 3, -48))
        }
        assertTrue(cache.updateWindow(pose(), camera, 8_000.0))
        assertTrue(cache.selectedCount > 0)
        assertTrue(cache.contains(Voxel(0, 3, -48)))
        val selected = mutableListOf<Long>()
        cache.visitSelected(selected::add)
        assertTrue(selected.contains(0xffffffffL))
        assertFalse(selected.contains(1L))
    }
}
