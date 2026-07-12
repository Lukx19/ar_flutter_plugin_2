package com.uhg0.ar_flutter_plugin_2.capture

internal class SharedCameraRepeatingRequestLifecycle {
    private var hasStartedRepeating = false
    private var isPaused = false

    fun markRepeatingStarted() {
        hasStartedRepeating = true
    }

    fun onSessionPaused(): Boolean {
        val shouldStopRepeating = hasStartedRepeating && !isPaused
        isPaused = true
        return shouldStopRepeating
    }

    fun onSessionResumed(): Boolean {
        val shouldRestartRepeating = hasStartedRepeating && isPaused
        isPaused = false
        return shouldRestartRepeating
    }

    fun shouldSubmitRuntimeUpdate(): Boolean = hasStartedRepeating && !isPaused

    fun isRepeatingActive(): Boolean = hasStartedRepeating && !isPaused
}
