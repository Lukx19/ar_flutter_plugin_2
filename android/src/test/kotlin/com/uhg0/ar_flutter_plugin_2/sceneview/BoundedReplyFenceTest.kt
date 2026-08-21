package com.uhg0.ar_flutter_plugin_2.sceneview

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class BoundedReplyFenceTest {
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
}
