package com.uhg0.ar_flutter_plugin_2.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class SharedCameraRecreationGuardTest {
    @Test
    fun `restart gate preserves a bounded vendor drain window`() {
        val gate = SharedCameraRestartGate(cooldownMs = 3_500L)

        assertEquals(0L, gate.restartDelayMs(nowMs = 100L, completionPollMs = 50L))

        val generation = gate.markShutdownStarted()

        assertEquals(50L, gate.restartDelayMs(nowMs = 100L, completionPollMs = 50L))
        gate.markShutdownCompleted(generation = generation, nowMs = 100L)
        assertEquals(3_500L, gate.restartDelayMs(nowMs = 100L, completionPollMs = 50L))
        assertEquals(1L, gate.restartDelayMs(nowMs = 3_599L, completionPollMs = 50L))
        assertEquals(0L, gate.restartDelayMs(nowMs = 3_600L, completionPollMs = 50L))
    }

    @Test
    fun `restart remains blocked until the matching shutdown completes`() {
        val gate = SharedCameraRestartGate(cooldownMs = 3_500L)

        val first = gate.markShutdownStarted()
        val second = gate.markShutdownStarted()

        gate.markShutdownCompleted(generation = first, nowMs = 100L)
        assertEquals(50L, gate.restartDelayMs(nowMs = 10_000L, completionPollMs = 50L))

        gate.markShutdownCompleted(generation = second, nowMs = 10_000L)
        assertEquals(3_500L, gate.restartDelayMs(nowMs = 10_000L, completionPollMs = 50L))
    }

    @Test
    fun `restart remains blocked when newer shutdown completes first`() {
        val gate = SharedCameraRestartGate(cooldownMs = 3_500L)
        val first = gate.markShutdownStarted()
        val second = gate.markShutdownStarted()

        gate.markShutdownCompleted(generation = second, nowMs = 100L)
        assertEquals(50L, gate.restartDelayMs(nowMs = 10_000L, completionPollMs = 50L))

        gate.markShutdownCompleted(generation = first, nowMs = 10_000L)
        assertEquals(3_500L, gate.restartDelayMs(nowMs = 10_000L, completionPollMs = 50L))
    }

    @Test
    fun `callback guard converts ARCore callback exception into failure`() {
        val expected = IllegalArgumentException("configure failed")
        var observed: Exception? = null

        SharedCameraCallbackGuard.run(
            onFailure = { observed = it },
        ) {
            throw expected
        }

        assertSame(expected, observed)
    }

    @Test
    fun `callback guard preserves successful callback`() {
        var invoked = false

        SharedCameraCallbackGuard.run(
            onFailure = { throw AssertionError("unexpected failure", it) },
        ) {
            invoked = true
        }

        assertTrue(invoked)
    }

    @Test
    fun `startup retry policy accepts transient configure failures`() {
        val transient =
            SharedCameraStartupException(
                reason = SharedCameraStartupFailureReason.SESSION_CONFIGURATION,
                message = "configure failed",
            )

        assertTrue(
            SharedCameraStartupRetryPolicy.shouldRetry(
                RuntimeException("startup failed", transient),
            ),
        )
    }

    @Test
    fun `startup retry policy rejects stable capability failures`() {
        assertFalse(
            SharedCameraStartupRetryPolicy.shouldRetry(
                IllegalStateException("configureFailed: Broken pipe"),
            ),
        )
    }
}
