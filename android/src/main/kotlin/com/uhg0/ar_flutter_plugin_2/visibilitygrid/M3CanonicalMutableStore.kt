package com.uhg0.ar_flutter_plugin_2.visibilitygrid

import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.OutputStream
import java.security.MessageDigest

/** The only production seam from a prepared mutation to current private authority. */
internal interface M3CanonicalCommitStore : AutoCloseable {
    fun commit(
        plan: M3PreparedCanonicalMutation,
        generationZero: M3CanonicalStateView,
        faults: M3CanonicalCommitFaults = M3CanonicalCommitFaults(),
    ): M3CanonicalCommitResult
    fun reopen(generationZero: M3CanonicalStateView): M3CanonicalReopenResult
    fun lookupCommit(query: M3CanonicalCommitQuery, generationZero: M3CanonicalStateView): M3CanonicalCommitLookup

    companion object {
        fun open(parent: File, budget: M3CanonicalStorageBudget): M3CanonicalCommitStore? = try {
            require(parent.exists() || parent.mkdirs()); M3CanonicalMutableStore(parent, budget)
        } catch (_: Exception) { null }
    }
}

private class M3CanonicalMutableStore(
    private val parent: File,
    private val budget: M3CanonicalStorageBudget,
) : M3CanonicalCommitStore {
    private var closed = false

    @Synchronized
    private fun stage(
        intent: M3PreparedIntent,
        baseView: M3CanonicalStateView,
        fault: M3CanonicalCowFault? = null,
    ): M3CanonicalCowStageResult {
        if (closed) return M3CanonicalCowStageResult.Refused(M3CanonicalCowRefusal.CLOSED, M3CowStorageReceipt(0, 0, 0))
        val identity = (intent.identity() as? M3PreparedIntentIdentityResult.Complete)?.identity
            ?: return refused(M3CanonicalCowRefusal.INVALID_INTENT)
        if (identity.sourceCut != baseView.cut) return refused(M3CanonicalCowRefusal.STALE_BASE)
        // Collapse dry-run metadata to scalars in this expression scope. Its
        // manifest graph is unreachable before the real writer constructs one.
        val preflight = M3CowStreamingWriter.dryRun(intent, baseView)?.let { M3CowPreflight.from(identity, it) }
            ?: return refused(M3CanonicalCowRefusal.INVALID_INTENT)
        if (preflight.phasePeakBytes > M3CompactCanonicalStore.JOURNAL_RESERVE_BYTES ||
            preflight.directoryFileBytes > M3CanonicalCowGeneration.DIRECTORY_LIMIT_BYTES ||
            preflight.rootFileBytes > M3CanonicalCowGeneration.DIRECTORY_LIMIT_BYTES)
            return refused(M3CanonicalCowRefusal.PHASE_OR_DIRECTORY_LIMIT)
        val target = commandDirectory(identity)
        val deterministicStaging = File(parent, ".${target.name}.staging")
        try { budget.reconcileCandidate(target) } catch (_: Exception) {
            return refused(M3CanonicalCowRefusal.DURABILITY_FAILURE)
        }
        if (!target.exists() && deterministicStaging.exists() && !deterministicStaging.deleteRecursively())
            return refused(M3CanonicalCowRefusal.DURABILITY_FAILURE)
        if (target.exists()) {
            val existing = M3CanonicalCowGeneration.open(target)
            if (existing != null && existing.root.matches(identity, preflight.current))
                return M3CanonicalCowStageResult.Prepared(existing.withAllocatedStorage(budget.allocatedBytes(target)), reused = true)
            return refused(if (existing != null && existing.root.commandId == identity.commandId && existing.root.commandKind == identity.kind) M3CanonicalCowRefusal.IDENTITY_CONFLICT else M3CanonicalCowRefusal.CORRUPT_GENERATION)
        }

        var token: Any? = null
        var staging: File? = null
        var published = false
        try {
            inject(fault, M3CanonicalCowFault.BEFORE_RESERVATION)
            staging = deterministicStaging
            val files = linkedMapOf<String, Long>()
            preflight.pageCounts.forEach { (kind, pages) -> if (pages > 0) files[kind.file] = pages.toLong() * M3CanonicalCowGeneration.PAGE_BYTES }
            files.putAll(preflight.temporaryFiles)
            files[M3CanonicalCowGeneration.CURRENT_UNACKED_FILE] = preflight.current.length
            files["root.m3cow"] = preflight.rootFileBytes
            files["directory.m3cow"] = preflight.directoryFileBytes
            val unit = budget.allocationUnitBytes(parent)
            // Candidate files plus staging/target directory entries, reservation
            // ledger replacement, and rollback/coexistence metadata.
            val worst = (files.values + listOf(1L, 1L, 1L, 1L)).fold(0L) { total, bytes -> Math.addExact(total, round(bytes, unit)) }
            token = budget.reserveCandidate(staging, target, files, worst) ?: return refused(M3CanonicalCowRefusal.QUOTA_REFUSED)
            inject(fault, M3CanonicalCowFault.AFTER_RESERVATION)
            budget.verifyCandidate(token, staging)
            inject(fault, M3CanonicalCowFault.BEFORE_FRAGMENT_WRITE)
            val currentFile = File(staging, M3CanonicalCowGeneration.CURRENT_UNACKED_FILE)
            val written = M3CowStreamingWriter.write(intent, baseView, staging, currentFile, fault) ?: return refused(M3CanonicalCowRefusal.INVALID_INTENT)
            if (written.pageCounts != preflight.pageCounts || written.current != preflight.current) return refused(M3CanonicalCowRefusal.CORRUPT_GENERATION)
            val root = M3MutableSemanticRoot(
                identity.sourceCut, identity.commandId, identity.kind, identity.commandHash, identity.commandFingerprint,
                identity.targetHighWater, identity.targetLive, identity.targetSource, identity.targetSupport,
                identity.targetLineage, identity.targetGeometry, identity.targetLineageRevision,
                written.current, written.entries,
            )
            val generationIdentity = M3CowGenerationIdentity.from(root)
            inject(fault, M3CanonicalCowFault.AFTER_FRAGMENT_WRITE)
            verifyCurrent(currentFile, preflight.current)
            budget.verifyCandidate(token, staging)
            inject(fault, M3CanonicalCowFault.BEFORE_ROOT_WRITE)
            root.write(M3CanonicalCowGeneration.rootFile(staging), fault == M3CanonicalCowFault.DURING_ROOT_WRITE)
            inject(fault, M3CanonicalCowFault.AFTER_ROOT_WRITE)
            inject(fault, M3CanonicalCowFault.BEFORE_DIRECTORY_WRITE)
            M3CowDirectoryEntry.write(M3CanonicalCowGeneration.directoryFile(staging), written.entries, fault == M3CanonicalCowFault.DURING_DIRECTORY_WRITE)
            inject(fault, M3CanonicalCowFault.AFTER_DIRECTORY_WRITE)
            budget.verifyCandidate(token, staging)
            inject(fault, M3CanonicalCowFault.BEFORE_STAGING_SYNC)
            M3CanonicalCowGeneration.sync(staging)
            inject(fault, M3CanonicalCowFault.AFTER_STAGING_SYNC)
            inject(fault, M3CanonicalCowFault.BEFORE_RENAME)
            budget.publishCandidate(token, staging, target)
            published = true
            inject(fault, M3CanonicalCowFault.AFTER_RENAME)
            budget.verifyCandidate(token, target)
            inject(fault, M3CanonicalCowFault.BEFORE_PARENT_SYNC)
            M3CanonicalCowGeneration.sync(parent)
            inject(fault, M3CanonicalCowFault.AFTER_PARENT_SYNC)
            inject(fault, M3CanonicalCowFault.BEFORE_BUDGET_COMMIT)
            budget.commit(token, budget.allocatedBytes(target)); token = null
            inject(fault, M3CanonicalCowFault.AFTER_BUDGET_COMMIT)
            inject(fault, M3CanonicalCowFault.BEFORE_REOPEN)
            val generation = M3CanonicalCowGeneration.open(target, generationIdentity)?.withAllocatedStorage(budget.allocatedBytes(target))
                ?: return refused(M3CanonicalCowRefusal.CORRUPT_GENERATION)
            inject(fault, M3CanonicalCowFault.AFTER_REOPEN)
            inject(fault, M3CanonicalCowFault.BEFORE_CLEANUP)
            if (staging.exists()) staging.deleteRecursively()
            inject(fault, M3CanonicalCowFault.AFTER_CLEANUP)
            return M3CanonicalCowStageResult.Prepared(generation, reused = false)
        } catch (_: Exception) {
            return refused(M3CanonicalCowRefusal.DURABILITY_FAILURE)
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
        plan: M3PreparedCanonicalMutation,
        generationZero: M3CanonicalStateView,
        faults: M3CanonicalCommitFaults,
    ): M3CanonicalCommitResult {
        if (closed) return M3CanonicalCommitResult.Refused(M3CanonicalCommitRefusal.CLOSED)
        var selected: M3CanonicalPublishedCommit? = null
        var intent: M3PreparedIntent? = null
        var generation: M3CanonicalCowGeneration? = null
        try {
            val current = when (val reopened = M3PrivateRootSelector(parent, budget).reopen(generationZero)) {
                is M3CanonicalReopenResult.GenerationZero -> generationZero
                is M3CanonicalReopenResult.Selected -> {
                    selected = reopened.commit
                    val newest = reopened.commit.roots.lastOrNull()
                        ?: return M3CanonicalCommitResult.Refused(M3CanonicalCommitRefusal.SELECTOR_REFUSED)
                    val current = try { currentReceipt(plan) } catch (_: Exception) {
                        return M3CanonicalCommitResult.Refused(M3CanonicalCommitRefusal.INVALID_INTENT)
                    }
                    if (newest.matches(plan, current)) {
                        selected = null
                        return M3CanonicalCommitResult.Committed(reopened.commit, replayed = true)
                    }
                    return M3CanonicalCommitResult.Refused(M3CanonicalCommitRefusal.CURRENT_PENDING)
                }
                is M3CanonicalReopenResult.Refused -> return M3CanonicalCommitResult.Refused(
                    M3CanonicalCommitRefusal.SELECTOR_REFUSED, selectorReason = reopened.reason,
                )
            }
            if (plan.sourceCut != current.cut)
                return M3CanonicalCommitResult.Refused(M3CanonicalCommitRefusal.STALE_BASE)
            val journal = when (val opened = M3CanonicalDirtyJournal.open(current, parent, budget)) {
                is M3CanonicalDirtyJournalOpenResult.Opened -> opened.journal
                is M3CanonicalDirtyJournalOpenResult.Refused -> return M3CanonicalCommitResult.Refused(
                    M3CanonicalCommitRefusal.JOURNAL_REFUSED, journalReason = opened.reason,
                )
            }
            try {
                intent = when (val durable = journal.reopen()) {
                    is M3CanonicalDirtyJournalReopenResult.Complete -> durable.intent
                    is M3CanonicalDirtyJournalReopenResult.None -> when (val flushed = journal.flush(plan, faults.journal)) {
                        is M3CanonicalDirtyJournalFlushResult.Prepared -> flushed.intent
                        is M3CanonicalDirtyJournalFlushResult.Refused -> return M3CanonicalCommitResult.Refused(
                            M3CanonicalCommitRefusal.JOURNAL_REFUSED, journalReason = flushed.reason,
                        )
                    }
                    is M3CanonicalDirtyJournalReopenResult.Refused -> return M3CanonicalCommitResult.Refused(
                        M3CanonicalCommitRefusal.JOURNAL_REFUSED, journalReason = durable.reason,
                    )
                }
                val identity = (requireNotNull(intent).identity() as? M3PreparedIntentIdentityResult.Complete)?.identity
                    ?: return M3CanonicalCommitResult.Refused(M3CanonicalCommitRefusal.INVALID_INTENT)
                if (!identity.matches(plan)) return M3CanonicalCommitResult.Refused(
                    if (identity.commandId == plan.commandId && identity.kind == plan.kind)
                        M3CanonicalCommitRefusal.IDENTITY_CONFLICT else M3CanonicalCommitRefusal.STALE_BASE,
                )
                when (val staged = stage(requireNotNull(intent), current, faults.cow)) {
                    is M3CanonicalCowStageResult.Prepared -> generation = staged.generation
                    is M3CanonicalCowStageResult.Refused -> return M3CanonicalCommitResult.Refused(
                        if (staged.reason == M3CanonicalCowRefusal.IDENTITY_CONFLICT) M3CanonicalCommitRefusal.IDENTITY_CONFLICT
                        else M3CanonicalCommitRefusal.COW_REFUSED,
                        cowReason = staged.reason,
                    )
                }
                return when (val published = publish(requireNotNull(generation), generationZero, faults.selector)) {
                    is M3CanonicalPublishResult.Committed -> M3CanonicalCommitResult.Committed(published.commit, published.replayed)
                    is M3CanonicalPublishResult.UnknownAfterSwitch -> M3CanonicalCommitResult.UnknownAfterSwitch(published.receipt)
                    is M3CanonicalPublishResult.Refused -> M3CanonicalCommitResult.Refused(
                        if (published.reason == M3CanonicalSelectorRefusal.IDENTITY_CONFLICT) M3CanonicalCommitRefusal.IDENTITY_CONFLICT
                        else M3CanonicalCommitRefusal.SELECTOR_REFUSED,
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
        generation: M3CanonicalCowGeneration,
        generationZero: M3CanonicalStateView,
        fault: M3CanonicalSelectorFault? = null,
    ): M3CanonicalPublishResult {
        if (closed) return M3CanonicalPublishResult.Refused(M3CanonicalSelectorRefusal.CLOSED)
        return M3PrivateRootSelector(parent, budget).publish(generation, generationZero, fault)
    }

    /** Reopens exactly the selected private cut, or generation zero only if no selector exists. */
    @Synchronized
    override fun reopen(generationZero: M3CanonicalStateView): M3CanonicalReopenResult {
        if (closed) return M3CanonicalReopenResult.Refused(M3CanonicalSelectorRefusal.CLOSED)
        return M3PrivateRootSelector(parent, budget).reopen(generationZero)
    }

    /** Process-recovery lookup; changed bytes under one command identity fail closed. */
    @Synchronized
    override fun lookupCommit(query: M3CanonicalCommitQuery, generationZero: M3CanonicalStateView): M3CanonicalCommitLookup {
        if (closed) return M3CanonicalCommitLookup.Refused(M3CanonicalSelectorRefusal.CLOSED)
        return M3PrivateRootSelector(parent, budget).lookup(query, generationZero)
    }

    @Synchronized
    override fun close() { closed = true }
    private fun refused(reason: M3CanonicalCowRefusal) = M3CanonicalCowStageResult.Refused(reason, M3CowStorageReceipt(0, 0, M3CanonicalCowGeneration.FIXED_PHASE_BYTES))
    private fun inject(requested: M3CanonicalCowFault?, point: M3CanonicalCowFault) {
        if (requested == point) error("fault:$point")
    }

    /** Exact base + command identity is a deterministic bounded namespace. */
    private fun commandDirectory(identity: M3PreparedIntentIdentity): File {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(identity.sourceCut.rootHash.toByteArray()); digest.update(identity.sourceCut.sourceHash.toByteArray())
        digest.update(identity.commandId.encodeToByteArray()); digest.update(identity.kind.ordinal.toByte())
        return File(parent, "m3-cow-command-${digest.digest().joinToString("") { "%02x".format(it) }}")
    }

    companion object {
        private fun round(bytes: Long, unit: Long) = if (bytes == 0L) 0L else Math.multiplyExact((bytes - 1L) / unit + 1L, unit)
        private fun verifyCurrent(file: File, expected: M3PreparedIntentCurrentReceipt) {
            require(file.length() == expected.length)
            val digest = MessageDigest.getInstance("SHA-256")
            FileInputStream(file).use { input ->
                val scratch = ByteArray(M3PreparedIntentVisitorResources.STREAMING_SCRATCH_BYTES)
                while (true) { val count = input.read(scratch); if (count < 0) break; digest.update(scratch, 0, count) }
            }
            require(M3CanonicalReceiptBytes(digest.digest()) == expected.hash)
        }

        private fun currentReceipt(plan: M3PreparedCanonicalMutation): M3PreparedIntentCurrentReceipt {
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
            return M3PreparedIntentCurrentReceipt(length, M3CanonicalReceiptBytes(digest.digest()))
        }
    }
}

internal data class M3CanonicalCommitFaults(
    val journal: M3CanonicalDirtyJournalFault? = null,
    val cow: M3CanonicalCowFault? = null,
    val selector: M3CanonicalSelectorFault? = null,
)

internal sealed interface M3CanonicalCommitResult {
    data class Committed(val commit: M3CanonicalPublishedCommit, val replayed: Boolean) : M3CanonicalCommitResult
    data class UnknownAfterSwitch(val receipt: M3CanonicalPublicationReceipt) : M3CanonicalCommitResult
    data class Refused(
        val reason: M3CanonicalCommitRefusal,
        val journalReason: M3CanonicalDirtyJournalRefusal? = null,
        val cowReason: M3CanonicalCowRefusal? = null,
        val selectorReason: M3CanonicalSelectorRefusal? = null,
    ) : M3CanonicalCommitResult
}

internal enum class M3CanonicalCommitRefusal {
    CLOSED, STALE_BASE, CURRENT_PENDING, INVALID_INTENT, IDENTITY_CONFLICT, JOURNAL_REFUSED, COW_REFUSED, SELECTOR_REFUSED,
}

private fun PublishedRoot.matches(plan: M3PreparedCanonicalMutation, current: M3PreparedIntentCurrentReceipt) =
    isCommand(plan.commandId) && commandKind == plan.kind && commandHash == plan.commandHash &&
        commandFingerprint == plan.commandFingerprint && baseRootHash == plan.sourceCut.rootHash && this.current == current

private fun M3PreparedIntentIdentity.matches(plan: M3PreparedCanonicalMutation) =
    sourceCut == plan.sourceCut && commandHash == plan.commandHash && commandFingerprint == plan.commandFingerprint &&
        commandId == plan.commandId && kind == plan.kind && targetHighWater == plan.targetHighWater &&
        targetLive == plan.targetLiveSurfaceCount && targetSource == plan.targetSourceCount &&
        targetSupport == plan.targetSupportCount && targetLineage == plan.targetLineageCount &&
        targetGeometry == plan.targetGeometryRevision && targetLineageRevision == plan.targetLineageRevision

internal sealed interface M3CanonicalCowStageResult {
    data class Prepared(val generation: M3CanonicalCowGeneration, val reused: Boolean) : M3CanonicalCowStageResult
    data class Refused(val reason: M3CanonicalCowRefusal, val storage: M3CowStorageReceipt) : M3CanonicalCowStageResult
}
internal enum class M3CanonicalCowRefusal { CLOSED, INVALID_INTENT, STALE_BASE, QUOTA_REFUSED, IDENTITY_CONFLICT, CORRUPT_GENERATION, PHASE_OR_DIRECTORY_LIMIT, DURABILITY_FAILURE }
internal enum class M3CanonicalCowFault {
    BEFORE_RESERVATION, AFTER_RESERVATION, BEFORE_FRAGMENT_WRITE, DURING_FRAGMENT_WRITE, AFTER_FRAGMENT_WRITE,
    BEFORE_ROOT_WRITE, DURING_ROOT_WRITE, AFTER_ROOT_WRITE, BEFORE_DIRECTORY_WRITE, DURING_DIRECTORY_WRITE, AFTER_DIRECTORY_WRITE,
    BEFORE_STAGING_SYNC, AFTER_STAGING_SYNC, BEFORE_RENAME, AFTER_RENAME, BEFORE_PARENT_SYNC, AFTER_PARENT_SYNC,
    BEFORE_BUDGET_COMMIT, AFTER_BUDGET_COMMIT, BEFORE_REOPEN, AFTER_REOPEN, BEFORE_CLEANUP, AFTER_CLEANUP,
}

private data class M3CowWriteReceipt(
    val entries: List<M3CowDirectoryEntry>, val current: M3PreparedIntentCurrentReceipt,
    val pageCounts: Map<M3CowFragmentKind, Int>, val temporaryFiles: Map<String, Long>, val phasePeakBytes: Long,
)

private data class M3CowPreflight(
    val current: M3PreparedIntentCurrentReceipt,
    val pageCounts: Map<M3CowFragmentKind, Int>,
    val temporaryFiles: Map<String, Long>,
    val phasePeakBytes: Long,
    val directoryFileBytes: Long,
    val rootFileBytes: Long,
) {
    companion object {
        fun from(identity: M3PreparedIntentIdentity, receipt: M3CowWriteReceipt): M3CowPreflight {
            val root = M3MutableSemanticRoot(
                identity.sourceCut, identity.commandId, identity.kind, identity.commandHash, identity.commandFingerprint,
                identity.targetHighWater, identity.targetLive, identity.targetSource, identity.targetSupport,
                identity.targetLineage, identity.targetGeometry, identity.targetLineageRevision,
                receipt.current, receipt.entries,
            )
            return M3CowPreflight(
                receipt.current,
                receipt.pageCounts,
                receipt.temporaryFiles,
                receipt.phasePeakBytes,
                M3CowDirectoryEntry.encodedFileBytes(receipt.entries),
                root.encodedBytes(),
            )
        }
    }
}

/** Uses only #121's scalar callbacks.  A fragment page is the largest retained write state. */
private class M3CowStreamingWriter private constructor(private val directory: File?, private val base: M3CanonicalStateView, private val fault: M3CanonicalCowFault?) : M3PreparedIntentVisitor {
    private val writers: Map<M3CowFragmentKind, M3CowRecordWriter> = M3CowFragmentKind.entries.associateWith { kind ->
        if (kind in SORTED_KINDS) M3CowSortedPageWriter(directory, kind, fault) else M3CowPageWriter(directory, kind, fault)
    }
    private var identity: M3PreparedIntentIdentity? = null
    private var terminal: M3PreparedIntentCurrentReceipt? = null
    private var previousSource: M3CowRow? = null
    override fun onHeader(identity: M3PreparedIntentIdentity): Boolean { this.identity = identity; return true }
    override fun onDirtyRow(id: Long, x: Int, y: Int, z: Int, packedNormal: Int, confidence: Int, fingerprint0: Long, fingerprint1: Long, fingerprint2: Long, fingerprint3: Long): Boolean {
        val row = writers.getValue(M3CowFragmentKind.ROW).record(id) { out -> row(out, id, x, y, z, packedNormal, confidence, fingerprint0, fingerprint1, fingerprint2, fingerprint3) }
        val idIndex = writers.getValue(M3CowFragmentKind.ID_INDEX).record(id) { it.writeLong(id) }
        val voxelKey = voxelKey(x, y, z)
        val voxel = writers.getValue(M3CowFragmentKind.VOXEL_INDEX).record(voxelKey) { out -> out.writeInt(x); out.writeInt(y); out.writeInt(z); out.writeLong(id) }
        val location = m3CompactLocation(M3SurfaceOwnershipConfiguration(), M3Voxel(x, y, z)) ?: return false
        val page = writers.getValue(M3CowFragmentKind.PAGE_INDEX).record(pageKey(location.region, location.page)) { out -> out.writeInt(x); out.writeInt(y); out.writeInt(z); out.writeLong(id) }
        val old = base.findById(M3SurfaceId(id))
        val moved = old != null && old.voxel != M3Voxel(x, y, z)
        val voxelTombstone = !moved || writers.getValue(M3CowFragmentKind.VOXEL_TOMBSTONE).record(voxelKey(old.voxel.x, old.voxel.y, old.voxel.z)) { out -> out.writeInt(old.voxel.x); out.writeInt(old.voxel.y); out.writeInt(old.voxel.z); out.writeLong(id) }
        val oldLocation = old?.let { m3CompactLocation(M3SurfaceOwnershipConfiguration(), it.voxel) }
        val pageTombstone = !moved || oldLocation != null && writers.getValue(M3CowFragmentKind.PAGE_TOMBSTONE).record(pageKey(oldLocation.region, oldLocation.page)) { out -> out.writeInt(old.voxel.x); out.writeInt(old.voxel.y); out.writeInt(old.voxel.z); out.writeLong(id) }
        return row && idIndex && voxel && page && voxelTombstone && pageTombstone
    }
    override fun onRemovedId(id: Long): Boolean {
        val removed = writers.getValue(M3CowFragmentKind.ID_TOMBSTONE).record(id) { it.writeLong(id) }
        val old = base.findById(M3SurfaceId(id)) ?: return false
        val voxelTombstone = writers.getValue(M3CowFragmentKind.VOXEL_TOMBSTONE).record(voxelKey(old.voxel.x, old.voxel.y, old.voxel.z)) { out -> out.writeInt(old.voxel.x); out.writeInt(old.voxel.y); out.writeInt(old.voxel.z); out.writeLong(id) }
        val oldLocation = m3CompactLocation(M3SurfaceOwnershipConfiguration(), old.voxel) ?: return false
        val pageTombstone = writers.getValue(M3CowFragmentKind.PAGE_TOMBSTONE).record(pageKey(oldLocation.region, oldLocation.page)) { out -> out.writeInt(old.voxel.x); out.writeInt(old.voxel.y); out.writeInt(old.voxel.z); out.writeLong(id) }
        val supportTombstone = writers.getValue(M3CowFragmentKind.SUPPORT_TOMBSTONE).record(id) { it.writeLong(id) }
        val lineageTombstone = writers.getValue(M3CowFragmentKind.LINEAGE_TOMBSTONE).record(id) { out -> out.writeLong(id); out.writeLong(0L) }
        return removed && voxelTombstone && pageTombstone && supportTombstone && lineageTombstone
    }
    override fun onDirtySupport(targetId: Long, sourceId: Long, x: Int, y: Int, z: Int, packedNormal: Int, confidence: Int, fingerprint0: Long, fingerprint1: Long, fingerprint2: Long, fingerprint3: Long): Boolean {
        val existing = when (val read = base.readSourceById(M3SurfaceId(sourceId))) { is M3CanonicalPageRead.Complete -> read.value; is M3CanonicalPageRead.Refused -> return false }
        val candidate = M3CowRow(sourceId, x, y, z, packedNormal, confidence, fingerprint0, fingerprint1, fingerprint2, fingerprint3).source()
        if (existing != null && existing != candidate) return false
        return writers.getValue(M3CowFragmentKind.SUPPORT).record(targetId) { out -> out.writeLong(targetId); row(out, sourceId, x, y, z, packedNormal, confidence, fingerprint0, fingerprint1, fingerprint2, fingerprint3) }
    }
    override fun onDirtySource(id: Long, x: Int, y: Int, z: Int, packedNormal: Int, confidence: Int, fingerprint0: Long, fingerprint1: Long, fingerprint2: Long, fingerprint3: Long): Boolean {
        val candidate = M3CowRow(id, x, y, z, packedNormal, confidence, fingerprint0, fingerprint1, fingerprint2, fingerprint3)
        val prior = previousSource
        if (prior != null && prior.id == id && prior != candidate) return false
        val existing = when (val read = base.readSourceById(M3SurfaceId(id))) {
            is M3CanonicalPageRead.Complete -> read.value
            is M3CanonicalPageRead.Refused -> return false
        }
        if (existing != null && existing != candidate.source()) return false
        previousSource = candidate
        return writers.getValue(M3CowFragmentKind.SOURCE).record(id) { out -> row(out, id, x, y, z, packedNormal, confidence, fingerprint0, fingerprint1, fingerprint2, fingerprint3) }
    }
    override fun onDirtyLineage(sourceId: Long, targetId: Long) = writers.getValue(M3CowFragmentKind.LINEAGE).record(sourceId) { out -> out.writeLong(sourceId); out.writeLong(targetId) }
    override fun onTerminal(currentReceipt: M3PreparedIntentCurrentReceipt): Boolean { terminal = currentReceipt; return true }
    fun finish(): M3CowWriteReceipt? {
        val current = terminal ?: run { writers.values.forEach(M3CowRecordWriter::abort); return null }
        val entries = ArrayList<M3CowDirectoryEntry>()
        for (writer in writers.values) {
            val written = writer.finish() ?: run {
                writers.values.forEach(M3CowRecordWriter::abort)
                return null
            }
            entries += written
        }
        return M3CowWriteReceipt(
            entries,
            current,
            writers.mapValues { it.value.pages },
            writers.values.flatMap { it.temporaryFiles().entries }.associate { it.toPair() },
            M3CanonicalCowGeneration.phasePeakBytes(entries.size),
        )
    }
    companion object {
        fun dryRun(intent: M3PreparedIntent, base: M3CanonicalStateView): M3CowWriteReceipt? { val writer = M3CowStreamingWriter(null, base, null); return if (intent.visit(writer) is M3PreparedIntentVisitResult.Complete) writer.finish() else null }
        fun write(intent: M3PreparedIntent, base: M3CanonicalStateView, directory: File, current: File, fault: M3CanonicalCowFault?): M3CowWriteReceipt? {
            val writer = M3CowStreamingWriter(directory, base, fault)
            return FileOutputStream(current, false).use { output ->
                val result = intent.visitCurrent(writer, output)
                output.fd.sync()
                if (result is M3PreparedIntentVisitResult.Complete) writer.finish() else {
                    writer.writers.values.forEach(M3CowRecordWriter::abort)
                    null
                }
            }
        }
        private fun row(out: DataOutputStream, id: Long, x: Int, y: Int, z: Int, normal: Int, confidence: Int, f0: Long, f1: Long, f2: Long, f3: Long) { out.writeLong(id); out.writeInt(x); out.writeInt(y); out.writeInt(z); out.writeInt(normal); out.writeInt(confidence); out.writeLong(f0); out.writeLong(f1); out.writeLong(f2); out.writeLong(f3) }
        private fun voxelKey(x: Int, y: Int, z: Int) = M3CanonicalCowGeneration.voxelKey(x, y, z)
        private fun pageKey(region: M3StorageRegion, page: Int) = M3CanonicalCowGeneration.pageKey(region, page)
        private val SORTED_KINDS = setOf(M3CowFragmentKind.VOXEL_INDEX, M3CowFragmentKind.PAGE_INDEX, M3CowFragmentKind.VOXEL_TOMBSTONE, M3CowFragmentKind.PAGE_TOMBSTONE)
    }
}

private interface M3CowRecordWriter {
    val kind: M3CowFragmentKind
    val pages: Int
    fun record(key: Long, write: (DataOutputStream) -> Unit): Boolean
    fun finish(): List<M3CowDirectoryEntry>?
    fun abort() = Unit
    fun temporaryFiles(): Map<String, Long> = emptyMap()
}

private class M3CowPageWriter(private val directory: File?, override val kind: M3CowFragmentKind, private val fault: M3CanonicalCowFault?) : M3CowRecordWriter {
    private var bytes = ByteArray(M3CanonicalCowGeneration.PAGE_BYTES); private var count = 0; private var position = 12
    private val entries = ArrayList<M3CowDirectoryEntry>(); override var pages = 0; private var failed = false; private var minimum = Long.MAX_VALUE; private var maximum = Long.MIN_VALUE
    override fun record(key: Long, write: (DataOutputStream) -> Unit): Boolean {
        if (failed) return false
        if (count == kind.recordsPerPage) flush()
        return try {
            // Record encoding is fixed-size; encode directly into the owned 16 KiB page.
            val stream = object : java.io.OutputStream() { override fun write(value: Int) { bytes[position++] = value.toByte() }; override fun write(value: ByteArray, offset: Int, length: Int) { value.copyInto(bytes, position, offset, offset + length); position += length } }
            DataOutputStream(stream).use { write(it) }
            require(position == 12 + (count + 1) * kind.recordBytes)
            if (count == 0) { minimum = key; maximum = key }
            else {
                if (java.lang.Long.compareUnsigned(key, minimum) < 0) minimum = key
                if (java.lang.Long.compareUnsigned(key, maximum) > 0) maximum = key
            }
            count++; true
        } catch (_: Exception) { failed = true; false }
    }
    override fun finish(): List<M3CowDirectoryEntry>? { if (failed) return null; if (count > 0) flush(); return if (failed) null else entries }
    private fun flush() {
        if (count == 0 || failed) return
        try {
            java.nio.ByteBuffer.wrap(bytes).putInt(0, M3CanonicalCowGeneration.PAGE_MAGIC).putInt(4, kind.wire).putInt(8, count)
            if (fault == M3CanonicalCowFault.DURING_FRAGMENT_WRITE && pages == 0) { failed = true; return }
            val hash = M3CanonicalCowGeneration.sha(bytes)
            directory?.let { dir ->
                dir.mkdirs()
                java.io.RandomAccessFile(File(dir, kind.file), "rw").use { output ->
                    output.seek(pages.toLong() * M3CanonicalCowGeneration.PAGE_BYTES)
                    output.write(bytes); output.fd.sync()
                }
            }
            entries += M3CowDirectoryEntry(kind, pages, minimum, maximum, pages.toLong() * M3CanonicalCowGeneration.PAGE_BYTES, M3CanonicalCowGeneration.PAGE_BYTES, count, hash); pages++
            bytes = ByteArray(M3CanonicalCowGeneration.PAGE_BYTES); count = 0; position = 12; minimum = Long.MAX_VALUE; maximum = Long.MIN_VALUE
        } catch (_: Exception) { failed = true }
    }
}

/** Stable external radix sort: two pre-reserved spools, fixed counters, and one fragment page. */
private class M3CowSortedPageWriter(
    private val directory: File?, override val kind: M3CowFragmentKind, private val fault: M3CanonicalCowFault?,
) : M3CowRecordWriter {
    private val recordBytes = 8 + kind.recordBytes
    private val spoolAName = "${kind.file}.sort-a"
    private val spoolBName = "${kind.file}.sort-b"
    private val spoolA = directory?.let { File(it, spoolAName) }
    private val spoolB = directory?.let { File(it, spoolBName) }
    private var output = spoolA?.let { java.io.RandomAccessFile(it, "rw").also { file -> file.setLength(0) } }
    private var records = 0
    private var failed = false
    override val pages get() = if (records == 0) 0 else (records - 1) / kind.recordsPerPage + 1
    override fun record(key: Long, write: (DataOutputStream) -> Unit): Boolean = try {
        if (failed) return false
        val payload = ByteArray(kind.recordBytes)
        val sink = object : java.io.OutputStream() {
            var position = 0
            override fun write(value: Int) { payload[position++] = value.toByte() }
            override fun write(value: ByteArray, offset: Int, length: Int) { value.copyInto(payload, position, offset, offset + length); position += length }
        }
        DataOutputStream(sink).use(write)
        require(sink.position == payload.size)
        output?.run { writeLong(key); write(payload) }
        records++
        true
    } catch (_: Exception) { failed = true; false }
    override fun temporaryFiles(): Map<String, Long> {
        if (records == 0) return emptyMap()
        val bytes = records.toLong() * recordBytes
        return mapOf(spoolAName to bytes, spoolBName to bytes)
    }
    override fun abort() {
        try { output?.close() } catch (_: Exception) { }
        output = null
    }
    override fun finish(): List<M3CowDirectoryEntry>? {
        if (failed) return null
        if (directory == null) return List(pages) { page ->
            val count = minOf(kind.recordsPerPage, records - page * kind.recordsPerPage)
            M3CowDirectoryEntry(kind, page, 0, 0, page.toLong() * M3CanonicalCowGeneration.PAGE_BYTES, M3CanonicalCowGeneration.PAGE_BYTES, count, ByteArray(32))
        }
        return try {
            output?.fd?.sync(); output?.close(); output = null
            val byteOrder = (27 downTo 20) + (19 downTo 16) + (15 downTo 12) + (11 downTo 8) + (7 downTo 0)
            byteOrder.forEachIndexed { pass, byteIndex ->
                val input = if (pass % 2 == 0) requireNotNull(spoolA) else requireNotNull(spoolB)
                val target = if (pass % 2 == 0) requireNotNull(spoolB) else requireNotNull(spoolA)
                radixPass(input, target, byteIndex)
            }
            val pageWriter = M3CowPageWriter(directory, kind, fault)
            java.io.RandomAccessFile(requireNotNull(spoolA), "r").use { sorted ->
                repeat(records) {
                    val key = sorted.readLong(); val payload = ByteArray(kind.recordBytes).also(sorted::readFully)
                    require(pageWriter.record(key) { it.write(payload) })
                }
            }
            val entries = pageWriter.finish() ?: return null
            require(spoolA.delete() && requireNotNull(spoolB).delete())
            entries
        } catch (_: Exception) { failed = true; null }
    }
    private fun radixPass(inputFile: File, outputFile: File, byteIndex: Int) {
        val counts = LongArray(256)
        java.io.RandomAccessFile(inputFile, "r").use { input ->
            val bytes = ByteArray(recordBytes)
            repeat(records) { input.readFully(bytes); counts[bucket(bytes, byteIndex)]++ }
        }
        val offsets = LongArray(256); var next = 0L
        counts.indices.forEach { bucket -> offsets[bucket] = next; next += counts[bucket] * recordBytes }
        java.io.RandomAccessFile(inputFile, "r").use { input ->
            java.io.RandomAccessFile(outputFile, "rw").use { out ->
                out.setLength(records.toLong() * recordBytes)
                val bytes = ByteArray(recordBytes)
                repeat(records) {
                    input.readFully(bytes)
                    val bucket = bucket(bytes, byteIndex)
                    out.seek(offsets[bucket]); out.write(bytes); offsets[bucket] += recordBytes
                }
                out.fd.sync()
            }
        }
    }
    private fun bucket(bytes: ByteArray, byteIndex: Int): Int =
        (bytes[byteIndex].toInt() and 0xff) xor if (byteIndex == 8 || byteIndex == 12 || byteIndex == 16) 0x80 else 0
}

private fun M3MutableSemanticRoot.matches(identity: M3PreparedIntentIdentity, current: M3PreparedIntentCurrentReceipt) =
    baseCut == identity.sourceCut && commandId == identity.commandId && commandKind == identity.kind &&
        commandHash == identity.commandHash && commandFingerprint == identity.commandFingerprint &&
        targetHighWater == identity.targetHighWater && targetLive == identity.targetLive && targetSource == identity.targetSource &&
        targetSupport == identity.targetSupport && targetLineage == identity.targetLineage && targetGeometry == identity.targetGeometry &&
        targetLineageRevision == identity.targetLineageRevision && this.current == current
