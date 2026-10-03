package com.uhg0.ar_flutter_plugin_2.performance

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AllocationWorkReceiptAndroidTest {
    private fun receipt(units: Long = 2, allocated: Long = 100) = AllocationWorkReceipt(
        "depth", 1_000_000_000, allocated, 40, 1, 2, units, 800, 12, 256, 2, 0,
    )
    private val identity = AllocationWorkloadIdentity("layered", "fixture-sha", "debug-isolated", "tablet", "arcore", 7, 2, 2, 2, 0, 0, 800, 12, 4)

    @Test fun normalizesCompletedWorkAndNeverInventsZeroUnitRate() {
        assertEquals(50.0, receipt().allocatedBytesPerUnit!!, 0.0)
        assertEquals(6000.0, receipt().allocatedBytesPerMinute!!, 0.0)
        assertNull(receipt(units = 0).allocatedBytesPerUnit)
        assertFalse(receipt(allocated = -1).valid)
        assertNull(receipt(allocated = -1).allocatedBytesPerMinute)
    }

    @Test fun counterResetIsInvalidAndUnequalDiscardedWorkCannotPass() {
        val reset = AllocationWorkReceipt.between("depth", AllocationCounters(1, 10, 20, 2, 5),
            AllocationCounters(2, 9, 30, 3, 6), 1, 800, 12, 256, 2, 0)
        assertFalse(reset.valid)
        assertFalse(identity.copy(replaced = 1).valid)
        assertFalse(identity.copy(refused = 1).valid)
        assertEquals(0.5, AllocationWorkComparison.requireComparable(identity, identity, receipt(), receipt(allocated = 50)), 0.0)
        for (changed in listOf(identity.copy(replaced = 1), identity.copy(fixtureSourceRevision = "other"),
            identity.copy(instrumentationProfile = "profile"), identity.copy(selectedSamples = 11))) {
            try {
                AllocationWorkComparison.requireComparable(identity, changed, receipt(), receipt(allocated = 50))
                fail("Incomparable work accepted")
            } catch (_: IllegalArgumentException) { }
        }
    }

    @Test fun absentOrFailingObserverCannotAffectAcceptedWork() {
        var sampled = 0
        publishAllocationReceipt(null) { sampled++; receipt() }
        assertEquals(0, sampled)
        publishAllocationReceipt({ throw IllegalStateException("diagnostic") }) { sampled++; receipt() }
        assertEquals(1, sampled)
        publishAllocationReceipt({ throw AssertionError("diagnostic assertion") }) { sampled++; receipt() }
        assertEquals(2, sampled)
    }
}
