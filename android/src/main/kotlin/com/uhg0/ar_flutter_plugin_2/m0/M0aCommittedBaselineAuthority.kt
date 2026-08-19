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
class M0aCommittedBaselineAuthority(
    initial: M0aCommittedBaselineV1 = M0aCommittedBaselineV1.ZERO,
) {
    @Volatile private var value = initial

    @Synchronized
    fun snapshot(): M0aCommittedBaselineV1 = value

    @Synchronized
    fun publish(next: M0aCommittedBaselineV1) {
        value = next
    }
}
