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

/** Qualified Camera2 owner: cancellation fences only the exact submitted attempt. */
internal class QualifiedCaptureAttemptOwnerV2<T : Any, Q : Any>(
    private val qualifier: (T) -> Q,
) {
    private val owner = CaptureAttemptOwner<T>()

    fun get(): T? = owner.get()
    fun acquire(candidate: T): Boolean = owner.acquire(candidate)
    fun release(candidate: T): Boolean = owner.release(candidate)

    fun cancel(expected: Q): T? {
        val active = owner.get() ?: return null
        return if (qualifier(active) == expected && owner.release(active)) active else null
    }
}
