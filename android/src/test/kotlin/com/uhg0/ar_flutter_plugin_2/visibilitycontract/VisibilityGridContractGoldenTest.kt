package com.uhg0.ar_flutter_plugin_2.visibilitycontract

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Reads the Dart-written goldens in `test_assets/visibility_grid/` through the
 * Kotlin layout constants and writes three of them byte-exact. Field values
 * match `test/visibility_grid_contract/contract_fixtures.dart`.
 */
class VisibilityGridContractGoldenTest {
    private val goldens = File("../test_assets/visibility_grid")

    private fun golden(name: String): ByteBuffer =
        ByteBuffer.wrap(File(goldens, name).readBytes()).order(ByteOrder.LITTLE_ENDIAN)

    private fun assertIngestHeader(b: ByteBuffer, kind: Int) {
        assertEquals(IngestMessageLayout.MAGIC, b.getInt(0))
        assertEquals(IngestMessageLayout.VERSION, b.getShort(4).toInt())
        assertEquals(kind, b.get(IngestMessageLayout.KIND).toInt())
        assertEquals(
            b.capacity(),
            IngestMessageLayout.HEADER_BYTES + b.getInt(IngestMessageLayout.PAYLOAD_BYTES),
        )
    }

    @Test
    fun `record sizes match the normative contract`() {
        assertEquals(160, IngestMessageLayout.HEADER_BYTES)
        assertEquals(128, PhotoSavedLayout.BYTES)
        assertEquals(64, MarkerDetectionLayout.BYTES)
        assertEquals(32, RendererDeltaLayout.HEADER_BYTES)
        assertEquals(40, SurfaceInstanceLayout.BYTES)
        assertEquals(16, BrickSummaryLayout.BYTES)
        assertEquals(16, RegionSummaryLayout.BYTES)
        assertEquals(32, DisplayModeLayout.BYTES)
        assertEquals(1036, ColourTableLayout.BYTES)
        assertEquals(32, ShellControlLayout.BYTES)
        assertEquals(32, IngestRecordingLayout.HEADER_BYTES)
        assertEquals(0x49563343, IngestMessageLayout.MAGIC)
        val codes = NativeStatusCode.values().map { it.code }
        assertEquals(codes.size, codes.toSet().size)
    }

    @Test
    fun `depth golden carries the pose at the sample timestamp`() {
        val b = golden("ingest_depth_u16.bin")
        assertIngestHeader(b, IngestMessageLayout.KIND_DEPTH_IMAGE)
        assertEquals(7, b.getInt(IngestMessageLayout.SEQUENCE))
        assertEquals(1_000_000_000L, b.getLong(IngestMessageLayout.SAMPLE_TIMESTAMP_NS))
        assertEquals(1_170_000_000L, b.getLong(IngestMessageLayout.FRAME_TIMESTAMP_NS))
        assertEquals(IngestMessageLayout.POSE_MATCH_INTERPOLATED, b.get(IngestMessageLayout.POSE_MATCH).toInt())
        assertEquals(3, b.getShort(IngestMessageLayout.GROUP_EPOCH).toInt())
        assertEquals(-1250, b.getInt(IngestMessageLayout.POSE_GAP_US))
        assertEquals(1.25f, b.getFloat(IngestMessageLayout.CAMERA_TO_WORLD + 13 * 4))
        assertEquals(180f, b.getFloat(IngestMessageLayout.INTRINSICS))
        assertEquals(4, b.getShort(IngestMessageLayout.WIDTH).toInt())
        assertEquals(3, b.getShort(IngestMessageLayout.HEIGHT).toInt())
        assertEquals(8, b.getShort(IngestMessageLayout.ROW_STRIDE).toInt())
        assertEquals(IngestMessageLayout.CONFIDENCE_U8, b.get(IngestMessageLayout.CONFIDENCE_FORMAT).toInt())
        assertEquals(2, b.getInt(IngestMessageLayout.DROPPED))
        val payload = IngestMessageLayout.HEADER_BYTES
        assertEquals(3000, b.getShort(payload + 2 * 8 + 2 * 3).toInt())
    }

    @Test
    fun `point and voxel goldens decode`() {
        val points = golden("ingest_points.bin")
        assertIngestHeader(points, IngestMessageLayout.KIND_POINT_CLOUD)
        val p = IngestMessageLayout.HEADER_BYTES
        assertEquals(3, points.getInt(p))
        val second = p + IngestMessageLayout.POINT_COUNT_BYTES + IngestMessageLayout.POINT_BYTES
        assertEquals(101, points.getInt(second))
        assertEquals(1.25f, points.getFloat(second + 8))
        val voxels = golden("ingest_voxels.bin")
        assertIngestHeader(voxels, IngestMessageLayout.KIND_AGGREGATED_VOXELS)
        assertEquals(3, voxels.getInt(p + IngestMessageLayout.VOXEL_COUNT_BYTES + 24))
    }

    @Test
    fun `photo saved and marker goldens decode`() {
        val photo = golden("ingest_photo_saved.bin")
        assertIngestHeader(photo, IngestMessageLayout.KIND_PHOTO_SAVED)
        val p = IngestMessageLayout.HEADER_BYTES
        assertEquals(31, photo.get(p + PhotoSavedLayout.PHOTO_ID + 31).toInt())
        assertEquals(1_234_567_890_123L, photo.getLong(p + PhotoSavedLayout.EXPOSURE_TIMESTAMP_NS))
        assertEquals(812, photo.getShort(p + PhotoSavedLayout.SHARPNESS).toInt())
        assertEquals(PhotoSavedLayout.FLAG_AUTOMATIC, photo.get(p + PhotoSavedLayout.FLAGS).toInt())

        val markers = golden("ingest_markers.bin")
        assertIngestHeader(markers, IngestMessageLayout.KIND_MARKER_DETECTIONS)
        assertEquals(2, markers.getShort(p).toInt())
        val second = p + IngestMessageLayout.MARKER_PREFIX_BYTES + MarkerDetectionLayout.BYTES
        assertEquals(13, markers.getShort(second + MarkerDetectionLayout.MARKER_ID).toInt())
        assertEquals(
            MarkerDetectionLayout.FLAG_REJECTED_BLUR,
            markers.get(second + MarkerDetectionLayout.FLAGS).toInt(),
        )
        assertEquals(0x3a00, markers.getShort(second + MarkerDetectionLayout.REPROJECTION_ERROR_F16).toInt())
        assertEquals(200, markers.get(second + MarkerDetectionLayout.SHARPNESS).toInt() and 0xff)
    }

    @Test
    fun `renderer deltas decode through the reader layout`() {
        val slice = golden("render_surface_slice.bin")
        assertEquals(RendererDeltaLayout.MAGIC, slice.getInt(0))
        assertEquals(RendererDeltaLayout.KIND_SURFACE_SLICE, slice.get(RendererDeltaLayout.KIND).toInt())
        val count = slice.getInt(RendererDeltaLayout.RECORD_COUNT)
        assertEquals(slice.capacity(), RendererDeltaLayout.HEADER_BYTES + count * SurfaceInstanceLayout.BYTES)
        val second = RendererDeltaLayout.HEADER_BYTES + SurfaceInstanceLayout.BYTES
        assertEquals(9001, slice.getInt(second + SurfaceInstanceLayout.STABLE_ID))
        assertEquals(2f, slice.getFloat(second + SurfaceInstanceLayout.POSITION))
        for (name in listOf(
            "render_brick_summaries.bin", "render_occupancy_masks.bin", "render_region_summaries.bin",
            "render_compact_voxels.bin", "render_free_slots.bin", "render_reset.bin", "render_frame_end.bin",
        )) {
            val b = golden(name)
            val kind = b.get(RendererDeltaLayout.KIND).toInt()
            val records = b.getInt(RendererDeltaLayout.RECORD_COUNT)
            assertEquals(name, b.capacity(), RendererDeltaLayout.HEADER_BYTES + records * RendererDeltaLayout.recordBytes(kind))
        }
        val voxels = golden("render_compact_voxels.bin")
        assertEquals(-1L, voxels.getLong(RendererDeltaLayout.HEADER_BYTES + 2 * RendererDeltaLayout.COMPACT_VOXEL_BYTES))
    }

    @Test
    fun `recording frames ten messages`() {
        val b = golden("ingest_recording.bin")
        assertEquals(IngestRecordingLayout.MAGIC, b.getInt(0))
        assertEquals(10, b.getInt(IngestRecordingLayout.MESSAGE_COUNT))
        var at = IngestRecordingLayout.HEADER_BYTES
        var messages = 0
        while (at < b.capacity()) {
            val length = b.getInt(at)
            assertEquals(IngestMessageLayout.MAGIC, b.getInt(at + 4))
            assertEquals(messages, b.getInt(at + 4 + IngestMessageLayout.SEQUENCE))
            at += IngestRecordingLayout.LENGTH_PREFIX_BYTES + length
            messages++
        }
        assertEquals(b.capacity(), at)
        assertEquals(10, messages)
    }

    @Test
    fun `kotlin writer output equals the session event pull request and display goldens`() {
        val event = ByteBuffer.allocate(IngestMessageLayout.HEADER_BYTES + IngestMessageLayout.SESSION_EVENT_BYTES)
            .order(ByteOrder.LITTLE_ENDIAN)
        event.putInt(0, IngestMessageLayout.MAGIC)
        event.putShort(4, IngestMessageLayout.VERSION.toShort())
        event.put(IngestMessageLayout.KIND, IngestMessageLayout.KIND_SESSION_EVENT.toByte())
        event.putInt(IngestMessageLayout.SEQUENCE, 7)
        event.putInt(IngestMessageLayout.PAYLOAD_BYTES, IngestMessageLayout.SESSION_EVENT_BYTES)
        event.putLong(IngestMessageLayout.SAMPLE_TIMESTAMP_NS, 1_000_000_000L)
        event.putLong(IngestMessageLayout.FRAME_TIMESTAMP_NS, 1_170_000_000L)
        event.putLong(IngestMessageLayout.ACQUIRE_TIME_NS, 2_000_000_000L)
        event.putLong(IngestMessageLayout.SEND_TIME_NS, 2_000_500_000L)
        event.put(IngestMessageLayout.TRACKING_STATE, 1)
        event.put(IngestMessageLayout.POSE_MATCH, IngestMessageLayout.POSE_MATCH_INTERPOLATED.toByte())
        event.putShort(IngestMessageLayout.GROUP_EPOCH, 3)
        event.putInt(IngestMessageLayout.POSE_GAP_US, -1250)
        val pose = floatArrayOf(1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f, 0f, 0.5f, 1.25f, -2f, 1f)
        pose.forEachIndexed { i, v -> event.putFloat(IngestMessageLayout.CAMERA_TO_WORLD + 4 * i, v) }
        event.putInt(IngestMessageLayout.DROPPED, 2)
        val p = IngestMessageLayout.HEADER_BYTES
        event.put(p, IngestMessageLayout.SESSION_DEPTH_MODE_CHANGED.toByte())
        event.putLong(p + 8, 42L)
        assertArrayEquals(File(goldens, "ingest_session_event.bin").readBytes(), event.array())

        val pull = ByteBuffer.allocate(IngestMessageLayout.PULL_REQUEST_BYTES).order(ByteOrder.LITTLE_ENDIAN)
        pull.putInt(0, IngestMessageLayout.PULL_REQUEST_MAGIC).putShort(4, 1).put(6, 0x7e)
        assertArrayEquals(File(goldens, "ingest_pull_request.bin").readBytes(), pull.array())

        val display = golden("display_mode.bin")
        assertEquals(DisplayModeLayout.MAGIC, display.getInt(0))
        assertEquals(512, display.getShort(DisplayModeLayout.SPIKE_NEAREST_K).toInt())
        assertEquals(6.5f, display.getFloat(DisplayModeLayout.POINT_SIZE))
        val table = golden("colour_table.bin")
        assertEquals(ColourTableLayout.BYTES, table.capacity())
        assertEquals(3, table.getInt(ColourTableLayout.GENERATION))
        val control = golden("shell_control_start.bin")
        assertEquals(ShellControlLayout.KIND_START, control.get(ShellControlLayout.KIND).toInt())
        assertEquals(0.12f, control.getFloat(ShellControlLayout.MARKER_SIZE_METRES))
    }
}
