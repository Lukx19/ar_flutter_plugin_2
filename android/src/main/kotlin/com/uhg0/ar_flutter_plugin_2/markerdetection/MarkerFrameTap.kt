package com.uhg0.ar_flutter_plugin_2.markerdetection

import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import com.google.ar.core.Frame
import com.google.ar.core.TrackingState
import com.google.ar.core.exceptions.NotYetAvailableException
import com.uhg0.ar_flutter_plugin_2.visibilitycontract.IngestMessageLayout
import com.uhg0.ar_flutter_plugin_2.visibilitycontract.VisibilityGridFrameTap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Grey image tap of the marker detector: at most [hz] times a second it copies
 * the Y plane of the ARCore CPU image (the sensor-oriented camera pose at the
 * same timestamp, `Camera.getPose`, and the image intrinsics) into one
 * reusable buffer and hands it to a worker thread that runs
 * [MarkerDetectionProducer]. A frame arriving while the worker is busy is
 * skipped and counted, so the frame thread never waits for detection.
 *
 * Live detection uses the CPU image at the resolution ARCore is configured
 * for; 1920x1080 is the measured choice (range to 4 m for 10 cm markers,
 * about 12 ms per image on the SM-X700).
 */
internal class MarkerFrameTap(
    private val producer: MarkerDetectionProducer,
    private val groupEpoch: () -> Int,
    hz: Int = 8,
) : VisibilityGridFrameTap, AutoCloseable {
    private val periodNs = 1_000_000_000L / hz.coerceIn(1, 30)
    private val thread = HandlerThread("capture3d-marker-detector").apply { start() }
    private val worker = Handler(thread.looper)
    private val busy = AtomicBoolean(false)
    private val frame = GreyFrame()
    private val focal = FloatArray(2)
    private val principal = FloatArray(2)
    private var lastStartNs = Long.MIN_VALUE / 2
    private val job = Runnable {
        try {
            producer.produce(frame)
        } finally {
            busy.set(false)
        }
    }

    @Volatile var enabled = true
    var skippedBusy = 0
        private set
    var imagesUnavailable = 0
        private set

    override fun onFrame(frame: Frame) {
        if (!enabled || !producer.available) return
        val now = SystemClock.elapsedRealtimeNanos()
        if (now - lastStartNs < periodNs) return
        if (!busy.compareAndSet(false, true)) {
            skippedBusy++
            return
        }
        val image = try {
            frame.acquireCameraImage()
        } catch (_: NotYetAvailableException) {
            imagesUnavailable++
            busy.set(false)
            return
        }
        lastStartNs = now
        val target = this.frame
        try {
            image.use {
                val plane = it.planes[0]
                val rowStride = plane.rowStride
                val width = it.width
                val height = it.height
                val source = plane.buffer
                val bytes = rowStride * (height - 1) + width
                target.ensureCapacity(bytes)
                val grey = target.grey
                grey.clear()
                source.rewind()
                source.limit(minOf(source.capacity(), bytes))
                grey.put(source)
                grey.rewind()
                target.width = width
                target.height = height
                target.stride = rowStride
            }
            val camera = frame.camera
            val h = target.header
            h.sampleTimestampNs = frame.timestamp
            h.frameTimestampNs = frame.timestamp
            h.acquireTimeNs = now
            val tracking = camera.trackingState == TrackingState.TRACKING
            h.trackingState = if (tracking) 1 else 0
            h.poseMatch = if (tracking) IngestMessageLayout.POSE_MATCH_EXACT else IngestMessageLayout.POSE_MATCH_MISSING
            h.groupEpoch = groupEpoch()
            h.poseGapUs = 0
            h.dropped = skippedBusy
            camera.pose.toMatrix(h.cameraToWorld, 0)
            val intrinsics = camera.imageIntrinsics
            intrinsics.getFocalLength(focal, 0)
            intrinsics.getPrincipalPoint(principal, 0)
            val dims = intrinsics.imageDimensions
            h.fx = focal[0]
            h.fy = focal[1]
            h.cx = principal[0]
            h.cy = principal[1]
            target.intrinsicsWidth = dims[0]
            target.intrinsicsHeight = dims[1]
        } catch (e: RuntimeException) {
            busy.set(false)
            throw e
        }
        worker.post(job)
    }

    /** Stops the tap; the producer is closed on the worker after a running job. */
    override fun close() {
        enabled = false
        worker.post { producer.close() }
        thread.quitSafely()
    }
}
