package com.uhg0.ar_flutter_plugin_2.proposal08

enum class StructuralTransactionState {
    READY,
    SENDING_BEGIN,
    SENDING_CHUNKS,
    COMMIT_AWAITING_ACK,
    RESYNC_PENDING,
    STOPPED,
}

data class TransactionBeginV1(
    val transactionId: Long,
    val baseGeometryRevision: Long,
    val targetGeometryRevision: Long,
    val targetLineageRevision: Long,
    val chunkCount: Int,
    val totalBytes: Int,
    val payloadChecksum: Long,
)

data class TransactionBeginAcknowledgementV1(
    val transactionId: Long,
    val targetGeometryRevision: Long,
    val targetLineageRevision: Long,
)

data class TransactionChunkV1(
    val transactionId: Long,
    val chunkIndex: Int,
    val bytes: ByteArray,
    val offset: Int? = null,
) {
    override fun equals(other: Any?): Boolean = other is TransactionChunkV1 &&
        transactionId == other.transactionId && chunkIndex == other.chunkIndex &&
            offset == other.offset && bytes.contentEquals(other.bytes)

    override fun hashCode(): Int = 31 * (31 * (31 * transactionId.hashCode() + chunkIndex) + offset.hashCode()) + bytes.contentHashCode()
}

data class TransactionCommitV1(
    val transactionId: Long,
    val payloadChecksum: Long,
)

data class TransactionAcknowledgementV1(
    val transactionId: Long,
    val geometryRevision: Long,
    val lineageRevision: Long,
)

sealed interface TransactionFrameV1

data class TransactionBeginFrameV1(val value: TransactionBeginV1) : TransactionFrameV1

data class TransactionChunkFrameV1(val value: TransactionChunkV1) : TransactionFrameV1

data class TransactionCommitFrameV1(val value: TransactionCommitV1) : TransactionFrameV1

/**
 * Capacity rules for one logical structural delta. These are deliberately
 * separate from the 16 KiB request packet ceiling: a pull request stays small
 * while one committed native delta may span many bounded responses.
 */
object StructuralTransactionLimits {
    const val MAX_STRUCTURAL_TRANSACTION_BYTES = 1_048_576
    const val MAX_CHUNK_COUNT = 0xffff
    const val CHUNK_METADATA_BYTES = 12

    val ordinaryChunkPayloadBytes: Int
        get() = chunkPayloadBytesForResponseCeiling(PacketCodec.responseMaximumBytes)

    val catchUpChunkPayloadBytes: Int
        get() = chunkPayloadBytesForResponseCeiling(PacketCodec.catchUpMaximumBytes)

    fun chunkPayloadBytesForResponseCeiling(responseCeilingBytes: Int): Int {
        require(responseCeilingBytes in PacketCodec.responseMinimumBytes..PacketCodec.catchUpMaximumBytes) {
            "Response ceiling is outside the negotiated range"
        }
        val payloadBytes = responseCeilingBytes.toLong() -
            PacketCodec.responseHeaderBytes - CHUNK_METADATA_BYTES
        require(payloadBytes in 1..MAX_CHUNK_COUNT.toLong()) {
            "Response ceiling cannot carry a bounded CHUNK"
        }
        return payloadBytes.toInt()
    }

    fun chunkCount(totalBytes: Int, responseCeilingBytes: Int): Int {
        require(totalBytes in 0..MAX_STRUCTURAL_TRANSACTION_BYTES) {
            "Transaction bytes exceed the structural transaction ceiling"
        }
        if (totalBytes == 0) return 0
        val stride = chunkPayloadBytesForResponseCeiling(responseCeilingBytes).toLong()
        val count = (totalBytes.toLong() + stride - 1L) / stride
        require(count in 1..MAX_CHUNK_COUNT.toLong()) { "CHUNK count exceeds UInt16" }
        return count.toInt()
    }

    fun frameCount(totalBytes: Int, responseCeilingBytes: Int): Int =
        Math.addExact(chunkCount(totalBytes, responseCeilingBytes), 2)
}

/** Immutable response framing negotiated before a transaction is queued. */
data class TransactionResponseProfileV1(
    val responseCeilingBytes: Int,
) {
    val chunkPayloadBytes: Int =
        StructuralTransactionLimits.chunkPayloadBytesForResponseCeiling(responseCeilingBytes)

    fun chunkCount(totalBytes: Int): Int =
        StructuralTransactionLimits.chunkCount(totalBytes, responseCeilingBytes)

    fun frameCount(totalBytes: Int): Int =
        StructuralTransactionLimits.frameCount(totalBytes, responseCeilingBytes)

    companion object {
        val ordinary = TransactionResponseProfileV1(PacketCodec.responseMaximumBytes)
        val catchUp = TransactionResponseProfileV1(PacketCodec.catchUpMaximumBytes)

        fun forChunkPayloadBytes(maximumChunkBytes: Int): TransactionResponseProfileV1 {
            require(maximumChunkBytes in
                StructuralTransactionLimits.chunkPayloadBytesForResponseCeiling(
                    PacketCodec.responseMinimumBytes,
                )..StructuralTransactionLimits.catchUpChunkPayloadBytes) {
                "maximumChunkBytes is outside the encodable response range"
            }
            return TransactionResponseProfileV1(
                Math.addExact(
                    Math.addExact(maximumChunkBytes, PacketCodec.responseHeaderBytes),
                    StructuralTransactionLimits.CHUNK_METADATA_BYTES,
                ),
            )
        }
    }
}

object StructuralTransactionProducerV1 {
    fun produce(
        transactionId: Long,
        baseGeometryRevision: Long,
        targetGeometryRevision: Long,
        targetLineageRevision: Long,
        bytes: ByteArray,
        responseProfile: TransactionResponseProfileV1 = TransactionResponseProfileV1.ordinary,
    ): List<TransactionFrameV1> {
        require(bytes.size <= StructuralTransactionLimits.MAX_STRUCTURAL_TRANSACTION_BYTES) {
            "Transaction bytes exceed the structural transaction ceiling"
        }
        val maximumChunkBytes = responseProfile.chunkPayloadBytes
        val chunkCount = responseProfile.chunkCount(bytes.size)
        val checksum = PacketCodec.crc32Payload(bytes)
        val frames = mutableListOf<TransactionFrameV1>(
            TransactionBeginFrameV1(
                TransactionBeginV1(
                    transactionId,
                    baseGeometryRevision,
                    targetGeometryRevision,
                    targetLineageRevision,
                    chunkCount,
                    bytes.size,
                    checksum,
                ),
            ),
        )
        var offset = 0
        var index = 0
        while (offset < bytes.size) {
            val end = minOf(offset + maximumChunkBytes, bytes.size)
            frames += TransactionChunkFrameV1(
                TransactionChunkV1(
                    transactionId,
                    index,
                    bytes.copyOfRange(offset, end),
                    offset,
                ),
            )
            offset = end
            index++
        }
        frames += TransactionCommitFrameV1(TransactionCommitV1(transactionId, checksum))
        return frames.toList()
    }

}

/** Bounded receiver staging with atomic publication after COMMIT acknowledgement. */
class StructuralTransactionReceiverV1(
    val maximumStagedBytes: Int = StructuralTransactionLimits.MAX_STRUCTURAL_TRANSACTION_BYTES,
) {
    init {
        require(maximumStagedBytes in 1..StructuralTransactionLimits.MAX_STRUCTURAL_TRANSACTION_BYTES) {
            "maximumStagedBytes is outside the structural transaction range"
        }
    }

    var state: StructuralTransactionState = StructuralTransactionState.READY
        private set
    var visibleTransactionId: Long = 0
        private set
    var visibleGeometryRevision: Long = 0
        private set
    var visibleLineageRevision: Long = 0
        private set
    val visibleBytes: ByteArray get() = visible.copyOf()
    val stagedBytes: Int get() = stagedByteCount
    val nextChunkIndex: Int get() = nextChunk

    private var begin: TransactionBeginV1? = null
    private var staging = byteArrayOf()
    private var stagedByteCount = 0
    private var nextChunk = 0
    private var nextChunkOffset = 0
    private val receivedChunks = mutableListOf<ReceivedChunkV1>()
    private var pendingCommit: TransactionAcknowledgementV1? = null
    private var lastAcknowledgement: TransactionAcknowledgementV1? = null
    private var visible = byteArrayOf()

    fun begin(value: TransactionBeginV1) {
        requireState(StructuralTransactionState.READY)
        validateBegin(value)
        begin = value
        staging = ByteArray(value.totalBytes)
        stagedByteCount = 0
        nextChunk = 0
        nextChunkOffset = 0
        receivedChunks.clear()
        state = StructuralTransactionState.SENDING_BEGIN
    }

    fun acknowledgeBegin(value: TransactionBeginAcknowledgementV1) {
        requireState(StructuralTransactionState.SENDING_BEGIN)
        val current = requireNotNull(begin)
        require(
            value.transactionId == current.transactionId &&
                value.targetGeometryRevision == current.targetGeometryRevision &&
                value.targetLineageRevision == current.targetLineageRevision,
        ) { "BEGIN acknowledgement does not match the transaction" }
        state = StructuralTransactionState.SENDING_CHUNKS
    }

    fun chunk(value: TransactionChunkV1) {
        requireState(StructuralTransactionState.SENDING_CHUNKS)
        val current = requireNotNull(begin)
        require(value.transactionId == current.transactionId) { "Chunk transaction identity is invalid" }
        require(value.bytes.isNotEmpty()) { "Chunk must not be empty" }
        if (value.chunkIndex < nextChunk) {
            val received = receivedChunks.getOrNull(value.chunkIndex)
                ?: error("Duplicate CHUNK ordinal is not retained")
            val offset = value.offset ?: received.offset
            require(offset == received.offset && value.bytes.size == received.length) {
                "Duplicate CHUNK metadata does not match"
            }
            require(staging.copyOfRange(offset, offset + value.bytes.size).contentEquals(value.bytes)) {
                "Duplicate CHUNK bytes do not match"
            }
            return
        }
        require(value.chunkIndex == nextChunk && (value.offset == null || value.offset == nextChunkOffset)) {
            "Chunk ordinal or offset is invalid"
        }
        require(stagedByteCount <= maximumStagedBytes - value.bytes.size &&
            stagedByteCount + value.bytes.size <= current.totalBytes) {
            "Chunk exceeds the bounded transaction totals"
        }
        value.bytes.copyInto(staging, nextChunkOffset)
        receivedChunks += ReceivedChunkV1(nextChunkOffset, value.bytes.size)
        nextChunk++
        nextChunkOffset += value.bytes.size
        stagedByteCount += value.bytes.size
    }

    fun commit(value: TransactionCommitV1) {
        requireState(StructuralTransactionState.SENDING_CHUNKS)
        val current = requireNotNull(begin)
        require(
            value.transactionId == current.transactionId &&
                value.payloadChecksum == current.payloadChecksum &&
                nextChunk == current.chunkCount && stagedByteCount == current.totalBytes,
        ) { "COMMIT does not match the complete staged transaction" }
        require(PacketCodec.crc32Payload(staging) == current.payloadChecksum) {
            "COMMIT payload checksum is invalid"
        }
        pendingCommit = TransactionAcknowledgementV1(
            transactionId = current.transactionId,
            geometryRevision = current.targetGeometryRevision,
            lineageRevision = current.targetLineageRevision,
        )
        state = StructuralTransactionState.COMMIT_AWAITING_ACK
    }

    fun acknowledgeCommit(value: TransactionAcknowledgementV1) {
        if (state == StructuralTransactionState.READY && lastAcknowledgement == value) return
        requireState(StructuralTransactionState.COMMIT_AWAITING_ACK)
        require(pendingCommit == value) { "COMMIT acknowledgement does not match the transaction" }
        visible = staging.copyOf()
        visibleTransactionId = value.transactionId
        visibleGeometryRevision = value.geometryRevision
        visibleLineageRevision = value.lineageRevision
        lastAcknowledgement = value
        clearStaging()
        state = StructuralTransactionState.READY
    }

    fun abandon() {
        if (state == StructuralTransactionState.STOPPED) return
        clearStaging()
        state = StructuralTransactionState.RESYNC_PENDING
    }

    fun resync(command: ResyncCommandV1) {
        requireState(StructuralTransactionState.RESYNC_PENDING)
        val payload = command.payload
        require(
            payload.lastCommittedTransactionId == visibleTransactionId &&
                payload.lastCommittedGeometryRevision == visibleGeometryRevision &&
                payload.lastCommittedLineageRevision == visibleLineageRevision,
        ) { "Resync baseline does not match the visible transaction" }
        state = StructuralTransactionState.READY
    }

    fun stop() {
        clearStaging()
        state = StructuralTransactionState.STOPPED
    }

    private fun validateBegin(value: TransactionBeginV1) {
        require(value.baseGeometryRevision >= 0) {
            "baseGeometryRevision is outside PortableOrdinal"
        }
        listOf(
            "transactionId" to value.transactionId,
            "targetGeometryRevision" to value.targetGeometryRevision,
            "targetLineageRevision" to value.targetLineageRevision,
        ).forEach { (name, number) -> require(number > 0) { "$name is outside PortableOrdinal" } }
        require(value.chunkCount in 0..StructuralTransactionLimits.MAX_CHUNK_COUNT &&
            value.totalBytes in 0..maximumStagedBytes &&
            value.totalBytes <= StructuralTransactionLimits.MAX_STRUCTURAL_TRANSACTION_BYTES) {
            "BEGIN totals are outside the bounded transaction range"
        }
        require(value.payloadChecksum in 0..0xffff_ffffL) { "BEGIN checksum is outside UInt32" }
        require(value.chunkCount == 0 && value.totalBytes == 0 || value.chunkCount != 0 && value.totalBytes != 0) {
            "BEGIN chunk count and total bytes are inconsistent"
        }
    }

    private fun requireState(expected: StructuralTransactionState) {
        require(state == expected) { "Transaction state is $state; expected $expected" }
    }

    private fun clearStaging() {
        begin = null
        staging = byteArrayOf()
        stagedByteCount = 0
        nextChunk = 0
        nextChunkOffset = 0
        receivedChunks.clear()
        pendingCommit = null
    }

    private data class ReceivedChunkV1(val offset: Int, val length: Int)

}
