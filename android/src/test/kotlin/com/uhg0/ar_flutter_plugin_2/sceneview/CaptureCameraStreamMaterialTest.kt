package com.uhg0.ar_flutter_plugin_2.sceneview

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class CaptureCameraStreamMaterialTest {
    private val asset = File("src/main/assets", CAPTURE_CAMERA_STREAM_MATERIAL)
    private val source = File("src/main/materials", asset.nameWithoutExtension + ".mat")

    @Test
    fun `capture camera material is a packaged compiled asset with its source`() {
        assertTrue("missing ${asset.path}", asset.isFile && asset.length() > 0)
        assertTrue("missing ${source.path}", source.isFile)
    }

    @Test
    fun `compiled material is a Filament format 72 package of the passthrough source`() {
        val bytes = asset.readBytes()
        // matc writes the chunk tag "MAT_VERS" byte-reversed, then a 32-bit
        // size and the 32-bit little-endian material format version.
        val tag = "SREV_TAM".toByteArray(Charsets.US_ASCII)
        assertTrue("no version chunk", bytes.copyOfRange(0, tag.size).contentEquals(tag))
        val version = (0 until 4).sumOf { (bytes[tag.size + 4 + it].toInt() and 0xff) shl (8 * it) }
        assertEquals(72, version)
        assertTrue(
            "compiled material is not named Capture3D Camera Stream Passthrough",
            String(bytes, Charsets.ISO_8859_1).contains("Capture3D Camera Stream Passthrough"),
        )
        assertTrue(
            "source material is not named Capture3D Camera Stream Passthrough",
            source.readText().contains("name : \"Capture3D Camera Stream Passthrough\""),
        )
    }

    @Test
    fun `passthrough fragment writes the camera texel without tone-curve inversion`() {
        val text = source.readText()
        assertTrue(
            "fragment does not write the camera texel",
            text.contains("material.baseColor.rgb = texture(materialParams_cameraTexture, getUV0()).rgb;"),
        )
        assertFalse("source inverts the tone curve", text.contains("Inverse_Tonemap"))
        listOf("samplerExternal", "cameraTexture", "float4x4", "uvTransform").forEach {
            assertTrue("parameter text $it", text.contains(it))
        }
    }
}
