package com.uhg0.ar_flutter_plugin_2.capture

import java.io.Closeable
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

internal enum class FinalizationSubmissionStatus {
    ACCEPTED,
    BACKPRESSURE,
    CLOSED,
}

internal data class FinalizationWorkerSnapshot(
    val workerCount: Int,
    val activeJobs: Int,
    val queuedJobs: Int,
    val queueCapacity: Int,
    val completedJobs: Long,
)

/**
 * Bounded worker pool shared by expensive encode and persistence work.
 *
 * Submission never blocks the Camera2 or Flutter method-channel thread. Once
 * all workers and queue slots are occupied, callers receive explicit
 * backpressure and must defer a new shutter instead of retaining another
 * multi-megapixel frame in memory.
 */
internal class CaptureFinalizationWorkerPool(
    workerCount: Int,
    queueCapacity: Int,
    threadFactory: ThreadFactory = namedThreadFactory(),
) : Closeable {
    init {
        require(workerCount > 0) { "workerCount must be positive" }
        require(queueCapacity > 0) { "queueCapacity must be positive" }
    }

    private val closed = AtomicBoolean(false)
    private val executor =
        ThreadPoolExecutor(
            workerCount,
            workerCount,
            0L,
            TimeUnit.MILLISECONDS,
            ArrayBlockingQueue(queueCapacity),
            threadFactory,
            ThreadPoolExecutor.AbortPolicy(),
        )

    fun submit(job: () -> Unit): FinalizationSubmissionStatus {
        if (closed.get()) return FinalizationSubmissionStatus.CLOSED
        return try {
            executor.execute(job)
            FinalizationSubmissionStatus.ACCEPTED
        } catch (_: RejectedExecutionException) {
            if (closed.get()) {
                FinalizationSubmissionStatus.CLOSED
            } else {
                FinalizationSubmissionStatus.BACKPRESSURE
            }
        }
    }

    fun snapshot(): FinalizationWorkerSnapshot =
        FinalizationWorkerSnapshot(
            workerCount = executor.corePoolSize,
            activeJobs = executor.activeCount,
            queuedJobs = executor.queue.size,
            queueCapacity = executor.queue.remainingCapacity() + executor.queue.size,
            completedJobs = executor.completedTaskCount,
        )

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            executor.shutdownNow()
        }
    }

    companion object {
        /**
         * Derive a conservative bound from CPU and transient bytes per job.
         * The memory budget includes copied YUV, ARGB bitmap and encoded bytes.
         */
        fun recommendedWorkerCount(
            availableProcessors: Int,
            transientBytesPerJob: Long,
            memoryBudgetBytes: Long,
            requestedWorkers: Int? = null,
        ): Int {
            require(availableProcessors > 0)
            require(transientBytesPerJob > 0)
            require(memoryBudgetBytes > 0)
            val cpuBound = (availableProcessors - 1).coerceIn(1, 4)
            val memoryBound = (memoryBudgetBytes / transientBytesPerJob).toInt().coerceAtLeast(1)
            return minOf(requestedWorkers ?: cpuBound, cpuBound, memoryBound).coerceAtLeast(1)
        }

        private fun namedThreadFactory(): ThreadFactory {
            val sequence = AtomicInteger(0)
            return ThreadFactory { task ->
                Thread(task, "Capture3dFinalizer-${sequence.incrementAndGet()}")
            }
        }
    }
}
