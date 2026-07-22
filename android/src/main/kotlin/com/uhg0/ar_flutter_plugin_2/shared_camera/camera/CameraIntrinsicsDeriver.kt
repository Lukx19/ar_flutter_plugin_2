package com.uhg0.ar_flutter_plugin_2.shared_camera.camera

import android.graphics.Rect
import android.util.Size
import kotlin.math.atan

internal data class BaseCameraIntrinsics(
    val fx: Double,
    val fy: Double,
    val cx: Double,
    val cy: Double,
    val activeArrayWidth: Int,
    val activeArrayHeight: Int,
    val distortionCoefficients: List<Double>,
)

internal data class CropRegionSpec(
    val left: Int,
    val top: Int,
    val width: Int,
    val height: Int,
)

internal object CameraIntrinsicsDeriver {
    fun derive(
        baseIntrinsics: BaseCameraIntrinsics,
        outputSize: Size,
        cropRegion: Rect? = null,
    ): Map<String, Any> =
        derive(
            baseIntrinsics = baseIntrinsics,
            outputWidth = outputSize.width,
            outputHeight = outputSize.height,
            cropRegion =
                cropRegion?.let {
                    CropRegionSpec(
                        left = it.left,
                        top = it.top,
                        width = it.width(),
                        height = it.height(),
                    )
                },
        )

    fun derive(
        baseIntrinsics: BaseCameraIntrinsics,
        outputWidth: Int,
        outputHeight: Int,
        cropRegion: CropRegionSpec? = null,
    ): Map<String, Any> {
        val appliedCrop =
            cropRegion ?: CropRegionSpec(
                left = 0,
                top = 0,
                width = baseIntrinsics.activeArrayWidth,
                height = baseIntrinsics.activeArrayHeight,
            )

        val cropWidth = appliedCrop.width.toDouble()
        val cropHeight = appliedCrop.height.toDouble()
        val scaleX = outputWidth.toDouble() / cropWidth
        val scaleY = outputHeight.toDouble() / cropHeight
        // Some Camera2 HALs advertise LENS_INTRINSIC_CALIBRATION but return a
        // zero principal point. Treat values on/outside the active-array edge
        // as invalid calibration rather than emitting unusable intrinsics.
        val baseCx =
            baseIntrinsics.cx.takeIf { it > 0.0 && it < baseIntrinsics.activeArrayWidth.toDouble() }
                ?: baseIntrinsics.activeArrayWidth / 2.0
        val baseCy =
            baseIntrinsics.cy.takeIf { it > 0.0 && it < baseIntrinsics.activeArrayHeight.toDouble() }
                ?: baseIntrinsics.activeArrayHeight / 2.0
        val correctedFx = baseIntrinsics.fx * scaleX
        val correctedFy = baseIntrinsics.fy * scaleY
        val correctedCx = (baseCx - appliedCrop.left) * scaleX
        val correctedCy = (baseCy - appliedCrop.top) * scaleY
        val fovHorizontal = 2.0 * atan(outputWidth.toDouble() / (2.0 * correctedFx))
        val fovVertical = 2.0 * atan(outputHeight.toDouble() / (2.0 * correctedFy))

        return mapOf(
            "focalLength" to mapOf(
                "fx" to correctedFx,
                "fy" to correctedFy,
            ),
            "principalPoint" to mapOf(
                "cx" to correctedCx,
                "cy" to correctedCy,
            ),
            "resolution" to mapOf(
                "width" to outputWidth,
                "height" to outputHeight,
            ),
            "cropRegion" to mapOf(
                "left" to appliedCrop.left,
                "top" to appliedCrop.top,
                "width" to appliedCrop.width,
                "height" to appliedCrop.height,
            ),
            "distortionCoefficients" to baseIntrinsics.distortionCoefficients,
            "fieldOfView" to mapOf(
                "horizontal" to fovHorizontal,
                "vertical" to fovVertical,
            ),
        )
    }
}
