package com.uhg0.ar_flutter_plugin_2.capture

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptureStartupGateTest {
    @Test fun alreadyReadyOwnerStartsExactlyOnce() = runBlocking {
        var starts = 0
        var reads = 0
        val gate = CaptureStartupGate()
        repeat(2) {
            gate.initialize({ reads++; true }, {}, { starts++ })
        }
        assertEquals(1, starts)
        assertEquals(1, reads)
    }

    @Test fun sessionThenTextureReadinessPrecedesResourceInitialization() = runBlocking {
        var elapsedMs = 0L
        var polls = 0
        var starts = 0
        var hasSession = false
        var hasTexture = false
        val gate = CaptureStartupGate(
            nanoTime = { elapsedMs * 1_000_000L },
            suspendDelay = {
                elapsedMs += it
                polls++
                hasSession = polls >= 1
                hasTexture = polls >= 3
            },
        )
        gate.initialize(
            isReady = { hasSession && hasTexture },
            ensureCurrent = {},
            start = { assertEquals(3, polls); starts++ },
        )
        assertEquals(1, starts)
        assertEquals(48L, elapsedMs)
    }

    @Test fun unavailableHostTimesOutWithoutStartingResources() = runBlocking {
        var elapsedMs = 0L
        var starts = 0
        val gate = CaptureStartupGate(
            timeoutMs = 50,
            pollIntervalMs = 16,
            nanoTime = { elapsedMs * 1_000_000L },
            suspendDelay = { elapsedMs += it },
        )
        val error = runCatching { gate.initialize({ false }, {}, { starts++ }) }.exceptionOrNull()
        assertTrue(error is CaptureStartupTimeoutException)
        assertEquals(50L, elapsedMs)
        assertEquals(0, starts)
    }

    @Test fun predecessorSessionCloseDoesNotConsumeTheStartupDeadline() = runBlocking {
        // The previous view's ARCore close holds the scene session for 10 s.
        var elapsedMs = 0L
        var starts = 0
        val gate = CaptureStartupGate(
            timeoutMs = 10_000,
            pollIntervalMs = 16,
            nanoTime = { elapsedMs * 1_000_000L },
            suspendDelay = { elapsedMs += it },
        )
        gate.initialize(
            isReady = { elapsedMs >= 10_500L },
            ensureCurrent = {},
            start = { starts++ },
            waitingForPredecessor = { elapsedMs < 10_000L },
        )
        assertEquals(1, starts)
        assertTrue(elapsedMs in 10_500L..10_520L)
    }

    @Test fun predecessorWaitIsBoundedOnItsOwn() = runBlocking {
        var elapsedMs = 0L
        var starts = 0
        val gate = CaptureStartupGate(
            timeoutMs = 50,
            predecessorTimeoutMs = 1_000,
            pollIntervalMs = 16,
            nanoTime = { elapsedMs * 1_000_000L },
            suspendDelay = { elapsedMs += it },
        )
        val error = runCatching {
            gate.initialize({ true }, {}, { starts++ }, waitingForPredecessor = { true })
        }.exceptionOrNull()
        assertTrue(error is CaptureStartupTimeoutException)
        assertTrue(elapsedMs in 1_000L..1_016L)
        assertEquals(0, starts)
    }

    @Test fun readinessAfterDeadlineCannotStartResources() = runBlocking {
        var elapsedMs = 0L
        var starts = 0
        val gate = CaptureStartupGate(
            timeoutMs = 50,
            nanoTime = { elapsedMs * 1_000_000L },
            suspendDelay = { elapsedMs = 51L },
        )
        val error = runCatching {
            gate.initialize({ elapsedMs > 0L }, {}, { starts++ })
        }.exceptionOrNull()
        assertTrue(error is CaptureStartupTimeoutException)
        assertEquals(0, starts)
    }

    @Test fun earlyOwnerClosureFencesLateReadiness() = runBlocking {
        var closed = false
        var starts = 0
        val closedError = IllegalStateException("owner closed")
        val gate = CaptureStartupGate(suspendDelay = { closed = true })
        val error = runCatching {
            gate.initialize(
                isReady = { closed },
                ensureCurrent = { if (closed) throw closedError },
                start = { starts++ },
            )
        }.exceptionOrNull()
        assertSame(closedError, error)
        assertEquals(0, starts)
    }

    @Test(timeout = 2_000) fun cancellationDuringReadinessWaitCannotStartResources() = runBlocking {
        var starts = 0
        val enteredWait = CompletableDeferred<Unit>()
        val hold = CompletableDeferred<Unit>()
        val gate = CaptureStartupGate(suspendDelay = { enteredWait.complete(Unit); hold.await() })
        val pending = launch { gate.initialize({ false }, {}, { starts++ }) }
        enteredWait.await()
        pending.cancelAndJoin()
        assertTrue(pending.isCancelled)
        assertEquals(0, starts)
    }

    @Test fun unsupportedHostFailsImmediatelyWithoutWaitingOrStarting() = runBlocking {
        var delays = 0
        var starts = 0
        val unsupported = IllegalStateException("shared camera unsupported")
        val gate = CaptureStartupGate(suspendDelay = { delays++ })
        val error = runCatching {
            gate.initialize({ throw unsupported }, {}, { starts++ })
        }.exceptionOrNull()
        assertSame(unsupported, error)
        assertEquals(0, starts)
        assertEquals(0, delays)
    }

    @Test(timeout = 2_000) fun concurrentRequestsShareOneCompletedStartup() = runBlocking {
        var starts = 0
        val enteredStart = CompletableDeferred<Unit>()
        val releaseStart = CompletableDeferred<Unit>()
        val gate = CaptureStartupGate()
        val first = async {
            gate.initialize({ true }, {}, {
                starts++
                enteredStart.complete(Unit)
                releaseStart.await()
            })
        }
        enteredStart.await()
        val second = async { gate.initialize({ true }, {}, { starts++ }) }
        yield()
        assertEquals(1, starts)
        releaseStart.complete(Unit)
        first.await()
        second.await()
        assertEquals(1, starts)
    }

    @Test fun failedStartupCannotAllocateAgainForSameOwner() = runBlocking {
        val failure = IllegalStateException("startup failed")
        val gate = CaptureStartupGate()
        var starts = 0
        repeat(2) {
            val error = runCatching {
                gate.initialize({ true }, {}, { starts++; throw failure })
            }.exceptionOrNull()
            assertSame(failure, error)
        }
        assertEquals(1, starts)
    }
}
