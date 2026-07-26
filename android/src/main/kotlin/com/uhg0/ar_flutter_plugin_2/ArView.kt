package com.uhg0.ar_flutter_plugin_2

import android.app.Activity
import android.content.Context
import android.content.pm.ApplicationInfo
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import com.google.ar.core.Config
import com.google.ar.core.Frame
import com.google.ar.core.Anchor.CloudAnchorState
import com.google.ar.core.Plane
import com.google.ar.core.Session
import com.google.ar.core.TrackingState
import com.google.ar.core.exceptions.NotTrackingException
import com.uhg0.ar_flutter_plugin_2.capture.ArCaptureSession
import com.uhg0.ar_flutter_plugin_2.capture.CaptureSessionException
import com.uhg0.ar_flutter_plugin_2.capture.PoseBatchDispatcher
import com.uhg0.ar_flutter_plugin_2.sceneview.PluginAnchorRecord
import com.uhg0.ar_flutter_plugin_2.sceneview.PluginHitResult
import com.uhg0.ar_flutter_plugin_2.sceneview.PluginNodeRecord
import com.uhg0.ar_flutter_plugin_2.sceneview.PluginNodeSource
import com.uhg0.ar_flutter_plugin_2.sceneview.PluginSessionConfig
import com.uhg0.ar_flutter_plugin_2.sceneview.PluginTransform
import com.uhg0.ar_flutter_plugin_2.sceneview.SceneViewHost
import com.uhg0.ar_flutter_plugin_2.sceneview.decompose
import com.uhg0.ar_flutter_plugin_2.sceneview.resolveNodeUri
import com.uhg0.ar_flutter_plugin_2.pointcloud.PointCloudMethodChannel
import com.uhg0.ar_flutter_plugin_2.shared_camera.camera.CameraCapabilityQuerier
import io.flutter.FlutterInjector
import io.flutter.plugin.common.BinaryMessenger
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.platform.PlatformView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** SceneView 4.21.2 platform-view implementation. Flutter channels remain unchanged. */
internal class ArView(
    context: Context,
    private val activity: Activity,
    private val lifecycle: Lifecycle,
    messenger: BinaryMessenger,
    id: Int,
    initialSessionFeatures: Set<Session.Feature> = emptySet(),
) : PlatformView {
    private val root = FrameLayout(context)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val sessionChannel = MethodChannel(messenger, "arsession_$id")
    private val objectChannel = MethodChannel(messenger, "arobjects_$id")
    private val anchorChannel = MethodChannel(messenger, "aranchors_$id")
    private val captureChannel = MethodChannel(messenger, "arcapture_$id")
    private val poseBatchDispatcher = PoseBatchDispatcher(
        send = { method, arguments, result ->
            captureChannel.invokeMethod(method, arguments, result)
        },
    )
    private val nodeRecords = mutableMapOf<String, PluginNodeRecord>()
    private val nodeAnchorIds = mutableMapOf<String, String>()
    private val anchorRecords = mutableMapOf<String, PluginAnchorRecord>()
    private val detectedPlanes = mutableSetOf<Plane>()
    private var sessionConfig = defaultSessionConfig()
    private var sessionPausedByFlutter = false
    private var shutdownPrepared = false
    private var disposed = false
    private val pendingCloudOperations = mutableSetOf<() -> Unit>()

    private val sceneHost = SceneViewHost(
        context = context,
        activity = activity,
        lifecycle = lifecycle,
        sessionFeatures = initialSessionFeatures,
        onSessionUpdated = ::onFrame,
        onTrackingFailureChanged = { failure ->
            sessionChannel.invokeMethod("onTrackingFailure", failure)
        },
        onCoverageRendererMounted = ::setCoverageRendererMounted,
        onTouch = ::onTouch,
        onNodeGesture = ::onNodeGesture,
    )
    private lateinit var pointCloudChannel: PointCloudMethodChannel

    init {
        pointCloudChannel = PointCloudMethodChannel(
            messenger = messenger,
            viewId = id,
            isDebuggable = context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0,
            onRendererStateChanged = sceneHost::updateCoverageRenderer,
        )
    }

    private val captureSession = ArCaptureSession(
        sceneHost = sceneHost,
        capabilityQuerier = CameraCapabilityQuerier(context),
        captureChannel = captureChannel,
        onCapacityChanged = { value ->
            scope.launch { captureChannel.invokeMethod("onCaptureCapacityChanged", value) }
        },
        onObservedControlStateChanged = {
            scope.launch {
                captureChannel.invokeMethod("onExposureStateChanged", it.getCurrentExposureState())
                captureChannel.invokeMethod("onFocusStateChanged", it.getCurrentFocusState())
                captureChannel.invokeMethod("onWhiteBalanceStateChanged", it.getCurrentWhiteBalanceState())
                captureChannel.invokeMethod("onFlashStateChanged", it.getCurrentFlashState())
            }
        },
        onCaptureAccepted = { value ->
            scope.launch { captureChannel.invokeMethod("onCaptureAccepted", value) }
        },
        onCaptureFinalized = { value ->
            scope.launch { captureChannel.invokeMethod("onCaptureFinalized", value) }
        },
    )

    private fun setCoverageRendererMounted(mounted: Boolean) {
        if (::pointCloudChannel.isInitialized) {
            pointCloudChannel.setRendererMounted(mounted)
        }
    }

    private val lifecycleObserver = object : DefaultLifecycleObserver {
        override fun onPause(owner: LifecycleOwner) {
            pointCloudChannel.pause()
            captureSession.onSessionPaused()
            sceneHost.pause()
        }

        override fun onResume(owner: LifecycleOwner) {
            sceneHost.resume()
            pointCloudChannel.resume()
            if (!sessionPausedByFlutter) captureSession.onSessionResumed()
        }
    }

    init {
        lifecycle.addObserver(lifecycleObserver)
        root.addView(
            sceneHost.view,
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT,
        )
        sessionChannel.setMethodCallHandler(::onSessionCall)
        objectChannel.setMethodCallHandler(::onObjectCall)
        anchorChannel.setMethodCallHandler(::onAnchorCall)
        captureChannel.setMethodCallHandler(::onCaptureCall)
    }

    override fun getView(): View = root

    override fun dispose() {
        if (disposed) return
        disposed = true
        poseBatchDispatcher.clear()
        prepareForDispose()
        sessionChannel.setMethodCallHandler(null)
        objectChannel.setMethodCallHandler(null)
        anchorChannel.setMethodCallHandler(null)
        captureChannel.setMethodCallHandler(null)
        pointCloudChannel.dispose()
        lifecycle.removeObserver(lifecycleObserver)
        captureSession.dispose()
        pendingCloudOperations.toList().forEach { it() }
        pendingCloudOperations.clear()
        sceneHost.dispose()
        scope.cancel()
    }

    private fun prepareForDispose() {
        if (shutdownPrepared) return
        shutdownPrepared = true
        // ARCore's SharedCamera sample pauses the Session before closing
        // Camera2. Its wrapped image/session callbacks retain native Session
        // state until Camera2 shutdown completes.
        sceneHost.pause()
        captureSession.onSessionPaused()
    }

    private fun onSessionCall(call: MethodCall, result: MethodChannel.Result) {
        try {
            when (call.method) {
                "init" -> {
                    sessionConfig = PluginSessionConfig(
                        showPlanes = call.argument<Boolean>("showPlanes") ?: true,
                        showFeaturePoints = call.argument<Boolean>("showFeaturePoints") ?: false,
                        showWorldOrigin = call.argument<Boolean>("showWorldOrigin") ?: false,
                        handleTaps = call.argument<Boolean>("handleTaps") ?: true,
                        handlePans = call.argument<Boolean>("handlePans") ?: false,
                        handleRotation = call.argument<Boolean>("handleRotation") ?: false,
                        planeFindingMode = planeMode(call.argument<Int>("planeDetectionConfig") ?: 0),
                        customPlaneTexturePath = call.argument<String>("customPlaneTexturePath")?.let {
                            FlutterInjector.instance().flutterLoader().getLookupKeyForAsset(it)
                        },
                    )
                    sceneHost.configure(sessionConfig)
                    result.success(null)
                }
                "showPlanes" -> {
                    sessionConfig = sessionConfig.copy(
                        showPlanes = call.argument<Boolean>("showPlanes") ?: true,
                    )
                    sceneHost.configure(sessionConfig)
                    result.success(null)
                }
                "getCameraPose" -> result.success(
                    sceneHost.latestFrame?.camera?.pose?.toMatrixList(),
                )
                "getAnchorPose" -> result.success(
                    anchorRecords[call.argument<String>("anchorId")]?.transform?.matrix,
                )
                "getRendererPerformanceSnapshot" ->
                    result.success(sceneHost.rendererPerformanceSnapshot())
                "snapshot" -> sceneHost.snapshot { snapshot ->
                    snapshot.fold(result::success) {
                        result.error("SNAPSHOT_ERROR", it.message, null)
                    }
                }
                "disableCamera" -> {
                    sessionPausedByFlutter = true
                    pointCloudChannel.pause()
                    captureSession.onSessionPaused()
                    sceneHost.pause()
                    result.success(null)
                }
                "enableCamera" -> {
                    sessionPausedByFlutter = false
                    sceneHost.resume()
                    pointCloudChannel.resume()
                    captureSession.onSessionResumed()
                    result.success(null)
                }
                "dispose" -> {
                    dispose()
                    result.success(null)
                }
                else -> result.notImplemented()
            }
        } catch (error: Exception) {
            result.error("SESSION_ERROR", error.message, null)
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun onObjectCall(call: MethodCall, result: MethodChannel.Result) {
        try {
            when (call.method) {
                "init" -> result.success(null)
                "addNode" -> {
                    val record = nodeRecord(call.arguments as Map<String, Any?>)
                    nodeRecords[record.id] = record
                    result.success(sceneHost.addOrUpdateNode(record))
                }
                "addNodeToPlaneAnchor" -> {
                    val args = call.arguments as Map<String, Any?>
                    val record = nodeRecord(args.getValue("node") as Map<String, Any?>)
                    val anchor = anchorRecord(args.getValue("anchor") as Map<String, Any?>)
                    if (anchor.id !in anchorRecords) {
                        anchorRecords[anchor.id] = anchor
                        sceneHost.addOrUpdateAnchor(anchor)
                    }
                    nodeRecords[record.id] = record
                    nodeAnchorIds[record.id] = anchor.id
                    result.success(sceneHost.addOrUpdateNode(record, anchor.id))
                }
                "addNodeToScreenPosition" -> {
                    val args = call.arguments as Map<String, Any?>
                    val screenPosition = args["screenPosition"] as? Map<String, Number>
                        ?: throw IllegalArgumentException("screenPosition is required")
                    val record = nodeRecord(args)
                    val hit = sceneHost.hitTest(
                        screenPosition["x"]?.toFloat() ?: 0f,
                        screenPosition["y"]?.toFloat() ?: 0f,
                    ).firstOrNull()
                    if (hit == null) {
                        result.success(null)
                    } else {
                        val anchorId = "screen-${record.id}"
                        val anchor = PluginAnchorRecord(anchorId, hit.worldTransform)
                        anchorRecords[anchorId] = anchor
                        sceneHost.addOrUpdateAnchor(anchor)
                        nodeRecords[record.id] = record
                        nodeAnchorIds[record.id] = anchorId
                        sceneHost.addOrUpdateNode(record, anchorId)
                        result.success(null)
                    }
                }
                "transformationChanged" -> {
                    val name = call.argument<String>("name")
                    val transform = call.argument<List<Number>>("transformation")
                    val current = nodeRecords[name]
                    if (name == null || transform == null || current == null) {
                        result.error("INVALID_ARGUMENTS", "Known name and transformation are required", null)
                    } else {
                        val updated = current.copy(transform = transform.toPluginTransform())
                        nodeRecords[name] = updated
                        result.success(sceneHost.addOrUpdateNode(updated, nodeAnchorIds[name]))
                    }
                }
                "removeNode" -> {
                    val name = call.argument<String>("name")
                    if (name == null) {
                        result.error("INVALID_ARGUMENT", "Node name is required", null)
                    } else if (nodeRecords.remove(name) == null) {
                        result.error("NODE_NOT_FOUND", "Node with name $name not found", null)
                    } else {
                        nodeAnchorIds.remove(name)
                        sceneHost.removeNode(name)
                        result.success(true)
                    }
                }
                else -> result.notImplemented()
            }
        } catch (error: Exception) {
            result.error("OBJECT_ERROR", error.message, null)
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun onAnchorCall(call: MethodCall, result: MethodChannel.Result) {
        try {
            when (call.method) {
                "init" -> result.success(null)
                "initGoogleCloudAnchorMode" -> {
                    sessionConfig = sessionConfig.copy(cloudAnchorEnabled = true)
                    sceneHost.configure(sessionConfig)
                    result.success(null)
                }
                "estimateFeatureMapQualityForHosting" -> {
                    val session = sceneHost.activeSession
                        ?: throw IllegalStateException("AR Session is not available")
                    val camera = sceneHost.latestFrame?.camera
                    if (camera?.trackingState != TrackingState.TRACKING) {
                        result.error("NOT_TRACKING", "ARCore camera is not tracking", null)
                        return
                    }
                    val quality = try {
                        session.estimateFeatureMapQualityForHosting(camera.pose)
                    } catch (error: NotTrackingException) {
                        result.error("NOT_TRACKING", error.message, null)
                        return
                    }
                    result.success(
                        when (quality) {
                            Session.FeatureMapQuality.INSUFFICIENT -> "insufficient"
                            Session.FeatureMapQuality.SUFFICIENT -> "sufficient"
                            Session.FeatureMapQuality.GOOD -> "good"
                        },
                    )
                }
                "addAnchorAtCurrentCameraPose" -> {
                    val name = call.argument<String>("name")
                        ?: throw IllegalArgumentException("Anchor name is required")
                    val ttlDays = call.argument<Number>("ttl")?.toInt() ?: 1
                    require(ttlDays in 1..365) {
                        "Cloud Anchor TTL must be between 1 and 365 days"
                    }
                    val camera = sceneHost.latestFrame?.camera
                    if (camera?.trackingState != TrackingState.TRACKING) {
                        result.error("NOT_TRACKING", "ARCore camera is not tracking", null)
                        return
                    }
                    val transform = camera.pose.toPluginTransform()
                    val record = PluginAnchorRecord(
                        id = name,
                        transform = transform,
                        ttlDays = ttlDays,
                    )
                    anchorRecords[name] = record
                    if (!sceneHost.addOrUpdateAnchor(record)) {
                        anchorRecords.remove(name)
                        result.error("ANCHOR_CREATE_FAILED", "Could not create local anchor", null)
                        return
                    }
                    result.success(
                        mapOf(
                            "type" to 0,
                            "name" to name,
                            "transformation" to transform.matrix,
                            "childNodes" to emptyList<String>(),
                            "cloudanchorid" to null,
                            "ttl" to ttlDays,
                        ),
                    )
                }
                "addAnchor" -> {
                    val record = anchorRecord(call.arguments as Map<String, Any?>)
                    anchorRecords[record.id] = record
                    result.success(sceneHost.addOrUpdateAnchor(record))
                }
                "removeAnchor" -> {
                    val name = call.argument<String>("name")
                    if (name == null) {
                        result.error("INVALID_ARGUMENT", "Anchor name is required", null)
                    } else if (anchorRecords.remove(name) == null) {
                        result.error("ANCHOR_NOT_FOUND", "Anchor with name $name not found", null)
                    } else {
                        val anchorId = name
                        nodeAnchorIds.filterValues { it == anchorId }.keys.toList().forEach { nodeId ->
                            nodeAnchorIds.remove(nodeId)
                            nodeRecords.remove(nodeId)
                        }
                        sceneHost.removeAnchor(anchorId)
                        result.success(null)
                    }
                }
                "uploadAnchor" -> hostCloudAnchor(call, result)
                "downloadAnchor" -> resolveCloudAnchor(call, result)
                else -> result.notImplemented()
            }
        } catch (error: Exception) {
            result.error("ANCHOR_ERROR", error.message, null)
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun onCaptureCall(call: MethodCall, result: MethodChannel.Result) {
        try {
            when (call.method) {
                "initializeCapture" -> {
                    val config = call.arguments as? Map<String, Any?>
                        ?: throw IllegalArgumentException("Capture configuration is required")
                    captureSession.initialize(config)
                    result.success(mapOf(
                        "mode" to if (config["enableHighResCapture"] == true) "sharedCamera" else "previewFallback",
                    ))
                }
                "captureHighResImage" -> scope.launch {
                    try {
                        val args = call.arguments as? Map<String, Any?>
                        val policy = args?.get("qualityPolicy") as? Map<String, Any?>
                        result.success(withContext(Dispatchers.Default) { captureSession.captureImage(policy) })
                    } catch (error: CaptureSessionException) {
                        result.error(error.code, error.message, null)
                    } catch (error: IllegalArgumentException) {
                        result.error("CONFIG_INVALID", error.message, null)
                    } catch (error: Exception) {
                        result.error("CAPTURE_FAILED", error.message, null)
                    }
                }
                "getCaptureCapacity" -> result.success(captureSession.getCaptureCapacity())
                "getPerformanceSnapshot" -> result.success(captureSession.getPerformanceSnapshot())
                "getCameraIntrinsics" -> result.success(captureSession.getCameraIntrinsics())
                "getImageData" -> result.success(captureSession.getImageData(
                    call.argument<String>("imageId") ?: throw IllegalArgumentException("imageId is required"),
                    call.argument<String>("format") ?: "jpeg",
                ))
                "getImageSize" -> result.success(captureSession.getImageSize(
                    call.argument<String>("imageId") ?: throw IllegalArgumentException("imageId is required"),
                ))
                "setISO" -> result.success(mapOf(
                    "actualISO" to captureSession.setISO(
                        call.argument<Int>("isoValue")
                            ?: throw IllegalArgumentException("isoValue is required"),
                    ),
                ))
                "setExposureTime" -> result.success(mapOf(
                    "actualExposureTimeMicroseconds" to captureSession.setExposureTime(
                        call.argument<Number>("exposureTimeMicroseconds")?.toLong()
                            ?: throw IllegalArgumentException("exposureTimeMicroseconds is required"),
                    ),
                ))
                "setAutoExposureEnabled" -> result.success(captureSession.setAutoExposureEnabled(
                    call.argument<Boolean>("enabled")
                        ?: throw IllegalArgumentException("enabled is required"),
                ))
                "getCurrentISO" -> result.success(captureSession.getCurrentISO())
                "getCurrentExposureTime" -> result.success(captureSession.getCurrentExposureTimeMicros())
                "getSupportedISORange" -> result.success(captureSession.getSupportedISORange())
                "getSupportedExposureRange" -> result.success(captureSession.getSupportedExposureRange())
                "getCurrentExposureState" -> result.success(captureSession.getCurrentExposureState())
                "getExposureCompensationInfo" -> result.success(captureSession.getExposureCompensationInfo())
                "setExposureCompensation" -> result.success(mapOf(
                    "actualCompensation" to captureSession.setExposureCompensation(
                        call.argument<Number>("evStep")?.toDouble()
                            ?: throw IllegalArgumentException("evStep is required"),
                    ),
                ))
                "lockExposure" -> result.success(captureSession.lockExposure())
                "unlockExposure" -> result.success(captureSession.unlockExposure())
                "setFocusDistance" -> result.success(mapOf(
                    "actualDistance" to captureSession.setFocusDistance(
                        call.argument<Number>("distance")?.toDouble()
                            ?: throw IllegalArgumentException("distance is required"),
                    ),
                ))
                "setAutofocusEnabled" -> result.success(captureSession.setAutofocusEnabled(
                    call.argument<Boolean>("enabled")
                        ?: throw IllegalArgumentException("enabled is required"),
                ))
                "focusAtPoint" -> result.success(captureSession.focusAtPoint(
                    call.argument<Number>("x")?.toDouble()
                        ?: throw IllegalArgumentException("x is required"),
                    call.argument<Number>("y")?.toDouble()
                        ?: throw IllegalArgumentException("y is required"),
                ))
                "getCurrentFocusState" -> result.success(captureSession.getCurrentFocusState())
                "getSupportedFocusModes" -> result.success(captureSession.getSupportedFocusModes())
                "setFocusMode" -> result.success(captureSession.setFocusMode(
                    call.argument<String>("mode")
                        ?: throw IllegalArgumentException("mode is required"),
                ))
                "setWhiteBalanceMode" -> result.success(captureSession.setWhiteBalanceMode(
                    call.argument<String>("mode")
                        ?: throw IllegalArgumentException("mode is required"),
                ))
                "setColorTemperature" -> result.success(mapOf(
                    "actualColorTemperature" to captureSession.setColorTemperature(
                        call.argument<Int>("colorTemperatureK")
                            ?: throw IllegalArgumentException("colorTemperatureK is required"),
                    ),
                ))
                "getCurrentWhiteBalanceState" -> result.success(captureSession.getCurrentWhiteBalanceState())
                "getSupportedColorTemperatureRange" ->
                    result.success(captureSession.getSupportedColorTemperatureRange())
                "lockWhiteBalance" -> result.success(captureSession.lockWhiteBalance())
                "unlockWhiteBalance" -> result.success(captureSession.unlockWhiteBalance())
                "setWhiteBalanceFromPoint" -> result.success(captureSession.setWhiteBalanceFromPoint(
                    call.argument<Number>("x")?.toDouble()
                        ?: throw IllegalArgumentException("x is required"),
                    call.argument<Number>("y")?.toDouble()
                        ?: throw IllegalArgumentException("y is required"),
                ))
                "getSupportedWhiteBalanceModes" ->
                    result.success(captureSession.getSupportedWhiteBalanceModes())
                "setFlashMode" -> result.success(captureSession.setFlashMode(
                    call.argument<String>("mode")
                        ?: throw IllegalArgumentException("mode is required"),
                ))
                "getCurrentFlashState" -> result.success(captureSession.getCurrentFlashState())
                "isFlashAvailable" -> result.success(captureSession.isFlashAvailable())
                "setTorchEnabled" -> result.success(captureSession.setTorchEnabled(
                    call.argument<Boolean>("enabled")
                        ?: throw IllegalArgumentException("enabled is required"),
                ))
                "saveImageToFile" -> scope.launch {
                    try {
                        val saved = withContext(Dispatchers.IO) {
                            captureSession.saveImageToFile(
                                call.argument<String>("imageId")
                                    ?: throw IllegalArgumentException("imageId is required"),
                                call.argument<String>("filePath")
                                    ?: throw IllegalArgumentException("filePath is required"),
                                call.argument<String>("format") ?: "jpeg",
                            )
                        }
                        result.success(saved)
                    } catch (error: CaptureSessionException) {
                        result.error(error.code, error.message, null)
                    } catch (error: IllegalArgumentException) {
                        result.error("CONFIG_INVALID", error.message, null)
                    } catch (error: Exception) {
                        result.error("CAPTURE_FAILED", error.message, null)
                    }
                }
                "persistCapture" -> scope.launch {
                    try {
                        val destination = call.argument<Map<String, Any?>>("destination")
                        val persisted = withContext(Dispatchers.IO) {
                            captureSession.persistCapture(
                                call.argument<String>("imageId")
                                    ?: throw IllegalArgumentException("imageId is required"),
                                destination?.get("root") as? String
                                    ?: throw IllegalArgumentException("destination.root is required"),
                                call.argument<String>("sessionFolder")
                                    ?: throw IllegalArgumentException("sessionFolder is required"),
                                call.argument<String>("baseName")
                                    ?: throw IllegalArgumentException("baseName is required"),
                                call.argument<String>("format") ?: "jpeg",
                            )
                        }
                        result.success(persisted)
                    } catch (error: CaptureSessionException) {
                        result.error(error.code, error.message, null)
                    } catch (error: IllegalArgumentException) {
                        result.error("CONFIG_INVALID", error.message, null)
                    } catch (error: Exception) {
                        result.error("CAPTURE_FAILED", error.message, null)
                    }
                }
                "discardCapture" -> result.success(captureSession.discardCapture(
                    call.argument<String>("imageId") ?: throw IllegalArgumentException("imageId is required"),
                ))
                "dispose" -> {
                    prepareForDispose()
                    captureSession.dispose()
                    result.success(null)
                }
                else -> result.notImplemented()
            }
        } catch (error: CaptureSessionException) {
            result.error(error.code, error.message, null)
        } catch (error: Exception) {
            result.error("CAPTURE_FAILED", error.message, null)
        }
    }

    private fun onFrame(session: Session, frame: Frame) {
        pointCloudChannel.onFrame(frame)
        captureSession.buildPoseUpdate(frame)?.let(poseBatchDispatcher::offer)
        frame.getUpdatedTrackables(Plane::class.java).forEach { plane ->
            if (detectedPlanes.add(plane)) {
                sessionChannel.invokeMethod("onPlaneDetected", detectedPlanes.size)
            }
        }
    }

    private fun onTouch(event: MotionEvent, hits: List<PluginHitResult>) {
        if (event.action == MotionEvent.ACTION_UP && hits.isNotEmpty() && sessionConfig.handleTaps) {
            sessionChannel.invokeMethod("onPlaneOrPointTap", hits.map { it.toMap() })
        }
    }

    private fun onNodeGesture(event: String, nodeId: String, transform: PluginTransform?) {
        if (transform != null) {
            nodeRecords[nodeId]?.let { current ->
                val updated = current.copy(transform = transform)
                nodeRecords[nodeId] = updated
                sceneHost.addOrUpdateNode(updated, nodeAnchorIds[nodeId])
            }
        }
        when (event) {
            "tap" -> objectChannel.invokeMethod("onNodeTap", listOf(nodeId))
            "panStart" -> objectChannel.invokeMethod("onPanStart", nodeId)
            "panChange" -> objectChannel.invokeMethod("onPanChange", nodeId)
            "panEnd" -> objectChannel.invokeMethod(
                "onPanEnd",
                mapOf("name" to nodeId, "transform" to transform?.matrix),
            )
            "rotationStart" -> objectChannel.invokeMethod("onRotationStart", nodeId)
            "rotationChange" -> objectChannel.invokeMethod("onRotationChange", nodeId)
            "rotationEnd" -> objectChannel.invokeMethod(
                "onRotationEnd",
                mapOf("name" to nodeId, "transform" to transform?.matrix),
            )
        }
    }

    private fun hostCloudAnchor(call: MethodCall, result: MethodChannel.Result) {
        val anchorName = call.argument<String>("name")
            ?: throw IllegalArgumentException("Anchor name is required")
        val record = anchorRecords[anchorName]
            ?: throw IllegalArgumentException("Anchor not found: $anchorName")
        val session = sceneHost.activeSession
            ?: throw IllegalStateException("AR Session is not available")
        val localAnchor = session.createAnchor(record.transform.toPose())
        var cancel: (() -> Unit)? = null
        val future = session.hostCloudAnchorAsync(localAnchor, record.ttlDays) { cloudId, state ->
            cancel?.let(pendingCloudOperations::remove)
            localAnchor.detach()
            if (state == CloudAnchorState.SUCCESS && cloudId != null) {
                anchorRecords[anchorName] = record.copy(cloudAnchorId = cloudId)
                anchorChannel.invokeMethod(
                    "onCloudAnchorUploaded",
                    mapOf("name" to anchorName, "cloudanchorid" to cloudId),
                )
                result.success(true)
            } else {
                val code = if (state == CloudAnchorState.ERROR_NOT_AUTHORIZED) {
                    "ANCHOR_NOT_AUTHORIZED"
                } else {
                    "ANCHOR_HOST_FAILED"
                }
                result.error(code, "Failed to host cloud anchor: $state", null)
            }
        }
        val cancelOperation = { future.cancel(); localAnchor.detach(); Unit }
        cancel = cancelOperation
        pendingCloudOperations.add(cancelOperation)
    }

    private fun resolveCloudAnchor(call: MethodCall, result: MethodChannel.Result) {
        val cloudId = call.argument<String>("cloudanchorid")
            ?: throw IllegalArgumentException("Cloud Anchor ID is required")
        val session = sceneHost.activeSession
            ?: throw IllegalStateException("AR Session is not available")
        var cancel: (() -> Unit)? = null
        val future = session.resolveCloudAnchorAsync(cloudId) { resolvedAnchor, state ->
            cancel?.let(pendingCloudOperations::remove)
            if (state == CloudAnchorState.SUCCESS && resolvedAnchor != null) {
                val transform = resolvedAnchor.pose.toPluginTransform()
                resolvedAnchor.detach()
                val provisionalName = "resolved-$cloudId"
                val serialized = mapOf(
                    "type" to 0,
                    "name" to provisionalName,
                    "transformation" to transform.matrix,
                    "childNodes" to emptyList<String>(),
                    "cloudanchorid" to cloudId,
                    "ttl" to 1,
                )
                anchorChannel.invokeMethod(
                    "onAnchorDownloadSuccess",
                    serialized,
                    object : MethodChannel.Result {
                        override fun success(value: Any?) {
                            val name = value?.toString() ?: provisionalName
                            val record = PluginAnchorRecord(
                                id = name,
                                transform = transform,
                                cloudAnchorId = cloudId,
                            )
                            anchorRecords[name] = record
                            sceneHost.addOrUpdateAnchor(record)
                        }

                        override fun error(code: String, message: String?, details: Any?) {
                            sessionChannel.invokeMethod(
                                "onError",
                                listOf("Error registering downloaded anchor: $message"),
                            )
                        }

                        override fun notImplemented() {
                            sessionChannel.invokeMethod(
                                "onError",
                                listOf("Error registering downloaded anchor: not implemented"),
                            )
                        }
                    },
                )
                result.success(true)
            } else {
                val code = if (state == CloudAnchorState.ERROR_NOT_AUTHORIZED) {
                    "ANCHOR_NOT_AUTHORIZED"
                } else {
                    "ANCHOR_RESOLVE_FAILED"
                }
                result.error(code, "Failed to resolve cloud anchor: $state", null)
            }
        }
        val cancelOperation = { future.cancel(); Unit }
        cancel = cancelOperation
        pendingCloudOperations.add(cancelOperation)
    }

    private fun nodeRecord(data: Map<String, Any?>): PluginNodeRecord {
        val type = (data["type"] as Number).toInt()
        val rawUri = data["uri"] as String
        val source = PluginNodeSource.fromDartOrdinal(type)
        val uri = resolveNodeUri(
            source = source,
            rawUri = rawUri,
            appDataDirectory = activity.applicationInfo.dataDir,
            flutterAssetResolver = FlutterInjector.instance().flutterLoader()::getLookupKeyForAsset,
        )
        return PluginNodeRecord(
            id = data["name"] as String,
            source = source,
            uri = uri,
            transform = (data["transformation"] as List<Number>).toPluginTransform(),
            data = data["data"] as? Map<String, Any?> ?: emptyMap(),
        )
    }

    private fun anchorRecord(data: Map<String, Any?>): PluginAnchorRecord {
        val ttlDays = (data["ttl"] as? Number)?.toInt() ?: 1
        require(ttlDays in 1..365) { "Cloud Anchor TTL must be between 1 and 365 days" }
        return PluginAnchorRecord(
            id = data["name"] as String,
            transform = (data["transformation"] as List<Number>).toPluginTransform(),
            childNodeIds = (data["childNodes"] as? List<*>)?.map(Any?::toString).orEmpty(),
            cloudAnchorId = data["cloudanchorid"] as? String,
            ttlDays = ttlDays,
        )
    }

    private fun List<Number>.toPluginTransform() = PluginTransform(map(Number::toDouble))

    private fun PluginTransform.toPose(): com.google.ar.core.Pose {
        val parts = decompose()
        return com.google.ar.core.Pose(
            floatArrayOf(
                parts.position.x.toFloat(),
                parts.position.y.toFloat(),
                parts.position.z.toFloat(),
            ),
            floatArrayOf(
                parts.rotation.x.toFloat(),
                parts.rotation.y.toFloat(),
                parts.rotation.z.toFloat(),
                parts.rotation.w.toFloat(),
            ),
        )
    }

    private fun com.google.ar.core.Pose.toPluginTransform(): PluginTransform =
        FloatArray(16).also { toMatrix(it, 0) }
            .map(Float::toDouble)
            .let(::PluginTransform)

    private fun com.google.ar.core.Pose.toMatrixList(): List<Double> =
        FloatArray(16).also { toMatrix(it, 0) }.map(Float::toDouble)

    private fun PluginHitResult.toMap(): Map<String, Any> = mapOf(
        "type" to when (type.name) { "PLANE" -> 1; "POINT" -> 2; else -> 0 },
        "distance" to distanceMeters,
        "worldTransform" to worldTransform.matrix,
    )

    private fun planeMode(index: Int): Config.PlaneFindingMode = when (index) {
        1 -> Config.PlaneFindingMode.HORIZONTAL
        2 -> Config.PlaneFindingMode.VERTICAL
        3 -> Config.PlaneFindingMode.HORIZONTAL_AND_VERTICAL
        else -> Config.PlaneFindingMode.DISABLED
    }

    private companion object {
        fun defaultSessionConfig() = PluginSessionConfig(
            showPlanes = true,
            showFeaturePoints = false,
            showWorldOrigin = false,
            handleTaps = true,
            handlePans = false,
            handleRotation = false,
            planeFindingMode = Config.PlaneFindingMode.DISABLED,
        )
    }
}
