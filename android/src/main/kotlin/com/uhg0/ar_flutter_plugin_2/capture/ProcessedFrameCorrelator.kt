package com.uhg0.ar_flutter_plugin_2.capture

internal data class CorrelatedProcessedFrame<Frame>(
    val frame: Frame,
    val result: PendingStillResultMetadata,
)

/** Pairs an unencoded processed frame with Camera2 timing metadata. */
internal class ProcessedFrameCorrelator<Frame>(
    private val clockMs: () -> Long = { System.currentTimeMillis() },
    private val timeoutMs: Long = 1_000L,
    private val maxPendingEntries: Int = 8,
) {
    private data class TimedFrame<Frame>(val frame: Frame, val receivedAtMs: Long)
    private data class TimedResult(
        val result: PendingStillResultMetadata,
        val receivedAtMs: Long,
    )

    private val frames = linkedMapOf<Long, TimedFrame<Frame>>()
    private val results = linkedMapOf<Long, TimedResult>()

    @Synchronized
    fun onFrame(timestampNs: Long, frame: Frame): CorrelatedProcessedFrame<Frame>? {
        cleanupExpired()
        results.remove(timestampNs)?.let { return CorrelatedProcessedFrame(frame, it.result) }
        frames[timestampNs] = TimedFrame(frame, clockMs())
        trim(frames)
        return null
    }

    @Synchronized
    fun onResult(result: PendingStillResultMetadata): CorrelatedProcessedFrame<Frame>? {
        cleanupExpired()
        frames.remove(result.sensorTimestampNs)?.let {
            return CorrelatedProcessedFrame(it.frame, result)
        }
        results[result.sensorTimestampNs] = TimedResult(result, clockMs())
        trim(results)
        return null
    }

    @Synchronized
    fun clear() {
        frames.clear()
        results.clear()
    }

    @Synchronized
    fun snapshot(): Pair<Int, Int> = frames.size to results.size

    private fun cleanupExpired() {
        val now = clockMs()
        frames.entries.removeIf { now - it.value.receivedAtMs > timeoutMs }
        results.entries.removeIf { now - it.value.receivedAtMs > timeoutMs }
    }

    private fun <K, V> trim(map: LinkedHashMap<K, V>) {
        while (map.size > maxPendingEntries) map.remove(map.entries.first().key)
    }
}
