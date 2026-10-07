package com.uhg0.ar_flutter_plugin_2.capture

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Canvas
import android.graphics.Paint
import android.media.ExifInterface
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Android codec/storage only; generated pixels are not a Camera2 or visual-quality campaign. */
@RunWith(AndroidJUnit4::class)
class CaptureThumbnailAndroidTest {
    @Test fun maximumSizeOriginalRemainsExactWhileSmallDerivativesApplyAllExifOrientations() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val folder = File(context.cacheDir, "thumbnail-codec-${System.nanoTime()}").apply { check(mkdir()) }
        try {
            val source = File(folder, "original.jpg")
            val bitmap = Bitmap.createBitmap(4128, 3096, Bitmap.Config.RGB_565)
            try {
                val canvas = Canvas(bitmap)
                val paint = Paint()
                for ((bounds, color) in listOf(
                    floatArrayOf(0f, 0f, 2064f, 1548f) to Color.RED,
                    floatArrayOf(2064f, 0f, 4128f, 1548f) to Color.GREEN,
                    floatArrayOf(0f, 1548f, 2064f, 3096f) to Color.BLUE,
                    floatArrayOf(2064f, 1548f, 4128f, 3096f) to Color.YELLOW,
                )) {
                    paint.color = color
                    canvas.drawRect(bounds[0], bounds[1], bounds[2], bounds[3], paint)
                }
                FileOutputStream(source).use { assertTrue(bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it)) }
            } finally { bitmap.recycle() }
            val thumb = File(folder, "thumb.jpg")
            val display = File(folder, "display.jpg")
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            val corners = listOf(
                intArrayOf(Color.RED, Color.GREEN, Color.BLUE, Color.YELLOW),
                intArrayOf(Color.GREEN, Color.RED, Color.YELLOW, Color.BLUE),
                intArrayOf(Color.YELLOW, Color.BLUE, Color.GREEN, Color.RED),
                intArrayOf(Color.BLUE, Color.YELLOW, Color.RED, Color.GREEN),
                intArrayOf(Color.RED, Color.BLUE, Color.GREEN, Color.YELLOW),
                intArrayOf(Color.BLUE, Color.RED, Color.YELLOW, Color.GREEN),
                intArrayOf(Color.YELLOW, Color.GREEN, Color.BLUE, Color.RED),
                intArrayOf(Color.GREEN, Color.YELLOW, Color.RED, Color.BLUE),
            )
            for (orientation in 1..8) {
                ExifInterface(source.absolutePath).apply {
                    setAttribute(ExifInterface.TAG_ORIENTATION, orientation.toString())
                    saveAttributes()
                }
                val before = MessageDigest.getInstance("SHA-256").digest(source.readBytes())
                assertTrue(CaptureThumbnail.write(source, thumb, { false }, 512))
                assertTrue(CaptureThumbnail.valid(thumb, 512))
                val image = requireNotNull(BitmapFactory.decodeFile(thumb.absolutePath))
                try {
                    assertEquals(if (orientation < 5) 512 else 384, image.width)
                    assertEquals(if (orientation < 5) 384 else 512, image.height)
                    val pixels = intArrayOf(image.getPixel(image.width / 4, image.height / 4),
                        image.getPixel(image.width * 3 / 4, image.height / 4),
                        image.getPixel(image.width / 4, image.height * 3 / 4),
                        image.getPixel(image.width * 3 / 4, image.height * 3 / 4))
                    for (index in pixels.indices) for (channel in listOf<(Int) -> Int>(Color::red, Color::green, Color::blue)) {
                        assertTrue(kotlin.math.abs(channel(pixels[index]) - channel(corners[orientation - 1][index])) <= 24)
                    }
                } finally { image.recycle() }
                assertArrayEquals(before, MessageDigest.getInstance("SHA-256").digest(source.readBytes()))
            }
            assertTrue(CaptureThumbnail.write(source, display, { false }, 1024))
            assertTrue(CaptureThumbnail.valid(display, 1024))
            BitmapFactory.decodeFile(display.absolutePath, bounds)
            assertEquals(768, bounds.outWidth); assertEquals(1024, bounds.outHeight)
            BitmapFactory.decodeFile(source.absolutePath, bounds)
            assertEquals(4128, bounds.outWidth); assertEquals(3096, bounds.outHeight)
            val cancelled = File(folder, "cancelled.jpg")
            assertFalse(CaptureThumbnail.write(source, cancelled, { true }, 512))
            assertFalse(cancelled.exists())
        } finally { folder.deleteRecursively() }
    }
}
