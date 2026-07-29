package com.uhg0.ar_flutter_plugin_2.visibilitygrid

/**
 * Commits copied sensor work under the same lock used to activate barriers.
 *
 * The caller may perform expensive frame copying before this gate, but must
 * recheck every admission condition here before publishing the copied work.
 */
internal inline fun admitSensorWork(
    lock: Any,
    isBlocked: () -> Boolean,
    offer: () -> Unit,
): Boolean =
    synchronized(lock) {
        if (isBlocked()) {
            false
        } else {
            offer()
            true
        }
    }
