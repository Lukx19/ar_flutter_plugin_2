package com.uhg0.ar_flutter_plugin_2.visibilitygrid

/**
 * Owns renderer mount identity independently from the surrounding AR view.
 *
 * Compose effects for an outgoing mesh may arrive after a replacement has
 * been requested. A boolean callback cannot distinguish that stale event from
 * the replacement's own mount, so each requested renderer configuration gets
 * a monotonic generation.
 */
internal class VisibilityGridRendererLifecycle {
    private var requestedGeneration = 0L
    private var mountedGeneration: Long? = null

    @Synchronized
    fun requestReplacement(): Long = ++requestedGeneration

    @Synchronized
    fun requestedGeneration(): Long = requestedGeneration

    @Synchronized
    fun mountedGeneration(): Long? = mountedGeneration

    /** Returns false when an outgoing Compose effect belongs to an old mesh. */
    @Synchronized
    fun markMounted(generation: Long): Boolean {
        if (generation != requestedGeneration) return false
        mountedGeneration = generation
        return true
    }

    /** Returns false when an outgoing Compose effect cannot own the mesh. */
    @Synchronized
    fun markUnmounted(generation: Long): Boolean {
        if (mountedGeneration != generation) return false
        mountedGeneration = null
        return true
    }

    @Synchronized
    fun clear() {
        mountedGeneration = null
    }
}
