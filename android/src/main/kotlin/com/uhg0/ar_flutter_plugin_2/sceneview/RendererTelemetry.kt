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
    private val presentationCounts = linkedMapOf<String, Int>()
    private var resourceReplacementCount = 0
    private var resourceDisposalCount = 0
    private var resourceFailureCount = 0
    private var lastAdmissionStrategy = "none"
    private var lastAdmissionCurrentBytes = 0
    private var lastAdmissionCandidateBytes = 0
    private var lastAdmissionCombinedBytes = 0
    private var lastAdmissionOwnershipReceipt: CoverageRendererOwnershipReceipt? = null
    private var residentRowCount = 0
    private var residentGlyphCount = 0
    private var residentToken: CoverageResourceToken? = null
    private val residentRowsByMode = linkedMapOf<CoveragePresentationMode, Int>()
    private val residentGlyphsByMode = linkedMapOf<CoveragePresentationMode, Int>()

    @Synchronized
    fun recordPresentation(mode: CoveragePresentationMode) {
        val key = when (mode) {
            CoveragePresentationMode.SEMANTIC_CENTROIDS -> "semanticCentroidCount"
            CoveragePresentationMode.SEMANTIC_CUBES -> "semanticCubeCount"
            CoveragePresentationMode.RAW_FEATURES -> "rawFeatureCount"
            CoveragePresentationMode.WARM_PROXIES -> "warmProxyCount"
            CoveragePresentationMode.OVERVIEW -> "coldOverviewCount"
            CoveragePresentationMode.SUPPRESSED_DEBUG -> "debugCount"
        }
        presentationCounts[key] = (presentationCounts[key] ?: 0) + 1
    }

    @Synchronized
    fun recordResourceReplacement() {
        resourceReplacementCount++
    }

    @Synchronized
    fun recordResourceDisposal() {
        resourceDisposalCount++
    }

    @Synchronized
    fun recordResourceFailure() {
        resourceFailureCount++
    }

    @Synchronized
    fun recordResourceAdmission(admission: CoverageRendererResourceAdmission) {
        lastAdmissionStrategy = admission.strategy.wireName
        lastAdmissionCurrentBytes = admission.currentBytes
        lastAdmissionCandidateBytes = admission.candidateBytes
        lastAdmissionCombinedBytes = admission.combinedBytes
        lastAdmissionOwnershipReceipt = admission.ownershipReceipt
    }

    @Synchronized
    fun setResidentPresentation(rowCount: Int, glyphCount: Int) {
        require(rowCount >= 0)
        require(glyphCount in 0..rowCount)
        residentRowCount = rowCount
        residentGlyphCount = glyphCount
    }

    @Synchronized
    fun setResidentPresentation(
        mode: CoveragePresentationMode,
        rowCount: Int,
        glyphCount: Int,
    ) {
        setResidentPresentation(rowCount, glyphCount)
        residentToken = null
        residentRowsByMode[mode] = rowCount
        residentGlyphsByMode[mode] = glyphCount
    }

    /** Updates gauges only for the owner-issued resource lifetime. */
    @Synchronized
    fun setResidentPresentation(
        token: CoverageResourceToken,
        mode: CoveragePresentationMode,
        rowCount: Int,
        glyphCount: Int,
    ): Boolean {
        if (residentToken != null && residentToken != token &&
            residentToken!!.epoch > token.epoch
        ) return false
        if (residentToken != token) clearResidentPresentation()
        setResidentPresentation(rowCount, glyphCount)
        residentToken = token
        residentRowsByMode[mode] = rowCount
        residentGlyphsByMode[mode] = glyphCount
        return true
    }

    @Synchronized
    fun clearResidentPresentation(token: CoverageResourceToken): Boolean {
        if (residentToken != token) return false
        clearResidentPresentation()
        return true
    }

    @Synchronized
    fun clearResidentPresentation() {
        residentToken = null
        residentRowCount = 0
        residentGlyphCount = 0
        residentRowsByMode.clear()
        residentGlyphsByMode.clear()
    }

    fun setOwnedBufferBytes(owner: String, bytes: Int) {
        require(owner.isNotBlank())
        require(bytes >= 0)
        val previous = allocationsByOwner.put(owner, bytes)
        if (ownedBufferBytes > RENDERER_INSTANTANEOUS_LIMIT_BYTES) {
            if (previous == null) {
                allocationsByOwner.remove(owner)
            } else {
                allocationsByOwner[owner] = previous
            }
            throw IllegalStateException(
                "renderer-owned buffers exceed $RENDERER_INSTANTANEOUS_LIMIT_BYTES bytes",
            )
        }
        peakOwnedBufferBytes = maxOf(peakOwnedBufferBytes, ownedBufferBytes)
    }

    fun removeOwner(owner: String) {
        allocationsByOwner.remove(owner)
    }

    /** Removes only concrete mesh generations; semantic projection owners stay resident. */
    @Synchronized
    internal fun removeCoverageMeshOwners() {
        allocationsByOwner.keys
            .filter { owner ->
                owner == "coverage-points" || owner.startsWith("coverage-points-") ||
                    owner == "coverage-centroids" || owner.startsWith("coverage-centroids-") ||
                    owner == "coverage-cubes" || owner.startsWith("coverage-cubes-")
            }
            .toList()
            .forEach(allocationsByOwner::remove)
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

    /**
     * The platform-view recreation gate samples this process-scoped audit from
     * its replacement view. A callback delivered after its owning coordinator
     * is destroyed is fenced from renderer state and remains observable here.
     */
    fun recordFencedDestroyedUploadCallback() {
        fencedDestroyedUploadCallbacks.incrementAndGet()
    }

    private val ownedBufferBytes: Int
        get() = allocationsByOwner.values.sum()

    @Synchronized
    internal fun ownedBufferBytesSnapshot(): Int = ownedBufferBytes

    @Synchronized
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
        "fencedDestroyedUploadCallbackCount" to
            fencedDestroyedUploadCallbacks.get(),
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
        "rendererInstantaneousLimitBytes" to RENDERER_INSTANTANEOUS_LIMIT_BYTES,
        "rendererTransitionReserveBytes" to CoverageRendererLimits.TRANSITION_RESERVE_BYTES,
        "ownedBufferBytesByOwner" to allocationsByOwner.toMap(),
        "semanticCentroidCount" to (presentationCounts["semanticCentroidCount"] ?: 0),
        "semanticCubeCount" to (presentationCounts["semanticCubeCount"] ?: 0),
        "rawFeatureCount" to (presentationCounts["rawFeatureCount"] ?: 0),
        "warmProxyCount" to (presentationCounts["warmProxyCount"] ?: 0),
        "coldOverviewCount" to (presentationCounts["coldOverviewCount"] ?: 0),
        "glyphCount" to (presentationCounts["glyphCount"] ?: 0),
        "debugCount" to (presentationCounts["debugCount"] ?: 0),
        "resourceReplacementCount" to resourceReplacementCount,
        "resourceDisposalCount" to resourceDisposalCount,
        "resourceFailureCount" to resourceFailureCount,
        "lastAdmissionStrategy" to lastAdmissionStrategy,
        "lastAdmissionCurrentBytes" to lastAdmissionCurrentBytes,
        "lastAdmissionCandidateBytes" to lastAdmissionCandidateBytes,
        "lastAdmissionCombinedBytes" to lastAdmissionCombinedBytes,
        "lastAdmissionOwnershipBytes" to (lastAdmissionOwnershipReceipt?.totalBytes ?: 0),
        "lastAdmissionOwnershipReceipt" to (
            lastAdmissionOwnershipReceipt?.asMap() ?: emptyMap<String, Any>()
        ),
        "cumulativeResourceReplacementCount" to resourceReplacementCount,
        "cumulativeResourceDisposalCount" to resourceDisposalCount,
        "residentRowCount" to residentRowCount,
        "residentGlyphCount" to residentGlyphCount,
        "residentSemanticCentroidCount" to residentRowsByMode[
            CoveragePresentationMode.SEMANTIC_CENTROIDS
        ].orZero(),
        "residentSemanticCentroidGlyphCount" to residentGlyphsByMode[
            CoveragePresentationMode.SEMANTIC_CENTROIDS
        ].orZero(),
        "residentSemanticCubeCount" to residentRowsByMode[
            CoveragePresentationMode.SEMANTIC_CUBES
        ].orZero(),
        "residentSemanticCubeGlyphCount" to residentGlyphsByMode[
            CoveragePresentationMode.SEMANTIC_CUBES
        ].orZero(),
        "residentRawFeatureCount" to residentRowsByMode[
            CoveragePresentationMode.RAW_FEATURES
        ].orZero(),
        "residentRawFeatureGlyphCount" to residentGlyphsByMode[
            CoveragePresentationMode.RAW_FEATURES
        ].orZero(),
        "residentWarmProxyCount" to residentRowsByMode[
            CoveragePresentationMode.WARM_PROXIES
        ].orZero(),
        "residentWarmProxyGlyphCount" to residentGlyphsByMode[
            CoveragePresentationMode.WARM_PROXIES
        ].orZero(),
        "residentOverviewCount" to residentRowsByMode[
            CoveragePresentationMode.OVERVIEW
        ].orZero(),
        "residentOverviewGlyphCount" to residentGlyphsByMode[
            CoveragePresentationMode.OVERVIEW
        ].orZero(),
        "residentSuppressedDebugCount" to residentRowsByMode[
            CoveragePresentationMode.SUPPRESSED_DEBUG
        ].orZero(),
        "residentSuppressedDebugGlyphCount" to residentGlyphsByMode[
            CoveragePresentationMode.SUPPRESSED_DEBUG
        ].orZero(),
        "cumulativeReplacementBalance" to resourceDisposalCount - resourceReplacementCount,
        "resourceReplacementDisposalBalance" to
            resourceDisposalCount - resourceReplacementCount,
    )

    internal companion object {
        const val ORDINARY_UPLOAD_LIMIT_BYTES = 64 * 1024
        /** One-generation admission ceiling; coexistence uses the transition ceiling. */
        const val RENDERER_ALLOCATION_LIMIT_BYTES =
            CoverageRendererLimits.ACTIVE_RENDERER_OWNED_LIMIT_BYTES
        const val RENDERER_INSTANTANEOUS_LIMIT_BYTES =
            CoverageRendererLimits.INSTANTANEOUS_TRANSITION_LIMIT_BYTES
        private val fencedDestroyedUploadCallbacks = AtomicInteger()
    }
}

private fun CoverageRendererOwnershipReceipt.asMap(): Map<String, Any> = mapOf(
    "canonicalStateBytes" to canonicalStateBytes,
    "mutableProjectionSelectorBytes" to mutableProjectionSelectorBytes,
    "descriptorBackingBytes" to descriptorBackingBytes,
    "pageReaderCapturedMappingBytes" to pageReaderCapturedMappingBytes,
    "stagingBytes" to stagingBytes,
    "meshBytes" to meshBytes,
    "totalBytes" to totalBytes,
)

private fun Int?.orZero(): Int = this ?: 0

private val RendererUploadPageOrigin.wireName: String
    get() = when (this) {
        RendererUploadPageOrigin.RESOURCE_GENERATION_RESET -> "resource-generation-reset"
        RendererUploadPageOrigin.ORDINARY -> "ordinary"
    }
