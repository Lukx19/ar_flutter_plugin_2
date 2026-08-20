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
        require(bytes >= 0)
        currentUpdateUploadBytes += bytes
        peakUpdateUploadBytes = maxOf(peakUpdateUploadBytes, currentUpdateUploadBytes)
    }

    fun recordUploadCallback() {
        uploadCallbackCount++
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
        "ordinaryUploadLimitBytes" to ORDINARY_UPLOAD_LIMIT_BYTES,
        "rendererAllocationLimitBytes" to RENDERER_ALLOCATION_LIMIT_BYTES,
    )

    internal companion object {
        const val ORDINARY_UPLOAD_LIMIT_BYTES = 64 * 1024
        const val RENDERER_ALLOCATION_LIMIT_BYTES = 8 * 1024 * 1024
    }
}
