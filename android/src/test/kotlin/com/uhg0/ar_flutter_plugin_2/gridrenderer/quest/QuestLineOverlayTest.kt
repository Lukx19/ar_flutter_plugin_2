package com.uhg0.ar_flutter_plugin_2.gridrenderer.quest

import com.uhg0.ar_flutter_plugin_2.gridrenderer.ByteArrayGpuTarget
import com.uhg0.ar_flutter_plugin_2.gridrenderer.DisplayIntake
import com.uhg0.ar_flutter_plugin_2.gridrenderer.GridLineBatch
import com.uhg0.ar_flutter_plugin_2.gridrenderer.GridLineOverlay
import com.uhg0.ar_flutter_plugin_2.gridrenderer.GridOverlayLineSink
import com.uhg0.ar_flutter_plugin_2.gridrenderer.GridRendererCore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** The quest layer plugged into the grid renderer's line overlay hook. */
class QuestLineOverlayTest {
    // Reference message of QuestOverlayLayerTest (Dart encoder output).
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

    @Test
    fun `display channel routes quest messages by magic and draws their lines`() {
        val core = GridRendererCore(ByteArrayGpuTarget())
        val overlay = QuestLineOverlay()
        core.lineOverlays.add(overlay)
        val message = reference()
        assertEquals(DisplayIntake.OVERLAY, core.onDisplayMessage(message, 0, message.capacity()))
        val batch = GridLineBatch(capacitySegments = 2048, volumetric = true)
        val segments = core.buildOverlayLines(2f, 1.6f, 2f, batch)
        assertTrue(segments > 0)
        assertSame(overlay, core.scopeOverlay())
    }

    @Test
    fun `a malformed quest message is rejected and other display messages pass through`() {
        val core = GridRendererCore(ByteArrayGpuTarget())
        core.lineOverlays.add(QuestLineOverlay())
        val bytes = reference()
        val truncated = bytes.duplicate().apply { limit(12) }.slice().order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(DisplayIntake.REJECTED, core.onDisplayMessage(truncated, 0, 12))
        val other = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putInt(0, 0x12345678)
        assertEquals(DisplayIntake.REJECTED, core.onDisplayMessage(other, 0, 8))
        assertEquals(0, core.buildOverlayLines(0f, 0f, 0f, GridLineBatch()))
    }

    /** A depth-tested overlay emitting one segment. */
    private class DepthTestedOverlay : GridLineOverlay {
        override val messageMagic = 0x11111111
        override fun onMessage(buffer: ByteBuffer, length: Int) = false
        override fun buildLines(camX: Float, camY: Float, camZ: Float, sink: GridOverlayLineSink): Int {
            sink.line(0f, 0f, 0f, 1f, 0f, 0f, 0x00ff00ff)
            return 1
        }
        override val greyOutside = false
        override fun isInScope(x: Float, y: Float, z: Float) = true
    }

    /** Distance of the batch vertex [index] from (2, 1.2, 1.5), the reference standpoint. */
    private fun standpointDistance(batch: GridLineBatch, index: Int): Float {
        val o = index * GridLineBatch.VERTEX_BYTES
        val dx = batch.vertices.getFloat(o) - 2f
        val dy = batch.vertices.getFloat(o + 4) - 1.2f
        val dz = batch.vertices.getFloat(o + 8) - 1.5f
        return kotlin.math.sqrt(dx * dx + dy * dy + dz * dz)
    }

    @Test
    fun `quest volumes are depth tested and diagnostic lines remain separate`() {
        val core = GridRendererCore(ByteArrayGpuTarget())
        core.lineOverlays.add(QuestLineOverlay())
        core.lineOverlays.add(DepthTestedOverlay())
        val message = reference()
        core.onDisplayMessage(message, 0, message.capacity())
        val lines = GridLineBatch()
        assertEquals(1, core.buildOverlayLines(2f, 1.2f, 12f, lines))
        val volume = GridLineBatch(capacitySegments = 2048, volumetric = true)
        assertTrue(core.buildOverlayLines(2f, 1.2f, 12f, volume, focalPx = 1500f, pxPerDp = 2f) > 1)
        assertEquals(volume.segments, volume.depthTestedSegments)
        assertEquals(36, volume.verticesPerSegment)
        assertTrue(kotlin.math.abs(standpointDistance(volume, 0) - 0.8f*24f*10.5f/1500f)<0.02f)
    }

    @Test
    fun `projected near and far quest volumes retain a hollow camera aperture`() {
        val frame = QuestOverlayFrame().apply {
            layers = QuestOverlayLayout.LAYER_DONUTS
            donutCount = 1
            donutAim[2] = -1f
        }
        val geometry = QuestOverlayGeometry()
        // Distance, physical focal pixels, display density. The first two
        // reproduce a screen-clamped camera ring close to the phone lens.
        val views = arrayOf(
            floatArrayOf(0.3f, 1500f, 3f),
            floatArrayOf(0.3f, 3000f, 3f),
            floatArrayOf(1.5f, 1500f, 2f),
            floatArrayOf(10.5f, 1500f, 2f),
        )
        for ((distance, focal, density) in views) {
            geometry.setView(focal, density)
            val batch = GridLineBatch(capacitySegments = 128, volumetric = true)
            geometry.build(frame, 0f, 0f, distance, OverlayLineSink(batch::line))
            val segments = if (distance > QuestOverlayGeometry.FAR_METRES) 16 else 32
            assertEquals(3 * segments, batch.segments)
            assertEquals(0L, batch.dropped)
            val expectedRadiusPx = (0.08f * focal / distance).coerceIn(12f * density, 48f * density)
            var aperturePx = Float.POSITIVE_INFINITY
            var extentPx = 0f
            for (at in 0 until batch.segments * batch.verticesPerSegment step 3) {
                val triangle = Array(3) { j ->
                    val offset = (at + j) * GridLineBatch.VERTEX_BYTES
                    val scale = focal / (distance - batch.vertices.getFloat(offset + 8))
                    val x = batch.vertices.getFloat(offset) * scale
                    val y = batch.vertices.getFloat(offset + 4) * scale
                    extentPx = maxOf(extentPx, kotlin.math.sqrt(x * x + y * y))
                    floatArrayOf(x, y)
                }
                aperturePx = minOf(aperturePx, originToTriangle(triangle))
            }
            assertTrue("camera ring must remain hollow at $distance m: $aperturePx px", aperturePx >= 0.55f * expectedRadiusPx)
            assertTrue("tube thickness must respect the screen-size clamp at $distance m: $extentPx px", extentPx <= 1.15f * expectedRadiusPx)
        }
    }

    /** Minimum distance to a projected filled triangle, including edges and degenerate faces. */
    private fun originToTriangle(p: Array<FloatArray>): Float {
        fun cross(a: FloatArray, b: FloatArray) = a[0] * b[1] - a[1] * b[0]
        val a = cross(p[0], p[1])
        val b = cross(p[1], p[2])
        val c = cross(p[2], p[0])
        if (kotlin.math.abs(a + b + c) > 1e-6f &&
            ((a >= 0f && b >= 0f && c >= 0f) || (a <= 0f && b <= 0f && c <= 0f))) return 0f
        var result = Float.POSITIVE_INFINITY
        for (i in 0..2) {
            val start = p[i]
            val end = p[(i + 1) % 3]
            val dx = end[0] - start[0]
            val dy = end[1] - start[1]
            val lengthSquared = dx * dx + dy * dy
            val t = if (lengthSquared > 1e-12f) (-(start[0] * dx + start[1] * dy) / lengthSquared).coerceIn(0f, 1f) else 0f
            val x = start[0] + t * dx
            val y = start[1] + t * dy
            result = minOf(result, kotlin.math.sqrt(x * x + y * y))
        }
        return result
    }
}
