package com.uhg0.ar_flutter_plugin_2.gridrenderer

import com.uhg0.ar_flutter_plugin_2.visibilitycontract.VisibilityGridChannels
import io.flutter.plugin.common.BinaryMessenger
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Binary handlers of the render and display channels. The shell registry
 * installs them with [register]; each forwards to the attached
 * [VisibilityGridRendererHost].
 *
 * Reply of the render channel: empty when the delta was queued; otherwise
 * 4 bytes little-endian status — 1 resynchronisation required (the grid
 * calls `requestFullResync` and the next export starts with a reset),
 * 2 malformed, 3 no renderer attached. The display channel replies empty.
 */
internal object GridRendererChannels {
    const val STATUS_RESYNC_REQUIRED = 1
    const val STATUS_MALFORMED = 2
    const val STATUS_NO_RENDERER = 3

    val renderHandler = BinaryMessenger.BinaryMessageHandler { message, reply ->
        val status = if (message == null) {
            STATUS_MALFORMED
        } else {
            val bytes = message.order(ByteOrder.LITTLE_ENDIAN)
            val host = GridRendererRegistry.host
            if (host == null) {
                STATUS_NO_RENDERER
            } else {
                host.onRenderMessage(bytes, bytes.position(), bytes.remaining()).code
            }
        }
        reply.reply(if (status == 0) null else statusReply(status))
    }

    val displayHandler = BinaryMessenger.BinaryMessageHandler { message, reply ->
        if (message != null) {
            val bytes = message.order(ByteOrder.LITTLE_ENDIAN)
            GridRendererRegistry.host?.onDisplayMessage(bytes, bytes.position(), bytes.remaining())
        }
        reply.reply(null)
    }

    fun register(messenger: BinaryMessenger) {
        messenger.setMessageHandler(VisibilityGridChannels.RENDER, renderHandler)
        messenger.setMessageHandler(VisibilityGridChannels.DISPLAY, displayHandler)
    }

    fun unregister(messenger: BinaryMessenger) {
        messenger.setMessageHandler(VisibilityGridChannels.RENDER, null)
        messenger.setMessageHandler(VisibilityGridChannels.DISPLAY, null)
    }

    private fun statusReply(status: Int): ByteBuffer =
        ByteBuffer.allocateDirect(4).order(ByteOrder.LITTLE_ENDIAN).putInt(0, status)
}
