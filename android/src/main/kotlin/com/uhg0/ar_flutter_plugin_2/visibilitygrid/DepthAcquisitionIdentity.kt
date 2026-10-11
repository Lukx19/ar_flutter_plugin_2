package com.uhg0.ar_flutter_plugin_2.visibilitygrid

import com.uhg0.ar_flutter_plugin_2.BuildConfig

/** PROVIDED samples have caller-owned calibration/identity, rather than an acquired ARCore Image. */
enum class DepthAcquisitionMode {
    PROVIDED, AUTOMATIC, RAW, AUTOMATIC_CURRENT_FRAME_EXPERIMENTAL;

    val projectionProvenance: DepthProjectionProvenance get() = when (this) {
        RAW -> DepthProjectionProvenance.SDK_RAW_CURRENT_FRAME
        AUTOMATIC_CURRENT_FRAME_EXPERIMENTAL -> DepthProjectionProvenance.DENSE_CURRENT_FRAME_EXPERIMENTAL
        PROVIDED, AUTOMATIC -> DepthProjectionProvenance.UNQUALIFIED
    }
}

enum class DepthProjectionProvenance {
    UNQUALIFIED, SDK_RAW_CURRENT_FRAME, DENSE_CURRENT_FRAME_EXPERIMENTAL,
}

/** Build-only qualification; null/legacy preference, RAW and release keep their existing route. */
internal fun denseCurrentFrameExperimentForPreference(
    preference: DepthSourcePreference?,
    debug: Boolean = BuildConfig.DEBUG,
    enabled: Boolean = BuildConfig.DENSE_CURRENT_FRAME_EXPERIMENT,
): Boolean = debug && enabled && preference == DepthSourcePreference.DENSE

internal fun qualifiedDepthProjectionProvenance(metadata: RawDepthFrameMetadata): DepthProjectionProvenance =
    when (metadata.acquisitionMode) {
        DepthAcquisitionMode.RAW -> if (rawProjectionGeometryValid(metadata)) DepthProjectionProvenance.SDK_RAW_CURRENT_FRAME
            else DepthProjectionProvenance.UNQUALIFIED
        DepthAcquisitionMode.AUTOMATIC_CURRENT_FRAME_EXPERIMENTAL -> if (denseCurrentFrameGeometryValid(metadata))
            DepthProjectionProvenance.DENSE_CURRENT_FRAME_EXPERIMENTAL else DepthProjectionProvenance.UNQUALIFIED
        else -> DepthProjectionProvenance.UNQUALIFIED
    }

/** A stable frozen Frame is experimental Dense association, not an SDK fallback guarantee. */
internal fun denseCurrentFrameGeometryValid(metadata: RawDepthFrameMetadata): Boolean {
    if (metadata.width !in 1..16_384 || metadata.height !in 1..16_384 ||
        metadata.sourceImageWidth <= 0 || metadata.sourceImageHeight <= 0 ||
        metadata.arFrameTimestampNs <= 0L || metadata.acquisitionFrameBeforeNs <= 0L ||
        metadata.acquisitionFrameBeforeNs != metadata.acquisitionFrameAfterNs || !metadata.tracking ||
        metadata.worldFromCameraGl.size != 16 || !metadata.worldFromCameraGl.all(Double::isFinite) ||
        !metadata.intrinsics.fx.isFinite() || metadata.intrinsics.fx <= 0 ||
        !metadata.intrinsics.fy.isFinite() || metadata.intrinsics.fy <= 0 ||
        !metadata.intrinsics.cx.isFinite() || !metadata.intrinsics.cy.isFinite()) return false
    val before = metadata.acquisitionAndroidCameraBeforeNs
    val after = metadata.acquisitionAndroidCameraAfterNs
    return if (before > 0L || after > 0L) before > 0L && before == after else before == 0L && after == 0L
}

enum class DepthAcquisitionRefusal { INVALID_TIMESTAMP, STALE_AUTOMATIC, CONFIDENCE_PAIR, INVALID_PROJECTION }

/** The acquired estimate time and the pose's current AR frame time are different clocks of identity. */
internal fun acquiredDepthMetadata(
    metadata: RawDepthFrameMetadata,
    depth: RawDepthImage,
    confidence: RawDepthImage?,
): RawDepthFrameMetadata {
    if (metadata.acquisitionMode == DepthAcquisitionMode.PROVIDED) return metadata
    val confidenceTime = confidence?.timestampNs ?: 0L
    if (metadata.timestampNs == depth.timestampNs && metadata.confidenceTimestampNs == confidenceTime) return metadata
    return metadata.copy(timestampNs = depth.timestampNs, confidenceTimestampNs = confidenceTime)
}

internal fun depthAcquisitionRefusal(
    metadata: RawDepthFrameMetadata,
    depth: RawDepthImage,
    confidence: RawDepthImage?,
): DepthAcquisitionRefusal? {
    if (metadata.acquisitionMode == DepthAcquisitionMode.PROVIDED) return null
    depthImageIdentityRefusal(metadata.timestampNs, metadata.cameraImageTimestampNs,
        metadata.arFrameTimestampNs, metadata.acquisitionMode, rawProjectionGeometryValid(metadata),
        qualifiedDepthProjectionProvenance(metadata))?.let { return it }
    if (metadata.acquisitionMode == DepthAcquisitionMode.AUTOMATIC_CURRENT_FRAME_EXPERIMENTAL &&
        (!denseCurrentFrameGeometryValid(metadata) || metadata.width != depth.width || metadata.height != depth.height)) {
        return DepthAcquisitionRefusal.INVALID_PROJECTION
    }
    if (metadata.acquisitionMode == DepthAcquisitionMode.RAW &&
        (!rawProjectionGeometryValid(metadata) || metadata.width != depth.width || metadata.height != depth.height)) {
        return DepthAcquisitionRefusal.INVALID_PROJECTION
    }
    if (metadata.acquisitionMode == DepthAcquisitionMode.RAW &&
        (confidence == null || confidence.width != depth.width || confidence.height != depth.height)) {
        return DepthAcquisitionRefusal.CONFIDENCE_PAIR
    }
    return null
}

/** Dense keeps its existing gate. RAW separates estimate identity from current projection. */
internal fun depthImageIdentityRefusal(
    imageTimestampNs: Long,
    cameraImageTimestampNs: Long,
    arFrameTimestampNs: Long,
    mode: DepthAcquisitionMode,
    rawProjectionQualified: Boolean = false,
    projectionProvenance: DepthProjectionProvenance = if (rawProjectionQualified)
        DepthProjectionProvenance.SDK_RAW_CURRENT_FRAME else DepthProjectionProvenance.UNQUALIFIED,
    denseCurrentFrameExperimentEnabled: Boolean = BuildConfig.DEBUG && BuildConfig.DENSE_CURRENT_FRAME_EXPERIMENT,
): DepthAcquisitionRefusal? {
    if (mode == DepthAcquisitionMode.PROVIDED) return null
    if (mode == DepthAcquisitionMode.AUTOMATIC_CURRENT_FRAME_EXPERIMENTAL) {
        if (imageTimestampNs <= 0L || arFrameTimestampNs <= 0L) return DepthAcquisitionRefusal.INVALID_TIMESTAMP
        return if (denseCurrentFrameExperimentEnabled &&
            projectionProvenance == DepthProjectionProvenance.DENSE_CURRENT_FRAME_EXPERIMENTAL) null
        else DepthAcquisitionRefusal.INVALID_PROJECTION
    }
    if (mode == DepthAcquisitionMode.RAW) {
        if (imageTimestampNs <= 0L || arFrameTimestampNs <= 0L) return DepthAcquisitionRefusal.INVALID_TIMESTAMP
        return if (rawProjectionQualified) null else DepthAcquisitionRefusal.INVALID_PROJECTION
    }
    if (imageTimestampNs <= 0L || cameraImageTimestampNs <= 0L || arFrameTimestampNs <= 0L ||
        imageTimestampNs > cameraImageTimestampNs) return DepthAcquisitionRefusal.INVALID_TIMESTAMP
    if (mode == DepthAcquisitionMode.AUTOMATIC && imageTimestampNs != cameraImageTimestampNs) {
        return DepthAcquisitionRefusal.STALE_AUTOMATIC
    }
    return null
}

/** SDK RAW is projected in the current Frame; estimate time is an independent identity. */
internal fun rawProjectionGeometryValid(metadata: RawDepthFrameMetadata): Boolean {
    if (metadata.arFrameTimestampNs <= 0L || metadata.acquisitionFrameBeforeNs <= 0L ||
        metadata.acquisitionFrameBeforeNs != metadata.acquisitionFrameAfterNs || !metadata.tracking ||
        metadata.worldFromCameraGl.size != 16 || !metadata.worldFromCameraGl.all(Double::isFinite)) return false
    val before = metadata.acquisitionAndroidCameraBeforeNs
    val after = metadata.acquisitionAndroidCameraAfterNs
    return if (before > 0L || after > 0L) before > 0L && before == after else before == after
}

/** Diagnostic bounds describe the existing decision; they confer no clock comparability. */
internal fun depthRefusalBound(reason: DepthAcquisitionRefusal, metadata: RawDepthFrameMetadata): String = when {
    reason == DepthAcquisitionRefusal.STALE_AUTOMATIC -> "automaticImageNotEqualAndroidCamera"
    reason == DepthAcquisitionRefusal.CONFIDENCE_PAIR -> "confidencePair"
    reason == DepthAcquisitionRefusal.INVALID_PROJECTION && !metadata.tracking -> "rawProjectionUntracked"
    reason == DepthAcquisitionRefusal.INVALID_PROJECTION -> "rawProjectionBoundaryOrCalibration"
    metadata.acquisitionMode == DepthAcquisitionMode.RAW && metadata.arFrameTimestampNs <= 0L -> "arFrameNonPositive"
    metadata.timestampNs <= 0L -> "imageNonPositive"
    metadata.cameraImageTimestampNs <= 0L -> "androidCameraNonPositive"
    metadata.arFrameTimestampNs <= 0L -> "arFrameNonPositive"
    metadata.timestampNs > metadata.cameraImageTimestampNs -> "imageAfterAndroidCamera"
    else -> "unclassified"
}

/** Retryable preparation never becomes a definitive owner-qualified funding refusal. */
internal enum class DepthAcquisitionPreparationResult { FUNDED, REFUSED, RETRYABLE }

/** Only scalar generations are retained; this publication cannot retain an obsolete group matrix. */
internal data class DepthAcquisitionFunding(
    val bindingGeneration: Long,
    val sessionGeneration: Long,
    val groupGeneration: Long,
    val lifecycleSequence: Long,
    val operationGeneration: Long,
    val funded: Boolean,
) {
    // The Int fits existing object alignment padding; no extra owner envelope.
    var debugTransientMask: Int = 0

    fun matches(owner: VisibilityObservationOwnership): Boolean =
        bindingGeneration == owner.bindingGeneration && sessionGeneration == owner.sessionGeneration &&
            groupGeneration == owner.groupGeneration && lifecycleSequence == owner.lifecycleSequence &&
            operationGeneration == owner.operationGeneration

    companion object {
        fun from(owner: VisibilityObservationOwnership, funded: Boolean) = DepthAcquisitionFunding(
            owner.bindingGeneration, owner.sessionGeneration, owner.groupGeneration,
            owner.lifecycleSequence, owner.operationGeneration, funded,
        )
    }
}

/** Assigned retained capacity; VM allocation/padding and cold diagnostic wire maps are not measured here. */
internal object DepthAcquisitionPortableMemory {
    const val RESERVATION_BYTES = 1_024L
    const val METADATA_INCREMENT_BYTES = 64L // Existing after-clock fields are reused; two before clocks and one sequence.
    const val OBSERVATION_INCREMENT_BYTES = 40L // Three8-byte scalars, two Ints8, provenance enum reference8; replaces padded Boolean.
    // Two raw metadata envelopes can overlap while binding the acquired Image identity.
    const val PREPARED_METADATA_BYTES = 2L * METADATA_INCREMENT_BYTES
    const val TWO_OBSERVATION_BYTES = 2L * OBSERVATION_INCREMENT_BYTES
    const val RUNTIME_SCALAR_BYTES = 320L // Two fixed7-long arrays144, references16 and reason references16.
    const val HEALTH_SCALAR_BYTES = 104L
    // Header32 + five Longs40 + Boolean1 + diagnostic Int4 =77, aligned to80.
    const val FUNDING_PUBLICATION_OBJECT_BYTES = 80L
    const val FUNDING_PUBLICATION_BYTES = 3L * FUNDING_PUBLICATION_OBJECT_BYTES + 8L
    const val PREPARATION_CALLBACK_BYTES = 120L // Runtime completion56 + mapper executor closure64.
    const val PREPARATION_FLAG_BYTES = 8L
    const val RESERVATION_SCALAR_BYTES = 16L
    const val MAXIMUM_INCREMENT_BYTES = PREPARED_METADATA_BYTES + TWO_OBSERVATION_BYTES +
        RUNTIME_SCALAR_BYTES + HEALTH_SCALAR_BYTES + FUNDING_PUBLICATION_BYTES +
        PREPARATION_CALLBACK_BYTES + PREPARATION_FLAG_BYTES + RESERVATION_SCALAR_BYTES
}
