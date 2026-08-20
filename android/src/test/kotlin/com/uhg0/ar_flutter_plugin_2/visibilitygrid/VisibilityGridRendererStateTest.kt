package com.uhg0.ar_flutter_plugin_2.visibilitygrid

import com.uhg0.ar_flutter_plugin_2.pointcloud.VoxelRenderMode
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VisibilityGridRendererStateTest {
    @Test
    fun `removal compacts both modes and immediately reuses capacity`() {
        val state = VisibilityGridRendererState(capacity = 2)
        state.startGroup(group(2), geometryRevision = 0, restoredKeys = longArrayOf())
        val first = packVisibilityGridKey(0, 0, 0)
        val second = packVisibilityGridKey(1, 0, 0)
        val third = packVisibilityGridKey(2, 0, 0)

        assertTrue(state.applyGeometry(1, false, longArrayOf(first, second), longArrayOf()))
        assertTrue(state.applyGeometry(2, false, longArrayOf(third), longArrayOf(first)))

        val points = state.snapshot()
        assertEquals(2, points.count)
        assertArrayEquals(longArrayOf(second, third), points.keys.sortedArray())
        assertEquals(0, state.freeRowCount)
        state.setRenderMode(VoxelRenderMode.CUBES)
        assertEquals(2, state.snapshot().count)
    }

    @Test
    fun `long churn restores every renderer row without leaking state`() {
        val state = VisibilityGridRendererState(capacity = 8)
        state.startGroup(group(8), geometryRevision = 0, restoredKeys = longArrayOf())
        var revision = 0L
        repeat(2_000) { cycle ->
            val key = packVisibilityGridKey(cycle, 0, 0)
            assertTrue(
                state.applyGeometry(++revision, false, longArrayOf(key), longArrayOf()),
            )
            assertTrue(
                state.applyGeometry(++revision, false, longArrayOf(), longArrayOf(key)),
            )
        }

        assertEquals(0, state.snapshot().count)
        assertEquals(8, state.freeRowCount)
        assertEquals(8, state.capacity)
    }

    @Test
    fun `bounded presentation selection keeps semantic capacity separate and replaces by identity`() {
        val state = VisibilityGridRendererState(capacity = 2)
        state.startGroup(group(100_000), geometryRevision = 0, restoredKeys = longArrayOf())
        val first = packVisibilityGridKey(1, 0, 0)
        val second = packVisibilityGridKey(2, 0, 0)
        val third = packVisibilityGridKey(3, 0, 0)

        assertTrue(state.applyGeometry(1, false, longArrayOf(second, third), longArrayOf()))
        assertTrue(state.applyGeometry(2, false, longArrayOf(first), longArrayOf()))
        assertArrayEquals(longArrayOf(first, second), state.snapshot().keys.sortedArray())

        // A selected removal asks the authoritative grid for only its next
        // bounded presentation set, preserving the semantic 100k capacity.
        assertTrue(
            state.applyGeometry(
                3,
                false,
                longArrayOf(),
                longArrayOf(first),
                selectedKeysForResetOrReplacement = { longArrayOf(second, third) },
            ),
        )
        assertArrayEquals(longArrayOf(second, third), state.snapshot().keys.sortedArray())
        assertEquals(100_000, group(100_000).capacity)
        assertEquals(0, state.freeRowCount)
    }

    @Test
    fun `visibility patches are revision exact atomic and geometry only`() {
        val state = VisibilityGridRendererState(capacity = 2)
        val key = packVisibilityGridKey(0, 0, 0)
        state.startGroup(group(2), geometryRevision = 4, restoredKeys = longArrayOf(key))
        val green = 0xFF00FF00.toInt()

        assertFalse(state.applyVisibility(3, 1, longArrayOf(key), intArrayOf(green)))
        assertTrue(
            state.applyVisibility(
                4,
                1,
                longArrayOf(key, packVisibilityGridKey(1, 0, 0)),
                intArrayOf(green, green),
            ),
        )
        assertEquals(1, state.ignoredDeletedVisibilityKeys)
        assertEquals(green, state.snapshot().colors.single())
        assertFalse(state.applyVisibility(4, 1, longArrayOf(key), intArrayOf(0)))
    }

    @Test
    fun `restored baseline has no session identity and remains removable`() {
        val key = packVisibilityGridKey(-2, 3, -4)
        val state = VisibilityGridRendererState(capacity = 1)
        state.startGroup(
            group(1),
            geometryRevision = 7,
            visibilityRevision = 11,
            restoredKeys = longArrayOf(key),
        )

        assertArrayEquals(longArrayOf(key), state.snapshot().keys)
        assertEquals(11, state.currentVisibilityRevision)
        assertFalse(state.applyVisibility(7, 11, longArrayOf(key), intArrayOf(0)))
        assertTrue(state.applyVisibility(7, 12, longArrayOf(key), intArrayOf(0)))
        assertTrue(state.applyGeometry(8, false, longArrayOf(), longArrayOf(key)))
        assertEquals(1, state.freeRowCount)
        state.dispose()
        state.dispose()
        assertTrue(state.isDisposed)
    }

    @Test
    fun `failed upload forces one full retry without changing model revisions`() {
        val key = packVisibilityGridKey(1, 2, 3)
        val state = VisibilityGridRendererState(capacity = 1)
        state.startGroup(group(1), geometryRevision = 0, restoredKeys = longArrayOf())
        assertTrue(state.applyGeometry(1, false, longArrayOf(key), longArrayOf()))
        assertTrue(state.snapshot().update!!.reset)

        state.markUploadFailed()
        val retry = state.snapshot()

        val update = checkNotNull(retry.update)
        assertTrue(update.reset)
        assertEquals(1, update.spans.single().positions.size / 3)
        assertEquals(1, state.currentGeometryRevision)
        assertEquals(0, state.currentVisibilityRevision)
    }

    @Test
    fun `repeated group lifecycle clears callbacks revisions and renderer capacity`() {
        val state = VisibilityGridRendererState(capacity = 4)

        repeat(25) { generation ->
            val keys =
                LongArray(4) { offset ->
                    packVisibilityGridKey(generation * 4 + offset, 0, 0)
                }
            state.startGroup(
                group(4),
                geometryRevision = generation.toLong(),
                visibilityRevision = generation.toLong(),
                restoredKeys = keys,
            )
            state.setRenderMode(
                if (generation % 2 == 0) VoxelRenderMode.CENTROIDS else VoxelRenderMode.CUBES,
            )
            state.setEnabled(generation % 3 != 0)
            assertEquals(0, state.freeRowCount)
            assertEquals(4, state.snapshot().count)

            state.stopGroup()
            state.stopGroup()
            assertEquals(4, state.freeRowCount)
            assertEquals(0, state.snapshot().count)
            assertEquals(0, state.currentGeometryRevision)
            assertEquals(0, state.currentVisibilityRevision)
        }

        state.dispose()
        state.dispose()
        assertTrue(state.isDisposed)
    }

    private fun group(capacity: Int) =
        VisibilityGridGroupConfig(
            groupId = "group",
            groupGeneration = 1,
            sessionGeneration = 2,
            voxelSizeMeters = 0.1,
            capacity = capacity,
            groupFromWorldGl = identityVisibilityGridTransform(),
            worldFromGroupGl = identityVisibilityGridTransform(),
            restoredGeometryRevision = 0,
            restoredKeys = longArrayOf(),
        )
}
