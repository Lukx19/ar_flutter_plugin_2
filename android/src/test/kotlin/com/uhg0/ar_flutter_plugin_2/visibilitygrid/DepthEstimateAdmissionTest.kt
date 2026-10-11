package com.uhg0.ar_flutter_plugin_2.visibilitygrid

import com.google.ar.core.Config
import com.uhg0.ar_flutter_plugin_2.BuildConfig
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DepthEstimateAdmissionTest {
    @Test
    fun `dense experiment requires explicit Dense debug flag and preserves raw SDK routing`() {
        assertFalse(denseCurrentFrameExperimentForPreference(DepthSourcePreference.DENSE, debug = false, enabled = true))
        assertFalse(denseCurrentFrameExperimentForPreference(DepthSourcePreference.DENSE, debug = true, enabled = false))
        assertFalse(denseCurrentFrameExperimentForPreference(null, debug = true, enabled = true))
        assertFalse(denseCurrentFrameExperimentForPreference(DepthSourcePreference.RAW, debug = true, enabled = true))
        assertTrue(denseCurrentFrameExperimentForPreference(DepthSourcePreference.DENSE, debug = true, enabled = true))
        assertEquals(DepthAcquisitionMode.RAW, depthAcquisitionModeForSession(Config.DepthMode.RAW_DEPTH_ONLY, false, true))
        assertEquals(DepthAcquisitionMode.RAW, depthAcquisitionModeForSession(Config.DepthMode.AUTOMATIC, true, true))
        assertEquals(DepthAcquisitionMode.AUTOMATIC, depthAcquisitionModeForSession(Config.DepthMode.AUTOMATIC, false, false))
        val calls = mutableListOf<String>()
        val image = DenseExperimentImage(1)
        val selected = selectedDepthAcquirer(DepthAcquisitionMode.AUTOMATIC_CURRENT_FRAME_EXPERIMENTAL,
            { calls += "dense16"; image }, { error("RAW source must stay separate") }, { error("Dense has no paired confidence") })
        assertTrue(selected.acquireDepth() === image)
        assertEquals(null, selected.acquireConfidence())
        assertEquals(listOf("dense16"), calls)
        image.close()
        assertEquals(1, image.closes)
        assertEquals(1_024L, DepthAcquisitionPortableMemory.MAXIMUM_INCREMENT_BYTES)
        assertEquals(40L, DepthAcquisitionPortableMemory.OBSERVATION_INCREMENT_BYTES)
    }

    @Test
    fun `experimental Dense tuple freezes projection and reaches runtime only in qualified build`() {
        // The second estimate is deliberately an unqualified earlier-image association fixture.
        for (imageTimestamp in listOf(878831567435399L, 878831300000000L)) {
            val cut = AtomicReference(denseExperimentOwner())
            val mode = depthAcquisitionModeForSession(Config.DepthMode.AUTOMATIC,
                rawDepthCandidateForPreference(cut.get().groupFrame.depthSourcePreference),
                denseCurrentFrameExperimentForPreference(cut.get().groupFrame.depthSourcePreference))
            val image = DenseExperimentImage(imageTimestamp)
            val pose = FloatArray(16) { if (it % 5 == 0) 1f else 0f }
            val focal = floatArrayOf(320f, 320f)
            var acquired = 0
            var closed = 0
            val prepared = prepareRawDepthFrame(
                selectedDepthAcquirer(mode, { image }, { error("explicit Dense must not acquire RAW") }, { error("Dense confidence") }),
                { width, height -> freezeTextureDepthMetadata(
                    878831569982201L, 1L, 1L, true, width, height, intArrayOf(640, 480),
                    focal, floatArrayOf(320f, 240f), pose, intArrayOf(640, 480), mode,
                    cameraImageTimestampNs = 878831543956954L,
                ).copy(acquisitionFrameBeforeNs = 878831569982201L, acquisitionAndroidCameraBeforeNs = 878831543956954L) },
                onResourceAcquired = { acquired++ }, onResourceClosed = { closed++ },
            )
            if (!denseCurrentFrameExperimentForPreference(cut.get().groupFrame.depthSourcePreference)) {
                assertTrue(prepared is PreparedRawDepthResult.Refused)
                val reason = (prepared as PreparedRawDepthResult.Refused).reason
                assertEquals(if (imageTimestamp > 878831543956954L) DepthAcquisitionRefusal.INVALID_TIMESTAMP
                    else DepthAcquisitionRefusal.STALE_AUTOMATIC, reason)
                assertEquals(0, image.reads)
                assertEquals(1, image.closes)
                assertEquals(acquired, closed)
                continue
            }
            assertTrue(BuildConfig.DEBUG && BuildConfig.DENSE_CURRENT_FRAME_EXPERIMENT)
            val frozen = (prepared as PreparedRawDepthResult.Ready).frame
            pose[12] = 50f
            focal[0] = 1f
            assertEquals(0.0, frozen.metadata.worldFromCameraGl[12], 0.0)
            assertEquals(80.0, frozen.metadata.intrinsics.fx, 0.0)
            val adapter = ArCoreVisibilityObservationAdapter({}, {})
            val scheduler = Executors.newSingleThreadScheduledExecutor()
            val mapper = Mapper()
            val runtime = runtime(cut, mapper, scheduler)
            try {
                runtime.setDepthCapability(VisibilityDepthCapability.AUTOMATIC)
                fund(runtime, cut.get())
                val copied = adapter.finishDepthPacked(PreparedVisibilityDepthFrame(
                    frozen, cut.get(), VisibilityDepthCapability.AUTOMATIC, 1L, 878831569982201L,
                )) as VisibilityDepthCopyResult.PackedObservation
                assertEquals(DepthAcquisitionMode.AUTOMATIC_CURRENT_FRAME_EXPERIMENTAL, copied.metadata.acquisitionMode)
                assertEquals(imageTimestamp, copied.metadata.timestampNs)
                assertEquals(0.0, copied.metadata.worldFromCameraGl[12], 0.0)
                val observation = copied.toObservation()
                assertEquals(DepthProjectionProvenance.DENSE_CURRENT_FRAME_EXPERIMENTAL, observation.projectionProvenance)
                assertFalse(observation.rawProjectionQualified)
                assertEquals(878831569982201L, observation.frame.frameTimestampNs)
                assertTrue(observation.sampleCount in 1..4_096)
                assertTrue(runtime.offerDepth(observation, 0))
                settle(runtime, scheduler)
                assertEquals(1, mapper.admitted)
                assertEquals(1L, runtime.snapshot().freshEstimateAdmissions)
                assertEquals(1, image.closes)
                assertEquals(acquired, closed)
                frozen.close()
                assertEquals(1, image.closes)
                val replay = experimentalDenseObservation(cut.get(), imageTimestamp, 878831600000000L)
                assertFalse(runtime.offerDepth(replay, 0))
                assertEquals(1L, runtime.snapshot().duplicateEstimateRefusals)
                val oldOwner = cut.get()
                cut.set(oldOwner.copy(bindingGeneration = 2L, groupGeneration = 2L, operationGeneration = 2L))
                runtime.setDepthCapability(VisibilityDepthCapability.AUTOMATIC)
                fund(runtime, cut.get())
                assertFalse(runtime.offerDepth(experimentalDenseObservation(oldOwner, imageTimestamp + 1L, 878831600000001L), 0))
                assertEquals(1L, runtime.snapshot().staleGenerationObservations)
                runtime.close()
                assertEquals(0, adapter.packedLeaseReceipts().getValue("depth").outstanding)
                assertFalse(runtime.offerDepth(experimentalDenseObservation(cut.get(), imageTimestamp + 2L, 878831600000002L), 0))
            } finally { runtime.close(); adapter.closePackedPools() }
        }
    }

    @Test
    fun `experimental Dense refuses changed boundary untracked pose and runtime marker without qualification`() {
        val variants = listOf(
            denseExperimentMetadata().copy(acquisitionFrameBeforeNs = 1L),
            denseExperimentMetadata().copy(acquisitionAndroidCameraBeforeNs = 1L),
            denseExperimentMetadata().copy(tracking = false),
            denseExperimentMetadata().copy(worldFromCameraGl = identityVisibilityGridTransform().also { it[12] = Double.NaN }),
            denseExperimentMetadata().copy(sourceImageWidth = 0),
        )
        for (metadata in variants) {
            val image = DenseExperimentImage(878831567435399L)
            val result = prepareRawDepthFrame(calibrationAcquirer(image, null), { _, _ -> metadata })
            assertTrue(result is PreparedRawDepthResult.Refused)
            assertEquals(DepthAcquisitionRefusal.INVALID_PROJECTION, (result as PreparedRawDepthResult.Refused).reason)
            assertEquals(0, image.reads)
            assertEquals(1, image.closes)
        }
        val cut = AtomicReference(denseExperimentOwner())
        val scheduler = Executors.newSingleThreadScheduledExecutor()
        val runtime = runtime(cut, Mapper(), scheduler)
        try {
            runtime.setDepthCapability(VisibilityDepthCapability.AUTOMATIC)
            fund(runtime, cut.get())
            val observation = experimentalDenseObservation(cut.get(), 100L, 200L)
            val expected = denseCurrentFrameExperimentForPreference(cut.get().groupFrame.depthSourcePreference)
            assertEquals(expected, runtime.offerDepth(observation, 0))
            if (expected) settle(runtime, scheduler)
            else assertEquals(1L, runtime.snapshot().invalidDepthObservations)
            cut.set(cut.get().copy(groupFrame = VisibilityGroupFrame.copyOf(
                identityVisibilityGridTransform(), identityVisibilityGridTransform(), 100_000, 100_000,
                depthSourcePreference = DepthSourcePreference.RAW,
            ), groupGeneration = 2L, operationGeneration = 2L))
            runtime.setDepthCapability(VisibilityDepthCapability.AUTOMATIC)
            fund(runtime, cut.get())
            assertFalse(runtime.offerDepth(experimentalDenseObservation(cut.get(), 101L, 201L), 0))
        } finally { runtime.close() }
    }

    private fun denseExperimentOwner() = owner().copy(groupFrame = VisibilityGroupFrame.copyOf(
        identityVisibilityGridTransform(), identityVisibilityGridTransform(), 100_000, 100_000,
        depthSourcePreference = DepthSourcePreference.DENSE,
    ))

    private fun denseExperimentMetadata() = freezeTextureDepthMetadata(
        878831569982201L, 1L, 1L, true, 160, 90, intArrayOf(640, 480),
        floatArrayOf(320f, 320f), floatArrayOf(320f, 240f), FloatArray(16) { if (it % 5 == 0) 1f else 0f },
        intArrayOf(640, 480), DepthAcquisitionMode.AUTOMATIC_CURRENT_FRAME_EXPERIMENTAL,
        cameraImageTimestampNs = 878831543956954L,
    ).copy(acquisitionFrameBeforeNs = 878831569982201L, acquisitionAndroidCameraBeforeNs = 878831543956954L)

    private fun experimentalDenseObservation(owner: VisibilityObservationOwnership, image: Long, frame: Long): VisibilityDepthObservation {
        val base = observation(owner, image, frame, DepthAcquisitionMode.AUTOMATIC)
        return VisibilityDepthObservation(ownership = owner, frame = base.frame, samples = base.samples,
            sourceRejectedSamples = 0, payloadBytes = base.payloadBytes, cameraImageTimestampNs = frame,
            acquisitionMode = DepthAcquisitionMode.AUTOMATIC_CURRENT_FRAME_EXPERIMENTAL,
            projectionProvenance = DepthProjectionProvenance.DENSE_CURRENT_FRAME_EXPERIMENTAL)
    }

    private class DenseExperimentImage(override val timestampNs: Long) : RawDepthImage {
        override val width = 160
        override val height = 90
        var reads = 0
        var closes = 0
        override fun unsignedValue(x: Int, y: Int): Int { reads++; return 1_000 }
        override fun close() { closes++ }
    }

    @Test
    fun `raw estimate votes once and owner replacement precedes duplicate gate`() {
        val cut = AtomicReference(owner())
        val scheduler = Executors.newSingleThreadScheduledExecutor()
        val mapper = Mapper()
        val runtime = runtime(cut, mapper, scheduler)
        try {
            fund(runtime, cut.get())
            assertTrue(runtime.offerDepth(observation(cut.get(), 100, 200), 0))
            settle(runtime, scheduler)
            assertFalse(runtime.offerDepth(observation(cut.get(), 100, 201), 0))
            assertEquals(1L, runtime.snapshot().freshEstimateAdmissions)
            assertEquals(1L, runtime.snapshot().duplicateEstimateRefusals)
            assertEquals(1L, runtime.snapshot().rawReprojectedAcquisitions)
            val old = cut.get()
            cut.set(old.copy(bindingGeneration = 2, groupGeneration = 2, operationGeneration = 2))
            fund(runtime, cut.get())
            val duplicates = runtime.snapshot().duplicateDepthObservations
            assertFalse(runtime.offerDepth(observation(old, 1, 202), 0))
            assertEquals(duplicates, runtime.snapshot().duplicateDepthObservations)
            assertEquals(1L, runtime.snapshot().staleGenerationObservations)
            assertTrue(runtime.offerDepth(observation(cut.get(), 2, 203), 0))
            settle(runtime, scheduler)
            assertEquals(2, mapper.admitted)
            assertEquals(2L, runtime.snapshot().freshEstimateAdmissions)
        } finally { runtime.close() }
    }

    @Test
    fun `stale automatic admission refuses without consuming image freshness`() {
        val cut = AtomicReference(owner())
        val scheduler = Executors.newSingleThreadScheduledExecutor()
        val runtime = runtime(cut, Mapper(), scheduler)
        try {
            fund(runtime, cut.get())
            assertFalse(runtime.offerDepth(observation(cut.get(), 10, 20, DepthAcquisitionMode.AUTOMATIC), 0))
            assertEquals(1L, runtime.snapshot().staleAutomaticRefusals)
            assertEquals(0L, runtime.snapshot().freshEstimateAdmissions)
            assertTrue(runtime.offerDepth(observation(cut.get(), 10, 10, DepthAcquisitionMode.AUTOMATIC), 0))
            settle(runtime, scheduler)
            assertEquals(1L, runtime.snapshot().freshEstimateAdmissions)
        } finally { runtime.close() }
    }

    @Test
    fun `automatic runtime admits exact camera identity across AR offsets and refuses replay`() {
        val cut = AtomicReference(owner())
        val scheduler = Executors.newSingleThreadScheduledExecutor()
        val mapper = Mapper()
        val runtime = runtime(cut, mapper, scheduler)
        try {
            fund(runtime, cut.get())
            val first = observation(cut.get(), 100, 90, DepthAcquisitionMode.AUTOMATIC)
            val current = VisibilityDepthObservation(ownership = first.ownership, frame = first.frame,
                samples = first.samples, sourceRejectedSamples = first.sourceRejectedSamples,
                payloadBytes = first.payloadBytes, acquisitionMode = first.acquisitionMode,
                cameraImageTimestampNs = 100)
            assertTrue(runtime.offerDepth(current, 0))
            settle(runtime, scheduler)
            val next = observation(cut.get(), 101, 110, DepthAcquisitionMode.AUTOMATIC)
            val second = VisibilityDepthObservation(ownership = next.ownership, frame = next.frame,
                samples = next.samples, sourceRejectedSamples = next.sourceRejectedSamples,
                payloadBytes = next.payloadBytes, acquisitionMode = next.acquisitionMode,
                cameraImageTimestampNs = 101)
            assertTrue(runtime.offerDepth(second, 0))
            settle(runtime, scheduler)
            val replay = observation(cut.get(), 101, 101, DepthAcquisitionMode.AUTOMATIC)
            assertFalse(runtime.offerDepth(replay, 0))
            assertEquals(2, mapper.admitted)
            assertEquals(2L, runtime.snapshot().freshEstimateAdmissions)
            assertEquals(1L, runtime.snapshot().duplicateEstimateRefusals)
        } finally { runtime.close() }
    }

    @Test
    fun `same cut pause retains estimate fence while late funding cannot reopen newer pause`() {
        val cut = AtomicReference(owner())
        val scheduler = Executors.newSingleThreadScheduledExecutor()
        val mapper = Mapper()
        val runtime = runtime(cut, mapper, scheduler)
        try {
            fund(runtime, cut.get())
            assertTrue(runtime.offerDepth(observation(cut.get(), 10, 20), 0))
            settle(runtime, scheduler)
            runtime.pause()
            assertFalse(runtime.prepareDepthAcquisition(cut.get()))
            assertTrue(runtime.resume())
            assertFalse(runtime.offerDepth(observation(cut.get(), 10, 21), 0))
            cut.set(cut.get().copy(bindingGeneration = 2, operationGeneration = 2))
            mapper.hold = true
            assertFalse(runtime.prepareDepthAcquisition(cut.get()))
            assertEquals(2, mapper.requests)
            runtime.fencePause()
            mapper.finish(true)
            assertFalse(runtime.prepareDepthAcquisition(cut.get()))
            assertFalse(runtime.offerDepth(observation(cut.get(), 2, 22), 0))
            assertEquals(1L, runtime.snapshot().freshEstimateAdmissions)
        } finally { runtime.close() }
    }

    @Test
    fun `funding remains single pending and failed or old completion cannot admit images`() {
        val cut = AtomicReference(owner())
        val mapper = Mapper().also { it.hold = true }
        val runtime = runtime(cut, mapper)
        try {
            repeat(20) { assertFalse(runtime.prepareDepthAcquisition(cut.get())) }
            assertEquals(1, mapper.requests)
            assertFalse(runtime.offerDepth(observation(cut.get(), 10, 20), 0))
            cut.set(cut.get().copy(bindingGeneration = 2, operationGeneration = 2))
            mapper.finish(true)
            assertFalse(runtime.prepareDepthAcquisition(cut.get()))
            assertEquals(2, mapper.requests)
            mapper.finish(false)
            repeat(20) { assertFalse(runtime.prepareDepthAcquisition(cut.get())) }
            assertEquals(2, mapper.requests)
            assertFalse(runtime.offerDepth(observation(cut.get(), 10, 20), 0))
            assertEquals(0L, runtime.snapshot().freshEstimateAdmissions)
        } finally { runtime.close() }
    }

    @Test
    fun `lifecycle busy refusal releases lease and leaves estimate admissible`() {
        val cut = AtomicReference(owner())
        val scheduler = Executors.newSingleThreadScheduledExecutor()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val runtime = AndroidVisibilityGridRuntime(cut::get, Mapper(), scheduler = scheduler, ownsScheduler = true,
            beforeDepthCapabilityFence = { entered.countDown(); check(release.await(2, TimeUnit.SECONDS)) })
        val pool = DepthSamplesLeasePool(capacity = 12)
        try {
            runtime.setDepthCapability(VisibilityDepthCapability.RAW_DEPTH)
            fund(runtime, cut.get())
            runtime.setDepthCapability(VisibilityDepthCapability.UNSUPPORTED)
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            val lease = requireNotNull(pool.tryAcquire(cut.get()))
            assertTrue(lease.append(0, 0, 1_000, 255))
            val value = observation(cut.get(), 10, 20)
            val leased = VisibilityDepthObservation.fromPacked(cut.get(), value.frame, lease.samples, 0,
                acquisitionMode = DepthAcquisitionMode.RAW, cameraImageTimestampNs = 20, rawProjectionQualified = true)
            assertFalse(runtime.offerDepth(leased, 0))
            assertEquals(0, pool.leasedCount())
            assertEquals(0L, runtime.snapshot().freshEstimateAdmissions)
            release.countDown()
            runtime.setDepthCapability(VisibilityDepthCapability.RAW_DEPTH)
            await { runtime.snapshotWireMap()["ingressDrainPending"] == false }
            assertTrue(runtime.offerDepth(observation(cut.get(), 10, 21), 0))
            settle(runtime, scheduler)
            assertEquals(1L, runtime.snapshot().freshEstimateAdmissions)
        } finally { release.countDown(); runtime.close(); pool.close() }
    }

    @Test
    fun `raw actual tuple rejects unqualified projection then admits once across projection frames`() {
        val cut = AtomicReference(owner())
        val scheduler = Executors.newSingleThreadScheduledExecutor()
        val mapper = Mapper()
        val runtime = runtime(cut, mapper, scheduler)
        try {
            runtime.setDepthCapability(VisibilityDepthCapability.AUTOMATIC)
            fund(runtime, cut.get())
            val imageTime = 845720289472481L
            val frameTime = 845720290140319L
            fun raw(time: Long, projection: Long, qualified: Boolean): VisibilityDepthObservation {
                val value = observation(cut.get(), time, projection)
                return VisibilityDepthObservation(ownership = value.ownership, frame = value.frame,
                    samples = value.samples, sourceRejectedSamples = 0, payloadBytes = value.payloadBytes,
                    acquisitionMode = DepthAcquisitionMode.RAW, cameraImageTimestampNs = 845720257661069L,
                    rawProjectionQualified = qualified)
            }
            assertFalse(runtime.offerDepth(raw(imageTime, frameTime, false)))
            assertEquals(0L, runtime.snapshot().freshEstimateAdmissions)
            assertTrue(runtime.offerDepth(raw(imageTime, frameTime, true)))
            settle(runtime, scheduler)
            assertEquals(0L, runtime.snapshot().rawReprojectedAcquisitions)
            assertFalse(runtime.offerDepth(raw(imageTime, frameTime + 100, true)))
            assertEquals(1L, runtime.snapshot().rawReprojectedAcquisitions)
            assertTrue(runtime.offerDepth(raw(imageTime + 1, frameTime + 200, true)))
            settle(runtime, scheduler)
            assertEquals(2, mapper.admitted)
            assertEquals(2L, runtime.snapshot().freshEstimateAdmissions)
            assertEquals(1L, runtime.snapshot().duplicateEstimateRefusals)
        } finally { runtime.close() }
    }

    private fun runtime(cut: AtomicReference<VisibilityObservationOwnership>, mapper: Mapper,
        scheduler: ScheduledExecutorService = Executors.newScheduledThreadPool(2)) =
        AndroidVisibilityGridRuntime(cut::get, mapper, scheduler = scheduler, ownsScheduler = true)
            .also { it.setDepthCapability(VisibilityDepthCapability.RAW_DEPTH) }

    private fun settle(runtime: AndroidVisibilityGridRuntime, scheduler: ScheduledExecutorService) {
        // Every call follows one accepted zero-delay offer; the owned FIFO worker
        // completes mapping, release and accounting before this test-thread barrier.
        scheduler.submit {}.get(500, TimeUnit.MILLISECONDS)
        assertEquals(0L, runtime.snapshot().residentPayloadBytes)
    }

    private fun fund(runtime: AndroidVisibilityGridRuntime, owner: VisibilityObservationOwnership) {
        runtime.prepareDepthAcquisition(owner)
        assertTrue(runtime.prepareDepthAcquisition(owner))
    }

    private class Mapper : VisibilityObservationMapper {
        var admitted = 0
        var requests = 0
        var hold = false
        private var completion: ((DepthAcquisitionPreparationResult) -> Unit)? = null
        override fun prepareDepthAcquisition(qualifier: DepthAcquisitionFunding, complete: (DepthAcquisitionPreparationResult) -> Unit) {
            requests++
            if (hold) completion = complete else complete(DepthAcquisitionPreparationResult.FUNDED)
        }
        fun finish(funded: Boolean) { val callback = requireNotNull(completion); completion = null; callback(if (funded) DepthAcquisitionPreparationResult.FUNDED else DepthAcquisitionPreparationResult.REFUSED) }
        override fun admitFeature(observation: VisibilityFeatureObservation) {}
        private var retainedDepth: VisibilityDepthObservation? = null
        override fun admitDepth(observation: VisibilityDepthObservation) {
            retainedDepth?.close()
            retainedDepth = observation
            admitted++
        }
        override fun close() {
            val owned = retainedDepth
            retainedDepth = null
            owned?.close()
        }
    }

    private fun observation(owner: VisibilityObservationOwnership, image: Long, frame: Long,
        mode: DepthAcquisitionMode = DepthAcquisitionMode.RAW): VisibilityDepthObservation {
        val samples = listOf(VisibilityDepthSample(0, 0, 1_000, 255))
        return VisibilityDepthObservation(ownership = owner,
            frame = VisibilityObservationFrame(VisibilityObservationSource.ARCORE_RAW_DEPTH, frame, frame, image,
                "arcore-rear-camera", true, "landscape_right_x_right_y_down_v1",
                VisibilityCameraPose.copyOf(identityVisibilityGridTransform()), VisibilityCameraIntrinsics(4, 3, 2.0, 2.0, 1.5, 1.0),
                if (mode == DepthAcquisitionMode.AUTOMATIC) VisibilityDepthCapability.AUTOMATIC else VisibilityDepthCapability.RAW_DEPTH),
            samples = samples, sourceRejectedSamples = 0,
            payloadBytes = VisibilityDepthObservation.DEPTH_FIXED_BYTES + VisibilityDepthObservation.DEPTH_SAMPLE_BYTES,
            acquisitionMode = mode, cameraImageTimestampNs = frame, rawProjectionQualified = mode == DepthAcquisitionMode.RAW)
    }

    private fun owner() = VisibilityObservationOwnership("01".repeat(16), 1, "02".repeat(16), 1, 1,
        "03".repeat(16), "04".repeat(16), 1, "05".repeat(16), "06".repeat(16), 1, 1, 1,
        VisibilityGroupFrame.copyOf(identityVisibilityGridTransform(), identityVisibilityGridTransform(), 100_000, 100_000))

    private fun await(condition: () -> Boolean) {
        repeat(100) { if (condition()) return; Thread.sleep(5) }
        assertTrue(condition())
    }
}
