package com.uhg0.ar_flutter_plugin_2.capture

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal class CaptureStartupTimeoutException : IllegalStateException(
    "ARCore capture host did not become ready before the startup deadline",
)

/**
 * Waits without blocking the main/render loop; one mounted owner initializes once.
 * Time spent waiting for the previous view's ARCore session to close (this view
 * has no scene session yet) does not count against [timeoutMs]; that wait is
 * bounded by [predecessorTimeoutMs] on its own.
 */
internal class CaptureStartupGate(
    private val timeoutMs: Long = 10_000L,
    private val predecessorTimeoutMs: Long = 60_000L,
    private val pollIntervalMs: Long = 16L,
    private val nanoTime: () -> Long = System::nanoTime,
    private val suspendDelay: suspend (Long) -> Unit = { delay(it) },
) {
    private val mutex = Mutex()
    private var initialized = false
    private var startupFailure: Throwable? = null

    init {
        require(timeoutMs in 1..60_000L)
        require(predecessorTimeoutMs in 1..300_000L)
        require(pollIntervalMs > 0L)
    }

    suspend fun initialize(
        isReady: () -> Boolean,
        ensureCurrent: () -> Unit,
        start: suspend () -> Unit,
        waitingForPredecessor: () -> Boolean = { false },
    ) = mutex.withLock {
        currentCoroutineContext().ensureActive()
        ensureCurrent()
        if (initialized) return@withLock
        startupFailure?.let { throw it }
        val waitStartNs = nanoTime()
        var startNs = waitStartNs
        val timeoutNs = timeoutMs * 1_000_000L
        val predecessorTimeoutNs = predecessorTimeoutMs * 1_000_000L
        while (true) {
            currentCoroutineContext().ensureActive()
            ensureCurrent()
            if (waitingForPredecessor()) {
                val waitedNs = nanoTime() - waitStartNs
                if (waitedNs < 0L || waitedNs >= predecessorTimeoutNs) {
                    throw CaptureStartupTimeoutException()
                }
                startNs = nanoTime()
                suspendDelay(pollIntervalMs)
                continue
            }
            val elapsedNs = nanoTime() - startNs
            if (elapsedNs < 0L || elapsedNs >= timeoutNs) {
                throw CaptureStartupTimeoutException()
            }
            if (isReady()) break
            val remainingMs = (timeoutNs - elapsedNs + 999_999L) / 1_000_000L
            suspendDelay(minOf(pollIntervalMs, remainingMs))
        }
        currentCoroutineContext().ensureActive()
        ensureCurrent()
        try {
            start()
        } catch (error: Throwable) {
            startupFailure = error
            throw error
        }
        initialized = true
    }
}
