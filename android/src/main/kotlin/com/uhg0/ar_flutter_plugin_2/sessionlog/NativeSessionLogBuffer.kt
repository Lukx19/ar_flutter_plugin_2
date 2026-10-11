package com.uhg0.ar_flutter_plugin_2.sessionlog

/**
 * Bounded buffer of native session log records, drained by the app's
 * session log (`ar_flutter_plugin_2/session_log`, method `drain`).
 *
 * Applies the app's flood policy before anything is queued: the first
 * occurrence of a code and of each new variant (at most [MAX_VARIANTS]) is a
 * full record; after that a code repeating faster than [minFullIntervalNs]
 * (5 per minute) is only counted. Counted occurrences leave as one
 * `['C', code, count, lastMessage]` record per drain; a repeat costs a map
 * lookup and an int increment, no allocation.
 *
 * Records are lists the Dart side decodes with `SessionLogRecordCodec`:
 * `['F', code, level, message, data, variant]`.
 */
class NativeSessionLogBuffer(
    private val capacity: Int = 256,
    private val minFullIntervalNs: Long = 12_000_000_000L,
    private val nowNs: () -> Long = System::nanoTime,
) {
    private class CodeState {
        var lastFullNs = Long.MIN_VALUE
        var counted = 0
        var lastMessage = ""
        val variants = ArrayList<String>(MAX_VARIANTS)
    }

    private val lock = Any()
    private val records = ArrayList<List<Any?>>(capacity)
    private val codes = HashMap<String, CodeState>()
    private var droppedFull = 0

    /** Whether this occurrence should be recorded in full ([record] with `admitted`). */
    fun occur(code: String, variant: String? = null, message: String = ""): Boolean = synchronized(lock) {
        val state = codes.getOrPut(code) { CodeState() }
        val now = nowNs()
        val newVariant = variant != null && state.variants.size < MAX_VARIANTS && variant !in state.variants
        if (newVariant) state.variants.add(variant!!)
        if (state.lastFullNs == Long.MIN_VALUE || newVariant || now - state.lastFullNs >= minFullIntervalNs) {
            state.lastFullNs = now
            true
        } else {
            state.counted++
            state.lastMessage = message
            false
        }
    }

    /**
     * Records one occurrence of [code]: a full record when the flood policy
     * admits it, otherwise a count. [admitted] skips the policy (the caller
     * already called [occur]).
     */
    fun record(
        code: String,
        message: String,
        data: Map<String, Any?> = emptyMap(),
        variant: String? = null,
        level: String = LEVEL_WARNING,
        admitted: Boolean = false,
    ) {
        if (!admitted && !occur(code, variant, message)) return
        synchronized(lock) {
            if (records.size >= capacity) {
                droppedFull++
                codes.getOrPut(code) { CodeState() }.let {
                    it.counted++
                    it.lastMessage = message
                }
                return
            }
            records.add(listOf("F", code, level, message, data, variant))
        }
    }

    /** Full records and counts since the previous drain. */
    fun drain(): List<List<Any?>> = synchronized(lock) {
        val out = ArrayList<List<Any?>>(records.size + 4)
        out.addAll(records)
        records.clear()
        for ((code, state) in codes) {
            if (state.counted > 0) {
                out.add(listOf("C", code, state.counted, state.lastMessage))
                state.counted = 0
            }
        }
        if (droppedFull > 0) {
            out.add(
                listOf(
                    "F", "LOG_EVENTS_DROPPED", LEVEL_WARNING,
                    "Native session log buffer was full; records counted instead",
                    mapOf("countedInsteadOfWritten" to droppedFull), null,
                ),
            )
            droppedFull = 0
        }
        out
    }

    /** Forgets everything (a new session). */
    fun reset() = synchronized(lock) {
        records.clear()
        codes.clear()
        droppedFull = 0
    }

    companion object {
        const val MAX_VARIANTS = 8
        const val LEVEL_WARNING = "warning"
        const val LEVEL_ERROR = "error"
        const val LEVEL_INFO = "info"
    }
}
