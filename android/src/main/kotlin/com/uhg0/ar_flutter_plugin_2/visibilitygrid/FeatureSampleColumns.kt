package com.uhg0.ar_flutter_plugin_2.visibilitygrid

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicIntegerArray
import java.util.concurrent.atomic.AtomicLongArray

/** Reusable primitive storage for one bounded feature handoff. */
internal class FeatureSampleColumns(
    val capacity: Int = V2_FEATURE_SAMPLE_CAPACITY,
) {
    init { require(capacity in 1..V2_FEATURE_SAMPLE_CAPACITY) }

    private var idValues = IntArray(capacity)
    private var xValues = DoubleArray(capacity)
    private var yValues = DoubleArray(capacity)
    private var zValues = DoubleArray(capacity)
    private var confidenceValues = DoubleArray(capacity)
    private var seenIds = IntArray(nextPowerOfTwo(capacity * 2)) { -1 }
    private val seenMask = seenIds.size - 1

    var count: Int = 0
        private set
    var rejectedCount: Int = 0
        private set

    internal fun clear(rejected: Int = 0) {
        require(rejected >= 0)
        count = 0
        rejectedCount = rejected
        seenIds.fill(-1)
    }

    internal fun append(
        id: Int,
        xWorld: Double,
        yWorld: Double,
        zWorld: Double,
        confidence: Double,
    ): Boolean {
        if (count == capacity) return false
        idValues[count] = id
        xValues[count] = xWorld
        yValues[count] = yWorld
        zValues[count] = zWorld
        confidenceValues[count] = confidence
        count++
        return true
    }

    internal fun reject(count: Int = 1) {
        require(count >= 0)
        rejectedCount = Math.addExact(rejectedCount, count)
    }

    internal fun acceptId(id: Int): Boolean {
        var index = mix(id) and seenMask
        while (true) {
            val current = seenIds[index]
            if (current == id) return false
            if (current == -1) {
                seenIds[index] = id
                return true
            }
            index = (index + 1) and seenMask
        }
    }

    internal fun idAt(index: Int): Int = idValues[index]
    internal fun xAt(index: Int): Double = xValues[index]
    internal fun yAt(index: Int): Double = yValues[index]
    internal fun zAt(index: Int): Double = zValues[index]
    internal fun confidenceAt(index: Int): Double = confidenceValues[index]

    /** Array ownership charged to the producer pool, excluding object headers. */
    internal fun primitiveBytes(): Long = Math.addExact(
        Math.multiplyExact(idValues.size.toLong(), 36L),
        Math.multiplyExact(seenIds.size.toLong(), Int.SIZE_BYTES.toLong()),
    )

    /** Full portable ownership for one retained column slot. */
    internal fun portableBytes(): Long = listOf(
        SampleLeasePoolPortableMemory.objectBytes(references = 6, ints = 3),
        SampleLeasePoolPortableMemory.arrayBytes(idValues.size.toLong() * Int.SIZE_BYTES),
        SampleLeasePoolPortableMemory.arrayBytes(xValues.size.toLong() * Double.SIZE_BYTES),
        SampleLeasePoolPortableMemory.arrayBytes(yValues.size.toLong() * Double.SIZE_BYTES),
        SampleLeasePoolPortableMemory.arrayBytes(zValues.size.toLong() * Double.SIZE_BYTES),
        SampleLeasePoolPortableMemory.arrayBytes(confidenceValues.size.toLong() * Double.SIZE_BYTES),
        SampleLeasePoolPortableMemory.arrayBytes(seenIds.size.toLong() * Int.SIZE_BYTES),
    ).fold(0L, Math::addExact)

    /** Only called after the slot is free in a terminally closed pool. */
    internal fun releaseStorage() {
        clear()
        idValues = EMPTY_INTS
        xValues = EMPTY_DOUBLES
        yValues = EMPTY_DOUBLES
        zValues = EMPTY_DOUBLES
        confidenceValues = EMPTY_DOUBLES
        seenIds = EMPTY_INTS
    }

    private fun mix(value: Int): Int {
        var mixed = value * -0x7a143595
        mixed = mixed xor (mixed ushr 16)
        return mixed
    }

    private companion object {
        val EMPTY_INTS = IntArray(0)
        val EMPTY_DOUBLES = DoubleArray(0)
        fun nextPowerOfTwo(value: Int): Int {
            var result = 1
            while (result < value) result = Math.multiplyExact(result, 2)
            return result
        }
    }
}

/** Read-only scalar view over a live feature lease. */
internal class BorrowedFeatureSamples internal constructor(
    private val lease: FeatureSamplesLease,
    private val columns: FeatureSampleColumns,
) {
    val count: Int
        get() { checkActive(); return columns.count }

    val rejectedCount: Int
        get() { checkActive(); return columns.rejectedCount }

    fun idAt(index: Int): Int { checkIndex(index); return columns.idAt(index) }
    fun xWorldAt(index: Int): Double { checkIndex(index); return columns.xAt(index) }
    fun yWorldAt(index: Int): Double { checkIndex(index); return columns.yAt(index) }
    fun zWorldAt(index: Int): Double { checkIndex(index); return columns.zAt(index) }
    fun confidenceAt(index: Int): Double { checkIndex(index); return columns.confidenceAt(index) }

    internal fun matches(ownership: VisibilityObservationOwnership): Boolean =
        lease.matches(ownership)

    internal fun generation(): SampleLeaseGeneration = lease.generation

    internal fun close() = lease.close()

    private fun checkIndex(index: Int) {
        checkActive()
        require(index in 0 until columns.count)
    }

    private fun checkActive() {
        check(lease.isActive()) { "feature sample lease is closed or stale" }
    }
}

/** Idempotent ownership token transferred from feature producer to the consumer. */
internal class FeatureSamplesLease internal constructor(
    private val pool: FeatureSamplesLeasePool,
    val slotId: Int,
    val epoch: Long,
    val generation: SampleLeaseGeneration,
    private val columns: FeatureSampleColumns,
) : AutoCloseable {
    private val closed = AtomicBoolean(false)

    val samples: BorrowedFeatureSamples = BorrowedFeatureSamples(this, columns)

    internal fun isActive(): Boolean =
        !closed.get() && pool.isActive(slotId, epoch)

    internal fun matches(ownership: VisibilityObservationOwnership): Boolean =
        generation.matches(ownership)

    internal fun clear(rejected: Int = 0) {
        check(isActive()) { "feature sample lease is closed or stale" }
        columns.clear(rejected)
    }

    internal fun append(
        id: Int,
        xWorld: Double,
        yWorld: Double,
        zWorld: Double,
        confidence: Double,
    ): Boolean {
        check(isActive()) { "feature sample lease is closed or stale" }
        return columns.append(id, xWorld, yWorld, zWorld, confidence)
    }

    internal fun acceptId(id: Int): Boolean {
        check(isActive()) { "feature sample lease is closed or stale" }
        return columns.acceptId(id)
    }

    /** Producer-only count read while ownership has not yet transferred. */
    internal fun producerCountForFill(): Int = columns.count

    internal fun reject(count: Int = 1) {
        check(isActive()) { "feature sample lease is closed or stale" }
        columns.reject(count)
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) pool.release(slotId, epoch)
    }
}

/** Two-slot, non-blocking feature handoff pool. */
internal class FeatureSamplesLeasePool(
    capacity: Int = V2_FEATURE_SAMPLE_CAPACITY,
    private val slotCount: Int = 2,
) : AutoCloseable {
    init { require(slotCount == 2) }

    private val columns = Array(slotCount) { FeatureSampleColumns(capacity) }
    private val maximumColumnBytes = columns.first().portableBytes() * slotCount.toLong()
    private val epochs = AtomicLongArray(slotCount)
    private val leased = AtomicIntegerArray(slotCount)
    private val peakOutstanding = AtomicInteger(0)
    private val closed = AtomicBoolean(false)

    fun tryAcquire(ownership: VisibilityObservationOwnership): FeatureSamplesLease? =
        tryAcquire(SampleLeaseGeneration.from(ownership))

    internal fun tryAcquire(generation: SampleLeaseGeneration): FeatureSamplesLease? {
        if (closed.get()) return null
        for (slot in 0 until slotCount) {
            if (!leased.compareAndSet(slot, 0, 1)) continue
            if (closed.get()) {
                leased.set(slot, 0)
                retireFreeStorage(slot)
                return null
            }
            val previous = epochs.get(slot)
            val epoch = if (previous == Long.MAX_VALUE) 1L else previous + 1L
            epochs.set(slot, epoch)
            columns[slot].clear()
            if (closed.get() || leased.get(slot) != 1) {
                leased.compareAndSet(slot, 1, 0)
                if (closed.get()) retireFreeStorage(slot)
                return null
            }
            updatePeak((0 until slotCount).count { leased.get(it) == 1 })
            return FeatureSamplesLease(this, slot, epoch, generation, columns[slot])
        }
        return null
    }

    internal fun isActive(slotId: Int, epoch: Long): Boolean =
        slotId in 0 until slotCount &&
            leased.get(slotId) == 1 && epochs.get(slotId) == epoch

    internal fun release(slotId: Int, epoch: Long): Boolean {
        if (slotId !in 0 until slotCount || epochs.get(slotId) != epoch) return false
        if (!leased.compareAndSet(slotId, 1, 2)) return false
        columns[slotId].clear()
        leased.set(slotId, 0)
        if (closed.get()) retireFreeStorage(slotId)
        return true
    }

    internal fun leasedCount(): Int = (0 until slotCount).count { leased.get(it) == 1 }

    internal fun retainedPrimitiveBytes(): Long =
        columns.fold(0L) { total, value -> Math.addExact(total, value.primitiveBytes()) }

    internal fun receipt(): SampleLeasePoolReceipt = SampleLeasePoolReceipt(
        capacity = columns.first().capacity,
        slotCount = slotCount,
        growthCount = 0,
        peakOutstanding = peakOutstanding.get(),
        outstanding = leasedCount(),
        primitiveBytes = retainedPrimitiveBytes(),
        portableOwnedBytes = SampleLeasePoolPortableMemory.ownedBytes(
            poolBytes = SampleLeasePoolPortableMemory.poolBytes(slotCount),
            retainedColumnBytes = columns.fold(0L) { total, value ->
                Math.addExact(total, value.portableBytes())
            },
            outstanding = leasedCount(),
            closed = closed.get(),
        ),
        portableMaximumOwnedBytes = Math.addExact(
            SampleLeasePoolPortableMemory.poolBytes(slotCount),
            Math.addExact(
                maximumColumnBytes,
                slotCount.toLong() * Math.addExact(
                    SampleLeasePoolPortableMemory.liveLeaseBytes(),
                    SampleLeasePoolPortableMemory.FROZEN_POSE_BYTES,
                ),
            ),
        ),
    )

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        for (slot in 0 until slotCount) retireFreeStorage(slot)
    }

    private fun retireFreeStorage(slot: Int) {
        if (!leased.compareAndSet(slot, 0, 2)) return
        columns[slot].releaseStorage()
        leased.set(slot, 0)
    }

    private fun updatePeak(current: Int) {
        while (true) {
            val previous = peakOutstanding.get()
            if (current <= previous || peakOutstanding.compareAndSet(previous, current)) return
        }
    }
}
