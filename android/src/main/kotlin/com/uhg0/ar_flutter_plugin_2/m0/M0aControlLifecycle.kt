package com.uhg0.ar_flutter_plugin_2.m0

/**
 * Bounded control state for one V2 binding.
 *
 * The lifecycle is deliberately independent of AR state. It owns only the
 * control receipt/idempotency contract: one active stream token, explicit
 * checkpoint/stop ordering, and a bounded exact replay cache. Native grid
 * work can be attached behind these receipts once the V2 decision is locked.
 */
data class M0aCommittedBaselineV1(
    val transactionId: Long,
    val geometryRevision: Long,
    val lineageRevision: Long,
    val styleRevision: Long,
    val evidenceRevision: Long = 0,
    val captureRevision: Long = 0,
    val coverageRevision: Long = 0,
    val producedStyleRevision: Long = 0,
    val regionManifestRevision: Long = 0,
    val schemaRootRevision: Long = 0,
    val nextSurfaceIdHighWater: Long = 0,
    val schemaRootHashIdentity: String = "",
    val manifestRootHashIdentity: String = "",
    val groupFrameConvention: Int = 1,
    val matrixConvention: Int = 1,
    val directionConvention: Int = 1,
    val normalEncoding: Int = 1,
    val groupFromWorldIdentity: String = M0A_IDENTITY_MATRIX_IDENTITY,
    val worldFromGroupIdentity: String = M0A_IDENTITY_MATRIX_IDENTITY,
) {
    init {
        require(
            listOf(
                transactionId,
                geometryRevision,
                lineageRevision,
                styleRevision,
                evidenceRevision,
                captureRevision,
                coverageRevision,
                producedStyleRevision,
                regionManifestRevision,
                schemaRootRevision,
                nextSurfaceIdHighWater,
            ).all { it >= 0 },
        )
    }

    /** Complete accepted StartResultV2 cut in canonical revision order. */
    fun resultRevisionCut(): LongArray = longArrayOf(
        evidenceRevision,
        geometryRevision,
        lineageRevision,
        captureRevision,
        coverageRevision,
        producedStyleRevision,
        styleRevision,
        regionManifestRevision,
        schemaRootRevision,
        nextSurfaceIdHighWater,
        1L,
        0L,
    )

    companion object {
        val ZERO = M0aCommittedBaselineV1(0, 0, 0, 0)

        fun fromRestoredConfiguration(
            configuration: M0aStartRequestCodecV2.Configuration,
        ): M0aCommittedBaselineV1 {
            val revisions = configuration.restoredRevisions
            require(revisions.size >= 7) { "A canonical START cut has seven revisions" }
            return M0aCommittedBaselineV1(
                transactionId = 0,
                geometryRevision = revisions[1],
                lineageRevision = revisions[2],
                styleRevision = revisions[6],
                evidenceRevision = revisions[0],
                captureRevision = revisions[3],
                coverageRevision = revisions[4],
                producedStyleRevision = revisions[5],
                regionManifestRevision = revisions[7],
                schemaRootRevision = revisions[8],
                nextSurfaceIdHighWater = revisions[9],
                schemaRootHashIdentity = configuration.schemaRootHashIdentity,
                manifestRootHashIdentity = configuration.manifestRootHashIdentity,
                groupFrameConvention = configuration.groupFrameConvention,
                matrixConvention = configuration.matrixConvention,
                directionConvention = configuration.directionConvention,
                normalEncoding = configuration.normalEncoding,
                groupFromWorldIdentity = configuration.groupFromWorldIdentity,
                worldFromGroupIdentity = configuration.worldFromGroupIdentity,
            )
        }
    }
}

class M0aControlLifecycle(
    private val maximumResponseBytes: Int = M0aControlCodec.hardCeilingBytes,
    private val committedBaselineAuthority: M0aCommittedBaselineAuthority? = null,
    initialCommittedBaseline: M0aCommittedBaselineV1 = M0aCommittedBaselineV1.ZERO,
) {
    enum class State { IDLE, ACTIVE, ABANDONED, STOPPED }

    /** Bounded scalar telemetry; requested bit identities are never retained. */
    class Metrics {
        companion object {
            const val MAX_UNSUPPORTED_DESIRED_CAPABILITY_BITS = 0xffffL
        }

        var unsupportedDesiredCapabilityBits: Long = 0
            private set

        fun recordUnsupportedDesiredCapabilityBits(desiredCapabilities: Long) {
            val unsupported = desiredCapabilities and
                M0aStartRequestCodecV2.supportedCapabilities.inv()
            unsupportedDesiredCapabilityBits =
                (unsupportedDesiredCapabilityBits + java.lang.Long.bitCount(unsupported))
                    .coerceAtMost(MAX_UNSUPPORTED_DESIRED_CAPABILITY_BITS)
        }
    }

    val metrics = Metrics()

    private var state = State.IDLE
    private var nextStreamToken = 1L
    private var activeStreamToken = 0L
    private var committedBaseline = initialCommittedBaseline
    private var activeScope: M0aCommittedBaselineScopeV1? = null
    private data class Receipt(val request: ByteArray, val response: ByteArray, val id: M0aUuid)

    private val receipts = mutableListOf<Receipt>()

    fun state(): State = state

    fun streamToken(): Long = activeStreamToken

    @Synchronized
    fun committedBaseline(): M0aCommittedBaselineV1 = committedBaseline

    /** Supplies the authoritative native baseline before the next START. */
    @Synchronized
    fun setCommittedBaseline(value: M0aCommittedBaselineV1) {
        committedBaseline = value
        activeScope?.let { scope -> committedBaselineAuthority?.publish(scope, value) }
    }

    /** Returns null when a stream request may use this active binding token. */
    @Synchronized
    fun streamTokenError(token: Long): Int? = when {
        state != State.ACTIVE -> M0aControlError.LIFECYCLE_STATE_INVALID
        token != activeStreamToken -> M0aControlError.STREAM_TOKEN_STALE
        else -> null
    }

    /** Fences both control and stream work after an unknown native outcome. */
    @Synchronized
    fun abandon() {
        if (state != State.STOPPED) state = State.ABANDONED
    }

    fun cachedRequestBytes(): Int = receipts.sumOf { it.request.size }

    fun cachedResponseBytes(): Int = receipts.sumOf { it.response.size }

    /** Handles one already-decoded request and returns a packed VGD2 receipt. */
    @Synchronized
    fun handle(request: M0aControlRequest, encodedRequest: ByteArray): ByteArray {
        for (receipt in receipts) {
            if (receipt.request.contentEquals(encodedRequest)) return receipt.response.copyOf()
            if (receipt.id == request.controlRequestId) {
                return cache(
                    null,
                    error(request, M0aControlError.REQUEST_REPLAY_CONFLICT),
                )
            }
        }

        val response = when (request.operation) {
            M0aControlOperation.START -> start(request)
            M0aControlOperation.BEGIN_CHECKPOINT -> checkpoint(request)
            M0aControlOperation.RELEASE_CHECKPOINT -> checkpoint(request)
            M0aControlOperation.STOP -> stop(request)
        }
        return cache(encodedRequest, response)
    }

    private fun start(request: M0aControlRequest): ByteArray {
        if (state != State.IDLE) return error(request, M0aControlError.LIFECYCLE_STATE_INVALID)
        val configuration = M0aStartRequestCodecV2.decode(request.payload)
        metrics.recordUnsupportedDesiredCapabilityBits(configuration.desiredCapabilities)
        activeScope = M0aCommittedBaselineScopeV1.from(request)
        val persistedBaseline = committedBaselineAuthority?.snapshot(activeScope!!)
            ?: M0aCommittedBaselineV1.ZERO
        val availableBaseline = if (persistedBaseline != M0aCommittedBaselineV1.ZERO) {
            persistedBaseline
        } else {
            committedBaseline
        }
        if (configuration.minimumMinor > M0aStartRequestCodecV2.supportedMinor ||
            configuration.maximumMinor < M0aStartRequestCodecV2.supportedMinor) {
            return error(request, M0aControlError.UNSUPPORTED_WIRE_VERSION)
        }
        if (configuration.requiredCapabilities and
            M0aStartRequestCodecV2.supportedCapabilities != configuration.requiredCapabilities) {
            return error(request, M0aControlError.REQUIRED_CAPABILITY_UNSUPPORTED)
        }
        if (configuration.hasRestoredCutConflict(availableBaseline)) {
            return error(request, M0aControlError.CUT_INCOMPATIBLE)
        }
        committedBaseline = if (availableBaseline != M0aCommittedBaselineV1.ZERO) {
            availableBaseline
        } else if (configuration.restoreRequested) {
            M0aCommittedBaselineV1.fromRestoredConfiguration(configuration)
        } else {
            M0aCommittedBaselineV1.ZERO
        }
        activeStreamToken = nextStreamToken++
        state = State.ACTIVE
        return success(request, activeStreamToken, committedBaseline, configuration)
    }

    private fun checkpoint(request: M0aControlRequest): ByteArray {
        if (state != State.ACTIVE) return error(request, M0aControlError.LIFECYCLE_STATE_INVALID)
        if (request.streamToken != activeStreamToken) {
            return error(request, M0aControlError.STREAM_TOKEN_STALE)
        }
        return success(request, activeStreamToken)
    }

    private fun stop(request: M0aControlRequest): ByteArray {
        if (state != State.ACTIVE) return error(request, M0aControlError.LIFECYCLE_STATE_INVALID)
        if (request.streamToken != activeStreamToken) {
            return error(request, M0aControlError.STREAM_TOKEN_STALE)
        }
        state = State.STOPPED
        return success(request, activeStreamToken)
    }

    private fun success(
        request: M0aControlRequest,
        streamToken: Long,
        baseline: M0aCommittedBaselineV1? = null,
        configuration: M0aStartRequestCodecV2.Configuration? = null,
    ): ByteArray =
        M0aControlCodec.encodeResponse(
            M0aControlResponse(
                operation = request.operation,
                outcome = 0,
                resultFlags = if (request.operation == M0aControlOperation.START) 3 else 0,
                errorId = 0,
                controlRequestId = request.controlRequestId,
                sessionId = request.sessionId,
                captureGroupId = request.captureGroupId,
                sessionGeneration = request.sessionGeneration,
                groupGeneration = request.groupGeneration,
                coverageEpoch = request.coverageEpoch,
                streamToken = streamToken,
                nextExchangeRequestSequence = 1,
                nativeTransactionId = 0,
                payload = if (request.operation == M0aControlOperation.START) {
                    M0aStartResultCodecV2.encode(
                        configuration ?: error("START has no canonical configuration"),
                        baseline ?: committedBaseline,
                    )
                } else {
                    byteArrayOf()
                },
            ),
            maximumResponseBytes,
        )

    private fun error(request: M0aControlRequest, errorId: Int): ByteArray =
        M0aControlCodec.encodeResponse(
            M0aControlResponse(
                operation = request.operation,
                outcome = 1,
                resultFlags = 0,
                errorId = errorId,
                controlRequestId = request.controlRequestId,
                sessionId = request.sessionId,
                captureGroupId = request.captureGroupId,
                sessionGeneration = request.sessionGeneration,
                groupGeneration = request.groupGeneration,
                coverageEpoch = request.coverageEpoch,
                streamToken = request.streamToken,
                nextExchangeRequestSequence = 1,
                nativeTransactionId = 0,
            ),
            maximumResponseBytes,
        )

    private fun cache(request: ByteArray?, response: ByteArray): ByteArray {
        if (request != null) {
            receipts += Receipt(
                request = request.copyOf(),
                response = response.copyOf(),
                id = M0aControlCodec.decodeRequest(request).controlRequestId,
            )
            if (receipts.size > M0aControlError.MAX_RECEIPTS) receipts.removeAt(0)
        }
        return response.copyOf()
    }

    private object M0aControlError {
        const val UNSUPPORTED_WIRE_VERSION = 1
        const val REQUIRED_CAPABILITY_UNSUPPORTED = 46
        const val CUT_INCOMPATIBLE = 58
        const val REQUEST_REPLAY_CONFLICT = 30
        const val STREAM_TOKEN_STALE = 4
        const val LIFECYCLE_STATE_INVALID = 48
        const val MAX_RECEIPTS = 4
    }
}
