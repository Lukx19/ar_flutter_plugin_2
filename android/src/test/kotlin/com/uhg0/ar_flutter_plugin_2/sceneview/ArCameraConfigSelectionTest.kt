package com.uhg0.ar_flutter_plugin_2.sceneview

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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

    // Galaxy A40 rear camera as measured: the default is the 640x480 CPU image
    // over a 1080p texture; the only other config is a larger CPU image.
    private val a40 = listOf(
        ArCameraConfigShape(640, 480, 1920, 1080, 30),
        ArCameraConfigShape(1920, 1080, 1920, 1080, 30),
    )

    // A device whose default is Full HD with smaller configs below it.
    private val fullHd = listOf(
        ArCameraConfigShape(1920, 1080, 1920, 1080, 30),
        ArCameraConfigShape(1280, 720, 1920, 1080, 30),
        ArCameraConfigShape(1280, 720, 1280, 720, 30),
        ArCameraConfigShape(640, 480, 1280, 720, 30),
    )

    @Test
    fun `a tier never raises the CPU image or texture above the default`() {
        assertEquals(a40[0], selectConfigForMaxImageHeight(a40, 0) { it })
        // 1080p would raise the CPU image to 1920x1080: the default stays.
        assertEquals(a40[0], selectConfigForMaxImageHeight(a40, 1080) { it })
        assertEquals(a40[0], selectConfigForMaxImageHeight(a40, 720) { it })
        assertEquals(a40[0], selectConfigForMaxImageHeight(a40, 480) { it })
    }

    @Test
    fun `a tier prefers a texture that fits, then the larger image, then the smaller texture`() {
        assertEquals(fullHd[0], selectConfigForMaxImageHeight(fullHd, 0) { it })
        assertEquals(fullHd[0], selectConfigForMaxImageHeight(fullHd, 1080) { it })
        assertEquals(fullHd[2], selectConfigForMaxImageHeight(fullHd, 720) { it })
        assertEquals(fullHd[3], selectConfigForMaxImageHeight(fullHd, 480) { it })
        // Nothing fits 360 rows: the default choice stays.
        assertEquals(fullHd[0], selectConfigForMaxImageHeight(fullHd, 360) { it })
    }

    @Test
    fun `settings list only tiers that lower the session`() {
        // The A40 offers nothing below its default.
        @Suppress("UNCHECKED_CAST")
        assertTrue((arCameraResolutionChoices(a40)["tiers"] as Map<String, Any>).isEmpty())
        val choices = arCameraResolutionChoices(fullHd)
        assertEquals(fullHd[0].toChannelMap(), choices["auto"])
        @Suppress("UNCHECKED_CAST")
        val tiers = choices["tiers"] as Map<String, Any>
        assertEquals(listOf("720", "480"), tiers.keys.toList())
        assertEquals(fullHd[2].toChannelMap(), tiers["720"])
        assertTrue(arCameraResolutionChoices(emptyList()).isEmpty())
    }

    @Test
    fun `the catalog answers for the last session's camera`() {
        ArCameraConfigCatalog.clear()
        assertNull(ArCameraConfigCatalog.shapes("0"))
        ArCameraConfigCatalog.record("0", a40)
        assertEquals(a40, ArCameraConfigCatalog.shapes("0"))
        assertEquals(a40, ArCameraConfigCatalog.shapes(null))
        ArCameraConfigCatalog.clear()
    }
}

private data class SyntheticCameraConfig(
    val cameraId: String,
    val maximumFps: Int,
)
