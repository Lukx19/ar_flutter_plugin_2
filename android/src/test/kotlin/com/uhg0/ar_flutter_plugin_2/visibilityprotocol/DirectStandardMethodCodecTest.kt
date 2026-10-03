package com.uhg0.ar_flutter_plugin_2.visibilityprotocol

import io.flutter.plugin.common.StandardMethodCodec
import org.junit.Assert.*
import org.junit.Test
import java.math.BigInteger
import java.nio.ByteBuffer

class DirectStandardMethodCodecTest {
    @Test fun primitiveAndNestedSuccessEnvelopesMatchStandardBytes() {
        val values = listOf(null, true, false, 1.toByte(), 2.toShort(), -3, Long.MIN_VALUE,
            1.25f, -0.0, Double.NaN, Double.POSITIVE_INFINITY, BigInteger("-123456789abcdef", 16),
            "ASCII", StringBuilder("text"), "\u00e9\u20ac\ud83d\ude00", "\ud800x\udc00", byteArrayOf(0, -1),
            intArrayOf(1, -2), longArrayOf(Long.MAX_VALUE), doubleArrayOf(-0.0, Double.NaN),
            floatArrayOf(Float.NaN, 2f), emptyList<Any>(), emptyMap<String, Any>(),
            linkedMapOf("rows" to listOf(mapOf("ids" to longArrayOf(4, 9)), null), 4 to "value"))
        for (value in values) assertParity(value)
    }

    @Test fun sizePrefixesAndArrayAlignmentMatchAtEveryBoundary() {
        for (size in listOf(0, 1, 253, 254, 255, 65535, 65536)) {
            for (padding in 0..7) {
                assertParity(listOf("x".repeat(padding), ByteArray(size) { it.toByte() }))
                assertParity(listOf("x".repeat(padding), IntArray(size) { it }))
                assertParity(listOf("x".repeat(padding), LongArray(size) { it.toLong() }))
                assertParity(listOf("x".repeat(padding), DoubleArray(size) { it.toDouble() }))
                assertParity(listOf("x".repeat(padding), FloatArray(size) { it.toFloat() }))
            }
            assertParity("\u20ac".repeat(size))
        }
    }

    @Test fun heldEnvelopeStaysImmutableAndStandardDecoderReadsDirectBuffer() {
        val input = byteArrayOf(1, 2, 3)
        val first = DirectStandardMethodCodec.encodeSuccessEnvelope(mapOf("bytes" to input))
        val saved = bytes(first)
        input.fill(9)
        val next = DirectStandardMethodCodec.encodeSuccessEnvelope(mapOf("bytes" to input))
        assertArrayEquals(saved, bytes(first))
        assertFalse(saved.contentEquals(bytes(next)))
        val decoded = StandardMethodCodec.INSTANCE.decodeEnvelope(first.duplicate().apply { flip() }) as Map<*, *>
        assertArrayEquals(byteArrayOf(1, 2, 3), decoded["bytes"] as ByteArray)
    }

    @Test fun unsupportedSuccessValuesFailLikeStandardCodec() {
        val unsupported = Any()
        assertThrows(IllegalArgumentException::class.java) {
            StandardMethodCodec.INSTANCE.encodeSuccessEnvelope(unsupported)
        }
        assertThrows(IllegalArgumentException::class.java) {
            DirectStandardMethodCodec.encodeSuccessEnvelope(unsupported)
        }
    }

    private fun assertParity(value: Any?) {
        val standard = StandardMethodCodec.INSTANCE.encodeSuccessEnvelope(value)
        val direct = DirectStandardMethodCodec.encodeSuccessEnvelope(value)
        assertTrue(direct.isDirect)
        assertEquals(standard.position(), direct.position())
        assertEquals(direct.position(), direct.capacity())
        assertArrayEquals(bytes(standard), bytes(direct))
    }

    private fun bytes(buffer: ByteBuffer): ByteArray =
        buffer.duplicate().apply { flip() }.let { view -> ByteArray(view.remaining()).also(view::get) }
}
