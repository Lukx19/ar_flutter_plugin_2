package com.uhg0.ar_flutter_plugin_2.sessionlog

/** Runs [body] with the native session log open and returns the drained records. */
internal inline fun withNativeSessionLog(body: () -> Unit): List<List<Any?>> {
    NativeSessionLogEmitter.setActive(false)
    NativeSessionLogEmitter.setActive(true)
    try {
        body()
        return NativeSessionLogEmitter.buffer.drain()
    } finally {
        NativeSessionLogEmitter.setActive(false)
    }
}

/** Full records of [code]. */
internal fun List<List<Any?>>.full(code: String): List<List<Any?>> = filter { it[0] == "F" && it[1] == code }

/** The data map of a full record. */
@Suppress("UNCHECKED_CAST")
internal val List<Any?>.data: Map<String, Any?> get() = this[4] as Map<String, Any?>
