package com.uhg0.ar_flutter_plugin_2.visibilitygrid

import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** One image can be processed while the frame callback continues. */
internal class BoundedDepthObservationProcessor<T : AutoCloseable, R>(
    private val process: (T) -> R,
    private val publish: (R, Long) -> Unit,
    private val onFailure: (Throwable) -> Unit = {},
    private val executor: ExecutorService = Executors.newSingleThreadExecutor { task ->
        Thread(task, "capture3d-depth-observation").apply { isDaemon = true }
    },
) : AutoCloseable {
    private val lock = Any()
    private var busy = false
    private var closed = false

    /** Takes ownership of [frame] on either acceptance or refusal. */
    fun offer(frame: T, callbackCopyNs: Long): Boolean {
        require(callbackCopyNs >= 0)
        val accepted = synchronized(lock) {
            if (closed || busy) false else {
                busy = true
                true
            }
        }
        if (!accepted) {
            release(frame)
            return false
        }
        return try {
            executor.execute {
                try {
                    publish(process(frame), callbackCopyNs)
                } catch (error: Throwable) {
                    onFailure(error)
                } finally {
                    release(frame)
                    synchronized(lock) { busy = false }
                }
            }
            true
        } catch (error: RuntimeException) {
            release(frame)
            synchronized(lock) { busy = false }
            onFailure(error)
            false
        }
    }

    fun awaitIdle(timeoutMillis: Long): Boolean {
        require(timeoutMillis >= 0)
        return try {
            executor.submit {}.get(timeoutMillis, TimeUnit.MILLISECONDS)
            true
        } catch (_: RuntimeException) {
            false
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        } catch (_: java.util.concurrent.TimeoutException) {
            false
        }
    }

    private fun release(frame: T) {
        try {
            frame.close()
        } catch (error: Throwable) {
            onFailure(error)
        }
    }

    override fun close() {
        synchronized(lock) { closed = true }
        executor.shutdown()
        try {
            if (!executor.awaitTermination(1, TimeUnit.SECONDS)) executor.shutdownNow()
        } catch (_: InterruptedException) {
            executor.shutdownNow()
            Thread.currentThread().interrupt()
        }
    }
}
