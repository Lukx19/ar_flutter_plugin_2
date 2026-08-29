package com.uhg0.ar_flutter_plugin_2.visibilitygrid

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.openjdk.jol.info.GraphLayout
import java.nio.file.Files
import java.security.MessageDigest
import java.util.Collections

class M3CanonicalMutationOverlayTest {
    @Test
    fun `C12 confidence bands allocation provenance and normal orientation define material refinement`() {
        val provenance = ByteArray(32) { (it + 7).toByte() }
        val reliable = view(
            rows = listOf(surface(1, 0, confidence = 191)), high = 2,
            sources = listOf(source(1, 0, confidence = 191, fingerprint = provenance)),
        )
        val boundary = prepared(M3SurfaceOwnership.prepareMutation(
            reliable, configuration(), feature("band-boundary", 0, 0, target(0, M3SurfaceId(1), confidence = 192)),
        ))
        assertEquals(192, boundary.dirtyRows.single().normalConfidence)
        assertArrayEquals(provenance, boundary.dirtyRows.single().allocatedBy)

        val strong = view(
            rows = listOf(surface(1, 0, confidence = 192)), high = 2,
            sources = listOf(source(1, 0, confidence = 192, fingerprint = provenance)),
        )
        assertTrue(M3SurfaceOwnership.prepareMutation(
            strong, configuration(), feature("same-strong-band", 0, 0, target(0, M3SurfaceId(1), confidence = 193)),
        ) is M3CanonicalMutationPreparation.NoOp)
        val direction = prepared(M3SurfaceOwnership.prepareMutation(
            strong, configuration(), feature("normal-change", 0, 0, target(0, M3SurfaceId(1), confidence = 193, normalX = 1)),
        ))
        assertTrue(direction.dirtyRows.single().packedNormal != 0)
        assertArrayEquals(provenance, direction.dirtyRows.single().allocatedBy)

        val add = prepared(M3SurfaceOwnership.prepareMutation(
            view(), configuration(), feature("new-allocation", 0, 0, target(0)),
        ))
        assertArrayEquals(
            MessageDigest.getInstance("SHA-256").digest("new-allocation".encodeToByteArray()),
            add.dirtyRows.single().allocatedBy,
        )
    }

    @Test
    fun `feature add refine and reobservation prepare stable dirty plans without mutating the view`() {
        val empty = view()
        val add = prepared(M3SurfaceOwnership.prepareMutation(empty, configuration(), feature("add", 0, 0, target(0))))
        assertEquals(M3PreparedMutationKind.FEATURE_ADD, add.kind)
        assertEquals(listOf(1L), add.dirtyRows.map { it.id.value })
        assertEquals(1, add.targetGeometryRevision); assertEquals(0, add.targetLineageRevision)
        assertEquals(2L, add.targetHighWater); assertEquals(1, add.dirtySupport.size)
        assertEquals(empty.cut, add.sourceCut)
        assertEquals(32, add.commandHash.size); assertEquals(32, add.commandFingerprint.size)
        assertTrue(add.walBytes.size > 0 && add.currentBytes.size > 0)
        assertEquals(add.walBytes.size + add.currentBytes.size.toLong(), add.work.stagingBytes)
        assertEquals(0, empty.mutations)

        val active = view(rows = listOf(surface(1, 0)), geometry = 1, high = 2, sources = listOf(source(1, 0)), supports = mapOf(1L to listOf(source(1, 0))))
        val refine = prepared(M3SurfaceOwnership.prepareMutation(active, configuration(), feature("refine", 1, 0, target(0, M3SurfaceId(1), confidence = 191))))
        assertEquals(M3PreparedMutationKind.FEATURE_REFINE, refine.kind)
        assertEquals(1L, refine.dirtyRows.single().id.value)
        assertEquals(2, refine.targetGeometryRevision); assertEquals(0, refine.targetLineageRevision)
        assertTrue(refine.dirtySupport.isEmpty()); assertTrue(refine.dirtyLineage.isEmpty())
        assertArrayEquals(source(1, 0).allocationFingerprint.toByteArray(), refine.dirtyRows.single().allocatedBy)

        val noOp = M3SurfaceOwnership.prepareMutation(active, configuration(), feature("same", 1, 0, target(0, M3SurfaceId(1))))
        assertEquals(M3CanonicalMutationPreparation.NoOp(M3CanonicalStateReceipt(1, 0, 2, 1)), noOp)
        assertEquals(0, active.mutations)
    }

    @Test
    fun `create relocation merge split and replacement preserve dirty IDs support lineage and revisions`() {
        val create = prepared(M3SurfaceOwnership.prepareMutation(view(), configuration(), canonical("create", M3CanonicalOperation.CREATE, 0, 0, emptyList(), target(0), target(1))))
        assertEquals(listOf(1L, 2L), create.dirtyRows.map { it.id.value })
        assertEquals(2, create.dirtySupport.size); assertTrue(create.dirtyLineage.isEmpty())
        assertEquals(1, create.targetGeometryRevision); assertEquals(0, create.targetLineageRevision)
        val createAllocation = MessageDigest.getInstance("SHA-256").digest("create".encodeToByteArray())
        create.dirtyRows.forEach { assertArrayEquals(createAllocation, it.allocatedBy) }

        val sourceRows = listOf(surface(1, 0), surface(2, 1), surface(3, 2), surface(4, 3))
        val evidence = sourceRows.associate { it.id.value to listOf(source(it.id.value, it.voxel.x)) }
        val base = view(rows = sourceRows, geometry = 7, lineage = 5, high = 5, sources = evidence.values.flatten(), supports = evidence)
        val relocate = prepared(M3SurfaceOwnership.prepareMutation(base, configuration(), canonical("relocate", M3CanonicalOperation.RELOCATION, 7, 5, listOf(M3SurfaceId(1)), target(10, M3SurfaceId(1)))))
        assertEquals(listOf(1L), relocate.dirtyRows.map { it.id.value })
        assertEquals(listOf(M3LineageEdge(M3SurfaceId(1), M3SurfaceId(1))), relocate.dirtyLineage)
        assertEquals(listOf(1L), relocate.dirtySupport.map { it.source.id.value })
        assertArrayEquals(source(1, 0).allocationFingerprint.toByteArray(), relocate.dirtyRows.single().allocatedBy)

        val merge = prepared(M3SurfaceOwnership.prepareMutation(base, configuration(), canonical("merge", M3CanonicalOperation.MERGE, 7, 5, listOf(M3SurfaceId(2), M3SurfaceId(3)), target(20))))
        assertEquals(5L, merge.dirtyRows.single().id.value)
        assertEquals(listOf(2L, 3L), merge.dirtySupport.map { it.source.id.value })
        assertEquals(listOf(2L, 3L), merge.dirtyLineage.map { it.source.value })
        assertEquals(6L, merge.targetHighWater)

        val split = prepared(M3SurfaceOwnership.prepareMutation(base, configuration(), canonical("split", M3CanonicalOperation.SPLIT, 7, 5, listOf(M3SurfaceId(4)), target(30), target(31))))
        assertEquals(listOf(5L, 6L), split.dirtyRows.map { it.id.value })
        assertEquals(listOf(5L, 6L), split.dirtyLineage.map { it.target.value })
        val replacement = prepared(M3SurfaceOwnership.prepareMutation(base, configuration(), canonical("replacement", M3CanonicalOperation.REPLACEMENT, 7, 5, listOf(M3SurfaceId(1)), target(40))))
        assertEquals(5L, replacement.dirtyRows.single().id.value)
        assertEquals(8, replacement.targetGeometryRevision); assertEquals(6, replacement.targetLineageRevision)
    }

    @Test
    fun `invalid stale capacity uint32 lineage journal and source failures retain the source cut`() {
        val base = view(rows = listOf(surface(1, 0)), high = 2, sources = listOf(source(1, 0)), supports = mapOf(1L to listOf(source(1, 0))))
        fun refusal(value: M3CanonicalMutationPreparation) = value as M3CanonicalMutationPreparation.Refused
        assertEquals(M3CanonicalMutationRefusal.REVISION_CONFLICT, refusal(M3SurfaceOwnership.prepareMutation(base, configuration(), feature("stale", 1, 0, target(0, M3SurfaceId(1))))).reason)
        assertEquals(M3CanonicalMutationRefusal.CAPACITY, refusal(M3SurfaceOwnership.prepareMutation(base, configuration(surfaceCapacity = 1), feature("capacity", 0, 0, target(2)))).reason)
        assertEquals(M3CanonicalMutationRefusal.LINEAGE_EXHAUSTED, refusal(M3SurfaceOwnership.prepareMutation(base, configuration(lineageCapacity = 1), canonical("lineage", M3CanonicalOperation.SPLIT, 0, 0, listOf(M3SurfaceId(1)), target(2), target(3)))).reason)
        assertEquals(M3CanonicalMutationRefusal.JOURNAL_EXHAUSTED, refusal(M3SurfaceOwnership.prepareMutation(base, configuration(changeJournalByteCapacity = 1), feature("journal", 0, 0, target(0, M3SurfaceId(1), confidence = 191)))).reason)
        val exhausted = view(high = 0x1_0000_0000L)
        assertEquals(M3CanonicalMutationRefusal.EXHAUSTED, refusal(M3SurfaceOwnership.prepareMutation(exhausted, configuration(), feature("exhausted", 0, 0, target(0)))).reason)
        assertEquals(M3CanonicalStateReceipt(0, 0, 2, 1), refusal(M3SurfaceOwnership.prepareMutation(base, configuration(), feature("unknown", 0, 0, target(0, M3SurfaceId(9))))).receipt)
        assertEquals(0, base.mutations)
    }

    @Test
    fun `huge split and merge cardinalities refuse before support payload reads or Cartesian allocation`() {
        val splitView = view(rows = listOf(surface(1, 0)), high = 2, liveCount = 1)
        val hugeTargets = Collections.nCopies(100_000, target(2))
        val split = M3SurfaceOwnership.prepareMutation(
            splitView,
            configuration(surfaceCapacity = 200_000, lineageCapacity = 200_000),
            M3CanonicalTransactionCommand("huge-split", M3CanonicalOperation.SPLIT, 0, 0, listOf(M3SurfaceId(1)), hugeTargets),
        ) as M3CanonicalMutationPreparation.Refused
        assertEquals(M3CanonicalMutationRefusal.JOURNAL_EXHAUSTED, split.reason)
        assertEquals(0, splitView.supportVisits)

        val mergeSources = Collections.nCopies(100_000, M3SurfaceId(1))
        val mergeView = view(liveCount = 100_000, high = 100_001)
        val merge = M3SurfaceOwnership.prepareMutation(
            mergeView,
            configuration(surfaceCapacity = 200_000, lineageCapacity = 200_000),
            M3CanonicalTransactionCommand("huge-merge", M3CanonicalOperation.MERGE, 0, 0, mergeSources, listOf(target(2))),
        ) as M3CanonicalMutationPreparation.Refused
        assertEquals(M3CanonicalMutationRefusal.JOURNAL_EXHAUSTED, merge.reason)
        assertEquals(0, mergeView.supportVisits)
    }

    @Test
    fun `prepared plans own deep immutable values and cannot diverge from frozen bytes`() {
        val targets = mutableListOf(target(0), target(1))
        val command = M3CanonicalTransactionCommand("immutable", M3CanonicalOperation.CREATE, 0, 0, emptyList(), targets)
        val plan = prepared(M3SurfaceOwnership.prepareMutation(view(), configuration(), command))
        val frozenWal = plan.walBytes.toByteArray()
        val frozenFingerprint = plan.dirtyRows.first().allocatedBy.copyOf()
        targets.clear()
        plan.dirtyRows.first().allocatedBy.fill(0x55)
        plan.walBytes.toByteArray().fill(0x66)
        assertEquals(2, plan.dirtyRows.size)
        assertArrayEquals(frozenFingerprint, plan.dirtyRows.first().allocatedBy)
        assertArrayEquals(frozenWal, plan.walBytes.toByteArray())
    }

    @Test
    fun `production paged v6 one-row plans remain bounded at small and one hundred thousand authority`() {
        val smallDirectory = Files.createTempDirectory("m3-overlay-real-small").toFile()
        val largeDirectory = Files.createTempDirectory("m3-overlay-real-100k").toFile()
        try {
            val small = productionV6(smallDirectory, "real-small", 1)
            val large = productionV6(largeDirectory, "real-large", 100_000)
            small.use { smallStore -> large.use { largeStore ->
                val one = prepared(M3SurfaceOwnership.prepareMutation(
                    smallStore, configuration(), feature("real-dirty", 0, 0, target(0, M3SurfaceId(1), confidence = 191)),
                ))
                val hundredK = prepared(M3SurfaceOwnership.prepareMutation(
                    largeStore, configuration(), feature("real-dirty", 0, 0, target(49_999, M3SurfaceId(50_000), confidence = 191)),
                ))
                assertEquals(one.work.dirtyRows, hundredK.work.dirtyRows)
                assertEquals(one.work.directLookupCount, hundredK.work.directLookupCount)
                assertEquals(one.work.authorityDirectLookups, hundredK.work.authorityDirectLookups)
                assertEquals(one.work.authorityPageReads, hundredK.work.authorityPageReads)
                assertEquals(one.work.authorityInspectedRows, hundredK.work.authorityInspectedRows)
                assertEquals(one.work.authorityBytesRead, hundredK.work.authorityBytesRead)
                assertTrue(hundredK.work.authorityInspectedRows <= 4)
                println("M3_V6_DIRTY_PROPORTIONAL small=${one.work} hundredK=${hundredK.work}")
            } }
        } finally {
            smallDirectory.deleteRecursively(); largeDirectory.deleteRecursively()
        }
    }

    @Test
    fun `one row plans have equal dirty work at one and one hundred thousand authority without scans`() {
        val small = view(rows = listOf(surface(1, 0)), high = 2, sources = listOf(source(1, 0)), supports = mapOf(1L to listOf(source(1, 0))))
        val largeRows = ArrayList<M3CompactSurface>(100_000)
        val largeSources = ArrayList<M3PagedSource>(100_000)
        repeat(100_000) { index -> largeRows += surface(index + 1L, index); largeSources += source(index + 1L, index) }
        val large = view(rows = largeRows, high = 100_001, sources = largeSources, supports = mapOf(43L to listOf(source(43, 42))))
        val one = prepared(M3SurfaceOwnership.prepareMutation(small, configuration(), feature("same-dirty", 0, 0, target(0, M3SurfaceId(1), confidence = 191))))
        val hundredK = prepared(M3SurfaceOwnership.prepareMutation(large, configuration(), feature("same-dirty", 0, 0, target(42, M3SurfaceId(43), confidence = 191))))
        assertEquals(one.work.dirtyRows, hundredK.work.dirtyRows)
        assertEquals(one.work.dirtyIdIndexRecords, hundredK.work.dirtyIdIndexRecords)
        assertEquals(one.work.dirtyVoxelIndexRecords, hundredK.work.dirtyVoxelIndexRecords)
        assertEquals(one.work.directLookupCount, hundredK.work.directLookupCount)
        assertEquals(0, one.work.sourceBytesRead); assertEquals(0, hundredK.work.sourceBytesRead)
        assertEquals(one.work.stagingBytes, hundredK.work.stagingBytes)
        assertEquals(0, small.mutations); assertEquals(0, large.mutations)
        assertArrayEquals(one.currentBytes.toByteArray().copyOfRange(0, 8), hundredK.currentBytes.toByteArray().copyOfRange(0, 8))
        val planBytes = GraphLayout.parseInstance(hundredK).totalSize()
        println(
            "M3_CANONICAL_MUTATION_OVERLAY_DIRTY_WORK " +
                "smallRows=${one.work.dirtyRows} largeRows=${hundredK.work.dirtyRows} " +
                "smallLookups=${one.work.directLookupCount} largeLookups=${hundredK.work.directLookupCount} " +
                "stagingBytes=${hundredK.work.stagingBytes} planBytes=$planBytes reserve=${M3CompactCanonicalStore.JOURNAL_RESERVE_BYTES}",
        )
        assertTrue(planBytes < 8_192L)
    }

    private fun configuration(surfaceCapacity: Int = 100_000, lineageCapacity: Int = 200_000, changeJournalByteCapacity: Int = 1_048_576) =
        M3SurfaceOwnershipConfiguration(surfaceCapacity = surfaceCapacity, lineageCapacity = lineageCapacity, changeJournalByteCapacity = changeJournalByteCapacity)
    private fun feature(id: String, geometry: Long, lineage: Long, target: M3CanonicalTarget) = M3FeatureMutationCommand(id, geometry, lineage, target)
    private fun canonical(id: String, kind: M3CanonicalOperation, geometry: Long, lineage: Long, source: List<M3SurfaceId>, vararg targets: M3CanonicalTarget) = M3CanonicalTransactionCommand(id, kind, geometry, lineage, source, targets.toList())
    private fun target(x: Int, id: M3SurfaceId? = null, confidence: Int = 192, normalX: Int = 0, normalY: Int = 0) = M3CanonicalTarget(id, M3Voxel(x, 0, 0), normalX, normalY, confidence)
    private fun surface(id: Long, x: Int, confidence: Int = 192) = M3CompactSurface(M3SurfaceId(id), M3Voxel(x, 0, 0), 0, confidence)
    private fun source(id: Long, x: Int, confidence: Int = 192, fingerprint: ByteArray = ByteArray(32) { id.toByte() }) = M3PagedSource(M3SurfaceId(id), M3Voxel(x, 0, 0), 0, confidence, M3CanonicalReceiptBytes(fingerprint))
    private fun prepared(value: M3CanonicalMutationPreparation) = (value as M3CanonicalMutationPreparation.Prepared).mutation

    private fun view(
        rows: List<M3CompactSurface> = emptyList(), geometry: Long = 0, lineage: Long = 0, high: Long = 1,
        sources: List<M3PagedSource> = emptyList(), supports: Map<Long, List<M3PagedSource>> = emptyMap(), liveCount: Int = rows.size,
    ) = TestView(rows, geometry, lineage, high, sources, supports, liveCount)

    private fun productionV6(directory: java.io.File, identity: String, count: Int): M3CompactCanonicalStore {
        val group = M3SurfaceGroup(identity)
        val config = configuration(changeJournalByteCapacity = 64 * 1024 * 1024)
        val legacy = (M3SurfaceOwnership.open(group, directory, config) as M3SurfaceOwnershipOpenResult.Opened).ownership
        val result = legacy.apply(M3SurfaceOwnershipCommand(
            "seed-$identity",
            List(count) { index -> M3SurfaceCandidate(voxel = M3Voxel(index, 0, 0), normalOctX = 0, normalOctY = 0, normalConfidence = 192) },
        ))
        assertTrue(result.toString(), result is M3SurfaceOwnershipResult.Accepted)
        legacy.close()
        val budget = object : M3CanonicalStorageBudget {
            override fun reserve(bytes: Long): Any = bytes
            override fun commit(token: Any, actualBytes: Long) = Unit
            override fun release(token: Any) = Unit
            override fun allocationUnitBytes(path: java.io.File) = 4_096L
        }
        val migration = M3CompactCanonicalStore.prepareV6SiblingMigration(group, directory, budget, config)
        assertTrue(migration.toString(), migration is M3CompactCanonicalMigrationResult.Prepared)
        return (M3CompactCanonicalStore.openV6(group, directory, budget, config) as M3CompactCanonicalOpenResult.Opened).store
    }

    private class TestView(
        rows: List<M3CompactSurface>, geometry: Long, lineage: Long, high: Long,
        sources: List<M3PagedSource>, private val supports: Map<Long, List<M3PagedSource>>, liveCount: Int,
    ) : M3CanonicalStateView {
        private val ids = rows.associateBy { it.id }; private val voxels = rows.associateBy { it.voxel }
        private val sourceIds = sources.associateBy { it.id }
        var mutations = 0
        var supportVisits = 0
        override val cut = M3CompactCanonicalCut(M3SurfaceGroup("overlay"), M3CompactCanonicalStore.PROFILE, geometry, lineage, high, liveCount, sources.size, supports.values.sumOf { it.size }, 0, null, M3CanonicalReceiptBytes(ByteArray(32) { 7 }), M3CanonicalReceiptBytes(ByteArray(32) { 8 }))
        override fun findById(id: M3SurfaceId) = ids[id]
        override fun findByVoxel(voxel: M3Voxel) = voxels[voxel]
        override fun readPage(region: M3StorageRegion, page: Int, cursor: Int, limit: Int) = M3CompactPage(emptyList(), null, 0)
        override fun readSourceById(id: M3SurfaceId) = M3CanonicalPageRead.Complete(sourceIds[id], 0, 0)
        override fun visitSourceSupport(target: M3SurfaceId, cursor: M3SourceSupportCursor?, sink: (M3PagedSupport) -> Boolean): M3SourceSupportRead {
            supportVisits++
            if (cursor != null) return M3SourceSupportRead.Complete(0, null, 0, 0)
            var delivered = 0; supports[target.value].orEmpty().forEach { if (sink(M3PagedSupport(target, it))) delivered++ }
            return M3SourceSupportRead.Complete(delivered, null, 0, 0)
        }
        override fun retainedMemoryReceipt() = error("not used")
        override fun allocatedStorageReceipt() = error("not used")
        override fun close() { mutations++ }
    }
}
