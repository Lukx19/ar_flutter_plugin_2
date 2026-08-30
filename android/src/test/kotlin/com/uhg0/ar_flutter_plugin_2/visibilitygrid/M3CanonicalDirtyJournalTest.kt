package com.uhg0.ar_flutter_plugin_2.visibilitygrid

import com.uhg0.ar_flutter_plugin_2.capture.JvmDescriptorFilesystemV2
import com.uhg0.ar_flutter_plugin_2.capture.StorageBudgetCoordinatorV2
import com.uhg0.ar_flutter_plugin_2.capture.StorageBudgetPolicyV2
import java.io.ByteArrayOutputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class M3CanonicalDirtyJournalTest {
    @Test
    fun `two independent journals admit one target winner and loser reopens exact intent`() {
        val directory = Files.createTempDirectory("m3-dirty-target-collision-").toFile()
        val executor = Executors.newSingleThreadExecutor()
        val continueWinner = CountDownLatch(1)
        try {
            val view = view("target-collision", rows = 0)
            val plan = prepared(view, add("same"))
            val firstCoordinator = coordinator(directory)
            val secondCoordinator = coordinator(directory)
            val reserved = CountDownLatch(1)
            val blocking = object : M3CanonicalStorageBudget by M3CoordinatorStorageBudget(firstCoordinator) {
                private val delegate = M3CoordinatorStorageBudget(firstCoordinator)
                override fun reserveCandidateExclusive(
                    staging: File, target: File, fileBytes: Map<String, Long>, maximumPhysicalBytes: Long,
                ): M3CanonicalCandidateReservation = delegate.reserveCandidateExclusive(
                    staging, target, fileBytes, maximumPhysicalBytes,
                ).also { admission ->
                    if (admission is M3CanonicalCandidateReservation.Reserved && target.name.endsWith("allocation-1")) {
                        reserved.countDown(); assertTrue(continueWinner.await(10, TimeUnit.SECONDS))
                    }
                }
            }
            val first = opened(M3CanonicalDirtyJournal.open(view, directory, blocking))
            val second = opened(M3CanonicalDirtyJournal.open(view, directory, M3CoordinatorStorageBudget(secondCoordinator)))
            val winner = executor.submit<M3CanonicalDirtyJournalFlushResult> { first.flush(plan) }
            assertTrue(reserved.await(10, TimeUnit.SECONDS))
            val loser = second.flush(plan) as M3CanonicalDirtyJournalFlushResult.Refused
            assertEquals(M3CanonicalDirtyJournalRefusal.TARGET_RESERVED, loser.reason)
            continueWinner.countDown()
            val prepared = winner.get(20, TimeUnit.SECONDS) as M3CanonicalDirtyJournalFlushResult.Prepared
            val replay = second.reopen() as M3CanonicalDirtyJournalReopenResult.Complete
            assertEquals(identity(prepared.intent), identity(replay.intent))
            assertEquals(0L, firstCoordinator.reservedBytes())
            assertEquals(0L, secondCoordinator.reservedBytes())
            val physical = directory.listFiles().orEmpty().filter { it.isDirectory &&
                (it.name.contains(".allocation-") || it.name.endsWith(".intent"))
            }.sumOf(firstCoordinator::physicallyAllocatedTreeBytes)
            assertEquals(physical, firstCoordinator.committedBytes())
            assertEquals(physical, secondCoordinator.committedBytes())
            first.close(); second.close(); firstCoordinator.close(); secondCoordinator.close()
        } finally { continueWinner.countDown(); executor.shutdownNow(); directory.deleteRecursively() }
    }

    @Test
    fun `maximum allocation history reopens and flushes with bounded streaming receipt`() {
        val directory = Files.createTempDirectory("m3-dirty-history-maximum-").toFile()
        try {
            val view = view("history-maximum", rows = 1, high = 100_001L)
            val ledger = M3SurfaceAllocationAuthority.legacyFile(directory, view.cut.group)
            var previous = ByteArray(32)
            BufferedOutputStream(FileOutputStream(ledger), 65_536).use { output ->
                repeat(100_000) { index ->
                    val record = M3AllocationRecord(
                        index + 1L, index + 1L, index + 2L, view.cut.group.hash,
                        sha256("history-command-$index".encodeToByteArray()),
                        sha256("history-fingerprint-$index".encodeToByteArray()), previous,
                    )
                    output.write(record.encoded()); previous = record.recordHash
                }
            }
            val fixture = budgetFixture(directory, quota = 32L * 1024 * 1024, free = 64L * 1024 * 1024)
            val journal = opened(M3CanonicalDirtyJournal.open(view, directory, fixture.budget))
            val reopened = journal.reopen() as M3CanonicalDirtyJournalReopenResult.None
            assertEquals(100_001L, reopened.burnedHighWater)
            val intent = (journal.flush(prepared(view, refine("history-next", M3SurfaceId(1))))
                as M3CanonicalDirtyJournalFlushResult.Prepared).intent
            assertEquals(100_001L, intent.storage.history.records)
            assertEquals(100_001L * M3AllocationRecord.ENCODED_BYTES, intent.storage.history.bytesRead)
            assertTrue(intent.storage.history.phasePeakBytes <= M3CompactCanonicalStore.JOURNAL_RESERVE_BYTES)
            assertTrue(intent.storage.phasePeakBytes <= M3CompactCanonicalStore.JOURNAL_RESERVE_BYTES)
            assertTrue(intent.storage.history.phasePeakBytes < ledger.length())
            assertTrue(directory.walkTopDown().any { it.name == "allocation-checkpoint.bin" })
            intent.close(); journal.close(); fixture.coordinator.close()
            assertTrue(ledger.renameTo(File(directory, "closed-ledger")))
        } finally { directory.deleteRecursively() }
    }

    @Test
    fun `one immutable plan burns its exact allocation and exposes a checksummed file backed intent`() {
        val directory = Files.createTempDirectory("m3-dirty-complete-").toFile()
        try {
            val view = view("complete", rows = 0)
            val plan = prepared(view, add("complete"))
            val authorityRootBefore = view.cut.rootHash.toByteArray()
            val fixture = budgetFixture(directory)
            val journal = opened(M3CanonicalDirtyJournal.open(view, directory, fixture.budget))
            val flushed = journal.flush(plan) as M3CanonicalDirtyJournalFlushResult.Prepared
            val flushedIdentity = identity(flushed.intent)

            assertEquals(plan.sourceCut, flushedIdentity.sourceCut)
            assertEquals(plan.targetHighWater, flushedIdentity.targetHighWater)
            assertTrue(flushed.intent.file.isFile)
            assertTrue(flushed.intent.file.length() <= M3CompactCanonicalStore.JOURNAL_RESERVE_BYTES)
            assertEquals(plan.work.walBytes.toLong(), flushedIdentity.walReceipt.length)
            assertEquals(sha256(wal(plan)).toList(), flushedIdentity.walReceipt.hash.toByteArray().toList())
            assertEquals(0, view.mutations)
            assertArrayEquals(authorityRootBefore, view.cut.rootHash.toByteArray())
            val reopened = journal.reopen() as M3CanonicalDirtyJournalReopenResult.Complete
            assertEquals(flushedIdentity.walReceipt, identity(reopened.intent).walReceipt)
            assertTrue(reopened.intent.storage.totalAllocatedBytes > 0)
            assertTrue(fixture.budget.actuals.zip(fixture.budget.requests).all { (actual, requested) -> actual in 1..requested })
            assertEquals(flushed.intent.storage.totalAllocatedBytes, fixture.coordinator.committedBytes())
            assertEquals(0L, fixture.coordinator.reservedBytes())

            // The same real adapter refuses at the exact quota and free-floor inequalities.
            val requested = requireNotNull(fixture.budget.requests.firstOrNull())
            listOf(false, true).forEachIndexed { index, floorCase ->
                val refusedDirectory = Files.createTempDirectory("m3-dirty-budget-$index").toFile()
                try {
                    val refusalFixture = if (floorCase) budgetFixture(
                        refusedDirectory, quota = requested, floor = 4_096L, free = requested + 4_095L,
                    ) else budgetFixture(refusedDirectory, quota = requested - 1L)
                    val refusedView = view("budget-$index", rows = 0)
                    val result = opened(M3CanonicalDirtyJournal.open(refusedView, refusedDirectory, refusalFixture.budget))
                        .flush(prepared(refusedView, add("budget-$index")))
                    assertEquals(M3CanonicalDirtyJournalRefusal.DURABILITY_FAILURE,
                        (result as M3CanonicalDirtyJournalFlushResult.Refused).reason)
                    assertEquals(0L, refusalFixture.coordinator.committedBytes())
                    assertEquals(0L, refusalFixture.coordinator.reservedBytes())
                } finally { refusedDirectory.deleteRecursively() }
            }

            val orphanDirectory = Files.createTempDirectory("m3-dirty-orphan").toFile()
            try {
                val orphanView = view("orphan", rows = 0)
                val orphanFixture = budgetFixture(orphanDirectory)
                val base = "m3-canonical-v6-${orphanView.cut.group.hash.hex()}"
                val staging = File(orphanDirectory, "$base.staging-allocation-orphan")
                val target = File(orphanDirectory, "$base.allocation-1")
                assertTrue(orphanFixture.coordinator.reserveCandidate(
                    "m3:canonical:v6-migration", staging, target,
                    mapOf("allocation-record.bin" to M3AllocationRecord.ENCODED_BYTES.toLong()), 20_480L,
                ) != null)
                assertTrue(staging.isDirectory)
                orphanFixture.coordinator.close()
                val recoveredFixture = budgetFixture(orphanDirectory)
                assertTrue(M3CanonicalDirtyJournal.open(orphanView, orphanDirectory, recoveredFixture.budget)
                    is M3CanonicalDirtyJournalOpenResult.Opened)
                assertEquals(0L, recoveredFixture.coordinator.reservedBytes())
                assertFalse(staging.exists())
            } finally { orphanDirectory.deleteRecursively() }
        } finally { directory.deleteRecursively() }
    }

    @Test
    fun `every physical cut has its own recovery state and allocation authority outcome`() {
        M3CanonicalDirtyJournalFault.entries.forEach { fault ->
            val directory = Files.createTempDirectory("m3-dirty-fault-${fault.name}").toFile()
            try {
                val view = view("fault-${fault.name}", rows = 0)
                val storage = budgetFixture(directory).budget
                val result = opened(M3CanonicalDirtyJournal.open(view, directory, storage)).flush(prepared(view, add("fault")), fault)
                assertTrue(result is M3CanonicalDirtyJournalFlushResult.Refused)
                val recovery = budgetFixture(directory)
                val reopened = opened(M3CanonicalDirtyJournal.open(view, directory, recovery.budget)).reopen()
                assertFalse(reopened is M3CanonicalDirtyJournalReopenResult.Refused && reopened.reason == M3CanonicalDirtyJournalRefusal.CORRUPT_LEDGER)
                val high = when (reopened) {
                    is M3CanonicalDirtyJournalReopenResult.None -> reopened.burnedHighWater
                    is M3CanonicalDirtyJournalReopenResult.Complete -> identity(reopened.intent).targetHighWater
                    is M3CanonicalDirtyJournalReopenResult.Refused -> reopened.burnedHighWater
                }
                val allocationPublished = fault.ordinal >= M3CanonicalDirtyJournalFault.AFTER_ALLOCATION_PUBLISH.ordinal
                assertEquals(if (allocationPublished) 2L else 1L, high)
                val intentPublished = fault.ordinal >= M3CanonicalDirtyJournalFault.AFTER_INTENT_PUBLISH.ordinal &&
                    fault != M3CanonicalDirtyJournalFault.DURING_WAL_WRITE
                assertEquals(intentPublished, reopened is M3CanonicalDirtyJournalReopenResult.Complete)
                assertEquals(0L, recovery.coordinator.reservedBytes())
                assertTrue(directory.listFiles().orEmpty().none { it.name.contains(".staging-") })
                if (allocationPublished && fault.ordinal <= M3CanonicalDirtyJournalFault.AFTER_ALLOCATION_BUDGET_COMMIT.ordinal) {
                    val switched = TestView(
                        view.cut.copy(rootHash = M3CanonicalReceiptBytes(sha256("switched".encodeToByteArray()))), 0, 0,
                    )
                    assertTrue(M3CanonicalDirtyJournal.open(switched, directory, recovery.budget) is M3CanonicalDirtyJournalOpenResult.Opened)
                }
                assertEquals(0, view.mutations)
            } finally { directory.deleteRecursively() }
        }
    }

    @Test
    fun `truncated or corrupt pre root intent is refused while the durable allocation burn remains`() {
        listOf(false, true).forEachIndexed { index, truncate ->
            val directory = Files.createTempDirectory("m3-dirty-corrupt-$index").toFile()
            try {
                val view = view("corrupt-$index", rows = 0)
                val journal = opened(M3CanonicalDirtyJournal.open(view, directory, budget(directory)))
                val intent = (journal.flush(prepared(view, add("corrupt"))) as M3CanonicalDirtyJournalFlushResult.Prepared).intent.file
                if (truncate) intent.writeBytes(intent.readBytes().copyOf(13))
                else intent.writeBytes(intent.readBytes().also { it[17] = (it[17].toInt() xor 0x55).toByte() })

                val reopened = opened(M3CanonicalDirtyJournal.open(view, directory, budget(directory))).reopen()
                    as M3CanonicalDirtyJournalReopenResult.Refused
                assertEquals(M3CanonicalDirtyJournalRefusal.CORRUPT_INTENT, reopened.reason)
                assertEquals(2L, reopened.burnedHighWater)
                assertEquals(0, view.mutations)
            } finally { directory.deleteRecursively() }
        }
    }

    @Test
    fun `empty and UInt32 allocation cuts remain legal while later allocation is unavailable`() {
        val emptyDirectory = Files.createTempDirectory("m3-dirty-empty-").toFile()
        val maximumDirectory = Files.createTempDirectory("m3-dirty-u32-").toFile()
        try {
            val empty = view("empty", rows = 1, high = 2)
            val emptyPlan = prepared(empty, refine("empty", M3SurfaceId(1)))
            val emptyIntent = opened(M3CanonicalDirtyJournal.open(empty, emptyDirectory, budget(emptyDirectory))).flush(emptyPlan)
                as M3CanonicalDirtyJournalFlushResult.Prepared
            assertEquals(2L, identity(emptyIntent.intent).targetHighWater)

            val zeroOne = M3AllocationRecord(1, 2, 2, empty.cut.group.hash, sha256("z1".encodeToByteArray()), sha256("f1".encodeToByteArray()), ByteArray(32))
            val zeroTwo = M3AllocationRecord(2, 2, 2, empty.cut.group.hash, sha256("z2".encodeToByteArray()), sha256("f2".encodeToByteArray()), zeroOne.recordHash)
            val zeroChain = M3SurfaceAllocationAuthority.validate(empty.cut.group, listOf(zeroOne, zeroTwo), 2)
            assertEquals(2L, zeroChain.highWater)
            assertEquals(2L, zeroChain.lastRevision)

            val maximum = view("maximum", rows = 0, high = 0xffff_ffffL)
            val maximumPlan = prepared(maximum, add("maximum"))
            val maximumIntent = opened(M3CanonicalDirtyJournal.open(maximum, maximumDirectory, budget(maximumDirectory))).flush(maximumPlan)
                as M3CanonicalDirtyJournalFlushResult.Prepared
            assertEquals(0x1_0000_0000L, identity(maximumIntent.intent).targetHighWater)
        } finally { emptyDirectory.deleteRecursively(); maximumDirectory.deleteRecursively() }
    }

    @Test
    fun `small and 100k authority plans retain equal fixed ledger and WAL work`() {
        val smallDirectory = Files.createTempDirectory("m3-dirty-small-").toFile()
        val largeDirectory = Files.createTempDirectory("m3-dirty-large-").toFile()
        try {
            val small = view("small", rows = 1)
            val large = view("large", rows = 100_000)
            val smallPlan = prepared(small, refine("same", M3SurfaceId(1)))
            val largePlan = prepared(large, refine("same", M3SurfaceId(1)))
            val smallFixture = budgetFixture(smallDirectory)
            val largeFixture = budgetFixture(largeDirectory)
            val smallIntent = (opened(M3CanonicalDirtyJournal.open(small, smallDirectory, smallFixture.budget)).flush(smallPlan) as M3CanonicalDirtyJournalFlushResult.Prepared).intent
            val largeIntent = (opened(M3CanonicalDirtyJournal.open(large, largeDirectory, largeFixture.budget)).flush(largePlan) as M3CanonicalDirtyJournalFlushResult.Prepared).intent
            assertEquals(smallPlan.work.walBytes, largePlan.work.walBytes)
            assertEquals(identity(smallIntent).walReceipt.length, identity(largeIntent).walReceipt.length)
            assertEquals(smallIntent.storage.ledgerAllocatedBytes, largeIntent.storage.ledgerAllocatedBytes)
            assertEquals(smallIntent.storage.intentAllocatedBytes, largeIntent.storage.intentAllocatedBytes)
            assertEquals(65_536, M3CanonicalDirtyJournal.WRITER_SCRATCH_BYTES)
            assertTrue(largeIntent.file.length() <= M3CompactCanonicalStore.JOURNAL_RESERVE_BYTES)
            println(
                "M3_CANONICAL_DIRTY_JOURNAL_RECEIPT wal=${identity(largeIntent).walReceipt.length} " +
                    "ledgerAllocated=${largeIntent.storage.ledgerAllocatedBytes} " +
                    "intentAllocated=${largeIntent.storage.intentAllocatedBytes} " +
                    "candidateRequests=${largeFixture.budget.requests.joinToString(",")} " +
                    "candidateActuals=${largeFixture.budget.actuals.distinct().joinToString(",")} " +
                    "writerScratch=${M3CanonicalDirtyJournal.WRITER_SCRATCH_BYTES} " +
                    "reserve=${M3CompactCanonicalStore.JOURNAL_RESERVE_BYTES}",
            )
        } finally { smallDirectory.deleteRecursively(); largeDirectory.deleteRecursively() }
    }

    @Test
    fun `maximum accepted predecessor plan plus intent encoding and writer scratch stays inside one MiB`() {
        val directory = Files.createTempDirectory("m3-dirty-maximum-").toFile()
        try {
            val view = view("maximum-intent", rows = 1, sourceCount = 8_192, supportCount = 8_192)
            val command = M3CanonicalTransactionCommand(
                "maximum-intent", M3CanonicalOperation.RELOCATION, 0, 0, listOf(M3SurfaceId(1)),
                listOf(target(M3SurfaceId(1), 191)),
            )
            val plan = (M3SurfaceOwnership.prepareMutation(view, M3SurfaceOwnershipConfiguration(), command)
                as M3CanonicalMutationPreparation.Prepared).mutation
            val fixture = budgetFixture(directory)
            val intent = (opened(M3CanonicalDirtyJournal.open(view, directory, fixture.budget)).flush(plan)
                as M3CanonicalDirtyJournalFlushResult.Prepared).intent
            assertEquals(8_192, plan.work.dirtySupportRecords)
            assertTrue(plan.work.constructionPeakBytes <= M3CompactCanonicalStore.JOURNAL_RESERVE_BYTES)
            assertTrue(intent.file.length() <= M3CompactCanonicalStore.JOURNAL_RESERVE_BYTES)
            assertTrue(plan.work.retainedPlanBytes + M3CanonicalDirtyJournal.WRITER_SCRATCH_BYTES <= M3CompactCanonicalStore.JOURNAL_RESERVE_BYTES)
            assertTrue(fixture.budget.requests.all { it <= M3CompactCanonicalStore.JOURNAL_RESERVE_BYTES })
            println(
                "M3_CANONICAL_DIRTY_JOURNAL_MAX wal=${plan.work.walBytes} current=${plan.work.currentBytes} " +
                    "planRetained=${plan.work.retainedPlanBytes} planPeak=${plan.work.constructionPeakBytes} " +
                    "intentLogical=${intent.file.length()} writerScratch=${M3CanonicalDirtyJournal.WRITER_SCRATCH_BYTES} " +
                    "candidateRequests=${fixture.budget.requests.joinToString(",")} " +
                    "candidateActuals=${fixture.budget.actuals.distinct().joinToString(",")} " +
                    "reserve=${M3CompactCanonicalStore.JOURNAL_RESERVE_BYTES}",
            )
        } finally { directory.deleteRecursively() }
    }

    private fun add(command: String) = M3FeatureMutationCommand(command, 0, 0, target(id = null))
    private fun refine(command: String, id: M3SurfaceId) = M3FeatureMutationCommand(command, 0, 0, target(id, 191))
    private fun target(id: M3SurfaceId?, confidence: Int = 192) = M3CanonicalTarget(id, M3Voxel(0, 0, 0), 0, 0, confidence)
    private fun prepared(view: TestView, command: M3FeatureMutationCommand) =
        (M3SurfaceOwnership.prepareMutation(view, M3SurfaceOwnershipConfiguration(), command) as M3CanonicalMutationPreparation.Prepared).mutation
    private fun wal(plan: M3PreparedCanonicalMutation) = ByteArrayOutputStream().also(plan::writeWalTo).toByteArray()
    private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
    private fun identity(intent: M3PreparedIntent) =
        (intent.identity() as M3PreparedIntentIdentityResult.Complete).identity
    private fun ByteArray.hex() = joinToString("") { "%02x".format(it) }
    private fun opened(result: M3CanonicalDirtyJournalOpenResult) = (result as M3CanonicalDirtyJournalOpenResult.Opened).journal
    private fun budget(directory: File): M3CanonicalStorageBudget = budgetFixture(directory).budget
    private fun budgetFixture(
        directory: File,
        quota: Long = 8L * 1024 * 1024,
        floor: Long = 4_096L,
        free: Long = 16L * 1024 * 1024,
    ): BudgetFixture {
        val coordinator = StorageBudgetCoordinatorV2(
            directory,
            StorageBudgetPolicyV2(quota, floor),
            JvmDescriptorFilesystemV2(authoritativeAllocationUnit = { 4_096L }),
            freeBytes = { free },
        )
        return BudgetFixture(coordinator, RecordingBudget(M3CoordinatorStorageBudget(coordinator)))
    }

    private fun coordinator(directory: File) = StorageBudgetCoordinatorV2(
        directory,
        StorageBudgetPolicyV2(8L * 1024 * 1024, 4_096L),
        JvmDescriptorFilesystemV2(authoritativeAllocationUnit = { 4_096L }),
        freeBytes = { 16L * 1024 * 1024 },
    )

    private data class BudgetFixture(val coordinator: StorageBudgetCoordinatorV2, val budget: RecordingBudget)
    private class RecordingBudget(private val delegate: M3CanonicalStorageBudget) : M3CanonicalStorageBudget by delegate {
        val requests = mutableListOf<Long>()
        val actuals = mutableListOf<Long>()
        override fun reserveCandidate(staging: File, target: File, fileBytes: Map<String, Long>, maximumPhysicalBytes: Long): Any? {
            requests += maximumPhysicalBytes
            return delegate.reserveCandidate(staging, target, fileBytes, maximumPhysicalBytes)
        }
        override fun reserveCandidateExclusive(
            staging: File,
            target: File,
            fileBytes: Map<String, Long>,
            maximumPhysicalBytes: Long,
        ): M3CanonicalCandidateReservation {
            requests += maximumPhysicalBytes
            return delegate.reserveCandidateExclusive(staging, target, fileBytes, maximumPhysicalBytes)
        }
        override fun verifyCandidate(token: Any, candidate: File): Long = delegate.verifyCandidate(token, candidate).also { actuals += it }
    }

    private fun view(identity: String, rows: Int, high: Long = rows + 1L, sourceCount: Int = rows, supportCount: Int = rows): TestView {
        val group = M3SurfaceGroup(identity)
        val cut = M3CompactCanonicalCut(
            group, M3CompactCanonicalStore.PROFILE, 0, 0, high, rows, sourceCount, supportCount, 0, null,
            M3CanonicalReceiptBytes(sha256("root-$identity".encodeToByteArray())),
            M3CanonicalReceiptBytes(sha256("source-$identity".encodeToByteArray())),
        )
        return TestView(cut, rows, supportCount)
    }

    private class TestView(override val cut: M3CompactCanonicalCut, rows: Int, private val supportCount: Int) : M3CanonicalStateView {
        private val row = if (rows > 0) M3CompactSurface(M3SurfaceId(1), M3Voxel(0, 0, 0), 0, 192) else null
        var mutations = 0
        override fun findById(id: M3SurfaceId) = row?.takeIf { it.id == id }
        override fun findByVoxel(voxel: M3Voxel) = row?.takeIf { it.voxel == voxel }
        override fun readPage(region: M3StorageRegion, page: Int, cursor: Int, limit: Int) = M3CompactPage(emptyList(), null, 0)
        override fun readSourceById(id: M3SurfaceId) = M3CanonicalPageRead.Complete(
            row?.takeIf { it.id == id }?.let {
                M3PagedSource(it.id, it.voxel, it.packedNormal, it.normalConfidence, M3CanonicalReceiptBytes(ByteArray(32)))
            },
            0,
            0,
        )
        override fun visitSourceSupport(target: M3SurfaceId, cursor: M3SourceSupportCursor?, sink: (M3PagedSupport) -> Boolean): M3SourceSupportRead {
            repeat(supportCount) { index ->
                if (!sink(M3PagedSupport(target, M3PagedSource(M3SurfaceId(index + 1L), M3Voxel(index, 0, 0), 0, 192, M3CanonicalReceiptBytes(ByteArray(32))))))
                    return M3SourceSupportRead.Complete(index, null, 0, 0)
            }
            return M3SourceSupportRead.Complete(supportCount, null, 0, 0)
        }
        override fun retainedMemoryReceipt() = error("not used")
        override fun allocatedStorageReceipt() = error("not used")
        override fun close() = Unit
    }
}
