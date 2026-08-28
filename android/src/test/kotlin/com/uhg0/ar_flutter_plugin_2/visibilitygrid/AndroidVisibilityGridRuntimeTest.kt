package com.uhg0.ar_flutter_plugin_2.visibilitygrid

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidVisibilityGridRuntimeTest {
    @Test
    fun `pause discards queued copied values and rejects paused callbacks`() {
        val cut = AtomicReference(ownership())
        val scheduler = Executors.newSingleThreadScheduledExecutor()
        val blockerEntered = CountDownLatch(1)
        val blockerRelease = CountDownLatch(1)
        scheduler.execute {
            blockerEntered.countDown()
            blockerRelease.await(2, TimeUnit.SECONDS)
        }
        assertTrue(blockerEntered.await(1, TimeUnit.SECONDS))
        val runtime = AndroidVisibilityGridRuntime(
            ownership = cut::get,
            mapper = AndroidVisibilityGridMappingAdmission(cut::get),
            scheduler = scheduler,
            featureIntervalNs = 1,
            depthIntervalNs = 1,
            ownsScheduler = true,
        )
        try {
            runtime.setDepthCapability(VisibilityDepthCapability.AUTOMATIC)
            assertTrue(runtime.offerFeature(feature(cut.get(), 1, 1)))
            assertTrue(runtime.offerDepth(depth(cut.get(), 2, 2)))
            runtime.pause()
            assertFalse(runtime.shouldCopyFeature(3))
            assertFalse(runtime.shouldCopyDepth(3))
            assertFalse(runtime.offerFeature(feature(cut.get(), 3, 3)))
            blockerRelease.countDown()
            Thread.sleep(25)

            val health = runtime.snapshot()
            assertTrue(health.paused)
            assertEquals(0, health.admittedFeatureObservations)
            assertEquals(0, health.admittedDepthObservations)
            assertEquals(2, health.lifecycleDiscardedObservations)
            assertEquals(1, health.pausedObservationRejections)
            assertEquals(0, health.residentPayloadBytes)
        } finally {
            blockerRelease.countDown()
            runtime.close()
        }
    }

    @Test
    fun `resume on the exact paused cut preserves ingress without rollover`() {
        val cut = AtomicReference(ownership())
        val mapper = AndroidVisibilityGridMappingAdmission(cut::get)
        val runtime = runtime(cut, mapper)
        try {
            assertTrue(runtime.offerFeature(feature(cut.get(), 100, 1)))
            await { mapper.snapshot().admittedFeatures == 1L }
            runtime.pause()
            assertTrue(runtime.resume())
            assertEquals(
                feature(cut.get(), 100, 1).payloadBytes.toLong(),
                mapper.snapshot().residentBytes,
            )
            assertEquals(0, mapper.snapshot().rolloverCount)
            assertEquals(1, runtime.snapshot().sameCutResumeCount)
            assertEquals(0, runtime.snapshot().ownershipRolloverCount)
            assertTrue(runtime.offerFeature(feature(cut.get(), 101, 2)))
            await { mapper.snapshot().admittedFeatures == 2L }
        } finally {
            runtime.close()
        }
    }

    @Test
    fun `resume requires current cut and replacement admits only the new generation`() {
        val cut = AtomicReference<VisibilityObservationOwnership?>(ownership())
        val mapper = AndroidVisibilityGridMappingAdmission(cut::get)
        val runtime = AndroidVisibilityGridRuntime(
            ownership = cut::get,
            mapper = mapper,
            scheduler = Executors.newScheduledThreadPool(2),
            featureIntervalNs = 1,
            depthIntervalNs = 1,
            ownsScheduler = true,
        )
        try {
            val old = cut.get()!!
            assertTrue(runtime.offerFeature(feature(old, 100, 1)))
            await { mapper.snapshot().admittedFeatures == 1L }
            runtime.pause()
            cut.set(null)
            assertFalse(runtime.resume())
            val replacement = old.copy(
                bindingGeneration = 2,
                lifecycleSequence = 2,
                operationGeneration = 2,
            )
            cut.set(replacement)
            assertTrue(runtime.resume())
            assertFalse(runtime.offerFeature(feature(old, 2, 1)))
            // A replacement cut has an independent timestamp domain.
            assertTrue(runtime.offerFeature(feature(replacement, 1, 2)))
            await { mapper.snapshot().admittedFeatures == 2L }

            val receipt = mapper.snapshot().lastReceipt!!
            assertEquals(2, receipt.bindingGeneration)
            assertEquals(2, receipt.lifecycleSequence)
            assertEquals(1, receipt.sourceTimestampNs)
            assertEquals(VisibilityFeatureObservation.FEATURE_FIXED_BYTES + 32, receipt.payloadBytes)
            assertEquals(1, receipt.sampleCount)
            assertEquals(1, runtime.snapshot().staleGenerationObservations)
            assertEquals(1, runtime.snapshot().ownershipRolloverCount)
            assertEquals(1, runtime.snapshot().rolloverDiscardedIngressObservations)
            assertEquals(1, mapper.snapshot().rolloverCount)
            assertEquals(1, mapper.snapshot().rolloverDiscardedObservations)
            assertEquals(2, mapper.snapshot().rolloverBindingGeneration)
            assertEquals(1, mapper.snapshot().rolloverGroupGeneration)
            assertEquals(2, mapper.snapshot().rolloverLifecycleSequence)
        } finally {
            runtime.close()
        }
        assertEquals(0, mapper.snapshot().residentBytes)
    }

    @Test
    fun `close drains an admitted mapping operation then releases bounded ingress`() {
        val cut = AtomicReference(ownership())
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val closed = CountDownLatch(1)
        val mapper = AndroidVisibilityGridMappingAdmission(
            ownership = cut::get,
            beforeAdmission = {
                entered.countDown()
                release.await(2, TimeUnit.SECONDS)
            },
        )
        val runtime = runtime(cut, mapper)
        assertTrue(runtime.offerFeature(feature(cut.get(), 1, 1)))
        assertTrue(entered.await(1, TimeUnit.SECONDS))
        val closer = Thread {
            runtime.close()
            closed.countDown()
        }.also(Thread::start)
        assertFalse(closed.await(25, TimeUnit.MILLISECONDS))
        release.countDown()
        assertTrue(closed.await(1, TimeUnit.SECONDS))
        closer.join()
        assertEquals(0, mapper.snapshot().residentBytes)
        assertEquals(0, runtime.snapshot().resourceBalance)
    }

    @Test
    fun `two millisecond p95 is exact and sheds depth until bounded recovery`() {
        val cut = AtomicReference(ownership())
        val clock = AtomicLong(1)
        val runtime = AndroidVisibilityGridRuntime(
            ownership = cut::get,
            mapper = AndroidVisibilityGridMappingAdmission(cut::get),
            scheduler = Executors.newScheduledThreadPool(2),
            nanoTime = clock::get,
            featureIntervalNs = 125_000_000,
            depthIntervalNs = 250_000_000,
            ownsScheduler = true,
            callbackCopySampleCapacity = 4,
            captureSafe = VisibilityCaptureSafePredicate { true },
        )
        try {
            runtime.setDepthCapability(VisibilityDepthCapability.AUTOMATIC)
            assertTrue(runtime.offerFeature(feature(cut.get(), 1, 1), 2_000_000))
            assertEquals("withinBudget", runtime.snapshot().callbackCopyBudgetState)
            assertTrue(runtime.offerFeature(feature(cut.get(), 2, 2), 2_000_001))
            var health = runtime.snapshot()
            assertEquals("severeDepthShedCaptureSafeFeature1Hz", health.callbackCopyBudgetState)
            assertEquals(1, health.callbackCopyDepthSheds)
            assertEquals(1_000, runtime.featureSampleCapacity())
            assertFalse(runtime.shouldCopyDepth(1_000_000_000))
            assertTrue(runtime.shouldCopyFeature(1_000_000_000))
            assertFalse(runtime.shouldCopyFeature(1_999_999_999))
            assertTrue(runtime.shouldCopyFeature(2_000_000_000))

            repeat(4) { index ->
                assertTrue(
                    runtime.offerFeature(
                        feature(cut.get(), 3L + index, 3 + index),
                        2_000_000,
                    ),
                )
            }
            clock.addAndGet(30_000_000_000)
            assertTrue(runtime.offerFeature(feature(cut.get(), 7, 7), 2_000_000))
            health = runtime.snapshot()
            assertEquals("withinBudget", health.callbackCopyBudgetState)
            assertEquals(1, health.callbackCopyBudgetRecoveries)
            assertEquals(VisibilitySourceHealth.CONFIGURED, health.depthHealth)
            assertEquals(V2_FEATURE_SAMPLE_CAPACITY, runtime.featureSampleCapacity())
        } finally {
            runtime.close()
        }
    }

    @Test
    fun `severe callback pressure pauses intake when capture safety is unproven`() {
        val cut = AtomicReference(ownership())
        val safe = AtomicBoolean(false)
        val runtime = AndroidVisibilityGridRuntime(
            ownership = cut::get,
            mapper = AndroidVisibilityGridMappingAdmission(cut::get),
            scheduler = Executors.newScheduledThreadPool(2),
            nanoTime = { 1 },
            featureIntervalNs = 125_000_000,
            depthIntervalNs = 250_000_000,
            ownsScheduler = true,
            callbackCopySampleCapacity = 2,
            captureSafe = VisibilityCaptureSafePredicate(safe::get),
        )
        try {
            runtime.setDepthCapability(VisibilityDepthCapability.AUTOMATIC)
            assertTrue(runtime.offerFeature(feature(cut.get(), 1, 1), 2_000_001))
            assertEquals("severeMapIntakePausedCaptureUnsafe", runtime.snapshot().callbackCopyBudgetState)
            assertEquals(0, runtime.featureSampleCapacity())
            assertFalse(runtime.shouldCopyFeature(1_000_000_000))
            assertFalse(runtime.shouldCopyDepth(1_000_000_000))
            assertFalse(runtime.offerFeature(feature(cut.get(), 2, 2), 1))
            assertEquals(1, runtime.snapshot().callbackCopyFeatureSheds)

            safe.set(true)
            assertEquals(1_000, runtime.featureSampleCapacity())
            assertTrue(runtime.shouldCopyFeature(1_000_000_000))
            assertFalse(runtime.shouldCopyFeature(1_999_999_999))
            assertTrue(runtime.shouldCopyFeature(2_000_000_000))
        } finally {
            runtime.close()
        }
    }

    @Test
    fun `copy cadence claims exact independent moving source intervals`() {
        val runtime = AndroidVisibilityGridRuntime(
            ownership = { ownership() },
            mapper = AndroidVisibilityGridMappingAdmission(ownership = { ownership() }),
            scheduler = Executors.newScheduledThreadPool(2),
            featureIntervalNs = 125_000_000,
            depthIntervalNs = 250_000_000,
            ownsScheduler = true,
        )
        try {
            assertTrue(runtime.shouldCopyFeature(1_000_000_000))
            assertFalse(runtime.shouldCopyFeature(1_124_999_999))
            assertTrue(runtime.shouldCopyFeature(1_125_000_000))

            assertFalse(runtime.shouldCopyDepth(1_000_000_000))
            runtime.setDepthCapability(VisibilityDepthCapability.AUTOMATIC)
            assertTrue(runtime.shouldCopyDepth(1_000_000_000))
            assertFalse(runtime.shouldCopyDepth(1_249_999_999))
            assertTrue(runtime.shouldCopyDepth(1_250_000_000))
        } finally {
            runtime.close()
        }
    }

    @Test
    fun `feature and depth mapping lanes remain independent while feature stalls`() {
        val cut = AtomicReference(ownership())
        val featureEntered = CountDownLatch(1)
        val featureRelease = CountDownLatch(1)
        val depthDelivered = CountDownLatch(1)
        val runtime = runtime(
            cut = cut,
            mapper = object : VisibilityObservationMapper {
                override fun admitFeature(observation: VisibilityFeatureObservation) {
                    featureEntered.countDown()
                    featureRelease.await(2, TimeUnit.SECONDS)
                }

                override fun admitDepth(observation: VisibilityDepthObservation) {
                    depthDelivered.countDown()
                }
            },
        )
        try {
            runtime.setDepthCapability(VisibilityDepthCapability.AUTOMATIC)
            assertTrue(runtime.offerFeature(feature(cut.get(), 1, 1)))
            assertTrue(featureEntered.await(1, TimeUnit.SECONDS))
            assertTrue(runtime.offerDepth(depth(cut.get(), 1, 1)))
            assertTrue(depthDelivered.await(1, TimeUnit.SECONDS))
            featureRelease.countDown()
            await { runtime.snapshot().admittedFeatureObservations == 1L }

            val health = runtime.snapshot()
            assertEquals(1, health.admittedFeatureObservations)
            assertEquals(1, health.admittedDepthObservations)
            assertEquals("healthy", health.totalGridHealth)
        } finally {
            featureRelease.countDown()
            runtime.close()
        }
    }

    @Test
    fun `latest feature replaces pending value and drains without catch up burst`() {
        val cut = AtomicReference(ownership())
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val delivered = CopyOnWriteArrayList<Int>()
        val complete = CountDownLatch(2)
        val deliveryTimes = CopyOnWriteArrayList<Long>()
        val runtime = runtime(
            cut = cut,
            intervalNs = 40_000_000,
            mapper = object : VisibilityObservationMapper {
                override fun admitFeature(observation: VisibilityFeatureObservation) {
                    delivered += observation.samples.single().id
                    deliveryTimes += System.nanoTime()
                    if (delivered.size == 1) {
                        entered.countDown()
                        release.await(2, TimeUnit.SECONDS)
                    }
                    complete.countDown()
                }

                override fun admitDepth(observation: VisibilityDepthObservation) = Unit
            },
        )
        try {
            runtime.offerFeature(feature(cut.get(), 1, 1))
            assertTrue(entered.await(1, TimeUnit.SECONDS))
            runtime.offerFeature(feature(cut.get(), 200_000_000, 2))
            runtime.offerFeature(feature(cut.get(), 400_000_000, 3))
            release.countDown()
            assertTrue(complete.await(2, TimeUnit.SECONDS))

            assertEquals(listOf(1, 3), delivered)
            assertEquals(1, runtime.snapshot().replacedFeatureObservations)
            assertTrue(deliveryTimes[1] - deliveryTimes[0] >= 35_000_000)
        } finally {
            release.countDown()
            runtime.close()
        }
    }

    @Test
    fun `duplicate invalid unsupported and stale generation inputs are rejected exactly`() {
        val cut = AtomicReference(ownership())
        val runtime = runtime(cut)
        try {
            assertTrue(runtime.offerFeature(feature(cut.get(), 1, 1)))
            assertFalse(runtime.offerFeature(feature(cut.get(), 1, 2)))

            assertFalse(runtime.offerDepth(depth(cut.get(), 2, 1)))
            assertEquals(VisibilitySourceHealth.UNSUPPORTED, runtime.snapshot().depthHealth)

            runtime.setDepthCapability(VisibilityDepthCapability.RAW_DEPTH)
            assertTrue(runtime.offerDepth(depth(cut.get(), 2, 1)))
            assertFalse(runtime.offerDepth(depth(cut.get(), 2, 2)))

            val old = cut.get()
            cut.set(old.copy(bindingGeneration = old.bindingGeneration + 1))
            assertFalse(runtime.offerFeature(feature(old, 200_000_000, 3)))

            await { runtime.snapshot().admittedFeatureObservations == 0L ||
                runtime.snapshot().admittedFeatureObservations == 1L }
            val health = runtime.snapshot()
            assertEquals(1, health.duplicateFeatureObservations)
            assertEquals(1, health.duplicateDepthObservations)
            assertEquals(1, health.invalidDepthObservations)
            assertTrue(health.staleGenerationObservations >= 1)
        } finally {
            runtime.close()
        }
    }

    @Test
    fun `health projection is bounded and resource closure is balanced`() {
        val runtime = runtime(AtomicReference(ownership()))
        try {
            repeat(3) {
                runtime.recordProducerResourceAcquired()
                runtime.recordProducerResourceClosed()
            }
            runtime.recordFeatureTransientUnavailable()
            runtime.setDepthCapability(VisibilityDepthCapability.AUTOMATIC)
            repeat(3) { runtime.recordDepthFailure() }

            val health = runtime.snapshot()
            assertEquals(0, health.resourceBalance)
            assertEquals(VisibilitySourceHealth.TRANSIENT_UNAVAILABLE, health.featureHealth)
            assertEquals(VisibilitySourceHealth.FAILED, health.depthHealth)
            assertEquals("featureOnly", health.totalGridHealth)
            assertTrue(health.toWireMap().size <= 64)
            assertFalse(health.toWireMap().values.any { it is Collection<*> || it is ByteArray })
        } finally {
            runtime.close()
        }
    }

    @Test
    fun `pose and sample copies do not retain producer owned arrays or lists`() {
        val matrix = identityVisibilityGridTransform()
        val pose = VisibilityCameraPose.copyOf(matrix)
        matrix[12] = 99.0
        assertEquals(0.0, pose.worldFromCameraGl[12], 0.0)
        assertThrows(UnsupportedOperationException::class.java) {
            (pose.worldFromCameraGl as MutableList<Double>)[0] = 2.0
        }

        val mutable = mutableListOf(VisibilityFeatureSample(1, 0.0, 0.0, -1.0, 1.0))
        val copied = VisibilityFeatureObservation.copySamples(mutable)
        mutable.clear()
        assertEquals(1, copied.size)
        assertThrows(UnsupportedOperationException::class.java) {
            (copied as MutableList<VisibilityFeatureSample>).clear()
        }
    }

    @Test
    fun `DTO validation rejects non finite duplicate and over capacity feature payloads`() {
        val cut = ownership()
        assertThrows(IllegalArgumentException::class.java) {
            feature(cut, 1, 1).let {
                VisibilityFeatureObservation(
                    ownership = cut,
                    frame = it.frame,
                    samples = listOf(VisibilityFeatureSample(1, Double.NaN, 0.0, -1.0, 1.0)),
                    sourceRejectedSamples = 0,
                    payloadBytes = VisibilityFeatureObservation.FEATURE_FIXED_BYTES +
                        VisibilityFeatureObservation.FEATURE_SAMPLE_BYTES,
                )
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            val duplicate = VisibilityFeatureSample(1, 0.0, 0.0, -1.0, 1.0)
            VisibilityFeatureObservation(
                ownership = cut,
                frame = feature(cut, 1, 1).frame,
                samples = listOf(duplicate, duplicate),
                sourceRejectedSamples = 0,
                payloadBytes = VisibilityFeatureObservation.FEATURE_FIXED_BYTES +
                    2 * VisibilityFeatureObservation.FEATURE_SAMPLE_BYTES,
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            val samples = List(V2_FEATURE_SAMPLE_CAPACITY + 1) { index ->
                VisibilityFeatureSample(index, 0.0, 0.0, -1.0, 1.0)
            }
            VisibilityFeatureObservation(
                ownership = cut,
                frame = feature(cut, 1, 1).frame,
                samples = samples,
                sourceRejectedSamples = 0,
                payloadBytes = VisibilityFeatureObservation.FEATURE_FIXED_BYTES +
                    samples.size * VisibilityFeatureObservation.FEATURE_SAMPLE_BYTES,
            )
        }
    }

    private fun runtime(
        cut: AtomicReference<VisibilityObservationOwnership>,
        mapper: VisibilityObservationMapper = AndroidVisibilityGridMappingAdmission(cut::get),
        intervalNs: Long = 1_000_000,
    ) = AndroidVisibilityGridRuntime(
        ownership = cut::get,
        mapper = mapper,
        scheduler = Executors.newScheduledThreadPool(2),
        featureIntervalNs = intervalNs,
        depthIntervalNs = intervalNs,
        ownsScheduler = true,
    )

    private fun ownership() = VisibilityObservationOwnership(
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
    )

    private fun feature(
        cut: VisibilityObservationOwnership,
        timestampNs: Long,
        marker: Int,
    ): VisibilityFeatureObservation {
        val samples = VisibilityFeatureObservation.copySamples(
            listOf(VisibilityFeatureSample(marker, 0.0, 0.0, -1.0, 1.0)),
        )
        return VisibilityFeatureObservation(
            ownership = cut,
            frame = frame(VisibilityObservationSource.SYNTHETIC_FEATURE, timestampNs),
            samples = samples,
            sourceRejectedSamples = 0,
            payloadBytes = VisibilityFeatureObservation.FEATURE_FIXED_BYTES +
                samples.size * VisibilityFeatureObservation.FEATURE_SAMPLE_BYTES,
        )
    }

    private fun depth(
        cut: VisibilityObservationOwnership,
        timestampNs: Long,
        marker: Int,
    ): VisibilityDepthObservation {
        val samples = VisibilityDepthObservation.copySamples(
            listOf(VisibilityDepthSample(marker.mod(16), marker.mod(12), 1_000, 255)),
        )
        return VisibilityDepthObservation(
            ownership = cut,
            frame = frame(VisibilityObservationSource.SYNTHETIC_DEPTH, timestampNs),
            samples = samples,
            sourceRejectedSamples = 0,
            payloadBytes = VisibilityDepthObservation.DEPTH_FIXED_BYTES +
                samples.size * VisibilityDepthObservation.DEPTH_SAMPLE_BYTES,
        )
    }

    private fun frame(
        source: VisibilityObservationSource,
        timestampNs: Long,
    ) = VisibilityObservationFrame(
        source = source,
        frameSequence = timestampNs,
        frameTimestampNs = timestampNs,
        sourceTimestampNs = timestampNs,
        cameraIdentity = "synthetic-camera",
        tracking = true,
        imageOrientation = "landscape_right_x_right_y_down_v1",
        pose = VisibilityCameraPose.copyOf(identityVisibilityGridTransform()),
        intrinsics = VisibilityCameraIntrinsics(16, 12, 10.0, 10.0, 8.0, 6.0),
        depthCapability = if (source == VisibilityObservationSource.SYNTHETIC_DEPTH) {
            VisibilityDepthCapability.RAW_DEPTH
        } else {
            VisibilityDepthCapability.UNSUPPORTED
        },
    )

    private fun await(condition: () -> Boolean) {
        repeat(100) {
            if (condition()) return
            Thread.sleep(5)
        }
        assertTrue("condition was not reached", condition())
    }
}
