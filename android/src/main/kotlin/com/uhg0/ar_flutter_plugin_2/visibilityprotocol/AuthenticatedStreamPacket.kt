package com.uhg0.ar_flutter_plugin_2.visibilityprotocol

import java.nio.ByteBuffer

/** Framework buffers are borrowed synchronously; queued work owns exactly one payload copy. */
internal object AuthenticatedStreamPacket {
    fun copyPayload(message: ByteBuffer, qualifier: ByteArray?): ByteArray? {
        val prefix = qualifier?.size ?: 0
        if (message.remaining() < prefix) return null
        if (qualifier != null) for (index in qualifier.indices) {
            if (message.get(message.position() + index) != qualifier[index]) return null
        }
        val payload = ByteArray(message.remaining() - prefix)
        message.duplicate().apply { position(position() + prefix); get(payload) }
        return payload
    }
}
