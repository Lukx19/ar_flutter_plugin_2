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
        structuralFault("declared-count") { it.rewriteHeader { header -> header.copy(targetLive = 100_001) } }
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

    private fun intent(directory: File, view: TestView, command: String): M3PreparedIntent {
        val plan = M3SurfaceOwnership.prepareMutation(view, M3SurfaceOwnershipConfiguration(),
            M3FeatureMutationCommand(command, 0, 0, M3CanonicalTarget(M3SurfaceId(1), M3Voxel(0, 0, 0), 0, 0, 191)))
            as M3CanonicalMutationPreparation.Prepared
        val coordinator = StorageBudgetCoordinatorV2(directory, StorageBudgetPolicyV2(8L * 1024 * 1024, 4_096L), JvmDescriptorFilesystemV2(authoritativeAllocationUnit = { 4_096L }), freeBytes = { 16L * 1024 * 1024 })
        val journal = (M3CanonicalDirtyJournal.open(view, directory, M3CoordinatorStorageBudget(coordinator)) as M3CanonicalDirtyJournalOpenResult.Opened).journal
        return (journal.flush(plan.mutation) as M3CanonicalDirtyJournalFlushResult.Prepared).intent
    }

    private fun view(identity: String, rows: Int): TestView {
        val cut = M3CompactCanonicalCut(M3SurfaceGroup(identity), M3CompactCanonicalStore.PROFILE, 0, 0, rows + 1L, rows, rows, rows, 0, null,
            M3CanonicalReceiptBytes(sha256("root-$identity".encodeToByteArray())), M3CanonicalReceiptBytes(sha256("source-$identity".encodeToByteArray())))
        return TestView(cut)
    }

    private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)

    /** Rebuilds a structurally valid test envelope so each fault reaches its named invariant. */
    private class RechecksummedIntentFixture(private val intent: M3PreparedIntent) {
        private var header = M3DirtyIntentHeader.read(intent.file)
        var wal = intent.file.readBytes().copyOfRange(header.walOffset.toInt(), header.walOffset.toInt() + header.walLength.toInt())
            private set
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

        data class WalLayout(val rowCount: Int, val rowStart: Int) {
            companion object {
                fun read(wal: ByteArray): WalLayout {
                    val buffer = ByteBuffer.wrap(wal)
                    var cursor = 8 + 32
                    val commandBytes = buffer.getShort(cursor).toInt() and 0xffff
                    cursor += 2 + commandBytes
                    cursor += 4 + 32 + 32 + 8 + (4 * 4) + 8 + 8
                    return WalLayout(cursor, cursor + 4)
                }
            }
        }

        companion object {
            private const val ROW_BYTES = 60
            private fun digest(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
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
