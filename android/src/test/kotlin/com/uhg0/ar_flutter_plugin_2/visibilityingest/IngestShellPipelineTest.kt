package com.uhg0.ar_flutter_plugin_2.visibilityingest

import com.google.ar.core.Config
import com.uhg0.ar_flutter_plugin_2.visibilitycontract.IngestMessageLayout
import com.uhg0.ar_flutter_plugin_2.visibilitycontract.NativeStatusCode
import com.uhg0.ar_flutter_plugin_2.visibilitycontract.ShellControlLayout
import com.uhg0.ar_flutter_plugin_2.sessionlog.NativeSessionLogEmitter
import com.uhg0.ar_flutter_plugin_2.sessionlog.data
import com.uhg0.ar_flutter_plugin_2.sessionlog.full
import com.uhg0.ar_flutter_plugin_2.sessionlog.withNativeSessionLog
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.IntBuffer

/**
 * Frames from a fake frame source through the producers and the pull
 * handler; the replies must equal the Dart-written goldens byte for byte.
 */
class IngestShellPipelineTest {
    private val ms = 1_000_000L
    private val clock = FakeClock()
    private val shell = VisibilityGridIngestShell(clock, null)
    private val frame = FakeFrame().apply {
        pose[0] = 0.5f; pose[1] = 1.25f; pose[2] = -2f
    }
    private val reply = CapturingReply()

    private fun start(flags: Int, preference: Int, epoch: Int = 3) {
        shell.start(IngestShellConfig(flags, preference, 0, 0, 0f, epoch))
    }

    private fun frameAt(ns: Long) {
        frame.timestampNs = ns
        frame.androidCameraTimestampNs = ns - SENSOR_CLOCK_BEHIND_NS
        shell.process(frame, SESSION)
    }

    /** Frames from 966.25 ms to 1,170 ms; the depth image (1,000 ms) falls 1.25 ms before the second. */
    private fun warmFrames() {
        frameAt(966_250_000L)
        var t = 1_001_250_000L
        while (t < 1_170_000_000L) {
            frameAt(t)
            t += 33 * ms
        }
    }

    /** Advances the per-kind sequence to 7 with two drops, as in the golden fixture. */
    private fun primeSequence(kind: Int, goldenName: String) {
        val message = ByteBuffer.allocateDirect(golden(goldenName).size).order(ByteOrder.LITTLE_ENDIAN)
        repeat(7) {
            message.clear()
            message.put(golden(goldenName))
            assertTrue(shell.pull.offer(message, message.capacity()))
            shell.pull.onPull(pullRequest(), reply)
        }
        shell.pull.countSourceDrop(kind)
        shell.pull.countSourceDrop(kind)
    }

    @Test
    fun `raw depth with confidence equals the depth golden`() {
        start(0, IngestShellConfig.DEPTH_PREFERRED)
        shell.depthMode = { Config.DepthMode.RAW_DEPTH_ONLY }
        primeSequence(IngestMessageLayout.KIND_DEPTH_IMAGE, "ingest_depth_u16.bin")
        warmFrames()
        frame.goldenDepth()
        frame.depthImageNs = 1_000_000_000L
        clock.now = 2_000_000_000L
        frameAt(1_170_000_000L)
        clock.now = 2_000_500_000L
        shell.pull.onPull(pullRequest(), reply)
        assertArrayEquals(golden("ingest_depth_u16.bin"), reply.bytes())
        assertEquals(1L, shell.depthProducer.published)
    }

    @Test
    fun `point cloud drops non-finite and far points and equals the points golden`() {
        start(ShellControlLayout.FLAG_POINT_CLOUD, IngestShellConfig.DEPTH_POINTS_ONLY)
        // The golden predates the confidence gate and carries a 0.25 point.
        shell.pointProducer.minConfidence = 0f
        primeSequence(IngestMessageLayout.KIND_POINT_CLOUD, "ingest_points.bin")
        warmFrames()
        val nan = Float.NaN
        frame.points = FloatBuffer.wrap(floatArrayOf(
            nan, 0f, 0f, 1f,
            0f, 1f, -1.5f, 0.25f,
            0f, Float.POSITIVE_INFINITY, 0f, 1f,
            0.5f, 1.25f, -1.5f, 0.5f,
            1f, 1f, nan, 1f,
            20_000f, 0f, 0f, 1f,
            1f, 1.5f, -1.5f, 0.75f,
        ))
        frame.ids = IntBuffer.wrap(intArrayOf(1, 100, 2, 101, 3, 4, 102))
        frame.cloudNs = 1_000_000_000L
        clock.now = 2_000_000_000L
        frameAt(1_170_000_000L)
        clock.now = 2_000_500_000L
        shell.pull.onPull(pullRequest(), reply)
        assertArrayEquals(golden("ingest_points.bin"), reply.bytes())
        val p = shell.pointProducer
        assertEquals(7L, p.pointsIn)
        assertEquals(3L, p.pointsSent)
        assertEquals(3L, p.nonFiniteDropped)
        assertEquals(1L, p.farDropped)
    }

    @Test
    fun `point cloud drops low-confidence points and points closer than 20 cm or farther than 4 m from the camera and keeps ids`() {
        start(ShellControlLayout.FLAG_POINT_CLOUD, IngestShellConfig.DEPTH_POINTS_ONLY)
        warmFrames()
        // Camera at (0.5, 1.25, -2).
        frame.points = FloatBuffer.wrap(floatArrayOf(
            0.5f, 1.25f, -3f, 0.9f, // 1 m: kept
            0.5f, 1.25f, -3f, 0.05f, // confidence 0.05: dropped
            0.5f, 1.25f, -3f, 0f, // confidence 0: dropped
            0.5f, 1.25f, -2.1f, 0.9f, // 0.1 m: too near
            0.5f, 1.25f, -52f, 0.9f, // 50 m: too far
            0.5f, 1.25f, -5.5f, 0.3f, // 3.5 m, confidence at the gate: kept
        ))
        frame.ids = IntBuffer.wrap(intArrayOf(11, 12, 13, 14, 15, 16))
        frame.cloudNs = 1_000_000_000L
        clock.now = 2_000_000_000L
        frameAt(1_170_000_000L)
        shell.pull.onPull(pullRequest(1 shl IngestMessageLayout.KIND_POINT_CLOUD), reply)
        val b = checkNotNull(reply.last)
        assertEquals(IngestMessageLayout.KIND_POINT_CLOUD, b.get(IngestMessageLayout.KIND).toInt())
        val p = IngestMessageLayout.HEADER_BYTES
        assertEquals(2, b.getInt(p))
        assertEquals(11, b.getInt(p + IngestMessageLayout.POINT_COUNT_BYTES))
        assertEquals(16, b.getInt(p + IngestMessageLayout.POINT_COUNT_BYTES + IngestMessageLayout.POINT_BYTES))
        val producer = shell.pointProducer
        assertEquals(2L, producer.pointsSent)
        assertEquals(2L, producer.lowConfidenceDropped)
        assertEquals(2L, producer.rangeDropped)
    }

    @Test
    fun `automatic depth is checked on the frame clock while the sensor clock is 5 s behind`() {
        start(0, IngestShellConfig.DEPTH_AUTOMATIC)
        shell.depthMode = { Config.DepthMode.AUTOMATIC }
        warmFrames()
        frame.goldenDepth()
        frame.confidence = null
        frame.depthImageNs = 1_170_000_000L
        clock.now = 2_000_000_000L
        frameAt(1_170_000_000L)
        val depth = shell.depthProducer
        assertEquals(0L, depth.identityRefused)
        assertEquals(1L, depth.published)
        // An older image of the same clock is counted, not refused.
        clock.now += 300 * ms
        frame.depthImageNs = 1_190_000_000L
        frameAt(1_203_000_000L)
        assertEquals(0L, depth.identityRefused)
        assertEquals(1L, depth.identityStaleAutomatic)
        assertEquals(2L, depth.published)
        // An image newer than its frame is refused.
        clock.now += 300 * ms
        frame.depthImageNs = 1_300_000_000L
        frameAt(1_236_000_000L)
        assertEquals(1L, depth.identityRefused)
        assertEquals(2L, depth.published)
    }

    @Test
    fun `depth older than 500 ms is dropped at the source and counted in the next message`() {
        start(0, IngestShellConfig.DEPTH_PREFERRED)
        shell.depthMode = { Config.DepthMode.RAW_DEPTH_ONLY }
        warmFrames()
        frame.goldenDepth()
        frame.depthImageNs = 600 * ms // 570 ms older than its frame
        frameAt(1_170_000_000L)
        assertEquals(1L, shell.depthProducer.staleDropped)
        clock.now += 300 * ms
        frame.depthImageNs = 1_210 * ms
        frameAt(1_203_000_000L)
        // The image leads the newest pose: it waits for the frame that covers it.
        assertEquals(0L, shell.depthProducer.published)
        frameAt(1_236_000_000L)
        assertEquals(1L, shell.depthProducer.published)
        shell.pull.onPull(pullRequest(), reply)
        val b = checkNotNull(reply.last)
        assertEquals(IngestMessageLayout.KIND_DEPTH_IMAGE, b.get(IngestMessageLayout.KIND).toInt())
        assertEquals(1, b.getInt(IngestMessageLayout.DROPPED))
        assertEquals(IngestMessageLayout.POSE_MATCH_INTERPOLATED, b.get(IngestMessageLayout.POSE_MATCH).toInt())
        assertEquals(1_203_000_000L, b.getLong(IngestMessageLayout.FRAME_TIMESTAMP_NS))
    }

    @Test
    fun `depth drops before a session log opens are not reported as new in it`() {
        NativeSessionLogEmitter.setActive(false)
        start(0, IngestShellConfig.DEPTH_PREFERRED)
        shell.depthMode = { Config.DepthMode.RAW_DEPTH_ONLY }
        warmFrames()
        frame.goldenDepth()
        frame.depthImageNs = 600 * ms
        frameAt(1_170_000_000L)
        assertEquals(1L, shell.depthProducer.staleDropped)
        val records = withNativeSessionLog {
            clock.now += 1_000 * ms
            frame.depthImageNs = 700 * ms // 600 ms older than its frame
            frameAt(1_300_000_000L)
            assertEquals(2L, shell.depthProducer.staleDropped)
            clock.now += 1_000 * ms
            frameAt(1_333_000_000L)
        }
        val stale = records.full("DEPTH_STALE").first()
        assertEquals(2L, stale.data["total"])
        assertEquals("only the drop while the log was open is new", 1L, stale.data["new"])
    }

    @Test
    fun `session events report tracking, depth mode and AR session changes`() {
        start(0, IngestShellConfig.DEPTH_AUTOMATIC)
        shell.depthMode = { Config.DepthMode.AUTOMATIC }
        frameAt(100 * ms)
        frame.trackingState = IngestTrackingState.PAUSED
        frame.trackingFailureReason = EXCESSIVE_MOTION
        frameAt(133 * ms)
        frame.trackingState = IngestTrackingState.TRACKING
        frame.trackingFailureReason = 0
        frameAt(166 * ms)
        shell.depthMode = { Config.DepthMode.DISABLED }
        frameAt(200 * ms)
        shell.process(frame.apply { timestampNs = 233 * ms }, OTHER_SESSION)
        val events = drainSessionEvents()
        assertEquals(
            listOf(
                IngestMessageLayout.SESSION_TRACKING_LOST to IngestTrackingState.PAUSED.toLong(),
                IngestMessageLayout.SESSION_TRACKING_REGAINED to 0L,
                IngestMessageLayout.SESSION_TRACKING_RESET to
                    (IngestMessageLayout.TRACKING_RESET_TRACKING_FAILURE.toLong() or (EXCESSIVE_MOTION.toLong() shl 8)),
                IngestMessageLayout.SESSION_DEPTH_MODE_CHANGED to IngestFrameSource.DEPTH_MODE_DISABLED.toLong(),
                IngestMessageLayout.SESSION_AR_RESTARTED to 4L,
            ),
            events,
        )
        assertEquals(4, shell.groupEpoch)
    }

    private fun drainSessionEvents(): List<Pair<Int, Long>> {
        val events = mutableListOf<Pair<Int, Long>>()
        while (true) {
            reply.last = null
            shell.pull.onPull(pullRequest(1 shl IngestMessageLayout.KIND_SESSION_EVENT), reply)
            val b = reply.last ?: break
            if (b.get(IngestMessageLayout.KIND).toInt() != IngestMessageLayout.KIND_SESSION_EVENT) break
            events += (b.get(IngestMessageLayout.HEADER_BYTES).toInt() to b.getLong(IngestMessageLayout.HEADER_BYTES + 8))
        }
        return events
    }

    @Test
    fun `an AR session resume and an anchor jump emit tracking reset events within the same group epoch`() {
        start(0, IngestShellConfig.DEPTH_AUTOMATIC)
        shell.depthMode = { Config.DepthMode.AUTOMATIC }
        var reapplied = 0
        shell.depthReapply = { reapplied++ }
        frameAt(100 * ms)
        frameAt(133 * ms)
        shell.onArSessionResumed()
        frameAt(2_133 * ms)
        shell.onAnchorJump(2_133 * ms, 120)
        assertEquals(
            listOf(
                IngestMessageLayout.SESSION_AR_RESUMED to 2_000 * ms,
                IngestMessageLayout.SESSION_TRACKING_RESET to IngestMessageLayout.TRACKING_RESET_AR_RESUMED.toLong(),
                IngestMessageLayout.SESSION_TRACKING_RESET to
                    (IngestMessageLayout.TRACKING_RESET_ANCHOR_JUMP.toLong() or (120L shl 8)),
            ),
            drainSessionEvents(),
        )
        assertEquals(3, shell.groupEpoch)
        assertEquals(1, reapplied)
        assertEquals(2L, shell.trackingResets)
    }

    @Test
    fun `depth acquisition continues after two AR session resumes`() {
        start(0, IngestShellConfig.DEPTH_AUTOMATIC)
        shell.depthMode = { Config.DepthMode.AUTOMATIC }
        // Fake ARCore after a shared camera resume: depth stays "not yet available"
        // until the depth mode is applied to the session again.
        shell.depthReapply = { frame.depthResult = IngestFrameSource.DEPTH_OK }
        frame.goldenDepth()
        frame.confidence = null
        var t = 1_000 * ms
        fun run(frames: Int) {
            repeat(frames) {
                t += 33 * ms
                clock.now = t
                frame.depthImageNs = t
                frameAt(t)
            }
        }
        val depth = shell.depthProducer
        run(30)
        assertTrue(depth.acquired > 0)
        repeat(2) { resume ->
            val before = depth.acquired
            val published = depth.published
            frame.depthResult = IngestFrameSource.DEPTH_NOT_YET_AVAILABLE
            t += 2_000 * ms
            shell.onArSessionResumed()
            run(30)
            assertTrue("acquisition continues after resume ${resume + 1}", depth.acquired > before)
            assertTrue("publication continues after resume ${resume + 1}", depth.published > published)
        }
        assertEquals(2L, shell.depthReapplyRequests)
    }

    @Test
    fun `depth that stops for 3 s while tracking asks for the depth mode at most every 5 s`() {
        start(0, IngestShellConfig.DEPTH_AUTOMATIC)
        shell.depthMode = { Config.DepthMode.AUTOMATIC }
        var reapplied = 0
        shell.depthReapply = { reapplied++ }
        frame.goldenDepth()
        frame.confidence = null
        var t = 1_000 * ms
        fun run(frames: Int) {
            repeat(frames) {
                t += 100 * ms
                clock.now = t
                frame.depthImageNs = t
                frameAt(t)
            }
        }
        run(10)
        frame.depthResult = IngestFrameSource.DEPTH_NOT_YET_AVAILABLE
        run(29)
        assertEquals("2.9 s without depth", 0, reapplied)
        run(2)
        assertEquals(1, reapplied)
        run(45)
        assertEquals("not again within 5 s", 1, reapplied)
        run(5)
        assertEquals("one more after the 5 s interval", 2, reapplied)
    }

    @Test
    fun `depth that keeps returning the same old image for 3 s while tracking asks for the depth mode`() {
        // Playback smoke: ARCore answered every acquisition with its last depth image
        // (acquired grew, the image timestamp did not, staleDropped grew) and the depth
        // mode was never re-applied.
        start(0, IngestShellConfig.DEPTH_AUTOMATIC)
        shell.depthMode = { Config.DepthMode.AUTOMATIC }
        var reapplied = 0
        shell.depthReapply = { reapplied++ }
        frame.goldenDepth()
        frame.confidence = null
        var t = 1_000 * ms
        fun run(frames: Int, freshImages: Boolean) {
            repeat(frames) {
                t += 100 * ms
                clock.now = t
                if (freshImages) frame.depthImageNs = t
                frameAt(t)
            }
        }
        run(10, freshImages = true)
        val acquiredBefore = shell.depthProducer.acquired
        run(29, freshImages = false)
        assertTrue("the same image is still acquired", shell.depthProducer.acquired > acquiredBefore)
        assertEquals("2.9 s of the same image", 0, reapplied)
        run(2, freshImages = false)
        assertEquals(1, reapplied)
        run(39, freshImages = true)
        assertEquals("fresh images are progress", 1, reapplied)
    }

    @Test
    fun `control channel starts the shell from the start golden and rejects malformed messages`() {
        val handler = GridShellControlHandler(shell = { shell }, markerDetectorAvailable = { true })
        val start = ByteBuffer.wrap(golden("shell_control_start.bin"))
        assertEquals(NativeStatusCode.OK, handler.handle(start))
        assertTrue(shell.started)
        val config = checkNotNull(shell.config)
        assertEquals(ShellControlLayout.FLAG_MARKER_DETECTION or ShellControlLayout.FLAG_POINT_CLOUD, config.flags)
        assertEquals(IngestShellConfig.DEPTH_PREFERRED, config.depthSourcePreference)
        assertEquals(8, config.markerDetectionHz)
        assertEquals(0.12f, config.markerSizeMetres)
        assertEquals(3, config.groupEpoch)
        assertTrue(shell.markerDetectionEnabled)

        val badVersion = golden("shell_control_start.bin").also { it[4] = 9 }
        assertEquals(NativeStatusCode.CONTRACT_VERSION_MISMATCH, handler.handle(ByteBuffer.wrap(badVersion)))
        assertEquals(NativeStatusCode.CONTRACT_MESSAGE_MALFORMED, handler.handle(ByteBuffer.allocate(8)))
        // Recording is unavailable without a recorder (release builds).
        val record = golden("shell_control_start.bin").also {
            it[ShellControlLayout.KIND] = ShellControlLayout.KIND_SET_RECORDING.toByte()
            it[ShellControlLayout.FLAGS] = ShellControlLayout.FLAG_RECORDING.toByte()
        }
        assertEquals(NativeStatusCode.RECORDING_WRITE_FAILED, handler.handle(ByteBuffer.wrap(record)))

        val stop = golden("shell_control_start.bin").also { it[ShellControlLayout.KIND] = ShellControlLayout.KIND_STOP.toByte() }
        assertEquals(NativeStatusCode.OK, handler.handle(ByteBuffer.wrap(stop)))
        assertFalse(shell.started)
    }

    @Test
    fun `stopped shell ignores frames`() {
        frameAt(100 * ms)
        assertEquals(0L, shell.depthProducer.acquired)
        assertEquals(0, frame.depthCalls)
    }

    @Test
    fun `steady state allocates nothing per frame and pull`() {
        start(ShellControlLayout.FLAG_POINT_CLOUD, IngestShellConfig.DEPTH_PREFERRED)
        shell.depthMode = { Config.DepthMode.RAW_DEPTH_ONLY }
        frame.goldenDepth()
        val cloud = FloatArray(4 * 64) { if (it % 4 == 3) 0.5f else (it % 7).toFloat() }
        cloud[0] = Float.NaN
        frame.points = FloatBuffer.wrap(cloud)
        frame.ids = IntBuffer.wrap(IntArray(64) { it })
        val pull = pullRequest()
        var t = 1_000 * ms
        fun run(frames: Int) {
            for (i in 0 until frames) {
                t += 33 * ms
                clock.now = t + 2_000 * ms
                frame.depthImageNs = t - 170 * ms
                frame.cloudNs = t + 66 * ms
                frame.timestampNs = t
                frame.androidCameraTimestampNs = t - SENSOR_CLOCK_BEHIND_NS
                shell.process(frame, SESSION)
                pull.clear()
                shell.pull.onPull(pull, reply)
                shell.pull.onPull(pull, reply)
            }
        }
        run(2_000) // warm up, JIT
        // HotSpot keeps compiling (and occasionally deoptimising, which
        // rematerialises scalar-replaced objects on the heap) for thousands
        // of frames after warm-up; such one-off events land in a different
        // window depending on the loaded class set. A per-frame allocation
        // (at least one 16 B object per frame) shows in every window, so
        // steady state is the quietest window and no window may average a
        // byte per frame.
        val probe = AllocationProbe()
        val overhead = probe.measure { }
        val windows = LongArray(STEADY_WINDOWS) { probe.measure { run(STEADY_WINDOW_FRAMES) } - overhead }
        assertTrue(shell.depthProducer.published > 100)
        assertTrue(shell.pointProducer.published > 100)
        assertEquals("bytes allocated in the quietest steady-state window ${windows.toList()}", 0L, windows.min())
        for (bytes in windows) {
            assertTrue("per-frame allocation in ${windows.toList()}", bytes < STEADY_WINDOW_FRAMES)
        }
    }

    /** Thread allocation counter through reflection (the Android compile classpath has no java.lang.management). */
    private class AllocationProbe {
        private val bean = Class.forName("java.lang.management.ManagementFactory").getMethod("getThreadMXBean").invoke(null)
        private val read = Class.forName("com.sun.management.ThreadMXBean").getMethod("getThreadAllocatedBytes", Long::class.javaPrimitiveType)
        private val thread: Any = Thread.currentThread().id

        init {
            Class.forName("com.sun.management.ThreadMXBean")
                .getMethod("setThreadAllocatedMemoryEnabled", Boolean::class.javaPrimitiveType).invoke(bean, true)
            repeat(10) { measure { } }
        }

        fun measure(block: () -> Unit): Long {
            val before = read.invoke(bean, thread) as Long
            block()
            return (read.invoke(bean, thread) as Long) - before
        }
    }

    private companion object {
        const val STEADY_WINDOWS = 8
        const val STEADY_WINDOW_FRAMES = 500
        val SESSION = Any()
        val OTHER_SESSION = Any()
        const val EXCESSIVE_MOTION = 3
    }
}
