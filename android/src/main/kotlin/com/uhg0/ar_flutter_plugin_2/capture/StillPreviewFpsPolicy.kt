package com.uhg0.ar_flutter_plugin_2.capture

/** Changes only the still AE range, using the range actually on the repeating builder. */
internal object StillPreviewFpsPolicy {
    fun <R : Any> apply(
        enabled: Boolean,
        repeatingRange: () -> R?,
        advertisedRanges: () -> Collection<R>?,
        setStillRange: (R) -> Unit,
    ): Boolean {
        if (!enabled) return false
        val actual = repeatingRange()
            ?: throw IllegalStateException("Still FPS match requires an actual repeating AE range")
        val supported = advertisedRanges()
            ?: throw IllegalStateException("Still FPS match requires advertised AE ranges")
        check(actual in supported) { "Actual repeating AE range is not advertised; no fallback" }
        setStillRange(actual)
        return true
    }
}
