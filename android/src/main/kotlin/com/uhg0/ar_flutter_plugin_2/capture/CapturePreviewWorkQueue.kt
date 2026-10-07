package com.uhg0.ar_flutter_plugin_2.capture

import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** One bitmap/FD owner, at most sixteen queued operations, cancellable before and during I/O. */
internal class CapturePreviewWorkQueue(private val enterWorker: () -> Unit = {}) {
    private val lock = Any()
    private val requests = HashMap<String, Job>()
    private val jobs = HashSet<Job>()
    private var closed = false
    private val executor = ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, ArrayBlockingQueue(16),
        { action -> Thread({ enterWorker(); action.run() }, "capture3d-preview-disk").apply { isDaemon = true } })

    fun submit(id: String?, action: (() -> Boolean) -> Any?, reply: (Result<Any?>) -> Unit) {
        val job = Job(id, action, reply)
        val accepted = synchronized(lock) {
            if (closed || (id != null && requests.containsKey(id))) false
            else {
                if (id != null) requests[id] = job
                jobs.add(job)
                runCatching { executor.execute(job) }.onFailure {
                    if (id != null) requests.remove(id)
                    jobs.remove(job)
                }.isSuccess
            }
        }
        if (!accepted) job.finish(Result.failure(IllegalStateException("Preview worker unavailable or queue full")))
    }

    fun cancel(id: String) {
        val job = synchronized(lock) { requests[id] } ?: return
        job.cancelled.set(true)
        if (executor.remove(job)) job.finish(Result.success(null))
    }

    /** No Main join: running bounded codec work releases its own bitmap/FD in finally. */
    fun close() {
        synchronized(lock) {
            closed = true
            jobs.forEach { it.cancelled.set(true) }
        }
        executor.shutdownNow().filterIsInstance<Job>().forEach { it.finish(Result.success(null)) }
    }

    private inner class Job(val id: String?, val action: (() -> Boolean) -> Any?, val reply: (Result<Any?>) -> Unit) : Runnable {
        val cancelled = AtomicBoolean(false)
        private val replied = AtomicBoolean(false)
        override fun run() {
            val result = runCatching { if (cancelled.get()) null else action(cancelled::get) }
            finish(if (cancelled.get()) Result.success(null) else result)
        }
        fun finish(result: Result<Any?>) {
            if (!replied.compareAndSet(false, true)) return
            synchronized(lock) {
                if (id != null && requests[id] === this) requests.remove(id)
                jobs.remove(this)
            }
            reply(result)
        }
    }
}
