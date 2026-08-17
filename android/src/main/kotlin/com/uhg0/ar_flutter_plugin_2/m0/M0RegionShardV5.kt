package com.uhg0.ar_flutter_plugin_2.m0

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.zip.Deflater
import java.util.zip.DataFormatException
import java.util.zip.Inflater

/** Portable schema-5 shard codec mirrored by the Dart reference. */
object M0RegionShardV5 {
    const val headerBytes = 96
    const val maximumBytes = 16 * 1024 * 1024
    const val maximumPayloadBytes = maximumBytes - headerBytes

    enum class Kind { CANONICAL, COVERAGE }
    enum class Compression { NONE, ZLIB }

    data class Decoded(
        val kind: Kind,
        val region: M0RegionCoordinate,
        val captureEvaluatedThrough: Long,
        val pendingThrough: Long,
        val compression: Compression,
        val surfaceRows: List<ByteArray>,
        val secondaryRows: List<ByteArray>,
    )

    fun encodeCanonical(
        region: M0RegionCoordinate,
        captureEvaluatedThrough: Long,
        pendingThrough: Long,
        surfaceRows: List<ByteArray>,
        lineageRows: List<ByteArray>,
        compression: Compression = Compression.NONE,
    ): ByteArray = encode(
        Kind.CANONICAL,
        region,
        captureEvaluatedThrough,
        pendingThrough,
        surfaceRows,
        lineageRows,
        compression,
    )

    fun encodeCoverage(
        region: M0RegionCoordinate,
        captureEvaluatedThrough: Long,
        pendingThrough: Long,
        surfaceRows: List<ByteArray>,
        overflowRows: List<ByteArray>,
        compression: Compression = Compression.NONE,
    ): ByteArray = encode(
        Kind.COVERAGE,
        region,
        captureEvaluatedThrough,
        pendingThrough,
        surfaceRows,
        overflowRows,
        compression,
    )

    fun decode(bytes: ByteArray): Decoded {
        require(bytes.size in headerBytes..maximumBytes)
        val data = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val kind = when (String(bytes.copyOfRange(0, 4), Charsets.US_ASCII)) {
            "VRC1" -> Kind.CANONICAL
            "VRV1" -> Kind.COVERAGE
            else -> error("Schema-5 shard magic is invalid")
        }
        require(data.getShort(4).toInt() and 0xffff == 5)
        require(data.getShort(6).toInt() and 0xffff == headerBytes)
        val flags = data.getInt(8)
        require(flags and 1.inv() == 0 && data.getInt(60) == 0)
        val compression = if (flags and 1 == 0) Compression.NONE else Compression.ZLIB
        val surfaceCount = data.getInt(24).toLong() and 0xffffffffL
        val secondaryCount = data.getInt(28).toLong() and 0xffffffffL
        val capture = data.getLong(32)
        val pending = data.getLong(40)
        val overflow = data.getInt(48).toLong() and 0xffffffffL
        val storedBytes = data.getInt(52).toLong() and 0xffffffffL
        val decodedBytes = data.getInt(56).toLong() and 0xffffffffL
        require(
            capture >= 0 && pending >= 0 && pending < capture,
        )
        val surfaceWidth = if (kind == Kind.CANONICAL) 19 else 56
        val secondaryWidth = if (kind == Kind.CANONICAL) 9 else 13
        require(surfaceCount <= 100_000)
        require(kind != Kind.COVERAGE || overflow <= surfaceCount * 24)
        require(surfaceCount * surfaceWidth + secondaryCount * secondaryWidth.toLong() == decodedBytes)
        require(storedBytes == bytes.size.toLong() - headerBytes && storedBytes <= maximumPayloadBytes.toLong())
        require(decodedBytes <= maximumPayloadBytes.toLong())
        require(MessageDigest.isEqual(sha256(bytes.copyOfRange(headerBytes, bytes.size)), bytes.copyOfRange(64, 96)))
        val decoded = decodePayload(bytes.copyOfRange(headerBytes, bytes.size), compression, decodedBytes.toInt())
        var offset = 0
        val surfaces = buildList {
            repeat(surfaceCount.toInt()) {
                add(decoded.copyOfRange(offset, offset + surfaceWidth))
                offset += surfaceWidth
            }
        }
        val secondary = buildList {
            repeat(secondaryCount.toInt()) {
                add(decoded.copyOfRange(offset, offset + secondaryWidth))
                offset += secondaryWidth
            }
        }
        return Decoded(
            kind,
            M0RegionCoordinate(data.getInt(12), data.getInt(16), data.getInt(20)),
            capture,
            pending,
            compression,
            surfaces,
            secondary,
        )
    }

    private fun encode(
        kind: Kind,
        region: M0RegionCoordinate,
        capture: Long,
        pending: Long,
        surfaceRows: List<ByteArray>,
        secondaryRows: List<ByteArray>,
        compression: Compression,
    ): ByteArray {
        require(
            capture >= 0 && pending >= 0 && pending < capture,
        )
        val surfaceWidth = if (kind == Kind.CANONICAL) 19 else 56
        val secondaryWidth = if (kind == Kind.CANONICAL) 9 else 13
        require(surfaceRows.size <= 100_000)
        require(surfaceRows.all { it.size == surfaceWidth })
        require(secondaryRows.all { it.size == secondaryWidth })
        val decoded = surfaceRows.flatMap { it.asIterable() }.toByteArray() +
            secondaryRows.flatMap { it.asIterable() }.toByteArray()
        require(decoded.size <= maximumPayloadBytes)
        val stored = if (compression == Compression.NONE) decoded else zlibEncode(decoded)
        require(stored.size <= maximumPayloadBytes)
        require(decoded.isEmpty() || decoded.size <= maxOf(4096, stored.size * 64))
        val result = ByteArray(headerBytes + stored.size)
        val data = ByteBuffer.wrap(result).order(ByteOrder.LITTLE_ENDIAN)
        result[0] = if (kind == Kind.CANONICAL) 'V'.code.toByte() else 'V'.code.toByte()
        result[1] = 'R'.code.toByte()
        result[2] = if (kind == Kind.CANONICAL) 'C'.code.toByte() else 'V'.code.toByte()
        result[3] = '1'.code.toByte()
        data.putShort(4, 5)
        data.putShort(6, headerBytes.toShort())
        data.putInt(8, if (compression == Compression.ZLIB) 1 else 0)
        data.putInt(12, region.x)
        data.putInt(16, region.y)
        data.putInt(20, region.z)
        data.putInt(24, surfaceRows.size)
        data.putInt(28, secondaryRows.size)
        data.putLong(32, capture)
        data.putLong(40, pending)
        data.putInt(48, if (kind == Kind.COVERAGE) secondaryRows.size else 0)
        data.putInt(52, stored.size)
        data.putInt(56, decoded.size)
        stored.copyInto(result, headerBytes)
        sha256(stored).copyInto(result, 64)
        return result
    }

    private fun decodePayload(stored: ByteArray, compression: Compression, expected: Int): ByteArray {
        if (compression == Compression.NONE) {
            require(stored.size == expected)
            return stored
        }
        require(expected <= maxOf(4096, stored.size * 64))
        val inflater = Inflater(false)
        return try {
            inflater.setInput(stored)
            val output = ByteArray(expected)
            val count = inflater.inflate(output)
            require(inflater.finished() && count == expected && inflater.remaining == 0)
            output
        } catch (error: DataFormatException) {
            throw IllegalArgumentException("Zlib shard payload is invalid", error)
        } finally {
            inflater.end()
        }
    }

    private fun zlibEncode(bytes: ByteArray): ByteArray {
        val deflater = Deflater(Deflater.DEFAULT_COMPRESSION, false)
        return try {
            deflater.setInput(bytes)
            deflater.finish()
            val output = ByteArrayOutputStream(bytes.size)
            val buffer = ByteArray(8192)
            while (!deflater.finished()) {
                val count = deflater.deflate(buffer)
                require(count > 0 || deflater.finished())
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        } finally {
            deflater.end()
        }
    }

    private fun sha256(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)
}
