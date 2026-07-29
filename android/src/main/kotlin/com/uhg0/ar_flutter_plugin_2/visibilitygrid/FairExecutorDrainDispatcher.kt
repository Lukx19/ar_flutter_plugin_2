package com.uhg0.ar_flutter_plugin_2.visibilitygrid

import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException

/**
 * Coalesces producer requests while yielding the executor after every turn.
 *
 * Control work submitted while a sensor turn is running stays ahead of the
 * next sensor turn, so a continuously refilled latest-value handoff cannot
 * starve lifecycle barriers.
 */
internal class FairExecutorDrainDispatcher(
    private val executor: Executor,
    private val drainOne: () -> Unit,
) {
    private var scheduled = false
    private var requested = false

    @Synchronized
    fun request() {
        requested = true
        if (scheduled) return
        scheduled = true
        submitTurn()
    }

    private fun submitTurn() {
        try {
            executor.execute(::runTurn)
        } catch (_: RejectedExecutionException) {
            synchronized(this) {
                scheduled = false
                requested = false
            }
        }
    }

    private fun runTurn() {
        synchronized(this) {
            requested = false
        }
        try {
            drainOne()
        } finally {
            val reschedule =
                synchronized(this) {
                    if (requested) {
                        true
                    } else {
                        scheduled = false
                        false
                    }
                }
            if (reschedule) submitTurn()
        }
    }
}
