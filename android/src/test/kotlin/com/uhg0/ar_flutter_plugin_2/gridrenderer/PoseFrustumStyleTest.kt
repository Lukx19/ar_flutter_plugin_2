package com.uhg0.ar_flutter_plugin_2.gridrenderer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Pose marker deltas (`C3PM`) decoded into photo frusta and keyframe crosses. */
class PoseFrustumStyleTest {
    private var now = 0L
    private val style = PoseFrustumStyle { now }

    private class Segment(val a: FloatArray, val b: FloatArray, val rgba: Int)

    private class Recorder : GridOverlayLineSink {
        val segments = ArrayList<Segment>()
        override fun line(x0: Float, y0: Float, z0: Float, x1: Float, y1: Float, z1: Float, rgba: Int) {
            segments += Segment(floatArrayOf(x0, y0, z0), floatArrayOf(x1, y1, z1), rgba)
        }
    }

    private class Pose(
        val id: Int,
        val kind: Int,
        val state: Int = PoseFrustumStyle.STATE_STABLE,
        val x: Float = 0f,
        val y: Float = 0f,
        val z: Float = 0f,
        val q: FloatArray = floatArrayOf(0f, 0f, 0f, 1f),
    )

    private fun message(flags: Int, poses: List<Pose>, magic: Int = PoseFrustumStyle.MAGIC, version: Int = 1): ByteBuffer {
        val b = ByteBuffer.allocate(PoseFrustumStyle.HEADER_BYTES + poses.size * PoseFrustumStyle.RECORD_BYTES)
            .order(ByteOrder.LITTLE_ENDIAN)
        b.putInt(0, magic)
        b.putShort(4, version.toShort())
        b.putShort(PoseFrustumStyle.COUNT_OFFSET, poses.size.toShort())
        b.putInt(PoseFrustumStyle.FLAGS_OFFSET, flags)
        poses.forEachIndexed { i, p ->
            val o = PoseFrustumStyle.HEADER_BYTES + i * PoseFrustumStyle.RECORD_BYTES
            b.putInt(o + PoseFrustumStyle.ID_OFFSET, p.id)
            b.put(o + PoseFrustumStyle.KIND_OFFSET, p.kind.toByte())
            b.put(o + PoseFrustumStyle.STATE_OFFSET, p.state.toByte())
            b.putShort(o + PoseFrustumStyle.SUBMAP_OFFSET, 0)
            b.putFloat(o + PoseFrustumStyle.POSITION_OFFSET, p.x)
            b.putFloat(o + PoseFrustumStyle.POSITION_OFFSET + 4, p.y)
            b.putFloat(o + PoseFrustumStyle.POSITION_OFFSET + 8, p.z)
            for (k in 0 until 4) b.putShort(o + PoseFrustumStyle.ROTATION_OFFSET + 2 * k, (p.q[k] * 32767f).roundToInt().toShort())
            b.putShort(o + PoseFrustumStyle.AGE_OFFSET, 1)
            b.putShort(o + PoseFrustumStyle.CORRECTION_OFFSET, 2)
        }
        return b
    }

    private fun send(flags: Int, poses: List<Pose>): Boolean {
        val b = message(flags, poses)
        return style.onMessage(b, b.capacity())
    }

    private fun lines(camX: Float = 0f, camY: Float = 0f, camZ: Float = 0f): Recorder {
        val r = Recorder()
        val n = style.buildLines(camX, camY, camZ, r)
        assertEquals(r.segments.size, n)
        return r
    }

    @Test
    fun `photos draw eight segments and keyframes three`() {
        assertTrue(send(BOTH, listOf(Pose(1, PHOTO), Pose(2, KEYFRAME), Pose(3, KEYFRAME))))
        assertEquals(3, style.poseCount)
        assertEquals(8 + 3 + 3, lines().segments.size)
        assertEquals(PoseFrustumStyle.MAGIC, style.messageMagic)
        assertFalse(style.greyOutside)
        assertTrue(style.isInScope(100f, 0f, 0f))
    }

    @Test
    fun `show flags filter the kinds`() {
        send(BOTH, listOf(Pose(1, PHOTO), Pose(2, KEYFRAME)))
        send(PoseFrustumStyle.FLAG_SHOW_PHOTOS, emptyList())
        assertEquals(8, lines().segments.size)
        send(PoseFrustumStyle.FLAG_SHOW_KEYFRAMES, emptyList())
        assertEquals(3, lines().segments.size)
        send(0, emptyList())
        assertEquals(0, lines().segments.size)
        assertEquals(2, style.poseCount)
    }

    @Test
    fun `removed state deletes and full refresh replaces`() {
        send(BOTH, listOf(Pose(1, PHOTO), Pose(2, KEYFRAME), Pose(3, PHOTO)))
        send(BOTH, listOf(Pose(1, PHOTO, state = PoseFrustumStyle.STATE_REMOVED)))
        assertEquals(2, style.poseCount)
        assertEquals(8 + 3, lines().segments.size)
        send(BOTH or PoseFrustumStyle.FLAG_FULL_REFRESH, listOf(Pose(9, KEYFRAME)))
        assertEquals(1, style.poseCount)
        assertEquals(3, lines().segments.size)
        // Updating an existing id keeps one entry; removing an unknown id is harmless.
        send(BOTH, listOf(Pose(9, KEYFRAME, x = 1f), Pose(42, PHOTO, state = PoseFrustumStyle.STATE_REMOVED)))
        assertEquals(1, style.poseCount)
        assertEquals(1f, (lines().segments[0].a[0] + lines().segments[0].b[0]) / 2f, 1e-6f)
    }

    @Test
    fun `colours follow the state and the colour table`() {
        send(BOTH, listOf(
            Pose(1, KEYFRAME, state = PoseFrustumStyle.STATE_FRESH),
            Pose(2, KEYFRAME, state = PoseFrustumStyle.STATE_STABILIZING, x = 0.5f),
            Pose(3, KEYFRAME, state = PoseFrustumStyle.STATE_STABLE, x = 1f),
        ))
        val defaults = lines().segments.map { it.rgba }.distinct()
        assertEquals(listOf(PoseFrustumStyle.DEFAULT_FRESH, PoseFrustumStyle.DEFAULT_STABILIZING, PoseFrustumStyle.DEFAULT_STABLE), defaults)

        val table = ByteBuffer.allocate(PoseFrustumStyle.HEADER_BYTES + 16).order(ByteOrder.LITTLE_ENDIAN)
        table.putInt(0, PoseFrustumStyle.MAGIC).putShort(4, 1).putShort(6, 0).putInt(8, PoseFrustumStyle.FLAG_COLOUR_TABLE)
        table.putInt(16, 0x11111111).putInt(20, 0x22222222).putInt(24, 0x33333333).putInt(28, 0x44444444)
        assertTrue(style.onMessage(table, table.capacity()))
        assertEquals(listOf(0x11111111, 0x22222222, 0x33333333), lines().segments.map { it.rgba }.distinct())
        assertEquals(0x44444444, style.colourAt(3))
        assertEquals(3, style.poseCount)
    }

    @Test
    fun `flash alternates the flash colour about twice a second`() {
        send(BOTH, listOf(Pose(1, KEYFRAME, state = PoseFrustumStyle.STATE_STABLE or PoseFrustumStyle.FLASH_BIT)))
        now = 0L
        assertEquals(PoseFrustumStyle.DEFAULT_FLASH, lines().segments[0].rgba)
        now = PoseFrustumStyle.FLASH_HALF_PERIOD_NS
        assertEquals(PoseFrustumStyle.DEFAULT_STABLE, lines().segments[0].rgba)
        now = 2 * PoseFrustumStyle.FLASH_HALF_PERIOD_NS
        assertEquals(PoseFrustumStyle.DEFAULT_FLASH, lines().segments[0].rgba)
    }

    @Test
    fun `malformed messages are rejected`() {
        val wrongMagic = message(BOTH, listOf(Pose(1, PHOTO)), magic = 0x0BADF00D)
        assertFalse(style.onMessage(wrongMagic, wrongMagic.capacity()))
        val wrongVersion = message(BOTH, listOf(Pose(1, PHOTO)), version = 2)
        assertFalse(style.onMessage(wrongVersion, wrongVersion.capacity()))
        val truncated = message(BOTH, listOf(Pose(1, PHOTO), Pose(2, PHOTO)))
        assertFalse(style.onMessage(truncated, PoseFrustumStyle.HEADER_BYTES + PoseFrustumStyle.RECORD_BYTES))
        val shortTable = ByteBuffer.allocate(PoseFrustumStyle.HEADER_BYTES + 8).order(ByteOrder.LITTLE_ENDIAN)
        shortTable.putInt(0, PoseFrustumStyle.MAGIC).putShort(4, 1).putInt(8, PoseFrustumStyle.FLAG_COLOUR_TABLE)
        assertFalse(style.onMessage(shortTable, shortTable.capacity()))
        assertFalse(style.onMessage(ByteBuffer.allocate(8), 8))
        assertEquals(5L, style.rejectedMessages)
        assertEquals(0, style.poseCount)
        assertEquals(0, lines().segments.size)
    }

    @Test
    fun `poses beyond eight metres are skipped and segments stop at the cap`() {
        send(BOTH, listOf(Pose(1, PHOTO, x = 7.9f), Pose(2, PHOTO, x = 8.1f)))
        assertEquals(8, lines().segments.size)
        assertEquals(16, lines(camX = 4f).segments.size)

        val many = (0 until 700).map { Pose(100 + it, PHOTO, x = (it % 10) * 0.1f) }
        send(BOTH or PoseFrustumStyle.FLAG_FULL_REFRESH, many)
        assertEquals(700, style.poseCount)
        assertEquals(PoseFrustumStyle.MAX_SEGMENTS, lines().segments.size)
        assertEquals((700 * 8 - PoseFrustumStyle.MAX_SEGMENTS).toLong(), style.droppedSegments)
    }

    @Test
    fun `frustum apex and corners for an identity pose`() {
        send(BOTH, listOf(Pose(1, PHOTO, x = 1f, y = 2f, z = 3f)))
        val s = lines().segments
        for (i in 0 until 4) assertPoint(floatArrayOf(1f, 2f, 3f), s[i].a)
        assertPoint(floatArrayOf(1f - 0.05f, 2f - 0.0375f, 3f - 0.08f), s[0].b)
        assertPoint(floatArrayOf(1f + 0.05f, 2f - 0.0375f, 3f - 0.08f), s[1].b)
        assertPoint(floatArrayOf(1f + 0.05f, 2f + 0.0375f, 3f - 0.08f), s[2].b)
        assertPoint(floatArrayOf(1f - 0.05f, 2f + 0.0375f, 3f - 0.08f), s[3].b)
        // Rectangle edges join consecutive corners.
        for (i in 0 until 4) {
            assertPoint(s[i].b, s[4 + i].a)
            assertPoint(s[(i + 1) % 4].b, s[4 + i].b)
        }
    }

    @Test
    fun `frustum turns with the pose rotation`() {
        // 90 degrees about +Y: local -Z (the view direction) maps to world -X, local +X to world -Z.
        val h = (sqrt(0.5)).toFloat()
        send(BOTH, listOf(Pose(1, PHOTO, q = floatArrayOf(0f, h, 0f, h))))
        val s = lines().segments
        assertPoint(floatArrayOf(-0.08f, -0.0375f, 0.05f), s[0].b, 2e-4f)
        assertPoint(floatArrayOf(-0.08f, -0.0375f, -0.05f), s[1].b, 2e-4f)
        assertPoint(floatArrayOf(-0.08f, 0.0375f, -0.05f), s[2].b, 2e-4f)
    }

    @Test
    fun `keyframe cross spans three centimetres per axis`() {
        send(BOTH, listOf(Pose(1, KEYFRAME, x = 1f)))
        val s = lines().segments
        assertPoint(floatArrayOf(0.985f, 0f, 0f), s[0].a)
        assertPoint(floatArrayOf(1.015f, 0f, 0f), s[0].b)
        assertPoint(floatArrayOf(1f, -0.015f, 0f), s[1].a)
        assertPoint(floatArrayOf(1f, 0f, 0.015f), s[2].b)
    }

    @Test
    fun `table holds at most 4096 poses`() {
        val first = (0 until 2047).map { Pose(it, KEYFRAME) }
        val second = (2047 until 4094).map { Pose(it, KEYFRAME) }
        val third = (4094 until 4100).map { Pose(it, KEYFRAME) }
        send(BOTH, first)
        send(BOTH, second)
        send(BOTH, third)
        assertEquals(PoseFrustumStyle.MAX_POSES, style.poseCount)
        assertEquals(4L, style.tableFullDrops)
    }

    private fun assertPoint(expected: FloatArray, actual: FloatArray, tolerance: Float = 1e-6f) {
        for (k in 0 until 3) assertEquals(expected[k], actual[k], tolerance)
    }

    private companion object {
        const val PHOTO = PoseFrustumStyle.KIND_PHOTO
        const val KEYFRAME = PoseFrustumStyle.KIND_KEYFRAME
        const val BOTH = PoseFrustumStyle.FLAG_SHOW_PHOTOS or PoseFrustumStyle.FLAG_SHOW_KEYFRAMES
    }
}
