package com.uhg0.ar_flutter_plugin_2.capture

import com.uhg0.ar_flutter_plugin_2.util.toLowercaseHex

private const val CAPTURE_HASH_HEX_DIGITS = "0123456789abcdef"

/** Encodes immutable hash bytes without allocating a formatter for each byte. */
internal fun ByteArray.toCaptureHashHex(): String = toLowercaseHex()

/** Protocol digest lists contain unsigned bytes, not arbitrary integer values. */
internal fun List<Int>.toCaptureHashHex(): String {
    val result = CharArray(Math.multiplyExact(size, 2))
    for (index in indices) {
        val value = this[index]
        require(value in 0..255) { "Capture hash contains a non-byte value" }
        result[index * 2] = CAPTURE_HASH_HEX_DIGITS[value ushr 4]
        result[index * 2 + 1] = CAPTURE_HASH_HEX_DIGITS[value and 0x0f]
    }
    return String(result)
}
