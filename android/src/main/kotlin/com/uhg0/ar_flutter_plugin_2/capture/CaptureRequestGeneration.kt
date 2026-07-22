package com.uhg0.ar_flutter_plugin_2.capture

import java.util.concurrent.atomic.AtomicLong

/** Monotonic ownership token used to reject callbacks from older attempts. */
internal class CaptureRequestGeneration {
    private val sequence = AtomicLong(0L)
    private val active = AtomicLong(0L)

    fun next(): Long = sequence.incrementAndGet().also(active::set)

    fun isCurrent(generation: Long): Boolean = active.get() == generation

    fun clear(generation: Long): Boolean = active.compareAndSet(generation, 0L)
}
