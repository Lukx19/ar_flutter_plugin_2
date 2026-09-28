package com.uhg0.ar_flutter_plugin_2.visibilitygrid

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BoundedDepthObservationProcessorTest {
    @Test
    fun `busy depth processor rejects and releases the next image without blocking callback`() {
        val processing = CountDownLatch(1)
        val release = CountDownLatch(1)
        val published = AtomicInteger()
        val firstClosed = AtomicInteger()
        val nextClosed = AtomicInteger()
        val processor = BoundedDepthObservationProcessor<AutoCloseable, Int>(
            process = { frame ->
                processing.countDown()
                check(release.await(2, TimeUnit.SECONDS))
                7
            },
            publish = { result, callbackCopyNs ->
                assertEquals(7, result)
                assertEquals(800L, callbackCopyNs)
                published.incrementAndGet()
            },
        )
        try {
            val first = AutoCloseable { firstClosed.incrementAndGet() }
            assertTrue(processor.offer(first, callbackCopyNs = 800))
            assertTrue(processing.await(2, TimeUnit.SECONDS))
            val next = AutoCloseable { nextClosed.incrementAndGet() }
            assertFalse(processor.offer(next, callbackCopyNs = 800))
            assertEquals(1, nextClosed.get())
            assertEquals(0, firstClosed.get())
            release.countDown()
            assertTrue(processor.awaitIdle(2_000))
            assertEquals(1, firstClosed.get())
            assertEquals(1, published.get())
            val resumedClosed = AtomicInteger()
            assertTrue(processor.offer(AutoCloseable { resumedClosed.incrementAndGet() }, 800))
            assertTrue(processor.awaitIdle(2_000))
            assertEquals(1, resumedClosed.get())
            assertEquals(2, published.get())
        } finally {
            release.countDown()
            processor.close()
        }
    }
}
