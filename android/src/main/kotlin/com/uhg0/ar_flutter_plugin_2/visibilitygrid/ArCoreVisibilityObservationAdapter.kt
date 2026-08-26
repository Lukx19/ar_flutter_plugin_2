package com.uhg0.ar_flutter_plugin_2.visibilitygrid

import com.google.ar.core.Config
import com.google.ar.core.Frame
import com.google.ar.core.TrackingState
import com.google.ar.core.exceptions.NotYetAvailableException

internal sealed interface VisibilityFeatureCopyResult {
    data class Observation(val value: VisibilityFeatureObservation) : VisibilityFeatureCopyResult

    data object TransientUnavailable : VisibilityFeatureCopyResult

    data class Rejected(val reason: String) : VisibilityFeatureCopyResult
}

internal sealed interface VisibilityDepthCopyResult {
    data class Observation(val value: VisibilityDepthObservation) : VisibilityDepthCopyResult

    data object TransientUnavailable : VisibilityDepthCopyResult

    data class Rejected(val reason: String) : VisibilityDepthCopyResult
}

/** Copies all ARCore-owned data before returning to the frame callback. */
internal class ArCoreVisibilityObservationAdapter(
    private val resourceAcquired: () -> Unit,
    private val resourceClosed: () -> Unit,
    private val minimumFeatureConfidence: Double = 0.30,
) {
    init {
        require(minimumFeatureConfidence.isFinite() && minimumFeatureConfidence in 0.0..1.0)
    }

    fun copyFeature(
        frame: Frame,
        ownership: VisibilityObservationOwnership,
        depthCapability: VisibilityDepthCapability,
        frameSequence: Long,
    ): VisibilityFeatureCopyResult {
        val pointCloud = try {
            frame.acquirePointCloud().also { resourceAcquired() }
        } catch (_: NotYetAvailableException) {
            return VisibilityFeatureCopyResult.TransientUnavailable
        } catch (error: RuntimeException) {
            return VisibilityFeatureCopyResult.Rejected(error.message ?: "point cloud acquisition failed")
        }
        return try {
            val sourceTimestamp = pointCloud.timestamp
            if (sourceTimestamp <= 0 || frame.timestamp <= 0) {
                return VisibilityFeatureCopyResult.Rejected("feature timestamp is not positive")
            }
            val ids = pointCloud.ids.duplicate()
            val points = pointCloud.points.duplicate()
            if (points.remaining() < ids.remaining() * 4) {
                return VisibilityFeatureCopyResult.Rejected("feature buffers have mismatched lengths")
            }
            val accepted = ArrayList<VisibilityFeatureSample>(V2_FEATURE_SAMPLE_CAPACITY)
            val seen = HashSet<Int>(V2_FEATURE_SAMPLE_CAPACITY)
            var rejected = 0
            while (ids.hasRemaining() && points.remaining() >= 4) {
                val sample = VisibilityFeatureSample(
                    id = ids.get(),
                    xWorld = points.get().toDouble(),
                    yWorld = points.get().toDouble(),
                    zWorld = points.get().toDouble(),
                    confidence = points.get().toDouble(),
                )
                if (!sample.isValid() || sample.confidence < minimumFeatureConfidence ||
                    !seen.add(sample.id) || accepted.size == V2_FEATURE_SAMPLE_CAPACITY
                ) {
                    rejected++
                } else {
                    accepted += sample
                }
            }
            if (accepted.isEmpty()) {
                VisibilityFeatureCopyResult.Rejected("feature observation has no valid samples")
            } else {
                val copied = VisibilityFeatureObservation.copySamples(accepted)
                VisibilityFeatureCopyResult.Observation(
                    VisibilityFeatureObservation(
                        ownership = ownership,
                        frame = cameraFrame(
                            frame = frame,
                            source = VisibilityObservationSource.ARCORE_FEATURE,
                            sourceTimestampNs = sourceTimestamp,
                            frameSequence = frameSequence,
                            depthCapability = depthCapability,
                        ),
                        samples = copied,
                        sourceRejectedSamples = rejected,
                        payloadBytes = VisibilityFeatureObservation.FEATURE_FIXED_BYTES +
                            copied.size * VisibilityFeatureObservation.FEATURE_SAMPLE_BYTES,
                    ),
                )
            }
        } catch (error: IllegalArgumentException) {
            VisibilityFeatureCopyResult.Rejected(error.message ?: "invalid feature metadata")
        } catch (error: RuntimeException) {
            VisibilityFeatureCopyResult.Rejected(error.message ?: "feature copy failed")
        } finally {
            try {
                pointCloud.release()
            } finally {
                resourceClosed()
            }
        }
    }

    fun copyDepth(
        frame: Frame,
        ownership: VisibilityObservationOwnership,
        depthCapability: VisibilityDepthCapability,
        frameSequence: Long,
    ): VisibilityDepthCopyResult {
        if (depthCapability == VisibilityDepthCapability.UNSUPPORTED) {
            return VisibilityDepthCopyResult.Rejected("depth is unsupported")
        }
        return when (
            val result = ArCoreRawDepthSource(
                maxCopiedPixels = V2_DEPTH_SAMPLE_CAPACITY,
                onResourceAcquired = resourceAcquired,
                onResourceClosed = resourceClosed,
            ).acquire(frame, ownership.groupGeneration, ownership.sessionGeneration)
        ) {
            is DepthAcquisitionResult.Observation -> {
                try {
                    val source = result.value
                    val copied = VisibilityDepthObservation.copySamples(
                        source.samples.map {
                            VisibilityDepthSample(
                                x = it.x,
                                y = it.y,
                                depthMillimeters = it.depthMillimeters,
                                confidence = it.confidence,
                            )
                        },
                    )
                    if (copied.isEmpty()) {
                        VisibilityDepthCopyResult.Rejected("depth observation has no valid samples")
                    } else {
                        VisibilityDepthCopyResult.Observation(
                            VisibilityDepthObservation(
                                ownership = ownership,
                                frame = VisibilityObservationFrame(
                                    source = VisibilityObservationSource.ARCORE_RAW_DEPTH,
                                    frameSequence = frameSequence,
                                    frameTimestampNs = frame.timestamp,
                                    sourceTimestampNs = source.timestampNs,
                                    cameraIdentity = "arcore-rear-camera",
                                    tracking = source.tracking,
                                    imageOrientation = "landscape_right_x_right_y_down_v1",
                                    pose = VisibilityCameraPose.copyOf(source.worldFromCameraGl),
                                    intrinsics = VisibilityCameraIntrinsics(
                                        imageWidth = source.width,
                                        imageHeight = source.height,
                                        fx = source.intrinsics.fx,
                                        fy = source.intrinsics.fy,
                                        cx = source.intrinsics.cx,
                                        cy = source.intrinsics.cy,
                                    ),
                                    depthCapability = depthCapability,
                                ),
                                samples = copied,
                                sourceRejectedSamples = source.sourceRejectedPixels,
                                payloadBytes = VisibilityDepthObservation.DEPTH_FIXED_BYTES +
                                    copied.size * VisibilityDepthObservation.DEPTH_SAMPLE_BYTES,
                            ),
                        )
                    }
                } catch (error: IllegalArgumentException) {
                    VisibilityDepthCopyResult.Rejected(error.message ?: "invalid depth metadata")
                }
            }
            DepthAcquisitionResult.TransientUnavailable ->
                VisibilityDepthCopyResult.TransientUnavailable
            is DepthAcquisitionResult.Failure -> VisibilityDepthCopyResult.Rejected(result.reason)
        }
    }

    private fun cameraFrame(
        frame: Frame,
        source: VisibilityObservationSource,
        sourceTimestampNs: Long,
        frameSequence: Long,
        depthCapability: VisibilityDepthCapability,
    ): VisibilityObservationFrame {
        val intrinsics = frame.camera.imageIntrinsics
        val dimensions = intrinsics.imageDimensions
        val focal = intrinsics.focalLength
        val principal = intrinsics.principalPoint
        val pose = FloatArray(16)
        frame.camera.pose.toMatrix(pose, 0)
        return VisibilityObservationFrame(
            source = source,
            frameSequence = frameSequence,
            frameTimestampNs = frame.timestamp,
            sourceTimestampNs = sourceTimestampNs,
            cameraIdentity = "arcore-rear-camera",
            tracking = frame.camera.trackingState == TrackingState.TRACKING,
            imageOrientation = "landscape_right_x_right_y_down_v1",
            pose = VisibilityCameraPose.copyOf(DoubleArray(16) { pose[it].toDouble() }),
            intrinsics = VisibilityCameraIntrinsics(
                imageWidth = dimensions[0],
                imageHeight = dimensions[1],
                fx = focal[0].toDouble(),
                fy = focal[1].toDouble(),
                cx = principal[0].toDouble(),
                cy = principal[1].toDouble(),
            ),
            depthCapability = depthCapability,
        )
    }
}

internal fun Config.DepthMode.toVisibilityDepthCapability(): VisibilityDepthCapability = when (this) {
    Config.DepthMode.RAW_DEPTH_ONLY -> VisibilityDepthCapability.RAW_DEPTH
    Config.DepthMode.AUTOMATIC -> VisibilityDepthCapability.AUTOMATIC
    Config.DepthMode.DISABLED -> VisibilityDepthCapability.UNSUPPORTED
}

/** Live AR callback adapter. It has no picture, guidance, selector, or renderer dependency. */
internal class ArCoreVisibilityObservationSource(
    private val runtime: AndroidVisibilityGridRuntime,
    private val ownership: () -> VisibilityObservationOwnership?,
    private val depthMode: () -> Config.DepthMode,
) {
    private var frameSequence = 0L
    private val adapter = ArCoreVisibilityObservationAdapter(
        resourceAcquired = runtime::recordProducerResourceAcquired,
        resourceClosed = runtime::recordProducerResourceClosed,
    )

    fun onFrame(frame: Frame) {
        if (runtime.isSyntheticSource()) return
        val cut = ownership() ?: return
        if (frame.camera.trackingState != TrackingState.TRACKING || frame.timestamp <= 0) return
        val sequence = frameSequence++
        val capability = depthMode().toVisibilityDepthCapability()
        runtime.setDepthCapability(capability)
        if (runtime.shouldCopyFeature(frame.timestamp)) {
            val started = System.nanoTime()
            when (val result = adapter.copyFeature(frame, cut, capability, sequence)) {
                is VisibilityFeatureCopyResult.Observation ->
                    runtime.offerFeature(result.value, System.nanoTime() - started)
                VisibilityFeatureCopyResult.TransientUnavailable ->
                    runtime.recordFeatureTransientUnavailable()
                is VisibilityFeatureCopyResult.Rejected -> runtime.recordFeatureFailure()
            }
        }
        if (runtime.shouldCopyDepth(frame.timestamp)) {
            val started = System.nanoTime()
            when (val result = adapter.copyDepth(frame, cut, capability, sequence)) {
                is VisibilityDepthCopyResult.Observation ->
                    runtime.offerDepth(result.value, System.nanoTime() - started)
                VisibilityDepthCopyResult.TransientUnavailable ->
                    runtime.recordDepthTransientUnavailable()
                is VisibilityDepthCopyResult.Rejected -> runtime.recordDepthFailure()
            }
        }
    }
}
