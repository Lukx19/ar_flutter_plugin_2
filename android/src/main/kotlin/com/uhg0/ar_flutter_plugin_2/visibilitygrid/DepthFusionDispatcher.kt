package com.uhg0.ar_flutter_plugin_2.visibilitygrid

import java.util.concurrent.Executor

class DepthFusionDispatcher(
    private val executor: Executor,
    private val onFailure: (RuntimeException) -> Unit = {},
    private val consume: (DepthAcquisitionResult) -> Unit,
) : AutoCloseable {
    private var pending: DepthAcquisitionResult? = null
    private var running = false
    private var closed = false

    @Synchronized
    fun offer(result: DepthAcquisitionResult): Boolean {
        if (closed) return false
        pending = result
        if (!running) {
            running = true
            try {
                executor.execute(::drain)
            } catch (error: RuntimeException) {
                running = false
                pending = null
                onFailure(error)
                return false
            }
        }
        return true
    }

    private fun drain() {
        while (true) {
            val next =
                synchronized(this) {
                    val value = pending
                    pending = null
                    if (value == null || closed) {
                        running = false
                        return
                    }
                    value
                }
            try {
                consume(next)
            } catch (error: RuntimeException) {
                onFailure(error)
            }
        }
    }

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        pending = null
    }
}
