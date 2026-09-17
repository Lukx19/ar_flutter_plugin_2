package com.uhg0.ar_flutter_plugin_2.visibilityprotocol

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

/** Complete renderer-style cut carried by one or more kind-5 pages. */
data class RendererStyleCutPayloadV1(
    val captureGroupId: ByteArray,
    val bindingGeneration: Long,
    val groupGeneration: Long,
    val transactionId: Long,
    val geometryRevision: Long,
    val lineageRevision: Long,
    val semanticRevision: Long,
    val coverageRevision: Long,
    val styleRevision: Long,
    val residencyRevision: Long,
    val targetRevision: Long,
    val reset: Boolean,
    val surfaceIds: LongArray,
    val styleRows: ByteArray,
    val targetSurfaceId: Long? = null,
    val targetDirectionIndex: Int? = null,
) {
    init {
        require(captureGroupId.size == 16) { "captureGroupId must contain exactly 16 bytes" }
        require(bindingGeneration > 0) { "bindingGeneration must be positive" }
        listOf(
            "bindingGeneration" to bindingGeneration,
            "groupGeneration" to groupGeneration,
            "transactionId" to transactionId,
            "geometryRevision" to geometryRevision,
            "lineageRevision" to lineageRevision,
            "semanticRevision" to semanticRevision,
            "coverageRevision" to coverageRevision,
            "styleRevision" to styleRevision,
            "residencyRevision" to residencyRevision,
            "targetRevision" to targetRevision,
        ).forEach { (name, value) -> require(value >= 0) { "$name is outside PortableOrdinal" } }
        require(styleRevision > 0) { "styleRevision must advance from zero" }
        require(surfaceIds.size <= RendererStyleCommandV1.MAX_ROWS)
        require(styleRows.size == surfaceIds.size * RendererStyleCommandV1.STYLE_ROW_BYTES) {
            "styleRows must contain one 16-byte row per surface ID"
        }
        require((1 until surfaceIds.size).all { index -> surfaceIds[index - 1] < surfaceIds[index] }) {
            "surface IDs must be strictly ascending"
        }
        require(surfaceIds.all { it >= 0 }) { "surface IDs are outside PortableOrdinal" }
        require(targetSurfaceId == null || targetSurfaceId >= 0) {
            "targetSurfaceId is outside PortableOrdinal"
        }
        require(targetDirectionIndex == null || targetDirectionIndex in 0..23) {
            "targetDirectionIndex is outside the renderer direction range"
        }
        require((targetDirectionIndex == null) == (targetSurfaceId == null)) {
            "Renderer-style target fields must be paired"
        }
    }

    /** Canonical complete-cut bytes; page sizing and page ordinal are excluded. */
    fun canonicalBytes(): ByteArray {
        val bytes = ByteArray(CANONICAL_HEADER_BYTES + surfaceIds.size * RECORD_BYTES)
        val data = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        data.put(captureGroupId)
        data.putLong(bindingGeneration)
        data.putLong(groupGeneration)
        data.putLong(transactionId)
        data.putLong(geometryRevision)
        data.putLong(lineageRevision)
        data.putLong(semanticRevision)
        data.putLong(coverageRevision)
        data.putLong(styleRevision)
        data.putLong(residencyRevision)
        data.putLong(targetRevision)
        data.put(if (reset) 1 else 0)
        data.put(0)
        data.putShort(0)
        data.putLong(targetSurfaceId ?: NO_TARGET_SURFACE_ID)
        data.putInt(targetDirectionIndex ?: NO_TARGET_DIRECTION)
        data.putInt(surfaceIds.size)
        surfaceIds.forEachIndexed { index, surfaceId ->
            data.putLong(surfaceId)
            data.put(styleRows, index * RendererStyleCommandV1.STYLE_ROW_BYTES,
                RendererStyleCommandV1.STYLE_ROW_BYTES)
        }
        return bytes
    }

    fun completeDigest(): ByteArray = sha256(canonicalBytes())

    private companion object {
        const val CANONICAL_HEADER_BYTES = 116
        const val RECORD_BYTES = 8 + RendererStyleCommandV1.STYLE_ROW_BYTES
        const val NO_TARGET_SURFACE_ID = -1L
        const val NO_TARGET_DIRECTION = -1

        fun sha256(bytes: ByteArray): ByteArray =
            MessageDigest.getInstance("SHA-256").digest(bytes)
    }
}

/** Versioned kind-5 command pages embedded in VGR2 commandBytes. */
object RendererStyleCommandV1 {
    const val KIND = 5
    const val VERSION = 1
    const val RESET_FLAG = 1
    const val FINAL_FLAG = 1 shl 1
    const val HEADER_BYTES = 176
    const val STYLE_ROW_BYTES = 16
    const val RECORD_BYTES = 8 + STYLE_ROW_BYTES
    const val MAX_ROWS = 100_000
    const val MAX_PAGE_BYTES = PacketCodec.requestCeilingBytes - PacketCodec.requestHeaderBytes

    data class Page(
        val kind: Int,
        val version: Int,
        val flags: Int,
        val captureGroupId: ByteArray,
        val bindingGeneration: Long,
        val groupGeneration: Long,
        val transactionId: Long,
        val geometryRevision: Long,
        val lineageRevision: Long,
        val semanticRevision: Long,
        val coverageRevision: Long,
        val styleRevision: Long,
        val residencyRevision: Long,
        val targetRevision: Long,
        val pageIndex: Int,
        val pageCount: Int,
        val totalRows: Int,
        val surfaceIds: LongArray,
        val styleRows: ByteArray,
        val targetSurfaceId: Long?,
        val targetDirectionIndex: Int?,
        val completeDigest: ByteArray,
        val bytes: ByteArray,
    ) {
        val reset: Boolean get() = flags and RESET_FLAG != 0
        val isFinal: Boolean get() = flags and FINAL_FLAG != 0
        val rowCount: Int get() = surfaceIds.size
    }

    fun encodePages(
        cut: RendererStyleCutPayloadV1,
        maxPageBytes: Int = MAX_PAGE_BYTES,
    ): List<ByteArray> {
        require(maxPageBytes in HEADER_BYTES + RECORD_BYTES..MAX_PAGE_BYTES) {
            "maxPageBytes must fit one renderer-style row and stay within VGR2"
        }
        val rowsPerPage = ((maxPageBytes - HEADER_BYTES) / RECORD_BYTES).coerceAtLeast(1)
        val pageCount = ((cut.surfaceIds.size + rowsPerPage - 1) / rowsPerPage).coerceAtLeast(1)
        val digest = cut.completeDigest()
        return (0 until pageCount).map { pageIndex ->
            val from = minOf(pageIndex * rowsPerPage, cut.surfaceIds.size)
            val to = minOf(from + rowsPerPage, cut.surfaceIds.size)
            val ids = cut.surfaceIds.copyOfRange(from, to)
            val styles = cut.styleRows.copyOfRange(from * STYLE_ROW_BYTES, to * STYLE_ROW_BYTES)
            encode(
                Page(
                    kind = KIND,
                    version = VERSION,
                    flags = (if (cut.reset) RESET_FLAG else 0) or
                        (if (pageIndex == pageCount - 1) FINAL_FLAG else 0),
                    captureGroupId = cut.captureGroupId,
                    bindingGeneration = cut.bindingGeneration,
                    groupGeneration = cut.groupGeneration,
                    transactionId = cut.transactionId,
                    geometryRevision = cut.geometryRevision,
                    lineageRevision = cut.lineageRevision,
                    semanticRevision = cut.semanticRevision,
                    coverageRevision = cut.coverageRevision,
                    styleRevision = cut.styleRevision,
                    residencyRevision = cut.residencyRevision,
                    targetRevision = cut.targetRevision,
                    pageIndex = pageIndex,
                    pageCount = pageCount,
                    totalRows = cut.surfaceIds.size,
                    surfaceIds = ids,
                    styleRows = styles,
                    targetSurfaceId = cut.targetSurfaceId,
                    targetDirectionIndex = cut.targetDirectionIndex,
                    completeDigest = digest,
                    bytes = byteArrayOf(),
                ),
            )
        }
    }

    fun encode(page: Page): ByteArray {
        require(page.kind == KIND && page.version == VERSION)
        require(page.flags and (RESET_FLAG or FINAL_FLAG) == page.flags)
        require(page.pageCount > 0 && page.pageIndex in 0 until page.pageCount)
        require(page.totalRows in 0..MAX_ROWS && page.rowCount <= page.totalRows)
        require(page.styleRows.size == page.rowCount * STYLE_ROW_BYTES)
        require(page.completeDigest.size == 32)
        require(page.captureGroupId.size == 16)
        require(page.bindingGeneration > 0)
        require(page.surfaceIds.isStrictlyAscending())
        val bytes = ByteArray(HEADER_BYTES + page.rowCount * RECORD_BYTES)
        val data = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        data.put(KIND.toByte()); data.put(VERSION.toByte())
        data.putShort(page.flags.toShort())
        data.putShort(HEADER_BYTES.toShort()); data.putShort(0)
        page.captureGroupId.copyInto(bytes, 8)
        data.putLong(24, page.bindingGeneration)
        data.putLong(32, page.groupGeneration)
        data.putLong(40, page.transactionId)
        data.putLong(48, page.geometryRevision)
        data.putLong(56, page.lineageRevision)
        data.putLong(64, page.semanticRevision)
        data.putLong(72, page.coverageRevision)
        data.putLong(80, page.styleRevision)
        data.putLong(88, page.residencyRevision)
        data.putLong(96, page.targetRevision)
        data.putInt(104, page.pageIndex)
        data.putInt(108, page.pageCount)
        data.putInt(112, page.totalRows)
        data.putInt(116, page.rowCount)
        data.putLong(120, page.targetSurfaceId ?: NO_TARGET_SURFACE_ID)
        data.putInt(128, page.targetDirectionIndex ?: NO_TARGET_DIRECTION)
        data.putInt(132, 0)
        data.putInt(136, 0)
        data.putInt(140, 0)
        page.completeDigest.copyInto(bytes, 144)
        var offset = HEADER_BYTES
        page.surfaceIds.forEachIndexed { index, id ->
            data.putLong(offset, id)
            page.styleRows.copyInto(bytes, offset + 8, index * STYLE_ROW_BYTES, (index + 1) * STYLE_ROW_BYTES)
            offset += RECORD_BYTES
        }
        return bytes
    }

    fun decode(bytes: ByteArray): Page {
        require(bytes.size in HEADER_BYTES..MAX_PAGE_BYTES) {
            "Renderer-style command page is outside the bounded VGR2 page range"
        }
        val data = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        require(data.get().toInt() and 0xff == KIND && data.get().toInt() and 0xff == VERSION) {
            "Renderer-style command must be kind 5/version 1"
        }
        val flags = data.short.toInt() and 0xffff
        require(flags and (RESET_FLAG or FINAL_FLAG) == flags) { "Renderer-style flags are reserved" }
        require(data.short.toInt() and 0xffff == HEADER_BYTES && data.short.toInt() == 0) {
            "Renderer-style command header is invalid"
        }
        val captureGroupId = bytes.copyOfRange(8, 24)
        val bindingGeneration = data.getLong(24)
        val groupGeneration = data.getLong(32)
        val transactionId = data.getLong(40)
        val geometryRevision = data.getLong(48)
        val lineageRevision = data.getLong(56)
        val semanticRevision = data.getLong(64)
        val coverageRevision = data.getLong(72)
        val styleRevision = data.getLong(80)
        val residencyRevision = data.getLong(88)
        val targetRevision = data.getLong(96)
        val pageIndex = data.getInt(104)
        val pageCount = data.getInt(108)
        val totalRows = data.getInt(112)
        val rowCount = data.getInt(116)
        val targetSurface = data.getLong(120)
        val targetDirection = data.getInt(128)
        require(data.getInt(132) == 0 && data.getInt(136) == 0 && data.getInt(140) == 0) {
            "Renderer-style command reserved bytes are non-zero"
        }
        val digest = bytes.copyOfRange(144, 176)
        listOf(
            "bindingGeneration" to bindingGeneration,
            "groupGeneration" to groupGeneration,
            "transactionId" to transactionId,
            "geometryRevision" to geometryRevision,
            "lineageRevision" to lineageRevision,
            "semanticRevision" to semanticRevision,
            "coverageRevision" to coverageRevision,
            "styleRevision" to styleRevision,
            "residencyRevision" to residencyRevision,
            "targetRevision" to targetRevision,
        ).forEach { (name, value) -> require(value >= 0) { "$name is outside PortableOrdinal" } }
        require(bindingGeneration > 0)
        require(styleRevision > 0)
        require(pageCount > 0 && pageIndex in 0 until pageCount)
        require(totalRows in 0..MAX_ROWS && rowCount >= 0 && rowCount <= totalRows)
        require(bytes.size == HEADER_BYTES + rowCount * RECORD_BYTES)
        require((flags and FINAL_FLAG != 0) == (pageIndex == pageCount - 1))
        require((pageIndex < pageCount - 1 && rowCount > 0) || pageIndex == pageCount - 1)
        val targetSurfaceId = targetSurface.takeUnless { it == NO_TARGET_SURFACE_ID }
        require(targetSurfaceId == null || targetSurfaceId >= 0)
        val targetDirectionIndex = targetDirection.takeUnless { it == NO_TARGET_DIRECTION }
        require(targetDirectionIndex == null || targetDirectionIndex in 0..23)
        require((targetDirectionIndex == null) == (targetSurfaceId == null))
        val ids = LongArray(rowCount)
        val styles = ByteArray(rowCount * STYLE_ROW_BYTES)
        var offset = HEADER_BYTES
        repeat(rowCount) { index ->
            ids[index] = data.getLong(offset)
            styles.copyFrom(bytes, offset + 8, index * STYLE_ROW_BYTES, STYLE_ROW_BYTES)
            offset += RECORD_BYTES
        }
        require(ids.all { it >= 0 })
        require(ids.isStrictlyAscending()) {
            "Renderer-style page IDs must be strictly ascending"
        }
        return Page(
            kind = KIND,
            version = VERSION,
            flags = flags,
            captureGroupId = captureGroupId,
            bindingGeneration = bindingGeneration,
            groupGeneration = groupGeneration,
            transactionId = transactionId,
            geometryRevision = geometryRevision,
            lineageRevision = lineageRevision,
            semanticRevision = semanticRevision,
            coverageRevision = coverageRevision,
            styleRevision = styleRevision,
            residencyRevision = residencyRevision,
            targetRevision = targetRevision,
            pageIndex = pageIndex,
            pageCount = pageCount,
            totalRows = totalRows,
            surfaceIds = ids,
            styleRows = styles,
            targetSurfaceId = targetSurfaceId,
            targetDirectionIndex = targetDirectionIndex,
            completeDigest = digest,
            bytes = bytes.copyOf(),
        )
    }

    fun isKind(bytes: ByteArray): Boolean = bytes.size >= 1 && bytes[0].toInt() and 0xff == KIND

    private const val NO_TARGET_SURFACE_ID = -1L
    private const val NO_TARGET_DIRECTION = -1

    private fun ByteArray.copyFrom(source: ByteArray, sourceOffset: Int, targetOffset: Int, length: Int) {
        source.copyInto(this, targetOffset, sourceOffset, sourceOffset + length)
    }

    private fun LongArray.isStrictlyAscending(): Boolean {
        for (index in 1 until size) if (this[index - 1] >= this[index]) return false
        return true
    }
}
