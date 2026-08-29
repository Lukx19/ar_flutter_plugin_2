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

class M3PreparedIntentVisitorTest {
    @Test
    fun `file backed intent rehashes and streams immutable scalar records without live N work`() {
        val smallDirectory = Files.createTempDirectory("m3-intent-visitor-small-").toFile()
        val largeDirectory = Files.createTempDirectory("m3-intent-visitor-large-").toFile()
        try {
            val small = view("small", 1)
            val large = view("large", 100_000)
            val smallIntent = intent(smallDirectory, small, "same")
            val largeIntent = intent(largeDirectory, large, "same")
            val smallVisitor = CountingVisitor()
            val largeVisitor = CountingVisitor()
            val smallResult = smallIntent.visit(smallVisitor)
            val largeResult = largeIntent.visit(largeVisitor)

            assertTrue(smallResult is M3PreparedIntentVisitResult.Complete)
            assertTrue(largeResult is M3PreparedIntentVisitResult.Complete)
            assertEquals(1, smallVisitor.rows)
            assertEquals(smallVisitor.rows, largeVisitor.rows)
            assertEquals(smallVisitor.supports, largeVisitor.supports)
            assertEquals(smallVisitor.sources, largeVisitor.sources)
            assertEquals(smallVisitor.lineage, largeVisitor.lineage)
            assertEquals(1, smallVisitor.terminals)
            assertEquals(M3CanonicalDirtyJournal.WRITER_SCRATCH_BYTES, 65_536)
            assertEquals(65_536, M3PreparedIntentVisitorResources.STREAMING_SCRATCH_BYTES)
            assertEquals(0, M3PreparedIntentVisitorResources.RETAINED_DECODED_RECORD_BYTES)
            assertTrue(M3PreparedIntentVisitorResources.PHASE_PEAK_BYTES <= M3CompactCanonicalStore.JOURNAL_RESERVE_BYTES)
            assertTrue((smallResult as M3PreparedIntentVisitResult.Complete).currentReceipt.length <= M3CompactCanonicalStore.JOURNAL_RESERVE_BYTES)
            assertTrue((largeResult as M3PreparedIntentVisitResult.Complete).currentReceipt.length <= M3CompactCanonicalStore.JOURNAL_RESERVE_BYTES)
            assertTrue(smallIntent.currentReceipt() is M3PreparedIntentCurrentReceiptResult.Complete)
            assertTrue(smallIntent.identity() is M3PreparedIntentIdentityResult.Complete)
            val staleGetters = setOf("getSourceCut", "getTargetHighWater", "getWalLength", "getWalHash", "getCurrentLength", "getCurrentHash")
            assertTrue(M3PreparedIntent::class.java.methods.none { it.name in staleGetters })
        } finally {
            smallDirectory.deleteRecursively()
            largeDirectory.deleteRecursively()
        }
    }

    @Test
    fun `early stop close and disk corruption never yield a terminal success`() {
        val directory = Files.createTempDirectory("m3-intent-visitor-fault-").toFile()
        try {
            val view = view("fault", 1)
            val stopped = intent(directory, view, "stop")
            val stopVisitor = object : CountingVisitor() {
                override fun onDirtyRow(id: Long, x: Int, y: Int, z: Int, packedNormal: Int, confidence: Int, fingerprint0: Long, fingerprint1: Long, fingerprint2: Long, fingerprint3: Long) = false
            }
            val stopResult = stopped.visit(stopVisitor)
            assertTrue(stopResult is M3PreparedIntentVisitResult.Stopped)
            assertEquals(0, stopVisitor.terminals)

            stopped.close()
            val afterClose = CountingVisitor()
            assertEquals(M3PreparedIntentVisitRefusal.CLOSED, (stopped.visit(afterClose) as M3PreparedIntentVisitResult.Refused).reason)
            assertEquals(0, afterClose.callbacks)

            val corruptDirectory = Files.createTempDirectory("m3-intent-visitor-corrupt-").toFile()
            try {
                val corrupted = intent(corruptDirectory, view("corrupt", 1), "corrupt")
                val bytes = corrupted.file.readBytes()
                bytes[bytes.size / 2] = (bytes[bytes.size / 2].toInt() xor 0x5a).toByte()
                corrupted.file.writeBytes(bytes)
                assertEquals(M3PreparedIntentVisitRefusal.CORRUPT_INTENT,
                    (corrupted.visit(CountingVisitor()) as M3PreparedIntentVisitResult.Refused).reason)
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
    fun `all seven mutation kinds round trip exact typed records order and target cut`() {
        val empty = scenarioView("all-kinds-empty")
        val rows = listOf(scenarioSurface(1, 0), scenarioSurface(2, 1), scenarioSurface(3, 2), scenarioSurface(4, 3))
        val sources = rows.map { scenarioSource(it.id.value, it.voxel.x) }
        val supports = rows.associate { row -> row.id.value to listOf(scenarioSource(row.id.value, row.voxel.x)) }
        val active = scenarioView("all-kinds-active", rows, geometry = 7, lineage = 5, high = 5, sources = sources, supports = supports)
        val cases = listOf(
            empty to prepare(empty, M3FeatureMutationCommand("feature-add", 0, 0, scenarioTarget(10))),
            active to prepare(active, M3FeatureMutationCommand("feature-refine", 7, 5, scenarioTarget(0, M3SurfaceId(1), confidence = 191))),
            empty to prepare(empty, scenarioCommand("create", M3CanonicalOperation.CREATE, 0, 0, emptyList(), scenarioTarget(0), scenarioTarget(1))),
            active to prepare(active, scenarioCommand("relocation", M3CanonicalOperation.RELOCATION, 7, 5, listOf(M3SurfaceId(1)), scenarioTarget(10, M3SurfaceId(1)))),
            active to prepare(active, scenarioCommand("merge", M3CanonicalOperation.MERGE, 7, 5, listOf(M3SurfaceId(2), M3SurfaceId(3)), scenarioTarget(20))),
            active to prepare(active, scenarioCommand("split", M3CanonicalOperation.SPLIT, 7, 5, listOf(M3SurfaceId(4)), scenarioTarget(30), scenarioTarget(31))),
            active to prepare(active, scenarioCommand("replacement", M3CanonicalOperation.REPLACEMENT, 7, 5, listOf(M3SurfaceId(1)), scenarioTarget(40))),
        )
        assertEquals(M3PreparedMutationKind.entries, cases.map { it.second.kind })

        cases.forEach { (view, plan) ->
            val directory = Files.createTempDirectory("m3-intent-kind-${plan.kind.name.lowercase()}-").toFile()
            try {
                val expected = ScalarSnapshot.from(plan)
                val prepared = intent(directory, view, plan)
                val visitor = ScalarVisitor()
                val result = prepared.visit(visitor) as M3PreparedIntentVisitResult.Complete
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
        val directory = Files.createTempDirectory("m3-intent-disk-divergence-").toFile()
        try {
            val prepared = intent(directory, view("disk-divergence", 1), "disk-divergence")
            val before = (prepared.identity() as M3PreparedIntentIdentityResult.Complete).identity
            val changedHash = M3CanonicalReceiptBytes(sha256("changed-on-disk".encodeToByteArray()))
            RechecksummedIntentFixture(prepared).rewriteHeader { header ->
                header.copy(sourceCut = header.sourceCut.copy(sourceHash = changedHash))
            }
            val after = (prepared.identity() as M3PreparedIntentIdentityResult.Complete).identity
            assertEquals(changedHash, after.sourceCut.sourceHash)
            assertTrue(before.sourceCut.sourceHash != after.sourceCut.sourceHash)
        } finally { directory.deleteRecursively() }
    }

    @Test
    fun `rechecksummed structural ordinal substitution changes the observed durable kind`() {
        val directory = Files.createTempDirectory("m3-intent-kind-substitution-").toFile()
        try {
            val row = scenarioSurface(1, 0)
            val source = scenarioSource(1, 0)
            val active = scenarioView(
                "kind-substitution", listOf(row), high = 2, sources = listOf(source),
                supports = mapOf(1L to listOf(source)),
            )
            val plan = prepare(active, scenarioCommand(
                "kind-substitution", M3CanonicalOperation.RELOCATION, 0, 0,
                listOf(M3SurfaceId(1)), scenarioTarget(10, M3SurfaceId(1)),
            ))
            val prepared = intent(directory, active, plan)
            val before = (prepared.identity() as M3PreparedIntentIdentityResult.Complete).identity
            assertEquals(M3PreparedMutationKind.RELOCATION, before.kind)
            RechecksummedIntentFixture(prepared).rewriteKind(M3PreparedMutationKind.REPLACEMENT)
            val after = (prepared.identity() as M3PreparedIntentIdentityResult.Complete).identity
            assertEquals("kind-substitution", after.commandId)
            assertEquals(M3PreparedMutationKind.REPLACEMENT, after.kind)
            assertTrue(before != after)
        } finally { directory.deleteRecursively() }
    }

    @Test
    fun `legacy v1 feature body remains readable while structural cardinality is typed refused`() {
        val featureDirectory = Files.createTempDirectory("m3-intent-v1-feature-").toFile()
        val structuralDirectory = Files.createTempDirectory("m3-intent-v1-structural-").toFile()
        try {
            val feature = intent(featureDirectory, view("v1-feature", 1), "v1-feature")
            RechecksummedIntentFixture(feature).downgradeBodyToLegacyV1()
            val featureVisitor = CountingVisitor()
            assertTrue(feature.visit(featureVisitor) is M3PreparedIntentVisitResult.Complete)
            assertEquals(1, featureVisitor.rows)
            assertEquals(1, featureVisitor.terminals)

            val row = scenarioSurface(1, 0)
            val source = scenarioSource(1, 0)
            val active = scenarioView(
                "v1-structural", listOf(row), high = 2, sources = listOf(source),
                supports = mapOf(1L to listOf(source)),
            )
            val plan = prepare(active, scenarioCommand(
                "v1-relocation", M3CanonicalOperation.RELOCATION, 0, 0,
                listOf(M3SurfaceId(1)), scenarioTarget(10, M3SurfaceId(1)),
            ))
            val structural = intent(structuralDirectory, active, plan)
            RechecksummedIntentFixture(structural).downgradeBodyToLegacyV1()
            val structuralVisitor = CountingVisitor()
            assertEquals(
                M3PreparedIntentVisitRefusal.UNVERIFIABLE_LEGACY_STRUCTURAL_CARDINALITY,
                (structural.visit(structuralVisitor) as M3PreparedIntentVisitResult.Refused).reason,
            )
            assertEquals(0, structuralVisitor.terminals)
        } finally {
            featureDirectory.deleteRecursively()
            structuralDirectory.deleteRecursively()
        }
    }

    @Test
    fun `close is serialized with an active visit and prevents every later callback`() {
        val directory = Files.createTempDirectory("m3-intent-visitor-close-").toFile()
        val executor = Executors.newFixedThreadPool(2)
        try {
            val prepared = intent(directory, view("serialized-close", 1), "serialized-close")
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            val visit = executor.submit<M3PreparedIntentVisitResult> {
                prepared.visit(object : CountingVisitor() {
                    override fun onHeader(identity: M3PreparedIntentIdentity): Boolean {
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
            assertTrue(visit.get(10, TimeUnit.SECONDS) is M3PreparedIntentVisitResult.Complete)
            close.get(10, TimeUnit.SECONDS)

            val afterClose = CountingVisitor()
            assertEquals(M3PreparedIntentVisitRefusal.CLOSED,
                (prepared.visit(afterClose) as M3PreparedIntentVisitResult.Refused).reason)
            assertEquals(0, afterClose.callbacks)
        } finally {
            executor.shutdownNow()
            directory.deleteRecursively()
        }
    }

    private fun structuralFault(name: String, mutate: (RechecksummedIntentFixture) -> Unit) {
        val directory = Files.createTempDirectory("m3-intent-corpus-$name-").toFile()
        try {
            val prepared = intent(directory, view(name, 1), name)
            mutate(RechecksummedIntentFixture(prepared))
            val visitor = CountingVisitor()
            assertEquals(name, M3PreparedIntentVisitRefusal.CORRUPT_INTENT,
                (prepared.visit(visitor) as M3PreparedIntentVisitResult.Refused).reason)
            assertEquals(name, 0, visitor.terminals)
        } finally { directory.deleteRecursively() }
    }

    private fun prepare(view: M3CanonicalStateView, command: Any): M3PreparedCanonicalMutation {
        val result = when (command) {
            is M3FeatureMutationCommand -> M3SurfaceOwnership.prepareMutation(view, M3SurfaceOwnershipConfiguration(), command)
            is M3CanonicalTransactionCommand -> M3SurfaceOwnership.prepareMutation(view, M3SurfaceOwnershipConfiguration(), command)
            else -> error("unsupported command")
        }
        return (result as M3CanonicalMutationPreparation.Prepared).mutation
    }

    private fun scenarioCommand(
        id: String,
        kind: M3CanonicalOperation,
        geometry: Long,
        lineage: Long,
        sources: List<M3SurfaceId>,
        vararg targets: M3CanonicalTarget,
    ) = M3CanonicalTransactionCommand(id, kind, geometry, lineage, sources, targets.toList())

    private fun scenarioTarget(x: Int, id: M3SurfaceId? = null, confidence: Int = 192) =
        M3CanonicalTarget(id, M3Voxel(x, 0, 0), 0, 0, confidence)
    private fun scenarioSurface(id: Long, x: Int) = M3CompactSurface(M3SurfaceId(id), M3Voxel(x, 0, 0), 0, 192)
    private fun scenarioSource(id: Long, x: Int) = M3PagedSource(
        M3SurfaceId(id), M3Voxel(x, 0, 0), 0, 192, M3CanonicalReceiptBytes(ByteArray(32) { id.toByte() }),
    )

    private fun scenarioView(
        identity: String,
        rows: List<M3CompactSurface> = emptyList(),
        geometry: Long = 0,
        lineage: Long = 0,
        high: Long = 1,
        sources: List<M3PagedSource> = emptyList(),
        supports: Map<Long, List<M3PagedSource>> = emptyMap(),
    ) = ScenarioView(identity, rows, geometry, lineage, high, sources, supports)

    private fun intent(directory: File, view: M3CanonicalStateView, plan: M3PreparedCanonicalMutation): M3PreparedIntent {
        val coordinator = StorageBudgetCoordinatorV2(directory, StorageBudgetPolicyV2(8L * 1024 * 1024, 4_096L), JvmDescriptorFilesystemV2(authoritativeAllocationUnit = { 4_096L }), freeBytes = { 16L * 1024 * 1024 })
        val journal = (M3CanonicalDirtyJournal.open(view, directory, M3CoordinatorStorageBudget(coordinator)) as M3CanonicalDirtyJournalOpenResult.Opened).journal
        return (journal.flush(plan) as M3CanonicalDirtyJournalFlushResult.Prepared).intent
    }

    private fun intent(directory: File, view: TestView, command: String): M3PreparedIntent {
        val plan = M3SurfaceOwnership.prepareMutation(view, M3SurfaceOwnershipConfiguration(),
            M3FeatureMutationCommand(command, 0, 0, M3CanonicalTarget(M3SurfaceId(1), M3Voxel(0, 0, 0), 0, 0, 191)))
            as M3CanonicalMutationPreparation.Prepared
        return intent(directory, view, plan.mutation)
    }

    private fun view(identity: String, rows: Int): TestView {
        val cut = M3CompactCanonicalCut(M3SurfaceGroup(identity), M3CompactCanonicalStore.PROFILE, 0, 0, rows + 1L, rows, rows, rows, 0, null,
            M3CanonicalReceiptBytes(sha256("root-$identity".encodeToByteArray())), M3CanonicalReceiptBytes(sha256("source-$identity".encodeToByteArray())))
        return TestView(cut)
    }

    private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)

    private data class ScalarRecord(
        val id: Long, val x: Int, val y: Int, val z: Int, val normal: Int, val confidence: Int,
        val f0: Long, val f1: Long, val f2: Long, val f3: Long,
    ) {
        companion object {
            fun from(id: Long, voxel: M3Voxel, normal: Int, confidence: Int, fingerprint: M3CanonicalReceiptBytes): ScalarRecord {
                val words = ByteBuffer.wrap(fingerprint.toByteArray())
                return ScalarRecord(id, voxel.x, voxel.y, voxel.z, normal, confidence,
                    words.long, words.long, words.long, words.long)
            }
        }
    }
    private data class ScalarSupport(val target: Long, val source: ScalarRecord)
    private data class ScalarSnapshot(
        val identity: M3PreparedIntentIdentity?,
        val rows: List<ScalarRecord>,
        val removed: List<Long>,
        val supports: List<ScalarSupport>,
        val sources: List<ScalarRecord>,
        val lineage: List<Pair<Long, Long>>,
        val terminal: M3PreparedIntentCurrentReceipt?,
        val order: List<String>,
    ) {
        companion object {
            fun from(plan: M3PreparedCanonicalMutation): ScalarSnapshot {
                val wal = ByteArrayOutputStream().also(plan::writeWalTo).toByteArray()
                val current = ByteArrayOutputStream().also(plan::writeCurrentTo).toByteArray()
                val identity = M3PreparedIntentIdentity(
                    plan.sourceCut, plan.commandId, plan.kind, plan.commandHash, plan.commandFingerprint, plan.targetHighWater,
                    plan.targetLiveSurfaceCount, plan.targetSourceCount, plan.targetSupportCount,
                    plan.targetLineageCount, plan.targetGeometryRevision, plan.targetLineageRevision,
                    M3PreparedIntentWalReceipt(wal.size.toLong(), M3CanonicalReceiptBytes(digest(wal))),
                    M3PreparedIntentCurrentReceipt(current.size.toLong(), M3CanonicalReceiptBytes(digest(current))),
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
            private fun digest(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
        }
    }

    private class ScalarVisitor : M3PreparedIntentVisitor {
        private var identity: M3PreparedIntentIdentity? = null
        private val rows = mutableListOf<ScalarRecord>()
        private val removed = mutableListOf<Long>()
        private val supports = mutableListOf<ScalarSupport>()
        private val sources = mutableListOf<ScalarRecord>()
        private val lineage = mutableListOf<Pair<Long, Long>>()
        private var terminal: M3PreparedIntentCurrentReceipt? = null
        private val order = mutableListOf<String>()
        override fun onHeader(identity: M3PreparedIntentIdentity) = true.also { this.identity = identity; order += "header" }
        override fun onDirtyRow(id: Long, x: Int, y: Int, z: Int, packedNormal: Int, confidence: Int, fingerprint0: Long, fingerprint1: Long, fingerprint2: Long, fingerprint3: Long) =
            true.also { ScalarRecord(id, x, y, z, packedNormal, confidence, fingerprint0, fingerprint1, fingerprint2, fingerprint3).also { row -> rows += row; order += "row:$row" } }
        override fun onRemovedId(id: Long) = true.also { removed += id; order += "removed:$id" }
        override fun onDirtySupport(targetId: Long, sourceId: Long, x: Int, y: Int, z: Int, packedNormal: Int, confidence: Int, fingerprint0: Long, fingerprint1: Long, fingerprint2: Long, fingerprint3: Long) =
            true.also { ScalarSupport(targetId, ScalarRecord(sourceId, x, y, z, packedNormal, confidence, fingerprint0, fingerprint1, fingerprint2, fingerprint3)).also { support -> supports += support; order += "support:$support" } }
        override fun onDirtySource(id: Long, x: Int, y: Int, z: Int, packedNormal: Int, confidence: Int, fingerprint0: Long, fingerprint1: Long, fingerprint2: Long, fingerprint3: Long) =
            true.also { ScalarRecord(id, x, y, z, packedNormal, confidence, fingerprint0, fingerprint1, fingerprint2, fingerprint3).also { source -> sources += source; order += "source:$source" } }
        override fun onDirtyLineage(sourceId: Long, targetId: Long) = true.also { lineage += sourceId to targetId; order += "lineage:${sourceId to targetId}" }
        override fun onTerminal(currentReceipt: M3PreparedIntentCurrentReceipt) = true.also { terminal = currentReceipt; order += "terminal" }
        fun snapshot() = ScalarSnapshot(identity, rows, removed, supports, sources, lineage, terminal, order)
    }

    private class ScenarioView(
        identity: String,
        rows: List<M3CompactSurface>,
        geometry: Long,
        lineage: Long,
        high: Long,
        sources: List<M3PagedSource>,
        private val supports: Map<Long, List<M3PagedSource>>,
    ) : M3CanonicalStateView {
        private val ids = rows.associateBy { it.id }
        private val voxels = rows.associateBy { it.voxel }
        private val sourceIds = sources.associateBy { it.id }
        override val cut = M3CompactCanonicalCut(
            M3SurfaceGroup(identity), M3CompactCanonicalStore.PROFILE, geometry, lineage, high,
            rows.size, sources.size, supports.values.sumOf { it.size }, 0, null,
            M3CanonicalReceiptBytes(MessageDigest.getInstance("SHA-256").digest("root-$identity".encodeToByteArray())),
            M3CanonicalReceiptBytes(MessageDigest.getInstance("SHA-256").digest("source-$identity".encodeToByteArray())),
        )
        override fun findById(id: M3SurfaceId) = ids[id]
        override fun findByVoxel(voxel: M3Voxel) = voxels[voxel]
        override fun readPage(region: M3StorageRegion, page: Int, cursor: Int, limit: Int) = M3CompactPage(emptyList(), null, 0)
        override fun readSourceById(id: M3SurfaceId) = M3CanonicalPageRead.Complete(sourceIds[id], 0, 0)
        override fun visitSourceSupport(target: M3SurfaceId, cursor: M3SourceSupportCursor?, sink: (M3PagedSupport) -> Boolean): M3SourceSupportRead {
            var delivered = 0
            supports[target.value].orEmpty().forEach { source -> if (sink(M3PagedSupport(target, source))) delivered++ }
            return M3SourceSupportRead.Complete(delivered, null, 0, 0)
        }
        override fun retainedMemoryReceipt() = error("not used")
        override fun allocatedStorageReceipt() = error("not used")
        override fun close() = Unit
    }

    /** Rebuilds a structurally valid test envelope so each fault reaches its named invariant. */
    private class RechecksummedIntentFixture(private val intent: M3PreparedIntent) {
        private var header = M3DirtyIntentHeader.read(intent.file)
        var wal = intent.file.readBytes().copyOfRange(header.walOffset.toInt(), header.walOffset.toInt() + header.walLength.toInt())
            private set
        val headerTargetSupport get() = header.targetSupport
        val layout get() = WalLayout.read(wal)

        fun rewriteHeader(change: (M3DirtyIntentHeader) -> M3DirtyIntentHeader) = rewrite(change(header), wal)

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
                walHash = if (recomputeWalHash) M3CanonicalReceiptBytes(digest(changed)) else header.walHash,
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
                    walHash = M3CanonicalReceiptBytes(digest(changed)),
                    currentHash = M3CanonicalReceiptBytes(digest(current)),
                    walOffset = 0,
                ),
                changed,
            )
        }

        fun rewriteKind(changedKind: M3PreparedMutationKind) {
            val changed = wal.copyOf()
            ByteBuffer.wrap(changed).putInt(layout.kind, changedKind.ordinal)
            val current = currentFrom(changed)
            rewrite(
                header.copy(
                    walHash = M3CanonicalReceiptBytes(digest(changed)),
                    currentHash = M3CanonicalReceiptBytes(digest(current)),
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
                    walHash = M3CanonicalReceiptBytes(digest(changed)),
                    currentLength = current.size.toLong(),
                    currentHash = M3CanonicalReceiptBytes(digest(current)),
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
                    walHash = M3CanonicalReceiptBytes(digest(changed)),
                    currentLength = current.size.toLong(),
                    currentHash = M3CanonicalReceiptBytes(digest(current)),
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

        private fun rewrite(changedHeader: M3DirtyIntentHeader, changedWal: ByteArray) {
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
                    return WalLayout(kind, targetSupport, rowCount, rowStart, removedCount + 4 + removed * 8)
                }
            }
        }

        companion object {
            private const val ROW_BYTES = 60
            private const val CURRENT_MAGIC = 0x4d334350
            private fun digest(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
            private fun currentFrom(wal: ByteArray) = wal.copyOf().also { ByteBuffer.wrap(it).putInt(0, CURRENT_MAGIC) }
        }
    }

    private class TestView(override val cut: M3CompactCanonicalCut) : M3CanonicalStateView {
        private val row = M3CompactSurface(M3SurfaceId(1), M3Voxel(0, 0, 0), 0, 192)
        override fun findById(id: M3SurfaceId) = row.takeIf { it.id == id }
        override fun findByVoxel(voxel: M3Voxel) = row.takeIf { it.voxel == voxel }
        override fun readPage(region: M3StorageRegion, page: Int, cursor: Int, limit: Int) = M3CompactPage(emptyList(), null, 0)
        override fun readSourceById(id: M3SurfaceId) = M3CanonicalPageRead.Complete(row.takeIf { it.id == id }?.let { M3PagedSource(it.id, it.voxel, it.packedNormal, it.normalConfidence, M3CanonicalReceiptBytes(ByteArray(32))) }, 0, 0)
        override fun visitSourceSupport(target: M3SurfaceId, cursor: M3SourceSupportCursor?, sink: (M3PagedSupport) -> Boolean) = M3SourceSupportRead.Complete(0, null, 0, 0)
        override fun retainedMemoryReceipt() = error("not used")
        override fun allocatedStorageReceipt() = error("not used")
        override fun close() = Unit
    }

    private open class CountingVisitor : M3PreparedIntentVisitor {
        var rows = 0; var removed = 0; var supports = 0; var sources = 0; var lineage = 0; var terminals = 0
        private var headers = 0
        val callbacks get() = headers + rows + removed + supports + sources + lineage + terminals
        override fun onHeader(identity: M3PreparedIntentIdentity) = true.also { headers++ }
        override fun onDirtyRow(id: Long, x: Int, y: Int, z: Int, packedNormal: Int, confidence: Int, fingerprint0: Long, fingerprint1: Long, fingerprint2: Long, fingerprint3: Long): Boolean { rows++; return true }
        override fun onRemovedId(id: Long): Boolean { removed++; return true }
        override fun onDirtySupport(targetId: Long, sourceId: Long, x: Int, y: Int, z: Int, packedNormal: Int, confidence: Int, fingerprint0: Long, fingerprint1: Long, fingerprint2: Long, fingerprint3: Long): Boolean { supports++; return true }
        override fun onDirtySource(id: Long, x: Int, y: Int, z: Int, packedNormal: Int, confidence: Int, fingerprint0: Long, fingerprint1: Long, fingerprint2: Long, fingerprint3: Long): Boolean { sources++; return true }
        override fun onDirtyLineage(sourceId: Long, targetId: Long): Boolean { lineage++; return true }
        override fun onTerminal(currentReceipt: M3PreparedIntentCurrentReceipt): Boolean { terminals++; return true }
    }
}
