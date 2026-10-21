package com.uhg0.ar_flutter_plugin_2.capture

import com.uhg0.ar_flutter_plugin_2.storeversion.NativeStoreFormatVersions
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
    internal data class RecoveryProjectionV2(
        val attemptId: String,
        val kind: CaptureTerminalKind?,
        val captureId: String? = null,
        val captureRevision: Long? = null,
        val manifestId: String? = null,
        val reason: String,
        val recoveryContext: NativeCaptureRecoveryContextV2? = null,
        val jpegSizeBytes: Long? = null,
        val dngSizeBytes: Long? = null,
        val qualityAssessment: CaptureQualityAssessmentV1? = null,
        val qualityAssessmentHash: String? = null,
        val verifiedTerminal: CaptureTerminal? = null,
    )
    private val mutex = Any()
    private val files = SafeFilesystemV2(root, faults, filesystemBackend)
    private val sessionRoot = files.child("sessions")
    private val recoveryIndex = files.child("recovery-index.properties")
    private val uncertainRootHashes = mutableSetOf<String>()
    private val rootMetadata = CaptureRootMetadataReaderV2()

    init {
        try { files.ensureDirectory(sessionRoot) }
        catch (error: Throwable) { files.close(); throw error }
    }

    /**
     * Materializes one committed JPEG into an app-private preview cache.
     *
     * V2 keeps the durable asset under the native store.  The UI needs a
     * displayable image, but it must not receive a durable-store path or make
     * the capture payload part of the Dart ownership protocol.  The caller
     * supplies a cache target owned by the plugin; this method only accepts
     * the immutable manifest and capture hashes published in the terminal
     * event and copies the matching retained asset.
     */
    internal fun materializeJpegPreview(
        manifestId: String,
        captureId: String,
        target: File,
    ): Boolean = synchronized(mutex) {
        if (!SAFE_HASH.matches(manifestId) || !SAFE_HASH.matches(captureId)) return@synchronized false
        val locations = sessionRoot.listFiles()
            ?.asSequence()
            ?.filter { it.isDirectory }
            .orEmpty()
        var source: File? = null
        for (location in locations) {
            if (manifestId !in retainedRootHashes(location)) continue
            val jpegHash = readRootMetadata(location, manifestId)?.components
                ?.firstOrNull { it.kind == "JPEG" }?.hash
                ?: continue
            val asset = path(location, "assets", captureId)
            source = path(asset, "jpeg.$jpegHash.blob").takeIf(files::isFile)
            if (source != null) break
        }
        val selectedSource = source ?: return@synchronized false
        target.parentFile?.mkdirs()
        if (target.isFile && target.length() > 0L) return@synchronized true
        selectedSource.copyTo(target, overwrite = true)
        target.isFile && target.length() > 0L
    }

    /** Closes only this store's owned bound filesystem; the injected budget is borrowed. */
    override fun close() = synchronized(mutex) { files.close() }

    override fun acceptBeforeExposure(attempt: CaptureAcceptedAttempt): CaptureReceipt =
        acceptBeforeExposureInternal(attempt, null)

    private fun acceptBeforeExposureInternal(
        attempt: CaptureAcceptedAttempt,
        recoveryContext: NativeCaptureRecoveryContextV2?,
    ): CaptureReceipt = synchronized(mutex) {
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
                putExactIdentity(attempt.identity)
                recoveryContext?.let { putRecoveryContext(it) }
            }
            check(propertiesBytes(data).size <= NativeCaptureReservationBoundsV2.ACCEPTED_RECORD_BYTES) { "Accepted metadata exceeds reserved record bound" }
            faults.at(DurableStoreFaultPointV2.ACCEPTED_RECORD)
            recordRecoveryCandidate(location, requireNotNull(accepted.parentFile))
            writePropertiesExclusive(accepted, data)
            acceptedReceipt(attempt)
        } catch (error: Throwable) { budget.release(reservation); throw error }
    }

    /**
     * Native admission fence for #101. A terminal identity is checked against
     * the complete commit request before a scheduler owner or shutter can be
     * allocated. The frozen public port remains attempt-based for #99 callers.
     */
    internal fun acceptCaptureBeforeExposure(
        request: CaptureCommitRequest,
        requireRecoveryIdentity: Boolean = false,
    ): CaptureReceipt = synchronized(mutex) {
        if (requireRecoveryIdentity) {
            require(request.accepted.reservation.physicalStoreBytes >= NativeCaptureReservationBoundsV2.ACCEPTED_RECORD_BYTES) { "Exact recovery identity requires funded accepted metadata" }
        }
        val location = location(request.accepted.identity)
        tombstone(location)?.let { throw DurableStoreConflictV2("Session is tombstoned") }
        replayReceiptBeforeExposure(location, request)?.let { return@synchronized it }
        acceptBeforeExposureInternal(request.accepted, request.recoveryContext).also {
            if (requireRecoveryIdentity) requireExactAcceptedIdentity(location, request.accepted.identity)
        }
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

    internal fun replayTemplateFenceBeforeExposure(template: CapturePostOutputTemplateV2): CaptureReceipt? =
        replayTemplateFenceBeforeExposureBound(template, allowAssessedQuality = false)

    /** Pre-exposure query carries existing durable evidence; it never verifies newly supplied sensor bytes. */
    internal fun replayAssessedTemplateFenceBeforeExposure(template: CapturePostOutputTemplateV2): CaptureReceipt? =
        replayTemplateFenceBeforeExposureBound(template, allowAssessedQuality = true)

    private fun replayTemplateFenceBeforeExposureBound(
        template: CapturePostOutputTemplateV2,
        allowAssessedQuality: Boolean,
    ): CaptureReceipt? = synchronized(mutex) {
        val location = location(template.accepted.identity)
        tombstone(location)?.let { throw DurableStoreConflictV2("Session is tombstoned") }
        val receiptFile = receiptFile(location, template.accepted.identity)
        if (files.isFile(receiptFile)) {
            val properties = readProperties(receiptFile)
            if (properties.getProperty("templateHash") != templateHash(template)) {
                throw DurableStoreConflictV2("Changed accepted template conflicts with durable identity")
            }
            val prior = receipt(location, template.accepted.identity)
                ?: throw DurableStoreConflictV2("Template receipt is not selected-root authoritative")
            if (prior.terminal?.qualityAssessment != null && allowAssessedQuality) {
                requireExactAcceptedIdentity(location, template.accepted.identity)
            }
            if (prior.terminal?.qualityAssessment != null && !allowAssessedQuality) {
                throw DurableStoreConflictV2("Assessed terminal requires disposition-aware replay")
            }
            return@synchronized prior
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
    fun commitStreamed(request: CaptureCommitRequest, streams: List<CaptureComponentStreamV2>): CaptureReceipt =
        commitStreamedBound(request, streams, assessed = false)

    /** Explicit opt-in only; current native callers remain on the legacy entrypoint. */
    fun commitStreamedAssessed(request: CaptureCommitRequest, streams: List<CaptureComponentStreamV2>): CaptureReceipt =
        commitStreamedBound(request, streams, assessed = true)

    private fun commitStreamedBound(
        request: CaptureCommitRequest,
        streams: List<CaptureComponentStreamV2>,
        assessed: Boolean,
    ): CaptureReceipt = synchronized(mutex) {
        require((request.qualityAssessment != null) == assessed) { "Quality requires the explicit assessed store entrypoint" }
        val location = location(request.accepted.identity)
        tombstone(location)?.let { throw DurableStoreConflictV2("Session is tombstoned") }
        val requestHash = requestHash(request)
        receipt(location, request.accepted.identity)?.let { prior ->
            if (assessed && streams.isNotEmpty()) {
                streams.forEach { it.input.close() }
                throw DurableStoreConflictV2("Assessed descriptor replay accepts no new streams; use finalized assessed replay to verify bytes")
            }
            if (prior.requestHash == requestHash) return@synchronized prior
            throw DurableStoreConflictV2("Changed replay conflicts with committed durable identity")
        }
        val accepted = readProperties(acceptedFile(location, request.accepted.identity))
        check(accepted.getProperty("acceptedHash") == acceptedHash(request.accepted)) { "Attempt was not durably accepted" }
        if (assessed) requireExactAcceptedIdentity(location, request.accepted.identity)
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
            // A root whose pointer rename is not yet directory-durable still has a durable root
            // object and receipt: the next commit builds on it instead of overwriting its slot.
            val prior = selectedRoot(location, includeUncertain = true)
            val revision = (prior?.revision ?: 0L) + 1L
            val rootBytes = rootRecord(request, requestHash, revision, staged, prior)
            val rootHash = sha256(rootBytes).hex()
            faults.at(DurableStoreFaultPointV2.ROOT_WRITE)
            writeImmutable(path(location, "objects", "$rootHash.root"), rootBytes)
            faults.at(DurableStoreFaultPointV2.ROOT_FILE_SYNC)
            val jpegSizeBytes = request.components.single { it.kind == CaptureComponentKind.JPEG }.byteLength
            val dngSizeBytes = request.components.singleOrNull { it.kind == CaptureComponentKind.DNG }?.byteLength
            val terminal = CaptureTerminal(CaptureTerminalKind.COMMITTED_PICTURE, request.accepted.identity, requestHash, "committed", captureId, revision, rootHash, jpegSizeBytes, dngSizeBytes, request.qualityAssessment,
                request.qualityAssessment?.let { CaptureQualityAssessmentV1.boundHash(requestHash, it) })
            val committed = CaptureReceipt(request.accepted.identity, CaptureAttemptPhase.COMMITTED_PICTURE, requestHash, receiptHash(requestHash, rootHash), true, terminal)
            // Receipt is durable before, and independently validates, the pointer switch.
            faults.at(DurableStoreFaultPointV2.RECEIPT_WRITE)
            writeReceipt(receiptFile(location, request.accepted.identity), receiptProperties(committed, rootHash), DurableStoreFaultPointV2.RECEIPT_REPLACE)
            faults.at(DurableStoreFaultPointV2.RECEIPT_FILE_SYNC)
            publishPointer(location, RootPointer(revision, rootHash, request.accepted.identity.commitId), prior)
            budget.settle(reservation, actualBytes)
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
    ): CaptureReceipt = commitStreamedFinalizedBound(template, exposureTimestampNanoseconds, streams, null)

    /** Assessment binds after actual descriptors exist. Repeated streams are rehashed, never metadata-only replay. */
    internal fun commitStreamedFinalizedAssessed(
        template: CapturePostOutputTemplateV2,
        exposureTimestampNanoseconds: Long,
        streams: List<CaptureComponentStreamV2>,
        qualityAssessment: CaptureQualityAssessmentV1,
    ): CaptureReceipt = commitStreamedFinalizedBound(template, exposureTimestampNanoseconds, streams, qualityAssessment)

    private fun commitStreamedFinalizedBound(
        template: CapturePostOutputTemplateV2,
        exposureTimestampNanoseconds: Long,
        streams: List<CaptureComponentStreamV2>,
        qualityAssessment: CaptureQualityAssessmentV1?,
    ): CaptureReceipt = synchronized(mutex) {
        val identity = template.accepted.identity
        val location = location(identity)
        tombstone(location)?.let { throw DurableStoreConflictV2("Session is tombstoned") }
        val priorReceipt = receipt(location, identity)
        if (qualityAssessment == null && priorReceipt != null) {
            if (priorReceipt.terminal?.qualityAssessment != null) {
                throw DurableStoreConflictV2("Assessed terminal cannot replay through legacy finalization")
            }
            return@synchronized priorReceipt
        }
        val accepted = readProperties(acceptedFile(location, identity))
        check(accepted.getProperty("acceptedHash") == acceptedHash(template.accepted)) { "Attempt was not durably accepted" }
        if (qualityAssessment != null) requireExactAcceptedIdentity(location, identity)
        check(streams.map { it.kind }.toSet() == template.accepted.profile.requiredComponents && streams.size == streams.map { it.kind }.toSet().size) {
            "Stream component set is incomplete or duplicated"
        }
        val reservation = budget.reservation(accepted.getProperty("reservationToken"))
        if (priorReceipt == null && reservation == null) throw IllegalStateException("Accepted attempt has no live reservation")
        val staging = path(attemptDirectory(location, identity), "staging")
        files.ensureDirectory(staging)
        var actualBytes = 0L
        try {
            val staged = streams.sortedBy { it.kind.ordinal }.map { stream ->
                val file = path(staging, "${stream.kind.name.lowercase()}.part")
                faults.at(DurableStoreFaultPointV2.COMPONENT_OPEN)
                val measured = files.streamExclusive(file,
                    if (qualityAssessment == null) stream.input else boundedAssessmentStream(stream.input,
                        template.accepted.profile.maximumComponentBytes - actualBytes))
                faults.at(DurableStoreFaultPointV2.LENGTH_CHECK)
                actualBytes = Math.addExact(actualBytes, measured.first)
                val hash = measured.second.map { it.toInt() and 0xff }
                val descriptor = CaptureComponentDescriptor(stream.kind, measured.first, hash, measured.second.hex())
                Staged(stream.kind, file, descriptor)
            }
            check(actualBytes <= (reservation?.bytes ?: template.accepted.reservation.physicalStoreBytes) && actualBytes <= template.accepted.profile.maximumComponentBytes) { "Component bytes exceed accepted reservation" }
            val request = CaptureCommitRequest(
                template.accepted, staged.map { it.descriptor }, exposureTimestampNanoseconds,
                template.poseRecordHash, template.cameraModelHash, template.validationRecordHash, template.ledgerRecordHash,
                qualityAssessment = qualityAssessment,
            )
            val requestHash = requestHash(request)
            priorReceipt?.let { prior ->
                deleteTree(staging, DurableStoreFaultPointV2.DELETE_RECLAIM)
                if (prior.requestHash == requestHash) return@synchronized prior
                throw DurableStoreConflictV2("Changed assessment, exposure, template or component conflicts with durable identity")
            }
            val captureId = safe(identity.attemptId)
            val assets = path(location, "assets", captureId).also(files::ensureDirectory)
            staged.forEach { moveImmutable(it.file, path(assets, "${it.kind.name.lowercase()}.${it.descriptor.sha256.hex()}.blob"), it.descriptor) }
            // A root whose pointer rename is not yet directory-durable still has a durable root
            // object and receipt: the next commit builds on it instead of overwriting its slot.
            val prior = selectedRoot(location, includeUncertain = true)
            val revision = (prior?.revision ?: 0L) + 1L
            val rootBytes = rootRecord(request, requestHash, revision, staged, prior)
            val rootHash = sha256(rootBytes).hex()
            faults.at(DurableStoreFaultPointV2.ROOT_WRITE)
            writeImmutable(path(location, "objects", "$rootHash.root"), rootBytes)
            faults.at(DurableStoreFaultPointV2.ROOT_FILE_SYNC)
            val jpegSizeBytes = request.components.single { it.kind == CaptureComponentKind.JPEG }.byteLength
            val dngSizeBytes = request.components.singleOrNull { it.kind == CaptureComponentKind.DNG }?.byteLength
            val terminal = CaptureTerminal(CaptureTerminalKind.COMMITTED_PICTURE, identity, requestHash, "committed", captureId, revision, rootHash, jpegSizeBytes, dngSizeBytes, request.qualityAssessment,
                request.qualityAssessment?.let { CaptureQualityAssessmentV1.boundHash(requestHash, it) })
            val committed = CaptureReceipt(identity, CaptureAttemptPhase.COMMITTED_PICTURE, requestHash, receiptHash(requestHash, rootHash), true, terminal)
            faults.at(DurableStoreFaultPointV2.RECEIPT_WRITE)
            writeReceipt(
                receiptFile(location, identity),
                receiptProperties(committed, rootHash).apply {
                    setProperty("templateHash", templateHash(template))
                },
                DurableStoreFaultPointV2.RECEIPT_REPLACE,
            )
            faults.at(DurableStoreFaultPointV2.RECEIPT_FILE_SYNC)
            publishPointer(location, RootPointer(revision, rootHash, identity.commitId), prior)
            budget.settle(requireNotNull(reservation), actualBytes)
            deleteTree(staging, DurableStoreFaultPointV2.DELETE_RECLAIM)
            committed
        } catch (error: Throwable) {
            if (priorReceipt != null || !files.isFile(receiptFile(location, identity))) {
                deleteTree(staging, DurableStoreFaultPointV2.DELETE_RECLAIM)
            }
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
            publishPointer(location, RootPointer(revision, rootHash, request.accepted.identity.commitId), selected)
        } else {
            uncertainRootHashes.remove(rootHash)
        }
        val accepted = readProperties(acceptedFile(location, request.accepted.identity))
        budget.reservation(accepted.getProperty("reservationToken"))?.let { reservation ->
            budget.settle(reservation, request.components.fold(0L) { total, component -> Math.addExact(total, component.byteLength) })
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
        // A receipt outside the retained graph (pointer cut unknown) keeps its assets and liability
        // for rebase or restart recovery; abandonment must not delete a possibly committed photo.
        if (files.isFile(receiptFile(location, terminal.identity))) {
            throw DurableStoreConflictV2("Attempt has an unresolved durable receipt")
        }
        val accepted = acceptedFile(location, terminal.identity).takeIf(files::isFile)?.let(::readProperties)
        faults.at(DurableStoreFaultPointV2.ABANDONMENT_CLEANUP)
        deleteTree(path(attemptDirectory(location, terminal.identity), "staging"), DurableStoreFaultPointV2.ABANDONMENT_CLEANUP)
        deleteTree(assetDirectory(location, terminal.identity), DurableStoreFaultPointV2.ABANDONMENT_CLEANUP)
        val boundHash = fullRequestHash ?: terminal.canonicalTerminalHash
        val value = CaptureReceipt(terminal.identity, CaptureAttemptPhase.ABANDONED_ATTEMPT, boundHash,
            receiptHash(boundHash, "abandoned"), true, terminal)
        faults.at(DurableStoreFaultPointV2.ABANDONMENT_RECORD)
        writeReceipt(receiptFile(location, terminal.identity), receiptProperties(value, "abandoned").apply {
            setProperty("requestBinding", binding)
            fullRequestHash?.let { setProperty("fullRequestHash", it) }
            templateHash?.let { setProperty("templateHash", it) }
        }, DurableStoreFaultPointV2.RECEIPT_REPLACE)
        accepted?.getProperty("reservationToken")?.let { budget.reservation(it)?.let(budget::release) }
        return value
    }

    /**
     * Tombstone-first whole-session deletion fence; it wins over later callbacks and roots.
     * Every live reservation of the session then closes: an attempt whose committed root is
     * published (reachable from the selected pointer) charges that root's bytes, so the session
     * reclaim covers it; any other reservation is released (its data was never charged).
     * Idempotent: an existing tombstone is kept. The session directory is synced first, so a
     * pointer switch of unknown durability becomes known.
     */
    fun tombstoneSession(sessionId: String) = synchronized(mutex) {
        val directory = files.child("sessions", safe(sessionId))
        files.ensureDirectory(directory)
        faults.at(DurableStoreFaultPointV2.TOMBSTONE_RECORD)
        val tombstone = path(directory, "tombstone")
        if (!files.isFile(tombstone)) {
            writeExclusive(tombstone, "deleted\n".toByteArray(), DurableStoreFaultPointV2.TOMBSTONE_RECORD)
        }
        faults.at(DurableStoreFaultPointV2.TOMBSTONE_DIRECTORY_SYNC)
        files.syncDirectory(directory)
        for (slot in listOf("root-A.ptr", "root-B.ptr")) {
            val file = path(directory, slot)
            if (!files.isFile(file)) continue
            files.readDescriptor(file) { rootMetadata.pointer(it::read) }?.let { uncertainRootHashes.remove(it.hash) }
        }
        val published = publishedRootHashes(directory)
        files.walk(directory).filter { it.name == "accepted.properties" }.toList().forEach { file ->
            val token = readProperties(file).getProperty("reservationToken") ?: return@forEach
            val reservation = budget.reservation(token) ?: return@forEach
            val bytes = publishedRecordBytes(directory, requireNotNull(file.parentFile), published)
            if (bytes != null) budget.settle(reservation, minOf(bytes, reservation.bytes)) else budget.release(reservation)
        }
    }

    /**
     * Deletes one session's photos after the app deleted the session: the tombstone fence, then
     * the bytes of the session's published commits leave the ledger exactly once (verified
     * reclaim `verified-<id>.reclaim`, id bound to the session), then its attempts, roots,
     * pointers and blobs are removed. The tombstone and the reclaim marker stay, so a repeated
     * call after a crash at any step finishes the same deletion and never reclaims twice.
     * Returns the bytes reclaimed by this call.
     */
    fun deleteSession(sessionId: String): Long = synchronized(mutex) {
        require(sessionId.isNotEmpty()) { "sessionId is required" }
        tombstoneSession(sessionId)
        val directory = files.child("sessions", safe(sessionId))
        val reclaimId = sessionReclaimId(sessionId)
        var reclaimed = 0L
        if (!budget.hasVerifiedReclaim(reclaimId)) {
            val published = publishedRootHashes(directory)
            var total = 0L
            files.walk(directory).filter { it.name == "accepted.properties" }.toList().forEach { file ->
                total = Math.addExact(total, publishedRecordBytes(directory, requireNotNull(file.parentFile), published) ?: 0L)
            }
            reclaimed = minOf(total, budget.committedBytes())
            if (reclaimed > 0L) budget.reclaimVerifiedOnce(reclaimId, reclaimed)
        }
        files.list(directory).filter { it.name != "tombstone" }.forEach {
            deleteTree(it, DurableStoreFaultPointV2.DELETE_RECLAIM)
        }
        reclaimed
    }

    /** Every root reachable from the selected pointer through `previous`. */
    private fun publishedRootHashes(location: File): Set<String> {
        val out = linkedSetOf<String>()
        var hash = selectedRoot(location)?.rootHash
        while (hash != null && hash !in out) {
            out += hash
            hash = rootPrevious(location, hash)
        }
        return out
    }

    /** Bytes of the published root an attempt's committed receipt names (its charge); null without one. */
    private fun publishedRecordBytes(location: File, attempt: File, published: Set<String>): Long? {
        val receipt = path(attempt, "receipt.properties")
        if (!files.isFile(receipt)) return null
        val bytes = files.readBytes(receipt)
        if (!isFinishedReceipt(bytes)) return null
        val values = parseProperties(bytes)
        if (values.getProperty("kind") != CaptureTerminalKind.COMMITTED_PICTURE.name) return null
        val rootHash = values.getProperty("rootHash")?.takeIf { it in published } ?: return null
        return readRootMetadata(location, rootHash)?.components
            ?.fold(0L) { total, component -> Math.addExact(total, component.length) }
    }

    /** Reconciles prepared staging and ensures only receipt/pointer backed results are visible. */
    fun recover() = synchronized(mutex) {
        faults.at(DurableStoreFaultPointV2.RECOVERY)
        files.list(sessionRoot).filter(File::isDirectory).forEach { session ->
            files.walk(session).filter { it.name == "accepted.properties" }.forEach { accepted ->
                val acceptedBytes = files.readBytes(accepted)
                val acceptedValues = parseProperties(acceptedBytes)
                // An unfinished acceptance preceded no exposure; reconciliation releases its reservation.
                if (!isFinishedAcceptedRecord(acceptedBytes, acceptedValues)) return@forEach
                // Same rules as startup projection and reconciliation. A committed receipt outside
                // the retained graph is unknown: staging and liability remain for exact query/rebase.
                repairAcceptedCandidate(session, accepted)
            }
        }
    }

    /**
     * Bounded startup projection for the per-view owner after durable repair.
     * The durable index has one spare entry so a crash between indexing and
     * acceptance cannot evict either of the two prior valid candidates.
     */
    internal fun recoverAndProject(
        limit: Int = 2,
        shouldContinue: () -> Boolean = { true },
        onCandidateExamined: () -> Unit = {},
    ): List<RecoveryProjectionV2> = synchronized(mutex) {
        require(limit in 1..2)
        if (!files.isFile(recoveryIndex) || !shouldContinue()) return@synchronized emptyList()
        val index = readProperties(recoveryIndex)
        val count = index.getProperty("count")?.toIntOrNull()?.coerceIn(0, RECOVERY_INDEX_CAPACITY) ?: 0
        val result = mutableListOf<RecoveryProjectionV2>()
        val validEntries = mutableListOf<Pair<String, String>>()
        var examinedSlots = 0
        for (slot in 0 until count) {
            if (!shouldContinue() || result.size == limit) break
            examinedSlots++
            onCandidateExamined()
            val sessionHash = index.getProperty("entry.$slot.session") ?: continue
            val attemptHash = index.getProperty("entry.$slot.attempt") ?: continue
            if (!sessionHash.matches(SAFE_HASH) || !attemptHash.matches(SAFE_HASH)) continue
            val session = files.child("sessions", sessionHash)
            // A deleted session projects nothing, also while an interrupted deletion is unfinished.
            if (tombstone(session) != null) continue
            val accepted = path(session, "attempts", attemptHash, "accepted.properties")
            if (!files.isFile(accepted)) continue
            val acceptedBytes = files.readBytes(accepted)
            // The accepted record is created under its final name and then written, after its
            // index entry: a record cut short is an acceptance that never finished, and no exposure
            // can precede it. It is neither repaired nor projected and leaves the index; binding-open
            // reconciliation releases its reservation.
            if (!isFinishedAcceptedRecord(acceptedBytes, parseProperties(acceptedBytes))) continue
            validEntries += sessionHash to attemptHash
            repairAcceptedCandidate(session, accepted)
            result += run {
                val acceptedValues = readProperties(accepted)
                val attemptId = acceptedValues.getProperty("attemptId")
                    ?: throw DurableStoreConflictV2("Recovered acceptance has no attempt identity")
                val receiptPath = path(requireNotNull(accepted.parentFile), "receipt.properties")
                if (!files.isFile(receiptPath)) {
                    RecoveryProjectionV2(attemptId, null, reason = "recovered-accepted-unknown", recoveryContext = acceptedValues.recoveryContext())
                } else {
                    val value = readProperties(receiptPath)
                    if (value.getProperty("kind") == CaptureTerminalKind.COMMITTED_PICTURE.name &&
                        value.getProperty("rootHash") in retainedRootHashes(session)
                    ) {
                        val exactIdentity = acceptedValues.exactIdentity()
                        val verified = validateCommittedReceipt(session, exactIdentity, value)
                        val verifiedTerminal = exactIdentity?.let { identity ->
                            if (safe(identity.lifecycleCut.sessionId) != sessionHash || safe(identity.commitId) != attemptHash) {
                                throw DurableStoreConflictV2("Recovered exact identity conflicts with durable location")
                            }
                            buildCommittedTerminal(identity, value, verified)
                        }
                        RecoveryProjectionV2(
                            attemptId,
                            CaptureTerminalKind.COMMITTED_PICTURE,
                            verified.captureId,
                            verified.revision,
                            value.getProperty("rootHash"),
                            "recovered-committed",
                            acceptedValues.recoveryContext(),
                            verified.jpegSizeBytes,
                            verified.dngSizeBytes,
                            verified.qualityAssessment, verified.qualityAssessmentHash, verifiedTerminal,
                        )
                    } else if (value.getProperty("kind") == CaptureTerminalKind.ABANDONED_ATTEMPT.name) {
                        RecoveryProjectionV2(
                            attemptId,
                            CaptureTerminalKind.ABANDONED_ATTEMPT,
                            reason = value.getProperty("reason", "recovered-abandoned"),
                            recoveryContext = acceptedValues.recoveryContext(),
                        )
                    } else {
                        RecoveryProjectionV2(attemptId, null, reason = "recovered-receipt-unknown", recoveryContext = acceptedValues.recoveryContext())
                    }
                }
            }
        }
        if (examinedSlots == count && validEntries.size != count && shouldContinue()) {
            writeRecoveryIndex(validEntries)
        }
        result
    }

    /**
     * Startup recovery at binding open: the bounded projection, then reservation reconciliation
     * before the first admission.
     */
    internal fun recoverAtOpen(
        shouldContinue: () -> Boolean = { true },
        log: (String) -> Unit = ::logStoreWarning,
    ): List<RecoveryProjectionV2> {
        if (shouldContinue()) adoptUnpublishedRoots(log)
        return recoverAndProject(shouldContinue = shouldContinue).also {
            if (shouldContinue()) reconcileReservations(log)
        }
    }

    /**
     * Pointer re-publication for every live capture reservation whose complete committed receipt
     * extends the selected root exactly; runs before projection. Never throws.
     */
    internal fun adoptUnpublishedRoots(log: (String) -> Unit = ::logStoreWarning): Int = synchronized(mutex) {
        val live = try { budget.liveReservations() } catch (error: Exception) {
            log("root adoption skipped: $error")
            return@synchronized 0
        }
        var adopted = 0
        for (reservation in live) {
            try {
                val (session, accepted) = acceptedRecordFor(reservation) ?: continue
                if (adoptUnpublishedRoot(session, accepted)) adopted++
            } catch (error: Exception) {
                log("reservation ${reservation.owner} root not adopted: $error")
            }
        }
        adopted
    }

    /** The finished accepted record that names [reservation], if any. */
    private fun acceptedRecordFor(reservation: StorageBudgetReservationV2): Pair<File, File>? {
        val parts = reservation.owner.split(':')
        if (parts.size != 3 || parts[0] != "capture" || !parts[1].matches(SAFE_HASH) || !parts[2].matches(SAFE_HASH)) return null
        val session = files.child("sessions", parts[1])
        val accepted = path(session, "attempts", parts[2], "accepted.properties")
        if (!files.isFile(accepted)) return null
        val bytes = files.readBytes(accepted)
        val values = parseProperties(bytes)
        if (!isFinishedAcceptedRecord(bytes, values) || values.getProperty("reservationToken") != reservation.token) return null
        return session to accepted
    }

    /**
     * Reservation-driven reconciliation: every live reservation names its attempt
     * (`capture:<session>:<commit>`). Without a finished accepted record naming it, the reservation
     * is released; otherwise the attempt is repaired by the recovery rules (no receipt: abandoned
     * and released; committed receipt in the retained graph: settled; partial receipt or root
     * outside the retained graph: the liability stays). Committed roots, receipts and assets are
     * never deleted. A failure on one reservation is logged and the next one goes on; it never
     * throws. Returns the number of reservations closed.
     */
    internal fun reconcileReservations(log: (String) -> Unit = ::logStoreWarning): Int = synchronized(mutex) {
        val live = try { budget.liveReservations() } catch (error: Exception) {
            log("reservation reconciliation skipped: $error")
            return@synchronized 0
        }
        var closed = 0
        for (reservation in live) {
            try {
                if (reconcileLocked(reservation)) closed++
            } catch (error: Exception) {
                log("reservation ${reservation.owner} not reconciled: $error")
            }
        }
        closed
    }

    private fun reconcileLocked(reservation: StorageBudgetReservationV2): Boolean {
        val parts = reservation.owner.split(':')
        if (parts.size != 3 || parts[0] != "capture" || !parts[1].matches(SAFE_HASH) || !parts[2].matches(SAFE_HASH)) {
            throw DurableStoreConflictV2("Reservation owner is not a capture attempt")
        }
        val (session, accepted) = acceptedRecordFor(reservation) ?: return budget.release(reservation)
        repairAcceptedCandidate(session, accepted)
        return budget.reservation(reservation.token) == null
    }

    /** Schema-qualified exact identity; only written at new acceptance, never inferred for old records. */
    private fun Properties.putExactIdentity(identity: CaptureAttemptIdentity) {
        val bytes = java.io.ByteArrayOutputStream()
        java.io.DataOutputStream(bytes).use { output ->
            output.writeUTF(identity.attemptId); output.writeUTF(identity.commitId); output.writeLong(identity.attemptOrdinal)
            val cut = identity.lifecycleCut
            output.writeUTF(cut.sessionId); output.writeLong(cut.sessionGeneration)
            output.writeUTF(cut.groupId); output.writeLong(cut.groupGeneration)
            output.writeUTF(cut.arSessionId); output.writeUTF(cut.viewId); output.writeLong(cut.viewGeneration)
            output.writeUTF(cut.bindingToken); output.writeLong(cut.lifecycleSequence); output.writeLong(cut.operationGeneration)
        }
        setProperty("acceptedIdentityVersion", "1")
        setProperty("acceptedIdentity", java.util.Base64.getEncoder().encodeToString(bytes.toByteArray()))
    }

    private fun Properties.exactIdentity(): CaptureAttemptIdentity? {
        val version = getProperty("acceptedIdentityVersion")
        val encoded = getProperty("acceptedIdentity")
        if (version == null && encoded == null) return null // Explicit legacy branch.
        try {
            require(version == "1" && encoded != null && encoded.length <= NativeCaptureReservationBoundsV2.ACCEPTED_RECORD_BYTES)
            val identity = java.io.DataInputStream(java.util.Base64.getDecoder().decode(encoded).inputStream()).use { input ->
                val attemptId = input.readUTF(); val commitId = input.readUTF(); val ordinal = input.readLong()
                val cut = CaptureLifecycleCut(input.readUTF(), input.readLong(), input.readUTF(), input.readLong(),
                    input.readUTF(), input.readUTF(), input.readLong(), input.readUTF(), input.readLong(), input.readLong())
                require(input.read() == -1)
                CaptureAttemptIdentity(attemptId, commitId, ordinal, cut)
            }
            require(identity.attemptId == getProperty("attemptId") && identity.commitId == getProperty("commitId") &&
                identity.lifecycleCut.sessionId == getProperty("session"))
            return identity
        } catch (error: Exception) {
            throw DurableStoreConflictV2("Accepted exact identity is missing, malformed or conflicting")
        }
    }

    private fun requireExactAcceptedIdentity(location: File, identity: CaptureAttemptIdentity) {
        val accepted = readProperties(acceptedFile(location, identity))
        if (accepted.exactIdentity() != identity) throw DurableStoreConflictV2("Assessed capture requires exact durable identity")
        val actualBytes = propertiesBytes(accepted).size.toLong()
        require(actualBytes <= NativeCaptureReservationBoundsV2.ACCEPTED_RECORD_BYTES &&
            accepted.getProperty("reservationBytes").toLong() >= actualBytes) { "Accepted metadata exceeds funded bytes" }
    }

    private fun Properties.putRecoveryContext(value: NativeCaptureRecoveryContextV2) {
        setProperty("recovery.sessionId", value.sessionId); setProperty("recovery.groupId", value.groupId)
        setProperty("recovery.groupIndex", value.groupIndex.toString()); setProperty("recovery.groupGeneration", value.groupGeneration.toString())
        setProperty("recovery.trigger", value.trigger); setProperty("recovery.requestedAtMs", value.requestedAtMs.toString())
        setProperty("recovery.coverageRevision", value.coverageRevision.toString()); setProperty("recovery.timestampMs", value.timestampMs.toString())
        setProperty("recovery.position", value.position.joinToString(",")); setProperty("recovery.rotation", value.rotation.joinToString(","))
        setProperty("recovery.viewMatrix", value.viewMatrix.joinToString(",")); setProperty("recovery.projectionMatrix", value.projectionMatrix.joinToString(","))
        setProperty("recovery.groupFromWorld", value.groupFromWorld.joinToString(",")); setProperty("recovery.worldFromGroup", value.worldFromGroup.joinToString(","))
    }

    private fun Properties.recoveryContext(): NativeCaptureRecoveryContextV2? {
        val sessionId = getProperty("recovery.sessionId") ?: return null
        fun long(name: String) = getProperty("recovery.$name")?.toLongOrNull()
            ?: throw DurableStoreConflictV2("Recovered context has invalid $name")
        fun doubles(name: String, size: Int): List<Double> = getProperty("recovery.$name")?.split(',')?.map(String::toDouble).also {
            if (it?.size != size) throw DurableStoreConflictV2("Recovered context has invalid $name")
        }!!
        return NativeCaptureRecoveryContextV2(
            sessionId, getProperty("recovery.groupId") ?: throw DurableStoreConflictV2("Recovered context has no group"),
            long("groupIndex"), long("groupGeneration"), getProperty("recovery.trigger") ?: throw DurableStoreConflictV2("Recovered context has no trigger"),
            long("requestedAtMs"), long("coverageRevision"), long("timestampMs"), doubles("position", 3), doubles("rotation", 4),
            doubles("viewMatrix", 16), doubles("projectionMatrix", 16), doubles("groupFromWorld", 16), doubles("worldFromGroup", 16),
        )
    }

    private fun repairAcceptedCandidate(session: File, accepted: File) {
        val attempt = requireNotNull(accepted.parentFile)
        val receipt = path(attempt, "receipt.properties")
        val acceptedValues = readProperties(accepted)
        val receiptBytes = if (files.isFile(receipt)) files.readBytes(receipt) else null
        // The pointer is published only after its receipt is durable, so an unfinished receipt
        // (an earlier in-place write cut short) proves that no pointer names its root.
        val unfinished = receiptBytes != null && !isFinishedReceipt(receiptBytes)
        if (receiptBytes == null || unfinished) {
            val abandoned = Properties().apply {
                setProperty("kind", CaptureTerminalKind.ABANDONED_ATTEMPT.name)
                setProperty("requestHash", acceptedValues.getProperty("acceptedHash"))
                setProperty("receiptHash", receiptHash(acceptedValues.getProperty("acceptedHash"), "abandoned"))
                setProperty("rootHash", "abandoned")
                setProperty("reason", if (unfinished) "recovered-unfinished-receipt" else "recovered-proven-absent")
                setProperty("requestBinding", ABANDONMENT_BINDING_RECOVERED)
            }
            writeReceipt(receipt, abandoned, DurableStoreFaultPointV2.RECEIPT_REPLACE, replaceUnfinished = unfinished)
            deleteTree(path(attempt, "staging"), DurableStoreFaultPointV2.ABANDONMENT_CLEANUP)
            acceptedValues.getProperty("attemptId")?.let {
                deleteTree(path(session, "assets", safe(it)), DurableStoreFaultPointV2.ABANDONMENT_CLEANUP)
            }
            acceptedValues.getProperty("reservationToken")?.let { budget.reservation(it)?.let(budget::release) }
            return
        }
        val receiptValues = parseProperties(receiptBytes)
        if (receiptValues.getProperty("kind") == CaptureTerminalKind.COMMITTED_PICTURE.name &&
            receiptValues.getProperty("rootHash") in retainedRootHashes(session)
        ) {
            acceptedValues.getProperty("reservationToken")?.let { token ->
                budget.reservation(token)?.let { reservation ->
                    budget.settle(reservation, committedComponentBytes(session, receiptValues.getProperty("rootHash")))
                }
            }
            deleteTree(path(attempt, "staging"), DurableStoreFaultPointV2.DELETE_RECLAIM)
        }
    }

    /**
     * Re-publishes the pointer of a complete committed receipt whose pointer slot was lost
     * (rename not durable before a restart) when its root extends the selected root exactly:
     * valid root, revision one above the selected one and previous equal to the selected hash.
     * Any other lineage stays unknown. Returns true when a pointer was published.
     */
    private fun adoptUnpublishedRoot(session: File, accepted: File): Boolean {
        // A deleted session never gains a published root after its reclaim.
        if (tombstone(session) != null) return false
        val receipt = path(requireNotNull(accepted.parentFile), "receipt.properties")
        if (!files.isFile(receipt)) return false
        val receiptBytes = files.readBytes(receipt)
        if (!isFinishedReceipt(receiptBytes)) return false
        val values = parseProperties(receiptBytes)
        val rootHash = values.getProperty("rootHash")
        if (values.getProperty("kind") != CaptureTerminalKind.COMMITTED_PICTURE.name ||
            rootHash in retainedRootHashes(session)) return false
        val revision = values.getProperty("revision")?.toLongOrNull() ?: return false
        val commitId = readProperties(accepted).getProperty("commitId") ?: return false
        if (!validRootHash(session, rootHash, revision, 0)) return false
        val selected = selectedRoot(session)
        if (revision != (selected?.revision ?: 0L) + 1L || rootPrevious(session, rootHash) != selected?.rootHash) return false
        if (values.getProperty("receiptHash") != receiptHash(values.getProperty("requestHash"), rootHash)) return false
        publishPointer(session, RootPointer(revision, rootHash, commitId), selected)
        return true
    }

    private fun recordRecoveryCandidate(session: File, attempt: File) {
        val sessionHash = session.name
        val attemptHash = attempt.name
        val existing = if (files.isFile(recoveryIndex)) readProperties(recoveryIndex) else Properties()
        val entries = buildList {
            add(sessionHash to attemptHash)
            val count = existing.getProperty("count")?.toIntOrNull()?.coerceIn(0, RECOVERY_INDEX_CAPACITY) ?: 0
            for (slot in 0 until count) {
                val priorSession = existing.getProperty("entry.$slot.session") ?: continue
                val priorAttempt = existing.getProperty("entry.$slot.attempt") ?: continue
                if (priorSession to priorAttempt != sessionHash to attemptHash) add(priorSession to priorAttempt)
            }
        }.take(RECOVERY_INDEX_CAPACITY)
        writeRecoveryIndex(entries)
    }

    private fun writeRecoveryIndex(entries: List<Pair<String, String>>) {
        val updated = Properties().apply {
            setProperty("schema", "bounded-recovery-index-v1")
            setProperty("count", entries.size.toString())
            entries.forEachIndexed { slot, (indexedSession, indexedAttempt) ->
                setProperty("entry.$slot.session", indexedSession)
                setProperty("entry.$slot.attempt", indexedAttempt)
            }
        }
        files.atomicReplace(
            recoveryIndex,
            buildString {
                updated.stringPropertyNames().sorted().forEach {
                    append(it).append('=').append(updated.getProperty(it)).append('\n')
                }
            }.toByteArray(),
            null,
            null,
        )
    }

    private fun location(identity: CaptureAttemptIdentity): File = files.child("sessions", safe(identity.lifecycleCut.sessionId)).also(files::ensureDirectory)
    private fun attemptDirectory(location: File, identity: CaptureAttemptIdentity): File =
        path(location, "attempts", safe(identity.commitId)).also(files::ensureDirectory)
    private fun tombstone(location: File): File? = path(location, "tombstone").takeIf(files::isFile)
    private fun acceptedFile(location: File, identity: CaptureAttemptIdentity) = path(attemptDirectory(location, identity), "accepted.properties")
    private fun receiptFile(location: File, identity: CaptureAttemptIdentity) = path(attemptDirectory(location, identity), "receipt.properties")
    private fun assetDirectory(location: File, identity: CaptureAttemptIdentity) = path(location, "assets", safe(identity.attemptId))
    private data class VerifiedCommittedReceiptV2(
        val captureId: String?,
        val revision: Long?,
        val jpegSizeBytes: Long?,
        val dngSizeBytes: Long?,
        val qualityAssessment: CaptureQualityAssessmentV1?,
        val qualityAssessmentHash: String?,
    )

    private fun receipt(location: File, identity: CaptureAttemptIdentity): CaptureReceipt? {
        val file = receiptFile(location, identity); if (!files.isFile(file)) return null
        val value = readProperties(file)
        val kind = value.getProperty("kind") ?: return null
        if (kind == CaptureTerminalKind.COMMITTED_PICTURE.name && value.getProperty("rootHash") !in retainedRootHashes(location)) return null
        val verified = if (kind == CaptureTerminalKind.COMMITTED_PICTURE.name) validateCommittedReceipt(location, identity, value) else null
        val terminal = if (kind == CaptureTerminalKind.COMMITTED_PICTURE.name) buildCommittedTerminal(identity, value, requireNotNull(verified))
        else CaptureTerminal(CaptureTerminalKind.ABANDONED_ATTEMPT, identity, value.getProperty("requestHash"), value.getProperty("reason", "abandoned"))
        return CaptureReceipt(identity, if (terminal.kind == CaptureTerminalKind.COMMITTED_PICTURE) CaptureAttemptPhase.COMMITTED_PICTURE else CaptureAttemptPhase.ABANDONED_ATTEMPT, value.getProperty("requestHash"), value.getProperty("receiptHash"), true, terminal)
    }

    private fun buildCommittedTerminal(
        identity: CaptureAttemptIdentity,
        receipt: Properties,
        verified: VerifiedCommittedReceiptV2,
    ) = CaptureTerminal(
        CaptureTerminalKind.COMMITTED_PICTURE,
        identity,
        receipt.getProperty("requestHash"),
        "committed",
        verified.captureId,
        verified.revision,
        receipt.getProperty("rootHash"),
        verified.jpegSizeBytes,
        verified.dngSizeBytes,
        verified.qualityAssessment,
        verified.qualityAssessmentHash,
    )

    /** Schema-6 receipt, accepted identity and root must agree before authority is constructed. */
    private fun validateCommittedReceipt(
        location: File,
        identity: CaptureAttemptIdentity?,
        receipt: Properties,
    ): VerifiedCommittedReceiptV2 {
        val rootHash = receipt.getProperty("rootHash") ?: throw DurableStoreConflictV2("Committed receipt has no root")
        val metadata = readRootMetadata(location, rootHash) ?: throw DurableStoreConflictV2("Committed root is invalid")
        if (metadata.schema == "5") {
            if (receipt.stringPropertyNames().any { it.startsWith("quality") }) {
                throw DurableStoreConflictV2("Legacy receipt cannot carry assessed quality")
            }
            return VerifiedCommittedReceiptV2(
                receipt.getProperty("captureId"),
                receipt.getProperty("revision").toLong(),
                terminalComponentSize(location, receipt, CaptureComponentKind.JPEG),
                terminalComponentSize(location, receipt, CaptureComponentKind.DNG),
                null,
                null,
            )
        }
        if (metadata.schema != "6") throw DurableStoreConflictV2("Unknown quality root schema")
        val fields = receipt.stringPropertyNames().associateWith { receipt.getProperty(it) }
        val assessment = CaptureQualityAssessmentV1.fromRootFields(fields)
            ?: throw DurableStoreConflictV2("Committed receipt has missing or malformed quality")
        val assessmentHash = receipt.getProperty("qualityAssessmentHash")
        val requestHash = receipt.getProperty("requestHash")
            ?: throw DurableStoreConflictV2("Committed assessed receipt has no request hash")
        if (assessment != metadata.qualityAssessment || assessmentHash != metadata.qualityAssessmentHash ||
            requestHash != metadata.request ||
            receipt.getProperty("receiptHash") != receiptHash(requestHash, rootHash)) {
            throw DurableStoreConflictV2("Committed receipt conflicts with bound quality root")
        }
        val exactIdentity = identity ?: throw DurableStoreConflictV2("Assessed recovery requires durable exact identity")
        requireExactAcceptedIdentity(location, exactIdentity)
        val expectedCaptureId = safe(exactIdentity.attemptId)
        if (receipt.getProperty("captureId") != expectedCaptureId) {
            throw DurableStoreConflictV2("Assessed receipt capture identity conflicts with accepted attempt")
        }
        val revision = receipt.getProperty("revision")?.toLongOrNull()
        if (revision == null || revision <= 0L || revision != metadata.revision) {
            throw DurableStoreConflictV2("Assessed receipt revision conflicts with verified root")
        }
        fun exactComponentSize(kind: CaptureComponentKind, property: String): Long {
            val descriptor = metadata.components.singleOrNull { it.kind == kind.name }
                ?: throw DurableStoreConflictV2("Assessed root has no required ${kind.name} component")
            val size = receipt.getProperty(property)?.toLongOrNull()
                ?: throw DurableStoreConflictV2("Assessed receipt has missing or malformed $property")
            if (size <= 0L || size != descriptor.length) {
                throw DurableStoreConflictV2("Assessed receipt $property conflicts with verified root")
            }
            return size
        }
        val jpegSize = exactComponentSize(CaptureComponentKind.JPEG, "jpegSizeBytes")
        val dngDescriptor = metadata.components.singleOrNull { it.kind == CaptureComponentKind.DNG.name }
        val dngProperty = receipt.getProperty("dngSizeBytes")
        val dngSize = if (dngDescriptor == null) {
            if (dngProperty != null) throw DurableStoreConflictV2("Assessed receipt has unexpected DNG size")
            null
        } else {
            exactComponentSize(CaptureComponentKind.DNG, "dngSizeBytes")
        }
        return VerifiedCommittedReceiptV2(
            expectedCaptureId,
            revision,
            jpegSize,
            dngSize,
            assessment,
            requireNotNull(assessmentHash),
        )
    }

    /** Hard bound on new assessed stream replay, including a one-byte overflow probe. */
    private fun boundedAssessmentStream(input: InputStream, maximumBytes: Long): InputStream = object : InputStream() {
        private var remaining = maximumBytes.also { require(it >= 0L) }
        override fun read(): Int {
            val value = input.read()
            if (value < 0) return value
            check(remaining > 0L) { "Component bytes exceed assessment replay bound" }
            remaining--
            return value
        }
        override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
            if (length == 0) return 0
            if (remaining == 0L) return read()
            val received = input.read(bytes, offset, minOf(length.toLong(), remaining).toInt())
            if (received > 0) remaining -= received.toLong()
            return received
        }
        override fun close() = input.close()
    }

    private fun acceptedReceipt(attempt: CaptureAcceptedAttempt) = CaptureReceipt(attempt.identity, CaptureAttemptPhase.RESERVED_ACCEPTED, acceptedHash(attempt), attempt.acceptedReceiptHash.hex(), true)
    /** Legacy receipts omitted DNG sizes; the retained hash-validated root still records the exact set. */
    private fun terminalComponentSize(location: File, receipt: Properties, kind: CaptureComponentKind): Long? {
        val property = if (kind == CaptureComponentKind.JPEG) "jpegSizeBytes" else "dngSizeBytes"
        receipt.getProperty(property)?.let { return it.toLong().also { size -> require(size > 0) } }
        val rootHash = receipt.getProperty("rootHash") ?: return null
        return readRootMetadata(location, rootHash)?.components
            ?.singleOrNull { it.kind == kind.name }?.length?.also { require(it > 0) }
    }
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
            request.qualityAssessment?.let { assessment ->
                text("capture-quality-assessment-v1")
                val qualityBytes = assessment.canonicalBytes()
                output.writeInt(qualityBytes.size); output.write(qualityBytes)
            }
        }
        return sha256(bytes.toByteArray()).hex()
    }
    private fun reservationOwner(identity: CaptureAttemptIdentity) = "capture:${safe(identity.lifecycleCut.sessionId)}:${safe(identity.commitId)}"
    private fun receiptHash(requestHash: String, rootHash: String) = sha256("$requestHash|$rootHash".toByteArray()).hex()
    private fun rootRecord(request: CaptureCommitRequest, requestHash: String, revision: Long, staged: List<Staged>, prior: RootPointer?) = buildString {
        append("schema=${if (request.qualityAssessment == null) NativeStoreFormatVersions.PHOTO_ROOT_SCHEMA_PLAIN else NativeStoreFormatVersions.PHOTO_ROOT_SCHEMA_ASSESSED}\nrevision=$revision\nrequest=$requestHash\nprevious=${prior?.rootHash ?: "-"}\nsecondPrevious=${prior?.previousHash ?: "-"}\n")
        staged.sortedBy { it.kind.ordinal }.forEach { append("component=${it.kind}:${it.descriptor.byteLength}:${it.descriptor.sha256.hex()}\n") }
        request.qualityAssessment?.let { assessment ->
            append("exposureTimestampNanoseconds=${request.exposureTimestampNanoseconds}\n")
            assessment.rootFields().forEach { (key, value) -> append("$key=$value\n") }
            append("qualityAssessmentHash=${CaptureQualityAssessmentV1.boundHash(requestHash, assessment)}\n")
        }
    }.toByteArray(StandardCharsets.UTF_8)
    private fun receiptProperties(receipt: CaptureReceipt, rootHash: String) = Properties().apply { setProperty("kind", receipt.terminal!!.kind.name); setProperty("requestHash", receipt.requestHash); setProperty("receiptHash", receipt.receiptHash); setProperty("rootHash", rootHash); setProperty("reason", receipt.terminal.reason); receipt.terminal.captureId?.let { setProperty("captureId", it) }; receipt.terminal.captureRevision?.let { setProperty("revision", it.toString()) }; receipt.terminal.jpegSizeBytes?.let { setProperty("jpegSizeBytes", it.toString()) }; receipt.terminal.dngSizeBytes?.let { setProperty("dngSizeBytes", it.toString()) }; receipt.terminal.qualityAssessment?.let { assessment -> assessment.rootFields().forEach { (key, value) -> setProperty(key, value) }; setProperty("qualityAssessmentHash", requireNotNull(receipt.terminal.qualityAssessmentHash)) } }

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
    private fun selectedRoot(location: File, includeUncertain: Boolean = false): RootPointer? {
        var selected: RootPointer? = null
        for (slot in 0..1) {
            val file = path(location, if (slot == 0) "root-A.ptr" else "root-B.ptr")
            if (!files.isFile(file)) continue
            val pointer = files.readDescriptor(file) { rootMetadata.pointer(it::read) } ?: continue
            if (!includeUncertain && pointer.hash in uncertainRootHashes) continue
            val candidate = RootPointer(pointer.revision, pointer.hash, pointer.commit,
                if (slot == 0) "A" else "B", rootPrevious(location, pointer.hash))
            if (!validRoot(location, candidate)) continue
            val prior = selected
            if (prior != null && prior.revision == candidate.revision && prior.rootHash != candidate.rootHash) {
                throw DurableStoreConflictV2("Schema-5 root fork")
            }
            if (prior == null || candidate.revision > prior.revision) selected = candidate
        }
        return selected
    }
    private fun readRootMetadata(location: File, hash: String): CaptureRootMetadataV2? {
        if (!CaptureRootMetadataReaderV2.isHash(hash)) return null
        val file = path(location, "objects", "$hash.root")
        if (!files.isFile(file)) return null
        return files.readDescriptor(file) { rootMetadata.root(hash, it::read) }
    }
    private fun rootPrevious(location: File, hash: String): String? =
        readRootMetadata(location, hash)?.previous?.takeUnless { it == "-" }
    private fun validRoot(location: File, pointer: RootPointer): Boolean {
        return validRootHash(location, pointer.rootHash, pointer.revision, 0)
    }
    private fun validRootHash(location: File, hash: String, revision: Long, depth: Int): Boolean {
        if (depth > 2) return false
        val values = readRootMetadata(location, hash) ?: return false
        if (values.schema !in NativeStoreFormatVersions.PHOTO_ROOT_SCHEMAS_READABLE || values.revision != revision || !CaptureRootMetadataReaderV2.isHash(values.request)) return false
        val previous = values.previous
        val second = values.secondPrevious
        if (depth == 2) return true
        if (revision == 1L) return previous == "-" && second == "-"
        if (previous == null || previous == "-" || !validRootHash(location, previous, revision - 1, depth + 1)) return false
        if (depth == 0 && revision >= 3 && second != rootPrevious(location, previous)) return false
        return true
    }
    /** Publishes [pointer] into the slot that does not hold [prior], the root it supersedes. */
    private fun publishPointer(location: File, pointer: RootPointer, prior: RootPointer?) {
        val target = path(location, if (prior?.slot == "A") "root-B.ptr" else "root-A.ptr")
        try {
            writeAtomic(target, "${pointer.revision}\n${pointer.rootHash}\n${pointer.commitId}\n".toByteArray())
            uncertainRootHashes.remove(pointer.rootHash)
            // The parent fsync also made every earlier rename in this directory durable.
            for (slot in listOf("root-A.ptr", "root-B.ptr")) {
                val file = path(location, slot)
                if (!files.isFile(file)) continue
                files.readDescriptor(file) { rootMetadata.pointer(it::read) }?.let { uncertainRootHashes.remove(it.hash) }
            }
        } catch (error: PointerDirectorySyncUnknownV2) {
            uncertainRootHashes += pointer.rootHash
            throw error
        }
    }
    private fun moveImmutable(from: File, target: File, descriptor: CaptureComponentDescriptor) { if (files.isFile(target)) { val measured = files.digestAndLength(target); check(measured.first == descriptor.byteLength && measured.second.hex() == descriptor.sha256.hex()) { "Immutable asset conflict" }; files.delete(from, DurableStoreFaultPointV2.DELETE_RECLAIM); return }; files.moveAtomic(from, target) }
    private fun propertiesBytes(properties: Properties) = buildString { properties.stringPropertyNames().sorted().forEach { append(it).append('=').append(properties.getProperty(it)).append('\n') } }.toByteArray()
    private fun writePropertiesExclusive(file: File, properties: Properties, point: DurableStoreFaultPointV2 = DurableStoreFaultPointV2.ACCEPTED_RECORD) = writeExclusive(file, propertiesBytes(properties), point)
    private fun readProperties(file: File) = parseProperties(files.readBytes(file))
    /**
     * Receipts are published whole by rename; the final bytes equal the earlier in-place write.
     * Only recovery replaces an existing (unfinished) receipt.
     */
    private fun writeReceipt(
        file: File,
        properties: Properties,
        point: DurableStoreFaultPointV2,
        replaceUnfinished: Boolean = false,
    ) {
        if (files.isFile(file) && !replaceUnfinished) throw DurableStoreConflictV2("Receipt already exists")
        files.atomicReplace(file, propertiesBytes(properties), point, null)
    }
    /** A receipt written whole ends with a newline and carries its four identity keys. */
    private fun isFinishedReceipt(bytes: ByteArray): Boolean {
        if (bytes.isEmpty() || bytes.last() != '\n'.code.toByte()) return false
        val values = parseProperties(bytes)
        return listOf("kind", "requestHash", "receiptHash", "rootHash").all { values.getProperty(it) != null }
    }
    private fun parseProperties(bytes: ByteArray) = Properties().apply { bytes.inputStream().use(::load) }
    /**
     * An accepted record is written whole as sorted `key=value` lines, so a finished one ends with
     * a newline and carries `session`, its last key; anything else is a write cut short.
     */
    private fun isFinishedAcceptedRecord(bytes: ByteArray, values: Properties) =
        bytes.isNotEmpty() && bytes.last() == '\n'.code.toByte() && values.getProperty("session") != null
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
    private fun committedComponentBytes(location: File, rootHash: String): Long =
        requireNotNull(readRootMetadata(location, rootHash)).components
            .fold(0L) { total, component -> Math.addExact(total, component.length) }
    private fun path(base: File, vararg names: String): File = files.child(base, *names)
    private fun safe(value: String) = sha256(value.toByteArray()).hex()
    private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
    private fun ByteArray.hex() = toCaptureHashHex()
    private fun List<Int>.hex() = toCaptureHashHex()
    private data class Staged(val kind: CaptureComponentKind, val file: File, val descriptor: CaptureComponentDescriptor)
    private data class RootPointer(val revision: Long, val rootHash: String, val commitId: String, val slot: String = "", val previousHash: String? = null)
    companion object {
        /** Reclaim identity of one deleted session; the same derivation as the iOS store. */
        fun sessionReclaimId(sessionId: String): String =
            MessageDigest.getInstance("SHA-256").digest("native-capture-session-reclaim|$sessionId".toByteArray()).toCaptureHashHex()
        private const val RECOVERY_INDEX_CAPACITY = 3
        internal fun logStoreWarning(message: String) { android.util.Log.w("Capture3DStore", message) }
        internal val SAFE_HASH = Regex("[0-9a-f]{64}")
    }
}
