package com.uhg0.ar_flutter_plugin_2.visibilitygrid

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest

/** Read-only, bounded projection of group-local canonical authority. */
internal interface M3CanonicalStateView : AutoCloseable {
    val cut: M3CompactCanonicalCut
    fun findById(id: M3SurfaceId): M3CompactSurface?
    fun findByVoxel(voxel: M3Voxel): M3CompactSurface?
    fun readPage(region: M3StorageRegion, page: Int, cursor: Int, limit: Int): M3CompactPage
    fun visitSourceSupport(id: M3SurfaceId, sink: (M3CompactSource) -> Boolean): Int
    fun retainedMemoryReceipt(): M3CompactRetainedMemoryReceipt
}

internal data class M3CompactCanonicalCut(
    val group: M3SurfaceGroup,
    val geometryRevision: Long,
    val lineageRevision: Long,
    val nextSurfaceIdHighWater: Long,
    val liveSurfaceCount: Int,
    val rootHash: ByteArray,
    val sourceHash: ByteArray,
) {
    override fun equals(other: Any?) = other is M3CompactCanonicalCut && group == other.group &&
        geometryRevision == other.geometryRevision && lineageRevision == other.lineageRevision &&
        nextSurfaceIdHighWater == other.nextSurfaceIdHighWater && liveSurfaceCount == other.liveSurfaceCount &&
        rootHash.contentEquals(other.rootHash) && sourceHash.contentEquals(other.sourceHash)
    override fun hashCode() = listOf(group, geometryRevision, lineageRevision, nextSurfaceIdHighWater, liveSurfaceCount).hashCode() * 31 + rootHash.contentHashCode()
}

internal data class M3CompactSurface(
    val id: M3SurfaceId,
    val voxel: M3Voxel,
    val packedNormal: Int,
    val normalConfidence: Int,
)

internal data class M3CompactSource(
    val id: M3SurfaceId,
    val voxel: M3Voxel,
    val packedNormal: Int,
    val normalConfidence: Int,
)

internal data class M3CompactPage(val rows: List<M3CompactSurface>, val nextCursor: Int?)

/** Every strongly reachable retained owner, including the kernel share. */
internal data class M3CompactRetainedMemoryReceipt(
    val kernelBytes: Long,
    val rowColumnsBytes: Long,
    val idOrderBytes: Long,
    val pageOrderBytes: Long,
    val sourceColumnsBytes: Long,
    val lineageColumnsBytes: Long,
    val directoryBytes: Long,
    val scalarAndObjectBytes: Long,
) {
    val compactAndDirectoryBytes: Long get() = rowColumnsBytes + idOrderBytes + pageOrderBytes + sourceColumnsBytes + lineageColumnsBytes + directoryBytes + scalarAndObjectBytes
    val totalBytes: Long get() = kernelBytes + compactAndDirectoryBytes
    val journalReserveBytes: Long get() = M3CompactCanonicalStore.JOURNAL_RESERVE_BYTES
    val withinIssue114Budget: Boolean get() = totalBytes <= M3CompactCanonicalStore.ISSUE_114_BUDGET_BYTES
    val preservesIssue115Reserve: Boolean get() = totalBytes + journalReserveBytes <= M3CompactCanonicalStore.C17_TOTAL_BYTES
}

internal sealed interface M3CompactCanonicalOpenResult {
    data class Opened(val store: M3CompactCanonicalStore) : M3CompactCanonicalOpenResult
    data class Refused(val reason: M3CompactCanonicalRefusal) : M3CompactCanonicalOpenResult
}
internal sealed interface M3CompactCanonicalMigrationResult {
    data class Prepared(val cut: M3CompactCanonicalCut, val candidateDirectory: File) : M3CompactCanonicalMigrationResult
    data class Refused(val reason: M3CompactCanonicalRefusal) : M3CompactCanonicalMigrationResult
}
internal enum class M3CompactCanonicalRefusal { INVALID_CONFIGURATION, CORRUPT, CAPACITY, IDENTITY_CONFLICT, DURABILITY_FAILURE, CLOSED }
internal enum class M3CompactCanonicalMigrationFault { BEFORE_PAGE_SYNC, AFTER_PAGE_SYNC, BEFORE_DIRECTORY_SYNC }

/**
 * Compact v6 read authority.  Its small interface hides the primitive columns,
 * sorted lookup indexes, page directory and legacy codec from callers.  It is
 * intentionally read-only: v6 mutation, WAL/current receipt and ACK are #115.
 */
internal class M3CompactCanonicalStore private constructor(
    override val cut: M3CompactCanonicalCut,
    private val configuration: M3SurfaceOwnershipConfiguration,
    private val rowCount: Int,
    private val rowId: IntArray,
    private val rowX: IntArray,
    private val rowY: IntArray,
    private val rowZ: IntArray,
    private val rowNormal: ShortArray,
    private val rowConfidence: ByteArray,
    private val idOrder: IntArray,
    private val pageOrder: IntArray,
    private val sourceCount: Int,
    private val sourceId: IntArray,
    private val sourceX: IntArray,
    private val sourceY: IntArray,
    private val sourceZ: IntArray,
    private val sourceNormal: ShortArray,
    private val sourceConfidence: ByteArray,
    private val lineageCount: Int,
    private val lineageSource: IntArray,
    private val lineageTarget: IntArray,
    private val directoryBytes: Long,
) : M3CanonicalStateView {
    private var closed = false

    override fun findById(id: M3SurfaceId): M3CompactSurface? = synchronized(this) {
        if (closed) return null
        val wanted = id.value.toInt()
        var low = 0; var high = rowCount - 1
        while (low <= high) {
            val mid = (low + high) ushr 1
            val slot = idOrder[mid]
            when {
                rowId[slot] < wanted -> low = mid + 1
                rowId[slot] > wanted -> high = mid - 1
                else -> return row(slot)
            }
        }
        null
    }

    override fun findByVoxel(voxel: M3Voxel): M3CompactSurface? = synchronized(this) {
        if (closed) return null
        var low = 0; var high = rowCount - 1
        while (low <= high) {
            val mid = (low + high) ushr 1
            val slot = pageOrder[mid]
            val compare = compareVoxel(rowX[slot], rowY[slot], rowZ[slot], voxel.x, voxel.y, voxel.z)
            when {
                compare < 0 -> low = mid + 1
                compare > 0 -> high = mid - 1
                else -> return row(slot)
            }
        }
        null
    }

    override fun readPage(region: M3StorageRegion, page: Int, cursor: Int, limit: Int): M3CompactPage = synchronized(this) {
        if (closed || page !in 0..26 || cursor < 0 || limit !in 1..MAX_PAGE_READ) return M3CompactPage(emptyList(), null)
        val rows = ArrayList<M3CompactSurface>(minOf(limit, 64))
        var index = cursor
        while (index < rowCount && rows.size < limit) {
            val slot = pageOrder[index++]
            val location = compactLocation(configuration, M3Voxel(rowX[slot], rowY[slot], rowZ[slot])) ?: continue
            if (location.region == region && location.page == page) rows += row(slot)
        }
        M3CompactPage(rows, if (index < rowCount) index else null)
    }

    override fun visitSourceSupport(id: M3SurfaceId, sink: (M3CompactSource) -> Boolean): Int = synchronized(this) {
        if (closed || findById(id) == null) return 0
        // Support is represented by immutable source rows plus canonical
        // lineage.  A bounded reverse walk avoids a duplicate support CSR.
        val pending = IntArray(sourceCount + lineageCount + 1)
        val seen = BooleanArray(sourceCount)
        var head = 0; var tail = 0; var delivered = 0
        pending[tail++] = id.value.toInt()
        while (head < tail && delivered < configuration.lineageCapacity) {
            val target = pending[head++]
            var predecessor = false
            for (edge in 0 until lineageCount) if (lineageTarget[edge] == target && lineageSource[edge] != target) {
                pending[tail++] = lineageSource[edge]; predecessor = true
            }
            if (!predecessor) {
                val source = sourceFor(target) ?: continue
                val sourceIndex = sourceIndex(target)
                if (sourceIndex >= 0 && !seen[sourceIndex]) {
                    seen[sourceIndex] = true; delivered++
                    if (!sink(source)) break
                }
            }
        }
        delivered
    }

    override fun retainedMemoryReceipt(): M3CompactRetainedMemoryReceipt = M3CompactRetainedMemoryReceipt(
        kernelBytes = KERNEL_RETAINED_BYTES,
        rowColumnsBytes = rowId.size.toLong() * 4 + rowX.size.toLong() * 12 + rowNormal.size.toLong() * 2 + rowConfidence.size,
        idOrderBytes = idOrder.size.toLong() * 4,
        pageOrderBytes = pageOrder.size.toLong() * 4,
        sourceColumnsBytes = sourceId.size.toLong() * 4 + sourceX.size.toLong() * 12 + sourceNormal.size.toLong() * 2 + sourceConfidence.size,
        lineageColumnsBytes = lineageSource.size.toLong() * 8,
        directoryBytes = directoryBytes,
        scalarAndObjectBytes = SCALAR_AND_OBJECT_BYTES,
    )

    override fun close() { synchronized(this) { closed = true } }

    private fun row(slot: Int) = M3CompactSurface(M3SurfaceId(rowId[slot].toLong() and 0xffff_ffffL), M3Voxel(rowX[slot], rowY[slot], rowZ[slot]), rowNormal[slot].toInt() and 0xffff, rowConfidence[slot].toInt() and 0xff)
    private fun sourceFor(id: Int): M3CompactSource? {
        val index = sourceIndex(id); if (index < 0) return null
        return M3CompactSource(M3SurfaceId(sourceId[index].toLong() and 0xffff_ffffL), M3Voxel(sourceX[index], sourceY[index], sourceZ[index]), sourceNormal[index].toInt() and 0xffff, sourceConfidence[index].toInt() and 0xff)
    }
    private fun sourceIndex(id: Int): Int {
        var low = 0; var high = sourceCount - 1
        while (low <= high) { val mid = (low + high) ushr 1; when { sourceId[mid] < id -> low = mid + 1; sourceId[mid] > id -> high = mid - 1; else -> return mid } }
        return -1
    }

    companion object {
        internal const val C17_TOTAL_BYTES = 16_777_216L
        internal const val JOURNAL_RESERVE_BYTES = 1_048_576L
        internal const val ISSUE_114_BUDGET_BYTES = C17_TOTAL_BYTES - JOURNAL_RESERVE_BYTES
        internal const val KERNEL_RETAINED_BYTES = 7_548_936L
        private const val SCALAR_AND_OBJECT_BYTES = 4_096L
        private const val MAX_PAGE_READ = 512
        private const val MAGIC = 0x4d334336
        private const val VERSION = 6

        fun openV6(group: M3SurfaceGroup, directory: File, configuration: M3SurfaceOwnershipConfiguration = M3SurfaceOwnershipConfiguration()): M3CompactCanonicalOpenResult = try {
            if (!configuration.isValid) return M3CompactCanonicalOpenResult.Refused(M3CompactCanonicalRefusal.INVALID_CONFIGURATION)
            val candidate = candidateDirectory(directory, group)
            val root = File(candidate, "canonical.v6")
            if (!root.isFile) return M3CompactCanonicalOpenResult.Refused(M3CompactCanonicalRefusal.CORRUPT)
            M3CompactCanonicalOpenResult.Opened(decode(group, configuration, root.readBytes(), File(candidate, "directory.v6").length()))
        } catch (_: Exception) { M3CompactCanonicalOpenResult.Refused(M3CompactCanonicalRefusal.CORRUPT) }

        fun prepareV6SiblingMigration(
            group: M3SurfaceGroup,
            directory: File,
            configuration: M3SurfaceOwnershipConfiguration = M3SurfaceOwnershipConfiguration(),
            fault: M3CompactCanonicalMigrationFault? = null,
        ): M3CompactCanonicalMigrationResult {
            if (!configuration.isValid) return M3CompactCanonicalMigrationResult.Refused(M3CompactCanonicalRefusal.INVALID_CONFIGURATION)
            return try {
                val legacy = M3SurfaceOwnershipLegacyCodec.readValidated(group, directory, configuration)
                val target = candidateDirectory(directory, group)
                if (target.exists()) {
                    val opened = openV6(group, directory, configuration)
                    val current = (opened as? M3CompactCanonicalOpenResult.Opened)?.store
                        ?: return M3CompactCanonicalMigrationResult.Refused(M3CompactCanonicalRefusal.CORRUPT)
                    return if (current.cut.sourceHash.contentEquals(legacy.sourceHash)) M3CompactCanonicalMigrationResult.Prepared(current.cut, target)
                    else M3CompactCanonicalMigrationResult.Refused(M3CompactCanonicalRefusal.IDENTITY_CONFLICT)
                }
                val bytes = encode(legacy, configuration)
                val staging = File(directory, "${target.name}.staging-${Thread.currentThread().id}-${System.nanoTime()}")
                try {
                    if (!staging.mkdirs()) throw IllegalStateException()
                    val root = File(staging, "canonical.v6")
                    if (fault == M3CompactCanonicalMigrationFault.BEFORE_PAGE_SYNC) throw IllegalStateException()
                    FileOutputStream(root).use { out -> out.write(bytes); out.fd.sync() }
                    if (fault == M3CompactCanonicalMigrationFault.AFTER_PAGE_SYNC) throw IllegalStateException()
                    val directoryBytes = encodeDirectory(legacy.sourceHash, sha256(bytes), bytes.size.toLong())
                    FileOutputStream(File(staging, "directory.v6")).use { out -> out.write(directoryBytes); out.fd.sync() }
                    if (fault == M3CompactCanonicalMigrationFault.BEFORE_DIRECTORY_SYNC) throw IllegalStateException()
                    syncDirectory(staging)
                    Files.move(staging.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
                    syncDirectory(directory)
                    val store = decode(group, configuration, bytes, directoryBytes.size.toLong())
                    M3CompactCanonicalMigrationResult.Prepared(store.cut, target)
                } catch (_: Exception) {
                    staging.deleteRecursively()
                    throw IllegalStateException()
                }
            } catch (_: M3RestoreFailure) { M3CompactCanonicalMigrationResult.Refused(M3CompactCanonicalRefusal.CORRUPT) }
            catch (_: IllegalArgumentException) { M3CompactCanonicalMigrationResult.Refused(M3CompactCanonicalRefusal.CAPACITY) }
            catch (_: Exception) { M3CompactCanonicalMigrationResult.Refused(M3CompactCanonicalRefusal.DURABILITY_FAILURE) }
        }

        private fun encode(legacy: M3LegacyCanonicalState, configuration: M3SurfaceOwnershipConfiguration): ByteArray {
            require(legacy.rows.size <= configuration.surfaceCapacity)
            require(legacy.sources.size <= configuration.surfaceCapacity)
            require(legacy.lineage.size <= configuration.lineageCapacity)
            require(legacy.rows.map { it.id.value }.distinct().size == legacy.rows.size)
            require(legacy.rows.all { compactLocation(configuration, it.voxel) != null })
            // Validate legacy's immutable support before dropping the duplicate
            // map: v6 derives the same root support from source+lineage.
            val liveIds = legacy.rows.mapTo(hashSetOf()) { it.id.value }
            require(legacy.supports.all { (id, values) -> id in liveIds && values.isNotEmpty() && values.contentEquals(values.distinct().sorted().toLongArray()) })
            return ByteArrayOutputStream().use { raw -> DataOutputStream(raw).use { out ->
                out.writeInt(MAGIC); out.writeInt(VERSION); out.writeUTF(legacy.group.value)
                out.writeLong(legacy.nextHighWater); out.writeLong(legacy.geometryRevision); out.writeLong(legacy.lineageRevision)
                out.write(legacy.sourceHash)
                out.writeInt(legacy.rows.size)
                legacy.rows.sortedBy { it.id.value }.forEach { row -> out.writeInt(row.id.value.toInt()); out.writeInt(row.voxel.x); out.writeInt(row.voxel.y); out.writeInt(row.voxel.z); out.writeShort(row.packedNormal); out.writeByte(row.normalConfidence) }
                out.writeInt(legacy.sources.size)
                legacy.sources.sortedBy { it.id.value }.forEach { source -> out.writeInt(source.id.value.toInt()); out.writeInt(source.voxel.x); out.writeInt(source.voxel.y); out.writeInt(source.voxel.z); out.writeShort(source.packedNormal); out.writeByte(source.normalConfidence) }
                out.writeInt(legacy.lineage.size)
                legacy.lineage.sortedWith(compareBy({ it.source.value }, { it.target.value })).forEach { edge -> out.writeInt(edge.source.value.toInt()); out.writeInt(edge.target.value.toInt()) }
                out.writeBoolean(legacy.baseline != null)
                legacy.baseline?.let { out.writeUTF(it.bindingIdentity); out.writeUTF(it.groupIdentity); out.writeLong(it.transactionId); out.writeLong(it.geometryRevision); out.writeLong(it.lineageRevision) }
            }; val body = raw.toByteArray(); body + sha256(body) }
        }

        private fun decode(group: M3SurfaceGroup, configuration: M3SurfaceOwnershipConfiguration, bytes: ByteArray, directoryBytes: Long): M3CompactCanonicalStore {
            require(bytes.size > 32); val body = bytes.copyOfRange(0, bytes.size - 32); require(sha256(body).contentEquals(bytes.copyOfRange(bytes.size - 32, bytes.size)))
            DataInputStream(ByteArrayInputStream(body)).use { input ->
                require(input.readInt() == MAGIC && input.readInt() == VERSION && input.readUTF() == group.value)
                val high = input.readLong(); val geometry = input.readLong(); val lineageRevision = input.readLong(); val sourceHash = ByteArray(32).also(input::readFully)
                val rows = bounded(input.readInt(), configuration.surfaceCapacity); val rowId = IntArray(configuration.surfaceCapacity); val rowX = IntArray(configuration.surfaceCapacity); val rowY = IntArray(configuration.surfaceCapacity); val rowZ = IntArray(configuration.surfaceCapacity); val rowNormal = ShortArray(configuration.surfaceCapacity); val rowConfidence = ByteArray(configuration.surfaceCapacity)
                repeat(rows) { index -> rowId[index] = input.readInt(); rowX[index] = input.readInt(); rowY[index] = input.readInt(); rowZ[index] = input.readInt(); rowNormal[index] = input.readShort(); rowConfidence[index] = input.readByte(); require(rowId[index] != 0 && compactLocation(configuration, M3Voxel(rowX[index], rowY[index], rowZ[index])) != null) }
                val sources = bounded(input.readInt(), configuration.surfaceCapacity); val sourceId = IntArray(configuration.surfaceCapacity); val sourceX = IntArray(configuration.surfaceCapacity); val sourceY = IntArray(configuration.surfaceCapacity); val sourceZ = IntArray(configuration.surfaceCapacity); val sourceNormal = ShortArray(configuration.surfaceCapacity); val sourceConfidence = ByteArray(configuration.surfaceCapacity)
                repeat(sources) { index -> sourceId[index] = input.readInt(); sourceX[index] = input.readInt(); sourceY[index] = input.readInt(); sourceZ[index] = input.readInt(); sourceNormal[index] = input.readShort(); sourceConfidence[index] = input.readByte(); require(sourceId[index] != 0) }
                val edges = bounded(input.readInt(), configuration.lineageCapacity); val lineageSource = IntArray(configuration.lineageCapacity); val lineageTarget = IntArray(configuration.lineageCapacity)
                repeat(edges) { index -> lineageSource[index] = input.readInt(); lineageTarget[index] = input.readInt(); require(lineageSource[index] != 0 && lineageTarget[index] != 0) }
                if (input.readBoolean()) { M3CommittedEmptyBaseline(input.readUTF(), input.readUTF(), input.readLong(), input.readLong(), input.readLong()) }
                require(input.available() == 0)
                val idOrder = IntArray(configuration.surfaceCapacity) { it }; idOrder.sortWithRows(rows) { a, b -> rowId[a].compareTo(rowId[b]) }
                val pageOrder = IntArray(configuration.surfaceCapacity) { it }; pageOrder.sortWithRows(rows) { a, b -> compareVoxel(rowX[a], rowY[a], rowZ[a], rowX[b], rowY[b], rowZ[b]) }
                require((1 until rows).none { rowId[idOrder[it - 1]] == rowId[idOrder[it]] }); require((1 until sources).none { sourceId[it - 1] >= sourceId[it] })
                val rootHash = sha256(bytes)
                return M3CompactCanonicalStore(M3CompactCanonicalCut(group, geometry, lineageRevision, high, rows, rootHash, sourceHash), configuration, rows, rowId, rowX, rowY, rowZ, rowNormal, rowConfidence, idOrder, pageOrder, sources, sourceId, sourceX, sourceY, sourceZ, sourceNormal, sourceConfidence, edges, lineageSource, lineageTarget, directoryBytes)
            }
        }

        private fun encodeDirectory(sourceHash: ByteArray, rootHash: ByteArray, bytes: Long) = ByteArrayOutputStream().use { raw -> DataOutputStream(raw).use { out -> out.writeInt(0x4d334344); out.writeInt(VERSION); out.write(sourceHash); out.write(rootHash); out.writeLong(bytes) }; raw.toByteArray() }
        private fun candidateDirectory(parent: File, group: M3SurfaceGroup) = File(parent, "m3-canonical-v6-${sha256(group.value.encodeToByteArray()).hex()}")
        private fun bounded(value: Int, maximum: Int): Int { require(value in 0..maximum); return value }
        private fun syncDirectory(directory: File) { if (!(System.getProperty("os.name") ?: "").startsWith("Windows", true)) FileChannel.open(directory.toPath(), StandardOpenOption.READ).use { it.force(true) } }
    }
}

private data class M3CompactLocation(val region: M3StorageRegion, val page: Int)
private fun compactLocation(configuration: M3SurfaceOwnershipConfiguration, voxel: M3Voxel): M3CompactLocation? {
    val perRegion = configuration.regionMicrometers / configuration.voxelMicrometers; val perPage = configuration.pageMicrometers / configuration.voxelMicrometers
    fun axis(value: Int) = Math.floorDiv(value, perRegion) to Math.floorMod(value, perRegion)
    val (rx, lx) = axis(voxel.x); val (ry, ly) = axis(voxel.y); val (rz, lz) = axis(voxel.z); val px = lx / perPage; val py = ly / perPage; val pz = lz / perPage
    return if (px !in 0..2 || py !in 0..2 || pz !in 0..2) null else M3CompactLocation(M3StorageRegion(rx, ry, rz), px + 3 * (py + 3 * pz))
}
private fun compareVoxel(ax: Int, ay: Int, az: Int, bx: Int, by: Int, bz: Int): Int = when { ax != bx -> ax.compareTo(bx); ay != by -> ay.compareTo(by); else -> az.compareTo(bz) }
private fun IntArray.sortWithRows(count: Int, compare: (Int, Int) -> Int) { java.util.Arrays.sort(this.copyOfRange(0, count)); for (i in 1 until count) { val value = this[i]; var j = i - 1; while (j >= 0 && compare(this[j], value) > 0) { this[j + 1] = this[j]; j-- }; this[j + 1] = value } }
private fun sha256(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)
private fun ByteArray.hex(): String = joinToString("") { "%02x".format(it) }
