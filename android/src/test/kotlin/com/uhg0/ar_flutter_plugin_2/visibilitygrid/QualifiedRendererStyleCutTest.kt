package com.uhg0.ar_flutter_plugin_2.visibilitygrid

import com.uhg0.ar_flutter_plugin_2.pointcloud.COVERAGE_RENDERER_STYLE_ROW_BYTES
import com.uhg0.ar_flutter_plugin_2.pointcloud.CoverageRendererCoverage
import com.uhg0.ar_flutter_plugin_2.pointcloud.CoverageRendererGlyph
import com.uhg0.ar_flutter_plugin_2.pointcloud.CoverageRendererStyleRowV1
import com.uhg0.ar_flutter_plugin_2.pointcloud.CoverageRendererTarget
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class QualifiedRendererStyleCutTest {
    private val activeOwnership = ownership()

    @Test
    fun `accepted cut changes styles and exact replay is a no-op`() {
        val state = stateWithRows()
        state.snapshot() // consume the geometry upload before the style cut
        val cut = cut(
            reset = true,
            ids = longArrayOf(1, 2),
            styleValues = arrayOf(
                style(coverage = CoverageRendererCoverage.PARTIAL, target = CoverageRendererTarget.PRIMARY),
                style(coverage = CoverageRendererCoverage.COMPLETE),
            ),
            targetSurfaceId = 1,
            targetDirectionIndex = 4,
        )

        val applied = state.applyStyleCut(cut)
        assertEquals(
            RendererStyleCutResult.Applied(1, 1, 2, 1),
            applied,
        )
        assertEquals(1, state.currentStyleRevision)
        assertEquals(1L, state.currentTargetSurfaceId)
        assertEquals(4, state.currentTargetDirectionIndex)

        val replay = state.applyStyleCut(cut)
        assertEquals(RendererStyleCutResult.Replayed(1), replay)
        assertEquals(1, state.currentStyleRevision)
        val snapshot = state.snapshot()
        assertEquals(0xffffab00.toInt(), snapshot.colors[0])
        assertEquals(0xff00c853.toInt(), snapshot.colors[1])
    }

    @Test
    fun `malformed or stale cut is rejected before any row or revision mutation`() {
        val state = stateWithRows()
        state.snapshot()
        val accepted = cut(
            reset = true,
            ids = longArrayOf(1, 2),
            styleValues = arrayOf(style(), style()),
        )
        assertTrue(state.applyStyleCut(accepted) is RendererStyleCutResult.Applied)
        val before = state.snapshot()

        val malformed = accepted.copy(
            semanticRevision = 2,
            coverageRevision = 2,
            styleRevision = 2,
            residencyRevision = 2,
            targetRevision = 2,
            styleRows = ByteArray(COVERAGE_RENDERER_STYLE_ROW_BYTES),
        )
        val rejected = state.applyStyleCut(malformed)
        assertEquals(
            RendererStyleCutRejection.MALFORMED_LENGTH,
            (rejected as RendererStyleCutResult.Rejected).reason,
        )
        val after = state.snapshot()
        assertEquals(before.revision, after.revision)
        assertArrayEquals(before.keys, after.keys)
        assertArrayEquals(before.colors, after.colors)
        assertArrayEquals(before.styleRows, after.styleRows)
        assertEquals(before.update!!.geometryRevision, after.update!!.geometryRevision)
        assertEquals(before.update!!.visibilityRevision, after.update!!.visibilityRevision)
        assertEquals(1, state.currentStyleRevision)
        assertEquals(null, state.currentTargetSurfaceId)
        assertEquals(null, state.currentTargetDirectionIndex)
    }

    @Test
    fun `stale ownership and old group are rejected before any style effect`() {
        val qualifiedState = stateWithRows()
        qualifiedState.snapshot()
        val baseline = cut(
            reset = true,
            ids = longArrayOf(1, 2),
            styleValues = arrayOf(style(), style()),
        )
        val beforeQualified = qualifiedState.snapshot()
        assertEquals(
            RendererStyleCutRejection.STALE_OWNERSHIP,
            (qualifiedState.applyStyleCut(
                baseline.copy(ownership = activeOwnership.copy(bindingGeneration = 2)),
            ) as RendererStyleCutResult.Rejected).reason,
        )
        val afterQualified = qualifiedState.snapshot()
        assertEquals(beforeQualified.revision, afterQualified.revision)
        assertArrayEquals(beforeQualified.colors, afterQualified.colors)
        assertEquals(0, qualifiedState.currentStyleRevision)

        val oldGroupState = VisibilityGridRendererState(2)
        oldGroupState.startCanonicalGroup(
            config = group(2),
            geometryRevision = 7,
            rows = listOf(row(1, 0), row(2, 0)),
            ownership = null,
            transactionId = 9,
            lineageRevision = 3,
        )
        oldGroupState.snapshot()
        val beforeOldGroup = oldGroupState.snapshot()
        assertEquals(
            RendererStyleCutRejection.GROUP_MISMATCH,
            (oldGroupState.applyStyleCut(
                baseline.copy(ownership = activeOwnership.copy(captureGroupId = "03".repeat(16))),
            ) as RendererStyleCutResult.Rejected).reason,
        )
        val afterOldGroup = oldGroupState.snapshot()
        assertEquals(beforeOldGroup.revision, afterOldGroup.revision)
        assertArrayEquals(beforeOldGroup.colors, afterOldGroup.colors)
        assertEquals(0, oldGroupState.currentStyleRevision)
    }

    @Test
    fun `qualifiers rows target and reset completeness reject atomically`() {
        val state = stateWithRows()
        state.snapshot()
        val baseline = cut(
            reset = true,
            ids = longArrayOf(1, 2),
            styleValues = arrayOf(style(), style()),
        )
        assertTrue(state.applyStyleCut(baseline) is RendererStyleCutResult.Applied)
        val before = state.snapshot()

        val cases = listOf(
            baseline.copy(geometryRevision = 6, styleRevision = 2, semanticRevision = 2,
                coverageRevision = 2, residencyRevision = 2, targetRevision = 2),
            baseline.copy(styleRevision = 2, semanticRevision = 2, coverageRevision = 2,
                residencyRevision = 2, targetRevision = 2, surfaceIds = longArrayOf(2, 3),
                styleRows = styles(style(), style(coverage = CoverageRendererCoverage.COMPLETE))),
            baseline.copy(styleRevision = 2, semanticRevision = 2, coverageRevision = 2,
                residencyRevision = 2, targetRevision = 2, surfaceIds = longArrayOf(2, 1),
                styleRows = styles(style(), style())),
            baseline.copy(styleRevision = 2, semanticRevision = 2, coverageRevision = 2,
                residencyRevision = 2, targetRevision = 2, targetSurfaceId = 3,
                targetDirectionIndex = 2, styleRows = styles(style(semanticGeneration = 2,
                    styleGeneration = 2), style(semanticGeneration = 2, styleGeneration = 2))),
            baseline.copy(styleRevision = 2, semanticRevision = 2, coverageRevision = 2,
                residencyRevision = 2, targetRevision = 2, surfaceIds = longArrayOf(1),
                styleRows = styles(style(semanticGeneration = 2, styleGeneration = 2))),
            baseline.copy(styleRevision = 2, semanticRevision = 2, coverageRevision = 2,
                residencyRevision = 2, targetRevision = 2, targetSurfaceId = 1,
                targetDirectionIndex = 4, styleRows = styles(
                    style(semanticGeneration = 2, styleGeneration = 2,
                        target = CoverageRendererTarget.PRIMARY),
                    style(semanticGeneration = 2, styleGeneration = 2,
                        target = CoverageRendererTarget.PRIMARY),
                )),
        )
        val reasons = listOf(
            RendererStyleCutRejection.GEOMETRY_REVISION_MISMATCH,
            RendererStyleCutRejection.UNKNOWN_SURFACE_ID,
            RendererStyleCutRejection.UNSORTED_SURFACE_IDS,
            RendererStyleCutRejection.TARGET_NOT_SUPPLIED,
            RendererStyleCutRejection.INCOMPLETE_RESET,
            RendererStyleCutRejection.MALFORMED_STYLE,
        )
        cases.zip(reasons).forEach { (candidate, reason) ->
            assertEquals(reason, (state.applyStyleCut(candidate) as RendererStyleCutResult.Rejected).reason)
            val unchanged = state.snapshot()
            assertEquals(before.revision, unchanged.revision)
            assertArrayEquals(before.colors, unchanged.colors)
            assertArrayEquals(before.styleRows, unchanged.styleRows)
        }
    }

    @Test
    fun `geometry removal invalidates the old target and style replay`() {
        val state = stateWithRows()
        state.snapshot()
        val cut = cut(
            reset = true,
            ids = longArrayOf(1, 2),
            styleValues = arrayOf(
                style(target = CoverageRendererTarget.PRIMARY),
                style(),
            ),
            targetSurfaceId = 1,
            targetDirectionIndex = 4,
        )
        assertTrue(state.applyStyleCut(cut) is RendererStyleCutResult.Applied)
        state.snapshot()

        assertTrue(
            state.applyGeometry(
                revision = 8,
                reset = false,
                upsertRows = emptyList(),
                removalSurfaceIds = longArrayOf(1),
            ),
        )
        assertNull(state.currentTargetSurfaceId)
        assertNull(state.currentTargetDirectionIndex)
        assertEquals(1, state.currentStyleRevision)
        assertEquals(
            RendererStyleCutRejection.GEOMETRY_REVISION_MISMATCH,
            (state.applyStyleCut(cut) as RendererStyleCutResult.Rejected).reason,
        )
        val afterRejectedReplay = state.snapshot()
        assertEquals(1, state.currentStyleRevision)
        assertEquals(8L, afterRejectedReplay.update!!.geometryRevision)
    }

    @Test
    fun `geometry replacement clears the stable row style before a new qualified cut`() {
        val state = stateWithRows()
        state.snapshot()
        val styled = cut(
            reset = true,
            ids = longArrayOf(1, 2),
            styleValues = arrayOf(
                style(coverage = CoverageRendererCoverage.COMPLETE,
                    target = CoverageRendererTarget.PRIMARY),
                style(coverage = CoverageRendererCoverage.PARTIAL),
            ),
            targetSurfaceId = 1,
            targetDirectionIndex = 4,
        )
        assertTrue(state.applyStyleCut(styled) is RendererStyleCutResult.Applied)
        state.snapshot()

        assertTrue(
            state.applyGeometry(
                revision = 8,
                reset = false,
                upsertRows = listOf(row(1, 1)),
                removalSurfaceIds = longArrayOf(),
            ),
        )
        val snapshot = state.snapshot()
        val replaced = snapshot.keys.indexOf(packVisibilityGridKey(1, 0, 0))
        val cleared = CoverageRendererStyleRowV1.decode(
            snapshot.styleRows,
            replaced * COVERAGE_RENDERER_STYLE_ROW_BYTES,
        )
        assertEquals(CoverageRendererCoverage.UNCOVERED, cleared.coverage)
        assertEquals(CoverageRendererTarget.NONE, cleared.target)
        assertNull(state.currentTargetSurfaceId)
        assertEquals(
            RendererStyleCutRejection.GEOMETRY_REVISION_MISMATCH,
            (state.applyStyleCut(styled) as RendererStyleCutResult.Rejected).reason,
        )
    }

    @Test
    fun `empty cut is accepted only when it clears an installed target`() {
        val state = stateWithRows()
        state.snapshot()
        val empty = cut(
            reset = false,
            ids = longArrayOf(),
            styleValues = emptyArray<CoverageRendererStyleRowV1>(),
        )
        assertEquals(
            RendererStyleCutRejection.EMPTY_CUT_NOT_ALLOWED,
            (state.applyStyleCut(empty) as RendererStyleCutResult.Rejected).reason,
        )
        assertEquals(0, state.currentStyleRevision)

        val targeted = cut(
            reset = true,
            ids = longArrayOf(1, 2),
            styleValues = arrayOf(
                style(target = CoverageRendererTarget.PRIMARY),
                style(),
            ),
            targetSurfaceId = 1,
            targetDirectionIndex = 4,
        )
        assertTrue(state.applyStyleCut(targeted) is RendererStyleCutResult.Applied)
        val clear = empty.copy(
            semanticRevision = 2,
            coverageRevision = 2,
            styleRevision = 2,
            residencyRevision = 2,
            targetRevision = 2,
        )
        assertTrue(state.applyStyleCut(clear) is RendererStyleCutResult.Applied)
        assertNull(state.currentTargetSurfaceId)
        assertEquals(2, state.currentStyleRevision)
    }

    private fun stateWithRows(): VisibilityGridRendererState {
        val state = VisibilityGridRendererState(2)
        state.startCanonicalGroup(
            config = group(2),
            geometryRevision = 7,
            rows = listOf(row(1, 0), row(2, 0)),
            ownership = activeOwnership,
            transactionId = 9,
            lineageRevision = 3,
        )
        return state
    }

    private fun cut(
        reset: Boolean,
        ids: LongArray,
        styleValues: Array<CoverageRendererStyleRowV1>,
        targetSurfaceId: Long? = null,
        targetDirectionIndex: Int? = null,
    ) = QualifiedRendererStyleCut(
        ownership = activeOwnership,
        transactionId = 9,
        geometryRevision = 7,
        lineageRevision = 3,
        semanticRevision = 1,
        coverageRevision = 1,
        styleRevision = 1,
        residencyRevision = 1,
        targetRevision = 1,
        reset = reset,
        surfaceIds = ids,
        styleRows = styles(*styleValues),
        targetSurfaceId = targetSurfaceId,
        targetDirectionIndex = targetDirectionIndex,
    )

    private fun style(
        semanticGeneration: Long = 1,
        styleGeneration: Long = 1,
        coverage: CoverageRendererCoverage = CoverageRendererCoverage.UNCOVERED,
        target: CoverageRendererTarget = CoverageRendererTarget.NONE,
    ) = CoverageRendererStyleRowV1(
        semanticGeneration = semanticGeneration,
        styleGeneration = styleGeneration,
        coverage = coverage,
        target = target,
        directionBin = if (target == CoverageRendererTarget.NONE) 0xff else 4,
        glyph = if (target == CoverageRendererTarget.NONE) CoverageRendererGlyph.NONE
        else CoverageRendererGlyph.DESIRED_DIRECTION,
    )

    private fun styles(vararg rows: CoverageRendererStyleRowV1): ByteArray =
        rows.fold(ByteArray(0)) { bytes, row -> bytes + row.encode() }

    private fun row(id: Long, x: Int) = CanonicalRenderRow(
        surfaceId = id,
        voxelKey = packVisibilityGridKey(x, 0, 0),
        packedNormal = 0,
        normalConfidence = 0,
        lineageCount = 0,
    )

    private fun group(capacity: Int) = VisibilityGridGroupConfig(
        groupId = "02".repeat(16),
        groupGeneration = 1,
        sessionGeneration = 1,
        voxelSizeMeters = 0.1,
        capacity = capacity,
        groupFromWorldGl = identityVisibilityGridTransform(),
        worldFromGroupGl = identityVisibilityGridTransform(),
        restoredGeometryRevision = 0,
        restoredKeys = longArrayOf(),
    )

    private fun ownership() = VisibilityObservationOwnership(
        sessionId = "01".repeat(16), sessionGeneration = 1,
        captureGroupId = "02".repeat(16), groupGeneration = 1, coverageEpoch = 1,
        arSessionIdentity = "03".repeat(16), viewInstanceId = "04".repeat(16), viewGeneration = 1,
        nativeStreamToken = "05".repeat(16), workerBindingToken = "06".repeat(16),
        bindingGeneration = 1, lifecycleSequence = 1, operationGeneration = 1,
        groupFrame = VisibilityGroupFrame.copyOf(
            identityVisibilityGridTransform(), identityVisibilityGridTransform(), 100_000, 2,
        ),
    )
}
