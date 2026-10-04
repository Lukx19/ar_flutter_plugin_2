package com.uhg0.ar_flutter_plugin_2.visibilitygrid

/** Keeps depth fenced across overlapping capture pause and scene resume attempts. */
internal class DepthObservationPauseGate(
    private val suspendDepth: () -> Unit,
    private val resumeDepth: () -> Unit,
) {
    private var pending = 0
    private var paused = false

    @Synchronized fun begin() {
        pending++
        suspendDepth()
    }

    @Synchronized fun complete(success: Boolean) {
        check(pending > 0)
        pending--
        if (success) paused = true
        if (pending == 0 && !paused) resumeDepth()
    }

    @Synchronized fun resumed() {
        paused = false
        if (pending == 0) resumeDepth()
    }
}
