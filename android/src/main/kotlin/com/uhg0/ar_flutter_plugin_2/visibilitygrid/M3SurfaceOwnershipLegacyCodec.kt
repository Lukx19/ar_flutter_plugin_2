package com.uhg0.ar_flutter_plugin_2.visibilitygrid

import java.io.DataInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FilterInputStream
import java.io.InputStream
import java.io.RandomAccessFile
import java.security.MessageDigest

/**
 * Validated v1-v5 authority with resident primitive columns and re-openable historical cursors.
 * No historical source/support graph is retained during migration.
 */
internal class M3LegacyCanonicalState(
    val group: M3SurfaceGroup,
    val nextHighWater: Long,
    val resident: M3CompactResident,
    val sourceCount: Int,
    val supportCount: Int,
    val lineageCount: Int,
    val geometryRevision: Long,
    val lineageRevision: Long,
    val baseline: M3CommittedEmptyBaseline?,
    val sourceHash: ByteArray,
    private val sourceCursor: ((M3PagedSource) -> Unit) -> Unit,
    private val supportCursor: ((Long, M3PagedSource) -> Unit) -> Unit,
    private val sourceLookup: (Long) -> M3PagedSource?,
) {
    fun visitSources(visitor: (M3PagedSource) -> Unit) = sourceCursor(visitor)

    fun visitSupports(visitor: (target: Long, source: M3PagedSource) -> Unit) =
        supportCursor(visitor)

    fun sourceById(id: Long): M3PagedSource? = sourceLookup(id)
}

/** Bounded, decoder-only bridge for immutable v1-v5 files. */
internal object M3SurfaceOwnershipLegacyCodec {
    private const val SNAPSHOT_MAGIC = 0x4d33534f
    private const val LEDGER_MAGIC = 0x4d33524c
    private const val LEDGER_RECORD_BYTES = 192L
    private const val SOURCE_RECORD_BYTES = 60L
    private const val CHECKSUM_BYTES = 32L
    private const val SCRATCH_BYTES = 65_536

    fun readValidated(
        group: M3SurfaceGroup,
        directory: File,
        configuration: M3SurfaceOwnershipConfiguration,
    ): M3LegacyCanonicalState =
        try {
            val prefix = m3PageSha256(group.value.encodeToByteArray()).hex()
            val snapshot = File(directory, "m3-surface-$prefix.snapshot")
            val ledger = File(directory, "m3-surface-$prefix.ledger")
            val ledgerHigh = validateLedger(ledger, group)
            if (!snapshot.exists()) {
                require(ledgerHigh == 1L)
                return emptyState(group, ledger, snapshot, configuration)
            }
            verifySnapshotChecksum(snapshot)
            decodeSnapshot(snapshot, ledger, group, ledgerHigh, configuration)
        } catch (failure: M3RestoreFailure) {
            throw failure
        } catch (_: Exception) {
            throw M3RestoreFailure(M3SurfaceOwnershipRestoreRefusal.CORRUPT)
        }

    private fun decodeSnapshot(
        snapshot: File,
        ledger: File,
        group: M3SurfaceGroup,
        ledgerHigh: Long,
        configuration: M3SurfaceOwnershipConfiguration,
    ): M3LegacyCanonicalState {
        var version = 0
        var nextHigh = 0L
        var geometry = 0L
        var lineageRevision = 0L
        var supportEntries = 0
        var supportRecords = 0
        var supportOffset = 0L
        var sourceCount = 0
        var sourceOffset = 0L
        var baseline: M3CommittedEmptyBaseline? = null
        val rowIds = IntArray(configuration.surfaceCapacity)
        val rowX = IntArray(configuration.surfaceCapacity)
        val rowY = IntArray(configuration.surfaceCapacity)
        val rowZ = IntArray(configuration.surfaceCapacity)
        val rowNormal = ShortArray(configuration.surfaceCapacity)
        val rowConfidence = ByteArray(configuration.surfaceCapacity)
        val rowOffsets = LongArray(configuration.surfaceCapacity)
        val lineageSource = IntArray(configuration.lineageCapacity)
        val lineageTarget = IntArray(configuration.lineageCapacity)
        var rowCount = 0
        var lineageCount = 0

        snapshotInput(snapshot).use { counted ->
            val data = DataInputStream(counted)
            require(data.readInt() == SNAPSHOT_MAGIC)
            version = bounded(data.readInt(), 1, 5)
            nextHigh = data.readLong()
            require(nextHigh in 1..ledgerHigh)
            rowCount = bounded(data.readInt(), 0, configuration.surfaceCapacity)
            repeat(rowCount) { index ->
                rowOffsets[index] = counted.position
                val id = data.readLong()
                val rowGroup = data.readUTF()
                val x = data.readInt()
                val y = data.readInt()
                val z = data.readInt()
                val region = M3StorageRegion(data.readInt(), data.readInt(), data.readInt())
                val page = data.readInt()
                val normal = data.readInt()
                val confidence = data.readInt()
                val fingerprint = ByteArray(32).also(data::readFully)
                require(
                    id in 1 until nextHigh &&
                        rowGroup == group.value &&
                        normal in 0..0xffff &&
                        confidence in 0..255 &&
                        (normal ushr 8) != 0x80 &&
                        (normal and 0xff) != 0x80
                )
                val location = m3CompactLocation(configuration, M3Voxel(x, y, z))
                require(location != null && location.region == region && location.page == page)
                require(index == 0 || unsignedCompare(unsigned(rowIds[index - 1]), id) < 0)
                require(fingerprint.size == 32)
                rowIds[index] = id.toInt()
                rowX[index] = x
                rowY[index] = y
                rowZ[index] = z
                rowNormal[index] = normal.toShort()
                rowConfidence[index] = confidence.toByte()
            }
            val receiptCount = bounded(data.readInt(), 0, configuration.receiptCapacity)
            repeat(receiptCount) {
                data.skipFully(64)
                val owners = bounded(data.readInt(), 0, configuration.surfaceCapacity)
                repeat(owners) { require(rowIndex(rowIds, rowCount, data.readLong()) >= 0) }
                val receiptHigh = data.readLong()
                val live = data.readInt()
                val resultRows = data.readInt()
                val allocated = data.readInt()
                require(receiptHigh in 1..ledgerHigh && live in 0..configuration.surfaceCapacity)
                require(resultRows in 0..configuration.surfaceCapacity && allocated >= 0)
            }
            if (version == 1) {
                sourceCount = rowCount
                require(data.read() == -1) {
                    "snapshot trailing bytes at ${counted.position}/${snapshot.length() - CHECKSUM_BYTES}"
                }
            } else {
                geometry = data.readLong()
                lineageRevision = data.readLong()
                require(geometry >= 0 && lineageRevision >= 0)
                supportEntries = bounded(data.readInt(), 0, configuration.surfaceCapacity)
                supportOffset = counted.position
                var previousTarget = 0L
                repeat(supportEntries) { entry ->
                    val target = data.readLong()
                    val count = bounded(data.readInt(), 1, configuration.lineageCapacity)
                    require(rowIndex(rowIds, rowCount, target) >= 0)
                    require(entry == 0 || unsignedCompare(previousTarget, target) < 0)
                    previousTarget = target
                    var previousSource = 0L
                    repeat(count) { index ->
                        val source = data.readLong()
                        require(source in 1 until ledgerHigh)
                        require(index == 0 || unsignedCompare(previousSource, source) < 0)
                        previousSource = source
                    }
                    supportRecords = Math.addExact(supportRecords, count)
                }
                require(supportEntries == rowCount)
                require(
                    supportRecords <= configuration.surfaceCapacity + configuration.lineageCapacity
                )
                if (version >= 3) {
                    sourceCount =
                        bounded(
                            data.readInt(),
                            0,
                            configuration.surfaceCapacity + configuration.lineageCapacity,
                        )
                    sourceOffset = counted.position
                    var previous = 0L
                    repeat(sourceCount) { index ->
                        val source = readSource(data)
                        require(source.id.value in 1 until ledgerHigh)
                        require(index == 0 || unsignedCompare(previous, source.id.value) < 0)
                        previous = source.id.value
                    }
                } else {
                    sourceCount = rowCount
                }
                lineageCount = bounded(data.readInt(), 0, configuration.lineageCapacity)
                repeat(lineageCount) { index ->
                    val source = data.readLong()
                    val target = data.readLong()
                    require(source in 1 until ledgerHigh && target in 1 until ledgerHigh)
                    require(
                        index == 0 ||
                            unsignedComparePair(
                                unsigned(lineageSource[index - 1]),
                                unsigned(lineageTarget[index - 1]),
                                source,
                                target,
                            ) < 0
                    )
                    lineageSource[index] = source.toInt()
                    lineageTarget[index] = target.toInt()
                }
                skipCanonicalReceipts(
                    data,
                    version,
                    configuration,
                    group,
                    nextHigh,
                    geometry,
                    lineageRevision,
                )
                if (version >= 5 && data.readBoolean()) {
                    baseline =
                        M3CommittedEmptyBaseline(
                            data.readUTF(),
                            data.readUTF(),
                            data.readLong(),
                            data.readLong(),
                            data.readLong(),
                        )
                }
                require(data.read() == -1) {
                    "snapshot trailing bytes at ${counted.position}/${snapshot.length() - CHECKSUM_BYTES}"
                }
            }
        }

        val pageOrder = IntArray(configuration.surfaceCapacity) { it }
        pageOrder.sortIndices(rowCount) { a, b ->
            compareVoxel(rowX[a], rowY[a], rowZ[a], rowX[b], rowY[b], rowZ[b])
        }
        repeat(rowCount - 1) { index ->
            val left = pageOrder[index]
            val right = pageOrder[index + 1]
            require(
                compareVoxel(
                    rowX[left], rowY[left], rowZ[left],
                    rowX[right], rowY[right], rowZ[right],
                ) < 0
            )
        }
        val resident =
            M3CompactResident(
                rowCount,
                rowIds,
                rowX,
                rowY,
                rowZ,
                rowNormal,
                rowConfidence,
                pageOrder,
                lineageSource,
                lineageTarget,
            )
        val explicitSources = version >= 3
        val finalSourceOffset = sourceOffset
        val finalSupportOffset = supportOffset
        val finalSourceCount = sourceCount
        val finalSupportEntries = supportEntries
        val sourceLookup: (Long) -> M3PagedSource? = { id ->
            if (explicitSources)
                sourceAt(snapshot, finalSourceOffset, finalSourceCount, id)
            else rowSource(snapshot, rowOffsets, resident, id)
        }
        val supportCursor: ((Long, M3PagedSource) -> Unit) -> Unit = { visitor ->
            if (version == 1) {
                repeat(rowCount) { index ->
                    val id = unsigned(rowIds[index])
                    visitor(id, requireNotNull(rowSource(snapshot, rowOffsets, resident, id)))
                }
            } else {
                RandomAccessFile(snapshot, "r").use { sourceReader ->
                    var cachedSourceIndex = -1
                    var cachedSource: M3PagedSource? = null
                    at(snapshot, finalSupportOffset).use { data ->
                        repeat(finalSupportEntries) {
                            val target = data.readLong()
                            val count = data.readInt()
                            repeat(count) {
                                val sourceId = data.readLong()
                                val source =
                                    if (explicitSources) {
                                        if (cachedSource?.id?.value == sourceId) cachedSource
                                        else {
                                            var sourceIndex = -1
                                            val adjacent = cachedSourceIndex + 1
                                            if (adjacent in 0 until finalSourceCount) {
                                                sourceReader.seek(
                                                    finalSourceOffset + adjacent * SOURCE_RECORD_BYTES
                                                )
                                                if (sourceReader.readLong() == sourceId)
                                                    sourceIndex = adjacent
                                            }
                                            if (sourceIndex < 0)
                                                sourceIndex =
                                                    sourceIndex(
                                                        sourceReader,
                                                        finalSourceOffset,
                                                        finalSourceCount,
                                                        sourceId,
                                                    )
                                            if (sourceIndex < 0) null
                                            else {
                                                cachedSourceIndex = sourceIndex
                                                sourceReader.seek(
                                                    finalSourceOffset +
                                                        sourceIndex * SOURCE_RECORD_BYTES
                                                )
                                                readSource(sourceReader.dataInput()).also {
                                                    cachedSource = it
                                                }
                                            }
                                        }
                                    }
                                    else rowSource(snapshot, rowOffsets, resident, sourceId)
                                visitor(target, requireNotNull(source))
                            }
                        }
                    }
                }
            }
        }
        // A second pass binds every support ID to exact source authority before any v6 write.
        supportCursor { _, _ -> }
        val sourceCursor: ((M3PagedSource) -> Unit) -> Unit = { visitor ->
            if (explicitSources) {
                at(snapshot, finalSourceOffset).use { data ->
                    repeat(finalSourceCount) { visitor(readSource(data)) }
                }
            } else {
                repeat(rowCount) {
                    visitor(
                        requireNotNull(
                            rowSource(snapshot, rowOffsets, resident, unsigned(rowIds[it]))
                        )
                    )
                }
            }
        }
        return M3LegacyCanonicalState(
            group,
            nextHigh,
            resident,
            sourceCount,
            if (version == 1) rowCount else supportRecords,
            lineageCount,
            geometry,
            lineageRevision,
            baseline,
            authorityHash(ledger, snapshot),
            sourceCursor,
            supportCursor,
            sourceLookup,
        )
    }

    private fun validateLedger(file: File, group: M3SurfaceGroup): Long {
        if (!file.exists()) return 1L
        require(file.length() % LEDGER_RECORD_BYTES == 0L)
        var revision = 0L
        var high = 1L
        var previous = ByteArray(32)
        DataInputStream(FileInputStream(file)).use { data ->
            repeat((file.length() / LEDGER_RECORD_BYTES).toInt()) {
                require(data.readInt() == LEDGER_MAGIC && data.readInt() == 1)
                val recordRevision = data.readLong()
                val start = data.readLong()
                val end = data.readLong()
                val groupHash = ByteArray(32).also(data::readFully)
                val commandHash = ByteArray(32).also(data::readFully)
                val fingerprint = ByteArray(32).also(data::readFully)
                val previousHash = ByteArray(32).also(data::readFully)
                val hash = ByteArray(32).also(data::readFully)
                val body = java.io.ByteArrayOutputStream(168).also { raw ->
                    java.io.DataOutputStream(raw).use { out ->
                        out.writeInt(LEDGER_MAGIC)
                        out.writeInt(1)
                        out.writeLong(recordRevision)
                        out.writeLong(start)
                        out.writeLong(end)
                        out.write(groupHash)
                        out.write(commandHash)
                        out.write(fingerprint)
                        out.write(previousHash)
                    }
                }.toByteArray()
                require(
                    recordRevision == ++revision &&
                        start == high &&
                        end > start &&
                        end <= 0x1_0000_0000L &&
                        groupHash.contentEquals(group.hash) &&
                        previousHash.contentEquals(previous) &&
                        hash.contentEquals(m3PageSha256(body)) &&
                        !hash.contentEquals(ByteArray(32))
                )
                high = end
                previous = hash
            }
            require(data.read() == -1)
        }
        return high
    }

    private fun skipCanonicalReceipts(
        data: DataInputStream,
        version: Int,
        configuration: M3SurfaceOwnershipConfiguration,
        group: M3SurfaceGroup,
        nextHigh: Long,
        geometryRevision: Long,
        lineageRevision: Long,
    ) {
        val count = bounded(data.readInt(), 0, configuration.transactionCapacity)
        var journalBytes = 0L
        repeat(count) {
            data.skipFully(64)
            if (version >= 4) {
                val bytes = bounded(data.readInt(), 0, configuration.changeJournalByteCapacity)
                journalBytes = Math.addExact(journalBytes, 68L + bytes)
                validateCanonicalReceipt(
                    data,
                    bytes,
                    configuration,
                    group,
                    nextHigh,
                    geometryRevision,
                    lineageRevision,
                )
            } else {
                data.readUTF()
                bounded(data.readInt(), 0, M3CanonicalOperation.entries.lastIndex)
                skipOwners(data, bounded(data.readInt(), 0, configuration.surfaceCapacity))
                data.skipFully(bounded(data.readInt(), 0, configuration.surfaceCapacity) * 8)
                data.skipFully(bounded(data.readInt(), 0, configuration.lineageCapacity) * 16)
                data.skipFully(28)
                if (version >= 3) {
                    repeat(bounded(data.readInt(), 0, configuration.lineageCapacity)) {
                        readSource(data)
                    }
                    data.skipFully(
                        bounded(data.readInt(), 0, configuration.changeJournalByteCapacity)
                    )
                }
            }
        }
        require(journalBytes <= configuration.changeJournalByteCapacity)
    }

    private fun validateCanonicalReceipt(
        source: DataInputStream,
        bytes: Int,
        configuration: M3SurfaceOwnershipConfiguration,
        group: M3SurfaceGroup,
        nextHigh: Long,
        geometryRevision: Long,
        lineageRevision: Long,
    ) {
        val body = DataInputStream(M3LegacyLimitedInputStream(source, bytes.toLong()))
        require(body.readInt() == 0x4d334352 && body.readInt() == 1)
        require(body.readUTF() == group.value)
        require(body.readUTF().isNotEmpty())
        bounded(body.readInt(), 0, M3CanonicalOperation.entries.lastIndex)
        val geometry = body.readLong()
        val lineage = body.readLong()
        val high = body.readLong()
        require(
            geometry in 0..geometryRevision &&
                lineage in 0..lineageRevision &&
                high in 1..nextHigh
        )
        bounded(body.readInt(), 0, configuration.surfaceCapacity)
        repeat(bounded(body.readInt(), 0, configuration.surfaceCapacity)) {
            val id = body.readLong()
            require(id in 1 until nextHigh && body.readUTF() == group.value)
            val voxel = M3Voxel(body.readInt(), body.readInt(), body.readInt())
            val region = M3StorageRegion(body.readInt(), body.readInt(), body.readInt())
            val page = body.readInt()
            val normal = body.readInt()
            val confidence = body.readInt()
            require(
                m3CompactLocation(configuration, voxel) == M3CompactLocation(region, page) &&
                    normal in 0..0xffff &&
                    confidence in 0..255 &&
                    (normal ushr 8) != 0x80 &&
                    (normal and 0xff) != 0x80
            )
            body.skipFully(32)
        }
        repeat(bounded(body.readInt(), 0, configuration.surfaceCapacity)) {
            require(body.readLong() in 1 until nextHigh)
        }
        repeat(bounded(body.readInt(), 0, configuration.lineageCapacity)) {
            require(body.readLong() in 1 until nextHigh)
            require(body.readLong() in 1 until nextHigh)
        }
        repeat(bounded(body.readInt(), 0, configuration.lineageCapacity)) {
            readSource(body).also { require(it.id.value in 1 until nextHigh) }
        }
        require(body.read() == -1)
    }

    private fun skipOwners(data: DataInputStream, count: Int) {
        repeat(count) {
            data.readLong()
            data.readUTF()
            data.skipFully(9 * 4 + 32)
        }
    }

    private fun readSource(data: DataInputStream): M3PagedSource {
        val id = data.readLong()
        val voxel = M3Voxel(data.readInt(), data.readInt(), data.readInt())
        val normal = data.readInt()
        val confidence = data.readInt()
        val fingerprint = ByteArray(32).also(data::readFully)
        require(
            id in 1..0xffff_ffffL &&
                normal in 0..0xffff &&
                confidence in 0..255 &&
                (normal ushr 8) != 0x80 &&
                (normal and 0xff) != 0x80
        )
        return M3PagedSource(
            M3SurfaceId(id),
            voxel,
            normal,
            confidence,
            M3CanonicalReceiptBytes(fingerprint),
        )
    }

    private fun sourceAt(file: File, offset: Long, count: Int, id: Long): M3PagedSource? {
        RandomAccessFile(file, "r").use { reader ->
            return sourceAt(reader, offset, count, id)
        }
    }

    private fun sourceAt(
        reader: RandomAccessFile,
        offset: Long,
        count: Int,
        id: Long,
    ): M3PagedSource? {
        val index = sourceIndex(reader, offset, count, id)
        if (index < 0) return null
        reader.seek(offset + index * SOURCE_RECORD_BYTES)
        return readSource(reader.dataInput())
    }

    private fun sourceIndex(
        reader: RandomAccessFile,
        offset: Long,
        count: Int,
        id: Long,
    ): Int {
        var low = 0
        var high = count - 1
        while (low <= high) {
            val middle = (low + high) ushr 1
            val recordOffset = offset + middle * SOURCE_RECORD_BYTES
            reader.seek(recordOffset)
            val candidateId = reader.readLong()
            when (unsignedCompare(candidateId, id)) {
                in Int.MIN_VALUE until 0 -> low = middle + 1
                in 1..Int.MAX_VALUE -> high = middle - 1
                else -> return middle
            }
        }
        return -1
    }

    private fun rowSource(
        file: File,
        offsets: LongArray,
        resident: M3CompactResident,
        id: Long,
    ): M3PagedSource? {
        val index = rowIndex(resident.rowId, resident.rows, id)
        if (index < 0) return null
        at(file, offsets[index]).use { data ->
            val rowId = data.readLong()
            data.readUTF()
            val voxel = M3Voxel(data.readInt(), data.readInt(), data.readInt())
            data.skipFully(4 * 4)
            val normal = data.readInt()
            val confidence = data.readInt()
            val fingerprint = ByteArray(32).also(data::readFully)
            require(rowId == id)
            return M3PagedSource(
                M3SurfaceId(id),
                voxel,
                normal,
                confidence,
                M3CanonicalReceiptBytes(fingerprint),
            )
        }
    }

    private fun rowIndex(ids: IntArray, count: Int, id: Long): Int {
        var low = 0
        var high = count - 1
        while (low <= high) {
            val middle = (low + high) ushr 1
            when (unsignedCompare(unsigned(ids[middle]), id)) {
                in Int.MIN_VALUE until 0 -> low = middle + 1
                in 1..Int.MAX_VALUE -> high = middle - 1
                else -> return middle
            }
        }
        return -1
    }

    private fun emptyState(
        group: M3SurfaceGroup,
        ledger: File,
        snapshot: File,
        configuration: M3SurfaceOwnershipConfiguration,
    ) =
        M3LegacyCanonicalState(
            group,
            1,
            M3CompactResident(
                0,
                IntArray(configuration.surfaceCapacity),
                IntArray(configuration.surfaceCapacity),
                IntArray(configuration.surfaceCapacity),
                IntArray(configuration.surfaceCapacity),
                ShortArray(configuration.surfaceCapacity),
                ByteArray(configuration.surfaceCapacity),
                IntArray(configuration.surfaceCapacity),
                IntArray(configuration.lineageCapacity),
                IntArray(configuration.lineageCapacity),
            ),
            0,
            0,
            0,
            0,
            0,
            configuration.seededEmptyBaseline,
            authorityHash(ledger, snapshot),
            {},
            {},
            { null },
        )

    private fun authorityHash(vararg files: File): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(SCRATCH_BYTES)
        files.filter(File::exists).forEach { file ->
            FileInputStream(file).use { input ->
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    digest.update(buffer, 0, count)
                }
            }
        }
        return digest.digest()
    }

    private fun verifySnapshotChecksum(file: File) {
        require(file.length() >= CHECKSUM_BYTES)
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(SCRATCH_BYTES)
        FileInputStream(file).use { input ->
            var remaining = file.length() - CHECKSUM_BYTES
            while (remaining > 0) {
                val count = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                require(count > 0)
                digest.update(buffer, 0, count)
                remaining -= count
            }
            val expected = ByteArray(32).also(DataInputStream(input)::readFully)
            require(digest.digest().contentEquals(expected) && input.read() == -1)
        }
    }

    private fun snapshotInput(file: File): M3CountingInputStream =
        M3CountingInputStream(
            M3LegacyLimitedInputStream(FileInputStream(file), file.length() - CHECKSUM_BYTES)
        )

    private fun at(file: File, offset: Long): DataInputStream {
        val input = FileInputStream(file)
        input.channel.position(offset)
        return DataInputStream(input)
    }

    private fun bounded(value: Int, minimum: Int, maximum: Int): Int {
        require(value in minimum..maximum)
        return value
    }
}

private class M3CountingInputStream(input: InputStream) : FilterInputStream(input) {
    var position = 0L
        private set

    override fun read(): Int = super.read().also { if (it >= 0) position++ }

    override fun read(bytes: ByteArray, offset: Int, length: Int): Int =
        super.read(bytes, offset, length).also { if (it > 0) position += it }

    override fun skip(bytes: Long): Long = super.skip(bytes).also { position += it }
}

private class M3LegacyLimitedInputStream(input: InputStream, private var remaining: Long) :
    FilterInputStream(input) {
    override fun read(): Int {
        if (remaining == 0L) return -1
        return super.read().also { if (it >= 0) remaining-- }
    }

    override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
        if (remaining == 0L) return -1
        return super.read(bytes, offset, minOf(length.toLong(), remaining).toInt()).also {
            if (it > 0) remaining -= it
        }
    }

    override fun skip(bytes: Long): Long {
        if (remaining == 0L) return 0L
        return super.skip(minOf(bytes, remaining)).also { remaining -= it }
    }
}

private fun DataInputStream.skipFully(bytes: Int) {
    require(bytes >= 0)
    var remaining = bytes
    while (remaining > 0) {
        val skipped = skipBytes(remaining)
        require(skipped > 0)
        remaining -= skipped
    }
}

private fun RandomAccessFile.dataInput(): DataInputStream =
    DataInputStream(
        object : InputStream() {
            override fun read(): Int = this@dataInput.read()

            override fun read(bytes: ByteArray, offset: Int, length: Int): Int =
                this@dataInput.read(bytes, offset, length)
        }
    )

private fun ByteArray.hex(): String = joinToString("") { "%02x".format(it) }
