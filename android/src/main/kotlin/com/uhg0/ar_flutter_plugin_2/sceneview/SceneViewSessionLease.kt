package com.uhg0.ar_flutter_plugin_2.sceneview

/**
 * Serializes ARSceneView composition ownership across Flutter PlatformView
 * generations.
 *
 * Filament and ARCore teardown is performed by Compose effects. A replacement
 * PlatformView can otherwise construct its own engine and AR session while the
 * outgoing composition is still releasing its camera and GL resources. The
 * lease gives the replacement composition ownership only after the outgoing
 * generation has released it.
 */
internal class SceneViewSessionLease {
    private var activeGeneration: Long? = null
    private val queuedGrants = LinkedHashMap<Long, () -> Unit>()

    @Synchronized
    fun request(generation: Long, onGranted: () -> Unit) {
        require(generation > 0) { "SceneView generation must be positive" }
        if (activeGeneration == generation) return
        if (activeGeneration == null) {
            activeGeneration = generation
            onGranted()
            return
        }
        queuedGrants[generation] = onGranted
    }

    /** Releases the active generation, or drops a generation still awaiting it. */
    @Synchronized
    fun releaseOrCancel(generation: Long): Boolean {
        if (activeGeneration != generation) return queuedGrants.remove(generation) != null

        activeGeneration = null
        val next = queuedGrants.entries.firstOrNull() ?: return true
        queuedGrants.remove(next.key)
        activeGeneration = next.key
        next.value.invoke()
        return true
    }

    @Synchronized
    fun activeGenerationForTest(): Long? = activeGeneration

    @Synchronized
    fun queuedGenerationCountForTest(): Int = queuedGrants.size
}
