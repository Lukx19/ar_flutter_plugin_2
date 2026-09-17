package com.uhg0.ar_flutter_plugin_2.sceneview

import com.uhg0.ar_flutter_plugin_2.pointcloud.CoverageRendererCoverage
import com.uhg0.ar_flutter_plugin_2.pointcloud.CoverageRendererPalette
import com.uhg0.ar_flutter_plugin_2.pointcloud.CoverageRendererSemantic
import com.uhg0.ar_flutter_plugin_2.pointcloud.CoveragePointRenderSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CoverageRendererOwnerTest {
    private fun snapshot(
        geometryRevision: Long = 4,
        styleRevision: Long = 7,
        rendererGeneration: Long = 5,
    ) = VisibilityRendererSnapshot(
        bindingGeneration = 2,
        groupGeneration = 3,
        rendererGeneration = rendererGeneration,
        transactionId = 8,
        geometryRevision = geometryRevision,
        styleRevision = styleRevision,
        rows = listOf(
            VisibilityRendererRow(
                surfaceId = 30,
                x = 3f,
                y = 0f,
                z = 0f,
                semanticLabel = CoverageRendererSemantic.CONFIRMED,
                coverageLabel = CoverageRendererCoverage.COMPLETE,
                targetDirectionIndex = 2,
            ),
            VisibilityRendererRow(
                surfaceId = 10,
                x = 1f,
                y = 0f,
                z = 0f,
                semanticLabel = CoverageRendererSemantic.AMBIGUOUS,
                coverageLabel = CoverageRendererCoverage.PARTIAL,
                targetDirectionIndex = null,
            ),
        ),
        renderSnapshot = CoveragePointRenderSnapshot(
            revision = geometryRevision,
            enabled = true,
            capacity = 2,
            count = 2,
            keys = longArrayOf(30L, 10L),
            surfaceIds = longArrayOf(30L, 10L),
            positions = floatArrayOf(3f, 0f, 0f, 1f, 0f, 0f),
            colors = intArrayOf(0, 0),
        ),
    )

    @Test
    fun `owner keeps immutable stable snapshot and hit result uses stable surface identity`() {
        val owner = NativeCoverageRendererOwner()
        val original = snapshot()

        val install = owner.install(original)
        assertTrue(install.installed)
        assertEquals(2, install.rowCount)

        val result = owner.hitTest(1.02f, 0f)
        assertNotNull(result)
        assertEquals(10L, result!!.surfaceId)
        assertEquals(4L, result.geometryRevision)
        assertEquals(7L, result.styleRevision)
        assertEquals(CoverageRendererCoverage.PARTIAL, result.coverageLabel)

        val controls = owner.setControls(
            CoverageRendererControls(
                visible = true,
                mode = CoveragePresentationMode.SEMANTIC_CUBES,
                palette = CoverageRendererPalette.COVERAGE,
            ),
        )
        assertEquals(CoveragePresentationMode.SEMANTIC_CUBES, controls.mode)
        assertEquals(2, checkNotNull(owner.snapshot()).rowCount)
    }

    @Test
    fun `pause reports renderer unavailable and resume rehydrates newest committed cut once`() {
        val owner = NativeCoverageRendererOwner()
        owner.install(snapshot())
        owner.pause()
        assertNull(owner.hitTest(1f, 0f))
        assertTrue(owner.status().rendererUnavailable)

        owner.install(snapshot(geometryRevision = 5, styleRevision = 8))
        val recovery = owner.resume()
        assertTrue(recovery.recovered)
        assertTrue(recovery.rehydrated)
        assertEquals(5L, recovery.geometryRevision)
        assertEquals(8L, recovery.styleRevision)
        assertTrue(owner.resume().recovered.not())
    }

    @Test
    fun `stale hit receipt rejects an old revision without exposing rows`() {
        val owner = NativeCoverageRendererOwner()
        owner.install(snapshot())

        val receipt = owner.hitTestReceipt(
            xPx = 1f,
            yPx = 0f,
            expectedGeometryRevision = 3,
            expectedStyleRevision = 7,
        )
        assertTrue(receipt is CoverageHitReceipt.Stale)
    }

    @Test
    fun `screen projection discards invalid and behind rows and breaks ties by depth then surface`() {
        val owner = NativeCoverageRendererOwner(
            worldToScreen = CoverageWorldToScreenProjection { x, _, _ ->
                when (x.toInt()) {
                    1 -> CoverageScreenPoint(10f, 10f, 2f)
                    2 -> CoverageScreenPoint(10f, 10f, 1f)
                    3 -> null
                    else -> CoverageScreenPoint(10f, 10f, -1f)
                }
            },
        )
        owner.install(
            VisibilityRendererSnapshot(
                bindingGeneration = 1,
                groupGeneration = 1,
                rendererGeneration = 1,
                transactionId = 1,
                geometryRevision = 1,
                styleRevision = 1,
                rows = listOf(
                    snapshot().rows[0].copy(surfaceId = 30, x = 1f),
                    snapshot().rows[0].copy(surfaceId = 20, x = 2f),
                    snapshot().rows[0].copy(surfaceId = 10, x = 3f),
                    snapshot().rows[0].copy(surfaceId = 5, x = 4f),
                ),
            ),
        )

        assertEquals(20L, owner.hitTest(10f, 10f)!!.surfaceId)
    }

    @Test
    fun `stale install leaves the committed cut unchanged while a higher renderer replays it`() {
        val owner = NativeCoverageRendererOwner()
        owner.install(snapshot(rendererGeneration = 5))

        val replay = owner.install(snapshot(rendererGeneration = 5))
        assertTrue(replay.replayed)

        val stale = owner.install(snapshot(geometryRevision = 3, rendererGeneration = 4))
        assertTrue(stale.stale)
        assertEquals(5L, owner.status().rendererGeneration)
        assertEquals(4L, owner.status().geometryRevision)

        val staleCutOnNewRenderer = owner.install(
            snapshot(geometryRevision = 3, rendererGeneration = 6),
        )
        assertTrue(staleCutOnNewRenderer.stale)
        assertEquals(5L, owner.status().rendererGeneration)

        val replacement = owner.install(snapshot(rendererGeneration = 6))
        assertTrue(replacement.installed)
        assertEquals(6L, owner.status().rendererGeneration)
        assertEquals(4L, owner.status().geometryRevision)
    }

    @Test
    fun `mode controls select a bounded deterministic presentation and update the mesh callback`() {
        var callbackCount = 0
        var selectedCount = -1
        val owner = NativeCoverageRendererOwner(
            onPresentationChanged = { selected, _ ->
                callbackCount++
                selectedCount = selected?.count ?: 0
            },
        )
        owner.install(snapshot())
        owner.setControls(
            CoverageRendererControls(
                visible = true,
                mode = CoveragePresentationMode.OVERVIEW,
                palette = CoveragePalette.COVERAGE,
            ),
        )
        assertTrue(callbackCount > 0)
        assertEquals(2, selectedCount)
    }

    @Test
    fun `each presentation mode applies its cap and cubes keep the centroid prefix`() {
        val count = CoverageRendererLimits.CENTROID_CAPACITY + 1
        val rows = (0 until count).map { index ->
            VisibilityRendererRow(
                surfaceId = index.toLong(),
                x = index.toFloat(),
                y = 0f,
                z = 0f,
                semanticLabel = CoverageRendererSemantic.CONFIRMED,
                coverageLabel = CoverageRendererCoverage.COMPLETE,
                targetDirectionIndex = null,
            )
        }
        val renderSnapshot = CoveragePointRenderSnapshot(
            revision = 1L,
            enabled = true,
            capacity = count,
            count = count,
            keys = LongArray(count) { it.toLong() },
            surfaceIds = LongArray(count) { it.toLong() },
            positions = FloatArray(count * 3),
            colors = IntArray(count),
        )
        val owner = NativeCoverageRendererOwner()
        owner.install(
            VisibilityRendererSnapshot(
                bindingGeneration = 1L,
                groupGeneration = 1L,
                rendererGeneration = 1L,
                transactionId = 1L,
                geometryRevision = 1L,
                styleRevision = 1L,
                rows = rows,
                renderSnapshot = renderSnapshot,
            ),
        )

        val expected = mapOf(
            CoveragePresentationMode.SEMANTIC_CENTROIDS to CoverageRendererLimits.CENTROID_CAPACITY,
            CoveragePresentationMode.SEMANTIC_CUBES to CoverageRendererLimits.CUBE_CAPACITY,
            CoveragePresentationMode.WARM_PROXIES to CoverageRendererLimits.WARM_PROXY_CAPACITY,
            CoveragePresentationMode.OVERVIEW to CoverageRendererLimits.COLD_OVERVIEW_CAPACITY,
            CoveragePresentationMode.SUPPRESSED_DEBUG to CoverageRendererLimits.DEBUG_ROW_CAPACITY,
            CoveragePresentationMode.RAW_FEATURES to CoverageRendererLimits.RAW_POINT_CAPACITY,
        )
        expected.forEach { (mode, cap) ->
            owner.setControls(
                CoverageRendererControls(
                    visible = true,
                    mode = mode,
                    palette = CoverageRendererPalette.COVERAGE,
                ),
            )
            assertEquals(cap, owner.status().selectedRowCount)
            assertEquals(minOf(cap, count), owner.presentationSnapshot()!!.count)
        }

        owner.setControls(
            CoverageRendererControls(
                visible = true,
                mode = CoveragePresentationMode.SEMANTIC_CUBES,
                palette = CoverageRendererPalette.COVERAGE,
            ),
        )
        assertEquals(
            LongArray(CoverageRendererLimits.CUBE_CAPACITY) { it.toLong() }.toList(),
            owner.presentationSnapshot()!!.keys.toList(),
        )
    }
}
