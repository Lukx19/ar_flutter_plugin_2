package com.uhg0.ar_flutter_plugin_2.visibilitygrid

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.LinkedHashMap

internal enum class M3CanonicalPageKind(val wire: Int) {
    SOURCE(1),
    SUPPORT(2),
}

internal data class M3CanonicalDirectoryEntry(
    val kind: M3CanonicalPageKind,
    val ordinal: Int,
    val minimumKey: Long,
    val maximumKey: Long,
    val offset: Long,
    val length: Int,
    val count: Int,
    val hash: ByteArray,
)

internal data class M3PagedSource(
    val id: M3SurfaceId,
    val voxel: M3Voxel,
    val packedNormal: Int,
    val normalConfidence: Int,
    val allocationFingerprint: M3CanonicalReceiptBytes,
)

internal data class M3PagedSupport(val target: M3SurfaceId, val source: M3PagedSource)

internal data class M3CanonicalPageSummary(
    val kind: M3CanonicalPageKind,
    val count: Int,
    val firstTarget: Long,
    val firstSource: Long,
    val lastTarget: Long,
    val lastSource: Long,
)

internal sealed interface M3CanonicalPageRead<out T> {
    data class Complete<T>(val value: T, val pageFaults: Int, val bytesRead: Int) :
        M3CanonicalPageRead<T>

    data class Refused(val reason: M3CompactCanonicalRefusal) : M3CanonicalPageRead<Nothing>
}

internal class M3CanonicalPageCache(private val file: File) : AutoCloseable {
    private val pages =
        object : LinkedHashMap<Int, ByteArray>(CACHE_PAGES, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, ByteArray>?) =
                size > CACHE_PAGES
        }
    private var closed = false

    /** Performs zero or one fixed-size read and validates before returning bytes. */
    fun read(entryIndex: Int, entry: M3CanonicalDirectoryEntry): M3CanonicalPageRead<ByteArray> {
        synchronized(this) {
            if (closed) return M3CanonicalPageRead.Refused(M3CompactCanonicalRefusal.CLOSED)
            pages[entryIndex]?.let {
                return M3CanonicalPageRead.Complete(it, 0, 0)
            }
        }
        if (entry.length != PAGE_BYTES || entry.offset < 0 || entry.offset % PAGE_BYTES != 0L) {
            return M3CanonicalPageRead.Refused(M3CompactCanonicalRefusal.CORRUPT)
        }
        val bytes = ByteArray(PAGE_BYTES)
        try {
            RandomAccessFile(file, "r").use { input ->
                if (entry.offset > input.length() - PAGE_BYTES)
                    return M3CanonicalPageRead.Refused(M3CompactCanonicalRefusal.CORRUPT)
                input.seek(entry.offset)
                input.readFully(bytes)
            }
        } catch (_: Exception) {
            return M3CanonicalPageRead.Refused(M3CompactCanonicalRefusal.IO_FAILURE)
        }
        if (!m3PageSha256(bytes).contentEquals(entry.hash) || inspectPage(entry, bytes) == null) {
            return M3CanonicalPageRead.Refused(M3CompactCanonicalRefusal.CORRUPT)
        }
        synchronized(this) {
            if (closed) return M3CanonicalPageRead.Refused(M3CompactCanonicalRefusal.CLOSED)
            pages[entryIndex] = bytes
        }
        return M3CanonicalPageRead.Complete(bytes, 1, PAGE_BYTES)
    }

    override fun close() =
        synchronized(this) {
            closed = true
            pages.clear()
        }

    fun retainedPayloadBytes() = CACHE_PAGES.toLong() * PAGE_BYTES

    companion object {
        const val PAGE_BYTES = 16_384
        const val CACHE_PAGES = 4
        const val MAX_RECORDS = 256
        private const val PAGE_MAGIC = 0x4d335047
        private const val PAGE_VERSION = 1

        fun encodeSourcePage(ordinal: Int, sources: List<M3PagedSource>): ByteArray =
            encodePage(M3CanonicalPageKind.SOURCE, ordinal, sources.size) { out ->
                sources.forEach { writeSource(out, it) }
            }

        fun encodeSupportPage(ordinal: Int, supports: List<M3PagedSupport>): ByteArray =
            encodePage(M3CanonicalPageKind.SUPPORT, ordinal, supports.size) { out ->
                supports.forEach {
                    out.writeInt(it.target.idBits())
                    writeSource(out, it.source)
                }
            }

        fun decodeSources(bytes: ByteArray): List<M3PagedSource> =
            decodePage(bytes, M3CanonicalPageKind.SOURCE) { input, count ->
                List(count) { readSource(input) }
            }

        fun decodeSupports(bytes: ByteArray): List<M3PagedSupport> =
            decodePage(bytes, M3CanonicalPageKind.SUPPORT) { input, count ->
                List(count) {
                    M3PagedSupport(M3SurfaceId(unsigned(input.readInt())), readSource(input))
                }
            }

        private fun encodePage(
            kind: M3CanonicalPageKind,
            ordinal: Int,
            count: Int,
            records: (DataOutputStream) -> Unit,
        ): ByteArray {
            require(ordinal >= 0 && count in 1..MAX_RECORDS)
            val body =
                ByteArrayOutputStream().use { raw ->
                    DataOutputStream(raw).use { out ->
                        out.writeInt(PAGE_MAGIC)
                        out.writeInt(PAGE_VERSION)
                        out.writeByte(kind.wire)
                        out.writeInt(ordinal)
                        out.writeShort(count)
                        records(out)
                    }
                    raw.toByteArray()
                }
            require(body.size <= PAGE_BYTES)
            return body.copyOf(PAGE_BYTES)
        }

        private fun <T> decodePage(
            bytes: ByteArray,
            kind: M3CanonicalPageKind,
            read: (DataInputStream, Int) -> T,
        ): T =
            DataInputStream(ByteArrayInputStream(bytes)).use { input ->
                require(
                    input.readInt() == PAGE_MAGIC &&
                        input.readInt() == PAGE_VERSION &&
                        input.readUnsignedByte() == kind.wire
                )
                require(input.readInt() >= 0)
                val count = input.readUnsignedShort()
                require(count in 1..MAX_RECORDS)
                read(input, count)
            }

        /** Validates a complete fixed page without constructing its record object graph. */
        fun inspectPage(
            entry: M3CanonicalDirectoryEntry,
            bytes: ByteArray,
        ): M3CanonicalPageSummary? =
            try {
                DataInputStream(ByteArrayInputStream(bytes)).use { input ->
                    if (
                        input.readInt() != PAGE_MAGIC ||
                            input.readInt() != PAGE_VERSION ||
                            input.readUnsignedByte() != entry.kind.wire ||
                            input.readInt() != entry.ordinal ||
                            input.readUnsignedShort() != entry.count ||
                            entry.count !in 1..MAX_RECORDS
                    )
                        return null
                    var firstTarget = 0L
                    var firstSource = 0L
                    var previousTarget = 0L
                    var previousSource = 0L
                    repeat(entry.count) {
                        val target =
                            if (entry.kind == M3CanonicalPageKind.SUPPORT) unsigned(input.readInt())
                            else 0L
                        val source = unsigned(input.readInt())
                        input.readInt()
                        input.readInt()
                        input.readInt()
                        val normal = input.readUnsignedShort()
                        input.readUnsignedByte()
                        repeat(32) { input.readByte() }
                        if (
                            source == 0L ||
                                (normal ushr 8) == 0x80 ||
                                (normal and 0xff) == 0x80 ||
                                (entry.kind == M3CanonicalPageKind.SUPPORT && target == 0L) ||
                                (it > 0 &&
                                (if (entry.kind == M3CanonicalPageKind.SOURCE)
                                    unsignedCompare(previousSource, source) >= 0
                                else
                                    unsignedComparePair(
                                        previousTarget,
                                        previousSource,
                                        target,
                                        source,
                                    ) >= 0)
                                )
                        )
                            return null
                        if (it == 0) {
                            firstTarget = target
                            firstSource = source
                        }
                        previousTarget = target
                        previousSource = source
                    }
                    val min =
                        if (entry.kind == M3CanonicalPageKind.SOURCE) firstSource else firstTarget
                    val max =
                        if (entry.kind == M3CanonicalPageKind.SOURCE) previousSource
                        else previousTarget
                    if (min != entry.minimumKey || max != entry.maximumKey) return null
                    while (input.available() > 0) if (input.readUnsignedByte() != 0) return null
                    M3CanonicalPageSummary(
                        entry.kind,
                        entry.count,
                        firstTarget,
                        firstSource,
                        previousTarget,
                        previousSource,
                    )
                }
            } catch (_: Exception) {
                null
            }

        private fun writeSource(out: DataOutputStream, source: M3PagedSource) {
            val fingerprint = source.allocationFingerprint.toByteArray()
            require(fingerprint.size == 32)
            out.writeInt(source.id.idBits())
            out.writeInt(source.voxel.x)
            out.writeInt(source.voxel.y)
            out.writeInt(source.voxel.z)
            out.writeShort(source.packedNormal)
            out.writeByte(source.normalConfidence)
            out.write(fingerprint)
        }

        private fun readSource(input: DataInputStream): M3PagedSource =
            M3PagedSource(
                M3SurfaceId(unsigned(input.readInt())),
                M3Voxel(input.readInt(), input.readInt(), input.readInt()),
                input.readUnsignedShort(),
                input.readUnsignedByte(),
                M3CanonicalReceiptBytes(ByteArray(32).also(input::readFully)),
            )
    }
}

internal fun M3SurfaceId.idBits() = value.toInt()

internal fun unsigned(bits: Int) = bits.toLong() and 0xffff_ffffL

internal fun unsignedCompare(left: Long, right: Long) = java.lang.Long.compareUnsigned(left, right)

internal fun unsignedComparePair(lt: Long, ls: Long, rt: Long, rs: Long): Int {
    val target = unsignedCompare(lt, rt)
    return if (target != 0) target else unsignedCompare(ls, rs)
}

internal fun m3PageSha256(bytes: ByteArray): ByteArray =
    MessageDigest.getInstance("SHA-256").digest(bytes)
