package com.uhg0.ar_flutter_plugin_2.visibilitygrid

import io.flutter.plugin.common.MethodChannel
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VisibilitySmallSceneDebugChannelTest {
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
                "pauseResume",
                mapOf("scenarioId" to "small-scene-faults", "sequence" to 6L),
            )
            assertEquals(1, rendererLosses)
            assertEquals(1, rendererRecoveries)
            assertEquals(1, guidanceFailures)
            assertEquals(1, canonicalFaultArms)
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

private class RecordingVisibilityMapper(
    expectedFeatures: Int = 1,
    expectedDepths: Int = 1,
) : VisibilityObservationMapper {
    val featureLatch = CountDownLatch(expectedFeatures)
    val depthLatch = CountDownLatch(expectedDepths)
    val features = CopyOnWriteArrayList<VisibilityFeatureObservation>()
    val depths = CopyOnWriteArrayList<VisibilityDepthObservation>()
    var feature: VisibilityFeatureObservation? = null
    var depth: VisibilityDepthObservation? = null

    override fun admitFeature(observation: VisibilityFeatureObservation) {
        features.add(observation)
        feature = observation
        featureLatch.countDown()
    }

    override fun admitDepth(observation: VisibilityDepthObservation) {
        depths.add(observation)
        depth = observation
        depthLatch.countDown()
    }
}
