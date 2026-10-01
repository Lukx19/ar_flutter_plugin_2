package com.uhg0.ar_flutter_plugin_2.visibilitygrid

import android.os.Debug
import android.os.SystemClock
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
    private val workloadError = AtomicReference<String?>(null)

    init {
        channel.setMethodCallHandler(this)
    }

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
                    runtime.snapshotWireMap() + mapOf("mappingStalled" to gate.stalled())
                }
                "pressureSnapshot" -> dispatchSnapshot(result) {
                    VisibilityPressureReceipt.capture(
                        runtime.snapshot(),
                        pressureOwners(),
                    ).toWireMap()
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
                "startAllocationWorkload" -> {
                    check(allocationWorkload == null) { "allocation workload already running" }
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
                            val offer = workloadFeatureOffers.getAndIncrement()
                            val timestampNs = allocationTimestampNs()
                            if (source.emitFeature(timestampNs, (offer % 5).toInt())) {
                                workloadFeatureAccepted.incrementAndGet()
                            }
                            if (offer % 2L == 1L) {
                                workloadDepthOffers.incrementAndGet()
                                if (source.emitDepth(timestampNs + 1L, ((offer / 2L) % 5L).toInt())) {
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
                    result.success(mapOf(
                        "featureOffers" to workloadFeatureOffers.get(),
                        "depthOffers" to workloadDepthOffers.get(),
                        "featureAccepted" to workloadFeatureAccepted.get(),
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
        gate.release()
        snapshotExecutor?.shutdownNow()
        val executor = allocationWorkload
        if (executor != null) {
            allocationWorkloadTask?.cancel(false)
            executor.shutdownNow()
            check(executor.awaitTermination(3L, TimeUnit.SECONDS)) {
                "allocation workload did not terminate before view disposal"
            }
        }
        allocationWorkloadTask = null
        allocationWorkload = null
        channel.setMethodCallHandler(null)
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

    private fun MethodCall.requiredTimestamp(): Long =
        (argument<Number>("timestampNs")?.toLong() ?: error("timestampNs is required"))
            .also { require(it > 0) }
}
