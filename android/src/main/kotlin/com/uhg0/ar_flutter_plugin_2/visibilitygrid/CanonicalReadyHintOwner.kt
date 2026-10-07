package com.uhg0.ar_flutter_plugin_2.visibilitygrid

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean

/** Fixed advisory envelope. No sensor time, surface bytes or protocol acknowledgement. */
internal class CanonicalReadyHintV1 private constructor(val bytes: ByteArray) {
    val transactionId: Long get() = longAt(9)
    val geometryRevision: Long get() = longAt(10)
    val lineageRevision: Long get() = longAt(11)
    private fun longAt(index: Int) = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        .getLong(HEADER_BYTES + IDENTITY_BYTES + index * 8)

    fun sameOwner(other: CanonicalReadyHintV1): Boolean =
        (0 until OWNER_BYTES).all { bytes[it] == other.bytes[it] }

    companion object {
        const val BYTES = 200
        const val HEADER_BYTES = 8
        const val IDENTITY_BYTES = 96
        const val OWNER_BYTES = 176
        private const val MAGIC = 0x31595243 // CRY1, little endian

        fun create(identities: List<ByteArray>, ordinals: LongArray): CanonicalReadyHintV1 {
            require(identities.size == 6 && identities.all { it.size == 16 })
            require(ordinals.size == 12 && ordinals[0] >= 0 &&
                (1..11).all { if (it == 9) ordinals[it] >= 0 else ordinals[it] > 0 })
            val buffer = ByteBuffer.allocate(BYTES).order(ByteOrder.LITTLE_ENDIAN)
            buffer.putInt(MAGIC).putShort(1).putShort(BYTES.toShort())
            identities.forEach(buffer::put)
            ordinals.forEach(buffer::putLong)
            return CanonicalReadyHintV1(buffer.array())
        }

        fun decode(bytes: ByteArray?): CanonicalReadyHintV1? {
            if (bytes == null || bytes.size != BYTES) return null
            val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            if (buffer.int != MAGIC || buffer.short.toInt() != 1 || buffer.short.toInt() != BYTES) return null
            buffer.position(HEADER_BYTES + IDENTITY_BYTES)
            if (buffer.long < 0) return null
            for (index in 1..11) {
                val ordinal = buffer.long
                if (if (index == 9) ordinal < 0 else ordinal <= 0) return null
            }
            return CanonicalReadyHintV1(bytes)
        }
    }
}

/**
 * One native latest + one sent envelope, one posted/inflight callback globally
 * across binding replacements. Queueing only stages. publish() is called AFTER
 * integration/binding/renderer critical sections, before posting to main.
 * Register observes an already-published latest atomically. Lost delivery
 * blocks further advisory sends, never exact stream work or fallback polling.
 *
 * Portable: <=2 x 256 envelope slots + 16 x 8 diagnostic counters. A separate
 * 4 KiB MODELED native implementation allowance covers owner/monitor, arrays,
 * runnable, reply callback and transient codec copies. Actual VM heap remains
 * UNMEASURED and is an adoption gate; this model is not heap pass evidence.
 * reset fences callbacks without replacing this shared owner or queuing new
 * replies behind an unknown old send. The callback never captures an envelope.
 */
internal class CanonicalReadyHintOwner(
    private val post: (() -> Unit) -> Unit,
    private val send: (ByteArray, (Boolean) -> Unit) -> Unit,
    private val ownsProcessReservation: Boolean = false,
) {
    private val lock = Any()
    private var latest: CanonicalReadyHintV1? = null
    private var sent: CanonicalReadyHintV1? = null
    private var published = false
    private var subscribed = false
    private var posted = false
    private var inFlight = false
    private var closing = false
    private var processReservationReleased = false
    private var epoch = 0L
    private var stagingOrdinal = 0L
    private var releasedTransaction = 0L
    private var releasedGeometry = 0L
    private var releasedLineage = 0L
    private var offered = 0L
    private var coalesced = 0L
    private var registrations = 0L
    private var invalidations = 0L
    private var posts = 0L
    private var sends = 0L
    private var delivered = 0L
    private var failed = 0L
    private var suppressed = 0L
    private var drained = 0L
    private var maxHints = 0L
    private var maxCallbacks = 0L

    fun stage(hint: CanonicalReadyHintV1) = synchronized(lock) {
        if (closing) return@synchronized
        offered++
        stagingOrdinal++
        if (latest != null) coalesced++
        latest = hint
        published = false
        chargePeak()
    }

    fun publicationStamp(): Long = synchronized(lock) { stagingOrdinal }

    fun publish(stamp: Long) {
        synchronized(lock) {
            if (latest != null && stamp == stagingOrdinal && !closing) {
                published = true
                releasedTransaction = latest!!.transactionId
                releasedGeometry = latest!!.geometryRevision
                releasedLineage = latest!!.lineageRevision
            }
        }
        schedule()
    }

    /** Rebuilds a paused slot only for the exact already-released pending cut. */
    fun restorePublished(hint: CanonicalReadyHintV1) = synchronized(lock) {
        if (!closing && latest == null && hint.transactionId == releasedTransaction &&
            hint.geometryRevision == releasedGeometry && hint.lineageRevision == releasedLineage) {
            latest = hint
            published = true
            chargePeak()
        }
    }

    fun register() {
        synchronized(lock) { if (!closing) { subscribed = true; registrations++ } }
        schedule()
    }

    fun unregister() = synchronized(lock) {
        subscribed = false
        epoch++
        invalidations++
        latest = null
        sent = null
        published = false
    }

    fun reset() = synchronized(lock) {
        subscribed = false
        epoch++
        invalidations++
        latest = null
        sent = null
        published = false
        stagingOrdinal++
        releasedTransaction = 0L
        releasedGeometry = 0L
        releasedLineage = 0L
        // Keep at most one unknown transport callback reserved across rollover.
    }

    fun close() {
        synchronized(lock) { closing = true }
        reset()
        releaseProcessReservationIfDrained()
    }

    private fun releaseProcessReservationIfDrained() = synchronized(lock) {
        if (ownsProcessReservation && !processReservationReleased && closing && !posted && !inFlight) {
            processReservationReleased = true
            processReservation.set(false)
        }
    }

    fun acknowledged(transactionId: Long) = synchronized(lock) {
        if ((latest?.transactionId ?: Long.MAX_VALUE) <= transactionId) latest = null
    }

    private fun schedule() {
        val generation = synchronized(lock) {
            if (closing || !subscribed || !published || latest == null || posted || inFlight) return
            posted = true
            posts++
            chargePeak()
            epoch
        }
        post { drain(generation) }
    }

    private fun drain(generation: Long) {
        val bytes = synchronized(lock) {
            posted = false
            drained++
            if (generation != epoch || !subscribed || !published || latest == null || inFlight) {
                suppressed++
                null
            } else {
                sent = latest
                latest = null
                inFlight = true
                sends++
                chargePeak()
                sent!!.bytes
            }
        }
        if (bytes == null) { releaseProcessReservationIfDrained(); schedule(); return }
        try {
            send(bytes) { accepted ->
                synchronized(lock) {
                    if (generation == epoch && accepted) delivered++ else failed++
                    sent = null
                    inFlight = false
                    drained++
                }
                releaseProcessReservationIfDrained()
                schedule()
            }
        } catch (_: RuntimeException) {
            synchronized(lock) { sent = null; inFlight = false; failed++; drained++ }
            releaseProcessReservationIfDrained()
            schedule()
        }
    }

    private fun chargePeak() {
        maxHints = maxOf(maxHints, (if (latest != null) 1L else 0L) + (if (sent != null) 1L else 0L))
        maxCallbacks = maxOf(maxCallbacks, if (posted || inFlight) 1L else 0L)
    }

    /** 128 diagnostic bytes + 56 scalar state bytes within envelope-slot padding. */
    fun portableOwnedBytes(): Long = synchronized(lock) {
        184L + (if (latest != null) CanonicalReadyHintV1.BYTES else 0) +
            (if (sent != null) CanonicalReadyHintV1.BYTES else 0)
    }

    fun snapshot(): Map<String, Any> = synchronized(lock) { mapOf(
        "enabled" to true, "offered" to offered, "coalesced" to coalesced,
        "registrations" to registrations, "invalidations" to invalidations,
        "posted" to posts, "sent" to sends, "delivered" to delivered,
        "failed" to failed, "suppressed" to suppressed, "drained" to drained,
        "maxHints" to maxHints, "maxCallbacks" to maxCallbacks,
        "pendingHints" to (if (latest != null) 1 else 0) + (if (sent != null) 1 else 0),
        "pendingCallbacks" to if (posted || inFlight) 1 else 0,
        "portableEnvelopeBytes" to CanonicalReadyHintV1.BYTES,
        "unknownTransportReserved" to (inFlight && sent == null),
        "modeledImplementationAllowanceBytes" to 4096,
        "portableOwnerBytes" to portableOwnedBytes(),
    ) }

    companion object {
        private val processReservation = AtomicBoolean(false)
        /** One live enabled native owner, including an unresolved old callback. */
        fun tryCreate(post: (() -> Unit) -> Unit, send: (ByteArray, (Boolean) -> Unit) -> Unit): CanonicalReadyHintOwner? =
            if (processReservation.compareAndSet(false, true)) CanonicalReadyHintOwner(post, send, true) else null
    }
}
