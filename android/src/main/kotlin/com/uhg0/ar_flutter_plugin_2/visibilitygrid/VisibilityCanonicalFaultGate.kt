package com.uhg0.ar_flutter_plugin_2.visibilitygrid

import java.util.concurrent.atomic.AtomicBoolean

/** One-shot debug fault at the real canonical commit boundary. */
internal class VisibilityCanonicalFaultGate(private val isDebuggable: Boolean) {
    private val retryableDepthCommit = AtomicBoolean(false)

    fun armRetryableDepthCommit() {
        check(isDebuggable) { "canonical fault injection is debug-only" }
        check(retryableDepthCommit.compareAndSet(false, true)) {
            "a retryable depth commit fault is already armed"
        }
    }

    fun commit(
        resources: CanonicalRuntimeResources,
        mutation: PreparedCanonicalMutation,
    ): CanonicalAdjacentCommitResult {
        if (mutation.kind == PreparedMutationKind.DEPTH_BATCH &&
            retryableDepthCommit.compareAndSet(true, false)
        ) {
            return CanonicalAdjacentCommitResult.Refused(
                CanonicalAdjacentCommitRefusal.DURABILITY_FAILURE,
                disposition = PreparedMutationDisposition.RETRYABLE,
            )
        }
        return resources.commitAdjacent(mutation)
    }
}
