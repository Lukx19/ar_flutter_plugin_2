package com.uhg0.ar_flutter_plugin_2.markerdetection

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

/**
 * The synthetic marker scene (`benchmarks/marker_detection/synthetic_scene`,
 * bundled as androidTest assets `marker_scene/`) through the JNI library:
 * per view `nativeDetect` (detection and pose on the grey image) and
 * `nativeDetectTiePointsBitmap` (the photo path on the decoded bitmap), with
 * the detector configuration of the ground truth. Writes the records in the
 * shared format `capture3d-marker-scene-records-v1` to
 * `<external files>/marker_scene_records_android.txt` (path logged under
 * `MarkerSceneRecords`); `marker_scene_records.py check` and `compare` judge
 * them against the ground truth and the iOS records. Here: the decoded
 * pixels equal the generator's, no call fails and no id outside the view's
 * markers is reported. Needs `libcapture3d_marker_detector.so`
 * (CAPTURE3D_GRID_MARKERS); skipped by assumption otherwise.
 */
@RunWith(AndroidJUnit4::class)
class MarkerSceneRecordsInstrumentedTest {
    private fun hex(buffer: ByteBuffer, offset: Int): String {
        val sb = StringBuilder(128)
        for (i in 0 until 64) sb.append(String.format("%02x", buffer.get(offset + i).toInt() and 0xFF))
        return sb.toString()
    }

    @Test
    fun writesSceneRecordsThroughTheJniPath() {
        assumeTrue("marker detector library not packaged", MarkerDetectorNative.available)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val assets = instrumentation.context.assets
        val truth = JSONObject(assets.open("marker_scene/ground_truth.json").bufferedReader().readText())
        val config = truth.getJSONObject("detector")
        val handle = MarkerDetectorNative.nativeCreate(config.getInt("dictionary"))
        assertNotEquals(0L, handle)
        MarkerDetectorNative.nativeSetMarkerSize(handle, config.getDouble("defaultMarkerSizeMetres"))
        val sizes = config.getJSONObject("markerSizeByIdMetres")
        for (key in sizes.keys()) MarkerDetectorNative.nativeSetMarkerSizeForId(handle, key.toInt(), sizes.getDouble(key))
        val max = MarkerDetectorNative.MAX_MARKERS
        val out = ByteBuffer.allocateDirect(max * 64).order(ByteOrder.LITTLE_ENDIAN)
        val lines = mutableListOf(FORMAT, "platform android ${android.os.Build.MODEL} ${android.os.Build.SUPPORTED_ABIS[0]}")
        val views = truth.getJSONArray("views")
        val options = BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inScaled = false
            inPremultiplied = false
        }
        for (v in 0 until views.length()) {
            val view = views.getJSONObject(v)
            val file = view.getString("file")
            val bitmap = assets.open("marker_scene/$file").use { BitmapFactory.decodeStream(it, null, options) }!!
            val w = bitmap.width
            val h = bitmap.height
            val pixels = IntArray(w * h)
            bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
            val greyBytes = ByteArray(w * h)
            for (i in pixels.indices) {
                val p = pixels[i]
                val r = (p shr 16) and 0xFF
                assertTrue("$file pixel $i is not grey", r == (p shr 8) and 0xFF && r == p and 0xFF)
                greyBytes[i] = r.toByte()
            }
            val sha = MessageDigest.getInstance("SHA-256").digest(greyBytes).joinToString("") { "%02x".format(it) }
            assertEquals("$file decoded pixels", view.getString("greySha256"), sha)
            val grey = ByteBuffer.allocateDirect(w * h)
            grey.put(greyBytes)
            grey.rewind()
            val camera = view.getJSONObject("camera")
            MarkerDetectorNative.nativeSetCamera(
                handle, camera.getDouble("fx"), camera.getDouble("fy"), camera.getDouble("cx"), camera.getDouble("cy"), null,
            )
            val ids = HashSet<Int>()
            val markers = view.getJSONArray("markers")
            for (m in 0 until markers.length()) ids.add(markers.getJSONObject(m).getInt("id"))
            lines.add("view $file $sha")
            val detections = MarkerDetectorNative.nativeDetect(handle, grey, w, h, w, out, 0, max)
            assertTrue("$file detection status $detections", detections >= 0)
            lines.add("detections $detections")
            for (k in 0 until detections) {
                val id = out.getShort(64 * k).toInt() and 0xFFFF
                assertTrue("$file detection of id $id outside the view", id in ids)
                lines.add(hex(out, 64 * k))
            }
            val ties = MarkerDetectorNative.nativeDetectTiePointsBitmap(
                handle, bitmap, config.getInt("tiePointSearchDownscale"), config.getInt("tiePointRefinement"), out, 0, max,
            )
            assertTrue("$file tie point status $ties", ties >= 0)
            lines.add("tiepoints $ties")
            for (k in 0 until ties) {
                val id = out.getShort(64 * k).toInt() and 0xFFFF
                assertTrue("$file tie point of id $id outside the view", id in ids)
                lines.add(hex(out, 64 * k))
            }
            lines.add("end")
            bitmap.recycle()
        }
        MarkerDetectorNative.nativeDestroy(handle)
        val target = File(instrumentation.targetContext.getExternalFilesDir(null), RECORDS_FILE)
        target.writeText(lines.joinToString("\n") + "\n")
        Log.i("MarkerSceneRecords", "wrote ${views.length()} views to ${target.absolutePath}")
    }

    companion object {
        const val FORMAT = "capture3d-marker-scene-records-v1"
        const val RECORDS_FILE = "marker_scene_records_android.txt"
    }
}
