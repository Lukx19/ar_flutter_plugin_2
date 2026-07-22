package com.uhg0.ar_flutter_plugin_2.shared_camera.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraCapabilityProfileResolverTest {
    @Test
    fun `cold cache uses detected capability instead of false`() {
        val supported =
            CameraCapabilityProfileResolver.resolveCachedOrDetectedBoolean(
                cacheHit = false,
                cachedValuePresent = false,
                cachedValue = false,
                detectedValue = true,
            )

        assertTrue(supported)
    }

    @Test
    fun `valid cache reuses stored capability`() {
        val supported =
            CameraCapabilityProfileResolver.resolveCachedOrDetectedBoolean(
                cacheHit = true,
                cachedValuePresent = true,
                cachedValue = false,
                detectedValue = true,
            )

        assertFalse(supported)
    }

    @Test
    fun `missing cached value falls back to current detection`() {
        val supported =
            CameraCapabilityProfileResolver.resolveCachedOrDetectedBoolean(
                cacheHit = true,
                cachedValuePresent = false,
                cachedValue = false,
                detectedValue = true,
            )

        assertTrue(supported)
    }

    @Test
    fun `concurrent detection keeps only rear partners of primary camera`() {
        val rearIds =
            CameraCapabilityProfileResolver.rearConcurrentCameraIds(
                primaryId = "0",
                concurrentCameraIdSets =
                    setOf(
                        setOf("0", "1"),
                        setOf("0", "3"),
                        setOf("2", "4"),
                    ),
                lensFacingByCameraId =
                    mapOf(
                        "0" to 1,
                        "1" to 0,
                        "2" to 1,
                        "3" to 1,
                        "4" to 1,
                    ),
                backFacingValue = 1,
            )

        assertEquals(listOf("3"), rearIds)
    }
}
