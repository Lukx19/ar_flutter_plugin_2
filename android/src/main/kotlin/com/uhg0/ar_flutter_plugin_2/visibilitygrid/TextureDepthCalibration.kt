package com.uhg0.ar_flutter_plugin_2.visibilitygrid

/** A temporary invalid SDK calibration must not consume the terminal failure threshold. */
internal class DepthCalibrationUnavailableException(reason: String) : IllegalArgumentException(reason)

private fun requireAvailableDepthCalibration(available: Boolean, reason: String) {
    if (!available) throw DepthCalibrationUnavailableException(reason)
}

/**
 * Depth pixels use the camera's unrotated GPU-texture axes. This follows the
 * ARCore raw_depth_java PointCloudHelper sample, rather than resizing the CPU
 * image (whose crop can differ). Integer x/y are the kernel's pixel convention.
 * https://github.com/google-ar/arcore-android-sdk/blob/master/samples/raw_depth_java/app/src/main/java/com/google/ar/core/examples/java/rawdepth/PointCloudHelper.java
 */
internal fun depthIntrinsicsFromTexture(
    depthWidth: Int,
    depthHeight: Int,
    textureWidth: Int,
    textureHeight: Int,
    fx: Double,
    fy: Double,
    cx: Double,
    cy: Double,
): DepthIntrinsics {
    requireAvailableDepthCalibration(depthWidth > 0 && depthHeight > 0 && textureWidth > 0 && textureHeight > 0,
        "invalid depth texture calibration dimensions")
    requireAvailableDepthCalibration(fx.isFinite() && fx > 0.0 && fy.isFinite() && fy > 0.0 && cx.isFinite() && cy.isFinite(),
        "invalid depth texture calibration scalars")
    val scaleX = depthWidth.toDouble() / textureWidth
    val scaleY = depthHeight.toDouble() / textureHeight
    val scaledFx = fx * scaleX
    val scaledFy = fy * scaleY
    val scaledCx = cx * scaleX
    val scaledCy = cy * scaleY
    requireAvailableDepthCalibration(scaledFx.isFinite() && scaledFx > 0.0 && scaledFy.isFinite() && scaledFy > 0.0 &&
        scaledCx.isFinite() && scaledCy.isFinite(), "invalid scaled depth texture calibration")
    return DepthIntrinsics(scaledFx, scaledFy, scaledCx, scaledCy)
}

/** Freezes primitives while the acquisition Frame and Camera are still current. */
internal fun freezeTextureDepthMetadata(
    frameTimestampNs: Long,
    groupGeneration: Long,
    sessionGeneration: Long,
    tracking: Boolean,
    depthWidth: Int,
    depthHeight: Int,
    textureDimensions: IntArray,
    textureFocal: FloatArray,
    texturePrincipal: FloatArray,
    worldFromCameraGl: FloatArray,
    sourceImageDimensions: IntArray,
    acquisitionMode: DepthAcquisitionMode,
    cameraImageTimestampNs: Long = 0L,
): RawDepthFrameMetadata {
    requireAvailableDepthCalibration(textureDimensions.size == 2 && textureFocal.size == 2 && texturePrincipal.size == 2, "invalid texture calibration shape")
    requireAvailableDepthCalibration(sourceImageDimensions.size == 2 && sourceImageDimensions.all { it > 0 },
        "invalid CPU source image dimensions")
    requireAvailableDepthCalibration(worldFromCameraGl.size == 16 && worldFromCameraGl.all(Float::isFinite),
        "invalid acquisition camera pose")
    return RawDepthFrameMetadata(
        timestampNs = frameTimestampNs,
        groupGeneration = groupGeneration,
        sessionGeneration = sessionGeneration,
        tracking = tracking,
        width = depthWidth,
        height = depthHeight,
        intrinsics = depthIntrinsicsFromTexture(
            depthWidth, depthHeight, textureDimensions[0], textureDimensions[1],
            textureFocal[0].toDouble(), textureFocal[1].toDouble(),
            texturePrincipal[0].toDouble(), texturePrincipal[1].toDouble(),
        ),
        worldFromCameraGl = DoubleArray(16) { worldFromCameraGl[it].toDouble() },
        imageOrientation = DepthImageOrientation.LANDSCAPE_RIGHT,
        arFrameTimestampNs = frameTimestampNs,
        cameraImageTimestampNs = cameraImageTimestampNs,
        // Preserve these fields' CPU-image meaning; texture dimensions are used
        // only for the immutable four-scalar calibration above.
        sourceImageWidth = sourceImageDimensions[0],
        sourceImageHeight = sourceImageDimensions[1],
        acquisitionMode = acquisitionMode,
    )
}
