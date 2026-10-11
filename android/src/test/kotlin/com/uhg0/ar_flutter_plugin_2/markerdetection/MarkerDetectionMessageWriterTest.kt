package com.uhg0.ar_flutter_plugin_2.markerdetection

import com.uhg0.ar_flutter_plugin_2.visibilitycontract.IngestMessageLayout
import com.uhg0.ar_flutter_plugin_2.visibilitycontract.MarkerDetectionLayout
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The Kotlin marker message writer reproduces the Dart-written golden
 * `ingest_markers.bin` byte for byte (field values of
 * `test/visibility_grid_contract/contract_fixtures.dart`), and the tie-point
 * batch header keeps the offsets of the Dart `TiePointBatchLayout`.
 */
class MarkerDetectionMessageWriterTest {
    private val golden = File("../test_assets/visibility_grid/ingest_markers.bin").readBytes()

    @Test
    fun `writer reproduces the marker golden`() {
        val writer = MarkerDetectionMessageWriter(maxRecords = 4)
        val records = golden.copyOfRange(MarkerDetectionMessageWriter.RECORDS_OFFSET, golden.size)
        assertEquals(2 * MarkerDetectionLayout.BYTES, records.size)
        for (i in records.indices) writer.buffer.put(MarkerDetectionMessageWriter.RECORDS_OFFSET + i, records[i])
        val h = MarkerDetectionMessageWriter.Header().apply {
            sequence = 7
            sampleTimestampNs = 1_000_000_000L
            frameTimestampNs = 1_170_000_000L
            acquireTimeNs = 2_000_000_000L
            sendTimeNs = 2_000_500_000L
            trackingState = 1
            poseMatch = IngestMessageLayout.POSE_MATCH_INTERPOLATED
            groupEpoch = 3
            poseGapUs = -1250
            dropped = 2
            floatArrayOf(1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f, 0f, 0.5f, 1.25f, -2f, 1f)
                .copyInto(cameraToWorld)
        }
        // A previous message must not leak into the next one.
        writer.buffer.put(IngestMessageLayout.RESERVED, 99)
        val length = writer.finish(h, 2, 640, 480)
        assertEquals(golden.size, length)
        val written = ByteArray(length)
        for (i in 0 until length) written[i] = writer.buffer.get(i)
        assertArrayEquals(golden, written)
    }

    @Test
    fun `tie point batch header offsets`() {
        val b = ByteBuffer.allocate(TiePointBatchLayout.HEADER_BYTES + 64).order(ByteOrder.LITTLE_ENDIAN)
        val id = ByteArray(32) { (it + 1).toByte() }
        val length = TiePointBatchLayout.writeHeader(b, 1, id, 4128, 3096, 3, 123_456_789L, 0, 81.5f)
        assertEquals(128, length)
        assertEquals(0x42543343, b.getInt(0)) // "C3TB"
        assertEquals(1, b.getShort(4).toInt())
        assertEquals(1, b.getShort(6).toInt())
        assertEquals(1, b.get(8).toInt())
        assertEquals(32, b.get(39).toInt())
        assertEquals(4128, b.getShort(40).toInt())
        assertEquals(3096, b.getShort(42).toInt())
        assertEquals(3, b.getShort(44).toInt())
        assertEquals(0, b.getShort(46).toInt())
        assertEquals(123_456_789L, b.getLong(48))
        assertEquals(81.5f, b.getFloat(56))
    }
}
