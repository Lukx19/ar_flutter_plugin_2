package com.uhg0.ar_flutter_plugin_2.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class JpegDimensionLedgerTest {
    @Test
    fun `unobserved dimensions stay unavailable and every mismatched Image is counted`() {
        val ledger = JpegDimensionLedger()
        assertNull(ledger.receipt()["lastWidth"])
        ledger.record(4128, 2580, 4128, 2580)
        ledger.record(1920, 1080, 4128, 2580)
        ledger.record(4128, 2580, 4128, 2580)
        assertEquals(3L, ledger.receipt()["receivedCount"])
        assertEquals(1L, ledger.receipt()["mismatchedDimensions"])
        assertEquals(4128, ledger.receipt()["lastWidth"])
        assertEquals(2580, ledger.receipt()["lastHeight"])
    }
}
