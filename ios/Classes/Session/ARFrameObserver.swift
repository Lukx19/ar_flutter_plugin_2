import ARKit
import Foundation

/// Seam between the AR session host (`Session/`) and frame consumers such as
/// the visibility grid ingest shell (`VisibilityGrid/`).
///
/// The host owns the one `ARSession` of the capture view and calls observers
/// on its session delegate queue. Observers copy what they need and return
/// quickly; they never retain an `ARFrame` (ARKit stops delivering frames when
/// too many are retained).
protocol ARFrameObserver: AnyObject {
  /// Every camera frame, on the session delegate queue.
  func frameSource(_ source: ARFrameSource, didUpdate frame: ARFrame)
  /// The session (re)started with this configuration. [epoch] increments on
  /// every run with `.resetTracking`, so poses of different epochs never mix.
  func frameSource(_ source: ARFrameSource, didStart configuration: ARConfiguration, epoch: Int)
  func frameSource(_ source: ARFrameSource, trackingChanged camera: ARCamera)
  func frameSourceWasInterrupted(_ source: ARFrameSource)
  func frameSourceInterruptionEnded(_ source: ARFrameSource)
  /// The session was paused or the view disposed; no frames follow until the next start.
  func frameSourceDidStop(_ source: ARFrameSource)
}

extension ARFrameObserver {
  func frameSource(_ source: ARFrameSource, didStart configuration: ARConfiguration, epoch: Int) {}
  func frameSource(_ source: ARFrameSource, trackingChanged camera: ARCamera) {}
  func frameSourceWasInterrupted(_ source: ARFrameSource) {}
  func frameSourceInterruptionEnded(_ source: ARFrameSource) {}
  func frameSourceDidStop(_ source: ARFrameSource) {}
}

/// What the session host exposes to consumers.
protocol ARFrameSource: AnyObject {
  var session: ARSession { get }
  /// True when the configuration delivers LiDAR `sceneDepth` (sets the
  /// LiDAR-class source flag, bit 2, in ingest headers).
  var deliversSceneDepth: Bool { get }
  var epoch: Int { get }
  func addFrameObserver(_ observer: ARFrameObserver)
  func removeFrameObserver(_ observer: ARFrameObserver)
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
