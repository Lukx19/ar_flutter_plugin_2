import Foundation
import UIKit

/// The two `UIApplication` background task calls; a fake replaces them in tests.
protocol BackgroundTaskRunning: AnyObject {
  func beginBackgroundTask(withName name: String?, expirationHandler: (() -> Void)?) -> UIBackgroundTaskIdentifier
  func endBackgroundTask(_ identifier: UIBackgroundTaskIdentifier)
  /// Seconds the system still grants this background stay.
  var backgroundTimeRemaining: TimeInterval { get }
}

/// The shared application's background tasks (main thread only).
final class ApplicationBackgroundTasks: BackgroundTaskRunning {
  func beginBackgroundTask(withName name: String?, expirationHandler: (() -> Void)?) -> UIBackgroundTaskIdentifier {
    UIApplication.shared.beginBackgroundTask(withName: name) {
      MainActor.assumeIsolated { expirationHandler?() }
    }
  }

  func endBackgroundTask(_ identifier: UIBackgroundTaskIdentifier) {
    UIApplication.shared.endBackgroundTask(identifier)
  }

  var backgroundTimeRemaining: TimeInterval { UIApplication.shared.backgroundTimeRemaining }
}

/// Keeps the process running while the Dart durability layer writes its
/// lifecycle checkpoint after the app enters the background.
///
/// The Dart side needs no new channel call: on `inactive`, `hidden` and
/// `paused` its `SessionLifecycleBridge` starts a grid checkpoint
/// (`saveGroup`) and the session log controller drains and fsyncs, exactly as
/// on Android. Without a background task iOS may suspend the process within
/// a few seconds, in the middle of that write. This task begins on
/// `didEnterBackground` and ends exactly once on the first of:
///
/// - the app returning to the foreground (`willEnterForeground`);
/// - the hold window [holdSeconds] elapsing (the checkpoint's pause budget
///   is 1 s; the window allows a slow flash write several times over);
/// - the system expiration handler (recorded as a warning);
/// - the plugin detaching from the engine ([stopObserving] or deinit).
///
/// Every outcome is one `BACKGROUND_TASK_ENDED` session log record with
/// `endReason` (`foreground`, `holdElapsed`, `expired`, `detached`) and
/// `heldMs`. The `detached` record never reaches a session log: the
/// services close the native log window right after it, and Dart cannot
/// drain once the engine detached.
/// Callbacks run on the main thread (`UIApplication` notifications and the
/// expiration handler), so the state needs no lock; [schedule] defers the
/// hold timeout on the main queue.
final class BackgroundCheckpointTask {
  enum EndReason: String {
    case foreground
    case holdElapsed
    case expired
    /// The plugin detached from the engine while a task was held (written,
    /// then dropped with the closing log window; never drained).
    case detached
  }

  static let taskName = "capture3d.durability.checkpoint"
  /// Default hold window, seconds.
  static let defaultHoldSeconds: TimeInterval = 8

  private let runner: BackgroundTaskRunning
  private let emitter: NativeSessionLogEmitter
  private let holdSeconds: TimeInterval
  private let schedule: (TimeInterval, @escaping () -> Void) -> Void
  private let nowNs: () -> UInt64
  private let notificationCenter: NotificationCenter
  private var tokens: [NSObjectProtocol] = []

  private var identifier: UIBackgroundTaskIdentifier = .invalid
  private var startedNs: UInt64 = 0
  /// Increments per begin, so a hold timeout of an earlier stay is ignored.
  private var stay = 0

  /// Begun and ended counts (tests and diagnostics).
  private(set) var begun = 0
  private(set) var ended = 0
  private(set) var lastEndReason: EndReason?

  init(
    runner: BackgroundTaskRunning,
    emitter: NativeSessionLogEmitter = .shared,
    holdSeconds: TimeInterval = BackgroundCheckpointTask.defaultHoldSeconds,
    notificationCenter: NotificationCenter = .default,
    nowNs: @escaping () -> UInt64 = { clock_gettime_nsec_np(CLOCK_UPTIME_RAW) },
    schedule: @escaping (TimeInterval, @escaping () -> Void) -> Void = { delay, work in
      DispatchQueue.main.asyncAfter(deadline: .now() + delay, execute: work)
    }
  ) {
    self.runner = runner
    self.emitter = emitter
    self.holdSeconds = holdSeconds
    self.notificationCenter = notificationCenter
    self.nowNs = nowNs
    self.schedule = schedule
  }

  deinit { stopObserving() }

  var isHeld: Bool { identifier != .invalid }

  func startObserving() {
    guard tokens.isEmpty else { return }
    tokens.append(notificationCenter.addObserver(
      forName: UIApplication.didEnterBackgroundNotification, object: nil, queue: nil
    ) { [weak self] _ in self?.didEnterBackground() })
    tokens.append(notificationCenter.addObserver(
      forName: UIApplication.willEnterForegroundNotification, object: nil, queue: nil
    ) { [weak self] _ in self?.willEnterForeground() })
  }

  /// Stops listening and ends a held task (engine detached).
  func stopObserving() {
    tokens.forEach(notificationCenter.removeObserver)
    tokens = []
    end(.detached)
  }

  func didEnterBackground() {
    guard identifier == .invalid else { return }
    stay += 1
    let thisStay = stay
    startedNs = nowNs()
    let id = runner.beginBackgroundTask(withName: Self.taskName) { [weak self] in
      self?.end(.expired)
    }
    guard id != .invalid else { return }
    identifier = id
    begun += 1
    // The system grants a limited stay; end before it would expire.
    let remaining = runner.backgroundTimeRemaining
    let hold = remaining.isFinite ? max(0, min(holdSeconds, remaining - 1)) : holdSeconds
    schedule(hold) { [weak self] in
      guard let self = self, self.stay == thisStay else { return }
      self.end(.holdElapsed)
    }
  }

  func willEnterForeground() {
    end(.foreground)
  }

  private func end(_ reason: EndReason) {
    let id = identifier
    guard id != .invalid else { return }
    identifier = .invalid
    ended += 1
    lastEndReason = reason
    let heldMs = Int((nowNs() &- startedNs) / 1_000_000)
    emitter.record(
      NativeSessionLogCodes.backgroundTaskEnded,
      message: reason == .expired
        ? "Background checkpoint task expired after \(heldMs) ms"
        : "Background checkpoint task ended (\(reason.rawValue)) after \(heldMs) ms",
      data: ["endReason": reason.rawValue, "heldMs": heldMs],
      variant: reason.rawValue,
      level: reason == .expired ? .warning : .info
    )
    runner.endBackgroundTask(id)
  }
}
