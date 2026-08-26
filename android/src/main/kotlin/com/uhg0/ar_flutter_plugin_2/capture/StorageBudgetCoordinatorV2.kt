package com.uhg0.ar_flutter_plugin_2.capture

import java.io.File
import java.security.MessageDigest

data class StorageBudgetPolicyV2(val quotaBytes: Long, val freeSpaceFloorBytes: Long) {
    init { require(quotaBytes >= 0 && freeSpaceFloorBytes >= 0) }
}

data class StorageBudgetReservationV2(val token: String, val owner: String, val bytes: Long)

/** Process-global, refreshed, physically-backed storage authority. */
class StorageBudgetCoordinatorV2(
    directory: File,
    private val policy: StorageBudgetPolicyV2,
    filesystemBackend: DescriptorFilesystemV2 = AndroidDescriptorFilesystemV2,
    private val freeBytes: () -> Long = { directory.usableSpace },
) {
    private val files = SafeFilesystemV2(directory, DurableStoreFaultInjectorV2 { }, filesystemBackend)
    private val ledger = files.child("ledger-v2")
    private val reservationsDirectory = files.child("reservations-v2")
    private var committed = 0L
    private val reservations = linkedMapOf<String, StorageBudgetReservationV2>()

    init {
        files.ensureDirectory(reservationsDirectory)
        withAuthority { Unit }
    }

    fun reserve(owner: String, bytes: Long): StorageBudgetReservationV2? = withAuthority {
        require(owner.matches(OWNER)) { "Storage reservation owner is non-canonical" }
        if (bytes <= 0 || !canCharge(bytes)) return@withAuthority null
        val token = sha256("$owner:$bytes:${nextTokenLocked()}".toByteArray()).hex()
        val reservation = StorageBudgetReservationV2(token, owner, bytes)
        val allocation = allocationFile(token)
        val metadata = metadataFile(token)
        try {
            files.allocateExclusive(allocation, bytes)
            files.writeExclusive(metadata, "$owner\n$bytes\n".toByteArray(), DurableStoreFaultPointV2.ACCEPTED_RECORD)
            reservations[token] = reservation
            persistLedgerLocked()
            reservation
        } catch (error: Throwable) {
            if (files.isFile(metadata)) files.delete(metadata, DurableStoreFaultPointV2.DELETE_RECLAIM)
            if (files.isFile(allocation)) files.delete(allocation, DurableStoreFaultPointV2.DELETE_RECLAIM)
            throw error
        }
    }

    fun commit(reservation: StorageBudgetReservationV2, actualBytes: Long) = withAuthority {
        val current = requireReservationLocked(reservation)
        require(actualBytes in 0..current.bytes)
        val other = reservedBytesLocked() - current.bytes
        if (actualBytes > policy.quotaBytes - committed ||
            actualBytes > freeBytes() - policy.freeSpaceFloorBytes - other) {
            throw IllegalStateException("Committed bytes would violate the global storage budget")
        }
        committed = Math.addExact(committed, actualBytes)
        deleteReservationLocked(current)
        persistLedgerLocked()
    }

    fun release(reservation: StorageBudgetReservationV2): Boolean = withAuthority {
        val current = reservations[reservation.token] ?: return@withAuthority false
        if (current != reservation) throw IllegalStateException("Storage reservation token conflict")
        deleteReservationLocked(current)
        persistLedgerLocked()
        true
    }

    fun reclaimVerified(bytes: Long) = withAuthority {
        require(bytes in 1..committed)
        committed -= bytes
        persistLedgerLocked()
    }

    fun reservation(token: String): StorageBudgetReservationV2? = withAuthority { reservations[token] }
    fun committedBytes(): Long = withAuthority { committed }
    fun reservedBytes(): Long = withAuthority { reservedBytesLocked() }
    fun physicallyAllocatedBytes(token: String): Long = withAuthority {
        reservations[token]?.let { files.length(allocationFile(token)) } ?: 0L
    }

    private fun <T> withAuthority(block: () -> T): T = synchronized(lockFor(requireNotNull(ledger.parentFile))) {
        recoverLocked()
        block()
    }

    /** Reloaded before every mutation and query so independent instances never use stale state. */
    private fun recoverLocked() {
        committed = if (files.isFile(ledger)) files.readBytes(ledger).toString(Charsets.UTF_8).trim().toLongOrNull()
            ?: error("Corrupt storage budget ledger") else 0L
        require(committed >= 0) { "Corrupt storage budget ledger" }
        reservations.clear()
        val metadata = files.list(reservationsDirectory).filter { it.name.matches(Regex("[0-9a-f]{64}\\.reservation")) }
        metadata.sortedBy(File::getName).forEach { file ->
            val lines = files.readLines(file)
            require(lines.size == 2 && lines[0].matches(OWNER)) { "Corrupt storage reservation" }
            val bytes = lines[1].toLongOrNull() ?: error("Corrupt storage reservation bytes")
            val token = file.name.removeSuffix(".reservation")
            require(bytes > 0 && files.isFile(allocationFile(token)) && files.length(allocationFile(token)) == bytes) {
                "Reservation is not physically backed"
            }
            reservations[token] = StorageBudgetReservationV2(token, lines[0], bytes)
        }
        // An allocation without published metadata is not an authority and is reclaimed.
        val live = reservations.keys
        files.list(reservationsDirectory).filter { it.name.matches(Regex("[0-9a-f]{64}\\.allocation")) }
            .filter { it.name.removeSuffix(".allocation") !in live }
            .forEach { files.delete(it, DurableStoreFaultPointV2.DELETE_RECLAIM) }
        if (committed > policy.quotaBytes || policy.freeSpaceFloorBytes > freeBytes()) {
            throw IllegalStateException("Storage budget cannot open within its physical quota/floor")
        }
    }

    private fun canCharge(bytes: Long): Boolean {
        val reserved = reservedBytesLocked()
        if (committed > Long.MAX_VALUE - reserved || committed + reserved > Long.MAX_VALUE - bytes) return false
        val charged = committed + reserved + bytes
        return charged <= policy.quotaBytes && charged <= freeBytes() - policy.freeSpaceFloorBytes
    }
    private fun reservedBytesLocked() = reservations.values.fold(0L) { total, value -> Math.addExact(total, value.bytes) }
    private fun requireReservationLocked(value: StorageBudgetReservationV2) =
        reservations[value.token]?.also { check(it == value) { "Storage reservation token conflict" } }
            ?: error("Storage reservation is stale or already closed")
    private fun deleteReservationLocked(value: StorageBudgetReservationV2) {
        files.delete(metadataFile(value.token), DurableStoreFaultPointV2.DELETE_RECLAIM)
        files.delete(allocationFile(value.token), DurableStoreFaultPointV2.DELETE_RECLAIM)
        reservations.remove(value.token)
    }
    private fun nextTokenLocked() = "${reservations.size}:${committed}:${System.nanoTime()}"
    private fun persistLedgerLocked() = files.atomicReplace(ledger, "$committed\n".toByteArray(), DurableStoreFaultPointV2.POINTER_SLOT_REPLACE)
    private fun metadataFile(token: String) = files.child("reservations-v2", "$token.reservation")
    private fun allocationFile(token: String) = files.child("reservations-v2", "$token.allocation")
    private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
    private fun ByteArray.hex() = joinToString("") { "%02x".format(it) }

    companion object {
        private val OWNER = Regex("[A-Za-z0-9._:-]{1,160}")
        private val locks = mutableMapOf<String, Any>()
        private fun lockFor(directory: File): Any = synchronized(locks) {
            locks.getOrPut(directory.absoluteFile.toPath().normalize().toString()) { Any() }
        }
    }
}
