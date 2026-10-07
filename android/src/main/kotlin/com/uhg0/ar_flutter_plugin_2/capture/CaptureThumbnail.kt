package com.uhg0.ar_flutter_plugin_2.capture

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ColorSpace
import android.graphics.Matrix
import android.media.ExifInterface
import java.io.File
import java.io.FileOutputStream

/** Fixed display limits; durable JPEG/DNG components are never rewritten. */
internal object CaptureThumbnailLimits {
    const val EDGE = 512
    const val DECODE_EDGE = 1024
    const val MAX_FILE_BYTES = 256 * 1024L
    fun sampleSize(width: Int, height: Int, edge: Int = EDGE): Int {
        require(width > 0 && height > 0)
        require(edge == EDGE || edge == 1024)
        var sample = 1
        while ((maxOf(width, height).toLong() + sample - 1) / sample > edge * 2) sample *= 2
        return sample
    }
}

/** Called only by the preview worker, with one bounded bitmap decode at a time. */
internal object CaptureThumbnail {
    fun valid(file: File, edge: Int): Boolean {
        if (edge != 512 && edge != 1024) return false
        if (!file.isFile || file.length() !in 1..(CaptureThumbnailLimits.MAX_FILE_BYTES * (edge / 512))) return false
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        return bounds.outMimeType == "image/jpeg" && bounds.outWidth in 1..edge && bounds.outHeight in 1..edge
    }

    fun write(source: File, target: File, cancelled: () -> Boolean, edge: Int): Boolean {
        if (cancelled()) return false
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(source.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0 || cancelled()) return false
        val options = BitmapFactory.Options().apply {
            inSampleSize = CaptureThumbnailLimits.sampleSize(bounds.outWidth, bounds.outHeight, edge)
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inPreferredColorSpace = ColorSpace.get(ColorSpace.Named.SRGB)
        }
        val decoded = BitmapFactory.decodeFile(source.absolutePath, options) ?: return false
        var scaled: Bitmap? = null
        var oriented: Bitmap? = null
        try {
            if (decoded.width > edge * 2 || decoded.height > edge * 2 || cancelled()) return false
            val scale = minOf(1.0, edge.toDouble() / maxOf(decoded.width, decoded.height))
            val resized = Bitmap.createScaledBitmap(decoded, maxOf(1, (decoded.width * scale).toInt()), maxOf(1, (decoded.height * scale).toInt()), true)
            scaled = resized
            val orientation = runCatching {
                ExifInterface(source.absolutePath).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
            }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
            val matrix = Matrix().apply {
                when (orientation) {
                    ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> setScale(-1f, 1f)
                    ExifInterface.ORIENTATION_ROTATE_180 -> setRotate(180f)
                    ExifInterface.ORIENTATION_FLIP_VERTICAL -> setScale(1f, -1f)
                    ExifInterface.ORIENTATION_TRANSPOSE -> { setRotate(90f); postScale(-1f, 1f) }
                    ExifInterface.ORIENTATION_ROTATE_90 -> setRotate(90f)
                    ExifInterface.ORIENTATION_TRANSVERSE -> { setRotate(270f); postScale(-1f, 1f) }
                    ExifInterface.ORIENTATION_ROTATE_270 -> setRotate(270f)
                }
            }
            val display = Bitmap.createBitmap(resized, 0, 0, resized.width, resized.height, matrix, true)
            oriented = display
            if (cancelled()) return false
            FileOutputStream(target).use { output ->
                check(display.compress(Bitmap.CompressFormat.JPEG, if (edge == 512) 78 else 85, output))
                // Reconstructible display cache; durable originals own their fsync.
            }
            return !cancelled() && valid(target, edge)
        } finally {
            oriented?.takeUnless { it === scaled || it === decoded }?.recycle()
            scaled?.takeUnless { it === decoded }?.recycle()
            decoded.recycle()
        }
    }
}
