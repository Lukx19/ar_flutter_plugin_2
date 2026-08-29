package com.uhg0.ar_flutter_plugin_2.visibilitygrid

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
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
)

internal object M3SurfaceAllocationAuthority {
    private const val UINT32_HIGH_WATER = 0x1_0000_0000L

    fun legacyFile(directory: File, group: M3SurfaceGroup) =
        File(directory, "m3-surface-${group.hash.hex()}.ledger")

    fun readLegacy(directory: File, group: M3SurfaceGroup): List<M3AllocationRecord> {
        val file = legacyFile(directory, group)
        if (!file.exists()) return emptyList()
        val bytes = file.readBytes()
        require(bytes.size % M3AllocationRecord.ENCODED_BYTES == 0)
        return bytes.asList().chunked(M3AllocationRecord.ENCODED_BYTES)
            .map { M3AllocationRecord.decode(it.toByteArray()) }
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
        val identities = hashSetOf<String>()
        records.sortedBy { it.revision }.forEach { record ->
            require(record.revision == ++revision)
            require(record.start == highWater)
            // A zero-range refinement still burns a unique transaction revision.
            require(record.endExclusive in record.start..UINT32_HIGH_WATER)
            require(record.groupHash.contentEquals(group.hash))
            require(record.commandHash.size == 32 && identities.add(record.commandHash.hex()))
            require(record.fingerprint.size == 32)
            require(record.previousHash.contentEquals(previous))
            require(!record.recordHash.contentEquals(ByteArray(32)))
            highWater = record.endExclusive
            previous = record.recordHash
        }
        return M3AllocationChain(records.sortedBy { it.revision }, highWater, revision, previous)
    }
}

private fun ByteArray.hex() = joinToString("") { "%02x".format(it) }
private fun allocationSha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
