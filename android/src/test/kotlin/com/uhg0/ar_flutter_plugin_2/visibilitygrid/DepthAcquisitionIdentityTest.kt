package com.uhg0.ar_flutter_plugin_2.visibilitygrid

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DepthAcquisitionIdentityTest {
    @Test
    fun `stale automatic image releases before sample lease or pixel copy`() {
        val depth = Image(10, 1_000)
        val pool = DepthSamplesLeasePool(capacity = 12)
        try {
            val result = copy(depth, null).acquirePacked({ _, _ -> metadata(20, DepthAcquisitionMode.AUTOMATIC) },
                pool, SampleLeaseGeneration(1, 1, 1, 1))
            assertEquals(DepthAcquisitionRefusal.STALE_AUTOMATIC, (result as PackedDepthAcquisitionResult.Refused).reason)
            assertEquals(10L, result.metadata.timestampNs)
            assertEquals(20L, result.metadata.arFrameTimestampNs)
            assertEquals(0, pool.leasedCount())
            assertEquals(0, depth.reads)
            assertEquals(1, depth.closes)
        } finally { pool.close() }
    }

    @Test
    fun `fresh automatic image keeps independent frame identity and releases once`() {
        val depth = Image(20, 1_000)
        val result = copy(depth, null).acquire(metadata(20, DepthAcquisitionMode.AUTOMATIC)) as DepthAcquisitionResult.Observation
        assertEquals(20L, result.value.timestampNs)
        assertEquals(DepthAcquisitionMode.AUTOMATIC, result.value.acquisitionMode)
        assertEquals(8, result.value.sourceImageWidth)
        assertEquals(6, result.value.sourceImageHeight)
        assertEquals(12, result.value.samples.size)
        assertEquals(1, depth.closes)
    }

    @Test
    fun `reprojected raw unequal confidence timestamp keeps current pose through packed adapter`() {
        val depth = Image(10, 1_000)
        val confidence = Image(20, 255)
        val pool = DepthSamplesLeasePool(capacity = 12)
        try {
            val currentPose = identityVisibilityGridTransform().also { it[12] = 0.75 }
            val result = copy(depth, confidence).acquirePacked({ _, _ -> metadata(20, DepthAcquisitionMode.RAW).copy(worldFromCameraGl = currentPose) },
                pool, SampleLeaseGeneration(1, 1, 1, 1)) as PackedDepthAcquisitionResult.Observation
            val owner = owner()
            val observation = VisibilityDepthCopyResult.PackedObservation(result.metadata, result.lease, owner,
                VisibilityDepthCapability.RAW_DEPTH, 1, 20).toObservation()
            try {
                assertEquals(10L, observation.frame.sourceTimestampNs)
                assertEquals(20L, observation.frame.frameTimestampNs)
                assertEquals(20L, observation.confidenceTimestampNs)
                assertEquals(0.75, observation.frame.pose.worldFromCameraGl[12], 0.0)
                assertEquals(DepthAcquisitionMode.RAW, observation.acquisitionMode)
                assertEquals(1, depth.closes)
                assertEquals(1, confidence.closes)
            } finally { observation.close() }
            assertEquals(0, pool.leasedCount())
        } finally { pool.close() }
    }

    @Test
    fun `raw dimension pairing refusal releases both images without pixels`() {
        val depth = Image(10, 1_000)
        val confidence = Image(20, 255, width = 3)
        val result = copy(depth, confidence).acquire(metadata(20, DepthAcquisitionMode.RAW)) as DepthAcquisitionResult.Refused
        assertEquals(DepthAcquisitionRefusal.CONFIDENCE_PAIR, result.reason)
        assertEquals(0, depth.reads)
        assertEquals(0, confidence.reads)
        assertEquals(1, depth.closes)
        assertEquals(1, confidence.closes)
    }

    @Test
    fun `invalid acquired timestamp refuses without consuming a lease`() {
        val depth = Image(0, 1_000)
        val confidence = Image(20, 255)
        val pool = DepthSamplesLeasePool(capacity = 12)
        try {
            val result = copy(depth, confidence).acquirePacked({ _, _ -> metadata(20, DepthAcquisitionMode.RAW) },
                pool, SampleLeaseGeneration(1, 1, 1, 1)) as PackedDepthAcquisitionResult.Refused
            assertEquals(DepthAcquisitionRefusal.INVALID_TIMESTAMP, result.reason)
            assertEquals(0, pool.leasedCount())
            assertEquals(1, depth.closes)
            assertEquals(1, confidence.closes)
        } finally { pool.close() }
    }

    @Test
    fun `depth identity reservation includes prepared overlap without growing feature pool`() {
        assertEquals(736L, DepthAcquisitionPortableMemory.MAXIMUM_INCREMENT_BYTES)
        assertTrue(DepthAcquisitionPortableMemory.MAXIMUM_INCREMENT_BYTES <= DepthAcquisitionPortableMemory.RESERVATION_BYTES)
        assertTrue(DepthAcquisitionPortableMemory.OBSERVATION_INCREMENT_BYTES <= 96L)
        assertTrue(DepthAcquisitionPortableMemory.MAXIMUM_INCREMENT_BYTES - DepthAcquisitionPortableMemory.TWO_OBSERVATION_BYTES <= 832L)
        val feature = FeatureSamplesLeasePool(capacity = 8)
        val depth = DepthSamplesLeasePool(capacity = 16)
        try {
            val identityExtra = 2L * DepthAcquisitionPortableMemory.OBSERVATION_INCREMENT_BYTES
            assertEquals(4_136L + identityExtra, depth.receipt().portableMaximumOwnedBytes)
            assertEquals(3_880L, feature.receipt().portableMaximumOwnedBytes)
            assertEquals(0, depth.receipt().growthCount)
            assertEquals(2, depth.receipt().slotCount)
        } finally { depth.close(); feature.close() }
    }

    private fun metadata(frame: Long, mode: DepthAcquisitionMode) = RawDepthFrameMetadata(
        timestampNs = frame, groupGeneration = 1, sessionGeneration = 1, tracking = true,
        width = 4, height = 3, intrinsics = DepthIntrinsics(2.0, 2.0, 1.5, 1.0),
        worldFromCameraGl = identityVisibilityGridTransform(), arFrameTimestampNs = frame,
        sourceImageWidth = 8, sourceImageHeight = 6, acquisitionMode = mode,
    )

    private fun owner() = VisibilityObservationOwnership(
        "01".repeat(16), 1, "02".repeat(16), 1, 1, "03".repeat(16), "04".repeat(16),
        1, "05".repeat(16), "06".repeat(16), 1, 1, 1,
        VisibilityGroupFrame.copyOf(identityVisibilityGridTransform(), identityVisibilityGridTransform(), 100_000, 100_000),
    )

    private fun copy(depth: RawDepthImage, confidence: RawDepthImage?) = RawDepthCopySource(object : PairedRawDepthAcquirer {
        override fun acquireDepth() = depth
        override fun acquireConfidence() = confidence
    }, maxCopiedPixels = 12)

    private class Image(override val timestampNs: Long, private val value: Int,
        override val width: Int = 4, override val height: Int = 3) : RawDepthImage {
        var reads = 0
        var closes = 0
        override fun unsignedValue(x: Int, y: Int): Int { reads++; return value }
        override fun close() { closes++ }
    }
}
