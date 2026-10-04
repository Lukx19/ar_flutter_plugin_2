package com.uhg0.ar_flutter_plugin_2.sceneview

/** Opt-in diagnostic scalars; allocation windows include every process thread. */
internal class RendererMainApplyAttribution {
    private var sampleCount = 0L
    private var totalNanos = 0L
    private var maxNanos = 0L
    private var allocationSampleCount = 0L
    private var totalAllocatedBytes = 0L
    private var maxAllocatedBytes = 0L

    @Synchronized
    fun reset() {
        sampleCount = 0L
        totalNanos = 0L
        maxNanos = 0L
        allocationSampleCount = 0L
        totalAllocatedBytes = 0L
        maxAllocatedBytes = 0L
    }

    @Synchronized
    fun record(elapsedNanos: Long, allocatedBefore: Long, allocatedAfter: Long) {
        require(elapsedNanos >= 0L)
        sampleCount++
        totalNanos += elapsedNanos
        maxNanos = maxOf(maxNanos, elapsedNanos)
        // Missing or reset process counters are unavailable, not zero work.
        if (allocatedBefore < 0L || allocatedAfter < allocatedBefore) return
        val allocatedBytes = allocatedAfter - allocatedBefore
        allocationSampleCount++
        totalAllocatedBytes += allocatedBytes
        maxAllocatedBytes = maxOf(maxAllocatedBytes, allocatedBytes)
    }

    @Synchronized
    fun snapshot(): Map<String, Any> = snapshotValues(
        enabled = true,
        sampleCount = sampleCount,
        totalNanos = totalNanos,
        maxNanos = maxNanos,
        allocationSampleCount = allocationSampleCount,
        totalAllocatedBytes = if (allocationSampleCount == 0L) -1L else totalAllocatedBytes,
        maxAllocatedBytes = if (allocationSampleCount == 0L) -1L else maxAllocatedBytes,
    )

    companion object {
        const val TRACE_SECTION = "CoverageRenderer.mainApply"
        // Modeled scalar payload only; this diagnostic owner has no row buffers
        // and is separate from production renderer/canonical memory limits.
        const val SCALAR_BYTES = 6 * Long.SIZE_BYTES

        fun disabledSnapshot(): Map<String, Any> = snapshotValues(
            enabled = false,
            sampleCount = 0L,
            totalNanos = 0L,
            maxNanos = 0L,
            allocationSampleCount = 0L,
            totalAllocatedBytes = -1L,
            maxAllocatedBytes = -1L,
        )

        private fun snapshotValues(
            enabled: Boolean,
            sampleCount: Long,
            totalNanos: Long,
            maxNanos: Long,
            allocationSampleCount: Long,
            totalAllocatedBytes: Long,
            maxAllocatedBytes: Long,
        ): Map<String, Any> = mapOf(
            "rendererMainApplyDiagnosticsEnabled" to enabled,
            "rendererMainApplySampleCount" to sampleCount,
            "rendererMainApplyTotalNanos" to totalNanos,
            "rendererMainApplyMaxNanos" to maxNanos,
            "rendererMainApplyAllocationSampleCount" to allocationSampleCount,
            "rendererMainApplyProcessAllocatedBytesTotal" to totalAllocatedBytes,
            "rendererMainApplyProcessAllocatedBytesMax" to maxAllocatedBytes,
            "rendererMainApplyDiagnosticsScalarBytes" to if (enabled) SCALAR_BYTES else 0,
            "rendererMainApplyTimingScope" to "main-publication-application-excludes-page-upload-and-gpu",
            "rendererMainApplyAllocationScope" to "inclusive-process-counter-window-may-overlap-worker",
        )
    }
}
