package com.uhg0.ar_flutter_plugin_2.m0

import java.nio.ByteBuffer
import java.nio.ByteOrder
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
    /** Fixed M0a budgets; these are configuration observables, not pass claims. */
    val resourceLimits = ResourceLimits()

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
    private val allocationBytes = AtomicLong()
    private val maximumSingleAllocationBytes = AtomicLong()
    private val replayCacheBytes = AtomicLong()
    private val structuralStagingBytes = AtomicLong()
    private val peakRetainedBytes = AtomicLong()
    private val peakWorkingSetBytes = AtomicLong()
    private val replacementCount = AtomicLong()
    private val lastSummaryNanos = AtomicLong(Long.MIN_VALUE)
    private val workerStartedAtNanos = ThreadLocal<Long>()

    /** Records a real byte-buffer allocation owned by this transport. */
    fun allocated(bytes: Int) {
        require(bytes >= 0) { "allocated bytes must be non-negative" }
        allocationBytes.addAndGet(bytes.toLong())
        maximumSingleAllocationBytes.updateAndGet { previous -> maxOf(previous, bytes.toLong()) }
        peakWorkingSetBytes.updateAndGet { previous ->
            maxOf(previous, retainedBytes() + bytes)
        }
    }

    /** Replaces the exact duplicate-replay cache accounting. */
    fun retainedReplayCache(requestBytes: Int, responseBytes: Int) {
        require(requestBytes >= 0 && responseBytes >= 0)
        replayCacheBytes.set(requestBytes.toLong() + responseBytes.toLong())
        updateRetainedPeaks()
    }

    /** Replaces the structural transaction staging accounting. */
    fun retainedStructuralStaging(bytes: Int) {
        require(bytes >= 0)
        structuralStagingBytes.set(bytes.toLong())
        updateRetainedPeaks()
    }

    fun bindingReplaced() {
        replacementCount.incrementAndGet()
    }

    fun clearRetained() {
        replayCacheBytes.set(0)
        structuralStagingBytes.set(0)
    }

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
        resourceLimits = resourceLimits,
        allocationBytesObserved = allocationBytes.get(),
        maximumSingleAllocationBytes = maximumSingleAllocationBytes.get(),
        retainedAllocationBytes = retainedBytes(),
        peakRetainedAllocationBytes = peakRetainedBytes.get(),
        peakWorkingSetBytes = peakWorkingSetBytes.get(),
        replacementCount = replacementCount.get(),
        compressionBytesObserved = 0,
        decompressionBytesObserved = 0,
        decompressionRejects = 0,
        ordinaryRootSurfaceBytes = 0,
        ordinaryRootIsolateTimeNanos = 0,
    )

    /**
     * Fixed-width numeric summary safe for the low-rate root-isolate path.
     * It contains no request, response, diagnostic text, or surface bytes.
     */
    fun encodeBoundedSummary(): ByteArray {
        val value = snapshot()
        return ByteBuffer.allocate(SUMMARY_BYTES).order(ByteOrder.LITTLE_ENDIAN).apply {
            putInt(0x324d4756) // VGM2
            putShort(1)
            putShort(SUMMARY_BYTES.toShort())
            listOf(
                value.submittedRequests,
                value.acceptedRequests,
                value.replayedRequests,
                value.rejectedRequests,
                value.malformedRequests,
                value.timeoutCount,
                value.workerExitCount,
                value.bindingAbandonCount,
                value.replacementCount,
                value.allocationBytesObserved,
                value.maximumSingleAllocationBytes,
                value.retainedAllocationBytes,
                value.peakRetainedAllocationBytes,
                value.peakWorkingSetBytes,
                value.workerLatencyNanos,
                value.maxWorkerLatencyNanos,
                value.ordinaryRootSurfaceBytes,
                value.ordinaryRootIsolateTimeNanos,
                value.compressionBytesObserved,
                value.decompressionBytesObserved,
            ).forEach(::putLong)
        }.array()
    }

    /** Returns at most one bounded summary in each canonical 200 ms window. */
    fun tryEncodeBoundedSummary(nowNanos: Long = System.nanoTime()): ByteArray? {
        while (true) {
            val previous = lastSummaryNanos.get()
            if (previous != Long.MIN_VALUE && nowNanos - previous < SUMMARY_INTERVAL_NANOS) {
                return null
            }
            if (lastSummaryNanos.compareAndSet(previous, nowNanos)) {
                return encodeBoundedSummary()
            }
        }
    }

    private fun retainedBytes(): Long = replayCacheBytes.get() + structuralStagingBytes.get()

    private fun updateRetainedPeaks() {
        val retained = retainedBytes()
        peakRetainedBytes.updateAndGet { previous -> maxOf(previous, retained) }
        peakWorkingSetBytes.updateAndGet { previous -> maxOf(previous, retained) }
    }

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
        val resourceLimits: ResourceLimits,
        val allocationBytesObserved: Long,
        val maximumSingleAllocationBytes: Long,
        val retainedAllocationBytes: Long,
        val peakRetainedAllocationBytes: Long,
        val peakWorkingSetBytes: Long,
        val replacementCount: Long,
        val compressionBytesObserved: Long,
        val decompressionBytesObserved: Long,
        val decompressionRejects: Long,
        val ordinaryRootSurfaceBytes: Long,
        val ordinaryRootIsolateTimeNanos: Long,
    )

    data class ResourceLimits(
        val requestCeilingBytes: Int = 16 * 1024,
        val ordinaryResponseCeilingBytes: Int = 16 * 1024,
        val catchUpResponseCeilingBytes: Int = 64 * 1024,
        val diagnosticSummaryBytes: Int = 1024,
        val diagnosticSummaryRateHz: Int = 5,
        val structuralTransactionFrames: Int = 18,
        val structuralChunkBytes: Int = 1024,
        val scratchBytesPerSide: Int = 256 * 1024,
        val compressionInputBytes: Int = 0,
        val compressionOutputBytes: Int = 0,
        val decompressionInputBytes: Int = 0,
        val decompressionOutputBytes: Int = 0,
        val ordinaryRootSurfaceBytes: Long = 0,
    )

    private companion object {
        const val SUMMARY_BYTES = 168
        const val SUMMARY_INTERVAL_NANOS = 200_000_000L
    }
}
