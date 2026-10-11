package com.uhg0.ar_flutter_plugin_2.gridrenderer

import com.google.ar.core.Camera
import com.google.ar.core.Frame
import com.google.ar.core.exceptions.FatalException
import com.uhg0.ar_flutter_plugin_2.visibilitycontract.RendererDeltaLayout
import com.uhg0.ar_flutter_plugin_2.visibilitycontract.VisibilityGridChannels
import io.flutter.plugin.common.BinaryMessenger
import com.uhg0.ar_flutter_plugin_2.sessionlog.data
import com.uhg0.ar_flutter_plugin_2.sessionlog.full
import com.uhg0.ar_flutter_plugin_2.sessionlog.withNativeSessionLog
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Channel threading, bulk delta intake and the failed render thread state. */
class GridRendererRobustnessTest {
    private val w = RendererDeltaWriter()

    private fun reset() = w.begin(RendererDeltaLayout.KIND_RESET, 0, 0, 4096, 0)

    private fun slice(brick: Int) {
        w.begin(RendererDeltaLayout.KIND_SURFACE_SLICE, brick * 256, 256, 4096 * 64, GridBrickKey.pack(brick, 0, 0))
        for (i in 0 until 256) w.surface(i, brick * 0.8f + i * 0.001f, 0.05f, 0.05f, 0f, 1f, 0f)
    }

    @Test
    fun `render and display handlers run on one background task queue`() {
        val messenger = QueueRecordingMessenger()
        GridRendererChannels.register(messenger)
        val render = messenger.queues[VisibilityGridChannels.RENDER]
        assertNotNull(render)
        assertSame(messenger.created, render)
        assertSame(render, messenger.queues[VisibilityGridChannels.DISPLAY])
    }

    @Test
    fun `every render and display message gets a 4-byte status reply, never null`() {
        fun render(message: ByteBuffer?): Int = statusOf { GridRendererChannels.renderHandler.onMessage(message, it) }
        fun display(message: ByteBuffer?): Int = statusOf { GridRendererChannels.displayHandler.onMessage(message, it) }
        reset()
        val delta = { ByteBuffer.wrap(w.buffer.array(), 0, w.length) }
        assertEquals(GridRendererChannels.STATUS_NO_RENDERER, render(delta()))
        assertEquals(GridRendererChannels.STATUS_MALFORMED, render(null))
        assertEquals(GridRendererChannels.STATUS_OK, display(null))
        val host = VisibilityGridRendererHost()
        host.attach(Any(), Any())
        try {
            assertEquals(GridRendererChannels.STATUS_OK, render(delta()))
            slice(2)
            assertEquals(GridRendererChannels.STATUS_OK, render(delta()))
            assertEquals(GridRendererChannels.STATUS_OK, display(ByteBuffer.allocate(8)))
        } finally {
            host.detach()
        }
    }

    /** Status of the single reply [call] gives; fails on a null or missing reply. */
    private fun statusOf(call: (BinaryMessenger.BinaryReply) -> Unit): Int {
        var replies = 0
        var status = -1
        call { reply ->
            replies++
            val bytes = checkNotNull(reply) { "null reply never completes a background isolate send" }
            assertEquals(4, bytes.position()) // the engine sends position() bytes
            status = bytes.duplicate().order(ByteOrder.LITTLE_ENDIAN).getInt(0)
        }
        assertEquals(1, replies)
        return status
    }

    @Test
    fun `a delta at an offset in a heap buffer is queued byte for byte and the source window is kept`() {
        val reference = ByteArrayGpuTarget()
        val referenceCore = GridRendererCore(reference)
        val heap = ByteArrayGpuTarget()
        val heapCore = GridRendererCore(heap)
        reset()
        assertEquals(DeltaIntake.ACCEPTED, referenceCore.onRenderMessage(w.buffer, 0, w.length))
        assertEquals(DeltaIntake.ACCEPTED, heapCore.onRenderMessage(w.buffer, 0, w.length))
        slice(3)
        assertEquals(DeltaIntake.ACCEPTED, referenceCore.onRenderMessage(w.buffer, 0, w.length))
        val bytes = ByteArray(w.length + 11)
        for (i in 0 until w.length) bytes[7 + i] = w.buffer.get(i)
        val src = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        src.position(2).limit(bytes.size - 1)
        assertEquals(DeltaIntake.ACCEPTED, heapCore.onRenderMessage(src, 7, w.length))
        assertEquals(2, src.position())
        assertEquals(bytes.size - 1, src.limit())
        reference.beginFrame()
        referenceCore.frame()
        heap.beginFrame()
        heapCore.frame()
        for (buffer in GridBuffer.values()) assertArrayEquals(reference.buffer(buffer), heap.buffer(buffer))
    }

    @Test
    fun `a failed render thread is a RENDERER_FAILED session log record`() {
        val host = VisibilityGridRendererHost()
        val records = withNativeSessionLog { host.onRenderThreadFailed(null) }
        assertEquals(1L, records.full("RENDERER_FAILED").single().data["renderThreadFailures"].toString().toLong())
    }

    @Test
    fun `a failed render thread refuses deltas until a new thread starts and then asks for a resync`() {
        val host = VisibilityGridRendererHost()
        reset()
        assertEquals(DeltaIntake.ACCEPTED, host.onRenderMessage(w.buffer, 0, w.length))
        host.onRenderThreadFailed(null)
        assertEquals(1, host.renderThreadFailures)
        slice(1)
        assertEquals(DeltaIntake.RENDERER_FAILED, host.onRenderMessage(w.buffer, 0, w.length))
        assertEquals(DeltaIntake.RENDERER_FAILED, host.onRenderMessage(w.buffer, 0, w.length))
        assertEquals(0, host.core.scheduler.queuedMessages)

        // The channel reports the failure (status 4) instead of a resync.
        host.attach(Any(), Any())
        try {
            val status = statusOf { GridRendererChannels.renderHandler.onMessage(w.buffer.duplicate().apply { limit(w.length) }, it) }
            assertEquals(GridRendererChannels.STATUS_RENDERER_FAILED, status)
        } finally {
            host.detach()
        }

        host.onRenderThreadStarted()
        assertEquals(DeltaIntake.RESYNC_REQUIRED, host.onRenderMessage(w.buffer, 0, w.length))
        reset()
        assertEquals(DeltaIntake.ACCEPTED, host.onRenderMessage(w.buffer, 0, w.length))
    }

    @Test
    fun `a failing camera read in the frame callback is counted and not thrown`() {
        val host = VisibilityGridRendererHost()
        reset()
        host.onRenderMessage(w.buffer, 0, w.length) // enables the per-frame camera update
        host.onArFrame(object : Frame() {
            override fun getCamera(): Camera = throw FatalException("session lost")
        })
        assertEquals(1, host.cameraUpdateFailures)
    }

    private class QueueRecordingMessenger : BinaryMessenger {
        val created = object : BinaryMessenger.TaskQueue {}
        val queues = HashMap<String, BinaryMessenger.TaskQueue?>()

        override fun makeBackgroundTaskQueue(options: BinaryMessenger.TaskQueueOptions): BinaryMessenger.TaskQueue = created

        override fun send(channel: String, message: ByteBuffer?) = Unit

        override fun send(channel: String, message: ByteBuffer?, callback: BinaryMessenger.BinaryReply?) = Unit

        override fun setMessageHandler(channel: String, handler: BinaryMessenger.BinaryMessageHandler?) {
            queues[channel] = null
        }

        override fun setMessageHandler(
            channel: String,
            handler: BinaryMessenger.BinaryMessageHandler?,
            taskQueue: BinaryMessenger.TaskQueue?,
        ) {
            queues[channel] = taskQueue
        }
    }
}
