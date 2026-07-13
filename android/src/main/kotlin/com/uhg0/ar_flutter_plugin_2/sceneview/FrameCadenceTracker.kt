package com.uhg0.ar_flutter_plugin_2.sceneview

internal class FrameCadenceTracker(
    private val maximumSamples: Int = 600,
) {
    private val intervalsNs = ArrayDeque<Long>()
    private var previousFrameNs: Long? = null

    fun record(frameTimeNs: Long) {
        val previous = previousFrameNs
        previousFrameNs = frameTimeNs
        if (previous == null || frameTimeNs <= previous) return

        intervalsNs.addLast(frameTimeNs - previous)
        while (intervalsNs.size > maximumSamples) {
            intervalsNs.removeFirst()
        }
    }

    fun snapshot(): Map<String, Any> {
        if (intervalsNs.isEmpty()) {
            return mapOf(
                "sampleCount" to 0,
                "medianFrameIntervalMs" to 0.0,
                "medianFps" to 0.0,
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
        )
    }
}
