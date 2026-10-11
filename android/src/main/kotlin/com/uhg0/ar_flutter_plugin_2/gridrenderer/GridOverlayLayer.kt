package com.uhg0.ar_flutter_plugin_2.gridrenderer

/**
 * Per-frame view data handed to overlay layers on the render thread. Arrays
 * are owned by the renderer and valid only during [GridOverlayLayer.draw].
 */
internal class GridOverlayFrame {
    /** Column-major view, projection and their product (world to clip). */
    val view = FloatArray(16)
    val projection = FloatArray(16)
    val viewProjection = FloatArray(16)

    /** Camera position in the AR world frame. */
    val cameraPosition = FloatArray(3)
    var widthPx = 0
    var heightPx = 0

    /** Renderer frame counter and the AR frame timestamp it shows. */
    var frameNumber = 0L
    var arFrameTimestampNs = 0L

    /** Overlay bits of the current display mode (`GridDisplayCodes.OVERLAY_*`). */
    var overlayBits = 0

    /** Projects world ([x], [y], [z]) to pixels in [out] (x, y, depth); false when behind the camera. */
    fun project(x: Float, y: Float, z: Float, out: FloatArray): Boolean {
        val m = viewProjection
        val cx = m[0] * x + m[4] * y + m[8] * z + m[12]
        val cy = m[1] * x + m[5] * y + m[9] * z + m[13]
        val cz = m[2] * x + m[6] * y + m[10] * z + m[14]
        val cw = m[3] * x + m[7] * y + m[11] * z + m[15]
        if (cw <= 1e-6f) return false
        out[0] = (cx / cw * 0.5f + 0.5f) * widthPx
        out[1] = (0.5f - cy / cw * 0.5f) * heightPx
        out[2] = cz / cw
        return true
    }
}

/** Services the renderer offers to overlay layers. */
internal interface GridOverlayContext {
    /** Asks for another frame even without a new AR frame (animations). */
    fun requestRender()
}

/**
 * Hook for overlays drawn into the grid renderer's GLES 3.0 context after the
 * grid styles, for example guided-capture quests and marker outlines. Every
 * method runs on the render thread with the context current. A layer owns the
 * GL objects it creates in [attach] and deletes them in [detach]; it must
 * restore GL state it changes except blend and depth-mask state, which the
 * renderer resets per frame.
 */
internal interface GridOverlayLayer {
    /** Called once after the context exists (and again after a context loss). */
    fun attach(context: GridOverlayContext)

    /** Draws one frame. */
    fun draw(frame: GridOverlayFrame)

    /** Called before the context is destroyed or the layer is removed. */
    fun detach()
}
