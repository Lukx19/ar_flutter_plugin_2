package com.uhg0.ar_flutter_plugin_2.m0

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Canonical reasons carried by the M0a kind-4/version-1 resync command. */
enum class M0aResyncReason(val id: Int) {
    INVALID_TRANSACTION_ORDER(1),
    INVALID_PAYLOAD_CRC(2),
    INVALID_SEMANTIC_PAYLOAD(3),
    JOURNAL_OVERFLOW(4),
    ABANDONED_STAGING(5),
    RECEIVER_RESTARTED(6),
    ;

    companion object {
        fun fromId(id: Int): M0aResyncReason =
            entries.firstOrNull { it.id == id }
                ?: throw IllegalArgumentException("Resync reason ID is invalid")
    }
}

data class M0aResyncPayloadV1(
    val lastCommittedTransactionId: Long,
    val lastCommittedGeometryRevision: Long,
    val lastCommittedLineageRevision: Long,
    val failedTransactionId: Long,
    val reason: M0aResyncReason,
    val flags: Int = 0,
    val failedResponseRequestSequence: Long = 0,
) {
    init {
        listOf(
            "lastCommittedTransactionId" to lastCommittedTransactionId,
            "lastCommittedGeometryRevision" to lastCommittedGeometryRevision,
            "lastCommittedLineageRevision" to lastCommittedLineageRevision,
            "failedTransactionId" to failedTransactionId,
            "failedResponseRequestSequence" to failedResponseRequestSequence,
        ).forEach { (name, value) -> require(value >= 0) { "$name is outside PortableOrdinal" } }
        require(flags in 0..1) { "Resync payload flags contain reserved bits" }
        require(
            failedTransactionId == 0L || failedTransactionId != lastCommittedTransactionId,
        ) { "Resync command cannot acknowledge its failed transaction" }
    }

    val failedResponseHeaderReadable: Boolean get() = flags and 1 != 0

    fun encode(): ByteArray = ByteArray(BYTE_LENGTH).also { bytes ->
        val data = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        data.putLong(0, lastCommittedTransactionId)
        data.putLong(8, lastCommittedGeometryRevision)
        data.putLong(16, lastCommittedLineageRevision)
        data.putLong(24, failedTransactionId)
        data.putShort(32, reason.id.toShort())
        data.putShort(34, flags.toShort())
        data.putInt(36, 0)
        data.putLong(40, failedResponseRequestSequence)
    }

    companion object {
        const val BYTE_LENGTH = 48

        fun decode(bytes: ByteArray): M0aResyncPayloadV1 {
            require(bytes.size == BYTE_LENGTH) { "Resync payload must contain exactly 48 bytes" }
            val data = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            require(data.getInt(36) == 0) { "Resync payload reserved bytes are non-zero" }
            return M0aResyncPayloadV1(
                lastCommittedTransactionId = data.getLong(0),
                lastCommittedGeometryRevision = data.getLong(8),
                lastCommittedLineageRevision = data.getLong(16),
                failedTransactionId = data.getLong(24),
                reason = M0aResyncReason.fromId(data.getShort(32).toInt() and 0xffff),
                flags = data.getShort(34).toInt() and 0xffff,
                failedResponseRequestSequence = data.getLong(40),
            )
        }
    }
}

/** Kind-4/version-1 envelope followed by the canonical 48-byte payload. */
data class M0aResyncCommandV1(val payload: M0aResyncPayloadV1) {
    fun encode(): ByteArray = ByteArray(BYTE_LENGTH).also { bytes ->
        bytes[0] = KIND.toByte()
        bytes[1] = VERSION.toByte()
        payload.encode().copyInto(bytes, 2)
    }

    companion object {
        const val KIND = 4
        const val VERSION = 1
        const val BYTE_LENGTH = 2 + M0aResyncPayloadV1.BYTE_LENGTH

        fun decode(bytes: ByteArray): M0aResyncCommandV1 {
            require(bytes.size == BYTE_LENGTH && bytes[0].toInt() and 0xff == KIND &&
                bytes[1].toInt() and 0xff == VERSION) {
                "Resync command must be kind 4/version 1 with a 48-byte payload"
            }
            return M0aResyncCommandV1(M0aResyncPayloadV1.decode(bytes.copyOfRange(2, BYTE_LENGTH)))
        }
    }
}
