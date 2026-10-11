package com.uhg0.ar_flutter_plugin_2.visibilityingest

import com.uhg0.ar_flutter_plugin_2.visibilitycontract.IngestMessageLayout
import com.uhg0.ar_flutter_plugin_2.visibilitycontract.IngestRecordingLayout
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files

class IngestPullHandlerTest {
    private val ms = 1_000_000L
    private val clock = FakeClock(10_000 * ms)
    private val handler = IngestPullHandler(clock)
    private val reply = CapturingReply()

    private fun message(kind: Int, payloadBytes: Int, fill: Int = 0): ByteBuffer {
        val b = ByteBuffer.allocateDirect(IngestMessageLayout.HEADER_BYTES + payloadBytes).order(ByteOrder.LITTLE_ENDIAN)
        IngestMessageWriter.begin(b, kind)
        IngestMessageWriter.payloadBytes(b, payloadBytes)
        for (i in 0 until payloadBytes) b.put(IngestMessageLayout.HEADER_BYTES + i, (fill + i).toByte())
        return b
    }

    private fun offer(kind: Int, payloadBytes: Int, fill: Int = 0): Boolean {
        val b = message(kind, payloadBytes, fill)
        return handler.offer(b, b.capacity())
    }

    @Test
    fun `a parked pull is answered by the next offer`() {
        handler.onPull(pullRequest(), reply)
        assertEquals(0, reply.count)
        assertTrue(handler.isParked)
        clock.now += 100 * ms
        assertTrue(offer(IngestMessageLayout.KIND_DEPTH_IMAGE, 24))
        assertEquals(1, reply.count)
        assertEquals(IngestMessageLayout.KIND_DEPTH_IMAGE, reply.kind())
        assertEquals(clock.now, reply.last!!.getLong(IngestMessageLayout.SEND_TIME_NS))
        assertFalse(handler.isParked)
    }

    @Test
    fun `a parked pull is answered idle after 500 ms on the fake clock`() {
        handler.onPull(pullRequest(), reply)
        clock.now += 499 * ms
        assertEquals(1 * ms, handler.tick())
        assertEquals(0, reply.count)
        clock.now += 1 * ms
        assertEquals(-1L, handler.tick())
        assertEquals(1, reply.count)
        assertEquals(IngestMessageLayout.KIND_IDLE, reply.kind())
        assertEquals(IngestMessageLayout.HEADER_BYTES, reply.last!!.position())
        assertEquals(IngestMessageLayout.POSE_MATCH_MISSING, reply.last!!.get(IngestMessageLayout.POSE_MATCH).toInt())
        assertEquals(1L, handler.idleReplyCount)
        assertEquals(-1L, handler.tick())
    }

    @Test
    fun `malformed pulls and pulls after close are answered idle at once`() {
        handler.onPull(ByteBuffer.allocate(3), reply)
        assertEquals(IngestMessageLayout.KIND_IDLE, reply.kind())
        handler.onPull(pullRequest(), reply)
        handler.close()
        assertEquals(2, reply.count)
        handler.onPull(pullRequest(), reply)
        assertEquals(3, reply.count)
        assertFalse(offer(IngestMessageLayout.KIND_DEPTH_IMAGE, 8))
    }

    @Test
    fun `the accepted kind mask filters deliveries`() {
        handler.onPull(pullRequest(1 shl IngestMessageLayout.KIND_DEPTH_IMAGE), reply)
        offer(IngestMessageLayout.KIND_POINT_CLOUD, 4)
        assertEquals(0, reply.count)
        offer(IngestMessageLayout.KIND_DEPTH_IMAGE, 4)
        assertEquals(IngestMessageLayout.KIND_DEPTH_IMAGE, reply.kind())
        handler.onPull(pullRequest(), reply)
        assertEquals(IngestMessageLayout.KIND_POINT_CLOUD, reply.kind())
    }

    @Test
    fun `photos and session events are delivered in order before freshest-only kinds`() {
        offer(IngestMessageLayout.KIND_DEPTH_IMAGE, 4)
        offer(IngestMessageLayout.KIND_PHOTO_SAVED, 128, fill = 1)
        offer(IngestMessageLayout.KIND_PHOTO_SAVED, 128, fill = 2)
        offer(IngestMessageLayout.KIND_SESSION_EVENT, 16)
        val order = mutableListOf<Int>()
        val firstBytes = mutableListOf<Int>()
        repeat(4) {
            handler.onPull(pullRequest(), reply)
            order += reply.kind()
            firstBytes += reply.last!!.get(IngestMessageLayout.HEADER_BYTES).toInt()
        }
        assertEquals(
            listOf(
                IngestMessageLayout.KIND_PHOTO_SAVED, IngestMessageLayout.KIND_PHOTO_SAVED,
                IngestMessageLayout.KIND_SESSION_EVENT, IngestMessageLayout.KIND_DEPTH_IMAGE,
            ),
            order,
        )
        assertEquals(listOf(1, 2), firstBytes.take(2))
    }

    @Test
    fun `replaced unsent messages are counted as dropped with a sequence gap`() {
        offer(IngestMessageLayout.KIND_POINT_CLOUD, 4, fill = 1)
        offer(IngestMessageLayout.KIND_POINT_CLOUD, 4, fill = 2)
        offer(IngestMessageLayout.KIND_POINT_CLOUD, 4, fill = 3)
        handler.countSourceDrop(IngestMessageLayout.KIND_POINT_CLOUD)
        handler.onPull(pullRequest(), reply)
        val b = reply.last!!
        assertEquals(2, b.getInt(IngestMessageLayout.SEQUENCE))
        assertEquals(3, b.getInt(IngestMessageLayout.DROPPED))
        assertEquals(3, b.get(IngestMessageLayout.HEADER_BYTES).toInt())
        assertEquals(2L, handler.replacedCount(IngestMessageLayout.KIND_POINT_CLOUD))
        offer(IngestMessageLayout.KIND_POINT_CLOUD, 4, fill = 4)
        handler.onPull(pullRequest(), reply)
        assertEquals(3, reply.last!!.getInt(IngestMessageLayout.SEQUENCE))
        assertEquals(0, reply.last!!.getInt(IngestMessageLayout.DROPPED))
    }

    @Test
    fun `a replied message is never mutated by later offers before the next pull`() {
        offer(IngestMessageLayout.KIND_DEPTH_IMAGE, 1024, fill = 10)
        handler.onPull(pullRequest(), reply)
        val first = reply.last!!
        val snapshot = reply.bytes()
        // The producer keeps publishing while the grid isolate handles the reply.
        for (i in 0 until 50) {
            offer(IngestMessageLayout.KIND_DEPTH_IMAGE, 1024, fill = 20 + i)
            offer(IngestMessageLayout.KIND_POINT_CLOUD, 512, fill = 90 + i)
            offer(IngestMessageLayout.KIND_PHOTO_SAVED, 128, fill = i)
        }
        val after = ByteArray(snapshot.size) { first.get(it) }
        assertArrayEquals(snapshot, after)
        assertEquals(snapshot.size, first.position())
        handler.onPull(pullRequest(), reply)
        assertNotSame(first, reply.last)
        // Producer buffers are reusable right after the offer: the queue copied them.
        val reused = message(IngestMessageLayout.KIND_DEPTH_IMAGE, 1024, fill = 7)
        handler.offer(reused, reused.capacity())
        IngestMessageWriter.begin(reused, IngestMessageLayout.KIND_IDLE)
        handler.onPull(pullRequest(1 shl IngestMessageLayout.KIND_DEPTH_IMAGE), reply)
        assertEquals(IngestMessageLayout.KIND_DEPTH_IMAGE, reply.kind())
        assertEquals(7, reply.last!!.get(IngestMessageLayout.HEADER_BYTES).toInt())
    }

    @Test
    fun `oversized and malformed offers are refused`() {
        assertFalse(offer(IngestMessageLayout.KIND_DEPTH_IMAGE, IngestMessageLayout.MAX_MESSAGE_BYTES))
        val bad = message(IngestMessageLayout.KIND_DEPTH_IMAGE, 8)
        bad.putInt(0, 0)
        assertFalse(handler.offer(bad, bad.capacity()))
        assertFalse(offer(IngestMessageLayout.KIND_IDLE, 0))
        repeat(IngestPullHandler.PHOTO_QUEUE) { assertTrue(offer(IngestMessageLayout.KIND_PHOTO_SAVED, 128)) }
        assertFalse(offer(IngestMessageLayout.KIND_PHOTO_SAVED, 128))
        assertEquals(1L, handler.queueOverflowCount)
    }

    @Test
    fun `debug recording frames every replied message`() {
        val dir = Files.createTempDirectory("ingest-recording").toFile()
        try {
            val recorder = IngestRecorder(dir, "SM-X700", wallClockNs = { 42L })
            val recorded = IngestPullHandler(clock, recorder)
            assertTrue(recorder.start())
            for (i in 0 until 3) {
                val b = message(IngestMessageLayout.KIND_POINT_CLOUD, 8 + i, fill = i)
                recorded.offer(b, b.capacity())
                recorded.onPull(pullRequest(), reply)
            }
            val path = recorder.stop()!!
            val bytes = ByteBuffer.wrap(java.io.File(path).readBytes()).order(ByteOrder.LITTLE_ENDIAN)
            assertEquals(IngestRecordingLayout.MAGIC, bytes.getInt(0))
            assertEquals(IngestRecorder.deviceModelHash("SM-X700"), bytes.getLong(IngestRecordingLayout.DEVICE_MODEL_HASH))
            assertEquals(42L, bytes.getLong(IngestRecordingLayout.CREATED_TIME_NS))
            assertEquals(3, bytes.getInt(IngestRecordingLayout.MESSAGE_COUNT))
            var at = IngestRecordingLayout.HEADER_BYTES
            for (i in 0 until 3) {
                val length = bytes.getInt(at)
                assertEquals(IngestMessageLayout.HEADER_BYTES + 8 + i, length)
                assertEquals(i, bytes.getInt(at + 4 + IngestMessageLayout.SEQUENCE))
                at += 4 + length
            }
            assertEquals(bytes.capacity(), at)
            assertFalse(recorder.isRecording)
            assertNull(recorder.stop())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `recording header matches the golden layout`() {
        val b = ByteBuffer.allocate(IngestRecordingLayout.HEADER_BYTES).order(ByteOrder.LITTLE_ENDIAN)
        val golden = ByteBuffer.wrap(golden("ingest_recording.bin")).order(ByteOrder.LITTLE_ENDIAN)
        IngestRecorder.writeHeader(
            b, golden.getLong(IngestRecordingLayout.DEVICE_MODEL_HASH),
            golden.getLong(IngestRecordingLayout.CREATED_TIME_NS), golden.getInt(IngestRecordingLayout.MESSAGE_COUNT),
        )
        assertArrayEquals(golden("ingest_recording.bin").copyOf(IngestRecordingLayout.HEADER_BYTES), b.array())
    }
}
