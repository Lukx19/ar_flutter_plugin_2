package com.uhg0.ar_flutter_plugin_2.posetracking

import com.uhg0.ar_flutter_plugin_2.visibilitycontract.IngestMessageLayout
import com.uhg0.ar_flutter_plugin_2.visibilityingest.IngestClock
import com.uhg0.ar_flutter_plugin_2.visibilityingest.IngestTrackingState
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.cos
import kotlin.math.sin

internal class FakePoseClock(var now: Long = 0L) : IngestClock {
    override fun nowNs(): Long = now
}

/** Scripted anchor: pose t xyz, q xyzw and a tracking state. */
internal class FakeAnchor(tq: FloatArray) {
    val pose: FloatArray = tq.copyOf()
    var tracking = AnchorTracking.TRACKING
    var detached = false
}

internal class FakeAnchorBackend : AnchorBackend {
    val created = ArrayList<FakeAnchor>()
    var detachCalls = 0
    var failCreates = false

    override fun create(session: Any, tq: FloatArray): Any {
        if (failCreates) throw IllegalStateException("refused")
        return FakeAnchor(tq).also { created += it }
    }

    override fun detach(anchor: Any) {
        (anchor as FakeAnchor).detached = true
        detachCalls++
    }

    override fun tracking(anchor: Any): Int = (anchor as FakeAnchor).tracking

    override fun readPose(anchor: Any, out: FloatArray, offset: Int) {
        (anchor as FakeAnchor).pose.copyInto(out, offset)
    }
}

/** Scripted frame: timestamp, camera tracking and pose, updated anchors. */
internal class FakeAnchorFrame : AnchorFrame {
    override var timestampNs = 1_000_000L
    override var cameraTracking = IngestTrackingState.TRACKING
    val cameraTq = floatArrayOf(0f, 0f, 0f, 0f, 0f, 0f, 1f)
    val updated = arrayOfNulls<Any>(4096)
    var updatedCount = 0

    override fun readCameraPose(out: FloatArray) {
        cameraTq.copyInto(out)
    }

    override fun loadUpdatedAnchors(): Int = updatedCount

    override fun updatedAnchor(index: Int): Any = updated[index]!!

    fun setUpdated(anchors: List<FakeAnchor>) {
        anchors.forEachIndexed { i, a -> updated[i] = a }
        updatedCount = anchors.size
    }
}

internal class KeyframeRequest(
    val anchorId: Int,
    val subject: Long = 0L,
    val captureNs: Long = 0L,
    val tq: FloatArray = identityPose(),
)

internal fun identityPose(x: Float = 0f, y: Float = 0f, z: Float = 0f): FloatArray =
    floatArrayOf(x, y, z, 0f, 0f, 0f, 1f)

/** Pose rotated by [degrees] about +Y. */
internal fun yawPose(degrees: Double, x: Float = 0f): FloatArray {
    val h = Math.toRadians(degrees) / 2.0
    return floatArrayOf(x, 0f, 0f, 0f, sin(h).toFloat(), 0f, cos(h).toFloat())
}

internal fun poseQuery(
    flags: Int = PoseQueryLayout.FLAG_TRACKING,
    groupEpoch: Int = 7,
    anchorLimit: Int = 0,
    detachAfterSeconds: Int = 0,
    requests: List<KeyframeRequest> = emptyList(),
): ByteBuffer {
    val b = ByteBuffer.allocateDirect(PoseQueryLayout.HEADER_BYTES + requests.size * PoseQueryLayout.REQUEST_BYTES)
        .order(ByteOrder.LITTLE_ENDIAN)
    b.putInt(0, PoseQueryLayout.MAGIC)
    b.putShort(4, PoseQueryLayout.VERSION.toShort())
    b.putShort(PoseQueryLayout.FLAGS_OFFSET, flags.toShort())
    b.putShort(PoseQueryLayout.COUNT_OFFSET, requests.size.toShort())
    b.putShort(PoseQueryLayout.GROUP_EPOCH_OFFSET, groupEpoch.toShort())
    b.putInt(PoseQueryLayout.ANCHOR_LIMIT_OFFSET, anchorLimit)
    b.putInt(PoseQueryLayout.DETACH_AFTER_OFFSET, detachAfterSeconds)
    b.putInt(20, 0)
    requests.forEachIndexed { i, r ->
        val o = PoseQueryLayout.HEADER_BYTES + i * PoseQueryLayout.REQUEST_BYTES
        b.putInt(o + PoseQueryLayout.REQUEST_ANCHOR_ID_OFFSET, r.anchorId)
        b.putInt(o + 4, 0)
        b.putLong(o + PoseQueryLayout.REQUEST_SUBJECT_OFFSET, r.subject)
        b.putLong(o + PoseQueryLayout.REQUEST_CAPTURE_NS_OFFSET, r.captureNs)
        for (k in 0 until 7) b.putFloat(o + PoseQueryLayout.REQUEST_POSE_OFFSET + 4 * k, r.tq[k])
        b.putInt(o + 52, 0)
    }
    return b
}

internal class UpdateRecord(val anchorId: Int, val kind: Int, val tracking: Int, val rows: FloatArray)

internal class CreationRecord(
    val anchorId: Int,
    val kind: Int,
    val status: Int,
    val subject: Long,
    val captureNs: Long,
    val frameNs: Long,
)

/** Decoded kind 7 reply. */
internal class DecodedReply(val bytes: ByteBuffer, val length: Int) {
    val payload = IngestMessageLayout.HEADER_BYTES
    val updateCount = bytes.getShort(payload).toInt() and 0xffff
    val creationCount = bytes.getShort(payload + 2).toInt() and 0xffff

    val updates: List<UpdateRecord> = (0 until updateCount).map { i ->
        val o = payload + AnchorPoseUpdateLayout.PREFIX_BYTES + i * AnchorPoseUpdateLayout.UPDATE_BYTES
        UpdateRecord(
            bytes.getInt(o),
            bytes.get(o + AnchorPoseUpdateLayout.UPDATE_KIND_OFFSET).toInt(),
            bytes.get(o + AnchorPoseUpdateLayout.UPDATE_TRACKING_OFFSET).toInt(),
            FloatArray(12) { k -> bytes.getFloat(o + AnchorPoseUpdateLayout.UPDATE_MATRIX_OFFSET + 4 * k) },
        )
    }

    val creations: List<CreationRecord> = (0 until creationCount).map { i ->
        val o = payload + AnchorPoseUpdateLayout.PREFIX_BYTES + updateCount * AnchorPoseUpdateLayout.UPDATE_BYTES +
            i * AnchorPoseUpdateLayout.CREATION_BYTES
        CreationRecord(
            bytes.getInt(o),
            bytes.get(o + AnchorPoseUpdateLayout.CREATION_KIND_OFFSET).toInt(),
            bytes.get(o + AnchorPoseUpdateLayout.CREATION_STATUS_OFFSET).toInt(),
            bytes.getLong(o + AnchorPoseUpdateLayout.CREATION_SUBJECT_OFFSET),
            bytes.getLong(o + AnchorPoseUpdateLayout.CREATION_CAPTURE_NS_OFFSET),
            bytes.getLong(o + AnchorPoseUpdateLayout.CREATION_FRAME_NS_OFFSET),
        )
    }

    fun header(offset: Int): Int = bytes.getInt(offset)
}

internal fun replyBuffer(): ByteBuffer =
    ByteBuffer.allocateDirect(AnchorPoseUpdateLayout.MAX_MESSAGE_BYTES).order(ByteOrder.LITTLE_ENDIAN)
