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
        assertEquals(80L, first.physicallyAllocatedBytes(token.token))
        assertNull(first.reserve("capture:session:two", 11))

        val restarted = StorageBudgetCoordinatorV2(root, policy) { 100 }
        assertEquals(80, restarted.reservedBytes())
        restarted.commit(token, 60)
        assertEquals(60, restarted.committedBytes())
        restarted.reclaimVerified(60)
        assertEquals(0, restarted.committedBytes())
    }

    @Test fun `independent live coordinators refresh one process global ledger before every operation`() {
        val root = directory(); val policy = StorageBudgetPolicyV2(100, 0)
        val first = StorageBudgetCoordinatorV2(root, policy) { 100 }
        val second = StorageBudgetCoordinatorV2(root, policy) { 100 }
        val one = first.reserve("capture:session:one", 60)!!
        assertEquals(60L, second.reservedBytes())
        assertNull(second.reserve("capture:session:two", 41))
        assertTrue(second.release(one))
        assertEquals(0L, first.reservedBytes())
        assertEquals(100L, first.reserve("capture:session:three", 100)!!.bytes)
    }

    @Test fun `release is idempotent and cannot free a changed token`() {
        val coordinator = StorageBudgetCoordinatorV2(directory(), StorageBudgetPolicyV2(100, 0)) { 100 }
        val reservation = coordinator.reserve("capture:session:one", 20)!!
        assertTrue(coordinator.release(reservation))
        assertTrue(!coordinator.release(reservation))
    }

    @Test fun `safe filesystem syncs the actual parent after authority replacement`() {
        val root = directory(); val synced = mutableListOf<File>()
        val files = SafeFilesystemV2(root, DurableStoreFaultInjectorV2 { }, DirectorySyncV2 { synced += it.canonicalFile })
        val pointer = files.child("root-A.ptr")
        files.atomicReplace(pointer, "authority\n".toByteArray(), DurableStoreFaultPointV2.POINTER_SLOT_REPLACE)
        assertEquals("authority\n", files.readBytes(pointer).toString(Charsets.UTF_8))
        assertEquals(listOf(root.canonicalFile), synced)
    }

    private fun directory(): File = File.createTempFile("storage-budget-v2", "").also { it.delete(); assertTrue(it.mkdirs()); directories += it }
}
