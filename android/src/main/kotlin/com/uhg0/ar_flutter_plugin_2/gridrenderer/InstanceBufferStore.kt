package com.uhg0.ar_flutter_plugin_2.gridrenderer

import com.uhg0.ar_flutter_plugin_2.visibilitycontract.RendererDeltaLayout
import com.uhg0.ar_flutter_plugin_2.visibilitycontract.SurfaceInstanceLayout
import java.nio.ByteBuffer

/** Persistent GPU buffers of the grid renderer and their record sizes. */
internal enum class GridBuffer(val recordBytes: Int) {
    SURFACES(SurfaceInstanceLayout.BYTES),
    VOXELS(RendererDeltaLayout.COMPACT_VOXEL_BYTES),
    BRICKS(16),
    REGIONS(16),
}

/**
 * Where delta records land. The GLES implementation stages bytes through a
 * fenced upload ring and copies them on the GPU; JVM tests use a byte-array
 * mirror. Every call runs on the render thread.
 */
internal interface GpuBufferTarget {
    /** Grows [buffer] to at least [bytes], keeping its content. */
    fun ensureCapacity(buffer: GridBuffer, bytes: Int)

    /** Copies `src[srcOffset, srcOffset + length)` to [dstOffset] of [buffer]. */
    fun write(buffer: GridBuffer, dstOffset: Int, src: ByteBuffer, srcOffset: Int, length: Int)

    /** Zeroes `[dstOffset, dstOffset + length)` of [buffer]. */
    fun clear(buffer: GridBuffer, dstOffset: Int, length: Int)

    /** Bytes this target can still accept in the current frame without stalling. */
    fun frameUploadCapacity(): Int
}

/**
 * Open-addressing map from brick key to a slice index; removal shifts the
 * probe chain back, so there are no tombstones. Grows by doubling (counted).
 */
internal class BrickSliceTable(initialCapacity: Int = 1024) {
    private var keys = LongArray(roundPow2(initialCapacity * 2)) { EMPTY }
    private var values = IntArray(keys.size)
    var size = 0
        private set
    var growthEvents = 0
        private set

    // Slice columns, indexed by slice index.
    var first = IntArray(initialCapacity)
        private set
    var count = IntArray(initialCapacity)
        private set
    var brickKey = LongArray(initialCapacity)
        private set
    var selected = IntArray(initialCapacity)
        private set
    private var freeIndices = IntArray(initialCapacity)
    private var freeCount = 0
    var highWater = 0
        private set

    /** Index of the slice of [key], or -1. */
    fun find(key: Long): Int {
        val mask = keys.size - 1
        var i = mix(key) and mask
        while (true) {
            val k = keys[i]
            if (k == key) return values[i]
            if (k == EMPTY) return -1
            i = (i + 1) and mask
        }
    }

    /** Sets the slice of [key] to ([sliceFirst], [sliceCount]); returns its index. */
    fun put(key: Long, sliceFirst: Int, sliceCount: Int, selectedCount: Int): Int {
        var index = find(key)
        if (index < 0) {
            if ((size + 1) * 2 > keys.size) rehash(keys.size * 2)
            index = if (freeCount > 0) freeIndices[--freeCount] else highWater++
            if (index >= first.size) growColumns(first.size * 2)
            val mask = keys.size - 1
            var i = mix(key) and mask
            while (keys[i] != EMPTY) i = (i + 1) and mask
            keys[i] = key
            values[i] = index
            size++
        }
        first[index] = sliceFirst
        count[index] = sliceCount
        brickKey[index] = key
        selected[index] = selectedCount
        return index
    }

    /** Removes the slice of [key]; returns whether it existed. */
    fun remove(key: Long): Boolean {
        val mask = keys.size - 1
        var i = mix(key) and mask
        while (true) {
            val k = keys[i]
            if (k == EMPTY) return false
            if (k == key) break
            i = (i + 1) and mask
        }
        val index = values[i]
        count[index] = 0
        selected[index] = 0
        brickKey[index] = EMPTY
        if (freeCount == freeIndices.size) freeIndices = freeIndices.copyOf(freeIndices.size * 2)
        freeIndices[freeCount++] = index
        size--
        // Backward-shift deletion.
        var hole = i
        var j = (i + 1) and mask
        while (keys[j] != EMPTY) {
            val home = mix(keys[j]) and mask
            val between = if (hole <= j) home in (hole + 1)..j else home in (hole + 1)..mask || home <= j
            if (!between) {
                keys[hole] = keys[j]
                values[hole] = values[j]
                hole = j
            }
            j = (j + 1) and mask
        }
        keys[hole] = EMPTY
        return true
    }

    fun clear() {
        keys.fill(EMPTY)
        count.fill(0)
        selected.fill(0)
        brickKey.fill(EMPTY)
        size = 0
        freeCount = 0
        highWater = 0
    }

    private fun rehash(capacity: Int) {
        val oldKeys = keys
        val oldValues = values
        keys = LongArray(capacity) { EMPTY }
        values = IntArray(capacity)
        val mask = capacity - 1
        for (s in oldKeys.indices) {
            val k = oldKeys[s]
            if (k == EMPTY) continue
            var i = mix(k) and mask
            while (keys[i] != EMPTY) i = (i + 1) and mask
            keys[i] = k
            values[i] = oldValues[s]
        }
        growthEvents++
    }

    private fun growColumns(capacity: Int) {
        first = first.copyOf(capacity)
        count = count.copyOf(capacity)
        brickKey = brickKey.copyOf(capacity).also { it.fill(EMPTY, highWater, capacity) }
        selected = selected.copyOf(capacity)
        growthEvents++
    }

    companion object {
        const val EMPTY = Long.MIN_VALUE

        fun mix(key: Long): Int {
            val h = key * -0x61c8864680b583ebL
            return (h xor (h ushr 29)).toInt()
        }

        fun roundPow2(v: Int): Int {
            var p = 1
            while (p < v) p = p shl 1
            return p
        }
    }
}

/** Counters of the instance buffers, read by diagnostics and tests. */
internal class InstanceBufferCounters {
    var messages = 0L
    var bytes = 0L
    var rejected = 0L
    var ignoredOccupancyMasks = 0L
    var resets = 0L
    var frameEnds = 0L
    var capacityGrowths = 0L
    var lastGridVersion = 0L
}

/**
 * CPU-side bookkeeping of the persistent instance buffers: applies validated
 * renderer deltas to a [GpuBufferTarget] and keeps the draw tables (surface and
 * voxel slices per brick, summary and region high water marks). It never
 * touches GL itself, so the JVM tests drive it with a byte-array target.
 *
 * Kind 3 (dense occupancy masks) is ignored: occupancy voxels are drawn only
 * from compact voxel lists (kind 8), because dense 512-instance-per-brick
 * drawing measured 29/104/276 ms frames on the tablet while the compact
 * path held 60 fps.
 */
internal class InstanceBufferStore(private val target: GpuBufferTarget) {
    val surfaceSlices = BrickSliceTable()
    val voxelSlices = BrickSliceTable()
    val counters = InstanceBufferCounters()
    private val reader = RendererDeltaReader()
    private val capacityBytes = IntArray(GridBuffer.values().size)

    /** Brick summary slots in use (draw count of brick styles). */
    var brickSlots = 0
        private set

    /** Region summary slots in use. */
    var regionSlots = 0
        private set

    /** Increments whenever any draw table changes. */
    var tableVersion = 0L
        private set

    fun capacityBytes(buffer: GridBuffer): Int = capacityBytes[buffer.ordinal]

    /** Validates and applies the message `[offset, offset + length)` of [source]; returns its status. */
    fun apply(source: ByteBuffer, offset: Int, length: Int): DeltaStatus {
        val r = reader.attach(source, offset, length)
        val status = r.validate()
        if (status != DeltaStatus.OK) {
            counters.rejected++
            return status
        }
        counters.messages++
        counters.bytes += length
        counters.lastGridVersion = r.gridVersion
        when (r.kind) {
            RendererDeltaLayout.KIND_RESET -> {
                surfaceSlices.clear()
                voxelSlices.clear()
                brickSlots = 0
                regionSlots = 0
                ensure(GridBuffer.SURFACES, r.bufferCapacity)
                counters.resets++
                tableVersion++
            }
            RendererDeltaLayout.KIND_SURFACE_SLICE -> {
                ensure(GridBuffer.SURFACES, r.bufferCapacity)
                if (r.recordCount == 0) {
                    surfaceSlices.remove(r.brickKey)
                } else {
                    writeRecords(GridBuffer.SURFACES, r)
                    surfaceSlices.put(r.brickKey, r.firstSlot, r.recordCount, countSelected(r))
                }
                tableVersion++
            }
            RendererDeltaLayout.KIND_COMPACT_VOXELS -> {
                ensure(GridBuffer.VOXELS, r.bufferCapacity)
                if (r.recordCount == 0) {
                    voxelSlices.remove(r.brickKey)
                } else {
                    writeRecords(GridBuffer.VOXELS, r)
                    voxelSlices.put(r.brickKey, r.firstSlot, r.recordCount, 0)
                }
                tableVersion++
            }
            RendererDeltaLayout.KIND_FREE_SLOTS -> {
                ensure(GridBuffer.SURFACES, r.bufferCapacity)
                val src = r.source
                for (i in 0 until r.recordCount) {
                    val at = r.recordsOffset + i * RendererDeltaLayout.FREE_SLOT_RANGE_BYTES
                    val first = src.getInt(at)
                    val count = src.getInt(at + 4)
                    val bytes = GridBuffer.SURFACES.recordBytes
                    if (first < 0 || count <= 0 || (first.toLong() + count) * bytes > capacityBytes[GridBuffer.SURFACES.ordinal]) continue
                    target.clear(GridBuffer.SURFACES, first * bytes, count * bytes)
                }
            }
            RendererDeltaLayout.KIND_BRICK_SUMMARIES -> {
                ensure(GridBuffer.BRICKS, r.bufferCapacity)
                writeRecords(GridBuffer.BRICKS, r)
                brickSlots = maxOf(brickSlots, r.firstSlot + r.recordCount)
                tableVersion++
            }
            RendererDeltaLayout.KIND_REGION_SUMMARIES -> {
                ensure(GridBuffer.REGIONS, r.bufferCapacity)
                writeRecords(GridBuffer.REGIONS, r)
                regionSlots = maxOf(regionSlots, r.firstSlot + r.recordCount)
                tableVersion++
            }
            RendererDeltaLayout.KIND_OCCUPANCY_MASKS -> counters.ignoredOccupancyMasks++
            RendererDeltaLayout.KIND_FRAME_END -> counters.frameEnds++
        }
        return DeltaStatus.OK
    }

    /** Forgets buffers and draw tables after the GPU buffers were destroyed. */
    fun forgetGpuBuffers() {
        surfaceSlices.clear()
        voxelSlices.clear()
        brickSlots = 0
        regionSlots = 0
        capacityBytes.fill(0)
        tableVersion++
    }

    /** Bytes [apply] would hand to the target for the message, used for budgeting. */
    fun uploadBytes(source: ByteBuffer, offset: Int, length: Int): Int {
        val r = reader.attach(source, offset, length)
        if (r.validate() != DeltaStatus.OK) return 0
        return when (r.kind) {
            RendererDeltaLayout.KIND_SURFACE_SLICE, RendererDeltaLayout.KIND_COMPACT_VOXELS,
            RendererDeltaLayout.KIND_BRICK_SUMMARIES, RendererDeltaLayout.KIND_REGION_SUMMARIES -> r.recordsLength
            RendererDeltaLayout.KIND_FREE_SLOTS -> {
                var bytes = 0
                for (i in 0 until r.recordCount) {
                    bytes += r.source.getInt(r.recordsOffset + i * 8 + 4) * GridBuffer.SURFACES.recordBytes
                }
                bytes
            }
            else -> 0
        }
    }

    private fun ensure(buffer: GridBuffer, capacityRecords: Int) {
        if (capacityRecords <= 0) return
        val bytes = capacityRecords * buffer.recordBytes
        if (bytes > capacityBytes[buffer.ordinal]) {
            target.ensureCapacity(buffer, bytes)
            if (capacityBytes[buffer.ordinal] > 0) counters.capacityGrowths++
            capacityBytes[buffer.ordinal] = bytes
        }
    }

    private fun writeRecords(buffer: GridBuffer, r: RendererDeltaReader) {
        val dst = r.firstSlot.toLong() * buffer.recordBytes
        val length = r.recordsLength
        if (r.firstSlot < 0 || dst + length > capacityBytes[buffer.ordinal]) {
            // A slot beyond the announced capacity: grow to fit rather than drop.
            val needed = ((dst + length + buffer.recordBytes - 1) / buffer.recordBytes).toInt()
            ensure(buffer, needed)
        }
        target.write(buffer, dst.toInt(), r.source, r.recordsOffset, length)
    }

    private fun countSelected(r: RendererDeltaReader): Int {
        var n = 0
        val src = r.source
        val flagsAt = r.recordsOffset + SurfaceInstanceLayout.FLAGS
        for (i in 0 until r.recordCount) {
            val flags = src.get(flagsAt + i * SurfaceInstanceLayout.BYTES).toInt()
            if (flags and SurfaceInstanceLayout.FLAG_SELECTED != 0 &&
                flags and SurfaceInstanceLayout.FLAG_PUBLISHED != 0
            ) n++
        }
        return n
    }
}
