package com.uhg0.ar_flutter_plugin_2.markerdetection

import com.uhg0.ar_flutter_plugin_2.visibilitycontract.VisibilityGridFrameTap
import com.uhg0.ar_flutter_plugin_2.visibilityingest.IngestClock
import com.uhg0.ar_flutter_plugin_2.visibilityingest.IngestMessageSink
import com.uhg0.ar_flutter_plugin_2.visibilityingest.VisibilityGridIngestShell

/**
 * Marker detection hosted by the visibility grid shell: a grey-image frame
 * tap that runs only while the shell is started with marker detection on
 * (control start or set marker detection) and offers each detection message
 * (ingest kind 5) into the shell's pull queue; and full-resolution tie point
 * detection of every durable photo ([photoListener], registered on the
 * shell's durable photo listeners) whose batches travel as ingest kind 8.
 *
 * Detection uses the free marker size of the control message; the grid
 * isolate rescales poses of markers with another printed size (tapes) from
 * the session's marker layout. Exists only when the detector library is
 * packaged (`CAPTURE3D_GRID_MARKERS` build), so the default build creates
 * nothing.
 */
internal class GridMarkerShellTaps private constructor(
    private val producer: MarkerDetectionProducer,
    private val tap: MarkerFrameTap,
    private val shell: () -> VisibilityGridIngestShell?,
    private val tiePoints: PhotoTiePointProducer,
    val tiePointWriter: TiePointIngestWriter,
) : AutoCloseable {
    /** Durable photo listener feeding tie point detection. */
    val photoListener = TiePointPhotoRelay(tiePoints, shell)

    /** Composite-tap entry: forwards frames while detection is on. */
    val frameTap = VisibilityGridFrameTap { frame ->
        val s = shell()
        if (s != null && s.started && s.markerDetectionEnabled) {
            val size = s.markerSizeMetres
            if (size > 0f) producer.requestMarkerSize(size.toDouble())
            tap.onFrame(frame)
        }
    }

    override fun close() {
        tap.close()
        tiePoints.close()
    }

    companion object {
        /** Detection rate when the control message leaves it open. */
        const val DEFAULT_HZ = 8

        /** The taps, or null when the detector library is not packaged. */
        fun create(
            ingestSink: () -> IngestMessageSink?,
            shell: () -> VisibilityGridIngestShell?,
            clock: IngestClock,
        ): GridMarkerShellTaps? {
            if (!MarkerDetectorNative.available) return null
            val producer = MarkerDetectionProducer(
                MarkerDetectionSink { message, length -> ingestSink()?.offer(message, length) },
            )
            val hz = shell()?.markerDetectionHz?.takeIf { it > 0 } ?: DEFAULT_HZ
            val tap = MarkerFrameTap(producer, groupEpoch = { shell()?.groupEpoch ?: 0 }, hz = hz)
            val writer = TiePointIngestWriter(ingestSink, clock)
            return GridMarkerShellTaps(producer, tap, shell, PhotoTiePointProducer(writer), writer)
        }
    }
}
