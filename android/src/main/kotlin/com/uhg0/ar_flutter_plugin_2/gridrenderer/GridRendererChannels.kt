package com.uhg0.ar_flutter_plugin_2.gridrenderer

import com.uhg0.ar_flutter_plugin_2.sessionlog.NativeSessionLogEmitter
import com.uhg0.ar_flutter_plugin_2.channelstatus.ChannelStatusReply
import com.uhg0.ar_flutter_plugin_2.visibilitycontract.VisibilityGridChannels
import io.flutter.plugin.common.BinaryMessenger
import java.nio.ByteOrder

/**
 * Binary handlers of the render and display channels. The shell registry
 * installs them with [register]; each forwards to the attached
 * [VisibilityGridRendererHost]. Both run on one background task queue (in
 * arrival order), never on the platform main thread, which is also the
 * ARCore/SceneView frame thread; the render thread uploads the queued deltas
 * within its frame budget.
 *
 * Every message is answered with a 4-byte little-endian status, never null
 * (the grid isolate sends through `BackgroundIsolateBinaryMessenger`, which
 * cannot deliver a null reply). Render channel: 0 queued, 1 resynchronisation required (the grid
 * calls `requestFullResync` and the next export starts with a reset),
 * 2 malformed, 3 no renderer attached, 4 renderer failed (its render thread
 * stopped; nothing is drawn until a new overlay surface starts a working
 * thread, which then answers 1 once). Display channel: 0 always (the
 * message was handled or ignored).
 */
internal object GridRendererChannels {
    const val STATUS_OK = ChannelStatusReply.OK
    const val STATUS_RESYNC_REQUIRED = 1
    const val STATUS_MALFORMED = 2
    const val STATUS_NO_RENDERER = 3
    const val STATUS_RENDERER_FAILED = 4

    /** A handler failure for the session log; the caller still replies a status. */
    private fun handlerFailed(method: String, e: RuntimeException) {
        NativeSessionLogEmitter.failure(
            "NATIVE_HANDLER_ERROR", "gridRenderer/$method/${e.javaClass.simpleName}", "Grid renderer handler failed",
        ) { mapOf("channel" to "gridRenderer", "method" to method, "error" to (e.message ?: "")) }
    }

    val renderHandler = BinaryMessenger.BinaryMessageHandler { message, reply ->
        val status = if (message == null) {
            STATUS_MALFORMED
        } else {
            val bytes = message.order(ByteOrder.LITTLE_ENDIAN)
            val host = GridRendererRegistry.host
            if (host == null) {
                STATUS_NO_RENDERER
            } else {
                try {
                    host.onRenderMessage(bytes, bytes.position(), bytes.remaining()).code
                } catch (e: RuntimeException) {
                    handlerFailed("render", e)
                    STATUS_MALFORMED
                }
            }
        }
        reply.reply(ChannelStatusReply.of(status))
    }

    val displayHandler = BinaryMessenger.BinaryMessageHandler { message, reply ->
        if (message != null) {
            val bytes = message.order(ByteOrder.LITTLE_ENDIAN)
            try {
                GridRendererRegistry.host?.onDisplayMessage(bytes, bytes.position(), bytes.remaining())
            } catch (e: RuntimeException) {
                // Still answered below: a sender never waits for a missing reply.
                handlerFailed("display", e)
            }
        }
        reply.reply(ChannelStatusReply.of(STATUS_OK))
    }

    fun register(messenger: BinaryMessenger) {
        val queue = messenger.makeBackgroundTaskQueue()
        messenger.setMessageHandler(VisibilityGridChannels.RENDER, renderHandler, queue)
        messenger.setMessageHandler(VisibilityGridChannels.DISPLAY, displayHandler, queue)
    }

    fun unregister(messenger: BinaryMessenger) {
        messenger.setMessageHandler(VisibilityGridChannels.RENDER, null)
        messenger.setMessageHandler(VisibilityGridChannels.DISPLAY, null)
    }
}
