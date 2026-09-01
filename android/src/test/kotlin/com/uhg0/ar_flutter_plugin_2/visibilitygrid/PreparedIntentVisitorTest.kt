package com.uhg0.ar_flutter_plugin_2.visibilitygrid

import com.uhg0.ar_flutter_plugin_2.capture.JvmDescriptorFilesystemV2
import com.uhg0.ar_flutter_plugin_2.capture.StorageBudgetCoordinatorV2
import com.uhg0.ar_flutter_plugin_2.capture.StorageBudgetPolicyV2
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.file.Files
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PreparedIntentVisitorTest {
    @Test
    fun `file backed intent rehashes and streams immutable scalar records without live N work`() {
        val smallDirectory = Files.createTempDirectory("canonical-surface-intent-visitor-small-").toFile()
        val largeDirectory = Files.createTempDirectory("canonical-surface-intent-visitor-large-").toFile()
        try {
            val small = view("small", 1)
            val large = view("large", 100_000)
            val smallIntent = intent(smallDirectory, small, "same")
            val largeIntent = intent(largeDirectory, large, "same")
            val smallVisitor = CountingVisitor()
            val largeVisitor = CountingVisitor()
            val smallResult = smallIntent.visit(smallVisitor)
            val largeResult = largeIntent.visit(largeVisitor)

            assertTrue(smallResult is PreparedIntentVisitResult.Complete)
            assertTrue(largeResult is PreparedIntentVisitResult.Complete)
            assertEquals(1, smallVisitor.rows)
            assertEquals(smallVisitor.rows, largeVisitor.rows)
            assertEquals(smallVisitor.supports, largeVisitor.supports)
            assertEquals(smallVisitor.sources, largeVisitor.sources)
            assertEquals(smallVisitor.lineage, largeVisitor.lineage)
            assertEquals(1, smallVisitor.terminals)
            assertEquals(CanonicalDirtyJournal.WRITER_SCRATCH_BYTES, 65_536)
            assertEquals(65_536, PreparedIntentVisitorResources.STREAMING_SCRATCH_BYTES)
            assertEquals(0, PreparedIntentVisitorResources.RETAINED_DECODED_RECORD_BYTES)
            assertTrue(PreparedIntentVisitorResources.PHASE_PEAK_BYTES <= CompactCanonicalStore.JOURNAL_RESERVE_BYTES)
            assertTrue((smallResult as PreparedIntentVisitResult.Complete).currentReceipt.length <= CompactCanonicalStore.JOURNAL_RESERVE_BYTES)
            assertTrue((largeResult as PreparedIntentVisitResult.Complete).currentReceipt.length <= CompactCanonicalStore.JOURNAL_RESERVE_BYTES)
            assertTrue(smallIntent.currentReceipt() is PreparedIntentCurrentReceiptResult.Complete)
            assertTrue(smallIntent.identity() is PreparedIntentIdentityResult.Complete)
            val staleGetters = setOf("getSourceCut", "getTargetHighWater", "getWalLength", "getWalHash", "getCurrentLength", "getCurrentHash")
            assertTrue(PreparedIntent::class.java.methods.none { it.name in staleGetters })
        } finally {
            smallDirectory.deleteRecursively()
            largeDirectory.deleteRecursively()
        }
    }

    @Test
    fun `early stop close and disk corruption never yield a terminal success`() {
        val directory = Files.createTempDirectory("canonical-surface-intent-visitor-fault-").toFile()
        try {
            val view = view("fault", 1)
            val stopped = intent(directory, view, "stop")
            val stopVisitor = object : CountingVisitor() {
                override fun onDirtyRow(id: Long, x: Int, y: Int, z: Int, packedNormal: Int, confidence: Int, fingerprint0: Long, fingerprint1: Long, fingerprint2: Long, fingerprint3: Long) = false
            }
            val stopResult = stopped.visit(stopVisitor)
            assertTrue(stopResult is PreparedIntentVisitResult.Stopped)
            assertEquals(0, stopVisitor.terminals)

            stopped.close()
            val afterClose = CountingVisitor()
            assertEquals(PreparedIntentVisitRefusal.CLOSED, (stopped.visit(afterClose) as PreparedIntentVisitResult.Refused).reason)
            assertEquals(0, afterClose.callbacks)

            val corruptDirectory = Files.createTempDirectory("canonical-surface-intent-visitor-corrupt-").toFile()
            try {
                val corrupted = intent(corruptDirectory, view("corrupt", 1), "corrupt")
                val bytes = corrupted.file.readBytes()
                bytes[bytes.size / 2] = (bytes[bytes.size / 2].toInt() xor 0x5a).toByte()
                corrupted.file.writeBytes(bytes)
                assertEquals(PreparedIntentVisitRefusal.CORRUPT_INTENT,
                    (corrupted.visit(CountingVisitor()) as PreparedIntentVisitResult.Refused).reason)
            } finally { corruptDirectory.deleteRecursively() }
        } finally { directory.deleteRecursively() }
    }

    @Test
    fun `independently rechecksummed structural faults reach every invariant without terminal success`() {
        structuralFault("header-profile") { it.rewriteHeader { header ->
            header.copy(sourceCut = header.sourceCut.copy(profile = "invalid-profile"))
        } }
        structuralFault("blank-command-id") { it.rewriteCommand("     ") }
        structuralFault("oversized-command-id") { it.rewriteCommand("a".repeat(257)) }
        structuralFault("declared-count") { it.rewriteHeader { header -> header.copy(targetLive = 100_001) } }
        structuralFault("target-support-cardinality") { it.rewriteTargetSupport(it.headerTargetSupport + 1) }
        structuralFault("wal-capacity") { it.putWalInt(it.layout.rowCount, 100_001) }
        structuralFault("canonical-misorder") { it.expandFirstRow(firstId = 2, secondId = 1) }
        structuralFault("duplicate-key") { it.expandFirstRow(firstId = 1, secondId = 1) }
        structuralFault("truncation") { it.rewriteWal(it.wal.copyOf(it.wal.size - 1)) }
        structuralFault("envelope-checksum") { it.corruptEnvelopeChecksum() }
        structuralFault("trailing-byte") { it.rewriteWal(it.wal + byteArrayOf(1)) }
        structuralFault("fresh-disk-wal-rehash") {
            val changed = it.wal.copyOf()
            ByteBuffer.wrap(changed).putInt(it.layout.rowStart + 8, 1)
            it.rewriteWal(changed, recomputeWalHash = false)
        }
        structuralFault("current-reencode-hash") {
            val changed = it.wal.copyOf()
            ByteBuffer.wrap(changed).putInt(it.layout.rowStart + 8, 1)
            it.rewriteWal(changed)
        }
    }

    @Test
    fun `rechecksummed row gap and dirty source mismatch cannot complete an allocation range`() {
        allocationRangeFault("row-gap") { it.rewriteRowId(2) }
        allocationRangeFault("source-mismatch") { it.rewriteSourceId(2) }
    }

    @Test
    fun `all eight mutation kinds round trip exact typed records order and target cut`() {
        val empty = scenarioView("all-kinds-empty")
        val rows = listOf(scenarioSurface(1, 0), scenarioSurface(2, 1), scenarioSurface(3, 2), scenarioSurface(4, 3))
        val sources = rows.map { scenarioSource(it.id.value, it.voxel.x) }
        val supports = rows.associate { row -> row.id.value to listOf(scenarioSource(row.id.value, row.voxel.x)) }
        val active = scenarioView("all-kinds-active", rows, geometry = 7, lineage = 5, high = 5, sources = sources, supports = supports)
        val cases = listOf(
            empty to prepare(empty, FeatureMutationCommand("feature-add", 0, 0, scenarioTarget(10))),
            active to prepare(active, FeatureMutationCommand("feature-refine", 7, 5, scenarioTarget(0, SurfaceId(1), confidence = 191))),
            empty to prepare(empty, scenarioCommand("create", CanonicalOperation.CREATE, 0, 0, emptyList(), scenarioTarget(0), scenarioTarget(1))),
            active to prepare(active, scenarioCommand("relocation", CanonicalOperation.RELOCATION, 7, 5, listOf(SurfaceId(1)), scenarioTarget(10, SurfaceId(1)))),
            active to prepare(active, scenarioCommand("merge", CanonicalOperation.MERGE, 7, 5, listOf(SurfaceId(2), SurfaceId(3)), scenarioTarget(20))),
            active to prepare(active, scenarioCommand("split", CanonicalOperation.SPLIT, 7, 5, listOf(SurfaceId(4)), scenarioTarget(30), scenarioTarget(31))),
            active to prepare(active, scenarioCommand("replacement", CanonicalOperation.REPLACEMENT, 7, 5, listOf(SurfaceId(1)), scenarioTarget(40))),
            empty to prepare(empty, CanonicalFeatureBatchCommand(
                "feature-batch", 0, 0,
                listOf(FeatureFusionChange.Upsert(FeatureFusionCandidate(
                    50, 0, 0, 2, 1,
                    listOf(FeatureNormalCandidate(50, 0, 0, FeatureNormalFace.PRIMARY, 0, 0, 191)),
                ))),
            )),
        )
        assertEquals(PreparedMutationKind.entries, cases.map { it.second.kind })

        cases.forEach { (view, plan) ->
            val directory = Files.createTempDirectory("canonical-surface-intent-kind-${plan.kind.name.lowercase()}-").toFile()
            try {
                val expected = ScalarSnapshot.from(plan)
                val prepared = intent(directory, view, plan)
                val visitor = ScalarVisitor()
                val result = prepared.visit(visitor) as PreparedIntentVisitResult.Complete
                assertEquals(plan.kind.name, expected, visitor.snapshot())
                assertEquals(plan.kind.name, expected.identity, result.identity)
                assertEquals(plan.kind.name, plan.commandId, result.identity.commandId)
                assertEquals(plan.kind.name, plan.kind, result.identity.kind)
                assertEquals(plan.kind.name, expected.terminal, result.currentReceipt)
            } finally { directory.deleteRecursively() }
        }
    }

    @Test
    fun `fresh validated identity follows a fully rechecksummed disk divergence`() {
        val directory = Files.createTempDirectory("canonical-surface-intent-disk-divergence-").toFile()
        try {
            val prepared = intent(directory, view("disk-divergence", 1), "disk-divergence")
            val before = (prepared.identity() as PreparedIntentIdentityResult.Complete).identity
            val changedHash = CanonicalReceiptBytes(testSha256("changed-on-disk".encodeToByteArray()))
            RechecksummedIntentFixture(prepared).rewriteHeader { header ->
                header.copy(sourceCut = header.sourceCut.copy(sourceHash = changedHash))
            }
            val after = (prepared.identity() as PreparedIntentIdentityResult.Complete).identity
            assertEquals(changedHash, after.sourceCut.sourceHash)
            assertTrue(before.sourceCut.sourceHash != after.sourceCut.sourceHash)
        } finally { directory.deleteRecursively() }
    }

    @Test
    fun `rechecksummed structural ordinal substitution changes the observed durable kind`() {
        val directory = Files.createTempDirectory("canonical-surface-intent-kind-substitution-").toFile()
        try {
            val row = scenarioSurface(1, 0)
            val source = scenarioSource(1, 0)
            val active = scenarioView(
                "kind-substitution", listOf(row), high = 2, sources = listOf(source),
                supports = mapOf(1L to listOf(source)),
            )
            val plan = prepare(active, scenarioCommand(
                "kind-substitution", CanonicalOperation.RELOCATION, 0, 0,
                listOf(SurfaceId(1)), scenarioTarget(10, SurfaceId(1)),
            ))
            val prepared = intent(directory, active, plan)
            val before = (prepared.identity() as PreparedIntentIdentityResult.Complete).identity
            assertEquals(PreparedMutationKind.RELOCATION, before.kind)
            RechecksummedIntentFixture(prepared).rewriteKind(PreparedMutationKind.REPLACEMENT)
            val after = (prepared.identity() as PreparedIntentIdentityResult.Complete).identity
            assertEquals("kind-substitution", after.commandId)
            assertEquals(PreparedMutationKind.REPLACEMENT, after.kind)
            assertTrue(before != after)
        } finally { directory.deleteRecursively() }
    }

    @Test
    fun `legacy v1 feature body remains readable while structural cardinality is typed refused`() {
        val featureDirectory = Files.createTempDirectory("canonical-surface-intent-v1-feature-").toFile()
        val structuralDirectory = Files.createTempDirectory("canonical-surface-intent-v1-structural-").toFile()
        try {
            val feature = intent(featureDirectory, view("v1-feature", 1), "v1-feature")
            RechecksummedIntentFixture(feature).downgradeBodyToLegacyV1()
            val featureVisitor = CountingVisitor()
            assertTrue(feature.visit(featureVisitor) is PreparedIntentVisitResult.Complete)
            assertEquals(1, featureVisitor.rows)
            assertEquals(1, featureVisitor.terminals)

            val row = scenarioSurface(1, 0)
            val source = scenarioSource(1, 0)
            val active = scenarioView(
                "v1-structural", listOf(row), high = 2, sources = listOf(source),
                supports = mapOf(1L to listOf(source)),
            )
            val plan = prepare(active, scenarioCommand(
                "v1-relocation", CanonicalOperation.RELOCATION, 0, 0,
                listOf(SurfaceId(1)), scenarioTarget(10, SurfaceId(1)),
            ))
            val structural = intent(structuralDirectory, active, plan)
            RechecksummedIntentFixture(structural).downgradeBodyToLegacyV1()
            val structuralVisitor = CountingVisitor()
            assertEquals(
                PreparedIntentVisitRefusal.UNVERIFIABLE_LEGACY_STRUCTURAL_CARDINALITY,
                (structural.visit(structuralVisitor) as PreparedIntentVisitResult.Refused).reason,
            )
            assertEquals(0, structuralVisitor.terminals)
        } finally {
            featureDirectory.deleteRecursively()
            structuralDirectory.deleteRecursively()
        }
    }

    @Test
    fun `close is serialized with an active visit and prevents every later callback`() {
        val directory = Files.createTempDirectory("canonical-surface-intent-visitor-close-").toFile()
        val executor = Executors.newFixedThreadPool(2)
        try {
            val prepared = intent(directory, view("serialized-close", 1), "serialized-close")
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            val visit = executor.submit<PreparedIntentVisitResult> {
                prepared.visit(object : CountingVisitor() {
                    override fun onHeader(identity: PreparedIntentIdentity): Boolean {
                        entered.countDown()
                        assertTrue(release.await(10, TimeUnit.SECONDS))
                        return true
                    }
                })
            }
            assertTrue(entered.await(10, TimeUnit.SECONDS))
            val close = executor.submit { prepared.close() }
            Thread.sleep(50)
            assertFalse(close.isDone)
            release.countDown()
            assertTrue(visit.get(10, TimeUnit.SECONDS) is PreparedIntentVisitResult.Complete)
            close.get(10, TimeUnit.SECONDS)

            val afterClose = CountingVisitor()
            assertEquals(PreparedIntentVisitRefusal.CLOSED,
                (prepared.visit(afterClose) as PreparedIntentVisitResult.Refused).reason)
            assertEquals(0, afterClose.callbacks)
        } finally {
            executor.shutdownNow()
            directory.deleteRecursively()
        }
    }

    private fun structuralFault(name: String, mutate: (RechecksummedIntentFixture) -> Unit) {
        val directory = Files.createTempDirectory("canonical-surface-intent-corpus-$name-").toFile()
        try {
            val prepared = intent(directory, view(name, 1), name)
            mutate(RechecksummedIntentFixture(prepared))
            val visitor = CountingVisitor()
            assertEquals(name, PreparedIntentVisitRefusal.CORRUPT_INTENT,
                (prepared.visit(visitor) as PreparedIntentVisitResult.Refused).reason)
            assertEquals(name, 0, visitor.terminals)
        } finally { directory.deleteRecursively() }
    }

    private fun allocationRangeFault(name: String, mutate: (RechecksummedIntentFixture) -> Unit) {
        val directory = Files.createTempDirectory("canonical-surface-intent-allocation-$name-").toFile()
        try {
            val view = scenarioView("allocation-$name")
            val plan = prepare(view, FeatureMutationCommand(name, 0, 0, scenarioTarget(0)))
            val prepared = intent(directory, view, plan)
            mutate(RechecksummedIntentFixture(prepared))
            val visitor = CountingVisitor()
            assertEquals(name, PreparedIntentVisitRefusal.CORRUPT_INTENT,
                (prepared.visit(visitor) as PreparedIntentVisitResult.Refused).reason)
            assertEquals(name, 0, visitor.terminals)
        } finally { directory.deleteRecursively() }
    }

    private fun prepare(view: CanonicalStateView, command: Any): PreparedCanonicalMutation {
        val result = when (command) {
            is FeatureMutationCommand -> SurfaceOwnership.prepareMutation(view, SurfaceOwnershipConfiguration(), command)
            is CanonicalTransactionCommand -> SurfaceOwnership.prepareMutation(view, SurfaceOwnershipConfiguration(), command)
            is CanonicalFeatureBatchCommand -> MutableCanonicalOverlay.prepare(view, SurfaceOwnershipConfiguration(), command)
            else -> error("unsupported command")
        }
        return (result as CanonicalMutationPreparation.Prepared).mutation
    }

    private fun scenarioCommand(
        id: String,
        kind: CanonicalOperation,
        geometry: Long,
        lineage: Long,
        sources: List<SurfaceId>,
        vararg targets: CanonicalTarget,
    ) = CanonicalTransactionCommand(id, kind, geometry, lineage, sources, targets.toList())

    private fun scenarioTarget(x: Int, id: SurfaceId? = null, confidence: Int = 192) =
        CanonicalTarget(id, Voxel(x, 0, 0), 0, 0, confidence)
    private fun scenarioSurface(id: Long, x: Int) = CompactSurface(SurfaceId(id), Voxel(x, 0, 0), 0, 192)
    private fun scenarioSource(id: Long, x: Int) = PagedSource(
        SurfaceId(id), Voxel(x, 0, 0), 0, 192, CanonicalReceiptBytes(ByteArray(32) { id.toByte() }),
    )

    private fun scenarioView(
        identity: String,
        rows: List<CompactSurface> = emptyList(),
        geometry: Long = 0,
        lineage: Long = 0,
        high: Long = 1,
        sources: List<PagedSource> = emptyList(),
        supports: Map<Long, List<PagedSource>> = emptyMap(),
    ) = ScenarioView(identity, rows, geometry, lineage, high, sources, supports)

    private fun intent(directory: File, view: CanonicalStateView, plan: PreparedCanonicalMutation): PreparedIntent {
        val coordinator = StorageBudgetCoordinatorV2(directory, StorageBudgetPolicyV2(8L * 1024 * 1024, 4_096L), JvmDescriptorFilesystemV2(authoritativeAllocationUnit = { 4_096L }), freeBytes = { 16L * 1024 * 1024 })
        val journal = (CanonicalDirtyJournal.open(view, directory, CoordinatorStorageBudget(coordinator)) as CanonicalDirtyJournalOpenResult.Opened).journal
        return (journal.flush(plan) as CanonicalDirtyJournalFlushResult.Prepared).intent
    }

    private fun intent(directory: File, view: TestView, command: String): PreparedIntent {
        val plan = SurfaceOwnership.prepareMutation(view, SurfaceOwnershipConfiguration(),
            FeatureMutationCommand(command, 0, 0, CanonicalTarget(SurfaceId(1), Voxel(0, 0, 0), 0, 0, 191)))
            as CanonicalMutationPreparation.Prepared
        return intent(directory, view, plan.mutation)
    }

    private fun view(identity: String, rows: Int): TestView {
        val cut = CompactCanonicalCut(SurfaceGroup(identity), CompactCanonicalStore.PROFILE, 0, 0, rows + 1L, rows, rows, rows, 0, null,
            CanonicalReceiptBytes(testSha256("root-$identity".encodeToByteArray())), CanonicalReceiptBytes(testSha256("source-$identity".encodeToByteArray())))
        return TestView(cut)
    }

    

    private data class ScalarRecord(
        val id: Long, val x: Int, val y: Int, val z: Int, val normal: Int, val confidence: Int,
        val f0: Long, val f1: Long, val f2: Long, val f3: Long,
    ) {
        companion object {
            fun from(id: Long, voxel: Voxel, normal: Int, confidence: Int, fingerprint: CanonicalReceiptBytes): ScalarRecord {
                val words = ByteBuffer.wrap(fingerprint.toByteArray())
                return ScalarRecord(id, voxel.x, voxel.y, voxel.z, normal, confidence,
                    words.long, words.long, words.long, words.long)
            }
        }
    }
    private data class ScalarSupport(val target: Long, val source: ScalarRecord)
    private data class ScalarSnapshot(
        val identity: PreparedIntentIdentity?,
        val rows: List<ScalarRecord>,
        val removed: List<Long>,
        val supports: List<ScalarSupport>,
        val sources: List<ScalarRecord>,
        val lineage: List<Pair<Long, Long>>,
        val terminal: PreparedIntentCurrentReceipt?,
        val order: List<String>,
    ) {
        companion object {
            fun from(plan: PreparedCanonicalMutation): ScalarSnapshot {
                val wal = ByteArrayOutputStream().also(plan::writeWalTo).toByteArray()
                val current = ByteArrayOutputStream().also(plan::writeCurrentTo).toByteArray()
                val identity = PreparedIntentIdentity(
                    plan.sourceCut, plan.commandId, plan.kind, plan.commandHash, plan.commandFingerprint, plan.targetHighWater,
                    plan.targetLiveSurfaceCount, plan.targetSourceCount, plan.targetSupportCount,
                    plan.targetLineageCount, plan.targetGeometryRevision, plan.targetLineageRevision,
                    PreparedIntentWalReceipt(wal.size.toLong(), CanonicalReceiptBytes(digest(wal))),
                    PreparedIntentCurrentReceipt(current.size.toLong(), CanonicalReceiptBytes(digest(current))),
                )
                val rows = mutableListOf<ScalarRecord>()
                plan.visitDirtyRows { rows += ScalarRecord.from(it.id.value, it.voxel, it.packedNormal, it.normalConfidence, it.allocationFingerprint); true }
                val removed = mutableListOf<Long>()
                plan.visitRemovedSurfaceIds { removed += it.value; true }
                val supports = mutableListOf<ScalarSupport>()
                plan.visitDirtySupport { supports += ScalarSupport(it.target.value, ScalarRecord.from(it.source.id.value, it.source.voxel, it.source.packedNormal, it.source.normalConfidence, it.source.allocationFingerprint)); true }
                val sources = mutableListOf<ScalarRecord>()
                plan.visitDirtySources { sources += ScalarRecord.from(it.id.value, it.voxel, it.packedNormal, it.normalConfidence, it.allocationFingerprint); true }
                val lineage = mutableListOf<Pair<Long, Long>>()
                plan.visitDirtyLineage { lineage += it.source.value to it.target.value; true }
                val order = buildList {
                    add("header")
                    rows.forEach { add("row:$it") }
                    removed.forEach { add("removed:$it") }
                    supports.forEach { add("support:$it") }
                    sources.forEach { add("source:$it") }
                    lineage.forEach { add("lineage:$it") }
                    add("terminal")
                }
                return ScalarSnapshot(identity, rows, removed, supports, sources, lineage,
                    identity.expectedCurrentReceipt, order)
            }
            private fun digest(bytes: ByteArray) = testSha256(bytes)
        }
    }

    private class ScalarVisitor : PreparedIntentVisitor {
        private var identity: PreparedIntentIdentity? = null
        private val rows = mutableListOf<ScalarRecord>()
        private val removed = mutableListOf<Long>()
        private val supports = mutableListOf<ScalarSupport>()
        private val sources = mutableListOf<ScalarRecord>()
        private val lineage = mutableListOf<Pair<Long, Long>>()
        private var terminal: PreparedIntentCurrentReceipt? = null
        private val order = mutableListOf<String>()
        override fun onHeader(identity: PreparedIntentIdentity) = true.also { this.identity = identity; order += "header" }
        override fun onDirtyRow(id: Long, x: Int, y: Int, z: Int, packedNormal: Int, confidence: Int, fingerprint0: Long, fingerprint1: Long, fingerprint2: Long, fingerprint3: Long) =
            true.also { ScalarRecord(id, x, y, z, packedNormal, confidence, fingerprint0, fingerprint1, fingerprint2, fingerprint3).also { row -> rows += row; order += "row:$row" } }
        override fun onRemovedId(id: Long) = true.also { removed += id; order += "removed:$id" }
        override fun onDirtySupport(targetId: Long, sourceId: Long, x: Int, y: Int, z: Int, packedNormal: Int, confidence: Int, fingerprint0: Long, fingerprint1: Long, fingerprint2: Long, fingerprint3: Long) =
            true.also { ScalarSupport(targetId, ScalarRecord(sourceId, x, y, z, packedNormal, confidence, fingerprint0, fingerprint1, fingerprint2, fingerprint3)).also { support -> supports += support; order += "support:$support" } }
        override fun onDirtySource(id: Long, x: Int, y: Int, z: Int, packedNormal: Int, confidence: Int, fingerprint0: Long, fingerprint1: Long, fingerprint2: Long, fingerprint3: Long) =
            true.also { ScalarRecord(id, x, y, z, packedNormal, confidence, fingerprint0, fingerprint1, fingerprint2, fingerprint3).also { source -> sources += source; order += "source:$source" } }
        override fun onDirtyLineage(sourceId: Long, targetId: Long) = true.also { lineage += sourceId to targetId; order += "lineage:${sourceId to targetId}" }
        override fun onTerminal(currentReceipt: PreparedIntentCurrentReceipt) = true.also { terminal = currentReceipt; order += "terminal" }
        fun snapshot() = ScalarSnapshot(identity, rows, removed, supports, sources, lineage, terminal, order)
    }

    private class ScenarioView(
        identity: String,
        rows: List<CompactSurface>,
        geometry: Long,
        lineage: Long,
        high: Long,
        sources: List<PagedSource>,
        private val supports: Map<Long, List<PagedSource>>,
    ) : CanonicalStateView {
        private val ids = rows.associateBy { it.id }
        private val voxels = rows.associateBy { it.voxel }
        private val sourceIds = sources.associateBy { it.id }
        override val cut = CompactCanonicalCut(
            SurfaceGroup(identity), CompactCanonicalStore.PROFILE, geometry, lineage, high,
            rows.size, sources.size, supports.values.sumOf { it.size }, 0, null,
            CanonicalReceiptBytes(testSha256("root-$identity".encodeToByteArray())),
            CanonicalReceiptBytes(testSha256("source-$identity".encodeToByteArray())),
        )
        override fun findById(id: SurfaceId) = ids[id]
        override fun findByVoxel(voxel: Voxel) = voxels[voxel]
        override fun readPage(region: StorageRegion, page: Int, cursor: Int, limit: Int) = CompactPage(emptyList(), null, 0)
        override fun readSourceById(id: SurfaceId) = CanonicalPageRead.Complete(sourceIds[id], 0, 0)
        override fun visitSourceSupport(target: SurfaceId, cursor: SourceSupportCursor?, sink: (PagedSupport) -> Boolean): SourceSupportRead {
            var delivered = 0
            supports[target.value].orEmpty().forEach { source -> if (sink(PagedSupport(target, source))) delivered++ }
            return SourceSupportRead.Complete(delivered, null, 0, 0)
        }
        override fun retainedMemoryReceipt() = error("not used")
        override fun allocatedStorageReceipt() = error("not used")
        override fun close() = Unit
    }

    /** Rebuilds a structurally valid test envelope so each fault reaches its named invariant. */
    private class RechecksummedIntentFixture(private val intent: PreparedIntent) {
        private var header = DirtyIntentHeader.read(intent.file)
        var wal = intent.file.readBytes().copyOfRange(header.walOffset.toInt(), header.walOffset.toInt() + header.walLength.toInt())
            private set
        val headerTargetSupport get() = header.targetSupport
        val layout get() = WalLayout.read(wal)

        fun rewriteHeader(change: (DirtyIntentHeader) -> DirtyIntentHeader) = rewrite(change(header), wal)

        fun putWalInt(offset: Int, value: Int) {
            val changed = wal.copyOf()
            ByteBuffer.wrap(changed).putInt(offset, value)
            rewriteWal(changed)
        }

        fun expandFirstRow(firstId: Long, secondId: Long) {
            val positions = layout
            require(ByteBuffer.wrap(wal).getInt(positions.rowCount) == 1)
            val record = wal.copyOfRange(positions.rowStart, positions.rowStart + ROW_BYTES)
            val expanded = ByteArrayOutputStream().also { output ->
                output.write(wal, 0, positions.rowStart + ROW_BYTES)
                output.write(record)
                output.write(wal, positions.rowStart + ROW_BYTES, wal.size - positions.rowStart - ROW_BYTES)
            }.toByteArray()
            ByteBuffer.wrap(expanded).putInt(positions.rowCount, 2)
            ByteBuffer.wrap(expanded).putLong(positions.rowStart, firstId)
            ByteBuffer.wrap(expanded).putLong(positions.rowStart + ROW_BYTES, secondId)
            rewriteWal(expanded)
        }

        fun rewriteWal(changed: ByteArray, recomputeWalHash: Boolean = true) {
            val changedHeader = header.copy(
                walLength = changed.size.toLong(),
                walHash = if (recomputeWalHash) CanonicalReceiptBytes(digest(changed)) else header.walHash,
                walOffset = 0,
            )
            rewrite(changedHeader, changed)
        }

        fun rewriteTargetSupport(changedTargetSupport: Int) {
            val changed = wal.copyOf()
            ByteBuffer.wrap(changed).putInt(layout.targetSupport, changedTargetSupport)
            val current = currentFrom(changed)
            rewrite(
                header.copy(
                    targetSupport = changedTargetSupport,
                    walHash = CanonicalReceiptBytes(digest(changed)),
                    currentHash = CanonicalReceiptBytes(digest(current)),
                    walOffset = 0,
                ),
                changed,
            )
        }

        fun rewriteKind(changedKind: PreparedMutationKind) {
            val changed = wal.copyOf()
            ByteBuffer.wrap(changed).putInt(layout.kind, changedKind.ordinal)
            val current = currentFrom(changed)
            rewrite(
                header.copy(
                    walHash = CanonicalReceiptBytes(digest(changed)),
                    currentHash = CanonicalReceiptBytes(digest(current)),
                    walOffset = 0,
                ),
                changed,
            )
        }

        fun rewriteCommand(changedCommand: String) {
            val encoded = ByteArrayOutputStream().also { bytes ->
                DataOutputStream(bytes).use { it.writeUTF(changedCommand) }
            }.toByteArray()
            val commandOffset = 8 + 32
            val originalBytes = 2 + (ByteBuffer.wrap(wal).getShort(commandOffset).toInt() and 0xffff)
            val changed = ByteArrayOutputStream().also { output ->
                output.write(wal, 0, commandOffset)
                output.write(encoded)
                output.write(wal, commandOffset + originalBytes, wal.size - commandOffset - originalBytes)
            }.toByteArray()
            val current = currentFrom(changed)
            rewrite(
                header.copy(
                    walLength = changed.size.toLong(),
                    walHash = CanonicalReceiptBytes(digest(changed)),
                    currentLength = current.size.toLong(),
                    currentHash = CanonicalReceiptBytes(digest(current)),
                    walOffset = 0,
                ),
                changed,
            )
        }

        fun rewriteRowId(changedId: Long) = rewriteRecordId(layout.rowStart, changedId)
        fun rewriteSourceId(changedId: Long) = rewriteRecordId(layout.sourceStart, changedId)

        private fun rewriteRecordId(offset: Int, changedId: Long) {
            val changed = wal.copyOf()
            ByteBuffer.wrap(changed).putLong(offset, changedId)
            val current = currentFrom(changed)
            rewrite(
                header.copy(
                    walHash = CanonicalReceiptBytes(digest(changed)),
                    currentHash = CanonicalReceiptBytes(digest(current)),
                    walOffset = 0,
                ),
                changed,
            )
        }

        fun downgradeBodyToLegacyV1() {
            val positions = layout
            require(ByteBuffer.wrap(wal).getInt(4) == 2)
            val changed = ByteArrayOutputStream().also { output ->
                output.write(wal, 0, positions.removedSupportCount)
                output.write(wal, positions.removedSupportCount + 4, wal.size - positions.removedSupportCount - 4)
            }.toByteArray()
            ByteBuffer.wrap(changed).putInt(4, 1)
            val current = currentFrom(changed)
            rewrite(
                header.copy(
                    walLength = changed.size.toLong(),
                    walHash = CanonicalReceiptBytes(digest(changed)),
                    currentLength = current.size.toLong(),
                    currentHash = CanonicalReceiptBytes(digest(current)),
                    walOffset = 0,
                ),
                changed,
            )
        }

        fun corruptEnvelopeChecksum() {
            val bytes = intent.file.readBytes()
            bytes[bytes.lastIndex] = (bytes.last().toInt() xor 0x5a).toByte()
            intent.file.writeBytes(bytes)
        }

        private fun rewrite(changedHeader: DirtyIntentHeader, changedWal: ByteArray) {
            val payload = ByteArrayOutputStream().also { bytes ->
                DataOutputStream(bytes).use { output ->
                    changedHeader.writeWithoutChecksum(output)
                    output.write(changedWal)
                }
            }.toByteArray()
            intent.file.writeBytes(payload + digest(payload))
            header = changedHeader.copy(walOffset = (payload.size - changedWal.size).toLong())
            wal = changedWal
        }

        data class WalLayout(
            val kind: Int,
            val targetSupport: Int,
            val rowCount: Int,
            val rowStart: Int,
            val removedSupportCount: Int,
            val sourceStart: Int,
        ) {
            companion object {
                fun read(wal: ByteArray): WalLayout {
                    val buffer = ByteBuffer.wrap(wal)
                    var cursor = 8 + 32
                    val commandBytes = buffer.getShort(cursor).toInt() and 0xffff
                    cursor += 2 + commandBytes
                    val kind = cursor
                    val targetSupport = cursor + 4 + 32 + 32 + 8 + 4 + 4
                    cursor += 4 + 32 + 32 + 8 + (4 * 4) + 8 + 8
                    val rowCount = cursor
                    val rows = buffer.getInt(rowCount)
                    val rowStart = rowCount + 4
                    val removedCount = rowStart + rows * ROW_BYTES
                    val removed = buffer.getInt(removedCount)
                    val removedSupportCount = removedCount + 4 + removed * 8
                    val supportCount = removedSupportCount + 4
                    val supports = buffer.getInt(supportCount)
                    val sourceCount = supportCount + 4 + supports * SUPPORT_BYTES
                    return WalLayout(kind, targetSupport, rowCount, rowStart, removedSupportCount, sourceCount + 4)
                }
            }
        }

        companion object {
            private const val ROW_BYTES = 60
            private const val SUPPORT_BYTES = 68
            private const val CURRENT_MAGIC = 0x4d334350
            private fun digest(bytes: ByteArray) = testSha256(bytes)
            private fun currentFrom(wal: ByteArray) = wal.copyOf().also { ByteBuffer.wrap(it).putInt(0, CURRENT_MAGIC) }
        }
    }

    private class TestView(override val cut: CompactCanonicalCut) : CanonicalStateView {
        private val row = CompactSurface(SurfaceId(1), Voxel(0, 0, 0), 0, 192)
        override fun findById(id: SurfaceId) = row.takeIf { it.id == id }
        override fun findByVoxel(voxel: Voxel) = row.takeIf { it.voxel == voxel }
        override fun readPage(region: StorageRegion, page: Int, cursor: Int, limit: Int) = CompactPage(emptyList(), null, 0)
        override fun readSourceById(id: SurfaceId) = CanonicalPageRead.Complete(row.takeIf { it.id == id }?.let { PagedSource(it.id, it.voxel, it.packedNormal, it.normalConfidence, CanonicalReceiptBytes(ByteArray(32))) }, 0, 0)
        override fun visitSourceSupport(target: SurfaceId, cursor: SourceSupportCursor?, sink: (PagedSupport) -> Boolean) = SourceSupportRead.Complete(0, null, 0, 0)
        override fun retainedMemoryReceipt() = error("not used")
        override fun allocatedStorageReceipt() = error("not used")
        override fun close() = Unit
    }

    private open class CountingVisitor : PreparedIntentVisitor {
        var rows = 0; var removed = 0; var supports = 0; var sources = 0; var lineage = 0; var terminals = 0
        private var headers = 0
        val callbacks get() = headers + rows + removed + supports + sources + lineage + terminals
        override fun onHeader(identity: PreparedIntentIdentity) = true.also { headers++ }
        override fun onDirtyRow(id: Long, x: Int, y: Int, z: Int, packedNormal: Int, confidence: Int, fingerprint0: Long, fingerprint1: Long, fingerprint2: Long, fingerprint3: Long): Boolean { rows++; return true }
        override fun onRemovedId(id: Long): Boolean { removed++; return true }
        override fun onDirtySupport(targetId: Long, sourceId: Long, x: Int, y: Int, z: Int, packedNormal: Int, confidence: Int, fingerprint0: Long, fingerprint1: Long, fingerprint2: Long, fingerprint3: Long): Boolean { supports++; return true }
        override fun onDirtySource(id: Long, x: Int, y: Int, z: Int, packedNormal: Int, confidence: Int, fingerprint0: Long, fingerprint1: Long, fingerprint2: Long, fingerprint3: Long): Boolean { sources++; return true }
        override fun onDirtyLineage(sourceId: Long, targetId: Long): Boolean { lineage++; return true }
        override fun onTerminal(currentReceipt: PreparedIntentCurrentReceipt): Boolean { terminals++; return true }
    }
}
