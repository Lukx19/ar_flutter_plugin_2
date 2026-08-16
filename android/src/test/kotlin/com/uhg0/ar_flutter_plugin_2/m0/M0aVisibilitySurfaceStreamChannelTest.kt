package com.uhg0.ar_flutter_plugin_2.m0

import io.flutter.plugin.common.BinaryMessenger
import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class M0aVisibilitySurfaceStreamChannelTest {
    @Test
    fun `serial binding returns exact response for duplicate and rejects conflict`() {
        val messenger = TestMessenger(17)
        val binding = M0aVisibilitySurfaceStreamChannel(messenger, 17)
        val request = request(sequence = 1, token = 91)

        val first = messenger.exchange(request)
        val duplicate = messenger.exchange(request)
        assertArrayEquals(first, duplicate)
        assertEquals(1L, M0aPacketCodec.decodeResponse(first).requestSequence)

        val conflict = messenger.exchange(request(sequence = 1, token = 92))
        val conflictResponse = M0aPacketCodec.decodeResponse(conflict)
        assertEquals(255, conflictResponse.messageKind)
        assertEquals(30, conflictResponse.errorId)
        assertEquals(1L, conflictResponse.requestSequence)

        binding.dispose()
    }

    @Test
    fun `binding reports sequence gap and malformed packet without crashing executor`() {
        val messenger = TestMessenger(18)
        val binding = M0aVisibilitySurfaceStreamChannel(messenger, 18)

        val gap = messenger.exchange(request(sequence = 2, token = 4))
        val gapResponse = M0aPacketCodec.decodeResponse(gap)
        assertEquals(255, gapResponse.messageKind)
        assertEquals(32, gapResponse.errorId)
        assertEquals(2L, gapResponse.requestSequence)
        assertEquals(1L, gapResponse.nextExpectedRequestSequence)

        val malformed = request(sequence = 1, token = 4).also { it[20] = (it[20].toInt() xor 1).toByte() }
        val malformedResponse = M0aPacketCodec.decodeResponse(messenger.exchange(malformed))
        assertEquals(255, malformedResponse.messageKind)
        assertEquals(6, malformedResponse.errorId)

        val accepted = messenger.exchange(request(sequence = 1, token = 4))
        assertEquals(0, M0aPacketCodec.decodeResponse(accepted).messageKind)
        assertEquals(0, M0aPacketCodec.decodeResponse(messenger.exchange(request(sequence = 2, token = 4))).messageKind)
        val stale = M0aPacketCodec.decodeResponse(messenger.exchange(request(sequence = 1, token = 5)))
        assertEquals(255, stale.messageKind)
        assertEquals(31, stale.errorId)
        binding.dispose()
    }

    @Test
    fun `dispose removes handler and a replacement binding can serve the view`() {
        val messenger = TestMessenger(19)
        val firstBinding = M0aVisibilitySurfaceStreamChannel(messenger, 19)
        messenger.exchange(request(sequence = 1, token = 5))
        firstBinding.dispose()
        assertNull(messenger.tryExchange(request(sequence = 1, token = 5)))

        val replacement = M0aVisibilitySurfaceStreamChannel(messenger, 19)
        val response = messenger.exchange(request(sequence = 1, token = 5))
        assertEquals(0, M0aPacketCodec.decodeResponse(response).messageKind)
        replacement.dispose()
    }

    @Test
    fun `worker rejection returns stable binding lost error and replacement recovers`() {
        val messenger = TestMessenger(20)
        val failedBinding = M0aVisibilitySurfaceStreamChannel(
            messenger,
            20,
            workerExecutor = Executor { throw RejectedExecutionException("worker exited") },
            shutdownWorkerOnDispose = false,
        )

        val failed = M0aPacketCodec.decodeResponse(
            messenger.exchange(request(sequence = 1, token = 6)),
        )
        assertEquals(255, failed.messageKind)
        assertEquals(144, failed.errorId)
        assertEquals(1L, failed.requestSequence)
        failedBinding.dispose()

        val replacement = M0aVisibilitySurfaceStreamChannel(messenger, 20)
        val recovered = M0aPacketCodec.decodeResponse(
            messenger.exchange(request(sequence = 1, token = 6)),
        )
        assertEquals(0, recovered.messageKind)
        replacement.dispose()
    }

    @Test
    fun `accepted worker exception returns binding lost error and replacement recovers`() {
        val messenger = TestMessenger(24)
        val failedBinding = M0aVisibilitySurfaceStreamChannel(
            messenger,
            24,
            workerExecutor = Executor { command ->
                Thread(command, "m0a-accepted-worker-exit").start()
            },
            shutdownWorkerOnDispose = false,
            beforeWorkerProcessing = {
                throw IllegalStateException("worker exited after accepting task")
            },
        )

        val failed = M0aPacketCodec.decodeResponse(
            messenger.exchange(request(sequence = 1, token = 10)),
        )
        assertEquals(255, failed.messageKind)
        assertEquals(144, failed.errorId)
        assertEquals(1L, failed.requestSequence)
        assertNull(messenger.tryExchange(request(sequence = 2, token = 10)))
        failedBinding.dispose()

        val replacement = M0aVisibilitySurfaceStreamChannel(messenger, 24)
        val recovered = M0aPacketCodec.decodeResponse(
            messenger.exchange(request(sequence = 1, token = 10)),
        )
        assertEquals(0, recovered.messageKind)
        replacement.dispose()
    }

    @Test
    fun `queued work returns binding lost after disposal`() {
        val messenger = TestMessenger(21)
        val executor = HoldingExecutor()
        val binding = M0aVisibilitySurfaceStreamChannel(
            messenger,
            21,
            workerExecutor = executor,
            shutdownWorkerOnDispose = false,
        )
        val reply = arrayOfNulls<ByteArray>(1)
        val completed = CountDownLatch(1)
        messenger.send(
            "visibility_surface_stream_21",
            ByteBuffer.wrap(request(sequence = 1, token = 7)),
        ) { response ->
            reply[0] = response?.let { buffer ->
                val copy = ByteArray(buffer.remaining())
                buffer.slice().get(copy)
                copy
            }
            completed.countDown()
        }
        binding.dispose()
        executor.runQueued()
        assertTrue(completed.await(2, TimeUnit.SECONDS))
        val decoded = M0aPacketCodec.decodeResponse(reply[0]!!)
        assertEquals(255, decoded.messageKind)
        assertEquals(144, decoded.errorId)
        assertEquals(1L, decoded.requestSequence)
    }

    @Test
    fun `stalled accepted work abandons binding once and late worker output is ignored`() {
        val messenger = TestMessenger(22)
        val executor = HoldingExecutor()
        val scheduler = HoldingTimeoutScheduler()
        val binding = M0aVisibilitySurfaceStreamChannel(
            messenger,
            22,
            workerExecutor = executor,
            shutdownWorkerOnDispose = false,
            timeoutScheduler = scheduler,
        )
        val replies = AtomicInteger(0)
        val result = arrayOfNulls<ByteArray>(1)
        val completed = CountDownLatch(1)
        messenger.send(
            "visibility_surface_stream_22",
            ByteBuffer.wrap(request(sequence = 1, token = 8)),
        ) { response ->
            replies.incrementAndGet()
            result[0] = response?.let { buffer ->
                val copy = ByteArray(buffer.remaining())
                buffer.slice().get(copy)
                copy
            }
            completed.countDown()
        }

        scheduler.fireNext()

        assertTrue(completed.await(2, TimeUnit.SECONDS))
        val abandoned = M0aPacketCodec.decodeResponse(result[0]!!)
        assertEquals(255, abandoned.messageKind)
        assertEquals(142, abandoned.errorId)
        assertEquals(1L, abandoned.requestSequence)
        assertEquals(1, replies.get())
        assertNull(messenger.tryExchange(request(sequence = 2, token = 8)))

        executor.runQueued()
        assertEquals(1, replies.get())

        binding.dispose()
        val replacement = M0aVisibilitySurfaceStreamChannel(
            messenger,
            22,
            timeoutScheduler = HoldingTimeoutScheduler(),
        )
        val recovered = M0aPacketCodec.decodeResponse(
            messenger.exchange(request(sequence = 1, token = 8)),
        )
        assertEquals(0, recovered.messageKind)
        replacement.dispose()
    }

    @Test
    fun `active worker stall does not hold the timeout behind the channel monitor`() {
        val messenger = TestMessenger(23)
        val scheduler = HoldingTimeoutScheduler()
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        var workerThread: Thread? = null
        val binding = M0aVisibilitySurfaceStreamChannel(
            messenger,
            23,
            workerExecutor = Executor { command ->
                val thread = Thread(command, "m0a-active-stall")
                workerThread = thread
                thread.start()
            },
            shutdownWorkerOnDispose = false,
            timeoutScheduler = scheduler,
            beforeWorkerProcessing = {
                started.countDown()
                check(release.await(2, TimeUnit.SECONDS))
            },
        )
        val reply = arrayOfNulls<ByteArray>(1)
        val completed = CountDownLatch(1)
        messenger.send(
            "visibility_surface_stream_23",
            ByteBuffer.wrap(request(sequence = 1, token = 9)),
        ) { response ->
            reply[0] = response?.let { buffer ->
                val copy = ByteArray(buffer.remaining())
                buffer.slice().get(copy)
                copy
            }
            completed.countDown()
        }

        assertTrue(started.await(2, TimeUnit.SECONDS))
        scheduler.fireNext()
        assertTrue(completed.await(2, TimeUnit.SECONDS))
        val abandoned = M0aPacketCodec.decodeResponse(reply[0]!!)
        assertEquals(255, abandoned.messageKind)
        assertEquals(142, abandoned.errorId)

        release.countDown()
        val completedWorker = checkNotNull(workerThread)
        completedWorker.join(2_000)
        assertTrue(!completedWorker.isAlive)
        binding.dispose()
    }

    private fun request(sequence: Long, token: Long): ByteArray =
        M0aPacketCodec.encodeRequest(
            M0aPacketCodec.Request(
                requestFlags = 0,
                streamToken = token,
                acknowledgedTransactionId = 0,
                acknowledgedGeometryRevision = 0,
                acknowledgedLineageRevision = 0,
                nextStyleRevision = 0,
                maximumResponseBytes = 4096,
                styleRecords = emptyList(),
                commandBytes = byteArrayOf(),
                requestSequence = sequence,
            ),
        )
}

private class HoldingExecutor : Executor {
    private var queued: Runnable? = null

    override fun execute(command: Runnable) {
        queued = command
    }

    fun runQueued() {
        checkNotNull(queued).run()
    }
}

private class HoldingTimeoutScheduler : M0aTimeoutScheduler {
    private var pending: (() -> Unit)? = null

    override fun schedule(delayMillis: Long, task: () -> Unit): M0aTimeoutHandle {
        pending = task
        return M0aTimeoutHandle { pending = null }
    }

    override fun shutdown() {
        pending = null
    }

    fun fireNext() {
        val task = checkNotNull(pending)
        pending = null
        task()
    }
}

private class TestMessenger(viewId: Int) : BinaryMessenger {
    private val channelName = "visibility_surface_stream_$viewId"
    private var handler: BinaryMessenger.BinaryMessageHandler? = null

    override fun send(channel: String, message: ByteBuffer?) {
        send(channel, message, null)
    }

    override fun send(
        channel: String,
        message: ByteBuffer?,
        callback: BinaryMessenger.BinaryReply?,
    ) {
        check(channel == channelName)
        val currentHandler = handler
        if (currentHandler == null) {
            callback?.reply(null)
            return
        }
        currentHandler.onMessage(
            message,
            BinaryMessenger.BinaryReply { reply ->
                val engineReply = reply?.let { buffer ->
                    val length = buffer.position()
                    val copy = ByteArray(length)
                    buffer.duplicate().apply {
                        flip()
                        get(copy)
                    }
                    ByteBuffer.wrap(copy)
                }
                callback?.reply(engineReply)
            },
        )
    }

    override fun setMessageHandler(
        channel: String,
        handler: BinaryMessenger.BinaryMessageHandler?,
    ) {
        check(channel == channelName)
        this.handler = handler
    }

    fun exchange(request: ByteArray): ByteArray =
        tryExchange(request) ?: error("Binding returned no response")

    fun tryExchange(request: ByteArray): ByteArray? {
        val result = arrayOfNulls<ByteArray>(1)
        val completed = CountDownLatch(1)
        send(
            channelName,
            ByteBuffer.wrap(request),
            BinaryMessenger.BinaryReply { response ->
                result[0] = response?.let { buffer ->
                    val copy = ByteArray(buffer.remaining())
                    buffer.slice().get(copy)
                    copy
                }
                completed.countDown()
            },
        )
        assertTrue(completed.await(2, TimeUnit.SECONDS))
        return result[0]
    }
}
