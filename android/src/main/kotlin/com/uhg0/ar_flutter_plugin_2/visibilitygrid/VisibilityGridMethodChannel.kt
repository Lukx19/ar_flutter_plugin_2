package com.uhg0.ar_flutter_plugin_2.visibilitygrid

import android.os.Handler
import android.os.Looper
import com.google.ar.core.Config
import com.google.ar.core.Frame
import com.google.ar.core.exceptions.NotYetAvailableException
import com.uhg0.ar_flutter_plugin_2.pointcloud.CoveragePointRenderSnapshot
import com.uhg0.ar_flutter_plugin_2.pointcloud.PointCloudNativeConfig
import com.uhg0.ar_flutter_plugin_2.pointcloud.VoxelRenderMode
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
    private val render: (CoveragePointRenderSnapshot?, PointCloudNativeConfig?) -> Unit,
) : MethodChannel.MethodCallHandler {
    private val channel = MethodChannel(messenger, "arpointcloud_$viewId")
    private val main = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor()
    private val lifecycleGuard = VisibilityGridLifecycleGuard()
    @Volatile private var grid: NativeVisibilityGrid? = null
    private var renderer: VisibilityGridRendererState? = null
    private var rendererConfig: PointCloudNativeConfig? = null
    @Volatile private var group: VisibilityGridGroupConfig? = null
    private var sessionGeneration = 0L
    private var visibilityRevision = 0L
    @Volatile private var disposed = false
    @Volatile private var paused = false
    @Volatile private var checkpointBarrierActive = false
    private var pendingCheckpointResult: MethodChannel.Result? = null
    private var pending: ObservationBundle? = null
    private var draining = false
    private var featureConfidenceMinimum = 0.30
    private var maxFeaturesPerObservation = 2_000
    @Volatile private var lastEmittedGeometryRevision = -1L
    @Volatile private var lastEmittedHealth: Map<String, String>? = null

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
                        check(
                            requireNotNull(renderer).applyGeometry(
                                revision = snapshot.geometryRevision,
                                reset = true,
                                upsertKeys = snapshot.upsertKeys.toLongArray(),
                                removalKeys = snapshot.removalKeys.toLongArray(),
                            ),
                        )
                        lastEmittedGeometryRevision = snapshot.geometryRevision
                        publishRenderer()
                        result.success(snapshot.toWireMap(currentHealth(snapshot.diagnostics)))
                    }
                }
                "applyVisibility" -> applyVisibility(call, result)
                "checkpointBarrier" -> checkpointBarrier(call, result)
                "releaseCheckpoint" -> releaseCheckpoint(call, result)
                "setPointsEnabled" -> setPointsEnabled(call, result)
                "setVoxelRenderMode" -> setVoxelRenderMode(call, result)
                "stopGrid" -> {
                    requireIdentity(call)
                    synchronized(this) { pending = null }
                    synchronized(this) {
                        lifecycleGuard.advance()
                        cancelPendingCheckpoint("Visibility group stopped during checkpoint")
                        group = null
                        checkpointBarrierActive = false
                        renderer?.stopGroup()
                    }
                    render(null, null)
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
        if (paused || checkpointBarrierActive) return
        val lifecycleToken = lifecycleGuard.token()
        val activeGroup = group ?: return
        val activeGrid = grid ?: return
        val activeRenderer = renderer ?: return
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
            if (
                disposed ||
                group !== activeGroup ||
                grid !== activeGrid ||
                renderer !== activeRenderer ||
                !lifecycleGuard.allows(lifecycleToken)
            ) {
                return
            }
            pending =
                ObservationBundle(
                    activeGrid,
                    activeRenderer,
                    activeGroup,
                    feature,
                    depth,
                    lifecycleToken,
                )
            if (draining) return
            draining = true
        }
        executor.execute(::drain)
    }

    fun dispose() {
        var checkpointResult: MethodChannel.Result? = null
        var oldRenderer: VisibilityGridRendererState? = null
        synchronized(this) {
            if (disposed) return
            disposed = true
            lifecycleGuard.dispose()
            checkpointResult = pendingCheckpointResult
            pendingCheckpointResult = null
            pending = null
            draining = false
            oldRenderer = renderer
            renderer = null
            rendererConfig = null
            grid = null
            group = null
            checkpointBarrierActive = false
        }
        checkpointResult?.error(
            "VG_NOT_INITIALIZED",
            "Visibility grid was disposed during checkpoint",
            null,
        )
        executor.shutdownNow()
        channel.setMethodCallHandler(null)
        oldRenderer?.dispose()
        render(null, null)
    }

    fun pause() {
        synchronized(this) {
            paused = true
            lifecycleGuard.pause()
            pending = null
            cancelPendingCheckpoint("Visibility grid paused during checkpoint")
        }
    }

    fun resume() {
        synchronized(this) {
            paused = false
            lifecycleGuard.resume()
        }
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
        val defaultColor = call.requiredColor("defaultColor")
        val pointSizePx =
            call.requiredDouble("pointSizePx", Double.MIN_VALUE, Double.MAX_VALUE).toFloat()
        val enabled = call.argument<Boolean>("enabled") ?: true
        val renderMode =
            VoxelRenderMode.fromWire(call.requiredString("voxelRenderMode"))
        val cubeSizeFactor = call.requiredDouble("cubeSizeFactor", 0.1, 1.0).toFloat()
        synchronized(this) {
            lifecycleGuard.advance()
            cancelPendingCheckpoint("Visibility grid reinitialized during checkpoint")
            pending = null
            checkpointBarrierActive = false
            grid = NativeVisibilityGrid(featureConfig, depthConfig)
            renderer?.dispose()
            renderer = null
            rendererConfig = null
            render(null, null)
            renderer =
                VisibilityGridRendererState(
                    capacity = featureConfig.stableVoxelCapacity,
                    defaultColor = defaultColor,
                ).also {
                    it.setEnabled(enabled)
                    it.setRenderMode(renderMode)
                }
            rendererConfig =
                PointCloudNativeConfig(
                    renderCapacity = featureConfig.stableVoxelCapacity,
                    defaultColor = defaultColor,
                    pointSizePx = pointSizePx,
                    enabled = enabled,
                    voxelRenderMode = renderMode,
                    cubeSizeFactor = cubeSizeFactor,
                )
            group = null
            visibilityRevision = 0
            lastEmittedGeometryRevision = -1
            lastEmittedHealth = capabilities.initialHealth()
            sessionGeneration++
        }
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
                restoredVisibilityRevision = call.requiredLong("restoredVisibilityRevision"),
                restoredKeys = call.argument<LongArray>("restoredKeys")
                    ?: throw IllegalArgumentException("restoredKeys must be Int64List"),
            )
        val snapshot =
            synchronized(this) {
                val active = requireGrid()
                lifecycleGuard.advance()
                cancelPendingCheckpoint("Visibility group changed during checkpoint")
                active.startGroup(next)
                group = next
                checkpointBarrierActive = false
                renderer?.startGroup(
                    config = next,
                    geometryRevision = next.restoredGeometryRevision,
                    visibilityRevision = next.restoredVisibilityRevision,
                    restoredKeys = next.restoredKeys,
                )
                visibilityRevision = next.restoredVisibilityRevision
                rendererConfig =
                    checkNotNull(rendererConfig).copy(
                        voxelSizeMeters = next.voxelSizeMeters.toFloat(),
                    )
                checkNotNull(
                    active.requestSnapshot(
                        VisibilityGridSnapshotRequest(
                            groupId = next.groupId,
                            groupGeneration = next.groupGeneration,
                            sessionGeneration = next.sessionGeneration,
                            receiverGeometryRevision = next.restoredGeometryRevision,
                        ),
                    ),
                ).also {
                    lastEmittedGeometryRevision = it.geometryRevision
                    lastEmittedHealth = currentHealth(it.diagnostics)
                    check(
                        renderer?.applyGeometry(
                            revision = it.geometryRevision,
                            reset = true,
                            upsertKeys = it.upsertKeys.toLongArray(),
                            removalKeys = it.removalKeys.toLongArray(),
                        ) == true,
                    )
                }
            }
        publishRenderer()
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
        require(
            requireNotNull(renderer).applyVisibility(
                namedGeometryRevision = geometryRevision,
                nextVisibilityRevision = nextVisibilityRevision,
                patchKeys = keys,
                patchColors = colors,
            ),
        )
        visibilityRevision = nextVisibilityRevision
        runCatching(::publishRenderer).onFailure(::emitRendererError)
        result.success(
            mapOf(
                "applied" to true,
                "geometryRevision" to geometryRevision,
                "visibilityRevision" to visibilityRevision,
            ),
        )
    }

    private fun checkpointBarrier(call: MethodCall, result: MethodChannel.Result) {
        requireIdentity(call)
        val request = call.snapshotRequest()
        val lifecycleToken = lifecycleGuard.token()
        val checkpointGrid = requireGrid()
        val checkpointRenderer = requireNotNull(renderer)
        synchronized(this) {
            check(pendingCheckpointResult == null) { "Checkpoint barrier is already pending" }
            checkpointBarrierActive = true
            pending = null
            pendingCheckpointResult = result
        }
        executor.execute {
            try {
                val snapshot =
                    requireNotNull(checkpointGrid.requestSnapshot(request)) {
                        "Snapshot identity is invalid"
                    }
                check(
                    checkpointRenderer.applyGeometry(
                        revision = snapshot.geometryRevision,
                        reset = true,
                        upsertKeys = snapshot.upsertKeys.toLongArray(),
                        removalKeys = snapshot.removalKeys.toLongArray(),
                    ),
                )
                lastEmittedGeometryRevision = snapshot.geometryRevision
                main.post {
                    if (claimCheckpointResult(result)) {
                        if (!lifecycleGuard.allows(lifecycleToken)) {
                            checkpointBarrierActive = false
                            result.error(
                                "VG_NOT_INITIALIZED",
                                "Visibility lifecycle changed during checkpoint",
                                null,
                            )
                            return@post
                        }
                        try {
                            publishRenderer()
                            result.success(
                                snapshot.toWireMap(currentHealth(snapshot.diagnostics)),
                            )
                        } catch (error: Exception) {
                            emitRendererError(error)
                            result.success(
                                snapshot.toWireMap(currentHealth(snapshot.diagnostics)),
                            )
                        }
                    }
                }
            } catch (error: Exception) {
                checkpointBarrierActive = false
                main.post {
                    if (claimCheckpointResult(result)) {
                        result.error("VG_INTERNAL", error.message, null)
                    }
                }
            }
        }
    }

    private fun releaseCheckpoint(call: MethodCall, result: MethodChannel.Result) {
        requireIdentity(call)
        val expectedGeometryRevision = call.requiredLong("geometryRevision")
        val expectedVisibilityRevision = call.requiredLong("visibilityRevision")
        val activeRenderer = requireNotNull(renderer)
        val barrierWasActive = checkpointBarrierActive
        val released =
            barrierWasActive &&
                activeRenderer.currentGeometryRevision == expectedGeometryRevision &&
                activeRenderer.currentVisibilityRevision == expectedVisibilityRevision
        if (barrierWasActive) checkpointBarrierActive = false
        result.success(mapOf("released" to released))
    }

    private fun drain() {
        var failed: ObservationBundle? = null
        try {
            while (true) {
                val next =
                    synchronized(this) {
                        pending.also { pending = null }
                    } ?: return
                failed = next
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
                if (!isCurrent(next)) continue
                val health = currentHealth(next.grid.snapshot().diagnostics)
                if (health != lastEmittedHealth) {
                    lastEmittedHealth = health
                    main.post {
                        if (
                            lifecycleGuard.allows(next.lifecycleToken) &&
                            group === next.group &&
                            renderer === next.renderer
                        ) {
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
                    if (
                        delta.geometryRevision != lastEmittedGeometryRevision &&
                        isCurrent(next)
                    ) {
                        val applied =
                            synchronized(this) {
                                if (!isCurrent(next)) {
                                    false
                                } else {
                                    check(
                                        next.renderer.applyGeometry(
                                            revision = delta.geometryRevision,
                                            reset = delta.reset,
                                            upsertKeys = delta.upsertKeys.toLongArray(),
                                            removalKeys = delta.removalKeys.toLongArray(),
                                        ),
                                    )
                                    lastEmittedGeometryRevision = delta.geometryRevision
                                    true
                                }
                            }
                        if (!applied) return@let
                        main.post {
                            val activeGroup = group
                            if (
                                lifecycleGuard.allows(next.lifecycleToken) &&
                                renderer === next.renderer &&
                                activeGroup === next.group &&
                                activeGroup.groupId == delta.groupId &&
                                activeGroup.groupGeneration == delta.groupGeneration &&
                                activeGroup.sessionGeneration == delta.sessionGeneration
                            ) {
                                runCatching(::publishRenderer)
                                    .onFailure(::emitRendererError)
                                channel.invokeMethod(
                                    "onGridDelta",
                                    delta.toWireMap(currentHealth(delta.diagnostics)),
                                )
                            }
                        }
                    }
                }
                failed = null
            }
        } catch (error: Exception) {
            val failedWork = failed
            if (failedWork != null) {
                main.post {
                    if (isCurrent(failedWork)) {
                        emitRendererError(error)
                    }
                }
            }
        } finally {
            val reschedule =
                synchronized(this) {
                    draining = false
                    if (!disposed && pending != null) {
                        draining = true
                        true
                    } else {
                        false
                    }
                }
            if (reschedule) {
                executor.execute(::drain)
            }
        }
    }

    private fun isCurrent(work: ObservationBundle): Boolean =
        synchronized(this) {
            lifecycleGuard.allows(work.lifecycleToken) &&
                grid === work.grid &&
                renderer === work.renderer &&
                group === work.group
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

    private fun setPointsEnabled(call: MethodCall, result: MethodChannel.Result) {
        val enabled =
            call.argument<Boolean>("enabled")
                ?: throw IllegalArgumentException("enabled is required")
        requireNotNull(renderer).setEnabled(enabled)
        rendererConfig = requireNotNull(rendererConfig).copy(enabled = enabled)
        publishRenderer()
        result.success(true)
    }

    private fun setVoxelRenderMode(call: MethodCall, result: MethodChannel.Result) {
        val mode = VoxelRenderMode.fromWire(call.requiredString("mode"))
        requireNotNull(renderer).setRenderMode(mode)
        rendererConfig = requireNotNull(rendererConfig).copy(voxelRenderMode = mode)
        publishRenderer()
        result.success(true)
    }

    private fun publishRenderer() {
        val state = renderer ?: return
        render(state.snapshot(), requireNotNull(rendererConfig))
    }

    private fun emitRendererError(error: Throwable) {
        if (disposed) return
        renderer?.markUploadFailed()
        channel.invokeMethod(
            "onError",
            mapOf(
                "code" to "VG_RENDERER_FAILED",
                "message" to (error.message ?: "Visibility-grid renderer failed"),
                "recoverable" to true,
                "fatalToFeature" to false,
                "fatalToDepth" to false,
                "fatalToRenderer" to true,
                "fatalToGrid" to false,
                "groupGeneration" to group?.groupGeneration,
                "sessionGeneration" to group?.sessionGeneration,
            ),
        )
    }

    private fun claimCheckpointResult(result: MethodChannel.Result): Boolean =
        synchronized(this) {
            if (pendingCheckpointResult !== result) {
                false
            } else {
                pendingCheckpointResult = null
                true
            }
        }

    private fun cancelPendingCheckpoint(message: String) {
        val checkpointResult =
            synchronized(this) {
                pendingCheckpointResult.also { pendingCheckpointResult = null }
            }
        checkpointBarrierActive = false
        checkpointResult?.error("VG_NOT_INITIALIZED", message, null)
    }

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
        val renderer: VisibilityGridRendererState,
        val group: VisibilityGridGroupConfig,
        val feature: FeatureAcquisition,
        val depth: DepthAcquisitionResult,
        val lifecycleToken: Long,
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

private fun MethodCall.requiredColor(name: String): Int {
    val value =
        argument<Number>(name)?.toLong()
            ?: throw IllegalArgumentException("$name is required")
    require(value in Int.MIN_VALUE.toLong()..0xFFFF_FFFFL)
    return value.toInt()
}

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
