package com.uhg0.ar_flutter_plugin_2.markerdetection

import com.uhg0.ar_flutter_plugin_2.visibilitycontract.IngestMessageLayout
import com.uhg0.ar_flutter_plugin_2.visibilitycontract.MarkerDetectionLayout
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Builds ingest messages of kind 5 (marker detections) in one direct buffer
 * allocated up front: 160 B header with the camera pose at the grey image
 * timestamp, 8 B prefix (count, image width and height), then 64 B records.
 * The native detector writes the records straight to [RECORDS_OFFSET].
 */
internal class MarkerDetectionMessageWriter(val maxRecords: Int = 64) {
    val buffer: ByteBuffer = ByteBuffer
        .allocateDirect(RECORDS_OFFSET + maxRecords * MarkerDetectionLayout.BYTES)
        .order(ByteOrder.LITTLE_ENDIAN)

    /** Fields of one message besides the records. */
    class Header {
        var sequence = 0
        var sampleTimestampNs = 0L
        var frameTimestampNs = 0L
        var acquireTimeNs = 0L
        var sendTimeNs = 0L
        var trackingState = 0
        var poseMatch = IngestMessageLayout.POSE_MATCH_EXACT
        var groupEpoch = 0
        var poseGapUs = 0
        val cameraToWorld = FloatArray(16)
        var fx = 0f
        var fy = 0f
        var cx = 0f
        var cy = 0f
        var dropped = 0
    }

    /**
     * Writes header and prefix for [count] records already in place; returns
     * the message length.
     */
    fun finish(header: Header, count: Int, imageWidth: Int, imageHeight: Int): Int {
        require(count in 0..maxRecords)
        val b = buffer
        for (i in 0 until RECORDS_OFFSET) b.put(i, 0)
        val payload = IngestMessageLayout.MARKER_PREFIX_BYTES + count * MarkerDetectionLayout.BYTES
        b.putInt(0, IngestMessageLayout.MAGIC)
        b.putShort(4, IngestMessageLayout.VERSION.toShort())
        b.put(IngestMessageLayout.KIND, IngestMessageLayout.KIND_MARKER_DETECTIONS.toByte())
        b.putInt(IngestMessageLayout.SEQUENCE, header.sequence)
        b.putInt(IngestMessageLayout.PAYLOAD_BYTES, payload)
        b.putLong(IngestMessageLayout.SAMPLE_TIMESTAMP_NS, header.sampleTimestampNs)
        b.putLong(IngestMessageLayout.FRAME_TIMESTAMP_NS, header.frameTimestampNs)
        b.putLong(IngestMessageLayout.ACQUIRE_TIME_NS, header.acquireTimeNs)
        b.putLong(IngestMessageLayout.SEND_TIME_NS, header.sendTimeNs)
        b.put(IngestMessageLayout.TRACKING_STATE, header.trackingState.toByte())
        b.put(IngestMessageLayout.POSE_MATCH, header.poseMatch.toByte())
        b.putShort(IngestMessageLayout.GROUP_EPOCH, header.groupEpoch.toShort())
        b.putInt(IngestMessageLayout.POSE_GAP_US, header.poseGapUs)
        for (i in 0 until 16) b.putFloat(IngestMessageLayout.CAMERA_TO_WORLD + 4 * i, header.cameraToWorld[i])
        b.putFloat(IngestMessageLayout.INTRINSICS, header.fx)
        b.putFloat(IngestMessageLayout.INTRINSICS + 4, header.fy)
        b.putFloat(IngestMessageLayout.INTRINSICS + 8, header.cx)
        b.putFloat(IngestMessageLayout.INTRINSICS + 12, header.cy)
        b.putShort(IngestMessageLayout.WIDTH, imageWidth.toShort())
        b.putShort(IngestMessageLayout.HEIGHT, imageHeight.toShort())
        b.putInt(IngestMessageLayout.DROPPED, header.dropped)
        val p = IngestMessageLayout.HEADER_BYTES
        b.putShort(p, count.toShort())
        b.putShort(p + 2, imageWidth.toShort())
        b.putShort(p + 4, imageHeight.toShort())
        return IngestMessageLayout.HEADER_BYTES + payload
    }

    companion object {
        const val RECORDS_OFFSET = IngestMessageLayout.HEADER_BYTES + IngestMessageLayout.MARKER_PREFIX_BYTES
    }
}
