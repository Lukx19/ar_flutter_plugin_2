package com.uhg0.ar_flutter_plugin_2.visibilityingest

import com.google.ar.core.Config
import com.google.ar.core.Frame
import com.google.ar.core.TrackingFailureReason
import com.google.ar.core.TrackingState
import com.google.ar.core.exceptions.NotYetAvailableException
import java.nio.ByteBuffer
import java.nio.FloatBuffer
import java.nio.IntBuffer

/**
 * The parts of one AR frame the ingest producers read, as primitives, so the
 * producers run in JVM tests with a fake frame. The ARCore implementation is
 * [ArCoreIngestFrame]; one instance is rebound to every frame.
 */
internal interface IngestFrameSource {
    /** ARCore frame timestamp (the depth image and point cloud clock). */
    val timestampNs: Long

    /** Camera2 sensor timestamp of the frame's camera image (the still exposure clock). */
    val androidCameraTimestampNs: Long

    /** One of [IngestTrackingState]. */
    val trackingState: Int

    /** ARCore `TrackingFailureReason` ordinal (0 none, 1 bad state, 2 light, 3 motion, 4 features, 5 camera). */
    val trackingFailureReason: Int get() = 0

    /** Writes the sensor-oriented camera pose as tx, ty, tz, qx, qy, qz, qw. */
    fun readPose(out: FloatArray)

    /** Writes texture intrinsics fx, fy, cx, cy, width, height; false when unavailable. */
    fun readTextureIntrinsics(out: FloatArray): Boolean

    /** Acquires the depth image of [mode] and passes it to [sink]; returns one of the `DEPTH_` codes. */
    fun acquireDepth(mode: Int, sink: DepthImageSink): Int

    /** Acquires the point cloud and passes it to [sink]; false when unavailable. */
    fun acquirePointCloud(sink: PointCloudSink): Boolean

    companion object {
        const val DEPTH_MODE_DISABLED = 0
        const val DEPTH_MODE_AUTOMATIC = 1
        const val DEPTH_MODE_RAW = 2

        const val DEPTH_OK = 0
        const val DEPTH_NOT_YET_AVAILABLE = 1
        const val DEPTH_FAILED = 2

        fun depthModeCode(mode: Config.DepthMode?): Int = when (mode) {
            Config.DepthMode.AUTOMATIC -> DEPTH_MODE_AUTOMATIC
            Config.DepthMode.RAW_DEPTH_ONLY -> DEPTH_MODE_RAW
            else -> DEPTH_MODE_DISABLED
        }
    }
}

/** Receives one acquired depth image; buffers are valid only during the call. */
internal fun interface DepthImageSink {
    /**
     * [depth] holds u16 millimetres with [rowStride] and [pixelStride] bytes;
     * [confidence] (raw depth only) holds u8 values with its own strides, or
     * is null.
     */
    fun onDepthImage(
        imageTimestampNs: Long,
        width: Int,
        height: Int,
        depth: ByteBuffer,
        rowStride: Int,
        pixelStride: Int,
        confidence: ByteBuffer?,
        confidenceRowStride: Int,
        confidencePixelStride: Int,
    )
}

/** Receives one point cloud; buffers are valid only during the call. */
internal fun interface PointCloudSink {
    /** [points] holds x, y, z, confidence per point (world space); [ids] one id per point. */
    fun onPointCloud(timestampNs: Long, points: FloatBuffer, ids: IntBuffer)
}

/** [IngestFrameSource] over an ARCore [Frame]; rebound per frame on the AR thread. */
internal class ArCoreIngestFrame : IngestFrameSource {
    var frame: Frame? = null
    private val focal = FloatArray(2)
    private val principal = FloatArray(2)
    private val dimensions = IntArray(2)
    private val translation = FloatArray(3)
    private val rotation = FloatArray(4)

    private val current: Frame get() = checkNotNull(frame)

    override val timestampNs: Long get() = current.timestamp
    override val androidCameraTimestampNs: Long get() = current.androidCameraTimestamp
    override val trackingState: Int
        get() = when (current.camera.trackingState) {
            TrackingState.TRACKING -> IngestTrackingState.TRACKING
            TrackingState.PAUSED -> IngestTrackingState.PAUSED
            else -> IngestTrackingState.STOPPED
        }
    override val trackingFailureReason: Int
        get() = when (current.camera.trackingFailureReason) {
            TrackingFailureReason.NONE, null -> 0
            TrackingFailureReason.BAD_STATE -> 1
            TrackingFailureReason.INSUFFICIENT_LIGHT -> 2
            TrackingFailureReason.EXCESSIVE_MOTION -> 3
            TrackingFailureReason.INSUFFICIENT_FEATURES -> 4
            TrackingFailureReason.CAMERA_UNAVAILABLE -> 5
            else -> 0
        }

    override fun readPose(out: FloatArray) {
        val pose = current.camera.pose
        pose.getTranslation(translation, 0)
        pose.getRotationQuaternion(rotation, 0)
        out[0] = translation[0]
        out[1] = translation[1]
        out[2] = translation[2]
        out[3] = rotation[0]
        out[4] = rotation[1]
        out[5] = rotation[2]
        out[6] = rotation[3]
    }

    override fun readTextureIntrinsics(out: FloatArray): Boolean {
        val intrinsics = current.camera.textureIntrinsics
        intrinsics.getFocalLength(focal, 0)
        intrinsics.getPrincipalPoint(principal, 0)
        intrinsics.getImageDimensions(dimensions, 0)
        out[0] = focal[0]
        out[1] = focal[1]
        out[2] = principal[0]
        out[3] = principal[1]
        out[4] = dimensions[0].toFloat()
        out[5] = dimensions[1].toFloat()
        return dimensions[0] > 0 && dimensions[1] > 0
    }

    override fun acquireDepth(mode: Int, sink: DepthImageSink): Int {
        val f = current
        try {
            when (mode) {
                IngestFrameSource.DEPTH_MODE_AUTOMATIC -> f.acquireDepthImage16Bits().use { image ->
                    val plane = image.planes[0]
                    sink.onDepthImage(image.timestamp, image.width, image.height, plane.buffer, plane.rowStride,
                        plane.pixelStride, null, 0, 0)
                }
                IngestFrameSource.DEPTH_MODE_RAW -> f.acquireRawDepthImage16Bits().use { image ->
                    f.acquireRawDepthConfidenceImage().use { confidence ->
                        val plane = image.planes[0]
                        val c = confidence.planes[0]
                        val paired = confidence.width == image.width && confidence.height == image.height
                        sink.onDepthImage(image.timestamp, image.width, image.height, plane.buffer, plane.rowStride,
                            plane.pixelStride, if (paired) c.buffer else null, c.rowStride, c.pixelStride)
                    }
                }
                else -> return IngestFrameSource.DEPTH_FAILED
            }
            return IngestFrameSource.DEPTH_OK
        } catch (_: NotYetAvailableException) {
            return IngestFrameSource.DEPTH_NOT_YET_AVAILABLE
        } catch (_: RuntimeException) {
            return IngestFrameSource.DEPTH_FAILED
        }
    }

    override fun acquirePointCloud(sink: PointCloudSink): Boolean =
        try {
            current.acquirePointCloud().use { cloud ->
                sink.onPointCloud(cloud.timestamp, cloud.points, cloud.ids)
            }
            true
        } catch (_: RuntimeException) {
            false
        }
}
