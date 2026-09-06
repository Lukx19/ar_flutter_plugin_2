package com.uhg0.ar_flutter_plugin_2.visibilitygrid

import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption
import java.security.MessageDigest

/**
 * An immutable, deliberately unreferenced canonical surface delta.  It is not a schema-5
 * authority and this type has no operation which can make it one.  #123 owns
 * selecting a complete [MutableSemanticRoot].
 */
internal class CanonicalCowGeneration private constructor(
    val directory: File,
    val root: MutableSemanticRoot,
    private val entries: List<CowDirectoryEntry>,
    private val storage: CowStorageReceipt,
) : AutoCloseable {
    private val entryIndex = CowDirectoryIndex(entries)
    private var closed = false
    private var cowPagesRead = 0L
    private var cowRecordsInspected = 0L
    private var cowHashesValidated = 0L
    private var cowBytesRead = 0L

    @Synchronized
    override fun close() { closed = true }

    @Synchronized
    fun storageReceipt() = storage

    @Synchronized
    fun readWorkReceipt() = CowReadWork(cowPagesRead, cowRecordsInspected, cowHashesValidated, cowBytesRead)

    @Synchronized
    internal fun matchingPageCount(kind: CowFragmentKind, key: Long): Long = entryIndex.matchingPageCount(kind, key)

    @Synchronized
    internal fun pageCount(kind: CowFragmentKind): Long = entryIndex.pageCount(kind)
    @Synchronized
    internal fun withAllocatedStorage(bytes: Long): CanonicalCowGeneration {
        check(!closed)
        return CanonicalCowGeneration(directory, root, entries, storage.copy(allocatedBytes = bytes))
    }

    /** Holds the generation lifecycle lock across a complete overlay operation. */
    @Synchronized
    internal fun <T> readOr(closedResult: T, operation: () -> T): T =
        if (closed) closedResult else operation()

    /** Private lookup view.  It has no publication capability. */
    @Synchronized
    fun overlay(base: CanonicalStateView): CanonicalStateView {
        require(!closed && base.cut == root.baseCut)
        return CowOverlay(base, this)
    }

    /** Root-bound private page cursor; #114's public cursor contract is unchanged. */
    @Synchronized
    fun readPage(
        base: CanonicalStateView,
        region: StorageRegion,
        page: Int,
        cursor: CowPageCursor?,
        limit: Int,
    ): CowPageRead {
        if (closed || base.cut != root.baseCut) return CowPageRead.Refused(CompactCanonicalRefusal.CLOSED)
        val target = root.targetCut()
        if (cursor != null && (cursor.rootHash != target.rootHash || cursor.region != region || cursor.page != page))
            return CowPageRead.Refused(CompactCanonicalRefusal.STALE_CURSOR)
        val state = cursor ?: CowPageCursor(target.rootHash, region, page, 0, 0, 0)
        val result = CowOverlay(base, this).readPageWindow(region, page, state, limit)
            ?: return CowPageRead.Refused(CompactCanonicalRefusal.CORRUPT)
        return CowPageRead.Complete(result.rows, result.nextCursor, result.inspectedRows)
    }

    @Synchronized
    private fun records(kind: CowFragmentKind, sink: (CowRecord) -> Boolean): Boolean {
        if (closed) return false
        return entryIndex.entries(kind).asSequence().all { entry ->
            val file = File(directory, entry.file)
            readPage(file, entry, sink)
        }
    }

    @Synchronized
    private fun records(kind: CowFragmentKind, key: Long, sink: (CowRecord) -> Boolean): Boolean {
        if (closed) return false
        return entryIndex.forEachMatching(kind, key) { entry ->
            readPage(File(directory, entry.file), entry, sink)
        }
    }

    private fun readPage(file: File, entry: CowDirectoryEntry, sink: (CowRecord) -> Boolean): Boolean =
        readPageStatus(file, entry, sink) == CowPageVisitStatus.VALID_COMPLETE

    private fun readPageStatus(file: File, entry: CowDirectoryEntry, sink: (CowRecord) -> Boolean): CowPageVisitStatus {
        if (!file.isFile || file.length() < (entry.page.toLong() + 1) * PAGE_BYTES) return CowPageVisitStatus.CORRUPT
        cowPagesRead++
        cowHashesValidated++
        cowRecordsInspected += entry.count
        cowBytesRead = try { Math.addExact(cowBytesRead, PAGE_BYTES.toLong()) } catch (_: ArithmeticException) { Long.MAX_VALUE }
        val bytes = ByteArray(PAGE_BYTES)
        try {
            RandomAccessReader(file, entry.page.toLong() * PAGE_BYTES).use { input -> input.readFully(bytes) }
        } catch (_: Exception) { return CowPageVisitStatus.CORRUPT }
        if (!sha(bytes).contentEquals(entry.hash)) return CowPageVisitStatus.CORRUPT
        val input = DataInputStream(bytes.inputStream())
        if (input.readInt() != PAGE_MAGIC || input.readInt() != entry.kind.wire || input.readInt() != entry.count) return CowPageVisitStatus.CORRUPT
        var keepGoing = true
        var minimum = Long.MAX_VALUE
        var maximum = Long.MIN_VALUE
        repeat(entry.count) {
            val record = when (entry.kind) {
                CowFragmentKind.ROW -> CowRecord.Row(readRecord(input))
                CowFragmentKind.ID_TOMBSTONE, CowFragmentKind.SUPPORT_TOMBSTONE -> CowRecord.Tombstone(input.readLong(), 0, 0, 0, 0)
                CowFragmentKind.SUPPORT -> CowRecord.Support(input.readLong(), readRecord(input))
                CowFragmentKind.SOURCE -> CowRecord.Source(readRecord(input))
                CowFragmentKind.LINEAGE -> CowRecord.Lineage(input.readLong(), input.readLong())
                CowFragmentKind.ID_INDEX -> CowRecord.Index(input.readLong(), 0, 0, 0, 0)
                CowFragmentKind.VOXEL_INDEX, CowFragmentKind.PAGE_INDEX -> CowRecord.Index(0, input.readInt(), input.readInt(), input.readInt(), input.readLong())
                CowFragmentKind.VOXEL_TOMBSTONE, CowFragmentKind.PAGE_TOMBSTONE -> CowRecord.Tombstone(0, input.readInt(), input.readInt(), input.readInt(), input.readLong())
                CowFragmentKind.LINEAGE_TOMBSTONE -> CowRecord.Lineage(input.readLong(), input.readLong())
            }
            val key = recordKey(entry.kind, record) ?: return CowPageVisitStatus.CORRUPT
            if (it == 0) { minimum = key; maximum = key }
            else {
                if (java.lang.Long.compareUnsigned(key, minimum) < 0) minimum = key
                if (java.lang.Long.compareUnsigned(key, maximum) > 0) maximum = key
            }
            if (keepGoing) keepGoing = sink(record)
        }
        if (minimum != entry.minimumKey || maximum != entry.maximumKey) return CowPageVisitStatus.CORRUPT
        while (input.available() > 0) if (input.readUnsignedByte() != 0) return CowPageVisitStatus.CORRUPT
        return if (keepGoing) CowPageVisitStatus.VALID_COMPLETE else CowPageVisitStatus.VALID_EARLY_STOP
    }

    private fun recordKey(kind: CowFragmentKind, record: CowRecord): Long? = when (record) {
                is CowRecord.Row -> record.value.id
                is CowRecord.Source -> record.value.id
                is CowRecord.Support -> record.target
                is CowRecord.Lineage -> record.source
                is CowRecord.Index -> when (kind) {
                    CowFragmentKind.ID_INDEX -> record.key
                    CowFragmentKind.PAGE_INDEX -> record.location()?.let { pageKey(it.region, it.page) }
                    else -> voxelKey(record.x, record.y, record.z)
                }
                is CowRecord.Tombstone -> when (kind) {
                    CowFragmentKind.ID_TOMBSTONE, CowFragmentKind.SUPPORT_TOMBSTONE -> record.key
                    CowFragmentKind.PAGE_TOMBSTONE -> record.location()?.let { pageKey(it.region, it.page) }
                    else -> voxelKey(record.x, record.y, record.z)
                }
            }

    private fun compareSortedIndex(kind: CowFragmentKind, left: CowRecord, right: CowRecord): Int {
        val key = java.lang.Long.compareUnsigned(requireNotNull(recordKey(kind, left)), requireNotNull(recordKey(kind, right)))
        if (key != 0) return key
        fun x(record: CowRecord) = when (record) {
            is CowRecord.Index -> record.x
            is CowRecord.Tombstone -> record.x
            else -> error("non-index record")
        }
        fun y(record: CowRecord) = when (record) { is CowRecord.Index -> record.y; is CowRecord.Tombstone -> record.y; else -> error("non-index record") }
        fun z(record: CowRecord) = when (record) { is CowRecord.Index -> record.z; is CowRecord.Tombstone -> record.z; else -> error("non-index record") }
        fun id(record: CowRecord) = when (record) {
            is CowRecord.Index -> record.id
            is CowRecord.Tombstone -> record.id
            else -> error("non-index record")
        }
        val voxel = compareVoxel(x(left), y(left), z(left), x(right), y(right), z(right))
        return if (voxel != 0) voxel else java.lang.Long.compareUnsigned(id(left), id(right))
    }

    private fun readRecord(input: DataInputStream) = CowRow(
        input.readLong(), input.readInt(), input.readInt(), input.readInt(), input.readInt(), input.readInt(),
        input.readLong(), input.readLong(), input.readLong(), input.readLong(),
    )

    internal fun visit(kind: CowFragmentKind, sink: (CowRecord) -> Boolean) = records(kind, sink)
    internal fun visit(kind: CowFragmentKind, key: Long, sink: (CowRecord) -> Boolean) = records(kind, key, sink)

    @Synchronized
    internal fun visitBounded(
        kind: CowFragmentKind,
        key: Long,
        maximumPageReads: Long,
        maximumBytesRead: Long,
        sink: (CowRecord) -> Boolean,
    ): CowBoundedVisitResult {
        if (closed) return CowBoundedVisitResult.Corrupt(CanonicalReadWork.ZERO)
        if (maximumPageReads < 0L || maximumBytesRead < 0L) {
            return CowBoundedVisitResult.Limit(CanonicalReadWork.ZERO)
        }
        val before = readWorkReceipt()
        var status = CowPageVisitStatus.VALID_COMPLETE
        var limit = false
        entryIndex.forEachMatching(kind, key) { entry ->
            val consumed = readWorkReceipt() - before
            if (consumed.pages < 0L || consumed.bytes < 0L) {
                status = CowPageVisitStatus.CORRUPT
                return@forEachMatching false
            }
            val remainingPages = maximumPageReads - consumed.pages
            val remainingBytes = maximumBytesRead - consumed.bytes
            if (remainingPages < 1L || remainingBytes < PAGE_BYTES.toLong()) {
                limit = true
                return@forEachMatching false
            }
            status = readPageStatus(File(directory, entry.file), entry, sink)
            status == CowPageVisitStatus.VALID_COMPLETE
        }
        val work = (readWorkReceipt() - before).canonical()
        return when {
            limit -> CowBoundedVisitResult.Limit(work)
            status == CowPageVisitStatus.CORRUPT -> CowBoundedVisitResult.Corrupt(work)
            status == CowPageVisitStatus.VALID_EARLY_STOP -> CowBoundedVisitResult.Valid(CowPageVisitStatus.VALID_EARLY_STOP, work)
            else -> CowBoundedVisitResult.Valid(CowPageVisitStatus.VALID_COMPLETE, work)
        }
    }

    @Synchronized
    internal fun has(kind: CowFragmentKind, key: Long) = !closed && entryIndex.matchingPageCount(kind, key) > 0L

    @Synchronized
    internal fun indexes(kind: CowFragmentKind, key: Long): CowLookup<CowRecord.Index> {
        val values = ArrayList<CowRecord.Index>()
        val valid = records(kind, key) { record -> if (record is CowRecord.Index) values += record; true }
        return if (valid) CowLookup.Complete(values) else CowLookup.Refused
    }

    @Synchronized
    internal fun indexWindow(kind: CowFragmentKind, key: Long, offset: Int, limit: Int): CowWindow<CowRecord.Index> =
        window(kind, key, offset, limit) { it as? CowRecord.Index }

    @Synchronized
    internal fun tombstoneWindow(kind: CowFragmentKind, key: Long, offset: Int, limit: Int): CowWindow<CowRecord.Tombstone> =
        window(kind, key, offset, limit) { it as? CowRecord.Tombstone }

    private fun <T> window(kind: CowFragmentKind, key: Long, offset: Int, limit: Int, cast: (CowRecord) -> T?): CowWindow<T> {
        if (closed || offset < 0 || limit !in 1..512) return CowWindow.Refused
        val values = ArrayList<T>(limit + 1)
        var ordinal = 0
        val candidates = entries.filter { it.kind == kind && java.lang.Long.compareUnsigned(key, it.minimumKey) >= 0 && java.lang.Long.compareUnsigned(key, it.maximumKey) <= 0 }
        for (entry in candidates) {
            if (entry.minimumKey == key && entry.maximumKey == key && ordinal + entry.count <= offset) {
                ordinal += entry.count
                continue
            }
            val valid = readPage(File(directory, entry.file), entry) { record ->
                val value = cast(record)
                if (value != null && recordKey(kind, record) == key) {
                    if (ordinal++ >= offset && values.size <= limit) values += value
                }
                true
            }
            if (!valid) return CowWindow.Refused
            if (values.size > limit) break
        }
        val hasNext = values.size > limit
        return CowWindow.Complete(if (hasNext) values.subList(0, limit).toList() else values, if (hasNext) offset + limit else null)
    }

    @Synchronized
    internal fun tombstones(kind: CowFragmentKind, key: Long): CowLookup<CowRecord.Tombstone> {
        val values = ArrayList<CowRecord.Tombstone>()
        val valid = records(kind, key) { record -> if (record is CowRecord.Tombstone) values += record; true }
        return if (valid) CowLookup.Complete(values) else CowLookup.Refused
    }

    @Synchronized
    internal fun row(id: Long): CowLookup<CowRow> {
        var value: CowRow? = null
        val valid = records(CowFragmentKind.ROW, id) { record -> if (record is CowRecord.Row && record.value.id == id) value = record.value; true }
        return if (valid) CowLookup.Complete(listOfNotNull(value)) else CowLookup.Refused
    }

    @Synchronized
    internal fun visitSupport(
        rootHash: CanonicalReceiptBytes,
        target: SurfaceId,
        cursor: SourceSupportCursor?,
        sink: (PagedSupport) -> Boolean,
    ): SourceSupportRead {
        val candidates = entries.withIndex().filter { (_, entry) ->
            entry.kind == CowFragmentKind.SUPPORT &&
                java.lang.Long.compareUnsigned(target.value, entry.minimumKey) >= 0 &&
                java.lang.Long.compareUnsigned(target.value, entry.maximumKey) <= 0
        }
        if (cursor != null && (cursor.offset < 0 || candidates.none { it.index == cursor.ordinal }))
            return SourceSupportRead.Refused(CompactCanonicalRefusal.STALE_CURSOR)
        if (candidates.isEmpty()) return SourceSupportRead.Complete(0, null, 0, 0)
        val startEntry = cursor?.ordinal ?: candidates.first().index
        var skip = cursor?.offset ?: 0
        var delivered = 0
        for ((entryIndex, entry) in candidates) {
            if (entryIndex < startEntry) continue
            if (entryIndex > startEntry) skip = 0
            var matchingOffset = 0
            var stopped = false
            var resumeOffset = 0
            val status = readPageStatus(File(directory, entry.file), entry) { record ->
                if (record is CowRecord.Support && record.target == target.value) {
                    if (matchingOffset++ >= skip && !stopped) {
                        if (sink(PagedSupport(target, record.source.source()))) delivered++ else { stopped = true; resumeOffset = matchingOffset - 1 }
                    }
                }
                true
            }
            if (status == CowPageVisitStatus.CORRUPT) return SourceSupportRead.Refused(CompactCanonicalRefusal.CORRUPT)
            if (cursor != null && entryIndex == startEntry && skip >= matchingOffset)
                return SourceSupportRead.Refused(CompactCanonicalRefusal.STALE_CURSOR)
            if (stopped) return SourceSupportRead.Complete(delivered, SourceSupportCursor(rootHash, target, entryIndex, resumeOffset), candidates.size, candidates.size * PAGE_BYTES)
        }
        return SourceSupportRead.Complete(delivered, null, candidates.size, candidates.size * PAGE_BYTES)
    }

    companion object {
        const val PAGE_BYTES = 16_384
        const val DIRECTORY_LIMIT_BYTES = 1_048_576
        /** Fixed visitor scratch plus one page for every writer kind. */
        val FIXED_PHASE_BYTES = PreparedIntentVisitorResources.STREAMING_SCRATCH_BYTES +
            CowFragmentKind.entries.size * PAGE_BYTES.toLong()
        /** Writer maps/instances, receipts, list backing arrays and transient root metadata. */
        internal const val PHASE_OBJECT_OVERHEAD_BYTES = 65_536L
        internal const val SORT_SCRATCH_BYTES = 16_384L
        internal fun phasePeakBytes(directoryEntries: Int) = FIXED_PHASE_BYTES + SORT_SCRATCH_BYTES +
            PHASE_OBJECT_OVERHEAD_BYTES + 64L + directoryEntries * 128L
        internal const val PAGE_MAGIC = 0x4d334350
        private const val ROOT_FILE = "root.m3cow"
        private const val DIRECTORY_FILE = "directory.m3cow"
        internal const val CURRENT_UNACKED_FILE = "current-unacked.intent"

        internal fun generationDirectory(parent: File, identity: CowGenerationIdentity) =
            File(parent, "canonical-surface-cow-${identity.hash.toByteArray().hex()}")

        internal fun open(
            directory: File,
            expected: CowGenerationIdentity? = null,
            acknowledgedCurrent: PreparedIntentCurrentReceipt? = null,
        ): CanonicalCowGeneration? = try {
            val rootFile = File(directory, ROOT_FILE)
            require(rootFile.length() <= DIRECTORY_LIMIT_BYTES && File(directory, DIRECTORY_FILE).length() <= DIRECTORY_LIMIT_BYTES)
            val root = MutableSemanticRoot.read(rootFile) ?: return null
            val entries = root.manifest
            require(CowDirectoryEntry.matches(File(directory, DIRECTORY_FILE), entries))
            require(entries.sumOf { it.encodedBytes() } <= DIRECTORY_LIMIT_BYTES)
            require(64L + entries.size * 128L <= DIRECTORY_LIMIT_BYTES)
            val currentFile = File(directory, CURRENT_UNACKED_FILE)
            require(
                validateCurrent(currentFile, root.current) ||
                    (!currentFile.exists() && acknowledgedCurrent == root.current)
            )
            val identity = CowGenerationIdentity.from(root)
            require(expected == null || expected == identity)
            require(entries == entries.sortedWith(compareBy<CowDirectoryEntry> { it.kind.wire }.thenBy { it.page }))
            entries.forEach { entry ->
                require(entry.count in 1..entry.kind.recordsPerPage)
                require(entry.page >= 0 && entry.file == entry.kind.file)
                require(entry.offset == entry.page.toLong() * PAGE_BYTES && entry.length == PAGE_BYTES)
            }
            entries.groupBy { it.kind }.forEach { (kind, pages) ->
                require(pages.indices.all { index -> pages[index].page == index })
                require(File(directory, kind.file).length() == pages.size.toLong() * PAGE_BYTES)
            }
            val expectedNames = entries.mapTo(mutableSetOf()) { it.file } + setOf(ROOT_FILE, DIRECTORY_FILE) +
                if (currentFile.exists()) setOf(CURRENT_UNACKED_FILE) else emptySet()
            require(directory.listFiles().orEmpty().mapTo(mutableSetOf()) { it.name } == expectedNames)
            val storage = CowStorageReceipt(
                directory.listFiles().orEmpty().sumOf { allocated(it) }, entries.size, phasePeakBytes(entries.size),
            )
            require(storage.phasePeakBytes <= DIRECTORY_LIMIT_BYTES)
            CanonicalCowGeneration(directory, root, entries, storage).also { generation ->
                val sortedKinds = setOf(
                    CowFragmentKind.VOXEL_INDEX, CowFragmentKind.PAGE_INDEX,
                    CowFragmentKind.VOXEL_TOMBSTONE, CowFragmentKind.PAGE_TOMBSTONE,
                )
                CowFragmentKind.entries.forEach { kind ->
                    var previous: CowRecord? = null
                    require(generation.records(kind) { record ->
                        val ordered = kind !in sortedKinds || previous?.let { generation.compareSortedIndex(kind, it, record) <= 0 } != false
                        previous = record
                        ordered
                    }) { "invalid fragment $kind" }
                }
                CanonicalCowGenerationTestHooks.onVerifiedOpen?.invoke(
                    CanonicalCowVerifiedOpen(entries.size, root.current.length),
                )
            }
        } catch (_: Exception) { null }

        internal fun rootFile(directory: File) = File(directory, ROOT_FILE)
        internal fun directoryFile(directory: File) = File(directory, DIRECTORY_FILE)
        internal fun sha(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)
        internal fun voxelKey(x: Int, y: Int, z: Int) = digestKey(x, y, z, 0x564f5845)
        internal fun pageKey(region: StorageRegion, page: Int) = digestKey(region.x, region.y, region.z, page)
        private fun digestKey(a: Int, b: Int, c: Int, d: Int): Long {
            var value = -0x340d631b7bdddcdbL
            listOf(a, b, c, d).forEach { part ->
                repeat(4) { byte -> value = (value xor ((part ushr (byte * 8)) and 0xff).toLong()) * 0x100000001b3L }
            }
            return value
        }
        private fun validateCurrent(file: File, expected: PreparedIntentCurrentReceipt): Boolean = try {
            if (!file.isFile || file.length() != expected.length) return false
            val digest = MessageDigest.getInstance("SHA-256")
            FileInputStream(file).use { input ->
                val scratch = ByteArray(PreparedIntentVisitorResources.STREAMING_SCRATCH_BYTES)
                while (true) { val count = input.read(scratch); if (count < 0) break; digest.update(scratch, 0, count) }
            }
            CanonicalReceiptBytes(digest.digest()) == expected.hash
        } catch (_: Exception) { false }
        internal fun allocated(file: File): Long = if (file.isDirectory) file.listFiles().orEmpty().sumOf(::allocated) else file.length()
        internal fun sameManifest(left: List<CowDirectoryEntry>, right: List<CowDirectoryEntry>) = left.size == right.size && left.indices.all { index ->
            val a = left[index]; val b = right[index]
            a.kind == b.kind && a.page == b.page && a.minimumKey == b.minimumKey && a.maximumKey == b.maximumKey && a.offset == b.offset && a.length == b.length && a.count == b.count && a.hash.contentEquals(b.hash)
        }
        internal fun sync(directory: File) {
            if (!System.getProperty("os.name").orEmpty().startsWith("Windows", true))
                FileChannel.open(directory.toPath(), StandardOpenOption.READ).use { it.force(true) }
        }
    }
}

/** Disabled-by-default scalar test observation; it retains no opened generation or payload. */
internal object CanonicalCowGenerationTestHooks {
    @Volatile var onVerifiedOpen: ((CanonicalCowVerifiedOpen) -> Unit)? = null
}

internal data class CanonicalCowVerifiedOpen(
    val verifiedPages: Int,
    val currentChecksumBytes: Long,
)

/**
 * Process-local proof cache scoped to one live runtime resource lease. COW
 * directories are immutable after publication; startup/recovery still enters
 * through a cold lease and performs the complete verification above once.
 */
internal enum class CowFragmentKind(val wire: Int, val file: String, val recordBytes: Int) {
    ROW(1, "rows.pages", 60), ID_INDEX(2, "id-index.pages", 8),
    VOXEL_INDEX(3, "voxel-index.pages", 20), PAGE_INDEX(4, "page-index.pages", 20),
    SOURCE(5, "source.pages", 60), SUPPORT(6, "support.pages", 68), LINEAGE(7, "lineage.pages", 16),
    ID_TOMBSTONE(8, "id-tombstones.pages", 8),
    VOXEL_TOMBSTONE(9, "voxel-tombstones.pages", 20),
    PAGE_TOMBSTONE(10, "page-tombstones.pages", 20),
    SUPPORT_TOMBSTONE(11, "support-tombstones.pages", 8),
    LINEAGE_TOMBSTONE(12, "lineage-tombstones.pages", 16);
    val recordsPerPage get() = (CanonicalCowGeneration.PAGE_BYTES - 12) / recordBytes
}

internal data class CowRow(val id: Long, val x: Int, val y: Int, val z: Int, val normal: Int, val confidence: Int, val f0: Long, val f1: Long, val f2: Long, val f3: Long) {
    fun source() = PagedSource(SurfaceId(id), Voxel(x, y, z), normal, confidence, CanonicalReceiptBytes(words()))
    fun surface() = CompactSurface(SurfaceId(id), Voxel(x, y, z), normal, confidence)
    fun words() = ByteArray(32).also { bytes ->
        java.nio.ByteBuffer.wrap(bytes).putLong(f0).putLong(f1).putLong(f2).putLong(f3)
    }
}

internal sealed interface CowRecord {
    data class Row(val value: CowRow) : CowRecord
    data class Tombstone(val key: Long, val x: Int, val y: Int, val z: Int, val id: Long) : CowRecord
    data class Support(val target: Long, val source: CowRow) : CowRecord
    data class Source(val value: CowRow) : CowRecord
    data class Lineage(val source: Long, val target: Long) : CowRecord
    data class Index(val key: Long, val x: Int, val y: Int, val z: Int, val id: Long) : CowRecord
}

private fun CowRecord.Index.location() = CompactLocation(SurfaceOwnershipConfiguration(), Voxel(x, y, z))
private fun CowRecord.Tombstone.location() = CompactLocation(SurfaceOwnershipConfiguration(), Voxel(x, y, z))

internal sealed interface CowLookup<out T> {
    data class Complete<T>(val values: List<T>) : CowLookup<T>
    data object Refused : CowLookup<Nothing>
}
internal sealed interface CowWindow<out T> {
    data class Complete<T>(val values: List<T>, val nextOffset: Int?) : CowWindow<T>
    data object Refused : CowWindow<Nothing>
}

internal data class CowDirectoryEntry(val kind: CowFragmentKind, val page: Int, val minimumKey: Long, val maximumKey: Long, val offset: Long, val length: Int, val count: Int, val hash: ByteArray) {
    val file get() = kind.file
    fun encodedBytes() = 1 + 4 + 8 + 8 + 8 + 4 + 4 + 32
    fun write(out: DataOutputStream) { out.writeByte(kind.wire); out.writeInt(page); out.writeLong(minimumKey); out.writeLong(maximumKey); out.writeLong(offset); out.writeInt(length); out.writeInt(count); out.write(hash) }
    companion object {
        private const val MAGIC = 0x4d334344
        fun write(file: File, entries: List<CowDirectoryEntry>, failDuringWrite: Boolean = false) {
            require(entries.sumOf { it.encodedBytes() } <= CanonicalCowGeneration.DIRECTORY_LIMIT_BYTES)
            writeChecked(file, failDuringWrite) { out -> out.writeInt(MAGIC); out.writeInt(entries.size); entries.forEach { it.write(out) } }
        }
        fun encodedFileBytes(entries: List<CowDirectoryEntry>) = 8L + entries.sumOf { it.encodedBytes().toLong() } + 32L
        fun read(file: File): List<CowDirectoryEntry>? = try {
            checkedInput(file).use { input ->
                require(input.readInt() == MAGIC); val count = input.readInt(); require(count in 0..20_000)
                List(count) {
                    val wire = input.readUnsignedByte()
                    val kind = requireNotNull(CowFragmentKind.entries.firstOrNull { item -> item.wire == wire }) { "unknown directory kind $wire" }
                    val page = input.readInt(); val minimum = input.readLong(); val maximum = input.readLong(); val offset = input.readLong(); val length = input.readInt(); val records = input.readInt(); val hash = ByteArray(32).also(input::readFully)
                    require(java.lang.Long.compareUnsigned(minimum, maximum) <= 0 && offset == page.toLong() * CanonicalCowGeneration.PAGE_BYTES && length == CanonicalCowGeneration.PAGE_BYTES)
                    CowDirectoryEntry(kind, page, minimum, maximum, offset, length, records, hash)
                }.also { require(input.read() == -1) }
            }
        } catch (_: Exception) { null }
        fun matches(file: File, expected: List<CowDirectoryEntry>): Boolean = try {
            checkedInput(file).use { input ->
                require(input.readInt() == MAGIC && input.readInt() == expected.size)
                expected.forEach { wanted ->
                    val wire = input.readUnsignedByte(); val page = input.readInt(); val minimum = input.readLong(); val maximum = input.readLong()
                    val offset = input.readLong(); val length = input.readInt(); val records = input.readInt(); val hash = ByteArray(32).also(input::readFully)
                    require(wire == wanted.kind.wire && page == wanted.page && minimum == wanted.minimumKey && maximum == wanted.maximumKey &&
                        offset == wanted.offset && length == wanted.length && records == wanted.count && hash.contentEquals(wanted.hash))
                }
                require(input.read() == -1)
            }
            true
        } catch (_: Exception) { false }
    }
}

/**
 * Immutable keyed directory metadata.  Page ranges are sorted once at open, so a
 * lookup only visits ranges whose key interval can contain the requested key.
 */
private class CowDirectoryIndex(entries: List<CowDirectoryEntry>) {
    private val byKind = Array(CowFragmentKind.entries.size) { kind ->
        entries.filter { it.kind.ordinal == kind }.sortedBy { it.page }.toTypedArray()
    }
    private val byKey = Array(CowFragmentKind.entries.size) { kind ->
        byKind[kind].sortedWith { left, right ->
            val minimum = java.lang.Long.compareUnsigned(left.minimumKey, right.minimumKey)
            if (minimum != 0) minimum else java.lang.Long.compareUnsigned(left.maximumKey, right.maximumKey)
        }.toTypedArray()
    }
    private val prefixMaximum = Array(CowFragmentKind.entries.size) { kind ->
        LongArray(byKey[kind].size).also { maxima ->
            byKey[kind].forEachIndexed { index, entry ->
                maxima[index] = if (index == 0 ||
                    java.lang.Long.compareUnsigned(entry.maximumKey, maxima[index - 1]) > 0
                ) entry.maximumKey else maxima[index - 1]
            }
        }
    }

    fun entries(kind: CowFragmentKind): Array<CowDirectoryEntry> = byKind[kind.ordinal]

    fun pageCount(kind: CowFragmentKind): Long = byKind[kind.ordinal].size.toLong()

    fun matchingPageCount(kind: CowFragmentKind, key: Long): Long {
        var count = 0L
        forEachMatching(kind, key) { count++; true }
        return count
    }

    fun forEachMatching(kind: CowFragmentKind, key: Long, action: (CowDirectoryEntry) -> Boolean): Boolean {
        val ranges = byKey[kind.ordinal]
        if (ranges.isEmpty()) return true
        val maxima = prefixMaximum[kind.ordinal]
        var low = 0
        var high = ranges.size
        while (low < high) {
            val middle = (low + high) ushr 1
            if (java.lang.Long.compareUnsigned(maxima[middle], key) >= 0) high = middle else low = middle + 1
        }
        val firstPossible = low
        low = firstPossible
        high = ranges.size
        while (low < high) {
            val middle = (low + high) ushr 1
            if (java.lang.Long.compareUnsigned(ranges[middle].minimumKey, key) <= 0) low = middle + 1 else high = middle
        }
        for (index in firstPossible until low) {
            val entry = ranges[index]
            if (java.lang.Long.compareUnsigned(key, entry.minimumKey) >= 0 &&
                java.lang.Long.compareUnsigned(key, entry.maximumKey) <= 0 &&
                !action(entry)
            ) return false
        }
        return true
    }
}

/** Complete, self-validating root for a candidate only.  It names #114's root hash. */
internal data class MutableSemanticRoot(
    val baseCut: CompactCanonicalCut,
    val commandId: String,
    val commandKind: PreparedMutationKind,
    val commandHash: CanonicalReceiptBytes,
    val commandFingerprint: CanonicalReceiptBytes,
    val targetHighWater: Long,
    val targetLive: Int,
    val targetSource: Int,
    val targetSupport: Int,
    val targetLineage: Int,
    val targetGeometry: Long,
    val targetLineageRevision: Long,
    val current: PreparedIntentCurrentReceipt,
    val manifest: List<CowDirectoryEntry>,
) {
    fun targetCut() = CompactCanonicalCut(baseCut.group, baseCut.profile, targetGeometry, targetLineageRevision,
        targetHighWater, targetLive, targetSource, targetSupport, targetLineage, baseCut.seededEmptyBaseline,
        CowGenerationIdentity.from(this).hash, sourceIdentity())
    private fun sourceIdentity(): CanonicalReceiptBytes {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(baseCut.sourceHash.toByteArray())
        manifest.filter { it.kind in setOf(CowFragmentKind.SOURCE, CowFragmentKind.SUPPORT, CowFragmentKind.SUPPORT_TOMBSTONE) }
            .forEach { entry -> digest.update(entry.kind.wire.toByte()); digest.update(entry.hash) }
        return CanonicalReceiptBytes(digest.digest())
    }
    fun write(file: File, failDuringWrite: Boolean = false) = writeChecked(file, failDuringWrite, ::writeBody)
    fun encodedBytes(): Long = CountingSink().also { sink -> DataOutputStream(sink).use(::writeBody) }.count + 32L
    private fun writeBody(out: DataOutputStream) {
        out.writeInt(ROOT_MAGIC); out.writeUTF(baseCut.group.value); out.writeUTF(baseCut.profile)
        out.writeLong(baseCut.geometryRevision); out.writeLong(baseCut.lineageRevision); out.writeLong(baseCut.nextSurfaceIdHighWater)
        out.writeInt(baseCut.liveSurfaceCount); out.writeInt(baseCut.sourceCount); out.writeInt(baseCut.supportCount); out.writeInt(baseCut.lineageCount)
        out.writeBoolean(baseCut.seededEmptyBaseline != null)
        baseCut.seededEmptyBaseline?.let { baseline ->
            out.writeUTF(baseline.bindingIdentity); out.writeUTF(baseline.groupIdentity); out.writeLong(baseline.transactionId)
            out.writeLong(baseline.geometryRevision); out.writeLong(baseline.lineageRevision)
        }
        out.write(baseCut.rootHash.toByteArray()); out.write(baseCut.sourceHash.toByteArray())
        out.writeUTF(commandId); out.writeInt(commandKind.ordinal)
        out.write(commandHash.toByteArray()); out.write(commandFingerprint.toByteArray())
        out.writeLong(targetHighWater); out.writeInt(targetLive); out.writeInt(targetSource); out.writeInt(targetSupport); out.writeInt(targetLineage); out.writeLong(targetGeometry); out.writeLong(targetLineageRevision)
        out.writeLong(current.length); out.write(current.hash.toByteArray()); out.writeInt(manifest.size); manifest.forEach { it.write(out) }
    }
    companion object {
        private const val ROOT_MAGIC = 0x4d334352
        fun read(file: File): MutableSemanticRoot? = try {
            checkedInput(file).use { input ->
                require(input.readInt() == ROOT_MAGIC); val group = SurfaceGroup(input.readUTF()); val profile = input.readUTF()
                val geometry = input.readLong(); val lineage = input.readLong(); val high = input.readLong(); val live = input.readInt(); val sources = input.readInt(); val supports = input.readInt(); val edges = input.readInt()
                val baseline = if (input.readBoolean()) committedEmptyBaseline(
                    input.readUTF(), input.readUTF(), input.readLong(), input.readLong(), input.readLong(),
                ) else null
                val root = CanonicalReceiptBytes(ByteArray(32).also(input::readFully)); val source = CanonicalReceiptBytes(ByteArray(32).also(input::readFully))
                val commandId = input.readUTF(); val kindOrdinal = input.readInt(); require(kindOrdinal in PreparedMutationKind.entries.indices)
                val command = CanonicalReceiptBytes(ByteArray(32).also(input::readFully)); val fingerprint = CanonicalReceiptBytes(ByteArray(32).also(input::readFully))
                val targetHigh = input.readLong(); val targetLive = input.readInt(); val targetSource = input.readInt(); val targetSupport = input.readInt(); val targetLineage = input.readInt(); val targetGeometry = input.readLong(); val targetLineageRevision = input.readLong()
                val current = PreparedIntentCurrentReceipt(input.readLong(), CanonicalReceiptBytes(ByteArray(32).also(input::readFully)))
                val count = input.readInt(); require(count in 0..20_000); val manifest = List(count) {
                    val wire = input.readUnsignedByte(); val kind = requireNotNull(CowFragmentKind.entries.firstOrNull { it.wire == wire }) { "unknown root kind $wire" }; val page = input.readInt(); val minimum = input.readLong(); val maximum = input.readLong(); val offset = input.readLong(); val length = input.readInt(); val records = input.readInt(); val hash = ByteArray(32).also(input::readFully); CowDirectoryEntry(kind, page, minimum, maximum, offset, length, records, hash)
                }
                require(input.read() == -1 && profile == CompactCanonicalStore.PROFILE && targetHigh >= high && targetLive in 0..100_000 && targetSource in 0..300_000 && targetSupport in 0..300_000 && targetLineage in 0..200_000 && targetGeometry >= geometry && targetLineageRevision >= lineage)
                require(validM3CommandId(commandId))
                MutableSemanticRoot(CompactCanonicalCut(group, profile, geometry, lineage, high, live, sources, supports, edges, baseline, root, source), commandId, PreparedMutationKind.entries[kindOrdinal], command, fingerprint, targetHigh, targetLive, targetSource, targetSupport, targetLineage, targetGeometry, targetLineageRevision, current, manifest)
            }
        } catch (_: Exception) { null }
    }
}

internal data class CowGenerationIdentity(val hash: CanonicalReceiptBytes) {
    companion object {
        fun from(root: MutableSemanticRoot): CowGenerationIdentity {
            val digest = MessageDigest.getInstance("SHA-256")
            DataOutputStream(java.security.DigestOutputStream(object : java.io.OutputStream() {
                override fun write(value: Int) = Unit
            }, digest)).use { out ->
                val cut = root.baseCut
                out.writeUTF(cut.group.value); out.writeUTF(cut.profile); out.writeLong(cut.geometryRevision); out.writeLong(cut.lineageRevision)
                out.writeLong(cut.nextSurfaceIdHighWater); out.writeInt(cut.liveSurfaceCount); out.writeInt(cut.sourceCount); out.writeInt(cut.supportCount); out.writeInt(cut.lineageCount)
                out.writeBoolean(cut.seededEmptyBaseline != null); cut.seededEmptyBaseline?.let { baseline ->
                    out.writeUTF(baseline.bindingIdentity); out.writeUTF(baseline.groupIdentity); out.writeLong(baseline.transactionId); out.writeLong(baseline.geometryRevision); out.writeLong(baseline.lineageRevision)
                }
                out.write(cut.rootHash.toByteArray()); out.write(cut.sourceHash.toByteArray()); out.writeUTF(root.commandId); out.writeInt(root.commandKind.ordinal)
                out.write(root.commandHash.toByteArray()); out.write(root.commandFingerprint.toByteArray())
                out.writeLong(root.targetHighWater); out.writeInt(root.targetLive); out.writeInt(root.targetSource); out.writeInt(root.targetSupport); out.writeInt(root.targetLineage); out.writeLong(root.targetGeometry); out.writeLong(root.targetLineageRevision)
                out.writeLong(root.current.length); out.write(root.current.hash.toByteArray()); out.writeInt(root.manifest.size)
                root.manifest.forEach { it.write(out) }
            }
            return CowGenerationIdentity(CanonicalReceiptBytes(digest.digest()))
        }
    }
}

internal data class CowStorageReceipt(val allocatedBytes: Long, val directoryEntries: Int, val phasePeakBytes: Long)
internal data class CowReadWork(
    val pages: Long,
    val records: Long,
    val hashes: Long,
    val bytes: Long = 0L,
) {
    operator fun minus(other: CowReadWork) = CowReadWork(
        pages - other.pages, records - other.records, hashes - other.hashes, bytes - other.bytes,
    )
}
private fun CowReadWork.canonical() = CanonicalReadWork(0L, pages, records, bytes)
internal data class CowPageCursor(
    val rootHash: CanonicalReceiptBytes,
    val region: StorageRegion,
    val page: Int,
    val baseCursor: Int,
    val indexOffset: Int,
    val tombstoneOffset: Int,
)
internal data class CowMergeWindow(val rows: List<CompactSurface>, val nextCursor: CowPageCursor?, val inspectedRows: Int)
internal enum class CowPageVisitStatus { VALID_COMPLETE, VALID_EARLY_STOP, CORRUPT }
internal sealed interface CowBoundedVisitResult {
    val work: CanonicalReadWork
    data class Valid(val status: CowPageVisitStatus, override val work: CanonicalReadWork) : CowBoundedVisitResult
    data class Limit(override val work: CanonicalReadWork) : CowBoundedVisitResult
    data class Corrupt(override val work: CanonicalReadWork) : CowBoundedVisitResult
}
private class CowLongBuffer(initialCapacity: Int = 8) {
    private var values = LongArray(initialCapacity)
    var size = 0
        private set

    fun add(value: Long) {
        if (size == values.size) values = values.copyOf(values.size.coerceAtLeast(1) * 2)
        values[size++] = value
    }

    operator fun get(index: Int): Long = values[index]
}
internal sealed interface CowPageRead {
    data class Complete(val rows: List<CompactSurface>, val nextCursor: CowPageCursor?, val inspectedRows: Int) : CowPageRead
    data class Refused(val reason: CompactCanonicalRefusal) : CowPageRead
}

private val SURFACE_ORDER = compareBy<CompactSurface> { it.voxel.x }
    .thenBy { it.voxel.y }.thenBy { it.voxel.z }.thenBy { it.id.value }
private fun compareSurfaceKey(ax: Int, ay: Int, az: Int, aid: Long, bx: Int, by: Int, bz: Int, bid: Long): Int = when {
    ax != bx -> ax.compareTo(bx)
    ay != by -> ay.compareTo(by)
    az != bz -> az.compareTo(bz)
    else -> aid.compareTo(bid)
}

private class CowOverlay(private val base: CanonicalStateView, private val delta: CanonicalCowGeneration) : CanonicalStateView {
    private class CowBudget(private val maximumPageReads: Long, private val maximumBytesRead: Long) {
        var work = CanonicalReadWork.ZERO
            private set

        fun remainingPageReads() = maximumPageReads - work.pageReads
        fun remainingBytesRead() = maximumBytesRead - work.bytesRead

        fun add(next: CanonicalReadWork): Boolean {
            if (next.directLookups < 0L || next.pageReads < 0L || next.inspectedRows < 0L || next.bytesRead < 0L) return false
            val updated = try {
                CanonicalReadWork(
                    Math.addExact(work.directLookups, next.directLookups),
                    Math.addExact(work.pageReads, next.pageReads),
                    Math.addExact(work.inspectedRows, next.inspectedRows),
                    Math.addExact(work.bytesRead, next.bytesRead),
                )
            } catch (_: ArithmeticException) { return false }
            if (updated.pageReads > maximumPageReads || updated.bytesRead > maximumBytesRead) return false
            work = updated
            return true
        }

        fun <T> complete(value: T): CanonicalBoundedReadResult<T> =
            CanonicalBoundedReadResult.Complete(value, work)
        fun <T> refused(reason: CanonicalBoundedReadRefusal): CanonicalBoundedReadResult<T> =
            CanonicalBoundedReadResult.Refused(reason, work)

        fun <T> combine(result: CanonicalBoundedReadResult<T>): CanonicalBoundedReadResult<T> = when (result) {
            is CanonicalBoundedReadResult.Complete ->
                if (add(result.work)) complete(result.value)
                else refused(CanonicalBoundedReadRefusal.CANONICAL_READ_FAILURE)
            is CanonicalBoundedReadResult.Refused ->
                if (add(result.work)) refused(result.reason)
                else refused(CanonicalBoundedReadRefusal.CANONICAL_READ_FAILURE)
        }

        fun refusal(result: CowBoundedVisitResult): CanonicalBoundedReadRefusal? {
            if (!add(result.work)) return CanonicalBoundedReadRefusal.CANONICAL_READ_FAILURE
            return when (result) {
                is CowBoundedVisitResult.Limit -> CanonicalBoundedReadRefusal.LIMIT_EXHAUSTED
                is CowBoundedVisitResult.Corrupt -> CanonicalBoundedReadRefusal.CANONICAL_READ_FAILURE
                is CowBoundedVisitResult.Valid -> null
            }
        }
    }

    override val generationZeroAuthority: CanonicalStateView get() = base.generationZeroAuthority
    override val cut get() = delta.root.targetCut()
    override fun findById(id: SurfaceId): CompactSurface? = delta.readOr(null) { findByIdOpen(id) }
    private fun findByIdOpen(id: SurfaceId): CompactSurface? {
        var row: CompactSurface? = null; if (!delta.visit(CowFragmentKind.ROW, id.value) { record -> if (record is CowRecord.Row && record.value.id == id.value) row = record.value.surface(); true }) return null
        if (row != null) return row
        var removed = false; if (!delta.visit(CowFragmentKind.ID_TOMBSTONE, id.value) { record -> if (record is CowRecord.Tombstone && record.key == id.value) removed = true; true }) return null
        return if (removed) null else base.findById(id)
    }
    override fun findByVoxel(voxel: Voxel): CompactSurface? = delta.readOr(null) { findByVoxelOpen(voxel) }

    override fun findByIdBounded(
        id: SurfaceId,
        maximumPageReads: Long,
        maximumBytesRead: Long,
    ): CanonicalBoundedReadResult<CompactSurface?> {
        val budget = CowBudget(maximumPageReads, maximumBytesRead)
        if (maximumPageReads < 0L || maximumBytesRead < 0L) return budget.refused(CanonicalBoundedReadRefusal.LIMIT_EXHAUSTED)
        var row: CompactSurface? = null
        val rows = delta.visitBounded(
            CowFragmentKind.ROW, id.value,
            budget.remainingPageReads(), budget.remainingBytesRead(),
        ) { record ->
            if (record is CowRecord.Row && record.value.id == id.value) row = record.value.surface()
            true
        }
        budget.refusal(rows)?.let { return budget.refused(it) }
        if (row != null) return budget.complete(row)

        var removed = false
        val tombstones = delta.visitBounded(
            CowFragmentKind.ID_TOMBSTONE, id.value,
            budget.remainingPageReads(), budget.remainingBytesRead(),
        ) { record ->
            if (record is CowRecord.Tombstone && record.key == id.value) removed = true
            true
        }
        budget.refusal(tombstones)?.let { return budget.refused(it) }
        if (removed) return budget.complete(null)
        return budget.combine(
            base.findByIdBounded(id, budget.remainingPageReads(), budget.remainingBytesRead()),
        )
    }

    override fun findByVoxelBounded(
        voxel: Voxel,
        maximumPageReads: Long,
        maximumBytesRead: Long,
    ): CanonicalBoundedReadResult<CompactSurface?> {
        val budget = CowBudget(maximumPageReads, maximumBytesRead)
        if (maximumPageReads < 0L || maximumBytesRead < 0L) return budget.refused(CanonicalBoundedReadRefusal.LIMIT_EXHAUSTED)
        val key = CanonicalCowGeneration.voxelKey(voxel.x, voxel.y, voxel.z)
        val candidateIds = CowLongBuffer()
        val indexes = delta.visitBounded(
            CowFragmentKind.VOXEL_INDEX, key,
            budget.remainingPageReads(), budget.remainingBytesRead(),
        ) { record ->
            if (record is CowRecord.Index && record.x == voxel.x && record.y == voxel.y && record.z == voxel.z) {
                candidateIds.add(record.id)
            }
            true
        }
        budget.refusal(indexes)?.let { return budget.refused(it) }
        var dirty: CompactSurface? = null
        var refusal: CanonicalBoundedReadRefusal? = null
        for (index in 0 until candidateIds.size) {
            var candidate: CompactSurface? = null
            val row = delta.visitBounded(
                CowFragmentKind.ROW, candidateIds[index],
                budget.remainingPageReads(), budget.remainingBytesRead(),
            ) { record ->
                if (record is CowRecord.Row && record.value.id == candidateIds[index]) candidate = record.value.surface()
                true
            }
            refusal = budget.refusal(row)
            if (refusal != null) break
            if (candidate?.voxel == voxel) {
                dirty = candidate
                break
            }
        }
        refusal?.let { return budget.refused(it) }
        if (dirty != null) return budget.complete(dirty)

        val oldResult = budget.combine(
            base.findByVoxelBounded(voxel, budget.remainingPageReads(), budget.remainingBytesRead()),
        )
        val old = when (oldResult) {
            is CanonicalBoundedReadResult.Complete -> oldResult.value
            is CanonicalBoundedReadResult.Refused -> return oldResult
        } ?: return budget.complete(null)
        var tombstoned = false
        val tombstones = delta.visitBounded(
            CowFragmentKind.VOXEL_TOMBSTONE, key,
            budget.remainingPageReads(), budget.remainingBytesRead(),
        ) { record ->
            if (record is CowRecord.Tombstone && record.id == old.id.value &&
                record.x == voxel.x && record.y == voxel.y && record.z == voxel.z
            ) tombstoned = true
            true
        }
        budget.refusal(tombstones)?.let { return budget.refused(it) }
        if (tombstoned) return budget.complete(null)
        val current = budget.combine(
            findByIdBounded(old.id, budget.remainingPageReads(), budget.remainingBytesRead()),
        )
        return when (current) {
            is CanonicalBoundedReadResult.Complete -> budget.complete(current.value?.takeIf { it.voxel == voxel })
            is CanonicalBoundedReadResult.Refused -> current
        }
    }
    private fun findByVoxelOpen(voxel: Voxel): CompactSurface? {
        val key = CanonicalCowGeneration.voxelKey(voxel.x, voxel.y, voxel.z)
        val indexes = when (val read = delta.indexes(CowFragmentKind.VOXEL_INDEX, key)) {
            is CowLookup.Complete -> read.values
            CowLookup.Refused -> return null
        }
        indexes.filter { it.x == voxel.x && it.y == voxel.y && it.z == voxel.z }.forEach { index ->
            val row = when (val read = delta.row(index.id)) {
                is CowLookup.Complete -> read.values.singleOrNull()?.surface()
                CowLookup.Refused -> return null
            }
            if (row?.voxel == voxel) return row
        }
        val tombstones = when (val read = delta.tombstones(CowFragmentKind.VOXEL_TOMBSTONE, key)) {
            is CowLookup.Complete -> read.values
            CowLookup.Refused -> return null
        }
        val old = base.findByVoxel(voxel) ?: return null
        if (tombstones.any { it.id == old.id.value && it.x == voxel.x && it.y == voxel.y && it.z == voxel.z }) return null
        return findByIdOpen(old.id)?.takeIf { it.voxel == voxel }
    }
    override fun readPage(region: StorageRegion, page: Int, cursor: Int, limit: Int): CompactPage =
        delta.readOr(CompactPage(emptyList(), null, 0)) { readLegacyPage(region, page, cursor, limit) }
    private fun readLegacyPage(region: StorageRegion, page: Int, cursor: Int, limit: Int): CompactPage {
        if (limit !in 1..512 || cursor < 0 || page !in 0..26) return CompactPage(emptyList(), null, 0)
        var state = CowPageCursor(cut.rootHash, region, page, 0, 0, 0)
        var skipped = 0
        while (true) {
            val window = readPageWindowOpen(region, page, state, minOf(512, maxOf(limit, cursor - skipped)))
                ?: return CompactPage(emptyList(), null, 0)
            if (skipped + window.rows.size > cursor || window.nextCursor == null) {
                val start = (cursor - skipped).coerceIn(0, window.rows.size)
                val rows = window.rows.drop(start).take(limit)
                return CompactPage(rows, if (window.nextCursor != null || start + rows.size < window.rows.size) cursor + rows.size else null, window.inspectedRows)
            }
            skipped += window.rows.size
            state = window.nextCursor
        }
    }
    internal fun readPageWindow(region: StorageRegion, page: Int, cursor: CowPageCursor, limit: Int): CowMergeWindow? =
        delta.readOr<CowMergeWindow?>(null) { readPageWindowOpen(region, page, cursor, limit) }
    private fun readPageWindowOpen(region: StorageRegion, page: Int, cursor: CowPageCursor, limit: Int): CowMergeWindow? {
        if (limit !in 1..512 || page !in 0..26) return CowMergeWindow(emptyList(), null, 0)
        val key = CanonicalCowGeneration.pageKey(region, page)
        // Resolving an index record requires a keyed ROW-page validation, so
        // never resolve more dirty rows than this caller can emit. Tombstones
        // remain a fixed metadata-only window because they can all suppress
        // base rows without producing output.
        val indexWindow = when (val read = delta.indexWindow(CowFragmentKind.PAGE_INDEX, key, cursor.indexOffset, limit)) {
            is CowWindow.Complete -> read
            CowWindow.Refused -> return null
        }
        val tombstoneWindow = when (val read = delta.tombstoneWindow(CowFragmentKind.PAGE_TOMBSTONE, key, cursor.tombstoneOffset, 512)) {
            is CowWindow.Complete -> read
            CowWindow.Refused -> return null
        }
        val requested = CompactLocation(region, page)
        data class DirtyRecord(val rawOffset: Int, val surface: CompactSurface)
        data class TombstoneRecord(val rawOffset: Int, val value: CowRecord.Tombstone)
        val dirty = ArrayList<DirtyRecord>(indexWindow.values.size)
        indexWindow.values.forEachIndexed { rawOffset, index ->
            if (index.location() != requested) return@forEachIndexed
            val row = when (val read = delta.row(index.id)) {
                is CowLookup.Complete -> read.values.singleOrNull()?.surface() ?: return null
                CowLookup.Refused -> return null
            }
            if (CompactLocation(SurfaceOwnershipConfiguration(), row.voxel) != requested) return null
            dirty += DirtyRecord(rawOffset, row)
        }
        val tombstones = tombstoneWindow.values.mapIndexedNotNull { rawOffset, value ->
            TombstoneRecord(rawOffset, value).takeIf { value.location() == requested }
        }
        val basePage = base.readPage(region, page, cursor.baseCursor, 512)
        val rows = ArrayList<CompactSurface>(limit)
        var baseIndex = 0; var dirtyIndex = 0; var tombstoneIndex = 0
        fun compare(row: CompactSurface, tombstone: CowRecord.Tombstone) =
            compareSurfaceKey(row.voxel.x, row.voxel.y, row.voxel.z, row.id.value, tombstone.x, tombstone.y, tombstone.z, tombstone.id)
        while (rows.size < limit) {
            val baseRow = basePage.rows.getOrNull(baseIndex)
            val dirtyRow = dirty.getOrNull(dirtyIndex)?.surface
            val tombstone = tombstones.getOrNull(tombstoneIndex)?.value
            if (baseRow == null && dirtyRow == null && tombstone == null) break
            val smallest = listOfNotNull(baseRow, dirtyRow).minWithOrNull(SURFACE_ORDER)
            if (tombstone != null && (smallest == null || compare(smallest, tombstone) >= 0)) {
                val matchesBase = baseRow != null && compare(baseRow, tombstone) == 0
                if (matchesBase) baseIndex++
                tombstoneIndex++
                continue
            }
            if (baseRow != null && dirtyRow != null && SURFACE_ORDER.compare(baseRow, dirtyRow) == 0) {
                rows += dirtyRow; baseIndex++; dirtyIndex++
            } else if (dirtyRow != null && (baseRow == null || SURFACE_ORDER.compare(dirtyRow, baseRow) < 0)) {
                rows += dirtyRow; dirtyIndex++
            } else if (baseRow != null) {
                rows += baseRow; baseIndex++
            }
        }
        val hasNext = baseIndex < basePage.rows.size || dirtyIndex < dirty.size || tombstoneIndex < tombstones.size ||
            basePage.nextCursor != null || indexWindow.nextOffset != null || tombstoneWindow.nextOffset != null
        fun nextRawOffset(consumed: Int, values: List<Int>, windowSize: Int, current: Int): Int =
            if (consumed < values.size) current + values[consumed] else current + windowSize
        val next = if (hasNext) CowPageCursor(
            cut.rootHash,
            region,
            page,
            cursor.baseCursor + baseIndex,
            nextRawOffset(dirtyIndex, dirty.map { it.rawOffset }, indexWindow.values.size, cursor.indexOffset),
            nextRawOffset(tombstoneIndex, tombstones.map { it.rawOffset }, tombstoneWindow.values.size, cursor.tombstoneOffset),
        ) else null
        return CowMergeWindow(rows, next, basePage.inspectedRows + indexWindow.values.size + tombstoneWindow.values.size)
    }
    override fun readSourceById(id: SurfaceId): CanonicalPageRead<PagedSource?> =
        delta.readOr(CanonicalPageRead.Refused(CompactCanonicalRefusal.CLOSED)) { readSourceByIdOpen(id) }
    private fun readSourceByIdOpen(id: SurfaceId): CanonicalPageRead<PagedSource?> {
        var value: PagedSource? = null; val ok = delta.visit(CowFragmentKind.SOURCE, id.value) { record -> if (record is CowRecord.Source && record.value.id == id.value) value = record.value.source(); true }
        if (!ok) return CanonicalPageRead.Refused(CompactCanonicalRefusal.CORRUPT)
        if (value != null) return CanonicalPageRead.Complete(value, 0, 0)
        // Source evidence is immutable historical truth; removing or relocating
        // a live row never tombstones its source identity.
        return base.readSourceById(id)
    }
    override fun visitSourceSupport(target: SurfaceId, cursor: SourceSupportCursor?, sink: (PagedSupport) -> Boolean): SourceSupportRead =
        delta.readOr(SourceSupportRead.Refused(CompactCanonicalRefusal.CLOSED)) { visitSourceSupportOpen(target, cursor, sink) }
    private fun visitSourceSupportOpen(target: SurfaceId, cursor: SourceSupportCursor?, sink: (PagedSupport) -> Boolean): SourceSupportRead {
        if (cursor != null && (cursor.rootHash != cut.rootHash || cursor.target != target)) return SourceSupportRead.Refused(CompactCanonicalRefusal.STALE_CURSOR)
        if (delta.has(CowFragmentKind.SUPPORT, target.value)) return delta.visitSupport(cut.rootHash, target, cursor, sink)
        var removed = false; delta.visit(CowFragmentKind.SUPPORT_TOMBSTONE, target.value) { record -> if (record is CowRecord.Tombstone && record.key == target.value) removed = true; true }
        if (removed) return SourceSupportRead.Complete(0, null, 0, 0)
        val baseCursor = cursor?.copy(rootHash = base.cut.rootHash)
        return when (val read = base.visitSourceSupport(target, baseCursor, sink)) {
            is SourceSupportRead.Refused -> read
            is SourceSupportRead.Complete -> read.copy(nextCursor = read.nextCursor?.copy(rootHash = cut.rootHash))
        }
    }
    override fun visitLineage(source: SurfaceId, cursor: LineageCursor?, sink: (LineageEdge) -> Boolean): LineageRead =
        delta.readOr(LineageRead.Refused(CompactCanonicalRefusal.CLOSED)) { visitLineageOpen(source, cursor, sink) }

    override fun visitLineageBounded(
        source: SurfaceId,
        cursor: LineageCursor?,
        maximumPageReads: Long,
        maximumBytesRead: Long,
        sink: (LineageEdge) -> Boolean,
    ): CanonicalBoundedReadResult<LineageRead> {
        val budget = CowBudget(maximumPageReads, maximumBytesRead)
        if (maximumPageReads < 0L || maximumBytesRead < 0L) return budget.refused(CanonicalBoundedReadRefusal.LIMIT_EXHAUSTED)
        if (cursor != null && (cursor.rootHash != cut.rootHash || cursor.source != source || cursor.offset < 0)) {
            return budget.complete(LineageRead.Refused(CompactCanonicalRefusal.STALE_CURSOR))
        }
        var matching = 0
        var delivered = 0
        var stopped = false
        val offset = cursor?.offset ?: 0
        val lineage = delta.visitBounded(
            CowFragmentKind.LINEAGE, source.value,
            budget.remainingPageReads(), budget.remainingBytesRead(),
        ) { record ->
            if (record is CowRecord.Lineage && record.source == source.value) {
                matching++
                if (matching > offset) {
                    if (sink(LineageEdge(source, SurfaceId(record.target)))) {
                        delivered++
                    } else {
                        stopped = true
                        return@visitBounded false
                    }
                }
            }
            true
        }
        budget.refusal(lineage)?.let { return budget.refused(it) }
        if (matching > 0) {
            val next = if (stopped) {
                LineageCursor(cut.rootHash, source, matching - 1)
            } else null
            return budget.complete(LineageRead.Complete(delivered, next))
        }

        var tombstoned = false
        val tombstones = delta.visitBounded(
            CowFragmentKind.LINEAGE_TOMBSTONE, source.value,
            budget.remainingPageReads(), budget.remainingBytesRead(),
        ) { record ->
            if (record is CowRecord.Lineage && record.source == source.value) {
                tombstoned = true
                return@visitBounded false
            }
            true
        }
        budget.refusal(tombstones)?.let { return budget.refused(it) }
        if (tombstoned) return budget.complete(LineageRead.Complete(0, null))

        val baseCursor = cursor?.copy(rootHash = base.cut.rootHash)
        val baseResult = base.visitLineageBounded(
            source, baseCursor, budget.remainingPageReads(), budget.remainingBytesRead(), sink,
        )
        return when (baseResult) {
            is CanonicalBoundedReadResult.Complete -> {
                val value = when (val read = baseResult.value) {
                    is LineageRead.Refused -> read
                    is LineageRead.Complete -> read.copy(nextCursor = read.nextCursor?.copy(rootHash = cut.rootHash))
                }
                if (budget.add(baseResult.work)) budget.complete(value)
                else budget.refused(CanonicalBoundedReadRefusal.CANONICAL_READ_FAILURE)
            }
            is CanonicalBoundedReadResult.Refused -> budget.combine(baseResult)
        }
    }
    private fun visitLineageOpen(source: SurfaceId, cursor: LineageCursor?, sink: (LineageEdge) -> Boolean): LineageRead {
        if (cursor != null && (cursor.rootHash != cut.rootHash || cursor.source != source || cursor.offset < 0))
            return LineageRead.Refused(CompactCanonicalRefusal.STALE_CURSOR)
        var matching = 0; var delivered = 0; var resume: Int? = null
        val validLineage = delta.visit(CowFragmentKind.LINEAGE, source.value) { record ->
            if (record is CowRecord.Lineage && record.source == source.value) {
                if (matching++ >= (cursor?.offset ?: 0) && resume == null) {
                    if (sink(LineageEdge(source, SurfaceId(record.target)))) delivered++ else resume = matching - 1
                }
            }
            true
        }
        if (!validLineage) return LineageRead.Refused(CompactCanonicalRefusal.CORRUPT)
        if (matching > 0) {
            return LineageRead.Complete(delivered, resume?.let { LineageCursor(cut.rootHash, source, it) })
        }
        var tombstoned = false
        delta.visit(CowFragmentKind.LINEAGE_TOMBSTONE, source.value) { record ->
            if (record is CowRecord.Lineage && record.source == source.value) tombstoned = true
            true
        }
        if (tombstoned) return LineageRead.Complete(0, null)
        val baseCursor = cursor?.copy(rootHash = base.cut.rootHash)
        return when (val read = base.visitLineage(source, baseCursor, sink)) {
            is LineageRead.Refused -> read
            is LineageRead.Complete -> read.copy(nextCursor = read.nextCursor?.copy(rootHash = cut.rootHash))
        }
    }
    override fun retainedMemoryReceipt() = delta.readOr<CompactRetainedMemoryReceipt?>(null) { base.retainedMemoryReceipt() }
        ?: error("COW generation is closed")
    override fun allocatedStorageReceipt() = delta.readOr<CompactStorageReceipt?>(null) { base.allocatedStorageReceipt() }
        ?: error("COW generation is closed")
    override fun readWorkReceipt() = delta.readOr<CanonicalReadWork?>(null) {
        val baseWork = base.readWorkReceipt()
        val cowWork = delta.readWorkReceipt()
        CanonicalReadWork(
            baseWork.directLookups,
            Math.addExact(baseWork.pageReads, cowWork.pages),
            Math.addExact(baseWork.inspectedRows, cowWork.records),
            Math.addExact(baseWork.bytesRead, cowWork.bytes),
        )
    }
        ?: error("COW generation is closed")
    override fun close() = Unit
}

private class RandomAccessReader(file: File, offset: Long) : AutoCloseable {
    private val input = java.io.RandomAccessFile(file, "r")
    init { input.seek(offset) }
    fun readFully(bytes: ByteArray) = input.readFully(bytes)
    override fun close() = input.close()
}

private class CountingSink : java.io.OutputStream() {
    var count = 0L
    override fun write(value: Int) { count++ }
    override fun write(bytes: ByteArray, offset: Int, length: Int) { count += length }
}

private fun writeChecked(file: File, failDuringWrite: Boolean = false, body: (DataOutputStream) -> Unit) {
    file.parentFile?.mkdirs()
    val digest = MessageDigest.getInstance("SHA-256")
    FileOutputStream(file).use { raw ->
        val digestOut = java.security.DigestOutputStream(raw, digest)
        val out = DataOutputStream(digestOut)
        body(out); out.flush()
        if (failDuringWrite) error("fault")
        digestOut.on(false)
        raw.write(digest.digest()); raw.flush(); raw.fd.sync()
    }
}
private fun checkedInput(file: File): DataInputStream {
    require(file.isFile && file.length() >= 32)
    val bodyLength = file.length() - 32L
    val digest = MessageDigest.getInstance("SHA-256")
    FileInputStream(file).use { input ->
        val scratch = ByteArray(PreparedIntentVisitorResources.STREAMING_SCRATCH_BYTES)
        var remaining = bodyLength
        while (remaining > 0) {
            val count = input.read(scratch, 0, minOf(scratch.size.toLong(), remaining).toInt())
            require(count > 0); digest.update(scratch, 0, count); remaining -= count
        }
        require(input.readNBytes(32).contentEquals(digest.digest()) && input.read() == -1)
    }
    return DataInputStream(BufferedInputStream(BoundedInputStream(FileInputStream(file), 0, bodyLength), PreparedIntentVisitorResources.STREAMING_SCRATCH_BYTES))
}
private fun ByteArray.hex() = joinToString("") { "%02x".format(it) }
