import Foundation

/// Hosts marker detection in the visibility grid shell (the iOS twin of the
/// Android `GridMarkerShellTaps`): the live frame tap on the current frame
/// source and tie points of every durable photo, both following the shell's
/// marker detection settings and offering into its ingest queue. Installing
/// it tells the control channel that a detector is linked, so set marker
/// detection on is accepted instead of `MARKER_DETECTOR_UNAVAILABLE` (300).
///
/// `PluginEngineServices` installs it with the grid shell registration and
/// uninstalls it when no engine owns the shell any more.
final class GridMarkerDetection {
  static let shared = GridMarkerDetection()

  private let lock = NSLock()
  private let detectorAvailable: () -> Bool
  private var tap: MarkerFrameTap?
  private var tiePoints: MarkerTiePointProducer?
  private var observation: UUID?
  private weak var source: ARFrameSource?
  private weak var registry: VisibilityGridShellRegistry?

  init(detectorAvailable: @escaping () -> Bool = { NativeMarkerDetector.available }) {
    self.detectorAvailable = detectorAvailable
  }

  /// Installs into [registry]; idempotent for the same registry, moves from
  /// another one. Does nothing when the library cannot detect.
  func install(registry: VisibilityGridShellRegistry = .shared) {
    guard detectorAvailable() else { return }
    lock.lock()
    let installedHere = tap != nil && self.registry === registry
    lock.unlock()
    if installedHere { return }
    uninstall()
    let settings = { [weak registry] in registry?.activeShell?.markerDetectionSettings }
    let sink = { [weak registry] in registry?.ingestSink }
    let clock = UptimeRawClock()
    let created = MarkerFrameTap(makeDetector: { NativeMarkerDetector() }, settings: settings, sink: sink, clock: clock)
    let producer = MarkerTiePointProducer(
      makeDetector: { NativeMarkerDetector() }, settings: settings, sink: sink, clock: clock)
    lock.lock()
    tap = created
    tiePoints = producer
    self.registry = registry
    lock.unlock()
    registry.markerDetectorAvailable = { true }
    registry.addDurablePhotoListener(producer)
    let id = ARFrameSourceRegistry.shared.observe { [weak self] next in self?.follow(next) }
    lock.lock()
    if tap === created {
      observation = id
      lock.unlock()
    } else {
      lock.unlock()
      ARFrameSourceRegistry.shared.stopObserving(id)
    }
  }

  /// Removes the tap from the frame source and the tie point producer from
  /// the registry; set marker detection on is refused again. Idempotent.
  func uninstall() {
    lock.lock()
    let oldTap = tap, oldProducer = tiePoints, id = observation, oldSource = source, oldRegistry = registry
    tap = nil
    tiePoints = nil
    observation = nil
    source = nil
    registry = nil
    lock.unlock()
    if let id { ARFrameSourceRegistry.shared.stopObserving(id) }
    if let oldTap { oldSource?.removeFrameObserver(oldTap) }
    if let oldProducer { oldRegistry?.removeDurablePhotoListener(oldProducer) }
    oldRegistry?.markerDetectorAvailable = { false }
  }

  /// True while installed into [registry].
  func isInstalled(in registry: VisibilityGridShellRegistry) -> Bool {
    lock.lock(); defer { lock.unlock() }
    return tap != nil && self.registry === registry
  }

  /// The live tap and the tie point producer, for diagnostics.
  var frameTap: MarkerFrameTap? { lock.lock(); defer { lock.unlock() }; return tap }
  var tiePointProducer: MarkerTiePointProducer? { lock.lock(); defer { lock.unlock() }; return tiePoints }

  private func follow(_ next: ARFrameSource?) {
    lock.lock()
    guard let t = tap, source !== next else { lock.unlock(); return }
    let previous = source
    source = next
    lock.unlock()
    previous?.removeFrameObserver(t)
    next?.addFrameObserver(t)
    // An uninstall that raced this call already removed from the old source only.
    lock.lock()
    let stillInstalled = tap === t
    lock.unlock()
    if !stillInstalled { next?.removeFrameObserver(t) }
  }
}
