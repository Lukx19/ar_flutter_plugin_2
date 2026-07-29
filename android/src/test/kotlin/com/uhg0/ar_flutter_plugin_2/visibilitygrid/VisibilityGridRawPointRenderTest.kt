package com.uhg0.ar_flutter_plugin_2.visibilitygrid

import com.google.ar.core.TrackingState
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VisibilityGridRawPointRenderTest {
    @Test
    fun `feature acquisition is disabled while ARCore tracking is unavailable`() {
        assertTrue(shouldAcquireVisibilityFeatures(TrackingState.TRACKING))
        assertTrue(!shouldAcquireVisibilityFeatures(TrackingState.PAUSED))
        assertTrue(!shouldAcquireVisibilityFeatures(TrackingState.STOPPED))
    }

    @Test
    fun `accepted feature observations remain available to native raw point rendering`() {
        val observation =
            FeatureObservation(
                timestampNs = 42,
                groupGeneration = 1,
                sessionGeneration = 2,
                samples =
                    listOf(
                        FeatureSample(7, 0.25, -0.50, -1.25, 0.90),
                        FeatureSample(8, -0.75, 0.10, -2.00, 0.80),
                    ),
                sanitized = true,
            )

        val snapshot =
            observation.toRawPointRenderSnapshot(
                capacity = 100,
                color = 0xFFFF0000.toInt(),
                enabled = true,
            )

        assertEquals(42, snapshot.revision)
        assertEquals(2, snapshot.count)
        assertTrue(snapshot.enabled)
        assertArrayEquals(longArrayOf(7, 8), snapshot.keys)
        assertArrayEquals(
            floatArrayOf(0.25f, -0.50f, -1.25f, -0.75f, 0.10f, -2.00f),
            snapshot.positions,
            0f,
        )
        assertArrayEquals(
            intArrayOf(0xFFFF0000.toInt(), 0xFFFF0000.toInt()),
            snapshot.colors,
        )
    }

    @Test
    fun `raw point rendering respects native capacity`() {
        val observation =
            FeatureObservation(
                timestampNs = 1,
                groupGeneration = 1,
                sessionGeneration = 1,
                samples =
                    listOf(
                        FeatureSample(1, 1.0, 2.0, 3.0, 1.0),
                        FeatureSample(2, 4.0, 5.0, 6.0, 1.0),
                    ),
                sanitized = true,
            )

        val snapshot =
            observation.toRawPointRenderSnapshot(
                capacity = 1,
                color = 0xFF00FF00.toInt(),
                enabled = false,
            )

        assertEquals(1, snapshot.count)
        assertArrayEquals(longArrayOf(1), snapshot.keys)
        assertArrayEquals(floatArrayOf(1f, 2f, 3f), snapshot.positions, 0f)
    }
}
