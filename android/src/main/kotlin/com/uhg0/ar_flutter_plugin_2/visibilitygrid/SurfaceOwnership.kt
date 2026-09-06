package com.uhg0.ar_flutter_plugin_2.visibilitygrid

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest

/**
 * Group-local durable owner for canonical surface surface identities and their storage location.
 *
 * Its interface is intentionally limited to [open], [apply], and [close].  The
 * allocator, durable journal, mutable owner indexes, and persistence adapters
 * are all implementation details.  An accepted result is visible only after a
 * flushed append-only reservation and a durable owner snapshot.
 */
internal class SurfaceOwnership private constructor(
    private val group: SurfaceGroup,
    private val configuration: SurfaceOwnershipConfiguration,
    private val store: SurfaceOwnershipStore?,
    restored: RestoredOwnership,
    private var activation: CanonicalActivationState? = null,
    private val v6Parent: File? = null,
    private val v6Budget: CanonicalStorageBudget? = null,
) {
    private var legacyLeaseRelease: (() -> Unit)? = null
    private val rowsById = restored.rows.associateByTo(linkedMapOf()) { it.id.value }
    private val idByVoxel = restored.rows.associateTo(linkedMapOf()) { it.voxel to it.id.value }
    private val receipts = restored.receipts.associateByTo(linkedMapOf()) { it.commandHash.hex() }
    private val supportById = restored.supports.mapValuesTo(linkedMapOf()) { it.value.copyOf() }
    private val sourceRecords = restored.sourceRecords.associateByTo(linkedMapOf()) { it.id.value }
    private val lineageEdges = restored.lineageEdges.toMutableList()
    private val transactionReceipts = restored.transactionReceipts.associateByTo(linkedMapOf()) { it.commandHash.hex() }
    private var nextHighWater = restored.nextHighWater
    private var geometryRevision = restored.geometryRevision
    private var lineageRevision = restored.lineageRevision
    private var closed = false
    private val adjacentOwnerCapability = Any()
    private val outstandingAdjacentPlans = java.util.Collections.newSetFromMap(
        java.util.IdentityHashMap<PreparedCanonicalMutation, Boolean>(),
    )

    /** Applies one complete candidate set atomically, or returns a typed refusal. */
    @Synchronized
    fun apply(command: SurfaceOwnershipCommand): SurfaceOwnershipResult {
        if (closed) return SurfaceOwnershipResult.Refused(SurfaceOwnershipRefusal.CLOSED, snapshot())
        // #125 makes v6 the sole owner after the switch.  #126 owns the next mutation, so this
        // legacy mutation entry point cannot manufacture a second authority in the interim.
        if (activation != null) return SurfaceOwnershipResult.Refused(SurfaceOwnershipRefusal.DURABILITY_FAILURE, snapshot())
        val store = requireNotNull(store)
        val commandHash = sha256(command.commandId.encodeToByteArray())
        val fingerprint = command.fingerprint()
        receipts[commandHash.hex()]?.let { receipt ->
            return if (receipt.fingerprint.contentEquals(fingerprint)) receipt.result else {
                SurfaceOwnershipResult.Refused(SurfaceOwnershipRefusal.IDENTITY_CONFLICT, snapshot())
            }
        }
        if (commandHash.hex() in transactionReceipts) {
            return SurfaceOwnershipResult.Refused(SurfaceOwnershipRefusal.IDENTITY_CONFLICT, snapshot())
        }
        val preparation = prepare(command, commandHash)
        if (preparation is Preparation.Refused) return SurfaceOwnershipResult.Refused(preparation.reason, snapshot())
        preparation as Preparation.Accepted

        // Reservation is the authority cut. Any error after this point burns its
        // range; no row becomes visible unless the complete owner snapshot lands.
        if (preparation.newCount > 0) {
            val end = checkedEnd(nextHighWater, preparation.newCount)
                ?: return SurfaceOwnershipResult.Refused(SurfaceOwnershipRefusal.EXHAUSTED, snapshot())
            val reservation = Reservation(
                revision = store.lastReservationRevision + 1,
                start = nextHighWater,
                endExclusive = end,
                groupHash = group.hash,
                commandHash = commandHash,
                fingerprint = fingerprint,
                previousHash = store.lastReservationHash,
            )
            try {
                store.appendReservation(reservation)
            } catch (_: OwnershipFault) {
                nextHighWater = end
                return SurfaceOwnershipResult.Refused(SurfaceOwnershipRefusal.DURABILITY_FAILURE, snapshot())
            }
            nextHighWater = end
        }

        val stagedRows = preparation.rows
        val nextRows = rowsById.toMutableMap()
        preparation.changedRows.forEach { nextRows[it.id.value] = it }
        val accepted = SurfaceOwnershipResult.Accepted(
            stagedRows.toList(),
            SurfaceOwnershipReceipt(nextHighWater, nextRows.size, stagedRows.size, preparation.newCount),
        )
        val receipt = StoredReceipt(commandHash, fingerprint, accepted)
        try {
            val nextSupports = supportById.toMutableMap()
            preparation.changedRows.forEach { nextSupports.putIfAbsent(it.id.value, longArrayOf(it.id.value)) }
            val nextSourceRecords = sourceRecords.toMutableMap()
            preparation.changedRows.forEach { nextSourceRecords.putIfAbsent(it.id.value, it.toSourceRecord()) }
            store.writeSnapshot(currentSnapshot(nextRows.values.toList(), receipts.values.toList() + receipt, supports = nextSupports, sources = nextSourceRecords.values.toList()))
        } catch (_: OwnershipFault) {
            return SurfaceOwnershipResult.Refused(SurfaceOwnershipRefusal.DURABILITY_FAILURE, snapshot())
        } catch (_: RootPublishedFault) {
            adopt(store.restore(configuration))
            return receipts[commandHash.hex()]?.result
                ?: SurfaceOwnershipResult.Refused(SurfaceOwnershipRefusal.DURABILITY_FAILURE, snapshot())
        }
        preparation.changedRows.forEach { row ->
            rowsById[row.id.value] = row
            idByVoxel.entries.removeIf { it.value == row.id.value && it.key != row.voxel }
            idByVoxel[row.voxel] = row.id.value
            supportById.putIfAbsent(row.id.value, longArrayOf(row.id.value))
            sourceRecords.putIfAbsent(row.id.value, row.toSourceRecord())
        }
        receipts[commandHash.hex()] = receipt
        return accepted
    }

    /** Commits one complete canonical refinement behind the same group lock and durable root. */
    @Synchronized
    fun transact(command: CanonicalTransactionCommand): CanonicalTransactionResult {
        if (closed) return canonicalRefusal(CanonicalTransactionRefusal.CLOSED)
        if (activation != null) return canonicalRefusal(CanonicalTransactionRefusal.DURABILITY_FAILURE)
        val store = requireNotNull(store)
        val commandHash = sha256(command.commandId.encodeToByteArray())
        val fingerprint = command.fingerprint()
        transactionReceipts[commandHash.hex()]?.let { stored ->
            return if (stored.fingerprint.contentEquals(fingerprint)) stored.result
            else canonicalRefusal(CanonicalTransactionRefusal.IDENTITY_CONFLICT)
        }
        if (commandHash.hex() in receipts) return canonicalRefusal(CanonicalTransactionRefusal.IDENTITY_CONFLICT)
        val prepared = prepareCanonical(command, commandHash)
        if (prepared is CanonicalPreparation.Refused) return canonicalRefusal(prepared.reason)
        prepared as CanonicalPreparation.Accepted

        if (prepared.allocatedCount > 0) {
            val end = checkedEnd(nextHighWater, prepared.allocatedCount)
                ?: return canonicalRefusal(CanonicalTransactionRefusal.EXHAUSTED)
            val reservation = Reservation(
                store.lastReservationRevision + 1, nextHighWater, end, group.hash,
                commandHash, fingerprint, store.lastReservationHash,
            )
            try {
                store.appendReservation(reservation)
            } catch (_: OwnershipFault) {
                nextHighWater = end
                return canonicalRefusal(CanonicalTransactionRefusal.DURABILITY_FAILURE)
            }
            nextHighWater = end
        }

        val nextRows = rowsById.toMutableMap()
        val nextVoxels = idByVoxel.toMutableMap()
        prepared.removedIds.forEach { id ->
            nextRows.remove(id)?.let { nextVoxels.remove(it.voxel) }
        }
        prepared.targets.forEach { row -> nextRows[row.id.value] = row; nextVoxels[row.voxel] = row.id.value }
        val nextSupports = supportById.toMutableMap()
        prepared.removedIds.forEach(nextSupports::remove)
        prepared.targets.forEach { target ->
            nextSupports[target.id.value] = if (command.kind == CanonicalOperation.CREATE) {
                longArrayOf(target.id.value)
            } else {
                prepared.sourceSupport.copyOf()
            }
        }
        val nextSourceRecords = sourceRecords.toMutableMap()
        prepared.targets.forEach { nextSourceRecords.putIfAbsent(it.id.value, it.toSourceRecord()) }
        val nextEdges = (lineageEdges + prepared.edges)
            .sortedWith(compareBy({ it.source.value }, { it.target.value }))
        val nextGeometryRevision = geometryRevision + 1
        // CREATE establishes a root but has no predecessor lineage.  Keep the
        // revision vector truthful: only the geometry authority advanced.
        val nextLineageRevision = if (command.kind == CanonicalOperation.CREATE) lineageRevision else lineageRevision + 1
        val provisional = CanonicalTransactionResult.Accepted(
            targets = prepared.targets,
            receipt = CanonicalTransactionReceipt(
                commandId = command.commandId,
                kind = command.kind,
                removedSurfaceIds = prepared.removedIds.map(::SurfaceId),
                lineageEdges = prepared.edges,
                geometryRevision = nextGeometryRevision,
                lineageRevision = nextLineageRevision,
                nextSurfaceIdHighWater = nextHighWater,
                liveSurfaceCount = nextRows.size,
                sourceSupport = prepared.sourceSupport.map { sourceRecords.getValue(it).toReceiptSupport() },
                canonicalBytes = CanonicalReceiptBytes.EMPTY,
            ),
        )
        val result = provisional.copy(receipt = provisional.receipt.copy(
            canonicalBytes = CanonicalReceiptBytes(encodeCanonicalReceipt(group, provisional)),
        ))
        val stored = StoredCanonicalReceipt(commandHash, fingerprint, result)
        if (store.fault == SurfaceOwnershipFault.AFTER_PRIVATE_CANDIDATE) {
            return canonicalRefusal(CanonicalTransactionRefusal.DURABILITY_FAILURE)
        }
        try {
            store.writeSnapshot(currentSnapshot(
                rows = nextRows.values.sortedBy { it.id.value },
                supports = nextSupports,
                sources = nextSourceRecords.values.sortedBy { it.id.value },
                edges = nextEdges,
                canonicalReceipts = transactionReceipts.values.toList() + stored,
                geometry = nextGeometryRevision,
                lineage = nextLineageRevision,
            ))
        } catch (_: OwnershipFault) {
            return canonicalRefusal(CanonicalTransactionRefusal.DURABILITY_FAILURE)
        } catch (_: RootPublishedFault) {
            // The root names the complete new snapshot. Adopt exactly what a
            // reopening reader observes instead of exposing the old process cut.
            adopt(store.restore(configuration))
            return transactionReceipts[commandHash.hex()]?.result
                ?: canonicalRefusal(CanonicalTransactionRefusal.DURABILITY_FAILURE)
        }
        rowsById.clear(); rowsById.putAll(nextRows)
        idByVoxel.clear(); idByVoxel.putAll(nextVoxels)
        supportById.clear(); supportById.putAll(nextSupports)
        sourceRecords.clear(); sourceRecords.putAll(nextSourceRecords)
        lineageEdges.clear(); lineageEdges.addAll(nextEdges)
        geometryRevision = nextGeometryRevision
        lineageRevision = nextLineageRevision
        transactionReceipts[commandHash.hex()] = stored
        return result
    }

    /** The sole retained feature-only CREATE, used for exact post-crash replay. */
    @Synchronized
    internal fun currentCanonicalTransaction(): CanonicalTransactionResult.Accepted? {
        if (closed) return null
        if (activation != null) return null
        return transactionReceipts.values.lastOrNull()?.result
    }

    /** Bounded activation state; all activation/closed access linearizes on this owner monitor. */
    @Synchronized
    internal fun activationState(): CanonicalActivationState? = if (closed) null else activation

    /**
     * The only canonical-owner ACK seam.  It is deliberately not connected to a
     * runtime callback: callers name the immutable command hash and the exact
     * geometry/lineage cut they consumed.
     */
    @Synchronized
    internal fun acknowledgeCanonicalCurrent(
        acknowledgement: CanonicalAcknowledgement,
        fault: CanonicalAcknowledgementFault? = null,
    ): CanonicalAcknowledgementResult {
        if (closed) return CanonicalAcknowledgementResult.NoOp(CanonicalAcknowledgementNoOp.CLOSED)
        val parent = v6Parent ?: return CanonicalAcknowledgementResult.NoOp(CanonicalAcknowledgementNoOp.NO_CURRENT)
        val budget = v6Budget ?: return CanonicalAcknowledgementResult.NoOp(CanonicalAcknowledgementNoOp.NO_CURRENT)
        val authenticatedCut = activation?.cut
        return CanonicalActivationSelector.acknowledge(
            group, parent, budget, acknowledgement, fault, authenticatedCut,
        ).also { result ->
            when (result) {
                is CanonicalAcknowledgementResult.Acknowledged -> activation = result.state
                is CanonicalAcknowledgementResult.Idempotent -> activation = result.state
                is CanonicalAcknowledgementResult.NoOp -> Unit
            }
        }
    }

    /** Admits exactly one adjacent #117 plan only from this owner's durable ACK state. */
    internal fun commitAdjacentCanonicalMutation(
        plan: PreparedCanonicalMutation,
        faults: CanonicalCommitFaults = CanonicalCommitFaults(),
    ): CanonicalAdjacentCommitResult {
        val parent: File
        val budget: CanonicalStorageBudget
        synchronized(this) {
            if (closed) {
                plan.discard()
                return CanonicalAdjacentCommitResult.Refused(CanonicalAdjacentCommitRefusal.NO_ACTIVE_AUTHORITY)
            }
            parent = v6Parent ?: run {
                plan.discard()
                return CanonicalAdjacentCommitResult.Refused(CanonicalAdjacentCommitRefusal.NO_ACTIVE_AUTHORITY)
            }
            budget = v6Budget ?: run {
                plan.discard()
                return CanonicalAdjacentCommitResult.Refused(CanonicalAdjacentCommitRefusal.NO_ACTIVE_AUTHORITY)
            }
            when (plan.claim()) {
                PreparedMutationClaimResult.Claimed -> Unit
                PreparedMutationClaimResult.AlreadyInFlight -> return CanonicalAdjacentCommitResult.Refused(
                    CanonicalAdjacentCommitRefusal.PLAN_IN_FLIGHT,
                    disposition = PreparedMutationDisposition.RETRYABLE,
                )
                PreparedMutationClaimResult.Terminal -> return CanonicalAdjacentCommitResult.Refused(
                    CanonicalAdjacentCommitRefusal.PLAN_DISCARDED,
                )
            }
        }
        val authority = when (val resolution = CanonicalAuthorityLeaseRegistry.resolve(
            plan.authorityLease, group, parent, adjacentOwnerCapability,
        )) {
            is CanonicalAuthorityLeaseResolution.Resolved -> resolution
            is CanonicalAuthorityLeaseResolution.Refused -> {
                plan.finish(PreparedMutationFinish.TERMINAL)
                return CanonicalAdjacentCommitResult.Refused(
                    CanonicalAdjacentCommitRefusal.INVALID_AUTHORITY_LEASE,
                )
            }
        }
        val boundBase = authority.authority
        return try {
            when (val result = CanonicalActivationSelector.commitAdjacent(
                group, parent, budget, plan, boundBase, faults, authority.published,
            )) {
                is CanonicalAdjacentCommitResult.Committed -> {
                    check(plan.finish(PreparedMutationFinish.SUCCESS) == PreparedMutationLifecycle.CONSUMED)
                    synchronized(this) { activation = result.state }
                    result
                }
                is CanonicalAdjacentCommitResult.Refused -> {
                    val finished = plan.finish(when (result.disposition) {
                        PreparedMutationDisposition.RETRYABLE -> PreparedMutationFinish.RETRYABLE
                        PreparedMutationDisposition.TERMINAL -> PreparedMutationFinish.TERMINAL
                    })
                    if (result.disposition == PreparedMutationDisposition.RETRYABLE &&
                        finished == PreparedMutationLifecycle.DISCARDED
                    ) result.copy(disposition = PreparedMutationDisposition.TERMINAL) else result
                }
            }
        } catch (failure: Throwable) {
            plan.finish(PreparedMutationFinish.TERMINAL)
            throw failure
        }
    }

    /** Owner-issued preparation binds one small exact authority lease for adjacent admission. */
    @Synchronized
    internal fun prepareAdjacentMutation(
        view: CanonicalStateView,
        command: FeatureMutationCommand,
    ): CanonicalMutationPreparation = bindAdjacentPreparation(
        MutableCanonicalOverlay.prepare(view, configuration, command), view,
    )

    @Synchronized
    internal fun prepareAdjacentMutation(
        view: CanonicalStateView,
        command: CanonicalTransactionCommand,
    ): CanonicalMutationPreparation = bindAdjacentPreparation(
        MutableCanonicalOverlay.prepare(view, configuration, command), view,
    )

    /** Private #128 seam: one kernel delta batch plans one adjacent v6 commit. */
    @Synchronized
    internal fun prepareAdjacentMutation(
        view: CanonicalFeaturePlanningView,
        command: CanonicalFeatureBatchCommand,
    ): CanonicalMutationPreparation = bindAdjacentPreparation(
        MutableCanonicalOverlay.prepare(view, configuration, command), view.generationZeroAuthority,
    )

    /** Public adjacent-authority seam for one immutable depth evidence batch. */
    @Synchronized
    internal fun prepareAdjacentMutation(
        view: CanonicalStateView,
        command: CanonicalEvidenceBatchCommand,
    ): CanonicalMutationPreparation = bindAdjacentPreparation(
        MutableCanonicalOverlay.prepare(view, configuration, command), view,
    )

    private fun bindAdjacentPreparation(
        preparation: CanonicalMutationPreparation,
        view: CanonicalStateView,
    ): CanonicalMutationPreparation {
        if (closed || activation == null) return CanonicalMutationPreparation.Refused(
            CanonicalMutationRefusal.INVALID_OWNERSHIP,
            CanonicalStateReceipt(geometryRevision, lineageRevision, nextHighWater, rowsById.size),
        )
        val prepared = preparation as? CanonicalMutationPreparation.Prepared ?: return preparation
        if (synchronized(outstandingAdjacentPlans) { outstandingAdjacentPlans.isNotEmpty() }) {
            prepared.mutation.discard()
            return CanonicalMutationPreparation.Refused(
                CanonicalMutationRefusal.ADJACENT_BUSY,
                CanonicalStateReceipt(geometryRevision, lineageRevision, nextHighWater, rowsById.size),
            )
        }
        val mutation = prepared.mutation
        if (!mutation.bindAuthority(view, adjacentOwnerCapability) { releaseAdjacentPlan(mutation) }) {
            prepared.mutation.discard()
            return CanonicalMutationPreparation.Refused(
                CanonicalMutationRefusal.INVALID_OWNERSHIP,
                CanonicalStateReceipt(geometryRevision, lineageRevision, nextHighWater, rowsById.size),
            )
        }
        synchronized(outstandingAdjacentPlans) {
            check(outstandingAdjacentPlans.add(mutation))
        }
        return prepared
    }

    private fun releaseAdjacentPlan(plan: PreparedCanonicalMutation) {
        synchronized(outstandingAdjacentPlans) {
            outstandingAdjacentPlans.remove(plan)
        }
    }

    /** Idempotently closes the owner; later calls are deterministic refusals. */
    @Synchronized
    fun close(): SurfaceOwnershipCloseResult {
        if (closed) return SurfaceOwnershipCloseResult.AlreadyClosed
        closed = true
        try {
            val abandoned = synchronized(outstandingAdjacentPlans) {
                outstandingAdjacentPlans.toList().also { outstandingAdjacentPlans.clear() }
            }
            abandoned.forEach(PreparedCanonicalMutation::discard)
            store?.close()
        } finally {
            legacyLeaseRelease?.invoke()
            legacyLeaseRelease = null
        }
        return SurfaceOwnershipCloseResult.Closed
    }

    /** Attached atomically by the directory opener before this legacy owner is returned. */
    @Synchronized
    internal fun attachLegacyLease(release: () -> Unit) {
        check(!closed && activation == null && legacyLeaseRelease == null)
        legacyLeaseRelease = release
    }

    private fun prepare(command: SurfaceOwnershipCommand, commandHash: ByteArray): Preparation {
        if (command.commandId.isBlank() || command.commandId.encodeToByteArray().size > MAX_COMMAND_BYTES || command.candidates.isEmpty()) {
            return Preparation.Refused(SurfaceOwnershipRefusal.INVALID_COMMAND)
        }
        val requestedAllocations = command.candidates.count { it.id == null }
        if (sourceRecords.size.toLong() + requestedAllocations > configuration.surfaceCapacity.toLong() + configuration.lineageCapacity) {
            return Preparation.Refused(SurfaceOwnershipRefusal.CAPACITY)
        }
        if (checkedEnd(nextHighWater, requestedAllocations) == null) {
            return Preparation.Refused(SurfaceOwnershipRefusal.EXHAUSTED)
        }
        val seenIds = hashSetOf<Long>()
        val seenVoxels = hashSetOf<Voxel>()
        val changed = ArrayList<SurfaceOwner>(command.candidates.size)
        var allocated = 0
        command.candidates.forEach { candidate ->
            val ownership = ownershipFor(candidate.voxel) ?: return Preparation.Refused(SurfaceOwnershipRefusal.INVALID_OWNERSHIP)
            val packed = packNormal(candidate.normalOctX, candidate.normalOctY, candidate.normalConfidence)
                ?: return Preparation.Refused(SurfaceOwnershipRefusal.INVALID_NORMAL)
            val existing = candidate.id?.let { rowsById[it] }
            if (candidate.id != null && (candidate.id == 0L || existing == null || !seenIds.add(candidate.id))) {
                return Preparation.Refused(SurfaceOwnershipRefusal.UNKNOWN_IDENTITY)
            }
            if (!seenVoxels.add(candidate.voxel)) return Preparation.Refused(SurfaceOwnershipRefusal.OWNERSHIP_CONFLICT)
            val occupied = idByVoxel[candidate.voxel]
            if (occupied != null && occupied != candidate.id) return Preparation.Refused(SurfaceOwnershipRefusal.OWNERSHIP_CONFLICT)
            val id = candidate.id ?: run {
                allocated++
                nextHighWater + allocated - 1
            }
            changed += SurfaceOwner(
                id = SurfaceId(id), group = group, voxel = candidate.voxel,
                region = ownership.region, page = ownership.page,
                packedNormal = packed.first, normalConfidence = packed.second,
                allocatedBy = commandHash,
            )
        }
        if (rowsById.size - command.candidates.count { it.id != null } + changed.size > configuration.surfaceCapacity) {
            return Preparation.Refused(SurfaceOwnershipRefusal.CAPACITY)
        }
        return Preparation.Accepted(changed, allocated)
    }

    private fun prepareCanonical(command: CanonicalTransactionCommand, commandHash: ByteArray): CanonicalPreparation {
        if (command.commandId.isBlank() || command.commandId.encodeToByteArray().size > MAX_COMMAND_BYTES || command.targets.isEmpty()) {
            return CanonicalPreparation.Refused(CanonicalTransactionRefusal.INVALID_COMMAND)
        }
        if (command.expectedGeometryRevision != geometryRevision || command.expectedLineageRevision != lineageRevision) {
            return CanonicalPreparation.Refused(CanonicalTransactionRefusal.REVISION_CONFLICT)
        }
        val sourceIds = command.sourceIds.map { it.value }
        val shapeValid = when (command.kind) {
            CanonicalOperation.RELOCATION -> sourceIds.size == 1 && command.targets.size == 1 && command.targets.single().id?.value == sourceIds.single()
            CanonicalOperation.MERGE -> sourceIds.size >= 2 && command.targets.size == 1 && command.targets.single().id == null
            CanonicalOperation.SPLIT -> sourceIds.size == 1 && command.targets.size >= 2 && command.targets.all { it.id == null }
            CanonicalOperation.REPLACEMENT -> command.targets.all { it.id == null }
            // CREATE is deliberately initial-only.  It is the bootstrap path,
            // not an alternate mutation route around canonical lineage.
            CanonicalOperation.CREATE -> rowsById.isEmpty() && supportById.isEmpty() && sourceRecords.isEmpty() &&
                lineageEdges.isEmpty() && transactionReceipts.isEmpty() &&
                geometryRevision == (configuration.seededEmptyBaseline?.geometryRevision ?: 0L) &&
                lineageRevision == (configuration.seededEmptyBaseline?.lineageRevision ?: 0L) &&
                sourceIds.isEmpty() && command.targets.all { it.id == null }
        }
        if (!shapeValid) return CanonicalPreparation.Refused(CanonicalTransactionRefusal.INVALID_COMMAND)
        if (sourceIds.toSet().size != sourceIds.size || sourceIds.any { it !in rowsById }) {
            return CanonicalPreparation.Refused(CanonicalTransactionRefusal.UNKNOWN_IDENTITY)
        }
        if (geometryRevision >= configuration.revisionLimit ||
            (command.kind != CanonicalOperation.CREATE && lineageRevision >= configuration.revisionLimit)) {
            return CanonicalPreparation.Refused(CanonicalTransactionRefusal.REVISION_EXHAUSTED)
        }
        val allocations = command.targets.count { it.id == null }
        if (checkedEnd(nextHighWater, allocations) == null) return CanonicalPreparation.Refused(CanonicalTransactionRefusal.EXHAUSTED)
        val finalCount = rowsById.size - sourceIds.size + command.targets.size
        if (finalCount > configuration.surfaceCapacity) return CanonicalPreparation.Refused(CanonicalTransactionRefusal.CAPACITY)
        val newSourceRecords = command.targets.count { it.id == null }
        if (sourceRecords.size.toLong() + newSourceRecords > configuration.surfaceCapacity.toLong() + configuration.lineageCapacity) {
            return CanonicalPreparation.Refused(CanonicalTransactionRefusal.LINEAGE_EXHAUSTED)
        }
        if (transactionReceipts.size >= configuration.transactionCapacity) return CanonicalPreparation.Refused(CanonicalTransactionRefusal.JOURNAL_EXHAUSTED)

        val sourceSupport = sourceIds.flatMap { (supportById[it] ?: longArrayOf(it)).asIterable() }.distinct().sorted().toLongArray()
        val edgeCount = sourceSupport.size.toLong() * command.targets.size
        if (edgeCount > Int.MAX_VALUE || lineageEdges.size + edgeCount > configuration.lineageCapacity) {
            return CanonicalPreparation.Refused(CanonicalTransactionRefusal.LINEAGE_EXHAUSTED)
        }
        val vacated = sourceIds.toSet()
        val seenVoxels = hashSetOf<Voxel>()
        var nextAllocation = nextHighWater
        val targets = ArrayList<SurfaceOwner>(command.targets.size)
        command.targets.forEach { target ->
            val location = locationFor(configuration, target.voxel)
                ?: return CanonicalPreparation.Refused(CanonicalTransactionRefusal.INVALID_OWNERSHIP)
            val packed = packNormal(target.normalOctX, target.normalOctY, target.normalConfidence)
                ?: return CanonicalPreparation.Refused(CanonicalTransactionRefusal.INVALID_NORMAL)
            if (!seenVoxels.add(target.voxel)) return CanonicalPreparation.Refused(CanonicalTransactionRefusal.OWNERSHIP_CONFLICT)
            val occupied = idByVoxel[target.voxel]
            if (occupied != null && occupied !in vacated) return CanonicalPreparation.Refused(CanonicalTransactionRefusal.OWNERSHIP_CONFLICT)
            val id = target.id?.value ?: nextAllocation++
            if (target.id != null && id !in vacated) return CanonicalPreparation.Refused(CanonicalTransactionRefusal.UNKNOWN_IDENTITY)
            targets += SurfaceOwner(SurfaceId(id), group, target.voxel, location.region, location.page,
                packed.first, packed.second, commandHash)
        }
        val edges = targets.flatMap { target -> sourceIds.map { source -> LineageEdge(SurfaceId(source), target.id) } }
            .sortedWith(compareBy({ it.source.value }, { it.target.value }))
        val preview = CanonicalTransactionResult.Accepted(targets, CanonicalTransactionReceipt(
            command.commandId, command.kind, sourceIds.sorted().map(::SurfaceId), edges,
            geometryRevision + 1,
            if (command.kind == CanonicalOperation.CREATE) lineageRevision else lineageRevision + 1,
            nextHighWater + allocations, finalCount,
            sourceSupport.map { sourceRecords.getValue(it).toReceiptSupport() },
            CanonicalReceiptBytes.EMPTY,
        ))
        val exactBytes = encodeCanonicalReceipt(group, preview).size.toLong()
        val retainedBytes = transactionReceipts.values.fold(0L) { total, receipt ->
            checkedAdd(total, canonicalJournalEntryBytes(receipt.result)) ?: Long.MAX_VALUE
        }
        val entryBytes = checkedAdd(exactBytes, CANONICAL_JOURNAL_ENVELOPE_BYTES) ?: Long.MAX_VALUE
        if (checkedAdd(retainedBytes, entryBytes) == null || retainedBytes + entryBytes > configuration.changeJournalByteCapacity) {
            return CanonicalPreparation.Refused(CanonicalTransactionRefusal.JOURNAL_EXHAUSTED)
        }
        return CanonicalPreparation.Accepted(sourceIds.sorted(), targets.sortedBy { it.id.value }, sourceSupport, edges, allocations)
    }

    private fun canonicalRefusal(reason: CanonicalTransactionRefusal) = CanonicalTransactionResult.Refused(
        reason, CanonicalStateReceipt(geometryRevision, lineageRevision, nextHighWater, rowsById.size),
    )

    private fun currentSnapshot(
        rows: List<SurfaceOwner> = rowsById.values.toList(),
        ownershipReceipts: List<StoredReceipt> = receipts.values.toList(),
        supports: Map<Long, LongArray> = supportById,
        sources: List<SourceSupportRecord> = sourceRecords.values.toList(),
        edges: List<LineageEdge> = lineageEdges,
        canonicalReceipts: List<StoredCanonicalReceipt> = transactionReceipts.values.toList(),
        geometry: Long = geometryRevision,
        lineage: Long = lineageRevision,
    ) = OwnershipSnapshot(nextHighWater, rows, ownershipReceipts, supports, sources, edges, canonicalReceipts, geometry, lineage, configuration.seededEmptyBaseline)

    private fun adopt(restored: RestoredOwnership) {
        rowsById.clear(); rowsById.putAll(restored.rows.associateBy { it.id.value })
        idByVoxel.clear(); idByVoxel.putAll(restored.rows.associate { it.voxel to it.id.value })
        supportById.clear(); supportById.putAll(restored.supports.mapValues { it.value.copyOf() })
        sourceRecords.clear(); sourceRecords.putAll(restored.sourceRecords.associateBy { it.id.value })
        lineageEdges.clear(); lineageEdges.addAll(restored.lineageEdges)
        receipts.clear(); receipts.putAll(restored.receipts.associateBy { it.commandHash.hex() })
        transactionReceipts.clear(); transactionReceipts.putAll(restored.transactionReceipts.associateBy { it.commandHash.hex() })
        nextHighWater = restored.nextHighWater; geometryRevision = restored.geometryRevision; lineageRevision = restored.lineageRevision
    }

    private fun snapshot(resultRows: Int = 0, allocated: Int = 0) = SurfaceOwnershipReceipt(
        nextSurfaceIdHighWater = nextHighWater,
        liveSurfaceCount = rowsById.size,
        resultRowCount = resultRows,
        allocatedCount = allocated,
    )

    private fun ownershipFor(voxel: Voxel): Location? = locationFor(configuration, voxel)

    private fun checkedEnd(start: Long, count: Int): Long? {
        if (count == 0) return start
        if (start !in 1..MAX_HIGH_WATER || count < 0) return null
        val end = start + count.toLong()
        return end.takeIf { it <= MAX_HIGH_WATER }
    }

    private sealed interface Preparation {
        data class Accepted(val changedRows: List<SurfaceOwner>, val newCount: Int) : Preparation { val rows get() = changedRows }
        data class Refused(val reason: SurfaceOwnershipRefusal) : Preparation
    }

    private sealed interface CanonicalPreparation {
        data class Accepted(
            val removedIds: List<Long>, val targets: List<SurfaceOwner>, val sourceSupport: LongArray,
            val edges: List<LineageEdge>, val allocatedCount: Int,
        ) : CanonicalPreparation
        data class Refused(val reason: CanonicalTransactionRefusal) : CanonicalPreparation
    }

    companion object {
        private const val MAX_COMMAND_BYTES = 256
        private const val MAX_HIGH_WATER = 0x1_0000_0000L

        /**
         * Builds, but deliberately does not publish, one dirty canonical plan
         * over the immutable v6 authority.  #115b owns reservation, WAL/root
         * writes, current-receipt retention, and activation.
         */
        internal fun prepareMutation(
            view: CanonicalStateView,
            configuration: SurfaceOwnershipConfiguration,
            command: FeatureMutationCommand,
        ): CanonicalMutationPreparation =
            MutableCanonicalOverlay.prepare(view, configuration, command)

        /** Structural parity planner for the pre-existing canonical commands. */
        internal fun prepareMutation(
            view: CanonicalStateView,
            configuration: SurfaceOwnershipConfiguration,
            command: CanonicalTransactionCommand,
        ): CanonicalMutationPreparation =
            MutableCanonicalOverlay.prepare(view, configuration, command)

        fun open(
            group: SurfaceGroup,
            directory: File,
            configuration: SurfaceOwnershipConfiguration = SurfaceOwnershipConfiguration(),
            fault: SurfaceOwnershipFault? = null,
        ): SurfaceOwnershipOpenResult = CanonicalActivationSelector.withGroupLock(directory, group) { try {
            // This overload has no v6 storage budget.  Once the durable selector exists it may
            // not reinterpret legacy history; the budget-aware overload below is the only route.
            if (CanonicalActivationSelector.hasDurableSelector(group, directory)) {
                return@withGroupLock SurfaceOwnershipOpenResult.Refused(SurfaceOwnershipRestoreRefusal.CORRUPT)
            }
            CanonicalActivationTestHooks.afterLegacySelection?.invoke()
            open(group, configuration, FileSurfaceOwnershipStore(directory, group, fault)).also { result ->
                if (result is SurfaceOwnershipOpenResult.Opened) {
                    result.ownership.attachLegacyLease(
                        CanonicalActivationSelector.acquireLegacyLease(directory, group),
                    )
                }
            }
        } catch (failure: RestoreFailure) {
            SurfaceOwnershipOpenResult.Refused(failure.reason)
        } }

        /**
         * Opens group-local v6 authority when the durable activation selector exists.  Its active
         * branch reads no legacy receipts and never reruns sibling migration; absence leaves the
         * established legacy opening path unchanged.
         */
        fun open(
            group: SurfaceGroup,
            directory: File,
            budget: CanonicalStorageBudget,
            configuration: SurfaceOwnershipConfiguration = SurfaceOwnershipConfiguration(),
        ): SurfaceOwnershipOpenResult {
            if (!configuration.isValid) return SurfaceOwnershipOpenResult.Refused(SurfaceOwnershipRestoreRefusal.INVALID_CONFIGURATION)
            return CanonicalActivationSelector.withGroupLock(directory, group) {
                when (val activation = CanonicalActivationSelector.reopen(group, directory, budget)) {
                    CanonicalActivationResult.Legacy -> open(group, directory, configuration)
                    is CanonicalActivationResult.Active -> openedV6(group, directory, budget, configuration, activation.state)
                    CanonicalActivationResult.UnknownAfterSwitch -> SurfaceOwnershipOpenResult.Refused(SurfaceOwnershipRestoreRefusal.DURABILITY_FAILURE)
                    is CanonicalActivationResult.Refused -> SurfaceOwnershipOpenResult.Refused(
                        if (activation.reason == CanonicalActivationSelectorRefusal.FORKED_SELECTOR)
                            SurfaceOwnershipRestoreRefusal.FORK else SurfaceOwnershipRestoreRefusal.CORRUPT,
                    )
                }
            }
        }

        /**
         * The terminal owner entry point: an existing selector opens v6, while an absent selector
         * consumes exactly the immutable #124 plan and publishes the switch before exposing it.
         */
        fun open(
            group: SurfaceGroup,
            directory: File,
            budget: CanonicalStorageBudget,
            plan: CanonicalActivationPlan,
            configuration: SurfaceOwnershipConfiguration = SurfaceOwnershipConfiguration(),
            fault: CanonicalActivationFault? = null,
        ): SurfaceOwnershipOpenResult {
            if (!configuration.isValid) return SurfaceOwnershipOpenResult.Refused(SurfaceOwnershipRestoreRefusal.INVALID_CONFIGURATION)
            return CanonicalActivationSelector.withGroupLock(directory, group) {
                when (val activation = CanonicalActivationSelector.activate(group, directory, budget, plan, fault)) {
                    is CanonicalActivationResult.Active -> openedV6(group, directory, budget, configuration, activation.state)
                    CanonicalActivationResult.Legacy,
                    CanonicalActivationResult.UnknownAfterSwitch -> SurfaceOwnershipOpenResult.Refused(SurfaceOwnershipRestoreRefusal.DURABILITY_FAILURE)
                    is CanonicalActivationResult.Refused -> SurfaceOwnershipOpenResult.Refused(
                        if (activation.reason == CanonicalActivationSelectorRefusal.FORKED_SELECTOR)
                            SurfaceOwnershipRestoreRefusal.FORK else SurfaceOwnershipRestoreRefusal.CORRUPT,
                    )
                }
            }
        }

        /** Consumes only the exact immutable #124 plan; it never scans legacy evidence itself. */
        fun activateV6(
            group: SurfaceGroup,
            directory: File,
            budget: CanonicalStorageBudget,
            plan: CanonicalActivationPlan,
            fault: CanonicalActivationFault? = null,
        ): CanonicalActivationResult = CanonicalActivationSelector.activate(group, directory, budget, plan, fault)

        private fun openedV6(
            group: SurfaceGroup,
            parent: File,
            budget: CanonicalStorageBudget,
            configuration: SurfaceOwnershipConfiguration,
            activation: CanonicalActivationState,
        ) = SurfaceOwnershipOpenResult.Opened(
            SurfaceOwnership(group, configuration, null, activeRestored(activation.cut), activation, parent, budget),
        )

        fun inMemory(
            group: SurfaceGroup,
            configuration: SurfaceOwnershipConfiguration = SurfaceOwnershipConfiguration(),
            fault: SurfaceOwnershipFault? = null,
        ): SurfaceOwnershipOpenResult = open(group, configuration, MemorySurfaceOwnershipStore(group, fault))

        private fun open(group: SurfaceGroup, configuration: SurfaceOwnershipConfiguration, store: SurfaceOwnershipStore): SurfaceOwnershipOpenResult {
            if (!configuration.isValid) return SurfaceOwnershipOpenResult.Refused(SurfaceOwnershipRestoreRefusal.INVALID_CONFIGURATION)
            return try {
                var restored = store.restore(configuration)
                configuration.seededEmptyBaseline?.let { baseline ->
                    if (baseline.groupIdentity != group.value) {
                        throw RestoreFailure(SurfaceOwnershipRestoreRefusal.FORK)
                    }
                    if (restored.rows.isEmpty() && restored.transactionReceipts.isEmpty() &&
                        restored.receipts.isEmpty() && restored.supports.isEmpty() &&
                        restored.sourceRecords.isEmpty() && restored.lineageEdges.isEmpty() &&
                        restored.geometryRevision == 0L && restored.lineageRevision == 0L &&
                        restored.seededEmptyBaseline == null
                    ) {
                        val seeded = restored.copy(
                            geometryRevision = baseline.geometryRevision,
                            lineageRevision = baseline.lineageRevision,
                            seededEmptyBaseline = baseline,
                        )
                        try {
                            // Baseline identity is authority, not configuration:
                            // make it durable before admission can observe open.
                            store.writeSnapshot(seeded.toSnapshot())
                        } catch (_: RootPublishedFault) {
                            // The root switch won; restore the exact published cut.
                        } catch (_: OwnershipFault) {
                            store.close()
                            return SurfaceOwnershipOpenResult.Refused(
                                SurfaceOwnershipRestoreRefusal.DURABILITY_FAILURE,
                            )
                        }
                        restored = store.restore(configuration)
                    } else if (restored.seededEmptyBaseline != baseline) {
                        throw RestoreFailure(SurfaceOwnershipRestoreRefusal.FORK)
                    }
                }
                SurfaceOwnershipOpenResult.Opened(SurfaceOwnership(group, configuration, store, restored))
            } catch (failure: RestoreFailure) {
                store.close()
                SurfaceOwnershipOpenResult.Refused(failure.reason)
            }
        }

        private fun activeRestored(cut: CompactCanonicalCut) = RestoredOwnership(
            cut.nextSurfaceIdHighWater, emptyList(), emptyList(), emptyMap(), emptyList(), emptyList(),
            emptyList(), cut.geometryRevision, cut.lineageRevision, cut.seededEmptyBaseline,
        )
    }
}

internal data class SurfaceGroup(val value: String) {
    init { require(value.isNotBlank() && value.encodeToByteArray().size <= 128) }
    internal val hash: ByteArray get() = sha256(value.encodeToByteArray())
}

internal data class SurfaceOwnershipConfiguration(
    val voxelMicrometers: Int = 100_000,
    val regionMicrometers: Int = 3_000_000,
    val pageMicrometers: Int = 1_000_000,
    val surfaceCapacity: Int = 100_000,
    val receiptCapacity: Int = 100_000,
    val transactionCapacity: Int = 100_000,
    val lineageCapacity: Int = 200_000,
    val changeJournalByteCapacity: Int = 1_048_576,
    val revisionLimit: Long = Long.MAX_VALUE,
    val seededEmptyBaseline: committedEmptyBaseline? = null,
) { internal val isValid get() = voxelMicrometers > 0 && regionMicrometers > 0 && pageMicrometers > 0 && regionMicrometers % voxelMicrometers == 0 && pageMicrometers % voxelMicrometers == 0 && regionMicrometers == pageMicrometers * 3 && surfaceCapacity > 0 && receiptCapacity > 0 && transactionCapacity > 0 && lineageCapacity > 0 && changeJournalByteCapacity > 0 && revisionLimit >= 0 && (seededEmptyBaseline == null || seededEmptyBaseline.geometryRevision <= revisionLimit && seededEmptyBaseline.lineageRevision <= revisionLimit) }

/**
 * Empty canonical surface baseline derived only after an exact bootstrap acknowledgement.
 * Transaction zero is the binding-local cursor for an authority-authenticated
 * restored cut; fresh ZERO bootstrap and all public material selectors remain positive.
 */
@ConsistentCopyVisibility
internal data class committedEmptyBaseline internal constructor(
    val bindingIdentity: String,
    val groupIdentity: String,
    val transactionId: Long,
    val geometryRevision: Long,
    val lineageRevision: Long,
) {
    init {
        require(bindingIdentity.isNotBlank() && bindingIdentity.length <= 256)
        require(groupIdentity.isNotBlank() && groupIdentity.encodeToByteArray().size <= 128)
        require(transactionId >= 0 && geometryRevision > 0 && lineageRevision > 0)
    }
}

internal data class SurfaceOwnershipCommand(val commandId: String, val candidates: List<SurfaceCandidate>) {
    internal fun fingerprint(): ByteArray = sha256(buildString {
        append(commandId).append('|')
        candidates.forEach { append(it.id).append(':').append(it.voxel).append(':').append(it.normalOctX).append(':').append(it.normalOctY).append(':').append(it.normalConfidence).append(';') }
    }.encodeToByteArray())
}
internal data class SurfaceCandidate(
    val id: Long? = null,
    val voxel: Voxel,
    val normalOctX: Int,
    val normalOctY: Int,
    val normalConfidence: Int,
)
internal data class Voxel(val x: Int, val y: Int, val z: Int)
internal data class SurfaceId(val value: Long) { init { require(value in 1 until 0x1_0000_0000L) } }
internal data class StorageRegion(val x: Int, val y: Int, val z: Int)
internal data class SurfaceOwner(val id: SurfaceId, val group: SurfaceGroup, val voxel: Voxel, val region: StorageRegion, val page: Int, val packedNormal: Int, val normalConfidence: Int, internal val allocatedBy: ByteArray) {
    val reliabilityBand: NormalReliabilityBand get() = when (normalConfidence) {
        0 -> NormalReliabilityBand.UNKNOWN
        in 1..63 -> NormalReliabilityBand.WEAK
        in 64..191 -> NormalReliabilityBand.RELIABLE
        else -> NormalReliabilityBand.STRONG
    }
    override fun equals(other: Any?): Boolean = other is SurfaceOwner && id == other.id && group == other.group &&
        voxel == other.voxel && region == other.region && page == other.page && packedNormal == other.packedNormal &&
        normalConfidence == other.normalConfidence && allocatedBy.contentEquals(other.allocatedBy)
    override fun hashCode(): Int = listOf(id, group, voxel, region, page, packedNormal, normalConfidence).hashCode() * 31 + allocatedBy.contentHashCode()
}
internal enum class NormalReliabilityBand { UNKNOWN, WEAK, RELIABLE, STRONG }
internal data class SurfaceOwnershipReceipt(val nextSurfaceIdHighWater: Long, val liveSurfaceCount: Int, val resultRowCount: Int, val allocatedCount: Int)
internal sealed interface SurfaceOwnershipResult { data class Accepted(val owners: List<SurfaceOwner>, val receipt: SurfaceOwnershipReceipt) : SurfaceOwnershipResult; data class Refused(val reason: SurfaceOwnershipRefusal, val receipt: SurfaceOwnershipReceipt) : SurfaceOwnershipResult }
internal enum class SurfaceOwnershipRefusal { CLOSED, INVALID_COMMAND, INVALID_OWNERSHIP, INVALID_NORMAL, UNKNOWN_IDENTITY, OWNERSHIP_CONFLICT, CAPACITY, EXHAUSTED, IDENTITY_CONFLICT, DURABILITY_FAILURE }
/** Existing ordinal values are persisted in canonical receipts; append only. */
internal enum class CanonicalOperation { RELOCATION, MERGE, SPLIT, REPLACEMENT, CREATE }
internal data class CanonicalTarget(
    val id: SurfaceId? = null,
    val voxel: Voxel,
    val normalOctX: Int,
    val normalOctY: Int,
    val normalConfidence: Int,
)
internal data class CanonicalTransactionCommand(
    val commandId: String,
    val kind: CanonicalOperation,
    val expectedGeometryRevision: Long,
    val expectedLineageRevision: Long,
    val sourceIds: List<SurfaceId>,
    val targets: List<CanonicalTarget>,
) {
    internal fun fingerprint(): ByteArray = sha256(buildString {
        append(commandId).append('|').append(kind).append('|').append(expectedGeometryRevision).append('|')
            .append(expectedLineageRevision).append('|')
        sourceIds.forEach { append(it.value).append(',') }
        append('|')
        targets.forEach { append(it.id?.value).append(':').append(it.voxel).append(':').append(it.normalOctX)
            .append(':').append(it.normalOctY).append(':').append(it.normalConfidence).append(';') }
    }.encodeToByteArray())
}
internal data class LineageEdge(val source: SurfaceId, val target: SurfaceId)
internal data class CanonicalStateReceipt(
    val geometryRevision: Long, val lineageRevision: Long, val nextSurfaceIdHighWater: Long, val liveSurfaceCount: Int,
)
internal data class CanonicalTransactionReceipt(
    val commandId: String,
    val kind: CanonicalOperation,
    val removedSurfaceIds: List<SurfaceId>,
    val lineageEdges: List<LineageEdge>,
    val geometryRevision: Long,
    val lineageRevision: Long,
    val nextSurfaceIdHighWater: Long,
    val liveSurfaceCount: Int,
    val sourceSupport: List<ImmutableSourceSupport>,
    val canonicalBytes: CanonicalReceiptBytes,
)
internal data class ImmutableSourceSupport(
    val id: SurfaceId, val voxel: Voxel, val packedNormal: Int, val normalConfidence: Int,
    val allocationFingerprint: CanonicalReceiptBytes,
)
internal class CanonicalReceiptBytes(bytes: ByteArray) {
    private val value = bytes.copyOf()
    val size: Int get() = value.size
    fun toByteArray(): ByteArray = value.copyOf()
    /** Fixed-size scalar comparison for streaming codecs; it never exposes the backing bytes. */
    internal fun matchesWords(first: Long, second: Long, third: Long, fourth: Long): Boolean {
        if (value.size != 32) return false
        fun word(offset: Int) = (0 until 8).fold(0L) { result, index ->
            (result shl 8) or (value[offset + index].toLong() and 0xffL)
        }
        return word(0) == first && word(8) == second && word(16) == third && word(24) == fourth
    }
    override fun equals(other: Any?): Boolean = other is CanonicalReceiptBytes && value.contentEquals(other.value)
    override fun hashCode(): Int = value.contentHashCode()
    companion object { val EMPTY = CanonicalReceiptBytes(ByteArray(0)) }
}
internal sealed interface CanonicalTransactionResult {
    data class Accepted(val targets: List<SurfaceOwner>, val receipt: CanonicalTransactionReceipt) : CanonicalTransactionResult
    data class Refused(val reason: CanonicalTransactionRefusal, val receipt: CanonicalStateReceipt) : CanonicalTransactionResult
}
internal enum class CanonicalTransactionRefusal {
    CLOSED, INVALID_COMMAND, INVALID_OWNERSHIP, INVALID_NORMAL, UNKNOWN_IDENTITY, OWNERSHIP_CONFLICT,
    CAPACITY, EXHAUSTED, REVISION_CONFLICT, REVISION_EXHAUSTED, LINEAGE_EXHAUSTED, JOURNAL_EXHAUSTED,
    IDENTITY_CONFLICT, DURABILITY_FAILURE,
}
internal sealed interface SurfaceOwnershipOpenResult { data class Opened(val ownership: SurfaceOwnership) : SurfaceOwnershipOpenResult; data class Refused(val reason: SurfaceOwnershipRestoreRefusal) : SurfaceOwnershipOpenResult }
internal enum class SurfaceOwnershipRestoreRefusal { INVALID_CONFIGURATION, CORRUPT, FORK, DURABILITY_FAILURE }
internal sealed interface SurfaceOwnershipCloseResult { data object Closed : SurfaceOwnershipCloseResult; data object AlreadyClosed : SurfaceOwnershipCloseResult }
internal enum class SurfaceOwnershipFault {
    AFTER_RESERVATION_FLUSH,
    AFTER_PRIVATE_CANDIDATE,
    BEFORE_SNAPSHOT_FLUSH,
    AFTER_SNAPSHOT_FILE_SYNC_BEFORE_ROOT_SWITCH,
    AFTER_ROOT_SWITCH_BEFORE_DIRECTORY_SYNC,
    AFTER_ROOT_DIRECTORY_SYNC,
}

private data class Location(val region: StorageRegion, val page: Int)
private typealias Reservation = AllocationRecord
private data class StoredReceipt(val commandHash: ByteArray, val fingerprint: ByteArray, val result: SurfaceOwnershipResult.Accepted)
private data class StoredCanonicalReceipt(val commandHash: ByteArray, val fingerprint: ByteArray, val result: CanonicalTransactionResult.Accepted)
private data class SourceSupportRecord(
    val id: SurfaceId, val voxel: Voxel, val packedNormal: Int, val normalConfidence: Int, val allocatedBy: ByteArray,
)
private fun SurfaceOwner.toSourceRecord() = SourceSupportRecord(id, voxel, packedNormal, normalConfidence, allocatedBy.copyOf())
private fun SourceSupportRecord.toReceiptSupport() = ImmutableSourceSupport(id, voxel, packedNormal, normalConfidence, CanonicalReceiptBytes(allocatedBy))
private data class OwnershipSnapshot(
    val nextHighWater: Long, val rows: List<SurfaceOwner>, val receipts: List<StoredReceipt>,
    val supports: Map<Long, LongArray> = emptyMap(), val sourceRecords: List<SourceSupportRecord> = emptyList(), val lineageEdges: List<LineageEdge> = emptyList(),
    val transactionReceipts: List<StoredCanonicalReceipt> = emptyList(),
    val geometryRevision: Long = 0, val lineageRevision: Long = 0,
    val seededEmptyBaseline: committedEmptyBaseline? = null,
)
private data class RestoredOwnership(
    val nextHighWater: Long, val rows: List<SurfaceOwner>, val receipts: List<StoredReceipt>,
    val supports: Map<Long, LongArray>, val sourceRecords: List<SourceSupportRecord>, val lineageEdges: List<LineageEdge>,
    val transactionReceipts: List<StoredCanonicalReceipt>, val geometryRevision: Long, val lineageRevision: Long,
    val seededEmptyBaseline: committedEmptyBaseline? = null,
)
private fun RestoredOwnership.toSnapshot() = OwnershipSnapshot(
    nextHighWater, rows, receipts, supports, sourceRecords, lineageEdges,
    transactionReceipts, geometryRevision, lineageRevision, seededEmptyBaseline,
)
private class OwnershipFault : RuntimeException()
private class RootPublishedFault : RuntimeException()
/** Internal decode failure shared with the v1-v5 migration reader. */
internal class RestoreFailure(val reason: SurfaceOwnershipRestoreRefusal) : RuntimeException()

private interface SurfaceOwnershipStore { val fault: SurfaceOwnershipFault?; val lastReservationRevision: Long; val lastReservationHash: ByteArray; fun appendReservation(reservation: Reservation); fun writeSnapshot(snapshot: OwnershipSnapshot); fun restore(configuration: SurfaceOwnershipConfiguration): RestoredOwnership; fun close() }

private class MemorySurfaceOwnershipStore(private val group: SurfaceGroup, override val fault: SurfaceOwnershipFault?) : SurfaceOwnershipStore {
    private val reservations = mutableListOf<Reservation>(); private var snapshot = OwnershipSnapshot(1, emptyList(), emptyList())
    override val lastReservationRevision get() = reservations.lastOrNull()?.revision ?: 0L
    override val lastReservationHash get() = reservations.lastOrNull()?.recordHash ?: ByteArray(32)
    override fun appendReservation(reservation: Reservation) { reservations += reservation; if (fault == SurfaceOwnershipFault.AFTER_RESERVATION_FLUSH) throw OwnershipFault() }
    override fun writeSnapshot(snapshot: OwnershipSnapshot) {
        if (fault == SurfaceOwnershipFault.BEFORE_SNAPSHOT_FLUSH || fault == SurfaceOwnershipFault.AFTER_SNAPSHOT_FILE_SYNC_BEFORE_ROOT_SWITCH) throw OwnershipFault()
        this.snapshot = snapshot
        if (fault == SurfaceOwnershipFault.AFTER_ROOT_SWITCH_BEFORE_DIRECTORY_SYNC || fault == SurfaceOwnershipFault.AFTER_ROOT_DIRECTORY_SYNC) throw RootPublishedFault()
    }
    override fun restore(configuration: SurfaceOwnershipConfiguration) = validate(group, configuration, reservations, snapshot)
    override fun close() = Unit
}

private class FileSurfaceOwnershipStore(directory: File, private val group: SurfaceGroup, override val fault: SurfaceOwnershipFault?) : SurfaceOwnershipStore {
    private val prefix = sha256(group.value.encodeToByteArray()).hex(); private val ledger = File(directory, "canonical-surface-surface-$prefix.ledger"); private val snapshot = File(directory, "canonical-surface-surface-$prefix.snapshot")
    private var reservations: List<Reservation> = readLedger(ledger)
    override val lastReservationRevision get() = reservations.lastOrNull()?.revision ?: 0L
    override val lastReservationHash get() = reservations.lastOrNull()?.recordHash ?: ByteArray(32)
    init { directory.mkdirs(); if (!directory.isDirectory) throw RestoreFailure(SurfaceOwnershipRestoreRefusal.CORRUPT) }
    override fun appendReservation(reservation: Reservation) { SurfaceAllocationAuthority.appendLegacy(ledger, reservation); reservations = reservations + reservation; if (fault == SurfaceOwnershipFault.AFTER_RESERVATION_FLUSH) throw OwnershipFault() }
    override fun writeSnapshot(snapshot: OwnershipSnapshot) {
        if (fault == SurfaceOwnershipFault.BEFORE_SNAPSHOT_FLUSH) throw OwnershipFault()
        val bytes = encodeSnapshot(snapshot)
        val temp = File("${this.snapshot.path}.tmp")
        try {
            FileOutputStream(temp).use { out -> out.write(bytes); out.fd.sync() }
            if (fault == SurfaceOwnershipFault.AFTER_SNAPSHOT_FILE_SYNC_BEFORE_ROOT_SWITCH) throw OwnershipFault()
            Files.move(temp.toPath(), this.snapshot.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            if (fault == SurfaceOwnershipFault.AFTER_ROOT_SWITCH_BEFORE_DIRECTORY_SYNC) throw RootPublishedFault()
            syncOwnershipDirectory(requireNotNull(this.snapshot.parentFile))
            if (fault == SurfaceOwnershipFault.AFTER_ROOT_DIRECTORY_SYNC) throw RootPublishedFault()
        } catch (failure: OwnershipFault) {
            throw failure
        } catch (failure: RootPublishedFault) {
            throw failure
        } catch (_: AtomicMoveNotSupportedException) {
            throw OwnershipFault()
        } catch (_: Exception) {
            throw OwnershipFault()
        }
    }
    override fun restore(configuration: SurfaceOwnershipConfiguration): RestoredOwnership = validate(group, configuration, reservations, if (snapshot.exists()) decodeSnapshot(snapshot.readBytes(), configuration) else OwnershipSnapshot(1, emptyList(), emptyList()))
    override fun close() = Unit
}

private fun validate(group: SurfaceGroup, configuration: SurfaceOwnershipConfiguration, reservations: List<Reservation>, snapshot: OwnershipSnapshot): RestoredOwnership {
    if (snapshot.seededEmptyBaseline != null && snapshot.seededEmptyBaseline != configuration.seededEmptyBaseline) {
        throw RestoreFailure(SurfaceOwnershipRestoreRefusal.FORK)
    }
    val allocation = try {
        SurfaceAllocationAuthority.validate(group, reservations)
    } catch (_: Exception) {
        throw RestoreFailure(SurfaceOwnershipRestoreRefusal.CORRUPT)
    }
    val highWater = allocation.highWater
    if (snapshot.nextHighWater !in 1..highWater) throw RestoreFailure(SurfaceOwnershipRestoreRefusal.FORK)
    if (highWater > 0x1_0000_0000L) throw RestoreFailure(SurfaceOwnershipRestoreRefusal.CORRUPT)
    if (snapshot.rows.size > configuration.surfaceCapacity || snapshot.receipts.size > configuration.receiptCapacity ||
        snapshot.transactionReceipts.size > configuration.transactionCapacity || snapshot.lineageEdges.size > configuration.lineageCapacity ||
        snapshot.geometryRevision < 0 || snapshot.lineageRevision < 0) throw RestoreFailure(SurfaceOwnershipRestoreRefusal.CORRUPT)
    val ids = hashSetOf<Long>(); val voxels = hashSetOf<Voxel>()
    snapshot.rows.forEach { row -> if (row.group != group || row.id.value !in 1 until snapshot.nextHighWater || !ids.add(row.id.value) || !voxels.add(row.voxel) || !isCanonicalPackedNormal(row.packedNormal, row.normalConfidence) || locationFor(configuration, row.voxel) != Location(row.region, row.page)) throw RestoreFailure(SurfaceOwnershipRestoreRefusal.CORRUPT) }
    val receiptIds = hashSetOf<String>()
    snapshot.receipts.forEach { receipt ->
        if (receipt.commandHash.size != 32 || receipt.fingerprint.size != 32 || !receiptIds.add(receipt.commandHash.hex()) || receipt.result.owners.any { rows -> rowsById(snapshot.rows, rows.id.value) == null }) throw RestoreFailure(SurfaceOwnershipRestoreRefusal.CORRUPT)
    }
    val supports = if (snapshot.supports.isEmpty()) snapshot.rows.associate { it.id.value to longArrayOf(it.id.value) } else snapshot.supports
    if (supports.keys != ids || supports.values.any { values -> values.isEmpty() || !values.contentEquals(values.distinct().sorted().toLongArray()) || values.any { it !in 1 until highWater } }) {
        throw RestoreFailure(SurfaceOwnershipRestoreRefusal.CORRUPT)
    }
    if (snapshot.lineageEdges != snapshot.lineageEdges.sortedWith(compareBy({ it.source.value }, { it.target.value })) ||
        snapshot.lineageEdges.any { it.source.value !in 1 until highWater || it.target.value !in 1 until highWater }) {
        throw RestoreFailure(SurfaceOwnershipRestoreRefusal.CORRUPT)
    }
    val sources = if (snapshot.sourceRecords.isEmpty()) snapshot.rows.map { it.toSourceRecord() } else snapshot.sourceRecords
    val sourceIds = hashSetOf<Long>()
    if (sources.any { !sourceIds.add(it.id.value) || it.id.value !in 1 until highWater ||
            !isCanonicalPackedNormal(it.packedNormal, it.normalConfidence) || it.allocatedBy.size != 32 }) {
        throw RestoreFailure(SurfaceOwnershipRestoreRefusal.CORRUPT)
    }
    if (supports.values.any { roots -> roots.any { it !in sourceIds } }) throw RestoreFailure(SurfaceOwnershipRestoreRefusal.CORRUPT)
    val canonicalIds = hashSetOf<String>()
    var journalBytes = 0L
    snapshot.transactionReceipts.forEach { receipt ->
        journalBytes = checkedAdd(journalBytes, canonicalJournalEntryBytes(receipt.result))
            ?: throw RestoreFailure(SurfaceOwnershipRestoreRefusal.CORRUPT)
        if (receipt.commandHash.size != 32 || receipt.fingerprint.size != 32 || !canonicalIds.add(receipt.commandHash.hex()) ||
            receipt.result.targets.any { it.group != group || it.id.value !in 1 until highWater ||
                locationFor(configuration, it.voxel) != Location(it.region, it.page) || !isCanonicalPackedNormal(it.packedNormal, it.normalConfidence) } ||
            receipt.result.receipt.geometryRevision > snapshot.geometryRevision || receipt.result.receipt.lineageRevision > snapshot.lineageRevision ||
            !receipt.result.receipt.canonicalBytes.toByteArray().contentEquals(encodeCanonicalReceipt(group, receipt.result))) {
            throw RestoreFailure(SurfaceOwnershipRestoreRefusal.CORRUPT)
        }
    }
    if (journalBytes > configuration.changeJournalByteCapacity) throw RestoreFailure(SurfaceOwnershipRestoreRefusal.CORRUPT)
    return RestoredOwnership(highWater, snapshot.rows, snapshot.receipts, supports, sources, snapshot.lineageEdges,
        snapshot.transactionReceipts, snapshot.geometryRevision, snapshot.lineageRevision, snapshot.seededEmptyBaseline)
}

private fun readLedger(file: File): List<Reservation> = try {
    if (!file.exists()) emptyList() else {
        val bytes = file.readBytes()
        require(bytes.size % AllocationRecord.ENCODED_BYTES == 0)
        bytes.asList().chunked(AllocationRecord.ENCODED_BYTES).map { AllocationRecord.decode(it.toByteArray()) }
    }
} catch (_: Exception) {
    throw RestoreFailure(SurfaceOwnershipRestoreRefusal.CORRUPT)
}
private fun encodeSnapshot(snapshot: OwnershipSnapshot): ByteArray {
    val body = ByteArrayOutputStream().use { output ->
        DataOutputStream(output).use { data ->
            data.writeInt(0x4d33534f)
            data.writeInt(5)
            data.writeLong(snapshot.nextHighWater)
            data.writeInt(snapshot.rows.size)
            snapshot.rows.forEach { row ->
                data.writeLong(row.id.value)
                data.writeUTF(row.group.value)
                data.writeInt(row.voxel.x)
                data.writeInt(row.voxel.y)
                data.writeInt(row.voxel.z)
                data.writeInt(row.region.x)
                data.writeInt(row.region.y)
                data.writeInt(row.region.z)
                data.writeInt(row.page)
                data.writeInt(row.packedNormal)
                data.writeInt(row.normalConfidence)
                data.write(row.allocatedBy)
            }
            data.writeInt(snapshot.receipts.size)
            snapshot.receipts.forEach { receipt ->
                data.write(receipt.commandHash)
                data.write(receipt.fingerprint)
                val accepted = receipt.result
                data.writeInt(accepted.owners.size)
                accepted.owners.forEach { data.writeLong(it.id.value) }
                data.writeLong(accepted.receipt.nextSurfaceIdHighWater)
                data.writeInt(accepted.receipt.liveSurfaceCount)
                data.writeInt(accepted.receipt.resultRowCount)
                data.writeInt(accepted.receipt.allocatedCount)
            }
            data.writeLong(snapshot.geometryRevision)
            data.writeLong(snapshot.lineageRevision)
            data.writeInt(snapshot.supports.size)
            snapshot.supports.toSortedMap().forEach { (id, supports) ->
                data.writeLong(id); data.writeInt(supports.size); supports.forEach(data::writeLong)
            }
            data.writeInt(snapshot.sourceRecords.size)
            snapshot.sourceRecords.sortedBy { it.id.value }.forEach { source ->
                data.writeLong(source.id.value); data.writeInt(source.voxel.x); data.writeInt(source.voxel.y); data.writeInt(source.voxel.z)
                data.writeInt(source.packedNormal); data.writeInt(source.normalConfidence); data.write(source.allocatedBy)
            }
            data.writeInt(snapshot.lineageEdges.size)
            snapshot.lineageEdges.forEach { data.writeLong(it.source.value); data.writeLong(it.target.value) }
            data.writeInt(snapshot.transactionReceipts.size)
            snapshot.transactionReceipts.forEach { stored ->
                data.write(stored.commandHash); data.write(stored.fingerprint)
                val result = stored.result
                val canonical = result.receipt.canonicalBytes.toByteArray()
                data.writeInt(canonical.size); data.write(canonical)
            }
            val baseline = snapshot.seededEmptyBaseline
            data.writeBoolean(baseline != null)
            if (baseline != null) {
                data.writeUTF(baseline.bindingIdentity)
                data.writeUTF(baseline.groupIdentity)
                data.writeLong(baseline.transactionId)
                data.writeLong(baseline.geometryRevision)
                data.writeLong(baseline.lineageRevision)
            }
        }
        output.toByteArray()
    }
    return body + sha256(body)
}
private fun decodeSnapshot(
    bytes: ByteArray,
    configuration: SurfaceOwnershipConfiguration,
): OwnershipSnapshot = try {
    if (bytes.size < 32) throw RestoreFailure(SurfaceOwnershipRestoreRefusal.CORRUPT)
    val body = bytes.copyOfRange(0, bytes.size - 32)
    if (!sha256(body).contentEquals(bytes.copyOfRange(bytes.size - 32, bytes.size))) {
        throw RestoreFailure(SurfaceOwnershipRestoreRefusal.CORRUPT)
    }
    DataInputStream(ByteArrayInputStream(body)).use { data ->
        if (data.readInt() != 0x4d33534f) {
            throw RestoreFailure(SurfaceOwnershipRestoreRefusal.CORRUPT)
        }
        val version = data.readInt()
        if (version !in 1..5) throw RestoreFailure(SurfaceOwnershipRestoreRefusal.CORRUPT)
        val high = data.readLong()
        val rowCount = boundedCount(data.readInt(), configuration.surfaceCapacity)
        val rows = List(rowCount) {
            SurfaceOwner(
                SurfaceId(data.readLong()),
                SurfaceGroup(data.readUTF()),
                Voxel(data.readInt(), data.readInt(), data.readInt()),
                StorageRegion(data.readInt(), data.readInt(), data.readInt()),
                data.readInt(),
                data.readInt(),
                data.readInt(),
                ByteArray(32).also(data::readFully),
            )
        }
        val byId = rows.associateBy { it.id.value }
        val receiptCount = boundedCount(data.readInt(), configuration.receiptCapacity)
        val receipts = List(receiptCount) {
            val commandHash = ByteArray(32).also(data::readFully)
            val fingerprint = ByteArray(32).also(data::readFully)
            val ownerCount = boundedCount(data.readInt(), configuration.surfaceCapacity)
            val owners = List(ownerCount) {
                byId[data.readLong()] ?: throw RestoreFailure(SurfaceOwnershipRestoreRefusal.CORRUPT)
            }
            val receipt = SurfaceOwnershipReceipt(data.readLong(), data.readInt(), data.readInt(), data.readInt())
            StoredReceipt(commandHash, fingerprint, SurfaceOwnershipResult.Accepted(owners, receipt))
        }
        if (version == 1) {
            if (data.available() != 0) throw RestoreFailure(SurfaceOwnershipRestoreRefusal.CORRUPT)
            OwnershipSnapshot(high, rows, receipts)
        } else {
            val geometryRevision = data.readLong()
            val lineageRevision = data.readLong()
            val supportCount = boundedCount(data.readInt(), configuration.surfaceCapacity)
            val supports = linkedMapOf<Long, LongArray>()
            repeat(supportCount) {
                val id = data.readLong(); val count = boundedCount(data.readInt(), configuration.lineageCapacity)
                supports[id] = LongArray(count) { data.readLong() }
            }
            val sources = if (version >= 3) {
                val count = boundedCount(data.readInt(), configuration.surfaceCapacity + configuration.lineageCapacity)
                List(count) { SourceSupportRecord(SurfaceId(data.readLong()), Voxel(data.readInt(), data.readInt(), data.readInt()),
                    data.readInt(), data.readInt(), ByteArray(32).also(data::readFully)) }
            } else emptyList()
            val edgeCount = boundedCount(data.readInt(), configuration.lineageCapacity)
            val edges = List(edgeCount) { LineageEdge(SurfaceId(data.readLong()), SurfaceId(data.readLong())) }
            val canonicalCount = boundedCount(data.readInt(), configuration.transactionCapacity)
            val canonicalReceipts = List(canonicalCount) {
                val commandHash = ByteArray(32).also(data::readFully)
                val fingerprint = ByteArray(32).also(data::readFully)
                if (version >= 4) {
                    val size = boundedCount(data.readInt(), configuration.changeJournalByteCapacity)
                    val canonical = ByteArray(size).also(data::readFully)
                    StoredCanonicalReceipt(commandHash, fingerprint, decodeCanonicalReceipt(canonical, configuration))
                } else {
                val commandId = data.readUTF()
                val kindOrdinal = data.readInt()
                val kind = CanonicalOperation.entries.getOrNull(kindOrdinal) ?: throw RestoreFailure(SurfaceOwnershipRestoreRefusal.CORRUPT)
                val targetCount = boundedCount(data.readInt(), configuration.surfaceCapacity)
                val targets = List(targetCount) {
                    SurfaceOwner(SurfaceId(data.readLong()), SurfaceGroup(data.readUTF()),
                        Voxel(data.readInt(), data.readInt(), data.readInt()),
                        StorageRegion(data.readInt(), data.readInt(), data.readInt()),
                        data.readInt(), data.readInt(), data.readInt(), ByteArray(32).also(data::readFully))
                }
                val removedCount = boundedCount(data.readInt(), configuration.surfaceCapacity)
                val removed = List(removedCount) { SurfaceId(data.readLong()) }
                val receiptEdgeCount = boundedCount(data.readInt(), configuration.lineageCapacity)
                val receiptEdges = List(receiptEdgeCount) { LineageEdge(SurfaceId(data.readLong()), SurfaceId(data.readLong())) }
                val receiptGeometry = data.readLong(); val receiptLineage = data.readLong(); val receiptHigh = data.readLong(); val receiptLive = data.readInt()
                val receiptSupports = if (version >= 3) {
                    val count = boundedCount(data.readInt(), configuration.lineageCapacity)
                    List(count) { ImmutableSourceSupport(SurfaceId(data.readLong()), Voxel(data.readInt(), data.readInt(), data.readInt()),
                        data.readInt(), data.readInt(), CanonicalReceiptBytes(ByteArray(32).also(data::readFully))) }
                } else emptyList()
                val receiptWithoutBytes = CanonicalTransactionReceipt(commandId, kind, removed, receiptEdges,
                    receiptGeometry, receiptLineage, receiptHigh, receiptLive, receiptSupports, CanonicalReceiptBytes.EMPTY)
                val provisional = CanonicalTransactionResult.Accepted(targets, receiptWithoutBytes)
                val canonical = if (version >= 3) {
                    val size = boundedCount(data.readInt(), configuration.changeJournalByteCapacity)
                    ByteArray(size).also(data::readFully)
                } else encodeCanonicalReceipt(targets.first().group, provisional)
                StoredCanonicalReceipt(commandHash, fingerprint, provisional.copy(receipt = receiptWithoutBytes.copy(canonicalBytes = CanonicalReceiptBytes(canonical))))
                }
            }
            val baseline = if (version >= 5 && data.readBoolean()) {
                committedEmptyBaseline(
                    data.readUTF(), data.readUTF(), data.readLong(), data.readLong(), data.readLong(),
                )
            } else null
            if (data.available() != 0) throw RestoreFailure(SurfaceOwnershipRestoreRefusal.CORRUPT)
            OwnershipSnapshot(high, rows, receipts, supports, sources, edges, canonicalReceipts, geometryRevision, lineageRevision, baseline)
        }
    }
} catch (failure: RestoreFailure) {
    throw failure
} catch (_: Exception) {
    throw RestoreFailure(SurfaceOwnershipRestoreRefusal.CORRUPT)
}

private fun encodeCanonicalReceipt(group: SurfaceGroup, result: CanonicalTransactionResult.Accepted): ByteArray =
    ByteArrayOutputStream().use { output ->
        DataOutputStream(output).use { data ->
            val receipt = result.receipt
            data.writeInt(0x4d334352); data.writeInt(1); data.writeUTF(group.value); data.writeUTF(receipt.commandId)
            data.writeInt(receipt.kind.ordinal); data.writeLong(receipt.geometryRevision); data.writeLong(receipt.lineageRevision)
            data.writeLong(receipt.nextSurfaceIdHighWater); data.writeInt(receipt.liveSurfaceCount)
            data.writeInt(result.targets.size)
            result.targets.forEach { row ->
                data.writeLong(row.id.value); data.writeUTF(row.group.value)
                data.writeInt(row.voxel.x); data.writeInt(row.voxel.y); data.writeInt(row.voxel.z)
                data.writeInt(row.region.x); data.writeInt(row.region.y); data.writeInt(row.region.z)
                data.writeInt(row.page); data.writeInt(row.packedNormal); data.writeInt(row.normalConfidence); data.write(row.allocatedBy)
            }
            data.writeInt(receipt.removedSurfaceIds.size); receipt.removedSurfaceIds.forEach { data.writeLong(it.value) }
            data.writeInt(receipt.lineageEdges.size); receipt.lineageEdges.forEach { data.writeLong(it.source.value); data.writeLong(it.target.value) }
            data.writeInt(receipt.sourceSupport.size)
            receipt.sourceSupport.forEach { source ->
                data.writeLong(source.id.value); data.writeInt(source.voxel.x); data.writeInt(source.voxel.y); data.writeInt(source.voxel.z)
                data.writeInt(source.packedNormal); data.writeInt(source.normalConfidence); data.write(source.allocationFingerprint.toByteArray())
            }
        }
        output.toByteArray()
    }

private fun decodeCanonicalReceipt(bytes: ByteArray, configuration: SurfaceOwnershipConfiguration): CanonicalTransactionResult.Accepted = try {
    DataInputStream(ByteArrayInputStream(bytes)).use { data ->
        if (data.readInt() != 0x4d334352 || data.readInt() != 1) throw RestoreFailure(SurfaceOwnershipRestoreRefusal.CORRUPT)
        val group = SurfaceGroup(data.readUTF()); val commandId = data.readUTF()
        val kind = CanonicalOperation.entries.getOrNull(data.readInt()) ?: throw RestoreFailure(SurfaceOwnershipRestoreRefusal.CORRUPT)
        val geometry = data.readLong(); val lineage = data.readLong(); val high = data.readLong(); val live = data.readInt()
        val targetCount = boundedCount(data.readInt(), configuration.surfaceCapacity)
        val targets = List(targetCount) {
            SurfaceOwner(SurfaceId(data.readLong()), SurfaceGroup(data.readUTF()),
                Voxel(data.readInt(), data.readInt(), data.readInt()), StorageRegion(data.readInt(), data.readInt(), data.readInt()),
                data.readInt(), data.readInt(), data.readInt(), ByteArray(32).also(data::readFully))
        }
        if (targets.any { it.group != group }) throw RestoreFailure(SurfaceOwnershipRestoreRefusal.CORRUPT)
        val removed = List(boundedCount(data.readInt(), configuration.surfaceCapacity)) { SurfaceId(data.readLong()) }
        val edges = List(boundedCount(data.readInt(), configuration.lineageCapacity)) { LineageEdge(SurfaceId(data.readLong()), SurfaceId(data.readLong())) }
        val supports = List(boundedCount(data.readInt(), configuration.lineageCapacity)) {
            ImmutableSourceSupport(SurfaceId(data.readLong()), Voxel(data.readInt(), data.readInt(), data.readInt()),
                data.readInt(), data.readInt(), CanonicalReceiptBytes(ByteArray(32).also(data::readFully)))
        }
        if (data.available() != 0) throw RestoreFailure(SurfaceOwnershipRestoreRefusal.CORRUPT)
        CanonicalTransactionResult.Accepted(targets, CanonicalTransactionReceipt(commandId, kind, removed, edges,
            geometry, lineage, high, live, supports, CanonicalReceiptBytes(bytes)))
    }
} catch (failure: RestoreFailure) { throw failure } catch (_: Exception) { throw RestoreFailure(SurfaceOwnershipRestoreRefusal.CORRUPT) }

private const val CANONICAL_JOURNAL_ENVELOPE_BYTES = 68L
private fun canonicalJournalEntryBytes(result: CanonicalTransactionResult.Accepted): Long =
    checkedAdd(CANONICAL_JOURNAL_ENVELOPE_BYTES, result.receipt.canonicalBytes.size.toLong()) ?: Long.MAX_VALUE

private fun checkedAdd(left: Long, right: Long): Long? = try { Math.addExact(left, right) } catch (_: ArithmeticException) { null }
private fun syncOwnershipDirectory(directory: File) {
    // Android/Linux supports directory fsync. The Windows JVM test adapter has
    // atomic rename semantics but does not expose directory descriptors.
    if ((System.getProperty("os.name") ?: "").startsWith("Windows", ignoreCase = true)) return
    FileChannel.open(directory.toPath(), StandardOpenOption.READ).use { it.force(true) }
}

private fun boundedCount(value: Int, maximum: Int): Int {
    if (value !in 0..maximum) throw RestoreFailure(SurfaceOwnershipRestoreRefusal.CORRUPT)
    return value
}

private fun rowsById(rows: List<SurfaceOwner>, id: Long): SurfaceOwner? = rows.firstOrNull { it.id.value == id }
private fun locationFor(
    configuration: SurfaceOwnershipConfiguration,
    voxel: Voxel,
): Location? {
    val cellsPerRegion = configuration.regionMicrometers / configuration.voxelMicrometers
    val cellsPerPage = configuration.pageMicrometers / configuration.voxelMicrometers
    if (cellsPerRegion != cellsPerPage * 3) return null

    fun axis(value: Int): Pair<Int, Int> =
        Math.floorDiv(value, cellsPerRegion) to Math.floorMod(value, cellsPerRegion)

    val (rx, lx) = axis(voxel.x)
    val (ry, ly) = axis(voxel.y)
    val (rz, lz) = axis(voxel.z)
    val px = lx / cellsPerPage
    val py = ly / cellsPerPage
    val pz = lz / cellsPerPage
    return if (px !in 0..2 || py !in 0..2 || pz !in 0..2) {
        null
    } else {
        Location(StorageRegion(rx, ry, rz), px + 3 * (py + 3 * pz))
    }
}

private fun packNormal(x: Int, y: Int, confidence: Int): Pair<Int, Int>? {
    if (x !in -127..127 || y !in -127..127 || confidence !in 0..255) return null
    return (((x and 0xff) shl 8) or (y and 0xff)) to confidence
}

private fun isCanonicalPackedNormal(packed: Int, confidence: Int): Boolean {
    if (packed !in 0..0xffff || confidence !in 0..255) return false
    return (packed ushr 8) != 0x80 && (packed and 0xff) != 0x80
}
private fun sha256(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)
private fun ByteArray.hex(): String = joinToString("") { "%02x".format(it) }
