package com.uhg0.ar_flutter_plugin_2.pointcloud

import android.os.Handler
import android.os.Looper
import kotlin.math.floor
import com.google.ar.core.Frame
import com.google.ar.core.TrackingState
import io.flutter.plugin.common.BinaryMessenger
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel

class PointCloudMethodChannel internal constructor(
    private val endpoint: PointCloudChannelEndpoint,
    private val isDebuggable: Boolean,
    private val onRendererStateChanged:
        (CoveragePointRenderSnapshot?, PointCloudNativeConfig?) -> Unit = { _, _ -> },
    private val onRawPointCloudChanged:
        (CoveragePointRenderSnapshot?) -> Unit = {},
    private val sourceFactory: (PointCloudNativeConfig) -> PointCloudSource = { config ->
        if (config.syntheticSource) {
            SyntheticPointCloudSource()
        } else {
            ArCorePointCloudSource(config.minConfidence)
        }
    },
    private val callbackScheduler: PointCloudCallbackScheduler,
) : MethodChannel.MethodCallHandler {
    constructor(
        messenger: BinaryMessenger,
        viewId: Int,
        isDebuggable: Boolean,
        onRendererStateChanged:
            (CoveragePointRenderSnapshot?, PointCloudNativeConfig?) -> Unit = { _, _ -> },
        onRawPointCloudChanged:
            (CoveragePointRenderSnapshot?) -> Unit = {},
        sourceFactory: (PointCloudNativeConfig) -> PointCloudSource = { config ->
            if (config.syntheticSource) {
                SyntheticPointCloudSource()
            } else {
                ArCorePointCloudSource(config.minConfidence)
            }
        },
    ) : this(
        endpoint = FlutterPointCloudChannelEndpoint(messenger, viewId),
        isDebuggable = isDebuggable,
        onRendererStateChanged = onRendererStateChanged,
        onRawPointCloudChanged = onRawPointCloudChanged,
        sourceFactory = sourceFactory,
        callbackScheduler = AndroidPointCloudCallbackScheduler(),
    )

    private val queue = LatestPointCloudQueue()
    private var config: PointCloudNativeConfig? = null
    private var source: PointCloudSource? = null
    private var renderer: CoveragePointRendererState? = null
    private var latestRawPointSnapshot: CoveragePointRenderSnapshot? = null
    private var acquisitionReady = false
    private var rendererReady = false
    private var rendererMounted = false
    private var disposed = false
    private var lastAcquisitionNs = Long.MIN_VALUE
    private var consecutiveAcquisitionErrors = 0
    private var recordedQueueCoalesces = 0L
    private var lastPublishedRendererRevision = Long.MIN_VALUE
    private var lastStatsTimestampNs = Long.MIN_VALUE
    private var framesSinceStats = 0
    private var lastRendererFps = 0.0
    private var initializationGeneration = 0L
    private var trackingFrameCallbacks = 0L
    private var nonTrackingFrameCallbacks = 0L
    private var acquisitionAttempts = 0L
    private var emptyAcquisitions = 0L
    private var acquisitionErrors = 0L
    private var successfulPointCloudSamples = 0L

    init {
        endpoint.setMethodCallHandler(this)
    }

    override fun onMethodCall(call: MethodCall, result: MethodChannel.Result) {
        if (disposed && call.method != "dispose") {
            result.error("PC_NOT_INITIALIZED", "Point-cloud channel is disposed", null)
            return
        }
        try {
            when (call.method) {
                "init" -> initialize(call, result)
                "updateVoxels" -> updateVoxels(call, result)
                "setPointsEnabled" -> {
                    val enabled = requiredBoolean(call, "enabled")
                    requireRenderer().setEnabled(enabled)
                    config = config?.copy(enabled = enabled)
                    latestRawPointSnapshot =
                        latestRawPointSnapshot?.copy(enabled = enabled)
                    onRawPointCloudChanged(latestRawPointSnapshot)
                    publishRendererState()
                    result.success(true)
                }
                "setVoxelRenderMode" -> {
                    val mode = requiredVoxelRenderMode(call)
                    requireRenderer().setRenderMode(mode)
                    config = config?.copy(voxelRenderMode = mode)
                    if (mode != VoxelRenderMode.POINTS) {
                        latestRawPointSnapshot = null
                        onRawPointCloudChanged(null)
                    }
                    // A repeated selection is also an explicit renderer refresh.
                    // Always republish so a retained node cannot remain in the
                    // previous visual mode after an interrupted UI command.
                    publishRendererState(force = true)
                    result.success(true)
                }
                "getRenderingStats" -> result.success(currentStats().toMap())
                "clear" -> {
                    queue.clear()
                    renderer?.clear()
                    publishRendererState()
                    result.success(true)
                }
                "dispose" -> {
                    dispose()
                    result.success(true)
                }
                else -> result.notImplemented()
            }
        } catch (error: PointCloudChannelException) {
            result.error(error.code, error.message, null)
        } catch (error: IllegalArgumentException) {
            result.error("PC_PROTOCOL_INVALID", error.message, null)
        } catch (error: IllegalStateException) {
            result.error("PC_NOT_INITIALIZED", error.message, null)
        } catch (error: Exception) {
            result.error("PC_INTERNAL", error.message, null)
        }
    }

    fun onFrame(frame: Frame) {
        processFrame(
            timestampNs = frame.timestamp,
            isTracking = frame.camera.trackingState == TrackingState.TRACKING,
            frame = frame,
        )
    }

    internal fun onFrameForTest(
        timestampNs: Long,
        isTracking: Boolean = true,
    ) {
        processFrame(timestampNs, isTracking, null)
    }

    private fun processFrame(
        timestampNs: Long,
        isTracking: Boolean,
        frame: Frame?,
    ) {
        if (disposed) return
        updateAndMaybeEmitStats(timestampNs)
        val activeConfig = config ?: return
        if (isTracking) {
            trackingFrameCallbacks++
        } else {
            nonTrackingFrameCallbacks++
        }
        if (!acquisitionReady || !isTracking) return
        val intervalNs = 1_000_000_000L / activeConfig.frameRateHz
        if (lastAcquisitionNs != Long.MIN_VALUE && timestampNs - lastAcquisitionNs < intervalNs) {
            return
        }
        lastAcquisitionNs = timestampNs
        try {
            acquisitionAttempts++
            val sample = source?.acquire(frame)
            if (sample == null) {
                emptyAcquisitions++
                val invalidAcquisitions =
                    source?.diagnostics()?.invalidPointCloudAcquisitions ?: 0L
                if (
                    successfulPointCloudSamples == 0L &&
                    invalidAcquisitions >= MAX_INVALID_POINT_CLOUD_ACQUISITIONS
                ) {
                    acquisitionReady = false
                    emitError(
                        PointCloudError(
                            code = "PC_INVALID_POINT_DATA",
                            message = "ARCore returned repeated point clouds with no finite geometry",
                            fatalToAcquisition = true,
                        ),
                    )
                    publishReadiness()
                }
                return
            }
            successfulPointCloudSamples++
            consecutiveAcquisitionErrors = 0
            if (activeConfig.voxelRenderMode == VoxelRenderMode.POINTS) {
                latestRawPointSnapshot = sample.toRawPointRenderSnapshot(
                    capacity = activeConfig.renderCapacity,
                    color = activeConfig.defaultColor,
                    enabled = activeConfig.enabled,
                )
                onRawPointCloudChanged(latestRawPointSnapshot)
            }
            val emit = queue.offer(sample)
            syncCoalescingStats()
            if (emit != null) emit(emit)
        } catch (error: Exception) {
            acquisitionErrors++
            consecutiveAcquisitionErrors++
            if (consecutiveAcquisitionErrors >= activeConfig.maxConsecutiveAcquisitionErrors) {
                acquisitionReady = false
                emitError(
                    PointCloudError(
                        code = "PC_ACQUISITION_FAILED",
                        message = error.message ?: "Point-cloud acquisition failed",
                        fatalToAcquisition = true,
                    ),
                )
            }
        }
    }

    fun emitError(error: PointCloudError) {
        endpoint.invokeMethod("onError", error.toMap())
    }

    fun dispose() {
        if (disposed) return
        disposed = true
        initializationGeneration++
        acquisitionReady = false
        source = null
        queue.clear()
        renderer?.dispose()
        renderer = null
        rendererReady = false
        rendererMounted = false
        latestRawPointSnapshot = null
        onRawPointCloudChanged(null)
        onRendererStateChanged(null, null)
        callbackScheduler.removeAll()
        endpoint.setMethodCallHandler(null)
    }

    fun setRendererMounted(mounted: Boolean) {
        if (disposed) return
        if (rendererMounted == mounted) {
            publishReadiness()
            return
        }
        rendererMounted = mounted
        publishRendererState(force = true)
        publishReadiness()
    }

    /** Stops acquisition and invalidates every callback from the old session. */
    fun pause() {
        if (disposed) return
        initializationGeneration++
        acquisitionReady = false
        lastAcquisitionNs = Long.MIN_VALUE
        queue.clear()
        latestRawPointSnapshot = null
        onRawPointCloudChanged(null)
        callbackScheduler.removeAll()
        publishReadiness()
    }

    /** Resumes acquisition while retaining the fixed-capacity renderer state. */
    fun resume() {
        if (disposed) return
        initializationGeneration++
        acquisitionReady = config != null && rendererReady
        lastAcquisitionNs = Long.MIN_VALUE
        publishReadiness()
    }

    private fun initialize(call: MethodCall, result: MethodChannel.Result) {
        val version = call.argument<String>("version")
        if (version != POINT_CLOUD_WIRE_VERSION) {
            throw PointCloudChannelException(
                "PC_VERSION_MISMATCH",
                "Expected $POINT_CLOUD_WIRE_VERSION, received $version",
            )
        }
        val synthetic = call.argument<Boolean>("syntheticSource") ?: false
        if (synthetic && !isDebuggable) {
            throw PointCloudChannelException(
                "PC_SYNTHETIC_FORBIDDEN",
                "Synthetic point-cloud acquisition is debug-only",
            )
        }
        val parsed = PointCloudNativeConfig(
            wireVersion = version,
            renderCapacity = requiredInt(call, "renderCapacity", 1, 100_000),
            defaultColor = requiredArgb32(call, "defaultColor"),
            pointSizePx = requiredFiniteFloat(call, "pointSizePx", 0.000001f, Float.MAX_VALUE),
            frameRateHz = requiredInt(call, "frameRateHz", 1, 60),
            maxConsecutiveAcquisitionErrors =
                requiredInt(call, "maxConsecutiveAcquisitionErrors", 1, Int.MAX_VALUE),
            minConfidence = requiredFiniteFloat(call, "minConfidence", 0f, 1f),
            enabled = call.argument<Boolean>("enabled") ?: true,
            syntheticSource = synthetic,
            voxelRenderMode = VoxelRenderMode.fromWire(
                call.argument<String>("voxelRenderMode")
                    ?: VoxelRenderMode.POINTS.wireName,
            ),
            voxelSizeMeters = optionalFiniteFloat(call, "voxelSizeMeters", 0.1f),
            cubeSizeFactor = optionalFiniteFloat(call, "cubeSizeFactor", 1f),
        )
        val nextRenderer = try {
            CoveragePointRendererState(parsed)
        } catch (error: Exception) {
            emitError(
                PointCloudError(
                    code = "PC_RENDERER_INIT_FAILED",
                    message = error.message ?: "Point renderer initialization failed",
                    fatalToRenderer = true,
                ),
            )
            null
        }
        val nextSource = try {
            sourceFactory(parsed)
        } catch (error: Exception) {
            nextRenderer?.dispose()
            emitError(
                PointCloudError(
                    code = "PC_ACQUISITION_INIT_FAILED",
                    message = error.message ?: "Point-cloud acquisition initialization failed",
                    fatalToAcquisition = true,
                ),
            )
            throw PointCloudChannelException(
                "PC_ACQUISITION_INIT_FAILED",
                error.message ?: "Point-cloud acquisition initialization failed",
            )
        }
        renderer?.dispose()
        renderer = nextRenderer
        rendererReady = nextRenderer != null
        source = nextSource
        rendererMounted = false
        acquisitionReady = true
        config = parsed
        lastPublishedRendererRevision = Long.MIN_VALUE
        lastAcquisitionNs = Long.MIN_VALUE
        consecutiveAcquisitionErrors = 0
        recordedQueueCoalesces = 0
        lastStatsTimestampNs = Long.MIN_VALUE
        framesSinceStats = 0
        lastRendererFps = 0.0
        trackingFrameCallbacks = 0
        nonTrackingFrameCallbacks = 0
        acquisitionAttempts = 0
        emptyAcquisitions = 0
        acquisitionErrors = 0
        successfulPointCloudSamples = 0
        queue.clear()
        latestRawPointSnapshot = null
        onRawPointCloudChanged(null)
        initializationGeneration++
        val generation = initializationGeneration
        publishRendererState(force = true)
        publishReadiness()
        result.success(
            mapOf(
                "version" to POINT_CLOUD_WIRE_VERSION,
                "rendererReady" to (rendererReady && rendererMounted),
                "acquisitionReady" to acquisitionReady,
            ),
        )
    }

    private fun updateVoxels(call: MethodCall, result: MethodChannel.Result) {
        val state = requireRenderer()
        val epoch = requiredLong(call, "epoch", 0L, Long.MAX_VALUE)
        val keys = call.argument<LongArray>("keys")
            ?: throw IllegalArgumentException("keys must be Int64List")
        val positions = call.argument<FloatArray>("positionsWorld")
            ?: throw IllegalArgumentException("positionsWorld must be Float32List")
        val colors = call.argument<IntArray>("colors")
            ?: throw IllegalArgumentException("colors must be Int32List")
        val gridRotationWorld =
            call.argument<FloatArray>("gridRotationWorld")
                ?: identityGridRotation()
        val applied = state.updateVoxels(
            epoch,
            keys,
            positions,
            colors,
            gridRotationWorld,
        )
        publishRendererState()
        result.success(
            mapOf(
                "applied" to applied,
                "lastAppliedEpoch" to state.stats().lastAppliedColorEpoch,
            ),
        )
    }

    private fun emit(sample: PointCloudSample) {
        val generation = initializationGeneration
        callbackScheduler.postDelayed(
            {
                if (generation != initializationGeneration) return@postDelayed
                val failure = queue.fail(sample.sequence) ?: return@postDelayed
                emitError(
                    PointCloudError(
                        code = "PC_CALLBACK_TIMEOUT",
                        message = "Point-cloud frame callback timed out",
                    ),
                )
                emitNext(failure.next)
            },
            CALLBACK_TIMEOUT_MS,
        )
        endpoint.invokeMethod(
            "onPointCloudFrame",
            mapOf(
                "version" to POINT_CLOUD_WIRE_VERSION,
                "sequence" to sample.sequence,
                "timestampNs" to sample.timestampNs,
                "count" to sample.ids.size,
                "ids" to sample.ids,
                "points" to sample.points,
            ),
            object : MethodChannel.Result {
                override fun success(result: Any?) {
                    if (generation != initializationGeneration) return
                    val accepted = (result as? Map<*, *>)?.get("acceptedSequence") as? Number
                    if (accepted?.toLong() != sample.sequence) {
                        acquisitionReady = false
                        emitError(
                            PointCloudError(
                                code = "PC_PROTOCOL_INVALID",
                                message = "Frame acknowledgement sequence mismatch",
                                fatalToAcquisition = true,
                            ),
                        )
                    }
                    emitNext(queue.acknowledge(sample.sequence))
                }

                override fun error(code: String, message: String?, details: Any?) {
                    if (generation == initializationGeneration) {
                        emitNext(queue.fail(sample.sequence)?.next)
                    }
                }

                override fun notImplemented() {
                    if (generation == initializationGeneration) {
                        emitNext(queue.fail(sample.sequence)?.next)
                    }
                }
            },
        )
    }

    private fun emitNext(next: PointCloudSample?) {
        syncCoalescingStats()
        if (next != null) emit(next)
    }

    private fun syncCoalescingStats() {
        val latest = queue.coalescedCount
        while (recordedQueueCoalesces < latest) {
            renderer?.recordCoalescedFrame()
            recordedQueueCoalesces++
        }
    }

    private fun updateAndMaybeEmitStats(timestampNs: Long) {
        framesSinceStats++
        if (lastStatsTimestampNs == Long.MIN_VALUE) {
            lastStatsTimestampNs = timestampNs
            return
        }
        val elapsed = timestampNs - lastStatsTimestampNs
        if (elapsed < STATS_INTERVAL_NS) return
        lastRendererFps = framesSinceStats * 1_000_000_000.0 / elapsed
        framesSinceStats = 0
        lastStatsTimestampNs = timestampNs
        endpoint.invokeMethod("onRenderingStats", currentStats().toMap())
    }

    private fun currentStats(): PointCloudRenderStats {
        val queueState = queue.stateCounts()
        val sourceDiagnostics = source?.diagnostics() ?: PointCloudSourceDiagnostics()
        val base = renderer?.stats(lastRendererFps)
            ?: PointCloudRenderStats(
            fps = lastRendererFps,
            livePointCount = 0,
            bufferBytes = 0,
            emittedFrames = 0,
            coalescedFrames = 0,
            lastAppliedColorEpoch = 0,
            )
        return base.copy(
            arFrameCallbackFps = lastRendererFps,
            frameCallbackInFlight = queueState.first.toLong(),
            frameCallbackPending = queueState.second.toLong(),
            rendererMounted = rendererMounted,
            trackingFrameCallbacks = trackingFrameCallbacks,
            nonTrackingFrameCallbacks = nonTrackingFrameCallbacks,
            acquisitionAttempts = acquisitionAttempts,
            emptyAcquisitions = emptyAcquisitions,
            acquisitionErrors = acquisitionErrors,
            rawPointCloudIds = sourceDiagnostics.rawIds,
            rawPointCloudFloats = sourceDiagnostics.rawPointFloats,
            acceptedSourcePoints = sourceDiagnostics.acceptedPoints,
            confidenceRejectedPoints = sourceDiagnostics.confidenceRejectedPoints,
            nonFiniteRejectedPoints = sourceDiagnostics.nonFiniteRejectedPoints,
            invalidPointCloudAcquisitions = sourceDiagnostics.invalidPointCloudAcquisitions,
            unchangedPointCloudTimestamps = sourceDiagnostics.unchangedTimestampAcquisitions,
            lastPointCloudTimestampNs = sourceDiagnostics.lastTimestampNs,
        )
    }

    private fun publishRendererState(force: Boolean = false) {
        if (!rendererReady) return
        val snapshot = renderer?.snapshotIfChanged(lastPublishedRendererRevision, force) ?: return
        lastPublishedRendererRevision = snapshot.revision
        onRendererStateChanged(snapshot, config)
    }

    private fun publishReadiness() {
        endpoint.invokeMethod(
            "onRendererReady",
            mapOf(
                "version" to POINT_CLOUD_WIRE_VERSION,
                "rendererReady" to (rendererReady && rendererMounted),
                "acquisitionReady" to acquisitionReady,
                "rendererMounted" to rendererMounted,
            ),
        )
    }

    private fun requireRenderer(): CoveragePointRendererState = renderer
        ?: throw PointCloudChannelException(
            "PC_NOT_INITIALIZED",
            "Point renderer is not initialized",
        )

    private fun requiredNumber(call: MethodCall, key: String): Number =
        call.argument<Number>(key)
            ?: throw IllegalArgumentException("$key is required")

    private fun requiredLong(call: MethodCall, key: String, min: Long, max: Long): Long {
        val value = requiredNumber(call, key).toDouble()
        require(value.isFinite() && value == floor(value)) {
            "$key must be a finite whole number"
        }
        require(value >= min.toDouble() && value <= max.toDouble()) {
            "$key is outside [$min, $max]"
        }
        return value.toLong()
    }

    private fun requiredInt(call: MethodCall, key: String, min: Int, max: Int): Int =
        requiredLong(call, key, min.toLong(), max.toLong()).toInt()

    private fun requiredFiniteFloat(
        call: MethodCall,
        key: String,
        min: Float,
        max: Float,
    ): Float {
        val value = requiredNumber(call, key).toDouble()
        require(value.isFinite() && value >= min && value <= max) {
            "$key must be finite and within [$min, $max]"
        }
        return value.toFloat()
    }

    private fun requiredArgb32(call: MethodCall, key: String): Int {
        val value = requiredNumber(call, key).toDouble()
        require(value.isFinite() && value == floor(value)) {
            "$key must be a finite whole number"
        }
        require(value >= Int.MIN_VALUE.toDouble() && value <= 4_294_967_295.0) {
            "$key must be a 32-bit ARGB value"
        }
        return value.toLong().toInt()
    }

    private fun requiredBoolean(call: MethodCall, key: String): Boolean =
        call.argument<Boolean>(key)
            ?: throw IllegalArgumentException("$key is required")

    private fun requiredVoxelRenderMode(call: MethodCall): VoxelRenderMode =
        VoxelRenderMode.fromWire(
            call.argument<String>("mode")
                ?: throw IllegalArgumentException("mode is required"),
        )

    private fun optionalFiniteFloat(call: MethodCall, key: String, default: Float): Float {
        val value = call.argument<Number>(key)?.toDouble() ?: return default
        require(value.isFinite() && value > 0.0) {
            "$key must be finite and greater than zero"
        }
        return value.toFloat()
    }
}

internal interface PointCloudChannelEndpoint {
    fun setMethodCallHandler(handler: MethodChannel.MethodCallHandler?)
    fun invokeMethod(method: String, arguments: Any?, callback: MethodChannel.Result? = null)
}

private class FlutterPointCloudChannelEndpoint(
    messenger: BinaryMessenger,
    viewId: Int,
) : PointCloudChannelEndpoint {
    private val channel = MethodChannel(messenger, "arpointcloud_$viewId")

    override fun setMethodCallHandler(handler: MethodChannel.MethodCallHandler?) {
        channel.setMethodCallHandler(handler)
    }

    override fun invokeMethod(method: String, arguments: Any?, callback: MethodChannel.Result?) {
        channel.invokeMethod(method, arguments, callback)
    }
}

internal interface PointCloudCallbackScheduler {
    fun postDelayed(callback: () -> Unit, delayMs: Long)
    fun removeAll()
}

private class AndroidPointCloudCallbackScheduler : PointCloudCallbackScheduler {
    private val handler = Handler(Looper.getMainLooper())

    override fun postDelayed(callback: () -> Unit, delayMs: Long) {
        handler.postDelayed(callback, delayMs)
    }

    override fun removeAll() {
        handler.removeCallbacksAndMessages(null)
    }
}

private const val CALLBACK_TIMEOUT_MS = 1_000L
private const val STATS_INTERVAL_NS = 1_000_000_000L
private const val MAX_INVALID_POINT_CLOUD_ACQUISITIONS = 3L

private class PointCloudChannelException(
    val code: String,
    override val message: String,
) : IllegalStateException(message)
