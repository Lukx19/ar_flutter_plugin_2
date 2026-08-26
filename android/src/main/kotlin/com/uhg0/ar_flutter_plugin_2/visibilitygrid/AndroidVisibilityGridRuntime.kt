package com.uhg0.ar_flutter_plugin_2.visibilitygrid

import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

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
) : AutoCloseable {
    private val lock = Any()
    private var closed = false
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
    private var callbackCopySamples = BoundedLatencySamples(256)
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
        isCurrent = { observation -> ownership() == observation.ownership },
        deliver = { observation ->
            mapper.admitFeature(observation)
            synchronized(lock) {
                admittedFeatureObservations++
                featureHealth = VisibilitySourceHealth.HEALTHY
            }
        },
        onReplacement = { synchronized(lock) { replacedFeatureObservations++ } },
        onStale = { synchronized(lock) { staleGenerationObservations++ } },
        onResidentBytesChanged = { bytes -> updateResidentBytes(featureBytes = bytes) },
    )
    private val depthLane = LatestObservationLane(
        intervalNs = depthIntervalNs,
        scheduler = scheduler,
        nanoTime = nanoTime,
        payloadBytes = VisibilityDepthObservation::payloadBytes,
        isCurrent = { observation -> ownership() == observation.ownership },
        deliver = { observation ->
            mapper.admitDepth(observation)
            synchronized(lock) {
                admittedDepthObservations++
                depthHealth = VisibilitySourceHealth.HEALTHY
            }
        },
        onReplacement = { synchronized(lock) { replacedDepthObservations++ } },
        onStale = { synchronized(lock) { staleGenerationObservations++ } },
        onResidentBytesChanged = { bytes -> updateResidentBytes(depthBytes = bytes) },
    )

    init {
        require(featureIntervalNs > 0 && depthIntervalNs > 0)
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

    fun shouldCopyFeature(timestampNs: Long): Boolean = synchronized(lock) {
        claimCopy(timestampNs, featureLastCopyAttemptTimestampNs, featureIntervalNs).also {
            if (it) featureLastCopyAttemptTimestampNs = timestampNs
        }
    }

    fun shouldCopyDepth(timestampNs: Long): Boolean = synchronized(lock) {
        depthCapability != VisibilityDepthCapability.UNSUPPORTED &&
            claimCopy(timestampNs, depthLastCopyAttemptTimestampNs, depthIntervalNs).also {
                if (it) depthLastCopyAttemptTimestampNs = timestampNs
            }
    }

    fun offerFeature(
        observation: VisibilityFeatureObservation,
        callbackCopyNs: Long = 0,
    ): Boolean {
        synchronized(lock) {
            if (closed || !isStructurallyValid(observation)) {
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
            callbackCopySamples.record(callbackCopyNs)
        }
        featureLane.offer(observation)
        return true
    }

    fun offerDepth(
        observation: VisibilityDepthObservation,
        callbackCopyNs: Long = 0,
    ): Boolean {
        synchronized(lock) {
            if (closed || depthCapability == VisibilityDepthCapability.UNSUPPORTED ||
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
            callbackCopySamples.record(callbackCopyNs)
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
            admittedBindingGeneration = admittedBindingGeneration,
            admittedSessionGeneration = admittedSessionGeneration,
            admittedGroupGeneration = admittedGroupGeneration,
            admittedLifecycleSequence = admittedLifecycleSequence,
            admittedOperationGeneration = admittedOperationGeneration,
            syntheticSource = syntheticSource,
        )
    }

    override fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
        }
        featureLane.close()
        depthLane.close()
        if (ownsScheduler) scheduler.shutdownNow()
    }

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
        !closed && ownership() != null && timestampNs > 0 &&
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

    private companion object {
        const val TERMINAL_FAILURE_THRESHOLD = 3
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
    private val onFeature: (VisibilityFeatureObservation) -> Unit = {},
    private val onDepth: (VisibilityDepthObservation) -> Unit = {},
) : VisibilityObservationMapper {
    override fun admitFeature(observation: VisibilityFeatureObservation) {
        check(ownership() == observation.ownership) { "stale V2 feature mapping admission" }
        onFeature(observation)
    }

    override fun admitDepth(observation: VisibilityDepthObservation) {
        check(ownership() == observation.ownership) { "stale V2 depth mapping admission" }
        onDepth(observation)
    }
}

private class LatestObservationLane<T : Any>(
    private val intervalNs: Long,
    private val scheduler: ScheduledExecutorService,
    private val nanoTime: () -> Long,
    private val payloadBytes: (T) -> Int,
    private val isCurrent: (T) -> Boolean,
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
            current = null
            latest = null
        }
        onResidentBytesChanged(residentBytes)
    }

    private fun scheduleLocked(delayNs: Long) {
        scheduler.schedule(::runOne, delayNs.coerceAtLeast(0), TimeUnit.NANOSECONDS)
    }

    private fun runOne() {
        val value = synchronized(lock) {
            if (closed) return
            current ?: return
        }
        try {
            if (isCurrent(value)) {
                deliver(value)
            } else {
                onStale()
            }
        } catch (_: RuntimeException) {
            // The exact cut is rechecked by the mapper. A cut changing between
            // lane qualification and mapper admission is a stale observation,
            // not a scheduler failure that may wedge the source forever.
            onStale()
        }
        val nextDelay = synchronized(lock) {
            lastDeliveryNs = nanoTime()
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
                if (!closed && scheduled) scheduleLocked(nextDelay)
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
