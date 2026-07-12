package com.uhg0.ar_flutter_plugin_2.capture

import java.util.concurrent.atomic.AtomicReference

/** Atomic single-flight owner for one native capture attempt. */
internal class CaptureAttemptOwner<T : Any> {
    private val active = AtomicReference<T?>()

    fun get(): T? = active.get()

    fun acquire(candidate: T): Boolean = active.compareAndSet(null, candidate)

    fun release(candidate: T): Boolean = active.compareAndSet(candidate, null)

    fun clear() = active.set(null)
}
