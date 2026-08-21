package com.uhg0.ar_flutter_plugin_2.visibilitygrid

import android.os.Handler
import android.os.Looper
import com.google.ar.core.Config
import com.google.ar.core.Frame
import com.google.ar.core.TrackingState
import com.google.ar.core.exceptions.NotYetAvailableException
import com.uhg0.ar_flutter_plugin_2.m0.M0aControlCodec
import com.uhg0.ar_flutter_plugin_2.m0.M0aControlLifecycle
import com.uhg0.ar_flutter_plugin_2.m0.M0aControlOperation
import com.uhg0.ar_flutter_plugin_2.pointcloud.CoveragePointRenderSnapshot
import com.uhg0.ar_flutter_plugin_2.pointcloud.PointCloudNativeConfig
import com.uhg0.ar_flutter_plugin_2.pointcloud.VoxelRenderMode
import io.flutter.plugin.common.BinaryMessenger
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import java.util.concurrent.Executors
import java.util.concurrent.Executor
import java.util.concurrent.ExecutorService

/** Per-view `visibility_grid_wire_v1` endpoint. Raw sensor arrays stay native. */
class VisibilityGridMethodChannel(
    messenger: BinaryMessenger,
    viewId: Int,
    private val isDebuggable: Boolean,
    private val runtimeCapabilities: () -> VisibilityGridRuntimeCapabilities,
    private val render: (CoveragePointRenderSnapshot?, PointCloudNativeConfig?) -> Unit,
    private val renderRawPoints: (CoveragePointRenderSnapshot?) -> Unit = {},
    private val m0aControlLifecycle: M0aControlLifecycle = M0aControlLifecycle(),
    sharedExecutor: Executor? = null,
) : MethodChannel.MethodCallHandler {
    private val channel = MethodChannel(messenger, "arpointcloud_$viewId")
    private val main = Handler(Looper.getMainLooper())
    private val executor: ExecutorService =
        (sharedExecutor as? ExecutorService) ?: Executors.newSingleThreadExecutor()
    private val sensorDrainDispatcher =
        FairExecutorDrainDispatcher(
            executor = executor,
            drainOne = ::drainOneSensorBatch,
        )
    private val lifecycleGuard = VisibilityGridLifecycleGuard()
    @Volatile private var grid: NativeVisibilityGrid? = null
    private var renderer: VisibilityGridRendererState? = null
    @Volatile private var rendererConfig: PointCloudNativeConfig? = null
    @Volatile private var group: VisibilityGridGroupConfig? = null
    private var sessionGeneration = 0L
    private var visibilityRevision = 0L
    @Volatile private var disposed = false
    @Volatile private var paused = false
    @Volatile private var checkpointBarrierActive = false
    private var pendingCheckpointResult: MethodChannel.Result? = null
    // Compose owns actual mesh disposal and mounting. Keep its lifecycle
    // distinct from a requested config mutation so callers can fence a mode
    // replacement without guessing a frame delay.
    private var rendererMounted = false
    private var pendingRendererMountResult: MethodChannel.Result? = null
    private var pendingRendererUnmountResult: MethodChannel.Result? = null
    private val sensorHandoff = LatestSensorHandoff<FeatureWork, DepthWork>()
    private var featureConfidenceMinimum = 0.30
    private var maxFeaturesPerObservation = 2_000
    // Debug synthetic input is supplied through the protocol; it must not
    // concurrently acquire Image-backed ARCore sensor data on the render
    // callback. That would race native Session teardown.
    @Volatile private var syntheticSource = false
    @Volatile private var lastEmittedGeometryRevision = -1L
    @Volatile private var lastEmittedHealth: Map<String, String>? = null
    private var coalescedFeatureObservations = 0L
    private var coalescedDepthObservations = 0L
    private val callbackCopySamples = VisibilityGridChannelLatencySamples()
    private val frameCadence = VisibilityGridFrameCadence()
    private val rawPointRenderHandoff =
        LatestRawPointRenderHandoff<CoveragePointRenderSnapshot>()
    private var rawPointSnapshotPublished = false
    private var healthHeartbeatGeneration = 0L

    init {
        channel.setMethodCallHandler(this)
    }

    override fun onMethodCall(call: MethodCall, result: MethodChannel.Result) {
        if (disposed && call.method != "dispose") {
            result.error("VG_NOT_INITIALIZED", "Visibility grid is disposed", null)
            return
        }
        if (
            isDebuggable &&
            call.method in setOf("start", "beginCheckpoint", "releaseCheckpoint", "stop") &&
            (call.method != "releaseCheckpoint" || call.arguments is ByteArray)
        ) {
            handleM0aControl(call, result)
            return
        }
        try {
            when (call.method) {
                "init" -> initialize(call, result)
                "startGrid" -> startGrid(call, result)
                "startGridSummary" -> startGrid(call, result, summaryOnly = true)
                "pullGridDelta" -> pullGridDelta(call, result)
                "ackGeometry" -> result.success(
                    mapOf("accepted" to requireGrid().ackGeometry(call.geometryAck())),
                )
                "requestSnapshot" -> requestSnapshot(call, result)
                "requestSnapshotSummary" -> requestSnapshot(call, result, summaryOnly = true)
                "getHealth" -> {
                    result.success(
                        healthWireMap()
                            ?: throw IllegalStateException("Visibility group is not started"),
                    )
                }
                "applyVisibility" -> applyVisibility(call, result)
                "checkpointBarrier" -> checkpointBarrier(call, result)
                "releaseCheckpoint" -> releaseCheckpoint(call, result)
                "setPointsEnabled" -> setPointsEnabled(call, result)
                "setVoxelRenderMode" -> setVoxelRenderMode(call, result)
                "awaitRendererMounted" -> awaitRendererMounted(result)
                "awaitRendererUnmounted" -> awaitRendererUnmounted(result)
                "stopGrid" -> {
                    requireIdentity(call)
                    synchronized(this) { sensorHandoff.clear() }
                    synchronized(this) {
                        lifecycleGuard.advance()
                        healthHeartbeatGeneration++
                        cancelPendingCheckpoint("Visibility group stopped during checkpoint")
                        group = null
                        checkpointBarrierActive = false
                        renderer?.stopGroup()
                    }
                    render(null, null)
                    clearRawPoints()
                    result.success(true)
                }
                "dispose" -> {
                    dispose()
                    result.success(true)
                }
                "disposeM0aBinding" -> {
                    m0aControlLifecycle.abandon()
                    result.success(true)
                }
                else -> result.notImplemented()
            }
        } catch (error: VisibilityGridMethodException) {
            result.error(error.code, error.message, null)
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
        if (
            !shouldAcquireVisibilitySensorWork(
                trackingState = frame.camera.trackingState,
                syntheticSource = syntheticSource,
            )
        ) {
            rawPointRenderHandoff.clear()
            main.post(::clearRawPoints)
            frameCadence.reset()
            return
        }
        val lifecycleToken = lifecycleGuard.token()
        val activeGroup = group ?: return
        val activeGrid = grid ?: return
        val activeRenderer = renderer ?: return
        val capabilities = runtimeCapabilities()
        val framePlan =
            frameCadence.plan(
                timestampNs = frame.timestamp,
                depthEnabled = capabilities.depthMode != Config.DepthMode.DISABLED,
            )
        if (!framePlan.acquireFeature && !framePlan.acquireDepth) return
        val callbackStartedNs = System.nanoTime()
        val feature =
            if (framePlan.acquireFeature) {
                try {
                    FeatureAcquisition.Observation(copyFeatures(frame, activeGroup))
                } catch (_: NotYetAvailableException) {
                    FeatureAcquisition.TransientUnavailable
                } catch (error: RuntimeException) {
                    FeatureAcquisition.Failure(error.message ?: "feature acquisition failed")
                }
            } else {
                null
            }
        val rawConfig = rendererConfig
        if (
            feature is FeatureAcquisition.Observation &&
            rawConfig?.voxelRenderMode == VoxelRenderMode.POINTS &&
            rawConfig.enabled
        ) {
            scheduleRawPoints(
                feature.value.toRawPointRenderSnapshot(
                    capacity = rawConfig.renderCapacity,
                    color = rawConfig.defaultColor,
                    enabled = true,
                ),
            )
        }
        val depth =
            if (framePlan.acquireDepth) {
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
                null
            }
        val admitted =
            admitSensorWork(
                lock = this,
                isBlocked = {
                    disposed ||
                        paused ||
                        checkpointBarrierActive ||
                        group !== activeGroup ||
                        grid !== activeGrid ||
                        renderer !== activeRenderer ||
                        !lifecycleGuard.allows(lifecycleToken)
                },
                offer = {
                    callbackCopySamples.record(System.nanoTime() - callbackStartedNs)
                    val context =
                        ObservationContext(
                            activeGrid,
                            activeRenderer,
                            activeGroup,
                            lifecycleToken,
                        )
                    if (feature != null) {
                        if (
                            sensorHandoff.offerFeature(
                                FeatureWork(context, frame.timestamp, feature),
                            )
                        ) {
                            coalescedFeatureObservations++
                        }
                    }
                    if (depth != null) {
                        if (
                            sensorHandoff.offerDepth(
                                DepthWork(context, frame.timestamp, depth),
                            )
                        ) {
                            coalescedDepthObservations++
                        }
                    }
                },
            )
        if (!admitted) return
        sensorDrainDispatcher.request()
    }

    fun dispose() {
        var checkpointResult: MethodChannel.Result? = null
        var rendererMountResult: MethodChannel.Result? = null
        var rendererUnmountResult: MethodChannel.Result? = null
        var oldRenderer: VisibilityGridRendererState? = null
        synchronized(this) {
            if (disposed) return
            disposed = true
            lifecycleGuard.dispose()
            healthHeartbeatGeneration++
            checkpointResult = pendingCheckpointResult
            pendingCheckpointResult = null
            rendererMountResult = pendingRendererMountResult
            pendingRendererMountResult = null
            rendererUnmountResult = pendingRendererUnmountResult
            pendingRendererUnmountResult = null
            rendererMounted = false
            sensorHandoff.clear()
            frameCadence.reset()
            oldRenderer = renderer
            renderer = null
            rendererConfig = null
            syntheticSource = false
            grid = null
            group = null
            checkpointBarrierActive = false
        }
        checkpointResult?.error(
            "VG_NOT_INITIALIZED",
            "Visibility grid was disposed during checkpoint",
            null,
        )
        rendererMountResult?.error(
            "VG_NOT_INITIALIZED",
            "Visibility grid was disposed before renderer mount",
            null,
        )
        rendererUnmountResult?.error(
            "VG_NOT_INITIALIZED",
            "Visibility grid was disposed before renderer unmount",
            null,
        )
        executor.shutdownNow()
        channel.setMethodCallHandler(null)
        oldRenderer?.dispose()
        render(null, null)
        clearRawPoints()
    }

    private fun handleM0aControl(call: MethodCall, result: MethodChannel.Result) {
        val operation = when (call.method) {
            "start" -> M0aControlOperation.START
            "beginCheckpoint" -> M0aControlOperation.BEGIN_CHECKPOINT
            "releaseCheckpoint" -> M0aControlOperation.RELEASE_CHECKPOINT
            "stop" -> M0aControlOperation.STOP
            else -> null
        }
        val bytes = call.arguments as? ByteArray
        if (operation == null || bytes == null) {
            result.error("VG_PROTOCOL_INVALID", "M0a control requires one Uint8List", null)
            return
        }
        executor.execute {
            try {
                val response: ByteArray? = synchronized(this) {
                    if (disposed) return@synchronized null
                    val request = M0aControlCodec.decodeRequest(bytes)
                    require(request.operation == operation) { "Control method and operation differ" }
                    val encoded = m0aControlLifecycle.handle(request, bytes)
                    encoded
                }
                if (response == null) {
                    result.error("VG_NOT_INITIALIZED", "Visibility grid is disposed", null)
                } else {
                    result.success(response)
                }
            } catch (error: Exception) {
                result.error("VG_PROTOCOL_INVALID", error.message, null)
            }
        }
    }

    fun pause() {
        synchronized(this) {
            paused = true
            lifecycleGuard.pause()
            healthHeartbeatGeneration++
            sensorHandoff.clear()
            frameCadence.reset()
            cancelPendingCheckpoint("Visibility grid paused during checkpoint")
        }
        clearRawPoints()
    }

    fun resume() {
        synchronized(this) {
            paused = false
            lifecycleGuard.resume()
            frameCadence.reset()
        }
        restartHealthHeartbeat()
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
            healthHeartbeatGeneration++
            syntheticSource = synthetic
            cancelPendingCheckpoint("Visibility grid reinitialized during checkpoint")
            sensorHandoff.clear()
            frameCadence.reset()
            checkpointBarrierActive = false
            grid = NativeVisibilityGrid(featureConfig, depthConfig)
            renderer?.dispose()
            renderer = null
            rendererConfig = null
            render(null, null)
            clearRawPoints()
                renderer =
                    VisibilityGridRendererState(
                    capacity = VisibilityGridRendererState.presentationCapacity(renderMode),
                    defaultColor = defaultColor,
                ).also {
                    it.setEnabled(enabled)
                    it.setRenderMode(renderMode)
                }
            rendererConfig =
                PointCloudNativeConfig(
                    renderCapacity = VisibilityGridRendererState.presentationCapacity(renderMode),
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
            coalescedFeatureObservations = 0
            coalescedDepthObservations = 0
            callbackCopySamples.clear()
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
                "diagnostics" to
                    VisibilityGridDiagnostics(
                        candidateTracks = 0,
                        stableTracks = 0,
                        stableVoxels = 0,
                        featureTrackCapacity = featureConfig.featureTrackCapacity,
                        stableVoxelCapacity = featureConfig.stableVoxelCapacity,
                        acceptedSamples = 0,
                        rejectedSamples = 0,
                        capacityRejectedCandidates = 0,
                        featureHealth = "configured",
                        featureTransientUnavailableCount = 0,
                        featureFailureCount = 0,
                        lastFeatureFusionNs = 0,
                        maxFeatureFusionNs = 0,
                        estimatedStateBytes = 8_192,
                        depthHealth =
                            if (capabilities.depthCapability == "unsupported") {
                                "unsupported"
                            } else {
                                "configured"
                            },
                        rendererFreeRows = featureConfig.stableVoxelCapacity,
                    ).toWireMap(),
            ),
        )
    }

    private fun startGrid(
        call: MethodCall,
        result: MethodChannel.Result,
        summaryOnly: Boolean = false,
    ) {
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
                coalescedFeatureObservations = 0
                coalescedDepthObservations = 0
                callbackCopySamples.clear()
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
                            selectedKeysForResetOrReplacement = {
                                active.selectedRenderKeys(
                                    checkNotNull(renderer).capacity,
                                )
                            },
                        ) == true,
                    )
                }
            }
        publishRenderer()
        restartHealthHeartbeat()
        result.success(
            if (summaryOnly) deltaSummaryWireMap(snapshot) else deltaWireMap(snapshot),
        )
    }

    /**
     * Background-worker-only semantic hand-off. The ordinary callback names
     * this retained revision without moving stable keys through the root
     * isolate. The delta stays retained until the existing ack accepts it.
     */
    private fun pullGridDelta(call: MethodCall, result: MethodChannel.Result) {
        requireIdentity(call)
        val delta = requireGrid().inFlightGeometryDelta()
            ?: throw VisibilityGridMethodException(
                "VG_NO_PENDING_DELTA",
                "No retained visibility-grid delta is available",
            )
        // The background coverage worker owns this hand-off. Apply the
        // retained geometry before returning it so neither the root isolate
        // nor a later visibility patch needs to rebuild semantic rows.
        check(
            requireNotNull(renderer).applyGeometry(
                revision = delta.geometryRevision,
                reset = delta.reset,
                upsertKeys = delta.upsertKeys.toLongArray(),
                removalKeys = delta.removalKeys.toLongArray(),
                selectedKeysForResetOrReplacement = {
                    requireGrid().selectedRenderKeys(
                        requireNotNull(renderer).capacity,
                    )
                },
            ),
        )
        lastEmittedGeometryRevision = delta.geometryRevision
        runCatching(::publishRenderer).onFailure(::emitRendererError)
        result.success(deltaWireMap(delta))
    }

    /** Explicit recovery reset. Product callers receive only its summary. */
    private fun requestSnapshot(
        call: MethodCall,
        result: MethodChannel.Result,
        summaryOnly: Boolean = false,
    ) {
        val snapshot = requireGrid().requestSnapshot(call.snapshotRequest())
        if (snapshot == null) {
            result.error("VG_PROTOCOL_INVALID", "Snapshot identity is invalid", null)
            return
        }
        check(
            requireNotNull(renderer).applyGeometry(
                revision = snapshot.geometryRevision,
                reset = true,
                upsertKeys = snapshot.upsertKeys.toLongArray(),
                removalKeys = snapshot.removalKeys.toLongArray(),
                selectedKeysForResetOrReplacement = {
                    requireGrid().selectedRenderKeys(requireNotNull(renderer).capacity)
                },
            ),
        )
        lastEmittedGeometryRevision = snapshot.geometryRevision
        publishRenderer()
        result.success(
            if (summaryOnly) deltaSummaryWireMap(snapshot) else deltaWireMap(snapshot),
        )
    }

    private fun applyVisibility(call: MethodCall, result: MethodChannel.Result) {
        requireIdentity(call)
        val active = requireGrid().snapshot()
        val geometryRevision = call.requiredLong("geometryRevision")
        val nextVisibilityRevision = call.requiredLong("visibilityRevision")
        val keys = call.argument<LongArray>("keys")
            ?: throw IllegalArgumentException("keys must be Int64List")
        val styles = call.argument<ByteArray>("styles")
            ?: throw IllegalArgumentException("styles must be Uint8List")
        require(
            keys.size <= com.uhg0.ar_flutter_plugin_2.pointcloud.COVERAGE_RENDERER_MAX_STYLE_PATCH_ROWS &&
                styles.size == keys.size *
                    com.uhg0.ar_flutter_plugin_2.pointcloud.COVERAGE_RENDERER_STYLE_ROW_BYTES &&
                keys.distinct().size == keys.size,
        )
        validateVisibilityRevisions(
            namedGeometryRevision = geometryRevision,
            currentGeometryRevision = active.geometryRevision,
            nextVisibilityRevision = nextVisibilityRevision,
            currentVisibilityRevision = visibilityRevision,
        )
        require(
            requireNotNull(renderer).applyVisibility(
                namedGeometryRevision = geometryRevision,
                nextVisibilityRevision = nextVisibilityRevision,
                patchKeys = keys,
                patchStyleRows = styles,
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
            sensorHandoff.clear()
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
                        if (!lifecycleGuard.allowsCheckpoint(lifecycleToken)) {
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
                                deltaWireMap(snapshot),
                            )
                        } catch (error: Exception) {
                            emitRendererError(error)
                            result.success(
                                deltaWireMap(snapshot),
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

    private fun drainOneSensorBatch() {
        var failed: ObservationContext? = null
        try {
            val next =
                synchronized(this) {
                    sensorHandoff.take()
                } ?: return
            val feature = next.feature
            val depth = next.depth
            sensorProcessingOrder(
                feature?.timestampNs,
                depth?.timestampNs,
            ).forEach { source ->
                when (source) {
                    SensorHandoffSource.FEATURE -> {
                        val work = checkNotNull(feature)
                        failed = work.context
                        consumeFeature(work)
                    }
                    SensorHandoffSource.DEPTH -> {
                        val work = checkNotNull(depth)
                        failed = work.context
                        consumeDepth(work)
                    }
                }
            }
            val context = next.depth?.context ?: next.feature?.context ?: return
            failed = context
            if (!isCurrent(context)) return
            val health = currentHealth(context.grid.snapshot().diagnostics)
            if (health != lastEmittedHealth) {
                lastEmittedHealth = health
                val healthPayload = healthWireMap()
                main.post {
                    if (
                        lifecycleGuard.allows(context.lifecycleToken) &&
                        group === context.group &&
                        renderer === context.renderer
                    ) {
                        channel.invokeMethod(
                            "onGridHealth",
                            healthPayload,
                        )
                    }
                }
            }
            context.grid.takeGeometryDelta()?.let { delta ->
                if (
                    delta.geometryRevision > lastEmittedGeometryRevision &&
                    isCurrent(context)
                ) {
                    val applied =
                        synchronized(this) {
                            if (
                                !isCurrent(context) ||
                                delta.geometryRevision <= lastEmittedGeometryRevision
                            ) {
                                false
                            } else {
                                val sync =
                                    synchronizeRendererGeometry(
                                        renderer = context.renderer,
                                        delta = delta,
                                        fullSnapshot = context.grid::snapshot,
                                        selectedRenderKeys = {
                                            context.grid.selectedRenderKeys(
                                                context.renderer.capacity,
                                            )
                                        },
                                    )
                                if (sync == RendererGeometrySyncResult.REJECTED) {
                                    false
                                } else {
                                    lastEmittedGeometryRevision = delta.geometryRevision
                                    true
                                }
                            }
                        }
                    if (!applied) return@let
                    main.post {
                        val activeGroup = group
                        if (
                            lifecycleGuard.allows(context.lifecycleToken) &&
                            renderer === context.renderer &&
                            activeGroup === context.group &&
                            activeGroup.groupId == delta.groupId &&
                            activeGroup.groupGeneration == delta.groupGeneration &&
                            activeGroup.sessionGeneration == delta.sessionGeneration
                        ) {
                            runCatching(::publishRenderer)
                                .onFailure(::emitRendererError)
                            channel.invokeMethod(
                                "onGridSummary",
                                deltaSummaryWireMap(delta, context.renderer),
                            )
                        }
                    }
                }
            }
            failed = null
        } catch (error: Exception) {
            val failedWork = failed
            if (failedWork != null) {
                main.post {
                    if (isCurrent(failedWork)) {
                        emitGridError(error)
                    }
                }
            }
        }
    }

    private fun isCurrent(work: ObservationContext): Boolean =
        synchronized(this) {
            lifecycleGuard.allows(work.lifecycleToken) &&
                grid === work.grid &&
                renderer === work.renderer &&
                group === work.group
        }

    private fun consumeFeature(work: FeatureWork) {
        if (!isCurrent(work.context)) return
        when (val feature = work.feature) {
            is FeatureAcquisition.Observation ->
                work.context.grid.consumeNext(
                    FeatureObservationSource { feature.value },
                )
            FeatureAcquisition.TransientUnavailable ->
                work.context.grid.consumeNext(FeatureObservationSource { null })
            is FeatureAcquisition.Failure ->
                work.context.grid.consumeNext(
                    FeatureObservationSource {
                        throw IllegalStateException(feature.reason)
                    },
                )
        }
    }

    private fun consumeDepth(work: DepthWork) {
        if (isCurrent(work.context)) {
            work.context.grid.consumeDepth(work.depth)
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

    private fun setPointsEnabled(call: MethodCall, result: MethodChannel.Result) {
        val enabled =
            call.argument<Boolean>("enabled")
                ?: throw IllegalArgumentException("enabled is required")
        requireNotNull(renderer).setEnabled(enabled)
        rendererConfig = requireNotNull(rendererConfig).copy(enabled = enabled)
        frameCadence.reset()
        if (!enabled) clearRawPoints()
        publishRenderer()
        result.success(true)
    }

    private fun setVoxelRenderMode(call: MethodCall, result: MethodChannel.Result) {
        val mode = VoxelRenderMode.fromWire(call.requiredString("mode"))
        val current = requireNotNull(renderer)
        val currentConfig = requireNotNull(rendererConfig)
        if (currentConfig.voxelRenderMode != mode) {
            val retained = current.snapshot()
            val replacement = VisibilityGridRendererState(
                capacity = VisibilityGridRendererState.presentationCapacity(mode),
                defaultColor = currentConfig.defaultColor,
            ).also {
                it.setEnabled(currentConfig.enabled)
                it.setRenderMode(mode)
            }
            group?.let { activeGroup ->
                replacement.rehydrate(activeGroup, retained)
            }
            current.dispose()
            renderer = replacement
        }
        rendererConfig = currentConfig.copy(
            renderCapacity = VisibilityGridRendererState.presentationCapacity(mode),
            voxelRenderMode = mode,
        )
        frameCadence.reset()
        if (mode != VoxelRenderMode.POINTS) clearRawPoints()
        publishRenderer()
        result.success(true)
    }

    /** Called by the SceneView Compose effect after an actual mesh transition. */
    fun setRendererMounted(mounted: Boolean) {
        val pending = synchronized(this) {
            rendererMounted = mounted
            if (mounted) {
                pendingRendererMountResult.also { pendingRendererMountResult = null }
            } else {
                pendingRendererUnmountResult.also { pendingRendererUnmountResult = null }
            }
        }
        // A mode or enabled-state replacement installs a fresh Compose mesh.
        // Re-publish only after that mesh has crossed its mount fence so its
        // new upload coordinator receives the retained snapshot before Dart
        // observes the renderer as ready. Without this, a replacement can
        // report mounted while an active AR frame has nothing queued to upload.
        if (mounted) publishRenderer()
        pending?.success(true)
    }

    private fun awaitRendererMounted(result: MethodChannel.Result) {
        synchronized(this) {
            if (rendererMounted) {
                result.success(true)
                return
            }
            check(pendingRendererMountResult == null) {
                "Renderer mount fence is already pending"
            }
            pendingRendererMountResult = result
        }
    }

    private fun awaitRendererUnmounted(result: MethodChannel.Result) {
        synchronized(this) {
            if (!rendererMounted) {
                result.success(true)
                return
            }
            check(pendingRendererUnmountResult == null) {
                "Renderer unmount fence is already pending"
            }
            pendingRendererUnmountResult = result
        }
    }

    private fun publishRenderer() {
        val state = renderer ?: return
        render(state.snapshot(), requireNotNull(rendererConfig))
    }

    private fun scheduleRawPoints(snapshot: CoveragePointRenderSnapshot) {
        if (rawPointRenderHandoff.offer(snapshot)) {
            main.post(::drainRawPoints)
        }
    }

    private fun drainRawPoints() {
        val snapshot = rawPointRenderHandoff.takeLatest() ?: return
        val config = rendererConfig
        if (
            config?.enabled != true ||
            config.voxelRenderMode != VoxelRenderMode.POINTS ||
            paused ||
            disposed
        ) {
            return
        }
        rawPointSnapshotPublished = true
        renderRawPoints(snapshot)
    }

    private fun clearRawPoints() {
        rawPointRenderHandoff.clear()
        if (!rawPointSnapshotPublished) return
        rawPointSnapshotPublished = false
        renderRawPoints(null)
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

    private fun emitGridError(error: Throwable) {
        if (disposed) return
        channel.invokeMethod(
            "onError",
            mapOf(
                "code" to "VG_GRID_FAILED",
                "message" to (error.message ?: "Visibility-grid processing failed"),
                "recoverable" to true,
                "fatalToFeature" to false,
                "fatalToDepth" to false,
                "fatalToRenderer" to false,
                "fatalToGrid" to true,
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

    private fun restartHealthHeartbeat() {
        val generation =
            synchronized(this) {
                healthHeartbeatGeneration++
                if (disposed || paused || group == null) return
                healthHeartbeatGeneration
            }
        main.postDelayed(
            { emitHealthHeartbeat(generation) },
            HEALTH_HEARTBEAT_INTERVAL_MS,
        )
    }

    private fun emitHealthHeartbeat(generation: Long) {
        val payload =
            synchronized(this) {
                if (
                    disposed ||
                    paused ||
                    group == null ||
                    generation != healthHeartbeatGeneration
                ) {
                    null
                } else {
                    healthWireMap()
                }
            } ?: return
        channel.invokeMethod("onGridHealth", payload)
        main.postDelayed(
            { emitHealthHeartbeat(generation) },
            HEALTH_HEARTBEAT_INTERVAL_MS,
        )
    }

    private fun healthWireMap(): Map<String, Any>? {
        val activeGrid = grid ?: return null
        val activeRenderer = renderer ?: return null
        if (group == null) return null
        val diagnostics =
            enrichDiagnostics(activeGrid.snapshot().diagnostics, activeRenderer)
        return mapOf(
            "version" to VISIBILITY_GRID_WIRE_VERSION,
            "sourceHealth" to currentHealth(diagnostics),
            "diagnostics" to diagnostics.toWireMap(),
        )
    }

    private fun enrichDiagnostics(
        diagnostics: VisibilityGridDiagnostics,
        rendererState: VisibilityGridRendererState,
    ): VisibilityGridDiagnostics =
        synchronized(this) {
            val freeRows = rendererState.freeRowCount
            val renderedRows = rendererState.capacity - freeRows
            diagnostics.copy(
                callbackCopyP95Ns = callbackCopySamples.p95(),
                coalescedFeatureObservations = coalescedFeatureObservations,
                coalescedDepthObservations = coalescedDepthObservations,
                // This wire contract describes the authoritative semantic
                // grid, whose 100k capacity remains distinct from M0d's 20k
                // presentation selection. Keep its row/free invariant valid
                // for Dart while the bounded renderer is ledgered separately.
                rendererRows = renderedRows,
                rendererFreeRows =
                    (diagnostics.stableVoxelCapacity - renderedRows).coerceAtLeast(0),
            )
        }

    private fun deltaWireMap(
        delta: VisibilityGridDelta,
        rendererState: VisibilityGridRendererState? = renderer,
    ): Map<String, Any> {
        val enriched = rendererState?.let {
            enrichDiagnostics(delta.diagnostics, it)
        } ?: delta.diagnostics
        return delta.copy(diagnostics = enriched).toWireMap(currentHealth(enriched))
    }

    /** Fixed-size ordinary callback map: it has no stable-key arrays. */
    private fun deltaSummaryWireMap(
        delta: VisibilityGridDelta,
        rendererState: VisibilityGridRendererState? = renderer,
    ): Map<String, Any> {
        val enriched = rendererState?.let {
            enrichDiagnostics(delta.diagnostics, it)
        } ?: delta.diagnostics
        return mapOf(
            "version" to VISIBILITY_GRID_WIRE_VERSION,
            "groupId" to delta.groupId,
            "groupGeneration" to delta.groupGeneration,
            "sessionGeneration" to delta.sessionGeneration,
            "baseGeometryRevision" to delta.baseGeometryRevision,
            "geometryRevision" to delta.geometryRevision,
            "reset" to delta.reset,
            "capacity" to delta.capacity,
            "sourceHealth" to currentHealth(enriched),
            "diagnostics" to enriched.toWireMap(),
        )
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
}

internal fun shouldAcquireVisibilityFeatures(trackingState: TrackingState): Boolean =
    trackingState == TrackingState.TRACKING

/** Synthetic debug input owns its samples, so ARCore image acquisition stays off. */
internal fun shouldAcquireVisibilitySensorWork(
    trackingState: TrackingState,
    syntheticSource: Boolean,
): Boolean = !syntheticSource && shouldAcquireVisibilityFeatures(trackingState)

private class VisibilityGridChannelLatencySamples(
    private val capacity: Int = 256,
) {
    private val values = LongArray(capacity)
    private var count = 0
    private var next = 0

    fun clear() {
        count = 0
        next = 0
    }

    fun record(value: Long) {
        values[next] = value.coerceAtLeast(0)
        next = (next + 1) % capacity
        count = minOf(count + 1, capacity)
    }

    fun p95(): Long {
        if (count == 0) return 0
        val sorted = values.copyOf(count).sortedArray()
        return sorted[((count * 95 + 99) / 100 - 1).coerceIn(0, count - 1)]
    }
}

private data class ObservationContext(
    val grid: NativeVisibilityGrid,
    val renderer: VisibilityGridRendererState,
    val group: VisibilityGridGroupConfig,
    val lifecycleToken: Long,
)

private data class FeatureWork(
    val context: ObservationContext,
    val timestampNs: Long,
    val feature: FeatureAcquisition,
)

private data class DepthWork(
    val context: ObservationContext,
    val timestampNs: Long,
    val depth: DepthAcquisitionResult,
)

private sealed interface FeatureAcquisition {
    data class Observation(val value: FeatureObservation) : FeatureAcquisition

    data object TransientUnavailable : FeatureAcquisition

    data class Failure(val reason: String) : FeatureAcquisition
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

private const val HEALTH_HEARTBEAT_INTERVAL_MS = 1_000L

internal class VisibilityGridMethodException(
    val code: String,
    message: String,
) : IllegalArgumentException(message)

internal fun validateVisibilityRevisions(
    namedGeometryRevision: Long,
    currentGeometryRevision: Long,
    nextVisibilityRevision: Long,
    currentVisibilityRevision: Long,
) {
    if (namedGeometryRevision != currentGeometryRevision) {
        throw VisibilityGridMethodException(
            "VG_GEOMETRY_REVISION_GAP",
            "Visibility patch geometry revision is stale",
        )
    }
    if (nextVisibilityRevision <= currentVisibilityRevision) {
        throw VisibilityGridMethodException(
            "VG_VISIBILITY_REVISION_STALE",
            "Visibility revision must increase",
        )
    }
}
