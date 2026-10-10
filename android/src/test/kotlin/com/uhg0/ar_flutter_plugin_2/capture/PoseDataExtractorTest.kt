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
    fun `paired Android image timestamps preserve raw alignment time and owned samples`() {
        val extractor = PoseDataExtractor(capacity = 3)
        val older = sample(7_000_000_000L, observedTimestampNs = 5_000_000_000L,
            androidCameraTimestampNs = 9_000_000_000L)
        val newer = sample(7_000_007_611L, observedTimestampNs = 5_015_741_250L,
            positionX = 0.0001136969826f, androidCameraTimestampNs = 9_015_741_250L)
        extractor.addSample(older)
        extractor.addSample(newer)
        older.position[0] = 99f
        newer.position[0] = 99f
        val snapshot = checkNotNull(extractor.latest())
        var diagnostic: PoseDataExtractor.ExposureMotionDiagnostic? = null
        assertTrue(!extractor.isExposureMotionStable(5_020_000_000L) { diagnostic = it })
        val receipt = checkNotNull(diagnostic)
        assertEquals(PoseDataExtractor.ExposureMotionGate.INSUFFICIENT_DWELL, receipt.gate)
        assertNull(receipt.firstRefusingPair)
        assertEquals(7_611L, receipt.minimumPositiveCameraGapNs)
        assertEquals(0.0001136969826 / 0.015741250, checkNotNull(receipt.latestTranslationSpeedMps), 1e-8)
        assertEquals(7_000_007_611L, snapshot.timestampNs)
        assertEquals(9_015_741_250L, snapshot.androidCameraTimestampNs)
        assertEquals(0.0001136969826f, snapshot.position[0], 1e-10f)
        for (step in 1L..3L) extractor.addSample(sample(7_100_000_000L + step * 100_000_000L))
        assertEquals(9_015_741_250L, snapshot.androidCameraTimestampNs)
        assertEquals(0.0001136969826f, snapshot.position[0], 1e-10f)
    }

    @Test
    fun `unavailable Android image timestamp is retained without raw timestamp fallback`() {
        for (unavailable in listOf(0L, -1L)) {
            val extractor = PoseDataExtractor()
            extractor.addSample(sample(1_000_000_000L, androidCameraTimestampNs = unavailable))
            extractor.addSample(sample(1_000_007_611L, positionX = 0.001f,
                androidCameraTimestampNs = unavailable))
            var diagnostic: PoseDataExtractor.ExposureMotionDiagnostic? = null
            assertTrue(!extractor.isExposureMotionStable(1_000_007_611L) { diagnostic = it })
            val pair = checkNotNull(checkNotNull(diagnostic).firstRefusingPair)
            assertEquals(unavailable, pair.older.androidCameraTimestampNs)
            assertEquals(unavailable, pair.newer.androidCameraTimestampNs)
            assertEquals(1_000_000_000L, pair.older.cameraTimestampNs)
            assertEquals(1_000_007_611L, pair.newer.cameraTimestampNs)
        }
    }

    @Test
    fun `delayed callback cannot mask a real source timed fast step`() {
        for ((positionX, angleDegrees) in listOf(0.004f to 0.0, 0f to 0.2)) {
            val extractor = PoseDataExtractor()
            for (step in 0L..4L) extractor.addSample(sample(1_000_000_000L + step * 100_000_000L,
                observedTimestampNs = 3_000_000_000L + step * 100_000_000L,
                androidCameraTimestampNs = 2_000_000_000L + step * 100_000_000L))
            extractor.addSample(sample(1_410_000_000L, observedTimestampNs = 3_500_000_000L,
                positionX = positionX, angleDegrees = angleDegrees,
                androidCameraTimestampNs = 2_410_000_000L))
            var diagnostic: PoseDataExtractor.ExposureMotionDiagnostic? = null
            assertTrue(!extractor.isExposureMotionStable(3_504_250_729L) { diagnostic = it })
            assertTrue(!extractor.isExposureMotionStable(3_504_250_729L))
            val refusal = checkNotNull(diagnostic)
            assertEquals(PoseDataExtractor.ExposureMotionGate.MOVING_TOO_FAST, refusal.gate)
            val pair = checkNotNull(refusal.firstRefusingPair)
            assertEquals(10_000_000L, pair.cameraGapNs)
            assertEquals(100_000_000L, pair.observedGapNs)
            assertEquals(10_000_000L, pair.newer.androidCameraTimestampNs - pair.older.androidCameraTimestampNs)
        }
    }

    @Test
    fun `shutter decision observer executes after releasing pose history lock`() {
        val extractor = PoseDataExtractor()
        for (step in 0L..4L) extractor.addSample(sample(step * 100_000_000L))
        val ingested = java.util.concurrent.CountDownLatch(1)
        var frameThread: Thread? = null
        try {
            assertTrue(extractor.isExposureMotionStable(400_000_000L) {
                frameThread = Thread {
                    extractor.addSample(sample(500_000_000L))
                    ingested.countDown()
                }.also { it.start() }
                assertTrue("A diagnostic observer must not block concurrent pose ingestion",
                    ingested.await(1, java.util.concurrent.TimeUnit.SECONDS))
            })
        } finally {
            frameThread?.join(1000)
        }
    }

    @Test
    fun `shutter diagnostic identifies an older pose spike despite quiet latest speed and median`() {
        fun receipt(cameraGapNs: Long): PoseDataExtractor.ExposureMotionDiagnostic {
            val extractor = PoseDataExtractor()
            val cameraTimes = listOf(0L, 100_000_000L, 100_000_000L + cameraGapNs,
                200_000_000L + cameraGapNs, 300_000_000L + cameraGapNs, 400_000_000L + cameraGapNs)
            for ((index, timestamp) in cameraTimes.withIndex()) {
                extractor.addSample(sample(timestamp, observedTimestampNs = index * 100_000_000L,
                    positionX = if (index < 2) 0f else 0.01f, angleDegrees = if (index < 2) 0.0 else 0.1))
            }
            var diagnostic: PoseDataExtractor.ExposureMotionDiagnostic? = null
            val approved = extractor.isExposureMotionStable(500_000_000L) { diagnostic = it }
            assertEquals(cameraGapNs == 100_000_000L, approved)
            assertEquals(approved, extractor.isExposureMotionStable(500_000_000L))
            return checkNotNull(diagnostic)
        }

        val tinyGap = receipt(100L)
        assertEquals(PoseDataExtractor.ExposureMotionGate.MOVING_TOO_FAST, tinyGap.gate)
        assertEquals(0.0, checkNotNull(tinyGap.latestTranslationSpeedMps), 0.0)
        assertEquals(0.0, checkNotNull(tinyGap.latestAngularSpeedDegreesPerSecond), 0.0)
        assertEquals(0.0, checkNotNull(tinyGap.medianTranslationSpeedMps), 0.0)
        assertEquals(0.0, checkNotNull(tinyGap.medianAngularSpeedDegreesPerSecond), 0.0)
        assertEquals(100_000.0, checkNotNull(tinyGap.maximumTranslationSpeedMps), 0.01)
        assertEquals(1_000_000.0, checkNotNull(tinyGap.maximumAngularSpeedDegreesPerSecond), 1.0)
        assertEquals(300.0, tinyGap.stableDwellMs, 0.0)
        assertEquals(400.0, tinyGap.historySpanMs, 0.0)
        assertEquals(4, tinyGap.pairCount)
        assertEquals(100L, tinyGap.minimumPositiveCameraGapNs)
        assertEquals(0, tinyGap.duplicatePairCount)
        assertEquals(0L, tinyGap.timestampReadMismatchCount)
        val refusing = checkNotNull(tinyGap.firstRefusingPair)
        assertEquals(3, refusing.pairIndex)
        assertEquals(100L, refusing.cameraGapNs)
        assertEquals(100_000_000L, refusing.observedGapNs)
        assertEquals(0.01, checkNotNull(refusing.translationDeltaMeters), 1e-8)
        assertEquals(0.1, checkNotNull(refusing.angularDeltaDegrees), 1e-6)
        assertEquals(100_000_000L, refusing.older.cameraTimestampNs)
        assertEquals(100_000_100L, refusing.newer.cameraTimestampNs)
        assertTrue(refusing.older.isTracking && refusing.newer.isTracking)
        assertEquals("tracking", refusing.older.trackingState)
        assertEquals(PoseDataExtractor.LIVE_ANCHOR_POSE_SOURCE, refusing.newer.poseSource)
        assertNull(refusing.newer.fixtureBindingGeneration)
        assertNull(refusing.newer.fixtureGroupGeneration)
        assertNull(tinyGap.maximumAngularPair)

        val normalGap = receipt(100_000_000L)
        assertEquals(PoseDataExtractor.ExposureMotionGate.APPROVED, normalGap.gate)
        assertEquals(0.1, checkNotNull(normalGap.maximumTranslationSpeedMps), 1e-8)
        assertEquals(1.0, checkNotNull(normalGap.maximumAngularSpeedDegreesPerSecond), 1e-6)
        assertEquals(100_000_000L, normalGap.minimumPositiveCameraGapNs)
        assertNull(normalGap.firstRefusingPair)

        val ordinaryOlderSpike = PoseDataExtractor()
        for (step in 0L..4L) ordinaryOlderSpike.addSample(sample(step * 100_000_000L,
            positionX = if (step <= 1) 0f else 0.05f))
        var olderSpikeDiagnostic: PoseDataExtractor.ExposureMotionDiagnostic? = null
        assertTrue(!ordinaryOlderSpike.isExposureMotionStable(400_000_000L) { olderSpikeDiagnostic = it })
        assertEquals(0.0, checkNotNull(olderSpikeDiagnostic!!.latestTranslationSpeedMps), 0.0)
        assertEquals(0.0, checkNotNull(olderSpikeDiagnostic!!.medianTranslationSpeedMps), 0.0)
        assertEquals(100_000_000L, checkNotNull(olderSpikeDiagnostic!!.firstRefusingPair).cameraGapNs)
        assertTrue(checkNotNull(olderSpikeDiagnostic!!.maximumTranslationSpeedMps) > 0.25)

        // The gate's first rejecting pair need not own the angular maximum.
        val distinct = PoseDataExtractor()
        for (index in 0..5) {
            distinct.addSample(sample(index * 100_000_000L,
                observedTimestampNs = index * 100_000_000L,
                positionX = if (index == 5) 0.04f else 0.01f,
                angleDegrees = if (index >= 3) 4.0 else 0.0))
        }
        var diagnostic: PoseDataExtractor.ExposureMotionDiagnostic? = null
        assertTrue(!distinct.isExposureMotionStable(500_000_000L) { diagnostic = it })
        val result = checkNotNull(diagnostic)
        assertEquals(PoseDataExtractor.ExposureMotionGate.MOVING_TOO_FAST, result.gate)
        assertEquals(0, checkNotNull(result.firstRefusingPair).pairIndex)
        assertEquals(0.03, checkNotNull(result.firstRefusingPair!!.translationDeltaMeters), 1e-8)
        val maximumAngular = checkNotNull(result.maximumAngularPair)
        assertEquals(2, maximumAngular.pairIndex)
        assertEquals(100_000_000L, maximumAngular.cameraGapNs)
        assertEquals(100_000_000L, maximumAngular.observedGapNs)
        assertEquals(4.0, checkNotNull(maximumAngular.angularDeltaDegrees), 1e-5)
        assertEquals(PoseDataExtractor.LIVE_ANCHOR_POSE_SOURCE, maximumAngular.older.poseSource)
        assertEquals(PoseDataExtractor.LIVE_ANCHOR_POSE_SOURCE, maximumAngular.newer.poseSource)
        assertNull(maximumAngular.newer.fixtureBindingGeneration)
        assertNull(maximumAngular.newer.fixtureGroupGeneration)
    }

    @Test
    fun `shutter diagnostic separates stale tracking and missing dwell without changing verdict`() {
        val extractor = PoseDataExtractor()
        var diagnostic: PoseDataExtractor.ExposureMotionDiagnostic? = null
        assertTrue(!extractor.isExposureMotionStable(0L) { diagnostic = it })
        assertEquals(PoseDataExtractor.ExposureMotionGate.NO_POSE, diagnostic!!.gate)
        extractor.addSample(sample(0L))
        assertTrue(!extractor.isExposureMotionStable(0L) { diagnostic = it })
        assertEquals(PoseDataExtractor.ExposureMotionGate.INSUFFICIENT_DWELL, diagnostic!!.gate)
        assertTrue(!extractor.isExposureMotionStable(750_000_001L) { diagnostic = it })
        assertEquals(PoseDataExtractor.ExposureMotionGate.STALE, diagnostic!!.gate)
        extractor.addSample(sample(100_000_000L, isTracking = false))
        assertTrue(!extractor.isExposureMotionStable(100_000_000L) { diagnostic = it })
        assertEquals(PoseDataExtractor.ExposureMotionGate.NOT_TRACKING, diagnostic!!.gate)
    }

    @Test
    fun `exposure requires fresh tracked stable dwell and rejects the latest fast step`() {
        val extractor = PoseDataExtractor()
        for (time in 0L..4L) extractor.addSample(sample(timestampNs = time * 100_000_000L))
        assertTrue(extractor.isExposureMotionStable(400_000_000L))
        assertTrue(!extractor.isExposureMotionStable(1_150_000_001L))
        assertTrue(!extractor.isExposureMotionStable(399_999_999L))
        extractor.addSample(sample(timestampNs = 500_000_000L, positionX = 1f))
        assertTrue(!extractor.isExposureMotionStable(500_000_000L))
        var diagnostic: PoseDataExtractor.ExposureMotionDiagnostic? = null
        assertTrue(!extractor.isExposureMotionStable(500_000_000L) { diagnostic = it })
        assertEquals(PoseDataExtractor.ExposureMotionGate.MOVING_TOO_FAST, diagnostic!!.gate)
        assertEquals(0, checkNotNull(diagnostic!!.firstRefusingPair).pairIndex)
        assertEquals(100_000_000L, diagnostic!!.firstRefusingPair!!.cameraGapNs)
        assertEquals(1.0, checkNotNull(diagnostic!!.firstRefusingPair!!.translationDeltaMeters), 0.0)
        assertEquals(10.0, checkNotNull(diagnostic!!.maximumTranslationSpeedMps), 0.0)

        val rotating = PoseDataExtractor()
        for (time in 0L..4L) rotating.addSample(sample(time * 100_000_000L))
        rotating.addSample(sample(500_000_000L, angleDegrees = 10.0))
        assertTrue(!rotating.isExposureMotionStable(500_000_000L))
        assertTrue(!rotating.isExposureMotionStable(500_000_000L) { diagnostic = it })
        assertEquals(PoseDataExtractor.ExposureMotionGate.MOVING_TOO_FAST, diagnostic!!.gate)
        assertEquals(100_000_000L, diagnostic!!.firstRefusingPair!!.cameraGapNs)
        assertEquals(10.0, checkNotNull(diagnostic!!.firstRefusingPair!!.angularDeltaDegrees), 1e-5)
        assertEquals(100.0, checkNotNull(diagnostic!!.maximumAngularSpeedDegreesPerSecond), 1e-4)
    }

    @Test
    fun `exposure does not inherit stable history across tracking loss`() {
        val extractor = PoseDataExtractor()
        for (time in 0L..4L) extractor.addSample(sample(timestampNs = time * 100_000_000L))
        extractor.addSample(sample(timestampNs = 500_000_000L, isTracking = false))
        assertTrue(!extractor.isExposureMotionStable(500_000_000L))
    }

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
    fun `synthetic head view updates photo position rotation and preserves generation tags`() {
        val extractor = PoseDataExtractor()
        extractor.beginSyntheticDebugFixture(7L, 11L)
        val transform = doubleArrayOf(0.0,0.0,-1.0,0.0, 0.0,1.0,0.0,0.0, 1.0,0.0,0.0,0.0, .2,.1,.05,1.0)
        val quaternion = floatArrayOf(0f, .70710677f, 0f, .70710677f)
        extractor.setSyntheticDebugView(transform, quaternion, 7L, 11L)
        transform[12] = 99.0
        quaternion[1] = 0f
        val latest = checkNotNull(extractor.latest())
        assertEquals(.2f, latest.position[0], 1e-6f)
        assertEquals(.2f, latest.transform[12], 1e-6f)
        assertEquals(.70710677f, latest.rotationQuaternion[1], 1e-6f)
        assertEquals(PoseDataExtractor.SYNTHETIC_POSE_SOURCE, latest.poseSource)
        assertEquals(7L, latest.fixtureBindingGeneration)
        assertEquals(11L, latest.fixtureGroupGeneration)
    }

    @Test
    fun `stale synthetic head view cannot change a replacement generation`() {
        val extractor = PoseDataExtractor()
        extractor.beginSyntheticDebugFixture(7L, 11L)
        var rejected = false
        try {
            extractor.setSyntheticDebugView(DoubleArray(16), floatArrayOf(0f,0f,0f,1f), 8L, 11L)
        } catch (_: IllegalStateException) { rejected = true }
        assertTrue(rejected)
        assertEquals(0f, checkNotNull(extractor.latest()).position[0], 0f)
        extractor.clearDebugFixture()
        rejected = false
        try {
            extractor.setSyntheticDebugView(DoubleArray(16), floatArrayOf(0f,0f,0f,1f), 7L, 11L)
        } catch (_: IllegalStateException) { rejected = true }
        assertTrue(rejected)
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
        for (capacity in listOf(3, 120)) {
            val extractor = PoseDataExtractor(capacity)
            val reference = mutableListOf<PoseDataExtractor.CachedPose>()
            // Inspect complete copied snapshots so equal timestamps cannot hide
            // slot aliasing behind the nearest-pose resolver's one selected pose.
            @Suppress("UNCHECKED_CAST")
            val history = extractor.javaClass.getDeclaredField("poses").let { field ->
                field.isAccessible = true
                field.get(extractor) as List<PoseDataExtractor.CachedPose>
            }
            fun assertHistory() {
                assertEquals(reference.size, history.size)
                for (index in reference.indices) {
                    val expected = reference[index]
                    val actual = history[index]
                    assertEquals(expected.timestampNs, actual.timestampNs)
                    assertEquals(expected.observedTimestampNs, actual.observedTimestampNs)
                    assertEquals(expected.systemTimestampMs, actual.systemTimestampMs)
                    assertEquals(expected.confidence, actual.confidence, 0f)
                    assertEquals(expected.position.toList(), actual.position.toList())
                    assertEquals(expected.rotationQuaternion.toList(), actual.rotationQuaternion.toList())
                    assertEquals(expected.transform.toList(), actual.transform.toList())
                    assertEquals(expected.isTracking, actual.isTracking)
                    assertEquals(expected.trackingState, actual.trackingState)
                    assertEquals(expected.poseSource, actual.poseSource)
                    assertEquals(expected.fixtureBindingGeneration, actual.fixtureBindingGeneration)
                    assertEquals(expected.fixtureGroupGeneration, actual.fixtureGroupGeneration)
                }
            }
            var observedSequence = 0L
            fun insert(timestampNs: Long, marker: Int) {
                val original = sample(timestampNs, observedTimestampNs = ++observedSequence * 10_000_000L,
                    positionX = marker.toFloat(), angleDegrees = marker.toDouble()).copy(
                        poseSource = PoseDataExtractor.SYNTHETIC_POSE_SOURCE,
                        fixtureBindingGeneration = marker.toLong() + 1L,
                        fixtureGroupGeneration = 11L,
                    )
                val copied = original.copy(position = original.position.clone(),
                    rotationQuaternion = original.rotationQuaternion.clone(), transform = original.transform.clone())
                val insertionIndex = reference.indexOfFirst { it.timestampNs > timestampNs }
                    .let { if (it < 0) reference.size else it }
                reference.add(insertionIndex, copied)
                if (reference.size > capacity) reference.removeAt(0)
                extractor.addSample(original)
                original.position.fill(900f)
                original.rotationQuaternion.fill(900f)
                original.transform.fill(900f)
                assertHistory()
            }

            for (index in 1..capacity) insert(index * 100_000_000L, index)
            val retainedSnapshots = history.map { it }
            val retainedPositions = retainedSnapshots.map { it.position.toList() }
            val retainedRotations = retainedSnapshots.map { it.rotationQuaternion.toList() }
            val retainedTransforms = retainedSnapshots.map { it.transform.toList() }
            // Full history: interior insertion, rejected old input, equal latest
            // camera times, then ordinary append eviction all use the same oracle.
            insert(capacity * 100_000_000L - 50_000_000L, capacity + 1)
            insert(0L, capacity + 2)
            insert(capacity * 100_000_000L, capacity + 3)
            var diagnostic: PoseDataExtractor.ExposureMotionDiagnostic? = null
            assertTrue(!extractor.isExposureMotionStable(observedSequence * 10_000_000L) { diagnostic = it })
            val result = checkNotNull(diagnostic)
            assertEquals(PoseDataExtractor.ExposureMotionGate.INVALID_POSE, result.gate)
            assertEquals(0, checkNotNull(result.firstRefusingPair).pairIndex)
            assertEquals(0L, result.firstRefusingPair!!.cameraGapNs)
            assertEquals(30_000_000L, result.firstRefusingPair!!.observedGapNs)
            assertEquals(capacity.toLong() + 4L, result.firstRefusingPair!!.newer.fixtureBindingGeneration)
            insert((capacity + 1) * 100_000_000L, capacity + 4)
            insert((capacity + 2) * 100_000_000L, capacity + 5)
            for (index in retainedSnapshots.indices) {
                assertEquals(retainedPositions[index], retainedSnapshots[index].position.toList())
                assertEquals(retainedRotations[index], retainedSnapshots[index].rotationQuaternion.toList())
                assertEquals(retainedTransforms[index], retainedSnapshots[index].transform.toList())
            }
            history[0].also { escaped ->
                escaped.position.fill(-900f)
                escaped.rotationQuaternion.fill(-900f)
                escaped.transform.fill(-900f)
            }
            assertHistory()
            val latestTimestampNs = (capacity + 2) * 100_000_000L
            val resolved = extractor.resolvePose(PoseDataExtractor.CaptureTiming(latestTimestampNs, 0L), 0L)
            assertNotNull(resolved)
            assertEquals(latestTimestampNs, resolved!!.pose.timestampNs)
            assertEquals(reference.last().position.toList(), resolved.pose.position.toList())
            assertNull(extractor.resolvePose(PoseDataExtractor.CaptureTiming(latestTimestampNs + 300_000_000L, 0L), 0L))
        }
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

}

private fun sample(
    timestampNs: Long,
    observedTimestampNs: Long = timestampNs,
    positionX: Float = 0f,
    isTracking: Boolean = true,
    transform: FloatArray? = null,
    angleDegrees: Double = 0.0,
    androidCameraTimestampNs: Long = 10_000_000_000L + timestampNs,
): PoseDataExtractor.CachedPose {
    val halfAngle = Math.toRadians(angleDegrees) / 2
    val trackingState = if (isTracking) "tracking" else "paused"
    return PoseDataExtractor.CachedPose(
        position = floatArrayOf(positionX, 0f, 0f),
        rotationQuaternion = floatArrayOf(0f, kotlin.math.sin(halfAngle).toFloat(), 0f, kotlin.math.cos(halfAngle).toFloat()),
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
        androidCameraTimestampNs = androidCameraTimestampNs,
    )
}

/** Distinct Android image motion and fail-closed ingress continuity contract.
 * Supplied Android times are an explicit synthetic premise, not device evidence. */
class PoseImageClockContractTest {
    @Test
    fun `qualified image clock approves small correction across 7611 ns raw gap`() {
        assertQualifiedSmallCorrection(7_611L)
    }

    @Test
    fun `qualified image clock approves small correction across 1 ns raw gap`() {
        assertQualifiedSmallCorrection(1L)
    }

    private fun assertQualifiedSmallCorrection(rawGapNs: Long) {
        val rawOffsets = listOf(0L, 100_000_000L, 200_000_000L,
            200_000_000L + rawGapNs, 300_000_000L + rawGapNs, 400_000_000L + rawGapNs)
        val imageOffsets = listOf(0L, 100_000_000L, 200_000_000L,
            215_741_250L, 315_741_250L, 415_741_250L)
        val extractor = PoseDataExtractor()
        for (index in rawOffsets.indices) {
            extractor.addSample(sample(1_000_000_000L + rawOffsets[index],
                observedTimestampNs = 3_000_000_000L + imageOffsets[index],
                positionX = if (index < 3) 0f else 0.0001136969826f,
                angleDegrees = if (index < 3) 0.0 else 0.0013061699,
                androidCameraTimestampNs = 2_000_000_000L + imageOffsets[index]))
        }
        var diagnostic: PoseDataExtractor.ExposureMotionDiagnostic? = null
        val approved = extractor.isExposureMotionStable(3_419_991_979L) { diagnostic = it }
        assertTrue("Qualified distinct images span 415741250 ns; tiny raw gap must not amplify the quiet correction: ${diagnostic?.gate}", approved)
        assertTrue(extractor.isExposureMotionStable(3_419_991_979L))
    }

    @Test
    fun `repeated image identity cannot earn stable dwell`() {
        val repeated = PoseDataExtractor()
        for (step in 0L..5L) repeated.addSample(sample(1_000_000_000L + step * 100_000_000L,
            observedTimestampNs = 3_000_000_000L + step * 100_000_000L,
            androidCameraTimestampNs = 2_000_000_000L))
        assertTrue("Repeated physical images cannot earn stable dwell", !repeated.isExposureMotionStable(3_500_000_000L))
    }

    @Test
    fun `repeated image identity cannot refresh freshness`() {
        val stale = quietHistory()
        stale.addSample(sample(1_500_000_000L, observedTimestampNs = 4_150_000_001L,
            androidCameraTimestampNs = 2_400_000_000L))
        assertTrue("Re-delivery cannot refresh the last distinct image", !stale.isExposureMotionStable(4_150_000_001L))
    }

    @Test
    fun `unavailable image clock refuses qualified motion`() {
        for (imageTime in listOf(0L, -1L)) {
            val extractor = quietHistory()
            extractor.addSample(sample(1_500_000_000L, observedTimestampNs = 3_500_000_000L,
                androidCameraTimestampNs = imageTime))
            assertTrue("Invalid/reordered image identity needs a new continuous dwell: $imageTime",
                !extractor.isExposureMotionStable(3_500_000_000L))
        }
    }

    @Test
    fun `regressing image identity requires a new continuous dwell`() {
        val extractor = quietHistory()
        extractor.addSample(sample(1_500_000_000L, observedTimestampNs = 3_500_000_000L,
            androidCameraTimestampNs = 2_300_000_000L))
        assertTrue(!extractor.isExposureMotionStable(3_500_000_000L))
    }

    @Test
    fun `nonincreasing ingress time breaks qualified motion continuity`() {
        for (arrivalTime in listOf(3_400_000_000L, 3_399_999_999L)) {
            val extractor = quietHistory()
            extractor.addSample(sample(1_500_000_000L, observedTimestampNs = arrivalTime,
                androidCameraTimestampNs = 2_500_000_000L))
            assertTrue(!extractor.isExposureMotionStable(3_400_000_000L))
        }
    }

    @Test
    fun `stale delivery break cannot contribute quiet dwell`() {
        val extractor = quietHistory()
        for (step in 0L..3L) extractor.addSample(sample(1_410_000_000L + step * 100_000_000L,
            observedTimestampNs = 4_150_000_001L + step * 100_000_000L,
            androidCameraTimestampNs = 2_410_000_000L + step * 100_000_000L))
        assertTrue("Only 300 ms of new continuous observations follows the stale break",
            !extractor.isExposureMotionStable(4_450_000_001L))
    }

    @Test
    fun `tracking and owner changes require a new continuous dwell`() {
        val tracking = quietHistory()
        tracking.addSample(sample(1_500_000_000L, observedTimestampNs = 3_500_000_000L,
            isTracking = false, androidCameraTimestampNs = 2_500_000_000L))
        for (step in 6L..9L) tracking.addSample(sample(1_000_000_000L + step * 100_000_000L,
            observedTimestampNs = 3_000_000_000L + step * 100_000_000L,
            androidCameraTimestampNs = 2_000_000_000L + step * 100_000_000L))
        assertTrue(!tracking.isExposureMotionStable(3_900_000_000L))

        val replaced = quietHistory(bindingGeneration = 1L)
        for (step in 5L..8L) replaced.addSample(sample(1_000_000_000L + step * 100_000_000L,
            observedTimestampNs = 3_000_000_000L + step * 100_000_000L,
            androidCameraTimestampNs = 2_000_000_000L + step * 100_000_000L)
            .copy(fixtureBindingGeneration = 2L))
        assertTrue(!replaced.isExposureMotionStable(3_800_000_000L))
    }

    @Test
    fun `qualified motion retains exact dwell freshness and interruption boundaries`() {
        val extractor = quietHistory()
        assertTrue(extractor.isExposureMotionStable(4_150_000_000L))
        assertTrue(!extractor.isExposureMotionStable(4_150_000_001L))
        val short = PoseDataExtractor()
        for (offset in listOf(0L, 100_000_000L, 200_000_000L, 300_000_000L, 399_999_999L)) {
            short.addSample(sample(1_000_000_000L + offset, observedTimestampNs = 3_000_000_000L + offset,
                androidCameraTimestampNs = 2_000_000_000L + offset))
        }
        assertTrue(!short.isExposureMotionStable(3_399_999_999L))
        short.addSample(sample(1_400_000_000L, observedTimestampNs = 3_400_000_000L,
            androidCameraTimestampNs = 2_400_000_000L))
        assertTrue(short.isExposureMotionStable(3_400_000_000L))
        val interrupted = quietHistory()
        interrupted.addSample(sample(2_400_000_001L, observedTimestampNs = 4_400_000_001L,
            androidCameraTimestampNs = 3_400_000_001L))
        assertTrue(!interrupted.isExposureMotionStable(4_400_000_001L))
    }

    @Test
    fun `qualified image clock never approves invalid pose or quaternion`() {
        for (invalid in listOf(
            sample(1_500_000_000L, observedTimestampNs = 3_500_000_000L,
                positionX = Float.NaN, androidCameraTimestampNs = 2_500_000_000L),
            sample(1_500_000_000L, observedTimestampNs = 3_500_000_000L,
                androidCameraTimestampNs = 2_500_000_000L).copy(rotationQuaternion = FloatArray(4)),
        )) {
            val extractor = quietHistory()
            extractor.addSample(invalid)
            assertTrue(!extractor.isExposureMotionStable(3_500_000_000L))
        }
    }

    @Test
    fun `same image pose revision invalidates approved dwell until new distinct image dwell`() {
        for ((positionX, angleDegrees) in listOf(0.0014481076f to 0.0, 0f to 0.0013208869)) {
            val extractor = quietHistory()
            assertTrue(extractor.isExposureMotionStable(3_400_000_000L))
            extractor.addSample(sample(1_400_000_716L, observedTimestampNs = 3_421_121_406L,
                positionX = positionX, angleDegrees = angleDegrees, androidCameraTimestampNs = 2_400_000_000L))
            var receipt: PoseDataExtractor.ExposureMotionDiagnostic? = null
            assertTrue(!extractor.isExposureMotionStable(3_421_121_406L) { receipt = it })
            assertEquals(PoseDataExtractor.ExposureMotionGate.INVALID_POSE, receipt!!.gate)
            val pair = checkNotNull(receipt!!.firstRefusingPair)
            assertEquals(716L, pair.cameraGapNs)
            assertEquals(pair.older.androidCameraTimestampNs, pair.newer.androidCameraTimestampNs)
            assertNull(receipt!!.latestTranslationSpeedMps)
            assertNull(receipt!!.latestAngularSpeedDegreesPerSecond)
            // Further revisions (including restoration of the original pose) cannot reseed.
            for (step in 0L..5L) {
                extractor.addSample(sample(1_500_000_000L + step * 100_000_000L,
                    observedTimestampNs = 3_500_000_000L + step * 100_000_000L,
                    positionX = if (step % 2L == 0L) positionX else 0f,
                    angleDegrees = if (step % 2L == 0L) angleDegrees else 0.0,
                    androidCameraTimestampNs = 2_400_000_000L))
                assertTrue(!extractor.isExposureMotionStable(3_500_000_000L + step * 100_000_000L))
            }
            for (offset in listOf(0L, 100_000_000L, 200_000_000L, 300_000_000L, 399_999_999L)) {
                extractor.addSample(sample(2_100_000_000L + offset,
                    observedTimestampNs = 4_100_000_000L + offset,
                    androidCameraTimestampNs = 2_500_000_000L + offset))
            }
            assertTrue(!extractor.isExposureMotionStable(4_499_999_999L))
            extractor.addSample(sample(2_500_000_000L, observedTimestampNs = 4_500_000_000L,
                androidCameraTimestampNs = 2_900_000_000L))
            assertTrue(extractor.isExposureMotionStable(4_500_000_000L))
        }
    }

    @Test
    fun `unchanged duplicate retains original image freshness and earns no dwell`() {
        val extractor = quietHistory()
        for (step in 1L..7L) extractor.addSample(sample(1_400_000_000L + step * 100_000_000L,
            observedTimestampNs = 3_400_000_000L + step * 100_000_000L,
            androidCameraTimestampNs = 2_400_000_000L))
        assertTrue(extractor.isExposureMotionStable(4_150_000_000L))
        assertTrue(!extractor.isExposureMotionStable(4_150_000_001L))
        val short = PoseDataExtractor()
        short.addSample(sample(1_000_000_000L, observedTimestampNs = 3_000_000_000L,
            androidCameraTimestampNs = 2_000_000_000L))
        short.addSample(sample(1_001_000_000L, observedTimestampNs = 3_400_000_000L,
            androidCameraTimestampNs = 2_000_000_000L))
        assertTrue(!short.isExposureMotionStable(3_400_000_000L))
    }

    @Test
    fun `burst delivered distinct images cannot borrow source span for observed dwell`() {
        val extractor = PoseDataExtractor()
        for (step in 0L..4L) extractor.addSample(sample(1_000_000_000L + step * 100_000_000L,
            observedTimestampNs = 3_000_000_000L + step,
            androidCameraTimestampNs = 2_000_000_000L + step * 100_000_000L))
        assertTrue(!extractor.isExposureMotionStable(3_000_000_004L))
    }

    @Test
    fun `invalid duplicate tracking owner geometry or ingress cannot preserve approval`() {
        for (variant in 0..5) {
            val extractor = quietHistory(bindingGeneration = 1L)
            val duplicate = sample(1_500_000_000L, observedTimestampNs = 3_500_000_000L,
                androidCameraTimestampNs = 2_400_000_000L).copy(fixtureBindingGeneration = 1L)
            extractor.addSample(when (variant) {
                0 -> duplicate.copy(isTracking = false)
                1 -> duplicate.copy(fixtureBindingGeneration = 2L)
                2 -> duplicate.copy(position = floatArrayOf(Float.NaN, 0f, 0f))
                3 -> duplicate.copy(rotationQuaternion = FloatArray(4))
                4 -> duplicate.copy(observedTimestampNs = 3_400_000_000L)
                else -> duplicate.copy(poseSource = PoseDataExtractor.SYNTHETIC_POSE_SOURCE)
            })
            assertTrue("Duplicate validity must precede suppression: $variant", !extractor.isExposureMotionStable(3_500_000_000L))
        }
    }

    @Test
    fun `raw discarded ingress interruption still breaks motion continuity`() {
        val extractor = PoseDataExtractor(capacity = 5)
        for (step in 0L..4L) extractor.addSample(sample(1_000_000_000L + step * 100_000_000L,
            observedTimestampNs = 3_000_000_000L + step * 100_000_000L,
            androidCameraTimestampNs = 2_000_000_000L + step * 100_000_000L))
        assertTrue(extractor.isExposureMotionStable(3_400_000_000L))
        extractor.addSample(sample(1L, observedTimestampNs = 3_500_000_000L,
            androidCameraTimestampNs = 2_300_000_000L))
        assertEquals(1_400_000_000L, checkNotNull(extractor.latest()).timestampNs)
        assertTrue(!extractor.isExposureMotionStable(3_500_000_000L))
        for (step in 0L..3L) extractor.addSample(sample(2_000_000_000L + step * 100_000_000L,
            observedTimestampNs = 3_600_000_000L + step * 100_000_000L,
            androidCameraTimestampNs = 2_500_000_000L + step * 100_000_000L))
        assertTrue(!extractor.isExposureMotionStable(3_900_000_000L))
        extractor.addSample(sample(2_400_000_000L, observedTimestampNs = 4_000_000_000L,
            androidCameraTimestampNs = 2_900_000_000L))
        assertTrue(extractor.isExposureMotionStable(4_000_000_000L))
    }

    private fun quietHistory(bindingGeneration: Long? = null): PoseDataExtractor = PoseDataExtractor().also { extractor ->
        for (step in 0L..4L) extractor.addSample(sample(1_000_000_000L + step * 100_000_000L,
            observedTimestampNs = 3_000_000_000L + step * 100_000_000L,
            androidCameraTimestampNs = 2_000_000_000L + step * 100_000_000L)
            .copy(fixtureBindingGeneration = bindingGeneration))
    }
}
