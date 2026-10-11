package com.uhg0.ar_flutter_plugin_2.visibilitygrid

import com.uhg0.ar_flutter_plugin_2.pointcloud.*
import com.uhg0.ar_flutter_plugin_2.sceneview.CoveragePresentationMode
import com.uhg0.ar_flutter_plugin_2.visibilityprotocol.*
import org.junit.Assert.*
import org.junit.Test

class SparseRendererStyleCutTest {
    private val ownership = VisibilityObservationOwnership(
        sessionId = "01".repeat(16), sessionGeneration = 1, captureGroupId = "02".repeat(16),
        groupGeneration = 1, coverageEpoch = 1, arSessionIdentity = "03".repeat(16),
        viewInstanceId = "04".repeat(16), viewGeneration = 1, nativeStreamToken = "05".repeat(16),
        workerBindingToken = "06".repeat(16), bindingGeneration = 1, lifecycleSequence = 1,
        operationGeneration = 1, groupFrame = VisibilityGroupFrame.copyOf(
            identityVisibilityGridTransform(), identityVisibilityGridTransform(), 100_000, 512))

    @Test fun `zero semantic sparse command synthesizes full generations age colors and selected rows`() {
        val state = state()
        val initial = CoverageRendererStyleRowV1(semanticGeneration = 1, styleGeneration = 1,
            palette = CoverageRendererPalette.AGE)
        assertTrue(state.applyStyleCut(full(initial)) is RendererStyleCutResult.Applied)
        val before = state.snapshot()
        val held = requireNotNull(state.presentationDescriptor(qualifier(1),
            CoveragePresentationMode.SEMANTIC_CENTROIDS, true, intArrayOf(0, 511)))
        val heldBytes = held.styleRows.copyOf()
        val sparse = sparse(longArrayOf(), ByteArray(0), age = 2)
        assertTrue(state.applyStyleCut(sparse) is RendererStyleCutResult.Applied)
        val expected = initial.copy(semanticGeneration = 2, styleGeneration = 2, age = CoverageRendererAge.AGING)
        val snapshot = state.snapshot()
        for (index in 0 until 512) {
            assertArrayEquals(expected.encode(), snapshot.styleRows.copyOfRange(index * 16, index * 16 + 16))
            assertEquals(expected.packedColor(), snapshot.colors[index])
        }
        assertArrayEquals(heldBytes, held.styleRows)
        assertFalse(held.withPage(qualifier(1), 0, 1) { error("stale page") })
        assertTrue(state.withCommittedRows(qualifier(2)) { rows ->
            val bytes = ByteArray(16)
            rows.copyStyleAt(511, bytes, 0)
            assertArrayEquals(expected.encode(), bytes)
            assertEquals(expected, rows.rowAt(511).style)
            assertEquals(expected.packedColor(), rows.colorAt(511))
            val selector = com.uhg0.ar_flutter_plugin_2.sceneview.CoveragePresentationSelector(2)
            val selected = selector.select(rows, forceReset = true)
            assertTrue(selected.styleRows.isNotEmpty())
            assertEquals(2L, CoverageRendererStyleRowV1.decode(selected.styleRows).styleGeneration)
        })
        assertEquals(RendererStyleCutResult.Replayed(2), state.applyStyleCut(sparse))
        assertArrayEquals(snapshot.styleRows, state.snapshot().styleRows)
        assertEquals(before.count, snapshot.count)

        val unavailable = initial.copy(cut = CoverageRendererCut.UNAVAILABLE)
        val unavailableSparseState = this.state()
        val unavailableFullState = this.state()
        assertTrue(unavailableSparseState.applyStyleCut(full(unavailable)) is RendererStyleCutResult.Applied)
        assertTrue(unavailableFullState.applyStyleCut(full(unavailable)) is RendererStyleCutResult.Applied)
        val unavailableHeld = requireNotNull(unavailableSparseState.presentationDescriptor(qualifier(1),
            CoveragePresentationMode.SEMANTIC_CENTROIDS, true, intArrayOf(0, 511)))
        val unavailableHeldBytes = unavailableHeld.styleRows.copyOf()
        CoverageRendererAge.values().forEachIndexed { index, age ->
            val base = index + 1L
            val revision = base + 1L
            val unavailableExpected = unavailable.copy(semanticGeneration = revision,
                styleGeneration = revision, age = age)
            assertEquals(0, unavailable.packedColor(age))
            assertEquals(0, CoverageRendererStyleRowV1.validatedPackedColor(unavailable.encode(), 0, age.code))
            assertTrue(unavailableSparseState.applyStyleCut(this.sparse(longArrayOf(), ByteArray(0),
                base = base, age = age.code)) is RendererStyleCutResult.Applied)
            assertTrue(unavailableFullState.applyStyleCut(full(unavailableExpected).copy(
                semanticRevision = revision, coverageRevision = revision, styleRevision = revision,
                residencyRevision = revision, targetRevision = revision)) is RendererStyleCutResult.Applied)
            val sparseSnapshot = unavailableSparseState.snapshot()
            val fullSnapshot = unavailableFullState.snapshot()
            assertArrayEquals(fullSnapshot.styleRows, sparseSnapshot.styleRows)
            assertArrayEquals(fullSnapshot.colors, sparseSnapshot.colors)
            for (row in 0 until 512) {
                assertArrayEquals(unavailableExpected.encode(),
                    sparseSnapshot.styleRows.copyOfRange(row * 16, row * 16 + 16))
                assertEquals(0, sparseSnapshot.colors[row])
            }
            assertTrue(unavailableSparseState.withCommittedRows(qualifier(revision)) { rows ->
                val bytes = ByteArray(16)
                rows.copyStyleAt(511, bytes, 0)
                assertArrayEquals(unavailableExpected.encode(), bytes)
                assertEquals(unavailableExpected, rows.rowAt(511).style)
                assertEquals(0, rows.colorAt(511))
                val selector = com.uhg0.ar_flutter_plugin_2.sceneview.CoveragePresentationSelector(2)
                val selected = selector.select(rows, forceReset = true)
                assertTrue(selected.styleRows.isNotEmpty())
                assertEquals(revision, CoverageRendererStyleRowV1.decode(selected.styleRows).styleGeneration)
                assertEquals(age, CoverageRendererStyleRowV1.decode(selected.styleRows).age)
                selected.colors.forEach { assertEquals(0, it) }
            })
        }
        assertArrayEquals(unavailableHeldBytes, unavailableHeld.styleRows)
        assertFalse(unavailableHeld.withPage(qualifier(1), 0, 1) { error("stale unavailable page") })
    }

    @Test fun `sparse target set clear and bounded caller mutation preserve exact accepted rows`() {
        val state = state()
        assertTrue(state.applyStyleCut(full(CoverageRendererStyleRowV1(semanticGeneration = 1, styleGeneration = 1))) is RendererStyleCutResult.Applied)
        val target = CoverageRendererStyleRowV1(semanticGeneration = 2, styleGeneration = 2,
            target = CoverageRendererTarget.PRIMARY, directionBin = 4, glyph = CoverageRendererGlyph.DESIRED_DIRECTION)
        val command = sparse(longArrayOf(8), target.encode(), targetId = 8)
        assertTrue(state.applyStyleCut(command) is RendererStyleCutResult.Applied)
        val accepted = state.snapshot()
        val replay = command.copy(surfaceIds = command.surfaceIds.copyOf(), styleRows = command.styleRows.copyOf())
        command.styleRows.fill(0)
        assertEquals(RendererStyleCutResult.Replayed(2), state.applyStyleCut(replay))
        assertTrue(state.applyStyleCut(command) is RendererStyleCutResult.Rejected)
        assertArrayEquals(accepted.styleRows, state.snapshot().styleRows)
        val cleared = CoverageRendererStyleRowV1(semanticGeneration = 3, styleGeneration = 3)
        assertTrue(state.applyStyleCut(sparse(longArrayOf(8), cleared.encode(), base = 2)) is RendererStyleCutResult.Applied)
        assertNull(state.currentTargetSurfaceId)
        assertEquals(CoverageRendererTarget.NONE, CoverageRendererStyleRowV1.decode(state.snapshot().styleRows, 7 * 16).target)
    }

    @Test fun `unknown rows stale bases replaced ownership and structural change reject before mutation`() {
        val state = state()
        assertTrue(state.applyStyleCut(full(CoverageRendererStyleRowV1(semanticGeneration = 1, styleGeneration = 1))) is RendererStyleCutResult.Applied)
        val before = state.snapshot()
        val unknown = sparse(longArrayOf(999), CoverageRendererStyleRowV1(semanticGeneration = 2, styleGeneration = 2).encode())
        assertTrue(state.applyStyleCut(unknown) is RendererStyleCutResult.Rejected)
        assertTrue(state.applyStyleCut(sparse(longArrayOf(), ByteArray(0), base = 7)) is RendererStyleCutResult.Rejected)
        assertTrue(state.applyStyleCut(sparse(longArrayOf(), ByteArray(0)).copy(
            ownership = ownership.copy(operationGeneration = 2))) is RendererStyleCutResult.Rejected)
        assertArrayEquals(before.styleRows, state.snapshot().styleRows)
        val zero = sparse(longArrayOf(), ByteArray(0), age = 1)
        assertTrue(state.applyStyleCut(zero) is RendererStyleCutResult.Applied)
        assertTrue(state.applyGeometry(8, false, listOf(CanonicalRenderRow(1, packVisibilityGridKey(8, 0, 0), 0, 0, 0)),
            longArrayOf(), ownership, 10, 3))
        assertTrue(state.applyStyleCut(zero) is RendererStyleCutResult.Rejected)
        val structural = state.snapshot()
        assertEquals(0L, CoverageRendererStyleRowV1.decode(structural.styleRows).styleGeneration)
        val unchanged = CoverageRendererStyleRowV1.decode(structural.styleRows, 16)
        assertEquals(2L, unchanged.styleGeneration)
        assertEquals(CoverageRendererAge.RECENT, unchanged.age)
    }

    private fun state() = VisibilityGridRendererState(512).also { state ->
        state.startCanonicalGroup(VisibilityGridGroupConfig(groupId = "02".repeat(16), groupGeneration = 1, sessionGeneration = 1,
            voxelSizeMeters = .1, capacity = 512, groupFromWorldGl = identityVisibilityGridTransform(),
            worldFromGroupGl = identityVisibilityGridTransform(), restoredKeys = longArrayOf()), 7,
            List(512) { CanonicalRenderRow(it + 1L, packVisibilityGridKey(it, 0, 0), 0, 0, 0) }, ownership, 9, 3)
    }
    private fun full(row: CoverageRendererStyleRowV1) = QualifiedRendererStyleCut(ownership, 9, 7, 3,
        1, 1, 1, 1, 1, true, LongArray(512) { it + 1L }, ByteArray(512 * 16).also {
            bytes -> for (index in 0 until 512) row.encodeInto(bytes, index * 16)
        }, null, null)
    private fun sparse(ids: LongArray, rows: ByteArray, base: Long = 1, age: Int = 0, targetId: Long? = null): QualifiedRendererStyleCut {
        val cut = sparseFixture(ids, 512, 9, 7, 3, base, age, ByteArray(16) { 2 }, rows)
            .copy(targetSurfaceId = targetId, targetDirectionIndex = targetId?.let { 4 })
        val decoded = SparseRendererStyleCommandV1.decode(SparseRendererStyleCommandV1.encode(cut))
        return QualifiedRendererStyleCut(ownership, 9, 7, 3, base + 1, base + 1, base + 1,
            base + 1, base + 1, true, decoded.surfaceIds, decoded.styleRows,
            targetId, targetId?.let { 4 }, decoded.sparseContext)
    }
    private fun qualifier(style: Long) = CoverageRowsQualifier(1, 1, 0, 9, 7, style)
}
