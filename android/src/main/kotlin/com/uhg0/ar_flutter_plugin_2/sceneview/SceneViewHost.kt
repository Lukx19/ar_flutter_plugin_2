package com.uhg0.ar_flutter_plugin_2.sceneview

import com.uhg0.ar_flutter_plugin_2.diagnostics.NativeFrameAttributionOwner
import com.uhg0.ar_flutter_plugin_2.diagnostics.NativeFrameSpan
import com.uhg0.ar_flutter_plugin_2.diagnostics.measureNativeFrame

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
import androidx.compose.runtime.MutableState
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
import io.github.sceneview.node.RenderableNode
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
    @Volatile override var nativeFrameAttribution: NativeFrameAttributionOwner? = null
        private set
    override fun bindNativeFrameAttribution(owner: NativeFrameAttributionOwner?): Boolean {
        val retired = nativeFrameAttribution
        nativeFrameAttribution = owner
        if (retired !== owner) retired?.close()
        return true
    }

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
    /** Fixed physical slot zero; its role is resolved from its token. */
    private val coverageCandidateRenderConfig = mutableStateOf<PointCloudNativeConfig?>(null)
    private val coverageCandidateMeshRef = AtomicReference<CoveragePointMeshBinding?>()
    private val coverageSlotRefs = arrayOf(coverageMeshRef, coverageCandidateMeshRef)
    private val coverageSlotConfigs = arrayOf(coverageRenderConfig, coverageCandidateRenderConfig)
    private val coverageSlotTokens = arrayOf(
        mutableStateOf<CoverageResourceToken?>(null),
        mutableStateOf<CoverageResourceToken?>(null),
    )
    private var activeCoverageSlot = 0
    private var preparingCoverageSlot = -1
    private var retiringCoverageSlot = -1
    private var pendingCoverageRecovery = false

    /** Recovery may acquire only a genuinely drained physical slot. */
    private fun stageCoverageRecoveryIfDrained() {
        if (!pendingCoverageRecovery || coverageSlotTokens[activeCoverageSlot].value != null ||
            coverageSlotRef(activeCoverageSlot).get() != null || retiringCoverageSlot == activeCoverageSlot) return
        if (activeCoverageConfig() == null) return
        val token = coverageRendererOwner.requestResourceReplacement() ?: return
        if (!CoverageSlotAdmission.canStage(coverageSlotTokens[activeCoverageSlot].value, token, false)) return
        setCoverageSlotToken(activeCoverageSlot, token)
        pendingCoverageRecovery = false
    }

    private fun coverageSlotRef(slot: Int): AtomicReference<CoveragePointMeshBinding?> = coverageSlotRefs[slot]
    private fun coverageSlotConfig(slot: Int): MutableState<PointCloudNativeConfig?> = coverageSlotConfigs[slot]
    private fun activeCoverageBinding(): CoveragePointMeshBinding? = coverageSlotRef(activeCoverageSlot).get()
    private fun preparingCoverageBinding(): CoveragePointMeshBinding? =
        preparingCoverageSlot.takeIf { it >= 0 }?.let { coverageSlotRef(it).get() }
    private fun activeCoverageConfig(): PointCloudNativeConfig? = coverageSlotConfig(activeCoverageSlot).value
    private fun preparingCoverageConfig(): PointCloudNativeConfig? =
        preparingCoverageSlot.takeIf { it >= 0 }?.let { coverageSlotConfig(it).value }
    private fun freeCoverageSlot(): Int? = (0..1).firstOrNull { slot ->
        slot != activeCoverageSlot && slot != preparingCoverageSlot && slot != retiringCoverageSlot &&
            coverageSlotConfig(slot).value == null && coverageSlotRef(slot).get() == null &&
            coverageSlotTokens[slot].value == null
    }

    private fun setCoverageSlotToken(slot: Int, token: CoverageResourceToken?) {
        coverageSlotTokens[slot].value = token
    }

    private fun stageCoverageCandidateConfig(config: PointCloudNativeConfig, token: CoverageResourceToken): Boolean {
        val slot = preparingCoverageSlot.takeIf { it >= 0 } ?: freeCoverageSlot() ?: return false
        if (!CoverageSlotAdmission.canStage(coverageSlotTokens[slot].value, token,
                retiringCoverageSlot == slot)) return false
        preparingCoverageSlot = slot
        setCoverageSlotToken(slot, token)
        coverageSlotConfig(slot).value = config.copy(enabled = false)
        return true
    }

    private fun stageCoverageCandidate(
        descriptor: BoundedCoveragePresentation,
        config: PointCloudNativeConfig,
    ): Boolean {
        // C/D updates advance canonical authority while frozen B is uploading.
        // Keep B and its backing lease intact; promotion will decide whether
        // the already-qualified successor is still usable.
        if (coverageRendererOwner.currentCandidateResourceToken() != null) return true
        if (CoveragePresentationEligibility.resourceKind(descriptor, initial = false) ==
            CoveragePresentationEligibility.ResourceKind.PENDING) return false
        val slot = preparingCoverageSlot.takeIf { it >= 0 } ?: freeCoverageSlot() ?: return false
        val token = coverageRendererOwner.issueCandidateResourceToken() ?: return false
        if (!CoverageSlotAdmission.canStage(coverageSlotTokens[slot].value, token,
                retiringCoverageSlot == slot) || !reserveFrozenBacking(descriptor, token) ||
            !coverageRendererOwner.stageCandidateDisplay(descriptor) ||
            !stageCoverageCandidateConfig(config, token)
        ) {
            coverageRendererOwner.cancelCandidateDisplay()
            if (frozenBackingToken == token) releaseFrozenBacking()
            return false
        }
        return true
    }

    /** A never-created candidate has no disposal callback; reclaim only its proven empty slot. */
    private fun cancelCoverageCandidateSlot(slot: Int, token: CoverageResourceToken) {
        if (slot !in coverageSlotTokens.indices || preparingCoverageSlot != slot ||
            coverageSlotTokens[slot].value != token ||
            coverageRendererOwner.displayRoleFor(token) == CoverageDisplayRole.ACTIVE) return
        val currentCandidate = coverageRendererOwner.currentCandidateResourceToken()
        if (currentCandidate != null && currentCandidate != token) return
        if (currentCandidate == token) coverageRendererOwner.cancelCandidateDisplay()
        val binding = coverageSlotRef(slot).getAndSet(null)
        binding?.disposeForReplacement()
        val released = coverageResourceFactory.releaseResource(token)
        if (!released && CoverageSlotAdmission.canClearCancelledCandidate(
                token, coverageSlotTokens[slot].value,
                coverageRendererOwner.displayRoleFor(token) == null &&
                    coverageRendererOwner.currentCandidateResourceToken() == null,
                binding == null, coverageResourceFactory, rendererAllocationLedger,
                shapeResourceBudget.contains(token))) {
            // Reuse the same exact-token cleanup as a real drain, including frozen backing.
            onCoverageSlotResourceDrained(token)
        }
    }

    /** Clears a slot only after the single factory has released its token. */
    private fun onCoverageSlotResourceDrained(token: CoverageResourceToken) {
        val slot = coverageSlotTokens.indexOfFirst { it.value == token }
        if (slot < 0) return
        val isRetiring = retiringCoverageSlot == slot
        val isFailedPreparation = preparingCoverageSlot == slot &&
            coverageRendererOwner.displayRoleFor(token) == null
        if (!isRetiring && !isFailedPreparation) return
        coverageSlotRef(slot).getAndSet(null)?.dispose()
        if (!((rendererPaused || pendingCoverageRecovery) && isRetiring && slot == activeCoverageSlot)) {
            coverageSlotConfig(slot).value = null
        }
        setCoverageSlotToken(slot, null)
        if (preparingCoverageSlot == slot) preparingCoverageSlot = -1
        if (retiringCoverageSlot == slot) retiringCoverageSlot = -1
        if (isFailedPreparation) frozenBacking.release(token)
        if (!rendererPaused) stageCoverageRecoveryIfDrained()
        if (!rendererPaused && coverageSlotTokens.all { it.value == null } &&
            preparingCoverageSlot < 0 && retiringCoverageSlot < 0
        ) {
            rendererAllocationLedger.clearCoverageState()
        }
        requestCoveragePresentationRefresh()
    }

    /** Invalidates roles immediately but retains slot identities until drain. */
    private fun clearCoverageResourceState() {
        val activeToken = coverageSlotTokens[activeCoverageSlot].value
        val candidateSlot = preparingCoverageSlot
        val candidateToken = candidateSlot.takeIf { it >= 0 }?.let { coverageSlotTokens[it].value }
        if (activeToken != null) retiringCoverageSlot = activeCoverageSlot
        if (activeToken == null) retiringCoverageSlot = -1
        if (candidateSlot >= 0 && candidateToken != null) cancelCoverageCandidateSlot(candidateSlot, candidateToken)
        coverageRendererOwner.clearLatest()
        coverageRendererOwner.cancelCandidateDisplay()
        coverageResourceFactory.clear()
        coverageSlotRefs.forEach { it.getAndSet(null)?.disposeForReplacement() }
        coverageSlotConfigs.forEachIndexed { slot, config ->
            if (coverageSlotTokens[slot].value == null) config.value = null
        }
        if (candidateToken == null) preparingCoverageSlot = -1
        if (candidateToken == null) releaseFrozenBacking()
        if (activeToken == null && candidateToken == null) {
            rendererAllocationLedger.clearCoverageState()
        }
        rendererTelemetry.clearResidentPresentation()
    }

    /** Releases GPU generations for pause while retaining canonical authority. */
    private fun pauseCoverageResources() {
        val activeToken = coverageSlotTokens[activeCoverageSlot].value
        val candidateSlot = preparingCoverageSlot
        val candidateToken = candidateSlot.takeIf { it >= 0 }?.let { coverageSlotTokens[it].value }
        if (activeToken != null) retiringCoverageSlot = activeCoverageSlot
        if (activeToken == null) retiringCoverageSlot = -1
        if (candidateSlot >= 0 && candidateToken != null) cancelCoverageCandidateSlot(candidateSlot, candidateToken)
        coverageResourceFactory.clear()
        coverageSlotRefs.forEach { it.getAndSet(null)?.disposeForReplacement() }
        if (candidateToken == null) preparingCoverageSlot = -1
        if (candidateToken == null) releaseFrozenBacking()
    }
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
    private val arSessionCallbackCadenceTracker = FrameCadenceTracker(debugGapTimingEnabled = debugGapTimingEnabled)
    private val cameraFrameCadenceTracker = FrameCadenceTracker(debugGapTimingEnabled = debugGapTimingEnabled)
    private val uploadContinuationCadenceTracker = UploadContinuationCadenceTracker()
    private val frameCallbackClockNs: () -> Long = System::nanoTime
    @Volatile private var configuredCameraFpsLower = 0
    @Volatile private var configuredCameraFpsUpper = 0
    private val rendererTelemetry = RendererTelemetry()
    private val rendererAllocationLedger = CoverageRendererAllocationLedger(rendererTelemetry)
    private val shapeResourceBudget = CoverageShapeResourceBudget(
        { rendererTelemetry.ownedBufferBytesSnapshot().toLong() },
        rendererTelemetry::setOwnedBufferBytes, rendererTelemetry::removeOwner)
    private var shapeAllowanceBytes = 0
    private val shapeResourceUpgrade = CoverageShapeResourceUpgrade()
    private val frozenBacking = CoverageFrozenBackingReservation(rendererTelemetry)
    private val frozenBackingBytes: Int get() = frozenBacking.bytes
    private val frozenBackingToken: CoverageResourceToken? get() = frozenBacking.token
    private fun reserveFrozenBacking(descriptor: BoundedCoveragePresentation, token: CoverageResourceToken): Boolean =
        frozenBacking.reserve(descriptor, token)
    private fun releaseFrozenBacking() { frozenBacking.release() }
    /** CPU/codec model only; every physical GPU/page generation is charged separately. */
    internal fun admitRendererAttributeBytes(required: Long): Boolean = synchronized(rendererTelemetry) {
        if (disposed || required !in 0..Int.MAX_VALUE.toLong()) return@synchronized false
        val reserved = maxOf(required, shapeAllowanceBytes.toLong())
        val remainingOwners = rendererTelemetry.ownedBufferBytesSnapshot().toLong() - shapeAllowanceBytes
        if (remainingOwners + reserved > CoverageRendererLimits.ACTIVE_RENDERER_OWNED_LIMIT_BYTES ||
            remainingOwners + reserved > CoverageRendererLimits.COMBINED_RENDERER_OWNED_LIMIT_BYTES) return@synchronized false
        rendererTelemetry.setOwnedBufferBytes("coverage-renderer-attributes-and-shapes", reserved.toInt())
        shapeAllowanceBytes = reserved.toInt()
        true
    }
    private val coverageCreationRecovery = CoverageResourceCreationRecovery()
    private val coverageResourceFactory = CoverageRendererResourceFactory(
        onAcquisition = rendererTelemetry::recordResourceAcquisition,
        onReplacement = rendererTelemetry::recordResourceReplacement,
        onDisposal = rendererTelemetry::recordResourceDisposal,
        onDisposed = { token ->
            token?.let { coverageRendererOwner.markAnyResourceReleased(it) }
            token?.let { rendererTelemetry.clearResidentPresentation(it) }
            token?.let(::onCoverageSlotResourceDrained)
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
            activeCoverageBinding()?.disposeForReplacement()
        },
        onCreated = { transition ->
            transition.token?.let { coverageRendererOwner.markAnyResourceMounted(it) }
                ?: coverageRendererOwner.markResourceMounted(transition.rendererGeneration)
        },
        onFailureDetail = rendererTelemetry::recordResourceFailureDetail,
        onCreationFailure = { transition ->
            rendererTelemetry.recordResourceFailure()
            if (!transition.hadActiveResource) {
                val token = transition.token
                val cut = coverageRendererOwner.snapshot()
                if (token != null && token == coverageRendererOwner.currentResourceToken() && cut != null)
                    coverageCreationRecovery.recordFailure(token,
                    CoverageResourceRecoveryCut(cut.bindingGeneration, cut.groupGeneration,
                        cut.transactionId, cut.geometryRevision))
            }
            if (transition.admission.strategy == CoverageRendererTransitionStrategy.CLEAR_FIRST ||
                !transition.hadActiveResource
            ) {
                rendererAllocationLedger.releaseRendererStaging()
                transition.token?.let { coverageRendererOwner.markAnyResourceFailure(it) }
                    ?: coverageRendererOwner.markResourceFailure(transition.rendererGeneration)
            } else {
                transition.previousToken?.let { coverageRendererOwner.restoreResourceToken(it) }
            }
        },
    )
    /** One physical generation domain shared by both fixed Compose slots. */
    private val coverageCandidateResourceFactory: CoverageRendererResourceFactory
        get() = coverageResourceFactory
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
                    processArSessionUpdate(
                        cameraTimestampNs = { frame.timestamp },
                        rendererWork = {
                            // Admit at most one bounded upload page after
                            // resetting this renderer frame's shared ledger.
                            rendererTelemetry.beginRendererFrame()
                            consumeCoveragePublications()
                            coverageUploadFramePending.set(false)
                            nativeFrameAttribution.measureNativeFrame(NativeFrameSpan.UPLOAD) {
                                coverageSlotRefs.forEach { it.get()?.onRendererFrame() }
                            }
                        },
                        sessionWork = {
                            sessionRef.set(session)
                            frameRef.set(frame)
                            nativeFrameAttribution.measureNativeFrame(NativeFrameSpan.HOST_PLANES) {
                                frame.getUpdatedTrackables(Plane::class.java).forEach { plane ->
                                    if (plane.subsumedBy == null &&
                                        plane.trackingState != com.google.ar.core.TrackingState.STOPPED
                                    ) {
                                        detectedPlanes[plane] = Unit
                                    } else {
                                        detectedPlanes.remove(plane)
                                    }
                                }
                            }
                            onSessionUpdated(session, frame)
                        },
                    )
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
                val coverage = activeCoverageConfig()
                repeat(2) { slot ->
                    val slotCoverage = coverageSlotConfig(slot).value
                    val token = coverageSlotTokens[slot].value
                    val role = coverageRendererOwner.displayRoleFor(token)
                    if (slotCoverage != null && token != null && role != null &&
                        shouldComposeCoverageRenderer(slotCoverage)
                    ) {
                        // The physical slot is the only Compose identity. Role
                        // changes (candidate -> active) are resolved by token
                        // at draw time and therefore do not remount the node.
                        key("coverage-slot", slot) {
                            when (slotCoverage.voxelRenderMode) {
                                VoxelRenderMode.POINTS -> CoverageActivePointNode(
                                    engine, materialLoader, rendererTelemetry, slotCoverage, token, role,
                                )?.let { attachment ->
                                    NodeLifecycle(attachment.node) {
                                        CoverageActiveBindingEffect(
                                            attachment.binding, slotCoverage, token,
                                            coverageSlotRef(slot), onCoverageRendererMounted, role,
                                        )
                                    }
                                }
                                VoxelRenderMode.CENTROIDS -> CoverageActiveCentroidNode(
                                    engine, materialLoader, rendererTelemetry, slotCoverage, token, role,
                                )?.let { attachment ->
                                    NodeLifecycle(attachment.node) {
                                        CoverageActiveBindingEffect(
                                            attachment.binding, slotCoverage, token,
                                            coverageSlotRef(slot), onCoverageRendererMounted, role,
                                        )
                                    }
                                }
                                VoxelRenderMode.CUBES -> CoverageActiveCubeNode(
                                    engine, materialLoader, rendererTelemetry, slotCoverage, token, role,
                                )?.let { attachment ->
                                    NodeLifecycle(attachment.node) {
                                        CoverageActiveBindingEffect(
                                            attachment.binding, slotCoverage, token,
                                            coverageSlotRef(slot), onCoverageRendererMounted, role,
                                        )
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

    /** Only a new cut may retry a failed empty slot; active/draining GPU owners remain charged. */
    private fun recoverFailedCoverageCreation(config: PointCloudNativeConfig): Boolean {
        val next = coverageCreationRecovery.tryReplaceFailedGeneration(
            coverageRendererOwner, coverageSlotTokens[activeCoverageSlot].value,
            config.rendererGeneration, !disposed && !rendererPaused,
            activeCoverageBinding() == null && preparingCoverageSlot < 0 && retiringCoverageSlot < 0,
            coverageResourceFactory) ?: return false
        coverageSlotConfig(activeCoverageSlot).value = config
        // This actual owner-epoch operation invalidates remember(engine, token)'s cached null.
        setCoverageSlotToken(activeCoverageSlot, next)
        return true
    }

    private fun applyCoveragePresentation(
        descriptor: BoundedCoveragePresentation?,
        config: PointCloudNativeConfig?,
    ) {
        checkCoverageRendererThread()
        if (descriptor == null || config == null) {
            shapeResourceUpgrade.reset()
            clearCoverageResourceState()
            return
        }
        if (!rendererAllocationLedger.reserveCommonPresentationMetadata()) return
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
        val current = activeCoverageConfig()
        val authorityChanged = current != null &&
            coverageRendererOwner.activeDisplayDescriptor()?.qualifier !=
                coverageRendererOwner.presentationDescriptor()?.qualifier
        if (!controls.visible) {
            // CPU authority advances; the hidden mounted resource only drains prior work.
            preparingCoverageSlot.takeIf { it >= 0 }?.let { slot ->
                val token = coverageSlotTokens[slot].value
                if (token != null) cancelCoverageCandidateSlot(slot, token)
                if (token == null) {
                    coverageRendererOwner.cancelCandidateDisplay()
                    coverageSlotRef(slot).getAndSet(null)?.disposeForReplacement()
                    coverageSlotConfig(slot).value = null
                    setCoverageSlotToken(slot, null)
                    preparingCoverageSlot = -1
                    releaseFrozenBacking()
                }
            }
            val install = coverageRendererOwner.installPresentation(descriptor, effectiveConfig)
            if (install.stale) return
            if (!coverageRendererOwner.controlsConfigured()) {
                coverageRendererOwner.setControls(controls)
            }
            if (current != null) coverageSlotConfig(activeCoverageSlot).value = current.copy(enabled = false)
            coverageRendererOwner.refreshPresentation()
            return
        }
        val visualChange = current == null ||
            current.renderCapacity != effectiveConfig.renderCapacity ||
            current.pointSizePx != effectiveConfig.pointSizePx ||
            current.voxelRenderMode != effectiveConfig.voxelRenderMode ||
            current.voxelSizeMeters != effectiveConfig.voxelSizeMeters ||
            current.cubeSizeFactor != effectiveConfig.cubeSizeFactor
        val requiresReplacement = current == null || visualChange || authorityChanged ||
            (descriptor.shapes?.shapeCount ?: 0) > 0 &&
                activeCoverageBinding()?.supportsShapeCapacity(descriptor.attributes?.total ?: 0) != true ||
            current.rendererGeneration != effectiveConfig.rendererGeneration
        val candidateAlreadyRetained = coverageRendererOwner.currentCandidateResourceToken() != null
        val admission = if (requiresReplacement && !candidateAlreadyRetained) {
            rendererAllocationLedger.admitResourceReplacement(
                effectiveConfig.voxelRenderMode,
                sourceCapacity = descriptor.sourceCapacity,
                retainedCount = descriptor.count,
            )
        } else null
        if (admission?.strategy == CoverageRendererTransitionStrategy.REJECT) return
        if (!candidateAlreadyRetained && current != null && requiresReplacement &&
            admission?.strategy != CoverageRendererTransitionStrategy.COEXIST
        ) return
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
        if (recoverFailedCoverageCreation(effectiveConfig)) return
        if (current != null && requiresReplacement) {
            // Keep the complete active generation mounted while a successor is
            // prepared in the single bounded candidate slot.
            val candidateDescriptor = coverageRendererOwner.presentationDescriptor()
            if (candidateDescriptor != null && stageCoverageCandidate(candidateDescriptor, effectiveConfig)) {
                coverageRendererOwner.refreshPresentation()
                return
            }
            return
        }
        if (requiresReplacement) {
            checkNotNull(admission)
            if (current != null && admission.strategy == CoverageRendererTransitionStrategy.CLEAR_FIRST) {
                coverageRendererOwner.currentResourceToken()?.let { coverageRendererOwner.markResourceFailure(it) }
                activeCoverageBinding()?.disposeForReplacement()
                coverageResourceFactory.clear()
                rendererAllocationLedger.releaseRendererStaging()
            }
            coverageSlotConfig(activeCoverageSlot).value = effectiveConfig
            coverageRendererOwner.requestResourceReplacement()
            coverageRendererOwner.issueResourceToken()?.let { token ->
                setCoverageSlotToken(activeCoverageSlot, token)
            }
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
            clearCoverageResourceState()
            return
        }
        if (!rendererAllocationLedger.reserveCommonPresentationMetadata()) return

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
        val current = activeCoverageConfig()
        if (!controls.visible) {
            // CPU authority advances; the hidden mounted resource only drains prior work.
            val install = coverageRendererOwner.install(snapshot.toVisibilityRendererSnapshot(requestedConfig))
            if (install.stale) return
            if (!coverageRendererOwner.controlsConfigured()) {
                coverageRendererOwner.setControls(controls)
            }
            if (current != null) coverageSlotConfig(activeCoverageSlot).value = current.copy(enabled = false)
            coverageRendererOwner.refreshPresentation()
            return
        }
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
        val candidateAlreadyRetained = coverageRendererOwner.currentCandidateResourceToken() != null
        val admission = if (requiresReplacement && !candidateAlreadyRetained) {
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
        if (!candidateAlreadyRetained && current != null && requiresReplacement &&
            admission?.strategy != CoverageRendererTransitionStrategy.COEXIST
        ) return
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
        if (recoverFailedCoverageCreation(effectiveConfig)) return
        if (requiresReplacement) {
            checkNotNull(admission)
            if (current != null) {
                val candidateDescriptor = coverageRendererOwner.presentationDescriptor()
                if (candidateDescriptor == null || !stageCoverageCandidate(candidateDescriptor, effectiveConfig)) return
                coverageRendererOwner.refreshPresentation()
                return
            }
            coverageSlotConfig(activeCoverageSlot).value = effectiveConfig
            coverageRendererOwner.requestResourceReplacement()
            coverageRendererOwner.issueResourceToken()?.let { token ->
                setCoverageSlotToken(activeCoverageSlot, token)
            }
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
        val current = activeCoverageConfig() ?: run {
            rendererTelemetry.recordPresentation(controls.mode)
            return true
        }
        if (!controls.visible) {
            coverageSlotConfig(activeCoverageSlot).value = current.copy(enabled = false)
            rendererTelemetry.recordPresentation(controls.mode)
            return true
        }
        val nextMode = controls.mode.toVoxelRenderMode()
        val modeChanged = current.voxelRenderMode != nextMode ||
            coverageRendererOwner.status().mode != controls.mode ||
            current.rendererGeneration != coverageRendererOwner.status().rendererGeneration
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
                // A control change is a requested successor. A clear-first
                // admission would expose an empty frame and cannot be made a
                // coherent whole-presentation promotion.
                if (admission.strategy != CoverageRendererTransitionStrategy.COEXIST) {
                    return@runCatching false
                }
            }
            if (!modeChanged) {
                coverageSlotConfig(activeCoverageSlot).value = current.copy(enabled = controls.visible)
            }
            if (modeChanged) {
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

    fun resumeSession() {
        checkNotDisposed()
        check(!futureResumesBlocked) { "SceneView host is shutting down" }
        activeSession?.resume()
    }

    fun resume() { resumeSession(); recoverCoverageRenderer() }

    /** Rehydrates the latest committed renderer cut after a renderer-only loss. */
    fun recoverCoverageRenderer() {
        checkNotDisposed()
        check(!futureResumesBlocked) { "SceneView host is shutting down" }
        shapeResourceUpgrade.reset()
        val recovery = coverageRendererOwner.resume()
        if (recovery.recovered) {
            pendingCoverageRecovery = true
        }
        rendererPaused = false
        stageCoverageRecoveryIfDrained()
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
            pauseCoverageResources()
            rendererAllocationLedger.releaseRendererStaging()
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
        clearCoverageResourceState()
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

    /** Samples arrival before callback-owned work; inline keeps the frame path allocation-free. */
    private inline fun processArSessionUpdate(
        cameraTimestampNs: () -> Long,
        rendererWork: () -> Unit,
        sessionWork: () -> Unit,
    ) {
        val callbackArrivalNs = frameCallbackClockNs()
        rendererCallbackDepth.incrementAndGet()
        try {
            // A callback already queued when pause completes is excluded from
            // renderer work and cadence after pause is acknowledged to Flutter.
            if (!rendererPaused) {
                rendererWork()
                recordArSessionFrameCadence(cameraTimestampNs(), callbackArrivalNs)
            }
            sessionWork()
        } finally {
            rendererCallbackDepth.decrementAndGet()
        }
    }

    /** AR session delivery cadence; upload continuation callbacks are a separate stream. */
    internal fun recordArSessionFrameCadence(cameraTimestampNs: Long, callbackArrivalNs: Long) {
        arSessionCallbackCadenceTracker.record(callbackArrivalNs)
        coverageRendererOwner.recordShapeMeasurementFrame(callbackArrivalNs)
        cameraFrameCadenceTracker.record(cameraTimestampNs, callbackArrivalNs)
    }

    /** O(1) ownership and bounded cadence checks for depth image intake. */
    fun depthIntakeFrameHealthy(): Boolean =
        arSessionCallbackCadenceTracker.healthyForDepthIntake() &&
            cameraFrameCadenceTracker.healthyForDepthIntake()

    /** Bounded recent cadence and owner scalars, without windows, timing arrays or backing pages. */
    fun rendererScalarSnapshot(nativeCapturePending: Boolean): Map<String, Any?> {
        checkCoverageRendererThread()
        val started = System.nanoTime()
        val renderer = coverageRendererOwner.status()
        val frames = arSessionCallbackCadenceTracker.snapshot()
        val camera = cameraFrameCadenceTracker.snapshot()
        return rendererScalarReceipt(renderer, frames, camera, configuredCameraFpsLower,
            configuredCameraFpsUpper, nativeCapturePending, arSessionCallbackCadenceTracker.healthyForDepthIntake(),
            cameraFrameCadenceTracker.healthyForDepthIntake(), started, System.nanoTime(),
            android.os.SystemClock.uptimeMillis(),
            rendererTelemetry.resourceScalarSnapshot() + rendererPageFrameScheduler.pressureSnapshot().let {
                mapOf("pageFramesRequested" to it.requested, "pageFramesExecuted" to it.executed,
                    "pageFramesPending" to it.pendingTransactions,
                    "resourceTokenEpoch" to (coverageRendererOwner.currentResourceToken()?.epoch ?: 0L))
            })
    }

    fun rendererPerformanceSnapshot(
        beginMeasurementWindow: Boolean = false,
        captureDiagnosticTiming: Boolean = false,
        freezeDiagnosticTiming: Boolean = false,
        includeDiagnosticTiming: Boolean = false,
        beginShapeMeasurementWindow: Boolean = false,
        endShapeMeasurementWindow: Boolean = false,
    ): Map<String, Any> {
        if (beginShapeMeasurementWindow) coverageRendererOwner.beginShapeMeasurementWindow()
        if (endShapeMeasurementWindow) coverageRendererOwner.endShapeMeasurementWindow()
        if (beginMeasurementWindow) {
            arSessionCallbackCadenceTracker.beginMeasurementWindow(captureDiagnosticTiming)
            cameraFrameCadenceTracker.beginMeasurementWindow(captureDiagnosticTiming)
            rendererMainApplyAttribution = (rendererMainApplyAttribution
                ?: RendererMainApplyAttribution()).also { it.reset() }
        }
        if (freezeDiagnosticTiming) {
            arSessionCallbackCadenceTracker.freezeDiagnosticTiming()
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
        val uploadContinuationCadence = uploadContinuationCadenceTracker.snapshot()
        return arSessionCallbackCadenceTracker.snapshot() + rendererTelemetry.snapshot() + mapOf(
            "callbackCadenceSource" to "arSessionUpdate",
            "uploadContinuationStorageBytes" to UploadContinuationCadenceTracker.MODELED_PRIMITIVE_STORAGE_BYTES,
            "uploadContinuationSampleCount" to uploadContinuationCadence.getValue("sampleCount"),
            "uploadContinuationP95FrameIntervalMs" to uploadContinuationCadence.getValue("p95FrameIntervalMs"),
            "uploadContinuationMaxFrameIntervalMs" to uploadContinuationCadence.getValue("maxFrameIntervalMs"),
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
            "coverageFrozenBackingBytes" to frozenBackingBytes,
        ) + coverageRendererOwner.rendererGeometryStatus() + coverageRendererOwner.shapeMeasurementSnapshot() +
            shapeResourceBudget.snapshot() + cameraMeasurement + (rendererMainApplyAttribution?.snapshot()
            ?: RendererMainApplyAttribution.disabledSnapshot()) +
            (if (includeDiagnosticTiming && debugGapTimingEnabled) mapOf(
                "frameGapTiming" to mapOf(
                    "clock" to "androidMonotonic",
                    "rendererIntervalDomain" to "arSessionUpdateCallbackArrival",
                    "cameraIntervalDomain" to "arCoreCameraTimestamp",
                    "renderer" to arSessionCallbackCadenceTracker.diagnosticTimingSnapshot(),
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
        if (coverageSlotRefs.none { it.get() === binding }) return
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
            nativeFrameAttribution.measureNativeFrame(NativeFrameSpan.UPLOAD) {
                        coverageSlotRefs.forEach { it.get()?.onRendererFrame() }
            }
            uploadContinuationCadenceTracker.record(frameCallbackClockNs())
        }
    }

    private fun consumeCoveragePublications() {
        nativeFrameAttribution.measureNativeFrame(NativeFrameSpan.PUBLICATION) {
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
        if (!status.visible) {
            activeCoverageBinding()?.hideRetainingUploadOwnership()
            return
        }
        val descriptor = coverageRendererOwner.presentationDescriptor()
        val mesh = descriptor?.shapes
        val shapeRows = descriptor?.attributes?.total ?: 0
        val config = activeCoverageConfig()
        val activeBinding = activeCoverageBinding()
        val activeToken = coverageRendererOwner.currentResourceToken()
        val resident = activeToken?.let(coverageRendererOwner::activeDisplayResidencyReceipt)
        val activeSource = coverageRendererOwner.activeDisplayDescriptor()
        val resourceKind = CoveragePresentationEligibility.resourceKind(descriptor, initial = false)
        val displayDiffers = CoveragePresentationEligibility.requiresCandidate(descriptor, activeSource, resident,
            activeBinding?.supportsShapeCapacity(shapeRows) == true)
        if (config != null && descriptor != null && displayDiffers &&
            coverageRendererOwner.currentCandidateResourceToken() == null &&
            resourceKind != CoveragePresentationEligibility.ResourceKind.PENDING) {
            // Delayed attributes (including complete selected point fallbacks)
            // use the same hidden candidate route as canonical replacements.
            val nextConfig = config.copy(enabled = true, voxelRenderMode = status.mode.toVoxelRenderMode())
            val admission = rendererAllocationLedger.admitResourceReplacement(nextConfig.voxelRenderMode,
                sourceCapacity = descriptor.sourceCapacity, retainedCount = descriptor.count)
            if (admission.strategy == CoverageRendererTransitionStrategy.COEXIST) {
                stageCoverageCandidate(descriptor, nextConfig)
            }
        }
        val candidateSlot = preparingCoverageSlot
        val candidateToken = candidateSlot.takeIf { it >= 0 }?.let { coverageSlotTokens[it].value }
        if (candidateSlot >= 0 && candidateToken != null &&
            (coverageRendererOwner.displayRoleFor(candidateToken) == null ||
                !coverageRendererOwner.candidatePromotionAllowed(candidateToken))
        ) {
            cancelCoverageCandidateSlot(candidateSlot, candidateToken)
            // Created/charged candidates retain their slot/backing until matching drain.
        }
        val token = coverageRendererOwner.currentResourceToken() ?: coverageRendererOwner.issueResourceToken() ?: return
        if (!coverageRendererOwner.acceptsResourceToken(token)) return
        rendererTelemetry.setResidentPresentation(
            token, status.mode, status.selectedRowCount, status.residentGlyphCount,
        )
        val binding = activeCoverageBinding()
        if (binding != null && fullRefresh) binding.requireFullRefresh()
        val activeDescriptor = coverageRendererOwner.activeDisplayDescriptor()
        if (binding != null && activeDescriptor != null) {
            binding.updateCoverageDescriptor(activeDescriptor, status.mode.toVoxelRenderMode())
        } else {
            binding?.updateCoverage(status.mode.toVoxelRenderMode())
        }
        val candidateBinding = preparingCoverageBinding()
        val candidateDescriptorForBinding = coverageRendererOwner.candidateDisplayDescriptor()
        if (candidateBinding != null && candidateDescriptorForBinding != null) {
            candidateBinding.updateCoverageDescriptor(candidateDescriptorForBinding, status.mode.toVoxelRenderMode())
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
        bindNativeFrameAttribution(null)
        if (!ownership.onDispose()) return
        rendererProvenance("dispose-request")
        disposed = true
        coveragePublications.close()
        coveragePresentationRefreshPending.set(false)
        rendererPaused = true
        sceneRenderLifecycle.pauseForTeardown()
        clearCoverageResourceState()
        coverageRendererOwner.dispose()
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
        // SceneView 4.47.0 keeps config stable across resume. Release the first-resume gate only
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

    /** Renderer-thread atomic handoff after both point and shape receipts. */
    private fun promoteCoverageCandidate() {
        val token = coverageRendererOwner.currentCandidateResourceToken() ?: return
        val candidateSlot = preparingCoverageSlot.takeIf { it >= 0 } ?: return
        val binding = coverageSlotRef(candidateSlot).get() ?: return
        val descriptor = coverageRendererOwner.candidateDisplayDescriptor() ?: return
        if (!coverageRendererOwner.candidatePromotionAllowed(token)) {
            cancelCoverageCandidateSlot(candidateSlot, token)
            requestCoveragePresentationRefresh()
            return
        }
        if (!CoveragePresentationPromotion.promote(
            coverageRendererOwner,
            coverageResourceFactory,
            binding,
        )) return
        val outgoingSlot = activeCoverageSlot
        val outgoing = coverageSlotRef(outgoingSlot).get()
        outgoing?.takeUnless { it === binding }?.setDisplayVisible(false)
        binding.promoteToActive()
        activeCoverageSlot = candidateSlot
        coverageSlotConfig(candidateSlot).value = coverageSlotConfig(candidateSlot).value?.copy(enabled = true)
        preparingCoverageSlot = -1
        retiringCoverageSlot = outgoingSlot.takeUnless { it == candidateSlot } ?: -1
        // The active binding stays in its physical Compose slot. The old slot
        // remains owned until the factory's disposal callback proves its drain.
        // Detach the old binding after the active token has switched. This
        // retires its factory generation through the normal callback drain and
        // releases its descriptor backing; the new candidate remains mounted.
        outgoing?.takeUnless { it === binding }?.disposeForReplacement()
        coverageRendererOwner.releaseActiveDisplaySource(token)
        binding.releaseCompletedDescriptorSource()
        frozenBacking.release(token)
        rendererTelemetry.setResidentPresentation(
            token,
            coverageRendererOwner.status().mode,
            descriptor.count,
            descriptor.glyphCount,
        )
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
        role: CoverageDisplayRole = CoverageDisplayRole.ACTIVE,
    ): CoverageMeshAttachment? {
        val resources = remember(engine, token) {
            createCoverageResource {
                val factory = if (role == CoverageDisplayRole.CANDIDATE) coverageCandidateResourceFactory else coverageResourceFactory
                if (role == CoverageDisplayRole.CANDIDATE) {
                    factory.preparePoint(
                        VoxelRenderMode.POINTS, CoverageRendererLimits.RAW_POINT_CAPACITY, "coverage-points",
                        rendererGeneration = coverage.rendererGeneration, token = checkNotNull(token),
                        create = { _, capacity, owner -> CoveragePointMeshResources(engine, capacity, telemetry, owner) },
                        release = CoverageVoxelMeshResources::destroy,
                    )
                } else {
                    factory.replacePoint(
                        VoxelRenderMode.POINTS, CoverageRendererLimits.RAW_POINT_CAPACITY, "coverage-points",
                        rendererGeneration = coverage.rendererGeneration, token = token,
                        create = { _, capacity, owner -> CoveragePointMeshResources(engine, capacity, telemetry, owner) },
                        release = CoverageVoxelMeshResources::destroy,
                    )
                }
            }
        }
        if (resources == null) return null
        val material = rememberCoverageResourceMaterial(resources, materialLoader) {
            materialLoader.createMaterial("materials/coverage_points.filamat")
        }
        val materialInstance = remember(materialLoader, material) {
            materialLoader.createInstance(material)
        }
        val binding = remember(resources, materialInstance, coverage.pointSizePx) {
            CoveragePointMeshBinding(
                mode = VoxelRenderMode.POINTS,
                target = CoverageMeshTarget(resources, materialInstance),
                owner = coverageRendererOwner,
                pointSizePx = coverage.pointSizePx,
                displayVisible = role == CoverageDisplayRole.ACTIVE,
                readCoverageSnapshot = coverageRendererOwner::presentationSnapshot,
                readCoverageDescriptor = coverageRendererOwner::displayDescriptorFor,
                resourceToken = token,
                acceptsResourceToken = coverageRendererOwner::acceptsAnyResourceToken,
                requestRendererFrame = ::requestCoverageUploadFrame,
                cancelRendererFrame = ::cancelCoverageUploadFrame,
                releaseResources = { releaseCoverageResources(token, resources) },
                onUploadFailure = {
                    rendererTelemetry.recordResourceFailure()
                    token?.let(coverageRendererOwner::markAnyResourceFailure)
                },
                onCandidateReady = if (role == CoverageDisplayRole.CANDIDATE) ::promoteCoverageCandidate else ({}),
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

    private fun createAttributedResources(engine: Engine, capacity: Int,
        mode: CoveragePresentationMode, rows: Int, token: CoverageResourceToken,
        telemetry: RendererTelemetry, pointOwner: String,
        role: CoverageDisplayRole = CoverageDisplayRole.ACTIVE): CoverageVoxelMeshResources {
        val baseBytes = CoverageRendererLimits.ownershipReceipt(VoxelRenderMode.CENTROIDS).totalBytes.toLong()
        check(shapeResourceBudget.reserve(token, CoverageShapeResourceBudget.bytes(mode, rows),
            shapeAllowanceBytes.toLong(), baseBytes)) { "attributed resource admission refused" }
        return try {
            CoverageAttributedMeshResources(engine, capacity, mode, rows, token, coverageRendererOwner,
                telemetry, shapeResourceBudget,
                role)
        } catch (failure: RuntimeException) {
            // Partial native construction is unknown. Keep the token charged;
            // no later candidate can turn it into an uncounted third owner.
            throw failure
        }
    }

    @Composable
    private fun CoverageActiveCentroidNode(
        engine: Engine,
        materialLoader: MaterialLoader,
        telemetry: RendererTelemetry,
        coverage: PointCloudNativeConfig,
        token: CoverageResourceToken?,
        role: CoverageDisplayRole = CoverageDisplayRole.ACTIVE,
    ): CoverageMeshAttachment? {
        val descriptor = coverageRendererOwner.displayDescriptorFor(token)
        val resourceKind = CoveragePresentationEligibility.resourceKind(descriptor,
            initial = role == CoverageDisplayRole.ACTIVE)
        val mountedResident = CoveragePresentationEligibility.mountedResidencyResource(
            descriptor, role, token, coverageRendererOwner, coverageResourceFactory) as? CoverageVoxelMeshResources
        if (resourceKind == CoveragePresentationEligibility.ResourceKind.PENDING && mountedResident == null) return null
        val selectedShapeRows = descriptor?.attributes?.total ?: 0
        val shapeMode = descriptor?.mode ?: coverageRendererOwner.status().mode
        val resources = remember(engine, token) {
            mountedResident ?: createCoverageResource {
                val factory = if (role == CoverageDisplayRole.CANDIDATE) coverageCandidateResourceFactory else coverageResourceFactory
                if (role == CoverageDisplayRole.CANDIDATE) factory.preparePoint(
                    VoxelRenderMode.CENTROIDS,
                    CoverageRendererLimits.CENTROID_CAPACITY,
                    "coverage-centroids",
                    rendererGeneration = coverage.rendererGeneration,
                    token = checkNotNull(token),
                    create = { _, capacity, owner ->
                        if (resourceKind == CoveragePresentationEligibility.ResourceKind.ATTRIBUTED &&
                            (shapeMode == CoveragePresentationMode.PLANAR_PATCHES || shapeMode == CoveragePresentationMode.DIRECTIONAL_BALLS))
                            createAttributedResources(engine, capacity, shapeMode, selectedShapeRows, token, telemetry, owner, role)
                        else CoveragePointMeshResources(engine, capacity, telemetry, owner)
                    },
                    release = CoverageVoxelMeshResources::destroy,
                ) else factory.replacePoint(
                    VoxelRenderMode.CENTROIDS,
                    CoverageRendererLimits.CENTROID_CAPACITY,
                    "coverage-centroids",
                    rendererGeneration = coverage.rendererGeneration,
                    token = token,
                    create = { _, capacity, owner ->
                        if (resourceKind == CoveragePresentationEligibility.ResourceKind.ATTRIBUTED && token != null &&
                            (shapeMode == CoveragePresentationMode.PLANAR_PATCHES || shapeMode == CoveragePresentationMode.DIRECTIONAL_BALLS))
                            createAttributedResources(engine, capacity, shapeMode, selectedShapeRows, token, telemetry, owner, role)
                        else CoveragePointMeshResources(engine, capacity, telemetry, owner)
                    },
                    release = CoverageVoxelMeshResources::destroy,
                )
            }
        }
        if (resources == null) return null
        val material = rememberCoverageResourceMaterial(resources, materialLoader) {
            materialLoader.createMaterial("materials/coverage_points.filamat")
        }
        val materialInstance = remember(materialLoader, material) {
            materialLoader.createInstance(material)
        }
        val binding = remember(resources, materialInstance, coverage.pointSizePx) {
            CoveragePointMeshBinding(
                mode = VoxelRenderMode.CENTROIDS,
                target = CoverageMeshTarget(resources, materialInstance),
                owner = coverageRendererOwner,
                pointSizePx = coverage.pointSizePx,
                displayVisible = role == CoverageDisplayRole.ACTIVE,
                readCoverageSnapshot = coverageRendererOwner::presentationSnapshot,
                readCoverageDescriptor = coverageRendererOwner::displayDescriptorFor,
                resourceToken = token,
                acceptsResourceToken = coverageRendererOwner::acceptsAnyResourceToken,
                requestRendererFrame = ::requestCoverageUploadFrame,
                cancelRendererFrame = ::cancelCoverageUploadFrame,
                releaseResources = { releaseCoverageResources(token, resources) },
                onUploadFailure = {
                    rendererTelemetry.recordResourceFailure()
                    token?.let(coverageRendererOwner::markAnyResourceFailure)
                },
                onCandidateReady = if (role == CoverageDisplayRole.CANDIDATE) ::promoteCoverageCandidate else ({}),
            )
        }
        val node = remember(engine, resources, material, materialInstance) {
            if (resources is CoverageAttributedMeshResources) CoverageOwnedAttributedNode(
                engine, resources, CoveragePointMeshResources.DEFAULT_BOUNDING_BOX, materialInstance,
                binding::clearNode, { releaseCoverageResources(token, resources) },
                { materialLoader.destroyMaterialInstance(materialInstance); materialLoader.destroyMaterial(material) },
            ).also(binding::setNode) else CoverageOwnedMeshNode(
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
        role: CoverageDisplayRole = CoverageDisplayRole.ACTIVE,
    ): CoverageMeshAttachment? {
        val resources = remember(
            engine,
            token,
            coverage.voxelSizeMeters,
            coverage.cubeSizeFactor,
        ) {
            createCoverageResource {
                val factory = if (role == CoverageDisplayRole.CANDIDATE) coverageCandidateResourceFactory else coverageResourceFactory
                if (role == CoverageDisplayRole.CANDIDATE) factory.prepareCube(
                    CoverageRendererLimits.CUBE_CAPACITY,
                    "coverage-cubes",
                    rendererGeneration = coverage.rendererGeneration,
                    token = checkNotNull(token),
                    create = { _, capacity, owner -> CoverageCubeMeshResources(engine, capacity, coverage.voxelSizeMeters * coverage.cubeSizeFactor, telemetry, owner) },
                    release = CoverageVoxelMeshResources::destroy,
                ) else factory.replaceCube(
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
        val material = rememberCoverageResourceMaterial(resources, materialLoader) {
            materialLoader.createMaterial("materials/coverage_cubes.filamat")
        }
        val materialInstance = remember(materialLoader, material) {
            materialLoader.createInstance(material)
        }
        val outlineMaterialInstance = rememberCoverageResourceMaterial(resources, materialLoader) {
            materialLoader.createUnlitColorInstance(android.graphics.Color.BLACK)
        }
        val binding = remember(resources, materialInstance, coverage.pointSizePx) {
            CoveragePointMeshBinding(
                mode = VoxelRenderMode.CUBES,
                target = CoverageMeshTarget(resources, materialInstance),
                owner = coverageRendererOwner,
                pointSizePx = coverage.pointSizePx,
                displayVisible = role == CoverageDisplayRole.ACTIVE,
                readCoverageSnapshot = coverageRendererOwner::presentationSnapshot,
                readCoverageDescriptor = coverageRendererOwner::displayDescriptorFor,
                resourceToken = token,
                acceptsResourceToken = coverageRendererOwner::acceptsAnyResourceToken,
                requestRendererFrame = ::requestCoverageUploadFrame,
                cancelRendererFrame = ::cancelCoverageUploadFrame,
                releaseResources = { releaseCoverageResources(token, resources) },
                onUploadFailure = {
                    rendererTelemetry.recordResourceFailure()
                    token?.let(coverageRendererOwner::markAnyResourceFailure)
                },
                onCandidateReady = if (role == CoverageDisplayRole.CANDIDATE) ::promoteCoverageCandidate else ({}),
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
        role: CoverageDisplayRole = CoverageDisplayRole.ACTIVE,
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
                        coverageRendererOwner.markAnyResourceMounted(it)
                        coverageResourceFactory.markResourceMounted(it)
                        coverageCreationRecovery.mounted(it)
                }
                token?.let { onCoverageRendererMounted(true, it.epoch) }
            }
            onDispose {
                val wasCurrent = coverageMeshRef.compareAndSet(binding, null)
                binding.dispose()
                if (wasCurrent) {
                    token?.let(coverageRendererOwner::markAnyResourceFailure)
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

    private class CoverageOwnedAttributedNode(
        engine: Engine,
        private val resources: CoverageAttributedMeshResources,
        boundingBox: com.google.android.filament.Box,
        material: com.google.android.filament.MaterialInstance,
        private val beforeDestroy: () -> Unit,
        private val releaseResources: () -> Unit,
        private val releaseMaterial: () -> Unit,
    ) : RenderableNode(engine) {
        private var closed = false
        init {
            RenderableManager.Builder(3).boundingBox(boundingBox).culling(true)
                .geometry(0, RenderableManager.PrimitiveType.POINTS, resources.vertexBuffer, resources.indexBuffer, 0, 0)
                .material(0, material)
                .geometry(1, RenderableManager.PrimitiveType.TRIANGLES, resources.shapeVertexBuffer, resources.shapeIndexBuffer, 0, 0)
                .material(1, material)
                .geometry(2, RenderableManager.PrimitiveType.POINTS, resources.shapeVertexBuffer, resources.shapeIndexBuffer, 0, 0)
                .material(2, material).build(engine, entity)
            isVisible = false
        }
        override fun destroy() {
            if (closed) return
            closed = true; beforeDestroy(); super.destroy(); releaseResources(); releaseMaterial()
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
    ) : RenderableNode(engine = engine) {
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
        private val owner: NativeCoverageRendererOwner,
        private val pointSizePx: Float,
        private var displayVisible: Boolean = true,
        private val readCoverageSnapshot: () -> CoveragePointRenderSnapshot?,
        private val readCoverageDescriptor: (CoverageResourceToken?) -> BoundedCoveragePresentation?,
        private val resourceToken: CoverageResourceToken?,
        private val acceptsResourceToken: (CoverageResourceToken) -> Boolean,
        private val requestRendererFrame: (CoveragePointMeshBinding) -> Unit,
        private val cancelRendererFrame: () -> Unit,
        private val releaseResources: () -> Unit,
        private val onUploadFailure: () -> Unit,
        private val onCandidateReady: () -> Unit = {},
    ) : CoveragePromotionReadiness {
        private var latestPresentationDescriptor: BoundedCoveragePresentation? = null
        @Volatile private var attached = false
        @Volatile private var disposed = false
        private var candidateReadyNotified = false

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

        fun setDisplayVisible(value: Boolean) {
            displayVisible = value
            val residencyVisible = resourceToken?.let(owner::isDisplayResidencyVisible) ?: false
            target.resources.setDisplayVisible(value && residencyVisible)
            if (!value || !residencyVisible) target.node?.isVisible = false
        }

        fun promoteToActive() {
            displayVisible = true
            (target.resources as? CoverageAttributedMeshResources)?.promoteToActive()
            target.resources.setDisplayVisible(true)
            updateActiveTarget()
        }

        fun releaseCompletedDescriptorSource() {
            latestPresentationDescriptor = null
            target.resources.releaseCompletedDescriptorSource()
        }

        fun updateCoverage(requestedMode: VoxelRenderMode = mode) {
            if (disposed || requestedMode != mode) return
            if (attached) updateActiveTarget()
        }
        fun hideRetainingUploadOwnership() {
            if (disposed) return
            target.node?.let(target.resources::hide)
        }

        fun supportsShapeCapacity(rows: Int): Boolean =
            (target.resources as? CoverageAttributedMeshResources)?.selectedCapacity?.let { it >= rows } == true

        override fun isWholeDescriptorUploadComplete(descriptor: BoundedCoveragePresentation): Boolean =
            CoveragePresentationEligibility.wholeUploadComplete(descriptor,
                target.resources.isWholeDescriptorUploadComplete(descriptor),
                target.resources is CoverageAttributedMeshResources,
                initial = owner.displayRoleFor(resourceToken) == CoverageDisplayRole.ACTIVE,
                completedAttributed = resourceToken?.let { owner.completedShapeDrawReceipt(it, descriptor) } != null)

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
                val token = resourceToken
                val role = owner.displayRoleFor(token)
                if (role == CoverageDisplayRole.CANDIDATE && !owner.candidatePromotionAllowed(token)) {
                    // The real host callback cancels/drains this exact slot;
                    // obsolete/expired preparation must not submit another page.
                    onCandidateReady()
                    return
                }
                target.resources.onRendererFrame()
                val descriptor = latestPresentationDescriptor ?: readCoverageDescriptor(token)
                if (role == CoverageDisplayRole.ACTIVE && descriptor != null &&
                    isWholeDescriptorUploadComplete(descriptor) &&
                    owner.releaseActiveDisplaySource(token)
                ) {
                    // Keep draw ranges in the GPU resource while dropping all
                    // descriptor/shape source aliases behind the compact receipt.
                    target.resources.releaseCompletedDescriptorSource()
                    latestPresentationDescriptor = null
                }
                val residencyVisible = owner.isDisplayResidencyVisible(token)
                target.resources.setDisplayVisible(displayVisible && residencyVisible)
                if (!displayVisible || !residencyVisible) target.node?.isVisible = false
                if (role == CoverageDisplayRole.CANDIDATE && !candidateReadyNotified) {
                    if (descriptor != null && isWholeDescriptorUploadComplete(descriptor)) {
                        candidateReadyNotified = true
                        onCandidateReady()
                    }
                }
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
            val descriptor = latestPresentationDescriptor ?: readCoverageDescriptor(resourceToken)
            val residencyVisible = owner.isDisplayResidencyVisible(resourceToken)
            if (descriptor == null && owner.activeDisplayResidencyReceipt(resourceToken) != null) {
                target.resources.setDisplayVisible(displayVisible && residencyVisible)
                if (!displayVisible || !residencyVisible) currentNode.isVisible = false
                return
            }
            if (descriptor != null) {
                target.resources.setDisplayVisible(displayVisible && residencyVisible)
                when (val resources = target.resources) {
                    is CoverageAttributedMeshResources -> resources.updateDescriptor(currentNode, descriptor,
                        target.materialInstance, pointSizePx)
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
                if (!displayVisible || !residencyVisible) currentNode.isVisible = false
                if (target.resources is CoverageAttributedMeshResources || target.resources is CoveragePointMeshResources ||
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

/**
 * The node destroys its materials when its exact mesh resource is retired.
 * A physical Compose slot can outlive that node; only a retained live resource
 * may retain its materials across recomposition.
 */
@Composable
internal fun <Resource : Any, Loader : Any, Material : Any> rememberCoverageResourceMaterial(
    resources: Resource,
    materialLoader: Loader,
    create: () -> Material,
): Material = remember(resources, materialLoader, calculation = create)

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

/** Scalar projection shared with host checks; source maps and arrays are never retained. */
internal fun rendererScalarReceipt(
    renderer: CoverageRendererStatus,
    frames: Map<String, Any>,
    camera: Map<String, Any>,
    configuredCameraFpsLower: Int,
    configuredCameraFpsUpper: Int,
    nativeCapturePending: Boolean,
    rendererCadenceEligible: Boolean,
    cameraCadenceEligible: Boolean,
    started: Long,
    finished: Long,
    uptimeMs: Long,
    resourceScalars: Map<String, Any?> = emptyRendererResourceScalars(),
): Map<String, Any?> {
        return mapOf(
            "renderer" to mapOf(
                "unavailable" to renderer.rendererUnavailable,
                "visible" to renderer.visible,
                "mode" to renderer.mode.name,
                "rowCount" to renderer.rowCount,
                "selectedRows" to renderer.selectedRowCount,
                "rendererGeneration" to renderer.rendererGeneration,
                "geometryRevision" to renderer.geometryRevision,
                "styleRevision" to renderer.styleRevision,
                "resourceAvailable" to renderer.resourceAvailable,
                "recoveryPending" to renderer.recoveryPending,
            ) + resourceScalars,
            "cadence" to mapOf(
                "sampleCount" to frames["sampleCount"],
                "medianFrameIntervalMs" to frames["medianFrameIntervalMs"],
                "medianFps" to frames["medianFps"],
                "p95FrameIntervalMs" to frames["p95FrameIntervalMs"],
                "p99FrameIntervalMs" to frames["p99FrameIntervalMs"],
                "maxFrameIntervalMs" to frames["maxFrameIntervalMs"],
                "frameGapsOver50Ms" to frames["frameGapsOver50Ms"],
                "frameGapsOver100Ms" to frames["frameGapsOver100Ms"],
                "cameraSampleCount" to camera["sampleCount"],
                "cameraMedianFrameIntervalMs" to camera["medianFrameIntervalMs"],
                "cameraMedianFps" to camera["medianFps"],
                "cameraP95FrameIntervalMs" to camera["p95FrameIntervalMs"],
                "cameraP99FrameIntervalMs" to camera["p99FrameIntervalMs"],
                "cameraMaxFrameIntervalMs" to camera["maxFrameIntervalMs"],
                "cameraFrameGapsOver50Ms" to camera["frameGapsOver50Ms"],
                "cameraFrameGapsOver100Ms" to camera["frameGapsOver100Ms"],
                "configuredCameraFpsLower" to configuredCameraFpsLower,
                "configuredCameraFpsUpper" to configuredCameraFpsUpper,
            ),
            "gates" to mapOf(
                "rendererCadenceEligible" to rendererCadenceEligible,
                "cameraCadenceEligible" to cameraCadenceEligible,
                "nativeCapturePending" to nativeCapturePending,
            ),
            "sampling" to mapOf(
                "rendererCapturedStartNs" to started.toString(),
                "rendererCapturedEndNs" to finished.toString(),
                "deviceUptimeMs" to uptimeMs,
            ),
        )
}

/** Defaults also pin the complete native producer fixture to the ordinary scalar contract. */
internal fun emptyRendererResourceScalars(): Map<String, Any?> = mapOf(
    "resourcesAcquired" to 0L,
    "resourceFailureCount" to 0L,
    "resourceReplacementCount" to 0L,
    "resourceDisposalCount" to 0L,
    "lastResourceFailureStage" to "none",
    "lastResourceFailureClass" to "none",
    "lastAdmissionStrategy" to "none",
    "lastAdmissionCurrentBytes" to 0L,
    "lastAdmissionCandidateBytes" to 0L,
    "lastAdmissionCombinedBytes" to 0L,
    "uploadPageSubmissionCount" to 0L,
    "completedUploadCount" to 0L,
    "pageFramesRequested" to 0L,
    "pageFramesExecuted" to 0L,
    "pageFramesPending" to 0L,
    "resourceTokenEpoch" to 0L,
)
