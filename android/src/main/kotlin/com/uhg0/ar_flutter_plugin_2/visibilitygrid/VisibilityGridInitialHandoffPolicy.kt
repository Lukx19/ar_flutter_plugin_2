package com.uhg0.ar_flutter_plugin_2.visibilitygrid

/**
 * Assigns the initial renderer mutation to exactly one hand-off owner.
 *
 * Full-delta callers apply during start. Summary-only callers leave the
 * retained delta untouched for the background worker's pull to apply.
 */
internal object VisibilityGridInitialHandoffPolicy {
    fun applyOnStart(summaryOnly: Boolean): Boolean = !summaryOnly
}
