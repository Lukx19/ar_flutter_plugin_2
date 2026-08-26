package com.uhg0.ar_flutter_plugin_2.capture

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.DigestInputStream
import java.security.MessageDigest
import java.util.Properties

/** A component input owned by native code; it is consumed once and never buffered by the store. */
data class CaptureComponentStreamV2(val kind: CaptureComponentKind, val input: InputStream)
class DurableStoreConflictV2(message: String) : IllegalStateException(message)

/**
 * Schema-5 capture-store implementation for the frozen #99 port.  V1 files
 * are not inspected or modified.  Every durable record is immutable and the
 * one mutable authority is an alternating, directory-synced selected pointer.
 */
class DurableSessionStoreV2(
    private val root: File,
    private val budget: StorageBudgetCoordinatorV2,
    private val faults: DurableStoreFaultInjectorV2 = DurableStoreFaultInjectorV2 { },
) : CaptureCommitPort {
    private val sessionRoot = File(root, "sessions")
    private val mutex = Any()
    private val files = SafeFilesystemV2(root, faults)

    init { require(root.exists() || root.mkdirs()) { "Cannot create schema-5 store" }; require(sessionRoot.exists() || sessionRoot.mkdirs()) }

    override fun acceptBeforeExposure(attempt: CaptureAcceptedAttempt): CaptureReceipt = synchronized(mutex) {
        val location = location(attempt.identity)
        tombstone(location)?.let { throw DurableStoreConflictV2("Session is tombstoned") }
        receipt(location, attempt.identity)?.let { prior ->
            if (prior.requestHash == acceptedHash(attempt)) return@synchronized prior
            throw DurableStoreConflictV2("Changed replay conflicts with durable identity")
        }
        val accepted = acceptedFile(location)
        if (accepted.isFile) {
            val existing = readProperties(accepted)
            if (existing.getProperty("acceptedHash") == acceptedHash(attempt)) return@synchronized acceptedReceipt(attempt)
            throw DurableStoreConflictV2("Changed accepted attempt conflicts")
        }
        val reservation = budget.reserve(reservationOwner(attempt.identity), attempt.reservation.totalStoreLiability)
            ?: throw IllegalStateException("Storage reservation refused before exposure")
        try {
            val data = Properties().apply {
                setProperty("acceptedHash", acceptedHash(attempt)); setProperty("reservationToken", reservation.token)
                setProperty("reservationBytes", reservation.bytes.toString()); setProperty("attemptId", attempt.identity.attemptId)
                setProperty("commitId", attempt.identity.commitId); setProperty("session", attempt.identity.lifecycleCut.sessionId)
            }
            faults.at(DurableStoreFaultPointV2.ACCEPTED_RECORD)
            writePropertiesExclusive(accepted, data)
            acceptedReceipt(attempt)
        } catch (error: Throwable) { budget.release(reservation); throw error }
    }

    /** Streams, hashes and commits the exact descriptor set.  The passed streams close here. */
    fun commitStreamed(request: CaptureCommitRequest, streams: List<CaptureComponentStreamV2>): CaptureReceipt = synchronized(mutex) {
        val location = location(request.accepted.identity)
        tombstone(location)?.let { throw DurableStoreConflictV2("Session is tombstoned") }
        val requestHash = requestHash(request)
        receipt(location, request.accepted.identity)?.let { prior ->
            if (prior.requestHash == requestHash) return@synchronized prior
            throw DurableStoreConflictV2("Changed replay conflicts with committed durable identity")
        }
        val accepted = readProperties(acceptedFile(location))
        check(accepted.getProperty("acceptedHash") == acceptedHash(request.accepted)) { "Attempt was not durably accepted" }
        check(request.hasCompleteComponentSet) { "Component descriptors are incomplete" }
        check(streams.map { it.kind }.toSet() == request.accepted.profile.requiredComponents && streams.size == streams.map { it.kind }.toSet().size) {
            "Stream component set is incomplete or duplicated"
        }
        val reservation = budget.reservation(accepted.getProperty("reservationToken"))
            ?: throw IllegalStateException("Accepted attempt has no live reservation")
        val staging = File(location, "staging/${safe(request.accepted.identity.commitId)}")
        require(staging.mkdirs() || staging.isDirectory)
        var actualBytes = 0L
        try {
            val descriptors = request.components.associateBy { it.kind }
            val staged = streams.sortedBy { it.kind.ordinal }.map { stream ->
                val descriptor = descriptors.getValue(stream.kind)
                val file = File(staging, "${stream.kind.name.lowercase()}.part")
                faults.at(DurableStoreFaultPointV2.COMPONENT_OPEN)
                val measured = copyAndDigest(stream.input, file)
                faults.at(DurableStoreFaultPointV2.LENGTH_CHECK)
                check(measured.first == descriptor.byteLength && measured.second.hex() == descriptor.sha256.hex()) { "Component length/hash mismatch" }
                actualBytes = Math.addExact(actualBytes, measured.first)
                Staged(stream.kind, file, descriptor)
            }
            check(actualBytes <= reservation.bytes && actualBytes <= request.accepted.profile.maximumComponentBytes) { "Component bytes exceed accepted reservation" }
            val captureId = safe(request.accepted.identity.attemptId)
            val assets = File(location, "assets/$captureId").also { require(it.mkdirs() || it.isDirectory) }
            staged.forEach { moveImmutable(it.file, File(assets, "${it.kind.name.lowercase()}.${it.descriptor.sha256.hex()}.blob"), it.descriptor) }
            val prior = selectedRoot(location)
            val revision = (prior?.revision ?: 0L) + 1L
            val rootBytes = rootRecord(request, requestHash, revision, staged, prior)
            val rootHash = sha256(rootBytes).hex()
            faults.at(DurableStoreFaultPointV2.ROOT_WRITE)
            writeImmutable(File(location, "objects/$rootHash.root"), rootBytes)
            faults.at(DurableStoreFaultPointV2.ROOT_FILE_SYNC)
            val terminal = CaptureTerminal(CaptureTerminalKind.COMMITTED_PICTURE, request.accepted.identity, requestHash, "committed", captureId, revision, rootHash)
            val committed = CaptureReceipt(request.accepted.identity, CaptureAttemptPhase.COMMITTED_PICTURE, requestHash, receiptHash(requestHash, rootHash), true, terminal)
            // Receipt is durable before, and independently validates, the pointer switch.
            faults.at(DurableStoreFaultPointV2.RECEIPT_WRITE)
            writePropertiesExclusive(receiptFile(location), receiptProperties(committed, rootHash))
            faults.at(DurableStoreFaultPointV2.RECEIPT_FILE_SYNC)
            publishPointer(location, RootPointer(revision, rootHash, request.accepted.identity.commitId))
            budget.commit(reservation, actualBytes)
            deleteTree(staging)
            committed
        } catch (error: Throwable) {
            deleteTree(staging)
            throw error
        }
    }

    override fun prepareCommit(request: CaptureCommitRequest): CaptureReceipt =
        throw UnsupportedOperationException("DurableSessionStoreV2 requires native component streams")

    override fun queryReceipt(identity: CaptureAttemptIdentity): CaptureReceipt? = synchronized(mutex) { receipt(location(identity), identity) }

    override fun abandon(terminal: CaptureTerminal): CaptureReceipt = synchronized(mutex) {
        require(terminal.kind == CaptureTerminalKind.ABANDONED_ATTEMPT)
        val location = location(terminal.identity)
        receipt(location, terminal.identity)?.let { return@synchronized it }
        val accepted = acceptedFile(location).takeIf(File::isFile)?.let(::readProperties)
        faults.at(DurableStoreFaultPointV2.ABANDONMENT_CLEANUP)
        deleteTree(File(location, "staging/${safe(terminal.identity.commitId)}"))
        val value = CaptureReceipt(terminal.identity, CaptureAttemptPhase.ABANDONED_ATTEMPT, terminal.canonicalTerminalHash,
            receiptHash(terminal.canonicalTerminalHash, "abandoned"), true, terminal)
        faults.at(DurableStoreFaultPointV2.ABANDONMENT_RECORD)
        writePropertiesExclusive(receiptFile(location), receiptProperties(value, "abandoned"))
        accepted?.getProperty("reservationToken")?.let { budget.reservation(it)?.let(budget::release) }
        value
    }

    /** Tombstone-first whole-session deletion fence.  It wins over later callbacks and roots. */
    fun tombstoneSession(sessionId: String) = synchronized(mutex) {
        val directory = File(sessionRoot, safe(sessionId))
        require(directory.exists() || directory.mkdirs())
        faults.at(DurableStoreFaultPointV2.TOMBSTONE_RECORD)
        writeExclusive(File(directory, "tombstone"), "deleted\n".toByteArray())
        faults.at(DurableStoreFaultPointV2.TOMBSTONE_DIRECTORY_SYNC)
        directory.walkTopDown().filter { it.name == "accepted.properties" }.forEach { file ->
            readProperties(file).getProperty("reservationToken")?.let { budget.reservation(it)?.let(budget::release) }
        }
    }

    /** Reconciles prepared staging and ensures only receipt/pointer backed results are visible. */
    fun recover() = synchronized(mutex) {
        faults.at(DurableStoreFaultPointV2.RECOVERY)
        sessionRoot.listFiles()?.filter(File::isDirectory)?.forEach { session ->
            session.walkTopDown().filter { it.name == "staging" }.forEach(::deleteTree)
            session.walkTopDown().filter { it.name == "accepted.properties" }.forEach { accepted ->
                val attempt = accepted.parentFile
                if (!receiptFile(attempt).isFile) readProperties(accepted).getProperty("reservationToken")?.let { budget.reservation(it)?.let(budget::release) }
            }
        }
    }

    private fun location(identity: CaptureAttemptIdentity): File = File(File(File(sessionRoot, safe(identity.lifecycleCut.sessionId)), "attempts"), safe(identity.commitId)).also { it.mkdirs() }
    private fun tombstone(location: File): File? = generateSequence(location) { it.parentFile }.firstOrNull { File(it, "tombstone").isFile }
    private fun acceptedFile(location: File) = File(location, "accepted.properties")
    private fun receiptFile(location: File) = File(location, "receipt.properties")
    private fun receipt(location: File, fallbackIdentity: CaptureAttemptIdentity? = null): CaptureReceipt? {
        val file = receiptFile(location); if (!file.isFile) return null
        val value = readProperties(file); val identity = fallbackIdentity ?: return null
        val kind = value.getProperty("kind") ?: return null
        val terminal = if (kind == CaptureTerminalKind.COMMITTED_PICTURE.name) CaptureTerminal(CaptureTerminalKind.COMMITTED_PICTURE, identity, value.getProperty("requestHash"), "committed", value.getProperty("captureId"), value.getProperty("revision").toLong(), value.getProperty("rootHash"))
        else CaptureTerminal(CaptureTerminalKind.ABANDONED_ATTEMPT, identity, value.getProperty("requestHash"), value.getProperty("reason", "abandoned"))
        return CaptureReceipt(identity, if (terminal.kind == CaptureTerminalKind.COMMITTED_PICTURE) CaptureAttemptPhase.COMMITTED_PICTURE else CaptureAttemptPhase.ABANDONED_ATTEMPT, value.getProperty("requestHash"), value.getProperty("receiptHash"), true, terminal)
    }
    private fun acceptedReceipt(attempt: CaptureAcceptedAttempt) = CaptureReceipt(attempt.identity, CaptureAttemptPhase.RESERVED_ACCEPTED, acceptedHash(attempt), attempt.acceptedReceiptHash.hex(), true)
    private fun acceptedHash(attempt: CaptureAcceptedAttempt) = sha256("${attempt.identity.commitId}|${attempt.identity.attemptId}|${attempt.reservation.totalStoreLiability}|${attempt.canonicalIntentHash.hex()}".toByteArray()).hex()
    private fun requestHash(request: CaptureCommitRequest) = sha256(buildString { append(acceptedHash(request.accepted)); append('|').append(request.exposureTimestampNanoseconds); request.components.sortedBy { it.kind.ordinal }.forEach { append('|').append(it.kind).append(':').append(it.byteLength).append(':').append(it.sha256.hex()) } }.toByteArray()).hex()
    private fun reservationOwner(identity: CaptureAttemptIdentity) = "capture:${safe(identity.lifecycleCut.sessionId)}:${safe(identity.commitId)}"
    private fun receiptHash(requestHash: String, rootHash: String) = sha256("$requestHash|$rootHash".toByteArray()).hex()
    private fun rootRecord(request: CaptureCommitRequest, requestHash: String, revision: Long, staged: List<Staged>, prior: RootPointer?) = buildString { append("schema=5\nrevision=$revision\nrequest=$requestHash\nprevious=${prior?.rootHash ?: "-"}\nsecondPrevious=${prior?.previousHash ?: "-"}\n"); staged.sortedBy { it.kind.ordinal }.forEach { append("component=${it.kind}:${it.descriptor.byteLength}:${it.descriptor.sha256.hex()}\n") } }.toByteArray(StandardCharsets.UTF_8)
    private fun receiptProperties(receipt: CaptureReceipt, rootHash: String) = Properties().apply { setProperty("kind", receipt.terminal!!.kind.name); setProperty("requestHash", receipt.requestHash); setProperty("receiptHash", receipt.receiptHash); setProperty("rootHash", rootHash); setProperty("reason", receipt.terminal.reason); receipt.terminal.captureId?.let { setProperty("captureId", it) }; receipt.terminal.captureRevision?.let { setProperty("revision", it.toString()) } }
    private fun selectedRoot(location: File): RootPointer? = listOf("root-A.ptr", "root-B.ptr").mapNotNull { file ->
        File(location, file).takeIf(File::isFile)?.readLines()?.takeIf { it.size == 3 }?.let {
            RootPointer(it[0].toLongOrNull() ?: return@let null, it[1], it[2], if (file == "root-A.ptr") "A" else "B", rootPrevious(location, it[1]))
        }
    }.filter { validRoot(location, it) }.let { candidates ->
        candidates.groupBy { it.revision }.values.forEach { same -> if (same.map { it.rootHash }.toSet().size > 1) throw DurableStoreConflictV2("Schema-5 root fork") }
        candidates.maxByOrNull { it.revision }
    }
    private fun rootPrevious(location: File, hash: String): String? = File(location, "objects/$hash.root").takeIf(File::isFile)?.readLines()?.firstOrNull { it.startsWith("previous=") }?.removePrefix("previous=")?.takeUnless { it == "-" }
    private fun validRoot(location: File, pointer: RootPointer): Boolean {
        val file = File(location, "objects/${pointer.rootHash}.root")
        if (!file.isFile || sha256(file.readBytes()).hex() != pointer.rootHash) return false
        val values = file.readLines().associate { it.substringBefore('=') to it.substringAfter('=', "") }
        return values["schema"] == "5" && values["revision"]?.toLongOrNull() == pointer.revision && values["request"]?.matches(Regex("[0-9a-f]{64}")) == true
    }
    private fun publishPointer(location: File, pointer: RootPointer) { val old = selectedRoot(location); val target = File(location, if (old?.slot == "A") "root-B.ptr" else "root-A.ptr"); faults.at(DurableStoreFaultPointV2.POINTER_SLOT_REPLACE); writeAtomic(target, "${pointer.revision}\n${pointer.rootHash}\n${pointer.commitId}\n".toByteArray()) }
    private fun moveImmutable(from: File, target: File, descriptor: CaptureComponentDescriptor) { if (target.exists()) { check(target.length() == descriptor.byteLength && sha256(target.readBytes()).hex() == descriptor.sha256.hex()) { "Immutable asset conflict" }; from.delete(); return }; files.moveSameDirectory(from, target) }
    private fun copyAndDigest(input: InputStream, output: File): Pair<Long, ByteArray> { val digest = MessageDigest.getInstance("SHA-256"); var length = 0L; faults.at(DurableStoreFaultPointV2.PART_CREATE); DigestInputStream(input, digest).use { source -> FileOutputStream(output).use { destination -> val buffer = ByteArray(64 * 1024); while (true) { val read = source.read(buffer); if (read < 0) break; faults.at(DurableStoreFaultPointV2.PART_WRITE); destination.write(buffer, 0, read); length = Math.addExact(length, read.toLong()) }; faults.at(DurableStoreFaultPointV2.PART_FILE_SYNC); destination.fd.sync() } }; faults.at(DurableStoreFaultPointV2.PART_HASH); syncDirectory(output.parentFile); return length to digest.digest() }
    private fun writePropertiesExclusive(file: File, properties: Properties) = writeExclusive(file, buildString { properties.stringPropertyNames().sorted().forEach { append(it).append('=').append(properties.getProperty(it)).append('\n') } }.toByteArray())
    private fun readProperties(file: File) = Properties().apply { FileInputStream(file).use(::load) }
    private fun writeExclusive(file: File, bytes: ByteArray) {
        file.parentFile?.mkdirs()
        check(!file.exists()) { "Immutable durable record already exists" }
        FileOutputStream(file, false).use { it.write(bytes); it.fd.sync() }
        syncDirectory(file.parentFile)
    }
    private fun writeImmutable(file: File, bytes: ByteArray) {
        if (file.isFile) {
            check(file.length() == bytes.size.toLong() && sha256(file.readBytes()).contentEquals(sha256(bytes))) { "Immutable durable object conflict" }
            return
        }
        writeExclusive(file, bytes)
    }
    private fun writeAtomic(file: File, bytes: ByteArray) { files.atomicReplace(file, bytes, DurableStoreFaultPointV2.POINTER_SLOT_REPLACE) }
    private fun deleteTree(file: File) { if (!file.exists()) return; file.walkBottomUp().forEach { if (!it.delete()) error("Cannot delete staging") }; syncDirectory(file.parentFile ?: root) }
    private fun syncDirectory(directory: File) { FileOutputStream(File(directory, ".sync")).use { it.fd.sync() }; File(directory, ".sync").delete() }
    private fun safe(value: String) = sha256(value.toByteArray()).hex()
    private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
    private fun ByteArray.hex() = joinToString("") { "%02x".format(it) }
    private fun List<Int>.hex() = joinToString("") { "%02x".format(it) }
    private data class Staged(val kind: CaptureComponentKind, val file: File, val descriptor: CaptureComponentDescriptor)
    private data class RootPointer(val revision: Long, val rootHash: String, val commitId: String, val slot: String = "", val previousHash: String? = null)
}
