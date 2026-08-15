package com.uhg0.ar_flutter_plugin_2.m0

import io.flutter.plugin.common.BasicMessageChannel
import io.flutter.plugin.common.BinaryCodec
import io.flutter.plugin.common.BinaryMessenger
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Executors

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
) {
    private val channel = BasicMessageChannel<ByteBuffer>(
        messenger,
        "visibility_surface_stream_$viewId",
        BinaryCodec.INSTANCE,
    )
    private val executor = Executors.newSingleThreadExecutor()
    private var disposed = false
    private var lastSequence: Long? = null
    private var nextExpectedSequence = 1L
    private var lastRequest: ByteArray? = null
    private var lastResponse: ByteArray? = null

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
            executor.execute {
                val response = synchronized(this) {
                    if (disposed) return@synchronized null
                    var decodedRequest: M0aPacketCodec.Request? = null
                    try {
                        val request = M0aPacketCodec.decodeRequest(bytes)
                        decodedRequest = request
                        val previousSequence = lastSequence
                        when {
                            previousSequence == request.requestSequence -> {
                                check(lastRequest!!.contentEquals(bytes)) {
                                    "Request replay conflict"
                                }
                                lastResponse!!.copyOf()
                            }
                            previousSequence != null && request.requestSequence <= previousSequence -> {
                                throw IllegalArgumentException("Request sequence is stale")
                            }
                            request.requestSequence != nextExpectedSequence -> {
                                throw IllegalArgumentException("Request sequence gap")
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
                                errorId = if (decodedRequest != null &&
                                    sequence > nextExpectedSequence) 32 else 1,
                            ),
                            M0aPacketCodec.responseMinimumBytes,
                        )
                    }
                }
                reply.reply(response?.let {
                    val buffer = ByteBuffer.allocateDirect(it.size)
                    buffer.put(it)
                    buffer
                })
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
        executor.shutdownNow()
    }
}
