package com.uhg0.ar_flutter_plugin_2.gridrenderer.markers

import android.os.SystemClock
import com.uhg0.ar_flutter_plugin_2.gridrenderer.GridLineOverlay
import com.uhg0.ar_flutter_plugin_2.gridrenderer.GridOverlayLineSink
import com.uhg0.ar_flutter_plugin_2.visibilitycontract.contractMagic
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt

/**
 * Marker outline overlay message (`C3MO`), written by the grid isolate's
 * marker stage (`MarkerOutlineLayout` in
 * `capture3d/lib/capture/visibility_grid/markers/marker_outline_feedback.dart`):
 * a 32 B header and 80 B entries.
 */
internal object MarkerOutlineLayout {
    val MAGIC = contractMagic("C3MO")
    const val VERSION = 1
    const val HEADER_BYTES = 32
    const val ENTRY_BYTES = 80
    const val COUNT = 6
    const val MARKER_ID = 0
    const val STATE = 2
    const val FLAGS = 3
    const val SIZE = 4
    const val POSE = 40
    const val AGE_MS = 68
    const val FLAG_POSE_VALID = 2

    const val STATE_SEEN_NOW = 1
    const val STATE_REJECTED = 2
    const val STATE_STABLE = 3
    const val STATE_USED_FOR_ALIGNMENT = 4
    const val STATE_MOVED = 5

    /** Most entries a message may carry (the Dart feedback default). */
    const val MAX_ENTRIES = 128
    const val MAX_BYTES = HEADER_BYTES + MAX_ENTRIES * ENTRY_BYTES
}

/**
 * Draws detected markers through the grid renderer's line overlay hook, so
 * the user sees when a marker is detected: a square of the printed size at
 * the marker pose and a short normal, coloured by state (seen now yellow,
 * rejected orange, stable green, used for alignment cyan, moved red).
 * Seen-now and rejected outlines disappear [flashMs] after their detection,
 * counted from the age the message reported at receipt, so the grid isolate
 * needs no clearing message. Entries without a valid pose are not drawn
 * (image-space corners would need the camera display transform).
 *
 * Messages are copied on the channel thread into a staging buffer and
 * swapped into the render buffer at the next frame; nothing allocates per
 * message or frame.
 */
internal class MarkerOutlineOverlay(
    private val nowMs: () -> Long = { SystemClock.elapsedRealtime() },
    private val flashMs: Long = DEFAULT_FLASH_MS,
) : GridLineOverlay {
    private val lock = Any()
    private val staging = ByteBuffer.allocate(MarkerOutlineLayout.MAX_BYTES).order(ByteOrder.LITTLE_ENDIAN)
    private var stagingCount = 0
    private var stagingReceivedMs = 0L
    private var stagingVersion = 0L

    private val shown = ByteBuffer.allocate(MarkerOutlineLayout.MAX_BYTES).order(ByteOrder.LITTLE_ENDIAN)
    private var shownCount = 0
    private var shownReceivedMs = 0L
    private var shownVersion = 0L

    private val corner = FloatArray(12)

    var accepted = 0L
        private set
    var rejected = 0L
        private set

    override val messageMagic: Int get() = MarkerOutlineLayout.MAGIC

    override fun onMessage(buffer: ByteBuffer, length: Int): Boolean {
        val b = buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        val base = b.position()
        if (length < MarkerOutlineLayout.HEADER_BYTES || b.remaining() < length ||
            b.getInt(base) != MarkerOutlineLayout.MAGIC ||
            (b.getShort(base + 4).toInt() and 0xffff) != MarkerOutlineLayout.VERSION
        ) {
            synchronized(lock) { rejected++ }
            return false
        }
        val count = b.getShort(base + MarkerOutlineLayout.COUNT).toInt() and 0xffff
        val bytes = MarkerOutlineLayout.HEADER_BYTES + count * MarkerOutlineLayout.ENTRY_BYTES
        if (count > MarkerOutlineLayout.MAX_ENTRIES || bytes > length) {
            synchronized(lock) { rejected++ }
            return false
        }
        synchronized(lock) {
            for (i in 0 until bytes) staging.put(i, b.get(base + i))
            stagingCount = count
            stagingReceivedMs = nowMs()
            stagingVersion++
            accepted++
        }
        return true
    }

    override fun buildLines(camX: Float, camY: Float, camZ: Float, sink: GridOverlayLineSink): Int {
        synchronized(lock) {
            if (stagingVersion != shownVersion) {
                val bytes = MarkerOutlineLayout.HEADER_BYTES + stagingCount * MarkerOutlineLayout.ENTRY_BYTES
                for (i in 0 until bytes) shown.put(i, staging.get(i))
                shownCount = stagingCount
                shownReceivedMs = stagingReceivedMs
                shownVersion = stagingVersion
            }
        }
        val sinceReceipt = nowMs() - shownReceivedMs
        var segments = 0
        for (e in 0 until shownCount) {
            val at = MarkerOutlineLayout.HEADER_BYTES + e * MarkerOutlineLayout.ENTRY_BYTES
            val flags = shown.get(at + MarkerOutlineLayout.FLAGS).toInt() and 0xff
            if (flags and MarkerOutlineLayout.FLAG_POSE_VALID == 0) continue
            val state = shown.get(at + MarkerOutlineLayout.STATE).toInt() and 0xff
            if (state == MarkerOutlineLayout.STATE_SEEN_NOW || state == MarkerOutlineLayout.STATE_REJECTED) {
                val age = shown.getInt(at + MarkerOutlineLayout.AGE_MS).toLong() and 0xffffffffL
                if (age + sinceReceipt > flashMs) continue
            }
            segments += outline(at, colourOf(state), sink)
        }
        return segments
    }

    /** Square of the printed size in the marker plane plus a normal tick; returns the segments. */
    private fun outline(at: Int, rgba: Int, sink: GridOverlayLineSink): Int {
        var size = shown.getFloat(at + MarkerOutlineLayout.SIZE)
        if (!(size > 0f) || size > MAX_SIZE_M) size = DEFAULT_SIZE_M
        val p = at + MarkerOutlineLayout.POSE
        val tx = shown.getFloat(p)
        val ty = shown.getFloat(p + 4)
        val tz = shown.getFloat(p + 8)
        var qx = shown.getFloat(p + 12)
        var qy = shown.getFloat(p + 16)
        var qz = shown.getFloat(p + 20)
        var qw = shown.getFloat(p + 24)
        val n = sqrt(qx * qx + qy * qy + qz * qz + qw * qw)
        if (!(n > 1e-6f) || !tx.isFinite() || !ty.isFinite() || !tz.isFinite()) return 0
        qx /= n
        qy /= n
        qz /= n
        qw /= n
        // Columns of the rotation: marker x, y and z axes in the world.
        val xx = 1 - 2 * (qy * qy + qz * qz)
        val xy = 2 * (qx * qy + qz * qw)
        val xz = 2 * (qx * qz - qy * qw)
        val yx = 2 * (qx * qy - qz * qw)
        val yy = 1 - 2 * (qx * qx + qz * qz)
        val yz = 2 * (qy * qz + qx * qw)
        val zx = 2 * (qx * qz + qy * qw)
        val zy = 2 * (qy * qz - qx * qw)
        val zz = 1 - 2 * (qx * qx + qy * qy)
        val h = size * 0.5f
        for (k in 0 until 4) {
            val u = if (k == 0 || k == 3) -h else h
            val v = if (k < 2) h else -h
            corner[3 * k] = tx + xx * u + yx * v
            corner[3 * k + 1] = ty + xy * u + yy * v
            corner[3 * k + 2] = tz + xz * u + yz * v
        }
        for (k in 0 until 4) {
            val a = 3 * k
            val b = 3 * ((k + 1) % 4)
            sink.line(corner[a], corner[a + 1], corner[a + 2], corner[b], corner[b + 1], corner[b + 2], rgba)
        }
        val l = size * NORMAL_FRACTION
        sink.line(tx, ty, tz, tx + zx * l, ty + zy * l, tz + zz * l, rgba)
        return SEGMENTS_PER_MARKER
    }

    override val greyOutside: Boolean get() = false

    override fun isInScope(x: Float, y: Float, z: Float): Boolean = true

    companion object {
        const val DEFAULT_FLASH_MS = 600L
        const val SEGMENTS_PER_MARKER = 5
        private const val DEFAULT_SIZE_M = 0.1f
        private const val MAX_SIZE_M = 2f
        private const val NORMAL_FRACTION = 0.5f

        fun colourOf(state: Int): Int = when (state) {
            MarkerOutlineLayout.STATE_SEEN_NOW -> 0xFFE040FF.toInt()
            MarkerOutlineLayout.STATE_REJECTED -> 0xFF8020FF.toInt()
            MarkerOutlineLayout.STATE_STABLE -> 0x40E060FF
            MarkerOutlineLayout.STATE_USED_FOR_ALIGNMENT -> 0x30D0FFFF
            MarkerOutlineLayout.STATE_MOVED -> 0xFF3030FF.toInt()
            else -> 0xFFFFFFFF.toInt()
        }
    }
}
