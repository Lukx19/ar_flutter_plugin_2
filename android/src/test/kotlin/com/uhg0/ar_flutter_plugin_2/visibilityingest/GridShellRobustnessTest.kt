package com.uhg0.ar_flutter_plugin_2.visibilityingest

import com.google.ar.core.Frame
import com.google.ar.core.exceptions.ResourceExhaustedException
import com.uhg0.ar_flutter_plugin_2.visibilitycontract.NativeStatusCode
import com.uhg0.ar_flutter_plugin_2.visibilitycontract.ShellControlLayout
import com.uhg0.ar_flutter_plugin_2.visibilitycontract.VisibilityGridFrameTap
import com.uhg0.ar_flutter_plugin_2.sessionlog.data
import com.uhg0.ar_flutter_plugin_2.sessionlog.full
import com.uhg0.ar_flutter_plugin_2.sessionlog.withNativeSessionLog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer

/** Frame tap containment of the shell registry and marker availability of the control channel. */
class GridShellRobustnessTest {
    @Test
    fun `a throwing frame tap is counted and the other taps and the frame callback continue`() {
        var later = 0
        val throwing = VisibilityGridFrameTap { throw ResourceExhaustedException() }
        val counting = VisibilityGridFrameTap { later++ }
        VisibilityGridShellRegistry.addFrameTap(throwing)
        VisibilityGridShellRegistry.addFrameTap(counting)
        try {
            val before = VisibilityGridShellRegistry.frameTapFailures
            val frame = object : Frame() {}
            VisibilityGridShellRegistry.onArFrame(Any(), frame)
            VisibilityGridShellRegistry.onArFrame(Any(), frame)
            assertEquals(2, later)
            assertEquals(before + 2, VisibilityGridShellRegistry.frameTapFailures)
            VisibilityGridShellRegistry.frameTap.onFrame(frame)
            assertEquals(3, later)
            assertEquals(before + 3, VisibilityGridShellRegistry.frameTapFailures)
        } finally {
            VisibilityGridShellRegistry.removeFrameTap(throwing)
            VisibilityGridShellRegistry.removeFrameTap(counting)
        }
    }

    @Test
    fun `marker detection is refused with MARKER_DETECTOR_UNAVAILABLE when the detector is not packaged`() {
        val shell = VisibilityGridIngestShell(FakeClock(), null)
        val handler = GridShellControlHandler(shell = { shell }, markerDetectorAvailable = { false })
        val start = golden("shell_control_start.bin")
        assertTrue(start[ShellControlLayout.FLAGS].toInt() and ShellControlLayout.FLAG_MARKER_DETECTION != 0)
        assertEquals(NativeStatusCode.MARKER_DETECTOR_UNAVAILABLE, handler.handle(ByteBuffer.wrap(start)))
        assertFalse(shell.started)

        val withoutMarkers = start.copyOf().also {
            it[ShellControlLayout.FLAGS] = (it[ShellControlLayout.FLAGS].toInt() and ShellControlLayout.FLAG_MARKER_DETECTION.inv()).toByte()
        }
        assertEquals(NativeStatusCode.OK, handler.handle(ByteBuffer.wrap(withoutMarkers)))
        assertTrue(shell.started)
        assertFalse(shell.markerDetectionEnabled)

        val enable = start.copyOf().also { it[ShellControlLayout.KIND] = ShellControlLayout.KIND_SET_MARKER_DETECTION.toByte() }
        assertEquals(NativeStatusCode.MARKER_DETECTOR_UNAVAILABLE, handler.handle(ByteBuffer.wrap(enable)))
        assertFalse(shell.markerDetectionEnabled)
        val disable = withoutMarkers.copyOf().also { it[ShellControlLayout.KIND] = ShellControlLayout.KIND_SET_MARKER_DETECTION.toByte() }
        assertEquals(NativeStatusCode.OK, handler.handle(ByteBuffer.wrap(disable)))
    }

    @Test
    fun `a started shell logs depth telemetry once a second`() {
        val clock = FakeClock()
        val lines = ArrayList<String>()
        val shell = VisibilityGridIngestShell(clock, null, telemetryLocator(), { lines += it })
        shell.depthMode = { com.google.ar.core.Config.DepthMode.RAW_DEPTH_ONLY }
        shell.start(IngestShellConfig(0, IngestShellConfig.DEPTH_PREFERRED, 0, 0, 0f, 1))
        val frame = FakeFrame().apply { goldenDepth() }
        var t = 1_000_000_000L
        for (i in 0 until 75) { // 2.5 s at 30 Hz
            t += 33_333_333L
            clock.now = t
            frame.timestampNs = t
            frame.androidCameraTimestampNs = t
            frame.depthImageNs = t - 1_000_000L
            if (i == 40) frame.trackingState = IngestTrackingState.PAUSED
            shell.process(frame, null)
        }
        assertEquals(3, lines.size)
        assertTrue(lines[0], lines[0].startsWith("[grid-depth] published="))
        for (key in listOf("duplicate=", "staleDropped=", "identityRefused=", "staleAutomatic=", "lastImageAgeMs=")) {
            assertTrue(key, lines[2].contains(key))
        }
        assertTrue(lines[0], lines[0].contains("lastImageAgeMs=none"))
        assertTrue(lines[2], lines[2].contains("tracking=${IngestTrackingState.PAUSED}"))
    }

    @Test
    fun `a throwing frame tap is a NATIVE_HANDLER_ERROR session log record`() {
        val throwing = VisibilityGridFrameTap { throw ResourceExhaustedException() }
        VisibilityGridShellRegistry.addFrameTap(throwing)
        try {
            val records = withNativeSessionLog { VisibilityGridShellRegistry.onArFrame(Any(), object : Frame() {}) }
            val error = records.full("NATIVE_HANDLER_ERROR").single()
            assertEquals("gridFrameTap/ResourceExhaustedException", error[5])
        } finally {
            VisibilityGridShellRegistry.removeFrameTap(throwing)
        }
    }

    @Test
    fun `duplicate depth images reach the session log once a second from the depth counters`() {
        val clock = FakeClock()
        val shell = VisibilityGridIngestShell(clock, null, telemetryLocator())
        shell.depthMode = { com.google.ar.core.Config.DepthMode.RAW_DEPTH_ONLY }
        shell.start(IngestShellConfig(0, IngestShellConfig.DEPTH_PREFERRED, 0, 0, 0f, 1))
        val frame = FakeFrame().apply { goldenDepth() }
        var t = 1_000_000_000L
        frame.depthImageNs = t
        val records = withNativeSessionLog {
            for (i in 0 until 75) { // 2.5 s at 30 Hz, the same depth image every frame
                t += 33_333_333L
                clock.now = t
                frame.timestampNs = t
                frame.androidCameraTimestampNs = t
                shell.process(frame, null)
            }
        }
        val duplicates = records.full("DEPTH_DUPLICATE").single()
        assertTrue(duplicates.data.toString(), (duplicates.data["total"] as Long) > 0L)
        assertEquals(shell.depthProducer.duplicateDropped > 0L, true)
    }

    private fun telemetryLocator() = DurableJpegLocator(null)
}
