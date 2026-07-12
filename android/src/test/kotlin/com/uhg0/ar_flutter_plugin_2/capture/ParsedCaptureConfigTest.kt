package com.uhg0.ar_flutter_plugin_2.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ParsedCaptureConfigTest {
    @Test
    fun `fromMap accepts jpeg shared-camera configuration`() {
        val config =
            ParsedCaptureConfig.fromMap(
                mapOf(
                    "enableHighResCapture" to true,
                    "captureIntervalMs" to 0,
                    "resolution" to mapOf("width" to 640, "height" to 480),
                    "format" to "jpeg",
                    "maxCacheSize" to 4,
                    "jpegQuality" to 95,
                    "autoExposure" to true,
                    "autoWhiteBalance" to true,
                    "enablePoseStream" to true,
                    "bufferStrategy" to "balanced",
                ),
            )

        assertEquals(android.graphics.ImageFormat.YUV_420_888, config.format)
        assertTrue(config.enableHighResCapture)
        assertTrue(config.processed)
    }

    @Test
    fun `fromMap accepts raw only sensor configuration`() {
        val config =
            ParsedCaptureConfig.fromMap(
                mapOf(
                    "enableHighResCapture" to true,
                    "captureIntervalMs" to 0,
                    "resolution" to mapOf("width" to 640, "height" to 480),
                    "format" to "raw",
                    "maxCacheSize" to 4,
                    "jpegQuality" to 95,
                    "autoExposure" to true,
                    "autoWhiteBalance" to true,
                    "enablePoseStream" to true,
                    "bufferStrategy" to "balanced",
                ),
            )
        assertEquals(android.graphics.ImageFormat.RAW_SENSOR, config.format)
        assertTrue(config.rawOnly)
        assertTrue(!config.processed)
    }

    @Test
    fun `fromMap accepts explicit paired raw jpeg mode`() {
        val config =
            ParsedCaptureConfig.fromMap(
                mapOf(
                    "enableHighResCapture" to true,
                    "captureIntervalMs" to 0,
                    "resolution" to mapOf("width" to 640, "height" to 480),
                    "format" to "raw+jpeg",
                    "maxCacheSize" to 4,
                    "jpegQuality" to 95,
                    "autoExposure" to true,
                    "autoWhiteBalance" to true,
                    "enablePoseStream" to true,
                    "bufferStrategy" to "balanced",
                ),
            )

        assertEquals(android.graphics.ImageFormat.JPEG, config.format)
        assertTrue(config.rawJpeg)
        assertTrue(!config.processed)
    }

    @Test
    fun `fromMap accepts lossless png backed by yuv`() {
        val config =
            ParsedCaptureConfig.fromMap(
                mapOf(
                    "enableHighResCapture" to true,
                    "resolution" to mapOf("width" to 640, "height" to 480),
                    "format" to "png",
                ),
            )

        assertEquals(android.graphics.ImageFormat.YUV_420_888, config.format)
        assertTrue(config.png)
    }
}
