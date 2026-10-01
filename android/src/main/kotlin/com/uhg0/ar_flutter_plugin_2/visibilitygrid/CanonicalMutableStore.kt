package com.uhg0.ar_flutter_plugin_2.visibilitygrid

import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.OutputStream
import java.security.MessageDigest

/** The only production seam from a prepared mutation to current private authority. */
internal interface CanonicalCommitStore : AutoCloseable {
    fun commit(
        plan: PreparedCanonicalMutation,
        generationZero: CanonicalStateView,
        faults: CanonicalCommitFaults = CanonicalCommitFaults(),
        acknowledgedCurrent: PreparedIntentCurrentReceipt? = null,
        authenticatedPrior: CanonicalPublishedCommit? = null,
    ): CanonicalCommitResult
    fun reopen(
        generationZero: CanonicalStateView,
        acknowledgedCurrent: PreparedIntentCurrentReceipt? = null,
    ): CanonicalReopenResult
    fun lookupCommit(query: CanonicalCommitQuery, generationZero: CanonicalStateView): CanonicalCommitLookup

    companion object {
        fun open(parent: File, budget: CanonicalStorageBudget): CanonicalCommitStore? = try {
            require(parent.exists() || parent.mkdirs()); CanonicalMutableStore(parent, budget)
        } catch (_: Exception) { null }
    }
}

private class CanonicalMutableStore(
    private val parent: File,
    private val budget: CanonicalStorageBudget,
) : CanonicalCommitStore {
    private var closed = false
    /** Reuses bounded sorted-record buffers across the dry-run and real write. */
    private val sortedScratch = CowSortedPageScratchPool()

    @Synchronized
    private fun stage(
        intent: PreparedIntent,
        baseView: CanonicalStateView,
        fault: CanonicalCowFault? = null,
    ): CanonicalCowStageResult {
        if (closed) return CanonicalCowStageResult.Refused(CanonicalCowRefusal.CLOSED, CowStorageReceipt(0, 0, 0))
        val identity = (intent.identity() as? PreparedIntentIdentityResult.Complete)?.identity
            ?: return refused(CanonicalCowRefusal.INVALID_INTENT)
        if (identity.sourceCut != baseView.cut) return refused(CanonicalCowRefusal.STALE_BASE)
        // Collapse dry-run metadata to scalars in this expression scope. Its
        // manifest graph is unreachable before the real writer constructs one.
        val preflight = CowStreamingWriter.dryRun(intent, baseView, sortedScratch)?.let { CowPreflight.from(identity, it) }
            ?: return refused(CanonicalCowRefusal.INVALID_INTENT)
        if (preflight.phasePeakBytes > CompactCanonicalStore.JOURNAL_RESERVE_BYTES ||
            preflight.directoryFileBytes > CanonicalCowGeneration.DIRECTORY_LIMIT_BYTES ||
            preflight.rootFileBytes > CanonicalCowGeneration.DIRECTORY_LIMIT_BYTES)
            return refused(CanonicalCowRefusal.PHASE_OR_DIRECTORY_LIMIT)
        val target = commandDirectory(identity)
        val deterministicStaging = File(parent, ".${target.name}.staging")
        try { budget.reconcileCandidate(target) } catch (_: Exception) {
            return refused(CanonicalCowRefusal.DURABILITY_FAILURE)
        }
        if (!target.exists() && deterministicStaging.exists() && !deterministicStaging.deleteRecursively())
            return refused(CanonicalCowRefusal.DURABILITY_FAILURE)
        if (target.exists()) {
            val existing = CanonicalCowGeneration.open(target)
            if (existing != null && existing.root.matches(identity, preflight.current))
                return CanonicalCowStageResult.Prepared(existing.withAllocatedStorage(budget.allocatedBytes(target)), reused = true)
            return refused(if (existing != null && existing.root.commandId == identity.commandId && existing.root.commandKind == identity.kind) CanonicalCowRefusal.IDENTITY_CONFLICT else CanonicalCowRefusal.CORRUPT_GENERATION)
        }

        var token: Any? = null
        var staging: File? = null
        var published = false
        try {
            inject(fault, CanonicalCowFault.BEFORE_RESERVATION)
            staging = deterministicStaging
            val files = linkedMapOf<String, Long>()
            preflight.pageCounts.forEach { (kind, pages) -> if (pages > 0) files[kind.file] = pages.toLong() * CanonicalCowGeneration.PAGE_BYTES }
            files.putAll(preflight.temporaryFiles)
            files[CanonicalCowGeneration.CURRENT_UNACKED_FILE] = preflight.current.length
            files["root.m3cow"] = preflight.rootFileBytes
            files["directory.m3cow"] = preflight.directoryFileBytes
            val unit = budget.allocationUnitBytes(parent)
            // Candidate files plus staging/target directory entries, reservation
            // ledger replacement, and rollback/coexistence metadata.
            val worst = (files.values + listOf(1L, 1L, 1L, 1L)).fold(0L) { total, bytes -> Math.addExact(total, round(bytes, unit)) }
            token = budget.reserveCandidate(staging, target, files, worst) ?: return refused(CanonicalCowRefusal.QUOTA_REFUSED)
            inject(fault, CanonicalCowFault.AFTER_RESERVATION)
            budget.verifyCandidate(token, staging)
            inject(fault, CanonicalCowFault.BEFORE_FRAGMENT_WRITE)
            val currentFile = File(staging, CanonicalCowGeneration.CURRENT_UNACKED_FILE)
            val written = CowStreamingWriter.write(intent, baseView, staging, currentFile, fault, sortedScratch)
                ?: return refused(CanonicalCowRefusal.INVALID_INTENT)
            if (written.pageCounts != preflight.pageCounts || written.current != preflight.current) return refused(CanonicalCowRefusal.CORRUPT_GENERATION)
            val root = MutableSemanticRoot(
                identity.sourceCut, identity.commandId, identity.kind, identity.commandHash, identity.commandFingerprint,
                identity.targetHighWater, identity.targetLive, identity.targetSource, identity.targetSupport,
                identity.targetLineage, identity.targetGeometry, identity.targetLineageRevision,
                written.current, written.entries,
            )
            val generationIdentity = CowGenerationIdentity.from(root)
            inject(fault, CanonicalCowFault.AFTER_FRAGMENT_WRITE)
            verifyCurrent(currentFile, preflight.current)
            budget.verifyCandidate(token, staging)
            inject(fault, CanonicalCowFault.BEFORE_ROOT_WRITE)
            root.write(CanonicalCowGeneration.rootFile(staging), fault == CanonicalCowFault.DURING_ROOT_WRITE)
            inject(fault, CanonicalCowFault.AFTER_ROOT_WRITE)
            inject(fault, CanonicalCowFault.BEFORE_DIRECTORY_WRITE)
            CowDirectoryEntry.write(CanonicalCowGeneration.directoryFile(staging), written.entries, fault == CanonicalCowFault.DURING_DIRECTORY_WRITE)
            inject(fault, CanonicalCowFault.AFTER_DIRECTORY_WRITE)
            budget.verifyCandidate(token, staging)
            inject(fault, CanonicalCowFault.BEFORE_STAGING_SYNC)
            CanonicalCowGeneration.sync(staging)
            inject(fault, CanonicalCowFault.AFTER_STAGING_SYNC)
            inject(fault, CanonicalCowFault.BEFORE_RENAME)
            budget.publishCandidate(token, staging, target)
            published = true
            inject(fault, CanonicalCowFault.AFTER_RENAME)
            budget.verifyCandidate(token, target)
            inject(fault, CanonicalCowFault.BEFORE_PARENT_SYNC)
            CanonicalCowGeneration.sync(parent)
            inject(fault, CanonicalCowFault.AFTER_PARENT_SYNC)
            inject(fault, CanonicalCowFault.BEFORE_BUDGET_COMMIT)
            budget.commit(token, budget.allocatedBytes(target)); token = null
            inject(fault, CanonicalCowFault.AFTER_BUDGET_COMMIT)
            inject(fault, CanonicalCowFault.BEFORE_REOPEN)
            val generation = CanonicalCowGeneration.open(target, generationIdentity)?.withAllocatedStorage(budget.allocatedBytes(target))
                ?: return refused(CanonicalCowRefusal.CORRUPT_GENERATION)
            inject(fault, CanonicalCowFault.AFTER_REOPEN)
            inject(fault, CanonicalCowFault.BEFORE_CLEANUP)
            if (staging.exists()) staging.deleteRecursively()
            inject(fault, CanonicalCowFault.AFTER_CLEANUP)
            return CanonicalCowStageResult.Prepared(generation, reused = false)
        } catch (_: Exception) {
            return refused(CanonicalCowRefusal.DURABILITY_FAILURE)
        } finally {
            token?.let { reservation ->
                try {
                    if (published || target.exists()) budget.commit(reservation, budget.allocatedBytes(target))
                    else staging?.let { budget.releaseCandidate(reservation, it) } ?: budget.release(reservation)
                } catch (_: Exception) { }
            }
        }
    }

    @Synchronized
    override fun commit(
        plan: PreparedCanonicalMutation,
        generationZero: CanonicalStateView,
        faults: CanonicalCommitFaults,
        acknowledgedCurrent: PreparedIntentCurrentReceipt?,
        authenticatedPrior: CanonicalPublishedCommit?,
    ): CanonicalCommitResult {
        if (closed) return CanonicalCommitResult.Refused(CanonicalCommitRefusal.CLOSED)
        var selected: CanonicalPublishedCommit? = null
        var intent: PreparedIntent? = null
        var generation: CanonicalCowGeneration? = null
        try {
            val current = authenticatedPrior?.view ?: when (val reopened = PrivateRootSelector(parent, budget).reopen(generationZero, acknowledgedCurrent)) {
                is CanonicalReopenResult.GenerationZero -> generationZero
                is CanonicalReopenResult.Selected -> {
                    selected = reopened.commit
                    val newest = reopened.commit.roots.lastOrNull()
                        ?: return CanonicalCommitResult.Refused(CanonicalCommitRefusal.SELECTOR_REFUSED)
                    if (acknowledgedCurrent != null && newest.current == acknowledgedCurrent) {
                        reopened.commit.view
                    } else {
                        val current = try { currentReceipt(plan) } catch (_: Exception) {
                            return CanonicalCommitResult.Refused(CanonicalCommitRefusal.INVALID_INTENT)
                        }
                        if (newest.matches(plan, current)) {
                            selected = null
                            return CanonicalCommitResult.Committed(reopened.commit, replayed = true)
                        }
                        return CanonicalCommitResult.Refused(CanonicalCommitRefusal.CURRENT_PENDING)
                    }
                }
                is CanonicalReopenResult.Refused -> return CanonicalCommitResult.Refused(
                    CanonicalCommitRefusal.SELECTOR_REFUSED, selectorReason = reopened.reason,
                )
            }
            if (plan.sourceCut != current.cut)
                return CanonicalCommitResult.Refused(CanonicalCommitRefusal.STALE_BASE)
            val journal = when (val opened = CanonicalDirtyJournal.open(current, parent, budget)) {
                is CanonicalDirtyJournalOpenResult.Opened -> opened.journal
                is CanonicalDirtyJournalOpenResult.Refused -> return CanonicalCommitResult.Refused(
                    CanonicalCommitRefusal.JOURNAL_REFUSED, journalReason = opened.reason,
                )
            }
            try {
                if ((selected != null || authenticatedPrior != null) && acknowledgedCurrent != null &&
                    !journal.reclaimAcknowledgedIntent(acknowledgedCurrent)
                )
                    return CanonicalCommitResult.Refused(CanonicalCommitRefusal.JOURNAL_REFUSED,
                        journalReason = CanonicalDirtyJournalRefusal.CORRUPT_INTENT)
                intent = when (val durable = journal.reopen()) {
                    is CanonicalDirtyJournalReopenResult.Complete -> durable.intent
                    is CanonicalDirtyJournalReopenResult.None -> when (val flushed = journal.flush(plan, faults.journal)) {
                        is CanonicalDirtyJournalFlushResult.Prepared -> flushed.intent
                        is CanonicalDirtyJournalFlushResult.Refused -> return CanonicalCommitResult.Refused(
                            CanonicalCommitRefusal.JOURNAL_REFUSED, journalReason = flushed.reason,
                        )
                    }
                    is CanonicalDirtyJournalReopenResult.Refused -> return CanonicalCommitResult.Refused(
                        CanonicalCommitRefusal.JOURNAL_REFUSED, journalReason = durable.reason,
                    )
                }
                val identity = (requireNotNull(intent).identity() as? PreparedIntentIdentityResult.Complete)?.identity
                    ?: return CanonicalCommitResult.Refused(CanonicalCommitRefusal.INVALID_INTENT)
                if (!identity.matches(plan)) return CanonicalCommitResult.Refused(
                    if (identity.commandId == plan.commandId && identity.kind == plan.kind)
                        CanonicalCommitRefusal.IDENTITY_CONFLICT else CanonicalCommitRefusal.STALE_BASE,
                )
                when (val staged = stage(requireNotNull(intent), current, faults.cow)) {
                    is CanonicalCowStageResult.Prepared -> generation = staged.generation
                    is CanonicalCowStageResult.Refused -> return CanonicalCommitResult.Refused(
                        if (staged.reason == CanonicalCowRefusal.IDENTITY_CONFLICT) CanonicalCommitRefusal.IDENTITY_CONFLICT
                        else CanonicalCommitRefusal.COW_REFUSED,
                        cowReason = staged.reason,
                    )
                }
                return when (val published = publish(
                    requireNotNull(generation), generationZero, faults.selector,
                    acknowledgedCurrent, authenticatedPrior,
                )) {
                    is CanonicalPublishResult.Committed -> {
                        if (authenticatedPrior != null) generation = null // successor owns the transferred generation
                        CanonicalCommitResult.Committed(published.commit, published.replayed)
                    }
                    is CanonicalPublishResult.UnknownAfterSwitch -> CanonicalCommitResult.UnknownAfterSwitch(published.receipt)
                    is CanonicalPublishResult.Refused -> CanonicalCommitResult.Refused(
                        if (published.reason == CanonicalSelectorRefusal.IDENTITY_CONFLICT) CanonicalCommitRefusal.IDENTITY_CONFLICT
                        else CanonicalCommitRefusal.SELECTOR_REFUSED,
                        selectorReason = published.reason,
                    )
                }
            } finally { journal.close() }
        } finally {
            generation?.close(); intent?.close(); selected?.close()
        }
    }

    /** #123's private publication seam. The immutable v6 generation remains separate. */
    @Synchronized
    private fun publish(
        generation: CanonicalCowGeneration,
        generationZero: CanonicalStateView,
        fault: CanonicalSelectorFault? = null,
        acknowledgedCurrent: PreparedIntentCurrentReceipt? = null,
        authenticatedPrior: CanonicalPublishedCommit? = null,
    ): CanonicalPublishResult {
        if (closed) return CanonicalPublishResult.Refused(CanonicalSelectorRefusal.CLOSED)
        return PrivateRootSelector(parent, budget).publish(
            generation, generationZero, fault, acknowledgedCurrent, authenticatedPrior,
        )
    }

    /** Reopens exactly the selected private cut, or generation zero only if no selector exists. */
    @Synchronized
    override fun reopen(
        generationZero: CanonicalStateView,
        acknowledgedCurrent: PreparedIntentCurrentReceipt?,
    ): CanonicalReopenResult {
        if (closed) return CanonicalReopenResult.Refused(CanonicalSelectorRefusal.CLOSED)
        return PrivateRootSelector(parent, budget).reopen(generationZero, acknowledgedCurrent)
    }

    /** Process-recovery lookup; changed bytes under one command identity fail closed. */
    @Synchronized
    override fun lookupCommit(query: CanonicalCommitQuery, generationZero: CanonicalStateView): CanonicalCommitLookup {
        if (closed) return CanonicalCommitLookup.Refused(CanonicalSelectorRefusal.CLOSED)
        return PrivateRootSelector(parent, budget).lookup(query, generationZero)
    }

    @Synchronized
    override fun close() { closed = true; sortedScratch.clear() }
    private fun refused(reason: CanonicalCowRefusal) = CanonicalCowStageResult.Refused(reason, CowStorageReceipt(0, 0, CanonicalCowGeneration.FIXED_PHASE_BYTES))
    private fun inject(requested: CanonicalCowFault?, point: CanonicalCowFault) {
        if (requested == point) error("fault:$point")
    }

    /** Exact base + command identity is a deterministic bounded namespace. */
    private fun commandDirectory(identity: PreparedIntentIdentity): File {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(identity.sourceCut.rootHash.toByteArray()); digest.update(identity.sourceCut.sourceHash.toByteArray())
        digest.update(identity.commandId.encodeToByteArray()); digest.update(identity.kind.ordinal.toByte())
        return File(parent, "canonical-surface-cow-command-${digest.digest().joinToString("") { "%02x".format(it) }}")
    }

    companion object {
        private fun round(bytes: Long, unit: Long) = if (bytes == 0L) 0L else Math.multiplyExact((bytes - 1L) / unit + 1L, unit)
        private fun verifyCurrent(file: File, expected: PreparedIntentCurrentReceipt) {
            require(file.length() == expected.length)
            val digest = MessageDigest.getInstance("SHA-256")
            FileInputStream(file).use { input ->
                val scratch = ByteArray(PreparedIntentVisitorResources.STREAMING_SCRATCH_BYTES)
                while (true) { val count = input.read(scratch); if (count < 0) break; digest.update(scratch, 0, count) }
            }
            require(CanonicalReceiptBytes(digest.digest()) == expected.hash)
        }

        private fun currentReceipt(plan: PreparedCanonicalMutation): PreparedIntentCurrentReceipt {
            val digest = MessageDigest.getInstance("SHA-256")
            var length = 0L
            plan.writeCurrentTo(object : OutputStream() {
                override fun write(value: Int) {
                    digest.update(value.toByte()); length = Math.addExact(length, 1L)
                }
                override fun write(bytes: ByteArray, offset: Int, count: Int) {
                    digest.update(bytes, offset, count); length = Math.addExact(length, count.toLong())
                }
            })
            return PreparedIntentCurrentReceipt(length, CanonicalReceiptBytes(digest.digest()))
        }
    }
}

internal data class CanonicalCommitFaults(
    val journal: CanonicalDirtyJournalFault? = null,
    val cow: CanonicalCowFault? = null,
    val selector: CanonicalSelectorFault? = null,
    val adjacent: CanonicalAdjacentFault? = null,
)

internal sealed interface CanonicalCommitResult {
    data class Committed(val commit: CanonicalPublishedCommit, val replayed: Boolean) : CanonicalCommitResult
    data class UnknownAfterSwitch(val receipt: CanonicalPublicationReceipt) : CanonicalCommitResult
    data class Refused(
        val reason: CanonicalCommitRefusal,
        val journalReason: CanonicalDirtyJournalRefusal? = null,
        val cowReason: CanonicalCowRefusal? = null,
        val selectorReason: CanonicalSelectorRefusal? = null,
    ) : CanonicalCommitResult
}

internal enum class CanonicalCommitRefusal {
    CLOSED, STALE_BASE, CURRENT_PENDING, INVALID_INTENT, IDENTITY_CONFLICT, JOURNAL_REFUSED, COW_REFUSED, SELECTOR_REFUSED,
}

private fun PublishedRoot.matches(plan: PreparedCanonicalMutation, current: PreparedIntentCurrentReceipt) =
    isCommand(plan.commandId) && commandKind == plan.kind && commandHash == plan.commandHash &&
        commandFingerprint == plan.commandFingerprint && baseRootHash == plan.sourceCut.rootHash && this.current == current

private fun PreparedIntentIdentity.matches(plan: PreparedCanonicalMutation) =
    sourceCut == plan.sourceCut && commandHash == plan.commandHash && commandFingerprint == plan.commandFingerprint &&
        commandId == plan.commandId && kind == plan.kind && targetHighWater == plan.targetHighWater &&
        targetLive == plan.targetLiveSurfaceCount && targetSource == plan.targetSourceCount &&
        targetSupport == plan.targetSupportCount && targetLineage == plan.targetLineageCount &&
        targetGeometry == plan.targetGeometryRevision && targetLineageRevision == plan.targetLineageRevision

internal sealed interface CanonicalCowStageResult {
    data class Prepared(val generation: CanonicalCowGeneration, val reused: Boolean) : CanonicalCowStageResult
    data class Refused(val reason: CanonicalCowRefusal, val storage: CowStorageReceipt) : CanonicalCowStageResult
}
internal enum class CanonicalCowRefusal { CLOSED, INVALID_INTENT, STALE_BASE, QUOTA_REFUSED, IDENTITY_CONFLICT, CORRUPT_GENERATION, PHASE_OR_DIRECTORY_LIMIT, DURABILITY_FAILURE }
internal enum class CanonicalCowFault {
    BEFORE_RESERVATION, AFTER_RESERVATION, BEFORE_FRAGMENT_WRITE, DURING_FRAGMENT_WRITE, AFTER_FRAGMENT_WRITE,
    BEFORE_ROOT_WRITE, DURING_ROOT_WRITE, AFTER_ROOT_WRITE, BEFORE_DIRECTORY_WRITE, DURING_DIRECTORY_WRITE, AFTER_DIRECTORY_WRITE,
    BEFORE_STAGING_SYNC, AFTER_STAGING_SYNC, BEFORE_RENAME, AFTER_RENAME, BEFORE_PARENT_SYNC, AFTER_PARENT_SYNC,
    BEFORE_BUDGET_COMMIT, AFTER_BUDGET_COMMIT, BEFORE_REOPEN, AFTER_REOPEN, BEFORE_CLEANUP, AFTER_CLEANUP,
}

private data class CowWriteReceipt(
    val entries: List<CowDirectoryEntry>, val current: PreparedIntentCurrentReceipt,
    val pageCounts: Map<CowFragmentKind, Int>, val temporaryFiles: Map<String, Long>, val phasePeakBytes: Long,
)

private data class CowPreflight(
    val current: PreparedIntentCurrentReceipt,
    val pageCounts: Map<CowFragmentKind, Int>,
    val temporaryFiles: Map<String, Long>,
    val phasePeakBytes: Long,
    val directoryFileBytes: Long,
    val rootFileBytes: Long,
) {
    companion object {
        fun from(identity: PreparedIntentIdentity, receipt: CowWriteReceipt): CowPreflight {
            val root = MutableSemanticRoot(
                identity.sourceCut, identity.commandId, identity.kind, identity.commandHash, identity.commandFingerprint,
                identity.targetHighWater, identity.targetLive, identity.targetSource, identity.targetSupport,
                identity.targetLineage, identity.targetGeometry, identity.targetLineageRevision,
                receipt.current, receipt.entries,
            )
            return CowPreflight(
                receipt.current,
                receipt.pageCounts,
                receipt.temporaryFiles,
                receipt.phasePeakBytes,
                CowDirectoryEntry.encodedFileBytes(receipt.entries),
                root.encodedBytes(),
            )
        }
    }
}

/** Uses only #121's scalar callbacks.  A fragment page is the largest retained write state. */
private class CowStreamingWriter private constructor(
    private val directory: File?,
    private val base: CanonicalStateView,
    private val fault: CanonicalCowFault?,
    private val sortedScratch: CowSortedPageScratchPool,
) : PreparedIntentVisitor {
    private val writers: Map<CowFragmentKind, CowRecordWriter> = CowFragmentKind.entries.associateWith { kind ->
        if (kind in SORTED_KINDS) CowSortedPageWriter(directory, kind, fault, sortedScratch) else CowPageWriter(directory, kind, fault)
    }
    private var identity: PreparedIntentIdentity? = null
    private var terminal: PreparedIntentCurrentReceipt? = null
    private var previousSource: CowRow? = null
    override fun onHeader(identity: PreparedIntentIdentity): Boolean { this.identity = identity; return true }
    override fun onDirtyRow(id: Long, x: Int, y: Int, z: Int, packedNormal: Int, confidence: Int, fingerprint0: Long, fingerprint1: Long, fingerprint2: Long, fingerprint3: Long): Boolean {
        val row = writers.getValue(CowFragmentKind.ROW).record(id) { out -> row(out, id, x, y, z, packedNormal, confidence, fingerprint0, fingerprint1, fingerprint2, fingerprint3) }
        val idIndex = writers.getValue(CowFragmentKind.ID_INDEX).record(id) { it.writeLong(id) }
        val voxelKey = voxelKey(x, y, z)
        val voxel = writers.getValue(CowFragmentKind.VOXEL_INDEX).record(voxelKey) { out -> out.writeInt(x); out.writeInt(y); out.writeInt(z); out.writeLong(id) }
        val location = CompactLocation(SurfaceOwnershipConfiguration(), Voxel(x, y, z)) ?: return false
        val page = writers.getValue(CowFragmentKind.PAGE_INDEX).record(pageKey(location.region, location.page)) { out -> out.writeInt(x); out.writeInt(y); out.writeInt(z); out.writeLong(id) }
        val old = base.findById(SurfaceId(id))
        val moved = old != null && old.voxel != Voxel(x, y, z)
        val voxelTombstone = !moved || writers.getValue(CowFragmentKind.VOXEL_TOMBSTONE).record(voxelKey(old.voxel.x, old.voxel.y, old.voxel.z)) { out -> out.writeInt(old.voxel.x); out.writeInt(old.voxel.y); out.writeInt(old.voxel.z); out.writeLong(id) }
        val oldLocation = old?.let { CompactLocation(SurfaceOwnershipConfiguration(), it.voxel) }
        val pageTombstone = !moved || oldLocation != null && writers.getValue(CowFragmentKind.PAGE_TOMBSTONE).record(pageKey(oldLocation.region, oldLocation.page)) { out -> out.writeInt(old.voxel.x); out.writeInt(old.voxel.y); out.writeInt(old.voxel.z); out.writeLong(id) }
        return row && idIndex && voxel && page && voxelTombstone && pageTombstone
    }
    override fun onRemovedId(id: Long): Boolean {
        val removed = writers.getValue(CowFragmentKind.ID_TOMBSTONE).record(id) { it.writeLong(id) }
        val old = base.findById(SurfaceId(id)) ?: return false
        val voxelTombstone = writers.getValue(CowFragmentKind.VOXEL_TOMBSTONE).record(voxelKey(old.voxel.x, old.voxel.y, old.voxel.z)) { out -> out.writeInt(old.voxel.x); out.writeInt(old.voxel.y); out.writeInt(old.voxel.z); out.writeLong(id) }
        val oldLocation = CompactLocation(SurfaceOwnershipConfiguration(), old.voxel) ?: return false
        val pageTombstone = writers.getValue(CowFragmentKind.PAGE_TOMBSTONE).record(pageKey(oldLocation.region, oldLocation.page)) { out -> out.writeInt(old.voxel.x); out.writeInt(old.voxel.y); out.writeInt(old.voxel.z); out.writeLong(id) }
        val supportTombstone = writers.getValue(CowFragmentKind.SUPPORT_TOMBSTONE).record(id) { it.writeLong(id) }
        val lineageTombstone = writers.getValue(CowFragmentKind.LINEAGE_TOMBSTONE).record(id) { out -> out.writeLong(id); out.writeLong(0L) }
        return removed && voxelTombstone && pageTombstone && supportTombstone && lineageTombstone
    }
    override fun onDirtySupport(targetId: Long, sourceId: Long, x: Int, y: Int, z: Int, packedNormal: Int, confidence: Int, fingerprint0: Long, fingerprint1: Long, fingerprint2: Long, fingerprint3: Long): Boolean {
        val existing = when (val read = base.readSourceById(SurfaceId(sourceId))) { is CanonicalPageRead.Complete -> read.value; is CanonicalPageRead.Refused -> return false }
        val candidate = CowRow(sourceId, x, y, z, packedNormal, confidence, fingerprint0, fingerprint1, fingerprint2, fingerprint3).source()
        if (existing != null && existing != candidate) return false
        return writers.getValue(CowFragmentKind.SUPPORT).record(targetId) { out -> out.writeLong(targetId); row(out, sourceId, x, y, z, packedNormal, confidence, fingerprint0, fingerprint1, fingerprint2, fingerprint3) }
    }
    override fun onDirtySource(id: Long, x: Int, y: Int, z: Int, packedNormal: Int, confidence: Int, fingerprint0: Long, fingerprint1: Long, fingerprint2: Long, fingerprint3: Long): Boolean {
        val candidate = CowRow(id, x, y, z, packedNormal, confidence, fingerprint0, fingerprint1, fingerprint2, fingerprint3)
        val prior = previousSource
        if (prior != null && prior.id == id && prior != candidate) return false
        val existing = when (val read = base.readSourceById(SurfaceId(id))) {
            is CanonicalPageRead.Complete -> read.value
            is CanonicalPageRead.Refused -> return false
        }
        if (existing != null && existing != candidate.source()) return false
        previousSource = candidate
        return writers.getValue(CowFragmentKind.SOURCE).record(id) { out -> row(out, id, x, y, z, packedNormal, confidence, fingerprint0, fingerprint1, fingerprint2, fingerprint3) }
    }
    override fun onDirtyLineage(sourceId: Long, targetId: Long) = writers.getValue(CowFragmentKind.LINEAGE).record(sourceId) { out -> out.writeLong(sourceId); out.writeLong(targetId) }
    override fun onTerminal(currentReceipt: PreparedIntentCurrentReceipt): Boolean { terminal = currentReceipt; return true }
    fun finish(): CowWriteReceipt? {
        val current = terminal ?: run { writers.values.forEach(CowRecordWriter::abort); return null }
        val entries = ArrayList<CowDirectoryEntry>()
        for (writer in writers.values) {
            val written = writer.finish() ?: run {
                writers.values.forEach(CowRecordWriter::abort)
                return null
            }
            entries += written
        }
        return CowWriteReceipt(
            entries,
            current,
            writers.mapValues { it.value.pages },
            writers.values.flatMap { it.temporaryFiles().entries }.associate { it.toPair() },
            CanonicalCowGeneration.phasePeakBytes(entries.size),
        )
    }
    companion object {
        fun dryRun(intent: PreparedIntent, base: CanonicalStateView, sortedScratch: CowSortedPageScratchPool): CowWriteReceipt? {
            val writer = CowStreamingWriter(null, base, null, sortedScratch)
            return if (intent.visit(writer) is PreparedIntentVisitResult.Complete) writer.finish() else null
        }
        fun write(
            intent: PreparedIntent,
            base: CanonicalStateView,
            directory: File,
            current: File,
            fault: CanonicalCowFault?,
            sortedScratch: CowSortedPageScratchPool,
        ): CowWriteReceipt? {
            val writer = CowStreamingWriter(directory, base, fault, sortedScratch)
            return FileOutputStream(current, false).use { output ->
                val result = intent.visitCurrent(writer, output)
                output.fd.sync()
                if (result is PreparedIntentVisitResult.Complete) writer.finish() else {
                    writer.writers.values.forEach(CowRecordWriter::abort)
                    null
                }
            }
        }
        private fun row(out: DataOutputStream, id: Long, x: Int, y: Int, z: Int, normal: Int, confidence: Int, f0: Long, f1: Long, f2: Long, f3: Long) { out.writeLong(id); out.writeInt(x); out.writeInt(y); out.writeInt(z); out.writeInt(normal); out.writeInt(confidence); out.writeLong(f0); out.writeLong(f1); out.writeLong(f2); out.writeLong(f3) }
        private fun voxelKey(x: Int, y: Int, z: Int) = CanonicalCowGeneration.voxelKey(x, y, z)
        private fun pageKey(region: StorageRegion, page: Int) = CanonicalCowGeneration.pageKey(region, page)
        private val SORTED_KINDS = setOf(CowFragmentKind.VOXEL_INDEX, CowFragmentKind.PAGE_INDEX, CowFragmentKind.VOXEL_TOMBSTONE, CowFragmentKind.PAGE_TOMBSTONE)
    }
}

/**
 * Process-local pool for the small, bounded arrays used while preparing a
 * sorted fragment.  A pool is owned by one canonical store, so a buffer is
 * never returned while a writer can still use it.  Large external-sort
 * batches do not retain their full record stream here.
 */
internal class CowSortedPageScratchPool {
    private val available = ArrayDeque<ByteArray>()
    private var availableBytes = 0

    @Synchronized
    fun acquire(minimumBytes: Int): ByteArray {
        val candidate = available.firstOrNull { it.size >= minimumBytes }
        if (candidate != null) {
            available.remove(candidate)
            availableBytes -= candidate.size
            return candidate
        }
        return ByteArray(minimumBytes)
    }

    @Synchronized
    fun release(bytes: ByteArray?) {
        if (bytes == null || bytes.isEmpty() || bytes.size > CowSortedPageWriter.MAX_RETAINED_POOL_BYTES) return
        if (available.size >= CowSortedPageWriter.MAX_RETAINED_BUFFERS) return
        if (availableBytes > CowSortedPageWriter.MAX_RETAINED_POOL_BYTES - bytes.size) return
        available.addLast(bytes)
        availableBytes += bytes.size
    }

    @Synchronized
    fun clear() { available.clear(); availableBytes = 0 }

    @Synchronized
    fun retainedBufferCount() = available.size

    @Synchronized
    fun retainedBytes() = availableBytes
}

/** Fixed-size output used by every record in a writer; it allocates no stream per callback. */
private class FixedRecordOutput : OutputStream() {
    private var bytes: ByteArray? = null
    private var position = 0
    private var limit = 0

    fun reset(target: ByteArray, offset: Int, length: Int) {
        bytes = target
        position = offset
        limit = offset + length
    }

    val written get() = position

    override fun write(value: Int) {
        check(position < limit)
        requireNotNull(bytes)[position++] = value.toByte()
    }

    override fun write(value: ByteArray, offset: Int, length: Int) {
        check(length >= 0 && position + length <= limit)
        value.copyInto(requireNotNull(bytes), position, offset, offset + length)
        position += length
    }
}

private interface CowRecordWriter {
    val kind: CowFragmentKind
    val pages: Int
    fun record(key: Long, write: (DataOutputStream) -> Unit): Boolean
    fun finish(): List<CowDirectoryEntry>?
    fun abort() = Unit
    fun temporaryFiles(): Map<String, Long> = emptyMap()
}

private class CowPageWriter(private val directory: File?, override val kind: CowFragmentKind, private val fault: CanonicalCowFault?) : CowRecordWriter {
    private var bytes = ByteArray(CanonicalCowGeneration.PAGE_BYTES); private var count = 0; private var position = 12
    private val recordOutput = FixedRecordOutput()
    private val recordStream = DataOutputStream(recordOutput)
    private val entries = ArrayList<CowDirectoryEntry>(); override var pages = 0; private var failed = false; private var minimum = Long.MAX_VALUE; private var maximum = Long.MIN_VALUE
    override fun record(key: Long, write: (DataOutputStream) -> Unit): Boolean {
        if (failed) return false
        if (count == kind.recordsPerPage) flush()
        return try {
            // Record encoding is fixed-size; encode directly into the owned 16 KiB page.
            val start = position
            recordOutput.reset(bytes, start, kind.recordBytes)
            write(recordStream)
            position = recordOutput.written
            require(position == start + kind.recordBytes)
            if (count == 0) { minimum = key; maximum = key }
            else {
                if (java.lang.Long.compareUnsigned(key, minimum) < 0) minimum = key
                if (java.lang.Long.compareUnsigned(key, maximum) > 0) maximum = key
            }
            count++; true
        } catch (_: Exception) { failed = true; false }
    }

    /** Writes an already encoded payload without allocating a callback stream or payload array. */
    fun recordEncoded(key: Long, payload: ByteArray, offset: Int): Boolean = try {
        if (failed) return false
        if (count == kind.recordsPerPage) flush()
        val start = position
        payload.copyInto(bytes, start, offset, offset + kind.recordBytes)
        position = start + kind.recordBytes
        require(position == 12 + (count + 1) * kind.recordBytes)
        if (count == 0) { minimum = key; maximum = key }
        else {
            if (java.lang.Long.compareUnsigned(key, minimum) < 0) minimum = key
            if (java.lang.Long.compareUnsigned(key, maximum) > 0) maximum = key
        }
        count++
        true
    } catch (_: Exception) { failed = true; false }
    override fun finish(): List<CowDirectoryEntry>? { if (failed) return null; if (count > 0) flush(); return if (failed) null else entries }
    private fun flush() {
        if (count == 0 || failed) return
        try {
            java.nio.ByteBuffer.wrap(bytes).putInt(0, CanonicalCowGeneration.PAGE_MAGIC).putInt(4, kind.wire).putInt(8, count)
            if (fault == CanonicalCowFault.DURING_FRAGMENT_WRITE && pages == 0) { failed = true; return }
            val hash = CanonicalCowGeneration.sha(bytes)
            directory?.let { dir ->
                dir.mkdirs()
                java.io.RandomAccessFile(File(dir, kind.file), "rw").use { output ->
                    output.seek(pages.toLong() * CanonicalCowGeneration.PAGE_BYTES)
                    output.write(bytes); output.fd.sync()
                }
            }
            entries += CowDirectoryEntry(kind, pages, minimum, maximum, pages.toLong() * CanonicalCowGeneration.PAGE_BYTES, CanonicalCowGeneration.PAGE_BYTES, count, hash); pages++
            // The page has already been hashed and written. Clear only the
            // old record region and retain the page allocation for the next
            // fragment page; the header is rewritten at the next flush.
            java.util.Arrays.fill(bytes, 12, bytes.size, 0)
            count = 0; position = 12; minimum = Long.MAX_VALUE; maximum = Long.MIN_VALUE
        } catch (_: Exception) { failed = true }
    }
}

/**
 * Stable radix sort for canonical indexes.  Bounded fragments stay in two
 * reusable in-memory buffers; large fragments retain the external two-spool
 * implementation so the commit remains bounded by the storage reservation.
 */
internal class CowSortedPageWriter(
    private val directory: File?,
    override val kind: CowFragmentKind,
    private val fault: CanonicalCowFault?,
    private val scratch: CowSortedPageScratchPool,
    private val inMemoryRecordLimit: Int = IN_MEMORY_RECORD_LIMIT,
) : CowRecordWriter {
    private val recordBytes = 8 + kind.recordBytes
    private val spoolAName = "${kind.file}.sort-a"
    private val spoolBName = "${kind.file}.sort-b"
    private val spoolA = directory?.let { File(it, spoolAName) }
    private val spoolB = directory?.let { File(it, spoolBName) }
    private var output: java.io.RandomAccessFile? = null
    private var records = 0
    private var failed = false
    private var external = false
    private var released = false
    private var recordBuffer: ByteArray? = null
    private var sortBuffer: ByteArray? = null
    private var externalPayload: ByteArray? = null
    private var radixBytes: ByteArray? = null
    private var radixCounts: LongArray? = null
    private var radixOffsets: LongArray? = null
    private val recordOutput = FixedRecordOutput()
    private val recordStream = DataOutputStream(recordOutput)
    override val pages get() = if (records == 0) 0 else (records - 1) / kind.recordsPerPage + 1

    override fun record(key: Long, write: (DataOutputStream) -> Unit): Boolean = try {
        if (failed) return false
        if (!external && directory != null && records >= inMemoryRecordLimit) switchToExternal()
        if (external) {
            val payload = requireNotNull(externalPayload)
            recordOutput.reset(payload, 0, kind.recordBytes)
            write(recordStream)
            require(recordOutput.written == kind.recordBytes)
            requireNotNull(output).writeLong(key)
            requireNotNull(output).write(payload, 0, kind.recordBytes)
        } else {
            val payload = if (directory == null) {
                val candidate = externalPayload ?: ByteArray(kind.recordBytes).also { externalPayload = it }
                candidate
            } else {
                ensureRecordCapacity(records + 1)
                requireNotNull(recordBuffer)
            }
            val offset = if (directory == null) 0 else records * recordBytes + 8
            recordOutput.reset(payload, offset, kind.recordBytes)
            write(recordStream)
            require(recordOutput.written == offset + kind.recordBytes)
            if (directory != null) writeLong(recordBuffer!!, records * recordBytes, key)
        }
        records++
        true
    } catch (_: Exception) { failed = true; false }

    override fun temporaryFiles(): Map<String, Long> {
        if (records == 0 || (!external && records <= inMemoryRecordLimit)) return emptyMap()
        val bytes = records.toLong() * recordBytes
        return mapOf(spoolAName to bytes, spoolBName to bytes)
    }

    override fun abort() {
        try { output?.close() } catch (_: Exception) { }
        output = null
        releaseBuffers()
    }

    override fun finish(): List<CowDirectoryEntry>? {
        if (failed) return null
        if (directory == null) return try {
            dummyEntries().also { releaseBuffers() }
        } catch (_: Exception) { failed = true; null }
        return try {
            val entries = if (external) finishExternal() else finishInMemory()
            releaseBuffers()
            entries
        } catch (_: Exception) {
            failed = true
            abort()
            null
        }
    }

    private fun finishInMemory(): List<CowDirectoryEntry> {
        if (records == 0) return emptyList()
        val sorted = sortInMemory()
        val pageWriter = CowPageWriter(requireNotNull(directory), kind, fault)
        repeat(records) { index ->
            val offset = index * recordBytes
            val key = readLong(sorted, offset)
            require(pageWriter.recordEncoded(key, sorted, offset + 8))
        }
        return pageWriter.finish() ?: error("page write failed")
    }

    private fun finishExternal(): List<CowDirectoryEntry> {
        output?.fd?.sync()
        output?.close(); output = null
        BYTE_ORDER.forEachIndexed { pass, byteIndex ->
            val input = if (pass % 2 == 0) requireNotNull(spoolA) else requireNotNull(spoolB)
            val target = if (pass % 2 == 0) requireNotNull(spoolB) else requireNotNull(spoolA)
            radixPass(input, target, byteIndex)
        }
        val pageWriter = CowPageWriter(requireNotNull(directory), kind, fault)
        val payload = externalPayload ?: ByteArray(kind.recordBytes).also { externalPayload = it }
        java.io.RandomAccessFile(requireNotNull(spoolA), "r").use { sorted ->
            repeat(records) {
                val key = sorted.readLong()
                sorted.readFully(payload)
                require(pageWriter.recordEncoded(key, payload, 0))
            }
        }
        val entries = pageWriter.finish() ?: error("page write failed")
        require(requireNotNull(spoolA).delete() && requireNotNull(spoolB).delete())
        return entries
    }

    private fun sortInMemory(): ByteArray {
        var input = requireNotNull(recordBuffer)
        var target = sortBuffer ?: scratch.acquire(input.size).also { sortBuffer = it }
        val counts = IntArray(256)
        val offsets = IntArray(256)
        BYTE_ORDER.forEach { byteIndex ->
            java.util.Arrays.fill(counts, 0)
            repeat(records) { index ->
                val offset = index * recordBytes
                counts[bucket(input, offset, byteIndex)]++
            }
            var next = 0
            counts.indices.forEach { bucket ->
                offsets[bucket] = next
                next += counts[bucket]
            }
            repeat(records) { index ->
                val sourceOffset = index * recordBytes
                val bucket = bucket(input, sourceOffset, byteIndex)
                val targetOffset = offsets[bucket]++ * recordBytes
                input.copyInto(target, targetOffset, sourceOffset, sourceOffset + recordBytes)
            }
            val swap = input
            input = target
            target = swap
        }
        // There are 28 passes, so the sorted result is back in recordBuffer.
        recordBuffer = input
        sortBuffer = target
        return input
    }

    private fun ensureRecordCapacity(requiredRecords: Int) {
        val requiredBytes = Math.multiplyExact(requiredRecords, recordBytes)
        val current = recordBuffer
        if (current != null && current.size >= requiredBytes) return
        val nextBytes = maxOf(requiredBytes, current?.size?.times(2) ?: recordBytes)
        val next = scratch.acquire(nextBytes)
        if (current != null && records > 0) current.copyInto(next, 0, 0, records * recordBytes)
        scratch.release(current)
        recordBuffer = next
    }

    private fun switchToExternal() {
        if (external || directory == null) return
        external = true
        externalPayload = ByteArray(kind.recordBytes)
        val file = requireNotNull(spoolA)
        file.parentFile?.mkdirs()
        output = java.io.RandomAccessFile(file, "rw").also { it.setLength(0) }
        val buffered = recordBuffer
        if (buffered != null && records > 0) requireNotNull(output).write(buffered, 0, records * recordBytes)
        scratch.release(buffered)
        recordBuffer = null
    }

    private fun releaseBuffers() {
        if (released) return
        released = true
        scratch.release(recordBuffer)
        scratch.release(sortBuffer)
        recordBuffer = null
        sortBuffer = null
        externalPayload = null
        radixBytes = null
        radixCounts = null
        radixOffsets = null
    }

    private fun dummyEntries(): List<CowDirectoryEntry> = List(pages) { page ->
        val count = minOf(kind.recordsPerPage, records - page * kind.recordsPerPage)
        CowDirectoryEntry(kind, page, 0, 0, page.toLong() * CanonicalCowGeneration.PAGE_BYTES, CanonicalCowGeneration.PAGE_BYTES, count, ByteArray(32))
    }

    private fun radixPass(inputFile: File, outputFile: File, byteIndex: Int) {
        val bytes = radixBytes ?: ByteArray(recordBytes).also { radixBytes = it }
        val counts = radixCounts ?: LongArray(256).also { radixCounts = it }
        val offsets = radixOffsets ?: LongArray(256).also { radixOffsets = it }
        java.util.Arrays.fill(counts, 0L)
        java.io.RandomAccessFile(inputFile, "r").use { input ->
            repeat(records) { input.readFully(bytes); counts[bucket(bytes, 0, byteIndex)]++ }
        }
        var next = 0L
        counts.indices.forEach { bucket -> offsets[bucket] = next; next += counts[bucket] * recordBytes }
        java.io.RandomAccessFile(inputFile, "r").use { input ->
            java.io.RandomAccessFile(outputFile, "rw").use { out ->
                out.setLength(records.toLong() * recordBytes)
                repeat(records) {
                    input.readFully(bytes)
                    val bucket = bucket(bytes, 0, byteIndex)
                    out.seek(offsets[bucket]); out.write(bytes); offsets[bucket] += recordBytes
                }
            }
        }
    }

    private fun bucket(bytes: ByteArray, offset: Int, byteIndex: Int): Int =
        (bytes[offset + byteIndex].toInt() and 0xff) xor if (byteIndex == 8 || byteIndex == 12 || byteIndex == 16) 0x80 else 0

    companion object {
        /**
         * Total owned array budget stays within the existing 1 MiB canonical
         * phase envelope.  The live bounded
         * case owns at most four sorted record streams of
         * `4,096 * (8 + 20) = 114,688` bytes, one active sort buffer of the
         * same size, twelve 16 KiB page buffers, a 128 KiB reusable pool, and
         * roughly 16 KiB of radix/callback scalars.  That is 917,504 bytes,
         * below `CompactCanonicalStore.JOURNAL_RESERVE_BYTES`.  Writers finish
         * sorted fragments sequentially, so only one sort buffer is live;
         * larger fragments switch to the external path and release their
         * record buffer before sorting.
         */
        const val IN_MEMORY_RECORD_LIMIT = 4_096
        const val MAX_RETAINED_POOL_BYTES = 128 * 1024
        const val MAX_RETAINED_BUFFERS = 2
        private val BYTE_ORDER = intArrayOf(
            27, 26, 25, 24, 23, 22, 21, 20,
            19, 18, 17, 16, 15, 14, 13, 12,
            11, 10, 9, 8, 7, 6, 5, 4, 3, 2, 1, 0,
        )

        private fun writeLong(bytes: ByteArray, offset: Int, value: Long) {
            for (shift in 56 downTo 0 step 8) bytes[offset + (56 - shift) / 8] = (value ushr shift).toByte()
        }

        private fun readLong(bytes: ByteArray, offset: Int): Long {
            var value = 0L
            for (index in 0 until 8) value = (value shl 8) or (bytes[offset + index].toLong() and 0xffL)
            return value
        }
    }
}

private fun MutableSemanticRoot.matches(identity: PreparedIntentIdentity, current: PreparedIntentCurrentReceipt) =
    baseCut == identity.sourceCut && commandId == identity.commandId && commandKind == identity.kind &&
        commandHash == identity.commandHash && commandFingerprint == identity.commandFingerprint &&
        targetHighWater == identity.targetHighWater && targetLive == identity.targetLive && targetSource == identity.targetSource &&
        targetSupport == identity.targetSupport && targetLineage == identity.targetLineage && targetGeometry == identity.targetGeometry &&
        targetLineageRevision == identity.targetLineageRevision && this.current == current
