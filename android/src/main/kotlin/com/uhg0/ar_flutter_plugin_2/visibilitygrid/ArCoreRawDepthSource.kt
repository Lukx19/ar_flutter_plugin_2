package com.uhg0.ar_flutter_plugin_2.visibilitygrid

import android.media.Image
import com.uhg0.ar_flutter_plugin_2.BuildConfig
import com.google.ar.core.Config
import com.google.ar.core.Frame
import com.google.ar.core.TrackingState
import com.google.ar.core.exceptions.NotYetAvailableException
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean

/** Build-only qualification seam. Default dense; changing source requires a fresh session/build. */
internal fun depthAcquisitionModeForSession(
    mode: Config.DepthMode,
    rawCandidate: Boolean,
    denseCurrentFrameExperiment: Boolean = false,
): DepthAcquisitionMode =
    when (mode) {
        Config.DepthMode.AUTOMATIC -> when {
            rawCandidate -> DepthAcquisitionMode.RAW
            denseCurrentFrameExperiment -> DepthAcquisitionMode.AUTOMATIC_CURRENT_FRAME_EXPERIMENTAL
            else -> DepthAcquisitionMode.AUTOMATIC
        }
        Config.DepthMode.RAW_DEPTH_ONLY -> DepthAcquisitionMode.RAW
        Config.DepthMode.DISABLED -> throw IllegalArgumentException("depth source is disabled")
    }

/** The actual SDK route selector, exercised with owned planes by focused tests. */
internal fun selectedDepthAcquirer(
    mode: DepthAcquisitionMode,
    acquireDense: () -> RawDepthImage,
    acquireRaw: () -> RawDepthImage,
    acquireRawConfidence: () -> RawDepthImage,
): PairedRawDepthAcquirer = object : PairedRawDepthAcquirer {
    override fun acquireDepth(): RawDepthImage = when (mode) {
        DepthAcquisitionMode.AUTOMATIC, DepthAcquisitionMode.AUTOMATIC_CURRENT_FRAME_EXPERIMENTAL -> acquireDense()
        DepthAcquisitionMode.RAW -> acquireRaw()
        DepthAcquisitionMode.PROVIDED -> error("provided pixels have no SDK acquisition route")
    }
    override fun acquireConfidence(): RawDepthImage? =
        if (mode == DepthAcquisitionMode.RAW) acquireRawConfidence() else null
}

internal sealed interface PreparedRawDepthResult {
    data class Ready(val frame: PreparedRawDepthFrame) : PreparedRawDepthResult
    data object TransientUnavailable : PreparedRawDepthResult
    data class Refused(val reason: DepthAcquisitionRefusal, val metadata: RawDepthFrameMetadata) : PreparedRawDepthResult
    data class Failure(val reason: String) : PreparedRawDepthResult
}

internal class PreparedRawDepthFrame(
    val metadata: RawDepthFrameMetadata,
    private val depth: RawDepthImage,
    private val confidence: RawDepthImage?,
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

    fun processPacked(
        pool: DepthSamplesLeasePool,
        generation: SampleLeaseGeneration,
        owner: VisibilityObservationOwnership? = null,
        diagnostic: DepthCopyTransientObserver? = null,
    ): PackedDepthAcquisitionResult {
        val source = RawDepthCopySource(
            acquirer = object : PairedRawDepthAcquirer {
                override fun acquireDepth() = depth
                override fun acquireConfidence() = confidence
            },
            maxCopiedPixels = maxCopiedPixels,
        )
        check(processing.compareAndSet(false, true))
        return source.acquirePacked({ _, _ -> metadata }, pool, generation, owner, diagnostic)
    }

    override fun close() {
        // A refused offer never reaches process(); only that path closes here.
        if (!processing.compareAndSet(false, true)) return
        try {
            confidence?.close()
        } finally {
            depth.close()
        }
    }
}

class ArCoreRawDepthSource(
    private val maxCopiedPixels: Int = 4_096,
    private val onResourceAcquired: () -> Unit = {},
    private val onResourceClosed: () -> Unit = {},
    private val depthMode: Config.DepthMode = Config.DepthMode.RAW_DEPTH_ONLY,
    rawCandidate: Boolean = BuildConfig.DEBUG && BuildConfig.RAW_DEPTH_CANDIDATE,
    denseCurrentFrameExperiment: Boolean = false,
) {
    private val acquisitionMode = depthAcquisitionModeForSession(depthMode, rawCandidate, denseCurrentFrameExperiment)

    init {
        require(depthMode == Config.DepthMode.RAW_DEPTH_ONLY ||
            depthMode == Config.DepthMode.AUTOMATIC) {
            "ARCore depth source requires an enabled depth mode"
        }
    }

    fun acquire(
        frame: Frame,
        groupGeneration: Long,
        sessionGeneration: Long,
    ): DepthAcquisitionResult {
        val frameBefore = frame.timestamp
        val cameraBefore = frame.androidCameraTimestamp
        val source = RawDepthCopySource(
            acquirer = ArCorePairedRawDepthAcquirer(frame, acquisitionMode),
            maxCopiedPixels = maxCopiedPixels,
            onResourceAcquired = onResourceAcquired,
            onResourceClosed = onResourceClosed,
        )
        return source.acquire { width, height ->
            metadata(frame, groupGeneration, sessionGeneration, width, height).copy(acquisitionFrameBeforeNs = frameBefore, acquisitionAndroidCameraBeforeNs = cameraBefore)
        }
    }

    /** Copies ARCore depth into an application-owned packed lease on this stack. */
    internal fun acquirePacked(
        frame: Frame,
        ownership: VisibilityObservationOwnership,
        pool: DepthSamplesLeasePool,
    ): PackedDepthAcquisitionResult {
        val frameBefore = frame.timestamp
        val cameraBefore = frame.androidCameraTimestamp
        val source = RawDepthCopySource(
            acquirer = ArCorePairedRawDepthAcquirer(frame, acquisitionMode),
            maxCopiedPixels = maxCopiedPixels,
            onResourceAcquired = onResourceAcquired,
            onResourceClosed = onResourceClosed,
        )
        return source.acquirePacked(
            metadataForDimensions = { width, height ->
                metadata(frame, ownership.groupGeneration, ownership.sessionGeneration, width, height).copy(acquisitionFrameBeforeNs = frameBefore, acquisitionAndroidCameraBeforeNs = cameraBefore)
            },
            pool = pool,
            generation = SampleLeaseGeneration.from(ownership),
        )
    }

    /** Acquires both ARCore images on the current frame; selection happens on a worker. */
    internal fun prepare(
        frame: Frame,
        groupGeneration: Long,
        sessionGeneration: Long,
        frameSequence: Long = 0L,
    ): PreparedRawDepthResult {
        val frameBefore = frame.timestamp
        val cameraBefore = frame.androidCameraTimestamp
        return prepareRawDepthFrame(
            acquirer = ArCorePairedRawDepthAcquirer(frame, acquisitionMode),
            metadataForDimensions = { width, height ->
                metadata(frame, groupGeneration, sessionGeneration, width, height).copy(
                    acquisitionFrameBeforeNs = frameBefore,
                    acquisitionAndroidCameraBeforeNs = cameraBefore,
                    acquisitionFrameSequence = frameSequence,
                )
            },
            maxCopiedPixels = maxCopiedPixels,
            onResourceAcquired = onResourceAcquired,
            onResourceClosed = onResourceClosed,
        )
    }

    private fun metadata(
        frame: Frame,
        groupGeneration: Long,
        sessionGeneration: Long,
        depthWidth: Int,
        depthHeight: Int,
    ): RawDepthFrameMetadata {
        val camera = frame.camera
        val textureIntrinsics = camera.textureIntrinsics
        val pose = FloatArray(16)
        camera.pose.toMatrix(pose, 0)
        return freezeTextureDepthMetadata(
            frameTimestampNs = frame.timestamp,
            groupGeneration = groupGeneration,
            sessionGeneration = sessionGeneration,
            tracking = camera.trackingState == TrackingState.TRACKING,
            depthWidth = depthWidth,
            depthHeight = depthHeight,
            textureDimensions = textureIntrinsics.imageDimensions,
            textureFocal = textureIntrinsics.focalLength,
            texturePrincipal = textureIntrinsics.principalPoint,
            worldFromCameraGl = pose,
            sourceImageDimensions = camera.imageIntrinsics.imageDimensions,
            cameraImageTimestampNs = frame.androidCameraTimestamp,
            acquisitionMode = acquisitionMode,
        )
    }
}

/** The current-frame acquisition stack; no acquirer/calibration callback escapes into prepared work. */
internal fun prepareRawDepthFrame(
    acquirer: PairedRawDepthAcquirer,
    metadataForDimensions: (Int, Int) -> RawDepthFrameMetadata,
    maxCopiedPixels: Int = 4_096,
    onResourceAcquired: () -> Unit = {},
    onResourceClosed: () -> Unit = {},
): PreparedRawDepthResult {
    var depth: RawDepthImage? = null
    var confidence: RawDepthImage? = null
    var accepted = false
    return try {
        depth = ManagedRawDepthImage(acquirer.acquireDepth(), onResourceClosed)
        onResourceAcquired()
        confidence = acquirer.acquireConfidence()?.let {
            ManagedRawDepthImage(it, onResourceClosed).also { onResourceAcquired() }
        }
        val metadata = acquiredDepthMetadata(metadataForDimensions(depth.width, depth.height), depth, confidence)
        val refusal = depthAcquisitionRefusal(metadata, depth, confidence)
        if (refusal != null) {
            PreparedRawDepthResult.Refused(refusal, metadata)
        } else {
            accepted = true
            PreparedRawDepthResult.Ready(
                PreparedRawDepthFrame(
                    metadata,
                    depth,
                    confidence,
                    maxCopiedPixels,
                ),
            )
        }
    } catch (_: DepthCalibrationUnavailableException) {
        PreparedRawDepthResult.TransientUnavailable
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

private class ManagedRawDepthImage(
    private val delegate: RawDepthImage,
    private val onClosed: () -> Unit,
) : RawDepthImage {
    private val closed = AtomicBoolean(false)
    override val timestampNs get() = delegate.timestampNs
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
    frame: Frame,
    acquisitionMode: DepthAcquisitionMode,
) : PairedRawDepthAcquirer {
    private val selected = selectedDepthAcquirer(
        acquisitionMode,
        acquireDense = { acquireImage { frame.acquireDepthImage16Bits() } },
        acquireRaw = { acquireImage { frame.acquireRawDepthImage16Bits() } },
        acquireRawConfidence = { acquireImage { frame.acquireRawDepthConfidenceImage() } },
    )
    override fun acquireDepth(): RawDepthImage = selected.acquireDepth()
    override fun acquireConfidence(): RawDepthImage? = selected.acquireConfidence()

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

    override val timestampNs: Long get() = image.timestamp
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
