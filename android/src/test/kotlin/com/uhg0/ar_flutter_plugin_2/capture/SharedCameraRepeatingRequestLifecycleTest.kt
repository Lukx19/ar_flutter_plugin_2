package com.uhg0.ar_flutter_plugin_2.capture

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SharedCameraRepeatingRequestLifecycleTest {
    @Test
    fun `runtime updates are suppressed until repeating request has started`() {
        val lifecycle = SharedCameraRepeatingRequestLifecycle()

        assertFalse(lifecycle.shouldSubmitRuntimeUpdate())
        assertFalse(lifecycle.onSessionPaused())
        assertFalse(lifecycle.onSessionResumed())
    }

    @Test
    fun `pause stops one active repeating request and resume restarts it once`() {
        val lifecycle = SharedCameraRepeatingRequestLifecycle()
        lifecycle.markRepeatingStarted()

        assertTrue(lifecycle.shouldSubmitRuntimeUpdate())
        assertTrue(lifecycle.onSessionPaused())
        assertFalse(lifecycle.shouldSubmitRuntimeUpdate())
        assertFalse(lifecycle.onSessionPaused())
        assertTrue(lifecycle.onSessionResumed())
        assertTrue(lifecycle.shouldSubmitRuntimeUpdate())
        assertFalse(lifecycle.onSessionResumed())
    }
}
