package com.uhg0.ar_flutter_plugin_2.shared_camera.camera

import org.junit.Assert.assertEquals
import org.junit.Test

class CameraIntrinsicsDeriverTest {
    @Test
    fun `zero vendor principal point falls back to active array center`() {
        val intrinsics =
            CameraIntrinsicsDeriver.derive(
                BaseCameraIntrinsics(
                    fx = 3000.0,
                    fy = 3000.0,
                    cx = 0.0,
                    cy = 0.0,
                    activeArrayWidth = 4000,
                    activeArrayHeight = 3000,
                    distortionCoefficients = emptyList(),
                ),
                outputWidth = 2000,
                outputHeight = 1500,
            )

        val principalPoint = intrinsics["principalPoint"] as Map<*, *>
        assertEquals(1000.0, principalPoint["cx"] as Double, 0.0001)
        assertEquals(750.0, principalPoint["cy"] as Double, 0.0001)
    }

    @Test
    fun `derives crop corrected intrinsics from active array`() {
        val intrinsics =
            CameraIntrinsicsDeriver.derive(
                baseIntrinsics =
                    BaseCameraIntrinsics(
                        fx = 3080.4,
                        fy = 3081.1,
                        cx = 2016.0,
                        cy = 1512.0,
                        activeArrayWidth = 4032,
                        activeArrayHeight = 3024,
                        distortionCoefficients = listOf(0.1, 0.01, 0.0, 0.0, 0.0),
                    ),
                outputWidth = 2016,
                outputHeight = 1512,
                cropRegion = CropRegionSpec(left = 504, top = 252, width = 3024, height = 2520),
            )

        val focalLength = intrinsics["focalLength"] as Map<*, *>
        val principalPoint = intrinsics["principalPoint"] as Map<*, *>
        val resolution = intrinsics["resolution"] as Map<*, *>
        val cropRegion = intrinsics["cropRegion"] as Map<*, *>

        assertEquals(2053.6, focalLength["fx"] as Double, 0.0001)
        assertEquals(1848.66, focalLength["fy"] as Double, 0.01)
        assertEquals(1008.0, principalPoint["cx"] as Double, 0.0001)
        assertEquals(756.0, principalPoint["cy"] as Double, 0.0001)
        assertEquals(2016, resolution["width"])
        assertEquals(1512, resolution["height"])
        assertEquals(504, cropRegion["left"])
        assertEquals(252, cropRegion["top"])
        assertEquals(3024, cropRegion["width"])
        assertEquals(2520, cropRegion["height"])
    }

    @Test
    fun `uses full active array when crop region is absent`() {
        val intrinsics =
            CameraIntrinsicsDeriver.derive(
                baseIntrinsics =
                    BaseCameraIntrinsics(
                        fx = 3080.4,
                        fy = 3081.1,
                        cx = 2016.0,
                        cy = 1512.0,
                        activeArrayWidth = 4032,
                        activeArrayHeight = 3024,
                        distortionCoefficients = emptyList(),
                    ),
                outputWidth = 1008,
                outputHeight = 756,
            )

        val focalLength = intrinsics["focalLength"] as Map<*, *>
        val principalPoint = intrinsics["principalPoint"] as Map<*, *>
        val cropRegion = intrinsics["cropRegion"] as Map<*, *>

        assertEquals(770.1, focalLength["fx"] as Double, 0.0001)
        assertEquals(770.275, focalLength["fy"] as Double, 0.0001)
        assertEquals(504.0, principalPoint["cx"] as Double, 0.0001)
        assertEquals(378.0, principalPoint["cy"] as Double, 0.0001)
        assertEquals(0, cropRegion["left"])
        assertEquals(0, cropRegion["top"])
        assertEquals(4032, cropRegion["width"])
        assertEquals(3024, cropRegion["height"])
    }
}
