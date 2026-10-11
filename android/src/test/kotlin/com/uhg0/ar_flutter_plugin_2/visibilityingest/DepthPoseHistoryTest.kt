package com.uhg0.ar_flutter_plugin_2.visibilityingest

import com.uhg0.ar_flutter_plugin_2.visibilitycontract.IngestMessageLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sqrt

class DepthPoseHistoryTest {
    private val out = FloatArray(16)
    private val ms = 1_000_000L

    private fun push(h: DepthPoseHistory, t: Long, x: Float, yawQuarter: Boolean = false) {
        // Rotation about y: identity or 90 degrees.
        val s = if (yawQuarter) (sqrt(0.5)).toFloat() else 0f
        val w = if (yawQuarter) (sqrt(0.5)).toFloat() else 1f
        h.push(t, x, 0f, 0f, 0f, s, 0f, w)
    }

    @Test
    fun `exact match returns the stored pose`() {
        val h = DepthPoseHistory()
        push(h, 100 * ms, 1f)
        push(h, 133 * ms, 2f)
        assertEquals(IngestMessageLayout.POSE_MATCH_EXACT, h.match(100 * ms, out))
        assertEquals(1f, out[12])
        assertEquals(0L, h.errorNs)
        assertEquals(IngestMessageLayout.POSE_MATCH_EXACT, h.match(133 * ms, out))
        assertEquals(2f, out[12])
    }

    @Test
    fun `interpolates translation and slerps rotation between neighbours`() {
        val h = DepthPoseHistory()
        push(h, 0L, 0f)
        push(h, 40 * ms, 4f, yawQuarter = true)
        assertEquals(IngestMessageLayout.POSE_MATCH_INTERPOLATED, h.match(10 * ms, out))
        assertEquals(1f, out[12], 1e-5f)
        assertEquals(10 * ms, h.errorNs)
        assertEquals(40 * ms, h.spanNs)
        // A quarter of a 90 degree yaw: 22.5 degrees, so m[0] = cos(22.5 deg).
        assertEquals(Math.cos(Math.toRadians(22.5)).toFloat(), out[0], 1e-5f)
        // Nearest neighbour sign: closer to the newer entry gives a negative gap.
        h.match(35 * ms, out)
        assertEquals(-5 * ms, h.errorNs)
    }

    @Test
    fun `nearest within 20 ms outside the ring and missing beyond it`() {
        val h = DepthPoseHistory()
        push(h, 100 * ms, 1f)
        push(h, 133 * ms, 2f)
        assertEquals(IngestMessageLayout.POSE_MATCH_NEAREST_WITHIN_20_MS, h.match(150 * ms, out))
        assertEquals(2f, out[12])
        assertEquals(17 * ms, h.errorNs)
        assertEquals(IngestMessageLayout.POSE_MATCH_NEAREST_WITHIN_20_MS, h.match(85 * ms, out))
        assertEquals(IngestMessageLayout.POSE_MATCH_MISSING, h.match(160 * ms, out))
        assertEquals(IngestMessageLayout.POSE_MATCH_MISSING, h.match(70 * ms, out))
    }

    @Test
    fun `empty ring is missing and too wide gaps are not interpolated`() {
        val h = DepthPoseHistory()
        assertEquals(IngestMessageLayout.POSE_MATCH_MISSING, h.match(1L, out))
        push(h, 0L, 0f)
        push(h, 1_000 * ms, 10f)
        // 1 s frame gap: the 10 ms side is a nearest match, the middle is missing.
        assertEquals(IngestMessageLayout.POSE_MATCH_NEAREST_WITHIN_20_MS, h.match(10 * ms, out))
        assertEquals(0f, out[12])
        assertEquals(IngestMessageLayout.POSE_MATCH_MISSING, h.match(500 * ms, out))
    }

    @Test
    fun `ring wraps around and forgets the oldest poses`() {
        val h = DepthPoseHistory(capacity = 4)
        for (i in 0 until 10) push(h, i * 33 * ms, i.toFloat())
        assertEquals(4, h.count)
        assertEquals(6 * 33 * ms, h.oldestNs)
        assertEquals(9 * 33 * ms, h.newestNs)
        assertEquals(IngestMessageLayout.POSE_MATCH_EXACT, h.match(7 * 33 * ms, out))
        assertEquals(7f, out[12])
        assertEquals(IngestMessageLayout.POSE_MATCH_MISSING, h.match(2 * 33 * ms, out))
        assertFalse("older timestamps are ignored", h.push(5 * 33 * ms, 0f, 0f, 0f, 0f, 0f, 0f, 1f))
    }

    @Test
    fun `default ring holds 256 frames, about 8 s at 30 Hz`() {
        val h = DepthPoseHistory()
        for (i in 0 until 300) push(h, i * 33 * ms, i.toFloat())
        assertEquals(256, h.count)
        assertEquals(IngestMessageLayout.POSE_MATCH_EXACT, h.match(60 * 33 * ms, out))
    }

    @Test
    fun `samples newer than the newest pose wait for a covering frame or six frames`() {
        val h = DepthPoseHistory()
        assertFalse(h.readyToMatch(0L, 10))
        push(h, 100 * ms, 0f)
        assertFalse(h.readyToMatch(166 * ms, 0))
        assertFalse(h.readyToMatch(166 * ms, 5))
        assertTrue(h.readyToMatch(166 * ms, 6))
        push(h, 166 * ms, 0f)
        assertTrue(h.readyToMatch(166 * ms, 0))
    }

    @Test
    fun `depth older than 500 ms relative to its frame is stale`() {
        assertFalse(DepthPoseHistory.isStaleDepth(1_170 * ms, 1_000 * ms))
        assertFalse(DepthPoseHistory.isStaleDepth(1_500 * ms, 1_000 * ms))
        assertTrue(DepthPoseHistory.isStaleDepth(1_501 * ms, 1_000 * ms))
        assertTrue(DepthPoseHistory.isStaleDepth(22_000 * ms, 1_000 * ms))
    }
}
