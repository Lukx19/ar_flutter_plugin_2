package com.uhg0.ar_flutter_plugin_2.capture

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Test

class CapturePreviewWorkQueueTest {
    @Test fun `obsolete queued request never opens source while held decode cancels and completes once`() {
        val queue = CapturePreviewWorkQueue()
        val entered = CountDownLatch(1); val release = CountDownLatch(1); val finished = CountDownLatch(2)
        val replies = AtomicInteger(); val obsoleteReads = AtomicInteger()
        try {
            queue.submit("active", { cancelled -> entered.countDown(); release.await(2, TimeUnit.SECONDS); assertTrue(cancelled()); "obsolete path" },
                { assertNull(it.getOrThrow()); replies.incrementAndGet(); finished.countDown() })
            assertTrue(entered.await(1, TimeUnit.SECONDS))
            queue.submit("queued", { obsoleteReads.incrementAndGet(); "queued path" },
                { assertNull(it.getOrThrow()); replies.incrementAndGet(); finished.countDown() })
            queue.cancel("queued"); queue.cancel("active"); queue.cancel("active")
            release.countDown()
            assertTrue(finished.await(1, TimeUnit.SECONDS))
            assertEquals(0, obsoleteReads.get()); assertEquals(2, replies.get())
        } finally { release.countDown(); queue.close() }
    }

    @Test fun `queue overflow refuses excess work without unbounded jobs or worker concurrency`() {
        val queue = CapturePreviewWorkQueue()
        val entered = CountDownLatch(1); val release = CountDownLatch(1); val refused = CountDownLatch(1)
        try {
            queue.submit("active", { entered.countDown(); release.await(2, TimeUnit.SECONDS); null }, {})
            assertTrue(entered.await(1, TimeUnit.SECONDS))
            repeat(16) { index -> queue.submit("pending-$index", { null }, {}) }
            queue.submit("overflow", { error("must not execute") }, { assertTrue(it.isFailure); refused.countDown() })
            assertTrue(refused.await(1, TimeUnit.SECONDS))
        } finally { release.countDown(); queue.close() }
    }

    @Test fun `engine detach completes pending replies and running owner observes cancellation`() {
        val queue = CapturePreviewWorkQueue()
        val entered = CountDownLatch(1); val release = CountDownLatch(1); val finished = CountDownLatch(2)
        try {
            queue.submit("active", { cancelled ->
                entered.countDown()
                try { release.await(2, TimeUnit.SECONDS) } catch (_: InterruptedException) {}
                assertTrue(cancelled()); null
            }, { finished.countDown() })
            assertTrue(entered.await(1, TimeUnit.SECONDS))
            queue.submit("pending", { error("detached queued work must not execute") }, { finished.countDown() })
            queue.close()
            assertTrue(finished.await(1, TimeUnit.SECONDS))
            queue.submit("late", { error("closed worker must not execute") }, { assertTrue(it.isFailure) })
        } finally { release.countDown(); queue.close() }
    }
}
