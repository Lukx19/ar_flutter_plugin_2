package com.uhg0.ar_flutter_plugin_2.shared_camera.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraCapabilityProfileResolverTest {
    @Test fun `HEIC route is offered only when that camera advertises encoded output sizes`() {
        assertEquals(listOf("jpeg"), CameraCapabilityProfileResolver.pipelineCaptureFormats(false, null, false, hardwareHeicAvailable = false))
        assertEquals(listOf("jpeg", "heic"), CameraCapabilityProfileResolver.pipelineCaptureFormats(false, null, false, hardwareHeicAvailable = true))
        assertEquals(listOf("jpeg", "raw+jpeg", "heic"), CameraCapabilityProfileResolver.pipelineCaptureFormats(true, "supported", true, hardwareHeicAvailable = true))
    }

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
    @Test
    fun `paired RAW pipeline requires current successful pair qualification and dimensions`() {
        for (status in listOf("pending", "failed", "unsupported", null)) {
            assertEquals(listOf("jpeg"), CameraCapabilityProfileResolver.pipelineCaptureFormats(
                true, status, true,
            ))
        }
        assertEquals(listOf("jpeg"), CameraCapabilityProfileResolver.pipelineCaptureFormats(
            true, "supported", false,
        ))
        assertEquals(listOf("jpeg"), CameraCapabilityProfileResolver.pipelineCaptureFormats(
            false, "supported", true,
        ))
        assertEquals(listOf("jpeg", "raw+jpeg"), CameraCapabilityProfileResolver.pipelineCaptureFormats(
            true, "supported", true,
        ))
    }

    @Test
    fun `JPEG exposure bracket metadata requires three distinct clamped sensor steps`() {
        assertEquals(true, CameraCapabilityProfileResolver.exposureBracketingAvailable(-6, 6, 1.0 / 3))
        assertEquals(true, CameraCapabilityProfileResolver.exposureBracketingAvailable(-1, 1, 1.0))
        assertEquals(false, CameraCapabilityProfileResolver.exposureBracketingAvailable(0, 0, 1.0 / 3))
        assertEquals(false, CameraCapabilityProfileResolver.exposureBracketingAvailable(-6, 6, 5.0))
        assertEquals(false, CameraCapabilityProfileResolver.exposureBracketingAvailable(-6, 6, 0.0))
        assertEquals(null, CameraCapabilityProfileResolver.exposureBracketingAvailable(null, 6, 1.0 / 3))
        assertEquals(null, CameraCapabilityProfileResolver.exposureBracketingAvailable(-6, 6, null))
    }

}
