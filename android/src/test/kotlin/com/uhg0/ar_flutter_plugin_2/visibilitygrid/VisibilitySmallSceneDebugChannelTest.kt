package com.uhg0.ar_flutter_plugin_2.visibilitygrid

import io.flutter.plugin.common.MethodChannel
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VisibilitySmallSceneDebugChannelTest {
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
        val channel = VisibilitySmallSceneDebugChannel(
            messenger = messenger,
            viewId = 71,
            isDebuggable = true,
            runtime = runtime,
            ownership = cut::get,
            productHooks = object : VisibilitySmallSceneProductHooks {
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

            invoke(
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

            val disarm = invoke(
                method,
                "disarm",
                mapOf("scenarioId" to "small-scene-primary", "sequence" to 4L),
            )
            assertEquals(4L, disarm["sequence"])
            assertTrue(runtime.snapshot().paused)
            assertEquals(
                disarm,
                invoke(
                    method,
                    "disarm",
                    mapOf("scenarioId" to "small-scene-primary", "sequence" to 4L),
                ),
            )
            val afterDisarm = invokeResult(
                method,
                "emit",
                mapOf(
                    "scenarioId" to "small-scene-primary",
                    "step" to "secondView",
                    "sequence" to 5L,
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
                "pauseResume",
                mapOf("scenarioId" to "small-scene-faults", "sequence" to 5L),
            )
            assertEquals(1, rendererLosses)
            assertEquals(1, rendererRecoveries)
            assertEquals(1, guidanceFailures)
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
        assertEquals(1, result.successCount)
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

    private fun ownership() = VisibilityObservationOwnership(
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
            groupFromWorldGl = identityMatrix(),
            worldFromGroupGl = identityMatrix(),
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
}
