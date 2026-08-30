package com.uhg0.ar_flutter_plugin_2.visibilitygrid

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class M3IntegratedCapacityCampaignTest {
    @Test
    fun `complete fixed profile preserves 16 MiB and exact 1 MiB phase limits`() {
        val profile = M3SurfaceOwnershipConfiguration()
        assertEquals(100_000, profile.surfaceCapacity)
        assertEquals(200_000, profile.lineageCapacity)
        assertEquals(M3CompactCanonicalStore.JOURNAL_RESERVE_BYTES, profile.changeJournalByteCapacity.toLong())
        assertEquals(M3CompactCanonicalStore.JOURNAL_RESERVE_BYTES, M3CanonicalActivationResources.MAX_CURRENT_BYTES)
        assertEquals(M3CompactCanonicalStore.JOURNAL_RESERVE_BYTES, M3CanonicalActivationResources.SHARED_PHASE_BYTES)

        // The accepted maximum-history store receipt (100k rows, 200k lineage,
        // 300k legal support/source joins and four resident pages) is combined
        // with the ACK/transaction scalar owner and the one shared journal/current phase.
        assertEquals(15_614_656L, M3CanonicalActivationResources.INTEGRATED_PEAK_BYTES)
        assertTrue(M3CanonicalActivationResources.INTEGRATED_PEAK_BYTES <= M3CompactCanonicalStore.C17_TOTAL_BYTES)
    }
}
