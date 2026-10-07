package com.uhg0.ar_flutter_plugin_2.sceneview

import org.junit.Assert.*
import org.junit.Test

class CoverageShapeResourceUpgradeTest {
    @Test fun `late qualified shapes promote an initially mounted point resource without another canonical cut`() {
        val gate = CoverageShapeResourceUpgrade()
        assertFalse(gate.request(CoveragePresentationMode.PLANAR_PATCHES, 4, 8, false, false))
        assertTrue(gate.request(CoveragePresentationMode.PLANAR_PATCHES, 4, 8, true, false))
        assertFalse(gate.request(CoveragePresentationMode.PLANAR_PATCHES, 4, 8, true, true))
    }
    @Test fun `failed or budget refused promotion cannot retry on every renderer refresh`() {
        val gate = CoverageShapeResourceUpgrade()
        assertTrue(gate.request(CoveragePresentationMode.PLANAR_PATCHES, 4, 8, true, false))
        repeat(1000) { assertFalse(gate.request(CoveragePresentationMode.PLANAR_PATCHES, 4, 8, true, false)) }
        assertTrue(gate.request(CoveragePresentationMode.PLANAR_PATCHES, 4, 9, true, false))
    }
    @Test fun `unavailable stale or point only geometry does not consume a valid selection attempt`() {
        val gate = CoverageShapeResourceUpgrade()
        assertFalse(gate.request(CoveragePresentationMode.PLANAR_PATCHES, 4, 8, false, false))
        assertFalse(gate.request(CoveragePresentationMode.PLANAR_PATCHES, 4, 0, true, false))
        assertFalse(gate.request(CoveragePresentationMode.PLANAR_PATCHES, -1, 8, true, false))
        assertTrue(gate.request(CoveragePresentationMode.PLANAR_PATCHES, 4, 8, true, false))
    }
    @Test fun `existing sufficient shape capacity avoids replacement while a new source can promote`() {
        val gate = CoverageShapeResourceUpgrade()
        assertFalse(gate.request(CoveragePresentationMode.PLANAR_PATCHES, 4, 8, true, true))
        assertTrue(gate.request(CoveragePresentationMode.PLANAR_PATCHES, 4, 8, true, false))
        assertTrue(gate.request(CoveragePresentationMode.PLANAR_PATCHES, 5, 8, true, false))
    }
    @Test fun `semantic point cube and overview modes never promote retained shape data`() {
        val gate = CoverageShapeResourceUpgrade()
        for (mode in CoveragePresentationMode.entries) {
            if (mode != CoveragePresentationMode.PLANAR_PATCHES && mode != CoveragePresentationMode.DIRECTIONAL_BALLS)
                assertFalse(gate.request(mode, 4, 8, true, false))
        }
        assertTrue(gate.request(CoveragePresentationMode.DIRECTIONAL_BALLS, 4, 8, true, false))
    }
    @Test fun `explicit clear or renderer recovery permits one fresh attempt for retained geometry`() {
        val gate = CoverageShapeResourceUpgrade()
        assertTrue(gate.request(CoveragePresentationMode.PLANAR_PATCHES, 4, 8, true, false))
        gate.reset()
        assertTrue(gate.request(CoveragePresentationMode.PLANAR_PATCHES, 4, 8, true, false))
        assertFalse(gate.request(CoveragePresentationMode.PLANAR_PATCHES, 4, 8, true, false))
    }
}
