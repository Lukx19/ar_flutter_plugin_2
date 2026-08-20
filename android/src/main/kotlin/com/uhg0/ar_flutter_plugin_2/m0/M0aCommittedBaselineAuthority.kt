package com.uhg0.ar_flutter_plugin_2.m0

/**
 * Process-scoped owner for the last native committed cut.
 *
 * A platform view is replaceable, but the capture-group authority is not. The
 * plugin keeps this small value object outside each view so a replacement can
 * start from the same committed geometry/style cut instead of silently
 * reverting to ZERO. Transaction identity remains owned by the stream; a
 * binding-start result therefore reports the canonical binding transaction
 * value while the full cut remains available to the native stream.
 */
data class M0aCommittedBaselineScopeV1(
    val sessionId: M0aUuid,
    val captureGroupId: M0aUuid,
    val sessionGeneration: Long,
    val groupGeneration: Long,
    val coverageEpoch: Long,
) {
    companion object {
        fun from(request: M0aControlRequest): M0aCommittedBaselineScopeV1 =
            M0aCommittedBaselineScopeV1(
                sessionId = request.sessionId,
                captureGroupId = request.captureGroupId,
                sessionGeneration = request.sessionGeneration,
                groupGeneration = request.groupGeneration,
                coverageEpoch = request.coverageEpoch,
            )
    }
}

class M0aCommittedBaselineAuthority {
    private val values = mutableMapOf<M0aCommittedBaselineScopeV1, M0aCommittedBaselineV1>()

    @Synchronized
    fun snapshot(scope: M0aCommittedBaselineScopeV1): M0aCommittedBaselineV1 =
        values[scope] ?: M0aCommittedBaselineV1.ZERO

    @Synchronized
    fun publish(scope: M0aCommittedBaselineScopeV1, next: M0aCommittedBaselineV1) {
        values[scope] = next
    }
}
