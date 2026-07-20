package com.uhg0.ar_flutter_plugin_2.capture

import com.google.ar.core.Frame
import com.google.ar.core.TrackingState
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

// Roughly two seconds at 60 Hz is enough to bracket a Camera2 exposure now
// that pose resolution occurs before frames enter the encoder queue.
class PoseDataExtractor(private val capacity: Int = 120) {
    data class CachedPose(
        val position: FloatArray,
        val rotationQuaternion: FloatArray,
        val transform: FloatArray,
        val timestampNs: Long,
        val systemTimestampMs: Long,
        val isTracking: Boolean,
        val confidence: Float,
        val trackingState: String,
        val observedTimestampNs: Long = timestampNs,
    )

    data class CaptureTiming(
        val sensorTimestampNs: Long,
        val exposureTimeNs: Long,
        val rollingShutterSkewNs: Long = 0,
        val observedTimestampNs: Long? = null,
    ) {
        val referenceTimestampNs: Long
            get() = sensorTimestampNs + exposureTimeNs / 2L

        val observedReferenceTimestampNs: Long?
            get() = observedTimestampNs?.plus(exposureTimeNs / 2L)
    }

    data class AlignedPose(
        val pose: CachedPose,
        val sensorTimestampNs: Long,
        val poseAlignment: String,
        val poseTimeErrorNs: Long,
        val exposureTimeNs: Long,
        val rollingShutterSkewNs: Long,
    )

    private val lock = Object()
    private val poses = mutableListOf<CachedPose>()

    companion object {
        const val OPENCV_CONVENTION = "opencv_c2w_v1"
        const val EXACT_MATCH_THRESHOLD_NS = 2_000_000L
        // When ARCore pauses around a still request, a two-sided interpolation
        // bracket may not exist. A bounded nearest tracked pose is still a
        // useful relative camera pose, and its measured offset is preserved in
        // poseTimeErrorNs so downstream reconstruction can weight it.
        const val NEAREST_MATCH_THRESHOLD_NS = 150_000_000L
        const val OBSERVED_NEAREST_MATCH_THRESHOLD_NS = 300_000_000L
        // A one-shot multi-megapixel Camera2 request can suppress several
        // ARCore frames. Physical release evidence observed a 253.2 ms tracked
        // bracket. The 300 ms ceiling still requires tracked samples on both
        // sides and never permits extrapolation.
        const val INTERPOLATION_GAP_LIMIT_NS = 300_000_000L
        // Some shared-camera devices pause ARCore delivery for several frames
        // around a still. Wait for a preferred bracket before using the
        // explicitly bounded nearest-pose fallback.
        const val OBSERVED_FRAME_WAIT_MS = 1_000L
    }

    fun onFrame(frame: Frame) {
        val camera = frame.camera
        val pose = camera.pose
        val transform = FloatArray(16)
        pose.toMatrix(transform, 0)
        val trackingState = when (camera.trackingState) {
            TrackingState.TRACKING -> "tracking"
            TrackingState.PAUSED -> "paused"
            TrackingState.STOPPED -> "stopped"
        }

        addSample(
            CachedPose(
                position = floatArrayOf(pose.tx(), pose.ty(), pose.tz()),
                rotationQuaternion = pose.rotationQuaternion.clone(),
                transform = transform,
                timestampNs = frame.timestamp,
                systemTimestampMs = System.currentTimeMillis(),
                isTracking = camera.trackingState == TrackingState.TRACKING,
                confidence = if (camera.trackingState == TrackingState.TRACKING) 1.0f else 0.0f,
                trackingState = trackingState,
                observedTimestampNs = System.nanoTime(),
            ),
        )
    }

    fun addSample(pose: CachedPose) {
        synchronized(lock) {
            val insertIndex = poses.indexOfFirst { it.timestampNs > pose.timestampNs }
            if (insertIndex >= 0) {
                poses.add(insertIndex, pose)
            } else {
                poses.add(pose)
            }
            while (poses.size > capacity) {
                poses.removeAt(0)
            }
            lock.notifyAll()
        }
    }

    fun latest(): CachedPose? = synchronized(lock) { poses.lastOrNull() }

    fun alignmentDiagnostics(captureTiming: CaptureTiming): String = synchronized(lock) {
        val tracked = poses.filter { it.isTracking }
        val target = captureTiming.referenceTimestampNs
        val nearestError = tracked.minOfOrNull { abs(it.timestampNs - target) }
        val before = tracked.lastOrNull { it.timestampNs <= target }
        val after = tracked.firstOrNull { it.timestampNs >= target }
        val bracketGap =
            if (before != null && after != null) after.timestampNs - before.timestampNs else null
        val observedTarget = captureTiming.observedReferenceTimestampNs
        val observedNearestError = observedTarget?.let { targetNs ->
            tracked.minOfOrNull { abs(it.observedTimestampNs - targetNs) }
        }
        "targetNs=$target trackedCount=${tracked.size} " +
            "oldestTrackedNs=${tracked.firstOrNull()?.timestampNs} " +
            "latestTrackedNs=${tracked.lastOrNull()?.timestampNs} nearestErrorNs=$nearestError " +
            "beforeNs=${before?.timestampNs} afterNs=${after?.timestampNs} bracketGapNs=$bracketGap " +
            "observedTargetNs=$observedTarget observedNearestErrorNs=$observedNearestError"
    }

    /**
     * Waits until ARCore has produced at least one observed tracking pose.
     *
     * Shared-camera initialization can finish before SceneView's next frame. A
     * still submitted in that gap can never be paired with a tracked pose, so
     * callers use this as a readiness gate rather than issuing an unalignable
     * Camera2 request.
     */
    fun awaitTrackingPose(timeoutMs: Long): Boolean {
        val deadlineMs = System.currentTimeMillis() + timeoutMs
        synchronized(lock) {
            while (poses.none { it.isTracking }) {
                val remainingMs = deadlineMs - System.currentTimeMillis()
                if (remainingMs <= 0) {
                    return false
                }
                lock.wait(remainingMs)
            }
            return true
        }
    }

    fun toAlignedPose(
        pose: CachedPose,
        sensorTimestampNs: Long = pose.timestampNs,
        poseAlignment: String = "exact",
        poseTimeErrorNs: Long = 0L,
        exposureTimeNs: Long = 0L,
        rollingShutterSkewNs: Long = 0L,
    ): AlignedPose {
        return AlignedPose(
            pose = pose,
            sensorTimestampNs = sensorTimestampNs,
            poseAlignment = poseAlignment,
            poseTimeErrorNs = poseTimeErrorNs,
            exposureTimeNs = exposureTimeNs,
            rollingShutterSkewNs = rollingShutterSkewNs,
        )
    }

    fun toPoseMap(alignedPose: AlignedPose): Map<String, Any?> {
        val trackingTransform = alignedPose.pose.transform.clone()
        val openCvTransform = glToOpenCvTransform(alignedPose.pose.transform)
        val openCvPosition = floatArrayOf(
            openCvTransform[12],
            openCvTransform[13],
            openCvTransform[14],
        )
        val openCvQuaternion = quaternionFromTransform(openCvTransform)

        return mapOf(
            "position" to mapOf(
                "x" to openCvPosition[0].toDouble(),
                "y" to openCvPosition[1].toDouble(),
                "z" to openCvPosition[2].toDouble(),
            ),
            "rotation" to mapOf(
                "x" to openCvQuaternion[0].toDouble(),
                "y" to openCvQuaternion[1].toDouble(),
                "z" to openCvQuaternion[2].toDouble(),
                "w" to openCvQuaternion[3].toDouble(),
            ),
            "transform" to openCvTransform.map { it.toDouble() },
            "convention" to OPENCV_CONVENTION,
            "timestampMs" to alignedPose.pose.systemTimestampMs,
            "sensorTimestampNs" to alignedPose.sensorTimestampNs,
            "confidence" to alignedPose.pose.confidence.toDouble(),
            "isTracking" to alignedPose.pose.isTracking,
            "trackingState" to alignedPose.pose.trackingState,
            "poseAlignment" to alignedPose.poseAlignment,
            "poseTimeErrorNs" to alignedPose.poseTimeErrorNs,
            "trackingPose" to mapOf(
                "convention" to "arcore_gl_c2w_v1",
                "position" to mapOf(
                    "x" to alignedPose.pose.position[0].toDouble(),
                    "y" to alignedPose.pose.position[1].toDouble(),
                    "z" to alignedPose.pose.position[2].toDouble(),
                ),
                "rotation" to mapOf(
                    "x" to alignedPose.pose.rotationQuaternion[0].toDouble(),
                    "y" to alignedPose.pose.rotationQuaternion[1].toDouble(),
                    "z" to alignedPose.pose.rotationQuaternion[2].toDouble(),
                    "w" to alignedPose.pose.rotationQuaternion[3].toDouble(),
                ),
                "cameraToWorld" to trackingTransform.map { it.toDouble() },
            ),
        )
    }

    fun resolvePose(
        captureTiming: CaptureTiming,
        waitForFuturePoseMs: Long = OBSERVED_FRAME_WAIT_MS,
    ): AlignedPose? {
        val deadlineMs = System.currentTimeMillis() + waitForFuturePoseMs
        synchronized(lock) {
            while (true) {
                val resolved = resolvePoseLocked(captureTiming)
                if (resolved != null) {
                    return resolved
                }

                val observedResolved = resolveObservedPoseLocked(captureTiming)
                if (observedResolved != null) {
                    return observedResolved
                }

                val latestTracked = poses.lastOrNull { it.isTracking }
                val observedTarget = captureTiming.observedReferenceTimestampNs
                if (observedTarget != null) {
                    if (latestTracked != null &&
                        latestTracked.observedTimestampNs >= observedTarget
                    ) {
                        return resolveNearestPoseLocked(captureTiming)
                    }
                } else if (latestTracked != null &&
                    latestTracked.timestampNs >= captureTiming.referenceTimestampNs
                ) {
                    return resolveNearestPoseLocked(captureTiming)
                }

                val remainingMs = deadlineMs - System.currentTimeMillis()
                if (remainingMs <= 0) {
                    return resolveNearestPoseLocked(captureTiming)
                }
                lock.wait(remainingMs)
            }
        }
    }

    private fun resolvePoseLocked(captureTiming: CaptureTiming): AlignedPose? {
        val targetTimestampNs = captureTiming.referenceTimestampNs
        val trackedPoses = poses.filter { it.isTracking }
        if (trackedPoses.isEmpty()) {
            return null
        }

        trackedPoses
            .filter { abs(it.timestampNs - targetTimestampNs) <= EXACT_MATCH_THRESHOLD_NS }
            .minByOrNull { abs(it.timestampNs - targetTimestampNs) }
            ?.let { exactPose ->
            return AlignedPose(
                pose = exactPose,
                sensorTimestampNs = targetTimestampNs,
                poseAlignment = "exact",
                poseTimeErrorNs = abs(exactPose.timestampNs - targetTimestampNs),
                exposureTimeNs = captureTiming.exposureTimeNs,
                rollingShutterSkewNs = captureTiming.rollingShutterSkewNs,
            )
        }

        val before = trackedPoses.lastOrNull { it.timestampNs <= targetTimestampNs }
        val after = trackedPoses.firstOrNull { it.timestampNs >= targetTimestampNs }

        if (
            before != null &&
                after != null &&
                before.timestampNs != after.timestampNs
        ) {
            val gapNs = after.timestampNs - before.timestampNs
            if (gapNs <= INTERPOLATION_GAP_LIMIT_NS) {
                val alpha =
                    ((targetTimestampNs - before.timestampNs).toDouble() / gapNs.toDouble())
                        .coerceIn(0.0, 1.0)
                val interpolatedPosition = lerp(before.position, after.position, alpha)
                val interpolatedRotation =
                    slerp(before.rotationQuaternion, after.rotationQuaternion, alpha)
                val interpolatedTransform =
                    transformFrom(interpolatedPosition, interpolatedRotation)
                val interpolationTrackingState =
                    if (before.trackingState == after.trackingState) {
                        before.trackingState
                    } else {
                        "tracking"
                    }

                return AlignedPose(
                    pose =
                        CachedPose(
                            position = interpolatedPosition,
                            rotationQuaternion = interpolatedRotation,
                            transform = interpolatedTransform,
                            timestampNs = targetTimestampNs,
                            systemTimestampMs =
                                interpolateLong(
                                    before.systemTimestampMs,
                                    after.systemTimestampMs,
                                    alpha,
                                ),
                            isTracking = true,
                            confidence =
                                interpolateFloat(
                                    before.confidence,
                                    after.confidence,
                                    alpha,
                                ),
                            trackingState = interpolationTrackingState,
                        ),
                    sensorTimestampNs = targetTimestampNs,
                    poseAlignment = "interpolated",
                    poseTimeErrorNs = 0L,
                    exposureTimeNs = captureTiming.exposureTimeNs,
                    rollingShutterSkewNs = captureTiming.rollingShutterSkewNs,
                )
            }
        }

        return null
    }

    /**
     * Correlates through the process monotonic clock when Camera2 and ARCore do
     * not advertise a common sensor clock. This still resolves a bounded pose
     * at exposure time; it never substitutes an unbounded latest-pose guess.
     */
    private fun resolveObservedPoseLocked(
        captureTiming: CaptureTiming,
    ): AlignedPose? {
        val targetTimestampNs = captureTiming.observedReferenceTimestampNs ?: return null
        val trackedPoses = poses.filter { it.isTracking }
        if (trackedPoses.isEmpty()) {
            return null
        }

        trackedPoses
            .filter { abs(it.observedTimestampNs - targetTimestampNs) <= EXACT_MATCH_THRESHOLD_NS }
            .minByOrNull { abs(it.observedTimestampNs - targetTimestampNs) }
            ?.let { exactPose ->
                return AlignedPose(
                    pose = exactPose,
                    sensorTimestampNs = captureTiming.referenceTimestampNs,
                    poseAlignment = "observedMonotonicExact",
                    poseTimeErrorNs = abs(exactPose.observedTimestampNs - targetTimestampNs),
                    exposureTimeNs = captureTiming.exposureTimeNs,
                    rollingShutterSkewNs = captureTiming.rollingShutterSkewNs,
                )
            }

        val before = trackedPoses.lastOrNull { it.observedTimestampNs <= targetTimestampNs }
        val after = trackedPoses.firstOrNull { it.observedTimestampNs >= targetTimestampNs }
        if (before == null || after == null || before === after) {
            return null
        }
        val gapNs = after.observedTimestampNs - before.observedTimestampNs
        if (gapNs > INTERPOLATION_GAP_LIMIT_NS) {
            return null
        }

        val alpha =
            ((targetTimestampNs - before.observedTimestampNs).toDouble() / gapNs.toDouble())
                .coerceIn(0.0, 1.0)
        val interpolatedPosition = lerp(before.position, after.position, alpha)
        val interpolatedRotation = slerp(before.rotationQuaternion, after.rotationQuaternion, alpha)
        val interpolatedTransform = transformFrom(interpolatedPosition, interpolatedRotation)
        val interpolatedPoseTimestampNs =
            interpolateLong(before.timestampNs, after.timestampNs, alpha)

        return AlignedPose(
            pose =
                CachedPose(
                    position = interpolatedPosition,
                    rotationQuaternion = interpolatedRotation,
                    transform = interpolatedTransform,
                    timestampNs = interpolatedPoseTimestampNs,
                    systemTimestampMs =
                        interpolateLong(before.systemTimestampMs, after.systemTimestampMs, alpha),
                    isTracking = true,
                    confidence = interpolateFloat(before.confidence, after.confidence, alpha),
                    trackingState = "tracking",
                    observedTimestampNs = targetTimestampNs,
                ),
            sensorTimestampNs = captureTiming.referenceTimestampNs,
            poseAlignment = "observedMonotonicInterpolated",
            poseTimeErrorNs = 0L,
            exposureTimeNs = captureTiming.exposureTimeNs,
            rollingShutterSkewNs = captureTiming.rollingShutterSkewNs,
        )
    }

    private fun resolveNearestPoseLocked(
        captureTiming: CaptureTiming,
    ): AlignedPose? {
        val trackedPoses = poses.filter { it.isTracking }
        val sensorTargetNs = captureTiming.referenceTimestampNs
        val nearestSensorPose = trackedPoses.minByOrNull {
            abs(it.timestampNs - sensorTargetNs)
        }
        val sensorErrorNs = nearestSensorPose?.let {
            abs(it.timestampNs - sensorTargetNs)
        }
        if (nearestSensorPose != null &&
            sensorErrorNs != null &&
            sensorErrorNs <= NEAREST_MATCH_THRESHOLD_NS
        ) {
            return AlignedPose(
                pose = nearestSensorPose,
                sensorTimestampNs = sensorTargetNs,
                poseAlignment = "nearest",
                poseTimeErrorNs = sensorErrorNs,
                exposureTimeNs = captureTiming.exposureTimeNs,
                rollingShutterSkewNs = captureTiming.rollingShutterSkewNs,
            )
        }

        val observedTargetNs = captureTiming.observedReferenceTimestampNs ?: return null
        val nearestObservedPose = trackedPoses.minByOrNull {
            abs(it.observedTimestampNs - observedTargetNs)
        } ?: return null
        val observedErrorNs = abs(nearestObservedPose.observedTimestampNs - observedTargetNs)
        if (observedErrorNs > OBSERVED_NEAREST_MATCH_THRESHOLD_NS) {
            return null
        }
        return AlignedPose(
            pose = nearestObservedPose,
            sensorTimestampNs = sensorTargetNs,
            poseAlignment = "observedMonotonicNearest",
            poseTimeErrorNs = observedErrorNs,
            exposureTimeNs = captureTiming.exposureTimeNs,
            rollingShutterSkewNs = captureTiming.rollingShutterSkewNs,
        )
    }

    private fun lerp(start: FloatArray, end: FloatArray, alpha: Double): FloatArray {
        return FloatArray(start.size) { index ->
            (start[index] + ((end[index] - start[index]) * alpha)).toFloat()
        }
    }

    private fun slerp(start: FloatArray, end: FloatArray, alpha: Double): FloatArray {
        var startQuat = start.normalizedQuaternion()
        var endQuat = end.normalizedQuaternion()

        var dotProduct = dot(startQuat, endQuat)
        if (dotProduct < 0.0f) {
            endQuat = FloatArray(4) { index -> -endQuat[index] }
            dotProduct = -dotProduct
        }

        if (dotProduct > 0.9995f) {
            return lerp(startQuat, endQuat, alpha).normalizedQuaternion()
        }

        val theta0 = acos(dotProduct.coerceIn(-1.0f, 1.0f).toDouble())
        val sinTheta0 = sin(theta0)
        if (sinTheta0 == 0.0) {
            return startQuat
        }

        val theta = theta0 * alpha
        val sinTheta = sin(theta)
        val s0 = (cos(theta) - dotProduct * sinTheta / sinTheta0)
        val s1 = sinTheta / sinTheta0

        return floatArrayOf(
            (startQuat[0] * s0 + endQuat[0] * s1).toFloat(),
            (startQuat[1] * s0 + endQuat[1] * s1).toFloat(),
            (startQuat[2] * s0 + endQuat[2] * s1).toFloat(),
            (startQuat[3] * s0 + endQuat[3] * s1).toFloat(),
        ).normalizedQuaternion()
    }

    private fun transformFrom(position: FloatArray, quaternion: FloatArray): FloatArray {
        val x = quaternion[0]
        val y = quaternion[1]
        val z = quaternion[2]
        val w = quaternion[3]
        val xx = x * x
        val yy = y * y
        val zz = z * z
        val xy = x * y
        val xz = x * z
        val yz = y * z
        val wx = w * x
        val wy = w * y
        val wz = w * z

        return floatArrayOf(
            1f - 2f * (yy + zz),
            2f * (xy + wz),
            2f * (xz - wy),
            0f,
            2f * (xy - wz),
            1f - 2f * (xx + zz),
            2f * (yz + wx),
            0f,
            2f * (xz + wy),
            2f * (yz - wx),
            1f - 2f * (xx + yy),
            0f,
            position[0],
            position[1],
            position[2],
            1f,
        )
    }

    private fun dot(left: FloatArray, right: FloatArray): Float {
        return left[0] * right[0] +
            left[1] * right[1] +
            left[2] * right[2] +
            left[3] * right[3]
    }

    private fun interpolateFloat(start: Float, end: Float, alpha: Double): Float {
        return (start + ((end - start) * alpha)).toFloat()
    }

    private fun interpolateLong(start: Long, end: Long, alpha: Double): Long {
        return (start + ((end - start) * alpha)).toLong()
    }

    private fun FloatArray.normalizedQuaternion(): FloatArray {
        val magnitude =
            sqrt(
                this[0] * this[0] +
                    this[1] * this[1] +
                    this[2] * this[2] +
                    this[3] * this[3],
            )
        if (magnitude == 0.0f) {
            return floatArrayOf(0f, 0f, 0f, 1f)
        }
        return FloatArray(4) { index -> this[index] / magnitude }
    }

    private fun glToOpenCvTransform(transform: FloatArray): FloatArray {
        val converted = transform.clone()
        for (row in 0..3) {
            converted[4 + row] = -converted[4 + row]
            converted[8 + row] = -converted[8 + row]
        }
        return converted
    }

    private fun quaternionFromTransform(transform: FloatArray): FloatArray {
        val m00 = transform[0]
        val m11 = transform[5]
        val m22 = transform[10]
        val trace = m00 + m11 + m22

        val quaternion =
            if (trace > 0f) {
                val scale = sqrt(trace + 1f) * 2f
                floatArrayOf(
                    (transform[6] - transform[9]) / scale,
                    (transform[8] - transform[2]) / scale,
                    (transform[1] - transform[4]) / scale,
                    0.25f * scale,
                )
            } else if (m00 > m11 && m00 > m22) {
                val scale = sqrt(1f + m00 - m11 - m22) * 2f
                floatArrayOf(
                    0.25f * scale,
                    (transform[4] + transform[1]) / scale,
                    (transform[8] + transform[2]) / scale,
                    (transform[6] - transform[9]) / scale,
                )
            } else if (m11 > m22) {
                val scale = sqrt(1f + m11 - m00 - m22) * 2f
                floatArrayOf(
                    (transform[4] + transform[1]) / scale,
                    0.25f * scale,
                    (transform[9] + transform[6]) / scale,
                    (transform[8] - transform[2]) / scale,
                )
            } else {
                val scale = sqrt(1f + m22 - m00 - m11) * 2f
                floatArrayOf(
                    (transform[8] + transform[2]) / scale,
                    (transform[9] + transform[6]) / scale,
                    0.25f * scale,
                    (transform[1] - transform[4]) / scale,
                )
            }

        return quaternion.normalizedQuaternion()
    }
}
