package com.uhg0.ar_flutter_plugin_2.capture

import com.google.ar.core.Frame
import com.google.ar.core.TrackingState
import com.uhg0.ar_flutter_plugin_2.BuildConfig
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
        val poseSource: String = "liveAnchor",
        val fixtureBindingGeneration: Long? = null,
        val fixtureGroupGeneration: Long? = null,
        // Same-frame Android image identity; zero/negative values are unavailable.
        // Distinct image intervals qualify motion; raw timestamps still align exposures.
        val androidCameraTimestampNs: Long = 0L,
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
    private val poses = PoseHistory(capacity)
    // Ingress order is authoritative for motion; raw sorting cannot hide a break.
    private val motionPoses = PoseHistory(capacity)
    private val frameIngress = PoseSlot()
    private val previousIngress = PoseSlot()
    private val motionImage = PoseSlot()
    private var hasMotionIngress = false
    private var motionObservedWatermarkNs = 0L
    private var motionImageWatermarkNs = 0L
    private var motionFreshnessObservedNs: Long? = null
    private var motionIngressGate: ExposureMotionGate? = null
    private val wireTransform = FloatArray(16)
    private val wireQuaternion = FloatArray(4)
    private var debugTimestampReadMismatchCount = 0L
    private var debugFixtureAnchor: CachedPose? = null
    private var debugFixtureOffsetX = 0.0f
    private var debugFixtureSource = LIVE_ANCHOR_POSE_SOURCE
    private var debugFixtureBindingGeneration: Long? = null
    private var debugFixtureGroupGeneration: Long? = null

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
        const val LIVE_ANCHOR_POSE_SOURCE = "liveAnchor"
        const val SYNTHETIC_POSE_SOURCE = "synthetic"
    }

    fun onFrame(frame: Frame) {
        // Sample both source clocks once from this frame, before a history-lock wait.
        val cameraTimestampNs = frame.timestamp
        val androidCameraTimestampNs = frame.androidCameraTimestamp
        val observedTimestampNs = System.nanoTime()
        val camera = frame.camera
        val pose = camera.pose
        val cameraTrackingState = camera.trackingState
        val trackingState = when (cameraTrackingState) {
            TrackingState.TRACKING -> "tracking"
            TrackingState.PAUSED -> "paused"
            TrackingState.STOPPED -> "stopped"
        }

        synchronized(lock) {
            val slot = frameIngress
            slot.timestampNs = cameraTimestampNs
            slot.androidCameraTimestampNs = androidCameraTimestampNs
            slot.systemTimestampMs = System.currentTimeMillis()
            slot.observedTimestampNs = observedTimestampNs
            val anchor = debugFixtureAnchor
            if (anchor == null) {
                slot.position[0] = pose.tx()
                slot.position[1] = pose.ty()
                slot.position[2] = pose.tz()
                pose.getRotationQuaternion(slot.rotation, 0)
                pose.toMatrix(slot.transform, 0)
                slot.isTracking = cameraTrackingState == TrackingState.TRACKING
                slot.confidence = if (slot.isTracking) 1f else 0f
                slot.trackingState = trackingState
                slot.poseSource = LIVE_ANCHOR_POSE_SOURCE
                slot.bindingGeneration = null
                slot.groupGeneration = null
            } else {
                anchor.position.copyInto(slot.position)
                anchor.rotationQuaternion.copyInto(slot.rotation)
                anchor.transform.copyInto(slot.transform)
                slot.position[0] += debugFixtureOffsetX
                slot.transform[12] += debugFixtureOffsetX
                slot.isTracking = true
                slot.confidence = 1f
                slot.trackingState = "tracking"
                slot.poseSource = debugFixtureSource
                slot.bindingGeneration = debugFixtureBindingGeneration
                slot.groupGeneration = debugFixtureGroupGeneration
            }
            ingestMotionLocked(slot)
            poses.insert(cameraTimestampNs)?.copyFrom(slot)
            lock.notifyAll()
        }
    }

    internal fun applyDebugFixture(raw: CachedPose): CachedPose {
        val fixture = synchronized(lock) {
            debugFixtureAnchor?.let {
                DebugFixtureSnapshot(
                    anchor = it,
                    offsetX = debugFixtureOffsetX,
                    source = debugFixtureSource,
                    bindingGeneration = debugFixtureBindingGeneration,
                    groupGeneration = debugFixtureGroupGeneration,
                )
            }
        } ?: return raw
        val position = fixture.anchor.position.clone()
        val transform = fixture.anchor.transform.clone()
        position[0] += fixture.offsetX
        transform[12] += fixture.offsetX
        return raw.copy(
            position = position,
            rotationQuaternion = fixture.anchor.rotationQuaternion.clone(),
            transform = transform,
            isTracking = true,
            confidence = 1.0f,
            trackingState = "tracking",
            poseSource = fixture.source,
            fixtureBindingGeneration = fixture.bindingGeneration,
            fixtureGroupGeneration = fixture.groupGeneration,
        )
    }

    fun addSample(pose: CachedPose) {
        synchronized(lock) {
            frameIngress.set(pose)
            ingestMotionLocked(frameIngress)
            poses.insert(pose.timestampNs)?.copyFrom(frameIngress)
            lock.notifyAll()
        }
    }

    private fun resetMotionLocked() {
        motionPoses.clear()
        hasMotionIngress = false
        motionObservedWatermarkNs = 0L
        motionImageWatermarkNs = 0L
        motionFreshnessObservedNs = null
        motionIngressGate = null
    }

    private fun sameMotionOwner(a: PoseSlot, b: PoseSlot): Boolean =
        a.poseSource == b.poseSource && a.bindingGeneration == b.bindingGeneration &&
            a.groupGeneration == b.groupGeneration && a.trackingState == b.trackingState

    private fun ingestMotionLocked(input: PoseSlot) {
        val imageNs = input.androidCameraTimestampNs
        val latest = motionPoses.lastSlot()
        val sameImageRevision = motionImageWatermarkNs > 0L && imageNs == motionImageWatermarkNs &&
            (!input.position.contentEquals(motionImage.position) || !input.rotation.contentEquals(motionImage.rotation))
        val failure = when {
            hasMotionIngress && input.observedTimestampNs <= motionObservedWatermarkNs -> ExposureMotionGate.INVALID_GAP
            !input.isTracking -> ExposureMotionGate.NOT_TRACKING
            !input.position.all { it.isFinite() } || !input.rotation.all { it.isFinite() } -> ExposureMotionGate.INVALID_POSE
            input.rotation.sumOf { it.toDouble() * it.toDouble() } <= 0.0 -> ExposureMotionGate.INVALID_QUATERNION
            imageNs <= 0L || imageNs < motionImageWatermarkNs -> ExposureMotionGate.INVALID_GAP
            hasMotionIngress && motionIngressGate == null && !sameMotionOwner(previousIngress, input) -> ExposureMotionGate.INVALID_GAP
            sameImageRevision -> ExposureMotionGate.INVALID_POSE
            latest != null && motionIngressGate == null &&
                (input.observedTimestampNs - latest.observedTimestampNs > 750_000_000L ||
                    imageNs - motionImageWatermarkNs > 1_000_000_000L) -> ExposureMotionGate.INVALID_GAP
            else -> null
        }
        if (failure != null) {
            // Retain only the interrupting pair for a truthful bounded diagnostic.
            motionPoses.clear()
            if (hasMotionIngress) motionPoses.append().copyFrom(if (sameImageRevision) motionImage else previousIngress)
            motionPoses.append().copyFrom(input)
            motionIngressGate = failure
        } else if (imageNs > motionImageWatermarkNs) {
            // First distinct image after a break seeds a new dwell; it cannot
            // inherit approval from the segment retained for diagnostics.
            if (motionIngressGate != null) motionPoses.clear()
            motionPoses.append().copyFrom(input)
            motionIngressGate = null
            motionFreshnessObservedNs = input.observedTimestampNs
        }
        // A repeated identity never updates the original image pose or its
        // observation time, including repeated revisions after invalidation.
        if (imageNs > motionImageWatermarkNs) {
            motionImageWatermarkNs = imageNs
            motionImage.copyFrom(input)
        }
        if (!hasMotionIngress || input.observedTimestampNs > motionObservedWatermarkNs) {
            motionObservedWatermarkNs = input.observedTimestampNs
        }
        previousIngress.copyFrom(input)
        hasMotionIngress = true
    }

    fun latest(): CachedPose? = synchronized(lock) { poses.lastOrNull() }

    internal enum class ExposureMotionGate {
        APPROVED, NO_POSE, STALE, NOT_TRACKING, OLDER_NOT_TRACKING,
        INVALID_GAP, INVALID_POSE, INVALID_QUATERNION, MOVING_TOO_FAST, INSUFFICIENT_DWELL,
    }

    /** Scalar sample identity; no pose arrays escape in a shutter receipt. */
    internal data class ExposureMotionSampleIdentity(
        val cameraTimestampNs: Long,
        val observedTimestampNs: Long,
        val isTracking: Boolean,
        val trackingState: String,
        val poseSource: String,
        val fixtureBindingGeneration: Long?,
        val fixtureGroupGeneration: Long?,
        val androidCameraTimestampNs: Long,
    )

    internal data class ExposureMotionPairDiagnostic(
        // Zero is the latest adjacent pair; larger indices are older pairs.
        val pairIndex: Int,
        val cameraGapNs: Long,
        val observedGapNs: Long,
        val translationDeltaMeters: Double?,
        val angularDeltaDegrees: Double?,
        val older: ExposureMotionSampleIdentity,
        val newer: ExposureMotionSampleIdentity,
    )

    /** One scalar-only receipt from the final shutter decision, never a frame log. */
    internal data class ExposureMotionDiagnostic(
        val gate: ExposureMotionGate,
        val decisionNs: Long,
        val ageMs: Double?,
        val historySpanMs: Double,
        val stableDwellMs: Double,
        val pairCount: Int,
        val latestTranslationSpeedMps: Double?,
        val latestAngularSpeedDegreesPerSecond: Double?,
        val medianTranslationSpeedMps: Double?,
        val medianAngularSpeedDegreesPerSecond: Double?,
        val maximumTranslationSpeedMps: Double?,
        val maximumAngularSpeedDegreesPerSecond: Double?,
        val firstRefusingPair: ExposureMotionPairDiagnostic?,
        // Omitted when the first refusing pair already carries this maximum.
        val maximumAngularPair: ExposureMotionPairDiagnostic?,
        val minimumPositiveCameraGapNs: Long?,
        val duplicatePairCount: Int,
        val timestampReadMismatchCount: Long,
    )

    // Called only when a bounded debug decision listener is installed. History
    // stays under the same lock as the Boolean decision; no pose leaves this owner.
    private fun exposureMotionDiagnostic(gate: ExposureMotionGate, nowNs: Long): ExposureMotionDiagnostic {
        val poses = motionPoses
        val latest = poses.lastOrNull()
        val translations = mutableListOf<Double>()
        val rotations = mutableListOf<Double>()
        var latestTranslation: Double? = null
        var latestRotation: Double? = null
        var historySpan = 0L
        var stableDwell = 0L
        var stablePrefix = true
        var firstRefusingPairIndex: Int? = null
        var maximumAngularPairIndex: Int? = null
        var maximumAngularSpeed: Double? = null
        var minimumPositiveCameraGap: Long? = null
        var duplicatePairCount = 0
        var decidingPrefix = gate == ExposureMotionGate.OLDER_NOT_TRACKING ||
            gate == ExposureMotionGate.INVALID_GAP || gate == ExposureMotionGate.INVALID_POSE ||
            gate == ExposureMotionGate.INVALID_QUATERNION || gate == ExposureMotionGate.MOVING_TOO_FAST
        if (latest != null) {
            var newer: CachedPose = latest
            for (index in poses.size - 2 downTo 0) {
                val older = poses[index]
                val pairIndex = poses.size - 2 - index
                val gap = newer.androidCameraTimestampNs - older.androidCameraTimestampNs
                val cameraGap = newer.timestampNs - older.timestampNs
                if (gap == 0L) duplicatePairCount++
                if (cameraGap > 0L && (minimumPositiveCameraGap == null || cameraGap < minimumPositiveCameraGap)) {
                    minimumPositiveCameraGap = cameraGap
                }
                historySpan = (latest.observedTimestampNs - older.observedTimestampNs).coerceAtLeast(0L)
                val finitePose = newer.position.all { it.isFinite() } && older.position.all { it.isFinite() } &&
                    newer.rotationQuaternion.all { it.isFinite() } && older.rotationQuaternion.all { it.isFinite() }
                var translation: Double? = null
                var rotation: Double? = null
                var olderNorm = 0.0
                var newerNorm = 0.0
                var rawTranslationSpeed = 0.0
                var rawAngularSpeed = 0.0
                if (gap in 1L..1_000_000_000L && finitePose) {
                    val seconds = gap / 1e9
                    var distanceSquared = 0.0
                    for (axis in 0..2) {
                        val delta = (newer.position[axis] - older.position[axis]).toDouble()
                        distanceSquared += delta * delta
                    }
                    rawTranslationSpeed = sqrt(distanceSquared) / seconds
                    translation = rawTranslationSpeed.takeIf { it.isFinite() }
                    var dot = 0.0
                    for (axis in 0..3) {
                        val a = older.rotationQuaternion[axis].toDouble()
                        val b = newer.rotationQuaternion[axis].toDouble()
                        dot += a * b; olderNorm += a * a; newerNorm += b * b
                    }
                    if (olderNorm > 0.0 && newerNorm > 0.0) {
                        val cosine = abs(dot / sqrt(olderNorm * newerNorm)).coerceIn(0.0, 1.0)
                        rawAngularSpeed = 2 * acos(cosine) * 180 / Math.PI / seconds
                        rotation = rawAngularSpeed.takeIf { it.isFinite() }
                    }
                }
                // Mirror the existing gate's refusal order only to identify its
                // first determining pair. This receipt never supplies its verdict.
                if (decidingPrefix) {
                    val pairGate = when {
                        !older.isTracking -> ExposureMotionGate.OLDER_NOT_TRACKING
                        gap !in 1L..1_000_000_000L || older.androidCameraTimestampNs <= 0L -> ExposureMotionGate.INVALID_GAP
                        !finitePose -> ExposureMotionGate.INVALID_POSE
                        olderNorm <= 0.0 || newerNorm <= 0.0 -> ExposureMotionGate.INVALID_QUATERNION
                        rawTranslationSpeed > 0.25 || rawAngularSpeed > 15.0 -> ExposureMotionGate.MOVING_TOO_FAST
                        else -> null
                    }
                    if (pairGate != null) {
                        if (pairGate == gate) firstRefusingPairIndex = pairIndex
                        decidingPrefix = false
                    } else if (historySpan >= 400_000_000L) decidingPrefix = false
                }
                if (index == poses.size - 2) { latestTranslation = translation; latestRotation = rotation }
                translation?.let(translations::add); rotation?.let(rotations::add)
                if (rotation != null && (maximumAngularSpeed == null || rotation > maximumAngularSpeed)) {
                    maximumAngularSpeed = rotation
                    maximumAngularPairIndex = pairIndex
                }
                if (stablePrefix && latest.isTracking && newer.isTracking && older.isTracking &&
                    translation != null && rotation != null && translation <= 0.25 && rotation <= 15.0) {
                    stableDwell = historySpan
                } else stablePrefix = false
                if (historySpan >= 400_000_000L) break
                newer = older
            }
        }
        // Ingress failures can precede pair velocity (for example a pose
        // revision of the same image or an owner change). Report that exact pair.
        if (motionIngressGate != null && poses.size >= 2) firstRefusingPairIndex = 0
        if (motionIngressGate != null) stableDwell = 0L
        fun median(values: List<Double>): Double? {
            if (values.isEmpty()) return null
            val sorted = values.sorted(); val middle = sorted.size / 2
            return if (sorted.size % 2 == 1) sorted[middle] else (sorted[middle - 1] + sorted[middle]) / 2
        }
        fun identity(pose: CachedPose) = ExposureMotionSampleIdentity(
            pose.timestampNs, pose.observedTimestampNs, pose.isTracking, pose.trackingState,
            pose.poseSource, pose.fixtureBindingGeneration, pose.fixtureGroupGeneration,
            pose.androidCameraTimestampNs,
        )
        fun pairDiagnostic(pairIndex: Int): ExposureMotionPairDiagnostic {
            val newer = poses[poses.size - 1 - pairIndex]
            val older = poses[poses.size - 2 - pairIndex]
            var distance: Double? = null
            var angle: Double? = null
            if (newer.position.all { it.isFinite() } && older.position.all { it.isFinite() }) {
                var distanceSquared = 0.0
                for (axis in 0..2) {
                    val delta = (newer.position[axis] - older.position[axis]).toDouble()
                    distanceSquared += delta * delta
                }
                distance = sqrt(distanceSquared).takeIf { it.isFinite() }
            }
            if (newer.rotationQuaternion.all { it.isFinite() } && older.rotationQuaternion.all { it.isFinite() }) {
                var dot = 0.0; var olderNorm = 0.0; var newerNorm = 0.0
                for (axis in 0..3) {
                    val a = older.rotationQuaternion[axis].toDouble()
                    val b = newer.rotationQuaternion[axis].toDouble()
                    dot += a * b; olderNorm += a * a; newerNorm += b * b
                }
                if (olderNorm > 0.0 && newerNorm > 0.0) {
                    val cosine = abs(dot / sqrt(olderNorm * newerNorm)).coerceIn(0.0, 1.0)
                    angle = (2 * acos(cosine) * 180 / Math.PI).takeIf { it.isFinite() }
                }
            }
            return ExposureMotionPairDiagnostic(pairIndex, newer.timestampNs - older.timestampNs,
                newer.observedTimestampNs - older.observedTimestampNs, distance, angle, identity(older), identity(newer))
        }
        return ExposureMotionDiagnostic(gate, nowNs, motionFreshnessObservedNs?.let { (nowNs - it) / 1e6 },
            historySpan / 1e6, stableDwell / 1e6, translations.size, latestTranslation, latestRotation,
            median(translations), median(rotations), translations.maxOrNull(), rotations.maxOrNull(),
            firstRefusingPairIndex?.let(::pairDiagnostic),
            maximumAngularPairIndex?.takeIf { it != firstRefusingPairIndex }?.let(::pairDiagnostic),
            minimumPositiveCameraGap, duplicatePairCount, debugTimestampReadMismatchCount)
    }

    /** Non-blocking shutter check over existing authoritative observed poses.
     * Limits match AutomaticCapturePolicyConfig: 0.25 m/s, 15 deg/s,
     * 400 ms stable dwell, 750 ms freshness and a one-second maximum gap.
     */
    internal fun isExposureMotionStable(
        nowNs: Long = System.nanoTime(),
        onDecision: ((ExposureMotionDiagnostic) -> Unit)? = null,
    ): Boolean {
        if (onDecision == null) {
            return synchronized(lock) { exposureMotionGateLocked(nowNs) == ExposureMotionGate.APPROVED }
        }
        val diagnostic = synchronized(lock) {
            exposureMotionDiagnostic(exposureMotionGateLocked(nowNs), nowNs)
        }
        // Formatting, logging and service callbacks must never compete with onFrame's pose lock.
        onDecision(diagnostic)
        return diagnostic.gate == ExposureMotionGate.APPROVED
    }

    private fun exposureMotionGateLocked(nowNs: Long): ExposureMotionGate {
        if (!hasMotionIngress) return ExposureMotionGate.NO_POSE
        motionIngressGate?.let { return it }
        val poses = motionPoses
        val latest = poses.lastOrNull() ?: return ExposureMotionGate.NO_POSE
        val age = nowNs - latest.observedTimestampNs
        if (age !in 0L..750_000_000L) return ExposureMotionGate.STALE
        if (!latest.isTracking) return ExposureMotionGate.NOT_TRACKING
        var newer = latest
        for (index in poses.size - 2 downTo 0) {
            val older = poses[index]
            val gap = newer.androidCameraTimestampNs - older.androidCameraTimestampNs
            if (!older.isTracking) return ExposureMotionGate.OLDER_NOT_TRACKING
            if (gap !in 1L..1_000_000_000L || older.androidCameraTimestampNs <= 0L) return ExposureMotionGate.INVALID_GAP
            if (!newer.position.all { it.isFinite() } || !older.position.all { it.isFinite() } ||
                !newer.rotationQuaternion.all { it.isFinite() } || !older.rotationQuaternion.all { it.isFinite() }) {
                return ExposureMotionGate.INVALID_POSE
            }
            val seconds = gap / 1e9
            var distanceSquared = 0.0
            var dot = 0.0
            var olderNorm = 0.0
            var newerNorm = 0.0
            for (axis in 0..2) {
                val delta = (newer.position[axis] - older.position[axis]).toDouble()
                distanceSquared += delta * delta
            }
            for (axis in 0..3) {
                val a = older.rotationQuaternion[axis].toDouble()
                val b = newer.rotationQuaternion[axis].toDouble()
                dot += a * b
                olderNorm += a * a
                newerNorm += b * b
            }
            if (olderNorm <= 0.0 || newerNorm <= 0.0) return ExposureMotionGate.INVALID_QUATERNION
            val cosine = kotlin.math.abs(dot / kotlin.math.sqrt(olderNorm * newerNorm)).coerceIn(0.0, 1.0)
            val angularSpeed = 2 * kotlin.math.acos(cosine) * 180 / Math.PI / seconds
            if (kotlin.math.sqrt(distanceSquared) / seconds > 0.25 || angularSpeed > 15.0) {
                return ExposureMotionGate.MOVING_TOO_FAST
            }
            if (latest.observedTimestampNs - older.observedTimestampNs >= 400_000_000L) {
                return ExposureMotionGate.APPROVED
            }
            newer = older
        }
        return ExposureMotionGate.INSUFFICIENT_DWELL
    }

    /** New immutable wire owner; history and conversion scratch never escape the lock. */
    internal fun latestPacked(sequence: Long): ByteArray? = synchronized(lock) {
        val pose = poses.lastSlot() ?: return@synchronized null
        pose.transform.copyInto(wireTransform)
        for (row in 0..3) {
            wireTransform[4 + row] = -wireTransform[4 + row]
            wireTransform[8 + row] = -wireTransform[8 + row]
        }
        quaternionFromTransform(wireTransform, wireQuaternion)
        val bytes = ByteArray(PackedPoseWireV2.SAMPLE_BYTES)
        PackedPoseWireV2.putLong(bytes, 0, sequence)
        PackedPoseWireV2.putLong(bytes, 8, pose.systemTimestampMs)
        PackedPoseWireV2.putLong(bytes, 16, pose.timestampNs)
        PackedPoseWireV2.putLong(bytes, 24, pose.bindingGeneration ?: 0L)
        PackedPoseWireV2.putLong(bytes, 32, pose.groupGeneration ?: 0L)
        PackedPoseWireV2.putFloat(bytes, 40, pose.confidence)
        PackedPoseWireV2.putInt(bytes, 44, when (pose.trackingState) {
            "tracking" -> 1
            "paused" -> 2
            "stopped" -> 3
            "synthetic" -> 4
            else -> 0
        } or if (pose.isTracking) 256 else 0)
        PackedPoseWireV2.putInt(bytes, 48, if (pose.poseSource == SYNTHETIC_POSE_SOURCE) 1 else 0)
        var offset = 52
        for (i in 12..14) { PackedPoseWireV2.putFloat(bytes, offset, wireTransform[i]); offset += 4 }
        for (value in wireQuaternion) { PackedPoseWireV2.putFloat(bytes, offset, value); offset += 4 }
        for (value in wireTransform) { PackedPoseWireV2.putFloat(bytes, offset, value); offset += 4 }
        for (value in pose.position) { PackedPoseWireV2.putFloat(bytes, offset, value); offset += 4 }
        for (value in pose.rotation) { PackedPoseWireV2.putFloat(bytes, offset, value); offset += 4 }
        for (value in pose.transform) { PackedPoseWireV2.putFloat(bytes, offset, value); offset += 4 }
        bytes
    }

    internal fun beginDebugFixture(): Boolean = synchronized(lock) {
        if (debugFixtureAnchor != null) return@synchronized true
        val anchor = poses.lastTrackingSlot()?.snapshot() ?: return@synchronized false
        resetMotionLocked()
        debugFixtureAnchor = anchor
        debugFixtureOffsetX = 0.0f
        debugFixtureSource = LIVE_ANCHOR_POSE_SOURCE
        debugFixtureBindingGeneration = null
        debugFixtureGroupGeneration = null
        true
    }

    /**
     * Seeds one bounded debug pose for a fully synthetic exposure campaign.
     *
     * This path is deliberately separate from [beginDebugFixture]: it never
     * turns a real ARCore frame into a synthetic claim and requires the
     * ownership generations supplied by the debug orchestration seam.
     */
    internal fun beginSyntheticDebugFixture(
        bindingGeneration: Long,
        groupGeneration: Long,
    ): CachedPose = synchronized(lock) {
        require(bindingGeneration > 0L) { "binding generation must be positive" }
        require(groupGeneration > 0L) { "group generation must be positive" }
        val current = debugFixtureAnchor
        if (current != null) {
            check(debugFixtureSource == SYNTHETIC_POSE_SOURCE) {
                "a live pose fixture is already active"
            }
            check(debugFixtureBindingGeneration == bindingGeneration) {
                "synthetic pose binding generation does not match"
            }
            check(debugFixtureGroupGeneration == groupGeneration) {
                "synthetic pose group generation does not match"
            }
            return@synchronized current
        }
        val timestampNs = System.nanoTime()
        val synthetic = CachedPose(
            position = floatArrayOf(0.0f, 0.0f, 0.0f),
            rotationQuaternion = floatArrayOf(0.0f, 0.0f, 0.0f, 1.0f),
            transform = floatArrayOf(
                1.0f, 0.0f, 0.0f, 0.0f,
                0.0f, 1.0f, 0.0f, 0.0f,
                0.0f, 0.0f, 1.0f, 0.0f,
                0.0f, 0.0f, 0.0f, 1.0f,
            ),
            timestampNs = timestampNs,
            systemTimestampMs = System.currentTimeMillis(),
            isTracking = true,
            confidence = 1.0f,
            trackingState = "synthetic",
            observedTimestampNs = timestampNs,
            poseSource = SYNTHETIC_POSE_SOURCE,
            fixtureBindingGeneration = bindingGeneration,
            fixtureGroupGeneration = groupGeneration,
        )
        resetMotionLocked()
        poses.insert(synthetic.timestampNs)?.set(synthetic)
        debugFixtureAnchor = synthetic
        debugFixtureOffsetX = 0.0f
        debugFixtureSource = SYNTHETIC_POSE_SOURCE
        debugFixtureBindingGeneration = bindingGeneration
        debugFixtureGroupGeneration = groupGeneration
        lock.notifyAll()
        synthetic
    }

    /** Changes an explicitly synthetic head pose without relabelling a live ARCore anchor. */
    internal fun setSyntheticDebugView(
        transform: DoubleArray,
        quaternion: FloatArray,
        bindingGeneration: Long,
        groupGeneration: Long,
    ) = synchronized(lock) {
        val current = checkNotNull(debugFixtureAnchor) { "synthetic pose fixture is not active" }
        check(debugFixtureSource == SYNTHETIC_POSE_SOURCE) { "live pose fixture cannot accept synthetic head views" }
        check(debugFixtureBindingGeneration == bindingGeneration && debugFixtureGroupGeneration == groupGeneration) {
            "synthetic head view ownership changed"
        }
        require(transform.size == 16 && transform.all { it.isFinite() })
        require(quaternion.size == 4 && quaternion.all { it.isFinite() })
        val now = System.nanoTime()
        val next = current.copy(
            position = FloatArray(3) { transform[12+it].toFloat() },
            rotationQuaternion = quaternion.copyOf(),
            transform = FloatArray(16) { transform[it].toFloat() },
            timestampNs = now, observedTimestampNs = now,
            systemTimestampMs = System.currentTimeMillis(),
        )
        resetMotionLocked()
        debugFixtureAnchor = next
        debugFixtureOffsetX = 0.0f
        poses.insert(now)?.set(next)
        lock.notifyAll()
    }

    internal fun setDebugFixtureManualView() = synchronized(lock) {
        check(debugFixtureAnchor != null) { "synthetic pose fixture is not active" }
        resetMotionLocked()
        debugFixtureOffsetX = 0.24f
    }

    internal fun setDebugFixtureAutomaticRevisit() = synchronized(lock) {
        check(debugFixtureAnchor != null) { "synthetic pose fixture is not active" }
        resetMotionLocked()
        debugFixtureOffsetX = 0.0f
    }

    internal fun debugFixtureTransform(): DoubleArray? = synchronized(lock) {
        val anchor = debugFixtureAnchor ?: return@synchronized null
        val transform = anchor.transform.clone()
        transform[12] += debugFixtureOffsetX
        DoubleArray(transform.size) { transform[it].toDouble() }
    }

    internal fun clearDebugFixture() {
        synchronized(lock) {
            resetMotionLocked()
            debugFixtureAnchor = null
            debugFixtureOffsetX = 0.0f
            debugFixtureSource = LIVE_ANCHOR_POSE_SOURCE
            debugFixtureBindingGeneration = null
            debugFixtureGroupGeneration = null
            poses.removeAll { it.poseSource == SYNTHETIC_POSE_SOURCE }
        }
    }

    private data class DebugFixtureSnapshot(
        val anchor: CachedPose,
        val offsetX: Float,
        val source: String,
        val bindingGeneration: Long?,
        val groupGeneration: Long?,
    )

    fun alignmentDiagnostics(captureTiming: CaptureTiming): String = synchronized(lock) {
        val tracked = poses.trackedSlots()
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
            while (poses.lastTrackingSlot() == null) {
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
            "poseSource" to alignedPose.pose.poseSource,
            "fixtureBindingGeneration" to alignedPose.pose.fixtureBindingGeneration,
            "fixtureGroupGeneration" to alignedPose.pose.fixtureGroupGeneration,
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

                val latestTracked = poses.lastTrackingSlot()
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
        val trackedPoses = poses.trackedSlots()
        if (trackedPoses.isEmpty()) {
            return null
        }

        trackedPoses.minByOrNull { abs(it.timestampNs - targetTimestampNs) }
            ?.takeIf { abs(it.timestampNs - targetTimestampNs) <= EXACT_MATCH_THRESHOLD_NS }
            ?.let { exactPose ->
            return AlignedPose(
                pose = exactPose.snapshot(),
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
        val trackedPoses = poses.trackedSlots()
        if (trackedPoses.isEmpty()) {
            return null
        }

        trackedPoses.minByOrNull { abs(it.observedTimestampNs - targetTimestampNs) }
            ?.takeIf { abs(it.observedTimestampNs - targetTimestampNs) <= EXACT_MATCH_THRESHOLD_NS }
            ?.let { exactPose ->
                return AlignedPose(
                    pose = exactPose.snapshot(),
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
        val trackedPoses = poses.trackedSlots()
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
                pose = nearestSensorPose.snapshot(),
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
            pose = nearestObservedPose.snapshot(),
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

    private fun quaternionFromTransform(transform: FloatArray): FloatArray =
        FloatArray(4).also { quaternionFromTransform(transform, it) }

    private fun quaternionFromTransform(transform: FloatArray, quaternion: FloatArray) {
        val m00 = transform[0]
        val m11 = transform[5]
        val m22 = transform[10]
        val trace = m00 + m11 + m22

            if (trace > 0f) {
                val scale = sqrt(trace + 1f) * 2f
                quaternion[0] = (transform[6] - transform[9]) / scale
                quaternion[1] = (transform[8] - transform[2]) / scale
                quaternion[2] = (transform[1] - transform[4]) / scale
                quaternion[3] = 0.25f * scale
            } else if (m00 > m11 && m00 > m22) {
                val scale = sqrt(1f + m00 - m11 - m22) * 2f
                quaternion[0] = 0.25f * scale
                quaternion[1] = (transform[4] + transform[1]) / scale
                quaternion[2] = (transform[8] + transform[2]) / scale
                quaternion[3] = (transform[6] - transform[9]) / scale
            } else if (m11 > m22) {
                val scale = sqrt(1f + m11 - m00 - m22) * 2f
                quaternion[0] = (transform[4] + transform[1]) / scale
                quaternion[1] = 0.25f * scale
                quaternion[2] = (transform[9] + transform[6]) / scale
                quaternion[3] = (transform[8] - transform[2]) / scale
            } else {
                val scale = sqrt(1f + m22 - m00 - m11) * 2f
                quaternion[0] = (transform[8] + transform[2]) / scale
                quaternion[1] = (transform[9] + transform[6]) / scale
                quaternion[2] = 0.25f * scale
                quaternion[3] = (transform[1] - transform[4]) / scale
            }

        val magnitude = sqrt(quaternion[0] * quaternion[0] + quaternion[1] * quaternion[1] +
            quaternion[2] * quaternion[2] + quaternion[3] * quaternion[3])
        if (magnitude == 0f) {
            quaternion.fill(0f)
            quaternion[3] = 1f
        } else for (i in 0..3) quaternion[i] /= magnitude
    }

    /** Fixed primitive history; a snapshot is created only when a caller takes ownership. */
    private class PoseHistory(capacity: Int) : AbstractMutableList<CachedPose>() {
        init { require(capacity > 0) }
        private val slots = Array(capacity) { PoseSlot() }
        private val tracked = ArrayList<PoseSlot>(capacity)
        override var size = 0
            private set
        override fun get(index: Int): CachedPose {
            require(index in 0 until size)
            return slots[index].snapshot()
        }
        fun lastSlot(): PoseSlot? = if (size == 0) null else slots[size - 1]
        fun lastTrackingSlot(): PoseSlot? {
            for (index in size - 1 downTo 0) if (slots[index].isTracking) return slots[index]
            return null
        }
        fun trackedSlots(): List<PoseSlot> {
            tracked.clear()
            for (index in 0 until size) if (slots[index].isTracking) tracked.add(slots[index])
            return tracked
        }
        fun append(): PoseSlot {
            if (size < slots.size) return slots[size++]
            val freed = slots[0]
            for (index in 1 until size) slots[index - 1] = slots[index]
            slots[size - 1] = freed
            return freed
        }
        fun insert(timestamp: Long): PoseSlot? {
            var index = 0
            while (index < size && slots[index].timestampNs <= timestamp) index++
            if (size == slots.size && index == 0) return null
            val slot: PoseSlot
            if (size == slots.size) {
                slot = slots[0]
                for (i in 1 until index) slots[i - 1] = slots[i]
                index--
            } else {
                slot = slots[size]
                for (i in size downTo index + 1) slots[i] = slots[i - 1]
                size++
            }
            slots[index] = slot
            return slot
        }
        override fun add(index: Int, element: CachedPose) {
            require(index == size) { "History insertion is timestamp ordered" }
            insert(element.timestampNs)?.set(element)
        }
        override fun set(index: Int, element: CachedPose): CachedPose =
            get(index).also { slots[index].set(element) }
        override fun clear() {
            size = 0
            tracked.clear()
        }
        override fun removeAt(index: Int): CachedPose {
            val result = get(index)
            val freed = slots[index]
            for (i in index until size - 1) slots[i] = slots[i + 1]
            slots[--size] = freed
            return result
        }
    }

    private class PoseSlot {
        val position = FloatArray(3)
        val rotation = FloatArray(4)
        val rotationQuaternion: FloatArray get() = rotation
        val transform = FloatArray(16)
        var timestampNs = 0L
        var androidCameraTimestampNs = 0L
        var systemTimestampMs = 0L
        var observedTimestampNs = 0L
        var isTracking = false
        var confidence = 0f
        var trackingState = "stopped"
        var poseSource = LIVE_ANCHOR_POSE_SOURCE
        var bindingGeneration: Long? = null
        var groupGeneration: Long? = null
        fun copyFrom(pose: PoseSlot) {
            pose.position.copyInto(position)
            pose.rotation.copyInto(rotation)
            pose.transform.copyInto(transform)
            timestampNs = pose.timestampNs
            androidCameraTimestampNs = pose.androidCameraTimestampNs
            systemTimestampMs = pose.systemTimestampMs
            observedTimestampNs = pose.observedTimestampNs
            isTracking = pose.isTracking
            confidence = pose.confidence
            trackingState = pose.trackingState
            poseSource = pose.poseSource
            bindingGeneration = pose.bindingGeneration
            groupGeneration = pose.groupGeneration
        }
        fun set(pose: CachedPose) {
            pose.position.copyInto(position)
            pose.rotationQuaternion.copyInto(rotation)
            pose.transform.copyInto(transform)
            timestampNs = pose.timestampNs
            androidCameraTimestampNs = pose.androidCameraTimestampNs
            systemTimestampMs = pose.systemTimestampMs
            observedTimestampNs = pose.observedTimestampNs
            isTracking = pose.isTracking
            confidence = pose.confidence
            trackingState = pose.trackingState
            poseSource = pose.poseSource
            bindingGeneration = pose.fixtureBindingGeneration
            groupGeneration = pose.fixtureGroupGeneration
        }
        fun snapshot() = CachedPose(position.clone(), rotation.clone(), transform.clone(), timestampNs,
            systemTimestampMs, isTracking, confidence, trackingState, observedTimestampNs,
            poseSource, bindingGeneration, groupGeneration, androidCameraTimestampNs)
    }
}
