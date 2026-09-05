package com.uhg0.ar_flutter_plugin_2.visibilitygrid

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CanonicalEvidenceBatchTest {
    @Test
    fun `refine must address the source voxel and does not mutate structure`() {
        val view = TestView(
            listOf(surface(1, 0), surface(2, 1), surface(3, 2)),
            high = 4,
            geometry = 7,
            lineage = 5,
        )
        val command = CanonicalEvidenceBatchCommand(
            commandId = "refine-current",
            expectedGeometryRevision = 7,
            expectedLineageRevision = 5,
            changes = listOf(
                DepthEvidenceChange.Refine(
                    SurfaceId(1),
                    CanonicalTarget(SurfaceId(1), Voxel(10, 0, 0), 0, 0, 192),
                ),
            ),
        )

        val preparation = MutableCanonicalOverlay.prepare(
            view,
            SurfaceOwnershipConfiguration(),
            command,
        )
        assertTrue(preparation is CanonicalMutationPreparation.Refused)
        assertEquals(
            CanonicalMutationRefusal.OWNERSHIP_CONFLICT,
            (preparation as CanonicalMutationPreparation.Refused).reason,
        )
    }

    @Test
    fun `refine at the current source voxel only updates the row`() {
        val view = TestView(
            listOf(surface(1, 0), surface(2, 1), surface(3, 2)),
            high = 4,
            geometry = 7,
            lineage = 5,
        )
        val preparation = MutableCanonicalOverlay.prepare(
            view,
            SurfaceOwnershipConfiguration(),
            CanonicalEvidenceBatchCommand(
                "refine-same", 7, 5,
                listOf(DepthEvidenceChange.Refine(
                    SurfaceId(1), CanonicalTarget(SurfaceId(1), Voxel(0, 0, 0), 1, 0, 193),
                )),
            ),
        ) as CanonicalMutationPreparation.Prepared
        val plan = preparation.mutation
        try {
            assertEquals(4L, plan.targetHighWater)
            assertEquals(3, plan.targetLiveSurfaceCount)
            assertEquals(3, plan.targetSourceCount)
            assertEquals(3, plan.targetSupportCount)
            assertEquals(5, plan.targetLineageCount)
            assertEquals(8L, plan.targetGeometryRevision)
            assertEquals(5L, plan.targetLineageRevision)
            assertEquals(listOf(1L), dirtyRows(plan).map { it.id.value })
            assertTrue(removed(plan).isEmpty())
            assertTrue(dirtySupport(plan).isEmpty())
            assertTrue(dirtyLineage(plan).isEmpty())
        } finally {
            plan.discard()
        }
    }

    @Test
    fun `mixed depth evidence changes become one geometry cut with one lineage advance`() {
        val view = TestView(
            listOf(
                surface(1, 0),
                surface(2, 1),
                surface(3, 2),
            ),
            high = 4,
            geometry = 7,
            lineage = 5,
        )
        val command = CanonicalEvidenceBatchCommand(
            commandId = "depth-mixed",
            expectedGeometryRevision = 7,
            expectedLineageRevision = 5,
            changes = listOf(
                DepthEvidenceChange.Remove(SurfaceId(3)),
                DepthEvidenceChange.Relocate(
                    SurfaceId(1),
                    CanonicalTarget(SurfaceId(1), Voxel(10, 0, 0), 0, 0, 192),
                ),
                DepthEvidenceChange.Create(
                    CanonicalTarget(null, Voxel(11, 0, 0), 0, 0, 192),
                ),
            ),
        )

        val preparation = MutableCanonicalOverlay.prepare(
            view,
            SurfaceOwnershipConfiguration(),
            command,
        ) as CanonicalMutationPreparation.Prepared
        val plan = preparation.mutation
        try {
            assertEquals(PreparedMutationKind.DEPTH_BATCH, plan.kind)
            assertEquals(5L, plan.targetHighWater)
            assertEquals(3, plan.targetLiveSurfaceCount)
            assertEquals(4, plan.targetSourceCount)
            assertEquals(5, plan.targetSupportCount)
            assertEquals(6, plan.targetLineageCount)
            assertEquals(8L, plan.targetGeometryRevision)
            assertEquals(6L, plan.targetLineageRevision)
            assertEquals(listOf(1L, 4L), dirtyRows(plan).map { it.id.value })
            assertEquals(listOf(1L, 3L), removed(plan).map { it.value })
            assertEquals(
                listOf(1L to 1L, 4L to 4L),
                dirtySupport(plan).map { it.target.value to it.source.id.value },
            )
            assertEquals(listOf(1L to 1L), dirtyLineage(plan).map { it.source.value to it.target.value })
        } finally {
            plan.discard()
        }
    }

    private fun surface(id: Long, x: Int) = CompactSurface(SurfaceId(id), Voxel(x, 0, 0), 0, 192)

    private fun dirtyRows(plan: PreparedCanonicalMutation) = mutableListOf<PreparedRow>().also { values ->
        plan.visitDirtyRows { values += it; true }
    }

    private fun removed(plan: PreparedCanonicalMutation) = mutableListOf<SurfaceId>().also { values ->
        plan.visitRemovedSurfaceIds { values += it; true }
    }

    private fun dirtySupport(plan: PreparedCanonicalMutation) = mutableListOf<PreparedSupport>().also { values ->
        plan.visitDirtySupport { values += it; true }
    }

    private fun dirtyLineage(plan: PreparedCanonicalMutation) = mutableListOf<LineageEdge>().also { values ->
        plan.visitDirtyLineage { values += it; true }
    }

    private class TestView(
        rows: List<CompactSurface>,
        high: Long,
        geometry: Long,
        lineage: Long,
    ) : CanonicalStateView {
        private val byId = rows.associateBy { it.id }
        private val byVoxel = rows.associateBy { it.voxel }
        override val cut = CompactCanonicalCut(
            SurfaceGroup("depth-batch"), CompactCanonicalStore.PROFILE,
            geometry, lineage, high, rows.size, rows.size, rows.size, lineage.toInt(),
            null, CanonicalReceiptBytes(ByteArray(32)), CanonicalReceiptBytes(ByteArray(32) { 1 }),
        )

        override fun findById(id: SurfaceId) = byId[id]
        override fun findByVoxel(voxel: Voxel) = byVoxel[voxel]
        override fun readPage(region: StorageRegion, page: Int, cursor: Int, limit: Int) =
            CompactPage(emptyList(), null, 0)
        override fun readSourceById(id: SurfaceId) = CanonicalPageRead.Complete(
            byId[id]?.let { row ->
                PagedSource(
                    row.id, row.voxel, row.packedNormal, row.normalConfidence,
                    CanonicalReceiptBytes(ByteArray(32) { row.id.value.toByte() }),
                )
            }, 0, 0,
        )
        override fun visitSourceSupport(
            target: SurfaceId,
            cursor: SourceSupportCursor?,
            sink: (PagedSupport) -> Boolean,
        ) = SourceSupportRead.Complete(0, null, 0, 0)
        override fun retainedMemoryReceipt() = error("not used")
        override fun allocatedStorageReceipt() = error("not used")
        override fun close() = Unit
    }
}
