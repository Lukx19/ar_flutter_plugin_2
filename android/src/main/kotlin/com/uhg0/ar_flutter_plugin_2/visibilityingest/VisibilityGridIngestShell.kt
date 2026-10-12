package com.uhg0.ar_flutter_plugin_2.visibilityingest

import com.google.ar.core.Config
import com.google.ar.core.Frame
import com.uhg0.ar_flutter_plugin_2.capture.CaptureCommitRequest
import com.uhg0.ar_flutter_plugin_2.capture.CaptureTerminal
import com.uhg0.ar_flutter_plugin_2.visibilitycontract.IngestMessageLayout
import com.uhg0.ar_flutter_plugin_2.visibilitycontract.ShellControlLayout
import com.uhg0.ar_flutter_plugin_2.visibilitycontract.VisibilityGridFrameTap
import com.uhg0.ar_flutter_plugin_2.sessionlog.NativeSessionLogEmitter

/** Configuration of a started shell, from the control start message. */
internal class IngestShellConfig(
    val flags: Int,
    val depthSourcePreference: Int,
    val runMode: Int,
    val markerDetectionHz: Int,
    val markerSizeMetres: Float,
    val groupEpoch: Int,
) {
    companion object {
        const val DEPTH_AUTOMATIC = 0
        const val DEPTH_PREFERRED = 1
        const val DEPTH_POINTS_ONLY = 2
    }
}

/**
 * The Android visibility grid ingest shell: one per Flutter engine. Idle
 * (every frame call returns after one volatile read) until the UI isolate
 * sends a control start message, which only happens when the grid flag is
 * on, so the legacy capture path is unchanged by default.
 *
 * Threads: [onFrame] runs on the AR frame thread and owns the producers,
 * the pose history and the session state; control calls arrive on the
 * platform thread and are applied at the next frame; pulls run on the ingest
 * channel's background task queue; durable terminals arrive on the capture
 * worker.
 */
internal class VisibilityGridIngestShell(
    private val clock: IngestClock,
    val recorder: IngestRecorder?,
    jpegLocator: DurableJpegLocator = DurableJpegLocator(null),
    /**
     * Receives one depth telemetry line per second of started frames (debug
     * and profile builds; null in release and tests): published, duplicate,
     * stale-dropped, identity refusals, STALE_AUTOMATIC, not yet available,
     * failures and the age of the newest depth image, so a device run shows
     * why depth stops.
     */
    private val depthTelemetry: ((String) -> Unit)? = null,
) : VisibilityGridFrameTap, DurableTerminalTap {
    val pull = IngestPullHandler(clock, recorder)
    private val session = IngestSessionState()
    private val depth = DepthIngestProducer(session, pull, clock)
    private val points = PointCloudIngestProducer(session, pull, clock)
    val sessionEvents = SessionEventEmitter(pull, clock)
    private val arFrame = ArCoreIngestFrame()
    private val tq = FloatArray(7)

    @Volatile var depthMode: () -> Config.DepthMode = { Config.DepthMode.DISABLED }
    @Volatile var stillCamera: StillCameraModelSource = StillCameraModelSource.NONE
    @Volatile var durablePhotoListeners: Array<DurablePhotoListener> = emptyArray()

    @Volatile private var epochForEvents = 0

    private val photoWriter = PhotoSavedIngestWriter(
        sink = pull,
        clock = clock,
        trackingState = { session.trackingState },
        groupEpoch = { epochForEvents },
        poseMatch = { photoEmitter.lastPoseMatch },
        poseGapUs = { photoEmitter.lastPoseGapUs },
    )
    val photoEmitter: PhotoSavedEmitter = PhotoSavedEmitter(
        stillCamera = { stillCamera },
        listener = { if (started) photoWriter else com.uhg0.ar_flutter_plugin_2.visibilitycontract.PhotoSavedListener.NONE },
        durableListeners = { durablePhotoListeners },
        jpegLocator = jpegLocator,
        groupEpoch = { epochForEvents },
    )

    @Volatile var started = false
        private set
    @Volatile var config: IngestShellConfig? = null
        private set
    @Volatile private var configVersion = 0
    private var appliedVersion = 0

    // Marker detection settings for a marker frame tap (read on the AR thread).
    @Volatile var markerDetectionEnabled = false; private set
    @Volatile var markerDetectionHz = 0; private set
    @Volatile var markerSizeMetres = 0f; private set

    // AR thread state.
    private var lastFrameNs = Long.MIN_VALUE
    private var lastTracking = -1
    private var lastDepthMode = -1
    private var lastSession: Any? = null

    @Volatile var frameFailures = 0L; private set
    @Volatile var restarts = 0L; private set
    @Volatile var resumes = 0L; private set
    @Volatile var trackingResets = 0L; private set
    @Volatile var depthReapplyRequests = 0L; private set

    /**
     * Asks the AR view to re-apply the session's depth mode; the view posts
     * it to its own thread. Requested after every AR session resume and when
     * depth that was acquired before stops for [DEPTH_STALL_NS] while
     * tracking (ARCore keeps answering "not yet available" after a shared
     * camera resume).
     */
    @Volatile var depthReapply: () -> Unit = {}

    // Set by [onArSessionResumed] on any thread, handled by the next frame.
    @Volatile private var resumePending = false
    private var lossFailureReason = 0
    private var watchedImageNs = Long.MIN_VALUE
    private var depthProgressNs = Long.MIN_VALUE
    private var lastDepthReapplyNs = Long.MIN_VALUE

    fun start(config: IngestShellConfig) {
        this.config = config
        markerDetectionEnabled = config.flags and ShellControlLayout.FLAG_MARKER_DETECTION != 0
        markerDetectionHz = config.markerDetectionHz
        markerSizeMetres = config.markerSizeMetres
        epochForEvents = config.groupEpoch and 0xffff
        pull.clearQueued()
        configVersion++
        started = true
    }

    fun stop() {
        started = false
        pull.clearQueued()
    }

    fun setMarkerDetection(enabled: Boolean, hz: Int, sizeMetres: Float) {
        markerDetectionEnabled = enabled
        markerDetectionHz = hz
        markerSizeMetres = sizeMetres
    }

    /** Starts or stops the debug recording; emits the matching session event. */
    fun setRecording(enabled: Boolean): Boolean {
        val r = recorder ?: return !enabled
        return if (enabled) {
            val ok = r.isRecording || r.start()
            if (ok) sessionEvents.emit(IngestMessageLayout.SESSION_RECORDING_STARTED, 0L, lastFrameNsSnapshot, session.trackingState, epochForEvents)
            ok
        } else {
            if (r.isRecording) {
                sessionEvents.emit(IngestMessageLayout.SESSION_RECORDING_STOPPED, r.messages.toLong(), lastFrameNsSnapshot, session.trackingState, epochForEvents)
                r.stop()
            }
            true
        }
    }

    @Volatile private var lastFrameNsSnapshot = 0L

    /**
     * The AR session resumed after a pause (any thread). The next frame
     * emits [IngestMessageLayout.SESSION_AR_RESUMED] and a tracking reset
     * (ARCore may restart its motion tracking across a pause), drops the pose
     * history and staged samples of before the pause and asks for the depth
     * mode to be re-applied.
     */
    fun onArSessionResumed() {
        if (started) resumePending = true
    }

    /**
     * AR thread: an anchor moved by [jumpMillimetres] within one frame while
     * tracking (an ARCore map correction). Emits a tracking reset so the grid
     * can start a new submap.
     */
    fun onAnchorJump(frameNs: Long, jumpMillimetres: Int) {
        if (!started) return
        trackingResets++
        sessionEvents.emit(
            IngestMessageLayout.SESSION_TRACKING_RESET,
            IngestMessageLayout.TRACKING_RESET_ANCHOR_JUMP.toLong() or (jumpMillimetres.toLong() shl 8),
            frameNs, session.trackingState, epochForEvents,
        )
    }

    /** Seam: one call per AR frame. */
    override fun onFrame(frame: Frame) {
        if (!started) return
        arFrame.frame = frame
        try {
            process(arFrame, null)
        } catch (_: RuntimeException) {
            frameFailures++
        } finally {
            arFrame.frame = null
        }
    }

    /** Seam with the session identity, so a replaced AR session is noticed. */
    fun onFrame(sessionIdentity: Any, frame: Frame) {
        if (!started) return
        arFrame.frame = frame
        try {
            process(arFrame, sessionIdentity)
        } catch (_: RuntimeException) {
            frameFailures++
        } finally {
            arFrame.frame = null
        }
    }

    override fun onDurableTerminal(request: CaptureCommitRequest, terminal: CaptureTerminal, exposureTimestampNs: Long) {
        if (!started && durablePhotoListeners.isEmpty()) return
        photoEmitter.onDurableTerminal(request, terminal, exposureTimestampNs)
    }

    /** AR thread: one frame through the producers. [sessionIdentity] is null when unknown. */
    internal fun process(source: IngestFrameSource, sessionIdentity: Any?) {
        if (!started) return
        val ts = source.timestampNs
        if (appliedVersion != configVersion) {
            appliedVersion = configVersion
            session.groupEpoch = config?.groupEpoch?.and(0xffff) ?: 0
            resetSession()
            lastTracking = -1
            lastDepthMode = -1
            lastSession = sessionIdentity
        } else if ((sessionIdentity != null && lastSession != null && sessionIdentity !== lastSession) ||
            (lastFrameNs != Long.MIN_VALUE && ts < lastFrameNs)) {
            session.groupEpoch = (session.groupEpoch + 1) and 0xffff
            resetSession()
            restarts++
            epochForEvents = session.groupEpoch
            sessionEvents.emit(IngestMessageLayout.SESSION_AR_RESTARTED, session.groupEpoch.toLong(), ts,
                source.trackingState, session.groupEpoch)
        }
        if (sessionIdentity != null) lastSession = sessionIdentity
        epochForEvents = session.groupEpoch
        if (resumePending) {
            resumePending = false
            val gapNs = if (lastFrameNs == Long.MIN_VALUE) 0L else ts - lastFrameNs
            resetPoseState()
            resumes++
            trackingResets++
            sessionEvents.emit(IngestMessageLayout.SESSION_AR_RESUMED, gapNs, ts, source.trackingState, session.groupEpoch)
            sessionEvents.emit(IngestMessageLayout.SESSION_TRACKING_RESET,
                IngestMessageLayout.TRACKING_RESET_AR_RESUMED.toLong(), ts, source.trackingState, session.groupEpoch)
            requestDepthReapply(clock.nowNs())
        }
        photoEmitter.recordEpochStart(source.androidCameraTimestampNs, session.groupEpoch)
        lastFrameNs = ts
        lastFrameNsSnapshot = ts

        val tracking = source.trackingState
        session.trackingState = tracking
        if (tracking != lastTracking) {
            if (lastTracking == IngestTrackingState.TRACKING) {
                lossFailureReason = source.trackingFailureReason
                sessionEvents.emit(IngestMessageLayout.SESSION_TRACKING_LOST, tracking.toLong(), ts, tracking, session.groupEpoch)
            } else if (tracking == IngestTrackingState.TRACKING && lastTracking != -1) {
                sessionEvents.emit(IngestMessageLayout.SESSION_TRACKING_REGAINED, 0L, ts, tracking, session.groupEpoch)
                // ARCore may have reset its motion tracking during the loss: poses of before
                // the loss are not interpolated with poses after it.
                resetPoseState()
                trackingResets++
                sessionEvents.emit(IngestMessageLayout.SESSION_TRACKING_RESET,
                    IngestMessageLayout.TRACKING_RESET_TRACKING_FAILURE.toLong() or (lossFailureReason.toLong() shl 8),
                    ts, tracking, session.groupEpoch)
                lossFailureReason = 0
            }
            lastTracking = tracking
        }
        if (tracking != IngestTrackingState.TRACKING) {
            val reason = source.trackingFailureReason
            if (reason != 0) lossFailureReason = reason
        }

        val cfg = config
        val preference = cfg?.depthSourcePreference ?: IngestShellConfig.DEPTH_AUTOMATIC
        val mode = if (preference == IngestShellConfig.DEPTH_POINTS_ONLY) IngestFrameSource.DEPTH_MODE_DISABLED
            else IngestFrameSource.depthModeCode(depthMode())
        if (mode != lastDepthMode) {
            if (lastDepthMode != -1) {
                sessionEvents.emit(IngestMessageLayout.SESSION_DEPTH_MODE_CHANGED, mode.toLong(), ts, tracking, session.groupEpoch)
            }
            lastDepthMode = mode
        }
        val logGeneration = NativeSessionLogEmitter.activeGeneration
        if (logGeneration != countedLogGeneration) {
            countedLogGeneration = logGeneration
            rebaselineSessionCounters()
        }
        // Before the tracking check, so a run also shows depth stopping while not tracking.
        if (depthTelemetry != null || NativeSessionLogEmitter.active) depthTelemetryTick(ts, tracking)
        if (tracking != IngestTrackingState.TRACKING) return

        source.readPose(tq)
        session.history.pushTq(ts, tq)
        photoEmitter.recordFramePose(source.androidCameraTimestampNs, tq)
        depth.publishStaged(true)
        points.publishStaged(true)
        depth.onFrame(source, mode)
        if (mode != IngestFrameSource.DEPTH_MODE_DISABLED) watchDepthStall()
        val wantPoints = mode == IngestFrameSource.DEPTH_MODE_DISABLED ||
            (cfg != null && cfg.flags and ShellControlLayout.FLAG_POINT_CLOUD != 0)
        if (wantPoints) points.onFrame(source, tq)
        depth.publishStaged(false)
        points.publishStaged(false)
    }

    /**
     * Depth that was delivered before and has not delivered a new image for
     * [DEPTH_STALL_NS] of tracking frames is re-applied. Progress is a new
     * image timestamp, not an acquisition: ARCore keeps answering with its
     * last depth image once depth stops (seen in dataset playback), so the
     * acquisition count keeps rising while depth is stale.
     */
    private fun watchDepthStall() {
        val now = clock.nowNs()
        val imageNs = depth.latestImageNs
        if (imageNs != watchedImageNs || depthProgressNs == Long.MIN_VALUE) {
            watchedImageNs = imageNs
            depthProgressNs = now
            return
        }
        if (imageNs == Long.MIN_VALUE || now - depthProgressNs < DEPTH_STALL_NS) return
        if (lastDepthReapplyNs != Long.MIN_VALUE && now - lastDepthReapplyNs < DEPTH_REAPPLY_INTERVAL_NS) return
        requestDepthReapply(now)
    }

    private fun requestDepthReapply(now: Long) {
        lastDepthReapplyNs = now
        depthProgressNs = now
        depthReapplyRequests++
        try {
            depthReapply()
        } catch (_: RuntimeException) {
            frameFailures++
        }
    }

    private var lastTelemetryNs = Long.MIN_VALUE

    // Counter totals already handed to the session log.
    private var loggedStale = 0L
    private var loggedDuplicate = 0L
    private var loggedIdentityRefused = 0L
    private var loggedStaleAutomatic = 0L
    private var loggedFrameFailures = 0L

    // Session log opening seen by the counters (NativeSessionLogEmitter.activeGeneration).
    private var countedLogGeneration = 0L

    /**
     * A session log was opened since the last frame: growth until now
     * belongs to no log, so the new log reports only its own.
     */
    private fun rebaselineSessionCounters() {
        loggedStale = depth.staleDropped
        loggedDuplicate = depth.duplicateDropped
        loggedIdentityRefused = depth.identityRefused
        loggedStaleAutomatic = depth.identityStaleAutomatic
        loggedFrameFailures = frameFailures
    }

    /** At most once a second (shell clock): the telemetry line and the session log counters. */
    private fun depthTelemetryTick(frameNs: Long, tracking: Int) {
        val now = clock.nowNs()
        if (lastTelemetryNs != Long.MIN_VALUE && now - lastTelemetryNs < TELEMETRY_INTERVAL_NS) return
        lastTelemetryNs = now
        depthTelemetry?.let { logDepthTelemetry(it, frameNs, tracking) }
        logSessionCounters(frameNs)
    }

    /**
     * Growth of the depth and frame failure counters for the session log
     * (the same counters as the telemetry line); allocates only when a
     * counter grew.
     */
    private fun logSessionCounters(frameNs: Long) {
        val image = depth.latestImageNs
        val ageMs = if (image == Long.MIN_VALUE) -1L else (frameNs - image) / 1_000_000L
        if (depth.staleDropped > loggedStale) {
            loggedStale = NativeSessionLogEmitter.reportGrowth(
                "DEPTH_STALE", null, depth.staleDropped, loggedStale,
                "Depth images dropped as stale", mapOf("lastImageAgeMs" to ageMs),
            )
        }
        if (depth.duplicateDropped > loggedDuplicate) {
            loggedDuplicate = NativeSessionLogEmitter.reportGrowth(
                "DEPTH_DUPLICATE", null, depth.duplicateDropped, loggedDuplicate,
                "Depth images dropped as duplicates", mapOf("published" to depth.published),
            )
        }
        if (depth.identityRefused > loggedIdentityRefused) {
            loggedIdentityRefused = NativeSessionLogEmitter.reportGrowth(
                "DEPTH_REFUSED", "identity", depth.identityRefused, loggedIdentityRefused,
                "Depth images refused by the frame clock identity check",
            )
        }
        if (depth.identityStaleAutomatic > loggedStaleAutomatic) {
            loggedStaleAutomatic = NativeSessionLogEmitter.reportGrowth(
                "DEPTH_REFUSED", "staleAutomatic", depth.identityStaleAutomatic, loggedStaleAutomatic,
                "Depth images older than their frame (STALE_AUTOMATIC, counted only)",
            )
        }
        if (frameFailures > loggedFrameFailures) {
            loggedFrameFailures = NativeSessionLogEmitter.reportGrowth(
                "NATIVE_HANDLER_ERROR", "ingestFrame", frameFailures, loggedFrameFailures,
                "Grid ingest frame processing failed", mapOf("channel" to "frame", "method" to "ingestFrame"),
            )
        }
    }

    /** Allocates only the line. */
    private fun logDepthTelemetry(log: (String) -> Unit, frameNs: Long, tracking: Int) {
        val image = depth.latestImageNs
        val ageMs = if (image == Long.MIN_VALUE) "none" else ((frameNs - image) / 1_000_000L).toString()
        log(
            "[grid-depth] published=${depth.published} duplicate=${depth.duplicateDropped} " +
                "staleDropped=${depth.staleDropped} identityRefused=${depth.identityRefused} " +
                "staleAutomatic=${depth.identityStaleAutomatic} notYetAvailable=${depth.notYetAvailable} " +
                "failures=${depth.failures} acquired=${depth.acquired} lastImageAgeMs=$ageMs " +
                "reapplied=$depthReapplyRequests resumes=$resumes trackingResets=$trackingResets " +
                "mode=$lastDepthMode tracking=$tracking epoch=${session.groupEpoch}",
        )
    }

    private fun resetSession() {
        session.history.clear()
        photoEmitter.clearPoses()
        depth.reset()
        points.reset()
        lastFrameNs = Long.MIN_VALUE
    }

    /** Tracking reset within the same AR session: frame poses and staged samples of before it are dropped. */
    private fun resetPoseState() {
        session.history.clear()
        depth.reset()
        points.reset()
        depthProgressNs = Long.MIN_VALUE
    }

    fun close() {
        started = false
        pull.close()
        recorder?.stop()
    }

    /** Diagnostic counters (allocates; not for the frame path). */
    fun snapshot(): Map<String, Any?> = mapOf(
        "started" to started,
        "groupEpoch" to session.groupEpoch,
        "restarts" to restarts,
        "resumes" to resumes,
        "trackingResets" to trackingResets,
        "depthReapplyRequests" to depthReapplyRequests,
        "frameFailures" to frameFailures,
        "pull" to pull.snapshot(),
        "depth" to mapOf(
            "acquired" to depth.acquired, "published" to depth.published, "staleDropped" to depth.staleDropped,
            "duplicateDropped" to depth.duplicateDropped, "identityRefused" to depth.identityRefused,
            "identityStaleAutomatic" to depth.identityStaleAutomatic, "notYetAvailable" to depth.notYetAvailable,
            "deferred" to depth.deferred, "deferTimeouts" to depth.deferTimeouts, "poseMissing" to depth.poseMissing,
        ),
        "points" to mapOf(
            "clouds" to points.clouds, "published" to points.published, "pointsIn" to points.pointsIn,
            "pointsSent" to points.pointsSent, "nonFiniteDropped" to points.nonFiniteDropped,
            "farDropped" to points.farDropped,
            "lowConfidenceDropped" to points.lowConfidenceDropped, "rangeDropped" to points.rangeDropped, "idCountMismatch" to points.idCountMismatch,
            "deferred" to points.deferred, "poseMissing" to points.poseMissing,
        ),
        "photos" to mapOf(
            "emitted" to photoEmitter.emitted, "duplicates" to photoEmitter.duplicates,
            "poseFromHistory" to photoEmitter.poseFromHistory, "poseFromShutter" to photoEmitter.poseFromShutter,
            "poseMissing" to photoEmitter.poseMissing,
        ),
        "recording" to recorder?.path,
    )

    internal val depthProducer: DepthIngestProducer get() = depth
    internal val pointProducer: PointCloudIngestProducer get() = points
    internal val groupEpoch: Int get() = session.groupEpoch
}

private const val TELEMETRY_INTERVAL_NS = 1_000_000_000L
private const val DEPTH_STALL_NS = 3_000_000_000L
private const val DEPTH_REAPPLY_INTERVAL_NS = 5_000_000_000L
