package com.uhg0.ar_flutter_plugin_2.sceneview

import android.app.Activity
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.Trace
import android.util.Log
import android.util.Base64
import android.view.MotionEvent
import android.view.Choreographer
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.opengl.Matrix
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.Composable
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.findViewTreeLifecycleOwner
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.google.android.filament.Engine
import com.google.android.filament.RenderableManager
import com.google.android.filament.Renderer
import com.google.android.filament.Stream
import com.google.android.filament.Texture
import com.google.ar.core.Anchor
import com.google.ar.core.Config
import com.google.ar.core.CameraConfig
import com.google.ar.core.CameraConfigFilter
import com.google.ar.core.Frame
import com.google.ar.core.HitResult
import com.google.ar.core.Plane
import com.google.ar.core.Point
import com.google.ar.core.Pose
import com.google.ar.core.Session
import com.uhg0.ar_flutter_plugin_2.pointcloud.CoveragePointRenderSnapshot
import com.uhg0.ar_flutter_plugin_2.pointcloud.CoverageRendererStyleRowV1
import com.uhg0.ar_flutter_plugin_2.pointcloud.CoverageRendererPalette
import com.uhg0.ar_flutter_plugin_2.pointcloud.COVERAGE_RENDERER_STYLE_ROW_BYTES
import com.uhg0.ar_flutter_plugin_2.pointcloud.PointCloudNativeConfig
import com.uhg0.ar_flutter_plugin_2.pointcloud.VoxelRenderMode
import com.uhg0.ar_flutter_plugin_2.pointcloud.rangeOnly
import com.uhg0.ar_flutter_plugin_2.pointcloud.deepCopy
import com.uhg0.ar_flutter_plugin_2.visibilitygrid.ArCoreDepthModeController
import io.github.sceneview.SurfaceType
import io.github.sceneview.ar.ARSceneView
import io.github.sceneview.ar.camera.ARCameraStream
import io.github.sceneview.ar.rememberARCameraStream
import io.github.sceneview.math.Position
import io.github.sceneview.math.Rotation
import io.github.sceneview.math.Scale
import io.github.sceneview.math.Size
import io.github.sceneview.gesture.GestureDetector
import io.github.sceneview.gesture.MoveGestureDetector
import io.github.sceneview.gesture.RotateGestureDetector
import io.github.sceneview.loaders.MaterialLoader
import io.github.sceneview.loaders.ModelLoader
import io.github.sceneview.model.ModelInstance
import io.github.sceneview.model.model
import io.github.sceneview.node.Node
import io.github.sceneview.node.MeshNode
import io.github.sceneview.rememberEngine
import io.github.sceneview.rememberEnvironmentLoader
import io.github.sceneview.rememberMaterialLoader
import io.github.sceneview.rememberModelInstance
import io.github.sceneview.rememberModelLoader
import io.github.sceneview.rememberRenderer
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.sqrt
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.PI

internal class SceneViewHost(
    override val context: Context,
    activity: Activity,
    private val lifecycle: Lifecycle,
    private val sessionFeatures: Set<Session.Feature>,
    private val requestedRearCameraId: String? = null,
    private val onSessionCreated: (Session) -> Unit = {},
    private val onSessionUpdated: (Session, Frame) -> Unit = { _, _ -> },
    private val onTrackingFailureChanged: (String?) -> Unit = {},
    private val onCoverageRendererMounted: (Boolean, Long) -> Unit = { _, _ -> },
    private val onTouch: (MotionEvent, List<PluginHitResult>) -> Unit = { _, _ -> },
    private val onNodeGesture: (String, String, PluginTransform?) -> Unit = { _, _, _ -> },
) : SceneViewCaptureHost {
    private val lifecycleOwner = activity as? LifecycleOwner
        ?: error("The Flutter activity must implement LifecycleOwner")
    private val viewModelStoreOwner = activity as? ViewModelStoreOwner
        ?: error("The Flutter activity must implement ViewModelStoreOwner")
    private val savedStateRegistryOwner = activity as? SavedStateRegistryOwner
        ?: error("The Flutter activity must implement SavedStateRegistryOwner")
    private data class NodeState(
        val record: PluginNodeRecord,
        val anchorId: String?,
    )

    private data class AnchorState(
        val record: PluginAnchorRecord,
        val anchor: Anchor,
    )

    private val ownership = SceneViewHostOwnership()
    private sealed interface CoveragePublication {
        data class Presentation(
            val descriptor: BoundedCoveragePresentation?,
            val config: PointCloudNativeConfig?,
        ) : CoveragePublication

        data class Legacy(
            val snapshot: CoveragePointRenderSnapshot?,
            val config: PointCloudNativeConfig?,
        ) : CoveragePublication
    }

    private val coveragePublications = CoverageRendererPublicationMailbox<CoveragePublication>()
    private val coveragePresentationRefreshPending = AtomicBoolean()
    private val coverageUploadFramePending = AtomicBoolean()
    private var rendererMainApplyAttribution: RendererMainApplyAttribution? = null
    private val nodes = mutableStateMapOf<String, NodeState>()
    private val anchors = mutableStateMapOf<String, AnchorState>()
    private val detectedPlanes = mutableStateMapOf<Plane, Unit>()
    private val configState = mutableStateOf(defaultConfig())
    // The Compose tree only needs to know whether a point mesh is mounted and
    // which fixed-capacity resources it owns. Individual point snapshots are
    // applied directly to the retained mesh; making the whole ARSceneView
    // recompose at the acquisition rate causes camera/overlay frame jitter.
    private val coverageRenderConfig = mutableStateOf<PointCloudNativeConfig?>(null)
    private val coverageMeshRef = AtomicReference<CoveragePointMeshBinding?>()
    /** Owner lifetime key; source renderer generations remain upstream-owned. */
    private val coverageResourceEpoch = mutableStateOf(0L)
    private val sharedCameraLifecycleGate = SharedCameraSceneLifecycleGate(
        Session.Feature.SHARED_CAMERA in sessionFeatures,
    )
    private val sessionRef = AtomicReference<Session?>()
    private val frameRef = AtomicReference<Frame?>()
    private val visibilityGridDepthModeCache = VisibilityGridDepthModeCache()
    private val engineRef = AtomicReference<Engine?>()
    private val cameraStreamRef = AtomicReference<ARCameraStream?>()
    private val debugGapTimingEnabled =
        context.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0
    private val frameCadenceTracker = FrameCadenceTracker(debugGapTimingEnabled = debugGapTimingEnabled)
    private val cameraFrameCadenceTracker = FrameCadenceTracker(debugGapTimingEnabled = debugGapTimingEnabled)
    @Volatile private var configuredCameraFpsLower = 0
    @Volatile private var configuredCameraFpsUpper = 0
    private val rendererTelemetry = RendererTelemetry()
    private val rendererAllocationLedger = CoverageRendererAllocationLedger(rendererTelemetry)
    private val coverageResourceFactory = CoverageRendererResourceFactory(
        onAcquisition = rendererTelemetry::recordResourceAcquisition,
        onReplacement = rendererTelemetry::recordResourceReplacement,
        onDisposal = rendererTelemetry::recordResourceDisposal,
        onDisposed = { token ->
            token?.let { coverageRendererOwner.markResourceReleased(it) }
            token?.let { rendererTelemetry.clearResidentPresentation(it) }
        },
        admit = { mode, _ ->
            rendererAllocationLedger.admitResourceReplacement(
                mode,
                sourceCapacity = coverageRendererOwner.sourceRowCount()
                    ?: CoverageRendererLimits.presentationCapacity(mode),
                retainedCount = coverageRendererOwner.sourceRowCount()
                    ?: CoverageRendererLimits.presentationCapacity(mode),
            )
        },
        onClearFirst = { transition ->
            transition.token?.let { coverageRendererOwner.markResourceFailure(it) }
                ?: coverageRendererOwner.markResourceFailure(transition.rendererGeneration)
            coverageMeshRef.get()?.disposeForReplacement()
        },
        onCreated = { transition ->
            transition.token?.let { coverageRendererOwner.markResourceMounted(it) }
                ?: coverageRendererOwner.markResourceMounted(transition.rendererGeneration)
        },
        onCreationFailure = { transition ->
            rendererTelemetry.recordResourceFailure()
            if (transition.admission.strategy == CoverageRendererTransitionStrategy.CLEAR_FIRST ||
                !transition.hadActiveResource
            ) {
                rendererAllocationLedger.releaseRendererResources()
                transition.token?.let { coverageRendererOwner.markResourceFailure(it) }
                    ?: coverageRendererOwner.markResourceFailure(transition.rendererGeneration)
            } else {
                transition.previousToken?.let { coverageRendererOwner.restoreResourceToken(it) }
            }
        },
    )
    /** The host owns the sole V2 coverage renderer owner for this SceneView. */
    internal val coverageRendererOwner: NativeCoverageRendererOwner =
        NativeCoverageRendererOwner(
            onControlsChanged = { controls ->
                transitionCoverageControls(controls)
            },
            onPresentationChanged = { _, _, _ -> requestCoveragePresentationRefresh() },
            onPresentationDescriptorChanged = { _, _, _ -> requestCoveragePresentationRefresh() },
            onResourceLifecycleChanged = { token, mounted ->
                if (!mounted) rendererTelemetry.clearResidentPresentation(token)
            },
            worldToScreen = CoverageWorldToScreenProjection(::projectCoveragePoint),
            onSelectionChanged = rendererTelemetry::recordSelectionChurn,
        )

    private fun projectCoveragePoint(x: Float, y: Float, z: Float): CoverageScreenPoint? {
        val frame = frameRef.get() ?: return null
        if (composeView.width <= 0 || composeView.height <= 0) return null
        val view = FloatArray(16)
        val projection = FloatArray(16)
        val camera = frame.camera
        runCatching {
            camera.getViewMatrix(view, 0)
            camera.getProjectionMatrix(projection, 0, 0.01f, 100f)
        }.getOrNull() ?: return null
        val viewProjection = FloatArray(16)
        Matrix.multiplyMM(viewProjection, 0, projection, 0, view, 0)
        val clip = FloatArray(4)
        Matrix.multiplyMV(clip, 0, viewProjection, 0, floatArrayOf(x, y, z, 1f), 0)
        val w = clip[3]
        if (!w.isFinite() || w <= 0f) return null
        val ndcX = clip[0] / w
        val ndcY = clip[1] / w
        val depth = clip[2] / w
        if (!ndcX.isFinite() || !ndcY.isFinite() || !depth.isFinite() ||
            depth < -1f || depth > 1f
        ) return null
        return CoverageScreenPoint(
            xPx = (ndcX + 1f) * 0.5f * composeView.width,
            yPx = (1f - ndcY) * 0.5f * composeView.height,
            depth = depth,
        )
    }
    // A PlatformView replacement must not overlap the outgoing Compose-owned
    // ARCore/Filament session. See [SceneViewSessionLease].
    private val sceneSessionGeneration = sceneSessionGenerationCounter.incrementAndGet()
    private val ownsSceneSession = mutableStateOf(false)
    // ARSceneView's frame coroutine must observe a pause before Compose
    // releases its Filament renderer. The activity lifecycle remains the
    // source of truth, while this owner provides the ordered teardown fence.
    private val rendererCallbackDepth = AtomicInteger()
    private val sceneRenderLifecycle = SceneViewRenderLifecycle(lifecycle) { detail ->
        Log.i(
            "SceneViewHost",
            "[RENDERER-LIFETIME] generation=$sceneSessionGeneration lifecycle $detail",
        )
    }
    private val compositionHandler = Handler(Looper.getMainLooper())
    private var disposed = false
    @Volatile private var futureResumesBlocked = false
    // SceneView dispatches session updates on its render callback while the
    // platform channel pauses from the Android main thread. A volatile gate
    // prevents a stale read from admitting extra upload frames after pause.
    @Volatile private var rendererPaused = false
    private val rendererPageFrameScheduler by lazy {
        RendererPageFrameScheduler { work ->
            composeView.post {
                Choreographer.getInstance().postFrameCallback { work() }
            }
        }
    }
    private val replaySettledTextureResize: Runnable = Runnable {
        if (disposed) return@Runnable
        val textureView = composeView.findTextureView() ?: return@Runnable
        if (textureView.width <= 0 || textureView.height <= 0) return@Runnable
        val surfaceTexture = textureView.surfaceTexture ?: return@Runnable
        val surfaceTextureListener =
            textureView.surfaceTextureListener ?: return@Runnable
        Log.i(
            "SceneViewHost",
            "Replaying TextureView surface resize at " +
                "${textureView.width}x${textureView.height}",
        )
        surfaceTextureListener.onSurfaceTextureSizeChanged(
            surfaceTexture,
            textureView.width,
            textureView.height,
        )
    }

    private val gestureListener = object : GestureDetector.SimpleOnGestureListener() {
        override fun onSingleTapConfirmed(e: MotionEvent, node: Node?) {
            // Tap identity is emitted from ARSceneView.onTouchEvent, whose
            // picked-node argument is reliable through Flutter PlatformView.
        }

        override fun onMoveBegin(detector: MoveGestureDetector, e: MotionEvent, node: Node?) {
            if (configState.value.handlePans) {
                node?.pluginGestureTarget()?.let { target ->
                    onNodeGesture("panStart", target.first, null)
                }
            }
        }

        override fun onMove(detector: MoveGestureDetector, e: MotionEvent, node: Node?) {
            if (configState.value.handlePans) {
                node?.pluginGestureTarget()?.let { target ->
                    onNodeGesture("panChange", target.first, null)
                }
            }
        }

        override fun onMoveEnd(detector: MoveGestureDetector, e: MotionEvent, node: Node?) {
            if (configState.value.handlePans) {
                node?.pluginGestureTarget()?.let { target ->
                    onNodeGesture("panEnd", target.first, target.second.pluginTransform())
                }
            }
        }

        override fun onRotateBegin(detector: RotateGestureDetector, e: MotionEvent, node: Node?) {
            if (configState.value.handleRotation) {
                node?.pluginGestureTarget()?.let { target ->
                    onNodeGesture("rotationStart", target.first, null)
                }
            }
        }

        override fun onRotate(detector: RotateGestureDetector, e: MotionEvent, node: Node?) {
            if (configState.value.handleRotation) {
                node?.pluginGestureTarget()?.let { target ->
                    onNodeGesture("rotationChange", target.first, null)
                }
            }
        }

        override fun onRotateEnd(detector: RotateGestureDetector, e: MotionEvent, node: Node?) {
            if (configState.value.handleRotation) {
                node?.pluginGestureTarget()?.let { target ->
                    onNodeGesture("rotationEnd", target.first, target.second.pluginTransform())
                }
            }
        }
    }

    private val composeView: ComposeView = ComposeView(context).apply {
        // Flutter can detach and reattach a platform view while Camera2 is
        // switching the shared-camera surface. Compose's default strategy
        // disposes the entire tree on that transient detach, which destroys
        // Filament's Engine while ARSceneView still has a frame callback
        // queued on BroadcastFrameClock. Keep the renderer tree owned by the
        // activity lifecycle; dispose() remains the explicit terminal fence.
        setViewCompositionStrategy(sceneViewCompositionStrategy())
        addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(view: View) {
                sceneRenderLifecycle.resumeAfterTransientDetach()
            }

            override fun onViewDetachedFromWindow(view: View) {
                // A platform-view detach can race a Compose frame callback.
                // Pause the ARSceneView loop before a transient detach can
                // release or replace any surface-owned Filament resources.
                sceneRenderLifecycle.pauseForTeardown()
            }
        })
        addOnLayoutChangeListener { _, left, top, right, bottom,
            oldLeft, oldTop, oldRight, oldBottom ->
            val newWidth = right - left
            val newHeight = bottom - top
            val oldWidth = oldRight - oldLeft
            val oldHeight = oldBottom - oldTop
            if (oldWidth > 0 &&
                oldHeight > 0 &&
                (newWidth != oldWidth || newHeight != oldHeight)
            ) {
                // Flutter's texture-layer platform view can resize this Compose
                // host without forwarding the corresponding size callback to
                // SceneView's nested TextureView. Filament recreates its swap
                // chain from that callback, so replay it after layout settles.
                removeCallbacks(replaySettledTextureResize)
                postDelayed(replaySettledTextureResize, 100)
            }
        }
        setViewTreeLifecycleOwner(lifecycleOwner)
        setViewTreeViewModelStoreOwner(viewModelStoreOwner)
        setViewTreeSavedStateRegistryOwner(savedStateRegistryOwner)
        setContent {
            DisposableEffect(sceneSessionGeneration) {
                rendererProvenance("composition-enter")
                sceneSessionLease.request(sceneSessionGeneration) {
                    rendererProvenance("session-lease-granted")
                    if (disposed) {
                        sceneSessionLease.releaseOrCancel(sceneSessionGeneration)
                    } else {
                        ownsSceneSession.value = true
                    }
                }
                onDispose {
                    rendererProvenance("composition-dispose")
                    // Compose disposes nested ARSceneView effects before this
                    // host effect, so releasing here hands off only after the
                    // outgoing ARCore and Filament resources are gone.
                    sceneSessionLease.releaseOrCancel(sceneSessionGeneration)
                }
            }
            if (!ownsSceneSession.value) return@setContent

            val engine = rememberEngine()
            // Keep Renderer ownership one composition level above ARSceneView.
            // Its frame coroutine is then cancelled with the child content
            // before this parent-owned renderer DisposableEffect is released.
            val renderer = rememberRenderer(engine)
            DisposableEffect(engine, renderer) {
                rendererProvenance("renderer-effect-enter", engine, renderer)
                onDispose {
                    rendererProvenance("renderer-effect-dispose", engine, renderer)
                }
            }
            val modelLoader = rememberModelLoader(engine)
            val materialLoader = rememberMaterialLoader(engine)
            val environmentLoader = rememberEnvironmentLoader(engine)
            val cameraStream = rememberARCameraStream(materialLoader)
            val config = configState.value
            val environment = remember(environmentLoader) {
                checkNotNull(
                    environmentLoader.createHDREnvironment(
                        assetFileLocation = "environments/evening_meadow_2k.hdr",
                        createSkybox = false,
                    ),
                ) { "Unable to load the Capture3D HDR environment" }
            }
            engineRef.set(engine)
            cameraStreamRef.set(cameraStream)
            DisposableEffect(engine, cameraStream) {
                onDispose {
                    cameraStreamRef.compareAndSet(cameraStream, null)
                    engineRef.compareAndSet(engine, null)
                }
            }

            ARSceneView(
                modifier = Modifier.fillMaxSize(),
                // A nested SurfaceView escapes Flutter's platform-view
                // composition and covers the app's capture controls. Keep the
                // renderer inline so camera content and Flutter overlays are
                // composited correctly on physical devices.
                surfaceType = SurfaceType.TextureSurface,
                engine = engine,
                modelLoader = modelLoader,
                materialLoader = materialLoader,
                environmentLoader = environmentLoader,
                environment = environment,
                cameraStream = cameraStream,
                renderer = renderer,
                lifecycle = sceneRenderLifecycle.lifecycle,
                sessionFeatures = sessionFeatures,
                planeFindingMode = config.planeFindingMode,
                cloudAnchorMode = if (config.cloudAnchorEnabled) {
                    Config.CloudAnchorMode.ENABLED
                } else {
                    Config.CloudAnchorMode.DISABLED
                },
                planeRenderer = config.showPlanes && config.customPlaneTexturePath == null,
                sessionConfiguration = { session, arConfig ->
                    val requestedId = requestedRearCameraId ?: session.cameraConfig.cameraId
                    val matchingConfigs =
                        session.getSupportedCameraConfigs(CameraConfigFilter(session))
                            .filter {
                                it.cameraId == requestedId &&
                                    it.facingDirection == CameraConfig.FacingDirection.BACK
                            }
                    val selectedConfig =
                        preferHighestFpsConfig(matchingConfigs) { it.fpsRange.upper }
                    if (selectedConfig == null) {
                        Log.w("SceneViewHost", "Requested ARCore rear camera $requestedId is unavailable")
                    } else {
                        session.cameraConfig = selectedConfig
                        Log.i(
                            "SceneViewHost",
                            "Selected ARCore rear camera $requestedId at " +
                                "${selectedConfig.fpsRange} fps",
                        )
                    }
                    val activeCameraConfig = selectedConfig ?: session.cameraConfig
                    configuredCameraFpsLower = activeCameraConfig.fpsRange.lower
                    configuredCameraFpsUpper = activeCameraConfig.fpsRange.upper
                    arConfig.depthMode =
                        visibilityGridDepthModeCache.configure(
                            session,
                            session::isDepthModeSupported,
                        )
                    arConfig.instantPlacementMode = Config.InstantPlacementMode.DISABLED
                    arConfig.lightEstimationMode = Config.LightEstimationMode.ENVIRONMENTAL_HDR
                    arConfig.focusMode = Config.FocusMode.AUTO
                    arConfig.planeFindingMode = config.planeFindingMode
                    arConfig.cloudAnchorMode = if (config.cloudAnchorEnabled) {
                        Config.CloudAnchorMode.ENABLED
                    } else {
                        Config.CloudAnchorMode.DISABLED
                    }
                },
                onSessionCreated = { session ->
                    sessionRef.set(session)
                    onSessionCreated(session)
                },
                onSessionResumed = { session ->
                    sessionRef.set(session)
                    if (sharedCameraLifecycleGate.shouldPauseSceneViewResume()) {
                        session.pause()
                    }
                },
                onSessionUpdated = { session, frame ->
                    rendererCallbackDepth.incrementAndGet()
                    try {
                        // ARCore can deliver an already-queued callback while
                        // Session.pause() is completing. Keep that callback out
                        // of the renderer cadence contract once pause has been
                        // acknowledged to Flutter.
                        if (!rendererPaused) {
                            // Upload pages are renderer-frame work, not callback
                            // work. Admit at most one bounded page for the active
                            // mesh after resetting this frame's shared ledger.
                            rendererTelemetry.beginRendererFrame()
                            consumeCoveragePublications()
                            coverageUploadFramePending.set(false)
                            coverageMeshRef.get()?.onRendererFrame()
                            val callbackArrivalNs = System.nanoTime()
                            frameCadenceTracker.record(callbackArrivalNs)
                            cameraFrameCadenceTracker.record(frame.timestamp, callbackArrivalNs)
                        }
                        sessionRef.set(session)
                        frameRef.set(frame)
                        frame.getUpdatedTrackables(Plane::class.java).forEach { plane ->
                            if (plane.subsumedBy == null &&
                                plane.trackingState != com.google.ar.core.TrackingState.STOPPED
                            ) {
                                detectedPlanes[plane] = Unit
                            } else {
                                detectedPlanes.remove(plane)
                            }
                        }
                        onSessionUpdated(session, frame)
                    } finally {
                        rendererCallbackDepth.decrementAndGet()
                    }
                },
                onTrackingFailureChanged = { failure ->
                    onTrackingFailureChanged(failure?.name)
                },
                onTouchEvent = { event, touchedNode ->
                    val hits = if (event.action == MotionEvent.ACTION_UP) {
                        hitTest(event.x, event.y)
                    } else {
                        emptyList()
                    }
                    onTouch(event, hits)
                    if (event.action == MotionEvent.ACTION_UP && config.handleTaps) {
                        touchedNode?.node?.pluginGestureTarget()?.let { target ->
                            onNodeGesture("tap", target.first, null)
                        }
                    }
                    false
                },
                onGestureListener = gestureListener,
            ) {
                if (config.showWorldOrigin) {
                    val red = remember(materialLoader) {
                        materialLoader.createUnlitColorInstance(android.graphics.Color.RED)
                    }
                    val green = remember(materialLoader) {
                        materialLoader.createUnlitColorInstance(android.graphics.Color.GREEN)
                    }
                    val blue = remember(materialLoader) {
                        materialLoader.createUnlitColorInstance(android.graphics.Color.BLUE)
                    }
                    DisposableEffect(red, green, blue) {
                        onDispose {
                            materialLoader.destroyMaterialInstance(red)
                            materialLoader.destroyMaterialInstance(green)
                            materialLoader.destroyMaterialInstance(blue)
                        }
                    }
                    Node {
                        CylinderNode(
                            radius = 0.005f,
                            height = 0.1f,
                            materialInstance = red,
                            position = Position(x = 0.05f),
                            rotation = Rotation(z = 90f),
                        )
                        CylinderNode(
                            radius = 0.005f,
                            height = 0.1f,
                            materialInstance = green,
                            position = Position(y = 0.05f),
                        )
                        CylinderNode(
                            radius = 0.005f,
                            height = 0.1f,
                            materialInstance = blue,
                            position = Position(z = 0.05f),
                            rotation = Rotation(x = 90f),
                        )
                    }
                }
                config.customPlaneTexturePath?.takeIf { config.showPlanes }?.let { texturePath ->
                    detectedPlanes.keys.forEach { plane ->
                        PlaneNode(plane = plane) {
                            ImageNode(
                                imageFileLocation = texturePath,
                                size = Size(x = plane.extentX, z = plane.extentZ),
                            )
                        }
                    }
                }
                val coverage = coverageRenderConfig.value
                if (coverage != null && shouldComposeCoverageRenderer(coverage)) {
                    key(
                        coverage.voxelRenderMode,
                        coverage.voxelSizeMeters,
                        coverage.cubeSizeFactor,
                        coverage.pointSizePx,
                        coverage.rendererGeneration,
                        coverageResourceEpoch.value,
                    ) {
                        when (coverage.voxelRenderMode) {
                            VoxelRenderMode.POINTS -> {
                                val token = coverageRendererOwner.issueResourceToken()
                                CoverageActivePointNode(engine, materialLoader, rendererTelemetry, coverage, token)
                                    ?.let { active ->
                                        NodeLifecycle(active.node) {
                                            CoverageActiveBindingEffect(active.binding, coverage, token, coverageMeshRef, onCoverageRendererMounted)
                                        }
                                    }
                            }
                            VoxelRenderMode.CENTROIDS -> {
                                val token = coverageRendererOwner.issueResourceToken()
                                CoverageActiveCentroidNode(engine, materialLoader, rendererTelemetry, coverage, token)
                                    ?.let { active ->
                                        NodeLifecycle(active.node) {
                                            CoverageActiveBindingEffect(active.binding, coverage, token, coverageMeshRef, onCoverageRendererMounted)
                                        }
                                    }
                            }
                            VoxelRenderMode.CUBES -> {
                                val token = coverageRendererOwner.issueResourceToken()
                                CoverageActiveCubeNode(engine, materialLoader, rendererTelemetry, coverage, token)
                                    ?.let { active ->
                                        NodeLifecycle(active.node) {
                                            CoverageActiveBindingEffect(active.binding, coverage, token, coverageMeshRef, onCoverageRendererMounted)
                                        }
                                    }
                            }
                        }
                    }
                }
                if (coverage == null && config.showFeaturePoints) {
                    val pointMaterial = remember(materialLoader) {
                        materialLoader.createUnlitColorInstance(android.graphics.Color.CYAN)
                    }
                    DisposableEffect(pointMaterial) {
                        onDispose { materialLoader.destroyMaterialInstance(pointMaterial) }
                    }
                    val pointCloud = rememberPointCloud(materialInstance = pointMaterial)
                    PointCloudNode(pointCloud)
                }
                anchors.values.forEach { anchorState ->
                    AnchorNode(anchor = anchorState.anchor) {
                        nodes.values
                            .filter { it.anchorId == anchorState.record.id }
                            .forEach { state ->
                                key(state.record.id) {
                                    val instance = rememberPluginModelInstance(modelLoader, state.record)
                                    instance?.let {
                                        val parts = sceneParts(state.record.transform)
                                        ModelNode(
                                            modelInstance = it,
                                            position = parts.position,
                                            rotation = parts.rotation,
                                            scale = parts.scale,
                                            isEditable = config.handlePans || config.handleRotation,
                                            apply = { name = state.record.id },
                                        )
                                    }
                                }
                            }
                    }
                }
                nodes.values.filter { it.anchorId == null }.forEach { state ->
                    key(state.record.id) {
                        val instance = rememberPluginModelInstance(modelLoader, state.record)
                        instance?.let {
                            val parts = sceneParts(state.record.transform)
                            ModelNode(
                                modelInstance = it,
                                position = parts.position,
                                rotation = parts.rotation,
                                scale = parts.scale,
                                isEditable = config.handlePans || config.handleRotation,
                                apply = { name = state.record.id },
                            )
                        }
                    }
                }
            }
        }
    }

    private val compositionDisposalGate = SceneViewCompositionDisposalGate(
        scheduleOnNextFrame = { work ->
            // The host may already be detached when the explicit terminal
            // fence runs. Schedule through the main looper instead of View.post
            // so the one-frame teardown still executes in that case.
            compositionHandler.post {
                Choreographer.getInstance().postFrameCallback { work() }
            }
        },
        disposeComposition = {
            rendererProvenance("composition-dispose-run")
            composeView.disposeComposition()
            (composeView.parent as? ViewGroup)?.removeView(composeView)
            sceneRenderLifecycle.destroyAfterComposition()
        },
    )
    private val parentLifecycleTeardownObserver: LifecycleEventObserver =
        LifecycleEventObserver { _, event ->
            if (event != Lifecycle.Event.ON_DESTROY) return@LifecycleEventObserver
            rendererProvenance("parent-lifecycle-destroy")
            coveragePublications.close()
            coveragePresentationRefreshPending.set(false)
            cancelCoverageUploadFrame()
            // Dispose the child ARSceneView composition before destroying the
            // host-owned renderer. This ordering is required when the activity
            // lifecycle destroys the view tree without an explicit Flutter dispose.
            composeView.disposeComposition()
            (composeView.parent as? ViewGroup)?.removeView(composeView)
            sceneRenderLifecycle.destroyAfterComposition()
            lifecycle.removeObserver(parentLifecycleTeardownObserver)
        }

    init {
        lifecycle.addObserver(parentLifecycleTeardownObserver)
        ownership.onCreate()
    }

    val view: View
        get() = composeView

    override val activeSession: Session?
        get() = sessionRef.get()

    val latestFrame: Frame?
        get() = frameRef.get()

    override val cameraTextureIds: IntArray
        get() = cameraStreamRef.get()?.cameraTextureIds?.copyOf() ?: intArrayOf()

    override val engine: Engine
        get() = checkNotNull(engineRef.get()) { "SceneView engine is not ready" }

    override val cameraTexture: Texture?
        get() = cameraStreamRef.get()?.cameraTexture

    override fun destroyCaptureStream(stream: Stream): Boolean {
        val engine = engineRef.get() ?: return false
        return runCatching {
            engine.destroyStream(stream)
            true
        }.getOrDefault(false)
    }

    fun configure(config: PluginSessionConfig) {
        checkNotDisposed()
        configState.value = config
    }

    /**
     * Production V2 boundary: only immutable bounded metadata crosses from
     * the canonical projection. Meshes borrow <=512-row pages from it while
     * they stage an upload; the legacy snapshot method remains for raw tests.
     */
    fun updateCoveragePresentation(
        descriptor: BoundedCoveragePresentation?,
        config: PointCloudNativeConfig?,
    ) {
        if (coveragePublications.offer(
                CoveragePublication.Presentation(descriptor, config),
                clearsRenderer = descriptor == null || config == null,
            )
        ) requestCoveragePublicationFrame()
    }

    private fun applyCoveragePresentation(
        descriptor: BoundedCoveragePresentation?,
        config: PointCloudNativeConfig?,
    ) {
        checkCoverageRendererThread()
        if (descriptor == null || config == null) {
            coverageRendererOwner.clearLatest()
            coverageMeshRef.get()?.disposeForReplacement()
            coverageResourceFactory.clear()
            coverageRenderConfig.value = null
            rendererAllocationLedger.clearCoverageState()
            rendererTelemetry.clearResidentPresentation()
            return
        }
        val status = coverageRendererOwner.status()
        val controls = if (coverageRendererOwner.controlsConfigured()) {
            CoverageRendererControls(status.visible, status.mode, status.palette)
        } else {
            CoverageRendererControls(
                visible = config.enabled,
                mode = descriptor.mode,
                palette = descriptor.palette,
            )
        }
        val effectiveConfig = config.copy(
            enabled = controls.visible,
            voxelRenderMode = controls.mode.toVoxelRenderMode(),
        )
        val current = coverageRenderConfig.value
        val visualChange = current == null ||
            current.renderCapacity != effectiveConfig.renderCapacity ||
            current.pointSizePx != effectiveConfig.pointSizePx ||
            current.voxelRenderMode != effectiveConfig.voxelRenderMode ||
            current.voxelSizeMeters != effectiveConfig.voxelSizeMeters ||
            current.cubeSizeFactor != effectiveConfig.cubeSizeFactor
        val requiresReplacement = current == null || visualChange ||
            current.rendererGeneration != effectiveConfig.rendererGeneration
        val admission = if (requiresReplacement) {
            rendererAllocationLedger.admitResourceReplacement(
                effectiveConfig.voxelRenderMode,
                sourceCapacity = descriptor.sourceCapacity,
                retainedCount = descriptor.count,
            )
        } else null
        if (admission?.strategy == CoverageRendererTransitionStrategy.REJECT) return
        val install = coverageRendererOwner.installPresentation(descriptor, effectiveConfig)
        if (install.stale) return
        if (!coverageRendererOwner.controlsConfigured()) {
            val receipt = coverageRendererOwner.setControls(controls)
            if (!receipt.accepted && !receipt.rendererUnavailable) return
        }
        installAcceptedCoverageOwnership(
            mode = effectiveConfig.voxelRenderMode,
            fallbackSourceCapacity = descriptor.sourceCapacity,
        )
        if (requiresReplacement) {
            checkNotNull(admission)
            if (current != null && admission.strategy == CoverageRendererTransitionStrategy.CLEAR_FIRST) {
                coverageRendererOwner.currentResourceToken()?.let { coverageRendererOwner.markResourceFailure(it) }
                coverageMeshRef.get()?.disposeForReplacement()
                coverageResourceFactory.clear()
                rendererAllocationLedger.releaseRendererResources()
            }
            coverageRenderConfig.value = effectiveConfig
            coverageRendererOwner.requestResourceReplacement()
            coverageRendererOwner.issueResourceToken()?.let { coverageResourceEpoch.value = it.epoch }
        }
        coverageRendererOwner.refreshPresentation()
    }

    fun updateCoverageRenderer(
        snapshot: CoveragePointRenderSnapshot?,
        config: PointCloudNativeConfig?,
    ) {
        // The legacy adapter exposes arrays; give its delayed publication an
        // independent snapshot. V2 transfers only its immutable descriptor.
        if (coveragePublications.offer(
                CoveragePublication.Legacy(snapshot?.deepCopy(), config),
                clearsRenderer = snapshot == null || config == null,
            )
        ) requestCoveragePublicationFrame()
    }

    private fun applyCoverageRenderer(
        snapshot: CoveragePointRenderSnapshot?,
        config: PointCloudNativeConfig?,
    ) {
        checkCoverageRendererThread()
        if (snapshot == null || config == null) {
            coverageRendererOwner.clearLatest()
            coverageMeshRef.get()?.disposeForReplacement()
            coverageResourceFactory.clear()
            coverageRenderConfig.value = null
            rendererAllocationLedger.clearCoverageState()
            rendererTelemetry.clearResidentPresentation()
            return
        }

        val controls = if (coverageRendererOwner.controlsConfigured()) {
            val status = coverageRendererOwner.status()
            CoverageRendererControls(status.visible, status.mode, status.palette)
        } else {
            CoverageRendererControls(
                visible = config.enabled,
                mode = config.voxelRenderMode.toDefaultCoveragePresentationMode(),
                palette = CoverageRendererPalette.COVERAGE,
            )
        }
        val requestedConfig = config.copy(
            enabled = controls.visible,
            voxelRenderMode = controls.mode.toVoxelRenderMode(),
        )
        val current = coverageRenderConfig.value
        val visualChange = current == null ||
            current.renderCapacity != requestedConfig.renderCapacity ||
            current.pointSizePx != requestedConfig.pointSizePx ||
            current.voxelRenderMode != requestedConfig.voxelRenderMode ||
            current.voxelSizeMeters != requestedConfig.voxelSizeMeters ||
            current.cubeSizeFactor != requestedConfig.cubeSizeFactor
        // rendererGeneration belongs to the upstream semantic snapshot. GPU
        // lifetimes are fenced independently by the owner's resource epoch.
        val effectiveConfig = requestedConfig
        val requiresReplacement = current == null || visualChange ||
            current.rendererGeneration != effectiveConfig.rendererGeneration
        val admission = if (requiresReplacement) {
            val sourceRows = minOf(snapshot.capacity, snapshot.count)
            rendererAllocationLedger.admitResourceReplacement(
                effectiveConfig.voxelRenderMode,
                sourceCapacity = sourceRows,
                retainedCount = snapshot.count,
            )
        } else {
            null
        }
        // Admission must be non-mutating. Do not commit a semantic cut or
        // alter the current Compose config when its replacement cannot fit.
        if (admission?.strategy == CoverageRendererTransitionStrategy.REJECT) return
        val install = coverageRendererOwner.install(
            snapshot.toVisibilityRendererSnapshot(effectiveConfig),
        )
        if (install.stale) {
            return
        }
        if (!coverageRendererOwner.controlsConfigured()) {
            val controlReceipt = coverageRendererOwner.setControls(controls)
            if (!controlReceipt.accepted && !controlReceipt.rendererUnavailable) return
        }
        installAcceptedCoverageOwnership(
            mode = effectiveConfig.voxelRenderMode,
            fallbackSourceCapacity = minOf(snapshot.capacity, snapshot.count),
        )
        if (requiresReplacement) {
            checkNotNull(admission)
            if (current != null &&
                admission.strategy == CoverageRendererTransitionStrategy.CLEAR_FIRST
            ) {
                coverageRendererOwner.currentResourceToken()?.let {
                    coverageRendererOwner.markResourceFailure(it)
                }
                coverageMeshRef.get()?.disposeForReplacement()
                coverageResourceFactory.clear()
                rendererAllocationLedger.releaseRendererResources()
            }
            coverageRenderConfig.value = effectiveConfig
            coverageRendererOwner.requestResourceReplacement()
            coverageRendererOwner.issueResourceToken()?.let { coverageResourceEpoch.value = it.epoch }
        }
        coverageRendererOwner.refreshPresentation()
    }

    /**
     * Charges semantic renderer ownership only after the owner accepted a
     * non-stale install. The ledger setters are replacement-safe, so repeated
     * accepted updates do not accumulate duplicate ownership.
     */
    private fun installAcceptedCoverageOwnership(
        mode: VoxelRenderMode,
        fallbackSourceCapacity: Int,
    ) {
        val descriptor = coverageRendererOwner.presentationDescriptor()
        rendererAllocationLedger.installPersistentCoverageStateForCapacity(
            presentationCapacity = descriptor?.capacity
                ?: CoverageRendererLimits.presentationCapacity(mode),
            sourceCapacity = descriptor?.sourceCapacity
                ?: coverageRendererOwner.sourceRowCount()
                ?: fallbackSourceCapacity,
        )
    }

    /**
     * Renderer controls are admitted only after the host has fenced the old
     * mesh and preflighted the replacement ledger. A failed preflight leaves
     * the owner controls and semantic cut unchanged.
     */
    private fun transitionCoverageControls(controls: CoverageRendererControls): Boolean {
        checkCoverageRendererThread()
        if (disposed) return false
        val current = coverageRenderConfig.value ?: run {
            rendererTelemetry.recordPresentation(controls.mode)
            return true
        }
        val nextMode = controls.mode.toVoxelRenderMode()
        val modeChanged = current.voxelRenderMode != nextMode
        return runCatching {
            if (modeChanged) {
                val admission = rendererAllocationLedger.admitResourceReplacement(
                    nextMode,
                    sourceCapacity = coverageRendererOwner.sourceRowCount()
                        ?: CoverageRendererLimits.presentationCapacity(nextMode),
                    retainedCount = coverageRendererOwner.sourceRowCount()
                        ?: CoverageRendererLimits.presentationCapacity(nextMode),
                )
                if (admission.strategy == CoverageRendererTransitionStrategy.REJECT) return@runCatching false
                if (admission.strategy == CoverageRendererTransitionStrategy.CLEAR_FIRST) {
                    coverageRendererOwner.currentResourceToken()?.let {
                        coverageRendererOwner.markResourceFailure(it)
                    }
                    coverageMeshRef.get()?.disposeForReplacement()
                    coverageResourceFactory.clear()
                    rendererAllocationLedger.releaseRendererResources()
                }
            }
            coverageRenderConfig.value = current.copy(
                enabled = controls.visible,
                voxelRenderMode = nextMode,
                rendererGeneration = current.rendererGeneration,
            )
            if (modeChanged) {
                coverageRendererOwner.issueResourceToken()?.let { coverageResourceEpoch.value = it.epoch }
            }
            rendererTelemetry.recordPresentation(controls.mode)
            true
        }.getOrElse { false }
    }

    private inline fun <T> createCoverageResource(
        create: () -> T,
    ): T? = runCatching { create() }
        .onFailure { rendererTelemetry.recordResourceFailure() }
        .getOrNull()

    fun resume() {
        checkNotDisposed()
        check(!futureResumesBlocked) { "SceneView host is shutting down" }
        rendererProvenance("resume")
        activeSession?.resume()
        recoverCoverageRenderer()
    }

    /** Rehydrates the latest committed renderer cut after a renderer-only loss. */
    fun recoverCoverageRenderer() {
        checkNotDisposed()
        check(!futureResumesBlocked) { "SceneView host is shutting down" }
        val recovery = coverageRendererOwner.resume()
        if (recovery.recovered) {
            coverageRenderConfig.value?.let { current ->
                // Force one Compose resource generation so the latest complete
                // owner snapshot is mounted after GPU loss.
                coverageRendererOwner.requestResourceReplacement()
                coverageRendererOwner.issueResourceToken()?.let { coverageResourceEpoch.value = it.epoch }
            }
        }
        rendererPaused = false
        requestCoveragePublicationFrame()
    }

    /** Arms one failed resource creation for the debug synthetic scene. */
    fun armDebugCoverageAllocationFailure() {
        checkNotDisposed()
        coverageResourceFactory.armDebugAllocationFailure()
    }

    /** Prevents new resume calls while a late in-flight ARCore resume drains. */
    fun blockFutureResumes() {
        futureResumesBlocked = true
    }

    fun pause() {
        if (!disposed) {
            rendererProvenance("pause")
            coverageRendererOwner.pause()
            rendererPaused = true
            coverageMeshRef.get()?.disposeForReplacement()
            coverageResourceFactory.clear()
            rendererAllocationLedger.releaseRendererResources()
            rendererTelemetry.clearResidentPresentation()
            activeSession?.pause()
        }
    }

    /**
     * Optional Activity/ComponentCallbacks2 seam for OS low-memory pressure.
     * Resource generations are released before the owner is fenced; the
     * semantic cut and controls remain eligible for a later remount.
     */
    fun onLowMemoryPressure() {
        if (disposed) return
        coverageResourceFactory.clear()
        coverageMeshRef.getAndSet(null)?.disposeForReplacement()
        rendererAllocationLedger.releaseRendererResources()
        rendererTelemetry.clearResidentPresentation()
        coverageRendererOwner.markLowMemoryPressure()
    }

    private fun CoveragePointRenderSnapshot.toVisibilityRendererSnapshot(
        config: PointCloudNativeConfig,
    ): VisibilityRendererSnapshot {
        // The projection borrower is the canonical source seam. Do not turn
        // its 100k cut into a second row list or deep-copied snapshot at the
        // host boundary; the owner streams it into the bounded selector.
        var bestIndex = -1
        var bestStyle: CoverageRendererStyleRowV1? = null
        repeat(count) { index ->
            if (styleRows.isEmpty()) return@repeat
            val style = CoverageRendererStyleRowV1.decode(
                styleRows,
                index * COVERAGE_RENDERER_STYLE_ROW_BYTES,
            )
            if (style.target == com.uhg0.ar_flutter_plugin_2.pointcloud.CoverageRendererTarget.NONE) {
                return@repeat
            }
            val currentBest = bestStyle
            if (currentBest == null || compareCoverageStyles(style, currentBest, surfaceIds[index], surfaceIds[bestIndex])) {
                bestIndex = index
                bestStyle = style
            }
        }
        val targetSurfaceId = bestStyle?.let { surfaceIds[bestIndex] }
        val targetDirectionIndex = bestStyle?.directionBin?.takeUnless { it == 0xff }
        return VisibilityRendererSnapshot(
            bindingGeneration = bindingGeneration,
            groupGeneration = groupGeneration,
            rendererGeneration = config.rendererGeneration,
            transactionId = transactionId.takeIf { it > 0L } ?: (update?.geometryRevision ?: revision),
            geometryRevision = geometryRevision.takeIf { it > 0L } ?: (update?.geometryRevision ?: revision),
            styleRevision = styleRevision.takeIf { it > 0L } ?: (update?.visibilityRevision ?: revision),
            rows = emptyList(),
            renderSnapshot = null,
            rowCountOverride = count,
            sourceCapacity = capacity,
            sourceCount = count,
            update = update?.rangeOnly(),
            targetSurfaceId = targetSurfaceId,
            targetDirectionIndex = targetDirectionIndex,
        )
    }

    private fun compareCoverageStyles(
        first: CoverageRendererStyleRowV1,
        second: CoverageRendererStyleRowV1,
        firstSurfaceId: Long,
        secondSurfaceId: Long,
    ): Boolean {
        val firstTarget = first.target.code
        val secondTarget = second.target.code
        if (firstTarget != secondTarget) return firstTarget > secondTarget
        val firstNeed = when (first.coverage) {
            com.uhg0.ar_flutter_plugin_2.pointcloud.CoverageRendererCoverage.UNCOVERED -> 2
            com.uhg0.ar_flutter_plugin_2.pointcloud.CoverageRendererCoverage.PARTIAL -> 1
            com.uhg0.ar_flutter_plugin_2.pointcloud.CoverageRendererCoverage.COMPLETE -> 0
        }
        val secondNeed = when (second.coverage) {
            com.uhg0.ar_flutter_plugin_2.pointcloud.CoverageRendererCoverage.UNCOVERED -> 2
            com.uhg0.ar_flutter_plugin_2.pointcloud.CoverageRendererCoverage.PARTIAL -> 1
            com.uhg0.ar_flutter_plugin_2.pointcloud.CoverageRendererCoverage.COMPLETE -> 0
        }
        if (firstNeed != secondNeed) return firstNeed > secondNeed
        val firstResidency = first.residency.code
        val secondResidency = second.residency.code
        if (firstResidency != secondResidency) return firstResidency > secondResidency
        return firstSurfaceId < secondSurfaceId
    }

    /** O(1) ownership and bounded cadence checks for depth image intake. */
    fun depthIntakeFrameHealthy(): Boolean =
        frameCadenceTracker.healthyForDepthIntake() &&
            cameraFrameCadenceTracker.healthyForDepthIntake()

    fun rendererPerformanceSnapshot(
        beginMeasurementWindow: Boolean = false,
        captureDiagnosticTiming: Boolean = false,
        freezeDiagnosticTiming: Boolean = false,
        includeDiagnosticTiming: Boolean = false,
    ): Map<String, Any> {
        if (beginMeasurementWindow) {
            frameCadenceTracker.beginMeasurementWindow(captureDiagnosticTiming)
            cameraFrameCadenceTracker.beginMeasurementWindow(captureDiagnosticTiming)
            rendererMainApplyAttribution = (rendererMainApplyAttribution
                ?: RendererMainApplyAttribution()).also { it.reset() }
        }
        if (freezeDiagnosticTiming) {
            frameCadenceTracker.freezeDiagnosticTiming()
            cameraFrameCadenceTracker.freezeDiagnosticTiming()
        }
        val status = coverageRendererOwner.status()
        val cameraCadence = cameraFrameCadenceTracker.snapshot()
        val cameraMeasurement = if (cameraCadence.containsKey("measurementWindowSampleCount")) {
            mapOf(
                "cameraMeasurementWindowSampleCount" to
                    cameraCadence.getValue("measurementWindowSampleCount"),
                "cameraMeasurementWindowP99FrameIntervalMs" to
                    cameraCadence.getValue("measurementWindowP99FrameIntervalMs"),
                "cameraMeasurementWindowP99Complete" to
                    cameraCadence.getValue("measurementWindowP99Complete"),
                "cameraMeasurementWindowMaxFrameIntervalMs" to
                    cameraCadence.getValue("measurementWindowMaxFrameIntervalMs"),
                "cameraMeasurementWindowFrameGapsOver100Ms" to
                    cameraCadence.getValue("measurementWindowFrameGapsOver100Ms"),
                "cameraMeasurementWindowLastArrivalAgeMs" to
                    cameraCadence.getValue("measurementWindowLastArrivalAgeMs"),
                "cameraMeasurementWindowHasAdvancingFrame" to
                    cameraCadence.getValue("measurementWindowHasAdvancingFrame"),
            )
        } else {
            emptyMap<String, Any>()
        }
        return frameCadenceTracker.snapshot() + rendererTelemetry.snapshot() + mapOf(
            "cameraFrameSampleCount" to cameraCadence.getValue("sampleCount"),
            "cameraMedianFrameIntervalMs" to cameraCadence.getValue("medianFrameIntervalMs"),
            "cameraMedianFps" to cameraCadence.getValue("medianFps"),
            "cameraP95FrameIntervalMs" to cameraCadence.getValue("p95FrameIntervalMs"),
            "cameraP99FrameIntervalMs" to cameraCadence.getValue("p99FrameIntervalMs"),
            "cameraMaxFrameIntervalMs" to cameraCadence.getValue("maxFrameIntervalMs"),
            "cameraFrameGapsOver50Ms" to cameraCadence.getValue("frameGapsOver50Ms"),
            "cameraFrameGapsOver100Ms" to cameraCadence.getValue("frameGapsOver100Ms"),
            "cameraConfiguredFpsLower" to configuredCameraFpsLower,
            "cameraConfiguredFpsUpper" to configuredCameraFpsUpper,
            "deviceUptimeMs" to SystemClock.uptimeMillis(),
            "rendererUnavailable" to status.rendererUnavailable,
            "rendererPresentationMode" to status.mode.wireName,
            "rendererPresentationVisible" to status.visible,
            "rendererPresentationRowCount" to status.selectedRowCount,
            "rendererGeometryRevision" to status.geometryRevision,
            "rendererStyleRevision" to status.styleRevision,
        ) + cameraMeasurement + (rendererMainApplyAttribution?.snapshot()
            ?: RendererMainApplyAttribution.disabledSnapshot()) +
            (if (includeDiagnosticTiming && debugGapTimingEnabled) mapOf(
                "frameGapTiming" to mapOf(
                    "clock" to "androidMonotonic",
                    "rendererIntervalDomain" to "callbackArrival",
                    "cameraIntervalDomain" to "arCoreCameraTimestamp",
                    "renderer" to frameCadenceTracker.diagnosticTimingSnapshot(),
                    "camera" to cameraFrameCadenceTracker.diagnosticTimingSnapshot(),
                ),
            ) else emptyMap())
    }

    /** Fixed native renderer/page scalars for the visibility pressure receipt. */
    internal fun visibilityPressureSnapshot(): SceneRendererPressureSnapshot =
        SceneRendererPressureSnapshot(
            renderer = rendererTelemetry.pressureSnapshot(),
            pages = rendererPageFrameScheduler.pressureSnapshot(),
        )

    /**
     * Callback completion is a fence, not permission to upload inline. Resume
     * the active resource on a distinct Android render frame and fence stale
     * replacement callbacks by binding identity.
     */
    private fun requestCoverageUploadFrame(binding: CoveragePointMeshBinding) {
        if (coverageMeshRef.get() !== binding) return
        coverageUploadFramePending.set(true)
        requestCoveragePublicationFrame()
    }

    private fun requestCoveragePresentationRefresh() {
        if (coveragePublications.isClosed) return
        coveragePresentationRefreshPending.set(true)
        requestCoveragePublicationFrame()
    }

    private fun requestCoveragePublicationFrame() {
        rendererPageFrameScheduler.request {
            if (disposed || rendererPaused || coveragePublications.isClosed) return@request
            val uploadRequested = coverageUploadFramePending.getAndSet(false)
            if (!uploadRequested && !coveragePublications.hasPending &&
                !coveragePresentationRefreshPending.get()
            ) return@request
            rendererTelemetry.beginRendererFrame()
            consumeCoveragePublications()
            coverageMeshRef.get()?.onRendererFrame()
            frameCadenceTracker.record(System.nanoTime())
        }
    }

    private fun consumeCoveragePublications() {
        checkCoverageRendererThread()
        if (disposed || rendererPaused || coveragePublications.isClosed) return
        val pending = coveragePublications.take()
        if (pending == null && !coveragePresentationRefreshPending.get()) return
        val attribution = rendererMainApplyAttribution
        if (attribution == null) {
            applyPendingCoveragePublication(pending)
            return
        }
        val allocatedBefore = rendererMainApplyAllocatedBytes()
        Trace.beginSection(RendererMainApplyAttribution.TRACE_SECTION)
        val startedNanos = System.nanoTime()
        try {
            applyPendingCoveragePublication(pending)
        } finally {
            val elapsedNanos = (System.nanoTime() - startedNanos).coerceAtLeast(0L)
            Trace.endSection()
            attribution.record(elapsedNanos, allocatedBefore, rendererMainApplyAllocatedBytes())
        }
    }

    private fun rendererMainApplyAllocatedBytes(): Long =
        android.os.Debug.getRuntimeStat("art.gc.bytes-allocated")?.toLongOrNull() ?: -1L

    private fun applyPendingCoveragePublication(
        queued: CoverageRendererPublicationMailbox.Pending<CoveragePublication>?,
    ) {
        var fullRefresh = false
        queued?.let { pending ->
            fullRefresh = pending.coalesced
            // A clear is a generation boundary even when a newer cut replaces
            // it before the next frame. Skipped dirty spans require a full cut.
            if (pending.clearBeforeApply) applyCoveragePresentation(null, null)
            when (val publication = pending.value) {
                is CoveragePublication.Presentation -> {
                    val descriptor = publication.descriptor?.let {
                        if (pending.coalesced) it.withControls(
                            mode = it.mode,
                            fullRange = true,
                        ) else it
                    }
                    if (descriptor != null && publication.config != null) {
                        applyCoveragePresentation(descriptor, publication.config)
                    }
                }
                is CoveragePublication.Legacy -> {
                    val snapshot = publication.snapshot?.let {
                        if (pending.coalesced) it.copy(update = null) else it
                    }
                    if (snapshot != null && publication.config != null) {
                        applyCoverageRenderer(snapshot, publication.config)
                    }
                }
            }
        }
        if (!coveragePresentationRefreshPending.getAndSet(false)) return
        val status = coverageRendererOwner.status()
        val token = coverageRendererOwner.issueResourceToken() ?: return
        if (!coverageRendererOwner.acceptsResourceToken(token)) return
        coverageResourceEpoch.value = token.epoch
        rendererTelemetry.setResidentPresentation(
            token, status.mode, status.selectedRowCount, status.residentGlyphCount,
        )
        val binding = coverageMeshRef.get() ?: return
        if (fullRefresh) binding.requireFullRefresh()
        val descriptor = coverageRendererOwner.presentationDescriptor()
        if (descriptor != null) {
            binding.updateCoverageDescriptor(descriptor, status.mode.toVoxelRenderMode())
        } else {
            binding.updateCoverage(status.mode.toVoxelRenderMode())
        }
    }

    private fun checkCoverageRendererThread() {
        check(Looper.myLooper() == Looper.getMainLooper()) {
            "Coverage renderer resources belong to the Android main thread"
        }
    }

    private fun cancelCoverageUploadFrame() {
        coverageUploadFramePending.set(false)
        rendererPageFrameScheduler.cancel()
    }

    fun visibilityGridDepthMode(): Config.DepthMode =
        visibilityGridDepthModeCache.current()

    fun dispose() {
        if (!ownership.onDispose()) return
        rendererProvenance("dispose-request")
        disposed = true
        coveragePublications.close()
        coveragePresentationRefreshPending.set(false)
        rendererPaused = true
        sceneRenderLifecycle.pauseForTeardown()
        coverageMeshRef.getAndSet(null)?.disposeForReplacement()
        coverageResourceFactory.clear()
        coverageRendererOwner.dispose()
        rendererAllocationLedger.clearCoverageState()
        rendererTelemetry.clearResidentPresentation()
        futureResumesBlocked = true
        cancelCoverageUploadFrame()
        composeView.removeCallbacks(replaySettledTextureResize)
        nodes.clear()
        anchors.values.forEach { it.anchor.detach() }
        anchors.clear()
        detectedPlanes.clear()
        frameRef.set(null)
        sessionRef.set(null)
        visibilityGridDepthModeCache.reset()
        cameraStreamRef.set(null)
        engineRef.set(null)
        compositionDisposalGate.request()
    }

    /** Emits sparse lifetime evidence; this must never run from the frame hot path. */
    private fun rendererProvenance(
        event: String,
        engine: Engine? = engineRef.get(),
        renderer: Renderer? = null,
    ) {
        Log.i(
            "SceneViewHost",
            "[RENDERER-LIFETIME] event=$event " +
                "generation=$sceneSessionGeneration " +
                "parentState=${lifecycle.currentState} " +
                "renderState=${sceneRenderLifecycle.lifecycle.currentState} " +
                "attached=${composeView.isAttachedToWindow} " +
                "viewTreeOwner=${composeView.findViewTreeLifecycleOwner()?.let {
                    it.javaClass.name + "@" + System.identityHashCode(it)
                } ?: "none"} " +
                "engineId=${engine?.let(System::identityHashCode) ?: 0} " +
                "rendererId=${renderer?.let(System::identityHashCode) ?: 0} " +
                "callbackDepth=${rendererCallbackDepth.get()} " +
                "disposed=$disposed paused=$rendererPaused",
        )
    }

    fun addOrUpdateNode(node: PluginNodeRecord, anchorId: String? = null): Boolean {
        checkNotDisposed()
        if (anchorId != null && anchorId !in anchors) return false
        nodes[node.id] = NodeState(node, anchorId)
        return true
    }

    fun removeNode(nodeId: String): Boolean = nodes.remove(nodeId) != null

    fun addOrUpdateAnchor(anchor: PluginAnchorRecord): Boolean {
        checkNotDisposed()
        val session = activeSession ?: return false
        anchors.remove(anchor.id)?.anchor?.detach()
        anchors[anchor.id] = AnchorState(
            record = anchor,
            anchor = session.createAnchor(anchor.transform.toPose()),
        )
        return true
    }

    fun removeAnchor(anchorId: String): Boolean {
        val state = anchors.remove(anchorId) ?: return false
        state.anchor.detach()
        nodes.entries.removeAll { it.value.anchorId == anchorId }
        return true
    }

    fun hitTest(xPx: Float, yPx: Float): List<PluginHitResult> =
        latestFrame?.hitTest(xPx, yPx).orEmpty().map { it.toPluginHit() }

    fun snapshot(callback: (Result<ByteArray>) -> Unit) {
        val textureView = composeView.findTextureView()
        if (textureView == null || textureView.width <= 0 || textureView.height <= 0) {
            callback(Result.failure(IllegalStateException("SceneView surface is not ready")))
            return
        }
        Handler(Looper.getMainLooper()).post {
            val bitmap = textureView.bitmap
            if (bitmap == null) {
                callback(Result.failure(IllegalStateException("SceneView bitmap is not ready")))
                return@post
            }
            val bytes = ByteArrayOutputStream().use { output ->
                bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, output)
                output.toByteArray()
            }
            bitmap.recycle()
            callback(Result.success(bytes))
        }
    }

    override fun frameForCapture(): Frame? = latestFrame

    override fun prepareSharedCameraResume(session: Session) {
        // SceneView 4.21.2 keeps config stable across resume. Release the first-resume gate only
        // after SharedCameraManager has an active Camera2 repeating request.
        sharedCameraLifecycleGate.prepareSharedCameraResume()
    }

    private fun checkNotDisposed() = check(!disposed) { "SceneView host is disposed" }

    private data class TransformParts(
        val position: Position,
        val rotation: Rotation,
        val scale: Scale,
    )

    private fun sceneParts(transform: PluginTransform): TransformParts {
        val parts = transform.decompose()
        val q = parts.rotation
        val xRadians = atan2(
            2.0 * (q.w * q.x + q.y * q.z),
            1.0 - 2.0 * (q.x * q.x + q.y * q.y),
        )
        val yTerm = (2.0 * (q.w * q.y - q.z * q.x)).coerceIn(-1.0, 1.0)
        val yRadians = asin(yTerm)
        val zRadians = atan2(
            2.0 * (q.w * q.z + q.x * q.y),
            1.0 - 2.0 * (q.y * q.y + q.z * q.z),
        )
        return TransformParts(
            position = Position(
                parts.position.x.toFloat(),
                parts.position.y.toFloat(),
                parts.position.z.toFloat(),
            ),
            rotation = Rotation(
                (xRadians * 180.0 / PI).toFloat(),
                (yRadians * 180.0 / PI).toFloat(),
                (zRadians * 180.0 / PI).toFloat(),
            ),
            scale = Scale(
                parts.scale.x.toFloat(),
                parts.scale.y.toFloat(),
                parts.scale.z.toFloat(),
            ),
        )
    }

    private fun PluginTransform.toPose(): Pose {
        val parts = decompose()
        return Pose(
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

    private fun HitResult.toPluginHit(): PluginHitResult = PluginHitResult(
        type = when (trackable) {
            is Plane -> PluginHitType.PLANE
            is Point -> PluginHitType.POINT
            else -> PluginHitType.UNDEFINED
        },
        distanceMeters = distance.toDouble(),
        worldTransform = PluginTransform(DoubleArray(16).also { output ->
            val floats = FloatArray(16)
            hitPose.toMatrix(floats, 0)
            floats.forEachIndexed { index, value -> output[index] = value.toDouble() }
        }.toList()),
    )

    private fun View.findTextureView(): TextureView? {
        if (this is TextureView) return this
        if (this is ViewGroup) {
            repeat(childCount) { index ->
                getChildAt(index).findTextureView()?.let { return it }
            }
        }
        return null
    }

    private fun releaseCoverageResources(
        token: CoverageResourceToken?,
        resources: CoverageVoxelMeshResources,
    ) {
        if (token == null || !coverageResourceFactory.releaseResource(token)) {
            resources.destroy()
        }
    }

    private fun Node.pluginTransform(): PluginTransform = PluginTransform(
        transform.toFloatArray().map(Float::toDouble),
    )

    @Composable
    private fun CoverageActivePointNode(
        engine: Engine,
        materialLoader: MaterialLoader,
        telemetry: RendererTelemetry,
        coverage: PointCloudNativeConfig,
        token: CoverageResourceToken?,
    ): CoverageMeshAttachment? {
        val resources = remember(engine) {
            createCoverageResource {
                coverageResourceFactory.replacePoint(
                    VoxelRenderMode.POINTS,
                    CoverageRendererLimits.RAW_POINT_CAPACITY,
                    "coverage-points",
                    rendererGeneration = coverage.rendererGeneration,
                    token = token,
                    create = { _, capacity, owner -> CoveragePointMeshResources(engine, capacity, telemetry, owner) },
                    release = CoverageVoxelMeshResources::destroy,
                )
            }
        }
        if (resources == null) return null
        val material = remember(materialLoader) {
            materialLoader.createMaterial("materials/coverage_points.filamat")
        }
        val materialInstance = remember(materialLoader, material) {
            materialLoader.createInstance(material)
        }
        val binding = remember(resources, materialInstance, coverage.pointSizePx) {
            CoveragePointMeshBinding(
                mode = VoxelRenderMode.POINTS,
                target = CoverageMeshTarget(resources, materialInstance),
                pointSizePx = coverage.pointSizePx,
                readCoverageSnapshot = coverageRendererOwner::presentationSnapshot,
                readCoverageDescriptor = coverageRendererOwner::presentationDescriptor,
                resourceToken = token,
                acceptsResourceToken = coverageRendererOwner::acceptsResourceToken,
                requestRendererFrame = ::requestCoverageUploadFrame,
                cancelRendererFrame = ::cancelCoverageUploadFrame,
                releaseResources = { releaseCoverageResources(token, resources) },
                onUploadFailure = {
                    rendererTelemetry.recordResourceFailure()
                    token?.let { coverageRendererOwner.markUploadFailure(it) }
                },
            )
        }
        val node = remember(engine, resources, material, materialInstance) {
            CoverageOwnedMeshNode(
                engine,
                resources,
                CoveragePointMeshResources.DEFAULT_BOUNDING_BOX,
                materialInstance,
                beforeDestroy = binding::clearNode,
                releaseResources = { releaseCoverageResources(token, resources) },
            ) {
                materialLoader.destroyMaterialInstance(materialInstance)
                materialLoader.destroyMaterial(material)
            }.also(binding::setNode)
        }
        return CoverageMeshAttachment(node, binding)
    }

    @Composable
    private fun CoverageActiveCentroidNode(
        engine: Engine,
        materialLoader: MaterialLoader,
        telemetry: RendererTelemetry,
        coverage: PointCloudNativeConfig,
        token: CoverageResourceToken?,
    ): CoverageMeshAttachment? {
        val resources = remember(engine) {
            createCoverageResource {
                coverageResourceFactory.replacePoint(
                    VoxelRenderMode.CENTROIDS,
                    CoverageRendererLimits.CENTROID_CAPACITY,
                    "coverage-centroids",
                    rendererGeneration = coverage.rendererGeneration,
                    token = token,
                    create = { _, capacity, owner -> CoveragePointMeshResources(engine, capacity, telemetry, owner) },
                    release = CoverageVoxelMeshResources::destroy,
                )
            }
        }
        if (resources == null) return null
        val material = remember(materialLoader) {
            materialLoader.createMaterial("materials/coverage_points.filamat")
        }
        val materialInstance = remember(materialLoader, material) {
            materialLoader.createInstance(material)
        }
        val binding = remember(resources, materialInstance, coverage.pointSizePx) {
            CoveragePointMeshBinding(
                mode = VoxelRenderMode.CENTROIDS,
                target = CoverageMeshTarget(resources, materialInstance),
                pointSizePx = coverage.pointSizePx,
                readCoverageSnapshot = coverageRendererOwner::presentationSnapshot,
                readCoverageDescriptor = coverageRendererOwner::presentationDescriptor,
                resourceToken = token,
                acceptsResourceToken = coverageRendererOwner::acceptsResourceToken,
                requestRendererFrame = ::requestCoverageUploadFrame,
                cancelRendererFrame = ::cancelCoverageUploadFrame,
                releaseResources = { releaseCoverageResources(token, resources) },
                onUploadFailure = {
                    rendererTelemetry.recordResourceFailure()
                    token?.let { coverageRendererOwner.markUploadFailure(it) }
                },
            )
        }
        val node = remember(engine, resources, material, materialInstance) {
            CoverageOwnedMeshNode(
                engine,
                resources,
                CoveragePointMeshResources.DEFAULT_BOUNDING_BOX,
                materialInstance,
                beforeDestroy = binding::clearNode,
                releaseResources = { releaseCoverageResources(token, resources) },
            ) {
                materialLoader.destroyMaterialInstance(materialInstance)
                materialLoader.destroyMaterial(material)
            }.also(binding::setNode)
        }
        return CoverageMeshAttachment(node, binding)
    }

    @Composable
    private fun CoverageActiveCubeNode(
        engine: Engine,
        materialLoader: MaterialLoader,
        telemetry: RendererTelemetry,
        coverage: PointCloudNativeConfig,
        token: CoverageResourceToken?,
    ): CoverageMeshAttachment? {
        val resources = remember(
            engine,
            coverage.voxelSizeMeters,
            coverage.cubeSizeFactor,
        ) {
            createCoverageResource {
                coverageResourceFactory.replaceCube(
                    CoverageRendererLimits.CUBE_CAPACITY,
                    "coverage-cubes",
                    rendererGeneration = coverage.rendererGeneration,
                    token = token,
                    create = { _, capacity, owner -> CoverageCubeMeshResources(engine, capacity, coverage.voxelSizeMeters * coverage.cubeSizeFactor, telemetry, owner) },
                    release = CoverageVoxelMeshResources::destroy,
                )
            }
        }
        if (resources == null) return null
        val material = remember(materialLoader) {
            materialLoader.createMaterial("materials/coverage_cubes.filamat")
        }
        val materialInstance = remember(materialLoader, material) {
            materialLoader.createInstance(material)
        }
        val outlineMaterialInstance = remember(materialLoader) {
            materialLoader.createUnlitColorInstance(android.graphics.Color.BLACK)
        }
        val binding = remember(resources, materialInstance, coverage.pointSizePx) {
            CoveragePointMeshBinding(
                mode = VoxelRenderMode.CUBES,
                target = CoverageMeshTarget(resources, materialInstance),
                pointSizePx = coverage.pointSizePx,
                readCoverageSnapshot = coverageRendererOwner::presentationSnapshot,
                readCoverageDescriptor = coverageRendererOwner::presentationDescriptor,
                resourceToken = token,
                acceptsResourceToken = coverageRendererOwner::acceptsResourceToken,
                requestRendererFrame = ::requestCoverageUploadFrame,
                cancelRendererFrame = ::cancelCoverageUploadFrame,
                releaseResources = { releaseCoverageResources(token, resources) },
                onUploadFailure = {
                    rendererTelemetry.recordResourceFailure()
                    token?.let { coverageRendererOwner.markUploadFailure(it) }
                },
            )
        }
        val node = remember(
            engine,
            resources,
            material,
            materialInstance,
            outlineMaterialInstance,
        ) {
            CoverageOwnedCubeNode(
                engine,
                resources,
                CoverageCubeMeshResources.DEFAULT_BOUNDING_BOX,
                materialInstance,
                outlineMaterialInstance,
                beforeDestroy = binding::clearNode,
                releaseResources = { releaseCoverageResources(token, resources) },
            ) {
                materialLoader.destroyMaterialInstance(outlineMaterialInstance)
                materialLoader.destroyMaterialInstance(materialInstance)
                materialLoader.destroyMaterial(material)
            }.also(binding::setNode)
        }
        return CoverageMeshAttachment(node, binding)
    }

    @Composable
    private fun CoverageActiveBindingEffect(
        binding: CoveragePointMeshBinding,
        coverage: PointCloudNativeConfig,
        token: CoverageResourceToken?,
        coverageMeshRef: AtomicReference<CoveragePointMeshBinding?>,
        onCoverageRendererMounted: (Boolean, Long) -> Unit,
    ) {
        SideEffect {
            binding.updateCoverage(coverage.voxelRenderMode)
        }
        DisposableEffect(binding) {
            // NodeLifecycle's attach effect is registered before this nested
            // content effect. Queue the retained reset for this exact resource
            // generation, then publish its frame binding; a stale outgoing
            // composition cannot revive or replace it.
            binding.updateCoverage(coverage.voxelRenderMode)
            if (binding.attachAfterNodeLifecycle {
                    coverageMeshRef.set(binding)
                }
            ) {
                token?.let {
                    coverageRendererOwner.markResourceMounted(it)
                    coverageResourceFactory.markResourceMounted(it)
                }
                token?.let { onCoverageRendererMounted(true, it.epoch) }
            }
            onDispose {
                val wasCurrent = coverageMeshRef.compareAndSet(binding, null)
                binding.dispose()
                if (wasCurrent) {
                    token?.let { coverageRendererOwner.markResourceFailure(it) }
                    token?.let { onCoverageRendererMounted(false, it.epoch) }
                }
            }
        }
    }

    @Composable
    private fun rememberPluginModelInstance(
        modelLoader: ModelLoader,
        record: PluginNodeRecord,
    ): ModelInstance? {
        if (record.source == PluginNodeSource.FLUTTER_ASSET_GLTF2) {
            return rememberModelInstance(modelLoader, record.uri)
        }
        val instance = produceState<ModelInstance?>(
            initialValue = null,
            key1 = modelLoader,
            key2 = record.uri,
        ) {
            value = runCatching {
                modelLoader.loadModelInstance(
                    record.uri,
                    resourceResolver = { resourceFileName ->
                        resolveModelResource(record.uri, resourceFileName)
                    },
                )
            }.onFailure { error ->
                Log.e("SceneViewHost", "Failed to load model ${record.uri}", error)
            }.getOrNull()
        }.value
        DisposableEffect(instance) {
            onDispose {
                instance?.let { modelLoader.destroyModel(it.model) }
            }
        }
        return instance
    }

    private fun resolveModelResource(
        modelUri: String,
        resourceFileName: String,
    ): String {
        if (!resourceFileName.startsWith("data:")) {
            return ModelLoader.getFolderPath(modelUri, resourceFileName)
        }
        val marker = ";base64,"
        require(marker in resourceFileName) {
            "Only base64 data URI model resources are supported"
        }
        val encoded = resourceFileName.substringAfter(marker)
        val cacheFile = File(
            context.cacheDir,
            "sceneview-resource-${encoded.hashCode().toUInt().toString(16)}.bin",
        )
        if (!cacheFile.exists()) {
            cacheFile.writeBytes(Base64.decode(encoded, Base64.DEFAULT))
        }
        return cacheFile.toURI().toString()
    }

    private fun Node.pluginGestureTarget(): Pair<String, Node>? {
        var candidate: Node? = this
        while (candidate != null) {
            val id = candidate.name
            if (id != null && id in nodes) return id to candidate
            candidate = candidate.parent
        }
        return null
    }

    private class CoverageOwnedMeshNode(
        engine: Engine,
        private val resources: CoverageVoxelMeshResources,
        boundingBox: com.google.android.filament.Box,
        materialInstance: com.google.android.filament.MaterialInstance,
        private val beforeDestroy: () -> Unit,
        private val releaseResources: () -> Unit,
        private val releaseMaterial: () -> Unit,
    ) : MeshNode(
        engine = engine,
        primitiveType = resources.primitiveType,
        vertexBuffer = resources.vertexBuffer,
        indexBuffer = resources.indexBuffer,
        boundingBox = boundingBox,
        materialInstance = materialInstance,
    ) {
        private var coverageDestroyed = false

        override fun destroy() {
            if (coverageDestroyed) return
            coverageDestroyed = true
            beforeDestroy()
            // Filament requires the renderable to release its references before
            // its material and geometry buffers are destroyed.
            super.destroy()
            releaseResources()
            releaseMaterial()
        }
    }

    private class CoverageOwnedCubeNode(
        engine: Engine,
        private val resources: CoverageCubeMeshResources,
        boundingBox: com.google.android.filament.Box,
        materialInstance: com.google.android.filament.MaterialInstance,
        outlineMaterialInstance: com.google.android.filament.MaterialInstance,
        private val beforeDestroy: () -> Unit,
        private val releaseResources: () -> Unit,
        private val releaseMaterials: () -> Unit,
    ) : Node(engine = engine) {
        private var coverageDestroyed = false

        init {
            RenderableManager.Builder(2)
                .boundingBox(boundingBox)
                .culling(true)
                .geometry(
                    0,
                    RenderableManager.PrimitiveType.TRIANGLES,
                    resources.vertexBuffer,
                    resources.indexBuffer,
                )
                .material(0, materialInstance)
                .geometry(
                    CoverageCubeMeshResources.OUTLINE_PRIMITIVE_INDEX,
                    RenderableManager.PrimitiveType.LINES,
                    resources.vertexBuffer,
                    resources.outlineIndexBuffer,
                )
                .material(
                    CoverageCubeMeshResources.OUTLINE_PRIMITIVE_INDEX,
                    outlineMaterialInstance,
                )
                .build(engine, entity)
            isVisible = false
        }

        override fun destroy() {
            if (coverageDestroyed) return
            coverageDestroyed = true
            beforeDestroy()
            engine.renderableManager.destroy(entity)
            super.destroy()
            releaseResources()
            releaseMaterials()
        }
    }

    private class CoverageMeshTarget(
        val resources: CoverageVoxelMeshResources,
        val materialInstance: com.google.android.filament.MaterialInstance,
    ) {
        var node: Node? = null
    }

    private data class CoverageMeshAttachment(
        val node: Node,
        val binding: CoveragePointMeshBinding,
    )

    /** Owns exactly one active presentation mesh; replacements are fenced first. */
    private class CoveragePointMeshBinding(
        private val mode: VoxelRenderMode,
        private val target: CoverageMeshTarget,
        private val pointSizePx: Float,
        private val readCoverageSnapshot: () -> CoveragePointRenderSnapshot?,
        private val readCoverageDescriptor: () -> BoundedCoveragePresentation?,
        private val resourceToken: CoverageResourceToken?,
        private val acceptsResourceToken: (CoverageResourceToken) -> Boolean,
        private val requestRendererFrame: (CoveragePointMeshBinding) -> Unit,
        private val cancelRendererFrame: () -> Unit,
        private val releaseResources: () -> Unit,
        private val onUploadFailure: () -> Unit,
    ) {
        private var latestPresentationDescriptor: BoundedCoveragePresentation? = null
        @Volatile private var attached = false
        @Volatile private var disposed = false

        init {
            target.resources.setOnUploadPageReleased { requestRendererFrame(this) }
        }

        fun setNode(value: Node) {
            target.node = value
            updateActiveTarget()
        }

        fun clearNode() {
            target.node = null
        }

        fun updateCoverage(requestedMode: VoxelRenderMode = mode) {
            if (disposed || requestedMode != mode) return
            if (attached) updateActiveTarget()
        }

        fun updateCoverageDescriptor(
            descriptor: BoundedCoveragePresentation?,
            requestedMode: VoxelRenderMode,
        ) {
            if (disposed || requestedMode != mode) return
            latestPresentationDescriptor = descriptor
            if (attached) updateActiveTarget()
        }

        fun onRendererFrame() {
            if (attached && !disposed && resourceToken != null && acceptsResourceToken(resourceToken)) {
                target.resources.onRendererFrame()
            }
        }

        fun requireFullRefresh() {
            if (!disposed) target.resources.requireRetainedSnapshotUpload()
        }

        /**
         * Runs from the content of SceneView's [NodeLifecycle], whose own
         * DisposableEffect has already attached the node. The binding is then
         * prepared before it is registered for a subsequent renderer frame.
         */
        fun attachAfterNodeLifecycle(registerForFrames: () -> Unit): Boolean {
            if (disposed || resourceToken == null || !acceptsResourceToken(resourceToken)) return false
            return run {
                attached = true
                target.resources.requireRetainedSnapshotUpload()
                updateActiveTarget()
                // Publish the binding only after its retained reset is queued.
                // The coordinator will admit that page on a later real frame.
                registerForFrames()
                true
            }
        }

        private fun updateActiveTarget() {
            if (disposed) return
            if (resourceToken == null || !acceptsResourceToken(resourceToken)) return
            val currentNode = target.node ?: return
            val descriptor = latestPresentationDescriptor ?: readCoverageDescriptor()
            if (descriptor != null) {
                when (val resources = target.resources) {
                    is CoveragePointMeshResources -> resources.updateDescriptor(
                        node = currentNode,
                        descriptor = descriptor,
                        materialInstance = target.materialInstance,
                        pointSizePx = pointSizePx,
                    )
                    is CoverageCubeMeshResources -> resources.updateDescriptor(
                        node = currentNode,
                        descriptor = descriptor,
                        materialInstance = target.materialInstance,
                        pointSizePx = pointSizePx,
                    )
                    else -> Unit
                }
                if (target.resources is CoveragePointMeshResources ||
                    target.resources is CoverageCubeMeshResources
                ) return
            }
            val snapshot = descriptor?.toLegacySnapshot() ?: readCoverageSnapshot()
            if (snapshot == null) {
                target.resources.hide(currentNode)
                return
            }
            runCatching {
                target.resources.update(
                    node = currentNode,
                    snapshot = snapshot,
                    materialInstance = target.materialInstance,
                    pointSizePx = pointSizePx,
                )
            }.onFailure { onUploadFailure() }
        }

        fun dispose() {
            disposed = true
            attached = false
            cancelRendererFrame()
            target.resources.setOnUploadPageReleased {}
            target.node = null
            latestPresentationDescriptor = null
        }

        /** Frees the old mesh before Compose creates a replacement mode. */
        fun disposeForReplacement() {
            if (disposed) return
            disposed = true
            attached = false
            cancelRendererFrame()
            target.resources.setOnUploadPageReleased {}
            // The node owns the renderable lifetime. Release the factory lease
            // only after that node has detached, or release a never-mounted
            // resource directly when no node was created.
            val outgoingNode = target.node
            if (outgoingNode != null) {
                outgoingNode.destroy()
            } else {
                releaseResources()
            }
            target.node = null
            latestPresentationDescriptor = null
        }
    }

    private companion object {
        val sceneSessionLease = SceneViewSessionLease()
        val sceneSessionGenerationCounter = AtomicLong()

        fun defaultConfig() = PluginSessionConfig(
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

internal data class SceneRendererPressureSnapshot(
    val renderer: RendererPressureSnapshot,
    val pages: RendererPageFramePressureSnapshot,
)

internal fun selectVisibilityGridDepthMode(
    isSupported: (Config.DepthMode) -> Boolean,
): Config.DepthMode =
    ArCoreDepthModeController(
        rawDepthSupported = isSupported(Config.DepthMode.RAW_DEPTH_ONLY),
        automaticDepthSupported = isSupported(Config.DepthMode.AUTOMATIC),
    ).activeMode

internal class VisibilityGridDepthModeCache {
    @Volatile
    private var configuredSession: Any? = null

    @Volatile
    private var activeMode: Config.DepthMode? = null

    @Synchronized
    fun configure(
        sessionIdentity: Any,
        isSupported: (Config.DepthMode) -> Boolean,
    ): Config.DepthMode {
        if (configuredSession === sessionIdentity) {
            activeMode?.let { return it }
        }
        val selected = selectVisibilityGridDepthMode(isSupported)
        configuredSession = sessionIdentity
        activeMode = selected
        return selected
    }

    fun current(): Config.DepthMode =
        activeMode ?: Config.DepthMode.DISABLED

    @Synchronized
    fun reset() {
        configuredSession = null
        activeMode = null
    }
}
