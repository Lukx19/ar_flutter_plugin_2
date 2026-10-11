package com.uhg0.ar_flutter_plugin_2.visibilitygrid

import android.util.Log
import com.uhg0.ar_flutter_plugin_2.BuildConfig
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

/**
 * Capture-independent V2 sensor admission boundary.
 *
 * Producer-backed objects must already be copied and closed before [offerFeature]
 * or [offerDepth] is called. Each source owns one current and one replaceable
 * latest value; the lanes never retain history or run a catch-up burst.
 */
internal class AndroidVisibilityGridRuntime(
    private val ownership: () -> VisibilityObservationOwnership?,
    private val mapper: VisibilityObservationMapper,
    private val scheduler: ScheduledExecutorService = Executors.newScheduledThreadPool(2),
    private val nanoTime: () -> Long = System::nanoTime,
    private val featureIntervalNs: Long = 125_000_000L,
    private val depthIntervalNs: Long = 250_000_000L,
    private val ownsScheduler: Boolean = true,
    callbackCopySampleCapacity: Int = 256,
    private val callbackCopyBudgetNs: Long = 2_000_000L,
    private val callbackCopyRecoveryNs: Long = 30_000_000_000L,
    private val captureSafe: VisibilityCaptureSafePredicate =
        VisibilityCaptureSafePredicate.CONSERVATIVE,
    private val beforeLaneEnqueue: (VisibilityObservationSource) -> Unit = {},
    private val beforeLaneDelivery: (VisibilityObservationSource) -> Unit = {},
    private val afterLaneDelivery: (VisibilityObservationSource) -> Unit = {},
    private val afterMapperAdmission: () -> Unit = {},
    private val onMapperFailure: (RuntimeException) -> Unit = {},
    private val beforeDepthCapabilityFence: () -> Unit = {},
    private val afterDepthCapabilityInvalidated: () -> Unit = {},
    private val afterTerminalIngressInvalidated: () -> Unit = {},
    private val beforeLaneResidentPublication: (VisibilityObservationSource) -> Unit = {},
    private val afterLaneResidentPublication: (VisibilityObservationSource) -> Unit = {},
    private val beforeLaneDiscardResidentPublication: (VisibilityObservationSource) -> Unit = {},
    private val debugDepthOfferNanoTime: () -> Long = System::nanoTime,
) : AutoCloseable, DepthCopyTransientObserver {
    private val lock = Any()
    private var requestedDepthCapability = VisibilityDepthCapability.UNSUPPORTED
    private var requestedCapabilityOwnership: VisibilityObservationOwnership? = null
    private var ingressDrainGeneration = 0L
    private var ingressDrainScheduled = false
    private var pendingFeatureDrain = false
    private var pendingDepthDrain = false
    private var pendingCapabilityFence = false
    private var pendingTerminalFence = false
    private var lifecycleAdmissionBusyDrops = 0L
    private var ingressDrainFailures = 0L
    private var ingressDrainFailed = false
    private val lifecycleLock = ReentrantReadWriteLock()
    @Volatile private var closed = false
    private var closeCompleted = false
    @Volatile private var paused = false
    private var pauseDrainPending = false
    private var lifecycleGeneration = 0L
    private val pauseDrainGate = java.util.concurrent.locks.ReentrantLock()
    private var pausedOwnership: VisibilityObservationOwnership? = null
    private var featureLastCopiedTimestampNs = Long.MIN_VALUE
    private var depthLastCopiedTimestampNs = Long.MIN_VALUE
    private var featureLastCopyAttemptTimestampNs = Long.MIN_VALUE
    private var depthLastCopyAttemptTimestampNs = Long.MIN_VALUE
    @Volatile private var depthAcquisitionFunding: DepthAcquisitionFunding? = null
    private var depthAcquisitionPreparationPending = false
    private var depthEstimateBindingGeneration = -1L
    private var depthEstimateSessionGeneration = -1L
    private var depthEstimateGroupGeneration = -1L
    private var depthEstimateLifecycleSequence = -1L
    private var depthEstimateOperationGeneration = -1L
    private var freshEstimateAdmissions = 0L
    private var staleAutomaticRefusals = 0L
    private var rawReprojectedAcquisitions = 0L
    private var duplicateEstimateRefusals = 0L
    private var depthConfidencePairRefusals = 0L
    private var invalidImageTimestampRefusals = 0L
    private val invalidDepthTuple = LongArray(7)
    private val staleDepthTuple = LongArray(7)
    private var invalidDepthReason: String? = null
    private var staleDepthReason: String? = null
    private var latestDepthImageTimestampNs = 0L
    private var latestDepthFrameTimestampNs = 0L
    private var latestDepthCameraImageTimestampNs = 0L
    private var latestDepthConfidenceTimestampNs = 0L
    private var latestDepthSourceImageWidth = 0
    private var latestDepthSourceImageHeight = 0
    private var latestDepthImageWidth = 0
    private var latestDepthImageHeight = 0
    private var latestDepthAcquisitionMode = DepthAcquisitionMode.PROVIDED
    private var featureHealth = VisibilitySourceHealth.CONFIGURED
    private var depthHealth = VisibilitySourceHealth.UNSUPPORTED
    /**
     * Monotonic time at which a usable depth observation was last copied.
     * Motion based depth can return NotYetAvailable while the camera is
     * stationary, so retain the healthy state for a bounded freshness window.
     */
    private var depthLastHealthyNs = Long.MIN_VALUE
    private var depthCapability = VisibilityDepthCapability.UNSUPPORTED
    private var syntheticSource = false
    @Volatile private var depthOfferTiming: DepthOfferTimingLedger? = null
    private var offeredFeatureObservations = 0L
    private var offeredDepthObservations = 0L
    private var droppedFeatureObservations = 0L
    private var droppedDepthObservations = 0L
    private var maximumFeatureSamples = 0
    private var maximumDepthSamples = 0
    private var copiedFeatureObservations = 0L
    private var copiedDepthObservations = 0L
    private var admittedFeatureObservations = 0L
    private var admittedDepthObservations = 0L
    private var accountedMapperFeatures = 0L
    private var accountedMapperDepths = 0L
    private var invalidFeatureObservations = 0L
    private var invalidDepthObservations = 0L
    private var duplicateFeatureObservations = 0L
    private var duplicateDepthObservations = 0L
    private var staleGenerationObservations = 0L
    private var replacedFeatureObservations = 0L
    private var replacedDepthObservations = 0L
    private var featureTransientUnavailable = 0L
    private var depthTransientUnavailable = 0L
    private var featureFailures = 0L
    private var depthFailures = 0L
    private var depthPreparationReady = 0L
    private var depthPreparationTransient = 0L
    private var depthPreparationRejected = 0L
    private var depthProcessorBusyDrops = 0L
    private var depthProcessingTransient = 0L
    private var depthProcessingRejected = 0L
    private var mappingRuntimeFailureCount = 0L
    private var lastMappingFailureClass: String? = null
    private var acquiredProducerResources = 0L
    private var closedProducerResources = 0L
    private var residentPayloadBytes = 0L
    private var featureResidentPayloadBytes = 0L
    private var depthResidentPayloadBytes = 0L
    private var peakResidentPayloadBytes = 0L
    private var callbackCopySamples = BoundedLatencySamples(callbackCopySampleCapacity)
    private var callbackCopyBudgetDegraded = false
    private var callbackCopyBudgetBreaches = 0L
    private var callbackCopyBudgetRecoveries = 0L
    private var callbackCopyDepthSheds = 0L
    private var callbackCopyFeatureSheds = 0L
    private var lastCallbackCopyBudgetBreachNs = Long.MIN_VALUE
    private var lastCallbackRecoveryProbeNs = Long.MIN_VALUE
    private var callbackRecoveryProbeInFlight = false
    private var pausedObservationRejections = 0L
    private var lifecycleDiscardedObservations = 0L
    private var pauseCount = 0L
    private var resumeCount = 0L
    private var sameCutResumeCount = 0L
    private var ownershipRolloverCount = 0L
    private var rolloverDiscardedIngressObservations = 0L
    private var admittedBindingGeneration = 0L
    private var admittedSessionGeneration = 0L
    private var admittedGroupGeneration = 0L
    private var admittedLifecycleSequence = 0L
    private var admittedOperationGeneration = 0L

    private val featureLane = LatestObservationLane(
        intervalNs = featureIntervalNs,
        scheduler = scheduler,
        nanoTime = nanoTime,
        payloadBytes = VisibilityFeatureObservation::payloadBytes,
        deliver = { beforeLaneDelivery(it.frame.source); deliverFeature(it) },
        afterDelivery = { afterLaneDelivery(it.frame.source) },
        beforeResidentPublication = { beforeLaneResidentPublication(it.frame.source) },
        afterResidentPublication = { afterLaneResidentPublication(it.frame.source) },
        beforeDiscardResidentPublication = {
            beforeLaneDiscardResidentPublication(VisibilityObservationSource.SYNTHETIC_FEATURE)
        },
        onReplacement = { synchronized(lock) { replacedFeatureObservations++ } },
        onStale = { synchronized(lock) {
            staleGenerationObservations++
            droppedFeatureObservations++
        } },
        onMapperFailure = { observation, error ->
            recordMapperRuntimeFailure(observation.ownership, error)
        },
        onDiscard = { it.close() },
        onResidentBytesChanged = { bytes -> updateResidentBytes(featureBytes = bytes) },
    )
    private val depthLane = LatestObservationLane(
        intervalNs = depthIntervalNs,
        scheduler = scheduler,
        nanoTime = nanoTime,
        payloadBytes = VisibilityDepthObservation::payloadBytes,
        deliver = { beforeLaneDelivery(it.frame.source); deliverDepth(it) },
        afterDelivery = { afterLaneDelivery(it.frame.source) },
        beforeResidentPublication = { beforeLaneResidentPublication(it.frame.source) },
        afterResidentPublication = { afterLaneResidentPublication(it.frame.source) },
        beforeDiscardResidentPublication = {
            beforeLaneDiscardResidentPublication(VisibilityObservationSource.SYNTHETIC_DEPTH)
        },
        onReplacement = {
            it.finishDebugOffer(DepthOfferTimingLedger.REPLACED)
            synchronized(lock) { replacedDepthObservations++ }
        },
        onStale = { synchronized(lock) {
            staleGenerationObservations++
            droppedDepthObservations++
        } },
        onMapperFailure = { observation, error ->
            recordMapperRuntimeFailure(observation.ownership, error)
        },
        onDiscard = { it.close() },
        onResidentBytesChanged = { bytes -> updateResidentBytes(depthBytes = bytes) },
    )

    init {
        require(featureIntervalNs > 0 && depthIntervalNs > 0)
        require(callbackCopySampleCapacity > 0)
        require(callbackCopyBudgetNs == CALLBACK_COPY_BUDGET_NS)
        require(callbackCopyRecoveryNs >= 0)
        val initialMapperHealth = mapper.snapshot()
        accountedMapperFeatures = initialMapperHealth.admittedFeatures
        accountedMapperDepths = initialMapperHealth.admittedDepths
    }

    fun setDepthCapability(capability: VisibilityDepthCapability) {
        val needsDrain = synchronized(lock) {
            if (closed || ingressDrainFailed) return
            val requestOwnership = ownership()
            val ownerChanged = requestOwnership != requestedCapabilityOwnership
            val changed = capability != requestedDepthCapability || ownerChanged
            requestedDepthCapability = capability
            requestedCapabilityOwnership = requestOwnership
            if (changed) ingressDrainGeneration++
            if (ownerChanged) depthAcquisitionFunding?.debugTransientMask = 0
            if (capability == VisibilityDepthCapability.UNSUPPORTED) {
                val wasSupported = depthCapability != VisibilityDepthCapability.UNSUPPORTED
                depthCapability = capability
                depthLastHealthyNs = Long.MIN_VALUE
                if (depthHealth != VisibilitySourceHealth.FAILED) depthHealth = VisibilitySourceHealth.UNSUPPORTED
                if (wasSupported) {
                    pendingDepthDrain = true
                    pendingCapabilityFence = true
                }
                wasSupported
            } else if (!ingressDrainScheduled && !pendingDepthDrain) {
                depthCapability = capability
                if (depthHealth == VisibilitySourceHealth.UNSUPPORTED) depthHealth = VisibilitySourceHealth.CONFIGURED
                false
            } else false
        }
        if (needsDrain) scheduleIngressDrain()
    }

    fun configureSyntheticSource(capability: VisibilityDepthCapability) {
        synchronized(lock) {
            syntheticSource = true
            if (depthOfferTiming == null) depthOfferTiming = DepthOfferTimingLedger(debugDepthOfferNanoTime)
        }
        setDepthCapability(capability)
    }

    /**
     * Waits until both copied-observation lanes have delivered all resident work.
     *
     * This is an internal debug-fixture boundary, not a production frame-path
     * primitive. It lets a deterministic synthetic scene preserve two camera
     * viewpoints without replacing the first one in the latest-only lanes.
     */
    fun awaitDebugFixtureIdle() {
        check(isSyntheticSource()) { "debug fixture idle requires the synthetic source" }
        lifecycleLock.read {
            featureLane.awaitIdle()
            depthLane.awaitIdle()
        }
    }

    fun isSyntheticSource(): Boolean = synchronized(lock) { syntheticSource }

    fun featureSampleCapacity(): Int = synchronized(lock) {
        when {
            !callbackCopyBudgetDegraded -> V2_FEATURE_SAMPLE_CAPACITY
            callbackRecoveryProbeInFlight -> V2_FEATURE_SAMPLE_CAPACITY
            isCaptureSafe() -> 1_000
            else -> 0
        }
    }

    fun shouldCopyFeature(timestampNs: Long): Boolean = synchronized(lock) {
        if (closed || paused || ingressDrainFailed || syntheticSource) return@synchronized false
        if (featureHealth == VisibilitySourceHealth.FAILED) return@synchronized false
        val unsafeRecoveryProbe = callbackCopyBudgetDegraded && !isCaptureSafe()
        val now = if (unsafeRecoveryProbe) nanoTime() else Long.MIN_VALUE
        if (unsafeRecoveryProbe && !callbackRecoveryProbeReady(now)) return@synchronized false
        claimCopy(
            timestampNs,
            featureLastCopyAttemptTimestampNs,
            if (callbackCopyBudgetDegraded) {
                maxOf(featureIntervalNs, SEVERE_FEATURE_INTERVAL_NS)
            } else featureIntervalNs,
        ).also {
            if (it) {
                featureLastCopyAttemptTimestampNs = timestampNs
                if (unsafeRecoveryProbe) {
                    callbackCopySamples.clear()
                    lastCallbackRecoveryProbeNs = now
                    callbackRecoveryProbeInFlight = true
                }
            }
        }
    }

    fun shouldCopyDepth(timestampNs: Long): Boolean {
        // The mapper owns a single depth publication lane. Copying another
        // full image while it is still mapping or awaiting delivery only
        // creates replaceable work and competes with the camera callback.
        // Check the lane outside the runtime lock: replacement callbacks take
        // the lane lock before updating runtime counters.
        if (depthLane.hasOutstandingWork()) return false
        return synchronized(lock) {
            !closed && !paused && !ingressDrainFailed && !syntheticSource && !callbackCopyBudgetDegraded &&
                depthCapability != VisibilityDepthCapability.UNSUPPORTED &&
                depthHealth != VisibilitySourceHealth.FAILED &&
                claimCopy(timestampNs, depthLastCopyAttemptTimestampNs, depthIntervalNs).also {
                    if (it) depthLastCopyAttemptTimestampNs = timestampNs
                }
        }
    }

    fun offerFeature(
        observation: VisibilityFeatureObservation,
        callbackCopyNs: Long = 0,
    ): Boolean = tryOfferLifecycle(observation) {
        synchronized(lock) {
            offeredFeatureObservations++
            maximumFeatureSamples = maxOf(maximumFeatureSamples, observation.sampleCount)
            if (syntheticSource && observation.frame.source == VisibilityObservationSource.ARCORE_FEATURE) {
                invalidFeatureObservations++
                droppedFeatureObservations++
                observation.close()
                return@tryOfferLifecycle false
            }
            if (paused) {
                pausedObservationRejections++
                droppedFeatureObservations++
                observation.close()
                return@tryOfferLifecycle false
            }
            if (featureHealth == VisibilitySourceHealth.FAILED) {
                droppedFeatureObservations++
                observation.close()
                return@tryOfferLifecycle false
            }
            if (closed || !isStructurallyValid(observation) ||
                observation.sampleCount > featureSampleCapacity() ||
                (callbackCopyBudgetDegraded && !isCaptureSafe() && !callbackRecoveryProbeInFlight)
            ) {
                invalidFeatureObservations++
                droppedFeatureObservations++
                observation.close()
                return@tryOfferLifecycle false
            }
            if (observation.frame.sourceTimestampNs <= featureLastCopiedTimestampNs) {
                duplicateFeatureObservations++
                droppedFeatureObservations++
                observation.close()
                return@tryOfferLifecycle false
            }
            if (ownership() != observation.ownership) {
                staleGenerationObservations++
                droppedFeatureObservations++
                observation.close()
                return@tryOfferLifecycle false
            }
            featureLastCopiedTimestampNs = observation.frame.sourceTimestampNs
            featureHealth = VisibilitySourceHealth.HEALTHY
            featureFailures = 0
            recordOwnership(observation.ownership)
            copiedFeatureObservations++
            recordCallbackCopy(callbackCopyNs)
        }
        try {
            beforeLaneEnqueue(observation.frame.source)
            featureLane.offer(observation)
        } catch (error: RuntimeException) {
            observation.close()
            throw error
        }
        true
    }

    fun offerDepth(
        observation: VisibilityDepthObservation,
        callbackCopyNs: Long = 0,
    ): Boolean {
        depthOfferTiming?.let { ledger ->
            observation.debugOfferLedger = ledger
            observation.debugOfferId = ledger.start(observation.frame.sourceTimestampNs)
        }
        return tryOfferLifecycle(observation) {
            synchronized(lock) {
                offeredDepthObservations++
                maximumDepthSamples = maxOf(maximumDepthSamples, observation.sampleCount)
                if (syntheticSource && observation.frame.source == VisibilityObservationSource.ARCORE_RAW_DEPTH) {
                    invalidDepthObservations++
                    droppedDepthObservations++
                    observation.close()
                    return@tryOfferLifecycle false
                }
                if (paused) {
                    pausedObservationRejections++
                    droppedDepthObservations++
                    observation.close()
                    return@tryOfferLifecycle false
                }
                if (depthHealth == VisibilitySourceHealth.FAILED) {
                    droppedDepthObservations++
                    observation.close()
                    return@tryOfferLifecycle false
                }
                if (closed || callbackCopyBudgetDegraded ||
                    depthCapability == VisibilityDepthCapability.UNSUPPORTED ||
                    !isStructurallyValid(observation)
                ) {
                    invalidDepthObservations++
                    droppedDepthObservations++
                    observation.close()
                    return@tryOfferLifecycle false
                }
                if (ownership() != observation.ownership) {
                    staleGenerationObservations++
                    droppedDepthObservations++
                    observation.close()
                    return@tryOfferLifecycle false
                }
                if (observation.acquisitionMode != DepthAcquisitionMode.PROVIDED) {
                    recordDepthAcquisitionEvidence(observation.frame.sourceTimestampNs, observation.frame.frameTimestampNs,
                        observation.confidenceTimestampNs, observation.cameraImageTimestampNs, observation.sourceImageWidth, observation.sourceImageHeight,
                        observation.frame.intrinsics.imageWidth, observation.frame.intrinsics.imageHeight, observation.acquisitionMode)
                    if (depthAcquisitionFunding?.let { it.funded && it.matches(observation.ownership) } != true) {
                        droppedDepthObservations++
                        observation.close()
                        return@tryOfferLifecycle false
                    }
                    depthImageIdentityRefusal(observation.frame.sourceTimestampNs,
                        observation.cameraImageTimestampNs, observation.frame.frameTimestampNs,
                        observation.acquisitionMode, observation.rawProjectionQualified,
                        observation.projectionProvenance,
                        denseCurrentFrameExperimentForPreference(observation.ownership.groupFrame.depthSourcePreference))?.let { refusal ->
                        when (refusal) {
                            DepthAcquisitionRefusal.INVALID_TIMESTAMP -> invalidImageTimestampRefusals++
                            DepthAcquisitionRefusal.INVALID_PROJECTION -> invalidDepthObservations++
                            DepthAcquisitionRefusal.STALE_AUTOMATIC -> staleAutomaticRefusals++
                            DepthAcquisitionRefusal.CONFIDENCE_PAIR -> depthConfidencePairRefusals++
                        }
                        droppedDepthObservations++
                        observation.close()
                        return@tryOfferLifecycle false
                    }
                }
                qualifyDepthEstimate(observation.ownership)
                if (observation.acquisitionMode == DepthAcquisitionMode.RAW &&
                    depthLastCopiedTimestampNs > 0L && observation.frame.sourceTimestampNs == depthLastCopiedTimestampNs) {
                    rawReprojectedAcquisitions++
                }
                if (observation.frame.sourceTimestampNs <= depthLastCopiedTimestampNs) {
                    duplicateDepthObservations++
                    if (observation.acquisitionMode != DepthAcquisitionMode.PROVIDED) duplicateEstimateRefusals++
                    droppedDepthObservations++
                    observation.close()
                    return@tryOfferLifecycle false
                }
                depthLastCopiedTimestampNs = observation.frame.sourceTimestampNs
                depthHealth = VisibilitySourceHealth.HEALTHY
                depthLastHealthyNs = nanoTime()
                depthFailures = 0
                recordOwnership(observation.ownership)
                copiedDepthObservations++
                if (observation.acquisitionMode != DepthAcquisitionMode.PROVIDED) freshEstimateAdmissions++
                recordCallbackCopy(callbackCopyNs)
            }
            try {
                beforeLaneEnqueue(observation.frame.source)
                depthLane.offer(observation) { laneHasOutstandingWork ->
                    mapper.beforeDepthObservationOffer(laneHasOutstandingWork)
                }
            } catch (error: RuntimeException) {
                observation.close()
                throw error
            }
            true
        }
    }

    fun recordFeatureTransientUnavailable() = synchronized(lock) {
        callbackRecoveryProbeInFlight = false
        featureTransientUnavailable++
        if (featureHealth != VisibilitySourceHealth.FAILED) {
            featureHealth = VisibilitySourceHealth.TRANSIENT_UNAVAILABLE
        }
    }

    /** Cold reservation runs on the mapper executor; frames read only the immutable result. */
    fun prepareDepthAcquisition(expected: VisibilityObservationOwnership): Boolean {
        depthAcquisitionFunding?.takeIf { it.matches(expected) }?.let {
            return it.funded && !closed && !paused && ownership() == expected
        }
        val generation = synchronized(lock) {
            if (closed || paused || ownership() != expected || depthAcquisitionPreparationPending) return false
            depthAcquisitionPreparationPending = true
            lifecycleGeneration
        }
        val qualifier = DepthAcquisitionFunding.from(expected, false)
        try {
            mapper.prepareDepthAcquisition(qualifier) { result ->
                synchronized(lock) {
                    depthAcquisitionPreparationPending = false
                    if (!closed && !paused && generation == lifecycleGeneration && ownership()?.let { qualifier.matches(it) } == true) {
                        // Only final profile decisions are cached. One pending cold request
                        // still bounds retries when opening or renderer recovery is incomplete.
                        depthAcquisitionFunding = if (result == DepthAcquisitionPreparationResult.RETRYABLE) null
                            else qualifier.copy(funded = result == DepthAcquisitionPreparationResult.FUNDED)
                    }
                }
            }
        } catch (_: RuntimeException) {
            synchronized(lock) { depthAcquisitionPreparationPending = false }
        }
        return false
    }

    fun recordDepthAcquisitionRefusal(reason: DepthAcquisitionRefusal, metadata: RawDepthFrameMetadata) = synchronized(lock) {
        when (reason) {
            DepthAcquisitionRefusal.INVALID_TIMESTAMP, DepthAcquisitionRefusal.INVALID_PROJECTION -> {
                recordDepthRefusalTuple(invalidDepthTuple, metadata)
                invalidDepthReason = depthRefusalBound(reason, metadata)
            }
            DepthAcquisitionRefusal.STALE_AUTOMATIC -> {
                recordDepthRefusalTuple(staleDepthTuple, metadata)
                staleDepthReason = depthRefusalBound(reason, metadata)
            }
            DepthAcquisitionRefusal.CONFIDENCE_PAIR -> Unit
        }
        recordDepthAcquisitionEvidence(metadata.timestampNs, metadata.arFrameTimestampNs,
            metadata.confidenceTimestampNs, metadata.cameraImageTimestampNs, metadata.sourceImageWidth, metadata.sourceImageHeight,
            metadata.width, metadata.height, metadata.acquisitionMode)
        when (reason) {
            DepthAcquisitionRefusal.STALE_AUTOMATIC -> staleAutomaticRefusals++
            DepthAcquisitionRefusal.CONFIDENCE_PAIR -> depthConfidencePairRefusals++
            DepthAcquisitionRefusal.INVALID_TIMESTAMP -> invalidImageTimestampRefusals++
            DepthAcquisitionRefusal.INVALID_PROJECTION -> invalidDepthObservations++
        }
    }

    private fun recordDepthRefusalTuple(target: LongArray, metadata: RawDepthFrameMetadata) {
        target[0] = metadata.timestampNs
        target[1] = metadata.acquisitionFrameBeforeNs
        target[2] = metadata.acquisitionFrameAfterNs
        target[3] = metadata.acquisitionAndroidCameraBeforeNs
        target[4] = metadata.acquisitionAndroidCameraAfterNs
        target[5] = metadata.acquisitionFrameSequence
        target[6] = metadata.groupGeneration
    }

    private fun depthRefusalScalarFields(prefix: String, reason: String?, tuple: LongArray): Map<String, Any?> = mapOf(
        "${prefix}Reason" to reason,
        "${prefix}ImageTimestampNs" to if (reason == null) null else tuple[0].toString(),
        "${prefix}FrameBeforeNs" to if (reason == null) null else tuple[1].toString(),
        "${prefix}FrameAfterNs" to if (reason == null) null else tuple[2].toString(),
        "${prefix}AndroidCameraBeforeNs" to if (reason == null) null else tuple[3].toString(),
        "${prefix}AndroidCameraAfterNs" to if (reason == null) null else tuple[4].toString(),
        "${prefix}FrameSequence" to if (reason == null) null else tuple[5],
        "${prefix}GroupGeneration" to if (reason == null) null else tuple[6],
    )

    private fun recordDepthAcquisitionEvidence(imageTime: Long, frameTime: Long, confidenceTime: Long, cameraImageTime: Long,
        sourceWidth: Int, sourceHeight: Int, depthWidth: Int, depthHeight: Int, mode: DepthAcquisitionMode) {
        latestDepthImageTimestampNs = imageTime
        latestDepthFrameTimestampNs = frameTime
        latestDepthCameraImageTimestampNs = cameraImageTime
        latestDepthConfidenceTimestampNs = confidenceTime
        latestDepthSourceImageWidth = sourceWidth
        latestDepthSourceImageHeight = sourceHeight
        latestDepthImageWidth = depthWidth
        latestDepthImageHeight = depthHeight
        latestDepthAcquisitionMode = mode
    }

    private fun qualifyDepthEstimate(owner: VisibilityObservationOwnership) {
        if (depthEstimateBindingGeneration != owner.bindingGeneration ||
            depthEstimateSessionGeneration != owner.sessionGeneration ||
            depthEstimateGroupGeneration != owner.groupGeneration ||
            depthEstimateLifecycleSequence != owner.lifecycleSequence ||
            depthEstimateOperationGeneration != owner.operationGeneration) {
            depthLastCopiedTimestampNs = Long.MIN_VALUE
            depthEstimateBindingGeneration = owner.bindingGeneration
            depthEstimateSessionGeneration = owner.sessionGeneration
            depthEstimateGroupGeneration = owner.groupGeneration
            depthEstimateLifecycleSequence = owner.lifecycleSequence
            depthEstimateOperationGeneration = owner.operationGeneration
        }
    }

    override fun onPackedDepthTransient(
        owner: VisibilityObservationOwnership,
        metadata: RawDepthFrameMetadata,
        reason: PackedDepthTransientReason,
        examinedDepthPositive: Int,
        examinedPairedPositive: Int,
        selectedCount: Int,
        sourceRejected: Int,
    ) {
        claimDepthCopyTransientDiagnostic(owner, metadata, reason, examinedDepthPositive,
            examinedPairedPositive, selectedCount, sourceRejected)?.let {
            Log.d("Capture3DDepthCopy", it)
        }
    }

    /** Full existing owner references fence this cold event; no obsolete pose is retained. */
    internal fun claimDepthCopyTransientDiagnostic(
        owner: VisibilityObservationOwnership,
        metadata: RawDepthFrameMetadata,
        reason: PackedDepthTransientReason,
        examinedDepthPositive: Int,
        examinedPairedPositive: Int,
        selectedCount: Int,
        sourceRejected: Int,
        debug: Boolean = BuildConfig.DEBUG,
    ): String? = synchronized(lock) {
        if (!debug || closed || paused || ingressDrainFailed ||
            metadata.acquisitionMode == DepthAcquisitionMode.PROVIDED ||
            ownership() != owner || requestedCapabilityOwnership != owner) return null
        val funding = depthAcquisitionFunding?.takeIf { it.funded && it.matches(owner) } ?: return null
        val bit = 1 shl (metadata.acquisitionMode.ordinal * 3 + reason.ordinal)
        if (funding.debugTransientMask and bit != 0) return null
        require(metadata.width in 1..16_384 && metadata.height in 1..16_384)
        val maximumProbes = Math.multiplyExact(metadata.width, metadata.height)
        require(examinedDepthPositive in 0..maximumProbes)
        require(examinedPairedPositive in 0..examinedDepthPositive)
        require(selectedCount in 0..4_096 && sourceRejected >= 0)
        funding.debugTransientMask = funding.debugTransientMask or bit
        "depthCopyTransient source=${metadata.acquisitionMode.name} reason=${reason.name} " +
            "width=${metadata.width} height=${metadata.height} " +
            "examinedDepthPositive=$examinedDepthPositive examinedPairedPositive=$examinedPairedPositive " +
            "selected=$selectedCount sourceRejected=$sourceRejected"
    }

    fun recordDepthPreparationReady() = synchronized(lock) {
        depthPreparationReady++
    }

    fun recordDepthPreparationTransient() = synchronized(lock) {
        depthPreparationTransient++
    }

    fun recordDepthPreparationRejected() = synchronized(lock) {
        depthPreparationRejected++
    }

    fun recordDepthProcessorBusyDrop() = synchronized(lock) {
        depthProcessorBusyDrops++
    }

    fun recordDepthProcessingTransient() = synchronized(lock) {
        depthProcessingTransient++
    }

    fun recordDepthProcessingRejected() = synchronized(lock) {
        depthProcessingRejected++
    }

    fun recordDepthTransientUnavailable() = synchronized(lock) {
        if (depthCapability != VisibilityDepthCapability.UNSUPPORTED) {
            depthTransientUnavailable++
            if (depthHealth != VisibilitySourceHealth.FAILED &&
                !hasFreshDepthObservation(nanoTime())
            ) {
                depthHealth = VisibilitySourceHealth.TRANSIENT_UNAVAILABLE
            }
        }
    }

    fun recordFeatureFailure() {
        synchronized(lock) {
            callbackRecoveryProbeInFlight = false
            featureFailures++
            featureHealth = VisibilitySourceHealth.FAILED
            pendingFeatureDrain = true
            pendingTerminalFence = true
            ingressDrainGeneration++
        }
        scheduleIngressDrain()
    }

    fun recordDepthFailure() {
        val terminal = synchronized(lock) {
            if (depthCapability == VisibilityDepthCapability.UNSUPPORTED) false else {
                depthFailures++
                if (depthFailures >= TERMINAL_FAILURE_THRESHOLD) {
                    depthHealth = VisibilitySourceHealth.FAILED
                    pendingDepthDrain = true
                    pendingTerminalFence = true
                    ingressDrainGeneration++
                    true
                } else { depthHealth = VisibilitySourceHealth.TRANSIENT_UNAVAILABLE; false }
            }
        }
        if (terminal) scheduleIngressDrain()
    }

    fun recordFeatureStalled() = synchronized(lock) {
        if (featureHealth != VisibilitySourceHealth.FAILED) {
            featureHealth = VisibilitySourceHealth.STALLED
        }
    }

    fun recordDepthStalled() = synchronized(lock) {
        if (depthCapability != VisibilityDepthCapability.UNSUPPORTED &&
            depthHealth != VisibilitySourceHealth.FAILED
        ) {
            depthHealth = VisibilitySourceHealth.STALLED
        }
    }

    fun recordProducerResourceAcquired() = synchronized(lock) {
        acquiredProducerResources++
    }

    fun recordProducerResourceClosed() = synchronized(lock) {
        closedProducerResources++
    }

    /** Rejects new callbacks and discards copied-but-uncommitted lane values. */
    fun lifecycleToken(): Long = synchronized(lock) { lifecycleGeneration }
    fun isCurrentLifecycle(expected: Long): Boolean = synchronized(lock) { !closed && lifecycleGeneration == expected }
    fun fencePause(): Long = synchronized(lock) {
        if (!closed) {
            lifecycleGeneration++
            if (!paused) { pausedOwnership = ownership(); pauseCount++ }
            paused = true
            pauseDrainPending = true
            ingressDrainGeneration++
        }
        lifecycleGeneration
    }
    /** Worker-only: serialize mapper/barrier work without holding the admission-fence monitor. */
    fun finishPause(expectedLifecycle: Long = lifecycleToken()) {
        pauseDrainGate.lock()
        try {
            if (!synchronized(lock) { !closed && lifecycleGeneration == expectedLifecycle && pauseDrainPending }) return
            depthOfferTiming?.finishActive(DepthOfferTimingLedger.LIFECYCLE)
            // Mapper invalidation happens before lane drain so a callback which
            // already crossed deliverFeature cannot commit behind the pause cut.
            mapper.pause()
            val featureDiscarded = featureLane.pauseAndDiscard()
            val depthDiscarded = depthLane.pauseAndDiscard()
            synchronized(lock) {
                lifecycleDiscardedObservations += featureDiscarded + depthDiscarded
                droppedFeatureObservations += featureDiscarded
                droppedDepthObservations += depthDiscarded
            }
            check(featureLane.awaitIdle(1_000) && depthLane.awaitIdle(1_000)) {
                "Observation accounting remains owned across pause"
            }
            synchronized(lock) {
                if (lifecycleGeneration == expectedLifecycle) pauseDrainPending = false
            }
        } finally { pauseDrainGate.unlock() }
    }
    fun pause() { val token = fencePause(); finishPause(token) }

    fun resume(expectedLifecycle: Long = lifecycleToken()): Boolean {
        finishPause(expectedLifecycle)
        return lifecycleLock.write {
            if (!isCurrentLifecycle(expectedLifecycle)) return@write false
            val current = ownership() ?: run {
                // Authority is independently checked by every observation
                // offer. A lifecycle resume without START/baseline ACK must
                // not leave a permanent pause after authority arrives later.
                mapper.resume()
                val accepted = synchronized(lock) {
                    if (closed || lifecycleGeneration != expectedLifecycle) false else {
                        paused = false
                        // Keep an earlier cut until a later qualified resume
                        // can perform its existing rollover/accounting reset.
                        resumeCount++
                        ingressDrainGeneration++
                        true
                    }
                }
                if (!accepted) mapper.pause()
                return@write false
            }
            val previous = synchronized(lock) {
                if (closed || lifecycleGeneration != expectedLifecycle) return@write false
                if (!paused && pausedOwnership == null) return@write true
                pausedOwnership
            }
            if (previous == current) {
                synchronized(lock) { sameCutResumeCount++ }
            } else {
                val featureDiscarded = featureLane.pauseAndDiscard()
                val depthDiscarded = depthLane.pauseAndDiscard()
                val ingressBefore = mapper.snapshot().residentObservations
                mapper.rollover(current)
                synchronized(lock) {
                    lifecycleDiscardedObservations += featureDiscarded + depthDiscarded
                    droppedFeatureObservations += featureDiscarded
                    droppedDepthObservations += depthDiscarded
                    rolloverDiscardedIngressObservations += ingressBefore
                    ownershipRolloverCount++
                    featureLastCopiedTimestampNs = Long.MIN_VALUE
                    depthLastCopiedTimestampNs = Long.MIN_VALUE
                    featureLastCopyAttemptTimestampNs = Long.MIN_VALUE
                    depthLastCopyAttemptTimestampNs = Long.MIN_VALUE
                    depthLastHealthyNs = Long.MIN_VALUE
                    recordOwnership(current)
                }
            }
            // Mapper work must never retain the Main admission-fence monitor.
            mapper.resume()
            val accepted = synchronized(lock) {
                if (closed || lifecycleGeneration != expectedLifecycle || ownership() != current) false else {
                    paused = false
                    pausedOwnership = null
                    resumeCount++
                    ingressDrainGeneration++
                    true
                }
            }
            if (!accepted) mapper.pause()
            accepted
        }
    }

    /** One sampled, fixed-field receipt. No reservation, acquisition or diagnostic exporter runs. */
    internal fun scalarSnapshot(expected: VisibilityObservationOwnership?): Map<String, Any?> {
        val started = System.nanoTime()
        val admission = mapper.snapshot()
        val retained = mapper.retainedDepthPayloadBytes()
        val copy = synchronized(lock) {
            val funding = depthAcquisitionFunding
            DepthScalarCopy(
                depthTransientUnavailable = depthTransientUnavailable,
                depthFailures = depthFailures,
                depthPreparationReady = depthPreparationReady,
                depthPreparationTransient = depthPreparationTransient,
                depthPreparationRejected = depthPreparationRejected,
                depthProcessingTransient = depthProcessingTransient,
                depthProcessingRejected = depthProcessingRejected,
                depthProcessorBusyDrops = depthProcessorBusyDrops,
                offeredDepthObservations = offeredDepthObservations,
                copiedDepthObservations = copiedDepthObservations,
                admittedDepthObservations = admittedDepthObservations,
                droppedDepthObservations = droppedDepthObservations,
                replacedDepthObservations = replacedDepthObservations,
                invalidDepthObservations = invalidDepthObservations,
                duplicateDepthObservations = duplicateDepthObservations,
                freshEstimateAdmissions = freshEstimateAdmissions,
                staleAutomaticRefusals = staleAutomaticRefusals,
                rawReprojectedAcquisitions = rawReprojectedAcquisitions,
                duplicateEstimateRefusals = duplicateEstimateRefusals,
                depthConfidencePairRefusals = depthConfidencePairRefusals,
                invalidImageTimestampRefusals = invalidImageTimestampRefusals,
                invalidDepthTuple = invalidDepthTuple.copyOf(),
                staleDepthTuple = staleDepthTuple.copyOf(),
                invalidDepthReason = invalidDepthReason,
                staleDepthReason = staleDepthReason,
                latestDepthImageTimestampNs = latestDepthImageTimestampNs,
                latestDepthFrameTimestampNs = latestDepthFrameTimestampNs,
                latestDepthCameraImageTimestampNs = latestDepthCameraImageTimestampNs,
                latestDepthConfidenceTimestampNs = latestDepthConfidenceTimestampNs,
                acquiredProducerResources = acquiredProducerResources,
                closedProducerResources = closedProducerResources,
                peakResidentPayloadBytes = peakResidentPayloadBytes,
                latestDepthSourceImageWidth = latestDepthSourceImageWidth,
                latestDepthSourceImageHeight = latestDepthSourceImageHeight,
                latestDepthImageWidth = latestDepthImageWidth,
                latestDepthImageHeight = latestDepthImageHeight,
                maximumDepthSamples = maximumDepthSamples,
                depthHealth = depthHealth.wireName,
                depthCapability = depthCapability.wireName,
                latestDepthAcquisitionMode = latestDepthAcquisitionMode.name,
                syntheticSource = syntheticSource,
                runtimePaused = paused,
                runtimeClosed = closed,
                ingressDrainFailed = ingressDrainFailed,
                depthAcquisitionPreparationPending = depthAcquisitionPreparationPending,
                fundingOwnerMatched = funding?.let { expected != null && it.matches(expected) } == true,
                fundingState = when { closed || paused -> "closedOrPaused"; depthAcquisitionPreparationPending -> "pending"; funding == null -> "unrequestedOrRetryable"; expected == null || !funding.matches(expected) -> "staleOwner"; funding.funded -> "funded"; else -> "refused" },
                callbackBudgetDegraded = callbackCopyBudgetDegraded,
                residentPayloadBytes = featureResidentPayloadBytes + depthResidentPayloadBytes + retained,
                accountedMapperDepths = accountedMapperDepths,
            )
        }
        val imageAgeUs = if (copy.latestDepthImageTimestampNs > 0L && copy.latestDepthCameraImageTimestampNs > 0L) {
            Math.subtractExact(copy.latestDepthCameraImageTimestampNs, copy.latestDepthImageTimestampNs) / 1_000L
        } else null
        val mapping = (mapper as? VisibilityGridIntegration)?.scalarSnapshot()
            ?: mapOf("detailUnavailable" to true)
        return mapOf(
            "source" to mapOf(
                "depthHealth" to copy.depthHealth,
                "depthCapability" to copy.depthCapability,
                "latestDepthAcquisitionMode" to copy.latestDepthAcquisitionMode,
                "depthTransientUnavailable" to copy.depthTransientUnavailable,
                "depthFailures" to copy.depthFailures,
                "depthPreparationReady" to copy.depthPreparationReady,
                "depthPreparationTransient" to copy.depthPreparationTransient,
                "depthPreparationRejected" to copy.depthPreparationRejected,
                "depthProcessingTransient" to copy.depthProcessingTransient,
                "depthProcessingRejected" to copy.depthProcessingRejected,
                "depthProcessorBusyDrops" to copy.depthProcessorBusyDrops,
                "offeredDepthObservations" to copy.offeredDepthObservations,
                "copiedDepthObservations" to copy.copiedDepthObservations,
                "droppedDepthObservations" to copy.droppedDepthObservations,
                "replacedDepthObservations" to copy.replacedDepthObservations,
                "invalidDepthObservations" to copy.invalidDepthObservations,
                "duplicateDepthObservations" to copy.duplicateDepthObservations,
                "admittedDepthObservations" to Math.addExact(copy.admittedDepthObservations, (admission.admittedDepths - copy.accountedMapperDepths).coerceAtLeast(0L)),
                "maximumDepthSamples" to copy.maximumDepthSamples,
                "syntheticSource" to copy.syntheticSource,
            ),
            "identity" to mapOf(
                "freshEstimateAdmissions" to copy.freshEstimateAdmissions,
                "staleAutomaticRefusals" to copy.staleAutomaticRefusals,
                "rawReprojectedAcquisitions" to copy.rawReprojectedAcquisitions,
                "duplicateEstimateRefusals" to copy.duplicateEstimateRefusals,
                "depthConfidencePairRefusals" to copy.depthConfidencePairRefusals,
                "invalidImageTimestampRefusals" to copy.invalidImageTimestampRefusals,
                "latestDepthImageTimestampNs" to copy.latestDepthImageTimestampNs.takeIf { it > 0L }?.toString(),
                "latestDepthFrameTimestampNs" to copy.latestDepthFrameTimestampNs.takeIf { it > 0L }?.toString(),
                "latestDepthCameraImageTimestampNs" to copy.latestDepthCameraImageTimestampNs.takeIf { it > 0L }?.toString(),
                "latestDepthConfidenceTimestampNs" to copy.latestDepthConfidenceTimestampNs.takeIf { it > 0L }?.toString(),
                "latestDepthSourceImageWidth" to copy.latestDepthSourceImageWidth,
                "latestDepthSourceImageHeight" to copy.latestDepthSourceImageHeight,
                "latestDepthImageWidth" to copy.latestDepthImageWidth,
                "latestDepthImageHeight" to copy.latestDepthImageHeight,
                "latestFrameMinusImageAgeUs" to null,
                "latestCameraMinusImageAgeUs" to imageAgeUs?.toString(),
                "depthTimestampComparability" to "unverified",
            ).plus(depthRefusalScalarFields("latestInvalidDepth", copy.invalidDepthReason, copy.invalidDepthTuple))
                .plus(depthRefusalScalarFields("latestStaleDepth", copy.staleDepthReason, copy.staleDepthTuple)),
            "gates" to mapOf(
                "fundingState" to copy.fundingState,
                "fundingOwnerMatched" to copy.fundingOwnerMatched,
                "depthAcquisitionPreparationPending" to copy.depthAcquisitionPreparationPending,
                "runtimePaused" to copy.runtimePaused,
                "runtimeClosed" to copy.runtimeClosed,
                "ingressDrainFailed" to copy.ingressDrainFailed,
                "callbackCopyBudgetState" to if (copy.callbackBudgetDegraded) "degraded" else "normal",
                "sourceSuspended" to null,
                "processorAvailable" to null,
                "noveltyEligibility" to null,
                "rateEligibility" to null,
            ),
            "resources" to mapOf(
                "acquiredProducerResources" to copy.acquiredProducerResources,
                "closedProducerResources" to copy.closedProducerResources,
                "resourceBalance" to Math.subtractExact(copy.acquiredProducerResources, copy.closedProducerResources),
                "residentPayloadBytes" to copy.residentPayloadBytes,
                "peakResidentPayloadBytes" to copy.peakResidentPayloadBytes,
                "pendingRetainedDepthBytes" to retained,
            ),
            "mapping" to mapping,
            "sampling" to mapOf(
                "sourceCapturedStartNs" to started.toString(),
                "sourceCapturedEndNs" to System.nanoTime().toString(),
            ),
        )
    }

    private data class DepthScalarCopy(
        val depthTransientUnavailable: Long,
        val depthFailures: Long,
        val depthPreparationReady: Long,
        val depthPreparationTransient: Long,
        val depthPreparationRejected: Long,
        val depthProcessingTransient: Long,
        val depthProcessingRejected: Long,
        val depthProcessorBusyDrops: Long,
        val offeredDepthObservations: Long,
        val copiedDepthObservations: Long,
        val admittedDepthObservations: Long,
        val droppedDepthObservations: Long,
        val replacedDepthObservations: Long,
        val invalidDepthObservations: Long,
        val duplicateDepthObservations: Long,
        val freshEstimateAdmissions: Long,
        val staleAutomaticRefusals: Long,
        val rawReprojectedAcquisitions: Long,
        val duplicateEstimateRefusals: Long,
        val depthConfidencePairRefusals: Long,
        val invalidImageTimestampRefusals: Long,
        val invalidDepthTuple: LongArray,
        val staleDepthTuple: LongArray,
        val invalidDepthReason: String?,
        val staleDepthReason: String?,
        val latestDepthImageTimestampNs: Long,
        val latestDepthFrameTimestampNs: Long,
        val latestDepthCameraImageTimestampNs: Long,
        val latestDepthConfidenceTimestampNs: Long,
        val acquiredProducerResources: Long,
        val closedProducerResources: Long,
        val peakResidentPayloadBytes: Long,
        val latestDepthSourceImageWidth: Int,
        val latestDepthSourceImageHeight: Int,
        val latestDepthImageWidth: Int,
        val latestDepthImageHeight: Int,
        val maximumDepthSamples: Int,
        val depthHealth: String,
        val depthCapability: String,
        val latestDepthAcquisitionMode: String,
        val syntheticSource: Boolean,
        val runtimePaused: Boolean,
        val runtimeClosed: Boolean,
        val ingressDrainFailed: Boolean,
        val depthAcquisitionPreparationPending: Boolean,
        val fundingOwnerMatched: Boolean,
        val fundingState: String,
        val callbackBudgetDegraded: Boolean,
        val residentPayloadBytes: Long,
        val accountedMapperDepths: Long,
    )

    fun snapshot(): VisibilityObservationHealth {
        // The mapper may release its bounded deferred-depth slot on the ACK
        // executor without a lane callback. Read its lock-free health and
        // deferred slot before taking the runtime lock; the mapper's admission
        // executor may be publishing at the same time.
        val mapperHealth = mapper.snapshot()
        val retainedDepthPayloadBytes = mapper.retainedDepthPayloadBytes()
        return synchronized(lock) {
            recordMapperAdmissionDeltaLocked(mapperHealth)
            residentPayloadBytes = featureResidentPayloadBytes +
                depthResidentPayloadBytes + retainedDepthPayloadBytes
            peakResidentPayloadBytes = maxOf(peakResidentPayloadBytes, residentPayloadBytes)
            VisibilityObservationHealth(
                offeredFeatureObservations = offeredFeatureObservations,
                offeredDepthObservations = offeredDepthObservations,
                droppedFeatureObservations = droppedFeatureObservations,
                droppedDepthObservations = droppedDepthObservations,
                maximumFeatureSamples = maximumFeatureSamples,
                maximumDepthSamples = maximumDepthSamples,
                featureMaximumHz = 1_000_000_000L / featureIntervalNs,
                depthMaximumHz = 1_000_000_000L / depthIntervalNs,
                featureHealth = featureHealth,
                depthHealth = depthHealth,
                depthCapability = depthCapability,
                copiedFeatureObservations = copiedFeatureObservations,
                copiedDepthObservations = copiedDepthObservations,
                freshEstimateAdmissions = freshEstimateAdmissions,
                staleAutomaticRefusals = staleAutomaticRefusals,
                rawReprojectedAcquisitions = rawReprojectedAcquisitions,
                duplicateEstimateRefusals = duplicateEstimateRefusals,
                depthConfidencePairRefusals = depthConfidencePairRefusals,
                invalidImageTimestampRefusals = invalidImageTimestampRefusals,
                latestDepthImageTimestampNs = latestDepthImageTimestampNs,
                latestDepthFrameTimestampNs = latestDepthFrameTimestampNs,
                latestDepthCameraImageTimestampNs = latestDepthCameraImageTimestampNs,
                latestDepthConfidenceTimestampNs = latestDepthConfidenceTimestampNs,
                latestDepthSourceImageWidth = latestDepthSourceImageWidth,
                latestDepthSourceImageHeight = latestDepthSourceImageHeight,
                latestDepthImageWidth = latestDepthImageWidth,
                latestDepthImageHeight = latestDepthImageHeight,
                latestDepthAcquisitionMode = latestDepthAcquisitionMode,
                admittedFeatureObservations = admittedFeatureObservations,
                admittedDepthObservations = admittedDepthObservations,
                invalidFeatureObservations = invalidFeatureObservations,
                invalidDepthObservations = invalidDepthObservations,
                duplicateFeatureObservations = duplicateFeatureObservations,
                duplicateDepthObservations = duplicateDepthObservations,
                staleGenerationObservations = staleGenerationObservations,
                replacedFeatureObservations = replacedFeatureObservations,
                replacedDepthObservations = replacedDepthObservations,
                featureTransientUnavailable = featureTransientUnavailable,
                depthTransientUnavailable = depthTransientUnavailable,
                featureFailures = featureFailures,
                depthFailures = depthFailures,
                depthPreparationReady = depthPreparationReady,
                depthPreparationTransient = depthPreparationTransient,
                depthPreparationRejected = depthPreparationRejected,
                depthProcessorBusyDrops = depthProcessorBusyDrops,
                depthProcessingTransient = depthProcessingTransient,
                depthProcessingRejected = depthProcessingRejected,
                mappingRuntimeFailureCount = mappingRuntimeFailureCount,
                lastMappingFailureClass = lastMappingFailureClass,
                acquiredProducerResources = acquiredProducerResources,
                closedProducerResources = closedProducerResources,
                residentPayloadBytes = residentPayloadBytes,
                peakResidentPayloadBytes = peakResidentPayloadBytes,
                callbackCopyP95Ns = callbackCopySamples.p95(),
                callbackCopyBudgetState = if (callbackCopyBudgetDegraded && isCaptureSafe()) {
                    "severeDepthShedCaptureSafeFeature1Hz"
                } else if (callbackCopyBudgetDegraded) {
                    "severeMapIntakePausedCaptureUnsafe"
                } else {
                    "withinBudget"
                },
                callbackCopyBudgetBreaches = callbackCopyBudgetBreaches,
                callbackCopyBudgetRecoveries = callbackCopyBudgetRecoveries,
                callbackCopyDepthSheds = callbackCopyDepthSheds,
                callbackCopyFeatureSheds = callbackCopyFeatureSheds,
                captureSafe = isCaptureSafe(),
                paused = paused,
                pausedObservationRejections = pausedObservationRejections,
                lifecycleDiscardedObservations = lifecycleDiscardedObservations,
                pauseCount = pauseCount,
                resumeCount = resumeCount,
                sameCutResumeCount = sameCutResumeCount,
                ownershipRolloverCount = ownershipRolloverCount,
                rolloverDiscardedIngressObservations = rolloverDiscardedIngressObservations,
                admittedBindingGeneration = admittedBindingGeneration,
                admittedSessionGeneration = admittedSessionGeneration,
                admittedGroupGeneration = admittedGroupGeneration,
                admittedLifecycleSequence = admittedLifecycleSequence,
                admittedOperationGeneration = admittedOperationGeneration,
                syntheticSource = syntheticSource,
            )
        }
    }

    override fun close() {
        synchronized(lock) {
            if (closeCompleted) return
            if (!closed) ingressDrainGeneration++
            closed = true
            lifecycleGeneration++
        }
        depthOfferTiming?.finishActive(DepthOfferTimingLedger.LIFECYCLE)
        featureLane.close()
        depthLane.close()
        check(featureLane.awaitIdle(1_000) && depthLane.awaitIdle(1_000)) {
            "Visibility lanes did not release every admitted observation and accounting owner"
        }
        lifecycleLock.write { mapper.close() }
        synchronized(lock) { closeCompleted = true }
        if (ownsScheduler) scheduler.shutdownNow()
    }

    fun snapshotWireMap(): Map<String, Any> {
        val integration = mapper as? VisibilityGridIntegration
        val publication = integration?.integrationReceipt()
        val depthEvidence = integration?.depthEvidenceDiagnostics()
        val depthPublicationDeferral = integration?.depthPublicationDeferralSnapshot()
        val depthLookup = integration?.depthLookupDiagnostics()
        return snapshot().toWireMap() + synchronized(lock) { mapOf(
            "ingressDrainPending" to ingressDrainScheduled,
            "ingressDrainGeneration" to ingressDrainGeneration,
            "ingressDrainFailures" to ingressDrainFailures,
            "ingressDrainFailed" to ingressDrainFailed,
            "lifecycleAdmissionBusyDrops" to lifecycleAdmissionBusyDrops,
        ) } + mapOf(
            "mappingIngress" to mapper.snapshot().toWireMap(),
            "mappingPublicationStatus" to (publication?.status ?: "unavailable"),
            "mappingRejected" to (publication?.rejected ?: 0L),
            "mappingFenced" to (publication?.fenced ?: 0L),
            "canonicalReadyTiming" to (integration?.canonicalReadyTimingSnapshot() ?: emptyMap<String, Any>()),
            "canonicalReadyWake" to (integration?.canonicalReadyWakeSnapshot() ?: mapOf("enabled" to false)),
            "depthEvidence" to (depthEvidence ?: emptyMap<String, Any>()),
            "depthLookup" to (depthLookup ?: emptyMap<String, Any>()),
            "depthAdmissionTiming" to mapper.depthAdmissionTiming().toWireMap(),
            "depthOfferTiming" to (depthOfferTiming?.snapshot() ?: emptyMap<String, Any>()),
            "depthPublicationDeferral" to (depthPublicationDeferral?.toWireMap() ?: emptyMap<String, Any>()),
            "featureAdmissionTiming" to mapper.featureAdmissionTiming().toWireMap(),
            "allocationWorkspaces" to (integration?.allocationWorkspaceReceipt() ?: emptyMap<String, Long>()),
        )
    }

    private fun updateResidentBytes(
        featureBytes: Long? = null,
        depthBytes: Long? = null,
    ) {
        // This callback can run on the source lane while canonical admission
        // is holding the mapper lock. The deferred-depth slot is atomic, so
        // sample it before entering the runtime lock.
        val retainedDepthPayloadBytes = mapper.retainedDepthPayloadBytes()
        synchronized(lock) {
            if (featureBytes != null) featureResidentPayloadBytes = featureBytes
            if (depthBytes != null) depthResidentPayloadBytes = depthBytes
            val sourceResidentPayloadBytes = featureResidentPayloadBytes + depthResidentPayloadBytes
            residentPayloadBytes = sourceResidentPayloadBytes + retainedDepthPayloadBytes
            require(sourceResidentPayloadBytes <= V2_SENSOR_HANDOFF_CAPACITY_BYTES) {
                "V2 sensor handoff exceeded its one MiB owner budget"
            }
            peakResidentPayloadBytes = maxOf(peakResidentPayloadBytes, residentPayloadBytes)
        }
    }

    private fun recordMapperRuntimeFailure(
        observationOwnership: VisibilityObservationOwnership,
        error: RuntimeException,
    ) {
        val shouldProbe = synchronized(lock) {
            // A changed ownership cut is a stale observation, even when the
            // mapper's own check reports it as a RuntimeException.
            if (ownership() != observationOwnership) {
                false
            } else {
                mappingRuntimeFailureCount++
                lastMappingFailureClass = error::class.java.name.take(MAX_FAILURE_CLASS_LENGTH)
                true
            }
        }
        if (shouldProbe) onMapperFailure(error)
    }

    private fun claimCopy(timestampNs: Long, previous: Long, interval: Long): Boolean =
        !closed && !paused && ownership() != null && timestampNs > 0 &&
            (previous == Long.MIN_VALUE || timestampNs - previous >= interval)

    private fun isStructurallyValid(observation: VisibilityFeatureObservation): Boolean =
        observation.payloadBytes <= V2_SENSOR_HANDOFF_CAPACITY_BYTES / 2 &&
            observation.frame.tracking && observation.sampleCount > 0

    private fun isStructurallyValid(observation: VisibilityDepthObservation): Boolean =
        observation.payloadBytes <= V2_SENSOR_HANDOFF_CAPACITY_BYTES / 2 &&
            observation.frame.tracking && observation.sampleCount > 0

    private fun recordOwnership(value: VisibilityObservationOwnership) {
        admittedBindingGeneration = value.bindingGeneration
        admittedSessionGeneration = value.sessionGeneration
        admittedGroupGeneration = value.groupGeneration
        admittedLifecycleSequence = value.lifecycleSequence
        admittedOperationGeneration = value.operationGeneration
    }

    private fun recordCallbackCopy(value: Long) {
        callbackRecoveryProbeInFlight = false
        callbackCopySamples.record(value)
        val p95 = callbackCopySamples.p95()
        val now = nanoTime()
        if (p95 > callbackCopyBudgetNs) {
            callbackCopyBudgetBreaches++
            if (!callbackCopyBudgetDegraded) {
                lastCallbackCopyBudgetBreachNs = now
                callbackCopyBudgetDegraded = true
                callbackCopyDepthSheds++
                if (!isCaptureSafe()) callbackCopyFeatureSheds++
                if (depthCapability != VisibilityDepthCapability.UNSUPPORTED &&
                    depthHealth != VisibilitySourceHealth.FAILED
                ) {
                    depthHealth = VisibilitySourceHealth.TRANSIENT_UNAVAILABLE
                }
            }
        } else if (callbackCopyBudgetDegraded &&
            lastCallbackCopyBudgetBreachNs != Long.MIN_VALUE &&
            now - lastCallbackCopyBudgetBreachNs >= callbackCopyRecoveryNs
        ) {
            callbackCopyBudgetDegraded = false
            callbackCopyBudgetRecoveries++
            if (depthCapability != VisibilityDepthCapability.UNSUPPORTED &&
                depthHealth != VisibilitySourceHealth.FAILED
            ) {
                depthHealth = if (hasFreshDepthObservation(now)) {
                    VisibilitySourceHealth.HEALTHY
                } else {
                    VisibilitySourceHealth.CONFIGURED
                }
            }
        }
    }

    private fun deliverFeature(observation: VisibilityFeatureObservation) = lifecycleLock.read {
        val terminal = synchronized(lock) {
            when {
                closed || paused -> 1
                ownership() != observation.ownership -> 2
                featureHealth == VisibilitySourceHealth.FAILED -> 1
                else -> 0
            }
        }
        when (terminal) {
            1 -> synchronized(lock) {
                lifecycleDiscardedObservations++
                droppedFeatureObservations++
                observation.close()
            }
            2 -> synchronized(lock) {
                staleGenerationObservations++
                droppedFeatureObservations++
                observation.close()
            }
            else -> {
                mapper.admitFeature(observation)
                recordMapperAdmissionDelta(mapper.snapshot())
                afterMapperAdmission()
            }
        }
    }

    private fun deliverDepth(observation: VisibilityDepthObservation) = lifecycleLock.read {
        observation.debugOfferLedger?.stage(observation.debugOfferId, DepthOfferTimingLedger.ADMITTING)
        val terminal = synchronized(lock) {
            when {
                closed || paused -> 1
                ownership() != observation.ownership -> 2
                depthHealth == VisibilitySourceHealth.FAILED ||
                    depthHealth == VisibilitySourceHealth.UNSUPPORTED -> 1
                else -> 0
            }
        }
        when (terminal) {
            1 -> synchronized(lock) {
                lifecycleDiscardedObservations++
                droppedDepthObservations++
                observation.close()
            }
            2 -> synchronized(lock) {
                staleGenerationObservations++
                droppedDepthObservations++
                observation.close()
            }
            else -> {
                mapper.admitDepth(observation)
                recordMapperAdmissionDelta(mapper.snapshot())
                afterMapperAdmission()
            }
        }
    }

    /** Mapper counters are authoritative; either lane may finish staged work from the other. */
    private fun recordMapperAdmissionDelta(after: VisibilityMappingAdmissionHealth) = synchronized(lock) {
        recordMapperAdmissionDeltaLocked(after)
    }

    private fun recordMapperAdmissionDeltaLocked(after: VisibilityMappingAdmissionHealth) {
        val features = if (after.admittedFeatures > accountedMapperFeatures) {
            Math.subtractExact(after.admittedFeatures, accountedMapperFeatures)
        } else 0L
        val depths = if (after.admittedDepths > accountedMapperDepths) {
            Math.subtractExact(after.admittedDepths, accountedMapperDepths)
        } else 0L
        admittedFeatureObservations = Math.addExact(admittedFeatureObservations, features)
        admittedDepthObservations = Math.addExact(admittedDepthObservations, depths)
        accountedMapperFeatures = maxOf(accountedMapperFeatures, after.admittedFeatures)
        accountedMapperDepths = maxOf(accountedMapperDepths, after.admittedDepths)
    }

    private fun discardUnusableIngress(feature: Boolean = false, depth: Boolean = false): UnusableIngressDrain {
        val bothUnusable = synchronized(lock) {
            featureHealth == VisibilitySourceHealth.FAILED &&
                (depthHealth == VisibilitySourceHealth.FAILED ||
                    depthHealth == VisibilitySourceHealth.UNSUPPORTED)
        }
        val drainFeature = feature || bothUnusable
        val drainDepth = depth || bothUnusable
        val featureDiscarded = if (drainFeature) featureLane.pauseAndDiscard() else 0
        val depthDiscarded = if (drainDepth) depthLane.pauseAndDiscard() else 0
        val discarded = featureDiscarded + depthDiscarded
        synchronized(lock) {
            lifecycleDiscardedObservations = Math.addExact(lifecycleDiscardedObservations, discarded)
            droppedFeatureObservations = Math.addExact(droppedFeatureObservations, featureDiscarded)
            droppedDepthObservations = Math.addExact(droppedDepthObservations, depthDiscarded)
        }
        return UnusableIngressDrain(drainFeature, drainDepth)
    }

    private inline fun tryOfferLifecycle(observation: VisibilityFeatureObservation, operation: () -> Boolean): Boolean =
        tryOfferLifecycle({ observation.close() }, operation)
    private inline fun tryOfferLifecycle(observation: VisibilityDepthObservation, operation: () -> Boolean): Boolean =
        tryOfferLifecycle({ observation.close() }, operation)
    private inline fun tryOfferLifecycle(release: () -> Unit, operation: () -> Boolean): Boolean {
        val reader = lifecycleLock.readLock()
        if (synchronized(lock) { ingressDrainFailed } || !reader.tryLock()) {
            synchronized(lock) { lifecycleAdmissionBusyDrops++ }
            release()
            return false
        }
        return try { operation() } finally { reader.unlock() }
    }

    private data class IngressDrainClaim(
        val generation: Long, val ownership: VisibilityObservationOwnership?,
        val feature: Boolean, val depth: Boolean, val capabilityFence: Boolean, val terminalFence: Boolean,
    )
    private fun scheduleIngressDrain() {
        val schedule = synchronized(lock) {
            if (closed || ingressDrainScheduled) false else { ingressDrainScheduled = true; true }
        }
        if (schedule) enqueueIngressDrain()
    }
    private fun enqueueIngressDrain() {
        try { scheduler.execute(::drainIngressAsync) }
        catch (failure: RuntimeException) {
            synchronized(lock) { ingressDrainFailures++; ingressDrainFailed = true; ingressDrainScheduled = false }
            onMapperFailure(failure)
        }
    }
    private fun drainIngressAsync() {
        val claim = synchronized(lock) {
            if (closed) { ingressDrainScheduled = false; return }
            IngressDrainClaim(ingressDrainGeneration, requestedCapabilityOwnership, pendingFeatureDrain, pendingDepthDrain,
                pendingCapabilityFence, pendingTerminalFence).also {
                pendingFeatureDrain = false; pendingDepthDrain = false
                pendingCapabilityFence = false; pendingTerminalFence = false
            }
        }
        val drain = try {
            lifecycleLock.write {
                if (claim.capabilityFence) beforeDepthCapabilityFence()
                discardUnusableIngress(claim.feature, claim.depth).also {
                    if (claim.capabilityFence) afterDepthCapabilityInvalidated()
                    if (claim.terminalFence) afterTerminalIngressInvalidated()
                }
            }
        } catch (failure: RuntimeException) {
            synchronized(lock) { ingressDrainFailures++; ingressDrainFailed = true; ingressDrainScheduled = false }
            onMapperFailure(failure)
            return
        }
        val remaining = java.util.concurrent.atomic.AtomicInteger((if (drain.feature) 1 else 0) + (if (drain.depth) 1 else 0))
        val completed = { if (remaining.decrementAndGet() == 0) completeIngressDrain(claim) }
        if (remaining.get() == 0) completeIngressDrain(claim) else {
            if (drain.feature) featureLane.whenIdle(completed)
            if (drain.depth) depthLane.whenIdle(completed)
        }
    }
    private fun completeIngressDrain(claim: IngressDrainClaim) {
        val restart = synchronized(lock) {
            if (closed) { ingressDrainScheduled = false; false }
            else if (claim.generation != ingressDrainGeneration || pendingFeatureDrain || pendingDepthDrain) true
            else {
                ingressDrainScheduled = false
                if (claim.ownership == ownership() && requestedDepthCapability != VisibilityDepthCapability.UNSUPPORTED &&
                    depthHealth != VisibilitySourceHealth.FAILED) {
                    depthCapability = requestedDepthCapability
                    if (depthHealth == VisibilitySourceHealth.UNSUPPORTED) depthHealth = VisibilitySourceHealth.CONFIGURED
                }
                false
            }
        }
        if (restart) enqueueIngressDrain()
    }

    private data class UnusableIngressDrain(val feature: Boolean, val depth: Boolean) {
        companion object { val NONE = UnusableIngressDrain(false, false) }
    }

    companion object {
        const val TERMINAL_FAILURE_THRESHOLD = 3
        const val CALLBACK_COPY_BUDGET_NS = 2_000_000L
        const val CALLBACK_COPY_RECOVERY_PROBE_INTERVAL_NS = 1_000_000_000L
        const val SEVERE_FEATURE_INTERVAL_NS = 1_000_000_000L
        const val DEPTH_HEALTHY_FRESHNESS_WINDOW_NS = 10_000_000_000L
        private const val MAX_FAILURE_CLASS_LENGTH = 128
    }

    private fun hasFreshDepthObservation(nowNs: Long): Boolean {
        val lastHealthyNs = depthLastHealthyNs
        if (lastHealthyNs == Long.MIN_VALUE) return false
        val ageNs = nowNs - lastHealthyNs
        return ageNs >= 0 && ageNs < DEPTH_HEALTHY_FRESHNESS_WINDOW_NS
    }

    private fun callbackRecoveryProbeReady(nowNs: Long): Boolean {
        val breachNs = lastCallbackCopyBudgetBreachNs
        if (!callbackCopyBudgetDegraded || breachNs == Long.MIN_VALUE) return false
        val sinceBreachNs = nowNs - breachNs
        if (sinceBreachNs < callbackCopyRecoveryNs) return false
        val probeNs = lastCallbackRecoveryProbeNs
        return probeNs == Long.MIN_VALUE ||
            nowNs - probeNs >= CALLBACK_COPY_RECOVERY_PROBE_INTERVAL_NS
    }

    private fun isCaptureSafe(): Boolean = try {
        captureSafe.isCaptureSafe()
    } catch (_: RuntimeException) {
        false
    }
}

internal data class VisibilityObservationHealth(
    val offeredFeatureObservations: Long,
    val offeredDepthObservations: Long,
    val droppedFeatureObservations: Long,
    val droppedDepthObservations: Long,
    val maximumFeatureSamples: Int,
    val maximumDepthSamples: Int,
    val featureMaximumHz: Long,
    val depthMaximumHz: Long,
    val featureHealth: VisibilitySourceHealth,
    val depthHealth: VisibilitySourceHealth,
    val depthCapability: VisibilityDepthCapability,
    val copiedFeatureObservations: Long,
    val copiedDepthObservations: Long,
    val admittedFeatureObservations: Long,
    val admittedDepthObservations: Long,
    val invalidFeatureObservations: Long,
    val invalidDepthObservations: Long,
    val duplicateFeatureObservations: Long,
    val duplicateDepthObservations: Long,
    val staleGenerationObservations: Long,
    val replacedFeatureObservations: Long,
    val replacedDepthObservations: Long,
    val featureTransientUnavailable: Long,
    val depthTransientUnavailable: Long,
    val featureFailures: Long,
    val depthFailures: Long,
    val depthPreparationReady: Long,
    val depthPreparationTransient: Long,
    val depthPreparationRejected: Long,
    val depthProcessorBusyDrops: Long,
    val depthProcessingTransient: Long,
    val depthProcessingRejected: Long,
    val mappingRuntimeFailureCount: Long,
    val lastMappingFailureClass: String?,
    val acquiredProducerResources: Long,
    val closedProducerResources: Long,
    val residentPayloadBytes: Long,
    val peakResidentPayloadBytes: Long,
    val callbackCopyP95Ns: Long,
    val callbackCopyBudgetState: String,
    val callbackCopyBudgetBreaches: Long,
    val callbackCopyBudgetRecoveries: Long,
    val callbackCopyDepthSheds: Long,
    val callbackCopyFeatureSheds: Long,
    val captureSafe: Boolean,
    val paused: Boolean,
    val pausedObservationRejections: Long,
    val lifecycleDiscardedObservations: Long,
    val pauseCount: Long,
    val resumeCount: Long,
    val sameCutResumeCount: Long,
    val ownershipRolloverCount: Long,
    val rolloverDiscardedIngressObservations: Long,
    val admittedBindingGeneration: Long,
    val admittedSessionGeneration: Long,
    val admittedGroupGeneration: Long,
    val admittedLifecycleSequence: Long,
    val admittedOperationGeneration: Long,
    val syntheticSource: Boolean,
    val freshEstimateAdmissions: Long = 0L,
    val staleAutomaticRefusals: Long = 0L,
    val rawReprojectedAcquisitions: Long = 0L,
    val duplicateEstimateRefusals: Long = 0L,
    val depthConfidencePairRefusals: Long = 0L,
    val invalidImageTimestampRefusals: Long = 0L,
    val latestDepthImageTimestampNs: Long = 0L,
    val latestDepthFrameTimestampNs: Long = 0L,
    val latestDepthCameraImageTimestampNs: Long = 0L,
    val latestDepthConfidenceTimestampNs: Long = 0L,
    val latestDepthSourceImageWidth: Int = 0,
    val latestDepthSourceImageHeight: Int = 0,
    val latestDepthImageWidth: Int = 0,
    val latestDepthImageHeight: Int = 0,
    val latestDepthAcquisitionMode: DepthAcquisitionMode = DepthAcquisitionMode.PROVIDED,
) {
    val resourceBalance: Long get() = acquiredProducerResources - closedProducerResources

    val totalGridHealth: String
        get() = when {
            featureHealth == VisibilitySourceHealth.FAILED &&
                (depthHealth == VisibilitySourceHealth.FAILED ||
                    depthHealth == VisibilitySourceHealth.UNSUPPORTED) -> "failed"
            depthHealth == VisibilitySourceHealth.FAILED ||
                depthHealth == VisibilitySourceHealth.UNSUPPORTED -> "featureOnly"
            else -> "healthy"
        }

    fun toWireMap(): Map<String, Any> = mapOf(
        "version" to VISIBILITY_OBSERVATION_VERSION,
        "offeredFeatureObservations" to offeredFeatureObservations,
        "offeredDepthObservations" to offeredDepthObservations,
        "droppedFeatureObservations" to droppedFeatureObservations,
        "droppedDepthObservations" to droppedDepthObservations,
        "maximumFeatureSamples" to maximumFeatureSamples,
        "maximumDepthSamples" to maximumDepthSamples,
        "featureMaximumHz" to featureMaximumHz,
        "depthMaximumHz" to depthMaximumHz,
        "featureHealth" to featureHealth.wireName,
        "depthHealth" to depthHealth.wireName,
        "depthCapability" to depthCapability.wireName,
        "totalGridHealth" to totalGridHealth,
        "copiedFeatureObservations" to copiedFeatureObservations,
        "copiedDepthObservations" to copiedDepthObservations,
        "freshEstimateAdmissions" to freshEstimateAdmissions,
        "staleAutomaticRefusals" to staleAutomaticRefusals,
        "rawReprojectedAcquisitions" to rawReprojectedAcquisitions,
        "duplicateEstimateRefusals" to duplicateEstimateRefusals,
        "depthConfidencePairRefusals" to depthConfidencePairRefusals,
        "invalidImageTimestampRefusals" to invalidImageTimestampRefusals,
        "latestDepthImageTimestampNs" to latestDepthImageTimestampNs,
        "latestDepthFrameTimestampNs" to latestDepthFrameTimestampNs,
        "latestDepthCameraImageTimestampNs" to latestDepthCameraImageTimestampNs,
        "latestDepthConfidenceTimestampNs" to latestDepthConfidenceTimestampNs,
        "latestDepthSourceImageWidth" to latestDepthSourceImageWidth,
        "latestDepthSourceImageHeight" to latestDepthSourceImageHeight,
        "latestDepthImageWidth" to latestDepthImageWidth,
        "latestDepthImageHeight" to latestDepthImageHeight,
        "latestDepthAcquisitionMode" to latestDepthAcquisitionMode.name,
        "depthCalibrationQualification" to "textureScaleUnverified",
        "admittedFeatureObservations" to admittedFeatureObservations,
        "admittedDepthObservations" to admittedDepthObservations,
        "invalidFeatureObservations" to invalidFeatureObservations,
        "invalidDepthObservations" to invalidDepthObservations,
        "duplicateFeatureObservations" to duplicateFeatureObservations,
        "duplicateDepthObservations" to duplicateDepthObservations,
        "staleGenerationObservations" to staleGenerationObservations,
        "replacedFeatureObservations" to replacedFeatureObservations,
        "replacedDepthObservations" to replacedDepthObservations,
        "featureTransientUnavailable" to featureTransientUnavailable,
        "depthTransientUnavailable" to depthTransientUnavailable,
        "featureFailures" to featureFailures,
        "depthFailures" to depthFailures,
        "depthPreparationReady" to depthPreparationReady,
        "depthPreparationTransient" to depthPreparationTransient,
        "depthPreparationRejected" to depthPreparationRejected,
        "depthProcessorBusyDrops" to depthProcessorBusyDrops,
        "depthProcessingTransient" to depthProcessingTransient,
        "depthProcessingRejected" to depthProcessingRejected,
        "mappingRuntimeFailureCount" to mappingRuntimeFailureCount,
        "lastMappingFailureClass" to (lastMappingFailureClass ?: "none"),
        "acquiredProducerResources" to acquiredProducerResources,
        "closedProducerResources" to closedProducerResources,
        "resourceBalance" to resourceBalance,
        "residentPayloadBytes" to residentPayloadBytes,
        "peakResidentPayloadBytes" to peakResidentPayloadBytes,
        "callbackCopyP95Ns" to callbackCopyP95Ns,
        "callbackCopyBudgetNs" to AndroidVisibilityGridRuntime.CALLBACK_COPY_BUDGET_NS,
        "callbackCopyBudgetState" to callbackCopyBudgetState,
        "callbackCopyBudgetBreaches" to callbackCopyBudgetBreaches,
        "callbackCopyBudgetRecoveries" to callbackCopyBudgetRecoveries,
        "callbackCopyDepthSheds" to callbackCopyDepthSheds,
        "callbackCopyFeatureSheds" to callbackCopyFeatureSheds,
        "captureSafe" to captureSafe,
        "paused" to paused,
        "pausedObservationRejections" to pausedObservationRejections,
        "lifecycleDiscardedObservations" to lifecycleDiscardedObservations,
        "pauseCount" to pauseCount,
        "resumeCount" to resumeCount,
        "sameCutResumeCount" to sameCutResumeCount,
        "ownershipRolloverCount" to ownershipRolloverCount,
        "rolloverDiscardedIngressObservations" to rolloverDiscardedIngressObservations,
        "admittedBindingGeneration" to admittedBindingGeneration,
        "admittedSessionGeneration" to admittedSessionGeneration,
        "admittedGroupGeneration" to admittedGroupGeneration,
        "admittedLifecycleSequence" to admittedLifecycleSequence,
        "admittedOperationGeneration" to admittedOperationGeneration,
        "syntheticSource" to syntheticSource,
    )
}

/**
 * The production mapping intake seam. capture ingress qualifies and admits observations;
 * the selected canonical surface kernel will provide the bounded evidence mutation callbacks.
 */
internal class AndroidVisibilityGridMappingAdmission(
    private val ownership: () -> VisibilityObservationOwnership?,
    private val beforeAdmission: () -> Unit = {},
) : VisibilityObservationMapper {
    private val lock = Any()
    private var feature: VisibilityFeatureObservation? = null
    private var depth: VisibilityDepthObservation? = null
    private var admittedFeatures = 0L
    private var admittedDepths = 0L
    private var replacedFeatures = 0L
    private var replacedDepths = 0L
    private var peakResidentBytes = 0L
    private var rolloverCount = 0L
    private var rolloverDiscardedObservations = 0L
    private var rolloverOwnership: VisibilityObservationOwnership? = null
    private var lastReceipt: VisibilityMappingAdmissionReceipt? = null

    override fun admitFeature(observation: VisibilityFeatureObservation) {
        check(ownership() == observation.ownership) { "stale V2 feature mapping admission" }
        beforeAdmission()
        var replaced: VisibilityFeatureObservation? = null
        synchronized(lock) {
            check(ownership() == observation.ownership) { "stale V2 feature mapping admission" }
            replaced = feature
            if (replaced != null) replacedFeatures++
            feature = observation
            admittedFeatures++
            lastReceipt = VisibilityMappingAdmissionReceipt.from(observation)
            recordPeak()
        }
        replaced?.close()
    }

    override fun admitDepth(observation: VisibilityDepthObservation) {
        check(ownership() == observation.ownership) { "stale V2 depth mapping admission" }
        beforeAdmission()
        var replaced: VisibilityDepthObservation? = null
        synchronized(lock) {
            check(ownership() == observation.ownership) { "stale V2 depth mapping admission" }
            replaced = depth
            if (replaced != null) replacedDepths++
            depth = observation
            admittedDepths++
            lastReceipt = VisibilityMappingAdmissionReceipt.from(observation)
            recordPeak()
        }
        replaced?.close()
    }

    override fun rollover(ownership: VisibilityObservationOwnership) = synchronized(lock) {
        check(this.ownership() == ownership) { "stale V2 mapping rollover" }
        rolloverDiscardedObservations += (if (feature != null) 1 else 0) +
            (if (depth != null) 1 else 0)
        feature?.close()
        depth?.close()
        feature = null
        depth = null
        lastReceipt = null
        rolloverOwnership = ownership
        rolloverCount++
        Unit
    }

    override fun snapshot(): VisibilityMappingAdmissionHealth = synchronized(lock) {
        VisibilityMappingAdmissionHealth(
            admittedFeatures = admittedFeatures,
            admittedDepths = admittedDepths,
            replacedFeatures = replacedFeatures,
            replacedDepths = replacedDepths,
            residentBytes = residentBytes(),
            residentObservations = (if (feature != null) 1 else 0) +
                (if (depth != null) 1 else 0),
            peakResidentBytes = peakResidentBytes,
            rolloverCount = rolloverCount,
            rolloverDiscardedObservations = rolloverDiscardedObservations,
            rolloverBindingGeneration = rolloverOwnership?.bindingGeneration ?: 0,
            rolloverGroupGeneration = rolloverOwnership?.groupGeneration ?: 0,
            rolloverLifecycleSequence = rolloverOwnership?.lifecycleSequence ?: 0,
            lastReceipt = lastReceipt,
        )
    }

    override fun close() = synchronized(lock) {
        feature?.close()
        depth?.close()
        feature = null
        depth = null
    }

    private fun residentBytes(): Long =
        (feature?.payloadBytes ?: 0).toLong() + (depth?.payloadBytes ?: 0).toLong()

    private fun recordPeak() {
        peakResidentBytes = maxOf(peakResidentBytes, residentBytes())
        check(peakResidentBytes <= V2_SENSOR_HANDOFF_CAPACITY_BYTES)
    }
}

internal data class VisibilityMappingAdmissionReceipt(
    val source: String,
    val sourceTimestampNs: Long,
    val frameSequence: Long,
    val sampleCount: Int,
    val payloadBytes: Int,
    val depthCapability: String,
    val sessionGeneration: Long,
    val groupGeneration: Long,
    val bindingGeneration: Long,
    val lifecycleSequence: Long,
    val operationGeneration: Long,
) {
    fun toWireMap(): Map<String, Any> = mapOf(
        "source" to source,
        "sourceTimestampNs" to sourceTimestampNs,
        "frameSequence" to frameSequence,
        "sampleCount" to sampleCount,
        "payloadBytes" to payloadBytes,
        "depthCapability" to depthCapability,
        "sessionGeneration" to sessionGeneration,
        "groupGeneration" to groupGeneration,
        "bindingGeneration" to bindingGeneration,
        "lifecycleSequence" to lifecycleSequence,
        "operationGeneration" to operationGeneration,
    )

    companion object {
        fun from(value: VisibilityFeatureObservation) = VisibilityMappingAdmissionReceipt(
            source = value.frame.source.wireName,
            sourceTimestampNs = value.frame.sourceTimestampNs,
            frameSequence = value.frame.frameSequence,
            sampleCount = value.sampleCount,
            payloadBytes = value.payloadBytes,
            depthCapability = value.frame.depthCapability.wireName,
            sessionGeneration = value.ownership.sessionGeneration,
            groupGeneration = value.ownership.groupGeneration,
            bindingGeneration = value.ownership.bindingGeneration,
            lifecycleSequence = value.ownership.lifecycleSequence,
            operationGeneration = value.ownership.operationGeneration,
        )

        fun from(value: VisibilityDepthObservation) = VisibilityMappingAdmissionReceipt(
            source = value.frame.source.wireName,
            sourceTimestampNs = value.frame.sourceTimestampNs,
            frameSequence = value.frame.frameSequence,
            sampleCount = value.sampleCount,
            payloadBytes = value.payloadBytes,
            depthCapability = value.frame.depthCapability.wireName,
            sessionGeneration = value.ownership.sessionGeneration,
            groupGeneration = value.ownership.groupGeneration,
            bindingGeneration = value.ownership.bindingGeneration,
            lifecycleSequence = value.ownership.lifecycleSequence,
            operationGeneration = value.ownership.operationGeneration,
        )
    }
}

internal data class VisibilityMappingAdmissionHealth(
    val admittedFeatures: Long,
    val admittedDepths: Long,
    val replacedFeatures: Long,
    val replacedDepths: Long,
    val residentBytes: Long,
    val residentObservations: Int,
    val peakResidentBytes: Long,
    val rolloverCount: Long,
    val rolloverDiscardedObservations: Long,
    val rolloverBindingGeneration: Long,
    val rolloverGroupGeneration: Long,
    val rolloverLifecycleSequence: Long,
    val lastReceipt: VisibilityMappingAdmissionReceipt?,
) {
    fun toWireMap(): Map<String, Any> = mapOf(
        "admittedFeatures" to admittedFeatures,
        "admittedDepths" to admittedDepths,
        "replacedFeatures" to replacedFeatures,
        "replacedDepths" to replacedDepths,
        "residentBytes" to residentBytes,
        "residentObservations" to residentObservations,
        "peakResidentBytes" to peakResidentBytes,
        "rolloverCount" to rolloverCount,
        "rolloverDiscardedObservations" to rolloverDiscardedObservations,
        "rolloverBindingGeneration" to rolloverBindingGeneration,
        "rolloverGroupGeneration" to rolloverGroupGeneration,
        "rolloverLifecycleSequence" to rolloverLifecycleSequence,
        "lastReceipt" to (lastReceipt?.toWireMap() ?: emptyMap<String, Any>()),
    )

    companion object {
        fun empty() = VisibilityMappingAdmissionHealth(
            0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, null,
        )
    }
}

@Suppress("PLATFORM_CLASS_MAPPED_TO_KOTLIN")
private class LatestObservationLane<T : Any>(
    private val intervalNs: Long,
    private val scheduler: ScheduledExecutorService,
    private val nanoTime: () -> Long,
    private val payloadBytes: (T) -> Int,
    private val deliver: (T) -> Unit,
    private val afterDelivery: (T) -> Unit,
    private val beforeResidentPublication: (T) -> Unit,
    private val afterResidentPublication: (T) -> Unit,
    private val beforeDiscardResidentPublication: () -> Unit,
    private val onReplacement: (T) -> Unit,
    private val onStale: () -> Unit,
    private val onMapperFailure: (T, RuntimeException) -> Unit,
    private val onDiscard: (T) -> Unit,
    private val onResidentBytesChanged: (Long) -> Unit,
) {
    private val lock = Any()
    private val publicationLock = Any()
    private var current: T? = null
    private var latest: T? = null
    private var scheduled = false
    private var closed = false
    private var lastDeliveryNs = Long.MIN_VALUE
    private var scheduleEpoch = 0L
    private var running = false
    private var accountingPending = false
    private var idleCompletion: (() -> Unit)? = null

    val residentBytes: Long
        get() = synchronized(lock) {
            (current?.let(payloadBytes) ?: 0).toLong() +
                (latest?.let(payloadBytes) ?: 0).toLong()
        }

    fun hasOutstandingWork(): Boolean = synchronized(lock) {
        current != null || latest != null || scheduled || running || accountingPending
    }

    fun offer(value: T, beforeOffer: ((Boolean) -> Unit)? = null) {
        var discarded: T? = null
        synchronized(lock) {
            if (closed) {
                discarded = value
                return@synchronized
            }
            beforeOffer?.invoke(
                current != null || latest != null || scheduled || running || accountingPending,
            )
            if (current == null && !scheduled) {
                current = value
                scheduled = true
                scheduleLocked(0)
            } else {
                latest?.let(onReplacement)
                discarded = latest
                latest = value
            }
        }
        discarded?.let(onDiscard)
        publishResidentBytes()
    }

    fun close() {
        val discarded: List<T>
        synchronized(lock) {
            closed = true
            scheduleEpoch++
            discarded = buildList {
                if (!running) current?.let(::add)
                latest?.let(::add)
            }
            if (!running) { current = null; scheduled = false }
            latest = null
        }
        discarded.forEach(onDiscard)
        publishResidentBytes()
        completeIdleIfReady()
    }

    /** Drops every copied value that has not entered mapper admission. */
    fun pauseAndDiscard(): Long {
        val discardedValues: List<T>
        val discarded = synchronized(lock) {
            discardedValues = buildList {
                if (!running) current?.let(::add)
                latest?.let(::add)
            }
            val count = discardedValues.size
            if (!running) current = null
            latest = null
            if (!running) scheduled = false
            scheduleEpoch++
            count.toLong()
        }
        discardedValues.forEach(onDiscard)
        publishResidentBytes(beforeDiscardResidentPublication)
        completeIdleIfReady()
        return discarded
    }

    fun whenIdle(completion: () -> Unit) {
        val ready = synchronized(lock) {
            if (isIdleLocked()) true else {
                check(idleCompletion == null) { "Only one owned lane drain may be outstanding" }
                idleCompletion = completion
                false
            }
        }
        if (ready) completion()
    }
    private fun isIdleLocked() = !scheduled && current == null && latest == null && !running && !accountingPending
    private fun completeIdleIfReady() {
        val completion = synchronized(lock) {
            if (!isIdleLocked()) null else idleCompletion.also { idleCompletion = null }
        }
        completion?.invoke()
    }

    fun awaitIdle() = synchronized(lock) {
        while (!isIdleLocked()) (lock as java.lang.Object).wait()
    }
    fun awaitIdle(timeoutMillis: Long): Boolean {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
        return synchronized(lock) {
            while (!isIdleLocked()) {
                val remaining = deadline - System.nanoTime()
                if (remaining <= 0L) return@synchronized false
                TimeUnit.NANOSECONDS.timedWait(lock, remaining)
            }
            true
        }
    }

    private fun scheduleLocked(delayNs: Long) {
        val epoch = scheduleEpoch
        scheduler.schedule({ runOne(epoch) }, delayNs.coerceAtLeast(0), TimeUnit.NANOSECONDS)
    }

    private fun runOne(epoch: Long) {
        val value = synchronized(lock) {
            if (closed || epoch != scheduleEpoch) return
            current?.also { running = true } ?: return
        }
        var delivered = false
        try {
            deliver(value)
            delivered = true
        } catch (error: RuntimeException) {
            // The exact cut is rechecked by the mapper. A cut changing between
            // lane qualification and mapper admission is a stale observation,
            // not a scheduler failure that may wedge the source forever.
            onMapperFailure(value, error)
            onStale()
        }
        if (!delivered) onDiscard(value)
        try {
            afterDelivery(value)
        } catch (_: RuntimeException) {
            if (delivered) onDiscard(value)
            onStale()
        }
        val nextDelay = synchronized(lock) {
            lastDeliveryNs = nanoTime()
            running = false
            accountingPending = true
            current = latest
            latest = null
            if (current == null || closed) {
                scheduled = false
                null
            } else {
                intervalNs
            }
        }
        try {
            beforeResidentPublication(value)
            publishResidentBytes()
            afterResidentPublication(value)
        } finally {
            synchronized(lock) {
                accountingPending = false
                (lock as java.lang.Object).notifyAll()
            }
        }
        completeIdleIfReady()
        if (nextDelay != null) {
            synchronized(lock) {
                if (!closed && scheduled && epoch == scheduleEpoch) scheduleLocked(nextDelay)
            }
        }
    }

    private fun publishResidentBytes(beforePublication: () -> Unit = {}) {
        beforePublication()
        synchronized(publicationLock) {
            onResidentBytesChanged(residentBytes)
        }
    }
}

private class BoundedLatencySamples(capacity: Int) {
    private val values = LongArray(capacity)
    private var count = 0
    private var next = 0

    fun record(value: Long) {
        values[next] = value.coerceAtLeast(0)
        next = (next + 1) % values.size
        count = minOf(count + 1, values.size)
    }

    fun clear() {
        count = 0
        next = 0
    }

    fun p95(): Long {
        if (count == 0) return 0
        val sorted = values.copyOf(count).sortedArray()
        return sorted[((count * 95 + 99) / 100 - 1).coerceIn(0, count - 1)]
    }
}
