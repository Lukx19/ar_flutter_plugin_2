import Foundation

/// Hosts marker detection in the visibility grid shell (the iOS twin of the
/// Android `GridMarkerShellTaps`): the live frame tap on the current frame
/// source and tie points of every durable photo, both following the shell's
/// marker detection settings and offering into its ingest queue. Installing
/// it tells the control channel that a detector is linked, so set marker
/// detection on is accepted instead of `MARKER_DETECTOR_UNAVAILABLE` (300).
final class GridMarkerDetection {
  static let shared = GridMarkerDetection()

  private let lock = NSLock()
  private var tap: MarkerFrameTap?
  private var tiePoints: MarkerTiePointProducer?
  private var observation: UUID?
  private weak var source: ARFrameSource?

  /// Idempotent; does nothing when the library cannot detect.
  func install(registry: VisibilityGridShellRegistry = .shared) {
    lock.lock()
    guard tap == nil, NativeMarkerDetector.available else { lock.unlock(); return }
    let settings = { [weak registry] in registry?.activeShell?.markerDetectionSettings }
    let sink = { [weak registry] in registry?.ingestSink }
    let clock = UptimeRawClock()
    let created = MarkerFrameTap(makeDetector: { NativeMarkerDetector() }, settings: settings, sink: sink, clock: clock)
    let producer = MarkerTiePointProducer(
      makeDetector: { NativeMarkerDetector() }, settings: settings, sink: sink, clock: clock)
    tap = created
    tiePoints = producer
    lock.unlock()
    registry.markerDetectorAvailable = { true }
    registry.addDurablePhotoListener(producer)
    let id = ARFrameSourceRegistry.shared.observe { [weak self] next in self?.follow(next) }
    lock.lock(); observation = id; lock.unlock()
  }

  /// The live tap and the tie point producer, for diagnostics and the capture layer's image seam.
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
  }
}
