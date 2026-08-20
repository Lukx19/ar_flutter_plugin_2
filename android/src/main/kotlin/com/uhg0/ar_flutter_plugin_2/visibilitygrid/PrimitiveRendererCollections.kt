package com.uhg0.ar_flutter_plugin_2.visibilitygrid

/** Fixed-capacity primitive long-to-row index; zero is an ordinary key. */
internal class LongRowIndex(capacity: Int) {
    private val mask: Int
    private val keys: LongArray
    private val values: IntArray
    private val states: ByteArray
    private var size = 0

    init {
        require(capacity > 0)
        var tableSize = 1
        while (tableSize < capacity * 2) tableSize = tableSize shl 1
        mask = tableSize - 1
        keys = LongArray(tableSize)
        values = IntArray(tableSize)
        states = ByteArray(tableSize)
    }

    operator fun get(key: Long): Int? {
        var index = slot(key)
        while (states[index].toInt() != EMPTY) {
            if (states[index].toInt() == OCCUPIED && keys[index] == key) return values[index]
            index = (index + 1) and mask
        }
        return null
    }

    fun containsKey(key: Long): Boolean = get(key) != null

    operator fun contains(key: Long): Boolean = containsKey(key)

    operator fun set(key: Long, value: Int) {
        var index = slot(key)
        while (states[index].toInt() != EMPTY) {
            if (states[index].toInt() == OCCUPIED && keys[index] == key) {
                values[index] = value
                return
            }
            index = (index + 1) and mask
        }
        keys[index] = key
        values[index] = value
        states[index] = OCCUPIED.toByte()
        size++
    }

    fun remove(key: Long): Int? {
        var index = slot(key)
        while (states[index].toInt() != EMPTY) {
            if (states[index].toInt() == OCCUPIED && keys[index] == key) {
                states[index] = EMPTY.toByte()
                size--
                val removed = values[index]
                var next = (index + 1) and mask
                while (states[next].toInt() == OCCUPIED) {
                    val movedKey = keys[next]
                    val movedValue = values[next]
                    states[next] = EMPTY.toByte()
                    size--
                    set(movedKey, movedValue)
                    next = (next + 1) and mask
                }
                return removed
            }
            index = (index + 1) and mask
        }
        return null
    }

    fun clear() {
        states.fill(EMPTY.toByte())
        size = 0
    }

    private fun slot(key: Long): Int {
        var mixed = key xor (key ushr 33)
        mixed *= -49064778989728563L
        mixed = mixed xor (mixed ushr 33)
        return mixed.toInt() and mask
    }

    private companion object {
        const val EMPTY = 0
        const val OCCUPIED = 1
    }
}

/** Fixed primitive max-heap with lazy deletion for selected identities. */
internal class SelectedKeyMaxHeap(private val capacity: Int) {
    private val heap = LongArray(capacity * 2)
    private var size = 0

    fun add(key: Long, active: (Long) -> Boolean) {
        if (size == heap.size) rebuild(active)
        heap[size] = key
        siftUp(size++)
    }

    fun largest(active: (Long) -> Boolean): Long? {
        discardInactive(active)
        return heap.firstOrNull().takeIf { size > 0 }
    }

    fun clear() {
        size = 0
    }

    private fun rebuild(active: (Long) -> Boolean) {
        val retained = heap.copyOf(size)
        size = 0
        retained.forEach { key -> if (active(key)) add(key, active) }
    }

    private fun discardInactive(active: (Long) -> Boolean) {
        while (size > 0 && !active(heap[0])) {
            heap[0] = heap[--size]
            if (size > 0) siftDown(0)
        }
    }

    private fun siftUp(start: Int) {
        var child = start
        while (child > 0) {
            val parent = (child - 1) / 2
            if (heap[parent] >= heap[child]) return
            val value = heap[parent]
            heap[parent] = heap[child]
            heap[child] = value
            child = parent
        }
    }

    private fun siftDown(start: Int) {
        var parent = start
        while (true) {
            val left = parent * 2 + 1
            if (left >= size) return
            val right = left + 1
            val child = if (right < size && heap[right] > heap[left]) right else left
            if (heap[parent] >= heap[child]) return
            val value = heap[parent]
            heap[parent] = heap[child]
            heap[child] = value
            parent = child
        }
    }
}

/** One dirty marker per dense row, drained in ascending row order. */
internal class DirtyRowQueue(private val capacity: Int) {
    private val marked = BooleanArray(capacity)
    private val heap = IntArray(capacity)
    private var size = 0

    fun add(row: Int) {
        if (row !in 0 until capacity || marked[row]) return
        marked[row] = true
        heap[size] = row
        siftUp(size++)
    }

    fun addRange(endExclusive: Int) {
        for (row in 0 until endExclusive) add(row)
    }

    fun clear() {
        marked.fill(false)
        size = 0
    }

    fun drainActive(activeCount: Int): IntArray {
        val result = IntArray(size)
        var resultSize = 0
        while (size > 0) {
            val row = heap[0]
            heap[0] = heap[--size]
            if (size > 0) siftDown(0)
            marked[row] = false
            if (row < activeCount) result[resultSize++] = row
        }
        return result.copyOf(resultSize)
    }

    private fun siftUp(start: Int) {
        var child = start
        while (child > 0) {
            val parent = (child - 1) / 2
            if (heap[parent] <= heap[child]) return
            val value = heap[parent]
            heap[parent] = heap[child]
            heap[child] = value
            child = parent
        }
    }

    private fun siftDown(start: Int) {
        var parent = start
        while (true) {
            val left = parent * 2 + 1
            if (left >= size) return
            val right = left + 1
            val child = if (right < size && heap[right] < heap[left]) right else left
            if (heap[parent] <= heap[child]) return
            val value = heap[parent]
            heap[parent] = heap[child]
            heap[child] = value
            parent = child
        }
    }
}
