package com.uhg0.ar_flutter_plugin_2.visibilityprotocol

import com.uhg0.ar_flutter_plugin_2.pointcloud.CoverageRendererStyleRowV1
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

/** A distinct negotiated command; never interpreted as a partial full-cut page. */
data class SparseRendererStyleContextV1(
    val baseStyleRevision: Long,
    val baseSemanticRevision: Long,
    val baseCoverageRevision: Long,
    val baseResidencyRevision: Long,
    val baseTargetRevision: Long,
    val totalRows: Int,
    val age: Int,
    val digest: ByteArray,
)

object SparseRendererStyleCommandV1 {
    const val VERSION = 2
    const val HEADER_BYTES = 256
    const val MAX_CHANGED_ROWS = 512
    fun isKind(bytes: ByteArray, offset: Int, length: Int): Boolean =
        length >= 2 && bytes[offset].toInt() == RendererStyleCommandV1.KIND &&
            bytes[offset + 1].toInt() == VERSION

    private fun header(cut: RendererStyleCutPayloadV1): ByteArray {
        val context = checkNotNull(cut.sparseContext)
        val header = ByteArray(224)
        val data = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
        data.put(0, RendererStyleCommandV1.KIND.toByte()).put(1, VERSION.toByte()).putShort(4, HEADER_BYTES.toShort())
        cut.captureGroupId.copyInto(header, 8)
        val revisions = longArrayOf(cut.bindingGeneration, cut.groupGeneration, cut.transactionId,
            cut.geometryRevision, cut.lineageRevision, cut.semanticRevision, cut.coverageRevision,
            cut.styleRevision, cut.residencyRevision, cut.targetRevision)
        revisions.forEachIndexed { index, value -> data.putLong(24 + index * 8, value) }
        data.putLong(104, cut.targetSurfaceId ?: -1L).putInt(112, cut.targetDirectionIndex ?: -1)
            .putInt(116, cut.surfaceIds.size).putLong(120, context.baseStyleRevision).putInt(128, context.totalRows)
            .putInt(132, cut.semanticRevision.toInt()).putInt(136, cut.styleRevision.toInt()).put(140, context.age.toByte())
            .putLong(144, context.baseSemanticRevision).putLong(152, context.baseCoverageRevision)
            .putLong(160, context.baseResidencyRevision).putLong(168, context.baseTargetRevision)
        return header
    }

    /** Hash the bounded canonical command without an encoded-record copy. */
    fun completeDigest(cut: RendererStyleCutPayloadV1): ByteArray {
        val header = header(cut)
        val data = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
        val hash = MessageDigest.getInstance("SHA-256")
        hash.update(header)
        for (index in cut.surfaceIds.indices) {
            data.putLong(0, cut.surfaceIds[index])
            hash.update(header, 0, 8)
            hash.update(cut.styleRows, index * 16, 16)
        }
        return hash.digest()
    }

    /** Symmetric encoder used by cross-language validation and host fixtures. */
    fun encode(cut: RendererStyleCutPayloadV1): ByteArray {
        require(cut.surfaceIds.size <= MAX_CHANGED_ROWS)
        val bytes = ByteArray(HEADER_BYTES + cut.surfaceIds.size * 24)
        header(cut).copyInto(bytes)
        completeDigest(cut).copyInto(bytes, 224)
        val data = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        for (index in cut.surfaceIds.indices) {
            val offset = HEADER_BYTES + index * 24
            data.putLong(offset, cut.surfaceIds[index])
            cut.styleRows.copyInto(bytes, offset + 8, index * 16, index * 16 + 16)
        }
        return bytes
    }

    fun decode(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size - offset): RendererStyleCutPayloadV1 {
        require(offset >= 0 && length >= HEADER_BYTES &&
            length <= RendererStyleCommandV1.MAX_PAGE_BYTES && offset <= bytes.size - length)
        require(isKind(bytes, offset, length))
        val data = ByteBuffer.wrap(bytes, offset, length).slice().order(ByteOrder.LITTLE_ENDIAN)
        require(data.getShort(2).toInt() == 0 && data.getShort(4).toInt() == HEADER_BYTES && data.getShort(6).toInt() == 0)
        val count = data.getInt(116)
        require(count in 0..MAX_CHANGED_ROWS && length == HEADER_BYTES + count * 24)
        require((141..143).all { data.get(it).toInt() == 0 } && (176..223).all { data.get(it).toInt() == 0 })
        val digest = MessageDigest.getInstance("SHA-256").apply {
            update(bytes, offset, 224)
            update(bytes, offset + HEADER_BYTES, length - HEADER_BYTES)
        }.digest()
        require((0 until 32).all { digest[it] == bytes[offset + 224 + it] })
        val revisions = LongArray(10) { data.getLong(24 + it * 8) }
        require(revisions.all { it >= 0 } && revisions[0] > 0 && revisions[7] > 0)
        val base = data.getLong(120)
        val bases = LongArray(4) { data.getLong(144 + it * 8) }
        require(base >= 0 && bases.all { it >= 0 })
        require(revisions[7] == Math.addExact(base, 1L))
        require(revisions[5] == Math.addExact(bases[0], 1L) && revisions[6] == Math.addExact(bases[1], 1L) &&
            revisions[8] == Math.addExact(bases[2], 1L) && revisions[9] == Math.addExact(bases[3], 1L))
        val total = data.getInt(128)
        val age = data.get(140).toInt() and 255
        require(total in 0..RendererStyleCommandV1.MAX_ROWS && count <= total && age in 0..3)
        val semanticGeneration = data.getInt(132).toLong() and 0xffffffffL
        val styleGeneration = data.getInt(136).toLong() and 0xffffffffL
        require(semanticGeneration == revisions[5] && styleGeneration == revisions[7])
        val targetId = data.getLong(104)
        val direction = data.getInt(112)
        require((targetId == -1L && direction == -1) || (targetId in 1 until 0x1_0000_0000L && direction in 0..23))
        val ids = LongArray(count)
        val rows = ByteArray(count * 16)
        for (index in 0 until count) {
            val position = HEADER_BYTES + index * 24
            ids[index] = data.getLong(position)
            require(ids[index] in 1 until 0x1_0000_0000L && (index == 0 || ids[index] > ids[index - 1]))
            bytes.copyInto(rows, index * 16, offset + position + 8, offset + position + 24)
            val row = CoverageRendererStyleRowV1.decode(rows, index * 16)
            require(row.semanticGeneration == semanticGeneration && row.styleGeneration == styleGeneration && row.age.code == age)
        }
        return RendererStyleCutPayloadV1(bytes.copyOfRange(offset + 8, offset + 24),
            revisions[0], revisions[1], revisions[2], revisions[3], revisions[4], revisions[5],
            revisions[6], revisions[7], revisions[8], revisions[9], true, ids, rows,
            targetId.takeUnless { it == -1L }, direction.takeUnless { it == -1 },
            SparseRendererStyleContextV1(base, bases[0], bases[1], bases[2], bases[3], total, age, digest))
    }
}
