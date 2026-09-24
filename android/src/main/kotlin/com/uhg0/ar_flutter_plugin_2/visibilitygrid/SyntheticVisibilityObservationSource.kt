package com.uhg0.ar_flutter_plugin_2.visibilitygrid

/** Deterministic copied-source emitter shared by JVM and debug emulator gates. */
internal class SyntheticVisibilityObservationSource(
    private val runtime: AndroidVisibilityGridRuntime,
    private val ownership: () -> VisibilityObservationOwnership?,
) {
    private var sequence = 0L
    private var worldFromCameraGl = identityVisibilityGridTransform()
    private var groupFromCameraGl = identityVisibilityGridTransform()

    /** Anchors feature geometry to the active group and depth to one exact AR world pose. */
    fun anchor(worldFromCameraGl: DoubleArray, groupFrame: VisibilityGroupFrame) {
        VisibilityCameraPose.copyOf(worldFromCameraGl)
        this.worldFromCameraGl = worldFromCameraGl.copyOf()
        groupFromCameraGl = compose(groupFrame.groupFromWorldGl, worldFromCameraGl)
        VisibilityCameraPose.copyOf(groupFromCameraGl)
    }

    fun setDepthCapability(capability: VisibilityDepthCapability) {
        runtime.configureSyntheticSource(capability)
    }

    fun emitFeature(
        timestampNs: Long,
        marker: Int = 0,
        lateralMarker: Int = marker,
    ): Boolean {
        val cut = ownership() ?: return false
        val frame = syntheticFrame(
            source = VisibilityObservationSource.SYNTHETIC_FEATURE,
            timestampNs = timestampNs,
        )
        val local = localToGroup(lateralMarker.toDouble() / 100.0, 0.0, -1.0)
        val samples = VisibilityFeatureObservation.copySamples(
            listOf(
                VisibilityFeatureSample(
                    id = marker.coerceAtLeast(0),
                    xWorld = local[0],
                    yWorld = local[1],
                    zWorld = local[2],
                    confidence = 1.0,
                ),
            ),
        )
        return runtime.offerFeature(
            VisibilityFeatureObservation(
                ownership = cut,
                frame = frame,
                samples = samples,
                sourceRejectedSamples = 0,
                payloadBytes = VisibilityFeatureObservation.FEATURE_FIXED_BYTES +
                    samples.size * VisibilityFeatureObservation.FEATURE_SAMPLE_BYTES,
            ),
        )
    }

    fun emitDepth(
        timestampNs: Long,
        marker: Int = 0,
        lateralMarker: Int = marker,
    ): Boolean {
        val cut = ownership() ?: return false
        val frame = syntheticFrame(
            source = VisibilityObservationSource.SYNTHETIC_DEPTH,
            timestampNs = timestampNs,
        )
        val samples = VisibilityDepthObservation.copySamples(
            listOf(
                VisibilityDepthSample(
                    x = SYNTHETIC_PRINCIPAL_X + lateralMarker.coerceAtLeast(0) * 20,
                    y = SYNTHETIC_PRINCIPAL_Y,
                    depthMillimeters = SYNTHETIC_DEPTH_MILLIMETERS,
                    confidence = 255,
                ),
            ),
        )
        return runtime.offerDepth(
            VisibilityDepthObservation(
                ownership = cut,
                frame = frame,
                samples = samples,
                sourceRejectedSamples = 0,
                payloadBytes = VisibilityDepthObservation.DEPTH_FIXED_BYTES +
                    samples.size * VisibilityDepthObservation.DEPTH_SAMPLE_BYTES,
            ),
        )
    }

    private fun syntheticFrame(
        source: VisibilityObservationSource,
        timestampNs: Long,
    ): VisibilityObservationFrame = VisibilityObservationFrame(
        source = source,
        frameSequence = sequence++,
        frameTimestampNs = timestampNs,
        sourceTimestampNs = timestampNs,
        cameraIdentity = "synthetic-camera",
        tracking = true,
        imageOrientation = "landscape_right_x_right_y_down_v1",
        pose = VisibilityCameraPose.copyOf(
            if (source == VisibilityObservationSource.SYNTHETIC_FEATURE) {
                groupFromCameraGl
            } else {
                worldFromCameraGl
            },
        ),
        intrinsics = VisibilityCameraIntrinsics(
            imageWidth = SYNTHETIC_IMAGE_WIDTH,
            imageHeight = SYNTHETIC_IMAGE_HEIGHT,
            fx = SYNTHETIC_FOCAL_LENGTH,
            fy = SYNTHETIC_FOCAL_LENGTH,
            cx = SYNTHETIC_PRINCIPAL_X.toDouble(),
            cy = SYNTHETIC_PRINCIPAL_Y.toDouble(),
        ),
        depthCapability = runtime.snapshot().depthCapability,
    )

    private fun localToGroup(x: Double, y: Double, z: Double): DoubleArray = doubleArrayOf(
        groupFromCameraGl[0] * x + groupFromCameraGl[4] * y + groupFromCameraGl[8] * z + groupFromCameraGl[12],
        groupFromCameraGl[1] * x + groupFromCameraGl[5] * y + groupFromCameraGl[9] * z + groupFromCameraGl[13],
        groupFromCameraGl[2] * x + groupFromCameraGl[6] * y + groupFromCameraGl[10] * z + groupFromCameraGl[14],
    )

    private fun compose(left: List<Double>, right: DoubleArray): DoubleArray {
        require(left.size == 16 && right.size == 16)
        return DoubleArray(16) { offset ->
            val column = offset / 4
            val row = offset % 4
            var value = 0.0
            for (index in 0 until 4) {
                value += left[index * 4 + row] * right[column * 4 + index]
            }
            require(value.isFinite())
            value
        }
    }

    private companion object {
        const val SYNTHETIC_IMAGE_WIDTH = 1280
        const val SYNTHETIC_IMAGE_HEIGHT = 960
        const val SYNTHETIC_FOCAL_LENGTH = 2000.0
        const val SYNTHETIC_PRINCIPAL_X = 640
        const val SYNTHETIC_PRINCIPAL_Y = 480
        const val SYNTHETIC_DEPTH_MILLIMETERS = 1000
    }
}
