package com.uhg0.ar_flutter_plugin_2.m0

import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Bounded, numeric telemetry for one packed M0a binding.
 *
 * The channel carries only packed request/response bytes. In particular,
 * ordinary surface bytes on the root isolate are a fixed zero observable;
 * this class does not expose a surface-array escape hatch.
 */
class M0aTransportInstrumentation {
    private val submittedRequests = AtomicLong()
    private val submittedRequestBytes = AtomicLong()
    private val acceptedRequests = AtomicLong()
    private val replayedRequests = AtomicLong()
    private val rejectedRequests = AtomicLong()
    private val malformedRequests = AtomicLong()
    private val timeoutCount = AtomicLong()
    private val workerExitCount = AtomicLong()
    private val bindingAbandonCount = AtomicLong()
    private val responseBytes = AtomicLong()
    private val queueDepth = AtomicInteger()
    private val peakQueueDepth = AtomicInteger()
    private val inFlight = AtomicInteger()
    private val workerLatencyNanos = AtomicLong()
    private val maxWorkerLatencyNanos = AtomicLong()
    private val workerStartedAtNanos = ThreadLocal<Long>()

    fun submitted(requestBytes: Int) {
        require(requestBytes >= 0) { "requestBytes must be non-negative" }
        submittedRequests.incrementAndGet()
        submittedRequestBytes.addAndGet(requestBytes.toLong())
    }

    fun queued() {
        val depth = queueDepth.incrementAndGet()
        peakQueueDepth.updateAndGet { previous -> maxOf(previous, depth) }
    }

    fun dequeued() {
        queueDepth.updateAndGet { previous -> maxOf(0, previous - 1) }
        inFlight.incrementAndGet()
        workerStartedAtNanos.set(System.nanoTime())
    }

    fun completed() {
        inFlight.updateAndGet { previous -> maxOf(0, previous - 1) }
        val started = workerStartedAtNanos.get()
        if (started != null) {
            val elapsed = (System.nanoTime() - started).coerceAtLeast(0)
            workerLatencyNanos.addAndGet(elapsed)
            maxWorkerLatencyNanos.updateAndGet { previous -> maxOf(previous, elapsed) }
            workerStartedAtNanos.remove()
        }
    }

    fun accepted(requestBytes: Int, responseBytes: Int) {
        require(requestBytes >= 0 && responseBytes >= 0) {
            "packet byte counts must be non-negative"
        }
        acceptedRequests.incrementAndGet()
        this.responseBytes.addAndGet(responseBytes.toLong())
    }

    fun replayed() {
        replayedRequests.incrementAndGet()
    }

    fun rejected() {
        rejectedRequests.incrementAndGet()
    }

    fun malformed() {
        malformedRequests.incrementAndGet()
    }

    fun timedOut() {
        timeoutCount.incrementAndGet()
        bindingAbandonCount.incrementAndGet()
    }

    fun workerLost() {
        workerExitCount.incrementAndGet()
        bindingAbandonCount.incrementAndGet()
    }

    fun snapshot(): Snapshot = Snapshot(
        submittedRequests = submittedRequests.get(),
        submittedRequestBytes = submittedRequestBytes.get(),
        acceptedRequests = acceptedRequests.get(),
        replayedRequests = replayedRequests.get(),
        rejectedRequests = rejectedRequests.get(),
        malformedRequests = malformedRequests.get(),
        timeoutCount = timeoutCount.get(),
        workerExitCount = workerExitCount.get(),
        bindingAbandonCount = bindingAbandonCount.get(),
        responseBytes = responseBytes.get(),
        queueDepth = queueDepth.get(),
        peakQueueDepth = peakQueueDepth.get(),
        inFlight = inFlight.get(),
        workerLatencyNanos = workerLatencyNanos.get(),
        maxWorkerLatencyNanos = maxWorkerLatencyNanos.get(),
        allocationBytesObserved = null,
        compressionBytesObserved = 0,
        decompressionBytesObserved = 0,
        decompressionRejects = 0,
        ordinaryRootSurfaceBytes = 0,
    )

    data class Snapshot(
        val submittedRequests: Long,
        val submittedRequestBytes: Long,
        val acceptedRequests: Long,
        val replayedRequests: Long,
        val rejectedRequests: Long,
        val malformedRequests: Long,
        val timeoutCount: Long,
        val workerExitCount: Long,
        val bindingAbandonCount: Long,
        val responseBytes: Long,
        val queueDepth: Int,
        val peakQueueDepth: Int,
        val inFlight: Int,
        val workerLatencyNanos: Long,
        val maxWorkerLatencyNanos: Long,
        val allocationBytesObserved: Long?,
        val compressionBytesObserved: Long,
        val decompressionBytesObserved: Long,
        val decompressionRejects: Long,
        val ordinaryRootSurfaceBytes: Long,
    )
}
