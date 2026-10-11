package com.uhg0.ar_flutter_plugin_2.gridrenderer.quest

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Decodes the reference quest overlay message written by the Dart encoder
 * (`referenceOverlayHex` in
 * `capture3d/test/capture/guided_capture/quest_overlay_message_test.dart`;
 * change both together) and checks the drawn geometry.
 */
class QuestOverlayLayerTest {
    private val referenceHex =
        "4333514f01001f0001000000010003000100040001000000cdcccc3d66662640" +
            "00000000000000000000000000000000010000000100ff01000000409a99993f" +
            "0000c03f000000409a99993f0000000000a0993e000080400000704100000000" +
            "0000803f9a99993f0000000000000000000000000000c03f9a99993f00000000" +
            "0000020000000000000000409a99993f00000000000000000000000000000040" +
            "cdcc4c3e0000c03fdb0fc9bf0000000000000000000080400000000000008040" +
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
        assertEquals(0.2f, frame.footprint[1], 1e-6f)
        assertEquals(-Math.PI.toFloat() / 2, frame.footprintYaw[0], 1e-6f)
        assertTrue(frame.isInScope(2f, 1f, 1.5f))
        assertFalse(frame.isInScope(5f, 1f, 1.5f))
        assertFalse(frame.isInScope(2f, 3f, 1.5f))
    }

    @Test
    fun `near donut draws ring, inner ring and reticle, far a small circle`() {
        val layer = QuestOverlayLayer()
        val b = reference()
        assertEquals(QuestOverlayStatus.OK, layer.onMessage(b, b.capacity()))
        val near = Recorder()
        val n = layer.buildLines(2f, 1.2f, 1.5f, near)
        // 3 circles x 32 + 2 cross lines + 2 trail segments + 2 feet x 4 + 4 zone edges.
        assertEquals(3 * 32 + 2 + 2 + 8 + 4, n)
        assertEquals(n, near.points.size)
        // The outer ring lies in the plane facing the aim point, at the
        // standpoint tolerance.
        for (k in 0 until 32) {
            val p = near.points[k]
            assertEquals(1.5f, p[2], 1e-5f)
            val r = sqrt((p[0] - 2f) * (p[0] - 2f) + (p[1] - 1.2f) * (p[1] - 1.2f))
            assertEquals(0.3f, r, 1e-3f)
            assertEquals(QuestOverlayGeometry.COLOUR_AIMED, near.colours[k])
        }
        val far = Recorder()
        assertEquals(16 + 2 + 8 + 4, layer.buildLines(2f, 1.2f, 12f, far))
        assertTrue(layer.greyOutside)
        assertFalse(layer.isInScope(6f, 1f, 1f))
    }

    @Test
    fun `captured trail segment is solid, the rest pending`() {
        val layer = QuestOverlayLayer()
        val b = reference()
        layer.onMessage(b, b.capacity())
        val r = Recorder()
        layer.buildLines(2f, 1.2f, 12f, r)
        // Far circle 16 segments, then the two trail segments; station 1 is
        // captured but each segment needs both ends captured.
        assertEquals(QuestOverlayGeometry.COLOUR_TRAIL_PENDING, r.colours[16])
        assertEquals(QuestOverlayGeometry.COLOUR_TRAIL_PENDING, r.colours[17])
        b.put(QuestOverlayLayout.HEADER_BYTES + QuestOverlayLayout.DONUT_BYTES + 14, 2)
        layer.onMessage(b, b.capacity())
        val solid = Recorder()
        layer.buildLines(2f, 1.2f, 12f, solid)
        assertEquals(QuestOverlayGeometry.COLOUR_TRAIL_CAPTURED, solid.colours[16])
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
        // Lit phases draw the 16-segment far circle on top of the rest (14).
        assertEquals(listOf(30, 14, 30, 14, 30, 14, 14), counts.toList())
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
        assertEquals(30, layer.buildLines(2f, 1.2f, 12f, Recorder()))
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
