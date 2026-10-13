package com.uhg0.ar_flutter_plugin_2.capture

import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean

/** One owned background barrier; all settlement and continuation runs on Main. */
internal class CaptureLifecycleDrain(
    private val background: Executor,
    private val main: Executor,
    private val deadline: (Long, () -> Unit) -> (() -> Unit),
) {
    private class Attempt(val completion: (Result<Unit>) -> Unit) {
        val settled = AtomicBoolean()
        var cancelDeadline: (() -> Unit)? = null
    }
    private var active: Attempt? = null

    fun shutdown(
        timeoutMillis: Long,
        barrier: () -> Unit,
        releaseMainResources: () -> Unit,
        completion: (Result<Unit>) -> Unit,
    ): Boolean = run(timeoutMillis, barrier) { result ->
        completion(if (result.isSuccess) runCatching(releaseMainResources) else result)
    }

    companion object {
        /** Camera permission is independent of whether V2 observation authority exists. */
        fun resumeCamera(observationResume: () -> Boolean, current: () -> Boolean, resumeCamera: () -> Unit): Boolean {
            val observationReady = observationResume()
            check(current()) { "Resume lifecycle was superseded" }
            resumeCamera()
            check(current()) { "Camera resume completed behind a newer lifecycle fence" }
            return observationReady
        }
        fun completePause(preparation: Result<Unit>, pauseMain: () -> Unit): Result<Unit> =
            if (preparation.isSuccess) runCatching(pauseMain) else preparation
    }

    /**
     * Timeout keeps ownership until the old barrier actually terminates. A
     * barrier that then succeeds runs [afterLateSuccess] on Main (once, after
     * the drain is free again) so its owner can finish a teardown that the
     * timeout reported as failed; without it a late result is ignored.
     */
    fun run(
        timeoutMillis: Long,
        barrier: () -> Unit,
        afterLateSuccess: (() -> Unit)? = null,
        completion: (Result<Unit>) -> Unit,
    ): Boolean {
        check(timeoutMillis > 0)
        if (active != null) return false
        val attempt = Attempt(completion)
        active = attempt
        attempt.cancelDeadline = deadline(timeoutMillis) {
            main.execute {
                if (active === attempt && attempt.settled.compareAndSet(false, true)) {
                    attempt.completion(Result.failure(IllegalStateException("Capture lifecycle drain timed out; owners retained")))
                }
            }
        }
        try {
            background.execute {
                val result = runCatching(barrier)
                main.execute {
                    if (active !== attempt) return@execute
                    active = null
                    attempt.cancelDeadline?.invoke()
                    if (attempt.settled.compareAndSet(false, true)) {
                        attempt.completion(result)
                    } else if (result.isSuccess) {
                        afterLateSuccess?.invoke()
                    }
                }
            }
        } catch (failure: RuntimeException) {
            active = null
            attempt.cancelDeadline?.invoke()
            if (attempt.settled.compareAndSet(false, true)) completion(Result.failure(failure))
        }
        return true
    }
}
