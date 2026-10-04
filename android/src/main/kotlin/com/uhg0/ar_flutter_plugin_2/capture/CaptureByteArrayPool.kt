package com.uhg0.ar_flutter_plugin_2.capture

import java.util.ArrayDeque
import java.util.IdentityHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Owner-private, nonblocking scratch storage for serialized camera conversion.
 *
 * The pool owns at most [maximumSlots] arrays and [maximumBytes] bytes across
 * both retained and checked-out leases. An unavailable slot or a request that
 * would cross the byte bound is refused immediately. Camera callbacks must
 * treat a null lease as backpressure; this class never waits for a consumer.
 *
 * Only [Lease] instances created by this pool can return storage. A lease
 * remains valid while it is held, including after [close], so an in-flight
 * encoder can finish without the pool reporting ownership as free early.
 * Published capture bytes must be copied before the lease is closed.
 */
internal class CaptureByteArrayPool(
    private val maximumSlots: Int = 4,
    private val maximumBytes: Long = Long.MAX_VALUE,
) : AutoCloseable {
    init {
        require(maximumSlots > 0)
        require(maximumBytes >= 0L)
    }

    /** A one-shot owner token for one mutable scratch array. */
    internal class Lease internal constructor(
        internal val owner: CaptureByteArrayPool,
        internal val epoch: Long,
    ) : AutoCloseable {
        private val closed = AtomicBoolean(false)

        /**
         * Returns the still-owned array. Access after this lease is closed is
         * rejected so a stale consumer cannot observe a recycled buffer.
         */
        val buffer: ByteArray
            get() = owner.borrowedBuffer(this, epoch)

        override fun close() {
            if (closed.compareAndSet(false, true)) {
                owner.release(this, epoch)
            }
        }
    }

    /** Scalar ownership evidence; no arrays or image data are retained here. */
    internal data class Receipt(
        val maximumSlots: Int,
        val maximumBytes: Long,
        val retainedSlots: Int,
        val inUseSlots: Int,
        val ownedSlots: Int,
        val retainedCapacityBytes: Long,
        val inUseCapacityBytes: Long,
        val ownedCapacityBytes: Long,
        val peakOwnedCapacityBytes: Long,
        val peakInUseSlots: Int,
        val growthEvents: Long,
        val refusalCount: Long,
        val closed: Boolean,
    )

    private val available = ArrayDeque<ByteArray>()
    private val leases = IdentityHashMap<Lease, ByteArray>()
    private var retainedBytes = 0L
    private var inUseBytes = 0L
    private var ownedBytes = 0L
    private var peakOwnedBytes = 0L
    private var peakInUseSlotsValue = 0
    private var growthEventsValue = 0L
    private var refusalCountValue = 0L
    private var epoch = 1L
    private var closed = false

    /**
     * Acquires a reusable array or allocates one inside the explicit owner
     * bound. Returns null immediately when the owner is at capacity.
     */
    @Synchronized
    fun acquire(minimumCapacity: Int): Lease? {
        require(minimumCapacity >= 0)
        if (closed) {
            refusalCountValue += 1
            return null
        }

        val iterator = available.iterator()
        var candidate: ByteArray? = null
        while (iterator.hasNext()) {
            val next = iterator.next()
            if (next.size >= minimumCapacity) {
                iterator.remove()
                retainedBytes -= next.size.toLong()
                candidate = next
                break
            }
        }

        val buffer = candidate ?: run {
            val requestedBytes = minimumCapacity.toLong()
            if (leases.size + available.size >= maximumSlots ||
                ownedBytes > maximumBytes - requestedBytes
            ) {
                refusalCountValue += 1
                return null
            }
            growthEventsValue += 1
            ByteArray(minimumCapacity).also {
                ownedBytes += it.size.toLong()
            }
        }

        val lease = Lease(this, epoch)
        leases[lease] = buffer
        inUseBytes += buffer.size.toLong()
        val inUseSlots = leases.size
        if (inUseSlots > peakInUseSlotsValue) peakInUseSlotsValue = inUseSlots
        if (ownedBytes > peakOwnedBytes) peakOwnedBytes = ownedBytes
        return lease
    }

    /** Releases a token supplied by a consumer or a test owner. */
    internal fun release(lease: Lease): Boolean = release(lease, lease.epoch)

    @Synchronized
    private fun release(lease: Lease, leaseEpoch: Long): Boolean {
        if (leaseEpoch != epoch) return false
        val buffer = leases.remove(lease) ?: return false
        inUseBytes -= buffer.size.toLong()
        if (closed) {
            ownedBytes -= buffer.size.toLong()
            return true
        }
        available.addLast(buffer)
        retainedBytes += buffer.size.toLong()
        return true
    }

    @Synchronized
    private fun borrowedBuffer(lease: Lease, leaseEpoch: Long): ByteArray {
        if (leaseEpoch != epoch || lease.owner !== this) {
            throw IllegalStateException("Capture scratch lease belongs to another pool")
        }
        return leases[lease]
            ?: throw IllegalStateException("Capture scratch lease is closed or stale")
    }

    @Synchronized
    internal fun retainedCount(): Int = available.size

    @Synchronized
    internal fun inUseCount(): Int = leases.size

    @Synchronized
    internal fun retainedCapacityBytes(): Long = retainedBytes

    @Synchronized
    internal fun ownedCapacityBytes(): Long = ownedBytes

    @Synchronized
    internal fun receipt(): Receipt =
        Receipt(
            maximumSlots = maximumSlots,
            maximumBytes = maximumBytes,
            retainedSlots = available.size,
            inUseSlots = leases.size,
            ownedSlots = leases.size + available.size,
            retainedCapacityBytes = retainedBytes,
            inUseCapacityBytes = inUseBytes,
            ownedCapacityBytes = ownedBytes,
            peakOwnedCapacityBytes = peakOwnedBytes,
            peakInUseSlots = peakInUseSlotsValue,
            growthEvents = growthEventsValue,
            refusalCount = refusalCountValue,
            closed = closed,
        )

    /**
     * Stops new acquisitions and drops every retained array. Checked-out
     * leases remain owned until their consumers close them; this keeps the
     * resource ledger truthful while an encoder finishes after cleanup.
     */
    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        ownedBytes -= retainedBytes
        available.clear()
        retainedBytes = 0L
    }
}
