package com.uhg0.ar_flutter_plugin_2.markerdetection

import com.uhg0.ar_flutter_plugin_2.sessionlog.NativeSessionLogEmitter
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import com.google.ar.core.Frame
import com.google.ar.core.TrackingState
import com.google.ar.core.exceptions.NotYetAvailableException
import com.uhg0.ar_flutter_plugin_2.visibilitycontract.IngestMessageLayout
import com.uhg0.ar_flutter_plugin_2.visibilitycontract.VisibilityGridFrameTap
import java.util.concurrent.atomic.AtomicBoolean

/** Detection side of [MarkerFrameTap]; [MarkerDetectionProducer] in the app. */
internal interface GreyFrameDetector : AutoCloseable {
    val available: Boolean

    /** Worker thread: detects markers on [frame]. */
    fun detect(frame: GreyFrame)

    /** Any thread: marker size the worker applies before its next detection. */
    fun requestMarkerSize(markerSizeMetres: Double)
}

/** Worker that runs the detection jobs of [MarkerFrameTap] one at a time. */
internal interface MarkerDetectionWorker {
    /** Queues [job]; false when the worker no longer accepts jobs. */
    fun post(job: Runnable): Boolean

    /** Runs [last] after a running job, then stops the worker. */
    fun quit(last: Runnable)
}

/** [MarkerDetectionWorker] on its own [HandlerThread]. */
internal class HandlerMarkerDetectionWorker(name: String = "capture3d-marker-detector") : MarkerDetectionWorker {
    private val thread = HandlerThread(name).apply { start() }
    private val handler = Handler(thread.looper)

    override fun post(job: Runnable): Boolean = handler.post(job)

    override fun quit(last: Runnable) {
        handler.post(last)
        thread.quitSafely()
    }
}

/**
 * Grey image tap of the marker detector: at most [hz] times a second it copies
 * the Y plane of the ARCore CPU image (the sensor-oriented camera pose at the
 * same timestamp, `Camera.getPose`, and the image intrinsics) into one
 * reusable buffer and hands it to a worker thread that runs the
 * [GreyFrameDetector]. A frame arriving while the worker is busy is skipped
 * and counted, so the frame thread never waits for detection.
 *
 * The tap runs inside the SceneView frame callback on the main thread, so it
 * never throws: an ARCore failure while acquiring or reading the CPU image
 * (`ResourceExhaustedException` while another CPU image is held,
 * `DeadlineExceededException`, `FatalException`, ...) is counted in
 * [captureFailures], the busy state is cleared on every path, and the next
 * frame after the detection period tries again.
 *
 * Live detection uses the CPU image at the resolution ARCore is configured
 * for; 1920x1080 is the measured choice (range to 4 m for 10 cm markers,
 * about 12 ms per image on the SM-X700).
 */
internal class MarkerFrameTap(
    private val detector: GreyFrameDetector,
    private val groupEpoch: () -> Int,
    hz: Int = 8,
    private val worker: MarkerDetectionWorker = HandlerMarkerDetectionWorker(),
    private val clock: () -> Long = SystemClock::elapsedRealtimeNanos,
) : VisibilityGridFrameTap, AutoCloseable {
    @Volatile private var periodNs = periodOf(hz)
    private val busy = AtomicBoolean(false)
    private val frame = GreyFrame()
    private val focal = FloatArray(2)
    private val principal = FloatArray(2)
    private var lastStartNs = Long.MIN_VALUE / 2
    private val job = Runnable {
        try {
            detector.detect(frame)
        } catch (e: RuntimeException) {
            detectionFailures++
            if (Integer.bitCount(detectionFailures) == 1) Log.w(TAG, "marker detection failed ($detectionFailures)", e)
            NativeSessionLogEmitter.failure(
                "MARKER_DETECTION_FAILED", "detect/${e.javaClass.simpleName}", "Marker detection failed",
            ) { mapOf("stage" to "detect", "detectionFailures" to detectionFailures, "error" to (e.message ?: "")) }
        } finally {
            busy.set(false)
        }
    }

    @Volatile var enabled = true
    var skippedBusy = 0
        private set
    var imagesUnavailable = 0
        private set

    /** ARCore failures while acquiring or reading the CPU image (frame thread). */
    @Volatile var captureFailures = 0
        private set

    /** Detector failures on the worker thread. */
    @Volatile var detectionFailures = 0
        private set

    /** Images the worker could not accept (stopped worker). */
    @Volatile var rejectedJobs = 0
        private set

    /** Detection rate in images per second (clamped to 1-30); applies from the next frame. */
    fun setRateHz(hz: Int) {
        periodNs = periodOf(hz)
    }

    override fun onFrame(frame: Frame) {
        if (!enabled || !detector.available) return
        val now = clock()
        if (now - lastStartNs < periodNs) return
        if (!busy.compareAndSet(false, true)) {
            skippedBusy++
            return
        }
        // Owned by this call until the job is posted: every early exit clears it.
        var handedOff = false
        try {
            val image = try {
                frame.acquireCameraImage()
            } catch (_: NotYetAvailableException) {
                imagesUnavailable++
                return
            }
            // A failing attempt also waits one period, so a held CPU image is not hammered every frame.
            lastStartNs = now
            copy(frame, image, now)
            if (worker.post(job)) {
                handedOff = true
            } else {
                rejectedJobs++
            }
        } catch (e: RuntimeException) {
            lastStartNs = now
            captureFailures++
            if (Integer.bitCount(captureFailures) == 1) Log.w(TAG, "marker image capture failed ($captureFailures)", e)
            NativeSessionLogEmitter.failure(
                "MARKER_DETECTION_FAILED", "capture/${e.javaClass.simpleName}", "Marker image capture failed",
            ) { mapOf("stage" to "capture", "captureFailures" to captureFailures, "error" to (e.message ?: "")) }
        } finally {
            if (!handedOff) busy.set(false)
        }
    }

    private fun copy(frame: Frame, image: android.media.Image, now: Long) {
        val target = this.frame
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
    }

    /** Stops the tap; the detector is closed on the worker after a running job. */
    override fun close() {
        enabled = false
        worker.quit { detector.close() }
    }

    private companion object {
        const val TAG = "MarkerFrameTap"

        fun periodOf(hz: Int): Long = 1_000_000_000L / hz.coerceIn(1, 30)
    }
}
