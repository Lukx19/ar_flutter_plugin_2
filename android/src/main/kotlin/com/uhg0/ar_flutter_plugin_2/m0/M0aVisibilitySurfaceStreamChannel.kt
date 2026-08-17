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
) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val channel = BasicMessageChannel<ByteBuffer>(
        messenger,
        "visibility_surface_stream_$viewId",
        BinaryCodec.INSTANCE,
    )
    private val disposed = AtomicBoolean(false)
    @Volatile private var lastSequence: Long? = null
    @Volatile private var nextExpectedSequence = 1L
    @Volatile private var lastRequest: ByteArray? = null
    @Volatile private var lastResponse: ByteArray? = null
    @Volatile private var resyncPending = false
    private val bindingAbandoned = AtomicBoolean(false)
    private val transactionReceiver = M0aStructuralTransactionReceiverV1()
    private val telemetry = M0aTransportInstrumentation()

    /** The bounded begin/chunk/commit seam owned by this binding. */
    val structuralTransactionReceiver: M0aStructuralTransactionReceiverV1
        get() = transactionReceiver

    /** Numeric telemetry for this packed binding; no surface arrays are exposed. */
    val transportInstrumentation: M0aTransportInstrumentation
        get() = telemetry

    /** Alias retained for callers that use the shorter telemetry name. */
    val instrumentation: M0aTransportInstrumentation
        get() = telemetry

    private class BindingError(val errorId: Int) : IllegalArgumentException()

    init {
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
            telemetry.queued()
            val pendingReply = PendingReply(reply)
            val timeoutHandle = timeoutScheduler.schedule(workerTimeoutMillis) {
                abandonForTimeout(pendingReply, bytes)
            }
            try {
                workerExecutor.execute {
                    telemetry.dequeued()
                    try {
                        val response = synchronized(this) {
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
                                            val requiresResync =
                                                request.acknowledgedTransactionId != 0L ||
                                                    request.acknowledgedGeometryRevision != 0L ||
                                                    request.acknowledgedLineageRevision != 0L ||
                                                    request.nextStyleRevision != 0L
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
                                                        M0aPacketCodec.noChanges(
                                                            streamToken = request.streamToken,
                                                            requestSequence = request.requestSequence,
                                                            nextExpectedRequestSequence = request.requestSequence + 1,
                                                        )
                                                    }
                                                },
                                                request.maximumResponseBytes,
                                            )
                                            lastSequence = request.requestSequence
                                            nextExpectedSequence = request.requestSequence + 1
                                            lastRequest = bytes.copyOf()
                                            lastResponse = encoded.copyOf()
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
                                if (pendingReply.tryClaim()) encoded else null
                            }
                        }
                        timeoutHandle.cancel()
                        if (response != null) {
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
        clearMessageHandler()
        if (shutdownWorkerOnDispose && workerExecutor is java.util.concurrent.ExecutorService) {
            workerExecutor.shutdownNow()
        }
        timeoutScheduler.shutdown()
        if (ownsReply) pendingReply.reply(workerLostResponse(bytes))
    }

    private fun clearMessageHandler() {
        val clear = { channel.setMessageHandler(null) }
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

    private class PendingReply(
        private val callback: BasicMessageChannel.Reply<ByteBuffer>,
    ) {
        private val claimed = AtomicBoolean(false)

        fun tryClaim(): Boolean = claimed.compareAndSet(false, true)

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
        const val REPLAY_CONFLICT_ERROR_ID = 30
        const val STALE_SEQUENCE_ERROR_ID = 31
        const val SEQUENCE_GAP_ERROR_ID = 32
        const val TRANSACTION_STATE_ERROR_ID = 34
        const val RESYNC_REQUEST_FLAG = 1 shl 2
        const val STREAM_BINDING_ABANDONED_ERROR_ID = 142
        const val WORKER_BINDING_LOST_ERROR_ID = 144
        const val DEFAULT_WORKER_TIMEOUT_MILLIS = 2_000L
        const val MAIN_HANDLER_CLEAR_TIMEOUT_MILLIS = 2_000L
    }
}
