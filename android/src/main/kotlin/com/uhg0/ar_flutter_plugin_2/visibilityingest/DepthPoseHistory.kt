package com.uhg0.ar_flutter_plugin_2.visibilityingest

import com.uhg0.ar_flutter_plugin_2.visibilitycontract.IngestMessageLayout

/**
 * Ring of recent camera poses keyed by a sensor timestamp, used to find the
 * pose at an older sample timestamp. Ported from the measured prototype
 * (`benchmarks/visibility_grid/.../PoseHistory.kt`): depth images are about
 * 170 ms older than the frame they are acquired with and point clouds lead it
 * by about two frames.
 *
 * Allocation-free after construction. Poses are ARCore `Camera.getPose()`
 * (sensor oriented, camera looks along -Z, OpenGL axes) stored as translation
 * plus quaternion; [lookup] writes a column-major camera-to-world matrix like
 * `Pose.toMatrix`.
 *
 * Lookup kinds:
 * - [EXACT]: an entry has exactly the requested timestamp.
 * - [INTERPOLATED]: between two neighbours; translation lerped, rotation slerped.
 * - [CLAMPED_OLD] / [CLAMPED_NEW]: before the oldest or after the newest entry.
 * - [NONE]: the ring is empty.
 *
 * [contractPoseMatch] maps a lookup to the header byte 49 of contract 2.4.
 */
internal class DepthPoseHistory(private val capacity: Int = DEFAULT_CAPACITY) {
    companion object {
        const val NONE = 0
        const val EXACT = 1
        const val INTERPOLATED = 2
        const val CLAMPED_OLD = 3
        const val CLAMPED_NEW = 4
        private const val STRIDE = 7 // tx, ty, tz, qx, qy, qz, qw

        /** About 8.5 s at 30 Hz; 64 frames were too short under load on the tablet. */
        const val DEFAULT_CAPACITY = 256

        /** Nearest match tolerance of the contract (pose match 2). */
        const val NEAREST_TOLERANCE_NS = 20_000_000L

        /** Interpolation across a wider frame gap is not trusted (the still-capture bracket limit). */
        const val MAX_INTERPOLATION_SPAN_NS = 300_000_000L

        /** A sample newer than the newest pose waits at most this many new frame poses (a repeated frame timestamp does not count). */
        const val MAX_DEFER_FRAMES = 6

        /** Depth images older than this relative to their frame are dropped at the source. */
        const val MAX_DEPTH_AGE_NS = 500_000_000L

        fun isStaleDepth(frameTimestampNs: Long, imageTimestampNs: Long): Boolean =
            frameTimestampNs - imageTimestampNs > MAX_DEPTH_AGE_NS

        /** Contract pose match code for a lookup [kind] with [errorNs] and [spanNs]. */
        fun contractPoseMatch(kind: Int, errorNs: Long, spanNs: Long): Int = when (kind) {
            EXACT -> IngestMessageLayout.POSE_MATCH_EXACT
            INTERPOLATED -> when {
                spanNs <= MAX_INTERPOLATION_SPAN_NS -> IngestMessageLayout.POSE_MATCH_INTERPOLATED
                errorNs in -NEAREST_TOLERANCE_NS..NEAREST_TOLERANCE_NS -> IngestMessageLayout.POSE_MATCH_NEAREST_WITHIN_20_MS
                else -> IngestMessageLayout.POSE_MATCH_MISSING
            }
            CLAMPED_OLD, CLAMPED_NEW ->
                if (errorNs in -NEAREST_TOLERANCE_NS..NEAREST_TOLERANCE_NS) IngestMessageLayout.POSE_MATCH_NEAREST_WITHIN_20_MS
                else IngestMessageLayout.POSE_MATCH_MISSING
            else -> IngestMessageLayout.POSE_MATCH_MISSING
        }
    }

    private val ts = LongArray(capacity)
    private val tq = FloatArray(capacity * STRIDE)
    private val nearestTq = FloatArray(STRIDE)
    private var head = 0
    var count = 0
        private set

    /** Signed distance of the last lookup to the nearest entry (requested minus entry). */
    var errorNs = 0L
        private set

    /** Gap between the two neighbours of the last lookup (0 unless interpolated). */
    var spanNs = 0L
        private set

    /** Pose of the last lookup as tx, ty, tz, qx, qy, qz, qw. */
    val lastTq = FloatArray(STRIDE)

    private fun slot(k: Int): Int = (head - 1 - k + 2 * capacity) % capacity

    /** Adds a pose; ignores timestamps not newer than the newest entry. */
    fun push(timestampNs: Long, tx: Float, ty: Float, tz: Float, qx: Float, qy: Float, qz: Float, qw: Float): Boolean {
        if (count > 0 && timestampNs <= ts[slot(0)]) return false
        val o = head * STRIDE
        ts[head] = timestampNs
        tq[o] = tx
        tq[o + 1] = ty
        tq[o + 2] = tz
        tq[o + 3] = qx
        tq[o + 4] = qy
        tq[o + 5] = qz
        tq[o + 6] = qw
        head = (head + 1) % capacity
        if (count < capacity) count++
        return true
    }

    fun pushTq(timestampNs: Long, pose: FloatArray): Boolean =
        push(timestampNs, pose[0], pose[1], pose[2], pose[3], pose[4], pose[5], pose[6])

    val newestNs: Long get() = if (count == 0) Long.MIN_VALUE else ts[slot(0)]
    val oldestNs: Long get() = if (count == 0) Long.MIN_VALUE else ts[slot(count - 1)]

    /** A sample newer than the newest pose is matched once a covering frame arrived, or after [MAX_DEFER_FRAMES]. */
    fun readyToMatch(sampleTimestampNs: Long, waitedFrames: Int): Boolean =
        count > 0 && (sampleTimestampNs <= ts[slot(0)] || waitedFrames >= MAX_DEFER_FRAMES)

    fun clear() {
        head = 0
        count = 0
    }

    fun lookup(timestampNs: Long, out: FloatArray): Int {
        errorNs = 0L
        spanNs = 0L
        if (count == 0) return NONE
        val newest = slot(0)
        if (timestampNs >= ts[newest]) {
            errorNs = timestampNs - ts[newest]
            write(newest, out)
            return if (errorNs == 0L) EXACT else CLAMPED_NEW
        }
        for (k in 1 until count) {
            val older = slot(k)
            val t0 = ts[older]
            if (t0 <= timestampNs) {
                val newer = slot(k - 1)
                val t1 = ts[newer]
                if (t0 == timestampNs) {
                    write(older, out)
                    return EXACT
                }
                errorNs = if (timestampNs - t0 <= t1 - timestampNs) timestampNs - t0 else timestampNs - t1
                spanNs = t1 - t0
                if (spanNs > MAX_INTERPOLATION_SPAN_NS) {
                    // Too wide to interpolate: report the nearest neighbour.
                    write(if (timestampNs - t0 <= t1 - timestampNs) older else newer, out)
                } else {
                    interpolate(older, newer, (timestampNs - t0).toDouble() / (t1 - t0).toDouble(), out)
                }
                return INTERPOLATED
            }
        }
        val oldest = slot(count - 1)
        errorNs = timestampNs - ts[oldest]
        write(oldest, out)
        return CLAMPED_OLD
    }

    /** Lookup plus [contractPoseMatch]; [out] is written unless the ring is empty. */
    fun match(timestampNs: Long, out: FloatArray): Int = contractPoseMatch(lookup(timestampNs, out), errorNs, spanNs)

    private fun write(s: Int, out: FloatArray) {
        val o = s * STRIDE
        System.arraycopy(tq, o, lastTq, 0, STRIDE)
        toMatrix(
            tq[o].toDouble(), tq[o + 1].toDouble(), tq[o + 2].toDouble(),
            tq[o + 3].toDouble(), tq[o + 4].toDouble(), tq[o + 5].toDouble(), tq[o + 6].toDouble(), out,
        )
    }

    private fun interpolate(a: Int, b: Int, t: Double, out: FloatArray) {
        val oa = a * STRIDE
        val ob = b * STRIDE
        val tx = tq[oa] + (tq[ob] - tq[oa]) * t
        val ty = tq[oa + 1] + (tq[ob + 1] - tq[oa + 1]) * t
        val tz = tq[oa + 2] + (tq[ob + 2] - tq[oa + 2]) * t
        val ax = tq[oa + 3].toDouble()
        val ay = tq[oa + 4].toDouble()
        val az = tq[oa + 5].toDouble()
        val aw = tq[oa + 6].toDouble()
        var bx = tq[ob + 3].toDouble()
        var by = tq[ob + 4].toDouble()
        var bz = tq[ob + 5].toDouble()
        var bw = tq[ob + 6].toDouble()
        var dot = ax * bx + ay * by + az * bz + aw * bw
        if (dot < 0.0) {
            dot = -dot
            bx = -bx
            by = -by
            bz = -bz
            bw = -bw
        }
        val wa: Double
        val wb: Double
        if (dot > 0.9995) {
            wa = 1.0 - t
            wb = t
        } else {
            val theta = Math.acos(dot)
            val sin = Math.sin(theta)
            wa = Math.sin((1.0 - t) * theta) / sin
            wb = Math.sin(t * theta) / sin
        }
        var qx = wa * ax + wb * bx
        var qy = wa * ay + wb * by
        var qz = wa * az + wb * bz
        var qw = wa * aw + wb * bw
        val l = Math.sqrt(qx * qx + qy * qy + qz * qz + qw * qw)
        qx /= l
        qy /= l
        qz /= l
        qw /= l
        lastTq[0] = tx.toFloat()
        lastTq[1] = ty.toFloat()
        lastTq[2] = tz.toFloat()
        lastTq[3] = qx.toFloat()
        lastTq[4] = qy.toFloat()
        lastTq[5] = qz.toFloat()
        lastTq[6] = qw.toFloat()
        toMatrix(tx, ty, tz, qx, qy, qz, qw, out)
    }

    private fun toMatrix(tx: Double, ty: Double, tz: Double, qx: Double, qy: Double, qz: Double, qw: Double, m: FloatArray) {
        val xx = qx * qx
        val yy = qy * qy
        val zz = qz * qz
        val xy = qx * qy
        val xz = qx * qz
        val yz = qy * qz
        val xw = qx * qw
        val yw = qy * qw
        val zw = qz * qw
        m[0] = (1.0 - 2.0 * (yy + zz)).toFloat()
        m[1] = (2.0 * (xy + zw)).toFloat()
        m[2] = (2.0 * (xz - yw)).toFloat()
        m[3] = 0f
        m[4] = (2.0 * (xy - zw)).toFloat()
        m[5] = (1.0 - 2.0 * (xx + zz)).toFloat()
        m[6] = (2.0 * (yz + xw)).toFloat()
        m[7] = 0f
        m[8] = (2.0 * (xz + yw)).toFloat()
        m[9] = (2.0 * (yz - xw)).toFloat()
        m[10] = (1.0 - 2.0 * (xx + yy)).toFloat()
        m[11] = 0f
        m[12] = tx.toFloat()
        m[13] = ty.toFloat()
        m[14] = tz.toFloat()
        m[15] = 1f
    }
}
