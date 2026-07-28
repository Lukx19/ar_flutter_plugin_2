package com.uhg0.ar_flutter_plugin_2.sceneview

import android.app.Activity
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.Base64
import android.view.MotionEvent
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
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
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.google.android.filament.Engine
import com.google.android.filament.RenderableManager
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
import com.uhg0.ar_flutter_plugin_2.pointcloud.PointCloudNativeConfig
import com.uhg0.ar_flutter_plugin_2.pointcloud.VoxelRenderMode
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
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.atomic.AtomicReference
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
    private val onCoverageRendererMounted: (Boolean) -> Unit = {},
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
    private val nodes = mutableStateMapOf<String, NodeState>()
    private val anchors = mutableStateMapOf<String, AnchorState>()
    private val detectedPlanes = mutableStateMapOf<Plane, Unit>()
    private val configState = mutableStateOf(defaultConfig())
    // The Compose tree only needs to know whether a point mesh is mounted and
    // which fixed-capacity resources it owns. Individual point snapshots are
    // applied directly to the retained mesh; making the whole ARSceneView
    // recompose at the acquisition rate causes camera/overlay frame jitter.
    private val coverageRenderConfig = mutableStateOf<PointCloudNativeConfig?>(null)
    private val coverageSnapshotRef = AtomicReference<CoveragePointRenderSnapshot?>()
    private val rawPointSnapshotRef = AtomicReference<CoveragePointRenderSnapshot?>()
    private val coverageMeshRef = AtomicReference<CoveragePointMeshBinding?>()
    private val sharedCameraLifecycleGate = SharedCameraSceneLifecycleGate(
        Session.Feature.SHARED_CAMERA in sessionFeatures,
    )
    private val sessionRef = AtomicReference<Session?>()
    private val frameRef = AtomicReference<Frame?>()
    private val engineRef = AtomicReference<Engine?>()
    private val cameraStreamRef = AtomicReference<ARCameraStream?>()
    private val frameCadenceTracker = FrameCadenceTracker()
    private var disposed = false
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
            val engine = rememberEngine()
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
                lifecycle = lifecycle,
                sessionFeatures = sessionFeatures,
                planeFindingMode = config.planeFindingMode,
                cloudAnchorMode = if (config.cloudAnchorEnabled) {
                    Config.CloudAnchorMode.ENABLED
                } else {
                    Config.CloudAnchorMode.DISABLED
                },
                planeRenderer = config.showPlanes && config.customPlaneTexturePath == null,
                sessionConfiguration = { session, arConfig ->
                    requestedRearCameraId?.let { requestedId ->
                        val selectedConfig =
                            session.getSupportedCameraConfigs(CameraConfigFilter(session))
                                .firstOrNull {
                                    it.cameraId == requestedId &&
                                        it.facingDirection == CameraConfig.FacingDirection.BACK
                                }
                        if (selectedConfig == null) {
                            Log.w("SceneViewHost", "Requested ARCore rear camera $requestedId is unavailable")
                        } else {
                            session.cameraConfig = selectedConfig
                            Log.i("SceneViewHost", "Selected ARCore rear camera $requestedId")
                        }
                    }
                    arConfig.depthMode =
                        selectVisibilityGridDepthMode(session::isDepthModeSupported)
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
                    frameCadenceTracker.record(System.nanoTime())
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
                if (coverage != null) {
                    key(
                        coverage.renderCapacity,
                        coverage.voxelSizeMeters,
                        coverage.cubeSizeFactor,
                        coverage.pointSizePx,
                    ) {
                        val pointResources = remember(engine, coverage.renderCapacity) {
                            CoveragePointMeshResources(engine, coverage.renderCapacity)
                        }
                        val centroidResources = remember(engine, coverage.renderCapacity) {
                            CoveragePointMeshResources(engine, coverage.renderCapacity)
                        }
                        val cubeResources = remember(
                            engine,
                            coverage.renderCapacity,
                            coverage.voxelSizeMeters,
                            coverage.cubeSizeFactor,
                        ) {
                            CoverageCubeMeshResources(
                                engine,
                                coverage.renderCapacity,
                                coverage.voxelSizeMeters * coverage.cubeSizeFactor,
                            )
                        }
                        val pointMaterial = remember(materialLoader) {
                            materialLoader.createMaterial("materials/coverage_points.filamat")
                        }
                        val centroidMaterial = remember(materialLoader) {
                            materialLoader.createMaterial("materials/coverage_points.filamat")
                        }
                        val cubeMaterial = remember(materialLoader) {
                            materialLoader.createMaterial("materials/coverage_cubes.filamat")
                        }
                        val pointMaterialInstance = remember(materialLoader, pointMaterial) {
                            materialLoader.createInstance(pointMaterial)
                        }
                        val centroidMaterialInstance =
                            remember(materialLoader, centroidMaterial) {
                                materialLoader.createInstance(centroidMaterial)
                            }
                        val cubeMaterialInstance = remember(materialLoader, cubeMaterial) {
                            materialLoader.createInstance(cubeMaterial)
                        }
                        val cubeOutlineMaterialInstance = remember(materialLoader) {
                            materialLoader.createUnlitColorInstance(android.graphics.Color.BLACK)
                        }
                        val binding = remember(
                            pointResources,
                            centroidResources,
                            cubeResources,
                            pointMaterialInstance,
                            centroidMaterialInstance,
                            cubeMaterialInstance,
                        ) {
                            CoveragePointMeshBinding(
                                pointTarget = CoverageMeshTarget(
                                    pointResources,
                                    pointMaterialInstance,
                                ),
                                centroidTarget = CoverageMeshTarget(
                                    centroidResources,
                                    centroidMaterialInstance,
                                ),
                                cubeTarget = CoverageMeshTarget(
                                    cubeResources,
                                    cubeMaterialInstance,
                                ),
                                pointSizePx = coverage.pointSizePx,
                            )
                        }
                        SideEffect {
                            binding.setMode(coverage.voxelRenderMode)
                            binding.updateCoverage(coverageSnapshotRef.get())
                            binding.updateRawPoints(rawPointSnapshotRef.get())
                        }
                        DisposableEffect(binding) {
                            coverageMeshRef.set(binding)
                            binding.setMode(coverage.voxelRenderMode)
                            binding.updateCoverage(coverageSnapshotRef.get())
                            binding.updateRawPoints(rawPointSnapshotRef.get())
                            onCoverageRendererMounted(true)
                            onDispose {
                                val wasCurrent = coverageMeshRef.compareAndSet(binding, null)
                                binding.dispose()
                                // A replacement mode may already have installed its binding.
                                // Do not let the retiring node mark that replacement unmounted.
                                if (wasCurrent) {
                                    onCoverageRendererMounted(false)
                                }
                            }
                        }
                        val pointNode = remember(
                            engine,
                            pointResources,
                            pointMaterial,
                            pointMaterialInstance,
                        ) {
                            CoverageOwnedMeshNode(
                                engine = engine,
                                resources = pointResources,
                                boundingBox = CoveragePointMeshResources.DEFAULT_BOUNDING_BOX,
                                materialInstance = pointMaterialInstance,
                                beforeDestroy = {
                                    binding.clearNode(VoxelRenderMode.POINTS)
                                },
                            ) {
                                materialLoader.destroyMaterialInstance(pointMaterialInstance)
                                materialLoader.destroyMaterial(pointMaterial)
                            }.also { binding.setNode(VoxelRenderMode.POINTS, it) }
                        }
                        val cubeNode = remember(
                            engine,
                            cubeResources,
                            cubeMaterial,
                            cubeMaterialInstance,
                            cubeOutlineMaterialInstance,
                        ) {
                            CoverageOwnedCubeNode(
                                engine = engine,
                                resources = cubeResources,
                                boundingBox = CoverageCubeMeshResources.DEFAULT_BOUNDING_BOX,
                                materialInstance = cubeMaterialInstance,
                                outlineMaterialInstance = cubeOutlineMaterialInstance,
                                beforeDestroy = {
                                    binding.clearNode(VoxelRenderMode.CUBES)
                                },
                            ) {
                                materialLoader.destroyMaterialInstance(
                                    cubeOutlineMaterialInstance,
                                )
                                materialLoader.destroyMaterialInstance(cubeMaterialInstance)
                                materialLoader.destroyMaterial(cubeMaterial)
                            }.also { binding.setNode(VoxelRenderMode.CUBES, it) }
                        }
                        val centroidNode = remember(
                            engine,
                            centroidResources,
                            centroidMaterial,
                            centroidMaterialInstance,
                        ) {
                            CoverageOwnedMeshNode(
                                engine = engine,
                                resources = centroidResources,
                                boundingBox = CoveragePointMeshResources.DEFAULT_BOUNDING_BOX,
                                materialInstance = centroidMaterialInstance,
                                beforeDestroy = {
                                    binding.clearNode(VoxelRenderMode.CENTROIDS)
                                },
                            ) {
                                materialLoader.destroyMaterialInstance(
                                    centroidMaterialInstance,
                                )
                                materialLoader.destroyMaterial(centroidMaterial)
                            }.also {
                                binding.setNode(VoxelRenderMode.CENTROIDS, it)
                            }
                        }
                        NodeLifecycle(pointNode) {}
                        NodeLifecycle(centroidNode) {}
                        NodeLifecycle(cubeNode) {}
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

    init {
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

    fun updateCoverageRenderer(
        snapshot: CoveragePointRenderSnapshot?,
        config: PointCloudNativeConfig?,
    ) {
        if (snapshot == null || config == null) {
            coverageSnapshotRef.set(null)
            coverageMeshRef.get()?.updateCoverage(null)
            coverageRenderConfig.value = null
            return
        }

        coverageSnapshotRef.set(snapshot)
        coverageMeshRef.get()?.let { binding ->
            binding.setMode(config.voxelRenderMode)
            binding.updateCoverage(snapshot)
        }
        val current = coverageRenderConfig.value
        if (current == null ||
            current.renderCapacity != config.renderCapacity ||
            current.pointSizePx != config.pointSizePx ||
            current.enabled != config.enabled ||
            current.voxelRenderMode != config.voxelRenderMode ||
            current.voxelSizeMeters != config.voxelSizeMeters ||
            current.cubeSizeFactor != config.cubeSizeFactor
        ) {
            coverageRenderConfig.value = config
        } else {
            coverageMeshRef.get()?.updateCoverage(snapshot)
        }
    }

    fun updateRawPointCloud(snapshot: CoveragePointRenderSnapshot?) {
        rawPointSnapshotRef.set(snapshot)
        coverageMeshRef.get()?.updateRawPoints(snapshot)
    }

    fun resume() {
        checkNotDisposed()
        activeSession?.resume()
    }

    fun pause() {
        if (!disposed) activeSession?.pause()
    }

    fun rendererPerformanceSnapshot(): Map<String, Any> =
        frameCadenceTracker.snapshot()

    fun dispose() {
        if (!ownership.onDispose()) return
        disposed = true
        composeView.removeCallbacks(replaySettledTextureResize)
        nodes.clear()
        anchors.values.forEach { it.anchor.detach() }
        anchors.clear()
        detectedPlanes.clear()
        frameRef.set(null)
        sessionRef.set(null)
        cameraStreamRef.set(null)
        engineRef.set(null)
        composeView.disposeComposition()
        (composeView.parent as? ViewGroup)?.removeView(composeView)
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

    private fun Node.pluginTransform(): PluginTransform = PluginTransform(
        transform.toFloatArray().map(Float::toDouble),
    )

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
            resources.destroy()
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
            resources.destroy()
            releaseMaterials()
        }
    }

    private class CoverageMeshTarget(
        val resources: CoverageVoxelMeshResources,
        val materialInstance: com.google.android.filament.MaterialInstance,
    ) {
        var node: Node? = null
    }

    /** Retains both render modes so a UI toggle never replaces a live SceneView node. */
    private class CoveragePointMeshBinding(
        private val pointTarget: CoverageMeshTarget,
        private val centroidTarget: CoverageMeshTarget,
        private val cubeTarget: CoverageMeshTarget,
        private val pointSizePx: Float,
    ) {
        private var latestCoverageSnapshot: CoveragePointRenderSnapshot? = null
        private var latestRawPointSnapshot: CoveragePointRenderSnapshot? = null
        private var mode = VoxelRenderMode.POINTS

        fun setNode(mode: VoxelRenderMode, value: Node) {
            target(mode).node = value
            updateActiveTarget()
        }

        fun setMode(value: VoxelRenderMode) {
            if (mode == value) return
            mode = value
            updateActiveTarget()
        }

        fun clearNode(mode: VoxelRenderMode) {
            target(mode).node = null
        }

        fun updateCoverage(snapshot: CoveragePointRenderSnapshot?) {
            latestCoverageSnapshot = snapshot
            if (mode != VoxelRenderMode.POINTS) updateActiveTarget()
        }

        fun updateRawPoints(snapshot: CoveragePointRenderSnapshot?) {
            latestRawPointSnapshot = snapshot
            if (mode == VoxelRenderMode.POINTS) updateActiveTarget()
        }

        private fun updateActiveTarget() {
            val active = target(mode)
            targets()
                .filterNot { it === active }
                .forEach { inactive ->
                    inactive.node?.let(inactive.resources::hide)
                }
            val currentNode = active.node ?: return
            val snapshot = when (mode) {
                VoxelRenderMode.POINTS -> latestRawPointSnapshot
                VoxelRenderMode.CENTROIDS,
                VoxelRenderMode.CUBES -> latestCoverageSnapshot
            }
            if (snapshot == null) {
                active.resources.hide(currentNode)
                return
            }
            active.resources.update(
                node = currentNode,
                snapshot = snapshot,
                materialInstance = active.materialInstance,
                pointSizePx = pointSizePx,
            )
        }

        fun dispose() {
            pointTarget.node = null
            centroidTarget.node = null
            cubeTarget.node = null
            latestCoverageSnapshot = null
            latestRawPointSnapshot = null
        }

        private fun target(mode: VoxelRenderMode): CoverageMeshTarget = when (mode) {
            VoxelRenderMode.POINTS -> pointTarget
            VoxelRenderMode.CENTROIDS -> centroidTarget
            VoxelRenderMode.CUBES -> cubeTarget
        }

        private fun targets(): List<CoverageMeshTarget> =
            listOf(pointTarget, centroidTarget, cubeTarget)
    }

    private companion object {
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

internal fun selectVisibilityGridDepthMode(
    isSupported: (Config.DepthMode) -> Boolean,
): Config.DepthMode =
    ArCoreDepthModeController(
        rawDepthSupported = isSupported(Config.DepthMode.RAW_DEPTH_ONLY),
        automaticDepthSupported = isSupported(Config.DepthMode.AUTOMATIC),
    ).activeMode
