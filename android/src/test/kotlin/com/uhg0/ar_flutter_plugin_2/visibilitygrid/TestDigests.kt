package com.uhg0.ar_flutter_plugin_2.visibilitygrid

import java.security.MessageDigest

/** Shared deterministic digest operations for canonical-format tests. */
internal fun testSha256(bytes: ByteArray): ByteArray =
    MessageDigest.getInstance("SHA-256").digest(bytes)

internal fun testSha256Hex(bytes: ByteArray): String = testSha256(bytes).toLowerHex()

internal fun ByteArray.toLowerHex(): String =
    joinToString(separator = "") { byte -> "%02x".format(byte) }
