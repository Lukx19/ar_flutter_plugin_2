package com.uhg0.ar_flutter_plugin_2.sceneview

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ArCameraConfigSelectionTest {
    @Before
    fun clearCatalogs() {
        ArCameraConfigCatalog.clear()
        RearCameraCatalog.clear()
        OpenArViews.clear()
    }

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

    // A telephoto rear camera beside the default wide camera.
    private val tele = listOf(ArCameraConfigShape(1280, 720, 1280, 720, 30))

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
        assertNull(ArCameraConfigCatalog.shapes("0"))
        ArCameraConfigCatalog.record("0", a40)
        assertEquals(a40, ArCameraConfigCatalog.shapes("0"))
    }

    @Test
    fun `the default camera is looked up by ARCore's default camera id`() {
        // A session on the telephoto camera alone says nothing about the default.
        ArCameraConfigCatalog.record("2", tele)
        assertNull(ArCameraConfigCatalog.shapes(null))
        ArCameraConfigCatalog.record("0", fullHd)
        assertNull(ArCameraConfigCatalog.shapes(null))
        ArCameraConfigCatalog.recordDefaultCameraId("0")
        assertEquals(fullHd, ArCameraConfigCatalog.shapes(null))
        assertEquals(tele, ArCameraConfigCatalog.shapes("2"))
    }

    @Test
    fun `an open AR view never opens a probe session and offers Auto only on a miss`() {
        var probes = 0
        val probe: (String?) -> ArCameraConfigProbe? = { id ->
            probes++
            ArCameraConfigProbe("0", id ?: "0", fullHd)
        }
        OpenArViews.opened(this)
        assertTrue(OpenArViews.any)
        assertTrue(lookUpArCameraResolutions(null, OpenArViews.any, probe).isEmpty())
        assertTrue(lookUpArCameraResolutions("2", OpenArViews.any, probe).isEmpty())
        assertEquals(0, probes)
        // The running session's recorded configs still answer.
        ArCameraConfigCatalog.recordDefaultCameraId("0")
        ArCameraConfigCatalog.record("0", fullHd)
        assertEquals(arCameraResolutionChoices(fullHd), lookUpArCameraResolutions(null, OpenArViews.any, probe))
        assertEquals(0, probes)
        OpenArViews.closed(this)
        assertFalse(OpenArViews.any)
    }

    @Test
    fun `without an open AR view a miss probes once and records the default camera`() {
        val asked = mutableListOf<String?>()
        val probe: (String?) -> ArCameraConfigProbe? = { id ->
            asked += id
            ArCameraConfigProbe("0", id ?: "0", fullHd)
        }
        val choices = lookUpArCameraResolutions(null, arViewOpen = false, probe = probe)
        assertEquals(arCameraResolutionChoices(fullHd), choices)
        assertEquals("0", ArCameraConfigCatalog.defaultCameraId)
        // The recorded configs answer the next lookup, so Settings over a
        // later capture needs no probe.
        assertEquals(choices, lookUpArCameraResolutions(null, arViewOpen = true, probe = probe))
        assertEquals(listOf<String?>(null), asked)
        // A failed probe is unknown and records nothing.
        assertTrue(lookUpArCameraResolutions("2", arViewOpen = false) { null }.isEmpty())
        assertNull(ArCameraConfigCatalog.shapes("2"))
    }

    @Test
    fun `probed tiers never exceed the default`() {
        val choices = lookUpArCameraResolutions(null, arViewOpen = false) {
            ArCameraConfigProbe("0", "0", a40)
        }
        assertEquals(a40[0].toChannelMap(), choices["auto"])
        @Suppress("UNCHECKED_CAST")
        assertTrue((choices["tiers"] as Map<String, Any>).isEmpty())
    }

    @Test
    fun `rear cameras are enumerated only without an open AR view`() {
        val wide = mapOf<String, Any?>("cameraId" to "0", "lensLabel" to "WIDE")
        var enumerations = 0
        val enumerate = { enumerations++; listOf(wide) }
        OpenArViews.opened(this)
        assertTrue(lookUpSelectableRearCameras(OpenArViews.any, enumerate).isEmpty())
        assertEquals(0, enumerations)
        OpenArViews.closed(this)
        assertEquals(listOf(wide), lookUpSelectableRearCameras(OpenArViews.any, enumerate))
        OpenArViews.opened(this)
        // Over a running capture the last enumeration answers.
        assertEquals(listOf(wide), lookUpSelectableRearCameras(OpenArViews.any, enumerate))
        assertEquals(1, enumerations)
        OpenArViews.closed(this)
        // A failed enumeration is none and keeps the last one.
        assertTrue(lookUpSelectableRearCameras(false) { null }.isEmpty())
        assertEquals(listOf(wide), RearCameraCatalog.cameras)
    }
}

private data class SyntheticCameraConfig(
    val cameraId: String,
    val maximumFps: Int,
)
