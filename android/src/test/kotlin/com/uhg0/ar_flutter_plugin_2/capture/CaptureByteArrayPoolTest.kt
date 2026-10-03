package com.uhg0.ar_flutter_plugin_2.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptureByteArrayPoolTest {
    @Test
    fun `reuses retained capacity without exceeding the pool bound`() {
        val pool = CaptureByteArrayPool(maximumSlots = 2, maximumBytes = 96)
        val first = requireNotNull(pool.acquire(32))
        val firstBuffer = first.buffer
        first.close()

        val reused = requireNotNull(pool.acquire(16))
        assertSame(firstBuffer, reused.buffer)
        reused.close()

        val larger = requireNotNull(pool.acquire(64))
        assertTrue(larger.buffer.size >= 64)
        assertFalse(firstBuffer === larger.buffer)
        larger.close()
        assertEquals(2, pool.retainedCount())
    }

    @Test
    fun `a smaller request never truncates an existing scratch array`() {
        val pool = CaptureByteArrayPool(maximumSlots = 2, maximumBytes = 192)
        val first = requireNotNull(pool.acquire(128))
        val firstBuffer = first.buffer
        first.close()

        val reused = requireNotNull(pool.acquire(64))
        assertSame(firstBuffer, reused.buffer)
        assertEquals(128, reused.buffer.size)
        reused.close()
    }

    @Test
    fun `held leases refuse immediately at slot and byte bounds`() {
        val pool = CaptureByteArrayPool(maximumSlots = 2, maximumBytes = 64)
        val first = requireNotNull(pool.acquire(32))
        val second = requireNotNull(pool.acquire(32))

        assertNull(pool.acquire(1))
        assertEquals(2, pool.inUseCount())
        assertEquals(64L, pool.ownedCapacityBytes())

        val firstBuffer = first.buffer
        first.close()
        val replacement = requireNotNull(pool.acquire(16))
        assertSame(firstBuffer, replacement.buffer)
        second.close()
        replacement.close()
        assertEquals(0, pool.inUseCount())
    }

    @Test
    fun `two padded YUV frames fit the profile pixel bound`() {
        val width = 640
        val height = 480
        val pixels = width.toLong() * height.toLong()
        val pool = CaptureByteArrayPool(maximumSlots = 6, maximumBytes = pixels * 4L)
        val paddedFramePlanes = listOf(
            (width + 64) * height,
            (width / 2 + 64) * (height / 2),
            (width / 2 + 64) * (height / 2),
        )

        val leases = (0 until 2).flatMap { paddedFramePlanes.map { size ->
            requireNotNull(pool.acquire(size))
        } }

        assertEquals(6, pool.inUseCount())
        assertTrue(pool.receipt().ownedCapacityBytes <= pixels * 4L)
        leases.forEach { it.close() }
        assertEquals(6, pool.retainedCount())
    }

    @Test
    fun `foreign and duplicate token release cannot create ownership`() {
        val owner = CaptureByteArrayPool(maximumSlots = 2, maximumBytes = 64)
        val foreignOwner = CaptureByteArrayPool(maximumSlots = 2, maximumBytes = 64)
        val lease = requireNotNull(owner.acquire(32))

        assertFalse(foreignOwner.release(lease))
        assertEquals(1, owner.inUseCount())
        assertTrue(owner.release(lease))
        assertFalse(owner.release(lease))
        lease.close()
        assertEquals(1, owner.retainedCount())
    }

    @Test
    fun `receipt records retained growth peak and refusal without hidden bytes`() {
        val pool = CaptureByteArrayPool(maximumSlots = 3, maximumBytes = 96)
        val first = requireNotNull(pool.acquire(32))
        val second = requireNotNull(pool.acquire(64))
        assertNull(pool.acquire(1))
        second.close()
        first.close()

        val receipt = pool.receipt()
        assertEquals(2, receipt.retainedSlots)
        assertEquals(0, receipt.inUseSlots)
        assertEquals(96L, receipt.retainedCapacityBytes)
        assertEquals(96L, receipt.ownedCapacityBytes)
        assertEquals(96L, receipt.peakOwnedCapacityBytes)
        assertEquals(2, receipt.peakInUseSlots)
        assertEquals(2L, receipt.growthEvents)
        assertEquals(1L, receipt.refusalCount)
    }

    @Test
    fun `terminal close drops retained arrays but keeps held ownership until release`() {
        val pool = CaptureByteArrayPool(maximumSlots = 2, maximumBytes = 64)
        val lease = requireNotNull(pool.acquire(32))
        val heldBuffer = lease.buffer
        pool.close()

        assertSame(heldBuffer, lease.buffer)
        val heldReceipt = pool.receipt()
        assertTrue(heldReceipt.closed)
        assertEquals(0, heldReceipt.retainedSlots)
        assertEquals(1, heldReceipt.inUseSlots)
        assertEquals(32L, heldReceipt.inUseCapacityBytes)
        assertEquals(32L, heldReceipt.ownedCapacityBytes)
        assertNull(pool.acquire(32))

        lease.close()

        val receipt = pool.receipt()
        assertTrue(receipt.closed)
        assertEquals(0, receipt.retainedSlots)
        assertEquals(0, receipt.inUseSlots)
        assertEquals(0L, receipt.ownedCapacityBytes)
        assertThrows(IllegalStateException::class.java) { lease.buffer }
    }
}
