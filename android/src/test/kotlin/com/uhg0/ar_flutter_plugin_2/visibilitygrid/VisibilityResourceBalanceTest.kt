package com.uhg0.ar_flutter_plugin_2.visibilitygrid

import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class VisibilityResourceBalanceTest {
    @Test
    fun `teardown receipt balances every acquired scalar resource`() {
        val cut = AtomicReference(ownership())
        val runtime = AndroidVisibilityGridRuntime(
            ownership = cut::get,
            mapper = AndroidVisibilityGridMappingAdmission(cut::get),
            scheduler = Executors.newScheduledThreadPool(2),
            ownsScheduler = true,
        )
        repeat(6) {
            runtime.recordProducerResourceAcquired()
            runtime.recordProducerResourceClosed()
        }
        val owners = VisibilityPressureOwnerScalars(
            callbacksAcquired = 8,
            callbacksReleased = 8,
            pagesAcquired = 13,
            pagesReleased = 13,
            workerPortsAcquired = 2,
            workerPortsReleased = 2,
            rendererResourcesAcquired = 3,
            rendererResourcesReleased = 3,
            terminalGuidanceStatus = "closed",
        )
        runtime.close()

        val receipt = VisibilityPressureReceipt.capture(runtime.snapshot(), owners)
        assertEquals(receipt.imagesAcquired, receipt.imagesReleased)
        assertEquals(receipt.buffersAcquired, receipt.buffersReleased)
        assertEquals(receipt.callbacksAcquired, receipt.callbacksReleased)
        assertEquals(receipt.pagesAcquired, receipt.pagesReleased)
        assertEquals(receipt.workerPortsAcquired, receipt.workerPortsReleased)
        assertEquals(receipt.rendererResourcesAcquired, receipt.rendererResourcesReleased)
        assertEquals(0, receipt.rootIsolateSurfaceBytes)
        assertEquals(0, receipt.rootIsolateImageBytes)
        assertTrue(receipt.toWireMap().keys.none { it.contains("passed", ignoreCase = true) })
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
