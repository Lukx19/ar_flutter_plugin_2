package com.uhg0.ar_flutter_plugin_2.capture

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptureAttemptOwnerTest {
    private data class Qualified(val id: String)

    @Test
    fun `two barrier synchronized callers admit exactly one owner`() {
        val owner = CaptureAttemptOwner<String>()
        val ready = CountDownLatch(2)
        val start = CountDownLatch(1)
        val done = CountDownLatch(2)
        val admitted = java.util.concurrent.ConcurrentLinkedQueue<String>()
        val pool = Executors.newFixedThreadPool(2)
        listOf("A", "B").forEach { candidate ->
            pool.execute {
                ready.countDown()
                start.await()
                if (owner.acquire(candidate)) admitted.add(candidate)
                done.countDown()
            }
        }

        assertTrue(ready.await(1, TimeUnit.SECONDS))
        start.countDown()
        assertTrue(done.await(1, TimeUnit.SECONDS))
        pool.shutdownNow()

        assertEquals(1, admitted.size)
        assertEquals(admitted.single(), owner.get())
    }

    @Test
    fun `exact production cancellation releases owner and permits later success`() {
        val owner = QualifiedCaptureAttemptOwnerV2<Qualified, String>(Qualified::id)
        val first = Qualified("first")
        assertTrue(owner.acquire(first))
        assertNull(owner.cancel("stale"))
        assertEquals(first, owner.get())
        assertEquals(first, owner.cancel("first"))
        assertNull(owner.get())

        val later = Qualified("later")
        assertTrue(owner.acquire(later))
        assertFalse(owner.acquire(Qualified("blocked")))
        assertEquals(later, owner.get())
    }
}
