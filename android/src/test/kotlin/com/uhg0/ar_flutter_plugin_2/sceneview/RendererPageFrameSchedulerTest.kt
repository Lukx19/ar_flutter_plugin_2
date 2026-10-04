package com.uhg0.ar_flutter_plugin_2.sceneview

import org.junit.Assert.assertEquals
import org.junit.Test

class RendererPageFrameSchedulerTest {
    @Test
    fun `coalesces callback releases into one later frame`() {
        val scheduled = mutableListOf<() -> Unit>()
        val scheduler = RendererPageFrameScheduler(scheduled::add)
        var frames = 0

        scheduler.request { frames++ }
        scheduler.request { frames++ }
        assertEquals(1, scheduled.size)

        scheduled.single().invoke()
        assertEquals(1, frames)
    }

    @Test
    fun `cancel fences an old scheduled frame before replacement`() {
        val scheduled = mutableListOf<() -> Unit>()
        val scheduler = RendererPageFrameScheduler(scheduled::add)
        var frames = 0

        scheduler.request { frames++ }
        scheduler.cancel()
        scheduled.single().invoke()

        assertEquals(0, frames)
    }
}
