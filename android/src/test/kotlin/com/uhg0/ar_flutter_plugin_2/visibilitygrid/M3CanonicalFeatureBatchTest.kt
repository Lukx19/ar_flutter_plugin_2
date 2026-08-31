package com.uhg0.ar_flutter_plugin_2.visibilitygrid

import java.io.ByteArrayOutputStream
import java.util.Collections
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class M3CanonicalFeatureBatchTest {
    @Test
    fun `scalar worst case preflight rejects max plus one before owned work or authority`() {
        val budget = M3MutableCanonicalOverlay.featureBatchBudget(configuration(), "limit")
        assertEquals(5_576, budget.journalAndCurrentMaximum)
        assertEquals(16_247, budget.sharedMaximum)
        assertEquals(3_552, budget.constructionMaximum)
        assertEquals(3_552, budget.maximumUpserts)
        assertEquals(Int.MAX_VALUE, M3MutableCanonicalOverlay.boundedRecordMaximum(Long.MAX_VALUE, 0, 1))
        assertEquals(0, M3MutableCanonicalOverlay.boundedRecordMaximum(Long.MAX_VALUE - 1, Long.MAX_VALUE, 1))

        val view = view(rows = emptyList(), high = 1)
        val refused = M3MutableCanonicalOverlay.prepare(
            view, configuration(), batch("limit", List(budget.maximumUpserts + 1) { upsert(it) }),
        ) as M3CanonicalMutationPreparation.Refused

        assertEquals(M3CanonicalMutationRefusal.JOURNAL_EXHAUSTED, refused.reason)
        assertEquals(budget.maximumUpserts + 1, refused.preflightWork.featureBatchUpserts)
        assertEquals(budget.maximumUpserts, refused.preflightWork.featureBatchMaximumUpserts)
        assertEquals(M3CanonicalMutationPreflightWork(
            featureBatchUpserts = budget.maximumUpserts + 1,
            featureBatchMaximumUpserts = budget.maximumUpserts,
        ), refused.preflightWork)
        assertEquals(0, view.authoritySnapshots)
        assertEquals(0, view.authorityReads)
    }

    @Test
    fun `exact scalar maximum stays within the shared journal current and construction budget`() {
        val budget = M3MutableCanonicalOverlay.featureBatchBudget(configuration(), "limit")
        val view = view(rows = emptyList(), high = 1)
        val plan = prepared(M3MutableCanonicalOverlay.prepare(
            view, configuration(), batch("limit", List(budget.maximumUpserts) { upsert(it) }),
        ))

        assertEquals(budget.maximumUpserts, plan.work.dirtyRows)
        assertTrue(plan.work.walBytes <= M3CompactCanonicalStore.JOURNAL_RESERVE_BYTES)
        assertTrue(plan.work.currentBytes <= M3CompactCanonicalStore.JOURNAL_RESERVE_BYTES)
        assertTrue(plan.work.stagingBytes <= M3CompactCanonicalStore.JOURNAL_RESERVE_BYTES)
        assertEquals(M3CompactCanonicalStore.JOURNAL_RESERVE_BYTES, plan.work.constructionPeakBytes)
    }

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
        val removal = M3MutableCanonicalOverlay.prepare(
            view, configuration(), batch("removal", Collections.nCopies(100_001, removal(1))),
        )
        val same = M3MutableCanonicalOverlay.prepare(view, configuration(), batch("same", listOf(upsert(1, confidence = 192))))

        assertEquals(M3CanonicalMutationPreparation.NoOp(M3CanonicalStateReceipt(0, 0, 2, 1)), removal)
        assertEquals(M3CanonicalMutationPreparation.NoOp(M3CanonicalStateReceipt(0, 0, 2, 1)), same)
        assertEquals(0, view.mutations)
        assertEquals(1, view.authoritySnapshots) // only the accepted nonmaterial upsert
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
        var authoritySnapshots = 0
        var authorityReads = 0
        override fun readWorkReceipt(): M3CanonicalReadWork {
            authoritySnapshots++
            return M3CanonicalReadWork.ZERO
        }
        override fun findById(id: M3SurfaceId) = ids[id].also { authorityReads++ }
        override fun findByVoxel(voxel: M3Voxel) = voxels[voxel].also { authorityReads++ }
        override fun readPage(region: M3StorageRegion, page: Int, cursor: Int, limit: Int) = M3CompactPage(emptyList(), null, 0)
        override fun readSourceById(id: M3SurfaceId) = M3CanonicalPageRead.Complete(
            rows.firstOrNull { it.id == id }?.let { row ->
                M3PagedSource(row.id, row.voxel, row.packedNormal, row.normalConfidence,
                    M3CanonicalReceiptBytes(ByteArray(32) { row.id.value.toByte() }))
            }, 0, 0,
        ).also { authorityReads++ }
        override fun visitSourceSupport(target: M3SurfaceId, cursor: M3SourceSupportCursor?, sink: (M3PagedSupport) -> Boolean) =
            M3SourceSupportRead.Complete(0, null, 0, 0)
        override fun retainedMemoryReceipt() = error("not used")
        override fun allocatedStorageReceipt() = error("not used")
        override fun close() { mutations++ }
    }
}
