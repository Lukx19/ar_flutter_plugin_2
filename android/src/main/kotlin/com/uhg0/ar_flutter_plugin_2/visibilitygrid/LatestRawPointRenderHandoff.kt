package com.uhg0.ar_flutter_plugin_2.visibilitygrid

/**
 * Coalesces live ARCore point snapshots before they reach the scene renderer.
 *
 * AR frame callbacks may outpace the main/render thread while the device moves.
 * Keeping only the newest snapshot prevents an obsolete queue of mesh uploads
 * from delaying UI commands such as changing visualization mode.
 */
internal class LatestRawPointRenderHandoff<T : Any> {
    private var pending: T? = null
    private var drainScheduled = false

    @Synchronized
    fun offer(value: T): Boolean {
        pending = value
        if (drainScheduled) return false
        drainScheduled = true
        return true
    }

    @Synchronized
    fun takeLatest(): T? {
        val latest = pending
        pending = null
        drainScheduled = false
        return latest
    }

    @Synchronized
    fun clear() {
        pending = null
    }
}
