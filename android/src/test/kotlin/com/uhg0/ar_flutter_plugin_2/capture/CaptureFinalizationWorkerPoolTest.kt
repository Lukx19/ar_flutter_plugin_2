package com.uhg0.ar_flutter_plugin_2.capture

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
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
        pool.close()
        assertEquals(FinalizationSubmissionStatus.CLOSED, pool.submit { })
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
