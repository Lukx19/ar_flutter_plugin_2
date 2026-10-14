import Foundation

/// What the session host must do to the `ARSession` after a lifecycle event.
enum ARSessionCommand: Equatable {
  case none
  case run(resetTracking: Bool)
  case pause
}

enum ARSessionLifecycleState: Equatable {
  /// Created; the session has never run.
  case idle
  case running
  case paused
  /// Terminal: no command and no callback is accepted afterwards.
  case disposed
}

/// Pause/resume state machine of the capture view's `ARSession`.
///
/// Pure value type: the host applies the returned command to the session and
/// asks [admitFrame] before fanning a frame out. Late callbacks are the
/// failure mode this guards against (Android teardown history): a frame or
/// tracking callback queued before `pause()`/dispose and delivered after it,
/// or a frame captured before a pause or restart delivered after the next
/// run started, is refused. Times are `ARFrame.timestamp` seconds (system
/// uptime); the host passes the current uptime as `now`.
struct ARSessionLifecycle: Equatable {
  private(set) var state: ARSessionLifecycleState = .idle
  /// Increments on every run with `.resetTracking`; poses of different epochs
  /// never belong to one world frame.
  private(set) var epoch = 0
  /// Frames must be strictly newer than this: the newest admitted frame, or
  /// the time of the last pause or restart when later.
  private(set) var frameFence: TimeInterval = -.infinity

  var isRunning: Bool { state == .running }

  /// First run of the session, or a full restart (new world frame).
  mutating func start() -> ARSessionCommand {
    switch state {
    case .disposed, .running:
      return .none
    case .idle, .paused:
      state = .running
      epoch += 1
      return .run(resetTracking: true)
    }
  }

  /// Re-runs a running or paused session with `.resetTracking` (for example
  /// after a configuration change that invalidates the world frame).
  mutating func restart(now: TimeInterval) -> ARSessionCommand {
    switch state {
    case .disposed:
      return .none
    case .idle, .running, .paused:
      raiseFence(now)
      state = .running
      epoch += 1
      return .run(resetTracking: true)
    }
  }

  mutating func pause(now: TimeInterval) -> ARSessionCommand {
    guard state == .running else { return .none }
    raiseFence(now)
    state = .paused
    return .pause
  }

  /// Continues tracking in the same world frame (no reset, same epoch). A
  /// session that never ran starts instead.
  mutating func resume() -> ARSessionCommand {
    switch state {
    case .idle:
      return start()
    case .paused:
      state = .running
      return .run(resetTracking: false)
    case .running, .disposed:
      return .none
    }
  }

  /// Idempotent; pauses a running session once.
  mutating func dispose() -> ARSessionCommand {
    let wasRunning = state == .running
    state = .disposed
    return wasRunning ? .pause : .none
  }

  /// True when a frame with this sensor timestamp may reach observers.
  mutating func admitFrame(timestamp: TimeInterval) -> Bool {
    guard state == .running, timestamp > frameFence else { return false }
    frameFence = timestamp
    return true
  }

  private mutating func raiseFence(_ now: TimeInterval) {
    frameFence = max(frameFence, now)
  }

  /// Non-frame session callbacks (tracking, interruption) are delivered only
  /// while running.
  var admitsSessionCallbacks: Bool { state == .running }
}
