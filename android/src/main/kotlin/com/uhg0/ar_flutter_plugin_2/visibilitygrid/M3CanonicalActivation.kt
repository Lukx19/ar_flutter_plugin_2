package com.uhg0.ar_flutter_plugin_2.visibilitygrid

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.OutputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest

/**
 * Read-only bridge between a validated legacy authority and its inactive v6 sibling.  It owns the
 * selection rules so the later activation step receives one immutable fact, not a second chance to
 * reinterpret receipt history.
 */
internal object M3CanonicalActivation {
    private val PREPARATION_AUTHORITY = Any()
    /**
     * Opaque preparation capability. The binding retains the exact objects selected by [prepare],
     * in addition to their values, so copying a cut/current or substituting a source cannot create
     * another valid activation request.
     */
    internal class Plan internal constructor(
        internal val legacySourceHash: M3CanonicalReceiptBytes,
        internal val siblingCut: M3CompactCanonicalCut,
        internal val current: M3CanonicalActivationCurrent,
        internal val receipt: M3CanonicalActivationPreparationReceipt,
        private val preparationAuthority: Any? = null,
    ) {
        private val binding = Binding(legacySourceHash, siblingCut, current)

        internal fun isExactlyBound() =
            preparationAuthority === PREPARATION_AUTHORITY &&
                binding.legacySourceHash === legacySourceHash &&
                binding.siblingCut === siblingCut &&
                binding.current === current &&
                (current !is M3CanonicalActivationCurrent.Receipt ||
                    binding.currentSource === current.source)

        private class Binding(
            val legacySourceHash: M3CanonicalReceiptBytes,
            val siblingCut: M3CompactCanonicalCut,
            val current: M3CanonicalActivationCurrent,
        ) {
            val currentSource = (current as? M3CanonicalActivationCurrent.Receipt)?.source
        }
    }

    fun prepare(
        group: M3SurfaceGroup,
        directory: File,
        budget: M3CanonicalStorageBudget,
        configuration: M3SurfaceOwnershipConfiguration = M3SurfaceOwnershipConfiguration(),
    ): M3CanonicalActivationPreparation = try {
        val legacy = M3SurfaceOwnershipLegacyCodec.readValidated(group, directory, configuration)
        val opened = M3CompactCanonicalStore.openV6(group, directory, budget, configuration)
        val sibling = (opened as? M3CompactCanonicalOpenResult.Opened)?.store
            ?: return M3CanonicalActivationPreparation.Refused(M3CanonicalActivationRefusal.SIBLING_INVALID)
        val cut = sibling.use { it.cut }
        if (!matchesLegacy(legacy, cut))
            return M3CanonicalActivationPreparation.Refused(M3CanonicalActivationRefusal.SIBLING_MISMATCH)

        var selected: M3LegacyCanonicalReceipt? = null
        var scanned = 0L
        var matching = 0L
        var refusal: M3CanonicalActivationRefusal? = null
        legacy.visitCanonicalReceipts { receipt ->
            scanned++
            if (receipt.geometryRevision != legacy.geometryRevision ||
                receipt.lineageRevision != legacy.lineageRevision
            ) return@visitCanonicalReceipts
            if (receipt.nextHighWater != legacy.nextHighWater ||
                receipt.liveSurfaceCount != legacy.resident.rows
            ) {
                refusal = M3CanonicalActivationRefusal.INCOMPATIBLE_FINAL_CUT
                return@visitCanonicalReceipts
            }
            matching++
            val prior = selected
            if (prior == null) {
                selected = receipt
                return@visitCanonicalReceipts
            }
            refusal = when {
                prior.commandHash == receipt.commandHash &&
                    prior.commandFingerprint == receipt.commandFingerprint &&
                    prior.canonicalHash == receipt.canonicalHash &&
                    prior.canonicalLength == receipt.canonicalLength -> null
                prior.commandHash == receipt.commandHash &&
                    prior.commandFingerprint == receipt.commandFingerprint ->
                    M3CanonicalActivationRefusal.CHANGED_RECEIPT
                prior.commandHash == receipt.commandHash -> M3CanonicalActivationRefusal.FORKED_IDENTITY
                prior.canonicalHash == receipt.canonicalHash -> M3CanonicalActivationRefusal.FORKED_IDENTITY
                else -> M3CanonicalActivationRefusal.AMBIGUOUS_CURRENT
            }
        }
        refusal?.let { return M3CanonicalActivationPreparation.Refused(it) }
        val current = selected?.let {
            M3CanonicalActivationCurrent.Receipt(
                M3CanonicalCurrentIdentity(
                    it.commandHash,
                    it.commandFingerprint,
                    it.canonicalLength,
                    it.canonicalHash,
                ),
                M3CanonicalCurrentSource(it),
            )
        } ?: M3CanonicalActivationCurrent.None
        M3CanonicalActivationPreparation.Prepared(
            Plan(
                M3CanonicalReceiptBytes(legacy.sourceHash), cut, current,
                M3CanonicalActivationPreparationReceipt(scanned, matching, 512),
                PREPARATION_AUTHORITY,
            )
        )
    } catch (_: M3RestoreFailure) {
        M3CanonicalActivationPreparation.Refused(M3CanonicalActivationRefusal.LEGACY_INVALID)
    } catch (_: Exception) {
        M3CanonicalActivationPreparation.Refused(M3CanonicalActivationRefusal.LEGACY_INVALID)
    }

    private fun matchesLegacy(legacy: M3LegacyCanonicalState, cut: M3CompactCanonicalCut) =
        cut.group == legacy.group &&
            cut.profile == M3CompactCanonicalStore.PROFILE &&
            cut.geometryRevision == legacy.geometryRevision &&
            cut.lineageRevision == legacy.lineageRevision &&
            cut.nextSurfaceIdHighWater == legacy.nextHighWater &&
            cut.liveSurfaceCount == legacy.resident.rows &&
            cut.sourceCount == legacy.sourceCount &&
            cut.supportCount == legacy.supportCount &&
            cut.lineageCount == legacy.lineageCount &&
            cut.seededEmptyBaseline == legacy.baseline &&
            cut.sourceHash == M3CanonicalReceiptBytes(legacy.sourceHash)
}

internal typealias M3CanonicalActivationPlan = M3CanonicalActivation.Plan

internal data class M3CanonicalActivationPreparationReceipt(
    val scannedReceipts: Long,
    val finalCutReceipts: Long,
    /** One fixed descriptor and no history or receipt body. */
    val retainedBytes: Long,
)

internal sealed interface M3CanonicalActivationPreparation {
    data class Prepared(val plan: M3CanonicalActivationPlan) : M3CanonicalActivationPreparation
    data class Refused(val reason: M3CanonicalActivationRefusal) : M3CanonicalActivationPreparation
}

internal sealed interface M3CanonicalActivationCurrent {
    data object None : M3CanonicalActivationCurrent
    data class Receipt(
        val identity: M3CanonicalCurrentIdentity,
        val source: M3CanonicalCurrentSource,
    ) : M3CanonicalActivationCurrent
}

internal data class M3CanonicalCurrentIdentity(
    val commandHash: M3CanonicalReceiptBytes,
    val commandFingerprint: M3CanonicalReceiptBytes,
    val canonicalLength: Long,
    val canonicalHash: M3CanonicalReceiptBytes,
)

/** A source can copy one selected immutable receipt, but never exposes its backing byte array. */
internal class M3CanonicalCurrentSource internal constructor(
    private val copyTo: (OutputStream) -> Unit,
) {
    internal constructor(receipt: M3LegacyCanonicalReceipt) : this(receipt::writeCanonicalTo)

    fun writeTo(output: OutputStream) = copyTo(output)

    internal companion object {
        fun fromFile(file: File) = M3CanonicalCurrentSource { output ->
            FileInputStream(file).use { input ->
                val scratch = ByteArray(CURRENT_STREAM_BUFFER_BYTES)
                while (true) {
                    val count = input.read(scratch)
                    if (count < 0) break
                    output.write(scratch, 0, count)
                }
            }
        }
    }
}

internal enum class M3CanonicalActivationRefusal {
    LEGACY_INVALID,
    SIBLING_INVALID,
    SIBLING_MISMATCH,
    INCOMPATIBLE_FINAL_CUT,
    AMBIGUOUS_CURRENT,
    FORKED_IDENTITY,
    CHANGED_RECEIPT,
}

/**
 * The one group-local durable switch from immutable legacy authority to its already prepared v6
 * sibling.  The selector is deliberately separate from #117's mutation selector: no mutable
 * generation can be admitted here, and an active selector is sufficient authority to bypass
 * legacy parsing forever.
 */
internal object M3CanonicalActivationSelector {
    fun activate(
        group: M3SurfaceGroup,
        parent: File,
        budget: M3CanonicalStorageBudget,
        plan: M3CanonicalActivationPlan,
        fault: M3CanonicalActivationFault? = null,
    ): M3CanonicalActivationResult = withGroupLock(parent, group) {
        if (legacyLeaseCount(parent, group) != 0) {
            return@withGroupLock M3CanonicalActivationResult.Refused(
                M3CanonicalActivationSelectorRefusal.LEGACY_OWNER_ACTIVE,
            )
        }
        if (!reconcileReclaimsLocked(group, parent, budget) || !reconcileAttemptsLocked(group, parent, budget) || !reconcileAcknowledgementsLocked(group, parent, budget) ||
            !reconcileAdjacentTransactionsLocked(group, parent, budget)) {
            return@withGroupLock M3CanonicalActivationResult.Refused(
                M3CanonicalActivationSelectorRefusal.DURABILITY_FAILURE,
            )
        }
        val existing = reopenLocked(group, parent, budget)
        when (existing) {
            is M3CanonicalActivationResult.Active -> return@withGroupLock replay(existing.state, plan)
            is M3CanonicalActivationResult.Refused -> return@withGroupLock existing
            M3CanonicalActivationResult.Legacy -> Unit
            M3CanonicalActivationResult.UnknownAfterSwitch -> return@withGroupLock M3CanonicalActivationResult.Refused(
                M3CanonicalActivationSelectorRefusal.DURABILITY_FAILURE,
            )
        }
        if (!validPlan(group, parent, budget, plan)) {
            return@withGroupLock M3CanonicalActivationResult.Refused(M3CanonicalActivationSelectorRefusal.INVALID_PLAN)
        }

        val root = ActivationRoot(
            plan.siblingCut,
            (plan.current as? M3CanonicalActivationCurrent.Receipt)?.let { CurrentRecord.Unacknowledged(it.identity) },
        )
        val rootBytes = root.bytes()
        val rootHash = digest(rootBytes)
        val rootTarget = rootFile(parent, group, rootHash)
        val currentTarget = currentFile(parent, group, plan.current)
        val slotTarget = slotFile(parent, group, 0)
        val selectorTarget = selectorFile(parent, group)
        if (slotTarget.exists() || selectorTarget.exists()) {
            return@withGroupLock M3CanonicalActivationResult.Refused(
                M3CanonicalActivationSelectorRefusal.DURABILITY_FAILURE,
            )
        }
        val unit = try { budget.allocationUnitBytes(parent) } catch (_: Exception) {
            return@withGroupLock M3CanonicalActivationResult.Refused(M3CanonicalActivationSelectorRefusal.DURABILITY_FAILURE)
        }
        val attempt = ActivationAttempt(
            group.hash.hex(),
            rootTarget.name,
            currentTarget?.name,
            slotTarget.name,
            rootTarget.exists(),
            currentTarget?.exists() == true,
        )
        val attemptBytes = attempt.bytes()
        val attemptId = digest(attemptBytes)
        val attemptTarget = attemptFile(parent, group, attemptId)
        val commitBytes = listOf(
            rootBytes.size.toLong(), ActivationSlot.BYTES.toLong(), ActivationSelector.BYTES.toLong(),
            currentLength(plan.current),
        ).fold(0L) { total, bytes -> Math.addExact(total, round(bytes, unit)) }
        val maximum = listOf(attemptBytes.size.toLong(), 1L, 1L, 1L).fold(commitBytes) { total, bytes ->
            Math.addExact(total, round(bytes, unit))
        }
        var reservation: Any? = null
        var switched = false
        try {
            inject(fault, M3CanonicalActivationFault.BEFORE_RESERVATION)
            reservation = budget.reserveActivationAttempt(
                group.hash.hex(),
                attemptId.toByteArray().hex(),
                if (rootTarget.exists()) budget.allocatedBytes(rootTarget) else 0L,
                0L,
                0L,
                commitBytes,
                maximum,
            )
                ?: return@withGroupLock M3CanonicalActivationResult.Refused(M3CanonicalActivationSelectorRefusal.QUOTA_REFUSED)
            inject(fault, M3CanonicalActivationFault.AFTER_RESERVATION)
            writeAttempt(attemptTarget, attemptBytes)
            sync(parent, M3CanonicalActivationSyncStage.ATTEMPT)
            inject(fault, M3CanonicalActivationFault.AFTER_ATTEMPT_SYNC)
            if (currentTarget != null) {
                inject(fault, M3CanonicalActivationFault.BEFORE_CURRENT_WRITE)
                writeCurrent(currentTarget, plan.current as M3CanonicalActivationCurrent.Receipt, fault)
                inject(fault, M3CanonicalActivationFault.AFTER_CURRENT_SYNC)
            }
            inject(fault, M3CanonicalActivationFault.BEFORE_ROOT_WRITE)
            writeImmutable(rootTarget, rootBytes, fault == M3CanonicalActivationFault.DURING_ROOT_WRITE)
            inject(fault, M3CanonicalActivationFault.AFTER_ROOT_SYNC)
            val slot = ActivationSlot(1L, rootHash)
            inject(fault, M3CanonicalActivationFault.BEFORE_SLOT_WRITE)
            atomicReplace(slotTarget, slot.bytes(), fault == M3CanonicalActivationFault.DURING_SLOT_WRITE)
            inject(fault, M3CanonicalActivationFault.AFTER_SLOT_SYNC)
            // Persist every prerequisite target name before the selector is allowed to name it.
            // Android/Linux opens and fsyncs the directory. The Windows host adapter cannot open
            // directory descriptors, and reports that limitation through the ordered test receipt.
            inject(fault, M3CanonicalActivationFault.BEFORE_PREREQUISITE_PARENT_SYNC)
            sync(parent, M3CanonicalActivationSyncStage.PREREQUISITES)
            inject(fault, M3CanonicalActivationFault.AFTER_PREREQUISITE_PARENT_SYNC)
            inject(fault, M3CanonicalActivationFault.PROCESS_CRASH_AFTER_PREREQUISITE_SYNC)
            inject(fault, M3CanonicalActivationFault.BEFORE_SELECTOR_SWITCH)
            atomicReplace(
                selectorTarget,
                ActivationSelector(0, 1L, rootHash).bytes(),
                fault == M3CanonicalActivationFault.DURING_SELECTOR_WRITE,
            )
            switched = true
            inject(fault, M3CanonicalActivationFault.AFTER_SELECTOR_SWITCH)
            inject(fault, M3CanonicalActivationFault.BEFORE_PARENT_SYNC)
            sync(parent, M3CanonicalActivationSyncStage.SELECTOR)
            inject(fault, M3CanonicalActivationFault.AFTER_PARENT_SYNC)
            val actual = selectedActivationBytes(
                rootTarget, currentTarget, slotTarget, selectorTarget, budget,
            )
            require(actual == commitBytes)
            inject(fault, M3CanonicalActivationFault.BEFORE_BUDGET_COMMIT)
            budget.commit(requireNotNull(reservation), actual)
            reservation = null
            inject(fault, M3CanonicalActivationFault.AFTER_BUDGET_COMMIT)
            require(attemptTarget.delete())
            sync(parent, M3CanonicalActivationSyncStage.RECOVERY)
            inject(fault, M3CanonicalActivationFault.BEFORE_CLEANUP)
            cleanupLegacyCandidates(parent, group)
            inject(fault, M3CanonicalActivationFault.AFTER_CLEANUP)
            reopenLocked(group, parent, budget)
        } catch (_: Exception) {
            if (switched) M3CanonicalActivationResult.UnknownAfterSwitch
            else M3CanonicalActivationResult.Refused(M3CanonicalActivationSelectorRefusal.DURABILITY_FAILURE)
        } finally {
            if (!fault.isProcessCrash()) reservation?.let { token ->
                try {
                    if (switched) {
                        budget.commit(token, selectedActivationBytes(
                            rootTarget, currentTarget, slotTarget, selectorTarget, budget,
                        ))
                    } else {
                        cleanupAttempt(parent, group, attempt, attemptTarget, emptySet())
                        sync(parent, M3CanonicalActivationSyncStage.RECOVERY)
                        budget.release(token)
                    }
                } catch (_: Exception) { }
            }
            if (!switched && !fault.isProcessCrash()) cleanupLegacyCandidates(parent, group)
        }
    }

    fun reopen(
        group: M3SurfaceGroup,
        parent: File,
        budget: M3CanonicalStorageBudget,
    ): M3CanonicalActivationResult = withGroupLock(parent, group) {
        if (!reconcileReclaimsLocked(group, parent, budget) || !reconcileAttemptsLocked(group, parent, budget) || !reconcileAcknowledgementsLocked(group, parent, budget) ||
            !reconcileAdjacentTransactionsLocked(group, parent, budget)) {
            M3CanonicalActivationResult.Refused(M3CanonicalActivationSelectorRefusal.DURABILITY_FAILURE)
        } else {
            reopenLocked(group, parent, budget)
        }
    }

    /**
     * Publishes an ACK-only activation root.  The semantic cut is copied
     * byte-for-byte; only the retained-current ownership changes.  The second
     * slot means a pre-selector crash still has a complete old root to reopen.
     */
    fun acknowledge(
        group: M3SurfaceGroup,
        parent: File,
        budget: M3CanonicalStorageBudget,
        acknowledgement: M3CanonicalAcknowledgement,
        fault: M3CanonicalAcknowledgementFault? = null,
    ): M3CanonicalAcknowledgementResult = withGroupLock(parent, group) {
        if (!reconcileReclaimsLocked(group, parent, budget) || !reconcileAttemptsLocked(group, parent, budget) || !reconcileAcknowledgementsLocked(group, parent, budget) ||
            !reconcileAdjacentTransactionsLocked(group, parent, budget)) {
            return@withGroupLock M3CanonicalAcknowledgementResult.NoOp(M3CanonicalAcknowledgementNoOp.DURABILITY_FAILURE)
        }
        val selectorTarget = selectorFile(parent, group)
        val selector = ActivationSelector.read(selectorTarget)
            ?: return@withGroupLock M3CanonicalAcknowledgementResult.NoOp(M3CanonicalAcknowledgementNoOp.NO_CURRENT)
        val oldRoot = ActivationRoot.read(rootFile(parent, group, selector.rootHash), selector.rootHash)
            ?: return@withGroupLock M3CanonicalAcknowledgementResult.NoOp(M3CanonicalAcknowledgementNoOp.DURABILITY_FAILURE)
        val expected = oldRoot.current
            ?: return@withGroupLock M3CanonicalAcknowledgementResult.NoOp(M3CanonicalAcknowledgementNoOp.NO_CURRENT)
        if (oldRoot.cut.geometryRevision != acknowledgement.geometryRevision ||
            oldRoot.cut.lineageRevision != acknowledgement.lineageRevision
        ) return@withGroupLock M3CanonicalAcknowledgementResult.NoOp(M3CanonicalAcknowledgementNoOp.STALE_REVISION)
        if (expected.identity.commandHash != acknowledgement.commandHash)
            return@withGroupLock M3CanonicalAcknowledgementResult.NoOp(M3CanonicalAcknowledgementNoOp.COMMAND_MISMATCH)
        if (expected is CurrentRecord.Acknowledged) {
            val rawReopened = reopenLocked(group, parent, budget)
            val reopened = rawReopened as? M3CanonicalActivationResult.Active
                ?: error("ack reopen failed: $rawReopened")
            return@withGroupLock M3CanonicalAcknowledgementResult.Idempotent(reopened.state)
        }
        val oldCurrent = currentFile(parent, group, expected)
        if (!verifyCurrent(oldCurrent, expected.identity))
            return@withGroupLock M3CanonicalAcknowledgementResult.NoOp(M3CanonicalAcknowledgementNoOp.DURABILITY_FAILURE)

        val nextRoot = ActivationRoot(oldRoot.cut, CurrentRecord.Acknowledged(expected.identity))
        val nextRootBytes = nextRoot.bytes()
        val nextRootHash = digest(nextRootBytes)
        val nextRootTarget = rootFile(parent, group, nextRootHash)
        val nextSlot = 1 - selector.slot
        val nextSlotTarget = slotFile(parent, group, nextSlot)
        val obsoleteRootName = obsoleteRootName(parent, group, nextSlot, selector.rootHash, nextRootHash)
        val rootBefore = nextRootTarget.takeIf(File::exists)?.let(budget::allocatedBytes) ?: 0L
        val slotBefore = nextSlotTarget.takeIf(File::exists)?.let(budget::allocatedBytes) ?: 0L
        val selectorBefore = selectorTarget.takeIf(File::exists)?.let(budget::allocatedBytes) ?: 0L
        val unit = budget.allocationUnitBytes(parent)
        val commitBytes = Math.max(0L, round(nextRootBytes.size.toLong(), unit) - rootBefore) +
            Math.max(0L, round(ActivationSlot.BYTES.toLong(), unit) - slotBefore) +
            Math.max(0L, round(ActivationSelector.BYTES.toLong(), unit) - selectorBefore)
        val reservationMaximum = listOf(nextRootBytes.size.toLong(), ActivationSlot.BYTES.toLong(), ActivationSelector.BYTES.toLong(), 1L)
            .fold(0L) { total, bytes -> Math.addExact(total, round(bytes, unit)) }
        val attempt = AcknowledgementAttempt(
            group.hash.hex(), oldCurrent.name, nextRootTarget.name, nextSlotTarget.name,
            selectorTarget.name, obsoleteRootName, nextRootTarget.exists(), nextSlotTarget.exists(),
        )
        val attemptTarget = acknowledgementAttemptFile(parent, group, digest(attempt.bytes()))
        var switched = false
        var token: Any? = null
        var reservation: M3CanonicalPointerReservation? = null
        try {
            inject(fault, M3CanonicalAcknowledgementFault.BEFORE_ATTEMPT)
            token = budget.reserveActivationAttempt(
                acknowledgementBudgetGroup(group), nextRootHash.toByteArray().hex(), rootBefore,
                slotBefore, selectorBefore, commitBytes, reservationMaximum,
            ) ?: return@withGroupLock M3CanonicalAcknowledgementResult.NoOp(M3CanonicalAcknowledgementNoOp.DURABILITY_FAILURE)
            reservation = M3CanonicalPointerReservation(
                requireNotNull(token), nextRootHash.toByteArray().hex(), nextSlot,
                rootBefore, slotBefore, selectorBefore, commitBytes,
            )
            writeAcknowledgementAttempt(attemptTarget, attempt.bytes())
            sync(parent, M3CanonicalActivationSyncStage.ACK_ATTEMPT)
            inject(fault, M3CanonicalAcknowledgementFault.AFTER_ATTEMPT_SYNC)
            inject(fault, M3CanonicalAcknowledgementFault.BEFORE_ROOT_WRITE)
            writeImmutable(nextRootTarget, nextRootBytes, false)
            sync(parent, M3CanonicalActivationSyncStage.ACK_ROOT)
            inject(fault, M3CanonicalAcknowledgementFault.AFTER_ROOT_SYNC)
            inject(fault, M3CanonicalAcknowledgementFault.PROCESS_CRASH_AFTER_ROOT_SYNC)
            inject(fault, M3CanonicalAcknowledgementFault.BEFORE_SLOT_WRITE)
            atomicReplace(nextSlotTarget, ActivationSlot(selector.revision + 1, nextRootHash).bytes(), false)
            sync(parent, M3CanonicalActivationSyncStage.ACK_SLOT)
            inject(fault, M3CanonicalAcknowledgementFault.AFTER_SLOT_SYNC)
            inject(fault, M3CanonicalAcknowledgementFault.BEFORE_SELECTOR_SWITCH)
            atomicReplace(selectorTarget, ActivationSelector(nextSlot, selector.revision + 1, nextRootHash).bytes(), false)
            switched = true
            inject(fault, M3CanonicalAcknowledgementFault.AFTER_SELECTOR_SWITCH)
            inject(fault, M3CanonicalAcknowledgementFault.PROCESS_CRASH_AFTER_SELECTOR_SWITCH)
            inject(fault, M3CanonicalAcknowledgementFault.BEFORE_PARENT_SYNC)
            sync(parent, M3CanonicalActivationSyncStage.ACK_SELECTOR)
            budget.commitActivationAttempt(requireNotNull(reservation)); token = null; reservation = null
            inject(fault, M3CanonicalAcknowledgementFault.AFTER_PARENT_SYNC)
            // The new root is authoritative before the payload is touched.
            inject(fault, M3CanonicalAcknowledgementFault.BEFORE_CLEANUP)
            reclaimArtifacts(
                group, parent, budget, "ack-${nextRootHash.toByteArray().hex()}",
                listOfNotNull(oldCurrent, obsoleteRootName?.let { File(parent, it) }),
            ) { stage -> injectAcknowledgementReclaim(fault, stage) }
            require(!attemptTarget.exists() || attemptTarget.delete())
            sync(parent, M3CanonicalActivationSyncStage.RECOVERY)
            inject(fault, M3CanonicalAcknowledgementFault.AFTER_CLEANUP)
            val reopened = reopenLocked(group, parent, budget) as? M3CanonicalActivationResult.Active
                ?: return@withGroupLock M3CanonicalAcknowledgementResult.NoOp(M3CanonicalAcknowledgementNoOp.DURABILITY_FAILURE)
            M3CanonicalAcknowledgementResult.Acknowledged(reopened.state)
        } catch (failure: Exception) {
            if (!fault.isAcknowledgementProcessCrash() && !switched) {
                cleanupAcknowledgementAttempt(parent, group, attempt, attemptTarget, emptySet())
                sync(parent, M3CanonicalActivationSyncStage.RECOVERY)
                reservation?.let(budget::releaseActivationAttempt); token = null; reservation = null
            } else if (!fault.isAcknowledgementProcessCrash() && switched) {
                reservation?.let(budget::commitActivationAttempt); token = null; reservation = null
            }
            M3CanonicalAcknowledgementResult.NoOp(M3CanonicalAcknowledgementNoOp.DURABILITY_FAILURE)
        }
    }

    /** Owner-issued one-shot admission from one durable ACK to one exact #117 successor. */
    fun commitAdjacent(
        group: M3SurfaceGroup,
        parent: File,
        budget: M3CanonicalStorageBudget,
        plan: M3PreparedCanonicalMutation,
        faults: M3CanonicalCommitFaults = M3CanonicalCommitFaults(),
    ): M3CanonicalAdjacentCommitResult = withGroupLock(parent, group) {
        if (!reconcileReclaimsLocked(group, parent, budget) || !reconcileAttemptsLocked(group, parent, budget) || !reconcileAcknowledgementsLocked(group, parent, budget) ||
            !reconcileAdjacentTransactionsLocked(group, parent, budget))
            return@withGroupLock M3CanonicalAdjacentCommitResult.Refused(M3CanonicalAdjacentCommitRefusal.DURABILITY_FAILURE)
        val selector = ActivationSelector.read(selectorFile(parent, group))
            ?: return@withGroupLock M3CanonicalAdjacentCommitResult.Refused(M3CanonicalAdjacentCommitRefusal.NO_ACTIVE_AUTHORITY)
        val root = ActivationRoot.read(rootFile(parent, group, selector.rootHash), selector.rootHash)
            ?: return@withGroupLock M3CanonicalAdjacentCommitResult.Refused(M3CanonicalAdjacentCommitRefusal.DURABILITY_FAILURE)
        val acknowledged = when (val current = root.current) {
            null -> null
            is CurrentRecord.Acknowledged -> current.identity
            is CurrentRecord.Unacknowledged ->
                return@withGroupLock M3CanonicalAdjacentCommitResult.Refused(M3CanonicalAdjacentCommitRefusal.CURRENT_UNACKNOWLEDGED)
        }
        if (plan.sourceCut != root.cut)
            return@withGroupLock M3CanonicalAdjacentCommitResult.Refused(M3CanonicalAdjacentCommitRefusal.STALE_CUT)
        val attempt = AdjacentTransaction(
            group.hash.hex(), selector.rootHash, plan.sourceCut.rootHash, plan.commandHash,
            plan.commandFingerprint, acknowledged,
            obsoleteRootName(parent, group, 1 - selector.slot, selector.rootHash, null),
        )
        val target = adjacentAttemptFile(parent, group)
        try {
            if (!target.exists()) writeAcknowledgementAttempt(target, attempt.bytes())
            else require(AdjacentTransaction.read(target, group.hash.hex()) == attempt)
            sync(parent, M3CanonicalActivationSyncStage.ADJACENT_INTENT)
            val generationZero = (M3CompactCanonicalStore.openV6(group, parent, budget) as? M3CompactCanonicalOpenResult.Opened)?.store
                ?: return@withGroupLock M3CanonicalAdjacentCommitResult.Refused(M3CanonicalAdjacentCommitRefusal.DURABILITY_FAILURE)
            generationZero.use { base ->
                val store = M3CanonicalCommitStore.open(parent, budget)
                    ?: return@withGroupLock M3CanonicalAdjacentCommitResult.Refused(M3CanonicalAdjacentCommitRefusal.DURABILITY_FAILURE)
                store.use {
                    when (val committed = store.commit(plan, base, faults, acknowledged?.toIntentReceipt())) {
                        is M3CanonicalCommitResult.Refused -> {
                            require(target.delete()); sync(parent, M3CanonicalActivationSyncStage.RECOVERY)
                            return@withGroupLock M3CanonicalAdjacentCommitResult.Refused(
                                M3CanonicalAdjacentCommitRefusal.COMMIT_REFUSED, committed,
                            )
                        }
                        is M3CanonicalCommitResult.Committed,
                        is M3CanonicalCommitResult.UnknownAfterSwitch -> Unit
                    }
                }
            }
            if (!reconcileAdjacentTransactionsLocked(group, parent, budget, faults.adjacent))
                return@withGroupLock M3CanonicalAdjacentCommitResult.Refused(M3CanonicalAdjacentCommitRefusal.DURABILITY_FAILURE)
            val active = reopenLocked(group, parent, budget) as? M3CanonicalActivationResult.Active
                ?: return@withGroupLock M3CanonicalAdjacentCommitResult.Refused(M3CanonicalAdjacentCommitRefusal.DURABILITY_FAILURE)
            M3CanonicalAdjacentCommitResult.Committed(active.state)
        } catch (_: Exception) {
            M3CanonicalAdjacentCommitResult.Refused(M3CanonicalAdjacentCommitRefusal.DURABILITY_FAILURE)
        }
    }

    /** Shared selector/restore lock used by every directory-backed ownership opener. */
    internal fun <T> withGroupLock(parent: File, group: M3SurfaceGroup, block: () -> T): T {
        val key = parent.absoluteFile.toPath().normalize().toString() + ':' + group.value
        val lock = synchronized(locks) { locks.getOrPut(key) { Any() } }
        return synchronized(lock, block)
    }

    /** Registers a live writable legacy owner. Must be called while [withGroupLock] is held. */
    internal fun acquireLegacyLease(parent: File, group: M3SurfaceGroup): () -> Unit {
        val key = lockKey(parent, group)
        check(!selectorFile(parent, group).exists())
        synchronized(legacyLeases) { legacyLeases[key] = (legacyLeases[key] ?: 0) + 1 }
        var released = false
        return {
            withGroupLock(parent, group) {
                if (!released) {
                    released = true
                    synchronized(legacyLeases) {
                        val remaining = requireNotNull(legacyLeases[key]) - 1
                        if (remaining == 0) legacyLeases.remove(key) else legacyLeases[key] = remaining
                    }
                }
            }
        }
    }

    private fun legacyLeaseCount(parent: File, group: M3SurfaceGroup) =
        synchronized(legacyLeases) { legacyLeases[lockKey(parent, group)] ?: 0 }

    private fun lockKey(parent: File, group: M3SurfaceGroup) =
        parent.absoluteFile.toPath().normalize().toString() + ':' + group.value

    /** A legacy-only caller must never step around a durable v6 authority. */
    fun hasDurableSelector(group: M3SurfaceGroup, parent: File): Boolean =
        selectorFile(parent, group).exists() || parent.listFiles().orEmpty().any {
            it.name.startsWith("m3-activation-${group.hash.hex()}-attempt-") && it.name.endsWith(".attempt")
        }

    /** Finishes exact-byte deletion journals before any authority is exposed. */
    private fun reconcileReclaimsLocked(
        group: M3SurfaceGroup,
        parent: File,
        budget: M3CanonicalStorageBudget,
    ): Boolean = try {
        val prefix = "m3-activation-${group.hash.hex()}-reclaim-"
        parent.listFiles().orEmpty()
            .filter { it.name.startsWith(prefix) && it.name.endsWith(".attempt") }
            .sortedBy(File::getName)
            .forEach { target ->
                val id = target.name.removePrefix(prefix).removeSuffix(".attempt")
                val attempt = ReclaimAttempt.read(target, id, group.hash.hex()) ?: return false
                executeReclaim(parent, budget, target, attempt) {}
            }
        true
    } catch (_: Exception) { false }

    private fun reclaimArtifacts(
        group: M3SurfaceGroup,
        parent: File,
        budget: M3CanonicalStorageBudget,
        purpose: String,
        candidates: List<File>,
        cut: (ReclaimStage) -> Unit = {},
    ) {
        val id = digest("${group.hash.hex()}:$purpose".encodeToByteArray()).toByteArray().hex()
        val target = reclaimAttemptFile(parent, group, id)
        val attempt = if (target.exists()) {
            ReclaimAttempt.read(target, id, group.hash.hex()) ?: error("corrupt activation reclaim")
        } else {
            val entries = candidates.distinctBy { it.absoluteFile.toPath().normalize() }
                .filter(File::exists)
                .map { file ->
                    require(file.isFile && file.parentFile != null)
                    val relative = parent.toPath().relativize(file.toPath()).toString().replace('\\', '/')
                    require(relative.isSafeRelativeActivationPath())
                    ReclaimEntry(relative, budget.allocatedBytes(file).also { require(it > 0L) })
                }
            if (entries.isEmpty()) return
            ReclaimAttempt(group.hash.hex(), id, false, entries).also { value ->
                writeReclaimAttempt(target, value.bytes())
                sync(parent, M3CanonicalActivationSyncStage.RECLAIM_INTENT)
                cut(ReclaimStage.AFTER_INTENT_SYNC)
            }
        }
        executeReclaim(parent, budget, target, attempt, cut)
    }

    private fun executeReclaim(
        parent: File,
        budget: M3CanonicalStorageBudget,
        target: File,
        attempt: ReclaimAttempt,
        cut: (ReclaimStage) -> Unit,
    ) {
        val total = attempt.entries.fold(0L) { sum, entry -> Math.addExact(sum, entry.bytes) }
        if (!attempt.complete) {
            attempt.entries.forEach { entry ->
                require(entry.path.isSafeRelativeActivationPath())
                val file = File(parent, entry.path)
                if (file.exists()) {
                    require(file.isFile && budget.allocatedBytes(file) == entry.bytes && file.delete())
                }
            }
            sync(parent, M3CanonicalActivationSyncStage.RECLAIM_DELETE)
            cut(ReclaimStage.AFTER_DELETE_SYNC)
            budget.reclaimCommittedBytesOnce(attempt.reclaimId, total)
            cut(ReclaimStage.AFTER_LEDGER)
            atomicReplace(target, attempt.copy(complete = true).bytes(), false)
            sync(parent, M3CanonicalActivationSyncStage.RECLAIM_COMPLETE)
        }
        budget.forgetCommittedReclaim(attempt.reclaimId)
        require(!target.exists() || target.delete())
        sync(parent, M3CanonicalActivationSyncStage.RECOVERY)
    }

    private fun obsoleteRootName(
        parent: File,
        group: M3SurfaceGroup,
        overwrittenSlot: Int,
        activeRoot: M3CanonicalReceiptBytes,
        nextRoot: M3CanonicalReceiptBytes?,
    ): String? {
        val rootHash = ActivationSlot.read(slotFile(parent, group, overwrittenSlot))?.rootHash ?: return null
        if (rootHash == activeRoot || rootHash == nextRoot) return null
        val target = rootFile(parent, group, rootHash)
        return target.name.takeIf { ActivationRoot.read(target, rootHash) != null }
    }

    /** Reconciles durable attempts before authority selection; release always follows deletion fsync. */
    private fun reconcileAttemptsLocked(
        group: M3SurfaceGroup,
        parent: File,
        budget: M3CanonicalStorageBudget,
    ): Boolean = try {
        val prefix = "m3-activation-${group.hash.hex()}-attempt-"
        val reservations = budget.activationAttempts(group.hash.hex())
        val liveIds = reservations.mapTo(hashSetOf()) { it.publicationId }
        for (reservation in reservations) {
            val target = attemptFile(parent, group, M3CanonicalReceiptBytes(hex(reservation.publicationId)))
            if (!target.exists()) {
                // Reservation landed before its manifest; therefore no named activation artifact
                // could have been written by this attempt.
                budget.releaseActivationAttempt(reservation)
                continue
            }
            val attempt = ActivationAttempt.read(target, reservation.publicationId, group.hash.hex())
                ?: return false
            val reachable = selectedReachableNames(parent, group) ?: return false
            if (attempt.rootName in reachable) {
                budget.commitActivationAttempt(reservation)
                require(target.delete())
                sync(parent, M3CanonicalActivationSyncStage.RECOVERY)
            } else {
                cleanupAttempt(parent, group, attempt, target, reachable)
                sync(parent, M3CanonicalActivationSyncStage.RECOVERY)
                budget.releaseActivationAttempt(reservation)
            }
        }
        // A crash after commit but before manifest deletion leaves no reservation. Its manifest is
        // still sufficient to preserve reachable authority and remove itself idempotently.
        parent.listFiles().orEmpty()
            .filter { it.name.startsWith(prefix) && it.name.endsWith(".attempt") }
            .forEach { target ->
                val id = target.name.removePrefix(prefix).removeSuffix(".attempt")
                if (id !in liveIds) {
                    val attempt = ActivationAttempt.read(target, id, group.hash.hex()) ?: return false
                    cleanupAttempt(parent, group, attempt, target, selectedReachableNames(parent, group) ?: return false)
                    sync(parent, M3CanonicalActivationSyncStage.RECOVERY)
                }
            }
        true
    } catch (_: Exception) {
        false
    }

    /** Replays the ACK's tiny intent journal before exposing either root. */
    private fun reconcileAcknowledgementsLocked(
        group: M3SurfaceGroup,
        parent: File,
        budget: M3CanonicalStorageBudget,
    ): Boolean = try {
        val prefix = "m3-activation-${group.hash.hex()}-ack-"
        val reservations = budget.activationAttempts(acknowledgementBudgetGroup(group)).associateBy { it.publicationId }.toMutableMap()
        parent.listFiles().orEmpty().filter { it.name.startsWith(prefix) && it.name.endsWith(".attempt") }
            .forEach { target ->
                val id = target.name.removePrefix(prefix).removeSuffix(".attempt")
                val attempt = AcknowledgementAttempt.read(target, id, group.hash.hex()) ?: return false
                val selected = ActivationSelector.read(selectorFile(parent, group))
                val selectedRoot = selected?.let { rootFile(parent, group, it.rootHash).name }
                val nextHash = attempt.nextRootName.removePrefix("m3-activation-${group.hash.hex()}-root-").removeSuffix(".root")
                val reservation = reservations.remove(nextHash)
                if (selectedRoot == attempt.nextRootName) {
                    reservation?.let { value ->
                        val actual = incrementalBytes(budget, File(parent, attempt.nextRootName), value.rootBeforeBytes) +
                            incrementalBytes(budget, File(parent, attempt.nextSlotName), value.slotBeforeBytes) +
                            incrementalBytes(budget, File(parent, attempt.selectorName), value.selectorBeforeBytes)
                        require(actual == value.commitBytes)
                        budget.commitActivationAttempt(value)
                    }
                    reclaimArtifacts(
                        group, parent, budget, "ack-$nextHash",
                        listOfNotNull(File(parent, attempt.oldCurrentName), attempt.obsoleteRootName?.let { File(parent, it) }),
                    )
                    require(target.delete())
                    sync(parent, M3CanonicalActivationSyncStage.RECOVERY)
                } else {
                    cleanupAcknowledgementAttempt(parent, group, attempt, target, emptySet())
                    reservation?.let(budget::releaseActivationAttempt)
                }
                sync(parent, M3CanonicalActivationSyncStage.RECOVERY)
            }
        reservations.values.forEach(budget::releaseActivationAttempt)
        true
    } catch (_: Exception) { false }

    /** Forward-repairs a crossed #117 selector before activation authority is exposed. */
    private fun reconcileAdjacentTransactionsLocked(
        group: M3SurfaceGroup,
        parent: File,
        budget: M3CanonicalStorageBudget,
        fault: M3CanonicalAdjacentFault? = null,
    ): Boolean = try {
        val target = adjacentAttemptFile(parent, group)
        if (!target.exists()) return true
        val attempt = AdjacentTransaction.read(target, group.hash.hex()) ?: return false
        val activationSelector = ActivationSelector.read(selectorFile(parent, group)) ?: return false
        val activationRoot = ActivationRoot.read(rootFile(parent, group, activationSelector.rootHash), activationSelector.rootHash) ?: return false
        if (activationSelector.rootHash != attempt.activationRootHash) {
            // The exact successor is already authoritative; only durable cleanup remains.
            val retained = activationRoot.current as? CurrentRecord.Unacknowledged ?: return false
            val reservation = budget.activationAttempts(adjacentBudgetGroup(group))
                .singleOrNull { it.publicationId == activationSelector.rootHash.toByteArray().hex() }
            reservation?.let { value ->
                val currentTarget = currentFile(parent, group, retained)
                val actual = Math.max(0L,
                    budget.allocatedBytes(currentTarget) + budget.allocatedBytes(rootFile(parent, group, activationSelector.rootHash)) - value.rootBeforeBytes,
                ) + incrementalBytes(budget, slotFile(parent, group, activationSelector.slot), value.slotBeforeBytes) +
                    incrementalBytes(budget, selectorFile(parent, group), value.selectorBeforeBytes)
                require(actual == value.commitBytes)
                sync(parent, M3CanonicalActivationSyncStage.RECOVERY)
                budget.commitActivationAttempt(value)
            }
            val generationZero = (M3CompactCanonicalStore.openV6(group, parent, budget) as? M3CompactCanonicalOpenResult.Opened)?.store ?: return false
            generationZero.use { base ->
                val store = M3CanonicalCommitStore.open(parent, budget) ?: return false
                store.use {
                    val selected = store.reopen(base, retained.identity.toIntentReceipt()) as? M3CanonicalReopenResult.Selected ?: return false
                    selected.commit.use { commit ->
                        val latest = commit.roots.lastOrNull() ?: return false
                        val duplicate = File(File(parent, latest.generationDirectory), M3CanonicalCowGeneration.CURRENT_UNACKED_FILE)
                        if (duplicate.exists()) require(verifyCurrent(duplicate, retained.identity))
                        reclaimArtifacts(
                            group, parent, budget,
                            "adjacent-${activationSelector.rootHash.toByteArray().hex()}",
                            listOfNotNull(duplicate, attempt.obsoleteRootName?.let { File(parent, it) }),
                        )
                    }
                }
            }
            require(target.delete()); sync(parent, M3CanonicalActivationSyncStage.RECOVERY); return true
        }
        require(
            if (attempt.acknowledged == null) activationRoot.current == null
            else activationRoot.current is CurrentRecord.Acknowledged && activationRoot.current.identity == attempt.acknowledged
        )
        val generationZero = (M3CompactCanonicalStore.openV6(group, parent, budget) as? M3CompactCanonicalOpenResult.Opened)?.store ?: return false
        generationZero.use { base ->
            val store = M3CanonicalCommitStore.open(parent, budget) ?: return false
            store.use {
                when (val reopened = store.reopen(base, attempt.acknowledged?.toIntentReceipt())) {
                    is M3CanonicalReopenResult.GenerationZero -> {
                        require(target.delete()); sync(parent, M3CanonicalActivationSyncStage.RECOVERY); return true
                    }
                    is M3CanonicalReopenResult.Refused -> return false
                    is M3CanonicalReopenResult.Selected -> reopened.commit.use { commit ->
                        val published = commit.roots.lastOrNull() ?: return false
                        require(published.baseRootHash == attempt.sourceRootHash &&
                            published.commandHash == attempt.commandHash &&
                            published.commandFingerprint == attempt.commandFingerprint &&
                            commit.view.cut.rootHash == published.targetRootHash)
                        val identity = M3CanonicalCurrentIdentity(
                            published.commandHash, published.commandFingerprint,
                            published.current.length, published.current.hash,
                        )
                        require(identity.canonicalLength <= M3CanonicalActivationResources.MAX_CURRENT_BYTES)
                        val sourceFile = File(File(parent, published.generationDirectory), M3CanonicalCowGeneration.CURRENT_UNACKED_FILE)
                        require(sourceFile.exists())
                        publishAdjacentRoot(group, parent, budget, activationSelector, commit.view.cut, identity, sourceFile, fault)
                    }
                }
            }
        }
        require(target.delete()); sync(parent, M3CanonicalActivationSyncStage.RECOVERY)
        true
    } catch (_: Exception) { false }

    private fun selectedReachableNames(parent: File, group: M3SurfaceGroup): Set<String>? {
        val selectorTarget = selectorFile(parent, group)
        if (!selectorTarget.exists()) return emptySet()
        val selector = ActivationSelector.read(selectorTarget) ?: return null
        val slotTarget = slotFile(parent, group, selector.slot)
        val slot = ActivationSlot.read(slotTarget)
            ?.takeIf { it.revision == selector.revision && it.rootHash == selector.rootHash }
            ?: return null
        val rootTarget = rootFile(parent, group, selector.rootHash)
        val root = ActivationRoot.read(rootTarget, selector.rootHash) ?: return null
        val names = linkedSetOf(selectorTarget.name, slotTarget.name, rootTarget.name)
        (root.current as? CurrentRecord.Unacknowledged)?.let {
            val current = currentFile(parent, group, it)
            if (!verifyCurrent(current, it.identity)) return null
            names += current.name
        }
        return names
    }

    private fun cleanupAttempt(
        parent: File,
        group: M3SurfaceGroup,
        attempt: ActivationAttempt,
        attemptTarget: File,
        reachable: Set<String>,
    ) {
        require(attempt.groupHash == group.hash.hex())
        val created = buildList {
            if (attempt.rootCreated) add(attempt.rootName)
            if (attempt.currentCreated) attempt.currentName?.let(::add)
            add(attempt.slotName)
        }
        val prefix = "m3-activation-${group.hash.hex()}"
        created.filter { it !in reachable }.forEach { name ->
            require(name.startsWith(prefix) && '/' !in name && '\\' !in name)
            val target = File(parent, name)
            require(!target.exists() || target.delete())
            val part = File(parent, ".$name.part")
            require(!part.exists() || part.delete())
        }
        val selectorPart = File(parent, ".${selectorFile(parent, group).name}.part")
        require(!selectorPart.exists() || selectorPart.delete())
        require(!attemptTarget.exists() || attemptTarget.delete())
        cleanupLegacyCandidates(parent, group)
    }

    private fun cleanupAcknowledgementAttempt(
        parent: File,
        group: M3SurfaceGroup,
        attempt: AcknowledgementAttempt,
        attemptTarget: File,
        reachable: Set<String>,
    ) {
        require(attempt.groupHash == group.hash.hex())
        listOfNotNull(
            attempt.nextRootName.takeIf { attempt.rootCreated },
            attempt.nextSlotName.takeIf { attempt.slotCreated },
        ).filter { it !in reachable }.forEach { name ->
            require(name.startsWith("m3-activation-${group.hash.hex()}") && '/' !in name && '\\' !in name)
            val target = File(parent, name)
            require(!target.exists() || target.delete())
            val part = File(parent, ".$name.part")
            require(!part.exists() || part.delete())
        }
        require(!attemptTarget.exists() || attemptTarget.delete())
    }

    private fun reopenLocked(
        group: M3SurfaceGroup,
        parent: File,
        budget: M3CanonicalStorageBudget,
    ): M3CanonicalActivationResult {
        val selectorFile = selectorFile(parent, group)
        if (!selectorFile.exists()) return M3CanonicalActivationResult.Legacy
        val selector = ActivationSelector.read(selectorFile)
            ?: return M3CanonicalActivationResult.Refused(M3CanonicalActivationSelectorRefusal.CORRUPT_SELECTOR)
        val slot = ActivationSlot.read(slotFile(parent, group, selector.slot))
            ?.takeIf { it.revision == selector.revision && it.rootHash == selector.rootHash }
            ?: return M3CanonicalActivationResult.Refused(M3CanonicalActivationSelectorRefusal.FORKED_SELECTOR)
        val root = ActivationRoot.read(rootFile(parent, group, selector.rootHash), selector.rootHash)
            ?: return M3CanonicalActivationResult.Refused(M3CanonicalActivationSelectorRefusal.CORRUPT_SELECTOR)
        if (root.cut.group != group || root.cut.profile != M3CompactCanonicalStore.PROFILE) {
            return M3CanonicalActivationResult.Refused(M3CanonicalActivationSelectorRefusal.FORKED_SELECTOR)
        }
        val opened = M3CompactCanonicalStore.openV6(group, parent, budget)
            as? M3CompactCanonicalOpenResult.Opened
            ?: return M3CanonicalActivationResult.Refused(M3CanonicalActivationSelectorRefusal.CORRUPT_V6)
        val v6Cut = opened.store.use { base ->
            val store = M3CanonicalCommitStore.open(parent, budget)
                ?: return M3CanonicalActivationResult.Refused(M3CanonicalActivationSelectorRefusal.CORRUPT_V6)
            store.use {
                // Activation owns the exact retained payload after supersession, so the
                // duplicate embedded COW payload may already have been reclaimed.
                val retained = root.current?.identity?.toIntentReceipt()
                when (val selected = store.reopen(base, retained)) {
                    is M3CanonicalReopenResult.GenerationZero -> selected.view.cut
                    is M3CanonicalReopenResult.Selected -> selected.commit.use { it.view.cut }
                    is M3CanonicalReopenResult.Refused ->
                        return M3CanonicalActivationResult.Refused(M3CanonicalActivationSelectorRefusal.CORRUPT_V6)
                }
            }
        }
        if (v6Cut != root.cut) return M3CanonicalActivationResult.Refused(M3CanonicalActivationSelectorRefusal.FORKED_SELECTOR)
        val current = (root.current as? CurrentRecord.Unacknowledged)?.let { record ->
            val file = currentFile(parent, group, record)
            if (!verifyCurrent(file, record.identity)) return M3CanonicalActivationResult.Refused(
                M3CanonicalActivationSelectorRefusal.CORRUPT_CURRENT,
            )
            M3CanonicalActivationCurrent.Receipt(record.identity, M3CanonicalCurrentSource.fromFile(file))
        } ?: M3CanonicalActivationCurrent.None
        val currentState = when (val record = root.current) {
            null -> M3CanonicalCurrentState.None
            is CurrentRecord.Acknowledged -> M3CanonicalCurrentState.Acknowledged(record.identity)
            is CurrentRecord.Unacknowledged -> M3CanonicalCurrentState.Unacknowledged(record.identity)
        }
        return M3CanonicalActivationResult.Active(
            M3CanonicalActivationState(root.cut, current, M3CanonicalActivationIdentity(selector.rootHash), currentState),
        )
    }

    private fun replay(
        state: M3CanonicalActivationState,
        plan: M3CanonicalActivationPlan,
    ): M3CanonicalActivationResult {
        if (state.cut != plan.siblingCut) return M3CanonicalActivationResult.Refused(
            M3CanonicalActivationSelectorRefusal.FORKED_SELECTOR,
        )
        val existing = state.current
        val requested = plan.current
        if (existing == M3CanonicalActivationCurrent.None && requested == M3CanonicalActivationCurrent.None) {
            return M3CanonicalActivationResult.Active(state)
        }
        if (existing is M3CanonicalActivationCurrent.Receipt && requested is M3CanonicalActivationCurrent.Receipt) {
            if (existing.identity == requested.identity) return M3CanonicalActivationResult.Active(state)
            return M3CanonicalActivationResult.Refused(
                if (existing.identity.commandHash == requested.identity.commandHash &&
                    existing.identity.commandFingerprint == requested.identity.commandFingerprint
                ) M3CanonicalActivationSelectorRefusal.CHANGED_CURRENT
                else M3CanonicalActivationSelectorRefusal.CURRENT_PENDING,
            )
        }
        return M3CanonicalActivationResult.Refused(M3CanonicalActivationSelectorRefusal.CURRENT_PENDING)
    }

    private fun validPlan(
        group: M3SurfaceGroup,
        parent: File,
        budget: M3CanonicalStorageBudget,
        plan: M3CanonicalActivationPlan,
    ): Boolean {
        if (!plan.isExactlyBound()) return false
        if (plan.siblingCut.group != group || plan.legacySourceHash != plan.siblingCut.sourceHash) return false
        val opened = M3CompactCanonicalStore.openV6(group, parent, budget)
            as? M3CompactCanonicalOpenResult.Opened ?: return false
        if (opened.store.use { it.cut } != plan.siblingCut) return false
        val receipt = plan.current as? M3CanonicalActivationCurrent.Receipt ?: return true
        return receipt.identity.canonicalLength in 0..M3CanonicalActivationResources.MAX_CURRENT_BYTES &&
            streamMatches(receipt.source, receipt.identity)
    }

    private fun streamMatches(source: M3CanonicalCurrentSource, identity: M3CanonicalCurrentIdentity): Boolean = try {
        val digest = MessageDigest.getInstance("SHA-256")
        var length = 0L
        source.writeTo(object : OutputStream() {
            override fun write(value: Int) { digest.update(value.toByte()); length = Math.addExact(length, 1L) }
            override fun write(bytes: ByteArray, offset: Int, count: Int) {
                digest.update(bytes, offset, count); length = Math.addExact(length, count.toLong())
            }
        })
        length == identity.canonicalLength && M3CanonicalReceiptBytes(digest.digest()) == identity.canonicalHash
    } catch (_: Exception) { false }

    private fun publishAdjacentRoot(
        group: M3SurfaceGroup,
        parent: File,
        budget: M3CanonicalStorageBudget,
        selector: ActivationSelector,
        cut: M3CompactCanonicalCut,
        identity: M3CanonicalCurrentIdentity,
        sourceFile: File,
        fault: M3CanonicalAdjacentFault?,
    ) {
        val record = CurrentRecord.Unacknowledged(identity)
        val currentTarget = currentFile(parent, group, record)
        val nextRoot = ActivationRoot(cut, record)
        val rootBytes = nextRoot.bytes()
        val rootHash = digest(rootBytes)
        val rootTarget = rootFile(parent, group, rootHash)
        val nextSlot = 1 - selector.slot
        val slotTarget = slotFile(parent, group, nextSlot)
        val obsoleteRootName = obsoleteRootName(parent, group, nextSlot, selector.rootHash, rootHash)
        val selectorTarget = selectorFile(parent, group)
        val unit = budget.allocationUnitBytes(parent)
        val currentBefore = currentTarget.takeIf(File::exists)?.let(budget::allocatedBytes) ?: 0L
        val rootBefore = rootTarget.takeIf(File::exists)?.let(budget::allocatedBytes) ?: 0L
        val slotBefore = slotTarget.takeIf(File::exists)?.let(budget::allocatedBytes) ?: 0L
        val selectorBefore = selectorTarget.takeIf(File::exists)?.let(budget::allocatedBytes) ?: 0L
        val combinedBefore = Math.addExact(currentBefore, rootBefore)
        val commitBytes = Math.max(0L, round(identity.canonicalLength, unit) - currentBefore) +
            Math.max(0L, round(rootBytes.size.toLong(), unit) - rootBefore) +
            Math.max(0L, round(ActivationSlot.BYTES.toLong(), unit) - slotBefore) +
            Math.max(0L, round(ActivationSelector.BYTES.toLong(), unit) - selectorBefore)
        val maximum = listOf(identity.canonicalLength, rootBytes.size.toLong(), ActivationSlot.BYTES.toLong(), ActivationSelector.BYTES.toLong(), 1L)
            .fold(0L) { total, bytes -> Math.addExact(total, round(bytes, unit)) }
        val publicationId = rootHash.toByteArray().hex()
        val existing = budget.activationAttempts(adjacentBudgetGroup(group)).singleOrNull { it.publicationId == publicationId }
        val token = existing?.token ?: budget.reserveActivationAttempt(
            adjacentBudgetGroup(group), publicationId, combinedBefore, slotBefore,
            selectorBefore, commitBytes, maximum,
        ) ?: error("adjacent quota refused")
        val reservation = existing ?: M3CanonicalPointerReservation(
            token, publicationId, nextSlot, combinedBefore, slotBefore, selectorBefore, commitBytes,
        )
        var switched = false
        var reservationClosed = false
        try {
            val receipt = M3CanonicalActivationCurrent.Receipt(identity, M3CanonicalCurrentSource.fromFile(sourceFile))
            writeCurrent(currentTarget, receipt, null)
            writeImmutable(rootTarget, rootBytes, false)
            atomicReplace(slotTarget, ActivationSlot(selector.revision + 1, rootHash).bytes(), false)
            sync(parent, M3CanonicalActivationSyncStage.ADJACENT_PREREQUISITES)
            injectAdjacent(fault, M3CanonicalAdjacentFault.PROCESS_CRASH_BEFORE_SELECTOR_SWITCH)
            atomicReplace(selectorTarget, ActivationSelector(nextSlot, selector.revision + 1, rootHash).bytes(), false)
            switched = true
            injectAdjacent(fault, M3CanonicalAdjacentFault.PROCESS_CRASH_AFTER_SELECTOR_SWITCH)
            sync(parent, M3CanonicalActivationSyncStage.ADJACENT_SELECTOR)
            budget.commitActivationAttempt(reservation)
            reservationClosed = true
            reclaimArtifacts(
                group, parent, budget, "adjacent-${rootHash.toByteArray().hex()}",
                listOfNotNull(sourceFile, obsoleteRootName?.let { File(parent, it) }),
            ) { stage -> injectAdjacentReclaim(fault, stage) }
        } catch (failure: Exception) {
            if (!switched && !reservationClosed) {
                listOf(currentTarget, rootTarget).forEach { if (it.exists()) it.delete() }
                sync(parent, M3CanonicalActivationSyncStage.RECOVERY)
                budget.releaseActivationAttempt(reservation)
            } else if (!reservationClosed) {
                budget.commitActivationAttempt(reservation)
            }
            throw failure
        }
    }

    private fun injectAdjacent(requested: M3CanonicalAdjacentFault?, point: M3CanonicalAdjacentFault) {
        if (requested == point) throw M3CanonicalAdjacentProcessCrash(point)
    }

    private fun writeCurrent(
        target: File,
        receipt: M3CanonicalActivationCurrent.Receipt,
        fault: M3CanonicalActivationFault?,
    ) {
        if (target.exists()) {
            require(verifyCurrent(target, receipt.identity))
            return
        }
        val temporary = File(target.parentFile, ".${target.name}.part")
        FileOutputStream(temporary, false).use { output ->
            var written = 0L
            val digest = MessageDigest.getInstance("SHA-256")
            receipt.source.writeTo(object : OutputStream() {
                override fun write(value: Int) { output.write(value); digest.update(value.toByte()); written++ }
                override fun write(bytes: ByteArray, offset: Int, count: Int) {
                    output.write(bytes, offset, count); digest.update(bytes, offset, count); written = Math.addExact(written, count.toLong())
                }
            })
            output.fd.sync()
            require(written == receipt.identity.canonicalLength && M3CanonicalReceiptBytes(digest.digest()) == receipt.identity.canonicalHash)
        }
        if (fault == M3CanonicalActivationFault.DURING_CURRENT_WRITE) throw IllegalStateException("fault")
        Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
    }

    private fun verifyCurrent(file: File, identity: M3CanonicalCurrentIdentity): Boolean = try {
        if (!file.isFile || file.length() != identity.canonicalLength) return false
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val scratch = ByteArray(CURRENT_STREAM_BUFFER_BYTES)
            while (true) { val count = input.read(scratch); if (count < 0) break; digest.update(scratch, 0, count) }
        }
        M3CanonicalReceiptBytes(digest.digest()) == identity.canonicalHash
    } catch (_: Exception) { false }

    private fun writeImmutable(target: File, bytes: ByteArray, partial: Boolean) {
        if (target.exists()) { require(ActivationRoot.read(target, digest(bytes)) != null); return }
        val temporary = File(target.parentFile, ".${target.name}.part")
        FileOutputStream(temporary, false).use { output -> output.write(if (partial) bytes.copyOf(bytes.size / 2) else bytes); output.fd.sync() }
        if (partial) throw IllegalStateException("fault")
        Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
    }
    private fun writeAttempt(target: File, bytes: ByteArray) {
        require(!target.exists())
        val temporary = File(target.parentFile, ".${target.name}.part")
        FileOutputStream(temporary, false).use { output -> output.write(bytes); output.fd.sync() }
        Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
    }
    private fun atomicReplace(target: File, bytes: ByteArray, partial: Boolean) {
        val temporary = File(target.parentFile, ".${target.name}.part")
        FileOutputStream(temporary, false).use { output -> output.write(if (partial) bytes.copyOf(bytes.size / 2) else bytes); output.fd.sync() }
        if (partial) throw IllegalStateException("fault")
        Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    }
    private fun cleanupLegacyCandidates(parent: File, group: M3SurfaceGroup) {
        val prefix = "m3-activation-${group.hash.hex()}"
        parent.listFiles().orEmpty().filter { it.name.startsWith(".$prefix") && it.name.endsWith(".part") }.forEach(File::delete)
    }
    private fun selectedActivationBytes(
        root: File,
        current: File?,
        slot: File,
        selector: File,
        budget: M3CanonicalStorageBudget,
    ) = listOfNotNull(root, current, slot, selector).sumOf(budget::allocatedBytes)
    private fun currentLength(current: M3CanonicalActivationCurrent) = (current as? M3CanonicalActivationCurrent.Receipt)?.identity?.canonicalLength ?: 0L
    private fun currentFile(parent: File, group: M3SurfaceGroup, current: M3CanonicalActivationCurrent): File? =
        (current as? M3CanonicalActivationCurrent.Receipt)?.let { currentFile(parent, group, CurrentRecord.Unacknowledged(it.identity)) }
    private fun currentFile(parent: File, group: M3SurfaceGroup, current: CurrentRecord): File =
        File(parent, "m3-activation-${group.hash.hex()}-current-${current.identity.canonicalHash.toByteArray().hex()}.receipt")
    private fun rootFile(parent: File, group: M3SurfaceGroup, hash: M3CanonicalReceiptBytes) =
        File(parent, "m3-activation-${group.hash.hex()}-root-${hash.toByteArray().hex()}.root")
    private fun slotFile(parent: File, group: M3SurfaceGroup, slot: Int) = File(parent, "m3-activation-${group.hash.hex()}-$slot.slot")
    private fun selectorFile(parent: File, group: M3SurfaceGroup) = File(parent, "m3-activation-${group.hash.hex()}.selector")
    private fun attemptFile(parent: File, group: M3SurfaceGroup, id: M3CanonicalReceiptBytes) =
        File(parent, "m3-activation-${group.hash.hex()}-attempt-${id.toByteArray().hex()}.attempt")
    private fun inject(fault: M3CanonicalActivationFault?, point: M3CanonicalActivationFault) {
        if (fault == point) {
            if (point.isProcessCrash()) throw M3CanonicalActivationProcessCrash(point)
            throw IllegalStateException("fault:$point")
        }
    }
    private fun writeAcknowledgementAttempt(target: File, bytes: ByteArray) {
        require(bytes.size <= MAX_ATTEMPT_BYTES)
        require(!target.exists())
        val temporary = File(target.parentFile, ".${target.name}.part")
        FileOutputStream(temporary, false).use { output -> output.write(bytes); output.fd.sync() }
        Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
    }
    private fun acknowledgementAttemptFile(parent: File, group: M3SurfaceGroup, id: M3CanonicalReceiptBytes) =
        File(parent, "m3-activation-${group.hash.hex()}-ack-${id.toByteArray().hex()}.attempt")
    private fun adjacentAttemptFile(parent: File, group: M3SurfaceGroup) =
        File(parent, "m3-activation-${group.hash.hex()}-adjacent.transaction")
    private fun reclaimAttemptFile(parent: File, group: M3SurfaceGroup, id: String) =
        File(parent, "m3-activation-${group.hash.hex()}-reclaim-$id.attempt")
    private fun writeReclaimAttempt(target: File, bytes: ByteArray) {
        require(bytes.size <= MAX_ATTEMPT_BYTES)
        atomicReplace(target, bytes, false)
    }
    private fun acknowledgementBudgetGroup(group: M3SurfaceGroup) = "ack:${group.hash.hex()}"
    private fun adjacentBudgetGroup(group: M3SurfaceGroup) = "adjacent:${group.hash.hex()}"
    private fun incrementalBytes(budget: M3CanonicalStorageBudget, file: File, before: Long) =
        Math.max(0L, (file.takeIf(File::exists)?.let(budget::allocatedBytes) ?: 0L) - before)
    private fun inject(fault: M3CanonicalAcknowledgementFault?, point: M3CanonicalAcknowledgementFault) {
        if (fault == point) {
            if (fault.isAcknowledgementProcessCrash()) throw M3CanonicalAcknowledgementProcessCrash(point)
            throw IllegalStateException("fault:$point")
        }
    }
    private fun M3CanonicalAcknowledgementFault?.isAcknowledgementProcessCrash() =
        this == M3CanonicalAcknowledgementFault.PROCESS_CRASH_AFTER_ROOT_SYNC ||
            this == M3CanonicalAcknowledgementFault.PROCESS_CRASH_AFTER_SELECTOR_SWITCH ||
            this == M3CanonicalAcknowledgementFault.PROCESS_CRASH_AFTER_RECLAIM_INTENT_SYNC ||
            this == M3CanonicalAcknowledgementFault.PROCESS_CRASH_AFTER_RECLAIM_DELETE_SYNC ||
            this == M3CanonicalAcknowledgementFault.PROCESS_CRASH_AFTER_RECLAIM_LEDGER
    private fun injectAcknowledgementReclaim(fault: M3CanonicalAcknowledgementFault?, stage: ReclaimStage) {
        val expected = when (stage) {
            ReclaimStage.AFTER_INTENT_SYNC -> M3CanonicalAcknowledgementFault.PROCESS_CRASH_AFTER_RECLAIM_INTENT_SYNC
            ReclaimStage.AFTER_DELETE_SYNC -> M3CanonicalAcknowledgementFault.PROCESS_CRASH_AFTER_RECLAIM_DELETE_SYNC
            ReclaimStage.AFTER_LEDGER -> M3CanonicalAcknowledgementFault.PROCESS_CRASH_AFTER_RECLAIM_LEDGER
        }
        if (fault == expected) throw M3CanonicalAcknowledgementProcessCrash(fault)
    }
    private fun injectAdjacentReclaim(fault: M3CanonicalAdjacentFault?, stage: ReclaimStage) {
        val expected = when (stage) {
            ReclaimStage.AFTER_INTENT_SYNC -> M3CanonicalAdjacentFault.PROCESS_CRASH_AFTER_RECLAIM_INTENT_SYNC
            ReclaimStage.AFTER_DELETE_SYNC -> M3CanonicalAdjacentFault.PROCESS_CRASH_AFTER_RECLAIM_DELETE_SYNC
            ReclaimStage.AFTER_LEDGER -> M3CanonicalAdjacentFault.PROCESS_CRASH_AFTER_RECLAIM_LEDGER
        }
        if (fault == expected) throw M3CanonicalAdjacentProcessCrash(fault)
    }
    private fun sync(parent: File, stage: M3CanonicalActivationSyncStage) {
        val physical = !System.getProperty("os.name").orEmpty().startsWith("Windows", true)
        if (physical) FileChannel.open(parent.toPath(), StandardOpenOption.READ).use { it.force(true) }
        M3CanonicalActivationTestHooks.onDirectorySync?.invoke(stage, physical)
    }
    private fun round(bytes: Long, unit: Long) = if (bytes == 0L) 0L else Math.multiplyExact((bytes - 1L) / unit + 1L, unit)
    private fun digest(bytes: ByteArray) = M3CanonicalReceiptBytes(MessageDigest.getInstance("SHA-256").digest(bytes))
    private fun hex(value: String) = value.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    private fun ByteArray.hex() = joinToString("") { "%02x".format(it) }
    private val locks = mutableMapOf<String, Any>()
    private val legacyLeases = mutableMapOf<String, Int>()
}

internal enum class M3CanonicalActivationSyncStage {
    ATTEMPT, PREREQUISITES, SELECTOR, ACK_ATTEMPT, ACK_ROOT, ACK_SLOT, ACK_SELECTOR,
    ADJACENT_INTENT, ADJACENT_PREREQUISITES, ADJACENT_SELECTOR,
    RECLAIM_INTENT, RECLAIM_DELETE, RECLAIM_COMPLETE, RECOVERY,
}
internal object M3CanonicalActivationTestHooks {
    /** Test-only observation after the requested physical sync has completed. */
    @Volatile var onDirectorySync: ((M3CanonicalActivationSyncStage, Boolean) -> Unit)? = null
    /** Test-only latch point while selector absence and legacy restoration share the group lock. */
    @Volatile var afterLegacySelection: (() -> Unit)? = null
}

internal data class M3CanonicalActivationState(
    val cut: M3CompactCanonicalCut,
    val current: M3CanonicalActivationCurrent,
    val identity: M3CanonicalActivationIdentity,
    val currentState: M3CanonicalCurrentState = when (current) {
        M3CanonicalActivationCurrent.None -> M3CanonicalCurrentState.None
        is M3CanonicalActivationCurrent.Receipt -> M3CanonicalCurrentState.Unacknowledged(current.identity)
    },
)
internal data class M3CanonicalActivationIdentity(val rootHash: M3CanonicalReceiptBytes)

/** Durable, revision-qualified state of the one retained canonical receipt. */
internal sealed interface M3CanonicalCurrentState {
    data object None : M3CanonicalCurrentState
    data class Unacknowledged(val identity: M3CanonicalCurrentIdentity) : M3CanonicalCurrentState
    data class Acknowledged(val identity: M3CanonicalCurrentIdentity) : M3CanonicalCurrentState
}

/** Exact ACK identity; the cut revisions fence a delayed ACK from a newer current. */
internal data class M3CanonicalAcknowledgement(
    val commandHash: M3CanonicalReceiptBytes,
    val geometryRevision: Long,
    val lineageRevision: Long,
)

internal sealed interface M3CanonicalAcknowledgementResult {
    data class Acknowledged(val state: M3CanonicalActivationState) : M3CanonicalAcknowledgementResult
    data class Idempotent(val state: M3CanonicalActivationState) : M3CanonicalAcknowledgementResult
    data class NoOp(val reason: M3CanonicalAcknowledgementNoOp) : M3CanonicalAcknowledgementResult
}

internal sealed interface M3CanonicalAdjacentCommitResult {
    data class Committed(val state: M3CanonicalActivationState) : M3CanonicalAdjacentCommitResult
    data class Refused(
        val reason: M3CanonicalAdjacentCommitRefusal,
        val commit: M3CanonicalCommitResult.Refused? = null,
    ) : M3CanonicalAdjacentCommitResult
}
internal enum class M3CanonicalAdjacentCommitRefusal {
    NO_ACTIVE_AUTHORITY, CURRENT_UNACKNOWLEDGED, STALE_CUT, COMMIT_REFUSED, DURABILITY_FAILURE,
}
internal enum class M3CanonicalAdjacentFault {
    PROCESS_CRASH_BEFORE_SELECTOR_SWITCH,
    PROCESS_CRASH_AFTER_SELECTOR_SWITCH,
    PROCESS_CRASH_AFTER_RECLAIM_INTENT_SYNC,
    PROCESS_CRASH_AFTER_RECLAIM_DELETE_SYNC,
    PROCESS_CRASH_AFTER_RECLAIM_LEDGER,
}
internal class M3CanonicalAdjacentProcessCrash(val point: M3CanonicalAdjacentFault) : Error(point.name)

internal enum class M3CanonicalAcknowledgementNoOp { CLOSED, NO_CURRENT, COMMAND_MISMATCH, STALE_REVISION, DURABILITY_FAILURE }
internal enum class M3CanonicalAcknowledgementFault {
    BEFORE_ATTEMPT, AFTER_ATTEMPT_SYNC, BEFORE_ROOT_WRITE, AFTER_ROOT_SYNC,
    BEFORE_SLOT_WRITE, AFTER_SLOT_SYNC, BEFORE_SELECTOR_SWITCH, AFTER_SELECTOR_SWITCH,
    BEFORE_PARENT_SYNC, AFTER_PARENT_SYNC, BEFORE_CLEANUP, AFTER_CLEANUP,
    PROCESS_CRASH_AFTER_ROOT_SYNC, PROCESS_CRASH_AFTER_SELECTOR_SWITCH,
    PROCESS_CRASH_AFTER_RECLAIM_INTENT_SYNC, PROCESS_CRASH_AFTER_RECLAIM_DELETE_SYNC,
    PROCESS_CRASH_AFTER_RECLAIM_LEDGER,
}
internal class M3CanonicalAcknowledgementProcessCrash(val point: M3CanonicalAcknowledgementFault) : Error(point.name)
internal sealed interface M3CanonicalActivationResult {
    data object Legacy : M3CanonicalActivationResult
    data class Active(val state: M3CanonicalActivationState) : M3CanonicalActivationResult
    data object UnknownAfterSwitch : M3CanonicalActivationResult
    data class Refused(val reason: M3CanonicalActivationSelectorRefusal) : M3CanonicalActivationResult
}
internal enum class M3CanonicalActivationSelectorRefusal { INVALID_PLAN, LEGACY_OWNER_ACTIVE, QUOTA_REFUSED, CURRENT_PENDING, CHANGED_CURRENT, CORRUPT_SELECTOR, FORKED_SELECTOR, CORRUPT_V6, CORRUPT_CURRENT, DURABILITY_FAILURE }
internal enum class M3CanonicalActivationFault {
    BEFORE_RESERVATION, AFTER_RESERVATION, BEFORE_CURRENT_WRITE, DURING_CURRENT_WRITE, AFTER_CURRENT_SYNC,
    AFTER_ATTEMPT_SYNC,
    BEFORE_ROOT_WRITE, DURING_ROOT_WRITE, AFTER_ROOT_SYNC, BEFORE_SLOT_WRITE, DURING_SLOT_WRITE, AFTER_SLOT_SYNC,
    BEFORE_PREREQUISITE_PARENT_SYNC, AFTER_PREREQUISITE_PARENT_SYNC,
    PROCESS_CRASH_AFTER_PREREQUISITE_SYNC,
    BEFORE_SELECTOR_SWITCH, DURING_SELECTOR_WRITE, AFTER_SELECTOR_SWITCH, BEFORE_PARENT_SYNC, AFTER_PARENT_SYNC,
    BEFORE_BUDGET_COMMIT, AFTER_BUDGET_COMMIT, BEFORE_CLEANUP, AFTER_CLEANUP,
}

internal class M3CanonicalActivationProcessCrash(val point: M3CanonicalActivationFault) : Error(point.name)
private fun M3CanonicalActivationFault?.isProcessCrash() =
    this == M3CanonicalActivationFault.PROCESS_CRASH_AFTER_PREREQUISITE_SYNC

private enum class ReclaimStage { AFTER_INTENT_SYNC, AFTER_DELETE_SYNC, AFTER_LEDGER }
private data class ReclaimEntry(val path: String, val bytes: Long)
private data class ReclaimAttempt(
    val groupHash: String,
    val reclaimId: String,
    val complete: Boolean,
    val entries: List<ReclaimEntry>,
) {
    fun bytes(): ByteArray = checked { out ->
        out.writeInt(0x4d335243); out.writeInt(1); out.writeUTF(groupHash); out.writeUTF(reclaimId)
        out.writeBoolean(complete); out.writeInt(entries.size)
        entries.forEach { entry -> out.writeUTF(entry.path); out.writeLong(entry.bytes) }
    }.also { require(it.size <= MAX_ATTEMPT_BYTES) }

    companion object {
        fun read(file: File, expectedId: String, expectedGroup: String): ReclaimAttempt? = try {
            val bytes = readBounded(file, MAX_ATTEMPT_BYTES, 33) ?: return null
            val body = checkedBody(bytes) ?: return null
            DataInputStream(ByteArrayInputStream(body)).use { input ->
                require(input.readInt() == 0x4d335243 && input.readInt() == 1)
                val group = input.readUTF(); val id = input.readUTF(); val complete = input.readBoolean()
                val count = input.readInt(); require(count in 1..3)
                val entries = List(count) {
                    ReclaimEntry(input.readUTF().also { require(it.isSafeRelativeActivationPath()) }, input.readLong().also { require(it > 0L) })
                }
                require(input.read() == -1 && group == expectedGroup && id == expectedId && entries.map(ReclaimEntry::path).distinct().size == entries.size)
                ReclaimAttempt(group, id, complete, entries)
            }
        } catch (_: Exception) { null }
    }
}

private fun String.isSafeRelativeActivationPath(): Boolean {
    if (isEmpty() || startsWith('/') || contains('\\')) return false
    val segments = split('/')
    return segments.size in 1..2 && segments.all { it.isNotEmpty() && it != "." && it != ".." && it.matches(Regex("[A-Za-z0-9._-]{1,180}")) }
}

private data class ActivationAttempt(
    val groupHash: String,
    val rootName: String,
    val currentName: String?,
    val slotName: String,
    val rootPreexisted: Boolean,
    val currentPreexisted: Boolean,
) {
    val rootCreated get() = !rootPreexisted
    val currentCreated get() = currentName != null && !currentPreexisted

    fun bytes(): ByteArray = ByteArrayOutputStream().use { raw ->
        DataOutputStream(raw).use { output ->
            output.writeInt(MAGIC); output.writeInt(1)
            output.writeUTF(groupHash); output.writeUTF(rootName)
            output.writeBoolean(currentName != null); currentName?.let(output::writeUTF)
            output.writeUTF(slotName); output.writeBoolean(rootPreexisted); output.writeBoolean(currentPreexisted)
        }
        raw.toByteArray()
    }

    companion object {
        private const val MAGIC = 0x4d334141
        fun read(file: File, expectedId: String, expectedGroupHash: String): ActivationAttempt? = try {
            val bytes = readBounded(file, MAX_ATTEMPT_BYTES, 16) ?: return null
            val actualId = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
            require(actualId == expectedId)
            DataInputStream(ByteArrayInputStream(bytes)).use { input ->
                require(input.readInt() == MAGIC && input.readInt() == 1)
                val groupHash = input.readUTF(); val root = input.readUTF()
                val current = if (input.readBoolean()) input.readUTF() else null
                val slot = input.readUTF(); val rootBefore = input.readBoolean(); val currentBefore = input.readBoolean()
                require(input.available() == 0 && groupHash == expectedGroupHash)
                ActivationAttempt(groupHash, root, current, slot, rootBefore, currentBefore)
            }
        } catch (_: Exception) { null }
    }
}

/** Bounded durable intent for an ACK root switch and later payload release. */
private data class AcknowledgementAttempt(
    val groupHash: String,
    val oldCurrentName: String,
    val nextRootName: String,
    val nextSlotName: String,
    val selectorName: String,
    val obsoleteRootName: String?,
    val rootPreexisted: Boolean,
    val slotPreexisted: Boolean,
) {
    val rootCreated get() = !rootPreexisted
    val slotCreated get() = !slotPreexisted
    fun bytes(): ByteArray = checked { out ->
            out.writeInt(0x4d33414b); out.writeInt(2); out.writeUTF(groupHash)
            out.writeUTF(oldCurrentName); out.writeUTF(nextRootName); out.writeUTF(nextSlotName)
            out.writeUTF(selectorName); out.writeBoolean(obsoleteRootName != null); obsoleteRootName?.let(out::writeUTF)
            out.writeBoolean(rootPreexisted); out.writeBoolean(slotPreexisted)
    }
    companion object {
        fun read(file: File, expectedId: String, expectedGroupHash: String): AcknowledgementAttempt? = try {
            val bytes = readBounded(file, MAX_ATTEMPT_BYTES, 33) ?: return null
            require(MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) } == expectedId)
            val body = checkedBody(bytes) ?: return null
            DataInputStream(ByteArrayInputStream(body)).use { input ->
                require(input.readInt() == 0x4d33414b && input.readInt() == 2)
                val group = input.readUTF(); val old = input.readUTF(); val root = input.readUTF(); val slot = input.readUTF()
                val selector = input.readUTF(); val obsolete = if (input.readBoolean()) input.readUTF() else null
                val rootBefore = input.readBoolean(); val slotBefore = input.readBoolean()
                require(input.available() == 0 && group == expectedGroupHash)
                AcknowledgementAttempt(group, old, root, slot, selector, obsolete, rootBefore, slotBefore)
            }
        } catch (_: Exception) { null }
    }
}

private data class AdjacentTransaction(
    val groupHash: String,
    val activationRootHash: M3CanonicalReceiptBytes,
    val sourceRootHash: M3CanonicalReceiptBytes,
    val commandHash: M3CanonicalReceiptBytes,
    val commandFingerprint: M3CanonicalReceiptBytes,
    val acknowledged: M3CanonicalCurrentIdentity?,
    val obsoleteRootName: String?,
) {
    fun bytes() = checked { out ->
        out.writeInt(0x4d334154); out.writeInt(2); out.writeUTF(groupHash)
        out.write(activationRootHash.toByteArray()); out.write(sourceRootHash.toByteArray())
        out.write(commandHash.toByteArray()); out.write(commandFingerprint.toByteArray())
        out.writeBoolean(acknowledged != null)
        acknowledged?.let {
            out.write(it.commandHash.toByteArray()); out.write(it.commandFingerprint.toByteArray())
            out.writeLong(it.canonicalLength); out.write(it.canonicalHash.toByteArray())
        }
        out.writeBoolean(obsoleteRootName != null); obsoleteRootName?.let(out::writeUTF)
    }
    companion object {
        fun read(file: File, expectedGroup: String): AdjacentTransaction? = try {
            val bytes = readBounded(file, MAX_ATTEMPT_BYTES, 33) ?: return null
            val body = checkedBody(bytes) ?: return null
            DataInputStream(ByteArrayInputStream(body)).use { input ->
                require(input.readInt() == 0x4d334154 && input.readInt() == 2)
                val group = input.readUTF(); require(group == expectedGroup)
                fun hash() = M3CanonicalReceiptBytes(ByteArray(32).also(input::readFully))
                val activation = hash(); val source = hash(); val command = hash(); val fingerprint = hash()
                val acknowledged = if (input.readBoolean()) M3CanonicalCurrentIdentity(hash(), hash(), input.readLong(), hash()) else null
                val obsolete = if (input.readBoolean()) input.readUTF() else null
                require((acknowledged == null || acknowledged.canonicalLength in 0..M3CanonicalActivationResources.MAX_CURRENT_BYTES) && input.read() == -1)
                AdjacentTransaction(group, activation, source, command, fingerprint, acknowledged, obsolete)
            }
        } catch (_: Exception) { null }
    }
}

/** Explicit durable authority states; an ACK is never inferred from payload absence. */
private sealed interface CurrentRecord {
    val identity: M3CanonicalCurrentIdentity
    data class Unacknowledged(override val identity: M3CanonicalCurrentIdentity) : CurrentRecord
    data class Acknowledged(override val identity: M3CanonicalCurrentIdentity) : CurrentRecord
}
private data class ActivationRoot(val cut: M3CompactCanonicalCut, val current: CurrentRecord?) {
    fun bytes(): ByteArray {
        val raw = ByteArrayOutputStream()
        DataOutputStream(raw).use { out ->
            out.writeInt(0x4d334152); out.writeInt(2); out.writeUTF(cut.group.value); out.writeUTF(cut.profile)
            out.writeLong(cut.geometryRevision); out.writeLong(cut.lineageRevision); out.writeLong(cut.nextSurfaceIdHighWater)
            out.writeInt(cut.liveSurfaceCount); out.writeInt(cut.sourceCount); out.writeInt(cut.supportCount); out.writeInt(cut.lineageCount)
            out.writeBoolean(cut.seededEmptyBaseline != null)
            cut.seededEmptyBaseline?.let { baseline -> out.writeUTF(baseline.bindingIdentity); out.writeUTF(baseline.groupIdentity); out.writeLong(baseline.transactionId); out.writeLong(baseline.geometryRevision); out.writeLong(baseline.lineageRevision) }
            out.write(cut.rootHash.toByteArray()); out.write(cut.sourceHash.toByteArray())
            out.writeByte(when (current) { null -> 0; is CurrentRecord.Unacknowledged -> 1; is CurrentRecord.Acknowledged -> 2 })
            current?.let { value -> out.write(value.identity.commandHash.toByteArray()); out.write(value.identity.commandFingerprint.toByteArray()); out.writeLong(value.identity.canonicalLength); out.write(value.identity.canonicalHash.toByteArray()) }
        }
        val body = raw.toByteArray()
        return body + MessageDigest.getInstance("SHA-256").digest(body)
    }
    companion object {
        fun read(file: File, expected: M3CanonicalReceiptBytes): ActivationRoot? = try {
            val bytes = readBounded(file, MAX_ROOT_BYTES, 33) ?: return null
            require(M3CanonicalActivationSelectorDigest.of(bytes) == expected)
            val body = checkedBody(bytes) ?: return null
            DataInputStream(ByteArrayInputStream(body)).use { input ->
                require(input.readInt() == 0x4d334152); val version = input.readInt(); require(version in 1..2)
                val group = M3SurfaceGroup(input.readUTF()); val profile = input.readUTF()
                val geometry = input.readLong(); val lineageRevision = input.readLong(); val high = input.readLong(); val live = input.readInt(); val sources = input.readInt(); val supports = input.readInt(); val lineage = input.readInt()
                val baseline = if (input.readBoolean()) M3CommittedEmptyBaseline(input.readUTF(), input.readUTF(), input.readLong(), input.readLong(), input.readLong()) else null
                val root = M3CanonicalReceiptBytes(ByteArray(32).also(input::readFully)); val source = M3CanonicalReceiptBytes(ByteArray(32).also(input::readFully))
                val currentState = if (version == 1) if (input.readBoolean()) 1 else 0 else input.readUnsignedByte().also { require(it in 0..2) }
                val current = if (currentState != 0) {
                    val identity = M3CanonicalCurrentIdentity(M3CanonicalReceiptBytes(ByteArray(32).also(input::readFully)), M3CanonicalReceiptBytes(ByteArray(32).also(input::readFully)), input.readLong(), M3CanonicalReceiptBytes(ByteArray(32).also(input::readFully)))
                    require(identity.canonicalLength in 0..M3CanonicalActivationResources.MAX_CURRENT_BYTES)
                    if (currentState == 2) CurrentRecord.Acknowledged(identity) else CurrentRecord.Unacknowledged(identity)
                } else null
                require(input.read() == -1)
                val cut = M3CompactCanonicalCut(group, profile, geometry, lineageRevision, high, live, sources, supports, lineage, baseline, root, source)
                ActivationRoot(cut, current)
            }
        } catch (_: Exception) { null }
    }
}
private data class ActivationSlot(val revision: Long, val rootHash: M3CanonicalReceiptBytes) {
    fun bytes() = checked { out -> out.writeInt(0x4d334153); out.writeInt(1); out.writeLong(revision); out.write(rootHash.toByteArray()) }
    companion object { const val BYTES = 80; fun read(file: File) = readCheckedActivation(file, BYTES) { input -> require(input.readInt() == 0x4d334153 && input.readInt() == 1); ActivationSlot(input.readLong().also { require(it > 0) }, M3CanonicalReceiptBytes(ByteArray(32).also(input::readFully))) } }
}
private data class ActivationSelector(val slot: Int, val revision: Long, val rootHash: M3CanonicalReceiptBytes) {
    fun bytes() = checked { out -> out.writeInt(0x4d334154); out.writeInt(1); out.writeByte(slot); out.writeLong(revision); out.write(rootHash.toByteArray()); repeat(7) { out.writeByte(0) } }
    companion object { const val BYTES = 88; fun read(file: File) = readCheckedActivation(file, BYTES) { input -> require(input.readInt() == 0x4d334154 && input.readInt() == 1); val slot = input.readUnsignedByte(); require(slot in 0..1); ActivationSelector(slot, input.readLong().also { require(it > 0) }, M3CanonicalReceiptBytes(ByteArray(32).also(input::readFully))).also { repeat(7) { require(input.readUnsignedByte() == 0) } } } }
}
private object M3CanonicalActivationSelectorDigest { fun of(bytes: ByteArray) = M3CanonicalReceiptBytes(MessageDigest.getInstance("SHA-256").digest(bytes)) }
private fun checked(body: (DataOutputStream) -> Unit): ByteArray { val raw = ByteArrayOutputStream(); DataOutputStream(raw).use(body); val bytes = raw.toByteArray(); return bytes + MessageDigest.getInstance("SHA-256").digest(bytes) }
private fun checkedBody(bytes: ByteArray): ByteArray? {
    if (bytes.size <= 32) return null
    val body = bytes.copyOf(bytes.size - 32)
    return body.takeIf { MessageDigest.getInstance("SHA-256").digest(it).contentEquals(bytes.copyOfRange(body.size, bytes.size)) }
}
private fun readBounded(file: File, maximum: Int, minimum: Int): ByteArray? = try {
    val length = file.length()
    if (!file.isFile || length !in minimum.toLong()..maximum.toLong()) return null
    val bytes = ByteArray(length.toInt())
    FileInputStream(file).use { input ->
        var offset = 0
        while (offset < bytes.size) {
            val count = input.read(bytes, offset, bytes.size - offset)
            if (count <= 0) return null
            offset += count
        }
        if (input.read() != -1) return null
    }
    bytes
} catch (_: Exception) { null }
private fun <T> readCheckedActivation(file: File, size: Int, reader: (DataInputStream) -> T): T? = try {
    val bytes = readBounded(file, size, size) ?: return null
    val body = checkedBody(bytes) ?: return null
    DataInputStream(ByteArrayInputStream(body)).use { input -> reader(input).also { require(input.read() == -1) } }
} catch (_: Exception) { null }
private const val MAX_ATTEMPT_BYTES = 2_048
private const val MAX_ROOT_BYTES = 8_192
private const val CURRENT_STREAM_BUFFER_BYTES = 8 * 1024
internal object M3CanonicalActivationResources {
    const val MAX_CURRENT_BYTES = 1_048_576L
    const val RETAINED_STATE_BYTES = 1_024L
    const val MAXIMUM_PROFILE_RETAINED_BYTES = 14_565_056L
    const val SHARED_PHASE_BYTES = 1_048_576L
    const val INTEGRATED_PEAK_BYTES = MAXIMUM_PROFILE_RETAINED_BYTES + RETAINED_STATE_BYTES + SHARED_PHASE_BYTES
}
private fun M3CanonicalCurrentIdentity.toIntentReceipt() =
    M3PreparedIntentCurrentReceipt(canonicalLength, canonicalHash)
