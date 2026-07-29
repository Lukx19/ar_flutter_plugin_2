package com.uhg0.ar_flutter_plugin_2.capture

import org.junit.Assert.assertEquals
import org.junit.Test

class SharedCameraStartupBarrierTest {
    @Test
    fun `awaitReady returns after successful configuration`() {
        val barrier = SharedCameraStartupBarrier()

        barrier.markConfigured()
        barrier.awaitReady(timeoutMs = 1)
    }

    @Test
    fun `awaitReady surfaces startup failure`() {
        val barrier = SharedCameraStartupBarrier()

        barrier.fail("boom")

        val error =
            try {
                barrier.awaitReady(timeoutMs = 1)
                null
            } catch (failure: IllegalStateException) {
                failure
            }

        requireNotNull(error)
        assertEquals("boom", error.message)
    }

    @Test
    fun `awaitReady preserves typed startup failure`() {
        val barrier = SharedCameraStartupBarrier()
        val expected =
            SharedCameraStartupException(
                reason = SharedCameraStartupFailureReason.SESSION_CONFIGURATION,
                message = "configuration failed",
            )

        barrier.fail(expected)

        val error =
            try {
                barrier.awaitReady(timeoutMs = 1)
                null
            } catch (failure: SharedCameraStartupException) {
                failure
            }

        assertEquals(expected, error)
    }

    @Test
    fun `awaitReady times out when startup never completes`() {
        val barrier = SharedCameraStartupBarrier()

        val error =
            try {
                barrier.awaitReady(timeoutMs = 1)
                null
            } catch (failure: IllegalStateException) {
                failure
            }

        requireNotNull(error)
        assertEquals("Timed out waiting for shared camera startup", error.message)
    }
}
