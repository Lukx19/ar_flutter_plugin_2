package com.uhg0.ar_flutter_plugin_2.util

private const val LOWERCASE_HEX_DIGITS = "0123456789abcdef"

/** Stable byte identity without a formatter, locale lookup, or intermediate string per byte. */
internal fun ByteArray.toLowercaseHex(): String {
    val result = CharArray(Math.multiplyExact(size, 2))
    for (index in indices) {
        val value = this[index].toInt() and 0xff
        result[index * 2] = LOWERCASE_HEX_DIGITS[value ushr 4]
        result[index * 2 + 1] = LOWERCASE_HEX_DIGITS[value and 0x0f]
    }
    return String(result)
}
