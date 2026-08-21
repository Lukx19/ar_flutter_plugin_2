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
    private val controlLifecycle: M0aControlLifecycle? = null,
    private val onExecutorOperation: ((String) -> Unit)? = null,
) {
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
    private val outstandingInvocation = AtomicBoolean(false)
    @Volatile private var lastSequence: Long? = null
    @Volatile private var nextExpectedSequence = 1L
    @Volatile private var lastRequest: ByteArray? = null
    @Volatile private var lastResponse: ByteArray? = null
    @Volatile private var resyncPending = false
    private val bindingAbandoned = AtomicBoolean(false)
    private val transactionReceiver = M0aStructuralTransactionReceiverV1()
    private val telemetry = M0aTransportInstrumentation()
    private val structuralFrames = ArrayDeque<M0aTransactionFrameV1>()
    private var committedBaseline =
        controlLifecycle?.committedBaseline() ?: M0aCommittedBaselineV1.ZERO
    private var queuedTransactionBaseline: M0aCommittedBaselineV1? = null

    /** The bounded begin/chunk/commit seam owned by this binding. */
    val structuralTransactionReceiver: M0aStructuralTransactionReceiverV1
        get() = transactionReceiver

    /** Numeric telemetry for this packed binding; no surface arrays are exposed. */
    val transportInstrumentation: M0aTransportInstrumentation
        get() = telemetry

    /**
     * Queues one bounded structural transaction for worker-pull delivery.
     * Frames are consumed only after their response is encoded and accepted;
     * an exact request replay therefore never advances the producer.
     */
    fun queueStructuralTransaction(frames: List<M0aTransactionFrameV1>) {
        require(frames.isNotEmpty()) { "A structural transaction cannot be empty" }
        synchronized(this) {
            check(!disposed.get() && !bindingAbandoned.get()) { "Binding is abandoned" }
            check(structuralFrames.isEmpty()) { "A structural transaction is already queued" }
            validateStructuralTransaction(frames)
            frames.forEach { structuralFrames.addLast(it) }
            telemetry.retainedStructuralStaging(structuralPayloadBytes())
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

    private class BindingError(val errorId: Int) : IllegalArgumentException()

    init {
        metricsChannel.setMessageHandler { _, reply ->
            val summary = telemetry.tryEncodeBoundedSummary()
            reply.reply(summary?.let { bytes ->
                ByteBuffer.allocateDirect(bytes.size).apply { put(bytes) }
            })
        }
        channel.setMessageHandler { message, reply ->
            val bytes = message?.let { buffer ->
                val copy = ByteArray(buffer.remaining())
                buffer.slice().get(copy)
                copy
            }
            if (bytes == null) {
                reply.reply(null)
                return@setMessageHandler
            }
            telemetry.submitted(bytes.size)
            telemetry.allocated(bytes.size)
            if (!outstandingInvocation.compareAndSet(false, true)) {
                telemetry.rejected()
                val rejected = backpressureResponse(bytes)
                telemetry.allocated(rejected.remaining())
                reply.reply(rejected)
                return@setMessageHandler
            }
            telemetry.queued()
            val pendingReply = PendingReply(reply) {
                outstandingInvocation.set(false)
            }
            val timeoutHandle = timeoutScheduler.schedule(workerTimeoutMillis) {
                abandonForTimeout(pendingReply, bytes)
            }
            try {
                workerExecutor.execute {
                    telemetry.dequeued()
                    try {
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
                                null
                            } else {
                                var decodedRequest: M0aPacketCodec.Request? = null
                                val encoded = try {
                                    val request = M0aPacketCodec.decodeRequest(bytes)
                                    decodedRequest = request
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
                                        else -> {
                                            if (request.requestFlags and RESYNC_REQUEST_FLAG != 0 &&
                                                !isValidResyncRequest(request)) {
                                                throw BindingError(TRANSACTION_STATE_ERROR_ID)
                                            }
                                            controlLifecycle?.let {
                                                committedBaseline = it.committedBaseline()
                                            }
                                            val requiresResync = requiresResync(request)
                                            val encoded = M0aPacketCodec.encodeResponse(
                                                when {
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
                                            lastSequence = request.requestSequence
                                            nextExpectedSequence = request.requestSequence + 1
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
                                        M0aPacketCodec.error(
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
                                        M0aPacketCodec.error(
                                            streamToken = token,
                                            requestSequence = sequence,
                                            nextExpectedRequestSequence = nextExpectedSequence,
                                            errorId = MALFORMED_PACKET_ERROR_ID,
                                        ),
                                        M0aPacketCodec.responseMinimumBytes,
                                    )
                                }
                                telemetry.allocated(encoded.size)
                                if (pendingReply.tryClaim()) encoded else null
                            }
                        }
                        timeoutHandle.cancel()
                        if (response != null) {
                            telemetry.allocated(response.size)
                            reply.reply(response.let {
                                // Flutter's Android messenger passes position() as the
                                // JNI message length, so leave the reply positioned after
                                // the bytes rather than flipping it to zero.
                                ByteBuffer.allocateDirect(it.size).apply { put(it) }
                            })
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
    }

    fun dispose() {
        if (!disposed.compareAndSet(false, true)) return
        bindingAbandoned.set(true)
        lastRequest = null
        lastResponse = null
        resyncPending = false
        lastSequence = null
        nextExpectedSequence = 1L
        synchronized(this) {
            structuralFrames.clear()
            queuedTransactionBaseline = null
        }
        telemetry.clearRetained()
        controlLifecycle?.abandon()
        transactionReceiver.stop()
        clearMessageHandler()
        if (shutdownWorkerOnDispose && workerExecutor is java.util.concurrent.ExecutorService) {
            workerExecutor.shutdownNow()
        }
        timeoutScheduler.shutdown()
    }

    private fun abandonForTimeout(pendingReply: PendingReply, bytes: ByteArray) {
        if (!pendingReply.tryClaim()) return
        telemetry.timedOut()
        if (disposed.get()) {
            pendingReply.reply(workerLostResponse(bytes))
            return
        }
        if (!bindingAbandoned.compareAndSet(false, true)) {
            pendingReply.reply(workerLostResponse(bytes))
            return
        }
        transactionReceiver.abandon()
        controlLifecycle?.abandon()
        clearMessageHandler()
        if (shutdownWorkerOnDispose && workerExecutor is java.util.concurrent.ExecutorService) {
            workerExecutor.shutdownNow()
        }
        timeoutScheduler.shutdown()
        pendingReply.reply(workerAbandonedResponse(bytes))
    }

    private fun abandonForWorkerLoss(pendingReply: PendingReply, bytes: ByteArray) {
        val ownsReply = pendingReply.tryClaim()
        if (!bindingAbandoned.compareAndSet(false, true)) {
            if (ownsReply) pendingReply.reply(workerLostResponse(bytes))
            return
        }
        telemetry.workerLost()
        transactionReceiver.abandon()
        controlLifecycle?.abandon()
        clearMessageHandler()
        if (shutdownWorkerOnDispose && workerExecutor is java.util.concurrent.ExecutorService) {
            workerExecutor.shutdownNow()
        }
        timeoutScheduler.shutdown()
        if (ownsReply) pendingReply.reply(workerLostResponse(bytes))
    }

    private fun clearMessageHandler() {
        val clear = {
            channel.setMessageHandler(null)
            metricsChannel.setMessageHandler(null)
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

    private fun validateStructuralTransaction(frames: List<M0aTransactionFrameV1>) {
        require(frames.size <= MAX_STRUCTURAL_FRAMES) { "Structural transaction is too large" }
        val begin = (frames.firstOrNull() as? M0aTransactionBeginFrameV1)?.value
            ?: error("Structural transaction must begin with BEGIN")
        val commit = (frames.lastOrNull() as? M0aTransactionCommitFrameV1)?.value
            ?: error("Structural transaction must end with COMMIT")
        require(begin.transactionId > 0 && commit.transactionId == begin.transactionId)
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

    private fun structuralPayloadBytes(): Int = structuralFrames.sumOf { frame ->
        when (frame) {
            is M0aTransactionChunkFrameV1 -> frame.value.bytes.size
            else -> 0
        }
    }

    private class PendingReply(
        private val callback: BasicMessageChannel.Reply<ByteBuffer>,
        private val onClaimed: () -> Unit,
    ) {
        private val claimed = AtomicBoolean(false)

        fun tryClaim(): Boolean {
            val ownsReply = claimed.compareAndSet(false, true)
            if (ownsReply) onClaimed()
            return ownsReply
        }

        fun reply(buffer: ByteBuffer) {
            callback.reply(buffer)
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
            M0aPacketCodec.error(
                streamToken = token,
                requestSequence = sequence,
                nextExpectedRequestSequence = nextExpectedSequence,
                errorId = STREAM_BINDING_ABANDONED_ERROR_ID,
            ),
            M0aPacketCodec.responseMinimumBytes,
        )
        return ByteBuffer.allocateDirect(encoded.size).apply { put(encoded) }
    }

    private fun backpressureResponse(bytes: ByteArray): ByteBuffer {
        val request = runCatching { M0aPacketCodec.decodeRequest(bytes) }.getOrNull()
        val sequence = request?.requestSequence ?: 0
        val token = request?.streamToken ?: 0
        val encoded = M0aPacketCodec.encodeResponse(
            M0aPacketCodec.error(
                streamToken = token,
                requestSequence = sequence,
                nextExpectedRequestSequence = nextExpectedSequence,
                errorId = STREAM_BACKPRESSURE_ERROR_ID,
            ),
            M0aPacketCodec.responseMinimumBytes,
        )
        return ByteBuffer.allocateDirect(encoded.size).apply { put(encoded) }
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
            M0aPacketCodec.error(
                streamToken = token,
                requestSequence = sequence,
                nextExpectedRequestSequence = nextExpectedSequence,
                errorId = WORKER_BINDING_LOST_ERROR_ID,
            ),
            M0aPacketCodec.responseMinimumBytes,
        )
        return encoded
    }

    private companion object {
        const val MALFORMED_PACKET_ERROR_ID = 6
        const val STREAM_BACKPRESSURE_ERROR_ID = 8
        const val REPLAY_CONFLICT_ERROR_ID = 30
        const val STALE_SEQUENCE_ERROR_ID = 31
        const val SEQUENCE_GAP_ERROR_ID = 32
        const val TRANSACTION_STATE_ERROR_ID = 34
        const val RESYNC_REQUEST_FLAG = 1 shl 2
        const val STREAM_BINDING_ABANDONED_ERROR_ID = 142
        const val WORKER_BINDING_LOST_ERROR_ID = 144
        const val DEFAULT_WORKER_TIMEOUT_MILLIS = 2_000L
        const val MAIN_HANDLER_CLEAR_TIMEOUT_MILLIS = 2_000L
        const val MAX_STRUCTURAL_FRAMES = 18
    }
}
