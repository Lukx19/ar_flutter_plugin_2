package com.uhg0.ar_flutter_plugin_2.visibilitygrid

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DepthObservationPauseGateTest {
    @Test
    fun `failed pause preparation restores depth admission`() {
        var suspended = false
        val gate = DepthObservationPauseGate(
            suspendDepth = { suspended = true },
            resumeDepth = { suspended = false },
        )

        gate.begin()
        assertTrue(suspended)
        gate.complete(success = false)
        assertFalse(suspended)
        gate.begin()
        gate.complete(success = true)
        assertTrue(suspended)
        gate.resumed()
        assertFalse(suspended)
    }

    @Test
    fun `overlapping pause attempts and failed resume do not reopen depth early`() {
        var suspended = false
        var resumed = 0
        val gate = DepthObservationPauseGate(
            suspendDepth = { suspended = true },
            resumeDepth = { suspended = false; resumed++ },
        )

        gate.begin()
        gate.begin()
        gate.complete(success = false)
        assertTrue(suspended)
        assertEquals(0, resumed)
        gate.complete(success = true)
        assertTrue(suspended)
        assertEquals(0, resumed)
        // A scene resume timeout leaves the session paused, so no resumed()
        // callback is issued. A later successful retry reopens depth once.
        gate.resumed()
        assertFalse(suspended)
        assertEquals(1, resumed)
    }
}
