package com.uhg0.ar_flutter_plugin_2.visibilitygrid

import com.uhg0.ar_flutter_plugin_2.pointcloud.COVERAGE_RENDERER_STYLE_ROW_BYTES
import com.uhg0.ar_flutter_plugin_2.pointcloud.CoverageRendererCoverage
import com.uhg0.ar_flutter_plugin_2.pointcloud.CoverageRendererCut
import com.uhg0.ar_flutter_plugin_2.pointcloud.CoverageRendererGlyph
import com.uhg0.ar_flutter_plugin_2.pointcloud.CoverageRendererPalette
import com.uhg0.ar_flutter_plugin_2.pointcloud.CoverageRendererResidency
import com.uhg0.ar_flutter_plugin_2.pointcloud.CoverageRendererStyleRowV1
import com.uhg0.ar_flutter_plugin_2.pointcloud.VoxelRenderMode
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VisibilityGridRendererStateTest {
    @Test
    fun `renderer state exposes its complete primitive storage ownership`() {
        val centroid = VisibilityGridRendererState(
            VisibilityGridRendererState.CENTROID_PRESENTATION_CAPACITY,
        )
        val cubes = VisibilityGridRendererState(8_000)

        // 20k rows (800,000), 65,536-slot key index (851,968), heap
        // (320,000), and dirty queue (100,000). These are retained arrays,
        // not a ledger estimate that may omit a second selector/state.
        assertEquals(2_071_968, centroid.ownedStorageBytes)
        assertEquals(
            8_000 * 40 +
                LongRowIndex.ownedStorageBytes(8_000) +
                SelectedKeyMaxHeap.ownedStorageBytes(8_000) +
                DirtyRowQueue.ownedStorageBytes(8_000),
            cubes.ownedStorageBytes,
        )
    }

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
    fun `visibility patches carry bounded style cuts and reject stale generations`() {
        val state = VisibilityGridRendererState(capacity = 2)
        val key = packVisibilityGridKey(0, 0, 0)
        state.startGroup(group(2), geometryRevision = 4, restoredKeys = longArrayOf(key))
        val complete = CoverageRendererStyleRowV1(
            semanticGeneration = 8,
            styleGeneration = 3,
            coverage = CoverageRendererCoverage.COMPLETE,
            palette = CoverageRendererPalette.COVERAGE,
        )

        assertFalse(state.applyVisibility(3, 1, longArrayOf(key), styles(complete)))
        assertTrue(
            state.applyVisibility(
                4,
                1,
                longArrayOf(key, packVisibilityGridKey(1, 0, 0)),
                styles(complete, complete),
            ),
        )
        assertEquals(1, state.ignoredDeletedVisibilityKeys)
        val snapshot = state.snapshot()
        assertEquals(0xFF00C853.toInt(), snapshot.colors.single())
        assertEquals(complete, CoverageRendererStyleRowV1.decode(snapshot.styleRows))
        assertFalse(state.applyVisibility(4, 1, longArrayOf(key), styles(complete)))
        assertFalse(
            state.applyVisibility(
                4,
                2,
                longArrayOf(key),
                styles(complete.copy(semanticGeneration = 7, styleGeneration = 4)),
            ),
        )
        assertFalse(
            state.applyVisibility(
                4,
                2,
                longArrayOf(key),
                styles(complete.copy(styleGeneration = 2)),
            ),
        )
    }

    @Test
    fun `renderer rejects mixed geometry semantic style and residency cuts`() {
        val state = VisibilityGridRendererState(capacity = 2)
        val first = packVisibilityGridKey(0, 0, 0)
        val second = packVisibilityGridKey(1, 0, 0)
        state.startGroup(group(2), geometryRevision = 4, restoredKeys = longArrayOf(first, second))
        val current = CoverageRendererStyleRowV1(
            semanticGeneration = 8,
            styleGeneration = 3,
            residency = CoverageRendererResidency.ACTIVE_L0,
        )

        assertFalse(state.applyVisibility(3, 1, longArrayOf(first), styles(current)))
        assertFalse(
            state.applyVisibility(
                4,
                1,
                longArrayOf(first, second),
                styles(current, current.copy(residency = CoverageRendererResidency.WARM_L1, styleGeneration = 4)),
            ),
        )
        assertTrue(
            state.applyVisibility(
                4,
                1,
                longArrayOf(first, second),
                styles(current, current.copy(residency = CoverageRendererResidency.WARM_L1)),
            ),
        )
    }

    @Test
    fun `pending indeterminate direction and unavailable cuts reach production spans`() {
        val state = VisibilityGridRendererState(capacity = 4)
        val keys = LongArray(4) { packVisibilityGridKey(it, 0, 0) }
        state.startGroup(group(4), geometryRevision = 1, restoredKeys = keys)
        val rows = arrayOf(
            CoverageRendererStyleRowV1(
                semanticGeneration = 1,
                styleGeneration = 1,
                cut = CoverageRendererCut.COVERAGE_PENDING,
            ),
            CoverageRendererStyleRowV1(
                semanticGeneration = 1,
                styleGeneration = 1,
                cut = CoverageRendererCut.INDETERMINATE_HISTORY,
            ),
            CoverageRendererStyleRowV1(
                semanticGeneration = 1,
                styleGeneration = 1,
                palette = CoverageRendererPalette.DIRECTION,
                directionBin = 23,
                glyph = CoverageRendererGlyph.DESIRED_DIRECTION,
            ),
            CoverageRendererStyleRowV1(
                semanticGeneration = 1,
                styleGeneration = 1,
                cut = CoverageRendererCut.UNAVAILABLE,
            ),
        )

        assertTrue(state.applyVisibility(1, 1, keys, styles(*rows)))
        val snapshot = state.snapshot()
        assertEquals(keys.size * COVERAGE_RENDERER_STYLE_ROW_BYTES, snapshot.styleRows.size)
        assertArrayEquals(
            intArrayOf(
                0xFFFFA000.toInt(),
                0xFF616161.toInt(),
                0xFF26A69A.toInt(),
                0x00000000,
            ),
            snapshot.colors,
        )
        assertArrayEquals(snapshot.styleRows, snapshot.update!!.spans.single().styleRows)
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
        val baseline = CoverageRendererStyleRowV1()
        assertFalse(state.applyVisibility(7, 11, longArrayOf(key), styles(baseline)))
        assertTrue(state.applyVisibility(7, 12, longArrayOf(key), styles(baseline)))
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
    fun `mode-sized replacement retains the lowest rows and their style cut`() {
        val keys = longArrayOf(
            packVisibilityGridKey(1, 0, 0),
            packVisibilityGridKey(3, 0, 0),
            packVisibilityGridKey(5, 0, 0),
            packVisibilityGridKey(7, 0, 0),
        )
        val centroid = VisibilityGridRendererState(capacity = 4)
        centroid.startGroup(group(4), geometryRevision = 3, restoredKeys = keys)
        val complete = CoverageRendererStyleRowV1(
            coverage = CoverageRendererCoverage.COMPLETE,
        )
        assertTrue(centroid.applyVisibility(3, 1, keys, styles(complete, complete, complete, complete)))
        val retained = centroid.snapshot()

        val cubes = VisibilityGridRendererState(capacity = 2)
        cubes.rehydrate(group(4), retained)
        val rehydrated = cubes.snapshot()

        assertArrayEquals(keys.copyOf(2), rehydrated.keys)
        assertEquals(2, rehydrated.count)
        assertTrue(rehydrated.update!!.reset)
        assertEquals(1, cubes.currentVisibilityRevision)
        assertEquals(
            CoverageRendererCoverage.COMPLETE,
            CoverageRendererStyleRowV1.decode(rehydrated.styleRows).coverage,
        )
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

    private fun styles(vararg rows: CoverageRendererStyleRowV1): ByteArray =
        rows.fold(ByteArray(0)) { bytes, row -> bytes + row.encode() }
}
