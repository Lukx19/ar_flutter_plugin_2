package com.uhg0.ar_flutter_plugin_2.visibilitygrid

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
    private val startingReadWork = view.readWorkReceipt()
    private var pageFaults = 0
    private var bytesRead = 0
    private var directLookups = 0
    private var targetDeepCopies = 0
    private var targetSetInsertions = 0
    private var ownerConstructions = 0
    private var removalGraphAllocations = 0

    private fun state() = M3CanonicalStateReceipt(
        view.cut.geometryRevision,
        view.cut.lineageRevision,
        view.cut.nextSurfaceIdHighWater,
        view.cut.liveSurfaceCount,
    )

    private fun refuse(reason: M3CanonicalMutationRefusal) =
        M3CanonicalMutationPreparation.Refused(
            reason,
            state(),
            M3CanonicalMutationPreflightWork(
                targetDeepCopies,
                targetSetInsertions,
                ownerConstructions,
                removalGraphAllocations,
            ),
        )

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
        if (existing != null && existing.packedNormal == packed.first &&
            reliabilityBand(existing.normalConfidence) == reliabilityBand(packed.second)) {
            return M3CanonicalMutationPreparation.NoOp(state())
        }
        if (view.cut.geometryRevision >= configuration.revisionLimit) return refuse(M3CanonicalMutationRefusal.REVISION_EXHAUSTED)

        val allocate = existing == null
        val high = checkedHighWater(view.cut.nextSurfaceIdHighWater, if (allocate) 1 else 0)
            ?: return refuse(M3CanonicalMutationRefusal.EXHAUSTED)
        if (allocate && view.cut.liveSurfaceCount >= configuration.surfaceCapacity) return refuse(M3CanonicalMutationRefusal.CAPACITY)
        if (allocate && view.cut.sourceCount.toLong() + 1L > sourceCapacity()) return refuse(M3CanonicalMutationRefusal.LINEAGE_EXHAUSTED)
        val fingerprint = command.fingerprint()
        val allocationFingerprint = if (existing == null) overlayHash(command.commandId.encodeToByteArray()) else {
            when (val source = view.readSourceById(existing.id)) {
                is M3CanonicalPageRead.Refused -> return refuse(M3CanonicalMutationRefusal.SOURCE_READ_FAILURE)
                is M3CanonicalPageRead.Complete -> {
                    pageFaults += source.pageFaults; bytesRead += source.bytesRead
                    source.value?.allocationFingerprint?.toByteArray()
                        ?: return refuse(M3CanonicalMutationRefusal.SOURCE_READ_FAILURE)
                }
            }
        }
        val id = command.target.id ?: M3SurfaceId(view.cut.nextSurfaceIdHighWater)
        val row = M3SurfaceOwner(id, view.cut.group, command.target.voxel, location.region, location.page,
            packed.first, packed.second, allocationFingerprint)
        return finish(
            command.commandId,
            if (allocate) M3PreparedMutationKind.FEATURE_ADD else M3PreparedMutationKind.FEATURE_REFINE,
            fingerprint,
            listOf(row), emptyList(), M3PreparedSourceTable.EMPTY,
            if (allocate) M3PreparedSupportMode.SELF else M3PreparedSupportMode.NONE,
            high, view.cut.liveSurfaceCount + if (allocate) 1 else 0,
            view.cut.sourceCount + if (allocate) 1 else 0,
            view.cut.supportCount + if (allocate) 1 else 0,
            view.cut.lineageCount,
            view.cut.geometryRevision + 1, view.cut.lineageRevision,
        )
    }

    /**
     * Plans one ordered kernel delta as one adjacent canonical transaction.
     *
     * Removal remains deliberately represented at this seam, but is a durable
     * canonical retention/no-op: M3 has no structural removal semantics yet.
     * It therefore never deletes an owner, support, source, or lineage row.
     */
    private fun prepareFeatureBatch(
        command: M3CanonicalFeatureBatchCommand,
        preflight: M3CanonicalFeatureBatchPreflightReceipt,
    ): M3CanonicalMutationPreparation {
        // Removal has no structural effect at this seam. A scalar-only first
        // pass therefore admits an arbitrarily large removal-only durable
        // no-op, while bounding every collection owned by an effective batch.
        if (preflight.upserts == 0) return M3CanonicalMutationPreparation.NoOp(state())
        val upserts = ArrayList<M3CanonicalTarget>(preflight.upserts)
        val seenVoxels = HashSet<M3Voxel>()
        for (change in command.changes) {
            when (change) {
                is M3FeatureFusionChange.Removal -> Unit
                is M3FeatureFusionChange.Upsert -> {
                    val voxel = M3Voxel(change.x, change.y, change.z)
                    if (!seenVoxels.add(voxel)) return refuse(M3CanonicalMutationRefusal.OWNERSHIP_CONFLICT)
                    targetSetInsertions++
                    val primary = change.candidate.primaryCanonicalTarget()
                        ?: return refuse(M3CanonicalMutationRefusal.INVALID_NORMAL)
                    // A candidate is self-describing; accepting a mismatched
                    // primary would make the kernel delta non-canonical.
                    if (primary.voxel != voxel) return refuse(M3CanonicalMutationRefusal.INVALID_COMMAND)
                    targetDeepCopies++
                    upserts += primary.copy(voxel = primary.voxel.copy())
                }
            }
        }
        // Duplicate removals remain no-op, but a removal of an upsert voxel is
        // a typed conflict without inserting any removal into the bounded set.
        for (change in command.changes) if (change is M3FeatureFusionChange.Removal &&
            M3Voxel(change.x, change.y, change.z) in seenVoxels
        ) return refuse(M3CanonicalMutationRefusal.OWNERSHIP_CONFLICT)
        if (view.cut.geometryRevision >= configuration.revisionLimit) return refuse(M3CanonicalMutationRefusal.REVISION_EXHAUSTED)

        val material = ArrayList<M3CanonicalTarget>(upserts.size)
        var allocations = 0
        for (target in upserts) {
            val location = m3CompactLocation(configuration, target.voxel)
                ?: return refuse(M3CanonicalMutationRefusal.INVALID_OWNERSHIP)
            val normal = packed(target) ?: return refuse(M3CanonicalMutationRefusal.INVALID_NORMAL)
            directLookups++
            val existing = view.findByVoxel(target.voxel)
            if (existing == null) {
                allocations++
                material += target
            } else if (existing.packedNormal != normal.first ||
                reliabilityBand(existing.normalConfidence) != reliabilityBand(normal.second)
            ) {
                material += target.copy(id = existing.id)
            }
            check(location.page in 0..26)
        }
        if (material.isEmpty()) return M3CanonicalMutationPreparation.NoOp(state())
        val high = checkedHighWater(view.cut.nextSurfaceIdHighWater, allocations)
            ?: return refuse(M3CanonicalMutationRefusal.EXHAUSTED)
        val finalLive = view.cut.liveSurfaceCount.toLong() + allocations.toLong()
        if (finalLive > configuration.surfaceCapacity || finalLive > Int.MAX_VALUE) return refuse(M3CanonicalMutationRefusal.CAPACITY)
        val finalSources = view.cut.sourceCount.toLong() + allocations.toLong()
        if (finalSources > sourceCapacity() || finalSources > Int.MAX_VALUE) return refuse(M3CanonicalMutationRefusal.LINEAGE_EXHAUSTED)
        val finalSupport = view.cut.supportCount.toLong() + allocations.toLong()
        if (finalSupport > sourceCapacity() || finalSupport > Int.MAX_VALUE) return refuse(M3CanonicalMutationRefusal.LINEAGE_EXHAUSTED)
        val estimatedBytes = encodedRecordBytes(material.size, 0, allocations.toLong(), allocations.toLong(), 0, command.commandId)
            ?: return refuse(M3CanonicalMutationRefusal.JOURNAL_EXHAUSTED)
        if (!journalFits(estimatedBytes)) return refuse(M3CanonicalMutationRefusal.JOURNAL_EXHAUSTED)

        var next = view.cut.nextSurfaceIdHighWater
        val commandHash = overlayHash(command.commandId.encodeToByteArray())
        val rows = material.sortedWith(compareBy<M3CanonicalTarget> { it.voxel.x }
            .thenBy { it.voxel.y }.thenBy { it.voxel.z }).map { target ->
            val id = target.id ?: M3SurfaceId(next++)
            val location = requireNotNull(m3CompactLocation(configuration, target.voxel))
            val normal = requireNotNull(packed(target))
            val provenance = if (target.id == null) commandHash else readAllocationFingerprint(id)
                ?: return refuse(M3CanonicalMutationRefusal.SOURCE_READ_FAILURE)
            M3SurfaceOwner(id, view.cut.group, target.voxel, location.region, location.page, normal.first, normal.second, provenance).also {
                ownerConstructions++
            }
        }.sortedBy { it.id.value }
        return finish(
            command.commandId, M3PreparedMutationKind.FEATURE_BATCH, command.fingerprint(), rows, emptyList(),
            M3PreparedSourceTable.EMPTY, M3PreparedSupportMode.NEW_ONLY, high, finalLive.toInt(),
            finalSources.toInt(), finalSupport.toInt(), view.cut.lineageCount,
            view.cut.geometryRevision + 1, view.cut.lineageRevision,
        )
    }

    private fun prepareStructural(input: M3CanonicalTransactionCommand): M3CanonicalMutationPreparation {
        if (!validCommandId(input.commandId) || input.targets.isEmpty()) return refuse(M3CanonicalMutationRefusal.INVALID_COMMAND)
        if (input.expectedGeometryRevision != view.cut.geometryRevision || input.expectedLineageRevision != view.cut.lineageRevision) return refuse(M3CanonicalMutationRefusal.REVISION_CONFLICT)
        val sourceCardinality = input.sourceIds.size
        val targetCardinality = input.targets.size
        if (sourceCardinality > view.cut.liveSurfaceCount) return refuse(M3CanonicalMutationRefusal.UNKNOWN_IDENTITY)
        if (targetCardinality.toLong() > configuration.surfaceCapacity.toLong() + sourceCardinality.toLong())
            return refuse(M3CanonicalMutationRefusal.CAPACITY)
        val minimumEdges = checkedProduct(sourceCardinality, targetCardinality)
            ?: return refuse(M3CanonicalMutationRefusal.LINEAGE_EXHAUSTED)
        if (view.cut.lineageCount.toLong() + minimumEdges > configuration.lineageCapacity.toLong()) {
            return refuse(M3CanonicalMutationRefusal.LINEAGE_EXHAUSTED)
        }
        val minimumStaging = encodedRecordBytes(targetCardinality, sourceCardinality, 0, 0, minimumEdges, input.commandId)
            ?: return refuse(M3CanonicalMutationRefusal.JOURNAL_EXHAUSTED)
        if (!journalFits(minimumStaging)) return refuse(M3CanonicalMutationRefusal.JOURNAL_EXHAUSTED)
        val scalarAllocations = input.targets.count { it.id == null }
        val supportLimit = maximumSupportRecords(
            targetCardinality, sourceCardinality, scalarAllocations, minimumEdges, input.commandId,
        ) ?: return refuse(M3CanonicalMutationRefusal.JOURNAL_EXHAUSTED)

        // Freeze caller-owned lists and values only after scalar cardinality admission.
        val command = input.copy(
            sourceIds = input.sourceIds.map {
                removalGraphAllocations++
                M3SurfaceId(it.value)
            },
            targets = input.targets.map {
                targetDeepCopies++
                it.copy(id = it.id?.let { id -> M3SurfaceId(id.value) }, voxel = it.voxel.copy())
            },
        )
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
            if (packed(target) == null) return refuse(M3CanonicalMutationRefusal.INVALID_NORMAL)
            if (!seenVoxels.add(target.voxel)) return refuse(M3CanonicalMutationRefusal.OWNERSHIP_CONFLICT)
            targetSetInsertions++
            if (target.id != null && target.id !in vacated) return refuse(M3CanonicalMutationRefusal.UNKNOWN_IDENTITY)
            directLookups++
            val occupied = view.findByVoxel(target.voxel)
            if (occupied != null && occupied.id !in vacated) return refuse(M3CanonicalMutationRefusal.OWNERSHIP_CONFLICT)
            // Keep this local calculation visible: planner must validate every dirty index target.
            check(location.page in 0..26)
        }
        val allocations = scalarAllocations
        val high = checkedHighWater(view.cut.nextSurfaceIdHighWater, allocations) ?: return refuse(M3CanonicalMutationRefusal.EXHAUSTED)
        val finalRowsLong = view.cut.liveSurfaceCount.toLong() - sources.size.toLong() + command.targets.size.toLong()
        if (finalRowsLong !in 0..configuration.surfaceCapacity.toLong()) return refuse(M3CanonicalMutationRefusal.CAPACITY)
        val finalRows = finalRowsLong.toInt()
        val finalSourceCount = view.cut.sourceCount.toLong() + allocations.toLong()
        if (finalSourceCount > sourceCapacity() || finalSourceCount > Int.MAX_VALUE) return refuse(M3CanonicalMutationRefusal.LINEAGE_EXHAUSTED)

        val edgeCardinality = minimumEdges
        val sourceSupport = M3BoundedSupportAccumulator(supportLimit)
        var removedSupports = 0L
        for (source in sources) {
            when (val read = readSupport(source, sourceSupport)) {
                is SupportRead.Refused -> return refuse(read.reason)
                is SupportRead.Complete -> {
                    removedSupports += read.records
                    if (read.records == 0L) {
                        when (val fallback = view.readSourceById(source)) {
                            is M3CanonicalPageRead.Refused -> return refuse(M3CanonicalMutationRefusal.SOURCE_READ_FAILURE)
                            is M3CanonicalPageRead.Complete -> {
                                pageFaults += fallback.pageFaults; bytesRead += fallback.bytesRead
                                fallback.value ?: return refuse(M3CanonicalMutationRefusal.UNKNOWN_IDENTITY)
                                if (!sourceSupport.add(fallback.value)) {
                                    return refuse(M3CanonicalMutationRefusal.JOURNAL_EXHAUSTED)
                                }
                            }
                        }
                    }
                }
            }
        }
        val supportProduct = checkedProduct(sourceSupport.size, command.targets.size)
            ?: return refuse(M3CanonicalMutationRefusal.LINEAGE_EXHAUSTED)
        if (view.cut.lineageCount.toLong() + edgeCardinality > configuration.lineageCapacity.toLong()) {
            return refuse(M3CanonicalMutationRefusal.LINEAGE_EXHAUSTED)
        }
        val dirtySourceCardinality = allocations
        val dirtySupportCardinality = if (create) command.targets.size.toLong() else supportProduct
        val exactStaging = encodedRecordBytes(command.targets.size, sources.size, dirtySupportCardinality, dirtySourceCardinality.toLong(), edgeCardinality, command.commandId)
            ?: return refuse(M3CanonicalMutationRefusal.JOURNAL_EXHAUSTED)
        if (!journalFits(exactStaging)) return refuse(M3CanonicalMutationRefusal.JOURNAL_EXHAUSTED)
        // Allocate exact IDs before constructing edges; no ID is reserved or burned here.
        var next = view.cut.nextSurfaceIdHighWater
        val fingerprint = command.fingerprint()
        val commandHash = overlayHash(command.commandId.encodeToByteArray())
        val rows = command.targets.map { target ->
            val id = target.id ?: M3SurfaceId(next++)
            val location = requireNotNull(m3CompactLocation(configuration, target.voxel))
            val normal = requireNotNull(packed(target))
            val provenance = if (target.id == null) commandHash else
                readAllocationFingerprint(id)
                    ?: return refuse(M3CanonicalMutationRefusal.SOURCE_READ_FAILURE)
            M3SurfaceOwner(id, view.cut.group, target.voxel, location.region, location.page, normal.first, normal.second, provenance).also {
                ownerConstructions++
            }
        }.sortedBy { it.id.value }
        if (view.cut.lineageCount.toLong() + edgeCardinality > configuration.lineageCapacity) return refuse(M3CanonicalMutationRefusal.LINEAGE_EXHAUSTED)
        val finalSupportLong = view.cut.supportCount.toLong() - removedSupports + dirtySupportCardinality
        if (finalSupportLong !in 0..sourceCapacity() || finalSupportLong > Int.MAX_VALUE) return refuse(M3CanonicalMutationRefusal.LINEAGE_EXHAUSTED)
        val finalSupport = finalSupportLong.toInt()
        return finish(
            command.commandId, M3PreparedMutationKind.from(command.kind), fingerprint, rows, sources,
            sourceSupport.freeze(), if (create) M3PreparedSupportMode.SELF else M3PreparedSupportMode.CARTESIAN,
            high, finalRows,
            finalSourceCount.toInt(), finalSupport,
            (view.cut.lineageCount.toLong() + edgeCardinality).toInt(),
            view.cut.geometryRevision + 1, if (create) view.cut.lineageRevision else view.cut.lineageRevision + 1,
        )
    }

    private fun finish(
        commandId: String,
        kind: M3PreparedMutationKind,
        fingerprint: ByteArray,
        rows: List<M3SurfaceOwner>,
        removed: List<M3SurfaceId>,
        supports: M3PreparedSourceTable,
        supportMode: M3PreparedSupportMode,
        high: Long, live: Int, sourceCount: Int, supportCount: Int, lineageCount: Int,
        geometry: Long, lineage: Long,
    ): M3CanonicalMutationPreparation {
        val rowTable = M3PreparedRowTable.from(rows)
        val removedIds = LongArray(removed.size) { removed[it].value }.also { it.sort() }
        val dirtySupportRecords = when (supportMode) {
            M3PreparedSupportMode.NONE -> 0L
            M3PreparedSupportMode.SELF -> rows.size.toLong()
            M3PreparedSupportMode.NEW_ONLY -> rows.count { it.id.value >= view.cut.nextSurfaceIdHighWater }.toLong()
            M3PreparedSupportMode.CARTESIAN -> checkedProduct(supports.size, rows.size)
                ?: return refuse(M3CanonicalMutationRefusal.JOURNAL_EXHAUSTED)
        }
        val dirtySourceRecords = rows.count { it.id.value >= view.cut.nextSurfaceIdHighWater }
        val dirtyLineageRecords = checkedProduct(removed.size, rows.size)
            ?: return refuse(M3CanonicalMutationRefusal.LINEAGE_EXHAUSTED)
        val removedSupportRecords = try {
            Math.addExact(
                Math.subtractExact(view.cut.supportCount.toLong(), supportCount.toLong()),
                dirtySupportRecords,
            )
        } catch (_: ArithmeticException) { return refuse(M3CanonicalMutationRefusal.LINEAGE_EXHAUSTED) }
        if (removedSupportRecords !in 0..view.cut.supportCount.toLong())
            return refuse(M3CanonicalMutationRefusal.LINEAGE_EXHAUSTED)
        val encodedBytes = encodedRecordBytes(
            rows.size, removed.size, dirtySupportRecords, dirtySourceRecords.toLong(),
            dirtyLineageRecords, commandId,
        ) ?: return refuse(M3CanonicalMutationRefusal.JOURNAL_EXHAUSTED)
        if (!journalFits(encodedBytes)) return refuse(M3CanonicalMutationRefusal.JOURNAL_EXHAUSTED)
        val retainedPlanBytes = PLAN_FIXED_OWNER_BYTES + rowTable.allocatedBytes + removedIds.size * 8L + supports.allocatedBytes
        val sharedReserveBytes = retainedPlanBytes + WRITER_SCRATCH_BYTES
        val constructionPeakBytes = PLAN_FIXED_OWNER_BYTES + WRITER_SCRATCH_BYTES + PLANNING_PAGE_SCRATCH_BYTES +
            rows.size * ROW_CONSTRUCTION_BYTES_PER_RECORD + removedIds.size * REMOVED_CONSTRUCTION_BYTES_PER_RECORD +
            supports.constructionArrayPeakBytes + supports.constructionHashBytes
        if (sharedReserveBytes > M3CompactCanonicalStore.JOURNAL_RESERVE_BYTES ||
            constructionPeakBytes > M3CompactCanonicalStore.JOURNAL_RESERVE_BYTES
        ) return refuse(M3CanonicalMutationRefusal.JOURNAL_EXHAUSTED)
        val authority = authorityWork()
        val rowConstructionBytes = rows.size * ROW_CONSTRUCTION_BYTES_PER_RECORD
        val removedConstructionBytes = removedIds.size * REMOVED_CONSTRUCTION_BYTES_PER_RECORD
        val work = M3CanonicalMutationWork(
            rows.size + removed.size, rows.size + removed.size, rows.size + removed.size,
            dirtySupportRecords.toIntExact(), dirtySourceRecords, dirtyLineageRecords.toIntExact(),
            pageFaults, bytesRead, directLookups,
            encodedBytes.toIntExact(), encodedBytes.toIntExact(), sharedReserveBytes,
            authority.directLookups.toIntExact(), authority.pageReads.toIntExact(),
            authority.inspectedRows.toIntExact(), authority.bytesRead,
            retainedPlanBytes, WRITER_SCRATCH_BYTES, constructionPeakBytes, supports.limit,
            rowTable.allocatedBytes, removedIds.size * 8L, supports.allocatedBytes,
            supports.constructionHashBytes, PLAN_FIXED_OWNER_BYTES, PLANNING_PAGE_SCRATCH_BYTES,
            supports.constructionArrayPeakBytes, rowConstructionBytes, removedConstructionBytes,
        )
        return M3CanonicalMutationPreparation.Prepared(M3PreparedCanonicalMutation(
            M3CanonicalAuthorityLease(),
            view.cut, M3CanonicalReceiptBytes(overlayHash(commandId.encodeToByteArray())),
            M3CanonicalReceiptBytes(fingerprint), commandId, kind, rowTable, removedIds,
            supports, supportMode, removedSupportRecords.toIntExact(), high, live, sourceCount, supportCount, lineageCount,
            geometry, lineage, work,
        ))
    }

    private sealed interface SupportRead {
        data class Complete(val records: Long) : SupportRead
        data class Refused(val reason: M3CanonicalMutationRefusal) : SupportRead
    }

    private fun readSupport(target: M3SurfaceId, accumulator: M3BoundedSupportAccumulator): SupportRead {
        var cursor: M3SourceSupportCursor? = null
        var pages = 0L
        var records = 0L
        var overflow = false
        do {
            val read = view.visitSourceSupport(target, cursor, { support ->
                if (!accumulator.add(support.source)) {
                    overflow = true
                    false
                } else {
                    records++
                    true
                }
            })
            when (read) {
                is M3SourceSupportRead.Refused -> return SupportRead.Refused(M3CanonicalMutationRefusal.SOURCE_READ_FAILURE)
                is M3SourceSupportRead.Complete -> {
                    pageFaults += read.pageFaults; bytesRead += read.bytesRead; cursor = read.nextCursor
                    if (overflow) return SupportRead.Refused(M3CanonicalMutationRefusal.JOURNAL_EXHAUSTED)
                    if (++pages > view.cut.supportCount.toLong() + 1L) return SupportRead.Refused(M3CanonicalMutationRefusal.SOURCE_READ_FAILURE)
                }
            }
        } while (cursor != null)
        return SupportRead.Complete(records)
    }

    private fun readAllocationFingerprint(id: M3SurfaceId): ByteArray? =
        when (val read = view.readSourceById(id)) {
            is M3CanonicalPageRead.Refused -> null
            is M3CanonicalPageRead.Complete -> {
                pageFaults += read.pageFaults
                bytesRead += read.bytesRead
                read.value?.allocationFingerprint?.toByteArray()
            }
        }

    private fun packed(target: M3CanonicalTarget): Pair<Int, Int>? =
        if (target.normalOctX !in -127..127 || target.normalOctY !in -127..127 || target.normalConfidence !in 0..255) null
        else (((target.normalOctX and 0xff) shl 8) or (target.normalOctY and 0xff)) to target.normalConfidence

    private fun checkedHighWater(start: Long, count: Int): Long? {
        if (start !in 1..UINT32_END || count < 0) return null
        return try { Math.addExact(start, count.toLong()).takeIf { it <= UINT32_END } } catch (_: ArithmeticException) { null }
    }

    private fun checkedProduct(left: Int, right: Int): Long? =
        try { Math.multiplyExact(left.toLong(), right.toLong()) } catch (_: ArithmeticException) { null }

    private fun journalFits(bytes: Long) = bytes <= M3CompactCanonicalStore.JOURNAL_RESERVE_BYTES &&
        bytes <= configuration.changeJournalByteCapacity.toLong()

    private fun encodedRecordBytes(rows: Int, removed: Int, supports: Long, sources: Long, edges: Long, commandId: String): Long? =
        try {
            val commandBytes = modifiedUtf8Length(commandId)
            val one = Math.addExact(174L + commandBytes, Math.addExact(
                Math.addExact(Math.multiplyExact(rows.toLong(), 60L), Math.multiplyExact(removed.toLong(), 8L)),
                Math.addExact(Math.multiplyExact(supports, 68L), Math.addExact(Math.multiplyExact(sources, 60L), Math.multiplyExact(edges, 16L))),
            ))
            one
        } catch (_: ArithmeticException) { null }

    private fun maximumSupportRecords(
        targets: Int,
        removed: Int,
        dirtySources: Int,
        edges: Long,
        commandId: String,
    ): Int? {
        if (targets <= 0) return 0
        if (!supportConstructionFits(targets, removed, 0)) return null
        val fixedEncoded = encodedRecordBytes(targets, removed, 0, dirtySources.toLong(), edges, commandId) ?: return null
        val journalLimit = minOf(M3CompactCanonicalStore.JOURNAL_RESERVE_BYTES, configuration.changeJournalByteCapacity.toLong())
        if (fixedEncoded > journalLimit) return null
        val perUniqueEncoded = Math.multiplyExact(targets.toLong(), 68L)
        var high = minOf(sourceCapacity(), (journalLimit - fixedEncoded) / perUniqueEncoded, Int.MAX_VALUE.toLong()).toInt()
        var low = 0
        while (low < high) {
            val middle = low + (high - low + 1) / 2
            if (supportConstructionFits(targets, removed, middle)) low = middle else high = middle - 1
        }
        return low
    }

    private fun supportConstructionFits(rows: Int, removed: Int, supports: Int): Boolean {
        val peak = try {
            Math.addExact(
                Math.addExact(
                    Math.addExact(PLAN_FIXED_OWNER_BYTES, WRITER_SCRATCH_BYTES),
                    PLANNING_PAGE_SCRATCH_BYTES,
                ),
                Math.addExact(
                    Math.addExact(
                        Math.multiplyExact(rows.toLong(), ROW_CONSTRUCTION_BYTES_PER_RECORD),
                        Math.multiplyExact(removed.toLong(), REMOVED_CONSTRUCTION_BYTES_PER_RECORD),
                    ),
                    Math.addExact(supportConstructionArrayBytes(supports), supportHashBytes(supports)),
                ),
            )
        } catch (_: ArithmeticException) {
            return false
        }
        return peak <= M3CompactCanonicalStore.JOURNAL_RESERVE_BYTES
    }

    private fun supportConstructionArrayBytes(limit: Int): Long {
        if (limit <= 0) return 0
        val previous = if (limit == 1) 0 else Integer.highestOneBit(limit - 1)
        return (limit.toLong() + previous.toLong()) * 60L
    }

    private fun supportHashBytes(limit: Int): Long {
        var capacity = 1L
        val needed = maxOf(1L, limit.toLong() * 2L)
        while (capacity < needed) capacity = Math.multiplyExact(capacity, 2L)
        return Math.multiplyExact(capacity, 4L)
    }

    private fun authorityWork() = view.readWorkReceipt() - startingReadWork

    private fun reliabilityBand(confidence: Int) = when (confidence) {
        0 -> M3NormalReliabilityBand.UNKNOWN
        in 1..63 -> M3NormalReliabilityBand.WEAK
        in 64..191 -> M3NormalReliabilityBand.RELIABLE
        else -> M3NormalReliabilityBand.STRONG
    }

    private fun sourceCapacity() = configuration.surfaceCapacity.toLong() + configuration.lineageCapacity.toLong()

    companion object {
        private const val UINT32_END = 0x1_0000_0000L
        // #117 gets one 16 KiB codec page, one 16 KiB checksum page, and 32 KiB of
        // bounded framing/index workspace. It must stream records and may not borrow plan ownership.
        internal const val WRITER_SCRATCH_BYTES = 65_536L
        internal const val PLAN_FIXED_OWNER_BYTES = 8_192L
        // One decoded 16 KiB source page plus bounded record/object decode overhead.
        internal const val PLANNING_PAGE_SCRATCH_BYTES = 65_536L
        internal const val ROW_CONSTRUCTION_BYTES_PER_RECORD = 256L
        internal const val REMOVED_CONSTRUCTION_BYTES_PER_RECORD = 40L

        fun prepare(view: M3CanonicalStateView, configuration: M3SurfaceOwnershipConfiguration, command: M3FeatureMutationCommand) =
            M3MutableCanonicalOverlay(view, configuration).prepareFeature(command)

        fun prepare(view: M3CanonicalStateView, configuration: M3SurfaceOwnershipConfiguration, command: M3CanonicalTransactionCommand) =
            M3MutableCanonicalOverlay(view, configuration).prepareStructural(command)

        fun prepare(
            view: M3CanonicalStateView,
            configuration: M3SurfaceOwnershipConfiguration,
            command: M3CanonicalFeatureBatchCommand,
        ): M3CanonicalMutationPreparation {
            if (!validCommandId(command.commandId)) return featureBatchRefusal(
                view.cut, M3CanonicalMutationRefusal.INVALID_COMMAND,
            )
            if (command.expectedGeometryRevision != view.cut.geometryRevision ||
                command.expectedLineageRevision != view.cut.lineageRevision
            ) return featureBatchRefusal(view.cut, M3CanonicalMutationRefusal.REVISION_CONFLICT)
            val budget = featureBatchBudget(configuration, command.commandId)
            var upserts = 0
            for (change in command.changes) if (change is M3FeatureFusionChange.Upsert) {
                upserts++
                if (upserts > configuration.surfaceCapacity) return featureBatchRefusal(
                    view.cut, M3CanonicalMutationRefusal.CAPACITY, budget.copy(upserts = upserts),
                )
                if (upserts > budget.maximumUpserts) return featureBatchRefusal(
                    view.cut, M3CanonicalMutationRefusal.JOURNAL_EXHAUSTED, budget.copy(upserts = upserts),
                )
            }
            val accepted = budget.copy(upserts = upserts)
            if (upserts == 0) return M3CanonicalMutationPreparation.NoOp(
                M3CanonicalStateReceipt(
                    view.cut.geometryRevision, view.cut.lineageRevision,
                    view.cut.nextSurfaceIdHighWater, view.cut.liveSurfaceCount,
                ),
            )
            return M3MutableCanonicalOverlay(view, configuration).prepareFeatureBatch(command, accepted)
        }

        internal fun featureBatchBudget(
            configuration: M3SurfaceOwnershipConfiguration,
            commandId: String,
        ): M3CanonicalFeatureBatchPreflightReceipt {
            val reserve = M3CompactCanonicalStore.JOURNAL_RESERVE_BYTES
            val journalLimit = minOf(reserve, configuration.changeJournalByteCapacity.toLong())
            val encodedFixed = 174L + modifiedUtf8Length(commandId)
            val journalAndCurrentMaximum = boundedRecordMaximum(journalLimit, encodedFixed, 188L)
            val sharedMaximum = boundedRecordMaximum(
                reserve, PLAN_FIXED_OWNER_BYTES + WRITER_SCRATCH_BYTES, 60L,
            )
            val constructionMaximum = boundedRecordMaximum(
                reserve,
                PLAN_FIXED_OWNER_BYTES + WRITER_SCRATCH_BYTES + PLANNING_PAGE_SCRATCH_BYTES,
                ROW_CONSTRUCTION_BYTES_PER_RECORD,
            )
            return M3CanonicalFeatureBatchPreflightReceipt(
                upserts = 0,
                maximumUpserts = minOf(journalAndCurrentMaximum, sharedMaximum, constructionMaximum),
                journalAndCurrentMaximum = journalAndCurrentMaximum,
                sharedMaximum = sharedMaximum,
                constructionMaximum = constructionMaximum,
            )
        }

        internal fun boundedRecordMaximum(limit: Long, fixed: Long, perRecord: Long): Int {
            if (limit < fixed || perRecord <= 0L) return 0
            return minOf((limit - fixed) / perRecord, Int.MAX_VALUE.toLong()).toInt()
        }

        private fun featureBatchRefusal(
            cut: M3CompactCanonicalCut,
            reason: M3CanonicalMutationRefusal,
            preflight: M3CanonicalFeatureBatchPreflightReceipt = M3CanonicalFeatureBatchPreflightReceipt(),
        ) = M3CanonicalMutationPreparation.Refused(
            reason,
            M3CanonicalStateReceipt(
                cut.geometryRevision, cut.lineageRevision, cut.nextSurfaceIdHighWater, cut.liveSurfaceCount,
            ),
            M3CanonicalMutationPreflightWork(
                featureBatchUpserts = preflight.upserts,
                featureBatchMaximumUpserts = preflight.maximumUpserts,
            ),
        )

        private fun validCommandId(value: String) = validM3CommandId(value)
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

/** Private bridge from one immutable kernel delta to one canonical v6 plan. */
internal data class M3CanonicalFeatureBatchCommand(
    val commandId: String,
    val expectedGeometryRevision: Long,
    val expectedLineageRevision: Long,
    val changes: List<M3FeatureFusionChange>,
) {
    internal fun fingerprint(): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        fun token(value: Any?) { digest.update(value.toString().encodeToByteArray()) }
        token(commandId); token('|'); token(expectedGeometryRevision); token('|'); token(expectedLineageRevision); token('|')
        changes.forEach { change ->
            when (change) {
                is M3FeatureFusionChange.Removal -> {
                    token('R'); token(':'); token(change.x); token(':'); token(change.y); token(':'); token(change.z)
                }
                is M3FeatureFusionChange.Upsert -> {
                    val primary = change.candidate.primaryCanonicalTarget()
                    token('U'); token(':'); token(change.x); token(':'); token(change.y); token(':'); token(change.z); token(':')
                    token(primary?.normalOctX); token(':'); token(primary?.normalOctY); token(':'); token(primary?.normalConfidence)
                }
            }
            token(';')
        }
        return digest.digest()
    }
}

internal data class M3CanonicalFeatureBatchPreflightReceipt(
    val upserts: Int = 0,
    val maximumUpserts: Int = 0,
    val journalAndCurrentMaximum: Int = 0,
    val sharedMaximum: Int = 0,
    val constructionMaximum: Int = 0,
)

internal enum class M3PreparedMutationKind {
    FEATURE_ADD, FEATURE_REFINE, CREATE, RELOCATION, MERGE, SPLIT, REPLACEMENT, FEATURE_BATCH;
    companion object { fun from(value: M3CanonicalOperation) = entries.first { it.name == value.name } }
}

internal data class M3PreparedSupport(val target: M3SurfaceId, val source: M3ImmutableSourceSupport)
internal enum class M3PreparedSupportMode { NONE, SELF, NEW_ONLY, CARTESIAN }

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
    val authorityDirectLookups: Int = 0,
    val authorityPageReads: Int = 0,
    val authorityInspectedRows: Int = 0,
    val authorityBytesRead: Long = 0,
    val retainedPlanBytes: Long = 0,
    val writerScratchBytes: Long = 0,
    val constructionPeakBytes: Long = 0,
    val supportRecordLimit: Int = 0,
    val rowArrayBytes: Long = 0,
    val removedArrayBytes: Long = 0,
    val supportArrayBytes: Long = 0,
    val supportHashBytes: Long = 0,
    val fixedOwnerBytes: Long = 0,
    val planningPageScratchBytes: Long = 0,
    val supportConstructionArrayPeakBytes: Long = 0,
    val rowConstructionBytes: Long = 0,
    val removedConstructionBytes: Long = 0,
)

internal class M3PreparedCanonicalMutation(
    /** Opaque capability; the retained authority is deliberately outside this bounded graph. */
    internal var authorityLease: M3CanonicalAuthorityLease,
    val sourceCut: M3CompactCanonicalCut,
    val commandHash: M3CanonicalReceiptBytes,
    val commandFingerprint: M3CanonicalReceiptBytes,
    val commandId: String,
    val kind: M3PreparedMutationKind,
    private val rows: M3PreparedRowTable,
    private val removedIds: LongArray,
    private val supports: M3PreparedSourceTable,
    private val supportMode: M3PreparedSupportMode,
    val removedSupportRecords: Int,
    val targetHighWater: Long,
    val targetLiveSurfaceCount: Int,
    val targetSourceCount: Int,
    val targetSupportCount: Int,
    val targetLineageCount: Int,
    val targetGeometryRevision: Long,
    val targetLineageRevision: Long,
    val work: M3CanonicalMutationWork,
) : AutoCloseable {
    private var lifecycle = M3PreparedMutationLifecycle.READY
    private var discardPending = false
    val dirtyRowCount get() = rows.size
    val removedSurfaceCount get() = removedIds.size

    @Synchronized internal fun lifecycle() = lifecycle

    @Synchronized internal fun bindAuthority(
        view: M3CanonicalStateView,
        owner: Any,
        onRelease: () -> Unit,
    ): Boolean {
        if (lifecycle != M3PreparedMutationLifecycle.READY ||
            M3CanonicalAuthorityLeaseRegistry.isActive(authorityLease)
        ) return false
        authorityLease = M3CanonicalAuthorityLeaseRegistry.acquire(view, owner, onRelease)
        return M3CanonicalAuthorityLeaseRegistry.isActive(authorityLease)
    }

    @Synchronized internal fun claim(): M3PreparedMutationClaimResult = when (lifecycle) {
        M3PreparedMutationLifecycle.READY -> {
            lifecycle = M3PreparedMutationLifecycle.IN_FLIGHT
            M3PreparedMutationClaimResult.Claimed
        }
        M3PreparedMutationLifecycle.IN_FLIGHT -> M3PreparedMutationClaimResult.AlreadyInFlight
        M3PreparedMutationLifecycle.CONSUMED,
        M3PreparedMutationLifecycle.DISCARDED -> M3PreparedMutationClaimResult.Terminal
    }

    internal fun finish(result: M3PreparedMutationFinish): M3PreparedMutationLifecycle {
        var release = false
        val finished = synchronized(this) {
            check(lifecycle == M3PreparedMutationLifecycle.IN_FLIGHT)
            lifecycle = when (result) {
                M3PreparedMutationFinish.SUCCESS -> M3PreparedMutationLifecycle.CONSUMED
                M3PreparedMutationFinish.TERMINAL -> M3PreparedMutationLifecycle.DISCARDED
                M3PreparedMutationFinish.RETRYABLE -> if (discardPending) {
                    M3PreparedMutationLifecycle.DISCARDED
                } else {
                    M3PreparedMutationLifecycle.READY
                }
            }
            release = lifecycle != M3PreparedMutationLifecycle.READY
            lifecycle
        }
        if (release) releaseSourceAuthority()
        return finished
    }

    internal fun discard(): M3PreparedMutationDiscardResult {
        var release = false
        val result = synchronized(this) {
            when (lifecycle) {
                M3PreparedMutationLifecycle.READY -> {
                    lifecycle = M3PreparedMutationLifecycle.DISCARDED
                    release = true
                    M3PreparedMutationDiscardResult.Discarded
                }
                M3PreparedMutationLifecycle.IN_FLIGHT -> if (discardPending) {
                    M3PreparedMutationDiscardResult.AlreadyPending
                } else {
                    discardPending = true
                    M3PreparedMutationDiscardResult.Deferred
                }
                M3PreparedMutationLifecycle.DISCARDED -> M3PreparedMutationDiscardResult.AlreadyDiscarded
                M3PreparedMutationLifecycle.CONSUMED -> M3PreparedMutationDiscardResult.AlreadyConsumed
            }
        }
        if (release) releaseSourceAuthority()
        return result
    }

    override fun close() { discard() }

    private fun releaseSourceAuthority() {
        M3CanonicalAuthorityLeaseRegistry.release(authorityLease)
    }

    fun visitDirtyRows(sink: (M3PreparedRow) -> Boolean) = rows.visit(sink)

    fun visitRemovedSurfaceIds(sink: (M3SurfaceId) -> Boolean) {
        for (id in removedIds) if (!sink(M3SurfaceId(id))) return
    }

    fun visitDirtySupport(sink: (M3PreparedSupport) -> Boolean) {
        when (supportMode) {
            M3PreparedSupportMode.NONE -> Unit
            M3PreparedSupportMode.SELF -> rows.visit { row ->
                sink(M3PreparedSupport(row.id, row.toSupport()))
            }
            M3PreparedSupportMode.NEW_ONLY -> rows.visit { row ->
                if (row.id.value < sourceCut.nextSurfaceIdHighWater) true else sink(M3PreparedSupport(row.id, row.toSupport()))
            }
            M3PreparedSupportMode.CARTESIAN -> rows.visit { row ->
                var keepGoing = true
                supports.visit { source ->
                    keepGoing = sink(M3PreparedSupport(row.id, source))
                    keepGoing
                }
                keepGoing
            }
        }
    }

    fun visitDirtySources(sink: (M3ImmutableSourceSupport) -> Boolean) = rows.visit { row ->
        if (row.id.value < sourceCut.nextSurfaceIdHighWater) true else sink(row.toSupport())
    }

    fun visitDirtyLineage(sink: (M3LineageEdge) -> Boolean) {
        for (source in removedIds) {
            var keepGoing = true
            rows.visit { row ->
                keepGoing = sink(M3LineageEdge(M3SurfaceId(source), row.id))
                keepGoing
            }
            if (!keepGoing) return
        }
    }

    fun writeWalTo(output: java.io.OutputStream) = writeTo(output, WAL_MAGIC)
    fun writeCurrentTo(output: java.io.OutputStream) = writeTo(output, CURRENT_MAGIC)

    private fun writeTo(output: java.io.OutputStream, magic: Int) {
        val out = DataOutputStream(output)
        out.writeInt(magic); out.writeInt(BODY_VERSION); out.write(sourceCut.rootHash.toByteArray())
        out.writeUTF(commandId); out.writeInt(kind.ordinal)
        out.write(commandHash.toByteArray()); out.write(commandFingerprint.toByteArray())
        out.writeLong(targetHighWater); out.writeInt(targetLiveSurfaceCount)
        out.writeInt(targetSourceCount); out.writeInt(targetSupportCount); out.writeInt(targetLineageCount)
        out.writeLong(targetGeometryRevision); out.writeLong(targetLineageRevision)
        out.writeInt(rows.size); rows.writeRecords(out)
        out.writeInt(removedIds.size); removedIds.forEach(out::writeLong)
        out.writeInt(removedSupportRecords)
        out.writeInt(work.dirtySupportRecords)
        when (supportMode) {
            M3PreparedSupportMode.NONE -> Unit
            M3PreparedSupportMode.SELF -> rows.writeSelfSupport(out)
            M3PreparedSupportMode.NEW_ONLY -> rows.writeNewSelfSupport(out, sourceCut.nextSurfaceIdHighWater)
            M3PreparedSupportMode.CARTESIAN -> rows.writeCartesianSupport(out, supports)
        }
        out.writeInt(work.dirtySourceRecords); rows.writeNewSources(out, sourceCut.nextSurfaceIdHighWater)
        out.writeInt(work.dirtyLineageRecords)
        removedIds.forEach { source -> rows.writeLineageTargets(out, source) }
        out.flush()
    }

    companion object {
        private const val WAL_MAGIC = 0x4d33574c
        private const val CURRENT_MAGIC = 0x4d334350
        private const val BODY_VERSION = 2
    }
}

internal enum class M3PreparedMutationLifecycle { READY, IN_FLIGHT, CONSUMED, DISCARDED }
internal enum class M3PreparedMutationClaimResult { Claimed, AlreadyInFlight, Terminal }
internal enum class M3PreparedMutationFinish { SUCCESS, RETRYABLE, TERMINAL }
internal sealed interface M3PreparedMutationDiscardResult {
    data object Discarded : M3PreparedMutationDiscardResult
    data object Deferred : M3PreparedMutationDiscardResult
    data object AlreadyPending : M3PreparedMutationDiscardResult
    data object AlreadyDiscarded : M3PreparedMutationDiscardResult
    data object AlreadyConsumed : M3PreparedMutationDiscardResult
}

internal data class M3PreparedRow(
    val id: M3SurfaceId,
    val voxel: M3Voxel,
    val packedNormal: Int,
    val normalConfidence: Int,
    val allocationFingerprint: M3CanonicalReceiptBytes,
) {
    fun toSupport() = M3ImmutableSourceSupport(id, voxel, packedNormal, normalConfidence, allocationFingerprint)
}

internal class M3PreparedRowTable private constructor(
    private val ids: LongArray,
    private val x: IntArray,
    private val y: IntArray,
    private val z: IntArray,
    private val normal: IntArray,
    private val confidence: IntArray,
    private val fingerprints: ByteArray,
) {
    val size get() = ids.size
    val allocatedBytes get() = size * 60L

    fun visit(sink: (M3PreparedRow) -> Boolean): Boolean {
        for (index in ids.indices) if (!sink(row(index))) return false
        return true
    }

    fun writeRecords(out: DataOutputStream) {
        for (index in ids.indices) writeSource(out, index)
    }

    fun writeSelfSupport(out: DataOutputStream) {
        for (index in ids.indices) { out.writeLong(ids[index]); writeSource(out, index) }
    }

    fun writeNewSelfSupport(out: DataOutputStream, highWater: Long) {
        for (index in ids.indices) if (ids[index] >= highWater) {
            out.writeLong(ids[index]); writeSource(out, index)
        }
    }

    fun writeCartesianSupport(out: DataOutputStream, supports: M3PreparedSourceTable) {
        for (target in ids) supports.writeForTarget(out, target)
    }

    fun writeNewSources(out: DataOutputStream, highWater: Long) {
        for (index in ids.indices) if (java.lang.Long.compareUnsigned(ids[index], highWater) >= 0) writeSource(out, index)
    }

    fun writeLineageTargets(out: DataOutputStream, source: Long) {
        for (target in ids) { out.writeLong(source); out.writeLong(target) }
    }

    private fun row(index: Int) = M3PreparedRow(
        M3SurfaceId(ids[index]), M3Voxel(x[index], y[index], z[index]), normal[index], confidence[index],
        M3CanonicalReceiptBytes(fingerprints.copyOfRange(index * 32, index * 32 + 32)),
    )

    private fun writeSource(out: DataOutputStream, index: Int) {
        out.writeLong(ids[index]); out.writeInt(x[index]); out.writeInt(y[index]); out.writeInt(z[index])
        out.writeInt(normal[index]); out.writeInt(confidence[index]); out.write(fingerprints, index * 32, 32)
    }

    companion object {
        fun from(rows: List<M3SurfaceOwner>): M3PreparedRowTable {
            val ids = LongArray(rows.size); val x = IntArray(rows.size); val y = IntArray(rows.size)
            val z = IntArray(rows.size); val normal = IntArray(rows.size); val confidence = IntArray(rows.size)
            val fingerprints = ByteArray(rows.size * 32)
            rows.forEachIndexed { index, row ->
                ids[index] = row.id.value; x[index] = row.voxel.x; y[index] = row.voxel.y; z[index] = row.voxel.z
                normal[index] = row.packedNormal; confidence[index] = row.normalConfidence
                row.allocatedBy.copyInto(fingerprints, index * 32)
            }
            return M3PreparedRowTable(ids, x, y, z, normal, confidence, fingerprints)
        }
    }
}

internal class M3PreparedSourceTable(
    private val ids: LongArray,
    private val x: IntArray,
    private val y: IntArray,
    private val z: IntArray,
    private val normal: IntArray,
    private val confidence: IntArray,
    private val fingerprints: ByteArray,
    val size: Int,
    val limit: Int,
    val constructionHashBytes: Long,
) {
    val allocatedBytes get() = ids.size * 60L
    val constructionArrayPeakBytes get(): Long {
        if (ids.isEmpty()) return 0
        val previous = if (ids.size == 1) 0 else Integer.highestOneBit(ids.size - 1)
        return (ids.size.toLong() + previous.toLong()) * 60L
    }

    fun visit(sink: (M3ImmutableSourceSupport) -> Boolean): Boolean {
        for (index in 0 until size) if (!sink(source(index))) return false
        return true
    }

    fun writeForTarget(out: DataOutputStream, target: Long) {
        for (index in 0 until size) { out.writeLong(target); writeSource(out, index) }
    }

    private fun source(index: Int) = M3ImmutableSourceSupport(
        M3SurfaceId(ids[index]), M3Voxel(x[index], y[index], z[index]), normal[index], confidence[index],
        M3CanonicalReceiptBytes(fingerprints.copyOfRange(index * 32, index * 32 + 32)),
    )

    private fun writeSource(out: DataOutputStream, index: Int) {
        out.writeLong(ids[index]); out.writeInt(x[index]); out.writeInt(y[index]); out.writeInt(z[index])
        out.writeInt(normal[index]); out.writeInt(confidence[index]); out.write(fingerprints, index * 32, 32)
    }

    companion object {
        val EMPTY = M3PreparedSourceTable(LongArray(0), IntArray(0), IntArray(0), IntArray(0), IntArray(0), IntArray(0), ByteArray(0), 0, 0, 0)
    }
}

private class M3BoundedSupportAccumulator(val limit: Int) {
    private var capacity = if (limit == 0) 0 else 1
    private var ids = LongArray(capacity)
    private var x = IntArray(capacity); private var y = IntArray(capacity); private var z = IntArray(capacity)
    private var normal = IntArray(capacity); private var confidence = IntArray(capacity)
    private var fingerprints = ByteArray(capacity * 32)
    private val hash = IntArray(hashCapacity(limit))
    private val mask = hash.size - 1
    var size = 0
        private set

    fun add(source: M3PagedSource): Boolean {
        var slot = mix(source.id.value) and mask
        while (true) {
            val stored = hash[slot]
            if (stored == 0) break
            if (ids[stored - 1] == source.id.value) return true
            slot = (slot + 1) and mask
        }
        if (size == limit) return false
        ensureCapacity(size + 1)
        ids[size] = source.id.value; x[size] = source.voxel.x; y[size] = source.voxel.y; z[size] = source.voxel.z
        normal[size] = source.packedNormal; confidence[size] = source.normalConfidence
        source.allocationFingerprint.toByteArray().copyInto(fingerprints, size * 32)
        hash[slot] = size + 1
        size++
        return true
    }

    fun freeze(): M3PreparedSourceTable {
        sort(0, size - 1)
        return M3PreparedSourceTable(ids, x, y, z, normal, confidence, fingerprints, size, limit, hash.size * 4L)
    }

    private fun ensureCapacity(required: Int) {
        if (required <= capacity) return
        val next = minOf(limit, maxOf(required, capacity * 2))
        ids = ids.copyOf(next); x = x.copyOf(next); y = y.copyOf(next); z = z.copyOf(next)
        normal = normal.copyOf(next); confidence = confidence.copyOf(next); fingerprints = fingerprints.copyOf(next * 32)
        capacity = next
    }

    private fun sort(low: Int, high: Int) {
        if (low >= high) return
        var left = low
        var right = high
        val pivot = ids[(low + high) ushr 1]
        while (left <= right) {
            while (java.lang.Long.compareUnsigned(ids[left], pivot) < 0) left++
            while (java.lang.Long.compareUnsigned(ids[right], pivot) > 0) right--
            if (left <= right) {
                swap(left, right)
                left++; right--
            }
        }
        if (low < right) sort(low, right)
        if (left < high) sort(left, high)
    }

    private fun swap(a: Int, b: Int) {
        if (a == b) return
        fun swap(values: IntArray) { val value = values[a]; values[a] = values[b]; values[b] = value }
        val id = ids[a]; ids[a] = ids[b]; ids[b] = id
        swap(x); swap(y); swap(z); swap(normal); swap(confidence)
        repeat(32) { offset ->
            val value = fingerprints[a * 32 + offset]
            fingerprints[a * 32 + offset] = fingerprints[b * 32 + offset]
            fingerprints[b * 32 + offset] = value
        }
    }

    private fun mix(value: Long): Int {
        var x = value
        x = (x xor (x ushr 33)) * -49064778989728563L
        x = (x xor (x ushr 33)) * -4265267296055464877L
        return (x xor (x ushr 33)).toInt()
    }

    companion object {
        private fun hashCapacity(limit: Int): Int {
            var capacity = 1
            val needed = maxOf(1L, limit.toLong() * 2L)
            while (capacity.toLong() < needed && capacity < (1 shl 30)) capacity = capacity shl 1
            return capacity
        }
    }
}

internal sealed interface M3CanonicalMutationPreparation {
    data class Prepared(val mutation: M3PreparedCanonicalMutation) : M3CanonicalMutationPreparation
    data class NoOp(val receipt: M3CanonicalStateReceipt) : M3CanonicalMutationPreparation
    data class Refused(
        val reason: M3CanonicalMutationRefusal,
        val receipt: M3CanonicalStateReceipt,
        val preflightWork: M3CanonicalMutationPreflightWork = M3CanonicalMutationPreflightWork(),
    ) : M3CanonicalMutationPreparation
}

internal data class M3CanonicalMutationPreflightWork(
    val targetDeepCopies: Int = 0,
    val targetSetInsertions: Int = 0,
    val ownerConstructions: Int = 0,
    val removalGraphAllocations: Int = 0,
    val featureBatchUpserts: Int = 0,
    val featureBatchMaximumUpserts: Int = 0,
)

internal enum class M3CanonicalMutationRefusal {
    INVALID_COMMAND, INVALID_OWNERSHIP, INVALID_NORMAL, UNKNOWN_IDENTITY, OWNERSHIP_CONFLICT,
    CAPACITY, EXHAUSTED, REVISION_CONFLICT, REVISION_EXHAUSTED, LINEAGE_EXHAUSTED,
    JOURNAL_EXHAUSTED, SOURCE_READ_FAILURE, ADJACENT_BUSY,
}

private fun Long.toIntExact(): Int = try { Math.toIntExact(this) } catch (_: ArithmeticException) { Int.MAX_VALUE }

internal const val M3_COMMAND_MODIFIED_UTF_BYTES = 256L

/** Exact DataOutputStream.writeUTF payload length without allocating its encoded form. */
internal fun modifiedUtf8Length(value: String): Long {
    var bytes = 0L
    value.forEach { character ->
        bytes = Math.addExact(bytes, when (character.code) {
            in 1..0x7f -> 1L
            in 0..0x7ff -> 2L
            else -> 3L
        })
    }
    return bytes
}

internal fun validM3CommandId(value: String): Boolean =
    value.isNotBlank() && modifiedUtf8Length(value) in 1..M3_COMMAND_MODIFIED_UTF_BYTES

private fun overlayHash(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)
