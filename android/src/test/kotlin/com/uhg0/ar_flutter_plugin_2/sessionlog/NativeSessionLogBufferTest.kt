package com.uhg0.ar_flutter_plugin_2.sessionlog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeSessionLogBufferTest {
    private var now = 0L
    private val buffer = NativeSessionLogBuffer(capacity = 4, nowNs = { now })

    @Test
    fun floodsBecomeOneFullRecordAndOneCountPerDrain() {
        repeat(1000) {
            now += 1_000_000L
            buffer.record("DEPTH_STALE", "stale $it", mapOf("ageMs" to it))
        }
        val drained = buffer.drain()
        assertEquals(2, drained.size)
        assertEquals(listOf("F", "DEPTH_STALE", "warning", "stale 0", mapOf("ageMs" to 0), null), drained[0])
        assertEquals(listOf("C", "DEPTH_STALE", 999, "stale 999"), drained[1])
        assertTrue(buffer.drain().isEmpty())
    }

    @Test
    fun newVariantsAndSlowRepeatsAreWrittenInFull() {
        assertTrue(buffer.occur("NATIVE_HANDLER_ERROR", "a"))
        assertTrue(buffer.occur("NATIVE_HANDLER_ERROR", "b"))
        assertFalse(buffer.occur("NATIVE_HANDLER_ERROR", "a"))
        now += 12_000_000_000L
        assertTrue(buffer.occur("NATIVE_HANDLER_ERROR", "a"))
    }

    @Test
    fun aFullBufferCountsInsteadOfGrowing() {
        for (i in 0 until 6) buffer.record("CODE_$i", "m", variant = "v")
        val drained = buffer.drain()
        assertEquals(4, drained.count { it[0] == "F" && (it[1] as String).startsWith("CODE_") })
        assertEquals(2, drained.count { it[0] == "C" })
        assertEquals("LOG_EVENTS_DROPPED", drained.last()[1])
    }

    @Test
    fun resetForgetsPendingRecords() {
        buffer.record("TRACKING_LOST", "lost")
        buffer.reset()
        assertTrue(buffer.drain().isEmpty())
    }
}
