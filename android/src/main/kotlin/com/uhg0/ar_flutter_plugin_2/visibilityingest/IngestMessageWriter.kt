package com.uhg0.ar_flutter_plugin_2.visibilityingest

import com.uhg0.ar_flutter_plugin_2.visibilitycontract.IngestMessageLayout
import java.nio.ByteBuffer

/**
 * Writes the 160 B ingest message header (contract 2.4) at absolute offsets of
 * a little-endian buffer. Every field is written with absolute puts, so the
 * buffer position and limit are untouched and nothing is allocated.
 */
internal object IngestMessageWriter {
    /** Writes magic and version and zeroes every other header byte. */
    fun begin(b: ByteBuffer, kind: Int) {
        var i = 0
        while (i < IngestMessageLayout.HEADER_BYTES) {
            b.putLong(i, 0L)
            i += 8
        }
        b.putInt(0, IngestMessageLayout.MAGIC)
        b.putShort(4, IngestMessageLayout.VERSION.toShort())
        b.put(IngestMessageLayout.KIND, kind.toByte())
    }

    fun kind(b: ByteBuffer): Int = b.get(IngestMessageLayout.KIND).toInt() and 0xff

    fun timing(b: ByteBuffer, sampleNs: Long, frameNs: Long, acquireNs: Long) {
        b.putLong(IngestMessageLayout.SAMPLE_TIMESTAMP_NS, sampleNs)
        b.putLong(IngestMessageLayout.FRAME_TIMESTAMP_NS, frameNs)
        b.putLong(IngestMessageLayout.ACQUIRE_TIME_NS, acquireNs)
    }

    fun sourceFlags(b: ByteBuffer, flags: Int) {
        b.put(IngestMessageLayout.SOURCE_FLAGS, flags.toByte())
    }

    fun payloadBytes(b: ByteBuffer, bytes: Int) {
        b.putInt(IngestMessageLayout.PAYLOAD_BYTES, bytes)
    }

    fun payloadBytesOf(b: ByteBuffer): Int = b.getInt(IngestMessageLayout.PAYLOAD_BYTES)

    fun sequence(b: ByteBuffer, sequence: Int) {
        b.putInt(IngestMessageLayout.SEQUENCE, sequence)
    }

    fun sendTime(b: ByteBuffer, ns: Long) {
        b.putLong(IngestMessageLayout.SEND_TIME_NS, ns)
    }

    fun dropped(b: ByteBuffer, count: Int) {
        b.putInt(IngestMessageLayout.DROPPED, count)
    }

    fun pose(b: ByteBuffer, trackingState: Int, poseMatch: Int, groupEpoch: Int, poseGapUs: Int, cameraToWorld: FloatArray) {
        b.put(IngestMessageLayout.TRACKING_STATE, trackingState.toByte())
        b.put(IngestMessageLayout.POSE_MATCH, poseMatch.toByte())
        b.putShort(IngestMessageLayout.GROUP_EPOCH, groupEpoch.toShort())
        b.putInt(IngestMessageLayout.POSE_GAP_US, poseGapUs)
        for (i in 0 until 16) b.putFloat(IngestMessageLayout.CAMERA_TO_WORLD + 4 * i, cameraToWorld[i])
    }

    fun intrinsics(b: ByteBuffer, fx: Float, fy: Float, cx: Float, cy: Float) {
        b.putFloat(IngestMessageLayout.INTRINSICS, fx)
        b.putFloat(IngestMessageLayout.INTRINSICS + 4, fy)
        b.putFloat(IngestMessageLayout.INTRINSICS + 8, cx)
        b.putFloat(IngestMessageLayout.INTRINSICS + 12, cy)
    }

    fun image(b: ByteBuffer, width: Int, height: Int, rowStrideBytes: Int, depthUnit: Int, confidenceFormat: Int) {
        b.putShort(IngestMessageLayout.WIDTH, width.toShort())
        b.putShort(IngestMessageLayout.HEIGHT, height.toShort())
        b.putShort(IngestMessageLayout.ROW_STRIDE, rowStrideBytes.toShort())
        b.put(IngestMessageLayout.DEPTH_UNIT, depthUnit.toByte())
        b.put(IngestMessageLayout.CONFIDENCE_FORMAT, confidenceFormat.toByte())
    }

    /** Total message length (header plus declared payload). */
    fun messageBytes(b: ByteBuffer): Int = IngestMessageLayout.HEADER_BYTES + payloadBytesOf(b)
}

/**
 * Tracking state byte (header offset 48). The contract leaves the values
 * open; the shell uses ARCore's three states. Mirrored by
 * `IngestTrackingState` in `lib/visibility_grid_shell/`.
 */
internal object IngestTrackingState {
    const val STOPPED = 0
    const val TRACKING = 1
    const val PAUSED = 2
}
