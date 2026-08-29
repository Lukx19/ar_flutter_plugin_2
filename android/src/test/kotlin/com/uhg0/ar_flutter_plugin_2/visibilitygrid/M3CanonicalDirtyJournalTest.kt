package com.uhg0.ar_flutter_plugin_2.visibilitygrid

import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class M3CanonicalDirtyJournalTest {
    @Test
    fun `one immutable plan burns its exact allocation and exposes a checksummed file backed intent`() {
        val directory = Files.createTempDirectory("m3-dirty-complete-").toFile()
        try {
            val view = view("complete", rows = 0)
            val plan = prepared(view, add("complete"))
            val authorityRootBefore = view.cut.rootHash.toByteArray()
            val journal = opened(M3CanonicalDirtyJournal.open(view, directory, budget()))
            val flushed = journal.flush(plan) as M3CanonicalDirtyJournalFlushResult.Prepared

            assertEquals(plan.sourceCut, flushed.intent.sourceCut)
            assertEquals(plan.targetHighWater, flushed.intent.targetHighWater)
            assertTrue(flushed.intent.file.isFile)
            assertTrue(flushed.intent.file.length() <= M3CompactCanonicalStore.JOURNAL_RESERVE_BYTES)
            assertEquals(plan.work.walBytes.toLong(), flushed.intent.openWal().use { it.copyTo(ByteArrayOutputStream()) })
            assertEquals(sha256(wal(plan)).toList(), flushed.intent.walHash.toByteArray().toList())
            assertEquals(0, view.mutations)
            assertArrayEquals(authorityRootBefore, view.cut.rootHash.toByteArray())
            val reopened = journal.reopen() as M3CanonicalDirtyJournalReopenResult.Complete
            assertEquals(flushed.intent.walHash, reopened.intent.walHash)
            assertTrue(reopened.intent.storage.totalAllocatedBytes > 0)
        } finally { directory.deleteRecursively() }
    }

    @Test
    fun `every post reservation fault preserves the burned high water without publishing a root`() {
        val faults = M3CanonicalDirtyJournalFault.entries - M3CanonicalDirtyJournalFault.BEFORE_RESERVATION
        faults.forEach { fault ->
            val directory = Files.createTempDirectory("m3-dirty-fault-${fault.name}").toFile()
            try {
                val view = view("fault-${fault.name}", rows = 0)
                val result = opened(M3CanonicalDirtyJournal.open(view, directory, budget())).flush(prepared(view, add("fault")), fault)
                assertTrue(result is M3CanonicalDirtyJournalFlushResult.Prepared || result is M3CanonicalDirtyJournalFlushResult.Refused)
                val reopened = opened(M3CanonicalDirtyJournal.open(view, directory, budget())).reopen()
                assertFalse(reopened is M3CanonicalDirtyJournalReopenResult.Refused && reopened.reason == M3CanonicalDirtyJournalRefusal.CORRUPT_LEDGER)
                val high = when (reopened) {
                    is M3CanonicalDirtyJournalReopenResult.None -> reopened.burnedHighWater
                    is M3CanonicalDirtyJournalReopenResult.Complete -> reopened.intent.targetHighWater
                    is M3CanonicalDirtyJournalReopenResult.Refused -> reopened.burnedHighWater
                }
                assertEquals(2L, high)
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
                val journal = opened(M3CanonicalDirtyJournal.open(view, directory, budget()))
                val intent = (journal.flush(prepared(view, add("corrupt"))) as M3CanonicalDirtyJournalFlushResult.Prepared).intent.file
                if (truncate) intent.writeBytes(intent.readBytes().copyOf(13))
                else intent.writeBytes(intent.readBytes().also { it[17] = (it[17].toInt() xor 0x55).toByte() })

                val reopened = opened(M3CanonicalDirtyJournal.open(view, directory, budget())).reopen()
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
            val emptyIntent = opened(M3CanonicalDirtyJournal.open(empty, emptyDirectory, budget())).flush(emptyPlan)
                as M3CanonicalDirtyJournalFlushResult.Prepared
            assertEquals(2L, emptyIntent.intent.targetHighWater)

            val maximum = view("maximum", rows = 0, high = 0xffff_ffffL)
            val maximumPlan = prepared(maximum, add("maximum"))
            val maximumIntent = opened(M3CanonicalDirtyJournal.open(maximum, maximumDirectory, budget())).flush(maximumPlan)
                as M3CanonicalDirtyJournalFlushResult.Prepared
            assertEquals(0x1_0000_0000L, maximumIntent.intent.targetHighWater)
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
            val smallIntent = (opened(M3CanonicalDirtyJournal.open(small, smallDirectory, budget())).flush(smallPlan) as M3CanonicalDirtyJournalFlushResult.Prepared).intent
            val largeIntent = (opened(M3CanonicalDirtyJournal.open(large, largeDirectory, budget())).flush(largePlan) as M3CanonicalDirtyJournalFlushResult.Prepared).intent
            assertEquals(smallPlan.work.walBytes, largePlan.work.walBytes)
            assertEquals(smallIntent.walLength, largeIntent.walLength)
            assertEquals(smallIntent.storage.ledgerAllocatedBytes, largeIntent.storage.ledgerAllocatedBytes)
            assertEquals(smallIntent.storage.intentAllocatedBytes, largeIntent.storage.intentAllocatedBytes)
            assertEquals(65_536, M3CanonicalDirtyJournal.WRITER_SCRATCH_BYTES)
            assertTrue(largeIntent.file.length() <= M3CompactCanonicalStore.JOURNAL_RESERVE_BYTES)
            println(
                "M3_CANONICAL_DIRTY_JOURNAL_RECEIPT wal=${largeIntent.walLength} " +
                    "ledgerAllocated=${largeIntent.storage.ledgerAllocatedBytes} " +
                    "intentAllocated=${largeIntent.storage.intentAllocatedBytes} " +
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
            val intent = (opened(M3CanonicalDirtyJournal.open(view, directory, budget())).flush(plan)
                as M3CanonicalDirtyJournalFlushResult.Prepared).intent
            assertEquals(8_192, plan.work.dirtySupportRecords)
            assertTrue(plan.work.constructionPeakBytes <= M3CompactCanonicalStore.JOURNAL_RESERVE_BYTES)
            assertTrue(intent.file.length() <= M3CompactCanonicalStore.JOURNAL_RESERVE_BYTES)
            assertTrue(plan.work.retainedPlanBytes + M3CanonicalDirtyJournal.WRITER_SCRATCH_BYTES <= M3CompactCanonicalStore.JOURNAL_RESERVE_BYTES)
            println(
                "M3_CANONICAL_DIRTY_JOURNAL_MAX wal=${plan.work.walBytes} current=${plan.work.currentBytes} " +
                    "planRetained=${plan.work.retainedPlanBytes} planPeak=${plan.work.constructionPeakBytes} " +
                    "intentLogical=${intent.file.length()} writerScratch=${M3CanonicalDirtyJournal.WRITER_SCRATCH_BYTES} " +
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
    private fun opened(result: M3CanonicalDirtyJournalOpenResult) = (result as M3CanonicalDirtyJournalOpenResult.Opened).journal
    private fun budget() = object : M3CanonicalStorageBudget {
        override fun reserve(bytes: Long): Any = bytes
        override fun commit(token: Any, actualBytes: Long) = Unit
        override fun release(token: Any) = Unit
        override fun allocationUnitBytes(path: File) = 4_096L
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
