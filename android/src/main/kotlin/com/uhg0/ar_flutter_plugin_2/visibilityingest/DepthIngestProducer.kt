package com.uhg0.ar_flutter_plugin_2.visibilityingest

import com.uhg0.ar_flutter_plugin_2.visibilitycontract.IngestMessageLayout
import com.uhg0.ar_flutter_plugin_2.visibilitygrid.DepthAcquisitionMode
import com.uhg0.ar_flutter_plugin_2.visibilitygrid.DepthAcquisitionRefusal
import com.uhg0.ar_flutter_plugin_2.visibilitygrid.depthImageIdentityRefusal
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Shared state of one ingest session, owned by the AR thread: pose history,
 * group epoch and the latest tracking state.
 */
internal class IngestSessionState {
    val history = DepthPoseHistory()
    var groupEpoch = 0
    var trackingState = IngestTrackingState.STOPPED
    val matched = FloatArray(16)
}

/**
 * Kind 1 depth image producer (contract 2.4). On the AR thread, at most every
 * [ACQUIRE_INTERVAL_NS] (the app's existing depth throttle), it acquires the
 * ARCore depth image of the session's depth mode, applies the acquisition
 * identity check ([depthIdentityRefusal]), drops images more than 500 ms
 * older than their frame (ARCore hands out its last image under load,
 * measured up to 21 s) and repeats of the last image, copies u16 millimetres
 * and raw confidence into an owned staging buffer and scales the texture
 * intrinsics to the depth size like `depthIntrinsicsFromTexture`. The message
 * carries the pose matched at the depth image timestamp; an image newer than
 * the newest pose waits for the frame that covers it.
 *
 * Clocks: the depth image timestamp is on the `Frame.getTimestamp` clock,
 * `Frame.getAndroidCameraTimestamp` is the Camera2 sensor clock (a different
 * base, behind on the SM-X700). Identity is therefore checked against the
 * frame timestamp only; the sensor timestamp is used for photo pose matching
 * and never compared with a depth image.
 *
 * An AUTOMATIC image older than its frame is counted
 * ([identityStaleAutomatic]) but not refused: the grid fuses with the pose at
 * the image timestamp instead of the frame pose.
 */
internal class DepthIngestProducer(
    private val session: IngestSessionState,
    private val sink: IngestMessageSink,
    private val clock: IngestClock,
) : DepthImageSink {
    companion object {
        const val ACQUIRE_INTERVAL_NS = 250_000_000L
        private const val MAX_PAYLOAD = IngestMessageLayout.MAX_MESSAGE_BYTES - IngestMessageLayout.HEADER_BYTES
    }

    private val staging = ByteBuffer.allocateDirect(IngestMessageLayout.MAX_MESSAGE_BYTES).order(ByteOrder.LITTLE_ENDIAN)
    private val texture = FloatArray(6)
    private var staged = false
    private var stagedImageNs = 0L
    private var stagedWaited = 0
    private var lastAcquireNs = Long.MIN_VALUE
    private var lastImageNs = Long.MIN_VALUE

    // Per-acquisition inputs for the sink callback.
    private var frameNs = 0L
    private var acquireNs = 0L
    private var mode = IngestFrameSource.DEPTH_MODE_DISABLED

    // Counters, AR thread writes; read for diagnostics.
    @Volatile var acquired = 0L; private set
    @Volatile var notYetAvailable = 0L; private set
    @Volatile var failures = 0L; private set
    @Volatile var staleDropped = 0L; private set
    @Volatile var duplicateDropped = 0L; private set
    @Volatile var identityRefused = 0L; private set
    @Volatile var identityStaleAutomatic = 0L; private set
    @Volatile var calibrationUnavailable = 0L; private set
    @Volatile var oversized = 0L; private set
    @Volatile var deferred = 0L; private set
    @Volatile var deferTimeouts = 0L; private set
    @Volatile var stagedReplaced = 0L; private set
    @Volatile var published = 0L; private set
    @Volatile var poseMissing = 0L; private set

    /** Timestamp of the newest depth image ARCore delivered (any outcome), or MIN_VALUE. */
    @Volatile var latestImageNs = Long.MIN_VALUE; private set

    /** Camera frame timestamp of the latest acquisition attempt. */
    @Volatile var latestFrameNs = Long.MIN_VALUE; private set

    fun reset() {
        staged = false
        lastAcquireNs = Long.MIN_VALUE
        lastImageNs = Long.MIN_VALUE
    }

    /** Acquires when due; call after the frame's pose was pushed. */
    fun onFrame(frame: IngestFrameSource, depthMode: Int) {
        if (depthMode == IngestFrameSource.DEPTH_MODE_DISABLED) return
        val now = clock.nowNs()
        if (lastAcquireNs != Long.MIN_VALUE && now - lastAcquireNs < ACQUIRE_INTERVAL_NS) return
        if (!frame.readTextureIntrinsics(texture)) {
            calibrationUnavailable++
            return
        }
        frameNs = frame.timestampNs
        latestFrameNs = frameNs
        acquireNs = now
        mode = depthMode
        when (frame.acquireDepth(depthMode, this)) {
            IngestFrameSource.DEPTH_OK -> {
                acquired++
                lastAcquireNs = now
            }
            IngestFrameSource.DEPTH_NOT_YET_AVAILABLE -> notYetAvailable++
            else -> failures++
        }
    }

    override fun onDepthImage(
        imageTimestampNs: Long,
        width: Int,
        height: Int,
        depth: ByteBuffer,
        rowStride: Int,
        pixelStride: Int,
        confidence: ByteBuffer?,
        confidenceRowStride: Int,
        confidencePixelStride: Int,
    ) {
        latestImageNs = imageTimestampNs
        val refusal = depthIdentityRefusal(imageTimestampNs, frameNs, mode)
        if (refusal == DepthAcquisitionRefusal.STALE_AUTOMATIC) {
            identityStaleAutomatic++
        } else if (refusal != null) {
            identityRefused++
            sink.countSourceDrop(IngestMessageLayout.KIND_DEPTH_IMAGE)
            return
        }
        if (DepthPoseHistory.isStaleDepth(frameNs, imageTimestampNs)) {
            staleDropped++
            sink.countSourceDrop(IngestMessageLayout.KIND_DEPTH_IMAGE)
            return
        }
        if (imageTimestampNs == lastImageNs) {
            duplicateDropped++
            return
        }
        val withConfidence = confidence != null && confidencePixelStride == 1
        val depthBytes = width * height * 2
        val payload = depthBytes + if (withConfidence) width * height else 0
        if (width <= 0 || height <= 0 || payload > MAX_PAYLOAD || pixelStride != 2) {
            oversized++
            sink.countSourceDrop(IngestMessageLayout.KIND_DEPTH_IMAGE)
            return
        }
        val tw = texture[4]
        val th = texture[5]
        val fx = texture[0] * (width / tw)
        val fy = texture[1] * (height / th)
        val cx = texture[2] * (width / tw)
        val cy = texture[3] * (height / th)
        if (!(fx.isFinite() && fx > 0f && fy.isFinite() && fy > 0f && cx.isFinite() && cy.isFinite())) {
            calibrationUnavailable++
            sink.countSourceDrop(IngestMessageLayout.KIND_DEPTH_IMAGE)
            return
        }
        lastImageNs = imageTimestampNs
        if (staged) {
            stagedReplaced++
            sink.countSourceDrop(IngestMessageLayout.KIND_DEPTH_IMAGE)
        }
        val b = staging
        IngestMessageWriter.begin(b, IngestMessageLayout.KIND_DEPTH_IMAGE)
        IngestMessageWriter.sourceFlags(b, IngestMessageLayout.SOURCE_DEPTH)
        IngestMessageWriter.payloadBytes(b, payload)
        IngestMessageWriter.timing(b, imageTimestampNs, frameNs, acquireNs)
        IngestMessageWriter.intrinsics(b, fx, fy, cx, cy)
        IngestMessageWriter.image(b, width, height, width * 2, IngestMessageLayout.DEPTH_UNIT_U16_MM,
            if (withConfidence) IngestMessageLayout.CONFIDENCE_U8 else IngestMessageLayout.CONFIDENCE_NONE)
        val base = IngestMessageLayout.HEADER_BYTES
        val srcBase = depth.position()
        for (y in 0 until height) {
            val src = srcBase + y * rowStride
            val dst = base + y * width * 2
            // Byte copy: image plane buffers are little-endian data in a big-endian view.
            for (i in 0 until width * 2) b.put(dst + i, depth.get(src + i))
        }
        if (withConfidence) {
            val c = confidence!!
            val cBase = c.position()
            val dstBase = base + depthBytes
            for (y in 0 until height) {
                val src = cBase + y * confidenceRowStride
                val dst = dstBase + y * width
                for (x in 0 until width) b.put(dst + x, c.get(src + x))
            }
        }
        staged = true
        stagedImageNs = imageTimestampNs
        stagedWaited = 0
        if (!session.history.readyToMatch(imageTimestampNs, 0)) deferred++
    }

    /** Matches and offers the staged image once its pose is available; [newFrame] counts waited frames. */
    fun publishStaged(newFrame: Boolean) {
        if (!staged) return
        if (newFrame) stagedWaited++
        val history = session.history
        if (!history.readyToMatch(stagedImageNs, stagedWaited)) return
        if (stagedImageNs > history.newestNs) deferTimeouts++
        val match = history.match(stagedImageNs, session.matched)
        if (match == IngestMessageLayout.POSE_MATCH_MISSING) poseMissing++
        IngestMessageWriter.pose(staging, session.trackingState, match, session.groupEpoch,
            gapUs(history.errorNs), session.matched)
        staged = false
        if (sink.offer(staging, IngestMessageWriter.messageBytes(staging))) published++
    }
}

/**
 * Identity check of an ingest depth image on the frame clock only: RAW keeps
 * the shared rule ([depthImageIdentityRefusal], frame clock); AUTOMATIC
 * refuses a non-positive image or frame time and an image newer than its
 * frame, and reports an older image as [DepthAcquisitionRefusal.STALE_AUTOMATIC]
 * (counted, not refused).
 */
internal fun depthIdentityRefusal(imageNs: Long, frameNs: Long, mode: Int): DepthAcquisitionRefusal? {
    if (mode == IngestFrameSource.DEPTH_MODE_RAW) {
        return depthImageIdentityRefusal(
            imageNs, 0L, frameNs, DepthAcquisitionMode.RAW,
            rawProjectionQualified = true, denseCurrentFrameExperimentEnabled = false,
        )
    }
    if (imageNs <= 0L || frameNs <= 0L || imageNs > frameNs) return DepthAcquisitionRefusal.INVALID_TIMESTAMP
    return if (imageNs != frameNs) DepthAcquisitionRefusal.STALE_AUTOMATIC else null
}

internal fun gapUs(errorNs: Long): Int {
    val us = errorNs / 1_000L
    return if (us > Int.MAX_VALUE) Int.MAX_VALUE else if (us < Int.MIN_VALUE) Int.MIN_VALUE else us.toInt()
}
