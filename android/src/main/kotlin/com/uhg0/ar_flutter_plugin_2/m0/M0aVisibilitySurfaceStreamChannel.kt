package com.uhg0.ar_flutter_plugin_2.m0

import android.os.Handler
import android.os.Looper
import io.flutter.plugin.common.BasicMessageChannel
import io.flutter.plugin.common.BinaryCodec
import io.flutter.plugin.common.BinaryMessenger
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.ArrayDeque

/** Small seam so lifecycle deadlines are deterministic in the JVM corpus. */
fun interface M0aTimeoutHandle {
    fun cancel()
}

interface M0aTimeoutScheduler {
    fun schedule(delayMillis: Long, task: () -> Unit): M0aTimeoutHandle

    fun shutdown()

    companion object {
        fun real(): M0aTimeoutScheduler = ExecutorTimeoutScheduler()
    }
}

private class ExecutorTimeoutScheduler(
    private val executor: ScheduledExecutorService =
        Executors.newSingleThreadScheduledExecutor(),
) : M0aTimeoutScheduler {
    override fun schedule(delayMillis: Long, task: () -> Unit): M0aTimeoutHandle {
        val future = executor.schedule(task, delayMillis, TimeUnit.MILLISECONDS)
        return M0aTimeoutHandle { future.cancel(false) }
    }

    override fun shutdown() {
        executor.shutdownNow()
    }
}

/**
 * M0a's real per-view T2 binding seam.
 *
 * The channel accepts only packed bytes, executes one request at a time on a
 * serial worker, and caches the exact response for a duplicate sequence. It
 * intentionally exposes no V1 maps or native surface arrays.
 */
class M0aVisibilitySurfaceStreamChannel(
    messenger: BinaryMessenger,
    viewId: Int,
    private val workerExecutor: Executor = Executors.newSingleThreadExecutor(),
    private val shutdownWorkerOnDispose: Boolean = true,
    private val workerTimeoutMillis: Long = DEFAULT_WORKER_TIMEOUT_MILLIS,
    private val timeoutScheduler: M0aTimeoutScheduler = M0aTimeoutScheduler.real(),
    private val beforeWorkerProcessing: (() -> Unit)? = null,
    private val beforeRequestProcessing: ((M0aPacketCodec.Request) -> Unit)? = null,
    private val controlLifecycle: M0aControlLifecycle? = null,
    private val onExecutorOperation: ((String) -> Unit)? = null,
    private val bindingQualifier: ByteArray? = null,
    private val beforeAuthorityPublication: (() -> Unit)? = null,
    private val afterAuthorityPublicationFenceAcquired: (() -> Unit)? = null,
    private val afterCommitPublication: (() -> Unit)? = null,
    private val onCommitPublished: ((M0aPacketCodec.Request, M0aCommittedBaselineV1) -> Unit)? = null,
    private val onAbandonedRequest: ((M0aPacketCodec.Request, M0aCommittedBaselineV1) -> Unit)? = null,
    private val onAbandonedContinuation: (() -> Unit)? = null,
    initialNextExpectedSequence: Long = 1L,
) {
    init {
        require(initialNextExpectedSequence in 1..Long.MAX_VALUE) {
            "initialNextExpectedSequence is outside PortableOrdinal"
        }
    }
    private val mainHandler = Handler(Looper.getMainLooper())
    private val channel = BasicMessageChannel<ByteBuffer>(
        messenger,
        "visibility_surface_stream_$viewId",
        BinaryCodec.INSTANCE,
    )
    private val metricsChannel = BasicMessageChannel<ByteBuffer>(
        messenger,
        "visibility_surface_metrics_$viewId",
        BinaryCodec.INSTANCE,
    )
    private val disposed = AtomicBoolean(false)
    private val handlersInstalled = AtomicBoolean(true)
    private val timeoutSchedulerActive = AtomicBoolean(true)
    private val outstandingInvocation = AtomicBoolean(false)
    private val queuedBackpressure = AtomicBoolean(false)
    @Volatile private var activePendingReply: PendingReply? = null
    @Volatile private var lastSequence: Long? = null
    @Volatile private var nextExpectedSequence = initialNextExpectedSequence
    @Volatile private var lastRequest: ByteArray? = null
    @Volatile private var lastResponse: ByteArray? = null
    @Volatile private var resyncPending = false
    private val bindingAbandoned = AtomicBoolean(false)
    private val publicationFence = Any()
    private val transactionReceiver = M0aStructuralTransactionReceiverV1()
    private val telemetry = M0aTransportInstrumentation()
    private val structuralFrames = ArrayDeque<M0aTransactionFrameV1>()
    @Volatile private var committedBaseline =
        controlLifecycle?.committedBaseline() ?: M0aCommittedBaselineV1.ZERO
    @Volatile private var queuedTransactionBaseline: M0aCommittedBaselineV1? = null

    /** The bounded begin/chunk/commit seam owned by this binding. */
    val structuralTransactionReceiver: M0aStructuralTransactionReceiverV1
        get() = transactionReceiver

    /** Numeric telemetry for this packed binding; no surface arrays are exposed. */
    val transportInstrumentation: M0aTransportInstrumentation
        get() = telemetry

    internal data class LifecycleResources(
        val handlerCount: Long,
        val timeoutSchedulerCount: Long,
        val pendingReplyCount: Long,
    )

    /** State-derived resources owned by this stream at the observation cut. */
    internal fun lifecycleResources() = LifecycleResources(
        handlerCount = if (handlersInstalled.get()) 2L else 0L,
        timeoutSchedulerCount = if (timeoutSchedulerActive.get()) 1L else 0L,
        pendingReplyCount = if (activePendingReply != null) 1L else 0L,
    )

    /** Debug-only execution of a terminal drain on this owned stream state. */
    internal fun executeDebugTerminalDrain(streamToken: Long): M0aPacketCodec.Response {
        val request = M0aPacketCodec.Request(
            requestFlags = TERMINAL_DRAIN_REQUEST_FLAG,
            streamToken = streamToken,
            acknowledgedTransactionId = committedBaseline.transactionId,
            acknowledgedGeometryRevision = committedBaseline.geometryRevision,
            acknowledgedLineageRevision = committedBaseline.lineageRevision,
            nextStyleRevision = committedBaseline.styleRevision,
            maximumResponseBytes = M0aPacketCodec.responseMinimumBytes,
            styleRecords = emptyList(),
            commandBytes = byteArrayOf(),
            requestSequence = Long.MAX_VALUE,
        )
        val bytes = M0aPacketCodec.encodeRequest(request)
        return synchronized(this) {
            check(lastSequence == null && synchronized(structuralFrames) { structuralFrames.isEmpty() })
            nextExpectedSequence = Long.MAX_VALUE
            check(isValidTerminalDrain(request))
            val encoded = M0aPacketCodec.encodeResponse(
                M0aPacketCodec.rolloverRequired(
                    streamToken = streamToken,
                    requestSequence = Long.MAX_VALUE,
                    transactionId = committedBaseline.transactionId,
                    targetGeometryRevision = committedBaseline.geometryRevision,
                    targetLineageRevision = committedBaseline.lineageRevision,
                    acceptedStyleRevision = committedBaseline.styleRevision,
                ),
                request.maximumResponseBytes,
            )
            lastSequence = Long.MAX_VALUE
            lastRequest = bytes
            lastResponse = encoded
            telemetry.retainedReplayCache(bytes.size, encoded.size)
            telemetry.accepted(bytes.size, encoded.size)
            M0aPacketCodec.decodeResponse(encoded)
        }
    }

    internal data class DebugQualifiedAttempt(
        val attemptCount: Long,
        val rejectionCount: Long,
        val semanticEffectCount: Long,
        val publicationCount: Long,
    )

    /**
     * Submits one debug-only transport attempt through the production binding
     * qualifier fence and reports observed effects rather than expected
     * constants. The payload is deliberately not decoded when qualification
     * fails, exactly as for the installed BasicMessageChannel handler.
     */
    internal fun submitDebugAttemptThroughInstalledHandler(
        transportBytes: ByteArray,
        completed: (DebugQualifiedAttempt) -> Unit,
    ) {
        val authorityBefore = synchronized(this) { committedBaseline }
        val telemetryBefore = telemetry.snapshot()
        handleStreamMessage(ByteBuffer.wrap(transportBytes)) { response ->
            val authorityAfter = synchronized(this) { committedBaseline }
            val telemetryAfter = telemetry.snapshot()
            completed(
                DebugQualifiedAttempt(
                    attemptCount = telemetryAfter.submittedRequests -
                        telemetryBefore.submittedRequests +
                        telemetryAfter.rejectedRequests - telemetryBefore.rejectedRequests,
                    rejectionCount = telemetryAfter.rejectedRequests -
                        telemetryBefore.rejectedRequests,
                    semanticEffectCount = if (authorityAfter == authorityBefore) 0 else 1,
                    publicationCount = if (response == null) 0 else 1,
                ),
            )
        }
    }

    /**
     * Queues one bounded structural transaction for worker-pull delivery.
     * Frames are consumed only after their response is encoded and accepted;
     * an exact request replay therefore never advances the producer.
     */
    fun queueStructuralTransaction(frames: List<M0aTransactionFrameV1>) {
        require(frames.isNotEmpty()) { "A structural transaction cannot be empty" }
        synchronized(this) {
            check(!disposed.get() && !bindingAbandoned.get()) { "Binding is abandoned" }
            validateStructuralTransaction(frames)
            synchronized(structuralFrames) {
                check(structuralFrames.isEmpty()) { "A structural transaction is already queued" }
                frames.forEach { structuralFrames.addLast(it) }
                telemetry.retainedStructuralStaging(structuralPayloadBytes())
            }
            val begin = (frames.first() as M0aTransactionBeginFrameV1).value
            queuedTransactionBaseline = committedBaseline.copy(
                transactionId = begin.transactionId,
                geometryRevision = begin.targetGeometryRevision,
                lineageRevision = begin.targetLineageRevision,
            )
        }
    }

    /** Restores the native committed baseline after a worker/binding restart. */
    fun setCommittedBaseline(
        transactionId: Long,
        geometryRevision: Long,
        lineageRevision: Long,
        styleRevision: Long,
        evidenceRevision: Long = 0,
        captureRevision: Long = 0,
        coverageRevision: Long = 0,
        producedStyleRevision: Long = 0,
        regionManifestRevision: Long = 0,
        schemaRootRevision: Long = 0,
        nextSurfaceIdHighWater: Long = 0,
        schemaRootHashIdentity: String = "",
        manifestRootHashIdentity: String = "",
        groupFrameConvention: Int = 1,
        matrixConvention: Int = 1,
        directionConvention: Int = 1,
        normalEncoding: Int = 1,
        groupFromWorldIdentity: String = M0A_IDENTITY_MATRIX_IDENTITY,
        worldFromGroupIdentity: String = M0A_IDENTITY_MATRIX_IDENTITY,
    ) {
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
        synchronized(this) {
            committedBaseline = M0aCommittedBaselineV1(
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
                schemaRootHashIdentity,
                manifestRootHashIdentity,
                groupFrameConvention,
                matrixConvention,
                directionConvention,
                normalEncoding,
                groupFromWorldIdentity,
                worldFromGroupIdentity,
            )
        }
    }

    /** Restores semantic authority into a fresh binding-scoped transaction domain. */
    fun setFreshBindingBaseline(value: M0aCommittedBaselineV1) {
        synchronized(this) {
            committedBaseline = M0aCommittedBaselineV1.forFreshBinding(value)
            controlLifecycle?.setCommittedBaseline(committedBaseline)
        }
    }

    private class BindingError(val errorId: Int) : IllegalArgumentException()

    init {
        metricsChannel.setMessageHandler { _, reply ->
            val summary = telemetry.tryEncodeBoundedSummary()
            reply.reply(summary?.let { bytes ->
                ByteBuffer.allocateDirect(bytes.size).apply { put(bytes) }
            })
        }
        channel.setMessageHandler(::handleStreamMessage)
    }

    private fun handleStreamMessage(
        message: ByteBuffer?,
        reply: BasicMessageChannel.Reply<ByteBuffer>,
    ) {
            val transportBytes = message?.let { buffer ->
                val copy = ByteArray(buffer.remaining())
                buffer.slice().get(copy)
                copy
            }
            if (transportBytes == null) {
                reply.reply(null)
                return
            }
            val bytes = authenticatedPayload(transportBytes)
            if (bytes == null) {
                telemetry.rejected()
                reply.reply(null)
                return
            }
            telemetry.submitted(bytes.size)
            telemetry.allocated(bytes.size)
            if (!outstandingInvocation.compareAndSet(false, true)) {
                telemetry.rejected()
                if (!queuedBackpressure.compareAndSet(false, true)) {
                    reply.reply(null)
                    return
                }
                telemetry.queued()
                try {
                    workerExecutor.execute { processQueuedBackpressure(bytes, reply) }
                } catch (_: RejectedExecutionException) {
                    queuedBackpressure.set(false)
                    outstandingInvocation.set(false)
                    reply.reply(null)
                }
                return
            }
            telemetry.queued()
            val pendingReply = PendingReply(bytes, reply, ::qualify) {
                if (!queuedBackpressure.get()) outstandingInvocation.set(false)
                activePendingReply = null
            }
            activePendingReply = pendingReply
            val timeoutHandle = timeoutScheduler.schedule(workerTimeoutMillis) {
                abandonForTimeout(pendingReply, bytes)
            }
            try {
                workerExecutor.execute {
                    telemetry.dequeued()
                    try {
                        var publicationClaimedReply = false
                        var commitPublicationStalled = false
                        val response = synchronized(this) {
                            onExecutorOperation?.invoke("exchange")
                            beforeWorkerProcessing?.invoke()
                            if (disposed.get()) {
                                if (pendingReply.tryClaim()) {
                                    workerLostResponseBytes(bytes)
                                } else {
                                    null
                                }
                            } else if (bindingAbandoned.get()) {
                                onAbandonedContinuation?.invoke()
                                null
                            } else {
                                var decodedRequest: M0aPacketCodec.Request? = null
                                val encoded = try {
                                    val request = M0aPacketCodec.decodeRequest(bytes)
                                    decodedRequest = request
                                    beforeRequestProcessing?.invoke(request)
                                    controlLifecycle?.streamTokenError(request.streamToken)?.let {
                                        throw BindingError(it)
                                    }
                                    val previousSequence = lastSequence
                                    when {
                                        previousSequence == request.requestSequence -> {
                                            if (!lastRequest!!.contentEquals(bytes)) {
                                                throw BindingError(REPLAY_CONFLICT_ERROR_ID)
                                            }
                                            telemetry.replayed()
                                            lastResponse!!.copyOf()
                                        }
                                        previousSequence != null && request.requestSequence <= previousSequence -> {
                                            throw BindingError(STALE_SEQUENCE_ERROR_ID)
                                        }
                                        request.requestSequence != nextExpectedSequence -> {
                                            throw BindingError(SEQUENCE_GAP_ERROR_ID)
                                        }
                                        resyncPending &&
                                            !isValidResyncRequest(request) -> {
                                            throw BindingError(TRANSACTION_STATE_ERROR_ID)
                                        }
                                        request.requestSequence == Long.MAX_VALUE &&
                                            !isValidTerminalDrain(request) -> {
                                            throw BindingError(STREAM_ROLLOVER_REQUIRED_ERROR_ID)
                                        }
                                        else -> {
                                            if (request.requestFlags and RESYNC_REQUEST_FLAG != 0 &&
                                                !isValidResyncRequest(request)) {
                                                throw BindingError(TRANSACTION_STATE_ERROR_ID)
                                            }
                                            val hasCommitFrame = synchronized(structuralFrames) {
                                                structuralFrames.peekFirst() is M0aTransactionCommitFrameV1
                                            }
                                            if (hasCommitFrame) {
                                                beforeAuthorityPublication?.invoke()
                                            }
                                            val encoded = synchronized(publicationFence) {
                                                if (disposed.get() || bindingAbandoned.get()) {
                                                    throw BindingError(STREAM_BINDING_ABANDONED_ERROR_ID)
                                                }
                                                val publishesCommit = synchronized(structuralFrames) {
                                                    structuralFrames.peekFirst() is M0aTransactionCommitFrameV1
                                                }
                                                if (publishesCommit) {
                                                    afterAuthorityPublicationFenceAcquired?.invoke()
                                                }
                                                controlLifecycle?.let {
                                                    committedBaseline = it.committedBaseline()
                                                }
                                                val requiresResync = requiresResync(request)
                                                val publicationBytes = M0aPacketCodec.encodeResponse(
                                                    when {
                                                    isValidTerminalDrain(request) -> {
                                                        M0aPacketCodec.rolloverRequired(
                                                            streamToken = request.streamToken,
                                                            requestSequence = request.requestSequence,
                                                            transactionId = committedBaseline.transactionId,
                                                            targetGeometryRevision = committedBaseline.geometryRevision,
                                                            targetLineageRevision = committedBaseline.lineageRevision,
                                                            acceptedStyleRevision = committedBaseline.styleRevision,
                                                        )
                                                    }
                                                    resyncPending -> {
                                                        if (transactionReceiver.state ==
                                                            M0aStructuralTransactionState.RESYNC_PENDING) {
                                                            transactionReceiver.resync(
                                                                M0aResyncCommandV1.decode(request.commandBytes),
                                                            )
                                                        }
                                                        resyncPending = false
                                                        M0aPacketCodec.noChanges(
                                                            streamToken = request.streamToken,
                                                            requestSequence = request.requestSequence,
                                                            nextExpectedRequestSequence = request.requestSequence + 1,
                                                            transactionId = committedBaseline.transactionId,
                                                            targetGeometryRevision = committedBaseline.geometryRevision,
                                                            targetLineageRevision = committedBaseline.lineageRevision,
                                                            acceptedStyleRevision = committedBaseline.styleRevision,
                                                        )
                                                    }
                                                    requiresResync -> {
                                                        resyncPending = true
                                                        M0aPacketCodec.resyncRequired(
                                                            streamToken = request.streamToken,
                                                            requestSequence = request.requestSequence,
                                                            nextExpectedRequestSequence = request.requestSequence + 1,
                                                        )
                                                    }
                                                    else -> {
                                                        if (request.styleRecords.isNotEmpty()) {
                                                            committedBaseline = committedBaseline.copy(
                                                                styleRevision = M0aStyleRevisionSemantics.committedRevision(
                                                                    committedBaseline.styleRevision,
                                                                    request.nextStyleRevision,
                                                                    true,
                                                                ),
                                                            )
                                                            controlLifecycle?.setCommittedBaseline(committedBaseline)
                                                        }
                                                        nextStructuralResponse(request)
                                                    }
                                                    },
                                                    request.maximumResponseBytes,
                                                )
                                                if (publishesCommit) {
                                                    if (!pendingReply.tryClaim()) {
                                                        throw BindingError(STREAM_BINDING_ABANDONED_ERROR_ID)
                                                    }
                                                    publicationClaimedReply = true
                                                    onCommitPublished?.invoke(request, committedBaseline)
                                                    commitPublicationStalled = true
                                                }
                                                publicationBytes
                                            }
                                            lastSequence = request.requestSequence
                                            nextExpectedSequence = if (request.requestSequence == Long.MAX_VALUE) {
                                                Long.MAX_VALUE
                                            } else {
                                                request.requestSequence + 1
                                            }
                                            lastRequest = bytes.copyOf()
                                            lastResponse = encoded.copyOf()
                                            telemetry.allocated(bytes.size + encoded.size)
                                            telemetry.retainedReplayCache(bytes.size, encoded.size)
                                            telemetry.accepted(bytes.size, encoded.size)
                                            encoded
                                        }
                                    }
                                } catch (error: BindingError) {
                                    telemetry.rejected()
                                    val sequence = if (bytes.size >= M0aPacketCodec.requestHeaderBytes) {
                                        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).getLong(64)
                                            .coerceAtLeast(0)
                                    } else {
                                        0
                                    }
                                    val token = decodedRequest?.streamToken ?: 0
                                    M0aPacketCodec.encodeResponse(
                                        streamError(
                                            streamToken = token,
                                            requestSequence = sequence,
                                            nextExpectedRequestSequence = nextExpectedSequence,
                                            errorId = error.errorId,
                                        ),
                                        M0aPacketCodec.responseMinimumBytes,
                                    )
                                } catch (_: Exception) {
                                    telemetry.malformed()
                                    val sequence = if (bytes.size >= M0aPacketCodec.requestHeaderBytes) {
                                        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).getLong(64)
                                            .coerceAtLeast(0)
                                    } else {
                                        0
                                    }
                                    val token = decodedRequest?.streamToken ?: 0
                                    M0aPacketCodec.encodeResponse(
                                        streamError(
                                            streamToken = token,
                                            requestSequence = sequence,
                                            nextExpectedRequestSequence = nextExpectedSequence,
                                            errorId = MALFORMED_PACKET_ERROR_ID,
                                        ),
                                        M0aPacketCodec.responseMinimumBytes,
                                    )
                                }
                                telemetry.allocated(encoded.size)
                                if (publicationClaimedReply || pendingReply.tryClaim()) encoded else null
                            }
                        }
                        if (commitPublicationStalled) afterCommitPublication?.invoke()
                        timeoutHandle.cancel()
                        if (response != null) {
                            telemetry.allocated(response.size)
                            // Flutter's Android messenger passes position() as the JNI
                            // message length; qualify() leaves it after the bytes.
                            reply.reply(qualify(response))
                        }
                    } catch (_: Exception) {
                        abandonForWorkerLoss(pendingReply, bytes)
                    } finally {
                        telemetry.completed()
                    }
                }
            } catch (_: RejectedExecutionException) {
                telemetry.dequeued()
                telemetry.completed()
                timeoutHandle.cancel()
                abandonForWorkerLoss(pendingReply, bytes)
            }
    }

    private fun authenticatedPayload(bytes: ByteArray): ByteArray? {
        val qualifier = bindingQualifier ?: return bytes
        if (bytes.size < qualifier.size ||
            !bytes.copyOfRange(0, qualifier.size).contentEquals(qualifier)) return null
        return bytes.copyOfRange(qualifier.size, bytes.size)
    }

    private fun qualify(buffer: ByteBuffer): ByteBuffer {
        val qualifier = bindingQualifier ?: return buffer
        val payload = ByteArray(buffer.position())
        buffer.duplicate().apply { flip(); get(payload) }
        return ByteBuffer.allocateDirect(qualifier.size + payload.size).apply {
            put(qualifier)
            put(payload)
        }
    }

    private fun qualify(bytes: ByteArray): ByteBuffer {
        val qualifier = bindingQualifier
        return ByteBuffer.allocateDirect((qualifier?.size ?: 0) + bytes.size).apply {
            if (qualifier != null) put(qualifier)
            put(bytes)
        }
    }

    fun dispose() {
        if (!disposed.compareAndSet(false, true)) return
        synchronized(publicationFence) { bindingAbandoned.set(true) }
        activePendingReply?.let { pendingReply ->
            if (pendingReply.tryClaim()) {
                pendingReply.reply(workerLostResponse(pendingReply.requestBytes))
            }
        }
        clearPreparedStaging()
        controlLifecycle?.abandon()
        transactionReceiver.stop()
        clearMessageHandler()
        if (shutdownWorkerOnDispose && workerExecutor is java.util.concurrent.ExecutorService) {
            workerExecutor.shutdownNow()
        }
        shutdownTimeoutScheduler()
    }

    /** Independently fences an admitted invocation without marking this
     * stream as normally disposed; the owner may install a fresh binding. */
    fun abandon() {
        if (disposed.get()) return
        val claim = claimAbandonFence(activePendingReply)
        if (!claim.won) return
        claim.pendingReply?.let { pendingReply ->
            onAbandonedRequest?.invoke(
                M0aPacketCodec.decodeRequest(pendingReply.requestBytes),
                pendingCommitBaseline(),
            )
        }
        claim.pendingReply?.let { pendingReply ->
            pendingReply.reply(workerAbandonedResponse(pendingReply.requestBytes))
        }
        clearPreparedStaging()
        transactionReceiver.abandon()
        controlLifecycle?.abandon()
        clearMessageHandler()
        if (shutdownWorkerOnDispose && workerExecutor is java.util.concurrent.ExecutorService) {
            workerExecutor.shutdownNow()
        }
        shutdownTimeoutScheduler()
    }

    private fun abandonForTimeout(pendingReply: PendingReply, bytes: ByteArray) {
        if (disposed.get()) {
            if (pendingReply.tryClaim()) pendingReply.reply(workerLostResponse(bytes))
            return
        }
        val claim = claimAbandonFence(pendingReply)
        if (!claim.won) return
        onAbandonedRequest?.invoke(M0aPacketCodec.decodeRequest(bytes), pendingCommitBaseline())
        telemetry.timedOut()
        clearPreparedStaging()
        transactionReceiver.abandon()
        controlLifecycle?.abandon()
        clearMessageHandler()
        if (shutdownWorkerOnDispose && workerExecutor is java.util.concurrent.ExecutorService) {
            workerExecutor.shutdownNow()
        }
        shutdownTimeoutScheduler()
        pendingReply.reply(workerAbandonedResponse(bytes))
    }

    private fun abandonForWorkerLoss(pendingReply: PendingReply, bytes: ByteArray) {
        val ownsReply = synchronized(publicationFence) {
            if (bindingAbandoned.get()) return
            val claimed = pendingReply.tryClaim()
            bindingAbandoned.set(true)
            claimed
        }
        if (ownsReply) {
            onAbandonedRequest?.invoke(M0aPacketCodec.decodeRequest(bytes), pendingCommitBaseline())
        }
        telemetry.workerLost()
        clearPreparedStaging()
        transactionReceiver.abandon()
        controlLifecycle?.abandon()
        clearMessageHandler()
        if (shutdownWorkerOnDispose && workerExecutor is java.util.concurrent.ExecutorService) {
            workerExecutor.shutdownNow()
        }
        shutdownTimeoutScheduler()
        if (ownsReply) pendingReply.reply(workerLostResponse(bytes))
    }

    private fun clearMessageHandler() {
        val clear = {
            channel.setMessageHandler(null)
            metricsChannel.setMessageHandler(null)
            handlersInstalled.set(false)
        }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            clear()
        } else {
            val completed = CountDownLatch(1)
            check(mainHandler.post {
                try {
                    clear()
                } finally {
                    completed.countDown()
                }
            }) { "Flutter main looper is unavailable." }
            try {
                check(completed.await(MAIN_HANDLER_CLEAR_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)) {
                    "Flutter main looper did not clear the binding handler."
                }
            } catch (error: InterruptedException) {
                Thread.currentThread().interrupt()
                throw IllegalStateException("Interrupted clearing binding handler.", error)
            }
        }
    }

    private fun shutdownTimeoutScheduler() {
        if (timeoutSchedulerActive.compareAndSet(true, false)) timeoutScheduler.shutdown()
    }

    private fun clearPreparedStaging() {
        lastRequest = null
        lastResponse = null
        resyncPending = false
        lastSequence = null
        nextExpectedSequence = 1L
        // The publication fence has already won. Do not reacquire the worker
        // monitor here: a stalled worker may still hold it while waiting for
        // its late continuation to be fenced.
        synchronized(structuralFrames) {
            structuralFrames.clear()
        }
        queuedTransactionBaseline = null
        telemetry.clearRetained()
    }

    private fun pendingCommitBaseline(): M0aCommittedBaselineV1 =
        queuedTransactionBaseline ?: committedBaseline

    private data class AbandonClaim(
        val won: Boolean,
        val pendingReply: PendingReply?,
    )

    private fun claimAbandonFence(pendingReply: PendingReply?): AbandonClaim =
        synchronized(publicationFence) {
            if (bindingAbandoned.get() ||
                (pendingReply != null && !pendingReply.tryClaim())
            ) {
                return@synchronized AbandonClaim(false, null)
            }
            bindingAbandoned.set(true)
            AbandonClaim(true, pendingReply)
    }

    private fun isValidResyncRequest(request: M0aPacketCodec.Request): Boolean {
        if (request.requestFlags != RESYNC_REQUEST_FLAG || request.styleRecords.isNotEmpty()) {
            return false
        }
        val command = runCatching { M0aResyncCommandV1.decode(request.commandBytes) }
            .getOrNull() ?: return false
        val payload = command.payload
        return payload.lastCommittedTransactionId == request.acknowledgedTransactionId &&
            payload.lastCommittedGeometryRevision == request.acknowledgedGeometryRevision &&
            payload.lastCommittedLineageRevision == request.acknowledgedLineageRevision
    }

    private fun isValidTerminalDrain(request: M0aPacketCodec.Request): Boolean =
        request.requestSequence == Long.MAX_VALUE &&
            request.requestFlags == TERMINAL_DRAIN_REQUEST_FLAG &&
            request.styleRecords.isEmpty() &&
            request.commandBytes.isEmpty() &&
            request.nextStyleRevision == committedBaseline.styleRevision &&
            request.acknowledgedTransactionId == committedBaseline.transactionId &&
            request.acknowledgedGeometryRevision == committedBaseline.geometryRevision &&
            request.acknowledgedLineageRevision == committedBaseline.lineageRevision &&
            synchronized(structuralFrames) { structuralFrames.isEmpty() }

    private fun requiresResync(request: M0aPacketCodec.Request): Boolean {
        val structuralAcknowledgement =
            (request.acknowledgedTransactionId != 0L ||
                request.acknowledgedGeometryRevision != 0L ||
                request.acknowledgedLineageRevision != 0L ||
                committedBaseline.transactionId != 0L ||
                committedBaseline.geometryRevision != 0L ||
                committedBaseline.lineageRevision != 0L)
        val structuralMismatch = structuralAcknowledgement &&
            (request.acknowledgedTransactionId != committedBaseline.transactionId ||
                request.acknowledgedGeometryRevision != committedBaseline.geometryRevision ||
                request.acknowledgedLineageRevision != committedBaseline.lineageRevision)
        val styleMismatch = !M0aStyleRevisionSemantics.accepts(
            committedBaseline.styleRevision,
            request.nextStyleRevision,
            request.styleRecords.isNotEmpty(),
        )
        return structuralMismatch || styleMismatch
    }

    private fun nextStructuralResponse(request: M0aPacketCodec.Request): M0aPacketCodec.Response {
        synchronized(this) {
            synchronized(structuralFrames) {
                val frame = structuralFrames.peekFirst()
                    ?: return M0aPacketCodec.noChanges(
                        streamToken = request.streamToken,
                        requestSequence = request.requestSequence,
                        nextExpectedRequestSequence = request.requestSequence + 1,
                        transactionId = committedBaseline.transactionId,
                        targetGeometryRevision = committedBaseline.geometryRevision,
                        targetLineageRevision = committedBaseline.lineageRevision,
                        acceptedStyleRevision = committedBaseline.styleRevision,
                    )
                val response = M0aTransactionResponseCodecV1.encodeFrame(
                    frame = frame,
                    streamToken = request.streamToken,
                    requestSequence = request.requestSequence,
                    nextExpectedRequestSequence = request.requestSequence + 1,
                ).copy(acceptedStyleRevision = committedBaseline.styleRevision)
                // The caller encodes this response under the negotiated ceiling
                // before returning. Only then is the producer advanced.
                val encoded = M0aPacketCodec.encodeResponse(response, request.maximumResponseBytes)
                check(encoded.isNotEmpty())
                structuralFrames.removeFirst()
                telemetry.retainedStructuralStaging(structuralPayloadBytes())
                if (frame is M0aTransactionCommitFrameV1) {
                    committedBaseline = queuedTransactionBaseline
                        ?.copy(styleRevision = committedBaseline.styleRevision)
                        ?: error("COMMIT has no queued transaction baseline")
                    queuedTransactionBaseline = null
                    controlLifecycle?.setCommittedBaseline(committedBaseline)
                }
                return response
            }
        }
    }

    private fun validateStructuralTransaction(frames: List<M0aTransactionFrameV1>) {
        require(frames.size <= MAX_STRUCTURAL_FRAMES) { "Structural transaction is too large" }
        val begin = (frames.firstOrNull() as? M0aTransactionBeginFrameV1)?.value
            ?: error("Structural transaction must begin with BEGIN")
        val commit = (frames.lastOrNull() as? M0aTransactionCommitFrameV1)?.value
            ?: error("Structural transaction must end with COMMIT")
        require(committedBaseline.transactionId < Long.MAX_VALUE) {
            "Native transaction allocation requires binding rollover"
        }
        require(
            begin.transactionId == committedBaseline.transactionId + 1 &&
                begin.baseGeometryRevision == committedBaseline.geometryRevision &&
                begin.targetGeometryRevision > begin.baseGeometryRevision &&
                begin.targetLineageRevision > 0 &&
                begin.targetLineageRevision >= committedBaseline.lineageRevision &&
                commit.transactionId == begin.transactionId,
        ) { "Structural transaction does not advance the committed cursor" }
        require(frames.size == begin.chunkCount + 2)
        require(begin.totalBytes in 0..M0aPacketCodec.requestCeilingBytes)
        require(begin.chunkCount in 0..0xffff)
        var totalBytes = 0
        frames.drop(1).dropLast(1).forEachIndexed { index, frame ->
            val chunk = (frame as? M0aTransactionChunkFrameV1)?.value
                ?: error("Structural transaction contains a non-CHUNK frame")
            require(chunk.transactionId == begin.transactionId && chunk.chunkIndex == index)
            require(chunk.bytes.isNotEmpty())
            require(chunk.offset == null || chunk.offset == totalBytes)
            totalBytes += chunk.bytes.size
            require(totalBytes <= begin.totalBytes)
        }
        require(totalBytes == begin.totalBytes)
        require(commit.payloadChecksum == begin.payloadChecksum)
        require(M0aTransactionResponseCodecV1.payloadChecksum(
            frames.drop(1).dropLast(1).flatMap { (it as M0aTransactionChunkFrameV1).value.bytes.toList() }.toByteArray(),
        ) == begin.payloadChecksum)
    }

    private fun structuralPayloadBytes(): Int = synchronized(structuralFrames) {
        structuralFrames.sumOf { frame ->
            when (frame) {
                is M0aTransactionChunkFrameV1 -> frame.value.bytes.size
                else -> 0
            }
        }
    }

    private class PendingReply(
        val requestBytes: ByteArray,
        private val callback: BasicMessageChannel.Reply<ByteBuffer>,
        private val qualify: (ByteBuffer) -> ByteBuffer,
        private val onClaimed: () -> Unit,
    ) {
        private val claimed = AtomicBoolean(false)

        fun tryClaim(): Boolean {
            val ownsReply = claimed.compareAndSet(false, true)
            if (ownsReply) onClaimed()
            return ownsReply
        }

        fun reply(buffer: ByteBuffer) {
            callback.reply(qualify(buffer))
        }
    }

    private fun workerAbandonedResponse(bytes: ByteArray): ByteBuffer {
        val sequence = if (bytes.size >= M0aPacketCodec.requestHeaderBytes) {
            ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).getLong(64).coerceAtLeast(0)
        } else {
            0
        }
        val token = runCatching { M0aPacketCodec.decodeRequest(bytes).streamToken }.getOrDefault(0)
        val encoded = M0aPacketCodec.encodeResponse(
            streamError(
                streamToken = token,
                requestSequence = sequence,
                nextExpectedRequestSequence = nextExpectedSequence,
                errorId = STREAM_BINDING_ABANDONED_ERROR_ID,
            ),
            M0aPacketCodec.responseMinimumBytes,
        )
        return ByteBuffer.allocateDirect(encoded.size).apply { put(encoded) }
    }

    /** Serializes the consumed ID8 transition behind the invocation that caused pressure. */
    private fun processQueuedBackpressure(
        bytes: ByteArray,
        reply: BasicMessageChannel.Reply<ByteBuffer>,
    ) {
        telemetry.dequeued()
        try {
            val encoded = synchronized(this) {
                check(!disposed.get() && !bindingAbandoned.get()) {
                    "Queued backpressure request belongs to an abandoned binding"
                }
                val request = M0aPacketCodec.decodeRequest(bytes)
                controlLifecycle?.streamTokenError(request.streamToken)?.let { throw BindingError(it) }
                require(request.requestSequence == nextExpectedSequence) {
                    "Queued backpressure request must advertise the next sequence"
                }
                val response = M0aPacketCodec.encodeResponse(
                    streamError(
                        streamToken = request.streamToken,
                        requestSequence = request.requestSequence,
                        nextExpectedRequestSequence = nextExpectedSequence,
                        errorId = STREAM_BACKPRESSURE_ERROR_ID,
                    ),
                    M0aPacketCodec.responseMinimumBytes,
                )
                resyncPending = true
                lastSequence = request.requestSequence
                nextExpectedSequence = request.requestSequence + 1
                lastRequest = bytes.copyOf()
                lastResponse = response.copyOf()
                telemetry.retainedReplayCache(bytes.size, response.size)
                telemetry.accepted(bytes.size, response.size)
                response
            }
            telemetry.allocated(bytes.size + encoded.size)
            reply.reply(qualify(encoded))
        } catch (_: Exception) {
            reply.reply(null)
        } finally {
            queuedBackpressure.set(false)
            outstandingInvocation.set(false)
            telemetry.completed()
        }
    }

    private fun workerLostResponse(bytes: ByteArray): ByteBuffer {
        val encoded = workerLostResponseBytes(bytes)
        return ByteBuffer.allocateDirect(encoded.size).apply { put(encoded) }
    }

    private fun workerLostResponseBytes(bytes: ByteArray): ByteArray {
        val sequence = if (bytes.size >= M0aPacketCodec.requestHeaderBytes) {
            ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).getLong(64).coerceAtLeast(0)
        } else {
            0
        }
        val token = runCatching { M0aPacketCodec.decodeRequest(bytes).streamToken }.getOrDefault(0)
        val encoded = M0aPacketCodec.encodeResponse(
            streamError(
                streamToken = token,
                requestSequence = sequence,
                nextExpectedRequestSequence = nextExpectedSequence,
                errorId = WORKER_BINDING_LOST_ERROR_ID,
            ),
            M0aPacketCodec.responseMinimumBytes,
        )
        return encoded
    }

    private fun streamError(
        streamToken: Long,
        requestSequence: Long,
        nextExpectedRequestSequence: Long,
        errorId: Int,
    ): M0aPacketCodec.Response {
        val baseline = committedBaseline
        return M0aPacketCodec.error(
            streamToken = streamToken,
            requestSequence = requestSequence,
            nextExpectedRequestSequence = nextExpectedRequestSequence,
            errorId = errorId,
            authority = M0aPacketCodec.ErrorAuthority(
                geometryRevision = baseline.geometryRevision,
                lineageRevision = baseline.lineageRevision,
                captureRevision = baseline.captureRevision,
                coverageRevision = baseline.coverageRevision,
                acceptedStyleRevision = baseline.styleRevision,
                regionManifestRevision = baseline.regionManifestRevision,
                nextSurfaceIdHighWater = baseline.nextSurfaceIdHighWater,
                schemaRootRevision = baseline.schemaRootRevision,
            ),
        )
    }

    private companion object {
        const val MALFORMED_PACKET_ERROR_ID = 6
        const val STREAM_BACKPRESSURE_ERROR_ID = 8
        const val REPLAY_CONFLICT_ERROR_ID = 30
        const val STALE_SEQUENCE_ERROR_ID = 31
        const val SEQUENCE_GAP_ERROR_ID = 32
        const val TRANSACTION_STATE_ERROR_ID = 34
        const val STREAM_ROLLOVER_REQUIRED_ERROR_ID = 35
        const val RESYNC_REQUEST_FLAG = 1 shl 2
        const val TERMINAL_DRAIN_REQUEST_FLAG = 1 shl 5
        const val STREAM_BINDING_ABANDONED_ERROR_ID = 142
        const val WORKER_BINDING_LOST_ERROR_ID = 144
        const val DEFAULT_WORKER_TIMEOUT_MILLIS = 2_000L
        const val MAIN_HANDLER_CLEAR_TIMEOUT_MILLIS = 2_000L
        const val MAX_STRUCTURAL_FRAMES = 18
    }
}
