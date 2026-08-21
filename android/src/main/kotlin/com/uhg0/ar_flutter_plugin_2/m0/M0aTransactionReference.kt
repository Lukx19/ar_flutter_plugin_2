package com.uhg0.ar_flutter_plugin_2.m0

import java.io.ByteArrayOutputStream

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

object M0aStructuralTransactionProducerV1 {
    fun produce(
        transactionId: Long,
        baseGeometryRevision: Long,
        targetGeometryRevision: Long,
        targetLineageRevision: Long,
        bytes: ByteArray,
        maximumChunkBytes: Int = 1024,
    ): List<M0aTransactionFrameV1> {
        require(maximumChunkBytes in 1..0xffff) { "maximumChunkBytes is outside the bounded transaction range" }
        require(bytes.size <= M0aPacketCodec.requestCeilingBytes) { "Transaction bytes exceed the request ceiling" }
        val chunkCount = if (bytes.isEmpty()) 0 else (bytes.size + maximumChunkBytes - 1) / maximumChunkBytes
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
    val maximumStagedBytes: Int = M0aPacketCodec.requestCeilingBytes,
) {
    init {
        require(maximumStagedBytes > 0) { "maximumStagedBytes must be positive" }
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
    val stagedBytes: Int get() = staging.size()
    val nextChunkIndex: Int get() = nextChunk

    private var begin: M0aTransactionBeginV1? = null
    private var staging = ByteArrayOutputStream()
    private var nextChunk = 0
    private var nextChunkOffset = 0
    private var pendingCommit: M0aTransactionAcknowledgementV1? = null
    private var lastAcknowledgement: M0aTransactionAcknowledgementV1? = null
    private var visible = byteArrayOf()

    fun begin(value: M0aTransactionBeginV1) {
        requireState(M0aStructuralTransactionState.READY)
        validateBegin(value)
        begin = value
        staging = ByteArrayOutputStream(value.totalBytes)
        nextChunk = 0
        nextChunkOffset = 0
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
        require(
            value.transactionId == current.transactionId &&
                value.chunkIndex == nextChunk &&
                (value.offset == null || value.offset == nextChunkOffset),
        ) {
            "Chunk transaction identity or ordinal is invalid"
        }
        require(value.bytes.isNotEmpty()) { "Chunk must not be empty" }
        require(stagedBytes <= maximumStagedBytes - value.bytes.size && stagedBytes + value.bytes.size <= current.totalBytes) {
            "Chunk exceeds the bounded transaction totals"
        }
        staging.write(value.bytes)
        nextChunk++
        nextChunkOffset += value.bytes.size
    }

    fun commit(value: M0aTransactionCommitV1) {
        requireState(M0aStructuralTransactionState.SENDING_CHUNKS)
        val current = requireNotNull(begin)
        require(
            value.transactionId == current.transactionId &&
                value.payloadChecksum == current.payloadChecksum &&
                nextChunk == current.chunkCount && stagedBytes == current.totalBytes,
        ) { "COMMIT does not match the complete staged transaction" }
        val bytes = staging.toByteArray()
        require(M0aPacketCodec.crc32Payload(bytes) == current.payloadChecksum) {
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
        visible = staging.toByteArray()
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
        require(value.chunkCount in 0..0xffff && value.totalBytes in 0..maximumStagedBytes) {
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
        staging = ByteArrayOutputStream()
        nextChunk = 0
        nextChunkOffset = 0
        pendingCommit = null
    }

}
