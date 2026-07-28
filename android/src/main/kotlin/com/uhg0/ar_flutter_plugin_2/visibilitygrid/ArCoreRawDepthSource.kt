package com.uhg0.ar_flutter_plugin_2.visibilitygrid

import android.media.Image
import com.google.ar.core.Frame
import com.google.ar.core.TrackingState
import com.google.ar.core.exceptions.NotYetAvailableException
import java.nio.ByteOrder

class ArCoreRawDepthSource(
    private val maxCopiedPixels: Int = 4_096,
) {
    fun acquire(
        frame: Frame,
        groupGeneration: Long,
        sessionGeneration: Long,
    ): DepthAcquisitionResult {
        val source =
            RawDepthCopySource(
                acquirer = ArCorePairedRawDepthAcquirer(frame),
                maxCopiedPixels = maxCopiedPixels,
            )
        return source.acquire { depthWidth, depthHeight ->
            val imageIntrinsics = frame.camera.imageIntrinsics
            val focal = imageIntrinsics.focalLength
            val principal = imageIntrinsics.principalPoint
            val imageDimensions = imageIntrinsics.imageDimensions
            val scaleX = depthWidth.toDouble() / imageDimensions[0]
            val scaleY = depthHeight.toDouble() / imageDimensions[1]
            val pose = FloatArray(16)
            frame.camera.pose.toMatrix(pose, 0)
            RawDepthFrameMetadata(
                timestampNs = frame.timestamp,
                groupGeneration = groupGeneration,
                sessionGeneration = sessionGeneration,
                tracking = frame.camera.trackingState == TrackingState.TRACKING,
                width = depthWidth,
                height = depthHeight,
                intrinsics =
                    DepthIntrinsics(
                        fx = focal[0] * scaleX,
                        fy = focal[1] * scaleY,
                        cx = principal[0] * scaleX,
                        cy = principal[1] * scaleY,
                    ),
                worldFromCameraGl = DoubleArray(16) { pose[it].toDouble() },
                imageOrientation = DepthImageOrientation.LANDSCAPE_RIGHT,
            )
        }
    }
}

private class ArCorePairedRawDepthAcquirer(
    private val frame: Frame,
) : PairedRawDepthAcquirer {
    override fun acquireDepth(): RawDepthImage =
        acquireImage { frame.acquireRawDepthImage16Bits() }

    override fun acquireConfidence(): RawDepthImage =
        acquireImage { frame.acquireRawDepthConfidenceImage() }

    private fun acquireImage(block: () -> Image): RawDepthImage =
        try {
            val image = block()
            try {
                AndroidRawDepthImage(image)
            } catch (error: RuntimeException) {
                image.close()
                throw error
            }
        } catch (_: NotYetAvailableException) {
            throw DepthNotYetAvailableException()
        }
}

private class AndroidRawDepthImage(
    private val image: Image,
) : RawDepthImage {
    private val plane =
        image.planes.singleOrNull()
            ?: error("raw depth image must have exactly one plane")
    private val buffer = plane.buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN)

    override val width: Int = image.width
    override val height: Int = image.height

    override fun unsignedValue(
        x: Int,
        y: Int,
    ): Int {
        require(x in 0 until width && y in 0 until height)
        val offset = y * plane.rowStride + x * plane.pixelStride
        return if (plane.pixelStride == 1) {
            buffer.get(offset).toInt() and 0xff
        } else {
            buffer.getShort(offset).toInt() and 0xffff
        }
    }

    override fun close() {
        image.close()
    }
}
