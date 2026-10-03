package com.uhg0.ar_flutter_plugin_2.capture

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptureFinalizationWorkerPoolTest {
    @Test
    fun `runs up to N jobs concurrently and queues within the bound`() {
        val started = CountDownLatch(2)
        val release = CountDownLatch(1)
        val pool = CaptureFinalizationWorkerPool(workerCount = 2, queueCapacity = 1)
        try {
            repeat(2) {
                assertEquals(
                    FinalizationSubmissionStatus.ACCEPTED,
                    pool.submit { started.countDown(); release.await() },
                )
            }
            assertTrue(started.await(1, TimeUnit.SECONDS))
            assertEquals(FinalizationSubmissionStatus.ACCEPTED, pool.submit { })
            assertEquals(FinalizationSubmissionStatus.BACKPRESSURE, pool.submit { })
            assertEquals(2, pool.snapshot().activeJobs)
            assertEquals(1, pool.snapshot().queuedJobs)
        } finally {
            release.countDown()
            pool.close()
        }
    }

    @Test
    fun `close rejects later jobs without blocking`() {
        val pool = CaptureFinalizationWorkerPool(workerCount = 1, queueCapacity = 1)
        var cancellations = 0
        pool.close()
        assertEquals(
            FinalizationSubmissionStatus.CLOSED,
            pool.submit(
                onCancelBeforeRun = { cancellations++ },
                job = {},
            ),
        )
        assertEquals(1, cancellations)
    }

    @Test
    fun `shutdown drains every queued cancellation before reporting its first failure`() {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        var firstCancelled = false
        var secondCancelled = false
        val pool = CaptureFinalizationWorkerPool(workerCount = 1, queueCapacity = 2)
        try {
            assertEquals(
                FinalizationSubmissionStatus.ACCEPTED,
                pool.submit {
                    started.countDown()
                    release.await()
                },
            )
            assertTrue(started.await(1, TimeUnit.SECONDS))
            assertEquals(
                FinalizationSubmissionStatus.ACCEPTED,
                pool.submit(
                    onCancelBeforeRun = {
                        firstCancelled = true
                        error("first cancellation failure")
                    },
                    job = {},
                ),
            )
            assertEquals(
                FinalizationSubmissionStatus.ACCEPTED,
                pool.submit(
                    onCancelBeforeRun = { secondCancelled = true },
                    job = {},
                ),
            )

            assertThrows(IllegalStateException::class.java) { pool.close() }
            assertTrue(firstCancelled)
            assertTrue(secondCancelled)
        } finally {
            release.countDown()
            pool.close()
        }
    }

    @Test
    fun `shutdown cancels queued ownership while running ownership drains`() {
        val bytes = CaptureByteArrayPool(maximumSlots = 2, maximumBytes = 64)
        val activeLease = requireNotNull(bytes.acquire(32))
        val queuedLease = requireNotNull(bytes.acquire(32))
        val activeStarted = CountDownLatch(1)
        val activeFinished = CountDownLatch(1)
        val releaseActive = CountDownLatch(1)
        val queuedRan = AtomicBoolean(false)
        val pool = CaptureFinalizationWorkerPool(workerCount = 1, queueCapacity = 1)
        try {
            assertEquals(
                FinalizationSubmissionStatus.ACCEPTED,
                pool.submit(
                    onCancelBeforeRun = activeLease::close,
                    job = {
                        activeStarted.countDown()
                        try {
                            try {
                                releaseActive.await()
                            } catch (_: InterruptedException) {
                                // shutdownNow interrupts running work; keep
                                // the lease held until the test releases it.
                                Thread.interrupted()
                                releaseActive.await()
                            }
                        } finally {
                            activeLease.close()
                            activeFinished.countDown()
                        }
                    },
                ),
            )
            assertTrue(activeStarted.await(1, TimeUnit.SECONDS))
            assertEquals(
                FinalizationSubmissionStatus.ACCEPTED,
                pool.submit(
                    onCancelBeforeRun = queuedLease::close,
                    job = {
                        queuedRan.set(true)
                        queuedLease.close()
                    },
                ),
            )

            pool.close()

            assertFalse(queuedRan.get())
            assertEquals(1, bytes.inUseCount())
            assertEquals(32L, bytes.receipt().inUseCapacityBytes)
            // The queued lease returned its array to the still-open byte
            // pool. It is no longer owned by a finalizer job, but remains
            // retained capacity until the byte pool reaches terminal close.
            assertEquals(64L, bytes.ownedCapacityBytes())
            assertEquals(32L, bytes.retainedCapacityBytes())
            releaseActive.countDown()
            assertTrue(activeFinished.await(1, TimeUnit.SECONDS))
            assertEquals(0, bytes.inUseCount())
            assertEquals(64L, bytes.ownedCapacityBytes())
            assertEquals(64L, bytes.retainedCapacityBytes())
            bytes.close()
            assertEquals(0L, bytes.ownedCapacityBytes())
        } finally {
            releaseActive.countDown()
            pool.close()
            activeLease.close()
            queuedLease.close()
            bytes.close()
        }
    }

    @Test
    fun `rejected submission releases its ownership exactly once`() {
        val bytes = CaptureByteArrayPool(maximumSlots = 3, maximumBytes = 96)
        val activeLease = requireNotNull(bytes.acquire(32))
        val queuedLease = requireNotNull(bytes.acquire(32))
        val rejectedLease = requireNotNull(bytes.acquire(32))
        val activeStarted = CountDownLatch(1)
        val activeFinished = CountDownLatch(1)
        val releaseActive = CountDownLatch(1)
        val cancellations = AtomicInteger(0)
        val pool = CaptureFinalizationWorkerPool(workerCount = 1, queueCapacity = 1)
        try {
            assertEquals(
                FinalizationSubmissionStatus.ACCEPTED,
                pool.submit(
                    job = {
                        activeStarted.countDown()
                        try {
                            try {
                                releaseActive.await()
                            } catch (_: InterruptedException) {
                                Thread.interrupted()
                                releaseActive.await()
                            }
                        } finally {
                            activeLease.close()
                            activeFinished.countDown()
                        }
                    },
                    onCancelBeforeRun = activeLease::close,
                ),
            )
            assertTrue(activeStarted.await(1, TimeUnit.SECONDS))
            assertEquals(
                FinalizationSubmissionStatus.ACCEPTED,
                pool.submit(onCancelBeforeRun = queuedLease::close, job = {}),
            )
            assertEquals(
                FinalizationSubmissionStatus.BACKPRESSURE,
                pool.submit(
                    onCancelBeforeRun = {
                        cancellations.incrementAndGet()
                        rejectedLease.close()
                    },
                    job = {},
                ),
            )
            assertEquals(1, cancellations.get())
            assertEquals(2, bytes.inUseCount())
        } finally {
            releaseActive.countDown()
            activeFinished.await(1, TimeUnit.SECONDS)
            pool.close()
            activeLease.close()
            queuedLease.close()
            rejectedLease.close()
            bytes.close()
        }
    }

    @Test
    fun `recommended workers are bounded by cpu memory and request`() {
        assertEquals(
            2,
            CaptureFinalizationWorkerPool.recommendedWorkerCount(
                availableProcessors = 8,
                transientBytesPerJob = 80,
                memoryBudgetBytes = 160,
            ),
        )
        assertEquals(
            1,
            CaptureFinalizationWorkerPool.recommendedWorkerCount(
                availableProcessors = 8,
                transientBytesPerJob = 80,
                memoryBudgetBytes = 400,
                requestedWorkers = 1,
            ),
        )
    }
}
