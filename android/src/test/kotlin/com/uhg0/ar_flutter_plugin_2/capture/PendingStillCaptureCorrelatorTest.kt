package com.uhg0.ar_flutter_plugin_2.capture

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PendingStillCaptureCorrelatorTest {
    @Test
    fun `matches when image arrives before capture result`() {
        val correlator = PendingStillCaptureCorrelator()
        val bytes = byteArrayOf(1, 2, 3)

        val initial =
            correlator.onImageAvailable(
                sensorTimestampNs = 100L,
                width = 640,
                height = 480,
                bytes = bytes,
            )
        val correlated =
            correlator.onCaptureResult(
                frameNumber = 7L,
                sensorTimestampNs = 100L,
                exposureTimeNs = 33L,
                rollingShutterSkewNs = 4L,
            )

        assertNull(initial)
        requireNotNull(correlated)
        assertEquals(7L, correlated.result.frameNumber)
        assertEquals(100L, correlated.image.sensorTimestampNs)
        assertEquals(640, correlated.image.width)
        assertEquals(480, correlated.image.height)
        assertArrayEquals(bytes, correlated.image.bytes)
        assertEquals(0, correlator.snapshot().pendingImages)
        assertEquals(0, correlator.snapshot().pendingResults)
    }

    @Test
    fun `matches when capture result arrives before image`() {
        val correlator = PendingStillCaptureCorrelator()
        val initial =
            correlator.onCaptureResult(
                frameNumber = 11L,
                sensorTimestampNs = 300L,
                exposureTimeNs = 50L,
                rollingShutterSkewNs = 5L,
            )
        val correlated =
            correlator.onImageAvailable(
                sensorTimestampNs = 300L,
                width = 800,
                height = 600,
                bytes = byteArrayOf(9, 8),
            )

        assertNull(initial)
        requireNotNull(correlated)
        assertEquals(11L, correlated.result.frameNumber)
        assertEquals(300L, correlated.result.sensorTimestampNs)
        assertEquals(0, correlator.snapshot().pendingImages)
        assertEquals(0, correlator.snapshot().pendingResults)
    }

    @Test
    fun `keeps mismatched timestamp callbacks pending`() {
        val correlator = PendingStillCaptureCorrelator()

        correlator.onImageAvailable(
            sensorTimestampNs = 100L,
            width = 1,
            height = 1,
            bytes = byteArrayOf(1),
        )
        val correlated =
            correlator.onCaptureResult(
                frameNumber = 1L,
                sensorTimestampNs = 101L,
                exposureTimeNs = 0L,
                rollingShutterSkewNs = 0L,
            )

        assertNull(correlated)
        assertEquals(1, correlator.snapshot().pendingImages)
        assertEquals(1, correlator.snapshot().pendingResults)
    }

    @Test
    fun `duplicate callbacks do not create duplicate matches`() {
        val correlator = PendingStillCaptureCorrelator()

        correlator.onImageAvailable(
            sensorTimestampNs = 200L,
            width = 1,
            height = 1,
            bytes = byteArrayOf(1),
        )
        val first =
            correlator.onCaptureResult(
                frameNumber = 2L,
                sensorTimestampNs = 200L,
                exposureTimeNs = 0L,
                rollingShutterSkewNs = 0L,
            )
        val duplicateResult =
            correlator.onCaptureResult(
                frameNumber = 2L,
                sensorTimestampNs = 200L,
                exposureTimeNs = 0L,
                rollingShutterSkewNs = 0L,
            )
        val duplicateImage =
            correlator.onImageAvailable(
                sensorTimestampNs = 200L,
                width = 1,
                height = 1,
                bytes = byteArrayOf(1),
            )

        requireNotNull(first)
        assertNull(duplicateResult)
        assertNull(duplicateImage)
        assertEquals(0, correlator.snapshot().pendingImages)
        assertEquals(0, correlator.snapshot().pendingResults)
    }

    @Test
    fun `duplicate pending capture result with same frame number is ignored`() {
        val correlator = PendingStillCaptureCorrelator()

        correlator.onCaptureResult(
            frameNumber = 30L,
            sensorTimestampNs = 3000L,
            exposureTimeNs = 0L,
            rollingShutterSkewNs = 0L,
        )
        val duplicate =
            correlator.onCaptureResult(
                frameNumber = 30L,
                sensorTimestampNs = 3000L,
                exposureTimeNs = 1L,
                rollingShutterSkewNs = 1L,
            )

        assertNull(duplicate)
        assertEquals(0, correlator.snapshot().pendingImages)
        assertEquals(1, correlator.snapshot().pendingResults)
    }

    @Test
    fun `cleanup expires stale unmatched callbacks`() {
        var nowMs = 0L
        val correlator =
            PendingStillCaptureCorrelator(
                clockMs = { nowMs },
                timeoutMs = 1000L,
            )

        correlator.onImageAvailable(
            sensorTimestampNs = 500L,
            width = 1,
            height = 1,
            bytes = byteArrayOf(1),
        )
        correlator.onCaptureResult(
            frameNumber = 5L,
            sensorTimestampNs = 600L,
            exposureTimeNs = 0L,
            rollingShutterSkewNs = 0L,
        )

        nowMs = 1001L
        correlator.cleanupExpired()

        assertEquals(0, correlator.snapshot().pendingImages)
        assertEquals(0, correlator.snapshot().pendingResults)
    }

    @Test
    fun `late callback after timeout starts a fresh pending entry`() {
        var nowMs = 0L
        val correlator =
            PendingStillCaptureCorrelator(
                clockMs = { nowMs },
                timeoutMs = 1000L,
            )

        correlator.onCaptureResult(
            frameNumber = 6L,
            sensorTimestampNs = 900L,
            exposureTimeNs = 0L,
            rollingShutterSkewNs = 0L,
        )
        nowMs = 1500L
        correlator.cleanupExpired()
        val correlated =
            correlator.onImageAvailable(
                sensorTimestampNs = 900L,
                width = 2,
                height = 2,
                bytes = byteArrayOf(7),
            )

        assertNull(correlated)
        assertEquals(1, correlator.snapshot().pendingImages)
        assertEquals(0, correlator.snapshot().pendingResults)
    }

    @Test
    fun `oldest pending image is evicted when pending image map exceeds limit`() {
        val correlator = PendingStillCaptureCorrelator(maxPendingEntries = 2)

        correlator.onImageAvailable(
            sensorTimestampNs = 100L,
            width = 1,
            height = 1,
            bytes = byteArrayOf(1),
        )
        correlator.onImageAvailable(
            sensorTimestampNs = 200L,
            width = 1,
            height = 1,
            bytes = byteArrayOf(2),
        )
        correlator.onImageAvailable(
            sensorTimestampNs = 300L,
            width = 1,
            height = 1,
            bytes = byteArrayOf(3),
        )

        val oldestMatch =
            correlator.onCaptureResult(
                frameNumber = 1L,
                sensorTimestampNs = 100L,
                exposureTimeNs = 0L,
                rollingShutterSkewNs = 0L,
            )
        val newestMatch =
            correlator.onCaptureResult(
                frameNumber = 3L,
                sensorTimestampNs = 300L,
                exposureTimeNs = 0L,
                rollingShutterSkewNs = 0L,
            )

        assertNull(oldestMatch)
        requireNotNull(newestMatch)
        assertEquals(1, correlator.snapshot().pendingImages)
        assertEquals(1, correlator.snapshot().pendingResults)
    }

    @Test
    fun `oldest pending result is evicted when pending result map exceeds limit`() {
        val correlator = PendingStillCaptureCorrelator(maxPendingEntries = 2)

        correlator.onCaptureResult(
            frameNumber = 10L,
            sensorTimestampNs = 1000L,
            exposureTimeNs = 0L,
            rollingShutterSkewNs = 0L,
        )
        correlator.onCaptureResult(
            frameNumber = 11L,
            sensorTimestampNs = 1100L,
            exposureTimeNs = 0L,
            rollingShutterSkewNs = 0L,
        )
        correlator.onCaptureResult(
            frameNumber = 12L,
            sensorTimestampNs = 1200L,
            exposureTimeNs = 0L,
            rollingShutterSkewNs = 0L,
        )

        val oldestMatch =
            correlator.onImageAvailable(
                sensorTimestampNs = 1000L,
                width = 1,
                height = 1,
                bytes = byteArrayOf(1),
            )
        val newestMatch =
            correlator.onImageAvailable(
                sensorTimestampNs = 1200L,
                width = 1,
                height = 1,
                bytes = byteArrayOf(3),
            )

        assertNull(oldestMatch)
        requireNotNull(newestMatch)
        assertEquals(1, correlator.snapshot().pendingImages)
        assertEquals(1, correlator.snapshot().pendingResults)
    }

    @Test
    fun `supports two captures in flight out of order`() {
        val correlator = PendingStillCaptureCorrelator()

        correlator.onCaptureResult(
            frameNumber = 20L,
            sensorTimestampNs = 2000L,
            exposureTimeNs = 0L,
            rollingShutterSkewNs = 0L,
        )
        correlator.onCaptureResult(
            frameNumber = 21L,
            sensorTimestampNs = 2100L,
            exposureTimeNs = 0L,
            rollingShutterSkewNs = 0L,
        )

        val second =
            correlator.onImageAvailable(
                sensorTimestampNs = 2100L,
                width = 2,
                height = 2,
                bytes = byteArrayOf(2),
            )
        val first =
            correlator.onImageAvailable(
                sensorTimestampNs = 2000L,
                width = 2,
                height = 2,
                bytes = byteArrayOf(1),
            )

        requireNotNull(second)
        requireNotNull(first)
        assertEquals(21L, second.result.frameNumber)
        assertEquals(20L, first.result.frameNumber)
        assertEquals(0, correlator.snapshot().pendingImages)
        assertEquals(0, correlator.snapshot().pendingResults)
    }

    @Test
    fun `completion history is bounded and evicts oldest completed keys`() {
        val correlator = PendingStillCaptureCorrelator(completionHistoryLimit = 2)

        fun complete(frameNumber: Long, sensorTimestampNs: Long) {
            correlator.onImageAvailable(
                sensorTimestampNs = sensorTimestampNs,
                width = 1,
                height = 1,
                bytes = byteArrayOf(frameNumber.toByte()),
            )
            val correlated =
                correlator.onCaptureResult(
                    frameNumber = frameNumber,
                    sensorTimestampNs = sensorTimestampNs,
                    exposureTimeNs = 0L,
                    rollingShutterSkewNs = 0L,
                )
            requireNotNull(correlated)
        }

        complete(frameNumber = 1L, sensorTimestampNs = 100L)
        complete(frameNumber = 2L, sensorTimestampNs = 200L)
        complete(frameNumber = 3L, sensorTimestampNs = 300L)

        val recycled =
            correlator.onCaptureResult(
                frameNumber = 1L,
                sensorTimestampNs = 100L,
                exposureTimeNs = 0L,
                rollingShutterSkewNs = 0L,
            )

        assertNull(recycled)
        assertEquals(0, correlator.snapshot().pendingImages)
        assertEquals(1, correlator.snapshot().pendingResults)
    }
}
