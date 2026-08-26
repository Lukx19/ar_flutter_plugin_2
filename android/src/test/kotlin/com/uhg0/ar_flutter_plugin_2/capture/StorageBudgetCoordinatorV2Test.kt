package com.uhg0.ar_flutter_plugin_2.capture

import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StorageBudgetCoordinatorV2Test {
    private val directories = mutableListOf<File>()
    @After fun cleanUp() { directories.forEach { it.deleteRecursively() } }

    @Test fun `physically persisted reservations serialize global quota and survive a new coordinator`() {
        val root = directory(); val policy = StorageBudgetPolicyV2(100, 10)
        val first = StorageBudgetCoordinatorV2(root, policy) { 100 }
        val token = first.reserve("capture:session:one", 80)!!
        assertNull(first.reserve("capture:session:two", 11))

        val restarted = StorageBudgetCoordinatorV2(root, policy) { 100 }
        assertEquals(80, restarted.reservedBytes())
        restarted.commit(token, 60)
        assertEquals(60, restarted.committedBytes())
        restarted.reclaimVerified(60)
        assertEquals(0, restarted.committedBytes())
    }

    @Test fun `release is idempotent and cannot free a changed token`() {
        val coordinator = StorageBudgetCoordinatorV2(directory(), StorageBudgetPolicyV2(100, 0)) { 100 }
        val reservation = coordinator.reserve("capture:session:one", 20)!!
        assertTrue(coordinator.release(reservation))
        assertTrue(!coordinator.release(reservation))
    }

    private fun directory(): File = File.createTempFile("storage-budget-v2", "").also { it.delete(); assertTrue(it.mkdirs()); directories += it }
}
