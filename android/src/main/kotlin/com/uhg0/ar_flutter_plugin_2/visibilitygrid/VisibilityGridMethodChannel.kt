package com.uhg0.ar_flutter_plugin_2.visibilitygrid

import android.os.Handler
import android.os.Looper
import com.google.ar.core.Config
import com.google.ar.core.Frame
import com.google.ar.core.exceptions.NotYetAvailableException
import io.flutter.plugin.common.BinaryMessenger
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import java.util.concurrent.Executors

/** Per-view `visibility_grid_wire_v1` endpoint. Raw sensor arrays stay native. */
class VisibilityGridMethodChannel(
    messenger: BinaryMessenger,
    viewId: Int,
    private val isDebuggable: Boolean,
    private val runtimeCapabilities: () -> VisibilityGridRuntimeCapabilities,
) : MethodChannel.MethodCallHandler {
    private val channel = MethodChannel(messenger, "arpointcloud_$viewId")
    private val main = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor()
    private var grid: NativeVisibilityGrid? = null
    private var group: VisibilityGridGroupConfig? = null
    private var sessionGeneration = 0L
    private var visibilityRevision = 0L
    private var disposed = false
    private var paused = false
    private var pending: ObservationBundle? = null
    private var draining = false
    private var featureConfidenceMinimum = 0.30
    private var maxFeaturesPerObservation = 2_000
    private var lastEmittedGeometryRevision = -1L
    private var lastEmittedHealth: Map<String, String>? = null

    init {
        channel.setMethodCallHandler(this)
    }

    override fun onMethodCall(call: MethodCall, result: MethodChannel.Result) {
        if (disposed && call.method != "dispose") {
            result.error("VG_NOT_INITIALIZED", "Visibility grid is disposed", null)
            return
        }
        try {
            when (call.method) {
                "init" -> initialize(call, result)
                "startGrid" -> startGrid(call, result)
                "ackGeometry" -> result.success(
                    mapOf("accepted" to requireGrid().ackGeometry(call.geometryAck())),
                )
                "requestSnapshot" -> {
                    val snapshot = requireGrid().requestSnapshot(call.snapshotRequest())
                    if (snapshot == null) {
                        result.error("VG_PROTOCOL_INVALID", "Snapshot identity is invalid", null)
                    } else {
                        lastEmittedGeometryRevision = snapshot.geometryRevision
                        result.success(snapshot.toWireMap(currentHealth(snapshot.diagnostics)))
                    }
                }
                "applyVisibility" -> applyVisibility(call, result)
                "setPointsEnabled" -> result.success(true)
                "stopGrid" -> {
                    requireIdentity(call)
                    group = null
                    synchronized(this) { pending = null }
                    result.success(true)
                }
                "dispose" -> {
                    dispose()
                    result.success(true)
                }
                else -> result.notImplemented()
            }
        } catch (error: IllegalArgumentException) {
            result.error("VG_PROTOCOL_INVALID", error.message, null)
        } catch (error: IllegalStateException) {
            result.error("VG_NOT_INITIALIZED", error.message, null)
        } catch (error: Exception) {
            result.error("VG_INTERNAL", error.message, null)
        }
    }

    fun onFrame(frame: Frame) {
        if (paused) return
        val activeGroup = group ?: return
        val activeGrid = grid ?: return
        val feature =
            try {
                FeatureAcquisition.Observation(copyFeatures(frame, activeGroup))
            } catch (_: NotYetAvailableException) {
                FeatureAcquisition.TransientUnavailable
            } catch (error: RuntimeException) {
                FeatureAcquisition.Failure(error.message ?: "feature acquisition failed")
            }
        val depth =
            if (runtimeCapabilities().depthMode != Config.DepthMode.DISABLED) {
                try {
                    ArCoreRawDepthSource().acquire(
                        frame,
                        activeGroup.groupGeneration,
                        activeGroup.sessionGeneration,
                    )
                } catch (error: RuntimeException) {
                    DepthAcquisitionResult.Failure(error.message ?: "depth acquisition failed")
                }
            } else {
                DepthAcquisitionResult.TransientUnavailable
            }
        synchronized(this) {
            if (disposed || group !== activeGroup || grid !== activeGrid) return
            pending = ObservationBundle(activeGrid, feature, depth)
            if (draining) return
            draining = true
        }
        executor.execute(::drain)
    }

    fun dispose() {
        if (disposed) return
        disposed = true
        synchronized(this) {
            pending = null
            draining = false
        }
        executor.shutdownNow()
        channel.setMethodCallHandler(null)
        grid = null
        group = null
    }

    fun pause() {
        paused = true
        synchronized(this) { pending = null }
    }

    fun resume() {
        paused = false
    }

    private fun initialize(call: MethodCall, result: MethodChannel.Result) {
        require(call.argument<String>("version") == VISIBILITY_GRID_WIRE_VERSION)
        val synthetic = call.argument<Boolean>("syntheticSource") ?: false
        require(!synthetic || isDebuggable) { "Synthetic visibility grid is debug-only" }
        val featureConfig =
            VisibilityGridFeatureConfig(
                stableVoxelCapacity = call.requiredInt("renderCapacity", 1, 100_000),
                featureTrackCapacity = call.requiredInt("featureTrackCapacity", 1, 200_000),
                maxFeaturesPerObservation =
                    call.requiredInt("maxFeaturesPerObservation", 1, 2_000),
                publishIntervalMs = call.requiredInt("publishIntervalMs", 500, Int.MAX_VALUE),
                minimumConfidence = call.requiredDouble("featureConfidenceMinimum", 0.0, 1.0),
            )
        featureConfidenceMinimum = featureConfig.minimumConfidence
        maxFeaturesPerObservation = featureConfig.maxFeaturesPerObservation
        val depthConfig =
            VisibilityGridDepthConfig(
                confidenceMinimum = call.requiredInt("depthConfidenceMinimum", 0, 255),
                maxAcceptedPixelsPerObservation =
                    call.requiredInt("maxDepthPixelsPerObservation", 1, 4_096),
                maxRayVisitsPerObservation =
                    call.requiredInt("maxRayVisitsPerObservation", 1, 65_536),
            )
        val capabilities = runtimeCapabilities()
        grid = NativeVisibilityGrid(featureConfig, depthConfig)
        group = null
        visibilityRevision = 0
        lastEmittedGeometryRevision = -1
        lastEmittedHealth = capabilities.initialHealth()
        sessionGeneration++
        result.success(
            mapOf(
                "version" to VISIBILITY_GRID_WIRE_VERSION,
                "sessionGeneration" to sessionGeneration,
                "rendererReady" to capabilities.rendererReady,
                "featureReady" to capabilities.featureReady,
                "depthCapability" to capabilities.depthCapability,
                "depthConfigured" to
                    (capabilities.depthMode != Config.DepthMode.DISABLED),
                "depthActiveMode" to capabilities.depthActiveMode,
                "renderCapacity" to featureConfig.stableVoxelCapacity,
                "featureTrackCapacity" to featureConfig.featureTrackCapacity,
                "health" to capabilities.initialHealth(),
            ),
        )
    }

    private fun startGrid(call: MethodCall, result: MethodChannel.Result) {
        val next =
            VisibilityGridGroupConfig(
                groupId = call.requiredString("groupId"),
                groupGeneration = call.requiredLong("groupGeneration"),
                sessionGeneration = sessionGeneration,
                voxelSizeMeters = call.requiredDouble("voxelSizeMeters", Double.MIN_VALUE, Double.MAX_VALUE),
                capacity = call.requiredInt("capacity", 1, 100_000),
                groupFromWorldGl = call.requiredDoubleArray("groupFromWorldGl"),
                worldFromGroupGl = call.requiredDoubleArray("worldFromGroupGl"),
                restoredGeometryRevision = call.requiredLong("restoredGeometryRevision"),
                restoredKeys = call.argument<LongArray>("restoredKeys")
                    ?: throw IllegalArgumentException("restoredKeys must be Int64List"),
            )
        val active = requireGrid()
        active.startGroup(next)
        group = next
        val snapshot =
            checkNotNull(
                active.requestSnapshot(
                    VisibilityGridSnapshotRequest(
                        groupId = next.groupId,
                        groupGeneration = next.groupGeneration,
                        sessionGeneration = next.sessionGeneration,
                        receiverGeometryRevision = next.restoredGeometryRevision,
                    ),
                ),
            )
        lastEmittedGeometryRevision = snapshot.geometryRevision
        lastEmittedHealth = currentHealth(snapshot.diagnostics)
        result.success(snapshot.toWireMap(currentHealth(snapshot.diagnostics)))
    }

    private fun applyVisibility(call: MethodCall, result: MethodChannel.Result) {
        requireIdentity(call)
        val active = requireGrid().snapshot()
        val geometryRevision = call.requiredLong("geometryRevision")
        val nextVisibilityRevision = call.requiredLong("visibilityRevision")
        val keys = call.argument<LongArray>("keys")
            ?: throw IllegalArgumentException("keys must be Int64List")
        val colors = call.argument<IntArray>("colors")
            ?: throw IllegalArgumentException("colors must be Int32List")
        require(keys.size == colors.size && keys.distinct().size == keys.size)
        require(geometryRevision == active.geometryRevision)
        require(nextVisibilityRevision > visibilityRevision)
        require(keys.all(active.stableKeys.toHashSet()::contains))
        visibilityRevision = nextVisibilityRevision
        result.success(
            mapOf(
                "applied" to true,
                "geometryRevision" to geometryRevision,
                "visibilityRevision" to visibilityRevision,
            ),
        )
    }

    private fun drain() {
        while (true) {
            val next =
                synchronized(this) {
                    val value = pending
                    pending = null
                    if (value == null || disposed) {
                        draining = false
                        return
                    }
                    value
                }
            when (val feature = next.feature) {
                is FeatureAcquisition.Observation ->
                    next.grid.consumeNext(FeatureObservationSource { feature.value })
                FeatureAcquisition.TransientUnavailable ->
                    next.grid.consumeNext(FeatureObservationSource { null })
                is FeatureAcquisition.Failure ->
                    next.grid.consumeNext(
                        FeatureObservationSource {
                            throw IllegalStateException(feature.reason)
                        },
                    )
            }
            next.grid.consumeDepth(next.depth)
            val health = currentHealth(next.grid.snapshot().diagnostics)
            if (health != lastEmittedHealth) {
                lastEmittedHealth = health
                main.post {
                    if (!disposed) {
                        channel.invokeMethod(
                            "onGridHealth",
                            mapOf(
                                "version" to VISIBILITY_GRID_WIRE_VERSION,
                                "sourceHealth" to health,
                            ),
                        )
                    }
                }
            }
            next.grid.takeGeometryDelta()?.let { delta ->
                if (delta.geometryRevision != lastEmittedGeometryRevision) {
                    lastEmittedGeometryRevision = delta.geometryRevision
                    main.post {
                        if (!disposed) {
                            channel.invokeMethod(
                                "onGridDelta",
                                delta.toWireMap(currentHealth(delta.diagnostics)),
                            )
                        }
                    }
                }
            }
        }
    }

    private fun copyFeatures(
        frame: Frame,
        activeGroup: VisibilityGridGroupConfig,
    ): FeatureObservation {
        val raw = mutableListOf<FeatureSample>()
        var capacityRejected = 0
        frame.acquirePointCloud().use { cloud ->
            val ids = cloud.ids
            val points = cloud.points
            val count = minOf(ids.remaining(), points.remaining() / 4)
            repeat(count) {
                val id = ids.get()
                val sample =
                    FeatureSample(
                        id,
                        points.get().toDouble(),
                        points.get().toDouble(),
                        points.get().toDouble(),
                        points.get().toDouble(),
                    )
                if (raw.size < maxFeaturesPerObservation) {
                    raw += sample
                } else {
                    capacityRejected++
                }
            }
        }
        val sanitized =
            sanitizeFeatureSamples(
                raw,
                featureConfidenceMinimum,
                maxFeaturesPerObservation,
            )
        return FeatureObservation(
            timestampNs = frame.timestamp,
            groupGeneration = activeGroup.groupGeneration,
            sessionGeneration = activeGroup.sessionGeneration,
            samples = sanitized.samples,
            sanitized = true,
            sourceRejectedSamples =
                sanitized.rejectedSamples + capacityRejected,
        )
    }

    private fun requireGrid(): NativeVisibilityGrid =
        grid ?: error("Visibility grid is not initialized")

    private fun currentHealth(
        diagnostics: VisibilityGridDiagnostics,
    ): Map<String, String> {
        val capabilities = runtimeCapabilities()
        val health =
            diagnostics
                .toHealthWireMap(
                    if (capabilities.rendererReady) "healthy" else "transientUnavailable",
                ).toMutableMap()
        if (!capabilities.featureReady) health["feature"] = "failed"
        if (capabilities.depthMode == Config.DepthMode.DISABLED) {
            health["depth"] = "unsupported"
        }
        health["totalGrid"] =
            if (
                health["feature"] == "failed" &&
                (health["depth"] == "failed" || health["depth"] == "unsupported")
            ) {
                "failed"
            } else if (health["depth"] == "failed") {
                "featureOnly"
            } else {
                "healthy"
            }
        return health
    }

    private fun requireIdentity(call: MethodCall) {
        val active = group ?: error("Visibility group is not started")
        require(call.argument<String>("version") == VISIBILITY_GRID_WIRE_VERSION)
        require(call.requiredString("groupId") == active.groupId)
        require(call.requiredLong("groupGeneration") == active.groupGeneration)
        require(call.requiredLong("sessionGeneration") == active.sessionGeneration)
    }

    private fun MethodCall.geometryAck(): VisibilityGridGeometryAck {
        requireIdentity(this)
        return VisibilityGridGeometryAck(
            groupId = requiredString("groupId"),
            groupGeneration = requiredLong("groupGeneration"),
            sessionGeneration = requiredLong("sessionGeneration"),
            acceptedGeometryRevision = requiredLong("acceptedGeometryRevision"),
        )
    }

    private fun MethodCall.snapshotRequest(): VisibilityGridSnapshotRequest {
        requireIdentity(this)
        return VisibilityGridSnapshotRequest(
            groupId = requiredString("groupId"),
            groupGeneration = requiredLong("groupGeneration"),
            sessionGeneration = requiredLong("sessionGeneration"),
            receiverGeometryRevision = requiredLong("receiverGeometryRevision"),
        )
    }

    private data class ObservationBundle(
        val grid: NativeVisibilityGrid,
        val feature: FeatureAcquisition,
        val depth: DepthAcquisitionResult,
    )

    private sealed interface FeatureAcquisition {
        data class Observation(val value: FeatureObservation) : FeatureAcquisition

        data object TransientUnavailable : FeatureAcquisition

        data class Failure(val reason: String) : FeatureAcquisition
    }
}

private fun MethodCall.requiredString(name: String): String =
    argument<String>(name)?.takeIf(String::isNotBlank)
        ?: throw IllegalArgumentException("$name is required")

private fun MethodCall.requiredLong(name: String): Long =
    (argument<Number>(name)?.toLong() ?: throw IllegalArgumentException("$name is required"))
        .also { require(it >= 0) }

private fun MethodCall.requiredInt(name: String, minimum: Int, maximum: Int): Int =
    (argument<Number>(name)?.toInt() ?: throw IllegalArgumentException("$name is required"))
        .also { require(it in minimum..maximum) }

private fun MethodCall.requiredDouble(name: String, minimum: Double, maximum: Double): Double =
    (argument<Number>(name)?.toDouble() ?: throw IllegalArgumentException("$name is required"))
        .also { require(it.isFinite() && it in minimum..maximum) }

private fun MethodCall.requiredDoubleArray(name: String): DoubleArray =
    (argument<DoubleArray>(name) ?: throw IllegalArgumentException("$name must be Float64List"))
        .also { require(it.size == 16 && it.all(Double::isFinite)) }

data class VisibilityGridRuntimeCapabilities(
    val featureReady: Boolean,
    val depthMode: Config.DepthMode,
    val rendererReady: Boolean,
) {
    val depthCapability: String
        get() =
            when (depthMode) {
                Config.DepthMode.RAW_DEPTH_ONLY -> "rawDepthOnly"
                Config.DepthMode.AUTOMATIC -> "automatic"
                Config.DepthMode.DISABLED -> "unsupported"
            }

    val depthActiveMode: String
        get() =
            when (depthMode) {
                Config.DepthMode.RAW_DEPTH_ONLY -> "rawDepthOnly"
                Config.DepthMode.AUTOMATIC -> "automatic"
                Config.DepthMode.DISABLED -> "featureOnly"
            }

    fun initialHealth(): Map<String, String> =
        mapOf(
            "feature" to if (featureReady) "configured" else "failed",
            "depth" to if (depthMode == Config.DepthMode.DISABLED) "unsupported" else "configured",
            "renderer" to if (rendererReady) "healthy" else "transientUnavailable",
            "totalGrid" to
                if (featureReady || depthMode != Config.DepthMode.DISABLED) {
                    "healthy"
                } else {
                    "failed"
                },
        )
}
