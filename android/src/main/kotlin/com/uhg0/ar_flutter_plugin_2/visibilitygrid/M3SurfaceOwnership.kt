package com.uhg0.ar_flutter_plugin_2.visibilitygrid

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/**
 * Group-local durable owner for M3 surface identities and their storage location.
 *
 * Its interface is intentionally limited to [open], [apply], and [close].  The
 * allocator, durable journal, mutable owner indexes, and persistence adapters
 * are all implementation details.  An accepted result is visible only after a
 * flushed append-only reservation and a durable owner snapshot.
 */
internal class M3SurfaceOwnership private constructor(
    private val group: M3SurfaceGroup,
    private val configuration: M3SurfaceOwnershipConfiguration,
    private val store: M3SurfaceOwnershipStore,
    restored: M3RestoredOwnership,
) {
    private val rowsById = restored.rows.associateByTo(linkedMapOf()) { it.id.value }
    private val idByVoxel = restored.rows.associateTo(linkedMapOf()) { it.voxel to it.id.value }
    private val receipts = restored.receipts.associateByTo(linkedMapOf()) { it.commandHash.hex() }
    private val supportById = restored.supports.mapValuesTo(linkedMapOf()) { it.value.copyOf() }
    private val lineageEdges = restored.lineageEdges.toMutableList()
    private val transactionReceipts = restored.transactionReceipts.associateByTo(linkedMapOf()) { it.commandHash.hex() }
    private var nextHighWater = restored.nextHighWater
    private var geometryRevision = restored.geometryRevision
    private var lineageRevision = restored.lineageRevision
    private var closed = false

    /** Applies one complete candidate set atomically, or returns a typed refusal. */
    @Synchronized
    fun apply(command: M3SurfaceOwnershipCommand): M3SurfaceOwnershipResult {
        if (closed) return M3SurfaceOwnershipResult.Refused(M3SurfaceOwnershipRefusal.CLOSED, snapshot())
        val commandHash = sha256(command.commandId.encodeToByteArray())
        val fingerprint = command.fingerprint()
        receipts[commandHash.hex()]?.let { receipt ->
            return if (receipt.fingerprint.contentEquals(fingerprint)) receipt.result else {
                M3SurfaceOwnershipResult.Refused(M3SurfaceOwnershipRefusal.IDENTITY_CONFLICT, snapshot())
            }
        }
        if (commandHash.hex() in transactionReceipts) {
            return M3SurfaceOwnershipResult.Refused(M3SurfaceOwnershipRefusal.IDENTITY_CONFLICT, snapshot())
        }
        val preparation = prepare(command, commandHash)
        if (preparation is M3Preparation.Refused) return M3SurfaceOwnershipResult.Refused(preparation.reason, snapshot())
        preparation as M3Preparation.Accepted

        // Reservation is the authority cut. Any error after this point burns its
        // range; no row becomes visible unless the complete owner snapshot lands.
        if (preparation.newCount > 0) {
            val end = checkedEnd(nextHighWater, preparation.newCount)
                ?: return M3SurfaceOwnershipResult.Refused(M3SurfaceOwnershipRefusal.EXHAUSTED, snapshot())
            val reservation = M3Reservation(
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
            } catch (_: M3OwnershipFault) {
                nextHighWater = end
                return M3SurfaceOwnershipResult.Refused(M3SurfaceOwnershipRefusal.DURABILITY_FAILURE, snapshot())
            }
            nextHighWater = end
        }

        val stagedRows = preparation.rows
        val nextRows = rowsById.toMutableMap()
        preparation.changedRows.forEach { nextRows[it.id.value] = it }
        val accepted = M3SurfaceOwnershipResult.Accepted(
            stagedRows.toList(),
            M3SurfaceOwnershipReceipt(nextHighWater, nextRows.size, stagedRows.size, preparation.newCount),
        )
        val receipt = M3StoredReceipt(commandHash, fingerprint, accepted)
        try {
            val nextSupports = supportById.toMutableMap()
            preparation.changedRows.forEach { nextSupports.putIfAbsent(it.id.value, longArrayOf(it.id.value)) }
            store.writeSnapshot(currentSnapshot(nextRows.values.toList(), receipts.values.toList() + receipt, supports = nextSupports))
        } catch (_: M3OwnershipFault) {
            return M3SurfaceOwnershipResult.Refused(M3SurfaceOwnershipRefusal.DURABILITY_FAILURE, snapshot())
        }
        preparation.changedRows.forEach { row ->
            rowsById[row.id.value] = row
            idByVoxel.entries.removeIf { it.value == row.id.value && it.key != row.voxel }
            idByVoxel[row.voxel] = row.id.value
            supportById.putIfAbsent(row.id.value, longArrayOf(row.id.value))
        }
        receipts[commandHash.hex()] = receipt
        return accepted
    }

    /** Commits one complete canonical refinement behind the same group lock and durable root. */
    @Synchronized
    fun transact(command: M3CanonicalTransactionCommand): M3CanonicalTransactionResult {
        if (closed) return canonicalRefusal(M3CanonicalTransactionRefusal.CLOSED)
        val commandHash = sha256(command.commandId.encodeToByteArray())
        val fingerprint = command.fingerprint()
        transactionReceipts[commandHash.hex()]?.let { stored ->
            return if (stored.fingerprint.contentEquals(fingerprint)) stored.result
            else canonicalRefusal(M3CanonicalTransactionRefusal.IDENTITY_CONFLICT)
        }
        if (commandHash.hex() in receipts) return canonicalRefusal(M3CanonicalTransactionRefusal.IDENTITY_CONFLICT)
        val prepared = prepareCanonical(command, commandHash)
        if (prepared is M3CanonicalPreparation.Refused) return canonicalRefusal(prepared.reason)
        prepared as M3CanonicalPreparation.Accepted

        if (prepared.allocatedCount > 0) {
            val end = checkedEnd(nextHighWater, prepared.allocatedCount)
                ?: return canonicalRefusal(M3CanonicalTransactionRefusal.EXHAUSTED)
            val reservation = M3Reservation(
                store.lastReservationRevision + 1, nextHighWater, end, group.hash,
                commandHash, fingerprint, store.lastReservationHash,
            )
            try {
                store.appendReservation(reservation)
            } catch (_: M3OwnershipFault) {
                nextHighWater = end
                return canonicalRefusal(M3CanonicalTransactionRefusal.DURABILITY_FAILURE)
            }
            nextHighWater = end
        }

        if (store.fault == M3SurfaceOwnershipFault.AFTER_PRIVATE_MUTATION) {
            return canonicalRefusal(M3CanonicalTransactionRefusal.DURABILITY_FAILURE)
        }
        val nextRows = rowsById.toMutableMap()
        val nextVoxels = idByVoxel.toMutableMap()
        prepared.removedIds.forEach { id ->
            nextRows.remove(id)?.let { nextVoxels.remove(it.voxel) }
        }
        prepared.targets.forEach { row -> nextRows[row.id.value] = row; nextVoxels[row.voxel] = row.id.value }
        val nextSupports = supportById.toMutableMap()
        prepared.removedIds.forEach(nextSupports::remove)
        prepared.targets.forEach { nextSupports[it.id.value] = prepared.sourceSupport.copyOf() }
        val nextEdges = lineageEdges + prepared.edges
        val nextGeometryRevision = geometryRevision + 1
        val nextLineageRevision = lineageRevision + 1
        val result = M3CanonicalTransactionResult.Accepted(
            targets = prepared.targets,
            receipt = M3CanonicalTransactionReceipt(
                commandId = command.commandId,
                kind = command.kind,
                removedSurfaceIds = prepared.removedIds.map(::M3SurfaceId),
                lineageEdges = prepared.edges,
                geometryRevision = nextGeometryRevision,
                lineageRevision = nextLineageRevision,
                nextSurfaceIdHighWater = nextHighWater,
                liveSurfaceCount = nextRows.size,
            ),
        )
        val stored = M3StoredCanonicalReceipt(commandHash, fingerprint, result)
        try {
            store.writeSnapshot(currentSnapshot(
                rows = nextRows.values.sortedBy { it.id.value },
                supports = nextSupports,
                edges = nextEdges,
                canonicalReceipts = transactionReceipts.values.toList() + stored,
                geometry = nextGeometryRevision,
                lineage = nextLineageRevision,
            ))
        } catch (_: M3OwnershipFault) {
            return canonicalRefusal(M3CanonicalTransactionRefusal.DURABILITY_FAILURE)
        }
        rowsById.clear(); rowsById.putAll(nextRows)
        idByVoxel.clear(); idByVoxel.putAll(nextVoxels)
        supportById.clear(); supportById.putAll(nextSupports)
        lineageEdges.clear(); lineageEdges.addAll(nextEdges)
        geometryRevision = nextGeometryRevision
        lineageRevision = nextLineageRevision
        transactionReceipts[commandHash.hex()] = stored
        return result
    }

    /** Idempotently closes the owner; later calls are deterministic refusals. */
    @Synchronized
    fun close(): M3SurfaceOwnershipCloseResult {
        if (closed) return M3SurfaceOwnershipCloseResult.AlreadyClosed
        closed = true
        store.close()
        return M3SurfaceOwnershipCloseResult.Closed
    }

    private fun prepare(command: M3SurfaceOwnershipCommand, commandHash: ByteArray): M3Preparation {
        if (command.commandId.isBlank() || command.commandId.encodeToByteArray().size > MAX_COMMAND_BYTES || command.candidates.isEmpty()) {
            return M3Preparation.Refused(M3SurfaceOwnershipRefusal.INVALID_COMMAND)
        }
        val requestedAllocations = command.candidates.count { it.id == null }
        if (checkedEnd(nextHighWater, requestedAllocations) == null) {
            return M3Preparation.Refused(M3SurfaceOwnershipRefusal.EXHAUSTED)
        }
        val seenIds = hashSetOf<Long>()
        val seenVoxels = hashSetOf<M3Voxel>()
        val changed = ArrayList<M3SurfaceOwner>(command.candidates.size)
        var allocated = 0
        command.candidates.forEach { candidate ->
            val ownership = ownershipFor(candidate.voxel) ?: return M3Preparation.Refused(M3SurfaceOwnershipRefusal.INVALID_OWNERSHIP)
            val packed = packNormal(candidate.normalOctX, candidate.normalOctY, candidate.normalConfidence)
                ?: return M3Preparation.Refused(M3SurfaceOwnershipRefusal.INVALID_NORMAL)
            val existing = candidate.id?.let { rowsById[it] }
            if (candidate.id != null && (candidate.id == 0L || existing == null || !seenIds.add(candidate.id))) {
                return M3Preparation.Refused(M3SurfaceOwnershipRefusal.UNKNOWN_IDENTITY)
            }
            if (!seenVoxels.add(candidate.voxel)) return M3Preparation.Refused(M3SurfaceOwnershipRefusal.OWNERSHIP_CONFLICT)
            val occupied = idByVoxel[candidate.voxel]
            if (occupied != null && occupied != candidate.id) return M3Preparation.Refused(M3SurfaceOwnershipRefusal.OWNERSHIP_CONFLICT)
            val id = candidate.id ?: run {
                allocated++
                nextHighWater + allocated - 1
            }
            changed += M3SurfaceOwner(
                id = M3SurfaceId(id), group = group, voxel = candidate.voxel,
                region = ownership.region, page = ownership.page,
                packedNormal = packed.first, normalConfidence = packed.second,
                allocatedBy = commandHash,
            )
        }
        if (rowsById.size - command.candidates.count { it.id != null } + changed.size > configuration.surfaceCapacity) {
            return M3Preparation.Refused(M3SurfaceOwnershipRefusal.CAPACITY)
        }
        return M3Preparation.Accepted(changed, allocated)
    }

    private fun prepareCanonical(command: M3CanonicalTransactionCommand, commandHash: ByteArray): M3CanonicalPreparation {
        if (command.commandId.isBlank() || command.commandId.encodeToByteArray().size > MAX_COMMAND_BYTES ||
            command.sourceIds.isEmpty() || command.targets.isEmpty()) {
            return M3CanonicalPreparation.Refused(M3CanonicalTransactionRefusal.INVALID_COMMAND)
        }
        if (command.expectedGeometryRevision != geometryRevision || command.expectedLineageRevision != lineageRevision) {
            return M3CanonicalPreparation.Refused(M3CanonicalTransactionRefusal.REVISION_CONFLICT)
        }
        val sourceIds = command.sourceIds.map { it.value }
        if (sourceIds.toSet().size != sourceIds.size || sourceIds.any { it !in rowsById }) {
            return M3CanonicalPreparation.Refused(M3CanonicalTransactionRefusal.UNKNOWN_IDENTITY)
        }
        val shapeValid = when (command.kind) {
            M3CanonicalOperation.RELOCATION -> sourceIds.size == 1 && command.targets.size == 1 && command.targets.single().id?.value == sourceIds.single()
            M3CanonicalOperation.MERGE -> sourceIds.size >= 2 && command.targets.size == 1 && command.targets.single().id == null
            M3CanonicalOperation.SPLIT -> sourceIds.size == 1 && command.targets.size >= 2 && command.targets.all { it.id == null }
            M3CanonicalOperation.REPLACEMENT -> command.targets.all { it.id == null }
        }
        if (!shapeValid) return M3CanonicalPreparation.Refused(M3CanonicalTransactionRefusal.INVALID_COMMAND)
        if (geometryRevision >= configuration.revisionLimit || lineageRevision >= configuration.revisionLimit) {
            return M3CanonicalPreparation.Refused(M3CanonicalTransactionRefusal.REVISION_EXHAUSTED)
        }
        val allocations = command.targets.count { it.id == null }
        if (checkedEnd(nextHighWater, allocations) == null) return M3CanonicalPreparation.Refused(M3CanonicalTransactionRefusal.EXHAUSTED)
        val finalCount = rowsById.size - sourceIds.size + command.targets.size
        if (finalCount > configuration.surfaceCapacity) return M3CanonicalPreparation.Refused(M3CanonicalTransactionRefusal.CAPACITY)
        if (transactionReceipts.size >= configuration.transactionCapacity) return M3CanonicalPreparation.Refused(M3CanonicalTransactionRefusal.JOURNAL_EXHAUSTED)

        val sourceSupport = sourceIds.flatMap { (supportById[it] ?: longArrayOf(it)).asIterable() }.distinct().sorted().toLongArray()
        val edgeCount = sourceSupport.size.toLong() * command.targets.size
        if (edgeCount > Int.MAX_VALUE || lineageEdges.size + edgeCount > configuration.lineageCapacity) {
            return M3CanonicalPreparation.Refused(M3CanonicalTransactionRefusal.LINEAGE_EXHAUSTED)
        }
        val retainedJournalBytes = transactionReceipts.values.sumOf { it.result.conservativeJournalBytes() }
        val proposedJournalBytes = 256L + command.targets.size * 128L + sourceIds.size * 8L + edgeCount * 16L
        if (retainedJournalBytes + proposedJournalBytes > configuration.changeJournalByteCapacity) {
            return M3CanonicalPreparation.Refused(M3CanonicalTransactionRefusal.JOURNAL_EXHAUSTED)
        }
        val vacated = sourceIds.toSet()
        val seenVoxels = hashSetOf<M3Voxel>()
        var nextAllocation = nextHighWater
        val targets = ArrayList<M3SurfaceOwner>(command.targets.size)
        command.targets.forEach { target ->
            val location = locationFor(configuration, target.voxel)
                ?: return M3CanonicalPreparation.Refused(M3CanonicalTransactionRefusal.INVALID_OWNERSHIP)
            val packed = packNormal(target.normalOctX, target.normalOctY, target.normalConfidence)
                ?: return M3CanonicalPreparation.Refused(M3CanonicalTransactionRefusal.INVALID_NORMAL)
            if (!seenVoxels.add(target.voxel)) return M3CanonicalPreparation.Refused(M3CanonicalTransactionRefusal.OWNERSHIP_CONFLICT)
            val occupied = idByVoxel[target.voxel]
            if (occupied != null && occupied !in vacated) return M3CanonicalPreparation.Refused(M3CanonicalTransactionRefusal.OWNERSHIP_CONFLICT)
            val id = target.id?.value ?: nextAllocation++
            if (target.id != null && id !in vacated) return M3CanonicalPreparation.Refused(M3CanonicalTransactionRefusal.UNKNOWN_IDENTITY)
            targets += M3SurfaceOwner(M3SurfaceId(id), group, target.voxel, location.region, location.page,
                packed.first, packed.second, commandHash)
        }
        val edges = targets.flatMap { target -> sourceSupport.map { source -> M3LineageEdge(M3SurfaceId(source), target.id) } }
            .sortedWith(compareBy({ it.source.value }, { it.target.value }))
        return M3CanonicalPreparation.Accepted(sourceIds.sorted(), targets.sortedBy { it.id.value }, sourceSupport, edges, allocations)
    }

    private fun canonicalRefusal(reason: M3CanonicalTransactionRefusal) = M3CanonicalTransactionResult.Refused(
        reason, M3CanonicalStateReceipt(geometryRevision, lineageRevision, nextHighWater, rowsById.size),
    )

    private fun currentSnapshot(
        rows: List<M3SurfaceOwner> = rowsById.values.toList(),
        ownershipReceipts: List<M3StoredReceipt> = receipts.values.toList(),
        supports: Map<Long, LongArray> = supportById,
        edges: List<M3LineageEdge> = lineageEdges,
        canonicalReceipts: List<M3StoredCanonicalReceipt> = transactionReceipts.values.toList(),
        geometry: Long = geometryRevision,
        lineage: Long = lineageRevision,
    ) = M3OwnershipSnapshot(nextHighWater, rows, ownershipReceipts, supports, edges, canonicalReceipts, geometry, lineage)

    private fun snapshot(resultRows: Int = 0, allocated: Int = 0) = M3SurfaceOwnershipReceipt(
        nextSurfaceIdHighWater = nextHighWater,
        liveSurfaceCount = rowsById.size,
        resultRowCount = resultRows,
        allocatedCount = allocated,
    )

    private fun ownershipFor(voxel: M3Voxel): M3Location? = locationFor(configuration, voxel)

    private fun checkedEnd(start: Long, count: Int): Long? {
        if (count == 0) return start
        if (start !in 1..MAX_HIGH_WATER || count < 0) return null
        val end = start + count.toLong()
        return end.takeIf { it <= MAX_HIGH_WATER }
    }

    private sealed interface M3Preparation {
        data class Accepted(val changedRows: List<M3SurfaceOwner>, val newCount: Int) : M3Preparation { val rows get() = changedRows }
        data class Refused(val reason: M3SurfaceOwnershipRefusal) : M3Preparation
    }

    private sealed interface M3CanonicalPreparation {
        data class Accepted(
            val removedIds: List<Long>, val targets: List<M3SurfaceOwner>, val sourceSupport: LongArray,
            val edges: List<M3LineageEdge>, val allocatedCount: Int,
        ) : M3CanonicalPreparation
        data class Refused(val reason: M3CanonicalTransactionRefusal) : M3CanonicalPreparation
    }

    companion object {
        private const val MAX_COMMAND_BYTES = 256
        private const val MAX_HIGH_WATER = 0x1_0000_0000L

        fun open(
            group: M3SurfaceGroup,
            directory: File,
            configuration: M3SurfaceOwnershipConfiguration = M3SurfaceOwnershipConfiguration(),
            fault: M3SurfaceOwnershipFault? = null,
        ): M3SurfaceOwnershipOpenResult = try {
            open(group, configuration, M3FileSurfaceOwnershipStore(directory, group, fault))
        } catch (failure: M3RestoreFailure) {
            M3SurfaceOwnershipOpenResult.Refused(failure.reason)
        }

        fun inMemory(
            group: M3SurfaceGroup,
            configuration: M3SurfaceOwnershipConfiguration = M3SurfaceOwnershipConfiguration(),
            fault: M3SurfaceOwnershipFault? = null,
        ): M3SurfaceOwnershipOpenResult = open(group, configuration, M3MemorySurfaceOwnershipStore(group, fault))

        private fun open(group: M3SurfaceGroup, configuration: M3SurfaceOwnershipConfiguration, store: M3SurfaceOwnershipStore): M3SurfaceOwnershipOpenResult {
            if (!configuration.isValid) return M3SurfaceOwnershipOpenResult.Refused(M3SurfaceOwnershipRestoreRefusal.INVALID_CONFIGURATION)
            return try {
                val restored = store.restore(configuration)
                M3SurfaceOwnershipOpenResult.Opened(M3SurfaceOwnership(group, configuration, store, restored))
            } catch (failure: M3RestoreFailure) {
                store.close()
                M3SurfaceOwnershipOpenResult.Refused(failure.reason)
            }
        }
    }
}

internal data class M3SurfaceGroup(val value: String) {
    init { require(value.isNotBlank() && value.encodeToByteArray().size <= 128) }
    internal val hash: ByteArray get() = sha256(value.encodeToByteArray())
}

internal data class M3SurfaceOwnershipConfiguration(
    val voxelMicrometers: Int = 100_000,
    val regionMicrometers: Int = 3_000_000,
    val pageMicrometers: Int = 1_000_000,
    val surfaceCapacity: Int = 100_000,
    val receiptCapacity: Int = 100_000,
    val transactionCapacity: Int = 100_000,
    val lineageCapacity: Int = 200_000,
    val changeJournalByteCapacity: Int = 1_048_576,
    val revisionLimit: Long = Long.MAX_VALUE,
) { internal val isValid get() = voxelMicrometers > 0 && regionMicrometers > 0 && pageMicrometers > 0 && regionMicrometers % voxelMicrometers == 0 && pageMicrometers % voxelMicrometers == 0 && regionMicrometers == pageMicrometers * 3 && surfaceCapacity > 0 && receiptCapacity > 0 && transactionCapacity > 0 && lineageCapacity > 0 && changeJournalByteCapacity > 0 && revisionLimit >= 0 }

internal data class M3SurfaceOwnershipCommand(val commandId: String, val candidates: List<M3SurfaceCandidate>) {
    internal fun fingerprint(): ByteArray = sha256(buildString {
        append(commandId).append('|')
        candidates.forEach { append(it.id).append(':').append(it.voxel).append(':').append(it.normalOctX).append(':').append(it.normalOctY).append(':').append(it.normalConfidence).append(';') }
    }.encodeToByteArray())
}
internal data class M3SurfaceCandidate(
    val id: Long? = null,
    val voxel: M3Voxel,
    val normalOctX: Int,
    val normalOctY: Int,
    val normalConfidence: Int,
)
internal data class M3Voxel(val x: Int, val y: Int, val z: Int)
internal data class M3SurfaceId(val value: Long) { init { require(value in 1 until 0x1_0000_0000L) } }
internal data class M3StorageRegion(val x: Int, val y: Int, val z: Int)
internal data class M3SurfaceOwner(val id: M3SurfaceId, val group: M3SurfaceGroup, val voxel: M3Voxel, val region: M3StorageRegion, val page: Int, val packedNormal: Int, val normalConfidence: Int, internal val allocatedBy: ByteArray) {
    val reliabilityBand: M3NormalReliabilityBand get() = when (normalConfidence) {
        0 -> M3NormalReliabilityBand.UNKNOWN
        in 1..63 -> M3NormalReliabilityBand.WEAK
        in 64..191 -> M3NormalReliabilityBand.RELIABLE
        else -> M3NormalReliabilityBand.STRONG
    }
    override fun equals(other: Any?): Boolean = other is M3SurfaceOwner && id == other.id && group == other.group &&
        voxel == other.voxel && region == other.region && page == other.page && packedNormal == other.packedNormal &&
        normalConfidence == other.normalConfidence && allocatedBy.contentEquals(other.allocatedBy)
    override fun hashCode(): Int = listOf(id, group, voxel, region, page, packedNormal, normalConfidence).hashCode() * 31 + allocatedBy.contentHashCode()
}
internal enum class M3NormalReliabilityBand { UNKNOWN, WEAK, RELIABLE, STRONG }
internal data class M3SurfaceOwnershipReceipt(val nextSurfaceIdHighWater: Long, val liveSurfaceCount: Int, val resultRowCount: Int, val allocatedCount: Int)
internal sealed interface M3SurfaceOwnershipResult { data class Accepted(val owners: List<M3SurfaceOwner>, val receipt: M3SurfaceOwnershipReceipt) : M3SurfaceOwnershipResult; data class Refused(val reason: M3SurfaceOwnershipRefusal, val receipt: M3SurfaceOwnershipReceipt) : M3SurfaceOwnershipResult }
internal enum class M3SurfaceOwnershipRefusal { CLOSED, INVALID_COMMAND, INVALID_OWNERSHIP, INVALID_NORMAL, UNKNOWN_IDENTITY, OWNERSHIP_CONFLICT, CAPACITY, EXHAUSTED, IDENTITY_CONFLICT, DURABILITY_FAILURE }
internal enum class M3CanonicalOperation { RELOCATION, MERGE, SPLIT, REPLACEMENT }
internal data class M3CanonicalTarget(
    val id: M3SurfaceId? = null,
    val voxel: M3Voxel,
    val normalOctX: Int,
    val normalOctY: Int,
    val normalConfidence: Int,
)
internal data class M3CanonicalTransactionCommand(
    val commandId: String,
    val kind: M3CanonicalOperation,
    val expectedGeometryRevision: Long,
    val expectedLineageRevision: Long,
    val sourceIds: List<M3SurfaceId>,
    val targets: List<M3CanonicalTarget>,
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
internal data class M3LineageEdge(val source: M3SurfaceId, val target: M3SurfaceId)
internal data class M3CanonicalStateReceipt(
    val geometryRevision: Long, val lineageRevision: Long, val nextSurfaceIdHighWater: Long, val liveSurfaceCount: Int,
)
internal data class M3CanonicalTransactionReceipt(
    val commandId: String,
    val kind: M3CanonicalOperation,
    val removedSurfaceIds: List<M3SurfaceId>,
    val lineageEdges: List<M3LineageEdge>,
    val geometryRevision: Long,
    val lineageRevision: Long,
    val nextSurfaceIdHighWater: Long,
    val liveSurfaceCount: Int,
)
internal sealed interface M3CanonicalTransactionResult {
    data class Accepted(val targets: List<M3SurfaceOwner>, val receipt: M3CanonicalTransactionReceipt) : M3CanonicalTransactionResult
    data class Refused(val reason: M3CanonicalTransactionRefusal, val receipt: M3CanonicalStateReceipt) : M3CanonicalTransactionResult
}
internal enum class M3CanonicalTransactionRefusal {
    CLOSED, INVALID_COMMAND, INVALID_OWNERSHIP, INVALID_NORMAL, UNKNOWN_IDENTITY, OWNERSHIP_CONFLICT,
    CAPACITY, EXHAUSTED, REVISION_CONFLICT, REVISION_EXHAUSTED, LINEAGE_EXHAUSTED, JOURNAL_EXHAUSTED,
    IDENTITY_CONFLICT, DURABILITY_FAILURE,
}
internal sealed interface M3SurfaceOwnershipOpenResult { data class Opened(val ownership: M3SurfaceOwnership) : M3SurfaceOwnershipOpenResult; data class Refused(val reason: M3SurfaceOwnershipRestoreRefusal) : M3SurfaceOwnershipOpenResult }
internal enum class M3SurfaceOwnershipRestoreRefusal { INVALID_CONFIGURATION, CORRUPT, FORK }
internal sealed interface M3SurfaceOwnershipCloseResult { data object Closed : M3SurfaceOwnershipCloseResult; data object AlreadyClosed : M3SurfaceOwnershipCloseResult }
internal enum class M3SurfaceOwnershipFault { AFTER_RESERVATION_FLUSH, AFTER_PRIVATE_MUTATION, BEFORE_SNAPSHOT_FLUSH, AFTER_SNAPSHOT_FLUSH }

private data class M3Location(val region: M3StorageRegion, val page: Int)
private data class M3Reservation(val revision: Long, val start: Long, val endExclusive: Long, val groupHash: ByteArray, val commandHash: ByteArray, val fingerprint: ByteArray, val previousHash: ByteArray) { val recordHash: ByteArray get() = sha256(bytesWithoutHash())
    fun bytesWithoutHash(): ByteArray = ByteArrayOutputStream().use { output -> DataOutputStream(output).use { data -> data.writeInt(0x4d33524c); data.writeInt(1); data.writeLong(revision); data.writeLong(start); data.writeLong(endExclusive); data.write(groupHash); data.write(commandHash); data.write(fingerprint); data.write(previousHash); output.toByteArray() } }
}
private data class M3StoredReceipt(val commandHash: ByteArray, val fingerprint: ByteArray, val result: M3SurfaceOwnershipResult.Accepted)
private data class M3StoredCanonicalReceipt(val commandHash: ByteArray, val fingerprint: ByteArray, val result: M3CanonicalTransactionResult.Accepted)
private fun M3CanonicalTransactionResult.Accepted.conservativeJournalBytes(): Long =
    256L + targets.size * 128L + receipt.removedSurfaceIds.size * 8L + receipt.lineageEdges.size * 16L
private data class M3OwnershipSnapshot(
    val nextHighWater: Long, val rows: List<M3SurfaceOwner>, val receipts: List<M3StoredReceipt>,
    val supports: Map<Long, LongArray> = emptyMap(), val lineageEdges: List<M3LineageEdge> = emptyList(),
    val transactionReceipts: List<M3StoredCanonicalReceipt> = emptyList(),
    val geometryRevision: Long = 0, val lineageRevision: Long = 0,
)
private data class M3RestoredOwnership(
    val nextHighWater: Long, val rows: List<M3SurfaceOwner>, val receipts: List<M3StoredReceipt>,
    val supports: Map<Long, LongArray>, val lineageEdges: List<M3LineageEdge>,
    val transactionReceipts: List<M3StoredCanonicalReceipt>, val geometryRevision: Long, val lineageRevision: Long,
)
private class M3OwnershipFault : RuntimeException()
private class M3RestoreFailure(val reason: M3SurfaceOwnershipRestoreRefusal) : RuntimeException()

private interface M3SurfaceOwnershipStore { val fault: M3SurfaceOwnershipFault?; val lastReservationRevision: Long; val lastReservationHash: ByteArray; fun appendReservation(reservation: M3Reservation); fun writeSnapshot(snapshot: M3OwnershipSnapshot); fun restore(configuration: M3SurfaceOwnershipConfiguration): M3RestoredOwnership; fun close() }

private class M3MemorySurfaceOwnershipStore(private val group: M3SurfaceGroup, override val fault: M3SurfaceOwnershipFault?) : M3SurfaceOwnershipStore {
    private val reservations = mutableListOf<M3Reservation>(); private var snapshot = M3OwnershipSnapshot(1, emptyList(), emptyList())
    override val lastReservationRevision get() = reservations.lastOrNull()?.revision ?: 0L
    override val lastReservationHash get() = reservations.lastOrNull()?.recordHash ?: ByteArray(32)
    override fun appendReservation(reservation: M3Reservation) { reservations += reservation; if (fault == M3SurfaceOwnershipFault.AFTER_RESERVATION_FLUSH) throw M3OwnershipFault() }
    override fun writeSnapshot(snapshot: M3OwnershipSnapshot) { if (fault == M3SurfaceOwnershipFault.BEFORE_SNAPSHOT_FLUSH) throw M3OwnershipFault(); this.snapshot = snapshot }
    override fun restore(configuration: M3SurfaceOwnershipConfiguration) = validate(group, configuration, reservations, snapshot)
    override fun close() = Unit
}

private class M3FileSurfaceOwnershipStore(directory: File, private val group: M3SurfaceGroup, override val fault: M3SurfaceOwnershipFault?) : M3SurfaceOwnershipStore {
    private val prefix = sha256(group.value.encodeToByteArray()).hex(); private val ledger = File(directory, "m3-surface-$prefix.ledger"); private val snapshot = File(directory, "m3-surface-$prefix.snapshot")
    private var reservations: List<M3Reservation> = readLedger(ledger)
    override val lastReservationRevision get() = reservations.lastOrNull()?.revision ?: 0L
    override val lastReservationHash get() = reservations.lastOrNull()?.recordHash ?: ByteArray(32)
    init { directory.mkdirs(); if (!directory.isDirectory) throw M3RestoreFailure(M3SurfaceOwnershipRestoreRefusal.CORRUPT) }
    override fun appendReservation(reservation: M3Reservation) { FileOutputStream(ledger, true).use { out -> out.write(reservation.bytesWithoutHash()); out.write(reservation.recordHash); out.fd.sync() }; reservations = reservations + reservation; if (fault == M3SurfaceOwnershipFault.AFTER_RESERVATION_FLUSH) throw M3OwnershipFault() }
    override fun writeSnapshot(snapshot: M3OwnershipSnapshot) { if (fault == M3SurfaceOwnershipFault.BEFORE_SNAPSHOT_FLUSH) throw M3OwnershipFault(); val bytes = encodeSnapshot(snapshot); val temp = File("${this.snapshot.path}.tmp"); FileOutputStream(temp).use { out -> out.write(bytes); out.fd.sync() }; try { Files.move(temp.toPath(), this.snapshot.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING) } catch (_: AtomicMoveNotSupportedException) { Files.move(temp.toPath(), this.snapshot.toPath(), StandardCopyOption.REPLACE_EXISTING) } }
    override fun restore(configuration: M3SurfaceOwnershipConfiguration): M3RestoredOwnership = validate(group, configuration, reservations, if (snapshot.exists()) decodeSnapshot(snapshot.readBytes(), configuration) else M3OwnershipSnapshot(1, emptyList(), emptyList()))
    override fun close() = Unit
}

private fun validate(group: M3SurfaceGroup, configuration: M3SurfaceOwnershipConfiguration, reservations: List<M3Reservation>, snapshot: M3OwnershipSnapshot): M3RestoredOwnership {
    var highWater = 1L; var previous = ByteArray(32); var revision = 0L
    reservations.forEach { record ->
        if (record.revision != ++revision || record.start != highWater || record.endExclusive <= record.start || !record.groupHash.contentEquals(group.hash) || !record.previousHash.contentEquals(previous) || record.recordHash.contentEquals(ByteArray(32))) throw M3RestoreFailure(M3SurfaceOwnershipRestoreRefusal.CORRUPT)
        if (record.endExclusive > 0x1_0000_0000L) throw M3RestoreFailure(M3SurfaceOwnershipRestoreRefusal.CORRUPT)
        highWater = record.endExclusive; previous = record.recordHash
    }
    if (snapshot.nextHighWater !in 1..highWater) throw M3RestoreFailure(M3SurfaceOwnershipRestoreRefusal.FORK)
    if (highWater > 0x1_0000_0000L) throw M3RestoreFailure(M3SurfaceOwnershipRestoreRefusal.CORRUPT)
    if (snapshot.rows.size > configuration.surfaceCapacity || snapshot.receipts.size > configuration.receiptCapacity ||
        snapshot.transactionReceipts.size > configuration.transactionCapacity || snapshot.lineageEdges.size > configuration.lineageCapacity ||
        snapshot.geometryRevision < 0 || snapshot.lineageRevision < 0) throw M3RestoreFailure(M3SurfaceOwnershipRestoreRefusal.CORRUPT)
    val ids = hashSetOf<Long>(); val voxels = hashSetOf<M3Voxel>()
    snapshot.rows.forEach { row -> if (row.group != group || row.id.value !in 1 until snapshot.nextHighWater || !ids.add(row.id.value) || !voxels.add(row.voxel) || !isCanonicalPackedNormal(row.packedNormal, row.normalConfidence) || locationFor(configuration, row.voxel) != M3Location(row.region, row.page)) throw M3RestoreFailure(M3SurfaceOwnershipRestoreRefusal.CORRUPT) }
    val receiptIds = hashSetOf<String>()
    snapshot.receipts.forEach { receipt ->
        if (receipt.commandHash.size != 32 || receipt.fingerprint.size != 32 || !receiptIds.add(receipt.commandHash.hex()) || receipt.result.owners.any { rows -> rowsById(snapshot.rows, rows.id.value) == null }) throw M3RestoreFailure(M3SurfaceOwnershipRestoreRefusal.CORRUPT)
    }
    val supports = if (snapshot.supports.isEmpty()) snapshot.rows.associate { it.id.value to longArrayOf(it.id.value) } else snapshot.supports
    if (supports.keys != ids || supports.values.any { values -> values.isEmpty() || !values.contentEquals(values.distinct().sorted().toLongArray()) || values.any { it !in 1 until highWater } }) {
        throw M3RestoreFailure(M3SurfaceOwnershipRestoreRefusal.CORRUPT)
    }
    if (snapshot.lineageEdges != snapshot.lineageEdges.sortedWith(compareBy({ it.source.value }, { it.target.value })) ||
        snapshot.lineageEdges.any { it.source.value !in 1 until highWater || it.target.value !in 1 until highWater }) {
        throw M3RestoreFailure(M3SurfaceOwnershipRestoreRefusal.CORRUPT)
    }
    val canonicalIds = hashSetOf<String>()
    var journalBytes = 0L
    snapshot.transactionReceipts.forEach { receipt ->
        journalBytes += receipt.result.conservativeJournalBytes()
        if (receipt.commandHash.size != 32 || receipt.fingerprint.size != 32 || !canonicalIds.add(receipt.commandHash.hex()) ||
            receipt.result.targets.any { it.group != group || it.id.value !in 1 until highWater ||
                locationFor(configuration, it.voxel) != M3Location(it.region, it.page) || !isCanonicalPackedNormal(it.packedNormal, it.normalConfidence) } ||
            receipt.result.receipt.geometryRevision > snapshot.geometryRevision || receipt.result.receipt.lineageRevision > snapshot.lineageRevision) {
            throw M3RestoreFailure(M3SurfaceOwnershipRestoreRefusal.CORRUPT)
        }
    }
    if (journalBytes > configuration.changeJournalByteCapacity) throw M3RestoreFailure(M3SurfaceOwnershipRestoreRefusal.CORRUPT)
    return M3RestoredOwnership(highWater, snapshot.rows, snapshot.receipts, supports, snapshot.lineageEdges,
        snapshot.transactionReceipts, snapshot.geometryRevision, snapshot.lineageRevision)
}

private fun readLedger(file: File): List<M3Reservation> { if (!file.exists()) return emptyList(); val bytes = file.readBytes(); val recordBytes = 4 + 4 + 8 + 8 + 8 + 32 * 5; if (bytes.size % recordBytes != 0) throw M3RestoreFailure(M3SurfaceOwnershipRestoreRefusal.CORRUPT); return bytes.asList().chunked(recordBytes).map { decodeReservation(it.toByteArray()) } }
private fun decodeReservation(bytes: ByteArray): M3Reservation { val data = DataInputStream(ByteArrayInputStream(bytes)); if (data.readInt() != 0x4d33524c || data.readInt() != 1) throw M3RestoreFailure(M3SurfaceOwnershipRestoreRefusal.CORRUPT); val record = M3Reservation(data.readLong(), data.readLong(), data.readLong(), ByteArray(32).also(data::readFully), ByteArray(32).also(data::readFully), ByteArray(32).also(data::readFully), ByteArray(32).also(data::readFully)); val hash = ByteArray(32).also(data::readFully); if (!hash.contentEquals(record.recordHash)) throw M3RestoreFailure(M3SurfaceOwnershipRestoreRefusal.CORRUPT); return record }
private fun encodeSnapshot(snapshot: M3OwnershipSnapshot): ByteArray {
    val body = ByteArrayOutputStream().use { output ->
        DataOutputStream(output).use { data ->
            data.writeInt(0x4d33534f)
            data.writeInt(2)
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
            data.writeInt(snapshot.lineageEdges.size)
            snapshot.lineageEdges.forEach { data.writeLong(it.source.value); data.writeLong(it.target.value) }
            data.writeInt(snapshot.transactionReceipts.size)
            snapshot.transactionReceipts.forEach { stored ->
                data.write(stored.commandHash); data.write(stored.fingerprint)
                val result = stored.result
                data.writeUTF(result.receipt.commandId); data.writeInt(result.receipt.kind.ordinal)
                data.writeInt(result.targets.size); result.targets.forEach { row ->
                    data.writeLong(row.id.value); data.writeUTF(row.group.value)
                    data.writeInt(row.voxel.x); data.writeInt(row.voxel.y); data.writeInt(row.voxel.z)
                    data.writeInt(row.region.x); data.writeInt(row.region.y); data.writeInt(row.region.z)
                    data.writeInt(row.page); data.writeInt(row.packedNormal); data.writeInt(row.normalConfidence); data.write(row.allocatedBy)
                }
                data.writeInt(result.receipt.removedSurfaceIds.size); result.receipt.removedSurfaceIds.forEach { data.writeLong(it.value) }
                data.writeInt(result.receipt.lineageEdges.size); result.receipt.lineageEdges.forEach { data.writeLong(it.source.value); data.writeLong(it.target.value) }
                data.writeLong(result.receipt.geometryRevision); data.writeLong(result.receipt.lineageRevision)
                data.writeLong(result.receipt.nextSurfaceIdHighWater); data.writeInt(result.receipt.liveSurfaceCount)
            }
        }
        output.toByteArray()
    }
    return body + sha256(body)
}
private fun decodeSnapshot(
    bytes: ByteArray,
    configuration: M3SurfaceOwnershipConfiguration,
): M3OwnershipSnapshot = try {
    if (bytes.size < 32) throw M3RestoreFailure(M3SurfaceOwnershipRestoreRefusal.CORRUPT)
    val body = bytes.copyOfRange(0, bytes.size - 32)
    if (!sha256(body).contentEquals(bytes.copyOfRange(bytes.size - 32, bytes.size))) {
        throw M3RestoreFailure(M3SurfaceOwnershipRestoreRefusal.CORRUPT)
    }
    DataInputStream(ByteArrayInputStream(body)).use { data ->
        if (data.readInt() != 0x4d33534f) {
            throw M3RestoreFailure(M3SurfaceOwnershipRestoreRefusal.CORRUPT)
        }
        val version = data.readInt()
        if (version !in 1..2) throw M3RestoreFailure(M3SurfaceOwnershipRestoreRefusal.CORRUPT)
        val high = data.readLong()
        val rowCount = boundedCount(data.readInt(), configuration.surfaceCapacity)
        val rows = List(rowCount) {
            M3SurfaceOwner(
                M3SurfaceId(data.readLong()),
                M3SurfaceGroup(data.readUTF()),
                M3Voxel(data.readInt(), data.readInt(), data.readInt()),
                M3StorageRegion(data.readInt(), data.readInt(), data.readInt()),
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
                byId[data.readLong()] ?: throw M3RestoreFailure(M3SurfaceOwnershipRestoreRefusal.CORRUPT)
            }
            val receipt = M3SurfaceOwnershipReceipt(data.readLong(), data.readInt(), data.readInt(), data.readInt())
            M3StoredReceipt(commandHash, fingerprint, M3SurfaceOwnershipResult.Accepted(owners, receipt))
        }
        if (version == 1) {
            if (data.available() != 0) throw M3RestoreFailure(M3SurfaceOwnershipRestoreRefusal.CORRUPT)
            M3OwnershipSnapshot(high, rows, receipts)
        } else {
            val geometryRevision = data.readLong()
            val lineageRevision = data.readLong()
            val supportCount = boundedCount(data.readInt(), configuration.surfaceCapacity)
            val supports = linkedMapOf<Long, LongArray>()
            repeat(supportCount) {
                val id = data.readLong(); val count = boundedCount(data.readInt(), configuration.lineageCapacity)
                supports[id] = LongArray(count) { data.readLong() }
            }
            val edgeCount = boundedCount(data.readInt(), configuration.lineageCapacity)
            val edges = List(edgeCount) { M3LineageEdge(M3SurfaceId(data.readLong()), M3SurfaceId(data.readLong())) }
            val canonicalCount = boundedCount(data.readInt(), configuration.transactionCapacity)
            val canonicalReceipts = List(canonicalCount) {
                val commandHash = ByteArray(32).also(data::readFully)
                val fingerprint = ByteArray(32).also(data::readFully)
                val commandId = data.readUTF()
                val kindOrdinal = data.readInt()
                val kind = M3CanonicalOperation.entries.getOrNull(kindOrdinal) ?: throw M3RestoreFailure(M3SurfaceOwnershipRestoreRefusal.CORRUPT)
                val targetCount = boundedCount(data.readInt(), configuration.surfaceCapacity)
                val targets = List(targetCount) {
                    M3SurfaceOwner(M3SurfaceId(data.readLong()), M3SurfaceGroup(data.readUTF()),
                        M3Voxel(data.readInt(), data.readInt(), data.readInt()),
                        M3StorageRegion(data.readInt(), data.readInt(), data.readInt()),
                        data.readInt(), data.readInt(), data.readInt(), ByteArray(32).also(data::readFully))
                }
                val removedCount = boundedCount(data.readInt(), configuration.surfaceCapacity)
                val removed = List(removedCount) { M3SurfaceId(data.readLong()) }
                val receiptEdgeCount = boundedCount(data.readInt(), configuration.lineageCapacity)
                val receiptEdges = List(receiptEdgeCount) { M3LineageEdge(M3SurfaceId(data.readLong()), M3SurfaceId(data.readLong())) }
                val receipt = M3CanonicalTransactionReceipt(commandId, kind, removed, receiptEdges,
                    data.readLong(), data.readLong(), data.readLong(), data.readInt())
                M3StoredCanonicalReceipt(commandHash, fingerprint, M3CanonicalTransactionResult.Accepted(targets, receipt))
            }
            if (data.available() != 0) throw M3RestoreFailure(M3SurfaceOwnershipRestoreRefusal.CORRUPT)
            M3OwnershipSnapshot(high, rows, receipts, supports, edges, canonicalReceipts, geometryRevision, lineageRevision)
        }
    }
} catch (failure: M3RestoreFailure) {
    throw failure
} catch (_: Exception) {
    throw M3RestoreFailure(M3SurfaceOwnershipRestoreRefusal.CORRUPT)
}

private fun boundedCount(value: Int, maximum: Int): Int {
    if (value !in 0..maximum) throw M3RestoreFailure(M3SurfaceOwnershipRestoreRefusal.CORRUPT)
    return value
}

private fun rowsById(rows: List<M3SurfaceOwner>, id: Long): M3SurfaceOwner? = rows.firstOrNull { it.id.value == id }
private fun locationFor(
    configuration: M3SurfaceOwnershipConfiguration,
    voxel: M3Voxel,
): M3Location? {
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
        M3Location(M3StorageRegion(rx, ry, rz), px + 3 * (py + 3 * pz))
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
