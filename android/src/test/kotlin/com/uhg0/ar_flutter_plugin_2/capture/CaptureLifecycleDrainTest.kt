package com.uhg0.ar_flutter_plugin_2.capture

import java.util.ArrayDeque
import java.util.concurrent.Executor
import org.junit.Assert.*
import org.junit.Test

class CaptureLifecycleDrainTest {
    private class Queue : Executor {
        val tasks = ArrayDeque<Runnable>()
        override fun execute(command: Runnable) { tasks.addLast(command) }
        fun next() = tasks.removeFirst().run()
    }
    private class Fixture {
        val worker = Queue(); val main = Queue()
        var expired: (() -> Unit)? = null
        var canceled = false
        val drain = CaptureLifecycleDrain(worker, main) { _, callback ->
            expired = callback
            val cancel: () -> Unit = { canceled = true }
            cancel
        }
    }

    @Test fun `pause and camera shutdown preserve Main continuation order without worker waits`() {
        val f = Fixture(); val events = mutableListOf<String>()
        assertTrue(CaptureLifecycleDrain.completePause(Result.success(Unit)) { events += "ARCore paused" }.isSuccess)
        assertTrue(f.drain.shutdown(1000,
            barrier = { events += "camera closed"; events += "images and handlers drained" },
            releaseMainResources = { events += "session destroyed" },
            completion = { assertTrue(it.isSuccess); events += "complete" },
        ))
        f.main.execute { events += "Main responsive" }; f.main.next()
        assertEquals(listOf("ARCore paused", "Main responsive"), events)
        f.worker.next()
        assertFalse(events.contains("session destroyed"))
        f.main.next()
        assertEquals(listOf("ARCore paused", "Main responsive", "camera closed", "images and handlers drained", "session destroyed", "complete"), events)
        assertTrue(f.canceled)
    }

    @Test fun `failed preparation never authorizes ARCore pause or camera shutdown`() {
        var paused = 0
        val failed = Result.failure<Unit>(IllegalStateException("depth still owns Image"))
        val result = CaptureLifecycleDrain.completePause(failed) { paused++ }
        assertTrue(result.isFailure); assertEquals(0, paused)
    }

    @Test fun `deadline reports failure once retains barrier and ignores late successful destruction`() {
        val f = Fixture(); var released = 0; var completions = 0
        assertTrue(f.drain.shutdown(1000, {}, { released++ }) { assertTrue(it.isFailure); completions++ })
        f.expired!!(); f.expired!!(); f.main.next(); f.main.next()
        assertEquals(1, completions); assertEquals(0, released)
        assertFalse(f.drain.run(1000, {}, {}))
        f.worker.next(); f.main.next()
        assertEquals(1, completions); assertEquals(0, released)
        assertTrue(f.drain.run(1000, {}, {}))
    }

    @Test fun `failed background image barrier keeps Main resources and reports exact failure`() {
        val f = Fixture(); var released = 0; var completions = 0
        val failure = IllegalStateException("owned Image outstanding")
        assertTrue(f.drain.shutdown(1000, { throw failure }, { released++ }) {
            assertSame(failure, it.exceptionOrNull()); completions++
        })
        f.worker.next(); f.main.next(); f.expired!!(); f.main.next()
        assertEquals(1, completions); assertEquals(0, released)
    }

    @Test fun `duplicate Main terminal invocation cannot destroy resources twice`() {
        val f = Fixture(); var released = 0; var completions = 0
        assertTrue(f.drain.shutdown(1000, {}, { released++ }) { completions++ })
        f.worker.next()
        val terminal = f.main.tasks.removeFirst(); terminal.run(); terminal.run()
        assertEquals(1, released); assertEquals(1, completions)
    }

    @Test fun `Main resource release error is an honest failed terminal`() {
        val f = Fixture(); var completions = 0
        assertTrue(f.drain.shutdown(1000, {}, { error("GPU teardown") }) { assertTrue(it.isFailure); completions++ })
        f.worker.next(); f.main.next(); assertEquals(1, completions)
    }

    @Test fun `worker enqueue rejection keeps resources and terminates once`() {
        val main = Queue(); var completions = 0; var released = 0
        val drain = CaptureLifecycleDrain(Executor { throw java.util.concurrent.RejectedExecutionException() }, main) { _, _ -> {} }
        assertTrue(drain.shutdown(1000, {}, { released++ }) { assertTrue(it.isFailure); completions++ })
        assertEquals(1, completions); assertEquals(0, released)
    }

    @Test fun `superseded observation resume never invokes camera resume`() {
        var cameraResumed = 0
        assertThrows(IllegalStateException::class.java) {
            CaptureLifecycleDrain.resumeCamera({ false }, { false }, { cameraResumed++ })
        }
        assertEquals(0, cameraResumed)
    }
    @Test fun `pause arriving during camera resume makes its completion fail qualification`() {
        var current = true; var cameras = 0
        assertThrows(IllegalStateException::class.java) {
            CaptureLifecycleDrain.resumeCamera({ true }, { current }, { cameras++; current = false })
        }
        assertEquals(1, cameras)
    }

}
