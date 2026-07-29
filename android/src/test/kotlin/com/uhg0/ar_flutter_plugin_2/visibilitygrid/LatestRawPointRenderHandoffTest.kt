package com.uhg0.ar_flutter_plugin_2.visibilitygrid

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LatestRawPointRenderHandoffTest {
    @Test
    fun `movement burst keeps only the latest synthetic point snapshot`() {
        val handoff = LatestRawPointRenderHandoff<String>()

        assertTrue(handoff.offer("frame-1"))
        assertFalse(handoff.offer("frame-2"))
        assertFalse(handoff.offer("frame-3"))

        assertEquals("frame-3", handoff.takeLatest())
        assertTrue(handoff.offer("frame-4"))
        assertEquals("frame-4", handoff.takeLatest())
    }

    @Test
    fun `mode change clears a queued raw point snapshot`() {
        val handoff = LatestRawPointRenderHandoff<String>()

        assertTrue(handoff.offer("points-before-centroids"))
        handoff.clear()

        assertNull(handoff.takeLatest())
        assertTrue(handoff.offer("points-after-returning-to-points"))
    }
}
