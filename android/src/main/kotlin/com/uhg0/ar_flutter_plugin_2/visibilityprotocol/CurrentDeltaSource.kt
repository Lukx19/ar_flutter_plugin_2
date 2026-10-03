package com.uhg0.ar_flutter_plugin_2.visibilityprotocol

import java.io.ByteArrayInputStream
import java.io.InputStream
import java.io.OutputStream

/** Exact durable-journal identity of the one canonical delta requested by canonical surface. */
data class CurrentDeltaSelectorV1(
    val transactionId: Long,
    val targetGeometryRevision: Long,
    val targetLineageRevision: Long,
) {
    init {
        require(transactionId > 0 && targetGeometryRevision > 0 && targetLineageRevision > 0) {
            "Current-delta selector is outside PortableOrdinal"
        }
    }
}

/** Immutable snapshot returned across the current-delta source seam. */
class CurrentDeltaReceiptV1 private constructor(
    val selector: CurrentDeltaSelectorV1,
    val baseGeometryRevision: Long,
    bytes: ByteArray,
    commandHash: ByteArray,
    ownsSerializedBytes: Boolean,
) {
    /** Foreign callers retain their mutable arrays; snapshot them defensively. */
    constructor(
        selector: CurrentDeltaSelectorV1,
        baseGeometryRevision: Long,
        bytes: ByteArray,
        commandHash: ByteArray,
    ) : this(selector, baseGeometryRevision, bytes, commandHash, false)

    init {
        require(baseGeometryRevision >= 0 && baseGeometryRevision < selector.targetGeometryRevision) {
            "Current delta does not advance its base geometry"
        }
        require(bytes.size <= StructuralTransactionLimits.MAX_STRUCTURAL_TRANSACTION_BYTES) {
            "Current delta exceeds the structural transaction ceiling"
        }
        require(commandHash.size == COMMAND_HASH_BYTES) {
            "Current delta command hash is not SHA-256"
        }
    }

    private val immutableBytes = if (ownsSerializedBytes) bytes else bytes.copyOf()
    private val immutableCommandHash = commandHash.copyOf()

    /** A defensive copy; journal-owned storage never crosses the seam. */
    val bytes: ByteArray get() = immutableBytes.copyOf()

    /** Immutable command identity for retry-conflict qualification. */
    val commandHash: ByteArray get() = immutableCommandHash.copyOf()

    internal val byteCount: Int get() = immutableBytes.size

    /** Reading can copy bytes out, but cannot expose or modify owned storage. */
    internal fun openStream(): InputStream = ByteArrayInputStream(immutableBytes)

    /** Exact retry comparison without copying either owner's payload. */
    internal fun matchesPayloadRange(offset: Int, bytes: ByteArray): Boolean {
        if (offset < 0 || offset > immutableBytes.size || bytes.size > immutableBytes.size - offset) return false
        for (index in bytes.indices) if (immutableBytes[offset + index] != bytes[index]) return false
        return true
    }

    /** Producer chunks are fresh arrays; receipt storage remains private. */
    internal fun produceFrames(responseProfile: TransactionResponseProfileV1): List<TransactionFrameV1> =
        StructuralTransactionProducerV1.produce(
            transactionId = selector.transactionId,
            baseGeometryRevision = baseGeometryRevision,
            targetGeometryRevision = selector.targetGeometryRevision,
            targetLineageRevision = selector.targetLineageRevision,
            bytes = immutableBytes,
            responseProfile = responseProfile,
        )

    companion object {
        private const val COMMAND_HASH_BYTES = 32

        /**
         * Serialize once into a private exact-size destination. Only this writer
         * can transfer storage to a receipt; no mutable-array ownership API exists.
         * The supplied length belongs to the authenticated canonical identity.
         */
        internal fun serialize(
            selector: CurrentDeltaSelectorV1,
            baseGeometryRevision: Long,
            expectedByteCount: Long,
            commandHash: ByteArray,
            writeTo: (OutputStream) -> Unit,
        ): CurrentDeltaReceiptV1? {
            if (expectedByteCount !in 0L..StructuralTransactionLimits.MAX_STRUCTURAL_TRANSACTION_BYTES.toLong()) {
                return null
            }
            val output = ExactCurrentDeltaOutput(expectedByteCount.toInt())
            try {
                writeTo(output)
                val ownedBytes = output.finish() ?: return null
                return CurrentDeltaReceiptV1(selector, baseGeometryRevision, ownedBytes, commandHash, true)
            } catch (_: CurrentDeltaLengthMismatch) {
                return null
            } finally {
                output.discard()
            }
        }
    }
}

/** The serializer never sees this destination's backing array. */
private class ExactCurrentDeltaOutput(size: Int) : OutputStream() {
    private var bytes: ByteArray? = ByteArray(size)
    private var written = 0
    private var closed = false
    private var lengthMismatch = false

    @Synchronized override fun write(value: Int) {
        val target = writable(1)
        target[written++] = value.toByte()
    }

    @Synchronized override fun write(source: ByteArray, offset: Int, length: Int) {
        if (offset < 0 || length < 0 || offset > source.size - length) throw IndexOutOfBoundsException()
        val target = writable(length)
        source.copyInto(target, written, offset, offset + length)
        written += length
    }

    private fun writable(length: Int): ByteArray {
        check(!closed) { "Current-delta serialization is closed" }
        val target = checkNotNull(bytes)
        if (length > target.size - written) {
            lengthMismatch = true
            throw CurrentDeltaLengthMismatch()
        }
        return target
    }

    @Synchronized fun finish(): ByteArray? {
        closed = true
        val result = bytes
        bytes = null
        return result?.takeIf { !lengthMismatch && written == it.size }
    }

    @Synchronized override fun close() { closed = true }

    @Synchronized fun discard() { closed = true; bytes = null }
}

private class CurrentDeltaLengthMismatch : RuntimeException()

/**
 * Selects one named durable receipt without exposing or aggregating the journal.
 * A source retains the immutable receipt until its exact canonical acknowledgement.
 */
fun interface CurrentDeltaSourceV1 {
    fun selectCurrentDelta(selector: CurrentDeltaSelectorV1): CurrentDeltaReceiptV1?
}
