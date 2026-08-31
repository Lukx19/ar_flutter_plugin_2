package com.uhg0.ar_flutter_plugin_2.visibilitygrid

import java.io.ByteArrayOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class M3CanonicalFeatureBatchTest {
    @Test
    fun `ordered upserts become one mixed add refine plan with ascending burned ids`() {
        val view = view(rows = listOf(surface(7, 7)), high = 8)
        val plan = prepared(M3MutableCanonicalOverlay.prepare(view, configuration(), batch(
            "batch", listOf(upsert(10, confidence = 191), upsert(7, confidence = 191), upsert(-2, confidence = 191)),
        )))

        assertEquals(M3PreparedMutationKind.FEATURE_BATCH, plan.kind)
        assertEquals(10L, plan.targetHighWater)
        assertEquals(3, plan.targetLiveSurfaceCount)
        assertEquals(3, plan.targetSourceCount)
        assertEquals(3, plan.targetSupportCount)
        assertEquals(0, plan.targetLineageCount)
        assertEquals(1L, plan.targetGeometryRevision)
        assertEquals(0L, plan.targetLineageRevision)
        assertEquals(listOf(7L, 8L, 9L), rows(plan).map { it.id.value })
        assertEquals(listOf(7, -2, 10), rows(plan).map { it.voxel.x })
        assertEquals(listOf(8L, 9L), support(plan).map { it.target.value })
        assertEquals(0, plan.work.dirtyLineageRecords)
        assertEquals(3, plan.work.dirtyRows)
        assertEquals(2, plan.work.dirtySourceRecords)
        assertEquals(2, plan.work.dirtySupportRecords)
        assertTrue(ByteArrayOutputStream().also(plan::writeWalTo).size() <= M3CompactCanonicalStore.JOURNAL_RESERVE_BYTES)
    }

    @Test
    fun `removal only and nonmaterial batches retain canonical state without publication`() {
        val view = view(rows = listOf(surface(1, 1)), high = 2)
        val removal = M3MutableCanonicalOverlay.prepare(view, configuration(), batch("removal", listOf(removal(1))))
        val same = M3MutableCanonicalOverlay.prepare(view, configuration(), batch("same", listOf(upsert(1, confidence = 192))))

        assertEquals(M3CanonicalMutationPreparation.NoOp(M3CanonicalStateReceipt(0, 0, 2, 1)), removal)
        assertEquals(M3CanonicalMutationPreparation.NoOp(M3CanonicalStateReceipt(0, 0, 2, 1)), same)
        assertEquals(0, view.mutations)
    }

    @Test
    fun `duplicate conflicting stale exhausted and journal batches refuse before a plan exists`() {
        val base = view(rows = listOf(surface(1, 1)), high = 2)
        fun refused(value: M3CanonicalMutationPreparation) = value as M3CanonicalMutationPreparation.Refused

        assertEquals(M3CanonicalMutationRefusal.OWNERSHIP_CONFLICT, refused(M3MutableCanonicalOverlay.prepare(base, configuration(), batch(
            "duplicate", listOf(upsert(2), upsert(2)),
        ))).reason)
        assertEquals(M3CanonicalMutationRefusal.OWNERSHIP_CONFLICT, refused(M3MutableCanonicalOverlay.prepare(base, configuration(), batch(
            "conflict", listOf(upsert(2), removal(2)),
        ))).reason)
        assertEquals(M3CanonicalMutationRefusal.REVISION_CONFLICT, refused(M3MutableCanonicalOverlay.prepare(base, configuration(),
            M3CanonicalFeatureBatchCommand("stale", 1, 0, listOf(upsert(2))),
        )).reason)
        assertEquals(M3CanonicalMutationRefusal.EXHAUSTED, refused(M3MutableCanonicalOverlay.prepare(
            view(rows = emptyList(), high = 0xffff_ffffL), configuration(), batch("u32", listOf(upsert(2), upsert(3))),
        )).reason)
        assertEquals(M3CanonicalMutationRefusal.JOURNAL_EXHAUSTED, refused(M3MutableCanonicalOverlay.prepare(base,
            configuration(changeJournalByteCapacity = 1), batch("journal", listOf(upsert(2))),
        )).reason)
    }

    private fun batch(id: String, changes: List<M3FeatureFusionChange>) = M3CanonicalFeatureBatchCommand(id, 0, 0, changes)
    private fun upsert(x: Int, confidence: Int = 191) = M3FeatureFusionChange.Upsert(M3FeatureFusionCandidate(
        x, 0, 0, 2, 1, listOf(M3FeatureNormalCandidate(x, 0, 0, M3FeatureNormalFace.PRIMARY, 0, 0, confidence)),
    ))
    private fun removal(x: Int) = M3FeatureFusionChange.Removal(x, 0, 0)
    private fun surface(id: Long, x: Int, confidence: Int = 192) = M3CompactSurface(M3SurfaceId(id), M3Voxel(x, 0, 0), 0, confidence)
    private fun prepared(value: M3CanonicalMutationPreparation) = (value as M3CanonicalMutationPreparation.Prepared).mutation
    private fun rows(plan: M3PreparedCanonicalMutation) = mutableListOf<M3PreparedRow>().also { values -> plan.visitDirtyRows { values += it; true } }
    private fun support(plan: M3PreparedCanonicalMutation) = mutableListOf<M3PreparedSupport>().also { values -> plan.visitDirtySupport { values += it; true } }
    private fun configuration(changeJournalByteCapacity: Int = 1_048_576) = M3SurfaceOwnershipConfiguration(changeJournalByteCapacity = changeJournalByteCapacity)
    private fun view(rows: List<M3CompactSurface>, high: Long) = TestView(rows, high)

    private class TestView(private val rows: List<M3CompactSurface>, high: Long) : M3CanonicalStateView {
        private val ids = rows.associateBy { it.id }
        private val voxels = rows.associateBy { it.voxel }
        override val cut = M3CompactCanonicalCut(
            M3SurfaceGroup("batch"), M3CompactCanonicalStore.PROFILE, 0, 0, high, rows.size, rows.size, rows.size, 0,
            null, M3CanonicalReceiptBytes(ByteArray(32)), M3CanonicalReceiptBytes(ByteArray(32) { 1 }),
        )
        var mutations = 0
        override fun findById(id: M3SurfaceId) = ids[id]
        override fun findByVoxel(voxel: M3Voxel) = voxels[voxel]
        override fun readPage(region: M3StorageRegion, page: Int, cursor: Int, limit: Int) = M3CompactPage(emptyList(), null, 0)
        override fun readSourceById(id: M3SurfaceId) = M3CanonicalPageRead.Complete(
            rows.firstOrNull { it.id == id }?.let { row ->
                M3PagedSource(row.id, row.voxel, row.packedNormal, row.normalConfidence,
                    M3CanonicalReceiptBytes(ByteArray(32) { row.id.value.toByte() }))
            }, 0, 0,
        )
        override fun visitSourceSupport(target: M3SurfaceId, cursor: M3SourceSupportCursor?, sink: (M3PagedSupport) -> Boolean) =
            M3SourceSupportRead.Complete(0, null, 0, 0)
        override fun retainedMemoryReceipt() = error("not used")
        override fun allocatedStorageReceipt() = error("not used")
        override fun close() { mutations++ }
    }
}
