package com.uhg0.ar_flutter_plugin_2.visibilitygrid

/** PROVIDED samples have caller-owned calibration/identity, rather than an acquired ARCore Image. */
enum class DepthAcquisitionMode { PROVIDED, AUTOMATIC, RAW }

enum class DepthAcquisitionRefusal { INVALID_TIMESTAMP, STALE_AUTOMATIC, CONFIDENCE_PAIR }

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
    if (metadata.timestampNs <= 0 || metadata.arFrameTimestampNs <= 0 ||
        metadata.timestampNs > metadata.arFrameTimestampNs) return DepthAcquisitionRefusal.INVALID_TIMESTAMP
    if (metadata.acquisitionMode == DepthAcquisitionMode.AUTOMATIC &&
        metadata.timestampNs != metadata.arFrameTimestampNs) return DepthAcquisitionRefusal.STALE_AUTOMATIC
    if (metadata.acquisitionMode == DepthAcquisitionMode.RAW &&
        (confidence == null || confidence.width != depth.width || confidence.height != depth.height)) {
        return DepthAcquisitionRefusal.CONFIDENCE_PAIR
    }
    return null
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
    const val METADATA_INCREMENT_BYTES = 32L
    const val OBSERVATION_INCREMENT_BYTES = 24L
    // Two raw metadata envelopes can overlap while binding the acquired Image identity.
    const val PREPARED_METADATA_BYTES = 2L * METADATA_INCREMENT_BYTES
    const val TWO_OBSERVATION_BYTES = 2L * OBSERVATION_INCREMENT_BYTES
    const val RUNTIME_SCALAR_BYTES = 136L
    const val HEALTH_SCALAR_BYTES = 96L
    const val FUNDING_PUBLICATION_BYTES = 248L // Pending request, prior publication and completed publication; one reference.
    const val PREPARATION_CALLBACK_BYTES = 120L // Runtime completion56 + mapper executor closure64.
    const val PREPARATION_FLAG_BYTES = 8L
    const val RESERVATION_SCALAR_BYTES = 16L
    const val MAXIMUM_INCREMENT_BYTES = PREPARED_METADATA_BYTES + TWO_OBSERVATION_BYTES +
        RUNTIME_SCALAR_BYTES + HEALTH_SCALAR_BYTES + FUNDING_PUBLICATION_BYTES +
        PREPARATION_CALLBACK_BYTES + PREPARATION_FLAG_BYTES + RESERVATION_SCALAR_BYTES
}
