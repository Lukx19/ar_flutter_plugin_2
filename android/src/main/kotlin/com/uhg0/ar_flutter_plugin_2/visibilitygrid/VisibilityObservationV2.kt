package com.uhg0.ar_flutter_plugin_2.visibilitygrid

import java.util.Collections

internal const val VISIBILITY_OBSERVATION_VERSION = "visibility_observation_v2"
internal const val V2_FEATURE_SAMPLE_CAPACITY = 1_200
internal const val V2_DEPTH_SAMPLE_CAPACITY = 1_536
internal const val V2_SENSOR_HANDOFF_CAPACITY_BYTES = 1_048_576L

internal enum class VisibilityObservationSource(val wireName: String) {
    ARCORE_FEATURE("arcoreFeature"),
    ARCORE_RAW_DEPTH("arcoreRawDepth"),
    SYNTHETIC_FEATURE("syntheticFeature"),
    SYNTHETIC_DEPTH("syntheticDepth"),
}

internal enum class VisibilityDepthCapability(val wireName: String) {
    UNSUPPORTED("unsupported"),
    RAW_DEPTH("rawDepth"),
    AUTOMATIC("automatic"),
}

internal enum class VisibilitySourceHealth(val wireName: String) {
    CONFIGURED("configured"),
    HEALTHY("healthy"),
    TRANSIENT_UNAVAILABLE("transientUnavailable"),
    STALLED("stalled"),
    FAILED("failed"),
    UNSUPPORTED("unsupported"),
}

/** Immutable, platform-neutral identity of the V2 owner accepting one observation. */
internal data class VisibilityObservationOwnership(
    val sessionId: String,
    val sessionGeneration: Long,
    val captureGroupId: String,
    val groupGeneration: Long,
    val coverageEpoch: Long,
    val arSessionIdentity: String,
    val viewInstanceId: String,
    val viewGeneration: Long,
    val nativeStreamToken: String,
    val workerBindingToken: String,
    val bindingGeneration: Long,
    val lifecycleSequence: Long,
    val operationGeneration: Long,
) {
    init {
        require(sessionId.matches(HEX_128))
        require(captureGroupId.matches(HEX_128))
        require(arSessionIdentity.matches(HEX_128))
        require(viewInstanceId.matches(HEX_128))
        require(nativeStreamToken.matches(HEX_128))
        require(workerBindingToken.matches(HEX_128))
        require(sessionGeneration > 0)
        require(groupGeneration > 0)
        require(coverageEpoch > 0)
        require(viewGeneration > 0)
        require(bindingGeneration > 0)
        require(lifecycleSequence > 0)
        require(operationGeneration > 0)
    }

    private companion object {
        val HEX_128 = Regex("[0-9a-f]{32}")
    }
}

internal data class VisibilityCameraIntrinsics(
    val imageWidth: Int,
    val imageHeight: Int,
    val fx: Double,
    val fy: Double,
    val cx: Double,
    val cy: Double,
    val cropLeft: Int = 0,
    val cropTop: Int = 0,
    val cropWidth: Int = imageWidth,
    val cropHeight: Int = imageHeight,
) {
    init {
        require(imageWidth in 1..16_384 && imageHeight in 1..16_384)
        require(fx.isFinite() && fx > 0.0 && fx <= 65_535.0)
        require(fy.isFinite() && fy > 0.0 && fy <= 65_535.0)
        require(cx.isFinite() && cx in 0.0..imageWidth.toDouble())
        require(cy.isFinite() && cy in 0.0..imageHeight.toDouble())
        require(cropLeft >= 0 && cropTop >= 0 && cropWidth > 0 && cropHeight > 0)
        require(cropLeft + cropWidth <= imageWidth)
        require(cropTop + cropHeight <= imageHeight)
    }
}

/** Copies the matrix and exposes an unmodifiable value list. */
internal class VisibilityCameraPose private constructor(
    val worldFromCameraGl: List<Double>,
) {
    init {
        require(worldFromCameraGl.size == 16 && worldFromCameraGl.all(Double::isFinite))
        require(kotlin.math.abs(worldFromCameraGl[3]) <= 1e-6)
        require(kotlin.math.abs(worldFromCameraGl[7]) <= 1e-6)
        require(kotlin.math.abs(worldFromCameraGl[11]) <= 1e-6)
        require(kotlin.math.abs(worldFromCameraGl[15] - 1.0) <= 1e-6)
    }

    companion object {
        fun copyOf(values: DoubleArray): VisibilityCameraPose =
            VisibilityCameraPose(Collections.unmodifiableList(values.copyOf().toList()))
    }
}

internal data class VisibilityObservationFrame(
    val source: VisibilityObservationSource,
    val frameSequence: Long,
    val frameTimestampNs: Long,
    val sourceTimestampNs: Long,
    val cameraIdentity: String,
    val tracking: Boolean,
    val imageOrientation: String,
    val pose: VisibilityCameraPose,
    val intrinsics: VisibilityCameraIntrinsics,
    val depthCapability: VisibilityDepthCapability,
) {
    init {
        require(frameSequence >= 0)
        require(frameTimestampNs > 0 && sourceTimestampNs > 0)
        require(cameraIdentity.isNotBlank() && cameraIdentity.length <= 128)
        require(imageOrientation == "landscape_right_x_right_y_down_v1")
    }
}

internal data class VisibilityFeatureSample(
    val id: Int,
    val xWorld: Double,
    val yWorld: Double,
    val zWorld: Double,
    val confidence: Double,
) {
    fun isValid(): Boolean =
        id >= 0 && xWorld.isFinite() && yWorld.isFinite() && zWorld.isFinite() &&
            confidence.isFinite() && confidence in 0.0..1.0
}

internal data class VisibilityDepthSample(
    val x: Int,
    val y: Int,
    val depthMillimeters: Int,
    val confidence: Int,
)

internal class VisibilityFeatureObservation(
    val version: String = VISIBILITY_OBSERVATION_VERSION,
    val ownership: VisibilityObservationOwnership,
    val frame: VisibilityObservationFrame,
    samples: List<VisibilityFeatureSample>,
    val sourceRejectedSamples: Int,
    val payloadBytes: Int,
) {
    val samples: List<VisibilityFeatureSample> = copySamples(samples)

    init {
        require(version == VISIBILITY_OBSERVATION_VERSION)
        require(frame.source == VisibilityObservationSource.ARCORE_FEATURE ||
            frame.source == VisibilityObservationSource.SYNTHETIC_FEATURE)
        require(samples.isNotEmpty() && samples.size <= V2_FEATURE_SAMPLE_CAPACITY)
        require(samples.all(VisibilityFeatureSample::isValid))
        require(samples.map(VisibilityFeatureSample::id).distinct().size == samples.size)
        require(sourceRejectedSamples >= 0)
        require(payloadBytes == FEATURE_FIXED_BYTES + samples.size * FEATURE_SAMPLE_BYTES)
    }

    companion object {
        const val FEATURE_FIXED_BYTES = 512
        const val FEATURE_SAMPLE_BYTES = 32

        fun copySamples(samples: List<VisibilityFeatureSample>): List<VisibilityFeatureSample> =
            Collections.unmodifiableList(ArrayList(samples))
    }
}

internal class VisibilityDepthObservation(
    val version: String = VISIBILITY_OBSERVATION_VERSION,
    val ownership: VisibilityObservationOwnership,
    val frame: VisibilityObservationFrame,
    samples: List<VisibilityDepthSample>,
    val sourceRejectedSamples: Int,
    val payloadBytes: Int,
) {
    val samples: List<VisibilityDepthSample> = copySamples(samples)

    init {
        require(version == VISIBILITY_OBSERVATION_VERSION)
        require(frame.source == VisibilityObservationSource.ARCORE_RAW_DEPTH ||
            frame.source == VisibilityObservationSource.SYNTHETIC_DEPTH)
        require(samples.size <= V2_DEPTH_SAMPLE_CAPACITY)
        require(samples.all {
            it.x in 0 until frame.intrinsics.imageWidth &&
                it.y in 0 until frame.intrinsics.imageHeight &&
                it.depthMillimeters in 0..65_535 && it.confidence in 0..255
        })
        require(sourceRejectedSamples >= 0)
        require(payloadBytes == DEPTH_FIXED_BYTES + samples.size * DEPTH_SAMPLE_BYTES)
    }

    companion object {
        const val DEPTH_FIXED_BYTES = 512
        const val DEPTH_SAMPLE_BYTES = 16

        fun copySamples(samples: List<VisibilityDepthSample>): List<VisibilityDepthSample> =
            Collections.unmodifiableList(ArrayList(samples))
    }
}

internal interface VisibilityObservationMapper : AutoCloseable {
    fun admitFeature(observation: VisibilityFeatureObservation)

    fun admitDepth(observation: VisibilityDepthObservation)

    fun snapshot(): VisibilityMappingAdmissionHealth = VisibilityMappingAdmissionHealth.empty()

    override fun close() = Unit
}
