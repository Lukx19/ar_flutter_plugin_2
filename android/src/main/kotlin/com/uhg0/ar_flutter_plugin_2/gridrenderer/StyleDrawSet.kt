package com.uhg0.ar_flutter_plugin_2.gridrenderer

import android.opengl.GLES30
import com.uhg0.ar_flutter_plugin_2.visibilitycontract.ColourTableLayout
import com.uhg0.ar_flutter_plugin_2.visibilitycontract.SurfaceInstanceLayout
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** View inputs of one frame, filled by the host from the AR camera. */
internal class GridFrameView {
    val viewProjection = FloatArray(16)
    val cameraPosition = FloatArray(3)
    var widthPx = 1
    var heightPx = 1

    /** Projection focal length in pixels (`P[1][1] * height / 2`). */
    var focalPx = 1f
}

/** Draw statistics of the last frame. */
internal class StyleDrawCounters {
    var drawCalls = 0
    var instances = 0L
    var visibleSurfaceSlices = 0
    var visibleVoxelSlices = 0

    /** Voxel instances drawn (padding entries included; they collapse in the shader). */
    var voxelInstances = 0L
}

/**
 * The grid styles drawn from the persistent instance buffers with GLES 3.0
 * instancing: points (fixed or sized, optional sphere shading), splats,
 * directional spike balls (instance = surface x 24 + bin, selected surfaces
 * only), compact occupancy voxels, brick boxes, region boxes and planner
 * targets. A display mode only selects programs, uniforms and draw calls;
 * nothing here writes the instance buffers.
 *
 * Culling and runs follow the measured prototype: brick slices are culled
 * against the frustum by their bounding sphere, sorted by buffer offset and
 * merged when exactly adjacent; GLES 3.0 has no base instance, so instanced
 * runs re-point the attributes at their first record.
 */
internal class StyleDrawSet(
    private val store: InstanceBufferStore,
    private val buffers: GlStagedBufferTarget,
) {
    private lateinit var points: Program
    private lateinit var splat: Program
    private val focusUniform = FloatArray(64 * 4)
    private var focusCount = 0
    private var focusVersion = -1L
    private lateinit var spike: Program
    private lateinit var voxel: Program
    private lateinit var boxWire: Program
    private lateinit var boxFill: Program
    private lateinit var targets: Program
    private lateinit var lines: Program
    private val lineBuffer = IntArray(1)
    private val vao = IntArray(VAO_COUNT)
    private val texture = IntArray(1)
    private val tableBytes: ByteBuffer =
        ByteBuffer.allocateDirect(GridDisplayCodes.COLOUR_ATTRIBUTES * ColourTableLayout.ENTRIES * 4)
            .order(ByteOrder.nativeOrder())
    private var tablesVersion = 0L
    private val targetFloats = FloatArray(PlannerTargetMessageLayout.MAX_TARGETS * PlannerTargetMessageLayout.FLOATS_PER_TARGET)
    private val targetUniform = FloatArray(PlannerTargetMessageLayout.MAX_TARGETS * 8)
    private var targetCount = 0
    private var targetsVersion = -1L
    private val planes = FloatArray(24)

    // Visible runs (start record, end record) per slice table, reused per frame.
    private var runKeys = LongArray(1024)
    private var surfaceRunStart = IntArray(1024)
    private var surfaceRunEnd = IntArray(1024)
    private var surfaceRunGrey = BooleanArray(1024)
    private var surfaceRunCount = 0
    private var voxelRunStart = IntArray(1024)
    private var voxelRunEnd = IntArray(1024)
    private var voxelRunGrey = BooleanArray(1024)
    private var voxelRunCount = 0

    /**
     * Overlay whose scope greys grid content this frame (bricks whose centre
     * is outside it draw desaturated), or null. Set by the host per frame.
     */
    var scope: GridLineOverlay? = null
    private var spikeSlices = IntArray(256)
    private var spikeSliceCount = 0

    val counters = StyleDrawCounters()

    /** Times a run table had to grow (zero in steady state once warm). */
    var runGrowths = 0
        private set

    fun create() {
        points = Program(GridRendererShaders.POINTS_VS, GridRendererShaders.POINTS_FS)
        splat = Program(GridRendererShaders.SPLAT_VS, GridRendererShaders.SPLAT_FS)
        spike = Program(GridRendererShaders.SPIKE_VS, GridRendererShaders.FLAT_FS)
        voxel = Program(GridRendererShaders.VOXEL_VS, GridRendererShaders.FLAT_FS)
        boxWire = Program(GridRendererShaders.BOX_WIRE_VS, GridRendererShaders.BOX_FS)
        boxFill = Program(GridRendererShaders.BOX_FILL_VS, GridRendererShaders.BOX_FS)
        targets = Program(GridRendererShaders.TARGET_VS, GridRendererShaders.FLAT_FS)
        lines = Program(GridRendererShaders.LINE_VS, GridRendererShaders.LINE_FS)
        GLES30.glGenBuffers(1, lineBuffer, 0)
        GLES30.glUseProgram(spike.id)
        GLES30.glUniform3fv(GLES30.glGetUniformLocation(spike.id, "u_binDirs"), 24, BIN_DIRECTIONS, 0)
        GLES30.glUseProgram(0)
        GLES30.glGenVertexArrays(VAO_COUNT, vao, 0)
        GLES30.glGenTextures(1, texture, 0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texture[0])
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_NEAREST)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_NEAREST)
        GLES30.glTexImage2D(
            GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA8, ColourTableLayout.ENTRIES, GridDisplayCodes.COLOUR_ATTRIBUTES,
            0, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, null,
        )
        tablesVersion = -1L
    }

    fun destroy() {
        for (p in arrayOf(points, splat, spike, voxel, boxWire, boxFill, targets, lines)) GLES30.glDeleteProgram(p.id)
        GLES30.glDeleteBuffers(1, lineBuffer, 0)
        GLES30.glDeleteVertexArrays(VAO_COUNT, vao, 0)
        GLES30.glDeleteTextures(1, texture, 0)
    }

    /** Uploads colour tables and planner targets when the display handler has newer ones. */
    fun syncDisplayResources(display: DisplayModeHandler) {
        val focusKnown = display.currentFocusVersion
        if (focusKnown != focusVersion) {
            focusCount = display.snapshotFocus(focusUniform, focusVersion).coerceAtLeast(0)
            focusVersion = focusKnown
        }
        val version = display.snapshotTables(tableBytes, tablesVersion)
        if (version >= 0) {
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texture[0])
            GLES30.glTexSubImage2D(
                GLES30.GL_TEXTURE_2D, 0, 0, 0, ColourTableLayout.ENTRIES, GridDisplayCodes.COLOUR_ATTRIBUTES,
                GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, tableBytes,
            )
            tablesVersion = version
        }
        val known = display.currentTargetsVersion
        if (known != targetsVersion) {
            val count = display.snapshotTargets(targetFloats, targetsVersion)
            if (count >= 0) {
                targetCount = count
                for (t in 0 until count) {
                    val s = t * PlannerTargetMessageLayout.FLOATS_PER_TARGET
                    val d = t * 8
                    targetUniform[d] = targetFloats[s]
                    targetUniform[d + 1] = targetFloats[s + 1]
                    targetUniform[d + 2] = targetFloats[s + 2]
                    targetUniform[d + 3] = targetFloats[s + 6]
                    targetUniform[d + 4] = targetFloats[s + 3]
                    targetUniform[d + 5] = targetFloats[s + 4]
                    targetUniform[d + 6] = targetFloats[s + 5]
                    targetUniform[d + 7] = 0f
                }
            }
            targetsVersion = known
        }
    }

    /** Draws the grid styles of [mode]. The caller cleared and bound the framebuffer. */
    fun draw(mode: GridDisplayState, view: GridFrameView) {
        counters.drawCalls = 0
        counters.instances = 0
        counters.voxelInstances = 0
        extractPlanes(view.viewProjection)
        cullSlices(store.surfaceSlices, isVoxel = false)
        cullSlices(store.voxelSlices, isVoxel = true)
        GLES30.glEnable(GLES30.GL_DEPTH_TEST)
        GLES30.glDisable(GLES30.GL_CULL_FACE)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texture[0])
        when (mode.baseLayer) {
            GridDisplayCodes.BASE_POINTS -> drawPoints(mode, view, selectedOnly = false, fixedPx = mode.pointSizePx, sphere = true)
            GridDisplayCodes.BASE_SPLATS -> drawSplats(mode, view)
            GridDisplayCodes.BASE_VOXELS -> drawVoxels(mode, view)
        }
        forEachLevelOfDetailPass(mode) { buffer, residency, alpha ->
            drawBoxes(view, buffer, wire = false, residencyFilter = residency, alpha = alpha)
        }
        if (mode.has(GridDisplayCodes.OVERLAY_SPIKE_BALLS)) drawSpikes(mode, view)
        if (mode.has(GridDisplayCodes.OVERLAY_BRICKS)) {
            drawBoxes(view, GridBuffer.BRICKS, wire = true, residencyFilter = -1, alpha = 1f)
        }
        if (mode.has(GridDisplayCodes.OVERLAY_REGIONS)) {
            drawBoxes(view, GridBuffer.REGIONS, wire = false, residencyFilter = -1, alpha = 0.15f)
        }
        if (mode.has(GridDisplayCodes.OVERLAY_TARGETS)) drawTargets(view)
        GLES30.glBindVertexArray(0)
        GLES30.glUseProgram(0)
        GLES30.glDisable(GLES30.GL_BLEND)
        GLES30.glDepthMask(true)
    }

    // ------------------------------------------------------------ styles

    private fun use(p: Program, mode: GridDisplayState, view: GridFrameView) {
        GLES30.glUseProgram(p.id)
        GLES30.glUniformMatrix4fv(p.viewProj, 1, false, view.viewProjection, 0)
        GLES30.glUniform3f(p.camPos, view.cameraPosition[0], view.cameraPosition[1], view.cameraPosition[2])
        GLES30.glUniform1f(p.focalPx, view.focalPx)
        GLES30.glUniform1i(p.colorAttr, mode.colourAttribute)
        GLES30.glUniform1i(p.sizeRule, mode.sizeRule)
        GLES30.glUniform1i(p.colors, 0)
        GLES30.glUniform1i(p.selectedOnly, 0)
        GLES30.glUniform1i(p.grey, 0)
        GLES30.glUniform1i(p.minQuality, mode.minQuality)
        val activeFocus = if (mode.has(GridDisplayCodes.OVERLAY_FOCUS_PRIORITY)) focusCount else 0
        GLES30.glUniform1i(p.focusCount, activeFocus)
        if (activeFocus > 0 && p.focus >= 0) GLES30.glUniform4fv(p.focus, activeFocus, focusUniform, 0)
        GLES30.glUniform1f(p.alpha, if (mode.has(GridDisplayCodes.OVERLAY_HALF_TRANSPARENCY)) 0.5f else 1f)
    }

    /**
     * Draws the overlay segments of [batch] after the grid styles: the
     * depth-tested part, then the on-top part without the depth test.
     */
    fun drawLines(batch: GridLineBatch, view: GridFrameView) {
        if (batch.segments == 0) return
        GLES30.glUseProgram(lines.id)
        GLES30.glUniformMatrix4fv(lines.viewProj, 1, false, view.viewProjection, 0)
        GLES30.glEnable(GLES30.GL_BLEND)
        GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE_MINUS_SRC_ALPHA)
        GLES30.glBindVertexArray(vao[VAO_LINES])
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, lineBuffer[0])
        val bytes = batch.segments * batch.verticesPerSegment * GridLineBatch.VERTEX_BYTES
        batch.vertices.position(0)
        // Orphan and refill: a few kilobytes per frame, never read back.
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, bytes, batch.vertices, GLES30.GL_STREAM_DRAW)
        GLES30.glEnableVertexAttribArray(0)
        GLES30.glEnableVertexAttribArray(1)
        GLES30.glVertexAttribDivisor(0, 0)
        GLES30.glVertexAttribDivisor(1, 0)
        GLES30.glVertexAttribPointer(0, 3, GLES30.GL_FLOAT, false, GridLineBatch.VERTEX_BYTES, 0)
        GLES30.glVertexAttribPointer(1, 4, GLES30.GL_UNSIGNED_BYTE, true, GridLineBatch.VERTEX_BYTES, 12)
        val primitive = if (batch.volumetric) GLES30.GL_TRIANGLES else GLES30.GL_LINES
        val vertexCount = batch.verticesPerSegment
        val tested = batch.depthTestedSegments
        if (tested > 0) {
            GLES30.glDrawArrays(primitive, 0, tested * vertexCount)
            counters.drawCalls++
        }
        if (batch.segments > tested) {
            GLES30.glDisable(GLES30.GL_DEPTH_TEST)
            GLES30.glDrawArrays(primitive, tested * vertexCount, (batch.segments - tested) * vertexCount)
            GLES30.glEnable(GLES30.GL_DEPTH_TEST)
            counters.drawCalls++
        }
        GLES30.glDisable(GLES30.GL_BLEND)
        GLES30.glBindVertexArray(0)
        GLES30.glUseProgram(0)
    }

    private fun drawPoints(mode: GridDisplayState, view: GridFrameView, selectedOnly: Boolean, fixedPx: Float, sphere: Boolean) {
        val name = buffers.bufferName(GridBuffer.SURFACES)
        if (name == 0) return
        use(points, mode, view)
        GLES30.glUniform1f(points.fixedPx, fixedPx)
        GLES30.glUniform1i(points.sphere, if (sphere) 1 else 0)
        GLES30.glUniform1i(points.selectedOnly, if (selectedOnly) 1 else 0)
        if (selectedOnly) GLES30.glUniform1i(points.sizeRule, GridDisplayCodes.SIZE_FIXED)
        GLES30.glBindVertexArray(vao[VAO_POINTS])
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, name)
        surfacePointers(0, divisor = 0)
        if (selectedOnly) {
            val table = store.surfaceSlices
            for (i in 0 until spikeSliceCount) {
                val s = spikeSlices[i]
                GLES30.glDrawArrays(GLES30.GL_POINTS, table.first[s], table.count[s])
                counters.drawCalls++
                counters.instances += table.count[s]
            }
        } else {
            for (r in 0 until surfaceRunCount) {
                val n = surfaceRunEnd[r] - surfaceRunStart[r]
                GLES30.glUniform1i(points.grey, if (surfaceRunGrey[r]) 1 else 0)
                GLES30.glDrawArrays(GLES30.GL_POINTS, surfaceRunStart[r], n)
                counters.drawCalls++
                counters.instances += n
            }
        }
    }

    /**
     * Soft splats in two passes ("visibility splatting"). Overlapping splats
     * of one surface patch are coplanar: drawn in one pass with depth
     * writes, the first splat to touch a pixel (often its nearly transparent
     * rim) wrote the depth and every later splat at the same depth failed
     * the test, leaving holes in the middle of a fully covered patch. Pass 1
     * writes only depth, pushed back by a polygon offset; pass 2 blends the
     * colour of every splat in front of that depth without writing depth,
     * so coplanar splats accumulate while hidden surfaces stay hidden.
     * With HALF_TRANSPARENCY both passes discard the same checkerboard
     * pixels; surviving colour is opaque, so arbitrarily many overlapping
     * surfels still leave half the camera pixels visible. This is spatial
     * transparency, not smooth alpha, and allocates no extra render target.
     * Depth is reconstructed surfel depth, not the real camera scene depth.
     */
    private fun drawSplats(mode: GridDisplayState, view: GridFrameView) {
        val name = buffers.bufferName(GridBuffer.SURFACES)
        if (name == 0) return
        use(splat, mode, view)
        GLES30.glBindVertexArray(vao[VAO_SPLATS])
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, name)
        GLES30.glColorMask(false, false, false, false)
        GLES30.glEnable(GLES30.GL_POLYGON_OFFSET_FILL)
        GLES30.glPolygonOffset(SPLAT_DEPTH_OFFSET_FACTOR, SPLAT_DEPTH_OFFSET_UNITS)
        splatRuns(colour = false)
        GLES30.glDisable(GLES30.GL_POLYGON_OFFSET_FILL)
        GLES30.glColorMask(true, true, true, true)
        GLES30.glDepthMask(false)
        GLES30.glDepthFunc(GLES30.GL_LEQUAL)
        GLES30.glEnable(GLES30.GL_BLEND)
        GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE_MINUS_SRC_ALPHA)
        splatRuns(colour = true)
        GLES30.glDisable(GLES30.GL_BLEND)
        GLES30.glDepthFunc(GLES30.GL_LESS)
        GLES30.glDepthMask(true)
    }

    private fun splatRuns(colour: Boolean) {
        for (r in 0 until surfaceRunCount) {
            val n = surfaceRunEnd[r] - surfaceRunStart[r]
            surfacePointers(surfaceRunStart[r], divisor = 1)
            GLES30.glUniform1i(splat.grey, if (surfaceRunGrey[r]) 1 else 0)
            GLES30.glDrawArraysInstanced(GLES30.GL_TRIANGLE_STRIP, 0, 4, n)
            counters.drawCalls++
            if (colour) counters.instances += n
        }
    }

    private fun drawSpikes(mode: GridDisplayState, view: GridFrameView) {
        val name = buffers.bufferName(GridBuffer.SURFACES)
        if (name == 0 || spikeSliceCount == 0) return
        use(spike, mode, view)
        GLES30.glUniform1i(spike.selectedOnly, 1)
        GLES30.glBindVertexArray(vao[VAO_SPIKES])
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, name)
        val table = store.surfaceSlices
        for (i in 0 until spikeSliceCount) {
            val s = spikeSlices[i]
            surfacePointers(table.first[s], divisor = SPIKES_PER_SURFACE)
            GLES30.glDrawArraysInstanced(GLES30.GL_TRIANGLE_STRIP, 0, 4, table.count[s] * SPIKES_PER_SURFACE)
            counters.drawCalls++
            counters.instances += table.count[s].toLong() * SPIKES_PER_SURFACE
        }
        // The ball: a shaded sprite at each selected surface.
        drawPoints(mode, view, selectedOnly = true, fixedPx = SPIKE_BALL_PX, sphere = true)
    }

    private fun drawVoxels(mode: GridDisplayState, view: GridFrameView) {
        val name = buffers.bufferName(GridBuffer.VOXELS)
        if (name == 0) return
        use(voxel, mode, view)
        GLES30.glBindVertexArray(vao[VAO_VOXELS])
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, name)
        GLES30.glEnableVertexAttribArray(LOC_VOXEL)
        GLES30.glVertexAttribDivisor(LOC_VOXEL, 1)
        for (r in 0 until voxelRunCount) {
            val n = voxelRunEnd[r] - voxelRunStart[r]
            GLES30.glVertexAttribIPointer(LOC_VOXEL, 4, GLES30.GL_SHORT, GridBuffer.VOXELS.recordBytes, voxelRunStart[r] * GridBuffer.VOXELS.recordBytes)
            GLES30.glUniform1i(voxel.grey, if (voxelRunGrey[r]) 1 else 0)
            GLES30.glDrawArraysInstanced(GLES30.GL_TRIANGLE_STRIP, 0, 14, n)
            counters.drawCalls++
            counters.instances += n
            counters.voxelInstances += n
        }
    }

    private fun drawBoxes(view: GridFrameView, buffer: GridBuffer, wire: Boolean, residencyFilter: Int, alpha: Float) {
        val name = buffers.bufferName(buffer)
        val count = if (buffer == GridBuffer.BRICKS) store.brickSlots else store.regionSlots
        if (name == 0 || count == 0) return
        val p = if (wire) boxWire else boxFill
        GLES30.glUseProgram(p.id)
        GLES30.glUniformMatrix4fv(p.viewProj, 1, false, view.viewProjection, 0)
        GLES30.glUniform1i(p.colors, 0)
        GLES30.glUniform1f(p.scale, if (buffer == GridBuffer.BRICKS) BRICK_METRES else BRICK_METRES * 8)
        GLES30.glUniform1i(p.summaryKind, if (buffer == GridBuffer.BRICKS) 0 else 1)
        GLES30.glUniform1i(p.residencyFilter, residencyFilter)
        GLES30.glUniform1f(p.alpha, alpha)
        if (alpha < 1f) {
            GLES30.glEnable(GLES30.GL_BLEND)
            GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE_MINUS_SRC_ALPHA)
            GLES30.glDepthMask(false)
        }
        GLES30.glBindVertexArray(vao[if (buffer == GridBuffer.BRICKS) VAO_BRICKS else VAO_REGIONS])
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, name)
        GLES30.glEnableVertexAttribArray(LOC_BOX0)
        GLES30.glEnableVertexAttribArray(LOC_BOX1)
        GLES30.glVertexAttribDivisor(LOC_BOX0, 1)
        GLES30.glVertexAttribDivisor(LOC_BOX1, 1)
        GLES30.glVertexAttribPointer(LOC_BOX0, 4, GLES30.GL_SHORT, false, 16, 0)
        GLES30.glVertexAttribIPointer(LOC_BOX1, 2, GLES30.GL_UNSIGNED_INT, 16, 8)
        if (wire) {
            GLES30.glDrawArraysInstanced(GLES30.GL_LINES, 0, 24, count)
        } else {
            GLES30.glDrawArraysInstanced(GLES30.GL_TRIANGLE_STRIP, 0, 14, count)
        }
        counters.drawCalls++
        counters.instances += count
        if (alpha < 1f) {
            GLES30.glDepthMask(true)
            GLES30.glDisable(GLES30.GL_BLEND)
        }
    }

    private fun drawTargets(view: GridFrameView) {
        if (targetCount == 0) return
        GLES30.glUseProgram(targets.id)
        GLES30.glUniformMatrix4fv(targets.viewProj, 1, false, view.viewProjection, 0)
        GLES30.glUniform4fv(GLES30.glGetUniformLocation(targets.id, "u_targets"), targetCount * 2, targetUniform, 0)
        GLES30.glBindVertexArray(vao[VAO_TARGETS])
        GLES30.glDrawArraysInstanced(GLES30.GL_LINES, 0, 6, targetCount)
        counters.drawCalls++
        counters.instances += targetCount
    }

    /** Points the surface attributes at record [base] of the bound ARRAY_BUFFER. */
    private fun surfacePointers(base: Int, divisor: Int) {
        val stride = SurfaceInstanceLayout.BYTES
        val o = base * stride
        for (l in 0..4) {
            GLES30.glEnableVertexAttribArray(l)
            GLES30.glVertexAttribDivisor(l, divisor)
        }
        GLES30.glVertexAttribPointer(0, 3, GLES30.GL_FLOAT, false, stride, o)
        GLES30.glVertexAttribPointer(1, 2, GLES30.GL_SHORT, true, stride, o + SurfaceInstanceLayout.NORMAL)
        GLES30.glVertexAttribIPointer(2, 1, GLES30.GL_UNSIGNED_INT, stride, o + SurfaceInstanceLayout.SIZE_F16)
        GLES30.glVertexAttribIPointer(3, 1, GLES30.GL_UNSIGNED_INT, stride, o + SurfaceInstanceLayout.COVERAGE)
        GLES30.glVertexAttribIPointer(4, 3, GLES30.GL_UNSIGNED_INT, stride, o + SurfaceInstanceLayout.BINS)
    }

    // ------------------------------------------------------------ culling

    private fun extractPlanes(m: FloatArray) {
        // Gribb-Hartmann on a column-major matrix: rows are m[i], m[4+i], m[8+i], m[12+i].
        for (p in 0 until 6) {
            val row = p / 2
            val sign = if (p % 2 == 0) 1f else -1f
            val a = m[3] + sign * m[row]
            val b = m[7] + sign * m[4 + row]
            val c = m[11] + sign * m[8 + row]
            val d = m[15] + sign * m[12 + row]
            val len = sqrt(a * a + b * b + c * c).coerceAtLeast(1e-6f)
            planes[p * 4] = a / len
            planes[p * 4 + 1] = b / len
            planes[p * 4 + 2] = c / len
            planes[p * 4 + 3] = d / len
        }
    }

    private fun visible(key: Long): Boolean {
        val x = (GridBrickKey.x(key) + 0.5f) * BRICK_METRES
        val y = (GridBrickKey.y(key) + 0.5f) * BRICK_METRES
        val z = (GridBrickKey.z(key) + 0.5f) * BRICK_METRES
        for (p in 0 until 6) {
            if (planes[p * 4] * x + planes[p * 4 + 1] * y + planes[p * 4 + 2] * z + planes[p * 4 + 3] < -BRICK_RADIUS) return false
        }
        return true
    }

    private fun cullSlices(table: BrickSliceTable, isVoxel: Boolean) {
        var n = 0
        if (!isVoxel) spikeSliceCount = 0
        val scope = scope
        for (i in 0 until table.highWater) {
            val count = table.count[i]
            val key = table.brickKey[i]
            if (count == 0 || !visible(key)) continue
            if (n == runKeys.size) {
                runKeys = runKeys.copyOf(n * 2)
                runGrowths++
            }
            val grey = scope != null && !scope.isInScope(
                (GridBrickKey.x(key) + 0.5f) * BRICK_METRES,
                (GridBrickKey.y(key) + 0.5f) * BRICK_METRES,
                (GridBrickKey.z(key) + 0.5f) * BRICK_METRES,
            )
            runKeys[n++] = (table.first[i].toLong() shl 32) or count.toLong() or (if (grey) GREY_BIT else 0L)
            if (!isVoxel && table.selected[i] > 0) {
                if (spikeSliceCount == spikeSlices.size) {
                    spikeSlices = spikeSlices.copyOf(spikeSliceCount * 2)
                    runGrowths++
                }
                spikeSlices[spikeSliceCount++] = i
            }
        }
        java.util.Arrays.sort(runKeys, 0, n)
        var runs = 0
        var starts = if (isVoxel) voxelRunStart else surfaceRunStart
        var ends = if (isVoxel) voxelRunEnd else surfaceRunEnd
        var greys = if (isVoxel) voxelRunGrey else surfaceRunGrey
        for (k in 0 until n) {
            val start = (runKeys[k] ushr 32).toInt()
            val end = start + (runKeys[k] and 0x7fffffffL).toInt()
            val grey = runKeys[k] and GREY_BIT != 0L
            if (runs > 0 && ends[runs - 1] == start && greys[runs - 1] == grey) {
                ends[runs - 1] = end
            } else {
                if (runs == starts.size) {
                    starts = starts.copyOf(runs * 2)
                    ends = ends.copyOf(runs * 2)
                    greys = greys.copyOf(runs * 2)
                    runGrowths++
                }
                starts[runs] = start
                ends[runs] = end
                greys[runs] = grey
                runs++
            }
        }
        if (isVoxel) {
            voxelRunStart = starts; voxelRunEnd = ends; voxelRunGrey = greys; voxelRunCount = runs
            counters.visibleVoxelSlices = n
        } else {
            surfaceRunStart = starts; surfaceRunEnd = ends; surfaceRunGrey = greys; surfaceRunCount = runs
            counters.visibleSurfaceSlices = n
        }
    }

    /** One linked GLSL program and its uniform locations. */
    class Program(vs: String, fs: String) {
        val id: Int
        val viewProj: Int
        val camPos: Int
        val focalPx: Int
        val colorAttr: Int
        val sizeRule: Int
        val fixedPx: Int
        val selectedOnly: Int
        val colors: Int
        val sphere: Int
        val scale: Int
        val summaryKind: Int
        val residencyFilter: Int
        val alpha: Int
        val grey: Int
        val minQuality: Int
        val focusCount: Int
        val focus: Int

        init {
            val v = compile(GLES30.GL_VERTEX_SHADER, vs)
            val f = compile(GLES30.GL_FRAGMENT_SHADER, fs)
            id = GLES30.glCreateProgram()
            GLES30.glAttachShader(id, v)
            GLES30.glAttachShader(id, f)
            GLES30.glLinkProgram(id)
            val ok = IntArray(1)
            GLES30.glGetProgramiv(id, GLES30.GL_LINK_STATUS, ok, 0)
            check(ok[0] == GLES30.GL_TRUE) { "grid program link failed: ${GLES30.glGetProgramInfoLog(id)}" }
            GLES30.glDeleteShader(v)
            GLES30.glDeleteShader(f)
            fun loc(name: String) = GLES30.glGetUniformLocation(id, name)
            viewProj = loc("u_viewProj")
            camPos = loc("u_camPos")
            focalPx = loc("u_focalPx")
            colorAttr = loc("u_colorAttr")
            sizeRule = loc("u_sizeRule")
            fixedPx = loc("u_fixedPx")
            selectedOnly = loc("u_selectedOnly")
            colors = loc("u_colors")
            sphere = loc("u_sphere")
            scale = loc("u_scale")
            summaryKind = loc("u_summaryKind")
            residencyFilter = loc("u_residencyFilter")
            alpha = loc("u_alpha")
            grey = loc("u_grey")
            minQuality = loc("u_minQuality")
            focusCount = loc("u_focusCount")
            focus = loc("u_focus[0]")
        }

        private fun compile(type: Int, source: String): Int {
            val s = GLES30.glCreateShader(type)
            GLES30.glShaderSource(s, source)
            GLES30.glCompileShader(s)
            val ok = IntArray(1)
            GLES30.glGetShaderiv(s, GLES30.GL_COMPILE_STATUS, ok, 0)
            check(ok[0] == GLES30.GL_TRUE) { "grid shader compile failed: ${GLES30.glGetShaderInfoLog(s)}" }
            return s
        }
    }

    companion object {
        private const val VAO_POINTS = 0
        private const val VAO_SPLATS = 1
        private const val VAO_SPIKES = 2
        private const val VAO_VOXELS = 3
        private const val VAO_BRICKS = 4
        private const val VAO_REGIONS = 5
        private const val VAO_TARGETS = 6
        private const val VAO_LINES = 7
        private const val VAO_COUNT = 8
        private const val GREY_BIT = 0x80000000L
        private const val LOC_BOX0 = 5
        private const val LOC_BOX1 = 6
        private const val LOC_VOXEL = 11
        private const val SPIKES_PER_SURFACE = 24
        private const val SPIKE_BALL_PX = 10f
        private const val SPLAT_DEPTH_OFFSET_FACTOR = 1f
        private const val SPLAT_DEPTH_OFFSET_UNITS = 4f

        /** Residency bytes of brick and region summaries (`renderer_delta.dart`). */
        internal const val RESIDENCY_ON_DISK = 1
        internal const val RESIDENCY_SUMMARY_ONLY = 2
        internal const val SUMMARY_BRICK_ALPHA = 0.22f
        internal const val PAGED_REGION_ALPHA = 0.15f

        /**
         * The level-of-detail boxes under a base layer, as (buffer, residency
         * filter, alpha): far bricks have no surface slices, so their
         * summaries stand in; paged-out regions have neither surfaces nor
         * brick summaries in RAM, so their region summaries stand in (the
         * region overlay, when on, already draws every region).
         */
        internal inline fun forEachLevelOfDetailPass(mode: GridDisplayState, pass: (GridBuffer, Int, Float) -> Unit) {
            if (mode.baseLayer == GridDisplayCodes.BASE_NONE) return
            pass(GridBuffer.BRICKS, RESIDENCY_SUMMARY_ONLY, SUMMARY_BRICK_ALPHA)
            if (!mode.has(GridDisplayCodes.OVERLAY_REGIONS)) pass(GridBuffer.REGIONS, RESIDENCY_ON_DISK, PAGED_REGION_ALPHA)
        }

        const val BRICK_METRES = GridBrickKey.BRICK_METRES
        private val BRICK_RADIUS = BRICK_METRES * sqrt(3f) * 0.5f

        /** The grid's 24 direction bins (three elevation bands of eight azimuths), as in the Dart exporter. */
        val BIN_DIRECTIONS = FloatArray(72).also {
            val elevations = doubleArrayOf(41.8, 0.0, -41.8)
            for (band in 0 until 3) for (a in 0 until 8) {
                val el = elevations[band] * PI / 180
                val az = (a * 45 + 22.5) * PI / 180
                val i = (band * 8 + a) * 3
                it[i] = (cos(el) * cos(az)).toFloat()
                it[i + 1] = sin(el).toFloat()
                it[i + 2] = (cos(el) * sin(az)).toFloat()
            }
        }
    }
}
