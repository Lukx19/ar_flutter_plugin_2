package com.uhg0.ar_flutter_plugin_2.gridrenderer.quest

import com.uhg0.ar_flutter_plugin_2.gridrenderer.GridLineOverlay
import com.uhg0.ar_flutter_plugin_2.gridrenderer.GridOverlayLineSink
import java.nio.ByteBuffer

/**
 * Plugs the guided-capture [QuestOverlayLayer] into the grid renderer's
 * data-only line overlay hook: quest overlay messages (`C3QO`) arrive on the
 * display channel, segments are drawn after the grid styles and surfaces
 * outside the scan zone are greyed. One reused sink bridge; nothing
 * allocates per frame.
 */
internal class QuestLineOverlay(
    val layer: QuestOverlayLayer = QuestOverlayLayer(),
) : GridLineOverlay {
    private var target: GridOverlayLineSink? = null
    private val bridge = OverlayLineSink { x0, y0, z0, x1, y1, z1, rgba ->
        target?.line(x0, y0, z0, x1, y1, z1, rgba)
    }

    override val messageMagic: Int get() = GridLineOverlay.QUEST_OVERLAY_MAGIC

    override fun onMessage(buffer: ByteBuffer, length: Int): Boolean =
        layer.onMessage(buffer, length) == QuestOverlayStatus.OK

    override fun buildLines(camX: Float, camY: Float, camZ: Float, sink: GridOverlayLineSink): Int {
        target = sink
        try {
            return layer.buildLines(camX, camY, camZ, bridge)
        } finally {
            target = null
        }
    }

    override val greyOutside: Boolean get() = layer.greyOutside

    override fun isInScope(x: Float, y: Float, z: Float): Boolean = layer.isInScope(x, y, z)
}
