package com.uhg0.ar_flutter_plugin_2.proposal08

/** The schema-5 phone-wide quota policy shared with the Dart reference. */
data class StorageQuotaPolicy(
    val globalQuotaBytes: Long,
    val freeSpaceFloorBytes: Long,
) {
    init {
        require(globalQuotaBytes >= 0 && freeSpaceFloorBytes >= 0)
    }

    companion object {
        fun forVolume(volumeBytes: Long): StorageQuotaPolicy {
            require(volumeBytes > 0)
            val roundedTenth = volumeBytes / 10 + if (volumeBytes % 10 == 0L) 0 else 1
            return StorageQuotaPolicy(
                globalQuotaBytes = volumeBytes / 2,
                freeSpaceFloorBytes = maxOf(4L * 1024 * 1024 * 1024, roundedTenth),
            )
        }
    }
}

data class StorageReservation(
    val reservationId: Long,
    val bytes: Long,
    val ownerId: String,
)

/** Deterministic all-or-none owner for the phone-wide storage byte ledger. */
class StorageBudgetCoordinator(
    val policy: StorageQuotaPolicy,
    committedBytes: Long,
    freeBytes: Long,
) {
    private var committed = checkedNonNegative(committedBytes)
    private var free = checkedNonNegative(freeBytes)
    private var nextReservationId = 1L
    private val reservations = linkedMapOf<Long, StorageReservation>()

    val committedBytes: Long get() = committed
    val freeBytes: Long get() = free
    val reservedBytes: Long
        get() = reservations.values.fold(0L) { total, reservation -> checkedAdd(total, reservation.bytes) }

    fun tryReserve(bytes: Long, ownerId: String = "legacy-reference-owner"): StorageReservation? {
        if (bytes <= 0 || ownerId.isEmpty() || ownerId.length > 128) return null
        val reserved = reservedBytes
        if (committed > Long.MAX_VALUE - reserved ||
            checkedAdd(committed, reserved) > Long.MAX_VALUE - bytes ||
            reserved > Long.MAX_VALUE - bytes ||
            policy.freeSpaceFloorBytes > Long.MAX_VALUE - reserved - bytes
        ) return null

        val committedAfterReservation = checkedAdd(checkedAdd(committed, reserved), bytes)
        val reservedAfterReservation = checkedAdd(reserved, bytes)
        val floorAndReserved = checkedAdd(policy.freeSpaceFloorBytes, reservedAfterReservation)
        if (committedAfterReservation > policy.globalQuotaBytes || floorAndReserved > free) return null

        val reservation = StorageReservation(nextReservationId++, bytes, ownerId)
        reservations[reservation.reservationId] = reservation
        return reservation
    }

    fun commit(reservation: StorageReservation, actualBytes: Long) {
        requireOwned(reservation)
        require(actualBytes in 0..reservation.bytes)
        val otherReservations = reservedBytes - reservation.bytes
        if (free < policy.freeSpaceFloorBytes ||
            actualBytes > free - policy.freeSpaceFloorBytes ||
            otherReservations > free - actualBytes - policy.freeSpaceFloorBytes ||
            actualBytes > policy.globalQuotaBytes - committed
        ) {
            throw IllegalStateException(
                "Storage commit would violate the free-space floor or another reservation.",
            )
        }
        committed = checkedAdd(committed, actualBytes)
        reservations.remove(reservation.reservationId)
        free -= actualBytes
    }

    fun release(reservation: StorageReservation) {
        requireOwned(reservation)
        reservations.remove(reservation.reservationId)
    }

    fun updateFreeBytes(bytes: Long) {
        free = checkedNonNegative(bytes)
    }

    /** Applies only filesystem-confirmed physical reclamation. */
    fun reclaim(bytes: Long) {
        require(bytes > 0 && bytes <= committed)
        committed -= bytes
        free = checkedAdd(free, bytes)
    }

    private fun requireOwned(reservation: StorageReservation) {
        check(reservations[reservation.reservationId] === reservation) {
            "Storage reservation is stale or already closed."
        }
    }

    private fun checkedNonNegative(value: Long): Long {
        require(value >= 0)
        return value
    }

    private fun checkedAdd(left: Long, right: Long): Long {
        require(left >= 0 && right >= 0 && left <= Long.MAX_VALUE - right) {
            "Storage byte arithmetic exceeds PortableOrdinal."
        }
        return left + right
    }
}
