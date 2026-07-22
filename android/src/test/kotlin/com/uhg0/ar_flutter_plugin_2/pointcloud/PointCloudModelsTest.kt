package com.uhg0.ar_flutter_plugin_2.pointcloud

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.fail
import org.junit.Test

class PointCloudModelsTest {
    @Test
    fun `config validates protocol and resource bounds`() {
        assertFails { PointCloudNativeConfig(wireVersion = "v2") }
        assertFails { PointCloudNativeConfig(renderCapacity = 0) }
        assertFails { PointCloudNativeConfig(minConfidence = 1.1f) }
        assertFails { PointCloudNativeConfig(frameRateHz = 0) }
        assertFails { PointCloudNativeConfig(voxelSizeMeters = 0f) }
    }

    @Test
    fun `synthetic source is deterministic and returns independent arrays`() {
        val fixture = SyntheticPointCloudFixture(
            ids = intArrayOf(7),
            points = floatArrayOf(1f, 2f, 3f, 0.9f),
            timestampStepNs = 25,
        )
        val source = SyntheticPointCloudSource(fixture)
        val first = source.acquire(null)
        val second = source.acquire(null)
        assertEquals(0, first.sequence)
        assertEquals(0, first.timestampNs)
        assertEquals(1, second.sequence)
        assertEquals(25, second.timestampNs)
        assertArrayEquals(first.ids, second.ids)
        assertArrayEquals(first.points, second.points, 0f)
        assertNotSame(first.ids, second.ids)
        assertNotSame(first.points, second.points)
    }

    private fun assertFails(block: () -> Unit) {
        try {
            block()
            fail("Expected IllegalArgumentException")
        } catch (_: IllegalArgumentException) {
            // Expected.
        }
    }
}
