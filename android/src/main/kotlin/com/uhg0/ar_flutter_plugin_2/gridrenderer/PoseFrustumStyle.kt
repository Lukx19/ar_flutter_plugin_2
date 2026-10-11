package com.uhg0.ar_flutter_plugin_2.gridrenderer

import com.uhg0.ar_flutter_plugin_2.visibilitycontract.contractMagic
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt

/** Monotonic clock of the flash phase (`System.nanoTime` in production). */
internal fun interface PoseFrustumClock {
    fun nowNs(): Long
}

/**
 * Pose stability overlay of the grid renderer: draws the poses of photos
 * (camera frusta) and keyframes (small axis crosses) from pose marker deltas
 * (`C3PM`, plan 2.14) received on the display channel, coloured by their
 * stability state.
 *
 * Message layout (mirrors `PoseMarkerDeltaLayout` in
 * `capture3d/lib/capture/visibility_grid/pose_stability/pose_tracking_messages.dart`):
 * 16 B header (magic u32, version u16, count u16, flags u32, reserved u32),
 * then 32 B records (id u32, kind u8, state u8 with bit 7 flash, submap id
 * u16, position f32 x3, rotation quaternion x y z w snorm16, age s u16, last
 * correction mm u16). A colour table message carries four RGBA8 words (new,
 * stabilizing, stable, flash) instead of records.
 *
 * The submap id is ignored for now: every position is read as world
 * coordinates (submap 0 = world) until submap transforms reach the renderer.
 *
 * Threads: [onMessage] on the channel thread and [buildLines] on the render
 * thread share one short lock over a fixed table of [MAX_POSES] poses;
 * nothing allocates after construction.
 */
internal class PoseFrustumStyle(
    private val clock: PoseFrustumClock = PoseFrustumClock { System.nanoTime() },
) : GridLineOverlay {
    companion object {
        val MAGIC = contractMagic("C3PM")
        const val VERSION = 1
        const val HEADER_BYTES = 16
        const val RECORD_BYTES = 32
        const val MAX_MESSAGE_BYTES = 65_536
        const val MAX_RECORDS = (MAX_MESSAGE_BYTES - HEADER_BYTES) / RECORD_BYTES

        const val COUNT_OFFSET = 6
        const val FLAGS_OFFSET = 8

        const val FLAG_SHOW_PHOTOS = 1
        const val FLAG_SHOW_KEYFRAMES = 2
        const val FLAG_FULL_REFRESH = 4
        const val FLAG_COLOUR_TABLE = 8

        const val ID_OFFSET = 0
        const val KIND_OFFSET = 4
        const val STATE_OFFSET = 5
        const val SUBMAP_OFFSET = 6
        const val POSITION_OFFSET = 8
        const val ROTATION_OFFSET = 20
        const val AGE_OFFSET = 28
        const val CORRECTION_OFFSET = 30

        const val KIND_PHOTO = 1
        const val KIND_KEYFRAME = 2

        const val STATE_FRESH = 0
        const val STATE_STABILIZING = 1
        const val STATE_STABLE = 2
        const val STATE_REMOVED = 3
        const val FLASH_BIT = 0x80

        const val DEFAULT_FRESH = 0x9E9E9EFF.toInt()
        const val DEFAULT_STABILIZING = 0xFFB300FF.toInt()
        const val DEFAULT_STABLE = 0x43A047FF
        const val DEFAULT_FLASH = 0xFFFFFFFF.toInt()

        const val MAX_POSES = 4096
        const val MAX_SEGMENTS = 5000
        const val MAX_DISTANCE_M = 8f

        /** Photo frustum: far rectangle [FRUSTUM_DEPTH] in front (-Z) with these half extents. */
        const val FRUSTUM_DEPTH = 0.08f
        const val FRUSTUM_HALF_WIDTH = 0.05f
        const val FRUSTUM_HALF_HEIGHT = 0.0375f

        /** Keyframe cross: total length of each axis segment, centred on the position. */
        const val CROSS_LENGTH = 0.03f

        /** Half period of the flash: the colour toggles every 250 ms (2 Hz). */
        const val FLASH_HALF_PERIOD_NS = 250_000_000L

        private const val SEGMENTS_PHOTO = 8
        private const val SEGMENTS_KEYFRAME = 3
        private const val EMPTY = -1
    }

    private val lock = Any()

    // Pose table, dense [0, count).
    private val ids = IntArray(MAX_POSES)
    private val kinds = ByteArray(MAX_POSES)
    private val states = ByteArray(MAX_POSES)
    private val positions = FloatArray(MAX_POSES * 3)
    private val rotations = FloatArray(MAX_POSES * 4)
    private var count = 0

    // id -> slot, linear probing with backward-shift removal.
    private val indexKeys = IntArray(MAX_POSES * 2)
    private val indexSlots = IntArray(MAX_POSES * 2).also { it.fill(EMPTY) }
    private val indexMask = MAX_POSES * 2 - 1

    private var flags = 0
    private var colourFresh = DEFAULT_FRESH
    private var colourStabilizing = DEFAULT_STABILIZING
    private var colourStable = DEFAULT_STABLE
    private var colourFlash = DEFAULT_FLASH

    // Render-thread scratch: rotated frustum corners.
    private val corner = FloatArray(12)

    /** Records dropped because the table was full. */
    var tableFullDrops = 0L
        private set

    /** Segments not emitted because of the per-frame cap. */
    var droppedSegments = 0L
        private set

    var rejectedMessages = 0L
        private set

    override val messageMagic: Int get() = MAGIC

    override val greyOutside: Boolean get() = false

    override fun isInScope(x: Float, y: Float, z: Float): Boolean = true

    override fun onMessage(buffer: ByteBuffer, length: Int): Boolean {
        val b = buffer.order(ByteOrder.LITTLE_ENDIAN)
        val at = b.position()
        val valid = length >= HEADER_BYTES && length <= MAX_MESSAGE_BYTES && b.limit() - at >= length &&
            b.getInt(at) == MAGIC && (b.getShort(at + 4).toInt() and 0xffff) == VERSION
        if (!valid) return reject()
        val n = b.getShort(at + COUNT_OFFSET).toInt() and 0xffff
        val messageFlags = b.getInt(at + FLAGS_OFFSET)
        synchronized(lock) {
            if (messageFlags and FLAG_COLOUR_TABLE != 0) {
                if (length < HEADER_BYTES + 16) return rejectLocked()
                colourFresh = b.getInt(at + HEADER_BYTES)
                colourStabilizing = b.getInt(at + HEADER_BYTES + 4)
                colourStable = b.getInt(at + HEADER_BYTES + 8)
                colourFlash = b.getInt(at + HEADER_BYTES + 12)
                return true
            }
            if (n > MAX_RECORDS || length < HEADER_BYTES + n * RECORD_BYTES) return rejectLocked()
            flags = messageFlags
            if (messageFlags and FLAG_FULL_REFRESH != 0) clearLocked()
            for (i in 0 until n) applyRecordLocked(b, at + HEADER_BYTES + i * RECORD_BYTES)
            return true
        }
    }

    private fun reject(): Boolean = synchronized(lock) { rejectLocked() }

    private fun rejectLocked(): Boolean {
        rejectedMessages++
        return false
    }

    private fun applyRecordLocked(b: ByteBuffer, o: Int) {
        val id = b.getInt(o + ID_OFFSET)
        val state = b.get(o + STATE_OFFSET).toInt() and 0xff
        var s = find(id)
        if ((state and FLASH_BIT.inv()) == STATE_REMOVED) {
            if (s >= 0) removeLocked(s)
            return
        }
        if (s < 0) {
            if (count == MAX_POSES) {
                tableFullDrops++
                return
            }
            s = count++
            ids[s] = id
            insert(id, s)
        }
        kinds[s] = b.get(o + KIND_OFFSET)
        states[s] = state.toByte()
        positions[s * 3] = b.getFloat(o + POSITION_OFFSET)
        positions[s * 3 + 1] = b.getFloat(o + POSITION_OFFSET + 4)
        positions[s * 3 + 2] = b.getFloat(o + POSITION_OFFSET + 8)
        var qx = b.getShort(o + ROTATION_OFFSET) / 32767f
        var qy = b.getShort(o + ROTATION_OFFSET + 2) / 32767f
        var qz = b.getShort(o + ROTATION_OFFSET + 4) / 32767f
        var qw = b.getShort(o + ROTATION_OFFSET + 6) / 32767f
        val norm = sqrt(qx * qx + qy * qy + qz * qz + qw * qw)
        if (norm > 1e-6f) {
            qx /= norm; qy /= norm; qz /= norm; qw /= norm
        } else {
            qx = 0f; qy = 0f; qz = 0f; qw = 1f
        }
        rotations[s * 4] = qx
        rotations[s * 4 + 1] = qy
        rotations[s * 4 + 2] = qz
        rotations[s * 4 + 3] = qw
    }

    private fun clearLocked() {
        indexSlots.fill(EMPTY)
        count = 0
    }

    private fun removeLocked(s: Int) {
        val last = count - 1
        removeFromIndex(ids[s])
        if (s != last) {
            ids[s] = ids[last]
            kinds[s] = kinds[last]
            states[s] = states[last]
            System.arraycopy(positions, last * 3, positions, s * 3, 3)
            System.arraycopy(rotations, last * 4, rotations, s * 4, 4)
            indexSlots[position(ids[s])] = s
        }
        count--
    }

    override fun buildLines(camX: Float, camY: Float, camZ: Float, sink: GridOverlayLineSink): Int {
        synchronized(lock) {
            val showPhotos = flags and FLAG_SHOW_PHOTOS != 0
            val showKeyframes = flags and FLAG_SHOW_KEYFRAMES != 0
            if (!showPhotos && !showKeyframes) return 0
            val flashOn = (clock.nowNs() / FLASH_HALF_PERIOD_NS) and 1L == 0L
            val maxD2 = MAX_DISTANCE_M * MAX_DISTANCE_M
            var segments = 0
            for (s in 0 until count) {
                val kind = kinds[s].toInt()
                val needed = when {
                    kind == KIND_PHOTO && showPhotos -> SEGMENTS_PHOTO
                    kind == KIND_KEYFRAME && showKeyframes -> SEGMENTS_KEYFRAME
                    else -> 0
                }
                if (needed == 0) continue
                val px = positions[s * 3]
                val py = positions[s * 3 + 1]
                val pz = positions[s * 3 + 2]
                val dx = px - camX
                val dy = py - camY
                val dz = pz - camZ
                if (dx * dx + dy * dy + dz * dz > maxD2) continue
                if (segments + needed > MAX_SEGMENTS) {
                    droppedSegments += needed
                    continue
                }
                val rgba = colour(states[s].toInt() and 0xff, flashOn)
                segments += if (kind == KIND_PHOTO) {
                    emitFrustum(s, px, py, pz, rgba, sink)
                } else {
                    emitCross(s, px, py, pz, rgba, sink)
                }
            }
            return segments
        }
    }

    private fun colour(state: Int, flashOn: Boolean): Int {
        if (state and FLASH_BIT != 0 && flashOn) return colourFlash
        return when (state and FLASH_BIT.inv()) {
            STATE_STABILIZING -> colourStabilizing
            STATE_STABLE -> colourStable
            else -> colourFresh
        }
    }

    /** Rotates local ([lx], [ly], [lz]) by the pose of slot [s] into [corner] at [o] (without translation). */
    private fun rotate(s: Int, lx: Float, ly: Float, lz: Float, o: Int) {
        val qx = rotations[s * 4]
        val qy = rotations[s * 4 + 1]
        val qz = rotations[s * 4 + 2]
        val qw = rotations[s * 4 + 3]
        // v' = v + 2 q_w (q x v) + 2 q x (q x v)
        val tx = 2f * (qy * lz - qz * ly)
        val ty = 2f * (qz * lx - qx * lz)
        val tz = 2f * (qx * ly - qy * lx)
        corner[o] = lx + qw * tx + (qy * tz - qz * ty)
        corner[o + 1] = ly + qw * ty + (qz * tx - qx * tz)
        corner[o + 2] = lz + qw * tz + (qx * ty - qy * tx)
    }

    /**
     * Camera frustum (ARCore sensor-oriented camera: x right, y up, looks
     * along -Z): apex at the position, far rectangle [FRUSTUM_DEPTH] in front.
     * Four apex edges and four rectangle edges.
     */
    private fun emitFrustum(s: Int, px: Float, py: Float, pz: Float, rgba: Int, sink: GridOverlayLineSink): Int {
        rotate(s, -FRUSTUM_HALF_WIDTH, -FRUSTUM_HALF_HEIGHT, -FRUSTUM_DEPTH, 0)
        rotate(s, FRUSTUM_HALF_WIDTH, -FRUSTUM_HALF_HEIGHT, -FRUSTUM_DEPTH, 3)
        rotate(s, FRUSTUM_HALF_WIDTH, FRUSTUM_HALF_HEIGHT, -FRUSTUM_DEPTH, 6)
        rotate(s, -FRUSTUM_HALF_WIDTH, FRUSTUM_HALF_HEIGHT, -FRUSTUM_DEPTH, 9)
        for (i in 0 until 4) {
            val o = i * 3
            sink.line(px, py, pz, px + corner[o], py + corner[o + 1], pz + corner[o + 2], rgba)
        }
        for (i in 0 until 4) {
            val a = i * 3
            val b = ((i + 1) and 3) * 3
            sink.line(
                px + corner[a], py + corner[a + 1], pz + corner[a + 2],
                px + corner[b], py + corner[b + 1], pz + corner[b + 2], rgba,
            )
        }
        return SEGMENTS_PHOTO
    }

    /** Keyframe: three segments of [CROSS_LENGTH] along the rotated local axes, centred on the position. */
    private fun emitCross(s: Int, px: Float, py: Float, pz: Float, rgba: Int, sink: GridOverlayLineSink): Int {
        val h = CROSS_LENGTH * 0.5f
        rotate(s, h, 0f, 0f, 0)
        rotate(s, 0f, h, 0f, 3)
        rotate(s, 0f, 0f, h, 6)
        for (i in 0 until 3) {
            val o = i * 3
            sink.line(
                px - corner[o], py - corner[o + 1], pz - corner[o + 2],
                px + corner[o], py + corner[o + 1], pz + corner[o + 2], rgba,
            )
        }
        return SEGMENTS_KEYFRAME
    }

    // ---------------------------------------------------------------- id index

    private fun mix(key: Int): Int {
        val h = key * -0x61c88647
        return (h xor (h ushr 16)) and indexMask
    }

    private fun position(id: Int): Int {
        var i = mix(id)
        while (indexSlots[i] != EMPTY) {
            if (indexKeys[i] == id) return i
            i = (i + 1) and indexMask
        }
        return -1
    }

    private fun find(id: Int): Int {
        val p = position(id)
        return if (p < 0) -1 else indexSlots[p]
    }

    private fun insert(id: Int, slot: Int) {
        var i = mix(id)
        while (indexSlots[i] != EMPTY) i = (i + 1) and indexMask
        indexKeys[i] = id
        indexSlots[i] = slot
    }

    private fun removeFromIndex(id: Int) {
        var hole = position(id)
        if (hole < 0) return
        var i = (hole + 1) and indexMask
        while (indexSlots[i] != EMPTY) {
            val ideal = mix(indexKeys[i])
            val move = if (hole <= i) ideal <= hole || ideal > i else ideal <= hole && ideal > i
            if (move) {
                indexKeys[hole] = indexKeys[i]
                indexSlots[hole] = indexSlots[i]
                hole = i
            }
            i = (i + 1) and indexMask
        }
        indexSlots[hole] = EMPTY
    }

    /** Poses held (render diagnostics and tests). */
    val poseCount: Int get() = synchronized(lock) { count }

    /** Current colours new, stabilizing, stable, flash (tests). */
    internal fun colourAt(index: Int): Int = synchronized(lock) {
        when (index) {
            0 -> colourFresh
            1 -> colourStabilizing
            2 -> colourStable
            else -> colourFlash
        }
    }
}
