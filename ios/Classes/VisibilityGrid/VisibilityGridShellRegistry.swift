import ARKit
import Flutter
import Foundation

/// Process-wide registration point of the iOS visibility grid ingest shell
/// (contract 2.12 seams), the Swift twin of the Android
/// `VisibilityGridShellRegistry`. `SwiftArFlutterPlugin.register(with:)`
/// calls only [register(messenger:)]:
///
/// - ingest (`capture3d/visibility_grid/ingest`) and pose tracking
///   (`capture3d/visibility_grid/pose_tracking`) run on background task
///   queues (`makeBackgroundTaskQueue`), pulled by the grid isolate through
///   `BackgroundIsolateBinaryMessenger`; control
///   (`capture3d/visibility_grid/control`) runs on the platform thread.
/// - Frames come from the current `ARFrameSource` through
///   `ARFrameSourceRegistry.shared.observe` and an `ARFrameObserver`; no
///   `ARFrame` is retained.
/// - The capture layer reports durable photos to [photoSavedEmitter]; other
///   producers (marker detections) offer messages to [ingestSink].
///
/// Everything is inert until the UI isolate sends a control start message.
final class VisibilityGridShellRegistry {
  static let shared = VisibilityGridShellRegistry()

  private let lock = NSLock()
  private var shell: VisibilityGridIngestShell?
  private var poseTracking: AnchorPoseUpdateProducer?
  private var anchorBackend: ARKitAnchorBackend?
  private var channels: [FlutterBasicMessageChannel] = []
  private var observer: GridFrameObserver?
  private var sourceObservation: UUID?
  private weak var source: ARFrameSource?
  private var hadSource = false
  private var extraPhotoListeners: [DurablePhotoListener] = []
  private let idleQueue = DispatchQueue(label: "capture3d.grid.ingest.idle", qos: .userInitiated)
  private let clock = UptimeRawClock()

  /// True when the marker detector library is linked (set by marker detection).
  var markerDetectorAvailable: () -> Bool = { false }

  /// Registers the channels of one engine; the latest engine owns the shell.
  func register(messenger: FlutterBinaryMessenger) {
    lock.lock()
    unregisterLocked()
    let created = VisibilityGridIngestShell(clock: clock, recorder: Self.makeRecorder())
    shell = created
    let pull = created.pull
    pull.scheduleIdleCheck = { [weak pull, idleQueue] delayNs in
      idleQueue.asyncAfter(deadline: .now() + .nanoseconds(Int(delayNs))) { pull?.tick() }
    }
    let backend = ARKitAnchorBackend()
    anchorBackend = backend
    let tracking = AnchorPoseUpdateProducer(backend: backend, clock: clock)
    tracking.tracker.jumpListener = { [weak created] frameNs, mm in
      created?.onAnchorJump(frameNs: frameNs, jumpMillimetres: mm)
    }
    poseTracking = tracking
    created.photos.durableListeners = { [weak self] in self?.photoListeners() ?? [] }

    let codec = FlutterBinaryCodec.sharedInstance()
    let ingestQueue = messenger.makeBackgroundTaskQueue?()
    let ingest = FlutterBasicMessageChannel(
      name: VisibilityGridChannels.ingest, binaryMessenger: messenger, codec: codec, taskQueue: ingestQueue)
    ingest.setMessageHandler { message, reply in
      pull.onPull(message as? Data) { reply($0) }
    }
    let control = FlutterBasicMessageChannel(name: VisibilityGridChannels.control, binaryMessenger: messenger, codec: codec)
    let controlHandler = GridShellControlHandler(
      shell: { [weak created] in created },
      markerDetectorAvailable: { [weak self] in self?.markerDetectorAvailable() ?? false }
    )
    control.setMessageHandler { message, reply in
      controlHandler.onMessage(message as? Data) { reply($0) }
    }
    let poseQueue = messenger.makeBackgroundTaskQueue?()
    let pose = FlutterBasicMessageChannel(
      name: VisibilityGridChannels.poseTracking, binaryMessenger: messenger, codec: codec, taskQueue: poseQueue)
    pose.setMessageHandler { [weak tracking] message, reply in
      guard let tracking else {
        reply(ChannelStatusReply.data(AnchorPoseUpdateProducer.statusQueryRefused))
        return
      }
      tracking.onQuery(message as? Data) { reply($0) }
    }
    channels = [ingest, control, pose]

    let frameObserver = GridFrameObserver(shell: created, poseTracking: tracking, backend: backend)
    observer = frameObserver
    lock.unlock()
    // Outside the lock: observe calls back at once with the current source.
    let observation = ARFrameSourceRegistry.shared.observe { [weak self] source in
      self?.follow(source)
    }
    lock.lock()
    if shell === created {
      sourceObservation = observation
    } else {
      ARFrameSourceRegistry.shared.stopObserving(observation)
    }
    lock.unlock()
  }

  /// Unregisters the channels and releases the shell (engine detached).
  func unregister() {
    lock.lock(); defer { lock.unlock() }
    unregisterLocked()
  }

  private func unregisterLocked() {
    for channel in channels { channel.setMessageHandler(nil) }
    channels = []
    if let id = sourceObservation { ARFrameSourceRegistry.shared.stopObserving(id) }
    sourceObservation = nil
    if let o = observer { source?.removeFrameObserver(o) }
    observer = nil
    source = nil
    hadSource = false
    poseTracking?.close()
    poseTracking = nil
    anchorBackend = nil
    shell?.close()
    shell = nil
  }

  /// Follows the current frame source (any thread).
  private func follow(_ next: ARFrameSource?) {
    lock.lock()
    guard let o = observer, let s = shell else { lock.unlock(); return }
    let previous = source
    if previous === next { lock.unlock(); return }
    if let p = previous { p.removeFrameObserver(o) }
    source = next
    let replaces = hadSource
    if next != nil { hadSource = true }
    lock.unlock()
    guard let n = next else {
      s.onSessionStopped()
      return
    }
    let configuration = n.session.configuration
    s.onSourceAttached(
      sourceEpoch: configuration == nil ? nil : n.epoch,
      depthMode: ingestDepthMode(configuration, deliversSceneDepth: n.deliversSceneDepth),
      replacesSession: replaces
    )
    n.addFrameObserver(o)
  }

  private func photoListeners() -> [DurablePhotoListener] {
    lock.lock(); defer { lock.unlock() }
    var listeners: [DurablePhotoListener] = []
    if let p = poseTracking { listeners.append(p) }
    listeners.append(contentsOf: extraPhotoListeners)
    return listeners
  }

  /// Adds a listener called for every durable photo after its event (for example tie points).
  func addDurablePhotoListener(_ listener: DurablePhotoListener) {
    lock.lock(); defer { lock.unlock() }
    if !extraPhotoListeners.contains(where: { $0 === listener }) { extraPhotoListeners.append(listener) }
  }

  func removeDurablePhotoListener(_ listener: DurablePhotoListener) {
    lock.lock(); defer { lock.unlock() }
    extraPhotoListeners.removeAll { $0 === listener }
  }

  /// Seam for the capture layer: one call per committed durable photo.
  var photoSavedEmitter: PhotoSavedEmitter? {
    lock.lock(); defer { lock.unlock() }
    return shell?.photos
  }

  /// Queue for producers of other ingest kinds (for example marker detections).
  var ingestSink: IngestMessageSink? {
    lock.lock(); defer { lock.unlock() }
    return shell?.pull
  }

  /// The running shell, for marker detection settings and diagnostics.
  var activeShell: VisibilityGridIngestShell? {
    lock.lock(); defer { lock.unlock() }
    return shell
  }

  /// Debug builds record replied messages (`C3IR`) under Application Support.
  private static func makeRecorder() -> IngestRecorder? {
    #if DEBUG
    guard let support = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask).first else {
      return nil
    }
    return IngestRecorder(
      directory: support.appendingPathComponent("visibility_grid/recordings", isDirectory: true),
      deviceModel: deviceModelIdentifier()
    )
    #else
    return nil
    #endif
  }

  /// Hardware model identifier, for example `iPhone13,3`.
  static func deviceModelIdentifier() -> String {
    var info = utsname()
    uname(&info)
    return withUnsafeBytes(of: &info.machine) { raw in
      String(decoding: raw.prefix { $0 != 0 }, as: UTF8.self)
    }
  }
}

/// Bridges the session host's frame observer seam to the shell and the anchor tracker.
private final class GridFrameObserver: ARFrameObserver {
  private let shell: VisibilityGridIngestShell
  private let poseTracking: AnchorPoseUpdateProducer
  private let backend: ARKitAnchorBackend

  init(shell: VisibilityGridIngestShell, poseTracking: AnchorPoseUpdateProducer, backend: ARKitAnchorBackend) {
    self.shell = shell
    self.poseTracking = poseTracking
    self.backend = backend
  }

  func frameSource(_ source: ARFrameSource, didUpdate frame: ARFrame) {
    shell.process(ARKitIngestFrame(frame: frame))
    guard poseTracking.tracker.armed else { return }
    backend.cameraTracking = anchorTracking(frame.camera.trackingState)
    poseTracking.onFrame(session: source.session, frame: ARKitAnchorFrame(frame))
  }

  func frameSource(_ source: ARFrameSource, didStart configuration: ARConfiguration, epoch: Int) {
    shell.onSessionStarted(
      sourceEpoch: epoch, depthMode: ingestDepthMode(configuration, deliversSceneDepth: source.deliversSceneDepth))
  }

  func frameSourceInterruptionEnded(_ source: ARFrameSource) {
    shell.onSessionInterruptionEnded()
  }

  func frameSourceDidStop(_ source: ARFrameSource) {
    shell.onSessionStopped()
  }
}
