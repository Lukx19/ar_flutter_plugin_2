package com.uhg0.ar_flutter_plugin_2.visibilitygrid

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.openjdk.jol.info.GraphLayout
import java.nio.file.Files
import java.security.MessageDigest
import java.util.Collections
import java.io.ByteArrayOutputStream
import java.io.OutputStream

class CanonicalMutationOverlayTest {
    @Test
    fun `C12 confidence bands allocation provenance and normal orientation define material refinement`() {
        val provenance = ByteArray(32) { (it + 7).toByte() }
        val reliable = view(
            rows = listOf(surface(1, 0, confidence = 191)), high = 2,
            sources = listOf(source(1, 0, confidence = 191, fingerprint = provenance)),
        )
        val boundary = prepared(SurfaceOwnership.prepareMutation(
            reliable, configuration(), feature("band-boundary", 0, 0, target(0, SurfaceId(1), confidence = 192)),
        ))
        assertEquals(192, rows(boundary).single().normalConfidence)
        assertArrayEquals(provenance, rows(boundary).single().allocationFingerprint.toByteArray())

        val strong = view(
            rows = listOf(surface(1, 0, confidence = 192)), high = 2,
            sources = listOf(source(1, 0, confidence = 192, fingerprint = provenance)),
        )
        assertTrue(SurfaceOwnership.prepareMutation(
            strong, configuration(), feature("same-strong-band", 0, 0, target(0, SurfaceId(1), confidence = 193)),
        ) is CanonicalMutationPreparation.NoOp)
        val direction = prepared(SurfaceOwnership.prepareMutation(
            strong, configuration(), feature("normal-change", 0, 0, target(0, SurfaceId(1), confidence = 193, normalX = 1)),
        ))
        assertTrue(rows(direction).single().packedNormal != 0)
        assertArrayEquals(provenance, rows(direction).single().allocationFingerprint.toByteArray())

        val add = prepared(SurfaceOwnership.prepareMutation(
            view(), configuration(), feature("new-allocation", 0, 0, target(0)),
        ))
        assertArrayEquals(
            MessageDigest.getInstance("SHA-256").digest("new-allocation".encodeToByteArray()),
            rows(add).single().allocationFingerprint.toByteArray(),
        )
    }

    @Test
    fun `feature add refine and reobservation prepare stable dirty plans without mutating the view`() {
        val empty = view()
        val add = prepared(SurfaceOwnership.prepareMutation(empty, configuration(), feature("add", 0, 0, target(0))))
        assertEquals(PreparedMutationKind.FEATURE_ADD, add.kind)
        assertEquals(listOf(1L), rows(add).map { it.id.value })
        assertEquals(1, add.targetGeometryRevision); assertEquals(0, add.targetLineageRevision)
        assertEquals(2L, add.targetHighWater); assertEquals(1, support(add).size)
        assertEquals(empty.cut, add.sourceCut)
        assertEquals(32, add.commandHash.size); assertEquals(32, add.commandFingerprint.size)
        assertTrue(add.work.walBytes > 0 && add.work.currentBytes > 0)
        assertEquals(add.work.retainedPlanBytes + add.work.writerScratchBytes, add.work.stagingBytes)
        assertEquals(0, empty.mutations)

        val active = view(rows = listOf(surface(1, 0)), geometry = 1, high = 2, sources = listOf(source(1, 0)), supports = mapOf(1L to listOf(source(1, 0))))
        val refine = prepared(SurfaceOwnership.prepareMutation(active, configuration(), feature("refine", 1, 0, target(0, SurfaceId(1), confidence = 191))))
        assertEquals(PreparedMutationKind.FEATURE_REFINE, refine.kind)
        assertEquals(1L, rows(refine).single().id.value)
        assertEquals(2, refine.targetGeometryRevision); assertEquals(0, refine.targetLineageRevision)
        assertTrue(support(refine).isEmpty()); assertTrue(lineage(refine).isEmpty())
        assertArrayEquals(source(1, 0).allocationFingerprint.toByteArray(), rows(refine).single().allocationFingerprint.toByteArray())

        val noOp = SurfaceOwnership.prepareMutation(active, configuration(), feature("same", 1, 0, target(0, SurfaceId(1))))
        assertEquals(CanonicalMutationPreparation.NoOp(CanonicalStateReceipt(1, 0, 2, 1)), noOp)
        assertEquals(0, active.mutations)
    }

    @Test
    fun `create relocation merge split and replacement preserve dirty IDs support lineage and revisions`() {
        val create = prepared(SurfaceOwnership.prepareMutation(view(), configuration(), canonical("create", CanonicalOperation.CREATE, 0, 0, emptyList(), target(0), target(1))))
        assertEquals(listOf(1L, 2L), rows(create).map { it.id.value })
        assertEquals(2, support(create).size); assertTrue(lineage(create).isEmpty())
        assertEquals(1, create.targetGeometryRevision); assertEquals(0, create.targetLineageRevision)
        val createAllocation = MessageDigest.getInstance("SHA-256").digest("create".encodeToByteArray())
        rows(create).forEach { assertArrayEquals(createAllocation, it.allocationFingerprint.toByteArray()) }

        val sourceRows = listOf(surface(1, 0), surface(2, 1), surface(3, 2), surface(4, 3))
        val evidence = sourceRows.associate { it.id.value to listOf(source(it.id.value, it.voxel.x)) }
        val base = view(rows = sourceRows, geometry = 7, lineage = 5, high = 5, sources = evidence.values.flatten(), supports = evidence)
        val relocate = prepared(SurfaceOwnership.prepareMutation(base, configuration(), canonical("relocate", CanonicalOperation.RELOCATION, 7, 5, listOf(SurfaceId(1)), target(10, SurfaceId(1)))))
        assertEquals(listOf(1L), rows(relocate).map { it.id.value })
        assertEquals(listOf(LineageEdge(SurfaceId(1), SurfaceId(1))), lineage(relocate))
        assertEquals(listOf(1L), support(relocate).map { it.source.id.value })
        assertArrayEquals(source(1, 0).allocationFingerprint.toByteArray(), rows(relocate).single().allocationFingerprint.toByteArray())

        val merge = prepared(SurfaceOwnership.prepareMutation(base, configuration(), canonical("merge", CanonicalOperation.MERGE, 7, 5, listOf(SurfaceId(2), SurfaceId(3)), target(20))))
        assertEquals(5L, rows(merge).single().id.value)
        assertEquals(listOf(2L, 3L), support(merge).map { it.source.id.value })
        assertEquals(listOf(2L, 3L), lineage(merge).map { it.source.value })
        assertEquals(6L, merge.targetHighWater)

        val split = prepared(SurfaceOwnership.prepareMutation(base, configuration(), canonical("split", CanonicalOperation.SPLIT, 7, 5, listOf(SurfaceId(4)), target(30), target(31))))
        assertEquals(listOf(5L, 6L), rows(split).map { it.id.value })
        assertEquals(listOf(5L, 6L), lineage(split).map { it.target.value })
        val replacement = prepared(SurfaceOwnership.prepareMutation(base, configuration(), canonical("replacement", CanonicalOperation.REPLACEMENT, 7, 5, listOf(SurfaceId(1)), target(40))))
        assertEquals(5L, rows(replacement).single().id.value)
        assertEquals(8, replacement.targetGeometryRevision); assertEquals(6, replacement.targetLineageRevision)
    }

    @Test
    fun `invalid stale capacity uint32 lineage journal and source failures retain the source cut`() {
        val base = view(rows = listOf(surface(1, 0)), high = 2, sources = listOf(source(1, 0)), supports = mapOf(1L to listOf(source(1, 0))))
        fun refusal(value: CanonicalMutationPreparation) = value as CanonicalMutationPreparation.Refused
        assertEquals(CanonicalMutationRefusal.REVISION_CONFLICT, refusal(SurfaceOwnership.prepareMutation(base, configuration(), feature("stale", 1, 0, target(0, SurfaceId(1))))).reason)
        assertEquals(CanonicalMutationRefusal.CAPACITY, refusal(SurfaceOwnership.prepareMutation(base, configuration(surfaceCapacity = 1), feature("capacity", 0, 0, target(2)))).reason)
        assertEquals(CanonicalMutationRefusal.LINEAGE_EXHAUSTED, refusal(SurfaceOwnership.prepareMutation(base, configuration(lineageCapacity = 1), canonical("lineage", CanonicalOperation.SPLIT, 0, 0, listOf(SurfaceId(1)), target(2), target(3)))).reason)
        assertEquals(CanonicalMutationRefusal.JOURNAL_EXHAUSTED, refusal(SurfaceOwnership.prepareMutation(base, configuration(changeJournalByteCapacity = 1), feature("journal", 0, 0, target(0, SurfaceId(1), confidence = 191)))).reason)
        val exhausted = view(high = 0x1_0000_0000L)
        assertEquals(CanonicalMutationRefusal.EXHAUSTED, refusal(SurfaceOwnership.prepareMutation(exhausted, configuration(), feature("exhausted", 0, 0, target(0)))).reason)
        assertEquals(CanonicalStateReceipt(0, 0, 2, 1), refusal(SurfaceOwnership.prepareMutation(base, configuration(), feature("unknown", 0, 0, target(0, SurfaceId(9))))).receipt)
        assertEquals(0, base.mutations)
    }

    @Test
    fun `huge split and merge cardinalities refuse before support payload reads or Cartesian allocation`() {
        val splitView = view(rows = listOf(surface(1, 0)), high = 2, liveCount = 1)
        val hugeTargets = Collections.nCopies(100_000, target(2))
        val split = SurfaceOwnership.prepareMutation(
            splitView,
            configuration(surfaceCapacity = 200_000, lineageCapacity = 200_000),
            CanonicalTransactionCommand("huge-split", CanonicalOperation.SPLIT, 0, 0, listOf(SurfaceId(1)), hugeTargets),
        ) as CanonicalMutationPreparation.Refused
        assertEquals(CanonicalMutationRefusal.JOURNAL_EXHAUSTED, split.reason)
        assertEquals(0, splitView.supportVisits)

        val mergeSources = Collections.nCopies(100_000, SurfaceId(1))
        val mergeView = view(liveCount = 100_000, high = 100_001)
        val merge = SurfaceOwnership.prepareMutation(
            mergeView,
            configuration(surfaceCapacity = 200_000, lineageCapacity = 200_000),
            CanonicalTransactionCommand("huge-merge", CanonicalOperation.MERGE, 0, 0, mergeSources, listOf(target(2))),
        ) as CanonicalMutationPreparation.Refused
        assertEquals(CanonicalMutationRefusal.JOURNAL_EXHAUSTED, merge.reason)
        assertEquals(0, mergeView.supportVisits)
    }

    @Test
    fun `zero-support construction peak refuses before copying or materializing command graphs`() {
        fun zeroSupportPeak(rows: Int, removed: Int) =
            MutableCanonicalOverlay.PLAN_FIXED_OWNER_BYTES + MutableCanonicalOverlay.WRITER_SCRATCH_BYTES +
                MutableCanonicalOverlay.PLANNING_PAGE_SCRATCH_BYTES +
                rows * MutableCanonicalOverlay.ROW_CONSTRUCTION_BYTES_PER_RECORD +
                removed * MutableCanonicalOverlay.REMOVED_CONSTRUCTION_BYTES_PER_RECORD + 4L
        fun assertScalarOnly(refused: CanonicalMutationPreparation.Refused, authorityReads: Int) {
            assertEquals(CanonicalMutationRefusal.JOURNAL_EXHAUSTED, refused.reason)
            assertEquals(CanonicalMutationPreflightWork(), refused.preflightWork)
            assertEquals(0, authorityReads)
        }

        val createView = ScalarPreflightView(0)
        assertTrue(zeroSupportPeak(rows = 5_000, removed = 0) > CompactCanonicalStore.JOURNAL_RESERVE_BYTES)
        val create = SurfaceOwnership.prepareMutation(
            createView,
            configuration(),
            canonical(
                "zero-support-large-create",
                CanonicalOperation.CREATE,
                0,
                0,
                emptyList(),
                *Array(5_000) { target(it) },
            ),
        ) as CanonicalMutationPreparation.Refused
        assertScalarOnly(create, createView.authorityReads)

        val removalCount = 25_000
        val removalView = ScalarPreflightView(removalCount)
        assertTrue(zeroSupportPeak(rows = 1, removed = removalCount) > CompactCanonicalStore.JOURNAL_RESERVE_BYTES)
        val removal = SurfaceOwnership.prepareMutation(
            removalView,
            configuration(surfaceCapacity = 100_000, lineageCapacity = 100_000),
            CanonicalTransactionCommand(
                "zero-support-large-removal",
                CanonicalOperation.REPLACEMENT,
                0,
                0,
                List(removalCount) { SurfaceId(it + 1L) },
                listOf(target(removalCount + 1)),
            ),
        ) as CanonicalMutationPreparation.Refused
        assertScalarOnly(removal, removalView.authorityReads)
    }

    @Test
    fun `journal-derived support cap accepts its maximum and stops high support at the next record`() {
        val probe = prepared(SurfaceOwnership.prepareMutation(
            view(
                rows = listOf(surface(1, 0)), high = 2,
                sources = listOf(source(1, 0)), supports = mapOf(1L to listOf(source(1, 0))),
            ),
            configuration(),
            canonical("support-cap", CanonicalOperation.RELOCATION, 0, 0, listOf(SurfaceId(1)), target(10, SurfaceId(1))),
        ))
        val limit = probe.work.supportRecordLimit
        assertEquals(8_192, limit)

        val maximumView = HighSupportView(limit)
        val maximum = prepared(SurfaceOwnership.prepareMutation(
            maximumView, configuration(),
            canonical("support-cap", CanonicalOperation.RELOCATION, 0, 0, listOf(SurfaceId(1)), target(10, SurfaceId(1))),
        ))
        assertEquals(limit, maximum.work.dirtySupportRecords)
        assertEquals(limit, maximumView.attemptedRecords)
        assertEquals(EXPECTED_MAX_ENCODED_BYTES, maximum.work.walBytes)
        assertEquals(EXPECTED_MAX_RETAINED_BYTES, maximum.work.retainedPlanBytes)
        assertEquals(EXPECTED_WRITER_SCRATCH_BYTES, maximum.work.writerScratchBytes)
        assertEquals(EXPECTED_MAX_CONSTRUCTION_PEAK_BYTES, maximum.work.constructionPeakBytes)
        assertTrue(maximum.work.constructionPeakBytes <= CompactCanonicalStore.JOURNAL_RESERVE_BYTES)
        assertEquals(
            maximum.work.fixedOwnerBytes + maximum.work.rowArrayBytes + maximum.work.removedArrayBytes +
                maximum.work.supportArrayBytes,
            maximum.work.retainedPlanBytes,
        )
        assertEquals(
            maximum.work.fixedOwnerBytes + maximum.work.writerScratchBytes + maximum.work.planningPageScratchBytes +
                maximum.work.rowConstructionBytes + maximum.work.removedConstructionBytes +
                maximum.work.supportConstructionArrayPeakBytes + maximum.work.supportHashBytes,
            maximum.work.constructionPeakBytes,
        )
        val planGraph = GraphLayout.parseInstance(maximum).totalSize()
        // JOL is diagnostic across JVM layouts; modeled/encoded receipts above are normative.
        assertTrue(planGraph + maximum.work.writerScratchBytes <= CompactCanonicalStore.JOURNAL_RESERVE_BYTES)
        val sink = CountingOutputStream()
        maximum.writeWalTo(sink)
        assertEquals(EXPECTED_MAX_ENCODED_BYTES.toLong(), sink.bytes)
        val currentSink = CountingOutputStream()
        maximum.writeCurrentTo(currentSink)
        assertEquals(EXPECTED_MAX_ENCODED_BYTES.toLong(), currentSink.bytes)
        println(
            "CANONICAL_SURFACE_MAX_PREPARED_PLAN limit=$limit encoded=${maximum.work.walBytes} planGraph=$planGraph " +
                "rowArrays=${maximum.work.rowArrayBytes} removedArrays=${maximum.work.removedArrayBytes} " +
                "supportArrays=${maximum.work.supportArrayBytes} constructionHash=${maximum.work.supportHashBytes} " +
                "supportConstructionArrays=${maximum.work.supportConstructionArrayPeakBytes} " +
                "rowConstruction=${maximum.work.rowConstructionBytes} removedConstruction=${maximum.work.removedConstructionBytes} " +
                "fixedOwners=${maximum.work.fixedOwnerBytes} planningPageScratch=${maximum.work.planningPageScratchBytes} " +
                "writerScratch=${maximum.work.writerScratchBytes} retained=${maximum.work.retainedPlanBytes} " +
                "constructionPeak=${maximum.work.constructionPeakBytes} reserve=${CompactCanonicalStore.JOURNAL_RESERVE_BYTES}",
        )

        listOf(limit + 1, 300_000).forEach { total ->
            val oversized = HighSupportView(total)
            val refused = SurfaceOwnership.prepareMutation(
                oversized, configuration(),
                canonical("support-cap", CanonicalOperation.RELOCATION, 0, 0, listOf(SurfaceId(1)), target(10, SurfaceId(1))),
            ) as CanonicalMutationPreparation.Refused
            assertEquals(CanonicalMutationRefusal.JOURNAL_EXHAUSTED, refused.reason)
            assertEquals(limit + 1, oversized.attemptedRecords)
            assertEquals((limit + 256) / 256, oversized.pageReads)
        }
    }

    @Test
    fun `prepared plans own deep immutable values and cannot diverge from frozen bytes`() {
        val targets = mutableListOf(target(0), target(1))
        val command = CanonicalTransactionCommand("immutable", CanonicalOperation.CREATE, 0, 0, emptyList(), targets)
        val plan = prepared(SurfaceOwnership.prepareMutation(view(), configuration(), command))
        val frozenWal = wal(plan)
        val frozenFingerprint = rows(plan).first().allocationFingerprint.toByteArray()
        targets.clear()
        rows(plan).first().allocationFingerprint.toByteArray().fill(0x55)
        wal(plan).fill(0x66)
        assertEquals(2, plan.dirtyRowCount)
        assertArrayEquals(frozenFingerprint, rows(plan).first().allocationFingerprint.toByteArray())
        assertArrayEquals(frozenWal, wal(plan))
        val methods = PreparedCanonicalMutation::class.java.declaredMethods.map { it.name }.toSet()
        assertTrue(methods.none { it in setOf("getDirtyRows", "getDirtySupport", "getDirtySources", "getDirtyLineage", "getWalBytes", "getCurrentBytes") })
        assertTrue(PreparedCanonicalMutation::class.java.declaredFields.none { Collection::class.java.isAssignableFrom(it.type) || it.type == ByteArray::class.java })
    }

    @Test
    fun `production paged v6 one-row plans remain bounded at small and one hundred thousand authority`() {
        val smallDirectory = Files.createTempDirectory("canonical-surface-overlay-real-small").toFile()
        val largeDirectory = Files.createTempDirectory("canonical-surface-overlay-real-100k").toFile()
        try {
            val small = productionV6(smallDirectory, "real-small", 1)
            val large = productionV6(largeDirectory, "real-large", 100_000)
            small.use { smallStore -> large.use { largeStore ->
                val one = prepared(SurfaceOwnership.prepareMutation(
                    smallStore, configuration(), feature("real-dirty", 0, 0, target(0, SurfaceId(1), confidence = 191)),
                ))
                val hundredK = prepared(SurfaceOwnership.prepareMutation(
                    largeStore, configuration(), feature("real-dirty", 0, 0, target(49_999, SurfaceId(50_000), confidence = 191)),
                ))
                assertEquals(one.work.dirtyRows, hundredK.work.dirtyRows)
                assertEquals(one.work.directLookupCount, hundredK.work.directLookupCount)
                assertEquals(one.work.authorityDirectLookups, hundredK.work.authorityDirectLookups)
                assertEquals(one.work.authorityPageReads, hundredK.work.authorityPageReads)
                assertEquals(one.work.authorityInspectedRows, hundredK.work.authorityInspectedRows)
                assertEquals(one.work.authorityBytesRead, hundredK.work.authorityBytesRead)
                assertEquals(1, hundredK.work.dirtyRows)
                assertEquals(1, hundredK.work.dirtyIdIndexRecords)
                assertEquals(1, hundredK.work.dirtyVoxelIndexRecords)
                assertEquals(2, hundredK.work.directLookupCount)
                assertEquals(1, hundredK.work.sourcePageFaults)
                assertEquals(2, hundredK.work.authorityDirectLookups)
                assertEquals(1, hundredK.work.authorityPageReads)
                assertEquals(2, hundredK.work.authorityInspectedRows)
                assertEquals(EXPECTED_ONE_ROW_ENCODED_BYTES, hundredK.work.walBytes)
                assertEquals(EXPECTED_ONE_ROW_ENCODED_BYTES, hundredK.work.currentBytes)
                assertEquals(EXPECTED_ONE_ROW_STAGING_BYTES, hundredK.work.stagingBytes)
                assertTrue(hundredK.work.authorityInspectedRows <= 4)
                println("CANONICAL_SURFACE_V6_DIRTY_PROPORTIONAL small=${one.work} hundredK=${hundredK.work}")
            } }
        } finally {
            smallDirectory.deleteRecursively(); largeDirectory.deleteRecursively()
        }
    }

    @Test
    fun `one row plans have equal dirty work at one and one hundred thousand authority without scans`() {
        val small = view(rows = listOf(surface(1, 0)), high = 2, sources = listOf(source(1, 0)), supports = mapOf(1L to listOf(source(1, 0))))
        val largeRows = ArrayList<CompactSurface>(100_000)
        val largeSources = ArrayList<PagedSource>(100_000)
        repeat(100_000) { index -> largeRows += surface(index + 1L, index); largeSources += source(index + 1L, index) }
        val large = view(rows = largeRows, high = 100_001, sources = largeSources, supports = mapOf(43L to listOf(source(43, 42))))
        val one = prepared(SurfaceOwnership.prepareMutation(small, configuration(), feature("same-dirty", 0, 0, target(0, SurfaceId(1), confidence = 191))))
        val hundredK = prepared(SurfaceOwnership.prepareMutation(large, configuration(), feature("same-dirty", 0, 0, target(42, SurfaceId(43), confidence = 191))))
        assertEquals(one.work.dirtyRows, hundredK.work.dirtyRows)
        assertEquals(one.work.dirtyIdIndexRecords, hundredK.work.dirtyIdIndexRecords)
        assertEquals(one.work.dirtyVoxelIndexRecords, hundredK.work.dirtyVoxelIndexRecords)
        assertEquals(one.work.directLookupCount, hundredK.work.directLookupCount)
        assertEquals(0, one.work.sourceBytesRead); assertEquals(0, hundredK.work.sourceBytesRead)
        assertEquals(one.work.stagingBytes, hundredK.work.stagingBytes)
        assertEquals(0, small.mutations); assertEquals(0, large.mutations)
        assertArrayEquals(current(one).copyOfRange(0, 8), current(hundredK).copyOfRange(0, 8))
        assertEquals(EXPECTED_ONE_ROW_ENCODED_BYTES, wal(hundredK).size)
        assertEquals(EXPECTED_ONE_ROW_ENCODED_BYTES, current(hundredK).size)
        assertEquals(EXPECTED_ONE_ROW_STAGING_BYTES, hundredK.work.stagingBytes)
        val planBytes = GraphLayout.parseInstance(hundredK).totalSize()
        println(
            "CANONICAL_SURFACE_CANONICAL_MUTATION_OVERLAY_DIRTY_WORK " +
                "smallRows=${one.work.dirtyRows} largeRows=${hundredK.work.dirtyRows} " +
                "smallLookups=${one.work.directLookupCount} largeLookups=${hundredK.work.directLookupCount} " +
                "stagingBytes=${hundredK.work.stagingBytes} planBytes=$planBytes reserve=${CompactCanonicalStore.JOURNAL_RESERVE_BYTES}",
        )
        assertTrue(planBytes < 8_192L)
    }

    private fun configuration(surfaceCapacity: Int = 100_000, lineageCapacity: Int = 200_000, changeJournalByteCapacity: Int = 1_048_576) =
        SurfaceOwnershipConfiguration(surfaceCapacity = surfaceCapacity, lineageCapacity = lineageCapacity, changeJournalByteCapacity = changeJournalByteCapacity)
    private fun feature(id: String, geometry: Long, lineage: Long, target: CanonicalTarget) = FeatureMutationCommand(id, geometry, lineage, target)
    private fun canonical(id: String, kind: CanonicalOperation, geometry: Long, lineage: Long, source: List<SurfaceId>, vararg targets: CanonicalTarget) = CanonicalTransactionCommand(id, kind, geometry, lineage, source, targets.toList())
    private fun target(x: Int, id: SurfaceId? = null, confidence: Int = 192, normalX: Int = 0, normalY: Int = 0) = CanonicalTarget(id, Voxel(x, 0, 0), normalX, normalY, confidence)
    private fun surface(id: Long, x: Int, confidence: Int = 192) = CompactSurface(SurfaceId(id), Voxel(x, 0, 0), 0, confidence)
    private fun source(id: Long, x: Int, confidence: Int = 192, fingerprint: ByteArray = ByteArray(32) { id.toByte() }) = PagedSource(SurfaceId(id), Voxel(x, 0, 0), 0, confidence, CanonicalReceiptBytes(fingerprint))
    private fun prepared(value: CanonicalMutationPreparation) = (value as CanonicalMutationPreparation.Prepared).mutation

    private companion object {
        const val EXPECTED_ONE_ROW_ENCODED_BYTES = 244
        const val EXPECTED_ONE_ROW_STAGING_BYTES = 73_788L
        const val EXPECTED_MAX_ENCODED_BYTES = 557_325
        const val EXPECTED_MAX_RETAINED_BYTES = 499_780L
        const val EXPECTED_WRITER_SCRATCH_BYTES = 65_536L
        const val EXPECTED_MAX_CONSTRUCTION_PEAK_BYTES = 942_376L
    }
    private fun rows(plan: PreparedCanonicalMutation) = mutableListOf<PreparedRow>().also { values -> plan.visitDirtyRows { values += it; true } }
    private fun support(plan: PreparedCanonicalMutation) = mutableListOf<PreparedSupport>().also { values -> plan.visitDirtySupport { values += it; true } }
    private fun lineage(plan: PreparedCanonicalMutation) = mutableListOf<LineageEdge>().also { values -> plan.visitDirtyLineage { values += it; true } }
    private fun wal(plan: PreparedCanonicalMutation) = ByteArrayOutputStream().also(plan::writeWalTo).toByteArray()
    private fun current(plan: PreparedCanonicalMutation) = ByteArrayOutputStream().also(plan::writeCurrentTo).toByteArray()

    private fun view(
        rows: List<CompactSurface> = emptyList(), geometry: Long = 0, lineage: Long = 0, high: Long = 1,
        sources: List<PagedSource> = emptyList(), supports: Map<Long, List<PagedSource>> = emptyMap(), liveCount: Int = rows.size,
    ) = TestView(rows, geometry, lineage, high, sources, supports, liveCount)

    private fun productionV6(directory: java.io.File, identity: String, count: Int): CompactCanonicalStore {
        val group = SurfaceGroup(identity)
        val config = configuration(changeJournalByteCapacity = 64 * 1024 * 1024)
        val legacy = (SurfaceOwnership.open(group, directory, config) as SurfaceOwnershipOpenResult.Opened).ownership
        val result = legacy.apply(SurfaceOwnershipCommand(
            "seed-$identity",
            List(count) { index -> SurfaceCandidate(voxel = Voxel(index, 0, 0), normalOctX = 0, normalOctY = 0, normalConfidence = 192) },
        ))
        assertTrue(result.toString(), result is SurfaceOwnershipResult.Accepted)
        legacy.close()
        val budget = object : CanonicalStorageBudget {
            override fun reserve(bytes: Long): Any = bytes
            override fun reserveCandidateExclusive(staging: java.io.File, target: java.io.File, fileBytes: Map<String, Long>, maximumPhysicalBytes: Long) =
                CanonicalCandidateReservation.QuotaRefused
            override fun commit(token: Any, actualBytes: Long) = Unit
            override fun release(token: Any) = Unit
            override fun allocationUnitBytes(path: java.io.File) = 4_096L
        }
        val migration = CompactCanonicalStore.prepareV6SiblingMigration(group, directory, budget, config)
        assertTrue(migration.toString(), migration is CompactCanonicalMigrationResult.Prepared)
        return (CompactCanonicalStore.openV6(group, directory, budget, config) as CompactCanonicalOpenResult.Opened).store
    }

    private class TestView(
        rows: List<CompactSurface>, geometry: Long, lineage: Long, high: Long,
        sources: List<PagedSource>, private val supports: Map<Long, List<PagedSource>>, liveCount: Int,
    ) : CanonicalStateView {
        private val ids = rows.associateBy { it.id }; private val voxels = rows.associateBy { it.voxel }
        private val sourceIds = sources.associateBy { it.id }
        var mutations = 0
        var supportVisits = 0
        override val cut = CompactCanonicalCut(SurfaceGroup("overlay"), CompactCanonicalStore.PROFILE, geometry, lineage, high, liveCount, sources.size, supports.values.sumOf { it.size }, 0, null, CanonicalReceiptBytes(ByteArray(32) { 7 }), CanonicalReceiptBytes(ByteArray(32) { 8 }))
        override fun findById(id: SurfaceId) = ids[id]
        override fun findByVoxel(voxel: Voxel) = voxels[voxel]
        override fun readPage(region: StorageRegion, page: Int, cursor: Int, limit: Int) = CompactPage(emptyList(), null, 0)
        override fun readSourceById(id: SurfaceId) = CanonicalPageRead.Complete(sourceIds[id], 0, 0)
        override fun visitSourceSupport(target: SurfaceId, cursor: SourceSupportCursor?, sink: (PagedSupport) -> Boolean): SourceSupportRead {
            supportVisits++
            if (cursor != null) return SourceSupportRead.Complete(0, null, 0, 0)
            var delivered = 0; supports[target.value].orEmpty().forEach { if (sink(PagedSupport(target, it))) delivered++ }
            return SourceSupportRead.Complete(delivered, null, 0, 0)
        }
        override fun retainedMemoryReceipt() = error("not used")
        override fun allocatedStorageReceipt() = error("not used")
        override fun close() { mutations++ }
    }

    private inner class HighSupportView(private val total: Int) : CanonicalStateView {
        private val root = CanonicalReceiptBytes(ByteArray(32) { 9 })
        var attemptedRecords = 0
        var pageReads = 0
        override val cut = CompactCanonicalCut(
            SurfaceGroup("high-support"), CompactCanonicalStore.PROFILE, 0, 0, 2, 1,
            total, total, 0, null, root, CanonicalReceiptBytes(ByteArray(32) { 10 }),
        )
        override fun findById(id: SurfaceId) = if (id.value == 1L) surface(1, 0) else null
        override fun findByVoxel(voxel: Voxel) = if (voxel == Voxel(0, 0, 0)) surface(1, 0) else null
        override fun readPage(region: StorageRegion, page: Int, cursor: Int, limit: Int) = CompactPage(emptyList(), null, 0)
        override fun readSourceById(id: SurfaceId) = CanonicalPageRead.Complete(
            if (id.value in 1..total.toLong()) source(id.value, (id.value - 1).toInt()) else null, 1, 16_384,
        )
        override fun visitSourceSupport(target: SurfaceId, cursor: SourceSupportCursor?, sink: (PagedSupport) -> Boolean): SourceSupportRead {
            val start = cursor?.ordinal ?: 0
            pageReads++
            var delivered = 0
            var index = start
            val end = minOf(total, start + 256)
            while (index < end) {
                attemptedRecords++
                if (!sink(PagedSupport(target, source(index + 1L, index)))) break
                delivered++; index++
            }
            val next = if (index < total) SourceSupportCursor(root, target, index, 0) else null
            return SourceSupportRead.Complete(delivered, next, 1, 16_384)
        }
        override fun retainedMemoryReceipt() = error("not used")
        override fun allocatedStorageReceipt() = error("not used")
        override fun close() = Unit
    }

    private inner class ScalarPreflightView(private val live: Int) : CanonicalStateView {
        private val root = CanonicalReceiptBytes(ByteArray(32) { 11 })
        var authorityReads = 0
        override val cut = CompactCanonicalCut(
            SurfaceGroup("scalar-preflight"), CompactCanonicalStore.PROFILE, 0, 0,
            live.toLong() + 1L, live, live, live, 0, null, root,
            CanonicalReceiptBytes(ByteArray(32) { 12 }),
        )
        override fun findById(id: SurfaceId): CompactSurface? {
            authorityReads++
            return if (id.value in 1..live.toLong()) surface(id.value, (id.value - 1).toInt()) else null
        }
        override fun findByVoxel(voxel: Voxel): CompactSurface? {
            authorityReads++
            return null
        }
        override fun readPage(region: StorageRegion, page: Int, cursor: Int, limit: Int): CompactPage {
            authorityReads++
            return CompactPage(emptyList(), null, 0)
        }
        override fun readSourceById(id: SurfaceId): CanonicalPageRead<PagedSource> {
            authorityReads++
            return CanonicalPageRead.Complete(source(id.value, (id.value - 1).toInt()), 1, 16_384)
        }
        override fun visitSourceSupport(
            target: SurfaceId,
            cursor: SourceSupportCursor?,
            sink: (PagedSupport) -> Boolean,
        ): SourceSupportRead {
            authorityReads++
            sink(PagedSupport(target, source(target.value, (target.value - 1).toInt())))
            return SourceSupportRead.Complete(1, null, 1, 16_384)
        }
        override fun retainedMemoryReceipt() = error("not used")
        override fun allocatedStorageReceipt() = error("not used")
        override fun close() = Unit
    }

    private class CountingOutputStream : OutputStream() {
        var bytes = 0L
        override fun write(value: Int) { bytes++ }
        override fun write(value: ByteArray, offset: Int, length: Int) { bytes += length }
    }
}
