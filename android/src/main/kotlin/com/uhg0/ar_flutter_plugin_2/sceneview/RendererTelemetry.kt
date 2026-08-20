package com.uhg0.ar_flutter_plugin_2.sceneview

/**
 * Bounded native renderer accounting exposed by the Android platform-view
 * seam. This counts the buffers and upload calls owned by this renderer; it
 * deliberately does not present a driver estimate as GPU-memory evidence.
 */
internal class RendererTelemetry {
    private val allocationsByOwner = linkedMapOf<String, Int>()
    private var currentUpdateUploadBytes = 0
    private var peakUpdateUploadBytes = 0
    private var peakOwnedBufferBytes = 0
    private var uploadCallbackCount = 0
    private var completedUploadCount = 0
    private var totalUploadCompletionNanos = 0L
    private var peakUploadCompletionNanos = 0L
    private var rendererUpdateCount = 0

    fun setOwnedBufferBytes(owner: String, bytes: Int) {
        require(owner.isNotBlank())
        require(bytes >= 0)
        allocationsByOwner[owner] = bytes
        peakOwnedBufferBytes = maxOf(peakOwnedBufferBytes, ownedBufferBytes)
    }

    fun removeOwner(owner: String) {
        allocationsByOwner.remove(owner)
    }

    fun beginRendererUpdate() {
        currentUpdateUploadBytes = 0
        rendererUpdateCount++
    }

    fun recordUpload(bytes: Int) {
        require(bytes in 0..ORDINARY_UPLOAD_LIMIT_BYTES)
        // A paged reset may span several callbacks. Each callback submits one
        // independently renderable range, so the ledger reports its actual
        // per-frame payload rather than incorrectly summing a resync batch.
        currentUpdateUploadBytes = bytes
        peakUpdateUploadBytes = maxOf(peakUpdateUploadBytes, currentUpdateUploadBytes)
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
        "currentUpdateUploadBytes" to currentUpdateUploadBytes,
        "peakUpdateUploadBytes" to peakUpdateUploadBytes,
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
