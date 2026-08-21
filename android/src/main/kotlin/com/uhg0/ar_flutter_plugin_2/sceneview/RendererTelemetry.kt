package com.uhg0.ar_flutter_plugin_2.sceneview

import java.util.concurrent.atomic.AtomicInteger

/** Why a renderer-frame page was submitted; used to separate lifecycle resets from data updates. */
internal enum class RendererUploadPageOrigin {
    RESOURCE_GENERATION_RESET,
    ORDINARY,
}

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
    private val resourceGenerationResetPageSubmissionCount = AtomicInteger()
    private val ordinaryPageSubmissionCount = AtomicInteger()
    private val resourceGenerationResetCallbackCount = AtomicInteger()
    private val ordinaryCallbackCount = AtomicInteger()
    private val resourceGenerationResetCompletionCount = AtomicInteger()
    private val ordinaryCompletionCount = AtomicInteger()
    @Volatile private var lastUploadPageReason = "none"
    @Volatile private var lastUploadCompletionReason = "none"
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

    fun recordUpload(bytes: Int, origin: RendererUploadPageOrigin) {
        require(bytes in 0..ORDINARY_UPLOAD_LIMIT_BYTES)
        val nextFrameBytes = currentFrameUploadBytes + bytes
        require(nextFrameBytes <= ORDINARY_UPLOAD_LIMIT_BYTES) {
            "renderer frame upload exceeds $ORDINARY_UPLOAD_LIMIT_BYTES bytes"
        }
        currentFrameUploadBytes = nextFrameBytes
        peakFrameUploadBytes = maxOf(peakFrameUploadBytes, currentFrameUploadBytes)
        uploadPageSubmissionCount.incrementAndGet()
        when (origin) {
            RendererUploadPageOrigin.RESOURCE_GENERATION_RESET ->
                resourceGenerationResetPageSubmissionCount.incrementAndGet()
            RendererUploadPageOrigin.ORDINARY -> ordinaryPageSubmissionCount.incrementAndGet()
        }
        lastUploadPageReason = origin.wireName
    }

    fun recordUpload(bytes: Int) = recordUpload(bytes, RendererUploadPageOrigin.ORDINARY)

    fun recordResourceResetScheduled() {
        resourceResetScheduledCount.incrementAndGet()
    }

    fun recordUploadCallback(origin: RendererUploadPageOrigin) {
        uploadCallbackCount++
        when (origin) {
            RendererUploadPageOrigin.RESOURCE_GENERATION_RESET ->
                resourceGenerationResetCallbackCount.incrementAndGet()
            RendererUploadPageOrigin.ORDINARY -> ordinaryCallbackCount.incrementAndGet()
        }
    }

    fun recordUploadCallback() = recordUploadCallback(RendererUploadPageOrigin.ORDINARY)

    /**
     * Records the native hand-off duration from submitting an upload to both
     * Filament consumption callbacks. This is not a GPU frame-time metric:
     * Filament intentionally does not expose driver timer-query results here.
     */
    fun recordUploadCompletion(elapsedNanos: Long, origin: RendererUploadPageOrigin) {
        require(elapsedNanos >= 0)
        completedUploadCount++
        totalUploadCompletionNanos += elapsedNanos
        peakUploadCompletionNanos = maxOf(peakUploadCompletionNanos, elapsedNanos)
        when (origin) {
            RendererUploadPageOrigin.RESOURCE_GENERATION_RESET ->
                resourceGenerationResetCompletionCount.incrementAndGet()
            RendererUploadPageOrigin.ORDINARY -> ordinaryCompletionCount.incrementAndGet()
        }
        lastUploadCompletionReason = origin.wireName
    }

    fun recordUploadCompletion(elapsedNanos: Long) =
        recordUploadCompletion(elapsedNanos, RendererUploadPageOrigin.ORDINARY)

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
        "resourceGenerationResetPageSubmissionCount" to resourceGenerationResetPageSubmissionCount.get(),
        "ordinaryPageSubmissionCount" to ordinaryPageSubmissionCount.get(),
        "uploadCallbackCount" to uploadCallbackCount,
        "completedUploadCount" to completedUploadCount,
        "resourceGenerationResetCallbackCount" to resourceGenerationResetCallbackCount.get(),
        "ordinaryCallbackCount" to ordinaryCallbackCount.get(),
        "resourceGenerationResetCompletionCount" to resourceGenerationResetCompletionCount.get(),
        "ordinaryCompletionCount" to ordinaryCompletionCount.get(),
        "lastUploadPageReason" to lastUploadPageReason,
        "lastUploadCompletionReason" to lastUploadCompletionReason,
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

private val RendererUploadPageOrigin.wireName: String
    get() = when (this) {
        RendererUploadPageOrigin.RESOURCE_GENERATION_RESET -> "resource-generation-reset"
        RendererUploadPageOrigin.ORDINARY -> "ordinary"
    }
