package com.uhg0.ar_flutter_plugin_2.visibilitygrid

import com.uhg0.ar_flutter_plugin_2.capture.StorageBudgetCoordinatorV2
import com.uhg0.ar_flutter_plugin_2.capture.StorageBudgetReservationV2
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption

internal interface M3CanonicalStateView : AutoCloseable {
    val cut: M3CompactCanonicalCut

    fun findById(id: M3SurfaceId): M3CompactSurface?

    fun findByVoxel(voxel: M3Voxel): M3CompactSurface?

    fun readPage(region: M3StorageRegion, page: Int, cursor: Int, limit: Int): M3CompactPage

    fun readSourceById(id: M3SurfaceId): M3CanonicalPageRead<M3PagedSource?>

    fun visitSourceSupport(
        target: M3SurfaceId,
        cursor: M3SourceSupportCursor?,
        sink: (M3PagedSupport) -> Boolean,
    ): M3SourceSupportRead

    fun retainedMemoryReceipt(): M3CompactRetainedMemoryReceipt

    fun allocatedStorageReceipt(): M3CompactStorageReceipt
}

internal data class M3CompactCanonicalCut(
    val group: M3SurfaceGroup,
    val profile: String,
    val geometryRevision: Long,
    val lineageRevision: Long,
    val nextSurfaceIdHighWater: Long,
    val liveSurfaceCount: Int,
    val sourceCount: Int,
    val supportCount: Int,
    val lineageCount: Int,
    val seededEmptyBaseline: M3CommittedEmptyBaseline?,
    val rootHash: M3CanonicalReceiptBytes,
    val sourceHash: M3CanonicalReceiptBytes,
) {
    override fun equals(other: Any?) =
        other is M3CompactCanonicalCut &&
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

internal data class M3CompactSurface(
    val id: M3SurfaceId,
    val voxel: M3Voxel,
    val packedNormal: Int,
    val normalConfidence: Int,
)

internal data class M3CompactPage(val rows: List<M3CompactSurface>, val nextCursor: Int?)

internal data class M3SourceSupportCursor(
    val rootHash: M3CanonicalReceiptBytes,
    val target: M3SurfaceId,
    val ordinal: Int,
    val offset: Int,
)

internal sealed interface M3SourceSupportRead {
    data class Complete(
        val delivered: Int,
        val nextCursor: M3SourceSupportCursor?,
        val pageFaults: Int,
        val bytesRead: Int,
    ) : M3SourceSupportRead

    data class Refused(val reason: M3CompactCanonicalRefusal) : M3SourceSupportRead
}

internal data class M3CompactRetainedMemoryReceipt(
    val kernelBytes: Long,
    val rowColumnsBytes: Long,
    val idOrderBytes: Long,
    val pageOrderBytes: Long,
    val lineageColumnsBytes: Long,
    val directoryColumnsBytes: Long,
    val cachePayloadBytes: Long,
    val cacheMetadataBytes: Long,
    val scalarAndObjectBytes: Long,
    val migrationOpenScratchBytes: Long,
) {
    val compactAndDirectoryBytes
        get() =
            rowColumnsBytes +
                idOrderBytes +
                pageOrderBytes +
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
        get() = M3CompactCanonicalStore.JOURNAL_RESERVE_BYTES

    val withinIssue114Budget
        get() = residentTotalBytes <= M3CompactCanonicalStore.ISSUE_114_BUDGET_BYTES

    val preservesIssue115Reserve
        get() = residentTotalBytes + journalReserveBytes <= M3CompactCanonicalStore.C17_TOTAL_BYTES
}

internal data class M3CompactStorageReceipt(
    val rootBytes: Long,
    val residentBytes: Long,
    val directoryBytes: Long,
    val pageBytes: Long,
) {
    val allocatedBytes
        get() = rootBytes + residentBytes + directoryBytes + pageBytes
}

internal sealed interface M3CompactCanonicalOpenResult {
    data class Opened(val store: M3CompactCanonicalStore) : M3CompactCanonicalOpenResult

    data class Refused(val reason: M3CompactCanonicalRefusal) : M3CompactCanonicalOpenResult
}

internal sealed interface M3CompactCanonicalMigrationResult {
    data class Prepared(
        val cut: M3CompactCanonicalCut,
        val candidateDirectory: File,
        val storage: M3CompactStorageReceipt,
    ) : M3CompactCanonicalMigrationResult

    data class Refused(val reason: M3CompactCanonicalRefusal) : M3CompactCanonicalMigrationResult
}

internal enum class M3CompactCanonicalRefusal {
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

internal enum class M3CompactCanonicalMigrationFault {
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

internal interface M3CanonicalStorageBudget {
    fun reserve(bytes: Long): Any?

    fun commit(token: Any, actualBytes: Long)

    fun release(token: Any)
}

internal class M3CoordinatorStorageBudget(private val coordinator: StorageBudgetCoordinatorV2) :
    M3CanonicalStorageBudget {
    override fun reserve(bytes: Long): Any? =
        coordinator.reserve("m3:canonical:v6-migration", bytes)

    override fun commit(token: Any, actualBytes: Long) =
        coordinator.commit(token as StorageBudgetReservationV2, actualBytes)

    override fun release(token: Any) {
        coordinator.release(token as StorageBudgetReservationV2)
    }
}

internal class M3CompactCanonicalStore
private constructor(
    override val cut: M3CompactCanonicalCut,
    private val configuration: M3SurfaceOwnershipConfiguration,
    private val rootDirectory: File,
    private val rowCount: Int,
    private val rowId: IntArray,
    private val rowX: IntArray,
    private val rowY: IntArray,
    private val rowZ: IntArray,
    private val rowNormal: ShortArray,
    private val rowConfidence: ByteArray,
    private val idOrder: IntArray,
    private val pageOrder: IntArray,
    private val lineageSource: IntArray,
    private val lineageTarget: IntArray,
    private val directory: M3CompactDirectory,
    private val sourceDirectoryCount: Int,
    private val storageReceipt: M3CompactStorageReceipt,
) : M3CanonicalStateView {
    private val cache = M3CanonicalPageCache(File(rootDirectory, PAGES_FILE))
    @Volatile private var closed = false

    override fun findById(id: M3SurfaceId): M3CompactSurface? {
        if (closed || id.value !in 1..UINT32_MAX) return null
        var low = 0
        var high = rowCount - 1
        while (low <= high) {
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

    override fun findByVoxel(voxel: M3Voxel): M3CompactSurface? {
        if (closed) return null
        var low = 0
        var high = rowCount - 1
        while (low <= high) {
            val mid = (low + high) ushr 1
            val slot = pageOrder[mid]
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

    override fun readPage(
        region: M3StorageRegion,
        page: Int,
        cursor: Int,
        limit: Int,
    ): M3CompactPage {
        if (closed || page !in 0..26 || cursor !in 0..rowCount || limit !in 1..MAX_PAGE_READ)
            return M3CompactPage(emptyList(), null)
        val rows = ArrayList<M3CompactSurface>(minOf(limit, 64))
        var index = cursor
        while (index < rowCount && rows.size < limit) {
            val slot = pageOrder[index++]
            val location =
                m3CompactLocation(configuration, M3Voxel(rowX[slot], rowY[slot], rowZ[slot]))
            if (location?.region == region && location.page == page) rows += row(slot)
        }
        return M3CompactPage(rows, if (index < rowCount) index else null)
    }

    override fun readSourceById(id: M3SurfaceId): M3CanonicalPageRead<M3PagedSource?> {
        if (closed) return M3CanonicalPageRead.Refused(M3CompactCanonicalRefusal.CLOSED)
        val entryIndex =
            findDirectory(M3CanonicalPageKind.SOURCE, id.value)
                ?: return M3CanonicalPageRead.Complete(null, 0, 0)
        return when (val page = cache.read(entryIndex, entry(entryIndex))) {
            is M3CanonicalPageRead.Refused -> page
            is M3CanonicalPageRead.Complete -> {
                val value =
                    try {
                        M3CanonicalPageCache.decodeSources(page.value)
                            .binarySearchUnsigned(id.value)
                    } catch (_: Exception) {
                        return M3CanonicalPageRead.Refused(M3CompactCanonicalRefusal.CORRUPT)
                    }
                M3CanonicalPageRead.Complete(value, page.pageFaults, page.bytesRead)
            }
        }
    }

    override fun visitSourceSupport(
        target: M3SurfaceId,
        cursor: M3SourceSupportCursor?,
        sink: (M3PagedSupport) -> Boolean,
    ): M3SourceSupportRead {
        if (closed) return M3SourceSupportRead.Refused(M3CompactCanonicalRefusal.CLOSED)
        if (
            cursor != null &&
                (cursor.rootHash != cut.rootHash ||
                    cursor.target != target ||
                    cursor.ordinal < 0 ||
                    cursor.offset < 0)
        )
            return M3SourceSupportRead.Refused(M3CompactCanonicalRefusal.STALE_CURSOR)
        val entryIndex =
            cursor?.ordinal
                ?: findDirectory(M3CanonicalPageKind.SUPPORT, target.value)
                ?: return M3SourceSupportRead.Complete(0, null, 0, 0)
        if (
            entryIndex !in 0 until directory.size ||
                directory.kind[entryIndex].toInt() != M3CanonicalPageKind.SUPPORT.wire ||
                unsignedCompare(target.value, unsigned(directory.minimum[entryIndex])) < 0 ||
                unsignedCompare(target.value, unsigned(directory.maximum[entryIndex])) > 0
        )
            return M3SourceSupportRead.Refused(M3CompactCanonicalRefusal.STALE_CURSOR)
        return when (val page = cache.read(entryIndex, entry(entryIndex))) {
            is M3CanonicalPageRead.Refused -> M3SourceSupportRead.Refused(page.reason)
            is M3CanonicalPageRead.Complete -> {
                val records =
                    try {
                        M3CanonicalPageCache.decodeSupports(page.value)
                    } catch (_: Exception) {
                        return M3SourceSupportRead.Refused(M3CompactCanonicalRefusal.CORRUPT)
                    }
                val matching = records.filter { it.target == target }
                val start = cursor?.offset ?: 0
                if (start > matching.size)
                    return M3SourceSupportRead.Refused(M3CompactCanonicalRefusal.STALE_CURSOR)
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
                            M3SourceSupportCursor(
                                cut.rootHash,
                                target,
                                entryIndex,
                                consumed,
                            )
                        following < directory.size &&
                            directory.kind[following].toInt() == M3CanonicalPageKind.SUPPORT.wire &&
                            unsignedCompare(target.value, unsigned(directory.minimum[following])) >= 0 &&
                            unsignedCompare(target.value, unsigned(directory.maximum[following])) <= 0 ->
                            M3SourceSupportCursor(cut.rootHash, target, following, 0)
                        else -> null
                    }
                M3SourceSupportRead.Complete(delivered, next, page.pageFaults, page.bytesRead)
            }
        }
    }

    override fun retainedMemoryReceipt() =
        M3CompactRetainedMemoryReceipt(
            KERNEL_RETAINED_BYTES,
            rowId.size * 19L,
            idOrder.size * 4L,
            pageOrder.size * 4L,
            lineageSource.size * 8L,
            directory.retainedBytes,
            cache.retainedPayloadBytes(),
            CACHE_METADATA_BYTES,
            SCALAR_AND_OBJECT_BYTES,
            SCRATCH_BYTES,
        )

    override fun allocatedStorageReceipt() = storageReceipt

    override fun close() {
        closed = true
        cache.close()
    }

    private fun row(slot: Int) =
        M3CompactSurface(
            M3SurfaceId(unsigned(rowId[slot])),
            M3Voxel(rowX[slot], rowY[slot], rowZ[slot]),
            rowNormal[slot].toInt() and 0xffff,
            rowConfidence[slot].toInt() and 0xff,
        )

    private fun entry(index: Int) = directory.entry(index)

    private fun findDirectory(kind: M3CanonicalPageKind, key: Long): Int? {
        var low = if (kind == M3CanonicalPageKind.SOURCE) 0 else sourceDirectoryCount
        var high =
            if (kind == M3CanonicalPageKind.SOURCE) sourceDirectoryCount - 1 else directory.size - 1
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
        const val KERNEL_RETAINED_BYTES = 7_548_936L
        const val PROFILE = M3CompactCanonicalFormat.PROFILE
        private const val UINT32_MAX = 0xffff_ffffL
        private const val SCALAR_AND_OBJECT_BYTES = 8_192L
        private const val CACHE_METADATA_BYTES = 4_096L
        private const val SCRATCH_BYTES = M3CompactCanonicalFormat.SCRATCH_BYTES.toLong()
        private const val MAX_PAGE_READ = 512
        private const val ROOT_FILE = "root.v6"
        private const val RESIDENT_FILE = "resident.v6"
        private const val DIRECTORY_FILE = "directory.v6"
        private const val PAGES_FILE = "sources.v6.pages"

        fun openV6(
            group: M3SurfaceGroup,
            directory: File,
            configuration: M3SurfaceOwnershipConfiguration = M3SurfaceOwnershipConfiguration(),
        ): M3CompactCanonicalOpenResult =
            try {
                if (!configuration.isValid)
                    return M3CompactCanonicalOpenResult.Refused(
                        M3CompactCanonicalRefusal.INVALID_CONFIGURATION
                    )
                val candidate = candidateDirectory(directory, group)
                val rootFile = File(candidate, ROOT_FILE)
                val (root, rootHash) =
                    M3CompactCanonicalFormat.readRoot(rootFile, group, configuration)
                val residentFile = File(candidate, RESIDENT_FILE)
                require(
                    M3CompactCanonicalFormat.hashFile(residentFile)
                        .contentEquals(root.residentHash)
                )
                val resident =
                    M3CompactCanonicalFormat.readResident(residentFile, configuration, root)
                val directoryFile = File(candidate, DIRECTORY_FILE)
                require(
                    M3CompactCanonicalFormat.hashFile(directoryFile)
                        .contentEquals(root.directoryHash)
                )
                val entries = M3CompactCanonicalFormat.readDirectory(directoryFile, root.pageCount)
                val pages = File(candidate, PAGES_FILE)
                require(
                    pages.isFile &&
                        pages.length() == root.pageCount.toLong() * M3CanonicalPageCache.PAGE_BYTES
                )
                RandomAccessFile(pages, "r").use { reader ->
                    var previous: M3CanonicalPageSummary? = null
                    var sourceRecords = 0L
                    var supportRecords = 0L
                    repeat(entries.size) { index ->
                        val entry = entries.entry(index)
                        val bytes = ByteArray(entry.length)
                        reader.seek(entry.offset)
                        reader.readFully(bytes)
                        require(m3PageSha256(bytes).contentEquals(entry.hash))
                        val summary = requireNotNull(M3CanonicalPageCache.inspectPage(entry, bytes))
                        previous?.let { prior ->
                            require(
                                prior.kind != M3CanonicalPageKind.SUPPORT ||
                                    summary.kind == M3CanonicalPageKind.SUPPORT
                            )
                            if (prior.kind == summary.kind) {
                                require(
                                    if (summary.kind == M3CanonicalPageKind.SOURCE)
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
                        if (summary.kind == M3CanonicalPageKind.SOURCE)
                            sourceRecords = Math.addExact(sourceRecords, summary.count.toLong())
                        else supportRecords = Math.addExact(supportRecords, summary.count.toLong())
                        previous = summary
                    }
                    require(sourceRecords == root.sources.toLong())
                    require(supportRecords == root.supports.toLong())
                }
                M3CompactCanonicalOpenResult.Opened(
                    buildStore(
                        group,
                        configuration,
                        candidate,
                        rootHash,
                        root,
                        resident,
                        entries,
                        M3CompactStorageReceipt(
                            rootFile.length(),
                            residentFile.length(),
                            directoryFile.length(),
                            pages.length(),
                        ),
                    )
                )
            } catch (_: IllegalArgumentException) {
                M3CompactCanonicalOpenResult.Refused(M3CompactCanonicalRefusal.CORRUPT)
            } catch (_: Exception) {
                M3CompactCanonicalOpenResult.Refused(M3CompactCanonicalRefusal.IO_FAILURE)
            }

        fun prepareV6SiblingMigration(
            group: M3SurfaceGroup,
            directory: File,
            budget: M3CanonicalStorageBudget,
            configuration: M3SurfaceOwnershipConfiguration = M3SurfaceOwnershipConfiguration(),
            fault: M3CompactCanonicalMigrationFault? = null,
        ): M3CompactCanonicalMigrationResult {
            if (!configuration.isValid)
                return M3CompactCanonicalMigrationResult.Refused(
                    M3CompactCanonicalRefusal.INVALID_CONFIGURATION
                )
            var reservation: Any? = null
            var staging: File? = null
            var published = false
            try {
                val legacy =
                    M3SurfaceOwnershipLegacyCodec.readValidated(group, directory, configuration)
                validateLegacyForV6(legacy, configuration)
                val target = candidateDirectory(directory, group)
                if (target.exists()) {
                    val opened = openV6(group, directory, configuration)
                    val store =
                        (opened as? M3CompactCanonicalOpenResult.Opened)?.store
                            ?: return M3CompactCanonicalMigrationResult.Refused(
                                M3CompactCanonicalRefusal.CORRUPT
                            )
                    return if (
                        store.cut.sourceHash == M3CanonicalReceiptBytes(legacy.sourceHash)
                    )
                        M3CompactCanonicalMigrationResult.Prepared(
                            store.cut,
                            target,
                            store.allocatedStorageReceipt(),
                        )
                    else
                        M3CompactCanonicalMigrationResult.Refused(
                            M3CompactCanonicalRefusal.IDENTITY_CONFLICT
                        )
                }
                val pageCount =
                    pageCount(legacy.sourceCount) + pageCount(legacy.supportCount)
                val pageBytes =
                    Math.multiplyExact(
                        pageCount.toLong(),
                        M3CanonicalPageCache.PAGE_BYTES.toLong(),
                    )
                val directoryWorstCaseBytes = 44L + pageCount * 59L
                val worst =
                    Math.addExact(
                        Math.addExact(residentFileBytes(legacy), pageBytes),
                        directoryWorstCaseBytes + 1_024L + 16_384L,
                    )
                reservation =
                    budget.reserve(worst)
                        ?: return M3CompactCanonicalMigrationResult.Refused(
                            M3CompactCanonicalRefusal.QUOTA_REFUSED
                        )
                staging =
                    File(
                        directory,
                        "${target.name}.staging-${Thread.currentThread().id}-${System.nanoTime()}",
                    )
                require(staging.mkdirs())
                if (fault == M3CompactCanonicalMigrationFault.BEFORE_PAGE_WRITE) error("fault")
                val entries = writePages(legacy, File(staging, PAGES_FILE), fault)
                if (fault == M3CompactCanonicalMigrationFault.AFTER_PAGE_SYNC) error("fault")
                val residentHash =
                    M3CompactCanonicalFormat.writeResident(
                        File(staging, RESIDENT_FILE),
                        legacy,
                        fault,
                    )
                if (fault == M3CompactCanonicalMigrationFault.AFTER_RESIDENT_SYNC) error("fault")
                if (fault == M3CompactCanonicalMigrationFault.BEFORE_DIRECTORY_WRITE) error("fault")
                val directoryHash =
                    M3CompactCanonicalFormat.writeDirectory(
                        File(staging, DIRECTORY_FILE),
                        entries,
                        fault,
                    )
                if (fault == M3CompactCanonicalMigrationFault.AFTER_DIRECTORY_SYNC) error("fault")
                if (fault == M3CompactCanonicalMigrationFault.BEFORE_ROOT_WRITE) error("fault")
                M3CompactCanonicalFormat.writeRoot(
                    File(staging, ROOT_FILE),
                    legacy,
                    residentHash,
                    directoryHash,
                    entries.size,
                    fault,
                )
                if (fault == M3CompactCanonicalMigrationFault.AFTER_ROOT_SYNC) error("fault")
                if (fault == M3CompactCanonicalMigrationFault.BEFORE_STAGING_SYNC) error("fault")
                syncDirectory(staging)
                if (fault == M3CompactCanonicalMigrationFault.AFTER_STAGING_SYNC) error("fault")
                if (fault == M3CompactCanonicalMigrationFault.BEFORE_RENAME) error("fault")
                Files.move(staging.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
                published = true
                if (fault == M3CompactCanonicalMigrationFault.AFTER_RENAME) error("fault")
                syncDirectory(directory)
                if (fault == M3CompactCanonicalMigrationFault.AFTER_PARENT_SYNC) error("fault")
                val actual = target.walkTopDown().filter { it.isFile }.sumOf { it.length() }
                budget.commit(requireNotNull(reservation), actual)
                reservation = null
                val opened =
                    openV6(group, directory, configuration) as? M3CompactCanonicalOpenResult.Opened
                        ?: error("published v6 invalid")
                return M3CompactCanonicalMigrationResult.Prepared(
                    opened.store.cut,
                    target,
                    opened.store.allocatedStorageReceipt(),
                )
            } catch (_: M3RestoreFailure) {
                return M3CompactCanonicalMigrationResult.Refused(M3CompactCanonicalRefusal.CORRUPT)
            } catch (_: IllegalArgumentException) {
                return M3CompactCanonicalMigrationResult.Refused(M3CompactCanonicalRefusal.CAPACITY)
            } catch (_: Exception) {
                if (!published) staging?.deleteRecursively()
                return M3CompactCanonicalMigrationResult.Refused(
                    M3CompactCanonicalRefusal.DURABILITY_FAILURE
                )
            } finally {
                reservation?.let { token ->
                    try {
                        if (published) {
                            val target = candidateDirectory(directory, group)
                            val actual =
                                target.walkTopDown().filter { it.isFile }.sumOf { it.length() }
                            budget.commit(token, actual)
                        } else budget.release(token)
                    } catch (_: Exception) {}
                }
            }
        }

        private fun pageCount(records: Int): Int =
            if (records == 0) 0 else (records - 1) / M3CanonicalPageCache.MAX_RECORDS + 1

        private fun residentFileBytes(legacy: M3LegacyCanonicalState): Long =
            12L + legacy.resident.rows * 19L + 4L + legacy.lineageCount * 8L + 32L

        /** Writes one fixed page at a time; the historical corpus is never assembled in RAM. */
        private fun writePages(
            legacy: M3LegacyCanonicalState,
            file: File,
            fault: M3CompactCanonicalMigrationFault?,
        ): M3CompactDirectory {
            val entries =
                M3CompactDirectory(
                    pageCount(legacy.sourceCount) + pageCount(legacy.supportCount)
                )
            var ordinal = 0
            FileOutputStream(file).use { output ->
                fun append(
                    kind: M3CanonicalPageKind,
                    records: Int,
                    minimum: Long,
                    maximum: Long,
                    bytes: ByteArray,
                ) {
                    check(bytes.size == M3CanonicalPageCache.PAGE_BYTES)
                    if (
                        fault == M3CompactCanonicalMigrationFault.DURING_PAGE_WRITE &&
                            ordinal == 0
                    ) {
                        output.write(bytes, 0, 100)
                        error("fault")
                    }
                    val offset = ordinal.toLong() * M3CanonicalPageCache.PAGE_BYTES
                    output.write(bytes)
                    entries.append(
                        M3CanonicalDirectoryEntry(
                            kind,
                            ordinal,
                            minimum,
                            maximum,
                            offset,
                            bytes.size,
                            records,
                            m3PageSha256(bytes),
                        )
                    )
                    ordinal++
                }

                val sourcePage = ArrayList<M3PagedSource>(M3CanonicalPageCache.MAX_RECORDS)
                fun flushSourcePage() {
                    if (sourcePage.isEmpty()) return
                    append(
                        M3CanonicalPageKind.SOURCE,
                        sourcePage.size,
                        sourcePage.first().id.value,
                        sourcePage.last().id.value,
                        M3CanonicalPageCache.encodeSourcePage(ordinal, sourcePage),
                    )
                    sourcePage.clear()
                }
                legacy.visitSources { source ->
                    sourcePage += source
                    if (sourcePage.size == M3CanonicalPageCache.MAX_RECORDS) flushSourcePage()
                }
                flushSourcePage()

                val supportPage = ArrayList<M3PagedSupport>(M3CanonicalPageCache.MAX_RECORDS)
                fun flushSupportPage() {
                    if (supportPage.isEmpty()) return
                    append(
                        M3CanonicalPageKind.SUPPORT,
                        supportPage.size,
                        supportPage.first().target.value,
                        supportPage.last().target.value,
                        M3CanonicalPageCache.encodeSupportPage(ordinal, supportPage),
                    )
                    supportPage.clear()
                }
                legacy.visitSupports { target, source ->
                    supportPage += M3PagedSupport(M3SurfaceId(target), source)
                    if (supportPage.size == M3CanonicalPageCache.MAX_RECORDS) flushSupportPage()
                }
                flushSupportPage()
                if (fault == M3CompactCanonicalMigrationFault.BEFORE_PAGE_SYNC) error("fault")
                output.fd.sync()
            }
            return entries
        }

        private fun buildStore(
            group: M3SurfaceGroup,
            configuration: M3SurfaceOwnershipConfiguration,
            candidate: File,
            rootHash: ByteArray,
            root: M3CompactRoot,
            resident: M3CompactResident,
            entries: M3CompactDirectory,
            storage: M3CompactStorageReceipt,
        ): M3CompactCanonicalStore {
            val idOrder = IntArray(configuration.surfaceCapacity) { it }
            val cut =
                M3CompactCanonicalCut(
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
                    M3CanonicalReceiptBytes(rootHash),
                    M3CanonicalReceiptBytes(root.sourceHash),
                )
            return M3CompactCanonicalStore(
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
                idOrder,
                resident.pageOrder,
                resident.lineageSource,
                resident.lineageTarget,
                entries,
                entries.sourcePageCount(),
                storage,
            )
        }

        private fun validateLegacyForV6(
            legacy: M3LegacyCanonicalState,
            configuration: M3SurfaceOwnershipConfiguration,
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

        private fun candidateDirectory(parent: File, group: M3SurfaceGroup) =
            File(parent, "m3-canonical-v6-${m3PageSha256(group.value.encodeToByteArray()).hex()}")
    }
}

private fun List<M3PagedSource>.binarySearchUnsigned(id: Long): M3PagedSource? {
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

internal data class M3CompactLocation(val region: M3StorageRegion, val page: Int)

internal fun m3CompactLocation(
    c: M3SurfaceOwnershipConfiguration,
    v: M3Voxel,
): M3CompactLocation? {
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
        M3CompactLocation(
            M3StorageRegion(regionX, regionY, regionZ),
            pageX + 3 * (pageY + 3 * pageZ),
        )
}

internal fun compareVoxel(ax: Int, ay: Int, az: Int, bx: Int, by: Int, bz: Int) =
    when {
        ax != bx -> ax.compareTo(bx)
        ay != by -> ay.compareTo(by)
        else -> az.compareTo(bz)
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

private fun M3CompactDirectory.sourcePageCount(): Int {
    var count = 0
    while (count < size && kind[count].toInt() == M3CanonicalPageKind.SOURCE.wire) count++
    return count
}
