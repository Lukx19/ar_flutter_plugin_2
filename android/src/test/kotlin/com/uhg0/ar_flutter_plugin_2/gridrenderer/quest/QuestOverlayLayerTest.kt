package com.uhg0.ar_flutter_plugin_2.gridrenderer.quest

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * Decodes the reference quest overlay message written by the Dart encoder
 * (`referenceOverlayHex` in
 * `capture3d/test/capture/guided_capture/quest_overlay_message_test.dart`;
 * change both together) and checks the drawn geometry.
 */
class QuestOverlayLayerTest {
    private val referenceHex =
        "4333514f01001f0001000000010003000100040001186000cdcccc3d66662640" +
            "00000000000000000000000000000000010000000100ff01000000409a99993f" +
            "0000c03f000000409a99993f0000000000a0993e00008040000070410ad7a33d" +
            "0000803f9a99993f0000000000000000000000000000c03f9a99993f00000000" +
            "0000020000000000000000409a99993f00000000000000000000000000000040" +
            "8fc2f53d0000c03fdb0fc9bf0000000000000000000080400000000000008040" +
            "000040400000000000004040"

    private fun reference(): ByteBuffer {
        val bytes = ByteArray(referenceHex.length / 2) {
            referenceHex.substring(2 * it, 2 * it + 2).toInt(16).toByte()
        }
        return ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
    }

    private class Recorder : OverlayLineSink {
        val points = ArrayList<FloatArray>()
        val colours = ArrayList<Int>()
        override fun line(x0: Float, y0: Float, z0: Float, x1: Float, y1: Float, z1: Float, rgba: Int) {
            points.add(floatArrayOf(x0, y0, z0, x1, y1, z1))
            colours.add(rgba)
        }
    }

    @Test
    fun `reference message decodes field by field`() {
        val frame = QuestOverlayFrame()
        val b = reference()
        assertEquals(QuestOverlayStatus.OK, frame.decode(b, b.capacity()))
        assertEquals(0x1f, frame.layers)
        assertEquals(1L, frame.sequence)
        assertEquals(1, frame.donutCount)
        assertEquals(3, frame.trailVertexCount)
        assertEquals(1, frame.footprintCount)
        assertEquals(4, frame.zoneVertexCount)
        assertEquals(QuestOverlayLayout.ZONE_SPACE, frame.zoneKind)
        assertEquals(0.1f, frame.zoneParams[0], 1e-6f)
        assertEquals(2.6f, frame.zoneParams[1], 1e-6f)
        assertEquals(1, frame.donutQuestId[0])
        assertEquals(1, frame.donutState[0])
        assertEquals(QuestOverlayLayout.FLASH_NONE, frame.donutFlash[0])
        assertEquals(QuestOverlayLayout.DONUT_FLAG_AIMED, frame.donutFlags[0])
        assertEquals(1.5f, frame.donutStandpoint[2], 1e-6f)
        assertEquals(0f, frame.donutAim[2], 1e-6f)
        assertEquals(0.3f, frame.donutPositionTolerance[0], 1e-3f)
        assertEquals(4f, frame.donutInnerCircleDegrees[0], 1e-6f)
        assertEquals(15f, frame.donutAngleToleranceDegrees[0], 1e-2f)
        assertEquals(QuestOverlayLayout.STATION_CAPTURED, frame.trailStationState[1])
        assertEquals(1.5f, frame.trailPoint[3], 1e-6f)
        assertEquals(0.12f, frame.footprint[1], 1e-6f)
        assertEquals(-Math.PI.toFloat() / 2, frame.footprintYaw[0], 1e-6f)
        assertTrue(frame.isInScope(2f, 1f, 1.5f))
        assertFalse(frame.isInScope(5f, 1f, 1.5f))
        assertFalse(frame.isInScope(2f, 3f, 1.5f))
    }

    // Reference donut: aimed, standpoint (2, 1.2, 1.5) facing the aim point
    // (2, 1.2, 0); tolerance 0.3 m, inner circle 4 deg, ring radius 0.08 m.
    // Other reference lines: 2 dashed trail segments x 5 dashes, 2 soles x
    // 10 edges, 4 zone edges.
    private val others = 10 + 20 + 4

    private fun radiusAround(p: FloatArray, cx: Float, cy: Float, cz: Float): Float {
        val dx = p[0] - cx
        val dy = p[1] - cy
        val dz = p[2] - cz
        return sqrt(dx * dx + dy * dy + dz * dz)
    }

    @Test
    fun `reference ring fields decode`() {
        val frame = QuestOverlayFrame()
        val b = reference()
        assertEquals(QuestOverlayStatus.OK, frame.decode(b, b.capacity()))
        assertEquals(24, frame.ringMinDiameterDp)
        assertEquals(96, frame.ringMaxDiameterDp)
        assertEquals(0.08f, frame.donutRingRadius[0], 1e-6f)
    }

    @Test
    fun `a donut is a thin 16 cm ring at the standpoint facing the aim point`() {
        val layer = QuestOverlayLayer()
        val b = reference()
        layer.onMessage(b, b.capacity())
        val r = Recorder()
        // 1.5 m behind the standpoint: three 32-segment loops, no reticle.
        assertEquals(3 * 32 + others, layer.buildLines(2f, 1.2f, 3f, r))
        val loops = floatArrayOf(0.064f, 0.072f, 0.08f)
        for (k in 0 until 96) {
            val p = r.points[k]
            assertEquals(1.5f, p[2], 1e-5f)
            assertEquals(1.5f, p[5], 1e-5f)
            assertEquals(loops[k / 32], radiusAround(p, 2f, 1.2f, 1.5f), 1e-5f)
            assertEquals(QuestOverlayGeometry.COLOUR_AIMED, r.colours[k])
        }
        // Outer diameter within 12-20 cm.
        assertTrue(2 * loops[2] in 0.12f..0.2f)
    }

    @Test
    fun `the ring stays between 24 and 96 dp on screen`() {
        val layer = QuestOverlayLayer()
        val b = reference()
        layer.onMessage(b, b.capacity())
        val focal = 1500f
        val density = 2f
        layer.setView(focal, density)
        // Camera distance, expected on-screen outer radius in px.
        val cases = arrayOf(
            floatArrayOf(0.7f, 48f * density), // 171 px unclamped: capped at 96 dp across
            floatArrayOf(1.5f, 80f), // 0.08 * 1500 / 1.5 = 80 px: inside the range
            floatArrayOf(10.5f, 12f * density), // 11 px unclamped: raised to 24 dp across
        )
        for (c in cases) {
            val r = Recorder()
            layer.buildLines(2f, 1.2f, 1.5f + c[0], r)
            val segments = if (c[0] > QuestOverlayGeometry.FAR_METRES) 16 else 32
            val outer = radiusAround(r.points[2 * segments], 2f, 1.2f, 1.5f)
            assertEquals("distance ${c[0]}", c[1], outer * focal / c[0], 0.05f)
            for (k in 0 until 3 * segments) assertEquals(1.5f, r.points[k][2], 1e-5f)
        }
    }

    @Test
    fun `the aim reticle appears only near the standpoint`() {
        val layer = QuestOverlayLayer()
        val b = reference()
        layer.onMessage(b, b.capacity())
        layer.setView(1500f, 2f)
        // At the standpoint: aim ring (3 x 32) and a 16-dash reticle, both
        // around the aim point; the reticle is the 4 deg inner circle.
        val near = Recorder()
        assertEquals(3 * 32 + 16 + others, layer.buildLines(2f, 1.2f, 1.5f, near))
        val reticle = 1.5f * tan(Math.toRadians(4.0)).toFloat()
        for (k in 0 until 96) {
            assertEquals(0f, near.points[k][2], 1e-5f)
            val expected = 1.5f * reticle * QuestOverlayGeometry.RING_LOOPS[k / 32]
            assertEquals(expected, radiusAround(near.points[k], 2f, 1.2f, 0f), 1e-4f)
        }
        for (k in 96 until 112) {
            assertEquals(reticle, radiusAround(near.points[k], 2f, 1.2f, 0f), 1e-4f)
            assertEquals(QuestOverlayGeometry.COLOUR_AIMED, near.colours[k])
        }
        // Just outside twice the tolerance and far away: no line near the aim point.
        for (z in floatArrayOf(2.11f, 12f)) {
            val r = Recorder()
            layer.buildLines(2f, 1.2f, z, r)
            val ring = if (z > 1.5f + QuestOverlayGeometry.FAR_METRES) 3 * 16 else 3 * 32
            for (k in 0 until ring) assertTrue(radiusAround(r.points[k], 2f, 1.2f, 0f) > 1f)
        }
    }

    private fun donutColour(state: Int, flags: Int, phase: Int): Int {
        val frame = QuestOverlayFrame()
        val b = reference()
        val donut = QuestOverlayLayout.HEADER_BYTES
        b.put(donut + 4, state.toByte())
        b.put(donut + 6, phase.toByte())
        b.put(donut + 7, flags.toByte())
        frame.decode(b, b.capacity())
        return QuestOverlayGeometry().donutColour(frame, 0)
    }

    @Test
    fun `colours per state`() {
        val none = QuestOverlayLayout.FLASH_NONE
        assertEquals(QuestOverlayGeometry.COLOUR_SHOWN, donutColour(0, 0, none))
        assertEquals(QuestOverlayGeometry.COLOUR_AIMED, donutColour(1, QuestOverlayLayout.DONUT_FLAG_AIMED, none))
        assertEquals(
            QuestOverlayGeometry.COLOUR_TRAIL_START,
            donutColour(0, QuestOverlayLayout.DONUT_FLAG_TRAIL_START, none),
        )
        // Captured: three white flashes, then nothing.
        val captured = (0..5).map { donutColour(QuestOverlayLayout.STATE_CAPTURED, 0, it) }
        val f = QuestOverlayGeometry.COLOUR_FLASH
        assertEquals(listOf(f, 0, f, 0, f, 0), captured)
        assertEquals(0, donutColour(QuestOverlayLayout.STATE_CAPTURED, 0, none))
        // Timed out: the shown colour fading out, then nothing.
        val faded = (0..5).map { donutColour(QuestOverlayLayout.STATE_TIMED_OUT, 0, it) }
        for (k in 0..5) {
            assertEquals(QuestOverlayGeometry.COLOUR_TIMED_OUT ushr 8, faded[k] ushr 8)
            if (k > 0) assertTrue((faded[k] and 0xff) < (faded[k - 1] and 0xff))
        }
        assertTrue((faded[0] and 0xff) < 255)
        assertEquals(0, donutColour(QuestOverlayLayout.STATE_TIMED_OUT, 0, none))
    }

    @Test
    fun `captured trail segment is solid, the rest dashed`() {
        val layer = QuestOverlayLayer()
        val b = reference()
        layer.onMessage(b, b.capacity())
        val r = Recorder()
        val ring = 3 * 16
        assertEquals(ring + others, layer.buildLines(2f, 1.2f, 12f, r))
        // Station 1 is captured but each segment needs both ends captured:
        // two 0.5 m segments of five 5 cm dashes.
        for (k in ring until ring + 10) {
            assertEquals(QuestOverlayGeometry.COLOUR_TRAIL_PENDING, r.colours[k])
            val p = r.points[k]
            assertEquals(0.05f, abs(p[3] - p[0]), 1e-5f)
        }
        b.put(QuestOverlayLayout.HEADER_BYTES + QuestOverlayLayout.DONUT_BYTES + 14, 2)
        layer.onMessage(b, b.capacity())
        val solid = Recorder()
        assertEquals(ring + 1 + 5 + 20 + 4, layer.buildLines(2f, 1.2f, 12f, solid))
        assertEquals(QuestOverlayGeometry.COLOUR_TRAIL_CAPTURED, solid.colours[ring])
        assertEquals(0.5f, abs(solid.points[ring][3] - solid.points[ring][0]), 1e-5f)
    }

    @Test
    fun `footprints are two foot-sized soles at the standpoint`() {
        val layer = QuestOverlayLayer()
        val b = reference()
        layer.onMessage(b, b.capacity())
        val r = Recorder()
        layer.buildLines(2f, 1.2f, 12f, r)
        val first = 3 * 16 + 10
        var minX = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE
        var minZ = Float.MAX_VALUE
        var maxZ = -Float.MAX_VALUE
        for (k in first until first + 20) {
            val p = r.points[k]
            assertEquals(0.12f, p[1], 1e-6f)
            minX = minOf(minX, p[0])
            maxX = maxOf(maxX, p[0])
            minZ = minOf(minZ, p[2])
            maxZ = maxOf(maxZ, p[2])
            assertEquals(QuestOverlayGeometry.COLOUR_FOOTPRINT, r.colours[k])
        }
        // Facing -Z: 26 cm long; two soles 10 cm wide whose centres are 20 cm apart.
        assertEquals(0.26f, maxZ - minZ, 1e-5f)
        assertEquals(0.3f, maxX - minX, 1e-5f)
    }

    @Test
    fun `captured donut flashes three times`() {
        val layer = QuestOverlayLayer()
        val b = reference()
        val donut = QuestOverlayLayout.HEADER_BYTES
        b.put(donut + 4, QuestOverlayLayout.STATE_CAPTURED.toByte())
        val counts = IntArray(7)
        for (phase in 0..6) {
            b.put(donut + 6, (if (phase == 6) QuestOverlayLayout.FLASH_NONE else phase).toByte())
            layer.onMessage(b, b.capacity())
            counts[phase] = layer.buildLines(2f, 1.2f, 12f, Recorder())
        }
        // Lit phases draw the far ring (3 x 16) on top of the rest.
        val lit = 48 + others
        assertEquals(listOf(lit, others, lit, others, lit, others, others), counts.toList())
    }

    @Test
    fun `rebuilds allocate nothing`() {
        val layer = QuestOverlayLayer()
        val b = reference()
        layer.onMessage(b, b.capacity())
        layer.setView(1500f, 2f)
        val sink = OverlayLineSink { _, _, _, _, _, _, _ -> }
        val bean = Class.forName("java.lang.management.ManagementFactory").getMethod("getThreadMXBean").invoke(null)
        val read = Class.forName("com.sun.management.ThreadMXBean")
            .getMethod("getThreadAllocatedBytes", Long::class.javaPrimitiveType)
        val id: Any = Thread.currentThread().id
        // Warm up the near, mid and far paths.
        repeat(2000) {
            layer.buildLines(2f, 1.2f, 1.5f, sink)
            layer.buildLines(2f, 1.2f, 3f, sink)
            layer.buildLines(2f, 1.2f, 12f, sink)
        }
        val before = read.invoke(bean, id) as Long
        repeat(1000) {
            layer.buildLines(2f, 1.2f, 1.5f, sink)
            layer.buildLines(2f, 1.2f, 3f, sink)
            layer.buildLines(2f, 1.2f, 12f, sink)
        }
        val allocated = (read.invoke(bean, id) as Long) - before
        // The probe boxes a few longs itself; 3000 rebuilds must add no per-frame garbage.
        assertTrue("allocated $allocated bytes", allocated < 1024)
    }

    @Test
    fun `rejected messages keep the shown frame`() {
        val layer = QuestOverlayLayer()
        val b = reference()
        layer.onMessage(b, b.capacity())
        val bad = reference()
        bad.putInt(0, 0x12345678)
        assertEquals(QuestOverlayStatus.BAD_MAGIC, layer.onMessage(bad, bad.capacity()))
        assertEquals(QuestOverlayStatus.BAD_LENGTH, layer.onMessage(b, b.capacity() - 8))
        assertEquals(QuestOverlayStatus.TOO_SHORT, layer.onMessage(b, 20))
        val version = reference()
        version.putShort(4, 9)
        assertEquals(QuestOverlayStatus.BAD_VERSION, layer.onMessage(version, version.capacity()))
        assertEquals(4, layer.rejectedMessages)
        assertEquals(48 + others, layer.buildLines(2f, 1.2f, 12f, Recorder()))
    }

    @Test
    fun `object zone draws twelve box edges`() {
        val b = ByteBuffer.allocate(QuestOverlayLayout.HEADER_BYTES).order(ByteOrder.LITTLE_ENDIAN)
        b.putInt(0, QuestOverlayLayout.MAGIC)
        b.putShort(4, 1)
        b.putShort(6, QuestOverlayLayout.LAYER_ZONE_OUTLINE.toShort())
        b.put(20, QuestOverlayLayout.ZONE_OBJECT.toByte())
        val params = floatArrayOf(0f, 0.5f, 0f, 0.3f, 0.5f, 0.3f)
        for (i in 0 until 6) b.putFloat(24 + 4 * i, params[i])
        val layer = QuestOverlayLayer()
        assertEquals(QuestOverlayStatus.OK, layer.onMessage(b, b.capacity()))
        val r = Recorder()
        assertEquals(12, layer.buildLines(0f, 1f, 2f, r))
        for (p in r.points) {
            val dx = p[3] - p[0]
            val dy = p[4] - p[1]
            val dz = p[5] - p[2]
            val len = sqrt(dx * dx + dy * dy + dz * dz)
            assertTrue(abs(len - 0.6f) < 1e-5f || abs(len - 1f) < 1e-5f)
        }
        assertTrue(layer.isInScope(0.1f, 0.2f, -0.1f))
        assertFalse(layer.isInScope(0.5f, 0.2f, 0f))
    }

    @Test
    fun `steady state rebuilds draw the same frame`() {
        val layer = QuestOverlayLayer()
        val b = reference()
        layer.onMessage(b, b.capacity())
        val sink = OverlayLineSink { _, _, _, _, _, _, _ -> }
        val first = layer.buildLines(2f, 1.2f, 1.5f, sink)
        for (i in 0 until 1000) assertEquals(first, layer.buildLines(2f, 1.2f, 1.5f, sink))
    }
}
