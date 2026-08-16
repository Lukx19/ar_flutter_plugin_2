package com.uhg0.ar_flutter_plugin_2.m0

import io.flutter.plugin.common.BinaryMessenger
import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
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
