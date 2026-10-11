package com.uhg0.ar_flutter_plugin_2.posetracking

import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.uhg0.ar_flutter_plugin_2.visibilitycontract.IngestMessageLayout
import com.uhg0.ar_flutter_plugin_2.visibilityingest.IngestClock
import io.flutter.plugin.common.BinaryMessenger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Registers the pose tracking channel on a recording messenger (on device,
 * with the real Flutter embedding classes) and checks that a `C3PQ` query is
 * answered with a valid kind 7 reply without records.
 */
@RunWith(AndroidJUnit4::class)
class PoseTrackingChannelInstrumentedTest {
    private class RecordingMessenger : BinaryMessenger {
        var channel: String? = null
        var handler: BinaryMessenger.BinaryMessageHandler? = null
        var taskQueue: BinaryMessenger.TaskQueue? = null
        private val queue = object : BinaryMessenger.TaskQueue {}

        override fun makeBackgroundTaskQueue(options: BinaryMessenger.TaskQueueOptions): BinaryMessenger.TaskQueue = queue

        override fun send(channel: String, message: ByteBuffer?) = Unit

        override fun send(channel: String, message: ByteBuffer?, callback: BinaryMessenger.BinaryReply?) = Unit

        override fun setMessageHandler(channel: String, handler: BinaryMessenger.BinaryMessageHandler?) {
            setMessageHandler(channel, handler, null)
        }

        override fun setMessageHandler(
            channel: String,
            handler: BinaryMessenger.BinaryMessageHandler?,
            taskQueue: BinaryMessenger.TaskQueue?,
        ) {
            this.channel = channel
            this.handler = handler
            this.taskQueue = taskQueue
        }
    }

    @Test
    fun queryIsAnsweredWithAnEmptyKindSevenReply() {
        val messenger = RecordingMessenger()
        val producer = AnchorPoseUpdateProducer(IngestClock { SystemClock.elapsedRealtimeNanos() })
        producer.bind(messenger)
        try {
            assertEquals(PoseTrackingLayout.CHANNEL, messenger.channel)
            assertNotNull("handler runs on a background task queue", messenger.taskQueue)
            val query = ByteBuffer.allocateDirect(PoseQueryLayout.HEADER_BYTES).order(ByteOrder.LITTLE_ENDIAN)
            query.putInt(0, PoseQueryLayout.MAGIC)
            query.putShort(PoseQueryLayout.VERSION_OFFSET, PoseQueryLayout.VERSION.toShort())
            query.putShort(PoseQueryLayout.FLAGS_OFFSET, PoseQueryLayout.FLAG_TRACKING.toShort())
            query.putShort(PoseQueryLayout.GROUP_EPOCH_OFFSET, 3)
            var reply: ByteBuffer? = null
            checkNotNull(messenger.handler).onMessage(query) { reply = it }
            val b = checkNotNull(reply).order(ByteOrder.LITTLE_ENDIAN)
            assertEquals(IngestMessageLayout.HEADER_BYTES + AnchorPoseUpdateLayout.PREFIX_BYTES, b.position())
            assertEquals(IngestMessageLayout.MAGIC, b.getInt(0))
            assertEquals(PoseTrackingLayout.ANCHOR_POSE_UPDATE_KIND, b.get(IngestMessageLayout.KIND).toInt())
            assertEquals(AnchorPoseUpdateLayout.PREFIX_BYTES, b.getInt(IngestMessageLayout.PAYLOAD_BYTES))
            assertEquals(3, b.getShort(IngestMessageLayout.GROUP_EPOCH).toInt())
            assertEquals(0, b.getShort(IngestMessageLayout.HEADER_BYTES).toInt())
            assertEquals(0, b.getShort(IngestMessageLayout.HEADER_BYTES + 2).toInt())
            assertEquals(0, b.getInt(AnchorPoseUpdateLayout.TRACKED_ANCHORS_OFFSET))
        } finally {
            producer.close()
        }
        assertSame(null, messenger.handler)
    }
}
