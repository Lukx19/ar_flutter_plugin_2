package com.uhg0.ar_flutter_plugin_2.visibilitygrid

import com.uhg0.ar_flutter_plugin_2.capture.StorageBudgetCoordinatorV2
import com.uhg0.ar_flutter_plugin_2.capture.StorageBudgetCandidateReservationV2
import com.uhg0.ar_flutter_plugin_2.capture.StorageBudgetReservationV2
import java.io.File
import java.io.RandomAccessFile
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption

internal interface CanonicalStateView : AutoCloseable {
    val cut: CompactCanonicalCut
    /** Exact compact authority borrowed by any overlays composing this view. */
    val generationZeroAuthority: CanonicalStateView get() = this

    fun findById(id: SurfaceId): CompactSurface?

    fun findByVoxel(voxel: Voxel): CompactSurface?

    /**
     * Narrow read seams for bounded runtime lookups. Implementations must refuse before
     * touching storage when the operation cannot fit the supplied remaining page/byte budget.
     * The default keeps existing planner-only views source-compatible; durable compact/COW
     * authorities override it with their zero-page in-memory index reads.
     */
    fun findByIdBounded(
        id: SurfaceId,
        maximumPageReads: Long,
        maximumBytesRead: Long,
    ): CanonicalBoundedReadResult<CompactSurface?> = boundedRead(
        maximumPageReads, maximumBytesRead,
    ) { findById(id) }

    fun findByVoxelBounded(
        voxel: Voxel,
        maximumPageReads: Long,
        maximumBytesRead: Long,
    ): CanonicalBoundedReadResult<CompactSurface?> = boundedRead(
        maximumPageReads, maximumBytesRead,
    ) { findByVoxel(voxel) }

    fun readPage(region: StorageRegion, page: Int, cursor: Int, limit: Int): CompactPage

    fun readSourceById(id: SurfaceId): CanonicalPageRead<PagedSource?>

    fun visitSourceSupport(
        target: SurfaceId,
        cursor: SourceSupportCursor?,
        sink: (PagedSupport) -> Boolean,
    ): SourceSupportRead

    fun visitLineage(
        source: SurfaceId,
        cursor: LineageCursor?,
        sink: (LineageEdge) -> Boolean,
    ): LineageRead = LineageRead.Complete(0, null)

    fun visitLineageBounded(
        source: SurfaceId,
        cursor: LineageCursor?,
        maximumPageReads: Long,
        maximumBytesRead: Long,
        sink: (LineageEdge) -> Boolean,
    ): CanonicalBoundedReadResult<LineageRead> = boundedRead(
        maximumPageReads, maximumBytesRead,
    ) { visitLineage(source, cursor, sink) }

    fun retainedMemoryReceipt(): CompactRetainedMemoryReceipt

    fun allocatedStorageReceipt(): CompactStorageReceipt

    /** Monotonic bounded-reader work, used to prove dirty planning is independent of live N. */
    fun readWorkReceipt(): CanonicalReadWork = CanonicalReadWork.ZERO
}

internal enum class CanonicalBoundedReadRefusal {
    LIMIT_EXHAUSTED,
    CANONICAL_READ_FAILURE,
}

internal sealed interface CanonicalBoundedReadResult<out T> {
    data class Complete<T>(val value: T, val work: CanonicalReadWork) : CanonicalBoundedReadResult<T>
    data class Refused(val reason: CanonicalBoundedReadRefusal) : CanonicalBoundedReadResult<Nothing>
}

private inline fun <T> CanonicalStateView.boundedRead(
    maximumPageReads: Long,
    maximumBytesRead: Long,
    read: () -> T,
): CanonicalBoundedReadResult<T> {
    if (maximumPageReads < 0L || maximumBytesRead < 0L) {
        return CanonicalBoundedReadResult.Refused(CanonicalBoundedReadRefusal.LIMIT_EXHAUSTED)
    }
    val before = readWorkReceipt()
    val value = read()
    val work = readWorkReceipt() - before
    return if (work.pageReads < 0L || work.bytesRead < 0L) {
        CanonicalBoundedReadResult.Refused(CanonicalBoundedReadRefusal.CANONICAL_READ_FAILURE)
    } else if (work.pageReads > maximumPageReads || work.bytesRead > maximumBytesRead) {
        CanonicalBoundedReadResult.Refused(CanonicalBoundedReadRefusal.LIMIT_EXHAUSTED)
    } else {
        CanonicalBoundedReadResult.Complete(value, work)
    }
}

internal data class CanonicalReadWork(
    val directLookups: Long,
    val pageReads: Long,
    val inspectedRows: Long,
    val bytesRead: Long,
) {
    operator fun minus(previous: CanonicalReadWork) = CanonicalReadWork(
        directLookups - previous.directLookups,
        pageReads - previous.pageReads,
        inspectedRows - previous.inspectedRows,
        bytesRead - previous.bytesRead,
    )

    companion object { val ZERO = CanonicalReadWork(0, 0, 0, 0) }
}

/** Scalar-only observation of live compact authorities for one physical group directory. */
internal data class CanonicalResidentOwnership(
    val liveStoreCount: Int,
    val retainedBytes: Long,
)

private object CanonicalResidentOwnershipRegistry {
    private val owners = mutableMapOf<String, java.util.IdentityHashMap<Any, Long>>()

    @Synchronized fun acquire(key: String, owner: Any, retainedBytes: Long) {
        require(retainedBytes >= 0)
        require(owners.getOrPut(key) { java.util.IdentityHashMap() }.put(owner, retainedBytes) == null)
    }

    @Synchronized fun release(key: String, owner: Any) {
        val group = owners[key] ?: return
        group.remove(owner)
        if (group.isEmpty()) owners.remove(key)
    }

    @Synchronized fun snapshot(key: String): CanonicalResidentOwnership {
        val group = owners[key]
        return CanonicalResidentOwnership(group?.size ?: 0, group?.values?.sum() ?: 0L)
    }
}

/** Small opaque plan capability; its exact authority remains outside the plan object graph. */
internal class CanonicalAuthorityLease internal constructor()

internal enum class CanonicalAuthorityLeaseRefusal {
    UNBOUND, STALE, GROUP_MISMATCH, DIRECTORY_MISMATCH, OWNER_MISMATCH,
}
internal sealed interface CanonicalAuthorityLeaseResolution {
    data class Resolved(
        val authority: CanonicalStateView,
        val published: CanonicalPublishedCommit?,
    ) : CanonicalAuthorityLeaseResolution
    data class Refused(val reason: CanonicalAuthorityLeaseRefusal) : CanonicalAuthorityLeaseResolution
}

/** Process-local exact authority table. Every entry owns one store reference and is erased once. */
internal object CanonicalAuthorityLeaseRegistry {
    private data class Entry(
        val authority: CanonicalStateView,
        val group: SurfaceGroup,
        val parentKey: String,
        val owner: Any,
        val releaseAuthority: () -> Unit,
        val onRelease: () -> Unit,
        var published: CanonicalPublishedCommit? = null,
    )
    private val entries = java.util.IdentityHashMap<CanonicalAuthorityLease, Entry>()

    internal fun acquire(
        authority: CanonicalStateView,
        owner: Any,
        onRelease: () -> Unit = {},
    ): CanonicalAuthorityLease {
        val lease = CanonicalAuthorityLease()
        val root = authority.generationZeroAuthority
        val identity = when (root) {
            is CompactCanonicalStore -> Triple(root.cut.group, root.authorityParentKey(), { root.close() })
            is ScalarCanonicalAuthority -> Triple(root.cut.group, root.authorityParentKey, { })
            else -> return lease
        }
        if (root is CompactCanonicalStore) root.retainAuthority()
        val boundAuthority = if (root is CompactCanonicalStore) root else authority
        synchronized(this) {
            check(entries.put(
                lease, Entry(boundAuthority, identity.first, identity.second, owner, identity.third, onRelease),
            ) == null)
        }
        return lease
    }

    @Synchronized fun resolve(
        lease: CanonicalAuthorityLease,
        group: SurfaceGroup,
        parent: File,
        owner: Any,
    ): CanonicalAuthorityLeaseResolution {
        val entry = entries[lease]
            ?: return CanonicalAuthorityLeaseResolution.Refused(CanonicalAuthorityLeaseRefusal.STALE)
        if (entry.group != group)
            return CanonicalAuthorityLeaseResolution.Refused(CanonicalAuthorityLeaseRefusal.GROUP_MISMATCH)
        if (entry.parentKey != parent.absoluteFile.toPath().normalize().toString())
            return CanonicalAuthorityLeaseResolution.Refused(CanonicalAuthorityLeaseRefusal.DIRECTORY_MISMATCH)
        if (entry.owner !== owner)
            return CanonicalAuthorityLeaseResolution.Refused(CanonicalAuthorityLeaseRefusal.OWNER_MISMATCH)
        return CanonicalAuthorityLeaseResolution.Resolved(entry.authority, entry.published)
    }

    @Synchronized fun attachPublished(
        lease: CanonicalAuthorityLease,
        published: CanonicalPublishedCommit,
    ): Boolean {
        val entry = entries[lease] ?: return false
        entry.published = published
        return true
    }

    internal fun release(lease: CanonicalAuthorityLease): Boolean {
        val entry = synchronized(this) { entries.remove(lease) } ?: return false
        try {
            entry.releaseAuthority()
        } finally {
            entry.onRelease()
        }
        return true
    }

    @Synchronized internal fun activeLeaseCount() = entries.size
    @Synchronized internal fun activeResidentStoreCount(): Int {
        val stores = java.util.Collections.newSetFromMap(
            java.util.IdentityHashMap<CompactCanonicalStore, Boolean>(),
        )
            entries.values.mapNotNull { it.authority.generationZeroAuthority as? CompactCanonicalStore }
                .forEach(stores::add)
        return stores.size
    }
    @Synchronized internal fun isActive(lease: CanonicalAuthorityLease) = entries.containsKey(lease)
}

/** Scalar authority identity; it deliberately owns no complete canonical store. */
internal interface ScalarCanonicalAuthority : CanonicalStateView {
    val authorityParentKey: String
}

internal data class CompactCanonicalCut(
    val group: SurfaceGroup,
    val profile: String,
    val geometryRevision: Long,
    val lineageRevision: Long,
    val nextSurfaceIdHighWater: Long,
    val liveSurfaceCount: Int,
    val sourceCount: Int,
    val supportCount: Int,
    val lineageCount: Int,
    val seededEmptyBaseline: committedEmptyBaseline?,
    val rootHash: CanonicalReceiptBytes,
    val sourceHash: CanonicalReceiptBytes,
) {
    override fun equals(other: Any?) =
        other is CompactCanonicalCut &&
            group == other.group &&
            profile == other.profile &&
            geometryRevision == other.geometryRevision &&
            lineageRevision == other.lineageRevision &&
            nextSurfaceIdHighWater == other.nextSurfaceIdHighWater &&
            liveSurfaceCount == other.liveSurfaceCount &&
            sourceCount == other.sourceCount &&
            supportCount == other.supportCount &&
            lineageCount == other.lineageCount &&
            seededEmptyBaseline == other.seededEmptyBaseline &&
            rootHash == other.rootHash &&
            sourceHash == other.sourceHash

    override fun hashCode() =
        listOf(
                group,
                profile,
                geometryRevision,
                lineageRevision,
                nextSurfaceIdHighWater,
                liveSurfaceCount,
                sourceCount,
                supportCount,
                lineageCount,
                seededEmptyBaseline,
            )
            .hashCode() * 31 + rootHash.hashCode()
}

internal data class CompactSurface(
    val id: SurfaceId,
    val voxel: Voxel,
    val packedNormal: Int,
    val normalConfidence: Int,
)

internal data class CompactPage(
    val rows: List<CompactSurface>,
    val nextCursor: Int?,
    val inspectedRows: Int,
)

internal data class SourceSupportCursor(
    val rootHash: CanonicalReceiptBytes,
    val target: SurfaceId,
    val ordinal: Int,
    val offset: Int,
)

internal sealed interface SourceSupportRead {
    data class Complete(
        val delivered: Int,
        val nextCursor: SourceSupportCursor?,
        val pageFaults: Int,
        val bytesRead: Int,
    ) : SourceSupportRead

    data class Refused(val reason: CompactCanonicalRefusal) : SourceSupportRead
}

internal data class LineageCursor(
    val rootHash: CanonicalReceiptBytes,
    val source: SurfaceId,
    val offset: Int,
)

internal sealed interface LineageRead {
    data class Complete(val delivered: Int, val nextCursor: LineageCursor?) : LineageRead
    data class Refused(val reason: CompactCanonicalRefusal) : LineageRead
}

internal data class CompactRetainedMemoryReceipt(
    val kernelBytes: Long,
    val rowColumnsBytes: Long,
    val idOrderBytes: Long,
    val voxelOrderBytes: Long,
    val pageOrderBytes: Long,
    val pageRangeBytes: Long,
    val lineageColumnsBytes: Long,
    val directoryColumnsBytes: Long,
    val cachePayloadBytes: Long,
    val cacheResidentPages: Int,
    val cacheMetadataBytes: Long,
    val scalarAndObjectBytes: Long,
    val migrationOpenScratchBytes: Long,
) {
    val compactAndDirectoryBytes
        get() =
            rowColumnsBytes +
                idOrderBytes +
                voxelOrderBytes +
                pageOrderBytes +
                pageRangeBytes +
                lineageColumnsBytes +
                directoryColumnsBytes +
                cachePayloadBytes +
                cacheMetadataBytes +
                scalarAndObjectBytes

    val residentTotalBytes
        get() = kernelBytes + compactAndDirectoryBytes

    val peakWithScratchBytes
        get() = residentTotalBytes + migrationOpenScratchBytes

    val journalReserveBytes
        get() = CompactCanonicalStore.JOURNAL_RESERVE_BYTES

    val withinIssue114Budget
        get() = residentTotalBytes <= CompactCanonicalStore.ISSUE_114_BUDGET_BYTES

    val preservesIssue115Reserve
        get() = residentTotalBytes + journalReserveBytes <= CompactCanonicalStore.C17_TOTAL_BYTES
}

internal data class CompactStorageReceipt(
    val rootBytes: Long,
    val residentBytes: Long,
    val directoryBytes: Long,
    val pageBytes: Long,
    val filesystemBytes: Long,
) {
    val allocatedBytes
        get() = rootBytes + residentBytes + directoryBytes + pageBytes + filesystemBytes
}

internal sealed interface CompactCanonicalOpenResult {
    data class Opened(val store: CompactCanonicalStore) : CompactCanonicalOpenResult

    data class Refused(val reason: CompactCanonicalRefusal) : CompactCanonicalOpenResult
}

internal sealed interface CompactCanonicalMigrationResult {
    data class Prepared(
        val cut: CompactCanonicalCut,
        val candidateDirectory: File,
        val storage: CompactStorageReceipt,
        val sourceIndex: LegacySourceIndexReceipt? = null,
    ) : CompactCanonicalMigrationResult

    data class Refused(val reason: CompactCanonicalRefusal) : CompactCanonicalMigrationResult
}

internal enum class CompactCanonicalRefusal {
    INVALID_CONFIGURATION,
    CORRUPT,
    CAPACITY,
    IDENTITY_CONFLICT,
    QUOTA_REFUSED,
    DURABILITY_FAILURE,
    IO_FAILURE,
    STALE_CURSOR,
    CLOSED,
}

internal enum class CompactCanonicalMigrationFault {
    BEFORE_PAGE_WRITE,
    DURING_PAGE_WRITE,
    BEFORE_PAGE_SYNC,
    AFTER_PAGE_SYNC,
    DURING_RESIDENT_WRITE,
    BEFORE_RESIDENT_SYNC,
    AFTER_RESIDENT_SYNC,
    BEFORE_DIRECTORY_WRITE,
    DURING_DIRECTORY_WRITE,
    BEFORE_DIRECTORY_SYNC,
    AFTER_DIRECTORY_SYNC,
    BEFORE_ROOT_WRITE,
    DURING_ROOT_WRITE,
    BEFORE_ROOT_SYNC,
    AFTER_ROOT_SYNC,
    BEFORE_STAGING_SYNC,
    AFTER_STAGING_SYNC,
    BEFORE_RENAME,
    AFTER_RENAME,
    AFTER_PARENT_SYNC,
}

internal interface CanonicalStorageBudget {
    fun reserve(bytes: Long): Any?

    fun reservePointerPublication(
        publicationId: String,
        slot: Int,
        rootBeforeBytes: Long,
        slotBeforeBytes: Long,
        selectorBeforeBytes: Long,
        commitBytes: Long,
        maximumPhysicalBytes: Long,
    ): Any? = reserve(maximumPhysicalBytes)

    fun pointerPublications(): List<CanonicalPointerReservation> = emptyList()

    fun commitPointerPublication(reservation: CanonicalPointerReservation) =
        commit(reservation.token, reservation.commitBytes)

    fun releasePointerPublication(reservation: CanonicalPointerReservation) =
        release(reservation.token)

    /** Durable reservation namespace for a recoverable one-time activation attempt. */
    fun reserveActivationAttempt(
        groupId: String,
        attemptId: String,
        rootBeforeBytes: Long,
        slotBeforeBytes: Long,
        selectorBeforeBytes: Long,
        commitBytes: Long,
        maximumPhysicalBytes: Long,
    ): Any? = reserve(maximumPhysicalBytes)

    fun activationAttempts(groupId: String): List<CanonicalPointerReservation> = emptyList()

    fun commitActivationAttempt(reservation: CanonicalPointerReservation) =
        commit(reservation.token, reservation.commitBytes)

    fun releaseActivationAttempt(reservation: CanonicalPointerReservation) =
        release(reservation.token)

    fun reserveCandidate(
        staging: File,
        target: File,
        fileBytes: Map<String, Long>,
        maximumPhysicalBytes: Long,
    ): Any? {
        val token = reserve(maximumPhysicalBytes) ?: return null
        require(staging.mkdirs())
        fileBytes.forEach { (name, bytes) ->
            RandomAccessFile(File(staging, name), "rw").use { file ->
                file.setLength(bytes)
                if (bytes > 0) {
                    val zero = ByteArray(65_536)
                    var offset = 0L
                    while (offset < bytes) {
                        val count = minOf(zero.size.toLong(), bytes - offset).toInt()
                        file.write(zero, 0, count); offset += count
                    }
                    file.fd.sync()
                }
            }
        }
        return token
    }

    fun reserveCandidateExclusive(
        staging: File,
        target: File,
        fileBytes: Map<String, Long>,
        maximumPhysicalBytes: Long,
    ): CanonicalCandidateReservation

    fun verifyCandidate(token: Any, candidate: File): Long = allocatedBytes(candidate)

    fun publishCandidate(token: Any, staging: File, target: File) {
        Files.move(staging.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
    }

    fun reconcilePublishedCandidate(target: File) = Unit

    /** Resolves either side of an interrupted candidate publication for this exact target. */
    fun reconcileCandidate(target: File) = reconcilePublishedCandidate(target)

    fun releaseCandidate(token: Any, staging: File) {
        if (staging.exists()) staging.deleteRecursively()
        release(token)
    }

    fun commit(token: Any, actualBytes: Long)

    fun release(token: Any)

    /** Begins or replays one identity-bound committed-byte reclaim exactly once. */
    fun reclaimCommittedBytesOnce(reclaimId: String, bytes: Long) = Unit

    /** Forgets an exact reclaim only after its owner journal durably records completion. */
    fun forgetCommittedReclaim(reclaimId: String) = Unit

    /** Durably removes one previously committed private candidate and its exact charge. */
    fun reclaimCommittedCandidate(candidate: File): Long {
        val actual = allocatedBytes(candidate)
        require(actual > 0L && candidate.deleteRecursively())
        return actual
    }

    fun allocatedBytes(path: File): Long = physicalAllocatedTreeBytes(path, allocationUnitBytes(path))

    fun allocationUnitBytes(path: File): Long {
        require(!System.getProperty("os.name").orEmpty().startsWith("Windows", true)) {
            "Windows allocation units require an authoritative injected storage budget"
        }
        return Files.getFileStore(path.toPath()).blockSize.coerceAtLeast(1L)
    }
}

internal sealed interface CanonicalCandidateReservation {
    data class Reserved(val token: Any) : CanonicalCandidateReservation
    data object TargetReserved : CanonicalCandidateReservation
    data object QuotaRefused : CanonicalCandidateReservation
}

internal data class CanonicalPointerReservation(
    val token: Any,
    val publicationId: String,
    val slot: Int,
    val rootBeforeBytes: Long,
    val slotBeforeBytes: Long,
    val selectorBeforeBytes: Long,
    val commitBytes: Long,
)

internal class CoordinatorStorageBudget(private val coordinator: StorageBudgetCoordinatorV2) :
    CanonicalStorageBudget {
    override fun reserve(bytes: Long): Any? =
        coordinator.reserve("canonical-surface:canonical:v6-migration", bytes)

    override fun reservePointerPublication(
        publicationId: String,
        slot: Int,
        rootBeforeBytes: Long,
        slotBeforeBytes: Long,
        selectorBeforeBytes: Long,
        commitBytes: Long,
        maximumPhysicalBytes: Long,
    ): Any? = coordinator.reservePointerPublication(
        "canonical-surface:canonical:selector", publicationId, slot, rootBeforeBytes, slotBeforeBytes,
        selectorBeforeBytes, commitBytes, maximumPhysicalBytes,
    )

    override fun pointerPublications() = coordinator.pointerPublications().map { reservation ->
        CanonicalPointerReservation(
            reservation,
            requireNotNull(reservation.pointerPublicationId),
            requireNotNull(reservation.pointerSlot),
            requireNotNull(reservation.pointerRootBeforeBytes),
            requireNotNull(reservation.pointerSlotBeforeBytes),
            requireNotNull(reservation.pointerSelectorBeforeBytes),
            requireNotNull(reservation.pointerCommitBytes),
        )
    }

    override fun commitPointerPublication(reservation: CanonicalPointerReservation) =
        coordinator.commitPointerPublication(reservation.token as StorageBudgetReservationV2)

    override fun releasePointerPublication(reservation: CanonicalPointerReservation) {
        coordinator.release(reservation.token as StorageBudgetReservationV2)
    }

    override fun reserveActivationAttempt(
        groupId: String,
        attemptId: String,
        rootBeforeBytes: Long,
        slotBeforeBytes: Long,
        selectorBeforeBytes: Long,
        commitBytes: Long,
        maximumPhysicalBytes: Long,
    ): Any? = coordinator.reservePointerPublication(
        "canonical-surface:canonical:activation:$groupId", attemptId, 0, rootBeforeBytes, slotBeforeBytes,
        selectorBeforeBytes, commitBytes, maximumPhysicalBytes,
    )

    override fun activationAttempts(groupId: String) = coordinator.pointerPublications()
        .filter { it.owner == "canonical-surface:canonical:activation:$groupId" }
        .map { reservation ->
            CanonicalPointerReservation(
                reservation,
                requireNotNull(reservation.pointerPublicationId),
                requireNotNull(reservation.pointerSlot),
                requireNotNull(reservation.pointerRootBeforeBytes),
                requireNotNull(reservation.pointerSlotBeforeBytes),
                requireNotNull(reservation.pointerSelectorBeforeBytes),
                requireNotNull(reservation.pointerCommitBytes),
            )
        }

    override fun commitActivationAttempt(reservation: CanonicalPointerReservation) =
        coordinator.commitPointerPublication(reservation.token as StorageBudgetReservationV2)

    override fun releaseActivationAttempt(reservation: CanonicalPointerReservation) {
        coordinator.release(reservation.token as StorageBudgetReservationV2)
    }

    override fun reserveCandidate(
        staging: File,
        target: File,
        fileBytes: Map<String, Long>,
        maximumPhysicalBytes: Long,
    ): Any? = coordinator.reserveCandidate(
        "canonical-surface:canonical:v6-migration", staging, target, fileBytes, maximumPhysicalBytes,
    )

    override fun reserveCandidateExclusive(
        staging: File,
        target: File,
        fileBytes: Map<String, Long>,
        maximumPhysicalBytes: Long,
    ): CanonicalCandidateReservation = when (val result = coordinator.reserveCandidateExclusive(
        "canonical-surface:canonical:v6-migration", staging, target, fileBytes, maximumPhysicalBytes,
    )) {
        is StorageBudgetCandidateReservationV2.Reserved -> CanonicalCandidateReservation.Reserved(result.reservation)
        StorageBudgetCandidateReservationV2.TargetReserved -> CanonicalCandidateReservation.TargetReserved
        StorageBudgetCandidateReservationV2.QuotaRefused -> CanonicalCandidateReservation.QuotaRefused
    }

    override fun verifyCandidate(token: Any, candidate: File) =
        coordinator.verifyCandidate(token as StorageBudgetReservationV2, candidate)

    override fun publishCandidate(token: Any, staging: File, target: File) =
        coordinator.publishCandidate(token as StorageBudgetReservationV2, staging, target)

    override fun reconcilePublishedCandidate(target: File) =
        coordinator.reconcilePublishedCandidate(target)

    override fun reconcileCandidate(target: File) = coordinator.reconcileCandidate(target)

    override fun releaseCandidate(token: Any, staging: File) {
        coordinator.releaseCandidate(token as StorageBudgetReservationV2, staging)
    }

    override fun commit(token: Any, actualBytes: Long) =
        coordinator.commit(token as StorageBudgetReservationV2, actualBytes)

    override fun release(token: Any) {
        coordinator.release(token as StorageBudgetReservationV2)
    }

    override fun reclaimCommittedBytesOnce(reclaimId: String, bytes: Long) =
        coordinator.reclaimVerifiedOnce(reclaimId, bytes)

    override fun forgetCommittedReclaim(reclaimId: String) =
        coordinator.forgetVerifiedReclaim(reclaimId)

    override fun reclaimCommittedCandidate(candidate: File) =
        coordinator.reclaimCommittedCandidate(candidate)

    override fun allocatedBytes(path: File) = coordinator.physicallyAllocatedTreeBytes(path)

    override fun allocationUnitBytes(path: File) = coordinator.allocationUnitBytes(path)
}

internal class CompactCanonicalStore
private constructor(
    override val cut: CompactCanonicalCut,
    private val configuration: SurfaceOwnershipConfiguration,
    private val rootDirectory: File,
    private val rowCount: Int,
    private val rowId: IntArray,
    private val rowX: IntArray,
    private val rowY: IntArray,
    private val rowZ: IntArray,
    private val rowNormal: ShortArray,
    private val rowConfidence: ByteArray,
    private val idOrder: IntArray,
    private val voxelOrder: IntArray,
    private val pageOrder: IntArray,
    private val pageRanges: CompactPageRanges,
    private val lineageSource: IntArray,
    private val lineageTarget: IntArray,
    private val directory: CompactDirectory,
    private val sourceDirectoryCount: Int,
    private val storageReceipt: CompactStorageReceipt,
) : CanonicalStateView {
    private val residentOwnershipKey = rootDirectory.absoluteFile.toPath().normalize().toString() + ':' + cut.group.value
    private val residentOwnershipIdentity = Any()
    private val cache = CanonicalPageCache(File(rootDirectory, PAGES_FILE))
    @Volatile private var closed = false
    private var authorityReferences = 1
    private var directLookupWork = 0L
    private var pageReadWork = 0L
    private var inspectedRowWork = 0L
    private var byteReadWork = 0L

    init {
        CanonicalResidentOwnershipRegistry.acquire(
            residentOwnershipKey, residentOwnershipIdentity, retainedMemoryReceipt().residentTotalBytes,
        )
    }

    internal fun residentOwnership(): CanonicalResidentOwnership =
        CanonicalResidentOwnershipRegistry.snapshot(residentOwnershipKey)

    internal fun authorityParentKey() = requireNotNull(rootDirectory.parentFile)
        .absoluteFile.toPath().normalize().toString()

    /** Keeps this exact mapped authority resident while a prepared plan crosses its caller scope. */
    @Synchronized internal fun retainAuthority(): CompactCanonicalStore {
        check(!closed)
        authorityReferences = Math.addExact(authorityReferences, 1)
        return this
    }

    override fun findById(id: SurfaceId): CompactSurface? {
        directLookupWork++
        if (closed || id.value !in 1..UINT32_MAX) return null
        var low = 0
        var high = rowCount - 1
        while (low <= high) {
            inspectedRowWork++
            val mid = (low + high) ushr 1
            val slot = idOrder[mid]
            when (val c = unsignedCompare(unsigned(rowId[slot]), id.value)) {
                in Int.MIN_VALUE until 0 -> low = mid + 1
                in 1..Int.MAX_VALUE -> high = mid - 1
                else -> return row(slot)
            }
        }
        return null
    }

    override fun findByVoxel(voxel: Voxel): CompactSurface? {
        directLookupWork++
        if (closed) return null
        var low = 0
        var high = rowCount - 1
        while (low <= high) {
            inspectedRowWork++
            val mid = (low + high) ushr 1
            val slot = voxelOrder[mid]
            when (
                val c = compareVoxel(rowX[slot], rowY[slot], rowZ[slot], voxel.x, voxel.y, voxel.z)
            ) {
                in Int.MIN_VALUE until 0 -> low = mid + 1
                in 1..Int.MAX_VALUE -> high = mid - 1
                else -> return row(slot)
            }
        }
        return null
    }

    override fun findByIdBounded(
        id: SurfaceId,
        maximumPageReads: Long,
        maximumBytesRead: Long,
    ): CanonicalBoundedReadResult<CompactSurface?> = if (
        maximumPageReads < 0L || maximumBytesRead < 0L
    ) {
        CanonicalBoundedReadResult.Refused(CanonicalBoundedReadRefusal.LIMIT_EXHAUSTED)
    } else CanonicalBoundedReadResult.Complete(findById(id), CanonicalReadWork.ZERO)

    override fun findByVoxelBounded(
        voxel: Voxel,
        maximumPageReads: Long,
        maximumBytesRead: Long,
    ): CanonicalBoundedReadResult<CompactSurface?> = if (
        maximumPageReads < 0L || maximumBytesRead < 0L
    ) {
        CanonicalBoundedReadResult.Refused(CanonicalBoundedReadRefusal.LIMIT_EXHAUSTED)
    } else CanonicalBoundedReadResult.Complete(findByVoxel(voxel), CanonicalReadWork.ZERO)

    override fun readPage(
        region: StorageRegion,
        page: Int,
        cursor: Int,
        limit: Int,
    ): CompactPage {
        pageReadWork++
        if (closed || page !in 0..26 || cursor !in 0..rowCount || limit !in 1..MAX_PAGE_READ)
            return CompactPage(emptyList(), null, 0)
        val range = pageRanges.find(region, page)
        if (range < 0) return CompactPage(emptyList(), null, 0)
        val rangeCount = pageRanges.count[range]
        if (cursor > rangeCount) return CompactPage(emptyList(), null, 0)
        val delivered = minOf(limit, rangeCount - cursor)
        val rows = ArrayList<CompactSurface>(delivered)
        repeat(delivered) { offset ->
            inspectedRowWork++
            rows += row(pageOrder[pageRanges.start[range] + cursor + offset])
        }
        val next = cursor + delivered
        return CompactPage(rows, if (next < rangeCount) next else null, delivered)
    }

    /** Bounded id-ordered renderer rebuild page; it never exposes the resident backing arrays. */
    internal fun readRendererPage(cursor: Int, limit: Int): CompactPage {
        if (closed || cursor !in 0..rowCount || limit !in 1..MAX_PAGE_READ) {
            return CompactPage(emptyList(), null, 0)
        }
        val delivered = minOf(limit, rowCount - cursor)
        val rows = ArrayList<CompactSurface>(delivered)
        repeat(delivered) { offset -> rows += row(idOrder[cursor + offset]) }
        val next = cursor + delivered
        return CompactPage(rows, if (next < rowCount) next else null, delivered)
    }

    override fun readSourceById(id: SurfaceId): CanonicalPageRead<PagedSource?> {
        pageReadWork++
        if (closed) return CanonicalPageRead.Refused(CompactCanonicalRefusal.CLOSED)
        val entryIndex =
            findDirectory(CanonicalPageKind.SOURCE, id.value)
                ?: return CanonicalPageRead.Complete(null, 0, 0)
        return when (val page = cache.read(entryIndex, entry(entryIndex))) {
            is CanonicalPageRead.Refused -> page
            is CanonicalPageRead.Complete -> {
                byteReadWork += page.bytesRead
                val value =
                    try {
                        CanonicalPageCache.decodeSources(page.value)
                            .binarySearchUnsigned(id.value)
                    } catch (_: Exception) {
                        return CanonicalPageRead.Refused(CompactCanonicalRefusal.CORRUPT)
                    }
                CanonicalPageRead.Complete(value, page.pageFaults, page.bytesRead)
            }
        }
    }

    override fun visitSourceSupport(
        target: SurfaceId,
        cursor: SourceSupportCursor?,
        sink: (PagedSupport) -> Boolean,
    ): SourceSupportRead {
        pageReadWork++
        if (closed) return SourceSupportRead.Refused(CompactCanonicalRefusal.CLOSED)
        if (
            cursor != null &&
                (cursor.rootHash != cut.rootHash ||
                    cursor.target != target ||
                    cursor.ordinal < 0 ||
                    cursor.offset < 0)
        )
            return SourceSupportRead.Refused(CompactCanonicalRefusal.STALE_CURSOR)
        val entryIndex =
            cursor?.ordinal
                ?: findDirectory(CanonicalPageKind.SUPPORT, target.value)
                ?: return SourceSupportRead.Complete(0, null, 0, 0)
        if (
            entryIndex !in 0 until directory.size ||
                directory.kind[entryIndex].toInt() != CanonicalPageKind.SUPPORT.wire ||
                unsignedCompare(target.value, unsigned(directory.minimum[entryIndex])) < 0 ||
                unsignedCompare(target.value, unsigned(directory.maximum[entryIndex])) > 0
        )
            return SourceSupportRead.Refused(CompactCanonicalRefusal.STALE_CURSOR)
        return when (val page = cache.read(entryIndex, entry(entryIndex))) {
            is CanonicalPageRead.Refused -> SourceSupportRead.Refused(page.reason)
            is CanonicalPageRead.Complete -> {
                byteReadWork += page.bytesRead
                val records =
                    try {
                        CanonicalPageCache.decodeSupports(page.value)
                    } catch (_: Exception) {
                        return SourceSupportRead.Refused(CompactCanonicalRefusal.CORRUPT)
                    }
                val matching = records.filter { it.target == target }
                val start = cursor?.offset ?: 0
                if (start > matching.size)
                    return SourceSupportRead.Refused(CompactCanonicalRefusal.STALE_CURSOR)
                var delivered = 0
                for (i in start until matching.size) {
                    if (!sink(matching[i])) break
                    delivered++
                }
                val consumed = start + delivered
                val following = entryIndex + 1
                val next =
                    when {
                        consumed < matching.size ->
                            SourceSupportCursor(
                                cut.rootHash,
                                target,
                                entryIndex,
                                consumed,
                            )
                        following < directory.size &&
                            directory.kind[following].toInt() == CanonicalPageKind.SUPPORT.wire &&
                            unsignedCompare(target.value, unsigned(directory.minimum[following])) >= 0 &&
                            unsignedCompare(target.value, unsigned(directory.maximum[following])) <= 0 ->
                            SourceSupportCursor(cut.rootHash, target, following, 0)
                        else -> null
                    }
                SourceSupportRead.Complete(delivered, next, page.pageFaults, page.bytesRead)
            }
        }
    }

    override fun visitLineage(
        source: SurfaceId,
        cursor: LineageCursor?,
        sink: (LineageEdge) -> Boolean,
    ): LineageRead {
        if (closed) return LineageRead.Refused(CompactCanonicalRefusal.CLOSED)
        if (cursor != null && (cursor.rootHash != cut.rootHash || cursor.source != source || cursor.offset < 0))
            return LineageRead.Refused(CompactCanonicalRefusal.STALE_CURSOR)
        var low = 0
        var high = lineageSource.size
        while (low < high) {
            val middle = (low + high) ushr 1
            if (unsignedCompare(unsigned(lineageSource[middle]), source.value) < 0) low = middle + 1 else high = middle
        }
        var matching = 0
        var delivered = 0
        for (index in low until lineageSource.size) {
            if (unsigned(lineageSource[index]) != source.value) break
            if (matching++ < (cursor?.offset ?: 0)) continue
            if (!sink(LineageEdge(source, SurfaceId(unsigned(lineageTarget[index])))))
                return LineageRead.Complete(delivered, LineageCursor(cut.rootHash, source, matching - 1))
            delivered++
        }
        return LineageRead.Complete(delivered, null)
    }

    override fun visitLineageBounded(
        source: SurfaceId,
        cursor: LineageCursor?,
        maximumPageReads: Long,
        maximumBytesRead: Long,
        sink: (LineageEdge) -> Boolean,
    ): CanonicalBoundedReadResult<LineageRead> = if (
        maximumPageReads < 0L || maximumBytesRead < 0L
    ) {
        CanonicalBoundedReadResult.Refused(CanonicalBoundedReadRefusal.LIMIT_EXHAUSTED)
    } else CanonicalBoundedReadResult.Complete(
        visitLineage(source, cursor, sink), CanonicalReadWork.ZERO,
    )

    override fun retainedMemoryReceipt() =
        CompactRetainedMemoryReceipt(
            KERNEL_RETAINED_BYTES,
            rowId.size * 19L,
            idOrder.size * 4L,
            voxelOrder.size * 4L,
            pageOrder.size * 4L,
            pageRanges.retainedBytes,
            lineageSource.size * 8L,
            directory.retainedBytes,
            cache.retainedPayloadBytes(),
            cache.residentPageCount(),
            CACHE_METADATA_BYTES,
            SCALAR_AND_OBJECT_BYTES,
            SCRATCH_BYTES,
        )

    override fun allocatedStorageReceipt() = storageReceipt

    override fun readWorkReceipt() = CanonicalReadWork(
        directLookupWork,
        pageReadWork,
        inspectedRowWork,
        byteReadWork,
    )

    @Synchronized override fun close() {
        if (!closed && --authorityReferences == 0) {
            closed = true
            cache.close()
            CanonicalResidentOwnershipRegistry.release(residentOwnershipKey, residentOwnershipIdentity)
        }
    }

    private fun row(slot: Int) =
        CompactSurface(
            SurfaceId(unsigned(rowId[slot])),
            Voxel(rowX[slot], rowY[slot], rowZ[slot]),
            rowNormal[slot].toInt() and 0xffff,
            rowConfidence[slot].toInt() and 0xff,
        )

    private fun entry(index: Int) = directory.entry(index)

    private fun findDirectory(kind: CanonicalPageKind, key: Long): Int? {
        var low = if (kind == CanonicalPageKind.SOURCE) 0 else sourceDirectoryCount
        var high =
            if (kind == CanonicalPageKind.SOURCE) sourceDirectoryCount - 1 else directory.size - 1
        var candidate = -1
        while (low <= high) {
            val middle = (low + high) ushr 1
            if (unsignedCompare(unsigned(directory.maximum[middle]), key) < 0) low = middle + 1
            else {
                candidate = middle
                high = middle - 1
            }
        }
        return candidate.takeIf {
            it >= 0 && unsignedCompare(key, unsigned(directory.minimum[it])) >= 0
        }
    }

    companion object {
        const val C17_TOTAL_BYTES = 16_777_216L
        const val JOURNAL_RESERVE_BYTES = 1_048_576L
        const val ISSUE_114_BUDGET_BYTES = C17_TOTAL_BYTES - JOURNAL_RESERVE_BYTES
        const val KERNEL_RETAINED_BYTES = 7_589_936L
        const val PROFILE = CompactCanonicalFormat.PROFILE
        private const val UINT32_MAX = 0xffff_ffffL
        private const val SCALAR_AND_OBJECT_BYTES = 8_192L
        private const val CACHE_METADATA_BYTES = 4_096L
        private const val SCRATCH_BYTES = CompactCanonicalFormat.SCRATCH_BYTES.toLong()
        private const val MAX_PAGE_READ = 512
        private const val ROOT_FILE = "root.v6"
        private const val RESIDENT_FILE = "resident.v6"
        private const val DIRECTORY_FILE = "directory.v6"
        private const val PAGES_FILE = "sources.v6.pages"

        fun openV6(
            group: SurfaceGroup,
            directory: File,
            budget: CanonicalStorageBudget,
            configuration: SurfaceOwnershipConfiguration = SurfaceOwnershipConfiguration(),
        ): CompactCanonicalOpenResult =
            try {
                if (!configuration.isValid)
                    return CompactCanonicalOpenResult.Refused(
                        CompactCanonicalRefusal.INVALID_CONFIGURATION
                    )
                val candidate = candidateDirectory(directory, group)
                val rootFile = File(candidate, ROOT_FILE)
                val (root, rootHash) =
                    CompactCanonicalFormat.readRoot(rootFile, group, configuration)
                val residentFile = File(candidate, RESIDENT_FILE)
                require(
                    CompactCanonicalFormat.hashFile(residentFile)
                        .contentEquals(root.residentHash)
                )
                val resident =
                    CompactCanonicalFormat.readResident(residentFile, configuration, root)
                val directoryFile = File(candidate, DIRECTORY_FILE)
                require(
                    CompactCanonicalFormat.hashFile(directoryFile)
                        .contentEquals(root.directoryHash)
                )
                val entries = CompactCanonicalFormat.readDirectory(directoryFile, root.pageCount)
                val pages = File(candidate, PAGES_FILE)
                require(
                    pages.isFile &&
                        pages.length() == root.pageCount.toLong() * CanonicalPageCache.PAGE_BYTES
                )
                RandomAccessFile(pages, "r").use { reader ->
                    var previous: CanonicalPageSummary? = null
                    var sourceRecords = 0L
                    var supportRecords = 0L
                    repeat(entries.size) { index ->
                        val entry = entries.entry(index)
                        val bytes = ByteArray(entry.length)
                        reader.seek(entry.offset)
                        reader.readFully(bytes)
                        require(pageSha256(bytes).contentEquals(entry.hash))
                        val summary = requireNotNull(CanonicalPageCache.inspectPage(entry, bytes))
                        previous?.let { prior ->
                            require(
                                prior.kind != CanonicalPageKind.SUPPORT ||
                                    summary.kind == CanonicalPageKind.SUPPORT
                            )
                            if (prior.kind == summary.kind) {
                                require(
                                    if (summary.kind == CanonicalPageKind.SOURCE)
                                        unsignedCompare(prior.lastSource, summary.firstSource) < 0
                                    else
                                        unsignedComparePair(
                                            prior.lastTarget,
                                            prior.lastSource,
                                            summary.firstTarget,
                                            summary.firstSource,
                                        ) < 0
                                )
                            }
                        }
                        if (summary.kind == CanonicalPageKind.SOURCE)
                            sourceRecords = Math.addExact(sourceRecords, summary.count.toLong())
                        else supportRecords = Math.addExact(supportRecords, summary.count.toLong())
                        previous = summary
                    }
                    require(sourceRecords == root.sources.toLong())
                    require(supportRecords == root.supports.toLong())
                }
                CompactCanonicalOpenResult.Opened(
                    buildStore(
                        group,
                        configuration,
                        candidate,
                        rootHash,
                        root,
                        resident,
                        entries,
                        physicalStorageReceipt(budget, candidate),
                    )
                )
            } catch (_: IllegalArgumentException) {
                CompactCanonicalOpenResult.Refused(CompactCanonicalRefusal.CORRUPT)
            } catch (_: Exception) {
                CompactCanonicalOpenResult.Refused(CompactCanonicalRefusal.IO_FAILURE)
            }

        fun prepareV6SiblingMigration(
            group: SurfaceGroup,
            directory: File,
            budget: CanonicalStorageBudget,
            configuration: SurfaceOwnershipConfiguration = SurfaceOwnershipConfiguration(),
            fault: CompactCanonicalMigrationFault? = null,
        ): CompactCanonicalMigrationResult = prepareV6Candidate(
            group, directory, budget, configuration, fault,
        ) { SurfaceOwnershipLegacyCodec.readValidated(group, directory, configuration) }

        /**
         * Creates the canonical empty v6 authority directly under the budgeted root.
         * No legacy snapshot, receipt journal, allocator, or writable fallback exists.
         */
        fun prepareEmptyV6Bootstrap(
            group: SurfaceGroup,
            directory: File,
            budget: CanonicalStorageBudget,
            baseline: committedEmptyBaseline,
            configuration: SurfaceOwnershipConfiguration = SurfaceOwnershipConfiguration(
                seededEmptyBaseline = baseline,
            ),
            fault: CompactCanonicalMigrationFault? = null,
        ): CompactCanonicalMigrationResult {
            if (baseline.groupIdentity != group.value || configuration.seededEmptyBaseline != baseline) {
                return CompactCanonicalMigrationResult.Refused(CompactCanonicalRefusal.IDENTITY_CONFLICT)
            }
            val sourceHash = pageSha256(
                listOf(
                    "canonical-surface-empty-v6-v1", group.value, baseline.bindingIdentity,
                    baseline.groupIdentity, baseline.transactionId.toString(),
                    baseline.geometryRevision.toString(), baseline.lineageRevision.toString(),
                ).joinToString(":").encodeToByteArray(),
            )
            val empty = LegacyCanonicalState(
                group = group,
                nextHighWater = 1,
                resident = CompactResident(
                    0, IntArray(0), IntArray(0), IntArray(0), IntArray(0),
                    ShortArray(0), ByteArray(0), IntArray(0), IntArray(0), IntArray(0),
                    IntArray(0), IntArray(0),
                ),
                sourceCount = 0,
                supportCount = 0,
                lineageCount = 0,
                geometryRevision = baseline.geometryRevision,
                lineageRevision = baseline.lineageRevision,
                baseline = baseline,
                sourceHash = sourceHash,
                sourceCursor = { _ -> },
                supportCursor = { _ -> },
                sourceLookup = { null },
                explicitSourceIndex = null,
                canonicalReceiptCursor = { _ -> },
            )
            return prepareV6Candidate(group, directory, budget, configuration, fault) { empty }
        }

        private fun prepareV6Candidate(
            group: SurfaceGroup,
            directory: File,
            budget: CanonicalStorageBudget,
            configuration: SurfaceOwnershipConfiguration,
            fault: CompactCanonicalMigrationFault?,
            state: () -> LegacyCanonicalState,
        ): CompactCanonicalMigrationResult {
            if (!configuration.isValid)
                return CompactCanonicalMigrationResult.Refused(
                    CompactCanonicalRefusal.INVALID_CONFIGURATION
                )
            var reservation: Any? = null
            var staging: File? = null
            var published = false
            try {
                val target = candidateDirectory(directory, group)
                val existing = if (target.exists()) {
                    budget.reconcilePublishedCandidate(target)
                    inspectExistingCandidate(group, directory, budget, configuration)
                        ?: return CompactCanonicalMigrationResult.Refused(
                            CompactCanonicalRefusal.CORRUPT
                        )
                } else null
                val legacy = state()
                validateLegacyForV6(legacy, configuration)
                if (existing != null) {
                    return if (
                        existing.cut.sourceHash == CanonicalReceiptBytes(legacy.sourceHash)
                    ) existing
                    else
                        CompactCanonicalMigrationResult.Refused(
                            CompactCanonicalRefusal.IDENTITY_CONFLICT
                        )
                }
                val pageCount =
                    pageCount(legacy.sourceCount) + pageCount(legacy.supportCount)
                val pageBytes =
                    Math.multiplyExact(
                        pageCount.toLong(),
                        CanonicalPageCache.PAGE_BYTES.toLong(),
                    )
                val files = linkedMapOf(
                    PAGES_FILE to pageBytes,
                    RESIDENT_FILE to CompactCanonicalFormat.residentFileBytes(legacy),
                    DIRECTORY_FILE to CompactCanonicalFormat.directoryFileBytes(pageCount),
                    ROOT_FILE to CompactCanonicalFormat.rootFileBytes(legacy),
                )
                val unit = budget.allocationUnitBytes(directory)
                // Candidate directory plus reservation metadata, ledger replacement, and their
                // parent-directory entries are charged conservatively in addition to exact files.
                val worst = (files.values + listOf(1L, 1L, 1L, 1L)).fold(0L) { total, logical ->
                    Math.addExact(total, roundPhysical(logical, unit))
                }
                staging =
                    File(
                        directory,
                        "${target.name}.staging-${Thread.currentThread().id}-${System.nanoTime()}",
                    )
                reservation = budget.reserveCandidate(staging, target, files, worst)
                    ?: return CompactCanonicalMigrationResult.Refused(
                        CompactCanonicalRefusal.QUOTA_REFUSED
                    )
                if (fault == CompactCanonicalMigrationFault.BEFORE_PAGE_WRITE) error("fault")
                val entries = writePages(legacy, File(staging, PAGES_FILE), fault)
                budget.verifyCandidate(requireNotNull(reservation), staging)
                if (fault == CompactCanonicalMigrationFault.AFTER_PAGE_SYNC) error("fault")
                val residentHash =
                    CompactCanonicalFormat.writeResident(
                        File(staging, RESIDENT_FILE),
                        legacy,
                        fault,
                    )
                budget.verifyCandidate(requireNotNull(reservation), staging)
                if (fault == CompactCanonicalMigrationFault.AFTER_RESIDENT_SYNC) error("fault")
                if (fault == CompactCanonicalMigrationFault.BEFORE_DIRECTORY_WRITE) error("fault")
                val directoryHash =
                    CompactCanonicalFormat.writeDirectory(
                        File(staging, DIRECTORY_FILE),
                        entries,
                        fault,
                    )
                budget.verifyCandidate(requireNotNull(reservation), staging)
                if (fault == CompactCanonicalMigrationFault.AFTER_DIRECTORY_SYNC) error("fault")
                if (fault == CompactCanonicalMigrationFault.BEFORE_ROOT_WRITE) error("fault")
                val rootHash = CompactCanonicalFormat.writeRoot(
                    File(staging, ROOT_FILE),
                    legacy,
                    residentHash,
                    directoryHash,
                    entries.size,
                    fault,
                )
                budget.verifyCandidate(requireNotNull(reservation), staging)
                if (fault == CompactCanonicalMigrationFault.AFTER_ROOT_SYNC) error("fault")
                if (fault == CompactCanonicalMigrationFault.BEFORE_STAGING_SYNC) error("fault")
                syncDirectory(staging)
                if (fault == CompactCanonicalMigrationFault.AFTER_STAGING_SYNC) error("fault")
                if (fault == CompactCanonicalMigrationFault.BEFORE_RENAME) error("fault")
                budget.publishCandidate(requireNotNull(reservation), staging, target)
                published = true
                if (fault == CompactCanonicalMigrationFault.AFTER_RENAME) error("fault")
                syncDirectory(directory)
                if (fault == CompactCanonicalMigrationFault.AFTER_PARENT_SYNC) error("fault")
                val actual = budget.allocatedBytes(target)
                budget.commit(requireNotNull(reservation), actual)
                reservation = null
                return CompactCanonicalMigrationResult.Prepared(
                    cutFromLegacy(legacy, rootHash),
                    target,
                    physicalStorageReceipt(budget, target),
                    legacy.sourceIndexReceipt(),
                )
            } catch (_: RestoreFailure) {
                return CompactCanonicalMigrationResult.Refused(CompactCanonicalRefusal.CORRUPT)
            } catch (_: IllegalArgumentException) {
                return CompactCanonicalMigrationResult.Refused(CompactCanonicalRefusal.CAPACITY)
            } catch (_: Exception) {
                return CompactCanonicalMigrationResult.Refused(
                    CompactCanonicalRefusal.DURABILITY_FAILURE
                )
            } finally {
                reservation?.let { token ->
                    try {
                        val target = candidateDirectory(directory, group)
                        if (published || target.isDirectory) {
                            val actual = budget.allocatedBytes(target)
                            budget.commit(token, actual)
                        } else staging?.let { budget.releaseCandidate(token, it) } ?: budget.release(token)
                    } catch (_: Exception) {}
                }
            }
        }

        /** The v6 graph is closed before legacy arrays and cursor closures are constructed. */
        private fun inspectExistingCandidate(
            group: SurfaceGroup,
            directory: File,
            budget: CanonicalStorageBudget,
            configuration: SurfaceOwnershipConfiguration,
        ): CompactCanonicalMigrationResult.Prepared? {
            val opened = openV6(group, directory, budget, configuration)
                as? CompactCanonicalOpenResult.Opened ?: return null
            return opened.store.use { store ->
                CompactCanonicalMigrationResult.Prepared(
                    store.cut, candidateDirectory(directory, group), store.allocatedStorageReceipt(),
                )
            }
        }

        private fun cutFromLegacy(legacy: LegacyCanonicalState, rootHash: ByteArray) =
            CompactCanonicalCut(
                legacy.group,
                PROFILE,
                legacy.geometryRevision,
                legacy.lineageRevision,
                legacy.nextHighWater,
                legacy.resident.rows,
                legacy.sourceCount,
                legacy.supportCount,
                legacy.lineageCount,
                legacy.baseline,
                CanonicalReceiptBytes(rootHash),
                CanonicalReceiptBytes(legacy.sourceHash),
            )

        private fun pageCount(records: Int): Int =
            if (records == 0) 0 else (records - 1) / CanonicalPageCache.MAX_RECORDS + 1

        private fun physicalStorageReceipt(
            budget: CanonicalStorageBudget,
            candidate: File,
        ): CompactStorageReceipt {
            val root = budget.allocatedBytes(File(candidate, ROOT_FILE))
            val resident = budget.allocatedBytes(File(candidate, RESIDENT_FILE))
            val directory = budget.allocatedBytes(File(candidate, DIRECTORY_FILE))
            val pages = budget.allocatedBytes(File(candidate, PAGES_FILE))
            val tree = budget.allocatedBytes(candidate)
            val files = Math.addExact(Math.addExact(root, resident), Math.addExact(directory, pages))
            require(tree >= files)
            return CompactStorageReceipt(root, resident, directory, pages, tree - files)
        }

        private fun roundPhysical(logical: Long, unit: Long): Long {
            require(logical >= 0 && unit > 0)
            return if (logical == 0L) 0L
            else Math.multiplyExact((logical - 1L) / unit + 1L, unit)
        }

        /** Writes one fixed page at a time; the historical corpus is never assembled in RAM. */
        private fun writePages(
            legacy: LegacyCanonicalState,
            file: File,
            fault: CompactCanonicalMigrationFault?,
        ): CompactDirectory {
            val entries =
                CompactDirectory(
                    pageCount(legacy.sourceCount) + pageCount(legacy.supportCount)
                )
            var ordinal = 0
            RandomAccessFile(file, "rw").use { output ->
                output.seek(0)
                fun append(
                    kind: CanonicalPageKind,
                    records: Int,
                    minimum: Long,
                    maximum: Long,
                    bytes: ByteArray,
                ) {
                    check(bytes.size == CanonicalPageCache.PAGE_BYTES)
                    if (
                        fault == CompactCanonicalMigrationFault.DURING_PAGE_WRITE &&
                            ordinal == 0
                    ) {
                        output.write(bytes, 0, 100)
                        error("fault")
                    }
                    val offset = ordinal.toLong() * CanonicalPageCache.PAGE_BYTES
                    output.write(bytes)
                    entries.append(
                        CanonicalDirectoryEntry(
                            kind,
                            ordinal,
                            minimum,
                            maximum,
                            offset,
                            bytes.size,
                            records,
                            pageSha256(bytes),
                        )
                    )
                    ordinal++
                }

                val sourcePage = ArrayList<PagedSource>(CanonicalPageCache.MAX_RECORDS)
                fun flushSourcePage() {
                    if (sourcePage.isEmpty()) return
                    append(
                        CanonicalPageKind.SOURCE,
                        sourcePage.size,
                        sourcePage.first().id.value,
                        sourcePage.last().id.value,
                        CanonicalPageCache.encodeSourcePage(ordinal, sourcePage),
                    )
                    sourcePage.clear()
                }
                legacy.visitSources { source ->
                    sourcePage += source
                    if (sourcePage.size == CanonicalPageCache.MAX_RECORDS) flushSourcePage()
                }
                flushSourcePage()

                val supportPage = ArrayList<PagedSupport>(CanonicalPageCache.MAX_RECORDS)
                fun flushSupportPage() {
                    if (supportPage.isEmpty()) return
                    append(
                        CanonicalPageKind.SUPPORT,
                        supportPage.size,
                        supportPage.first().target.value,
                        supportPage.last().target.value,
                        CanonicalPageCache.encodeSupportPage(ordinal, supportPage),
                    )
                    supportPage.clear()
                }
                legacy.visitSupports { target, source ->
                    supportPage += PagedSupport(SurfaceId(target), source)
                    if (supportPage.size == CanonicalPageCache.MAX_RECORDS) flushSupportPage()
                }
                flushSupportPage()
                if (fault == CompactCanonicalMigrationFault.BEFORE_PAGE_SYNC) error("fault")
                output.fd.sync()
                require(output.filePointer == output.length()) { "preallocated pages size mismatch" }
            }
            return entries
        }

        private fun buildStore(
            group: SurfaceGroup,
            configuration: SurfaceOwnershipConfiguration,
            candidate: File,
            rootHash: ByteArray,
            root: CompactRoot,
            resident: CompactResident,
            entries: CompactDirectory,
            storage: CompactStorageReceipt,
        ): CompactCanonicalStore {
            val pageRanges = buildPageRanges(configuration, resident)
            val cut =
                CompactCanonicalCut(
                    group,
                    PROFILE,
                    root.geometry,
                    root.lineageRevision,
                    root.high,
                    root.rows,
                    root.sources,
                    root.supports,
                    root.lineage,
                    root.baseline,
                    CanonicalReceiptBytes(rootHash),
                    CanonicalReceiptBytes(root.sourceHash),
                )
            return CompactCanonicalStore(
                cut,
                configuration,
                candidate,
                resident.rows,
                resident.rowId,
                resident.rowX,
                resident.rowY,
                resident.rowZ,
                resident.rowNormal,
                resident.rowConfidence,
                resident.idOrder,
                resident.voxelOrder,
                resident.pageOrder,
                pageRanges,
                resident.lineageSource,
                resident.lineageTarget,
                entries,
                entries.sourcePageCount(),
                storage,
            )
        }

        private fun validateLegacyForV6(
            legacy: LegacyCanonicalState,
            configuration: SurfaceOwnershipConfiguration,
        ) {
            require(legacy.resident.rows <= configuration.surfaceCapacity)
            require(
                legacy.sourceCount <= configuration.surfaceCapacity + configuration.lineageCapacity
            )
            require(legacy.lineageCount <= configuration.lineageCapacity)
            require(
                legacy.supportCount <= configuration.surfaceCapacity + configuration.lineageCapacity
            )
            require(legacy.baseline == configuration.seededEmptyBaseline)
        }

        private fun syncDirectory(directory: File) {
            if (!(System.getProperty("os.name") ?: "").startsWith("Windows", true))
                FileChannel.open(directory.toPath(), StandardOpenOption.READ).use { it.force(true) }
        }

        private fun candidateDirectory(parent: File, group: SurfaceGroup) =
            File(parent, "canonical-surface-canonical-v6-${pageSha256(group.value.encodeToByteArray()).hex()}")
    }
}

private fun List<PagedSource>.binarySearchUnsigned(id: Long): PagedSource? {
    var low = 0
    var high = size - 1
    while (low <= high) {
        val mid = (low + high) ushr 1
        when (val c = unsignedCompare(this[mid].id.value, id)) {
            in Int.MIN_VALUE until 0 -> low = mid + 1
            in 1..Int.MAX_VALUE -> high = mid - 1
            else -> return this[mid]
        }
    }
    return null
}

internal data class CompactLocation(val region: StorageRegion, val page: Int)

internal fun CompactLocation(
    c: SurfaceOwnershipConfiguration,
    v: Voxel,
): CompactLocation? {
    val perRegion = c.regionMicrometers / c.voxelMicrometers
    val perPage = c.pageMicrometers / c.voxelMicrometers
    fun axis(value: Int) = Math.floorDiv(value, perRegion) to Math.floorMod(value, perRegion)
    val (regionX, localX) = axis(v.x)
    val (regionY, localY) = axis(v.y)
    val (regionZ, localZ) = axis(v.z)
    val pageX = localX / perPage
    val pageY = localY / perPage
    val pageZ = localZ / perPage
    return if (pageX !in 0..2 || pageY !in 0..2 || pageZ !in 0..2) null
    else
        CompactLocation(
            StorageRegion(regionX, regionY, regionZ),
            pageX + 3 * (pageY + 3 * pageZ),
        )
}

internal fun compareVoxel(ax: Int, ay: Int, az: Int, bx: Int, by: Int, bz: Int) =
    when {
        ax != bx -> ax.compareTo(bx)
        ay != by -> ay.compareTo(by)
        else -> az.compareTo(bz)
    }

internal fun compareLocation(
    ax: Int, ay: Int, az: Int, ap: Int,
    bx: Int, by: Int, bz: Int, bp: Int,
) = when {
    ax != bx -> ax.compareTo(bx)
    ay != by -> ay.compareTo(by)
    az != bz -> az.compareTo(bz)
    else -> ap.compareTo(bp)
}

internal fun compareLocationThenVoxel(
    configuration: SurfaceOwnershipConfiguration,
    x: IntArray,
    y: IntArray,
    z: IntArray,
    left: Int,
    right: Int,
): Int {
    val a = requireNotNull(CompactLocation(configuration, Voxel(x[left], y[left], z[left])))
    val b = requireNotNull(CompactLocation(configuration, Voxel(x[right], y[right], z[right])))
    val location = compareLocation(
        a.region.x, a.region.y, a.region.z, a.page,
        b.region.x, b.region.y, b.region.z, b.page,
    )
    return if (location != 0) location
    else compareVoxel(x[left], y[left], z[left], x[right], y[right], z[right])
}

private fun buildPageRanges(
    configuration: SurfaceOwnershipConfiguration,
    resident: CompactResident,
): CompactPageRanges {
    val ranges = CompactPageRanges(configuration.surfaceCapacity)
    var start = 0
    while (start < resident.rows) {
        val slot = resident.pageOrder[start]
        val location = requireNotNull(
            CompactLocation(
                configuration,
                Voxel(resident.rowX[slot], resident.rowY[slot], resident.rowZ[slot]),
            )
        )
        var end = start + 1
        while (end < resident.rows) {
            val next = resident.pageOrder[end]
            val nextLocation = requireNotNull(
                CompactLocation(
                    configuration,
                    Voxel(resident.rowX[next], resident.rowY[next], resident.rowZ[next]),
                )
            )
            if (nextLocation != location) break
            end++
        }
        ranges.append(location, start, end - start)
        start = end
    }
    return ranges
}

internal fun IntArray.sortIndices(count: Int, compare: (Int, Int) -> Int) {
    fun sift(rootStart: Int, endExclusive: Int) {
        var root = rootStart
        while (true) {
            val left = root * 2 + 1
            if (left >= endExclusive) return
            var child = left
            if (left + 1 < endExclusive && compare(this[left], this[left + 1]) < 0) child++
            if (compare(this[root], this[child]) >= 0) return
            val swap = this[root]
            this[root] = this[child]
            this[child] = swap
            root = child
        }
    }
    for (start in count / 2 - 1 downTo 0) sift(start, count)
    for (end in count - 1 downTo 1) {
        val swap = this[0]
        this[0] = this[end]
        this[end] = swap
        sift(0, end)
    }
}

private fun ByteArray.hex() = joinToString("") { "%02x".format(it) }

private fun physicalAllocatedTreeBytes(path: File, allocationUnit: Long): Long {
    fun allocated(file: File): Long {
        val windows = System.getProperty("os.name").orEmpty().startsWith("Windows", true)
        val unix = if (windows) null else try {
            (Files.getAttribute(file.toPath(), "unix:blocks") as Number).toLong() * 512L
        } catch (_: Exception) { null }
        if (unix != null) return unix
        val unit = allocationUnit.also { require(it > 0) }
        val logical = if (file.isDirectory) unit else file.length()
        return if (logical == 0L) 0L
        else Math.multiplyExact((logical - 1L) / unit + 1L, unit)
    }
    if (!path.isDirectory) return allocated(path)
    return path.walkTopDown().fold(0L) { total, file -> Math.addExact(total, allocated(file)) }
}

private fun CompactDirectory.sourcePageCount(): Int {
    var count = 0
    while (count < size && kind[count].toInt() == CanonicalPageKind.SOURCE.wire) count++
    return count
}
