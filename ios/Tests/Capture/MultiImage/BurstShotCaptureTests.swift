import ARKit
import XCTest
@testable import ar_flutter_plugin_2

/// Burst and quality shots inside the running AR session (the simulator has
/// no ARKit camera: a frame source fake answers the still requests): every
/// burst image comes from its own frame with its own pose, intrinsics and
/// timestamp; an image taken while tracking is limited is kept and flagged;
/// quality asks for balanced multi-frame processing; Auto stays the fast
/// single still; a replay's repeated nearest still ends the burst; a
/// recording without stills keeps shots single; video HDR is a creation
/// option.
final class BurstShotCaptureTests: XCTestCase {
  private func qualifier() -> CaptureAttemptQualifier {
    CaptureAttemptQualifier(identity: CaptureFixture.request().accepted.identity, exposureGeneration: 1)
  }

  private func source(_ frames: [CaptureFrame]) -> StillFrameSource {
    let source = StillFrameSource()
    source.frames = frames
    return source
  }

  private func expose(_ exposure: HighResolutionStillExposure, lane: CaptureLane = .manual) throws -> StillComponentSet {
    let done = expectation(description: "exposure")
    var outcome: StillExposureOutcome?
    XCTAssertTrue(exposure.requestExposure(qualifier(), required: [.jpeg], lane: lane) {
      outcome = $0
      done.fulfill()
    })
    wait(for: [done], timeout: 10)
    guard case .components(let set)? = outcome else {
      if case .failure(_, let reason)? = outcome { XCTFail("exposure failed: \(reason)") }
      throw StillCaptureFailure(reason: "no components")
    }
    return set
  }

  func testCancelledQueuedAttemptsNeverRequestAnotherPhysicalFrame() throws {
    let source = StillFrameSource()
    let access = StillCameraAccess()
    defer { access.close {} }
    let exposure = HighResolutionStillExposure(frameSource: { source }, jpegQuality: 90, cameraAccess: access)
    let firstRequested = expectation(description: "first physical frame pending")
    var firstFrame: ((CaptureFrame?, Error?) -> Void)?
    source.interceptStill = { callback in firstFrame = callback; firstRequested.fulfill() }
    let completed = expectation(description: "all attempts complete")
    completed.expectedFulfillmentCount = 21
    let first = CaptureAttemptQualifier(identity: CaptureFixture.request().accepted.identity, exposureGeneration: 1)
    XCTAssertTrue(exposure.requestExposure(first, required: [.jpeg]) { _ in completed.fulfill() })
    wait(for: [firstRequested], timeout: 2)
    // More than the historical cancellation ring's sixteen entries: each
    // queued request must retain its own cancellation until it leaves camera
    // ownership, even if the native admission fence already released it.
    for index in 2...21 {
      let qualifier = CaptureAttemptQualifier(
        identity: CaptureFixture.request(attemptId: "queued-\(index)", commitId: "commit-\(index)", ordinal: Int64(index)).accepted.identity,
        exposureGeneration: Int64(index))
      XCTAssertTrue(exposure.requestExposure(qualifier, required: [.jpeg]) { outcome in
        if case .failure(_, let reason) = outcome { XCTAssertEqual(reason, "cancelled") }
        else { XCTFail("cancelled request acquired another physical frame") }
        completed.fulfill()
      })
      exposure.cancelExposure(qualifier)
    }
    source.interceptStill = nil
    let frame = ShotFixture.frame(timestamp: ProcessInfo.processInfo.systemUptime, pose: ShotFixture.pose())
    source.frames = [frame]
    try XCTUnwrap(firstFrame)(frame, nil)
    wait(for: [completed], timeout: 5)
    XCTAssertEqual(source.qualities.count, 1, "logical cancellation cannot schedule another physical still")
  }

  func testExactGeometryRejectsFallbackAndMismatchesForEveryExposureModeAndLane() {
    for mode in StillCaptureMode.allCases {
      for lane in [CaptureLane.manual, .automatic] {
        for missing in [false, true] {
          let source = StillFrameSource()
          source.refreshCurrentVideoFrame = true
          if !missing { source.frames = [ShotFixture.frame(timestamp: 100, pose: ShotFixture.pose())] }
          let exposure = HighResolutionStillExposure(frameSource: { source }, jpegQuality: 95,
            exactStillResolution: CGSize(width: 128, height: 96), deviceModel: "fixture")
          exposure.mode = mode
          exposure.burst = BurstSettings(imageCount: 2, intervalMs: 0)
          let completed = expectation(description: "strict \(mode) \(lane) \(missing)")
          XCTAssertTrue(exposure.requestExposure(qualifier(), required: [.jpeg], lane: lane) { result in
            guard case .failure(_, let reason) = result else {
              XCTFail("Exact selection must not return successful components")
              return completed.fulfill()
            }
            XCTAssertEqual(reason, missing ? "high-resolution-frame-unavailable" : "still-resolution-mismatch")
            completed.fulfill()
          })
          wait(for: [completed], timeout: 10)
          XCTAssertEqual(exposure.snapshot.highResolution, 0)
          XCTAssertEqual(exposure.snapshot.videoFallback, 0)
          XCTAssertEqual(exposure.snapshot.failures, 1)
        }
      }
    }
  }

  func testWrongLaterBurstGeometryFailsTheWholeExplicitShot() {
    let source = StillFrameSource()
    source.frames = [
      ShotFixture.frame(timestamp: 100, pose: ShotFixture.pose()),
      ShotFixture.frame(timestamp: 100.15, pose: ShotFixture.pose(), width: 128, height: 96),
    ]
    let exposure = HighResolutionStillExposure(frameSource: { source }, jpegQuality: 95,
      exactStillResolution: CGSize(width: 64, height: 48), deviceModel: "fixture")
    exposure.mode = .burst
    exposure.burst = BurstSettings(imageCount: 2, intervalMs: 0)
    let completed = expectation(description: "mixed burst is not a successful partial component set")
    XCTAssertTrue(exposure.requestExposure(qualifier(), required: [.jpeg], lane: .manual) { outcome in
      if case .failure(_, let reason) = outcome { XCTAssertEqual(reason, "still-resolution-mismatch") }
      else { XCTFail("A partial matching image must not hide later geometry failure") }
      completed.fulfill()
    })
    wait(for: [completed], timeout: 10)
    XCTAssertEqual(exposure.snapshot.highResolution, 1)
    XCTAssertEqual(exposure.snapshot.failures, 1)
  }

  func testABurstTakesEveryImageFromItsOwnFrame() throws {
    let frames = (0..<3).map { i in
      ShotFixture.frame(timestamp: 100 + 0.2 * Double(i), pose: ShotFixture.pose(x: 1 + 0.02 * Float(i), y: 2, z: 3))
    }
    let source = source(frames)
    var feedback = 0
    let exposure = HighResolutionStillExposure(frameSource: { source }, jpegQuality: 90, deviceModel: "test", shutterFeedback: {
      XCTAssertEqual(source.qualities.count, 3, "no motor pulse while later burst frames remain")
      feedback += 1
    })
    exposure.mode = .burst
    exposure.burst = BurstSettings(imageCount: 3, intervalMs: 50)
    let set = try expose(exposure)
    let shot = try XCTUnwrap(set.shot)
    XCTAssertEqual(shot.mode, .burst)
    XCTAssertEqual(shot.images.map { $0.index }, [0, 1, 2])
    XCTAssertEqual(shot.images.map { $0.metadata.exposureTimestampNs }, [100_000_000_000, 100_200_000_000, 100_400_000_000])
    for (image, x) in zip(shot.images, [Float(1), 1.02, 1.04]) {
      XCTAssertEqual(image.metadata.cameraToWorld?.columns.3.x ?? 0, x, accuracy: 1e-6, "image \(image.index) has its own pose")
    }
    XCTAssertTrue(shot.images.allSatisfy { $0.trackingNormal && $0.flags.isEmpty && $0.creditable })
    XCTAssertTrue(shot.images.allSatisfy { $0.metadata.intrinsics != nil })
    XCTAssertEqual(set.components.map { $0.0 }, [.jpeg])
    XCTAssertEqual(feedback, 1, "one confirmation for the terminal logical burst before storage")
    XCTAssertEqual(set.components.first?.1, shot.first.jpeg, "the first image is the attempt's JPEG")
    XCTAssertEqual(set.metadata.exposureTimestampNs, shot.first.metadata.exposureTimestampNs)
    XCTAssertEqual(source.qualities, [.fast, .fast, .fast])
    let gaps = zip(source.requestTimes.dropFirst(), source.requestTimes).map { $0 - $1 }
    XCTAssertTrue(gaps.allSatisfy { $0 >= 0.045 }, "the interval passes between images: \(gaps)")
    XCTAssertEqual(shot.payload?.images.map { $0.index }, [1, 2])
    XCTAssertEqual(shot.payload?.images.map { $0.role }, ["BURST", "BURST"])
  }

  func testAnImageTakenWhileTrackingIsLimitedIsKeptAndFlagged() throws {
    let frames = [
      ShotFixture.frame(timestamp: 10, pose: ShotFixture.pose(x: 1)),
      ShotFixture.frame(timestamp: 10.2, pose: ShotFixture.pose(x: 1.01), tracking: .limited(.excessiveMotion)),
    ]
    let limited = source(frames)
    let exposure = HighResolutionStillExposure(frameSource: { limited }, jpegQuality: 90, deviceModel: "test")
    exposure.mode = .burst
    exposure.burst = BurstSettings(imageCount: 2, intervalMs: 0)
    let shot = try XCTUnwrap(try expose(exposure).shot)
    XCTAssertEqual(shot.images.count, 2)
    XCTAssertTrue(shot.images[0].creditable)
    XCTAssertFalse(shot.images[1].trackingNormal)
    XCTAssertEqual(shot.images[1].flags, [.trackingNotNormal])
    XCTAssertFalse(shot.images[1].creditable)
    XCTAssertNotNil(shot.images[1].metadata.cameraToWorld, "the limited frame's pose stays in the shot record")
  }

  func testAQualityShotAsksForBalancedProcessingOnce() throws {
    let source = source([ShotFixture.frame(timestamp: 20, pose: ShotFixture.pose(x: 2))])
    let exposure = HighResolutionStillExposure(frameSource: { source }, jpegQuality: 90, deviceModel: "test")
    exposure.mode = .inArQuality
    let shot = try XCTUnwrap(try expose(exposure).shot)
    XCTAssertEqual(shot.mode, .quality)
    XCTAssertEqual(shot.images.count, 1)
    XCTAssertNil(shot.payload, "one image: a plain durable commit")
    XCTAssertEqual(source.qualities, [.balanced])
  }

  func testAutomaticCapturesTakeTheFastSingleStill() throws {
    let source = source([ShotFixture.frame(timestamp: 30, pose: ShotFixture.pose(x: 3))])
    let exposure = HighResolutionStillExposure(frameSource: { source }, jpegQuality: 90, deviceModel: "test")
    exposure.mode = .burst
    let set = try expose(exposure, lane: .automatic)
    XCTAssertNil(set.shot)
    XCTAssertEqual(source.qualities, [.fast])
  }

  func testARepeatedExposureEndsTheBurst() throws {
    // A replay answers close requests with the same nearest recorded still.
    let source = source([ShotFixture.frame(timestamp: 40, pose: ShotFixture.pose(x: 4))])
    source.replaysRecordedStills = true
    var feedback = 0
    let exposure = HighResolutionStillExposure(frameSource: { source }, jpegQuality: 90, deviceModel: "test", shutterFeedback: { feedback += 1 })
    exposure.mode = .burst
    exposure.burst = BurstSettings(imageCount: 4, intervalMs: 0)
    let shot = try XCTUnwrap(try expose(exposure).shot)
    XCTAssertEqual(shot.images.count, 1)
    XCTAssertEqual(source.qualities.count, 2, "the second request returned the same still")
    XCTAssertEqual(feedback, 0, "playback is not a physical exposure")
  }

  func testShutterFeedbackPrecedesEncodingAndDoesNotConfirmMissingOrCancelledFrames() {
    var feedback = 0
    let source = source([ShotFixture.frame(timestamp: 60, pose: ShotFixture.pose(x: 1))])
    let exposure = HighResolutionStillExposure(frameSource: { source }, jpegQuality: 90, deviceModel: "test", shutterFeedback: { feedback += 1 })
    let done = expectation(description: "encoded")
    XCTAssertTrue(exposure.captureStill { result in
      XCTAssertEqual(feedback, 1)
      done.fulfill()
    })
    wait(for: [done], timeout: 10)
    let cancelled = expectation(description: "cancelled")
    XCTAssertTrue(exposure.captureStill(isCancelled: { true }) { _ in cancelled.fulfill() })
    wait(for: [cancelled], timeout: 10)
    XCTAssertEqual(feedback, 1)
    let missing = HighResolutionStillExposure(frameSource: { nil }, jpegQuality: 90, shutterFeedback: { feedback += 1 })
    XCTAssertFalse(missing.captureStill { _ in XCTFail("unavailable source must reject before exposure") })
    XCTAssertEqual(feedback, 1)
  }

  func testLiveBurstThatEndsEarlyConfirmsOnlyItsTerminalShot() throws {
    let source = source([ShotFixture.frame(timestamp: 70, pose: ShotFixture.pose(x: 1))])
    var feedback = 0
    let exposure = HighResolutionStillExposure(frameSource: { source }, jpegQuality: 90, deviceModel: "test", shutterFeedback: {
      XCTAssertEqual(source.qualities.count, 2, "the repeated second frame ends acquisition before feedback")
      feedback += 1
    })
    exposure.mode = .burst
    exposure.burst = BurstSettings(imageCount: 4, intervalMs: 0)
    let shot = try XCTUnwrap(try expose(exposure).shot)
    XCTAssertEqual(shot.images.count, 1)
    XCTAssertEqual(feedback, 1)
  }

  func testRecordedPlaybackUsesTheNearestRecordedStillAndKeepsShotsSingleWithoutStills() throws {
    let room2 = try RecordedSessionFixture.room2URL()
    let recorded = RecordedFrameSource(
      session: try RecordedSession(directory: room2),
      config: RecordedPlaybackConfig(directory: room2, restart: .off, restartGeneration: 0),
      options: ARSessionOptions(), registry: nil)
    defer { recorded.dispose() }
    XCTAssertTrue(recorded.replaysRecordedStills)
    let exposure = HighResolutionStillExposure(frameSource: { recorded }, jpegQuality: 90, deviceModel: "test")
    exposure.mode = .burst
    exposure.burst = BurstSettings(imageCount: 3, intervalMs: 0)
    let shot = try XCTUnwrap(try expose(exposure).shot)
    XCTAssertEqual(shot.images.count, 1, "the not yet started replay answers every request with one recorded still")
    XCTAssertTrue(shot.first.metadata.highResolution)
    XCTAssertGreaterThan(shot.first.metadata.width, 0, "the recorded still (trimmed fixture: 252 px)")

    XCTAssertEqual(StillCaptureMode.effective(.burst, replaysRecordedStills: true, highResolution: false).mode, .single)
    XCTAssertEqual(StillCaptureMode.effective(.inArQuality, replaysRecordedStills: true, highResolution: false).refusal,
                   "recordedPlaybackWithoutStills")
    XCTAssertNil(StillCaptureMode.effective(.burst, replaysRecordedStills: true, highResolution: true).refusal)
    XCTAssertNil(StillCaptureMode.effective(.burst, replaysRecordedStills: false, highResolution: false).refusal,
                 "a live session falls back per image to the video frame")
  }

  func testStillModesAndBurstSettingsParse() {
    XCTAssertEqual(StillCaptureMode.parse(nil), .single)
    XCTAssertEqual(StillCaptureMode.parse("burst"), .burst)
    XCTAssertEqual(StillCaptureMode.parse("inArQuality"), .inArQuality)
    XCTAssertNil(StillCaptureMode.parse("multiCameraShot"), "no capture mode that pauses AR exists")
    XCTAssertEqual(BurstSettings.parse([:]), BurstSettings(imageCount: 3, intervalMs: 150))
    XCTAssertEqual(BurstSettings.parse(["burstImageCount": 5, "burstIntervalMs": 0]), BurstSettings(imageCount: 5, intervalMs: 0))
    XCTAssertNil(BurstSettings.parse(["burstImageCount": 6]))
    XCTAssertNil(BurstSettings.parse(["burstImageCount": 1]))
    XCTAssertNil(BurstSettings.parse(["burstIntervalMs": 1_001]))
  }

  func testVideoHdrIsACreationOptionOfTheSessionPlan() {
    XCTAssertFalse(ARSessionOptions(creationParams: [:]).videoHdr)
    let options = ARSessionOptions(creationParams: [ARCaptureViewParameter.videoHdr: true])
    XCTAssertTrue(options.videoHdr)
    let capabilities = ARCameraCapabilities(
      modelIdentifier: "test", worldTrackingSupported: true, sceneDepthSupported: true,
      smoothedSceneDepthSupported: true, sceneReconstructionSupported: true, videoFormats: [],
      highResolutionFrameCaptureFormat: nil)
    XCTAssertTrue(ARSessionConfigurationPlan(options: options, capabilities: capabilities).videoHdr)
    XCTAssertFalse(ARSessionConfigurationPlan(options: ARSessionOptions(), capabilities: capabilities).videoHdr)
  }
}
