import Foundation
import simd

/// The iOS visibility grid ingest shell, one per Flutter engine; the Swift
/// twin of the Android `VisibilityGridIngestShell`. Idle (each frame returns
/// after one locked flag read) until the UI isolate sends a control start
/// message, which only the grid flag path does.
///
/// Threads: [process] and the session callbacks run on the AR session
/// delegate queue and own the producers and session state (serialised by a
/// frame lock, because a frame source change arrives elsewhere); control calls
/// arrive on the platform thread and are applied at the next frame; pulls run
/// on the ingest channel's background task queue.
///
/// Group epoch: the control start sets it; every new AR session generation
/// (a run with `.resetTracking`, seen as a new frame source epoch, or frame
/// time going backwards) increments it and emits "AR session restarted". A
/// run without reset after a pause, or the end of an interruption, emits
/// "AR session resumed" and a tracking reset instead.
final class VisibilityGridIngestShell: GridShellControlTarget {
  let pull: IngestPullHandler
  let sessionEvents: SessionEventEmitter
  let depth: DepthIngestProducer
  let points: PointCloudIngestProducer
  let photos: PhotoSavedEmitter
  let recorder: IngestRecorder?
  private let clock: IngestClock

  // Control state, under [lock].
  private let lock = NSLock()
  private var started = false
  private var config: IngestShellConfig?
  private var configVersion = 0
  private var epochForEvents = 0
  private var currentTracking = IngestTrackingState.stopped
  private var lastFrameNsSnapshot: Int64 = 0
  private var markerDetectionEnabled = false
  private var markerDetectionHz = 0
  private var markerSizeMetres: Float = 0

  // Delegate queue state, under [frameLock] (frames and session callbacks
  // arrive on the delegate queue; a source change arrives on its publisher's thread).
  private let frameLock = NSLock()
  private var appliedVersion = 0
  private var groupEpoch = 0
  private var lastFrameNs: Int64 = .min
  private var lastTracking = -1
  private var lastDepthMode = -1
  private var lossFailureReason = 0
  private var lastSourceEpoch: Int?
  private var sessionStopped = false
  private var restartPending = false
  private var resumePending = false
  /// Depth mode of the running AR configuration ([IngestDepthMode]).
  private(set) var sessionDepthMode = IngestDepthMode.disabled

  private(set) var restarts = 0
  private(set) var resumes = 0
  private(set) var trackingResets = 0

  init(clock: IngestClock, recorder: IngestRecorder? = nil) {
    self.clock = clock
    self.recorder = recorder
    let pull = IngestPullHandler(clock: clock)
    self.pull = pull
    sessionEvents = SessionEventEmitter(sink: pull, clock: clock)
    depth = DepthIngestProducer(sink: pull)
    points = PointCloudIngestProducer(sink: pull)
    photos = PhotoSavedEmitter(sink: pull, clock: clock)
    photos.offersToIngest = { [weak self] in self?.isStarted ?? false }
    photos.currentEpoch = { [weak self] in self?.eventEpoch ?? 0 }
    photos.trackingState = { [weak self] in self?.trackingSnapshot ?? IngestTrackingState.stopped }
    if let recorder {
      pull.recorder = { bytes, length in recorder.record(bytes, length: length) }
    }
  }

  var isStarted: Bool { lock.lock(); defer { lock.unlock() }; return started }
  var eventEpoch: Int { lock.lock(); defer { lock.unlock() }; return epochForEvents }
  var trackingSnapshot: Int { lock.lock(); defer { lock.unlock() }; return currentTracking }
  var currentConfig: IngestShellConfig? { lock.lock(); defer { lock.unlock() }; return config }
  var currentGroupEpoch: Int { groupEpoch }

  /// Marker detection settings of a started shell as one snapshot under
  /// [lock] (control writes them on the platform thread; the frame tap and
  /// the tie point producer read them on their queues), with the group epoch
  /// of events; nil while stopped.
  var markerDetectionSettings: MarkerDetectionSettings? {
    lock.lock(); defer { lock.unlock() }
    guard started else { return nil }
    return MarkerDetectionSettings(
      enabled: markerDetectionEnabled, hz: markerDetectionHz, markerSizeMetres: markerSizeMetres,
      groupEpoch: epochForEvents)
  }

  // MARK: control (platform thread)

  func start(_ config: IngestShellConfig) {
    lock.lock()
    self.config = config
    markerDetectionEnabled = config.flags & ShellControlLayout.flagMarkerDetection != 0
    markerDetectionHz = config.markerDetectionHz
    markerSizeMetres = config.markerSizeMetres
    epochForEvents = config.groupEpoch & 0xffff
    configVersion += 1
    started = true
    lock.unlock()
    pull.clearQueued()
  }

  func stop() {
    lock.lock(); started = false; lock.unlock()
    pull.clearQueued()
  }

  func setMarkerDetection(enabled: Bool, hz: Int, sizeMetres: Float) {
    lock.lock(); defer { lock.unlock() }
    markerDetectionEnabled = enabled
    markerDetectionHz = hz
    markerSizeMetres = sizeMetres
  }

  func setRecording(_ enabled: Bool) -> Bool {
    guard let r = recorder else { return !enabled }
    lock.lock()
    let frameNs = lastFrameNsSnapshot, tracking = currentTracking, epoch = epochForEvents
    lock.unlock()
    if enabled {
      let ok = r.isRecording || r.start()
      if ok {
        sessionEvents.emit(event: IngestMessageLayout.sessionRecordingStarted, value: 0, sampleNs: frameNs,
                           trackingState: tracking, groupEpoch: epoch)
      }
      return ok
    }
    if r.isRecording {
      sessionEvents.emit(event: IngestMessageLayout.sessionRecordingStopped, value: Int64(r.messages),
                         sampleNs: frameNs, trackingState: tracking, groupEpoch: epoch)
      _ = r.stop()
    }
    return true
  }

  // MARK: session lifecycle (delegate queue)

  /// The AR session (re)started; [sourceEpoch] is the frame source's epoch.
  /// A new epoch is a new session generation (a run with `.resetTracking`);
  /// the same epoch after a pause is a resume.
  /// [depthSize] is the depth map size the capture view requested; nil keeps
  /// the producer's block downscale.
  func onSessionStarted(sourceEpoch: Int, depthMode: Int, depthSize: IngestDepthSize? = nil) {
    frameLock.lock(); defer { frameLock.unlock() }
    sessionDepthMode = depthMode
    depth.requestedSize = depthSize
    if let last = lastSourceEpoch {
      if sourceEpoch != last {
        restartPending = true
      } else if sessionStopped {
        resumePending = true
      }
    }
    lastSourceEpoch = sourceEpoch
    sessionStopped = false
  }

  /// Follows a frame source. [sourceEpoch] is nil while its session has not
  /// run yet (the start callback follows); [replacesSession] is true when a
  /// previous source existed (a new capture view owns a new `ARSession`).
  func onSourceAttached(
    sourceEpoch: Int?, depthMode: Int, depthSize: IngestDepthSize? = nil, replacesSession: Bool
  ) {
    frameLock.lock(); defer { frameLock.unlock() }
    sessionDepthMode = depthMode
    depth.requestedSize = depthSize
    if replacesSession { restartPending = true }
    lastSourceEpoch = sourceEpoch
    sessionStopped = false
  }

  func onSessionStopped() {
    frameLock.lock(); sessionStopped = true; frameLock.unlock()
  }

  func onSessionInterruptionEnded() {
    frameLock.lock(); resumePending = true; frameLock.unlock()
  }

  /// An anchor moved by [jumpMillimetres] within one frame while tracking.
  func onAnchorJump(frameNs: Int64, jumpMillimetres: Int) {
    lock.lock()
    let on = started, tracking = currentTracking, epoch = epochForEvents
    lock.unlock()
    guard on else { return }
    frameLock.lock(); trackingResets += 1; frameLock.unlock()
    sessionEvents.emit(
      event: IngestMessageLayout.sessionTrackingReset,
      value: Int64(IngestMessageLayout.trackingResetAnchorJump) | Int64(jumpMillimetres) << 8,
      sampleNs: frameNs, trackingState: tracking, groupEpoch: epoch
    )
  }

  // MARK: frames (delegate queue)

  /// One AR frame through the producers.
  func process(_ frame: IngestFrame) {
    frameLock.lock(); defer { frameLock.unlock() }
    lock.lock()
    let on = started, cfg = config, version = configVersion
    lock.unlock()
    guard on else { return }
    let ts = frame.timestampNs
    let tracking = frame.trackingState
    // A new AR session generation (world frame) starts at this frame.
    let newSession = restartPending || (lastFrameNs != .min && ts < lastFrameNs)
    if appliedVersion != version {
      appliedVersion = version
      groupEpoch = (cfg?.groupEpoch ?? 0) & 0xffff
      resetSession()
      lastTracking = -1
      lastDepthMode = -1
      restartPending = false
      resumePending = false
    } else if restartPending || (lastFrameNs != .min && ts < lastFrameNs) {
      restartPending = false
      resumePending = false
      groupEpoch = (groupEpoch + 1) & 0xffff
      resetSession()
      restarts += 1
      sessionEvents.emit(event: IngestMessageLayout.sessionArRestarted, value: Int64(groupEpoch), sampleNs: ts,
                         trackingState: tracking, groupEpoch: groupEpoch)
    }
    lock.lock()
    epochForEvents = groupEpoch
    currentTracking = tracking
    lastFrameNsSnapshot = ts
    lock.unlock()
    if resumePending {
      resumePending = false
      let gapNs = lastFrameNs == .min ? 0 : ts - lastFrameNs
      resetPoseState()
      resumes += 1
      trackingResets += 1
      sessionEvents.emit(event: IngestMessageLayout.sessionArResumed, value: gapNs, sampleNs: ts,
                         trackingState: tracking, groupEpoch: groupEpoch)
      sessionEvents.emit(event: IngestMessageLayout.sessionTrackingReset,
                         value: Int64(IngestMessageLayout.trackingResetArResumed), sampleNs: ts,
                         trackingState: tracking, groupEpoch: groupEpoch)
    }
    photos.recordEpochStart(frameNs: ts, epoch: groupEpoch, continuesSession: !newSession)
    lastFrameNs = ts

    if tracking != lastTracking {
      if lastTracking == IngestTrackingState.tracking {
        lossFailureReason = frame.trackingFailureReason
        sessionEvents.emit(event: IngestMessageLayout.sessionTrackingLost, value: Int64(tracking), sampleNs: ts,
                           trackingState: tracking, groupEpoch: groupEpoch)
      } else if tracking == IngestTrackingState.tracking && lastTracking != -1 {
        sessionEvents.emit(event: IngestMessageLayout.sessionTrackingRegained, value: 0, sampleNs: ts,
                           trackingState: tracking, groupEpoch: groupEpoch)
        // ARKit may have relocalised during the loss: samples before it and after it are not mixed.
        resetPoseState()
        trackingResets += 1
        sessionEvents.emit(
          event: IngestMessageLayout.sessionTrackingReset,
          value: Int64(IngestMessageLayout.trackingResetTrackingFailure) | Int64(lossFailureReason) << 8,
          sampleNs: ts, trackingState: tracking, groupEpoch: groupEpoch
        )
        lossFailureReason = 0
      }
      lastTracking = tracking
    }
    if tracking != IngestTrackingState.tracking {
      let reason = frame.trackingFailureReason
      if reason != 0 { lossFailureReason = reason }
    }

    let preference = cfg?.depthSourcePreference ?? IngestShellConfig.depthAutomatic
    let mode = preference == IngestShellConfig.depthPointsOnly ? IngestDepthMode.disabled : sessionDepthMode
    if mode != lastDepthMode {
      if lastDepthMode != -1 {
        sessionEvents.emit(event: IngestMessageLayout.sessionDepthModeChanged, value: Int64(mode), sampleNs: ts,
                           trackingState: tracking, groupEpoch: groupEpoch)
      }
      lastDepthMode = mode
    }
    guard tracking == IngestTrackingState.tracking else { return }

    let context = IngestSampleContext(
      sampleNs: ts, frameNs: ts, acquireNs: clock.nowNs(), trackingState: tracking, groupEpoch: groupEpoch,
      cameraToWorld: frame.cameraToWorld
    )
    depth.onFrame(frame, mode: mode, context: context)
    let wantPoints = mode == IngestDepthMode.disabled ||
      (cfg.map { $0.flags & ShellControlLayout.flagPointCloud != 0 } ?? false)
    if wantPoints { points.onFrame(frame, context: context) }
  }

  private func resetSession() {
    depth.reset()
    points.reset()
    lastFrameNs = .min
  }

  /// Tracking reset within the same AR session: samples are not deduplicated across it.
  private func resetPoseState() {
    depth.reset()
    points.reset()
  }

  /// Engine detached.
  func close() {
    lock.lock(); started = false; lock.unlock()
    pull.close()
    _ = recorder?.stop()
  }

  /// Diagnostic counters (allocates; not for the frame path).
  func snapshot() -> [String: Any] {
    [
      "started": isStarted, "groupEpoch": groupEpoch, "restarts": restarts, "resumes": resumes,
      "trackingResets": trackingResets, "depthMode": sessionDepthMode, "pull": pull.snapshot(),
      "depth": [
        "acquired": depth.acquired, "published": depth.published, "duplicateDropped": depth.duplicateDropped,
        "missing": depth.missing, "oversized": depth.oversized, "calibrationUnavailable": depth.calibrationUnavailable,
      ],
      "points": [
        "clouds": points.clouds, "published": points.published, "pointsIn": points.pointsIn,
        "pointsSent": points.pointsSent, "nonFiniteDropped": points.nonFiniteDropped, "farDropped": points.farDropped,
        "rangeDropped": points.rangeDropped,
      ],
      "photos": ["emitted": photos.emitted, "duplicates": photos.duplicates, "poseMissing": photos.poseMissing],
      "recording": recorder?.path as Any,
    ]
  }
}
