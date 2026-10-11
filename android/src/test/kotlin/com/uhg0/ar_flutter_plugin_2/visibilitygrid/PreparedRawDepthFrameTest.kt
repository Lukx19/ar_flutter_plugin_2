package com.uhg0.ar_flutter_plugin_2.visibilitygrid

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PreparedRawDepthFrameTest {
    @Test
    fun `processed images close once on success and copy failure`() {
        listOf(false, true).forEach { failRead ->
            val depth = CountingImage(1_000, failRead)
            val confidence = CountingImage(255)
            val frame = PreparedRawDepthFrame(metadata(), depth, confidence, 12)
            val result = frame.process()
            assertEquals(failRead, result is DepthAcquisitionResult.Failure)
            frame.close()
            assertEquals(1, depth.closeCount)
            assertEquals(1, confidence.closeCount)
        }
    }

    @Test
    fun `busy processor releases rejected paired images once`() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val processor = BoundedDepthObservationProcessor<PreparedRawDepthFrame, DepthAcquisitionResult>(
            process = { frame ->
                entered.countDown()
                check(release.await(2, TimeUnit.SECONDS))
                frame.process()
            },
            publish = { _, _ -> },
        )
        val firstDepth = CountingImage(1_000)
        val firstConfidence = CountingImage(255)
        val droppedDepth = CountingImage(1_000)
        val droppedConfidence = CountingImage(255)
        try {
            assertTrue(processor.offer(
                PreparedRawDepthFrame(metadata(), firstDepth, firstConfidence, 12), 0,
            ))
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            assertFalse(processor.offer(
                PreparedRawDepthFrame(metadata(), droppedDepth, droppedConfidence, 12), 0,
            ))
            assertEquals(1, droppedDepth.closeCount)
            assertEquals(1, droppedConfidence.closeCount)
            release.countDown()
            assertTrue(processor.awaitIdle(2_000))
            assertEquals(1, firstDepth.closeCount)
            assertEquals(1, firstConfidence.closeCount)
        } finally {
            release.countDown()
            processor.close()
        }
    }

    @Test
    fun `raw packed work retains prepared projection calibration pose and immutable group`() {
        val frameTime = 845720290140319L
        val cameraTime = 845720257661069L
        val imageTime = 845720289472481L
        val pose = FloatArray(16) { if (it % 5 == 0) 1f else 0f }.also { it[12] = 0.75f }
        val textureDimensions = intArrayOf(8, 6)
        val textureFocal = floatArrayOf(4f, 4f)
        val texturePrincipal = floatArrayOf(3.5f, 2.5f)
        val depth = CountingImage(1_000, timestampNs = imageTime)
        val confidence = CountingImage(255, timestampNs = 7)
        val prepared = (prepareRawDepthFrame(
            object : PairedRawDepthAcquirer {
                override fun acquireDepth(): RawDepthImage = depth
                override fun acquireConfidence(): RawDepthImage = confidence
            },
            { width, height -> freezeTextureDepthMetadata(frameTime, 1, 1, true, width, height,
                textureDimensions, textureFocal, texturePrincipal, pose, intArrayOf(8, 6),
                DepthAcquisitionMode.RAW, cameraTime).copy(acquisitionFrameBeforeNs = frameTime,
                    acquisitionAndroidCameraBeforeNs = cameraTime, acquisitionFrameSequence = 3) },
        ) as PreparedRawDepthResult.Ready).frame
        val groupFromWorld = identityVisibilityGridTransform().also { it[12] = -2.0 }
        val groupFrame = VisibilityGroupFrame.copyOf(groupFromWorld,
            identityVisibilityGridTransform().also { it[12] = 2.0 }, 100_000, 100)
        val owner = VisibilityObservationOwnership("01".repeat(16), 1, "02".repeat(16), 1, 1,
            "03".repeat(16), "04".repeat(16), 1, "05".repeat(16), "06".repeat(16), 1, 1, 1, groupFrame)
        pose[12] = 9f
        textureDimensions.fill(99)
        textureFocal.fill(99f)
        texturePrincipal.fill(99f)
        groupFromWorld[12] = 99.0
        val pool = DepthSamplesLeasePool(capacity = 12)
        try {
            val copied = prepared.processPacked(pool, SampleLeaseGeneration.from(owner)) as PackedDepthAcquisitionResult.Observation
            val observation = VisibilityDepthCopyResult.PackedObservation(copied.metadata, copied.lease,
                owner, VisibilityDepthCapability.AUTOMATIC, 3, frameTime + 999).toObservation()
            try {
                assertEquals(imageTime, observation.frame.sourceTimestampNs)
                assertEquals(frameTime, observation.frame.frameTimestampNs)
                assertEquals(0.75, observation.frame.pose.worldFromCameraGl[12], 0.0)
                assertEquals(2.0, observation.frame.intrinsics.fx, 0.0)
                assertEquals(4, observation.frame.intrinsics.imageWidth)
                assertEquals(DepthAcquisitionMode.RAW, observation.acquisitionMode)
                assertEquals(VisibilityDepthCapability.AUTOMATIC, observation.frame.depthCapability)
                assertTrue(observation.rawProjectionQualified)
                assertSame(groupFrame, observation.ownership.groupFrame)
                assertEquals(-2.0, observation.ownership.groupFrame.groupFromWorldGl[12], 0.0)
                assertEquals(1, depth.closeCount)
                assertEquals(1, confidence.closeCount)
            } finally { observation.close() }
            assertEquals(0, pool.leasedCount())
        } finally { prepared.close(); pool.close() }
        assertEquals(1, depth.closeCount)
        assertEquals(1, confidence.closeCount)
    }

    private fun metadata() = RawDepthFrameMetadata(
        timestampNs = 1, groupGeneration = 1, sessionGeneration = 1, tracking = true,
        width = 4, height = 3,
        intrinsics = DepthIntrinsics(2.0, 2.0, 1.5, 1.0),
        worldFromCameraGl = identityVisibilityGridTransform(),
    )

    private class CountingImage(
        private val value: Int,
        private val failRead: Boolean = false,
        override val timestampNs: Long = 0L,
    ) : RawDepthImage {
        override val width = 4
        override val height = 3
        var closeCount = 0
        override fun unsignedValue(x: Int, y: Int): Int {
            if (failRead) throw IllegalStateException("read failed")
            return value
        }
        override fun close() { closeCount++ }
    }
}
