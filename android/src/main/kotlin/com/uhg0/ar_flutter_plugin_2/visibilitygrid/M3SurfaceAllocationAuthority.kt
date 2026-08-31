package com.uhg0.ar_flutter_plugin_2.visibilitygrid

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.MessageDigest

/** The single C11 allocation authority shared by legacy ownership and v6 persistence. */
internal data class M3AllocationRecord(
    val revision: Long,
    val start: Long,
    val endExclusive: Long,
    val groupHash: ByteArray,
    val commandHash: ByteArray,
    val fingerprint: ByteArray,
    val previousHash: ByteArray,
) {
    val recordHash: ByteArray get() = allocationSha256(bytesWithoutHash())

    fun bytesWithoutHash(): ByteArray = ByteArrayOutputStream().use { output ->
        DataOutputStream(output).use { data ->
            data.writeInt(MAGIC)
            data.writeInt(VERSION)
            data.writeLong(revision)
            data.writeLong(start)
            data.writeLong(endExclusive)
            data.write(groupHash)
            data.write(commandHash)
            data.write(fingerprint)
            data.write(previousHash)
        }
        output.toByteArray()
    }

    fun encoded(): ByteArray = bytesWithoutHash() + recordHash

    companion object {
        internal const val ENCODED_BYTES = 4 + 4 + 8 + 8 + 8 + 32 * 5
        private const val MAGIC = 0x4d33524c
        private const val VERSION = 1

        fun decode(bytes: ByteArray): M3AllocationRecord {
            require(bytes.size == ENCODED_BYTES)
            val data = DataInputStream(ByteArrayInputStream(bytes))
            require(data.readInt() == MAGIC && data.readInt() == VERSION)
            val record = M3AllocationRecord(
                data.readLong(), data.readLong(), data.readLong(),
                ByteArray(32).also(data::readFully),
                ByteArray(32).also(data::readFully),
                ByteArray(32).also(data::readFully),
                ByteArray(32).also(data::readFully),
            )
            val storedHash = ByteArray(32).also(data::readFully)
            require(storedHash.contentEquals(record.recordHash))
            return record
        }
    }
}

internal data class M3AllocationChain(
    val records: List<M3AllocationRecord>,
    val highWater: Long,
    val lastRevision: Long,
    val lastHash: ByteArray,
    val history: M3AllocationHistoryReceipt = M3AllocationHistoryReceipt(records.size.toLong(), 0L, 0L),
    val authorityHighWaterSeen: Boolean = false,
)

internal data class M3AllocationHistoryReceipt(
    val records: Long,
    val bytesRead: Long,
    val phasePeakBytes: Long,
)

internal data class M3AllocationCheckpoint(
    val revision: Long,
    val highWater: Long,
    val lastHash: ByteArray,
    val history: M3AllocationHistoryReceipt,
    val authorityHighWaterSeen: Boolean,
) {
    /**
     * A checkpoint authenticates its allocation-history prefix. Whether that
     * prefix happened to contain the authority high-water is reopen-local: a
     * later adjacent cut can legitimately advance beyond an older prefix.
     */
    fun matchesHistoryPrefix(chain: M3AllocationChain): Boolean =
        revision == chain.lastRevision &&
            highWater == chain.highWater &&
            lastHash.contentEquals(chain.lastHash) &&
            history == chain.history

    fun encoded(): ByteArray {
        val body = ByteArrayOutputStream().use { output ->
            DataOutputStream(output).use { data ->
                data.writeInt(MAGIC); data.writeInt(VERSION)
                data.writeLong(revision); data.writeLong(highWater); data.write(lastHash)
                data.writeLong(history.records); data.writeLong(history.bytesRead); data.writeLong(history.phasePeakBytes)
                data.writeBoolean(authorityHighWaterSeen)
            }
            output.toByteArray()
        }
        return body + allocationSha256(body)
    }

    companion object {
        private const val MAGIC = 0x4d334143
        private const val VERSION = 1
        internal const val ENCODED_BYTES = 4 + 4 + 8 + 8 + 32 + 8 + 8 + 8 + 1 + 32

        fun from(chain: M3AllocationChain) = M3AllocationCheckpoint(
            chain.lastRevision, chain.highWater, chain.lastHash, chain.history, chain.authorityHighWaterSeen,
        )

        fun decode(bytes: ByteArray): M3AllocationCheckpoint {
            require(bytes.size == ENCODED_BYTES)
            val body = bytes.copyOfRange(0, bytes.size - 32)
            require(bytes.copyOfRange(body.size, bytes.size).contentEquals(allocationSha256(body)))
            return DataInputStream(ByteArrayInputStream(body)).use { data ->
                require(data.readInt() == MAGIC && data.readInt() == VERSION)
                M3AllocationCheckpoint(
                    data.readLong(), data.readLong(), ByteArray(32).also(data::readFully),
                    M3AllocationHistoryReceipt(data.readLong(), data.readLong(), data.readLong()),
                    data.readBoolean(),
                ).also { require(data.read() == -1) }
            }
        }
    }
}

internal object M3SurfaceAllocationAuthority {
    private const val UINT32_HIGH_WATER = 0x1_0000_0000L

    fun legacyFile(directory: File, group: M3SurfaceGroup) =
        File(directory, "m3-surface-${group.hash.hex()}.ledger")

    /** Streams legacy history into scalar chain state; no history-sized collection survives. */
    fun streamLegacy(
        directory: File,
        group: M3SurfaceGroup,
        initialHighWater: Long,
        authorityHighWater: Long,
    ): M3AllocationChain {
        val file = legacyFile(directory, group)
        if (!file.exists()) return emptyChain(initialHighWater, authorityHighWater)
        require(file.length() % M3AllocationRecord.ENCODED_BYTES == 0L)
        val cursor = M3AllocationCursor(group, initialHighWater, authorityHighWater)
        BufferedInputStream(FileInputStream(file), HISTORY_SCRATCH_BYTES).use { input ->
            val recordBytes = ByteArray(M3AllocationRecord.ENCODED_BYTES)
            while (true) {
                var offset = 0
                while (offset < recordBytes.size) {
                    val count = input.read(recordBytes, offset, recordBytes.size - offset)
                    if (count < 0) {
                        require(offset == 0)
                        return cursor.chain()
                    }
                    offset += count
                }
                cursor.accept(M3AllocationRecord.decode(recordBytes))
            }
        }
    }

    fun continueStreaming(
        chain: M3AllocationChain,
        group: M3SurfaceGroup,
        record: M3AllocationRecord,
        authorityHighWater: Long,
    ): M3AllocationChain {
        val cursor = M3AllocationCursor(
            group, chain.highWater, authorityHighWater, chain.lastRevision, chain.lastHash, chain.history,
            chain.authorityHighWaterSeen,
        )
        cursor.accept(record)
        return cursor.chain()
    }

    fun appendLegacy(file: File, record: M3AllocationRecord) {
        FileOutputStream(file, true).use { output ->
            output.write(record.encoded())
            output.fd.sync()
        }
    }

    /** Validates by record revision/hash, never by directory enumeration order or filename. */
    fun validate(
        group: M3SurfaceGroup,
        records: List<M3AllocationRecord>,
        initialHighWater: Long = 1L,
    ): M3AllocationChain {
        var highWater = initialHighWater
        var revision = 0L
        var previous = ByteArray(32)
        records.forEach { record ->
            require(record.revision == ++revision)
            require(record.start == highWater)
            // A zero-range refinement still burns a unique transaction revision.
            require(record.endExclusive in record.start..UINT32_HIGH_WATER)
            require(record.groupHash.contentEquals(group.hash))
            require(record.commandHash.size == 32)
            require(record.fingerprint.size == 32)
            require(record.previousHash.contentEquals(previous))
            require(!record.recordHash.contentEquals(ByteArray(32)))
            highWater = record.endExclusive
            previous = record.recordHash
        }
        return M3AllocationChain(records, highWater, revision, previous)
    }


    private fun emptyChain(initialHighWater: Long, authorityHighWater: Long) = M3AllocationChain(
        emptyList(), initialHighWater, 0L, ByteArray(32),
        M3AllocationHistoryReceipt(0L, 0L, HISTORY_PHASE_PEAK_BYTES),
        initialHighWater == authorityHighWater,
    )

    internal const val HISTORY_SCRATCH_BYTES = 65_536
    internal const val HISTORY_PHASE_PEAK_BYTES = HISTORY_SCRATCH_BYTES + M3AllocationRecord.ENCODED_BYTES * 2L + 512L
}

private class M3AllocationCursor(
    private val group: M3SurfaceGroup,
    private var highWater: Long,
    private val authorityHighWater: Long,
    private var revision: Long = 0L,
    previousHash: ByteArray = ByteArray(32),
    history: M3AllocationHistoryReceipt = M3AllocationHistoryReceipt(0L, 0L, M3SurfaceAllocationAuthority.HISTORY_PHASE_PEAK_BYTES),
    authorityHighWaterSeen: Boolean = highWater == authorityHighWater,
) {
    private var previous = previousHash.copyOf()
    private var records = history.records
    private var bytesRead = history.bytesRead
    private var authoritySeen = authorityHighWaterSeen

    fun accept(record: M3AllocationRecord) {
        require(record.revision == Math.addExact(revision, 1L))
        require(record.start == highWater)
        require(record.endExclusive in record.start..0x1_0000_0000L)
        require(record.groupHash.contentEquals(group.hash))
        require(record.commandHash.size == 32 && record.fingerprint.size == 32)
        require(record.previousHash.contentEquals(previous))
        require(!record.recordHash.contentEquals(ByteArray(32)))
        revision = record.revision
        highWater = record.endExclusive
        if (highWater == authorityHighWater) authoritySeen = true
        previous = record.recordHash
        records = Math.addExact(records, 1L)
        bytesRead = Math.addExact(bytesRead, M3AllocationRecord.ENCODED_BYTES.toLong())
    }

    fun chain() = M3AllocationChain(
        emptyList(), highWater, revision, previous,
        M3AllocationHistoryReceipt(records, bytesRead, M3SurfaceAllocationAuthority.HISTORY_PHASE_PEAK_BYTES),
        authoritySeen,
    )
}

private fun ByteArray.hex() = joinToString("") { "%02x".format(it) }
private fun allocationSha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
