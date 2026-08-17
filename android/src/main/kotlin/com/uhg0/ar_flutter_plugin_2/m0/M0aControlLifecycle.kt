package com.uhg0.ar_flutter_plugin_2.m0

/**
 * Bounded control state for one V2 binding.
 *
 * The lifecycle is deliberately independent of AR state. It owns only the
 * control receipt/idempotency contract: one active stream token, explicit
 * checkpoint/stop ordering, and a bounded exact replay cache. Native grid
 * work can be attached behind these receipts once the V2 decision is locked.
 */
class M0aControlLifecycle(
    private val maximumResponseBytes: Int = M0aControlCodec.hardCeilingBytes,
) {
    enum class State { IDLE, ACTIVE, STOPPED }

    private var state = State.IDLE
    private var nextStreamToken = 1L
    private var activeStreamToken = 0L
    private data class Receipt(val request: ByteArray, val response: ByteArray, val id: M0aUuid)

    private val receipts = mutableListOf<Receipt>()

    fun state(): State = state

    fun streamToken(): Long = activeStreamToken

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
        activeStreamToken = nextStreamToken++
        state = State.ACTIVE
        return success(request, activeStreamToken)
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

    private fun success(request: M0aControlRequest, streamToken: Long): ByteArray =
        M0aControlCodec.encodeResponse(
            M0aControlResponse(
                operation = request.operation,
                outcome = 0,
                resultFlags = 0,
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
        const val REQUEST_REPLAY_CONFLICT = 30
        const val STREAM_TOKEN_STALE = 4
        const val LIFECYCLE_STATE_INVALID = 48
        const val MAX_RECEIPTS = 4
    }
}
