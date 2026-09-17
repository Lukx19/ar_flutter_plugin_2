package com.uhg0.ar_flutter_plugin_2.sceneview

import com.uhg0.ar_flutter_plugin_2.pointcloud.CoverageRendererCoverage
import com.uhg0.ar_flutter_plugin_2.pointcloud.CoverageRendererPalette
import com.uhg0.ar_flutter_plugin_2.pointcloud.CoverageRendererSemantic
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CoverageRendererOwnerTest {
    private fun snapshot(
        geometryRevision: Long = 4,
        styleRevision: Long = 7,
    ) = VisibilityRendererSnapshot(
        bindingGeneration = 2,
        groupGeneration = 3,
        rendererGeneration = 5,
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
}
