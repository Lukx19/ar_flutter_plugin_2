package com.uhg0.ar_flutter_plugin_2.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PoseDataExtractorTest {
    @Test
    fun `tracking readiness waits for an observed tracking pose`() {
        val extractor = PoseDataExtractor()
        extractor.addSample(sample(timestampNs = 1_000_000L, isTracking = false))

        assertTrue(!extractor.awaitTrackingPose(timeoutMs = 0))

        extractor.addSample(sample(timestampNs = 2_000_000L))

        assertTrue(extractor.awaitTrackingPose(timeoutMs = 0))
    }

    @Test
    fun `keeps samples timestamp ordered and bounded by capacity`() {
        val extractor = PoseDataExtractor(capacity = 3)

        extractor.addSample(sample(timestampNs = 70_000_000L))
        extractor.addSample(sample(timestampNs = 50_000_000L))
        extractor.addSample(sample(timestampNs = 60_000_000L))
        extractor.addSample(sample(timestampNs = 80_000_000L))

        val resolved =
            extractor.resolvePose(
                PoseDataExtractor.CaptureTiming(
                    sensorTimestampNs = 80_000_000L,
                    exposureTimeNs = 0,
                ),
                waitForFuturePoseMs = 0,
            )

        assertNotNull(resolved)
        assertEquals(80_000_000L, resolved!!.pose.timestampNs)
        assertNull(
            extractor.resolvePose(
                PoseDataExtractor.CaptureTiming(
                    sensorTimestampNs = 300_000_000L,
                    exposureTimeNs = 0,
                ),
                waitForFuturePoseMs = 0,
            ),
        )
    }

    @Test
    fun `resolves exact match within tolerance`() {
        val extractor = PoseDataExtractor()
        extractor.addSample(sample(timestampNs = 1_000_000_000L))

        val resolved =
            extractor.resolvePose(
                PoseDataExtractor.CaptureTiming(
                    sensorTimestampNs = 999_500_000L,
                    exposureTimeNs = 1_000_000L,
                ),
                waitForFuturePoseMs = 0,
            )

        assertNotNull(resolved)
        assertEquals("exact", resolved!!.poseAlignment)
        assertEquals(0L, resolved.poseTimeErrorNs)
    }

    @Test
    fun `resolves interpolated pose at exposure midpoint`() {
        val extractor = PoseDataExtractor()
        extractor.addSample(sample(timestampNs = 1_000_000_000L, positionX = 0f))
        extractor.addSample(sample(timestampNs = 1_040_000_000L, positionX = 4f))

        val resolved =
            extractor.resolvePose(
                PoseDataExtractor.CaptureTiming(
                    sensorTimestampNs = 1_015_000_000L,
                    exposureTimeNs = 10_000_000L,
                ),
                waitForFuturePoseMs = 0,
            )

        assertNotNull(resolved)
        assertEquals("interpolated", resolved!!.poseAlignment)
        assertEquals(0L, resolved.poseTimeErrorNs)
        assertEquals(2.0f, resolved.pose.position[0], 0.0001f)
        assertEquals(1_020_000_000L, resolved.pose.timestampNs)
    }

    @Test
    fun `falls back to bounded monotonic observation correlation across clock domains`() {
        val extractor = PoseDataExtractor()
        extractor.addSample(
            sample(
                timestampNs = 5_000_000_000L,
                observedTimestampNs = 1_000_000_000L,
                positionX = 0f,
            ),
        )
        extractor.addSample(
            sample(
                timestampNs = 5_040_000_000L,
                observedTimestampNs = 1_040_000_000L,
                positionX = 4f,
            ),
        )

        val resolved =
            extractor.resolvePose(
                PoseDataExtractor.CaptureTiming(
                    sensorTimestampNs = 90_000_000_000L,
                    exposureTimeNs = 10_000_000L,
                    observedTimestampNs = 1_015_000_000L,
                ),
                waitForFuturePoseMs = 0,
            )

        assertNotNull(resolved)
        assertEquals("observedMonotonicInterpolated", resolved!!.poseAlignment)
        assertEquals(2.0f, resolved.pose.position[0], 0.0001f)
        assertEquals(90_005_000_000L, resolved.sensorTimestampNs)
    }

    @Test
    fun `monotonic observation correlation accepts a bounded one sided nearest pose`() {
        val extractor = PoseDataExtractor()
        extractor.addSample(
            sample(
                timestampNs = 5_000_000_000L,
                observedTimestampNs = 1_000_000_000L,
            ),
        )

        val resolved =
            extractor.resolvePose(
                PoseDataExtractor.CaptureTiming(
                    sensorTimestampNs = 90_000_000_000L,
                    exposureTimeNs = 0L,
                    observedTimestampNs = 1_020_000_000L,
                ),
                waitForFuturePoseMs = 0,
            )

        assertNotNull(resolved)
        assertEquals("observedMonotonicNearest", resolved!!.poseAlignment)
        assertEquals(20_000_000L, resolved.poseTimeErrorNs)
    }

    @Test
    fun `uses a bounded nearest sample when no interpolation bracket exists`() {
        val extractor = PoseDataExtractor()
        extractor.addSample(sample(timestampNs = 1_000_000_000L, positionX = 3f))

        val resolved =
            extractor.resolvePose(
                PoseDataExtractor.CaptureTiming(
                    sensorTimestampNs = 1_020_000_000L,
                    exposureTimeNs = 0,
                ),
                waitForFuturePoseMs = 0,
            )

        assertNotNull(resolved)
        assertEquals("nearest", resolved!!.poseAlignment)
        assertEquals(20_000_000L, resolved.poseTimeErrorNs)
    }

    @Test
    fun `interpolates across still capture stall but rejects wider brackets`() {
        val accepted = PoseDataExtractor().apply {
            addSample(sample(timestampNs = 1_000_000_000L, positionX = 0f))
            addSample(sample(timestampNs = 1_300_000_000L, positionX = 10f))
        }
        val rejected = PoseDataExtractor().apply {
            addSample(sample(timestampNs = 1_000_000_000L, positionX = 0f))
            addSample(sample(timestampNs = 1_300_000_002L, positionX = 10f))
        }

        assertNotNull(
            accepted.resolvePose(
                PoseDataExtractor.CaptureTiming(
                    sensorTimestampNs = 1_150_000_000L,
                    exposureTimeNs = 0L,
                ),
                waitForFuturePoseMs = 0,
            ),
        )
        assertNull(
            rejected.resolvePose(
                PoseDataExtractor.CaptureTiming(
                    sensorTimestampNs = 1_150_000_001L,
                    exposureTimeNs = 0L,
                ),
                waitForFuturePoseMs = 0,
            ),
        )
    }

    @Test
    fun `does not extrapolate beyond nearest threshold`() {
        val extractor = PoseDataExtractor()
        extractor.addSample(sample(timestampNs = 1_000_000_000L))

        val resolved =
            extractor.resolvePose(
                PoseDataExtractor.CaptureTiming(
                    sensorTimestampNs = 1_150_000_001L,
                    exposureTimeNs = 0,
                ),
                waitForFuturePoseMs = 0,
            )

        assertNull(resolved)
    }

    @Test
    fun `ignores non tracking samples when resolving`() {
        val extractor = PoseDataExtractor()
        extractor.addSample(sample(timestampNs = 1_000_000_000L, isTracking = false))
        extractor.addSample(sample(timestampNs = 1_010_000_000L, positionX = 7f))

        val resolved =
            extractor.resolvePose(
                PoseDataExtractor.CaptureTiming(
                    sensorTimestampNs = 1_010_000_000L,
                    exposureTimeNs = 0,
                ),
                waitForFuturePoseMs = 0,
            )

        assertNotNull(resolved)
        assertEquals("exact", resolved!!.poseAlignment)
        assertEquals(7.0f, resolved.pose.position[0], 0.0001f)
    }

    @Test
    fun `waits briefly for a future tracked pose to allow interpolation`() {
        val extractor = PoseDataExtractor()
        extractor.addSample(sample(timestampNs = 1_000_000_000L, positionX = 0f))

        val thread =
            Thread {
                Thread.sleep(20)
                extractor.addSample(sample(timestampNs = 1_040_000_000L, positionX = 4f))
            }
        thread.start()

        val resolved =
            extractor.resolvePose(
                PoseDataExtractor.CaptureTiming(
                    sensorTimestampNs = 1_020_000_000L,
                    exposureTimeNs = 0,
                ),
                waitForFuturePoseMs = 100,
            )

        thread.join()

        assertNotNull(resolved)
        assertEquals("interpolated", resolved!!.poseAlignment)
        assertTrue(resolved.pose.position[0] > 1.9f && resolved.pose.position[0] < 2.1f)
    }

    @Test
    fun `toPoseMap converts GL camera axes to OpenCV and tags convention`() {
        val extractor = PoseDataExtractor()
        val alignedPose =
            extractor.toAlignedPose(
                pose =
                    sample(
                        timestampNs = 1_000_000_000L,
                        transform =
                            floatArrayOf(
                                1f, 0f, 0f, 0f,
                                0f, 1f, 0f, 0f,
                                0f, 0f, 1f, 0f,
                                3f, 4f, 5f, 1f,
                            ),
                    ),
            )

        val poseMap = extractor.toPoseMap(alignedPose)
        val rotation = poseMap["rotation"] as Map<*, *>
        val transform = poseMap["transform"] as List<*>

        assertEquals("opencv_c2w_v1", poseMap["convention"])
        assertEquals(1.0, rotation["x"] as Double, 0.0001)
        assertEquals(0.0, rotation["y"] as Double, 0.0001)
        assertEquals(0.0, rotation["z"] as Double, 0.0001)
        assertEquals(0.0, rotation["w"] as Double, 0.0001)
        assertEquals(-1.0, transform[5] as Double, 0.0001)
        assertEquals(-1.0, transform[10] as Double, 0.0001)
        assertEquals(3.0, transform[12] as Double, 0.0001)
        assertEquals(4.0, transform[13] as Double, 0.0001)
        assertEquals(5.0, transform[14] as Double, 0.0001)
    }

    private fun sample(
        timestampNs: Long,
        observedTimestampNs: Long = timestampNs,
        positionX: Float = 0f,
        isTracking: Boolean = true,
        transform: FloatArray? = null,
    ): PoseDataExtractor.CachedPose {
        val trackingState = if (isTracking) "tracking" else "paused"
        return PoseDataExtractor.CachedPose(
            position = floatArrayOf(positionX, 0f, 0f),
            rotationQuaternion = floatArrayOf(0f, 0f, 0f, 1f),
            transform =
                transform
                    ?: floatArrayOf(
                        1f, 0f, 0f, 0f,
                        0f, 1f, 0f, 0f,
                        0f, 0f, 1f, 0f,
                        positionX, 0f, 0f, 1f,
                    ),
            timestampNs = timestampNs,
            systemTimestampMs = timestampNs / 1_000_000L,
            isTracking = isTracking,
            confidence = if (isTracking) 1.0f else 0.0f,
            trackingState = trackingState,
            observedTimestampNs = observedTimestampNs,
        )
    }
}
