package com.uhg0.ar_flutter_plugin_2.gridrenderer

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CopyOnWriteArrayList

/**
 * GL-free core of the grid renderer: delta intake and upload scheduling into
 * the instance buffers, plus display state. The GLES host owns one per GL
 * context and calls [frame] once per rendered frame; JVM tests drive it with
 * a byte-array [GpuBufferTarget].
 */
internal class GridRendererCore(
    val target: GpuBufferTarget,
    val scheduler: DeltaUploadScheduler = DeltaUploadScheduler(),
    val display: DisplayModeHandler = DisplayModeHandler(),
    var frameBudgetBytes: Int = DeltaUploadScheduler.DEFAULT_FRAME_BUDGET_BYTES,
) {
    val store = InstanceBufferStore(target)

    /** Render-thread copy of the display mode. */
    val mode = GridDisplayState()

    /** Rendered frames. */
    var frameNumber = 0L
        private set

    /** Frame in which the last display mode change took effect. */
    var modeAppliedFrame = -1L
        private set

    fun onRenderMessage(src: ByteBuffer, offset: Int, length: Int): DeltaIntake =
        scheduler.offer(src, offset, length)

    /** Data-only overlays (guided capture); their display-channel messages are routed by magic. */
    val lineOverlays = CopyOnWriteArrayList<GridLineOverlay>()

    fun onDisplayMessage(src: ByteBuffer, offset: Int, length: Int): DisplayIntake {
        if (length >= 4) {
            val magic = src.duplicate().order(ByteOrder.LITTLE_ENDIAN).getInt(offset)
            for (overlay in lineOverlays) {
                if (overlay.messageMagic != magic) continue
                val view = src.duplicate().order(ByteOrder.LITTLE_ENDIAN)
                view.limit(offset + length).position(offset)
                val slice = view.slice().order(ByteOrder.LITTLE_ENDIAN)
                return if (overlay.onMessage(slice, length)) DisplayIntake.OVERLAY else DisplayIntake.REJECTED
            }
        }
        return display.onMessage(src, offset, length)
    }

    /**
     * Render thread: collects this frame's overlay segments into [batch],
     * depth-tested overlays first, then [GridLineOverlay.drawOnTop] ones.
     * [focalPx] and [pxPerDp] reach overlays that size elements on screen.
     */
    fun buildOverlayLines(
        camX: Float,
        camY: Float,
        camZ: Float,
        batch: GridLineBatch,
        focalPx: Float = 0f,
        pxPerDp: Float = 1f,
    ): Int {
        batch.clear()
        for (overlay in lineOverlays) {
            if (overlay.drawOnTop) continue
            overlay.setView(focalPx, pxPerDp)
            overlay.buildLines(camX, camY, camZ, batch)
        }
        batch.endDepthTested()
        for (overlay in lineOverlays) {
            if (!overlay.drawOnTop) continue
            overlay.setView(focalPx, pxPerDp)
            overlay.buildLines(camX, camY, camZ, batch)
        }
        return batch.segments
    }

    /** The overlay that greys out-of-scope surfaces this frame, or null. */
    fun scopeOverlay(): GridLineOverlay? {
        for (overlay in lineOverlays) if (overlay.greyOutside) return overlay
        return null
    }

    /**
     * Starts one render-thread frame: uploads queued deltas within the frame
     * budget and takes the newest display mode. Returns uploaded bytes.
     */
    fun frame(): Int {
        frameNumber++
        if (display.snapshotMode(mode, mode.modeVersion)) modeAppliedFrame = frameNumber
        return scheduler.drainFrame(store, target, frameBudgetBytes)
    }
}
