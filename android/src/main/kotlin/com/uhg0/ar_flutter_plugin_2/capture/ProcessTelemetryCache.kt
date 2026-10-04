package com.uhg0.ar_flutter_plugin_2.capture

import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ExecutorService
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** Diagnostic process values only. Capture resource/scratch and safety state stay live. */
internal data class ProcessTelemetryValues(
    val processPssBytes: Long?,
    val openFileDescriptors: Int?,
    val threadCount: Int?,
)

/** Immutable detached view: held replies never change when a refresh finishes. */
internal data class ProcessTelemetrySnapshot(
    val values: ProcessTelemetryValues?,
    val sampledAtElapsedRealtimeMs: Long?,
    val ageMs: Long?,
    val refreshPending: Boolean,
    val refreshFailed: Boolean,
    val closed: Boolean,
) {
    fun toMap(): Map<String, Any?> = mapOf(
        "processPssBytes" to values?.processPssBytes,
        "openFileDescriptors" to values?.openFileDescriptors,
        "threadCount" to values?.threadCount,
        "processTelemetrySampledAtElapsedRealtimeMs" to sampledAtElapsedRealtimeMs,
        "processTelemetryAgeMs" to ageMs,
        "processTelemetryAvailable" to (values != null),
        "processTelemetryRefreshPending" to refreshPending,
        "processTelemetryRefreshFailed" to refreshFailed,
        "processTelemetryClosed" to closed,
        "processTelemetryRefreshIntervalMs" to ProcessTelemetryCache.RefreshIntervalMs,
        "processTelemetryMaxAgeMs" to ProcessTelemetryCache.MaxAgeMs,
        "processTelemetryWorkerCapacity" to 1,
        "processTelemetryQueuedRefreshCapacity" to 1,
        "processTelemetryCachedSampleCapacity" to 1,
    )
}

/**
 * Demand-refreshed diagnostic cache. No timer and no callbacks to the session.
 * One outstanding task (queued OR running), one immutable sample, one worker.
 * Collection never holds [lock]; callers only read scalars and enqueue once.
 */
internal class ProcessTelemetryCache(
    private val clockMs: () -> Long,
    private val collect: () -> ProcessTelemetryValues,
    private val executor: ExecutorService = newWorker(),
) : AutoCloseable {
    companion object {
        const val RefreshIntervalMs = 5_000L
        const val MaxAgeMs = 10_000L

        private fun newWorker(): ExecutorService = ThreadPoolExecutor(
            1, 1, 0L, TimeUnit.MILLISECONDS, ArrayBlockingQueue<Runnable>(1),
            { task -> Thread(task, "capture-process-telemetry").apply { isDaemon = true } },
            ThreadPoolExecutor.AbortPolicy(),
        )
    }

    private data class Sample(val values: ProcessTelemetryValues, val startedAtMs: Long)
    private val lock = Any()
    private var sample: Sample? = null
    private var lastAttemptMs: Long? = null
    private var pending = false
    private var failed = false
    private var closed = false
    // Reused runnable, and pending prevents queue accumulation.
    private val refresh = Runnable {
        val start = synchronized(lock) {
            if (closed) return@Runnable
            clockMs()
        }
        var result: ProcessTelemetryValues? = null
        try {
            result = collect()
        } catch (error: Exception) {
            if (error is InterruptedException) Thread.currentThread().interrupt()
        } finally {
            synchronized(lock) {
                if (!closed) {
                    result?.let { sample = Sample(it, start) }
                    failed = result == null
                    pending = false
                }
            }
        }
    }

    fun isRefreshPending(): Boolean = synchronized(lock) { pending }

    fun snapshot(): ProcessTelemetrySnapshot = synchronized(lock) {
        val now = clockMs()
        val previousAttempt = lastAttemptMs
        if (!closed && !pending && (previousAttempt == null || now < previousAttempt ||
                now - previousAttempt >= RefreshIntervalMs)) {
            pending = true
            lastAttemptMs = now
            try {
                executor.execute(refresh)
            } catch (_: RuntimeException) {
                // Rejected/shutdown owners cannot leave a permanently pending refresh.
                pending = false
                failed = true
            }
        }
        val current = sample
        val age = current?.let { (now - it.startedAtMs).takeIf { value -> value >= 0L } }
        val usable = !closed && age != null && age <= MaxAgeMs
        ProcessTelemetrySnapshot(
            values = if (usable) current.values else null,
            sampledAtElapsedRealtimeMs = current?.startedAtMs,
            ageMs = age,
            refreshPending = pending,
            refreshFailed = failed,
            closed = closed,
        )
    }

    override fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
            pending = false
            sample = null
        }
        // Interrupt best effort; never wait on capture/main. Late work cannot publish.
        executor.shutdownNow()
    }
}
