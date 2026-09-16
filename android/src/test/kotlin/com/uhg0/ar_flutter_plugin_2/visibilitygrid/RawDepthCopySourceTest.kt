package com.uhg0.ar_flutter_plugin_2.visibilitygrid

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RawDepthCopySourceTest {
    @Test
    fun `paired images are copied within budget and closed exactly once`() {
        val depth = FakeRawDepthImage(width = 100, height = 100, value = 1_000)
        val confidence = FakeRawDepthImage(width = 100, height = 100, value = 200)
        val source =
            RawDepthCopySource(
                acquirer = FakePairedAcquirer(depth, confidence),
                maxCopiedPixels = 64,
            )

        val result = source.acquire(metadata(width = 100, height = 100))

        assertTrue(result is DepthAcquisitionResult.Observation)
        assertEquals(64, (result as DepthAcquisitionResult.Observation).value.samples.size)
        assertEquals(
            DepthImageOrientation.LANDSCAPE_RIGHT,
            result.value.imageOrientation,
        )
        assertEquals(1, depth.closeCount)
        assertEquals(1, confidence.closeCount)
    }

    @Test
    fun `resource telemetry balances partial and complete paired acquisition`() {
        var acquired = 0
        var closed = 0
        val complete = RawDepthCopySource(
            acquirer = FakePairedAcquirer(
                FakeRawDepthImage(4, 3, 1_000),
                FakeRawDepthImage(4, 3, 255),
            ),
            onResourceAcquired = { acquired++ },
            onResourceClosed = { closed++ },
        )
        assertTrue(complete.acquire(metadata()) is DepthAcquisitionResult.Observation)
        assertEquals(2, acquired)
        assertEquals(2, closed)

        val partial = RawDepthCopySource(
            acquirer = FakePairedAcquirer(
                FakeRawDepthImage(4, 3, 1_000),
                null,
                IllegalStateException("confidence failed"),
            ),
            onResourceAcquired = { acquired++ },
            onResourceClosed = { closed++ },
        )
        assertTrue(partial.acquire(metadata()) is DepthAcquisitionResult.Failure)
        assertEquals(3, acquired)
        assertEquals(3, closed)
    }

    @Test
    fun `depth image is closed when confidence acquisition fails`() {
        val depth = FakeRawDepthImage(width = 4, height = 3, value = 1_000)
        val source =
            RawDepthCopySource(
                acquirer =
                    FakePairedAcquirer(
                        depth = depth,
                        confidence = null,
                        confidenceFailure = IllegalStateException("confidence failed"),
                    ),
            )

        assertTrue(source.acquire(metadata()) is DepthAcquisitionResult.Failure)
        assertEquals(1, depth.closeCount)
    }

    @Test
    fun `mismatched paired images are rejected and both closed`() {
        val depth = FakeRawDepthImage(width = 4, height = 3, value = 1_000)
        val confidence = FakeRawDepthImage(width = 3, height = 3, value = 200)
        val source = RawDepthCopySource(FakePairedAcquirer(depth, confidence))

        assertTrue(source.acquire(metadata()) is DepthAcquisitionResult.Failure)
        assertEquals(1, depth.closeCount)
        assertEquals(1, confidence.closeCount)
    }

    @Test
    fun `thin-wall foreground occlusion edges are rejected conservatively`() {
        val depth =
            FakeRawDepthImage(width = 3, height = 1) { x, _ ->
                if (x == 1) 2_000 else 1_000
            }
        val confidence = FakeRawDepthImage(width = 3, height = 1, value = 255)
        val source = RawDepthCopySource(FakePairedAcquirer(depth, confidence))

        val result =
            source.acquire(
                metadata(width = 3, height = 1).copy(
                    intrinsics = DepthIntrinsics(2.0, 2.0, 1.0, 0.0),
                ),
            ) as DepthAcquisitionResult.Observation

        assertTrue(result.value.samples.isEmpty())
        assertEquals(3, result.value.sourceRejectedPixels)
        assertEquals(1, depth.closeCount)
        assertEquals(1, confidence.closeCount)
    }

    @Test
    fun `copy failures still close both acquired images exactly once`() {
        val depth =
            FakeRawDepthImage(width = 4, height = 3) { _, _ ->
                throw IllegalStateException("synthetic read failure")
            }
        val confidence = FakeRawDepthImage(width = 4, height = 3, value = 255)
        val source = RawDepthCopySource(FakePairedAcquirer(depth, confidence))

        assertTrue(source.acquire(metadata()) is DepthAcquisitionResult.Failure)
        assertEquals(1, depth.closeCount)
        assertEquals(1, confidence.closeCount)
    }

    private fun metadata(
        width: Int = 4,
        height: Int = 3,
    ) = RawDepthFrameMetadata(
        timestampNs = 1,
        groupGeneration = 1,
        sessionGeneration = 1,
        tracking = true,
        width = width,
        height = height,
        intrinsics = DepthIntrinsics(2.0, 2.0, 1.5, 1.0),
        worldFromCameraGl = identityVisibilityGridTransform(),
    )

    private class FakePairedAcquirer(
        private val depth: RawDepthImage?,
        private val confidence: RawDepthImage?,
        private val confidenceFailure: RuntimeException? = null,
    ) : PairedRawDepthAcquirer {
        override fun acquireDepth(): RawDepthImage =
            depth ?: throw IllegalStateException("depth failed")

        override fun acquireConfidence(): RawDepthImage {
            confidenceFailure?.let { throw it }
            return confidence ?: throw IllegalStateException("confidence failed")
        }
    }

    private class FakeRawDepthImage(
        override val width: Int,
        override val height: Int,
        private val read: (Int, Int) -> Int,
    ) : RawDepthImage {
        constructor(
            width: Int,
            height: Int,
            value: Int,
        ) : this(width, height, { _, _ -> value })

        var closeCount = 0

        override fun unsignedValue(
            x: Int,
            y: Int,
        ): Int = read(x, y)

        override fun close() {
            closeCount++
        }
    }
}
