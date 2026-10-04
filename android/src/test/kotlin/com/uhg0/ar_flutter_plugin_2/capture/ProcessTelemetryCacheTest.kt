package com.uhg0.ar_flutter_plugin_2.capture

import java.util.ArrayDeque
import java.util.concurrent.AbstractExecutorService
import java.util.concurrent.CountDownLatch
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProcessTelemetryCacheTest {
    private class Worker : AbstractExecutorService() {
        val tasks = ArrayDeque<Runnable>()
        private var stopped = false
        override fun execute(task: Runnable) {
            if (stopped) throw RejectedExecutionException()
            tasks.add(task)
        }
        fun runNext() = tasks.removeFirst().run()
        override fun shutdown() { stopped = true }
        override fun shutdownNow(): MutableList<Runnable> {
            stopped = true
            return tasks.toMutableList().also { tasks.clear() }
        }
        override fun isShutdown() = stopped
        override fun isTerminated() = stopped && tasks.isEmpty()
        override fun awaitTermination(timeout: Long, unit: TimeUnit) = isTerminated
    }

    @Test fun `cold reads never collect inline and coalesce one thousand requests`() {
        val worker = Worker()
        var collections = 0
        val cache = ProcessTelemetryCache({ 100L }, {
            collections++
            ProcessTelemetryValues(42L, 7, 9)
        }, worker)
        repeat(1_000) {
            val reply = cache.snapshot()
            assertNull(reply.values)
            assertNull(reply.ageMs)
            assertTrue(reply.refreshPending)
        }
        assertEquals(0, collections)
        assertEquals(1, worker.tasks.size)
        worker.runNext()
        assertEquals(ProcessTelemetryValues(42L, 7, 9), cache.snapshot().values)
        assertEquals(1, collections)
        cache.close()
    }

    @Test fun `held snapshots stay unchanged across refresh and do not refresh before five seconds`() {
        val worker = Worker()
        var now = 100L
        var measured = ProcessTelemetryValues(10L, 2, 3)
        val cache = ProcessTelemetryCache({ now }, { measured }, worker)
        cache.snapshot()
        worker.runNext()
        val held = cache.snapshot()
        now = 5_099L
        assertFalse(cache.snapshot().refreshPending)
        now = 5_100L
        assertTrue(cache.snapshot().refreshPending)
        measured = ProcessTelemetryValues(20L, 4, 6)
        worker.runNext()
        assertEquals(ProcessTelemetryValues(10L, 2, 3), held.values)
        assertEquals(0L, held.ageMs)
        assertEquals(measured, cache.snapshot().values)
        cache.close()
    }

    @Test fun `failed refresh preserves bounded fresh sample then expires to null and retries at bounded cadence`() {
        val worker = Worker()
        var now = 0L
        var fail = false
        val cache = ProcessTelemetryCache({ now }, {
            if (fail) error("unavailable")
            ProcessTelemetryValues(100L, 10, 20)
        }, worker)
        cache.snapshot(); worker.runNext()
        fail = true
        now = 5_000L
        cache.snapshot(); worker.runNext()
        val failed = cache.snapshot()
        assertTrue(failed.refreshFailed)
        assertNotNull(failed.values)
        assertFalse(failed.refreshPending)
        now = 10_000L
        assertNotNull(cache.snapshot().values)
        worker.runNext()
        now = 10_001L
        val stale = cache.snapshot()
        assertNull(stale.values)
        assertEquals(10_001L, stale.ageMs)
        assertEquals(0L, stale.sampledAtElapsedRealtimeMs)
        assertEquals(0, worker.tasks.size)
        now = 15_000L
        fail = false
        cache.snapshot(); worker.runNext()
        assertFalse(cache.snapshot().refreshFailed)
        assertNotNull(cache.snapshot().values)
        cache.close()
    }

    @Test fun `closing queued work cancels it and rejects future refresh without callbacks`() {
        val worker = Worker()
        var collections = 0
        val cache = ProcessTelemetryCache({ 0L }, {
            collections++
            ProcessTelemetryValues(1L, 1, 1)
        }, worker)
        cache.snapshot()
        cache.close(); cache.close()
        assertTrue(worker.isShutdown)
        assertTrue(worker.tasks.isEmpty())
        val reply = cache.snapshot()
        assertTrue(reply.closed)
        assertFalse(reply.refreshPending)
        assertNull(reply.values)
        assertEquals(0, collections)
    }

    @Test fun `real worker stays off caller and cannot publish a late result after close`() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val finished = CountDownLatch(1)
        val workerThread = AtomicReference<Thread>()
        val collections = AtomicInteger()
        val cache = ProcessTelemetryCache({ 0L }, {
            workerThread.set(Thread.currentThread())
            collections.incrementAndGet()
            entered.countDown()
            try {
                // Simulate a platform call that does not honor interruption.
                while (release.count != 0L) {
                    try { release.await(100, TimeUnit.MILLISECONDS) } catch (_: InterruptedException) { }
                }
                ProcessTelemetryValues(999L, 999, 999)
            } finally { finished.countDown() }
        })
        try {
            assertNull(cache.snapshot().values)
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            assertNotSame(Thread.currentThread(), workerThread.get())
            repeat(100) { assertTrue(cache.snapshot().refreshPending) }
            assertEquals(1, collections.get())
            cache.close()
            release.countDown()
            assertTrue(finished.await(2, TimeUnit.SECONDS))
            workerThread.get().join(2_000)
            assertFalse(workerThread.get().isAlive)
            assertNull(cache.snapshot().values)
            assertTrue(cache.snapshot().closed)
        } finally { release.countDown(); cache.close() }
    }

    @Test fun `rejected executor and collection failure never leave refresh pending`() {
        val worker = Worker()
        worker.shutdown()
        val cache = ProcessTelemetryCache({ 0L }, { error("must not execute") }, worker)
        val reply = cache.snapshot()
        assertFalse(reply.refreshPending)
        assertTrue(reply.refreshFailed)
        assertNull(reply.values)
        cache.close()
    }

    @Test fun `clock reversal cannot make old sample appear fresh`() {
        val worker = Worker()
        var now = 10_000L
        val cache = ProcessTelemetryCache({ now }, { ProcessTelemetryValues(1L, 2, 3) }, worker)
        cache.snapshot(); worker.runNext()
        now = 0L
        val reply = cache.snapshot()
        assertNull(reply.values)
        assertNull(reply.ageMs)
        assertTrue(reply.refreshPending)
        worker.runNext()
        assertNotNull(cache.snapshot().values)
        cache.close()
    }

    @Test fun `wire snapshot preserves unknown values and declares bounded capture owner capacity`() {
        val worker = Worker()
        val cache = ProcessTelemetryCache({ 0L }, { ProcessTelemetryValues(1024L, null, 17) }, worker)
        val cold = cache.snapshot().toMap()
        assertNull(cold["processPssBytes"])
        assertNull(cold["threadCount"])
        worker.runNext()
        val warm = cache.snapshot().toMap()
        assertEquals(1024L, warm["processPssBytes"])
        assertNull(warm["openFileDescriptors"])
        assertEquals(17, warm["threadCount"])
        assertEquals(1, warm["processTelemetryWorkerCapacity"])
        assertEquals(1, warm["processTelemetryQueuedRefreshCapacity"])
        assertEquals(1, warm["processTelemetryCachedSampleCapacity"])
        assertEquals(5_000L, warm["processTelemetryRefreshIntervalMs"])
        assertEquals(10_000L, warm["processTelemetryMaxAgeMs"])
        assertNull(cold["processPssBytes"])
        cache.close()
    }
}
