package com.uhg0.ar_flutter_plugin_2.sceneview

import com.uhg0.ar_flutter_plugin_2.pointcloud.*
import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer

class CoverageMountedResidencyTest {
    private class UploadedPoints(descriptor: PresentationDescriptor) {
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

    private class Mesh(val resourceOwner: String, val uploaded: UploadedPoints)

    private class Fixture(count: Int) {
        val owner = NativeCoverageRendererOwner()
        val telemetry = RendererTelemetry()
        val ledger = CoverageRendererAllocationLedger(telemetry)
        val descriptor = descriptor(count)
        val config = PointCloudNativeConfig(renderCapacity = CoverageRendererLimits.CENTROID_CAPACITY,
            voxelRenderMode = VoxelRenderMode.CENTROIDS)
        var creates = 0
        var releases = 0
        val factory = CoverageRendererResourceFactory(
            onAcquisition = telemetry::recordResourceAcquisition,
            onDisposal = telemetry::recordResourceDisposal,
            onDisposed = { token -> token?.let(owner::markAnyResourceReleased) },
            onCreated = { transition -> assertTrue(owner.markAnyResourceMounted(checkNotNull(transition.token))) },
            admit = { mode, _ -> ledger.admitResourceReplacement(mode, count, count) },
        )
        val token: CoverageResourceToken
        val mesh: Mesh
        init {
            owner.installPresentation(descriptor, config)
            token = checkNotNull(owner.requestResourceReplacement())
            ledger.installPersistentCoverageStateForCapacity(descriptor.capacity, descriptor.sourceCapacity)
            mesh = checkNotNull(factory.replacePoint(VoxelRenderMode.CENTROIDS, descriptor.capacity, "residency",
                token = token, create = { _, capacity, name ->
                    creates++
                    ledger.installPointResources(name, capacity)
                    ledger.completePointStartup(name)
                    Mesh(name, UploadedPoints(descriptor))
                }, release = { resource ->
                    releases++
                    ledger.releasePointResources(resource.resourceOwner)
                }))
            assertTrue(factory.markResourceMounted(token))
        }
        fun completeAndReleaseSource() {
            assertTrue(CoveragePresentationEligibility.wholeUploadComplete(descriptor,
                mesh.uploaded.complete(descriptor), attributedResource = false, initial = true))
            assertTrue(owner.releaseActiveDisplaySource(token))
            mesh.uploaded.releaseSource(descriptor)
            assertNull(owner.displayDescriptorFor(token))
            assertEquals(descriptor.count, checkNotNull(owner.activeDisplayResidencyReceipt(token)).count)
        }
        fun recompose(role: CoverageDisplayRole = CoverageDisplayRole.ACTIVE,
            currentToken: CoverageResourceToken? = token, source: BoundedCoveragePresentation? = owner.displayDescriptorFor(currentToken)): Any? =
            CoveragePresentationEligibility.mountedResidencyResource(source, role, currentToken, owner, factory)
        fun close() {
            factory.releaseResource(token)
            factory.clear()
            ledger.clearCoverageState()
            owner.dispose()
            assertEquals(0, telemetry.ownedBufferBytesSnapshot())
        }
    }

    @Test fun completedEmptyAndNonemptyUploadsPreserveMountedResourceAcrossBenignRecomposition() {
        for (count in listOf(0, 3)) {
            val fixture = Fixture(count)
            fixture.completeAndReleaseSource()
            val charged = fixture.telemetry.ownedBufferBytesSnapshot()
            repeat(3) {
                assertSame(fixture.mesh, fixture.recompose())
                assertSame(fixture.mesh, fixture.factory.mountedResource(fixture.token))
                assertEquals(fixture.token, fixture.owner.currentResourceToken())
                assertEquals(1, fixture.factory.occupiedGenerationCount())
                assertEquals(charged, fixture.telemetry.ownedBufferBytesSnapshot())
                assertTrue(fixture.owner.status().resourceAvailable)
                assertFalse(fixture.owner.status().rendererUnavailable)
                assertEquals(1, fixture.creates)
                assertEquals(0, fixture.releases)
            }
            fixture.close()
            assertEquals(1, fixture.releases)
        }
    }

    @Test fun candidateNullAndStaleResidenciesCannotBorrowActiveResource() {
        val fixture = Fixture(3)
        fixture.completeAndReleaseSource()
        assertNull(fixture.recompose(currentToken = null))
        assertNull(fixture.recompose(currentToken = fixture.token.copy(epoch = fixture.token.epoch + 1)))
        assertNull(fixture.recompose(source = fixture.descriptor))
        val candidate = checkNotNull(fixture.owner.issueCandidateResourceToken())
        assertTrue(fixture.owner.stageCandidateDisplay(fixture.descriptor))
        assertEquals(CoverageDisplayRole.CANDIDATE, fixture.owner.displayRoleFor(candidate))
        assertNull(fixture.recompose(CoverageDisplayRole.CANDIDATE, candidate, source = null))
        assertNull(fixture.recompose(CoverageDisplayRole.ACTIVE, candidate, source = null))
        fixture.owner.cancelCandidateDisplay()
        val successor = checkNotNull(fixture.owner.requestResourceReplacement())
        assertTrue(successor.epoch > fixture.token.epoch)
        assertNull(fixture.recompose())
        assertNull(fixture.recompose(currentToken = successor, source = null))
        assertEquals(1, fixture.creates)
        assertEquals(0, fixture.releases)
        fixture.close()
    }

    @Test fun hiddenResidencyRetainsLeaseWithoutDrawingOrCreatingAnotherResource() {
        val fixture = Fixture(3)
        fixture.completeAndReleaseSource()
        fixture.owner.setControls(CoverageRendererControls(false,
            CoveragePresentationMode.SEMANTIC_CENTROIDS, CoverageRendererPalette.COVERAGE))
        assertFalse(fixture.owner.displayRequestedVisible())
        assertFalse(fixture.owner.isDisplayResidencyVisible(fixture.token))
        assertSame(fixture.mesh, fixture.recompose())
        assertEquals(1, fixture.creates)
        assertEquals(0, fixture.releases)
        fixture.factory.releaseResource(fixture.token)
        assertNull(fixture.recompose())
        assertEquals(1, fixture.creates)
        fixture.close()
    }

    @Test fun disposedPausedAndDrainingResidenciesCannotBeReused() {
        for (state in listOf("paused", "disposed", "draining")) {
            val fixture = Fixture(3)
            fixture.completeAndReleaseSource()
            when (state) {
                "paused" -> fixture.owner.pause()
                "disposed" -> fixture.owner.dispose()
                "draining" -> {
                    fixture.factory.clear()
                    assertEquals(1, fixture.factory.occupiedGenerationCount())
                    assertTrue(fixture.telemetry.ownedBufferBytesSnapshot() > 0)
                    assertNull(fixture.factory.mountedResource(fixture.token))
                }
            }
            assertNull(fixture.recompose())
            assertEquals(1, fixture.creates)
            fixture.close()
        }
    }

    private companion object {
        fun descriptor(count: Int): PresentationDescriptor {
            val qualifier = CoverageRowsQualifier(1, 1, 0, 1, 1, 1)
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
