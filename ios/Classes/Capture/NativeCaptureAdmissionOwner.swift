import Foundation
import simd

/// The durable store operations the admission owner uses (template path:
/// admission requests carry no component descriptors).
protocol NativeCaptureStorePort: AnyObject {
  func replayFenceBeforeExposure(_ request: CaptureCommitRequest) throws -> CaptureReceipt?
  func acceptBeforeExposure(_ request: CaptureCommitRequest) throws -> CaptureReceipt
  /// [shot]: the other images and the record of a burst shot,
  /// committed with the components or not at all.
  func commit(_ request: CaptureCommitRequest, exposureTimestampNanoseconds: Int64, components: [(CaptureComponentKind, [UInt8])],
              shot: DurableShotPayload?) throws -> CaptureReceipt
  func queryReceipt(_ identity: CaptureAttemptIdentity) throws -> CaptureReceipt?
  func rebaseSameAttempt(_ request: CaptureCommitRequest) throws -> CaptureReceipt
  func abandon(_ request: CaptureCommitRequest, terminal: CaptureTerminal) throws -> CaptureReceipt
  /// File of the committed JPEG of [terminal]; nil when unknown.
  func committedImageURL(_ terminal: CaptureTerminal) -> URL?
  /// Files of a committed burst shot (images after the first one by
  /// index, and the shot record); nil for a single image or when unknown.
  func committedShotFiles(_ terminal: CaptureTerminal) -> (images: [Int: URL], record: URL)?
}

extension NativeCaptureStorePort {
  func committedImageURL(_ terminal: CaptureTerminal) -> URL? { nil }
  func committedShotFiles(_ terminal: CaptureTerminal) -> (images: [Int: URL], record: URL)? { nil }
}

extension DurableCaptureStore: NativeCaptureStorePort {
  func replayFenceBeforeExposure(_ request: CaptureCommitRequest) throws -> CaptureReceipt? {
    try replayTemplateFenceBeforeExposure(request.template)
  }
  func acceptBeforeExposure(_ request: CaptureCommitRequest) throws -> CaptureReceipt {
    try acceptCaptureBeforeExposure(request)
  }
  func commit(_ request: CaptureCommitRequest, exposureTimestampNanoseconds: Int64, components: [(CaptureComponentKind, [UInt8])],
              shot: DurableShotPayload?) throws -> CaptureReceipt {
    try commitStreamedFinalized(request.template, exposureTimestampNanoseconds: exposureTimestampNanoseconds, components: components, shot: shot)
  }
  func abandon(_ request: CaptureCommitRequest, terminal: CaptureTerminal) throws -> CaptureReceipt {
    try abandonTemplate(request.template, terminal: terminal)
  }
}

/// Full immutable callback qualifier; a timestamp or generation alone is never enough.
struct CaptureAttemptQualifier: Hashable {
  var identity: CaptureAttemptIdentity
  var exposureGeneration: Int64
}

/// What the camera knew about one still: the photo saved event fields.
struct StillExposureMetadata {
  var exposureTimestampNs: Int64
  /// `ARCamera.transform` of the exposed frame; nil when tracking was unavailable.
  var cameraToWorld: simd_float4x4?
  /// fx, fy, cx, cy at the still resolution.
  var intrinsics: simd_float4?
  var width: Int
  var height: Int
  /// Laplacian variance of the JPEG (Android `jpegLaplacianVarianceV1`).
  var sharpness: Double?
  /// False when the current video frame replaced an unavailable high-resolution frame.
  var highResolution: Bool
}

/// One complete still: every required component, read once by the store.
/// A shot (burst or quality) carries its first image as the JPEG component
/// and all its images, each with its own frame, in [shot].
struct StillComponentSet {
  var qualifier: CaptureAttemptQualifier
  var components: [(CaptureComponentKind, [UInt8])]
  var metadata: StillExposureMetadata
  var shot: CapturedShot? = nil
}

enum StillExposureOutcome {
  case components(StillComponentSet)
  case failure(CaptureAttemptQualifier, String)
}

/// Attempt-qualified camera seam. The callback runs at most once per accepted
/// request, on any queue; a cancelled request may still call back (late).
protocol StillExposurePort: AnyObject {
  func requestExposure(_ qualifier: CaptureAttemptQualifier, required: Set<CaptureComponentKind>,
                       callback: @escaping (StillExposureOutcome) -> Void) -> Bool
  /// The same request with the attempt's lane: a camera may take a
  /// different still for a manual shutter (a burst or quality shot) than for
  /// Auto. Defaults to the lane-free request.
  func requestExposure(_ qualifier: CaptureAttemptQualifier, required: Set<CaptureComponentKind>, lane: CaptureLane,
                       callback: @escaping (StillExposureOutcome) -> Void) -> Bool
  func cancelExposure(_ qualifier: CaptureAttemptQualifier)
}

extension StillExposurePort {
  func requestExposure(_ qualifier: CaptureAttemptQualifier, required: Set<CaptureComponentKind>, lane: CaptureLane,
                       callback: @escaping (StillExposureOutcome) -> Void) -> Bool {
    requestExposure(qualifier, required: required, callback: callback)
  }
}

/// One committed durable photo for the visibility grid photo saved event.
struct DurableTerminalPhoto {
  var request: CaptureCommitRequest
  var terminal: CaptureTerminal
  var metadata: StillExposureMetadata?
  /// The committed JPEG in the durable store (read only; for tie points).
  var imageURL: URL? = nil
  /// The burst or quality shot of this photo, when it was one.
  var shot: CapturedShot? = nil
  /// Committed files of the shot's images after the first one, by index.
  var shotImageURLs: [Int: URL] = [:]
}

/// The per-view native admission owner (Android `NativeCaptureAdapterV2`):
/// durable acceptance before the only exposure, one running and one funded
/// waiting finalizer with manual priority, lifecycle cuts, the 10 s and 30 s
/// deadlines, and exactly one terminal per attempt (a store terminal is
/// claimed once; a repeated or late callback is counted and discarded).
///
/// Confined to one serial queue: every method, store call and exposure
/// outcome runs on the owner's queue, so there is no lock. The exposure port
/// calls back on any queue; [NativeCaptureBinding] hops to the owner queue.
final class NativeCaptureAdmissionOwner {
  static let automaticStallMs: Int64 = 10_000
  static let terminalFenceMs: Int64 = 30_000
  static let pauseLifecycleReason = "backgrounded"

  private final class Work {
    let request: CaptureCommitRequest
    let acceptedReceipt: CaptureReceipt
    let acceptedAtMs: Int64
    let qualifier: CaptureAttemptQualifier
    var exposureRequested = false
    var exposureCounted = false
    var componentsClaimed = false
    var terminalClaimed = false
    var tenSecondPresented = false
    var pendingLifecycleReason: String?
    var metadata: StillExposureMetadata?
    var shot: CapturedShot?

    init(request: CaptureCommitRequest, acceptedReceipt: CaptureReceipt, acceptedAtMs: Int64, qualifier: CaptureAttemptQualifier) {
      self.request = request
      self.acceptedReceipt = acceptedReceipt
      self.acceptedAtMs = acceptedAtMs
      self.qualifier = qualifier
    }
  }

  private let store: NativeCaptureStorePort
  private let exposure: StillExposurePort
  private let nowMs: () -> Int64
  private let events: (NativeCaptureEvent) -> Void
  private let durableTerminal: (DurableTerminalPhoto) -> Void
  /// Delivers an exposure outcome back onto the owner's queue.
  var deliver: (@escaping () -> Void) -> Void = { $0() }

  private var work: [CaptureAttemptIdentity: Work] = [:]
  private var running: CaptureAttemptIdentity?
  private var fundedWaiting: CaptureAttemptIdentity?
  private var counters = CaptureResourceSnapshot()
  private var unknownAttempts = Set<CaptureAttemptIdentity>()
  private var closed = false
  private var lifecycleDrain = false
  private var nextExposureGeneration: Int64 = 0

  init(store: NativeCaptureStorePort, exposure: StillExposurePort, nowMs: @escaping () -> Int64,
       events: @escaping (NativeCaptureEvent) -> Void, durableTerminal: @escaping (DurableTerminalPhoto) -> Void) {
    self.store = store
    self.exposure = exposure
    self.nowMs = nowMs
    self.events = events
    self.durableTerminal = durableTerminal
  }

  var activeAttemptCount: Int { work.count }
  var exposedAttemptCount: Int { work.values.filter { $0.exposureRequested }.count }
  /// Identities live in this owner now: the cut a later lifecycle drain may touch.
  var liveAttemptIdentities: Set<CaptureAttemptIdentity> { Set(work.keys) }

  /// Exposed attempts among [cut] that have not reached a terminal yet.
  func exposedAttemptCount(in cut: Set<CaptureAttemptIdentity>) -> Int {
    work.values.filter { $0.exposureRequested && cut.contains($0.request.accepted.identity) }.count
  }

  // MARK: Admission

  func admit(_ request: CaptureCommitRequest) throws -> CaptureReceipt {
    guard !closed else { throw DurableStoreStateError("Native capture owner is closed") }
    let accepted = request.accepted
    if let active = work[accepted.identity] {
      if active.request == request { return active.acceptedReceipt }
      throw DurableStoreConflict("Changed replay conflicts with active capture identity")
    }
    guard !work.values.contains(where: { $0.request.accepted.lane == accepted.lane }) else {
      throw DurableStoreStateError("lane-capacity-\(accepted.lane.wireName)")
    }
    if let replay = try store.replayFenceBeforeExposure(request) {
      if replay.terminal != nil { return replay }
      // A durable accepted identity from a prior owner is outcome-unknown:
      // fence it without a second exposure.
      return try abandon(request, reason: "durable-replay-no-reexposure")
    }
    // Capacity and manual priority are settled before durable acceptance; a
    // running exposure is never preempted.
    if running != nil, let waiting = fundedWaiting {
      if accepted.lane == .manual, let waitingWork = work[waiting], waitingWork.request.accepted.lane == .automatic {
        _ = try abandon(waitingWork.request, reason: "manual-priority-evicted-automatic")
      } else {
        throw DurableStoreStateError("finalizer-capacity")
      }
    }
    let isRunning: Bool
    if running == nil { running = accepted.identity; isRunning = true } else { fundedWaiting = accepted.identity; isRunning = false }
    let receipt: CaptureReceipt
    do {
      receipt = try store.acceptBeforeExposure(request)
    } catch {
      release(accepted.identity)
      startRunningIfReady()
      throw error
    }
    if receipt.terminal != nil {
      release(accepted.identity)
      startRunningIfReady()
      return receipt
    }
    nextExposureGeneration += 1
    let value = Work(request: request, acceptedReceipt: receipt, acceptedAtMs: nowMs(),
                     qualifier: CaptureAttemptQualifier(identity: accepted.identity, exposureGeneration: nextExposureGeneration))
    work[accepted.identity] = value
    emit(NativeCaptureEvent(kind: .accepted, attemptId: accepted.identity.attemptId))
    if isRunning { start(value) }
    return receipt
  }

  // MARK: Lifecycle

  /// Applies [event] to every live attempt, or only to those in [cut] (the
  /// attempts that were live when the lifecycle change arrived).
  func onLifecycle(_ event: CaptureLifecycleEvent, cut: Set<CaptureAttemptIdentity>? = nil) {
    let runningBefore = running
    lifecycleDrain = true
    for value in Array(work.values) where cut?.contains(value.request.accepted.identity) ?? true {
      switch event {
      case .automaticDisabled:
        if value.request.accepted.lane == .automatic && running != value.request.accepted.identity {
          _ = try? abandon(value.request, reason: "automatic-disabled-waiting")
        }
      case .routeLeft: lifecycleTransfer(value, "route-left")
      case .viewReplaced: lifecycleTransfer(value, "view-replaced")
      case .arSessionReplaced: lifecycleTransfer(value, "ar-session-replaced")
      case .backgrounded: lifecycleTransfer(value, Self.pauseLifecycleReason)
      case .processRestarted: lifecycleTransfer(value, "process-restarted")
      }
    }
    lifecycleDrain = false
    if let after = running, after != runningBefore { startRunningIfReady() }
  }

  /// 10 s stops an unexposed owner; an exposed owner is presented as unknown
  /// at 10 s and queried (then abandoned) exactly once at 30 s, never re-exposed.
  func advanceDeadlines() {
    let now = nowMs()
    for value in Array(work.values) where work[value.request.accepted.identity] === value {
      let elapsed = now - value.acceptedAtMs
      if !value.exposureRequested {
        if elapsed >= Self.automaticStallMs { _ = try? abandon(value.request, reason: "funded-waiting-timeout-before-exposure") }
        continue
      }
      guard value.exposureCounted else { continue }
      if elapsed >= Self.terminalFenceMs {
        queryOrAbandon(value, "terminal-fence-30s")
      } else if elapsed >= Self.automaticStallMs && !value.tenSecondPresented {
        value.tenSecondPresented = true
        presentUnknown(value, "terminal-presentation-10s")
      }
    }
  }

  func snapshot() -> CaptureResourceSnapshot {
    var value = counters
    value.unknownQueries = Int64(unknownAttempts.count)
    value.running = running == nil ? 0 : 1
    value.fundedWaiting = fundedWaiting == nil ? 0 : 1
    return value
  }

  /// Exposed attempts transfer to restart recovery; unexposed ones are abandoned.
  func close() {
    guard !closed else { return }
    closed = true
    for value in Array(work.values) {
      if value.exposureCounted { transferUnknown(value, "shutdown-transfer") }
      else { _ = try? abandon(value.request, reason: "shutdown-before-exposure") }
    }
  }

  // MARK: Exposure

  private func startRunningIfReady() {
    guard let identity = running, let value = work[identity] else { return }
    start(value)
  }

  private func start(_ value: Work) {
    guard !closed, !value.exposureRequested, running == value.request.accepted.identity else { return }
    value.exposureRequested = true
    let qualifier = value.qualifier
    let requested = exposure.requestExposure(
      qualifier, required: value.request.accepted.profile.requiredComponents, lane: value.request.accepted.lane
    ) { [weak self] outcome in
      guard let self = self else { return }
      self.deliver { self.handle(outcome) }
    }
    guard work[value.request.accepted.identity] === value else { return }
    if !requested {
      value.exposureRequested = false
      _ = try? abandon(value.request, reason: "exposure-request-rejected")
      return
    }
    countExposure(value)
  }

  /// Owner queue: one camera outcome.
  func handle(_ outcome: StillExposureOutcome) {
    switch outcome {
    case .failure(let qualifier, let reason):
      guard let value = work[qualifier.identity], value.qualifier == qualifier, !closed,
            value.pendingLifecycleReason == nil, !value.componentsClaimed else {
        counters.lateCallbacks += 1
        return
      }
      value.componentsClaimed = true
      _ = try? abandon(value.request, reason: "camera-\(reason)")
    case .components(let set):
      guard let value = work[set.qualifier.identity], value.qualifier == set.qualifier,
            running == set.qualifier.identity, !closed, value.pendingLifecycleReason == nil, !value.componentsClaimed else {
        counters.closedComponents += Int64(set.components.count)
        counters.lateCallbacks += 1
        return
      }
      value.componentsClaimed = true
      countExposure(value)
      value.metadata = set.metadata
      value.shot = set.shot
      let kinds = set.components.map { $0.0 }
      guard kinds.count == Set(kinds).count, Set(kinds) == value.request.accepted.profile.requiredComponents else {
        counters.closedComponents += Int64(set.components.count)
        _ = try? abandon(value.request, reason: "malformed-component-set")
        return
      }
      emit(NativeCaptureEvent(kind: .finalizing, attemptId: value.request.accepted.identity.attemptId))
      let receipt: CaptureReceipt?
      do {
        receipt = try store.commit(value.request, exposureTimestampNanoseconds: set.metadata.exposureTimestampNs,
                                   components: set.components, shot: set.shot?.payload)
      } catch {
        receipt = nil
      }
      counters.closedComponents += Int64(set.components.count)
      guard work[value.request.accepted.identity] === value else { return }
      if let receipt = receipt, receipt.terminal != nil { finish(value, receipt) }
      else { queryOrAbandon(value, "store-unknown") }
    }
  }

  // MARK: Terminal paths

  private func queryOrAbandon(_ value: Work, _ reason: String) {
    let identity = value.request.accepted.identity
    emit(NativeCaptureEvent(kind: .recovering, attemptId: identity.attemptId, reason: reason, recoveryContext: value.request.recoveryContext))
    unknownAttempts.insert(identity)
    if let receipt = try? store.queryReceipt(identity), receipt.terminal != nil {
      if work[identity] === value { finish(value, receipt) }
      return
    }
    if let rebased = try? store.rebaseSameAttempt(value.request) {
      if work[identity] === value { finish(value, rebased) }
      return
    }
    guard work[identity] === value else { return }
    _ = try? abandon(value.request, reason: reason)
  }

  private func presentUnknown(_ value: Work, _ reason: String) {
    let identity = value.request.accepted.identity
    emit(NativeCaptureEvent(kind: .recovering, attemptId: identity.attemptId, reason: reason, recoveryContext: value.request.recoveryContext))
    unknownAttempts.insert(identity)
    if let receipt = try? store.queryReceipt(identity), receipt.terminal != nil, work[identity] === value {
      finish(value, receipt)
    }
  }

  private func lifecycleTransfer(_ value: Work, _ reason: String) {
    if value.exposureCounted {
      if value.pendingLifecycleReason == nil { value.pendingLifecycleReason = reason }
      transferUnknown(value, "\(reason)-transfer")
    } else {
      _ = try? abandon(value.request, reason: "\(reason)-before-exposure")
    }
  }

  /// A lifecycle cut hands an exposed attempt to durable restart recovery.
  /// A pause keeps the view, so a terminal already durable is published.
  private func transferUnknown(_ value: Work, _ reason: String) {
    let identity = value.request.accepted.identity
    emit(NativeCaptureEvent(kind: .recovering, attemptId: identity.attemptId, reason: reason, recoveryContext: value.request.recoveryContext))
    unknownAttempts.insert(identity)
    let receipt = (try? store.queryReceipt(identity)).flatMap { $0?.terminal != nil ? $0 : nil }
    guard work[identity] === value else { return }
    if value.pendingLifecycleReason == Self.pauseLifecycleReason, let receipt = receipt {
      finish(value, receipt)
      return
    }
    work[identity] = nil
    release(identity)
    exposure.cancelExposure(value.qualifier)
  }

  @discardableResult
  private func abandon(_ request: CaptureCommitRequest, reason: String) throws -> CaptureReceipt {
    let value = work[request.accepted.identity]
    let terminal = CaptureTerminal(kind: .abandonedAttempt, identity: request.accepted.identity,
                                   canonicalTerminalHash: reason, reason: reason)
    let receipt = try store.abandon(request, terminal: terminal)
    finish(value, receipt, recoveryContext: request.recoveryContext)
    return receipt
  }

  private func finish(_ value: Work?, _ receipt: CaptureReceipt, recoveryContext: NativeCaptureRecoveryContext? = nil) {
    let identity = receipt.identity
    guard let terminal = receipt.terminal else { return }
    if let value = value {
      if value.terminalClaimed || work[identity] !== value { return }
      value.terminalClaimed = true
    }
    work[identity] = nil
    release(identity)
    if let value = value {
      exposure.cancelExposure(value.qualifier)
      unknownAttempts.remove(identity)
      if value.exposureCounted {
        if terminal.kind == .committedPicture { counters.committed += 1 } else { counters.abandoned += 1 }
      }
    }
    let context = recoveryContext ?? value?.request.recoveryContext
    emit(NativeCaptureWire.terminalEvent(terminal, recoveryContext: context))
    emit(NativeCaptureEvent(kind: .health, resources: snapshot()))
    // Photo saved seam: once per claimed durable committed terminal.
    if let value = value, terminal.kind == .committedPicture {
      durableTerminal(DurableTerminalPhoto(
        request: value.request, terminal: terminal, metadata: value.metadata,
        imageURL: store.committedImageURL(terminal), shot: value.shot,
        shotImageURLs: value.shot == nil ? [:] : store.committedShotFiles(terminal)?.images ?? [:]))
    }
    if !lifecycleDrain { startRunningIfReady() }
  }

  private func release(_ identity: CaptureAttemptIdentity) {
    if running == identity { running = fundedWaiting; fundedWaiting = nil }
    else if fundedWaiting == identity { fundedWaiting = nil }
  }

  private func countExposure(_ value: Work) {
    if !value.exposureCounted {
      value.exposureCounted = true
      counters.exposures += 1
    }
  }

  private func emit(_ event: NativeCaptureEvent) { events(event) }
}

/// Startup recovery and the bounded terminal replay (Android
/// `NativeCaptureRecoveryDispatcherV2`): recovery runs before any admission;
/// the newest two terminal or recovering events stay replayable until Dart
/// acknowledges them. Confined to the owner queue.
final class NativeCaptureRecoveryReplay {
  enum State { case initializing, ready, failed, closed }
  static let failedReason = "durable-startup-recovery-failed"
  static let failedKey = "recovery-failed"

  private(set) var state: State = .initializing
  private var replay: [(String, NativeCaptureEvent)] = []
  private let events: (NativeCaptureEvent) -> Void

  init(events: @escaping (NativeCaptureEvent) -> Void) {
    self.events = events
  }

  func recover(_ project: () throws -> [DurableCaptureStore.RecoveryProjection]) {
    guard state == .initializing else { return }
    do {
      let projections = try project().map(Self.event)
      state = .ready
      for event in projections { try? emitAndRetain(event) }
      if projections.isEmpty { events(Self.readyEvent) }
    } catch {
      state = .failed
      try? emitAndRetain(NativeCaptureEvent(kind: .recoveryFailed, reason: Self.failedReason))
    }
  }

  /// Throws the admission error code while recovery is not ready.
  func requireAdmissionReady() throws {
    switch state {
    case .ready: return
    case .initializing: throw NativeCaptureOwnerError.recoveryPending
    case .failed: throw NativeCaptureOwnerError.recoveryFailed
    case .closed: throw NativeCaptureOwnerError.recoveryClosed
    }
  }

  func emitLive(_ event: NativeCaptureEvent) {
    guard state != .closed else { return }
    try? emitAndRetain(event)
  }

  func replaySnapshot() -> [NativeCaptureEvent] {
    switch state {
    case .closed, .initializing: return []
    case .failed: return replay.map { $0.1 }
    case .ready: return replay.isEmpty ? [Self.readyEvent] : replay.map { $0.1 }
    }
  }

  func acknowledge(_ attemptId: String) {
    replay.removeAll { $0.0 == attemptId }
    if state == .ready && replay.isEmpty { events(Self.readyEvent) }
  }

  func close() { state = .closed }

  private func emitAndRetain(_ event: NativeCaptureEvent) throws {
    if event.kind == .recoveryFailed {
      replay.removeAll { $0.0 == Self.failedKey }
      replay.append((Self.failedKey, event))
    } else if event.isTerminal || event.kind == .recovering, let key = event.attemptId {
      if let index = replay.firstIndex(where: { $0.0 == key }) {
        let existing = replay[index].1
        if existing.isTerminal && event.isTerminal && existing.terminalIdentity != event.terminalIdentity {
          throw DurableStoreConflict("Changed durable terminal conflicts with replay identity")
        }
        replay[index] = (key, event)
      } else {
        replay.append((key, event))
      }
      while replay.count > 2 { replay.removeFirst() }
    }
    events(event)
  }

  static let readyEvent = NativeCaptureEvent(kind: .ready, reason: "native-owner-ready")

  static func event(_ projection: DurableCaptureStore.RecoveryProjection) -> NativeCaptureEvent {
    NativeCaptureEvent(
      kind: projection.kind == .committedPicture ? .committed : projection.kind == .abandonedAttempt ? .abandoned : .recovering,
      attemptId: projection.attemptId, captureId: projection.captureId, captureRevision: projection.captureRevision,
      manifestId: projection.manifestId, jpegSizeBytes: projection.jpegSizeBytes, dngSizeBytes: projection.dngSizeBytes,
      reason: projection.reason, recoveryContext: projection.recoveryContext)
  }
}

/// Admission errors with their `arcapture` error codes.
enum NativeCaptureOwnerError: Error {
  case recoveryPending, recoveryFailed, recoveryClosed, rootPending, closed

  var code: String {
    switch self {
    case .recoveryPending: return "NATIVE_CAPTURE_V2_RECOVERY_PENDING"
    case .recoveryFailed: return "NATIVE_CAPTURE_V2_RECOVERY_FAILED"
    case .recoveryClosed, .closed: return "NATIVE_CAPTURE_V2_RECOVERY_CLOSED"
    case .rootPending: return "NATIVE_CAPTURE_V2_ROOT_PENDING"
    }
  }

  var message: String {
    switch self {
    case .recoveryPending: return "Native capture recovery is still running."
    case .recoveryFailed: return "Native capture recovery failed; recreate the capture view before admitting exposure."
    case .recoveryClosed: return "Native capture recovery is closed."
    case .rootPending: return "The previous capture view still owns the durable root; retry after its shutdown completes."
    case .closed: return "Native capture binding is closed."
    }
  }
}
