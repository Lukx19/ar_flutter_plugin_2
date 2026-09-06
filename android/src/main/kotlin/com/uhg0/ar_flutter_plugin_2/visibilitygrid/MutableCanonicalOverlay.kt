package com.uhg0.ar_flutter_plugin_2.visibilitygrid

import java.io.DataOutputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.Collections

/**
 * Private, bounded mutation overlay for the immutable v6 reader.
 *
 * The overlay deliberately owns only dirty rows and the evidence needed to
 * replace them.  It never materializes the reader, walks its live population,
 * reserves IDs, writes a WAL/root, or retains a current receipt.  Its returned
 * bytes are the exact future writer payloads, so later phases cannot discover
 * a journal or staging refusal after a reservation has been burned.
 */
internal class MutableCanonicalOverlay private constructor(
    private val view: CanonicalStateView,
    private val configuration: SurfaceOwnershipConfiguration,
) {
    private val startingReadWork = view.readWorkReceipt()
    private var pageFaults = 0
    private var bytesRead = 0
    private var directLookups = 0
    private var targetDeepCopies = 0
    private var targetSetInsertions = 0
    private var ownerConstructions = 0
    private var removalGraphAllocations = 0

    private fun state() = CanonicalStateReceipt(
        view.cut.geometryRevision,
        view.cut.lineageRevision,
        view.cut.nextSurfaceIdHighWater,
        view.cut.liveSurfaceCount,
    )

    private fun refuse(reason: CanonicalMutationRefusal) =
        CanonicalMutationPreparation.Refused(
            reason,
            state(),
            CanonicalMutationPreflightWork(
                targetDeepCopies,
                targetSetInsertions,
                ownerConstructions,
                removalGraphAllocations,
            ),
        )

    private fun prepareFeature(command: FeatureMutationCommand): CanonicalMutationPreparation {
        if (!validCommandId(command.commandId) ||
            command.expectedGeometryRevision != view.cut.geometryRevision ||
            command.expectedLineageRevision != view.cut.lineageRevision
        ) return refuse(if (!validCommandId(command.commandId)) CanonicalMutationRefusal.INVALID_COMMAND else CanonicalMutationRefusal.REVISION_CONFLICT)
        val location = CompactLocation(configuration, command.target.voxel)
            ?: return refuse(CanonicalMutationRefusal.INVALID_OWNERSHIP)
        val packed = packed(command.target) ?: return refuse(CanonicalMutationRefusal.INVALID_NORMAL)
        val existing = command.target.id?.let {
            directLookups++
            view.findById(it)
        }
        if (command.target.id != null && existing == null) return refuse(CanonicalMutationRefusal.UNKNOWN_IDENTITY)
        val occupied = run {
            directLookups++
            view.findByVoxel(command.target.voxel)
        }
        if (occupied != null && occupied.id != command.target.id) return refuse(CanonicalMutationRefusal.OWNERSHIP_CONFLICT)
        if (existing != null && existing.voxel != command.target.voxel) return refuse(CanonicalMutationRefusal.INVALID_COMMAND)
        if (existing != null && existing.packedNormal == packed.first &&
            reliabilityBand(existing.normalConfidence) == reliabilityBand(packed.second)) {
            return CanonicalMutationPreparation.NoOp(state())
        }
        if (view.cut.geometryRevision >= configuration.revisionLimit) return refuse(CanonicalMutationRefusal.REVISION_EXHAUSTED)

        val allocate = existing == null
        val high = checkedHighWater(view.cut.nextSurfaceIdHighWater, if (allocate) 1 else 0)
            ?: return refuse(CanonicalMutationRefusal.EXHAUSTED)
        if (allocate && view.cut.liveSurfaceCount >= configuration.surfaceCapacity) return refuse(CanonicalMutationRefusal.CAPACITY)
        if (allocate && view.cut.sourceCount.toLong() + 1L > sourceCapacity()) return refuse(CanonicalMutationRefusal.LINEAGE_EXHAUSTED)
        val fingerprint = command.fingerprint()
        val allocationFingerprint = if (existing == null) overlayHash(command.commandId.encodeToByteArray()) else {
            when (val source = view.readSourceById(existing.id)) {
                is CanonicalPageRead.Refused -> return refuse(CanonicalMutationRefusal.SOURCE_READ_FAILURE)
                is CanonicalPageRead.Complete -> {
                    pageFaults += source.pageFaults; bytesRead += source.bytesRead
                    source.value?.allocationFingerprint?.toByteArray()
                        ?: return refuse(CanonicalMutationRefusal.SOURCE_READ_FAILURE)
                }
            }
        }
        val id = command.target.id ?: SurfaceId(view.cut.nextSurfaceIdHighWater)
        val row = SurfaceOwner(id, view.cut.group, command.target.voxel, location.region, location.page,
            packed.first, packed.second, allocationFingerprint)
        return finish(
            command.commandId,
            if (allocate) PreparedMutationKind.FEATURE_ADD else PreparedMutationKind.FEATURE_REFINE,
            fingerprint,
            listOf(row), emptyList(), PreparedSourceTable.EMPTY,
            if (allocate) PreparedSupportMode.SELF else PreparedSupportMode.NONE,
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
     * canonical retention/no-op: canonical surface has no structural removal semantics yet.
     * It therefore never deletes an owner, support, source, or lineage row.
     */
    private fun prepareFeatureBatch(
        command: CanonicalFeatureBatchCommand,
        preflight: CanonicalFeatureBatchPreflightReceipt,
    ): CanonicalMutationPreparation {
        // Removal has no structural effect at this seam. A scalar-only first
        // pass therefore admits an arbitrarily large removal-only durable
        // no-op, while bounding every collection owned by an effective batch.
        if (preflight.upserts == 0) return CanonicalMutationPreparation.NoOp(state())
        val upserts = ArrayList<CanonicalTarget>(preflight.upserts)
        val seenVoxels = HashSet<Voxel>()
        for (change in command.changes) {
            when (change) {
                is FeatureFusionChange.Removal -> Unit
                is FeatureFusionChange.Upsert -> {
                    val voxel = Voxel(change.x, change.y, change.z)
                    if (!seenVoxels.add(voxel)) return refuse(CanonicalMutationRefusal.OWNERSHIP_CONFLICT)
                    targetSetInsertions++
                    val primary = change.candidate.primaryCanonicalTarget()
                        ?: return refuse(CanonicalMutationRefusal.INVALID_NORMAL)
                    // A candidate is self-describing; accepting a mismatched
                    // primary would make the kernel delta non-canonical.
                    if (primary.voxel != voxel) return refuse(CanonicalMutationRefusal.INVALID_COMMAND)
                    targetDeepCopies++
                    upserts += primary.copy(voxel = primary.voxel.copy())
                }
            }
        }
        // Duplicate removals remain no-op, but a removal of an upsert voxel is
        // a typed conflict without inserting any removal into the bounded set.
        for (change in command.changes) if (change is FeatureFusionChange.Removal &&
            Voxel(change.x, change.y, change.z) in seenVoxels
        ) return refuse(CanonicalMutationRefusal.OWNERSHIP_CONFLICT)
        if (view.cut.geometryRevision >= configuration.revisionLimit) return refuse(CanonicalMutationRefusal.REVISION_EXHAUSTED)

        val material = ArrayList<CanonicalTarget>(upserts.size)
        var allocations = 0
        for (target in upserts) {
            val location = CompactLocation(configuration, target.voxel)
                ?: return refuse(CanonicalMutationRefusal.INVALID_OWNERSHIP)
            val normal = packed(target) ?: return refuse(CanonicalMutationRefusal.INVALID_NORMAL)
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
        if (material.isEmpty()) return CanonicalMutationPreparation.NoOp(state())
        val high = checkedHighWater(view.cut.nextSurfaceIdHighWater, allocations)
            ?: return refuse(CanonicalMutationRefusal.EXHAUSTED)
        val finalLive = view.cut.liveSurfaceCount.toLong() + allocations.toLong()
        if (finalLive > configuration.surfaceCapacity || finalLive > Int.MAX_VALUE) return refuse(CanonicalMutationRefusal.CAPACITY)
        val finalSources = view.cut.sourceCount.toLong() + allocations.toLong()
        if (finalSources > sourceCapacity() || finalSources > Int.MAX_VALUE) return refuse(CanonicalMutationRefusal.LINEAGE_EXHAUSTED)
        val finalSupport = view.cut.supportCount.toLong() + allocations.toLong()
        if (finalSupport > sourceCapacity() || finalSupport > Int.MAX_VALUE) return refuse(CanonicalMutationRefusal.LINEAGE_EXHAUSTED)
        val estimatedBytes = encodedRecordBytes(material.size, 0, allocations.toLong(), allocations.toLong(), 0, command.commandId)
            ?: return refuse(CanonicalMutationRefusal.JOURNAL_EXHAUSTED)
        if (!journalFits(estimatedBytes)) return refuse(CanonicalMutationRefusal.JOURNAL_EXHAUSTED)

        var next = view.cut.nextSurfaceIdHighWater
        val commandHash = overlayHash(command.commandId.encodeToByteArray())
        val rows = material.sortedWith(compareBy<CanonicalTarget> { it.voxel.x }
            .thenBy { it.voxel.y }.thenBy { it.voxel.z }).map { target ->
            val id = target.id ?: SurfaceId(next++)
            val location = requireNotNull(CompactLocation(configuration, target.voxel))
            val normal = requireNotNull(packed(target))
            val provenance = if (target.id == null) commandHash else readAllocationFingerprint(id)
                ?: return refuse(CanonicalMutationRefusal.SOURCE_READ_FAILURE)
            SurfaceOwner(id, view.cut.group, target.voxel, location.region, location.page, normal.first, normal.second, provenance).also {
                ownerConstructions++
            }
        }.sortedBy { it.id.value }
        return finish(
            command.commandId, PreparedMutationKind.FEATURE_BATCH, command.fingerprint(), rows, emptyList(),
            PreparedSourceTable.EMPTY, PreparedSupportMode.NEW_ONLY, high, finalLive.toInt(),
            finalSources.toInt(), finalSupport.toInt(), view.cut.lineageCount,
            view.cut.geometryRevision + 1, view.cut.lineageRevision,
        )
    }

    private fun prepareStructural(input: CanonicalTransactionCommand): CanonicalMutationPreparation {
        if (!validCommandId(input.commandId) || input.targets.isEmpty()) return refuse(CanonicalMutationRefusal.INVALID_COMMAND)
        if (input.expectedGeometryRevision != view.cut.geometryRevision || input.expectedLineageRevision != view.cut.lineageRevision) return refuse(CanonicalMutationRefusal.REVISION_CONFLICT)
        val sourceCardinality = input.sourceIds.size
        val targetCardinality = input.targets.size
        if (sourceCardinality > view.cut.liveSurfaceCount) return refuse(CanonicalMutationRefusal.UNKNOWN_IDENTITY)
        if (targetCardinality.toLong() > configuration.surfaceCapacity.toLong() + sourceCardinality.toLong())
            return refuse(CanonicalMutationRefusal.CAPACITY)
        val minimumEdges = checkedProduct(sourceCardinality, targetCardinality)
            ?: return refuse(CanonicalMutationRefusal.LINEAGE_EXHAUSTED)
        if (view.cut.lineageCount.toLong() + minimumEdges > configuration.lineageCapacity.toLong()) {
            return refuse(CanonicalMutationRefusal.LINEAGE_EXHAUSTED)
        }
        val minimumStaging = encodedRecordBytes(targetCardinality, sourceCardinality, 0, 0, minimumEdges, input.commandId)
            ?: return refuse(CanonicalMutationRefusal.JOURNAL_EXHAUSTED)
        if (!journalFits(minimumStaging)) return refuse(CanonicalMutationRefusal.JOURNAL_EXHAUSTED)
        val scalarAllocations = input.targets.count { it.id == null }
        val supportLimit = maximumSupportRecords(
            targetCardinality, sourceCardinality, scalarAllocations, minimumEdges, input.commandId,
        ) ?: return refuse(CanonicalMutationRefusal.JOURNAL_EXHAUSTED)

        // Freeze caller-owned lists and values only after scalar cardinality admission.
        val command = input.copy(
            sourceIds = input.sourceIds.map {
                removalGraphAllocations++
                SurfaceId(it.value)
            },
            targets = input.targets.map {
                targetDeepCopies++
                it.copy(id = it.id?.let { id -> SurfaceId(id.value) }, voxel = it.voxel.copy())
            },
        )
        val sources = command.sourceIds
        if (sources.map { it.value }.toSet().size != sources.size) return refuse(CanonicalMutationRefusal.UNKNOWN_IDENTITY)
        val create = command.kind == CanonicalOperation.CREATE
        val shape = when (command.kind) {
            CanonicalOperation.CREATE -> sources.isEmpty() && command.targets.all { it.id == null } &&
                view.cut.liveSurfaceCount == 0 && view.cut.sourceCount == 0 && view.cut.supportCount == 0 && view.cut.lineageCount == 0
            CanonicalOperation.RELOCATION -> sources.size == 1 && command.targets.size == 1 && command.targets.single().id == sources.single()
            CanonicalOperation.MERGE -> sources.size >= 2 && command.targets.size == 1 && command.targets.single().id == null
            CanonicalOperation.SPLIT -> sources.size == 1 && command.targets.size >= 2 && command.targets.all { it.id == null }
            CanonicalOperation.REPLACEMENT -> command.targets.all { it.id == null }
        }
        if (!shape) return refuse(CanonicalMutationRefusal.INVALID_COMMAND)
        if (view.cut.geometryRevision >= configuration.revisionLimit || (!create && view.cut.lineageRevision >= configuration.revisionLimit)) return refuse(CanonicalMutationRefusal.REVISION_EXHAUSTED)

        for (id in sources) {
            directLookups++
            if (view.findById(id) == null) return refuse(CanonicalMutationRefusal.UNKNOWN_IDENTITY)
        }
        val vacated = sources.toSet()
        val seenVoxels = HashSet<Voxel>()
        for (target in command.targets) {
            val location = CompactLocation(configuration, target.voxel) ?: return refuse(CanonicalMutationRefusal.INVALID_OWNERSHIP)
            if (packed(target) == null) return refuse(CanonicalMutationRefusal.INVALID_NORMAL)
            if (!seenVoxels.add(target.voxel)) return refuse(CanonicalMutationRefusal.OWNERSHIP_CONFLICT)
            targetSetInsertions++
            if (target.id != null && target.id !in vacated) return refuse(CanonicalMutationRefusal.UNKNOWN_IDENTITY)
            directLookups++
            val occupied = view.findByVoxel(target.voxel)
            if (occupied != null && occupied.id !in vacated) return refuse(CanonicalMutationRefusal.OWNERSHIP_CONFLICT)
            // Keep this local calculation visible: planner must validate every dirty index target.
            check(location.page in 0..26)
        }
        val allocations = scalarAllocations
        val high = checkedHighWater(view.cut.nextSurfaceIdHighWater, allocations) ?: return refuse(CanonicalMutationRefusal.EXHAUSTED)
        val finalRowsLong = view.cut.liveSurfaceCount.toLong() - sources.size.toLong() + command.targets.size.toLong()
        if (finalRowsLong !in 0..configuration.surfaceCapacity.toLong()) return refuse(CanonicalMutationRefusal.CAPACITY)
        val finalRows = finalRowsLong.toInt()
        val finalSourceCount = view.cut.sourceCount.toLong() + allocations.toLong()
        if (finalSourceCount > sourceCapacity() || finalSourceCount > Int.MAX_VALUE) return refuse(CanonicalMutationRefusal.LINEAGE_EXHAUSTED)

        val edgeCardinality = minimumEdges
        val sourceSupport = BoundedSupportAccumulator(supportLimit)
        var removedSupports = 0L
        for (source in sources) {
            when (val read = readSupport(source, sourceSupport)) {
                is SupportRead.Refused -> return refuse(read.reason)
                is SupportRead.Complete -> {
                    removedSupports += read.records
                    if (read.records == 0L) {
                        when (val fallback = view.readSourceById(source)) {
                            is CanonicalPageRead.Refused -> return refuse(CanonicalMutationRefusal.SOURCE_READ_FAILURE)
                            is CanonicalPageRead.Complete -> {
                                pageFaults += fallback.pageFaults; bytesRead += fallback.bytesRead
                                fallback.value ?: return refuse(CanonicalMutationRefusal.UNKNOWN_IDENTITY)
                                if (!sourceSupport.add(fallback.value)) {
                                    return refuse(CanonicalMutationRefusal.JOURNAL_EXHAUSTED)
                                }
                            }
                        }
                    }
                }
            }
        }
        val supportProduct = checkedProduct(sourceSupport.size, command.targets.size)
            ?: return refuse(CanonicalMutationRefusal.LINEAGE_EXHAUSTED)
        if (view.cut.lineageCount.toLong() + edgeCardinality > configuration.lineageCapacity.toLong()) {
            return refuse(CanonicalMutationRefusal.LINEAGE_EXHAUSTED)
        }
        val dirtySourceCardinality = allocations
        val dirtySupportCardinality = if (create) command.targets.size.toLong() else supportProduct
        val exactStaging = encodedRecordBytes(command.targets.size, sources.size, dirtySupportCardinality, dirtySourceCardinality.toLong(), edgeCardinality, command.commandId)
            ?: return refuse(CanonicalMutationRefusal.JOURNAL_EXHAUSTED)
        if (!journalFits(exactStaging)) return refuse(CanonicalMutationRefusal.JOURNAL_EXHAUSTED)
        // Allocate exact IDs before constructing edges; no ID is reserved or burned here.
        var next = view.cut.nextSurfaceIdHighWater
        val fingerprint = command.fingerprint()
        val commandHash = overlayHash(command.commandId.encodeToByteArray())
        val rows = command.targets.map { target ->
            val id = target.id ?: SurfaceId(next++)
            val location = requireNotNull(CompactLocation(configuration, target.voxel))
            val normal = requireNotNull(packed(target))
            val provenance = if (target.id == null) commandHash else
                readAllocationFingerprint(id)
                    ?: return refuse(CanonicalMutationRefusal.SOURCE_READ_FAILURE)
            SurfaceOwner(id, view.cut.group, target.voxel, location.region, location.page, normal.first, normal.second, provenance).also {
                ownerConstructions++
            }
        }.sortedBy { it.id.value }
        if (view.cut.lineageCount.toLong() + edgeCardinality > configuration.lineageCapacity) return refuse(CanonicalMutationRefusal.LINEAGE_EXHAUSTED)
        val finalSupportLong = view.cut.supportCount.toLong() - removedSupports + dirtySupportCardinality
        if (finalSupportLong !in 0..sourceCapacity() || finalSupportLong > Int.MAX_VALUE) return refuse(CanonicalMutationRefusal.LINEAGE_EXHAUSTED)
        val finalSupport = finalSupportLong.toInt()
        return finish(
            command.commandId, PreparedMutationKind.from(command.kind), fingerprint, rows, sources,
            sourceSupport.freeze(), if (create) PreparedSupportMode.SELF else PreparedSupportMode.CARTESIAN,
            high, finalRows,
            finalSourceCount.toInt(), finalSupport,
            (view.cut.lineageCount.toLong() + edgeCardinality).toInt(),
            view.cut.geometryRevision + 1, if (create) view.cut.lineageRevision else view.cut.lineageRevision + 1,
        )
    }

    private fun finish(
        commandId: String,
        kind: PreparedMutationKind,
        fingerprint: ByteArray,
        rows: List<SurfaceOwner>,
        removed: List<SurfaceId>,
        supports: PreparedSourceTable,
        supportMode: PreparedSupportMode,
        high: Long, live: Int, sourceCount: Int, supportCount: Int, lineageCount: Int,
        geometry: Long, lineage: Long,
        supportPairs: PreparedSupportPairTable? = null,
        lineagePairs: PreparedLineageTable? = null,
        removedLineageRecords: Int = 0,
    ): CanonicalMutationPreparation {
        val rowTable = PreparedRowTable.from(rows)
        val removedIds = LongArray(removed.size) { removed[it].value }.also { it.sort() }
        val dirtySupportRecords = supportPairs?.size?.toLong() ?: when (supportMode) {
            PreparedSupportMode.NONE -> 0L
            PreparedSupportMode.SELF -> rows.size.toLong()
            PreparedSupportMode.NEW_ONLY -> rows.count { it.id.value >= view.cut.nextSurfaceIdHighWater }.toLong()
            PreparedSupportMode.CARTESIAN -> checkedProduct(supports.size, rows.size)
                ?: return refuse(CanonicalMutationRefusal.JOURNAL_EXHAUSTED)
        }
        val dirtySourceRecords = rows.count { it.id.value >= view.cut.nextSurfaceIdHighWater }
        val dirtyLineageRecords = lineagePairs?.size?.toLong()
            ?: checkedProduct(removed.size, rows.size)
            ?: return refuse(CanonicalMutationRefusal.LINEAGE_EXHAUSTED)
        val removedSupportRecords = try {
            Math.addExact(
                Math.subtractExact(view.cut.supportCount.toLong(), supportCount.toLong()),
                dirtySupportRecords,
            )
        } catch (_: ArithmeticException) { return refuse(CanonicalMutationRefusal.LINEAGE_EXHAUSTED) }
        if (removedSupportRecords !in 0..view.cut.supportCount.toLong())
            return refuse(CanonicalMutationRefusal.LINEAGE_EXHAUSTED)
        val encodedBytesBase = encodedRecordBytes(
            rows.size, removed.size, dirtySupportRecords, dirtySourceRecords.toLong(),
            dirtyLineageRecords, commandId,
        ) ?: return refuse(CanonicalMutationRefusal.JOURNAL_EXHAUSTED)
        val encodedBytes = try {
            Math.addExact(encodedBytesBase, if (kind == PreparedMutationKind.DEPTH_BATCH) 4L else 0L)
        } catch (_: ArithmeticException) { return refuse(CanonicalMutationRefusal.JOURNAL_EXHAUSTED) }
        if (!journalFits(encodedBytes)) return refuse(CanonicalMutationRefusal.JOURNAL_EXHAUSTED)
        val retainedPlanBytes = PLAN_FIXED_OWNER_BYTES + rowTable.allocatedBytes + removedIds.size * 8L +
            (supportPairs?.allocatedBytes ?: supports.allocatedBytes) + (lineagePairs?.allocatedBytes ?: 0L)
        val sharedReserveBytes = retainedPlanBytes + WRITER_SCRATCH_BYTES
        val constructionPeakBytes = PLAN_FIXED_OWNER_BYTES + WRITER_SCRATCH_BYTES + PLANNING_PAGE_SCRATCH_BYTES +
            rows.size * ROW_CONSTRUCTION_BYTES_PER_RECORD + removedIds.size * REMOVED_CONSTRUCTION_BYTES_PER_RECORD +
            supports.constructionArrayPeakBytes + supports.constructionHashBytes +
            (supportPairs?.allocatedBytes ?: 0L) + (lineagePairs?.allocatedBytes ?: 0L)
        if (sharedReserveBytes > CompactCanonicalStore.JOURNAL_RESERVE_BYTES ||
            constructionPeakBytes > CompactCanonicalStore.JOURNAL_RESERVE_BYTES
        ) return refuse(CanonicalMutationRefusal.JOURNAL_EXHAUSTED)
        val authority = authorityWork()
        val rowConstructionBytes = rows.size * ROW_CONSTRUCTION_BYTES_PER_RECORD
        val removedConstructionBytes = removedIds.size * REMOVED_CONSTRUCTION_BYTES_PER_RECORD
        val work = CanonicalMutationWork(
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
        return CanonicalMutationPreparation.Prepared(PreparedCanonicalMutation(
            CanonicalAuthorityLease(),
            view.cut, CanonicalReceiptBytes(overlayHash(commandId.encodeToByteArray())),
            CanonicalReceiptBytes(fingerprint), commandId, kind, rowTable, removedIds,
            supports, supportMode, removedSupportRecords.toIntExact(), high, live, sourceCount, supportCount, lineageCount,
            geometry, lineage, work, supportPairs, lineagePairs, removedLineageRecords,
        ))
    }

    private sealed interface SupportRead {
        data class Complete(val records: Long) : SupportRead
        data class Refused(val reason: CanonicalMutationRefusal) : SupportRead
    }

    private fun readSupport(target: SurfaceId, accumulator: BoundedSupportAccumulator): SupportRead {
        var cursor: SourceSupportCursor? = null
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
                is SourceSupportRead.Refused -> return SupportRead.Refused(CanonicalMutationRefusal.SOURCE_READ_FAILURE)
                is SourceSupportRead.Complete -> {
                    pageFaults += read.pageFaults; bytesRead += read.bytesRead; cursor = read.nextCursor
                    if (overflow) return SupportRead.Refused(CanonicalMutationRefusal.JOURNAL_EXHAUSTED)
                    if (++pages > view.cut.supportCount.toLong() + 1L) return SupportRead.Refused(CanonicalMutationRefusal.SOURCE_READ_FAILURE)
                }
            }
        } while (cursor != null)
        return SupportRead.Complete(records)
    }

    private fun readAllocationFingerprint(id: SurfaceId): ByteArray? =
        when (val read = view.readSourceById(id)) {
            is CanonicalPageRead.Refused -> null
            is CanonicalPageRead.Complete -> {
                pageFaults += read.pageFaults
                bytesRead += read.bytesRead
                read.value?.allocationFingerprint?.toByteArray()
            }
        }

    private fun packed(target: CanonicalTarget): Pair<Int, Int>? =
        if (target.normalOctX !in -127..127 || target.normalOctY !in -127..127 || target.normalConfidence !in 0..255) null
        else (((target.normalOctX and 0xff) shl 8) or (target.normalOctY and 0xff)) to target.normalConfidence

    private fun checkedHighWater(start: Long, count: Int): Long? {
        if (start !in 1..UINT32_END || count < 0) return null
        return try { Math.addExact(start, count.toLong()).takeIf { it <= UINT32_END } } catch (_: ArithmeticException) { null }
    }

    private fun checkedProduct(left: Int, right: Int): Long? =
        try { Math.multiplyExact(left.toLong(), right.toLong()) } catch (_: ArithmeticException) { null }

    private fun journalFits(bytes: Long) = bytes <= CompactCanonicalStore.JOURNAL_RESERVE_BYTES &&
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
        val journalLimit = minOf(CompactCanonicalStore.JOURNAL_RESERVE_BYTES, configuration.changeJournalByteCapacity.toLong())
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
        return peak <= CompactCanonicalStore.JOURNAL_RESERVE_BYTES
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
        0 -> NormalReliabilityBand.UNKNOWN
        in 1..63 -> NormalReliabilityBand.WEAK
        in 64..191 -> NormalReliabilityBand.RELIABLE
        else -> NormalReliabilityBand.STRONG
    }

    /**
     * Counts the largest primitive collections before any operation/source/target graph is
     * allocated.  Every source is admitted only once below, so the relational edge bound is
     * exact for a valid batch (source IDs and target voxels are globally unique).
     */
    private fun depthBatchScalars(command: CanonicalEvidenceBatchCommand): DepthBatchScalars? {
        var sources = 0L
        var targets = 0L
        var lineage = 0L
        var removed = 0L
        var allocations = 0L
        var minimumSupports = 0L
        fun add(value: Long, current: Long): Long? = try { Math.addExact(current, value) } catch (_: ArithmeticException) { null }
        fun product(left: Int, right: Int): Long? = try { Math.multiplyExact(left.toLong(), right.toLong()) } catch (_: ArithmeticException) { null }
        command.changes.forEach { change ->
            when (change) {
                is DepthEvidenceChange.Create -> {
                    targets = add(1, targets) ?: return null
                    allocations = add(1, allocations) ?: return null
                    minimumSupports = add(1, minimumSupports) ?: return null
                }
                is DepthEvidenceChange.Refine,
                is DepthEvidenceChange.Relocate -> {
                    sources = add(1, sources) ?: return null
                    targets = add(1, targets) ?: return null
                    if (change is DepthEvidenceChange.Relocate) {
                        lineage = add(1, lineage) ?: return null
                        removed = add(1, removed) ?: return null
                        minimumSupports = add(1, minimumSupports) ?: return null
                    }
                }
                is DepthEvidenceChange.Merge -> {
                    sources = add(change.sourceIds.size.toLong(), sources) ?: return null
                    targets = add(1, targets) ?: return null
                    lineage = add(change.sourceIds.size.toLong(), lineage) ?: return null
                    removed = add(change.sourceIds.size.toLong(), removed) ?: return null
                    minimumSupports = add(change.sourceIds.size.toLong(), minimumSupports) ?: return null
                    allocations = add(1, allocations) ?: return null
                }
                is DepthEvidenceChange.Split -> {
                    sources = add(1, sources) ?: return null
                    targets = add(change.targets.size.toLong(), targets) ?: return null
                    lineage = add(product(1, change.targets.size) ?: return null, lineage) ?: return null
                    removed = add(1, removed) ?: return null
                    minimumSupports = add(change.targets.size.toLong(), minimumSupports) ?: return null
                    allocations = add(change.targets.size.toLong(), allocations) ?: return null
                }
                is DepthEvidenceChange.Replace -> {
                    sources = add(change.sourceIds.size.toLong(), sources) ?: return null
                    targets = add(change.targets.size.toLong(), targets) ?: return null
                    lineage = add(product(change.sourceIds.size, change.targets.size) ?: return null, lineage) ?: return null
                    removed = add(change.sourceIds.size.toLong(), removed) ?: return null
                    minimumSupports = add(product(change.sourceIds.size, change.targets.size) ?: return null, minimumSupports) ?: return null
                    allocations = add(change.targets.size.toLong(), allocations) ?: return null
                }
                is DepthEvidenceChange.Remove -> {
                    sources = add(1, sources) ?: return null
                    removed = add(1, removed) ?: return null
                }
            }
        }
        if (sources > sourceCapacity() || targets > configuration.surfaceCapacity || lineage > configuration.lineageCapacity) return null
        return DepthBatchScalars(
            sources.toInt(), targets.toInt(), lineage, removed.toInt(), allocations.toInt(), minimumSupports,
        )
    }

    private fun prepareEvidenceBatch(command: CanonicalEvidenceBatchCommand): CanonicalMutationPreparation {
        if (!validCommandId(command.commandId) || command.changes.isEmpty()) {
            return refuse(CanonicalMutationRefusal.INVALID_COMMAND)
        }
        if (command.expectedGeometryRevision != view.cut.geometryRevision ||
            command.expectedLineageRevision != view.cut.lineageRevision
        ) return refuse(CanonicalMutationRefusal.REVISION_CONFLICT)
        if (view.cut.geometryRevision == Long.MAX_VALUE) return refuse(CanonicalMutationRefusal.REVISION_EXHAUSTED)
        val scalars = depthBatchScalars(command) ?: return refuse(CanonicalMutationRefusal.CAPACITY)
        if (command.changes.size > configuration.surfaceCapacity + configuration.lineageCapacity) {
            return refuse(CanonicalMutationRefusal.CAPACITY)
        }
        val scalarEncodedBase = encodedRecordBytes(
            scalars.targetRows, scalars.removedRows, scalars.minimumSupportPairs,
            scalars.allocatedRows.toLong(), scalars.lineageEdges, command.commandId,
        ) ?: return refuse(CanonicalMutationRefusal.JOURNAL_EXHAUSTED)
        val scalarEncodedBytes = try { Math.addExact(scalarEncodedBase, 4L) }
            catch (_: ArithmeticException) { return refuse(CanonicalMutationRefusal.JOURNAL_EXHAUSTED) }
        if (!journalFits(scalarEncodedBytes)) return refuse(CanonicalMutationRefusal.JOURNAL_EXHAUSTED)

        data class Operation(val kind: DepthBatchOperationKind, val sources: List<SurfaceId>, val targets: IntArray)
        val operations = ArrayList<Operation>(command.changes.size)
        val sources = ArrayList<SurfaceId>(scalars.sourceReferences)
        val structuralSources = HashSet<SurfaceId>(scalars.sourceReferences)
        val targets = ArrayList<CanonicalTarget>(scalars.targetRows)
        val sourceSeen = HashSet<SurfaceId>(scalars.sourceReferences)
        val targetVoxels = HashSet<Voxel>(scalars.targetRows)
        fun addSource(source: SurfaceId, structural: Boolean): Boolean {
            if (!sourceSeen.add(source)) return false
            sources += source
            if (structural) structuralSources += source
            return true
        }
        fun addTarget(target: CanonicalTarget): Int {
            if (!targetVoxels.add(target.voxel)) return -1
            targets += target
            return targets.lastIndex
        }
        for (change in command.changes) {
            when (change) {
                is DepthEvidenceChange.Create -> {
                    if (change.target.id != null) return refuse(CanonicalMutationRefusal.INVALID_COMMAND)
                    val target = addTarget(change.target.copy(id = null))
                    if (target < 0) return refuse(CanonicalMutationRefusal.OWNERSHIP_CONFLICT)
                    operations += Operation(DepthBatchOperationKind.CREATE, emptyList(), intArrayOf(target))
                }
                is DepthEvidenceChange.Refine -> {
                    if (change.target.id != change.sourceId || !addSource(change.sourceId, DepthBatchOperationKind.REFINE.structural)) {
                        return refuse(CanonicalMutationRefusal.OWNERSHIP_CONFLICT)
                    }
                    val target = addTarget(change.target.copy(id = change.sourceId))
                    if (target < 0) return refuse(CanonicalMutationRefusal.OWNERSHIP_CONFLICT)
                    operations += Operation(DepthBatchOperationKind.REFINE, listOf(change.sourceId), intArrayOf(target))
                }
                is DepthEvidenceChange.Relocate -> {
                    if (change.target.id != change.sourceId || !addSource(change.sourceId, DepthBatchOperationKind.RELOCATE.structural)) {
                        return refuse(CanonicalMutationRefusal.OWNERSHIP_CONFLICT)
                    }
                    val target = addTarget(change.target.copy(id = change.sourceId))
                    if (target < 0) return refuse(CanonicalMutationRefusal.OWNERSHIP_CONFLICT)
                    operations += Operation(DepthBatchOperationKind.RELOCATE, listOf(change.sourceId), intArrayOf(target))
                }
                is DepthEvidenceChange.Merge -> {
                    if (change.sourceIds.size < 2 || change.target.id != null ||
                        change.sourceIds.any { !addSource(it, DepthBatchOperationKind.MERGE.structural) }
                    ) return refuse(CanonicalMutationRefusal.OWNERSHIP_CONFLICT)
                    val target = addTarget(change.target.copy(id = null))
                    if (target < 0) return refuse(CanonicalMutationRefusal.OWNERSHIP_CONFLICT)
                    operations += Operation(DepthBatchOperationKind.MERGE, change.sourceIds.toList(), intArrayOf(target))
                }
                is DepthEvidenceChange.Split -> {
                    if (change.targets.size < 2 || !addSource(change.sourceId, DepthBatchOperationKind.SPLIT.structural) ||
                        change.targets.any { it.id != null }
                    ) return refuse(CanonicalMutationRefusal.OWNERSHIP_CONFLICT)
                    val targetIndexes = IntArray(change.targets.size) { index -> addTarget(change.targets[index].copy(id = null)) }
                    if (targetIndexes.any { it < 0 }) return refuse(CanonicalMutationRefusal.OWNERSHIP_CONFLICT)
                    operations += Operation(DepthBatchOperationKind.SPLIT, listOf(change.sourceId), targetIndexes)
                }
                is DepthEvidenceChange.Replace -> {
                    if (change.sourceIds.isEmpty() || change.targets.isEmpty() ||
                        change.sourceIds.any { !addSource(it, DepthBatchOperationKind.REPLACE.structural) } || change.targets.any { it.id != null }
                    ) return refuse(CanonicalMutationRefusal.OWNERSHIP_CONFLICT)
                    val targetIndexes = IntArray(change.targets.size) { index -> addTarget(change.targets[index].copy(id = null)) }
                    if (targetIndexes.any { it < 0 }) return refuse(CanonicalMutationRefusal.OWNERSHIP_CONFLICT)
                    operations += Operation(DepthBatchOperationKind.REPLACE, change.sourceIds.toList(), targetIndexes)
                }
                is DepthEvidenceChange.Remove -> {
                    if (!addSource(change.sourceId, DepthBatchOperationKind.REMOVE.structural)) return refuse(CanonicalMutationRefusal.OWNERSHIP_CONFLICT)
                    operations += Operation(DepthBatchOperationKind.REMOVE, listOf(change.sourceId), IntArray(0))
                }
            }
        }
        if (sources.size > configuration.surfaceCapacity + configuration.lineageCapacity) {
            return refuse(CanonicalMutationRefusal.CAPACITY)
        }
        val sourceRows = HashMap<SurfaceId, CompactSurface>(sources.size)
        sources.forEach { source ->
            directLookups++
            val row = view.findById(source) ?: return refuse(CanonicalMutationRefusal.UNKNOWN_IDENTITY)
            sourceRows[source] = row
        }
        operations.filter { it.kind == DepthBatchOperationKind.REFINE }.forEach { operation ->
            val source = operation.sources.single()
            val target = targets[operation.targets.single()]
            if (sourceRows[source]?.voxel != target.voxel) {
                return refuse(CanonicalMutationRefusal.OWNERSHIP_CONFLICT)
            }
        }
        val vacated = structuralSources
        targets.forEach { target ->
            directLookups++
            val occupied = view.findByVoxel(target.voxel)
            if (occupied != null && occupied.id !in vacated && occupied.id != target.id) {
                return refuse(CanonicalMutationRefusal.OWNERSHIP_CONFLICT)
            }
            val explicitId = target.id
            if (explicitId != null && explicitId !in sourceSeen) {
                return refuse(CanonicalMutationRefusal.UNKNOWN_IDENTITY)
            }
        }
        if (structuralSources.isNotEmpty() && view.cut.lineageRevision >= configuration.revisionLimit &&
            operations.any { it.kind.lineageChanged }
        ) return refuse(CanonicalMutationRefusal.REVISION_EXHAUSTED)

        val outgoingLineage = HashMap<SurfaceId, Int>()
        fun readOutgoingLineage(source: SurfaceId): Int? {
            outgoingLineage[source]?.let { return it }
            var cursor: LineageCursor? = null
            var count = 0L
            var pages = 0
            do {
                val read = view.visitLineage(source, cursor) {
                    count++
                    count <= configuration.lineageCapacity.toLong()
                }
                when (read) {
                    is LineageRead.Refused -> return null
                    is LineageRead.Complete -> {
                        cursor = read.nextCursor
                        if (++pages > configuration.lineageCapacity) return null
                    }
                }
            } while (cursor != null)
            if (count > Int.MAX_VALUE.toLong()) return null
            return count.toInt().also { outgoingLineage[source] = it }
        }
        val removedLineageRecords = structuralSources.sumOf { readOutgoingLineage(it) ?: return refuse(CanonicalMutationRefusal.SOURCE_READ_FAILURE) }

        data class SupportDetails(val values: List<ImmutableSourceSupport>, val records: Int)
        val details = HashMap<SurfaceId, SupportDetails>()
        fun readDetails(source: SurfaceId): SupportDetails? {
            details[source]?.let { return it }
            var cursor: SourceSupportCursor? = null
            val values = ArrayList<ImmutableSourceSupport>()
            var records = 0
            var pages = 0L
            do {
                val read = view.visitSourceSupport(source, cursor) { support ->
                    records++
                    if (records > sourceCapacity().coerceAtMost(Int.MAX_VALUE.toLong())) return@visitSourceSupport false
                    values += support.source.toImmutableSupport()
                    true
                }
                when (read) {
                    is SourceSupportRead.Refused -> return null
                    is SourceSupportRead.Complete -> {
                        pageFaults += read.pageFaults; bytesRead += read.bytesRead; cursor = read.nextCursor
                        if (++pages > view.cut.supportCount.toLong() + 1L) return null
                    }
                }
            } while (cursor != null)
            if (values.isEmpty()) {
                val sourceValue = readAllocationSource(source) ?: return null
                values += sourceValue.toImmutableSupport()
            }
            return SupportDetails(Collections.unmodifiableList(values), records).also { details[source] = it }
        }
        structuralSources.forEach { source -> if (readDetails(source) == null) return refuse(CanonicalMutationRefusal.SOURCE_READ_FAILURE) }

        val overlay = this
        val commandHash = overlayHash(command.commandId.encodeToByteArray())
        val allocatedIds = LongArray(targets.size)
        var next = view.cut.nextSurfaceIdHighWater
        targets.forEachIndexed { index, target ->
            allocatedIds[index] = target.id?.value ?: next++
        }
        val high = checkedHighWater(view.cut.nextSurfaceIdHighWater, targets.count { it.id == null })
            ?: return refuse(CanonicalMutationRefusal.EXHAUSTED)
        val rows = ArrayList<SurfaceOwner>(targets.size)
        targets.forEachIndexed { index, target ->
            val location = CompactLocation(configuration, target.voxel)
                ?: return refuse(CanonicalMutationRefusal.INVALID_OWNERSHIP)
            val normal = packed(target) ?: return refuse(CanonicalMutationRefusal.INVALID_NORMAL)
            val id = SurfaceId(allocatedIds[index])
            val fingerprint = if (target.id == null) commandHash else readAllocationFingerprint(id)
                ?: return refuse(CanonicalMutationRefusal.SOURCE_READ_FAILURE)
            rows += SurfaceOwner(id, view.cut.group, target.voxel, location.region, location.page,
                normal.first, normal.second, fingerprint)
        }

        var supportPairCount = 0L
        operations.forEach { operation ->
            val sourceSupportCount = when (operation.kind) {
                DepthBatchOperationKind.CREATE,
                DepthBatchOperationKind.REFINE,
                DepthBatchOperationKind.REMOVE -> 0L
                else -> operation.sources.sumOf { readDetails(it)?.values?.size?.toLong() ?: 0L }
            }
            val contribution = when (operation.kind.supportMode) {
                DepthBatchSupportMode.SELF -> operation.targets.size.toLong()
                DepthBatchSupportMode.NONE -> 0L
                DepthBatchSupportMode.SOURCE_TO_TARGETS -> try { Math.multiplyExact(sourceSupportCount, operation.targets.size.toLong()) }
                    catch (_: ArithmeticException) { return refuse(CanonicalMutationRefusal.JOURNAL_EXHAUSTED) }
            }
            supportPairCount = try { Math.addExact(supportPairCount, contribution) }
            catch (_: ArithmeticException) { return refuse(CanonicalMutationRefusal.JOURNAL_EXHAUSTED) }
        }
        if (supportPairCount > Int.MAX_VALUE.toLong() || scalars.lineageEdges > Int.MAX_VALUE.toLong()) {
            return refuse(CanonicalMutationRefusal.JOURNAL_EXHAUSTED)
        }
        val pairStorageBytes = try { Math.addExact(Math.multiplyExact(supportPairCount, 68L), 64L) }
        catch (_: ArithmeticException) { return refuse(CanonicalMutationRefusal.JOURNAL_EXHAUSTED) }
        val lineageStorageBytes = try { Math.addExact(Math.multiplyExact(scalars.lineageEdges, 16L), 40L) }
        catch (_: ArithmeticException) { return refuse(CanonicalMutationRefusal.JOURNAL_EXHAUSTED) }
        val preflightRetainedBytes = try {
            Math.addExact(
                Math.addExact(PLAN_FIXED_OWNER_BYTES, rows.size * 60L),
                Math.addExact(structuralSources.size * 8L, Math.addExact(pairStorageBytes, lineageStorageBytes)),
            )
        } catch (_: ArithmeticException) { return refuse(CanonicalMutationRefusal.JOURNAL_EXHAUSTED) }
        val preflightConstructionBytes = try {
            Math.addExact(
                Math.addExact(preflightRetainedBytes, WRITER_SCRATCH_BYTES + PLANNING_PAGE_SCRATCH_BYTES),
                Math.addExact(
                    rows.size * ROW_CONSTRUCTION_BYTES_PER_RECORD + structuralSources.size * REMOVED_CONSTRUCTION_BYTES_PER_RECORD,
                    Math.addExact(supportPairCount * PREPARED_SUPPORT_CONSTRUCTION_BYTES_PER_RECORD, scalars.lineageEdges * PREPARED_LINEAGE_CONSTRUCTION_BYTES_PER_RECORD),
                ),
            )
        } catch (_: ArithmeticException) { return refuse(CanonicalMutationRefusal.JOURNAL_EXHAUSTED) }
        if (preflightRetainedBytes > CompactCanonicalStore.JOURNAL_RESERVE_BYTES - WRITER_SCRATCH_BYTES ||
            preflightConstructionBytes > CompactCanonicalStore.JOURNAL_RESERVE_BYTES
        ) return refuse(CanonicalMutationRefusal.JOURNAL_EXHAUSTED)
        val supportPairs = ArrayList<PreparedSupport>(supportPairCount.toInt())
        val lineagePairs = ArrayList<LineageEdge>(scalars.lineageEdges.toInt())
        val removedSupportTargets = HashSet<SurfaceId>()
        fun addSupport(targetIndex: Int, source: ImmutableSourceSupport) {
            supportPairs += PreparedSupport(SurfaceId(allocatedIds[targetIndex]), source)
        }
        operations.forEach { operation ->
            when (operation.kind.supportMode) {
                DepthBatchSupportMode.SELF -> rows[operation.targets.single()].let { row ->
                    addSupport(operation.targets.single(), ImmutableSourceSupport(
                        row.id, row.voxel, row.packedNormal, row.normalConfidence,
                        CanonicalReceiptBytes(row.allocatedBy.copyOf()),
                    ))
                }
                DepthBatchSupportMode.SOURCE_TO_TARGETS -> {
                    operation.sources.forEach { removedSupportTargets += it }
                    val union = operation.sources.flatMap { readDetails(it)?.values.orEmpty() }
                        .distinctBy { it.id.value }.sortedBy { it.id.value }
                    operation.targets.forEach { target -> union.forEach { addSupport(target, it) } }
                    operation.sources.forEach { source -> operation.targets.forEach { target ->
                        lineagePairs += LineageEdge(source, SurfaceId(allocatedIds[target]))
                    } }
                }
                DepthBatchSupportMode.NONE -> if (operation.kind.structural) {
                    removedSupportTargets += operation.sources.single()
                }
            }
        }
        val distinctSupports = supportPairs.distinctBy { it.target.value to it.source.id.value }
            .sortedWith(compareBy<PreparedSupport> { it.target.value }.thenBy { it.source.id.value })
        val distinctLineage = lineagePairs.distinctBy { it.source.value to it.target.value }
            .sortedWith(compareBy<LineageEdge> { it.source.value }.thenBy { it.target.value })
        val targetLive = view.cut.liveSurfaceCount + operations.sumOf { operation ->
            operation.kind.liveDelta(operation.sources.size, operation.targets.size)
        }
        val removedSupportRecords = removedSupportTargets.sumOf { readDetails(it)?.records ?: 0 }
        val targetSupport = view.cut.supportCount - removedSupportRecords + distinctSupports.size
        val allocations = targets.count { it.id == null }
        val targetSource = view.cut.sourceCount + allocations
        val targetLineage = view.cut.lineageCount - removedLineageRecords + distinctLineage.size
        if (targetLive !in 0..configuration.surfaceCapacity ||
            targetSource !in 0..sourceCapacity() || targetSupport !in 0..sourceCapacity() ||
            targetLineage !in 0..configuration.lineageCapacity
        ) return refuse(CanonicalMutationRefusal.CAPACITY)
        val estimatedBase = encodedRecordBytes(rows.size, structuralSources.size, distinctSupports.size.toLong(), allocations.toLong(), distinctLineage.size.toLong(), command.commandId)
            ?: return refuse(CanonicalMutationRefusal.JOURNAL_EXHAUSTED)
        val estimated = try { Math.addExact(estimatedBase, 4L) }
            catch (_: ArithmeticException) { return refuse(CanonicalMutationRefusal.JOURNAL_EXHAUSTED) }
        if (!journalFits(estimated)) return refuse(CanonicalMutationRefusal.JOURNAL_EXHAUSTED)
        val lineageChanged = operations.any { it.kind.lineageChanged }
        return finish(
            command.commandId, PreparedMutationKind.DEPTH_BATCH, command.fingerprint(),
            rows.sortedWith { left, right -> java.lang.Long.compareUnsigned(left.id.value, right.id.value) },
            structuralSources.sortedBy { it.value }, PreparedSourceTable.EMPTY, PreparedSupportMode.NONE,
            high, targetLive, targetSource, targetSupport, targetLineage,
            view.cut.geometryRevision + 1, view.cut.lineageRevision + if (lineageChanged) 1 else 0,
            PreparedSupportPairTable.from(distinctSupports), PreparedLineageTable.from(distinctLineage),
            removedLineageRecords,
        )
    }

    private fun readAllocationSource(id: SurfaceId): PagedSource? =
        when (val read = view.readSourceById(id)) {
            is CanonicalPageRead.Refused -> null
            is CanonicalPageRead.Complete -> {
                pageFaults += read.pageFaults; bytesRead += read.bytesRead; read.value
            }
        }

    private fun PagedSource.toImmutableSupport() =
        ImmutableSourceSupport(id, voxel, packedNormal, normalConfidence, allocationFingerprint)

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
        // Temporary callback values coexist with the compact primitive table until it is built.
        internal const val PREPARED_SUPPORT_CONSTRUCTION_BYTES_PER_RECORD = 128L
        internal const val PREPARED_LINEAGE_CONSTRUCTION_BYTES_PER_RECORD = 32L

        fun prepare(view: CanonicalStateView, configuration: SurfaceOwnershipConfiguration, command: FeatureMutationCommand) =
            MutableCanonicalOverlay(view, configuration).prepareFeature(command)

        fun prepare(view: CanonicalStateView, configuration: SurfaceOwnershipConfiguration, command: CanonicalTransactionCommand) =
            MutableCanonicalOverlay(view, configuration).prepareStructural(command)

        fun prepare(
            view: CanonicalStateView,
            configuration: SurfaceOwnershipConfiguration,
            command: CanonicalFeatureBatchCommand,
        ): CanonicalMutationPreparation {
            if (!validCommandId(command.commandId)) return featureBatchRefusal(
                view.cut, CanonicalMutationRefusal.INVALID_COMMAND,
            )
            if (command.expectedGeometryRevision != view.cut.geometryRevision ||
                command.expectedLineageRevision != view.cut.lineageRevision
            ) return featureBatchRefusal(view.cut, CanonicalMutationRefusal.REVISION_CONFLICT)
            val budget = featureBatchBudget(configuration, command.commandId)
            var upserts = 0
            for (change in command.changes) if (change is FeatureFusionChange.Upsert) {
                upserts++
                if (upserts > configuration.surfaceCapacity) return featureBatchRefusal(
                    view.cut, CanonicalMutationRefusal.CAPACITY, budget.copy(upserts = upserts),
                )
                if (upserts > budget.maximumUpserts) return featureBatchRefusal(
                    view.cut, CanonicalMutationRefusal.JOURNAL_EXHAUSTED, budget.copy(upserts = upserts),
                )
            }
            val accepted = budget.copy(upserts = upserts)
            if (upserts == 0) return CanonicalMutationPreparation.NoOp(
                CanonicalStateReceipt(
                    view.cut.geometryRevision, view.cut.lineageRevision,
                    view.cut.nextSurfaceIdHighWater, view.cut.liveSurfaceCount,
                ),
            )
            return MutableCanonicalOverlay(view, configuration).prepareFeatureBatch(command, accepted)
        }

        fun prepare(
            view: CanonicalStateView,
            configuration: SurfaceOwnershipConfiguration,
            command: CanonicalEvidenceBatchCommand,
        ): CanonicalMutationPreparation = MutableCanonicalOverlay(view, configuration).prepareEvidenceBatch(command)

        internal fun featureBatchBudget(
            configuration: SurfaceOwnershipConfiguration,
            commandId: String,
        ): CanonicalFeatureBatchPreflightReceipt {
            val reserve = CompactCanonicalStore.JOURNAL_RESERVE_BYTES
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
            return CanonicalFeatureBatchPreflightReceipt(
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
            cut: CompactCanonicalCut,
            reason: CanonicalMutationRefusal,
            preflight: CanonicalFeatureBatchPreflightReceipt = CanonicalFeatureBatchPreflightReceipt(),
        ) = CanonicalMutationPreparation.Refused(
            reason,
            CanonicalStateReceipt(
                cut.geometryRevision, cut.lineageRevision, cut.nextSurfaceIdHighWater, cut.liveSurfaceCount,
            ),
            CanonicalMutationPreflightWork(
                featureBatchUpserts = preflight.upserts,
                featureBatchMaximumUpserts = preflight.maximumUpserts,
            ),
        )

        private fun validCommandId(value: String) = validM3CommandId(value)
    }
}

/** One feature delta: no ID means add; an existing ID means geometry-only refinement. */
internal data class FeatureMutationCommand(
    val commandId: String,
    val expectedGeometryRevision: Long,
    val expectedLineageRevision: Long,
    val target: CanonicalTarget,
) {
    internal fun fingerprint(): ByteArray = overlayHash(buildString {
        append(commandId).append('|').append(expectedGeometryRevision).append('|').append(expectedLineageRevision).append('|')
        append(target.id?.value).append(':').append(target.voxel).append(':').append(target.normalOctX).append(':').append(target.normalOctY).append(':').append(target.normalConfidence)
    }.encodeToByteArray())
}

/** Private bridge from one immutable kernel delta to one canonical v6 plan. */
internal data class CanonicalFeatureBatchCommand(
    val commandId: String,
    val expectedGeometryRevision: Long,
    val expectedLineageRevision: Long,
    val changes: List<FeatureFusionChange>,
) {
    internal fun fingerprint(): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        fun token(value: Any?) { digest.update(value.toString().encodeToByteArray()) }
        token(commandId); token('|'); token(expectedGeometryRevision); token('|'); token(expectedLineageRevision); token('|')
        changes.forEach { change ->
            when (change) {
                is FeatureFusionChange.Removal -> {
                    token('R'); token(':'); token(change.x); token(':'); token(change.y); token(':'); token(change.z)
                }
                is FeatureFusionChange.Upsert -> {
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

internal data class CanonicalFeatureBatchPreflightReceipt(
    val upserts: Int = 0,
    val maximumUpserts: Int = 0,
    val journalAndCurrentMaximum: Int = 0,
    val sharedMaximum: Int = 0,
    val constructionMaximum: Int = 0,
)

private data class DepthBatchScalars(
    val sourceReferences: Int,
    val targetRows: Int,
    val lineageEdges: Long,
    val removedRows: Int,
    val allocatedRows: Int,
    val minimumSupportPairs: Long,
)

private enum class DepthBatchSupportMode { NONE, SELF, SOURCE_TO_TARGETS }

/** Semantic operation policy for one depth batch; no persisted/kernel ordinals are reused. */
private enum class DepthBatchOperationKind(
    val structural: Boolean,
    val lineageChanged: Boolean,
    val supportMode: DepthBatchSupportMode,
) {
    CREATE(false, false, DepthBatchSupportMode.SELF),
    REFINE(false, false, DepthBatchSupportMode.NONE),
    RELOCATE(true, true, DepthBatchSupportMode.SOURCE_TO_TARGETS),
    MERGE(true, true, DepthBatchSupportMode.SOURCE_TO_TARGETS),
    SPLIT(true, true, DepthBatchSupportMode.SOURCE_TO_TARGETS),
    REPLACE(true, true, DepthBatchSupportMode.SOURCE_TO_TARGETS),
    REMOVE(true, true, DepthBatchSupportMode.NONE);

    fun liveDelta(sourceCount: Int, targetCount: Int): Int = when (this) {
        CREATE -> targetCount
        REFINE, RELOCATE -> 0
        MERGE -> 1 - sourceCount
        SPLIT -> targetCount - 1
        REPLACE -> targetCount - sourceCount
        REMOVE -> -sourceCount
    }
}

/** One depth-kernel delta admitted as one canonical surface transaction. */
internal class CanonicalEvidenceBatchCommand(
    val commandId: String,
    val expectedGeometryRevision: Long,
    val expectedLineageRevision: Long,
    changes: List<DepthEvidenceChange>,
) {
    val changes: List<DepthEvidenceChange> = Collections.unmodifiableList(changes.map(::copyDepthChange))

    internal fun fingerprint(): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        val sink = object : OutputStream() { override fun write(value: Int) = Unit }
        DataOutputStream(java.security.DigestOutputStream(sink, digest)).use { out ->
            out.writeUTF(commandId); out.writeLong(expectedGeometryRevision); out.writeLong(expectedLineageRevision)
            out.writeInt(changes.size)
            changes.forEach { change -> writeDepthChange(out, change) }
        }
        return digest.digest()
    }
}

private fun copyDepthChange(change: DepthEvidenceChange): DepthEvidenceChange = when (change) {
    is DepthEvidenceChange.Create -> change.copy(target = change.target.copy(voxel = change.target.voxel.copy()))
    is DepthEvidenceChange.Refine -> change.copy(target = change.target.copy(voxel = change.target.voxel.copy()))
    is DepthEvidenceChange.Relocate -> change.copy(target = change.target.copy(voxel = change.target.voxel.copy()))
    is DepthEvidenceChange.Merge -> change.copy(
        sourceIds = Collections.unmodifiableList(change.sourceIds.toList()),
        target = change.target.copy(voxel = change.target.voxel.copy()),
    )
    is DepthEvidenceChange.Split -> change.copy(
        targets = Collections.unmodifiableList(change.targets.map { it.copy(voxel = it.voxel.copy()) }),
    )
    is DepthEvidenceChange.Replace -> change.copy(
        sourceIds = Collections.unmodifiableList(change.sourceIds.toList()),
        targets = Collections.unmodifiableList(change.targets.map { it.copy(voxel = it.voxel.copy()) }),
    )
    is DepthEvidenceChange.Remove -> change
}

private fun writeDepthChange(out: DataOutputStream, change: DepthEvidenceChange) {
    fun target(target: CanonicalTarget) {
        out.writeBoolean(target.id != null); target.id?.let { out.writeLong(it.value) }
        out.writeInt(target.voxel.x); out.writeInt(target.voxel.y); out.writeInt(target.voxel.z)
        out.writeInt(target.normalOctX); out.writeInt(target.normalOctY); out.writeInt(target.normalConfidence)
    }
    when (change) {
        is DepthEvidenceChange.Create -> { out.writeByte(0); target(change.target) }
        is DepthEvidenceChange.Refine -> { out.writeByte(1); out.writeLong(change.sourceId.value); target(change.target) }
        is DepthEvidenceChange.Relocate -> { out.writeByte(2); out.writeLong(change.sourceId.value); target(change.target) }
        is DepthEvidenceChange.Merge -> {
            out.writeByte(3); out.writeInt(change.sourceIds.size); change.sourceIds.forEach { out.writeLong(it.value) }; target(change.target)
        }
        is DepthEvidenceChange.Split -> {
            out.writeByte(4); out.writeLong(change.sourceId.value); out.writeInt(change.targets.size); change.targets.forEach(::target)
        }
        is DepthEvidenceChange.Replace -> {
            out.writeByte(5); out.writeInt(change.sourceIds.size); change.sourceIds.forEach { out.writeLong(it.value) }
            out.writeInt(change.targets.size); change.targets.forEach(::target)
        }
        is DepthEvidenceChange.Remove -> { out.writeByte(6); out.writeLong(change.sourceId.value) }
    }
}

internal enum class PreparedMutationKind {
    FEATURE_ADD, FEATURE_REFINE, CREATE, RELOCATION, MERGE, SPLIT, REPLACEMENT, FEATURE_BATCH, DEPTH_BATCH;
    companion object { fun from(value: CanonicalOperation) = entries.first { it.name == value.name } }
}

internal data class PreparedSupport(val target: SurfaceId, val source: ImmutableSourceSupport)
internal enum class PreparedSupportMode { NONE, SELF, NEW_ONLY, CARTESIAN }

/** Explicit support replacements for a mixed evidence batch. */
internal class PreparedSupportPairTable private constructor(
    private val targets: LongArray,
    private val sourceIds: LongArray,
    private val x: IntArray,
    private val y: IntArray,
    private val z: IntArray,
    private val normal: IntArray,
    private val confidence: IntArray,
    private val fingerprints: ByteArray,
) {
    val size: Int get() = targets.size
    val allocatedBytes: Long get() = size * 68L + TABLE_OBJECT_BYTES
    fun visit(sink: (PreparedSupport) -> Boolean): Boolean {
        for (index in targets.indices) if (!sink(value(index))) return false
        return true
    }
    fun writeTo(out: DataOutputStream) {
        targets.indices.forEach { index ->
            out.writeLong(targets[index]); out.writeLong(sourceIds[index]); out.writeInt(x[index]); out.writeInt(y[index]); out.writeInt(z[index])
            out.writeInt(normal[index]); out.writeInt(confidence[index]); out.write(fingerprints, index * 32, 32)
        }
    }
    private fun value(index: Int) = PreparedSupport(
        SurfaceId(targets[index]), ImmutableSourceSupport(
            SurfaceId(sourceIds[index]), Voxel(x[index], y[index], z[index]), normal[index], confidence[index],
            CanonicalReceiptBytes(fingerprints.copyOfRange(index * 32, index * 32 + 32)),
        ),
    )
    companion object {
        private const val TABLE_OBJECT_BYTES = 64L
        val EMPTY = PreparedSupportPairTable(LongArray(0), LongArray(0), IntArray(0), IntArray(0), IntArray(0), IntArray(0), IntArray(0), ByteArray(0))
        fun from(values: List<PreparedSupport>): PreparedSupportPairTable {
            val targets = LongArray(values.size); val sourceIds = LongArray(values.size)
            val x = IntArray(values.size); val y = IntArray(values.size); val z = IntArray(values.size)
            val normal = IntArray(values.size); val confidence = IntArray(values.size); val fingerprints = ByteArray(values.size * 32)
            values.forEachIndexed { index, support ->
                targets[index] = support.target.value
                sourceIds[index] = support.source.id.value
                x[index] = support.source.voxel.x; y[index] = support.source.voxel.y; z[index] = support.source.voxel.z
                normal[index] = support.source.packedNormal; confidence[index] = support.source.normalConfidence
                support.source.allocationFingerprint.toByteArray().copyInto(fingerprints, index * 32)
            }
            return PreparedSupportPairTable(targets, sourceIds, x, y, z, normal, confidence, fingerprints)
        }
    }
}

/** Explicit lineage replacements for a mixed evidence batch. */
internal class PreparedLineageTable private constructor(
    private val sources: LongArray,
    private val targets: LongArray,
) {
    val size: Int get() = sources.size
    val allocatedBytes: Long get() = size * 16L + TABLE_OBJECT_BYTES
    fun visit(sink: (LineageEdge) -> Boolean): Boolean {
        for (index in sources.indices) if (!sink(LineageEdge(SurfaceId(sources[index]), SurfaceId(targets[index])))) return false
        return true
    }
    fun writeTo(out: DataOutputStream) {
        sources.indices.forEach { index -> out.writeLong(sources[index]); out.writeLong(targets[index]) }
    }
    companion object {
        private const val TABLE_OBJECT_BYTES = 40L
        val EMPTY = PreparedLineageTable(LongArray(0), LongArray(0))
        fun from(values: List<LineageEdge>): PreparedLineageTable {
            val sources = LongArray(values.size); val targets = LongArray(values.size)
            values.forEachIndexed { index, edge -> sources[index] = edge.source.value; targets[index] = edge.target.value }
            return PreparedLineageTable(sources, targets)
        }
    }
}

internal data class CanonicalMutationWork(
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

internal class PreparedCanonicalMutation(
    /** Opaque capability; the retained authority is deliberately outside this bounded graph. */
    internal var authorityLease: CanonicalAuthorityLease,
    val sourceCut: CompactCanonicalCut,
    val commandHash: CanonicalReceiptBytes,
    val commandFingerprint: CanonicalReceiptBytes,
    val commandId: String,
    val kind: PreparedMutationKind,
    private val rows: PreparedRowTable,
    private val removedIds: LongArray,
    private val supports: PreparedSourceTable,
    private val supportMode: PreparedSupportMode,
    val removedSupportRecords: Int,
    val targetHighWater: Long,
    val targetLiveSurfaceCount: Int,
    val targetSourceCount: Int,
    val targetSupportCount: Int,
    val targetLineageCount: Int,
    val targetGeometryRevision: Long,
    val targetLineageRevision: Long,
    val work: CanonicalMutationWork,
    private val supportPairs: PreparedSupportPairTable? = null,
    private val lineagePairs: PreparedLineageTable? = null,
    val removedLineageRecords: Int = 0,
) : AutoCloseable {
    private var lifecycle = PreparedMutationLifecycle.READY
    private var discardPending = false
    val dirtyRowCount get() = rows.size
    val removedSurfaceCount get() = removedIds.size

    @Synchronized internal fun lifecycle() = lifecycle

    @Synchronized internal fun bindAuthority(
        view: CanonicalStateView,
        owner: Any,
        onRelease: () -> Unit,
    ): Boolean {
        if (lifecycle != PreparedMutationLifecycle.READY ||
            CanonicalAuthorityLeaseRegistry.isActive(authorityLease)
        ) return false
        authorityLease = CanonicalAuthorityLeaseRegistry.acquire(view, owner, onRelease)
        return CanonicalAuthorityLeaseRegistry.isActive(authorityLease)
    }

    @Synchronized internal fun claim(): PreparedMutationClaimResult = when (lifecycle) {
        PreparedMutationLifecycle.READY -> {
            lifecycle = PreparedMutationLifecycle.IN_FLIGHT
            PreparedMutationClaimResult.Claimed
        }
        PreparedMutationLifecycle.IN_FLIGHT -> PreparedMutationClaimResult.AlreadyInFlight
        PreparedMutationLifecycle.CONSUMED,
        PreparedMutationLifecycle.DISCARDED -> PreparedMutationClaimResult.Terminal
    }

    internal fun finish(result: PreparedMutationFinish): PreparedMutationLifecycle {
        var release = false
        val finished = synchronized(this) {
            check(lifecycle == PreparedMutationLifecycle.IN_FLIGHT)
            lifecycle = when (result) {
                PreparedMutationFinish.SUCCESS -> PreparedMutationLifecycle.CONSUMED
                PreparedMutationFinish.TERMINAL -> PreparedMutationLifecycle.DISCARDED
                PreparedMutationFinish.RETRYABLE -> if (discardPending) {
                    PreparedMutationLifecycle.DISCARDED
                } else {
                    PreparedMutationLifecycle.READY
                }
            }
            release = lifecycle != PreparedMutationLifecycle.READY
            lifecycle
        }
        if (release) releaseSourceAuthority()
        return finished
    }

    internal fun discard(): PreparedMutationDiscardResult {
        var release = false
        val result = synchronized(this) {
            when (lifecycle) {
                PreparedMutationLifecycle.READY -> {
                    lifecycle = PreparedMutationLifecycle.DISCARDED
                    release = true
                    PreparedMutationDiscardResult.Discarded
                }
                PreparedMutationLifecycle.IN_FLIGHT -> if (discardPending) {
                    PreparedMutationDiscardResult.AlreadyPending
                } else {
                    discardPending = true
                    PreparedMutationDiscardResult.Deferred
                }
                PreparedMutationLifecycle.DISCARDED -> PreparedMutationDiscardResult.AlreadyDiscarded
                PreparedMutationLifecycle.CONSUMED -> PreparedMutationDiscardResult.AlreadyConsumed
            }
        }
        if (release) releaseSourceAuthority()
        return result
    }

    override fun close() { discard() }

    private fun releaseSourceAuthority() {
        CanonicalAuthorityLeaseRegistry.release(authorityLease)
    }

    fun visitDirtyRows(sink: (PreparedRow) -> Boolean) = rows.visit(sink)

    fun visitRemovedSurfaceIds(sink: (SurfaceId) -> Boolean) {
        for (id in removedIds) if (!sink(SurfaceId(id))) return
    }

    fun visitDirtySupport(sink: (PreparedSupport) -> Boolean) {
        supportPairs?.let { pairs -> pairs.visit(sink); return }
        when (supportMode) {
            PreparedSupportMode.NONE -> Unit
            PreparedSupportMode.SELF -> rows.visit { row ->
                sink(PreparedSupport(row.id, row.toSupport()))
            }
            PreparedSupportMode.NEW_ONLY -> rows.visit { row ->
                if (row.id.value < sourceCut.nextSurfaceIdHighWater) true else sink(PreparedSupport(row.id, row.toSupport()))
            }
            PreparedSupportMode.CARTESIAN -> rows.visit { row ->
                var keepGoing = true
                supports.visit { source ->
                    keepGoing = sink(PreparedSupport(row.id, source))
                    keepGoing
                }
                keepGoing
            }
        }
    }

    fun visitDirtySources(sink: (ImmutableSourceSupport) -> Boolean) = rows.visit { row ->
        if (row.id.value < sourceCut.nextSurfaceIdHighWater) true else sink(row.toSupport())
    }

    fun visitDirtyLineage(sink: (LineageEdge) -> Boolean) {
        lineagePairs?.let { pairs -> pairs.visit(sink); return }
        for (source in removedIds) {
            var keepGoing = true
            rows.visit { row ->
                keepGoing = sink(LineageEdge(SurfaceId(source), row.id))
                keepGoing
            }
            if (!keepGoing) return
        }
    }

    fun writeWalTo(output: java.io.OutputStream) = writeTo(output, WAL_MAGIC)
    fun writeCurrentTo(output: java.io.OutputStream) = writeTo(output, CURRENT_MAGIC)

    private fun writeTo(output: java.io.OutputStream, magic: Int) {
        val out = DataOutputStream(output)
        val bodyVersion = if (kind == PreparedMutationKind.DEPTH_BATCH) DEPTH_BODY_VERSION else BODY_VERSION
        out.writeInt(magic); out.writeInt(bodyVersion); out.write(sourceCut.rootHash.toByteArray())
        out.writeUTF(commandId); out.writeInt(kind.ordinal)
        out.write(commandHash.toByteArray()); out.write(commandFingerprint.toByteArray())
        out.writeLong(targetHighWater); out.writeInt(targetLiveSurfaceCount)
        out.writeInt(targetSourceCount); out.writeInt(targetSupportCount); out.writeInt(targetLineageCount)
        out.writeLong(targetGeometryRevision); out.writeLong(targetLineageRevision)
        out.writeInt(rows.size); rows.writeRecords(out)
        out.writeInt(removedIds.size); removedIds.forEach(out::writeLong)
        out.writeInt(removedSupportRecords)
        if (bodyVersion >= DEPTH_BODY_VERSION) out.writeInt(removedLineageRecords)
        out.writeInt(work.dirtySupportRecords)
        supportPairs?.writeTo(out) ?: when (supportMode) {
            PreparedSupportMode.NONE -> Unit
            PreparedSupportMode.SELF -> rows.writeSelfSupport(out)
            PreparedSupportMode.NEW_ONLY -> rows.writeNewSelfSupport(out, sourceCut.nextSurfaceIdHighWater)
            PreparedSupportMode.CARTESIAN -> rows.writeCartesianSupport(out, supports)
        }
        out.writeInt(work.dirtySourceRecords); rows.writeNewSources(out, sourceCut.nextSurfaceIdHighWater)
        out.writeInt(work.dirtyLineageRecords)
        lineagePairs?.writeTo(out) ?: removedIds.forEach { source -> rows.writeLineageTargets(out, source) }
        out.flush()
    }

    companion object {
        private const val WAL_MAGIC = 0x4d33574c
        private const val CURRENT_MAGIC = 0x4d334350
        private const val BODY_VERSION = 2
        private const val DEPTH_BODY_VERSION = 3
    }
}

internal enum class PreparedMutationLifecycle { READY, IN_FLIGHT, CONSUMED, DISCARDED }
internal enum class PreparedMutationClaimResult { Claimed, AlreadyInFlight, Terminal }
internal enum class PreparedMutationFinish { SUCCESS, RETRYABLE, TERMINAL }
internal sealed interface PreparedMutationDiscardResult {
    data object Discarded : PreparedMutationDiscardResult
    data object Deferred : PreparedMutationDiscardResult
    data object AlreadyPending : PreparedMutationDiscardResult
    data object AlreadyDiscarded : PreparedMutationDiscardResult
    data object AlreadyConsumed : PreparedMutationDiscardResult
}

internal data class PreparedRow(
    val id: SurfaceId,
    val voxel: Voxel,
    val packedNormal: Int,
    val normalConfidence: Int,
    val allocationFingerprint: CanonicalReceiptBytes,
) {
    fun toSupport() = ImmutableSourceSupport(id, voxel, packedNormal, normalConfidence, allocationFingerprint)
}

internal class PreparedRowTable private constructor(
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

    fun visit(sink: (PreparedRow) -> Boolean): Boolean {
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

    fun writeCartesianSupport(out: DataOutputStream, supports: PreparedSourceTable) {
        for (target in ids) supports.writeForTarget(out, target)
    }

    fun writeNewSources(out: DataOutputStream, highWater: Long) {
        for (index in ids.indices) if (java.lang.Long.compareUnsigned(ids[index], highWater) >= 0) writeSource(out, index)
    }

    fun writeLineageTargets(out: DataOutputStream, source: Long) {
        for (target in ids) { out.writeLong(source); out.writeLong(target) }
    }

    private fun row(index: Int) = PreparedRow(
        SurfaceId(ids[index]), Voxel(x[index], y[index], z[index]), normal[index], confidence[index],
        CanonicalReceiptBytes(fingerprints.copyOfRange(index * 32, index * 32 + 32)),
    )

    private fun writeSource(out: DataOutputStream, index: Int) {
        out.writeLong(ids[index]); out.writeInt(x[index]); out.writeInt(y[index]); out.writeInt(z[index])
        out.writeInt(normal[index]); out.writeInt(confidence[index]); out.write(fingerprints, index * 32, 32)
    }

    companion object {
        fun from(rows: List<SurfaceOwner>): PreparedRowTable {
            val ids = LongArray(rows.size); val x = IntArray(rows.size); val y = IntArray(rows.size)
            val z = IntArray(rows.size); val normal = IntArray(rows.size); val confidence = IntArray(rows.size)
            val fingerprints = ByteArray(rows.size * 32)
            rows.forEachIndexed { index, row ->
                ids[index] = row.id.value; x[index] = row.voxel.x; y[index] = row.voxel.y; z[index] = row.voxel.z
                normal[index] = row.packedNormal; confidence[index] = row.normalConfidence
                row.allocatedBy.copyInto(fingerprints, index * 32)
            }
            return PreparedRowTable(ids, x, y, z, normal, confidence, fingerprints)
        }
    }
}

internal class PreparedSourceTable(
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

    fun visit(sink: (ImmutableSourceSupport) -> Boolean): Boolean {
        for (index in 0 until size) if (!sink(source(index))) return false
        return true
    }

    fun writeForTarget(out: DataOutputStream, target: Long) {
        for (index in 0 until size) { out.writeLong(target); writeSource(out, index) }
    }

    private fun source(index: Int) = ImmutableSourceSupport(
        SurfaceId(ids[index]), Voxel(x[index], y[index], z[index]), normal[index], confidence[index],
        CanonicalReceiptBytes(fingerprints.copyOfRange(index * 32, index * 32 + 32)),
    )

    private fun writeSource(out: DataOutputStream, index: Int) {
        out.writeLong(ids[index]); out.writeInt(x[index]); out.writeInt(y[index]); out.writeInt(z[index])
        out.writeInt(normal[index]); out.writeInt(confidence[index]); out.write(fingerprints, index * 32, 32)
    }

    companion object {
        val EMPTY = PreparedSourceTable(LongArray(0), IntArray(0), IntArray(0), IntArray(0), IntArray(0), IntArray(0), ByteArray(0), 0, 0, 0)
    }
}

private class BoundedSupportAccumulator(val limit: Int) {
    private var capacity = if (limit == 0) 0 else 1
    private var ids = LongArray(capacity)
    private var x = IntArray(capacity); private var y = IntArray(capacity); private var z = IntArray(capacity)
    private var normal = IntArray(capacity); private var confidence = IntArray(capacity)
    private var fingerprints = ByteArray(capacity * 32)
    private val hash = IntArray(hashCapacity(limit))
    private val mask = hash.size - 1
    var size = 0
        private set

    fun add(source: PagedSource): Boolean {
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

    fun freeze(): PreparedSourceTable {
        sort(0, size - 1)
        return PreparedSourceTable(ids, x, y, z, normal, confidence, fingerprints, size, limit, hash.size * 4L)
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

internal sealed interface CanonicalMutationPreparation {
    data class Prepared(val mutation: PreparedCanonicalMutation) : CanonicalMutationPreparation
    data class NoOp(val receipt: CanonicalStateReceipt) : CanonicalMutationPreparation
    data class Refused(
        val reason: CanonicalMutationRefusal,
        val receipt: CanonicalStateReceipt,
        val preflightWork: CanonicalMutationPreflightWork = CanonicalMutationPreflightWork(),
    ) : CanonicalMutationPreparation
}

internal data class CanonicalMutationPreflightWork(
    val targetDeepCopies: Int = 0,
    val targetSetInsertions: Int = 0,
    val ownerConstructions: Int = 0,
    val removalGraphAllocations: Int = 0,
    val featureBatchUpserts: Int = 0,
    val featureBatchMaximumUpserts: Int = 0,
)

internal enum class CanonicalMutationRefusal {
    INVALID_COMMAND, INVALID_OWNERSHIP, INVALID_NORMAL, UNKNOWN_IDENTITY, OWNERSHIP_CONFLICT,
    CAPACITY, EXHAUSTED, REVISION_CONFLICT, REVISION_EXHAUSTED, LINEAGE_EXHAUSTED,
    JOURNAL_EXHAUSTED, SOURCE_READ_FAILURE, ADJACENT_BUSY,
}

private fun Long.toIntExact(): Int = try { Math.toIntExact(this) } catch (_: ArithmeticException) { Int.MAX_VALUE }

internal const val CANONICAL_SURFACE_COMMAND_MODIFIED_UTF_BYTES = 256L

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
    value.isNotBlank() && modifiedUtf8Length(value) in 1..CANONICAL_SURFACE_COMMAND_MODIFIED_UTF_BYTES

private fun overlayHash(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)
