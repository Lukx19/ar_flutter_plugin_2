package com.uhg0.ar_flutter_plugin_2.markerdetection

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.uhg0.ar_flutter_plugin_2.visibilitycontract.IngestMessageLayout
import com.uhg0.ar_flutter_plugin_2.visibilitycontract.MarkerDetectionLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.hypot

/**
 * Marker detection on rendered ARUCO_MIP_36h12 bitmaps through the JNI
 * library: live detection messages at 640x480 and 1920x1080 and full
 * resolution tie points at 4128x3096. Logs detection times under the tag
 * `MarkerDetectorTest`. Needs `libcapture3d_marker_detector.so` (built with
 * CAPTURE3D_GRID_MARKERS); skipped by assumption otherwise.
 */
@RunWith(AndroidJUnit4::class)
class MarkerDetectorInstrumentedTest {
    /** Dictionary codes of ids 0-7 (36 bits, row-major, 1 = white). */
    private val codes = longArrayOf(
        0xd2b63a09dL, 0x6001134e5L, 0x1206fbe72L, 0xff8ad6cb4L,
        0x85da9bc49L, 0xb461afe9cL, 0x6db51fe13L, 0x5248c541fL,
    )

    private class Placed(val id: Int, val corners: FloatArray)

    /** Draws marker [id] warped onto [quad] (4 corners, aruco order, canvas px). */
    private fun draw(canvas: Canvas, id: Int, quad: FloatArray) {
        val modules = 8
        val src = floatArrayOf(0f, 0f, modules.toFloat(), 0f, modules.toFloat(), modules.toFloat(), 0f, modules.toFloat())
        val m = Matrix()
        m.setPolyToPoly(src, 0, quad, 0, 4)
        canvas.save()
        canvas.concat(m)
        val black = Paint().apply { color = Color.rgb(35, 35, 35); isAntiAlias = true }
        val white = Paint().apply { color = Color.rgb(215, 215, 215); isAntiAlias = true }
        canvas.drawRect(-1.5f, -1.5f, modules + 1.5f, modules + 1.5f, white)
        canvas.drawRect(0f, 0f, modules.toFloat(), modules.toFloat(), black)
        for (r in 0 until 6) {
            for (c in 0 until 6) {
                val bit = (codes[id] shr (35 - (r * 6 + c))) and 1L
                if (bit == 1L) {
                    canvas.drawRect((c + 1).toFloat(), (r + 1).toFloat(), (c + 2).toFloat(), (r + 2).toFloat(), white)
                }
            }
        }
        canvas.restore()
    }

    private fun scene(width: Int, height: Int, side: Float): Pair<Bitmap, List<Placed>> {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.rgb(120, 125, 118))
        val placed = mutableListOf<Placed>()
        val cols = 4
        for (id in 0 until 8) {
            val cx = width * (id % cols + 0.5f) / cols
            val cy = height * (id / cols + 0.5f) / 2
            val h = side / 2
            // A mild perspective: the right edge 10 % shorter.
            val quad = floatArrayOf(cx - h, cy - h, cx + h, cy - h * 0.9f, cx + h, cy + h * 0.9f, cx - h, cy + h)
            draw(canvas, id, quad)
            // Detector coordinates put pixel centres at integers.
            placed.add(Placed(id, FloatArray(8) { quad[it] - 0.5f }))
        }
        return bitmap to placed
    }

    private fun grey(bitmap: Bitmap): ByteBuffer {
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        val out = ByteBuffer.allocateDirect(pixels.size)
        for (i in pixels.indices) {
            val p = pixels[i]
            out.put(i, ((77 * Color.red(p) + 150 * Color.green(p) + 29 * Color.blue(p)) shr 8).toByte())
        }
        return out
    }

    private fun detectLive(width: Int, height: Int, side: Float) {
        assumeTrue("marker detector library not packaged", MarkerDetectorNative.available)
        val (bitmap, placed) = scene(width, height, side)
        var message: ByteBuffer? = null
        var length = 0
        val producer = MarkerDetectionProducer({ m, l ->
            val copy = ByteBuffer.allocate(l).order(ByteOrder.LITTLE_ENDIAN)
            for (i in 0 until l) copy.put(i, m.get(i))
            message = copy
            length = l
        })
        producer.configure(markerSizeMetres = 0.1)
        val frame = GreyFrame()
        frame.ensureCapacity(width * height)
        frame.grey.put(grey(bitmap))
        frame.grey.rewind()
        frame.width = width
        frame.height = height
        frame.stride = width
        frame.header.fx = width * 0.8f
        frame.header.fy = width * 0.8f
        frame.header.cx = width / 2f - 0.5f
        frame.header.cy = height / 2f - 0.5f
        val times = DoubleArray(20)
        var count = 0
        for (repeat in times.indices) {
            val t0 = SystemClock.elapsedRealtimeNanos()
            count = producer.produce(frame)
            times[repeat] = (SystemClock.elapsedRealtimeNanos() - t0) / 1e6
        }
        times.sort()
        Log.i("MarkerDetectorTest", "${width}x$height: $count markers, p50 ${times[10]} ms, max ${times[19]} ms, " +
            "native detect ${producer.timings[0]} ms, pose ${producer.timings[1]} ms")
        assertEquals(8, count)
        val b = message!!
        assertEquals(IngestMessageLayout.KIND_MARKER_DETECTIONS, b.get(IngestMessageLayout.KIND).toInt())
        assertEquals(length, IngestMessageLayout.HEADER_BYTES + b.getInt(IngestMessageLayout.PAYLOAD_BYTES))
        for (i in 0 until count) {
            val at = MarkerDetectionMessageWriter.RECORDS_OFFSET + i * MarkerDetectionLayout.BYTES
            val id = b.getShort(at + MarkerDetectionLayout.MARKER_ID).toInt()
            val truth = placed.first { it.id == id }
            for (k in 0 until 4) {
                val dx = b.getFloat(at + MarkerDetectionLayout.CORNERS + 8 * k) - truth.corners[2 * k]
                val dy = b.getFloat(at + MarkerDetectionLayout.CORNERS + 8 * k + 4) - truth.corners[2 * k + 1]
                assertTrue("marker $id corner $k off by ${hypot(dx, dy)} px", hypot(dx, dy) < 1.0)
            }
            assertTrue(b.getFloat(at + MarkerDetectionLayout.TVEC + 8) > 0f)
            assertEquals(0, b.get(at + MarkerDetectionLayout.FLAGS).toInt())
        }
        producer.close()
    }

    @Test
    fun detectsRenderedMarkersAt640x480() = detectLive(640, 480, 90f)

    @Test
    fun detectsRenderedMarkersAt1920x1080() = detectLive(1920, 1080, 220f)

    @Test
    fun tiePointsOnRenderedTwelveMegapixelStill() {
        assumeTrue("marker detector library not packaged", MarkerDetectorNative.available)
        val (bitmap, placed) = scene(4128, 3096, 600f)
        val handle = MarkerDetectorNative.nativeCreate(MarkerDetectorNative.DICTIONARY_ARUCO_MIP_36H12)
        MarkerDetectorNative.nativeSetCamera(handle, 2980.0, 2980.0, 2063.5, 1547.5, null)
        MarkerDetectorNative.nativeSetMarkerSize(handle, 0.1)
        val out = ByteBuffer.allocateDirect(MarkerDetectorNative.MAX_MARKERS * MarkerDetectorNative.TIE_POINT_BYTES)
            .order(ByteOrder.LITTLE_ENDIAN)
        val t0 = SystemClock.elapsedRealtimeNanos()
        val count = MarkerDetectorNative.nativeDetectTiePointsBitmap(
            handle, bitmap, 1, MarkerDetectorNative.REFINE_EDGE_LINES, out, 0, MarkerDetectorNative.MAX_MARKERS,
        )
        val ms = (SystemClock.elapsedRealtimeNanos() - t0) / 1e6
        val timings = DoubleArray(3)
        MarkerDetectorNative.nativeTimings(handle, timings)
        Log.i("MarkerDetectorTest", "12 MP tie points: $count in $ms ms (detect ${timings[0]}, refine ${timings[2]})")
        assertEquals(8, count)
        for (i in 0 until count) {
            val at = i * MarkerDetectorNative.TIE_POINT_BYTES
            val id = out.getShort(at).toInt()
            val truth = placed.first { it.id == id }
            for (k in 0 until 4) {
                val dx = out.getFloat(at + 4 + 8 * k) - truth.corners[2 * k]
                val dy = out.getFloat(at + 8 + 8 * k) - truth.corners[2 * k + 1]
                assertTrue("tie $id corner $k off by ${hypot(dx, dy)} px", hypot(dx, dy) < 0.5)
            }
        }
        MarkerDetectorNative.nativeDestroy(handle)
        bitmap.recycle()
    }
}
