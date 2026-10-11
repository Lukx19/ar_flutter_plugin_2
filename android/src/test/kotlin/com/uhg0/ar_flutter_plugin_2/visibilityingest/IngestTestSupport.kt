package com.uhg0.ar_flutter_plugin_2.visibilityingest

import com.uhg0.ar_flutter_plugin_2.visibilitycontract.IngestMessageLayout
import io.flutter.plugin.common.BasicMessageChannel
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.IntBuffer

internal val goldenDirectory = File("../test_assets/visibility_grid")

internal fun golden(name: String): ByteArray = File(goldenDirectory, name).readBytes()

internal class FakeClock(var now: Long = 0L) : IngestClock {
    override fun nowNs(): Long = now
}

/** Captures replies without allocating per reply. */
internal class CapturingReply : BasicMessageChannel.Reply<ByteBuffer> {
    var last: ByteBuffer? = null
    var count = 0

    override fun reply(reply: ByteBuffer?) {
        last = reply
        count++
    }

    /** Bytes of the last reply, `[0, position)`. */
    fun bytes(): ByteArray {
        val b = checkNotNull(last)
        val out = ByteArray(b.position())
        for (i in out.indices) out[i] = b.get(i)
        return out
    }

    fun kind(): Int = checkNotNull(last).get(IngestMessageLayout.KIND).toInt()
}

internal fun pullRequest(mask: Int = 0x7e): ByteBuffer {
    val b = ByteBuffer.allocateDirect(IngestMessageLayout.PULL_REQUEST_BYTES).order(ByteOrder.LITTLE_ENDIAN)
    b.putInt(0, IngestMessageLayout.PULL_REQUEST_MAGIC).putShort(4, 1).putShort(6, mask.toShort())
    return b
}

/** Scripted frame: pose, intrinsics, an optional depth image and point cloud. */
internal class FakeFrame : IngestFrameSource {
    override var timestampNs = 0L
    override var androidCameraTimestampNs = 0L
    override var trackingState = IngestTrackingState.TRACKING
    val pose = floatArrayOf(0f, 0f, 0f, 0f, 0f, 0f, 1f)
    val texture = floatArrayOf(180f, 181f, 80f, 45f, 4f, 3f)

    var depthResult = IngestFrameSource.DEPTH_OK
    var depthImageNs = 0L
    var depthWidth = 4
    var depthHeight = 3
    var depth: ByteBuffer? = null
    var depthRowStride = 8
    var confidence: ByteBuffer? = null
    var depthCalls = 0

    var cloudNs = 0L
    var points: FloatBuffer? = null
    var ids: IntBuffer? = null

    override fun readPose(out: FloatArray) {
        pose.copyInto(out)
    }

    override fun readTextureIntrinsics(out: FloatArray): Boolean {
        texture.copyInto(out)
        return true
    }

    override fun acquireDepth(mode: Int, sink: DepthImageSink): Int {
        depthCalls++
        if (depthResult != IngestFrameSource.DEPTH_OK) return depthResult
        val d = depth ?: return IngestFrameSource.DEPTH_NOT_YET_AVAILABLE
        sink.onDepthImage(depthImageNs, depthWidth, depthHeight, d, depthRowStride, 2, confidence, depthWidth, 1)
        return IngestFrameSource.DEPTH_OK
    }

    override fun acquirePointCloud(sink: PointCloudSink): Boolean {
        val p = points ?: return false
        val i = ids ?: return false
        sink.onPointCloud(cloudNs, p, i)
        return true
    }

    /** The golden 4 x 3 depth: (y * 4 + x + 1) * 250 mm, confidence (y * 4 + x) * 20. */
    fun goldenDepth() {
        val d = ByteBuffer.allocateDirect(4 * 3 * 2) // big-endian view of little-endian data, as ARCore planes
        val c = ByteBuffer.allocateDirect(4 * 3)
        for (i in 0 until 12) {
            val mm = (i + 1) * 250
            d.put(2 * i, (mm and 0xff).toByte())
            d.put(2 * i + 1, (mm shr 8).toByte())
            c.put(i, (i * 20).toByte())
        }
        depth = d
        confidence = c
        depthWidth = 4
        depthHeight = 3
        depthRowStride = 8
    }
}

internal fun pointCloud(values: FloatArray, idValues: IntArray): Pair<FloatBuffer, IntBuffer> =
    FloatBuffer.wrap(values) to IntBuffer.wrap(idValues)
