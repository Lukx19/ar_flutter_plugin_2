package com.uhg0.ar_flutter_plugin_2.capture

import java.io.File
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/**
 * Phone-wide, physically-backed V2 byte authority.  It deliberately does not
 * reuse the M0 reference ledger: reservations survive a new store instance and
 * are charged until a commit or verified reclamation releases them.
 */
data class StorageBudgetPolicyV2(val quotaBytes: Long, val freeSpaceFloorBytes: Long) {
    init { require(quotaBytes >= 0 && freeSpaceFloorBytes >= 0) }
}

data class StorageBudgetReservationV2(
    val token: String,
    val owner: String,
    val bytes: Long,
)

class StorageBudgetCoordinatorV2(
    private val directory: File,
    private val policy: StorageBudgetPolicyV2,
    private val freeBytes: () -> Long = { directory.usableSpace },
) {
    private val ledger = File(directory, "ledger-v2")
    private val reservationsDirectory = File(directory, "reservations-v2")
    private var committed = 0L
    private val reservations = linkedMapOf<String, StorageBudgetReservationV2>()

    init {
        require(directory.exists() || directory.mkdirs()) { "Cannot create storage budget directory" }
        require(directory.isDirectory)
        require(reservationsDirectory.exists() || reservationsDirectory.mkdirs())
        synchronized(lockFor(directory)) { recoverLocked() }
    }

    @Synchronized fun reserve(owner: String, bytes: Long): StorageBudgetReservationV2? = synchronized(lockFor(directory)) {
        require(owner.matches(OWNER)) { "Storage reservation owner is non-canonical" }
        if (bytes <= 0 || !canCharge(bytes)) return@synchronized null
        val token = sha256("$owner:$bytes:${nextTokenLocked()}".toByteArray()).hex()
        val reservation = StorageBudgetReservationV2(token, owner, bytes)
        writeAtomic(File(reservationsDirectory, "$token.reservation"), "$owner\n$bytes\n".toByteArray())
        reservations[token] = reservation
        persistLedgerLocked()
        reservation
    }

    /** Converts a still-live reservation into confirmed physical bytes. */
    @Synchronized fun commit(reservation: StorageBudgetReservationV2, actualBytes: Long) = synchronized(lockFor(directory)) {
        val current = requireReservationLocked(reservation)
        require(actualBytes in 0..current.bytes)
        val other = reservedBytesLocked() - current.bytes
        if (actualBytes > policy.quotaBytes - committed ||
            actualBytes > freeBytes() - policy.freeSpaceFloorBytes - other) {
            throw IllegalStateException("Committed bytes would violate the global storage budget")
        }
        committed = Math.addExact(committed, actualBytes)
        deleteDurably(File(reservationsDirectory, "${current.token}.reservation"))
        reservations.remove(current.token)
        persistLedgerLocked()
    }

    @Synchronized fun release(reservation: StorageBudgetReservationV2): Boolean = synchronized(lockFor(directory)) {
        val current = reservations[reservation.token] ?: return@synchronized false
        if (current != reservation) throw IllegalStateException("Storage reservation token conflict")
        deleteDurably(File(reservationsDirectory, "${current.token}.reservation"))
        reservations.remove(current.token)
        persistLedgerLocked()
        true
    }

    /** Only a caller that has already unlinked and synced physical bytes may reclaim them. */
    @Synchronized fun reclaimVerified(bytes: Long) = synchronized(lockFor(directory)) {
        require(bytes in 1..committed)
        committed -= bytes
        persistLedgerLocked()
    }

    @Synchronized fun reservation(token: String): StorageBudgetReservationV2? = synchronized(lockFor(directory)) {
        reservations[token]
    }
    @Synchronized fun committedBytes(): Long = synchronized(lockFor(directory)) { committed }
    @Synchronized fun reservedBytes(): Long = synchronized(lockFor(directory)) { reservedBytesLocked() }

    private fun recoverLocked() {
        committed = ledger.takeIf(File::isFile)?.readText()?.trim()?.toLongOrNull()
            ?: 0L
        require(committed >= 0) { "Corrupt storage budget ledger" }
        reservations.clear()
        reservationsDirectory.listFiles()?.sortedBy { it.name }?.forEach { file ->
            if (!file.name.matches(Regex("[0-9a-f]{64}\\.reservation"))) return@forEach
            val lines = file.readLines()
            require(lines.size == 2 && lines[0].matches(OWNER)) { "Corrupt storage reservation" }
            val bytes = lines[1].toLongOrNull() ?: error("Corrupt storage reservation bytes")
            require(bytes > 0)
            reservations[file.name.removeSuffix(".reservation")] =
                StorageBudgetReservationV2(file.name.removeSuffix(".reservation"), lines[0], bytes)
        }
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
    private fun nextTokenLocked() = "${reservations.size}:${committed}:${System.nanoTime()}"
    private fun persistLedgerLocked() = writeAtomic(ledger, "$committed\n".toByteArray())

    private fun writeAtomic(target: File, bytes: ByteArray) {
        val temporary = File(target.parentFile, ".${target.name}.tmp")
        FileOutputStream(temporary).use { output -> output.write(bytes); output.fd.sync() }
        try { Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING) }
        catch (_: AtomicMoveNotSupportedException) { throw IllegalStateException("Atomic replace unavailable") }
        syncDirectory(target.parentFile)
    }
    private fun deleteDurably(file: File) { if (file.exists() && !file.delete()) error("Cannot delete ${file.name}"); syncDirectory(file.parentFile) }
    private fun syncDirectory(value: File) { FileOutputStream(File(value, ".sync")).use { it.fd.sync() }; File(value, ".sync").delete() }
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
