package com.uhg0.ar_flutter_plugin_2.visibilitygrid

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DepthCalibrationAvailabilityTest {
    @Test
    fun `unavailable calibration closes acquired images before preparation copy or lease`() {
        val pool = DepthSamplesLeasePool(capacity = 12)
        try {
            for (path in 0..2) {
                val depth = CalibrationDepthImage(1L, 1_000)
                val confidence = CalibrationDepthImage(1L, 255)
                var acquired = 0
                var closed = 0
                val acquirer = calibrationAcquirer(depth, confidence)
                when (path) {
                    0 -> assertEquals(PreparedRawDepthResult.TransientUnavailable, prepareRawDepthFrame(
                        acquirer, { width, height -> calibratedMetadata(1L, width, height, valid = false) },
                        onResourceAcquired = { acquired++ }, onResourceClosed = { closed++ },
                    ))
                    1 -> assertEquals(DepthAcquisitionResult.TransientUnavailable, RawDepthCopySource(
                        acquirer, onResourceAcquired = { acquired++ }, onResourceClosed = { closed++ },
                    ).acquire { width, height -> calibratedMetadata(1L, width, height, valid = false) })
                    else -> assertEquals(PackedDepthAcquisitionResult.TransientUnavailable, RawDepthCopySource(
                        acquirer, onResourceAcquired = { acquired++ }, onResourceClosed = { closed++ },
                    ).acquirePacked({ width, height -> calibratedMetadata(1L, width, height, valid = false) },
                        pool, SampleLeaseGeneration(1, 1, 1, 1)))
                }
                assertEquals(2, acquired)
                assertEquals(2, closed)
                assertEquals(1, depth.closes)
                assertEquals(1, confidence.closes)
                assertEquals(0, depth.reads)
                assertEquals(0, confidence.reads)
                assertEquals(0, pool.leasedCount())
            }
            val fatalDepth = CalibrationDepthImage(2L, 1_000)
            val fatalConfidence = CalibrationDepthImage(2L, 255)
            assertTrue(prepareRawDepthFrame(calibrationAcquirer(fatalDepth, fatalConfidence), { _, _ ->
                throw IllegalStateException("genuine acquisition failure")
            }) is PreparedRawDepthResult.Failure)
            assertEquals(1, fatalDepth.closes)
            assertEquals(1, fatalConfidence.closes)
        } finally { pool.close() }
    }
}

internal class CalibrationDepthImage(override val timestampNs: Long, private val value: Int) : RawDepthImage {
    override val width = 4
    override val height = 3
    var closes = 0
    var reads = 0
    override fun unsignedValue(x: Int, y: Int): Int { reads++; return value }
    override fun close() { closes++ }
}

internal fun calibrationAcquirer(depth: RawDepthImage, confidence: RawDepthImage?) = object : PairedRawDepthAcquirer {
    override fun acquireDepth() = depth
    override fun acquireConfidence() = confidence
}

internal fun calibratedMetadata(timestamp: Long, width: Int, height: Int, valid: Boolean = true) = freezeTextureDepthMetadata(
    timestamp, 1L, 1L, true, width, height, intArrayOf(if (valid) 8 else 0, 6),
    floatArrayOf(4f, 5f), floatArrayOf(3f, 2f),
    FloatArray(16) { if (it % 5 == 0) 1f else 0f }, intArrayOf(12, 9), DepthAcquisitionMode.AUTOMATIC, cameraImageTimestampNs = timestamp,
)
