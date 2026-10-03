package com.uhg0.ar_flutter_plugin_2.visibilityprotocol

import android.os.Handler
import android.os.Looper
import com.uhg0.ar_flutter_plugin_2.visibilityprotocol.A_IDENTITY_MATRIX_IDENTITY
import com.uhg0.ar_flutter_plugin_2.visibilityprotocol.CommittedBaselineV1
import com.uhg0.ar_flutter_plugin_2.visibilityprotocol.ControlLifecycle
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
import java.util.zip.CRC32

/** Small seam so lifecycle deadlines are deterministic in the JVM corpus. */
fun interface TimeoutHandle {
    fun cancel()
}

interface TimeoutScheduler {
    fun schedule(delayMillis: Long, task: () -> Unit): TimeoutHandle

    fun shutdown()

    companion object {
        fun real(): TimeoutScheduler = ExecutorTimeoutScheduler()
    }
}

private class ExecutorTimeoutScheduler(
    private val executor: ScheduledExecutorService =
        Executors.newSingleThreadScheduledExecutor(),
) : TimeoutScheduler {
    override fun schedule(delayMillis: Long, task: () -> Unit): TimeoutHandle {
        val future = executor.schedule(task, delayMillis, TimeUnit.MILLISECONDS)
        return TimeoutHandle { future.cancel(false) }
    }

    override fun shutdown() {
        executor.shutdownNow()
    }
}

/**
 * visibility protocol's real per-view T2 binding seam.
 *
 * The channel accepts only packed bytes, executes one request at a time on a
 * serial worker, and caches the exact response for a duplicate sequence. It
 * intentionally exposes no V1 maps or native surface arrays.
 */
class DebugTransportProbe(
    private val expectedBytes: ByteArray,
    private val authorityBefore: CommittedBaselineV1,
) {
    private var ingress = 0L
    private var rejections = 0L
    private var publications = 0L

    @Synchronized fun ingress(bytes: ByteArray): Boolean =
        expectedBytes.contentEquals(bytes).also { if (it) ingress++ }

    @Synchronized fun rejected() { rejections++ }
    @Synchronized fun published() { publications++ }

    @Synchronized fun receipt(authorityAfter: CommittedBaselineV1) = mapOf(
        "oldTokenAttemptCount" to ingress,
        "oldTokenRejectionCount" to rejections,
        "semanticEffectCount" to if (authorityAfter == authorityBefore) 0L else 1L,
        "oldTokenPublicationCount" to publications,
    )
}

class VisibilitySurfaceStreamChannel(
    messenger: BinaryMessenger,
    viewId: Int,
    private val workerExecutor: Executor = Executors.newSingleThreadExecutor(),
    private val shutdownWorkerOnDispose: Boolean = true,
    private val workerTimeoutMillis: Long = DEFAULT_WORKER_TIMEOUT_MILLIS,
    private val timeoutScheduler: TimeoutScheduler = TimeoutScheduler.real(),
    private val beforeWorkerProcessing: (() -> Unit)? = null,
    private val beforeRequestProcessing: ((PacketCodec.Request) -> Unit)? = null,
    private val controlLifecycle: ControlLifecycle? = null,
    private val onExecutorOperation: ((String) -> Unit)? = null,
    private val bindingQualifier: ByteArray? = null,
    private val beforeAuthorityPublication: (() -> Unit)? = null,
    private val afterAuthorityPublicationFenceAcquired: (() -> Unit)? = null,
    private val afterCommitPublication: (() -> Unit)? = null,
    private val onCommitPublished: ((PacketCodec.Request, CommittedBaselineV1) -> Unit)? = null,
    private val onAbandonedRequest: ((PacketCodec.Request, CommittedBaselineV1) -> Unit)? = null,
    private val onAbandonedContinuation: (() -> Unit)? = null,
    initialNextExpectedSequence: Long = 1L,
    private val debugTransportProbe: DebugTransportProbe? = null,
    private val onStructuralTransactionAcknowledged: ((CommittedBaselineV1) -> Unit)? = null,
    private val admitRendererStylePage: ((RendererStyleCommandV1.Page) -> Boolean)? = null,
    private val onRendererStyleCut: ((RendererStyleCutPayloadV1) -> RendererStyleCommandApplyResultV1)? = null,
    private val onCommittedBaselineAdvanced:
        ((CommittedBaselineV1, CommittedBaselineV1) -> Unit)? = null,
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
    private val transactionReceiver = StructuralTransactionReceiverV1()
    private val rendererStyleStaging = RendererStyleCommandStagingV1(
        bindingIdentity = bindingQualifier ?: byteArrayOf(),
        scheduleExpiry = { delayMillis, task -> timeoutScheduler.schedule(delayMillis, task) },
    )
    private val telemetry = TransportInstrumentation()
    private val structuralFrames = ArrayDeque<TransactionFrameV1>()
    private var structuralFrameCursor = 0
    private var queuedResponseProfile: TransactionResponseProfileV1? = null
    /**
     * A renderer cut that has started owns the next few stream turns.  A
     * queued structural frame remains retained until the style pages and the
     * final empty acknowledgement have completed.  Without this small lease,
     * each style continuation can return the next geometry frame and a busy
     * observation producer can starve the picture cut indefinitely.
     */
    @Volatile private var rendererStylePriority = false
    @Volatile private var rendererStyleAcknowledgementPending = false
    @Volatile private var committedBaseline =
        controlLifecycle?.committedBaseline() ?: CommittedBaselineV1.ZERO
    @Volatile private var queuedTransactionBaseline: CommittedBaselineV1? = null

    /** The bounded begin/chunk/commit seam owned by this binding. */
    val structuralTransactionReceiver: StructuralTransactionReceiverV1
        get() = transactionReceiver

    /** Numeric telemetry for this packed binding; no surface arrays are exposed. */
    val transportInstrumentation: TransportInstrumentation
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
    internal fun executeDebugTerminalDrain(streamToken: Long): PacketCodec.Response {
        val request = PacketCodec.Request(
            requestFlags = TERMINAL_DRAIN_REQUEST_FLAG,
            streamToken = streamToken,
            acknowledgedTransactionId = committedBaseline.transactionId,
            acknowledgedGeometryRevision = committedBaseline.geometryRevision,
            acknowledgedLineageRevision = committedBaseline.lineageRevision,
            nextStyleRevision = committedBaseline.styleRevision,
            maximumResponseBytes = PacketCodec.responseMinimumBytes,
            styleRecords = emptyList(),
            commandBytes = byteArrayOf(),
            requestSequence = Long.MAX_VALUE,
        )
        val bytes = PacketCodec.encodeRequest(request)
        return synchronized(this) {
            check(lastSequence == null && synchronized(structuralFrames) { structuralFrames.isEmpty() })
            nextExpectedSequence = Long.MAX_VALUE
            check(isValidTerminalDrain(request))
            val encoded = PacketCodec.encodeResponse(
                PacketCodec.rolloverRequired(
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
            PacketCodec.decodeResponse(encoded)
        }
    }

    internal fun debugProbeReceipt() =
        debugTransportProbe?.receipt(synchronized(this) { committedBaseline })

    /**
     * A binding-side admission guard: terminal drain consumes the portable
     * request sequence, so a later transaction must wait for a fresh binding.
     */
    internal fun canQueueStructuralTransaction(): Boolean = synchronized(this) {
        !disposed.get() && !bindingAbandoned.get() &&
            lastSequence != Long.MAX_VALUE && nextExpectedSequence != Long.MAX_VALUE
    }

    /**
     * Compares an in-flight retry against the sole stream-owned current bytes
     * without making another payload copy or exposing frame storage.
     */
    internal fun hasExactQueuedCurrentDelta(
        receipt: CurrentDeltaReceiptV1,
    ): Boolean = synchronized(structuralFrames) {
        val selector = receipt.selector
        val begin = (structuralFrames.firstOrNull() as? TransactionBeginFrameV1)?.value
            ?: return@synchronized false
        if (begin.transactionId != selector.transactionId ||
            begin.baseGeometryRevision != receipt.baseGeometryRevision ||
            begin.targetGeometryRevision != selector.targetGeometryRevision ||
            begin.targetLineageRevision != selector.targetLineageRevision ||
            begin.totalBytes != receipt.byteCount
        ) return@synchronized false
        var offset = 0
        for (frame in structuralFrames) {
            if (frame !is TransactionChunkFrameV1) continue
            val chunk = frame.value
            if (!receipt.matchesPayloadRange(offset, chunk.bytes)) return@synchronized false
            offset += chunk.bytes.size
        }
        offset == receipt.byteCount
    }

    /**
     * Queues one bounded structural transaction for worker-pull delivery.
     * Frames are consumed only after their response is encoded and accepted;
     * an exact request replay therefore never advances the producer.
     */
    fun queueStructuralTransaction(
        frames: List<TransactionFrameV1>,
        responseProfile: TransactionResponseProfileV1,
    ) = queueStructuralTransaction(frames, responseProfile, snapshotForeignFrames = true)

    private fun queueStructuralTransaction(
        frames: List<TransactionFrameV1>,
        responseProfile: TransactionResponseProfileV1,
        snapshotForeignFrames: Boolean,
    ) {
        require(frames.isNotEmpty()) { "A structural transaction cannot be empty" }
        synchronized(this) {
            check(!disposed.get() && !bindingAbandoned.get()) { "Binding is abandoned" }
            validateStructuralTransaction(frames, responseProfile)
            synchronized(structuralFrames) {
                check(structuralFrames.isEmpty()) { "A structural transaction is already queued" }
                val retainedFrames = if (snapshotForeignFrames) frames.map(::copyStructuralFrame) else frames
                retainedFrames.forEach { structuralFrames.addLast(it) }
                structuralFrameCursor = 0
                queuedResponseProfile = responseProfile
                telemetry.retainedStructuralStaging(structuralPayloadBytes())
            }
            val begin = (frames.first() as TransactionBeginFrameV1).value
            queuedTransactionBaseline = committedBaseline.copy(
                transactionId = begin.transactionId,
                geometryRevision = begin.targetGeometryRevision,
                lineageRevision = begin.targetLineageRevision,
            )
        }
    }

    /** Selects one immutable receipt and transfers freshly produced frames to the queue. */
    fun queueCurrentDelta(
        source: CurrentDeltaSourceV1,
        selector: CurrentDeltaSelectorV1,
        responseProfile: TransactionResponseProfileV1,
    ) {
        val receipt = requireNotNull(source.selectCurrentDelta(selector)) {
            "The named current delta is not retained"
        }
        require(receipt.selector == selector) { "Current-delta source returned a different receipt" }
        // These new chunk arrays have never crossed a caller boundary. Their
        // only owner becomes this queue, which retains them through exact ACK.
        queueStructuralTransaction(
            receipt.produceFrames(responseProfile),
            responseProfile,
            snapshotForeignFrames = false,
        )
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
        groupFromWorldIdentity: String = A_IDENTITY_MATRIX_IDENTITY,
        worldFromGroupIdentity: String = A_IDENTITY_MATRIX_IDENTITY,
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
            committedBaseline = CommittedBaselineV1(
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
    fun setFreshBindingBaseline(value: CommittedBaselineV1) {
        synchronized(this) {
            committedBaseline = CommittedBaselineV1.forFreshBinding(value)
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
            val correlatedDebugAttempt = debugTransportProbe?.ingress(transportBytes) == true
            val bytes = authenticatedPayload(transportBytes)
            if (bytes == null) {
                telemetry.rejected()
                if (correlatedDebugAttempt) debugTransportProbe.rejected()
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
                        var acknowledgedStructuralBaseline: CommittedBaselineV1? = null
                        // Owner telemetry can take the binding monitor. Keep it
                        // outside the stream monitor because current-delta
                        // publication acquires those monitors in the opposite
                        // order (binding, then stream).
                        onExecutorOperation?.invoke("exchange")
                        val response = synchronized(this) {
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
                                var decodedRequest: PacketCodec.Request? = null
                                var rendererStyleCommand: RendererStyleCommandV1.Page? = null
                                val encoded = try {
                                    val request = PacketCodec.decodeRequest(bytes)
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
                                            lastResponse!!
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
                                            val styleAcknowledgementRequest =
                                                rendererStylePriority &&
                                                    rendererStyleAcknowledgementPending &&
                                                    request.commandBytes.isEmpty() &&
                                                    request.nextStyleRevision == committedBaseline.styleRevision
                                            val hasCommitFrame =
                                                nextStructuralFrame() is TransactionCommitFrameV1 &&
                                                    request.commandBytes.isEmpty() &&
                                                    !styleAcknowledgementRequest
                                            if (hasCommitFrame) {
                                                beforeAuthorityPublication?.invoke()
                                            }
                                            val encoded = synchronized(publicationFence) {
                                                if (disposed.get() || bindingAbandoned.get()) {
                                                    throw BindingError(STREAM_BINDING_ABANDONED_ERROR_ID)
                                                }
                                                var publishesCommit = false
                                                controlLifecycle?.let {
                                                    committedBaseline = it.committedBaseline()
                                                }
                                                rendererStyleCommand = if (
                                                    request.commandBytes.isNotEmpty() &&
                                                        request.requestFlags and RESYNC_REQUEST_FLAG == 0
                                                ) {
                                                    if (request.styleRecords.isNotEmpty() ||
                                                        !RendererStyleCommandV1.isKind(request.commandBytes)
                                                    ) {
                                                        throw BindingError(MALFORMED_PACKET_ERROR_ID)
                                                    }
                                                    // Decode before the structural ACK, but defer all style
                                                    // admission until after that ACK has cleared the previous
                                                    // renderer staging. A style page can describe the current
                                                    // style revision on the newly acknowledged geometry cut.
                                                    RendererStyleCommandV1.decode(request.commandBytes)
                                                } else {
                                                    null
                                                }
                                                val requiresResync = requiresResync(request, rendererStyleCommand)
                                                if (!requiresResync) {
                                                    acknowledgedStructuralBaseline =
                                                        acknowledgePendingStructuralTransaction(request)
                                                }
                                                rendererStyleCommand?.let { page ->
                                                    val acknowledgedCutMatches =
                                                        page.transactionId == committedBaseline.transactionId &&
                                                            page.geometryRevision == committedBaseline.geometryRevision &&
                                                            page.lineageRevision == committedBaseline.lineageRevision &&
                                                            (acknowledgedStructuralBaseline != null ||
                                                                (request.acknowledgedTransactionId ==
                                                                    committedBaseline.transactionId &&
                                                                    request.acknowledgedGeometryRevision ==
                                                                        committedBaseline.geometryRevision &&
                                                                    request.acknowledgedLineageRevision ==
                                                                        committedBaseline.lineageRevision &&
                                                                    request.nextStyleRevision ==
                                                                        committedBaseline.styleRevision))
                                                    if (admitRendererStylePage?.invoke(page) == false ||
                                                        request.nextStyleRevision != page.styleRevision ||
                                                        !rendererStyleStaging.canAccept(
                                                            page,
                                                            committedBaseline.styleRevision,
                                                            allowCurrentRevisionForGeometryCut = acknowledgedCutMatches,
                                                        )
                                                    ) {
                                                        throw BindingError(TRANSACTION_STATE_ERROR_ID)
                                                    }
                                                }
                                                val styleMatchesCommittedBaseline = rendererStyleCommand?.let { page ->
                                                    page.transactionId == committedBaseline.transactionId &&
                                                        page.geometryRevision == committedBaseline.geometryRevision &&
                                                        page.lineageRevision == committedBaseline.lineageRevision
                                                } == true
                                                val stylePageNeedsPriority = rendererStyleCommand?.let { page ->
                                                    page.pageCount > 1 || page.pageIndex > 0
                                                } == true
                                                val deferStructuralForStyle =
                                                    !requiresResync &&
                                                        ((styleMatchesCommittedBaseline && stylePageNeedsPriority) ||
                                                            (rendererStylePriority &&
                                                                rendererStyleAcknowledgementPending &&
                                                                request.commandBytes.isEmpty() &&
                                                                request.nextStyleRevision == committedBaseline.styleRevision))
                                                publishesCommit =
                                                    nextStructuralFrame() is TransactionCommitFrameV1 &&
                                                        !deferStructuralForStyle
                                                if (publishesCommit) {
                                                    afterAuthorityPublicationFenceAcquired?.invoke()
                                                }
                                                val publicationBytes = PacketCodec.encodeResponse(
                                                    when {
                                                    isValidTerminalDrain(request) -> {
                                                        PacketCodec.rolloverRequired(
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
                                                            StructuralTransactionState.RESYNC_PENDING) {
                                                            transactionReceiver.resync(
                                                                ResyncCommandV1.decode(request.commandBytes),
                                                            )
                                                        }
                                                        resyncPending = false
                                                        PacketCodec.noChanges(
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
                                                        PacketCodec.resyncRequired(
                                                            streamToken = request.streamToken,
                                                            requestSequence = request.requestSequence,
                                                            nextExpectedRequestSequence = request.requestSequence + 1,
                                                        )
                                                    }
                                                    else -> {
                                                        // Prove the complete response path before a final style page
                                                        // can invoke its irreversible renderer callback. The style
                                                        // lease may defer returning that frame, but it still proves
                                                        // that the retained structural response fits this request.
                                                        validateNextStructuralResponse(request)
                                                        rendererStyleCommand?.let { page ->
                                                            if (styleMatchesCommittedBaseline && stylePageNeedsPriority) {
                                                                rendererStylePriority = true
                                                                if (page.pageIndex == 0) {
                                                                    rendererStyleAcknowledgementPending = false
                                                                }
                                                            }
                                                            when (val staged = rendererStyleStaging.accept(page)) {
                                                                is RendererStyleCommandStagingV1.Result.Progress -> Unit
                                                                is RendererStyleCommandStagingV1.Result.Complete -> {
                                                                    rendererStyleAcknowledgementPending =
                                                                        styleMatchesCommittedBaseline &&
                                                                            stylePageNeedsPriority
                                                                    val applied = onRendererStyleCut?.invoke(staged.cut)
                                                                        ?: throw IllegalStateException(
                                                                            "Renderer-style owner is unavailable",
                                                                        )
                                                                    require(
                                                                        applied.acceptedStyleRevision ==
                                                                            staged.cut.styleRevision,
                                                                    ) {
                                                                        "Renderer-style callback returned a non-deterministic revision"
                                                                    }
                                                                    committedBaseline = committedBaseline.copy(
                                                                        styleRevision = applied.acceptedStyleRevision,
                                                                    )
                                                                    controlLifecycle?.setCommittedBaselineLocally(
                                                                        committedBaseline,
                                                                    )
                                                                }
                                                            }
                                                        }
                                                        if (request.styleRecords.isNotEmpty()) {
                                                            val previousBaseline = committedBaseline
                                                            committedBaseline = committedBaseline.copy(
                                                                styleRevision = StyleRevisionSemantics.committedRevision(
                                                                    committedBaseline.styleRevision,
                                                                    request.nextStyleRevision,
                                                                    true,
                                                                ),
                                                            )
                                                            onCommittedBaselineAdvanced?.invoke(
                                                                previousBaseline,
                                                                committedBaseline,
                                                            )
                                                            controlLifecycle?.setCommittedBaselineLocally(
                                                                committedBaseline,
                                                            )
                                                        }
                                                        if (deferStructuralForStyle) {
                                                            if (rendererStyleAcknowledgementPending &&
                                                                request.commandBytes.isEmpty()) {
                                                                rendererStyleAcknowledgementPending = false
                                                                rendererStylePriority = false
                                                            }
                                                            PacketCodec.noChanges(
                                                                streamToken = request.streamToken,
                                                                requestSequence = request.requestSequence,
                                                                nextExpectedRequestSequence = request.requestSequence + 1,
                                                                transactionId = committedBaseline.transactionId,
                                                                targetGeometryRevision = committedBaseline.geometryRevision,
                                                                targetLineageRevision = committedBaseline.lineageRevision,
                                                                acceptedStyleRevision = committedBaseline.styleRevision,
                                                            )
                                                        } else {
                                                            nextStructuralResponse(request)
                                                        }
                                                    }
                                                    },
                                                    request.maximumResponseBytes,
                                                )
                                                if (publishesCommit) {
                                                    if (!pendingReply.tryClaim()) {
                                                        throw BindingError(STREAM_BINDING_ABANDONED_ERROR_ID)
                                                    }
                                                    publicationClaimedReply = true
                                                    onCommitPublished?.invoke(request, pendingCommitBaseline())
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
                                            // Ingress owns bytes; qualify() copies the reply into
                                            // platform storage. Neither private array is writable
                                            // by the messenger, so replay needs no second clone.
                                            lastRequest = bytes
                                            lastResponse = encoded
                                            telemetry.retainedReplayCache(bytes.size, encoded.size)
                                            telemetry.accepted(bytes.size, encoded.size)
                                            encoded
                                        }
                                    }
                                } catch (error: BindingError) {
                                    rendererStyleStaging.clear()
                                    rendererStylePriority = false
                                    rendererStyleAcknowledgementPending = false
                                    telemetry.rejected()
                                    val sequence = if (bytes.size >= PacketCodec.requestHeaderBytes) {
                                        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).getLong(64)
                                            .coerceAtLeast(0)
                                    } else {
                                        0
                                    }
                                    val token = decodedRequest?.streamToken ?: 0
                                    PacketCodec.encodeResponse(
                                        streamError(
                                            streamToken = token,
                                            requestSequence = sequence,
                                            nextExpectedRequestSequence = nextExpectedSequence,
                                            errorId = error.errorId,
                                        ),
                                        PacketCodec.responseMinimumBytes,
                                    )
                                } catch (_: Exception) {
                                    rendererStyleStaging.clear()
                                    rendererStylePriority = false
                                    rendererStyleAcknowledgementPending = false
                                    telemetry.malformed()
                                    val sequence = if (bytes.size >= PacketCodec.requestHeaderBytes) {
                                        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).getLong(64)
                                            .coerceAtLeast(0)
                                    } else {
                                        0
                                    }
                                    val token = decodedRequest?.streamToken ?: 0
                                    PacketCodec.encodeResponse(
                                        streamError(
                                            streamToken = token,
                                            requestSequence = sequence,
                                            nextExpectedRequestSequence = nextExpectedSequence,
                                            errorId = MALFORMED_PACKET_ERROR_ID,
                                        ),
                                        PacketCodec.responseMinimumBytes,
                                    )
                                }
                                telemetry.allocated(encoded.size)
                                if (publicationClaimedReply || pendingReply.tryClaim()) encoded else null
                            }
                        }
                        // Do not cross into the binding while holding the stream
                        // monitor. Publication takes the opposite lock order.
                        acknowledgedStructuralBaseline?.let {
                            onStructuralTransactionAcknowledged?.invoke(it)
                        }
                        if (commitPublicationStalled) afterCommitPublication?.invoke()
                        timeoutHandle.cancel()
                        if (response != null) {
                            if (correlatedDebugAttempt) debugTransportProbe.published()
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
        if (bytes.size < qualifier.size) return null
        for (index in qualifier.indices) if (bytes[index] != qualifier[index]) return null
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
                PacketCodec.decodeRequest(pendingReply.requestBytes),
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
        onAbandonedRequest?.invoke(PacketCodec.decodeRequest(bytes), pendingCommitBaseline())
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
            onAbandonedRequest?.invoke(PacketCodec.decodeRequest(bytes), pendingCommitBaseline())
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
        rendererStyleStaging.clear()
        rendererStylePriority = false
        rendererStyleAcknowledgementPending = false
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
            structuralFrameCursor = 0
            queuedResponseProfile = null
        }
        queuedTransactionBaseline = null
        telemetry.clearRetained()
    }

    private fun pendingCommitBaseline(): CommittedBaselineV1 =
        queuedTransactionBaseline?.copy(styleRevision = committedBaseline.styleRevision)
            ?: committedBaseline

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

    private fun isValidResyncRequest(request: PacketCodec.Request): Boolean {
        if (request.requestFlags != RESYNC_REQUEST_FLAG || request.styleRecords.isNotEmpty()) {
            return false
        }
        val command = runCatching { ResyncCommandV1.decode(request.commandBytes) }
            .getOrNull() ?: return false
        val payload = command.payload
        return payload.lastCommittedTransactionId == request.acknowledgedTransactionId &&
            payload.lastCommittedGeometryRevision == request.acknowledgedGeometryRevision &&
            payload.lastCommittedLineageRevision == request.acknowledgedLineageRevision
    }

    private fun isValidTerminalDrain(request: PacketCodec.Request): Boolean =
        request.requestSequence == Long.MAX_VALUE &&
            request.requestFlags == TERMINAL_DRAIN_REQUEST_FLAG &&
            request.styleRecords.isEmpty() &&
            request.commandBytes.isEmpty() &&
            request.nextStyleRevision == committedBaseline.styleRevision &&
            synchronized(structuralFrames) {
                (structuralFrames.isEmpty() &&
                    request.acknowledgedTransactionId == committedBaseline.transactionId &&
                    request.acknowledgedGeometryRevision == committedBaseline.geometryRevision &&
                    request.acknowledgedLineageRevision == committedBaseline.lineageRevision) ||
                    (queuedTransactionBaseline != null &&
                        structuralFrameCursor == structuralFrames.size &&
                        request.acknowledgedTransactionId == queuedTransactionBaseline!!.transactionId &&
                        request.acknowledgedGeometryRevision == queuedTransactionBaseline!!.geometryRevision &&
                        request.acknowledgedLineageRevision == queuedTransactionBaseline!!.lineageRevision)
            }

    private fun requiresResync(
        request: PacketCodec.Request,
        rendererStyleCommand: RendererStyleCommandV1.Page? = null,
    ): Boolean {
        val structuralAcknowledgement =
            (request.acknowledgedTransactionId != 0L ||
                request.acknowledgedGeometryRevision != 0L ||
                request.acknowledgedLineageRevision != 0L ||
                committedBaseline.transactionId != 0L ||
                committedBaseline.geometryRevision != 0L ||
                committedBaseline.lineageRevision != 0L)
        val pendingMatches = synchronized(structuralFrames) {
            queuedTransactionBaseline != null &&
                structuralFrameCursor == structuralFrames.size &&
                request.acknowledgedTransactionId == queuedTransactionBaseline!!.transactionId &&
                request.acknowledgedGeometryRevision == queuedTransactionBaseline!!.geometryRevision &&
                request.acknowledgedLineageRevision == queuedTransactionBaseline!!.lineageRevision
        }
        val structuralMismatch = structuralAcknowledgement && !pendingMatches &&
            (request.acknowledgedTransactionId != committedBaseline.transactionId ||
                request.acknowledgedGeometryRevision != committedBaseline.geometryRevision ||
                request.acknowledgedLineageRevision != committedBaseline.lineageRevision)
        val styleMismatch = if (rendererStyleCommand != null) {
            false
        } else {
            !StyleRevisionSemantics.accepts(
                committedBaseline.styleRevision,
                request.nextStyleRevision,
                request.styleRecords.isNotEmpty(),
            )
        }
        return structuralMismatch || styleMismatch
    }

    private fun nextStructuralResponse(request: PacketCodec.Request): PacketCodec.Response {
        synchronized(this) {
            synchronized(structuralFrames) {
                val frame = structuralFrames.elementAtOrNull(structuralFrameCursor)
                    ?: return PacketCodec.noChanges(
                        streamToken = request.streamToken,
                        requestSequence = request.requestSequence,
                        nextExpectedRequestSequence = request.requestSequence + 1,
                        transactionId = committedBaseline.transactionId,
                        targetGeometryRevision = committedBaseline.geometryRevision,
                        targetLineageRevision = committedBaseline.lineageRevision,
                        acceptedStyleRevision = committedBaseline.styleRevision,
                    )
                val responseProfile = requireNotNull(queuedResponseProfile) {
                    "Queued structural transaction has no response profile"
                }
                if (request.maximumResponseBytes < responseProfile.responseCeilingBytes) {
                    throw BindingError(TRANSACTION_STATE_ERROR_ID)
                }
                val response = TransactionResponseCodecV1.encodeFrame(
                    frame = frame,
                    streamToken = request.streamToken,
                    requestSequence = request.requestSequence,
                    nextExpectedRequestSequence = request.requestSequence + 1,
                ).copy(acceptedStyleRevision = committedBaseline.styleRevision)
                // The caller encodes this response under the negotiated ceiling
                // before returning. Only then is the producer advanced.
                val encoded = PacketCodec.encodeResponse(response, responseProfile.responseCeilingBytes)
                check(encoded.isNotEmpty())
                structuralFrameCursor = Math.addExact(structuralFrameCursor, 1)
                return response
            }
        }
    }

    /** Validates the queued response without advancing its producer cursor. */
    private fun validateNextStructuralResponse(request: PacketCodec.Request) {
        synchronized(this) {
            synchronized(structuralFrames) {
                val frame = structuralFrames.elementAtOrNull(structuralFrameCursor) ?: return
                val responseProfile = requireNotNull(queuedResponseProfile) {
                    "Queued structural transaction has no response profile"
                }
                if (request.maximumResponseBytes < responseProfile.responseCeilingBytes) {
                    throw BindingError(TRANSACTION_STATE_ERROR_ID)
                }
                val response = TransactionResponseCodecV1.encodeFrame(
                    frame = frame,
                    streamToken = request.streamToken,
                    requestSequence = request.requestSequence,
                    nextExpectedRequestSequence = request.requestSequence + 1,
                ).copy(acceptedStyleRevision = request.nextStyleRevision)
                check(
                    PacketCodec.encodeResponse(response, responseProfile.responseCeilingBytes).isNotEmpty(),
                )
            }
        }
    }

    private fun validateStructuralTransaction(
        frames: List<TransactionFrameV1>,
        responseProfile: TransactionResponseProfileV1,
    ) {
        val begin = (frames.firstOrNull() as? TransactionBeginFrameV1)?.value
            ?: error("Structural transaction must begin with BEGIN")
        val commit = (frames.lastOrNull() as? TransactionCommitFrameV1)?.value
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
        require(frames.size == responseProfile.frameCount(begin.totalBytes))
        require(begin.chunkCount == responseProfile.chunkCount(begin.totalBytes))
        require(begin.totalBytes in 0..StructuralTransactionLimits.MAX_STRUCTURAL_TRANSACTION_BYTES)
        require(begin.chunkCount in 0..StructuralTransactionLimits.MAX_CHUNK_COUNT)
        var totalBytes = 0
        val checksum = CRC32()
        for (frameIndex in 1 until frames.lastIndex) {
            val chunk = (frames[frameIndex] as? TransactionChunkFrameV1)?.value
                ?: error("Structural transaction contains a non-CHUNK frame")
            require(chunk.transactionId == begin.transactionId && chunk.chunkIndex == frameIndex - 1)
            require(chunk.bytes.isNotEmpty())
            require(chunk.offset == null || chunk.offset == totalBytes)
            val expectedChunkBytes = minOf(responseProfile.chunkPayloadBytes, begin.totalBytes - totalBytes)
            require(chunk.bytes.size == expectedChunkBytes) {
                "CHUNK does not match the queued response profile"
            }
            totalBytes = Math.addExact(totalBytes, chunk.bytes.size)
            require(totalBytes <= begin.totalBytes)
            checksum.update(chunk.bytes)
        }
        require(totalBytes == begin.totalBytes)
        require(commit.payloadChecksum == begin.payloadChecksum)
        require(checksum.value == begin.payloadChecksum)
    }

    private fun structuralPayloadBytes(): Int = synchronized(structuralFrames) {
        structuralFrames.sumOf { frame ->
            when (frame) {
                is TransactionChunkFrameV1 -> frame.value.bytes.size
                else -> 0
            }
        }
    }

    private fun nextStructuralFrame(): TransactionFrameV1? = synchronized(structuralFrames) {
        structuralFrames.elementAtOrNull(structuralFrameCursor)
    }

    /** Releases the sole retained transaction only after Dart proves its full cut. */
    private fun acknowledgePendingStructuralTransaction(
        request: PacketCodec.Request,
    ): CommittedBaselineV1? {
        synchronized(structuralFrames) {
            val pending = queuedTransactionBaseline ?: return null
            if (structuralFrameCursor != structuralFrames.size ||
                request.acknowledgedTransactionId != pending.transactionId ||
                request.acknowledgedGeometryRevision != pending.geometryRevision ||
                request.acknowledgedLineageRevision != pending.lineageRevision) {
                return null
            }
            committedBaseline = pending.copy(styleRevision = committedBaseline.styleRevision)
            queuedTransactionBaseline = null
            structuralFrames.clear()
            structuralFrameCursor = 0
            queuedResponseProfile = null
            // A style page may have been admitted on the request that
            // returned the structural frame.  The canonical ACK starts a new
            // cut, so discard that incomplete renderer cut before the worker
            // replays it against the acknowledged baseline.
            rendererStyleStaging.clear()
            rendererStylePriority = false
            rendererStyleAcknowledgementPending = false
            telemetry.retainedStructuralStaging(0)
            controlLifecycle?.setCommittedBaseline(committedBaseline)
            return committedBaseline
        }
    }

    private fun copyStructuralFrame(frame: TransactionFrameV1): TransactionFrameV1 = when (frame) {
        is TransactionBeginFrameV1 -> TransactionBeginFrameV1(frame.value.copy())
        is TransactionChunkFrameV1 -> TransactionChunkFrameV1(
            frame.value.copy(bytes = frame.value.bytes.copyOf()),
        )
        is TransactionCommitFrameV1 -> TransactionCommitFrameV1(frame.value.copy())
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
        val sequence = if (bytes.size >= PacketCodec.requestHeaderBytes) {
            ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).getLong(64).coerceAtLeast(0)
        } else {
            0
        }
        val token = runCatching { PacketCodec.decodeRequest(bytes).streamToken }.getOrDefault(0)
        val encoded = PacketCodec.encodeResponse(
            streamError(
                streamToken = token,
                requestSequence = sequence,
                nextExpectedRequestSequence = nextExpectedSequence,
                errorId = STREAM_BINDING_ABANDONED_ERROR_ID,
            ),
            PacketCodec.responseMinimumBytes,
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
                val request = PacketCodec.decodeRequest(bytes)
                controlLifecycle?.streamTokenError(request.streamToken)?.let { throw BindingError(it) }
                require(request.requestSequence == nextExpectedSequence) {
                    "Queued backpressure request must advertise the next sequence"
                }
                val response = PacketCodec.encodeResponse(
                    streamError(
                        streamToken = request.streamToken,
                        requestSequence = request.requestSequence,
                        nextExpectedRequestSequence = nextExpectedSequence,
                        errorId = STREAM_BACKPRESSURE_ERROR_ID,
                    ),
                    PacketCodec.responseMinimumBytes,
                )
                resyncPending = true
                lastSequence = request.requestSequence
                nextExpectedSequence = request.requestSequence + 1
                lastRequest = bytes
                lastResponse = response
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
        val sequence = if (bytes.size >= PacketCodec.requestHeaderBytes) {
            ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).getLong(64).coerceAtLeast(0)
        } else {
            0
        }
        val token = runCatching { PacketCodec.decodeRequest(bytes).streamToken }.getOrDefault(0)
        val encoded = PacketCodec.encodeResponse(
            streamError(
                streamToken = token,
                requestSequence = sequence,
                nextExpectedRequestSequence = nextExpectedSequence,
                errorId = WORKER_BINDING_LOST_ERROR_ID,
            ),
            PacketCodec.responseMinimumBytes,
        )
        return encoded
    }

    private fun streamError(
        streamToken: Long,
        requestSequence: Long,
        nextExpectedRequestSequence: Long,
        errorId: Int,
    ): PacketCodec.Response {
        val baseline = committedBaseline
        return PacketCodec.error(
            streamToken = streamToken,
            requestSequence = requestSequence,
            nextExpectedRequestSequence = nextExpectedRequestSequence,
            errorId = errorId,
            authority = PacketCodec.ErrorAuthority(
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
        // Match the production Dart exchange deadline. A 1,200-sample
        // admission reached 14.3 s under emulator capture/renderer contention;
        // the former 2 s watchdog abandoned otherwise valid exact ACK work.
        const val DEFAULT_WORKER_TIMEOUT_MILLIS = 20_000L
        const val MAIN_HANDLER_CLEAR_TIMEOUT_MILLIS = 2_000L
    }
}
