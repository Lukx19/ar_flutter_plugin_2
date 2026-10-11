package com.uhg0.ar_flutter_plugin_2.markerdetection

import com.uhg0.ar_flutter_plugin_2.visibilitycontract.IngestMessageLayout
import com.uhg0.ar_flutter_plugin_2.visibilityingest.DurablePhoto
import com.uhg0.ar_flutter_plugin_2.visibilityingest.DurablePhotoListener
import com.uhg0.ar_flutter_plugin_2.visibilityingest.IngestClock
import com.uhg0.ar_flutter_plugin_2.visibilityingest.IngestMessageSink
import com.uhg0.ar_flutter_plugin_2.visibilityingest.IngestMessageWriter
import com.uhg0.ar_flutter_plugin_2.visibilityingest.StillCameraModel
import com.uhg0.ar_flutter_plugin_2.visibilityingest.VisibilityGridIngestShell
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Full-resolution tie point detection of a durable photo ([PhotoTiePointProducer]). */
internal interface TiePointPhotoTarget {
    fun onPhotoDurable(
        photoId: ByteArray,
        eventBytes: ByteArray,
        jpegPath: () -> String?,
        groupEpoch: Int,
        distortion: DoubleArray?,
    )

    fun ensureMarkerSize(markerSizeMetres: Double)
}

/**
 * Registered on the ingest shell's durable photo listeners: while the shell
 * runs with marker detection on, every durable photo goes to tie point
 * detection with its committed JPEG (resolved on the detection thread), the
 * still intrinsics of its photo saved event and Camera2 `LENS_DISTORTION`.
 */
internal class TiePointPhotoRelay(
    private val target: TiePointPhotoTarget,
    private val shell: () -> VisibilityGridIngestShell?,
) : DurablePhotoListener {
    var relayed = 0L
        private set

    override fun onPhotoDurable(photo: DurablePhoto) {
        val s = shell() ?: return
        if (!s.started || !s.markerDetectionEnabled) return
        val size = s.markerSizeMetres
        if (size > 0f) target.ensureMarkerSize(size.toDouble())
        relayed++
        target.onPhotoDurable(photo.photoId, photo.event, { photo.jpegFile()?.path }, s.groupEpoch, distortionOf(photo.camera))
    }

    companion object {
        /**
         * Camera2 `LENS_DISTORTION` (k1, k2, k3, p1, p2) reordered to the
         * detector's (k1, k2, p1, p2, k3); null when not reported (all zero).
         * Both act on normalised image coordinates.
         */
        fun distortionOf(camera: StillCameraModel): DoubleArray? {
            val d = camera.distortion
            if (d.all { it == 0.0 }) return null
            return doubleArrayOf(d[0], d[1], d[3], d[4], d[2])
        }
    }
}

/**
 * Wraps each photo tie point batch (`C3TB` header and native records) into
 * an ingest kind 8 message and offers it to the shell's pull queue. Header
 * pose fields stay zero; sample and frame time are the exposure, the group
 * epoch is the batch's. One reused buffer; called on the detection thread.
 */
internal class TiePointIngestWriter(
    private val ingestSink: () -> IngestMessageSink?,
    private val clock: IngestClock,
) : TiePointBatchSink {
    private val message: ByteBuffer = ByteBuffer
        .allocateDirect(IngestMessageLayout.HEADER_BYTES + TiePointBatchLayout.HEADER_BYTES +
            MarkerDetectorNative.MAX_MARKERS * MarkerDetectorNative.TIE_POINT_BYTES)
        .order(ByteOrder.LITTLE_ENDIAN)

    var offered = 0L
        private set
    var refused = 0L
        private set

    override fun onTiePointBatch(batch: ByteBuffer, length: Int) {
        val sink = ingestSink()
        val total = IngestMessageLayout.HEADER_BYTES + length
        if (sink == null || length < TiePointBatchLayout.HEADER_BYTES || total > message.capacity()) {
            refused++
            return
        }
        val b = message
        IngestMessageWriter.begin(b, IngestMessageLayout.KIND_PHOTO_TIE_POINTS)
        val exposure = batch.getLong(TiePointBatchLayout.EXPOSURE_TIMESTAMP_NS)
        IngestMessageWriter.timing(b, exposure, exposure, clock.nowNs())
        b.put(IngestMessageLayout.POSE_MATCH, IngestMessageLayout.POSE_MATCH_MISSING.toByte())
        b.putShort(IngestMessageLayout.GROUP_EPOCH, batch.getShort(TiePointBatchLayout.GROUP_EPOCH))
        IngestMessageWriter.payloadBytes(b, length)
        for (i in 0 until length) b.put(IngestMessageLayout.HEADER_BYTES + i, batch.get(i))
        b.clear()
        if (sink.offer(b, total)) offered++ else refused++
    }
}
