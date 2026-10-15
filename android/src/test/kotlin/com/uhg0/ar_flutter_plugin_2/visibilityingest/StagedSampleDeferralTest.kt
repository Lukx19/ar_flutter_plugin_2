package com.uhg0.ar_flutter_plugin_2.visibilityingest

import com.uhg0.ar_flutter_plugin_2.visibilitycontract.IngestMessageLayout
import com.uhg0.ar_flutter_plugin_2.visibilitycontract.ShellControlLayout
import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.FloatBuffer
import java.nio.IntBuffer

/**
 * A staged sample ahead of the newest pose waits for at most
 * [DepthPoseHistory.MAX_DEFER_FRAMES] new frame poses. A repeated frame
 * timestamp (ARCore playback hands the same frame out again) adds no pose
 * and must not use up that wait.
 */
class StagedSampleDeferralTest {
    private val ms = 1_000_000L
    private val clock = FakeClock()
    private val shell = VisibilityGridIngestShell(clock, null)
    private val frame = FakeFrame()
    private val reply = CapturingReply()
    private val start = 1_000 * ms

    private fun frameAt(ns: Long) {
        frame.timestampNs = ns
        frame.androidCameraTimestampNs = ns
        shell.process(frame, SESSION)
    }

    /** Stages one point cloud at [cloudNs] on the frame at [frameNs]. */
    private fun stageCloudAt(frameNs: Long, cloudNs: Long) {
        frame.points = FloatBuffer.wrap(floatArrayOf(0f, 0f, -1f, 1f))
        frame.ids = IntBuffer.wrap(intArrayOf(1))
        frame.cloudNs = cloudNs
        clock.now += 200 * ms
        frameAt(frameNs)
        frame.points = null
        frame.ids = null
    }

    private fun pulledPoseMatch(): Int {
        shell.pull.onPull(pullRequest(1 shl IngestMessageLayout.KIND_POINT_CLOUD), reply)
        assertEquals(IngestMessageLayout.KIND_POINT_CLOUD, reply.kind())
        return reply.bytes()[IngestMessageLayout.POSE_MATCH].toInt()
    }

    private fun startPointsOnly() {
        shell.start(IngestShellConfig(ShellControlLayout.FLAG_POINT_CLOUD, IngestShellConfig.DEPTH_POINTS_ONLY, 0, 0, 0f, 1))
        frameAt(start - 33 * ms)
    }

    @Test
    fun `repeated frame timestamps do not use up the deferral and a covering pose interpolates`() {
        startPointsOnly()
        stageCloudAt(start, start + 120 * ms)
        repeat(10) { frameAt(start) }
        assertEquals(0L, shell.pointProducer.published)

        for (k in 1..4) frameAt(start + k * 33 * ms)
        assertEquals(1L, shell.pointProducer.published)
        assertEquals(IngestMessageLayout.POSE_MATCH_INTERPOLATED, pulledPoseMatch())
    }

    @Test
    fun `a sample without a covering pose is sent missing after six new poses`() {
        startPointsOnly()
        stageCloudAt(start, start + 1_000 * ms)
        for (k in 1..5) frameAt(start + k * 33 * ms)
        assertEquals(0L, shell.pointProducer.published)

        frameAt(start + 6 * 33 * ms)
        assertEquals(1L, shell.pointProducer.published)
        assertEquals(IngestMessageLayout.POSE_MATCH_MISSING, pulledPoseMatch())
    }

    @Test
    fun `a tracking reset releases the staged sample`() {
        startPointsOnly()
        stageCloudAt(start, start + 120 * ms)
        frame.trackingState = IngestTrackingState.PAUSED
        frameAt(start + 33 * ms)
        frame.trackingState = IngestTrackingState.TRACKING
        for (k in 2..12) frameAt(start + k * 33 * ms)
        assertEquals(0L, shell.pointProducer.published)
    }

    private companion object {
        val SESSION = Any()
    }
}
