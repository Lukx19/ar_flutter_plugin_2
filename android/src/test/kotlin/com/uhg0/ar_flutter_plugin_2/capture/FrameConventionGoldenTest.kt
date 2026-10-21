package com.uhg0.ar_flutter_plugin_2.capture

import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.float
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Canonical camera frame golden (`opencv_c2w_v1`) through the Android pose
 * encoders: `test_assets/visibility_grid/frame_convention_golden.json`,
 * written by `scripts/geometry/frame_convention_golden.py` and read by the
 * Swift `FrameConventionGoldenTests` and the Dart
 * `frame_convention_golden_test.dart` too. Each case is an ARCore or ARKit
 * style tracking camera pose; `T_world_cam = T_world_arcam * diag(1,-1,-1,1)`.
 */
class FrameConventionGoldenTest {
    private val golden: JsonObject = Json.parseToJsonElement(
        File("../test_assets/visibility_grid/frame_convention_golden.json").readText(),
    ).jsonObject
    private val cases = golden["cases"]!!.jsonArray.map { it.jsonObject }

    private fun JsonObject.floats(name: String): FloatArray =
        this[name]!!.jsonArray.map { it.jsonPrimitive.float }.toFloatArray()

    private fun JsonObject.bits(name: String): IntArray =
        this[name]!!.jsonArray.map { java.lang.Long.parseLong(it.jsonPrimitive.content, 16).toInt() }.toIntArray()

    private fun trackedPose(case: JsonObject): PoseDataExtractor.CachedPose {
        val tracking = case["tracking"]!!.jsonObject
        val timestampNs = case["timestampNs"]!!.jsonPrimitive.long
        val transform = tracking.bits("transformBits").map { Float.fromBits(it) }.toFloatArray()
        return PoseDataExtractor.CachedPose(
            position = tracking.floats("position"),
            rotationQuaternion = tracking.floats("rotation"),
            transform = transform,
            timestampNs = timestampNs,
            systemTimestampMs = case["systemTimestampMs"]!!.jsonPrimitive.long,
            isTracking = true,
            confidence = 1f,
            trackingState = "tracking",
        )
    }

    @Test
    fun `packed pose samples equal the golden bytes for ARCore and ARKit style poses`() {
        assertTrue(cases.any { it["platform"]!!.jsonPrimitive.content == "arcore" })
        assertTrue(cases.any { it["platform"]!!.jsonPrimitive.content == "arkit" })
        for (case in cases) {
            val name = case["name"]!!.jsonPrimitive.content
            val extractor = PoseDataExtractor(2)
            extractor.addSample(trackedPose(case))
            val packed = extractor.latestPacked(case["sequence"]!!.jsonPrimitive.long)!!
            val expected = case["packedSampleHex"]!!.jsonPrimitive.content
                .chunked(2).map { it.toInt(16).toByte() }.toByteArray()
            assertArrayEquals(name, expected, packed)
        }
    }

    @Test
    fun `still capture pose map is the golden OpenCV transform with its convention tag`() {
        for (case in cases) {
            val name = case["name"]!!.jsonPrimitive.content
            val extractor = PoseDataExtractor(2)
            val pose = trackedPose(case)
            val map = extractor.toPoseMap(extractor.toAlignedPose(pose))
            assertEquals(name, "opencv_c2w_v1", map["convention"])
            val transform = (map["transform"] as List<*>).map { (it as Double).toFloat() }
            val expected = case["openCv"]!!.jsonObject.bits("transformBits")
            assertArrayEquals(name, expected, transform.map { it.toBits() }.toIntArray())
            val rotation = map["rotation"] as Map<*, *>
            val expectedRotation = case["openCv"]!!.jsonObject.bits("rotationBits")
            for ((i, axis) in listOf("x", "y", "z", "w").withIndex()) {
                assertEquals("$name $axis", expectedRotation[i], (rotation[axis] as Double).toFloat().toBits())
            }
        }
    }

    @Test
    fun `encoded poses project points ahead to positive depth with image y downward`() {
        for (case in cases) {
            val name = case["name"]!!.jsonPrimitive.content
            val extractor = PoseDataExtractor(2)
            extractor.addSample(trackedPose(case))
            val packed = java.nio.ByteBuffer.wrap(extractor.latestPacked(1L)!!)
                .order(java.nio.ByteOrder.LITTLE_ENDIAN)
            val t = DoubleArray(16) { packed.getFloat(80 + it * 4).toDouble() }
            val k = case["intrinsics"]!!.jsonObject
            val fx = k["fx"]!!.jsonPrimitive.double
            val fy = k["fy"]!!.jsonPrimitive.double
            val cx = k["cx"]!!.jsonPrimitive.double
            val cy = k["cy"]!!.jsonPrimitive.double
            for (row in case["projections"]!!.jsonArray.map { it.jsonObject }) {
                val label = "$name ${row["label"]!!.jsonPrimitive.content}"
                val world = row["world"]!!.jsonArray.map { it.jsonPrimitive.double }
                val d = DoubleArray(3) { world[it] - t[12 + it] }
                // Camera point R^T (p - c): row c of R^T is column c of T.
                val camera = DoubleArray(3) { c -> (0..2).sumOf { r -> t[c * 4 + r] * d[r] } }
                val expectedCamera = row["camera"]!!.jsonArray.map { it.jsonPrimitive.double }
                for (i in 0..2) assertEquals(label, expectedCamera[i], camera[i], 1e-9)
                val pixel = row["pixel"]?.jsonArray?.map { it.jsonPrimitive.double }
                if (pixel == null) {
                    assertTrue("$label is behind the camera", camera[2] < 0)
                    continue
                }
                assertTrue("$label is in front of the camera", camera[2] > 0)
                assertEquals(label, pixel[0], fx * camera[0] / camera[2] + cx, 1e-6)
                assertEquals(label, pixel[1], fy * camera[1] / camera[2] + cy, 1e-6)
            }
            val rows = case["projections"]!!.jsonArray.map { it.jsonObject }
                .associateBy { it["label"]!!.jsonPrimitive.content }
            val up = rows["ahead_2m_world_up"]!!["pixel"]!!.jsonArray.map { it.jsonPrimitive.double }
            when (case["worldUpInImage"]!!.jsonPrimitive.content) {
                "v_decreases" -> assertTrue("$name world up is image up", up[1] < cy)
                "u_decreases" -> assertTrue("$name portrait: world up is image left", up[0] < cx)
            }
        }
    }
}
