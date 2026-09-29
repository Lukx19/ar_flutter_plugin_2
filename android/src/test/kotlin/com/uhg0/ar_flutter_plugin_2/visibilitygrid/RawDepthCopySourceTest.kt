package com.uhg0.ar_flutter_plugin_2.visibilitygrid

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

class RawDepthCopySourceTest {
    @Test
    fun `failed image close stays outstanding while the other image still releases`() {
        var acquired = 0
        var released = 0
        val depth = FakeRawDepthImage(4, 3, 1_000)
        val confidence = object : RawDepthImage {
            override val width = 4
            override val height = 3
            override fun unsignedValue(x: Int, y: Int) = 255
            override fun close() { error("native image release failed") }
        }
        val source = RawDepthCopySource(
            FakePairedAcquirer(depth, confidence),
            onResourceAcquired = { acquired++ },
            onResourceClosed = { released++ },
        )
        assertThrows(IllegalStateException::class.java) { source.acquire(metadata()) }
        assertEquals(2, acquired)
        assertEquals(1, released)
        assertEquals(1, depth.closeCount)
    }

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
        assertTrue((result as DepthAcquisitionResult.Observation).value.samples.size in 1..64)
        assertEquals(
            DepthImageOrientation.LANDSCAPE_RIGHT,
            result.value.imageOrientation,
        )
        assertEquals(1, depth.closeCount)
        assertEquals(1, confidence.closeCount)
    }

    @Test
    fun `large depth image scans pixels with bounded retained samples`() {
        var depthReads = 0
        var confidenceReads = 0
        val depth = FakeRawDepthImage(width = 2_000, height = 2_000) { _, _ ->
            depthReads++
            1_000
        }
        val confidence = FakeRawDepthImage(width = 2_000, height = 2_000) { _, _ ->
            confidenceReads++
            255
        }
        val source = RawDepthCopySource(
            acquirer = FakePairedAcquirer(depth, confidence),
            maxCopiedPixels = V2_DEPTH_SAMPLE_CAPACITY,
        )

        val result = source.acquire(metadata(width = 2_000, height = 2_000))

        assertTrue(result is DepthAcquisitionResult.Observation)
        val samples = (result as DepthAcquisitionResult.Observation).value.samples
        assertTrue(samples.size in 1_000..V2_DEPTH_SAMPLE_CAPACITY)
        assertEquals(DepthPixelSample(0, 0, 1_000, 255), samples.first())
        assertTrue(samples.maxOf { it.y } >= 1_900)
        assertTrue(samples.maxOf { it.x } >= 1_900)
        assertTrue(depthReads in 4_000_000..4_200_000)
        assertTrue(confidenceReads in 4_000_000..4_100_000)
        assertEquals(1, depth.closeCount)
        assertEquals(1, confidence.closeCount)
    }

    @Test
    fun `small foreground patch survives large image depth selection`() {
        val depth = FakeRawDepthImage(width = 2_000, height = 2_000) { x, y ->
            if (x in 990..1_001 && y in 990..1_001) 500 else 1_000
        }
        val confidence = FakeRawDepthImage(width = 2_000, height = 2_000, value = 255)
        val source = RawDepthCopySource(
            acquirer = FakePairedAcquirer(depth, confidence),
            maxCopiedPixels = V2_DEPTH_SAMPLE_CAPACITY,
        )

        val result = source.acquire(metadata(width = 2_000, height = 2_000))
            as DepthAcquisitionResult.Observation
        assertTrue(result.value.samples.size <= V2_DEPTH_SAMPLE_CAPACITY)
        assertTrue(result.value.samples.any { sample ->
            sample.x in 991..1_000 && sample.y in 991..1_000 &&
                sample.depthMillimeters == 500
        })
        assertEquals(1, depth.closeCount)
        assertEquals(1, confidence.closeCount)
    }

    @Test
    fun `isolated near noise does not hide a small foreground patch`() {
        val depth = FakeRawDepthImage(width = 2_000, height = 2_000) { x, y ->
            when {
                x == 970 && y == 970 -> 200
                x in 990..1_001 && y in 980..991 -> 500
                else -> 1_000
            }
        }
        val confidence = FakeRawDepthImage(width = 2_000, height = 2_000, value = 255)
        val source = RawDepthCopySource(
            acquirer = FakePairedAcquirer(depth, confidence),
            maxCopiedPixels = V2_DEPTH_SAMPLE_CAPACITY,
        )
        val result = source.acquire(metadata(width = 2_000, height = 2_000))
            as DepthAcquisitionResult.Observation
        assertTrue(result.value.samples.any { sample ->
            sample.depthMillimeters == 500 && sample.x in 991..1_000 && sample.y in 981..990
        })
        assertTrue(result.value.samples.none { it.depthMillimeters == 200 })
    }

    @Test
    fun `two small surfaces in one tile both survive bounded selection`() {
        val depth = FakeRawDepthImage(width = 2_000, height = 2_000) { x, y ->
            when {
                x in 980..991 && y in 980..991 -> 500
                x in 1_005..1_016 && y in 980..991 -> 700
                else -> 1_000
            }
        }
        val source = RawDepthCopySource(
            acquirer = FakePairedAcquirer(
                depth, FakeRawDepthImage(width = 2_000, height = 2_000, value = 255),
            ),
            maxCopiedPixels = V2_DEPTH_SAMPLE_CAPACITY,
        )

        val result = source.acquire(metadata(width = 2_000, height = 2_000))
            as DepthAcquisitionResult.Observation
        assertTrue(result.value.samples.size <= V2_DEPTH_SAMPLE_CAPACITY)
        assertTrue(result.value.samples.any { it.depthMillimeters == 500 })
        assertTrue(result.value.samples.any { it.depthMillimeters == 700 })
    }

    @Test
    fun `three adjacent tiny surfaces survive content-aware depth selection`() {
        val depth = FakeRawDepthImage(width = 2_000, height = 2_000) { x, y ->
            when {
                x in 969..971 && y in 970..972 -> 400
                x in 995..997 && y in 970..972 -> 600
                x in 1_020..1_022 && y in 970..972 -> 800
                else -> 1_000
            }
        }
        val source = RawDepthCopySource(
            acquirer = FakePairedAcquirer(
                depth, FakeRawDepthImage(width = 2_000, height = 2_000, value = 255),
            ),
            maxCopiedPixels = V2_DEPTH_SAMPLE_CAPACITY,
        )

        val result = source.acquire(metadata(width = 2_000, height = 2_000))
            as DepthAcquisitionResult.Observation
        assertTrue(result.value.samples.size <= V2_DEPTH_SAMPLE_CAPACITY)
        for (foregroundDepth in listOf(400, 600, 800)) {
            assertTrue("Missing ${foregroundDepth}mm foreground", result.value.samples.any {
                it.depthMillimeters == foregroundDepth
            })
        }
    }

    @Test
    fun `two thousand pixel depth selection finishes within one second`() {
        val source = RawDepthCopySource(
            acquirer = object : PairedRawDepthAcquirer {
                override fun acquireDepth(): RawDepthImage =
                    FakeRawDepthImage(2_000, 2_000) { x, y ->
                        if (x in 990..1_001 && y in 990..1_001) 500 else 1_000
                    }

                override fun acquireConfidence(): RawDepthImage =
                    FakeRawDepthImage(2_000, 2_000, 255)
            },
            maxCopiedPixels = V2_DEPTH_SAMPLE_CAPACITY,
        )
        val frame = metadata(width = 2_000, height = 2_000)
        repeat(2) { source.acquire(frame) }
        val durations = (1..3).map {
            val started = System.nanoTime()
            val result = source.acquire(frame) as DepthAcquisitionResult.Observation
            assertTrue(result.value.samples.any { it.depthMillimeters == 500 })
            System.nanoTime() - started
        }
        val worstMillis = durations.max() / 1_000_000.0
        println("depth_selection_2000x2000_worst_ms=$worstMillis")
        assertTrue("Depth selection took ${worstMillis}ms", worstMillis < 1_000.0)
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
