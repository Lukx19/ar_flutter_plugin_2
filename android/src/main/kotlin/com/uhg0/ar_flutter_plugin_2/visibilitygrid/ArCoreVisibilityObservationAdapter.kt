package com.uhg0.ar_flutter_plugin_2.visibilitygrid

import com.google.ar.core.Config
import com.google.ar.core.Frame
import com.google.ar.core.TrackingState
import com.google.ar.core.exceptions.NotYetAvailableException
import java.nio.FloatBuffer
import java.nio.IntBuffer

internal sealed interface VisibilityFeatureCopyResult {
    data class Observation(val value: VisibilityFeatureObservation) : VisibilityFeatureCopyResult

    /** Packed observation whose lease must be transferred or closed by the consumer. */
    data class PackedObservation(val value: VisibilityFeatureObservation) : VisibilityFeatureCopyResult

    data object TransientUnavailable : VisibilityFeatureCopyResult

    data class Rejected(val reason: String) : VisibilityFeatureCopyResult
}

internal sealed interface VisibilityDepthCopyResult {
    data class Observation(val value: VisibilityDepthObservation) : VisibilityDepthCopyResult

    data class PackedObservation(
        val metadata: RawDepthFrameMetadata,
        val lease: DepthSamplesLease,
        val ownership: VisibilityObservationOwnership,
        val depthCapability: VisibilityDepthCapability,
        val frameSequence: Long,
        val frameTimestampNs: Long,
    ) : VisibilityDepthCopyResult {
        fun toObservation(): VisibilityDepthObservation = VisibilityDepthObservation.fromPacked(
            ownership = ownership,
            frame = VisibilityObservationFrame(
                source = VisibilityObservationSource.ARCORE_RAW_DEPTH,
                frameSequence = frameSequence,
                frameTimestampNs = frameTimestampNs,
                sourceTimestampNs = metadata.timestampNs,
                cameraIdentity = "arcore-rear-camera",
                tracking = metadata.tracking,
                imageOrientation = "landscape_right_x_right_y_down_v1",
                pose = VisibilityCameraPose.copyOf(metadata.worldFromCameraGl),
                intrinsics = VisibilityCameraIntrinsics(
                    imageWidth = metadata.width,
                    imageHeight = metadata.height,
                    fx = metadata.intrinsics.fx,
                    fy = metadata.intrinsics.fy,
                    cx = metadata.intrinsics.cx,
                    cy = metadata.intrinsics.cy,
                ),
                depthCapability = depthCapability,
            ),
            packedSamples = lease.samples,
            sourceRejectedSamples = lease.samples.rejectedCount,
        )
    }

    data object TransientUnavailable : VisibilityDepthCopyResult

    data class Rejected(val reason: String) : VisibilityDepthCopyResult
}

internal sealed interface PreparedVisibilityDepthResult {
    data class Ready(val frame: PreparedVisibilityDepthFrame) : PreparedVisibilityDepthResult
    data object TransientUnavailable : PreparedVisibilityDepthResult
    data class Rejected(val reason: String) : PreparedVisibilityDepthResult
}

internal class PreparedVisibilityDepthFrame(
    val raw: PreparedRawDepthFrame,
    val ownership: VisibilityObservationOwnership,
    val depthCapability: VisibilityDepthCapability,
    val frameSequence: Long,
    val frameTimestampNs: Long,
) : AutoCloseable {
    override fun close() = raw.close()
}

internal sealed interface VisibilityFeatureSamplesCopyResult {
    data class Samples(
        val values: List<VisibilityFeatureSample>,
        val sourceRejectedSamples: Int,
    ) : VisibilityFeatureSamplesCopyResult

    data class Packed(val lease: FeatureSamplesLease) : VisibilityFeatureSamplesCopyResult

    data object TransientUnavailable : VisibilityFeatureSamplesCopyResult

    data class Rejected(val reason: String) : VisibilityFeatureSamplesCopyResult
}

internal fun copyVisibilityFeatureSamples(
    sourceIds: IntBuffer,
    sourcePoints: FloatBuffer,
    minimumFeatureConfidence: Double,
    maxCopiedSamples: Int,
): VisibilityFeatureSamplesCopyResult {
    require(minimumFeatureConfidence.isFinite() && minimumFeatureConfidence in 0.0..1.0)
    require(maxCopiedSamples in 1..V2_FEATURE_SAMPLE_CAPACITY)
    val ids = sourceIds.duplicate()
    val points = sourcePoints.duplicate()
    if (points.remaining().toLong() != ids.remaining().toLong() * 4L) {
        return VisibilityFeatureSamplesCopyResult.Rejected(
            "feature buffers have mismatched lengths",
        )
    }
    val accepted = ArrayList<VisibilityFeatureSample>(maxCopiedSamples)
    val seen = HashSet<Int>(maxCopiedSamples)
    var rejected = 0
    while (ids.hasRemaining()) {
        val sample = VisibilityFeatureSample(
            id = ids.get(),
            xWorld = points.get().toDouble(),
            yWorld = points.get().toDouble(),
            zWorld = points.get().toDouble(),
            confidence = points.get().toDouble(),
        )
        if (!sample.isValid() || sample.confidence < minimumFeatureConfidence ||
            !seen.add(sample.id) || accepted.size == maxCopiedSamples
        ) {
            rejected++
        } else {
            accepted += sample
        }
    }
    return if (accepted.isEmpty()) {
        VisibilityFeatureSamplesCopyResult.TransientUnavailable
    } else {
        VisibilityFeatureSamplesCopyResult.Samples(
            values = VisibilityFeatureObservation.copySamples(accepted),
            sourceRejectedSamples = rejected,
        )
    }
}

/** Copies point-cloud columns directly into a producer-owned lease. */
internal fun copyVisibilityFeatureSamplesPacked(
    sourceIds: IntBuffer,
    sourcePoints: FloatBuffer,
    minimumFeatureConfidence: Double,
    lease: FeatureSamplesLease,
    maxCopiedSamples: Int = V2_FEATURE_SAMPLE_CAPACITY,
): VisibilityFeatureSamplesCopyResult {
    require(minimumFeatureConfidence.isFinite() && minimumFeatureConfidence in 0.0..1.0)
    require(maxCopiedSamples in 1..V2_FEATURE_SAMPLE_CAPACITY)
    val ids = sourceIds.duplicate()
    val points = sourcePoints.duplicate()
    if (points.remaining().toLong() != ids.remaining().toLong() * 4L) {
        lease.close()
        return VisibilityFeatureSamplesCopyResult.Rejected("feature buffers have mismatched lengths")
    }
    lease.clear()
    var rejected = 0
    while (ids.hasRemaining()) {
        val id = ids.get()
        val x = points.get().toDouble()
        val y = points.get().toDouble()
        val z = points.get().toDouble()
        val confidence = points.get().toDouble()
        if (id < 0 || !x.isFinite() || !y.isFinite() || !z.isFinite() ||
            !confidence.isFinite() || confidence !in 0.0..1.0 ||
            confidence < minimumFeatureConfidence || lease.producerCountForFill() >= maxCopiedSamples ||
            !lease.acceptId(id) ||
            !lease.append(id, x, y, z, confidence)
        ) {
            rejected++
        }
    }
    lease.reject(rejected)
    return if (lease.samples.count == 0) {
        lease.close()
        VisibilityFeatureSamplesCopyResult.TransientUnavailable
    } else {
        VisibilityFeatureSamplesCopyResult.Packed(lease)
    }
}

/** Copies all ARCore-owned data before returning to the frame callback. */
internal class ArCoreVisibilityObservationAdapter(
    private val resourceAcquired: () -> Unit,
    private val resourceClosed: () -> Unit,
    private val minimumFeatureConfidence: Double = 0.30,
) {
    private val featureSamplePool = FeatureSamplesLeasePool()
    private val depthSamplePool = DepthSamplesLeasePool()

    init {
        require(minimumFeatureConfidence.isFinite() && minimumFeatureConfidence in 0.0..1.0)
    }

    fun copyFeature(
        frame: Frame,
        ownership: VisibilityObservationOwnership,
        depthCapability: VisibilityDepthCapability,
        frameSequence: Long,
        maxCopiedSamples: Int = V2_FEATURE_SAMPLE_CAPACITY,
    ): VisibilityFeatureCopyResult {
        require(maxCopiedSamples in 1..V2_FEATURE_SAMPLE_CAPACITY)
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
            when (
                val samples = copyVisibilityFeatureSamples(
                    sourceIds = pointCloud.ids,
                    sourcePoints = pointCloud.points,
                    minimumFeatureConfidence = minimumFeatureConfidence,
                    maxCopiedSamples = maxCopiedSamples,
                )
            ) {
                VisibilityFeatureSamplesCopyResult.TransientUnavailable ->
                    VisibilityFeatureCopyResult.TransientUnavailable
                is VisibilityFeatureSamplesCopyResult.Rejected ->
                    VisibilityFeatureCopyResult.Rejected(samples.reason)
                is VisibilityFeatureSamplesCopyResult.Packed -> {
                    samples.lease.close()
                    VisibilityFeatureCopyResult.Rejected("packed feature copy requires the packed adapter")
                }
                is VisibilityFeatureSamplesCopyResult.Samples ->
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
                            samples = samples.values,
                            sourceRejectedSamples = samples.sourceRejectedSamples,
                            payloadBytes = VisibilityFeatureObservation.FEATURE_FIXED_BYTES +
                                samples.values.size * VisibilityFeatureObservation.FEATURE_SAMPLE_BYTES,
                        ),
                    )
            }
        } catch (error: IllegalArgumentException) {
            VisibilityFeatureCopyResult.Rejected(error.message ?: "invalid feature metadata")
        } catch (error: RuntimeException) {
            VisibilityFeatureCopyResult.Rejected(error.message ?: "feature copy failed")
        } finally {
            pointCloud.release()
            resourceClosed()
        }
    }

    /** Packed production handoff; the immutable list path remains the fixture adapter. */
    fun copyFeaturePacked(
        frame: Frame,
        ownership: VisibilityObservationOwnership,
        depthCapability: VisibilityDepthCapability,
        frameSequence: Long,
        maxCopiedSamples: Int = V2_FEATURE_SAMPLE_CAPACITY,
    ): VisibilityFeatureCopyResult {
        require(maxCopiedSamples in 1..V2_FEATURE_SAMPLE_CAPACITY)
        val pointCloud = try {
            frame.acquirePointCloud().also { resourceAcquired() }
        } catch (_: NotYetAvailableException) {
            return VisibilityFeatureCopyResult.TransientUnavailable
        } catch (error: RuntimeException) {
            return VisibilityFeatureCopyResult.Rejected(error.message ?: "point cloud acquisition failed")
        }
        var lease: FeatureSamplesLease? = null
        return try {
            val sourceTimestamp = pointCloud.timestamp
            if (sourceTimestamp <= 0 || frame.timestamp <= 0) {
                return VisibilityFeatureCopyResult.Rejected("feature timestamp is not positive")
            }
            lease = featureSamplePool.tryAcquire(ownership)
                ?: return VisibilityFeatureCopyResult.Rejected("feature sample pool exhausted")
            when (
                val samples = copyVisibilityFeatureSamplesPacked(
                    sourceIds = pointCloud.ids,
                    sourcePoints = pointCloud.points,
                    minimumFeatureConfidence = minimumFeatureConfidence,
                    lease = lease,
                    maxCopiedSamples = maxCopiedSamples,
                )
            ) {
                VisibilityFeatureSamplesCopyResult.TransientUnavailable ->
                    VisibilityFeatureCopyResult.TransientUnavailable
                is VisibilityFeatureSamplesCopyResult.Rejected ->
                    VisibilityFeatureCopyResult.Rejected(samples.reason)
                is VisibilityFeatureSamplesCopyResult.Packed ->
                    VisibilityFeatureCopyResult.PackedObservation(
                        VisibilityFeatureObservation.fromPacked(
                            ownership = ownership,
                            frame = cameraFrame(
                                frame = frame,
                                source = VisibilityObservationSource.ARCORE_FEATURE,
                                sourceTimestampNs = sourceTimestamp,
                                frameSequence = frameSequence,
                                depthCapability = depthCapability,
                            ),
                            packedSamples = samples.lease.samples,
                            sourceRejectedSamples = samples.lease.samples.rejectedCount,
                        ),
                    )
                is VisibilityFeatureSamplesCopyResult.Samples ->
                    error("packed feature copy returned immutable samples")
            }
        } catch (error: IllegalArgumentException) {
            lease?.close()
            VisibilityFeatureCopyResult.Rejected(error.message ?: "invalid feature metadata")
        } catch (error: RuntimeException) {
            lease?.close()
            VisibilityFeatureCopyResult.Rejected(error.message ?: "feature copy failed")
        } finally {
            pointCloud.release()
            resourceClosed()
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
        val result = ArCoreRawDepthSource(
            maxCopiedPixels = V2_DEPTH_SAMPLE_CAPACITY,
            onResourceAcquired = resourceAcquired,
            onResourceClosed = resourceClosed,
            depthMode = depthCapability.toArCoreDepthMode(),
        ).acquire(frame, ownership.groupGeneration, ownership.sessionGeneration)
        return convertDepthResult(
            result, ownership, depthCapability, frameSequence, frame.timestamp,
        )
    }

    /** Packed production handoff; images are closed before the result returns. */
    fun copyDepthPacked(
        frame: Frame,
        ownership: VisibilityObservationOwnership,
        depthCapability: VisibilityDepthCapability,
        frameSequence: Long,
    ): VisibilityDepthCopyResult {
        if (depthCapability == VisibilityDepthCapability.UNSUPPORTED) {
            return VisibilityDepthCopyResult.Rejected("depth is unsupported")
        }
        val result = ArCoreRawDepthSource(
            maxCopiedPixels = V2_DEPTH_SAMPLE_CAPACITY,
            onResourceAcquired = resourceAcquired,
            onResourceClosed = resourceClosed,
            depthMode = depthCapability.toArCoreDepthMode(),
        ).acquirePacked(
            frame = frame,
            ownership = ownership,
            pool = depthSamplePool,
        )
        return when (result) {
            is PackedDepthAcquisitionResult.Observation ->
                VisibilityDepthCopyResult.PackedObservation(
                    result.metadata,
                    result.lease,
                    ownership,
                    depthCapability,
                    frameSequence,
                    frame.timestamp,
                )
            PackedDepthAcquisitionResult.TransientUnavailable ->
                VisibilityDepthCopyResult.TransientUnavailable
            is PackedDepthAcquisitionResult.Failure ->
                VisibilityDepthCopyResult.Rejected(result.reason)
        }
    }

    fun prepareDepth(
        frame: Frame,
        ownership: VisibilityObservationOwnership,
        depthCapability: VisibilityDepthCapability,
        frameSequence: Long,
    ): PreparedVisibilityDepthResult {
        if (depthCapability == VisibilityDepthCapability.UNSUPPORTED) {
            return PreparedVisibilityDepthResult.Rejected("depth is unsupported")
        }
        return when (val result = ArCoreRawDepthSource(
            maxCopiedPixels = V2_DEPTH_SAMPLE_CAPACITY,
            onResourceAcquired = resourceAcquired,
            onResourceClosed = resourceClosed,
            depthMode = depthCapability.toArCoreDepthMode(),
        ).prepare(frame, ownership.groupGeneration, ownership.sessionGeneration)) {
            is PreparedRawDepthResult.Ready -> PreparedVisibilityDepthResult.Ready(
                PreparedVisibilityDepthFrame(
                    result.frame, ownership, depthCapability, frameSequence, frame.timestamp,
                ),
            )
            PreparedRawDepthResult.TransientUnavailable ->
                PreparedVisibilityDepthResult.TransientUnavailable
            is PreparedRawDepthResult.Failure -> PreparedVisibilityDepthResult.Rejected(result.reason)
        }
    }

    fun finishDepth(prepared: PreparedVisibilityDepthFrame): VisibilityDepthCopyResult =
        convertDepthResult(
            prepared.raw.process(), prepared.ownership, prepared.depthCapability,
            prepared.frameSequence, prepared.frameTimestampNs,
        )

    fun finishDepthPacked(prepared: PreparedVisibilityDepthFrame): VisibilityDepthCopyResult =
        when (val result = prepared.raw.processPacked(
            depthSamplePool,
            SampleLeaseGeneration.from(prepared.ownership),
        )) {
            is PackedDepthAcquisitionResult.Observation ->
                VisibilityDepthCopyResult.PackedObservation(
                    result.metadata,
                    result.lease,
                    prepared.ownership,
                    prepared.depthCapability,
                    prepared.frameSequence,
                    prepared.frameTimestampNs,
                )
            PackedDepthAcquisitionResult.TransientUnavailable ->
                VisibilityDepthCopyResult.TransientUnavailable
            is PackedDepthAcquisitionResult.Failure ->
                VisibilityDepthCopyResult.Rejected(result.reason)
        }

    internal fun closePackedPools() {
        featureSamplePool.close()
        depthSamplePool.close()
    }

    /** Debug-only ownership receipt; it never acquires or retains a lease. */
    internal fun packedLeaseReceipts(): Map<String, SampleLeasePoolReceipt> = mapOf(
        "feature" to featureSamplePool.receipt(),
        "depth" to depthSamplePool.receipt(),
    )

    private fun convertDepthResult(
        result: DepthAcquisitionResult,
        ownership: VisibilityObservationOwnership,
        depthCapability: VisibilityDepthCapability,
        frameSequence: Long,
        frameTimestampNs: Long,
    ): VisibilityDepthCopyResult = when (result) {
        is DepthAcquisitionResult.Observation -> convertDepthObservationResult(
            result.value, ownership, depthCapability, frameSequence, frameTimestampNs,
        )
        DepthAcquisitionResult.TransientUnavailable ->
            VisibilityDepthCopyResult.TransientUnavailable
        is DepthAcquisitionResult.Failure -> VisibilityDepthCopyResult.Rejected(result.reason)
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

    private fun rawDepthMetadata(
        frame: Frame,
        ownership: VisibilityObservationOwnership,
        width: Int,
        height: Int,
    ): RawDepthFrameMetadata {
        val intrinsics = frame.camera.imageIntrinsics
        val focal = intrinsics.focalLength
        val principal = intrinsics.principalPoint
        val dimensions = intrinsics.imageDimensions
        val pose = FloatArray(16)
        frame.camera.pose.toMatrix(pose, 0)
        val scaleX = width.toDouble() / dimensions[0]
        val scaleY = height.toDouble() / dimensions[1]
        return RawDepthFrameMetadata(
            timestampNs = frame.timestamp,
            groupGeneration = ownership.groupGeneration,
            sessionGeneration = ownership.sessionGeneration,
            tracking = frame.camera.trackingState == TrackingState.TRACKING,
            width = width,
            height = height,
            intrinsics = DepthIntrinsics(
                fx = focal[0] * scaleX,
                fy = focal[1] * scaleY,
                cx = principal[0] * scaleX,
                cy = principal[1] * scaleY,
            ),
            worldFromCameraGl = DoubleArray(16) { pose[it].toDouble() },
        )
    }
}

/**
 * Converts a copied ARCore depth observation while preserving the transient
 * nature of an all-invalid frame. Motion-based ARCore depth can legitimately
 * return an image whose depth/confidence pixels are all zero until parallax
 * establishes a prediction; that frame must not terminally disable depth.
 */
internal fun convertDepthObservationResult(
    source: DepthObservation,
    ownership: VisibilityObservationOwnership,
    depthCapability: VisibilityDepthCapability,
    frameSequence: Long,
    frameTimestampNs: Long,
): VisibilityDepthCopyResult {
    return try {
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
            VisibilityDepthCopyResult.TransientUnavailable
        } else {
            VisibilityDepthCopyResult.Observation(
                VisibilityDepthObservation(
                    ownership = ownership,
                    frame = VisibilityObservationFrame(
                        source = VisibilityObservationSource.ARCORE_RAW_DEPTH,
                        frameSequence = frameSequence,
                        frameTimestampNs = frameTimestampNs,
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

private fun VisibilityDepthCapability.toArCoreDepthMode(): Config.DepthMode = when (this) {
    VisibilityDepthCapability.RAW_DEPTH -> Config.DepthMode.RAW_DEPTH_ONLY
    VisibilityDepthCapability.AUTOMATIC -> Config.DepthMode.AUTOMATIC
    VisibilityDepthCapability.UNSUPPORTED -> Config.DepthMode.DISABLED
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
    private val depthIntakeAllowed: () -> Boolean = { true },
) {
    private var frameSequence = 0L
    private val adapter = ArCoreVisibilityObservationAdapter(
        resourceAcquired = runtime::recordProducerResourceAcquired,
        resourceClosed = runtime::recordProducerResourceClosed,
    )
    private val depthProcessor = BoundedDepthObservationProcessor<
        PreparedVisibilityDepthFrame, VisibilityDepthCopyResult
    >(
        process = adapter::finishDepthPacked,
        publish = { result, callbackCopyNs ->
            when (result) {
                is VisibilityDepthCopyResult.Observation ->
                    runtime.offerDepth(result.value, callbackCopyNs)
                is VisibilityDepthCopyResult.PackedObservation -> {
                    runtime.offerDepth(result.toObservation(), callbackCopyNs)
                }
                VisibilityDepthCopyResult.TransientUnavailable -> {
                    runtime.recordDepthProcessingTransient()
                    runtime.recordDepthTransientUnavailable()
                }
                is VisibilityDepthCopyResult.Rejected -> {
                    runtime.recordDepthProcessingRejected()
                    runtime.recordDepthFailure()
                }
            }
        },
        onFailure = {
            runtime.recordDepthProcessingRejected()
            runtime.recordDepthFailure()
        },
    )

    private val depthAdmissionLock = Any()
    private var depthSuspended = false
    private val depthNovelty = DepthViewNoveltyGate()
    private val depthTranslation = FloatArray(3)
    private val depthRotation = FloatArray(4)

    fun suspendDepth() = synchronized(depthAdmissionLock) { depthSuspended = true }

    fun resumeDepth() = synchronized(depthAdmissionLock) { depthSuspended = false }

    fun awaitDepthIdle(timeoutMillis: Long): Boolean = depthProcessor.awaitIdle(timeoutMillis)

    internal fun packedLeaseReceipts(): Map<String, SampleLeasePoolReceipt> = adapter.packedLeaseReceipts()

    fun close() {
        depthProcessor.close()
        adapter.closePackedPools()
    }

    fun onFrame(frame: Frame) {
        if (runtime.isSyntheticSource()) return
        val cut = ownership() ?: return
        if (frame.camera.trackingState != TrackingState.TRACKING || frame.timestamp <= 0) return
        val sequence = frameSequence++
        val capability = depthMode().toVisibilityDepthCapability()
        runtime.setDepthCapability(capability)
        if (runtime.shouldCopyFeature(frame.timestamp)) {
            val started = System.nanoTime()
            when (
                val result = adapter.copyFeaturePacked(
                    frame,
                    cut,
                    capability,
                    sequence,
                    runtime.featureSampleCapacity(),
                )
            ) {
                is VisibilityFeatureCopyResult.Observation ->
                    runtime.offerFeature(result.value, System.nanoTime() - started)
                is VisibilityFeatureCopyResult.PackedObservation -> {
                    runtime.offerFeature(result.value, System.nanoTime() - started)
                }
                VisibilityFeatureCopyResult.TransientUnavailable ->
                    runtime.recordFeatureTransientUnavailable()
                is VisibilityFeatureCopyResult.Rejected -> runtime.recordFeatureFailure()
            }
        }
        synchronized(depthAdmissionLock) {
            if (!depthSuspended && depthIntakeAllowed()) {
                if (!depthProcessor.canAccept()) {
                    runtime.recordDepthProcessorBusyDrop()
                    return@synchronized
                }
                val pose = frame.camera.pose
                pose.getTranslation(depthTranslation, 0)
                pose.getRotationQuaternion(depthRotation, 0)
                if (!depthNovelty.isNovel(frame.timestamp, depthTranslation, depthRotation) ||
                    !runtime.shouldCopyDepth(frame.timestamp)
                ) return@synchronized
                val started = System.nanoTime()
                when (val result = adapter.prepareDepth(frame, cut, capability, sequence)) {
                    is PreparedVisibilityDepthResult.Ready -> {
                        runtime.recordDepthPreparationReady()
                        if (depthProcessor.offer(result.frame, System.nanoTime() - started)) {
                            depthNovelty.remember(frame.timestamp, depthTranslation, depthRotation)
                        } else {
                            runtime.recordDepthProcessorBusyDrop()
                        }
                    }
                    PreparedVisibilityDepthResult.TransientUnavailable -> {
                        runtime.recordDepthPreparationTransient()
                        runtime.recordDepthTransientUnavailable()
                    }
                    is PreparedVisibilityDepthResult.Rejected -> {
                        runtime.recordDepthPreparationRejected()
                        runtime.recordDepthFailure()
                    }
                }
            }
        }
    }

}

/** Admits changed viewpoints promptly and refreshes a static view after three seconds. */
internal class DepthViewNoveltyGate {
    private var lastTimestampNs = Long.MIN_VALUE
    private val lastTranslation = FloatArray(3)
    private val lastRotation = FloatArray(4)

    fun isNovel(timestampNs: Long, translation: FloatArray, rotation: FloatArray): Boolean {
        if (lastTimestampNs == Long.MIN_VALUE || timestampNs <= lastTimestampNs ||
            timestampNs - lastTimestampNs >= MAX_STATIC_AGE_NS
        ) return true
        val dx = translation[0] - lastTranslation[0]
        val dy = translation[1] - lastTranslation[1]
        val dz = translation[2] - lastTranslation[2]
        if (dx * dx + dy * dy + dz * dz >= MIN_TRANSLATION_METERS_SQUARED) return true
        val dot = kotlin.math.abs(
            rotation[0] * lastRotation[0] + rotation[1] * lastRotation[1] +
                rotation[2] * lastRotation[2] + rotation[3] * lastRotation[3],
        )
        return dot < MIN_ROTATION_DOT
    }

    fun remember(timestampNs: Long, translation: FloatArray, rotation: FloatArray) {
        lastTimestampNs = timestampNs
        translation.copyInto(lastTranslation, endIndex = 3)
        rotation.copyInto(lastRotation, endIndex = 4)
    }

    private companion object {
        const val MAX_STATIC_AGE_NS = 3_000_000_000L
        const val MIN_TRANSLATION_METERS_SQUARED = 0.05f * 0.05f
        const val MIN_ROTATION_DOT = 0.9990482f // cos(5 degrees / 2)
    }
}
