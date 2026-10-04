package com.uhg0.ar_flutter_plugin_2.visibilitygrid

import io.flutter.plugin.common.MethodChannel
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VisibilityObservationDebugChannelTest {
    @Test
    fun `finite dense campaign rejects missing partial and overflowing variant ranges`() {
        val messenger = MethodTestMessenger()
        val runtime = AndroidVisibilityGridRuntime(
            ownership = { null },
            mapper = object : VisibilityObservationMapper {
                override fun admitFeature(observation: VisibilityFeatureObservation) = observation.close()
                override fun admitDepth(observation: VisibilityDepthObservation) = observation.close()
            },
        )
        val channel = VisibilityObservationDebugChannel(
            messenger = messenger, viewId = 79, isDebuggable = true,
            runtime = runtime, ownership = { null }, gate = VisibilityObservationDebugGate(),
        )
        try {
            for (method in listOf("prepareAllocationWorkload", "startAllocationWorkload"))
            for ((offset, count) in listOf(-1L to 24L, 25L to 24L, 6L to 480L,
                0L to 25L, 0L to null, 4_294_967_296L to 24L)) {
                val result = RecordingResult()
                MethodChannel(messenger, "visibility_observation_v2_79").invokeMethod(
                    method, mapOf("denseDepthGrids" to true,
                        "depthVariantOffset" to offset, "maximumFeatureOffers" to count), result,
                )
                assertTrue(result.completed.await(2, TimeUnit.SECONDS))
                assertEquals(0, result.successCount)
                assertEquals(1, result.errorCount)
            }
            assertEquals(0L, runtime.snapshot().offeredDepthObservations)
        } finally {
            channel.dispose()
            runtime.close()
        }
    }

    @Test
    fun `preparing dense allocation fixtures repeatedly offers no observations`() {
        val messenger = MethodTestMessenger()
        val ownership = VisibilityObservationOwnership(
            sessionId = "01".repeat(16),
            sessionGeneration = 1,
            captureGroupId = "02".repeat(16),
            groupGeneration = 1,
            coverageEpoch = 1,
            arSessionIdentity = "03".repeat(16),
            viewInstanceId = "04".repeat(16),
            viewGeneration = 1,
            nativeStreamToken = "05".repeat(16),
            workerBindingToken = "06".repeat(16),
            bindingGeneration = 1,
            lifecycleSequence = 1,
            operationGeneration = 1,
            groupFrame = VisibilityGroupFrame.copyOf(
                identityVisibilityGridTransform(), identityVisibilityGridTransform(), 1_000, 100_000,
            ),
        )
        val runtime = AndroidVisibilityGridRuntime(
            ownership = { ownership },
            mapper = object : VisibilityObservationMapper {
                override fun admitFeature(observation: VisibilityFeatureObservation) = observation.close()
                override fun admitDepth(observation: VisibilityDepthObservation) = observation.close()
            },
            scheduler = Executors.newSingleThreadScheduledExecutor(),
            ownsScheduler = true,
        )
        runtime.configureSyntheticSource(VisibilityDepthCapability.AUTOMATIC)
        val channel = VisibilityObservationDebugChannel(
            messenger = messenger, viewId = 78, isDebuggable = true,
            runtime = runtime, ownership = { ownership }, gate = VisibilityObservationDebugGate(),
            allocationTimestampNs = { error("preparation must not schedule offers") },
        )
        try {
            val before = runtime.snapshot()
            repeat(2) {
                val result = RecordingResult()
                MethodChannel(messenger, "visibility_observation_v2_78").invokeMethod(
                    "prepareAllocationWorkload", mapOf("denseDepthGrids" to true,
                        "depthVariantOffset" to 5L, "maximumFeatureOffers" to 480L), result,
                )
                assertTrue(result.completed.await(2, TimeUnit.SECONDS))
                assertEquals(1, result.successCount)
                assertEquals(0, result.errorCount)
            }
            assertEquals(before, runtime.snapshot())
            assertTrue(channel.samplePoolReceipts().values.all { it.outstanding == 0 })
        } finally {
            channel.dispose()
            runtime.close()
        }
    }

    @Test
    fun `pressure diagnostics return from the caller while mapping state is busy`() {
        val messenger = MethodTestMessenger()
        val runtime = AndroidVisibilityGridRuntime(
            ownership = { null },
            mapper = object : VisibilityObservationMapper {
                override fun admitFeature(observation: VisibilityFeatureObservation) = observation.close()
                override fun admitDepth(observation: VisibilityDepthObservation) = observation.close()
            },
            scheduler = Executors.newSingleThreadScheduledExecutor(),
            ownsScheduler = true,
        )
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val caller = Executors.newSingleThreadExecutor()
        val channel = VisibilityObservationDebugChannel(
            messenger = messenger,
            viewId = 77,
            isDebuggable = true,
            runtime = runtime,
            ownership = { null },
            gate = VisibilityObservationDebugGate(),
            pressureOwners = {
                entered.countDown()
                release.await(2, TimeUnit.SECONDS)
                VisibilityPressureOwnerScalars()
            },
        )
        try {
            val result = RecordingResult()
            val returned = caller.submit {
                MethodChannel(messenger, "visibility_observation_v2_77").invokeMethod(
                    "pressureSnapshot", null, result,
                )
            }
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            returned.get(200, TimeUnit.MILLISECONDS)
            assertFalse(result.completed.await(100, TimeUnit.MILLISECONDS))
            release.countDown()
            assertTrue(result.completed.await(2, TimeUnit.SECONDS))
            assertEquals(1, result.successCount)
        } finally {
            release.countDown()
            channel.dispose()
            caller.shutdownNow()
            runtime.close()
        }
    }

    @Test
    fun `disposing an active allocation workload waits for its last offer`() {
        val messenger = MethodTestMessenger()
        val ownership = VisibilityObservationOwnership(
            sessionId = "01".repeat(16),
            sessionGeneration = 1,
            captureGroupId = "02".repeat(16),
            groupGeneration = 1,
            coverageEpoch = 1,
            arSessionIdentity = "03".repeat(16),
            viewInstanceId = "04".repeat(16),
            viewGeneration = 1,
            nativeStreamToken = "05".repeat(16),
            workerBindingToken = "06".repeat(16),
            bindingGeneration = 1,
            lifecycleSequence = 1,
            operationGeneration = 1,
            groupFrame = VisibilityGroupFrame.copyOf(
                identityVisibilityGridTransform(), identityVisibilityGridTransform(), 1_000, 100_000,
            ),
        )
        val runtime = AndroidVisibilityGridRuntime(
            ownership = { ownership },
            mapper = object : VisibilityObservationMapper {
                override fun admitFeature(observation: VisibilityFeatureObservation) = observation.close()
                override fun admitDepth(observation: VisibilityDepthObservation) = observation.close()
            },
            scheduler = Executors.newSingleThreadScheduledExecutor(),
            ownsScheduler = true,
        )
        runtime.configureSyntheticSource(VisibilityDepthCapability.AUTOMATIC)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val disposer = Executors.newSingleThreadExecutor()
        val channel = VisibilityObservationDebugChannel(
            messenger = messenger,
            viewId = 76,
            isDebuggable = true,
            runtime = runtime,
            ownership = { ownership },
            gate = VisibilityObservationDebugGate(),
            allocationTimestampNs = {
                entered.countDown()
                while (true) {
                    try {
                        if (release.await(2, TimeUnit.SECONDS)) break
                    } catch (_: InterruptedException) {
                        // Keep the in-flight tick held despite shutdownNow().
                    }
                }
                1_000_000_000_000_000_000L
            },
        )
        try {
            val start = RecordingResult()
            MethodChannel(messenger, "visibility_observation_v2_76").invokeMethod(
                "startAllocationWorkload", null, start,
            )
            assertTrue(start.completed.await(2, TimeUnit.SECONDS))
            assertEquals(1, start.successCount)
            assertTrue(entered.await(2, TimeUnit.SECONDS))

            val disposeStarted = CountDownLatch(1)
            val disposed = disposer.submit {
                disposeStarted.countDown()
                channel.dispose()
            }
            assertTrue(disposeStarted.await(2, TimeUnit.SECONDS))
            Thread.sleep(100)
            assertFalse(disposed.isDone)
            release.countDown()
            disposed.get(2, TimeUnit.SECONDS)
            val offersAfterDispose = runtime.snapshot().offeredFeatureObservations
            assertEquals(1L, offersAfterDispose)
            Thread.sleep(250)
            assertEquals(offersAfterDispose, runtime.snapshot().offeredFeatureObservations)
            runtime.awaitDebugFixtureIdle()
            for (pool in channel.samplePoolReceipts().values) {
                assertEquals(0, pool.outstanding)
                assertEquals(0L, pool.primitiveBytes)
            }
        } finally {
            release.countDown()
            channel.dispose()
            disposer.shutdownNow()
            runtime.close()
        }
    }

    @Test
    fun `release diagnostics reject pressure and allocation work without mutating health`() {
        val messenger = MethodTestMessenger()
        val runtime = AndroidVisibilityGridRuntime(
            ownership = { null },
            mapper = object : VisibilityObservationMapper {
                override fun admitFeature(observation: VisibilityFeatureObservation) = observation.close()

                override fun admitDepth(observation: VisibilityDepthObservation) = observation.close()
            },
            scheduler = Executors.newSingleThreadScheduledExecutor(),
            ownsScheduler = true,
        )
        var pressureOwnerReads = 0
        val channel = VisibilityObservationDebugChannel(
            messenger = messenger,
            viewId = 75,
            isDebuggable = false,
            runtime = runtime,
            ownership = { null },
            gate = VisibilityObservationDebugGate(),
            pressureOwners = {
                pressureOwnerReads++
                VisibilityPressureOwnerScalars()
            },
        )
        try {
            val before = runtime.snapshot()
            for (method in listOf(
                "pressureSnapshot",
                "allocationSnapshot",
                "prepareAllocationWorkload",
                "startAllocationWorkload",
                "stopAllocationWorkload",
            )) {
                val result = RecordingResult()
                MethodChannel(messenger, "visibility_observation_v2_75").invokeMethod(
                    method,
                    null,
                    result,
                )
                assertTrue(result.completed.await(2, TimeUnit.SECONDS))
                assertEquals(0, result.successCount)
                assertEquals(1, result.errorCount)
                assertEquals("VG_PROTOCOL_INVALID", result.errorCode)
            }
            assertEquals(0, pressureOwnerReads)
            assertEquals(before, runtime.snapshot())
        } finally {
            channel.dispose()
            runtime.close()
        }
    }
}
