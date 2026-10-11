package com.uhg0.ar_flutter_plugin_2.gridrenderer

import org.junit.Assert.assertEquals
import org.junit.Test

/** Which summary boxes stand in for grid content under a base layer. */
class StyleDrawSetLevelOfDetailTest {
    private fun passes(mode: GridDisplayState): List<Pair<GridBuffer, Int>> {
        val out = ArrayList<Pair<GridBuffer, Int>>()
        StyleDrawSet.forEachLevelOfDetailPass(mode) { buffer, residency, _ -> out.add(buffer to residency) }
        return out
    }

    @Test
    fun `the default display mode draws paged-out regions and summary-only bricks`() {
        val mode = GridDisplayState() // base points, no overlays
        assertEquals(
            listOf(
                GridBuffer.BRICKS to StyleDrawSet.RESIDENCY_SUMMARY_ONLY,
                GridBuffer.REGIONS to StyleDrawSet.RESIDENCY_ON_DISK,
            ),
            passes(mode),
        )
    }

    @Test
    fun `every base layer keeps paged-out regions visible`() {
        for (base in intArrayOf(GridDisplayCodes.BASE_POINTS, GridDisplayCodes.BASE_SPLATS, GridDisplayCodes.BASE_VOXELS)) {
            val mode = GridDisplayState().apply { baseLayer = base }
            assertEquals(GridBuffer.REGIONS to StyleDrawSet.RESIDENCY_ON_DISK, passes(mode).last())
        }
    }

    @Test
    fun `the region overlay replaces the paged region pass and no base layer draws none`() {
        val overlay = GridDisplayState().apply { overlayBits = GridDisplayCodes.OVERLAY_REGIONS }
        assertEquals(listOf(GridBuffer.BRICKS to StyleDrawSet.RESIDENCY_SUMMARY_ONLY), passes(overlay))
        val none = GridDisplayState().apply { baseLayer = GridDisplayCodes.BASE_NONE }
        assertEquals(emptyList<Pair<GridBuffer, Int>>(), passes(none))
    }
}
