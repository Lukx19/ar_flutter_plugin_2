package com.uhg0.ar_flutter_plugin_2.gridrenderer

import com.uhg0.ar_flutter_plugin_2.visibilitycontract.ColourTableLayout
import com.uhg0.ar_flutter_plugin_2.visibilitycontract.RendererDeltaLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Upload scheduling on the render thread and display mode switches. */
class DeltaUploadSchedulerTest {
    private val w = RendererDeltaWriter()

    private fun offerReset(core: GridRendererCore) {
        w.begin(RendererDeltaLayout.KIND_RESET, 0, 0, 4096, 0)
        assertEquals(DeltaIntake.ACCEPTED, core.onRenderMessage(w.buffer, 0, w.length))
    }

    /** Offers [count] full-brick surface slices of 256 records (10,240 upload bytes each). */
    private fun offerSlices(core: GridRendererCore, count: Int, firstBrick: Int = 0) {
        for (b in firstBrick until firstBrick + count) {
            w.begin(RendererDeltaLayout.KIND_SURFACE_SLICE, b * 256, 256, 4096 * 64, GridBrickKey.pack(b, 0, 0))
            for (i in 0 until 256) w.surface(i, b * 0.8f + 0.05f, 0.05f, 0.05f, 0f, 1f, 0f)
            assertEquals(DeltaIntake.ACCEPTED, core.onRenderMessage(w.buffer, 0, w.length))
        }
    }

    private fun frame(core: GridRendererCore, target: ByteArrayGpuTarget): Int {
        target.beginFrame()
        return core.frame()
    }

    @Test
    fun `every frame stays within the byte budget and the backlog drains in order`() {
        val target = ByteArrayGpuTarget()
        val core = GridRendererCore(target, frameBudgetBytes = 32 * 1024)
        offerReset(core)
        offerSlices(core, 40)
        var frames = 0
        while (core.scheduler.queuedMessages > 0) {
            val bytes = frame(core, target)
            assertTrue("frame uploaded $bytes", bytes <= 32 * 1024)
            frames++
        }
        // 40 x 10,240 B at three slices per 32 KiB frame.
        assertEquals(14, frames)
        assertEquals(40, core.store.surfaceSlices.size)
        assertTrue(core.scheduler.counters.deferredFrames > 0)
        assertTrue(core.scheduler.counters.maxFrameBytes <= 32 * 1024)
    }

    @Test
    fun `a message larger than the budget still progresses one per frame`() {
        val target = ByteArrayGpuTarget()
        val core = GridRendererCore(target, frameBudgetBytes = 1024)
        offerReset(core)
        offerSlices(core, 3)
        frame(core, target) // reset plus the first slice
        assertEquals(1, core.store.surfaceSlices.size)
        frame(core, target)
        frame(core, target)
        assertEquals(3, core.store.surfaceSlices.size)
        assertEquals(0, core.scheduler.queuedMessages)
    }

    @Test
    fun `staging capacity defers uploads instead of overrunning`() {
        val target = ByteArrayGpuTarget(uploadCapacity = 0)
        val core = GridRendererCore(target)
        offerReset(core)
        offerSlices(core, 2)
        frame(core, target)
        // The reset uploads nothing; the slices wait for staging space.
        assertEquals(2, core.scheduler.queuedMessages)
        target.uploadCapacity = Int.MAX_VALUE
        frame(core, target)
        assertEquals(0, core.scheduler.queuedMessages)
    }

    @Test
    fun `intake waits for a reset and a full ring asks for resynchronisation`() {
        val target = ByteArrayGpuTarget()
        val core = GridRendererCore(target, scheduler = DeltaUploadScheduler(ringBytes = 64 * 1024))
        w.begin(RendererDeltaLayout.KIND_SURFACE_SLICE, 0, 1, 64, 1)
        assertEquals(DeltaIntake.RESYNC_REQUIRED, core.onRenderMessage(w.buffer, 0, w.length))
        offerReset(core)
        w.begin(RendererDeltaLayout.KIND_SURFACE_SLICE, 0, 256, 4096, 1)
        var result = DeltaIntake.ACCEPTED
        var offered = 0
        while (result == DeltaIntake.ACCEPTED && offered < 100) {
            result = core.onRenderMessage(w.buffer, 0, w.length)
            offered++
        }
        assertEquals(DeltaIntake.RESYNC_REQUIRED, result)
        assertEquals(1L, core.scheduler.counters.overflows)
        assertTrue(core.scheduler.isAwaitingReset)
        assertEquals(0, core.scheduler.queuedMessages)
        offerReset(core)
        frame(core, target)
        // The first reset was discarded with the overflowing queue.
        assertEquals(1L, core.store.counters.resets)
    }

    @Test
    fun `ring wraps around and keeps message order`() {
        val target = ByteArrayGpuTarget()
        // One slice per frame and a ring of four messages: the backlog never
        // empties, so writes wrap around the ring end.
        val core = GridRendererCore(target, scheduler = DeltaUploadScheduler(ringBytes = 40 * 1024), frameBudgetBytes = 10 * 1024)
        offerReset(core)
        offerSlices(core, 3)
        for (b in 3 until 40) {
            frame(core, target)
            offerSlices(core, 1, firstBrick = b)
        }
        while (core.scheduler.queuedMessages > 0) frame(core, target)
        assertEquals(40, core.store.surfaceSlices.size)
        for (b in 0 until 40) {
            val s = core.store.surfaceSlices.find(GridBrickKey.pack(b, 0, 0))
            assertEquals(b * 256, core.store.surfaceSlices.first[s])
        }
        assertEquals(0L, core.scheduler.counters.overflows)
    }

    @Test
    fun `mode switch changes the next frame and touches no buffer`() {
        val target = ByteArrayGpuTarget()
        val core = GridRendererCore(target)
        offerReset(core)
        offerSlices(core, 2)
        frame(core, target)
        val operations = target.operations
        val bytes = target.writtenBytes
        w.displayMode(GridDisplayCodes.BASE_VOXELS, GridDisplayCodes.OVERLAY_BRICKS, GridDisplayCodes.COLOUR_COMPLETION, 0)
        assertEquals(DisplayIntake.MODE, core.onDisplayMessage(w.buffer, 0, w.length))
        w.colourTable(GridDisplayCodes.COLOUR_COMPLETION, 0x11223344)
        assertEquals(DisplayIntake.COLOUR_TABLE, core.onDisplayMessage(w.buffer, 0, w.length))
        assertEquals(GridDisplayCodes.BASE_POINTS, core.mode.baseLayer)
        frame(core, target)
        assertEquals(core.frameNumber, core.modeAppliedFrame)
        assertEquals(GridDisplayCodes.BASE_VOXELS, core.mode.baseLayer)
        assertTrue(core.mode.has(GridDisplayCodes.OVERLAY_BRICKS))
        assertEquals(GridDisplayCodes.COLOUR_COMPLETION, core.mode.colourAttribute)
        assertEquals(0x11223344, core.display.rgba(GridDisplayCodes.COLOUR_COMPLETION, 200))
        assertEquals(operations, target.operations)
        assertEquals(bytes, target.writtenBytes)
    }

    @Test
    fun `display goldens decode and bad display messages are rejected`() {
        val goldens = File("../test_assets/visibility_grid")
        val handler = DisplayModeHandler()
        val mode = File(goldens, "display_mode.bin").readBytes()
        assertEquals(DisplayIntake.MODE, handler.onMessage(mode))
        val state = GridDisplayState()
        assertTrue(handler.snapshotMode(state, -1))
        assertEquals(GridDisplayCodes.BASE_SPLATS, state.baseLayer)
        assertEquals(GridDisplayCodes.OVERLAY_SPIKE_BALLS or GridDisplayCodes.OVERLAY_TARGETS, state.overlayBits)
        assertEquals(512, state.spikeNearestK)
        assertEquals(6.5f, state.pointSizePx)
        assertEquals(3L, state.colourTableGeneration)
        assertEquals(64, state.minQuality)
        val table = File(goldens, "colour_table.bin").readBytes()
        assertEquals(DisplayIntake.COLOUR_TABLE, handler.onMessage(table))
        assertEquals(0x0af540ff, handler.rgba(GridDisplayCodes.COLOUR_COVERAGE, 10))
        val tables = ByteBuffer.allocateDirect(5 * ColourTableLayout.ENTRIES * 4)
        assertTrue(handler.snapshotTables(tables, 0) > 0)
        mode[4] = 9
        assertEquals(DisplayIntake.REJECTED, handler.onMessage(mode))
        assertEquals(DisplayIntake.REJECTED, handler.onMessage(ByteArray(4)))
    }

    @Test
    fun `minimum quality reaches the render state and an older message draws every surface`() {
        val core = GridRendererCore(ByteArrayGpuTarget())
        w.displayMode(GridDisplayCodes.BASE_SPLATS, 0, GridDisplayCodes.COLOUR_CONFIDENCE, 0, minQuality = 200)
        assertEquals(DisplayIntake.MODE, core.onDisplayMessage(w.buffer, 0, w.length))
        val state = GridDisplayState()
        assertTrue(core.display.snapshotMode(state, -1))
        assertEquals(200, state.minQuality)
        // Byte 20 was reserved zero: a message written before the threshold decodes as 0.
        w.displayMode(GridDisplayCodes.BASE_POINTS, 0, GridDisplayCodes.COLOUR_COVERAGE, 0)
        assertEquals(DisplayIntake.MODE, core.onDisplayMessage(w.buffer, 0, w.length))
        assertTrue(core.display.snapshotMode(state, state.modeVersion))
        assertEquals(0, state.minQuality)
    }

    @Test
    fun `surface and voxel shaders discard instances below the minimum quality`() {
        for (source in listOf(GridRendererShaders.POINTS_VS, GridRendererShaders.SPLAT_VS, GridRendererShaders.SPIKE_VS)) {
            assertTrue(source.contains("uniform int u_minQuality;"))
            assertTrue(source.contains("int((a_meta1 >> 16u) & 255u) >= u_minQuality"))
            assertTrue(source.contains("!surfaceValid()"))
        }
        assertTrue(GridRendererShaders.VOXEL_VS.contains("uniform int u_minQuality;"))
        assertTrue(GridRendererShaders.VOXEL_VS.contains("< min(u_minQuality, 254)"))
    }

    @Test
    fun `planner targets decode from the provisional display message`() {
        val handler = DisplayModeHandler()
        val bytes = ByteBuffer.allocate(PlannerTargetMessageLayout.BYTES).order(ByteOrder.LITTLE_ENDIAN)
        bytes.putInt(0, PlannerTargetMessageLayout.MAGIC)
        bytes.putShort(4, 1)
        bytes.put(PlannerTargetMessageLayout.COUNT, 2)
        bytes.putFloat(8, 1.5f)
        bytes.putFloat(8 + 8 * 4 + 4, -2f)
        assertEquals(DisplayIntake.TARGETS, handler.onMessage(bytes.array()))
        val out = FloatArray(128)
        assertEquals(2, handler.snapshotTargets(out, -1))
        assertEquals(1.5f, out[0])
        assertEquals(-2f, out[9])
        assertEquals(-1, handler.snapshotTargets(out, handler.currentTargetsVersion))
    }
}
