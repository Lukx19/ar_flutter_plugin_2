package com.uhg0.ar_flutter_plugin_2.gridrenderer

import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES30
import android.opengl.Matrix
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import com.google.ar.core.Frame
import com.google.ar.core.TrackingState
import com.uhg0.ar_flutter_plugin_2.visibilitycontract.VisibilityGridRendererHostSeam
import com.uhg0.ar_flutter_plugin_2.gridrenderer.quest.QuestLineOverlay
import java.nio.ByteBuffer
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** Process-wide handle of the attached grid renderer, used by the channel handlers. */
internal object GridRendererRegistry {
    @Volatile
    var host: VisibilityGridRendererHost? = null
        private set

    fun register(host: VisibilityGridRendererHost) {
        this.host = host
    }

    fun unregister(host: VisibilityGridRendererHost) {
        if (this.host === host) this.host = null
    }
}

/**
 * Instanced grid renderer attached to the SceneView host.
 *
 * Choice recorded for the renderer design: the grid draws with the measured
 * GLES 3.0 instancing proxy, not Filament. Filament would need offline-built
 * materials with per-instance data in sampled textures, which the plugin's
 * prebuilt `.filamat` workflow cannot validate on the host, while the GLES
 * proxy is the path measured on the tablet (all styles smooth, compact voxels
 * at 60 fps). The renderer therefore owns its own EGL context and draws into
 * a transparent [TextureView] stacked above the SceneView texture: a
 * TextureView (not a SurfaceView) keeps Flutter's platform-view composition
 * intact, as the SceneView itself requires. Camera matrices come from the
 * ARCore frame SceneView renders; the grid is not depth-tested against
 * Filament content, which the grid styles do not need.
 *
 * Nothing is created until the first render or display message arrives, so
 * the legacy renderer runs untouched while `CAPTURE3D_GRID_RENDERER=legacy`.
 */
internal class VisibilityGridRendererHost(
    private val frameBudgetBytes: Int = DeltaUploadScheduler.DEFAULT_FRAME_BUDGET_BYTES,
) : VisibilityGridRendererHostSeam {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val target = GlStagedBufferTarget()
    val core = GridRendererCore(target, frameBudgetBytes = frameBudgetBytes)
    private val overlays = CopyOnWriteArrayList<GridOverlayLayer>()
    private val lineBatch = GridLineBatch()
    private val camera = CameraSnapshot()

    @Volatile private var anchor: View? = null
    @Volatile private var enabled = false
    @Volatile private var textureView: TextureView? = null
    @Volatile private var renderThread: GridRenderThread? = null

    /** Guided-capture quests; drawn only after their first overlay message. */
    val questOverlay = QuestLineOverlay().also { core.lineOverlays.addIfAbsent(it) }

    /** [scene] is the SceneView host view; the overlay is added to its parent above it. [engine] is unused by the GLES path. */
    override fun attach(engine: Any, scene: Any) {
        anchor = scene as? View
        GridRendererRegistry.register(this)
        if (enabled) mainHandler.post(::ensureView)
    }

    /** Removes the overlay view and releases every GL object. */
    fun detach() {
        GridRendererRegistry.unregister(this)
        anchor = null
        mainHandler.post(::removeView)
    }

    fun addOverlayLayer(layer: GridOverlayLayer) {
        overlays.addIfAbsent(layer)
        renderThread?.requestRender()
    }

    /** Adds a data-only overlay: its display-channel messages are routed to it and its lines drawn per frame. */
    fun addLineOverlay(overlay: GridLineOverlay) {
        core.lineOverlays.addIfAbsent(overlay)
        renderThread?.requestRender()
    }

    fun removeLineOverlay(overlay: GridLineOverlay) {
        core.lineOverlays.remove(overlay)
        renderThread?.requestRender()
    }

    fun removeOverlayLayer(layer: GridOverlayLayer) {
        if (overlays.remove(layer)) renderThread?.detachLayer(layer)
    }

    /** One call per ARCore frame from the SceneView frame callback; cheap when disabled. */
    fun onArFrame(frame: Frame) {
        if (!enabled) return
        camera.update(frame)
        renderThread?.requestRender()
    }

    fun onRenderMessage(message: ByteBuffer, offset: Int, length: Int): DeltaIntake {
        enable()
        val result = core.onRenderMessage(message, offset, length)
        renderThread?.requestRender()
        return result
    }

    fun onDisplayMessage(message: ByteBuffer, offset: Int, length: Int): DisplayIntake {
        enable()
        val result = core.onDisplayMessage(message, offset, length)
        renderThread?.requestRender()
        return result
    }

    private fun enable() {
        if (enabled) return
        enabled = true
        mainHandler.post(::ensureView)
    }

    private fun ensureView() {
        val host = anchor ?: return
        if (textureView != null) return
        val parent = host.parent as? ViewGroup
        if (parent == null) {
            host.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(v: View) {
                    v.removeOnAttachStateChangeListener(this)
                    ensureView()
                }

                override fun onViewDetachedFromWindow(v: View) = Unit
            })
            return
        }
        val view = TextureView(host.context).apply {
            isOpaque = false
            isClickable = false
            isFocusable = false
            surfaceTextureListener = SurfaceListener()
        }
        parent.addView(view, parent.indexOfChild(host) + 1, ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
        ))
        textureView = view
    }

    private fun removeView() {
        val view = textureView ?: return
        textureView = null
        (view.parent as? ViewGroup)?.removeView(view)
        stopThread()
    }

    private fun stopThread() {
        renderThread?.let {
            renderThread = null
            it.quitAndJoin()
        }
    }

    private inner class SurfaceListener : TextureView.SurfaceTextureListener {
        override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
            stopThread()
            renderThread = GridRenderThread(surface, width, height).also { it.start() }
        }

        override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {
            renderThread?.resize(width, height)
        }

        override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
            stopThread()
            return true
        }

        override fun onSurfaceTextureUpdated(surface: SurfaceTexture) = Unit
    }

    /** Latest camera matrices, written by the frame callback and copied by the render thread. */
    private class CameraSnapshot {
        private val lock = Any()
        private val view = FloatArray(16)
        private val projection = FloatArray(16)
        private val position = FloatArray(3)
        private val pose = FloatArray(3)
        private var tracking = false
        private var timestampNs = 0L
        var version = 0L
            private set

        fun update(frame: Frame) {
            val cam = frame.camera
            synchronized(lock) {
                tracking = cam.trackingState == TrackingState.TRACKING
                if (tracking) {
                    cam.getViewMatrix(view, 0)
                    cam.getProjectionMatrix(projection, 0, NEAR_METRES, FAR_METRES)
                    cam.pose.getTranslation(pose, 0)
                    position[0] = pose[0]; position[1] = pose[1]; position[2] = pose[2]
                }
                timestampNs = frame.timestamp
                version++
            }
        }

        /** Copies into [out]; returns false while not tracking. */
        fun copyTo(out: GridOverlayFrame): Boolean = synchronized(lock) {
            if (!tracking) return false
            System.arraycopy(view, 0, out.view, 0, 16)
            System.arraycopy(projection, 0, out.projection, 0, 16)
            System.arraycopy(position, 0, out.cameraPosition, 0, 3)
            out.arFrameTimestampNs = timestampNs
            true
        }
    }

    /** Render thread with its own EGL context on the overlay's SurfaceTexture. */
    private inner class GridRenderThread(
        private val surfaceTexture: SurfaceTexture,
        @Volatile private var width: Int,
        @Volatile private var height: Int,
    ) : Thread("GridRenderer") {
        private val lock = ReentrantLock()
        private val wake = lock.newCondition()
        private var renderRequested = true
        private var quit = false
        private val pendingDetach = CopyOnWriteArrayList<GridOverlayLayer>()
        private val attached = HashSet<GridOverlayLayer>()
        private val frameView = GridFrameView()
        private val overlayFrame = GridOverlayFrame()
        private var display: EGLDisplay = EGL14.EGL_NO_DISPLAY
        private var context: EGLContext = EGL14.EGL_NO_CONTEXT
        private var surface: EGLSurface = EGL14.EGL_NO_SURFACE
        private lateinit var draws: StyleDrawSet
        private val overlayContext = object : GridOverlayContext {
            override fun requestRender() = this@GridRenderThread.requestRender()
        }

        fun requestRender() = lock.withLock {
            renderRequested = true
            wake.signalAll()
        }

        fun resize(w: Int, h: Int) {
            width = w
            height = h
            requestRender()
        }

        fun detachLayer(layer: GridOverlayLayer) {
            pendingDetach.add(layer)
            requestRender()
        }

        fun quitAndJoin() {
            lock.withLock {
                quit = true
                wake.signalAll()
            }
            join(QUIT_TIMEOUT_MS)
        }

        override fun run() {
            try {
                if (!initEgl()) return
                target.create()
                draws = StyleDrawSet(core.store, target)
                draws.create()
                loop()
            } catch (t: Throwable) {
                Log.e(TAG, "grid renderer stopped: ${t.message}", t)
            } finally {
                teardown()
            }
        }

        private fun loop() {
            while (true) {
                lock.withLock {
                    while (!renderRequested && !quit) wake.await()
                    if (quit) return
                    renderRequested = false
                }
                renderFrame()
            }
        }

        private fun renderFrame() {
            for (layer in pendingDetach) {
                if (attached.remove(layer)) layer.detach()
            }
            pendingDetach.clear()
            for (layer in overlays) {
                if (attached.add(layer)) layer.attach(overlayContext)
            }
            target.beginFrame()
            core.frame()
            draws.syncDisplayResources(core.display)
            GLES30.glViewport(0, 0, width, height)
            GLES30.glClearColor(0f, 0f, 0f, 0f)
            GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT or GLES30.GL_DEPTH_BUFFER_BIT)
            if (camera.copyTo(overlayFrame)) {
                Matrix.multiplyMM(overlayFrame.viewProjection, 0, overlayFrame.projection, 0, overlayFrame.view, 0)
                System.arraycopy(overlayFrame.viewProjection, 0, frameView.viewProjection, 0, 16)
                System.arraycopy(overlayFrame.cameraPosition, 0, frameView.cameraPosition, 0, 3)
                frameView.widthPx = width
                frameView.heightPx = height
                frameView.focalPx = overlayFrame.projection[5] * height * 0.5f
                draws.scope = core.scopeOverlay()
                draws.draw(core.mode, frameView)
                val cam = overlayFrame.cameraPosition
                if (core.buildOverlayLines(cam[0], cam[1], cam[2], lineBatch) > 0) draws.drawLines(lineBatch, frameView)
                overlayFrame.widthPx = width
                overlayFrame.heightPx = height
                overlayFrame.frameNumber = core.frameNumber
                overlayFrame.overlayBits = core.mode.overlayBits
                for (layer in attached) {
                    layer.draw(overlayFrame)
                    GLES30.glDisable(GLES30.GL_BLEND)
                    GLES30.glDepthMask(true)
                }
            }
            target.endFrame()
            EGL14.eglSwapBuffers(display, surface)
            // Keep draining a backlog even when the camera is still.
            if (core.scheduler.queuedMessages > 0) lock.withLock { renderRequested = true }
        }

        private fun initEgl(): Boolean {
            display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
            val version = IntArray(2)
            if (!EGL14.eglInitialize(display, version, 0, version, 1)) return false
            val attributes = intArrayOf(
                EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
                EGL14.EGL_DEPTH_SIZE, 16, EGL14.EGL_RENDERABLE_TYPE, EGL_OPENGL_ES3_BIT, EGL14.EGL_NONE,
            )
            val configs = arrayOfNulls<EGLConfig>(1)
            val count = IntArray(1)
            if (!EGL14.eglChooseConfig(display, attributes, 0, configs, 0, 1, count, 0) || count[0] == 0) return false
            val config = configs[0] ?: return false
            context = EGL14.eglCreateContext(
                display, config, EGL14.EGL_NO_CONTEXT, intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE), 0,
            )
            if (context == EGL14.EGL_NO_CONTEXT) return false
            surface = EGL14.eglCreateWindowSurface(display, config, surfaceTexture, intArrayOf(EGL14.EGL_NONE), 0)
            if (surface == EGL14.EGL_NO_SURFACE) return false
            return EGL14.eglMakeCurrent(display, surface, surface, context)
        }

        private fun teardown() {
            if (context != EGL14.EGL_NO_CONTEXT) {
                runCatching {
                    for (layer in attached) layer.detach()
                    attached.clear()
                    if (::draws.isInitialized) draws.destroy()
                    target.destroy()
                }
                EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
                if (surface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, surface)
                EGL14.eglDestroyContext(display, context)
            }
            // The display is shared with Filament, so it is never terminated here.
            // A new context starts with empty GPU buffers: the grid must resend everything.
            core.store.forgetGpuBuffers()
            core.scheduler.requireReset()
        }
    }

    companion object {
        private const val TAG = "GridRenderer"
        private const val NEAR_METRES = 0.05f
        private const val FAR_METRES = 100f
        private const val QUIT_TIMEOUT_MS = 2000L
        private const val EGL_OPENGL_ES3_BIT = 0x40
    }
}
