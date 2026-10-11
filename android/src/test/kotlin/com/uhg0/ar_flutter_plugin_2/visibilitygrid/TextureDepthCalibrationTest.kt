package com.uhg0.ar_flutter_plugin_2.visibilitygrid

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class TextureDepthCalibrationTest {
    @Test
    fun `offset CPU crop and asymmetric focal lengths preserve texture rays`() {
        // Texture covers CPU x=80..880, y=30..430: an off-center 800x400
        // region inside a 1280x960 image, not a full CPU-image resize.
        val texture = depthIntrinsicsFromTexture(200, 100, 800, 400, 600.0, 500.0, 260.0, 180.0)
        assertEquals(DepthIntrinsics(150.0, 125.0, 65.0, 45.0), texture)
        val legacyCpuResize = depthIntrinsicsFromTexture(200, 100, 1280, 960, 600.0, 500.0, 340.0, 210.0)
        for ((x, y) in listOf(0 to 0, 65 to 45, 181 to 17, 199 to 99)) {
            val mappedCpuX = 80.0 + x * 4.0
            val mappedCpuY = 30.0 + y * 4.0
            assertEquals((mappedCpuX - 340.0) / 600.0, (x - texture.cx) / texture.fx, 1e-12)
            assertEquals((mappedCpuY - 210.0) / 500.0, (y - texture.cy) / texture.fy, 1e-12)
        }
        assertEquals(0.0, (65 - texture.cx) / texture.fx, 0.0)
        assertEquals(0.0, (45 - texture.cy) / texture.fy, 0.0)
        assertNotEquals(0.0, (65 - legacyCpuResize.cx) / legacyCpuResize.fx, 1e-6)
    }

    @Test
    fun `consecutive acquisitions freeze calibration pose and CPU dimensions independently`() {
        val dimensions = intArrayOf(800, 400)
        val focal = floatArrayOf(600f, 500f)
        val principal = floatArrayOf(260f, 180f)
        val sourceDimensions = intArrayOf(1280, 960)
        val pose = identity()
        val first = snapshot(10L, dimensions, focal, principal, pose, sourceDimensions)
        dimensions[0] = 1600
        focal[0] = 800f
        principal[0] = 600f
        sourceDimensions[0] = 1920
        pose[12] = 0.75f
        val second = snapshot(11L, dimensions, focal, principal, pose, sourceDimensions)
        pose[12] = 9f
        assertEquals(DepthIntrinsics(150.0, 125.0, 65.0, 45.0), first.intrinsics)
        assertEquals(DepthIntrinsics(100.0, 125.0, 75.0, 45.0), second.intrinsics)
        assertEquals(10L, first.arFrameTimestampNs)
        assertEquals(11L, second.arFrameTimestampNs)
        assertEquals(1280, first.sourceImageWidth)
        assertEquals(1920, second.sourceImageWidth)
        assertArrayEquals(DoubleArray(16) { identity()[it].toDouble() }, first.worldFromCameraGl, 0.0)
        assertEquals(0.75, second.worldFromCameraGl[12], 0.0)
        assertEquals(DepthImageOrientation.LANDSCAPE_RIGHT, first.imageOrientation)
    }

    @Test
    fun `invalid dimensions nonfinite scalars and degenerate focal lengths refuse calibration`() {
        for (dimensions in listOf(intArrayOf(0, 100, 800, 400), intArrayOf(200, -1, 800, 400),
            intArrayOf(200, 100, 0, 400), intArrayOf(200, 100, 800, 0))) {
            assertThrows(IllegalArgumentException::class.java) {
                depthIntrinsicsFromTexture(dimensions[0], dimensions[1], dimensions[2], dimensions[3], 600.0, 500.0, 260.0, 180.0)
            }
        }
        for (invalid in listOf(0.0, -1.0, Double.NaN, Double.POSITIVE_INFINITY)) {
            assertThrows(IllegalArgumentException::class.java) {
                depthIntrinsicsFromTexture(200, 100, 800, 400, invalid, 500.0, 260.0, 180.0)
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            depthIntrinsicsFromTexture(200, 100, 800, 400, 600.0, 500.0, Double.NaN, 180.0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            snapshot(10L, intArrayOf(800, 400), floatArrayOf(600f, 500f), floatArrayOf(260f, 180f),
                identity().also { it[12] = Float.NaN }, intArrayOf(1280, 960))
        }
    }

    @Test
    fun `frozen calibration keeps Android image identity independent from AR pose time`() {
        val metadata = freezeTextureDepthMetadata(
            10L, 3L, 5L, true, 200, 100, intArrayOf(800, 400),
            floatArrayOf(600f, 500f), floatArrayOf(260f, 180f), identity(),
            intArrayOf(1280, 960), DepthAcquisitionMode.AUTOMATIC,
            cameraImageTimestampNs = 20L,
        )
        assertEquals(10L, metadata.arFrameTimestampNs)
        assertEquals(20L, metadata.cameraImageTimestampNs)
        assertEquals(150.0, metadata.intrinsics.fx, 0.0)
        assertEquals(1280, metadata.sourceImageWidth)
    }

    private fun snapshot(timestamp: Long, dimensions: IntArray, focal: FloatArray, principal: FloatArray,
        pose: FloatArray, sourceDimensions: IntArray) = freezeTextureDepthMetadata(
        timestamp, 3L, 5L, true, 200, 100, dimensions, focal, principal, pose,
        sourceDimensions, DepthAcquisitionMode.RAW,
    )

    private fun identity() = FloatArray(16) { if (it % 5 == 0) 1f else 0f }
}
