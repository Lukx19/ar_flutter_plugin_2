package com.uhg0.ar_flutter_plugin_2.visibilitygrid

import java.io.DataInputStream
import java.io.ByteArrayInputStream
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
    private val explicitSourceIndex: M3LegacySourceIndex?,
) {
    fun visitSources(visitor: (M3PagedSource) -> Unit) = sourceCursor(visitor)

    fun visitSupports(visitor: (target: Long, source: M3PagedSource) -> Unit) =
        supportCursor(visitor)

    fun sourceById(id: Long): M3PagedSource? = sourceLookup(id)

    fun sourceIndexReceipt(): M3LegacySourceIndexReceipt? = explicitSourceIndex?.receipt()
}

internal data class M3LegacySourceIndexReceipt(
    val records: Int,
    val retainedBytes: Long,
    val buildIdReads: Long,
    val lookups: Long,
    val lookupIdReads: Long,
    val maximumLookupIdReads: Int,
)

/** One primitive ordinal column, sorted in-place by unsigned source ID. */
internal class M3LegacySourceIndex private constructor(
    private val file: File,
    private val sourceOffset: Long,
    private val order: IntArray,
    private val buildReads: Long,
) {
    private var lookups = 0L
    private var lookupReads = 0L
    private var maximumLookupReads = 0

    fun find(reader: RandomAccessFile, id: Long): M3PagedSource? {
        lookups++
        var probes = 0
        var low = 0
        var high = order.lastIndex
        while (low <= high) {
            val middle = (low + high) ushr 1
            val ordinal = order[middle]
            reader.seek(sourceOffset + ordinal * SOURCE_RECORD_BYTES)
            val candidate = reader.readLong()
            probes++
            when (unsignedCompare(candidate, id)) {
                in Int.MIN_VALUE until 0 -> low = middle + 1
                in 1..Int.MAX_VALUE -> high = middle - 1
                else -> {
                    recordLookup(probes)
                    reader.seek(sourceOffset + ordinal * SOURCE_RECORD_BYTES)
                    return readSourceRecord(reader)
                }
            }
        }
        recordLookup(probes)
        return null
    }

    fun visit(visitor: (M3PagedSource) -> Unit) {
        RandomAccessFile(file, "r").use { reader ->
            order.forEach { ordinal ->
                reader.seek(sourceOffset + ordinal * SOURCE_RECORD_BYTES)
                visitor(readSourceRecord(reader))
            }
        }
    }

    fun receipt() = M3LegacySourceIndexReceipt(
        order.size, order.size * 4L, buildReads, lookups, lookupReads, maximumLookupReads,
    )

    private fun recordLookup(probes: Int) {
        lookupReads = Math.addExact(lookupReads, probes.toLong())
        maximumLookupReads = maxOf(maximumLookupReads, probes)
    }

    companion object {
        private const val SOURCE_RECORD_BYTES = 60L

        fun build(file: File, sourceOffset: Long, count: Int): M3LegacySourceIndex {
            val order = IntArray(count) { it }
            var reads = 0L
            RandomAccessFile(file, "r").use { reader ->
                fun idAt(position: Int): Long {
                    reader.seek(sourceOffset + order[position] * SOURCE_RECORD_BYTES)
                    reads++
                    return reader.readLong()
                }

                fun sort(from: Int, until: Int, shift: Int) {
                    if (until - from <= 1 || shift < 0) return
                    val counts = IntArray(256)
                    for (position in from until until) {
                        counts[((idAt(position) ushr shift) and 0xff).toInt()]++
                    }
                    val starts = IntArray(256)
                    var cursor = from
                    repeat(256) { bucket ->
                        starts[bucket] = cursor
                        cursor += counts[bucket]
                    }
                    val next = starts.copyOf()
                    repeat(256) { bucket ->
                        val end = starts[bucket] + counts[bucket]
                        while (next[bucket] < end) {
                            val position = next[bucket]
                            val actual = ((idAt(position) ushr shift) and 0xff).toInt()
                            if (actual == bucket) next[bucket]++
                            else {
                                val target = next[actual]++
                                val swap = order[position]
                                order[position] = order[target]
                                order[target] = swap
                            }
                        }
                    }
                    if (shift > 0) repeat(256) { bucket ->
                        val size = counts[bucket]
                        if (size > 1) sort(starts[bucket], starts[bucket] + size, shift - 8)
                    }
                }

                sort(0, count, 24)
                var previous = 0L
                repeat(count) { position ->
                    val id = idAt(position)
                    require(position == 0 || unsignedCompare(previous, id) < 0)
                    previous = id
                }
            }
            return M3LegacySourceIndex(file, sourceOffset, order, reads)
        }

        private fun readSourceRecord(reader: RandomAccessFile): M3PagedSource =
            M3SurfaceOwnershipLegacyCodec.readSourceRecord(reader)
    }
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
                return emptyState(group, ledger, snapshot, ledgerHigh, configuration)
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
        var sourceCount = 0
        var sourceOffset = 0L
        var sourcesSorted = true
        var baseline: M3CommittedEmptyBaseline? = null
        var ownershipReceiptOffset = 0L
        var ownershipReceiptCount = 0
        var canonicalReceiptOffset = 0L
        var canonicalReceiptCount = 0
        val rowIds = IntArray(configuration.surfaceCapacity)
        val rowX = IntArray(configuration.surfaceCapacity)
        val rowY = IntArray(configuration.surfaceCapacity)
        val rowZ = IntArray(configuration.surfaceCapacity)
        val rowNormal = ShortArray(configuration.surfaceCapacity)
        val rowConfidence = ByteArray(configuration.surfaceCapacity)
        val rowOffsets = LongArray(configuration.surfaceCapacity)
        val supportOffsets = LongArray(configuration.surfaceCapacity) { -1L }
        val lineageSource = IntArray(configuration.lineageCapacity)
        val lineageTarget = IntArray(configuration.lineageCapacity)
        val idOrder = IntArray(configuration.surfaceCapacity) { it }
        val voxelOrder = IntArray(configuration.surfaceCapacity) { it }
        val pageOrder = IntArray(configuration.surfaceCapacity) { it }
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
                require(fingerprint.size == 32)
                rowIds[index] = id.toInt()
                rowX[index] = x
                rowY[index] = y
                rowZ[index] = z
                rowNormal[index] = normal.toShort()
                rowConfidence[index] = confidence.toByte()
            }
            idOrder.sortIndices(rowCount) { a, b ->
                unsignedCompare(unsigned(rowIds[a]), unsigned(rowIds[b]))
            }
            voxelOrder.sortIndices(rowCount) { a, b ->
                compareVoxel(rowX[a], rowY[a], rowZ[a], rowX[b], rowY[b], rowZ[b])
            }
            pageOrder.sortIndices(rowCount) { a, b ->
                compareLocationThenVoxel(configuration, rowX, rowY, rowZ, a, b)
            }
            repeat(rowCount - 1) { index ->
                require(
                    unsignedCompare(
                        unsigned(rowIds[idOrder[index]]),
                        unsigned(rowIds[idOrder[index + 1]]),
                    ) < 0
                )
                val left = voxelOrder[index]
                val right = voxelOrder[index + 1]
                require(
                    compareVoxel(
                        rowX[left], rowY[left], rowZ[left],
                        rowX[right], rowY[right], rowZ[right],
                    ) < 0
                )
            }
            ownershipReceiptOffset = counted.position
            ownershipReceiptCount = bounded(data.readInt(), 0, configuration.receiptCapacity)
            repeat(ownershipReceiptCount) {
                data.skipFully(64)
                val owners = bounded(data.readInt(), 0, configuration.surfaceCapacity)
                repeat(owners) {
                    require(rowIndex(rowIds, idOrder, rowCount, data.readLong()) >= 0)
                }
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
                repeat(supportEntries) { entry ->
                    val entryOffset = counted.position
                    val target = data.readLong()
                    val count = bounded(data.readInt(), 1, configuration.lineageCapacity)
                    val targetSlot = rowIndex(rowIds, idOrder, rowCount, target)
                    require(targetSlot >= 0 && supportOffsets[targetSlot] < 0)
                    supportOffsets[targetSlot] = entryOffset
                    var previousSource = 0L
                    repeat(count) { index ->
                        val source = data.readLong()
                        require(source in 1 until ledgerHigh)
                        require(index == 0 || unsignedCompare(previousSource, source) < 0)
                        previousSource = source
                    }
                    supportRecords = Math.addExact(supportRecords, count)
                }
                require(supportEntries == rowCount && (0 until rowCount).all { supportOffsets[it] >= 0 })
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
                        if (index > 0 && unsignedCompare(previous, source.id.value) >= 0)
                            sourcesSorted = false
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
                canonicalReceiptOffset = counted.position
                canonicalReceiptCount = skipCanonicalReceipts(
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

        validateUniqueHashes(ownershipReceiptCount) { visitor ->
            scanOwnershipReceiptHashes(snapshot, ownershipReceiptOffset, configuration, visitor)
        }
        if (version >= 2) validateUniqueHashes(canonicalReceiptCount) { visitor ->
            scanCanonicalReceiptHashes(
                snapshot,
                canonicalReceiptOffset,
                version,
                configuration,
                visitor,
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
                idOrder,
                voxelOrder,
                pageOrder,
                lineageSource,
                lineageTarget,
            )
        val explicitSources = version >= 3
        val finalSourceOffset = sourceOffset
        val finalSourceCount = sourceCount
        val finalSourcesSorted = sourcesSorted
        val explicitSourceIndex =
            if (explicitSources && !finalSourcesSorted)
                M3LegacySourceIndex.build(snapshot, finalSourceOffset, finalSourceCount)
            else null
        val sourceLookup: (Long) -> M3PagedSource? = { id ->
            if (explicitSources)
                if (finalSourcesSorted)
                    sourceAt(snapshot, finalSourceOffset, finalSourceCount, id)
                else RandomAccessFile(snapshot, "r").use { reader ->
                    requireNotNull(explicitSourceIndex).find(reader, id)
                }
            else rowSource(snapshot, rowOffsets, resident, id)
        }
        val sourceCursor: ((M3PagedSource) -> Unit) -> Unit = { visitor ->
            if (explicitSources) {
                if (finalSourcesSorted) {
                    at(snapshot, finalSourceOffset).use { data ->
                        repeat(finalSourceCount) { visitor(readSource(data)) }
                    }
                } else requireNotNull(explicitSourceIndex).visit(visitor)
            } else {
                repeat(rowCount) { order ->
                    val slot = idOrder[order]
                    visitor(
                        requireNotNull(
                            rowSource(snapshot, rowOffsets, resident, unsigned(rowIds[slot]))
                        )
                    )
                }
            }
        }
        val supportCursor: ((Long, M3PagedSource) -> Unit) -> Unit = { visitor ->
            if (version == 1) {
                repeat(rowCount) { order ->
                    val slot = idOrder[order]
                    val id = unsigned(rowIds[slot])
                    visitor(id, requireNotNull(rowSource(snapshot, rowOffsets, resident, id)))
                }
            } else {
                RandomAccessFile(snapshot, "r").use { supportReader ->
                    RandomAccessFile(snapshot, "r").use { sourceReader ->
                        var cachedSourceIndex = -1
                        var cachedSource: M3PagedSource? = null
                        repeat(rowCount) { targetOrder ->
                            val targetSlot = idOrder[targetOrder]
                            supportReader.seek(supportOffsets[targetSlot])
                            val data = supportReader.dataInput()
                            val target = data.readLong()
                            require(target == unsigned(rowIds[targetSlot]))
                            val count = data.readInt()
                            repeat(count) {
                                val sourceId = data.readLong()
                                val source =
                                    if (explicitSources) {
                                        if (!finalSourcesSorted)
                                            requireNotNull(explicitSourceIndex).find(
                                                sourceReader, sourceId,
                                            )
                                        else if (cachedSource?.id?.value == sourceId) cachedSource
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
        // The sorted stream or primitive index proves unique source identity. This single eager
        // support pass binds every membership before quota reservation; the later pass writes v6.
        supportCursor { _, _ -> }
        return M3LegacyCanonicalState(
            group,
            ledgerHigh,
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
            explicitSourceIndex,
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
    ): Int {
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
                val command = data.readUTF().also { require(it.isNotEmpty()) }
                val kind = bounded(data.readInt(), 0, M3CanonicalOperation.entries.lastIndex)
                val targets = M3FieldDigest()
                val targetCount = bounded(data.readInt(), 0, configuration.surfaceCapacity)
                repeat(targetCount) {
                    val id = data.readLong(); val ownerGroup = data.readUTF()
                    val voxel = M3Voxel(data.readInt(), data.readInt(), data.readInt())
                    val region = M3StorageRegion(data.readInt(), data.readInt(), data.readInt())
                    val page = data.readInt(); val normal = data.readInt(); val confidence = data.readInt()
                    val fingerprint = ByteArray(32).also(data::readFully)
                    require(id in 1 until nextHigh && ownerGroup == group.value)
                    require(m3CompactLocation(configuration, voxel) == M3CompactLocation(region, page))
                    require(normal in 0..0xffff && confidence in 0..255 &&
                        (normal ushr 8) != 0x80 && (normal and 0xff) != 0x80)
                    targets.long(id); targets.ints(voxel.x, voxel.y, voxel.z, region.x, region.y,
                        region.z, page, normal, confidence); targets.bytes(fingerprint)
                }
                val removed = M3FieldDigest()
                val removedCount = bounded(data.readInt(), 0, configuration.surfaceCapacity)
                repeat(removedCount) { data.readLong().also { require(it in 1 until nextHigh); removed.long(it) } }
                val edges = M3FieldDigest()
                val edgeCount = bounded(data.readInt(), 0, configuration.lineageCapacity)
                repeat(edgeCount) {
                    val source = data.readLong(); val target = data.readLong()
                    require(source in 1 until nextHigh && target in 1 until nextHigh)
                    edges.long(source); edges.long(target)
                }
                val receiptGeometry = data.readLong(); val receiptLineage = data.readLong()
                val receiptHigh = data.readLong(); val receiptLive = data.readInt()
                require(receiptGeometry in 0..geometryRevision && receiptLineage in 0..lineageRevision &&
                    receiptHigh in 1..nextHigh && receiptLive in 0..configuration.surfaceCapacity)
                if (version == 2) {
                    val bytes = inlineCanonicalReceiptBytes(
                        group.value, command, targetCount, removedCount, edgeCount,
                    )
                    journalBytes = Math.addExact(journalBytes, 68L + bytes)
                }
                if (version >= 3) {
                    val supports = M3FieldDigest()
                    val supportCount = bounded(data.readInt(), 0, configuration.lineageCapacity)
                    repeat(supportCount) {
                        val source = readSource(data).also { require(it.id.value in 1 until nextHigh) }
                        supports.long(source.id.value)
                        supports.ints(source.voxel.x, source.voxel.y, source.voxel.z,
                            source.packedNormal, source.normalConfidence)
                        supports.bytes(source.allocationFingerprint.toByteArray())
                    }
                    val bytes = bounded(data.readInt(), 0, configuration.changeJournalByteCapacity)
                    journalBytes = Math.addExact(journalBytes, 68L + bytes)
                    validateCanonicalReceipt(
                        data, bytes, configuration, group, nextHigh, geometryRevision,
                        lineageRevision,
                        M3InlineCanonicalReceipt(command, kind, receiptGeometry, receiptLineage,
                            receiptHigh, receiptLive, targetCount, targets.finish(), removedCount,
                            removed.finish(), edgeCount, edges.finish(), supportCount,
                            supports.finish()),
                    )
                }
            }
        }
        require(journalBytes <= configuration.changeJournalByteCapacity)
        return count
    }

    /** Canonical journal bytes reconstructed by the accepted v2 writer (support count is zero). */
    private fun inlineCanonicalReceiptBytes(
        group: String,
        command: String,
        targetCount: Int,
        removedCount: Int,
        edgeCount: Int,
    ): Long {
        var bytes = 8L + modifiedUtfBytes(group) + modifiedUtfBytes(command)
        bytes = Math.addExact(bytes, 4L + 3L * 8L + 4L + 4L)
        bytes = Math.addExact(
            bytes,
            Math.multiplyExact(targetCount.toLong(), 8L + modifiedUtfBytes(group) + 9L * 4L + 32L),
        )
        bytes = Math.addExact(bytes, 4L + removedCount * 8L)
        bytes = Math.addExact(bytes, 4L + edgeCount * 16L)
        return Math.addExact(bytes, 4L)
    }

    private fun modifiedUtfBytes(value: String): Long {
        var bytes = 0L
        value.forEach { character ->
            bytes += when (character.code) {
                in 0x0001..0x007f -> 1L
                in 0x0000..0x07ff -> 2L
                else -> 3L
            }
        }
        require(bytes <= 65_535L)
        return bytes + 2L
    }

    private fun validateCanonicalReceipt(
        source: DataInputStream,
        bytes: Int,
        configuration: M3SurfaceOwnershipConfiguration,
        group: M3SurfaceGroup,
        nextHigh: Long,
        geometryRevision: Long,
        lineageRevision: Long,
        expected: M3InlineCanonicalReceipt? = null,
    ) {
        val body = DataInputStream(M3LegacyLimitedInputStream(source, bytes.toLong()))
        require(body.readInt() == 0x4d334352 && body.readInt() == 1)
        require(body.readUTF() == group.value)
        val command = body.readUTF().also { require(it.isNotEmpty()) }
        val kind = bounded(body.readInt(), 0, M3CanonicalOperation.entries.lastIndex)
        val geometry = body.readLong()
        val lineage = body.readLong()
        val high = body.readLong()
        require(
            geometry in 0..geometryRevision &&
                lineage in 0..lineageRevision &&
                high in 1..nextHigh
        )
        val live = bounded(body.readInt(), 0, configuration.surfaceCapacity)
        val targets = M3FieldDigest()
        val targetCount = bounded(body.readInt(), 0, configuration.surfaceCapacity)
        repeat(targetCount) {
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
            val fingerprint = ByteArray(32).also(body::readFully)
            targets.long(id); targets.ints(voxel.x, voxel.y, voxel.z, region.x, region.y,
                region.z, page, normal, confidence); targets.bytes(fingerprint)
        }
        val removed = M3FieldDigest()
        val removedCount = bounded(body.readInt(), 0, configuration.surfaceCapacity)
        repeat(removedCount) {
            body.readLong().also { require(it in 1 until nextHigh); removed.long(it) }
        }
        val edges = M3FieldDigest()
        val edgeCount = bounded(body.readInt(), 0, configuration.lineageCapacity)
        repeat(edgeCount) {
            val edgeSource = body.readLong(); val edgeTarget = body.readLong()
            require(edgeSource in 1 until nextHigh && edgeTarget in 1 until nextHigh)
            edges.long(edgeSource); edges.long(edgeTarget)
        }
        val supports = M3FieldDigest()
        val supportCount = bounded(body.readInt(), 0, configuration.lineageCapacity)
        repeat(supportCount) {
            val support = readSource(body).also { require(it.id.value in 1 until nextHigh) }
            supports.long(support.id.value)
            supports.ints(support.voxel.x, support.voxel.y, support.voxel.z,
                support.packedNormal, support.normalConfidence)
            supports.bytes(support.allocationFingerprint.toByteArray())
        }
        require(body.read() == -1)
        expected?.let {
            require(command == it.command && kind == it.kind && geometry == it.geometry &&
                lineage == it.lineage && high == it.high && live == it.live &&
                targetCount == it.targetCount && targets.finish().contentEquals(it.targetDigest) &&
                removedCount == it.removedCount && removed.finish().contentEquals(it.removedDigest) &&
                edgeCount == it.edgeCount && edges.finish().contentEquals(it.edgeDigest) &&
                supportCount == it.supportCount && supports.finish().contentEquals(it.supportDigest))
        }
    }

    private fun skipOwners(data: DataInputStream, count: Int) {
        repeat(count) {
            data.readLong()
            data.readUTF()
            data.skipFully(9 * 4 + 32)
        }
    }

    private fun scanOwnershipReceiptHashes(
        file: File,
        offset: Long,
        configuration: M3SurfaceOwnershipConfiguration,
        visitor: (ByteArray) -> Unit,
    ) {
        at(file, offset).use { data ->
            repeat(bounded(data.readInt(), 0, configuration.receiptCapacity)) {
                visitor(ByteArray(32).also(data::readFully))
                data.skipFully(32)
                val ownerCount = bounded(data.readInt(), 0, configuration.surfaceCapacity)
                data.skipFully(ownerCount * 8)
                data.skipFully(20)
            }
        }
    }

    private fun scanCanonicalReceiptHashes(
        file: File,
        offset: Long,
        version: Int,
        configuration: M3SurfaceOwnershipConfiguration,
        visitor: (ByteArray) -> Unit,
    ) {
        at(file, offset).use { data ->
            repeat(bounded(data.readInt(), 0, configuration.transactionCapacity)) {
                visitor(ByteArray(32).also(data::readFully))
                data.skipFully(32)
                if (version >= 4) {
                    data.skipFully(
                        bounded(data.readInt(), 0, configuration.changeJournalByteCapacity)
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
        }
    }

    /** Exact duplicate rejection for bounded streaming hash scans. */
    private fun validateUniqueHashes(
        count: Int,
        scan: (((ByteArray) -> Unit) -> Unit),
    ) {
        fun matches(hash: ByteArray, prefix: ByteArray, depth: Int): Boolean {
            repeat(depth) { if (hash[it] != prefix[it]) return false }
            return true
        }
        fun comparePacked(bytes: ByteArray, left: Int, right: Int): Int {
            repeat(32) { offset ->
                val compared = (bytes[left * 32 + offset].toInt() and 0xff)
                    .compareTo(bytes[right * 32 + offset].toInt() and 0xff)
                if (compared != 0) return compared
            }
            return 0
        }
        fun validatePrefix(prefix: ByteArray, depth: Int, matchingCount: Int) {
            if (matchingCount <= HASH_SORT_BUCKET_RECORDS) {
                val records = ByteArray(matchingCount * 32)
                var stored = 0
                scan { hash ->
                    if (matches(hash, prefix, depth)) hash.copyInto(records, stored++ * 32)
                }
                require(stored == matchingCount)
                val order = IntArray(matchingCount) { it }
                order.sortIndices(matchingCount) { left, right -> comparePacked(records, left, right) }
                repeat(matchingCount - 1) { index ->
                    require(comparePacked(records, order[index], order[index + 1]) < 0)
                }
                return
            }
            require(depth < 32)
            val buckets = IntArray(256)
            scan { hash ->
                if (matches(hash, prefix, depth)) {
                    val next = hash[depth].toInt() and 0xff
                    buckets[next] = Math.addExact(buckets[next], 1)
                }
            }
            buckets.forEachIndexed { next, bucketCount ->
                if (bucketCount > 0) {
                    val child = prefix.copyOf()
                    child[depth] = next.toByte()
                    validatePrefix(child, depth + 1, bucketCount)
                }
            }
        }
        if (count > 0) validatePrefix(ByteArray(32), 0, count)
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

    internal fun readSourceRecord(reader: RandomAccessFile): M3PagedSource =
        readSource(reader.dataInput())

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
        val index = rowIndex(resident.rowId, resident.idOrder, resident.rows, id)
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

    private fun rowIndex(ids: IntArray, order: IntArray, count: Int, id: Long): Int {
        var low = 0
        var high = count - 1
        while (low <= high) {
            val middle = (low + high) ushr 1
            val slot = order[middle]
            when (unsignedCompare(unsigned(ids[slot]), id)) {
                in Int.MIN_VALUE until 0 -> low = middle + 1
                in 1..Int.MAX_VALUE -> high = middle - 1
                else -> return slot
            }
        }
        return -1
    }

    private fun emptyState(
        group: M3SurfaceGroup,
        ledger: File,
        snapshot: File,
        ledgerHigh: Long,
        configuration: M3SurfaceOwnershipConfiguration,
    ) =
        M3LegacyCanonicalState(
            group,
            ledgerHigh,
            M3CompactResident(
                0,
                IntArray(configuration.surfaceCapacity),
                IntArray(configuration.surfaceCapacity),
                IntArray(configuration.surfaceCapacity),
                IntArray(configuration.surfaceCapacity),
                ShortArray(configuration.surfaceCapacity),
                ByteArray(configuration.surfaceCapacity),
                IntArray(configuration.surfaceCapacity),
                IntArray(configuration.surfaceCapacity),
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
            null,
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

    private fun packedLong(bytes: ByteArray, offset: Int): Long {
        var value = 0L
        repeat(8) { value = (value shl 8) or (bytes[offset + it].toLong() and 0xff) }
        return value
    }

    private const val HASH_SORT_BUCKET_RECORDS = 800
}

private data class M3InlineCanonicalReceipt(
    val command: String,
    val kind: Int,
    val geometry: Long,
    val lineage: Long,
    val high: Long,
    val live: Int,
    val targetCount: Int,
    val targetDigest: ByteArray,
    val removedCount: Int,
    val removedDigest: ByteArray,
    val edgeCount: Int,
    val edgeDigest: ByteArray,
    val supportCount: Int,
    val supportDigest: ByteArray,
)

/** Small rolling semantic receipt digest; retained scratch is one hash state plus eight bytes. */
private class M3FieldDigest {
    private val digest = MessageDigest.getInstance("SHA-256")
    private val scratch = ByteArray(8)

    fun long(value: Long) {
        repeat(8) { offset -> scratch[offset] = (value ushr (56 - offset * 8)).toByte() }
        digest.update(scratch)
    }

    fun ints(vararg values: Int) = values.forEach { value ->
        repeat(4) { offset -> scratch[offset] = (value ushr (24 - offset * 8)).toByte() }
        digest.update(scratch, 0, 4)
    }

    fun bytes(value: ByteArray) = digest.update(value)
    fun finish(): ByteArray = digest.digest()
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
