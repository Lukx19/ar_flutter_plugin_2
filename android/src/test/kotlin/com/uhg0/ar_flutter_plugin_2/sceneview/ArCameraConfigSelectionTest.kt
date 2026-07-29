package com.uhg0.ar_flutter_plugin_2.sceneview

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ArCameraConfigSelectionTest {
    @Test
    fun `camera-id subset prefers supported sixty fps config`() {
        val configs =
            listOf(
                SyntheticCameraConfig("rear", 30),
                SyntheticCameraConfig("rear", 60),
                SyntheticCameraConfig("rear", 30),
            )

        assertEquals(
            60,
            preferHighestFpsConfig(configs, SyntheticCameraConfig::maximumFps)?.maximumFps,
        )
    }

    @Test
    fun `empty camera-id subset has no selection`() {
        assertNull(
            preferHighestFpsConfig(
                emptyList<SyntheticCameraConfig>(),
                SyntheticCameraConfig::maximumFps,
            ),
        )
    }
}

private data class SyntheticCameraConfig(
    val cameraId: String,
    val maximumFps: Int,
)
