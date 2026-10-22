package com.uhg0.ar_flutter_plugin_2.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PoseUpdateRateLimiterTest {
    @Test
    fun `does not emit above 30 hz`() {
        val limiter = PoseUpdateRateLimiter(maxRateHz = 30)

        assertTrue(limiter.shouldEmit(1_000_000_000L))
        assertFalse(limiter.shouldEmit(1_020_000_000L))
        assertTrue(limiter.shouldEmit(1_034_000_000L))
    }

    @Test
    fun `emits every frame of a jittered 30 fps camera`() {
        val limiter = PoseUpdateRateLimiter(maxRateHz = 30)
        var timestampNs = 1_000_000_000L
        var emitted = 0
        for (index in 0 until 300) {
            timestampNs += if (index % 2 == 0) 33_200_000L else 33_400_000L
            if (limiter.shouldEmit(timestampNs)) emitted += 1
        }

        // 300 frames span about 10 s of sensor time: about 30 Hz, not 15 Hz.
        assertEquals(300, emitted)
    }

    @Test
    fun `emits every other frame of a 60 fps camera`() {
        val limiter = PoseUpdateRateLimiter(maxRateHz = 30)
        var timestampNs = 1_000_000_000L
        var emitted = 0
        for (index in 0 until 600) {
            timestampNs += if (index % 2 == 0) 16_600_000L else 16_733_334L
            if (limiter.shouldEmit(timestampNs)) emitted += 1
        }

        assertEquals(300, emitted)
    }

    @Test
    fun `suppresses duplicate and out of order timestamps`() {
        val limiter = PoseUpdateRateLimiter(maxRateHz = 30)

        assertTrue(limiter.shouldEmit(1_000_000_000L))
        assertFalse(limiter.shouldEmit(1_000_000_000L))
        assertFalse(limiter.shouldEmit(999_000_000L))
        assertTrue(limiter.shouldEmit(1_040_000_000L))
        assertFalse(limiter.shouldEmit(1_050_000_000L))
        assertTrue(limiter.shouldEmit(1_080_000_000L))
    }

    @Test
    fun `does not emit while paused and resumes cleanly`() {
        val limiter = PoseUpdateRateLimiter(maxRateHz = 30)

        assertTrue(limiter.shouldEmit(1_000_000_000L))
        limiter.onPause()

        assertFalse(limiter.shouldEmit(1_040_000_000L))
        assertFalse(limiter.shouldEmit(1_080_000_000L))

        limiter.onResume()

        assertTrue(limiter.shouldEmit(1_120_000_000L))
        assertFalse(limiter.shouldEmit(1_130_000_000L))
    }

    @Test
    fun `does not emit after dispose`() {
        val limiter = PoseUpdateRateLimiter(maxRateHz = 30)

        assertTrue(limiter.shouldEmit(1_000_000_000L))
        limiter.onDispose()

        assertFalse(limiter.shouldEmit(1_040_000_000L))
        assertFalse(limiter.shouldEmit(1_080_000_000L))
    }
}
