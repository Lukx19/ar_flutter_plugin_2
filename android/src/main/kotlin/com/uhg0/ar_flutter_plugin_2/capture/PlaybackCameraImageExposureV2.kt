package com.uhg0.ar_flutter_plugin_2.capture

import java.io.ByteArrayInputStream
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException

/** A copied ARCore CPU camera image (NV21) of one frame. */
internal class PlaybackCameraImage(
    val nv21: ByteArray,
    val width: Int,
    val height: Int,
    val timestampNs: Long,
)

/**
 * Shutter exposures while the AR session replays an ARCore dataset
 * (debug/profile playback test mode).
 *
 * ARCore cannot replay a Shared Camera session, so there is no Camera2 still.
 * The exposure is the next frame's ARCore CPU camera image (the recorded
 * camera stream, typically 640x480), encoded as JPEG and handed to the same
 * durable V2 component path as a Camera2 still ([DirectStoreDispatch]: blur
 * analysis, assessed-original policy). DNG is refused.
 */
internal class PlaybackCameraImageExposureV2(
    /** Lifecycle/motion refusal, as for Camera2 exposures; null admits. */
    private val refusal: (CaptureAttemptQualifierV2) -> String?,
    /** Runs JPEG encoding and the component dispatch off the frame thread. */
    private val executor: Executor,
    /** NV21 -> JPEG. */
    private val encodeJpeg: (PlaybackCameraImage) -> ByteArray,
    private val analyzeJpeg: (ByteArray, () -> Boolean) -> PreviewBlurQuality = { jpeg, cancelled ->
        JpegBlurAnalyzer.analyzeLaplacianVariance({ ByteArrayInputStream(jpeg) }, cancelled)
    },
    /** Frames to wait for a CPU image before the attempt fails. */
    private val maximumWaitFrames: Int = 30,
) : SharedCameraExposurePortV2 {
    private class Pending(
        val qualifier: CaptureAttemptQualifierV2,
        val callback: SharedCameraExposureCallbackV2,
    ) {
        var waitedFrames = 0
    }

    private val lock = Any()
    private var pending: Pending? = null
    private var closed = false

    val hasPendingExposure: Boolean get() = synchronized(lock) { pending != null }

    override fun requestExposure(
        qualifier: CaptureAttemptQualifierV2,
        required: Set<CaptureComponentKind>,
        callback: SharedCameraExposureCallbackV2,
    ): Boolean {
        if (required != setOf(CaptureComponentKind.JPEG)) return false
        synchronized(lock) { if (closed || pending != null) return false }
        refusal(qualifier)?.let { reason ->
            callback.onFailure(qualifier, reason)
            return false
        }
        synchronized(lock) {
            if (closed || pending != null) return false
            pending = Pending(qualifier, callback)
        }
        return true
    }

    override fun cancelExposure(qualifier: CaptureAttemptQualifierV2) {
        synchronized(lock) { if (pending?.qualifier == qualifier) pending = null }
    }

    /**
     * Frame thread, once per ARCore frame. [acquire] copies the frame's CPU
     * image or returns null when it is not available yet; it is called only
     * while an exposure is pending.
     */
    fun onFrame(acquire: () -> PlaybackCameraImage?) {
        val current = synchronized(lock) { pending } ?: return
        val image = try {
            acquire()
        } catch (_: Exception) {
            null
        }
        if (image == null) {
            current.waitedFrames++
            if (current.waitedFrames >= maximumWaitFrames && take(current)) {
                current.callback.onFailure(current.qualifier, "playback-camera-image-unavailable")
            }
            return
        }
        if (!take(current)) return
        try {
            executor.execute { dispatch(current, image) }
        } catch (_: RejectedExecutionException) {
            current.callback.onFailure(current.qualifier, "component-dispatch")
        }
    }

    /** Fails a pending exposure and refuses later ones. */
    fun close() {
        val dropped = synchronized(lock) {
            closed = true
            pending.also { pending = null }
        }
        dropped?.let { runCatching { it.callback.onFailure(it.qualifier, "owner-closed") } }
    }

    private fun take(expected: Pending): Boolean = synchronized(lock) {
        if (pending !== expected) false else {
            pending = null
            true
        }
    }

    private fun dispatch(owner: Pending, image: PlaybackCameraImage) {
        val jpeg = try {
            encodeJpeg(image)
        } catch (_: Exception) {
            owner.callback.onFailure(owner.qualifier, "playback-jpeg-encode")
            return
        }
        DirectStoreDispatch(
            components = SharedCameraComponentSetV2(
                owner.qualifier,
                listOf(CaptureComponentStreamV2(CaptureComponentKind.JPEG, ByteArrayInputStream(jpeg))),
                image.timestampNs,
            ),
            callback = owner.callback::onComponents,
            onFailure = { reason -> runCatching { owner.callback.onFailure(owner.qualifier, reason) } },
            analyzeJpeg = { cancelled -> analyzeJpeg(jpeg, cancelled) },
            retainAssessedOriginals = owner.callback.retainAssessedOriginals,
        ).run()
    }
}
