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

    /** True when the library is present and was built with OpenCV. */
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
