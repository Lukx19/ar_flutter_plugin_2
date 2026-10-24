package com.uhg0.ar_flutter_plugin_2.capture

import java.util.concurrent.atomic.AtomicBoolean

/** One exposure confirmation per logical photo, including bracketed shots. */
internal class CaptureShutterConfirmation {
    private val confirmed = AtomicBoolean(false)

    fun cancel() { confirmed.set(true) }

    fun onExposureCompleted(memberIndex: Int = 0, lastMemberIndex: Int = 0, feedback: () -> Unit) {
        // Match the submitted final member, rather than counting callbacks:
        // duplicate/late results must not confirm while a bracket still exposes.
        if (memberIndex < 0 || memberIndex != lastMemberIndex) return
        if (confirmed.compareAndSet(false, true)) feedback()
    }
}
