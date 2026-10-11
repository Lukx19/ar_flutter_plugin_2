package com.uhg0.ar_flutter_plugin_2.gridrenderer

import com.uhg0.ar_flutter_plugin_2.visibilitycontract.contractMagic
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Receives overlay line segments in world coordinates with an RGBA8 colour (0xRRGGBBAA). */
internal fun interface GridOverlayLineSink {
    fun line(x0: Float, y0: Float, z0: Float, x1: Float, y1: Float, z1: Float, rgba: Int)
}

/**
 * Data-only overlay of the grid renderer (guided-capture quests and similar):
 * the renderer forwards the overlay's display-channel messages to
 * [onMessage], asks it once per frame for line segments, draws them as one
 * line list after the grid styles, and greys grid surfaces of bricks whose
 * centre is outside [isInScope] while [greyOutside] is true.
 *
 * Shape matches the guided-capture quest layer, so it plugs in with a
 * one-line adapter for its status type and line sink.
 */
internal interface GridLineOverlay {
    /** Magic (first u32, little-endian) of the display-channel messages this overlay consumes. */
    val messageMagic: Int

    /** Channel thread; returns whether the message was accepted. */
    fun onMessage(buffer: ByteBuffer, length: Int): Boolean

    /**
     * Whether the segments are drawn after the depth-tested ones without
     * the depth test, so grid surfaces never hide or cut through them
     * (guidance such as quest rings and reticles on the aimed surface).
     */
    val drawOnTop: Boolean get() = false

    /** Render thread, before [buildLines]: projection focal length in pixels and display density. */
    fun setView(focalPx: Float, pxPerDp: Float) {}

    /** Render thread; returns the number of segments emitted. */
    fun buildLines(camX: Float, camY: Float, camZ: Float, sink: GridOverlayLineSink): Int

    /** Whether surfaces outside the scope are greyed this frame. */
    val greyOutside: Boolean

    /** Render thread; called per visible brick centre while [greyOutside] is true. */
    fun isInScope(x: Float, y: Float, z: Float): Boolean

    companion object {
        /** Magic of the guided-capture quest overlay message (`C3QO`). */
        val QUEST_OVERLAY_MAGIC = contractMagic("C3QO")
    }
}

/**
 * Fixed-capacity line list filled through [GridOverlayLineSink]: per vertex
 * x, y, z as f32 and the colour as RGBA8, ready for one GL_LINES draw.
 * Segments beyond the capacity are dropped and counted; nothing allocates
 * per frame.
 */
internal class GridLineBatch(val capacitySegments: Int = DEFAULT_CAPACITY) : GridOverlayLineSink {
    val vertices: ByteBuffer =
        ByteBuffer.allocateDirect(capacitySegments * 2 * VERTEX_BYTES).order(ByteOrder.nativeOrder())
    var segments = 0
        private set
    var dropped = 0L
        private set

    /** Segments `[0, depthTestedSegments)` are drawn with the depth test, the rest on top. */
    var depthTestedSegments = 0
        private set

    fun clear() {
        segments = 0
        depthTestedSegments = 0
    }

    /** Ends the depth-tested part: later segments are drawn on top. */
    fun endDepthTested() {
        depthTestedSegments = segments
    }

    override fun line(x0: Float, y0: Float, z0: Float, x1: Float, y1: Float, z1: Float, rgba: Int) {
        if (segments == capacitySegments) {
            dropped++
            return
        }
        val o = segments * 2 * VERTEX_BYTES
        put(o, x0, y0, z0, rgba)
        put(o + VERTEX_BYTES, x1, y1, z1, rgba)
        segments++
    }

    private fun put(o: Int, x: Float, y: Float, z: Float, rgba: Int) {
        vertices.putFloat(o, x)
        vertices.putFloat(o + 4, y)
        vertices.putFloat(o + 8, z)
        vertices.put(o + 12, (rgba ushr 24).toByte())
        vertices.put(o + 13, (rgba ushr 16).toByte())
        vertices.put(o + 14, (rgba ushr 8).toByte())
        vertices.put(o + 15, rgba.toByte())
    }

    companion object {
        const val VERTEX_BYTES = 16
        const val DEFAULT_CAPACITY = 8192
    }
}
