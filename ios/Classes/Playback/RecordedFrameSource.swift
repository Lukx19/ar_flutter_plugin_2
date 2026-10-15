import ARKit
import CoreImage
import Foundation

/// Maps recording timestamps to the playback clock: playback time =
/// recorded time + offset. Anchored at the first frame of every run, so a
/// replay starts now and keeps the recording's frame spacing.
struct RecordedPlaybackClock: Equatable {
  private(set) var offset: TimeInterval = 0

  /// The frame recorded at [recorded] plays at [playback].
  mutating func anchor(recorded: TimeInterval, at playback: TimeInterval) {
    offset = playback - recorded
  }

  func playbackTime(_ recorded: TimeInterval) -> TimeInterval { recorded + offset }
}

/// Anchors of a replayed session. A recording has one fixed world frame per
/// epoch, so anchors never move; they are kept until removed or until the
/// world frame changes (restart or re-initialisation).
final class RecordedAnchorStore: CaptureAnchorHost {
  private let lock = NSLock()
  private var anchors: [ARAnchor] = []
  /// Anchors added since the last delivered frame: the only anchor updates
  /// of a replay (a recording moves no anchor).
  private var added: [ARAnchor] = []

  func add(anchor: ARAnchor) {
    lock.lock(); defer { lock.unlock() }
    anchors.removeAll { $0.identifier == anchor.identifier }
    anchors.append(anchor)
    added.append(anchor)
  }

  /// Takes the anchors added since the previous call.
  func takeAdded() -> [ARAnchor] {
    lock.lock(); defer { lock.unlock() }
    guard !added.isEmpty else { return [] }
    let out = added
    added.removeAll(keepingCapacity: true)
    return out
  }

  func remove(anchor: ARAnchor) {
    lock.lock(); defer { lock.unlock() }
    anchors.removeAll { $0.identifier == anchor.identifier }
  }

  func removeAll() {
    lock.lock(); defer { lock.unlock() }
    anchors.removeAll()
    added.removeAll()
  }

  var snapshot: [ARAnchor] {
    lock.lock(); defer { lock.unlock() }
    return anchors
  }
}

/// [ARFrameSource] that replays a recording of the ARKit session recorder
/// in real time instead of running the camera (debug and profile builds).
///
/// A replay queue reads frame records in order, decodes the camera image,
/// depth and feature points of each, waits until the frame's rebased time
/// and hands it to observers on [delegateQueue], like `ARSessionHost` does
/// with live frames. Timestamps are the recording's, rebased to
/// `CLOCK_UPTIME_RAW` at the first frame of every run, so ingest acquire
/// times and frame times share one clock. Tracking state changes of the
/// recording reach observers as `trackingChanged`; a recorded
/// re-initialisation (tracking back to limited/initializing) starts a new
/// epoch, because ARKit's world frame changed there. The recorder writes no
/// interruption events, so none are replayed.
///
/// Control (`start`, `pause`, `resume`, `restart`, `dispose`) follows
/// [ARSessionLifecycle] exactly as the live host: pause stops the replay
/// where it is, resume continues from there, restart and start replay from
/// the first frame in a new epoch. At the end of the recording the
/// [RecordedPlaybackRestart] mode applies: `session` replays again in a new
/// epoch, `view` reports [onPlaybackFinished] so Flutter rebuilds the view,
/// `off` stays at the last frame.
final class RecordedFrameSource: CaptureSessionSource {
  let delegateQueue: DispatchQueue
  let session: RecordedSession
  let config: RecordedPlaybackConfig

  /// Called on the main thread when the source fails; replay never fails
  /// after opening, so this stays unused.
  var onSessionFailure: ((Error) -> Void)?
  /// Called on the main thread once per end of the recording in `view`
  /// mode with the `onArPlaybackFinished` arguments (path, restart, restarts).
  var onPlaybackFinished: (([String: Any]) -> Void)?

  private let options: ARSessionOptions
  private let registry: ARFrameSourceRegistry?
  private let clock: () -> TimeInterval
  private let anchors = RecordedAnchorStore()
  private let replayQueue = DispatchQueue(label: "capture3d.recorded-session.replay", qos: .userInteractive)
  private let stillQueue = DispatchQueue(label: "capture3d.recorded-session.still", qos: .userInitiated)
  private let wake = DispatchSemaphore(value: 0)

  private let lock = NSLock()
  private var lifecycle = ARSessionLifecycle()
  private var observers = FrameObserverSet()
  private var currentConfiguration: CaptureRunConfiguration?
  private var latestFrame: RecordedCaptureFrame?
  private var playbackClock = RecordedPlaybackClock()
  /// Changes whenever the replay must stop or restart; a replay loop or a
  /// queued delivery of an older token does nothing.
  private var replayToken = 0
  /// Next frame record to replay.
  private var position = 0
  private var restarts: Int
  private var finishedReported = false

  // Replay queue only.
  private let video: RecordedVideoReader
  private let depthBuffers = RecordedDepthBuffers()
  private var lastImage: CVPixelBuffer?
  private var placeholder: CVPixelBuffer?
  private var lastReplayedRecord: RecordedFrameRecord?
  private var loggedStart = false

  // Delegate queue only.
  private var lastDeliveredTracking: ARCamera.TrackingState?
  /// The one replayed frame waiting for the delegate queue (under [lock]):
  /// a newer frame replaces it, as live ARKit drops frames its delegate
  /// cannot take, so decoded buffers never pile up behind slow observers.
  private var pendingDelivery: PendingDelivery?
  private var deliveryQueued = false
  private var droppedFrames = 0
  /// Signalled whenever the delegate queue takes the waiting frame.
  private let deliveryTaken = DispatchSemaphore(value: 0)
  /// Since when the observers have been busy with one frame (under [lock]);
  /// nil between deliveries.
  private var deliveringSince: TimeInterval?
  /// How long observers may stay busy with one frame before the waiting
  /// frame is replaced. While the delegate queue merely has not started the
  /// waiting delivery yet (a burst, a loaded host), the replay waits instead,
  /// so frames are dropped only for slow observers, as live ARKit does.
  static let deliveryGrace: TimeInterval = 0.1

  init(
    session: RecordedSession,
    config: RecordedPlaybackConfig,
    options: ARSessionOptions,
    registry: ARFrameSourceRegistry? = .shared,
    clock: @escaping () -> TimeInterval = { Double(clock_gettime_nsec_np(CLOCK_UPTIME_RAW)) / 1e9 }
  ) {
    self.session = session
    self.config = config
    self.options = options
    self.registry = registry
    self.clock = clock
    restarts = config.restartGeneration
    delegateQueue = DispatchQueue(label: "capture3d.recorded-session", qos: .userInteractive)
    video = RecordedVideoReader(directory: session.directory, segments: session.videoSegments)
    video.onSegmentFailed = { file, reason in
      RecordedPlaybackLog.write("video segment failed file=\(file) reason=\(reason) (placeholder image)")
    }
    registry?.publish(self)
  }

  deinit {
    lock.lock()
    _ = lifecycle.dispose()
    replayToken += 1
    lock.unlock()
    wake.signal()
  }

  // MARK: ARFrameSource

  var anchorHost: CaptureAnchorHost { anchors }

  var currentFrame: CaptureFrame? {
    lock.lock(); defer { lock.unlock() }
    return latestFrame
  }

  var runConfiguration: CaptureRunConfiguration? {
    lock.lock(); defer { lock.unlock() }
    return currentConfiguration
  }

  var lifecycleState: ARSessionLifecycleState {
    lock.lock(); defer { lock.unlock() }
    return lifecycle.state
  }

  /// LiDAR depth reaches consumers when the view asked for scene depth and
  /// the recording has depth maps.
  var deliversSceneDepth: Bool { options.sceneDepth && session.depthMapCount > 0 }
  var depthOutputSize: ARDepthOutputSize { options.depthOutputSize }

  var epoch: Int {
    lock.lock(); defer { lock.unlock() }
    return lifecycle.epoch
  }

  /// Replayed frames replaced before the delegate queue took them.
  var droppedFrameCount: Int {
    lock.lock(); defer { lock.unlock() }
    return droppedFrames
  }

  /// Replay restarts so far: earlier view generations plus session restarts.
  var restartCount: Int {
    lock.lock(); defer { lock.unlock() }
    return restarts
  }

  func addFrameObserver(_ observer: ARFrameObserver) {
    lock.lock()
    observers.add(observer)
    let replay = lifecycle.isRunning ? currentConfiguration : nil
    let epoch = lifecycle.epoch
    lock.unlock()
    guard let configuration = replay else { return }
    delegateQueue.async { [weak self, weak observer] in
      guard let self = self, let observer = observer else { return }
      self.lock.lock()
      let observing = self.observers.contains(observer)
      if observing { self.observers.markStarted([observer]) }
      self.lock.unlock()
      if observing { observer.frameSource(self, didStart: configuration, epoch: epoch) }
    }
  }

  func removeFrameObserver(_ observer: ARFrameObserver) {
    lock.lock(); defer { lock.unlock() }
    observers.remove(observer)
  }

  /// The recorded still nearest to the newest replayed frame, decoded from
  /// its JPEG, with the still's own pose and intrinsics and its timestamp on
  /// the playback clock. The recording's stills were taken every few
  /// seconds, so the pose can be up to half that interval away from the
  /// frame on screen.
  func captureHighResolutionFrame(completion: @escaping (CaptureFrame?, Error?) -> Void) {
    lock.lock()
    let recordedNow = latestFrame?.recordedTimestamp ?? session.meta.firstTimestamp
    let playbackClock = self.playbackClock
    let disposed = lifecycle.state == .disposed
    lock.unlock()
    guard !disposed, let still = session.stillNearest(to: recordedNow) else {
      completion(nil, RecordedSessionError.noStill)
      return
    }
    let url = session.url(of: still)
    stillQueue.async {
      guard let image = RecordedStillDecoder.decode(url) else {
        completion(nil, RecordedSessionError.malformed(still.file))
        return
      }
      let frame = RecordedCaptureFrame(
        timestamp: playbackClock.playbackTime(still.timestamp),
        recordedTimestamp: still.timestamp,
        cameraTransform: still.cameraTransform,
        intrinsics: still.intrinsics,
        imageResolution: CGSize(width: still.width, height: still.height),
        trackingState: .normal,
        capturedImage: image,
        depth: nil, featurePoints: nil, lightEstimate: nil, anchors: [])
      completion(frame, nil)
    }
  }

  /// Every quality answers with the recorded still: the recording has no
  /// camera to process more frames.
  func captureHighResolutionFrame(quality: StillFrameQuality, completion: @escaping (CaptureFrame?, Error?) -> Void) {
    captureHighResolutionFrame(completion: completion)
  }

  var replaysRecordedStills: Bool { true }

  /// The world map the recorder saved at the end of the recording.
  func getCurrentWorldMap(completion: @escaping (ARWorldMap?, Error?) -> Void) {
    guard let url = session.worldMapURL else {
      completion(nil, RecordedSessionError.noWorldMap)
      return
    }
    stillQueue.async {
      do {
        let data = try Data(contentsOf: url)
        guard let map = try NSKeyedUnarchiver.unarchivedObject(ofClass: ARWorldMap.self, from: data) else {
          throw RecordedSessionError.noWorldMap
        }
        completion(map, nil)
      } catch {
        completion(nil, error)
      }
    }
  }

  // MARK: Control (main thread)

  func start() { perform { $0.start() } }
  func initialize() { perform { $0.initialize() } }
  func pause() {
    let now = clock()
    perform { $0.pause(now: now) }
  }
  func resume() { perform { $0.resume() } }
  func restart() {
    let now = clock()
    perform { $0.restart(now: now) }
  }

  func dispose() {
    lock.lock()
    let alreadyDisposed = lifecycle.state == .disposed
    let command = lifecycle.dispose()
    replayToken += 1
    lock.unlock()
    wake.signal()
    if alreadyDisposed { return }
    if command == .pause { notifyStopped() }
    replayQueue.async { [video] in video.close() }
    if let registry = registry, registry.current === self {
      registry.publish(nil)
    }
  }

  private func perform(_ transition: (inout ARSessionLifecycle) -> ARSessionCommand) {
    lock.lock()
    let command = transition(&lifecycle)
    let epoch = lifecycle.epoch
    switch command {
    case .none:
      lock.unlock()
      return
    case .pause:
      replayToken += 1
      lock.unlock()
      wake.signal()
      notifyStopped()
    case .run(let resetTracking):
      let configuration = CaptureRunConfiguration(depth: deliversSceneDepth ? .raw : .none)
      currentConfiguration = configuration
      replayToken += 1
      let token = replayToken
      if resetTracking {
        position = 0
        finishedReported = false
      }
      let targets = observers.live
      lock.unlock()
      wake.signal()
      if resetTracking { anchors.removeAll() }
      // Enqueued before the replay starts, so it precedes every frame of
      // this run on the serial delegate queue.
      enqueueStart(targets, configuration: configuration, epoch: epoch)
      replayQueue.async { [weak self] in self?.replay(token: token, fromStart: resetTracking) }
    }
  }

  private func enqueueStart(_ targets: [ARFrameObserver], configuration: CaptureRunConfiguration, epoch: Int) {
    delegateQueue.async { [weak self] in
      guard let self = self else { return }
      self.lock.lock()
      let started = self.observers.stillObserving(targets)
      self.observers.markStarted(started)
      self.lock.unlock()
      self.lastDeliveredTracking = nil
      started.forEach { $0.frameSource(self, didStart: configuration, epoch: epoch) }
    }
  }

  private func notifyStopped() {
    lock.lock()
    let targets = observers.live
    lock.unlock()
    delegateQueue.async { [weak self] in
      guard let self = self else { return }
      self.lock.lock()
      let current = self.observers.stillObserving(targets)
      self.lock.unlock()
      current.forEach { $0.frameSourceDidStop(self) }
    }
  }

  private func isCurrent(_ token: Int) -> Bool {
    lock.lock(); defer { lock.unlock() }
    return token == replayToken
  }

  // MARK: Replay (replay queue)

  private func replay(token: Int, fromStart: Bool) {
    if fromStart { lastReplayedRecord = nil }
    if !loggedStart {
      loggedStart = true
      RecordedPlaybackLog.write(
        (config.restartGeneration == 0 ? "started" : "restarted strategy=view restarts=\(config.restartGeneration)")
          + " path=\(session.directory.path) restart=\(config.restart.rawValue) frames=\(session.frameCount) "
          + "video=\(session.hasVideo ? "decoded" : "placeholder") depth=\(deliversSceneDepth)")
    }
    var clockAnchored = false
    while true {
      lock.lock()
      guard token == replayToken else { lock.unlock(); return }
      let index = position
      lock.unlock()
      if index >= session.frameCount {
        guard finishReplay(token: token) else { return }
        clockAnchored = false
        continue
      }
      let record: RecordedFrameRecord
      do {
        record = try session.frame(at: index)
      } catch {
        RecordedPlaybackLog.write("failed frame=\(index) error=\(error)")
        advance(token: token, past: index)
        continue
      }
      if !clockAnchored {
        let now = clock()
        lock.lock()
        // At least the lifecycle fence: never older than the pause or restart.
        playbackClock.anchor(recorded: record.timestamp, at: max(now, lifecycle.frameFence + 1e-6))
        lock.unlock()
        clockAnchored = true
      }
      let prepared = prepare(record)
      lock.lock()
      let due = playbackClock.playbackTime(record.timestamp)
      lock.unlock()
      guard waitUntil(due, token: token) else { return }
      let reinitialises = record.isInitializing && lastReplayedRecord.map { !$0.isInitializing } ?? false
      lastReplayedRecord = record
      deliver(prepared, timestamp: due, reinitialises: reinitialises, token: token)
      advance(token: token, past: index)
    }
  }

  private struct PreparedFrame {
    let record: RecordedFrameRecord
    let image: CVPixelBuffer
    let depth: CaptureDepthData?
    let featurePoints: CaptureFeaturePoints?
  }

  private func prepare(_ record: RecordedFrameRecord) -> PreparedFrame {
    if let videoIndex = record.videoIndex, let image = video.image(videoIndex: videoIndex) {
      lastImage = image
    }
    let image: CVPixelBuffer
    if let last = lastImage {
      image = last
    } else {
      if placeholder == nil {
        placeholder = RecordedPlaceholderImage.make(width: record.imageWidth, height: record.imageHeight)
          ?? RecordedPlaceholderImage.make(width: 64, height: 48)
      }
      image = placeholder!
    }
    var depth: CaptureDepthData?
    if deliversSceneDepth, let depthIndex = record.depthIndex {
      do {
        depth = depthBuffers.make(try session.depthMap(depthIndex))
      } catch {
        RecordedPlaybackLog.write("failed depth=\(depthIndex) error=\(error)")
      }
    }
    let points = record.pointsIndex.flatMap { try? session.featurePoints($0) }
    return PreparedFrame(record: record, image: image, depth: depth, featurePoints: points)
  }

  /// Sleeps until [due] on the playback clock; false when the replay was
  /// stopped meanwhile.
  private func waitUntil(_ due: TimeInterval, token: Int) -> Bool {
    while true {
      guard isCurrent(token) else { return false }
      let remaining = due - clock()
      if remaining <= 0 { return true }
      _ = wake.wait(timeout: .now() + min(remaining, 0.05))
    }
  }

  private func advance(token: Int, past index: Int) {
    lock.lock(); defer { lock.unlock() }
    if token == replayToken && position == index { position = index + 1 }
  }

  private struct PendingDelivery {
    var prepared: PreparedFrame
    var timestamp: TimeInterval
    var reinitialises: Bool
    var token: Int
  }

  /// Hands [prepared] to the delegate queue, keeping at most one frame
  /// undelivered: while the previous frame still waits, the replay waits for
  /// the delegate queue to take it, unless the observers have been busy with
  /// an earlier frame for [deliveryGrace]; then the newer frame replaces the
  /// waiting one (a re-initialisation it carried is kept) and the replaced
  /// frame counts as dropped.
  private func deliver(_ prepared: PreparedFrame, timestamp: TimeInterval, reinitialises: Bool, token: Int) {
    lock.lock()
    while pendingDelivery != nil, token == replayToken {
      let busyFor = deliveringSince.map { clock() - $0 }
      if let busy = busyFor, busy >= Self.deliveryGrace { break }
      lock.unlock()
      _ = deliveryTaken.wait(timeout: .now() + Self.deliveryGrace - (busyFor ?? 0))
      lock.lock()
    }
    var next = PendingDelivery(prepared: prepared, timestamp: timestamp, reinitialises: reinitialises, token: token)
    if let waiting = pendingDelivery {
      droppedFrames += 1
      if waiting.token == token && waiting.reinitialises { next.reinitialises = true }
      if droppedFrames == 1 || droppedFrames % 100 == 0 {
        RecordedPlaybackLog.write("dropped frame=\(waiting.prepared.record.frameIndex) dropped=\(droppedFrames) (observers behind the replay)")
      }
    }
    pendingDelivery = next
    let enqueue = !deliveryQueued
    deliveryQueued = true
    lock.unlock()
    guard enqueue else { return }
    delegateQueue.async { [weak self] in self?.deliverPending() }
  }

  /// Delegate queue: delivers the frame waiting at this moment.
  private func deliverPending() {
    lock.lock()
    let next = pendingDelivery
    pendingDelivery = nil
    deliveryQueued = false
    deliveringSince = next == nil ? nil : clock()
    lock.unlock()
    deliveryTaken.signal()
    guard let delivery = next else { return }
    deliverNow(delivery.prepared, timestamp: delivery.timestamp, reinitialises: delivery.reinitialises, token: delivery.token)
    lock.lock()
    deliveringSince = nil
    lock.unlock()
    deliveryTaken.signal()
  }

  private func deliverNow(_ prepared: PreparedFrame, timestamp: TimeInterval, reinitialises: Bool, token: Int) {
    do {
      let record = prepared.record
      self.lock.lock()
      guard token == self.replayToken else { self.lock.unlock(); return }
      var restartTargets: [ARFrameObserver] = []
      var restartEpoch = 0
      let configuration = self.currentConfiguration ?? CaptureRunConfiguration(depth: .none)
      if reinitialises {
        // ARKit re-initialised its world frame here: a new epoch, as a
        // restart of a live session. The fence stays below this frame.
        _ = self.lifecycle.restart(now: timestamp - 1e-6)
        restartEpoch = self.lifecycle.epoch
        restartTargets = self.observers.live
        self.observers.markStarted(restartTargets)
      }
      guard self.lifecycle.admitFrame(timestamp: timestamp) else { self.lock.unlock(); return }
      self.lock.unlock()
      if reinitialises {
        self.anchors.removeAll()
        self.lastDeliveredTracking = nil
        RecordedPlaybackLog.write("re-initialised frame=\(record.frameIndex) epoch=\(restartEpoch)")
        restartTargets.forEach { $0.frameSource(self, didStart: configuration, epoch: restartEpoch) }
      }
      let frame = RecordedCaptureFrame(
        timestamp: timestamp,
        recordedTimestamp: record.timestamp,
        cameraTransform: record.cameraTransform,
        intrinsics: record.intrinsics,
        imageResolution: CGSize(width: record.imageWidth, height: record.imageHeight),
        trackingState: record.trackingState,
        capturedImage: prepared.image,
        depth: prepared.depth,
        featurePoints: prepared.featurePoints,
        lightEstimate: record.lightEstimate,
        anchors: self.anchors.snapshot,
        worldMappingStatus: ARFrame.WorldMappingStatus(rawValue: Int(record.worldMappingStatus)) ?? .notAvailable)
      self.lock.lock()
      self.latestFrame = frame
      let started = self.observers.started
      self.lock.unlock()
      if self.lastDeliveredTracking != frame.trackingState {
        self.lastDeliveredTracking = frame.trackingState
        started.forEach { $0.frameSource(self, trackingChanged: frame.trackingState) }
      }
      let added = self.anchors.takeAdded()
      if !added.isEmpty { started.forEach { $0.frameSource(self, didUpdateAnchors: added) } }
      started.forEach { $0.frameSource(self, didUpdate: frame) }
    }
  }

  /// End of the recording. Returns true when the replay continues from the
  /// first frame (`session` mode).
  private func finishReplay(token: Int) -> Bool {
    lock.lock()
    guard token == replayToken else { lock.unlock(); return false }
    let restart = config.restart
    if restart == .session {
      restarts += 1
      position = 0
      _ = lifecycle.restart(now: clock())
      let epoch = lifecycle.epoch
      let configuration = currentConfiguration ?? CaptureRunConfiguration(depth: .none)
      let targets = observers.live
      let count = restarts
      lock.unlock()
      anchors.removeAll()
      lastReplayedRecord = nil
      RecordedPlaybackLog.write("restarted path=\(session.directory.path) strategy=session restarts=\(count)")
      enqueueStart(targets, configuration: configuration, epoch: epoch)
      return true
    }
    let report = !finishedReported
    finishedReported = true
    let count = restarts
    let dropped = droppedFrames
    lock.unlock()
    guard report else { return false }
    RecordedPlaybackLog.write("finished path=\(session.directory.path) restarts=\(count) next=\(restart.rawValue) dropped=\(dropped)")
    if restart == .view {
      let event: [String: Any] = ["path": session.directory.path, "restart": restart.rawValue, "restarts": count]
      DispatchQueue.main.async { [weak self] in self?.onPlaybackFinished?(event) }
    }
    return false
  }
}

/// Decodes a recorded still JPEG into a bi-planar full-range YCbCr buffer,
/// the format of live `ARFrame.capturedImage`.
enum RecordedStillDecoder {
  private static let context = CIContext(options: [.cacheIntermediates: false])

  static func decode(_ url: URL) -> CVPixelBuffer? {
    guard let image = CIImage(contentsOf: url) else { return nil }
    let width = Int(image.extent.width)
    let height = Int(image.extent.height)
    guard width > 0, height > 0 else { return nil }
    var buffer: CVPixelBuffer?
    let attributes: [String: Any] = [
      kCVPixelBufferIOSurfacePropertiesKey as String: [String: Any](),
      kCVPixelBufferMetalCompatibilityKey as String: true,
    ]
    guard CVPixelBufferCreate(
      kCFAllocatorDefault, width, height, kCVPixelFormatType_420YpCbCr8BiPlanarFullRange,
      attributes as CFDictionary, &buffer) == kCVReturnSuccess, let output = buffer else { return nil }
    context.render(image, to: output)
    return output
  }
}
