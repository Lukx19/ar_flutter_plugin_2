package com.uhg0.ar_flutter_plugin_2.visibilitygrid

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
                acquisitionMode = DepthAcquisitionMode.RAW)
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
        override fun admitDepth(observation: VisibilityDepthObservation) { admitted++ }
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
            acquisitionMode = mode)
    }

    private fun owner() = VisibilityObservationOwnership("01".repeat(16), 1, "02".repeat(16), 1, 1,
        "03".repeat(16), "04".repeat(16), 1, "05".repeat(16), "06".repeat(16), 1, 1, 1,
        VisibilityGroupFrame.copyOf(identityVisibilityGridTransform(), identityVisibilityGridTransform(), 100_000, 100_000))

    private fun await(condition: () -> Boolean) {
        repeat(100) { if (condition()) return; Thread.sleep(5) }
        assertTrue(condition())
    }
}
