package com.uhg0.ar_flutter_plugin_2.visibilitygrid

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
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

    private fun metadata() = RawDepthFrameMetadata(
        timestampNs = 1, groupGeneration = 1, sessionGeneration = 1, tracking = true,
        width = 4, height = 3,
        intrinsics = DepthIntrinsics(2.0, 2.0, 1.5, 1.0),
        worldFromCameraGl = identityVisibilityGridTransform(),
    )

    private class CountingImage(
        private val value: Int,
        private val failRead: Boolean = false,
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
