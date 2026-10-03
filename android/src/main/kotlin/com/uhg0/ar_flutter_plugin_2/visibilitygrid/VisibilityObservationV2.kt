package com.uhg0.ar_flutter_plugin_2.visibilitygrid

import java.util.Collections
import com.uhg0.ar_flutter_plugin_2.visibilityprotocol.CoordinateFrameTransforms

/** The exact immutable coordinate frame accepted by one V2 group binding. */
internal class VisibilityGroupFrame private constructor(
    val groupFromWorldGl: List<Double>,
    val worldFromGroupGl: List<Double>,
    val voxelSizeMicrometres: Int,
    val modelCapacity: Int,
) {
    init {
        require(groupFromWorldGl.size == 16 && groupFromWorldGl.all(Double::isFinite))
        require(worldFromGroupGl.size == 16 && worldFromGroupGl.all(Double::isFinite))
        require(voxelSizeMicrometres > 0)
        require(modelCapacity in 1..100_000)
        require(CoordinateFrameTransforms.areFiniteAffineInverses(groupFromWorldGl, worldFromGroupGl))
    }

    companion object {
        fun copyOf(
            groupFromWorldGl: DoubleArray,
            worldFromGroupGl: DoubleArray,
            voxelSizeMicrometres: Int,
            modelCapacity: Int,
        ): VisibilityGroupFrame = VisibilityGroupFrame(
            Collections.unmodifiableList(groupFromWorldGl.copyOf().toList()),
            Collections.unmodifiableList(worldFromGroupGl.copyOf().toList()),
            voxelSizeMicrometres,
            modelCapacity,
        )
    }

    override fun equals(other: Any?): Boolean =
        other is VisibilityGroupFrame &&
            groupFromWorldGl == other.groupFromWorldGl &&
            worldFromGroupGl == other.worldFromGroupGl &&
            voxelSizeMicrometres == other.voxelSizeMicrometres &&
            modelCapacity == other.modelCapacity

    override fun hashCode(): Int = listOf(
        groupFromWorldGl,
        worldFromGroupGl,
        voxelSizeMicrometres,
        modelCapacity,
    ).hashCode()

    override fun toString(): String =
        "VisibilityGroupFrame(groupFromWorldGl=$groupFromWorldGl, " +
            "worldFromGroupGl=$worldFromGroupGl, voxelSizeMicrometres=$voxelSizeMicrometres, " +
            "modelCapacity=$modelCapacity)"
}

internal const val VISIBILITY_OBSERVATION_VERSION = "visibility_observation_v2"
internal const val V2_FEATURE_SAMPLE_CAPACITY = 1_200
internal const val V2_DEPTH_SAMPLE_CAPACITY = 4_096
internal const val V2_SENSOR_HANDOFF_CAPACITY_BYTES = 1_048_576L

internal enum class VisibilityObservationSource(val wireName: String) {
    ARCORE_FEATURE("arcoreFeature"),
    /** ARCore depth: sparse sensor depth with confidence or dense predicted depth. */
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
    val groupFrame: VisibilityGroupFrame,
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
    internal val packedSamples: BorrowedFeatureSamples? = null,
) : AutoCloseable {
    val samples: List<VisibilityFeatureSample> = copySamples(samples)
    val sampleCount: Int get() = packedSamples?.count ?: samples.size

    init {
        require(version == VISIBILITY_OBSERVATION_VERSION)
        require(frame.source == VisibilityObservationSource.ARCORE_FEATURE ||
            frame.source == VisibilityObservationSource.SYNTHETIC_FEATURE)
        require(sampleCount in 1..V2_FEATURE_SAMPLE_CAPACITY)
        require(packedSamples == null || samples.isEmpty())
        require(packedSamples == null || packedSamples.matches(ownership))
        require(packedSamples == null || sourceRejectedSamples == packedSamples.rejectedCount)
        require(samples.all(VisibilityFeatureSample::isValid))
        require(samples.map(VisibilityFeatureSample::id).distinct().size == samples.size)
        require(sourceRejectedSamples >= 0)
        require(payloadBytes == FEATURE_FIXED_BYTES + sampleCount * FEATURE_SAMPLE_BYTES)
    }

    internal fun closePackedSamples() {
        packedSamples?.close()
    }

    override fun close() {
        closePackedSamples()
    }

    companion object {
        const val FEATURE_FIXED_BYTES = 512
        const val FEATURE_SAMPLE_BYTES = 32

        fun copySamples(samples: List<VisibilityFeatureSample>): List<VisibilityFeatureSample> =
            Collections.unmodifiableList(ArrayList(samples))

        fun fromPacked(
            ownership: VisibilityObservationOwnership,
            frame: VisibilityObservationFrame,
            packedSamples: BorrowedFeatureSamples,
            sourceRejectedSamples: Int,
        ): VisibilityFeatureObservation = VisibilityFeatureObservation(
            ownership = ownership,
            frame = frame,
            samples = emptyList(),
            sourceRejectedSamples = sourceRejectedSamples,
            payloadBytes = FEATURE_FIXED_BYTES + packedSamples.count * FEATURE_SAMPLE_BYTES,
            packedSamples = packedSamples,
        )
    }
}

internal class VisibilityDepthObservation(
    val version: String = VISIBILITY_OBSERVATION_VERSION,
    val ownership: VisibilityObservationOwnership,
    val frame: VisibilityObservationFrame,
    samples: List<VisibilityDepthSample>,
    val sourceRejectedSamples: Int,
    val payloadBytes: Int,
    internal val packedSamples: BorrowedDepthSamples? = null,
) : AutoCloseable {
    internal var debugOfferLedger: DepthOfferTimingLedger? = null
    internal var debugOfferId: Long = 0
    internal fun finishDebugOffer(state: Int) { debugOfferLedger?.finish(debugOfferId, state) }
    val samples: List<VisibilityDepthSample> = copySamples(samples)
    val sampleCount: Int get() = packedSamples?.count ?: samples.size

    init {
        require(version == VISIBILITY_OBSERVATION_VERSION)
        require(frame.source == VisibilityObservationSource.ARCORE_RAW_DEPTH ||
            frame.source == VisibilityObservationSource.SYNTHETIC_DEPTH)
        require(sampleCount <= V2_DEPTH_SAMPLE_CAPACITY)
        require(packedSamples == null || samples.isEmpty())
        require(packedSamples == null || packedSamples.matches(ownership))
        require(packedSamples == null || sourceRejectedSamples == packedSamples.rejectedCount)
        require(samples.all {
            it.x in 0 until frame.intrinsics.imageWidth &&
                it.y in 0 until frame.intrinsics.imageHeight &&
                it.depthMillimeters in 0..65_535 && it.confidence in 0..255
        })
        require(sourceRejectedSamples >= 0)
        require(payloadBytes == DEPTH_FIXED_BYTES + sampleCount * DEPTH_SAMPLE_BYTES)
    }

    internal fun closePackedSamples() {
        packedSamples?.close()
    }

    override fun close() {
        debugOfferLedger?.refuseUnretained(debugOfferId)
        closePackedSamples()
    }

    companion object {
        const val DEPTH_FIXED_BYTES = 512
        const val DEPTH_SAMPLE_BYTES = 16

        fun copySamples(samples: List<VisibilityDepthSample>): List<VisibilityDepthSample> {
            require(samples.size <= V2_DEPTH_SAMPLE_CAPACITY)
            return Collections.unmodifiableList(ArrayList(samples))
        }

        fun fromPacked(
            ownership: VisibilityObservationOwnership,
            frame: VisibilityObservationFrame,
            packedSamples: BorrowedDepthSamples,
            sourceRejectedSamples: Int,
        ): VisibilityDepthObservation {
            require(packedSamples.count > 0)
            return VisibilityDepthObservation(
                ownership = ownership,
                frame = frame,
                samples = emptyList(),
                sourceRejectedSamples = sourceRejectedSamples,
                payloadBytes = DEPTH_FIXED_BYTES + packedSamples.count * DEPTH_SAMPLE_BYTES,
                packedSamples = packedSamples,
            )
        }
    }
}

internal interface VisibilityObservationMapper : AutoCloseable {
    fun admitFeature(observation: VisibilityFeatureObservation)

    fun admitDepth(observation: VisibilityDepthObservation)

    /**
     * Gives a mapper with a retained latest-depth slot a chance to release
     * that slot before the source lane accepts another resident value.
     * Ordinary mapper implementations have no retained handoff and keep the
     * no-op default.
     */
    fun beforeDepthObservationOffer(laneHasOutstandingWork: Boolean) = Unit

    /** Mapper-owned depth payload retained outside the source lane. */
    fun retainedDepthPayloadBytes(): Long = 0L

    /** Invalidates admitted-but-not-committed work before a lifecycle pause. */
    fun pause() = Unit

    /** Reopens admission only after the runtime has revalidated its ownership cut. */
    fun resume() = Unit

    /** Fences retained observations before a replacement ownership cut admits. */
    fun rollover(ownership: VisibilityObservationOwnership) = Unit

    fun snapshot(): VisibilityMappingAdmissionHealth = VisibilityMappingAdmissionHealth.empty()

    /** Debug-only scalar timing for the latest canonical depth transaction. */
    fun depthAdmissionTiming(): VisibilityDepthAdmissionTiming =
        VisibilityDepthAdmissionTiming.empty()

    /** Debug-only scalar timing for the latest canonical feature transaction. */
    fun featureAdmissionTiming(): VisibilityFeatureAdmissionTiming =
        VisibilityFeatureAdmissionTiming.empty()

    override fun close() = Unit
}

/**
 * Bounded timing evidence for one depth admission.
 *
 * This deliberately contains only scalar counters and elapsed microseconds;
 * no samples, rows, receipts, or image-backed objects cross the debug seam.
 * A pending record exposes the publication-to-ACK wait observed so far.
 */
internal data class VisibilityDepthAdmissionTiming(
    val completedCount: Long,
    val sequence: Long,
    val lookupMicros: Long,
    val mutationMicros: Long,
    val publicationAckMicros: Long,
    val endToEndMicros: Long,
    val pendingPublicationAck: Boolean,
) {
    init {
        require(completedCount in 0..MAX_COMPLETED_COUNT)
        require(sequence >= 0L)
        require(lookupMicros in 0..MAX_ELAPSED_MICROS)
        require(mutationMicros in 0..MAX_ELAPSED_MICROS)
        require(publicationAckMicros in 0..MAX_ELAPSED_MICROS)
        require(endToEndMicros in 0..MAX_ELAPSED_MICROS)
    }

    fun toWireMap(): Map<String, Any> = mapOf(
        "completedCount" to completedCount,
        "sequence" to sequence,
        "lookupMicros" to lookupMicros,
        "mutationMicros" to mutationMicros,
        "publicationAckMicros" to publicationAckMicros,
        "endToEndMicros" to endToEndMicros,
        "pendingPublicationAck" to pendingPublicationAck,
    )

    companion object {
        const val MAX_COMPLETED_COUNT = 1_000_000L
        const val MAX_ELAPSED_MICROS = 600_000_000L

        fun empty() = VisibilityDepthAdmissionTiming(
            completedCount = 0L,
            sequence = 0L,
            lookupMicros = 0L,
            mutationMicros = 0L,
            publicationAckMicros = 0L,
            endToEndMicros = 0L,
            pendingPublicationAck = false,
        )
    }
}

/**
 * Bounded timing evidence for one feature admission.
 *
 * Only scalar stage durations cross the debug seam. Publication ends after the
 * native queue and renderer have accepted the pending current, before the
 * separate exact ACK exchange completes.
 */
internal data class VisibilityFeatureAdmissionTiming(
    val completedCount: Long,
    val sequence: Long,
    val planningMicros: Long,
    val mutationMicros: Long,
    val serializationMicros: Long,
    val publicationMicros: Long,
    val endToEndMicros: Long,
    val pendingPublicationAck: Boolean,
) {
    init {
        require(completedCount in 0..MAX_COMPLETED_COUNT)
        require(sequence >= 0L)
        require(planningMicros in 0..MAX_ELAPSED_MICROS)
        require(mutationMicros in 0..MAX_ELAPSED_MICROS)
        require(serializationMicros in 0..MAX_ELAPSED_MICROS)
        require(publicationMicros in 0..MAX_ELAPSED_MICROS)
        require(endToEndMicros in 0..MAX_ELAPSED_MICROS)
    }

    fun toWireMap(): Map<String, Any> = mapOf(
        "completedCount" to completedCount,
        "sequence" to sequence,
        "planningMicros" to planningMicros,
        "mutationMicros" to mutationMicros,
        "serializationMicros" to serializationMicros,
        "publicationMicros" to publicationMicros,
        "endToEndMicros" to endToEndMicros,
        "pendingPublicationAck" to pendingPublicationAck,
    )

    companion object {
        const val MAX_COMPLETED_COUNT = 1_000_000L
        const val MAX_ELAPSED_MICROS = 600_000_000L

        fun empty() = VisibilityFeatureAdmissionTiming(
            completedCount = 0L,
            sequence = 0L,
            planningMicros = 0L,
            mutationMicros = 0L,
            serializationMicros = 0L,
            publicationMicros = 0L,
            endToEndMicros = 0L,
            pendingPublicationAck = false,
        )
    }
}

/**
 * Proof that reduced-rate map intake cannot interfere with capture.
 *
 * capture ingress cannot establish capture ownership (#61), so production uses the
 * conservative false implementation until that owner supplies this predicate.
 */
internal fun interface VisibilityCaptureSafePredicate {
    fun isCaptureSafe(): Boolean

    companion object {
        val CONSERVATIVE = VisibilityCaptureSafePredicate { false }
    }
}
