package com.uhg0.ar_flutter_plugin_2.capture

import java.io.File
import java.io.InputStream
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Properties

/** A component input owned by native code; it is consumed once and never buffered by the store. */
data class CaptureComponentStreamV2(val kind: CaptureComponentKind, val input: InputStream)
class DurableStoreConflictV2(message: String) : IllegalStateException(message)
private const val ABANDONMENT_BINDING_FULL_REQUEST = "full-request-v1"
private const val ABANDONMENT_BINDING_METADATA_ONLY = "metadata-only-v1"
private const val ABANDONMENT_BINDING_RECOVERED = "recovered-proven-absent-v1"

/** Immutable non-image fields frozen at durable acceptance; descriptors arrive after shutter. */
internal data class CapturePostOutputTemplateV2(
    val accepted: CaptureAcceptedAttempt,
    val poseRecordHash: List<Int>,
    val cameraModelHash: List<Int>,
    val validationRecordHash: List<Int>,
    val ledgerRecordHash: List<Int>,
)

/**
 * Schema-5 capture-store implementation for the frozen #99 port.  V1 files
 * are not inspected or modified.  Every durable record is immutable and the
 * one mutable authority is an alternating, directory-synced selected pointer.
 */
class DurableSessionStoreV2(
    private val root: File,
    private val budget: StorageBudgetCoordinatorV2,
    private val faults: DurableStoreFaultInjectorV2 = DurableStoreFaultInjectorV2 { },
    filesystemBackend: DescriptorFilesystemV2 = AndroidDescriptorFilesystemV2(),
) : CaptureCommitPort, AutoCloseable {
    private val mutex = Any()
    private val files = SafeFilesystemV2(root, faults, filesystemBackend)
    private val sessionRoot = files.child("sessions")
    private val uncertainRootHashes = mutableSetOf<String>()

    init {
        try { files.ensureDirectory(sessionRoot) }
        catch (error: Throwable) { files.close(); throw error }
    }

    /** Closes only this store's owned bound filesystem; the injected budget is borrowed. */
    override fun close() = synchronized(mutex) { files.close() }

    override fun acceptBeforeExposure(attempt: CaptureAcceptedAttempt): CaptureReceipt = synchronized(mutex) {
        val location = location(attempt.identity)
        tombstone(location)?.let { throw DurableStoreConflictV2("Session is tombstoned") }
        receipt(location, attempt.identity)?.let { prior ->
            val accepted = acceptedFile(location, attempt.identity).takeIf(files::isFile)?.let(::readProperties)
            if (accepted?.getProperty("acceptedHash") == acceptedHash(attempt)) return@synchronized prior
            throw DurableStoreConflictV2("Changed replay conflicts with durable identity")
        }
        val accepted = acceptedFile(location, attempt.identity)
        if (files.isFile(accepted)) {
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

    /**
     * Native admission fence for #101. A terminal identity is checked against
     * the complete commit request before a scheduler owner or shutter can be
     * allocated. The frozen public port remains attempt-based for #99 callers.
     */
    internal fun acceptCaptureBeforeExposure(request: CaptureCommitRequest): CaptureReceipt = synchronized(mutex) {
        val location = location(request.accepted.identity)
        tombstone(location)?.let { throw DurableStoreConflictV2("Session is tombstoned") }
        replayReceiptBeforeExposure(location, request)?.let { return@synchronized it }
        acceptBeforeExposure(request.accepted)
    }

    internal fun replayFenceBeforeExposure(request: CaptureCommitRequest): CaptureReceipt? = synchronized(mutex) {
        val location = location(request.accepted.identity)
        tombstone(location)?.let { throw DurableStoreConflictV2("Session is tombstoned") }
        replayReceiptBeforeExposure(location, request)?.let { return@synchronized it }
        val accepted = acceptedFile(location, request.accepted.identity)
        if (files.isFile(accepted)) {
            val existing = readProperties(accepted)
            if (existing.getProperty("acceptedHash") == acceptedHash(request.accepted)) return@synchronized acceptedReceipt(request.accepted)
            throw DurableStoreConflictV2("Changed replay conflicts with durable accepted identity")
        }
        null
    }

    internal fun replayTemplateFenceBeforeExposure(template: CapturePostOutputTemplateV2): CaptureReceipt? = synchronized(mutex) {
        val location = location(template.accepted.identity)
        tombstone(location)?.let { throw DurableStoreConflictV2("Session is tombstoned") }
        val receiptFile = receiptFile(location, template.accepted.identity)
        if (files.isFile(receiptFile)) {
            val properties = readProperties(receiptFile)
            if (properties.getProperty("templateHash") != templateHash(template)) {
                throw DurableStoreConflictV2("Changed accepted template conflicts with durable identity")
            }
            return@synchronized receipt(location, template.accepted.identity)
                ?: throw DurableStoreConflictV2("Template receipt is not selected-root authoritative")
        }
        val accepted = acceptedFile(location, template.accepted.identity)
        if (files.isFile(accepted)) {
            val existing = readProperties(accepted)
            if (existing.getProperty("acceptedHash") == acceptedHash(template.accepted)) return@synchronized acceptedReceipt(template.accepted)
            throw DurableStoreConflictV2("Changed template conflicts with durable accepted identity")
        }
        null
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
        val accepted = readProperties(acceptedFile(location, request.accepted.identity))
        check(accepted.getProperty("acceptedHash") == acceptedHash(request.accepted)) { "Attempt was not durably accepted" }
        check(request.hasCompleteComponentSet) { "Component descriptors are incomplete" }
        check(streams.map { it.kind }.toSet() == request.accepted.profile.requiredComponents && streams.size == streams.map { it.kind }.toSet().size) {
            "Stream component set is incomplete or duplicated"
        }
        val reservation = budget.reservation(accepted.getProperty("reservationToken"))
            ?: throw IllegalStateException("Accepted attempt has no live reservation")
        val staging = path(attemptDirectory(location, request.accepted.identity), "staging")
        files.ensureDirectory(staging)
        var actualBytes = 0L
        try {
            val descriptors = request.components.associateBy { it.kind }
            val staged = streams.sortedBy { it.kind.ordinal }.map { stream ->
                val descriptor = descriptors.getValue(stream.kind)
                val file = path(staging, "${stream.kind.name.lowercase()}.part")
                faults.at(DurableStoreFaultPointV2.COMPONENT_OPEN)
                val measured = files.streamExclusive(file, stream.input)
                faults.at(DurableStoreFaultPointV2.LENGTH_CHECK)
                check(measured.first == descriptor.byteLength && measured.second.hex() == descriptor.sha256.hex()) { "Component length/hash mismatch" }
                actualBytes = Math.addExact(actualBytes, measured.first)
                Staged(stream.kind, file, descriptor)
            }
            check(actualBytes <= reservation.bytes && actualBytes <= request.accepted.profile.maximumComponentBytes) { "Component bytes exceed accepted reservation" }
            val captureId = safe(request.accepted.identity.attemptId)
            val assets = path(location, "assets", captureId).also(files::ensureDirectory)
            staged.forEach { moveImmutable(it.file, path(assets, "${it.kind.name.lowercase()}.${it.descriptor.sha256.hex()}.blob"), it.descriptor) }
            val prior = selectedRoot(location)
            val revision = (prior?.revision ?: 0L) + 1L
            val rootBytes = rootRecord(request, requestHash, revision, staged, prior)
            val rootHash = sha256(rootBytes).hex()
            faults.at(DurableStoreFaultPointV2.ROOT_WRITE)
            writeImmutable(path(location, "objects", "$rootHash.root"), rootBytes)
            faults.at(DurableStoreFaultPointV2.ROOT_FILE_SYNC)
            val terminal = CaptureTerminal(CaptureTerminalKind.COMMITTED_PICTURE, request.accepted.identity, requestHash, "committed", captureId, revision, rootHash)
            val committed = CaptureReceipt(request.accepted.identity, CaptureAttemptPhase.COMMITTED_PICTURE, requestHash, receiptHash(requestHash, rootHash), true, terminal)
            // Receipt is durable before, and independently validates, the pointer switch.
            faults.at(DurableStoreFaultPointV2.RECEIPT_WRITE)
            writePropertiesExclusive(receiptFile(location, request.accepted.identity), receiptProperties(committed, rootHash))
            faults.at(DurableStoreFaultPointV2.RECEIPT_FILE_SYNC)
            publishPointer(location, RootPointer(revision, rootHash, request.accepted.identity.commitId))
            budget.commit(reservation, actualBytes)
            deleteTree(staging, DurableStoreFaultPointV2.DELETE_RECLAIM)
            committed
        } catch (error: Throwable) {
            // Once receipt persistence has begun the exact pointer cut is unknown;
            // retain staging and liability for restart/query rather than fabricate abandonment.
            if (!files.isFile(receiptFile(location, request.accepted.identity))) {
                deleteTree(staging, DurableStoreFaultPointV2.DELETE_RECLAIM)
            }
            throw error
        }
    }

    /**
     * The direct Camera2 path: accepted identity and reservation already exist,
     * then the store consumes each sole native stream once into staging while
     * calculating its descriptor.  No descriptor placeholder or replayable
     * image payload exists outside this method.
     */
    internal fun commitStreamedFinalized(
        template: CapturePostOutputTemplateV2,
        exposureTimestampNanoseconds: Long,
        streams: List<CaptureComponentStreamV2>,
    ): CaptureReceipt = synchronized(mutex) {
        val identity = template.accepted.identity
        val location = location(identity)
        tombstone(location)?.let { throw DurableStoreConflictV2("Session is tombstoned") }
        receipt(location, identity)?.let { return@synchronized it }
        val accepted = readProperties(acceptedFile(location, identity))
        check(accepted.getProperty("acceptedHash") == acceptedHash(template.accepted)) { "Attempt was not durably accepted" }
        check(streams.map { it.kind }.toSet() == template.accepted.profile.requiredComponents && streams.size == streams.map { it.kind }.toSet().size) {
            "Stream component set is incomplete or duplicated"
        }
        val reservation = budget.reservation(accepted.getProperty("reservationToken"))
            ?: throw IllegalStateException("Accepted attempt has no live reservation")
        val staging = path(attemptDirectory(location, identity), "staging")
        files.ensureDirectory(staging)
        var actualBytes = 0L
        try {
            val staged = streams.sortedBy { it.kind.ordinal }.map { stream ->
                val file = path(staging, "${stream.kind.name.lowercase()}.part")
                faults.at(DurableStoreFaultPointV2.COMPONENT_OPEN)
                val measured = files.streamExclusive(file, stream.input)
                faults.at(DurableStoreFaultPointV2.LENGTH_CHECK)
                actualBytes = Math.addExact(actualBytes, measured.first)
                val hash = measured.second.map { it.toInt() and 0xff }
                val descriptor = CaptureComponentDescriptor(stream.kind, measured.first, hash, measured.second.hex())
                Staged(stream.kind, file, descriptor)
            }
            check(actualBytes <= reservation.bytes && actualBytes <= template.accepted.profile.maximumComponentBytes) { "Component bytes exceed accepted reservation" }
            val request = CaptureCommitRequest(
                template.accepted, staged.map { it.descriptor }, exposureTimestampNanoseconds,
                template.poseRecordHash, template.cameraModelHash, template.validationRecordHash, template.ledgerRecordHash,
            )
            val requestHash = requestHash(request)
            val captureId = safe(identity.attemptId)
            val assets = path(location, "assets", captureId).also(files::ensureDirectory)
            staged.forEach { moveImmutable(it.file, path(assets, "${it.kind.name.lowercase()}.${it.descriptor.sha256.hex()}.blob"), it.descriptor) }
            val prior = selectedRoot(location)
            val revision = (prior?.revision ?: 0L) + 1L
            val rootBytes = rootRecord(request, requestHash, revision, staged, prior)
            val rootHash = sha256(rootBytes).hex()
            faults.at(DurableStoreFaultPointV2.ROOT_WRITE)
            writeImmutable(path(location, "objects", "$rootHash.root"), rootBytes)
            faults.at(DurableStoreFaultPointV2.ROOT_FILE_SYNC)
            val terminal = CaptureTerminal(CaptureTerminalKind.COMMITTED_PICTURE, identity, requestHash, "committed", captureId, revision, rootHash)
            val committed = CaptureReceipt(identity, CaptureAttemptPhase.COMMITTED_PICTURE, requestHash, receiptHash(requestHash, rootHash), true, terminal)
            faults.at(DurableStoreFaultPointV2.RECEIPT_WRITE)
            writePropertiesExclusive(
                receiptFile(location, identity),
                receiptProperties(committed, rootHash).apply {
                    setProperty("templateHash", templateHash(template))
                },
            )
            faults.at(DurableStoreFaultPointV2.RECEIPT_FILE_SYNC)
            publishPointer(location, RootPointer(revision, rootHash, identity.commitId))
            budget.commit(reservation, actualBytes)
            deleteTree(staging, DurableStoreFaultPointV2.DELETE_RECLAIM)
            committed
        } catch (error: Throwable) {
            if (!files.isFile(receiptFile(location, identity))) deleteTree(staging, DurableStoreFaultPointV2.DELETE_RECLAIM)
            throw error
        }
    }

    override fun prepareCommit(request: CaptureCommitRequest): CaptureReceipt =
        throw UnsupportedOperationException("DurableSessionStoreV2 requires native component streams")

    /** Re-publishes an already hashed/received attempt after an unknown pointer cut; no sensor input is accepted. */
    fun rebaseSameAttempt(request: CaptureCommitRequest): CaptureReceipt = synchronized(mutex) {
        val location = location(request.accepted.identity)
        val file = receiptFile(location, request.accepted.identity)
        check(files.isFile(file)) { "No durable receipt to rebase" }
        val values = readProperties(file)
        check(values.getProperty("kind") == CaptureTerminalKind.COMMITTED_PICTURE.name && values.getProperty("requestHash") == requestHash(request)) { "Changed attempt cannot rebase" }
        val rootHash = values.getProperty("rootHash")
        val revision = values.getProperty("revision").toLong()
        check(validRootHash(location, rootHash, revision, 0)) { "Receipt root is incomplete" }
        val selected = selectedRoot(location)
        if (rootHash !in retainedRootHashes(location)) {
            check(revision == (selected?.revision ?: 0L) + 1L && rootPrevious(location, rootHash) == selected?.rootHash) {
                "Unknown receipt cannot rebase over a different root cut"
            }
            publishPointer(location, RootPointer(revision, rootHash, request.accepted.identity.commitId))
        }
        val accepted = readProperties(acceptedFile(location, request.accepted.identity))
        budget.reservation(accepted.getProperty("reservationToken"))?.let { reservation ->
            budget.commit(reservation, request.components.fold(0L) { total, component -> Math.addExact(total, component.byteLength) })
        }
        deleteTree(path(attemptDirectory(location, request.accepted.identity), "staging"), DurableStoreFaultPointV2.DELETE_RECLAIM)
        receipt(location, request.accepted.identity)!!
    }

    override fun queryReceipt(identity: CaptureAttemptIdentity): CaptureReceipt? = synchronized(mutex) {
        val location = location(identity)
        if (tombstone(location) != null) null else receipt(location, identity)
    }

    override fun abandon(terminal: CaptureTerminal): CaptureReceipt = synchronized(mutex) {
        abandonLocked(terminal, fullRequestHash = null, binding = ABANDONMENT_BINDING_METADATA_ONLY)
    }

    /** #101 native abandonment binds the terminal to the complete frozen request. */
    internal fun abandonCapture(request: CaptureCommitRequest, terminal: CaptureTerminal): CaptureReceipt = synchronized(mutex) {
        require(request.accepted.identity == terminal.identity)
        abandonLocked(terminal, fullRequestHash = requestHash(request), binding = ABANDONMENT_BINDING_FULL_REQUEST)
    }

    internal fun abandonTemplate(template: CapturePostOutputTemplateV2, terminal: CaptureTerminal): CaptureReceipt = synchronized(mutex) {
        require(template.accepted.identity == terminal.identity)
        abandonLocked(
            terminal,
            fullRequestHash = null,
            binding = "post-output-template-v2",
            templateHash = templateHash(template),
        )
    }

    private fun abandonLocked(
        terminal: CaptureTerminal,
        fullRequestHash: String?,
        binding: String,
        templateHash: String? = null,
    ): CaptureReceipt {
        require(terminal.kind == CaptureTerminalKind.ABANDONED_ATTEMPT)
        val location = location(terminal.identity)
        receipt(location, terminal.identity)?.let { return it }
        val accepted = acceptedFile(location, terminal.identity).takeIf(files::isFile)?.let(::readProperties)
        faults.at(DurableStoreFaultPointV2.ABANDONMENT_CLEANUP)
        deleteTree(path(attemptDirectory(location, terminal.identity), "staging"), DurableStoreFaultPointV2.ABANDONMENT_CLEANUP)
        deleteTree(assetDirectory(location, terminal.identity), DurableStoreFaultPointV2.ABANDONMENT_CLEANUP)
        val boundHash = fullRequestHash ?: terminal.canonicalTerminalHash
        val value = CaptureReceipt(terminal.identity, CaptureAttemptPhase.ABANDONED_ATTEMPT, boundHash,
            receiptHash(boundHash, "abandoned"), true, terminal)
        faults.at(DurableStoreFaultPointV2.ABANDONMENT_RECORD)
        writePropertiesExclusive(receiptFile(location, terminal.identity), receiptProperties(value, "abandoned").apply {
            setProperty("requestBinding", binding)
            fullRequestHash?.let { setProperty("fullRequestHash", it) }
            templateHash?.let { setProperty("templateHash", it) }
        })
        accepted?.getProperty("reservationToken")?.let { budget.reservation(it)?.let(budget::release) }
        return value
    }

    /** Tombstone-first whole-session deletion fence.  It wins over later callbacks and roots. */
    fun tombstoneSession(sessionId: String) = synchronized(mutex) {
        val directory = files.child("sessions", safe(sessionId))
        files.ensureDirectory(directory)
        faults.at(DurableStoreFaultPointV2.TOMBSTONE_RECORD)
        writeExclusive(path(directory, "tombstone"), "deleted\n".toByteArray(), DurableStoreFaultPointV2.TOMBSTONE_RECORD)
        faults.at(DurableStoreFaultPointV2.TOMBSTONE_DIRECTORY_SYNC)
        files.walk(directory).filter { it.name == "accepted.properties" }.forEach { file ->
            readProperties(file).getProperty("reservationToken")?.let { budget.reservation(it)?.let(budget::release) }
        }
    }

    /** Reconciles prepared staging and ensures only receipt/pointer backed results are visible. */
    fun recover() = synchronized(mutex) {
        faults.at(DurableStoreFaultPointV2.RECOVERY)
        files.list(sessionRoot).filter(File::isDirectory).forEach { session ->
            files.walk(session).filter { it.name == "accepted.properties" }.forEach { accepted ->
                val attempt = requireNotNull(accepted.parentFile)
                val receipt = path(attempt, "receipt.properties")
                val acceptedValues = readProperties(accepted)
                if (!files.isFile(receipt)) {
                    val abandoned = Properties().apply {
                        setProperty("kind", CaptureTerminalKind.ABANDONED_ATTEMPT.name)
                        setProperty("requestHash", acceptedValues.getProperty("acceptedHash"))
                        setProperty("receiptHash", receiptHash(acceptedValues.getProperty("acceptedHash"), "abandoned"))
                        setProperty("rootHash", "abandoned")
                        setProperty("reason", "recovered-proven-absent")
                        setProperty("requestBinding", ABANDONMENT_BINDING_RECOVERED)
                    }
                    writePropertiesExclusive(receipt, abandoned, DurableStoreFaultPointV2.ABANDONMENT_RECORD)
                    deleteTree(path(attempt, "staging"), DurableStoreFaultPointV2.ABANDONMENT_CLEANUP)
                    acceptedValues.getProperty("attemptId")?.let { deleteTree(path(session, "assets", safe(it)), DurableStoreFaultPointV2.ABANDONMENT_CLEANUP) }
                    acceptedValues.getProperty("reservationToken")?.let { budget.reservation(it)?.let(budget::release) }
                } else {
                    val receiptValues = readProperties(receipt)
                    if (receiptValues.getProperty("kind") == CaptureTerminalKind.COMMITTED_PICTURE.name &&
                        receiptValues.getProperty("rootHash") in retainedRootHashes(session)) {
                        acceptedValues.getProperty("reservationToken")?.let { token ->
                            budget.reservation(token)?.let { reservation ->
                                budget.commit(reservation, committedComponentBytes(session, receiptValues.getProperty("rootHash")))
                            }
                        }
                        deleteTree(path(attempt, "staging"), DurableStoreFaultPointV2.DELETE_RECLAIM)
                    }
                    // A committed receipt outside the retained graph is unknown:
                    // staging and liability remain for exact query/rebase.
                }
            }
        }
    }

    private fun location(identity: CaptureAttemptIdentity): File = files.child("sessions", safe(identity.lifecycleCut.sessionId)).also(files::ensureDirectory)
    private fun attemptDirectory(location: File, identity: CaptureAttemptIdentity): File =
        path(location, "attempts", safe(identity.commitId)).also(files::ensureDirectory)
    private fun tombstone(location: File): File? = path(location, "tombstone").takeIf(files::isFile)
    private fun acceptedFile(location: File, identity: CaptureAttemptIdentity) = path(attemptDirectory(location, identity), "accepted.properties")
    private fun receiptFile(location: File, identity: CaptureAttemptIdentity) = path(attemptDirectory(location, identity), "receipt.properties")
    private fun assetDirectory(location: File, identity: CaptureAttemptIdentity) = path(location, "assets", safe(identity.attemptId))
    private fun receipt(location: File, identity: CaptureAttemptIdentity): CaptureReceipt? {
        val file = receiptFile(location, identity); if (!files.isFile(file)) return null
        val value = readProperties(file)
        val kind = value.getProperty("kind") ?: return null
        if (kind == CaptureTerminalKind.COMMITTED_PICTURE.name && value.getProperty("rootHash") !in retainedRootHashes(location)) return null
        val terminal = if (kind == CaptureTerminalKind.COMMITTED_PICTURE.name) CaptureTerminal(CaptureTerminalKind.COMMITTED_PICTURE, identity, value.getProperty("requestHash"), "committed", value.getProperty("captureId"), value.getProperty("revision").toLong(), value.getProperty("rootHash"))
        else CaptureTerminal(CaptureTerminalKind.ABANDONED_ATTEMPT, identity, value.getProperty("requestHash"), value.getProperty("reason", "abandoned"))
        return CaptureReceipt(identity, if (terminal.kind == CaptureTerminalKind.COMMITTED_PICTURE) CaptureAttemptPhase.COMMITTED_PICTURE else CaptureAttemptPhase.ABANDONED_ATTEMPT, value.getProperty("requestHash"), value.getProperty("receiptHash"), true, terminal)
    }
    private fun acceptedReceipt(attempt: CaptureAcceptedAttempt) = CaptureReceipt(attempt.identity, CaptureAttemptPhase.RESERVED_ACCEPTED, acceptedHash(attempt), attempt.acceptedReceiptHash.hex(), true)
    private fun acceptedHash(attempt: CaptureAcceptedAttempt) = sha256("${attempt.identity.commitId}|${attempt.identity.attemptId}|${attempt.reservation.totalStoreLiability}|${attempt.canonicalIntentHash.hex()}".toByteArray()).hex()

    private fun templateHash(template: CapturePostOutputTemplateV2) = sha256(
        "${acceptedHash(template.accepted)}|${template.poseRecordHash.hex()}|${template.cameraModelHash.hex()}|${template.validationRecordHash.hex()}|${template.ledgerRecordHash.hex()}".toByteArray(),
    ).hex()
    /** Canonical structural hash of every frozen #99 CaptureCommitRequest field. */
    private fun requestHash(request: CaptureCommitRequest): String {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { output ->
            fun text(value: String) {
                val encoded = value.toByteArray(StandardCharsets.UTF_8)
                output.writeInt(encoded.size)
                output.write(encoded)
            }
            fun digest(value: List<Int>) {
                output.writeInt(value.size)
                value.forEach(output::writeByte)
            }
            val accepted = request.accepted
            val identity = accepted.identity
            val cut = identity.lifecycleCut
            text(identity.attemptId); text(identity.commitId); output.writeLong(identity.attemptOrdinal)
            text(cut.sessionId); output.writeLong(cut.sessionGeneration); text(cut.groupId)
            output.writeLong(cut.groupGeneration); text(cut.arSessionId); text(cut.viewId)
            output.writeLong(cut.viewGeneration); text(cut.bindingToken)
            output.writeLong(cut.lifecycleSequence); output.writeLong(cut.operationGeneration)
            output.writeInt(accepted.lane.ordinal)
            text(accepted.profile.profileId)
            accepted.profile.requiredComponents.sortedBy { it.ordinal }.also { kinds ->
                output.writeInt(kinds.size); kinds.forEach { output.writeInt(it.ordinal) }
            }
            output.writeLong(accepted.profile.maximumComponentBytes)
            output.writeLong(accepted.profile.maximumWorkingBytes)
            output.writeLong(accepted.reservation.memoryBytes)
            output.writeLong(accepted.reservation.physicalStoreBytes)
            output.writeLong(accepted.reservation.componentEntries)
            output.writeLong(accepted.reservation.terminalEntries)
            output.writeLong(accepted.reservation.rollbackBytes)
            output.writeBoolean(accepted.reservation.physicallyBacked)
            digest(accepted.canonicalIntentHash); digest(accepted.acceptedReceiptHash)
            request.components.sortedBy { it.kind.ordinal }.also { components ->
                output.writeInt(components.size)
                components.forEach { component ->
                    output.writeInt(component.kind.ordinal); output.writeLong(component.byteLength)
                    digest(component.sha256); text(component.durableObjectId)
                }
            }
            output.writeLong(request.exposureTimestampNanoseconds)
            digest(request.poseRecordHash); digest(request.cameraModelHash)
            digest(request.validationRecordHash); digest(request.ledgerRecordHash)
        }
        return sha256(bytes.toByteArray()).hex()
    }
    private fun reservationOwner(identity: CaptureAttemptIdentity) = "capture:${safe(identity.lifecycleCut.sessionId)}:${safe(identity.commitId)}"
    private fun receiptHash(requestHash: String, rootHash: String) = sha256("$requestHash|$rootHash".toByteArray()).hex()
    private fun rootRecord(request: CaptureCommitRequest, requestHash: String, revision: Long, staged: List<Staged>, prior: RootPointer?) = buildString { append("schema=5\nrevision=$revision\nrequest=$requestHash\nprevious=${prior?.rootHash ?: "-"}\nsecondPrevious=${prior?.previousHash ?: "-"}\n"); staged.sortedBy { it.kind.ordinal }.forEach { append("component=${it.kind}:${it.descriptor.byteLength}:${it.descriptor.sha256.hex()}\n") } }.toByteArray(StandardCharsets.UTF_8)
    private fun receiptProperties(receipt: CaptureReceipt, rootHash: String) = Properties().apply { setProperty("kind", receipt.terminal!!.kind.name); setProperty("requestHash", receipt.requestHash); setProperty("receiptHash", receipt.receiptHash); setProperty("rootHash", rootHash); setProperty("reason", receipt.terminal.reason); receipt.terminal.captureId?.let { setProperty("captureId", it) }; receipt.terminal.captureRevision?.let { setProperty("revision", it.toString()) } }

    private fun replayReceiptBeforeExposure(location: File, request: CaptureCommitRequest): CaptureReceipt? {
        val prior = receipt(location, request.accepted.identity) ?: return null
        val properties = readProperties(receiptFile(location, request.accepted.identity))
        if (prior.terminal?.kind == CaptureTerminalKind.ABANDONED_ATTEMPT) {
            if (properties.getProperty("requestBinding") != ABANDONMENT_BINDING_FULL_REQUEST) {
                throw DurableStoreConflictV2("Metadata-only abandonment cannot prove exact request replay")
            }
            if (properties.getProperty("fullRequestHash") == requestHash(request)) return prior
            throw DurableStoreConflictV2("Changed replay conflicts with durable abandoned identity")
        }
        if (prior.requestHash == requestHash(request)) return prior
        throw DurableStoreConflictV2("Changed replay conflicts with durable terminal identity")
    }
    private fun selectedRoot(location: File): RootPointer? = listOf("root-A.ptr", "root-B.ptr").mapNotNull { file ->
        path(location, file).takeIf(files::isFile)?.let(files::readLines)?.takeIf { it.size == 3 }?.let {
            RootPointer(it[0].toLongOrNull() ?: return@let null, it[1], it[2], if (file == "root-A.ptr") "A" else "B", rootPrevious(location, it[1]))
        }
    }.filter { it.rootHash !in uncertainRootHashes && validRoot(location, it) }.let { candidates ->
        candidates.groupBy { it.revision }.values.forEach { same -> if (same.map { it.rootHash }.toSet().size > 1) throw DurableStoreConflictV2("Schema-5 root fork") }
        candidates.maxByOrNull { it.revision }
    }
    private fun rootPrevious(location: File, hash: String): String? = path(location, "objects", "$hash.root").takeIf(files::isFile)?.let(files::readLines)?.firstOrNull { it.startsWith("previous=") }?.removePrefix("previous=")?.takeUnless { it == "-" }
    private fun validRoot(location: File, pointer: RootPointer): Boolean {
        return validRootHash(location, pointer.rootHash, pointer.revision, 0)
    }
    private fun validRootHash(location: File, hash: String, revision: Long, depth: Int): Boolean {
        if (depth > 2 || !hash.matches(Regex("[0-9a-f]{64}"))) return false
        val file = path(location, "objects", "$hash.root")
        if (!files.isFile(file) || files.digestAndLength(file).second.hex() != hash) return false
        val values = files.readLines(file).associate { it.substringBefore('=') to it.substringAfter('=', "") }
        if (values["schema"] != "5" || values["revision"]?.toLongOrNull() != revision || values["request"]?.matches(Regex("[0-9a-f]{64}")) != true) return false
        val previous = values["previous"]
        val second = values["secondPrevious"]
        if (depth == 2) return true
        if (revision == 1L) return previous == "-" && second == "-"
        if (previous == null || previous == "-" || !validRootHash(location, previous, revision - 1, depth + 1)) return false
        if (depth == 0 && revision >= 3 && second != rootPrevious(location, previous)) return false
        return true
    }
    private fun publishPointer(location: File, pointer: RootPointer) {
        val old = selectedRoot(location)
        val target = path(location, if (old?.slot == "A") "root-B.ptr" else "root-A.ptr")
        try {
            writeAtomic(target, "${pointer.revision}\n${pointer.rootHash}\n${pointer.commitId}\n".toByteArray())
            uncertainRootHashes.remove(pointer.rootHash)
        } catch (error: PointerDirectorySyncUnknownV2) {
            uncertainRootHashes += pointer.rootHash
            throw error
        }
    }
    private fun moveImmutable(from: File, target: File, descriptor: CaptureComponentDescriptor) { if (files.isFile(target)) { val measured = files.digestAndLength(target); check(measured.first == descriptor.byteLength && measured.second.hex() == descriptor.sha256.hex()) { "Immutable asset conflict" }; files.delete(from, DurableStoreFaultPointV2.DELETE_RECLAIM); return }; files.moveAtomic(from, target) }
    private fun writePropertiesExclusive(file: File, properties: Properties, point: DurableStoreFaultPointV2 = DurableStoreFaultPointV2.ACCEPTED_RECORD) = writeExclusive(file, buildString { properties.stringPropertyNames().sorted().forEach { append(it).append('=').append(properties.getProperty(it)).append('\n') } }.toByteArray(), point)
    private fun readProperties(file: File) = Properties().apply { files.readBytes(file).inputStream().use(::load) }
    private fun writeExclusive(file: File, bytes: ByteArray, point: DurableStoreFaultPointV2 = DurableStoreFaultPointV2.ACCEPTED_RECORD) = files.writeExclusive(file, bytes, point)
    private fun writeImmutable(file: File, bytes: ByteArray) {
        files.writeImmutable(file, bytes, DurableStoreFaultPointV2.ROOT_WRITE)
    }
    private fun writeAtomic(file: File, bytes: ByteArray) { files.atomicReplace(file, bytes, DurableStoreFaultPointV2.POINTER_SLOT_REPLACE) }
    private fun deleteTree(file: File, point: DurableStoreFaultPointV2) { files.deleteTree(file, point) }
    private fun retainedRootHashes(location: File): Set<String> {
        val selected = selectedRoot(location) ?: return emptySet()
        return buildSet {
            var hash: String? = selected.rootHash
            repeat(3) { hash?.let(::add); hash = hash?.let { rootPrevious(location, it) } }
        }
    }
    private fun committedComponentBytes(location: File, rootHash: String): Long = files.readLines(path(location, "objects", "$rootHash.root"))
        .filter { it.startsWith("component=") }
        .fold(0L) { total, line -> Math.addExact(total, line.substringAfter(':').substringBefore(':').toLong()) }
    private fun relative(file: File): Array<String> = root.absoluteFile.toPath().normalize().relativize(file.absoluteFile.toPath().normalize()).map { it.toString() }.toList().toTypedArray()
    private fun path(base: File, vararg names: String): File = files.child(*(relative(base) + names))
    private fun safe(value: String) = sha256(value.toByteArray()).hex()
    private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
    private fun ByteArray.hex() = joinToString("") { "%02x".format(it) }
    private fun List<Int>.hex() = joinToString("") { "%02x".format(it) }
    private data class Staged(val kind: CaptureComponentKind, val file: File, val descriptor: CaptureComponentDescriptor)
    private data class RootPointer(val revision: Long, val rootHash: String, val commitId: String, val slot: String = "", val previousHash: String? = null)
}
