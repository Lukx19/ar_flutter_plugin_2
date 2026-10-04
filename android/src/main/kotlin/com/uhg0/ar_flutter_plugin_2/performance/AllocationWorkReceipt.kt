package com.uhg0.ar_flutter_plugin_2.performance

/** Process-wide ART churn; native heap occupancy must be recorded separately. */
data class AllocationWorkReceipt(
    val stage: String,
    val elapsedNs: Long,
    val artAllocatedBytes: Long,
    val artFreedBytes: Long,
    val gcCount: Long,
    val gcDurationMs: Long,
    val completedUnits: Long,
    val inputBytes: Long,
    val selectedSamples: Long,
    val ownedCapacityBytes: Long,
    val peakLeases: Int,
    val growthEvents: Long,
) {
    val valid: Boolean get() = stage.isNotBlank() && elapsedNs > 0 &&
        artAllocatedBytes >= 0 && artFreedBytes >= 0 && gcCount >= 0 &&
        gcDurationMs >= 0 && completedUnits >= 0 && inputBytes >= 0 &&
        selectedSamples >= 0 && ownedCapacityBytes >= 0 && peakLeases >= 0 && growthEvents >= 0

    val allocatedBytesPerUnit: Double?
        get() = if (valid && completedUnits > 0) artAllocatedBytes.toDouble() / completedUnits else null
    val allocatedBytesPerMinute: Double?
        get() = if (valid) artAllocatedBytes.toDouble() * 60_000_000_000.0 / elapsedNs else null

    fun toWireMap(): Map<String, Any?> = mapOf(
        "stage" to stage, "valid" to valid, "elapsedNs" to elapsedNs,
        "artAllocatedBytes" to artAllocatedBytes, "artFreedBytes" to artFreedBytes,
        "gcCount" to gcCount, "gcDurationMs" to gcDurationMs,
        "completedUnits" to completedUnits, "inputBytes" to inputBytes,
        "selectedSamples" to selectedSamples, "ownedCapacityBytes" to ownedCapacityBytes,
        "peakLeases" to peakLeases, "growthEvents" to growthEvents,
        "allocatedBytesPerUnit" to allocatedBytesPerUnit,
        "allocatedBytesPerMinute" to allocatedBytesPerMinute,
    )

    /** A reset is invalid evidence, never clamped to zero. */
    companion object {
        fun between(
            stage: String,
            before: AllocationCounters,
            after: AllocationCounters,
            completedUnits: Long,
            inputBytes: Long,
            selectedSamples: Long,
            ownedCapacityBytes: Long,
            peakLeases: Int,
            growthEvents: Long,
        ) = AllocationWorkReceipt(
            stage, after.elapsedNs - before.elapsedNs,
            after.allocatedBytes - before.allocatedBytes,
            after.freedBytes - before.freedBytes, after.gcCount - before.gcCount,
            after.gcDurationMs - before.gcDurationMs, completedUnits, inputBytes,
            selectedSamples, ownedCapacityBytes, peakLeases, growthEvents,
        )
    }
}

data class AllocationCounters(
    val elapsedNs: Long,
    val allocatedBytes: Long,
    val freedBytes: Long,
    val gcCount: Long,
    val gcDurationMs: Long,
)

/** Artifact identity includes fixture source and instrumentation, not candidate code hash. */
data class AllocationWorkloadIdentity(
    val fixture: String,
    val fixtureSourceRevision: String,
    val instrumentationProfile: String,
    val device: String,
    val runtimeVersion: String,
    val seed: Long,
    val offered: Long,
    val accepted: Long,
    val completed: Long,
    val replaced: Long,
    val refused: Long,
    val inputBytes: Long,
    val selectedSamples: Long,
    val mutations: Long,
) {
    val valid: Boolean get() = fixture.isNotBlank() && fixtureSourceRevision.isNotBlank() &&
        fixtureSourceRevision != "unrecorded" && instrumentationProfile.isNotBlank() &&
        device.isNotBlank() && runtimeVersion.isNotBlank() && offered >= 0 &&
        accepted in 0..offered && completed in 0..accepted && replaced in 0..accepted &&
        refused in 0..offered && accepted <= offered - refused &&
        completed <= accepted - replaced && inputBytes >= 0 && selectedSamples >= 0 && mutations >= 0
}

object AllocationWorkComparison {
    fun requireComparable(
        baselineIdentity: AllocationWorkloadIdentity,
        candidateIdentity: AllocationWorkloadIdentity,
        baseline: AllocationWorkReceipt,
        candidate: AllocationWorkReceipt,
    ): Double {
        require(baselineIdentity.valid && candidateIdentity.valid) { "Invalid or unrecorded workload identity." }
        require(baselineIdentity == candidateIdentity) { "Allocation runs completed unequal work or used different fixtures/profiles." }
        require(baseline.valid && candidate.valid) { "Invalid or reset allocation counters." }
        require(baseline.stage == candidate.stage && baseline.completedUnits == candidate.completedUnits &&
            baseline.inputBytes == candidate.inputBytes && baseline.selectedSamples == candidate.selectedSamples) {
            "Allocation stage denominators differ."
        }
        val before = requireNotNull(baseline.allocatedBytesPerUnit) { "No completed work." }
        val after = requireNotNull(candidate.allocatedBytesPerUnit) { "No completed work." }
        require(before > 0) { "Baseline has no measurable allocation." }
        return 1.0 - after / before
    }
}

/** Lazy probes add no counter sampling when disabled and cannot fail accepted work. */
inline fun publishAllocationReceipt(
    noinline sink: ((AllocationWorkReceipt) -> Unit)?,
    receipt: () -> AllocationWorkReceipt,
) {
    if (sink == null) return
    try { sink(receipt()) } catch (_: Throwable) { /* Diagnostic observer only. */ }
}
