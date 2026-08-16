package com.uhg0.ar_flutter_plugin_2.m0

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class M0StorageQuotaTest {
    @Test
    fun `quota policy rounds volume floor like the Dart reference`() {
        assertEquals(
            M0StorageQuotaPolicy(500L, 4L * 1024 * 1024 * 1024),
            M0StorageQuotaPolicy.forVolume(1_000L),
        )
        assertEquals(
            M0StorageQuotaPolicy(5_000L, 4L * 1024 * 1024 * 1024),
            M0StorageQuotaPolicy.forVolume(10_000L),
        )
    }

    @Test
    fun `reservation commit and release preserve the global quota and floor`() {
        val coordinator = M0StorageBudgetCoordinator(
            policy = M0StorageQuotaPolicy(globalQuotaBytes = 1_000, freeSpaceFloorBytes = 100),
            committedBytes = 100,
            freeBytes = 500,
        )
        val reservation = coordinator.tryReserve(200) ?: error("reservation should be granted")
        assertEquals(200L, coordinator.reservedBytes)
        assertNull(coordinator.tryReserve(201))
        coordinator.commit(reservation, actualBytes = 150)
        assertEquals(250L, coordinator.committedBytes)
        assertEquals(350L, coordinator.freeBytes)
        assertEquals(0L, coordinator.reservedBytes)

        val released = coordinator.tryReserve(100) ?: error("reservation should be granted")
        coordinator.release(released)
        assertEquals(0L, coordinator.reservedBytes)
    }

    @Test
    fun `exact floor is legal and one byte beyond it is rejected`() {
        val coordinator = M0StorageBudgetCoordinator(
            policy = M0StorageQuotaPolicy(globalQuotaBytes = 1_000, freeSpaceFloorBytes = 100),
            committedBytes = 0,
            freeBytes = 300,
        )
        assertTrue(coordinator.tryReserve(200) != null)
        assertFalse(coordinator.tryReserve(201) != null)
    }

    @Test
    fun `stale reservations and ordinal overflow are rejected`() {
        val coordinator = M0StorageBudgetCoordinator(
            policy = M0StorageQuotaPolicy(Long.MAX_VALUE, 0),
            committedBytes = Long.MAX_VALUE,
            freeBytes = Long.MAX_VALUE,
        )
        assertNull(coordinator.tryReserve(1))
        assertThrows(IllegalArgumentException::class.java) { coordinator.updateFreeBytes(-1) }
        assertThrows(IllegalStateException::class.java) {
            coordinator.release(M0StorageReservation(99, 1))
        }
    }
}
