package com.uhg0.ar_flutter_plugin_2.capture

internal class SharedCameraRestartGate(
    private val cooldownMs: Long,
) {
    private var shutdownGeneration = 0L
    private val activeShutdowns = mutableSetOf<Long>()
    private var restartAllowedAtMs = 0L

    @Synchronized
    fun markShutdownStarted(): Long {
        shutdownGeneration += 1L
        activeShutdowns += shutdownGeneration
        return shutdownGeneration
    }

    @Synchronized
    fun markShutdownCompleted(
        generation: Long,
        nowMs: Long,
    ) {
        if (!activeShutdowns.remove(generation) || activeShutdowns.isNotEmpty()) return
        restartAllowedAtMs = maxOf(restartAllowedAtMs, nowMs + cooldownMs)
    }

    @Synchronized
    fun restartDelayMs(
        nowMs: Long,
        completionPollMs: Long,
    ): Long =
        if (activeShutdowns.isNotEmpty()) {
            completionPollMs
        } else {
            (restartAllowedAtMs - nowMs).coerceAtLeast(0L)
        }
}

internal object SharedCameraCallbackGuard {
    inline fun run(
        onFailure: (Exception) -> Unit,
        callback: () -> Unit,
    ) {
        try {
            callback()
        } catch (error: Exception) {
            onFailure(error)
        }
    }
}

internal class SharedCameraSessionCallbackFence {
    private var generation = 0L
    private var draining = false

    @Synchronized
    fun open(): Long {
        generation += 1L
        draining = false
        return generation
    }

    @Synchronized
    fun beginShutdown(expectedGeneration: Long): Boolean {
        if (generation != expectedGeneration || draining) return false
        draining = true
        return true
    }

    @Synchronized
    fun finishShutdown(expectedGeneration: Long): Boolean {
        if (generation != expectedGeneration || !draining) return false
        generation += 1L
        draining = false
        return true
    }

    @Synchronized
    fun runActive(expectedGeneration: Long, callback: () -> Unit): Boolean {
        if (generation != expectedGeneration || draining) return false
        callback()
        return true
    }

    @Synchronized
    fun runTerminal(expectedGeneration: Long, callback: () -> Unit): Boolean {
        if (generation != expectedGeneration) return false
        callback()
        return true
    }
}

internal enum class SharedCameraStartupFailureReason {
    SESSION_CONFIGURATION,
}

internal class SharedCameraStartupException(
    val reason: SharedCameraStartupFailureReason,
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

internal object SharedCameraStartupRetryPolicy {
    fun shouldRetry(error: Throwable): Boolean =
        generateSequence(error) { it.cause }
            .filterIsInstance<SharedCameraStartupException>()
            .any { failure ->
                failure.reason == SharedCameraStartupFailureReason.SESSION_CONFIGURATION
            }
}
