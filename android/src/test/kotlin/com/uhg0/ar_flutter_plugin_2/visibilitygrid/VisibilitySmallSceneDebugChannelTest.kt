package com.uhg0.ar_flutter_plugin_2.visibilitygrid

import io.flutter.plugin.common.MethodChannel
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.roundToInt
import kotlin.math.sqrt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VisibilitySmallSceneDebugChannelTest {
    @Test
    fun `synthetic pool receipt waits for native lane ownership to drain`() {
        val cut = ownership()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val runtime = AndroidVisibilityGridRuntime(
            ownership = { cut },
            mapper = object : VisibilityObservationMapper {
                override fun admitFeature(observation: VisibilityFeatureObservation) {
                    entered.countDown()
                    check(release.await(2, TimeUnit.SECONDS)) {
                        "feature mapper did not receive the release fence"
                    }
                    observation.close()
                }

                override fun admitDepth(observation: VisibilityDepthObservation) =
                    observation.close()
            },
            scheduler = Executors.newSingleThreadScheduledExecutor(),
            ownsScheduler = true,
        )
        val source = SyntheticVisibilityObservationSource(runtime) { cut }
        val receiptReader = Executors.newSingleThreadExecutor()
        try {
            source.setDepthCapability(VisibilityDepthCapability.AUTOMATIC)
            assertTrue(source.emitFeature(1_000_000_000L, marker = 0))
            assertTrue(entered.await(2, TimeUnit.SECONDS))

            val receipt = receiptReader.submit<SampleLeasePoolReceipt> {
                source.awaitSyntheticIdle()
                source.packedLeaseReceipts().getValue("feature")
            }
            Thread.sleep(100)
            assertFalse(receipt.isDone)
            release.countDown()
            assertEquals(0, receipt.get(2, TimeUnit.SECONDS).outstanding)
        } finally {
            release.countDown()
            source.close()
            receiptReader.shutdownNow()
            runtime.close()
        }
    }

    @Test
    fun `dense campaign preserves twenty distinct measured views after five warm views`() {
        val cut = ownership()
        val mapper = RecordingVisibilityMapper()
        val runtime = AndroidVisibilityGridRuntime(
            ownership = { cut }, mapper = mapper,
            scheduler = Executors.newScheduledThreadPool(2), depthIntervalNs = 1,
        )
        val source = SyntheticVisibilityObservationSource(runtime) { cut }
        try {
            source.setDepthCapability(VisibilityDepthCapability.AUTOMATIC)
            source.prepareDenseDepthGrids(campaignVariants = true)
            repeat(25) { index ->
                assertTrue(source.emitCampaignFeatureFrame(1_000_000_000L + index * 3_000_000_000L, index))
                assertTrue(source.emitDenseDepthGrid(1_000_000_000L + index * 3_000_000_000L, index, campaignVariants = true))
                runtime.awaitDebugFixtureIdle()
            }
            val stablePoints = HashMap<Int, VisibilityFeatureSample>()
            assertEquals(25, mapper.features.size)
            mapper.features.forEachIndexed { index, feature ->
                val cameraX = feature.frame.pose.worldFromCameraGl[12]
                assertEquals(mapper.depths[index].frame.pose.worldFromCameraGl, feature.frame.pose.worldFromCameraGl)
                assertEquals(5, feature.samples.size)
                assertEquals(5, feature.samples.map { it.id }.toSet().size)
                feature.samples.forEach { sample ->
                    stablePoints.putIfAbsent(sample.id, sample)?.let { assertEquals(it, sample) }
                    val depth = -sample.zWorld
                    val pixelX = 640 + 2_000 * (sample.xWorld - cameraX) / depth
                    val pixelY = 480 - 2_000 * sample.yWorld / depth
                    assertTrue(pixelX in 0.0..1279.0 && pixelY in 0.0..959.0)
                    assertEquals(1.0, depth, 0.000001)
                    val patchCrossingY = sample.yWorld * 0.75 / depth
                    assertTrue("foreground must not occlude $sample", patchCrossingY > 0.10)
                }
            }
            val positions = mapper.depths.map { it.frame.pose.worldFromCameraGl[12] }
            assertEquals(25, positions.distinct().size)
            assertEquals(-0.48, positions.first(), 0.000001)
            assertEquals(-0.40, positions[4], 0.000001)
            assertEquals(-0.38, positions[5], 0.000001)
            assertEquals(0.38, positions.last(), 0.000001)
            assertTrue(positions.last() - positions.first() < 1.0)
            for (observation in mapper.depths) {
                assertEquals(4_096, observation.samples.size)
                val cameraX = observation.frame.pose.worldFromCameraGl[12]
                observation.samples.forEach { sample ->
                    val inPatch = abs((sample.x - 640) / 2_000.0 * 0.75 + cameraX) < 0.10 &&
                        abs((sample.y - 480) / 2_000.0 * 0.75) < 0.10
                    assertEquals(if (inPatch) 750 else 1_000, sample.depthMillimeters)
                    assertEquals(255, sample.confidence)
                }
            }
            org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
                source.emitDenseDepthGrid(99_000_000_000L, 25, campaignVariants = true)
            }
        } finally {
            source.close()
            runtime.close()
        }
    }

    @Test
    fun `complete campaign feature frames settle under permutations and whole frame coalescing`() {
        val cut = ownership()
        val mapper = RecordingVisibilityMapper()
        val runtime = AndroidVisibilityGridRuntime(ownership = { cut }, mapper = mapper,
            featureIntervalNs = 1)
        val source = SyntheticVisibilityObservationSource(runtime) { cut }
        val pool = FeatureSamplesLeasePool(capacity = 5)
        fun permutations(values: List<Int>): List<List<Int>> = if (values.isEmpty()) listOf(emptyList()) else
            values.flatMap { head -> permutations(values - head).map { listOf(head) + it } }
        val orders = permutations((0..4).toList())
        try {
            source.setDepthCapability(VisibilityDepthCapability.AUTOMATIC)
            source.prepareDenseDepthGrids(campaignVariants = true)
            for (variant in listOf(0, 5, 12, 24)) {
                assertTrue(source.emitCampaignFeatureFrame((variant + 1L) * 1_000_000_000L, variant))
                runtime.awaitDebugFixtureIdle()
                val recorded = requireNotNull(mapper.feature)
                val kernel = FeatureFusionKernel()
                var sequence = 0L
                var materialFrames = 0
                for (frameIndex in 0 until orders.size + 8) {
                    // Dropping complete source frames advances time and sequence without
                    // changing the five-point evidence distribution of an admitted frame.
                    sequence += if (frameIndex % 3 == 0) 5L else 1L
                    val lease = requireNotNull(pool.tryAcquire(cut))
                    for (slot in orders[frameIndex % orders.size]) {
                        val point = recorded.samples[slot]
                        assertTrue(lease.acceptId(point.id))
                        assertTrue(lease.append(point.id, point.xWorld, point.yWorld, point.zWorld, point.confidence))
                    }
                    val observation = VisibilityFeatureObservation.fromPacked(cut,
                        recorded.frame.copy(sourceTimestampNs = sequence * 125_000_000L), lease.samples, 0)
                    try {
                        val result = kernel.preparePacked(observation, sequence) as FeatureFusionResult.Accepted
                        if (result.delta.isNotEmpty()) materialFrames++
                        if (frameIndex >= 8) assertTrue("variant=$variant frame=$frameIndex delta=${result.delta}", result.delta.isEmpty())
                        assertTrue(kernel.prepareCanonicalApplication(emptyList()))
                        kernel.applyPrepared()
                    } finally {
                        observation.close()
                    }
                }
                assertTrue("fixture must exercise real material fusion", materialFrames > 0)
            }
            assertEquals(0, pool.leasedCount())
        } finally {
            source.close()
            runtime.close()
            pool.close()
        }
    }

    @Test
    fun `dense depth grids copy primitive leased samples and preserve a fixed foreground patch`() {
        val cut = ownership()
        val mapper = RecordingVisibilityMapper()
        val runtime = AndroidVisibilityGridRuntime(
            ownership = { cut },
            mapper = mapper,
            scheduler = Executors.newScheduledThreadPool(2),
            depthIntervalNs = 1,
            ownsScheduler = true,
        )
        val source = SyntheticVisibilityObservationSource(runtime) { cut }
        try {
            source.setDepthCapability(VisibilityDepthCapability.AUTOMATIC)
            source.prepareDenseDepthGrids()
            for (index in 0..5) {
                assertTrue(source.emitDenseDepthGrid(1_000_000_000L + index * 3_000_000_000L, index % 5))
                runtime.awaitDebugFixtureIdle()
            }
            assertEquals(6, mapper.depths.size)
            for (observation in mapper.depths) {
                assertEquals(4_096, observation.samples.size)
                assertTrue(observation.samples.all { it.confidence == 255 })
                val cameraX = observation.frame.pose.worldFromCameraGl[12]
                assertTrue(abs(cameraX) <= 0.08)
                val patch = observation.samples.filter { it.depthMillimeters == 750 }
                assertTrue(patch.isNotEmpty())
                assertTrue(patch.all {
                    abs((it.x - 640) / 2_000.0 * 0.75 + cameraX) < 0.10 &&
                        abs((it.y - 480) / 2_000.0 * 0.75) < 0.10
                })
            }
            // The mapper copies primitive lease values before closing each observation.
            val first = mapper.depths.first().samples
            val revisit = mapper.depths.last().samples
            assertFalse(first === revisit)
            assertTrue(first.all { it.confidence == 255 })
            assertTrue(revisit.all { it.confidence == 255 })
            assertTrue(first.any { it.depthMillimeters == 750 })
            assertTrue(revisit.any { it.depthMillimeters == 750 })
        } finally {
            runtime.close()
        }
        // The history remains valid after the producer leases have drained.
        assertEquals(4_096, mapper.depths.first().samples.size)
        assertEquals(4_096, mapper.depths.last().samples.size)
    }

    @Test
    fun `synthetic sphere sweep covers every viewing sector within a one metre camera volume`() {
        val cut = ownership(identityMatrix(), identityMatrix())
        val mapper = RecordingVisibilityMapper()
        val runtime = AndroidVisibilityGridRuntime(
            ownership = { cut },
            mapper = mapper,
            scheduler = Executors.newScheduledThreadPool(2),
            featureIntervalNs = 1,
            depthIntervalNs = 1,
            ownsScheduler = true,
        )
        val source = SyntheticVisibilityObservationSource(runtime) { cut }
        val seen = HashSet<Int>()
        try {
            source.anchor(identityMatrix(), cut.groupFrame)
            source.setDepthCapability(VisibilityDepthCapability.AUTOMATIC)
            for (view in 0 until SYNTHETIC_SPHERE_VIEW_COUNT) {
                val timestamp = 1_000_000_000L + view * 1_000_000_000L
                assertEquals(true to true, source.emitSphereView(view, timestamp, timestamp + 250_000_000L))
                runtime.awaitDebugFixtureIdle()
                val feature = checkNotNull(mapper.feature)
                val depth = checkNotNull(mapper.depth)
                assertEquals(64, feature.samples.size)
                assertEquals(8, depth.samples.size)
                val pose = feature.frame.pose.worldFromCameraGl
                val displacement = sqrt(pose[12] * pose[12] + pose[13] * pose[13] + pose[14] * pose[14])
                assertTrue(displacement <= 0.5)
                val centre = feature.samples.reduce { left, right ->
                    left.copy(
                        xWorld = left.xWorld + right.xWorld,
                        yWorld = left.yWorld + right.yWorld,
                        zWorld = left.zWorld + right.zWorld,
                    )
                }
                val x = centre.xWorld / feature.samples.size
                val y = centre.yWorld / feature.samples.size
                val z = centre.zWorld / feature.samples.size
                assertTrue(feature.samples.all { sample ->
                    abs(sqrt(sample.xWorld * sample.xWorld +
                        sample.yWorld * sample.yWorld + sample.zWorld * sample.zWorld) - 2.0) < 1e-6
                })
                assertTrue(x * -pose[8] + y * -pose[9] + z * -pose[10] > 1.0)
                if (view == 0) {
                    val cameraAngles = feature.samples.map { sample ->
                        val dx = sample.xWorld - pose[12]
                        val dy = sample.yWorld - pose[13]
                        val dz = sample.zWorld - pose[14]
                        val cameraX = pose[0] * dx + pose[1] * dy + pose[2] * dz
                        val cameraY = pose[4] * dx + pose[5] * dy + pose[6] * dz
                        val cameraZ = pose[8] * dx + pose[9] * dy + pose[10] * dz
                        atan2(cameraX, -cameraZ) to atan2(cameraY, -cameraZ)
                    }
                    val horizontal = cameraAngles.map { it.first }
                    val vertical = cameraAngles.map { it.second }
                    assertTrue(horizontal.max() - horizontal.min() >= PI / 3.0)
                    assertTrue(vertical.max() - vertical.min() >= PI / 4.0)
                }
                val pitch = atan2(-pose[9], sqrt(pose[8] * pose[8] + pose[10] * pose[10]))
                if (view < 18) {
                    val yaw = (atan2(-pose[8], pose[10]) + 2 * PI) % (2 * PI)
                    val ring = ((pitch + PI / 4) / (PI / 4)).roundToInt().coerceIn(0, 2)
                    val sector = ((yaw - PI / 6) / (PI / 3)).roundToInt().mod(6)
                    seen += ring * 6 + sector
                } else {
                    assertTrue(abs(pitch) > PI * 0.49)
                }
            }
            assertEquals(18, seen.size)
        } finally {
            runtime.close()
        }
    }

    @Test
    fun `synthetic sphere feature and depth phases commit one pair per view`() {
        val cut = ownership(identityMatrix(), identityMatrix())
        val runtime = AndroidVisibilityGridRuntime(
            ownership = { cut },
            mapper = RecordingVisibilityMapper(),
            scheduler = Executors.newScheduledThreadPool(2),
            featureIntervalNs = 1,
            depthIntervalNs = 1,
            ownsScheduler = true,
        )
        val messenger = MethodTestMessenger()
        val channel = VisibilitySmallSceneDebugChannel(
            messenger = messenger,
            viewId = 91,
            isDebuggable = true,
            runtime = runtime,
            ownership = { cut },
            referencePose = ::identityMatrix,
        )
        val method = MethodChannel(messenger, "visibility_scenario_v2_91")
        try {
            invoke(method, "arm", mapOf(
                "scenarioId" to "phased-sphere",
                "depthCapability" to "automatic",
                "sequence" to 1L,
                "expectedBindingGeneration" to 1L,
                "expectedGroupGeneration" to 1L,
            ))
            val feature0 = invoke(method, "sphereFeatureView", mapOf(
                "scenarioId" to "phased-sphere", "viewIndex" to 0, "sequence" to 2L,
            ))
            val depth0 = invoke(method, "sphereDepthView", mapOf(
                "scenarioId" to "phased-sphere", "viewIndex" to 0, "sequence" to 3L,
            ))
            assertTrue((feature0["acceptedFeatureObservations"] as Long) > 0L)
            assertEquals(feature0["acceptedFeatureObservations"], depth0["acceptedFeatureObservations"])
            assertTrue((depth0["acceptedDepthObservations"] as Long) > 0L)

            val feature1 = invoke(method, "sphereFeatureView", mapOf(
                "scenarioId" to "phased-sphere", "viewIndex" to 1, "sequence" to 4L,
            ))
            val depth1 = invoke(method, "sphereDepthView", mapOf(
                "scenarioId" to "phased-sphere", "viewIndex" to 1, "sequence" to 5L,
            ))
            assertTrue((feature1["acceptedFeatureObservations"] as Long) >
                (feature0["acceptedFeatureObservations"] as Long))
            assertTrue((depth1["acceptedDepthObservations"] as Long) >
                (depth0["acceptedDepthObservations"] as Long))
            assertEquals(5L, depth1["sequence"])
            invoke(method, "disarm", mapOf(
                "scenarioId" to "phased-sphere", "sequence" to 6L,
            ))
        } finally {
            channel.dispose()
            runtime.close()
        }
    }

    @Test
    fun `synthetic AR observations admit exact maximum feature and depth samples`() {
        val cut = ownership(identityMatrix(), identityMatrix())
        val mapper = RecordingVisibilityMapper()
        val runtime = AndroidVisibilityGridRuntime(
            ownership = { cut },
            mapper = mapper,
            scheduler = Executors.newScheduledThreadPool(2),
            featureIntervalNs = 1,
            depthIntervalNs = 1,
            ownsScheduler = true,
        )
        val source = SyntheticVisibilityObservationSource(runtime) { cut }
        try {
            source.setDepthCapability(VisibilityDepthCapability.AUTOMATIC)
            assertEquals(true to true, source.emitMaximumSamples(1_000_000_000L, 1_250_000_000L))
            assertTrue(mapper.featureLatch.await(2, TimeUnit.SECONDS))
            assertTrue(mapper.depthLatch.await(2, TimeUnit.SECONDS))
            runtime.awaitDebugFixtureIdle()
            assertEquals(V2_FEATURE_SAMPLE_CAPACITY, checkNotNull(mapper.feature).samples.size)
            assertTrue(checkNotNull(mapper.feature).samples.all { it.xWorld in 0.30..0.31 })
            assertEquals(V2_DEPTH_SAMPLE_CAPACITY, checkNotNull(mapper.depth).samples.size)
            assertTrue(checkNotNull(mapper.depth).samples.all { it.depthMillimeters == 300 })
            assertEquals(V2_FEATURE_SAMPLE_CAPACITY, runtime.snapshot().maximumFeatureSamples)
            assertEquals(V2_DEPTH_SAMPLE_CAPACITY, runtime.snapshot().maximumDepthSamples)
        } finally {
            runtime.close()
        }
        assertEquals(0, runtime.snapshot().residentPayloadBytes)
    }

    @Test
    fun `synthetic maximum samples advance timestamps after the full sphere sweep`() {
        val cut = ownership(identityMatrix(), identityMatrix())
        val mapper = RecordingVisibilityMapper()
        val runtime = AndroidVisibilityGridRuntime(
            ownership = { cut },
            mapper = mapper,
            scheduler = Executors.newScheduledThreadPool(2),
            featureIntervalNs = 1,
            depthIntervalNs = 1,
            ownsScheduler = true,
        )
        val source = SyntheticVisibilityObservationSource(runtime) { cut }
        try {
            source.anchor(identityMatrix(), cut.groupFrame)
            source.setDepthCapability(VisibilityDepthCapability.AUTOMATIC)
            for (view in 0 until SYNTHETIC_SPHERE_VIEW_COUNT) {
                val timestamp = 10_000_000_000L + view * 1_000_000_000L
                assertEquals(true to true, source.emitSphereView(
                    view,
                    timestamp,
                    timestamp + 250_000_000L,
                ))
                runtime.awaitDebugFixtureIdle()
            }

            // The command's legacy 8-second request must be advanced above
            // the sphere's last copied timestamps without weakening runtime
            // duplicate and out-of-order rejection.
            assertEquals(true to true, source.emitMaximumSamples(8_000_000_000L, 8_250_000_000L))
            runtime.awaitDebugFixtureIdle()
            assertEquals(V2_FEATURE_SAMPLE_CAPACITY, checkNotNull(mapper.feature).samples.size)
            assertEquals(V2_DEPTH_SAMPLE_CAPACITY, checkNotNull(mapper.depth).samples.size)
            assertTrue(checkNotNull(mapper.feature).frame.sourceTimestampNs > 29_000_000_000L)
            assertTrue(checkNotNull(mapper.depth).frame.sourceTimestampNs > 29_250_000_000L)
            assertEquals(0, runtime.snapshot().duplicateFeatureObservations)
            assertEquals(0, runtime.snapshot().duplicateDepthObservations)
        } finally {
            runtime.close()
        }
    }

    @Test
    fun `synthetic callback pressure sheds supported depth while capture remains safe`() {
        val cut = ownership(identityMatrix(), identityMatrix())
        val runtime = AndroidVisibilityGridRuntime(
            ownership = { cut },
            mapper = RecordingVisibilityMapper(),
            scheduler = Executors.newSingleThreadScheduledExecutor(),
            ownsScheduler = true,
            callbackCopySampleCapacity = 2,
            captureSafe = VisibilityCaptureSafePredicate { true },
        )
        val source = SyntheticVisibilityObservationSource(runtime) { cut }
        try {
            source.setDepthCapability(VisibilityDepthCapability.AUTOMATIC)
            assertTrue(source.emitFeature(1_000_000_000L, callbackCopyNs = 2_100_000L))
            assertEquals(VisibilitySourceHealth.TRANSIENT_UNAVAILABLE, runtime.snapshot().depthHealth)
            assertEquals(VisibilityDepthCapability.AUTOMATIC, runtime.snapshot().depthCapability)
            assertEquals(1_000, runtime.featureSampleCapacity())
            assertFalse(source.emitDepth(1_000_000_001L))
            assertTrue(source.emitFeature(2_000_000_000L, callbackCopyNs = 1_000_000L))
            assertTrue(runtime.snapshot().captureSafe)
        } finally {
            runtime.close()
        }
    }

    @Test
    fun `synthetic fixture aligns feature geometry to the armed ownership group`() {
        val groupFromWorld = identityMatrix().also {
            it[12] = -2.0
            it[13] = -3.0
            it[14] = -4.0
        }
        val worldFromGroup = identityMatrix().also {
            it[12] = 2.0
            it[13] = 3.0
            it[14] = 4.0
        }
        val activeOwnership = ownership(groupFromWorld, worldFromGroup)
        val mapper = RecordingVisibilityMapper()
        val runtime = AndroidVisibilityGridRuntime(
            ownership = { activeOwnership },
            mapper = mapper,
            scheduler = Executors.newSingleThreadScheduledExecutor(),
            featureIntervalNs = 1,
            depthIntervalNs = 1,
            ownsScheduler = true,
        )
        val source = SyntheticVisibilityObservationSource(runtime) { activeOwnership }
        val pose = identityVisibilityGridTransform().also {
            it[12] = 2.0
            it[13] = 3.0
            it[14] = 4.0
        }
        try {
            source.anchor(pose, activeOwnership.groupFrame)
            source.setDepthCapability(VisibilityDepthCapability.AUTOMATIC)

            assertTrue(source.emitFeature(timestampNs = 1L, marker = 10))
            assertTrue(mapper.featureLatch.await(1, TimeUnit.SECONDS))

            val observation = checkNotNull(mapper.feature)
            assertEquals(identityMatrix().toList(), observation.frame.pose.worldFromCameraGl)
            assertCommittedPictureIntrinsics(observation.frame.intrinsics)
            val sample = observation.samples.single()
            assertEquals(0.1, sample.xWorld, 1e-9)
            assertEquals(0.0, sample.yWorld, 1e-9)
            assertEquals(-1.0, sample.zWorld, 1e-9)

            assertTrue(source.emitDepth(timestampNs = 2L, marker = 10))
            assertTrue(mapper.depthLatch.await(1, TimeUnit.SECONDS))
            val depth = checkNotNull(mapper.depth)
            assertEquals(pose.toList(), depth.frame.pose.worldFromCameraGl)
            assertCommittedPictureIntrinsics(depth.frame.intrinsics)
            assertEquals(840, depth.samples.single().x)
            assertEquals(480, depth.samples.single().y)
            assertEquals(1_000, depth.samples.single().depthMillimeters)
        } finally {
            runtime.close()
        }
    }

    @Test
    fun `wall and second view use exact pose then severe pressure sheds depth`() {
        val messenger = MethodTestMessenger()
        val groupFromWorld = identityMatrix().also {
            it[12] = -2.0
            it[13] = -3.0
            it[14] = -4.0
        }
        val worldFromGroup = identityMatrix().also {
            it[12] = 2.0
            it[13] = 3.0
            it[14] = 4.0
        }
        val cut = AtomicReference(ownership(groupFromWorld, worldFromGroup))
        val mapper = RecordingVisibilityMapper()
        val runtime = AndroidVisibilityGridRuntime(
            ownership = cut::get,
            mapper = mapper,
            scheduler = Executors.newScheduledThreadPool(2),
            featureIntervalNs = 1,
            depthIntervalNs = 1,
            ownsScheduler = true,
            captureSafe = VisibilityCaptureSafePredicate { true },
        )
        val armedPose = doubleArrayOf(
            0.0, 1.0, 0.0, 0.0,
            -1.0, 0.0, 0.0, 0.0,
            0.0, 0.0, 1.0, 0.0,
            3.0, 5.0, 7.0, 1.0,
        )
        val wallPose = worldFromGroup.copyOf().also { it[12] += 1.5 }
        val wallGroupPose = identityMatrix().also { it[12] = 1.5 }
        val laterPose = identityMatrix().also {
            it[12] = -8.0
            it[13] = 6.0
            it[14] = 2.0
        }
        val laterGroupPose = identityMatrix().also {
            it[12] = -10.0
            it[13] = 3.0
            it[14] = -2.0
        }
        val pose = AtomicReference(armedPose.copyOf())
        val channel = VisibilitySmallSceneDebugChannel(
            messenger = messenger,
            viewId = 69,
            isDebuggable = true,
            runtime = runtime,
            ownership = cut::get,
            referencePose = pose::get,
        )
        val method = MethodChannel(messenger, "visibility_scenario_v2_69")
        try {
            invoke(
                method,
                "arm",
                mapOf(
                    "scenarioId" to "refreshed-second-view",
                    "depthCapability" to "automatic",
                    "sequence" to 1L,
                    "expectedBindingGeneration" to 1L,
                    "expectedGroupGeneration" to 1L,
                ),
            )
            pose.set(wallPose.copyOf())
            invoke(
                method,
                "emit",
                mapOf(
                    "scenarioId" to "refreshed-second-view",
                    "step" to "wall",
                    "sequence" to 2L,
                ),
            )
            pose.set(laterPose.copyOf())
            invoke(
                method,
                "emit",
                mapOf(
                    "scenarioId" to "refreshed-second-view",
                    "step" to "secondView",
                    "sequence" to 3L,
                ),
            )
            runtime.awaitDebugFixtureIdle()

            val endpointFeature = mapper.features.first { observation ->
                observation.samples.single().id in 0..3
            }
            val endpointDepth = mapper.depths.first()
            val feature = checkNotNull(mapper.feature)
            val depth = checkNotNull(mapper.depth)
            assertEquals(
                wallGroupPose.toList(),
                endpointFeature.frame.pose.worldFromCameraGl,
            )
            assertEquals(
                wallPose.toList(),
                endpointDepth.frame.pose.worldFromCameraGl,
            )
            assertEquals(20, feature.samples.single().id)
            assertEquals(laterGroupPose.toList(), feature.frame.pose.worldFromCameraGl)
            assertEquals(laterPose.toList(), depth.frame.pose.worldFromCameraGl)
            assertEquals(feature.frame.frameTimestampNs, depth.frame.frameTimestampNs)
            assertEquals(feature.frame.intrinsics, depth.frame.intrinsics)
            assertCommittedPictureIntrinsics(endpointFeature.frame.intrinsics)
            assertCommittedPictureIntrinsics(endpointDepth.frame.intrinsics)
            assertCommittedPictureIntrinsics(feature.frame.intrinsics)
            assertCommittedPictureIntrinsics(depth.frame.intrinsics)
            assertEquals(1.5, endpointFeature.samples.single().xWorld, 1e-9)
            assertEquals(0.0, endpointFeature.samples.single().yWorld, 1e-9)
            assertEquals(-1.0, endpointFeature.samples.single().zWorld, 1e-9)
            assertEquals(-10.0, feature.samples.single().xWorld, 1e-9)
            assertEquals(3.0, feature.samples.single().yWorld, 1e-9)
            assertEquals(-3.0, feature.samples.single().zWorld, 1e-9)
            assertEquals(640, depth.samples.single().x)
            assertEquals(480, depth.samples.single().y)
            assertEquals(1_000, depth.samples.single().depthMillimeters)
            val beforePressure = runtime.snapshot()
            invoke(
                method,
                "emit",
                mapOf(
                    "scenarioId" to "refreshed-second-view",
                    "step" to "severePressure",
                    "sequence" to 4L,
                ),
            )
            val severe = runtime.snapshot()
            assertEquals("severeDepthShedCaptureSafeFeature1Hz", severe.callbackCopyBudgetState)
            assertEquals(VisibilityDepthCapability.AUTOMATIC, severe.depthCapability)
            assertEquals(VisibilitySourceHealth.TRANSIENT_UNAVAILABLE, severe.depthHealth)
            assertTrue(severe.callbackCopyDepthSheds > beforePressure.callbackCopyDepthSheds)
            assertTrue(severe.copiedFeatureObservations > beforePressure.copiedFeatureObservations)
            assertTrue(severe.droppedDepthObservations > beforePressure.droppedDepthObservations)
            assertEquals(1_000, runtime.featureSampleCapacity())
        } finally {
            channel.dispose()
            runtime.close()
        }
    }

    @Test
    fun `ARM waits for native observation ownership without consuming sequence`() {
        val messenger = MethodTestMessenger()
        val cut = AtomicReference<VisibilityObservationOwnership?>(null)
        val runtime = AndroidVisibilityGridRuntime(
            ownership = cut::get,
            mapper = AndroidVisibilityGridMappingAdmission(cut::get),
            scheduler = Executors.newScheduledThreadPool(2),
            featureIntervalNs = 1,
            depthIntervalNs = 1,
            ownsScheduler = true,
        )
        val pose = AtomicReference<DoubleArray?>(null)
        val channel = VisibilitySmallSceneDebugChannel(
            messenger = messenger,
            viewId = 70,
            isDebuggable = true,
            runtime = runtime,
            ownership = cut::get,
            referencePose = pose::get,
        )
        val method = MethodChannel(messenger, "visibility_scenario_v2_70")
        val arguments = mapOf(
            "scenarioId" to "ownership-ready",
            "depthCapability" to "automatic",
            "sequence" to 1L,
            "expectedBindingGeneration" to 1L,
            "expectedGroupGeneration" to 1L,
        )
        try {
            val rejected = invokeResult(method, "arm", arguments)
            assertEquals("VG_SCENARIO_REJECTED", rejected.errorCode)
            assertEquals("native observation ownership is not ready", rejected.errorMessage)
            assertEquals(0L, runtime.snapshot().copiedFeatureObservations)

            cut.set(ownership())
            val accepted = invoke(method, "arm", arguments)
            assertEquals(1L, accepted["sequence"])
            assertEquals("ownership-ready", accepted["scenarioId"])
            val missingPose = invokeResult(
                method,
                "emit",
                mapOf(
                    "scenarioId" to "ownership-ready",
                    "step" to "wall",
                    "sequence" to 2L,
                ),
            )
            assertEquals("VG_SCENARIO_REJECTED", missingPose.errorCode)
            assertEquals(
                "live reference pose is not ready for the wall fixture",
                missingPose.errorMessage,
            )
        } finally {
            channel.dispose()
            runtime.close()
        }
    }

    @Test
    fun `prepare fences real producers before ownership without consuming ARM sequence`() {
        val messenger = MethodTestMessenger()
        val cut = AtomicReference<VisibilityObservationOwnership?>(null)
        val runtime = AndroidVisibilityGridRuntime(
            ownership = cut::get,
            mapper = AndroidVisibilityGridMappingAdmission(cut::get),
            scheduler = Executors.newScheduledThreadPool(2),
            featureIntervalNs = 1,
            depthIntervalNs = 1,
            ownsScheduler = true,
        )
        val channel = VisibilitySmallSceneDebugChannel(
            messenger = messenger,
            viewId = 75,
            isDebuggable = true,
            runtime = runtime,
            ownership = cut::get,
        )
        val method = MethodChannel(messenger, "visibility_scenario_v2_75")
        val armArguments = mapOf(
            "scenarioId" to "prepared-before-ownership",
            "depthCapability" to "automatic",
            "sequence" to 1L,
            "expectedBindingGeneration" to 1L,
            "expectedGroupGeneration" to 1L,
        )
        try {
            repeat(2) {
                val prepared = invokeResult(
                    method,
                    "prepare",
                    mapOf("depthCapability" to "automatic"),
                )
                assertEquals(1, prepared.successCount)
                assertEquals(null, prepared.successValue)
            }
            assertTrue(runtime.isSyntheticSource())
            assertEquals(
                VisibilityDepthCapability.AUTOMATIC,
                runtime.snapshot().depthCapability,
            )

            val prepareMismatch = invokeResult(
                method,
                "prepare",
                mapOf("depthCapability" to "rawDepth"),
            )
            assertEquals("VG_SCENARIO_REJECTED", prepareMismatch.errorCode)
            assertEquals(
                "depth capability does not match the prepared synthetic source",
                prepareMismatch.errorMessage,
            )
            val armMismatch = invokeResult(
                method,
                "arm",
                armArguments + ("depthCapability" to "rawDepth"),
            )
            assertEquals("VG_SCENARIO_REJECTED", armMismatch.errorCode)
            assertEquals(
                "depth capability does not match the prepared synthetic source",
                armMismatch.errorMessage,
            )

            val unavailable = invokeResult(method, "arm", armArguments)
            assertEquals("VG_SCENARIO_REJECTED", unavailable.errorCode)
            assertEquals(
                "native observation ownership is not ready",
                unavailable.errorMessage,
            )
            cut.set(ownership())
            val wrongBinding = invokeResult(
                method,
                "arm",
                armArguments + ("expectedBindingGeneration" to 2L),
            )
            assertEquals("VG_SCENARIO_REJECTED", wrongBinding.errorCode)
            assertEquals("binding generation does not match", wrongBinding.errorMessage)
            val armed = invoke(method, "arm", armArguments)
            assertEquals(1L, armed["sequence"])
            assertEquals("prepared-before-ownership", armed["scenarioId"])
        } finally {
            channel.dispose()
            runtime.close()
        }
    }

    @Test
    fun `finite scene steps use production observation runtime and replay exact receipt`() {
        val messenger = MethodTestMessenger()
        val cut = AtomicReference(ownership())
        val scheduler = Executors.newScheduledThreadPool(2)
        val mapper = AndroidVisibilityGridMappingAdmission(cut::get)
        val runtime = AndroidVisibilityGridRuntime(
            ownership = cut::get,
            mapper = mapper,
            scheduler = scheduler,
            featureIntervalNs = 1,
            depthIntervalNs = 1,
            ownsScheduler = true,
        )
        runtime.setDepthCapability(VisibilityDepthCapability.AUTOMATIC)
        val posePhases = mutableListOf<String>()
        val channel = VisibilitySmallSceneDebugChannel(
            messenger = messenger,
            viewId = 71,
            isDebuggable = true,
            runtime = runtime,
            ownership = cut::get,
            referencePose = ::identityMatrix,
            productHooks = object : VisibilitySmallSceneProductHooks {
                override fun beginPoseFixture(): Boolean {
                    posePhases += "baseline"
                    return true
                }
                override fun manualViewPose() { posePhases += "manual" }
                override fun automaticRevisitPose() { posePhases += "revisit" }
                override fun clearPoseFixture() { posePhases += "clear" }
                override fun snapshot() = VisibilitySmallSceneReceiptScalars(
                    geometryRevision = 3,
                    lineageRevision = 2,
                    guidanceStatus = "tracking",
                    rootIsolateSurfaceBytes = 0,
                )
            },
        )
        val method = MethodChannel(messenger, "visibility_scenario_v2_71")
        try {
            val arm = invoke(
                method,
                "arm",
                mapOf(
                    "scenarioId" to "small-scene-primary",
                    "depthCapability" to "automatic",
                    "sequence" to 1L,
                    "expectedBindingGeneration" to 1L,
                    "expectedGroupGeneration" to 1L,
                ),
            )
            assertEquals(1L, arm["sequence"])
            assertEquals("small-scene-primary", arm["scenarioId"])

            val wall = invoke(
                method,
                "emit",
                mapOf(
                    "scenarioId" to "small-scene-primary",
                    "step" to "wall",
                    "sequence" to 2L,
                ),
            )
            assertEquals(2L, wall["sequence"])
            assertTrue((wall["acceptedFeatureObservations"] as Long) > 0L)
            assertTrue((wall["acceptedDepthObservations"] as Long) > 0L)
            assertEquals(0L, wall["rootIsolateSurfaceBytes"])
            assertEquals(0L, wall["rootIsolateImageBytes"])
            assertTrue(wall.values.all { it == null || it is String || it is Number || it is Boolean })

            val replay = invoke(
                method,
                "emit",
                mapOf(
                    "scenarioId" to "small-scene-primary",
                    "step" to "wall",
                    "sequence" to 2L,
                ),
            )
            assertEquals(wall, replay)

            val differentReplay = invokeResult(
                method,
                "emit",
                mapOf(
                    "scenarioId" to "small-scene-primary",
                    "step" to "corner",
                    "sequence" to 2L,
                ),
            )
            assertEquals(1, differentReplay.errorCount)
            assertEquals("VG_SCENARIO_REJECTED", differentReplay.errorCode)

            val corner = invoke(
                method,
                "emit",
                mapOf(
                    "scenarioId" to "small-scene-primary",
                    "step" to "corner",
                    "sequence" to 3L,
                ),
            )
            val olderReplay = invoke(
                method,
                "emit",
                mapOf(
                    "scenarioId" to "small-scene-primary",
                    "step" to "wall",
                    "sequence" to 2L,
                ),
            )
            assertEquals(wall, olderReplay)

            val secondView = invoke(
                method,
                "emit",
                mapOf(
                    "scenarioId" to "small-scene-primary",
                    "step" to "secondView",
                    "sequence" to 4L,
                ),
            )
            assertEquals(4L, secondView["sequence"])
            assertEquals(
                (corner["acceptedFeatureObservations"] as Long) + 1L,
                secondView["acceptedFeatureObservations"],
            )
            assertEquals(
                (corner["acceptedDepthObservations"] as Long) + 1L,
                secondView["acceptedDepthObservations"],
            )

            val revisit = invoke(
                method,
                "emit",
                mapOf(
                    "scenarioId" to "small-scene-primary",
                    "step" to "automaticRevisit",
                    "sequence" to 5L,
                ),
            )
            assertEquals(5L, revisit["sequence"])
            assertEquals(
                secondView["acceptedFeatureObservations"],
                revisit["acceptedFeatureObservations"],
            )
            assertEquals(
                secondView["acceptedDepthObservations"],
                revisit["acceptedDepthObservations"],
            )

            val disarm = invoke(
                method,
                "disarm",
                mapOf("scenarioId" to "small-scene-primary", "sequence" to 6L),
            )
            assertEquals(6L, disarm["sequence"])
            assertEquals(listOf("baseline", "manual", "revisit", "clear"), posePhases)
            assertTrue(runtime.snapshot().paused)
            assertEquals(
                disarm,
                invoke(
                    method,
                    "disarm",
                    mapOf("scenarioId" to "small-scene-primary", "sequence" to 6L),
                ),
            )
            val afterDisarm = invokeResult(
                method,
                "emit",
                mapOf(
                    "scenarioId" to "small-scene-primary",
                    "step" to "secondView",
                    "sequence" to 7L,
                ),
            )
            assertEquals("VG_SCENARIO_REJECTED", afterDisarm.errorCode)
            assertTrue(messenger.hasHandler("visibility_scenario_v2_71"))
        } finally {
            channel.dispose()
            runtime.close()
        }
        assertFalse(messenger.hasHandler("visibility_scenario_v2_71"))
    }

    @Test
    fun `finite over-offer step reports copied observations without product credit`() {
        val messenger = MethodTestMessenger()
        val cut = AtomicReference(ownership())
        val runtime = AndroidVisibilityGridRuntime(
            ownership = cut::get,
            mapper = AndroidVisibilityGridMappingAdmission(cut::get),
            scheduler = Executors.newScheduledThreadPool(2),
            ownsScheduler = true,
        )
        val channel = VisibilitySmallSceneDebugChannel(
            messenger = messenger,
            viewId = 76,
            isDebuggable = true,
            runtime = runtime,
            ownership = cut::get,
            referencePose = ::identityMatrix,
            productHooks = object : VisibilitySmallSceneProductHooks {
                override fun beginPoseFixture() = true
                override fun snapshot() = VisibilitySmallSceneReceiptScalars(
                    geometryRevision = 1,
                    lineageRevision = 1,
                    durableCaptureRevision = 0,
                    coverageRevision = 0,
                )
            },
        )
        val method = MethodChannel(messenger, "visibility_scenario_v2_76")
        try {
            invoke(method, "arm", mapOf(
                "scenarioId" to "over-offer",
                "depthCapability" to "automatic",
                "sequence" to 1L,
                "expectedBindingGeneration" to 1L,
                "expectedGroupGeneration" to 1L,
            ))
            val wall = invoke(method, "emit", mapOf(
                "scenarioId" to "over-offer", "step" to "wall", "sequence" to 2L,
            ))
            val offered = invoke(method, "emit", mapOf(
                "scenarioId" to "over-offer", "step" to "overOffer", "sequence" to 3L,
            ))
            assertEquals(3L, offered["sequence"])
            assertTrue((offered["acceptedFeatureObservations"] as Long) >
                (wall["acceptedFeatureObservations"] as Long))
            assertTrue((offered["acceptedDepthObservations"] as Long) >
                (wall["acceptedDepthObservations"] as Long))
            assertEquals(0L, offered["durableCaptureRevision"])
            assertEquals(0L, offered["coverageRevision"])
            assertEquals(offered, invoke(method, "emit", mapOf(
                "scenarioId" to "over-offer", "step" to "overOffer", "sequence" to 3L,
            )))
        } finally {
            channel.dispose()
            runtime.close()
        }
    }

    @Test
    fun `release build and unwired product faults cannot mutate scenario state`() {
        val messenger = MethodTestMessenger()
        val cut = AtomicReference(ownership())
        val runtime = AndroidVisibilityGridRuntime(
            ownership = cut::get,
            mapper = AndroidVisibilityGridMappingAdmission(cut::get),
            scheduler = Executors.newScheduledThreadPool(2),
            featureIntervalNs = 1,
            depthIntervalNs = 1,
            ownsScheduler = true,
        )
        val releaseChannel = VisibilitySmallSceneDebugChannel(
            messenger = messenger,
            viewId = 72,
            isDebuggable = false,
            runtime = runtime,
            ownership = cut::get,
        )
        val releasePrepareResult = invokeResult(
            MethodChannel(messenger, "visibility_scenario_v2_72"),
            "prepare",
            mapOf("depthCapability" to "automatic"),
        )
        assertEquals(1, releasePrepareResult.errorCount)
        assertEquals("VG_SCENARIO_UNAVAILABLE", releasePrepareResult.errorCode)
        assertFalse(runtime.isSyntheticSource())
        val releaseResult = invokeResult(
            MethodChannel(messenger, "visibility_scenario_v2_72"),
            "arm",
            mapOf(
                "scenarioId" to "release-rejected",
                "depthCapability" to "automatic",
                "sequence" to 1L,
            ),
        )
        assertEquals(1, releaseResult.errorCount)
        assertEquals("VG_SCENARIO_UNAVAILABLE", releaseResult.errorCode)
        val releaseFault = invokeResult(
            MethodChannel(messenger, "visibility_scenario_v2_72"),
            "setFault",
            mapOf(
                "scenarioId" to "release-rejected",
                "fault" to "canonicalRetryableDepthCommit",
                "sequence" to 1L,
            ),
        )
        assertEquals("VG_SCENARIO_UNAVAILABLE", releaseFault.errorCode)
        val releaseAllocationFault = invokeResult(
            MethodChannel(messenger, "visibility_scenario_v2_72"),
            "setFault",
            mapOf(
                "scenarioId" to "release-rejected",
                "fault" to "rendererAllocationFailure",
                "sequence" to 1L,
            ),
        )
        assertEquals("VG_SCENARIO_UNAVAILABLE", releaseAllocationFault.errorCode)
        assertEquals(0L, runtime.snapshot().copiedFeatureObservations)

        val debugChannel = VisibilitySmallSceneDebugChannel(
            messenger = messenger,
            viewId = 73,
            isDebuggable = true,
            runtime = runtime,
            ownership = cut::get,
        )
        try {
            val method = MethodChannel(messenger, "visibility_scenario_v2_73")
            invoke(
                method,
                "arm",
                mapOf(
                "scenarioId" to "fault-rejected",
                "depthCapability" to "automatic",
                "sequence" to 1L,
                "expectedBindingGeneration" to 1L,
                "expectedGroupGeneration" to 1L,
            ),
        )
            val fault = invokeResult(
                method,
                "setFault",
                mapOf(
                    "scenarioId" to "fault-rejected",
                    "fault" to "rendererUnavailable",
                    "sequence" to 2L,
                ),
            )
            assertEquals(1, fault.errorCount)
            assertEquals("VG_SCENARIO_REJECTED", fault.errorCode)
            assertEquals(0L, runtime.snapshot().copiedFeatureObservations)
            val snapshot = invoke(
                method,
                "snapshot",
                mapOf("scenarioId" to "fault-rejected", "sequence" to 2L),
            )
            assertEquals(2L, snapshot["sequence"])
        } finally {
            releaseChannel.dispose()
            debugChannel.dispose()
            runtime.close()
        }
    }

    @Test
    fun `fault and lifecycle steps invoke production hooks once across replay`() {
        val messenger = MethodTestMessenger()
        val cut = AtomicReference(ownership())
        val runtime = AndroidVisibilityGridRuntime(
            ownership = cut::get,
            mapper = AndroidVisibilityGridMappingAdmission(cut::get),
            scheduler = Executors.newScheduledThreadPool(2),
            featureIntervalNs = 1,
            depthIntervalNs = 1,
            ownsScheduler = true,
        )
        var rendererLosses = 0
        var rendererRecoveries = 0
        var guidanceFailures = 0
        var canonicalFaultArms = 0
        var rendererAllocationFaultArms = 0
        var lifecyclePauses = 0
        var lifecycleResumes = 0
        val channel = VisibilitySmallSceneDebugChannel(
            messenger = messenger,
            viewId = 74,
            isDebuggable = true,
            runtime = runtime,
            ownership = cut::get,
            productHooks = object : VisibilitySmallSceneProductHooks {
                override fun rendererUnavailable() { rendererLosses++ }
                override fun rendererRecovered() { rendererRecoveries++ }
                override fun guidanceTerminal() { guidanceFailures++ }
                override fun canonicalRetryableDepthCommit() { canonicalFaultArms++ }
                override fun rendererAllocationFailure() { rendererAllocationFaultArms++ }
                override fun pause() { lifecyclePauses++ }
                override fun resume() { lifecycleResumes++ }
            },
        )
        val method = MethodChannel(messenger, "visibility_scenario_v2_74")
        try {
            invoke(
                method,
                "arm",
                mapOf(
                    "scenarioId" to "small-scene-faults",
                    "depthCapability" to "automatic",
                    "sequence" to 1L,
                    "expectedBindingGeneration" to 1L,
                    "expectedGroupGeneration" to 1L,
                ),
            )
            val loss = invoke(
                method,
                "setFault",
                mapOf(
                    "scenarioId" to "small-scene-faults",
                    "fault" to "rendererUnavailable",
                    "sequence" to 2L,
                ),
            )
            assertEquals(
                loss,
                invoke(
                    method,
                    "setFault",
                    mapOf(
                        "scenarioId" to "small-scene-faults",
                        "fault" to "rendererUnavailable",
                        "sequence" to 2L,
                    ),
                ),
            )
            invoke(
                method,
                "setFault",
                mapOf(
                    "scenarioId" to "small-scene-faults",
                    "fault" to "rendererRecovered",
                    "sequence" to 3L,
                ),
            )
            invoke(
                method,
                "setFault",
                mapOf(
                    "scenarioId" to "small-scene-faults",
                    "fault" to "guidanceTerminal",
                    "sequence" to 4L,
                ),
            )
            invoke(
                method,
                "setFault",
                mapOf(
                    "scenarioId" to "small-scene-faults",
                    "fault" to "canonicalRetryableDepthCommit",
                    "sequence" to 5L,
                ),
            )
            invoke(
                method,
                "setFault",
                mapOf(
                    "scenarioId" to "small-scene-faults",
                    "fault" to "rendererAllocationFailure",
                    "sequence" to 6L,
                ),
            )
            invoke(
                method,
                "pauseResume",
                mapOf("scenarioId" to "small-scene-faults", "sequence" to 7L),
            )
            assertEquals(1, rendererLosses)
            assertEquals(1, rendererRecoveries)
            assertEquals(1, guidanceFailures)
            assertEquals(1, canonicalFaultArms)
            assertEquals(1, rendererAllocationFaultArms)
            assertEquals(1, lifecyclePauses)
            assertEquals(1, lifecycleResumes)
        } finally {
            channel.dispose()
            runtime.close()
        }
    }

    private fun invoke(
        channel: MethodChannel,
        method: String,
        arguments: Map<String, Any?>,
    ): Map<*, *> {
        val result = invokeResult(channel, method, arguments)
        assertEquals("${result.errorCode}: ${result.errorMessage}", 1, result.successCount)
        return result.successValue as Map<*, *>
    }

    private fun invokeResult(
        channel: MethodChannel,
        method: String,
        arguments: Map<String, Any?>,
    ): RecordingResult {
        val result = RecordingResult()
        channel.invokeMethod(method, arguments, result)
        assertTrue(result.completed.await(2, TimeUnit.SECONDS))
        return result
    }

    private fun ownership(
        groupFromWorldGl: DoubleArray = identityMatrix(),
        worldFromGroupGl: DoubleArray = identityMatrix(),
    ) = VisibilityObservationOwnership(
        sessionId = "11111111111111111111111111111111",
        sessionGeneration = 1,
        captureGroupId = "22222222222222222222222222222222",
        groupGeneration = 1,
        coverageEpoch = 1,
        arSessionIdentity = "33333333333333333333333333333333",
        viewInstanceId = "44444444444444444444444444444444",
        viewGeneration = 1,
        nativeStreamToken = "55555555555555555555555555555555",
        workerBindingToken = "66666666666666666666666666666666",
        bindingGeneration = 1,
        lifecycleSequence = 1,
        operationGeneration = 1,
        groupFrame = VisibilityGroupFrame.copyOf(
            groupFromWorldGl = groupFromWorldGl,
            worldFromGroupGl = worldFromGroupGl,
            voxelSizeMicrometres = 100_000,
            modelCapacity = 100_000,
        ),
    )

    private fun identityMatrix() = doubleArrayOf(
        1.0, 0.0, 0.0, 0.0,
        0.0, 1.0, 0.0, 0.0,
        0.0, 0.0, 1.0, 0.0,
        0.0, 0.0, 0.0, 1.0,
    )

    private fun assertCommittedPictureIntrinsics(intrinsics: VisibilityCameraIntrinsics) {
        assertEquals(1280, intrinsics.imageWidth)
        assertEquals(960, intrinsics.imageHeight)
        assertEquals(2000.0, intrinsics.fx, 0.0)
        assertEquals(2000.0, intrinsics.fy, 0.0)
        assertEquals(640.0, intrinsics.cx, 0.0)
        assertEquals(480.0, intrinsics.cy, 0.0)
    }
}

private data class RecordedFeatureObservation(
    val ownership: VisibilityObservationOwnership,
    val frame: VisibilityObservationFrame,
    val samples: List<VisibilityFeatureSample>,
    val sourceRejectedSamples: Int,
    val payloadBytes: Int,
)

private data class RecordedDepthObservation(
    val ownership: VisibilityObservationOwnership,
    val frame: VisibilityObservationFrame,
    val samples: List<VisibilityDepthSample>,
    val sourceRejectedSamples: Int,
    val payloadBytes: Int,
)

private class RecordingVisibilityMapper(
    expectedFeatures: Int = 1,
    expectedDepths: Int = 1,
) : VisibilityObservationMapper {
    val featureLatch = CountDownLatch(expectedFeatures)
    val depthLatch = CountDownLatch(expectedDepths)
    val features = CopyOnWriteArrayList<RecordedFeatureObservation>()
    val depths = CopyOnWriteArrayList<RecordedDepthObservation>()
    var feature: RecordedFeatureObservation? = null
    var depth: RecordedDepthObservation? = null

    override fun admitFeature(observation: VisibilityFeatureObservation) {
        val recorded = try {
            copyFeature(observation)
        } finally {
            observation.close()
        }
        features.add(recorded)
        feature = recorded
        featureLatch.countDown()
    }

    override fun admitDepth(observation: VisibilityDepthObservation) {
        val recorded = try {
            copyDepth(observation)
        } finally {
            observation.close()
        }
        depths.add(recorded)
        depth = recorded
        depthLatch.countDown()
    }

    private fun copyFeature(observation: VisibilityFeatureObservation): RecordedFeatureObservation {
        val packed = observation.packedSamples
        val samples = if (packed == null) {
            observation.samples.toList()
        } else {
            List(packed.count) { index ->
                VisibilityFeatureSample(
                    id = packed.idAt(index),
                    xWorld = packed.xWorldAt(index),
                    yWorld = packed.yWorldAt(index),
                    zWorld = packed.zWorldAt(index),
                    confidence = packed.confidenceAt(index),
                )
            }
        }
        return RecordedFeatureObservation(
            ownership = observation.ownership,
            frame = observation.frame,
            samples = samples,
            sourceRejectedSamples = observation.sourceRejectedSamples,
            payloadBytes = observation.payloadBytes,
        )
    }

    private fun copyDepth(observation: VisibilityDepthObservation): RecordedDepthObservation {
        val packed = observation.packedSamples
        val samples = if (packed == null) {
            observation.samples.toList()
        } else {
            List(packed.count) { index ->
                VisibilityDepthSample(
                    x = packed.xAt(index),
                    y = packed.yAt(index),
                    depthMillimeters = packed.depthMillimetresAt(index),
                    confidence = packed.confidenceAt(index),
                )
            }
        }
        return RecordedDepthObservation(
            ownership = observation.ownership,
            frame = observation.frame,
            samples = samples,
            sourceRejectedSamples = observation.sourceRejectedSamples,
            payloadBytes = observation.payloadBytes,
        )
    }
}
