package com.uhg0.ar_flutter_plugin_2.visibilitygrid

/** Strictly bounded trace that is inert until an explicit debug seam is armed. */
internal class BoundedDebugTrace(private val capacity: Int) {
    init {
        require(capacity > 0)
    }

    private val entries = ArrayDeque<String>(capacity)
    private var armed = false

    @Synchronized
    fun arm(initial: String) {
        entries.clear()
        armed = true
        record(initial)
    }

    @Synchronized
    fun record(value: String) {
        if (!armed) return
        if (entries.size == capacity) entries.removeFirst()
        entries.addLast(value)
    }

    @Synchronized
    fun snapshot(): List<String> = entries.toList()

    @Synchronized
    fun clear() {
        entries.clear()
        armed = false
    }
}
