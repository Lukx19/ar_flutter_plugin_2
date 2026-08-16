package com.uhg0.ar_flutter_plugin_2.m0

import io.flutter.plugin.common.BasicMessageChannel
import io.flutter.plugin.common.BinaryCodec
import io.flutter.plugin.common.BinaryMessenger
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException

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
) {
    private val channel = BasicMessageChannel<ByteBuffer>(
        messenger,
        "visibility_surface_stream_$viewId",
        BinaryCodec.INSTANCE,
    )
    private var disposed = false
    private var lastSequence: Long? = null
    private var nextExpectedSequence = 1L
    private var lastRequest: ByteArray? = null
    private var lastResponse: ByteArray? = null

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
            try {
                workerExecutor.execute {
                    val response = synchronized(this) {
                        if (disposed) return@synchronized workerLostResponseBytes(bytes)
                        var decodedRequest: M0aPacketCodec.Request? = null
                        try {
                            val request = M0aPacketCodec.decodeRequest(bytes)
                            decodedRequest = request
                            val previousSequence = lastSequence
                            when {
                                previousSequence == request.requestSequence -> {
                                    if (!lastRequest!!.contentEquals(bytes)) {
                                        throw BindingError(REPLAY_CONFLICT_ERROR_ID)
                                    }
                                    lastResponse!!.copyOf()
                                }
                                previousSequence != null && request.requestSequence <= previousSequence -> {
                                    throw BindingError(STALE_SEQUENCE_ERROR_ID)
                                }
                                request.requestSequence != nextExpectedSequence -> {
                                    throw BindingError(SEQUENCE_GAP_ERROR_ID)
                                }
                                else -> {
                                    val encoded = M0aPacketCodec.encodeResponse(
                                        M0aPacketCodec.noChanges(
                                            streamToken = request.streamToken,
                                            requestSequence = request.requestSequence,
                                            nextExpectedRequestSequence = request.requestSequence + 1,
                                        ),
                                        request.maximumResponseBytes,
                                    )
                                    lastSequence = request.requestSequence
                                    nextExpectedSequence = request.requestSequence + 1
                                    lastRequest = bytes.copyOf()
                                    lastResponse = encoded.copyOf()
                                    encoded
                                }
                            }
                        } catch (error: BindingError) {
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
                    }
                    reply.reply(response.let {
                        // Flutter's Android messenger passes position() as the
                        // JNI message length, so leave the reply positioned after
                        // the bytes rather than flipping it to zero.
                        ByteBuffer.allocateDirect(it.size).apply { put(it) }
                    })
                }
            } catch (_: RejectedExecutionException) {
                reply.reply(workerLostResponse(bytes))
            }
        }
    }

    fun dispose() {
        synchronized(this) {
            if (disposed) return
            disposed = true
            lastRequest = null
            lastResponse = null
            lastSequence = null
            nextExpectedSequence = 1L
        }
        channel.setMessageHandler(null)
        if (shutdownWorkerOnDispose && workerExecutor is java.util.concurrent.ExecutorService) {
            workerExecutor.shutdownNow()
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
        const val WORKER_BINDING_LOST_ERROR_ID = 144
    }
}
