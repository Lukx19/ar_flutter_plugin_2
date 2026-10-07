package com.uhg0.ar_flutter_plugin_2.sceneview

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

/** Optional presentation bytes. These operations never acknowledge a stream cut. */
internal object RendererAttributeCodec {
    const val HEADER_BYTES = 256
    const val ROW_BYTES = 24
    const val PAGE_BYTES = 16 * 1024 - 80
    const val IDS_PER_PAGE = (PAGE_BYTES - HEADER_BYTES) / 8
    const val ROWS_PER_PAGE = (PAGE_BYTES - HEADER_BYTES) / ROW_BYTES
    const val MAX_ROWS = 4096
    const val SELECT = 1
    const val PAGE = 2
    const val COMMIT = 3
    const val STATUS = 4
    const val CANCEL = 5
    const val PATCHES = 1
    const val BALLS = 2
    const val OBSERVATIONS = 1
    const val DIRECTION_IDENTITY = 2
    const val ZERO_ARGB = 0xffff4545.toInt()
    const val ONE_ARGB = 0xffffbf3f.toInt()
    const val TWO_PLUS_ARGB = 0xff44cc66.toInt()
    const val UNAVAILABLE_ARGB = 0xff999999.toInt()
    private const val MAGIC = 0x31544152 // RAT1, little endian

    /** Owns one fixed header only; body views are borrowed for this invocation. */
    class Header(val bytes: ByteArray) {
        private fun buffer() = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val operation: Int get() = bytes[5].toInt() and 255
        val rendererGeneration: Long get() = buffer().getLong(124)
        val selectionGeneration: Long get() = buffer().getLong(132)
        val shape: Int get() = buffer().getInt(140)
        val color: Int get() = buffer().getInt(144)
        val total: Int get() = buffer().getInt(148)
        val pageIndex: Int get() = buffer().getInt(152)
        val pageCount: Int get() = buffer().getInt(156)
        val first: Int get() = buffer().getInt(160)
        val count: Int get() = buffer().getInt(164)
        fun styleMatches(style: ByteArray): Boolean = style.size == 116 &&
            style.indices.all { bytes[it + 8] == style[it] }
        fun sameSelection(other: Header): Boolean =
            (8 until 152).all { bytes[it] == other.bytes[it] } &&
                (168 until 200).all { bytes[it] == other.bytes[it] } &&
                (240 until 248).all { bytes[it] == other.bytes[it] }
        fun sameDigest(other: Header): Boolean = (200 until 232).all { bytes[it] == other.bytes[it] }
        fun page(operation: Int, index: Int, pages: Int, first: Int, count: Int,
            attributeDigest: ByteArray? = null): Header = Header(bytes.copyOf()).also {
            it.bytes[5] = operation.toByte()
            val data = ByteBuffer.wrap(it.bytes).order(ByteOrder.LITTLE_ENDIAN)
            data.putInt(152, index).putInt(156, pages).putInt(160, first).putInt(164, count)
            attributeDigest?.copyInto(it.bytes, 200)
        }
        fun encode(body: ByteArray = byteArrayOf(), offset: Int = 0, size: Int = body.size): ByteArray {
            require(size in 0..PAGE_BYTES - HEADER_BYTES && offset >= 0 && offset + size <= body.size)
            return ByteArray(HEADER_BYTES + size).also {
                bytes.copyInto(it); body.copyInto(it, HEADER_BYTES, offset, offset + size)
            }
        }
    }

    fun header(style: ByteArray, renderer: Long, selection: Long, shape: Int, color: Int,
        total: Int): Header {
        require(style.size == 116)
        val bytes = ByteArray(HEADER_BYTES)
        val data = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        data.putInt(MAGIC).put(1).put(SELECT.toByte()).putShort(HEADER_BYTES.toShort())
        style.copyInto(bytes, 8)
        data.putLong(124, renderer).putLong(132, selection).putInt(140, shape).putInt(144, color)
        data.putInt(148, total).putInt(240, 1).putInt(244, 1)
        return decode(bytes).first
    }

    fun decode(bytes: ByteArray): Pair<Header, ByteArray> {
        require(bytes.size in HEADER_BYTES..PAGE_BYTES)
        val data = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        require(data.int == MAGIC && data.get().toInt() == 1)
        val operation = data.get().toInt() and 255
        require(operation in SELECT..CANCEL && data.short.toInt() == HEADER_BYTES)
        require((232 until 240).all { bytes[it] == 0.toByte() } &&
            (248 until 256).all { bytes[it] == 0.toByte() })
        require(data.getInt(240) == 1 && data.getInt(244) == 1)
        require(data.getLong(124) >= 0 && data.getLong(132) >= 0)
        require(data.getInt(140) in PATCHES..BALLS && data.getInt(144) in OBSERVATIONS..DIRECTION_IDENTITY)
        val total = data.getInt(148)
        require(total in 0..if (data.getInt(140) == BALLS) 256 else MAX_ROWS)
        require((152..164 step 4).all { data.getInt(it) >= 0 })
        require(data.getInt(160) + data.getInt(164) <= total)
        // Canonical style header: exact existing fields and reserved lanes.
        require((0 until 10).all { data.getLong(24 + it * 8) >= 0 })
        require(data.getLong(24) > 0 && data.getLong(80) > 0)
        require(bytes[104].toInt() in 0..1 && bytes[105] == 0.toByte() && data.getShort(106).toInt() == 0)
        require(data.getInt(120) in 0..100_000)
        val target = data.getLong(108); val direction = data.getInt(116)
        require((target == -1L && direction == -1) || (target > 0 && direction in 0..23))
        require(if (operation == SELECT) data.getInt(164) <= IDS_PER_PAGE else
            if (operation == PAGE) data.getInt(164) <= ROWS_PER_PAGE else bytes.size == HEADER_BYTES)
        return Header(bytes.copyOfRange(0, HEADER_BYTES)) to bytes
    }

    /** Stable cut/conventions/ordered records, excluding operation and pagination. */
    fun digest(header: Header, body: ByteArray, length: Int = body.size): ByteArray {
        require(length in 0..body.size)
        return MessageDigest.getInstance("SHA-256").run {
            update(header.bytes, 8, 144); update(header.bytes, 240, 8)
            update(body, 0, length); digest()
        }
    }

    fun validateRow(bytes: ByteArray, offset: Int, expectedId: Long) {
        val data = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        require(data.getLong(offset) == expectedId && expectedId > 0)
        val normal = data.getShort(offset + 8).toInt() and 65535
        val confidence = bytes[offset + 10].toInt() and 255
        val flags = bytes[offset + 11].toInt() and 255
        require(flags in 0..3 && (18 until 24).all { bytes[offset + it] == 0.toByte() })
        require(if (flags and 1 == 0) normal == 0x8080 && confidence == 0 else
            normal and 255 != 128 && normal ushr 8 != 128)
        if (flags and 2 == 0) require((12 until 18).all { bytes[offset + it] == 0xff.toByte() })
        else require((0 until 24).all { ((bytes[offset + 12 + it / 4].toInt() ushr ((it % 4) * 2)) and 3) != 3 })
    }
}
