package com.uhg0.ar_flutter_plugin_2.capture

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
    fun `preserves stable ordering for nondecreasing timestamps`() {
        val limiter = PoseUpdateRateLimiter(maxRateHz = 30)

        assertTrue(limiter.shouldEmit(1_000_000_000L))
        assertTrue(limiter.shouldEmit(1_000_000_000L))
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
