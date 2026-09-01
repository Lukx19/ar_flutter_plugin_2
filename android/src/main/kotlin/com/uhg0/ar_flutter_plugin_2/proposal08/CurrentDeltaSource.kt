package com.uhg0.ar_flutter_plugin_2.proposal08

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
class CurrentDeltaReceiptV1(
    val selector: CurrentDeltaSelectorV1,
    val baseGeometryRevision: Long,
    bytes: ByteArray,
    commandHash: ByteArray,
) {
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

    private val immutableBytes = bytes.copyOf()
    private val immutableCommandHash = commandHash.copyOf()

    /** A defensive copy; journal-owned storage never crosses the seam. */
    val bytes: ByteArray get() = immutableBytes.copyOf()

    /** Immutable command identity for retry-conflict qualification. */
    val commandHash: ByteArray get() = immutableCommandHash.copyOf()

    private companion object {
        const val COMMAND_HASH_BYTES = 32
    }
}

/**
 * Selects one named durable receipt without exposing or aggregating the journal.
 * The future canonical surface runtime adapter belongs to #106; this issue owns only the seam.
 */
fun interface CurrentDeltaSourceV1 {
    fun selectCurrentDelta(selector: CurrentDeltaSelectorV1): CurrentDeltaReceiptV1?
}
