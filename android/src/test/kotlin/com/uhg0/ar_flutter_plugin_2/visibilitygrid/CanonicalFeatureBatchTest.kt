package com.uhg0.ar_flutter_plugin_2.visibilitygrid

import java.io.ByteArrayOutputStream
import java.util.Collections
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CanonicalFeatureBatchTest {
    @Test
    fun `modified UTF command boundary is shared by admission and budget before owned work`() {
        val nulValid = "\u0000".repeat(128)
        val nulInvalid = "\u0000".repeat(129)
        val surrogateValid = "\ud800".repeat(85)
        val surrogateInvalid = "\ud800".repeat(86)
        val asciiValid = "a".repeat(256)
        val asciiInvalid = "a".repeat(257)
        assertEquals(listOf(256L, 258L, 255L, 258L, 256L, 257L), listOf(
            nulValid, nulInvalid, surrogateValid, surrogateInvalid, asciiValid, asciiInvalid,
        ).map(::modifiedUtf8Length))
        assertEquals(3_552, MutableCanonicalOverlay.featureBatchBudget(configuration(), "limit").maximumUpserts)

        listOf(nulValid, surrogateValid, asciiValid).forEach { commandId ->
            assertTrue(validM3CommandId(commandId))
            val view = view(rows = emptyList(), high = 1)
            val plan = prepared(MutableCanonicalOverlay.prepare(
                view, configuration(), CanonicalFeatureBatchCommand(commandId, 0, 0, listOf(upsert(1))),
            ))
            assertEquals(modifiedUtf8Length(commandId), plan.commandId.let(::modifiedUtf8Length))
            assertEquals(2, view.authoritySnapshots) // before and after accepted authority work
            plan.discard()
        }

        listOf("", nulInvalid, surrogateInvalid, asciiInvalid).forEach { commandId ->
            assertTrue(!validM3CommandId(commandId))
            val view = view(rows = emptyList(), high = 1)
            val refused = MutableCanonicalOverlay.prepare(
                view, configuration(), CanonicalFeatureBatchCommand(commandId, 0, 0, listOf(upsert(1))),
            ) as CanonicalMutationPreparation.Refused
            assertEquals(CanonicalMutationRefusal.INVALID_COMMAND, refused.reason)
            assertEquals(CanonicalMutationPreflightWork(), refused.preflightWork)
            assertEquals(0, view.authoritySnapshots)
            assertEquals(0, view.authorityReads)
        }
    }

    @Test
    fun `scalar worst case preflight rejects max plus one before owned work or authority`() {
        val budget = MutableCanonicalOverlay.featureBatchBudget(configuration(), "limit")
        assertEquals(5_576, budget.journalAndCurrentMaximum)
        assertEquals(16_247, budget.sharedMaximum)
        assertEquals(3_552, budget.constructionMaximum)
        assertEquals(3_552, budget.maximumUpserts)
        assertEquals(Int.MAX_VALUE, MutableCanonicalOverlay.boundedRecordMaximum(Long.MAX_VALUE, 0, 1))
        assertEquals(0, MutableCanonicalOverlay.boundedRecordMaximum(Long.MAX_VALUE - 1, Long.MAX_VALUE, 1))

        val view = view(rows = emptyList(), high = 1)
        val refused = MutableCanonicalOverlay.prepare(
            view, configuration(), batch("limit", List(budget.maximumUpserts + 1) { upsert(it) }),
        ) as CanonicalMutationPreparation.Refused

        assertEquals(CanonicalMutationRefusal.JOURNAL_EXHAUSTED, refused.reason)
        assertEquals(budget.maximumUpserts + 1, refused.preflightWork.featureBatchUpserts)
        assertEquals(budget.maximumUpserts, refused.preflightWork.featureBatchMaximumUpserts)
        assertEquals(CanonicalMutationPreflightWork(
            featureBatchUpserts = budget.maximumUpserts + 1,
            featureBatchMaximumUpserts = budget.maximumUpserts,
        ), refused.preflightWork)
        assertEquals(0, view.authoritySnapshots)
        assertEquals(0, view.authorityReads)
    }

    @Test
    fun `exact scalar maximum stays within the shared journal current and construction budget`() {
        val budget = MutableCanonicalOverlay.featureBatchBudget(configuration(), "limit")
        val view = view(rows = emptyList(), high = 1)
        val plan = prepared(MutableCanonicalOverlay.prepare(
            view, configuration(), batch("limit", List(budget.maximumUpserts) { upsert(it) }),
        ))

        assertEquals(budget.maximumUpserts, plan.work.dirtyRows)
        assertTrue(plan.work.walBytes <= CompactCanonicalStore.JOURNAL_RESERVE_BYTES)
        assertTrue(plan.work.currentBytes <= CompactCanonicalStore.JOURNAL_RESERVE_BYTES)
        assertTrue(plan.work.stagingBytes <= CompactCanonicalStore.JOURNAL_RESERVE_BYTES)
        assertEquals(CompactCanonicalStore.JOURNAL_RESERVE_BYTES, plan.work.constructionPeakBytes)
    }

    @Test
    fun `ordered upserts become one mixed add refine plan with ascending burned ids`() {
        val view = view(rows = listOf(surface(7, 7)), high = 8)
        val plan = prepared(MutableCanonicalOverlay.prepare(view, configuration(), batch(
            "batch", listOf(upsert(10, confidence = 191), upsert(7, confidence = 191), upsert(-2, confidence = 191)),
        )))

        assertEquals(PreparedMutationKind.FEATURE_BATCH, plan.kind)
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
        assertTrue(ByteArrayOutputStream().also(plan::writeWalTo).size() <= CompactCanonicalStore.JOURNAL_RESERVE_BYTES)
    }

    @Test
    fun `removal only and nonmaterial batches retain canonical state without publication`() {
        val view = view(rows = listOf(surface(1, 1)), high = 2)
        val removal = MutableCanonicalOverlay.prepare(
            view, configuration(), batch("removal", Collections.nCopies(100_001, removal(1))),
        )
        val same = MutableCanonicalOverlay.prepare(view, configuration(), batch("same", listOf(upsert(1, confidence = 192))))

        assertEquals(CanonicalMutationPreparation.NoOp(CanonicalStateReceipt(0, 0, 2, 1)), removal)
        assertEquals(CanonicalMutationPreparation.NoOp(CanonicalStateReceipt(0, 0, 2, 1)), same)
        assertEquals(0, view.mutations)
        assertEquals(1, view.authoritySnapshots) // only the accepted nonmaterial upsert
    }

    @Test
    fun `duplicate conflicting stale exhausted and journal batches refuse before a plan exists`() {
        val base = view(rows = listOf(surface(1, 1)), high = 2)
        fun refused(value: CanonicalMutationPreparation) = value as CanonicalMutationPreparation.Refused

        assertEquals(CanonicalMutationRefusal.OWNERSHIP_CONFLICT, refused(MutableCanonicalOverlay.prepare(base, configuration(), batch(
            "duplicate", listOf(upsert(2), upsert(2)),
        ))).reason)
        assertEquals(CanonicalMutationRefusal.OWNERSHIP_CONFLICT, refused(MutableCanonicalOverlay.prepare(base, configuration(), batch(
            "conflict", listOf(upsert(2), removal(2)),
        ))).reason)
        assertEquals(CanonicalMutationRefusal.REVISION_CONFLICT, refused(MutableCanonicalOverlay.prepare(base, configuration(),
            CanonicalFeatureBatchCommand("stale", 1, 0, listOf(upsert(2))),
        )).reason)
        assertEquals(CanonicalMutationRefusal.EXHAUSTED, refused(MutableCanonicalOverlay.prepare(
            view(rows = emptyList(), high = 0xffff_ffffL), configuration(), batch("u32", listOf(upsert(2), upsert(3))),
        )).reason)
        assertEquals(CanonicalMutationRefusal.JOURNAL_EXHAUSTED, refused(MutableCanonicalOverlay.prepare(base,
            configuration(changeJournalByteCapacity = 1), batch("journal", listOf(upsert(2))),
        )).reason)
    }

    private fun batch(id: String, changes: List<FeatureFusionChange>) = CanonicalFeatureBatchCommand(id, 0, 0, changes)
    private fun upsert(x: Int, confidence: Int = 191) = FeatureFusionChange.Upsert(FeatureFusionCandidate(
        x, 0, 0, 2, 1, listOf(FeatureNormalCandidate(x, 0, 0, FeatureNormalFace.PRIMARY, 0, 0, confidence)),
    ))
    private fun removal(x: Int) = FeatureFusionChange.Removal(x, 0, 0)
    private fun surface(id: Long, x: Int, confidence: Int = 192) = CompactSurface(SurfaceId(id), Voxel(x, 0, 0), 0, confidence)
    private fun prepared(value: CanonicalMutationPreparation) = (value as CanonicalMutationPreparation.Prepared).mutation
    private fun rows(plan: PreparedCanonicalMutation) = mutableListOf<PreparedRow>().also { values -> plan.visitDirtyRows { values += it; true } }
    private fun support(plan: PreparedCanonicalMutation) = mutableListOf<PreparedSupport>().also { values -> plan.visitDirtySupport { values += it; true } }
    private fun configuration(changeJournalByteCapacity: Int = 1_048_576) = SurfaceOwnershipConfiguration(changeJournalByteCapacity = changeJournalByteCapacity)
    private fun view(rows: List<CompactSurface>, high: Long) = TestView(rows, high)

    private class TestView(private val rows: List<CompactSurface>, high: Long) : CanonicalStateView {
        private val ids = rows.associateBy { it.id }
        private val voxels = rows.associateBy { it.voxel }
        override val cut = CompactCanonicalCut(
            SurfaceGroup("batch"), CompactCanonicalStore.PROFILE, 0, 0, high, rows.size, rows.size, rows.size, 0,
            null, CanonicalReceiptBytes(ByteArray(32)), CanonicalReceiptBytes(ByteArray(32) { 1 }),
        )
        var mutations = 0
        var authoritySnapshots = 0
        var authorityReads = 0
        override fun readWorkReceipt(): CanonicalReadWork {
            authoritySnapshots++
            return CanonicalReadWork.ZERO
        }
        override fun findById(id: SurfaceId) = ids[id].also { authorityReads++ }
        override fun findByVoxel(voxel: Voxel) = voxels[voxel].also { authorityReads++ }
        override fun readPage(region: StorageRegion, page: Int, cursor: Int, limit: Int) = CompactPage(emptyList(), null, 0)
        override fun readSourceById(id: SurfaceId) = CanonicalPageRead.Complete(
            rows.firstOrNull { it.id == id }?.let { row ->
                PagedSource(row.id, row.voxel, row.packedNormal, row.normalConfidence,
                    CanonicalReceiptBytes(ByteArray(32) { row.id.value.toByte() }))
            }, 0, 0,
        ).also { authorityReads++ }
        override fun visitSourceSupport(target: SurfaceId, cursor: SourceSupportCursor?, sink: (PagedSupport) -> Boolean) =
            SourceSupportRead.Complete(0, null, 0, 0)
        override fun retainedMemoryReceipt() = error("not used")
        override fun allocatedStorageReceipt() = error("not used")
        override fun close() { mutations++ }
    }
}
