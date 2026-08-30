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
            M3CanonicalActivationPlan(
                M3CanonicalReceiptBytes(legacy.sourceHash), cut, current,
                M3CanonicalActivationPreparationReceipt(scanned, matching, 512),
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

internal data class M3CanonicalActivationPlan(
    val legacySourceHash: M3CanonicalReceiptBytes,
    val siblingCut: M3CompactCanonicalCut,
    val current: M3CanonicalActivationCurrent,
    val receipt: M3CanonicalActivationPreparationReceipt,
)

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
    ): M3CanonicalActivationResult = withLock(parent, group) {
        val existing = reopenLocked(group, parent, budget)
        when (existing) {
            is M3CanonicalActivationResult.Active -> return@withLock replay(existing.state, plan)
            is M3CanonicalActivationResult.Refused -> return@withLock existing
            M3CanonicalActivationResult.Legacy -> Unit
            M3CanonicalActivationResult.UnknownAfterSwitch -> return@withLock M3CanonicalActivationResult.Refused(
                M3CanonicalActivationSelectorRefusal.DURABILITY_FAILURE,
            )
        }
        if (!validPlan(group, parent, budget, plan)) {
            return@withLock M3CanonicalActivationResult.Refused(M3CanonicalActivationSelectorRefusal.INVALID_PLAN)
        }

        val root = ActivationRoot(
            plan.siblingCut,
            (plan.current as? M3CanonicalActivationCurrent.Receipt)?.let { CurrentRecord(it.identity) },
        )
        val rootBytes = root.bytes()
        val rootHash = digest(rootBytes)
        val rootTarget = rootFile(parent, group, rootHash)
        val currentTarget = currentFile(parent, group, plan.current)
        val slotTarget = slotFile(parent, group, 0)
        val selectorTarget = selectorFile(parent, group)
        val unit = try { budget.allocationUnitBytes(parent) } catch (_: Exception) {
            return@withLock M3CanonicalActivationResult.Refused(M3CanonicalActivationSelectorRefusal.DURABILITY_FAILURE)
        }
        val maximum = listOf(
            rootBytes.size.toLong(), ActivationSlot.BYTES.toLong(), ActivationSelector.BYTES.toLong(),
            currentLength(plan.current), 1L, 1L, 1L,
        ).fold(0L) { total, bytes -> Math.addExact(total, round(bytes, unit)) }
        var reservation: Any? = null
        var switched = false
        try {
            inject(fault, M3CanonicalActivationFault.BEFORE_RESERVATION)
            reservation = budget.reserve(maximum)
                ?: return@withLock M3CanonicalActivationResult.Refused(M3CanonicalActivationSelectorRefusal.QUOTA_REFUSED)
            inject(fault, M3CanonicalActivationFault.AFTER_RESERVATION)
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
            inject(fault, M3CanonicalActivationFault.BEFORE_SELECTOR_SWITCH)
            atomicReplace(
                selectorTarget,
                ActivationSelector(0, 1L, rootHash).bytes(),
                fault == M3CanonicalActivationFault.DURING_SELECTOR_WRITE,
            )
            switched = true
            inject(fault, M3CanonicalActivationFault.AFTER_SELECTOR_SWITCH)
            inject(fault, M3CanonicalActivationFault.BEFORE_PARENT_SYNC)
            sync(parent)
            inject(fault, M3CanonicalActivationFault.AFTER_PARENT_SYNC)
            val actual = allocatedActivationBytes(parent, group, budget)
            inject(fault, M3CanonicalActivationFault.BEFORE_BUDGET_COMMIT)
            budget.commit(requireNotNull(reservation), actual)
            reservation = null
            inject(fault, M3CanonicalActivationFault.AFTER_BUDGET_COMMIT)
            inject(fault, M3CanonicalActivationFault.BEFORE_CLEANUP)
            cleanupLegacyCandidates(parent, group)
            inject(fault, M3CanonicalActivationFault.AFTER_CLEANUP)
            reopenLocked(group, parent, budget)
        } catch (_: Exception) {
            if (switched) M3CanonicalActivationResult.UnknownAfterSwitch
            else M3CanonicalActivationResult.Refused(M3CanonicalActivationSelectorRefusal.DURABILITY_FAILURE)
        } finally {
            reservation?.let { token ->
                try {
                    if (switched) budget.commit(token, allocatedActivationBytes(parent, group, budget))
                    else budget.release(token)
                } catch (_: Exception) { }
            }
            if (!switched) cleanupLegacyCandidates(parent, group)
        }
    }

    fun reopen(
        group: M3SurfaceGroup,
        parent: File,
        budget: M3CanonicalStorageBudget,
    ): M3CanonicalActivationResult = withLock(parent, group) { reopenLocked(group, parent, budget) }

    /** A legacy-only caller must never step around a durable v6 authority. */
    fun hasDurableSelector(group: M3SurfaceGroup, parent: File): Boolean = selectorFile(parent, group).exists()

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
        val v6Cut = opened.store.use { it.cut }
        if (v6Cut != root.cut) return M3CanonicalActivationResult.Refused(M3CanonicalActivationSelectorRefusal.FORKED_SELECTOR)
        val current = root.current?.let { record ->
            val file = currentFile(parent, group, record)
            if (!verifyCurrent(file, record.identity)) return M3CanonicalActivationResult.Refused(
                M3CanonicalActivationSelectorRefusal.CORRUPT_CURRENT,
            )
            M3CanonicalActivationCurrent.Receipt(record.identity, M3CanonicalCurrentSource.fromFile(file))
        } ?: M3CanonicalActivationCurrent.None
        return M3CanonicalActivationResult.Active(
            M3CanonicalActivationState(root.cut, current, M3CanonicalActivationIdentity(selector.rootHash)),
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
        if (plan.siblingCut.group != group || plan.legacySourceHash != plan.siblingCut.sourceHash) return false
        val opened = M3CompactCanonicalStore.openV6(group, parent, budget)
            as? M3CompactCanonicalOpenResult.Opened ?: return false
        if (opened.store.use { it.cut } != plan.siblingCut) return false
        val receipt = plan.current as? M3CanonicalActivationCurrent.Receipt ?: return true
        return receipt.identity.canonicalLength >= 0 && streamMatches(receipt.source, receipt.identity)
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
    private fun allocatedActivationBytes(parent: File, group: M3SurfaceGroup, budget: M3CanonicalStorageBudget) =
        parent.listFiles().orEmpty().filter { it.name.startsWith("m3-activation-${group.hash.hex()}") }.sumOf(budget::allocatedBytes)
    private fun currentLength(current: M3CanonicalActivationCurrent) = (current as? M3CanonicalActivationCurrent.Receipt)?.identity?.canonicalLength ?: 0L
    private fun currentFile(parent: File, group: M3SurfaceGroup, current: M3CanonicalActivationCurrent): File? =
        (current as? M3CanonicalActivationCurrent.Receipt)?.let { currentFile(parent, group, CurrentRecord(it.identity)) }
    private fun currentFile(parent: File, group: M3SurfaceGroup, current: CurrentRecord): File =
        File(parent, "m3-activation-${group.hash.hex()}-current-${current.identity.canonicalHash.toByteArray().hex()}.receipt")
    private fun rootFile(parent: File, group: M3SurfaceGroup, hash: M3CanonicalReceiptBytes) =
        File(parent, "m3-activation-${group.hash.hex()}-root-${hash.toByteArray().hex()}.root")
    private fun slotFile(parent: File, group: M3SurfaceGroup, slot: Int) = File(parent, "m3-activation-${group.hash.hex()}-$slot.slot")
    private fun selectorFile(parent: File, group: M3SurfaceGroup) = File(parent, "m3-activation-${group.hash.hex()}.selector")
    private fun inject(fault: M3CanonicalActivationFault?, point: M3CanonicalActivationFault) { if (fault == point) throw IllegalStateException("fault:$point") }
    private fun sync(parent: File) { if (!System.getProperty("os.name").orEmpty().startsWith("Windows", true)) FileChannel.open(parent.toPath(), StandardOpenOption.READ).use { it.force(true) } }
    private fun round(bytes: Long, unit: Long) = if (bytes == 0L) 0L else Math.multiplyExact((bytes - 1L) / unit + 1L, unit)
    private fun digest(bytes: ByteArray) = M3CanonicalReceiptBytes(MessageDigest.getInstance("SHA-256").digest(bytes))
    private fun ByteArray.hex() = joinToString("") { "%02x".format(it) }
    private val locks = mutableMapOf<String, Any>()
    private fun <T> withLock(parent: File, group: M3SurfaceGroup, block: () -> T): T {
        val key = parent.absoluteFile.toPath().normalize().toString() + ':' + group.value
        val lock = synchronized(locks) { locks.getOrPut(key) { Any() } }
        return synchronized(lock, block)
    }
}

internal data class M3CanonicalActivationState(
    val cut: M3CompactCanonicalCut,
    val current: M3CanonicalActivationCurrent,
    val identity: M3CanonicalActivationIdentity,
)
internal data class M3CanonicalActivationIdentity(val rootHash: M3CanonicalReceiptBytes)
internal sealed interface M3CanonicalActivationResult {
    data object Legacy : M3CanonicalActivationResult
    data class Active(val state: M3CanonicalActivationState) : M3CanonicalActivationResult
    data object UnknownAfterSwitch : M3CanonicalActivationResult
    data class Refused(val reason: M3CanonicalActivationSelectorRefusal) : M3CanonicalActivationResult
}
internal enum class M3CanonicalActivationSelectorRefusal { INVALID_PLAN, QUOTA_REFUSED, CURRENT_PENDING, CHANGED_CURRENT, CORRUPT_SELECTOR, FORKED_SELECTOR, CORRUPT_V6, CORRUPT_CURRENT, DURABILITY_FAILURE }
internal enum class M3CanonicalActivationFault {
    BEFORE_RESERVATION, AFTER_RESERVATION, BEFORE_CURRENT_WRITE, DURING_CURRENT_WRITE, AFTER_CURRENT_SYNC,
    BEFORE_ROOT_WRITE, DURING_ROOT_WRITE, AFTER_ROOT_SYNC, BEFORE_SLOT_WRITE, DURING_SLOT_WRITE, AFTER_SLOT_SYNC,
    BEFORE_SELECTOR_SWITCH, DURING_SELECTOR_WRITE, AFTER_SELECTOR_SWITCH, BEFORE_PARENT_SYNC, AFTER_PARENT_SYNC,
    BEFORE_BUDGET_COMMIT, AFTER_BUDGET_COMMIT, BEFORE_CLEANUP, AFTER_CLEANUP,
}

private data class CurrentRecord(val identity: M3CanonicalCurrentIdentity)
private data class ActivationRoot(val cut: M3CompactCanonicalCut, val current: CurrentRecord?) {
    fun bytes(): ByteArray {
        val raw = ByteArrayOutputStream()
        DataOutputStream(raw).use { out ->
            out.writeInt(0x4d334152); out.writeInt(1); out.writeUTF(cut.group.value); out.writeUTF(cut.profile)
            out.writeLong(cut.geometryRevision); out.writeLong(cut.lineageRevision); out.writeLong(cut.nextSurfaceIdHighWater)
            out.writeInt(cut.liveSurfaceCount); out.writeInt(cut.sourceCount); out.writeInt(cut.supportCount); out.writeInt(cut.lineageCount)
            out.writeBoolean(cut.seededEmptyBaseline != null)
            cut.seededEmptyBaseline?.let { baseline -> out.writeUTF(baseline.bindingIdentity); out.writeUTF(baseline.groupIdentity); out.writeLong(baseline.transactionId); out.writeLong(baseline.geometryRevision); out.writeLong(baseline.lineageRevision) }
            out.write(cut.rootHash.toByteArray()); out.write(cut.sourceHash.toByteArray())
            out.writeBoolean(current != null)
            current?.let { value -> out.write(value.identity.commandHash.toByteArray()); out.write(value.identity.commandFingerprint.toByteArray()); out.writeLong(value.identity.canonicalLength); out.write(value.identity.canonicalHash.toByteArray()) }
        }
        val body = raw.toByteArray()
        return body + MessageDigest.getInstance("SHA-256").digest(body)
    }
    companion object {
        fun read(file: File, expected: M3CanonicalReceiptBytes): ActivationRoot? = try {
            val bytes = file.readBytes(); require(M3CanonicalActivationSelectorDigest.of(bytes) == expected && bytes.size > 32)
            val body = bytes.copyOf(bytes.size - 32); require(MessageDigest.getInstance("SHA-256").digest(body).contentEquals(bytes.copyOfRange(body.size, bytes.size)))
            DataInputStream(ByteArrayInputStream(body)).use { input ->
                require(input.readInt() == 0x4d334152 && input.readInt() == 1)
                val group = M3SurfaceGroup(input.readUTF()); val profile = input.readUTF()
                val geometry = input.readLong(); val lineageRevision = input.readLong(); val high = input.readLong(); val live = input.readInt(); val sources = input.readInt(); val supports = input.readInt(); val lineage = input.readInt()
                val baseline = if (input.readBoolean()) M3CommittedEmptyBaseline(input.readUTF(), input.readUTF(), input.readLong(), input.readLong(), input.readLong()) else null
                val root = M3CanonicalReceiptBytes(ByteArray(32).also(input::readFully)); val source = M3CanonicalReceiptBytes(ByteArray(32).also(input::readFully))
                val current = if (input.readBoolean()) {
                    val identity = M3CanonicalCurrentIdentity(M3CanonicalReceiptBytes(ByteArray(32).also(input::readFully)), M3CanonicalReceiptBytes(ByteArray(32).also(input::readFully)), input.readLong(), M3CanonicalReceiptBytes(ByteArray(32).also(input::readFully)))
                    require(identity.canonicalLength >= 0); CurrentRecord(identity)
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
    companion object { const val BYTES = 88; fun read(file: File) = readCheckedActivation(file, BYTES) { input -> require(input.readInt() == 0x4d334154 && input.readInt() == 1); val slot = input.readUnsignedByte(); require(slot == 0); ActivationSelector(slot, input.readLong().also { require(it > 0) }, M3CanonicalReceiptBytes(ByteArray(32).also(input::readFully))).also { repeat(7) { require(input.readUnsignedByte() == 0) } } } }
}
private object M3CanonicalActivationSelectorDigest { fun of(bytes: ByteArray) = M3CanonicalReceiptBytes(MessageDigest.getInstance("SHA-256").digest(bytes)) }
private fun checked(body: (DataOutputStream) -> Unit): ByteArray { val raw = ByteArrayOutputStream(); DataOutputStream(raw).use(body); val bytes = raw.toByteArray(); return bytes + MessageDigest.getInstance("SHA-256").digest(bytes) }
private fun <T> readCheckedActivation(file: File, size: Int, reader: (DataInputStream) -> T): T? = try { val bytes = file.readBytes(); require(bytes.size == size && bytes.size > 32); val body = bytes.copyOf(bytes.size - 32); require(MessageDigest.getInstance("SHA-256").digest(body).contentEquals(bytes.copyOfRange(body.size, bytes.size))); DataInputStream(ByteArrayInputStream(body)).use { input -> reader(input).also { require(input.read() == -1) } } } catch (_: Exception) { null }
private const val CURRENT_STREAM_BUFFER_BYTES = 8 * 1024
