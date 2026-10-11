package com.uhg0.ar_flutter_plugin_2.sceneview

import androidx.compose.runtime.mutableStateOf
import com.google.android.filament.*
import com.uhg0.ar_flutter_plugin_2.pointcloud.*
import io.github.sceneview.node.Node
import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.lang.reflect.InvocationTargetException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

class CoverageCandidateCancellationTest {
    private class Resource(val name: String, val ledger: CoverageRendererAllocationLedger,
        descriptor: PresentationDescriptor, var deferDrain: Boolean = false): CoverageVoxelMeshResources, CoveragePromotionReadiness {
        val uploaded = UploadedPoints(descriptor)
        private var drained = false
        private var onDrained: () -> Unit = {}
        override val capacity = CoverageRendererLimits.CENTROID_CAPACITY
        override val vertexBuffer: VertexBuffer get() = error("No physical GPU")
        override val indexBuffer: IndexBuffer get() = error("No physical GPU")
        override val primitiveType: RenderableManager.PrimitiveType get() = error("No physical GPU")
        override fun update(node: Node, snapshot: CoveragePointRenderSnapshot, materialInstance: MaterialInstance, pointSizePx: Float) = Unit
        override fun hide(node: Node) = Unit
        override fun requireRetainedSnapshotUpload() = Unit
        override fun onRendererFrame() = Unit
        override fun setOnUploadPageReleased(listener: () -> Unit) = Unit
        override fun setOnResourceDrained(listener: () -> Unit) { onDrained = listener; if (drained) listener() }
        override val resourceDrainComplete get() = drained
        override fun destroy() { if (!deferDrain) finishDrain() }
        fun finishDrain() { if (drained) return; drained = true; ledger.releasePointResources(name); onDrained() }
        override fun isWholeDescriptorUploadComplete(descriptor: BoundedCoveragePresentation): Boolean =
            uploaded.complete(descriptor as PresentationDescriptor)
    }

    private class Fixture {
        val owner = NativeCoverageRendererOwner(clockNanos = { 1_000_000L })
        val telemetry = RendererTelemetry()
        val ledger = CoverageRendererAllocationLedger(telemetry)
        val shapes = CoverageShapeResourceBudget({ telemetry.ownedBufferBytesSnapshot().toLong() },
            telemetry::setOwnedBufferBytes, telemetry::removeOwner)
        val frozen = CoverageFrozenBackingReservation(telemetry)
        val config = PointCloudNativeConfig(renderCapacity = CoverageRendererLimits.CENTROID_CAPACITY,
            voxelRenderMode = VoxelRenderMode.CENTROIDS)
        val host: SceneViewHost
        var creates = 0
        val factory = CoverageRendererResourceFactory(
            admit = { mode, _ -> ledger.admitResourceReplacement(mode, 5, 5) },
            onCreated = { transition -> owner.markAnyResourceMounted(checkNotNull(transition.token)) },
            onDisposed = { token -> token?.let { owner.markAnyResourceReleased(it); invoke("onCoverageSlotResourceDrained", it) } },
        )
        val active: CoverageResourceToken
        private val restoredTokens = mutableListOf<CoverageResourceToken>()
        init {
            val unsafe = Class.forName("sun.misc.Unsafe")
            val field = unsafe.getDeclaredField("theUnsafe").apply { isAccessible = true }
            host = unsafe.getMethod("allocateInstance", Class::class.java).invoke(field.get(null), SceneViewHost::class.java) as SceneViewHost
            owner.installPresentation(descriptor(3), config)
            active = checkNotNull(owner.requestResourceReplacement())
            ledger.installPersistentCoverageStateForCapacity(CoverageRendererLimits.CENTROID_CAPACITY, 5)
            checkNotNull(factory.replacePoint(VoxelRenderMode.CENTROIDS, CoverageRendererLimits.CENTROID_CAPACITY,
                "coverage-centroids", token = active, create = { _, capacity, name -> resource(name, capacity, descriptor(3)) }, release = Resource::destroy))
            factory.markResourceMounted(active)
            owner.releaseActiveDisplaySource(active)
            inject("coverageRendererOwner", owner); inject("coverageResourceFactory", factory)
            inject("rendererTelemetry", telemetry); inject("rendererAllocationLedger", ledger)
            inject("shapeResourceBudget", shapes); inject("frozenBacking", frozen)
            inject("coverageSlotTokens", arrayOf(mutableStateOf<CoverageResourceToken?>(active), mutableStateOf<CoverageResourceToken?>(null)))
            inject("coverageSlotConfigs", arrayOf(mutableStateOf<PointCloudNativeConfig?>(config), mutableStateOf<PointCloudNativeConfig?>(null)))
            inject("coverageSlotRefs", arrayOf(AtomicReference<Any?>(null), AtomicReference<Any?>(null)))
            inject("activeCoverageSlot", 0); inject("preparingCoverageSlot", -1); inject("retiringCoverageSlot", -1)
            inject("coveragePublications", CoverageRendererPublicationMailbox<Any>())
            inject("coveragePresentationRefreshPending", AtomicBoolean(false)); inject("coverageUploadFramePending", AtomicBoolean(false))
            inject("rendererPageFrameScheduler\$delegate", lazyOf(RendererPageFrameScheduler { /* frame execution belongs to device tests */ }))
        }
        private fun resource(name: String, capacity: Int, descriptor: PresentationDescriptor): Resource {
            creates++; ledger.installPointResources(name, capacity); ledger.completePointStartup(name)
            return Resource(name, ledger, descriptor)
        }
        fun prepare(token: CoverageResourceToken, descriptor: PresentationDescriptor, deferDrain: Boolean = false): Resource =
            checkNotNull(factory.preparePoint(VoxelRenderMode.CENTROIDS, CoverageRendererLimits.CENTROID_CAPACITY,
                "coverage-centroids", token = token, create = { _, capacity, name -> resource(name, capacity, checkNotNull(owner.candidateDisplayDescriptor()) as PresentationDescriptor).also { it.deferDrain = deferDrain } }, release = Resource::destroy))
        private fun inject(name: String, value: Any) = SceneViewHost::class.java.getDeclaredField(name).apply { isAccessible = true }.set(host, value)
        fun value(name: String): Any? = SceneViewHost::class.java.getDeclaredField(name).apply { isAccessible = true }.get(host)
        @Suppress("UNCHECKED_CAST") fun slotToken(): CoverageResourceToken? =
            (value("coverageSlotTokens") as Array<androidx.compose.runtime.MutableState<CoverageResourceToken?>>)[1].value
        fun invoke(name: String, vararg arguments: Any): Any? {
            val types = arguments.map { if (it is Int) Int::class.javaPrimitiveType!! else if (it is BoundedCoveragePresentation) BoundedCoveragePresentation::class.java else it.javaClass }.toTypedArray()
            val method = SceneViewHost::class.java.getDeclaredMethod(name, *types).apply { isAccessible = true }
            return try { method.invoke(host, *arguments) } catch (failure: InvocationTargetException) { throw failure.targetException }
        }
        fun stage(descriptor: PresentationDescriptor): Boolean {
            owner.installPresentation(descriptor, config)
            return invoke("stageCoverageCandidate", checkNotNull(owner.presentationDescriptor()), config) as Boolean
        }
        fun cancel(token: CoverageResourceToken) { invoke("cancelCoverageCandidateSlot", 1, token) }
        fun lifecycle(method: String) {
            if (method == "pauseCoverageResources") { owner.pause(); inject("rendererPaused", true) }
            invoke(method)
        }
        @Suppress("UNCHECKED_CAST") fun slotConfig(): PointCloudNativeConfig? =
            (value("coverageSlotConfigs") as Array<androidx.compose.runtime.MutableState<PointCloudNativeConfig?>>)[1].value
        @Suppress("UNCHECKED_CAST") fun restoreActiveAfterLifecycle() {
            factory.releaseResource(active)
            owner.resume(); inject("rendererPaused", false)
            owner.installPresentation(descriptor(3, 3), config)
            val token = checkNotNull(owner.requestResourceReplacement())
            ledger.installPersistentCoverageStateForCapacity(CoverageRendererLimits.CENTROID_CAPACITY, 5)
            checkNotNull(factory.replacePoint(VoxelRenderMode.CENTROIDS, CoverageRendererLimits.CENTROID_CAPACITY,
                "coverage-centroids", token = token, create = { _, capacity, name -> resource(name, capacity,
                    checkNotNull(owner.activeDisplayDescriptor()) as PresentationDescriptor) }, release = Resource::destroy))
            factory.markResourceMounted(token); restoredTokens += token
            (value("coverageSlotTokens") as Array<androidx.compose.runtime.MutableState<CoverageResourceToken?>>)[0].value = token
            (value("coverageSlotConfigs") as Array<androidx.compose.runtime.MutableState<PointCloudNativeConfig?>>)[0].value = config
        }
        fun close() {
            owner.cancelCandidateDisplay(); factory.clear()
            factory.releaseResource(active); restoredTokens.forEach { factory.releaseResource(it) }
            slotToken()?.let { factory.releaseResource(it) }
            frozen.release(); ledger.clearCoverageState(); owner.dispose()
            assertEquals(0, telemetry.ownedBufferBytesSnapshot())
        }
    }

    private fun exerciseLifecycleCancellation(method: String) {
        for (ownership in listOf("empty", "created", "unknownPoint", "unknownShape")) {
            val fixture = Fixture(); val cut = descriptor(4, 2)
            assertTrue(fixture.stage(cut)); val token = checkNotNull(fixture.slotToken())
            val name = "coverage-centroids-epoch-${token.epoch}"
            val resource = if (ownership == "created") fixture.prepare(token, cut, deferDrain = true) else null
            if (ownership == "unknownPoint") fixture.ledger.installPointResources(name, 1)
            if (ownership == "unknownShape") assertTrue(fixture.shapes.reserve(token, 1024, 0, 0))
            fixture.lifecycle(method)
            // Mounted active ownership waits for its own matching drain, independent of B.
            assertTrue(fixture.factory.ownsToken(fixture.active))
            assertTrue(fixture.ledger.hasResourceOwnership(fixture.active))
            if (ownership == "empty") {
                assertNull(fixture.slotToken()); assertNull(fixture.slotConfig())
                assertEquals(-1, fixture.value("preparingCoverageSlot")); assertEquals(0, fixture.frozen.bytes)
            } else {
                assertEquals(token, fixture.slotToken()); assertNotNull(fixture.slotConfig())
                assertTrue(fixture.frozen.bytes > 0)
                if (resource != null) resource.finishDrain()
                else {
                    if (ownership == "unknownPoint") fixture.ledger.releasePointResources(name)
                    else fixture.shapes.drained(token)
                    fixture.invoke("onCoverageSlotResourceDrained", token)
                }
                assertNull(fixture.slotToken()); assertEquals(0, fixture.frozen.bytes)
            }
            fixture.restoreActiveAfterLifecycle()
            assertTrue(fixture.stage(descriptor(5, 4)))
            val next = checkNotNull(fixture.slotToken()); assertNotEquals(token, next)
            val successor = fixture.prepare(next, descriptor(5, 4))
            assertTrue(successor.uploaded.pages > 0)
            assertTrue(CoveragePresentationPromotion.promote(fixture.owner, fixture.factory, successor))
            assertEquals(next, fixture.owner.currentResourceToken())
            fixture.invoke("onCoverageSlotResourceDrained", token)
            assertEquals(next, fixture.slotToken())
            fixture.close()
        }
    }

    @Test fun pauseReclaimsUncreatedCandidateAndPreservesChargedDrainBeforeNextPromotion() {
        exerciseLifecycleCancellation("pauseCoverageResources")
    }

    @Test fun clearReclaimsUncreatedCandidateAndPreservesChargedDrainBeforeNextPromotion() {
        exerciseLifecycleCancellation("clearCoverageResourceState")
    }

    @Test fun unallocatedCancellationAllowsNextCandidateUploadAndPromotion() {
        val fixture = Fixture()
        assertTrue(fixture.stage(descriptor(4, 2)))
        val abandoned = checkNotNull(fixture.slotToken())
        assertFalse(fixture.factory.ownsToken(abandoned))
        fixture.cancel(abandoned)
        assertNull(fixture.slotToken()); assertEquals(-1, fixture.value("preparingCoverageSlot")); assertEquals(0, fixture.frozen.bytes)
        val next = descriptor(5, 3)
        assertTrue(fixture.stage(next))
        val token = checkNotNull(fixture.slotToken()); assertNotEquals(abandoned, token)
        val resource = fixture.prepare(token, next)
        assertTrue(resource.uploaded.pages > 0)
        assertTrue(resource.isWholeDescriptorUploadComplete(checkNotNull(fixture.owner.candidateDisplayDescriptor())))
        assertTrue(CoveragePresentationPromotion.promote(fixture.owner, fixture.factory, resource))
        assertEquals(token, fixture.owner.currentResourceToken()); assertEquals(2, fixture.creates)
        fixture.close()
    }

    @Test fun staleDrainCallbackCannotClearNewCandidateSlot() {
        val fixture = Fixture(); assertTrue(fixture.stage(descriptor(4, 2)))
        val old = checkNotNull(fixture.slotToken()); fixture.cancel(old)
        assertTrue(fixture.stage(descriptor(5, 3))); val current = checkNotNull(fixture.slotToken())
        val bytes = fixture.frozen.bytes
        fixture.invoke("onCoverageSlotResourceDrained", old)
        assertEquals(current, fixture.slotToken()); assertEquals(1, fixture.value("preparingCoverageSlot")); assertEquals(bytes, fixture.frozen.bytes)
        fixture.cancel(current); fixture.close()
    }

    @Test fun retiringResourceKeepsSlotAndChargeUntilMatchingDrain() {
        val fixture = Fixture(); val cut = descriptor(4, 2); assertTrue(fixture.stage(cut))
        val token = checkNotNull(fixture.slotToken()); val resource = fixture.prepare(token, cut, deferDrain = true)
        val charged = fixture.telemetry.ownedBufferBytesSnapshot(); fixture.cancel(token)
        assertEquals(token, fixture.slotToken()); assertTrue(fixture.factory.ownsToken(token)); assertEquals(charged, fixture.telemetry.ownedBufferBytesSnapshot())
        assertFalse(fixture.stage(descriptor(5, 3)))
        resource.finishDrain()
        assertNull(fixture.slotToken()); assertFalse(fixture.factory.ownsToken(token)); assertEquals(0, fixture.frozen.bytes)
        assertTrue(fixture.stage(descriptor(5, 3))); fixture.cancel(checkNotNull(fixture.slotToken())); fixture.close()
    }

    @Test fun unownedTokenWithPointOrShapeReservationCannotClearSlot() {
        for (shape in listOf(false, true)) {
            val fixture = Fixture(); assertTrue(fixture.stage(descriptor(4, 2))); val token = checkNotNull(fixture.slotToken())
            val name = "coverage-centroids-epoch-${token.epoch}"
            if (shape) assertTrue(fixture.shapes.reserve(token, 1024, 0, 0)) else fixture.ledger.installPointResources(name, 1)
            fixture.cancel(token)
            assertEquals(token, fixture.slotToken()); assertTrue(fixture.frozen.bytes > 0)
            if (shape) fixture.shapes.drained(token) else fixture.ledger.releasePointResources(name)
            fixture.invoke("onCoverageSlotResourceDrained", token)
            assertNull(fixture.slotToken()); fixture.close()
        }
    }

    private class UploadedPoints(descriptor: PresentationDescriptor) {
        var pages = 0
        private val sequencer = CoverageDescriptorPageSequencer()
        private val coordinator = CoveragePointUploadCoordinator(descriptor.capacity,
            object : CoveragePointVertexUploader {
                override fun uploadPositions(buffer: java.nio.FloatBuffer, destOffsetBytes: Int,
                    elementCount: Int, onConsumed: () -> Unit) { onConsumed() }
                override fun uploadColors(buffer: ByteBuffer, destOffsetBytes: Int,
                    byteCount: Int, onConsumed: () -> Unit) { onConsumed() }
            }, onUploadPageSettled = { ticket, successful -> sequencer.finishUpload(ticket, successful) })
        init {
            sequencer.replace(descriptor)
            while (true) {
                val page = sequencer.nextPage() ?: break
                pages++
                coordinator.submitPage(page.page, RendererUploadPageOrigin.ORDINARY, page.ticket)
                coordinator.onRendererFrame()
            }
        }
        fun complete(descriptor: PresentationDescriptor): Boolean = CoveragePointMeshResources.descriptorUploadReady(
            sequencer, coordinator, descriptor, indexUploadPending = false, destroyed = false)
        fun releaseSource(descriptor: PresentationDescriptor) {
            sequencer.clear()
            assertFalse(sequencer.isCurrent(descriptor))
        }
    }


    private companion object {
        fun descriptor(count: Int, geometry: Long = 1): PresentationDescriptor {
            val qualifier = CoverageRowsQualifier(1, 1, 0, geometry, geometry, geometry)
            val ids = LongArray(count) { it.toLong() + 1 }
            val style = CoverageRendererStyleRowV1().encode()
            return PresentationDescriptor.createOwned(qualifier = qualifier,
                mode = CoveragePresentationMode.SEMANTIC_CENTROIDS, enabled = true,
                capacity = CoverageRendererLimits.CENTROID_CAPACITY, sourceCapacity = count, sourceCount = count,
                palette = CoveragePalette.COVERAGE, paletteEpoch = 1,
                targetSurfaceId = null, targetDirectionIndex = null,
                selectedSurfaceIds = ids, selectedSourceSlots = IntArray(count) { it },
                styleRows = ByteArray(count * COVERAGE_RENDERER_STYLE_ROW_BYTES) { style[it % style.size] },
                update = null, workspace = PresentationWorkspace(count),
                pageReader = PresentationPageReader { expected, start, requested, _, page ->
                    if (expected != qualifier || start + requested > count) false else {
                        ids.copyInto(page.surfaceIds, 0, start, start + requested)
                        java.util.Arrays.fill(page.positions, 0, requested * 3, 0f)
                        true
                    }
                })
        }
    }
}
