package com.uhg0.ar_flutter_plugin_2.sceneview

import com.uhg0.ar_flutter_plugin_2.pointcloud.COVERAGE_RENDERER_STYLE_ROW_BYTES
import com.uhg0.ar_flutter_plugin_2.pointcloud.CoveragePointRenderUpdate
import com.uhg0.ar_flutter_plugin_2.pointcloud.CoverageRendererCoverage
import com.uhg0.ar_flutter_plugin_2.pointcloud.CoverageRendererPalette
import com.uhg0.ar_flutter_plugin_2.pointcloud.CoverageRendererStyleRowV1
import com.uhg0.ar_flutter_plugin_2.pointcloud.CoverageRowsQualifier
import com.uhg0.ar_flutter_plugin_2.pointcloud.PointCloudNativeConfig
import com.uhg0.ar_flutter_plugin_2.pointcloud.VoxelRenderMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CombinedRendererPressureTest {
    @Test
    fun `renderer transition upload churn and page queue stay within exact bounds`() {
        val resources = PressureRendererResources()
        repeat(3) { cycle ->
            resources.openCycle(cycle)
            val active = resources.telemetry.pressureSnapshot()
            assertEquals(13_197_572, active.rendererOwnedBytes)
            assertTrue(active.rendererOwnedBytes <= CoverageRendererLimits.ACTIVE_RENDERER_OWNED_LIMIT_BYTES)
            assertTrue(resources.peakTransitionBytes <= CoverageRendererLimits.COMBINED_RENDERER_OWNED_LIMIT_BYTES)
            assertTrue(resources.peakReplacementBytes <= CoverageRendererLimits.TRANSITION_HEADROOM_BYTES)
            resources.closeCycle()
            assertEquals(0, resources.telemetry.pressureSnapshot().rendererOwnedBytes)
        }
        val renderer = resources.telemetry.pressureSnapshot()
        val pages = resources.pages.pressureSnapshot()
        assertEquals(19_085_572, resources.peakTransitionBytes)
        assertEquals(5_888_000, resources.peakReplacementBytes)
        assertEquals(64L * 1024L, renderer.maxUploadBytesPerFrame)
        assertEquals(6, renderer.rendererResourcesAcquired)
        assertEquals(renderer.rendererResourcesAcquired, renderer.rendererResourcesReleased)
        assertEquals(renderer.buffersAcquired, renderer.buffersReleased)
        assertEquals(renderer.callbacksAcquired, renderer.callbacksReleased)
        assertEquals(3 * 127L, pages.coalesced)
        assertEquals(1, pages.maxPendingTransactions)
        assertEquals(0, pages.pendingTransactions)
        assertEquals(0, pages.catchUpBursts)
        assertEquals(pages.pagesAcquired, pages.pagesReleased)
    }

    @Test
    fun `ninety six accepted selection changes report production churn across three lifecycle cycles`() {
        val telemetry = RendererTelemetry()
        val owner = NativeCoverageRendererOwner(onSelectionChanged = telemetry::recordSelectionChurn)
        val config = PointCloudNativeConfig(voxelRenderMode = VoxelRenderMode.CENTROIDS)
        val first = descriptor(1)
        assertTrue(owner.installPresentation(first, config).installed)
        assertEquals(0, telemetry.pressureSnapshot().selectionChurnPermille)
        repeat(96) { index ->
            val descriptor = descriptor(index + 2L)
            assertTrue(owner.installPresentation(descriptor, config).installed)
            assertEquals(19, telemetry.pressureSnapshot().selectionChurnPermille)
            // Rejected installs must not erase the actual accepted churn measurement.
            assertTrue(owner.installPresentation(first, config).stale)
            assertEquals(19, telemetry.pressureSnapshot().selectionChurnPermille)
            if ((index + 1) % 32 == 0) {
                owner.pause()
                assertTrue(owner.resume().recovered)
                assertEquals(1_000, owner.status().selectedRowCount)
            }
        }
        assertEquals(97, owner.status().styleRevision)
        owner.dispose()
        assertEquals(0, owner.status().selectedRowCount)
    }

    @Test
    fun `failed post allocation installation balances the released native owner`() {
        val telemetry = RendererTelemetry()
        val ledger = CoverageRendererAllocationLedger(telemetry)
        val factory = CoverageRendererResourceFactory(
            onAcquisition = telemetry::recordResourceAcquisition,
            onDisposal = telemetry::recordResourceDisposal,
            onCreated = { error("injected mount failure after allocation") },
        )
        factory.replaceCube(
            8_000, "coverage-cubes-failed",
            create = { _, capacity, name -> ledger.installCubeResources(name, capacity); name },
            release = ledger::releaseCubeResources,
        )
        val receipt = telemetry.pressureSnapshot()
        assertEquals(1, receipt.rendererResourcesAcquired)
        assertEquals(receipt.rendererResourcesAcquired, receipt.rendererResourcesReleased)
        assertEquals(0, receipt.rendererOwnedBytes)
    }

    @Test
    fun `failed native release cannot claim a balanced teardown receipt`() {
        val telemetry = RendererTelemetry()
        val factory = CoverageRendererResourceFactory(
            onAcquisition = telemetry::recordResourceAcquisition,
            onDisposal = telemetry::recordResourceDisposal,
            onCreated = { error("injected mount failure") },
        )
        factory.replaceCube(
            8_000, "coverage-cubes-release-failed",
            create = { _, _, name -> name },
            release = { error("injected native release failure") },
        )
        val receipt = telemetry.pressureSnapshot()
        assertEquals(1, receipt.rendererResourcesAcquired)
        assertEquals(0, receipt.rendererResourcesReleased)
    }

    private fun descriptor(revision: Long): PresentationDescriptor {
        val style = CoverageRendererStyleRowV1(
            coverage = if (revision % 2L == 0L) CoverageRendererCoverage.PARTIAL
                else CoverageRendererCoverage.UNCOVERED,
        ).encode()
        return PresentationDescriptor.create(
            qualifier = CoverageRowsQualifier(1, 1, 1, revision, 1, revision),
            mode = CoveragePresentationMode.SEMANTIC_CENTROIDS,
            enabled = true,
            capacity = CoverageRendererLimits.CENTROID_CAPACITY,
            sourceCapacity = 100_000,
            sourceCount = 100_000,
            palette = CoverageRendererPalette.COVERAGE,
            paletteEpoch = 0,
            selectedSurfaceIds = LongArray(1_000) { index ->
                if (index < 19) 100_000 + revision * 19 + index else index + 1L
            },
            selectedSourceSlots = IntArray(1_000) { it },
            styleRows = ByteArray(1_000 * COVERAGE_RENDERER_STYLE_ROW_BYTES) {
                style[it % COVERAGE_RENDERER_STYLE_ROW_BYTES]
            },
            update = CoveragePointRenderUpdate(1, revision, true, 1_000, emptyList(), false),
            pageReader = { _, _, _ -> null },
        )
    }
}

/** Uses the production capacity ledger/factory; no GPU-driver allocation claim is made. */
internal class PressureRendererResources {
    val telemetry = RendererTelemetry()
    private val ledger = CoverageRendererAllocationLedger(telemetry)
    private val frames = mutableListOf<() -> Unit>()
    val pages = RendererPageFrameScheduler(frames::add)
    var peakTransitionBytes = 0L
        private set
    var peakReplacementBytes = 0L
        private set
    private val factory = CoverageRendererResourceFactory(
        onAcquisition = telemetry::recordResourceAcquisition,
        onReplacement = telemetry::recordResourceReplacement,
        onDisposal = telemetry::recordResourceDisposal,
        admit = { mode, _ -> ledger.admitResourceReplacement(mode) },
    )

    fun openCycle(cycle: Int) {
        ledger.installPersistentCoverageStateForCapacity(20_000, 100_000)
        repeat(2) { generation ->
            val before = telemetry.pressureSnapshot().rendererOwnedBytes
            factory.replaceCube(
                CoverageRendererLimits.CUBE_CAPACITY, "coverage-cubes-$cycle-$generation",
                create = { _, capacity, name ->
                    ledger.installCubeResources(name, capacity)
                    val coexistence = telemetry.pressureSnapshot().rendererOwnedBytes
                    peakTransitionBytes = maxOf(peakTransitionBytes, coexistence)
                    peakReplacementBytes = maxOf(peakReplacementBytes, coexistence - before)
                    name
                },
                release = ledger::releaseCubeResources,
            ) ?: error("bounded renderer allocation was rejected")
        }
        repeat(128) {
            pages.request {
                telemetry.beginRendererFrame()
                telemetry.recordUpload(RendererTelemetry.ORDINARY_UPLOAD_LIMIT_BYTES)
                telemetry.recordUploadCallback()
                telemetry.recordUploadCompletion(1)
            }
        }
        assertEquals(1, frames.size)
        frames.removeAt(0).invoke()
        pages.request { error("cancelled frame executed after teardown") }
    }

    fun closeCycle() {
        pages.cancel()
        frames.toList().forEach { it() }
        frames.clear()
        factory.clear()
        ledger.clearCoverageState()
        factory.clear()
        ledger.clearCoverageState()
    }
}
