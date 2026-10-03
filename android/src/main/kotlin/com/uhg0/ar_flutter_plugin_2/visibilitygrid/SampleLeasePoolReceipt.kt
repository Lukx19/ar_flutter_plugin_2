package com.uhg0.ar_flutter_plugin_2.visibilitygrid

/**
 * Fixed logical ownership model for one packed producer pool.
 *
 * The model deliberately uses the same conservative header/alignment literals
 * used by the canonical receipts.  It is a portable capacity receipt, not a
 * claim about a particular ART allocator's object sizes.
 */
internal object SampleLeasePoolPortableMemory {
    const val OBJECT_HEADER_BYTES = 32L
    const val ARRAY_HEADER_BYTES = 24L
    const val REFERENCE_BYTES = 8L
    const val INT_BYTES = 4L
    const val LONG_BYTES = 8L
    const val BOOLEAN_BYTES = 1L
    const val ALIGNMENT_BYTES = 8L

    /** One retained pose is paired with each live packed observation lease. */
    const val FROZEN_POSE_BYTES = 920L

    fun aligned(bytes: Long): Long {
        require(bytes >= 0L)
        return Math.addExact(bytes, ALIGNMENT_BYTES - 1L) and -ALIGNMENT_BYTES
    }

    fun arrayBytes(payloadBytes: Long): Long = Math.addExact(
        ARRAY_HEADER_BYTES,
        aligned(payloadBytes),
    )

    fun objectBytes(
        references: Int = 0,
        ints: Int = 0,
        longs: Int = 0,
        booleans: Int = 0,
    ): Long {
        require(references >= 0 && ints >= 0 && longs >= 0 && booleans >= 0)
        return aligned(
            Math.addExact(
                OBJECT_HEADER_BYTES,
                Math.addExact(
                    Math.addExact(references.toLong() * REFERENCE_BYTES, ints.toLong() * INT_BYTES),
                    Math.addExact(longs.toLong() * LONG_BYTES, booleans.toLong() * BOOLEAN_BYTES),
                ),
            ),
        )
    }

    fun poolBytes(slotCount: Int): Long {
        require(slotCount > 0)
        val poolObject = objectBytes(references = 5, ints = 1, longs = 1)
        val columnsArray = arrayBytes(slotCount.toLong() * REFERENCE_BYTES)
        val epochs = Math.addExact(
            objectBytes(references = 1, ints = 1),
            arrayBytes(slotCount.toLong() * LONG_BYTES),
        )
        val leased = Math.addExact(
            objectBytes(references = 1, ints = 1),
            arrayBytes(slotCount.toLong() * INT_BYTES),
        )
        val peak = objectBytes(ints = 1)
        val closed = objectBytes(booleans = 1)
        return listOf(poolObject, columnsArray, epochs, leased, peak, closed)
            .fold(0L, Math::addExact)
    }

    /** Per live lease: lease, borrowed view, generation and close atomics. */
    fun liveLeaseBytes(): Long = listOf(
        objectBytes(references = 5, ints = 1, longs = 1), // Lease, including samples view
        objectBytes(references = 2), // BorrowedFeature/DepthSamples
        objectBytes(booleans = 1), // Lease.closed
        objectBytes(longs = 4), // SampleLeaseGeneration
    ).fold(0L, Math::addExact)

    fun ownedBytes(
        poolBytes: Long,
        retainedColumnBytes: Long,
        outstanding: Int,
        closed: Boolean,
    ): Long {
        require(poolBytes >= 0L && retainedColumnBytes >= 0L && outstanding >= 0)
        // Closing the pool stops new leases and retires free columns, but the
        // receipt owner still strongly owns the pool's atomics and slot array.
        // Keep charging that metadata until the pool owner itself is released;
        // a terminal close must not report a false zero while this object is
        // still reachable.
        return listOf(
            poolBytes,
            retainedColumnBytes,
            Math.multiplyExact(outstanding.toLong(), liveLeaseBytes()),
            Math.multiplyExact(outstanding.toLong(), FROZEN_POSE_BYTES),
        ).fold(0L, Math::addExact)
    }
}

/**
 * Bounded producer handoff ownership.  The pool has fixed capacity, therefore
 * growth is intentionally zero; the peak and outstanding counts make deferred
 * and replacement ownership visible without retaining a lease object.
 */
internal data class SampleLeasePoolReceipt(
    val capacity: Int,
    val slotCount: Int,
    val growthCount: Int,
    val peakOutstanding: Int,
    val outstanding: Int,
    val primitiveBytes: Long,
    /** Current pool/column/lease/pose ownership, excluding semantic payload. */
    val portableOwnedBytes: Long,
    /** Maximum warm pool ownership for all fixed slots and two live leases. */
    val portableMaximumOwnedBytes: Long,
) {
    internal fun toWireMap(): Map<String, Any> = mapOf(
        "capacity" to capacity,
        "slotCount" to slotCount,
        "growthCount" to growthCount,
        "peakOutstanding" to peakOutstanding,
        "outstanding" to outstanding,
        "primitiveBytes" to primitiveBytes,
        "portableOwnedBytes" to portableOwnedBytes,
        "portableMaximumOwnedBytes" to portableMaximumOwnedBytes,
    )
}
