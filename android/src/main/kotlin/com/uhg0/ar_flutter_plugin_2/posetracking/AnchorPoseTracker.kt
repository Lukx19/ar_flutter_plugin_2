package com.uhg0.ar_flutter_plugin_2.posetracking

import com.uhg0.ar_flutter_plugin_2.visibilitycontract.IngestMessageLayout
import com.uhg0.ar_flutter_plugin_2.visibilityingest.IngestClock
import com.uhg0.ar_flutter_plugin_2.visibilityingest.IngestMessageWriter
import com.uhg0.ar_flutter_plugin_2.visibilityingest.IngestTrackingState
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Anchor operations the tracker needs, as primitives, so the tracker runs in
 * JVM tests with a fake backend. The ARCore implementation is
 * [ArCoreAnchorBackend]. Every call happens on the AR thread.
 */
internal interface AnchorBackend {
    /** Creates an anchor at [tq] (t xyz, q xyzw) in [session]; throws when ARCore refuses. */
    fun create(session: Any, tq: FloatArray): Any

    fun detach(anchor: Any)

    /** One of [AnchorTracking.TRACKING], [AnchorTracking.PAUSED], [AnchorTracking.STOPPED]. */
    fun tracking(anchor: Any): Int

    /** Writes t xyz, q xyzw of [anchor] to `out[offset, offset + 7)`. */
    fun readPose(anchor: Any, out: FloatArray, offset: Int)
}

/**
 * The parts of one AR frame the tracker reads. The ARCore implementation is
 * [ArCoreAnchorFrame]; one instance is rebound to every frame.
 */
internal interface AnchorFrame {
    val timestampNs: Long

    /** One of [IngestTrackingState]. */
    val cameraTracking: Int

    /** Writes the sensor-oriented camera pose as t xyz, q xyzw. */
    fun readCameraPose(out: FloatArray)

    /** Loads the anchors this frame updated and returns their number. */
    fun loadUpdatedAnchors(): Int

    /** Updated anchor [index] of the last [loadUpdatedAnchors]. */
    fun updatedAnchor(index: Int): Any
}

/**
 * Open-addressing (linear probing) index from an int key to a table slot,
 * without boxing. Keys need not be unique (anchor hash codes); callers walk
 * the matching positions with [firstPosition] / [nextPosition]. Removal uses
 * backward shifting, so no tombstones accumulate. Doubles when half full and
 * counts the growth in [growthEvents].
 */
internal class IntSlotIndex(initialCapacity: Int) {
    private var keys: IntArray
    private var slots: IntArray
    private var mask: Int
    var size = 0
        private set
    var growthEvents = 0
        private set

    init {
        var c = 16
        while (c < initialCapacity * 2) c = c shl 1
        keys = IntArray(c)
        slots = IntArray(c) { EMPTY }
        mask = c - 1
    }

    fun clear() {
        slots.fill(EMPTY)
        size = 0
    }

    fun put(key: Int, slot: Int) {
        if ((size + 1) * 2 > slots.size) grow()
        insertRaw(key, slot)
        size++
    }

    /** Slot of the first entry with [key], or -1 (unique keys). */
    fun find(key: Int): Int {
        val p = firstPosition(key)
        return if (p < 0) -1 else slots[p]
    }

    fun firstPosition(key: Int): Int = scan(key, mix(key) and mask)

    fun nextPosition(key: Int, position: Int): Int = scan(key, (position + 1) and mask)

    fun slotAt(position: Int): Int = slots[position]

    /** Position of the entry ([key], [slot]), or -1. */
    fun positionOf(key: Int, slot: Int): Int {
        var p = firstPosition(key)
        while (p >= 0 && slots[p] != slot) p = nextPosition(key, p)
        return p
    }

    fun remove(key: Int, slot: Int): Boolean {
        val p = positionOf(key, slot)
        if (p < 0) return false
        removeAt(p)
        return true
    }

    fun moveSlot(key: Int, from: Int, to: Int) {
        val p = positionOf(key, from)
        if (p >= 0) slots[p] = to
    }

    private fun scan(key: Int, start: Int): Int {
        var i = start
        while (slots[i] != EMPTY) {
            if (keys[i] == key) return i
            i = (i + 1) and mask
        }
        return -1
    }

    private fun insertRaw(key: Int, slot: Int) {
        var i = mix(key) and mask
        while (slots[i] != EMPTY) i = (i + 1) and mask
        keys[i] = key
        slots[i] = slot
    }

    private fun removeAt(position: Int) {
        var hole = position
        var i = (position + 1) and mask
        while (slots[i] != EMPTY) {
            val ideal = mix(keys[i]) and mask
            val move = if (hole <= i) ideal <= hole || ideal > i else ideal <= hole && ideal > i
            if (move) {
                keys[hole] = keys[i]
                slots[hole] = slots[i]
                hole = i
            }
            i = (i + 1) and mask
        }
        slots[hole] = EMPTY
        size--
    }

    private fun grow() {
        val oldKeys = keys
        val oldSlots = slots
        keys = IntArray(oldKeys.size * 2)
        slots = IntArray(oldSlots.size * 2) { EMPTY }
        mask = slots.size - 1
        for (i in oldSlots.indices) if (oldSlots[i] != EMPTY) insertRaw(oldKeys[i], oldSlots[i])
        growthEvents++
    }

    private companion object {
        const val EMPTY = -1
        fun mix(key: Int): Int {
            val h = key * -0x61c88647
            return h xor (h ushr 16)
        }
    }
}

/**
 * State of the anchor pose tracker (pose stability, plan 2.14): the anchors
 * this tracker created, their poses and what was last sent to the grid
 * isolate, the creation queue and the creation results.
 *
 * Threads: [onFrame] runs on the AR thread, [handleQuery] on the channel's
 * background task queue, [enqueuePhoto] on the capture finalisation thread.
 * All share [lock]; the AR thread holds it for the whole per-frame anchor
 * processing (creations plus the updated anchors of the frame), the channel
 * thread for one reply. ARCore anchor calls (create, detach, pose reads)
 * happen only on the AR thread, except a final detach in [close].
 *
 * Tables are fixed-capacity primitive arrays grown by doubling (counted in
 * [growthEvents]); slots stay dense through swap-removal. Nothing allocates
 * in the frame or reply path except what ARCore allocates behind
 * [AnchorBackend] / [AnchorFrame].
 *
 * Poses are kept as t xyz, q xyzw (7 floats); rotation differences are
 * measured on the quaternions (|q_a - ±q_b|^2 = 4 sin^2(angle / 4)), which
 * stays exact at the 0.05 degree threshold where a matrix trace would be lost
 * in float rounding. Update records carry rows 0-2 of the 4x4 built from the
 * quaternion.
 */
internal class AnchorPoseTracker(
    private val backend: AnchorBackend,
    /** Query arrival and the activity timeout (`elapsedRealtimeNanos` in production). */
    private val clock: IngestClock,
    /** Per-frame anchor processing time. */
    private val readTimer: IngestClock = IngestClock { System.nanoTime() },
    initialCapacity: Int = DEFAULT_CAPACITY,
) {
    companion object {
        const val DEFAULT_CAPACITY = 2048
        const val QUEUE_CAPACITY = 128
        const val ACTIVE_TIMEOUT_NS = 10_000_000_000L

        /** An update is sent once the pose moved more than 1 mm or turned more than 0.05 degrees. */
        const val UPDATE_MOVE_M = 0.001
        const val UPDATE_TURN_DEG = 0.05

        /** A pose is stable (for detach by limit) while it stays within 5 mm and 0.2 degrees. */
        const val STABLE_MOVE_M = 0.005
        const val STABLE_TURN_DEG = 0.2

        private const val POSE = 7
        private const val NONE: Byte = -1
        private const val MAX_PHOTO_ID = AnchorIds.FIRST_KEYFRAME_ID - 1

        /** Squared quaternion distance of a rotation by [degrees]. */
        fun turnDistanceSquared(degrees: Double): Double {
            val s = sin(Math.toRadians(degrees) / 4.0)
            return 4.0 * s * s
        }

        private val UPDATE_TURN_D2 = turnDistanceSquared(UPDATE_TURN_DEG)
        private val STABLE_TURN_D2 = turnDistanceSquared(STABLE_TURN_DEG)

        /**
         * Writes rows 0-2 of the 4x4 of [tq] at [o] (`r00 r01 r02 tx`, ...)
         * when [rows] is true, else the column-major 4x4 to `out[0, 16)`.
         */
        internal fun poseMatrix(tq: FloatArray, o: Int, out: FloatArray, rows: Boolean) {
            val tx = tq[o].toDouble()
            val ty = tq[o + 1].toDouble()
            val tz = tq[o + 2].toDouble()
            val qx = tq[o + 3].toDouble()
            val qy = tq[o + 4].toDouble()
            val qz = tq[o + 5].toDouble()
            val qw = tq[o + 6].toDouble()
            val xx = qx * qx
            val yy = qy * qy
            val zz = qz * qz
            val xy = qx * qy
            val xz = qx * qz
            val yz = qy * qz
            val xw = qx * qw
            val yw = qy * qw
            val zw = qz * qw
            val r00 = 1.0 - 2.0 * (yy + zz)
            val r01 = 2.0 * (xy - zw)
            val r02 = 2.0 * (xz + yw)
            val r10 = 2.0 * (xy + zw)
            val r11 = 1.0 - 2.0 * (xx + zz)
            val r12 = 2.0 * (yz - xw)
            val r20 = 2.0 * (xz - yw)
            val r21 = 2.0 * (yz + xw)
            val r22 = 1.0 - 2.0 * (xx + yy)
            if (rows) {
                out[0] = r00.toFloat(); out[1] = r01.toFloat(); out[2] = r02.toFloat(); out[3] = tx.toFloat()
                out[4] = r10.toFloat(); out[5] = r11.toFloat(); out[6] = r12.toFloat(); out[7] = ty.toFloat()
                out[8] = r20.toFloat(); out[9] = r21.toFloat(); out[10] = r22.toFloat(); out[11] = tz.toFloat()
            } else {
                out[0] = r00.toFloat(); out[1] = r10.toFloat(); out[2] = r20.toFloat(); out[3] = 0f
                out[4] = r01.toFloat(); out[5] = r11.toFloat(); out[6] = r21.toFloat(); out[7] = 0f
                out[8] = r02.toFloat(); out[9] = r12.toFloat(); out[10] = r22.toFloat(); out[11] = 0f
                out[12] = tx.toFloat(); out[13] = ty.toFloat(); out[14] = tz.toFloat(); out[15] = 1f
            }
        }

        /**
         * Converts camera-to-world rows 0-2 ([m] 12 floats from [o]) to t xyz,
         * q xyzw in [out]. Returns false when the rotation is not a proper
         * rotation (for example the zero rows of an unposed photo).
         */
        internal fun rowsToPose(m: FloatArray, o: Int, out: FloatArray): Boolean {
            val r00 = m[o].toDouble(); val r01 = m[o + 1].toDouble(); val r02 = m[o + 2].toDouble()
            val r10 = m[o + 4].toDouble(); val r11 = m[o + 5].toDouble(); val r12 = m[o + 6].toDouble()
            val r20 = m[o + 8].toDouble(); val r21 = m[o + 9].toDouble(); val r22 = m[o + 10].toDouble()
            val det = r00 * (r11 * r22 - r12 * r21) - r01 * (r10 * r22 - r12 * r20) + r02 * (r10 * r21 - r11 * r20)
            if (!(det > 0.9 && det < 1.1)) return false
            val trace = r00 + r11 + r22
            var qx: Double
            var qy: Double
            var qz: Double
            var qw: Double
            if (trace > 0.0) {
                val s = sqrt(trace + 1.0) * 2.0
                qw = 0.25 * s
                qx = (r21 - r12) / s
                qy = (r02 - r20) / s
                qz = (r10 - r01) / s
            } else if (r00 > r11 && r00 > r22) {
                val s = sqrt(1.0 + r00 - r11 - r22) * 2.0
                qw = (r21 - r12) / s
                qx = 0.25 * s
                qy = (r01 + r10) / s
                qz = (r02 + r20) / s
            } else if (r11 > r22) {
                val s = sqrt(1.0 + r11 - r00 - r22) * 2.0
                qw = (r02 - r20) / s
                qx = (r01 + r10) / s
                qy = 0.25 * s
                qz = (r12 + r21) / s
            } else {
                val s = sqrt(1.0 + r22 - r00 - r11) * 2.0
                qw = (r10 - r01) / s
                qx = (r02 + r20) / s
                qy = (r12 + r21) / s
                qz = 0.25 * s
            }
            val n = sqrt(qx * qx + qy * qy + qz * qz + qw * qw)
            if (!(n > 0.0)) return false
            qx /= n; qy /= n; qz /= n; qw /= n
            out[0] = m[o + 3]
            out[1] = m[o + 7]
            out[2] = m[o + 11]
            out[3] = qx.toFloat()
            out[4] = qy.toFloat()
            out[5] = qz.toFloat()
            out[6] = qw.toFloat()
            return true
        }

        /** Whether pose b differs from pose a by more than [move] metres or a squared quaternion distance [turnD2]. */
        private fun moved(a: FloatArray, ao: Int, b: FloatArray, bo: Int, move: Double, turnD2: Double): Boolean {
            val dx = (a[ao] - b[bo]).toDouble()
            val dy = (a[ao + 1] - b[bo + 1]).toDouble()
            val dz = (a[ao + 2] - b[bo + 2]).toDouble()
            if (dx * dx + dy * dy + dz * dz > move * move) return true
            var dot = 0.0
            for (k in 3 until 7) dot += a[ao + k].toDouble() * b[bo + k].toDouble()
            val s = if (dot < 0.0) -1.0 else 1.0
            var d2 = 0.0
            for (k in 3 until 7) {
                val d = a[ao + k].toDouble() - s * b[bo + k].toDouble()
                d2 += d * d
            }
            return d2 > turnD2
        }
    }

    val lock = Any()

    /** True while tracking is active or detaches are pending; the frame path returns after reading it when false. */
    @Volatile var armed = false
        private set

    private var closed = false
    private var active = false
    private var lastQueryNs = 0L
    private var anchorLimit = PoseQueryLayout.DEFAULT_ANCHOR_LIMIT
    private var stableNs = PoseQueryLayout.DEFAULT_DETACH_AFTER_SECONDS * 1_000_000_000L
    private var lastSession: Any? = null

    // Latest AR frame.
    private var frameNs = 0L
    private var previousFrameNs = 0L
    private var cameraTracking = IngestTrackingState.STOPPED
    private val cameraTq = floatArrayOf(0f, 0f, 0f, 0f, 0f, 0f, 1f)
    private val cameraMatrix = FloatArray(16)
    private val rows = FloatArray(12)
    private var maxReadNs = 0L
    private var maxIntervalNs = 0L

    // Anchor table, dense slots [0, count).
    private var capacity = initialCapacity.coerceAtLeast(1)
    private var ids = IntArray(capacity)
    private var kinds = ByteArray(capacity)
    private var subjects = LongArray(capacity)
    private var captureNs = LongArray(capacity)
    private var createdNs = LongArray(capacity)
    private var anchors = arrayOfNulls<Any>(capacity)
    private var tracking = ByteArray(capacity)
    private var sentTracking = ByteArray(capacity)
    private var current = FloatArray(capacity * POSE)
    private var sent = FloatArray(capacity * POSE)
    private var reference = FloatArray(capacity * POSE)
    private var stableSinceNs = LongArray(capacity)
    private var dirty = BooleanArray(capacity)
    private var final = BooleanArray(capacity)
    private var count = 0
    private var attached = 0
    private var dirtyCount = 0
    private var cursor = 0
    private val idIndex = IntSlotIndex(capacity)
    private val handleIndex = IntSlotIndex(capacity)

    // Creation queue (ring).
    private var reqCapacity = QUEUE_CAPACITY
    private var reqIds = IntArray(reqCapacity)
    private var reqKinds = ByteArray(reqCapacity)
    private var reqStatus = ByteArray(reqCapacity)
    private var reqSubjects = LongArray(reqCapacity)
    private var reqCaptureNs = LongArray(reqCapacity)
    private var reqPoses = FloatArray(reqCapacity * POSE)
    private var reqHead = 0
    private var reqCount = 0
    private val requestTq = FloatArray(POSE)

    // Creation results (ring).
    private var resCapacity = QUEUE_CAPACITY
    private var resIds = IntArray(resCapacity)
    private var resKinds = ByteArray(resCapacity)
    private var resStatus = ByteArray(resCapacity)
    private var resSubjects = LongArray(resCapacity)
    private var resCaptureNs = LongArray(resCapacity)
    private var resFrameNs = LongArray(resCapacity)
    private var resHead = 0
    private var resCount = 0

    // Anchors waiting for a detach on the AR thread.
    private var pendingDetach = arrayOfNulls<Any>(capacity)
    private var pendingDetachCount = 0

    private var nextPhotoId = 1

    // Counters.
    private var created = 0L
    private var failed = 0L
    private var overLimit = 0L
    private var notTracking = 0L
    private var detachedByLimit = 0L
    private var updatesSent = 0L
    private var repliesSent = 0L
    private var malformedQueries = 0L
    private var tableGrowth = 0
    private var deactivations = 0L
    private var sessionReplacements = 0L

    /** Growths of every table, queue and index since construction. */
    val growthEvents: Int
        get() = synchronized(lock) { tableGrowth + idIndex.growthEvents + handleIndex.growthEvents }

    // ---------------------------------------------------------------- AR thread

    /** AR thread, once per frame with the frame's session. */
    fun onFrame(session: Any, frame: AnchorFrame) {
        if (!armed) return
        val start = readTimer.nowNs()
        synchronized(lock) {
            if (session !== lastSession) {
                if (lastSession != null && (count > 0 || pendingDetachCount > 0)) {
                    sessionReplacements++
                    detachAllLocked()
                    abandonRequestsLocked()
                }
                lastSession = session
                previousFrameNs = 0L
            }
            if (active && clock.nowNs() - lastQueryNs >= ACTIVE_TIMEOUT_NS) deactivateLocked()
            drainDetachLocked()
            if (!active || closed) {
                armed = false
                return
            }
            val ts = frame.timestampNs
            if (previousFrameNs != 0L) {
                val interval = ts - previousFrameNs
                if (interval > maxIntervalNs) maxIntervalNs = interval
            }
            previousFrameNs = ts
            frameNs = ts
            cameraTracking = frame.cameraTracking
            frame.readCameraPose(cameraTq)
            processRequestsLocked(session, ts)
            if (attached > anchorLimit) {
                while (attached > anchorLimit && detachOneStableLocked(ts)) {
                    // Detach long-stable anchors until the lowered limit holds or none qualifies.
                }
            }
            readUpdatedLocked(frame, ts)
            val spent = readTimer.nowNs() - start
            if (spent > maxReadNs) maxReadNs = spent
        }
    }

    private fun processRequestsLocked(session: Any, ts: Long) {
        while (reqCount > 0) {
            val r = reqHead
            reqHead = (reqHead + 1) % reqCapacity
            reqCount--
            val id = reqIds[r]
            val kind = reqKinds[r].toInt()
            var status = reqStatus[r].toInt()
            System.arraycopy(reqPoses, r * POSE, requestTq, 0, POSE)
            if (status < 0) status = createLocked(session, id, kind, reqSubjects[r], reqCaptureNs[r], ts)
            pushResultLocked(id, kind, status, reqSubjects[r], reqCaptureNs[r], ts)
        }
    }

    private fun createLocked(session: Any, id: Int, kind: Int, subject: Long, capture: Long, ts: Long): Int {
        if (cameraTracking != IngestTrackingState.TRACKING) {
            notTracking++
            return AnchorCreation.NOT_TRACKING
        }
        if (idIndex.find(id) >= 0) {
            failed++
            return AnchorCreation.FAILED
        }
        if (attached >= anchorLimit && !detachOneStableLocked(ts)) {
            overLimit++
            return AnchorCreation.OVER_LIMIT
        }
        val anchor = try {
            backend.create(session, requestTq)
        } catch (_: Exception) {
            failed++
            return AnchorCreation.FAILED
        }
        if (count == capacity) growTableLocked()
        val s = count++
        ids[s] = id
        kinds[s] = kind.toByte()
        subjects[s] = subject
        captureNs[s] = capture
        createdNs[s] = ts
        anchors[s] = anchor
        final[s] = false
        sentTracking[s] = NONE
        val o = s * POSE
        try {
            backend.readPose(anchor, current, o)
            tracking[s] = backend.tracking(anchor).toByte()
        } catch (_: Exception) {
            System.arraycopy(requestTq, 0, current, o, POSE)
            tracking[s] = AnchorTracking.TRACKING.toByte()
        }
        System.arraycopy(current, o, sent, o, POSE)
        System.arraycopy(current, o, reference, o, POSE)
        stableSinceNs[s] = ts
        dirty[s] = true
        dirtyCount++
        idIndex.put(id, s)
        handleIndex.put(anchor.hashCode(), s)
        attached++
        created++
        return AnchorCreation.CREATED
    }

    /** Detaches the oldest attached anchor that has been stable for [stableNs]; false when none qualifies. */
    private fun detachOneStableLocked(ts: Long): Boolean {
        var best = -1
        var bestCreated = Long.MAX_VALUE
        for (s in 0 until count) {
            if (final[s] || anchors[s] == null) continue
            if (ts - stableSinceNs[s] < stableNs) continue
            if (createdNs[s] < bestCreated) {
                bestCreated = createdNs[s]
                best = s
            }
        }
        if (best < 0) return false
        val anchor = anchors[best]!!
        handleIndex.remove(anchor.hashCode(), best)
        try {
            backend.detach(anchor)
        } catch (_: Exception) {
            // Already detached by ARCore; the pose stays final either way.
        }
        anchors[best] = null
        final[best] = true
        tracking[best] = AnchorTracking.DETACHED_BY_LIMIT.toByte()
        if (!dirty[best]) {
            dirty[best] = true
            dirtyCount++
        }
        attached--
        detachedByLimit++
        return true
    }

    private fun readUpdatedLocked(frame: AnchorFrame, ts: Long) {
        if (attached == 0) return
        val n = frame.loadUpdatedAnchors()
        for (i in 0 until n) {
            val a = frame.updatedAnchor(i)
            val s = slotOfLocked(a)
            if (s < 0) continue
            val o = s * POSE
            val state = backend.tracking(a)
            backend.readPose(a, current, o)
            tracking[s] = state.toByte()
            if (!dirty[s] && (state != sentTracking[s].toInt() ||
                    moved(current, o, sent, o, UPDATE_MOVE_M, UPDATE_TURN_D2))
            ) {
                dirty[s] = true
                dirtyCount++
            }
            if (state == AnchorTracking.TRACKING && moved(current, o, reference, o, STABLE_MOVE_M, STABLE_TURN_D2)) {
                System.arraycopy(current, o, reference, o, POSE)
                stableSinceNs[s] = ts
            }
        }
    }

    private fun slotOfLocked(anchor: Any): Int {
        val h = anchor.hashCode()
        var p = handleIndex.firstPosition(h)
        while (p >= 0) {
            val s = handleIndex.slotAt(p)
            val own = anchors[s]
            if (own === anchor || own == anchor) return s
            p = handleIndex.nextPosition(h, p)
        }
        return -1
    }

    private fun drainDetachLocked() {
        for (i in 0 until pendingDetachCount) {
            val a = pendingDetach[i]!!
            pendingDetach[i] = null
            try {
                backend.detach(a)
            } catch (_: Exception) {
                // Session closed or anchor already detached.
            }
        }
        pendingDetachCount = 0
    }

    // ---------------------------------------------------------------- channel thread

    /**
     * Applies [query] and writes the reply (ingest header of kind 7 plus
     * payload) to [out] from index 0. Returns the reply length, or -1 for a
     * malformed query (counted; nothing written).
     */
    fun handleQuery(query: ByteBuffer?, out: ByteBuffer, sequence: Int): Int {
        val nowNs = clock.nowNs()
        val at = validQueryStart(query)
        synchronized(lock) {
            if (at < 0 || closed) {
                if (at < 0) malformedQueries++
                return -1
            }
            val q = query!!
            val flags = q.getShort(at + PoseQueryLayout.FLAGS_OFFSET).toInt() and 0xffff
            val requests = q.getShort(at + PoseQueryLayout.COUNT_OFFSET).toInt() and 0xffff
            val epoch = q.getShort(at + PoseQueryLayout.GROUP_EPOCH_OFFSET).toInt() and 0xffff
            val limit = q.getInt(at + PoseQueryLayout.ANCHOR_LIMIT_OFFSET)
            val detachAfter = q.getInt(at + PoseQueryLayout.DETACH_AFTER_OFFSET).toLong() and 0xffffffffL
            if (active && nowNs - lastQueryNs >= ACTIVE_TIMEOUT_NS) deactivateLocked()
            if (flags and PoseQueryLayout.FLAG_DETACH_ALL != 0) {
                detachAllLocked()
                abandonRequestsLocked()
            }
            if (flags and PoseQueryLayout.FLAG_TRACKING == 0) {
                if (active) deactivateLocked()
            } else {
                active = true
                lastQueryNs = nowNs
            }
            anchorLimit = if (limit == 0) PoseQueryLayout.DEFAULT_ANCHOR_LIMIT else limit.coerceAtLeast(1)
            stableNs = (if (detachAfter == 0L) PoseQueryLayout.DEFAULT_DETACH_AFTER_SECONDS.toLong() else detachAfter) *
                1_000_000_000L
            for (i in 0 until requests) {
                val o = at + PoseQueryLayout.HEADER_BYTES + i * PoseQueryLayout.REQUEST_BYTES
                for (k in 0 until POSE) requestTq[k] = q.getFloat(o + PoseQueryLayout.REQUEST_POSE_OFFSET + 4 * k)
                val id = q.getInt(o + PoseQueryLayout.REQUEST_ANCHOR_ID_OFFSET)
                val subject = q.getLong(o + PoseQueryLayout.REQUEST_SUBJECT_OFFSET)
                val capture = q.getLong(o + PoseQueryLayout.REQUEST_CAPTURE_NS_OFFSET)
                if (active) {
                    enqueueLocked(id, AnchorKind.KEYFRAME, subject, capture, requestTq, -1)
                } else {
                    notTracking++
                    pushResultLocked(id, AnchorKind.KEYFRAME, AnchorCreation.NOT_TRACKING, subject, capture, frameNs)
                }
            }
            armed = !closed && (active || pendingDetachCount > 0)
            return writeReplyLocked(out, sequence, epoch, nowNs)
        }
    }

    /** Absolute index of a valid query's first byte, or -1. */
    private fun validQueryStart(query: ByteBuffer?): Int {
        if (query == null || query.remaining() < PoseQueryLayout.HEADER_BYTES) return -1
        query.order(ByteOrder.LITTLE_ENDIAN)
        val at = query.position()
        if (query.getInt(at) != PoseQueryLayout.MAGIC) return -1
        if (query.getShort(at + PoseQueryLayout.VERSION_OFFSET).toInt() and 0xffff != PoseQueryLayout.VERSION) return -1
        val requests = query.getShort(at + PoseQueryLayout.COUNT_OFFSET).toInt() and 0xffff
        if (requests > PoseQueryLayout.MAX_REQUESTS) return -1
        if (query.remaining() < PoseQueryLayout.HEADER_BYTES + requests * PoseQueryLayout.REQUEST_BYTES) return -1
        return at
    }

    private fun writeReplyLocked(b: ByteBuffer, sequence: Int, epoch: Int, nowNs: Long): Int {
        IngestMessageWriter.begin(b, PoseTrackingLayout.ANCHOR_POSE_UPDATE_KIND)
        val payload = IngestMessageLayout.HEADER_BYTES
        var updates = 0
        var finalsSent = false
        val n = count
        if (dirtyCount > 0 && n > 0) {
            var s = if (cursor < n) cursor else 0
            var scanned = 0
            while (scanned < n && updates < AnchorPoseUpdateLayout.MAX_UPDATES) {
                if (dirty[s]) {
                    writeUpdateLocked(b, payload + AnchorPoseUpdateLayout.PREFIX_BYTES +
                        updates * AnchorPoseUpdateLayout.UPDATE_BYTES, s)
                    dirty[s] = false
                    dirtyCount--
                    if (final[s]) finalsSent = true
                    updates++
                }
                s++
                if (s == n) s = 0
                scanned++
            }
            cursor = s
        }
        val creations = if (resCount < AnchorPoseUpdateLayout.MAX_CREATIONS) resCount else AnchorPoseUpdateLayout.MAX_CREATIONS
        val creationStart = payload + AnchorPoseUpdateLayout.PREFIX_BYTES + updates * AnchorPoseUpdateLayout.UPDATE_BYTES
        for (i in 0 until creations) {
            val r = resHead
            resHead = (resHead + 1) % resCapacity
            resCount--
            val o = creationStart + i * AnchorPoseUpdateLayout.CREATION_BYTES
            b.putInt(o, resIds[r])
            b.put(o + AnchorPoseUpdateLayout.CREATION_KIND_OFFSET, resKinds[r])
            b.put(o + AnchorPoseUpdateLayout.CREATION_STATUS_OFFSET, resStatus[r])
            b.putShort(o + 6, 0)
            b.putLong(o + AnchorPoseUpdateLayout.CREATION_SUBJECT_OFFSET, resSubjects[r])
            b.putLong(o + AnchorPoseUpdateLayout.CREATION_CAPTURE_NS_OFFSET, resCaptureNs[r])
            b.putLong(o + AnchorPoseUpdateLayout.CREATION_FRAME_NS_OFFSET, resFrameNs[r])
            b.putInt(o + 32, 0)
            b.putInt(o + 36, 0)
        }
        b.putShort(payload, updates.toShort())
        b.putShort(payload + 2, creations.toShort())
        val payloadBytes = AnchorPoseUpdateLayout.payloadBytes(updates, creations)
        IngestMessageWriter.payloadBytes(b, payloadBytes)
        IngestMessageWriter.sequence(b, sequence)
        IngestMessageWriter.timing(b, frameNs, frameNs, nowNs)
        IngestMessageWriter.sendTime(b, nowNs)
        poseMatrix(cameraTq, 0, cameraMatrix, rows = false)
        IngestMessageWriter.pose(b, cameraTracking, IngestMessageLayout.POSE_MATCH_EXACT, epoch, 0, cameraMatrix)
        b.putInt(AnchorPoseUpdateLayout.TRACKED_ANCHORS_OFFSET, attached)
        b.putInt(AnchorPoseUpdateLayout.ANCHOR_READ_MAX_MICROS_OFFSET, micros(maxReadNs))
        b.putInt(AnchorPoseUpdateLayout.FRAME_INTERVAL_MAX_MICROS_OFFSET, micros(maxIntervalNs))
        maxReadNs = 0L
        maxIntervalNs = 0L
        if (finalsSent) removeSentFinalsLocked()
        updatesSent += updates
        repliesSent++
        return IngestMessageLayout.HEADER_BYTES + payloadBytes
    }

    private fun micros(ns: Long): Int {
        val us = ns / 1000L
        return if (us > Int.MAX_VALUE) Int.MAX_VALUE else us.toInt()
    }

    private fun writeUpdateLocked(b: ByteBuffer, o: Int, s: Int) {
        b.putInt(o, ids[s])
        b.put(o + AnchorPoseUpdateLayout.UPDATE_KIND_OFFSET, kinds[s])
        b.put(o + AnchorPoseUpdateLayout.UPDATE_TRACKING_OFFSET, tracking[s])
        b.putShort(o + 6, 0)
        val p = s * POSE
        poseMatrix(current, p, rows, rows = true)
        for (k in 0 until 12) b.putFloat(o + AnchorPoseUpdateLayout.UPDATE_MATRIX_OFFSET + 4 * k, rows[k])
        System.arraycopy(current, p, sent, p, POSE)
        sentTracking[s] = tracking[s]
    }

    private fun removeSentFinalsLocked() {
        var s = count - 1
        while (s >= 0) {
            if (final[s] && !dirty[s]) removeSlotLocked(s)
            s--
        }
    }

    /** Swap-removes slot [s] (its anchor already detached or handed over). */
    private fun removeSlotLocked(s: Int) {
        val last = count - 1
        idIndex.remove(ids[s], s)
        if (s != last) {
            ids[s] = ids[last]
            kinds[s] = kinds[last]
            subjects[s] = subjects[last]
            captureNs[s] = captureNs[last]
            createdNs[s] = createdNs[last]
            anchors[s] = anchors[last]
            tracking[s] = tracking[last]
            sentTracking[s] = sentTracking[last]
            System.arraycopy(current, last * POSE, current, s * POSE, POSE)
            System.arraycopy(sent, last * POSE, sent, s * POSE, POSE)
            System.arraycopy(reference, last * POSE, reference, s * POSE, POSE)
            stableSinceNs[s] = stableSinceNs[last]
            dirty[s] = dirty[last]
            final[s] = final[last]
            idIndex.moveSlot(ids[s], last, s)
            anchors[s]?.let { handleIndex.moveSlot(it.hashCode(), last, s) }
        }
        anchors[last] = null
        dirty[last] = false
        count--
    }

    // ---------------------------------------------------------------- photo thread

    /**
     * Queues a photo anchor at [tq] while tracking is active; [poseValid]
     * false yields a failed creation record. Returns the assigned anchor id,
     * or -1 while inactive.
     */
    fun enqueuePhoto(subject: Long, capture: Long, tq: FloatArray, poseValid: Boolean): Int {
        synchronized(lock) {
            if (!active || closed) return -1
            val id = nextPhotoId
            nextPhotoId = if (nextPhotoId >= MAX_PHOTO_ID) 1 else nextPhotoId + 1
            enqueueLocked(id, AnchorKind.PHOTO, subject, capture, tq, if (poseValid) -1 else AnchorCreation.FAILED)
            if (!poseValid) failed++
            return id
        }
    }

    // ---------------------------------------------------------------- shared

    private fun enqueueLocked(id: Int, kind: Int, subject: Long, capture: Long, tq: FloatArray, status: Int) {
        if (reqCount == reqCapacity) growRequestsLocked()
        val r = (reqHead + reqCount) % reqCapacity
        reqIds[r] = id
        reqKinds[r] = kind.toByte()
        reqStatus[r] = status.toByte()
        reqSubjects[r] = subject
        reqCaptureNs[r] = capture
        System.arraycopy(tq, 0, reqPoses, r * POSE, POSE)
        reqCount++
    }

    private fun pushResultLocked(id: Int, kind: Int, status: Int, subject: Long, capture: Long, ts: Long) {
        if (resCount == resCapacity) growResultsLocked()
        val r = (resHead + resCount) % resCapacity
        resIds[r] = id
        resKinds[r] = kind.toByte()
        resStatus[r] = status.toByte()
        resSubjects[r] = subject
        resCaptureNs[r] = capture
        resFrameNs[r] = ts
        resCount++
    }

    /** Queued creations that will not run answer not tracking. */
    private fun abandonRequestsLocked() {
        while (reqCount > 0) {
            val r = reqHead
            reqHead = (reqHead + 1) % reqCapacity
            reqCount--
            val status = if (reqStatus[r] < 0) AnchorCreation.NOT_TRACKING else reqStatus[r].toInt()
            if (status == AnchorCreation.NOT_TRACKING) notTracking++
            pushResultLocked(reqIds[r], reqKinds[r].toInt(), status, reqSubjects[r], reqCaptureNs[r], frameNs)
        }
    }

    private fun deactivateLocked() {
        active = false
        deactivations++
        detachAllLocked()
        abandonRequestsLocked()
        previousFrameNs = 0L
    }

    /** Hands every anchor to the AR thread for detaching and clears the table (no records). */
    private fun detachAllLocked() {
        for (s in 0 until count) {
            val a = anchors[s] ?: continue
            if (pendingDetachCount == pendingDetach.size) pendingDetach = pendingDetach.copyOf(pendingDetach.size * 2).also { tableGrowth++ }
            pendingDetach[pendingDetachCount++] = a
            anchors[s] = null
        }
        for (s in 0 until count) {
            dirty[s] = false
            final[s] = false
        }
        count = 0
        attached = 0
        dirtyCount = 0
        cursor = 0
        idIndex.clear()
        handleIndex.clear()
        armed = !closed && (active || pendingDetachCount > 0)
    }

    private fun growTableLocked() {
        val c = capacity * 2
        ids = ids.copyOf(c)
        kinds = kinds.copyOf(c)
        subjects = subjects.copyOf(c)
        captureNs = captureNs.copyOf(c)
        createdNs = createdNs.copyOf(c)
        anchors = anchors.copyOf(c)
        tracking = tracking.copyOf(c)
        sentTracking = sentTracking.copyOf(c)
        current = current.copyOf(c * POSE)
        sent = sent.copyOf(c * POSE)
        reference = reference.copyOf(c * POSE)
        stableSinceNs = stableSinceNs.copyOf(c)
        dirty = dirty.copyOf(c)
        final = final.copyOf(c)
        capacity = c
        tableGrowth++
    }

    private fun growRequestsLocked() {
        val c = reqCapacity * 2
        val ids2 = IntArray(c)
        val kinds2 = ByteArray(c)
        val status2 = ByteArray(c)
        val subjects2 = LongArray(c)
        val capture2 = LongArray(c)
        val poses2 = FloatArray(c * POSE)
        for (i in 0 until reqCount) {
            val r = (reqHead + i) % reqCapacity
            ids2[i] = reqIds[r]
            kinds2[i] = reqKinds[r]
            status2[i] = reqStatus[r]
            subjects2[i] = reqSubjects[r]
            capture2[i] = reqCaptureNs[r]
            System.arraycopy(reqPoses, r * POSE, poses2, i * POSE, POSE)
        }
        reqIds = ids2; reqKinds = kinds2; reqStatus = status2; reqSubjects = subjects2; reqCaptureNs = capture2
        reqPoses = poses2
        reqHead = 0
        reqCapacity = c
        tableGrowth++
    }

    private fun growResultsLocked() {
        val c = resCapacity * 2
        val ids2 = IntArray(c)
        val kinds2 = ByteArray(c)
        val status2 = ByteArray(c)
        val subjects2 = LongArray(c)
        val capture2 = LongArray(c)
        val frame2 = LongArray(c)
        for (i in 0 until resCount) {
            val r = (resHead + i) % resCapacity
            ids2[i] = resIds[r]
            kinds2[i] = resKinds[r]
            status2[i] = resStatus[r]
            subjects2[i] = resSubjects[r]
            capture2[i] = resCaptureNs[r]
            frame2[i] = resFrameNs[r]
        }
        resIds = ids2; resKinds = kinds2; resStatus = status2; resSubjects = subjects2; resCaptureNs = capture2
        resFrameNs = frame2
        resHead = 0
        resCapacity = c
        tableGrowth++
    }

    /**
     * Stops the tracker (engine detached). Detaches every anchor on the
     * calling thread: no AR frame reaches a closed tracker, and ARCore's
     * `Anchor.detach` is safe off the GL thread.
     */
    fun close() {
        synchronized(lock) {
            closed = true
            active = false
            detachAllLocked()
            drainDetachLocked()
            armed = false
        }
    }

    /** Diagnostic counters (allocates; not for the frame path). */
    fun snapshot(): Map<String, Any> = synchronized(lock) {
        mapOf(
            "active" to active,
            "trackedAnchors" to attached,
            "created" to created,
            "failed" to failed,
            "overLimit" to overLimit,
            "notTracking" to notTracking,
            "detachedByLimit" to detachedByLimit,
            "updatesSent" to updatesSent,
            "repliesSent" to repliesSent,
            "malformedQueries" to malformedQueries,
            "growthEvents" to (tableGrowth + idIndex.growthEvents + handleIndex.growthEvents),
            "deactivations" to deactivations,
            "sessionReplacements" to sessionReplacements,
            "pendingCreations" to reqCount,
            "pendingResults" to resCount,
            "dirtyAnchors" to dirtyCount,
        )
    }

    internal val trackedAnchors: Int get() = synchronized(lock) { attached }
    internal val isActive: Boolean get() = synchronized(lock) { active }
    internal val pendingDetaches: Int get() = synchronized(lock) { pendingDetachCount }
}
