package com.uhg0.ar_flutter_plugin_2.visibilitygrid

import java.util.ArrayDeque
import java.util.concurrent.Executor
import org.junit.Assert.assertEquals
import org.junit.Test

class FairExecutorDrainDispatcherTest {
    @Test
    fun `control work queued during a sensor turn runs before the next turn`() {
        val executor = ControlledExecutor()
        val order = mutableListOf<String>()
        var sensorTurns = 0
        lateinit var dispatcher: FairExecutorDrainDispatcher
        dispatcher =
            FairExecutorDrainDispatcher(
                executor = executor,
                drainOne = {
                    sensorTurns++
                    order += "sensor-$sensorTurns"
                    if (sensorTurns == 1) {
                        executor.execute { order += "checkpoint" }
                        dispatcher.request()
                    }
                },
            )

        dispatcher.request()
        executor.runNext()
        executor.runNext()
        executor.runNext()

        assertEquals(listOf("sensor-1", "checkpoint", "sensor-2"), order)
    }

    @Test
    fun `requests coalesce while a turn is already scheduled`() {
        val executor = ControlledExecutor()
        var sensorTurns = 0
        val dispatcher =
            FairExecutorDrainDispatcher(
                executor = executor,
                drainOne = { sensorTurns++ },
            )

        dispatcher.request()
        dispatcher.request()
        dispatcher.request()

        assertEquals(1, executor.pendingCount)
        executor.runNext()
        assertEquals(1, sensorTurns)
        assertEquals(0, executor.pendingCount)
    }

    @Test(expected = AssertionError::class)
    fun `fatal drain errors propagate to the executor`() {
        val executor = ControlledExecutor()
        val dispatcher =
            FairExecutorDrainDispatcher(
                executor = executor,
                drainOne = { throw AssertionError("fatal") },
            )

        dispatcher.request()
        executor.runNext()
    }

    private class ControlledExecutor : Executor {
        private val pending = ArrayDeque<Runnable>()

        val pendingCount: Int
            get() = pending.size

        override fun execute(command: Runnable) {
            pending.addLast(command)
        }

        fun runNext() {
            pending.removeFirst().run()
        }
    }
}
