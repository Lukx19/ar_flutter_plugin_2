package com.uhg0.ar_flutter_plugin_2.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class PoseDataExtractorTest {
    @Test
    fun `retained history owns samples and old exposure snapshots survive eviction`() {
        val extractor = PoseDataExtractor(2)
        val original = sample(timestampNs = 1L, positionX = 2f)
        extractor.addSample(original)
        original.position[0] = 90f
        val held = extractor.latest()!!
        val aligned = extractor.resolvePose(PoseDataExtractor.CaptureTiming(1L, 0L), 0L)!!
        extractor.addSample(sample(timestampNs = 3L, positionX = 3f))
        extractor.addSample(sample(timestampNs = 2L, positionX = 4f))
        extractor.addSample(sample(timestampNs = 4L, positionX = 5f))
        assertEquals(2f, held.position[0], 0f)
        assertEquals(2f, aligned.pose.position[0], 0f)
        assertEquals(4L, extractor.latest()!!.timestampNs)
    }

    @Test
    fun `packed sample exactly preserves legacy geometry timestamps and independent ownership`() {
        val extractor = PoseDataExtractor(2)
        extractor.addSample(sample(timestampNs = 9_007_199_254_740_993L, positionX = 0.24f))
        val snapshot = extractor.latest()!!
        val map = extractor.toPoseMap(extractor.toAlignedPose(snapshot))
        val packed = extractor.latestPacked(7L)!!
        val buffer = ByteBuffer.wrap(packed).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(PackedPoseWireV2.SAMPLE_BYTES, packed.size)
        assertEquals(7L, buffer.getLong(0))
        assertEquals(snapshot.timestampNs, buffer.getLong(16))
        val converted = map["transform"] as List<*>
        for (i in 0..15) assertEquals(converted[i] as Double, buffer.getFloat(80 + i * 4).toDouble(), 0.0)
        for (i in 0..15) assertEquals(snapshot.transform[i], buffer.getFloat(172 + i * 4), 0f)
        val rotation = map["rotation"] as Map<*, *>
        for ((i, name) in listOf("x", "y", "z", "w").withIndex()) {
            assertEquals(rotation[name] as Double, buffer.getFloat(64 + i * 4).toDouble(), 0.0)
        }
        extractor.addSample(sample(timestampNs = snapshot.timestampNs + 1, positionX = 99f))
        extractor.latestPacked(8L)
        assertEquals(0.24f, buffer.getFloat(52), 0f)
    }

    @Test
    fun `finite debug camera phases use one tracked native pose for stream and exposure`() {
        val extractor = PoseDataExtractor()
        assertTrue(!extractor.beginDebugFixture())
        extractor.addSample(sample(timestampNs = 1L, positionX = 2f))
        assertTrue(extractor.beginDebugFixture())

        extractor.setDebugFixtureManualView()
        val manual = extractor.applyDebugFixture(
            sample(timestampNs = 2L, positionX = 30f, isTracking = false),
        )
        assertEquals(2.24f, manual.position[0], 0.0001f)
        assertEquals(2.24f, manual.transform[12], 0.0001f)
        assertTrue(manual.isTracking)
        assertEquals(2.24, extractor.debugFixtureTransform()!![12], 0.0001)

        extractor.setDebugFixtureAutomaticRevisit()
        val automatic = extractor.applyDebugFixture(
            sample(timestampNs = 3L, positionX = -50f, isTracking = false),
        )
        assertEquals(2f, automatic.position[0], 0.0001f)
        assertEquals(2f, automatic.transform[12], 0.0001f)
        extractor.clearDebugFixture()
        assertNull(extractor.debugFixtureTransform())
        assertEquals(
            -50f,
            extractor.applyDebugFixture(sample(timestampNs = 4L, positionX = -50f)).position[0],
            0.0001f,
        )
    }

    @Test
    fun `synthetic fixture is tagged bounded and cleared without a tracked anchor`() {
        val extractor = PoseDataExtractor()

        val seeded = extractor.beginSyntheticDebugFixture(
            bindingGeneration = 7L,
            groupGeneration = 11L,
        )

        assertTrue(seeded.isTracking)
        assertEquals(PoseDataExtractor.SYNTHETIC_POSE_SOURCE, seeded.poseSource)
        assertEquals(7L, seeded.fixtureBindingGeneration)
        assertEquals(11L, seeded.fixtureGroupGeneration)
        assertTrue(extractor.beginDebugFixture())
        assertEquals(
            PoseDataExtractor.SYNTHETIC_POSE_SOURCE,
            extractor.latest()!!.poseSource,
        )

        extractor.clearDebugFixture()

        assertNull(extractor.latest())
        assertTrue(!extractor.awaitTrackingPose(timeoutMs = 0L))
    }

    @Test
    fun `synthetic fixture rejects an ownership generation change`() {
        val extractor = PoseDataExtractor()
        extractor.beginSyntheticDebugFixture(
            bindingGeneration = 7L,
            groupGeneration = 11L,
        )

        var rejected = false
        try {
            extractor.beginSyntheticDebugFixture(
                bindingGeneration = 8L,
                groupGeneration = 11L,
            )
        } catch (_: IllegalStateException) {
            rejected = true
        }
        assertTrue(rejected)
    }

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
