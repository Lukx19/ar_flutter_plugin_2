package com.uhg0.ar_flutter_plugin_2.capture

import org.junit.Assert.assertEquals
import org.junit.Test

class CaptureResourceCountersTest {
    @Test
    fun `reports acquired closed and outstanding images`() {
        val counters = CaptureResourceCounters()
        repeat(3) { counters.onImageAcquired() }
        repeat(2) { counters.onImageClosed() }

        assertEquals(
            mapOf(
                "imagesAcquired" to 3L,
                "imagesClosed" to 2L,
                "outstandingImages" to 1L,
            ),
            counters.snapshot(),
        )
    }

    @Test
    fun `outstanding count never becomes negative`() {
        val counters = CaptureResourceCounters()
        counters.onImageClosed()

        assertEquals(0L, counters.snapshot()["outstandingImages"])
    }
}
