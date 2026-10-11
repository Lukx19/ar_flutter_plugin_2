package com.uhg0.ar_flutter_plugin_2.posetracking

import com.uhg0.ar_flutter_plugin_2.sessionlog.NativeSessionLogEmitter
import com.google.ar.core.Anchor
import com.google.ar.core.Frame
import com.google.ar.core.Pose
import com.google.ar.core.Session
import com.google.ar.core.TrackingState
import com.uhg0.ar_flutter_plugin_2.channelstatus.ChannelStatusReply
import com.uhg0.ar_flutter_plugin_2.visibilitycontract.PhotoSavedLayout
import com.uhg0.ar_flutter_plugin_2.visibilityingest.DurablePhoto
import com.uhg0.ar_flutter_plugin_2.visibilityingest.DurablePhotoListener
import com.uhg0.ar_flutter_plugin_2.visibilityingest.IngestClock
import com.uhg0.ar_flutter_plugin_2.visibilityingest.IngestTrackingState
import io.flutter.plugin.common.BasicMessageChannel
import io.flutter.plugin.common.BinaryCodec
import io.flutter.plugin.common.BinaryMessenger
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Platform side of the anchor pose tracking channel
 * `capture3d/visibility_grid/pose_tracking` (pose stability, plan 2.14).
 *
 * The grid isolate sends a `C3PQ` query at most 10 Hz; every query is
 * answered at once (no parking) with one kind 7 message: the 160 B ingest
 * header (latest AR frame timestamp, camera tracking and pose, the query's
 * group epoch, tracker diagnostics in the reserved words) followed by up to
 * 1024 dirty anchor updates and up to 64 creation records. A malformed query
 * (or a query after close) is counted and answered with the 4-byte status
 * [STATUS_QUERY_REFUSED], never with null.
 *
 * Anchors are created for the query's keyframe requests and, while tracking
 * is active, for every durable photo ([onPhotoDurable]). Tracking stays active
 * while the latest query carried the tracking flag and arrived less than 10 s
 * ago; otherwise every anchor is detached on the AR thread.
 *
 * Replies alternate between two preallocated direct buffers (copy-on-reply as
 * the ingest pull handler): a replied buffer is rewritten only by the reply
 * after next, which needs another query, and the grid isolate queries again
 * only after it handled the previous reply.
 */
internal class AnchorPoseUpdateProducer(
    private val clock: IngestClock,
    backend: AnchorBackend = ArCoreAnchorBackend(),
    readTimer: IngestClock = IngestClock { System.nanoTime() },
    initialCapacity: Int = AnchorPoseTracker.DEFAULT_CAPACITY,
    /**
     * Adds (true) or removes (false) this producer as durable photo listener.
     * It is registered only while tracking is armed, so the shell's
     * durable-terminal path stays idle when the grid is off.
     */
    private val photoListenerSwitch: (DurablePhotoListener, Boolean) -> Unit = { _, _ -> },
) : DurablePhotoListener {
    val tracker = AnchorPoseTracker(backend, clock, readTimer, initialCapacity)

    private val replyLock = Any()
    private val replies = arrayOf(direct(), direct())
    private var lastReply = 1
    private var sequence = 0

    private val arFrame = ArCoreAnchorFrame()
    private val photoRows = FloatArray(12)
    private val photoTq = FloatArray(7)
    private var channel: BasicMessageChannel<ByteBuffer>? = null
    private val switchLock = Any()
    private var photoListenerRegistered = false

    /** Registers the query channel on a background task queue of [messenger]. */
    fun bind(messenger: BinaryMessenger) {
        val queue = messenger.makeBackgroundTaskQueue()
        channel = BasicMessageChannel(messenger, PoseTrackingLayout.CHANNEL, BinaryCodec.INSTANCE_DIRECT, queue)
            .apply { setMessageHandler { message, reply -> onQuery(message, reply) } }
    }

    /** Queries answered with [STATUS_QUERY_REFUSED] (refused or failing tracker). */
    @Volatile var queriesRefused = 0L
        private set

    /**
     * Channel handler; runs on the background task queue. Never replies null
     * (the grid isolate sends through `BackgroundIsolateBinaryMessenger`): a
     * refused query (malformed, or the tracker is closed) or a failing tracker
     * is answered with the 4-byte status [STATUS_QUERY_REFUSED]; a pose reply is
     * always longer than 4 bytes (ingest header first).
     */
    fun onQuery(message: ByteBuffer?, reply: BasicMessageChannel.Reply<ByteBuffer>) {
        synchronized(replyLock) {
            val next = 1 - lastReply
            val b = replies[next]
            val length = try {
                tracker.handleQuery(message, b, sequence)
            } catch (e: RuntimeException) {
                NativeSessionLogEmitter.failure(
                    "NATIVE_HANDLER_ERROR", "poseTracking/query/${e.javaClass.simpleName}", "Pose tracking query failed",
                ) { mapOf("channel" to "poseTracking", "method" to "query", "error" to (e.message ?: "")) }
                QUERY_FAILED
            }
            if (length < 0) {
                queriesRefused++
                if (length == AnchorPoseTracker.QUERY_MALFORMED) {
                    NativeSessionLogEmitter.failure("POSE_REFUSED", "anchorQuery", "Anchor pose query refused") {
                        mapOf("reason" to "malformedOrClosed", "queriesRefused" to queriesRefused)
                    }
                }
                reply.reply(ChannelStatusReply.of(STATUS_QUERY_REFUSED))
                return
            }
            lastReply = next
            sequence++
            b.limit(b.capacity())
            b.position(length) // the engine sends position() bytes from index 0
            reply.reply(b)
        }
        // Outside the reply lock: the switch takes the shell registry's lock.
        syncPhotoListener(tracker.armed)
    }

    private fun syncPhotoListener(armed: Boolean) {
        synchronized(switchLock) {
            if (armed == photoListenerRegistered) return
            photoListenerRegistered = armed
        }
        photoListenerSwitch(this, armed)
    }

    /**
     * AR thread, once per frame with the real ARCore [session]. Returns after
     * one volatile read while tracking is inactive.
     */
    fun onArFrame(session: Any, frame: Frame) {
        if (!tracker.armed) return
        arFrame.frame = frame
        try {
            tracker.onFrame(session, arFrame)
        } finally {
            arFrame.release()
        }
    }

    /**
     * Capture finalisation thread: queues a photo anchor at the photo's
     * camera-to-world pose (photo saved event rows 0-2) while tracking is
     * active. Subject id is photo id bytes 0-7 as little-endian i64.
     */
    override fun onPhotoDurable(photo: DurablePhoto) {
        if (!tracker.armed) return
        val e = photo.event
        if (e.size < PhotoSavedLayout.BYTES) return
        synchronized(photoRows) {
            val subject = readLong(e, PhotoSavedLayout.PHOTO_ID)
            val exposureNs = readLong(e, PhotoSavedLayout.EXPOSURE_TIMESTAMP_NS)
            for (k in 0 until 12) {
                photoRows[k] = java.lang.Float.intBitsToFloat(readInt(e, PhotoSavedLayout.CAMERA_TO_WORLD_ROWS + 4 * k))
            }
            val valid = AnchorPoseTracker.rowsToPose(photoRows, 0, photoTq)
            tracker.enqueuePhoto(subject, exposureNs, photoTq, valid)
        }
    }

    fun close() {
        channel?.setMessageHandler(null)
        channel = null
        syncPhotoListener(false)
        tracker.close()
    }

    /** Diagnostic counters (allocates; not for the frame path). */
    fun snapshot(): Map<String, Any> = tracker.snapshot()

    companion object {
        /** 4-byte status reply of a refused query (malformed, or the tracker is closed). */
        const val STATUS_QUERY_REFUSED = 2

        /** Reply length standing for a tracker that threw (logged as a handler error, not a refusal). */
        private const val QUERY_FAILED = -2

        private fun direct(): ByteBuffer =
            ByteBuffer.allocateDirect(AnchorPoseUpdateLayout.MAX_MESSAGE_BYTES).order(ByteOrder.LITTLE_ENDIAN)

        private fun readInt(b: ByteArray, o: Int): Int =
            (b[o].toInt() and 0xff) or ((b[o + 1].toInt() and 0xff) shl 8) or
                ((b[o + 2].toInt() and 0xff) shl 16) or ((b[o + 3].toInt() and 0xff) shl 24)

        private fun readLong(b: ByteArray, o: Int): Long =
            (readInt(b, o).toLong() and 0xffffffffL) or (readInt(b, o + 4).toLong() shl 32)
    }
}

/**
 * [AnchorBackend] over ARCore. Unavoidable ARCore allocations: `Pose` and
 * `Anchor` per creation, the `Pose` returned by `Anchor.getPose` per pose
 * read. AR thread only (the scratch arrays are shared).
 */
internal class ArCoreAnchorBackend : AnchorBackend {
    private val translation = FloatArray(3)
    private val rotation = FloatArray(4)

    override fun create(session: Any, tq: FloatArray): Any {
        translation[0] = tq[0]; translation[1] = tq[1]; translation[2] = tq[2]
        rotation[0] = tq[3]; rotation[1] = tq[4]; rotation[2] = tq[5]; rotation[3] = tq[6]
        return (session as Session).createAnchor(Pose(translation, rotation))
    }

    override fun detach(anchor: Any) {
        (anchor as Anchor).detach()
    }

    override fun tracking(anchor: Any): Int = when ((anchor as Anchor).trackingState) {
        TrackingState.TRACKING -> AnchorTracking.TRACKING
        TrackingState.PAUSED -> AnchorTracking.PAUSED
        else -> AnchorTracking.STOPPED
    }

    override fun readPose(anchor: Any, out: FloatArray, offset: Int) {
        val pose = (anchor as Anchor).pose
        pose.getTranslation(out, offset)
        pose.getRotationQuaternion(out, offset + 3)
    }
}

/**
 * [AnchorFrame] over an ARCore [Frame]; rebound per frame on the AR thread.
 * Unavoidable ARCore allocations: the collection returned by
 * `Frame.getUpdatedAnchors` (and its iterator when it is not a list), the
 * anchor wrappers in it, and the `Pose` of `Camera.getPose`. The updated
 * anchors are copied into a grow-only array.
 */
internal class ArCoreAnchorFrame : AnchorFrame {
    var frame: Frame? = null
    private var updated = arrayOfNulls<Any>(64)
    private var loaded = 0

    private val current: Frame get() = checkNotNull(frame)

    override val timestampNs: Long get() = current.timestamp

    override val cameraTracking: Int
        get() = when (current.camera.trackingState) {
            TrackingState.TRACKING -> IngestTrackingState.TRACKING
            TrackingState.PAUSED -> IngestTrackingState.PAUSED
            else -> IngestTrackingState.STOPPED
        }

    override fun readCameraPose(out: FloatArray) {
        val pose = current.camera.pose
        pose.getTranslation(out, 0)
        pose.getRotationQuaternion(out, 3)
    }

    override fun loadUpdatedAnchors(): Int {
        val anchors = current.updatedAnchors
        val n = anchors.size
        if (n > updated.size) {
            var c = updated.size
            while (c < n) c *= 2
            updated = arrayOfNulls(c)
        }
        if (anchors is List<*>) {
            for (i in 0 until n) updated[i] = anchors[i]
        } else {
            var i = 0
            for (a in anchors) if (i < n) updated[i++] = a
        }
        loaded = n
        return n
    }

    override fun updatedAnchor(index: Int): Any = updated[index]!!

    /** Drops the frame and the anchor references of this frame. */
    fun release() {
        for (i in 0 until loaded) updated[i] = null
        loaded = 0
        frame = null
    }
}
