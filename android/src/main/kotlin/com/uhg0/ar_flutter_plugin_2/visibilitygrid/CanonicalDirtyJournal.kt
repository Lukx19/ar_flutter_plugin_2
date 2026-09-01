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
 * The deliberately pre-root half of canonical surface persistence.
 *
 * This module has one deep interface: it burns a prepared plan's projected ID
 * range and leaves behind a self-validating, file-backed intent.  It does not
 * own a mutable canonical view, a root switch, replay/ACK, or activation.  In
 * particular, a complete intent is evidence for a later owner, never a new
 * semantic authority.
 */
internal class CanonicalDirtyJournal private constructor(
    private val group: SurfaceGroup,
    private val authority: CompactCanonicalCut,
    private val directory: File,
    private val budget: CanonicalStorageBudget,
    private var burnedHighWater: Long,
    private var lastAllocationRevision: Long,
    private var lastAllocationHash: ByteArray,
    private var allocationHistory: AllocationHistoryReceipt,
    private var authorityHighWaterSeen: Boolean,
) : AutoCloseable {
    private var closed = false
    private var retainedPlanBytes = 0L

    @Synchronized
    fun flush(
        plan: PreparedCanonicalMutation,
        fault: CanonicalDirtyJournalFault? = null,
    ): CanonicalDirtyJournalFlushResult {
        if (closed) return refused(CanonicalDirtyJournalRefusal.CLOSED)
        retainedPlanBytes = plan.work.retainedPlanBytes
        if (reopen() is CanonicalDirtyJournalReopenResult.Complete)
            return refused(CanonicalDirtyJournalRefusal.INTENT_EXISTS)
        if (!matchesAuthority(plan.sourceCut) || !validRange(plan))
            return refused(CanonicalDirtyJournalRefusal.STALE_OR_INVALID_PLAN)
        val allocation = AllocationRecord(
            revision = lastAllocationRevision + 1,
            start = burnedHighWater,
            endExclusive = plan.targetHighWater,
            groupHash = group.hash,
            commandHash = plan.commandHash.toByteArray(),
            fingerprint = plan.commandFingerprint.toByteArray(),
            previousHash = lastAllocationHash,
        )
        val nextChain = SurfaceAllocationAuthority.continueStreaming(
            AllocationChain(
                emptyList(), burnedHighWater, lastAllocationRevision, lastAllocationHash,
                allocationHistory, authorityHighWaterSeen,
            ),
            group, allocation, authority.nextSurfaceIdHighWater,
        )
        val checkpoint = AllocationCheckpoint.from(nextChain).encoded()
        val ledgerTarget = allocationTarget(allocation.revision)
        val ledgerResult = publishCandidate(
            ledgerTarget,
            mapOf(
                ALLOCATION_RECORD_FILE to AllocationRecord.ENCODED_BYTES.toLong(),
                ALLOCATION_CHECKPOINT_FILE to checkpoint.size.toLong(),
            ),
            fault,
            CandidateKind.ALLOCATION,
        ) { staged ->
            FileOutputStream(File(staged, ALLOCATION_RECORD_FILE)).use { it.write(allocation.encoded()) }
            FileOutputStream(File(staged, ALLOCATION_CHECKPOINT_FILE)).use { it.write(checkpoint) }
        }
        if (ledgerResult == CandidatePublication.TARGET_RESERVED)
            return refused(CanonicalDirtyJournalRefusal.TARGET_RESERVED)
        refreshAllocationAuthority()
        if (ledgerResult != CandidatePublication.PUBLISHED)
            return refused(CanonicalDirtyJournalRefusal.DURABILITY_FAILURE)

        val intentTarget = File(directory, INTENT_DIRECTORY)
        if (intentTarget.exists()) return refused(CanonicalDirtyJournalRefusal.INTENT_EXISTS)
        val header = DirtyIntentHeader.from(plan, authority)
        val logicalBytes = header.encodedBytes(plan.work.walBytes)
        if (logicalBytes > CompactCanonicalStore.JOURNAL_RESERVE_BYTES)
            return refused(CanonicalDirtyJournalRefusal.INTENT_TOO_LARGE)
        val intentResult = publishCandidate(
            intentTarget,
            mapOf(INTENT_FILE to logicalBytes),
            fault,
            CandidateKind.INTENT,
        ) { staged ->
            writeIntent(File(staged, INTENT_FILE), plan, header, fault)
        }
        if (intentResult == CandidatePublication.TARGET_RESERVED)
            return refused(CanonicalDirtyJournalRefusal.TARGET_RESERVED)
        if (intentResult != CandidatePublication.PUBLISHED)
            return refused(CanonicalDirtyJournalRefusal.DURABILITY_FAILURE)
        return CanonicalDirtyJournalFlushResult.Prepared(
            PreparedIntent(File(intentTarget, INTENT_FILE), physicalReceipt()),
        )
    }

    @Synchronized
    fun reopen(): CanonicalDirtyJournalReopenResult {
        if (closed) return CanonicalDirtyJournalReopenResult.Refused(CanonicalDirtyJournalRefusal.CLOSED, burnedHighWater)
        try {
            refreshAllocationAuthority()
            budget.reconcileCandidate(intentTarget())
        } catch (_: Exception) {
            return CanonicalDirtyJournalReopenResult.Refused(CanonicalDirtyJournalRefusal.CORRUPT_LEDGER, burnedHighWater)
        }
        val intent = File(directory, "$INTENT_DIRECTORY/$INTENT_FILE")
        if (!intent.exists()) return CanonicalDirtyJournalReopenResult.None(burnedHighWater)
        return try {
            val header = DirtyIntentHeader.read(intent)
            if (!header.matches(authority) || header.targetHighWater > burnedHighWater)
                CanonicalDirtyJournalReopenResult.Refused(CanonicalDirtyJournalRefusal.CORRUPT_INTENT, burnedHighWater)
            else CanonicalDirtyJournalReopenResult.Complete(PreparedIntent(intent, physicalReceipt()))
        } catch (_: Exception) {
            // Never try to infer semantic data from a damaged pre-root file.
            CanonicalDirtyJournalReopenResult.Refused(CanonicalDirtyJournalRefusal.CORRUPT_INTENT, burnedHighWater)
        }
    }

    /** Releases only the exact consumed intent after its selected current has been ACKed. */
    @Synchronized
    fun reclaimAcknowledgedIntent(receipt: PreparedIntentCurrentReceipt): Boolean {
        if (closed) return false
        val target = intentTarget()
        if (!target.exists()) return true
        return try {
            budget.reconcileCandidate(target)
            val header = DirtyIntentHeader.read(File(target, INTENT_FILE))
            require(header.currentLength == receipt.length && header.currentHash == receipt.hash)
            require(header.targetHighWater == authority.nextSurfaceIdHighWater &&
                header.targetLive == authority.liveSurfaceCount && header.targetSource == authority.sourceCount &&
                header.targetSupport == authority.supportCount && header.targetLineage == authority.lineageCount &&
                header.targetGeometry == authority.geometryRevision && header.targetLineageRevision == authority.lineageRevision)
            budget.reclaimCommittedCandidate(target) > 0L
        } catch (_: Exception) { false }
    }

    @Synchronized
    override fun close() { closed = true }

    private fun matchesAuthority(cut: CompactCanonicalCut) = cut == authority && cut.group == group

    private fun validRange(plan: PreparedCanonicalMutation): Boolean =
        plan.targetHighWater >= burnedHighWater &&
            plan.targetHighWater <= UINT32_HIGH_WATER &&
            plan.sourceCut.nextSurfaceIdHighWater == burnedHighWater

    private fun refused(reason: CanonicalDirtyJournalRefusal) =
        CanonicalDirtyJournalFlushResult.Refused(reason, burnedHighWater, physicalReceipt())

    private fun physicalReceipt(): CanonicalDirtyJournalStorageReceipt {
        var ledger = 0L
        Files.newDirectoryStream(directory.toPath()).use { entries ->
            entries.forEach { path ->
                val file = path.toFile()
                if (file.isDirectory && file.name.startsWith("${candidateBase()}.allocation-"))
                    ledger = Math.addExact(ledger, budget.allocatedBytes(file))
            }
        }
        val intent = File(directory, INTENT_DIRECTORY).takeIf(File::exists)?.let(budget::allocatedBytes) ?: 0L
        return CanonicalDirtyJournalStorageReceipt(
            ledger, intent, Math.addExact(ledger, intent), allocationHistory,
            maxOf(
                Math.addExact(retainedPlanBytes, allocationHistory.phasePeakBytes),
                Math.addExact(retainedPlanBytes, WRITER_SCRATCH_BYTES.toLong()),
            ),
        )
    }

    /** Writes into one physically backed candidate, then moves it once. */
    private fun publishCandidate(
        target: File,
        files: Map<String, Long>,
        fault: CanonicalDirtyJournalFault?,
        kind: CandidateKind,
        writer: (File) -> Unit,
    ): CandidatePublication {
        var token: Any? = null
        var staging: File? = null
        var published = false
        try {
            inject(fault, kind.beforeReservation)
            staging = File(directory, "${candidateBase()}.staging-${kind.name.lowercase()}-${System.nanoTime()}")
            val unit = budget.allocationUnitBytes(directory)
            // File blocks plus staging directory, reservation metadata, budget-ledger replacement,
            // and parent-directory entry are all reserved before the first write.
            val worst = (files.values + listOf(1L, 1L, 1L, 1L)).fold(0L) { total, bytes ->
                Math.addExact(total, roundPhysical(bytes, unit))
            }
            when (val admission = budget.reserveCandidateExclusive(staging, target, files, worst)) {
                is CanonicalCandidateReservation.Reserved -> token = admission.token
                CanonicalCandidateReservation.TargetReserved -> return CandidatePublication.TARGET_RESERVED
                CanonicalCandidateReservation.QuotaRefused -> return CandidatePublication.FAILED
            }
            inject(fault, kind.afterReservation)
            budget.verifyCandidate(requireNotNull(token), staging)
            inject(fault, kind.beforeWrite)
            writer(staging)
            inject(fault, kind.afterWrite)
            budget.verifyCandidate(requireNotNull(token), staging)
            inject(fault, kind.beforeFsync)
            syncCandidateFiles(staging, files.keys)
            inject(fault, kind.afterFsync)
            budget.verifyCandidate(requireNotNull(token), staging)
            syncDirectory(staging)
            inject(fault, kind.beforePublish)
            budget.publishCandidate(requireNotNull(token), staging, target)
            published = true
            inject(fault, kind.afterPublish)
            budget.verifyCandidate(requireNotNull(token), target)
            inject(fault, kind.beforeParentSync)
            syncDirectory(directory)
            inject(fault, kind.afterParentSync)
            inject(fault, kind.beforeBudgetCommit)
            budget.commit(requireNotNull(token), budget.allocatedBytes(target))
            token = null
            inject(fault, kind.afterBudgetCommit)
            return CandidatePublication.PUBLISHED
        } catch (_: Exception) {
            return CandidatePublication.FAILED
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
        plan: PreparedCanonicalMutation,
        header: DirtyIntentHeader,
        fault: CanonicalDirtyJournalFault?,
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
        }
        require(file.length() <= CompactCanonicalStore.JOURNAL_RESERVE_BYTES)
    }

    companion object {
        private const val UINT32_HIGH_WATER = 0x1_0000_0000L
        private const val HASH_BYTES = 32
        internal const val WRITER_SCRATCH_BYTES = 65_536
        private const val ALLOCATION_RECORD_FILE = "allocation-record.bin"
        private const val ALLOCATION_CHECKPOINT_FILE = "allocation-checkpoint.bin"
        private const val INTENT_FILE = "intent.bin"

        fun open(
            authority: CanonicalStateView,
            directory: File,
            budget: CanonicalStorageBudget,
        ): CanonicalDirtyJournalOpenResult = open(authority.cut, directory, budget)

        fun open(
            authority: CompactCanonicalCut,
            directory: File,
            budget: CanonicalStorageBudget,
        ): CanonicalDirtyJournalOpenResult = try {
            require(directory.exists() || directory.mkdirs())
            val journal = CanonicalDirtyJournal(
                authority.group, authority, directory, budget, 1L, 0L, ByteArray(32),
                AllocationHistoryReceipt(0L, 0L, SurfaceAllocationAuthority.HISTORY_PHASE_PEAK_BYTES), false,
            )
            journal.refreshAllocationAuthority()
            require(authority.nextSurfaceIdHighWater <= journal.burnedHighWater)
            CanonicalDirtyJournalOpenResult.Opened(journal)
        } catch (_: Exception) {
            CanonicalDirtyJournalOpenResult.Refused(CanonicalDirtyJournalRefusal.CORRUPT_LEDGER)
        }

        private fun roundPhysical(bytes: Long, unit: Long): Long {
            require(bytes >= 0 && unit > 0)
            return if (bytes == 0L) 0L else Math.multiplyExact((bytes - 1L) / unit + 1L, unit)
        }

        private fun syncDirectory(directory: File) {
            if (!System.getProperty("os.name").orEmpty().startsWith("Windows", true))
                FileChannel.open(directory.toPath(), StandardOpenOption.READ).use { it.force(true) }
        }

    }

    private fun candidateBase() = "canonical-surface-canonical-v6-${group.hash.hex()}"
    private fun allocationTarget(revision: Long) = File(directory, "${candidateBase()}.allocation-$revision")
    private fun intentTarget() = File(directory, "${candidateBase()}.intent")
    private val INTENT_DIRECTORY get() = intentTarget().name

    private fun refreshAllocationAuthority() {
        val legacyFile = SurfaceAllocationAuthority.legacyFile(directory, group)
        val initialHighWater = if (authority.seededEmptyBaseline != null) 1L
            else if (!legacyFile.exists() || legacyFile.length() == 0L) authority.nextSurfaceIdHighWater
            else 1L
        var chain = SurfaceAllocationAuthority.streamLegacy(
            directory, group, initialHighWater, authority.nextSurfaceIdHighWater,
        )
        while (true) {
            val candidate = allocationTarget(Math.addExact(chain.lastRevision, 1L))
            if (!candidate.exists()) {
                // A live independent writer retains its exclusive target reservation. A reservation
                // whose owning coordinator closed is a recovery orphan and is reclaimed here.
                budget.reconcileCandidate(candidate)
                if (!candidate.exists()) break
            }
            budget.reconcilePublishedCandidate(candidate)
            val recordBytes = File(candidate, ALLOCATION_RECORD_FILE).readFixed(AllocationRecord.ENCODED_BYTES)
            chain = SurfaceAllocationAuthority.continueStreaming(
                chain, group, AllocationRecord.decode(recordBytes), authority.nextSurfaceIdHighWater,
            )
            val checkpointFile = File(candidate, ALLOCATION_CHECKPOINT_FILE)
            if (checkpointFile.exists()) {
                val checkpoint = checkpointFile.readFixed(AllocationCheckpoint.ENCODED_BYTES)
                require(AllocationCheckpoint.decode(checkpoint).matchesHistoryPrefix(chain))
            }
        }
        val prefix = "${candidateBase()}.allocation-"
        Files.newDirectoryStream(directory.toPath()).use { entries ->
            entries.forEach { path ->
                val file = path.toFile()
                if (file.isDirectory && file.name.startsWith(prefix)) {
                    val revision = file.name.removePrefix(prefix).toLongOrNull() ?: error("Corrupt allocation target")
                    require(revision in 1..chain.lastRevision)
                }
            }
        }
        require(chain.authorityHighWaterSeen || authority.nextSurfaceIdHighWater == 1L)
        burnedHighWater = chain.highWater
        lastAllocationRevision = chain.lastRevision
        lastAllocationHash = chain.lastHash
        allocationHistory = chain.history
        authorityHighWaterSeen = chain.authorityHighWaterSeen
    }

    private fun File.readFixed(bytes: Int): ByteArray {
        require(isFile && length() == bytes.toLong())
        return FileInputStream(this).use { input -> ByteArray(bytes).also { data ->
            var offset = 0
            while (offset < data.size) {
                val count = input.read(data, offset, data.size - offset)
                require(count > 0); offset += count
            }
            require(input.read() == -1)
        } }
    }

    private fun syncCandidateFiles(candidate: File, names: Set<String>) {
        names.forEach { name -> FileOutputStream(File(candidate, name), true).use { it.fd.sync() } }
    }

    private fun inject(requested: CanonicalDirtyJournalFault?, point: CanonicalDirtyJournalFault) {
        if (requested == point) error("fault:$point")
    }

    private enum class CandidateKind(
        val beforeReservation: CanonicalDirtyJournalFault,
        val afterReservation: CanonicalDirtyJournalFault,
        val beforeWrite: CanonicalDirtyJournalFault,
        val afterWrite: CanonicalDirtyJournalFault,
        val beforeFsync: CanonicalDirtyJournalFault,
        val afterFsync: CanonicalDirtyJournalFault,
        val beforePublish: CanonicalDirtyJournalFault,
        val afterPublish: CanonicalDirtyJournalFault,
        val beforeParentSync: CanonicalDirtyJournalFault,
        val afterParentSync: CanonicalDirtyJournalFault,
        val beforeBudgetCommit: CanonicalDirtyJournalFault,
        val afterBudgetCommit: CanonicalDirtyJournalFault,
    ) {
        ALLOCATION(
            CanonicalDirtyJournalFault.BEFORE_ALLOCATION_RESERVATION, CanonicalDirtyJournalFault.AFTER_ALLOCATION_RESERVATION,
            CanonicalDirtyJournalFault.BEFORE_ALLOCATION_WRITE, CanonicalDirtyJournalFault.AFTER_ALLOCATION_WRITE,
            CanonicalDirtyJournalFault.BEFORE_ALLOCATION_FSYNC, CanonicalDirtyJournalFault.AFTER_ALLOCATION_FSYNC,
            CanonicalDirtyJournalFault.BEFORE_ALLOCATION_PUBLISH, CanonicalDirtyJournalFault.AFTER_ALLOCATION_PUBLISH,
            CanonicalDirtyJournalFault.BEFORE_ALLOCATION_PARENT_SYNC, CanonicalDirtyJournalFault.AFTER_ALLOCATION_PARENT_SYNC,
            CanonicalDirtyJournalFault.BEFORE_ALLOCATION_BUDGET_COMMIT, CanonicalDirtyJournalFault.AFTER_ALLOCATION_BUDGET_COMMIT,
        ),
        INTENT(
            CanonicalDirtyJournalFault.BEFORE_INTENT_RESERVATION, CanonicalDirtyJournalFault.AFTER_INTENT_RESERVATION,
            CanonicalDirtyJournalFault.BEFORE_INTENT_WRITE, CanonicalDirtyJournalFault.AFTER_INTENT_WRITE,
            CanonicalDirtyJournalFault.BEFORE_INTENT_FSYNC, CanonicalDirtyJournalFault.AFTER_INTENT_FSYNC,
            CanonicalDirtyJournalFault.BEFORE_INTENT_PUBLISH, CanonicalDirtyJournalFault.AFTER_INTENT_PUBLISH,
            CanonicalDirtyJournalFault.BEFORE_INTENT_PARENT_SYNC, CanonicalDirtyJournalFault.AFTER_INTENT_PARENT_SYNC,
            CanonicalDirtyJournalFault.BEFORE_INTENT_BUDGET_COMMIT, CanonicalDirtyJournalFault.AFTER_INTENT_BUDGET_COMMIT,
        ),
    }

    private enum class CandidatePublication { PUBLISHED, TARGET_RESERVED, FAILED }
}

internal sealed interface CanonicalDirtyJournalOpenResult {
    data class Opened(val journal: CanonicalDirtyJournal) : CanonicalDirtyJournalOpenResult
    data class Refused(val reason: CanonicalDirtyJournalRefusal) : CanonicalDirtyJournalOpenResult
}

internal sealed interface CanonicalDirtyJournalFlushResult {
    data class Prepared(val intent: PreparedIntent) : CanonicalDirtyJournalFlushResult
    data class Refused(
        val reason: CanonicalDirtyJournalRefusal,
        val burnedHighWater: Long,
        val storage: CanonicalDirtyJournalStorageReceipt,
    ) : CanonicalDirtyJournalFlushResult
}

internal sealed interface CanonicalDirtyJournalReopenResult {
    data class None(val burnedHighWater: Long) : CanonicalDirtyJournalReopenResult
    data class Complete(val intent: PreparedIntent) : CanonicalDirtyJournalReopenResult
    data class Refused(val reason: CanonicalDirtyJournalRefusal, val burnedHighWater: Long) : CanonicalDirtyJournalReopenResult
}

internal enum class CanonicalDirtyJournalRefusal {
    CLOSED, STALE_OR_INVALID_PLAN, INTENT_EXISTS, TARGET_RESERVED, INTENT_TOO_LARGE, DURABILITY_FAILURE, CORRUPT_LEDGER, CORRUPT_INTENT,
}

internal enum class CanonicalDirtyJournalFault {
    BEFORE_ALLOCATION_RESERVATION, AFTER_ALLOCATION_RESERVATION,
    BEFORE_ALLOCATION_WRITE, AFTER_ALLOCATION_WRITE,
    BEFORE_ALLOCATION_FSYNC, AFTER_ALLOCATION_FSYNC,
    BEFORE_ALLOCATION_PUBLISH, AFTER_ALLOCATION_PUBLISH,
    BEFORE_ALLOCATION_PARENT_SYNC, AFTER_ALLOCATION_PARENT_SYNC,
    BEFORE_ALLOCATION_BUDGET_COMMIT, AFTER_ALLOCATION_BUDGET_COMMIT,
    BEFORE_INTENT_RESERVATION, AFTER_INTENT_RESERVATION,
    BEFORE_INTENT_WRITE, AFTER_INTENT_WRITE,
    BEFORE_INTENT_FSYNC, AFTER_INTENT_FSYNC,
    BEFORE_INTENT_PUBLISH, AFTER_INTENT_PUBLISH,
    BEFORE_INTENT_PARENT_SYNC, AFTER_INTENT_PARENT_SYNC,
    BEFORE_INTENT_BUDGET_COMMIT, AFTER_INTENT_BUDGET_COMMIT,
    DURING_WAL_WRITE,
}

internal data class CanonicalDirtyJournalStorageReceipt(
    val ledgerAllocatedBytes: Long,
    val intentAllocatedBytes: Long,
    val totalAllocatedBytes: Long,
    val history: AllocationHistoryReceipt,
    val phasePeakBytes: Long,
)

/** File-backed only: callers can stream the exact WAL but cannot receive its bytes as a graph. */
internal class PreparedIntent internal constructor(
    val file: File,
    val storage: CanonicalDirtyJournalStorageReceipt,
) : AutoCloseable {
    @Volatile private var closed = false

    /**
     * Fully validates the durable envelope and exact current re-encoding before
     * returning its identity. No plan-derived header state survives this seam.
     */
    @Synchronized
    fun identity(): PreparedIntentIdentityResult = when (val result = visit(PreparedIntentVisitor.NONE)) {
        is PreparedIntentVisitResult.Complete -> PreparedIntentIdentityResult.Complete(result.identity)
        is PreparedIntentVisitResult.Stopped -> PreparedIntentIdentityResult.Refused(PreparedIntentVisitRefusal.VISITOR_FAILURE)
        is PreparedIntentVisitResult.Refused -> PreparedIntentIdentityResult.Refused(result.reason)
    }

    /** Streams and re-encodes the current record.  It never returns decoded records. */
    @Synchronized
    fun currentReceipt(): PreparedIntentCurrentReceiptResult = when (val result = visit(PreparedIntentVisitor.NONE)) {
        is PreparedIntentVisitResult.Complete -> PreparedIntentCurrentReceiptResult.Complete(result.currentReceipt)
        is PreparedIntentVisitResult.Stopped -> PreparedIntentCurrentReceiptResult.Stopped
        is PreparedIntentVisitResult.Refused -> PreparedIntentCurrentReceiptResult.Refused(result.reason)
    }

    @Synchronized
    fun visit(visitor: PreparedIntentVisitor): PreparedIntentVisitResult =
        PreparedIntentStreamingVisitor(file, ::isClosed).visit(visitor)

    /**
     * Streams the byte-exact validated current record while visiting the same
     * scalar handoff. The caller owns [output]; no current-sized byte array is
     * ever materialized.
     */
    @Synchronized
    internal fun visitCurrent(
        visitor: PreparedIntentVisitor,
        output: OutputStream,
    ): PreparedIntentVisitResult =
        PreparedIntentStreamingVisitor(file, ::isClosed).visit(visitor, output)

    @Synchronized
    override fun close() { closed = true }
    private fun isClosed() = closed
}

internal data class DirtyIntentHeader(
    val sourceCut: CompactCanonicalCut,
    val commandHash: CanonicalReceiptBytes,
    val commandFingerprint: CanonicalReceiptBytes,
    val targetHighWater: Long,
    val targetLive: Int,
    val targetSource: Int,
    val targetSupport: Int,
    val targetLineage: Int,
    val targetGeometry: Long,
    val targetLineageRevision: Long,
    val walLength: Long,
    val walHash: CanonicalReceiptBytes,
    val currentLength: Long,
    val currentHash: CanonicalReceiptBytes,
    val walOffset: Long,
) {
    fun matches(authority: CompactCanonicalCut) = sourceCut == authority
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
        fun from(plan: PreparedCanonicalMutation, authority: CompactCanonicalCut): DirtyIntentHeader {
            val current = digestCurrent(plan)
            val wal = digestWal(plan)
            val prototype = DirtyIntentHeader(plan.sourceCut, plan.commandHash, plan.commandFingerprint, plan.targetHighWater,
                plan.targetLiveSurfaceCount, plan.targetSourceCount, plan.targetSupportCount, plan.targetLineageCount,
                plan.targetGeometryRevision, plan.targetLineageRevision, plan.work.walBytes.toLong(), CanonicalReceiptBytes(wal),
                plan.work.currentBytes.toLong(), CanonicalReceiptBytes(current), 0)
            val offset = countingHeader(prototype)
            return prototype.copy(walOffset = offset)
        }
        fun read(file: File): DirtyIntentHeader {
            require(file.length() in 32..CompactCanonicalStore.JOURNAL_RESERVE_BYTES.toLong())
            FileInputStream(file).use { raw ->
                val digest = MessageDigest.getInstance("SHA-256")
                val bounded = BoundedInputStream(raw, 0, file.length() - 32)
                val hashed = DigestInputStream(bounded, digest)
                val input = DataInputStream(BufferedInputStream(hashed, CanonicalDirtyJournal.WRITER_SCRATCH_BYTES))
                    require(input.readInt() == INTENT_MAGIC && input.readInt() == VERSION)
                    val group = SurfaceGroup(input.readUTF()); val profile = input.readUTF()
                    val geometry = input.readLong(); val lineage = input.readLong(); val high = input.readLong()
                    val live = input.readInt(); val source = input.readInt(); val support = input.readInt(); val edges = input.readInt()
                    val baseline = if (input.readBoolean()) committedEmptyBaseline(
                        input.readUTF(), input.readUTF(), input.readLong(), input.readLong(), input.readLong(),
                    ) else null
                    val root = CanonicalReceiptBytes(input.readNBytes(32)); val sourceHash = CanonicalReceiptBytes(input.readNBytes(32))
                    val command = CanonicalReceiptBytes(input.readNBytes(32)); val fingerprint = CanonicalReceiptBytes(input.readNBytes(32))
                    val targetHigh = input.readLong(); val targetLive = input.readInt(); val targetSource = input.readInt(); val targetSupport = input.readInt(); val targetEdges = input.readInt()
                    val targetGeometry = input.readLong(); val targetLineage = input.readLong(); val walLength = input.readLong(); val walHash = CanonicalReceiptBytes(input.readNBytes(32)); val currentLength = input.readLong(); val currentHash = CanonicalReceiptBytes(input.readNBytes(32))
                    val prototype = DirtyIntentHeader(CompactCanonicalCut(group, profile, geometry, lineage, high, live, source, support, edges, baseline, root, sourceHash), command, fingerprint, targetHigh, targetLive, targetSource, targetSupport, targetEdges, targetGeometry, targetLineage, walLength, walHash, currentLength, currentHash, 0)
                    prototype.requireValid()
                    val offset = file.length() - 32 - walLength
                    require(offset == countingHeader(prototype))
                    val walDigest = MessageDigest.getInstance("SHA-256"); var remaining = walLength
                    // BufferedInputStream is the sole streaming scratch.  Do not add a
                    // second WAL-sized byte array while checking the pre-root envelope.
                    while (remaining > 0) {
                        val value = input.read()
                        require(value >= 0)
                        walDigest.update(value.toByte())
                        remaining--
                    }
                    require(walDigest.digest().contentEquals(walHash.toByteArray()))
                    require(input.read() == -1)
                    val expected = digest.digest(); val trailer = raw.readNBytes(32); require(trailer.contentEquals(expected) && raw.read() == -1)
                    return prototype.copy(walOffset = offset)
            }
        }

        private fun DirtyIntentHeader.requireValid() {
            fun hash(value: CanonicalReceiptBytes) = require(value.size == 32)
            fun count(value: Int, maximum: Int) = require(value in 0..maximum)
            require(sourceCut.profile == CompactCanonicalStore.PROFILE)
            require(sourceCut.nextSurfaceIdHighWater in 1..0x1_0000_0000L)
            require(targetHighWater in sourceCut.nextSurfaceIdHighWater..0x1_0000_0000L)
            count(sourceCut.liveSurfaceCount, 100_000); count(targetLive, 100_000)
            count(sourceCut.sourceCount, 300_000); count(targetSource, 300_000)
            count(sourceCut.supportCount, 300_000); count(targetSupport, 300_000)
            count(sourceCut.lineageCount, 200_000); count(targetLineage, 200_000)
            require(sourceCut.geometryRevision >= 0 && sourceCut.lineageRevision >= 0)
            require(targetGeometry >= sourceCut.geometryRevision && targetLineageRevision >= sourceCut.lineageRevision)
            require(walLength in 1..CompactCanonicalStore.JOURNAL_RESERVE_BYTES.toLong())
            require(currentLength in 1..CompactCanonicalStore.JOURNAL_RESERVE_BYTES.toLong())
            hash(sourceCut.rootHash); hash(sourceCut.sourceHash); hash(commandHash); hash(commandFingerprint)
            hash(walHash); hash(currentHash)
        }
        private fun countingHeader(header: DirtyIntentHeader): Long {
            val counter = JournalCountingOutputStream(); DataOutputStream(counter).use { header.writeWithoutChecksum(it) }; return counter.count
        }
        private fun digestCurrent(plan: PreparedCanonicalMutation): ByteArray {
            val digest = MessageDigest.getInstance("SHA-256")
            plan.writeCurrentTo(DigestOutputStream(NullOutputStream, digest))
            return digest.digest()
        }
        private fun digestWal(plan: PreparedCanonicalMutation): ByteArray {
            val digest = MessageDigest.getInstance("SHA-256")
            plan.writeWalTo(DigestOutputStream(NullOutputStream, digest))
            return digest.digest()
        }
    }
}

private class FaultingOutputStream(private val delegate: OutputStream, private val fault: CanonicalDirtyJournalFault?) : OutputStream() {
    var count = 0L; private var fired = false
    override fun write(value: Int) { write(byteArrayOf(value.toByte())) }
    override fun write(bytes: ByteArray, offset: Int, length: Int) {
        if (fault == CanonicalDirtyJournalFault.DURING_WAL_WRITE && !fired && length > 0) {
            fired = true
            val partial = maxOf(1, length / 2)
            delegate.write(bytes, offset, partial)
            count += partial
            error("fault")
        }
        delegate.write(bytes, offset, length)
        count += length
    }
}
private object NullOutputStream : OutputStream() { override fun write(value: Int) = Unit; override fun write(bytes: ByteArray, offset: Int, length: Int) = Unit }
private class JournalCountingOutputStream : OutputStream() { var count = 0L; override fun write(value: Int) { count++ }; override fun write(bytes: ByteArray, offset: Int, length: Int) { count += length } }
internal class BoundedInputStream(delegate: InputStream, skip: Long, private var remaining: Long) : InputStream() {
    private val source = delegate
    init { var left = skip; while (left > 0) { val skipped = source.skip(left); require(skipped > 0); left -= skipped } }
    override fun read(): Int = if (remaining == 0L) -1 else source.read().also { if (it >= 0) remaining-- }
    override fun read(bytes: ByteArray, offset: Int, length: Int): Int { if (remaining == 0L) return -1; val count = source.read(bytes, offset, minOf(length.toLong(), remaining).toInt()); if (count > 0) remaining -= count; return count }
    override fun close() = source.close()
}
private fun ByteArray.hex() = joinToString("") { "%02x".format(it) }
