package com.uhg0.ar_flutter_plugin_2.sceneview

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Expected values come from Filament 1.72.1 `FColorGrading::hdrColorAt` with
 * SceneView's Filmic tone mapper (sRGB decode, `sRGB_to_Rec2020`, Filmic,
 * `Rec2020_to_sRGB`, clamp, sRGB encode; matrices from `ColorSpaceUtils.h`),
 * evaluated independently in double precision.
 */
class CapturePreviewColorTest {
    @Test
    fun `primaries keep the colors the post-processed preview displayed`() {
        assertArrayEquals(floatArrayOf(1f, 0f, 0f), CapturePreviewColor.displayedRgb(1f, 0f, 0f), 2e-3f)
        assertArrayEquals(floatArrayOf(0.5962f, 0.9213f, 0.1886f), CapturePreviewColor.displayedRgb(0f, 1f, 0f), 2e-3f)
        assertArrayEquals(floatArrayOf(0f, 0f, 0.9410f), CapturePreviewColor.displayedRgb(0f, 0f, 1f), 2e-3f)
        assertArrayEquals(floatArrayOf(0.6139f, 0.9173f, 0.9097f), CapturePreviewColor.displayedRgb(0f, 1f, 1f), 2e-3f)
    }

    @Test
    fun `greys follow the Filmic curve`() {
        assertArrayEquals(floatArrayOf(0.9082f, 0.9082f, 0.9082f), CapturePreviewColor.displayedRgb(1f, 1f, 1f), 2e-3f)
        assertArrayEquals(floatArrayOf(0.6025f, 0.6025f, 0.6025f), CapturePreviewColor.displayedRgb(0.5f, 0.5f, 0.5f), 2e-3f)
        assertArrayEquals(floatArrayOf(0f, 0f, 0f), CapturePreviewColor.displayedRgb(0f, 0f, 0f), 1e-6f)
    }

    @Test
    fun `color ints map to RGBA and keep alpha`() {
        assertArrayEquals(
            CapturePreviewColor.displayedRgb(1f, 0f, 0f) + 1f,
            CapturePreviewColor.displayedRgba(0xFFFF0000.toInt()),
            1e-6f,
        )
        assertArrayEquals(
            CapturePreviewColor.displayedRgb(0f, 1f, 1f) + 1f,
            CapturePreviewColor.displayedRgba(0xFF00FFFF.toInt()),
            1e-6f,
        )
        assertEquals(128f / 255f, CapturePreviewColor.displayedRgba(0x80000000.toInt())[3], 1e-6f)
    }

    @Test
    fun `sRGB transfer functions round trip`() {
        for (code in 0..255) {
            val c = code / 255f
            assertEquals(c, CapturePreviewColor.srgbEncode(CapturePreviewColor.srgbDecode(c)), 1e-4f)
        }
    }
}
