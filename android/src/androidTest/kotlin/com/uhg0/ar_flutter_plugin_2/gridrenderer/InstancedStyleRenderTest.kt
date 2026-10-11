package com.uhg0.ar_flutter_plugin_2.gridrenderer

import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES30
import android.opengl.Matrix
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.uhg0.ar_flutter_plugin_2.visibilitycontract.RendererDeltaLayout
import com.uhg0.ar_flutter_plugin_2.visibilitycontract.SurfaceInstanceLayout
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Draws every grid style from a known synthetic delta set in an offscreen
 * GLES 3.0 context and probes pixels at known screen positions (emulator
 * `emulator-5562`, GLES 3.0; functional bounds only, not performance).
 *
 * Scene: camera at the origin looking down -Z, 60 degree vertical field of
 * view, 256 x 256 pixels. An 8 x 8 patch of surfaces faces the camera at
 * z = -2 around the screen centre (brick (0, 0, -3)), its compact voxels sit
 * at the same place, one brick summary and one region summary enclose it.
 * Colour tables are solid per attribute: coverage red, confidence green,
 * completion blue, so a probe tells which attribute drew.
 */
@RunWith(AndroidJUnit4::class)
class InstancedStyleRenderTest {
    private var display: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var context: EGLContext = EGL14.EGL_NO_CONTEXT
    private var surface: EGLSurface = EGL14.EGL_NO_SURFACE
    private lateinit var target: GlStagedBufferTarget
    private lateinit var core: GridRendererCore
    private lateinit var draws: StyleDrawSet
    private val writer = RendererDeltaWriter()
    private val view = GridFrameView()
    private val pixel = ByteBuffer.allocateDirect(4).order(ByteOrder.nativeOrder())
    private val brickKey = GridBrickKey.pack(-1, -1, -3)

    @Before
    fun setUp() {
        display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        val version = IntArray(2)
        assertTrue(EGL14.eglInitialize(display, version, 0, version, 1))
        val configs = arrayOfNulls<EGLConfig>(1)
        val count = IntArray(1)
        assertTrue(
            EGL14.eglChooseConfig(
                display,
                intArrayOf(
                    EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
                    EGL14.EGL_DEPTH_SIZE, 16, EGL14.EGL_RENDERABLE_TYPE, 0x40, EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
                    EGL14.EGL_NONE,
                ),
                0, configs, 0, 1, count, 0,
            ) && count[0] == 1,
        )
        context = EGL14.eglCreateContext(
            display, configs[0], EGL14.EGL_NO_CONTEXT, intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE), 0,
        )
        surface = EGL14.eglCreatePbufferSurface(
            display, configs[0], intArrayOf(EGL14.EGL_WIDTH, SIZE, EGL14.EGL_HEIGHT, SIZE, EGL14.EGL_NONE), 0,
        )
        assertTrue(EGL14.eglMakeCurrent(display, surface, surface, context))
        target = GlStagedBufferTarget()
        target.create()
        core = GridRendererCore(target)
        draws = StyleDrawSet(core.store, target)
        draws.create()
        val projection = FloatArray(16)
        Matrix.perspectiveM(projection, 0, 60f, 1f, 0.05f, 100f)
        System.arraycopy(projection, 0, view.viewProjection, 0, 16) // view matrix is identity
        view.widthPx = SIZE
        view.heightPx = SIZE
        view.focalPx = projection[5] * SIZE * 0.5f
        sendTables()
        sendScene()
    }

    @After
    fun tearDown() {
        draws.destroy()
        target.destroy()
        EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
        EGL14.eglDestroySurface(display, surface)
        EGL14.eglDestroyContext(display, context)
    }

    private fun render(): Long {
        val start = SystemClock.elapsedRealtimeNanos()
        target.beginFrame()
        core.frame()
        draws.syncDisplayResources(core.display)
        GLES30.glViewport(0, 0, SIZE, SIZE)
        GLES30.glClearColor(0f, 0f, 0f, 0f)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT or GLES30.GL_DEPTH_BUFFER_BIT)
        draws.draw(core.mode, view)
        target.endFrame()
        GLES30.glFinish()
        assertEquals(GLES30.GL_NO_ERROR, GLES30.glGetError())
        return (SystemClock.elapsedRealtimeNanos() - start) / 1_000_000
    }

    /** RGBA of the pixel at window (x, y) with y measured from the top. */
    private fun probe(x: Int, y: Int): Int {
        pixel.clear()
        GLES30.glReadPixels(x, SIZE - 1 - y, 1, 1, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, pixel)
        return ((pixel.get(0).toInt() and 0xff) shl 24) or ((pixel.get(1).toInt() and 0xff) shl 16) or
            ((pixel.get(2).toInt() and 0xff) shl 8) or (pixel.get(3).toInt() and 0xff)
    }

    private fun drawnPixels(): Int {
        val all = ByteBuffer.allocateDirect(SIZE * SIZE * 4)
        GLES30.glReadPixels(0, 0, SIZE, SIZE, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, all)
        var n = 0
        for (i in 0 until SIZE * SIZE) if (all.get(i * 4 + 3).toInt() != 0) n++
        return n
    }

    private fun mode(base: Int, overlays: Int = 0, colour: Int = GridDisplayCodes.COLOUR_COVERAGE, size: Int = GridDisplayCodes.SIZE_FIXED) {
        writer.displayMode(base, overlays, colour, size, pointSizePx = 16f)
        assertEquals(DisplayIntake.MODE, core.onDisplayMessage(writer.buffer, 0, writer.length))
    }

    private fun sendTables() {
        val colours = intArrayOf(0xFF0000FF.toInt(), 0x00FF00FF, 0xFFFFFFFF.toInt(), 0xFFFF00FF.toInt(), 0x0000FFFF)
        for (attribute in 0 until GridDisplayCodes.COLOUR_ATTRIBUTES) {
            writer.colourTable(attribute, colours[attribute])
            assertEquals(DisplayIntake.COLOUR_TABLE, core.onDisplayMessage(writer.buffer, 0, writer.length))
        }
    }

    private fun offer() {
        assertEquals(DeltaIntake.ACCEPTED, core.onRenderMessage(writer.buffer, 0, writer.length))
    }

    private fun sendScene() {
        writer.begin(RendererDeltaLayout.KIND_RESET, 0, 0, 4096, 0)
        offer()
        writer.begin(RendererDeltaLayout.KIND_SURFACE_SLICE, 0, 64, 4096, brickKey)
        for (i in 0 until 64) {
            val x = (i % 8 - 3.5f) * 0.03f
            val y = (i / 8 - 3.5f) * 0.03f
            writer.surface(
                i, x, y, -2f, 0f, 0f, 1f, size = 0.08f, coverage = 255, confidence = 255,
                flags = SurfaceInstanceLayout.FLAG_PUBLISHED or (if (i == 27) SurfaceInstanceLayout.FLAG_SELECTED else 0),
                binQuality = 0,
            )
        }
        offer()
        writer.begin(RendererDeltaLayout.KIND_COMPACT_VOXELS, 0, 16, 1024, brickKey)
        writer.voxel(0, -1, -1, -20, 200)
        writer.voxel(1, 0, -1, -20, 200)
        writer.voxel(2, -1, 0, -20, 200)
        writer.voxel(3, 0, 0, -20, 200)
        for (i in 4 until 16) writer.voxel(i, 0, 0, 0, -1)
        offer()
        writer.begin(RendererDeltaLayout.KIND_BRICK_SUMMARIES, 0, 1, 64, 0)
        writer.brickSummary(0, -1, -1, -3, 64, 0, 200, 0)
        offer()
        writer.begin(RendererDeltaLayout.KIND_REGION_SUMMARIES, 0, 1, 16, 0)
        writer.regionSummary(0, -1, -1, -1, 1, 64, 200, 0)
        offer()
        writer.begin(RendererDeltaLayout.KIND_FRAME_END, 0, 0, 0, 0)
        offer()
    }

    /** Window pixel (x, y from the top) of world point ([x], [y], [z]). */
    private fun pixelOf(x: Float, y: Float, z: Float): IntArray {
        val clip = FloatArray(4)
        Matrix.multiplyMV(clip, 0, view.viewProjection, 0, floatArrayOf(x, y, z, 1f), 0)
        val px = ((clip[0] / clip[3]) * 0.5f + 0.5f) * SIZE
        val py = (0.5f - (clip[1] / clip[3]) * 0.5f) * SIZE
        return intArrayOf(px.toInt(), py.toInt())
    }

    private fun red(rgba: Int) = rgba ushr 24
    private fun green(rgba: Int) = (rgba ushr 16) and 0xff
    private fun alpha(rgba: Int) = rgba and 0xff

    @Test
    fun pointsDrawAtTheirProjectedPosition() {
        mode(GridDisplayCodes.BASE_POINTS)
        render()
        val centre = probe(SIZE / 2, SIZE / 2)
        assertTrue("centre $centre", red(centre) > 60 && green(centre) < 40 && alpha(centre) > 0)
        assertEquals(0, alpha(probe(4, 4)))
    }

    @Test
    fun splatsCoverThePatch() {
        mode(GridDisplayCodes.BASE_SPLATS)
        render()
        val centre = probe(SIZE / 2, SIZE / 2)
        assertTrue("centre $centre", red(centre) > 60 && alpha(centre) > 0)
        // Overlapping coplanar splats leave no hole inside the patch (one
        // splat's transparent rim must not hide its neighbours by depth).
        for (dy in -9..9 step 3) for (dx in -9..9 step 3) {
            val rgba = probe(SIZE / 2 + dx, SIZE / 2 + dy)
            assertTrue("($dx, $dy) $rgba", red(rgba) > 60 && alpha(rgba) > 0)
        }

    }

    @Test
    fun compactVoxelsDrawAndPaddingCollapses() {
        mode(GridDisplayCodes.BASE_VOXELS)
        render()
        // Cubes are 9 cm in 10 cm cells: the screen centre lies in the 1 cm
        // gap between the four voxels, so probe each voxel's centre.
        for (v in arrayOf(intArrayOf(-1, -1), intArrayOf(0, -1), intArrayOf(-1, 0), intArrayOf(0, 0))) {
            val p = pixelOf((v[0] + 0.5f) * 0.1f, (v[1] + 0.5f) * 0.1f, -1.95f)
            val rgba = probe(p[0], p[1])
            assertTrue("voxel ${v.toList()} at ${p.toList()}: $rgba", red(rgba) > 60 && alpha(rgba) > 0)
        }
        // Twelve padding entries are drawn and collapse off screen.
        assertEquals(16L, draws.counters.voxelInstances)
        assertEquals(1, draws.counters.visibleVoxelSlices)
        val cube = (0.09f / 1.905f * view.focalPx).toInt()
        assertTrue("drawn ${drawnPixels()}", drawnPixels() in 4 * (cube - 2) * (cube - 2)..4 * (cube + 3) * (cube + 3))
    }

    @Test
    fun spikeBallsExpandTwentyFourInstancesPerSelectedSurface() {
        mode(GridDisplayCodes.BASE_NONE, GridDisplayCodes.OVERLAY_SPIKE_BALLS)
        render()
        // 64 surfaces x 24 spikes in the selected slice, then the ball sprites.
        assertTrue(draws.counters.instances >= 64L * 24)
        assertTrue(drawnPixels() > 0)
        // The selected surface (index 27) sits 2 px from the screen centre.
        val ball = probe(SIZE / 2, SIZE / 2)
        assertTrue("ball $ball", alpha(ball) > 0)
    }

    @Test
    fun bricksAndRegionsDrawFromSummaries() {
        mode(GridDisplayCodes.BASE_NONE, GridDisplayCodes.OVERLAY_BRICKS)
        render()
        assertTrue(drawnPixels() > 0)
        mode(GridDisplayCodes.BASE_NONE, GridDisplayCodes.OVERLAY_REGIONS)
        render()
        // The region box spans x, y, z in [-6.4, 0]: lower left of the view.
        assertTrue(alpha(probe(SIZE / 4, SIZE * 3 / 4)) > 0)
    }

    @Test
    fun modeSwitchIsVisibleInTheNextFrameWithoutUploads() {
        mode(GridDisplayCodes.BASE_POINTS, colour = GridDisplayCodes.COLOUR_COVERAGE)
        render()
        assertTrue(red(probe(SIZE / 2, SIZE / 2)) > 60)
        val staged = target.stagedBytes
        val growths = target.growths
        val frameBefore = core.frameNumber
        mode(GridDisplayCodes.BASE_POINTS, colour = GridDisplayCodes.COLOUR_CONFIDENCE)
        render()
        assertEquals(frameBefore + 1, core.modeAppliedFrame)
        val centre = probe(SIZE / 2, SIZE / 2)
        assertTrue("centre $centre", green(centre) > 60 && red(centre) < 40)
        assertEquals(staged, target.stagedBytes)
        assertEquals(growths, target.growths)
    }

    @Test
    fun overlayLinesDrawAndOutOfScopeSurfacesGrey() {
        val overlay = object : GridLineOverlay {
            override val messageMagic = GridLineOverlay.QUEST_OVERLAY_MAGIC
            override fun onMessage(buffer: ByteBuffer, length: Int) = true
            override fun buildLines(camX: Float, camY: Float, camZ: Float, sink: GridOverlayLineSink): Int {
                sink.line(-1f, 0.5f, -2f, 1f, 0.5f, -2f, 0x00FF00FF)
                return 1
            }
            override val greyOutside = true
            override fun isInScope(x: Float, y: Float, z: Float) = false
        }
        core.lineOverlays.add(overlay)
        mode(GridDisplayCodes.BASE_POINTS)
        draws.scope = core.scopeOverlay()
        render()
        val batch = GridLineBatch()
        core.buildOverlayLines(0f, 0f, 0f, batch)
        draws.drawLines(batch, view)
        GLES30.glFinish()
        // The surface patch is out of scope: grey, not red.
        val centre = probe(SIZE / 2, SIZE / 2)
        assertTrue("centre $centre", alpha(centre) > 0 && kotlin.math.abs(red(centre) - green(centre)) < 16)
        // The line at y = 0.5 m, z = -2 m projects about 55 px above the centre.
        var lineFound = false
        for (y in SIZE / 2 - 64 until SIZE / 2 - 44) if (green(probe(SIZE / 2, y)) > 100) lineFound = true
        assertTrue(lineFound)
    }

    @Test
    fun hundredThousandSurfacesUploadOverFramesWithoutLongFrames() {
        mode(GridDisplayCodes.BASE_POINTS)
        render()
        val bricks = 100_000 / 256 + 1
        writer.begin(RendererDeltaLayout.KIND_RESET, 0, 0, bricks * 256, 0)
        offer()
        var worst = 0L
        var frames = 0
        var b = 0
        while (b < bricks || core.scheduler.queuedMessages > 0) {
            // Feed about one fusion batch per frame, as the grid isolate does.
            var fed = 0
            while (b < bricks && fed < 6) {
                writer.begin(RendererDeltaLayout.KIND_SURFACE_SLICE, b * 256, 256, bricks * 256, GridBrickKey.pack(b % 32 - 16, b / 32 % 4 - 2, -4 - b / 128))
                for (i in 0 until 256) {
                    writer.surface(i, (b % 32 - 16) * 0.8f + (i % 16) * 0.05f, (b / 32 % 4 - 2) * 0.8f + (i / 16) * 0.05f, -3.2f - b / 128 * 0.8f, 0f, 0f, 1f)
                }
                if (core.onRenderMessage(writer.buffer, 0, writer.length) != DeltaIntake.ACCEPTED) break
                b++
                fed++
            }
            worst = maxOf(worst, render())
            frames++
            assertTrue("upload did not finish", frames < 10_000)
        }
        Log.i("InstancedStyleRenderTest", "100k surfaces: $frames frames, worst frame $worst ms, staged ${target.stagedBytes} B")
        assertEquals(bricks, core.store.surfaceSlices.size)
        assertTrue("worst frame $worst ms", worst < 100)
        assertTrue(core.scheduler.counters.maxFrameBytes <= DeltaUploadScheduler.DEFAULT_FRAME_BUDGET_BYTES)
    }

    companion object {
        private const val SIZE = 256
    }
}
