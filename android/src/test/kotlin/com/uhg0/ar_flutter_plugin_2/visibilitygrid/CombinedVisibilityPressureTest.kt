package com.uhg0.ar_flutter_plugin_2.visibilitygrid

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CombinedVisibilityPressureTest {
    @Test
    fun `combined feature and depth overload remains latest-only and scalar`() {
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
            ownsScheduler = true,
        )
        try {
            runtime.setDepthCapability(VisibilityDepthCapability.AUTOMATIC)
            repeat(10) { index ->
                val count = if (index == 0) V2_FEATURE_SAMPLE_CAPACITY else 1
                assertTrue(runtime.offerFeature(feature(cut.get(), index + 1L, count), 1_500_000))
            }
            repeat(10) { index ->
                val count = if (index == 0) V2_DEPTH_SAMPLE_CAPACITY else 1
                assertTrue(runtime.offerDepth(depth(cut.get(), index + 101L, count), 1_500_000))
            }
            repeat(4) {
                runtime.recordProducerResourceAcquired()
                runtime.recordProducerResourceClosed()
            }

            runtime.pause()
            val receipt = VisibilityPressureReceipt.capture(
                runtime.snapshot(),
                VisibilityPressureOwnerScalars(
                    geometryRevision = 31,
                    lineageRevision = 2,
                    coverageRevision = 19,
                    styleRevision = 5,
                    canonicalSurfaceHighWater = 100_000,
                    associationHighWater = 200_000,
                    canonicalOwnedBytes = 16L * 1024L * 1024L,
                    terminalGuidanceStatus = "tracking",
                ),
            )

            assertEquals(10, receipt.featureOffered)
            assertEquals(0, receipt.featureAdmitted)
            assertEquals(8, receipt.featureCoalesced)
            assertEquals(2, receipt.featureDropped)
            assertEquals(V2_FEATURE_SAMPLE_CAPACITY.toLong(), receipt.featureMaxSamples)
            assertEquals(8, receipt.featureMaxHz)
            assertEquals(10, receipt.depthOffered)
            assertEquals(0, receipt.depthAdmitted)
            assertEquals(8, receipt.depthCoalesced)
            assertEquals(2, receipt.depthDropped)
            assertEquals(V2_DEPTH_SAMPLE_CAPACITY.toLong(), receipt.depthMaxSamples)
            assertEquals(4, receipt.depthMaxHz)
            assertEquals(1_500, receipt.callbackCopyP95Micros)
            assertEquals(1, receipt.featureLatestSlots)
            assertEquals(1, receipt.depthLatestSlots)
            assertEquals(0, receipt.catchUpBursts)
            assertEquals(4, receipt.imagesAcquired)
            assertEquals(receipt.imagesAcquired, receipt.imagesReleased)
            assertEquals(0, receipt.rootIsolateSurfaceBytes)
            assertEquals(0, receipt.rootIsolateImageBytes)
            assertTrue(receipt.toWireMap().values.all { value ->
                value is Number || value is String || value is Boolean
            })
        } finally {
            blockerRelease.countDown()
            runtime.close()
        }
    }

    private fun ownership() = VisibilityObservationOwnership(
        sessionId = "01".repeat(16),
        sessionGeneration = 3,
        captureGroupId = "02".repeat(16),
        groupGeneration = 7,
        coverageEpoch = 1,
        arSessionIdentity = "03".repeat(16),
        viewInstanceId = "04".repeat(16),
        viewGeneration = 1,
        nativeStreamToken = "05".repeat(16),
        workerBindingToken = "06".repeat(16),
        bindingGeneration = 4,
        lifecycleSequence = 1,
        operationGeneration = 1,
        groupFrame = VisibilityGroupFrame.copyOf(
            identityVisibilityGridTransform(),
            identityVisibilityGridTransform(),
            1_000,
            100_000,
        ),
    )

    private fun feature(
        cut: VisibilityObservationOwnership,
        timestampNs: Long,
        count: Int,
    ): VisibilityFeatureObservation {
        val samples = VisibilityFeatureObservation.copySamples(
            List(count) { index ->
                VisibilityFeatureSample(index, index / 1_000.0, 0.0, -1.0, 1.0)
            },
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
        count: Int,
    ): VisibilityDepthObservation {
        val samples = VisibilityDepthObservation.copySamples(
            List(count) { index ->
                VisibilityDepthSample(index % 48, index / 48, 1_000, 255)
            },
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

    private fun frame(source: VisibilityObservationSource, timestampNs: Long) =
        VisibilityObservationFrame(
            source = source,
            frameSequence = timestampNs,
            frameTimestampNs = timestampNs,
            sourceTimestampNs = timestampNs,
            cameraIdentity = "pressure-camera",
            tracking = true,
            imageOrientation = "landscape_right_x_right_y_down_v1",
            pose = VisibilityCameraPose.copyOf(identityVisibilityGridTransform()),
            intrinsics = VisibilityCameraIntrinsics(48, 32, 40.0, 40.0, 24.0, 16.0),
            depthCapability = if (source == VisibilityObservationSource.SYNTHETIC_DEPTH) {
                VisibilityDepthCapability.RAW_DEPTH
            } else {
                VisibilityDepthCapability.UNSUPPORTED
            },
        )
}
