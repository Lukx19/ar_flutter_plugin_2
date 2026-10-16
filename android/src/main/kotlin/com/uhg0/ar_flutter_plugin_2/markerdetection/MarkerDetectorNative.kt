package com.uhg0.ar_flutter_plugin_2.markerdetection

import android.graphics.Bitmap
import java.nio.ByteBuffer

/**
 * JNI entry points of `libcapture3d_marker_detector.so` (C ABI in
 * `native/marker_detector/include/marker_detector.h`). The library is only
 * packaged when the marker detector is built (CAPTURE3D_GRID_MARKERS);
 * [loaded] is false otherwise and nothing else here may be called.
 */
internal object MarkerDetectorNative {
    const val LIBRARY = "capture3d_marker_detector"

    const val STATUS_OK = 0
    const val STATUS_ARGUMENT = -1
    const val STATUS_UNAVAILABLE = -2

    const val DICTIONARY_ARUCO_MIP_36H12 = 0
    const val MAX_MARKERS = 128
    const val TIE_POINT_BYTES = 64

    const val REFINE_NONE = 0
    const val REFINE_CORNER_SUBPIX = 1
    const val REFINE_EDGE_LINES = 2

    val loaded: Boolean by lazy {
        try {
            System.loadLibrary(LIBRARY)
            true
        } catch (_: UnsatisfiedLinkError) {
            false
        }
    }

    /** True when the library is present and was built with its AprilTag backend. */
    val available: Boolean by lazy { loaded && nativeAvailable() == 1 }

    @JvmStatic external fun nativeAvailable(): Int

    @JvmStatic external fun nativeCreate(dictionary: Int): Long

    @JvmStatic external fun nativeDestroy(handle: Long)

    @JvmStatic external fun nativeSetCamera(
        handle: Long,
        fx: Double,
        fy: Double,
        cx: Double,
        cy: Double,
        distortion: DoubleArray?,
    ): Int

    @JvmStatic external fun nativeSetMarkerSize(handle: Long, sizeMetres: Double): Int

    @JvmStatic external fun nativeSetMarkerSizeForId(handle: Long, markerId: Int, sizeMetres: Double): Int

    @JvmStatic external fun nativeSetQuality(
        handle: Long,
        minSidePx: Float,
        minSharpness: Float,
        maxViewingAngleDeg: Float,
    ): Int

    /**
     * Detects on a grey image (direct buffer), estimates poses and copies up to
     * [maxRecords] 64 B detection records into [out] at [outOffset].
     */
    @JvmStatic external fun nativeDetect(
        handle: Long,
        grey: ByteBuffer,
        width: Int,
        height: Int,
        stride: Int,
        out: ByteBuffer,
        outOffset: Int,
        maxRecords: Int,
    ): Int

    /** Full-resolution tie points of an RGBA_8888 bitmap into [out] (64 B records). */
    @JvmStatic external fun nativeDetectTiePointsBitmap(
        handle: Long,
        bitmap: Bitmap,
        searchDownscale: Int,
        refineMethod: Int,
        out: ByteBuffer,
        outOffset: Int,
        maxRecords: Int,
    ): Int

    /** Detect, pose and refine milliseconds of the last calls (3 doubles). */
    @JvmStatic external fun nativeTimings(handle: Long, out: DoubleArray)
}

/**
 * Creates and destroys native detector handles. A detector holds the AprilTag
 * quick-decode table of its dictionary (about 8 MB for 36h12), so producers
 * create one on first use, never at construction. [NATIVE] is the JNI
 * library; host tests substitute a counting fake.
 */
internal interface MarkerDetectorFactory {
    /** True when [create] can return a detector (library packaged with its backend). */
    val available: Boolean

    /** A new detector handle for [dictionary], 0 on failure. */
    fun create(dictionary: Int): Long

    fun destroy(handle: Long)

    companion object {
        val NATIVE: MarkerDetectorFactory = object : MarkerDetectorFactory {
            override val available: Boolean get() = MarkerDetectorNative.available
            override fun create(dictionary: Int): Long = MarkerDetectorNative.nativeCreate(dictionary)
            override fun destroy(handle: Long) = MarkerDetectorNative.nativeDestroy(handle)
        }
    }
}

/**
 * One lazily created native detector: [handle] creates it on the first call
 * (on the owner's worker thread) and a failed creation is not retried.
 * [available] stays true until creation failed or [close].
 */
internal class LazyMarkerDetector(
    private val factory: MarkerDetectorFactory,
    private val dictionary: Int,
) {
    @Volatile private var closed = false
    @Volatile private var createFailed = false
    private var created = 0L

    val available: Boolean get() = !closed && !createFailed && factory.available

    /** True once the native detector exists. */
    val isCreated: Boolean get() = created != 0L

    /** The detector, created on first use; 0 when unavailable, failed or closed. */
    fun handle(): Long {
        if (created != 0L || !available) return created
        created = factory.create(dictionary)
        if (created == 0L) createFailed = true
        return created
    }

    /** Destroys the detector if it was created; later calls get 0. */
    fun close() {
        closed = true
        val h = created
        created = 0L
        if (h != 0L) factory.destroy(h)
    }
}
