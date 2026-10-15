import ARKit
import AVFoundation
import Foundation

/// Stable codes the capture view sends to Dart in place of English text
/// (`onError`, the `initializeCapture` warning). Dart localizes them in
/// `capture_message_localizer.dart`; English details go to the device log
/// only.
enum CaptureViewMessageCode {
  /// No Metal device: the camera image cannot be drawn.
  static let metalUnavailable = "ARKIT_METAL_UNAVAILABLE"
  /// ARKit reports that camera access is denied.
  static let cameraUnauthorized = "ARKIT_CAMERA_UNAUTHORIZED"
  /// Any other ARKit session failure.
  static let sessionFailed = "ARKIT_SESSION_FAILED"
  /// `initializeCapture` without a still capture extension: poses only.
  static let stillCaptureUnavailable = "ARKIT_STILL_CAPTURE_UNAVAILABLE"

  /// The code of an `ARSession` failure.
  static func sessionFailure(_ error: Error) -> String {
    if let arError = error as? ARError, arError.code == .cameraUnauthorized {
      return cameraUnauthorized
    }
    return sessionFailed
  }
}

/// The two `ARSession` calls the host drives; a fake replaces it in tests.
protocol ARSessionDriving: AnyObject {
  func run(_ configuration: ARConfiguration, options: ARSession.RunOptions)
  func pause()
}

extension ARSession: ARSessionDriving {}

/// Owns the one `ARSession` of a capture view and fans its callbacks out to
/// [ARFrameObserver]s on the session delegate queue.
///
/// Control calls (`start`, `pause`, `resume`, `restart`, `dispose`) come from
/// the main thread. The pause/resume state machine ([ARSessionLifecycle])
/// refuses callbacks that ARKit queued before a pause or dispose, and frames
/// of an earlier run delivered after a resume. Frames are passed through, never
/// stored: ARKit stops delivering frames when too many are retained.
final class ARSessionHost: NSObject, CaptureSessionSource, ARSessionDelegate {
  let session: ARSession
  let delegateQueue: DispatchQueue
  let plan: ARSessionConfigurationPlan

  /// Called on the main thread when ARKit reports a session error.
  var onSessionFailure: ((Error) -> Void)?

  private let driver: ARSessionDriving
  private let registry: ARFrameSourceRegistry?
  private let makeConfiguration: () -> ARConfiguration
  /// Current time on the `ARFrame.timestamp` clock (system uptime).
  private let clock: () -> TimeInterval
  private let lock = NSLock()
  private var lifecycle = ARSessionLifecycle()
  private var currentConfiguration: CaptureRunConfiguration?
  private var observers = FrameObserverSet()

  /// - Parameters:
  ///   - driver: receives `run`/`pause`; defaults to [session].
  ///   - registry: published on creation and cleared on dispose; `nil` keeps
  ///     the host private (tests).
  ///   - makeConfiguration: defaults to [plan]'s world tracking configuration.
  ///   - clock: defaults to `CLOCK_UPTIME_RAW` (mach absolute time), the
  ///     `ARFrame.timestamp` base.
  init(
    plan: ARSessionConfigurationPlan,
    session: ARSession = ARSession(),
    driver: ARSessionDriving? = nil,
    registry: ARFrameSourceRegistry? = .shared,
    makeConfiguration: (() -> ARConfiguration)? = nil,
    clock: @escaping () -> TimeInterval = { Double(clock_gettime_nsec_np(CLOCK_UPTIME_RAW)) / 1e9 }
  ) {
    self.plan = plan
    self.session = session
    self.driver = driver ?? session
    self.registry = registry
    self.makeConfiguration = makeConfiguration ?? { plan.makeConfiguration() }
    self.clock = clock
    delegateQueue = DispatchQueue(label: "capture3d.ar-session-host", qos: .userInteractive)
    super.init()
    session.delegate = self
    session.delegateQueue = delegateQueue
    registry?.publish(self)
  }

  /// No weak references to `self` may be formed here: pause only. The
  /// registry holds the host weakly, so its entry is already gone.
  deinit {
    lock.lock()
    let command = lifecycle.dispose()
    lock.unlock()
    if command == .pause { driver.pause() }
  }

  // MARK: ARFrameSource

  var anchorHost: CaptureAnchorHost { session }
  var currentFrame: CaptureFrame? { session.currentFrame.map(ARKitCaptureFrame.init) }

  var runConfiguration: CaptureRunConfiguration? {
    lock.lock(); defer { lock.unlock() }
    return currentConfiguration
  }

  var deliversSceneDepth: Bool { plan.deliversSceneDepth }
  var depthOutputSize: ARDepthOutputSize { plan.depthOutputSize }

  var epoch: Int {
    lock.lock(); defer { lock.unlock() }
    return lifecycle.epoch
  }

  var lifecycleState: ARSessionLifecycleState {
    lock.lock(); defer { lock.unlock() }
    return lifecycle.state
  }

  /// A new observer of a running session receives `didStart` for the current
  /// run before its first frame.
  func addFrameObserver(_ observer: ARFrameObserver) {
    lock.lock()
    observers.add(observer)
    let replay = lifecycle.isRunning ? currentConfiguration : nil
    let epoch = lifecycle.epoch
    lock.unlock()
    guard let configuration = replay else { return }
    delegateQueue.async { [weak self, weak observer] in
      guard let self = self, let observer = observer, self.isObserving(observer) else { return }
      self.markStarted([observer])
      observer.frameSource(self, didStart: configuration, epoch: epoch)
    }
  }

  func removeFrameObserver(_ observer: ARFrameObserver) {
    lock.lock()
    observers.remove(observer)
    lock.unlock()
  }

  func captureHighResolutionFrame(completion: @escaping (CaptureFrame?, Error?) -> Void) {
    session.captureHighResolutionFrame { frame, error in
      completion(frame.map(ARKitCaptureFrame.init), error)
    }
  }

  func getCurrentWorldMap(completion: @escaping (ARWorldMap?, Error?) -> Void) {
    session.getCurrentWorldMap(completionHandler: completion)
  }

  /// `.balanced` uses the running video format's default photo settings
  /// with balanced prioritization (iOS 26); ARKit refuses `.quality` and
  /// exposure brackets, so a refusal falls back to the plain frame once.
  func captureHighResolutionFrame(quality: StillFrameQuality, completion: @escaping (CaptureFrame?, Error?) -> Void) {
    guard quality == .balanced else { return captureHighResolutionFrame(completion: completion) }
    if #available(iOS 26.0, *), let format = session.configuration?.videoFormat {
      let settings = AVCapturePhotoSettings(from: format.defaultPhotoSettings)
      settings.photoQualityPrioritization = .balanced
      session.captureHighResolutionFrame(using: settings) { [weak self] frame, error in
        if let frame = frame { return completion(ARKitCaptureFrame(frame), nil) }
        NSLog("capture3d still: balanced frame refused (%@); plain high-resolution frame", String(describing: error))
        guard let self = self else { return completion(nil, error) }
        self.captureHighResolutionFrame(completion: completion)
      }
      return
    }
    captureHighResolutionFrame(completion: completion)
  }

  // MARK: Control (main thread)

  func start() { perform { $0.start() } }
  /// The session channel's `init` ([ARSessionLifecycle.initialize]).
  func initialize() { perform { $0.initialize() } }
  func pause() {
    let now = clock()
    perform { $0.pause(now: now) }
  }
  func resume() { perform { $0.resume() } }
  /// New world frame: runs with `.resetTracking`, epoch increments.
  func restart() {
    let now = clock()
    perform { $0.restart(now: now) }
  }

  /// Idempotent. Pauses a running session, stops callbacks and clears the
  /// registry entry when it still names this host.
  func dispose() {
    lock.lock()
    let alreadyDisposed = lifecycle.state == .disposed
    let command = lifecycle.dispose()
    lock.unlock()
    if alreadyDisposed { return }
    apply(command, epoch: 0)
    if let registry = registry, registry.current === self {
      registry.publish(nil)
    }
  }

  /// Exposed for tests: the admission step `session(_:didUpdate:)` applies.
  func admitFrame(timestamp: TimeInterval) -> Bool {
    lock.lock(); defer { lock.unlock() }
    return lifecycle.admitFrame(timestamp: timestamp)
  }

  private func perform(_ transition: (inout ARSessionLifecycle) -> ARSessionCommand) {
    lock.lock()
    let command = transition(&lifecycle)
    let epoch = lifecycle.epoch
    lock.unlock()
    apply(command, epoch: epoch)
  }

  private func apply(_ command: ARSessionCommand, epoch: Int) {
    switch command {
    case .none:
      return
    case .run(let resetTracking):
      let arConfiguration = makeConfiguration()
      let configuration = CaptureRunConfiguration(arConfiguration)
      lock.lock()
      currentConfiguration = configuration
      lock.unlock()
      // Enqueued before `run`, so it precedes every frame of this run on
      // the serial delegate queue. Observers added later get it through
      // the replay in `addFrameObserver`, not twice.
      let targets = liveObservers()
      delegateQueue.async { [weak self] in
        guard let self = self else { return }
        let started = self.stillObserving(targets)
        self.markStarted(started)
        started.forEach { $0.frameSource(self, didStart: configuration, epoch: epoch) }
      }
      driver.run(arConfiguration, options: resetTracking ? [.resetTracking, .removeExistingAnchors] : [])
    case .pause:
      driver.pause()
      let targets = liveObservers()
      delegateQueue.async { [weak self] in
        guard let self = self else { return }
        self.stillObserving(targets).forEach { $0.frameSourceDidStop(self) }
      }
    }
  }

  private func liveObservers() -> [ARFrameObserver] {
    lock.lock(); defer { lock.unlock() }
    return observers.live
  }

  private func stillObserving(_ targets: [ARFrameObserver]) -> [ARFrameObserver] {
    lock.lock(); defer { lock.unlock() }
    return observers.stillObserving(targets)
  }

  /// Observers that received `didStart` of a run; only they get frames, so
  /// no observer sees a frame before its start.
  private func startedObservers() -> [ARFrameObserver] {
    lock.lock(); defer { lock.unlock() }
    return observers.started
  }

  private func markStarted(_ started: [ARFrameObserver]) {
    lock.lock(); defer { lock.unlock() }
    observers.markStarted(started)
  }

  private func isObserving(_ observer: ARFrameObserver) -> Bool {
    lock.lock(); defer { lock.unlock() }
    return observers.contains(observer)
  }

  private var admitsSessionCallbacks: Bool {
    lock.lock(); defer { lock.unlock() }
    return lifecycle.admitsSessionCallbacks
  }

  // MARK: ARSessionDelegate (delegate queue)

  func session(_ session: ARSession, didUpdate frame: ARFrame) {
    guard admitFrame(timestamp: frame.timestamp) else { return }
    let captureFrame = ARKitCaptureFrame(frame)
    for observer in startedObservers() {
      observer.frameSource(self, didUpdate: captureFrame)
    }
  }

  func session(_ session: ARSession, cameraDidChangeTrackingState camera: ARCamera) {
    guard admitsSessionCallbacks else { return }
    let state = camera.trackingState
    startedObservers().forEach { $0.frameSource(self, trackingChanged: state) }
  }

  func sessionWasInterrupted(_ session: ARSession) {
    guard admitsSessionCallbacks else { return }
    startedObservers().forEach { $0.frameSourceWasInterrupted(self) }
  }

  func sessionInterruptionEnded(_ session: ARSession) {
    guard admitsSessionCallbacks else { return }
    startedObservers().forEach { $0.frameSourceInterruptionEnded(self) }
  }

  /// Keep the world frame after an interruption: relocalize instead of reset.
  func sessionShouldAttemptRelocalization(_ session: ARSession) -> Bool { true }

  func session(_ session: ARSession, didFailWithError error: Error) {
    guard lifecycleState != .disposed else { return }
    DispatchQueue.main.async { [weak self] in
      guard let self = self, self.lifecycleState != .disposed else { return }
      self.onSessionFailure?(error)
    }
  }
}
