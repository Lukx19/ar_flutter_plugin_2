package com.uhg0.ar_flutter_plugin_2.capture

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.ExifInterface
import java.io.File

/** Only the already bounded, authorized display JPEG enters this reader. */
internal object GuidancePreviewLuma {
    fun read(path: String, cancelled: () -> Boolean): Map<String, Any>? {
        val file = File(path)
        if (cancelled() || !CaptureThumbnail.valid(file, 512)) return null
        val descriptor = ExifInterface(path).getAttribute(ExifInterface.TAG_IMAGE_DESCRIPTION) ?: return null
        if (descriptor.length > 80) return null
        val fields = descriptor.split(':')
        if (fields.size != 4 || fields[0] != "guidance_preview_v1") return null
        val sourceWidth = fields[1].toIntOrNull() ?: return null
        val sourceHeight = fields[2].toIntOrNull() ?: return null
        val orientation = fields[3].toIntOrNull() ?: return null
        if (sourceWidth !in 1..65536 || sourceHeight !in 1..65536 || orientation !in 1..8) return null
        val decoded = BitmapFactory.decodeFile(path) ?: return null
        var scaled: Bitmap? = null
        try {
            if (decoded.width > 512 || decoded.height > 512 || cancelled()) return null
            val scale = minOf(1.0, 256.0 / maxOf(decoded.width, decoded.height))
            val width = maxOf(1, (decoded.width * scale).toInt())
            val height = maxOf(1, (decoded.height * scale).toInt())
            val image = Bitmap.createScaledBitmap(decoded, width, height, true)
            scaled = image
            val pixels = IntArray(width)
            val luma = ByteArray(width * height)
            for (y in 0 until height) {
                if (cancelled()) return null
                image.getPixels(pixels, 0, width, 0, y, width, 1)
                for (x in 0 until width) {
                    val pixel = pixels[x]
                    luma[y * width + x] = (((pixel shr 16 and 255) * 77 + (pixel shr 8 and 255) * 150 + (pixel and 255) * 29) shr 8).toByte()
                }
            }
            return mapOf("wireVersion" to "guidance_luma_v1", "width" to width, "height" to height,
                "sourceWidth" to sourceWidth, "sourceHeight" to sourceHeight, "exifOrientation" to orientation, "luma" to luma)
        } finally {
            scaled?.takeUnless { it === decoded }?.recycle()
            decoded.recycle()
        }
    }
}
