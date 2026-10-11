package com.uhg0.ar_flutter_plugin_2.channelstatus

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 4-byte little-endian status replies for binary channels.
 *
 * A handler on a channel that a background isolate can call never replies
 * null: Flutter's `BackgroundIsolateBinaryMessenger` cannot deliver a null
 * reply (it throws in the isolate and the awaited send never completes), so
 * every path answers with a payload, a status reply from here at the least.
 * Status 0 means OK; each channel documents its other codes.
 *
 * The buffers are shared, direct and never written after creation (position
 * 4, so the engine sends four bytes), so they can be replied from any thread
 * without an allocation per message.
 */
internal object ChannelStatusReply {
    const val BYTES = 4
    const val OK = 0

    private val shared = Array(16) { create(it) }

    /** The reply for [status]; shared for 0-15. */
    fun of(status: Int): ByteBuffer = if (status in shared.indices) shared[status] else create(status)

    /** Status of [reply] when it is a 4-byte status reply, else null. */
    fun statusOf(reply: ByteBuffer?): Int? {
        if (reply == null) return null
        val bytes = if (reply.position() > 0) reply.position() else reply.remaining()
        if (bytes != BYTES) return null
        return reply.duplicate().order(ByteOrder.LITTLE_ENDIAN).getInt(0)
    }

    private fun create(status: Int): ByteBuffer =
        ByteBuffer.allocateDirect(BYTES).order(ByteOrder.LITTLE_ENDIAN).apply {
            putInt(0, status)
            position(BYTES)
        }
}
