package com.uhg0.ar_flutter_plugin_2.visibilitygrid

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicIntegerArray
import java.util.concurrent.atomic.AtomicLongArray

/** Generation identity carried with one producer-owned sample slot. */
internal data class SampleLeaseGeneration(
    val bindingGeneration: Long,
    val sessionGeneration: Long,
    val groupGeneration: Long,
    val operationGeneration: Long,
) {
    init {
        require(bindingGeneration > 0L)
        require(sessionGeneration > 0L)
        require(groupGeneration > 0L)
        require(operationGeneration > 0L)
    }

    fun matches(ownership: VisibilityObservationOwnership): Boolean =
        bindingGeneration == ownership.bindingGeneration &&
            sessionGeneration == ownership.sessionGeneration &&
            groupGeneration == ownership.groupGeneration &&
            operationGeneration == ownership.operationGeneration

    companion object {
        fun from(ownership: VisibilityObservationOwnership) = SampleLeaseGeneration(
            bindingGeneration = ownership.bindingGeneration,
            sessionGeneration = ownership.sessionGeneration,
            groupGeneration = ownership.groupGeneration,
            operationGeneration = ownership.operationGeneration,
        )
    }
}

/** Reusable primitive storage for one bounded depth handoff. */
internal class DepthSampleColumns(
    val capacity: Int = V2_DEPTH_SAMPLE_CAPACITY,
) {
    init { require(capacity in 1..V2_DEPTH_SAMPLE_CAPACITY) }

    private var xValues = IntArray(capacity)
    private var yValues = IntArray(capacity)
    private var depthValues = IntArray(capacity)
    private var confidenceValues = ByteArray(capacity)

    // Layer candidates are held as primitive columns until the primary tile
    // representatives have been selected. They share the same bounded sample
    // envelope and are flushed without constructing DepthPixelSample objects.
    private var layerXValues = IntArray(capacity)
    private var layerYValues = IntArray(capacity)
    private var layerDepthValues = IntArray(capacity)
    private var layerConfidenceValues = ByteArray(capacity)

    var count: Int = 0
        private set
    var rejectedCount: Int = 0
        private set
    private var layerCount: Int = 0

    internal fun clear(rejected: Int = 0) {
        require(rejected >= 0)
        count = 0
        layerCount = 0
        rejectedCount = rejected
    }

    internal fun append(x: Int, y: Int, depthMillimetres: Int, confidence: Int): Boolean {
        if (count == capacity) return false
        xValues[count] = x
        yValues[count] = y
        depthValues[count] = depthMillimetres
        confidenceValues[count] = confidence.toByte()
        count++
        return true
    }

    internal fun appendLayer(x: Int, y: Int, depthMillimetres: Int, confidence: Int): Boolean {
        if (layerCount == capacity) return false
        layerXValues[layerCount] = x
        layerYValues[layerCount] = y
        layerDepthValues[layerCount] = depthMillimetres
        layerConfidenceValues[layerCount] = confidence.toByte()
        layerCount++
        return true
    }

    internal fun flushLayers() {
        var index = 0
        while (index < layerCount && count < capacity) {
            append(
                layerXValues[index], layerYValues[index],
                layerDepthValues[index], layerConfidenceValues[index].toInt() and 0xff,
            )
            index++
        }
        layerCount = 0
    }

    internal fun reject(count: Int = 1) {
        require(count >= 0)
        rejectedCount = Math.addExact(rejectedCount, count)
    }

    internal fun xAt(index: Int): Int = xValues[index]
    internal fun yAt(index: Int): Int = yValues[index]
    internal fun depthAt(index: Int): Int = depthValues[index]
    internal fun confidenceAt(index: Int): Int = confidenceValues[index].toInt() and 0xff

    /** Array ownership charged to the producer pool, excluding object headers. */
    internal fun primitiveBytes(): Long = Math.multiplyExact(xValues.size.toLong(), 26L)

    /** Full portable ownership for one retained column slot. */
    internal fun portableBytes(): Long = listOf(
        SampleLeasePoolPortableMemory.objectBytes(references = 8, ints = 4),
        SampleLeasePoolPortableMemory.arrayBytes(xValues.size.toLong() * Int.SIZE_BYTES),
        SampleLeasePoolPortableMemory.arrayBytes(yValues.size.toLong() * Int.SIZE_BYTES),
        SampleLeasePoolPortableMemory.arrayBytes(depthValues.size.toLong() * Int.SIZE_BYTES),
        SampleLeasePoolPortableMemory.arrayBytes(confidenceValues.size.toLong()),
        SampleLeasePoolPortableMemory.arrayBytes(layerXValues.size.toLong() * Int.SIZE_BYTES),
        SampleLeasePoolPortableMemory.arrayBytes(layerYValues.size.toLong() * Int.SIZE_BYTES),
        SampleLeasePoolPortableMemory.arrayBytes(layerDepthValues.size.toLong() * Int.SIZE_BYTES),
        SampleLeasePoolPortableMemory.arrayBytes(layerConfidenceValues.size.toLong()),
    ).fold(0L, Math::addExact)

    /** Only called after the slot is free in a terminally closed pool. */
    internal fun releaseStorage() {
        clear()
        xValues = EMPTY_INTS
        yValues = EMPTY_INTS
        depthValues = EMPTY_INTS
        confidenceValues = EMPTY_BYTES
        layerXValues = EMPTY_INTS
        layerYValues = EMPTY_INTS
        layerDepthValues = EMPTY_INTS
        layerConfidenceValues = EMPTY_BYTES
    }

    private companion object {
        val EMPTY_INTS = IntArray(0)
        val EMPTY_BYTES = ByteArray(0)
    }
}

/** Read-only scalar view over a live depth lease. */
internal class BorrowedDepthSamples internal constructor(
    private val lease: DepthSamplesLease,
    private val columns: DepthSampleColumns,
) : DepthEvidenceSampleAccess {
    override val count: Int
        get() { checkActive(); return columns.count }

    override val rejectedCount: Int
        get() { checkActive(); return columns.rejectedCount }

    override fun xAt(index: Int): Int { checkIndex(index); return columns.xAt(index) }
    override fun yAt(index: Int): Int { checkIndex(index); return columns.yAt(index) }
    override fun depthMillimetresAt(index: Int): Int { checkIndex(index); return columns.depthAt(index) }
    override fun confidenceAt(index: Int): Int { checkIndex(index); return columns.confidenceAt(index) }

    internal fun matches(ownership: VisibilityObservationOwnership): Boolean =
        lease.matches(ownership)

    internal fun generation(): SampleLeaseGeneration = lease.generation

    internal fun close() = lease.close()

    private fun checkIndex(index: Int) {
        checkActive()
        require(index in 0 until columns.count)
    }

    private fun checkActive() {
        check(lease.isActive()) { "depth sample lease is closed or stale" }
    }
}

/** Idempotent ownership token transferred from depth producer to the consumer. */
internal class DepthSamplesLease internal constructor(
    private val pool: DepthSamplesLeasePool,
    val slotId: Int,
    val epoch: Long,
    val generation: SampleLeaseGeneration,
    private val columns: DepthSampleColumns,
) : AutoCloseable {
    private val closed = AtomicBoolean(false)

    val samples: BorrowedDepthSamples = BorrowedDepthSamples(this, columns)

    internal fun isActive(): Boolean =
        !closed.get() && pool.isActive(slotId, epoch)

    internal fun matches(ownership: VisibilityObservationOwnership): Boolean =
        generation.matches(ownership)

    internal fun clear(rejected: Int = 0) {
        check(isActive()) { "depth sample lease is closed or stale" }
        columns.clear(rejected)
    }

    internal fun append(x: Int, y: Int, depthMillimetres: Int, confidence: Int): Boolean {
        check(isActive()) { "depth sample lease is closed or stale" }
        return columns.append(x, y, depthMillimetres, confidence)
    }

    internal fun appendLayer(x: Int, y: Int, depthMillimetres: Int, confidence: Int): Boolean {
        check(isActive()) { "depth sample lease is closed or stale" }
        return columns.appendLayer(x, y, depthMillimetres, confidence)
    }

    internal fun flushLayers() {
        check(isActive()) { "depth sample lease is closed or stale" }
        columns.flushLayers()
    }

    internal fun reject(count: Int = 1) {
        check(isActive()) { "depth sample lease is closed or stale" }
        columns.reject(count)
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) pool.release(slotId, epoch)
    }
}

/** Two-slot, non-blocking depth handoff pool. */
internal class DepthSamplesLeasePool(
    capacity: Int = V2_DEPTH_SAMPLE_CAPACITY,
    private val slotCount: Int = 2,
) : AutoCloseable {
    init { require(slotCount == 2) }

    private val columns = Array(slotCount) { DepthSampleColumns(capacity) }
    private val maximumColumnBytes = columns.first().portableBytes() * slotCount.toLong()
    private val epochs = AtomicLongArray(slotCount)
    private val leased = AtomicIntegerArray(slotCount)
    private val peakOutstanding = AtomicInteger(0)
    private val closed = AtomicBoolean(false)

    fun tryAcquire(ownership: VisibilityObservationOwnership): DepthSamplesLease? =
        tryAcquire(SampleLeaseGeneration.from(ownership))

    internal fun tryAcquire(generation: SampleLeaseGeneration): DepthSamplesLease? {
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
            return DepthSamplesLease(this, slot, epoch, generation, columns[slot])
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
