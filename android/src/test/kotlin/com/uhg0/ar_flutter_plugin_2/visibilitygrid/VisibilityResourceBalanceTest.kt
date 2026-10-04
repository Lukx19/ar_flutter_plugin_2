package com.uhg0.ar_flutter_plugin_2.visibilitygrid

import com.uhg0.ar_flutter_plugin_2.sceneview.PressureRendererResources
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class VisibilityResourceBalanceTest {
    @Test
    fun `native teardown receipt balances observed image and renderer resources`() {
        val cut = AtomicReference(ownership())
        val runtime = AndroidVisibilityGridRuntime(
            ownership = cut::get,
            mapper = AndroidVisibilityGridMappingAdmission(cut::get),
            scheduler = Executors.newScheduledThreadPool(2),
            ownsScheduler = true,
        )
        val resources = PressureRendererResources()
        repeat(3) { cycle ->
            resources.openCycle(cycle)
            // Copy and release actual leases at the production raw-depth seam:
            // complete pair, partial acquisition failure, then copy failure.
            val depth = PressureDepthImage(1_000, failRead = cycle == 2)
            val confidence = PressureDepthImage(255)
            val source = RawDepthCopySource(
                acquirer = object : PairedRawDepthAcquirer {
                    override fun acquireDepth(): RawDepthImage = depth
                    override fun acquireConfidence(): RawDepthImage {
                        if (cycle == 1) error("confidence acquisition failed")
                        return confidence
                    }
                },
                onResourceAcquired = runtime::recordProducerResourceAcquired,
                onResourceClosed = runtime::recordProducerResourceClosed,
            )
            val result = source.acquire(RawDepthFrameMetadata(
                timestampNs = cycle + 1L, groupGeneration = 1, sessionGeneration = 1,
                tracking = true, width = 4, height = 3,
                intrinsics = DepthIntrinsics(2.0, 2.0, 1.5, 1.0),
                worldFromCameraGl = identityVisibilityGridTransform(),
            ))
            assertTrue(if (cycle == 0) result is DepthAcquisitionResult.Observation
                else result is DepthAcquisitionResult.Failure)
            assertEquals(1, depth.closeCount)
            assertEquals(if (cycle == 1) 0 else 1, confidence.closeCount)
            runtime.pause()
            resources.closeCycle()
            assertTrue(runtime.resume())
        }
        runtime.close()
        val renderer = resources.telemetry.pressureSnapshot()
        val pages = resources.pages.pressureSnapshot()
        val owners = VisibilityPressureOwnerScalars.fromNativeOwners(
            CanonicalVisibilityPressureSnapshot(0, 0, 0, 0, 0, 0, 0, "closed"),
            renderer, pages,
        )

        val receipt = VisibilityPressureReceipt.capture(runtime.snapshot(), owners)
        assertEquals(3, runtime.snapshot().pauseCount)
        assertEquals(3, runtime.snapshot().resumeCount)
        assertEquals(0, receipt.rendererOwnedBytes)
        assertEquals(6, receipt.rendererResourcesAcquired)
        assertEquals(3, receipt.buffersAcquired)
        assertEquals(6, receipt.pagesAcquired)
        assertEquals(5, receipt.imagesAcquired)
        assertEquals(1, pages.maxPendingTransactions)
        assertTrue(pages.coalesced > 0)
        // Native page queue activity cannot stand in for unobserved Dart work.
        assertEquals(0, receipt.workerMaxPendingTransactions)
        assertEquals(0, receipt.workerCoalescedPresentations)
        assertEquals(receipt.imagesAcquired, receipt.imagesReleased)
        assertEquals(receipt.buffersAcquired, receipt.buffersReleased)
        assertEquals(receipt.callbacksAcquired, receipt.callbacksReleased)
        assertEquals(receipt.pagesAcquired, receipt.pagesReleased)
        assertEquals(receipt.rendererResourcesAcquired, receipt.rendererResourcesReleased)
        assertTrue(receipt.toWireMap().keys.none { it.contains("passed", ignoreCase = true) })
    }

    private class PressureDepthImage(private val value: Int, private val failRead: Boolean = false) : RawDepthImage {
        override val width = 4
        override val height = 3
        var closeCount = 0
            private set
        override fun unsignedValue(x: Int, y: Int): Int {
            if (failRead) error("depth copy failed")
            return value
        }
        override fun close() { closeCount++ }
    }

    @Test
    fun `pressure owners reject negative or unbounded scalar values`() {
        assertThrows(IllegalArgumentException::class.java) {
            VisibilityPressureOwnerScalars(canonicalOwnedBytes = -1)
        }
        assertThrows(IllegalArgumentException::class.java) {
            VisibilityPressureOwnerScalars(terminalGuidanceStatus = "x".repeat(65))
        }
    }

    private fun ownership() = VisibilityObservationOwnership(
        sessionId = "11".repeat(16),
        sessionGeneration = 1,
        captureGroupId = "12".repeat(16),
        groupGeneration = 1,
        coverageEpoch = 1,
        arSessionIdentity = "13".repeat(16),
        viewInstanceId = "14".repeat(16),
        viewGeneration = 1,
        nativeStreamToken = "15".repeat(16),
        workerBindingToken = "16".repeat(16),
        bindingGeneration = 1,
        lifecycleSequence = 1,
        operationGeneration = 1,
        groupFrame = VisibilityGroupFrame.copyOf(
            identityVisibilityGridTransform(),
            identityVisibilityGridTransform(),
            1_000,
            100_000,
        ),
    )
}
