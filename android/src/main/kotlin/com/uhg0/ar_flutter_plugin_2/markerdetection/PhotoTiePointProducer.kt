package com.uhg0.ar_flutter_plugin_2.markerdetection

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import com.uhg0.ar_flutter_plugin_2.visibilitycontract.PhotoSavedLayout
import com.uhg0.ar_flutter_plugin_2.visibilitycontract.contractMagic
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Receives one photo tie-point batch; [batch] is valid during the call. */
internal fun interface TiePointBatchSink {
    fun onTiePointBatch(batch: ByteBuffer, length: Int)
}

/**
 * 64 B header of a photo tie-point batch, followed by 64 B native tie point
 * records (`C3MarkerTiePoint`). Mirrors `TiePointBatchLayout` in
 * `capture3d/lib/capture/visibility_grid/markers/tie_points.dart`.
 */
internal object TiePointBatchLayout {
    val MAGIC = contractMagic("C3TB")
    const val VERSION = 1
    const val HEADER_BYTES = 64
    const val COUNT = 6
    const val PHOTO_ID = 8
    const val PHOTO_ID_BYTES = 32
    const val WIDTH = 40
    const val HEIGHT = 42
    const val GROUP_EPOCH = 44
    const val STATUS = 46
    const val EXPOSURE_TIMESTAMP_NS = 48
    const val TIME_MS = 56

    /** Writes the header for [count] records already in place after it. */
    fun writeHeader(
        b: ByteBuffer,
        count: Int,
        photoId: ByteArray,
        width: Int,
        height: Int,
        groupEpoch: Int,
        exposureTimestampNs: Long,
        status: Int,
        timeMs: Float,
    ): Int {
        for (i in 0 until HEADER_BYTES) b.put(i, 0)
        b.putInt(0, MAGIC)
        b.putShort(4, VERSION.toShort())
        b.putShort(COUNT, count.toShort())
        for (i in 0 until PHOTO_ID_BYTES) b.put(PHOTO_ID + i, photoId[i])
        b.putShort(WIDTH, width.toShort())
        b.putShort(HEIGHT, height.toShort())
        b.putShort(GROUP_EPOCH, groupEpoch.toShort())
        b.putShort(STATUS, status.toShort())
        b.putLong(EXPOSURE_TIMESTAMP_NS, exposureTimestampNs)
        b.putFloat(TIME_MS, timeMs)
        return HEADER_BYTES + count * MarkerDetectorNative.TIE_POINT_BYTES
    }
}

/**
 * Background marker detection on every saved full-resolution photo after it
 * is durable: the JPEG is decoded into one reused RGBA bitmap, converted to
 * grey natively, searched at full resolution and refined with edge lines;
 * corners and centres stay in the photo's raw pixels (lens distortion is
 * modelled during the fit when coefficients are given). Runs on its own
 * thread, one photo at a time, off the capture path (about 80 ms per 12 MP
 * photo on the SM-X700).
 */
internal class PhotoTiePointProducer(
    private val sink: TiePointBatchSink,
    dictionary: Int = MarkerDetectorNative.DICTIONARY_ARUCO_MIP_36H12,
    private val maxRecords: Int = MarkerDetectorNative.MAX_MARKERS,
) : AutoCloseable {
    private val thread = HandlerThread("capture3d-marker-tie-points").apply { start() }
    private val worker = Handler(thread.looper)
    private var handle: Long =
        if (MarkerDetectorNative.available) MarkerDetectorNative.nativeCreate(dictionary) else 0L
    private val batch: ByteBuffer = ByteBuffer
        .allocateDirect(TiePointBatchLayout.HEADER_BYTES + maxRecords * MarkerDetectorNative.TIE_POINT_BYTES)
        .order(ByteOrder.LITTLE_ENDIAN)
    private var bitmap: Bitmap? = null
    private val event = ByteBuffer.allocate(PhotoSavedLayout.BYTES).order(ByteOrder.LITTLE_ENDIAN)
    val timings = DoubleArray(3)

    val available: Boolean get() = handle != 0L
    var photos = 0
        private set
    var failures = 0
        private set

    fun configure(markerSizeMetres: Double, sizesById: Map<Int, Double> = emptyMap()) {
        val h = handle
        if (h == 0L) return
        worker.post {
            MarkerDetectorNative.nativeSetMarkerSize(h, markerSizeMetres)
            for ((id, size) in sizesById) MarkerDetectorNative.nativeSetMarkerSizeForId(h, id, size)
        }
    }

    /**
     * Queues the durable photo [jpegPath] with its 128 B photo saved [event]
     * (still intrinsics and dimensions) and optional distortion (k1, k2, p1,
     * p2, k3, normalised by the still's focal length).
     */
    fun onPhotoDurable(
        photoId: ByteArray,
        eventBytes: ByteArray,
        jpegPath: String,
        groupEpoch: Int,
        distortion: DoubleArray? = null,
    ) {
        if (!available) return
        val id = photoId.copyOf()
        val ev = eventBytes.copyOf()
        worker.post { detect(id, ev, jpegPath, groupEpoch, distortion) }
    }

    private fun detect(photoId: ByteArray, eventBytes: ByteArray, jpegPath: String, groupEpoch: Int, distortion: DoubleArray?) {
        val started = SystemClock.elapsedRealtimeNanos()
        event.clear()
        event.put(eventBytes, 0, PhotoSavedLayout.BYTES)
        val exposure = event.getLong(PhotoSavedLayout.EXPOSURE_TIMESTAMP_NS)
        val decoded = decode(jpegPath)
        if (decoded == null) {
            failures++
            send(photoId, 0, 0, 0, groupEpoch, exposure, MarkerDetectorNative.STATUS_ARGUMENT, started)
            return
        }
        // Intrinsics are given at the still resolution; scale to the decode.
        val stillWidth = (event.getShort(PhotoSavedLayout.WIDTH_DIV_8).toInt() and 0xffff) * 8
        val stillHeight = (event.getShort(PhotoSavedLayout.HEIGHT_DIV_8).toInt() and 0xffff) * 8
        val sx = if (stillWidth > 0) decoded.width.toDouble() / stillWidth else 1.0
        val sy = if (stillHeight > 0) decoded.height.toDouble() / stillHeight else 1.0
        val fx = event.getFloat(PhotoSavedLayout.INTRINSICS) * sx
        val fy = event.getFloat(PhotoSavedLayout.INTRINSICS + 4) * sy
        val cx = (event.getFloat(PhotoSavedLayout.INTRINSICS + 8) + 0.5) * sx - 0.5
        val cy = (event.getFloat(PhotoSavedLayout.INTRINSICS + 12) + 0.5) * sy - 0.5
        MarkerDetectorNative.nativeSetCamera(handle, fx, fy, cx, cy, distortion)
        val count = MarkerDetectorNative.nativeDetectTiePointsBitmap(
            handle,
            decoded,
            1,
            MarkerDetectorNative.REFINE_EDGE_LINES,
            batch,
            TiePointBatchLayout.HEADER_BYTES,
            maxRecords,
        )
        MarkerDetectorNative.nativeTimings(handle, timings)
        if (count < 0) failures++
        send(photoId, maxOf(count, 0), decoded.width, decoded.height, groupEpoch, exposure, minOf(count, 0), started)
    }

    private fun send(
        photoId: ByteArray,
        count: Int,
        width: Int,
        height: Int,
        groupEpoch: Int,
        exposure: Long,
        status: Int,
        startedNs: Long,
    ) {
        val ms = (SystemClock.elapsedRealtimeNanos() - startedNs) / 1e6f
        val length = TiePointBatchLayout.writeHeader(
            batch, count, photoId, width, height, groupEpoch, exposure, -status, ms,
        )
        photos++
        sink.onTiePointBatch(batch, length)
    }

    /** Decodes into the reused bitmap when the size matches (one 48 MB bitmap for 12 MP). */
    private fun decode(path: String): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val reuse = bitmap?.takeIf {
            it.isMutable && it.width == bounds.outWidth && it.height == bounds.outHeight
        }
        val options = BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inMutable = true
            inBitmap = reuse
        }
        val decoded = BitmapFactory.decodeFile(path, options) ?: return null
        if (decoded !== bitmap) {
            bitmap?.recycle()
            bitmap = decoded
        }
        return decoded
    }

    override fun close() {
        worker.post {
            if (handle != 0L) {
                MarkerDetectorNative.nativeDestroy(handle)
                handle = 0L
            }
            bitmap?.recycle()
            bitmap = null
        }
        thread.quitSafely()
    }
}
