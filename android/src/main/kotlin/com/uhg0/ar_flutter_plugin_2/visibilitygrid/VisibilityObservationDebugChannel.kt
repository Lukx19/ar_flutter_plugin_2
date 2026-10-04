package com.uhg0.ar_flutter_plugin_2.visibilitygrid

import android.os.Debug
import android.os.SystemClock
import com.uhg0.ar_flutter_plugin_2.performance.AllocationCounters
import com.uhg0.ar_flutter_plugin_2.performance.AllocationWorkReceipt
import io.flutter.plugin.common.BinaryMessenger
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

internal class VisibilityObservationDebugGate {
    private val lock = Any()
    private var release: CountDownLatch? = null
    private var entered: CountDownLatch? = null

    fun arm() = synchronized(lock) {
        check(release == null) { "observation stall is already armed" }
        release = CountDownLatch(1)
        entered = CountDownLatch(1)
    }

    fun awaitIfArmed() {
        val gate = synchronized(lock) {
            entered?.countDown()
            release
        } ?: return
        gate.await(5, TimeUnit.SECONDS)
    }

    fun release() = synchronized(lock) {
        release?.countDown()
        release = null
        entered = null
    }

    fun stalled(): Boolean = synchronized(lock) {
        release != null && entered?.count == 0L
    }
}

/** Debug-only scalar/synthetic seam used by the exact capture ingress emulator selector. */
internal class VisibilityObservationDebugChannel(
    messenger: BinaryMessenger,
    viewId: Int,
    private val isDebuggable: Boolean,
    private val runtime: AndroidVisibilityGridRuntime,
    ownership: () -> VisibilityObservationOwnership?,
    private val gate: VisibilityObservationDebugGate,
    private val pressureOwners: () -> VisibilityPressureOwnerScalars = {
        VisibilityPressureOwnerScalars()
    },
    private val allocationTimestampNs: () -> Long = {
        1_000_000_000_000_000_000L + SystemClock.elapsedRealtimeNanos()
    },
    private val physicalSamplePools: (() -> Map<String, SampleLeasePoolReceipt>)? = null,
) : MethodChannel.MethodCallHandler {
    private val channel = MethodChannel(messenger, "visibility_observation_v2_$viewId")
    private val source = SyntheticVisibilityObservationSource(runtime, ownership)
    // Diagnostic reads can wait behind a canonical mutation for seconds. Keep
    // that wait off the platform thread so probing a live camera cannot stall
    // its preview. This executor exists only in debuggable builds.
    private val snapshotExecutor = if (isDebuggable) Executors.newSingleThreadExecutor { task ->
        Thread(task, "visibility-debug-snapshot").apply { isDaemon = true }
    } else null
    private var allocationWorkload: ScheduledExecutorService? = null
    private var allocationWorkloadTask: ScheduledFuture<*>? = null
    private val workloadFeatureOffers = AtomicLong()
    private val workloadDepthOffers = AtomicLong()
    private val workloadFeatureAccepted = AtomicLong()
    private val workloadDepthAccepted = AtomicLong()
    private var workloadFeatureFixture = "legacyRotatingPoint"
    private var workloadFeatureSamplesPerOffer = 1
    private var workloadFeatureMaterialCommitsBefore = 0L
    private val workloadError = AtomicReference<String?>(null)
    private var allocationStage: Pair<String, AllocationCounters>? = null

    private fun allocationCounters() = AllocationCounters(
        SystemClock.elapsedRealtimeNanos(),
        Debug.getRuntimeStat("art.gc.bytes-allocated")!!.toLong(),
        Debug.getRuntimeStat("art.gc.bytes-freed")!!.toLong(),
        Debug.getRuntimeStat("art.gc.gc-count")!!.toLong(),
        Debug.getRuntimeStat("art.gc.gc-time")!!.toLong(),
    )

    init {
        channel.setMethodCallHandler(this)
    }

    internal fun samplePoolReceipts(): Map<String, SampleLeasePoolReceipt> = source.packedLeaseReceipts()

    override fun onMethodCall(call: MethodCall, result: MethodChannel.Result) {
        if (!isDebuggable) {
            result.error("VG_PROTOCOL_INVALID", "synthetic observation source is debug-only", null)
            return
        }
        try {
            when (call.method) {
                "setDepthCapability" -> {
                    val capability = when (call.argument<String>("capability")) {
                        "unsupported" -> VisibilityDepthCapability.UNSUPPORTED
                        "rawDepth" -> VisibilityDepthCapability.RAW_DEPTH
                        "automatic" -> VisibilityDepthCapability.AUTOMATIC
                        else -> error("unknown synthetic depth capability")
                    }
                    source.setDepthCapability(capability)
                    result.success(runtime.snapshotWireMap())
                }
                "emitFeature" -> result.success(
                    source.emitFeature(call.requiredTimestamp(), call.argument<Int>("marker") ?: 0),
                )
                "emitDepth" -> result.success(
                    source.emitDepth(call.requiredTimestamp(), call.argument<Int>("marker") ?: 0),
                )
                "armMappingStall" -> {
                    gate.arm()
                    result.success(true)
                }
                "markSourceStalled" -> {
                    when (call.argument<String>("source")) {
                        "feature" -> runtime.recordFeatureStalled()
                        "depth" -> runtime.recordDepthStalled()
                        else -> error("source must be feature or depth")
                    }
                    result.success(runtime.snapshotWireMap())
                }
                "releaseMappingStall" -> {
                    gate.release()
                    result.success(true)
                }
                "snapshot" -> dispatchSnapshot(result) {
                    runtime.snapshotWireMap() + mapOf(
                        "mappingStalled" to gate.stalled(),
                        "syntheticSamplePools" to samplePoolReceipts().mapValues { it.value.toWireMap() },
                        "arCoreSamplePools" to physicalSamplePools?.invoke()?.mapValues { it.value.toWireMap() },
                    )
                }
                "pressureSnapshot" -> dispatchSnapshot(result) {
                    VisibilityPressureReceipt.capture(
                        runtime.snapshot(),
                        pressureOwners(),
                    ).toWireMap()
                }
                "beginAllocationStage" -> {
                    check(allocationStage == null) { "allocation stage already running" }
                    val stage = requireNotNull(call.argument<String>("stage"))
                    require(stage.isNotBlank() && stage.length <= 64) { "invalid allocation stage" }
                    allocationStage = stage to allocationCounters()
                    result.success(true)
                }
                "endAllocationStage" -> {
                    val before = requireNotNull(allocationStage) { "allocation stage is absent" }
                    require(call.argument<String>("stage") == before.first) { "allocation stage mismatch" }
                    val after = allocationCounters()
                    fun units(name: String) = call.argument<Number>(name)?.toLong() ?: 0L
                    val receipt = AllocationWorkReceipt.between(before.first, before.second, after,
                        units("completedUnits"), units("inputBytes"), units("selectedSamples"),
                        units("ownedCapacityBytes"), Math.toIntExact(units("peakLeases")), units("growthEvents"))
                    allocationStage = null
                    val capacityObserved = listOf("ownedCapacityBytes", "peakLeases", "growthEvents")
                        .all { call.argument<Number>(it) != null }
                    result.success(receipt.toWireMap() + mapOf(
                        "capacityObserved" to capacityObserved,
                        "capacityScope" to if (capacityObserved) "callerReportedOwners" else "unobserved",
                        "ownedCapacityBytes" to if (capacityObserved) receipt.ownedCapacityBytes else null,
                        "peakLeases" to if (capacityObserved) receipt.peakLeases else null,
                        "growthEvents" to if (capacityObserved) receipt.growthEvents else null,
                    ))
                }
                "allocationSnapshot" -> result.success(mapOf(
                    "elapsedRealtimeMs" to SystemClock.elapsedRealtime(),
                    "artAllocatedBytes" to Debug.getRuntimeStat("art.gc.bytes-allocated")!!.toLong(),
                    "artFreedBytes" to Debug.getRuntimeStat("art.gc.bytes-freed")!!.toLong(),
                    "artGcCount" to Debug.getRuntimeStat("art.gc.gc-count")!!.toLong(),
                    "artGcTimeMs" to Debug.getRuntimeStat("art.gc.gc-time")!!.toLong(),
                    "artBlockingGcCount" to Debug.getRuntimeStat("art.gc.blocking-gc-count")!!.toLong(),
                    "nativeHeapAllocatedBytes" to Debug.getNativeHeapAllocatedSize(),
                ))
                "prepareAllocationWorkload" -> {
                    check(allocationWorkload == null) { "allocation workload already running" }
                    val config = call.allocationWorkloadConfig()
                    // One-time fixture construction is explicit and precedes measurement fences.
                    // It offers no observations and leaves actual first-photo work cold.
                    if (config.denseDepthGrids) source.prepareDenseDepthGrids(
                        campaignVariants = config.depthVariantOffset != null,
                    )
                    result.success(true)
                }
                "startAllocationWorkload" -> {
                    check(allocationWorkload == null) { "allocation workload already running" }
                    val config = call.allocationWorkloadConfig()
                    val denseDepthGrids = config.denseDepthGrids
                    val maximumFeatureOffers = config.maximumFeatureOffers
                    val depthVariantOffset = config.depthVariantOffset
                    if (denseDepthGrids) source.prepareDenseDepthGrids(campaignVariants = depthVariantOffset != null)
                    val depthEveryFeatureOffers = if (denseDepthGrids) 24L else 2L
                    workloadFeatureFixture = if (depthVariantOffset != null) SYNTHETIC_CAMPAIGN_FEATURE_FIXTURE else "legacyRotatingPoint"
                    workloadFeatureSamplesPerOffer = if (depthVariantOffset != null) SYNTHETIC_CAMPAIGN_FEATURE_SAMPLES else 1
                    workloadFeatureMaterialCommitsBefore = runtime.snapshot().admittedFeatureObservations
                    workloadFeatureOffers.set(0)
                    workloadDepthOffers.set(0)
                    workloadFeatureAccepted.set(0)
                    workloadDepthAccepted.set(0)
                    workloadError.set(null)
                    val executor = Executors.newSingleThreadScheduledExecutor { task ->
                        Thread(task, "visibility-allocation-workload").apply { isDaemon = true }
                    }
                    allocationWorkload = executor
                    allocationWorkloadTask = executor.scheduleAtFixedRate({
                        try {
                            if (maximumFeatureOffers != null && workloadFeatureOffers.get() >= maximumFeatureOffers) {
                                return@scheduleAtFixedRate
                            }
                            val offer = workloadFeatureOffers.getAndIncrement()
                            val timestampNs = allocationTimestampNs()
                            val featureAccepted = if (depthVariantOffset != null) {
                                source.emitCampaignFeatureFrame(timestampNs, (depthVariantOffset + offer / 24L).toInt())
                            } else source.emitFeature(timestampNs, (offer % 5).toInt())
                            if (featureAccepted) {
                                workloadFeatureAccepted.incrementAndGet()
                            }
                            if (offer % depthEveryFeatureOffers == depthEveryFeatureOffers - 1L) {
                                val depthOffer = workloadDepthOffers.getAndIncrement()
                                val marker = if (depthVariantOffset != null) (depthVariantOffset + depthOffer).toInt()
                                    else (depthOffer % 5L).toInt()
                                val accepted = if (denseDepthGrids) {
                                    source.emitDenseDepthGrid(timestampNs + 1L, marker, campaignVariants = depthVariantOffset != null)
                                } else source.emitDepth(timestampNs + 1L, marker)
                                if (accepted) {
                                    workloadDepthAccepted.incrementAndGet()
                                }
                            }
                        } catch (error: Exception) {
                            workloadError.compareAndSet(null, error.toString())
                            throw error
                        }
                    }, 0L, 125L, TimeUnit.MILLISECONDS)
                    result.success(true)
                }
                "stopAllocationWorkload" -> {
                    check(allocationWorkload != null) { "allocation workload is not running" }
                    allocationWorkloadTask?.cancel(false)
                    allocationWorkload?.shutdown()
                    check(allocationWorkload?.awaitTermination(3L, TimeUnit.SECONDS) == true) {
                        "allocation workload did not stop"
                    }
                    allocationWorkloadTask = null
                    allocationWorkload = null
                    source.awaitSyntheticIdle()
                    result.success(mapOf(
                        "featureOffers" to workloadFeatureOffers.get(),
                        "depthOffers" to workloadDepthOffers.get(),
                        "featureAccepted" to workloadFeatureAccepted.get(),
                        "featureFixture" to workloadFeatureFixture,
                        "featureSamplesPerOffer" to workloadFeatureSamplesPerOffer,
                        "featureAcceptedSamples" to Math.multiplyExact(workloadFeatureAccepted.get(), workloadFeatureSamplesPerOffer.toLong()),
                        "featureMaterialCommits" to (runtime.snapshot().admittedFeatureObservations - workloadFeatureMaterialCommitsBefore),
                        "featureMaterialCommitScope" to "canonicalCommitAtSourceDrainNotAckFence",
                        "depthAccepted" to workloadDepthAccepted.get(),
                        "error" to workloadError.get(),
                    ))
                }
                else -> result.notImplemented()
            }
        } catch (error: Exception) {
            result.error("VG_PROTOCOL_INVALID", error.message, null)
        }
    }

    fun dispose() {
        allocationStage = null
        gate.release()
        snapshotExecutor?.shutdownNow()
        try {
            val executor = allocationWorkload
            if (executor != null) {
                allocationWorkloadTask?.cancel(false)
                executor.shutdownNow()
                check(executor.awaitTermination(3L, TimeUnit.SECONDS)) {
                    "allocation workload did not terminate before view disposal"
                }
            }
        } finally {
            allocationWorkloadTask = null
            allocationWorkload = null
            // Closing fences acquisition. Active mapper borrows keep their
            // storage until the mapper releases them in its finally block.
            source.close()
            channel.setMethodCallHandler(null)
        }
    }

    private fun dispatchSnapshot(result: MethodChannel.Result, read: () -> Any) {
        requireNotNull(snapshotExecutor).execute {
            try {
                result.success(read())
            } catch (error: Exception) {
                result.error("VG_PROTOCOL_INVALID", error.message, null)
            }
        }
    }

    private data class AllocationWorkloadConfig(
        val denseDepthGrids: Boolean,
        val maximumFeatureOffers: Long?,
        val depthVariantOffset: Long?,
    )

    private fun MethodCall.allocationWorkloadConfig(): AllocationWorkloadConfig {
        val denseDepthGrids = argument<Boolean>("denseDepthGrids") ?: false
        val maximumFeatureOffers = argument<Number>("maximumFeatureOffers")?.toLong()
        val depthVariantOffset = argument<Number>("depthVariantOffset")?.toLong()
        require(maximumFeatureOffers == null || maximumFeatureOffers in 1L..10_000L) {
            "maximum feature offers is outside the bounded fixture range"
        }
        if (depthVariantOffset != null) {
            require(denseDepthGrids && maximumFeatureOffers != null &&
                maximumFeatureOffers % 24L == 0L && depthVariantOffset in 0L until SYNTHETIC_DENSE_CAMPAIGN_VARIANTS.toLong() &&
                depthVariantOffset + maximumFeatureOffers / 24L <= SYNTHETIC_DENSE_CAMPAIGN_VARIANTS) {
                "dense campaign variants require a finite in-range sequence"
            }
        }
        return AllocationWorkloadConfig(denseDepthGrids, maximumFeatureOffers, depthVariantOffset)
    }

    private fun MethodCall.requiredTimestamp(): Long =
        (argument<Number>("timestampNs")?.toLong() ?: error("timestampNs is required"))
            .also { require(it > 0) }
}
