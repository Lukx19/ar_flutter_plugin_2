package com.uhg0.ar_flutter_plugin_2.capture

/** Portable #99 capture contract. This reference owns no camera or durable store. */
const val CAPTURE_PORTABLE_ORDINAL_MAXIMUM: Long = Long.MAX_VALUE
const val CAPTURE_COEXISTENCE_BYTES: Long = 128L * 1024L * 1024L

enum class CaptureLane { MANUAL, AUTOMATIC }
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
)

/** Fixed pre-group component composition and conservative byte bounds. */
class CaptureComponentProfile(
    val profileId: String,
    requiredComponents: Set<CaptureComponentKind>,
    val maximumComponentBytes: Long,
    val maximumWorkingBytes: Long,
) {
    val requiredComponents: Set<CaptureComponentKind> = requiredComponents.toSet()

    init {
        require(requiredComponents.isNotEmpty())
        require(maximumComponentBytes > 0 && maximumWorkingBytes > 0)
    }
}

/** Complete RAM/store liability physically backed before exposure. */
data class CaptureReservationLiability(
    val memoryBytes: Long,
    val physicalStoreBytes: Long,
    val componentEntries: Int,
    val terminalEntries: Int,
    val rollbackBytes: Long,
    val physicallyBacked: Boolean,
) {
    val totalStoreLiability: Long = Math.addExact(physicalStoreBytes, rollbackBytes)
}

data class CaptureAttemptIdentity(
    val attemptId: String,
    val commitId: String,
    val attemptOrdinal: Long,
    val lifecycleCut: CaptureLifecycleCut,
)

/** Durable accepted record written before the only exposure request. */
data class CaptureAcceptedAttempt(
    val identity: CaptureAttemptIdentity,
    val lane: CaptureLane,
    val profile: CaptureComponentProfile,
    val reservation: CaptureReservationLiability,
    val canonicalIntentHash: String,
    val acceptedReceiptHash: String,
)

/** One streamed component descriptor. Component bytes are intentionally absent. */
data class CaptureComponentDescriptor(
    val kind: CaptureComponentKind,
    val byteLength: Long,
    val sha256: String,
    val durableObjectId: String,
)

/** Complete typed input to a future DurableSessionStoreV2 implementation. */
class CaptureCommitRequest(
    val accepted: CaptureAcceptedAttempt,
    components: List<CaptureComponentDescriptor>,
    val exposureTimestampNanoseconds: Long,
    val poseRecordHash: String,
    val cameraModelHash: String,
    val validationRecordHash: String,
    val ledgerRecordHash: String,
) {
    val components: List<CaptureComponentDescriptor> = components.toList()

    val hasCompleteComponentSet: Boolean
        get() = components.map { it.kind }.toSet() == accepted.profile.requiredComponents &&
            components.size == accepted.profile.requiredComponents.size
}

data class CaptureTerminal(
    val kind: CaptureTerminalKind,
    val identity: CaptureAttemptIdentity,
    val canonicalTerminalHash: String,
    val reason: String,
    val captureId: String? = null,
    val captureRevision: Long? = null,
    val manifestId: String? = null,
)

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
        accepted.acceptedReceiptHash,
        accepted.acceptedReceiptHash,
        durable = true,
    )
        private set
    var exposureCount = 0
        private set
    var retainedImageBytes = 0L
        private set
    var outcomeUnknown = false
        private set
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

    fun commit(hash: String, captureId: String, captureRevision: Long, manifestId: String): CaptureTransitionResult {
        if (receipt.phase != CaptureAttemptPhase.DURABLE_PREPARED) return replayOrReject(hash, "commit-before-prepare")
        return terminal(
            hash,
            CaptureTerminal(CaptureTerminalKind.COMMITTED_PICTURE, accepted.identity, hash, "committed", captureId, captureRevision, manifestId),
        )
    }

    fun abandon(hash: String, reason: String): CaptureTransitionResult {
        if (receipt.terminal != null) return replayOrReject(hash, "terminal-already-published")
        retainedImageBytes = 0
        return terminal(hash, CaptureTerminal(CaptureTerminalKind.ABANDONED_ATTEMPT, accepted.identity, hash, reason))
    }

    fun timeoutUnknown(): CaptureTransitionResult {
        outcomeUnknown = true
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
    val phase: CaptureAttemptPhase,
    val fault: CaptureFault,
    val lifecycleEvent: CaptureLifecycleEvent,
    val expectedTerminal: CaptureTerminalKind,
)

/** Generated complete 5 x 9 x 6 pre-terminal fault/lifecycle matrix. */
object CaptureFaultLifecycleMatrix {
    fun generate(): List<CaptureFaultLifecycleCase> =
        CaptureAttemptPhase.entries.take(5).flatMap { phase ->
            CaptureFault.entries.flatMap { fault ->
                CaptureLifecycleEvent.entries.map { event ->
                    CaptureFaultLifecycleCase(phase, fault, event, CaptureTerminalKind.ABANDONED_ATTEMPT)
                }
            }
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
