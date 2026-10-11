package com.uhg0.ar_flutter_plugin_2.markerdetection

import android.media.Image
import com.google.ar.core.Frame
import com.google.ar.core.exceptions.FatalException
import com.google.ar.core.exceptions.NotYetAvailableException
import com.google.ar.core.exceptions.ResourceExhaustedException
import com.uhg0.ar_flutter_plugin_2.visibilityingest.FakeClock
import com.uhg0.ar_flutter_plugin_2.visibilityingest.IngestShellConfig
import com.uhg0.ar_flutter_plugin_2.visibilityingest.VisibilityGridIngestShell
import com.uhg0.ar_flutter_plugin_2.visibilitycontract.ShellControlLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Failure containment and rate of the marker grey image tap (frame thread). */
class MarkerFrameTapTest {
    private val ms = 1_000_000L
    private var now = 0L
    private val detector = FakeDetector()
    private val worker = ManualWorker()

    private fun tap(hz: Int = 10) = MarkerFrameTap(detector, groupEpoch = { 0 }, hz = hz, worker = worker, clock = { now })

    @Test
    fun `an ARCore exception while acquiring the image is counted and never leaves the tap busy`() {
        val tap = tap()
        val frame = ThrowingFrame { throw ResourceExhaustedException() }
        tap.onFrame(frame)
        assertEquals(1, tap.captureFailures)
        assertEquals(1, frame.acquires)
        // Within the period: no retry storm while the CPU image is held elsewhere.
        now += 50 * ms
        tap.onFrame(frame)
        assertEquals(1, frame.acquires)
        // After the period the tap tries again: busy was cleared.
        now += 60 * ms
        tap.onFrame(frame)
        assertEquals(2, frame.acquires)
        assertEquals(2, tap.captureFailures)
        assertEquals(0, tap.skippedBusy)
        frame.failure = { throw FatalException("lost") }
        now += 200 * ms
        tap.onFrame(frame)
        assertEquals(3, tap.captureFailures)
        now += 200 * ms
        tap.onFrame(frame)
        assertEquals(4, frame.acquires)
    }

    @Test
    fun `a failure while reading an acquired image is contained and clears busy`() {
        val tap = tap()
        // A null image fails inside the copy (after the acquire), the path that used to rethrow.
        val frame = ThrowingFrame { null }
        tap.onFrame(frame)
        assertEquals(1, tap.captureFailures)
        now += 200 * ms
        tap.onFrame(frame)
        assertEquals(2, frame.acquires)
        assertEquals(0, tap.skippedBusy)
        assertEquals(0, worker.jobs.size)
    }

    @Test
    fun `an image not yet available retries on the next frame`() {
        val tap = tap()
        val frame = ThrowingFrame { throw NotYetAvailableException() }
        tap.onFrame(frame)
        tap.onFrame(frame)
        assertEquals(2, tap.imagesUnavailable)
        assertEquals(0, tap.captureFailures)
    }

    @Test
    fun `the detection rate follows setRateHz`() {
        val tap = tap(hz = 10)
        val frame = ThrowingFrame { throw ResourceExhaustedException() }
        for (i in 0 until 10) {
            tap.onFrame(frame)
            now += 50 * ms
        }
        assertEquals(5, frame.acquires) // 10 Hz over 500 ms
        tap.setRateHz(20)
        for (i in 0 until 10) {
            tap.onFrame(frame)
            now += 50 * ms
        }
        assertEquals(15, frame.acquires) // 20 Hz over 500 ms
    }

    @Test
    fun `marker shell taps apply the rate of the latest control message`() {
        val clock = FakeClock()
        val shell = VisibilityGridIngestShell(clock, null)
        val tap = tap(hz = GridMarkerShellTaps.DEFAULT_HZ)
        val writer = TiePointIngestWriter({ null }, clock)
        val taps = GridMarkerShellTaps(detector, tap, { shell }, PhotoTiePointProducer(writer), writer)
        shell.start(IngestShellConfig(ShellControlLayout.FLAG_MARKER_DETECTION, 0, 0, 5, 0.1f, 1))
        val frame = ThrowingFrame { throw ResourceExhaustedException() }
        for (i in 0 until 20) {
            taps.frameTap.onFrame(frame)
            now += 50 * ms
        }
        assertEquals(5, frame.acquires) // 5 Hz over 1 s, not the 8 Hz default
        shell.setMarkerDetection(true, 20, 0.1f)
        for (i in 0 until 20) {
            taps.frameTap.onFrame(frame)
            now += 50 * ms
        }
        assertEquals(25, frame.acquires) // 20 Hz over 1 s
        shell.setMarkerDetection(true, 0, 0.1f)
        for (i in 0 until 20) {
            taps.frameTap.onFrame(frame)
            now += 50 * ms
        }
        // 0 means the 8 Hz default: the 125 ms period is met every third 50 ms frame (6 starts).
        assertEquals(31, frame.acquires)
        assertEquals(0.1, detector.markerSize, 1e-6)
    }

    @Test
    fun `a detection failure on the worker is counted and clears busy`() {
        val tap = tap()
        detector.failure = IllegalStateException("native")
        // The worker runs the tap's job after a hand-off; run it directly (a real CPU image cannot be built on the JVM).
        val busy = privateField(tap, "busy") as java.util.concurrent.atomic.AtomicBoolean
        busy.set(true)
        (privateField(tap, "job") as Runnable).run()
        assertEquals(1, tap.detectionFailures)
        assertEquals(false, busy.get())
        val frame = ThrowingFrame { throw ResourceExhaustedException() }
        tap.onFrame(frame)
        assertEquals(1, frame.acquires)
        assertEquals(0, tap.skippedBusy)
    }

    private fun privateField(tap: MarkerFrameTap, name: String): Any {
        val field = MarkerFrameTap::class.java.getDeclaredField(name)
        field.isAccessible = true
        return field.get(tap)
    }

    private class FakeDetector : GreyFrameDetector {
        var failure: RuntimeException? = null
        var detections = 0
        var markerSize = 0.0
        override val available = true
        override fun detect(frame: GreyFrame) {
            failure?.let { throw it }
            detections++
        }

        override fun requestMarkerSize(markerSizeMetres: Double) {
            markerSize = markerSizeMetres
        }

        override fun close() = Unit
    }

    private class ManualWorker : MarkerDetectionWorker {
        val jobs = ArrayList<Runnable>()
        var accept = true
        override fun post(job: Runnable): Boolean {
            if (accept) jobs += job
            return accept
        }

        override fun quit(last: Runnable) = last.run()
    }

    /** ARCore frame whose CPU image acquisition runs [failure]. */
    private class ThrowingFrame(var failure: () -> Image?) : Frame() {
        var acquires = 0
        override fun acquireCameraImage(): Image {
            acquires++
            return erased(failure())
        }
    }

    private companion object {
        /** Passes a null image through ARCore's non-null signature, as a broken image source would. */
        @Suppress("UNCHECKED_CAST")
        fun <T> erased(value: Any?): T = value as T
    }
}
