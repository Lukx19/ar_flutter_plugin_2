package com.uhg0.ar_flutter_plugin_2.markerdetection

import com.uhg0.ar_flutter_plugin_2.visibilitycontract.IngestMessageLayout
import com.uhg0.ar_flutter_plugin_2.visibilitycontract.ShellControlLayout
import com.uhg0.ar_flutter_plugin_2.visibilityingest.CapturingReply
import com.uhg0.ar_flutter_plugin_2.visibilityingest.DurableJpegLocator
import com.uhg0.ar_flutter_plugin_2.visibilityingest.DurablePhoto
import com.uhg0.ar_flutter_plugin_2.visibilityingest.FakeClock
import com.uhg0.ar_flutter_plugin_2.visibilityingest.IngestPullHandler
import com.uhg0.ar_flutter_plugin_2.visibilityingest.IngestShellConfig
import com.uhg0.ar_flutter_plugin_2.visibilityingest.StillCameraModel
import com.uhg0.ar_flutter_plugin_2.visibilityingest.VisibilityGridIngestShell
import com.uhg0.ar_flutter_plugin_2.visibilityingest.pullRequest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Photo tie points travel from the durable photo listener through the
 * ingest pull queue as kind 8 messages: the relay forwards only while the
 * shell runs marker detection, the writer wraps each `C3TB` batch into an
 * in-order kind 8 message, and pulls deliver it only to a 16-bit mask that
 * accepts kind 8.
 */
class TiePointTransportTest {
    private val clock = FakeClock(5_000L)
    private val pull = IngestPullHandler(clock)
    private val reply = CapturingReply()

    private fun batch(records: Int, epoch: Int, exposureNs: Long): ByteBuffer {
        val b = ByteBuffer.allocate(TiePointBatchLayout.HEADER_BYTES + records * MarkerDetectorNative.TIE_POINT_BYTES)
            .order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until records * MarkerDetectorNative.TIE_POINT_BYTES) {
            b.put(TiePointBatchLayout.HEADER_BYTES + i, (i * 7).toByte())
        }
        TiePointBatchLayout.writeHeader(b, records, ByteArray(32) { it.toByte() }, 4128, 3096, epoch, exposureNs, 0, 81.5f)
        return b
    }

    @Test
    fun `batches become in-order kind 8 messages for a mask that accepts them`() {
        val writer = TiePointIngestWriter({ pull }, clock)
        val first = batch(2, 3, 111L)
        val second = batch(1, 3, 222L)
        writer.onTiePointBatch(first, first.capacity())
        writer.onTiePointBatch(second, second.capacity())
        assertEquals(2L, writer.offered)

        // A grid isolate on the first contract (8-bit mask) never sees kind 8.
        pull.onPull(pullRequest(0x7e), reply)
        assertEquals(0, reply.count)
        pull.tick()

        pull.onPull(pullRequest(0x17e), reply)
        val bytes = reply.bytes()
        val m = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(IngestMessageLayout.KIND_PHOTO_TIE_POINTS, reply.kind())
        assertEquals(first.capacity(), m.getInt(IngestMessageLayout.PAYLOAD_BYTES))
        assertEquals(3, m.getShort(IngestMessageLayout.GROUP_EPOCH).toInt())
        assertEquals(111L, m.getLong(IngestMessageLayout.SAMPLE_TIMESTAMP_NS))
        assertEquals(IngestMessageLayout.POSE_MATCH_MISSING, m.get(IngestMessageLayout.POSE_MATCH).toInt())
        val payload = bytes.copyOfRange(IngestMessageLayout.HEADER_BYTES, bytes.size)
        assertArrayEquals(first.array(), payload)

        pull.onPull(pullRequest(0x17e), reply)
        val next = ByteBuffer.wrap(reply.bytes()).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(222L, next.getLong(IngestMessageLayout.SAMPLE_TIMESTAMP_NS))
        assertEquals(1, next.getInt(IngestMessageLayout.SEQUENCE))
    }

    @Test
    fun `the queue keeps eight photos and counts the overflow`() {
        val writer = TiePointIngestWriter({ pull }, clock)
        val b = batch(MarkerDetectorNative.MAX_MARKERS, 1, 1L)
        repeat(IngestPullHandler.TIE_POINT_QUEUE + 1) { writer.onTiePointBatch(b, b.capacity()) }
        assertEquals(IngestPullHandler.TIE_POINT_QUEUE.toLong(), writer.offered)
        assertEquals(1L, writer.refused)
        assertEquals(1L, pull.queueOverflowCount)
    }

    @Test
    fun `without a running shell nothing is offered`() {
        val writer = TiePointIngestWriter({ null }, clock)
        val b = batch(1, 1, 1L)
        writer.onTiePointBatch(b, b.capacity())
        assertEquals(0L, writer.offered)
        assertEquals(1L, writer.refused)
    }

    private class RecordingTarget : TiePointPhotoTarget {
        var photos = 0
        var epoch = -1
        var distortion: DoubleArray? = null
        var path: (() -> String?)? = null
        var size = 0.0

        override fun onPhotoDurable(
            photoId: ByteArray,
            eventBytes: ByteArray,
            jpegPath: () -> String?,
            groupEpoch: Int,
            distortion: DoubleArray?,
        ) {
            photos++
            epoch = groupEpoch
            this.distortion = distortion
            path = jpegPath
        }

        override fun ensureMarkerSize(markerSizeMetres: Double) {
            size = markerSizeMetres
        }
    }

    private fun photo(camera: StillCameraModel) = DurablePhoto(
        ByteArray(32), ByteArray(128), "capture", "manifest", "attempt", 1L, null, 0L, camera, DurableJpegLocator(null),
    )

    @Test
    fun `durable photos reach tie point detection only while marker detection runs`() {
        val shell = VisibilityGridIngestShell(clock, null)
        val target = RecordingTarget()
        val relay = TiePointPhotoRelay(target) { shell }
        val camera = StillCameraModel().apply {
            fx = 3000.0; fy = 3000.0; cx = 2064.0; cy = 1548.0; width = 4128; height = 3096
        }
        relay.onPhotoDurable(photo(camera))
        assertEquals(0, target.photos)

        shell.start(IngestShellConfig(ShellControlLayout.FLAG_POINT_CLOUD, 0, 0, 0, 0f, 4))
        relay.onPhotoDurable(photo(camera))
        assertEquals(0, target.photos)

        shell.start(IngestShellConfig(ShellControlLayout.FLAG_MARKER_DETECTION, 0, 0, 8, 0.1f, 4))
        // Camera2 order (k1, k2, k3, p1, p2) becomes (k1, k2, p1, p2, k3).
        doubleArrayOf(0.1, -0.2, 0.05, 0.001, -0.002).copyInto(camera.distortion)
        relay.onPhotoDurable(photo(camera))
        assertEquals(1, target.photos)
        assertEquals(0.1, target.size, 1e-6)
        assertArrayEquals(doubleArrayOf(0.1, -0.2, 0.001, -0.002, 0.05), target.distortion, 0.0)
        // No JPEG in the store: resolved lazily to null on the detection thread.
        assertNull(target.path!!())
        assertTrue(relay.relayed == 1L)
        assertNull(TiePointPhotoRelay.distortionOf(StillCameraModel()))
    }
}
