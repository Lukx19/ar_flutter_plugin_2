package com.uhg0.ar_flutter_plugin_2.capture

/** Bounded evidence from actual received JPEG Images, independent of requested size. */
internal class JpegDimensionLedger {
    private var received = 0L
    private var mismatched = 0L
    private var lastWidth: Int? = null
    private var lastHeight: Int? = null

    @Synchronized
    fun record(width: Int, height: Int, expectedWidth: Int, expectedHeight: Int) {
        received++
        if (width != expectedWidth || height != expectedHeight) mismatched++
        lastWidth = width
        lastHeight = height
    }

    @Synchronized
    fun receipt(): Map<String, Any?> = mapOf(
        "receivedCount" to received,
        "mismatchedDimensions" to mismatched,
        "lastWidth" to lastWidth,
        "lastHeight" to lastHeight,
    )
}
