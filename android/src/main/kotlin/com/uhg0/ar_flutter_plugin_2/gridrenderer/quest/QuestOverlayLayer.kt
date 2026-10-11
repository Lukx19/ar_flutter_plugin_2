package com.uhg0.ar_flutter_plugin_2.gridrenderer.quest

import java.nio.ByteBuffer

/**
 * Overlay hook of the grid renderer as guided capture needs it. Defined here
 * until the renderer host exposes its own overlay layer; at integration the
 * renderer host adapts this interface: it forwards overlay messages from
 * their channel to [onMessage], calls [buildLines] once per rendered frame
 * with the camera position and draws the segments as one line-list
 * renderable, and greys surfaces for which [isInScope] is false while
 * [greyOutside] is true.
 */
internal interface GridOverlayLayer {
    /** Called on the channel thread with one overlay message. */
    fun onMessage(buffer: ByteBuffer, length: Int): QuestOverlayStatus

    /** Called on the render thread; returns the number of segments emitted. */
    fun buildLines(camX: Float, camY: Float, camZ: Float, sink: OverlayLineSink): Int

    /** Whether the renderer should grey surfaces outside the scan zone. */
    val greyOutside: Boolean

    fun isInScope(x: Float, y: Float, z: Float): Boolean
}

/**
 * Quest overlay layer: double-buffered decoded frames handed from the
 * channel thread to the render thread under one short lock (a rejected
 * message never replaces the shown frame); no allocation after
 * construction.
 */
internal class QuestOverlayLayer : GridOverlayLayer {
    private val frames = arrayOf(QuestOverlayFrame(), QuestOverlayFrame())
    private val geometry = QuestOverlayGeometry()

    @Volatile private var front = 0
    private var hasFrame = false
    private val lock = Any()

    /** Messages rejected so far, for diagnostics. */
    var rejectedMessages = 0
        private set

    override fun onMessage(buffer: ByteBuffer, length: Int): QuestOverlayStatus = synchronized(lock) {
        val back = 1 - front
        val status = frames[back].decode(buffer, length)
        if (status == QuestOverlayStatus.OK) {
            front = back
            hasFrame = true
        } else {
            rejectedMessages++
        }
        status
    }

    override fun buildLines(camX: Float, camY: Float, camZ: Float, sink: OverlayLineSink): Int {
        if (!hasFrame) return 0
        return synchronized(lock) { geometry.build(frames[front], camX, camY, camZ, sink) }
    }

    override val greyOutside: Boolean
        get() = hasFrame && frames[front].hasLayer(QuestOverlayLayout.LAYER_GREY_OUTSIDE)

    override fun isInScope(x: Float, y: Float, z: Float): Boolean =
        !hasFrame || frames[front].isInScope(x, y, z)
}
