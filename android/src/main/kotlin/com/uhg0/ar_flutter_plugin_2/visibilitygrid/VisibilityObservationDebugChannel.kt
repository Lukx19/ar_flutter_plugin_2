package com.uhg0.ar_flutter_plugin_2.visibilitygrid

import io.flutter.plugin.common.BinaryMessenger
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

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

/** Debug-only scalar/synthetic seam used by the exact M2 emulator selector. */
internal class VisibilityObservationDebugChannel(
    messenger: BinaryMessenger,
    viewId: Int,
    private val isDebuggable: Boolean,
    private val runtime: AndroidVisibilityGridRuntime,
    ownership: () -> VisibilityObservationOwnership?,
    private val gate: VisibilityObservationDebugGate,
) : MethodChannel.MethodCallHandler {
    private val channel = MethodChannel(messenger, "visibility_observation_v2_$viewId")
    private val source = SyntheticVisibilityObservationSource(runtime, ownership)

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
                "snapshot" -> result.success(
                    runtime.snapshotWireMap() + mapOf("mappingStalled" to gate.stalled()),
                )
                else -> result.notImplemented()
            }
        } catch (error: Exception) {
            result.error("VG_PROTOCOL_INVALID", error.message, null)
        }
    }

    fun dispose() {
        gate.release()
        channel.setMethodCallHandler(null)
    }

    private fun MethodCall.requiredTimestamp(): Long =
        (argument<Number>("timestampNs")?.toLong() ?: error("timestampNs is required"))
            .also { require(it > 0) }
}
