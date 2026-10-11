package com.uhg0.ar_flutter_plugin_2.visibilityingest

import com.uhg0.ar_flutter_plugin_2.sessionlog.NativeSessionLogEmitter
import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.util.Log
import com.google.ar.core.Config
import com.google.ar.core.Frame
import com.uhg0.ar_flutter_plugin_2.BuildConfig
import com.uhg0.ar_flutter_plugin_2.gridrenderer.GridRendererChannels
import com.uhg0.ar_flutter_plugin_2.markerdetection.GridMarkerShellTaps
import com.uhg0.ar_flutter_plugin_2.posetracking.AnchorJumpListener
import com.uhg0.ar_flutter_plugin_2.posetracking.AnchorPoseUpdateProducer
import com.uhg0.ar_flutter_plugin_2.visibilitycontract.VisibilityGridChannels
import com.uhg0.ar_flutter_plugin_2.visibilitycontract.VisibilityGridFrameTap
import io.flutter.plugin.common.BasicMessageChannel
import io.flutter.plugin.common.BinaryCodec
import io.flutter.plugin.common.BinaryMessenger
import java.io.File
import java.nio.ByteBuffer

/**
 * Process-wide registration point of the Android visibility grid shell
 * (contract 2.12 seams). The shared plugin files call only this object:
 *
 * - `ArFlutterPlugin` registers and unregisters the grid channels through
 *   [register] / [unregister] (ingest on a background task queue, control;
 *   render and display belong to the grid renderer, see the marked spot).
 * - `ArView` attaches its depth mode and still camera model ([attachView])
 *   and calls [onArFrame] once per AR frame. Every registered
 *   [VisibilityGridFrameTap] runs per frame: the ingest shell first, then taps
 *   added by other features (for example marker detection) via [addFrameTap].
 * - `NativeCaptureAdapterV2` reports each durable terminal to
 *   [durableTerminalTap]; the shell emits the photo saved event through the
 *   frozen `PhotoSavedListener` seam and then calls every
 *   [DurablePhotoListener] added via [addDurablePhotoListener].
 * - Other producers offer complete ingest messages (for example kind 5
 *   marker detections) into the same pull queue through [ingestSink].
 *
 * Everything is inert until the UI isolate sends a control start message,
 * which only the grid flag path does; the legacy path never reaches it.
 */
internal object VisibilityGridShellRegistry {
    private val lock = Any()
    @Volatile private var shell: VisibilityGridIngestShell? = null
    private var ingestChannel: BasicMessageChannel<ByteBuffer>? = null
    private var controlChannel: BasicMessageChannel<ByteBuffer>? = null
    private var idleTimer: Thread? = null
    private var messenger: BinaryMessenger? = null

    @Volatile private var taps: Array<VisibilityGridFrameTap> = emptyArray()
    @Volatile private var photoListeners: Array<DurablePhotoListener> = emptyArray()
    private var viewOwner: Any? = null
    @Volatile private var markers: GridMarkerShellTaps? = null

    // Pose stability: anchor pose tracker (query channel, durable photo listener, frame reads).
    @Volatile private var poseTracking: AnchorPoseUpdateProducer? = null

    private val elapsedRealtime = IngestClock { SystemClock.elapsedRealtimeNanos() }

    /** Registers the ingest and control channels of one engine; the latest engine owns the shell. */
    fun register(messenger: BinaryMessenger, context: Context) {
        synchronized(lock) {
            unregisterLocked()
            this.messenger = messenger
            val files = context.applicationContext.filesDir
            val recorder = if (BuildConfig.DEBUG) {
                IngestRecorder(File(files, "visibility_grid/recordings"), Build.MODEL)
            } else {
                null
            }
            // Depth telemetry (1 Hz while started) in debug and profile builds only.
            val telemetry: ((String) -> Unit)? = if (BuildConfig.DEBUG || BuildConfig.BUILD_TYPE == "profile") {
                { line -> Log.i(TAG, line) }
            } else {
                null
            }
            val created = VisibilityGridIngestShell(
                elapsedRealtime, recorder, DurableJpegLocator(File(files, "capture-v2-native/store")), telemetry,
            )
            created.durablePhotoListeners = photoListeners
            shell = created
            val queue = messenger.makeBackgroundTaskQueue()
            ingestChannel = BasicMessageChannel(messenger, VisibilityGridChannels.INGEST, BinaryCodec.INSTANCE_DIRECT, queue)
                .apply { setMessageHandler { message, reply -> created.pull.onPull(message, reply) } }
            controlChannel = BasicMessageChannel(messenger, VisibilityGridChannels.CONTROL, BinaryCodec.INSTANCE_DIRECT)
                .apply {
                    setMessageHandler(GridShellControlHandler(shell = { shell }, markerDetectorAvailable = { markers != null }))
                }
            // Render and display channels of the instanced grid renderer; inert until the
            // grid isolate or the UI isolate sends a message.
            GridRendererChannels.register(messenger)
            // Pose stability: anchor pose tracking channel, photo anchors and per-frame anchor reads.
            // The photo listener is added only while tracking is armed (legacy path stays idle).
            poseTracking = AnchorPoseUpdateProducer(
                elapsedRealtime,
                photoListenerSwitch = { listener, add ->
                    if (add) addDurablePhotoListener(listener) else removeDurablePhotoListener(listener)
                },
            ).also {
                it.bind(messenger)
                // Anchor jumps (ARCore map corrections) reach the grid as tracking reset session events.
                it.tracker.jumpListener = AnchorJumpListener { frameNs, mm -> shell?.onAnchorJump(frameNs, mm) }
            }
            markers = GridMarkerShellTaps.create(
                ingestSink = { ingestSink },
                shell = { shell },
                clock = elapsedRealtime,
            )?.also { taps ->
                if (taps.frameTap !in extraTaps) extraTaps += taps.frameTap
                photoListeners = photoListeners + taps.photoListener
                created.durablePhotoListeners = photoListeners
            }
            idleTimer = Thread({
                val pull = created.pull
                while (pull.awaitIdleDeadline()) pull.tick()
            }, "capture3d-grid-ingest-idle").apply {
                isDaemon = true
                start()
            }
            rebuildTapsLocked()
            applyViewLocked()
        }
    }

    /** Unregisters the channels of [messenger]'s engine; another engine's registration is kept. */
    fun unregister(messenger: BinaryMessenger) {
        synchronized(lock) { if (this.messenger === messenger) unregisterLocked() }
    }

    private fun unregisterLocked() {
        ingestChannel?.setMessageHandler(null)
        controlChannel?.setMessageHandler(null)
        messenger?.let(GridRendererChannels::unregister)
        // Pose stability: anchor pose tracking.
        poseTracking?.close()
        poseTracking = null
        markers?.let { taps ->
            extraTaps -= taps.frameTap
            photoListeners = photoListeners.filter { it !== taps.photoListener }.toTypedArray()
            taps.close()
        }
        markers = null
        ingestChannel = null
        controlChannel = null
        shell?.close()
        shell = null
        idleTimer = null
        messenger = null
        rebuildTapsLocked()
    }

    private val extraTaps = ArrayList<VisibilityGridFrameTap>()

    /** Adds a per-frame tap after the ingest shell (for example marker detection). */
    fun addFrameTap(tap: VisibilityGridFrameTap) {
        synchronized(lock) {
            if (tap !in extraTaps) extraTaps += tap
            rebuildTapsLocked()
        }
    }

    fun removeFrameTap(tap: VisibilityGridFrameTap) {
        synchronized(lock) {
            extraTaps -= tap
            rebuildTapsLocked()
        }
    }

    private fun rebuildTapsLocked() {
        val s = shell
        taps = (if (s == null) extraTaps.toList() else listOf<VisibilityGridFrameTap>(s) + extraTaps).toTypedArray()
    }

    fun addDurablePhotoListener(listener: DurablePhotoListener) {
        synchronized(lock) {
            if (listener !in photoListeners) photoListeners = photoListeners + listener
            shell?.durablePhotoListeners = photoListeners
        }
    }

    fun removeDurablePhotoListener(listener: DurablePhotoListener) {
        synchronized(lock) {
            photoListeners = photoListeners.filter { it !== listener }.toTypedArray()
            shell?.durablePhotoListeners = photoListeners
        }
    }

    /** The composite per-frame tap; allocation-free, contains tap failures like [onArFrame]. */
    val frameTap = VisibilityGridFrameTap { frame ->
        val t = taps
        for (i in t.indices) {
            try {
                t[i].onFrame(frame)
            } catch (e: RuntimeException) {
                tapFailed(e)
            }
        }
    }

    /**
     * Frame taps (shell, extra taps, anchor pose tracking) that threw; each
     * failure is contained, so one tap never stops the others or the
     * SceneView frame callback this runs in. Frame thread writes.
     */
    @Volatile var frameTapFailures = 0L
        private set

    /** Seam called by `ArView` once per AR frame; [session] identifies the AR session. */
    fun onArFrame(session: Any, frame: Frame) {
        val t = taps
        val s = shell
        for (i in t.indices) {
            val tap = t[i]
            try {
                if (tap === s) s.onFrame(session, frame) else tap.onFrame(frame)
            } catch (e: RuntimeException) {
                tapFailed(e)
            }
        }
        // Pose stability: returns after one volatile read while anchor tracking is inactive.
        try {
            poseTracking?.onArFrame(session, frame)
        } catch (e: RuntimeException) {
            tapFailed(e)
        }
    }

    private fun tapFailed(e: RuntimeException) {
        val failures = ++frameTapFailures
        // Logs the 1st, 2nd, 4th, 8th, ... failure so a persistent fault cannot flood the log.
        if (failures and (failures - 1) == 0L) Log.w(TAG, "grid frame tap failed ($failures)", e)
        NativeSessionLogEmitter.failure(
            "NATIVE_HANDLER_ERROR", "gridFrameTap/${e.javaClass.simpleName}", "Grid frame tap failed",
        ) { mapOf("channel" to "frame", "method" to "gridFrameTap", "frameTapFailures" to failures, "error" to (e.message ?: "")) }
    }

    private const val TAG = "VisibilityGridShell"

    /** Seam called by the native capture adapter for each durable terminal. */
    val durableTerminalTap = DurableTerminalTap { request, terminal, exposureNs ->
        shell?.onDurableTerminal(request, terminal, exposureNs)
    }

    /** Queue for producers of other ingest kinds (for example marker detections). */
    val ingestSink: IngestMessageSink? get() = shell?.pull

    /** The running shell, for marker detection settings and diagnostics. */
    val activeShell: VisibilityGridIngestShell? get() = shell

    /** Seam called by `ArView` after its AR session resumed (any thread); inert unless the shell was started. */
    fun onArSessionResumed() {
        shell?.onArSessionResumed()
    }

    /**
     * Binds the AR view's depth mode, its depth mode re-application (posted to
     * the view's thread by the view) and still camera intrinsics; the last
     * attached view wins.
     */
    fun attachView(
        owner: Any,
        depthMode: () -> Config.DepthMode,
        reapplyDepthMode: () -> Unit = {},
        stillCameraIntrinsics: () -> Map<String, Any>?,
    ) {
        synchronized(lock) {
            viewOwner = owner
            viewDepthMode = depthMode
            viewReapplyDepth = reapplyDepthMode
            viewStillCamera = StillCameraModelSource { out -> StillCameraModel.fromMap(stillCameraIntrinsics(), out) }
            applyViewLocked()
        }
    }

    fun detachView(owner: Any) {
        synchronized(lock) {
            if (viewOwner !== owner) return
            viewOwner = null
            viewDepthMode = DISABLED_DEPTH
            viewReapplyDepth = NO_REAPPLY
            viewStillCamera = StillCameraModelSource.NONE
            applyViewLocked()
        }
    }

    private val DISABLED_DEPTH: () -> Config.DepthMode = { Config.DepthMode.DISABLED }
    private var viewDepthMode: () -> Config.DepthMode = DISABLED_DEPTH
    private val NO_REAPPLY: () -> Unit = {}
    private var viewReapplyDepth: () -> Unit = NO_REAPPLY
    private var viewStillCamera: StillCameraModelSource = StillCameraModelSource.NONE

    private fun applyViewLocked() {
        shell?.let {
            it.depthMode = viewDepthMode
            it.depthReapply = viewReapplyDepth
            it.stillCamera = viewStillCamera
        }
    }
}
