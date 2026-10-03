package com.uhg0.ar_flutter_plugin_2.visibilitygrid

import com.uhg0.ar_flutter_plugin_2.visibilityprotocol.CurrentDeltaSelectorV1
import org.junit.Assert.assertEquals
import org.junit.Test

class DepthOfferTimingLedgerTest {
    @Test fun `offer clock includes queued and deferred time and requires exact publication selector`() {
        var now = 1_000L
        val ledger = DepthOfferTimingLedger { now }
        val id = ledger.start(51)
        now += 1_000_000_000L
        ledger.stage(id, DepthOfferTimingLedger.ADMITTING)
        now += 1_000_000_000L
        ledger.stage(id, DepthOfferTimingLedger.DEFERRED)
        val selector = CurrentDeltaSelectorV1(3, 4, 5)
        ledger.published(id, selector)
        ledger.refuseUnretained(id)
        ledger.complete(id, CurrentDeltaSelectorV1(3, 4, 6))
        assertEquals("publicationPending", entry(ledger)["status"])
        assertEquals(-1L, entry(ledger)["endToEndMicros"])
        now += 1_000_000_000L
        ledger.complete(id, selector)
        assertEquals("completed", entry(ledger)["status"])
        assertEquals(3_000_000L, entry(ledger)["endToEndMicros"])
        ledger.finishActive(DepthOfferTimingLedger.RESET)
        assertEquals("completed", entry(ledger)["status"])
    }

    @Test fun `replaced refused reset lifecycle and nonmaterial never fabricate ACK completion`() {
        for (state in listOf(DepthOfferTimingLedger.REPLACED, DepthOfferTimingLedger.REFUSED,
            DepthOfferTimingLedger.RESET, DepthOfferTimingLedger.LIFECYCLE, DepthOfferTimingLedger.NON_MATERIAL)) {
            val ledger = DepthOfferTimingLedger { 1_000 }
            val id = ledger.start(1)
            ledger.finish(id, state)
            ledger.published(id, CurrentDeltaSelectorV1(1, 1, 1))
            ledger.complete(id, CurrentDeltaSelectorV1(1, 1, 1))
            assertEquals(-1L, entry(ledger)["endToEndMicros"])
        }
    }

    @Test fun `overflow retains exactly sixty four scalar entries and stale callbacks cannot alter new offers`() {
        val ledger = DepthOfferTimingLedger { 1_000 }
        repeat(65) { ledger.start(it.toLong()) }
        ledger.finish(1, DepthOfferTimingLedger.REPLACED)
        val snapshot = ledger.snapshot()
        val entries = snapshot["entries"] as List<*>
        assertEquals(64, entries.size)
        assertEquals(65L, snapshot["offeredCount"])
        assertEquals(1L, snapshot["overwrittenCount"])
        assertEquals(2L, (entries.first() as Map<*, *>)["offerId"])
        assertEquals("queued", (entries.last() as Map<*, *>)["status"])
    }

    private fun entry(ledger: DepthOfferTimingLedger) =
        (ledger.snapshot()["entries"] as List<*>).single() as Map<*, *>
}
