package com.uhg0.ar_flutter_plugin_2.capture

import io.flutter.plugin.common.MethodChannel

/** Bounded acknowledged transport from AR frames to Dart. */
internal class PoseBatchDispatcher(
    private val send: (String, Map<String, Any?>, MethodChannel.Result) -> Unit,
    private val capacity: Int = 8,
) {
    init {
        require(capacity > 0)
    }

    private val pending = ArrayDeque<ByteArray>(capacity)
    private var inFlight = false
    private var droppedSinceLastBatch = 0L

    fun offer(sample: ByteArray) {
        require(sample.size == PackedPoseWireV2.SAMPLE_BYTES)
        if (pending.size == capacity) {
            pending.removeFirst()
            droppedSinceLastBatch++
        }
        pending.addLast(sample)
        dispatchIfIdle()
    }

    fun clear() {
        pending.clear()
        droppedSinceLastBatch = 0L
    }

    private fun dispatchIfIdle() {
        if (inFlight || pending.isEmpty()) return
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
        dispatchIfIdle()
    }
}
