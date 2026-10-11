package com.uhg0.ar_flutter_plugin_2.capture
import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.security.MessageDigest

@RunWith(AndroidJUnit4::class)
class HardwareJpegBlurGateAndroidTest {
    private fun jpegScene(motionBlurred: Boolean): ByteArray {
        val width = 4128
        val height = 3096
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        try {
            val row = IntArray(width)
            for (y in 0 until height) {
                for (x in 0 until width) {
                    // A 128-pixel horizontal box blur of the periodic 32-pixel checkerboard
                    // covers two complete periods of this same scene: its exact mean is 127.
                    val value = if (motionBlurred) 127 else if ((x / 32 + y / 32) % 2 == 0) 0 else 255
                    row[x] = Color.rgb(value, value, value)
                }
                bitmap.setPixels(row, 0, width, 0, y, width, 1)
            }
            return ByteArrayOutputStream().use { output ->
                assertTrue(bitmap.compress(Bitmap.CompressFormat.JPEG, 95, output))
                output.toByteArray()
            }
        } finally { bitmap.recycle() }
    }

    private fun analyzeOriginal(jpeg: ByteArray): PreviewBlurQuality {
        val source = ByteBuffer.wrap(jpeg)
        val before = MessageDigest.getInstance("SHA-256").digest(jpeg)
        val quality = JpegBlurAnalyzer.analyzeLaplacianVariance(
            { CameraPlaneInputStreamV2(source.asReadOnlyBuffer()) {} })
        assertEquals(0, source.position())
        assertArrayEquals(before, MessageDigest.getInstance("SHA-256").digest(jpeg))
        assertEquals(516, quality.analyzedWidth)
        assertEquals(387, quality.analyzedHeight)
        assertEquals("jpegLaplacianVarianceV1", quality.algorithm)
        return quality
    }

    @Test fun actualSampledHardwareJpegMetricSeparatesSharpAndDirectionalMotionBlur() {
        val sharp = analyzeOriginal(jpegScene(false))
        val blurred = analyzeOriginal(jpegScene(true))
        assertTrue("Sharp score " + sharp.blurScore, sharp.blurScore >= JpegBlurAnalyzer.ProductionThreshold)
        assertTrue("Motion-blurred score " + blurred.blurScore, blurred.blurScore < JpegBlurAnalyzer.ProductionThreshold)
        val receipt = JSONObject().put("algorithm", sharp.algorithm)
            .put("threshold", JpegBlurAnalyzer.ProductionThreshold)
            .put("originalWidth", 4128).put("originalHeight", 3096)
            .put("analyzedWidth", sharp.analyzedWidth).put("analyzedHeight", sharp.analyzedHeight)
            .put("sharpScore", sharp.blurScore).put("motionBlurredScore", blurred.blurScore)
        val cache = InstrumentationRegistry.getInstrumentation().targetContext.cacheDir
        cache.resolve("hardware-jpeg-blur-decoder.json").writeText(receipt.toString())
    }
}
