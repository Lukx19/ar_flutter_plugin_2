package com.uhg0.ar_flutter_plugin_2.visibilitygrid

import org.junit.Assert.*
import org.junit.Test

class DepthEmptySpaceSkippingTest {
    @Test fun `first four depth votes refine an existing canonical row with identical sparse semantics`() {
        val full = DepthEvidenceKernel()
        val sparse = DepthEvidenceKernel()
        val reference = MutableView(false)
        val indexed = MutableView(true)
        reference.seed(Voxel(0, 0, -10))
        indexed.seed(Voxel(0, 0, -10))
        try {
            for (sequence in 1L..4L) {
                val batch = axial(sequence, 0.05, 1_000)
                val expected = full.prepare(batch, reference) as DepthEvidenceResult.Accepted
                val actual = sparse.prepare(batch, indexed) as DepthEvidenceResult.Accepted
                assertEquivalent(expected, actual)
                if (sequence == 4L) assertEquals(1, actual.receipt.refineCount)
                full.applyPrepared(reference.publish(expected))
                sparse.applyPrepared(indexed.publish(actual))
            }
        } finally { full.close(); sparse.close() }
    }

    @Test fun `skipping matches full traversal for retained unpublished occupied free and conflicting evidence`() {
        val full = DepthEvidenceKernel()
        val sparse = DepthEvidenceKernel()
        val reference = MutableView(false)
        val indexed = MutableView(true)
        var sawConflict = false
        var sawRemoval = false
        try {
            for (sequence in 1L..32L) {
                val batch = when {
                    sequence <= 3 -> axial(sequence, 0.05, 1_000)
                    sequence == 4L -> axial(sequence, -0.8, 3_000)
                    sequence <= 8 -> axial(sequence, 0.05, 1_000)
                    sequence == 9L -> axial(sequence, 0.05, 1_000, conflict = true)
                    else -> axial(sequence, if (sequence % 2L == 0L) -0.8 else 0.8, 3_000)
                }
                val expected = full.prepare(batch, reference) as DepthEvidenceResult.Accepted
                val actual = sparse.prepare(batch, indexed) as DepthEvidenceResult.Accepted
                assertEquivalent(expected, actual)
                sawConflict = sawConflict || actual.receipt.conflictsRetained > 0
                sawRemoval = sawRemoval || actual.receipt.removeCount > 0
                if (sequence == 4L) {
                    // A discarded prepare must not change retained evidence or poison a later rebuild.
                    full.discardPrepared(); sparse.discardPrepared()
                    assertEquivalent(
                        full.prepare(batch, reference) as DepthEvidenceResult.Accepted,
                        sparse.prepare(batch, indexed) as DepthEvidenceResult.Accepted,
                    )
                }
                full.applyPrepared(reference.publish(expected))
                sparse.applyPrepared(indexed.publish(actual))
                if (sequence == 8L) {
                    // Independent feature publication can attach a canonical source
                    // after depth has retained unpublished conflicting evidence.
                    reference.seed(Voxel(0, 0, -10))
                    indexed.seed(Voxel(0, 0, -10))
                }
            }
            assertTrue(sawConflict)
            assertTrue(sawRemoval)
        } finally { full.close(); sparse.close() }
    }

    @Test fun `distinct near box and background maps exceed old ray cap and sustain eight published observations`() {
        val samples = roomSamples()
        val camera = translation(0.8, 0.8, 1.55)
        val intrinsics = VisibilityCameraIntrinsics(2_000, 2_000, 1_000.0, 1_000.0, 1_000.0, 1_000.0)
        val endpoints = samples.map {
            requireNotNull(DepthVoxelAddressing.quantize(DepthPointMm(
                800.0 + (it.x - 1_000) * it.depthMillimeters / 1_000.0,
                800.0 - (it.y - 1_000) * it.depthMillimeters / 1_000.0,
                1_550.0 - it.depthMillimeters,
            ), 100_000))
        }
        assertEquals(2_703, endpoints.toSet().size)
        assertEquals(256, endpoints.count { it.x in 0..15 && it.y in 0..15 && it.z in 0..15 })
        fun observation(sequence: Long) = DepthEvidenceBatch(
            sequence, sequence * 1_000_000_000L, frame(), camera.toList(), intrinsics,
            if (sequence % 2L == 0L) samples.asReversed() else samples, 0,
        )
        val original = DepthEvidenceKernel()
        try {
            val refused = original.prepare(observation(1), MutableView(false)) as DepthEvidenceResult.Refused
            assertEquals(DepthEvidenceRefusal.RAY_VISIT_CAPACITY, refused.reason)
            assertEquals(65_536, refused.receipt.rayVisits)
            println("depth-empty-space original accepted=${refused.receipt.acceptedSamples} traversalUnits=${refused.receipt.rayVisits} reason=${refused.reason}")
        } finally { original.close() }

        val kernel = DepthEvidenceKernel()
        val view = MutableView(true)
        val arrays = kernel.primitiveArraysForAccounting()
        val initial = kernel.resourceReceipt()
        var readPublishedRows = false
        try {
            for (sequence in 1L..8L) {
                val priorCanonicalHits = view.canonicalAddressHits
                val result = kernel.prepare(observation(sequence), view)
                assertTrue("observation $sequence: $result", result is DepthEvidenceResult.Accepted)
                val accepted = result as DepthEvidenceResult.Accepted
                println("depth-empty-space observation=$sequence accepted=${accepted.receipt.acceptedSamples} traversalUnits=${accepted.receipt.rayVisits} virtualWork=${accepted.work.virtualWorkUnits} creates=${accepted.receipt.createCount} refines=${accepted.receipt.refineCount}")
                assertEquals(2_703, accepted.receipt.acceptedSamples)
                assertTrue(accepted.receipt.rayVisits <= 65_536)
                if (sequence == 4L) assertTrue(accepted.receipt.createCount > 0)
                if (sequence > 4L) {
                    assertTrue(view.canonicalAddressHits > priorCanonicalHits)
                    readPublishedRows = true
                }
                kernel.applyPrepared(view.publish(accepted))
                assertEquals(initial.fixedPrimitiveBytes, kernel.resourceReceipt().fixedPrimitiveBytes)
                arrays.zip(kernel.primitiveArraysForAccounting()).forEach { (before, after) -> assertSame(before, after) }
            }
            assertTrue(readPublishedRows)
            assertEquals(13_358_480, initial.fixedPrimitiveBytes)
            assertEquals(15_987_160, initial.modeledMaximumSemanticStateBytes)
        } finally { kernel.close() }
    }

    private fun roomSamples(): List<VisibilityDepthSample> = buildList {
        // 256 distinct endpoints occupy the camera's own 1.6 m parent block.
        for (row in 0 until 16) for (column in 0 until 16) add(VisibilityDepthSample(
            475 + column * 70, 475 + row * 70, 1_000 + ((column + 2 * row) % 6) * 100, 255,
        ))
        for (index in 0 until 2_447) add(VisibilityDepthSample(
            100 + (index % 51) * 35, 80 + (index / 51) * 35, if (index % 2 == 0) 3_000 else 4_000, 255,
        ))
    }

    private fun axial(sequence: Long, cameraX: Double, depth: Int, conflict: Boolean = false): DepthEvidenceBatch {
        val endpointX = cameraX + (0.05 - cameraX) * (depth / 1_000.0)
        return DepthEvidenceBatch(
            sequence, sequence * 1_000_000_000L, frame(), translation(cameraX, 0.05, 0.05).toList(),
            VisibilityCameraIntrinsics(3, 1, 1.0, 1.0, 1.0 + (cameraX - endpointX) / (depth / 1_000.0), 0.0),
            if (conflict) listOf(VisibilityDepthSample(1, 0, 1_000, 255), VisibilityDepthSample(1, 0, 3_000, 255))
            else listOf(VisibilityDepthSample(1, 0, depth, 255)), 0,
        )
    }

    private fun assertEquivalent(expected: DepthEvidenceResult.Accepted, actual: DepthEvidenceResult.Accepted) {
        assertEquals(expected.changes, actual.changes)
        assertEquals(expected.receipt.copy(rayVisits = 0, p50VirtualWorkUnits = 0, p95VirtualWorkUnits = 0),
            actual.receipt.copy(rayVisits = 0, p50VirtualWorkUnits = 0, p95VirtualWorkUnits = 0))
        assertEquals(expected.work.copy(rayVisits = 0, virtualWorkUnits = 0), actual.work.copy(rayVisits = 0, virtualWorkUnits = 0))
    }

    private class MutableView(private val skipping: Boolean) : BoundedCanonicalSurfaceView {
        private val rows = linkedMapOf<Voxel, DepthCanonicalSurface>()
        private val rowsById = mutableMapOf<SurfaceId, DepthCanonicalSurface>()
        private val cache = if (skipping) LiveSurfaceSpatialCache(100_000, 100_000) else null
        private var nextId = 1L
        var canonicalAddressHits = 0; private set
        override var revisionPair = CanonicalRevisionPair(0, 0); private set
        override val surfaceCount: Int get() = rows.size
        override val supportsEmptyBlockSkipping: Boolean get() = skipping
        init { cache?.certifyCurrent(0, 0) }
        override fun isKnownEmptyBlock(blockX: Int, blockY: Int, blockZ: Int, blockVoxels: Int): Boolean =
            cache?.isKnownEmptyBlock(blockX, blockY, blockZ, revisionPair.geometryRevision, revisionPair.lineageRevision, blockVoxels) == true
        override fun findSurfaceById(id: SurfaceId) = rowsById[id]
        override fun findSurfaceAt(voxel: Voxel) = rows[voxel]?.let { canonicalAddressHits++; AddressedCanonicalSurface(voxel, it) }
        override fun visitRayCells(startGroupMm: DepthPointMm, endpointGroupMm: DepthPointMm, maximumVisits: Int,
            visitor: (Voxel, DepthCanonicalSurface?) -> Boolean): DepthRayVisitResult = error("kernel owns traversal")

        fun seed(voxel: Voxel) {
            val row = DepthCanonicalSurface(SurfaceId(nextId++), voxel, 0, 200, 1)
            rows[voxel] = row
            rowsById[row.id] = row
            revisionPair = CanonicalRevisionPair(1, 0)
            cache?.upsert(row.id, voxel)
            cache?.certifyCurrent(1, 0)
        }

        fun publish(result: DepthEvidenceResult.Accepted): LongArray {
            val retired = mutableListOf<Long>()
            for (change in result.changes) {
                if (change !is DepthEvidenceChange.Refine) for (i in 0 until change.sourceCount) {
                    val id = change.sourceAt(i)
                    val source = requireNotNull(rowsById.remove(id))
                    rows.remove(source.voxel); cache?.remove(id); retired += id.value
                }
                for (i in 0 until change.targetCount) {
                    val target = change.targetAt(i)
                    val id = target.id ?: if (change is DepthEvidenceChange.Refine) change.sourceId else SurfaceId(nextId++)
                    val normal = ((target.normalOctX.toInt() and 0xff) shl 8) or (target.normalOctY.toInt() and 0xff)
                    val row = DepthCanonicalSurface(id, target.voxel, normal, target.normalConfidence, 0)
                    rows[target.voxel] = row
                    rowsById[id] = row
                    cache?.upsert(id, target.voxel)
                }
            }
            if (result.changes.isNotEmpty()) revisionPair = CanonicalRevisionPair(
                revisionPair.geometryRevision + 1,
                revisionPair.lineageRevision + if (result.changes.any { it.operationKind.lineageChanged }) 1 else 0,
            )
            cache?.certifyCurrent(revisionPair.geometryRevision, revisionPair.lineageRevision)
            return retired.toLongArray()
        }
    }

    private fun translation(x: Double, y: Double, z: Double) = doubleArrayOf(
        1.0, 0.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 0.0, 1.0, 0.0, x, y, z, 1.0,
    )
    private fun frame(): VisibilityGroupFrame {
        val identity = translation(0.0, 0.0, 0.0)
        return VisibilityGroupFrame.copyOf(identity, identity, 100_000, 100_000)
    }
}
