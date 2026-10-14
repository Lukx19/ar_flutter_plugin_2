import ARKit
import Foundation

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
final class ARSessionHost: NSObject, ARFrameSource, ARSessionDelegate {
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
  private var currentConfiguration: ARConfiguration?
  private var observers: [FrameObserverEntry] = []

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

  var deliversSceneDepth: Bool { plan.deliversSceneDepth }

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
    observers.removeAll { $0.observer == nil || $0.observer === observer }
    observers.append(FrameObserverEntry(observer))
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
    observers.removeAll { $0.observer == nil || $0.observer === observer }
    lock.unlock()
  }

  // MARK: Control (main thread)

  func start() { perform { $0.start() } }
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
      let configuration = makeConfiguration()
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
      driver.run(configuration, options: resetTracking ? [.resetTracking, .removeExistingAnchors] : [])
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
    return observers.compactMap { $0.observer }
  }

  private func stillObserving(_ targets: [ARFrameObserver]) -> [ARFrameObserver] {
    let current = liveObservers()
    return targets.filter { target in current.contains { $0 === target } }
  }

  /// Observers that received `didStart` of a run; only they get frames, so
  /// no observer sees a frame before its start.
  private func startedObservers() -> [ARFrameObserver] {
    lock.lock(); defer { lock.unlock() }
    return observers.filter { $0.started }.compactMap { $0.observer }
  }

  private func markStarted(_ started: [ARFrameObserver]) {
    lock.lock(); defer { lock.unlock() }
    for entry in observers where started.contains(where: { $0 === entry.observer }) {
      entry.started = true
    }
  }

  private func isObserving(_ observer: ARFrameObserver) -> Bool {
    lock.lock(); defer { lock.unlock() }
    return observers.contains { $0.observer === observer }
  }

  private var admitsSessionCallbacks: Bool {
    lock.lock(); defer { lock.unlock() }
    return lifecycle.admitsSessionCallbacks
  }

  // MARK: ARSessionDelegate (delegate queue)

  func session(_ session: ARSession, didUpdate frame: ARFrame) {
    guard admitFrame(timestamp: frame.timestamp) else { return }
    for observer in startedObservers() {
      observer.frameSource(self, didUpdate: frame)
    }
  }

  func session(_ session: ARSession, cameraDidChangeTrackingState camera: ARCamera) {
    guard admitsSessionCallbacks else { return }
    startedObservers().forEach { $0.frameSource(self, trackingChanged: camera) }
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

private final class FrameObserverEntry {
  weak var observer: ARFrameObserver?
  /// Set on the delegate queue when the observer received `didStart`.
  var started = false
  init(_ observer: ARFrameObserver) { self.observer = observer }
}
