package com.uhg0.ar_flutter_plugin_2

import com.uhg0.ar_flutter_plugin_2.diagnostics.NativeFrameSpan
import com.uhg0.ar_flutter_plugin_2.diagnostics.measureNativeFrame

import android.app.Activity
import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
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
import com.uhg0.ar_flutter_plugin_2.capture.CaptureSafetySignalV2
import com.uhg0.ar_flutter_plugin_2.capture.CaptureSessionException
import com.uhg0.ar_flutter_plugin_2.capture.NativeCaptureRecoveryAdmissionExceptionV2
import com.uhg0.ar_flutter_plugin_2.capture.PoseBatchDispatcher
import com.uhg0.ar_flutter_plugin_2.capture.ReplayableShutdownPreparationV2
import com.uhg0.ar_flutter_plugin_2.capture.StorageBudgetCoordinatorV2
import com.uhg0.ar_flutter_plugin_2.capture.StorageBudgetPolicyV2
import com.uhg0.ar_flutter_plugin_2.sceneview.PluginAnchorRecord
import com.uhg0.ar_flutter_plugin_2.sceneview.PluginHitResult
import com.uhg0.ar_flutter_plugin_2.sceneview.PluginNodeRecord
import com.uhg0.ar_flutter_plugin_2.sceneview.PluginNodeSource
import com.uhg0.ar_flutter_plugin_2.sceneview.PluginSessionConfig
import com.uhg0.ar_flutter_plugin_2.sceneview.PluginTransform
import com.uhg0.ar_flutter_plugin_2.sceneview.SceneViewHost
import com.uhg0.ar_flutter_plugin_2.sceneview.BoundedOperationCoordinator
import com.uhg0.ar_flutter_plugin_2.sceneview.decompose
import com.uhg0.ar_flutter_plugin_2.sceneview.resolveNodeUri
import com.uhg0.ar_flutter_plugin_2.visibilitygrid.VisibilityGridV2Binding
import com.uhg0.ar_flutter_plugin_2.visibilitygrid.VisibilityGridIntegration
import com.uhg0.ar_flutter_plugin_2.visibilitygrid.NativeRendererProjection
import com.uhg0.ar_flutter_plugin_2.visibilitygrid.RendererPublicationTimingScope
import com.uhg0.ar_flutter_plugin_2.visibilitygrid.CanonicalRuntimeResources
import com.uhg0.ar_flutter_plugin_2.visibilitygrid.AndroidVisibilityGridRuntime
import com.uhg0.ar_flutter_plugin_2.sceneview.OpenArViews
import com.uhg0.ar_flutter_plugin_2.visibilityingest.VisibilityGridShellRegistry
import com.uhg0.ar_flutter_plugin_2.visibilitygrid.CanonicalReadyHintOwner
import com.uhg0.ar_flutter_plugin_2.visibilitygrid.ArCoreVisibilityObservationSource
import com.uhg0.ar_flutter_plugin_2.visibilitygrid.DepthObservationPauseGate
import com.uhg0.ar_flutter_plugin_2.visibilitygrid.VisibilityObservationDebugChannel
import com.uhg0.ar_flutter_plugin_2.visibilitygrid.VisibilityObservationDebugGate
import com.uhg0.ar_flutter_plugin_2.visibilitygrid.VisibilityCaptureSafePredicate
import com.uhg0.ar_flutter_plugin_2.visibilitygrid.VisibilityPressureOwnerScalars
import com.uhg0.ar_flutter_plugin_2.visibilitygrid.VisibilitySmallSceneDebugChannel
import com.uhg0.ar_flutter_plugin_2.visibilitygrid.VisibilitySmallSceneProductHooks
import com.uhg0.ar_flutter_plugin_2.visibilitygrid.VisibilityCanonicalFaultGate
import com.uhg0.ar_flutter_plugin_2.visibilitygrid.VisibilitySmallSceneReceiptScalars
import com.uhg0.ar_flutter_plugin_2.visibilityprotocol.CommittedBaselineAuthority
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
import com.uhg0.ar_flutter_plugin_2.capture.CaptureLifecycleDrain
import com.uhg0.ar_flutter_plugin_2.playback.ArPlaybackConfig
import com.uhg0.ar_flutter_plugin_2.playback.ArPlaybackController
import com.uhg0.ar_flutter_plugin_2.recording.ArCoreRecordingPort
import com.uhg0.ar_flutter_plugin_2.recording.ArPlaybackTrackReader
import com.uhg0.ar_flutter_plugin_2.recording.ArRecordingRequestParse
import com.uhg0.ar_flutter_plugin_2.recording.ArRecordingRequests
import com.uhg0.ar_flutter_plugin_2.recording.ArSessionRecorder
import com.uhg0.ar_flutter_plugin_2.recording.readTrackRecords
import com.uhg0.ar_flutter_plugin_2.recording.recordTrackBytes
import java.util.concurrent.Executor
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** SceneView 4.47.0 platform-view implementation. Flutter channels remain unchanged. */
internal class ArView(
    context: Context,
    private val activity: Activity,
    private val lifecycle: Lifecycle,
    messenger: BinaryMessenger,
    id: Int,
    initialSessionFeatures: Set<Session.Feature> = emptySet(),
    requestedRearCameraId: String? = null,
    depthInputEnabled: Boolean = true,
    maxCameraImageHeight: Int = 0,
    private val retainAssessedOriginals: Boolean = false,
    CommittedBaselineAuthority: CommittedBaselineAuthority,
    /** Debug/profile ARCore playback test mode; null runs the camera. */
    playbackConfig: ArPlaybackConfig? = null,
) : PlatformView {
    private val playback = playbackConfig?.let(::ArPlaybackController)
    private val root = FrameLayout(context)
    /** Developer-build ARCore session recording (`startRecording` session call). */
    private val recorder = ArSessionRecorder()
    /** Playback custom tracks posted to Flutter as `onArPlaybackTrackData`. */
    private val playbackTracks = playbackConfig?.trackIds?.takeIf { it.isNotEmpty() }?.let { ids ->
        ArPlaybackTrackReader(ids, deliver = { records ->
            root.post {
                if (!disposed) sessionChannel.invokeMethod("onArPlaybackTrackData", ArPlaybackTrackReader.event(records))
            }
        })
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val sessionChannel = MethodChannel(messenger, "arsession_$id")
    private val objectChannel = MethodChannel(messenger, "arobjects_$id")
    private val anchorChannel = MethodChannel(messenger, "aranchors_$id")
    private val captureChannel = MethodChannel(messenger, "arcapture_$id")
    private val poseFlushHandler = Handler(Looper.getMainLooper())
    private val poseBatchDispatcher = PoseBatchDispatcher(
        send = { method, arguments, result ->
            captureChannel.invokeMethod(method, arguments, result)
        },
        samplesPerSend = POSE_SAMPLES_PER_SEND,
        scheduleFlush = { flush -> poseFlushHandler.postDelayed(flush, POSE_MAX_HOLD_MS) },
        cancelFlush = poseFlushHandler::removeCallbacks,
    )
    private val nodeRecords = mutableMapOf<String, PluginNodeRecord>()
    private val nodeAnchorIds = mutableMapOf<String, String>()
    private val anchorRecords = mutableMapOf<String, PluginAnchorRecord>()
    private val detectedPlanes = mutableSetOf<Plane>()
    private var sessionConfig = defaultSessionConfig()
    private var sessionPausedByFlutter = false
    private val shutdownPreparation = ReplayableShutdownPreparationV2()
    private var capturePauseOperations = 0
    private val afterCapturePauseCallbacks = mutableListOf<() -> Unit>()
    private var disposed = false
    private var disposeCompleted = false
    private val disposeCompletionCallbacks = mutableListOf<(Result<Unit>) -> Unit>()
    private var disposeTerminalResult: Result<Unit>? = null
    private var coverageRendererMounted = false
    private val pendingCloudOperations = mutableSetOf<() -> Unit>()
    private val isDebuggable =
        context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
    private val mapperFailureProbeLogged = AtomicBoolean(false)

    private enum class ResumeTerminal {
        SUCCESS,
        SUPERSEDED,
        CANCELLED,
        TIMEOUT,
        FAILED,
    }
    private val resumeCoordinator = BoundedOperationCoordinator<ResumeTerminal>(
        launchOperation = { operation -> scope.launch(Dispatchers.Default) { operation() } },
        scheduleDeadline = { delayMillis, operation -> scope.launch { delay(delayMillis); operation() } },
        dispatchTerminal = { operation -> scope.launch { operation() } },
    )

    private val sceneHost = SceneViewHost(
        context = context,
        activity = activity,
        lifecycle = lifecycle,
        sessionFeatures = initialSessionFeatures,
        requestedRearCameraId = requestedRearCameraId,
        depthInputEnabled = depthInputEnabled,
        maxCameraImageHeight = maxCameraImageHeight,
        onSessionUpdated = ::onFrame,
        onTrackingFailureChanged = { failure ->
            sessionChannel.invokeMethod("onTrackingFailure", failure)
        },
        onCoverageRendererMounted = ::setCoverageRendererMounted,
        onTouch = ::onTouch,
        onNodeGesture = ::onNodeGesture,
        playback = playback,
        onPlaybackViewRestart = { event ->
            root.post { if (!disposed) sessionChannel.invokeMethod("onArPlaybackFinished", event) }
        },
    )
    private val visibilityRendererProjection =
        NativeRendererProjection(
            render = sceneHost::updateCoverageRenderer,
            publishPresentation = sceneHost::updateCoveragePresentation,
            publishTimingScope = RendererPublicationTimingScope.MAILBOX_ENQUEUE,
            timingSink = if (isDebuggable) {
                { timing ->
                    Log.d(
                        "VisibilityRendererTiming",
                        "style=${timing.styleRevision} " +
                            "requestedRows=${timing.requestedRows} " +
                            "sourceRows=${timing.sourceRows} " +
                            "selectedRows=${timing.selectedRows} " +
                            "stateApplyMicros=${timing.stateApplyMicros} " +
                            "stateApplyAllocatedBytes=${timing.stateApplyAllocatedBytes} " +
                            "selectionMicros=${timing.selectionMicros} " +
                            "selectionAllocatedBytes=${timing.selectionAllocatedBytes} " +
                            "descriptorMicros=${timing.descriptorMicros} " +
                            "descriptorAllocatedBytes=${timing.descriptorAllocatedBytes} " +
                            "publishMicros=${timing.publishMicros} " +
                            "publishAllocatedBytes=${timing.publishAllocatedBytes} " +
                            "publishTimingScope=${timing.publishTimingScope.wireName} " +
                            "allocationScope=${timing.allocationScope} " +
                            "callbackAllocatedBytes=${timing.callbackAllocatedBytes} " +
                            "callbackMicros=${timing.callbackMicros}",
                    )
                }
            } else {
                null
            },
        )
    private val canonicalReadyHints = if (BuildConfig.COVERAGE_CANONICAL_READY_WAKE) {
        CanonicalReadyHintOwner.tryCreate(
            post = { task -> root.post { task() } },
            send = { bytes, done ->
                sessionChannel.invokeMethod("onCanonicalReady", bytes, object : MethodChannel.Result {
                    override fun success(result: Any?) = done(result == true)
                    override fun error(code: String, message: String?, details: Any?) = done(false)
                    override fun notImplemented() = done(false)
                })
            },
        )
    } else null
    private val visibilityGridV2Binding = VisibilityGridV2Binding(
        messenger = messenger,
        viewId = id,
        CommittedBaselineAuthority = CommittedBaselineAuthority,
        isDebuggable = isDebuggable,
        onRendererStyleCut = visibilityRendererProjection::applyStyleCut,
        coverageRendererOwner = sceneHost.coverageRendererOwner,
        canonicalReadyHints = canonicalReadyHints,
    )
    private val visibilityObservationDebugGate = VisibilityObservationDebugGate()
    private val visibilityCanonicalFaultGate = VisibilityCanonicalFaultGate(isDebuggable)
    // #101 owns this native proof only.  It remains false until an internal
    // V2 capture owner binds one exact durable attempt/cut; Dart cannot enable it.
    private val captureSafetySignalV2 = CaptureSafetySignalV2()
    // One physical accounting root is shared across successive group-owned canonical surface resources.
    // Each resource borrows this coordinator and releases its owner before a replacement opens.
    private val visibilityStorageBudgetCoordinator = StorageBudgetCoordinatorV2(
        File(context.filesDir, "visibility-grid-canonical-surface-runtime"),
        StorageBudgetPolicyV2(64L * 1024L * 1024L, 0),
    )
    private val visibilityObservationMappingAdmission = VisibilityGridIntegration(
        binding = visibilityGridV2Binding,
        ownership = visibilityGridV2Binding::currentObservationOwnership,
        directory = context.filesDir,
        resourcesForGroup = { group ->
            CanonicalRuntimeResources.openLive(context.filesDir, group, visibilityStorageBudgetCoordinator)
        },
        renderer = visibilityRendererProjection,
        commitCanonical = visibilityCanonicalFaultGate::commit,
        beforeAdmission = visibilityObservationDebugGate::awaitIfArmed,
        allocationCounter = if (isDebuggable) {
            { android.os.Debug.getRuntimeStat("art.gc.bytes-allocated")?.toLongOrNull() }
        } else null,
    )
    private val visibilityObservationRuntime = AndroidVisibilityGridRuntime(
        ownership = visibilityGridV2Binding::currentObservationOwnership,
        mapper = visibilityObservationMappingAdmission,
        captureSafe = captureSafetySignalV2,
        onMapperFailure = { error ->
            if (isDebuggable && mapperFailureProbeLogged.compareAndSet(false, true)) {
                Log.e("MAPPER-PROBE", "First visibility mapper failure", error)
            }
        },
    )
    private val visibilityObservationSource = ArCoreVisibilityObservationSource(
        runtime = visibilityObservationRuntime,
        ownership = visibilityGridV2Binding::currentObservationOwnership,
        depthMode = sceneHost::visibilityGridDepthMode,
        depthIntakeAllowed = {
            sceneHost.depthIntakeFrameHealthy() &&
                !captureSession.nativeCaptureWorkPendingV2()
        },
    )
    private val depthPauseGate = DepthObservationPauseGate(
        suspendDepth = visibilityObservationSource::suspendDepth,
        resumeDepth = visibilityObservationSource::resumeDepth,
    )
    private val visibilityObservationDebugChannel = VisibilityObservationDebugChannel(
        messenger = messenger,
        viewId = id,
        isDebuggable = isDebuggable,
        runtime = visibilityObservationRuntime,
        ownership = visibilityGridV2Binding::currentObservationOwnership,
        gate = visibilityObservationDebugGate,
        pressureOwners = ::visibilityPressureOwnerScalars,
        applySyntheticViewPose = { transform, quaternion, binding, group ->
            check(!disposed) { "synthetic view owner is closed" }
            captureSession.setDebugSyntheticViewPose(transform, quaternion, binding, group)
        },
        physicalSamplePools = visibilityObservationSource::packedLeaseReceipts,
    )

    init {
        visibilityGridV2Binding.configureRendererAttributeBudget { required ->
            visibilityObservationMappingAdmission.reserveRendererAttributeBytes(required) &&
                sceneHost.admitRendererAttributeBytes(required)
        }
        sceneHost.coverageRendererOwner.attachCommittedRowsBorrower(
            visibilityRendererProjection::withCommittedRows,
        )
        visibilityGridV2Binding.attachObservationRuntime(visibilityObservationRuntime)
    }

    private val lifecycleDrain = CaptureLifecycleDrain(
        background = Executor { command -> scope.launch(Dispatchers.IO) { command.run() } },
        main = Executor { command -> scope.launch { command.run() } },
        deadline = { timeoutMillis, expired ->
            val timer = scope.launch { delay(timeoutMillis); expired() }
            val cancel: () -> Unit = { timer.cancel() }
            cancel
        },
    )
    private val captureSession = ArCaptureSession(
        lifecycleDrain = lifecycleDrain,
        sceneHost = sceneHost,
        capabilityQuerier = CameraCapabilityQuerier(context),
        captureChannel = captureChannel,
        isOwnerClosing = { disposed },
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
        captureSafetySignalV2 = captureSafetySignalV2,
        retainAssessedOriginals = retainAssessedOriginals,
        playbackActive = playback != null,
    )

    private val visibilitySmallSceneDebugChannel = VisibilitySmallSceneDebugChannel(
        messenger = messenger,
        viewId = id,
        isDebuggable = isDebuggable,
        runtime = visibilityObservationRuntime,
        ownership = visibilityGridV2Binding::currentObservationOwnership,
        referencePose = ::visibilitySmallSceneReferencePose,
        productHooks = object : VisibilitySmallSceneProductHooks {
            override fun beginPoseFixture(): Boolean = captureSession.beginDebugPoseFixture()

            override fun manualViewPose() = captureSession.setDebugPoseManualView()

            override fun automaticRevisitPose() =
                captureSession.setDebugPoseAutomaticRevisit()

            override fun clearPoseFixture() = captureSession.clearDebugPoseFixture()

            override fun rendererUnavailable() {
                sceneHost.onLowMemoryPressure()
            }

            override fun rendererRecovered() {
                sceneHost.recoverCoverageRenderer()
            }

            override fun guidanceTerminal() {
                visibilityObservationRuntime.recordFeatureFailure()
                visibilityObservationRuntime.setDepthCapability(
                    com.uhg0.ar_flutter_plugin_2.visibilitygrid.VisibilityDepthCapability.UNSUPPORTED,
                )
            }

            override fun canonicalRetryableDepthCommit() {
                visibilityCanonicalFaultGate.armRetryableDepthCommit()
            }

            override fun rendererAllocationFailure() {
                sceneHost.armDebugCoverageAllocationFailure()
            }

            override fun pause() {
                captureSafetySignalV2.invalidateLifecycleForViewPause()
                sceneHost.pause()
            }

            override fun resume() {
                sceneHost.resume()
                captureSession.onSessionResumed()
            }

            override fun snapshot(): VisibilitySmallSceneReceiptScalars =
                visibilitySmallSceneReceiptScalars()
        },
    )

    private fun visibilityPressureOwnerScalars(): VisibilityPressureOwnerScalars {
        val canonical = visibilityObservationMappingAdmission.pressureSnapshot()
        val scene = sceneHost.visibilityPressureSnapshot()
        return VisibilityPressureOwnerScalars.fromNativeOwners(canonical, scene.renderer, scene.pages)
    }

    private fun visibilitySmallSceneReferencePose(): DoubleArray? {
        captureSession.debugPoseFixtureTransform()?.let { return it }
        val pose = sceneHost.latestFrame?.camera?.pose ?: return null
        val matrix = FloatArray(16)
        pose.toMatrix(matrix, 0)
        return DoubleArray(matrix.size) { matrix[it].toDouble() }
    }

    private fun visibilitySmallSceneReceiptScalars(): VisibilitySmallSceneReceiptScalars {
        val integration = visibilityObservationMappingAdmission.integrationReceipt()
        val baseline = visibilityGridV2Binding.currentCommittedBaseline()
        val renderer = sceneHost.coverageRendererOwner.status()
        val targetSurfaceId = sceneHost.coverageRendererOwner.snapshot()?.targetSurfaceId
        val observation = visibilityObservationRuntime.snapshot()
        val pressure = visibilityPressureOwnerScalars()
        return VisibilitySmallSceneReceiptScalars(
            geometryRevision = maxOf(integration.geometryRevision, baseline.geometryRevision),
            lineageRevision = maxOf(integration.lineageRevision, baseline.lineageRevision),
            durableCaptureRevision = maxOf(
                baseline.captureRevision,
                captureSession.durableCaptureRevisionV2(),
            ),
            nativeMaxObservedCaptureRevision = captureSession.durableCaptureRevisionV2(),
            visibilityBaselineCaptureRevision = baseline.captureRevision,
            coverageRevision = maxOf(baseline.coverageRevision, pressure.coverageRevision),
            styleRevision = maxOf(baseline.styleRevision, renderer.styleRevision),
            targetSurfaceId = targetSurfaceId,
            rendererRows = renderer.rowCount,
            automaticEligible = targetSurfaceId != null && observation.totalGridHealth != "failed",
            guidanceStatus = observation.totalGridHealth,
            rootIsolateSurfaceBytes = 0,
            resourceBalance = observation.resourceBalance,
            rootIsolateImageBytes = 0,
            rendererOwnedBytes = pressure.rendererOwnedBytes,
            integrationStatus = visibilityObservationMappingAdmission.lastDepthAdmissionStatus()
                ?: integration.status,
        )
    }

    private fun setCoverageRendererMounted(mounted: Boolean, generation: Long) {
        coverageRendererMounted = mounted
    }

    private val lifecycleObserver = object : DefaultLifecycleObserver {
        override fun onPause(owner: LifecycleOwner) {
            stopRecordingNow()
            resumeCoordinator.invalidate(ResumeTerminal.SUPERSEDED)
            visibilityObservationRuntime.fencePause()
            captureSafetySignalV2.invalidateLifecycleForViewPause()
            prepareCapturePause { result ->
                result.onFailure { error -> Log.e("ArView", "Native capture pause fence failed", error) }
            }
        }

        override fun onResume(owner: LifecycleOwner) {
            if (!disposed) resumeLifecycleBounded()
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
        // Visibility grid shell seam: inert until the grid flag path starts it.
        VisibilityGridShellRegistry.attachView(
            this,
            sceneHost::visibilityGridDepthMode,
            reapplyDepthMode = { root.post { if (!disposed) sceneHost.reapplyVisibilityGridDepthMode() } },
        ) { runCatching { captureSession.getCameraIntrinsics() }.getOrNull() }
        // Last, so a view whose construction failed is never registered.
        // The view leaves the open views once its ARCore session is closed
        // (off the main thread, after disposal); until then Settings must not
        // open a second session (camera and tier enumeration).
        OpenArViews.opened(this)
    }

    override fun getView(): View = root

    override fun dispose() = dispose(null)

    /**
     * Serializes the Dart dispose acknowledgement with any abandoned resume.
     * A late native resume can still succeed after its Dart timeout; callers
     * that need a disposal boundary must not proceed until that resume has
     * been paused and the host has released its resources.
     */
    private fun dispose(onCompleted: ((Result<Unit>) -> Unit)?) {
        if (onCompleted != null) {
            disposeTerminalResult?.let(onCompleted) ?: run { disposeCompletionCallbacks += onCompleted }
        }
        if (disposed) return
        stopRecordingNow()
        disposed = true
        VisibilityGridShellRegistry.detachView(this)
        canonicalReadyHints?.close()
        sceneHost.blockFutureResumes()
        resumeCoordinator.dispose(ResumeTerminal.CANCELLED, ::disposeAfterResumeDrained)
    }

    private fun disposeAfterResumeDrained() {
        poseBatchDispatcher.close()
        prepareForDispose { result ->
            if (result.isFailure) {
                Log.e("ArView", "Disposal drain failed; AR session and owners retained", result.exceptionOrNull())
                settleDispose(result)
                return@prepareForDispose
            }
            disposeAfterPauseFence()
        }
    }

    private fun disposeAfterPauseFence() {
        sessionChannel.setMethodCallHandler(null)
        objectChannel.setMethodCallHandler(null)
        anchorChannel.setMethodCallHandler(null)
        captureChannel.setMethodCallHandler(null)
        visibilitySmallSceneDebugChannel.dispose()
        visibilityObservationDebugChannel.dispose()
        val accepted = lifecycleDrain.run(
            timeoutMillis = 3_000,
            barrier = {
                visibilityObservationSource.close()
                visibilityObservationRuntime.close()
            },
            // A slow observation close (for example a loaded emulator) still
            // releases the session and capture root once it has closed, so
            // the next AR view can take them.
            afterLateSuccess = {
                Log.w("ArView", "Observation shutdown finished after its deadline; releasing session and owners")
                disposeAfterObservationClosed()
            },
        ) { drainResult ->
            if (drainResult.isFailure) {
                Log.e("ArView", "Observation shutdown failed; session and owners retained", drainResult.exceptionOrNull())
                settleDispose(drainResult)
                return@run
            }
            disposeAfterObservationClosed()
        }
        if (!accepted) settleDispose(Result.failure(IllegalStateException("Observation shutdown already owns its lifecycle drain")))
    }

    private fun disposeAfterObservationClosed() {
        visibilityStorageBudgetCoordinator.close()
        visibilityGridV2Binding.dispose()
        lifecycle.removeObserver(lifecycleObserver)
        captureSession.dispose { result ->
            if (result.isFailure) {
                Log.e("ArView", "Native capture close failed; session retained", result.exceptionOrNull())
                settleDispose(result)
            } else disposeAfterCaptureClosed()
        }
    }

    private fun disposeAfterCaptureClosed() {
        pendingCloudOperations.toList().forEach { it() }
        pendingCloudOperations.clear()
        sceneHost.dispose(onSessionClosed = {
            Log.i("ArView", "AR view left the open views after its ARCore session closed")
            OpenArViews.closed(this)
        })
        disposeCompleted = true
        settleDispose(Result.success(Unit))
        scope.cancel()
    }

    private fun settleDispose(result: Result<Unit>) {
        if (disposeTerminalResult != null) return
        disposeTerminalResult = result
        disposeCompletionCallbacks.toList().forEach { it(result) }
        disposeCompletionCallbacks.clear()
    }

    private fun prepareForDispose(onCompleted: (Result<Unit>) -> Unit) {
        if (!shutdownPreparation.request(onCompleted)) return
        // ARCore's SharedCamera sample pauses the Session before closing
        // Camera2. Its wrapped image/session callbacks retain native Session
        // state until Camera2 shutdown completes.
        // Fence/drain V2 before ARCore pause, then preserve the required
        // ARCore-pause-before-Camera2 shutdown order for SharedCamera.
        prepareCapturePause { result ->
            shutdownPreparation.complete(result)
        }
    }

    private fun prepareCapturePause(onCompleted: (Result<Unit>) -> Unit) {
        depthPauseGate.begin()
        val pauseToken = visibilityObservationRuntime.fencePause()
        capturePauseOperations += 1
        val settled = AtomicBoolean(false)
        fun settle(preparation: Result<Unit>) {
            if (!settled.compareAndSet(false, true)) return
            val qualified = if (visibilityObservationRuntime.isCurrentLifecycle(pauseToken)) preparation else
                Result.failure(IllegalStateException("Pause lifecycle was superseded"))
            val terminal = CaptureLifecycleDrain.completePause(qualified) {
                sceneHost.pause()
                captureSafetySignalV2.invalidateAll()
                captureSession.finishSharedCameraPause()
                Unit
            }
            depthPauseGate.complete(terminal.isSuccess)
            try {
                onCompleted(terminal)
            } finally {
                capturePauseOperations -= 1
                if (capturePauseOperations == 0) {
                    afterCapturePauseCallbacks.toList().forEach { it() }
                    afterCapturePauseCallbacks.clear()
                }
            }
        }
        fun finish(preparation: Result<Unit>) {
            if (preparation.isFailure) settle(preparation) else {
                resumeCoordinator.afterNativeDrain { drained ->
                    settle(if (drained) preparation else
                        Result.failure(IllegalStateException("Prepared pause was superseded")))
                }
            }
        }
        try {
            if (!captureSession.prepareNativeCaptureForPause(
                    backgroundDrain = {
                        visibilityObservationRuntime.finishPause(pauseToken)
                        check(visibilityObservationSource.awaitDepthIdle(1_000)) { "Depth image did not drain before pause" }
                    },
                    onCompleted = ::finish,
                )) {
                finish(Result.failure(IllegalStateException("Native capture durable owner is closing")))
            }
        } catch (error: Exception) {
            finish(Result.failure(error))
        }
    }

    private fun runAfterCapturePause(operation: () -> Unit) {
        if (capturePauseOperations == 0) operation() else afterCapturePauseCallbacks += operation
    }

    private fun onSessionCall(call: MethodCall, result: MethodChannel.Result) {
        try {
            if (visibilityGridV2Binding.handleCanonicalReadyCall(call, result)) return
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
                "getCameraCadenceSnapshot" ->
                    result.success(sceneHost.cameraCadenceSnapshot())
                "getRendererPerformanceSnapshot" ->
                    result.success(
                        sceneHost.rendererPerformanceSnapshot(
                            beginMeasurementWindow =
                                isDebuggable && call.argument<Boolean>("beginWindow") == true,
                            captureDiagnosticTiming = isDebuggable && call.argument<Boolean>("captureDiagnosticTiming") == true,
                            freezeDiagnosticTiming = isDebuggable && call.argument<Boolean>("freezeDiagnosticTiming") == true,
                            beginShapeMeasurementWindow = call.argument<Boolean>("beginWindow") == true,
                            endShapeMeasurementWindow = call.argument<Boolean>("endWindow") == true,
                            includeDiagnosticTiming = isDebuggable && call.argument<Boolean>("includeDiagnosticTiming") == true,
                        ),
                    )
                "snapshot" -> sceneHost.snapshot { snapshot ->
                    snapshot.fold(result::success) {
                        result.error("SNAPSHOT_ERROR", it.message, null)
                    }
                }
                // Keep the legacy explicit camera controls and the
                // ARSessionManager lifecycle names on one native seam. The
                // Dart manager uses pauseSession/resumeSession, while older
                // callers use disableCamera/enableCamera.
                "disableCamera", "pauseSession" -> {
                    stopRecordingNow()
                    sessionPausedByFlutter = true
                    resumeCoordinator.invalidate(ResumeTerminal.SUPERSEDED)
                    visibilityObservationRuntime.fencePause()
                    prepareCapturePause { pauseResult ->
                        pauseResult.fold(
                            onSuccess = { result.success(null) },
                            onFailure = { result.error("SESSION_PAUSE_FAILED", it.message, null) },
                        )
                    }
                }
                "enableCamera", "resumeSession" -> {
                    resumeSessionBounded(result)
                }
                "dispose" -> {
                    dispose { terminal -> terminal.fold(
                        onSuccess = { result.success(null) },
                        onFailure = { result.error("SESSION_DISPOSE_FAILED", it.message, null) },
                    ) }
                }
                "startRecording" -> startRecording(call, result)
                "stopRecording" -> stopRecording(result)
                "recordTrackData" -> {
                    val id = call.argument<String>("trackId")
                        ?.let { runCatching { java.util.UUID.fromString(it) }.getOrNull() }
                    val data = call.argument<ByteArray>("data")
                    result.success(id != null && data != null && recorder.enqueue(id, data))
                }
                "recordingStatus" -> result.success(
                    recorder.status(sceneHost.activeSession?.let(::ArCoreRecordingPort)),
                )
                else -> result.notImplemented()
            }
        } catch (error: Exception) {
            result.error("SESSION_ERROR", error.message, null)
        }
    }

    private fun startRecording(call: MethodCall, result: MethodChannel.Result) {
        if (playback != null) {
            result.error("RECORDING_REFUSED", "playback-active", null)
            return
        }
        val session = sceneHost.activeSession
        if (session == null || disposed) {
            result.error("RECORDING_REFUSED", "session-unavailable", null)
            return
        }
        when (val parse = ArRecordingRequests.parse(
            call.arguments as? Map<*, *>,
            isDebuggable = isDebuggable,
            externalFilesDir = root.context.getExternalFilesDir(null),
        )) {
            is ArRecordingRequestParse.Refused -> result.error("RECORDING_REFUSED", parse.reason, null)
            is ArRecordingRequestParse.Accepted -> recorder.start(ArCoreRecordingPort(session), parse.request).fold(
                onSuccess = result::success,
                onFailure = { result.error("RECORDING_FAILED", "${it.javaClass.simpleName}: ${it.message}", null) },
            )
        }
    }

    /** Stops after queued track data reached a frame, or after the stop timeout. */
    private fun stopRecording(result: MethodChannel.Result) {
        val session = sceneHost.activeSession
        if (session == null) {
            result.success(recorder.status(null))
            return
        }
        val port = ArCoreRecordingPort(session)
        var answered = false
        val answer: (Map<String, Any?>) -> Unit = { status ->
            root.post {
                if (!answered) {
                    answered = true
                    result.success(status)
                }
            }
        }
        recorder.requestStop(port, answer)
        root.postDelayed({ recorder.stopNow(port) }, ArSessionRecorder.STOP_TIMEOUT_MS)
    }

    private fun stopRecordingNow() {
        if (!recorder.isRecording) return
        sceneHost.activeSession?.let { recorder.stopNow(ArCoreRecordingPort(it)) }
    }

    private fun resumeSessionBounded(result: MethodChannel.Result) {
        runAfterCapturePause {
            resumeBounded(clearFlutterPause = true) { terminal ->
                when (terminal) {
                    ResumeTerminal.SUCCESS -> result.success(null)
                    ResumeTerminal.SUPERSEDED -> result.error(
                        "SESSION_RESUME_SUPERSEDED",
                        "A newer resume request replaced this request",
                        null,
                    )
                    ResumeTerminal.CANCELLED -> result.error(
                        "SESSION_RESUME_CANCELLED",
                        "AR view was disposed before Session.resume completed",
                        null,
                    )
                    ResumeTerminal.TIMEOUT -> result.error(
                        "SESSION_RESUME_TIMEOUT",
                        "Session.resume exceeded the 5000ms native bound",
                        null,
                    )
                    ResumeTerminal.FAILED -> result.error(
                        "SESSION_RESUME_FAILED",
                        "Session.resume failed",
                        null,
                    )
                }
            }
        }
    }

    private fun resumeLifecycleBounded() {
        runAfterCapturePause {
            resumeBounded(clearFlutterPause = false) {
                // Lifecycle callbacks have no Dart reply to settle.
            }
        }
    }

    private fun resumeBounded(
        clearFlutterPause: Boolean,
        onTerminal: (ResumeTerminal) -> Unit,
    ) {
        var resumeToken = visibilityObservationRuntime.lifecycleToken()
        fun fenceResumeAdmission(): Long {
            val rollbackToken = if (visibilityObservationRuntime.isCurrentLifecycle(resumeToken))
                visibilityObservationRuntime.fencePause() else visibilityObservationRuntime.lifecycleToken()
            captureSafetySignalV2.invalidateAll()
            return rollbackToken
        }
        fun rollbackCompletedResume() {
            // Only the coordinator's native-completion path may physically
            // pause: a deadline reply can precede the return of Session.resume.
            val rollbackToken = fenceResumeAdmission()
            runCatching { sceneHost.pause() }
                .onFailure { Log.e("ArView", "Camera resume rollback retained its owner", it) }
                .getOrThrow()
            scope.launch(Dispatchers.IO) {
                runCatching { visibilityObservationRuntime.finishPause(rollbackToken) }
                    .onFailure { Log.e("ArView", "Observation resume rollback retained its fence", it) }
            }
        }
        resumeCoordinator.begin(
            timeoutMillis = 5_000,
            next = { terminal ->
                val qualifiedTerminal = if (terminal == ResumeTerminal.SUCCESS &&
                    !visibilityObservationRuntime.isCurrentLifecycle(resumeToken)) ResumeTerminal.SUPERSEDED else terminal
                when (qualifiedTerminal) {
                    ResumeTerminal.SUCCESS -> {
                        if (!disposed && visibilityObservationRuntime.isCurrentLifecycle(resumeToken)) {
                            sceneHost.recoverCoverageRenderer()
                            if (clearFlutterPause) sessionPausedByFlutter = false
                            if (!sessionPausedByFlutter) {
                                depthPauseGate.resumed()
                                captureSession.onSessionResumed()
                                // Grid ingest: resume and tracking reset session events, depth re-application.
                                VisibilityGridShellRegistry.onArSessionResumed()
                            }
                        }
                    }
                    else -> fenceResumeAdmission()
                }
                onTerminal(qualifiedTerminal)
            },
            superseded = ResumeTerminal.SUPERSEDED,
            timedOut = ResumeTerminal.TIMEOUT,
            failed = ResumeTerminal.FAILED,
            succeeded = ResumeTerminal.SUCCESS,
            operation = {
                CaptureLifecycleDrain.resumeCamera(
                    observationResume = { visibilityObservationRuntime.resume(resumeToken) },
                    current = { visibilityObservationRuntime.isCurrentLifecycle(resumeToken) },
                    resumeCamera = sceneHost::resumeSession,
                )
            },
            rollbackLateSuccess = ::rollbackCompletedResume,
            rollbackLateFailure = ::rollbackCompletedResume,
            acceptSuccess = { !disposed && visibilityObservationRuntime.isCurrentLifecycle(resumeToken) },
        )
        // begin may synchronously supersede an older Main reply before queuing
        // this successor. Capture that completed fence, not a later pause intent.
        resumeToken = visibilityObservationRuntime.lifecycleToken()
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
                    val requested = call.arguments as? Map<String, Any?>
                        ?: throw IllegalArgumentException("Capture configuration is required")
                    // Playback has no Shared Camera: capture starts without
                    // Camera2 and shutter photos use the CPU camera image.
                    val config = if (playback != null) requested + ("enableHighResCapture" to false) else requested
                    scope.launch {
                        try {
                            captureSession.initialize(config)
                            result.success(mapOf(
                                "mode" to if (config["enableHighResCapture"] == true) "sharedCamera" else "previewFallback",
                            ))
                        } catch (error: CaptureSessionException) {
                            result.error(error.code, error.message, null)
                        } catch (error: IllegalArgumentException) {
                            result.error("CONFIG_INVALID", error.message, null)
                        } catch (error: Exception) {
                            result.error("CAPTURE_FAILED", error.message, null)
                        }
                    }
                }
                "captureHighResImage" -> scope.launch {
                    try {
                        val args = call.arguments as? Map<String, Any?>
                        val policy = args?.get("qualityPolicy") as? Map<String, Any?>
                        val requiresPose = args?.get("requiresPose") as? Boolean ?: true
                        result.success(
                            withContext(Dispatchers.Default) {
                                captureSession.captureImage(
                                    qualityPolicyMap = policy,
                                    requiresPose = requiresPose,
                                    exposureBracketEnabled =
                                        call.argument<Boolean>("exposureBracketEnabled") ?: false,
                                )
                            },
                        )
                    } catch (error: CaptureSessionException) {
                        result.error(error.code, error.message, null)
                    } catch (error: IllegalArgumentException) {
                        result.error("CONFIG_INVALID", error.message, null)
                    } catch (error: Exception) {
                        result.error("CAPTURE_FAILED", error.message, null)
                    }
                }
                "getCaptureCapacity" -> result.success(captureSession.getCaptureCapacity())
                "admitNativeCaptureV2" -> {
                    val accepted = captureSession.admitNativeCaptureV2(call.arguments) { admissionResult ->
                        admissionResult.fold(
                            onSuccess = result::success,
                            onFailure = { error ->
                                val code = when (error) {
                                    is NativeCaptureRecoveryAdmissionExceptionV2 -> error.code
                                    is IllegalArgumentException -> "NATIVE_CAPTURE_V2_INVALID"
                                    else -> "NATIVE_CAPTURE_V2_FAILED"
                                }
                                Log.w("ArView", "Native capture admission refused: code=$code")
                                result.error(code, error.message, null)
                            },
                        )
                    }
                    if (!accepted) {
                        Log.w("ArView", "Native capture admission refused: code=NATIVE_CAPTURE_V2_CLOSED")
                        result.error("NATIVE_CAPTURE_V2_CLOSED", "Native capture durable owner is closing", null)
                    }
                }
                "getNativeCaptureHealthV2" -> result.success(captureSession.nativeCaptureHealthV2())
                "materializeNativeCapturePreview" -> scope.launch {
                    try {
                        val manifestId = call.argument<String>("manifestId")
                            ?: throw IllegalArgumentException("manifestId is required")
                        val captureId = call.argument<String>("captureId")
                            ?: throw IllegalArgumentException("captureId is required")
                        result.success(withContext(Dispatchers.IO) {
                            captureSession.materializeNativeCapturePreview(manifestId, captureId)
                        })
                    } catch (error: CaptureSessionException) {
                        result.error(error.code, error.message, null)
                    } catch (error: IllegalArgumentException) {
                        result.error("CONFIG_INVALID", error.message, null)
                    } catch (error: Exception) {
                        result.error("CAPTURE_FAILED", error.message, null)
                    }
                }
                "replayNativeCaptureRecoveryV2" -> result.success(captureSession.replayNativeCaptureRecoveryV2())
                "acknowledgeNativeCaptureTerminalV2" -> {
                    captureSession.acknowledgeNativeCaptureTerminalV2(
                        call.argument<String>("attemptId") ?: throw IllegalArgumentException("attemptId is required"),
                    )
                    result.success(true)
                }
                "notifyNativeCaptureLifecycleV2" -> {
                    val accepted = captureSession.notifyNativeCaptureLifecycleV2(
                        call.argument<String>("event") ?: throw IllegalArgumentException("event is required"),
                    ) { lifecycleResult ->
                        lifecycleResult.fold(
                            onSuccess = { result.success(true) },
                            onFailure = { result.error("NATIVE_CAPTURE_V2_FAILED", it.message, null) },
                        )
                    }
                    if (!accepted) result.error("NATIVE_CAPTURE_V2_CLOSED", "Native capture durable owner is closing", null)
                }
                "debugNativeCaptureV2Synthetic" -> {
                    val debuggable = root.context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
                    if (!debuggable) {
                        result.error("DEBUG_ONLY", "Synthetic V2 capture is unavailable in release builds", null)
                    } else {
                        result.success(
                            captureSession.installDebugNativeCaptureSyntheticV2(
                                call.argument<String>("fault"),
                            ),
                        )
                    }
                }
                "debugNativeCaptureV2AdvanceRecovery" -> {
                    val debuggable = root.context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
                    if (!debuggable) result.error("DEBUG_ONLY", "Synthetic V2 recovery is unavailable in release builds", null)
                    else { captureSession.advanceNativeCaptureRecoveryV2(); result.success(true) }
                }
                "debugNativeCaptureV2CompleteDeferred" -> {
                    val debuggable = root.context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
                    if (!debuggable) result.error("DEBUG_ONLY", "Synthetic V2 completion is unavailable in release builds", null)
                    else result.success(captureSession.completeDebugNativeCaptureV2())
                }
                "debugSyntheticPoseFixture" -> {
                    val debuggable = root.context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
                    if (!debuggable) {
                        result.error("DEBUG_ONLY", "Synthetic pose fixtures are unavailable in release builds", null)
                    } else {
                        val bindingGeneration = call.argument<Number>("bindingGeneration")?.toLong()
                            ?: throw IllegalArgumentException("bindingGeneration is required")
                        val groupGeneration = call.argument<Number>("groupGeneration")?.toLong()
                            ?: throw IllegalArgumentException("groupGeneration is required")
                        val ownership = visibilityGridV2Binding.currentObservationOwnership()
                            ?: throw IllegalStateException("visibility observation ownership is not ready")
                        check(ownership.bindingGeneration == bindingGeneration) {
                            "binding generation does not match current observation ownership"
                        }
                        check(ownership.groupGeneration == groupGeneration) {
                            "group generation does not match current observation ownership"
                        }
                        result.success(
                            captureSession.beginDebugSyntheticPoseFixture(
                                bindingGeneration = bindingGeneration,
                                groupGeneration = groupGeneration,
                            ),
                        )
                    }
                }
                "debugClearSyntheticPoseFixture" -> {
                    val debuggable = root.context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
                    if (!debuggable) result.error("DEBUG_ONLY", "Synthetic pose fixtures are unavailable in release builds", null)
                    else {
                        captureSession.clearDebugPoseFixture()
                        result.success(true)
                    }
                }
                "getPerformanceSnapshot" -> result.success(captureSession.getPerformanceSnapshot(
                    resetDiagnosticTiming = isDebuggable && call.argument<Boolean>("resetDiagnosticTiming") == true,
                    includeDiagnosticTiming = isDebuggable && call.argument<Boolean>("includeDiagnosticTiming") == true,
                ))
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
                    prepareForDispose { pauseResult ->
                        if (pauseResult.isFailure) {
                            result.error("CAPTURE_DISPOSE_FAILED", pauseResult.exceptionOrNull()?.message, null)
                            return@prepareForDispose
                        }
                        captureSession.dispose { closeResult ->
                            closeResult.fold(
                                onSuccess = { result.success(null) },
                                onFailure = { result.error("CAPTURE_DISPOSE_FAILED", it.message, null) },
                            )
                        }
                    }
                }
                else -> result.notImplemented()
            }
        } catch (error: NativeCaptureRecoveryAdmissionExceptionV2) {
            if (call.method == "admitNativeCaptureV2") {
                Log.w("ArView", "Native capture admission refused: code=${error.code}")
            }
            result.error(error.code, error.message, null)
        } catch (error: CaptureSessionException) {
            result.error(error.code, error.message, null)
        } catch (error: Exception) {
            result.error("CAPTURE_FAILED", error.message, null)
        }
    }

    private fun onFrame(session: Session, frame: Frame) {
        val attribution = sceneHost.nativeFrameAttribution
        attribution.measureNativeFrame(NativeFrameSpan.OBSERVATION) {
            visibilityObservationSource.onFrame(frame)
        }
        attribution.measureNativeFrame(NativeFrameSpan.POSE) {
            captureSession.buildPoseUpdate(frame)?.let(poseBatchDispatcher::offer)
        }
        if (playback != null) captureSession.onPlaybackFrame(frame)
        if (recorder.isRecording) recorder.onFrame(ArCoreRecordingPort(session)) { id, data -> frame.recordTrackBytes(id, data) }
        playbackTracks?.onFrame { id -> frame.readTrackRecords(id) }
        // Visibility grid frame tap seam: returns at once unless the grid shell was started.
        VisibilityGridShellRegistry.onArFrame(session, frame)
        attribution.measureNativeFrame(NativeFrameSpan.AR_PLANES) {
            frame.getUpdatedTrackables(Plane::class.java).forEach { plane ->
                if (detectedPlanes.add(plane)) {
                    sessionChannel.invokeMethod("onPlaneDetected", detectedPlanes.size)
                }
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
        /**
         * Two camera poses per pose-channel send halve the number of sends. At
         * 30 Hz the first pose of a pair normally reaches Dart one camera frame
         * later; below about 20 Hz each pose waits the full hold and goes alone.
         */
        const val POSE_SAMPLES_PER_SEND = 2

        /** A held pose is sent at most this long after it is held (1.5 camera frames at 30 Hz). */
        const val POSE_MAX_HOLD_MS = 50L

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
