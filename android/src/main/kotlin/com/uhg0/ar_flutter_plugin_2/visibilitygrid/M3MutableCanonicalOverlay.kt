package com.uhg0.ar_flutter_plugin_2.visibilitygrid

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.security.MessageDigest

/**
 * Private, bounded mutation overlay for the immutable v6 reader.
 *
 * The overlay deliberately owns only dirty rows and the evidence needed to
 * replace them.  It never materializes the reader, walks its live population,
 * reserves IDs, writes a WAL/root, or retains a current receipt.  Its returned
 * bytes are the exact future writer payloads, so later phases cannot discover
 * a journal or staging refusal after a reservation has been burned.
 */
internal class M3MutableCanonicalOverlay private constructor(
    private val view: M3CanonicalStateView,
    private val configuration: M3SurfaceOwnershipConfiguration,
) {
    private var pageFaults = 0
    private var bytesRead = 0
    private var directLookups = 0

    private fun state() = M3CanonicalStateReceipt(
        view.cut.geometryRevision,
        view.cut.lineageRevision,
        view.cut.nextSurfaceIdHighWater,
        view.cut.liveSurfaceCount,
    )

    private fun refuse(reason: M3CanonicalMutationRefusal) =
        M3CanonicalMutationPreparation.Refused(reason, state())

    private fun prepareFeature(command: M3FeatureMutationCommand): M3CanonicalMutationPreparation {
        if (!validCommandId(command.commandId) ||
            command.expectedGeometryRevision != view.cut.geometryRevision ||
            command.expectedLineageRevision != view.cut.lineageRevision
        ) return refuse(if (!validCommandId(command.commandId)) M3CanonicalMutationRefusal.INVALID_COMMAND else M3CanonicalMutationRefusal.REVISION_CONFLICT)
        val location = m3CompactLocation(configuration, command.target.voxel)
            ?: return refuse(M3CanonicalMutationRefusal.INVALID_OWNERSHIP)
        val packed = packed(command.target) ?: return refuse(M3CanonicalMutationRefusal.INVALID_NORMAL)
        val existing = command.target.id?.let {
            directLookups++
            view.findById(it)
        }
        if (command.target.id != null && existing == null) return refuse(M3CanonicalMutationRefusal.UNKNOWN_IDENTITY)
        val occupied = run {
            directLookups++
            view.findByVoxel(command.target.voxel)
        }
        if (occupied != null && occupied.id != command.target.id) return refuse(M3CanonicalMutationRefusal.OWNERSHIP_CONFLICT)
        if (existing != null && existing.voxel != command.target.voxel) return refuse(M3CanonicalMutationRefusal.INVALID_COMMAND)
        if (existing != null && existing.packedNormal == packed.first && existing.normalConfidence == packed.second) {
            return M3CanonicalMutationPreparation.NoOp(state())
        }
        if (view.cut.geometryRevision >= configuration.revisionLimit) return refuse(M3CanonicalMutationRefusal.REVISION_EXHAUSTED)

        val allocate = existing == null
        val high = checkedHighWater(view.cut.nextSurfaceIdHighWater, if (allocate) 1 else 0)
            ?: return refuse(M3CanonicalMutationRefusal.EXHAUSTED)
        if (allocate && view.cut.liveSurfaceCount >= configuration.surfaceCapacity) return refuse(M3CanonicalMutationRefusal.CAPACITY)
        if (allocate && view.cut.sourceCount.toLong() + 1L > sourceCapacity()) return refuse(M3CanonicalMutationRefusal.LINEAGE_EXHAUSTED)
        val fingerprint = command.fingerprint()
        val id = command.target.id ?: M3SurfaceId(view.cut.nextSurfaceIdHighWater)
        val row = M3SurfaceOwner(id, view.cut.group, command.target.voxel, location.region, location.page,
            packed.first, packed.second, fingerprint)
        val source = if (allocate) listOf(row.toSupport()) else emptyList()
        val support = if (allocate) listOf(M3PreparedSupport(id, row.toSupport())) else emptyList()
        return finish(
            command.commandId,
            if (allocate) M3PreparedMutationKind.FEATURE_ADD else M3PreparedMutationKind.FEATURE_REFINE,
            fingerprint,
            listOf(row), emptyList(), support, source, emptyList(),
            high, view.cut.liveSurfaceCount + if (allocate) 1 else 0,
            view.cut.sourceCount + if (allocate) 1 else 0,
            view.cut.supportCount + if (allocate) 1 else 0,
            view.cut.lineageCount,
            view.cut.geometryRevision + 1, view.cut.lineageRevision,
        )
    }

    private fun prepareStructural(command: M3CanonicalTransactionCommand): M3CanonicalMutationPreparation {
        if (!validCommandId(command.commandId) || command.targets.isEmpty()) return refuse(M3CanonicalMutationRefusal.INVALID_COMMAND)
        if (command.expectedGeometryRevision != view.cut.geometryRevision || command.expectedLineageRevision != view.cut.lineageRevision) return refuse(M3CanonicalMutationRefusal.REVISION_CONFLICT)
        val sources = command.sourceIds
        if (sources.map { it.value }.toSet().size != sources.size) return refuse(M3CanonicalMutationRefusal.UNKNOWN_IDENTITY)
        val create = command.kind == M3CanonicalOperation.CREATE
        val shape = when (command.kind) {
            M3CanonicalOperation.CREATE -> sources.isEmpty() && command.targets.all { it.id == null } &&
                view.cut.liveSurfaceCount == 0 && view.cut.sourceCount == 0 && view.cut.supportCount == 0 && view.cut.lineageCount == 0
            M3CanonicalOperation.RELOCATION -> sources.size == 1 && command.targets.size == 1 && command.targets.single().id == sources.single()
            M3CanonicalOperation.MERGE -> sources.size >= 2 && command.targets.size == 1 && command.targets.single().id == null
            M3CanonicalOperation.SPLIT -> sources.size == 1 && command.targets.size >= 2 && command.targets.all { it.id == null }
            M3CanonicalOperation.REPLACEMENT -> command.targets.all { it.id == null }
        }
        if (!shape) return refuse(M3CanonicalMutationRefusal.INVALID_COMMAND)
        if (view.cut.geometryRevision >= configuration.revisionLimit || (!create && view.cut.lineageRevision >= configuration.revisionLimit)) return refuse(M3CanonicalMutationRefusal.REVISION_EXHAUSTED)

        for (id in sources) {
            directLookups++
            if (view.findById(id) == null) return refuse(M3CanonicalMutationRefusal.UNKNOWN_IDENTITY)
        }
        val vacated = sources.toSet()
        val seenVoxels = HashSet<M3Voxel>()
        for (target in command.targets) {
            val location = m3CompactLocation(configuration, target.voxel) ?: return refuse(M3CanonicalMutationRefusal.INVALID_OWNERSHIP)
            if (packed(target) == null || !seenVoxels.add(target.voxel)) return refuse(if (packed(target) == null) M3CanonicalMutationRefusal.INVALID_NORMAL else M3CanonicalMutationRefusal.OWNERSHIP_CONFLICT)
            if (target.id != null && target.id !in vacated) return refuse(M3CanonicalMutationRefusal.UNKNOWN_IDENTITY)
            directLookups++
            val occupied = view.findByVoxel(target.voxel)
            if (occupied != null && occupied.id !in vacated) return refuse(M3CanonicalMutationRefusal.OWNERSHIP_CONFLICT)
            // Keep this local calculation visible: planner must validate every dirty index target.
            check(location.page in 0..26)
        }
        val allocations = command.targets.count { it.id == null }
        val high = checkedHighWater(view.cut.nextSurfaceIdHighWater, allocations) ?: return refuse(M3CanonicalMutationRefusal.EXHAUSTED)
        val finalRows = view.cut.liveSurfaceCount - sources.size + command.targets.size
        if (finalRows !in 0..configuration.surfaceCapacity) return refuse(M3CanonicalMutationRefusal.CAPACITY)
        if (view.cut.sourceCount.toLong() + allocations > sourceCapacity()) return refuse(M3CanonicalMutationRefusal.LINEAGE_EXHAUSTED)

        val sourceSupport = ArrayList<M3ImmutableSourceSupport>()
        var removedSupports = 0
        for (source in sources) {
            when (val read = readSupport(source)) {
                is SupportRead.Refused -> return refuse(read.reason)
                is SupportRead.Complete -> {
                    removedSupports += read.values.size
                    if (read.values.isEmpty()) {
                        when (val fallback = view.readSourceById(source)) {
                            is M3CanonicalPageRead.Refused -> return refuse(M3CanonicalMutationRefusal.SOURCE_READ_FAILURE)
                            is M3CanonicalPageRead.Complete -> {
                                pageFaults += fallback.pageFaults; bytesRead += fallback.bytesRead
                                fallback.value ?: return refuse(M3CanonicalMutationRefusal.UNKNOWN_IDENTITY)
                                sourceSupport += fallback.value.toSupport()
                            }
                        }
                    } else sourceSupport += read.values
                }
            }
        }
        val uniqueSupport = sourceSupport.distinctBy { it.id }.sortedWith { a, b -> java.lang.Long.compareUnsigned(a.id.value, b.id.value) }
        // Allocate exact IDs before constructing edges; no ID is reserved or burned here.
        var next = view.cut.nextSurfaceIdHighWater
        val fingerprint = command.fingerprint()
        val rows = command.targets.map { target ->
            val id = target.id ?: M3SurfaceId(next++)
            val location = requireNotNull(m3CompactLocation(configuration, target.voxel))
            val normal = requireNotNull(packed(target))
            M3SurfaceOwner(id, view.cut.group, target.voxel, location.region, location.page, normal.first, normal.second, fingerprint)
        }.sortedBy { it.id.value }
        val resolvedEdges = rows.flatMap { target -> sources.map { M3LineageEdge(it, target.id) } }
            .sortedWith(compareBy<M3LineageEdge> { it.source.value }.thenBy { it.target.value })
        if (view.cut.lineageCount.toLong() + resolvedEdges.size > configuration.lineageCapacity) return refuse(M3CanonicalMutationRefusal.LINEAGE_EXHAUSTED)
        val dirtySources = rows.filter { it.id.value >= view.cut.nextSurfaceIdHighWater }.map { it.toSupport() }
        val dirtySupport = if (create) rows.map { M3PreparedSupport(it.id, it.toSupport()) }
        else rows.flatMap { target -> uniqueSupport.map { M3PreparedSupport(target.id, it) } }
        val finalSupport = view.cut.supportCount - removedSupports + dirtySupport.size
        if (finalSupport < 0 || finalSupport.toLong() > sourceCapacity()) return refuse(M3CanonicalMutationRefusal.LINEAGE_EXHAUSTED)
        return finish(
            command.commandId, M3PreparedMutationKind.from(command.kind), fingerprint, rows, sources,
            dirtySupport, dirtySources, resolvedEdges, high, finalRows,
            view.cut.sourceCount + allocations, finalSupport,
            view.cut.lineageCount + resolvedEdges.size,
            view.cut.geometryRevision + 1, if (create) view.cut.lineageRevision else view.cut.lineageRevision + 1,
        )
    }

    private fun finish(
        commandId: String,
        kind: M3PreparedMutationKind,
        fingerprint: ByteArray,
        rows: List<M3SurfaceOwner>,
        removed: List<M3SurfaceId>,
        supports: List<M3PreparedSupport>,
        sources: List<M3ImmutableSourceSupport>,
        edges: List<M3LineageEdge>,
        high: Long, live: Int, sourceCount: Int, supportCount: Int, lineageCount: Int,
        geometry: Long, lineage: Long,
    ): M3CanonicalMutationPreparation {
        val draft = M3PreparedCanonicalMutation(
            view.cut, M3CanonicalReceiptBytes(overlayHash(commandId.encodeToByteArray())),
            M3CanonicalReceiptBytes(fingerprint), commandId, kind, rows, removed, supports, sources, edges,
            high, live, sourceCount, supportCount, lineageCount, geometry, lineage,
            M3CanonicalReceiptBytes.EMPTY, M3CanonicalReceiptBytes.EMPTY,
            M3CanonicalMutationWork(0, 0, 0, 0, 0, 0, pageFaults, bytesRead, directLookups, 0, 0, 0),
        )
        val current = encode(draft, 0x4d334350) // M3CP
        val wal = encode(draft, 0x4d33574c) // M3WL
        val total = current.size.toLong() + wal.size.toLong()
        if (total > M3CompactCanonicalStore.JOURNAL_RESERVE_BYTES || total > configuration.changeJournalByteCapacity) return refuse(M3CanonicalMutationRefusal.JOURNAL_EXHAUSTED)
        val work = M3CanonicalMutationWork(
            rows.size + removed.size, rows.size + removed.size, rows.size + removed.size,
            supports.size, sources.size, edges.size, pageFaults, bytesRead, directLookups,
            wal.size, current.size, total,
        )
        return M3CanonicalMutationPreparation.Prepared(draft.copy(
            walBytes = M3CanonicalReceiptBytes(wal), currentBytes = M3CanonicalReceiptBytes(current), work = work,
        ))
    }

    private sealed interface SupportRead {
        data class Complete(val values: List<M3ImmutableSourceSupport>) : SupportRead
        data class Refused(val reason: M3CanonicalMutationRefusal) : SupportRead
    }

    private fun readSupport(target: M3SurfaceId): SupportRead {
        val values = ArrayList<M3ImmutableSourceSupport>()
        var cursor: M3SourceSupportCursor? = null
        var pages = 0
        do {
            val read = view.visitSourceSupport(target, cursor, { support -> values += support.source.toSupport(); true })
            when (read) {
                is M3SourceSupportRead.Refused -> return SupportRead.Refused(M3CanonicalMutationRefusal.SOURCE_READ_FAILURE)
                is M3SourceSupportRead.Complete -> {
                    pageFaults += read.pageFaults; bytesRead += read.bytesRead; cursor = read.nextCursor
                    if (++pages > view.cut.supportCount + 1) return SupportRead.Refused(M3CanonicalMutationRefusal.SOURCE_READ_FAILURE)
                }
            }
        } while (cursor != null)
        return SupportRead.Complete(values)
    }

    private fun packed(target: M3CanonicalTarget): Pair<Int, Int>? =
        if (target.normalOctX !in -127..127 || target.normalOctY !in -127..127 || target.normalConfidence !in 0..255) null
        else (((target.normalOctX and 0xff) shl 8) or (target.normalOctY and 0xff)) to target.normalConfidence

    private fun checkedHighWater(start: Long, count: Int): Long? {
        if (start !in 1..UINT32_END || count < 0) return null
        return try { Math.addExact(start, count.toLong()).takeIf { it <= UINT32_END } } catch (_: ArithmeticException) { null }
    }

    private fun sourceCapacity() = configuration.surfaceCapacity.toLong() + configuration.lineageCapacity.toLong()

    companion object {
        private const val UINT32_END = 0x1_0000_0000L
        private const val MAX_COMMAND_BYTES = 256

        fun prepare(view: M3CanonicalStateView, configuration: M3SurfaceOwnershipConfiguration, command: M3FeatureMutationCommand) =
            M3MutableCanonicalOverlay(view, configuration).prepareFeature(command)

        fun prepare(view: M3CanonicalStateView, configuration: M3SurfaceOwnershipConfiguration, command: M3CanonicalTransactionCommand) =
            M3MutableCanonicalOverlay(view, configuration).prepareStructural(command)

        private fun validCommandId(value: String) = value.isNotBlank() && value.encodeToByteArray().size <= MAX_COMMAND_BYTES
    }
}

/** One feature delta: no ID means add; an existing ID means geometry-only refinement. */
internal data class M3FeatureMutationCommand(
    val commandId: String,
    val expectedGeometryRevision: Long,
    val expectedLineageRevision: Long,
    val target: M3CanonicalTarget,
) {
    internal fun fingerprint(): ByteArray = overlayHash(buildString {
        append(commandId).append('|').append(expectedGeometryRevision).append('|').append(expectedLineageRevision).append('|')
        append(target.id?.value).append(':').append(target.voxel).append(':').append(target.normalOctX).append(':').append(target.normalOctY).append(':').append(target.normalConfidence)
    }.encodeToByteArray())
}

internal enum class M3PreparedMutationKind {
    FEATURE_ADD, FEATURE_REFINE, CREATE, RELOCATION, MERGE, SPLIT, REPLACEMENT;
    companion object { fun from(value: M3CanonicalOperation) = entries.first { it.name == value.name } }
}

internal data class M3PreparedSupport(val target: M3SurfaceId, val source: M3ImmutableSourceSupport)

internal data class M3CanonicalMutationWork(
    val dirtyRows: Int,
    val dirtyIdIndexRecords: Int,
    val dirtyVoxelIndexRecords: Int,
    val dirtySupportRecords: Int,
    val dirtySourceRecords: Int,
    val dirtyLineageRecords: Int,
    val sourcePageFaults: Int,
    val sourceBytesRead: Int,
    val directLookupCount: Int,
    val walBytes: Int,
    val currentBytes: Int,
    val stagingBytes: Long,
)

internal data class M3PreparedCanonicalMutation(
    val sourceCut: M3CompactCanonicalCut,
    val commandHash: M3CanonicalReceiptBytes,
    val commandFingerprint: M3CanonicalReceiptBytes,
    val commandId: String,
    val kind: M3PreparedMutationKind,
    val dirtyRows: List<M3SurfaceOwner>,
    val removedSurfaceIds: List<M3SurfaceId>,
    val dirtySupport: List<M3PreparedSupport>,
    val dirtySources: List<M3ImmutableSourceSupport>,
    val dirtyLineage: List<M3LineageEdge>,
    val targetHighWater: Long,
    val targetLiveSurfaceCount: Int,
    val targetSourceCount: Int,
    val targetSupportCount: Int,
    val targetLineageCount: Int,
    val targetGeometryRevision: Long,
    val targetLineageRevision: Long,
    val walBytes: M3CanonicalReceiptBytes,
    val currentBytes: M3CanonicalReceiptBytes,
    val work: M3CanonicalMutationWork,
)

internal sealed interface M3CanonicalMutationPreparation {
    data class Prepared(val mutation: M3PreparedCanonicalMutation) : M3CanonicalMutationPreparation
    data class NoOp(val receipt: M3CanonicalStateReceipt) : M3CanonicalMutationPreparation
    data class Refused(val reason: M3CanonicalMutationRefusal, val receipt: M3CanonicalStateReceipt) : M3CanonicalMutationPreparation
}

internal enum class M3CanonicalMutationRefusal {
    INVALID_COMMAND, INVALID_OWNERSHIP, INVALID_NORMAL, UNKNOWN_IDENTITY, OWNERSHIP_CONFLICT,
    CAPACITY, EXHAUSTED, REVISION_CONFLICT, REVISION_EXHAUSTED, LINEAGE_EXHAUSTED,
    JOURNAL_EXHAUSTED, SOURCE_READ_FAILURE,
}

private fun M3SurfaceOwner.toSupport() = M3ImmutableSourceSupport(
    id, voxel, packedNormal, normalConfidence, M3CanonicalReceiptBytes(allocatedBy),
)

private fun M3PagedSource.toSupport() = M3ImmutableSourceSupport(
    id, voxel, packedNormal, normalConfidence, allocationFingerprint,
)

/** Fixed, deterministic, dirty-only future WAL/current representation. */
private fun encode(value: M3PreparedCanonicalMutation, magic: Int): ByteArray =
    ByteArrayOutputStream().use { raw ->
        DataOutputStream(raw).use { out ->
            out.writeInt(magic); out.writeInt(1); out.write(value.sourceCut.rootHash.toByteArray())
            out.writeUTF(value.commandId); out.writeInt(value.kind.ordinal); out.write(value.commandHash.toByteArray()); out.write(value.commandFingerprint.toByteArray())
            out.writeLong(value.targetHighWater); out.writeInt(value.targetLiveSurfaceCount); out.writeInt(value.targetSourceCount); out.writeInt(value.targetSupportCount); out.writeInt(value.targetLineageCount)
            out.writeLong(value.targetGeometryRevision); out.writeLong(value.targetLineageRevision)
            out.writeInt(value.dirtyRows.size); value.dirtyRows.forEach { row -> out.writeLong(row.id.value); out.writeInt(row.voxel.x); out.writeInt(row.voxel.y); out.writeInt(row.voxel.z); out.writeInt(row.packedNormal); out.writeInt(row.normalConfidence); out.write(row.allocatedBy) }
            out.writeInt(value.removedSurfaceIds.size); value.removedSurfaceIds.forEach { out.writeLong(it.value) }
            out.writeInt(value.dirtySupport.size); value.dirtySupport.forEach { row -> out.writeLong(row.target.value); out.writeLong(row.source.id.value); out.writeInt(row.source.voxel.x); out.writeInt(row.source.voxel.y); out.writeInt(row.source.voxel.z); out.writeInt(row.source.packedNormal); out.writeInt(row.source.normalConfidence); out.write(row.source.allocationFingerprint.toByteArray()) }
            out.writeInt(value.dirtySources.size); value.dirtySources.forEach { row -> out.writeLong(row.id.value); out.writeInt(row.voxel.x); out.writeInt(row.voxel.y); out.writeInt(row.voxel.z); out.writeInt(row.packedNormal); out.writeInt(row.normalConfidence); out.write(row.allocationFingerprint.toByteArray()) }
            out.writeInt(value.dirtyLineage.size); value.dirtyLineage.forEach { out.writeLong(it.source.value); out.writeLong(it.target.value) }
        }
        raw.toByteArray()
    }

private fun overlayHash(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)
