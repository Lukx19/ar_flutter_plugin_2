package com.uhg0.ar_flutter_plugin_2.m0

enum class M0aStructuralTransactionState {
    READY,
    SENDING_BEGIN,
    SENDING_CHUNKS,
    COMMIT_AWAITING_ACK,
    RESYNC_PENDING,
    STOPPED,
}

data class M0aTransactionBeginV1(
    val transactionId: Long,
    val baseGeometryRevision: Long,
    val targetGeometryRevision: Long,
    val targetLineageRevision: Long,
    val chunkCount: Int,
    val totalBytes: Int,
    val payloadChecksum: Long,
)

data class M0aTransactionBeginAcknowledgementV1(
    val transactionId: Long,
    val targetGeometryRevision: Long,
    val targetLineageRevision: Long,
)

data class M0aTransactionChunkV1(
    val transactionId: Long,
    val chunkIndex: Int,
    val bytes: ByteArray,
    val offset: Int? = null,
) {
    override fun equals(other: Any?): Boolean = other is M0aTransactionChunkV1 &&
        transactionId == other.transactionId && chunkIndex == other.chunkIndex &&
            offset == other.offset && bytes.contentEquals(other.bytes)

    override fun hashCode(): Int = 31 * (31 * (31 * transactionId.hashCode() + chunkIndex) + offset.hashCode()) + bytes.contentHashCode()
}

data class M0aTransactionCommitV1(
    val transactionId: Long,
    val payloadChecksum: Long,
)

data class M0aTransactionAcknowledgementV1(
    val transactionId: Long,
    val geometryRevision: Long,
    val lineageRevision: Long,
)

sealed interface M0aTransactionFrameV1

data class M0aTransactionBeginFrameV1(val value: M0aTransactionBeginV1) : M0aTransactionFrameV1

data class M0aTransactionChunkFrameV1(val value: M0aTransactionChunkV1) : M0aTransactionFrameV1

data class M0aTransactionCommitFrameV1(val value: M0aTransactionCommitV1) : M0aTransactionFrameV1

/**
 * Capacity rules for one logical structural delta. These are deliberately
 * separate from the 16 KiB request packet ceiling: a pull request stays small
 * while one committed native delta may span many bounded responses.
 */
object M0aStructuralTransactionLimits {
    const val MAX_STRUCTURAL_TRANSACTION_BYTES = 1_048_576
    const val MAX_CHUNK_COUNT = 0xffff
    const val CHUNK_METADATA_BYTES = 12

    val ordinaryChunkPayloadBytes: Int
        get() = chunkPayloadBytesForResponseCeiling(M0aPacketCodec.responseMaximumBytes)

    val catchUpChunkPayloadBytes: Int
        get() = chunkPayloadBytesForResponseCeiling(M0aPacketCodec.catchUpMaximumBytes)

    fun chunkPayloadBytesForResponseCeiling(responseCeilingBytes: Int): Int {
        require(responseCeilingBytes in M0aPacketCodec.responseMinimumBytes..M0aPacketCodec.catchUpMaximumBytes) {
            "Response ceiling is outside the negotiated range"
        }
        val payloadBytes = responseCeilingBytes.toLong() -
            M0aPacketCodec.responseHeaderBytes - CHUNK_METADATA_BYTES
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

object M0aStructuralTransactionProducerV1 {
    fun produce(
        transactionId: Long,
        baseGeometryRevision: Long,
        targetGeometryRevision: Long,
        targetLineageRevision: Long,
        bytes: ByteArray,
        maximumChunkBytes: Int = M0aStructuralTransactionLimits.ordinaryChunkPayloadBytes,
    ): List<M0aTransactionFrameV1> {
        require(maximumChunkBytes in 1..M0aStructuralTransactionLimits.MAX_CHUNK_COUNT) {
            "maximumChunkBytes is outside the bounded transaction range"
        }
        require(bytes.size <= M0aStructuralTransactionLimits.MAX_STRUCTURAL_TRANSACTION_BYTES) {
            "Transaction bytes exceed the structural transaction ceiling"
        }
        val chunkCount = if (bytes.isEmpty()) 0 else {
            val count = (bytes.size.toLong() + maximumChunkBytes - 1L) / maximumChunkBytes
            require(count <= M0aStructuralTransactionLimits.MAX_CHUNK_COUNT) { "CHUNK count exceeds UInt16" }
            count.toInt()
        }
        val checksum = M0aPacketCodec.crc32Payload(bytes)
        val frames = mutableListOf<M0aTransactionFrameV1>(
            M0aTransactionBeginFrameV1(
                M0aTransactionBeginV1(
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
            frames += M0aTransactionChunkFrameV1(
                M0aTransactionChunkV1(
                    transactionId,
                    index,
                    bytes.copyOfRange(offset, end),
                    offset,
                ),
            )
            offset = end
            index++
        }
        frames += M0aTransactionCommitFrameV1(M0aTransactionCommitV1(transactionId, checksum))
        return frames.toList()
    }

}

/** Bounded receiver staging with atomic publication after COMMIT acknowledgement. */
class M0aStructuralTransactionReceiverV1(
    val maximumStagedBytes: Int = M0aStructuralTransactionLimits.MAX_STRUCTURAL_TRANSACTION_BYTES,
) {
    init {
        require(maximumStagedBytes in 1..M0aStructuralTransactionLimits.MAX_STRUCTURAL_TRANSACTION_BYTES) {
            "maximumStagedBytes is outside the structural transaction range"
        }
    }

    var state: M0aStructuralTransactionState = M0aStructuralTransactionState.READY
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

    private var begin: M0aTransactionBeginV1? = null
    private var staging = byteArrayOf()
    private var stagedByteCount = 0
    private var nextChunk = 0
    private var nextChunkOffset = 0
    private val receivedChunks = mutableListOf<M0aReceivedChunkV1>()
    private var pendingCommit: M0aTransactionAcknowledgementV1? = null
    private var lastAcknowledgement: M0aTransactionAcknowledgementV1? = null
    private var visible = byteArrayOf()

    fun begin(value: M0aTransactionBeginV1) {
        requireState(M0aStructuralTransactionState.READY)
        validateBegin(value)
        begin = value
        staging = ByteArray(value.totalBytes)
        stagedByteCount = 0
        nextChunk = 0
        nextChunkOffset = 0
        receivedChunks.clear()
        state = M0aStructuralTransactionState.SENDING_BEGIN
    }

    fun acknowledgeBegin(value: M0aTransactionBeginAcknowledgementV1) {
        requireState(M0aStructuralTransactionState.SENDING_BEGIN)
        val current = requireNotNull(begin)
        require(
            value.transactionId == current.transactionId &&
                value.targetGeometryRevision == current.targetGeometryRevision &&
                value.targetLineageRevision == current.targetLineageRevision,
        ) { "BEGIN acknowledgement does not match the transaction" }
        state = M0aStructuralTransactionState.SENDING_CHUNKS
    }

    fun chunk(value: M0aTransactionChunkV1) {
        requireState(M0aStructuralTransactionState.SENDING_CHUNKS)
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
        receivedChunks += M0aReceivedChunkV1(nextChunkOffset, value.bytes.size)
        nextChunk++
        nextChunkOffset += value.bytes.size
        stagedByteCount += value.bytes.size
    }

    fun commit(value: M0aTransactionCommitV1) {
        requireState(M0aStructuralTransactionState.SENDING_CHUNKS)
        val current = requireNotNull(begin)
        require(
            value.transactionId == current.transactionId &&
                value.payloadChecksum == current.payloadChecksum &&
                nextChunk == current.chunkCount && stagedByteCount == current.totalBytes,
        ) { "COMMIT does not match the complete staged transaction" }
        require(M0aPacketCodec.crc32Payload(staging) == current.payloadChecksum) {
            "COMMIT payload checksum is invalid"
        }
        pendingCommit = M0aTransactionAcknowledgementV1(
            transactionId = current.transactionId,
            geometryRevision = current.targetGeometryRevision,
            lineageRevision = current.targetLineageRevision,
        )
        state = M0aStructuralTransactionState.COMMIT_AWAITING_ACK
    }

    fun acknowledgeCommit(value: M0aTransactionAcknowledgementV1) {
        if (state == M0aStructuralTransactionState.READY && lastAcknowledgement == value) return
        requireState(M0aStructuralTransactionState.COMMIT_AWAITING_ACK)
        require(pendingCommit == value) { "COMMIT acknowledgement does not match the transaction" }
        visible = staging.copyOf()
        visibleTransactionId = value.transactionId
        visibleGeometryRevision = value.geometryRevision
        visibleLineageRevision = value.lineageRevision
        lastAcknowledgement = value
        clearStaging()
        state = M0aStructuralTransactionState.READY
    }

    fun abandon() {
        if (state == M0aStructuralTransactionState.STOPPED) return
        clearStaging()
        state = M0aStructuralTransactionState.RESYNC_PENDING
    }

    fun resync(command: M0aResyncCommandV1) {
        requireState(M0aStructuralTransactionState.RESYNC_PENDING)
        val payload = command.payload
        require(
            payload.lastCommittedTransactionId == visibleTransactionId &&
                payload.lastCommittedGeometryRevision == visibleGeometryRevision &&
                payload.lastCommittedLineageRevision == visibleLineageRevision,
        ) { "Resync baseline does not match the visible transaction" }
        state = M0aStructuralTransactionState.READY
    }

    fun stop() {
        clearStaging()
        state = M0aStructuralTransactionState.STOPPED
    }

    private fun validateBegin(value: M0aTransactionBeginV1) {
        require(value.baseGeometryRevision >= 0) {
            "baseGeometryRevision is outside PortableOrdinal"
        }
        listOf(
            "transactionId" to value.transactionId,
            "targetGeometryRevision" to value.targetGeometryRevision,
            "targetLineageRevision" to value.targetLineageRevision,
        ).forEach { (name, number) -> require(number > 0) { "$name is outside PortableOrdinal" } }
        require(value.chunkCount in 0..M0aStructuralTransactionLimits.MAX_CHUNK_COUNT &&
            value.totalBytes in 0..maximumStagedBytes &&
            value.totalBytes <= M0aStructuralTransactionLimits.MAX_STRUCTURAL_TRANSACTION_BYTES) {
            "BEGIN totals are outside the bounded transaction range"
        }
        require(value.payloadChecksum in 0..0xffff_ffffL) { "BEGIN checksum is outside UInt32" }
        require(value.chunkCount == 0 && value.totalBytes == 0 || value.chunkCount != 0 && value.totalBytes != 0) {
            "BEGIN chunk count and total bytes are inconsistent"
        }
    }

    private fun requireState(expected: M0aStructuralTransactionState) {
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

    private data class M0aReceivedChunkV1(val offset: Int, val length: Int)

}
