package com.uhg0.ar_flutter_plugin_2.visibilitygrid

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.security.DigestInputStream
import java.security.DigestOutputStream

/**
 * The deliberately pre-root half of M3 persistence.
 *
 * This module has one deep interface: it burns a prepared plan's projected ID
 * range and leaves behind a self-validating, file-backed intent.  It does not
 * own a mutable canonical view, a root switch, replay/ACK, or activation.  In
 * particular, a complete intent is evidence for a later owner, never a new
 * semantic authority.
 */
internal class M3CanonicalDirtyJournal private constructor(
    private val group: M3SurfaceGroup,
    private val authority: M3CompactCanonicalCut,
    private val directory: File,
    private val budget: M3CanonicalStorageBudget,
    private var burnedHighWater: Long,
) : AutoCloseable {
    private var closed = false

    @Synchronized
    fun flush(
        plan: M3PreparedCanonicalMutation,
        fault: M3CanonicalDirtyJournalFault? = null,
    ): M3CanonicalDirtyJournalFlushResult {
        if (closed) return refused(M3CanonicalDirtyJournalRefusal.CLOSED)
        if (reopen() is M3CanonicalDirtyJournalReopenResult.Complete)
            return refused(M3CanonicalDirtyJournalRefusal.INTENT_EXISTS)
        if (!matchesAuthority(plan.sourceCut) || !validRange(plan))
            return refused(M3CanonicalDirtyJournalRefusal.STALE_OR_INVALID_PLAN)
        if (fault == M3CanonicalDirtyJournalFault.BEFORE_RESERVATION)
            return refused(M3CanonicalDirtyJournalRefusal.DURABILITY_FAILURE)

        val ledger = M3DirtyAllocationLedger(
            group, authority, burnedHighWater, plan.targetHighWater,
            plan.commandHash, plan.commandFingerprint,
        )
        val ledgerTarget = File(directory, "allocation-${ledger.endExclusive}.m3")
        if (ledgerTarget.exists()) return refused(M3CanonicalDirtyJournalRefusal.CORRUPT_LEDGER)

        // This candidate *is* the reservation backing.  It is promoted before
        // intent encoding, so every later failure retains the burned range.
        val ledgerResult = publishCandidate(
            ledgerTarget,
            mapOf(LEDGER_FILE to ledger.encodedBytes.toLong()),
        ) { staged ->
            writeFullySynced(File(staged, LEDGER_FILE), ledger.encoded)
        }
        if (!ledgerResult) return refused(M3CanonicalDirtyJournalRefusal.DURABILITY_FAILURE)
        burnedHighWater = ledger.endExclusive
        if (fault in setOf(
                M3CanonicalDirtyJournalFault.AFTER_LEDGER_RESERVATION,
                M3CanonicalDirtyJournalFault.BEFORE_LEDGER_WRITE,
                M3CanonicalDirtyJournalFault.AFTER_LEDGER_SYNC,
                M3CanonicalDirtyJournalFault.AFTER_LEDGER_PUBLISH,
            ))
            return refused(M3CanonicalDirtyJournalRefusal.DURABILITY_FAILURE)

        val intentTarget = File(directory, INTENT_DIRECTORY)
        if (intentTarget.exists()) return refused(M3CanonicalDirtyJournalRefusal.INTENT_EXISTS)
        val header = M3DirtyIntentHeader.from(plan, authority)
        val logicalBytes = header.encodedBytes(plan.work.walBytes)
        if (logicalBytes > M3CompactCanonicalStore.JOURNAL_RESERVE_BYTES)
            return refused(M3CanonicalDirtyJournalRefusal.INTENT_TOO_LARGE)
        if (fault == M3CanonicalDirtyJournalFault.AFTER_INTENT_RESERVATION)
            return refused(M3CanonicalDirtyJournalRefusal.DURABILITY_FAILURE)

        val intentResult = publishCandidate(
            intentTarget,
            mapOf(INTENT_FILE to logicalBytes),
        ) { staged ->
            if (fault == M3CanonicalDirtyJournalFault.BEFORE_INTENT_WRITE) error("fault")
            writeIntent(File(staged, INTENT_FILE), plan, header, fault)
            if (fault == M3CanonicalDirtyJournalFault.AFTER_INTENT_SYNC) error("fault")
        }
        if (!intentResult) return refused(M3CanonicalDirtyJournalRefusal.DURABILITY_FAILURE)
        if (fault == M3CanonicalDirtyJournalFault.AFTER_INTENT_PUBLISH)
            return refused(M3CanonicalDirtyJournalRefusal.DURABILITY_FAILURE)
        return M3CanonicalDirtyJournalFlushResult.Prepared(
            M3PreparedIntent(File(intentTarget, INTENT_FILE), header, physicalReceipt()),
        )
    }

    @Synchronized
    fun reopen(): M3CanonicalDirtyJournalReopenResult {
        if (closed) return M3CanonicalDirtyJournalReopenResult.Refused(M3CanonicalDirtyJournalRefusal.CLOSED, burnedHighWater)
        val intent = File(directory, "$INTENT_DIRECTORY/$INTENT_FILE")
        if (!intent.exists()) return M3CanonicalDirtyJournalReopenResult.None(burnedHighWater)
        return try {
            val header = M3DirtyIntentHeader.read(intent)
            if (!header.matches(authority) || header.targetHighWater > burnedHighWater)
                M3CanonicalDirtyJournalReopenResult.Refused(M3CanonicalDirtyJournalRefusal.CORRUPT_INTENT, burnedHighWater)
            else M3CanonicalDirtyJournalReopenResult.Complete(M3PreparedIntent(intent, header, physicalReceipt()))
        } catch (_: Exception) {
            // Never try to infer semantic data from a damaged pre-root file.
            M3CanonicalDirtyJournalReopenResult.Refused(M3CanonicalDirtyJournalRefusal.CORRUPT_INTENT, burnedHighWater)
        }
    }

    @Synchronized
    override fun close() { closed = true }

    private fun matchesAuthority(cut: M3CompactCanonicalCut) = cut == authority && cut.group == group

    private fun validRange(plan: M3PreparedCanonicalMutation): Boolean =
        plan.targetHighWater >= burnedHighWater &&
            plan.targetHighWater <= UINT32_HIGH_WATER &&
            plan.sourceCut.nextSurfaceIdHighWater == burnedHighWater

    private fun refused(reason: M3CanonicalDirtyJournalRefusal) =
        M3CanonicalDirtyJournalFlushResult.Refused(reason, burnedHighWater, physicalReceipt())

    private fun physicalReceipt(): M3CanonicalDirtyJournalStorageReceipt {
        val ledger = directory.listFiles { file -> file.name.startsWith("allocation-") && file.name.endsWith(".m3") }
            .orEmpty().sumOf { budget.allocatedBytes(it) }
        val intent = File(directory, INTENT_DIRECTORY).takeIf(File::exists)?.let(budget::allocatedBytes) ?: 0L
        return M3CanonicalDirtyJournalStorageReceipt(ledger, intent, Math.addExact(ledger, intent))
    }

    /** Writes into one physically backed candidate, then moves it once. */
    private fun publishCandidate(
        target: File,
        files: Map<String, Long>,
        writer: (File) -> Unit,
    ): Boolean {
        var token: Any? = null
        var staging: File? = null
        var published = false
        try {
            staging = File(directory, ".${target.name}.staging-${Thread.currentThread().name.hashCode()}-${System.nanoTime()}")
            val unit = budget.allocationUnitBytes(directory)
            val worst = files.values.fold(0L) { total, bytes -> Math.addExact(total, roundPhysical(bytes, unit)) }
            token = budget.reserveCandidate(staging, target, files, worst) ?: return false
            writer(staging)
            budget.verifyCandidate(requireNotNull(token), staging)
            syncDirectory(staging)
            budget.publishCandidate(requireNotNull(token), staging, target)
            published = true
            syncDirectory(directory)
            budget.commit(requireNotNull(token), budget.allocatedBytes(target))
            token = null
            return true
        } catch (_: Exception) {
            return false
        } finally {
            token?.let { reservation ->
                try {
                    if (published || target.exists()) budget.commit(reservation, budget.allocatedBytes(target))
                    else staging?.let { budget.releaseCandidate(reservation, it) } ?: budget.release(reservation)
                } catch (_: Exception) { }
            }
        }
    }

    private fun writeIntent(
        file: File,
        plan: M3PreparedCanonicalMutation,
        header: M3DirtyIntentHeader,
        fault: M3CanonicalDirtyJournalFault?,
    ) {
        FileOutputStream(file).use { raw ->
            val digest = MessageDigest.getInstance("SHA-256")
            val hashed = DigestOutputStream(BufferedOutputStream(raw, WRITER_SCRATCH_BYTES), digest)
            val out = DataOutputStream(hashed)
            header.writeWithoutChecksum(out)
            val wal = FaultingOutputStream(out, fault)
            plan.writeWalTo(wal)
            require(wal.count == plan.work.walBytes.toLong())
            out.flush()
            val checksum = digest.digest()
            require(checksum.size == HASH_BYTES)
            raw.write(checksum)
            raw.fd.sync()
        }
        require(file.length() <= M3CompactCanonicalStore.JOURNAL_RESERVE_BYTES)
    }

    companion object {
        private const val UINT32_HIGH_WATER = 0x1_0000_0000L
        private const val HASH_BYTES = 32
        internal const val WRITER_SCRATCH_BYTES = 65_536
        private const val LEDGER_FILE = "ledger.bin"
        private const val INTENT_DIRECTORY = "intent.m3"
        private const val INTENT_FILE = "intent.bin"

        fun open(
            authority: M3CanonicalStateView,
            directory: File,
            budget: M3CanonicalStorageBudget,
        ): M3CanonicalDirtyJournalOpenResult = open(authority.cut, directory, budget)

        fun open(
            authority: M3CompactCanonicalCut,
            directory: File,
            budget: M3CanonicalStorageBudget,
        ): M3CanonicalDirtyJournalOpenResult = try {
            require(directory.exists() || directory.mkdirs())
            val ledgers = directory.listFiles { file -> file.name.startsWith("allocation-") && file.name.endsWith(".m3") }
                .orEmpty().sortedBy { it.name }
            var high = authority.nextSurfaceIdHighWater
            for (file in ledgers) {
                val ledger = M3DirtyAllocationLedger.read(File(file, LEDGER_FILE))
                require(ledger.groupHash.contentEquals(authority.group.hash))
                require(ledger.authorityRootHash == authority.rootHash)
                require(ledger.start == high && ledger.endExclusive in high..UINT32_HIGH_WATER)
                high = ledger.endExclusive
            }
            M3CanonicalDirtyJournalOpenResult.Opened(M3CanonicalDirtyJournal(authority.group, authority, directory, budget, high))
        } catch (_: Exception) {
            M3CanonicalDirtyJournalOpenResult.Refused(M3CanonicalDirtyJournalRefusal.CORRUPT_LEDGER)
        }

        private fun roundPhysical(bytes: Long, unit: Long): Long {
            require(bytes >= 0 && unit > 0)
            return if (bytes == 0L) 0L else Math.multiplyExact((bytes - 1L) / unit + 1L, unit)
        }

        private fun syncDirectory(directory: File) {
            if (!System.getProperty("os.name").orEmpty().startsWith("Windows", true))
                FileChannel.open(directory.toPath(), StandardOpenOption.READ).use { it.force(true) }
        }

        private fun writeFullySynced(file: File, bytes: ByteArray) {
            FileOutputStream(file).use { output -> output.write(bytes); output.fd.sync() }
        }
    }
}

internal sealed interface M3CanonicalDirtyJournalOpenResult {
    data class Opened(val journal: M3CanonicalDirtyJournal) : M3CanonicalDirtyJournalOpenResult
    data class Refused(val reason: M3CanonicalDirtyJournalRefusal) : M3CanonicalDirtyJournalOpenResult
}

internal sealed interface M3CanonicalDirtyJournalFlushResult {
    data class Prepared(val intent: M3PreparedIntent) : M3CanonicalDirtyJournalFlushResult
    data class Refused(
        val reason: M3CanonicalDirtyJournalRefusal,
        val burnedHighWater: Long,
        val storage: M3CanonicalDirtyJournalStorageReceipt,
    ) : M3CanonicalDirtyJournalFlushResult
}

internal sealed interface M3CanonicalDirtyJournalReopenResult {
    data class None(val burnedHighWater: Long) : M3CanonicalDirtyJournalReopenResult
    data class Complete(val intent: M3PreparedIntent) : M3CanonicalDirtyJournalReopenResult
    data class Refused(val reason: M3CanonicalDirtyJournalRefusal, val burnedHighWater: Long) : M3CanonicalDirtyJournalReopenResult
}

internal enum class M3CanonicalDirtyJournalRefusal {
    CLOSED, STALE_OR_INVALID_PLAN, INTENT_EXISTS, INTENT_TOO_LARGE, DURABILITY_FAILURE, CORRUPT_LEDGER, CORRUPT_INTENT,
}

internal enum class M3CanonicalDirtyJournalFault {
    BEFORE_RESERVATION, AFTER_LEDGER_RESERVATION, BEFORE_LEDGER_WRITE, AFTER_LEDGER_SYNC, AFTER_LEDGER_PUBLISH,
    AFTER_INTENT_RESERVATION, BEFORE_INTENT_WRITE, DURING_WAL_WRITE, AFTER_INTENT_SYNC, AFTER_INTENT_PUBLISH,
}

internal data class M3CanonicalDirtyJournalStorageReceipt(
    val ledgerAllocatedBytes: Long,
    val intentAllocatedBytes: Long,
    val totalAllocatedBytes: Long,
)

/** File-backed only: callers can stream the exact WAL but cannot receive its bytes as a graph. */
internal class M3PreparedIntent internal constructor(
    val file: File,
    private val header: M3DirtyIntentHeader,
    val storage: M3CanonicalDirtyJournalStorageReceipt,
) {
    val sourceCut get() = header.sourceCut
    val targetHighWater get() = header.targetHighWater
    val walLength get() = header.walLength
    val walHash get() = header.walHash
    val currentLength get() = header.currentLength
    val currentHash get() = header.currentHash

    fun openWal(): InputStream = BoundedInputStream(BufferedInputStream(FileInputStream(file), M3CanonicalDirtyJournal.WRITER_SCRATCH_BYTES), header.walOffset, header.walLength)
}

private class M3DirtyAllocationLedger(
    group: M3SurfaceGroup,
    authority: M3CompactCanonicalCut,
    val start: Long,
    val endExclusive: Long,
    commandHash: M3CanonicalReceiptBytes,
    commandFingerprint: M3CanonicalReceiptBytes,
) {
    val groupHash = group.hash
    val authorityRootHash = authority.rootHash
    private val command = commandHash
    private val fingerprint = commandFingerprint
    val encoded: ByteArray by lazy {
        val body = java.io.ByteArrayOutputStream().use { bytes ->
            DataOutputStream(bytes).use { out ->
                out.writeInt(LEDGER_MAGIC); out.writeInt(VERSION); out.write(groupHash); out.write(authorityRootHash.toByteArray())
                out.writeLong(start); out.writeLong(endExclusive); out.write(command.toByteArray()); out.write(fingerprint.toByteArray())
            }
            bytes.toByteArray()
        }
        body + sha256(body)
    }
    val encodedBytes get() = encoded.size

    companion object {
        private const val LEDGER_MAGIC = 0x4d33444c
        private const val VERSION = 1
        fun read(file: File): M3DirtyAllocationLedger {
            val bytes = file.readBytes(); require(bytes.size == 4 + 4 + 32 + 32 + 8 + 8 + 32 + 32 + 32)
            require(sha256(bytes.copyOfRange(0, bytes.size - 32)).contentEquals(bytes.copyOfRange(bytes.size - 32, bytes.size)))
            DataInputStream(bytes.inputStream()).use { input ->
                require(input.readInt() == LEDGER_MAGIC && input.readInt() == VERSION)
                val groupHash = input.readNBytes(32); val root = input.readNBytes(32); val start = input.readLong(); val end = input.readLong()
                val command = input.readNBytes(32); val fingerprint = input.readNBytes(32)
                return M3DirtyAllocationLedger(M3SurfaceGroup("restored"), M3CompactCanonicalCut(M3SurfaceGroup("restored"), "", 0, 0, 1, 0, 0, 0, 0, null, M3CanonicalReceiptBytes(root), M3CanonicalReceiptBytes.EMPTY), start, end, M3CanonicalReceiptBytes(command), M3CanonicalReceiptBytes(fingerprint)).also {
                    groupHash.copyInto(it.groupHash)
                }
            }
        }
        private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
    }
}

internal data class M3DirtyIntentHeader(
    val sourceCut: M3CompactCanonicalCut,
    val commandHash: M3CanonicalReceiptBytes,
    val commandFingerprint: M3CanonicalReceiptBytes,
    val targetHighWater: Long,
    val targetLive: Int,
    val targetSource: Int,
    val targetSupport: Int,
    val targetLineage: Int,
    val targetGeometry: Long,
    val targetLineageRevision: Long,
    val walLength: Long,
    val walHash: M3CanonicalReceiptBytes,
    val currentLength: Long,
    val currentHash: M3CanonicalReceiptBytes,
    val walOffset: Long,
) {
    fun matches(authority: M3CompactCanonicalCut) = sourceCut == authority
    fun encodedBytes(walBytes: Int) = walOffset + walBytes + 32L
    fun writeWithoutChecksum(out: DataOutputStream) {
        out.writeInt(INTENT_MAGIC); out.writeInt(VERSION); out.writeUTF(sourceCut.group.value); out.writeUTF(sourceCut.profile)
        out.writeLong(sourceCut.geometryRevision); out.writeLong(sourceCut.lineageRevision); out.writeLong(sourceCut.nextSurfaceIdHighWater)
        out.writeInt(sourceCut.liveSurfaceCount); out.writeInt(sourceCut.sourceCount); out.writeInt(sourceCut.supportCount); out.writeInt(sourceCut.lineageCount)
        out.writeBoolean(sourceCut.seededEmptyBaseline != null)
        sourceCut.seededEmptyBaseline?.let { baseline ->
            out.writeUTF(baseline.bindingIdentity); out.writeUTF(baseline.groupIdentity); out.writeLong(baseline.transactionId)
            out.writeLong(baseline.geometryRevision); out.writeLong(baseline.lineageRevision)
        }
        out.write(sourceCut.rootHash.toByteArray()); out.write(sourceCut.sourceHash.toByteArray())
        out.write(commandHash.toByteArray()); out.write(commandFingerprint.toByteArray())
        out.writeLong(targetHighWater); out.writeInt(targetLive); out.writeInt(targetSource); out.writeInt(targetSupport); out.writeInt(targetLineage)
        out.writeLong(targetGeometry); out.writeLong(targetLineageRevision); out.writeLong(walLength); out.write(walHash.toByteArray()); out.writeLong(currentLength); out.write(currentHash.toByteArray())
    }
    companion object {
        private const val INTENT_MAGIC = 0x4d334449
        private const val VERSION = 1
        fun from(plan: M3PreparedCanonicalMutation, authority: M3CompactCanonicalCut): M3DirtyIntentHeader {
            val current = digestCurrent(plan)
            val wal = digestWal(plan)
            val prototype = M3DirtyIntentHeader(plan.sourceCut, plan.commandHash, plan.commandFingerprint, plan.targetHighWater,
                plan.targetLiveSurfaceCount, plan.targetSourceCount, plan.targetSupportCount, plan.targetLineageCount,
                plan.targetGeometryRevision, plan.targetLineageRevision, plan.work.walBytes.toLong(), M3CanonicalReceiptBytes(wal),
                plan.work.currentBytes.toLong(), M3CanonicalReceiptBytes(current), 0)
            val offset = countingHeader(prototype)
            return prototype.copy(walOffset = offset)
        }
        fun read(file: File): M3DirtyIntentHeader {
            require(file.length() >= 32)
            FileInputStream(file).use { raw ->
                val digest = MessageDigest.getInstance("SHA-256")
                val bounded = BoundedInputStream(raw, 0, file.length() - 32)
                val hashed = DigestInputStream(bounded, digest)
                val input = DataInputStream(BufferedInputStream(hashed, M3CanonicalDirtyJournal.WRITER_SCRATCH_BYTES))
                    require(input.readInt() == INTENT_MAGIC && input.readInt() == VERSION)
                    val group = M3SurfaceGroup(input.readUTF()); val profile = input.readUTF()
                    val geometry = input.readLong(); val lineage = input.readLong(); val high = input.readLong()
                    val live = input.readInt(); val source = input.readInt(); val support = input.readInt(); val edges = input.readInt()
                    val baseline = if (input.readBoolean()) M3CommittedEmptyBaseline(
                        input.readUTF(), input.readUTF(), input.readLong(), input.readLong(), input.readLong(),
                    ) else null
                    val root = M3CanonicalReceiptBytes(input.readNBytes(32)); val sourceHash = M3CanonicalReceiptBytes(input.readNBytes(32))
                    val command = M3CanonicalReceiptBytes(input.readNBytes(32)); val fingerprint = M3CanonicalReceiptBytes(input.readNBytes(32))
                    val targetHigh = input.readLong(); val targetLive = input.readInt(); val targetSource = input.readInt(); val targetSupport = input.readInt(); val targetEdges = input.readInt()
                    val targetGeometry = input.readLong(); val targetLineage = input.readLong(); val walLength = input.readLong(); val walHash = M3CanonicalReceiptBytes(input.readNBytes(32)); val currentLength = input.readLong(); val currentHash = M3CanonicalReceiptBytes(input.readNBytes(32))
                    val offset = file.length() - 32 - walLength
                    require(offset >= 0 && input.available().toLong() >= walLength)
                    val walDigest = MessageDigest.getInstance("SHA-256"); var remaining = walLength; val scratch = ByteArray(M3CanonicalDirtyJournal.WRITER_SCRATCH_BYTES)
                    while (remaining > 0) { val count = input.read(scratch, 0, minOf(scratch.size.toLong(), remaining).toInt()); require(count > 0); walDigest.update(scratch, 0, count); remaining -= count }
                    require(walDigest.digest().contentEquals(walHash.toByteArray()))
                    val expected = digest.digest(); val trailer = raw.readNBytes(32); require(trailer.contentEquals(expected))
                    return M3DirtyIntentHeader(M3CompactCanonicalCut(group, profile, geometry, lineage, high, live, source, support, edges, baseline, root, sourceHash), command, fingerprint, targetHigh, targetLive, targetSource, targetSupport, targetEdges, targetGeometry, targetLineage, walLength, walHash, currentLength, currentHash, offset)
            }
        }
        private fun countingHeader(header: M3DirtyIntentHeader): Long {
            val counter = CountingOutputStream(); DataOutputStream(counter).use { header.writeWithoutChecksum(it) }; return counter.count
        }
        private fun digestCurrent(plan: M3PreparedCanonicalMutation): ByteArray {
            val digest = MessageDigest.getInstance("SHA-256")
            plan.writeCurrentTo(DigestOutputStream(NullOutputStream, digest))
            return digest.digest()
        }
        private fun digestWal(plan: M3PreparedCanonicalMutation): ByteArray {
            val digest = MessageDigest.getInstance("SHA-256")
            plan.writeWalTo(DigestOutputStream(NullOutputStream, digest))
            return digest.digest()
        }
    }
}

private class FaultingOutputStream(private val delegate: OutputStream, private val fault: M3CanonicalDirtyJournalFault?) : OutputStream() {
    var count = 0L; private var fired = false
    override fun write(value: Int) { write(byteArrayOf(value.toByte())) }
    override fun write(bytes: ByteArray, offset: Int, length: Int) { if (fault == M3CanonicalDirtyJournalFault.DURING_WAL_WRITE && !fired && count > 0) { fired = true; error("fault") }; delegate.write(bytes, offset, length); count += length }
}
private object NullOutputStream : OutputStream() { override fun write(value: Int) = Unit; override fun write(bytes: ByteArray, offset: Int, length: Int) = Unit }
private class CountingOutputStream : OutputStream() { var count = 0L; override fun write(value: Int) { count++ }; override fun write(bytes: ByteArray, offset: Int, length: Int) { count += length } }
private class BoundedInputStream(delegate: InputStream, skip: Long, private var remaining: Long) : InputStream() {
    private val source = delegate
    init { var left = skip; while (left > 0) { val skipped = source.skip(left); require(skipped > 0); left -= skipped } }
    override fun read(): Int = if (remaining == 0L) -1 else source.read().also { if (it >= 0) remaining-- }
    override fun read(bytes: ByteArray, offset: Int, length: Int): Int { if (remaining == 0L) return -1; val count = source.read(bytes, offset, minOf(length.toLong(), remaining).toInt()); if (count > 0) remaining -= count; return count }
    override fun close() = source.close()
}
