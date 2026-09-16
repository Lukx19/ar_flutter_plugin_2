package com.uhg0.ar_flutter_plugin_2.visibilitygrid

import org.junit.Assert.assertEquals
import org.junit.Test

class BoundedDebugTraceTest {
    @Test
    fun `trace is inert until armed and uses a strict ring bound`() {
        val trace = BoundedDebugTrace(3)
        trace.record("release-event")
        assertEquals(emptyList<String>(), trace.snapshot())

        trace.arm("armed")
        trace.record("one")
        trace.record("two")
        trace.record("three")
        assertEquals(listOf("one", "two", "three"), trace.snapshot())
    }

    @Test
    fun `clear disarms and removes retained entries`() {
        val trace = BoundedDebugTrace(2)
        trace.arm("armed")
        trace.clear()
        trace.record("after-disarm")
        assertEquals(emptyList<String>(), trace.snapshot())
    }
}
