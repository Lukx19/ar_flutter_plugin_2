package com.uhg0.ar_flutter_plugin_2.sceneview

import com.google.android.filament.Colors
import com.google.android.filament.MaterialInstance
import io.github.sceneview.loaders.MaterialLoader
import kotlin.math.pow

/**
 * Colors of unlit Filament content on the capture preview, which runs without
 * post-processing ([configureCapturePresentation]).
 *
 * With post-processing on, Filament 1.72.1 color grading (SceneView's
 * `ToneMapper.Filmic`, default `ToneMapping` enum, no adjustments, 3D LUT)
 * displayed an unlit sRGB color as: sRGB decode, sRGB to Rec.2020, Filmic per
 * channel, Rec.2020 to sRGB, clamp, sRGB encode (`FColorGrading::hdrColorAt`).
 * Pure red stayed (255, 0, 0), green showed as about (152, 235, 48). Without
 * post-processing Filament writes the linear value unchanged to the non-sRGB
 * swap chain, so [displayedRgba] returns that displayed value to write as the
 * linear color.
 */
internal object CapturePreviewColor {
    // Filament ColorSpaceUtils.h (1.72.1), row-major here:
    // sRGB_to_Rec2020 = XYZ_to_Rec2020 * sRGB_to_XYZ, Rec2020_to_sRGB = XYZ_to_sRGB * Rec2020_to_XYZ.
    private val srgbToRec2020 = multiply(
        floatArrayOf(1.7166634f, -0.3556733f, -0.2533681f, -0.6666738f, 1.6164557f, 0.0157683f, 0.0176425f, -0.0427770f, 0.9422433f),
        floatArrayOf(0.4124560f, 0.3575760f, 0.1804380f, 0.2126730f, 0.7151520f, 0.0721750f, 0.0193339f, 0.1191920f, 0.9503040f),
    )
    private val rec2020ToSrgb = multiply(
        floatArrayOf(3.2404542f, -1.5371385f, -0.4985314f, -0.9692660f, 1.8760108f, 0.0415560f, 0.0556434f, -0.2040259f, 1.0572252f),
        floatArrayOf(0.6369530f, 0.1446169f, 0.1688558f, 0.2626983f, 0.6780088f, 0.0592929f, 0.0000000f, 0.0280731f, 1.0608272f),
    )

    /** sRGB color (0..1 channels) as the post-processed preview displayed it, sRGB encoded. */
    fun displayedRgb(r: Float, g: Float, b: Float): FloatArray {
        val linear = floatArrayOf(srgbDecode(r), srgbDecode(g), srgbDecode(b))
        val toneMapped = transform(srgbToRec2020, linear).map(::filmic).toFloatArray()
        return transform(rec2020ToSrgb, toneMapped).map { srgbEncode(it.coerceIn(0f, 1f)) }.toFloatArray()
    }

    /** RGBA (0..1) to write as linear color for an Android sRGB color int. */
    fun displayedRgba(color: Int): FloatArray {
        val rgb = displayedRgb(
            ((color shr 16) and 0xff) / 255f,
            ((color shr 8) and 0xff) / 255f,
            (color and 0xff) / 255f,
        )
        return floatArrayOf(rgb[0], rgb[1], rgb[2], ((color ushr 24) and 0xff) / 255f)
    }

    /** Filament's `FilmicToneMapper` (Narkowicz 2015 ACES fit). */
    internal fun filmic(x: Float): Float {
        val v = x.coerceAtLeast(0f)
        return (v * (2.51f * v + 0.03f)) / (v * (2.43f * v + 0.59f) + 0.14f)
    }

    internal fun srgbDecode(c: Float): Float {
        val v = c.coerceIn(0f, 1f)
        return if (v <= 0.04045f) v / 12.92f else ((v + 0.055f) / 1.055f).pow(2.4f)
    }

    internal fun srgbEncode(l: Float): Float =
        if (l <= 0.0031308f) l * 12.92f else 1.055f * l.coerceIn(0f, 1f).pow(1f / 2.4f) - 0.055f

    private fun multiply(a: FloatArray, b: FloatArray): FloatArray =
        FloatArray(9) { i -> (0 until 3).sumOf { k -> (a[i / 3 * 3 + k] * b[k * 3 + i % 3]).toDouble() }.toFloat() }

    private fun transform(m: FloatArray, v: FloatArray): FloatArray =
        FloatArray(3) { row -> m[row * 3] * v[0] + m[row * 3 + 1] * v[1] + m[row * 3 + 2] * v[2] }
}

/**
 * SceneView unlit color instance whose color matches the post-processed
 * preview it replaced (see [CapturePreviewColor]). SceneView 4.47.0's
 * `createUnlitColorInstance` sets the material's `color` parameter as sRGB;
 * this overrides it with the displayed value as linear.
 */
internal fun MaterialLoader.createCapturePreviewUnlitColorInstance(color: Int): MaterialInstance =
    createUnlitColorInstance(color).also { instance ->
        val rgba = CapturePreviewColor.displayedRgba(color)
        instance.setParameter("color", Colors.RgbaType.LINEAR, rgba[0], rgba[1], rgba[2], rgba[3])
    }
