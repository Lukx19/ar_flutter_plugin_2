package com.uhg0.ar_flutter_plugin_2.visibilitygrid

/** Deterministic copied-source emitter shared by JVM and debug emulator gates. */
internal class SyntheticVisibilityObservationSource(
    private val runtime: AndroidVisibilityGridRuntime,
    private val ownership: () -> VisibilityObservationOwnership?,
) {
    private var sequence = 0L

    fun setDepthCapability(capability: VisibilityDepthCapability) {
        runtime.configureSyntheticSource(capability)
    }

    fun emitFeature(timestampNs: Long, marker: Int = 0): Boolean {
        val cut = ownership() ?: return false
        val frame = syntheticFrame(
            source = VisibilityObservationSource.SYNTHETIC_FEATURE,
            timestampNs = timestampNs,
        )
        val samples = VisibilityFeatureObservation.copySamples(
            listOf(
                VisibilityFeatureSample(
                    id = marker.coerceAtLeast(0),
                    xWorld = marker.toDouble() / 100.0,
                    yWorld = 0.0,
                    zWorld = -1.0,
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

    fun emitDepth(timestampNs: Long, marker: Int = 0): Boolean {
        val cut = ownership() ?: return false
        val frame = syntheticFrame(
            source = VisibilityObservationSource.SYNTHETIC_DEPTH,
            timestampNs = timestampNs,
        )
        val samples = VisibilityDepthObservation.copySamples(
            listOf(
                VisibilityDepthSample(
                    x = marker.mod(16),
                    y = marker.mod(12),
                    depthMillimeters = 1_000 + marker.coerceAtLeast(0),
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
        pose = VisibilityCameraPose.copyOf(identityVisibilityGridTransform()),
        intrinsics = VisibilityCameraIntrinsics(
            imageWidth = 16,
            imageHeight = 12,
            fx = 10.0,
            fy = 10.0,
            cx = 8.0,
            cy = 6.0,
        ),
        depthCapability = runtime.snapshot().depthCapability,
    )
}
