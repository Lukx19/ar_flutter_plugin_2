package com.uhg0.ar_flutter_plugin_2.capture

internal class PoseUpdateRateLimiter(
    maxRateHz: Int = 30,
) {
    private val minPoseUpdateIntervalNs = 1_000_000_000L / maxRateHz
    private var lastPoseUpdateTimestampNs: Long? = null
    private var paused = false
    private var disposed = false

    fun shouldEmit(timestampNs: Long): Boolean {
        if (paused || disposed) {
            return false
        }

        val previousTimestampNs = lastPoseUpdateTimestampNs
        if (
            previousTimestampNs != null &&
                timestampNs > previousTimestampNs &&
                timestampNs - previousTimestampNs < minPoseUpdateIntervalNs
        ) {
            return false
        }

        lastPoseUpdateTimestampNs = timestampNs
        return true
    }

    fun onPause() {
        paused = true
        lastPoseUpdateTimestampNs = null
    }

    fun onResume() {
        paused = false
    }

    fun onDispose() {
        disposed = true
        lastPoseUpdateTimestampNs = null
    }
}
