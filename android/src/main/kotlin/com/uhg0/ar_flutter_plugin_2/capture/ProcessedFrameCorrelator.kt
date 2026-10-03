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
    private val onFrameDiscarded: (Frame) -> Unit = {},
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
        frames.remove(timestampNs)?.let { onFrameDiscarded(it.frame) }
        frames[timestampNs] = TimedFrame(frame, clockMs())
        trimFrames()
        return null
    }

    @Synchronized
    fun onResult(result: PendingStillResultMetadata): CorrelatedProcessedFrame<Frame>? {
        cleanupExpired()
        frames.remove(result.sensorTimestampNs)?.let {
            return CorrelatedProcessedFrame(it.frame, result)
        }
        results[result.sensorTimestampNs] = TimedResult(result, clockMs())
        trimResults()
        return null
    }

    @Synchronized
    fun clear() {
        frames.values.forEach { onFrameDiscarded(it.frame) }
        frames.clear()
        results.clear()
    }

    @Synchronized
    fun snapshot(): Pair<Int, Int> = frames.size to results.size

    private fun cleanupExpired() {
        val now = clockMs()
        val frameIterator = frames.entries.iterator()
        while (frameIterator.hasNext()) {
            val entry = frameIterator.next()
            if (now - entry.value.receivedAtMs > timeoutMs) {
                frameIterator.remove()
                onFrameDiscarded(entry.value.frame)
            }
        }
        results.entries.removeIf { now - it.value.receivedAtMs > timeoutMs }
    }

    private fun trimFrames() {
        while (frames.size > maxPendingEntries) {
            val key = frames.entries.first().key
            frames.remove(key)?.let { onFrameDiscarded(it.frame) }
        }
    }

    private fun trimResults() {
        while (results.size > maxPendingEntries) {
            results.remove(results.entries.first().key)
        }
    }
}
