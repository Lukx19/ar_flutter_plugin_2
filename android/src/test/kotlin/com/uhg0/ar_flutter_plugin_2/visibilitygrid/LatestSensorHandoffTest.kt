package com.uhg0.ar_flutter_plugin_2.visibilitygrid

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LatestSensorHandoffTest {
    @Test
    fun `feature and depth slots coalesce independently`() {
        val handoff = LatestSensorHandoff<String, Int>()

        assertFalse(handoff.offerFeature("feature-1"))
        assertFalse(handoff.offerDepth(1))
        assertTrue(handoff.offerFeature("feature-2"))

        val first = requireNotNull(handoff.take())
        assertEquals("feature-2", first.feature)
        assertEquals(1, first.depth)
        assertFalse(handoff.hasPending)

        assertFalse(handoff.offerDepth(2))
        assertTrue(handoff.offerDepth(3))
        val second = requireNotNull(handoff.take())
        assertNull(second.feature)
        assertEquals(3, second.depth)
    }

    @Test
    fun `clear drops both pending sensor slots`() {
        val handoff = LatestSensorHandoff<String, Int>()
        handoff.offerFeature("feature")
        handoff.offerDepth(1)

        handoff.clear()

        assertFalse(handoff.hasPending)
        assertNull(handoff.take())
    }

    @Test
    fun `stale visibility revision has its dedicated protocol code`() {
        val error =
            runCatching {
                validateVisibilityRevisions(
                    namedGeometryRevision = 4,
                    currentGeometryRevision = 4,
                    nextVisibilityRevision = 7,
                    currentVisibilityRevision = 7,
                )
            }.exceptionOrNull() as VisibilityGridMethodException

        assertEquals("VG_VISIBILITY_REVISION_STALE", error.code)
    }

    @Test
    fun `pending sources are processed in timestamp order after coalescing`() {
        data class Timed(val timestampNs: Long)

        val handoff = LatestSensorHandoff<Timed, Timed>()
        handoff.offerFeature(Timed(30))
        handoff.offerDepth(Timed(20))
        val depthFirst = requireNotNull(handoff.take())
        assertEquals(
            listOf(SensorHandoffSource.DEPTH, SensorHandoffSource.FEATURE),
            sensorProcessingOrder(
                depthFirst.feature?.timestampNs,
                depthFirst.depth?.timestampNs,
            ),
        )

        handoff.offerFeature(Timed(10))
        handoff.offerDepth(Timed(40))
        val featureFirst = requireNotNull(handoff.take())
        assertEquals(
            listOf(SensorHandoffSource.FEATURE, SensorHandoffSource.DEPTH),
            sensorProcessingOrder(
                featureFirst.feature?.timestampNs,
                featureFirst.depth?.timestampNs,
            ),
        )

        handoff.offerFeature(Timed(50))
        assertTrue(handoff.offerFeature(Timed(15)))
        handoff.offerDepth(Timed(25))
        val displaced = requireNotNull(handoff.take())
        assertEquals(
            listOf(SensorHandoffSource.FEATURE, SensorHandoffSource.DEPTH),
            sensorProcessingOrder(
                displaced.feature?.timestampNs,
                displaced.depth?.timestampNs,
            ),
        )
    }
}
