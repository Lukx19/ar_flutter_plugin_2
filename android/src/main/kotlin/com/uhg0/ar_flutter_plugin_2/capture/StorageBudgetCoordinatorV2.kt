package com.uhg0.ar_flutter_plugin_2.capture

import java.io.File
import java.security.MessageDigest

data class StorageBudgetPolicyV2(val quotaBytes: Long, val freeSpaceFloorBytes: Long) {
    init { require(quotaBytes >= 0 && freeSpaceFloorBytes >= 0) }
}

data class StorageBudgetReservationV2(
    val token: String,
    val owner: String,
    val bytes: Long,
    val stagingName: String? = null,
    val targetName: String? = null,
)

/** Process-global, refreshed, physically-backed storage authority. */
class StorageBudgetCoordinatorV2(
    directory: File,
    private val policy: StorageBudgetPolicyV2,
    filesystemBackend: DescriptorFilesystemV2 = AndroidDescriptorFilesystemV2(),
    private val freeBytes: () -> Long = { directory.usableSpace },
) : AutoCloseable {
    private val files = SafeFilesystemV2(directory, DurableStoreFaultInjectorV2 { }, filesystemBackend)
    private val ledger = files.child("ledger-v2")
    private val reservationsDirectory = files.child("reservations-v2")
    private var committed = 0L
    private val reservations = linkedMapOf<String, StorageBudgetReservationV2>()

    init {
        try {
            files.ensureDirectory(reservationsDirectory)
        } catch (error: Throwable) { files.close(); throw error }
    }

    /** Closes only the bound filesystem created from the borrowed backend factory. */
    override fun close() = synchronized(lockFor(requireNotNull(ledger.parentFile))) { files.close() }

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

    /**
     * Reserves storage by allocating the candidate files themselves. The staging tree remains the
     * durable physical backing before and after publication; no second reservation-sized file exists.
     */
    fun reserveCandidate(
        owner: String,
        staging: File,
        target: File,
        fileBytes: Map<String, Long>,
        maximumPhysicalBytes: Long,
    ): StorageBudgetReservationV2? = withAuthority {
        require(owner.matches(OWNER)) { "Storage reservation owner is non-canonical" }
        require(staging.parentFile == ledger.parentFile && target.parentFile == ledger.parentFile)
        require(staging.name.matches(CANDIDATE) && target.name.matches(CANDIDATE))
        require(fileBytes.isNotEmpty() && fileBytes.keys.all { it.matches(CANDIDATE) })
        require(fileBytes.values.all { it >= 0L } && maximumPhysicalBytes > 0L)
        if (!canCharge(maximumPhysicalBytes)) return@withAuthority null
        val token = sha256("$owner:$maximumPhysicalBytes:${nextTokenLocked()}".toByteArray()).hex()
        val reservation = StorageBudgetReservationV2(
            token, owner, maximumPhysicalBytes, staging.name, target.name,
        )
        val metadata = metadataFile(token)
        try {
            check(!files.isDirectory(staging) && !files.isDirectory(target))
            files.ensureDirectory(staging)
            fileBytes.toSortedMap().forEach { (name, bytes) ->
                if (bytes == 0L) files.writeExclusive(
                    File(staging, name), ByteArray(0), DurableStoreFaultPointV2.ACCEPTED_RECORD,
                ) else files.allocateExclusive(File(staging, name), bytes)
                require(files.allocatedTreeBytes(staging) <= maximumPhysicalBytes)
                require(freeBytes() >= policy.freeSpaceFloorBytes) {
                    "Candidate allocation violates free-space floor"
                }
            }
            val actual = files.allocatedTreeBytes(staging)
            require(actual in 1..maximumPhysicalBytes)
            require(freeBytes() >= policy.freeSpaceFloorBytes) { "Candidate violates free-space floor" }
            files.writeExclusive(
                metadata,
                "$owner\n$maximumPhysicalBytes\n${staging.name}\n${target.name}\n".toByteArray(),
                DurableStoreFaultPointV2.ACCEPTED_RECORD,
            )
            require(freeBytes() >= policy.freeSpaceFloorBytes)
            reservations[token] = reservation
            persistLedgerLocked()
            require(freeBytes() >= policy.freeSpaceFloorBytes)
            reservation
        } catch (error: Throwable) {
            reservations.remove(token)
            if (files.isFile(metadata)) files.delete(metadata, DurableStoreFaultPointV2.DELETE_RECLAIM)
            if (files.isDirectory(staging)) files.deleteTree(staging, DurableStoreFaultPointV2.DELETE_RECLAIM)
            throw error
        }
    }

    fun verifyCandidate(reservation: StorageBudgetReservationV2, candidate: File): Long = withAuthority {
        val current = requireReservationLocked(reservation)
        require(current.stagingName != null && candidate.name in setOf(current.stagingName, current.targetName))
        val actual = files.allocatedTreeBytes(candidate)
        require(actual in 1..current.bytes)
        require(freeBytes() >= policy.freeSpaceFloorBytes) { "Candidate violates free-space floor" }
        actual
    }

    fun publishCandidate(reservation: StorageBudgetReservationV2, staging: File, target: File) = withAuthority {
        val current = requireReservationLocked(reservation)
        require(current.stagingName == staging.name && current.targetName == target.name)
        val before = files.allocatedTreeBytes(staging)
        require(before in 1..current.bytes && freeBytes() >= policy.freeSpaceFloorBytes)
        files.moveAtomic(staging, target)
        val after = files.allocatedTreeBytes(target)
        require(after == before && freeBytes() >= policy.freeSpaceFloorBytes)
    }

    /** Completes a publication cut left after the durable rename but before the explicit commit. */
    fun reconcilePublishedCandidate(target: File) = withAuthority {
        val current = reservations.values.singleOrNull { it.targetName == target.name } ?: return@withAuthority
        require(files.isDirectory(target) && current.stagingName != null)
        val actual = files.allocatedTreeBytes(target)
        commitCandidateLocked(current, actual)
    }

    /** Reopen-only resolution for one known target: commit a rename or reclaim its private staging. */
    fun reconcileCandidate(target: File) = withAuthority {
        val current = reservations.values.singleOrNull { it.targetName == target.name } ?: return@withAuthority
        val staging = files.child(requireNotNull(current.stagingName))
        when {
            files.isDirectory(target) -> commitCandidateLocked(current, files.allocatedTreeBytes(target))
            files.isDirectory(staging) -> {
                files.delete(metadataFile(current.token), DurableStoreFaultPointV2.DELETE_RECLAIM)
                reservations.remove(current.token)
                persistLedgerLocked()
                files.deleteTree(staging, DurableStoreFaultPointV2.DELETE_RECLAIM)
            }
            else -> error("Candidate reservation has no physical backing")
        }
    }

    fun releaseCandidate(reservation: StorageBudgetReservationV2, staging: File): Boolean = withAuthority {
        val current = reservations[reservation.token] ?: return@withAuthority false
        require(current == reservation && current.stagingName == staging.name)
        // Remove authority first. A crash can then leave only an uncharged staging orphan, which
        // recovery reclaims under this same global lock before another reservation is admitted.
        files.delete(metadataFile(current.token), DurableStoreFaultPointV2.DELETE_RECLAIM)
        reservations.remove(current.token)
        persistLedgerLocked()
        if (files.isDirectory(staging)) files.deleteTree(staging, DurableStoreFaultPointV2.DELETE_RECLAIM)
        true
    }

    fun commit(reservation: StorageBudgetReservationV2, actualBytes: Long) = withAuthority {
        val current = requireReservationLocked(reservation)
        require(actualBytes in 0..current.bytes)
        if (current.stagingName != null) {
            commitCandidateLocked(current, actualBytes)
            return@withAuthority
        }
        val other = reservedBytesLocked() - current.bytes
        val violatesFreeFloor = actualBytes > freeBytes() - policy.freeSpaceFloorBytes - other
        if (actualBytes > policy.quotaBytes - committed || violatesFreeFloor) {
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
        reservations[token]?.let { files.allocatedLength(allocationFile(token)) } ?: 0L
    }
    fun physicallyAllocatedTreeBytes(directory: File): Long = withAuthority {
        files.allocatedTreeBytes(directory)
    }
    fun allocationUnitBytes(path: File): Long = withAuthority {
        files.allocationUnit(path)
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
            require(lines.size in setOf(2, 4, 5) && lines[0].matches(OWNER)) { "Corrupt storage reservation" }
            val bytes = lines[1].toLongOrNull() ?: error("Corrupt storage reservation bytes")
            val token = file.name.removeSuffix(".reservation")
            if (lines.size == 2) {
                require(bytes > 0 && files.isFile(allocationFile(token)) && files.length(allocationFile(token)) == bytes) {
                    "Reservation is not physically backed"
                }
                reservations[token] = StorageBudgetReservationV2(token, lines[0], bytes)
            } else {
                require(lines[2].matches(CANDIDATE) && lines[3].matches(CANDIDATE))
                val staging = files.child(lines[2]); val target = files.child(lines[3])
                val backing = when {
                    files.isDirectory(target) -> target
                    files.isDirectory(staging) -> staging
                    else -> error("Candidate reservation is not physically backed")
                }
                val actual = files.allocatedTreeBytes(backing)
                require(actual in 1..bytes) { "Candidate reservation exceeds liability" }
                val reservation = StorageBudgetReservationV2(token, lines[0], bytes, lines[2], lines[3])
                if (lines.size == 5) {
                    val state = lines[4].split(':')
                    require(state.size == 3 && state[0] == "COMMITTING" && backing == target)
                    val candidateBytes = state[1].toLongOrNull() ?: error("Corrupt candidate commit")
                    val previous = state[2].toLongOrNull() ?: error("Corrupt candidate commit")
                    require(candidateBytes == actual && committed in setOf(previous, Math.addExact(previous, actual)))
                    if (committed == previous) {
                        committed = Math.addExact(previous, actual)
                        persistLedgerLocked()
                    }
                    files.delete(file, DurableStoreFaultPointV2.DELETE_RECLAIM)
                } else reservations[token] = reservation
            }
        }
        // An allocation without published metadata is not an authority and is reclaimed.
        val live = reservations.keys
        files.list(reservationsDirectory).filter { it.name.matches(Regex("[0-9a-f]{64}\\.allocation")) }
            .filter { it.name.removeSuffix(".allocation") !in live }
            .forEach { files.delete(it, DurableStoreFaultPointV2.DELETE_RECLAIM) }
        files.list(reservationsDirectory)
            .filter { it.name.matches(Regex("\\.[0-9a-f]{64}\\.reservation\\.part")) }
            .forEach { files.delete(it, DurableStoreFaultPointV2.DELETE_RECLAIM) }
        val liveStaging = reservations.values.mapNotNull { it.stagingName }.toSet()
        files.list(requireNotNull(ledger.parentFile))
            .filter { it.name.matches(STAGING) && it.name !in liveStaging && files.isDirectory(it) }
            .forEach { files.deleteTree(it, DurableStoreFaultPointV2.DELETE_RECLAIM) }
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
    private fun commitCandidateLocked(value: StorageBudgetReservationV2, actualBytes: Long) {
        require(value.stagingName != null && actualBytes in 1..value.bytes)
        require(actualBytes <= policy.quotaBytes - committed)
        require(freeBytes() >= policy.freeSpaceFloorBytes)
        files.atomicReplace(
            metadataFile(value.token),
            "${value.owner}\n${value.bytes}\n${value.stagingName}\n${value.targetName}\nCOMMITTING:$actualBytes:$committed\n".toByteArray(),
            DurableStoreFaultPointV2.POINTER_SLOT_REPLACE,
        )
        committed = Math.addExact(committed, actualBytes)
        persistLedgerLocked()
        files.delete(metadataFile(value.token), DurableStoreFaultPointV2.DELETE_RECLAIM)
        reservations.remove(value.token)
    }
    private fun deleteReservationLocked(value: StorageBudgetReservationV2) {
        files.delete(metadataFile(value.token), DurableStoreFaultPointV2.DELETE_RECLAIM)
        if (value.stagingName == null) {
            files.delete(allocationFile(value.token), DurableStoreFaultPointV2.DELETE_RECLAIM)
        }
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
        private val CANDIDATE = Regex("[A-Za-z0-9._-]{1,160}")
        private val STAGING = Regex("m3-canonical-v6-[0-9a-f]{64}\\.staging-[A-Za-z0-9._-]{1,64}")
        private val locks = mutableMapOf<String, Any>()
        private fun lockFor(directory: File): Any = synchronized(locks) {
            locks.getOrPut(directory.absoluteFile.toPath().normalize().toString()) { Any() }
        }
    }
}
