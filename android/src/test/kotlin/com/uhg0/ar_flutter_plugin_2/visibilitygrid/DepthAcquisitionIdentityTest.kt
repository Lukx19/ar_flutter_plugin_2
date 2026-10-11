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
        assertEquals(1_024L, DepthAcquisitionPortableMemory.MAXIMUM_INCREMENT_BYTES)
        assertTrue(DepthAcquisitionPortableMemory.MAXIMUM_INCREMENT_BYTES <= DepthAcquisitionPortableMemory.RESERVATION_BYTES)
        assertTrue(DepthAcquisitionPortableMemory.OBSERVATION_INCREMENT_BYTES <= 96L)
        assertTrue(DepthAcquisitionPortableMemory.MAXIMUM_INCREMENT_BYTES - DepthAcquisitionPortableMemory.TWO_OBSERVATION_BYTES <=
            DepthAcquisitionPortableMemory.RESERVATION_BYTES - DepthAcquisitionPortableMemory.TWO_OBSERVATION_BYTES)
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

    @Test
    fun `automatic image identity uses exact camera timestamp despite either AR clock offset`() {
        for (poseFrameTime in listOf(10L, 30L)) {
            val depth = Image(20, 1_000)
            val currentPose = identityVisibilityGridTransform().also { it[12] = 0.75 }
            val frozen = metadata(poseFrameTime, DepthAcquisitionMode.AUTOMATIC).copy(
                cameraImageTimestampNs = 20, worldFromCameraGl = currentPose)
            val result = copy(depth, null).acquire(frozen) as DepthAcquisitionResult.Observation
            assertEquals(20L, result.value.timestampNs)
            assertEquals(20L, result.value.cameraImageTimestampNs)
            assertEquals(0.75, result.value.worldFromCameraGl[12], 0.0)
            assertEquals(poseFrameTime, frozen.arFrameTimestampNs)
            assertEquals(1, depth.closes)
            assertTrue(depth.reads > 0)
        }
    }

    @Test
    fun `unavailable old future or zero camera image identity refuses before pixel copy`() {
        val cases = listOf(
            Triple(19L, 20L, DepthAcquisitionRefusal.STALE_AUTOMATIC),
            Triple(21L, 20L, DepthAcquisitionRefusal.INVALID_TIMESTAMP),
            Triple(0L, 20L, DepthAcquisitionRefusal.INVALID_TIMESTAMP),
            Triple(20L, 0L, DepthAcquisitionRefusal.INVALID_TIMESTAMP),
        )
        for ((imageTime, cameraTime, expected) in cases) {
            val depth = Image(imageTime, 1_000)
            val pool = DepthSamplesLeasePool(capacity = 12)
            try {
                val frozen = metadata(1_000, DepthAcquisitionMode.AUTOMATIC).copy(cameraImageTimestampNs = cameraTime)
                val result = copy(depth, null).acquirePacked({ _, _ -> frozen }, pool,
                    SampleLeaseGeneration(1, 1, 1, 1)) as PackedDepthAcquisitionResult.Refused
                assertEquals(expected, result.reason)
                assertEquals(0, depth.reads)
                assertEquals(0, pool.leasedCount())
                assertEquals(1, depth.closes)
            } finally { pool.close() }
        }
    }

    @Test
    fun `refused preparation retains exact acquisition clocks without pixels or leaked images`() {
        val depth = Image(21, 1_000)
        var afterAcquisition = false
        val result = prepareRawDepthFrame(
            acquirer = object : PairedRawDepthAcquirer {
                override fun acquireDepth(): RawDepthImage { afterAcquisition = true; return depth }
                override fun acquireConfidence(): RawDepthImage? = null
            },
            metadataForDimensions = { _, _ ->
                assertTrue(afterAcquisition)
                metadata(21, DepthAcquisitionMode.AUTOMATIC).copy(
                    cameraImageTimestampNs = 20,
                    acquisitionFrameBeforeNs = 21,
                    acquisitionAndroidCameraBeforeNs = 20, acquisitionFrameSequence = 7,
                )
            },
        ) as PreparedRawDepthResult.Refused
        assertEquals(DepthAcquisitionRefusal.INVALID_TIMESTAMP, result.reason)
        assertEquals("imageAfterAndroidCamera", depthRefusalBound(result.reason, result.metadata))
        assertEquals(21L, result.metadata.timestampNs)
        assertEquals(21L, result.metadata.acquisitionFrameBeforeNs)
        assertEquals(21L, result.metadata.acquisitionFrameAfterNs)
        assertEquals(20L, result.metadata.acquisitionAndroidCameraBeforeNs)
        assertEquals(20L, result.metadata.acquisitionAndroidCameraAfterNs)
        assertEquals(7L, result.metadata.acquisitionFrameSequence)
        assertEquals(0, depth.reads)
        assertEquals(1, depth.closes)
    }

    @Test
    fun `invalid diagnostic bounds distinguish actual non-positive clocks from positive cross-source order`() {
        val valid = metadata(21, DepthAcquisitionMode.AUTOMATIC).copy(timestampNs = 21, cameraImageTimestampNs = 20)
        for ((value, expected) in listOf(
            valid.copy(timestampNs = Long.MIN_VALUE) to "imageNonPositive",
            valid.copy(timestampNs = 0) to "imageNonPositive",
            valid.copy(cameraImageTimestampNs = 0) to "androidCameraNonPositive",
            valid.copy(arFrameTimestampNs = 0) to "arFrameNonPositive",
            valid to "imageAfterAndroidCamera",
        )) assertEquals(expected, depthRefusalBound(DepthAcquisitionRefusal.INVALID_TIMESTAMP, value))
        assertEquals("automaticImageNotEqualAndroidCamera", depthRefusalBound(
            DepthAcquisitionRefusal.STALE_AUTOMATIC, valid.copy(timestampNs = 19)))
    }

    @Test
    fun `physical nonmatching tuple qualifies only paired raw current projection`() {
        val imageTime = 845720289472481L
        val frameTime = 845720290140319L
        val cameraTime = 845720257661069L
        for (mode in listOf(DepthAcquisitionMode.RAW, DepthAcquisitionMode.AUTOMATIC)) {
            val depth = Image(imageTime, 1_000)
            val confidence = if (mode == DepthAcquisitionMode.RAW) Image(7, 255) else null
            val frozen = metadata(frameTime, mode).copy(cameraImageTimestampNs = cameraTime,
                acquisitionAndroidCameraBeforeNs = cameraTime)
            val result = copy(depth, confidence).acquire(frozen)
            if (mode == DepthAcquisitionMode.RAW) {
                val observation = (result as DepthAcquisitionResult.Observation).value
                assertEquals(imageTime, observation.timestampNs)
                assertEquals(frameTime, observation.projectionFrameTimestampNs)
                assertEquals(7L, observation.confidenceTimestampNs)
                assertEquals(12, observation.samples.size)
                assertEquals(1, requireNotNull(confidence).closes)
            } else {
                assertEquals(DepthAcquisitionRefusal.INVALID_TIMESTAMP, (result as DepthAcquisitionResult.Refused).reason)
                assertEquals(0, depth.reads)
            }
            assertEquals(1, depth.closes)
        }
    }

    @Test
    fun `raw missing confidence changed frame or untracked projection refuses before pixels`() {
        val valid = metadata(845720290140319L, DepthAcquisitionMode.RAW).copy(
            cameraImageTimestampNs = 845720257661069L, acquisitionAndroidCameraBeforeNs = 845720257661069L)
        val cases = listOf(
            valid to null,
            valid to Image(7, 255, width = 3),
            valid.copy(acquisitionFrameBeforeNs = valid.arFrameTimestampNs - 1) to Image(7, 255),
            valid.copy(acquisitionAndroidCameraBeforeNs = valid.cameraImageTimestampNs - 1) to Image(7, 255),
            valid.copy(tracking = false) to Image(7, 255),
            valid.copy(arFrameTimestampNs = 0) to Image(7, 255),
            valid.copy(worldFromCameraGl = DoubleArray(16) { Double.NaN }) to Image(7, 255),
        )
        for ((frame, confidence) in cases) {
            val depth = Image(845720289472481L, 1_000)
            val result = copy(depth, confidence).acquire(frame)
            assertTrue(result is DepthAcquisitionResult.Refused)
            assertEquals(0, depth.reads)
            assertEquals(1, depth.closes)
            if (confidence != null) { assertEquals(0, confidence.reads); assertEquals(1, confidence.closes) }
        }
        val zero = Image(0, 1_000)
        val confidence = Image(7, 255)
        assertTrue(copy(zero, confidence).acquire(valid) is DepthAcquisitionResult.Refused)
        assertEquals(0, zero.reads)
        assertEquals(1, zero.closes)
        assertEquals(1, confidence.closes)
    }

    private fun metadata(frame: Long, mode: DepthAcquisitionMode) = RawDepthFrameMetadata(
        timestampNs = frame, groupGeneration = 1, sessionGeneration = 1, tracking = true,
        width = 4, height = 3, intrinsics = DepthIntrinsics(2.0, 2.0, 1.5, 1.0),
        worldFromCameraGl = identityVisibilityGridTransform(), arFrameTimestampNs = frame,
        sourceImageWidth = 8, sourceImageHeight = 6, acquisitionMode = mode, cameraImageTimestampNs = frame,
        acquisitionFrameBeforeNs = frame, acquisitionAndroidCameraBeforeNs = frame,
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
