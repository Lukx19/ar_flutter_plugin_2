package com.uhg0.ar_flutter_plugin_2.visibilitygrid

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
) : AutoCloseable {
    private val lock = Any()
    private val lifecycleLock = ReentrantReadWriteLock()
    private var closed = false
    private var paused = false
    private var pausedOwnership: VisibilityObservationOwnership? = null
    private var featureLastCopiedTimestampNs = Long.MIN_VALUE
    private var depthLastCopiedTimestampNs = Long.MIN_VALUE
    private var featureLastCopyAttemptTimestampNs = Long.MIN_VALUE
    private var depthLastCopyAttemptTimestampNs = Long.MIN_VALUE
    private var featureHealth = VisibilitySourceHealth.CONFIGURED
    private var depthHealth = VisibilitySourceHealth.UNSUPPORTED
    private var depthCapability = VisibilityDepthCapability.UNSUPPORTED
    private var syntheticSource = false
    private var copiedFeatureObservations = 0L
    private var copiedDepthObservations = 0L
    private var admittedFeatureObservations = 0L
    private var admittedDepthObservations = 0L
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
        deliver = ::deliverFeature,
        onReplacement = { synchronized(lock) { replacedFeatureObservations++ } },
        onStale = { synchronized(lock) { staleGenerationObservations++ } },
        onResidentBytesChanged = { bytes -> updateResidentBytes(featureBytes = bytes) },
    )
    private val depthLane = LatestObservationLane(
        intervalNs = depthIntervalNs,
        scheduler = scheduler,
        nanoTime = nanoTime,
        payloadBytes = VisibilityDepthObservation::payloadBytes,
        deliver = ::deliverDepth,
        onReplacement = { synchronized(lock) { replacedDepthObservations++ } },
        onStale = { synchronized(lock) { staleGenerationObservations++ } },
        onResidentBytesChanged = { bytes -> updateResidentBytes(depthBytes = bytes) },
    )

    init {
        require(featureIntervalNs > 0 && depthIntervalNs > 0)
        require(callbackCopySampleCapacity > 0)
        require(callbackCopyBudgetNs == CALLBACK_COPY_BUDGET_NS)
        require(callbackCopyRecoveryNs >= 0)
    }

    fun setDepthCapability(capability: VisibilityDepthCapability) = synchronized(lock) {
        depthCapability = capability
        depthHealth = if (capability == VisibilityDepthCapability.UNSUPPORTED) {
            VisibilitySourceHealth.UNSUPPORTED
        } else if (depthHealth == VisibilitySourceHealth.UNSUPPORTED) {
            VisibilitySourceHealth.CONFIGURED
        } else {
            depthHealth
        }
    }

    fun configureSyntheticSource(capability: VisibilityDepthCapability) = synchronized(lock) {
        syntheticSource = true
        setDepthCapability(capability)
    }

    fun isSyntheticSource(): Boolean = synchronized(lock) { syntheticSource }

    fun featureSampleCapacity(): Int = synchronized(lock) {
        when {
            !callbackCopyBudgetDegraded -> V2_FEATURE_SAMPLE_CAPACITY
            isCaptureSafe() -> 1_000
            else -> 0
        }
    }

    fun shouldCopyFeature(timestampNs: Long): Boolean = synchronized(lock) {
        if (callbackCopyBudgetDegraded && !isCaptureSafe()) return@synchronized false
        claimCopy(
            timestampNs,
            featureLastCopyAttemptTimestampNs,
            if (callbackCopyBudgetDegraded && isCaptureSafe()) {
                maxOf(featureIntervalNs, SEVERE_FEATURE_INTERVAL_NS)
            } else if (callbackCopyBudgetDegraded) {
                Long.MAX_VALUE
            }
            else featureIntervalNs,
        ).also {
            if (it) featureLastCopyAttemptTimestampNs = timestampNs
        }
    }

    fun shouldCopyDepth(timestampNs: Long): Boolean = synchronized(lock) {
        !callbackCopyBudgetDegraded && depthCapability != VisibilityDepthCapability.UNSUPPORTED &&
            claimCopy(timestampNs, depthLastCopyAttemptTimestampNs, depthIntervalNs).also {
                if (it) depthLastCopyAttemptTimestampNs = timestampNs
            }
    }

    fun offerFeature(
        observation: VisibilityFeatureObservation,
        callbackCopyNs: Long = 0,
    ): Boolean {
        synchronized(lock) {
            if (paused) {
                pausedObservationRejections++
                return false
            }
            if (closed || !isStructurallyValid(observation) ||
                observation.samples.size > featureSampleCapacity() ||
                (callbackCopyBudgetDegraded && !isCaptureSafe())
            ) {
                invalidFeatureObservations++
                return false
            }
            if (observation.frame.sourceTimestampNs <= featureLastCopiedTimestampNs) {
                duplicateFeatureObservations++
                return false
            }
            if (ownership() != observation.ownership) {
                staleGenerationObservations++
                return false
            }
            featureLastCopiedTimestampNs = observation.frame.sourceTimestampNs
            recordOwnership(observation.ownership)
            copiedFeatureObservations++
            recordCallbackCopy(callbackCopyNs)
        }
        featureLane.offer(observation)
        return true
    }

    fun offerDepth(
        observation: VisibilityDepthObservation,
        callbackCopyNs: Long = 0,
    ): Boolean {
        synchronized(lock) {
            if (paused) {
                pausedObservationRejections++
                return false
            }
            if (closed || callbackCopyBudgetDegraded ||
                depthCapability == VisibilityDepthCapability.UNSUPPORTED ||
                !isStructurallyValid(observation)
            ) {
                invalidDepthObservations++
                return false
            }
            if (observation.frame.sourceTimestampNs <= depthLastCopiedTimestampNs) {
                duplicateDepthObservations++
                return false
            }
            if (ownership() != observation.ownership) {
                staleGenerationObservations++
                return false
            }
            depthLastCopiedTimestampNs = observation.frame.sourceTimestampNs
            recordOwnership(observation.ownership)
            copiedDepthObservations++
            recordCallbackCopy(callbackCopyNs)
        }
        depthLane.offer(observation)
        return true
    }

    fun recordFeatureTransientUnavailable() = synchronized(lock) {
        featureTransientUnavailable++
        featureHealth = VisibilitySourceHealth.TRANSIENT_UNAVAILABLE
    }

    fun recordDepthTransientUnavailable() = synchronized(lock) {
        if (depthCapability != VisibilityDepthCapability.UNSUPPORTED) {
            depthTransientUnavailable++
            depthHealth = VisibilitySourceHealth.TRANSIENT_UNAVAILABLE
        }
    }

    fun recordFeatureFailure() = synchronized(lock) {
        featureFailures++
        featureHealth = VisibilitySourceHealth.FAILED
    }

    fun recordDepthFailure() = synchronized(lock) {
        if (depthCapability != VisibilityDepthCapability.UNSUPPORTED) {
            depthFailures++
            if (depthFailures >= TERMINAL_FAILURE_THRESHOLD) {
                depthHealth = VisibilitySourceHealth.FAILED
            } else {
                depthHealth = VisibilitySourceHealth.TRANSIENT_UNAVAILABLE
            }
        }
    }

    fun recordFeatureStalled() = synchronized(lock) {
        featureHealth = VisibilitySourceHealth.STALLED
    }

    fun recordDepthStalled() = synchronized(lock) {
        if (depthCapability != VisibilityDepthCapability.UNSUPPORTED) {
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
    fun pause() {
        synchronized(lock) {
            if (closed || paused) return
            paused = true
            pausedOwnership = ownership()
            pauseCount++
        }
        val discarded = featureLane.pauseAndDiscard() + depthLane.pauseAndDiscard()
        synchronized(lock) { lifecycleDiscardedObservations += discarded }
    }

    /** Resumes the exact paused cut, or fences and rolls over to its replacement. */
    fun resume(): Boolean = lifecycleLock.write {
        val current = ownership() ?: return@write false
        val previous = synchronized(lock) {
            if (closed) return@write false
            if (!paused) return@write true
            pausedOwnership
        }
        if (previous == current) {
            synchronized(lock) { sameCutResumeCount++ }
        } else {
            val discarded = featureLane.pauseAndDiscard() + depthLane.pauseAndDiscard()
            val ingressBefore = mapper.snapshot().residentObservations
            mapper.rollover(current)
            synchronized(lock) {
                lifecycleDiscardedObservations += discarded
                rolloverDiscardedIngressObservations += ingressBefore
                ownershipRolloverCount++
                featureLastCopiedTimestampNs = Long.MIN_VALUE
                depthLastCopiedTimestampNs = Long.MIN_VALUE
                featureLastCopyAttemptTimestampNs = Long.MIN_VALUE
                depthLastCopyAttemptTimestampNs = Long.MIN_VALUE
                recordOwnership(current)
            }
        }
        synchronized(lock) {
            paused = false
            pausedOwnership = null
            resumeCount++
        }
        true
    }

    fun snapshot(): VisibilityObservationHealth = synchronized(lock) {
        VisibilityObservationHealth(
            featureHealth = featureHealth,
            depthHealth = depthHealth,
            depthCapability = depthCapability,
            copiedFeatureObservations = copiedFeatureObservations,
            copiedDepthObservations = copiedDepthObservations,
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

    override fun close() {
        lifecycleLock.write {
            synchronized(lock) {
                if (closed) return
                closed = true
            }
            featureLane.close()
            depthLane.close()
            mapper.close()
        }
        if (ownsScheduler) scheduler.shutdownNow()
    }

    fun snapshotWireMap(): Map<String, Any> =
        snapshot().toWireMap() + mapOf("mappingIngress" to mapper.snapshot().toWireMap())

    private fun updateResidentBytes(
        featureBytes: Long? = null,
        depthBytes: Long? = null,
    ) = synchronized(lock) {
        if (featureBytes != null) featureResidentPayloadBytes = featureBytes
        if (depthBytes != null) depthResidentPayloadBytes = depthBytes
        residentPayloadBytes = featureResidentPayloadBytes + depthResidentPayloadBytes
        require(residentPayloadBytes <= V2_SENSOR_HANDOFF_CAPACITY_BYTES) {
            "V2 sensor handoff exceeded its one MiB owner budget"
        }
        peakResidentPayloadBytes = maxOf(peakResidentPayloadBytes, residentPayloadBytes)
    }

    private fun claimCopy(timestampNs: Long, previous: Long, interval: Long): Boolean =
        !closed && !paused && ownership() != null && timestampNs > 0 &&
            (previous == Long.MIN_VALUE || timestampNs - previous >= interval)

    private fun isStructurallyValid(observation: VisibilityFeatureObservation): Boolean =
        observation.payloadBytes <= V2_SENSOR_HANDOFF_CAPACITY_BYTES / 2 &&
            observation.frame.tracking && observation.samples.isNotEmpty()

    private fun isStructurallyValid(observation: VisibilityDepthObservation): Boolean =
        observation.payloadBytes <= V2_SENSOR_HANDOFF_CAPACITY_BYTES / 2 &&
            observation.frame.tracking && observation.samples.isNotEmpty()

    private fun recordOwnership(value: VisibilityObservationOwnership) {
        admittedBindingGeneration = value.bindingGeneration
        admittedSessionGeneration = value.sessionGeneration
        admittedGroupGeneration = value.groupGeneration
        admittedLifecycleSequence = value.lifecycleSequence
        admittedOperationGeneration = value.operationGeneration
    }

    private fun recordCallbackCopy(value: Long) {
        callbackCopySamples.record(value)
        val p95 = callbackCopySamples.p95()
        val now = nanoTime()
        if (p95 > callbackCopyBudgetNs) {
            lastCallbackCopyBudgetBreachNs = now
            callbackCopyBudgetBreaches++
            if (!callbackCopyBudgetDegraded) {
                callbackCopyBudgetDegraded = true
                callbackCopyDepthSheds++
                if (!isCaptureSafe()) callbackCopyFeatureSheds++
                if (depthCapability != VisibilityDepthCapability.UNSUPPORTED) {
                    depthHealth = VisibilitySourceHealth.TRANSIENT_UNAVAILABLE
                }
            }
        } else if (callbackCopyBudgetDegraded &&
            lastCallbackCopyBudgetBreachNs != Long.MIN_VALUE &&
            now - lastCallbackCopyBudgetBreachNs >= callbackCopyRecoveryNs
        ) {
            callbackCopyBudgetDegraded = false
            callbackCopyBudgetRecoveries++
            if (depthCapability != VisibilityDepthCapability.UNSUPPORTED) {
                depthHealth = VisibilitySourceHealth.CONFIGURED
            }
        }
    }

    private fun deliverFeature(observation: VisibilityFeatureObservation) = lifecycleLock.read {
        val terminal = synchronized(lock) {
            when {
                closed || paused -> 1
                ownership() != observation.ownership -> 2
                else -> 0
            }
        }
        when (terminal) {
            1 -> synchronized(lock) { lifecycleDiscardedObservations++ }
            2 -> synchronized(lock) { staleGenerationObservations++ }
            else -> {
                mapper.admitFeature(observation)
                synchronized(lock) {
                    admittedFeatureObservations++
                    featureHealth = VisibilitySourceHealth.HEALTHY
                }
            }
        }
    }

    private fun deliverDepth(observation: VisibilityDepthObservation) = lifecycleLock.read {
        val terminal = synchronized(lock) {
            when {
                closed || paused -> 1
                ownership() != observation.ownership -> 2
                else -> 0
            }
        }
        when (terminal) {
            1 -> synchronized(lock) { lifecycleDiscardedObservations++ }
            2 -> synchronized(lock) { staleGenerationObservations++ }
            else -> {
                mapper.admitDepth(observation)
                synchronized(lock) {
                    admittedDepthObservations++
                    depthHealth = VisibilitySourceHealth.HEALTHY
                }
            }
        }
    }

    companion object {
        const val TERMINAL_FAILURE_THRESHOLD = 3
        const val CALLBACK_COPY_BUDGET_NS = 2_000_000L
        const val SEVERE_FEATURE_INTERVAL_NS = 1_000_000_000L
    }

    private fun isCaptureSafe(): Boolean = try {
        captureSafe.isCaptureSafe()
    } catch (_: RuntimeException) {
        false
    }
}

internal data class VisibilityObservationHealth(
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
        "featureHealth" to featureHealth.wireName,
        "depthHealth" to depthHealth.wireName,
        "depthCapability" to depthCapability.wireName,
        "totalGridHealth" to totalGridHealth,
        "copiedFeatureObservations" to copiedFeatureObservations,
        "copiedDepthObservations" to copiedDepthObservations,
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
 * The production mapping intake seam. M2 qualifies and admits observations;
 * the selected M3 kernel will provide the bounded evidence mutation callbacks.
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
        synchronized(lock) {
            check(ownership() == observation.ownership) { "stale V2 feature mapping admission" }
            if (feature != null) replacedFeatures++
            feature = observation
            admittedFeatures++
            lastReceipt = VisibilityMappingAdmissionReceipt.from(observation)
            recordPeak()
        }
    }

    override fun admitDepth(observation: VisibilityDepthObservation) {
        check(ownership() == observation.ownership) { "stale V2 depth mapping admission" }
        beforeAdmission()
        synchronized(lock) {
            check(ownership() == observation.ownership) { "stale V2 depth mapping admission" }
            if (depth != null) replacedDepths++
            depth = observation
            admittedDepths++
            lastReceipt = VisibilityMappingAdmissionReceipt.from(observation)
            recordPeak()
        }
    }

    override fun rollover(ownership: VisibilityObservationOwnership) = synchronized(lock) {
        check(this.ownership() == ownership) { "stale V2 mapping rollover" }
        rolloverDiscardedObservations += (if (feature != null) 1 else 0) +
            (if (depth != null) 1 else 0)
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
            sampleCount = value.samples.size,
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
            sampleCount = value.samples.size,
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

private class LatestObservationLane<T : Any>(
    private val intervalNs: Long,
    private val scheduler: ScheduledExecutorService,
    private val nanoTime: () -> Long,
    private val payloadBytes: (T) -> Int,
    private val deliver: (T) -> Unit,
    private val onReplacement: () -> Unit,
    private val onStale: () -> Unit,
    private val onResidentBytesChanged: (Long) -> Unit,
) {
    private val lock = Any()
    private var current: T? = null
    private var latest: T? = null
    private var scheduled = false
    private var closed = false
    private var lastDeliveryNs = Long.MIN_VALUE
    private var scheduleEpoch = 0L
    private var running = false

    val residentBytes: Long
        get() = synchronized(lock) {
            (current?.let(payloadBytes) ?: 0).toLong() +
                (latest?.let(payloadBytes) ?: 0).toLong()
        }

    fun offer(value: T) {
        synchronized(lock) {
            if (closed) return
            if (current == null && !scheduled) {
                current = value
                scheduled = true
                scheduleLocked(0)
            } else {
                if (latest != null) onReplacement()
                latest = value
            }
        }
        onResidentBytesChanged(residentBytes)
    }

    fun close() {
        synchronized(lock) {
            closed = true
            scheduleEpoch++
            current = null
            latest = null
        }
        onResidentBytesChanged(residentBytes)
    }

    /** Drops every copied value that has not entered mapper admission. */
    fun pauseAndDiscard(): Long {
        val discarded = synchronized(lock) {
            val count = (if (!running && current != null) 1 else 0) +
                (if (latest != null) 1 else 0)
            if (!running) current = null
            latest = null
            if (!running) scheduled = false
            scheduleEpoch++
            count.toLong()
        }
        onResidentBytesChanged(residentBytes)
        return discarded
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
        try {
            deliver(value)
        } catch (_: RuntimeException) {
            // The exact cut is rechecked by the mapper. A cut changing between
            // lane qualification and mapper admission is a stale observation,
            // not a scheduler failure that may wedge the source forever.
            onStale()
        }
        val nextDelay = synchronized(lock) {
            lastDeliveryNs = nanoTime()
            running = false
            current = latest
            latest = null
            if (current == null || closed) {
                scheduled = false
                null
            } else {
                intervalNs
            }
        }
        onResidentBytesChanged(residentBytes)
        if (nextDelay != null) {
            synchronized(lock) {
                if (!closed && scheduled && epoch == scheduleEpoch) scheduleLocked(nextDelay)
            }
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

    fun p95(): Long {
        if (count == 0) return 0
        val sorted = values.copyOf(count).sortedArray()
        return sorted[((count * 95 + 99) / 100 - 1).coerceIn(0, count - 1)]
    }
}
