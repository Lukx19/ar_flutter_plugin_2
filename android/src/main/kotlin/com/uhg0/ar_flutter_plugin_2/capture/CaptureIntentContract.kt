package com.uhg0.ar_flutter_plugin_2.capture

import java.util.Collections

/** Portable #99 capture contract. This reference owns no camera or durable store. */
const val CAPTURE_PORTABLE_ORDINAL_MAXIMUM: Long = Long.MAX_VALUE
const val CAPTURE_PORTABLE_ENTRY_MAXIMUM: Long = Int.MAX_VALUE.toLong()
const val CAPTURE_COEXISTENCE_BYTES: Long = 128L * 1024L * 1024L

private fun requireDigest(bytes: List<Int>, name: String) {
    require(bytes.size == 32 && bytes.all { it in 0..255 }) { "$name must be exactly 32 unsigned bytes" }
}

private fun <T> immutableList(values: Collection<T>): List<T> =
    Collections.unmodifiableList(ArrayList(values))

private fun <T> immutableSet(values: Collection<T>): Set<T> =
    Collections.unmodifiableSet(LinkedHashSet(values))

private fun immutableDigest(bytes: List<Int>, name: String): List<Int> {
    requireDigest(bytes, name)
    return immutableList(bytes)
}

enum class CaptureLane { MANUAL, AUTOMATIC }
enum class CaptureIntentState {
    MANUAL_READY, MANUAL_WAITING_DURABILITY, AUTOMATIC_DISABLED_BY_USER,
    AUTOMATIC_READY, AUTOMATIC_WAITING_SELECTOR, AUTOMATIC_WAITING_DURABILITY,
    AUTOMATIC_WAITING_CAPTURE_HEALTH, AUTOMATIC_RECOVERING,
}
enum class CaptureAttemptPhase {
    RESERVED_ACCEPTED,
    EXPOSURE_REQUESTED,
    SENSOR_OUTPUT_OWNED,
    VALIDATED,
    DURABLE_PREPARED,
    COMMITTED_PICTURE,
    ABANDONED_ATTEMPT,
}
enum class CaptureComponentKind { JPEG, DNG, RAW, HDR, SIDECAR }
enum class CaptureTerminalKind { COMMITTED_PICTURE, ABANDONED_ATTEMPT }
enum class CaptureTransitionDisposition { APPLIED, EXACT_REPLAY, REJECTED, CONFLICT }
enum class CaptureFault {
    TIMEOUT, CANCELLATION, MALFORMED_OUTPUT, COMPONENT_FAILURE, STORAGE_FAILURE,
    ISOLATE_LOSS, PROCESS_LOSS, LATE_CALLBACK, RESERVATION_OVERFLOW,
}
enum class CaptureLifecycleEvent {
    AUTOMATIC_DISABLED, ROUTE_LEFT, VIEW_REPLACED, AR_SESSION_REPLACED, BACKGROUNDED, PROCESS_RESTARTED,
}

/** Complete immutable cut which qualifies every callback and durable receipt. */
data class CaptureLifecycleCut(
    val sessionId: String,
    val sessionGeneration: Long,
    val groupId: String,
    val groupGeneration: Long,
    val arSessionId: String,
    val viewId: String,
    val viewGeneration: Long,
    val bindingToken: String,
    val lifecycleSequence: Long,
    val operationGeneration: Long,
) {
    init {
        require(listOf(sessionGeneration, groupGeneration, viewGeneration, lifecycleSequence, operationGeneration).all { it >= 0 })
        require(listOf(sessionId, groupId, arSessionId, viewId, bindingToken).all { it.isNotEmpty() })
    }
}

/** Preflight intent before any attempt identity or exposure exists. */
class CaptureIntent(
    val lane: CaptureLane,
    val lifecycleCut: CaptureLifecycleCut,
    val profile: CaptureComponentProfile,
    val selectorValid: Boolean,
    val trackingValid: Boolean,
    val captureHealthy: Boolean,
    val durabilityPreflightValid: Boolean,
    canonicalIntentHash: List<Int>,
) {
    val canonicalIntentHash = immutableDigest(canonicalIntentHash, "canonicalIntentHash")

    override fun equals(other: Any?) = other is CaptureIntent &&
        listOf(lane, lifecycleCut, profile, selectorValid, trackingValid, captureHealthy,
            durabilityPreflightValid, canonicalIntentHash) ==
        listOf(other.lane, other.lifecycleCut, other.profile, other.selectorValid, other.trackingValid,
            other.captureHealthy, other.durabilityPreflightValid, other.canonicalIntentHash)
    override fun hashCode() = listOf(lane, lifecycleCut, profile, selectorValid, trackingValid,
        captureHealthy, durabilityPreflightValid, canonicalIntentHash).hashCode()
}

/** Fixed pre-group component composition and conservative byte bounds. */
class CaptureComponentProfile(
    val profileId: String,
    requiredComponents: Set<CaptureComponentKind>,
    val maximumComponentBytes: Long,
    val maximumWorkingBytes: Long,
) {
    val requiredComponents: Set<CaptureComponentKind> = immutableSet(requiredComponents)

    init {
        require(profileId.isNotEmpty() && requiredComponents.isNotEmpty())
        require(maximumComponentBytes > 0 && maximumWorkingBytes > 0)
    }

    override fun equals(other: Any?) = other is CaptureComponentProfile &&
        profileId == other.profileId && requiredComponents == other.requiredComponents &&
        maximumComponentBytes == other.maximumComponentBytes && maximumWorkingBytes == other.maximumWorkingBytes
    override fun hashCode() = listOf(profileId, requiredComponents, maximumComponentBytes, maximumWorkingBytes).hashCode()
}

/** Complete RAM/store liability physically backed before exposure. */
data class CaptureReservationLiability(
    val memoryBytes: Long,
    val physicalStoreBytes: Long,
    val componentEntries: Long,
    val terminalEntries: Long,
    val rollbackBytes: Long,
    val physicallyBacked: Boolean,
) {
    init {
        require(memoryBytes >= 0 && physicalStoreBytes >= 0 && rollbackBytes >= 0)
        require(componentEntries in 0..CAPTURE_PORTABLE_ENTRY_MAXIMUM)
        require(terminalEntries in 0..CAPTURE_PORTABLE_ENTRY_MAXIMUM)
    }
    val totalStoreLiability: Long = Math.addExact(physicalStoreBytes, rollbackBytes)
}

data class CaptureAttemptIdentity(
    val attemptId: String,
    val commitId: String,
    val attemptOrdinal: Long,
    val lifecycleCut: CaptureLifecycleCut,
) {
    init { require(attemptId.isNotEmpty() && commitId.isNotEmpty() && attemptOrdinal > 0) }
}

/** Durable accepted record written before the only exposure request. */
class CaptureAcceptedAttempt(
    val identity: CaptureAttemptIdentity,
    val lane: CaptureLane,
    val profile: CaptureComponentProfile,
    val reservation: CaptureReservationLiability,
    canonicalIntentHash: List<Int>,
    acceptedReceiptHash: List<Int>,
) {
    val canonicalIntentHash = immutableDigest(canonicalIntentHash, "canonicalIntentHash")
    val acceptedReceiptHash = immutableDigest(acceptedReceiptHash, "acceptedReceiptHash")

    override fun equals(other: Any?) = other is CaptureAcceptedAttempt &&
        listOf(identity, lane, profile, reservation, canonicalIntentHash, acceptedReceiptHash) ==
        listOf(other.identity, other.lane, other.profile, other.reservation,
            other.canonicalIntentHash, other.acceptedReceiptHash)
    override fun hashCode() =
        listOf(identity, lane, profile, reservation, canonicalIntentHash, acceptedReceiptHash).hashCode()
}

/** One streamed component descriptor. Component bytes are intentionally absent. */
class CaptureComponentDescriptor(
    val kind: CaptureComponentKind,
    val byteLength: Long,
    sha256: List<Int>,
    val durableObjectId: String,
) {
    val sha256 = immutableDigest(sha256, "sha256")

    init {
        require(byteLength >= 0 && durableObjectId.isNotEmpty())
    }

    override fun equals(other: Any?) = other is CaptureComponentDescriptor &&
        kind == other.kind && byteLength == other.byteLength && sha256 == other.sha256 &&
        durableObjectId == other.durableObjectId
    override fun hashCode() = listOf(kind, byteLength, sha256, durableObjectId).hashCode()
}

/** Complete typed input to a future DurableSessionStoreV2 implementation. */
class CaptureCommitRequest(
    val accepted: CaptureAcceptedAttempt,
    components: List<CaptureComponentDescriptor>,
    val exposureTimestampNanoseconds: Long,
    poseRecordHash: List<Int>,
    cameraModelHash: List<Int>,
    validationRecordHash: List<Int>,
    ledgerRecordHash: List<Int>,
) {
    val components: List<CaptureComponentDescriptor> = immutableList(components)
    val poseRecordHash = immutableDigest(poseRecordHash, "poseRecordHash")
    val cameraModelHash = immutableDigest(cameraModelHash, "cameraModelHash")
    val validationRecordHash = immutableDigest(validationRecordHash, "validationRecordHash")
    val ledgerRecordHash = immutableDigest(ledgerRecordHash, "ledgerRecordHash")

    init {
        require(exposureTimestampNanoseconds >= 0)
    }

    val hasCompleteComponentSet: Boolean
        get() = components.map { it.kind }.toSet() == accepted.profile.requiredComponents &&
            components.size == accepted.profile.requiredComponents.size

    override fun equals(other: Any?) = other is CaptureCommitRequest &&
        listOf(accepted, components, exposureTimestampNanoseconds, poseRecordHash, cameraModelHash,
            validationRecordHash, ledgerRecordHash) ==
        listOf(other.accepted, other.components, other.exposureTimestampNanoseconds, other.poseRecordHash,
            other.cameraModelHash, other.validationRecordHash, other.ledgerRecordHash)
    override fun hashCode() = listOf(accepted, components, exposureTimestampNanoseconds, poseRecordHash,
        cameraModelHash, validationRecordHash, ledgerRecordHash).hashCode()
}

data class CaptureTerminal(
    val kind: CaptureTerminalKind,
    val identity: CaptureAttemptIdentity,
    val canonicalTerminalHash: String,
    val reason: String,
    val captureId: String? = null,
    val captureRevision: Long? = null,
    val manifestId: String? = null,
) {
    init {
        require(canonicalTerminalHash.isNotEmpty() && reason.isNotEmpty())
        require(captureRevision == null || captureRevision >= 0)
        require((kind == CaptureTerminalKind.COMMITTED_PICTURE) == (captureId != null && captureRevision != null && manifestId != null))
    }
}

data class CaptureReceipt(
    val identity: CaptureAttemptIdentity,
    val phase: CaptureAttemptPhase,
    val requestHash: String,
    val receiptHash: String,
    val durable: Boolean,
    val terminal: CaptureTerminal? = null,
)

/** Declaration of the store seam; production implementation begins after #99. */
interface CaptureCommitPort {
    fun acceptBeforeExposure(attempt: CaptureAcceptedAttempt): CaptureReceipt
    fun prepareCommit(request: CaptureCommitRequest): CaptureReceipt
    fun queryReceipt(identity: CaptureAttemptIdentity): CaptureReceipt?
    fun abandon(terminal: CaptureTerminal): CaptureReceipt
}

enum class CaptureFinalizerPosition { RUNNING, FUNDED_WAITING, REJECTED }
data class CaptureFinalizerAssignment(val attempt: CaptureAcceptedAttempt, val position: CaptureFinalizerPosition, val reason: String)

/** Executable one-running plus one-funded-waiting scheduler reference. */
class CaptureFinalizerScheduler {
    var running: CaptureAcceptedAttempt? = null
        private set
    var fundedWaiting: CaptureAcceptedAttempt? = null
        private set

    fun scheduleReady(candidates: List<CaptureAcceptedAttempt>): List<CaptureFinalizerAssignment> =
        candidates.sortedWith(compareBy<CaptureAcceptedAttempt>({ it.lane != CaptureLane.MANUAL }, { it.identity.attemptOrdinal })).map { attempt ->
            when {
                running == null -> { running = attempt; CaptureFinalizerAssignment(attempt, CaptureFinalizerPosition.RUNNING, "running") }
                fundedWaiting == null -> { fundedWaiting = attempt; CaptureFinalizerAssignment(attempt, CaptureFinalizerPosition.FUNDED_WAITING, "funded-waiting") }
                else -> CaptureFinalizerAssignment(attempt, CaptureFinalizerPosition.REJECTED, "finalizer-capacity")
            }
        }

    fun release(identity: CaptureAttemptIdentity): CaptureAcceptedAttempt? {
        if (running?.identity == identity) { running = fundedWaiting; fundedWaiting = null }
        else if (fundedWaiting?.identity == identity) fundedWaiting = null
        return running
    }

    fun ownsExposure(identity: CaptureAttemptIdentity) = running?.identity == identity
}

data class CaptureTransitionResult(
    val disposition: CaptureTransitionDisposition,
    val receipt: CaptureReceipt,
    val reason: String,
)

/** Executable accepted-to-terminal oracle with exact replay and terminal fencing. */
class CaptureAttemptReferenceMachine(val accepted: CaptureAcceptedAttempt) {
    var receipt = CaptureReceipt(
        accepted.identity,
        CaptureAttemptPhase.RESERVED_ACCEPTED,
        accepted.acceptedReceiptHash.joinToString("") { it.toString(16).padStart(2, '0') },
        accepted.acceptedReceiptHash.joinToString("") { it.toString(16).padStart(2, '0') },
        durable = true,
    )
        private set
    var exposureCount = 0
        private set
    var retainedImageBytes = 0L
        private set
    var outcomeUnknown = false
        private set
    private var unknownRequestHash: String? = null
    private val replay = mutableMapOf<String, CaptureReceipt>()
    private val components = mutableSetOf<CaptureComponentKind>()

    fun requestExposure(hash: String): CaptureTransitionResult =
        transition(hash, CaptureAttemptPhase.RESERVED_ACCEPTED, CaptureAttemptPhase.EXPOSURE_REQUESTED) {
            check(receipt.durable)
            exposureCount++
        }

    fun ownSensorOutput(hash: String, values: List<CaptureComponentDescriptor>): CaptureTransitionResult =
        transition(hash, CaptureAttemptPhase.EXPOSURE_REQUESTED, CaptureAttemptPhase.SENSOR_OUTPUT_OWNED) {
            values.forEach {
                check(components.add(it.kind)) { "duplicate component" }
                retainedImageBytes = Math.addExact(retainedImageBytes, it.byteLength)
            }
        }

    fun validate(hash: String): CaptureTransitionResult {
        if (components != accepted.profile.requiredComponents) return unchanged(CaptureTransitionDisposition.REJECTED, "incomplete-component-set")
        return transition(hash, CaptureAttemptPhase.SENSOR_OUTPUT_OWNED, CaptureAttemptPhase.VALIDATED) {}
    }

    fun prepareDurable(hash: String, request: CaptureCommitRequest): CaptureTransitionResult {
        if (!request.hasCompleteComponentSet || request.accepted.identity != accepted.identity) {
            return unchanged(CaptureTransitionDisposition.REJECTED, "invalid-commit-input")
        }
        val bytes = request.components.fold(0L) { sum, component -> Math.addExact(sum, component.byteLength) }
        if (bytes > accepted.profile.maximumComponentBytes || bytes > accepted.reservation.physicalStoreBytes) {
            return abandon("$hash:overflow", "reservation-overflow")
        }
        return transition(hash, CaptureAttemptPhase.VALIDATED, CaptureAttemptPhase.DURABLE_PREPARED) { retainedImageBytes = 0 }
    }

    fun commit(
        hash: String,
        captureId: String,
        captureRevision: Long,
        manifestId: String,
        reason: String = "committed",
    ): CaptureTransitionResult {
        if (receipt.phase != CaptureAttemptPhase.DURABLE_PREPARED) return replayOrReject(hash, "commit-before-prepare")
        return terminal(
            hash,
            CaptureTerminal(CaptureTerminalKind.COMMITTED_PICTURE, accepted.identity, hash, reason, captureId, captureRevision, manifestId),
        )
    }

    fun abandon(hash: String, reason: String): CaptureTransitionResult {
        if (receipt.terminal != null) return replayOrReject(hash, "terminal-already-published")
        retainedImageBytes = 0
        return terminal(hash, CaptureTerminal(CaptureTerminalKind.ABANDONED_ATTEMPT, accepted.identity, hash, reason))
    }

    fun timeoutUnknown(hash: String = "unknown"): CaptureTransitionResult {
        unknownRequestHash?.let {
            return if (it == hash) CaptureTransitionResult(CaptureTransitionDisposition.EXACT_REPLAY, receipt, "exact-unknown-replay")
            else unchanged(CaptureTransitionDisposition.CONFLICT, "changed-unknown-replay")
        }
        outcomeUnknown = true
        unknownRequestHash = hash
        return unchanged(CaptureTransitionDisposition.APPLIED, "outcome-unknown-query-same-identity")
    }

    fun lateCallback(): CaptureTransitionResult = unchanged(CaptureTransitionDisposition.REJECTED, "late-producer-fenced")

    private fun transition(
        hash: String,
        expected: CaptureAttemptPhase,
        next: CaptureAttemptPhase,
        effect: () -> Unit,
    ): CaptureTransitionResult {
        if (receipt.phase != expected || receipt.terminal != null) return replayOrReject(hash, "invalid-transition")
        try {
            effect()
        } catch (error: IllegalStateException) {
            return unchanged(CaptureTransitionDisposition.REJECTED, error.message ?: "invalid-effect")
        }
        receipt = CaptureReceipt(accepted.identity, next, hash, "$hash:${next.name}", next == CaptureAttemptPhase.DURABLE_PREPARED)
        replay[hash] = receipt
        return CaptureTransitionResult(CaptureTransitionDisposition.APPLIED, receipt, "applied")
    }

    private fun terminal(hash: String, value: CaptureTerminal): CaptureTransitionResult {
        val phase = if (value.kind == CaptureTerminalKind.COMMITTED_PICTURE) CaptureAttemptPhase.COMMITTED_PICTURE else CaptureAttemptPhase.ABANDONED_ATTEMPT
        receipt = CaptureReceipt(accepted.identity, phase, hash, "$hash:${value.kind.name}", durable = true, terminal = value)
        replay[hash] = receipt
        outcomeUnknown = false
        unknownRequestHash = null
        return CaptureTransitionResult(CaptureTransitionDisposition.APPLIED, receipt, "terminal")
    }

    private fun replayOrReject(hash: String, reason: String): CaptureTransitionResult {
        replay[hash]?.let { return CaptureTransitionResult(CaptureTransitionDisposition.EXACT_REPLAY, it, "exact-replay") }
        val disposition = if (receipt.terminal == null) CaptureTransitionDisposition.REJECTED else CaptureTransitionDisposition.CONFLICT
        return unchanged(disposition, if (receipt.terminal == null) reason else "changed-terminal-replay")
    }

    private fun unchanged(disposition: CaptureTransitionDisposition, reason: String) = CaptureTransitionResult(disposition, receipt, reason)
}

data class CaptureFaultLifecycleCase(
    val lane: CaptureLane,
    val phase: CaptureAttemptPhase,
    val fault: CaptureFault,
    val lifecycleEvent: CaptureLifecycleEvent,
    val expectedOutcome: CaptureMatrixOutcome,
)

enum class CaptureMatrixOutcome { ABANDONED, OUTCOME_UNKNOWN, COMMITTED }
enum class CaptureLifecycleOwnershipEffect {
    AUTOMATIC_ACTIVE_OWNER_CONTINUES,
    ROUTE_PRE_OUTPUT_CLASSIFIED,
    ROUTE_OWNED_OUTPUT_TRANSFERRED,
    ROUTE_DURABLE_STORE_OWNED,
    BACKGROUND_PRE_OUTPUT_CLASSIFIED,
    BACKGROUND_OWNED_OUTPUT_FINISHING,
    BACKGROUND_DURABLE_STORE_OWNED,
    VIEW_PRE_OUTPUT_FENCED,
    VIEW_OWNED_OUTPUT_TRANSFERRED,
    VIEW_DURABLE_STORE_OWNED,
    AR_PRE_OUTPUT_GROUP_FENCED,
    AR_OWNED_OUTPUT_FROZEN_GROUP,
    AR_DURABLE_OLD_GROUP_STORE_OWNED,
    PROCESS_PRE_DURABLE_ABSENT,
    PROCESS_DURABLE_RECEIPT_RECOVERED,
}
data class CaptureMatrixExecution(
    val outcome: CaptureMatrixOutcome,
    val phase: CaptureAttemptPhase,
    val exposureCount: Int,
    val exactReplay: Boolean,
    val changedReplayConflict: Boolean,
    val ownershipEffect: CaptureLifecycleOwnershipEffect,
    val lateCallbackNoOp: Boolean,
    val sensorOutputOwnedAtCut: Boolean,
    val continuedExposureAfterCut: Boolean,
    val sensorOutputAcquiredAfterCut: Boolean,
    val classificationReason: String,
)

/** Generated complete 2 x 5 x 9 x 6 lane/fault/lifecycle matrix. */
object CaptureFaultLifecycleMatrix {
    fun generate(): List<CaptureFaultLifecycleCase> =
        CaptureLane.entries.flatMap { lane ->
            CaptureAttemptPhase.entries.take(5).flatMap { phase ->
                CaptureFault.entries.flatMap { fault ->
                    CaptureLifecycleEvent.entries.map { event ->
                        CaptureFaultLifecycleCase(lane, phase, fault, event, expectedOutcome(phase, fault, event))
                    }
                }
            }
        }

    private fun expectedOutcome(
        phase: CaptureAttemptPhase,
        fault: CaptureFault,
        lifecycleEvent: CaptureLifecycleEvent,
    ): CaptureMatrixOutcome {
        if (fault in setOf(CaptureFault.CANCELLATION, CaptureFault.MALFORMED_OUTPUT,
                CaptureFault.COMPONENT_FAILURE, CaptureFault.STORAGE_FAILURE, CaptureFault.RESERVATION_OVERFLOW)) {
            return CaptureMatrixOutcome.ABANDONED
        }
        if (fault == CaptureFault.LATE_CALLBACK) return lateCallbackOutcome(phase, lifecycleEvent)
        val uncertain = fault in setOf(CaptureFault.TIMEOUT, CaptureFault.ISOLATE_LOSS, CaptureFault.PROCESS_LOSS)
        if (phase == CaptureAttemptPhase.DURABLE_PREPARED && uncertain) return CaptureMatrixOutcome.COMMITTED
        if (lifecycleEvent == CaptureLifecycleEvent.PROCESS_RESTARTED) return CaptureMatrixOutcome.ABANDONED
        if (phase != CaptureAttemptPhase.RESERVED_ACCEPTED && uncertain) return CaptureMatrixOutcome.OUTCOME_UNKNOWN
        return CaptureMatrixOutcome.ABANDONED
    }

    private fun lateCallbackOutcome(phase: CaptureAttemptPhase, event: CaptureLifecycleEvent): CaptureMatrixOutcome =
        when (event) {
            CaptureLifecycleEvent.AUTOMATIC_DISABLED -> CaptureMatrixOutcome.COMMITTED
            CaptureLifecycleEvent.ROUTE_LEFT, CaptureLifecycleEvent.BACKGROUNDED,
            CaptureLifecycleEvent.VIEW_REPLACED, CaptureLifecycleEvent.AR_SESSION_REPLACED ->
                if (phase.ordinal < CaptureAttemptPhase.SENSOR_OUTPUT_OWNED.ordinal)
                    CaptureMatrixOutcome.ABANDONED else CaptureMatrixOutcome.COMMITTED
            CaptureLifecycleEvent.PROCESS_RESTARTED -> if (phase == CaptureAttemptPhase.DURABLE_PREPARED)
                CaptureMatrixOutcome.COMMITTED else CaptureMatrixOutcome.ABANDONED
        }

    private fun ownershipEffect(
        event: CaptureLifecycleEvent,
        phase: CaptureAttemptPhase,
    ): CaptureLifecycleOwnershipEffect {
        val preOutput = phase.ordinal < CaptureAttemptPhase.SENSOR_OUTPUT_OWNED.ordinal
        val durable = phase == CaptureAttemptPhase.DURABLE_PREPARED
        return when (event) {
            CaptureLifecycleEvent.AUTOMATIC_DISABLED ->
                CaptureLifecycleOwnershipEffect.AUTOMATIC_ACTIVE_OWNER_CONTINUES
            CaptureLifecycleEvent.ROUTE_LEFT -> if (preOutput)
                CaptureLifecycleOwnershipEffect.ROUTE_PRE_OUTPUT_CLASSIFIED else if (durable)
                CaptureLifecycleOwnershipEffect.ROUTE_DURABLE_STORE_OWNED else
                CaptureLifecycleOwnershipEffect.ROUTE_OWNED_OUTPUT_TRANSFERRED
            CaptureLifecycleEvent.BACKGROUNDED -> if (preOutput)
                CaptureLifecycleOwnershipEffect.BACKGROUND_PRE_OUTPUT_CLASSIFIED else if (durable)
                CaptureLifecycleOwnershipEffect.BACKGROUND_DURABLE_STORE_OWNED else
                CaptureLifecycleOwnershipEffect.BACKGROUND_OWNED_OUTPUT_FINISHING
            CaptureLifecycleEvent.VIEW_REPLACED -> if (preOutput)
                CaptureLifecycleOwnershipEffect.VIEW_PRE_OUTPUT_FENCED else if (durable)
                CaptureLifecycleOwnershipEffect.VIEW_DURABLE_STORE_OWNED else
                CaptureLifecycleOwnershipEffect.VIEW_OWNED_OUTPUT_TRANSFERRED
            CaptureLifecycleEvent.AR_SESSION_REPLACED -> if (preOutput)
                CaptureLifecycleOwnershipEffect.AR_PRE_OUTPUT_GROUP_FENCED else if (durable)
                CaptureLifecycleOwnershipEffect.AR_DURABLE_OLD_GROUP_STORE_OWNED else
                CaptureLifecycleOwnershipEffect.AR_OWNED_OUTPUT_FROZEN_GROUP
            CaptureLifecycleEvent.PROCESS_RESTARTED -> if (durable)
                CaptureLifecycleOwnershipEffect.PROCESS_DURABLE_RECEIPT_RECOVERED else
                CaptureLifecycleOwnershipEffect.PROCESS_PRE_DURABLE_ABSENT
        }
    }

    fun execute(row: CaptureFaultLifecycleCase, accepted: CaptureAcceptedAttempt, request: CaptureCommitRequest): CaptureMatrixExecution {
        val machine = CaptureAttemptReferenceMachine(accepted)
        if (row.phase.ordinal >= CaptureAttemptPhase.EXPOSURE_REQUESTED.ordinal) machine.requestExposure("expose")
        if (row.phase.ordinal >= CaptureAttemptPhase.SENSOR_OUTPUT_OWNED.ordinal) machine.ownSensorOutput("output", request.components)
        if (row.phase.ordinal >= CaptureAttemptPhase.VALIDATED.ordinal) machine.validate("validate")
        if (row.phase.ordinal >= CaptureAttemptPhase.DURABLE_PREPARED.ordinal) machine.prepareDurable("prepare", request)
        var lateCallbackNoOp = false
        if (row.fault == CaptureFault.LATE_CALLBACK) {
            val phaseBeforeCallback = machine.receipt.phase
            val exposureBeforeCallback = machine.exposureCount
            val callback = machine.lateCallback()
            lateCallbackNoOp = callback.disposition == CaptureTransitionDisposition.REJECTED &&
                machine.receipt.phase == phaseBeforeCallback && machine.exposureCount == exposureBeforeCallback
        }
        val ownershipEffect = ownershipEffect(row.lifecycleEvent, row.phase)
        val sensorOutputOwnedAtCut = row.phase.ordinal >= CaptureAttemptPhase.SENSOR_OUTPUT_OWNED.ordinal
        val canonicalOutcome = expectedOutcome(row.phase, row.fault, row.lifecycleEvent)
        if (canonicalOutcome == CaptureMatrixOutcome.COMMITTED) {
            val continuation = continuePreservedOwnership(machine, request, row.lifecycleEvent)
            machine.commit("terminal", "matrix-capture", 1, "matrix-manifest", "lifecycle:${ownershipEffect.name}")
            val exact = machine.commit("terminal", "matrix-capture", 1, "matrix-manifest")
            val changed = machine.commit("changed-terminal", "changed", 2, "changed")
            return CaptureMatrixExecution(CaptureMatrixOutcome.COMMITTED, machine.receipt.phase, machine.exposureCount,
                exact.disposition == CaptureTransitionDisposition.EXACT_REPLAY, changed.disposition == CaptureTransitionDisposition.CONFLICT,
                ownershipEffect, lateCallbackNoOp, sensorOutputOwnedAtCut, continuation.first, continuation.second,
                requireNotNull(machine.receipt.terminal).reason)
        }
        if (canonicalOutcome == CaptureMatrixOutcome.OUTCOME_UNKNOWN) {
            machine.timeoutUnknown("unknown")
            val exact = machine.timeoutUnknown("unknown")
            val changed = machine.timeoutUnknown("changed-unknown")
            return CaptureMatrixExecution(CaptureMatrixOutcome.OUTCOME_UNKNOWN, machine.receipt.phase, machine.exposureCount,
                exact.disposition == CaptureTransitionDisposition.EXACT_REPLAY, changed.disposition == CaptureTransitionDisposition.CONFLICT,
                ownershipEffect, lateCallbackNoOp, sensorOutputOwnedAtCut, false, false,
                "unknown:${ownershipEffect.name}:query-same-identity")
        }
        machine.abandon("terminal", "lifecycle:${ownershipEffect.name}:proven-absent")
        val exact = machine.abandon("terminal", "proven-absent")
        val changed = machine.abandon("changed-terminal", "changed")
        return CaptureMatrixExecution(CaptureMatrixOutcome.ABANDONED, machine.receipt.phase, machine.exposureCount,
            exact.disposition == CaptureTransitionDisposition.EXACT_REPLAY, changed.disposition == CaptureTransitionDisposition.CONFLICT,
            ownershipEffect, lateCallbackNoOp, sensorOutputOwnedAtCut, false, false,
            requireNotNull(machine.receipt.terminal).reason)
    }

    private fun continuePreservedOwnership(
        machine: CaptureAttemptReferenceMachine,
        request: CaptureCommitRequest,
        event: CaptureLifecycleEvent,
    ): Pair<Boolean, Boolean> {
        var continuedExposureAfterCut = false
        var sensorOutputAcquiredAfterCut = false
        if (machine.receipt.phase == CaptureAttemptPhase.RESERVED_ACCEPTED) {
            check(event == CaptureLifecycleEvent.AUTOMATIC_DISABLED) { "lifecycle cannot request a new exposure" }
            machine.requestExposure("lifecycle-expose")
            continuedExposureAfterCut = true
        }
        if (machine.receipt.phase == CaptureAttemptPhase.EXPOSURE_REQUESTED) {
            check(event == CaptureLifecycleEvent.AUTOMATIC_DISABLED) {
                "lifecycle cannot fabricate sensor-output ownership"
            }
            machine.ownSensorOutput("lifecycle-output", request.components)
            sensorOutputAcquiredAfterCut = true
        }
        if (machine.receipt.phase == CaptureAttemptPhase.SENSOR_OUTPUT_OWNED) machine.validate("lifecycle-validate")
        if (machine.receipt.phase == CaptureAttemptPhase.VALIDATED) machine.prepareDurable("lifecycle-prepare", request)
        return Pair(continuedExposureAfterCut, sensorOutputAcquiredAfterCut)
    }
}

/** Canonical legal edges; every other phase pair is rejected. */
object CaptureAttemptTransitionTable {
    val legalEdges = setOf(
        CaptureAttemptPhase.RESERVED_ACCEPTED to CaptureAttemptPhase.EXPOSURE_REQUESTED,
        CaptureAttemptPhase.EXPOSURE_REQUESTED to CaptureAttemptPhase.SENSOR_OUTPUT_OWNED,
        CaptureAttemptPhase.SENSOR_OUTPUT_OWNED to CaptureAttemptPhase.VALIDATED,
        CaptureAttemptPhase.VALIDATED to CaptureAttemptPhase.DURABLE_PREPARED,
        CaptureAttemptPhase.DURABLE_PREPARED to CaptureAttemptPhase.COMMITTED_PICTURE,
        CaptureAttemptPhase.RESERVED_ACCEPTED to CaptureAttemptPhase.ABANDONED_ATTEMPT,
        CaptureAttemptPhase.EXPOSURE_REQUESTED to CaptureAttemptPhase.ABANDONED_ATTEMPT,
        CaptureAttemptPhase.SENSOR_OUTPUT_OWNED to CaptureAttemptPhase.ABANDONED_ATTEMPT,
        CaptureAttemptPhase.VALIDATED to CaptureAttemptPhase.ABANDONED_ATTEMPT,
        CaptureAttemptPhase.DURABLE_PREPARED to CaptureAttemptPhase.ABANDONED_ATTEMPT,
    )
    fun isLegal(from: CaptureAttemptPhase, destination: CaptureAttemptPhase) =
        Pair(from, destination) in legalEdges
}
