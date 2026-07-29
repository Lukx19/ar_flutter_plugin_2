package com.uhg0.ar_flutter_plugin_2.visibilitygrid

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VisibilityGridLifecycleGuardTest {
    @Test
    fun `pause resume recreation and disposal permanently reject stale callbacks`() {
        val guard = VisibilityGridLifecycleGuard()
        val initial = guard.token()
        assertTrue(guard.allows(initial))

        guard.pause()
        guard.pause()
        assertFalse(guard.allows(initial))
        val paused = guard.token()
        assertFalse(guard.allows(paused))

        guard.resume()
        guard.resume()
        val resumed = guard.token()
        assertTrue(guard.allows(resumed))
        assertFalse(guard.allows(initial))
        assertFalse(guard.allows(paused))

        guard.advance()
        val recreated = guard.token()
        assertTrue(guard.allows(recreated))
        assertFalse(guard.allows(resumed))

        guard.dispose()
        guard.dispose()
        assertFalse(guard.allows(recreated))
        assertFalse(guard.allows(guard.token()))
    }

    @Test
    fun `checkpoint snapshot is allowed for the current paused epoch`() {
        val guard = VisibilityGridLifecycleGuard()

        guard.pause()
        val paused = guard.token()

        assertFalse(guard.allows(paused))
        assertTrue(guard.allowsCheckpoint(paused))

        guard.resume()
        assertFalse(guard.allowsCheckpoint(paused))

        guard.dispose()
        assertFalse(guard.allowsCheckpoint(guard.token()))
    }
}
