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
 * An immutable, deliberately unreferenced M3 delta.  It is not a schema-5
 * authority and this type has no operation which can make it one.  #123 owns
 * selecting a complete [M3MutableSemanticRoot].
 */
internal class M3CanonicalCowGeneration private constructor(
    val directory: File,
    val root: M3MutableSemanticRoot,
    private val entries: List<M3CowDirectoryEntry>,
    private val storage: M3CowStorageReceipt,
) : AutoCloseable {
    private var closed = false

    @Synchronized
    override fun close() { closed = true }

    @Synchronized
    fun storageReceipt() = storage
    @Synchronized
    internal fun withAllocatedStorage(bytes: Long): M3CanonicalCowGeneration {
        check(!closed)
        return M3CanonicalCowGeneration(directory, root, entries, storage.copy(allocatedBytes = bytes))
    }

    /** Holds the generation lifecycle lock across a complete overlay operation. */
    @Synchronized
    internal fun <T> readOr(closedResult: T, operation: () -> T): T =
        if (closed) closedResult else operation()

    /** Private lookup view.  It has no publication capability. */
    @Synchronized
    fun overlay(base: M3CanonicalStateView): M3CanonicalStateView {
        require(!closed && base.cut == root.baseCut)
        return M3CowOverlay(base, this)
    }

    /** Root-bound private page cursor; #114's public cursor contract is unchanged. */
    @Synchronized
    fun readPage(
        base: M3CanonicalStateView,
        region: M3StorageRegion,
        page: Int,
        cursor: M3CowPageCursor?,
        limit: Int,
    ): M3CowPageRead {
        if (closed || base.cut != root.baseCut) return M3CowPageRead.Refused(M3CompactCanonicalRefusal.CLOSED)
        val target = root.targetCut()
        if (cursor != null && (cursor.rootHash != target.rootHash || cursor.region != region || cursor.page != page))
            return M3CowPageRead.Refused(M3CompactCanonicalRefusal.STALE_CURSOR)
        val result = M3CowOverlay(base, this).readPage(region, page, cursor?.offset ?: 0, limit)
        return M3CowPageRead.Complete(result.rows, result.nextCursor?.let { M3CowPageCursor(target.rootHash, region, page, it) }, result.inspectedRows)
    }

    @Synchronized
    private fun records(kind: M3CowFragmentKind, sink: (M3CowRecord) -> Boolean): Boolean {
        if (closed) return false
        return entries.asSequence().filter { it.kind == kind }.all { entry ->
            val file = File(directory, entry.file)
            readPage(file, entry, sink)
        }
    }

    @Synchronized
    private fun records(kind: M3CowFragmentKind, key: Long, sink: (M3CowRecord) -> Boolean): Boolean {
        if (closed) return false
        return entries.asSequence().filter { it.kind == kind && java.lang.Long.compareUnsigned(key, it.minimumKey) >= 0 && java.lang.Long.compareUnsigned(key, it.maximumKey) <= 0 }
            .all { readPage(File(directory, it.file), it, sink) }
    }

    private fun readPage(file: File, entry: M3CowDirectoryEntry, sink: (M3CowRecord) -> Boolean): Boolean {
        if (!file.isFile || file.length() < (entry.page.toLong() + 1) * PAGE_BYTES) return false
        val bytes = ByteArray(PAGE_BYTES)
        RandomAccessReader(file, entry.page.toLong() * PAGE_BYTES).use { input -> input.readFully(bytes) }
        if (!sha(bytes).contentEquals(entry.hash)) return false
        val input = DataInputStream(bytes.inputStream())
        if (input.readInt() != PAGE_MAGIC || input.readInt() != entry.kind.wire || input.readInt() != entry.count) return false
        var keepGoing = true
        var minimum = Long.MAX_VALUE
        var maximum = Long.MIN_VALUE
        repeat(entry.count) {
            val record = when (entry.kind) {
                M3CowFragmentKind.ROW -> M3CowRecord.Row(readRecord(input))
                M3CowFragmentKind.ID_TOMBSTONE, M3CowFragmentKind.SUPPORT_TOMBSTONE -> M3CowRecord.Tombstone(input.readLong(), 0, 0, 0, 0)
                M3CowFragmentKind.SUPPORT -> M3CowRecord.Support(input.readLong(), readRecord(input))
                M3CowFragmentKind.SOURCE -> M3CowRecord.Source(readRecord(input))
                M3CowFragmentKind.LINEAGE -> M3CowRecord.Lineage(input.readLong(), input.readLong())
                M3CowFragmentKind.ID_INDEX -> M3CowRecord.Index(input.readLong(), 0, 0, 0, 0)
                M3CowFragmentKind.VOXEL_INDEX, M3CowFragmentKind.PAGE_INDEX -> M3CowRecord.Index(0, input.readInt(), input.readInt(), input.readInt(), input.readLong())
                M3CowFragmentKind.VOXEL_TOMBSTONE, M3CowFragmentKind.PAGE_TOMBSTONE -> M3CowRecord.Tombstone(0, input.readInt(), input.readInt(), input.readInt(), input.readLong())
                M3CowFragmentKind.LINEAGE_TOMBSTONE -> M3CowRecord.Lineage(input.readLong(), input.readLong())
            }
            val key = when (record) {
                is M3CowRecord.Row -> record.value.id
                is M3CowRecord.Source -> record.value.id
                is M3CowRecord.Support -> record.target
                is M3CowRecord.Lineage -> record.source
                is M3CowRecord.Index -> if (entry.kind == M3CowFragmentKind.ID_INDEX) record.key else voxelKey(record.x, record.y, record.z)
                is M3CowRecord.Tombstone -> if (entry.kind == M3CowFragmentKind.ID_TOMBSTONE || entry.kind == M3CowFragmentKind.SUPPORT_TOMBSTONE) record.key else voxelKey(record.x, record.y, record.z)
            }
            minimum = minOf(minimum, key); maximum = maxOf(maximum, key)
            if (keepGoing) keepGoing = sink(record)
        }
        if (minimum != entry.minimumKey || maximum != entry.maximumKey) return false
        while (input.available() > 0) if (input.readUnsignedByte() != 0) return false
        return keepGoing
    }

    private fun readRecord(input: DataInputStream) = M3CowRow(
        input.readLong(), input.readInt(), input.readInt(), input.readInt(), input.readInt(), input.readInt(),
        input.readLong(), input.readLong(), input.readLong(), input.readLong(),
    )

    internal fun visit(kind: M3CowFragmentKind, sink: (M3CowRecord) -> Boolean) = records(kind, sink)
    internal fun visit(kind: M3CowFragmentKind, key: Long, sink: (M3CowRecord) -> Boolean) = records(kind, key, sink)
    @Synchronized
    internal fun has(kind: M3CowFragmentKind, key: Long) = !closed && entries.any {
        it.kind == kind && java.lang.Long.compareUnsigned(key, it.minimumKey) >= 0 && java.lang.Long.compareUnsigned(key, it.maximumKey) <= 0
    }

    @Synchronized
    internal fun visitSupport(
        rootHash: M3CanonicalReceiptBytes,
        target: M3SurfaceId,
        cursor: M3SourceSupportCursor?,
        sink: (M3PagedSupport) -> Boolean,
    ): M3SourceSupportRead {
        val candidates = entries.withIndex().filter { (_, entry) ->
            entry.kind == M3CowFragmentKind.SUPPORT &&
                java.lang.Long.compareUnsigned(target.value, entry.minimumKey) >= 0 &&
                java.lang.Long.compareUnsigned(target.value, entry.maximumKey) <= 0
        }
        if (candidates.isEmpty()) return M3SourceSupportRead.Complete(0, null, 0, 0)
        val startEntry = cursor?.ordinal ?: candidates.first().index
        var skip = cursor?.offset ?: 0
        var delivered = 0
        for ((entryIndex, entry) in candidates) {
            if (entryIndex < startEntry) continue
            if (entryIndex > startEntry) skip = 0
            var matchingOffset = 0
            var stopped = false
            var resumeOffset = 0
            val valid = readPage(File(directory, entry.file), entry) { record ->
                if (record is M3CowRecord.Support && record.target == target.value) {
                    if (matchingOffset++ >= skip && !stopped) {
                        if (sink(M3PagedSupport(target, record.source.source()))) delivered++ else { stopped = true; resumeOffset = matchingOffset - 1 }
                    }
                }
                true
            }
            if (!valid) return M3SourceSupportRead.Refused(M3CompactCanonicalRefusal.CORRUPT)
            if (stopped) return M3SourceSupportRead.Complete(delivered, M3SourceSupportCursor(rootHash, target, entryIndex, resumeOffset), candidates.size, candidates.size * PAGE_BYTES)
        }
        return M3SourceSupportRead.Complete(delivered, null, candidates.size, candidates.size * PAGE_BYTES)
    }

    companion object {
        const val PAGE_BYTES = 16_384
        const val DIRECTORY_LIMIT_BYTES = 1_048_576
        /** Fixed visitor scratch plus one page for every writer kind. */
        val FIXED_PHASE_BYTES = M3PreparedIntentVisitorResources.STREAMING_SCRATCH_BYTES +
            M3CowFragmentKind.entries.size * PAGE_BYTES.toLong()
        internal const val PAGE_MAGIC = 0x4d334350
        private const val ROOT_FILE = "root.m3cow"
        private const val DIRECTORY_FILE = "directory.m3cow"
        internal const val CURRENT_UNACKED_FILE = "current-unacked.intent"

        internal fun generationDirectory(parent: File, identity: M3CowGenerationIdentity) =
            File(parent, "m3-cow-${identity.hash.toByteArray().hex()}")

        internal fun open(directory: File, expected: M3CowGenerationIdentity? = null): M3CanonicalCowGeneration? = try {
            val rootFile = File(directory, ROOT_FILE)
            require(rootFile.length() <= DIRECTORY_LIMIT_BYTES && File(directory, DIRECTORY_FILE).length() <= DIRECTORY_LIMIT_BYTES)
            val root = M3MutableSemanticRoot.read(rootFile) ?: return null
            val entries = root.manifest
            require(M3CowDirectoryEntry.matches(File(directory, DIRECTORY_FILE), entries))
            require(entries.sumOf { it.encodedBytes() } <= DIRECTORY_LIMIT_BYTES)
            require(64L + entries.size * 128L <= DIRECTORY_LIMIT_BYTES)
            require(validateCurrent(File(directory, CURRENT_UNACKED_FILE), root.current))
            val identity = M3CowGenerationIdentity.from(root)
            require(expected == null || expected == identity)
            require(entries == entries.sortedWith(compareBy<M3CowDirectoryEntry> { it.kind.wire }.thenBy { it.page }))
            entries.forEach { entry ->
                require(entry.count in 1..entry.kind.recordsPerPage)
                require(entry.page >= 0 && entry.file == entry.kind.file)
                require(entry.offset == entry.page.toLong() * PAGE_BYTES && entry.length == PAGE_BYTES)
            }
            entries.groupBy { it.kind }.forEach { (kind, pages) ->
                require(pages.indices.all { index -> pages[index].page == index })
                require(File(directory, kind.file).length() == pages.size.toLong() * PAGE_BYTES)
            }
            val expectedNames = entries.mapTo(mutableSetOf()) { it.file } + setOf(ROOT_FILE, DIRECTORY_FILE, CURRENT_UNACKED_FILE)
            require(directory.listFiles().orEmpty().mapTo(mutableSetOf()) { it.name } == expectedNames)
            val storage = M3CowStorageReceipt(
                directory.listFiles().orEmpty().sumOf { allocated(it) }, entries.size, FIXED_PHASE_BYTES + 64L + entries.size * 128L,
            )
            require(storage.phasePeakBytes <= DIRECTORY_LIMIT_BYTES)
            M3CanonicalCowGeneration(directory, root, entries, storage).also { generation ->
                M3CowFragmentKind.entries.forEach { kind -> require(generation.records(kind) { true }) }
            }
        } catch (_: Exception) { null }

        internal fun rootFile(directory: File) = File(directory, ROOT_FILE)
        internal fun directoryFile(directory: File) = File(directory, DIRECTORY_FILE)
        internal fun sha(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)
        internal fun voxelKey(x: Int, y: Int, z: Int) = (x.toLong() shl 32) xor ((y.toLong() and 0xffffL) shl 16) xor (z.toLong() and 0xffffL)
        private fun validateCurrent(file: File, expected: M3PreparedIntentCurrentReceipt): Boolean = try {
            if (!file.isFile || file.length() != expected.length) return false
            val digest = MessageDigest.getInstance("SHA-256")
            FileInputStream(file).use { input ->
                val scratch = ByteArray(M3PreparedIntentVisitorResources.STREAMING_SCRATCH_BYTES)
                while (true) { val count = input.read(scratch); if (count < 0) break; digest.update(scratch, 0, count) }
            }
            M3CanonicalReceiptBytes(digest.digest()) == expected.hash
        } catch (_: Exception) { false }
        internal fun allocated(file: File): Long = if (file.isDirectory) file.listFiles().orEmpty().sumOf(::allocated) else file.length()
        internal fun sameManifest(left: List<M3CowDirectoryEntry>, right: List<M3CowDirectoryEntry>) = left.size == right.size && left.indices.all { index ->
            val a = left[index]; val b = right[index]
            a.kind == b.kind && a.page == b.page && a.minimumKey == b.minimumKey && a.maximumKey == b.maximumKey && a.offset == b.offset && a.length == b.length && a.count == b.count && a.hash.contentEquals(b.hash)
        }
        internal fun sync(directory: File) {
            if (!System.getProperty("os.name").orEmpty().startsWith("Windows", true))
                FileChannel.open(directory.toPath(), StandardOpenOption.READ).use { it.force(true) }
        }
    }
}

internal enum class M3CowFragmentKind(val wire: Int, val file: String, val recordBytes: Int) {
    ROW(1, "rows.pages", 60), ID_INDEX(2, "id-index.pages", 8),
    VOXEL_INDEX(3, "voxel-index.pages", 20), PAGE_INDEX(4, "page-index.pages", 20),
    SOURCE(5, "source.pages", 60), SUPPORT(6, "support.pages", 68), LINEAGE(7, "lineage.pages", 16),
    ID_TOMBSTONE(8, "id-tombstones.pages", 8),
    VOXEL_TOMBSTONE(9, "voxel-tombstones.pages", 20),
    PAGE_TOMBSTONE(10, "page-tombstones.pages", 20),
    SUPPORT_TOMBSTONE(11, "support-tombstones.pages", 8),
    LINEAGE_TOMBSTONE(12, "lineage-tombstones.pages", 16);
    val recordsPerPage get() = (M3CanonicalCowGeneration.PAGE_BYTES - 12) / recordBytes
}

internal data class M3CowRow(val id: Long, val x: Int, val y: Int, val z: Int, val normal: Int, val confidence: Int, val f0: Long, val f1: Long, val f2: Long, val f3: Long) {
    fun source() = M3PagedSource(M3SurfaceId(id), M3Voxel(x, y, z), normal, confidence, M3CanonicalReceiptBytes(words()))
    fun surface() = M3CompactSurface(M3SurfaceId(id), M3Voxel(x, y, z), normal, confidence)
    fun words() = ByteArray(32).also { bytes ->
        java.nio.ByteBuffer.wrap(bytes).putLong(f0).putLong(f1).putLong(f2).putLong(f3)
    }
}

internal sealed interface M3CowRecord {
    data class Row(val value: M3CowRow) : M3CowRecord
    data class Tombstone(val key: Long, val x: Int, val y: Int, val z: Int, val id: Long) : M3CowRecord
    data class Support(val target: Long, val source: M3CowRow) : M3CowRecord
    data class Source(val value: M3CowRow) : M3CowRecord
    data class Lineage(val source: Long, val target: Long) : M3CowRecord
    data class Index(val key: Long, val x: Int, val y: Int, val z: Int, val id: Long) : M3CowRecord
}

internal data class M3CowDirectoryEntry(val kind: M3CowFragmentKind, val page: Int, val minimumKey: Long, val maximumKey: Long, val offset: Long, val length: Int, val count: Int, val hash: ByteArray) {
    val file get() = kind.file
    fun encodedBytes() = 1 + 4 + 8 + 8 + 8 + 4 + 4 + 32
    fun write(out: DataOutputStream) { out.writeByte(kind.wire); out.writeInt(page); out.writeLong(minimumKey); out.writeLong(maximumKey); out.writeLong(offset); out.writeInt(length); out.writeInt(count); out.write(hash) }
    companion object {
        private const val MAGIC = 0x4d334344
        fun write(file: File, entries: List<M3CowDirectoryEntry>, failDuringWrite: Boolean = false) {
            require(entries.sumOf { it.encodedBytes() } <= M3CanonicalCowGeneration.DIRECTORY_LIMIT_BYTES)
            writeChecked(file, failDuringWrite) { out -> out.writeInt(MAGIC); out.writeInt(entries.size); entries.forEach { it.write(out) } }
        }
        fun encodedFileBytes(entries: List<M3CowDirectoryEntry>) = 8L + entries.sumOf { it.encodedBytes().toLong() } + 32L
        fun read(file: File): List<M3CowDirectoryEntry>? = try {
            checkedInput(file).use { input ->
                require(input.readInt() == MAGIC); val count = input.readInt(); require(count in 0..20_000)
                List(count) {
                    val wire = input.readUnsignedByte()
                    val kind = requireNotNull(M3CowFragmentKind.entries.firstOrNull { item -> item.wire == wire }) { "unknown directory kind $wire" }
                    val page = input.readInt(); val minimum = input.readLong(); val maximum = input.readLong(); val offset = input.readLong(); val length = input.readInt(); val records = input.readInt(); val hash = ByteArray(32).also(input::readFully)
                    require(minimum <= maximum && offset == page.toLong() * M3CanonicalCowGeneration.PAGE_BYTES && length == M3CanonicalCowGeneration.PAGE_BYTES)
                    M3CowDirectoryEntry(kind, page, minimum, maximum, offset, length, records, hash)
                }.also { require(input.read() == -1) }
            }
        } catch (_: Exception) { null }
        fun matches(file: File, expected: List<M3CowDirectoryEntry>): Boolean = try {
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

/** Complete, self-validating root for a candidate only.  It names #114's root hash. */
internal data class M3MutableSemanticRoot(
    val baseCut: M3CompactCanonicalCut,
    val commandId: String,
    val commandKind: M3PreparedMutationKind,
    val commandHash: M3CanonicalReceiptBytes,
    val commandFingerprint: M3CanonicalReceiptBytes,
    val targetHighWater: Long,
    val targetLive: Int,
    val targetSource: Int,
    val targetSupport: Int,
    val targetLineage: Int,
    val targetGeometry: Long,
    val targetLineageRevision: Long,
    val current: M3PreparedIntentCurrentReceipt,
    val manifest: List<M3CowDirectoryEntry>,
) {
    fun targetCut() = M3CompactCanonicalCut(baseCut.group, baseCut.profile, targetGeometry, targetLineageRevision,
        targetHighWater, targetLive, targetSource, targetSupport, targetLineage, baseCut.seededEmptyBaseline,
        M3CowGenerationIdentity.from(this).hash, sourceIdentity())
    private fun sourceIdentity(): M3CanonicalReceiptBytes {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(baseCut.sourceHash.toByteArray())
        manifest.filter { it.kind in setOf(M3CowFragmentKind.SOURCE, M3CowFragmentKind.SUPPORT, M3CowFragmentKind.SUPPORT_TOMBSTONE) }
            .forEach { entry -> digest.update(entry.kind.wire.toByte()); digest.update(entry.hash) }
        return M3CanonicalReceiptBytes(digest.digest())
    }
    fun write(file: File, failDuringWrite: Boolean = false) = writeChecked(file, failDuringWrite, ::writeBody)
    fun encodedBytes(): Long = M3CountingSink().also { sink -> DataOutputStream(sink).use(::writeBody) }.count + 32L
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
        fun read(file: File): M3MutableSemanticRoot? = try {
            checkedInput(file).use { input ->
                require(input.readInt() == ROOT_MAGIC); val group = M3SurfaceGroup(input.readUTF()); val profile = input.readUTF()
                val geometry = input.readLong(); val lineage = input.readLong(); val high = input.readLong(); val live = input.readInt(); val sources = input.readInt(); val supports = input.readInt(); val edges = input.readInt()
                val baseline = if (input.readBoolean()) M3CommittedEmptyBaseline(
                    input.readUTF(), input.readUTF(), input.readLong(), input.readLong(), input.readLong(),
                ) else null
                val root = M3CanonicalReceiptBytes(ByteArray(32).also(input::readFully)); val source = M3CanonicalReceiptBytes(ByteArray(32).also(input::readFully))
                val commandId = input.readUTF(); val kindOrdinal = input.readInt(); require(kindOrdinal in M3PreparedMutationKind.entries.indices)
                val command = M3CanonicalReceiptBytes(ByteArray(32).also(input::readFully)); val fingerprint = M3CanonicalReceiptBytes(ByteArray(32).also(input::readFully))
                val targetHigh = input.readLong(); val targetLive = input.readInt(); val targetSource = input.readInt(); val targetSupport = input.readInt(); val targetLineage = input.readInt(); val targetGeometry = input.readLong(); val targetLineageRevision = input.readLong()
                val current = M3PreparedIntentCurrentReceipt(input.readLong(), M3CanonicalReceiptBytes(ByteArray(32).also(input::readFully)))
                val count = input.readInt(); require(count in 0..20_000); val manifest = List(count) {
                    val wire = input.readUnsignedByte(); val kind = requireNotNull(M3CowFragmentKind.entries.firstOrNull { it.wire == wire }) { "unknown root kind $wire" }; val page = input.readInt(); val minimum = input.readLong(); val maximum = input.readLong(); val offset = input.readLong(); val length = input.readInt(); val records = input.readInt(); val hash = ByteArray(32).also(input::readFully); M3CowDirectoryEntry(kind, page, minimum, maximum, offset, length, records, hash)
                }
                require(input.read() == -1 && profile == M3CompactCanonicalStore.PROFILE && targetHigh >= high && targetLive in 0..100_000 && targetSource in 0..300_000 && targetSupport in 0..300_000 && targetLineage in 0..200_000 && targetGeometry >= geometry && targetLineageRevision >= lineage)
                require(commandId.isNotBlank() && modifiedUtf8Length(commandId) <= 256)
                M3MutableSemanticRoot(M3CompactCanonicalCut(group, profile, geometry, lineage, high, live, sources, supports, edges, baseline, root, source), commandId, M3PreparedMutationKind.entries[kindOrdinal], command, fingerprint, targetHigh, targetLive, targetSource, targetSupport, targetLineage, targetGeometry, targetLineageRevision, current, manifest)
            }
        } catch (_: Exception) { null }
    }
}

internal data class M3CowGenerationIdentity(val hash: M3CanonicalReceiptBytes) {
    companion object {
        fun from(root: M3MutableSemanticRoot): M3CowGenerationIdentity {
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
            return M3CowGenerationIdentity(M3CanonicalReceiptBytes(digest.digest()))
        }
    }
}

internal data class M3CowStorageReceipt(val allocatedBytes: Long, val directoryEntries: Int, val phasePeakBytes: Long)
internal data class M3CowPageCursor(val rootHash: M3CanonicalReceiptBytes, val region: M3StorageRegion, val page: Int, val offset: Int)
internal sealed interface M3CowPageRead {
    data class Complete(val rows: List<M3CompactSurface>, val nextCursor: M3CowPageCursor?, val inspectedRows: Int) : M3CowPageRead
    data class Refused(val reason: M3CompactCanonicalRefusal) : M3CowPageRead
}

private class M3CowOverlay(private val base: M3CanonicalStateView, private val delta: M3CanonicalCowGeneration) : M3CanonicalStateView {
    override val cut get() = delta.root.targetCut()
    override fun findById(id: M3SurfaceId): M3CompactSurface? = delta.readOr(null) { findByIdOpen(id) }
    private fun findByIdOpen(id: M3SurfaceId): M3CompactSurface? {
        var row: M3CompactSurface? = null; if (!delta.visit(M3CowFragmentKind.ROW, id.value) { record -> if (record is M3CowRecord.Row && record.value.id == id.value) row = record.value.surface(); true }) return null
        if (row != null) return row
        var removed = false; if (!delta.visit(M3CowFragmentKind.ID_TOMBSTONE, id.value) { record -> if (record is M3CowRecord.Tombstone && record.key == id.value) removed = true; true }) return null
        return if (removed) null else base.findById(id)
    }
    override fun findByVoxel(voxel: M3Voxel): M3CompactSurface? = delta.readOr(null) { findByVoxelOpen(voxel) }
    private fun findByVoxelOpen(voxel: M3Voxel): M3CompactSurface? {
        var dirty: M3CompactSurface? = null; if (!delta.visit(M3CowFragmentKind.ROW) { record -> if (record is M3CowRecord.Row && record.value.surface().voxel == voxel) dirty = record.value.surface(); true }) return null
        if (dirty != null) return dirty
        val old = base.findByVoxel(voxel) ?: return null
        return findByIdOpen(old.id)?.takeIf { it.voxel == voxel }
    }
    override fun readPage(region: M3StorageRegion, page: Int, cursor: Int, limit: Int): M3CompactPage =
        delta.readOr(M3CompactPage(emptyList(), null, 0)) { readPageOpen(region, page, cursor, limit) }
    private fun readPageOpen(region: M3StorageRegion, page: Int, cursor: Int, limit: Int): M3CompactPage {
        if (limit !in 1..512 || cursor < 0 || page !in 0..26) return M3CompactPage(emptyList(), null, 0)
        // The base page is the only base population touched.  A dirty row wins
        // by id and an old location is suppressed by findById's tombstone path.
        val merged = ArrayList<M3CompactSurface>()
        var baseCursor = 0
        while (true) {
            val basePage = base.readPage(region, page, baseCursor, 512)
            basePage.rows.forEach { row ->
                val visible = findByIdOpen(row.id)
                if (visible != null && visible.voxel == row.voxel) merged += visible
            }
            baseCursor = basePage.nextCursor ?: break
        }
        delta.visit(M3CowFragmentKind.ROW) { record ->
            if (record is M3CowRecord.Row) {
                val row = record.value.surface()
                val location = m3CompactLocation(M3SurfaceOwnershipConfiguration(), row.voxel)
                if (location?.region == region && location.page == page && merged.none { it.id == row.id }) merged += row
            }
            true
        }
        merged.sortWith(compareBy<M3CompactSurface> { it.voxel.x }.thenBy { it.voxel.y }.thenBy { it.voxel.z }.thenBy { it.id.value })
        if (cursor > merged.size) return M3CompactPage(emptyList(), null, merged.size)
        val end = minOf(merged.size, cursor + limit)
        val pageRows = merged.subList(cursor, end).toList()
        return M3CompactPage(pageRows, if (end < merged.size) end else null, merged.size)
    }
    override fun readSourceById(id: M3SurfaceId): M3CanonicalPageRead<M3PagedSource?> =
        delta.readOr(M3CanonicalPageRead.Refused(M3CompactCanonicalRefusal.CLOSED)) { readSourceByIdOpen(id) }
    private fun readSourceByIdOpen(id: M3SurfaceId): M3CanonicalPageRead<M3PagedSource?> {
        var value: M3PagedSource? = null; val ok = delta.visit(M3CowFragmentKind.SOURCE, id.value) { record -> if (record is M3CowRecord.Source && record.value.id == id.value) value = record.value.source(); true }
        if (!ok) return M3CanonicalPageRead.Refused(M3CompactCanonicalRefusal.CORRUPT)
        if (value != null) return M3CanonicalPageRead.Complete(value, 0, 0)
        // Source evidence is immutable historical truth; removing or relocating
        // a live row never tombstones its source identity.
        return base.readSourceById(id)
    }
    override fun visitSourceSupport(target: M3SurfaceId, cursor: M3SourceSupportCursor?, sink: (M3PagedSupport) -> Boolean): M3SourceSupportRead =
        delta.readOr(M3SourceSupportRead.Refused(M3CompactCanonicalRefusal.CLOSED)) { visitSourceSupportOpen(target, cursor, sink) }
    private fun visitSourceSupportOpen(target: M3SurfaceId, cursor: M3SourceSupportCursor?, sink: (M3PagedSupport) -> Boolean): M3SourceSupportRead {
        if (cursor != null && (cursor.rootHash != cut.rootHash || cursor.target != target)) return M3SourceSupportRead.Refused(M3CompactCanonicalRefusal.STALE_CURSOR)
        if (delta.has(M3CowFragmentKind.SUPPORT, target.value)) return delta.visitSupport(cut.rootHash, target, cursor, sink)
        var removed = false; delta.visit(M3CowFragmentKind.SUPPORT_TOMBSTONE, target.value) { record -> if (record is M3CowRecord.Tombstone && record.key == target.value) removed = true; true }
        if (removed) return M3SourceSupportRead.Complete(0, null, 0, 0)
        val baseCursor = cursor?.copy(rootHash = base.cut.rootHash)
        return when (val read = base.visitSourceSupport(target, baseCursor, sink)) {
            is M3SourceSupportRead.Refused -> read
            is M3SourceSupportRead.Complete -> read.copy(nextCursor = read.nextCursor?.copy(rootHash = cut.rootHash))
        }
    }
    override fun visitLineage(source: M3SurfaceId, cursor: M3LineageCursor?, sink: (M3LineageEdge) -> Boolean): M3LineageRead =
        delta.readOr(M3LineageRead.Refused(M3CompactCanonicalRefusal.CLOSED)) { visitLineageOpen(source, cursor, sink) }
    private fun visitLineageOpen(source: M3SurfaceId, cursor: M3LineageCursor?, sink: (M3LineageEdge) -> Boolean): M3LineageRead {
        if (cursor != null && (cursor.rootHash != cut.rootHash || cursor.source != source || cursor.offset < 0))
            return M3LineageRead.Refused(M3CompactCanonicalRefusal.STALE_CURSOR)
        if (delta.has(M3CowFragmentKind.LINEAGE, source.value)) {
            var matching = 0; var delivered = 0; var resume: Int? = null
            val valid = delta.visit(M3CowFragmentKind.LINEAGE, source.value) { record ->
                if (record is M3CowRecord.Lineage && record.source == source.value) {
                    if (matching++ >= (cursor?.offset ?: 0) && resume == null) {
                        if (sink(M3LineageEdge(source, M3SurfaceId(record.target)))) delivered++ else resume = matching - 1
                    }
                }
                true
            }
            if (!valid) return M3LineageRead.Refused(M3CompactCanonicalRefusal.CORRUPT)
            return M3LineageRead.Complete(delivered, resume?.let { M3LineageCursor(cut.rootHash, source, it) })
        }
        var tombstoned = false
        delta.visit(M3CowFragmentKind.LINEAGE_TOMBSTONE, source.value) { record ->
            if (record is M3CowRecord.Lineage && record.source == source.value) tombstoned = true
            true
        }
        if (tombstoned) return M3LineageRead.Complete(0, null)
        val baseCursor = cursor?.copy(rootHash = base.cut.rootHash)
        return when (val read = base.visitLineage(source, baseCursor, sink)) {
            is M3LineageRead.Refused -> read
            is M3LineageRead.Complete -> read.copy(nextCursor = read.nextCursor?.copy(rootHash = cut.rootHash))
        }
    }
    override fun retainedMemoryReceipt() = delta.readOr<M3CompactRetainedMemoryReceipt?>(null) { base.retainedMemoryReceipt() }
        ?: error("COW generation is closed")
    override fun allocatedStorageReceipt() = delta.readOr<M3CompactStorageReceipt?>(null) { base.allocatedStorageReceipt() }
        ?: error("COW generation is closed")
    override fun readWorkReceipt() = delta.readOr<M3CanonicalReadWork?>(null) { base.readWorkReceipt() }
        ?: error("COW generation is closed")
    override fun close() = Unit
}

private class RandomAccessReader(file: File, offset: Long) : AutoCloseable {
    private val input = java.io.RandomAccessFile(file, "r")
    init { input.seek(offset) }
    fun readFully(bytes: ByteArray) = input.readFully(bytes)
    override fun close() = input.close()
}

private class M3CountingSink : java.io.OutputStream() {
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
        val scratch = ByteArray(M3PreparedIntentVisitorResources.STREAMING_SCRATCH_BYTES)
        var remaining = bodyLength
        while (remaining > 0) {
            val count = input.read(scratch, 0, minOf(scratch.size.toLong(), remaining).toInt())
            require(count > 0); digest.update(scratch, 0, count); remaining -= count
        }
        require(input.readNBytes(32).contentEquals(digest.digest()) && input.read() == -1)
    }
    return DataInputStream(BufferedInputStream(M3BoundedInputStream(FileInputStream(file), 0, bodyLength), M3PreparedIntentVisitorResources.STREAMING_SCRATCH_BYTES))
}
private fun ByteArray.hex() = joinToString("") { "%02x".format(it) }
