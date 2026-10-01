package com.uhg0.ar_flutter_plugin_2.visibilitygrid

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

internal const val SYNTHETIC_SPHERE_VIEW_COUNT = 20

/** Deterministic copied-source emitter shared by JVM and debug emulator gates. */
internal class SyntheticVisibilityObservationSource(
    private val runtime: AndroidVisibilityGridRuntime,
    private val ownership: () -> VisibilityObservationOwnership?,
) {
    private var sequence = 0L
    private var worldFromCameraGl = identityVisibilityGridTransform()
    private var groupFromCameraGl = identityVisibilityGridTransform()
    private var sweepAnchorWorldFromCameraGl = identityVisibilityGridTransform()
    private var sweepGroupFromWorldGl = identityVisibilityGridTransform().toList()

    /** Anchors feature geometry to the active group and depth to one exact AR world pose. */
    fun anchor(worldFromCameraGl: DoubleArray, groupFrame: VisibilityGroupFrame) {
        VisibilityCameraPose.copyOf(worldFromCameraGl)
        this.worldFromCameraGl = worldFromCameraGl.copyOf()
        groupFromCameraGl = compose(groupFrame.groupFromWorldGl, worldFromCameraGl)
        sweepAnchorWorldFromCameraGl = worldFromCameraGl.copyOf()
        sweepGroupFromWorldGl = groupFrame.groupFromWorldGl
        VisibilityCameraPose.copyOf(groupFromCameraGl)
    }

    fun setDepthCapability(capability: VisibilityDepthCapability) {
        runtime.configureSyntheticSource(capability)
    }

    fun emitFeature(
        timestampNs: Long,
        marker: Int = 0,
        lateralMarker: Int = marker,
        callbackCopyNs: Long = 0,
    ): Boolean {
        require(callbackCopyNs >= 0)
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
            callbackCopyNs,
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

    /** Exercises both per-observation sample ceilings without expanding the scene. */
    fun emitMaximumSamples(featureTimestampNs: Long, depthTimestampNs: Long): Pair<Boolean, Boolean> =
        emitMaximumFeature(featureTimestampNs) to emitMaximumDepth(depthTimestampNs)

    /** Drives the full feature source through a host or emulator integration owner. */
    fun emitMaximumFeature(featureTimestampNs: Long): Boolean {
        val cut = ownership() ?: return false
        val featureSamples = VisibilityFeatureObservation.copySamples(
            List(V2_FEATURE_SAMPLE_CAPACITY) { index ->
                val local = localToGroup(
                    0.30 + (index % 40).toDouble() / 4_000.0,
                    (index / 40).toDouble() / 4_000.0,
                    -1.0,
                )
                VisibilityFeatureSample(
                    id = 100_000 + index,
                    xWorld = local[0],
                    yWorld = local[1],
                    zWorld = local[2],
                    confidence = 1.0,
                )
            },
        )
        return runtime.offerFeature(
            VisibilityFeatureObservation(
                ownership = cut,
                frame = syntheticFrame(VisibilityObservationSource.SYNTHETIC_FEATURE, featureTimestampNs),
                samples = featureSamples,
                sourceRejectedSamples = 0,
                payloadBytes = VisibilityFeatureObservation.FEATURE_FIXED_BYTES +
                    featureSamples.size * VisibilityFeatureObservation.FEATURE_SAMPLE_BYTES,
            ),
        )
    }

    /** A fresh maximum-depth offer after a prior exact canonical ACK. */
    fun emitMaximumDepth(timestampNs: Long): Boolean {
        val cut = ownership() ?: return false
        val depthSamples = VisibilityDepthObservation.copySamples(
            List(V2_DEPTH_SAMPLE_CAPACITY) { index ->
                VisibilityDepthSample(
                    x = SYNTHETIC_PRINCIPAL_X - 24 + index % 48,
                    y = SYNTHETIC_PRINCIPAL_Y - 16 + index / 48,
                    // This near-field batch probes the full selected-sample
                    // ceiling through bounded canonical work.
                    depthMillimeters = MAXIMUM_SAMPLE_DEPTH_MILLIMETERS,
                    confidence = 255,
                )
            },
        )
        return runtime.offerDepth(
            VisibilityDepthObservation(
                ownership = cut,
                frame = syntheticFrame(VisibilityObservationSource.SYNTHETIC_DEPTH, timestampNs),
                samples = depthSamples,
                sourceRejectedSamples = 0,
                payloadBytes = VisibilityDepthObservation.DEPTH_FIXED_BYTES +
                    depthSamples.size * VisibilityDepthObservation.DEPTH_SAMPLE_BYTES,
            ),
        )
    }

    /** One bounded camera view of a two-metre spherical room around the anchor. */
    fun emitSphereView(
        viewIndex: Int,
        featureTimestampNs: Long,
        depthTimestampNs: Long,
        includeFeature: Boolean = true,
        includeDepth: Boolean = true,
    ): Pair<Boolean, Boolean> {
        require(viewIndex in 0 until SYNTHETIC_SPHERE_VIEW_COUNT)
        // Six headings per ring overlap at the sampled horizontal FOV.
        // Three 45-degree elevation steps plus both poles cover the sphere.
        val yaw = if (viewIndex < 18) ((viewIndex % 6) + 0.5) * PI / 3.0 else 0.0
        val pitch = when (viewIndex) {
            18 -> -PI / 2.0
            19 -> PI / 2.0
            else -> ((viewIndex / 6) - 1) * PI / 4.0
        }
        val cosYaw = cos(yaw)
        val sinYaw = sin(yaw)
        val cosPitch = cos(pitch)
        val sinPitch = sin(pitch)
        val relative = doubleArrayOf(
            cosYaw, 0.0, sinYaw, 0.0,
            -sinYaw * sinPitch, cosPitch, cosYaw * sinPitch, 0.0,
            -sinYaw * cosPitch, -sinPitch, cosYaw * cosPitch, 0.0,
            0.25 * sin(viewIndex * 1.7),
            0.20 * cos(viewIndex * 1.3),
            0.15 * sin(viewIndex * 0.9),
            1.0,
        )
        val worldPose = compose(sweepAnchorWorldFromCameraGl.toList(), relative)
        val groupPose = compose(sweepGroupFromWorldGl, worldPose)
        val center = sweepAnchorWorldFromCameraGl
        val featureSamples = ArrayList<VisibilityFeatureSample>(64)
        val depthSamples = ArrayList<VisibilityDepthSample>(8)
        for (sample in 0 until 64) {
            val pixelX = 40 + (sample % 8) * 171
            val pixelY = 60 + (sample / 8) * 120
            val cameraRay = doubleArrayOf(
                (pixelX - SYNTHETIC_PRINCIPAL_X) / SPHERE_FOCAL_LENGTH,
                (SYNTHETIC_PRINCIPAL_Y - pixelY) / SPHERE_FOCAL_LENGTH,
                -1.0,
            )
            val rayLength = sqrt(cameraRay.sumOf { it * it })
            val ray = DoubleArray(3) { cameraRay[it] / rayLength }
            val worldRay = DoubleArray(3) { axis ->
                worldPose[axis] * ray[0] + worldPose[4 + axis] * ray[1] + worldPose[8 + axis] * ray[2]
            }
            val offset = DoubleArray(3) { worldPose[12 + it] - center[12 + it] }
            val dot = (0..2).sumOf { offset[it] * worldRay[it] }
            val offsetSquared = offset.sumOf { it * it }
            val distance = -dot + sqrt(dot * dot + SPHERE_RADIUS_METERS * SPHERE_RADIUS_METERS - offsetSquared)
            val world = DoubleArray(3) { worldPose[12 + it] + distance * worldRay[it] }
            val group = DoubleArray(3) { axis ->
                sweepGroupFromWorldGl[axis] * world[0] +
                    sweepGroupFromWorldGl[4 + axis] * world[1] +
                    sweepGroupFromWorldGl[8 + axis] * world[2] +
                    sweepGroupFromWorldGl[12 + axis]
            }
            featureSamples += VisibilityFeatureSample(
                id = 200_000 + viewIndex * 64 + sample,
                xWorld = group[0], yWorld = group[1], zWorld = group[2], confidence = 1.0,
            )
            if (sample % 8 == 4) {
                depthSamples += VisibilityDepthSample(
                    x = pixelX, y = pixelY,
                    depthMillimeters = (distance / rayLength * 1000.0).roundToInt(),
                    confidence = 255,
                )
            }
        }
        val cut = ownership() ?: return false to false
        val feature = includeFeature && runtime.offerFeature(
            VisibilityFeatureObservation(
                ownership = cut,
                frame = syntheticFrame(VisibilityObservationSource.SYNTHETIC_FEATURE, featureTimestampNs, groupPose, SPHERE_FOCAL_LENGTH),
                samples = featureSamples,
                sourceRejectedSamples = 0,
                payloadBytes = VisibilityFeatureObservation.FEATURE_FIXED_BYTES +
                    featureSamples.size * VisibilityFeatureObservation.FEATURE_SAMPLE_BYTES,
            ),
        )
        val depth = includeDepth && runtime.offerDepth(
            VisibilityDepthObservation(
                ownership = cut,
                frame = syntheticFrame(VisibilityObservationSource.SYNTHETIC_DEPTH, depthTimestampNs, worldPose, SPHERE_FOCAL_LENGTH),
                samples = depthSamples,
                sourceRejectedSamples = 0,
                payloadBytes = VisibilityDepthObservation.DEPTH_FIXED_BYTES +
                    depthSamples.size * VisibilityDepthObservation.DEPTH_SAMPLE_BYTES,
            ),
        )
        return feature to depth
    }

    private fun syntheticFrame(
        source: VisibilityObservationSource,
        timestampNs: Long,
        pose: DoubleArray? = null,
        focalLength: Double = SYNTHETIC_FOCAL_LENGTH,
    ): VisibilityObservationFrame = VisibilityObservationFrame(
        source = source,
        frameSequence = sequence++,
        frameTimestampNs = timestampNs,
        sourceTimestampNs = timestampNs,
        cameraIdentity = "synthetic-camera",
        tracking = true,
        imageOrientation = "landscape_right_x_right_y_down_v1",
        pose = VisibilityCameraPose.copyOf(
            pose ?: if (source == VisibilityObservationSource.SYNTHETIC_FEATURE) {
                groupFromCameraGl
            } else {
                worldFromCameraGl
            },
        ),
        intrinsics = VisibilityCameraIntrinsics(
            imageWidth = SYNTHETIC_IMAGE_WIDTH,
            imageHeight = SYNTHETIC_IMAGE_HEIGHT,
            fx = focalLength,
            fy = focalLength,
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
        const val MAXIMUM_SAMPLE_DEPTH_MILLIMETERS = 300
        const val SPHERE_RADIUS_METERS = 2.0
        const val SPHERE_FOCAL_LENGTH = 1000.0
    }
}
