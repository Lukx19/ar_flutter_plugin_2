package com.uhg0.ar_flutter_plugin_2.sceneview

import java.util.concurrent.atomic.AtomicInteger

/**
 * Bounded native renderer accounting exposed by the Android platform-view
 * seam. This counts the buffers and upload calls owned by this renderer; it
 * deliberately does not present a driver estimate as GPU-memory evidence.
 */
internal class RendererTelemetry {
    private val allocationsByOwner = linkedMapOf<String, Int>()
    private var currentFrameUploadBytes = 0
    private var peakFrameUploadBytes = 0
    private var peakOwnedBufferBytes = 0
    private val resourceResetScheduledCount = AtomicInteger()
    private val uploadPageSubmissionCount = AtomicInteger()
    private var uploadCallbackCount = 0
    private var completedUploadCount = 0
    private var totalUploadCompletionNanos = 0L
    private var peakUploadCompletionNanos = 0L
    private var rendererUpdateCount = 0

    fun setOwnedBufferBytes(owner: String, bytes: Int) {
        require(owner.isNotBlank())
        require(bytes >= 0)
        val previous = allocationsByOwner.put(owner, bytes)
        if (ownedBufferBytes > RENDERER_ALLOCATION_LIMIT_BYTES) {
            if (previous == null) {
                allocationsByOwner.remove(owner)
            } else {
                allocationsByOwner[owner] = previous
            }
            throw IllegalStateException(
                "renderer-owned buffers exceed $RENDERER_ALLOCATION_LIMIT_BYTES bytes",
            )
        }
        peakOwnedBufferBytes = maxOf(peakOwnedBufferBytes, ownedBufferBytes)
    }

    fun removeOwner(owner: String) {
        allocationsByOwner.remove(owner)
    }

    fun beginRendererFrame() {
        currentFrameUploadBytes = 0
        rendererUpdateCount++
    }

    fun recordUpload(bytes: Int) {
        require(bytes in 0..ORDINARY_UPLOAD_LIMIT_BYTES)
        val nextFrameBytes = currentFrameUploadBytes + bytes
        require(nextFrameBytes <= ORDINARY_UPLOAD_LIMIT_BYTES) {
            "renderer frame upload exceeds $ORDINARY_UPLOAD_LIMIT_BYTES bytes"
        }
        currentFrameUploadBytes = nextFrameBytes
        peakFrameUploadBytes = maxOf(peakFrameUploadBytes, currentFrameUploadBytes)
        uploadPageSubmissionCount.incrementAndGet()
    }

    fun recordResourceResetScheduled() {
        resourceResetScheduledCount.incrementAndGet()
    }

    fun recordUploadCallback() {
        uploadCallbackCount++
    }

    /**
     * Records the native hand-off duration from submitting an upload to both
     * Filament consumption callbacks. This is not a GPU frame-time metric:
     * Filament intentionally does not expose driver timer-query results here.
     */
    fun recordUploadCompletion(elapsedNanos: Long) {
        require(elapsedNanos >= 0)
        completedUploadCount++
        totalUploadCompletionNanos += elapsedNanos
        peakUploadCompletionNanos = maxOf(peakUploadCompletionNanos, elapsedNanos)
    }

    private val ownedBufferBytes: Int
        get() = allocationsByOwner.values.sum()

    fun snapshot(): Map<String, Any> = mapOf(
        "rendererUpdateCount" to rendererUpdateCount,
        "ownedBufferBytes" to ownedBufferBytes,
        "peakOwnedBufferBytes" to peakOwnedBufferBytes,
        "currentUpdateUploadBytes" to currentFrameUploadBytes,
        "peakUpdateUploadBytes" to peakFrameUploadBytes,
        "resourceResetScheduledCount" to resourceResetScheduledCount.get(),
        "uploadPageSubmissionCount" to uploadPageSubmissionCount.get(),
        "uploadCallbackCount" to uploadCallbackCount,
        "completedUploadCount" to completedUploadCount,
        "meanUploadCompletionNanos" to if (completedUploadCount == 0) {
            0L
        } else {
            totalUploadCompletionNanos / completedUploadCount
        },
        "peakUploadCompletionNanos" to peakUploadCompletionNanos,
        // The ledger above is exact for renderer-owned buffers. Android's
        // public Filament API does not provide a portable driver allocation or
        // GPU timer-query counter, including on the supported emulator.
        "gpuTimingAvailable" to false,
        "gpuAllocationAvailable" to false,
        "gpuCounterStatus" to "unavailable: Filament driver counters are not exposed",
        "ordinaryUploadLimitBytes" to ORDINARY_UPLOAD_LIMIT_BYTES,
        "rendererAllocationLimitBytes" to RENDERER_ALLOCATION_LIMIT_BYTES,
    )

    internal companion object {
        const val ORDINARY_UPLOAD_LIMIT_BYTES = 64 * 1024
        const val RENDERER_ALLOCATION_LIMIT_BYTES = 8 * 1024 * 1024
    }
}
