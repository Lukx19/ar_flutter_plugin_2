package com.uhg0.ar_flutter_plugin_2.visibilitygrid

import java.util.concurrent.Executor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DepthFusionDispatcherTest {
    @Test
    fun `callback offers coalesce and fusion runs only on the executor`() {
        val executor = ControlledExecutor()
        val consumed = mutableListOf<String>()
        val dispatcher =
            DepthFusionDispatcher(executor) { result ->
                consumed += (result as DepthAcquisitionResult.Failure).reason
            }

        assertTrue(dispatcher.offer(DepthAcquisitionResult.Failure("old")))
        assertTrue(dispatcher.offer(DepthAcquisitionResult.Failure("latest")))
        assertTrue(consumed.isEmpty())

        executor.runNext()

        assertEquals(listOf("latest"), consumed)
        dispatcher.close()
        assertFalse(dispatcher.offer(DepthAcquisitionResult.Failure("after-close")))
    }

    @Test
    fun `executor rejection clears pending state and reports backpressure`() {
        val failures = mutableListOf<String>()
        val dispatcher =
            DepthFusionDispatcher(
                executor = Executor { throw IllegalStateException("rejected") },
                onFailure = { failures += it.message.orEmpty() },
                consume = {},
            )

        assertFalse(dispatcher.offer(DepthAcquisitionResult.Failure("never-consumed")))
        assertEquals(listOf("rejected"), failures)
        dispatcher.close()
        assertFalse(dispatcher.offer(DepthAcquisitionResult.Failure("closed")))
    }

    private class ControlledExecutor : Executor {
        private val work = ArrayDeque<Runnable>()

        override fun execute(command: Runnable) {
            work += command
        }

        fun runNext() {
            work.removeFirst().run()
        }
    }
}
