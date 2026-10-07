package com.uhg0.ar_flutter_plugin_2.sceneview

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class BoundedReplyFenceTest {
    @Test fun `held native resume fences promptly and rolls back only after return before successor or disposal`() {
        for (lateFailure in listOf(false, true)) for (dispose in listOf(false, true)) {
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            val finished = CountDownLatch(1)
            val main = java.util.concurrent.ConcurrentLinkedQueue<() -> Unit>()
            val deadlines = mutableListOf<() -> Unit>()
            val effects = mutableListOf<String>()
            val replies = mutableListOf<String>()
            val workers = mutableListOf<Thread>()
            val coordinator = BoundedOperationCoordinator<String>(
                launchOperation = { work -> workers += Thread(work).apply { start() } },
                scheduleDeadline = { _, deadline -> deadlines += deadline },
                dispatchTerminal = { main.add(it) },
            )
            fun runMain() { while (true) (main.poll() ?: break).invoke() }
            coordinator.begin(1, { replies += it; if (it != "success") effects += "fence" },
                "superseded", "timeout", "failed", "success",
                operation = {
                    entered.countDown()
                    check(release.await(2, TimeUnit.SECONDS))
                    if (lateFailure) error("partial native resume failure")
                },
                rollbackLateSuccess = { effects += "pause"; finished.countDown() },
                rollbackLateFailure = { effects += "pause"; finished.countDown() },
            )
            try {
                assertTrue(entered.await(1, TimeUnit.SECONDS))
                if (dispose) {
                    deadlines.single().invoke(); runMain()
                    coordinator.dispose("cancelled") { effects += "dispose" }
                    assertEquals(listOf("timeout"), replies)
                } else {
                    // Supersession settles the reply synchronously on Main.
                    coordinator.begin(1, replies::add, "superseded", "timeout", "failed", "success",
                        operation = { effects += "successor" }, rollbackLateSuccess = {})
                    assertEquals(listOf("superseded"), replies)
                }
                assertEquals(listOf("fence"), effects)
                assertEquals(1, workers.size)
                coordinator.afterNativeDrain { drained ->
                    assertTrue(drained); effects += "prepared-pause"
                }
                release.countDown(); workers.first().join(1000)
                assertFalse(workers.first().isAlive)
                runMain()
                assertTrue(finished.await(1, TimeUnit.SECONDS))
                if (dispose) assertEquals(listOf("fence", "pause", "prepared-pause", "dispose"), effects)
                else {
                    workers.last().join(1000); runMain()
                    assertEquals(listOf("fence", "pause", "prepared-pause", "successor"), effects)
                }
                runMain(); assertEquals(1, effects.count { it == "pause" })
            } finally { release.countDown(); workers.forEach { it.join(1000) } }
        }
    }

    @Test fun `prepared pause coalesces superseded completion and retains owner on rollback failure`() {
        val workers = mutableListOf<() -> Unit>()
        val events = mutableListOf<String>()
        val coordinator = BoundedOperationCoordinator<String>(workers::add, { _, _ -> }, { it() })
        coordinator.begin(1, {}, "superseded", "timeout", "failed", "success",
            operation = { error("partial resume") }, rollbackLateSuccess = {},
            rollbackLateFailure = { error("pause failed") })
        coordinator.afterNativeDrain { events += "old-$it" }
        coordinator.afterNativeDrain { events += "new-$it" }
        assertEquals(listOf("old-false"), events)
        coordinator.dispose("cancelled") { events += "dispose" }
        assertTrue(runCatching { workers.single().invoke() }.isFailure)
        assertEquals(listOf("old-false"), events)
    }

    @Test fun `completion rechecks Main ownership and rolls back accepted native failures`() {
        for (nativeFailure in listOf(false, true)) {
            val workers = mutableListOf<() -> Unit>()
            val main = mutableListOf<() -> Unit>()
            val events = mutableListOf<String>()
            var current = true
            val coordinator = BoundedOperationCoordinator<String>(workers::add, { _, _ -> }, main::add)
            coordinator.begin(1, { events += it }, "superseded", "timeout", "failed", "success",
                operation = { if (nativeFailure) error("partial resume") },
                rollbackLateSuccess = { events += "pause" },
                rollbackLateFailure = { events += "pause" },
                acceptSuccess = { current })
            workers.single().invoke()
            current = false
            main.single().invoke()
            coordinator.dispose("cancelled") { events += "dispose" }
            assertEquals(listOf("failed", "pause", "dispose"), events)
        }
    }

    @Test fun `production coordinator serializes late resumes and rolls each one back`() {
        val operations = mutableListOf<() -> Unit>()
        val deadlines = mutableListOf<() -> Unit>()
        val terminals = mutableListOf<String>()
        val effects = mutableListOf<String>()
        val coordinator = BoundedOperationCoordinator<String>(
            launchOperation = operations::add,
            scheduleDeadline = { _, deadline -> deadlines += deadline },
            dispatchTerminal = { terminal -> terminal() },
        )
        coordinator.begin(
            timeoutMillis = 1,
            next = terminals::add,
            superseded = "superseded",
            timedOut = "timeout",
            failed = "failed",
            succeeded = "success",
            operation = { effects += "resume-1" },
            rollbackLateSuccess = { effects += "pause-1" },
        )
        deadlines.single().invoke()
        coordinator.begin(
            timeoutMillis = 1,
            next = terminals::add,
            superseded = "superseded",
            timedOut = "timeout",
            failed = "failed",
            succeeded = "success",
            operation = { effects += "resume-2" },
            rollbackLateSuccess = { effects += "pause-2" },
        )
        assertEquals(1, operations.size)
        operations.single().invoke()
        assertEquals(listOf("resume-1", "pause-1"), effects)
        assertEquals(2, operations.size)
        operations.last().invoke()
        assertEquals(listOf("resume-1", "pause-1", "resume-2"), effects)
        assertEquals(listOf("timeout", "success"), terminals)
    }

    @Test fun `production coordinator drains late resume rollback before host disposal`() {
        val operations = mutableListOf<() -> Unit>()
        val deadlines = mutableListOf<() -> Unit>()
        val effects = mutableListOf<String>()
        val coordinator = BoundedOperationCoordinator<String>(
            launchOperation = operations::add,
            scheduleDeadline = { _, deadline -> deadlines += deadline },
            dispatchTerminal = { terminal -> terminal() },
        )
        coordinator.begin(
            timeoutMillis = 1,
            next = {},
            superseded = "superseded",
            timedOut = "timeout",
            failed = "failed",
            succeeded = "success",
            operation = { effects += "resume" },
            rollbackLateSuccess = { effects += "pause" },
        )
        deadlines.single().invoke()
        coordinator.dispose("cancelled") { effects += "dispose" }
        assertEquals(emptyList<String>(), effects)
        operations.single().invoke()
        assertEquals(listOf("resume", "pause", "dispose"), effects)
    }

    @Test fun `pause fences a stalled resume before its success and serializes a later resume`() {
        val operations = mutableListOf<() -> Unit>()
        val deadlines = mutableListOf<() -> Unit>()
        val terminals = mutableListOf<String>()
        val effects = mutableListOf<String>()
        val coordinator = BoundedOperationCoordinator<String>(
            launchOperation = operations::add,
            scheduleDeadline = { _, deadline -> deadlines += deadline },
            dispatchTerminal = { terminal -> terminal() },
        )
        coordinator.begin(
            timeoutMillis = 1,
            next = terminals::add,
            superseded = "superseded",
            timedOut = "timeout",
            failed = "failed",
            succeeded = "success",
            operation = { effects += "resume-1" },
            rollbackLateSuccess = { effects += "rollback-1" },
        )
        coordinator.invalidate("superseded")
        // Admission is fenced now; physical pause follows native return.
        coordinator.afterNativeDrain { drained -> assertTrue(drained); effects += "pause" }
        coordinator.begin(
            timeoutMillis = 1,
            next = terminals::add,
            superseded = "superseded",
            timedOut = "timeout",
            failed = "failed",
            succeeded = "success",
            operation = { effects += "resume-2" },
            rollbackLateSuccess = { effects += "rollback-2" },
        )
        assertEquals(1, operations.size)
        operations.single().invoke()
        assertEquals(listOf("resume-1", "rollback-1", "pause"), effects)
        assertEquals(2, operations.size)
        operations.last().invoke()
        assertEquals(
            listOf("resume-1", "rollback-1", "pause", "resume-2"),
            effects,
        )
        assertEquals(listOf("superseded", "success"), terminals)
    }
    @Test fun `success replies exactly once`() {
        val values = mutableListOf<String>(); val fence = BoundedReplyFence<String>()
        val token = fence.begin(values::add, "superseded")
        assertTrue(fence.settle(token, "success")); assertFalse(fence.settle(token, "late"))
        assertEquals(listOf("success"), values)
    }
    @Test fun `timeout and failure are terminal replies`() {
        val values = mutableListOf<String>(); val fence = BoundedReplyFence<String>()
        assertTrue(fence.settle(fence.begin(values::add, "superseded"), "timeout"))
        assertTrue(fence.settle(fence.begin(values::add, "superseded"), "failure"))
        assertEquals(listOf("timeout", "failure"), values)
    }
    @Test fun `supersede and disposal fence late completion`() {
        val values = mutableListOf<String>(); val fence = BoundedReplyFence<String>()
        val old = fence.begin({ values += "old:$it" }, "superseded")
        val current = fence.begin({ values += "new:$it" }, "superseded")
        assertFalse(fence.settle(old, "success")); fence.dispose("cancelled")
        assertFalse(fence.settle(current, "late"))
        assertEquals(listOf("old:superseded", "new:cancelled"), values)
    }

    @Test fun `begin after dispose rejects without retaining a reply`() {
        val operations = mutableListOf<() -> Unit>()
        val deadlines = mutableListOf<() -> Unit>()
        val terminals = mutableListOf<String>()
        val coordinator = BoundedOperationCoordinator<String>(
            launchOperation = operations::add,
            scheduleDeadline = { _, deadline -> deadlines += deadline },
            dispatchTerminal = { terminal -> terminal() },
        )

        coordinator.dispose("cancelled") {}
        try {
            coordinator.begin(
                timeoutMillis = 1,
                next = terminals::add,
                superseded = "superseded",
                timedOut = "timeout",
                failed = "failed",
                succeeded = "success",
                operation = {},
                rollbackLateSuccess = {},
            )
            fail("begin after dispose must reject")
        } catch (_: IllegalStateException) {
            // Expected: disposal wins before any reply is registered.
        }
        coordinator.dispose("cancelled-again") {}

        assertTrue(operations.isEmpty())
        assertTrue(deadlines.isEmpty())
        assertTrue(terminals.isEmpty())
    }

    @Test fun `begin racing disposal has at most one terminal reply`() {
        repeat(100) {
            val terminals = mutableListOf<String>()
            val ready = CountDownLatch(2)
            val start = CountDownLatch(1)
            val done = CountDownLatch(2)
            val coordinator = BoundedOperationCoordinator<String>(
                launchOperation = { _ -> },
                scheduleDeadline = { _, _ -> },
                dispatchTerminal = { terminal -> terminal() },
            )
            var rejected = false
            val beginThread = Thread {
                ready.countDown()
                start.await()
                try {
                    coordinator.begin(
                        timeoutMillis = 1,
                        next = { terminal -> synchronized(terminals) { terminals += terminal } },
                        superseded = "superseded",
                        timedOut = "timeout",
                        failed = "failed",
                        succeeded = "success",
                        operation = {},
                        rollbackLateSuccess = {},
                    )
                } catch (_: IllegalStateException) {
                    rejected = true
                } finally {
                    done.countDown()
                }
            }
            val disposeThread = Thread {
                ready.countDown()
                start.await()
                coordinator.dispose("cancelled") {}
                done.countDown()
            }
            beginThread.start()
            disposeThread.start()
            assertTrue(ready.await(1, TimeUnit.SECONDS))
            start.countDown()
            assertTrue(done.await(1, TimeUnit.SECONDS))
            coordinator.dispose("cancelled-again") {}

            synchronized(terminals) {
                if (rejected) assertTrue(terminals.isEmpty())
                else assertEquals(listOf("cancelled"), terminals)
            }
        }
    }
    @Test fun `timed out resume that later fails rolls back without reopening settled owners`() {
        val operations = mutableListOf<() -> Unit>(); val deadlines = mutableListOf<() -> Unit>()
        val replies = mutableListOf<String>(); var cameraPaused = false; var mapperPaused = false
        val coordinator = BoundedOperationCoordinator<String>(operations::add,
            { _, deadline -> deadlines += deadline }, { it() })
        coordinator.begin(1, replies::add, "superseded", "timeout", "failed", "success",
            operation = { cameraPaused = false; mapperPaused = false; error("late native resume failure") },
            rollbackLateSuccess = {},
            rollbackLateFailure = { cameraPaused = true; mapperPaused = true },
        )
        deadlines.single()(); assertEquals(listOf("timeout"), replies)
        operations.single()()
        assertTrue(cameraPaused); assertTrue(mapperPaused); assertEquals(listOf("timeout"), replies)
    }

}
