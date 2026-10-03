package com.uhg0.ar_flutter_plugin_2.visibilitygrid

import com.uhg0.ar_flutter_plugin_2.sceneview.PressureRendererResources
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CombinedVisibilityPressureTest {
    @Test
    fun `admitted feature and depth overload remains latest-only with renderer resources live`() {
        val cut = AtomicReference(ownership())
        val scheduler = PressureObservationScheduler()
        val mapper = AndroidVisibilityGridMappingAdmission(cut::get)
        val renderer = PressureRendererResources()
        renderer.openCycle(0)
        val runtime = AndroidVisibilityGridRuntime(
            ownership = cut::get,
            mapper = mapper,
            scheduler = scheduler,
            nanoTime = { scheduler.nowNs },
            ownsScheduler = true,
        )
        try {
            runtime.setDepthCapability(VisibilityDepthCapability.AUTOMATIC)
            repeat(10) { index ->
                assertTrue(runtime.offerFeature(feature(cut.get(), index + 1L, V2_FEATURE_SAMPLE_CAPACITY), 1_500_000))
            }
            repeat(10) { index ->
                assertTrue(runtime.offerDepth(depth(cut.get(), index + 101L, V2_DEPTH_SAMPLE_CAPACITY), 1_500_000))
            }
            scheduler.advanceBy(0)
            assertEquals(1, runtime.snapshot().admittedFeatureObservations)
            assertEquals(1, runtime.snapshot().admittedDepthObservations)
            scheduler.advanceBy(124_999_999)
            assertEquals(1, runtime.snapshot().admittedFeatureObservations)
            scheduler.advanceBy(1)
            assertEquals(2, runtime.snapshot().admittedFeatureObservations)
            assertEquals(1, runtime.snapshot().admittedDepthObservations)
            scheduler.advanceBy(125_000_000)
            val nativeRenderer = renderer.telemetry.pressureSnapshot()
            val receipt = VisibilityPressureReceipt.capture(
                runtime.snapshot(),
                VisibilityPressureOwnerScalars(
                    rendererOwnedBytes = nativeRenderer.rendererOwnedBytes,
                    maxUploadBytesPerFrame = nativeRenderer.maxUploadBytesPerFrame,
                    terminalGuidanceStatus = "tracking",
                ),
            )

            assertEquals(13_197_572, receipt.rendererOwnedBytes)
            assertEquals(10, receipt.featureOffered)
            assertEquals(2, receipt.featureAdmitted)
            assertEquals(8, receipt.featureCoalesced)
            assertEquals(0, receipt.featureDropped)
            assertEquals(V2_FEATURE_SAMPLE_CAPACITY.toLong(), receipt.featureMaxSamples)
            assertEquals(8, receipt.featureMaxHz)
            assertEquals(10, receipt.depthOffered)
            assertEquals(2, receipt.depthAdmitted)
            assertEquals(8, receipt.depthCoalesced)
            assertEquals(0, receipt.depthDropped)
            assertEquals(V2_DEPTH_SAMPLE_CAPACITY.toLong(), receipt.depthMaxSamples)
            assertEquals(4, receipt.depthMaxHz)
            assertEquals(1_500, receipt.callbackCopyP95Micros)
            assertEquals(1, receipt.featureLatestSlots)
            assertEquals(1, receipt.depthLatestSlots)
            assertEquals(0, receipt.catchUpBursts)
            assertTrue(receipt.toWireMap().values.all { value ->
                value is Number || value is String || value is Boolean
            })
        } finally {
            runtime.close()
            renderer.closeCycle()
        }
        assertEquals(0, runtime.snapshot().residentPayloadBytes)
        assertEquals(0, mapper.snapshot().residentBytes)
        assertEquals(0, renderer.telemetry.pressureSnapshot().rendererOwnedBytes)
    }

    @Test
    fun `feature capacity refuses excess surfaces and associations without mutation`() {
        val canonical = populatedFeatureOwner()
        assertEquals(100_000, canonical.surfaceCount)
        assertEquals(200_000, canonical.associationCount)
        assertEquals(9_371_384, canonical.assignedTupleShareBytes)
    }

    @Test
    fun `ordinary cadence admits maximum samples and teardown releases retained ingress`() {
        val cut = AtomicReference(ownership())
        val scheduler = PressureObservationScheduler()
        val mapper = AndroidVisibilityGridMappingAdmission(cut::get)
        val runtime = AndroidVisibilityGridRuntime(cut::get, mapper, scheduler,
            nanoTime = { scheduler.nowNs })
        try {
            runtime.setDepthCapability(VisibilityDepthCapability.AUTOMATIC)
            repeat(32) { tick ->
                val now = scheduler.nowNs
                assertTrue(runtime.shouldCopyFeature(now))
                assertTrue(runtime.offerFeature(feature(cut.get(), now, V2_FEATURE_SAMPLE_CAPACITY), 1_500_000))
                if (tick % 2 == 0) {
                    assertTrue(runtime.shouldCopyDepth(now))
                    assertTrue(runtime.offerDepth(depth(cut.get(), now, V2_DEPTH_SAMPLE_CAPACITY), 1_500_000))
                } else assertFalse(runtime.shouldCopyDepth(now))
                scheduler.advanceBy(125_000_000)
            }
            val receipt = VisibilityPressureReceipt.capture(runtime.snapshot())
            assertEquals(32, receipt.featureAdmitted)
            assertEquals(16, receipt.depthAdmitted)
            assertEquals(0, receipt.featureCoalesced + receipt.depthCoalesced)
            assertEquals(0, receipt.featureDropped + receipt.depthDropped)
            assertEquals(1_500, receipt.callbackCopyP95Micros)
        } finally { runtime.close() }
        assertEquals(0, runtime.snapshot().residentPayloadBytes)
        assertEquals(0, mapper.snapshot().residentObservations)
    }

    @Test
    fun `severe pressure sheds supported depth and only capture-safe one hertz features resume`() {
        val cut = AtomicReference(ownership())
        val scheduler = PressureObservationScheduler()
        var captureSafe = true
        val runtime = AndroidVisibilityGridRuntime(cut::get,
            AndroidVisibilityGridMappingAdmission(cut::get), scheduler,
            nanoTime = { scheduler.nowNs }, callbackCopySampleCapacity = 2,
            captureSafe = VisibilityCaptureSafePredicate { captureSafe })
        try {
            runtime.setDepthCapability(VisibilityDepthCapability.AUTOMATIC)
            assertTrue(runtime.offerFeature(feature(cut.get(), 1, 1), 2_000_001))
            scheduler.advanceBy(0)
            assertEquals(VisibilitySourceHealth.TRANSIENT_UNAVAILABLE, runtime.snapshot().depthHealth)
            assertEquals(VisibilityDepthCapability.AUTOMATIC, runtime.snapshot().depthCapability)
            assertFalse(runtime.shouldCopyDepth(scheduler.nowNs))
            assertFalse(runtime.offerDepth(depth(cut.get(), 1, 1)))
            assertEquals(1_000, runtime.featureSampleCapacity())
            assertTrue(runtime.shouldCopyFeature(scheduler.nowNs))
            assertFalse(runtime.shouldCopyFeature(scheduler.nowNs + 999_999_999))
            assertTrue(runtime.shouldCopyFeature(scheduler.nowNs + 1_000_000_000))
            assertFalse(runtime.offerFeature(feature(cut.get(), 2, 1_001)))
            captureSafe = false
            assertFalse(runtime.shouldCopyFeature(scheduler.nowNs + 2_000_000_000))
            assertEquals(0, runtime.featureSampleCapacity())
            assertFalse(runtime.offerFeature(feature(cut.get(), 3, 1)))
            assertEquals(VisibilityDepthCapability.AUTOMATIC, runtime.snapshot().depthCapability)
        } finally { runtime.close() }
    }

    @Test
    fun `pause replacement and depth fault fence outstanding work without catch up`() {
        val cut = AtomicReference(ownership())
        val scheduler = PressureObservationScheduler()
        val mapper = AndroidVisibilityGridMappingAdmission(cut::get)
        val runtime = AndroidVisibilityGridRuntime(cut::get, mapper, scheduler,
            nanoTime = { scheduler.nowNs })
        try {
            runtime.setDepthCapability(VisibilityDepthCapability.AUTOMATIC)
            repeat(4) { index ->
                runtime.offerFeature(feature(cut.get(), index + 1L, 1))
                runtime.offerDepth(depth(cut.get(), index + 1L, 1))
            }
            runtime.pause()
            val old = cut.get()
            cut.set(old.copy(bindingGeneration = old.bindingGeneration + 1,
                operationGeneration = old.operationGeneration + 1))
            assertTrue(runtime.resume())
            assertFalse(runtime.offerFeature(feature(old, 10, 1)))
            scheduler.advanceBy(30_000_000_000)
            assertEquals(0, runtime.snapshot().admittedFeatureObservations)
            assertEquals(0, runtime.snapshot().admittedDepthObservations)
            assertTrue(runtime.offerFeature(feature(cut.get(), 1, 1)))
            assertTrue(runtime.offerDepth(depth(cut.get(), 1, 1)))
            scheduler.advanceBy(0)
            assertEquals(1, runtime.snapshot().admittedFeatureObservations)
            assertEquals(1, runtime.snapshot().admittedDepthObservations)
            repeat(3) { runtime.recordDepthFailure() }
            assertFalse(runtime.offerDepth(depth(cut.get(), 2, 1)))
            assertEquals(VisibilitySourceHealth.FAILED, runtime.snapshot().depthHealth)
            assertEquals(VisibilityDepthCapability.AUTOMATIC, runtime.snapshot().depthCapability)
            assertTrue(runtime.offerFeature(feature(cut.get(), 2, 1)))
            scheduler.advanceBy(125_000_000)
            assertEquals(2, runtime.snapshot().admittedFeatureObservations)
        } finally { runtime.close() }
        assertEquals(0, runtime.snapshot().residentPayloadBytes)
        assertEquals(0, mapper.snapshot().residentObservations)
    }

    private fun populatedFeatureOwner(): FeatureFusionResourceReceipt {
        val kernel = FeatureFusionKernel()
        var sequence = 0L
        repeat(2) { pass ->
            repeat(100) { page ->
                val observations = List(1_000) { offset ->
                    val index = page * 1_000 + offset
                    FeatureFusionEvidence(
                        index * 0.1 + 0.02, 0.02, 0.02, 2,
                        pass * 100_000 + index,
                        normalEvidence(index),
                    )
                }
                sequence++
                assertTrue(kernel.accept(FeatureFusionBatch(sequence, sequence, observations))
                    is FeatureFusionResult.Accepted)
            }
            if (pass == 0) {
                val before = kernel.resourceReceipt()
                val refused = kernel.accept(FeatureFusionBatch(
                    sequence + 1, sequence + 1,
                    listOf(FeatureFusionEvidence(
                        10_000.02, 0.02, 0.02, 2, 200_000, normalEvidence(100_000),
                    )),
                )) as FeatureFusionResult.Refused
                assertEquals(FeatureFusionRefusal.SURFACE_CAPACITY, refused.reason)
                assertEquals(before, refused.receipt)
                assertEquals(before, kernel.resourceReceipt())
            }
        }
        val receipt = kernel.resourceReceipt()
        // Independent reflection catches a newly retained primitive owner omitted from
        // accounting, while avoiding a full object-graph or platform-memory estimate.
        val arrays = kernel.javaClass.declaredFields.filter { it.type.isArray }.map { field ->
            field.isAccessible = true
            val array = field.get(kernel)
            val width = when (array) {
                is ByteArray, is BooleanArray -> 1
                is IntArray -> 4
                is LongArray -> 8
                else -> error("unaccounted retained array ${field.name}")
            }
            16 + java.lang.reflect.Array.getLength(array) * width
        }
        assertEquals(
            receipt.assignedTupleShareBytes,
            384 + arrays.sum() + kernel.normalEncoderPortableBytes(),
        )
        val refusal = kernel.accept(FeatureFusionBatch(
            sequence + 1, sequence + 1,
            listOf(FeatureFusionEvidence(0.02, 0.02, 0.02, 1, 200_000, normalEvidence(0))),
        )) as FeatureFusionResult.Refused
        assertEquals(FeatureFusionRefusal.ASSOCIATION_CAPACITY, refusal.reason)
        assertEquals(receipt, refusal.receipt)
        assertEquals(receipt, kernel.resourceReceipt())
        return receipt
    }

    private fun normalEvidence(index: Int) = FeatureNormalEvidence(
        index, 0, 0, index * 100 + 20, 20, 20, index * 100 + 1_020, 20, 20, 32_767,
    )

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
                VisibilityDepthSample(index % 64, index / 64, 1_000, 255)
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
            intrinsics = VisibilityCameraIntrinsics(64, 64, 40.0, 40.0, 32.0, 32.0),
            depthCapability = if (source == VisibilityObservationSource.SYNTHETIC_DEPTH) {
                VisibilityDepthCapability.RAW_DEPTH
            } else {
                VisibilityDepthCapability.UNSUPPORTED
            },
        )
}
