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

    private val pending = ArrayDeque<Map<String, Any?>>(capacity)
    private var inFlight = false
    private var droppedSinceLastBatch = 0L

    fun offer(sample: Map<String, Any?>) {
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
        val batch = pending.toList()
        pending.clear()
        val dropped = droppedSinceLastBatch
        droppedSinceLastBatch = 0L
        inFlight = true
        send(
            "onPoseBatch",
            mapOf(
                "wireVersion" to "pose_batch_v1",
                "samples" to batch,
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
