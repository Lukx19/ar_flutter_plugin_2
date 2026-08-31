package com.uhg0.ar_flutter_plugin_2.visibilitygrid

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.io.FilterInputStream
import java.io.InputStream
import java.security.MessageDigest
import java.security.DigestOutputStream

internal data class M3CompactRoot(
    val high: Long,
    val geometry: Long,
    val lineageRevision: Long,
    val rows: Int,
    val sources: Int,
    val supports: Int,
    val lineage: Int,
    val baseline: M3CommittedEmptyBaseline?,
    val sourceHash: ByteArray,
    val residentHash: ByteArray,
    val directoryHash: ByteArray,
    val pageCount: Int,
)

internal data class M3CompactResident(
    val rows: Int,
    val rowId: IntArray,
    val rowX: IntArray,
    val rowY: IntArray,
    val rowZ: IntArray,
    val rowNormal: ShortArray,
    val rowConfidence: ByteArray,
    val idOrder: IntArray,
    val voxelOrder: IntArray,
    val pageOrder: IntArray,
    val lineageSource: IntArray,
    val lineageTarget: IntArray,
)

/** Primitive directory authority retained by the open store and filled directly by its decoder. */
internal class M3CompactDirectory(capacity: Int) {
    val kind = ByteArray(capacity)
    val ordinal = IntArray(capacity)
    val minimum = IntArray(capacity)
    val maximum = IntArray(capacity)
    val offset = LongArray(capacity)
    val length = IntArray(capacity)
    val count = ShortArray(capacity)
    val hash = ByteArray(capacity * 32)
    var size: Int = 0
        private set

    fun append(entry: M3CanonicalDirectoryEntry) {
        require(size < kind.size && entry.ordinal == size)
        val index = size++
        kind[index] = entry.kind.wire.toByte()
        ordinal[index] = entry.ordinal
        minimum[index] = entry.minimumKey.toInt()
        maximum[index] = entry.maximumKey.toInt()
        offset[index] = entry.offset
        length[index] = entry.length
        count[index] = entry.count.toShort()
        entry.hash.copyInto(hash, index * 32)
    }

    fun entry(index: Int) = M3CanonicalDirectoryEntry(
        M3CanonicalPageKind.entries.first { it.wire == kind[index].toInt() },
        ordinal[index],
        unsigned(minimum[index]),
        unsigned(maximum[index]),
        offset[index],
        length[index],
        count[index].toInt() and 0xffff,
        hash.copyOfRange(index * 32, index * 32 + 32),
    )

    val retainedBytes: Long
        get() = kind.size + ordinal.size * 4L + minimum.size * 4L + maximum.size * 4L +
            offset.size * 8L + length.size * 4L + count.size * 2L + hash.size
}

/** Compact `(region,page) -> sorted row range` index; no sparse query scans live capacity. */
internal class M3CompactPageRanges(capacity: Int) {
    val regionX = IntArray(capacity)
    val regionY = IntArray(capacity)
    val regionZ = IntArray(capacity)
    val page = ByteArray(capacity)
    val start = IntArray(capacity)
    val count = IntArray(capacity)
    var size = 0
        private set

    fun append(location: M3CompactLocation, first: Int, rows: Int) {
        require(size < regionX.size && rows > 0)
        val index = size++
        regionX[index] = location.region.x
        regionY[index] = location.region.y
        regionZ[index] = location.region.z
        page[index] = location.page.toByte()
        start[index] = first
        count[index] = rows
    }

    fun find(region: M3StorageRegion, targetPage: Int): Int {
        var low = 0
        var high = size - 1
        while (low <= high) {
            val middle = (low + high) ushr 1
            val compared = compareLocation(
                regionX[middle], regionY[middle], regionZ[middle], page[middle].toInt(),
                region.x, region.y, region.z, targetPage,
            )
            when {
                compared < 0 -> low = middle + 1
                compared > 0 -> high = middle - 1
                else -> return middle
            }
        }
        return -1
    }

    val retainedBytes: Long
        get() = regionX.size * 21L
}

/** Streaming v6 root/resident/directory codec. Scratch never exceeds [SCRATCH_BYTES]. */
internal object M3CompactCanonicalFormat {
    const val PROFILE = "android-v2-fixed-8g64-v1"
    const val SCRATCH_BYTES = 65_536
    private const val VERSION = 6
    private const val ROOT_MAGIC = 0x4d335236
    private const val RESIDENT_MAGIC = 0x4d335253
    private const val DIRECTORY_MAGIC = 0x4d334436
    private const val CHECKSUM_BYTES = 32L

    fun residentFileBytes(legacy: M3LegacyCanonicalState): Long =
        12L + legacy.resident.rows * 19L + 4L + legacy.lineageCount * 8L + CHECKSUM_BYTES

    fun directoryFileBytes(pageCount: Int): Long = 12L + pageCount * 59L + CHECKSUM_BYTES

    fun rootFileBytes(legacy: M3LegacyCanonicalState): Long {
        var bytes = 8L + utfBytes(legacy.group.value) + utfBytes(PROFILE)
        bytes += 5L * 4L + 3L * 8L + 5L * 4L + 1L + 3L * 32L + CHECKSUM_BYTES
        legacy.baseline?.let {
            bytes += utfBytes(it.bindingIdentity) + utfBytes(it.groupIdentity) + 3L * 8L
        }
        return bytes
    }

    fun writeResident(
        file: File,
        legacy: M3LegacyCanonicalState,
        fault: M3CompactCanonicalMigrationFault? = null,
    ): ByteArray {
        writeChecked(
            file,
            fault == M3CompactCanonicalMigrationFault.DURING_RESIDENT_WRITE,
            fault == M3CompactCanonicalMigrationFault.BEFORE_RESIDENT_SYNC,
        ) { out ->
            out.writeInt(RESIDENT_MAGIC)
            out.writeInt(VERSION)
            out.writeInt(legacy.resident.rows)
            repeat(legacy.resident.rows) { order ->
                val index = legacy.resident.idOrder[order]
                out.writeInt(legacy.resident.rowId[index])
                out.writeInt(legacy.resident.rowX[index])
                out.writeInt(legacy.resident.rowY[index])
                out.writeInt(legacy.resident.rowZ[index])
                out.writeShort(legacy.resident.rowNormal[index].toInt())
                out.writeByte(legacy.resident.rowConfidence[index].toInt())
            }
            out.writeInt(legacy.lineageCount)
            repeat(legacy.lineageCount) { index ->
                out.writeInt(legacy.resident.lineageSource[index])
                out.writeInt(legacy.resident.lineageTarget[index])
            }
        }
        return hashFile(file)
    }

    fun readResident(
        file: File,
        configuration: M3SurfaceOwnershipConfiguration,
        root: M3CompactRoot,
    ): M3CompactResident {
        verifyChecksum(file)
        checkedInput(file).use { input ->
            require(input.readInt() == RESIDENT_MAGIC && input.readInt() == VERSION)
            val rows = bounded(input.readInt(), configuration.surfaceCapacity)
            require(rows == root.rows)
            val ids = IntArray(configuration.surfaceCapacity)
            val x = IntArray(configuration.surfaceCapacity)
            val y = IntArray(configuration.surfaceCapacity)
            val z = IntArray(configuration.surfaceCapacity)
            val normals = ShortArray(configuration.surfaceCapacity)
            val confidence = ByteArray(configuration.surfaceCapacity)
            repeat(rows) { index ->
                ids[index] = input.readInt(); x[index] = input.readInt(); y[index] = input.readInt(); z[index] = input.readInt()
                normals[index] = input.readShort(); confidence[index] = input.readByte()
                require(ids[index] != 0)
                require(index == 0 || unsignedCompare(unsigned(ids[index - 1]), unsigned(ids[index])) < 0)
                require(m3CompactLocation(configuration, M3Voxel(x[index], y[index], z[index])) != null)
            }
            val pageOrder = IntArray(configuration.surfaceCapacity) { it }
            val idOrder = IntArray(configuration.surfaceCapacity) { it }
            val voxelOrder = IntArray(configuration.surfaceCapacity) { it }
            voxelOrder.sortIndices(rows) { a, b ->
                compareVoxel(x[a], y[a], z[a], x[b], y[b], z[b])
            }
            pageOrder.sortIndices(rows) { a, b ->
                compareLocationThenVoxel(configuration, x, y, z, a, b)
            }
            repeat(rows - 1) { index ->
                val left = voxelOrder[index]
                val right = voxelOrder[index + 1]
                require(compareVoxel(x[left], y[left], z[left], x[right], y[right], z[right]) < 0)
            }
            val lineage = bounded(input.readInt(), configuration.lineageCapacity)
            require(lineage == root.lineage)
            val sources = IntArray(configuration.lineageCapacity)
            val targets = IntArray(configuration.lineageCapacity)
            repeat(lineage) { index ->
                sources[index] = input.readInt(); targets[index] = input.readInt()
                require(sources[index] != 0 && targets[index] != 0)
                require(index == 0 || unsignedComparePair(
                    unsigned(sources[index - 1]), unsigned(targets[index - 1]),
                    unsigned(sources[index]), unsigned(targets[index]),
                ) < 0)
            }
            require(input.read() == -1)
            return M3CompactResident(
                rows, ids, x, y, z, normals, confidence, idOrder, voxelOrder, pageOrder,
                sources, targets,
            )
        }
    }

    fun writeDirectory(
        file: File,
        directory: M3CompactDirectory,
        fault: M3CompactCanonicalMigrationFault? = null,
    ): ByteArray {
        writeChecked(
            file,
            fault == M3CompactCanonicalMigrationFault.DURING_DIRECTORY_WRITE,
            fault == M3CompactCanonicalMigrationFault.BEFORE_DIRECTORY_SYNC,
        ) { out ->
            out.writeInt(DIRECTORY_MAGIC); out.writeInt(VERSION); out.writeInt(directory.size)
            repeat(directory.size) { index ->
                out.writeByte(directory.kind[index].toInt()); out.writeInt(directory.ordinal[index])
                out.writeInt(directory.minimum[index]); out.writeInt(directory.maximum[index])
                out.writeLong(directory.offset[index]); out.writeInt(directory.length[index])
                out.writeShort(directory.count[index].toInt()); out.write(directory.hash, index * 32, 32)
            }
        }
        require(file.length() <= 1_048_576L)
        return hashFile(file)
    }

    fun readDirectory(file: File, expected: Int): M3CompactDirectory {
        verifyChecksum(file)
        require(file.length() <= 1_048_576L)
        checkedInput(file).use { input ->
            require(input.readInt() == DIRECTORY_MAGIC && input.readInt() == VERSION)
            val count = bounded(input.readInt(), 4_096)
            require(count == expected)
            val directory = M3CompactDirectory(count)
            repeat(count) { index ->
                val wire = input.readUnsignedByte()
                val kind = M3CanonicalPageKind.entries.firstOrNull { it.wire == wire }
                    ?: throw IllegalArgumentException("unknown page kind")
                val ordinal = input.readInt(); val minimum = unsigned(input.readInt()); val maximum = unsigned(input.readInt())
                val offset = input.readLong(); val length = input.readInt(); val records = input.readUnsignedShort()
                val hash = ByteArray(32).also(input::readFully)
                require(ordinal == index && length == M3CanonicalPageCache.PAGE_BYTES)
                require(offset == index.toLong() * length && records in 1..M3CanonicalPageCache.MAX_RECORDS)
                require(unsignedCompare(minimum, maximum) <= 0)
                directory.append(M3CanonicalDirectoryEntry(kind, ordinal, minimum, maximum, offset, length, records, hash))
            }
            require(input.read() == -1)
            return directory
        }
    }

    fun writeRoot(
        file: File,
        legacy: M3LegacyCanonicalState,
        residentHash: ByteArray,
        directoryHash: ByteArray,
        pageCount: Int,
        fault: M3CompactCanonicalMigrationFault? = null,
    ): ByteArray {
        writeChecked(
            file,
            fault == M3CompactCanonicalMigrationFault.DURING_ROOT_WRITE,
            fault == M3CompactCanonicalMigrationFault.BEFORE_ROOT_SYNC,
        ) { out ->
            out.writeInt(ROOT_MAGIC); out.writeInt(VERSION); out.writeUTF(legacy.group.value); out.writeUTF(PROFILE)
            out.writeInt(100_000); out.writeInt(200_000); out.writeInt(300_000)
            out.writeInt(M3CanonicalPageCache.PAGE_BYTES); out.writeInt(M3CanonicalPageCache.CACHE_PAGES)
            out.writeLong(legacy.nextHighWater); out.writeLong(legacy.geometryRevision); out.writeLong(legacy.lineageRevision)
            out.writeInt(legacy.resident.rows); out.writeInt(legacy.sourceCount)
            out.writeInt(legacy.supportCount); out.writeInt(legacy.lineageCount); out.writeInt(pageCount)
            out.writeBoolean(legacy.baseline != null)
            legacy.baseline?.let { baseline ->
                out.writeUTF(baseline.bindingIdentity); out.writeUTF(baseline.groupIdentity)
                out.writeLong(baseline.transactionId); out.writeLong(baseline.geometryRevision); out.writeLong(baseline.lineageRevision)
            }
            out.write(legacy.sourceHash); out.write(residentHash); out.write(directoryHash)
        }
        require(file.length() <= SCRATCH_BYTES)
        return hashFile(file)
    }

    fun readRoot(
        file: File,
        group: M3SurfaceGroup,
        configuration: M3SurfaceOwnershipConfiguration,
    ): Pair<M3CompactRoot, ByteArray> {
        verifyChecksum(file)
        require(file.length() <= SCRATCH_BYTES)
        checkedInput(file).use { input ->
            require(input.readInt() == ROOT_MAGIC && input.readInt() == VERSION)
            require(input.readUTF() == group.value && input.readUTF() == PROFILE)
            require(input.readInt() == configuration.surfaceCapacity)
            require(input.readInt() == configuration.lineageCapacity)
            require(input.readInt() == configuration.surfaceCapacity + configuration.lineageCapacity)
            require(input.readInt() == M3CanonicalPageCache.PAGE_BYTES && input.readInt() == M3CanonicalPageCache.CACHE_PAGES)
            val high = input.readLong(); val geometry = input.readLong(); val lineageRevision = input.readLong()
            val rows = bounded(input.readInt(), configuration.surfaceCapacity)
            val sources = bounded(input.readInt(), configuration.surfaceCapacity + configuration.lineageCapacity)
            val supports = bounded(input.readInt(), configuration.surfaceCapacity + configuration.lineageCapacity)
            val lineage = bounded(input.readInt(), configuration.lineageCapacity)
            val pages = bounded(input.readInt(), 4_096)
            val baseline = if (input.readBoolean()) M3CommittedEmptyBaseline(
                input.readUTF(), input.readUTF(), input.readLong(), input.readLong(), input.readLong(),
            ) else null
            val sourceHash = ByteArray(32).also(input::readFully)
            val residentHash = ByteArray(32).also(input::readFully)
            val directoryHash = ByteArray(32).also(input::readFully)
            require(input.read() == -1 && high in 1..0x1_0000_0000L && geometry >= 0 && lineageRevision >= 0)
            return M3CompactRoot(
                high, geometry, lineageRevision, rows, sources, supports, lineage, baseline,
                sourceHash, residentHash, directoryHash, pages,
            ) to hashFile(file)
        }
    }

    fun hashFile(file: File): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(SCRATCH_BYTES)
        FileInputStream(file).use { input ->
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest()
    }

    private fun writeChecked(
        file: File,
        failDuringWrite: Boolean = false,
        failBeforeSync: Boolean = false,
        body: (DataOutputStream) -> Unit,
    ) {
        val digest = MessageDigest.getInstance("SHA-256")
        RandomAccessFile(file, "rw").use { random ->
            random.seek(0)
            val output = FileOutputStream(random.fd)
            val digestOutput = DigestOutputStream(output, digest)
            val data = DataOutputStream(digestOutput)
            body(data)
            data.flush()
            if (failDuringWrite) error("fault")
            digestOutput.on(false)
            output.write(digest.digest())
            output.flush()
            if (failBeforeSync) error("fault")
            output.fd.sync()
            require(output.channel.position() == random.length()) { "preallocated file size mismatch" }
        }
    }

    /** Exact byte count used by DataOutputStream.writeUTF, including its two-byte length prefix. */
    private fun utfBytes(value: String): Long {
        val bytes = modifiedUtf8Length(value)
        require(bytes <= 65_535)
        return bytes + 2L
    }

    private fun verifyChecksum(file: File) {
        require(file.isFile && file.length() >= CHECKSUM_BYTES)
        val digest = MessageDigest.getInstance("SHA-256")
        val bodyBytes = file.length() - CHECKSUM_BYTES
        val buffer = ByteArray(SCRATCH_BYTES)
        FileInputStream(file).use { input ->
            var remaining = bodyBytes
            while (remaining > 0) {
                val count = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                require(count > 0); digest.update(buffer, 0, count); remaining -= count
            }
            val expected = ByteArray(32); DataInputStream(input).readFully(expected)
            require(digest.digest().contentEquals(expected) && input.read() == -1)
        }
    }

    private fun checkedInput(file: File): DataInputStream = DataInputStream(
        M3LimitedInputStream(FileInputStream(file), file.length() - CHECKSUM_BYTES),
    )

    private fun bounded(value: Int, maximum: Int): Int {
        require(value in 0..maximum)
        return value
    }
}

private class M3LimitedInputStream(input: InputStream, private var remaining: Long) : FilterInputStream(input) {
    override fun read(): Int {
        if (remaining == 0L) return -1
        val value = super.read()
        if (value >= 0) remaining--
        return value
    }
    override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
        if (remaining == 0L) return -1
        val count = super.read(bytes, offset, minOf(length.toLong(), remaining).toInt())
        if (count > 0) remaining -= count
        return count
    }
}
