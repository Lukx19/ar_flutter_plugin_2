package com.uhg0.ar_flutter_plugin_2.capture

import java.security.MessageDigest

internal data class CaptureRootComponentV2(val kind: String, val length: Long, val hash: String)
internal data class CaptureRootMetadataV2(
    val schema: String?,
    val revision: Long?,
    val request: String?,
    val previous: String?,
    val secondPrevious: String?,
    val components: List<CaptureRootComponentV2>,
)
internal data class CaptureRootPointerV2(val revision: Long, val hash: String, val commit: String)

/**
 * Serial-owner scratch and a bounded cache of parsed immutable content, never file authority.
 * Every lookup reads the currently opened descriptor and verifies its hash before cache access.
 * Schema-5 emits fewer than 1 KiB; the 64 KiB ceiling also bounds malformed input.
 */
internal class CaptureRootMetadataReaderV2 {
    private val bytes = ByteArray(MAX_METADATA_BYTES + 1)
    private val digest = MessageDigest.getInstance("SHA-256")
    private val cache = object : LinkedHashMap<String, CaptureRootMetadataV2>(CACHE_CAPACITY, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, CaptureRootMetadataV2>?) =
            size > CACHE_CAPACITY
    }
    internal var parsedRootCount = 0L
        private set

    @Synchronized
    fun root(expectedHash: String, read: (ByteArray, Int, Int) -> Int): CaptureRootMetadataV2? {
        if (!isHash(expectedHash)) return null
        val count = readCurrent(read) ?: return null
        digest.reset()
        digest.update(bytes, 0, count)
        if (digest.digest().toCaptureHashHex() != expectedHash) return null
        cache[expectedHash]?.let { return it }
        var schema: String? = null
        var revision: Long? = null
        var request: String? = null
        var previous: String? = null
        var secondPrevious: String? = null
        var malformedComponent = false
        val components = ArrayList<CaptureRootComponentV2>(5)
        forEachLine(count) { line ->
            val separator = line.indexOf('=')
            if (separator >= 0) {
                val value = line.substring(separator + 1)
                when (line.substring(0, separator)) {
                    "schema" -> schema = value
                    "revision" -> revision = value.toLongOrNull()
                    "request" -> request = value
                    "previous" -> previous = value
                    "secondPrevious" -> secondPrevious = value
                    "component" -> {
                        val first = value.indexOf(':')
                        val second = value.indexOf(':', first + 1)
                        if (first > 0 && second > first) {
                            val length = value.substring(first + 1, second).toLongOrNull()
                            val hash = value.substring(second + 1)
                            if (length != null && length >= 0L && isHash(hash)) {
                                components.add(CaptureRootComponentV2(value.substring(0, first), length, hash))
                            } else malformedComponent = true
                        } else malformedComponent = true
                    }
                }
            }
        }
        if (malformedComponent) return null
        val parsed = CaptureRootMetadataV2(schema, revision, request, previous, secondPrevious,
            java.util.Collections.unmodifiableList(components))
        parsedRootCount++
        cache[expectedHash] = parsed
        return parsed
    }

    @Synchronized
    fun pointer(read: (ByteArray, Int, Int) -> Int): CaptureRootPointerV2? {
        val count = readCurrent(read) ?: return null
        var revision: Long? = null
        var hash: String? = null
        var commit: String? = null
        var lines = 0
        forEachLine(count) { line ->
            when (lines++) {
                0 -> revision = line.toLongOrNull()
                1 -> hash = line
                2 -> commit = line
            }
        }
        if (lines != 3 || revision == null || !isHash(hash)) return null
        return CaptureRootPointerV2(revision!!, hash!!, commit!!)
    }

    private fun readCurrent(read: (ByteArray, Int, Int) -> Int): Int? {
        var count = 0
        while (count < bytes.size) {
            val received = read(bytes, count, bytes.size - count)
            if (received < 0) return count
            if (received == 0) return null
            require(received <= bytes.size - count)
            count += received
        }
        return null
    }

    private inline fun forEachLine(count: Int, consume: (String) -> Unit) {
        var start = 0
        for (index in 0 until count) {
            if (bytes[index] == 10.toByte()) {
                val end = if (index > start && bytes[index - 1] == 13.toByte()) index - 1 else index
                consume(String(bytes, start, end - start, Charsets.UTF_8))
                start = index + 1
            }
        }
        if (start < count) consume(String(bytes, start, count - start, Charsets.UTF_8))
    }

    companion object {
        const val MAX_METADATA_BYTES = 64 * 1024
        const val CACHE_CAPACITY = 8
        fun isHash(value: String?): Boolean = value != null && value.length == 64 &&
            value.all { it in '0'..'9' || it in 'a'..'f' }
    }
}
