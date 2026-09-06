package com.uhg0.ar_flutter_plugin_2.visibilitygrid

/** Primitive open-addressed identity table used only during batch admission. */
internal class DepthEvidenceIdTable private constructor(
    private val keys: LongArray,
    private val occupied: BooleanArray,
) {
    val allocatedBytes: Long get() = keys.size * 9L + TABLE_BYTES

    fun add(value: Long): Boolean {
        var slot = mix(value).toInt() and (keys.size - 1)
        while (occupied[slot]) {
            if (keys[slot] == value) return false
            slot = (slot + 1) and (keys.size - 1)
        }
        occupied[slot] = true
        keys[slot] = value
        return true
    }

    fun contains(value: Long): Boolean {
        var slot = mix(value).toInt() and (keys.size - 1)
        while (occupied[slot]) {
            if (keys[slot] == value) return true
            slot = (slot + 1) and (keys.size - 1)
        }
        return false
    }

    companion object {
        private const val TABLE_BYTES = 64L

        fun forExpected(expected: Int): DepthEvidenceIdTable =
            DepthEvidenceIdTable(LongArray(capacity(expected)), BooleanArray(capacity(expected)))

        fun bytesForExpected(expected: Int): Long = capacity(expected) * 9L + TABLE_BYTES

        private fun capacity(expected: Int): Int {
            require(expected >= 0)
            val needed = Math.max(2L, Math.multiplyExact(expected.toLong(), 2L))
            var capacity = 1
            while (capacity.toLong() < needed) {
                capacity = Math.multiplyExact(capacity, 2)
            }
            return capacity
        }

        private fun mix(value: Long): Long {
            var mixed = value
            mixed = (mixed xor (mixed ushr 33)) * -49064778989728563L
            mixed = (mixed xor (mixed ushr 33)) * -4265267296055464877L
            return mixed xor (mixed ushr 33)
        }
    }
}

/** Primitive open-addressed voxel table used to reject duplicate targets. */
internal class DepthEvidenceVoxelTable private constructor(
    private val x: IntArray,
    private val y: IntArray,
    private val z: IntArray,
    private val occupied: BooleanArray,
) {
    val allocatedBytes: Long get() = x.size * 13L + TABLE_BYTES

    fun add(voxel: Voxel): Boolean {
        var slot = mix(voxel).toInt() and (x.size - 1)
        while (occupied[slot]) {
            if (x[slot] == voxel.x && y[slot] == voxel.y && z[slot] == voxel.z) return false
            slot = (slot + 1) and (x.size - 1)
        }
        occupied[slot] = true
        x[slot] = voxel.x; y[slot] = voxel.y; z[slot] = voxel.z
        return true
    }

    companion object {
        private const val TABLE_BYTES = 64L

        fun forExpected(expected: Int): DepthEvidenceVoxelTable {
            val capacity = capacity(expected)
            return DepthEvidenceVoxelTable(IntArray(capacity), IntArray(capacity), IntArray(capacity), BooleanArray(capacity))
        }

        fun bytesForExpected(expected: Int): Long = capacity(expected) * 13L + TABLE_BYTES

        private fun capacity(expected: Int): Int {
            require(expected >= 0)
            val needed = Math.max(2L, Math.multiplyExact(expected.toLong(), 2L))
            var capacity = 1
            while (capacity.toLong() < needed) {
                capacity = Math.multiplyExact(capacity, 2)
            }
            return capacity
        }

        private fun mix(voxel: Voxel): Long {
            var mixed = voxel.x.toLong() * -7046029254386353131L
            mixed = mixed xor (voxel.y.toLong() * -4417276706812531889L)
            mixed = mixed xor (voxel.z.toLong() * -8796714831421723037L)
            mixed = (mixed xor (mixed ushr 33)) * -49064778989728563L
            return mixed xor (mixed ushr 29)
        }
    }
}
