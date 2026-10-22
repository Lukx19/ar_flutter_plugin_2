package com.uhg0.ar_flutter_plugin_2.capture

/**
 * Limits the pose wire stream to about [maxRateHz] samples per second of
 * sensor time, with strictly increasing timestamps.
 *
 * A frame is emitted once at least [TOLERANCE_PERCENT] percent of the nominal period
 * has passed since the last emitted frame. Camera intervals jitter around the
 * nominal period (33.2 ms, 33.4 ms at 30 fps); a strict full-period check
 * rejects every interval just under it and halves the stream. The tolerance
 * still rejects the second frame of a 60 fps pair (16.7 ms), so the long-run
 * rate stays at or below the nominal rate for 30 and 60 fps cameras.
 */
internal class PoseUpdateRateLimiter(
    maxRateHz: Int = 30,
) {
    private val minPoseUpdateIntervalNs =
        (1_000_000_000L * TOLERANCE_PERCENT) / (100L * maxRateHz)
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
                timestampNs <= previousTimestampNs
        ) {
            return false
        }
        if (
            previousTimestampNs != null &&
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

    internal companion object {
        /** Emit once 80% of the nominal period has passed (26.7 ms at 30 Hz). */
        const val TOLERANCE_PERCENT = 80L
    }
}
