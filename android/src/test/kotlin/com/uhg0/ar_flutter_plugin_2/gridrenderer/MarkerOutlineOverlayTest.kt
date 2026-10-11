package com.uhg0.ar_flutter_plugin_2.gridrenderer

import com.uhg0.ar_flutter_plugin_2.gridrenderer.markers.MarkerOutlineLayout
import com.uhg0.ar_flutter_plugin_2.gridrenderer.markers.MarkerOutlineOverlay
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * The marker outline message (`C3MO`, written by the grid isolate's marker
 * stage) reaches the marker outline overlay through the renderer core's
 * display routing and becomes a square of the printed size at the marker
 * pose; seen-now outlines expire after the flash time.
 */
class MarkerOutlineOverlayTest {
    private var now = 10_000L
    private val overlay = MarkerOutlineOverlay(nowMs = { now })
    private val core = GridRendererCore(ByteArrayGpuTarget()).also { it.lineOverlays.add(overlay) }
    private val batch = GridLineBatch()

    /** One entry per (id, state, flags, size, x, y, z, age ms); identity rotation. */
    private fun message(vararg entries: List<Number>): ByteBuffer {
        val b = ByteBuffer.allocate(MarkerOutlineLayout.HEADER_BYTES + entries.size * MarkerOutlineLayout.ENTRY_BYTES)
            .order(ByteOrder.LITTLE_ENDIAN)
        b.putInt(0, MarkerOutlineLayout.MAGIC).putShort(4, 1).putShort(6, entries.size.toShort())
        b.putShort(8, 1920).putShort(10, 1080).putShort(12, 1)
        for ((i, e) in entries.withIndex()) {
            val at = MarkerOutlineLayout.HEADER_BYTES + i * MarkerOutlineLayout.ENTRY_BYTES
            b.putShort(at, e[0].toShort()).put(at + 2, e[1].toByte()).put(at + 3, e[2].toByte())
            b.putFloat(at + 4, e[3].toFloat())
            b.putFloat(at + 40, e[4].toFloat()).putFloat(at + 44, e[5].toFloat()).putFloat(at + 48, e[6].toFloat())
            b.putFloat(at + 64, 1f) // q w
            b.putInt(at + 68, e[7].toInt())
        }
        return b
    }

    private fun send(b: ByteBuffer) = core.onDisplayMessage(b, 0, b.capacity())

    private fun segmentLength(i: Int): Float {
        val v = batch.vertices
        val o = i * 2 * GridLineBatch.VERTEX_BYTES
        val dx = v.getFloat(o) - v.getFloat(o + GridLineBatch.VERTEX_BYTES)
        val dy = v.getFloat(o + 4) - v.getFloat(o + GridLineBatch.VERTEX_BYTES + 4)
        val dz = v.getFloat(o + 8) - v.getFloat(o + GridLineBatch.VERTEX_BYTES + 8)
        return sqrt(dx * dx + dy * dy + dz * dz)
    }

    @Test
    fun `a stable marker draws a square of its printed size at its pose`() {
        assertEquals(DisplayIntake.OVERLAY, send(message(listOf(7, MarkerOutlineLayout.STATE_STABLE, 3, 0.1f, 1f, 2f, -3f, 0))))
        assertEquals(MarkerOutlineOverlay.SEGMENTS_PER_MARKER, core.buildOverlayLines(0f, 0f, 0f, batch))
        for (i in 0 until 4) assertEquals(0.1f, segmentLength(i), 1e-5f)
        // Corners lie on the marker plane z = -3 around (1, 2).
        val v = batch.vertices
        assertEquals(0.95f, v.getFloat(0), 1e-5f)
        assertEquals(2.05f, v.getFloat(4), 1e-5f)
        assertEquals(-3f, v.getFloat(8), 1e-5f)
        // Colour of the stable state.
        assertEquals((MarkerOutlineOverlay.colourOf(MarkerOutlineLayout.STATE_STABLE) ushr 24).toByte(), v.get(12))
    }

    @Test
    fun `a marker seen now flashes and expires and entries without a pose are skipped`() {
        send(
            message(
                listOf(3, MarkerOutlineLayout.STATE_SEEN_NOW, 3, 0.055f, 0f, 0f, -1f, 100),
                listOf(4, MarkerOutlineLayout.STATE_SEEN_NOW, 1, 0.055f, 0f, 0f, 0f, 0),
            ),
        )
        assertEquals(MarkerOutlineOverlay.SEGMENTS_PER_MARKER, core.buildOverlayLines(0f, 0f, 0f, batch))
        now += 400
        assertEquals(MarkerOutlineOverlay.SEGMENTS_PER_MARKER, core.buildOverlayLines(0f, 0f, 0f, batch))
        now += 200 // 100 + 600 ms > flash time
        assertEquals(0, core.buildOverlayLines(0f, 0f, 0f, batch))
        // An empty message (new group) clears everything at once.
        send(message(listOf(5, MarkerOutlineLayout.STATE_MOVED, 2, 0.1f, 0f, 0f, -2f, -1)))
        assertEquals(MarkerOutlineOverlay.SEGMENTS_PER_MARKER, core.buildOverlayLines(0f, 0f, 0f, batch))
        send(message())
        assertEquals(0, core.buildOverlayLines(0f, 0f, 0f, batch))
    }

    @Test
    fun `malformed outline messages are rejected and keep the shown outlines`() {
        send(message(listOf(7, MarkerOutlineLayout.STATE_STABLE, 2, 0.1f, 0f, 0f, -1f, 0)))
        val short = message(listOf(8, MarkerOutlineLayout.STATE_STABLE, 2, 0.1f, 0f, 0f, -1f, 0))
        assertEquals(DisplayIntake.REJECTED, core.onDisplayMessage(short, 0, short.capacity() - 1))
        val badVersion = message().also { it.putShort(4, 2) }
        assertEquals(DisplayIntake.REJECTED, send(badVersion))
        assertEquals(2L, overlay.rejected)
        assertEquals(MarkerOutlineOverlay.SEGMENTS_PER_MARKER, core.buildOverlayLines(0f, 0f, 0f, batch))
        assertTrue(abs(segmentLength(0) - 0.1f) < 1e-5f)
    }
}
