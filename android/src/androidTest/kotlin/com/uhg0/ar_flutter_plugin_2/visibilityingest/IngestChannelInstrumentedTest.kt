package com.uhg0.ar_flutter_plugin_2.visibilityingest

import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.uhg0.ar_flutter_plugin_2.visibilitycontract.IngestMessageLayout
import com.uhg0.ar_flutter_plugin_2.visibilitycontract.ShellControlLayout
import com.uhg0.ar_flutter_plugin_2.visibilitycontract.VisibilityGridChannels
import io.flutter.plugin.common.BinaryMessenger
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Registration of the ingest and control channels through the registry on a
 * real Android runtime, and pull timing with the real idle timer thread and
 * `SystemClock.elapsedRealtimeNanos`. No camera or ARCore session is used.
 */
@RunWith(AndroidJUnit4::class)
class IngestChannelInstrumentedTest {
    private class RecordingMessenger : BinaryMessenger {
        val handlers = HashMap<String, BinaryMessenger.BinaryMessageHandler?>()
        val queues = HashMap<String, BinaryMessenger.TaskQueue?>()
        override fun makeBackgroundTaskQueue(options: BinaryMessenger.TaskQueueOptions): BinaryMessenger.TaskQueue =
            object : BinaryMessenger.TaskQueue {}
        override fun send(channel: String, message: ByteBuffer?) = Unit
        override fun send(channel: String, message: ByteBuffer?, callback: BinaryMessenger.BinaryReply?) = Unit
        override fun setMessageHandler(channel: String, handler: BinaryMessenger.BinaryMessageHandler?) {
            handlers[channel] = handler
            queues[channel] = null
        }
        override fun setMessageHandler(
            channel: String,
            handler: BinaryMessenger.BinaryMessageHandler?,
            taskQueue: BinaryMessenger.TaskQueue?,
        ) {
            handlers[channel] = handler
            queues[channel] = taskQueue
        }
    }

    private val messenger = RecordingMessenger()

    @After
    fun tearDown() {
        VisibilityGridShellRegistry.unregister(messenger)
    }

    private fun pull(): ByteBuffer = ByteBuffer.allocateDirect(8).order(ByteOrder.LITTLE_ENDIAN).apply {
        putInt(0, IngestMessageLayout.PULL_REQUEST_MAGIC)
        putShort(4, 1)
        put(6, 0x7e)
    }

    private fun startMessage(): ByteBuffer = ByteBuffer.allocateDirect(ShellControlLayout.BYTES).order(ByteOrder.LITTLE_ENDIAN).apply {
        putInt(0, ShellControlLayout.MAGIC)
        putShort(4, ShellControlLayout.VERSION.toShort())
        put(ShellControlLayout.KIND, ShellControlLayout.KIND_START.toByte())
    }

    @Test
    fun registersIngestOnABackgroundQueueAndControlOnThePlatformThread() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        VisibilityGridShellRegistry.register(messenger, context)
        assertNotNull(messenger.handlers[VisibilityGridChannels.INGEST])
        assertNotNull("ingest runs on a background task queue", messenger.queues[VisibilityGridChannels.INGEST])
        assertNotNull(messenger.handlers[VisibilityGridChannels.CONTROL])
        assertNotNull(VisibilityGridShellRegistry.ingestSink)

        val status = AtomicReference<ByteBuffer?>()
        messenger.handlers.getValue(VisibilityGridChannels.CONTROL)!!.onMessage(startMessage()) { status.set(it) }
        assertEquals(0, status.get()!!.order(ByteOrder.LITTLE_ENDIAN).getInt(0))
        assertTrue(VisibilityGridShellRegistry.activeShell!!.started)

        VisibilityGridShellRegistry.unregister(messenger)
        assertEquals(null, messenger.handlers[VisibilityGridChannels.INGEST])
    }

    @Test
    fun parkedPullIsAnsweredIdleAfterAboutHalfASecond() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        VisibilityGridShellRegistry.register(messenger, context)
        val done = CountDownLatch(1)
        val kind = AtomicReference<Int>()
        val elapsed = AtomicLong()
        val started = SystemClock.elapsedRealtimeNanos()
        messenger.handlers.getValue(VisibilityGridChannels.INGEST)!!.onMessage(pull()) { reply ->
            elapsed.set(SystemClock.elapsedRealtimeNanos() - started)
            kind.set(reply!!.get(IngestMessageLayout.KIND).toInt())
            done.countDown()
        }
        assertTrue(done.await(3, TimeUnit.SECONDS))
        assertEquals(IngestMessageLayout.KIND_IDLE, kind.get())
        val ms = elapsed.get() / 1_000_000L
        assertTrue("idle after $ms ms", ms in 480..1_500)
    }

    @Test
    fun offeredSessionEventAnswersAParkedPullAtOnce() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        VisibilityGridShellRegistry.register(messenger, context)
        val shell = VisibilityGridShellRegistry.activeShell!!
        val done = CountDownLatch(1)
        val kind = AtomicReference<Int>()
        messenger.handlers.getValue(VisibilityGridChannels.INGEST)!!.onMessage(pull()) { reply ->
            kind.set(reply!!.get(IngestMessageLayout.KIND).toInt())
            done.countDown()
        }
        shell.sessionEvents.emit(IngestMessageLayout.SESSION_TRACKING_LOST, 0L, 0L, IngestTrackingState.PAUSED, 0)
        assertTrue(done.await(200, TimeUnit.MILLISECONDS))
        assertEquals(IngestMessageLayout.KIND_SESSION_EVENT, kind.get())
    }
}
