import ARKit
import CoreVideo
import XCTest
@testable import ar_flutter_plugin_2

/// Real-time replay of recordings through the frame source seam: timestamps
/// rebased to the playback clock, decoded video or the placeholder image,
/// depth and points, tracking events and epochs, restart modes, still
/// capture. The fixture plays in 0.4 s.
final class RecordedFrameSourceTests: XCTestCase {
  private var sources: [RecordedFrameSource] = []

  override func tearDown() {
    sources.forEach { $0.dispose() }
    sources = []
    super.tearDown()
  }

  private func makeSource(
    _ directory: URL, restart: RecordedPlaybackRestart = .off, sceneDepth: Bool = true
  ) throws -> RecordedFrameSource {
    var options = ARSessionOptions()
    options.sceneDepth = sceneDepth
    let source = RecordedFrameSource(
      session: try RecordedSession(directory: directory),
      config: RecordedPlaybackConfig(directory: directory, restart: restart, restartGeneration: 0),
      options: options, registry: nil)
    sources.append(source)
    return source
  }

  /// Starts [source] and waits until [observer] saw [frames] frames.
  private func replay(_ source: RecordedFrameSource, _ observer: RecordingFrameObserver, frames: Int,
                      timeout: TimeInterval = 5) {
    let done = expectation(description: "\(frames) frames")
    let lock = NSLock()
    var fulfilled = false
    observer.onEvent = { [weak observer] in
      guard let observer = observer else { return }
      lock.lock(); defer { lock.unlock() }
      if !fulfilled && observer.frames.count >= frames {
        fulfilled = true
        done.fulfill()
      }
    }
    source.addFrameObserver(observer)
    source.start()
    wait(for: [done], timeout: timeout)
  }

  func testReplayRebasesTimestampsAndKeepsTheRecordedSpacing() throws {
    let source = try makeSource(try RecordedSessionFixture.room2URL())
    let observer = RecordingFrameObserver()
    let startedAt = Double(clock_gettime_nsec_np(CLOCK_UPTIME_RAW)) / 1e9
    replay(source, observer, frames: 24)
    let session = source.session
    let frames = observer.frames
    XCTAssertEqual(frames.count, 24)
    let first = try session.frame(at: 0).timestamp
    XCTAssertGreaterThanOrEqual(frames[0].timestamp, startedAt - 0.001, "rebased to the playback clock")
    XCTAssertLessThan(frames[0].timestamp - startedAt, 1)
    for (index, frame) in frames.enumerated() {
      let record = try session.frame(at: index)
      XCTAssertEqual(frame.timestamp - frames[0].timestamp, record.timestamp - first, accuracy: 1e-6)
      XCTAssertEqual(frame.cameraTransform, record.cameraTransform)
      XCTAssertEqual(frame.intrinsics, record.intrinsics)
      XCTAssertEqual(frame.depthData(.raw) != nil, record.depthIndex != nil, "depth at frame \(index)")
      XCTAssertEqual(frame.featurePoints != nil, record.pointsIndex != nil, "points at frame \(index)")
    }
    let elapsed = Double(clock_gettime_nsec_np(CLOCK_UPTIME_RAW)) / 1e9 - startedAt
    XCTAssertGreaterThan(elapsed, (try session.frame(at: 23).timestamp - first) * 0.9, "real time, not as fast as possible")
    XCTAssertEqual(observer.events.first, .start(epoch: 1))
    XCTAssertEqual(observer.events.filter { $0 == .tracking("normal") }.count, 1)
    XCTAssertEqual(source.runConfiguration, CaptureRunConfiguration(depth: .raw))
    XCTAssertTrue(source.deliversSceneDepth)
  }

  /// An observer that blocks the delegate queue never builds a backlog:
  /// at most one replayed frame waits, newer frames replace it and count as
  /// dropped, and the newest frame is delivered once the observer returns.
  func testSlowObserverKeepsAtMostOneUndeliveredFrame() throws {
    let source = try makeSource(try RecordedSessionFixture.room2URL(), restart: .view)
    let observer = RecordingFrameObserver()
    let release = DispatchSemaphore(value: 0)
    let finished = expectation(description: "replay finished")
    source.onPlaybackFinished = { _ in finished.fulfill() }
    let lock = NSLock()
    var blocked = false
    observer.onEvent = { [weak observer] in
      guard let observer = observer else { return }
      lock.lock()
      let first = !blocked && observer.frames.count == 1
      if first { blocked = true }
      lock.unlock()
      // The first frame holds the delegate queue until the replay ended.
      if first { _ = release.wait(timeout: .now() + 5) }
    }
    source.addFrameObserver(observer)
    source.start()
    wait(for: [finished], timeout: 5)
    release.signal()
    let last = try source.session.frame(at: source.session.frameCount - 1)
    let deadline = Date().addingTimeInterval(5)
    while (observer.frames.last as? RecordedCaptureFrame)?.recordedTimestamp != last.timestamp && Date() < deadline {
      usleep(10_000)
    }
    let delivered = observer.frames.count
    XCTAssertEqual((observer.frames.last as? RecordedCaptureFrame)?.recordedTimestamp, last.timestamp, "the newest frame is delivered")
    XCTAssertEqual(delivered, 2, "the blocked frame and the one that waited")
    XCTAssertEqual(source.droppedFrameCount, source.session.frameCount - delivered)
  }

  func testDecodedVideoIsReusedBetweenVideoFrames() throws {
    let source = try makeSource(try RecordedSessionFixture.room2URL())
    let observer = RecordingFrameObserver()
    replay(source, observer, frames: 4)
    let frames = observer.frames
    XCTAssertEqual(CVPixelBufferGetWidth(frames[0].capturedImage), 240, "decoded fixture video")
    XCTAssertTrue(frames[1].capturedImage === frames[0].capturedImage, "frame without video shows the previous image")
    XCTAssertFalse(frames[2].capturedImage === frames[1].capturedImage)
    XCTAssertEqual(frames[0].imageResolution, CGSize(width: 1920, height: 1440))
  }

  func testMissingVideoReplaysThePlaceholderWithPosesDepthAndPoints() throws {
    let source = try makeSource(try RecordedSessionFixture.room2Copy(omitting: ["video_000.mov"]))
    let observer = RecordingFrameObserver()
    replay(source, observer, frames: 24)
    let frames = observer.frames
    XCTAssertEqual(frames.count, 24)
    XCTAssertTrue(frames.allSatisfy { CVPixelBufferGetWidth($0.capturedImage) == 1920 }, "placeholder at the image size")
    XCTAssertTrue(frames.allSatisfy { $0.capturedImage === frames[0].capturedImage })
    XCTAssertEqual(frames.filter { $0.depthData(.raw) != nil }.count, 6)
    XCTAssertEqual(frames.filter { $0.featurePoints != nil }.count, 18)
  }

  func testPointsOnlyViewsGetNoDepth() throws {
    let source = try makeSource(try RecordedSessionFixture.room2URL(), sceneDepth: false)
    let observer = RecordingFrameObserver()
    replay(source, observer, frames: 24)
    XCTAssertTrue(observer.frames.allSatisfy { $0.depthData(.raw) == nil })
    XCTAssertEqual(source.runConfiguration, CaptureRunConfiguration(depth: .none))
  }

  func testRecordedReinitialisationStartsANewEpochAndReplaysTrackingChanges() throws {
    var recording = SyntheticRecording()
    recording.frames = [
      .init(timestamp: 5.00, trackingState: 1, trackingReason: 1),
      .init(timestamp: 5.02),
      .init(timestamp: 5.04, trackingState: 1, trackingReason: 3),
      .init(timestamp: 5.06),
      .init(timestamp: 5.08, trackingState: 1, trackingReason: 1, translation: simd_float3(1, 0, 0)),
      .init(timestamp: 5.10, translation: simd_float3(1, 0, 0)),
    ]
    let source = try makeSource(try recording.write())
    let observer = RecordingFrameObserver()
    replay(source, observer, frames: 6)
    XCTAssertEqual(observer.events, [
      .start(epoch: 1),
      .tracking("limited(initializing)"), .frame,
      .tracking("normal"), .frame,
      .tracking("limited(insufficientFeatures)"), .frame,
      .tracking("normal"), .frame,
      .start(epoch: 2), .tracking("limited(initializing)"), .frame,
      .tracking("normal"), .frame,
    ])
    XCTAssertEqual(source.epoch, 2)
  }

  func testSessionRestartReplaysFromTheFirstFrameInANewEpoch() throws {
    var recording = SyntheticRecording()
    recording.frames = (0..<4).map { .init(timestamp: 1 + Double($0) * 0.02) }
    let source = try makeSource(try recording.write(), restart: .session)
    let observer = RecordingFrameObserver()
    replay(source, observer, frames: 6)
    let starts = observer.events.filter { if case .start = $0 { return true } else { return false } }
    XCTAssertEqual(Array(starts.prefix(2)), [.start(epoch: 1), .start(epoch: 2)])
    let frames = observer.frames
    XCTAssertGreaterThan(frames[4].timestamp, frames[3].timestamp, "the replay clock keeps increasing")
    XCTAssertGreaterThanOrEqual(source.restartCount, 1)
  }

  func testViewRestartReportsTheEndOnceAndStops() throws {
    var recording = SyntheticRecording()
    recording.frames = (0..<3).map { .init(timestamp: 1 + Double($0) * 0.02) }
    let directory = try recording.write()
    let source = try makeSource(directory, restart: .view)
    let finished = expectation(description: "finished")
    var events: [[String: Any]] = []
    source.onPlaybackFinished = { event in
      events.append(event)
      finished.fulfill()
    }
    let observer = RecordingFrameObserver()
    source.addFrameObserver(observer)
    source.start()
    wait(for: [finished], timeout: 5)
    XCTAssertEqual(events.count, 1)
    XCTAssertEqual(events[0]["restart"] as? String, "view")
    XCTAssertEqual(events[0]["restarts"] as? Int, 0)
    XCTAssertEqual(observer.frames.count, 3)
    // Pause and resume at the end neither replays nor reports again.
    source.pause()
    source.resume()
    let settle = expectation(description: "settle")
    DispatchQueue.main.asyncAfter(deadline: .now() + 0.3) { settle.fulfill() }
    wait(for: [settle], timeout: 2)
    XCTAssertEqual(events.count, 1)
    XCTAssertEqual(observer.frames.count, 3)
  }

  func testPauseStopsTheReplayAndResumeContinuesWithoutOlderFrames() throws {
    let source = try makeSource(try RecordedSessionFixture.room2URL())
    let observer = RecordingFrameObserver()
    replay(source, observer, frames: 3)
    source.pause()
    let pausedAt = Double(clock_gettime_nsec_np(CLOCK_UPTIME_RAW)) / 1e9
    let settle = expectation(description: "settle")
    DispatchQueue.main.asyncAfter(deadline: .now() + 0.2) { settle.fulfill() }
    wait(for: [settle], timeout: 2)
    let countWhilePaused = observer.frames.count
    XCTAssertEqual(observer.events.last, .stop)
    let resumed = expectation(description: "resumed")
    observer.onEvent = { [weak observer] in
      if (observer?.frames.count ?? 0) == countWhilePaused + 2 { resumed.fulfill() }
    }
    source.resume()
    wait(for: [resumed], timeout: 5)
    let frames = observer.frames
    XCTAssertGreaterThan(frames[countWhilePaused].timestamp, pausedAt, "frames after resume are newer than the pause")
    XCTAssertEqual(source.epoch, 1, "resume keeps the world frame")
  }

  func testStillCaptureReturnsTheNearestRecordedStill() throws {
    let source = try makeSource(try RecordedSessionFixture.room2URL())
    let observer = RecordingFrameObserver()
    replay(source, observer, frames: 2)
    let captured = expectation(description: "still")
    var still: CaptureFrame?
    source.captureHighResolutionFrame { frame, error in
      XCTAssertNil(error)
      still = frame
      captured.fulfill()
    }
    wait(for: [captured], timeout: 5)
    let frame = try XCTUnwrap(still)
    let recorded = source.session.stills[0]
    XCTAssertEqual(frame.cameraTransform, recorded.cameraTransform, "frames 104-105 are nearest to still 0")
    XCTAssertEqual(frame.imageResolution, CGSize(width: 2016, height: 1512))
    XCTAssertEqual(CVPixelBufferGetWidth(frame.capturedImage), 252)
    let replayed = observer.frames[0]
    let recordedOffset = replayed.timestamp - (try source.session.frame(at: 0).timestamp)
    XCTAssertEqual(frame.timestamp, recorded.timestamp + recordedOffset, accuracy: 1e-6, "still on the playback clock")
  }

  func testAnchorsAddedDuringReplayAppearOnLaterFramesAndClearOnRestart() throws {
    let source = try makeSource(try RecordedSessionFixture.room2URL())
    let observer = RecordingFrameObserver()
    replay(source, observer, frames: 1)
    source.anchorHost.add(anchor: ARAnchor(name: "test", transform: matrix_identity_float4x4))
    let later = expectation(description: "later frame")
    later.assertForOverFulfill = false
    observer.onEvent = { [weak observer] in
      if observer?.frames.last?.anchors.count == 1 { later.fulfill() }
    }
    wait(for: [later], timeout: 5)
    source.restart()
    let cleared = expectation(description: "cleared")
    cleared.assertForOverFulfill = false
    observer.onEvent = { [weak observer] in
      if observer?.events.last == .frame, observer?.frames.last?.anchors.isEmpty == true { cleared.fulfill() }
    }
    wait(for: [cleared], timeout: 5)
    XCTAssertEqual(source.epoch, 2)
  }
}
