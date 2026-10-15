import ARKit
import XCTest
@testable import ar_flutter_plugin_2

/// Pause/resume state machine of the capture session and the host that
/// applies it, with a fake session driver (no camera needed).
final class ARSessionLifecycleTests: XCTestCase {
  func testStartRunsWithResetAndIncrementsEpoch() {
    var lifecycle = ARSessionLifecycle()
    XCTAssertEqual(lifecycle.start(), .run(resetTracking: true))
    XCTAssertEqual(lifecycle.state, .running)
    XCTAssertEqual(lifecycle.epoch, 1)
    XCTAssertEqual(lifecycle.start(), .none, "already running")
    XCTAssertEqual(lifecycle.epoch, 1)
  }

  func testPauseAndResumeKeepTheEpoch() {
    var lifecycle = ARSessionLifecycle()
    _ = lifecycle.start()
    XCTAssertEqual(lifecycle.pause(now: 1), .pause)
    XCTAssertEqual(lifecycle.pause(now: 2), .none, "pause is idempotent")
    XCTAssertEqual(lifecycle.resume(), .run(resetTracking: false))
    XCTAssertEqual(lifecycle.resume(), .none)
    XCTAssertEqual(lifecycle.epoch, 1)
  }

  func testInitRunsOnlyANeverStartedSessionAndKeepsAPausedOnePaused() {
    var lifecycle = ARSessionLifecycle()
    XCTAssertEqual(lifecycle.initialize(), .run(resetTracking: true))
    XCTAssertEqual(lifecycle.initialize(), .none, "running: configuration only")
    _ = lifecycle.pause(now: 1)
    XCTAssertEqual(lifecycle.initialize(), .none, "Android init does not resume a Flutter pause")
    XCTAssertEqual(lifecycle.state, .paused)
    XCTAssertEqual(lifecycle.epoch, 1, "the world frame is kept")
    XCTAssertEqual(lifecycle.resume(), .run(resetTracking: false))
    _ = lifecycle.dispose()
    XCTAssertEqual(lifecycle.initialize(), .none)
  }

  func testResumeOfANeverStartedSessionStartsIt() {
    var lifecycle = ARSessionLifecycle()
    XCTAssertEqual(lifecycle.resume(), .run(resetTracking: true))
    XCTAssertEqual(lifecycle.epoch, 1)
  }

  func testRestartAlwaysResetsAndIncrementsEpoch() {
    var lifecycle = ARSessionLifecycle()
    _ = lifecycle.start()
    XCTAssertEqual(lifecycle.restart(now: 1), .run(resetTracking: true))
    XCTAssertEqual(lifecycle.epoch, 2)
    _ = lifecycle.pause(now: 2)
    XCTAssertEqual(lifecycle.restart(now: 3), .run(resetTracking: true))
    XCTAssertEqual(lifecycle.epoch, 3)
    XCTAssertEqual(lifecycle.state, .running)
  }

  func testDisposeIsTerminalAndIdempotent() {
    var lifecycle = ARSessionLifecycle()
    _ = lifecycle.start()
    XCTAssertEqual(lifecycle.dispose(), .pause)
    XCTAssertEqual(lifecycle.dispose(), .none)
    XCTAssertEqual(lifecycle.start(), .none)
    XCTAssertEqual(lifecycle.resume(), .none)
    XCTAssertEqual(lifecycle.restart(now: 1), .none)
    XCTAssertEqual(lifecycle.state, .disposed)

    var paused = ARSessionLifecycle()
    _ = paused.start()
    _ = paused.pause(now: 1)
    XCTAssertEqual(paused.dispose(), .none, "a paused session is not paused twice")
  }

  func testLateFramesAreRefused() {
    var lifecycle = ARSessionLifecycle()
    XCTAssertFalse(lifecycle.admitFrame(timestamp: 1), "before start")
    _ = lifecycle.start()
    XCTAssertTrue(lifecycle.admitFrame(timestamp: 10))
    XCTAssertFalse(lifecycle.admitFrame(timestamp: 10), "duplicate")
    XCTAssertTrue(lifecycle.admitFrame(timestamp: 11))
    _ = lifecycle.pause(now: 12.5)
    XCTAssertFalse(lifecycle.admitFrame(timestamp: 12), "queued before pause, delivered after")
    XCTAssertFalse(lifecycle.admitsSessionCallbacks)
    _ = lifecycle.resume()
    XCTAssertFalse(lifecycle.admitFrame(timestamp: 12), "captured before the pause, delivered after resume")
    XCTAssertTrue(lifecycle.admitFrame(timestamp: 13))
    _ = lifecycle.restart(now: 20)
    XCTAssertFalse(lifecycle.admitFrame(timestamp: 19), "captured before a reset, delivered after it")
    XCTAssertTrue(lifecycle.admitFrame(timestamp: 21))
    _ = lifecycle.dispose()
    XCTAssertFalse(lifecycle.admitFrame(timestamp: 22), "after dispose")
    XCTAssertFalse(lifecycle.admitsSessionCallbacks)
  }
}

final class ARSessionHostTests: XCTestCase {
  private final class FakeSessionDriver: ARSessionDriving {
    enum Call: Equatable {
      case run(resetTracking: Bool)
      case pause
    }
    var calls: [Call] = []
    func run(_ configuration: ARConfiguration, options: ARSession.RunOptions) {
      calls.append(.run(resetTracking: options.contains(.resetTracking)))
    }
    func pause() { calls.append(.pause) }
  }

  private final class RecordingObserver: ARFrameObserver {
    var events: [String] = []
    func frameSource(_ source: ARFrameSource, didUpdate frame: CaptureFrame) { events.append("frame") }
    func frameSource(_ source: ARFrameSource, didStart configuration: CaptureRunConfiguration, epoch: Int) {
      events.append("start \(epoch)")
    }
    func frameSourceDidStop(_ source: ARFrameSource) { events.append("stop") }
  }

  private static let plan = ARSessionConfigurationPlan(
    options: ARSessionOptions(),
    capabilities: ARCameraCapabilities(
      modelIdentifier: "test", worldTrackingSupported: true, sceneDepthSupported: true,
      smoothedSceneDepthSupported: true, sceneReconstructionSupported: true,
      videoFormats: [], highResolutionFrameCaptureFormat: nil))

  private var now: TimeInterval = 0

  private func makeHost(_ driver: FakeSessionDriver, registry: ARFrameSourceRegistry? = nil) -> ARSessionHost {
    ARSessionHost(
      plan: ARSessionHostTests.plan, driver: driver, registry: registry,
      makeConfiguration: { ARWorldTrackingConfiguration() },
      clock: { [unowned self] in self.now })
  }

  func testControlCallsDriveTheSessionOnce() {
    let driver = FakeSessionDriver()
    let host = makeHost(driver)
    host.start()
    host.start()
    host.pause()
    host.pause()
    host.resume()
    host.restart()
    host.dispose()
    host.dispose()
    host.resume()
    XCTAssertEqual(driver.calls, [
      .run(resetTracking: true), .pause, .run(resetTracking: false), .run(resetTracking: true), .pause,
    ])
    XCTAssertEqual(host.epoch, 2)
    XCTAssertEqual(host.lifecycleState, .disposed)
    XCTAssertTrue(host.deliversSceneDepth)
  }

  func testSessionFailuresReachDartAsStableCodes() {
    XCTAssertEqual(
      CaptureViewMessageCode.sessionFailure(ARError(.cameraUnauthorized)), CaptureViewMessageCode.cameraUnauthorized)
    XCTAssertEqual(
      CaptureViewMessageCode.sessionFailure(ARError(.sensorFailed)), CaptureViewMessageCode.sessionFailed)
    XCTAssertEqual(
      CaptureViewMessageCode.sessionFailure(NSError(domain: "test", code: 1)), CaptureViewMessageCode.sessionFailed)
    let codes = [
      CaptureViewMessageCode.metalUnavailable, CaptureViewMessageCode.cameraUnauthorized,
      CaptureViewMessageCode.sessionFailed, CaptureViewMessageCode.stillCaptureUnavailable,
    ]
    XCTAssertEqual(Set(codes).count, codes.count)
    for code in codes {
      XCTAssertNotNil(code.range(of: "^ARKIT_[A-Z_]+$", options: .regularExpression), code)
    }
  }

  func testHostInitAfterPauseLeavesTheSessionPaused() {
    let driver = FakeSessionDriver()
    let host = makeHost(driver)
    host.initialize()
    host.pause()
    host.initialize()
    XCTAssertEqual(driver.calls, [.run(resetTracking: true), .pause])
    XCTAssertEqual(host.lifecycleState, .paused)
    XCTAssertEqual(host.epoch, 1)
    host.dispose()
  }

  func testObserversReceiveStartAndStopOnTheDelegateQueue() {
    let driver = FakeSessionDriver()
    let host = makeHost(driver)
    let observer = RecordingObserver()
    host.addFrameObserver(observer)
    host.start()
    host.pause()
    host.resume()
    host.restart()
    host.delegateQueue.sync {}
    XCTAssertEqual(observer.events, ["start 1", "stop", "start 1", "start 2"])
    host.removeFrameObserver(observer)
    host.dispose()
    host.delegateQueue.sync {}
    XCTAssertEqual(observer.events.count, 4, "removed observers get nothing")
  }

  func testLateObserverOfARunningSessionGetsTheCurrentStart() {
    let driver = FakeSessionDriver()
    let host = makeHost(driver)
    host.start()
    let observer = RecordingObserver()
    host.addFrameObserver(observer)
    host.delegateQueue.sync {}
    XCTAssertEqual(observer.events, ["start 1"])
    host.dispose()
  }

  func testFramesAfterPauseOrDisposeAreRefused() {
    let driver = FakeSessionDriver()
    let host = makeHost(driver)
    host.start()
    XCTAssertTrue(host.admitFrame(timestamp: 1))
    now = 2.5
    host.pause()
    XCTAssertFalse(host.admitFrame(timestamp: 2))
    host.resume()
    XCTAssertFalse(host.admitFrame(timestamp: 2.4), "captured before the pause")
    XCTAssertTrue(host.admitFrame(timestamp: 3))
    host.dispose()
    XCTAssertFalse(host.admitFrame(timestamp: 4))
  }

  func testHostPublishesItselfAndClearsTheRegistryOnDispose() {
    let registry = ARFrameSourceRegistry()
    let driver = FakeSessionDriver()
    let host = makeHost(driver, registry: registry)
    XCTAssertTrue(registry.current === host)
    host.dispose()
    XCTAssertNil(registry.current)
  }

  func testDisposingAnOlderHostKeepsTheNewerRegistryEntry() {
    let registry = ARFrameSourceRegistry()
    let older = makeHost(FakeSessionDriver(), registry: registry)
    let newer = makeHost(FakeSessionDriver(), registry: registry)
    older.dispose()
    XCTAssertTrue(registry.current === newer)
    newer.dispose()
  }

  func testReleasingARunningHostPausesTheSession() {
    let driver = FakeSessionDriver()
    var host: ARSessionHost? = makeHost(driver)
    host?.start()
    // The start posts an observer block to the delegate queue that holds
    // the host while it runs; drain it so the release below is the last
    // reference and `deinit` runs here, not later on that queue.
    host?.delegateQueue.sync {}
    weak var released = host
    host = nil
    XCTAssertNil(released, "nothing else keeps a running host alive")
    XCTAssertEqual(driver.calls, [.run(resetTracking: true), .pause])
  }
}
