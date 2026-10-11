package com.uhg0.ar_flutter_plugin_2.visibilityingest

import com.uhg0.ar_flutter_plugin_2.visibilitycontract.IngestMessageLayout
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.IntBuffer

/**
 * Kind 2 point cloud producer (contract 2.4). At most every
 * [ACQUIRE_INTERVAL_NS] it copies the ARCore feature points (world space) into
 * an owned staging buffer, dropping non-finite points and points beyond
 * [FAR_METRES] and counting both. ARCore itself emits the non-finite points
 * (about 40 per batch on the SM-X700; see the task manifest note), so they are
 * filtered here once. Clouds lead their frame by about two frames: a cloud
 * newer than the newest pose waits for the covering frame before the pose at
 * its timestamp is matched.
 */
internal class PointCloudIngestProducer(
    private val session: IngestSessionState,
    private val sink: IngestMessageSink,
    private val clock: IngestClock,
) : PointCloudSink {
    companion object {
        const val ACQUIRE_INTERVAL_NS = 125_000_000L
        const val MAX_POINTS = 8_192
        const val FAR_METRES = 10_000f
    }

    private val staging = ByteBuffer.allocateDirect(
        IngestMessageLayout.HEADER_BYTES + IngestMessageLayout.POINT_COUNT_BYTES + MAX_POINTS * IngestMessageLayout.POINT_BYTES,
    ).order(ByteOrder.LITTLE_ENDIAN)
    private var staged = false
    private var stagedNs = 0L
    private var stagedWaited = 0
    private var lastAcquireNs = Long.MIN_VALUE
    private var lastCloudNs = Long.MIN_VALUE
    private var frameNs = 0L
    private var acquireNs = 0L

    @Volatile var clouds = 0L; private set
    @Volatile var pointsIn = 0L; private set
    @Volatile var pointsSent = 0L; private set
    @Volatile var nonFiniteDropped = 0L; private set
    @Volatile var farDropped = 0L; private set
    @Volatile var idCountMismatch = 0L; private set
    @Volatile var truncated = 0L; private set
    @Volatile var duplicateDropped = 0L; private set
    @Volatile var deferred = 0L; private set
    @Volatile var deferTimeouts = 0L; private set
    @Volatile var stagedReplaced = 0L; private set
    @Volatile var published = 0L; private set
    @Volatile var poseMissing = 0L; private set

    /** Non-finite points of the last cloud (diagnostics). */
    @Volatile var lastNonFinite = 0; private set

    fun reset() {
        staged = false
        lastAcquireNs = Long.MIN_VALUE
        lastCloudNs = Long.MIN_VALUE
    }

    fun onFrame(frame: IngestFrameSource) {
        val now = clock.nowNs()
        if (lastAcquireNs != Long.MIN_VALUE && now - lastAcquireNs < ACQUIRE_INTERVAL_NS) return
        frameNs = frame.timestampNs
        acquireNs = now
        if (frame.acquirePointCloud(this)) {
            clouds++
            lastAcquireNs = now
        }
    }

    override fun onPointCloud(timestampNs: Long, points: FloatBuffer, ids: IntBuffer) {
        if (timestampNs == lastCloudNs) {
            duplicateDropped++
            return
        }
        lastCloudNs = timestampNs
        val fromPoints = points.remaining() / 4
        val fromIds = ids.remaining()
        if (fromPoints != fromIds) idCountMismatch++
        var n = if (fromPoints < fromIds) fromPoints else fromIds
        if (n > MAX_POINTS) {
            truncated++
            n = MAX_POINTS
        }
        if (staged) {
            stagedReplaced++
            sink.countSourceDrop(IngestMessageLayout.KIND_POINT_CLOUD)
        }
        val b = staging
        IngestMessageWriter.begin(b, IngestMessageLayout.KIND_POINT_CLOUD)
        IngestMessageWriter.sourceFlags(b, IngestMessageLayout.SOURCE_POINTS)
        IngestMessageWriter.timing(b, timestampNs, frameNs, acquireNs)
        val base = points.position()
        val idBase = ids.position()
        var kept = 0
        var nonFinite = 0
        var far = 0
        var o = IngestMessageLayout.HEADER_BYTES + IngestMessageLayout.POINT_COUNT_BYTES
        for (i in 0 until n) {
            val x = points.get(base + 4 * i)
            val y = points.get(base + 4 * i + 1)
            val z = points.get(base + 4 * i + 2)
            val c = points.get(base + 4 * i + 3)
            if (!x.isFinite() || !y.isFinite() || !z.isFinite() || !c.isFinite()) {
                nonFinite++
                continue
            }
            if (Math.abs(x) > FAR_METRES || Math.abs(y) > FAR_METRES || Math.abs(z) > FAR_METRES) {
                far++
                continue
            }
            b.putInt(o, ids.get(idBase + i))
            b.putFloat(o + 4, x)
            b.putFloat(o + 8, y)
            b.putFloat(o + 12, z)
            b.putFloat(o + 16, c)
            o += IngestMessageLayout.POINT_BYTES
            kept++
        }
        b.putInt(IngestMessageLayout.HEADER_BYTES, kept)
        IngestMessageWriter.payloadBytes(b, IngestMessageLayout.POINT_COUNT_BYTES + kept * IngestMessageLayout.POINT_BYTES)
        pointsIn += n
        pointsSent += kept
        nonFiniteDropped += nonFinite
        farDropped += far
        lastNonFinite = nonFinite
        staged = true
        stagedNs = timestampNs
        stagedWaited = 0
        if (!session.history.readyToMatch(timestampNs, 0)) deferred++
    }

    fun publishStaged(newFrame: Boolean) {
        if (!staged) return
        if (newFrame) stagedWaited++
        val history = session.history
        if (!history.readyToMatch(stagedNs, stagedWaited)) return
        if (stagedNs > history.newestNs) deferTimeouts++
        val match = history.match(stagedNs, session.matched)
        if (match == IngestMessageLayout.POSE_MATCH_MISSING) poseMissing++
        IngestMessageWriter.pose(staging, session.trackingState, match, session.groupEpoch,
            gapUs(history.errorNs), session.matched)
        staged = false
        if (sink.offer(staging, IngestMessageWriter.messageBytes(staging))) published++
    }
}
