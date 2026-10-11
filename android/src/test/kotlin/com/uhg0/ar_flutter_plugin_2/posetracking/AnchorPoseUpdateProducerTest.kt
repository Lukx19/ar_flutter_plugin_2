package com.uhg0.ar_flutter_plugin_2.posetracking

import com.uhg0.ar_flutter_plugin_2.visibilitycontract.IngestMessageLayout
import com.uhg0.ar_flutter_plugin_2.visibilitycontract.PhotoSavedLayout
import com.uhg0.ar_flutter_plugin_2.visibilityingest.DurableJpegLocator
import com.uhg0.ar_flutter_plugin_2.visibilityingest.DurablePhoto
import com.uhg0.ar_flutter_plugin_2.visibilityingest.IngestTrackingState
import com.uhg0.ar_flutter_plugin_2.visibilityingest.StillCameraModel
import io.flutter.plugin.common.BasicMessageChannel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Channel side of the pose tracker: reply bytes, splitting, malformed queries and durable photos. */
class AnchorPoseUpdateProducerTest {
    private val clock = FakePoseClock(5_000_000_000L)
    private val backend = FakeAnchorBackend()
    private val producer = AnchorPoseUpdateProducer(clock, backend, readTimer = { 0L }, initialCapacity = 64)
    private val frame = FakeAnchorFrame()

    private class Capture : BasicMessageChannel.Reply<ByteBuffer> {
        var last: ByteBuffer? = null
        var count = 0
        override fun reply(reply: ByteBuffer?) {
            last = reply
            count++
        }
    }

    private val reply = Capture()

    private fun send(query: ByteBuffer?): DecodedReply? {
        producer.onQuery(query, reply)
        val b = reply.last ?: return null
        return DecodedReply(b, b.position())
    }

    private fun step() {
        frame.timestampNs += 33_000_000L
        producer.tracker.onFrame(SESSION, frame)
    }

    @Test
    fun `reply header and records follow the kind 7 layout`() {
        frame.cameraTracking = IngestTrackingState.TRACKING
        frame.cameraTq[0] = 1f
        frame.cameraTq[1] = 2f
        frame.cameraTq[2] = 3f
        send(poseQuery(groupEpoch = 9, requests = listOf(KeyframeRequest(KEY, 77L, 88L, identityPose(0.5f, 0.25f, -1f)))))
        step()
        clock.now += 1_000L
        val r = checkNotNull(send(poseQuery(groupEpoch = 9)))
        val b = r.bytes
        val expectedPayload = AnchorPoseUpdateLayout.payloadBytes(1, 1)
        assertEquals(IngestMessageLayout.HEADER_BYTES + expectedPayload, r.length)
        assertEquals(IngestMessageLayout.MAGIC, b.getInt(0))
        assertEquals(IngestMessageLayout.VERSION, b.getShort(4).toInt())
        assertEquals(PoseTrackingLayout.ANCHOR_POSE_UPDATE_KIND, b.get(IngestMessageLayout.KIND).toInt())
        assertEquals(1, b.getInt(IngestMessageLayout.SEQUENCE))
        assertEquals(expectedPayload, b.getInt(IngestMessageLayout.PAYLOAD_BYTES))
        assertEquals(frame.timestampNs, b.getLong(IngestMessageLayout.SAMPLE_TIMESTAMP_NS))
        assertEquals(frame.timestampNs, b.getLong(IngestMessageLayout.FRAME_TIMESTAMP_NS))
        assertEquals(clock.now, b.getLong(IngestMessageLayout.ACQUIRE_TIME_NS))
        assertEquals(clock.now, b.getLong(IngestMessageLayout.SEND_TIME_NS))
        assertEquals(IngestTrackingState.TRACKING, b.get(IngestMessageLayout.TRACKING_STATE).toInt())
        assertEquals(IngestMessageLayout.POSE_MATCH_EXACT, b.get(IngestMessageLayout.POSE_MATCH).toInt())
        assertEquals(9, b.getShort(IngestMessageLayout.GROUP_EPOCH).toInt())
        // Column-major camera-to-world: identity rotation, translation in column 3.
        assertEquals(1f, b.getFloat(IngestMessageLayout.CAMERA_TO_WORLD), 0f)
        assertEquals(1f, b.getFloat(IngestMessageLayout.CAMERA_TO_WORLD + 12 * 4), 0f)
        assertEquals(2f, b.getFloat(IngestMessageLayout.CAMERA_TO_WORLD + 13 * 4), 0f)
        assertEquals(3f, b.getFloat(IngestMessageLayout.CAMERA_TO_WORLD + 14 * 4), 0f)
        assertEquals(1f, b.getFloat(IngestMessageLayout.CAMERA_TO_WORLD + 15 * 4), 0f)
        assertEquals(1, b.getInt(AnchorPoseUpdateLayout.TRACKED_ANCHORS_OFFSET))
        assertEquals(148, AnchorPoseUpdateLayout.TRACKED_ANCHORS_OFFSET)
        assertEquals(152, AnchorPoseUpdateLayout.ANCHOR_READ_MAX_MICROS_OFFSET)
        assertEquals(156, AnchorPoseUpdateLayout.FRAME_INTERVAL_MAX_MICROS_OFFSET)

        assertEquals(1, b.getShort(160).toInt())
        assertEquals(1, b.getShort(162).toInt())
        val u = 164
        assertEquals(KEY, b.getInt(u))
        assertEquals(AnchorKind.KEYFRAME, b.get(u + 4).toInt())
        assertEquals(AnchorTracking.TRACKING, b.get(u + 5).toInt())
        assertEquals(0, b.getShort(u + 6).toInt())
        val rows = FloatArray(12) { b.getFloat(u + 8 + 4 * it) }
        assertEquals(listOf(1f, 0f, 0f, 0.5f, 0f, 1f, 0f, 0.25f, 0f, 0f, 1f, -1f), rows.toList())
        val c = u + AnchorPoseUpdateLayout.UPDATE_BYTES
        assertEquals(KEY, b.getInt(c))
        assertEquals(AnchorKind.KEYFRAME, b.get(c + 4).toInt())
        assertEquals(AnchorCreation.CREATED, b.get(c + 5).toInt())
        assertEquals(77L, b.getLong(c + 8))
        assertEquals(88L, b.getLong(c + 16))
        assertEquals(frame.timestampNs, b.getLong(c + 24))
        assertEquals(0L, b.getLong(c + 32))
        assertEquals(IngestMessageLayout.HEADER_BYTES + expectedPayload, c + AnchorPoseUpdateLayout.CREATION_BYTES)
    }

    @Test
    fun `replies alternate between two buffers and never park`() {
        val first = checkNotNull(send(poseQuery())).bytes
        val second = checkNotNull(send(poseQuery())).bytes
        val third = checkNotNull(send(poseQuery())).bytes
        assertNotSame(first, second)
        assertTrue(first === third)
        assertEquals(3, reply.count)
        assertEquals(IngestMessageLayout.HEADER_BYTES + 4, third.position())
        assertEquals(0, third.getShort(160).toInt())
        assertEquals(0, third.getShort(162).toInt())
    }

    @Test
    fun `more than 1024 dirty anchors split across replies`() {
        send(poseQuery())
        val tq = identityPose()
        repeat(1100) { producer.tracker.enqueuePhoto(it.toLong(), 0L, tq, true) }
        step()
        val first = checkNotNull(send(poseQuery()))
        assertEquals(AnchorPoseUpdateLayout.MAX_UPDATES, first.updateCount)
        assertEquals(AnchorPoseUpdateLayout.MAX_CREATIONS, first.creationCount)
        assertEquals(AnchorPoseUpdateLayout.MAX_MESSAGE_BYTES, first.length)
        val second = checkNotNull(send(poseQuery()))
        assertEquals(76, second.updateCount)
        assertEquals(64, second.creationCount)
        val ids = (first.updates + second.updates).map { it.anchorId }.toSet()
        assertEquals((1..1100).toSet(), ids)
        var creations = first.creationCount + second.creationCount
        while (true) {
            val next = checkNotNull(send(poseQuery()))
            assertEquals(0, next.updateCount)
            if (next.creationCount == 0) break
            creations += next.creationCount
        }
        assertEquals(1100, creations)
    }

    @Test
    fun `a malformed query gets a null reply and is counted`() {
        val bad = poseQuery()
        bad.putInt(0, 0x12345678)
        assertNull(send(bad))
        assertNull(send(null))
        val short = ByteBuffer.allocateDirect(8).order(ByteOrder.LITTLE_ENDIAN)
        assertNull(send(short))
        val truncated = poseQuery(requests = listOf(KeyframeRequest(KEY)))
        truncated.limit(PoseQueryLayout.HEADER_BYTES + 10)
        assertNull(send(truncated))
        assertEquals(4L, producer.snapshot()["malformedQueries"])
        assertEquals(4, reply.count)
        val good = checkNotNull(send(poseQuery()))
        assertEquals("sequence counts only replies", 0, good.bytes.getInt(IngestMessageLayout.SEQUENCE))
    }

    @Test
    fun `a durable photo yields a photo anchor at its camera pose`() {
        send(poseQuery())
        producer.onPhotoDurable(photo(exposureNs = 4242L, tx = 1.5f))
        step()
        val r = checkNotNull(send(poseQuery()))
        val created = r.creations.single()
        assertEquals(1, created.anchorId)
        assertEquals(AnchorKind.PHOTO, created.kind)
        assertEquals(AnchorCreation.CREATED, created.status)
        assertEquals(0x0807060504030201L, created.subject)
        assertEquals(4242L, created.captureNs)
        assertEquals(1.5f, backend.created.single().pose[0], 0f)
        assertEquals(1.5f, r.updates.single().rows[3], 0f)
    }

    @Test
    fun `durable photos are ignored while tracking is off`() {
        producer.onPhotoDurable(photo(exposureNs = 1L, tx = 0f))
        send(poseQuery())
        step()
        assertEquals(0, checkNotNull(send(poseQuery())).creationCount)
    }

    private fun photo(exposureNs: Long, tx: Float): DurablePhoto {
        val e = ByteBuffer.allocate(PhotoSavedLayout.BYTES).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until 32) e.put(PhotoSavedLayout.PHOTO_ID + i, (i + 1).toByte())
        e.putLong(PhotoSavedLayout.EXPOSURE_TIMESTAMP_NS, exposureNs)
        val rows = floatArrayOf(1f, 0f, 0f, tx, 0f, 1f, 0f, 0f, 0f, 0f, 1f, 0f)
        for (k in 0 until 12) e.putFloat(PhotoSavedLayout.CAMERA_TO_WORLD_ROWS + 4 * k, rows[k])
        val bytes = e.array()
        return DurablePhoto(
            bytes.copyOf(32), bytes, "capture", "manifest", "attempt", exposureNs, null, 0L,
            StillCameraModel(), DurableJpegLocator(null),
        )
    }

    private companion object {
        const val KEY = AnchorIds.FIRST_KEYFRAME_ID
        val SESSION = Any()
    }

    @Test
    fun `durable photo listener is registered only while tracking is armed`() {
        val switches = ArrayList<Boolean>()
        val switching = AnchorPoseUpdateProducer(
            clock, backend, readTimer = { 0L }, initialCapacity = 64,
            photoListenerSwitch = { _, add -> switches += add },
        )
        val capture = Capture()
        switching.onQuery(poseQuery(groupEpoch = 1), capture)
        switching.onQuery(poseQuery(groupEpoch = 1), capture)
        assertEquals(listOf(true), switches)
        switching.onQuery(poseQuery(groupEpoch = 1, flags = PoseQueryLayout.FLAG_DETACH_ALL), capture)
        assertEquals(listOf(true, false), switches)
        switching.onQuery(poseQuery(groupEpoch = 1), capture)
        switching.close()
        assertEquals(listOf(true, false, true, false), switches)
    }
}
