package com.uhg0.ar_flutter_plugin_2.sceneview

import com.uhg0.ar_flutter_plugin_2.pointcloud.CoverageRendererCoverage
import com.uhg0.ar_flutter_plugin_2.pointcloud.CoverageRendererPalette
import com.uhg0.ar_flutter_plugin_2.pointcloud.CoverageRendererResidency
import com.uhg0.ar_flutter_plugin_2.pointcloud.CoverageRendererSemantic
import com.uhg0.ar_flutter_plugin_2.pointcloud.CoverageRendererStyleRowV1
import com.uhg0.ar_flutter_plugin_2.pointcloud.CoverageRendererTarget
import com.uhg0.ar_flutter_plugin_2.pointcloud.CoveragePointRenderSnapshot
import com.uhg0.ar_flutter_plugin_2.pointcloud.CoveragePointRenderUpdate
import com.uhg0.ar_flutter_plugin_2.pointcloud.CoveragePointSpan
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    private fun generatedSnapshot(
        count: Int,
        geometryRevision: Long,
        rendererGeneration: Long = 1L,
        styleRows: ByteArray = ByteArray(0),
        positions: FloatArray? = null,
        update: CoveragePointRenderUpdate? = null,
    ) = VisibilityRendererSnapshot(
        bindingGeneration = 1L,
        groupGeneration = 1L,
        rendererGeneration = rendererGeneration,
        transactionId = geometryRevision,
        geometryRevision = geometryRevision,
        styleRevision = geometryRevision,
        rows = (0 until count).map { index ->
            VisibilityRendererRow(
                surfaceId = index.toLong(),
                x = index.toFloat(),
                y = 0f,
                z = 0f,
                semanticLabel = CoverageRendererSemantic.CONFIRMED,
                coverageLabel = CoverageRendererCoverage.COMPLETE,
                targetDirectionIndex = null,
            )
        },
        renderSnapshot = CoveragePointRenderSnapshot(
            revision = geometryRevision,
            enabled = true,
            capacity = count,
            count = count,
            keys = LongArray(count) { it.toLong() },
            surfaceIds = LongArray(count) { it.toLong() },
            positions = positions ?: FloatArray(count * 3) { index ->
                if (index % 3 == 0) (index / 3).toFloat() else 0f
            },
            colors = IntArray(count),
            styleRows = styleRows,
            update = update,
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

    @Test
    fun `ordinary owner updates reuse selector and keep dirty churn below two percent`() {
        val owner = NativeCoverageRendererOwner()
        owner.install(generatedSnapshot(count = 100, geometryRevision = 1L))
        val next = generatedSnapshot(
            count = 100,
            geometryRevision = 2L,
            positions = FloatArray(300) { index ->
                if (index == 0) 123f else if (index % 3 == 0) (index / 3).toFloat() else 0f
            },
            update = CoveragePointRenderUpdate(
                geometryRevision = 2L,
                visibilityRevision = 2L,
                enabled = true,
                count = 100,
                spans = listOf(
                    CoveragePointSpan(
                        startSlot = 0,
                        positions = floatArrayOf(123f, 0f, 0f),
                        colors = intArrayOf(7),
                    ),
                ),
                reset = false,
            ),
        )
        owner.install(next)

        val presented = checkNotNull(owner.presentationSnapshot())
        val update = checkNotNull(presented.update)
        assertFalse(update.reset)
        val dirtyRows = update.spans.sumOf { it.colors.size }
        assertTrue(dirtyRows * 100 < 100 * 2)
        assertEquals(100, presented.surfaceIds.distinct().size)
    }

    @Test
    fun `selector resets on renderer epoch and mode changes but not ordinary revisions`() {
        val owner = NativeCoverageRendererOwner()
        owner.install(generatedSnapshot(count = 4, geometryRevision = 1L))

        val ordinary = generatedSnapshot(
            count = 4,
            geometryRevision = 2L,
            update = CoveragePointRenderUpdate(
                geometryRevision = 2L,
                visibilityRevision = 2L,
                enabled = true,
                count = 4,
                spans = emptyList(),
                reset = false,
            ),
        )
        owner.install(ordinary)
        assertFalse(checkNotNull(owner.presentationSnapshot()!!.update).reset)

        owner.install(
            generatedSnapshot(
                count = 4,
                geometryRevision = 3L,
                rendererGeneration = 2L,
                update = ordinary.renderSnapshot!!.update,
            ),
        )
        assertTrue(checkNotNull(owner.presentationSnapshot()!!.update).reset)

        owner.setControls(
            CoverageRendererControls(
                visible = true,
                mode = CoveragePresentationMode.SEMANTIC_CUBES,
                palette = CoverageRendererPalette.COVERAGE,
            ),
        )
        assertTrue(checkNotNull(owner.presentationSnapshot()!!.update).reset)
    }

    @Test
    fun `palette recolor preserves retained selector slots without a reset`() {
        val styleRows = listOf(
            CoverageRendererStyleRowV1(
                coverage = CoverageRendererCoverage.UNCOVERED,
                palette = CoverageRendererPalette.COVERAGE,
            ).encode(),
            CoverageRendererStyleRowV1(
                coverage = CoverageRendererCoverage.PARTIAL,
                palette = CoverageRendererPalette.COVERAGE,
            ).encode(),
            CoverageRendererStyleRowV1(
                coverage = CoverageRendererCoverage.COMPLETE,
                palette = CoverageRendererPalette.COVERAGE,
            ).encode(),
        ).reduce { left, right -> left + right }
        val owner = NativeCoverageRendererOwner()
        owner.install(
            generatedSnapshot(
                3,
                1L,
                styleRows = styleRows,
                update = CoveragePointRenderUpdate(
                    geometryRevision = 1L,
                    visibilityRevision = 1L,
                    enabled = true,
                    count = 3,
                    spans = emptyList(),
                    reset = false,
                ),
            ),
        )
        val before = checkNotNull(owner.presentationSnapshot())
        val slots = before.surfaceIds.copyOf()

        owner.setControls(
            CoverageRendererControls(
                visible = true,
                mode = CoveragePresentationMode.SEMANTIC_CENTROIDS,
                palette = CoverageRendererPalette.NORMAL,
            ),
        )

        val after = checkNotNull(owner.presentationSnapshot())
        assertEquals(slots.toList(), after.surfaceIds.toList())
        val update = checkNotNull(after.update)
        assertFalse(update.reset)
        assertEquals(1L, after.paletteRevision)
        assertEquals(1, update.spans.size)
        assertEquals(0, update.spans.single().startSlot)
        assertEquals(after.count, update.spans.single().colors.size)
        assertArrayEquals(after.positions, update.spans.single().positions, 0f)
        assertArrayEquals(after.colors, update.spans.single().colors)
        assertArrayEquals(after.styleRows, update.spans.single().styleRows)
        assertEquals(
            CoverageRendererPalette.NORMAL,
            CoverageRendererStyleRowV1.decode(after.styleRows).palette,
        )
    }

    @Test
    fun `shared plan ranks target need residency and surface while applying palette`() {
        val styles = listOf(
            CoverageRendererStyleRowV1(
                coverage = CoverageRendererCoverage.COMPLETE,
                residency = CoverageRendererResidency.ACTIVE_L0,
            ),
            CoverageRendererStyleRowV1(
                coverage = CoverageRendererCoverage.UNCOVERED,
                residency = CoverageRendererResidency.COLD_L2,
            ),
            CoverageRendererStyleRowV1(
                coverage = CoverageRendererCoverage.PARTIAL,
                residency = CoverageRendererResidency.WARM_L1,
                target = CoverageRendererTarget.PRIMARY,
            ),
        )
        val rows = styles.mapIndexed { index, style ->
            VisibilityRendererRow(
                surfaceId = longArrayOf(30L, 20L, 10L)[index],
                x = index.toFloat(),
                y = 0f,
                z = 0f,
                semanticLabel = style.semantic,
                coverageLabel = style.coverage,
                targetDirectionIndex = null,
                style = style,
            )
        }
        val encodedStyles = styles.flatMap { it.encode().toList() }.toByteArray()
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
                renderSnapshot = CoveragePointRenderSnapshot(
                    revision = 1L,
                    enabled = true,
                    capacity = 3,
                    count = 3,
                    keys = longArrayOf(30L, 20L, 10L),
                    surfaceIds = longArrayOf(30L, 20L, 10L),
                    positions = FloatArray(9),
                    colors = IntArray(3),
                    styleRows = encodedStyles,
                ),
            ),
        )
        owner.setControls(
            CoverageRendererControls(
                visible = true,
                mode = CoveragePresentationMode.SEMANTIC_CENTROIDS,
                palette = CoverageRendererPalette.NORMAL,
            ),
        )

        assertEquals(listOf(10L, 20L, 30L), owner.presentationPlan()!!.surfaceIds)
        val rendered = owner.presentationSnapshot()!!
        assertEquals(owner.presentationPlan()!!.surfaceIds, rendered.surfaceIds.toList())
        assertEquals(
            CoverageRendererPalette.NORMAL,
            CoverageRendererStyleRowV1.decode(rendered.styleRows).palette,
        )
    }

    @Test
    fun `controls survive a newer install and resource failure leaves semantic cut intact`() {
        val owner = NativeCoverageRendererOwner()
        owner.setControls(
            CoverageRendererControls(
                visible = true,
                mode = CoveragePresentationMode.SEMANTIC_CUBES,
                palette = CoverageRendererPalette.NORMAL,
            ),
        )
        owner.install(snapshot(rendererGeneration = 2L))
        assertEquals(CoveragePresentationMode.SEMANTIC_CUBES, owner.status().mode)
        assertEquals(CoverageRendererPalette.NORMAL, owner.status().palette)

        assertTrue(owner.markResourceFailure(2L))
        assertTrue(owner.status().rendererUnavailable)
        assertEquals(2, owner.status().rowCount)
        val recovery = owner.resume()
        assertTrue(recovery.recovered)
        assertTrue(recovery.rehydrated.not())
        assertTrue(owner.status().rendererUnavailable)
        assertTrue(owner.markResourceMounted(2L))
        assertTrue(owner.status().resourceAvailable)
        assertEquals(2, owner.status().rowCount)
    }

    @Test
    fun `owner control transition keeps prior cut on rejected resource admission`() {
        var admitted = false
        val owner = NativeCoverageRendererOwner(
            onControlsChanged = { admitted },
        )
        owner.install(snapshot())

        val rejected = owner.setControls(
            CoverageRendererControls(
                visible = true,
                mode = CoveragePresentationMode.SEMANTIC_CUBES,
                palette = CoverageRendererPalette.COVERAGE,
            ),
        )
        assertFalse(rejected.accepted)
        assertEquals(CoveragePresentationMode.SEMANTIC_CENTROIDS, owner.status().mode)
        assertEquals(5L, owner.status().rendererGeneration)

        admitted = true
        val accepted = owner.setControls(
            CoverageRendererControls(
                visible = true,
                mode = CoveragePresentationMode.SEMANTIC_CUBES,
                palette = CoverageRendererPalette.COVERAGE,
            ),
        )
        assertTrue(accepted.accepted)
        assertEquals(CoveragePresentationMode.SEMANTIC_CUBES, owner.status().mode)
    }
}
