import Darwin
import Foundation

/// Process-local exclusive authority over one durable capture root: a
/// replacement view cannot open the root until the previous owner closed it
/// (`NATIVE_CAPTURE_V2_ROOT_PENDING`, which Dart retries).
final class NativeCaptureRootLease {
  private static let lock = NSLock()
  private static var active = Set<String>()
  private let key: String
  private var released = false

  private init(key: String) { self.key = key }

  static func acquire(_ root: URL) throws -> NativeCaptureRootLease {
    let key = root.standardizedFileURL.resolvingSymlinksInPath().path
    lock.lock(); defer { lock.unlock() }
    guard !active.contains(key) else { throw NativeCaptureOwnerError.rootPending }
    active.insert(key)
    return NativeCaptureRootLease(key: key)
  }

  func release() {
    Self.lock.lock(); defer { Self.lock.unlock() }
    guard !released else { return }
    released = true
    Self.active.remove(key)
  }

  deinit { release() }
}

/// Everything one view's binding opens on first use.
struct NativeCaptureRootResources {
  var lease: NativeCaptureRootLease
  var store: DurableCaptureStore
}

/// Per-view native capture owner (Android `NativeCaptureBindingV2`): one
/// serial durable queue owns the root lease, budget, store, startup recovery,
/// the admission owner and the 1 s deadline tick. Calls come from the main
/// thread and complete on the main thread; events are delivered through
/// [events] on the main thread.
final class NativeCaptureBinding {
  typealias Completion<T> = (Result<T, Error>) -> Void

  static let pauseSettleMs: Int64 = 5_000
  /// Up to 1 s (50 x 20 ms) for a replaced view to release the durable root.
  static let rootLeaseWaits = 50
  static let rootLeaseWaitMicros: useconds_t = 20_000

  let queue = DispatchQueue(label: "capture3d.native-capture.durable", qos: .userInitiated)
  private let openResources: () throws -> NativeCaptureRootResources
  private let exposure: StillExposurePort
  private let nowMs: () -> Int64
  private let events: (NativeCaptureEvent) -> Void
  private let durableTerminal: (DurableTerminalPhoto) -> Void

  // Owner queue state.
  private var resources: NativeCaptureRootResources?
  private var recovery: NativeCaptureRecoveryReplay?
  private var owner: NativeCaptureAdmissionOwner?
  private var deadlineTimer: DispatchSourceTimer?
  private var closed = false
  private var pauseDeadlineMs: Int64?

  init(openResources: @escaping () throws -> NativeCaptureRootResources, exposure: StillExposurePort,
       nowMs: @escaping () -> Int64 = { Int64(Date().timeIntervalSince1970 * 1000) },
       events: @escaping (NativeCaptureEvent) -> Void, durableTerminal: @escaping (DurableTerminalPhoto) -> Void) {
    self.openResources = openResources
    self.exposure = exposure
    self.nowMs = nowMs
    self.events = events
    self.durableTerminal = durableTerminal
  }

  /// Opens the production root under Application Support.
  static func productionResources(appFiles: URL = NativeCaptureStorePaths.applicationFiles()) throws -> NativeCaptureRootResources {
    let lease = try NativeCaptureRootLease.acquire(NativeCaptureStorePaths.nativeRoot(appFiles))
    do {
      let budgetFiles = RootedDurableFiles(backend: try PosixDurableFileBackend(root: NativeCaptureStorePaths.budget(appFiles)))
      let usable = { usableBytes(NativeCaptureStorePaths.nativeRoot(appFiles)) }
      let budget = try StorageBudgetLedger(
        files: budgetFiles,
        policy: StorageBudgetPolicy(quotaBytes: max(usable() / 2, NativeCaptureReservationBounds.captureCoexistenceBytes), freeSpaceFloorBytes: 0),
        freeBytes: usable)
      let storeFiles = RootedDurableFiles(backend: try PosixDurableFileBackend(root: NativeCaptureStorePaths.store(appFiles)))
      return NativeCaptureRootResources(lease: lease, store: try DurableCaptureStore(files: storeFiles, budget: budget))
    } catch {
      lease.release()
      throw error
    }
  }

  static func usableBytes(_ url: URL) -> Int64 {
    var info = statfs()
    guard statfs(url.path, &info) == 0 else { return 0 }
    return Int64(info.f_bavail) * Int64(info.f_bsize)
  }

  // MARK: Operations (main thread in, main thread out)

  func admit(_ arguments: Any?, completion: @escaping Completion<[String: Any]>) {
    perform(completion) { owner, recovery in
      let request = try NativeCaptureWire.decodeAdmission(arguments)
      try recovery.requireAdmissionReady()
      let receipt = try owner.admit(request)
      return NativeCaptureWire.admissionReply(receipt, recoveryContext: request.recoveryContext)
    }
  }

  func replay(completion: @escaping Completion<[[String: Any]]>) {
    perform(completion) { _, recovery in recovery.replaySnapshot().map(NativeCaptureWire.event) }
  }

  func acknowledge(_ attemptId: String, completion: @escaping Completion<Bool>) {
    perform(completion) { _, recovery in
      guard !attemptId.isEmpty else { throw NativeCaptureWireError.invalid("attemptId is required") }
      recovery.acknowledge(attemptId)
      return true
    }
  }

  func health(completion: @escaping Completion<[String: Any]>) {
    perform(completion) { owner, _ in NativeCaptureWire.event(NativeCaptureEvent(kind: .health, resources: owner.snapshot())) }
  }

  func lifecycle(_ name: String, completion: @escaping Completion<Bool>) {
    perform(completion) { owner, _ in
      let event: CaptureLifecycleEvent
      switch name {
      case "automaticDisabled": event = .automaticDisabled
      case "routeLeft": event = .routeLeft
      case "processRestarted": event = .processRestarted
      default: throw NativeCaptureWireError.invalid("Unsupported V2 lifecycle event: \(name)")
      }
      owner.onLifecycle(event)
      return true
    }
  }

  /// Starts startup recovery on the durable queue (the first operation also does).
  func start() {
    queue.async { [weak self] in _ = try? self?.ensureOpen() }
  }

  /// The AR session paused: exposed attempts get up to 5 s to reach their
  /// terminal, then every remaining attempt is cut as backgrounded.
  func onSessionPaused() {
    queue.async { [weak self] in
      guard let self = self, !self.closed, self.owner != nil else { return }
      self.pauseDeadlineMs = self.nowMs() + Self.pauseSettleMs
      self.checkPauseSettled()
    }
  }

  /// Closes the owner: exposed attempts transfer to restart recovery, others
  /// are abandoned; the store and root lease are released. Idempotent.
  func close(completion: @escaping () -> Void = {}) {
    queue.async { [weak self] in
      self?.closeOnQueue()
      DispatchQueue.main.async(execute: completion)
    }
  }

  // MARK: Owner queue

  private func perform<T>(_ completion: @escaping Completion<T>, _ body: @escaping (NativeCaptureAdmissionOwner, NativeCaptureRecoveryReplay) throws -> T) {
    queue.async { [weak self] in
      let result: Result<T, Error>
      if let self = self {
        result = Result { () throws -> T in
          let (owner, recovery) = try self.ensureOpen()
          return try body(owner, recovery)
        }
      } else {
        result = .failure(NativeCaptureOwnerError.closed)
      }
      DispatchQueue.main.async { completion(result) }
    }
  }

  private func ensureOpen() throws -> (NativeCaptureAdmissionOwner, NativeCaptureRecoveryReplay) {
    guard !closed else { throw NativeCaptureOwnerError.closed }
    if let owner = owner, let recovery = recovery { return (owner, recovery) }
    // A replaced view releases the root on its own durable queue a moment
    // after this view starts; wait briefly before answering ROOT_PENDING.
    var waits = 0
    var resourcesOpened: NativeCaptureRootResources?
    while resourcesOpened == nil {
      do {
        resourcesOpened = try openResources()
      } catch NativeCaptureOwnerError.rootPending where waits < Self.rootLeaseWaits {
        waits += 1
        usleep(Self.rootLeaseWaitMicros)
      }
    }
    let opened = resourcesOpened!
    resources = opened
    let replay = NativeCaptureRecoveryReplay(events: { [weak self] event in self?.events(event) })
    recovery = replay
    let created = NativeCaptureAdmissionOwner(
      store: opened.store, exposure: exposure, nowMs: nowMs,
      // Every live event passes the replay, which drops it once closed.
      events: { [weak replay] event in replay?.emitLive(event) },
      durableTerminal: { [weak self] photo in self?.durableTerminal(photo) })
    created.deliver = { [weak self] block in
      self?.queue.async { [weak self] in
        guard let self = self, !self.closed else { return }
        block()
        self.checkPauseSettled()
      }
    }
    owner = created
    let store = opened.store
    replay.recover { try store.recoverAndProject() }
    let timer = DispatchSource.makeTimerSource(queue: queue)
    timer.schedule(deadline: .now() + 1, repeating: 1)
    timer.setEventHandler { [weak self] in
      guard let self = self, !self.closed else { return }
      self.owner?.advanceDeadlines()
      self.checkPauseSettled()
    }
    timer.resume()
    deadlineTimer = timer
    return (created, replay)
  }

  private func checkPauseSettled() {
    guard let deadline = pauseDeadlineMs, let owner = owner else { return }
    if owner.exposedAttemptCount == 0 || nowMs() >= deadline {
      pauseDeadlineMs = nil
      owner.onLifecycle(.backgrounded)
    }
  }

  private func closeOnQueue() {
    guard !closed else { return }
    deadlineTimer?.cancel()
    deadlineTimer = nil
    recovery?.close()
    owner?.onLifecycle(.viewReplaced)
    owner?.close()
    closed = true
    owner = nil
    recovery = nil
    resources?.lease.release()
    resources = nil
  }
}
