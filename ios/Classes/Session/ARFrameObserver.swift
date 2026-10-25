import ARKit
import Foundation

/// Seam between a frame source (the live session host in `Session/`, or a
/// recorded session in `Playback/`) and frame consumers such as the
/// visibility grid ingest shell (`VisibilityGrid/`).
///
/// The source calls observers on its delegate queue with [CaptureFrame]s.
/// Observers copy what they need and return quickly; they never retain a
/// frame (ARKit stops delivering frames when too many are retained).
protocol ARFrameObserver: AnyObject {
  /// Every camera frame, on the source's delegate queue.
  func frameSource(_ source: ARFrameSource, didUpdate frame: CaptureFrame)
  /// The session (re)started with this configuration. [epoch] increments on
  /// every run with `.resetTracking` (and on every re-initialisation of a
  /// recorded session), so poses of different epochs never mix.
  func frameSource(_ source: ARFrameSource, didStart configuration: CaptureRunConfiguration, epoch: Int)
  func frameSource(_ source: ARFrameSource, trackingChanged state: ARCamera.TrackingState)
  /// Anchors added or changed since the previous callback (ARKit
  /// `session(_:didAdd:)` and `session(_:didUpdate:)`, the counterpart of
  /// ARCore `Frame.getUpdatedAnchors`), on the delegate queue.
  func frameSource(_ source: ARFrameSource, didUpdateAnchors anchors: [ARAnchor])
  func frameSourceWasInterrupted(_ source: ARFrameSource)
  func frameSourceInterruptionEnded(_ source: ARFrameSource)
  /// The session was paused or the view disposed; no frames follow until the next start.
  func frameSourceDidStop(_ source: ARFrameSource)
}

extension ARFrameObserver {
  func frameSource(_ source: ARFrameSource, didStart configuration: CaptureRunConfiguration, epoch: Int) {}
  func frameSource(_ source: ARFrameSource, trackingChanged state: ARCamera.TrackingState) {}
  func frameSource(_ source: ARFrameSource, didUpdateAnchors anchors: [ARAnchor]) {}
  func frameSourceWasInterrupted(_ source: ARFrameSource) {}
  func frameSourceInterruptionEnded(_ source: ARFrameSource) {}
  func frameSourceDidStop(_ source: ARFrameSource) {}
}

/// What a frame source exposes to consumers.
protocol ARFrameSource: AnyObject {
  /// Where consumers add and remove anchors (the `ARSession`, or the anchor
  /// store of a recorded session).
  var anchorHost: CaptureAnchorHost { get }
  /// The newest frame, for per-draw and on-demand readers (render loop,
  /// camera pose query); read it for one call only.
  var currentFrame: CaptureFrame? { get }
  /// Configuration of the current run; nil before the first run.
  var runConfiguration: CaptureRunConfiguration? { get }
  var lifecycleState: ARSessionLifecycleState { get }
  /// True when the configuration delivers LiDAR `sceneDepth` (sets the
  /// LiDAR-class source flag, bit 2, in ingest headers).
  var deliversSceneDepth: Bool { get }
  /// Depth map size the capture view requested for the grid
  /// (`depthWidth`/`depthHeight` creation parameters).
  var depthOutputSize: ARDepthOutputSize { get }
  var epoch: Int { get }
  func addFrameObserver(_ observer: ARFrameObserver)
  func removeFrameObserver(_ observer: ARFrameObserver)
  /// A still for the shutter (`ARSession.captureHighResolutionFrame`; a
  /// recorded session answers with the recorded still nearest to the
  /// request). [completion] runs once, on any queue.
  func captureHighResolutionFrame(completion: @escaping (CaptureFrame?, Error?) -> Void)
  /// The session's world map (`ARSession.getCurrentWorldMap`; a recorded
  /// session answers with the recording's map). [completion] runs once.
  func getCurrentWorldMap(completion: @escaping (ARWorldMap?, Error?) -> Void)
  /// A still at [quality]: `.balanced` asks ARKit for multi-frame
  /// processing (iOS 26 `captureHighResolutionFrame(using:)` with
  /// `photoQualityPrioritization = .balanced`) and falls back to the plain
  /// high-resolution frame where that is refused. A recorded session
  /// answers with its recorded still for every quality. [completion] runs once.
  func captureHighResolutionFrame(quality: StillFrameQuality, completion: @escaping (CaptureFrame?, Error?) -> Void)
  /// Profile measurement must refuse unsupported processing rather than silently
  /// measuring the fast fallback as an enhanced capture.
  func captureHighResolutionFrameForProfile(quality: StillFrameQuality, completion: @escaping (CaptureFrame?, Error?) -> Void)
  /// True when stills are recorded ones (playback): every request answers
  /// with the recorded still nearest to it.
  var replaysRecordedStills: Bool { get }
}

/// Quality of an in-AR still.
enum StillFrameQuality: Equatable {
  /// `captureHighResolutionFrame` (about 100 ms on the iPhone 12 Pro).
  case fast
  /// Multi-frame processing (`.balanced` prioritization, about 490 ms).
  case balanced
}

extension ARFrameSource {
  func captureHighResolutionFrameForProfile(quality: StillFrameQuality, completion: @escaping (CaptureFrame?, Error?) -> Void) {
    guard quality == .fast else {
      return completion(nil, NSError(domain: "capture3d.encoding-profile", code: 1,
        userInfo: [NSLocalizedDescriptionKey: "Requested frame processing is unavailable"]))
    }
    captureHighResolutionFrame(quality: quality, completion: completion)
  }

  func captureHighResolutionFrame(quality: StillFrameQuality, completion: @escaping (CaptureFrame?, Error?) -> Void) {
    captureHighResolutionFrame(completion: completion)
  }

  var replaysRecordedStills: Bool { false }
}

/// A frame source the capture view drives: the live session host or a
/// recorded session. Control calls come from the main thread.
protocol CaptureSessionSource: ARFrameSource {
  /// Called on the main thread when the source fails.
  var onSessionFailure: ((Error) -> Void)? { get set }
  func start()
  /// The session channel's `init` ([ARSessionLifecycle.initialize]).
  func initialize()
  func pause()
  func resume()
  /// New world frame: epoch increments.
  func restart()
  /// Idempotent; no callback follows.
  func dispose()
}

/// Observers of one frame source, held weakly, with whether each received
/// `didStart` of the current run (only those get frames). Not thread-safe:
/// the owning source guards it with its own lock.
struct FrameObserverSet {
  private final class Entry {
    weak var observer: ARFrameObserver?
    var started = false
    init(_ observer: ARFrameObserver) { self.observer = observer }
  }

  private var entries: [Entry] = []

  /// Adds [observer] (again) as not started; drops released observers.
  mutating func add(_ observer: ARFrameObserver) {
    remove(observer)
    entries.append(Entry(observer))
  }

  mutating func remove(_ observer: ARFrameObserver) {
    entries.removeAll { $0.observer == nil || $0.observer === observer }
  }

  var live: [ARFrameObserver] { entries.compactMap { $0.observer } }

  var started: [ARFrameObserver] { entries.filter { $0.started }.compactMap { $0.observer } }

  func contains(_ observer: ARFrameObserver) -> Bool {
    entries.contains { $0.observer === observer }
  }

  /// The members of [targets] that are still observing.
  func stillObserving(_ targets: [ARFrameObserver]) -> [ARFrameObserver] {
    targets.filter { contains($0) }
  }

  func markStarted(_ observers: [ARFrameObserver]) {
    for entry in entries where observers.contains(where: { $0 === entry.observer }) {
      entry.started = true
    }
  }
}

/// The current frame source of the process (at most one capture view at a
/// time). The host publishes itself on creation and `nil` on dispose;
/// consumers that outlive a view (channels registered with the plugin)
/// follow it here.
final class ARFrameSourceRegistry {
  static let shared = ARFrameSourceRegistry()

  private let lock = NSLock()
  private weak var currentSource: ARFrameSource?
  private var listeners: [UUID: (ARFrameSource?) -> Void] = [:]

  var current: ARFrameSource? {
    lock.lock(); defer { lock.unlock() }
    return currentSource
  }

  func publish(_ source: ARFrameSource?) {
    lock.lock()
    currentSource = source
    let callbacks = Array(listeners.values)
    lock.unlock()
    callbacks.forEach { $0(source) }
  }

  /// Calls [listener] now with the current source and on every change.
  @discardableResult
  func observe(_ listener: @escaping (ARFrameSource?) -> Void) -> UUID {
    let id = UUID()
    lock.lock()
    listeners[id] = listener
    let source = currentSource
    lock.unlock()
    listener(source)
    return id
  }

  func stopObserving(_ id: UUID) {
    lock.lock(); listeners[id] = nil; lock.unlock()
  }
}
