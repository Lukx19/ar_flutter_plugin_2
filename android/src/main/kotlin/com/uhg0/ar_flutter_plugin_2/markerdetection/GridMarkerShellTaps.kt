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
 * Detection uses the free marker size and the detection rate of the latest
 * control message (start or set marker detection; rate 0 means
 * [DEFAULT_HZ]); the grid
 * isolate rescales poses of markers with another printed size (tapes) from
 * the session's marker layout. Exists only when the detector library is
 * packaged (`CAPTURE3D_GRID_MARKERS` build), so the default build creates
 * nothing.
 */
internal class GridMarkerShellTaps internal constructor(
    private val producer: GreyFrameDetector,
    private val tap: MarkerFrameTap,
    private val shell: () -> VisibilityGridIngestShell?,
    private val tiePoints: PhotoTiePointProducer,
    val tiePointWriter: TiePointIngestWriter,
) : AutoCloseable {
    /** Durable photo listener feeding tie point detection. */
    val photoListener = TiePointPhotoRelay(tiePoints, shell)

    private var appliedHz = -1

    /** Composite-tap entry: forwards frames while detection is on. */
    val frameTap = VisibilityGridFrameTap { frame ->
        val s = shell()
        if (s != null && s.started && s.markerDetectionEnabled) {
            val size = s.markerSizeMetres
            if (size > 0f) producer.requestMarkerSize(size.toDouble())
            val hz = s.markerDetectionHz
            if (hz != appliedHz) {
                appliedHz = hz
                tap.setRateHz(rateHz(hz))
            }
            tap.onFrame(frame)
        }
    }

    /** The grey image tap, for diagnostics. */
    val markerFrameTap: MarkerFrameTap get() = tap

    override fun close() {
        tap.close()
        tiePoints.close()
    }

    companion object {
        /** Detection rate when the control message leaves it open. */
        const val DEFAULT_HZ = 8

        /** Detection rate of a control message rate ([DEFAULT_HZ] when 0). */
        fun rateHz(controlHz: Int): Int = if (controlHz > 0) controlHz else DEFAULT_HZ

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
            val tap = MarkerFrameTap(producer, groupEpoch = { shell()?.groupEpoch ?: 0 }, hz = DEFAULT_HZ)
            val writer = TiePointIngestWriter(ingestSink, clock)
            return GridMarkerShellTaps(producer, tap, shell, PhotoTiePointProducer(writer), writer)
        }
    }
}
