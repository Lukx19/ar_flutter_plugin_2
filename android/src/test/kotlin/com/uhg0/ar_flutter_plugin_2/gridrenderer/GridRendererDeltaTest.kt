package com.uhg0.ar_flutter_plugin_2.gridrenderer

import com.uhg0.ar_flutter_plugin_2.visibilitycontract.RendererDeltaLayout
import com.uhg0.ar_flutter_plugin_2.visibilitycontract.SurfaceInstanceLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Delta reader and instance buffers against the contract goldens and the
 * Dart exporter's room stream (`test_assets/grid_renderer/room_export_stream.bin`,
 * written by `renderer_delta_exporter_test.dart`: a 1.6 x 1.2 x 1.2 m room,
 * 864 surfaces, nearest-K 64).
 */
class GridRendererDeltaTest {
    private val contractGoldens = File("../test_assets/visibility_grid")
    private val exportStream = File("../test_assets/grid_renderer/room_export_stream.bin")

    private fun golden(name: String): ByteBuffer =
        ByteBuffer.wrap(File(contractGoldens, name).readBytes()).order(ByteOrder.LITTLE_ENDIAN)

    /** Splits the length-prefixed export stream into (offset, length) pairs. */
    private fun streamMessages(): Pair<ByteBuffer, List<IntArray>> {
        val bytes = ByteBuffer.wrap(exportStream.readBytes()).order(ByteOrder.LITTLE_ENDIAN)
        val messages = ArrayList<IntArray>()
        var at = 0
        while (at < bytes.limit()) {
            val length = bytes.getInt(at)
            messages += intArrayOf(at + 4, length)
            at += 4 + length
        }
        assertEquals(bytes.limit(), at)
        return bytes to messages
    }

    @Test
    fun `reader validates every contract golden`() {
        val reader = RendererDeltaReader()
        for (name in listOf(
            "render_surface_slice.bin", "render_brick_summaries.bin", "render_occupancy_masks.bin",
            "render_region_summaries.bin", "render_compact_voxels.bin", "render_free_slots.bin",
            "render_reset.bin", "render_frame_end.bin",
        )) {
            val b = golden(name)
            reader.attach(b, 0, b.limit())
            assertEquals(name, DeltaStatus.OK, reader.validate())
            assertEquals(name, 41L, reader.gridVersion)
        }
        val slice = golden("render_surface_slice.bin")
        reader.attach(slice, 0, slice.limit())
        assertEquals(RendererDeltaLayout.KIND_SURFACE_SLICE, reader.kind)
        assertEquals(64, reader.firstSlot)
        assertEquals(0x0000123400005678L, reader.brickKey)
        assertEquals(2 * SurfaceInstanceLayout.BYTES, reader.recordsLength)
    }

    @Test
    fun `reader rejects malformed messages`() {
        val reader = RendererDeltaReader()
        val b = golden("render_free_slots.bin")
        reader.attach(b, 0, b.limit() - 1)
        assertEquals(DeltaStatus.BAD_LENGTH, reader.validate())
        reader.attach(b, 0, 16)
        assertEquals(DeltaStatus.TOO_SHORT, reader.validate())
        val bad = ByteBuffer.allocate(b.limit()).order(ByteOrder.LITTLE_ENDIAN).put(b.duplicate())
        bad.put(RendererDeltaLayout.KIND, 99)
        reader.attach(bad, 0, bad.limit())
        assertEquals(DeltaStatus.BAD_KIND, reader.validate())
        bad.put(RendererDeltaLayout.KIND, RendererDeltaLayout.KIND_FREE_SLOTS.toByte())
        bad.putInt(RendererDeltaLayout.RECORD_COUNT, 100_000)
        assertEquals(DeltaStatus.TOO_LARGE, reader.validate())
        bad.putInt(0, 0)
        assertEquals(DeltaStatus.BAD_MAGIC, reader.validate())
    }

    @Test
    fun `dart export stream builds the room in the instance buffers`() {
        val (bytes, messages) = streamMessages()
        val target = ByteArrayGpuTarget()
        val store = InstanceBufferStore(target)
        for (m in messages) assertEquals(DeltaStatus.OK, store.apply(bytes, m[0], m[1]))
        assertEquals(1L, store.counters.resets)
        assertEquals(0L, store.counters.rejected)
        val surfaces = ByteBuffer.wrap(target.buffer(GridBuffer.SURFACES)).order(ByteOrder.LITTLE_ENDIAN)
        var published = 0
        var selected = 0
        val ids = HashSet<Int>()
        val table = store.surfaceSlices
        for (i in 0 until table.highWater) {
            for (r in table.first[i] until table.first[i] + table.count[i]) {
                val o = r * SurfaceInstanceLayout.BYTES
                val flags = surfaces.get(o + SurfaceInstanceLayout.FLAGS).toInt()
                if (flags and SurfaceInstanceLayout.FLAG_PUBLISHED == 0) continue
                published++
                if (flags and SurfaceInstanceLayout.FLAG_SELECTED != 0) selected++
                ids += surfaces.getInt(o + SurfaceInstanceLayout.STABLE_ID)
            }
        }
        assertEquals(864, published)
        assertEquals(864, ids.size)
        assertEquals(64, selected)
        var selectedSlices = 0
        for (i in 0 until table.highWater) selectedSlices += table.selected[i]
        assertEquals(64, selectedSlices)
        assertTrue(store.voxelSlices.size > 0)
        assertEquals(table.size, store.voxelSlices.size)
        // One summary per brick slot; every live brick has a surface slice.
        val bricks = ByteBuffer.wrap(target.buffer(GridBuffer.BRICKS)).order(ByteOrder.LITTLE_ENDIAN)
        var liveBricks = 0
        for (b in 0 until store.brickSlots) if (bricks.getShort(b * 16 + 6).toInt() != 0) liveBricks++
        assertEquals(table.size, liveBricks)
        assertTrue(store.regionSlots >= 1)
    }

    @Test
    fun `free slots zero the surface range and zero-record slices leave the draw table`() {
        val w = RendererDeltaWriter()
        val target = ByteArrayGpuTarget()
        val store = InstanceBufferStore(target)
        w.begin(RendererDeltaLayout.KIND_RESET, 0, 0, 64, 0)
        store.apply(w.buffer, 0, w.length)
        val key = GridBrickKey.pack(1, 0, -2)
        w.begin(RendererDeltaLayout.KIND_SURFACE_SLICE, 32, 32, 64, key)
        for (i in 0 until 32) w.surface(i, 1f, 0.1f * i, -1.5f, 0f, 1f, 0f)
        store.apply(w.buffer, 0, w.length)
        assertEquals(1, store.surfaceSlices.size)
        w.begin(RendererDeltaLayout.KIND_FREE_SLOTS, 0, 1, 64, 0)
        w.freeRange(0, 32, 32)
        store.apply(w.buffer, 0, w.length)
        val surfaces = target.buffer(GridBuffer.SURFACES)
        for (i in 32 * 40 until 64 * 40) assertEquals(0, surfaces[i].toInt())
        w.begin(RendererDeltaLayout.KIND_SURFACE_SLICE, 32, 0, 64, key)
        store.apply(w.buffer, 0, w.length)
        assertEquals(0, store.surfaceSlices.size)
        assertEquals(-1, store.surfaceSlices.find(key))
    }

    @Test
    fun `capacity growth keeps content and is counted`() {
        val w = RendererDeltaWriter()
        val target = ByteArrayGpuTarget()
        val store = InstanceBufferStore(target)
        w.begin(RendererDeltaLayout.KIND_RESET, 0, 0, 32, 0)
        store.apply(w.buffer, 0, w.length)
        w.begin(RendererDeltaLayout.KIND_SURFACE_SLICE, 0, 32, 32, GridBrickKey.pack(0, 0, 0))
        w.surface(0, 1f, 2f, 3f, 0f, 1f, 0f, stableId = 77)
        store.apply(w.buffer, 0, w.length)
        w.begin(RendererDeltaLayout.KIND_SURFACE_SLICE, 32, 32, 64, GridBrickKey.pack(1, 0, 0))
        store.apply(w.buffer, 0, w.length)
        assertEquals(1L, store.counters.capacityGrowths)
        assertEquals(64 * 40, target.buffer(GridBuffer.SURFACES).size)
        val surfaces = ByteBuffer.wrap(target.buffer(GridBuffer.SURFACES)).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(77, surfaces.getInt(SurfaceInstanceLayout.STABLE_ID))
    }

    @Test
    fun `occupancy masks are ignored in favour of compact voxels`() {
        val target = ByteArrayGpuTarget()
        val store = InstanceBufferStore(target)
        val masks = golden("render_occupancy_masks.bin")
        assertEquals(DeltaStatus.OK, store.apply(masks, 0, masks.limit()))
        assertEquals(1L, store.counters.ignoredOccupancyMasks)
        assertEquals(0, target.writes)
        val voxels = golden("render_compact_voxels.bin")
        store.apply(voxels, 0, voxels.limit())
        assertEquals(1, store.voxelSlices.size)
    }

    @Test
    fun `slice table removal keeps every other key reachable`() {
        val table = BrickSliceTable(initialCapacity = 4)
        val keys = LongArray(200) { GridBrickKey.pack(it % 13 - 6, it / 13, -(it % 7)) }
        for ((i, k) in keys.withIndex()) table.put(k, i * 32, 32, 0)
        for (i in keys.indices step 3) assertTrue(table.remove(keys[i]))
        for ((i, k) in keys.withIndex()) {
            if (i % 3 == 0) assertEquals(-1, table.find(k)) else assertEquals(i * 32, table.first[table.find(k)])
        }
        assertFalse(table.remove(keys[0]))
        val growth = table.growthEvents
        for (round in 0 until 10) {
            for (i in keys.indices step 3) table.put(keys[i], i, 1, 0)
            for (i in keys.indices step 3) table.remove(keys[i])
        }
        assertEquals(growth, table.growthEvents)
    }
}
