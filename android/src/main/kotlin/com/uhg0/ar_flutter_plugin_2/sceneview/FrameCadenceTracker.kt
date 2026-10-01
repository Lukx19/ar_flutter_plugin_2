package com.uhg0.ar_flutter_plugin_2.sceneview

internal class FrameCadenceTracker(
    private val maximumSamples: Int = 600,
) {
    private val intervalsNs = ArrayDeque<Long>()
    private var previousFrameNs: Long? = null

    @Synchronized fun record(frameTimeNs: Long) {
        val previous = previousFrameNs
        if (previous != null && frameTimeNs <= previous) return
        previousFrameNs = frameTimeNs
        if (previous == null) return

        intervalsNs.addLast(frameTimeNs - previous)
        while (intervalsNs.size > maximumSamples) {
            intervalsNs.removeFirst()
        }
    }

    @Synchronized fun snapshot(): Map<String, Any> {
        if (intervalsNs.isEmpty()) {
            return mapOf(
                "sampleCount" to 0,
                "medianFrameIntervalMs" to 0.0,
                "medianFps" to 0.0,
                "p95FrameIntervalMs" to 0.0,
                "p99FrameIntervalMs" to 0.0,
                "maxFrameIntervalMs" to 0.0,
                "frameGapsOver50Ms" to 0,
                "frameGapsOver100Ms" to 0,
            )
        }

        val sorted = intervalsNs.sorted()
        val middle = sorted.size / 2
        val medianNs = if (sorted.size % 2 == 0) {
            (sorted[middle - 1] + sorted[middle]) / 2.0
        } else {
            sorted[middle].toDouble()
        }
        return mapOf(
            "sampleCount" to sorted.size,
            "medianFrameIntervalMs" to medianNs / 1_000_000.0,
            "medianFps" to 1_000_000_000.0 / medianNs,
            "p95FrameIntervalMs" to sorted[percentileIndex(sorted.size, 95)] / 1_000_000.0,
            "p99FrameIntervalMs" to sorted[percentileIndex(sorted.size, 99)] / 1_000_000.0,
            "maxFrameIntervalMs" to sorted.last() / 1_000_000.0,
            "frameGapsOver50Ms" to sorted.count { it > 50_000_000L },
            "frameGapsOver100Ms" to sorted.count { it > 100_000_000L },
        )
    }

    /** Cheap recent-frame guard for background depth work on the AR callback. */
    @Synchronized fun healthyForDepthIntake(): Boolean {
        if (intervalsNs.size < 15) return true
        var longGaps = 0
        val first = (intervalsNs.size - 60).coerceAtLeast(0)
        for (index in intervalsNs.lastIndex downTo first) {
            val interval = intervalsNs[index]
            if (interval > 100_000_000L) return false
            if (interval > 50_000_000L) longGaps++
        }
        return longGaps <= 3
    }

    private fun percentileIndex(size: Int, percentile: Int): Int =
        ((size * percentile + 99) / 100 - 1).coerceIn(0, size - 1)
}
