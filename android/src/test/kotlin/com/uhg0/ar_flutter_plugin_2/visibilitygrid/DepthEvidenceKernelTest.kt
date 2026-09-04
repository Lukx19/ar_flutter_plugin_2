package com.uhg0.ar_flutter_plugin_2.visibilitygrid

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DepthEvidenceKernelTest {
    @Test
    fun `translated group unprojects the locked depth fixture`() {
        val kernel = DepthEvidenceKernel()
        val view = FakeCanonicalView()
        val batch = batch(
            timestamp = 1,
            groupFrame = frame(),
            groupFromCamera = translation(0.0, 0.0, 0.0),
            samples = listOf(VisibilityDepthSample(2, 1, 1_000, 200)),
        )

        val result = kernel.prepare(batch, view)

        assertTrue(result is DepthEvidenceResult.Accepted)
        val accepted = result as DepthEvidenceResult.Accepted
        assertEquals(0, accepted.changes.size)
        assertEquals(1, accepted.receipt.acceptedSamples)
        assertEquals(Voxel(2, 0, -10), view.visitedEndpoints.single())
    }

    @Test
    fun `four independent occupied observations prepare one create and apply atomically`() {
        val kernel = DepthEvidenceKernel()
        val view = FakeCanonicalView()
        val first = batch(1, frame(), translation(0.0, 0.0, 0.0), listOf(sample()))

        repeat(3) { index ->
            assertTrue(kernel.prepare(first.copyWithTimestamp(index + 1L), view) is DepthEvidenceResult.Accepted)
            kernel.applyPrepared()
        }
        val staged = kernel.prepare(first.copyWithTimestamp(4), view) as DepthEvidenceResult.Accepted

        assertEquals(1, staged.changes.size)
        assertTrue(staged.changes.single() is DepthEvidenceChange.Create)
        assertEquals(1, kernel.resourceReceipt().residentEvidenceRows)
        assertTrue(kernel.applyPrepared() is DepthEvidenceApplyResult.Applied)
        assertEquals(1, kernel.resourceReceipt().residentEvidenceRows)
        assertEquals(32, kernel.resourceReceipt().residentBytes)
        assertTrue(kernel.applyPrepared() is DepthEvidenceApplyResult.NoPrepared)
    }

    @Test
    fun `endpoint safety and separated views remove only stale canonical cells`() {
        val stale = Voxel(0, 0, -5)
        val safety = Voxel(0, 0, -9)
        val view = FakeCanonicalView(
            surfaces = mapOf(
                stale to surface(7, stale),
                safety to surface(8, safety),
            ),
            rayCells = listOf(stale, safety),
        )
        val kernel = DepthEvidenceKernel()
        val removedIds = mutableListOf<SurfaceId>()
        repeat(4) { index ->
            val result = kernel.prepare(
                depthBatch(index + 1L, cameraX = 0.05, endpointX = 0.05),
                view,
            ) as DepthEvidenceResult.Accepted
            removedIds += result.changes.filterIsInstance<DepthEvidenceChange.Remove>().map { it.sourceId }
            kernel.applyPrepared()
            assertTrue(result.receipt.rayVisits <= 2)
        }
        repeat(4) { index ->
            val result = kernel.prepare(
                depthBatch(10L + index, cameraX = 0.55, endpointX = -0.45),
                view,
            ) as DepthEvidenceResult.Accepted
            removedIds += result.changes.filterIsInstance<DepthEvidenceChange.Remove>().map { it.sourceId }
            kernel.applyPrepared()
        }

        val receipt = kernel.resourceReceipt()
        assertTrue(SurfaceId(7) in removedIds)
        assertTrue(SurfaceId(8) !in removedIds)
        val last = kernel.prepare(
            depthBatch(30, cameraX = 0.55, endpointX = -0.45),
            view,
        ) as DepthEvidenceResult.Accepted
        assertTrue(last.changes.none { it is DepthEvidenceChange.Remove && it.sourceId == SurfaceId(8) })
    }

    @Test
    fun `one observation with repeated rays contributes one free vote`() {
        val stale = Voxel(0, 0, -5)
        val view = FakeCanonicalView(
            surfaces = mapOf(stale to surface(7, stale)),
            rayCells = List(32) { stale },
        )
        val kernel = DepthEvidenceKernel()
        val result = kernel.prepare(depthBatch(1, 0.05, 0.05), view) as DepthEvidenceResult.Accepted

        assertEquals("receipt=${result.receipt}", 2, result.receipt.touchedEvidenceRows)
        assertEquals("receipt=${result.receipt}", 1, result.receipt.independentDirectionVotes)
        assertTrue(result.changes.isEmpty())
    }

    @Test
    fun `carved canonical identity is restored after four occupied observations`() {
        val stale = Voxel(0, 0, -5)
        val view = FakeCanonicalView(
            surfaces = mapOf(stale to surface(7, stale)),
            rayCells = listOf(stale),
        )
        val kernel = DepthEvidenceKernel()
        repeat(4) { index ->
            val result = kernel.prepare(
                depthBatch(1L + index, cameraX = 0.05, endpointX = 0.05), view,
            ) as DepthEvidenceResult.Accepted
            kernel.applyPrepared()
            assertTrue(result.changes.none { it is DepthEvidenceChange.Remove })
        }
        var removed = false
        repeat(4) { index ->
            val result = kernel.prepare(
                depthBatch(10L + index, cameraX = 0.55, endpointX = -0.45), view,
            ) as DepthEvidenceResult.Accepted
            removed = removed || result.changes.any { it is DepthEvidenceChange.Remove && it.sourceId == SurfaceId(7) }
            kernel.applyPrepared()
        }
        assertTrue(removed)

        repeat(3) { index ->
            val result = kernel.prepare(
                depthBatch(30L + index, cameraX = 0.05, endpointX = 0.05, depthMillimeters = 500), view,
            ) as DepthEvidenceResult.Accepted
            assertTrue(result.changes.none { it is DepthEvidenceChange.Relocate || it is DepthEvidenceChange.Refine })
            kernel.applyPrepared()
        }
        val restored = kernel.prepare(depthBatch(40, 0.05, 0.05, depthMillimeters = 500), view)
            as DepthEvidenceResult.Accepted
        assertTrue(restored.changes.any { it is DepthEvidenceChange.Refine && it.sourceId == SurfaceId(7) })
        kernel.applyPrepared()
        val next = kernel.prepare(depthBatch(41, 0.05, 0.05, depthMillimeters = 500), view)
            as DepthEvidenceResult.Accepted
        assertTrue(next.changes.none { it is DepthEvidenceChange.Remove })
    }

    @Test
    fun `typed refusal leaves prepared and committed state unchanged`() {
        val kernel = DepthEvidenceKernel()
        val view = FakeCanonicalView()
        val valid = depthBatch(1, 0.05, 0.05)
        repeat(4) { index ->
            kernel.prepare(valid.copyWithTimestamp(index + 1L), view)
            kernel.applyPrepared()
        }
        val before = kernel.resourceReceipt()
        val refusal = kernel.prepare(valid.copyWithTimestamp(5), view)
        assertTrue(refusal is DepthEvidenceResult.Accepted)
        assertTrue(kernel.prepare(valid.copyWithTimestamp(6), view) is DepthEvidenceResult.Refused)
        assertEquals(DepthEvidenceRefusal.PREPARED_BUSY, (kernel.prepare(valid.copyWithTimestamp(7), view) as DepthEvidenceResult.Refused).reason)
        kernel.discardPrepared()
        assertEquals(before, kernel.resourceReceipt())

        val invalid = valid.copyWithTimestamp(8).withSamples(listOf(VisibilityDepthSample(0, 0, 1, 255)))
        val invalidResult = kernel.prepare(invalid, view) as DepthEvidenceResult.Refused
        assertEquals(DepthEvidenceRefusal.INVALID_SAMPLE, invalidResult.reason)
        assertEquals(before, kernel.resourceReceipt())
    }

    @Test
    fun `close releases logical rows and refuses later preparation`() {
        val kernel = DepthEvidenceKernel()
        val view = FakeCanonicalView()
        kernel.prepare(depthBatch(1, 0.05, 0.05), view)
        kernel.applyPrepared()
        kernel.close()

        assertEquals(0, kernel.resourceReceipt().residentEvidenceRows)
        assertEquals(0, kernel.resourceReceipt().residentBytes)
        assertEquals(DepthEvidenceRefusal.CLOSED, (kernel.prepare(depthBatch(2, 0.05, 0.05), view) as DepthEvidenceResult.Refused).reason)
    }

    private fun batch(
        timestamp: Long,
        groupFrame: VisibilityGroupFrame,
        groupFromCamera: DoubleArray,
        samples: List<VisibilityDepthSample>,
    ) = DepthEvidenceBatch(
        sequence = timestamp,
        sourceTimestampNs = timestamp,
        groupFrame = groupFrame,
        groupFromCameraGl = groupFromCamera.toList(),
        intrinsics = VisibilityCameraIntrinsics(4, 3, 2.0, 2.0, 1.5, 1.0),
        samples = samples,
        sourceRejectedSamples = 0,
    )

    private fun depthBatch(timestamp: Long, cameraX: Double, endpointX: Double, depthMillimeters: Int = 1_000) =
        DepthEvidenceBatch(
            sequence = timestamp,
            sourceTimestampNs = timestamp,
            groupFrame = frame(),
            groupFromCameraGl = translation(cameraX, 0.05, 0.05).toList(),
            intrinsics = VisibilityCameraIntrinsics(
                1, 1, 1.0, 1.0, (cameraX - endpointX) / 1.0, 0.0,
            ),
            samples = listOf(VisibilityDepthSample(0, 0, depthMillimeters, 255)),
            sourceRejectedSamples = 0,
        )

    private fun sample() = VisibilityDepthSample(0, 0, 1_000, 255)

    private fun surface(id: Long, voxel: Voxel) = DepthCanonicalSurface(
        SurfaceId(id), voxel, 0x1010, 200, 1,
    )

    private fun DepthEvidenceBatch.copyWithTimestamp(timestamp: Long) = DepthEvidenceBatch(
        sequence = timestamp,
        sourceTimestampNs = timestamp,
        groupFrame = groupFrame,
        groupFromCameraGl = groupFromCameraGl,
        intrinsics = intrinsics,
        samples = samples,
        sourceRejectedSamples = sourceRejectedSamples,
        tracking = tracking,
    )

    private fun DepthEvidenceBatch.withSamples(samples: List<VisibilityDepthSample>) = DepthEvidenceBatch(
        sequence = sequence,
        sourceTimestampNs = sourceTimestampNs,
        groupFrame = groupFrame,
        groupFromCameraGl = groupFromCameraGl,
        intrinsics = intrinsics,
        samples = samples,
        sourceRejectedSamples = sourceRejectedSamples,
        tracking = tracking,
    )

    private fun frame() = VisibilityGroupFrame.copyOf(
        identityVisibilityGridTransform(),
        identityVisibilityGridTransform(),
        voxelSizeMicrometres = 100_000,
        modelCapacity = 100,
    )

    private fun translation(x: Double, y: Double, z: Double) =
        identityVisibilityGridTransform().also {
            it[12] = x
            it[13] = y
            it[14] = z
        }

    private class FakeCanonicalView(
        private val surfaces: Map<Voxel, DepthCanonicalSurface> = emptyMap(),
        private val rayCells: List<Voxel> = emptyList(),
    ) : BoundedCanonicalSurfaceView {
        override val geometryRevision: Long = 0
        override val lineageRevision: Long = 0
        override val surfaceCount: Int = 0
        val visitedEndpoints = mutableListOf<Voxel>()

        override fun findSurfaceById(id: SurfaceId): DepthCanonicalSurface? = null

        override fun findSurfaceAt(voxel: Voxel): DepthCanonicalSurface? {
            visitedEndpoints += voxel
            return surfaces[voxel]
        }

        override fun visitRayCells(
            startGroupMm: DepthPointMm,
            endpointGroupMm: DepthPointMm,
            maximumVisits: Int,
            visitor: (Voxel, DepthCanonicalSurface?) -> Boolean,
        ): DepthRayVisitResult {
            rayCells.take(maximumVisits).forEach { voxel -> visitor(voxel, surfaces[voxel]) }
            return DepthRayVisitResult(rayCells.size.coerceAtMost(maximumVisits), rayCells.size > maximumVisits)
        }
    }
}
