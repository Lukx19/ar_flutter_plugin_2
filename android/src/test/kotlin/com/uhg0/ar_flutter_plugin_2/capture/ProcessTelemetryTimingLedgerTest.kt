package com.uhg0.ar_flutter_plugin_2.capture

import org.junit.Assert.*
import org.junit.Test

class ProcessTelemetryTimingLedgerTest {
    private fun records(snapshot: Map<String, Any?>) = snapshot["records"] as List<*>
    private fun stages(snapshot: Map<String, Any?>): List<*> =
        (records(snapshot).first() as Map<*, *>)["stages"] as List<*>

    @Test fun `sampler records the exact reader order and complete stage boundaries`() {
        var now = 100L
        val ledger = ProcessTelemetryTimingLedger { now++ }
        val order = mutableListOf<String>()
        ledger.reset()
        val sampler = ProcessTelemetrySampler(
            { order.add("pss"); 42L }, { order.add("fd"); 3 },
            { order.add("threads"); 7 }, ledger,
        )
        assertEquals(ProcessTelemetryValues(42L, 3, 7), sampler.sample())
        assertEquals(listOf("pss", "fd", "threads"), order)
        stages(ledger.snapshot()).forEachIndexed { index, row ->
            val stage = row as Map<*, *>
            assertEquals("completed", stage["status"])
            assertEquals(101L + index * 2, stage["startNs"])
            assertEquals(102L + index * 2, stage["endNs"])
            assertEquals(true, stage["clockQualified"])
        }
    }

    @Test fun `failed reader is timed and later readers do not run`() {
        var now = 0L
        val ledger = ProcessTelemetryTimingLedger { now++ }
        ledger.reset()
        val sampler = ProcessTelemetrySampler({ 1L }, { error("fd unavailable") },
            { fail("thread reader must not run"); 0 }, ledger)
        try { sampler.sample(); fail("failure must propagate") }
        catch (expected: IllegalStateException) { assertEquals("fd unavailable", expected.message) }
        val rows = stages(ledger.snapshot())
        assertEquals("completed", (rows[0] as Map<*, *>)["status"])
        assertEquals("failed", (rows[1] as Map<*, *>)["status"])
        assertEquals("notStarted", (rows[2] as Map<*, *>)["status"])
        assertNull((rows[2] as Map<*, *>)["startNs"])
    }

    @Test fun `held pending receipt stays detached and reset fences old completion`() {
        var now = 10L
        val ledger = ProcessTelemetryTimingLedger { now++ }
        ledger.reset()
        val old = ledger.beginSample()
        ledger.start(old, ProcessTelemetryTimingLedger.Stage.PSS)
        val held = ledger.snapshot()
        ledger.reset(refreshPending = true)
        ledger.finish(old, ProcessTelemetryTimingLedger.Stage.PSS, true)
        val current = ledger.snapshot()
        assertEquals("pending", (stages(held)[0] as Map<*, *>)["status"])
        assertNull((stages(held)[0] as Map<*, *>)["endNs"])
        assertEquals(1L, held["epoch"])
        assertEquals(2L, current["epoch"])
        assertEquals(true, current["resetWhileRefreshPending"])
        assertEquals(1L, current["staleStageWrites"])
        assertTrue(records(current).isEmpty())
    }

    @Test fun `first samples survive capacity overflow and close fences writes`() {
        val ledger = ProcessTelemetryTimingLedger { 10L }
        assertEquals(-1L, ledger.beginSample())
        ledger.reset()
        repeat(35) { ledger.beginSample() }
        val held = ledger.snapshot()
        assertEquals(32, records(held).size)
        assertEquals(35L, held["offeredSamples"])
        assertEquals(3L, held["droppedSamples"])
        assertEquals(2048, held["scalarBytes"])
        assertEquals(1L, (records(held).first() as Map<*, *>)["sample"])
        assertEquals(32L, (records(held).last() as Map<*, *>)["sample"])
        ledger.close()
        ledger.start(32L, ProcessTelemetryTimingLedger.Stage.PSS)
        ledger.reset()
        assertEquals(-1L, ledger.beginSample())
        assertEquals(records(held), records(ledger.snapshot()))
        assertEquals(1L, ledger.snapshot()["epoch"])
    }

    @Test fun `reversed clock is explicitly unqualified and absent ledger preserves values`() {
        var now = 10L
        val ledger = ProcessTelemetryTimingLedger { now-- }
        ledger.reset()
        ProcessTelemetrySampler({ null }, { null }, { null }, ledger).sample()
        assertEquals(false, (stages(ledger.snapshot())[0] as Map<*, *>)["clockQualified"])
        assertEquals(ProcessTelemetryValues(8L, 2, 9),
            ProcessTelemetrySampler({ 8L }, { 2 }, { 9 }, null).sample())
    }
}
