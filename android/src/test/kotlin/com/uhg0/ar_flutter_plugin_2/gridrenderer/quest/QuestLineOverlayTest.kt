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
}
