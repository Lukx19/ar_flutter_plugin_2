package com.uhg0.ar_flutter_plugin_2.visibilityprotocol

import io.flutter.plugin.common.MethodCodec
import io.flutter.plugin.common.StandardMessageCodec
import io.flutter.plugin.common.StandardMethodCodec
import java.io.ByteArrayOutputStream
import java.math.BigInteger
import java.nio.ByteBuffer

/**
 * Standard success envelopes written directly into one exclusively owned buffer.
 *
 * Wire encoding and decoding remain Flutter's standard codec. Only the growing
 * intermediate heap stream is removed. Each response owns fresh storage because
 * BinaryMessenger does not expose a reusable-buffer consumption fence.
 */
internal object DirectStandardMethodCodec : MethodCodec by StandardMethodCodec.INSTANCE {
    private val values = ValueWriter()

    override fun encodeSuccessEnvelope(result: Any?): ByteBuffer {
        val destination = ByteBuffer.allocateDirect(encodedEnd(result, 1))
        destination.put(0.toByte())
        values.write(DirectStream(destination), result)
        check(destination.position() == destination.capacity()) { "Standard envelope size mismatch" }
        return destination
    }

    private class ValueWriter : StandardMessageCodec() {
        fun write(stream: ByteArrayOutputStream, value: Any?) = writeValue(stream, value)
    }

    /** StandardMessageCodec uses only write and size; its alignment sees the envelope byte. */
    private class DirectStream(private val destination: ByteBuffer) : ByteArrayOutputStream(0) {
        override fun write(value: Int) { destination.put(value.toByte()) }
        override fun write(bytes: ByteArray, offset: Int, length: Int) {
            destination.put(bytes, offset, length)
        }
        override fun size(): Int = destination.position()
    }

    /** Count standard wire bytes without encoding strings or creating temporary containers. */
    private fun encodedEnd(value: Any?, start: Int): Int {
        var end = add(start, 1)
        when (value) {
            null, is Boolean -> Unit
            is Byte, is Short, is Int -> end = add(end, 4)
            is Long -> end = add(end, 8)
            is Float, is Double -> end = add(aligned(end, 8), 8)
            is BigInteger -> end = sized(end, value.toString(16).length)
            is CharSequence -> end = sized(end, utf8Length(value.toString()))
            is ByteArray -> end = sized(end, value.size)
            is IntArray -> end = arrayEnd(end, value.size, 4)
            is LongArray -> end = arrayEnd(end, value.size, 8)
            is DoubleArray -> end = arrayEnd(end, value.size, 8)
            is FloatArray -> end = arrayEnd(end, value.size, 4)
            is List<*> -> {
                end = add(end, sizePrefix(value.size))
                for (element in value) end = encodedEnd(element, end)
            }
            is Map<*, *> -> {
                end = add(end, sizePrefix(value.size))
                for ((key, element) in value) {
                    end = encodedEnd(key, end)
                    end = encodedEnd(element, end)
                }
            }
            else -> throw IllegalArgumentException("Unsupported standard codec value: ${value.javaClass.name}")
        }
        return end
    }

    private fun arrayEnd(start: Int, count: Int, alignment: Int): Int =
        add(aligned(add(start, sizePrefix(count)), alignment), Math.multiplyExact(count, alignment))

    private fun sized(start: Int, length: Int): Int = add(add(start, sizePrefix(length)), length)
    private fun sizePrefix(length: Int): Int = if (length < 254) 1 else if (length <= 65535) 3 else 5
    private fun aligned(value: Int, alignment: Int): Int = add(value, (-value) and (alignment - 1))
    private fun add(left: Int, right: Int): Int = Math.addExact(left, right)

    private fun utf8Length(value: String): Int {
        var count = 0
        var index = 0
        while (index < value.length) {
            val char = value[index++]
            count = add(count, when {
                char.code < 0x80 -> 1
                char.code < 0x800 -> 2
                char.isHighSurrogate() && index < value.length && value[index].isLowSurrogate() -> {
                    index++
                    4
                }
                // Java's UTF-8 replacement for an unpaired surrogate is one '?' byte.
                char.isSurrogate() -> 1
                else -> 3
            })
        }
        return count
    }
}
