package com.uhg0.ar_flutter_plugin_2.sceneview

/** Coalesces a post-fence upload continuation onto one actual Android frame. */
internal class RendererPageFrameScheduler(
    private val postFrame: ((() -> Unit) -> Unit),
) {
    private var generation = 0L
    private var pending = false

    @Synchronized
    fun request(frameWork: () -> Unit) {
        if (pending) return
        pending = true
        val requestedGeneration = ++generation
        postFrame {
            val shouldRun = synchronized(this) {
                if (!pending || generation != requestedGeneration) false else {
                    pending = false
                    true
                }
            }
            if (shouldRun) frameWork()
        }
    }

    @Synchronized
    fun cancel() {
        generation++
        pending = false
    }
}
