package com.uhg0.ar_flutter_plugin_2.visibilitygrid

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest

/**
 * #123's deep module: immutable generations and their ancestry sit behind one
 * constant-size selector. Callers learn only publish/reopen/lookup semantics.
 */
internal class PrivateRootSelector(
    private val parent: File,
    private val budget: CanonicalStorageBudget,
    private val maximumGenerations: Int = MAX_GENERATIONS,
) {
    init { require(maximumGenerations in 1..MAX_GENERATIONS) }
    fun reopen(
        generationZero: CanonicalStateView,
        acknowledgedCurrent: PreparedIntentCurrentReceipt? = null,
    ): CanonicalReopenResult = withParentLock {
        try { recoverPointerPublications() } catch (_: Exception) {
            return CanonicalReopenResult.Refused(CanonicalSelectorRefusal.DURABILITY_FAILURE)
        }
        reopenLocked(generationZero, cleanupUnreachable = true, acknowledgedCurrent)
    }

    private fun reopenLocked(
        generationZero: CanonicalStateView,
        cleanupUnreachable: Boolean,
        acknowledgedCurrent: PreparedIntentCurrentReceipt? = null,
    ): CanonicalReopenResult {
        val selectorFile = File(parent, SELECTOR_FILE)
        if (!selectorFile.exists()) {
            if (cleanupUnreachable) try { cleanup(emptyList()) } catch (_: Exception) { }
            return CanonicalReopenResult.GenerationZero(generationZero)
        }
        val selector = Selector.read(selectorFile)
            ?: return CanonicalReopenResult.Refused(CanonicalSelectorRefusal.CORRUPT_SELECTED_ROOT)
        val slot = Slot.read(File(parent, slotName(selector.slot)))
            ?.takeIf { it.revision == selector.revision && it.rootHash == selector.rootHash }
            ?: return CanonicalReopenResult.Refused(CanonicalSelectorRefusal.CORRUPT_SELECTED_ROOT)
        val commit = buildCommit(selector.rootHash, generationZero, acknowledgedCurrent)
            ?: return CanonicalReopenResult.Refused(CanonicalSelectorRefusal.CORRUPT_SELECTED_ROOT)
        if (cleanupUnreachable) try { cleanup(commit.roots) } catch (_: Exception) { }
        return CanonicalReopenResult.Selected(commit)
    }

    fun lookup(query: CanonicalCommitQuery, generationZero: CanonicalStateView): CanonicalCommitLookup = withParentLock {
        try { recoverPointerPublications() } catch (_: Exception) {
            return CanonicalCommitLookup.Refused(CanonicalSelectorRefusal.DURABILITY_FAILURE)
        }
        val selected = reopenLocked(generationZero, cleanupUnreachable = true)
        if (selected is CanonicalReopenResult.Refused) return CanonicalCommitLookup.Refused(selected.reason)
        if (selected is CanonicalReopenResult.GenerationZero) return CanonicalCommitLookup.Absent
        selected as CanonicalReopenResult.Selected
        val match = selected.commit.roots.indexOfFirst { it.isCommand(query.commandId) }
        if (match < 0) { selected.commit.close(); return CanonicalCommitLookup.Absent }
        val root = selected.commit.roots[match]
        if (!root.matches(query)) { selected.commit.close(); return CanonicalCommitLookup.Refused(CanonicalSelectorRefusal.IDENTITY_CONFLICT) }
        val commit = selected.commit.prefix(match + 1)
        selected.commit.detachPrefix(match + 1)
        selected.commit.close()
        return CanonicalCommitLookup.Found(commit)
    }

    fun publish(
        generation: CanonicalCowGeneration,
        generationZero: CanonicalStateView,
        fault: CanonicalSelectorFault?,
        acknowledgedCurrent: PreparedIntentCurrentReceipt? = null,
        authenticatedPrior: CanonicalPublishedCommit? = null,
    ): CanonicalPublishResult = withParentLock {
        try { recoverPointerPublications() } catch (_: Exception) {
            return CanonicalPublishResult.Refused(CanonicalSelectorRefusal.DURABILITY_FAILURE)
        }
        val generationIdentity = CowGenerationIdentity.from(generation.root)
        val priorFromDisk = if (authenticatedPrior == null) {
            val reopened = reopenLocked(generationZero, cleanupUnreachable = false, acknowledgedCurrent)
            if (reopened is CanonicalReopenResult.Refused) return CanonicalPublishResult.Refused(reopened.reason)
            (reopened as? CanonicalReopenResult.Selected)?.commit
        } else null
        val prior = authenticatedPrior ?: priorFromDisk
        val roots = prior?.roots.orEmpty()
        val sameCommand = roots.indexOfFirst { it.isCommand(generation.root.commandId) }
        if (sameCommand >= 0) {
            val existing = roots[sameCommand]
            if (existing.generationHash != generationIdentity.hash) {
                priorFromDisk?.close()
                return CanonicalPublishResult.Refused(CanonicalSelectorRefusal.IDENTITY_CONFLICT)
            }
            val replay = requireNotNull(prior).prefix(sameCommand + 1)
            prior.detachPrefix(sameCommand + 1); prior.close()
            return CanonicalPublishResult.Committed(replay, replayed = true)
        }
        val sourceCut = prior?.view?.cut ?: generationZero.cut
        if (generation.root.baseCut != sourceCut) {
            priorFromDisk?.close()
            return CanonicalPublishResult.Refused(CanonicalSelectorRefusal.STALE_BASE)
        }
        val validated = if (authenticatedPrior != null) generation else
            CanonicalCowGeneration.open(generation.directory, generationIdentity)
        if (validated == null) {
            priorFromDisk?.close()
            return CanonicalPublishResult.Refused(CanonicalSelectorRefusal.CORRUPT_GENERATION)
        }
        if (authenticatedPrior == null) validated.close()
        val ownedDirectory = try {
            generation.directory.parentFile?.canonicalFile == parent.canonicalFile &&
                GENERATION_NAME.matches(generation.directory.name)
        } catch (_: Exception) { false }
        if (!ownedDirectory) {
            priorFromDisk?.close()
            return CanonicalPublishResult.Refused(CanonicalSelectorRefusal.CORRUPT_GENERATION)
        }

        val oldSelector = Selector.read(File(parent, SELECTOR_FILE))
        if (authenticatedPrior != null) {
            val priorRoot = authenticatedPrior.roots.lastOrNull()
            val slot = oldSelector?.let { selectorValue ->
                Slot.read(File(parent, slotName(selectorValue.slot)))?.takeIf {
                    it.revision == selectorValue.revision && it.rootHash == selectorValue.rootHash
                }
            }
            if (priorRoot == null || oldSelector == null || slot == null ||
                oldSelector.rootHash != priorRoot.objectHash() ||
                acknowledgedCurrent != authenticatedPrior.current
            ) return CanonicalPublishResult.Refused(CanonicalSelectorRefusal.CORRUPT_SELECTED_ROOT)
        }
        val revision = try { Math.addExact(oldSelector?.revision ?: 0L, 1L) } catch (_: ArithmeticException) {
            priorFromDisk?.close()
            return CanonicalPublishResult.Refused(CanonicalSelectorRefusal.REVISION_OVERFLOW)
        }
        if (revision > maximumGenerations) {
            priorFromDisk?.close()
            return CanonicalPublishResult.Refused(CanonicalSelectorRefusal.ANCESTRY_LIMIT)
        }
        val root = PublishedRoot.from(revision, oldSelector?.rootHash, generation)
        val rootBytes = root.bytes()
        val rootHash = hash(rootBytes)
        val rootTarget = rootFile(rootHash)
        val inactive = if (oldSelector?.slot == 0) 1 else 0
        val slotTarget = File(parent, slotName(inactive))
        val selectorTarget = File(parent, SELECTOR_FILE)
        val rootBefore = rootTarget.takeIf(File::exists)?.let(budget::allocatedBytes) ?: 0L
        val slotBefore = slotTarget.takeIf(File::exists)?.let(budget::allocatedBytes) ?: 0L
        val selectorBefore = selectorTarget.takeIf(File::exists)?.let(budget::allocatedBytes) ?: 0L
        val slot = Slot(revision, rootHash)
        val selector = Selector(inactive, revision, rootHash)
        val unit = budget.allocationUnitBytes(parent)
        val reservationMaximum = listOf(rootBytes.size.toLong(), rootBytes.size.toLong(), Slot.BYTES.toLong(), Slot.BYTES.toLong(), Selector.BYTES.toLong(), Selector.BYTES.toLong(), 1L, 1L)
            .fold(0L) { total, bytes -> Math.addExact(total, round(bytes, unit)) }
        val scalarPrior = authenticatedPrior?.takeIf { it.scalar }
        val oldGenerationBytes = scalarPrior?.receipt?.let { receipt ->
            Math.addExact(receipt.oldGenerationBytes, receipt.newGenerationBytes)
        } ?: roots.sumOf { budget.allocatedBytes(File(parent, it.generationDirectory)) }
        val newGenerationBytes = budget.allocatedBytes(generation.directory)
        val oldAuthorityBytes = scalarPrior?.receipt?.let {
            Math.addExact(Math.addExact(it.rootObjectBytes, it.rootSlotBytes), it.selectorBytes)
        } ?: parent.listFiles().orEmpty().filter {
            it.name in setOf(SELECTOR_FILE, SLOT_A, SLOT_B) || ROOT_NAME.matches(it.name)
        }.sumOf(budget::allocatedBytes)
        val maximum = Math.addExact(
            Math.addExact(oldGenerationBytes, newGenerationBytes),
            Math.addExact(oldAuthorityBytes, Math.multiplyExact(reservationMaximum, 2L)),
        )
        val commitBytes = Math.max(0L, round(rootBytes.size.toLong(), unit) - rootBefore) +
            Math.max(0L, round(Slot.BYTES.toLong(), unit) - slotBefore) +
            Math.max(0L, round(Selector.BYTES.toLong(), unit) - selectorBefore)
        val receipt = CanonicalPublicationReceipt(
            pointerBytes = rootBytes.size.toLong() + Slot.BYTES + Selector.BYTES,
            hashOperations = 3,
            rootOperations = 3,
            phasePeakBytes = phasePeakBytes(if (scalarPrior != null) 2 else roots.size + 1),
            maximumPhysicalBytes = maximum,
            committedPhysicalBytes = 0,
            oldGenerationBytes = oldGenerationBytes,
            newGenerationBytes = newGenerationBytes,
            reservationAndMetadataBytes = reservationMaximum,
        )
        var token: Any? = null
        var switched = false
        var selected: CanonicalPublishedCommit? = null
        try {
            inject(fault, CanonicalSelectorFault.BEFORE_RESERVATION)
            token = budget.reservePointerPublication(
                rootHash.toByteArray().hex(), inactive, rootBefore, slotBefore, selectorBefore,
                commitBytes, reservationMaximum,
            ) ?: run { priorFromDisk?.close(); return CanonicalPublishResult.Refused(CanonicalSelectorRefusal.QUOTA_REFUSED) }
            inject(fault, CanonicalSelectorFault.AFTER_RESERVATION)
            inject(fault, CanonicalSelectorFault.BEFORE_ROOT_OBJECT_WRITE)
            if (!rootTarget.exists()) writeImmutable(rootTarget, rootBytes, fault == CanonicalSelectorFault.DURING_ROOT_OBJECT_WRITE)
            else require(PublishedRoot.read(rootTarget, rootHash) == root)
            inject(fault, CanonicalSelectorFault.AFTER_ROOT_OBJECT_SYNC)
            inject(fault, CanonicalSelectorFault.BEFORE_INACTIVE_SLOT_WRITE)
            atomicReplace(slotTarget, slot.bytes(), fault == CanonicalSelectorFault.DURING_INACTIVE_SLOT_WRITE)
            inject(fault, CanonicalSelectorFault.AFTER_INACTIVE_SLOT_SYNC)
            inject(fault, CanonicalSelectorFault.PROCESS_CRASH_BEFORE_SELECTOR_SWITCH)
            inject(fault, CanonicalSelectorFault.BEFORE_SELECTOR_SWITCH)
            atomicReplace(selectorTarget, selector.bytes(), fault == CanonicalSelectorFault.DURING_SELECTOR_WRITE)
            switched = true
            inject(fault, CanonicalSelectorFault.AFTER_SELECTOR_SWITCH)
            inject(fault, CanonicalSelectorFault.PROCESS_CRASH_AFTER_SELECTOR_SWITCH)
            inject(fault, CanonicalSelectorFault.BEFORE_PARENT_SYNC)
            sync(parent)
            inject(fault, CanonicalSelectorFault.AFTER_PARENT_SYNC)
            val actual = incrementalBytes(rootTarget, rootBefore) + incrementalBytes(slotTarget, slotBefore) +
                incrementalBytes(selectorTarget, selectorBefore)
            require(actual == commitBytes)
            inject(fault, CanonicalSelectorFault.BEFORE_BUDGET_COMMIT)
            budget.commit(requireNotNull(token), actual); token = null
            inject(fault, CanonicalSelectorFault.AFTER_BUDGET_COMMIT)
            selected = if (authenticatedPrior != null) {
                authenticatedPrior.advance(root, validated, receipt)
            } else requireNotNull(buildCommit(rootHash, generationZero)) { "selected root failed validation" }
            priorFromDisk?.close()
            inject(fault, CanonicalSelectorFault.BEFORE_CLEANUP)
            val reclaimed = if (scalarPrior != null) 0L else cleanup(requireNotNull(selected).roots)
            inject(fault, CanonicalSelectorFault.AFTER_CLEANUP)
            val rootsPhysical = if (scalarPrior != null) {
                Math.addExact(scalarPrior.receipt.rootObjectBytes, budget.allocatedBytes(rootTarget))
            } else requireNotNull(selected).roots.sumOf { budget.allocatedBytes(rootFile(it.objectHash())) }
            val slotsPhysical = listOf(File(parent, SLOT_A), File(parent, SLOT_B)).filter(File::exists).sumOf(budget::allocatedBytes)
            val selectorPhysical = budget.allocatedBytes(selectorTarget)
            val selectedGenerations = if (scalarPrior != null) Math.addExact(oldGenerationBytes, newGenerationBytes)
                else requireNotNull(selected).roots.sumOf { budget.allocatedBytes(File(parent, it.generationDirectory)) }
            val committed = requireNotNull(selected).withReceipt(receipt.copy(
                committedPhysicalBytes = actual,
                rootObjectBytes = rootsPhysical,
                rootSlotBytes = slotsPhysical,
                selectorBytes = selectorPhysical,
                cleanupReclaimedBytes = reclaimed,
                selectedAuthorityBytes = selectedGenerations + rootsPhysical + slotsPhysical + selectorPhysical,
            ))
            selected.close(); selected = null
            return CanonicalPublishResult.Committed(committed, replayed = false)
        } catch (_: Exception) {
            priorFromDisk?.close()
            if (authenticatedPrior != null && !switched) validated.close()
            selected?.close(); selected = null
            return if (switched) CanonicalPublishResult.UnknownAfterSwitch(receipt)
            else CanonicalPublishResult.Refused(CanonicalSelectorRefusal.DURABILITY_FAILURE)
        } finally {
            if (!fault.isProcessCrash()) token?.let { reservation ->
                try {
                    if (switched) {
                        val actual = incrementalBytes(rootTarget, rootBefore) + incrementalBytes(slotTarget, slotBefore) +
                            incrementalBytes(selectorTarget, selectorBefore)
                        budget.commit(reservation, actual)
                    } else {
                        if (rootBefore == 0L && rootTarget.exists()) rootTarget.delete()
                        if (slotBefore == 0L && slotTarget.exists()) slotTarget.delete()
                        budget.release(reservation)
                    }
                } catch (_: Exception) { }
            }
            parent.listFiles().orEmpty().filter { it.name.endsWith(".part") }.forEach(File::delete)
        }
    }

    private fun recoverPointerPublications() {
        val selector = Selector.read(File(parent, SELECTOR_FILE))
        val selectedId = selector?.let { value ->
            Slot.read(File(parent, slotName(value.slot)))
                ?.takeIf { it.revision == value.revision && it.rootHash == value.rootHash }
                ?.let { value.rootHash.toByteArray().hex() }
        }
        budget.pointerPublications().forEach { reservation ->
            val rootHash = receiptFromHex(reservation.publicationId)
            val root = rootFile(rootHash)
            val slot = File(parent, slotName(reservation.slot))
            val selectorFile = File(parent, SELECTOR_FILE)
            if (reservation.publicationId == selectedId) {
                require(PublishedRoot.read(root, rootHash) != null)
                val actual = incrementalBytes(root, reservation.rootBeforeBytes) +
                    incrementalBytes(slot, reservation.slotBeforeBytes) +
                    incrementalBytes(selectorFile, reservation.selectorBeforeBytes)
                require(actual == reservation.commitBytes)
                sync(parent)
                budget.commitPointerPublication(reservation)
            } else {
                if (reservation.rootBeforeBytes == 0L && root.exists()) require(root.delete())
                if (reservation.slotBeforeBytes == 0L && slot.exists() && selector?.slot != reservation.slot) require(slot.delete())
                parent.listFiles().orEmpty().filter { it.name.endsWith(".part") }.forEach(File::delete)
                sync(parent)
                budget.releasePointerPublication(reservation)
            }
        }
    }

    private fun buildCommit(
        selectedHash: CanonicalReceiptBytes,
        generationZero: CanonicalStateView,
        acknowledgedCurrent: PreparedIntentCurrentReceipt? = null,
    ): CanonicalPublishedCommit? {
        val newestFirst = ArrayList<PublishedRoot>()
        val seen = HashSet<CanonicalReceiptBytes>()
        val generations = ArrayList<CanonicalCowGeneration>()
        try {
            var next: CanonicalReceiptBytes? = selectedHash
            while (next != null) {
                require(seen.add(next) && newestFirst.size < maximumGenerations)
                val root = requireNotNull(PublishedRoot.read(rootFile(next), next))
                newestFirst += root; next = root.previousRootHash
            }
            val roots = newestFirst.asReversed()
            var view = generationZero
            for (root in roots) {
                require(root.revision == generations.size + 1L && root.baseRootHash == view.cut.rootHash)
                val directory = File(parent, root.generationDirectory)
                require(requireNotNull(directory.parentFile).canonicalFile == parent.canonicalFile && GENERATION_NAME.matches(directory.name))
                // Every non-newest root crossed this owner-only selector only after its exact
                // current was ACKed; its root-embedded receipt remains the durable validator.
                // The newest root may omit its payload only when activation supplies that exact
                // retained receipt during the crossed-selector repair window.
                val missingAllowed = if (root != roots.last()) root.current else acknowledgedCurrent
                val generation = requireNotNull(CanonicalCowGeneration.open(directory, CowGenerationIdentity(root.generationHash), missingAllowed))
                try {
                    require(root.matches(generation.root) && generation.root.baseCut == view.cut)
                    generations += generation
                    view = generation.overlay(view)
                } catch (error: Exception) {
                    generation.close()
                    throw error
                }
            }
            val selectorBytes = budget.allocatedBytes(File(parent, SELECTOR_FILE))
            val slotBytes = listOf(File(parent, SLOT_A), File(parent, SLOT_B)).filter(File::exists).sumOf(budget::allocatedBytes)
            val rootBytes = roots.sumOf { budget.allocatedBytes(rootFile(it.objectHash())) }
            val generationBytes = roots.sumOf { budget.allocatedBytes(File(parent, it.generationDirectory)) }
            val actual = selectorBytes + slotBytes + rootBytes
            return CanonicalPublishedCommit(
                generationZero, view, roots.last().current, roots, generations,
                CanonicalPublicationReceipt(
                    roots.last().encodedBytes().toLong() + Slot.BYTES + Selector.BYTES,
                    3, 3, phasePeakBytes(roots.size), actual + generationBytes, actual,
                    oldGenerationBytes = generationBytes - budget.allocatedBytes(File(parent, roots.last().generationDirectory)),
                    newGenerationBytes = budget.allocatedBytes(File(parent, roots.last().generationDirectory)),
                    rootObjectBytes = rootBytes,
                    rootSlotBytes = slotBytes,
                    selectorBytes = selectorBytes,
                    selectedAuthorityBytes = actual + generationBytes,
                ),
            )
        } catch (_: Exception) {
            generations.forEach { generation -> try { generation.close() } catch (_: Exception) { } }
            return null
        }
    }

    private fun cleanup(retained: List<PublishedRoot>): Long {
        val rootHashes = retained.mapTo(HashSet()) { it.objectHash() }
        val generations = retained.mapTo(HashSet()) { it.generationDirectory }
        var reclaimed = 0L
        parent.listFiles().orEmpty().forEach { file ->
            when {
                GENERATION_NAME.matches(file.name) && file.name !in generations -> {
                    val opened = CanonicalCowGeneration.open(file)
                    if (opened != null) {
                        opened.close()
                        reclaimed = Math.addExact(reclaimed, budget.reclaimCommittedCandidate(file))
                    }
                }
                ROOT_NAME.matches(file.name) && rootHashFromName(file.name)?.let { it !in rootHashes } == true && PublishedRoot.read(file, requireNotNull(rootHashFromName(file.name))) != null -> file.delete()
            }
        }
        sync(parent)
        return reclaimed
    }

    private fun writeImmutable(file: File, bytes: ByteArray, partial: Boolean) {
        val temporary = File(parent, ".${file.name}.part")
        FileOutputStream(temporary, false).use { output ->
            output.write(if (partial) bytes.copyOf(bytes.size / 2) else bytes); output.fd.sync()
        }
        if (partial) error("injected root write")
        Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE)
        sync(parent)
    }
    private fun atomicReplace(file: File, bytes: ByteArray, partial: Boolean) {
        val temporary = File(parent, ".${file.name}.part")
        FileOutputStream(temporary, false).use { output -> output.write(if (partial) bytes.copyOf(bytes.size / 2) else bytes); output.fd.sync() }
        if (partial) error("injected selector write")
        Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    }
    private fun inject(requested: CanonicalSelectorFault?, point: CanonicalSelectorFault) {
        if (requested == point) {
            if (point.isProcessCrash()) throw CanonicalSimulatedProcessCrash(point)
            error("fault:$point")
        }
    }
    private fun rootFile(hash: CanonicalReceiptBytes) = File(parent, "canonical-surface-selector-root-${hash.toByteArray().hex()}.root")
    private fun incrementalBytes(file: File, before: Long) =
        Math.max(0L, (file.takeIf(File::exists)?.let(budget::allocatedBytes) ?: 0L) - before)
    private fun slotName(slot: Int) = if (slot == 0) SLOT_A else SLOT_B
    private fun rootHashFromName(name: String): CanonicalReceiptBytes? = name.removePrefix("canonical-surface-selector-root-").removeSuffix(".root")
        .takeIf { it.length == 64 }?.chunked(2)?.map { it.toIntOrNull(16)?.toByte() ?: return null }?.toByteArray()?.let(::CanonicalReceiptBytes)
    private fun receiptFromHex(value: String) = CanonicalReceiptBytes(
        value.chunked(2).map { it.toInt(16).toByte() }.toByteArray(),
    )

    companion object {
        private const val SELECTOR_FILE = "canonical-surface-root-selector"
        private const val SLOT_A = "canonical-surface-root-A.slot"
        private const val SLOT_B = "canonical-surface-root-B.slot"
        private const val MAX_GENERATIONS = 1_024
        private const val FIXED_PHASE_BYTES = 98_304L
        private const val ROOT_RUNTIME_BYTES = 512L
        private val GENERATION_NAME = Regex("canonical-surface-cow-command-[0-9a-f]{64}")
        private val ROOT_NAME = Regex("canonical-surface-selector-root-[0-9a-f]{64}\\.root")
        private val locks = mutableMapOf<String, Any>()
        private fun parentLock(parent: File): Any = synchronized(locks) {
            locks.getOrPut(parent.absoluteFile.toPath().normalize().toString()) { Any() }
        }
        private fun round(bytes: Long, unit: Long) = if (bytes == 0L) 0L else Math.multiplyExact((bytes - 1L) / unit + 1L, unit)
        private fun phasePeakBytes(roots: Int) = Math.addExact(FIXED_PHASE_BYTES, Math.multiplyExact(roots.toLong(), ROOT_RUNTIME_BYTES))
        private fun sync(directory: File) {
            if (!System.getProperty("os.name").orEmpty().startsWith("Windows", true))
                FileChannel.open(directory.toPath(), StandardOpenOption.READ).use { it.force(true) }
        }
        private fun hash(bytes: ByteArray) = CanonicalReceiptBytes(MessageDigest.getInstance("SHA-256").digest(bytes))
        private fun ByteArray.hex() = joinToString("") { "%02x".format(it) }
    }

    private inline fun <T> withParentLock(block: () -> T): T = synchronized(parentLock(parent), block)
}

internal data class CanonicalCommitQuery(
    val commandId: String,
    val commandKind: PreparedMutationKind,
    val generationHash: CanonicalReceiptBytes,
    val current: PreparedIntentCurrentReceipt,
) {
    companion object { fun from(generation: CanonicalCowGeneration) = CanonicalCommitQuery(generation.root.commandId, generation.root.commandKind, CowGenerationIdentity.from(generation.root).hash, generation.root.current) }
}

internal data class CanonicalPublicationReceipt(
    val pointerBytes: Long,
    val hashOperations: Int,
    val rootOperations: Int,
    val phasePeakBytes: Long,
    val maximumPhysicalBytes: Long,
    val committedPhysicalBytes: Long,
    val oldGenerationBytes: Long = 0,
    val newGenerationBytes: Long = 0,
    val rootObjectBytes: Long = 0,
    val rootSlotBytes: Long = 0,
    val selectorBytes: Long = 0,
    val reservationAndMetadataBytes: Long = 0,
    val cleanupReclaimedBytes: Long = 0,
    val selectedAuthorityBytes: Long = 0,
)

internal class CanonicalPublishedCommit internal constructor(
    private val generationZero: CanonicalStateView,
    var view: CanonicalStateView,
    val current: PreparedIntentCurrentReceipt,
    internal var roots: List<PublishedRoot>,
    private val generations: MutableList<CanonicalCowGeneration>,
    val receipt: CanonicalPublicationReceipt,
) : AutoCloseable {
    private var closed = false
    internal var scalar = false
        private set
    @Synchronized override fun close() { if (!closed) { closed = true; generations.forEach(CanonicalCowGeneration::close); generations.clear() } }
    internal fun prefix(size: Int): CanonicalPublishedCommit {
        require(size in 1..roots.size)
        var selected: CanonicalStateView = generationZero
        generations.take(size).forEach { selected = it.overlay(selected) }
        return CanonicalPublishedCommit(generationZero, selected, roots[size - 1].current, roots.take(size), generations.take(size).toMutableList(), receipt)
    }
    internal fun detachPrefix(size: Int) { repeat(minOf(size, generations.size)) { generations.removeAt(0) } }
    internal fun withReceipt(value: CanonicalPublicationReceipt): CanonicalPublishedCommit {
        val transferred = generations.toMutableList()
        generations.clear()
        return CanonicalPublishedCommit(generationZero, view, current, roots, transferred, value)
            .also { it.scalar = scalar }
    }
    /** Drops verified historical generations after a named cold recovery. */
    internal fun collapseToScalar(scalarView: CanonicalStateView): CanonicalPublishedCommit {
        check(!closed && scalarView.cut == view.cut && scalarView.generationZeroAuthority === generationZero)
        generations.forEach(CanonicalCowGeneration::close)
        generations.clear()
        roots = listOf(roots.last())
        view = scalarView
        scalar = true
        return this
    }
    /** Detaches a scalar successor with no reference to the cold complete authority. */
    internal fun detachScalar(scalarView: CanonicalStateView): CanonicalPublishedCommit {
        check(!closed && scalarView.cut == view.cut)
        generations.forEach(CanonicalCowGeneration::close)
        generations.clear()
        closed = true
        return CanonicalPublishedCommit(
            scalarView, scalarView, current, listOf(roots.last()), mutableListOf(), receipt,
        ).also { it.scalar = true }
    }
    /** Transfers the one live composed authority to its exact adjacent successor. */
    internal fun advance(
        root: PublishedRoot,
        generation: CanonicalCowGeneration,
        value: CanonicalPublicationReceipt,
    ): CanonicalPublishedCommit {
        check(!closed && root.baseRootHash == view.cut.rootHash && generation.root.baseCut == view.cut)
        val transferred = generations.toMutableList()
        generations.clear()
        val successorView = generation.overlay(view)
        transferred += generation
        closed = true
        return CanonicalPublishedCommit(
            generationZero, successorView, root.current,
            if (scalar) listOf(roots.last(), root) else roots + root,
            transferred, value,
        ).also { it.scalar = scalar }
    }

    internal fun retainedProofBytes(): Long {
        var total = Math.addExact(512L, Math.multiplyExact(roots.size.toLong(), 512L))
        generations.forEach { generation -> total = Math.addExact(total, generation.retainedProofBytes()) }
        return total
    }
}

internal sealed interface CanonicalPublishResult {
    data class Committed(val commit: CanonicalPublishedCommit, val replayed: Boolean) : CanonicalPublishResult
    data class UnknownAfterSwitch(val receipt: CanonicalPublicationReceipt) : CanonicalPublishResult
    data class Refused(val reason: CanonicalSelectorRefusal) : CanonicalPublishResult
}
internal sealed interface CanonicalReopenResult {
    data class GenerationZero(val view: CanonicalStateView) : CanonicalReopenResult
    data class Selected(val commit: CanonicalPublishedCommit) : CanonicalReopenResult
    data class Refused(val reason: CanonicalSelectorRefusal) : CanonicalReopenResult
}
internal sealed interface CanonicalCommitLookup {
    data class Found(val commit: CanonicalPublishedCommit) : CanonicalCommitLookup
    data object Absent : CanonicalCommitLookup
    data class Refused(val reason: CanonicalSelectorRefusal) : CanonicalCommitLookup
}
internal enum class CanonicalSelectorRefusal { CLOSED, STALE_BASE, QUOTA_REFUSED, IDENTITY_CONFLICT, CORRUPT_GENERATION, CORRUPT_SELECTED_ROOT, REVISION_OVERFLOW, ANCESTRY_LIMIT, DURABILITY_FAILURE }
internal enum class CanonicalSelectorFault {
    BEFORE_RESERVATION, AFTER_RESERVATION, BEFORE_ROOT_OBJECT_WRITE, DURING_ROOT_OBJECT_WRITE, AFTER_ROOT_OBJECT_SYNC,
    BEFORE_INACTIVE_SLOT_WRITE, DURING_INACTIVE_SLOT_WRITE, AFTER_INACTIVE_SLOT_SYNC,
    BEFORE_SELECTOR_SWITCH, DURING_SELECTOR_WRITE, AFTER_SELECTOR_SWITCH,
    BEFORE_PARENT_SYNC, AFTER_PARENT_SYNC, BEFORE_BUDGET_COMMIT, AFTER_BUDGET_COMMIT, BEFORE_CLEANUP, AFTER_CLEANUP,
    PROCESS_CRASH_BEFORE_SELECTOR_SWITCH, PROCESS_CRASH_AFTER_SELECTOR_SWITCH,
}

internal class CanonicalSimulatedProcessCrash(point: CanonicalSelectorFault) : Error("process-crash:$point")
private fun CanonicalSelectorFault?.isProcessCrash() = this == CanonicalSelectorFault.PROCESS_CRASH_BEFORE_SELECTOR_SWITCH ||
    this == CanonicalSelectorFault.PROCESS_CRASH_AFTER_SELECTOR_SWITCH

internal data class PublishedRoot(
    val revision: Long,
    val previousRootHash: CanonicalReceiptBytes?,
    val generationDirectoryHash: CanonicalReceiptBytes,
    val generationHash: CanonicalReceiptBytes,
    val commandIdHash: CanonicalReceiptBytes,
    val commandKind: PreparedMutationKind,
    val commandHash: CanonicalReceiptBytes,
    val commandFingerprint: CanonicalReceiptBytes,
    val baseRootHash: CanonicalReceiptBytes,
    val targetRootHash: CanonicalReceiptBytes,
    val current: PreparedIntentCurrentReceipt,
) {
    val generationDirectory get() = "canonical-surface-cow-command-${generationDirectoryHash.toByteArray().hex()}"
    fun isCommand(commandId: String) = commandIdHash == hash(commandId.encodeToByteArray())
    fun matches(query: CanonicalCommitQuery) = isCommand(query.commandId) && commandKind == query.commandKind && generationHash == query.generationHash && current == query.current
    fun matches(root: MutableSemanticRoot) = isCommand(root.commandId) && commandKind == root.commandKind && commandHash == root.commandHash && commandFingerprint == root.commandFingerprint && baseRootHash == root.baseCut.rootHash && targetRootHash == root.targetCut().rootHash && current == root.current && generationHash == CowGenerationIdentity.from(root).hash
    fun encodedBytes() = bytes().size
    fun objectHash() = CanonicalReceiptBytes(MessageDigest.getInstance("SHA-256").digest(bytes()))
    fun bytes(): ByteArray = checkedBytes { out ->
        out.writeInt(MAGIC); out.writeInt(1); out.writeLong(revision); out.writeBoolean(previousRootHash != null)
        out.write(previousRootHash?.toByteArray() ?: ByteArray(32)); out.write(generationDirectoryHash.toByteArray())
        out.write(generationHash.toByteArray()); out.write(commandIdHash.toByteArray()); out.writeInt(commandKind.ordinal)
        out.write(commandHash.toByteArray()); out.write(commandFingerprint.toByteArray()); out.write(baseRootHash.toByteArray()); out.write(targetRootHash.toByteArray())
        out.writeLong(current.length); out.write(current.hash.toByteArray()); repeat(3) { out.writeByte(0) }
    }
    companion object {
        private const val MAGIC = 0x4d335052
        const val BYTES = 352
        fun from(revision: Long, previous: CanonicalReceiptBytes?, generation: CanonicalCowGeneration) = generation.root.let { root ->
            val suffix = generation.directory.name.removePrefix("canonical-surface-cow-command-")
            require(suffix.length == 64)
            PublishedRoot(revision, previous, CanonicalReceiptBytes(suffix.chunked(2).map { it.toInt(16).toByte() }.toByteArray()), CowGenerationIdentity.from(root).hash, hash(root.commandId.encodeToByteArray()), root.commandKind, root.commandHash, root.commandFingerprint, root.baseCut.rootHash, root.targetCut().rootHash, root.current)
        }
        fun read(file: File, expected: CanonicalReceiptBytes): PublishedRoot? = try {
            val bytes = FileInputStream(file).use { it.readBytes() }; require(bytes.size == BYTES && CanonicalReceiptBytes(MessageDigest.getInstance("SHA-256").digest(bytes)) == expected)
            val payload = bytes.copyOf(bytes.size - 32)
            require(MessageDigest.getInstance("SHA-256").digest(payload).contentEquals(bytes.copyOfRange(payload.size, bytes.size)))
            DataInputStream(ByteArrayInputStream(payload)).use { input ->
                require(input.readInt() == MAGIC && input.readInt() == 1); val revision = input.readLong(); require(revision > 0)
                val hasPrevious = input.readBoolean(); val previousBytes = ByteArray(32).also(input::readFully)
                require(hasPrevious || previousBytes.all { it == 0.toByte() }); val previous = previousBytes.takeIf { hasPrevious }?.let(::CanonicalReceiptBytes)
                val directory = CanonicalReceiptBytes(ByteArray(32).also(input::readFully)); val generation = CanonicalReceiptBytes(ByteArray(32).also(input::readFully)); val commandId = CanonicalReceiptBytes(ByteArray(32).also(input::readFully)); val kind = input.readInt(); require(kind in PreparedMutationKind.entries.indices)
                val command = CanonicalReceiptBytes(ByteArray(32).also(input::readFully)); val fingerprint = CanonicalReceiptBytes(ByteArray(32).also(input::readFully)); val base = CanonicalReceiptBytes(ByteArray(32).also(input::readFully)); val target = CanonicalReceiptBytes(ByteArray(32).also(input::readFully)); val current = PreparedIntentCurrentReceipt(input.readLong(), CanonicalReceiptBytes(ByteArray(32).also(input::readFully)))
                repeat(3) { require(input.readUnsignedByte() == 0) }; require(input.read() == -1 && current.length >= 0)
                PublishedRoot(revision, previous, directory, generation, commandId, PreparedMutationKind.entries[kind], command, fingerprint, base, target, current)
            }
        } catch (_: Exception) { null }
        private fun hash(bytes: ByteArray) = CanonicalReceiptBytes(MessageDigest.getInstance("SHA-256").digest(bytes))
    }
}

private data class Slot(val revision: Long, val rootHash: CanonicalReceiptBytes) {
    fun bytes() = checkedBytes { out -> out.writeInt(MAGIC); out.writeInt(1); out.writeLong(revision); out.write(rootHash.toByteArray()) }
    companion object { const val BYTES = 80; private const val MAGIC = 0x4d335053; fun read(file: File) = readChecked(file, BYTES) { input -> require(input.readInt() == MAGIC && input.readInt() == 1); Slot(input.readLong().also { require(it > 0) }, CanonicalReceiptBytes(ByteArray(32).also(input::readFully))) } }
}
private data class Selector(val slot: Int, val revision: Long, val rootHash: CanonicalReceiptBytes) {
    fun bytes() = checkedBytes { out -> out.writeInt(MAGIC); out.writeInt(1); out.writeByte(slot); out.writeLong(revision); out.write(rootHash.toByteArray()); repeat(7) { out.writeByte(0) } }
    companion object { const val BYTES = 88; private const val MAGIC = 0x4d335053 xor 0x10; fun read(file: File) = readChecked(file, BYTES) { input -> require(input.readInt() == MAGIC && input.readInt() == 1); val slot = input.readUnsignedByte(); require(slot in 0..1); Selector(slot, input.readLong().also { require(it > 0) }, CanonicalReceiptBytes(ByteArray(32).also(input::readFully))).also { repeat(7) { require(input.readUnsignedByte() == 0) } } } }
}
private fun checkedBytes(body: (DataOutputStream) -> Unit): ByteArray { val raw = ByteArrayOutputStream(); DataOutputStream(raw).use(body); val bytes = raw.toByteArray(); return bytes + MessageDigest.getInstance("SHA-256").digest(bytes) }
private fun <T> readChecked(file: File, size: Int, body: (DataInputStream) -> T): T? = try { val bytes = file.readBytes(); require(bytes.size == size); val payload = bytes.copyOf(bytes.size - 32); require(MessageDigest.getInstance("SHA-256").digest(payload).contentEquals(bytes.copyOfRange(bytes.size - 32, bytes.size))); DataInputStream(payload.inputStream()).use { input -> body(input).also { require(input.read() == -1) } } } catch (_: Exception) { null }
private fun ByteArray.hex() = joinToString("") { "%02x".format(it) }
