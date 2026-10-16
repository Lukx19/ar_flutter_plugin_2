import ARKit
import Foundation

/// A loaded world map waiting for the next AR session run.
///
/// The session host applies it when it builds the configuration of its next
/// run ([apply(to:)]); a run consumes it, so a later run starts without it.
/// The world map relocalizes the new run into the frame of the session that
/// saved it, which the app records as the `worldMapHint` transform source.
///
/// Ownership: every channel `load` claims a new generation ([claim()]) and
/// `clear` starts one too, so a later load or clear supersedes a load still
/// reading or decoding. Such a load stages nothing ([stage(_:generation:)])
/// and never restarts the session.
final class WorldMapInitialMapStore {
  static let shared = WorldMapInitialMapStore()

  /// What became of the map a load of one generation staged.
  enum LoadState: Equatable {
    /// Still waiting for a run.
    case pending
    /// A run start or restart took it (applied, or dropped by a recorded
    /// session's reset run).
    case consumedByRun
    /// A later load or clear owns the store.
    case superseded
  }

  /// A map applied to a run: [source] is the session-relative path the
  /// load named (`<sessionId>/groups/<groupId>/world_map-<seq>.arworldmap`),
  /// nil for a map staged without one.
  struct Applied: Equatable {
    let source: String?
  }

  private let lock = NSLock()
  private var pending: ARWorldMap?
  private var pendingSource: String?
  private var generation = 0
  /// Generation of the staged map the latest run consumed.
  private var consumedGeneration: Int?

  var hasPending: Bool {
    lock.lock(); defer { lock.unlock() }
    return pending != nil
  }

  /// Stages [map] for the current generation (tests and callers outside
  /// the channel's ownership).
  func stage(_ map: ARWorldMap, source: String? = nil) {
    lock.lock(); pending = map; pendingSource = source; lock.unlock()
  }

  /// Starts a new generation for one load and returns it: drops a map an
  /// earlier load staged and supersedes earlier loads still in flight.
  func claim() -> Int {
    lock.lock(); defer { lock.unlock() }
    pending = nil
    pendingSource = nil
    generation += 1
    return generation
  }

  /// Stages [map] only while [generation] still owns the store; [source]
  /// is the path the load read it from, reported with the run's
  /// relocalization.
  @discardableResult
  func stage(_ map: ARWorldMap, generation owner: Int, source: String? = nil) -> Bool {
    lock.lock(); defer { lock.unlock() }
    guard owner == generation else { return false }
    pending = map
    pendingSource = source
    return true
  }

  /// Drops a staged map and supersedes every load in flight.
  func clear() {
    lock.lock(); pending = nil; pendingSource = nil; generation += 1; lock.unlock()
  }

  /// Drops the staged map while [generation] still owns the store (a load
  /// that failed must not leave an earlier session's map for the next run).
  func discardPending(generation owner: Int) {
    lock.lock(); defer { lock.unlock() }
    guard owner == generation else { return }
    pending = nil
    pendingSource = nil
  }

  func state(of owner: Int) -> LoadState {
    lock.lock(); defer { lock.unlock() }
    guard owner == generation else { return .superseded }
    if pending != nil { return .pending }
    return consumedGeneration == owner ? .consumedByRun : .superseded
  }

  /// Drops a staged map; returns whether one was staged. A dropped map
  /// counts as consumed by the current run (`.consumedByRun`, reported as
  /// `appliedByStart`), so a pending restart is skipped: a recorded playback
  /// reset run calls this and drops the map without relocalizing, unlike a
  /// live start that begins from it.
  @discardableResult
  func discardPending() -> Bool {
    lock.lock(); defer { lock.unlock() }
    let had = pending != nil
    if had { consumedGeneration = generation }
    pending = nil
    pendingSource = nil
    return had
  }

  /// Sets the pending map as [configuration]'s `initialWorldMap` (world
  /// tracking only) and consumes it. Returns whether a map was applied.
  @discardableResult
  func apply(to configuration: ARConfiguration) -> Bool {
    applyMap(to: configuration) != nil
  }

  /// Like [apply(to:)]; returns what was applied (with the map's source
  /// path), or nil when no map was applied.
  func applyMap(to configuration: ARConfiguration) -> Applied? {
    guard let world = configuration as? ARWorldTrackingConfiguration else { return nil }
    lock.lock()
    let map = pending
    let source = pendingSource
    pending = nil
    pendingSource = nil
    if map != nil { consumedGeneration = generation }
    lock.unlock()
    guard let map = map else { return nil }
    world.initialWorldMap = map
    return Applied(source: source)
  }
}

/// Relocalization of a run started with an `initialWorldMap`, reported to
/// Dart through the world map channel's `status`:
///
/// - `none`: the current run has no initial world map;
/// - `relocalizing`: started with one, tracking not yet normal;
/// - `relocalized`: tracking became normal, so the run shares the saved
///   session's world frame: transform source `worldMapHint`.
///
/// A run started from a map a channel `load` staged also reports that
/// map's path (`mapPath`, relative to the load's session directory), so the
/// app can tell which saved group's world frame the run relocalizes into.
///
/// The reply also names the run's latest tracking state (`tracking`:
/// `normal`, `notAvailable` or the limited reason) and whether ARKit
/// reported `limited(relocalizing)` during it (`relocalizingReported`),
/// which only a run with an initial world map does. A run that starts with
/// a map is recorded as `WORLD_MAP_APPLIED`.
///
/// Also keeps the latest `ARFrame.worldMappingStatus` (saving needs
/// `extending` or `mapped` for a useful map). Runs on the session delegate
/// queue; reads come from the main thread.
final class WorldMapRelocalizationMonitor: ARFrameObserver {
  enum State: String {
    case none
    case relocalizing
    case relocalized
  }

  static let transformSource = "worldMapHint"

  private let emitter: NativeSessionLogEmitter
  private let nowNs: () -> UInt64
  private let lock = NSLock()
  private var state = State.none
  private var epoch = 0
  private var startedNs: UInt64 = 0
  private var relocalizedMs: Int?
  private var mappingStatus: ARFrame.WorldMappingStatus = .notAvailable
  private var tracking = "notAvailable"
  private var relocalizingReported = false
  private var mapPath: String?

  init(
    emitter: NativeSessionLogEmitter = .shared,
    nowNs: @escaping () -> UInt64 = { clock_gettime_nsec_np(CLOCK_UPTIME_RAW) }
  ) {
    self.emitter = emitter
    self.nowNs = nowNs
  }

  var currentState: State {
    lock.lock(); defer { lock.unlock() }
    return state
  }

  /// The `status` reply of the world map channel.
  func statusReply() -> [String: Any] {
    lock.lock(); defer { lock.unlock() }
    var reply: [String: Any] = [
      "status": "ok",
      "relocalization": state.rawValue,
      "epoch": epoch,
      "mappingStatus": WorldMapArchive.mappingStatusName(mappingStatus),
      "tracking": tracking,
      "relocalizingReported": relocalizingReported,
    ]
    if state != .none, let path = mapPath { reply["mapPath"] = path }
    if state == .relocalized {
      reply["transformSource"] = Self.transformSource
      if let ms = relocalizedMs { reply["relocalizedMs"] = ms }
    }
    return reply
  }

  // MARK: ARFrameObserver (session delegate queue)

  func frameSource(_ source: ARFrameSource, didUpdate frame: CaptureFrame) {
    let status = frame.worldMappingStatus
    lock.lock(); mappingStatus = status; lock.unlock()
  }

  func frameSource(_ source: ARFrameSource, didStart configuration: CaptureRunConfiguration, epoch: Int) {
    runStarted(
      withInitialWorldMap: configuration.hasInitialWorldMap, epoch: epoch,
      mapPath: configuration.initialWorldMapSource)
  }

  func frameSource(_ source: ARFrameSource, trackingChanged state: ARCamera.TrackingState) {
    trackingChanged(state)
  }

  func runStarted(withInitialWorldMap: Bool, epoch: Int, mapPath: String? = nil) {
    lock.lock()
    self.epoch = epoch
    self.mapPath = withInitialWorldMap ? mapPath : nil
    relocalizedMs = nil
    mappingStatus = .notAvailable
    tracking = "notAvailable"
    relocalizingReported = false
    startedNs = nowNs()
    state = withInitialWorldMap ? .relocalizing : .none
    lock.unlock()
    if withInitialWorldMap {
      emitter.record(
        NativeSessionLogCodes.worldMapApplied,
        message: "AR run started from a saved world map",
        data: ["epoch": epoch],
        variant: nil,
        level: .info
      )
    }
  }

  func trackingChanged(_ tracking: ARCamera.TrackingState) {
    lock.lock()
    self.tracking = Self.trackingName(tracking)
    if case .limited(.relocalizing) = tracking { relocalizingReported = true }
    guard case .normal = tracking, state == .relocalizing else { lock.unlock(); return }
    state = .relocalized
    let ms = Int((nowNs() &- startedNs) / 1_000_000)
    relocalizedMs = ms
    let runEpoch = epoch
    lock.unlock()
    emitter.record(
      NativeSessionLogCodes.worldMapRelocalized,
      message: "Relocalized to the saved world map after \(ms) ms",
      data: ["relocalizedMs": ms, "epoch": runEpoch, "transformSource": Self.transformSource],
      variant: nil,
      level: .info
    )
  }

  static func trackingName(_ tracking: ARCamera.TrackingState) -> String {
    switch tracking {
    case .normal: return "normal"
    case .notAvailable: return "notAvailable"
    case .limited(let reason): return NativeAnomalyEmitter.limitedReasonName(reason)
    }
  }
}
