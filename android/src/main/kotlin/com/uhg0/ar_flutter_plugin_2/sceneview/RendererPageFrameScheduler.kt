package com.uhg0.ar_flutter_plugin_2.sceneview

/** Coalesces a post-fence upload continuation onto one actual Android frame. */
internal class RendererPageFrameScheduler(
    private val postFrame: ((() -> Unit) -> Unit),
) {
    private var generation = 0L
    private var pending = false
    private var requested = 0L
    private var coalesced = 0L
    private var executed = 0L
    private var cancelled = 0L
    private var pagesAcquired = 0L
    private var pagesReleased = 0L
    private var maxPendingTransactions = 0L

    @Synchronized
    fun request(frameWork: () -> Unit) {
        requested++
        if (pending) {
            coalesced++
            return
        }
        pending = true
        pagesAcquired++
        maxPendingTransactions = maxOf(maxPendingTransactions, 1L)
        val requestedGeneration = ++generation
        postFrame {
            val shouldRun = synchronized(this) {
                if (!pending || generation != requestedGeneration) false else {
                    pending = false
                    executed++
                    pagesReleased++
                    true
                }
            }
            if (shouldRun) frameWork()
        }
    }

    @Synchronized
    fun cancel() {
        generation++
        if (pending) {
            cancelled++
            pagesReleased++
        }
        pending = false
    }

    @Synchronized
    fun pressureSnapshot(): RendererPageFramePressureSnapshot =
        RendererPageFramePressureSnapshot(
            requested = requested,
            coalesced = coalesced,
            executed = executed,
            cancelled = cancelled,
            pagesAcquired = pagesAcquired,
            pagesReleased = pagesReleased,
            maxPendingTransactions = maxPendingTransactions,
            pendingTransactions = if (pending) 1 else 0,
            catchUpBursts = 0,
        )
}

internal data class RendererPageFramePressureSnapshot(
    val requested: Long,
    val coalesced: Long,
    val executed: Long,
    val cancelled: Long,
    val pagesAcquired: Long,
    val pagesReleased: Long,
    val maxPendingTransactions: Long,
    val pendingTransactions: Long,
    val catchUpBursts: Long,
)
