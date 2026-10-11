package com.uhg0.ar_flutter_plugin_2.sceneview

import com.google.android.filament.IndexBuffer
import com.google.android.filament.MaterialInstance
import com.google.android.filament.RenderableManager
import com.google.android.filament.VertexBuffer
import com.uhg0.ar_flutter_plugin_2.pointcloud.*
import io.github.sceneview.node.Node
import java.nio.ByteBuffer
import java.nio.FloatBuffer
import org.junit.Assert.*
import org.junit.Test

/** Connected production factory, ledger, coordinator, slot and backing seams. */
class CoveragePresentationBoundariesTest {
    @Test fun `cancelled prepared source stays occupied through hide show until actual final vertex consumption`() {
        val telemetry = RendererTelemetry()
        val ledger = CoverageRendererAllocationLedger(telemetry)
        assertTrue(ledger.reserveCommonPresentationMetadata())
        val disposed = mutableListOf<CoverageResourceToken?>()
        val backing = CoverageFrozenBackingReservation(telemetry)
        val factory = CoverageRendererResourceFactory(onDisposed = {
            disposed += it
            backing.release(it)
        })
        val a = CoverageResourceToken(1, 1, CoveragePresentationMode.SEMANTIC_CENTROIDS)
        val b = a.copy(epoch = 2)
        val c = a.copy(epoch = 3)
        val first = HeldResource(false, telemetry, "a")
        val pending = HeldResource(false, telemetry, "b")
        assertSame(first, factory.replacePoint(VoxelRenderMode.CENTROIDS, 1, "a", token = a,
            create = { _, _, _ -> first }, release = HeldResource::destroy))
        assertSame(pending, factory.preparePoint(VoxelRenderMode.CENTROIDS, 1, "b", token = b,
            create = { _, _, _ -> pending }, release = HeldResource::destroy))
        val source = descriptor()
        assertTrue(backing.reserve(source, b))
        pending.start(source, page = true)
        pending.consumeStartup()
        val retainedBytes = telemetry.ownedBufferBytesSnapshot()
        val retainedIdentity = backing.identity
        assertTrue(factory.releaseResource(b))
        // The host's actual slot gate rejects Show/latest C while cancelled B is unknown.
        assertFalse(CoverageSlotAdmission.canStage(b, c, retiring = false))
        assertFalse(backing.reserve(descriptor(3), c))
        assertSame(retainedIdentity, backing.identity)
        repeat(3) { assertNull(factory.preparePoint(VoxelRenderMode.CENTROIDS, 1, "c", token = c,
            create = { _, _, _ -> error("third resource created") }, release = HeldResource::destroy)) }
        assertEquals(2, factory.occupiedGenerationCount())
        assertTrue(factory.ownsToken(b))
        assertEquals(retainedBytes, telemetry.ownedBufferBytesSnapshot())
        val old = pending.uploader.history.toList()
        pending.uploader.consume(1)
        assertTrue(factory.ownsToken(b))
        pending.uploader.consume(0)
        assertEquals(listOf(b), disposed)
        assertEquals(1, pending.completions)
        assertNull(backing.identity)
        val successor = HeldResource(false, telemetry, "c")
        assertSame(successor, factory.preparePoint(VoxelRenderMode.CENTROIDS, 1, "c", token = c,
            create = { _, _, _ -> successor }, release = HeldResource::destroy))
        old.forEach { it() }
        assertTrue(factory.ownsToken(c))
        assertEquals(listOf(b), disposed)
        // Active and prepared clear use the same occupancy transition.
        factory.markResourceMounted(a)
        factory.markResourceMounted(c)
        factory.clear()
        assertEquals(2, factory.occupiedGenerationCount())
        assertTrue(factory.releaseResource(a)); assertTrue(factory.releaseResource(c))
        first.consumeStartup(); successor.consumeStartup()
        assertEquals(0, factory.occupiedGenerationCount())
        assertEquals(1, first.completions); assertEquals(1, successor.completions)
    }

    @Test fun `actual point and cube terminal settlement completes drain after every index and vertex order including accepted lane failure`() {
        for (cube in listOf(false, true)) for (page in listOf(false, true))
            for (indicesFirst in listOf(false, true)) for (colorFirst in listOf(false, true))
                for (rejection in listOf<String?>(null, "colors", "kick")) {
            val telemetry = RendererTelemetry()
            val resource = HeldResource(cube, telemetry, "ordered")
            resource.uploader.rejection = rejection
            if (rejection == null) resource.start(descriptor(), page)
            else assertThrows(IllegalStateException::class.java) { resource.start(descriptor(), page) }
            val callbacks = resource.uploader.history.toList()
            resource.destroy()
            assertFalse(resource.resourceDrainComplete)
            if (indicesFirst) resource.consumeStartup(reverse = colorFirst)
            assertEquals(0, resource.completions)
            if (resource.uploader.pending.size == 2 && colorFirst) resource.uploader.consume(1)
            while (resource.uploader.pending.isNotEmpty()) resource.uploader.consume(0)
            if (!indicesFirst) {
                assertEquals(0, resource.completions)
                resource.consumeStartup(reverse = colorFirst)
            }
            assertTrue(resource.resourceDrainComplete)
            assertEquals(1, resource.completions)
            assertEquals(0, telemetry.ownedBufferBytesSnapshot())
            assertFalse(resource.pageSuccess)
            callbacks.forEach { it() }; resource.destroy(); resource.consumeStartup()
            assertEquals(1, resource.completions)
        }
    }

    @Test fun `synchronous consumption cannot complete destruction during accepted lane submission`() {
        for (cube in listOf(false, true)) {
            val resource = HeldResource(cube, RendererTelemetry(), "sync")
            resource.consumeStartup()
            resource.uploader.synchronous = true
            resource.uploader.afterPosition = { resource.destroy(); assertEquals(0, resource.completions) }
            resource.start(descriptor(), page = true)
            assertEquals(1, resource.completions)
            assertTrue(resource.resourceDrainComplete)
            assertFalse(resource.pageSuccess)
            resource.frame()
            assertEquals(1, resource.uploader.history.size)
        }
    }

    @Test fun `common metadata is funded once for occupied point cube and attributed pairs within unchanged ceilings`() {
        val telemetry = RendererTelemetry()
        val ledger = CoverageRendererAllocationLedger(telemetry)
        assertEquals(8192, CoverageRendererLimits.COMMON_PRESENTATION_METADATA_BYTES)
        assertTrue(ledger.reserveCommonPresentationMetadata())
        assertTrue(ledger.reserveCommonPresentationMetadata())
        assertEquals(8192, telemetry.ownedBufferBytesSnapshot())
        ledger.installPersistentCoverageState(VoxelRenderMode.CENTROIDS)
        val base = telemetry.ownedBufferBytesSnapshot()
        val receipt = CoverageRendererLimits.ownershipReceipt(VoxelRenderMode.CENTROIDS)
        assertEquals(receipt.totalBytes - receipt.meshBytes, base)
        ledger.installPointResources("coverage-centroids-epoch-1", CoverageRendererLimits.CENTROID_CAPACITY)
        ledger.installCubeResources("coverage-cubes-epoch-2", CoverageRendererLimits.CUBE_CAPACITY)
        assertEquals(base + CoverageRendererLimits.resourcePeakBytes(VoxelRenderMode.CENTROIDS) +
            CoverageRendererLimits.resourcePeakBytes(VoxelRenderMode.CUBES), telemetry.ownedBufferBytesSnapshot())
        assertTrue(telemetry.ownedBufferBytesSnapshot() < 32 * 1024 * 1024)
        ledger.releaseCubeResources("coverage-cubes-epoch-2")
        ledger.installPointResources("coverage-centroids-epoch-2", CoverageRendererLimits.CENTROID_CAPACITY)
        val budget = CoverageShapeResourceBudget({ telemetry.ownedBufferBytesSnapshot().toLong() },
            telemetry::setOwnedBufferBytes, telemetry::removeOwner)
        val a = CoverageResourceToken(1, 1, CoveragePresentationMode.PLANAR_PATCHES)
        val b = CoverageResourceToken(2, 1, CoveragePresentationMode.DIRECTIONAL_BALLS)
        val patches = CoverageShapeResourceBudget.bytes(a.mode, 4096)
        val balls = CoverageShapeResourceBudget.bytes(b.mode, 256)
        assertTrue(budget.reserve(a, patches, 0, receipt.totalBytes.toLong()))
        assertTrue(budget.reserve(b, balls, 0, receipt.totalBytes.toLong()))
        assertEquals(base + 2 * CoverageRendererLimits.resourcePeakBytes(VoxelRenderMode.CENTROIDS) +
            patches + balls, telemetry.ownedBufferBytesSnapshot())
        assertEquals(14 * 1024 * 1024, CoverageRendererLimits.ACTIVE_RENDERER_OWNED_LIMIT_BYTES)
        assertEquals(32 * 1024 * 1024, CoverageRendererLimits.COMBINED_RENDERER_OWNED_LIMIT_BYTES)
        assertTrue(receipt.totalBytes + patches < CoverageRendererLimits.ACTIVE_RENDERER_OWNED_LIMIT_BYTES)
        assertTrue(telemetry.ownedBufferBytesSnapshot() < CoverageRendererLimits.COMBINED_RENDERER_OWNED_LIMIT_BYTES)
        budget.drained(a); budget.drained(b); ledger.clearCoverageState()
        assertEquals(0, telemetry.ownedBufferBytesSnapshot())
    }

    private class HeldUploader : CoveragePointVertexUploader, CoverageCubeMeshResources.CoverageCubeVertexUploader {
        val pending = mutableListOf<() -> Unit>()
        val history = mutableListOf<() -> Unit>()
        var rejection: String? = null
        var synchronous = false
        var afterPosition: () -> Unit = {}
        override fun uploadPositions(buffer: FloatBuffer, destOffsetBytes: Int, elementCount: Int, onConsumed: () -> Unit) {
            check(rejection != "positions")
            history += onConsumed
            if (synchronous) onConsumed() else pending += onConsumed
            afterPosition()
        }
        override fun uploadColors(buffer: ByteBuffer, destOffsetBytes: Int, byteCount: Int, onConsumed: () -> Unit) {
            check(rejection != "colors")
            history += onConsumed
            if (synchronous) onConsumed() else pending += onConsumed
        }
        override fun kickSubmission() { check(rejection != "kick") }
        fun consume(index: Int) { pending.removeAt(index)() }
    }

    /** GPU calls are faked; the coordinator/destruction/factory/ledger path is production. */
    private class HeldResource(private val cube: Boolean, telemetry: RendererTelemetry, private val name: String) : CoverageVoxelMeshResources {
        override val capacity = 1
        override val vertexBuffer: VertexBuffer get() = error("GPU not needed")
        override val indexBuffer: IndexBuffer get() = error("GPU not needed")
        override val primitiveType = RenderableManager.PrimitiveType.POINTS
        val uploader = HeldUploader()
        private val ledger = CoverageRendererAllocationLedger(telemetry)
        private var startupMask = if (cube) 3 else 1
        private var destroyed = false
        private var destroyRequested = false
        private var listener: () -> Unit = {}
        var completions = 0
        var pageSuccess = false
        private val sequencer = CoverageDescriptorPageSequencer()
        private val points: CoveragePointUploadCoordinator? = if (cube) null else CoveragePointUploadCoordinator(1, uploader,
            onUploadPageSettled = { ticket, success -> pageSuccess = success; sequencer.finishUpload(ticket, success) },
            onTerminalStateSettled = { completion.onStateSettled() })
        private val cubes: CoverageCubeMeshResources.CoverageCubeUploadCoordinator? = if (!cube) null else CoverageCubeMeshResources.CoverageCubeUploadCoordinator(1, 0.05f, uploader,
            onUploadPageSettled = { ticket, success -> pageSuccess = success; sequencer.finishUpload(ticket, success) },
            onTerminalStateSettled = { completion.onStateSettled() })
        private val completion: CoverageResourceDrainCompletion = CoverageResourceDrainCompletion(
            { startupMask == 0 && (points?.isDrained() ?: checkNotNull(cubes).isDrained()) },
            { destroyed = true; completions++; if (cube) ledger.releaseCubeResources(name) else ledger.releasePointResources(name); listener() })
        init { if (cube) ledger.installCubeResources(name, 1) else ledger.installPointResources(name, 1) }
        fun start(descriptor: PresentationDescriptor, page: Boolean) {
            if (page) {
                sequencer.replace(descriptor)
                val submission = checkNotNull(sequencer.nextPage())
                points?.submitPage(submission.page, RendererUploadPageOrigin.ORDINARY, submission.ticket)
                cubes?.submitPage(submission.page, RendererUploadPageOrigin.ORDINARY, submission.ticket)
            } else {
                val snapshot = CoveragePointRenderSnapshot(revision = 1, enabled = true, capacity = 1, count = 1,
                    keys = longArrayOf(1), positions = floatArrayOf(0f, 0f, -2f), colors = intArrayOf(-1))
                points?.submit(snapshot); cubes?.submit(snapshot)
            }
            frame()
        }
        fun frame() { points?.onRendererFrame(); cubes?.onRendererFrame() }
        fun consumeStartup(reverse: Boolean = false) {
            val order = if (reverse) intArrayOf(2, 1) else intArrayOf(1, 2)
            for (bit in order) if (startupMask and bit != 0) {
                startupMask = startupMask and bit.inv()
                if (!cube) ledger.completePointStartup(name)
                else if (bit == 1) ledger.completeCubeTriangleStartup(name) else ledger.completeCubeOutlineStartup(name)
                completion.onStateSettled()
            }
        }
        override val resourceDrainComplete get() = destroyed
        override fun setOnResourceDrained(listener: () -> Unit) { this.listener = listener; if (destroyed) listener() }
        override fun destroy() {
            if (destroyRequested) return
            destroyRequested = true
            points?.destroy(); cubes?.destroy()
            completion.requestDestroy()
        }
        override fun update(node: Node, snapshot: CoveragePointRenderSnapshot, materialInstance: MaterialInstance, pointSizePx: Float) = Unit
        override fun hide(node: Node) = Unit
        override fun requireRetainedSnapshotUpload() = Unit
        override fun onRendererFrame() = frame()
        override fun setOnUploadPageReleased(listener: () -> Unit) = Unit
    }

    private fun descriptor(revision: Long = 1): PresentationDescriptor = PresentationDescriptor.create(
        CoverageRowsQualifier(1, 1, 1, revision, revision, revision), CoveragePresentationMode.SEMANTIC_CENTROIDS,
        true, 20000, 1, 1, CoverageRendererPalette.COVERAGE, 0,
        selectedSurfaceIds = longArrayOf(1), selectedSourceSlots = intArrayOf(0),
        styleRows = CoverageRendererStyleRowV1().encode(), update = null,
        pageReader = { _, start, _ -> if (start != 0) null else CoveragePresentationPage(0, 1,
            longArrayOf(1), floatArrayOf(0f, 0f, -2f), intArrayOf(-1), CoverageRendererStyleRowV1().encode()) })
}
