package com.uhg0.ar_flutter_plugin_2.visibilitygrid

/**
 * Invalidates asynchronous visibility-grid work across lifecycle boundaries.
 *
 * A callback may publish only while its token still names the current active
 * epoch. Pause/resume each advance the epoch, so work queued before a pause
 * cannot become current again merely because the session later resumes.
 */
internal class VisibilityGridLifecycleGuard {
    @Volatile private var epoch = 0L
    @Volatile private var paused = false
    @Volatile private var disposed = false

    fun token(): Long = epoch

    fun allows(token: Long): Boolean = !disposed && !paused && token == epoch

    /** Allows a durable snapshot after sensor admission has been paused. */
    fun allowsCheckpoint(token: Long): Boolean = !disposed && token == epoch

    @Synchronized
    fun pause() {
        if (disposed || paused) return
        paused = true
        epoch++
    }

    @Synchronized
    fun resume() {
        if (disposed || !paused) return
        paused = false
        epoch++
    }

    @Synchronized
    fun advance() {
        if (disposed) return
        epoch++
    }

    @Synchronized
    fun dispose() {
        if (disposed) return
        disposed = true
        epoch++
    }
}
