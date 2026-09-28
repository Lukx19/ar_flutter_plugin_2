package com.uhg0.ar_flutter_plugin_2.visibilitygrid

import android.media.Image
import com.google.ar.core.Frame
import com.google.ar.core.TrackingState
import com.google.ar.core.exceptions.NotYetAvailableException
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean

internal sealed interface PreparedRawDepthResult {
    data class Ready(val frame: PreparedRawDepthFrame) : PreparedRawDepthResult
    data object TransientUnavailable : PreparedRawDepthResult
    data class Failure(val reason: String) : PreparedRawDepthResult
}

internal class PreparedRawDepthFrame(
    val metadata: RawDepthFrameMetadata,
    private val depth: RawDepthImage,
    private val confidence: RawDepthImage,
    private val maxCopiedPixels: Int,
) : AutoCloseable {
    private val processing = AtomicBoolean(false)

    fun process(): DepthAcquisitionResult {
        val source = RawDepthCopySource(
            acquirer = object : PairedRawDepthAcquirer {
                override fun acquireDepth() = depth
                override fun acquireConfidence() = confidence
            },
            maxCopiedPixels = maxCopiedPixels,
        )
        check(processing.compareAndSet(false, true))
        // The copy source owns both closes after processing starts.
        return source.acquire(metadata)
    }

    override fun close() {
        // A refused offer never reaches process(); only that path closes here.
        if (!processing.compareAndSet(false, true)) return
        try {
            confidence.close()
        } finally {
            depth.close()
        }
    }
}

class ArCoreRawDepthSource(
    private val maxCopiedPixels: Int = 4_096,
    private val onResourceAcquired: () -> Unit = {},
    private val onResourceClosed: () -> Unit = {},
) {
    fun acquire(
        frame: Frame,
        groupGeneration: Long,
        sessionGeneration: Long,
    ): DepthAcquisitionResult {
        val source = RawDepthCopySource(
            acquirer = ArCorePairedRawDepthAcquirer(frame),
            maxCopiedPixels = maxCopiedPixels,
            onResourceAcquired = onResourceAcquired,
            onResourceClosed = onResourceClosed,
        )
        return source.acquire { width, height ->
            metadata(frame, groupGeneration, sessionGeneration, width, height)
        }
    }

    /** Acquires both ARCore images on the current frame; selection happens on a worker. */
    internal fun prepare(
        frame: Frame,
        groupGeneration: Long,
        sessionGeneration: Long,
    ): PreparedRawDepthResult {
        val acquirer = ArCorePairedRawDepthAcquirer(frame)
        var depth: RawDepthImage? = null
        var confidence: RawDepthImage? = null
        var accepted = false
        return try {
            depth = ManagedRawDepthImage(acquirer.acquireDepth(), onResourceClosed)
            onResourceAcquired()
            confidence = ManagedRawDepthImage(acquirer.acquireConfidence(), onResourceClosed)
            onResourceAcquired()
            if (depth.width != confidence.width || depth.height != confidence.height) {
                PreparedRawDepthResult.Failure("mismatched depth image dimensions")
            } else {
                val metadata = metadata(
                    frame, groupGeneration, sessionGeneration, depth.width, depth.height,
                )
                accepted = true
                PreparedRawDepthResult.Ready(
                    PreparedRawDepthFrame(metadata, depth, confidence, maxCopiedPixels),
                )
            }
        } catch (_: DepthNotYetAvailableException) {
            PreparedRawDepthResult.TransientUnavailable
        } catch (error: RuntimeException) {
            PreparedRawDepthResult.Failure(error.message ?: error.javaClass.simpleName)
        } finally {
            if (!accepted) {
                try {
                    confidence?.close()
                } finally {
                    depth?.close()
                }
            }
        }
    }

    private fun metadata(
        frame: Frame,
        groupGeneration: Long,
        sessionGeneration: Long,
        depthWidth: Int,
        depthHeight: Int,
    ): RawDepthFrameMetadata {
        val imageIntrinsics = frame.camera.imageIntrinsics
        val focal = imageIntrinsics.focalLength
        val principal = imageIntrinsics.principalPoint
        val imageDimensions = imageIntrinsics.imageDimensions
        val scaleX = depthWidth.toDouble() / imageDimensions[0]
        val scaleY = depthHeight.toDouble() / imageDimensions[1]
        val pose = FloatArray(16)
        frame.camera.pose.toMatrix(pose, 0)
        return RawDepthFrameMetadata(
            timestampNs = frame.timestamp,
            groupGeneration = groupGeneration,
            sessionGeneration = sessionGeneration,
            tracking = frame.camera.trackingState == TrackingState.TRACKING,
            width = depthWidth,
            height = depthHeight,
            intrinsics = DepthIntrinsics(
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

private class ManagedRawDepthImage(
    private val delegate: RawDepthImage,
    private val onClosed: () -> Unit,
) : RawDepthImage {
    private val closed = AtomicBoolean(false)
    override val width get() = delegate.width
    override val height get() = delegate.height
    override fun unsignedValue(x: Int, y: Int) = delegate.unsignedValue(x, y)
    override fun close() {
        if (closed.compareAndSet(false, true)) {
            delegate.close()
            onClosed()
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
    private val plane = image.planes.singleOrNull()
        ?: error("raw depth image must have exactly one plane")
    private val buffer = plane.buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN)

    override val width: Int = image.width
    override val height: Int = image.height

    override fun unsignedValue(x: Int, y: Int): Int {
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
