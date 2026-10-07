package com.uhg0.ar_flutter_plugin_2.capture

import org.junit.Assert.*
import org.junit.Test

class CaptureThumbnailLimitsTest {
    @Test fun `maximum JPEG and portrait use bounded decode before thumbnail and larger display`() {
        assertEquals(8, CaptureThumbnailLimits.sampleSize(4128, 3096))
        assertEquals(8, CaptureThumbnailLimits.sampleSize(3096, 4128))
        assertEquals(4, CaptureThumbnailLimits.sampleSize(4128, 3096, 1024))
        assertEquals(1, CaptureThumbnailLimits.sampleSize(320, 240))
        for ((width, height) in listOf(4128 to 3096, 65535 to 65535, Int.MAX_VALUE to 1)) {
            val sample = CaptureThumbnailLimits.sampleSize(width, height)
            assertTrue((width.toLong() + sample - 1) / sample <= 1024)
            assertTrue((height.toLong() + sample - 1) / sample <= 1024)
        }
    }
    @Test fun `invalid dimensions and caller sized decode requests are refused`() {
        assertThrows(IllegalArgumentException::class.java) { CaptureThumbnailLimits.sampleSize(0, 3096) }
        assertThrows(IllegalArgumentException::class.java) { CaptureThumbnailLimits.sampleSize(4128, -1) }
        assertThrows(IllegalArgumentException::class.java) { CaptureThumbnailLimits.sampleSize(4128, 3096, 4128) }
    }
}
