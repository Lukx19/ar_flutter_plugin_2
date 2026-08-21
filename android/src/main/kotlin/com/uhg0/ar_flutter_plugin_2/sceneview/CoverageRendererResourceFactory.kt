package com.uhg0.ar_flutter_plugin_2.sceneview

/**
 * Owns the one active mesh resource generation. Its small interface makes the
 * clear-before-replace rule identical for Compose and the JVM fake backend.
 */
internal class CoverageRendererResourceFactory {
    private var active: Any? = null
    private var releaseActive: ((Any) -> Unit)? = null

    @Suppress("UNCHECKED_CAST")
    fun <T : Any> replace(create: () -> T, release: (T) -> Unit): T {
        val prior = active
        val priorRelease = releaseActive
        active = null
        releaseActive = null
        if (prior != null && priorRelease != null) priorRelease(prior)
        return create().also { next ->
            active = next
            releaseActive = { value -> release(value as T) }
        }
    }

    fun clear() {
        val prior = active
        val priorRelease = releaseActive
        active = null
        releaseActive = null
        if (prior != null && priorRelease != null) priorRelease(prior)
    }
}
