package com.uhg0.ar_flutter_plugin_2.visibilitygrid

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VisibilityGridFrameCadenceTest {
    @Test
    fun `sixty fps callbacks admit feature copying at ten fps`() {
        val cadence = VisibilityGridFrameCadence(featureRateHz = 10, depthRateHz = 5)
        val plans =
            (0 until 60).map { frame ->
                cadence.plan(frame * 1_000_000_000L / 60, depthEnabled = false)
            }

        assertEquals(10, plans.count(VisibilityGridFramePlan::acquireFeature))
        assertFalse(plans.any(VisibilityGridFramePlan::acquireDepth))
    }

    @Test
    fun `feature and depth work are phased onto separate callbacks`() {
        val cadence = VisibilityGridFrameCadence(featureRateHz = 10, depthRateHz = 5)
        val plans =
            (0 until 100).map { tick ->
                cadence.plan(tick * 10_000_000L, depthEnabled = true)
            }

        assertEquals(10, plans.count(VisibilityGridFramePlan::acquireFeature))
        assertEquals(5, plans.count(VisibilityGridFramePlan::acquireDepth))
        assertFalse(plans.any { it.acquireFeature && it.acquireDepth })
        assertTrue(plans.first().acquireFeature)
        assertTrue(plans[5].acquireDepth)
    }

    @Test
    fun `late callback performs one task and never replays missed intervals`() {
        val cadence = VisibilityGridFrameCadence(featureRateHz = 10, depthRateHz = 5)
        assertTrue(cadence.plan(0, depthEnabled = true).acquireFeature)

        val late = cadence.plan(2_000_000_000L, depthEnabled = true)
        val sameTimestamp = cadence.plan(2_000_000_000L, depthEnabled = true)

        assertEquals(1, listOf(late, sameTimestamp).count { it.acquireDepth })
        assertEquals(1, listOf(late, sameTimestamp).count { it.acquireFeature })
        assertFalse(late.acquireFeature && late.acquireDepth)
        assertFalse(sameTimestamp.acquireFeature && sameTimestamp.acquireDepth)
    }

    @Test
    fun `reset admits a fresh feature observation immediately`() {
        val cadence = VisibilityGridFrameCadence(featureRateHz = 10, depthRateHz = 5)
        cadence.plan(1_000_000_000L, depthEnabled = false)
        cadence.reset()

        assertTrue(cadence.plan(1_010_000_000L, depthEnabled = false).acquireFeature)
    }
}
