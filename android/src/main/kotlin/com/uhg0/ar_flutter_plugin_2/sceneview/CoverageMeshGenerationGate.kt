package com.uhg0.ar_flutter_plugin_2.sceneview

/**
 * Owns the monotonic resource generation used to reject a late Compose
 * attachment from an outgoing coverage mesh. A generation is accepted only
 * after its NodeLifecycle has attached and registered its frame callback.
 */
internal class CoverageMeshGenerationGate {
    private var latest = 0L

    @Synchronized
    fun reserve(): Long = ++latest

    /**
     * Runs [attach] only while [generation] is still the newest resource.
     * Keeping the check and registration in one transition prevents a late
     * outgoing Compose effect from replacing the active frame binding.
     */
    @Synchronized
    fun attachIfCurrent(generation: Long, attach: () -> Unit): Boolean {
        if (generation != latest) return false
        attach()
        return true
    }
}
