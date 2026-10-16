import ARKit
import Foundation

/// A loaded world map waiting for the next AR session run.
///
/// The session host applies it when it builds the configuration of its next
/// run ([apply(to:)]); a run consumes it, so a later run starts without it.
/// The world map relocalizes the new run into the frame of the session that
/// saved it, which the app records as the `worldMapHint` transform source.
final class WorldMapInitialMapStore {
  static let shared = WorldMapInitialMapStore()

  private let lock = NSLock()
  private var pending: ARWorldMap?

  var hasPending: Bool {
    lock.lock(); defer { lock.unlock() }
    return pending != nil
  }

  func stage(_ map: ARWorldMap) {
    lock.lock(); pending = map; lock.unlock()
  }

  func clear() {
    lock.lock(); pending = nil; lock.unlock()
  }

  /// Drops a staged map; returns whether one was staged.
  @discardableResult
  func discardPending() -> Bool {
    lock.lock(); defer { lock.unlock() }
    let had = pending != nil
    pending = nil
    return had
  }

  /// Sets the pending map as [configuration]'s `initialWorldMap` (world
  /// tracking only) and consumes it. Returns whether a map was applied.
  @discardableResult
  func apply(to configuration: ARConfiguration) -> Bool {
    guard let world = configuration as? ARWorldTrackingConfiguration else { return false }
    lock.lock()
    let map = pending
    pending = nil
    lock.unlock()
    guard let map = map else { return false }
    world.initialWorldMap = map
    return true
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
    runStarted(withInitialWorldMap: configuration.hasInitialWorldMap, epoch: epoch)
  }

  func frameSource(_ source: ARFrameSource, trackingChanged state: ARCamera.TrackingState) {
    trackingChanged(state)
  }

  func runStarted(withInitialWorldMap: Bool, epoch: Int) {
    lock.lock()
    self.epoch = epoch
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
