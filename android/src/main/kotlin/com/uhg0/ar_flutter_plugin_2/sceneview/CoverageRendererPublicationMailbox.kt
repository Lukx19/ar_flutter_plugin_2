package com.uhg0.ar_flutter_plugin_2.sceneview

/**
 * One pending immutable publication between the semantic owner and renderer.
 * Taking a publication releases the monitor before any owner or page borrow.
 */
internal class CoverageRendererPublicationMailbox<T : Any> {
    data class Pending<T>(
        val value: T,
        val coalesced: Boolean,
        val clearBeforeApply: Boolean,
    )

    private var pending: Pending<T>? = null
    private var closed = false

    @Synchronized
    fun offer(value: T, clearsRenderer: Boolean = false): Boolean {
        if (closed) return false
        val previous = pending
        pending = Pending(
            value = value,
            coalesced = previous != null,
            clearBeforeApply = clearsRenderer || previous?.clearBeforeApply == true,
        )
        return true
    }

    @Synchronized
    fun take(): Pending<T>? = pending.also { pending = null }

    @Synchronized
    fun close() {
        closed = true
        pending = null
    }

    val isClosed: Boolean
        @Synchronized get() = closed

    val hasPending: Boolean
        @Synchronized get() = pending != null
}
