package com.uhg0.ar_flutter_plugin_2.gridrenderer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Data-only overlays: message routing by magic, per-frame lines and scope. */
class GridLineOverlayTest {
    private class RecordingOverlay : GridLineOverlay {
        override val messageMagic = GridLineOverlay.QUEST_OVERLAY_MAGIC
        var messages = 0
        var lastLength = 0
        var firstInt = 0
        override var greyOutside = false

        override fun onMessage(buffer: ByteBuffer, length: Int): Boolean {
            messages++
            lastLength = length
            firstInt = buffer.getInt(0)
            return length >= 8
        }

        override fun buildLines(camX: Float, camY: Float, camZ: Float, sink: GridOverlayLineSink): Int {
            sink.line(camX, camY, camZ, camX, camY, camZ - 1f, 0x00ff00ff)
            sink.line(0f, 0f, 0f, 1f, 0f, 0f, 0xff0000ff.toInt())
            return 2
        }

        override fun isInScope(x: Float, y: Float, z: Float): Boolean = x >= 0f
    }

    @Test
    fun `display messages with the overlay magic reach the overlay, others the mode handler`() {
        val core = GridRendererCore(ByteArrayGpuTarget())
        val overlay = RecordingOverlay()
        core.lineOverlays.add(overlay)
        val message = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN)
        message.putInt(4, GridLineOverlay.QUEST_OVERLAY_MAGIC)
        assertEquals(DisplayIntake.OVERLAY, core.onDisplayMessage(message, 4, 20))
        assertEquals(1, overlay.messages)
        assertEquals(20, overlay.lastLength)
        assertEquals(GridLineOverlay.QUEST_OVERLAY_MAGIC, overlay.firstInt)
        assertEquals(DisplayIntake.REJECTED, core.onDisplayMessage(message, 4, 4))
        val w = RendererDeltaWriter()
        w.displayMode(GridDisplayCodes.BASE_SPLATS, 0, GridDisplayCodes.COLOUR_AGE, 0)
        assertEquals(DisplayIntake.MODE, core.onDisplayMessage(w.buffer, 0, w.length))
        assertEquals(2, overlay.messages)
    }

    @Test
    fun `lines collect into the batch and the scope overlay is reported`() {
        val core = GridRendererCore(ByteArrayGpuTarget())
        val overlay = RecordingOverlay()
        core.lineOverlays.add(overlay)
        val batch = GridLineBatch(capacitySegments = 3)
        assertEquals(2, core.buildOverlayLines(0.5f, 1f, 2f, batch))
        assertEquals(0.5f, batch.vertices.getFloat(0))
        assertEquals(1f, batch.vertices.getFloat(GridLineBatch.VERTEX_BYTES + 4))
        assertEquals(0x00, batch.vertices.get(12).toInt())
        assertEquals(0xff, batch.vertices.get(13).toInt() and 0xff)
        assertEquals(2, core.buildOverlayLines(0f, 0f, 0f, batch))
        core.buildOverlayLines(0f, 0f, 0f, batch)
        repeat(2) { batch.line(0f, 0f, 0f, 0f, 0f, 0f, 0) }
        assertEquals(3, batch.segments)
        assertEquals(1L, batch.dropped)
        assertNull(core.scopeOverlay())
        overlay.greyOutside = true
        assertSame(overlay, core.scopeOverlay())
    }
    @Test
    fun `volume buffers bound whole tubes and reject nonfinite degenerate segments`() {
        val batch = GridLineBatch(capacitySegments = 1, volumetric = true)
        batch.line(0f,0f,0f,0f,0f,0f,0)
        batch.line(0f,0f,0f,Float.NaN,1f,0f,0)
        assertEquals(0,batch.segments)
        batch.line(0f,0f,0f,0f,1f,0f,0x12345678)
        assertEquals(1,batch.segments)
        assertEquals(36*16,batch.vertices.capacity())
        for(i in 0 until 36) {
            assertEquals(0.012f,kotlin.math.abs(batch.vertices.getFloat(i*16)),1e-6f)
            assertEquals(0x12,batch.vertices.get(i*16+12).toInt())
        }
        batch.line(0f,0f,0f,1f,0f,0f,0)
        assertEquals(1L,batch.dropped)
        batch.clear()
        assertEquals(0,batch.segments)
    }
}
