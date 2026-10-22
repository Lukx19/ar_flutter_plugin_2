package com.uhg0.ar_flutter_plugin_2.capture

import io.flutter.plugin.common.MethodChannel

/**
 * Bounded acknowledged transport from AR frames to Dart.
 *
 * Each send costs a platform-channel encode and engine post on the Android
 * main thread, plus a Dart dispatch and reply. With [samplesPerSend] above
 * one, an idle dispatcher holds samples until that many are pending, which
 * divides the number of pose-channel sends by [samplesPerSend]. A held sample
 * is sent at most one [scheduleFlush] delay after it was held: the flush sends
 * whatever is pending, and every send cancels a scheduled flush so each held
 * sample waits a full delay for its pair. Samples keep their order and the
 * drop-oldest and one-in-flight rules are unchanged. All calls run on one
 * thread (the main looper in `ArView`).
 */
internal class PoseBatchDispatcher(
    private val send: (String, Map<String, Any?>, MethodChannel.Result) -> Unit,
    private val capacity: Int = 8,
    private val samplesPerSend: Int = 1,
    /** Runs the task once after the maximum hold delay; required when [samplesPerSend] is above one. */
    private val scheduleFlush: ((Runnable) -> Unit)? = null,
    /** Removes a task passed to [scheduleFlush]; required together with it. */
    private val cancelFlush: ((Runnable) -> Unit)? = null,
) {
    init {
        require(capacity > 0)
        require(samplesPerSend in 1..capacity)
        require(samplesPerSend == 1 || scheduleFlush != null)
        require((scheduleFlush == null) == (cancelFlush == null))
    }

    private val pending = ArrayDeque<ByteArray>(capacity)
    private var inFlight = false
    private var droppedSinceLastBatch = 0L
    private var flushScheduled = false
    private var closed = false
    private val flushTask = Runnable {
        flushScheduled = false
        dispatchIfIdle(holdPartialBatch = false)
    }

    fun offer(sample: ByteArray) {
        require(sample.size == PackedPoseWireV2.SAMPLE_BYTES)
        if (closed) return
        if (pending.size == capacity) {
            pending.removeFirst()
            droppedSinceLastBatch++
        }
        pending.addLast(sample)
        dispatchIfIdle(holdPartialBatch = true)
    }

    /**
     * Drops pending samples and cancels a scheduled flush. Later offers,
     * flushes and acknowledgements send nothing; a batch already in flight
     * keeps its immutable payload.
     */
    fun close() {
        closed = true
        pending.clear()
        droppedSinceLastBatch = 0L
        if (flushScheduled) {
            flushScheduled = false
            cancelFlush!!(flushTask)
        }
    }

    private fun dispatchIfIdle(holdPartialBatch: Boolean) {
        if (closed || inFlight || pending.isEmpty()) return
        if (holdPartialBatch && pending.size < samplesPerSend) {
            if (!flushScheduled) {
                flushScheduled = true
                scheduleFlush!!(flushTask)
            }
            return
        }
        if (flushScheduled) {
            // The next held sample gets its own full hold delay.
            flushScheduled = false
            cancelFlush!!(flushTask)
        }
        val count = pending.size
        val batch = if (count == 1) pending.removeFirst() else {
            ByteArray(count * PackedPoseWireV2.SAMPLE_BYTES).also { bytes ->
                for (index in 0 until count) pending.removeFirst().copyInto(bytes, index * PackedPoseWireV2.SAMPLE_BYTES)
            }
        }
        val dropped = droppedSinceLastBatch
        droppedSinceLastBatch = 0L
        inFlight = true
        send(
            "onPoseBatch",
            mapOf(
                "wireVersion" to PackedPoseWireV2.VERSION,
                "sampleCount" to count,
                "sampleBytes" to batch,
                "droppedOldestCount" to dropped,
            ),
            object : MethodChannel.Result {
                override fun success(result: Any?) = complete()
                override fun error(errorCode: String, errorMessage: String?, errorDetails: Any?) = complete()
                override fun notImplemented() = complete()
            },
        )
    }

    private fun complete() {
        inFlight = false
        dispatchIfIdle(holdPartialBatch = true)
    }
}
