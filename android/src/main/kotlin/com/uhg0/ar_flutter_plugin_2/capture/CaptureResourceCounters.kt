package com.uhg0.ar_flutter_plugin_2.capture

import java.util.concurrent.atomic.AtomicLong

/** Process-lifetime counters used to prove that acquired camera images close. */
internal class CaptureResourceCounters {
    private val imagesAcquired = AtomicLong(0)
    private val imagesClosed = AtomicLong(0)

    fun onImageAcquired() {
        imagesAcquired.incrementAndGet()
    }

    fun onImageClosed() {
        imagesClosed.incrementAndGet()
    }

    fun snapshot(): Map<String, Long> {
        val acquired = imagesAcquired.get()
        val closed = imagesClosed.get()
        return mapOf(
            "imagesAcquired" to acquired,
            "imagesClosed" to closed,
            "outstandingImages" to (acquired - closed).coerceAtLeast(0),
        )
    }
}
