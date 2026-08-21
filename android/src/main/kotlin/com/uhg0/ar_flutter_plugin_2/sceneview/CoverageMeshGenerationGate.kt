package com.uhg0.ar_flutter_plugin_2.sceneview

/**
 * Owns the monotonic resource generation used to reject a late Compose
 * attachment from an outgoing coverage mesh. A generation is accepted only
 * after its NodeLifecycle has attached and registered its frame callback.
 */
internal class CoverageMeshGenerationGate {
    private var latest = 0L

    fun reserve(): Long = ++latest

    fun acceptsAttached(generation: Long): Boolean = generation == latest
}
